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
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue

import org.moqui.llm.LlmContentPart
import org.moqui.llm.LlmItem
import java.sql.Timestamp

/** Short-transaction persistence for LLM runs, checkpoints, recovery claims and tool invocations. */
public final class LlmRunStore {
    public static final String QUEUED = 'LlmRunQueued'
    public static final String RUNNING = 'LlmRunRunning'
    public static final String WAIT_CLIENT = 'LlmRunWaitClient'
    public static final String WAIT_CONFIRM = 'LlmRunWaitConfirm'
    public static final String RECOVERING = 'LlmRunRecovering'
    /** The provider accepted a background response and has not finished it; a poller completes the run. */
    public static final String WAIT_PROVIDER = 'LlmRunWaitProvider'
    public static final String COMPLETE = 'LlmRunComplete'
    public static final String FAILED = 'LlmRunFailed'
    public static final String CANCELLED = 'LlmRunCancelled'

    private static final Map<String, Set<String>> TRANSITIONS = [
        (QUEUED): [RUNNING, CANCELLED, FAILED] as Set,
        (RUNNING): [WAIT_CLIENT, WAIT_CONFIRM, WAIT_PROVIDER, COMPLETE, FAILED, CANCELLED] as Set,
        (WAIT_PROVIDER): [RUNNING, WAIT_CONFIRM, COMPLETE, FAILED, CANCELLED] as Set,
        (WAIT_CLIENT): [QUEUED, RUNNING, RECOVERING, CANCELLED, FAILED] as Set,
        (WAIT_CONFIRM): [QUEUED, RUNNING, RECOVERING, CANCELLED, FAILED] as Set,
        (RECOVERING): [RUNNING, WAIT_CLIENT, WAIT_CONFIRM, COMPLETE, FAILED, CANCELLED] as Set,
        (COMPLETE): [] as Set, (FAILED): [] as Set, (CANCELLED): [] as Set
    ].asImmutable()

    /** sourceType of the projection rows that the content writer feeds; sourceId is the LlmContent id. */
    static final String CONTENT_SOURCE = 'content'

    /**
     * Writes the projection of stored content that has none, for data stored before the projection was fed
     * automatically or while it was switched off. It works in batches from {@code afterLlmContentId}, skips what is
     * already projected and can be run again: the result says where to continue. Only the caller's own content is read.
     */
    static Map<String, Object> backfillContentProjection(ExecutionContext ec, String afterLlmContentId, int batchSize) {
        String userId = requireUser(ec)
        int size = Math.min(Math.max(batchSize, 1), 1000)
        int written = 0
        String last = afterLlmContentId
        boolean more = false
        isolated(ec) { disabled(ec) {
            def find = ec.entity.find('moqui.llm.LlmContent').condition('contentKind', EntityCondition.IN, ['content', 'output'])
                    .orderBy('llmContentId').limit(size + 1)
            if (afterLlmContentId) find.condition('llmContentId', EntityCondition.GREATER_THAN, afterLlmContentId)
            def rows = find.useCache(false).list()
            more = rows.size() > size
            for (EntityValue row : rows.take(size)) {
                last = row.llmContentId as String
                if (!row.textContent || !(row.contentType in [null, 'input_text', 'output_text', 'text'])) continue
                def item = ec.entity.find('moqui.llm.LlmItem').condition('llmItemId', row.llmItemId).useCache(false).one()
                if (item == null || !(item.itemType in ['message', 'function_call_output'])) continue
                def response = item.llmResponseId ? ec.entity.find('moqui.llm.LlmResponse').condition('llmResponseId', item.llmResponseId).useCache(false).one() : null
                def request = item.llmRequestId ? ec.entity.find('moqui.llm.LlmRequest').condition('llmRequestId', item.llmRequestId).useCache(false).one() : null
                def owner = response ?: request
                if (owner == null || owner.ownerUserId != userId) continue
                if (ec.entity.find('moqui.llm.LlmContextProjection').condition([userId: userId, sourceType: CONTENT_SOURCE,
                        sourceId: last]).useCache(false).count() > 0) continue
                EntityValue pv = ec.entity.makeValue('moqui.llm.LlmContextProjection')
                pv.setSequencedIdPrimary()
                pv.setAll([userId: userId, conversationId: owner.conversationId, runId: owner.runId,
                        llmResponseId: item.llmResponseId, sourceType: CONTENT_SOURCE, sourceId: last,
                        textContent: row.textContent, createdDate: ec.user.nowTimestamp]).create()
                written++
            }
        } }
        [written: written, lastLlmContentId: last, more: more]
    }

