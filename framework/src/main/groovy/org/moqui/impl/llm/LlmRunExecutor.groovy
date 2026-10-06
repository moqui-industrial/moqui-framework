/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.impl.llm

import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.context.UserFacadeImpl
import org.moqui.llm.LlmContentPart
import org.moqui.llm.LlmItem
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Runs a durable LlmRun outside any HTTP request. A ServiceJob (execute_LlmRun) calls execute(); the run, not the job,
 * is the source of truth: it carries the trajectory, lease and fencing token, so a lost job is re-dispatched by
 * LlmRecoveryWorker. See framework/plans/LlmRunBackgroundExecution.md.
 */
class LlmRunExecutor {
    private static final Logger logger = LoggerFactory.getLogger(LlmRunExecutor.class)
    static final String JOB_NAME = 'execute_LlmRun'
    static final int LEASE_SECONDS = 60
    /** Tools attached when the profile allows them; typed services come from the profile's allowed-service list. */
    static final List<String> BUILTIN_TOOLS = ['find_skill', 'browse', 'run_service', 'enter_sim', 'find_basic']
    private static final Map<String, Semaphore> PERMITS = new ConcurrentHashMap<>()

    /** Creates a queued run for the current user from an objective and starts its job. */
    static Map<String, Object> start(ExecutionContext ec, Map<String, Object> request) {
        String objective = request.objective as String
        if (!objective?.trim()) throw new IllegalArgumentException('objective is required')
        Map<String, Object> create = new LinkedHashMap<>(request)
        create.context = [LlmItem.message('user', [LlmContentPart.inputText(objective)])]
        create.checkpoint = [phase: 'ready_provider']
        create.continuationModeEnumId = 'LlmContLocal'
        Map<String, Object> run = LlmRunStore.createRun(ec, create)
        dispatch(ec, run.runId as String)
        LlmRunStore.getRun(ec, run.runId as String)
    }

    /** Starts execute_LlmRun for a queued run. The job always runs as the run owner, whoever calls this. */
    static String dispatch(ExecutionContext ec, String runId, boolean anyOwner = false) {
        Map<String, Object> run = LlmRunStore.getRun(ec, runId, anyOwner)
        if (run.statusId != LlmRunStore.QUEUED) throw new IllegalStateException("LLM run ${runId} is ${run.statusId}, not queued")
        String jobRunId = null
        asUser(ec, run.userId as String) {
            jobRunId = ec.service.job(JOB_NAME).parameter('runId', runId).run()
        }
        LlmRunStore.linkJobRun(ec, runId, jobRunId, anyOwner)
        jobRunId
    }

