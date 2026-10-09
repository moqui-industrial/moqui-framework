import groovy.json.JsonOutput
import org.moqui.impl.llm.LlmClientImpl
import org.moqui.impl.llm.LlmConversationImpl
import org.moqui.impl.llm.LlmConversationSpecBase
import org.moqui.impl.llm.LlmResponsesClientIntegrationTests

/** First JVM of the restart check: one Open Responses turn with an image, stored; the second JVM continues the conversation. */
class LlmConversationRestartSeedTests extends LlmConversationSpecBase {
    def 'first turn of a conversation that another JVM will continue'() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, answer('resp_restart_1', 'a tiny picture, code VIOLETTA-28'))
        def p = profile(provider.endpoint, 'restart-conv')
        def conv = LlmConversationImpl.create(ec, p.name, null)
        when:
        turn(p, conv) { LlmClientImpl c ->
            c.inputItems([org.moqui.llm.LlmItem.message('user', [org.moqui.llm.LlmContentPart.inputText('remember VIOLETTA-28'), image('data:image/png;base64,' + PNG)])])
        }
        File marker = new File(System.getProperty('llm.restart.marker'))
        marker.parentFile.mkdirs()
        marker.text = JsonOutput.toJson([conversationId: conv.conversationId, username: USERNAME])
        then:
        marker.exists()
        rowsOf(conv.conversationId) == 0
        cleanup:
        provider.close()
    }
}
