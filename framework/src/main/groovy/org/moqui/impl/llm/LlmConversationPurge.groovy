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

import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityValue
import org.moqui.llm.LlmException
import org.moqui.llm.LlmFinishReason

import java.sql.Timestamp

/**
 * The one way a conversation, or the old part of it, is removed: by the owner who deletes it and by retention. Both
 * lock the conversation row, refuse while a worker may still use it, and remove in an order that never leaves a
 * reference to a row that is gone: searchable copies, stream events, content, items, responses, requests, then the
 * runs and their journals, and last the conversation. An item that survives is cut loose from a replay source that
 * goes. Connections that belonged to the conversation or its runs are closed.
 */
@CompileStatic
final class LlmConversationPurge {
    static final List<String> LIVE = ['LlmRunQueued', LlmRunStore.RUNNING, LlmRunStore.RECOVERING, LlmRunStore.WAIT_PROVIDER]
    static final List<String> TERMINAL = [LlmRunStore.COMPLETE, LlmRunStore.FAILED, LlmRunStore.CANCELLED]
    private static final int CHUNK = 400

    private LlmConversationPurge() { }

    /** Deletes the conversation and everything of it. A live run or turn is a 409; a run that only waits is deleted with it. */
    static Map<String, Object> deleteAll(ExecutionContext ec, String conversationId) {
        Map<String, Object> out = [conversationId: conversationId, deleted: false] as Map<String, Object>
        Set<String> runIds = new LinkedHashSet<>()
        ec.transaction.runRequireNew(300, 'Error deleting LLM conversation', {
            EntityValue conversation = lock(ec, conversationId)
            if (conversation == null) return
            if (LlmConversationImpl.turnInFlight(ec, conversationId))
                throw new LlmException('Conversation ' + conversationId + ' has a turn in progress', null, LlmFinishReason.ERROR, 409, null, conversationId)
            if (count(ec, 'moqui.llm.LlmRun', ['conversationId': conversationId], [new Cond('statusId', EntityCondition.IN, LIVE)]) > 0)
                throw new LlmException('Conversation ' + conversationId + ' has a run that is still working', null, LlmFinishReason.ERROR, 409, null, conversationId)
            runIds.addAll(ids(ec, 'moqui.llm.LlmRun', 'runId', ['conversationId': conversationId], []))
            Map<String, Long> removed = new LinkedHashMap<>()
            removeEverythingOfRuns(ec, conversationId, new ArrayList<String>(runIds), removed)
            removeSide(ec, conversationId, removed)
            // a message transcript belongs to the message model only; an item conversation never had one
            if (holdsMessages(conversation))
                removed.put('messages', LlmMessageStore.deleteAll(ec, conversationId))
            conversation.delete()
            out.removed = removed
            out.deleted = true
        })
        closeConnections(ec, conversationId, runIds)
        return out
    }

    /**
     * Retention: what is older than the cutoff, in a conversation that is itself older and has no live run. The
     * conversation row goes only when nothing of it is left. Returns what was removed, and whether the conversation went.
     */
    static Map<String, Object> purgeOlderThan(ExecutionContext ec, String conversationId, Timestamp cutoff) {
        Map<String, Object> out = [conversationId: conversationId, conversationRemoved: false, callLogs: 0L, messages: 0L] as Map<String, Object>
        EntityValue conversation = lock(ec, conversationId)
        if (conversation == null || conversation.getTimestamp('lastMessageDate') == null
                || !conversation.getTimestamp('lastMessageDate').before(cutoff)) return out
        if (count(ec, 'moqui.llm.LlmRun', ['conversationId': conversationId], [new Cond('statusId', EntityCondition.NOT_IN, TERMINAL)]) > 0) return out
        List<String> oldResponseIds = ids(ec, 'moqui.llm.LlmResponse', 'llmResponseId', ['conversationId': conversationId],
                [new Cond('createdDate', EntityCondition.LESS_THAN, cutoff)])
        removeResponses(ec, oldResponseIds)
        List<String> oldRequestIds = ids(ec, 'moqui.llm.LlmRequest', 'llmRequestId', ['conversationId': conversationId],
                [new Cond('createdDate', EntityCondition.LESS_THAN, cutoff)])
        removeRequests(ec, oldRequestIds)
        out.callLogs = delete(ec, 'moqui.llm.LlmCallLog', ['conversationId': conversationId], [new Cond('startDate', EntityCondition.LESS_THAN, cutoff)])
        if (holdsMessages(conversation))
            out.messages = ec.entity.find('moqui.llm.LlmMessage').condition('conversationId', conversationId)
                    .condition('sentDate', EntityCondition.LESS_THAN, cutoff).disableAuthz().deleteAll()
        boolean leftover = count(ec, 'moqui.llm.LlmResponse', ['conversationId': conversationId], []) > 0 ||
                count(ec, 'moqui.llm.LlmRequest', ['conversationId': conversationId], []) > 0 ||
                count(ec, 'moqui.llm.LlmCallLog', ['conversationId': conversationId], []) > 0 ||
                (holdsMessages(conversation) &&
                        ec.entity.find('moqui.llm.LlmMessage').condition('conversationId', conversationId).useCache(false).disableAuthz().count() > 0) ||
                // an old finished run goes with the runs below; a newer or open one keeps its conversation
                hasNewerOrOpenRun(ec, conversationId, cutoff)
        if (!leftover) {
            out.conversationRemoved = true
            conversation.delete()
        }
        return out
    }

