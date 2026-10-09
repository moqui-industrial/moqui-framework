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
import org.moqui.entity.EntityValue
import org.moqui.impl.context.UserFacadeImpl
import org.moqui.llm.LlmFinishReason
import org.moqui.llm.LlmProtocol

/**
 * Completes the runs that wait for a provider to finish a background response. Only a provider that has an operation to
 * read a response (the RETRIEVE capability) is polled; for any other the run keeps the reference and nothing is invented.
 * One poller at a time holds a run (lease and fence), looks at the provider only when the run says it is due, gives up and
 * asks the provider to cancel at the deadline, and never makes a request that creates a response. A response that
 * finishes with an answer completes the run and its conversation like a turn that ended normally; one that asks for
 * tools is kept for a person to continue, never run by the poller; one that failed or was cancelled ends the run.
 */
final class LlmBackgroundPoller {
    static final int MAX_CONSECUTIVE_ERRORS = 5

    private LlmBackgroundPoller() { }

    /** As the owner of each waiting run, like the recovery of interrupted runs. */
    static Map<String, Object> pollAll(ExecutionContext ec, String workerId, int leaseSeconds, int limit) {
        String originalUsername = ec.user.username
        boolean already = ec.artifactExecution.disableAuthz()
        List<String> owners
        try {
            owners = ec.entity.find('moqui.llm.LlmRun').condition('statusId', LlmRunStore.WAIT_PROVIDER).selectField('userId')
                    .distinct(true).useCache(false).list().collect { it.userId as String }.findAll { it }
        } finally { if (!already) ec.artifactExecution.enableAuthz() }
        List<Map<String, Object>> results = []
        try {
            for (String userId : owners) {
                boolean disabled = ec.artifactExecution.disableAuthz()
                def user
                try { user = ec.entity.find('moqui.security.UserAccount').condition('userId', userId).selectField('username').useCache(false).one() }
                finally { if (!disabled) ec.artifactExecution.enableAuthz() }
                if (user?.username && ((UserFacadeImpl) ec.user).internalLoginUser(user.username as String, false)) {
                    results.add([userId: userId, result: poll(ec, workerId + ':' + userId, leaseSeconds, limit)])
                    ec.user.logoutUser()
                }
            }
        } finally {
            if (originalUsername && !ec.user.username) ((UserFacadeImpl) ec.user).internalLoginUser(originalUsername, false)
        }
        [owners: results, ownerCount: results.size()]
    }

    /** The waiting runs of the current user that are due. */
    static Map<String, Object> poll(ExecutionContext ec, String workerId, int leaseSeconds, int limit) {
        String userId = ec.user.userId
        boolean disabled = ec.artifactExecution.disableAuthz()
        List<EntityValue> candidates
        try {
            candidates = ec.entity.find('moqui.llm.LlmRun').condition('statusId', LlmRunStore.WAIT_PROVIDER).condition('userId', userId)
                    .orderBy(['lastUpdatedDate', 'runId']).limit(Math.max(limit, 1)).useCache(false).list() as List<EntityValue>
        } finally { if (!disabled) ec.artifactExecution.enableAuthz() }
        List<Map<String, Object>> outcomes = []
        for (EntityValue candidate : candidates) {
            String runId = candidate.runId as String
            try { outcomes.add(pollRun(ec, runId, workerId, leaseSeconds)) }
            catch (Throwable t) { outcomes.add([runId: runId, outcome: 'failed', error: t.message]) }
        }
        [polled: outcomes.size(), outcomes: outcomes]
    }

    static Map<String, Object> pollRun(ExecutionContext ec, String runId, String workerId, int leaseSeconds) {
        Map<String, Object> run = LlmRunStore.getRun(ec, runId)
        if (run.statusId != LlmRunStore.WAIT_PROVIDER) return [runId: runId, outcome: 'not_waiting']
        Map cp = (run.checkpoint instanceof Map ? run.checkpoint : [:]) as Map
        long now = System.currentTimeMillis()
        boolean pollable = cp.pollable == true
        // a run that cannot be asked about is left alone until its limit, which ends it
        if (!pollable && (cp.deadlineAt instanceof Number ? ((Number) cp.deadlineAt).longValue() : Long.MAX_VALUE) >= now)
            return [runId: runId, outcome: 'not_pollable']
        if (pollable && (cp.nextPollAt instanceof Number ? ((Number) cp.nextPollAt).longValue() : 0L) > now) return [runId: runId, outcome: 'not_due']
        long fence = LlmRunStore.claimPoll(ec, runId, workerId, leaseSeconds)
        if (fence == 0L) return [runId: runId, outcome: 'held']
        try {
            // what was true when the run was claimed, not when it was listed
            run = LlmRunStore.getRun(ec, runId)
            cp = (run.checkpoint instanceof Map ? run.checkpoint : [:]) as Map
            LlmFacadeImpl.ProfileState profile = ((LlmFacadeImpl) ec.llm).getProfileState(run.profileName as String)
            if (profile == null) return [runId: runId, outcome: 'profile_missing']
            LlmClientImpl client = (LlmClientImpl) ec.llm.getClient(run.profileName as String)
            LlmProtocol.ProtocolRequest request = client.buildRequest(client.resolveModel(), Collections.emptyList())
            String providerId = cp.providerResponseId as String
            if (!pollable) {
                end(ec, run, fence, LlmRunStore.FAILED, 'The provider had not finished response ' + providerId + ' and this protocol has no operation to ask it again; the limit of the wait was reached')
                return [runId: runId, outcome: 'expired_not_pollable']
            }
            if ((cp.deadlineAt instanceof Number ? ((Number) cp.deadlineAt).longValue() : Long.MAX_VALUE) < now) {
                if (profile.protocol.getCapabilities().contains(LlmProtocol.Capability.CANCEL)) {
                    try { OpenResponsesProtocol.cancel(request, providerId) } catch (Throwable t) { /* the run ends either way */ }
                }
                end(ec, run, fence, LlmRunStore.FAILED, 'The provider did not finish response ' + providerId + ' in time; cancel was requested')
                return [runId: runId, outcome: 'timeout']
            }
            LlmProtocol.ProtocolResult result = OpenResponsesProtocol.retrieve(request, providerId)
            if (result.httpStatus >= 400 || result.httpStatus <= 0) {
                int errors = ((cp.consecutiveErrors ?: 0) as Number).intValue() + 1
                if (errors >= MAX_CONSECUTIVE_ERRORS) {
                    end(ec, run, fence, LlmRunStore.FAILED, 'The provider could not be asked about response ' + providerId + ' ' + errors + ' times: ' + result.errorMessage)
                    return [runId: runId, outcome: 'gave_up']
                }
                reschedule(ec, run, fence, cp, [consecutiveErrors: errors, lastError: result.errorMessage])
                return [runId: runId, outcome: 'error']
            }
            if (result.finishReason == LlmFinishReason.PENDING) {
                reschedule(ec, run, fence, cp, [consecutiveErrors: 0, status: result.status])
                return [runId: runId, outcome: 'pending', status: result.status]
            }
            complete(ec, run, cp, result, fence, profile)
            return [runId: runId, outcome: 'finished', finishReason: result.finishReason?.name()]
        } finally {
            try { LlmRunStore.releaseLease(ec, runId, workerId, fence) } catch (Throwable ignored) { }
        }
    }