    /**
     * Brings responses stored before the options and the usage were kept apart to the current layout, a batch at a time
     * and from {@code afterLlmResponseId}; it can be stopped and run again, and a response already at version 2 is never
     * touched. For each old response: what its options column held is what was sent, so it moves to the request when the
     * request has no effective options yet; the echoed options and the usage are read from the stored raw response, and
     * stay empty when there is none; nothing is derived from anything else. Needs an administrator.
     */
    static Map<String, Object> upgradeOpenResponsesData(ExecutionContext ec, String afterLlmResponseId, int batchSize) {
        if (!ec.user.isInGroup('ADMIN')) throw new IllegalStateException('Upgrading stored LLM data needs an administrator')
        int size = Math.min(Math.max(batchSize, 1), 1000)
        int upgraded = 0, movedToRequest = 0
        String last = afterLlmResponseId
        boolean more = false
        isolated(ec) { disabled(ec) {
            def find = ec.entity.find('moqui.llm.LlmResponse').condition('dataVersion', EntityCondition.EQUALS, null)
                    .orderBy('llmResponseId').limit(size + 1)
            if (afterLlmResponseId) find.condition('llmResponseId', EntityCondition.GREATER_THAN, afterLlmResponseId)
            def rows = find.useCache(false).forUpdate(true).list()
            more = rows.size() > size
            for (EntityValue row : rows.take(size)) {
                last = row.llmResponseId as String
                if (row.llmRequestId && row.optionsJson) {
                    EntityValue request = ec.entity.find('moqui.llm.LlmRequest').condition('llmRequestId', row.llmRequestId)
                            .useCache(false).forUpdate(true).one()
                    if (request != null && !request.effectiveOptionsJson) {
                        request.effectiveOptionsJson = row.optionsJson
                        request.update()
                        movedToRequest++
                    }
                }
                Map payload = row.payloadJson ? LlmJson.toMap(row.payloadJson as String) : null
                def echoed = payload != null ? OpenResponsesCodec.optionsFromResponse(payload) : null
                row.optionsJson = echoed != null ? LlmJson.toExactJson(echoed.asMap()) : null
                if (!row.usageJson && payload != null && payload.containsKey('usage')) row.usageJson = LlmJson.toExactJson(payload.get('usage'))
                row.dataVersion = 2
                row.update()
                upgraded++
            }
        } }
        [upgraded: upgraded, movedToRequest: movedToRequest, lastLlmResponseId: last, more: more]
    }

    static Map<String, Object> createRun(ExecutionContext ec, Map<String, Object> request) {
        Map<String, Object> result
        isolated(ec) {
            String userId = requireUser(ec)
            Timestamp now = ec.user.nowTimestamp
            EntityValue value = disabled(ec) {
                EntityValue run = ec.entity.makeValue('moqui.llm.LlmRun')
                run.setSequencedIdPrimary()
                run.setAll([conversationId: request.conversationId, parentRunId: request.parentRunId,
                    userId: userId, visitId: ec.user.visitId, profileName: request.profileName,
                    statusId: QUEUED, continuationModeEnumId: request.continuationModeEnumId ?: 'LlmContLocal',
                    objective: request.objective, contextJson: json(request.context), checkpointJson: json(request.checkpoint),
                    envelopeJson: request.envelope != null ? json(request.envelope) : null,
                    previousProviderResponseId: request.previousProviderResponseId, iteration: 0,
                    maxIterations: positive(request.maxIterations, 8), cancelRequested: 'N', fencingToken: 0,
                    deadline: request.deadline, createdDate: now, lastUpdatedDate: now])
                run.create()
                appendStatus(ec, run, QUEUED, null)
                run
            }
            result = runMap(value)
        }
        result
    }

    /** anyOwner: for a calling service that has already checked who may see this run (see resumeWaiting). */
    static Map<String, Object> getRun(ExecutionContext ec, String runId, boolean anyOwner = false) {
        EntityValue value = ownedRun(ec, runId, false, anyOwner)
        Map<String, Object> result = runMap(value)
        result.statuses = disabled(ec) {
            ec.entity.find('moqui.llm.LlmRunStatus').condition('runId', runId)
                .orderBy('sequenceNum').list().collect { EntityValue row -> [sequenceNum: row.sequenceNum,
                    statusId: row.statusId, message: row.message, statusDate: row.statusDate] }
        }
        result
    }

