import groovy.json.JsonSlurper
import org.moqui.context.ExecutionContext
import org.moqui.impl.llm.LlmClientImpl
import org.moqui.impl.llm.LlmConversationImpl
import org.moqui.impl.llm.LlmConversationSpecBase
import org.moqui.impl.llm.LlmResponsesClientIntegrationTests

/** Second JVM: the conversation stored by the first one is continued, image and answer included, with no message row. */
class LlmConversationRestartVerifyTests extends LlmConversationSpecBase {
    def 'a new JVM continues the conversation from the stored trajectory'() {
        given:
        Map marker = new JsonSlurper().parse(new File(System.getProperty('llm.restart.marker'))) as Map
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, answer('resp_restart_2', 'VIOLETTA-28'))
        def p = profile(provider.endpoint, 'restart-conv')
        def conv = LlmConversationImpl.load(ec, marker.conversationId as String, true)
        when:
        def r = turn(p, conv) { LlmClientImpl c -> c.user('what was the code, and what was in the picture?') }
        def input = provider.requests[0].json.input
        then:
        r.content == 'VIOLETTA-28'
        input.findAll { it.role == 'user' && it.content*.type.contains('input_image') }.size() == 1
        input.find { it.content*.type.contains('input_image') }.content.find { it.type == 'input_image' }.image_url == 'data:image/png;base64,' + PNG
        input.findAll { it.role == 'assistant' }*.content.flatten()*.text == ['a tiny picture, code VIOLETTA-28']
        !provider.requests[0].json.containsKey('previous_response_id')
        rowsOf(conv.conversationId) == 0
        cleanup:
        provider.close()
        boolean off = ec.artifactExecution.disableAuthz()
        try { org.moqui.impl.llm.LlmGateway.deleteConversationOf(ec, marker.conversationId as String) } catch (Throwable ignored) { } finally { if (!off) ec.artifactExecution.enableAuthz() }
    }
}