    private static void reschedule(ExecutionContext ec, Map<String, Object> run, long fence, Map cp, Map extra) {
        Map next = new LinkedHashMap(cp)
        next.putAll(extra)
        next.nextPollAt = System.currentTimeMillis() + (cp.pollEverySeconds instanceof Number ? ((Number) cp.pollEverySeconds).longValue() : 5L) * 1000L
        LlmRunStore.checkpoint(ec, run.runId as String, fence, run.context as List, next, cp.providerResponseId as String, null)
    }

    private static void end(ExecutionContext ec, Map<String, Object> run, long fence, String status, String message) {
        String conversationId = run.conversationId as String
        if (conversationId) {
            LlmConversationImpl conv = LlmConversationImpl.load(ec, conversationId, false)
            conv.persistIsolated({
                LlmRunStore.transition(ec, run.runId as String, status, message, fence)
                conv.setStatusInternal(status == LlmRunStore.FAILED ? LlmConversationImpl.STATUS_FAILED : LlmConversationImpl.STATUS_CANCELLED)
                conv.setAttribute('activeLlmRunId', null)
            } as Runnable)
        } else {
            LlmRunStore.transition(ec, run.runId as String, status, message, fence)
        }
    }

    /** The response is final: store it like any response, then end the run according to what it says. */
    private static void complete(ExecutionContext ec, Map<String, Object> run, Map cp, LlmProtocol.ProtocolResult result, long fence,
                                 LlmFacadeImpl.ProfileState profile) {
        String runId = run.runId as String
        String conversationId = run.conversationId as String
        LlmConversationImpl conv = conversationId ? LlmConversationImpl.load(ec, conversationId, false) : LlmConversationImpl.detached(ec)
        if (conversationId) conv.bindProfile(profile)
        result.runId = runId
        result.runFence = fence
        result.localRequestId = cp.localRequestId as String
        int iteration = ((cp.iteration ?: 1) as Number).intValue()
        LlmFinishReason fr = result.finishReason ?: LlmFinishReason.ERROR
        boolean answer = fr == LlmFinishReason.STOP || fr == LlmFinishReason.LENGTH || fr == LlmFinishReason.TOOL_CALLS
        conv.persistIsolated({
            conv.writeCallLog(profile.name, profile.protocol.getName(), result.model ?: profile.model, false, null, result, 0L, iteration, !answer)
            if (answer) {
                List context = new ArrayList(OpenResponsesCodec.itemsFromStored(run.context))
                if (result.outputItems != null) result.outputItems.each { context.add(it.copy()) }
                Map checkpoint = [phase: 'provider_response', providerResponseId: result.responseId, localResponseId: result.localResponseId, iteration: iteration]
                LlmRunStore.checkpoint(ec, runId, fence, context, checkpoint, result.responseId, iteration)
                if (fr == LlmFinishReason.TOOL_CALLS) {
                    // tools are never run by a poller: the run waits for a person to continue it
                    LlmRunStore.transition(ec, runId, LlmRunStore.WAIT_CONFIRM,
                            'The background response asks for tool calls; it is not continued automatically', fence)
                } else {
                    if (conversationId && conv.usesItemModel()) {
                        Object base = run.envelope instanceof Map ? ((Map) run.envelope).conversationHeadVersion : null
                        conv.advanceHead(runId, base instanceof Number ? ((Number) base).longValue() : 0L)
                    }
                    LlmRunStore.transition(ec, runId, LlmRunStore.COMPLETE, null, fence)
                    if (conversationId) {
                        conv.setStatusInternal(LlmConversationImpl.STATUS_COMPLETE)
                        conv.setAttribute('activeLlmRunId', null)
                    }
                }
            } else {
                LlmRunStore.transition(ec, runId, LlmRunStore.FAILED, result.errorMessage ?: ('Response ended as ' + result.status), fence)
                if (conversationId) {
                    conv.setStatusInternal(LlmConversationImpl.STATUS_FAILED)
                    conv.setAttribute('activeLlmRunId', null)
                }
            }
        } as Runnable)
    }
}