    static Map<String, Object> listRuns(ExecutionContext ec, Map<String, Object> request) {
        String userId = requireUser(ec)
        int pageSize = Math.min(Math.max((request.pageSize ?: 50) as int, 1), 200)
        int offset = Math.max((request.offset ?: 0) as int, 0)
        EntityList rows = disabled(ec) {
            def find = ec.entity.find('moqui.llm.LlmRun').condition('userId', userId).useCache(false)
            if (request.conversationId) find.condition('conversationId', request.conversationId)
            if (request.statusId) find.condition('statusId', request.statusId)
            find.orderBy(['-createdDate', '-runId']).offset(offset).limit(pageSize + 1).list()
        }
        boolean hasMore = rows.size() > pageSize
        if (hasMore) rows.remove(rows.size() - 1)
        [runs: rows.collect { EntityValue row -> runMap(row) }, nextOffset: hasMore ? offset + rows.size() : null]
    }

    /** fencingToken: a worker that holds the run passes its token so a worker that lost the lease cannot change the state. */
    static Map<String, Object> transition(ExecutionContext ec, String runId, String statusId, String message = null,
            Long fencingToken = null) {
        Map<String, Object> result
        isolated(ec) {
            EntityValue run = ownedRun(ec, runId, true)
            if (fencingToken != null) requireFence(run, fencingToken)
            requireTransition(run.statusId as String, statusId)
            Timestamp now = ec.user.nowTimestamp
            run.setAll([statusId: statusId, lastUpdatedDate: now])
            if (statusId == RUNNING && run.startedDate == null) run.startedDate = now
            if (terminal(statusId)) run.completedDate = now
            if (statusId == FAILED) run.errorMessage = message
            if (statusId == CANCELLED) run.cancelRequested = 'Y'
            run.update()
            appendStatus(ec, run, statusId, message)
            result = runMap(run)
        }
        result
    }

    /**
     * Takes the right to poll a run that waits for the provider: one poller at a time, with a fence a later poller
     * supersedes. Returns the fence, or 0 when the run is not waiting for the provider, is cancelled or is held by another
     * poller whose lease has not expired.
     */
    static long claimPoll(ExecutionContext ec, String runId, String workerId, int leaseSeconds) {
        if (!workerId) throw new IllegalArgumentException('workerId is required')
        long fence = 0L
        isolated(ec) {
            EntityValue run = ownedRun(ec, runId, true)
            if (run.statusId != WAIT_PROVIDER || run.cancelRequested == 'Y') return
            Timestamp lease = run.leaseUntil as Timestamp
            if (lease != null && lease.after(ec.user.nowTimestamp)) return
            fence = ((run.fencingToken ?: 0) as Number).longValue() + 1L
            run.setAll([workerId: workerId, fencingToken: fence,
                leaseUntil: new Timestamp(ec.user.nowTimestamp.time + Math.max(leaseSeconds, 1) * 1000L),
                lastUpdatedDate: ec.user.nowTimestamp]).update()
        }
        fence
    }

    static Map<String, Object> checkpoint(ExecutionContext ec, String runId, long fencingToken,
            List<?> contextItems, Map<String, Object> checkpoint, String previousProviderResponseId,
            Integer iteration) {
        Map<String, Object> result
        isolated(ec) {
            EntityValue run = ownedRun(ec, runId, true)
            requireFence(run, fencingToken)
            if (run.cancelRequested == 'Y') throw new IllegalStateException("LLM run ${runId} is cancelled")
            run.setAll([contextJson: json(contextItems), checkpointJson: json(checkpoint),
                previousProviderResponseId: previousProviderResponseId,
                iteration: iteration != null ? iteration : run.iteration,
                lastUpdatedDate: ec.user.nowTimestamp])
            run.update()
            result = runMap(run)
        }
        result
    }

