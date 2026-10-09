package org.moqui.impl.llm

import org.moqui.llm.LlmContentPart
import org.moqui.llm.LlmItem
import org.moqui.llm.LlmResponse
import org.moqui.llm.LlmResponseOptions
import org.moqui.llm.LlmTool
import org.moqui.llm.LlmTransport
import org.moqui.context.ExecutionContext
import spock.lang.IgnoreIf

/** Opt-in: conversations of the Open Responses model against the real provider of the profile `openai-responses`. */
@IgnoreIf({
    def key = System.getenv('llm_openai_api_key') ?: System.getProperty('llm_openai_api_key')
    key == null || key.toString().trim().isEmpty()
})
class LlmResponsesLiveConversationTests extends LlmConversationSpecBase {
    private static final String PROFILE = 'openai-responses'

    private LlmResponse liveTurn(String conversationId, Closure config, LlmTransport transport = LlmTransport.HTTP, boolean stream = false) {
        LlmClientImpl client = (LlmClientImpl) ec.llm.getClient(PROFILE)
        client.conversation(conversationId)
        client.transport(transport)
        client.maxTokens(2048)
        config.call(client)
        boolean off = ec.artifactExecution.disableAuthz()
        try {
            if (!stream) return client.call()
            return client.stream(new LlmStreamListenerAdapter())
        } finally { if (!off) ec.artifactExecution.enableAuthz() }
    }

    static class LlmStreamListenerAdapter implements org.moqui.llm.LlmStreamListener { }

    private String newConversation() {
        boolean off = ec.artifactExecution.disableAuthz()
        try { return ec.llm.createConversation(PROFILE, null).conversationId } finally { if (!off) ec.artifactExecution.enableAuthz() }
    }

    def 'two turns over HTTP with an image: the second turn still sees the picture and no message row is written'() {
        given:
        String id = newConversation()
        when:
        LlmResponse first = liveTurn(id) { LlmClientImpl c ->
            c.inputItems([LlmItem.message('user', [LlmContentPart.inputText('This is a one-pixel image. Say OK and remember the secret word PINEAPPLE.'),
                                                   image('data:image/png;base64,' + PNG)])])
        }
        LlmResponse second = liveTurn(id) { LlmClientImpl c -> c.user('What was the secret word? Answer with the word only.') }
        then:
        first.content
        second.content?.toUpperCase()?.contains('PINEAPPLE')
        rowsOf(id) == 0
        cleanup: deleteLive(id)
    }

    def 'a strict tool loop and a JSON schema answer'() {
        given:
        String id = newConversation()
        def calls = []
        LlmTool tool = new LlmTool() {
            String getName() { 'lookup' }
            String getDescription() { 'Looks up the capital of a country' }
            Map<String, Object> getParametersSchema() {
                [type: 'object', properties: [country: [type: 'string']], required: ['country'], additionalProperties: false]
            }
            Boolean getStrict() { true }
            LlmTool.Execution getExecution() { LlmTool.Execution.SERVER }
            Object execute(Map<String, Object> arguments, ExecutionContext executionContext) { calls << arguments.country; [capital: 'Rome'] }
        }
        when:
        LlmResponse r = liveTurn(id) { LlmClientImpl c ->
            c.user('Use the lookup tool for Italy and tell me the capital in one word.').tool(tool)
        }
        LlmResponse json = liveTurn(id) { LlmClientImpl c ->
            c.user('Give the capital as JSON.').responseOptions(new LlmResponseOptions().put('text', [format: [type: 'json_schema', name: 'cap', strict: true,
                    schema: [type: 'object', properties: [capital: [type: 'string']], required: ['capital'], additionalProperties: false]]]))
        }
        then:
        calls.size() >= 1
        r.content?.contains('Rome')
        new groovy.json.JsonSlurper().parseText(json.content).capital == 'Rome'
        cleanup: deleteLive(id)
    }

    def 'two turns of a conversation over the WebSocket, with the second turn sending only what is new'() {
        given:
        String id = newConversation()
        when:
        LlmResponse first = liveTurn(id, { LlmClientImpl c -> c.user('Remember the number 7314. Say OK.').responseOptions(new LlmResponseOptions().put('store', false)) }, LlmTransport.WEBSOCKET)
        LlmResponse second = liveTurn(id, { LlmClientImpl c -> c.user('What number did I ask you to remember? Digits only.').responseOptions(new LlmResponseOptions().put('store', false)) }, LlmTransport.WEBSOCKET)
        then:
        first.content
        second.content?.contains('7314')
        cleanup:
        ((OpenResponsesProtocol) ec.llm.getClient(PROFILE).profile.protocol).closeSessionsOfScope('conv:' + id)
        deleteLive(id)
    }

    def 'a conversation is compacted and goes on from the compacted trajectory'() {
        given:
        String id = newConversation()
        liveTurn(id) { LlmClientImpl c -> c.user('Remember the code word MARMALADE. Say OK.') }
        when:
        boolean off = ec.artifactExecution.disableAuthz()
        try {
            LlmClientImpl c = (LlmClientImpl) ec.llm.getClient(PROFILE)
            c.conversation(id)
            c.compact()
        } finally { if (!off) ec.artifactExecution.enableAuthz() }
        LlmResponse after = liveTurn(id) { LlmClientImpl c -> c.user('What was the code word? One word.') }
        then:
        after.content?.toUpperCase()?.contains('MARMALADE')
        cleanup: deleteLive(id)
    }

    private void deleteLive(String id) {
        boolean off = ec.artifactExecution.disableAuthz()
        try { LlmGateway.deleteConversationOf(ec, id) } catch (Throwable ignored) { } finally { if (!off) ec.artifactExecution.enableAuthz() }
    }
}