    // ---- what is removed, in order

    private static void removeEverythingOfRuns(ExecutionContext ec, String conversationId, List<String> runIds, Map<String, Long> removed) {
        Set<String> requestIds = new LinkedHashSet<>(ids(ec, 'moqui.llm.LlmRequest', 'llmRequestId', ['conversationId': conversationId], []))
        Set<String> responseIds = new LinkedHashSet<>(ids(ec, 'moqui.llm.LlmResponse', 'llmResponseId', ['conversationId': conversationId], []))
        for (List<String> part : chunks(runIds)) {
            requestIds.addAll(ids(ec, 'moqui.llm.LlmRequest', 'llmRequestId', [:], [new Cond('runId', EntityCondition.IN, part)]))
            responseIds.addAll(ids(ec, 'moqui.llm.LlmResponse', 'llmResponseId', [:], [new Cond('runId', EntityCondition.IN, part)]))
        }
        removeResponses(ec, new ArrayList<String>(responseIds))
        removeRequests(ec, new ArrayList<String>(requestIds))
        removed.put('responses', (long) responseIds.size())
        removed.put('requests', (long) requestIds.size())
        for (List<String> part : chunks(runIds)) {
            delete(ec, 'moqui.llm.LlmContextProjection', [:], [new Cond('runId', EntityCondition.IN, part)])
            delete(ec, 'moqui.llm.LlmToolInvocation', [:], [new Cond('runId', EntityCondition.IN, part)])
            delete(ec, 'moqui.llm.LlmRunStatus', [:], [new Cond('runId', EntityCondition.IN, part)])
        }
        // the head of the conversation is one of these runs: the conversation row is deleted right after, so cut the reference first
        ec.entity.find('moqui.llm.LlmConversation').condition('conversationId', conversationId).disableAuthz()
                .updateAll([headRunId: null, headVersion: null] as Map<String, Object>)
        long runs = 0L
        for (List<String> part : chunks(runIds)) runs += delete(ec, 'moqui.llm.LlmRun', [:], [new Cond('runId', EntityCondition.IN, part)])
        removed.put('runs', runs)
    }

    private static void removeSide(ExecutionContext ec, String conversationId, Map<String, Long> removed) {
        removed.put('callLogs', delete(ec, 'moqui.llm.LlmCallLog', ['conversationId': conversationId], []))
        removed.put('skillUses', delete(ec, 'moqui.llm.LlmSkillUse', ['conversationId': conversationId], []))
        removed.put('contextProjection', delete(ec, 'moqui.llm.LlmContextProjection', ['conversationId': conversationId], []))
    }

    /** Responses with their stream events, items, content and searchable copies. */
    static void removeResponses(ExecutionContext ec, List<String> responseIds) {
        for (List<String> part : chunks(responseIds)) {
            List<String> itemIds = ids(ec, 'moqui.llm.LlmItem', 'llmItemId', [:], [new Cond('llmResponseId', EntityCondition.IN, part)])
            removeItems(ec, itemIds)
            delete(ec, 'moqui.llm.LlmResponseEvent', [:], [new Cond('llmResponseId', EntityCondition.IN, part)])
            delete(ec, 'moqui.llm.LlmItem', [:], [new Cond('llmResponseId', EntityCondition.IN, part)])
            delete(ec, 'moqui.llm.LlmResponse', [:], [new Cond('llmResponseId', EntityCondition.IN, part)])
        }
    }