    /**
     * Queued to Running for a background worker: bumps the fencing token and takes a lease. Returns null when the run
     * is not queued (already claimed, cancelled or finished), so two jobs for one run cannot both execute it.
     */
    static Map<String, Object> claimQueued(ExecutionContext ec, String runId, String workerId, int leaseSeconds) {
        if (!workerId) throw new IllegalArgumentException('workerId is required')
        Map<String, Object> result = null
        isolated(ec) {
            EntityValue run = ownedRun(ec, runId, true)
            if (run.statusId != QUEUED || run.cancelRequested == 'Y') return
            Timestamp now = ec.user.nowTimestamp
            long nextFence = ((run.fencingToken ?: 0) as Number).longValue() + 1L
            run.setAll([statusId: RUNNING, workerId: workerId, fencingToken: nextFence,
                leaseUntil: new Timestamp(now.time + Math.max(leaseSeconds, 1) * 1000L),
                startedDate: run.startedDate ?: now, lastUpdatedDate: now]).update()
            appendStatus(ec, run, RUNNING, "Claimed by ${workerId}")
            result = runMap(run)
        }
        result
    }

    static boolean isCancelRequested(ExecutionContext ec, String runId) {
        EntityValue run = disabled(ec) {
            ec.entity.find('moqui.llm.LlmRun').condition('runId', runId).selectField('cancelRequested,statusId')
                .useCache(false).one()
        }
        run != null && (run.cancelRequested == 'Y' || run.statusId == CANCELLED)
    }

    static String runStatus(ExecutionContext ec, String runId) {
        EntityValue run = disabled(ec) {
            ec.entity.find('moqui.llm.LlmRun').condition('runId', runId).selectField('statusId').useCache(false).one()
        }
        run?.statusId
    }

    static void linkJobRun(ExecutionContext ec, String runId, String jobRunId, boolean anyOwner = false) {
        isolated(ec) {
            EntityValue run = ownedRun(ec, runId, true, anyOwner)
            run.setAll([jobRunId: jobRunId, lastUpdatedDate: ec.user.nowTimestamp]).update()
        }
    }

    /**
     * Records a human answer (run waiting for the client) or a decision (run waiting for confirmation) in the run
     * trajectory and queues the run again. A rejection cancels it. Refuses while a tool invocation has an uncertain
     * outcome: that needs reconciliation, not an answer. The XML services allow only the owner; a calling service that
     * has done its own permission check may pass allowNonOwner.
     */
    static Map<String, Object> resumeWaiting(ExecutionContext ec, String runId, String kind, String text,
            Boolean approved, boolean allowNonOwner = false) {
        if (kind != 'answer' && kind != 'confirm') throw new IllegalArgumentException("Unknown resume kind ${kind}")
        Map<String, Object> result
        isolated(ec) {
            EntityValue run = ownedRun(ec, runId, true, allowNonOwner)
            String expected = kind == 'confirm' ? WAIT_CONFIRM : WAIT_CLIENT
            if (run.statusId != expected)
                throw new IllegalStateException("LLM run ${runId} is ${run.statusId}, not waiting for ${kind == 'confirm' ? 'confirmation' : 'an answer'}")
            if (kind == 'confirm' && approved == null) throw new IllegalArgumentException('approved is required')
            if (invocationsOf(ec, runId).any { it.statusId in ['LlmTiPlanned', 'LlmTiRunning', 'LlmTiUncertain'] })
                throw new IllegalStateException("LLM run ${runId} has a tool invocation with an uncertain outcome; reconcile it first")
            Timestamp now = ec.user.nowTimestamp
            if (kind == 'confirm' && !approved) {
                run.setAll([statusId: CANCELLED, cancelRequested: 'Y', completedDate: now, workerId: null, leaseUntil: null,
                    lastUpdatedDate: now]).update()
                appendStatus(ec, run, CANCELLED, "Rejected${text ? ': ' + text : ''}")
                result = runMap(run)
                return
            }
            String message = kind == 'confirm' ? "Confirmation: approved.${text ? ' ' + text : ''}" : text
            if (!message) throw new IllegalArgumentException('text is required for an answer')
            List<LlmItem> items = OpenResponsesCodec.itemsFromStored(parse(run.contextJson as String))
            items.add(LlmItem.message('user', [LlmContentPart.inputText(message)]))
            run.setAll([contextJson: json(items), checkpointJson: json([phase: 'ready_provider', resumedBy: kind]),
                statusId: QUEUED, workerId: null, leaseUntil: null, lastUpdatedDate: now]).update()
            appendStatus(ec, run, QUEUED, kind == 'confirm' ? 'Confirmed' : 'Answered')
            result = runMap(run)
        }
        result
    }

    private static List<EntityValue> invocationsOf(ExecutionContext ec, String runId) {
        disabled(ec) { ec.entity.find('moqui.llm.LlmToolInvocation').condition('runId', runId).useCache(false).list() }
    }