    /** Body of the execute_LlmRun job. Returns a summary; throws when the run failed so the job records the error. */
    static Map<String, Object> execute(ExecutionContext ec, String runId) {
        LlmGateway.requireLlmGateway(ec)
        Map<String, Object> run = LlmRunStore.getRun(ec, runId)
        if (run.statusId != LlmRunStore.QUEUED) return summary(run, false, "run is ${run.statusId}, not queued")
        LlmFacadeImpl.ProfileState profile = ((LlmFacadeImpl) ec.llm).getProfileState(run.profileName as String)
        if (profile == null) return fail(ec, run, "No LLM profile named '${run.profileName}'")
        if (run.continuationModeEnumId == 'LlmContRemote')
            return fail(ec, run, 'Background execution supports local item trajectories only (LlmContLocal)')
        if (run.deadline instanceof Date && ((Date) run.deadline).before(new Date(ec.user.nowTimestamp.time)))
            return fail(ec, run, 'Run deadline passed before execution started')

        Semaphore permits = permitsFor(profile)
        if (!permits.tryAcquire()) return summary(run, false, 'concurrency limit reached; run stays queued')
        try {
            String workerId = "execute:${UUID.randomUUID()}"
            Map<String, Object> claimed = LlmRunStore.claimQueued(ec, runId, workerId, LEASE_SECONDS)
            if (claimed == null) return summary(LlmRunStore.getRun(ec, runId), false, 'run was not claimable')
            long fence = ((claimed.fencingToken ?: 0) as Number).longValue()
            Heartbeat heartbeat = new Heartbeat((ExecutionContextFactoryImpl) ec.factory, ec.user.username, runId,
                    workerId, fence)
            heartbeat.start()
            Throwable failure = null
            String content = null
            try {
                content = LlmGateway.withoutCallerTx(ec) { runLoop(ec, claimed, profile) }
            } catch (Throwable t) {
                failure = t
            } finally {
                heartbeat.stop()
            }
            Map<String, Object> after = LlmRunStore.getRun(ec, runId)
            if (failure != null && !isCancellation(failure) && !terminal(after.statusId as String)) {
                try { LlmRunStore.transition(ec, runId, LlmRunStore.FAILED, failure.message) }
                catch (Throwable ignored) { logger.warn("Could not mark LLM run ${runId} failed: ${ignored.message}") }
            }
            try { LlmRunStore.releaseLease(ec, runId, workerId, fence) }
            catch (Throwable t) { logger.warn("Could not release lease of LLM run ${runId}: ${t.message}") }
            after = LlmRunStore.getRun(ec, runId)
            if (failure != null && !isCancellation(failure)) throw failure
            Map<String, Object> out = summary(after, true, null)
            if (content) out.content = content.length() > 4000 ? content.substring(0, 4000) : content
            return out
        } finally {
            permits.release()
        }
    }

    private static String runLoop(ExecutionContext ec, Map<String, Object> run, LlmFacadeImpl.ProfileState profile) {
        LlmClientImpl client = (LlmClientImpl) ec.llm.getClient(run.profileName as String)
        client.attachRecoveredRun(run)
        List<LlmItem> items = OpenResponsesCodec.itemsFromStored(run.context)
        if (items.isEmpty() && run.objective) client.user(run.objective as String)
        else client.inputItems(items)
        if (run.maxIterations) client.maxIterations(((Number) run.maxIterations).intValue())
        LlmGateway.applySystem(client, [:])
        LlmGateway.attachServletTools(client, profile, BUILTIN_TOOLS)
        for (LlmFacadeImpl.ServiceAllow allowed : profile.allowedServices)
            client.tool(new ServiceCallTool(allowed.serviceName, allowed.functionName, allowed.description))
        org.moqui.llm.LlmResponse response = client.call()
        // a yielded response means the run is waiting; there is no final text yet
        response != null && !response.yielded ? response.content : null
    }

    private static Map<String, Object> fail(ExecutionContext ec, Map<String, Object> run, String message) {
        LlmRunStore.transition(ec, run.runId as String, LlmRunStore.FAILED, message)
        throw new IllegalStateException(message)
    }

    private static Map<String, Object> summary(Map<String, Object> run, boolean executed, String reason) {
        Map<String, Object> out = [runId: run.runId, statusId: run.statusId, executed: executed,
                iteration: run.iteration] as Map<String, Object>
        if (reason) out.reason = reason
        out
    }

    private static boolean terminal(String statusId) {
        statusId in [LlmRunStore.COMPLETE, LlmRunStore.FAILED, LlmRunStore.CANCELLED]
    }

    private static boolean isCancellation(Throwable t) {
        t instanceof java.util.concurrent.CancellationException || LlmConversationImpl.isCancelThrowable(t)
    }

    /** One permit pool per profile, sized from the profile's pool-max; the job pool is shared with other jobs. */
    private static Semaphore permitsFor(LlmFacadeImpl.ProfileState profile) {
        int size = 16
        try { size = Integer.parseInt(profile.confNode?.attribute('pool-max') ?: '16') } catch (NumberFormatException ignored) { }
        PERMITS.computeIfAbsent(profile.name + ':' + size) { new Semaphore(Math.max(size, 1)) }
    }

