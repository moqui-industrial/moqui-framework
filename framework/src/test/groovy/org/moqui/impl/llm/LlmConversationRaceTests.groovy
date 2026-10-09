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

    /** Rows of any table that belong to a conversation that is gone. */
    private List<String> orphans(String conversationId) {
        List<String> found = []
        ['moqui.llm.LlmRun', 'moqui.llm.LlmRequest', 'moqui.llm.LlmResponse', 'moqui.llm.LlmMessage'].each { String entity ->
            if (count(entity, 'conversationId', conversationId) > 0) found << entity
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

    def 'retention against a turn that completes at the same instant never removes the run the conversation continues from'() {
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
            CyclicBarrier gate = new CyclicBarrier(2)
            List errors = new CopyOnWriteArrayList(), turnResults = new CopyOnWriteArrayList(), purgeResults = new CopyOnWriteArrayList()
            Thread t = racer('race-turn2', gate, errors, turnResults) { ExecutionContext tec ->
                LlmClientImpl c = new LlmClientImpl(tec, p, { false })
                c.conversation(LlmConversationImpl.load(tec, conv.conversationId, true)).user('two')
                c.call().content
            }
            Thread r = racer('race-purge', gate, errors, purgeResults) { ExecutionContext tec ->
                // everything that exists is older than this cutoff, so only the rule about the head and open runs protects it
                LlmConversationPurge.purgeOlderThan(tec, conv.conversationId, new java.sql.Timestamp(System.currentTimeMillis() + 3600_000L))
            }
            t.join(60000); r.join(60000)
            // a racer that lost may be refused with an explicit error; anything else, and any damage to the data, fails the round
            errors.findAll { !(it instanceof LlmException) }.each { throw new AssertionError("round ${round}: " + it.toString(), it) }
            errors.findAll { it instanceof LlmException }.each { println("RACE-LOSER round ${round}: ${it.message}") }
            def after = LlmConversationImpl.load(ec, conv.conversationId, true)
            boolean exists = count('moqui.llm.LlmConversation', 'conversationId', conv.conversationId) == 1
            if (exists && after.headRunId != null) {
                assert count('moqui.llm.LlmRun', 'runId', after.headRunId) == 1, "round ${round}: the head run is gone"
            }
            if (exists) {
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