    static Map<String, Object> cancel(ExecutionContext ec, String runId) {
        Map<String, Object> result
        isolated(ec) {
            EntityValue run = ownedRun(ec, runId, true)
            if (!terminal(run.statusId as String)) {
                run.cancelRequested = 'Y'
                requireTransition(run.statusId as String, CANCELLED)
                run.setAll([statusId: CANCELLED, completedDate: ec.user.nowTimestamp,
                    lastUpdatedDate: ec.user.nowTimestamp])
                run.update()
                appendStatus(ec, run, CANCELLED, 'Cancellation requested')
            }
            result = runMap(run)
        }
        result
    }

    static List<Map<String, Object>> claimRecoverable(ExecutionContext ec, String workerId,
            int leaseSeconds, int limit) {
        if (!workerId) throw new IllegalArgumentException('workerId is required')
        String userId = requireUser(ec)
        boolean admin = ec.user.isInGroup('ADMIN')
        Timestamp now = ec.user.nowTimestamp
        EntityList candidates = disabled(ec) {
            def find = ec.entity.find('moqui.llm.LlmRun')
                .condition('statusId', EntityCondition.IN, [RUNNING, RECOVERING])
                .condition(ec.entity.conditionFactory.makeCondition([
                    ec.entity.conditionFactory.makeCondition('leaseUntil', EntityCondition.EQUALS, null),
                    ec.entity.conditionFactory.makeCondition('leaseUntil', EntityCondition.LESS_THAN_EQUAL_TO, now)
                ], EntityCondition.OR))
            if (!admin) find.condition('userId', userId)
            find.orderBy(['lastUpdatedDate', 'runId']).limit(Math.max(limit, 1)).list()
        }
        List<Map<String, Object>> claimed = []
        for (EntityValue candidate : candidates) {
            isolated(ec) {
                EntityValue run = disabled(ec) {
                    ec.entity.find('moqui.llm.LlmRun').condition('runId', candidate.runId)
                        .forUpdate(true).useCache(false).one()
                }
                // the state may have changed between the candidate query and the lock (a wait, a finish, a cancel)
                if (run == null || !(run.statusId in [RUNNING, RECOVERING]) || run.cancelRequested == 'Y') return
                Timestamp lease = run.leaseUntil as Timestamp
                if (lease != null && lease.after(ec.user.nowTimestamp)) return
                long nextFence = ((run.fencingToken ?: 0) as Number).longValue() + 1L
                run.setAll([statusId: RECOVERING, workerId: workerId, fencingToken: nextFence,
                    leaseUntil: new Timestamp(ec.user.nowTimestamp.time + Math.max(leaseSeconds, 1) * 1000L),
                    lastUpdatedDate: ec.user.nowTimestamp]).update()
                appendStatus(ec, run, RECOVERING, "Claimed by ${workerId}")
                claimed.add(runMap(run))
            }
        }
        claimed
    }

    /**
     * A worker that starts executing a running run takes the lease and a new fencing token before it does anything
     * else, so the recovery job cannot claim a run that is being executed. Returns the token.
     */
    static long acquireLease(ExecutionContext ec, String runId, String workerId, int leaseSeconds) {
        if (!workerId) throw new IllegalArgumentException('workerId is required')
        long fence = 0L
        isolated(ec) {
            EntityValue run = ownedRun(ec, runId, true)
            if (run.statusId != RUNNING || run.cancelRequested == 'Y')
                throw new IllegalStateException("LLM run ${runId} is ${run.statusId}, it cannot be leased")
            Timestamp lease = run.leaseUntil as Timestamp
            if (lease != null && lease.after(ec.user.nowTimestamp) && run.workerId != workerId)
                throw new IllegalStateException("LLM run ${runId} is held by ${run.workerId}")
            fence = ((run.fencingToken ?: 0) as Number).longValue() + 1L
            run.setAll([workerId: workerId, fencingToken: fence,
                leaseUntil: new Timestamp(ec.user.nowTimestamp.time + Math.max(leaseSeconds, 1) * 1000L),
                lastUpdatedDate: ec.user.nowTimestamp]).update()
        }
        fence
    }

