package org.moqui.impl.llm

import com.sun.net.httpserver.HttpServer
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition
import org.moqui.llm.LlmException
import org.moqui.llm.LlmTool

import java.sql.Timestamp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Deleting a conversation of the Open Responses model, and keeping the one a retention run leaves in place. */
class LlmConversationDeleteTests extends LlmConversationSpecBase {

    private List<String> all(String entity, String field, Map where) {
        def find = ec.entity.find(entity).disableAuthz().useCache(false)
        where.each { k, v -> find.condition(k as String, v) }
        find.list().collect { it.getString(field) }
    }

    /** Every row that belongs to the conversation by any route: its own key, its runs, requests, responses, items and content. */
    private Map<String, List<String>> footprint(String conversationId) {
        Map<String, List<String>> f = [:]
        f.runs = all('moqui.llm.LlmRun', 'runId', [conversationId: conversationId])
        f.requests = all('moqui.llm.LlmRequest', 'llmRequestId', [conversationId: conversationId])
        f.responses = all('moqui.llm.LlmResponse', 'llmResponseId', [conversationId: conversationId])
        f.items = []
        f.requests.each { f.items.addAll(all('moqui.llm.LlmItem', 'llmItemId', [llmRequestId: it])) }
        f.responses.each { f.items.addAll(all('moqui.llm.LlmItem', 'llmItemId', [llmResponseId: it])) }
        f.content = []
        f.items.each { f.content.addAll(all('moqui.llm.LlmContent', 'llmContentId', [llmItemId: it])) }
        f.events = []
        f.responses.each { f.events.addAll(all('moqui.llm.LlmResponseEvent', 'llmResponseEventId', [llmResponseId: it])) }
        f.callLogs = all('moqui.llm.LlmCallLog', 'callId', [conversationId: conversationId])
        f.projection = all('moqui.llm.LlmContextProjection', 'contextProjectionId', [conversationId: conversationId])
        f.runs.each { f.projection.addAll(all('moqui.llm.LlmContextProjection', 'contextProjectionId', [runId: it])) }
        f
    }

    private int remaining(Map<String, List<String>> before) {
        int left = 0
        Map<String, List<String>> entities = [runs: ['moqui.llm.LlmRun', 'runId'], requests: ['moqui.llm.LlmRequest', 'llmRequestId'],
                responses: ['moqui.llm.LlmResponse', 'llmResponseId'], items: ['moqui.llm.LlmItem', 'llmItemId'],
                content: ['moqui.llm.LlmContent', 'llmContentId'], events: ['moqui.llm.LlmResponseEvent', 'llmResponseEventId'],
                callLogs: ['moqui.llm.LlmCallLog', 'callId'], projection: ['moqui.llm.LlmContextProjection', 'contextProjectionId']]
        entities.each { String key, List<String> spec ->
            before[key].each { String id -> left += ec.entity.find(spec[0]).condition(spec[1], id).disableAuthz().useCache(false).count() as int }
        }
        left
    }

    private Map deleteAs(String conversationId) {
        boolean off = ec.artifactExecution.disableAuthz()
        try { return LlmGateway.deleteConversationOf(ec, conversationId) } finally { if (!off) ec.artifactExecution.enableAuthz() }
    }

    private LlmTool lookup() {
        new LlmTool() {
            String getName() { 'lookup' }
            String getDescription() { 'looks up' }
            Map<String, Object> getParametersSchema() { [type: 'object', properties: [q: [type: 'string']]] }
            LlmTool.Execution getExecution() { LlmTool.Execution.SERVER }
            Object execute(Map<String, Object> arguments, ExecutionContext x) { [found: arguments.q] }
        }
    }