    /**
     * For a service called as a tool inside the agent loop: puts the current run into WAIT_CONFIRM or WAIT_CLIENT.
     * The loop notices after the tool round, stops without finishing the run, and the job ends.
     */
    static Map<String, Object> suspendCurrent(ExecutionContext ec, String statusId, String message) {
        if (statusId != LlmRunStore.WAIT_CONFIRM && statusId != LlmRunStore.WAIT_CLIENT)
            throw new IllegalArgumentException("statusId must be ${LlmRunStore.WAIT_CONFIRM} or ${LlmRunStore.WAIT_CLIENT}")
        LlmClientImpl client = LlmAgentLoop.currentClient()
        if (client == null || client.activeRunId == null)
            throw new IllegalStateException('Not called from inside a durable LLM run')
        LlmRunStore.transition(ec, client.activeRunId, statusId, message)
    }

    /**
     * The durable run the calling tool service belongs to, for services that must only act inside an agent run.
     * Throws when called from anywhere else (screen, REST, another thread).
     */
    static Map<String, Object> currentRun(ExecutionContext ec) {
        LlmClientImpl client = LlmAgentLoop.currentClient()
        if (client == null || client.activeRunId == null)
            throw new IllegalStateException('Not called from inside a durable LLM run')
        Map<String, Object> run = LlmRunStore.getRun(ec, client.activeRunId)
        if (run.userId != ec.user.userId) throw new IllegalStateException('The run belongs to another user')
        run
    }

    /** Runs the closure as another user (the run owner) and restores the caller afterwards. */
    static void asUser(ExecutionContext ec, String userId, Closure work) {
        if (ec.user.userId == userId) { work.call(); return }
        String original = ec.user.username
        boolean disabled = ec.artifactExecution.disableAuthz()
        String username
        try {
            username = ec.entity.find('moqui.security.UserAccount').condition('userId', userId)
                    .selectField('username').useCache(false).one()?.username
        } finally {
            if (!disabled) ec.artifactExecution.enableAuthz()
        }
        if (!username || !((UserFacadeImpl) ec.user).internalLoginUser(username, false))
            throw new IllegalStateException("Cannot act as the owner of the LLM run (user ${userId})")
        try { work.call() }
        finally {
            ec.user.logoutUser()
            if (original) ((UserFacadeImpl) ec.user).internalLoginUser(original, false)
        }
    }

    /** Keeps the lease of a running run alive so the recovery job does not take it over. */
    static class Heartbeat implements Runnable {
        private final ExecutionContextFactoryImpl ecfi
        private final String username, runId, workerId
        private final long fence
        private final ScheduledExecutorService scheduler

        Heartbeat(ExecutionContextFactoryImpl ecfi, String username, String runId, String workerId, long fence) {
            this.ecfi = ecfi; this.username = username; this.runId = runId; this.workerId = workerId; this.fence = fence
            this.scheduler = Executors.newSingleThreadScheduledExecutor({ Runnable r ->
                Thread t = new Thread(r, "llm-run-heartbeat-${runId}"); t.daemon = true; t } as java.util.concurrent.ThreadFactory)
        }
        void start() {
            long period = Math.max((long) (LEASE_SECONDS / 3), 1L)
            scheduler.scheduleAtFixedRate(this, period, period, TimeUnit.SECONDS)
        }
        void stop() { scheduler.shutdownNow() }

        @Override void run() {
            ExecutionContextImpl tec = null
            try {
                tec = ecfi.getEci()
                if (!((UserFacadeImpl) tec.user).internalLoginUser(username, false)) return
                LlmRunStore.renewLease(tec, runId, workerId, fence, LEASE_SECONDS)
            } catch (Throwable t) {
                logger.warn("LLM run ${runId} lease renewal failed: ${t.message}")
            } finally {
                tec?.destroy()
            }
        }
    }
}