    /**
     * Throws unless fencingToken is still the current one of the run. Called inside the transaction of a write that
     * belongs to a run (request, response, events), so a worker that lost the lease cannot add to the run.
     */
    static void assertFence(ExecutionContext ec, String runId, long fencingToken) {
        if (!runId || fencingToken <= 0L) return
        EntityValue run = disabled(ec) {
            ec.entity.find('moqui.llm.LlmRun').condition('runId', runId).selectField('fencingToken').useCache(false).one()
        }
        long current = ((run?.fencingToken ?: 0) as Number).longValue()
        if (run != null && current != fencingToken)
            throw new IllegalStateException("Stale LLM run worker fence ${fencingToken}; current ${current}")
    }

    /** Clears the lease of a run that is no longer running; a no-op when another worker holds a newer fence. */
    static void releaseLease(ExecutionContext ec, String runId, String workerId, long fencingToken) {
        isolated(ec) {
            EntityValue run = ownedRun(ec, runId, true)
            if (run.workerId != workerId || ((run.fencingToken ?: 0) as Number).longValue() != fencingToken) return
            if (run.statusId == RUNNING || run.statusId == RECOVERING) return
            run.setAll([workerId: null, leaseUntil: null, lastUpdatedDate: ec.user.nowTimestamp]).update()
        }
    }

    static Map<String, Object> renewLease(ExecutionContext ec, String runId, String workerId,
            long fencingToken, int leaseSeconds) {
        Map<String, Object> result
        isolated(ec) {
            EntityValue run = ownedRun(ec, runId, true)
            requireFence(run, fencingToken)
            if (run.workerId != workerId) throw new IllegalStateException("LLM run ${runId} is owned by another worker")
            if (terminal(run.statusId as String) || run.cancelRequested == 'Y')
                throw new IllegalStateException("LLM run ${runId} is not renewable")
            run.setAll([leaseUntil: new Timestamp(ec.user.nowTimestamp.time + Math.max(leaseSeconds, 1) * 1000L),
                lastUpdatedDate: ec.user.nowTimestamp]).update()
            result = runMap(run)
        }
        result
    }

    static Map<String, Object> planTool(ExecutionContext ec, String runId, Map<String, Object> request,
            Long fencingToken = null) {
        Map<String, Object> result
        isolated(ec) {
            EntityValue run = ownedRun(ec, runId, true)
            if (fencingToken != null) requireFence(run, fencingToken)
            String callId = request.providerCallId as String
            if (!callId) throw new IllegalArgumentException('providerCallId is required')
            EntityValue existing = disabled(ec) {
                ec.entity.find('moqui.llm.LlmToolInvocation')
                    .condition([runId: runId, providerCallId: callId]).useCache(false).one()
            }
            if (existing != null) {
                result = invocationMap(existing)
                return
            }
            EntityValue invocation = disabled(ec) {
                EntityValue value = ec.entity.makeValue('moqui.llm.LlmToolInvocation')
                value.setSequencedIdPrimary()
                value.setAll([runId: runId, llmResponseId: request.llmResponseId,
                    providerItemId: request.providerItemId, providerCallId: callId,
                    toolName: request.toolName, statusId: 'LlmTiPlanned', attempt: 0,
                    idempotencyKey: request.idempotencyKey, argumentsJson: json(request.arguments),
                    createdDate: ec.user.nowTimestamp])
                value.create()
                value
            }
            result = invocationMap(invocation)
        }
        result
    }

    static Map<String, Object> claimTool(ExecutionContext ec, String toolInvocationId, String workerId,
            long fencingToken) {
        if (!workerId) throw new IllegalArgumentException('workerId is required')
        Map<String, Object> result
        isolated(ec) {
            EntityValue invocation = disabled(ec) {
                ec.entity.find('moqui.llm.LlmToolInvocation').condition('toolInvocationId', toolInvocationId)
                    .forUpdate(true).useCache(false).one()
            }
            if (invocation == null) throw new IllegalArgumentException('LLM tool invocation not found')
            EntityValue run = ownedRun(ec, invocation.runId as String, true)
            requireFence(run, fencingToken)
            if (invocation.statusId != 'LlmTiPlanned')
                throw new IllegalStateException("LLM tool invocation ${toolInvocationId} is not planned")
            invocation.setAll([statusId: 'LlmTiRunning', workerId: workerId, fencingToken: fencingToken,
                attempt: ((invocation.attempt ?: 0) as Number).intValue() + 1,
                startedDate: ec.user.nowTimestamp]).update()
            result = invocationMap(invocation)
        }
        result
    }

