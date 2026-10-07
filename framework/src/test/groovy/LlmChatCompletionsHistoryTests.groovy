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

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.impl.llm.LlmClientImpl
import org.moqui.impl.llm.LlmConversationImpl
import org.moqui.impl.llm.LlmFacadeImpl
import org.moqui.impl.llm.OpenAiCompatProtocol
import org.moqui.llm.LlmFinishReason
import org.moqui.llm.LlmMessage
import org.moqui.llm.LlmProtocol.ProtocolResult
import org.moqui.llm.LlmResponse
import org.moqui.llm.LlmStreamListener
import org.moqui.llm.test.FakeLlmProtocol
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification

/** What a Chat Completions turn leaves in the history, and what is sent back from it. Needs the test database. */
@IgnoreIf({
    String runtime = System.getProperty("moqui.runtime") ?: "../runtime"
    String conf = System.getProperty("moqui.conf") ?: "conf/MoquiDevConf.xml"
    File direct = new File(conf)
    File nested = new File(runtime, conf.startsWith("conf/") ? conf : "conf/" + new File(conf).name)
    !direct.exists() && !nested.exists() && !new File(runtime, "conf/MoquiDevConf.xml").exists()
})
class LlmChatCompletionsHistoryTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() { ec = Moqui.getExecutionContext() }
    def cleanupSpec() { ec.destroy() }
    def setup() {
        ec.artifactExecution.disableAuthz()
        ec.artifactExecution.disableTarpit()
        if (!ec.user.userId) ec.user.loginUser("john.doe", "moqui")
        if (ec.transaction.isTransactionInPlace()) ec.transaction.commit()
    }
    def cleanup() {
        if (ec.transaction.isTransactionInPlace()) ec.transaction.rollback("test cleanup", null)
        ec.artifactExecution.enableAuthz()
        ec.artifactExecution.enableTarpit()
    }

    private LlmClientImpl client(FakeLlmProtocol proto, int emptyRetries = 2) {
        def profile = LlmFacadeImpl.ProfileState.forTest("default", proto, "test-model", false, emptyRetries, 0f, 5)
        new LlmClientImpl(ec, profile, { ec.transaction.isTransactionInPlace() })
    }

    private static ProtocolResult refusal(String text) {
        ProtocolResult r = new ProtocolResult(LlmFinishReason.REFUSAL)
        r.refusal = text
        r.httpStatus = 200
        r.metadata = [response: [id: 'chatcmpl-77', service_tier: 'default'], choice: [index: 0, finish_reason: 'stop'],
                      message: [annotations: [[type: 'url_citation', url_citation: [url: 'https://example.com']]]],
                      usage: [prompt_tokens: 4, completion_tokens: 2, total_tokens: 6, prompt_tokens_details: [cached_tokens: 1]]]
        r
    }

    def 'a refusal is kept in the history, survives a reload and goes back as a refusal'() {
        given:
        def proto = new FakeLlmProtocol()
        proto.handler = { request -> refusal('I cannot help with that') }
        def conv = LlmConversationImpl.create(ec, "default", null)
        when:
        LlmResponse first = client(proto).conversation(conv).user('do something').call()
        def reloaded = LlmConversationImpl.load(ec, conv.conversationId, true)
        def history = reloaded.getHistory()
        def assistant = history.find { it.role == LlmMessage.Role.ASSISTANT }
        then:
        first.finishReason == LlmFinishReason.REFUSAL
        first.refusal == 'I cannot help with that'
        proto.chatCount == 1
        reloaded.status == LlmConversationImpl.STATUS_COMPLETE
        assistant.content == null
        assistant.metadata.refusal == 'I cannot help with that'
        assistant.metadata.providerResponseId == 'chatcmpl-77'
        assistant.metadata.providerResponseId != conv.conversationId
        assistant.metadata.providerFinishReason == 'stop'
        assistant.metadata.annotations[0].url_citation.url == 'https://example.com'
        when: 'the next turn is sent'
        proto.handler = { request -> FakeLlmProtocol.stop('fine') }
        LlmResponse second = client(proto).conversation(reloaded).user('ok then something else').call()
        def sent = OpenAiCompatProtocol.convertMessages(proto.lastRequest.window)
        then:
        second.finishReason == LlmFinishReason.STOP
        sent*.role == ['user', 'assistant', 'user']
        sent[1].refusal == 'I cannot help with that'
        sent[1].content == null
    }

    def 'a streamed refusal ends the turn the same way'() {
        given:
        def proto = new FakeLlmProtocol()
        proto.handler = { request -> refusal('Not this') }
        def conv = LlmConversationImpl.create(ec, "default", null)
        def completed = 0
        def listener = new LlmStreamListener() {
            @Override void onComplete(LlmResponse response) { completed++ }
        }
        when:
        LlmResponse r = client(proto).conversation(conv).user('hello').stream(listener)
        def reloaded = LlmConversationImpl.load(ec, conv.conversationId, true)
        then:
        r.finishReason == LlmFinishReason.REFUSAL
        completed == 1
        reloaded.status == LlmConversationImpl.STATUS_COMPLETE
        reloaded.getHistory().find { it.role == LlmMessage.Role.ASSISTANT }.metadata.refusal == 'Not this'
    }

    def 'a refusal with emptyRetries configured is one provider call'() {
        given:
        def proto = new FakeLlmProtocol()
        proto.handler = { request -> refusal('No') }
        def conv = LlmConversationImpl.create(ec, "default", null)
        when:
        client(proto, 5).conversation(conv).user('x').call()
        then:
        proto.chatCount == 1
    }
}
