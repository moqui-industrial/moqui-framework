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
import org.moqui.entity.EntityCondition
import org.moqui.impl.context.UserFacadeImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Conservative owner-scoped recovery; never repeats an external effect with an uncertain outcome. */
final class LlmRecoveryWorker {
    private static final Logger logger = LoggerFactory.getLogger(LlmRecoveryWorker.class)
    static Map<String, Object> recoverAll(ExecutionContext ec, String workerId, int leaseSeconds, int limit) {
        String originalUsername = ec.user.username
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        List<Map<String, Object>> rows
        try {
            EntityCondition expiredLease = ec.entity.conditionFactory.makeCondition([
                    ec.entity.conditionFactory.makeCondition('leaseUntil', EntityCondition.EQUALS, null),
                    ec.entity.conditionFactory.makeCondition('leaseUntil', EntityCondition.LESS_THAN_EQUAL_TO, ec.user.nowTimestamp)
            ], EntityCondition.OR)
            rows = ec.entity.find('moqui.llm.LlmRun')
                    .condition('statusId', EntityCondition.IN, [LlmRunStore.RUNNING, LlmRunStore.RECOVERING])
                    .condition(expiredLease)
                    .orderBy(['lastUpdatedDate', 'runId']).limit(Math.max(limit, 1)).useCache(false).list()
                    .collect { [runId:it.runId, userId:it.userId] }
        } finally {
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
        List<Map<String, Object>> queued = orphanedQueued(ec, limit)
        List<Map<String, Object>> ownerResults = []
        try {
            for (String userId : (rows*.userId + queued*.userId).findAll { it }.unique()) {
                EntityCondition leaseCondition = ec.entity.conditionFactory.makeCondition([
                        ec.entity.conditionFactory.makeCondition('leaseUntil', EntityCondition.EQUALS, null),
                        ec.entity.conditionFactory.makeCondition('leaseUntil', EntityCondition.LESS_THAN_EQUAL_TO, ec.user.nowTimestamp)
                ], EntityCondition.OR)
                boolean disabled = ec.artifactExecution.disableAuthz()
                def user
                try {
                    boolean hasExpired = ec.entity.find('moqui.llm.LlmRun').condition('userId', userId)
                            .condition('statusId', EntityCondition.IN, [LlmRunStore.RUNNING, LlmRunStore.RECOVERING])
                            .condition(leaseCondition).useCache(false).count() > 0
                    if (!hasExpired && !queued.any { it.userId == userId }) continue
                    user = ec.entity.find('moqui.security.UserAccount').condition('userId', userId)
                            .selectField('username').useCache(false).one()
                } finally {
                    if (!disabled) ec.artifactExecution.enableAuthz()
                }
                if (user?.username && ((UserFacadeImpl) ec.user).internalLoginUser(user.username as String, false)) {
                    Map<String, Object> ownerResult = recover(ec, "${workerId}:${userId}", leaseSeconds, limit)
                    ownerResult.dispatched = redispatch(ec, queued.findAll { it.userId == userId }*.runId)
                    ownerResults.add([userId:userId, result:ownerResult])
                    ec.user.logoutUser()
                }
            }
        } finally {
            if (originalUsername && !ec.user.username)
                ((UserFacadeImpl) ec.user).internalLoginUser(originalUsername, false)
        }
        [owners:ownerResults, ownerCount:ownerResults.size()]
    }

    /** Queued runs whose job is gone: never started, or its ServiceJobRun already ended while the run stayed queued. */
    static List<Map<String, Object>> orphanedQueued(ExecutionContext ec, int limit) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        try {
            java.sql.Timestamp cutoff = new java.sql.Timestamp(ec.user.nowTimestamp.time - QUEUED_GRACE_SECONDS * 1000L)
            List<Map<String, Object>> out = []
            ec.entity.find('moqui.llm.LlmRun').condition('statusId', LlmRunStore.QUEUED)
                    .condition('lastUpdatedDate', EntityCondition.LESS_THAN_EQUAL_TO, cutoff)
                    .orderBy(['lastUpdatedDate', 'runId']).limit(Math.max(limit, 1)).useCache(false).list().each { run ->
                boolean alive = false
                if (run.jobRunId) {
                    def jobRun = ec.entity.find('moqui.service.job.ServiceJobRun').condition('jobRunId', run.jobRunId)
                            .useCache(false).one()
                    alive = jobRun != null && jobRun.endTime == null
                }
                if (!alive) out.add([runId:run.runId, userId:run.userId])
            }
            out
        } finally {
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
    }
    static final int QUEUED_GRACE_SECONDS = 120

    static List<String> redispatch(ExecutionContext ec, List<String> runIds) {
        List<String> started = []
        for (String runId : runIds) {
            try {
                LlmRunExecutor.dispatch(ec, runId)
                started.add(runId)
            } catch (Throwable t) {
                logger.warn("Could not re-dispatch queued LLM run ${runId}: ${t.message}")
            }
        }
        started
    }

    static Map<String, Object> recover(ExecutionContext ec, String workerId, int leaseSeconds, int limit) {
        List<Map<String, Object>> claimed = LlmRunStore.claimRecoverable(ec, workerId, leaseSeconds, limit)
        List<Map<String, Object>> recovered = []
        List<Map<String, Object>> waiting = []
        List<Map<String, Object>> failed = []
        for (Map<String, Object> run : claimed) {
            try {
                List<Map<String, Object>> invocations = LlmRunStore.listToolInvocations(ec, run.runId as String)
                List<Map<String, Object>> runningTools = invocations.findAll { it.statusId == 'LlmTiRunning' }
                if (runningTools) {
                    runningTools.each { invocation ->
                        LlmRunStore.markToolUncertain(ec, invocation.toolInvocationId as String,
                                'Worker stopped after external tool execution began; reconciliation required')
                    }
                    waiting.add(LlmRunStore.transition(ec, run.runId as String, LlmRunStore.WAIT_CONFIRM,
                            'Tool outcome uncertain; automatic retry is forbidden'))
                    continue
                }
                if (invocations.any { it.statusId in ['LlmTiPlanned', 'LlmTiUncertain'] }) {
                    waiting.add(LlmRunStore.transition(ec, run.runId as String, LlmRunStore.WAIT_CONFIRM,
                            'Tool invocation requires explicit reconciliation'))
                    continue
                }
                String phase = run.checkpoint instanceof Map ? run.checkpoint.phase as String : null
                if (phase != 'ready_provider') {
                    waiting.add(LlmRunStore.transition(ec, run.runId as String, LlmRunStore.WAIT_CONFIRM,
                            phase == 'provider_in_flight'
                                    ? 'Provider request outcome is uncertain; automatic replay is forbidden'
                                    : 'Checkpoint cannot be replayed safely without operator confirmation'))
                    continue
                }
                String unrecoverable = LlmClientImpl.unrecoverableReason(run)
                if (unrecoverable) {
                    waiting.add(LlmRunStore.transition(ec, run.runId as String, LlmRunStore.WAIT_CONFIRM, unrecoverable))
                    continue
                }
                LlmRunStore.transition(ec, run.runId as String, LlmRunStore.RUNNING, 'Recovery resumed before provider send')
                LlmClientImpl client = (LlmClientImpl) ec.llm.getClient(run.profileName as String)
                client.attachRecoveredRun(run)
                client.inputItems(OpenResponsesCodec.itemsFromStored(run.context))
                recovered.add([runId: run.runId, response: client.call()])
            } catch (Throwable error) {
                try {
                    failed.add(LlmRunStore.transition(ec, run.runId as String, LlmRunStore.FAILED, error.message))
                } catch (Throwable ignored) {
                    failed.add([runId: run.runId, error: error.message])
                }
            }
        }
        [claimed: claimed.size(), recovered: recovered, waitingConfirmation: waiting, failed: failed]
    }
}