    static Map<String, Object> completeToolAndCheckpoint(ExecutionContext ec, String toolInvocationId,
            long fencingToken, Object toolResult, boolean success, List<?> contextItems,
            Map<String, Object> checkpoint) {
        Map<String, Object> result
        isolated(ec) {
            EntityValue invocation = disabled(ec) {
                ec.entity.find('moqui.llm.LlmToolInvocation').condition('toolInvocationId', toolInvocationId)
                    .forUpdate(true).useCache(false).one()
            }
            if (invocation == null) throw new IllegalArgumentException('LLM tool invocation not found')
            EntityValue run = ownedRun(ec, invocation.runId as String, true)
            requireFence(run, fencingToken)
            invocation.setAll([statusId: success ? 'LlmTiComplete' : 'LlmTiFailed', resultJson: json(toolResult),
                errorMessage: success ? null : String.valueOf(toolResult), completedDate: ec.user.nowTimestamp,
                fencingToken: fencingToken]).update()
            run.setAll([contextJson: json(contextItems), checkpointJson: json(checkpoint),
                lastUpdatedDate: ec.user.nowTimestamp]).update()
            result = [invocation: invocationMap(invocation), run: runMap(run)]
        }
        result
    }

    static Map<String, Object> markToolUncertain(ExecutionContext ec, String toolInvocationId, String message) {
        Map<String, Object> result
        isolated(ec) {
            EntityValue invocation = disabled(ec) {
                ec.entity.find('moqui.llm.LlmToolInvocation').condition('toolInvocationId', toolInvocationId)
                    .forUpdate(true).useCache(false).one()
            }
            if (invocation == null) throw new IllegalArgumentException('LLM tool invocation not found')
            ownedRun(ec, invocation.runId as String, false)
            invocation.setAll([statusId: 'LlmTiUncertain', errorMessage: message,
                completedDate: ec.user.nowTimestamp]).update()
            result = invocationMap(invocation)
        }
        result
    }

    static List<Map<String, Object>> listToolInvocations(ExecutionContext ec, String runId) {
        ownedRun(ec, runId, false)
        disabled(ec) {
            ec.entity.find('moqui.llm.LlmToolInvocation').condition('runId', runId)
                .orderBy(['createdDate', 'toolInvocationId']).useCache(false).list()
                .collect { EntityValue value -> invocationMap(value) }
        }
    }

    static void projectText(ExecutionContext ec, Map<String, Object> source) {
        String text = source.textContent as String
        if (!text?.trim()) return
        String sourceType = source.sourceType as String
        String sourceId = source.sourceId as String
        if (!sourceType || !sourceId) throw new IllegalArgumentException('sourceType and sourceId are required')
        isolated(ec) {
            requireUser(ec)
            disabled(ec) {
                EntityValue value = ec.entity.find('moqui.llm.LlmContextProjection')
                    .condition([userId: ec.user.userId, sourceType: sourceType, sourceId: sourceId])
                    .forUpdate(true).useCache(false).one()
                if (value == null) {
                    value = ec.entity.makeValue('moqui.llm.LlmContextProjection')
                    value.setSequencedIdPrimary()
                }
                value.setAll([
                    userId: ec.user.userId, conversationId: source.conversationId, runId: source.runId,
                    llmResponseId: source.llmResponseId, sourceType: sourceType,
                    sourceId: sourceId, textContent: text, createdDate: ec.user.nowTimestamp
                ]).createOrUpdate()
            }
        }
    }

    static Map<String, Object> searchContext(ExecutionContext ec, Map<String, Object> request) {
        String userId = requireUser(ec)
        String query = request.query as String
        if (!query?.trim()) throw new IllegalArgumentException('query is required')
        int limit = Math.min(Math.max((request.limit ?: 20) as int, 1), 100)
        EntityList rows = disabled(ec) {
            def find = ec.entity.find('moqui.llm.LlmContextProjection').condition('userId', userId)
            if (request.conversationId) find.condition('conversationId', request.conversationId)
            if (request.runId) find.condition('runId', request.runId)
            if (request.profileName) {
                def runIds = ec.entity.find('moqui.llm.LlmRun').condition([userId: userId,
                    profileName: request.profileName]).selectField('runId').list().collect { it.runId }
                if (!runIds) return []
                find.condition('runId', EntityCondition.IN, runIds)
            }
            find.condition('textContent', EntityCondition.LIKE, "%${query.trim()}%")
                .orderBy(['-createdDate', '-contextProjectionId']).limit(limit).list()
        }
        [results: rows.collect { EntityValue row -> row.getMap() }]
    }