    def "deleting a conversation removes every row that belongs to it, and nothing of anyone else"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, toolCallResponse('resp_d1', 'call_d', 'lookup', '{"q":"x"}'))
        provider.enqueueJson(200, answer('resp_d2', 'found it'))
        provider.enqueueJson(200, answer('resp_d3', 'second turn'))
        provider.enqueueJson(200, answer('resp_other', 'someone else'))
        def p = profile(provider.endpoint)
        def conv = LlmConversationImpl.create(ec, p.name, null)
        def bystander = LlmConversationImpl.create(ec, p.name, null)
        turn(p, conv) { it.tool(lookup()).user('look it up') }
        turn(p, conv) { it.user('and again') }
        turn(p, bystander) { it.user('not yours to delete') }
        Map<String, List<String>> mine = footprint(conv.conversationId)
        Map<String, List<String>> theirs = footprint(bystander.conversationId)
        assert mine.runs.size() == 2 && mine.requests.size() >= 3 && mine.items && mine.content
        when:
        Map out = deleteAs(conv.conversationId)
        then:
        out.deleted == true && out.httpStatus == 200
        remaining(mine) == 0
        ec.entity.find('moqui.llm.LlmConversation').condition('conversationId', conv.conversationId).disableAuthz().count() == 0
        and: 'the other conversation is whole'
        remaining(theirs) == theirs.values().sum { it.size() }
        ec.entity.find('moqui.llm.LlmConversation').condition('conversationId', bystander.conversationId).disableAuthz().count() == 1
        cleanup: provider.close()
    }

    def "a conversation with a run that is working is refused with 409 and stays whole, then goes once the run ended"() {
        given:
        CountDownLatch arrived = new CountDownLatch(1)
        CountDownLatch release = new CountDownLatch(1)
        HttpServer server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/v1/responses') { exchange ->
            exchange.requestBody.getText('UTF-8')
            arrived.countDown()
            release.await(30, TimeUnit.SECONDS)
            byte[] bytes = answer('resp_slow', 'finally').getBytes('UTF-8')
            exchange.responseHeaders.set('Content-Type', 'application/json')
            exchange.sendResponseHeaders(200, bytes.length)
            exchange.responseBody.withCloseable { it.write(bytes) }
        }
        server.start()
        def p = profile("http://127.0.0.1:${server.address.port}/v1/responses")
        def conv = LlmConversationImpl.create(ec, p.name, null)
        Throwable turnFailure = null
        Thread worker = Thread.start {
            try {
                inNewEc(USERNAME) { ExecutionContext other ->
                    new LlmClientImpl(other, p, { false }).conversation(LlmConversationImpl.load(other, conv.conversationId, true)).user('slow one').call()
                }
            } catch (Throwable t) { turnFailure = t }
        }
        assert arrived.await(30, TimeUnit.SECONDS)
        when: 'the owner deletes while the provider has not answered'
        Map refused = deleteAs(conv.conversationId)
        then:
        refused.deleted == false && refused.httpStatus == 409
        ec.entity.find('moqui.llm.LlmConversation').condition('conversationId', conv.conversationId).disableAuthz().count() == 1
        ec.entity.find('moqui.llm.LlmRun').condition('conversationId', conv.conversationId).disableAuthz().count() == 1

        when: 'the turn ends and the delete is repeated'
        release.countDown()
        worker.join(60000)
        Map done = deleteAs(conv.conversationId)
        then:
        turnFailure == null
        done.deleted == true
        ec.entity.find('moqui.llm.LlmRun').condition('conversationId', conv.conversationId).disableAuthz().count() == 0
        cleanup:
        release.countDown()
        server.stop(0)
    }

    def "a conversation that waits for its client is deleted with the run that waits, and its connections are closed"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, toolCallResponse('resp_w', 'call_w', 'ask_form', '{}'))
        SpyProtocol spy = new SpyProtocol()
        def p = profile(provider.endpoint, 'spy-profile', spy)
        register(p)
        def conv = LlmConversationImpl.create(ec, p.name, null)
        LlmTool form = new LlmTool() {
            String getName() { 'ask_form' }
            String getDescription() { 'asks' }
            Map<String, Object> getParametersSchema() { [type: 'object', properties: [:]] }
            LlmTool.Execution getExecution() { LlmTool.Execution.CLIENT }
            Object execute(Map<String, Object> arguments, ExecutionContext x) { null }
        }
        def first = turn(p, conv) { it.tool(form).allowClientTools(true).user('ask me') }
        Map<String, List<String>> mine = footprint(conv.conversationId)
        assert first.yielded && mine.runs.size() == 1
        when:
        Map out = deleteAs(conv.conversationId)
        then:
        out.deleted == true
        remaining(mine) == 0
        spy.closedScopes.contains('conv:' + conv.conversationId)
        spy.closedScopes.contains('run:' + mine.runs[0])
        cleanup:
        unregister(p.name)
        provider.close()
    }

    def "retention that keeps a conversation also keeps the run it continues from, and the next turn replays it"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, answer('resp_k1', 'old answer'))
        provider.enqueueJson(400, '{"error":{"message":"bad","type":"invalid_request_error"}}')
        provider.enqueueJson(200, answer('resp_k3', 'after retention'))
        def p = profile(provider.endpoint)
        def conv = LlmConversationImpl.create(ec, p.name, null)
        turn(p, conv) { it.user('old question') }
        String head = LlmConversationImpl.load(ec, conv.conversationId, true).headRunId
        try { turn(p, LlmConversationImpl.load(ec, conv.conversationId, true)) { it.user('recent and failing') } } catch (LlmException expected) { }
        Timestamp longAgo = new Timestamp(System.currentTimeMillis() - 200L * 24 * 3600 * 1000)
        boolean off = ec.artifactExecution.disableAuthz()
        try {
            ec.transaction.runUseOrBegin(null, 'age the first turn') {
                ec.entity.find('moqui.llm.LlmRun').condition('runId', head).updateAll([completedDate: longAgo, createdDate: longAgo] as Map<String, Object>)
                ec.entity.find('moqui.llm.LlmResponse').condition('runId', head).updateAll([createdDate: longAgo] as Map<String, Object>)
                ec.entity.find('moqui.llm.LlmRequest').condition('runId', head).updateAll([createdDate: longAgo] as Map<String, Object>)
                ec.entity.find('moqui.llm.LlmConversation').condition('conversationId', conv.conversationId).updateAll([lastMessageDate: longAgo] as Map<String, Object>)
            }
        } finally { if (!off) ec.artifactExecution.enableAuthz() }
        when:
        ec.service.sync().name('org.moqui.impl.LlmServices.clean#LlmData').parameter('daysToKeep', 30).disableAuthz().call()
        def reopened = LlmConversationImpl.load(ec, conv.conversationId, true)
        turn(p, reopened) { it.user('third') }
        def input = provider.requests[2].json.input.collect { m -> (m.content ?: []).collect { it.text }.join('') }
        then: 'the head run is still there and the replay still has the old turn'
        ec.entity.find('moqui.llm.LlmRun').condition('runId', head).disableAuthz().count() == 1
        reopened.headRunId != null
        input.take(2) == ['old question', 'old answer']
        cleanup: provider.close()
    }

    def "retention removes a whole old conversation through the same procedure"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, answer('resp_g1', 'gone soon'))
        def p = profile(provider.endpoint)
        def conv = LlmConversationImpl.create(ec, p.name, null)
        turn(p, conv) { it.user('an old chat') }
        Map<String, List<String>> mine = footprint(conv.conversationId)
        Timestamp longAgo = new Timestamp(System.currentTimeMillis() - 200L * 24 * 3600 * 1000)
        boolean off = ec.artifactExecution.disableAuthz()
        try {
            ec.transaction.runUseOrBegin(null, 'age it') {
                mine.runs.each { ec.entity.find('moqui.llm.LlmRun').condition('runId', it).updateAll([completedDate: longAgo, createdDate: longAgo] as Map<String, Object>) }
                mine.responses.each { ec.entity.find('moqui.llm.LlmResponse').condition('llmResponseId', it).updateAll([createdDate: longAgo] as Map<String, Object>) }
                mine.requests.each { ec.entity.find('moqui.llm.LlmRequest').condition('llmRequestId', it).updateAll([createdDate: longAgo] as Map<String, Object>) }
                mine.callLogs.each { ec.entity.find('moqui.llm.LlmCallLog').condition('callId', it).updateAll([startDate: longAgo] as Map<String, Object>) }
                ec.entity.find('moqui.llm.LlmConversation').condition('conversationId', conv.conversationId).updateAll([lastMessageDate: longAgo] as Map<String, Object>)
            }
        } finally { if (!off) ec.artifactExecution.enableAuthz() }
        when:
        ec.service.sync().name('org.moqui.impl.LlmServices.clean#LlmData').parameter('daysToKeep', 30).disableAuthz().call()
        then:
        ec.entity.find('moqui.llm.LlmConversation').condition('conversationId', conv.conversationId).disableAuthz().count() == 0
        remaining(mine) == 0
        cleanup: provider.close()
    }

    // ---- a protocol that records which connection scopes were closed, and profile registration

    static class SpyProtocol extends OpenResponsesProtocol {
        final List<String> closedScopes = Collections.synchronizedList([])
        @Override void closeSessionsOfScope(String scope) { closedScopes << scope }
    }
}
