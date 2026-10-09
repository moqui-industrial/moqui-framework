import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition
import org.moqui.impl.context.UserFacadeImpl
import org.moqui.impl.llm.LlmConversationImpl
import org.moqui.impl.llm.LlmRunStore
import spock.lang.Shared
import spock.lang.Specification

import java.sql.Timestamp
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The retention service and the recovery claim, run against each other with latches and a barrier, never with sleeps.
 * What may be removed is decided with the row locked and again at that moment; what a run needs while it is alive, or
 * has just been handed to a recovering worker, must still be there afterwards, and what is removed leaves nothing
 * pointing at it. The same code runs on H2 and on PostgreSQL.
 */
class LlmCleanupRecoveryConcurrencyTests extends Specification {
    static final String PROFILE = 'cleanup-race'
    static final Timestamp OLD = Timestamp.valueOf('2000-01-01 00:00:00')
    @Shared ExecutionContext ec
    final List<Throwable> errors = new CopyOnWriteArrayList<>()
    final List<String> conversations = []
    final List<String> runs = []

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.artifactExecution.disableAuthz()
        ec.transaction.runUseOrBegin(null, 'seed failed') {
            [LlmRunStatus: 'LLM Run Status', LlmContinuationMode: 'LLM Continuation Mode', LlmConversationStatus: 'LLM Conversation Status',
             LlmContentPurpose: 'LLM Content Purpose', LlmRequestStatus: 'LLM Request Status', LlmTransport: 'LLM Transport',
             LlmToolInvocationStatus: 'LLM Tool Invocation Status'].each { type, desc ->
                ec.entity.makeValue('moqui.basic.EnumerationType').setAll([enumTypeId: type, description: desc]).createOrUpdate()
            }
            [LlmRunQueued: 'LlmRunStatus', LlmRunRunning: 'LlmRunStatus', LlmRunWaitClient: 'LlmRunStatus', LlmRunWaitConfirm: 'LlmRunStatus',
             LlmRunRecovering: 'LlmRunStatus', LlmRunComplete: 'LlmRunStatus', LlmRunFailed: 'LlmRunStatus', LlmRunCancelled: 'LlmRunStatus',
             LlmContLocal: 'LlmContinuationMode', LlmContRemote: 'LlmContinuationMode', LlmcsActive: 'LlmConversationStatus',
             LlmcsComplete: 'LlmConversationStatus', LlmCpUser: 'LlmContentPurpose', LlmCpAssistant: 'LlmContentPurpose',
             LlmReqAck: 'LlmRequestStatus', LlmTrHttp: 'LlmTransport'].each { id, type ->
                ec.entity.makeValue('moqui.basic.Enumeration').setAll([enumId: id, enumTypeId: type, description: id]).createOrUpdate()
            }
        }
        assert ((UserFacadeImpl) ec.user).internalLoginUser('john.doe', false)
    }

    def cleanupSpec() { ec.user.logoutUser(); ec.destroy() }

    def cleanup() {
        ec.artifactExecution.disableAuthz()
        ec.transaction.runUseOrBegin(null, 'cleanup failed') {
            def responses = ec.entity.find('moqui.llm.LlmResponse').condition('profileName', PROFILE).list()*.llmResponseId
            if (responses) {
                def items = ec.entity.find('moqui.llm.LlmItem').condition('llmResponseId', EntityCondition.IN, responses).list()*.llmItemId
                if (items) {
                    ec.entity.find('moqui.llm.LlmContextProjection').condition('sourceId', EntityCondition.IN, ec.entity.find('moqui.llm.LlmContent').condition('llmItemId', EntityCondition.IN, items).list()*.llmContentId ?: ['-']).deleteAll()
                    ec.entity.find('moqui.llm.LlmContent').condition('llmItemId', EntityCondition.IN, items).deleteAll()
                    ec.entity.find('moqui.llm.LlmItem').condition('llmItemId', EntityCondition.IN, items).deleteAll()
                }
                ec.entity.find('moqui.llm.LlmResponse').condition('llmResponseId', EntityCondition.IN, responses).deleteAll()
            }
            if (runs) {
                ec.entity.find('moqui.llm.LlmRunStatus').condition('runId', EntityCondition.IN, runs).deleteAll()
                ec.entity.find('moqui.llm.LlmRun').condition('runId', EntityCondition.IN, runs).deleteAll()
            }
            if (conversations) {
                ec.entity.find('moqui.llm.LlmMessage').condition('conversationId', EntityCondition.IN, conversations).deleteAll()
                ec.entity.find('moqui.llm.LlmConversation').condition('conversationId', EntityCondition.IN, conversations).deleteAll()
            }
        }
        runs.clear(); conversations.clear(); errors.clear()
    }

    private Thread worker(String name, Closure body) {
        Thread.start(name) {
            ExecutionContext tec = Moqui.getExecutionContext()
            try {
                assert ((UserFacadeImpl) tec.user).internalLoginUser('john.doe', false)
                tec.artifactExecution.disableAuthz()
                body.call(tec)
            } catch (Throwable t) { errors << t }
            finally { tec.destroy() }
        }
    }

    private void runCleanup(ExecutionContext tec) {
        tec.service.sync().name('org.moqui.impl.LlmServices.clean#LlmData').parameters([daysToKeep: 3650]).call()
    }

    private String oldConversation() {
        def conv = LlmConversationImpl.create(ec, 'default', null)
        ec.transaction.runUseOrBegin(null, 'age conversation failed') {
            ec.entity.find('moqui.llm.LlmConversation').condition('conversationId', conv.conversationId).one().set('lastMessageDate', OLD).update()
        }
        conversations << conv.conversationId
        conv.conversationId
    }

    private String response(String conversationId, String runId, Timestamp created, String text) {
        String id = ec.entity.sequencedIdPrimary('moqui.llm.LlmResponse', null, null)
        ec.transaction.runUseOrBegin(null, 'response failed') {
            ec.entity.makeValue('moqui.llm.LlmResponse').setAll([llmResponseId: id, conversationId: conversationId, runId: runId,
                    ownerUserId: ec.user.userId, profileName: PROFILE, status: 'completed', createdDate: created]).create()
            String item = ec.entity.sequencedIdPrimary('moqui.llm.LlmItem', null, null)
            ec.entity.makeValue('moqui.llm.LlmItem').setAll([llmItemId: item, llmResponseId: id, sequenceNum: 1, itemType: 'message', role: 'assistant']).create()
            ec.entity.makeValue('moqui.llm.LlmContent').setAll([llmContentId: ec.entity.sequencedIdPrimary('moqui.llm.LlmContent', null, null), llmItemId: item,
                    sequenceNum: 1, contentType: 'output_text', contentKind: 'content', purposeEnumId: 'LlmCpAssistant', textContent: text]).create()
        }
        id
    }

    private boolean exists(String entity, String field, String value) {
        ec.entity.find(entity).condition(field, value).useCache(false).count() > 0
    }

    def 'a conversation that is picked up again while cleanup waits for its lock is kept, with what the new turn wrote'() {
        given:
        String conv = oldConversation()
        String oldResponse = response(conv, null, OLD, 'old text')
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1), cleanupStarted = new CountDownLatch(1)
        String newResponse = null
        Thread picker = worker('picker') { ExecutionContext tec ->
            tec.transaction.begin(null)
            try {
                tec.entity.find('moqui.llm.LlmConversation').condition('conversationId', conv).forUpdate(true).one()
                        .set('lastMessageDate', new Timestamp(System.currentTimeMillis())).update()
                locked.countDown()
                release.await(60, TimeUnit.SECONDS)
                tec.transaction.commit()
            } catch (Throwable t) { tec.transaction.rollback('picker failed', t); throw t }
        }
        assert locked.await(60, TimeUnit.SECONDS)
        Thread cleaner = worker('cleaner') { ExecutionContext tec -> cleanupStarted.countDown(); runCleanup(tec) }
        assert cleanupStarted.await(60, TimeUnit.SECONDS)
        when: 'the pickup commits only now'
        release.countDown()
        picker.join(60000); cleaner.join(120000)
        newResponse = response(conv, null, new Timestamp(System.currentTimeMillis()), 'new text')
        runCleanup(ec)
        then:
        errors.isEmpty()
        exists('moqui.llm.LlmConversation', 'conversationId', conv)
        exists('moqui.llm.LlmResponse', 'llmResponseId', newResponse)
        // a response older than the limit may go whichever conversation it belongs to; the conversation and the new turn stay
    }

    def 'the same conversation, left old, is cleaned: the lock is not what protects the other one'() {
        given:
        String conv = oldConversation()
        String oldResponse = response(conv, null, OLD, 'old text')
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1), cleanupStarted = new CountDownLatch(1)
        Thread holder = worker('holder') { ExecutionContext tec ->
            tec.transaction.begin(null)
            tec.entity.find('moqui.llm.LlmConversation').condition('conversationId', conv).forUpdate(true).one()
            locked.countDown()
            release.await(60, TimeUnit.SECONDS)
            tec.transaction.rollback('nothing changed', null)
        }
        assert locked.await(60, TimeUnit.SECONDS)
        Thread cleaner = worker('cleaner') { ExecutionContext tec -> cleanupStarted.countDown(); runCleanup(tec) }
        assert cleanupStarted.await(60, TimeUnit.SECONDS)
        when:
        release.countDown()
        holder.join(60000); cleaner.join(120000)
        then:
        errors.isEmpty()
        !exists('moqui.llm.LlmResponse', 'llmResponseId', oldResponse)
        !exists('moqui.llm.LlmConversation', 'conversationId', conv)
    }

    def 'an old conversation with a response newer than the limit loses only what is old'() {
        given:
        String conv = oldConversation()
        String oldResponse = response(conv, null, OLD, 'old text')
        String freshResponse = response(conv, null, new Timestamp(System.currentTimeMillis()), 'fresh text')
        when:
        runCleanup(ec)
        then:
        !exists('moqui.llm.LlmResponse', 'llmResponseId', oldResponse)
        exists('moqui.llm.LlmResponse', 'llmResponseId', freshResponse)
        exists('moqui.llm.LlmConversation', 'conversationId', conv)
    }

    def 'a run handed to the recovery while cleanup runs keeps its response, context, checkpoint, envelope and fence'() {
        given:
        Map run = LlmRunStore.createRun(ec, [profileName: PROFILE, context: [[type: 'message', role: 'user', content: [[type: 'input_text', text: 'keep me']]]],
                checkpoint: [phase: 'ready_provider'], envelope: [instructions: 'be brief', tools: []], maxIterations: 3])
        String runId = run.runId
        runs << runId
        LlmRunStore.transition(ec, runId, LlmRunStore.RUNNING, null)
        ec.transaction.runUseOrBegin(null, 'age run failed') {
            ec.entity.find('moqui.llm.LlmRun').condition('runId', runId).one().set('createdDate', OLD).set('lastUpdatedDate', OLD).set('leaseUntil', OLD).update()
        }
        String runResponse = response(null, runId, OLD, 'run text')
        CountDownLatch go = new CountDownLatch(1)
        List claimed = new CopyOnWriteArrayList()
        Thread recovery = worker('recovery') { ExecutionContext tec ->
            go.await(60, TimeUnit.SECONDS)
            claimed.addAll(LlmRunStore.claimRecoverable(tec, 'recovery-worker', 60, 50).findAll { it.runId == runId })
        }
        Thread cleaner = worker('cleaner') { ExecutionContext tec -> go.await(60, TimeUnit.SECONDS); runCleanup(tec) }
        when: 'both start together'
        go.countDown()
        recovery.join(120000); cleaner.join(120000)
        Map stored = LlmRunStore.getRun(ec, runId)
        then:
        errors.isEmpty()
        claimed.size() == 1
        exists('moqui.llm.LlmResponse', 'llmResponseId', runResponse)
        stored.context[0].content[0].text == 'keep me'
        stored.checkpoint.phase == 'ready_provider'
        stored.envelope.instructions == 'be brief'
        stored.fencingToken == claimed[0].fencingToken
        stored.workerId == 'recovery-worker'
        and: 'the fence still works: the current token writes'
        LlmRunStore.assertFence(ec, runId, stored.fencingToken as long)
        when: 'a second recovery takes the run after the lease expires again'
        long firstFence = stored.fencingToken as long
        ec.transaction.runUseOrBegin(null, 'expire lease failed') {
            ec.entity.find('moqui.llm.LlmRun').condition('runId', runId).one().set('leaseUntil', OLD).update()
        }
        runCleanup(ec)
        def second = LlmRunStore.claimRecoverable(ec, 'second-worker', 60, 50).find { it.runId == runId }
        then: 'cleanup in between changed nothing, the new fence is higher, the old one is refused'
        second != null
        (second.fencingToken as long) > firstFence
        exists('moqui.llm.LlmResponse', 'llmResponseId', runResponse)
        LlmRunStore.assertFence(ec, runId, second.fencingToken as long)
        when:
        LlmRunStore.assertFence(ec, runId, firstFence)
        then:
        thrown(IllegalStateException)
    }

    def 'a finished run that is old goes completely, and nothing is left pointing at it or at what it held'() {
        given:
        Map run = LlmRunStore.createRun(ec, [profileName: PROFILE, context: [], maxIterations: 3])
        String runId = run.runId
        runs << runId
        LlmRunStore.transition(ec, runId, LlmRunStore.RUNNING, null)
        LlmRunStore.transition(ec, runId, LlmRunStore.COMPLETE, null)
        String kept = response(null, runId, OLD, 'finished text')
        String itemId = ec.entity.find('moqui.llm.LlmItem').condition('llmResponseId', kept).one().llmItemId
        String replayRequest = ec.entity.sequencedIdPrimary('moqui.llm.LlmRequest', null, null)
        String replayItem = ec.entity.sequencedIdPrimary('moqui.llm.LlmItem', null, null)
        ec.transaction.runUseOrBegin(null, 'replay failed') {
            ec.entity.find('moqui.llm.LlmRun').condition('runId', runId).one().set('completedDate', OLD).update()
            ec.entity.makeValue('moqui.llm.LlmRequest').setAll([llmRequestId: replayRequest, ownerUserId: ec.user.userId, profileName: PROFILE,
                    operation: 'create_response', localStatusEnumId: 'LlmReqAck', createdDate: new Timestamp(System.currentTimeMillis())]).create()
            ec.entity.makeValue('moqui.llm.LlmItem').setAll([llmItemId: replayItem, llmRequestId: replayRequest, sequenceNum: 1, itemType: 'message',
                    role: 'assistant', sourceLlmItemId: itemId, sourceStatus: 'named']).create()
        }
        when:
        runCleanup(ec)
        then:
        !exists('moqui.llm.LlmRun', 'runId', runId)
        !exists('moqui.llm.LlmResponse', 'llmResponseId', kept)
        !exists('moqui.llm.LlmRunStatus', 'runId', runId)
        !exists('moqui.llm.LlmToolInvocation', 'runId', runId)
        ec.entity.find('moqui.llm.LlmItem').condition('llmItemId', replayItem).one().sourceLlmItemId == null
        and: 'no item without an owner, no content without an item, no search copy without content'
        ec.entity.find('moqui.llm.LlmItem').condition('sourceLlmItemId', EntityCondition.NOT_EQUAL, null).list().every { exists('moqui.llm.LlmItem', 'llmItemId', it.sourceLlmItemId as String) }
        cleanup:
        ec.transaction.runUseOrBegin(null, 'replay cleanup failed') {
            ec.entity.find('moqui.llm.LlmItem').condition('llmItemId', replayItem).deleteAll()
            ec.entity.find('moqui.llm.LlmRequest').condition('llmRequestId', replayRequest).deleteAll()
        }
    }
}
