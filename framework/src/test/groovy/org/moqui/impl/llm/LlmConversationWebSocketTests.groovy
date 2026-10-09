package org.moqui.impl.llm

import org.moqui.context.ExecutionContext
import org.moqui.llm.LlmException
import org.moqui.llm.LlmResponse
import org.moqui.llm.LlmTransport

/** The WebSocket of an Open Responses conversation outlives its turns, and is used for the next one only when it is safe. */
class LlmConversationWebSocketTests extends LlmConversationSpecBase {
    private LlmResponse wsTurn(ExecutionContext context, LlmFacadeImpl.ProfileState p, String convId, String text, String turnContext = null) {
        LlmConversationImpl conv = LlmConversationImpl.load(context, convId, true)
        LlmClientImpl client = new LlmClientImpl(context, p, { false })
        client.transport(LlmTransport.WEBSOCKET)
        client.conversation(conv)
        if (turnContext != null) client.injectContext('session', turnContext)
        client.user(text)
        boolean was = context.artifactExecution.disableAuthz()
        try { return client.call() } finally { if (!was) context.artifactExecution.enableAuthz() }
    }

    def 'two turns of a conversation with distinct clients share one connection and the second sends only the new input'() {
        given:
        def fake = new ResponsesWebSocketFake()
        def p = profile(fake.endpoint, 'ws-conv-a')
        def conv = LlmConversationImpl.create(ec, p.name, null)
        when:
        def first = wsTurn(ec, p, conv.conversationId, 'first question')
        def second = inNewEc(USERNAME) { ExecutionContext other -> wsTurn(other, p, conv.conversationId, 'second question') }
        then:
        first.content == 'answer 1'
        second.content == 'answer 2'
        fake.connections.size() == 1
        fake.creates.size() == 2
        fake.creates[1].body.previous_response_id == 'resp_ws_1'
        fake.creates[1].body.input.size() == 1
        rowsOf(conv.conversationId) == 0
        cleanup:
        p.protocol.closeSessionsOfScope('conv:' + conv.conversationId)
        fake.close()
    }

    def 'a connection lost between turns makes the next turn replay the full local trajectory on a new one'() {
        given:
        def fake = new ResponsesWebSocketFake()
        fake.closeAfterTurn << 0
        def p = profile(fake.endpoint, 'ws-conv-b')
        def conv = LlmConversationImpl.create(ec, p.name, null)
        when:
        wsTurn(ec, p, conv.conversationId, 'first question')
        fake.waitUntil(3000) { fake.connections[0].closed }
        def second = wsTurn(ec, p, conv.conversationId, 'second question')
        then:
        second.content.startsWith('answer')
        fake.connections.size() == 2
        fake.creates.last().body.previous_response_id == null
        fake.creates.last().body.input.size() == 3
        cleanup:
        p.protocol.closeSessionsOfScope('conv:' + conv.conversationId)
        fake.close()
    }

    def 'deleting the conversation closes its connection'() {
        given:
        def fake = new ResponsesWebSocketFake()
        def p = profile(fake.endpoint, 'ws-conv-c')
        def conv = LlmConversationImpl.create(ec, p.name, null)
        wsTurn(ec, p, conv.conversationId, 'hello')
        expect: 'the connection is still open after the turn'
        !fake.connections[0].closed
        when:
        p.protocol.closeSessionsOfScope('conv:' + conv.conversationId)
        then:
        fake.waitUntil(3000) { fake.connections[0].closed }
        cleanup: fake.close()
    }

    def 'a provider that dropped its cached response fails the turn explicitly, keeps the head, and the next turn starts a new chain from the full trajectory'() {
        given:
        def fake = new ResponsesWebSocketFake()
        def p = profile(fake.endpoint, 'ws-conv-e')
        def conv = LlmConversationImpl.create(ec, p.name, null)
        wsTurn(ec, p, conv.conversationId, 'first question')
        def headBefore = LlmConversationImpl.load(ec, conv.conversationId, true).headRunId
        fake.connections[0].cachedResponseId = null
        when: 'the provider no longer holds what the next turn continues from'
        wsTurn(ec, p, conv.conversationId, 'second question')
        then:
        LlmException e = thrown()
        e.message.toLowerCase().contains('previous response')
        LlmConversationImpl.load(ec, conv.conversationId, true).headRunId == headBefore
        when: 'the same turn is tried again'
        def again = wsTurn(ec, p, conv.conversationId, 'second question')
        then: 'it goes on a new connection with the whole trajectory and no previous response id'
        again.content.startsWith('answer')
        fake.connections.size() == 2
        fake.creates.last().body.previous_response_id == null
        fake.creates.last().body.input.size() == 3
        cleanup:
        p.protocol.closeSessionsOfScope('conv:' + conv.conversationId)
        fake.close()
    }

    def 'the context that each turn brings (session, pins) does not stop a connection from continuing, and is sent again as new input'() {
        given:
        def fake = new ResponsesWebSocketFake()
        def p = profile(fake.endpoint, 'ws-conv-f')
        def conv = LlmConversationImpl.create(ec, p.name, null)
        when:
        wsTurn(ec, p, conv.conversationId, 'first question', 'userId=1 turn 1')
        wsTurn(ec, p, conv.conversationId, 'second question', 'userId=1 turn 2')
        then:
        fake.connections.size() == 1
        fake.creates[1].body.previous_response_id == 'resp_ws_1'
        fake.creates[1].body.input.size() == 2
        fake.creates[1].body.input*.content.flatten()*.text.any { it.contains('turn 2') }
        !fake.creates[1].body.input*.content.flatten()*.text.any { it.contains('turn 1') }
        and: 'the trajectory the conversation keeps does not hold the context of any turn'
        LlmConversationImpl.load(ec, conv.conversationId, true).history.findAll { it.content?.contains('userId=') }.isEmpty()
        cleanup:
        p.protocol.closeSessionsOfScope('conv:' + conv.conversationId)
        fake.close()
    }
}