    static void removeRequests(ExecutionContext ec, List<String> requestIds) {
        for (List<String> part : chunks(requestIds)) {
            List<String> itemIds = ids(ec, 'moqui.llm.LlmItem', 'llmItemId', [:], [new Cond('llmRequestId', EntityCondition.IN, part)])
            removeItems(ec, itemIds)
            delete(ec, 'moqui.llm.LlmItem', [:], [new Cond('llmRequestId', EntityCondition.IN, part)])
            delete(ec, 'moqui.llm.LlmRequest', [:], [new Cond('llmRequestId', EntityCondition.IN, part)])
        }
    }

    /** Content and the searchable copy of it; an item that survives and replays one of these is cut loose from it first. */
    private static void removeItems(ExecutionContext ec, List<String> itemIds) {
        if (!itemIds) return
        for (List<String> part : chunks(itemIds)) {
            ec.entity.find('moqui.llm.LlmItem').condition('sourceLlmItemId', EntityCondition.IN, part)
                    .condition('llmItemId', EntityCondition.NOT_IN, part).disableAuthz().updateAll([sourceLlmItemId: null] as Map<String, Object>)
            List<String> contentIds = ids(ec, 'moqui.llm.LlmContent', 'llmContentId', [:], [new Cond('llmItemId', EntityCondition.IN, part)])
            for (List<String> contentPart : chunks(contentIds))
                ec.entity.find('moqui.llm.LlmContextProjection').condition('sourceType', 'content')
                        .condition('sourceId', EntityCondition.IN, contentPart).disableAuthz().deleteAll()
            delete(ec, 'moqui.llm.LlmContent', [:], [new Cond('llmItemId', EntityCondition.IN, part)])
        }
    }

    private static boolean hasNewerOrOpenRun(ExecutionContext ec, String conversationId, Timestamp cutoff) {
        EntityCondition either = ec.entity.conditionFactory.makeCondition([
                ec.entity.conditionFactory.makeCondition('completedDate', EntityCondition.EQUALS, null),
                ec.entity.conditionFactory.makeCondition('completedDate', EntityCondition.GREATER_THAN_EQUAL_TO, cutoff)], EntityCondition.OR)
        return ec.entity.find('moqui.llm.LlmRun').condition('conversationId', conversationId).condition(either)
                .useCache(false).disableAuthz().count() > 0
    }

    // ---- helpers

    /** A conversation of the pure item model has no message rows; every other one may. */
    private static boolean holdsMessages(EntityValue conversation) {
        !LlmConversationImpl.MODEL_ITEMS.equals(conversation.getString('messageModel'))
    }

    static final class Cond {
        final String field; final EntityCondition.ComparisonOperator operator; final Object value
        Cond(String f, EntityCondition.ComparisonOperator o, Object v) { field = f; operator = o; value = v }
    }

    private static EntityValue lock(ExecutionContext ec, String conversationId) {
        ec.entity.find('moqui.llm.LlmConversation').condition('conversationId', conversationId)
                .forUpdate(true).useCache(false).disableAuthz().one()
    }

    private static org.moqui.entity.EntityFind find(ExecutionContext ec, String entity, Map<String, Object> equals, List<Cond> more) {
        org.moqui.entity.EntityFind f = ec.entity.find(entity).useCache(false).disableAuthz()
        equals.each { String k, Object v -> f.condition(k, v) }
        for (Cond c : more) f.condition(c.field, c.operator, c.value)
        f
    }

    private static List<String> ids(ExecutionContext ec, String entity, String field, Map<String, Object> equals, List<Cond> more) {
        List<String> out = new ArrayList<>()
        for (EntityValue v : find(ec, entity, equals, more).selectField(field).list()) {
            String id = v.getString(field)
            if (id != null) out.add(id)
        }
        out
    }

    private static long count(ExecutionContext ec, String entity, Map<String, Object> equals, List<Cond> more) {
        find(ec, entity, equals, more).count()
    }

    private static long delete(ExecutionContext ec, String entity, Map<String, Object> equals, List<Cond> more) {
        find(ec, entity, equals, more).deleteAll()
    }

    private static List<List<String>> chunks(List<String> all) {
        List<List<String>> out = new ArrayList<>()
        if (all == null) return out
        for (int i = 0; i < all.size(); i += CHUNK) out.add(new ArrayList<String>(all.subList(i, Math.min(all.size(), i + CHUNK))))
        out
    }

    private static void closeConnections(ExecutionContext ec, String conversationId, Set<String> runIds) {
        if (!(ec.llm instanceof LlmFacadeImpl)) return
        LlmFacadeImpl facade = (LlmFacadeImpl) ec.llm
        facade.closeSessionsOfScope('conv:' + conversationId)
        for (String runId : runIds) facade.closeSessionsOfScope('run:' + runId)
        facade.clearCancelled(conversationId)
    }
}
