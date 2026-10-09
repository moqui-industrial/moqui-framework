package org.moqui.impl.llm

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.UserFacadeImpl
import org.moqui.llm.LlmException

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

/**
 * A delete or a retention run against a turn of the same conversation. The two start together at a barrier, many times, and
 * whatever the interleaving, the end state has to be one of the two honest ones, and nothing may be left pointing at nothing.
 */
class LlmConversationRaceTests extends LlmConversationSpecBase {
    private static final int ROUNDS = 20

    private Thread racer(String name, CyclicBarrier gate, List<Throwable> errors, List results, Closure body) {
        Thread.start(name) {
            ExecutionContext tec = Moqui.getExecutionContext()
            try {
                assert ((UserFacadeImpl) tec.user).internalLoginUser(USERNAME, false)
                tec.artifactExecution.disableAuthz()
                tec.artifactExecution.disableTarpit()
                gate.await(30, TimeUnit.SECONDS)
                results << body.call(tec)
            } catch (Throwable t) { errors << t }
            finally { tec.destroy() }
        }
    }

    private long count(String entity, String field, String id) {
        boolean off = ec.artifactExecution.disableAuthz()
        try { return ec.entity.find(entity).condition(field, id).useCache(false).count() } finally { if (!off) ec.artifactExecution.enableAuthz() }
    }

    /** Moves everything stored so far for a conversation the given number of days into the past. */
    private void ageConversation(String conversationId, int days) {
        java.sql.Timestamp then = new java.sql.Timestamp(System.currentTimeMillis() - days * 86400_000L)
        boolean off = ec.artifactExecution.disableAuthz()
        try {
            ec.entity.find('moqui.llm.LlmConversation').condition('conversationId', conversationId).useCache(false)
                    .updateAll([lastMessageDate: then] as Map<String, Object>)
            ec.entity.find('moqui.llm.LlmRun').condition('conversationId', conversationId).useCache(false)
                    .updateAll([createdDate: then, startedDate: then, completedDate: then, lastUpdatedDate: then] as Map<String, Object>)
            ec.entity.find('moqui.llm.LlmRequest').condition('conversationId', conversationId).useCache(false)
                    .updateAll([createdDate: then] as Map<String, Object>)
            ec.entity.find('moqui.llm.LlmResponse').condition('conversationId', conversationId).useCache(false)
                    .updateAll([createdDate: then] as Map<String, Object>)
        } finally { if (!off) ec.artifactExecution.enableAuthz() }
    }

    /** Rows of any table that belong to a conversation that is gone. */
    private List<String> orphans(String conversationId) {
        List<String> found = []
        ['moqui.llm.LlmRun', 'moqui.llm.LlmRequest', 'moqui.llm.LlmResponse', 'moqui.llm.LlmMessage'].each { String entity ->
            if (count(entity, 'conversationId', conversationId) > 0) found << entity
        }
        if (found.contains('moqui.llm.LlmRun')) {
            boolean off = ec.artifactExecution.disableAuthz()
            try {
                ec.entity.find('moqui.llm.LlmRun').condition('conversationId', conversationId).useCache(false).list().each {
                    found << "run ${it.runId} ${it.statusId} created=${it.createdDate} completed=${it.completedDate} worker=${it.workerId}".toString()
                }
            } finally { if (!off) ec.artifactExecution.enableAuthz() }
        }
        found
    }