    private static EntityValue ownedRun(ExecutionContext ec, String runId, boolean forUpdate, boolean anyOwner = false) {
        if (!runId) throw new IllegalArgumentException('runId is required')
        String userId = requireUser(ec)
        EntityValue run = disabled(ec) {
            ec.entity.find('moqui.llm.LlmRun').condition('runId', runId).forUpdate(forUpdate)
                .useCache(false).one()
        }
        if (run == null || (run.userId != userId && !anyOwner && !ec.user.isInGroup('ADMIN')))
            throw new IllegalArgumentException('LLM run not found')
        run
    }

    private static void appendStatus(ExecutionContext ec, EntityValue run, String statusId, String message) {
        long count = ec.entity.find('moqui.llm.LlmRunStatus').condition('runId', run.runId).count()
        int sequenceNum = count.intValue() + 1
        ec.entity.makeValue('moqui.llm.LlmRunStatus').setAll([runId: run.runId,
            statusSeqId: String.format('%05d', sequenceNum), sequenceNum: sequenceNum,
            statusId: statusId, message: message, statusDate: ec.user.nowTimestamp]).create()
    }

    private static void requireTransition(String from, String to) {
        if (from == to) return
        if (!TRANSITIONS.containsKey(from) || !TRANSITIONS[from].contains(to))
            throw new IllegalStateException("Invalid LLM run transition ${from} -> ${to}")
    }

    private static void requireFence(EntityValue run, long fencingToken) {
        long current = ((run.fencingToken ?: 0) as Number).longValue()
        if (current != fencingToken)
            throw new IllegalStateException("Stale LLM run worker fence ${fencingToken}; current ${current}")
    }

    private static boolean terminal(String statusId) {
        statusId in [COMPLETE, FAILED, CANCELLED]
    }

    private static int positive(Object value, int defaultValue) {
        int parsed = value instanceof Number ? ((Number) value).intValue() : defaultValue
        parsed > 0 ? parsed : defaultValue
    }

    private static String requireUser(ExecutionContext ec) {
        String userId = ec?.user?.userId
        if (!userId) throw new IllegalStateException('Authenticated user is required for LLM run storage')
        userId
    }

    private static Map<String, Object> runMap(EntityValue run) {
        [runId: run.runId, conversationId: run.conversationId, parentRunId: run.parentRunId,
         userId: run.userId, profileName: run.profileName, statusId: run.statusId,
         continuationModeEnumId: run.continuationModeEnumId, objective: run.objective,
         context: parse(run.contextJson as String), checkpoint: parse(run.checkpointJson as String),
         envelope: run.envelopeJson ? parse(run.envelopeJson as String) : null,
         previousProviderResponseId: run.previousProviderResponseId, iteration: run.iteration,
         maxIterations: run.maxIterations, cancelRequested: run.cancelRequested == 'Y', workerId: run.workerId,
         jobRunId: run.jobRunId, leaseUntil: run.leaseUntil, fencingToken: run.fencingToken, deadline: run.deadline,
         errorMessage: run.errorMessage, createdDate: run.createdDate, startedDate: run.startedDate,
         lastUpdatedDate: run.lastUpdatedDate, completedDate: run.completedDate] as Map<String, Object>
    }

    private static Map<String, Object> invocationMap(EntityValue value) {
        [toolInvocationId: value.toolInvocationId, runId: value.runId, llmResponseId: value.llmResponseId,
         providerItemId: value.providerItemId, providerCallId: value.providerCallId, toolName: value.toolName,
         statusId: value.statusId, attempt: value.attempt, idempotencyKey: value.idempotencyKey,
         arguments: parse(value.argumentsJson as String), result: parse(value.resultJson as String),
         workerId: value.workerId, fencingToken: value.fencingToken, errorMessage: value.errorMessage,
         createdDate: value.createdDate, startedDate: value.startedDate,
         completedDate: value.completedDate] as Map<String, Object>
    }

    private static String json(Object value) { value != null ? LlmJson.toJson(value) : null }
    private static Object parse(String value) { value ? LlmJson.toObject(value) : null }

    private static <T> T disabled(ExecutionContext ec, Closure<T> work) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        try { work.call() } finally { if (!alreadyDisabled) ec.artifactExecution.enableAuthz() }
    }

    private static void isolated(ExecutionContext ec, Closure work) {
        LlmConversationImpl.persistIsolated(ec, work as Runnable)
    }
}