    def 'a delete and a new turn at the same instant leave either a deleted conversation with nothing behind it, or a finished turn and a refused delete'() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        def p = profile(provider.endpoint, 'race-delete')
        register(p)
        List<String> verdicts = []
        when:
        ROUNDS.times { round ->
            provider.enqueueJson(200, answer('resp_rd_a' + round, 'first'))
            provider.enqueueJson(200, answer('resp_rd_b' + round, 'second'))
            def conv = LlmConversationImpl.create(ec, p.name, null)
            turn(p, conv) { it.user('one') }
            CyclicBarrier gate = new CyclicBarrier(2)
            List errors = new CopyOnWriteArrayList(), turnResults = new CopyOnWriteArrayList(), deleteResults = new CopyOnWriteArrayList()
            Thread t = racer('race-turn', gate, errors, turnResults) { ExecutionContext tec ->
                LlmClientImpl c = new LlmClientImpl(tec, p, { false })
                c.conversation(LlmConversationImpl.load(tec, conv.conversationId, true)).user('two')
                c.call().content
            }
            Thread d = racer('race-delete', gate, errors, deleteResults) { ExecutionContext tec -> LlmGateway.deleteConversationOf(tec, conv.conversationId) }
            t.join(60000); d.join(60000)
            boolean deleted = deleteResults && deleteResults[0].deleted == true
            boolean turnDone = !turnResults.isEmpty()
            if (deleted) {
                assert orphans(conv.conversationId).isEmpty(), "round ${round}: rows left behind ${orphans(conv.conversationId)}"
                assert count('moqui.llm.LlmConversation', 'conversationId', conv.conversationId) == 0
                verdicts << 'deleted'
            } else {
                // the delete was refused or failed: the conversation is whole and its turn, if it ran, is complete
                assert count('moqui.llm.LlmConversation', 'conversationId', conv.conversationId) == 1, "round ${round}: neither deleted nor whole"
                if (turnDone) {
                    assert LlmConversationImpl.load(ec, conv.conversationId, true).history.any { it.content == 'second' }
                }
                verdicts << (turnDone ? 'turn-won' : 'neither')
            }
            errors.findAll { !(it instanceof LlmException) && !(it.message?.contains('not found') || it.message?.contains('does not exist')) }
                    .each { throw it }
        }
        then:
        verdicts.size() == ROUNDS
        !verdicts.contains('neither')
        cleanup:
        unregister('race-delete')
        provider.close()
    }

    def 'retention against a turn at the same instant leaves either nothing, or a whole conversation that can be continued'() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        def p = profile(provider.endpoint, 'race-retention')
        register(p)
        when:
        ROUNDS.times { round ->
            provider.enqueueJson(200, answer('resp_rr_a' + round, 'first'))
            provider.enqueueJson(200, answer('resp_rr_b' + round, 'second'))
            provider.enqueueJson(200, answer('resp_rr_c' + round, 'third'))
            def conv = LlmConversationImpl.create(ec, p.name, null)
            turn(p, conv) { it.user('one') }
            ageConversation(conv.conversationId, 3)
            CyclicBarrier gate = new CyclicBarrier(2)
            List errors = new CopyOnWriteArrayList(), turnResults = new CopyOnWriteArrayList(), purgeResults = new CopyOnWriteArrayList()
            Thread t = racer('race-turn2', gate, errors, turnResults) { ExecutionContext tec ->
                LlmClientImpl c = new LlmClientImpl(tec, p, { false })
                c.conversation(LlmConversationImpl.load(tec, conv.conversationId, true)).user('two')
                c.call().content
            }
            Thread r = racer('race-purge', gate, errors, purgeResults) { ExecutionContext tec ->
                // the retention job itself: the first turn is three days old and the limit is one day, so what the racing turn
                // writes is newer than the limit and has to keep the conversation; the job removes a conversation with its runs
                tec.service.sync().name('org.moqui.impl.LlmServices.clean#LlmData').parameter('daysToKeep', 1).disableAuthz().call()
            }
            t.join(60000); r.join(60000)
            // a racer that lost may be refused with an explicit error; anything else, and any damage to the data, fails the round
            errors.findAll { !(it instanceof LlmException) }.each { throw new AssertionError("round ${round}: " + it.toString(), it) }
            errors.findAll { it instanceof LlmException }.each { println("RACE-LOSER round ${round}: ${it.message}") }
            // everything here is older than the cutoff, so a conversation with no open run at that moment goes whole, which is an
            // honest end; one that is left has to be whole too: its head run is there and it can be continued
            boolean exists = count('moqui.llm.LlmConversation', 'conversationId', conv.conversationId) == 1
            if (!exists) assert orphans(conv.conversationId).isEmpty(), "round ${round}: rows left behind ${orphans(conv.conversationId)}"
            if (exists) {
                def after = LlmConversationImpl.load(ec, conv.conversationId, true)
                if (after.headRunId != null) assert count('moqui.llm.LlmRun', 'runId', after.headRunId) == 1, "round ${round}: the head run is gone"
                // whatever survived can still be continued
                provider.enqueueJson(200, answer('resp_rr_d' + round, 'again'))
                def next = turn(p, after) { it.user('three') }
                assert next.content != null
            }
        }
        then:
        noExceptionThrown()
        cleanup:
        unregister('race-retention')
        provider.close()
    }
}
