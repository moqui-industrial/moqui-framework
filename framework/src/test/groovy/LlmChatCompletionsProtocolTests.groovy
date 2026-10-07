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

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.eclipse.jetty.http.HttpHeader
import org.eclipse.jetty.io.Content
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.util.Callback
import org.moqui.context.ExecutionContext
import org.moqui.impl.llm.LlmClientImpl
import org.moqui.impl.llm.LlmFacadeImpl
import org.moqui.impl.llm.OpenAiCompatEofProtocol
import org.moqui.impl.llm.OpenAiCompatProtocol
import org.moqui.llm.LlmException
import org.moqui.llm.LlmFinishReason
import org.moqui.llm.LlmMessage
import org.moqui.llm.LlmProtocol
import org.moqui.llm.LlmProtocol.ProtocolRequest
import org.moqui.llm.LlmProtocol.ProtocolResult
import org.moqui.llm.LlmProtocol.ProtocolStreamListener
import org.moqui.llm.LlmResponse
import org.moqui.llm.LlmStreamListener
import org.moqui.llm.LlmTool
import org.moqui.util.RestClient
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Chat Completions client against a scripted loopback provider. Every test counts what reached the provider and what
 * ran as a tool, not only what came back. No network, no database.
 */
class LlmChatCompletionsProtocolTests extends Specification {
    @Shared Server server
    @Shared int port
    @Shared ScriptedProvider provider
    @Shared RestClient.RequestFactory requestFactory

    def setupSpec() {
        provider = new ScriptedProvider()
        server = new Server(0)
        server.setHandler(provider)
        server.start()
        port = ((ServerConnector) server.connectors[0]).localPort
        requestFactory = new RestClient.SimpleRequestFactory()
    }

    def cleanupSpec() {
        if (requestFactory != null) requestFactory.destroy()
        if (server != null) server.stop()
    }

    def setup() { provider.reset() }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private String url() { "http://127.0.0.1:${port}/v1/chat/completions" }

    private ProtocolRequest req(boolean stream = false) {
        ProtocolRequest r = new ProtocolRequest()
        r.profileName = 'cc'
        r.endpointUrl = url()
        r.model = 'test-model'
        r.window = [LlmMessage.user('hi')]
        r.timeoutSeconds = 15
        r.retryInitialSeconds = 0.01f
        r.retryMax = 0
        r.timeoutRetry = false
        r.stream = stream
        r.requestFactory = requestFactory
        r
    }

    private LlmClientImpl client(LlmProtocol protocol = new RedirectProtocol(url(), requestFactory), int emptyRetries = 0,
            List<LlmTool> tools = []) {
        def profile = LlmFacadeImpl.ProfileState.forTest('cc', protocol, 'test-model', false, emptyRetries, 0.01f, 0)
        def c = new LlmClientImpl((ExecutionContext) null, profile, { false })
        tools.each { c.tool(it) }
        c.user('hi')
        c
    }

    private static String completion(Map message, String finish = 'stop', Map extra = [:]) {
        JsonOutput.toJson([id: 'chatcmpl-1', object: 'chat.completion', created: 1700000000, model: 'test-model',
                           choices: [[index: 0, message: [role: 'assistant'] + message, finish_reason: finish]]] + extra)
    }

    private static String chunk(Map delta, String finish = null, Map extra = [:]) {
        Map choice = [index: 0, delta: delta]
        if (finish != null) choice.finish_reason = finish
        sse(JsonOutput.toJson([id: 'chatcmpl-1', object: 'chat.completion.chunk', created: 1700000000, model: 'test-model',
                               choices: [choice]] + extra))
    }

    private static String sse(String json) { "data: ${json}\n\n" }
    private static final String DONE = 'data: [DONE]\n\n'

    private ProtoListener stream(List chunks) {
        provider.enqueue(new Reply(chunks: chunks))
        def listener = new ProtoListener()
        new OpenAiCompatProtocol().chatStream(req(true), listener)
        listener
    }

    private ProtoListener streamWith(OpenAiCompatProtocol protocol, List chunks) {
        provider.enqueue(new Reply(chunks: chunks))
        def listener = new ProtoListener()
        protocol.chatStream(req(true), listener)
        listener
    }

    private static LlmTool countingTool(AtomicInteger counter, Boolean strict = null, Map schema = null) {
        new LlmTool() {
            String getName() { 'lookup' }
            String getDescription() { 'looks something up' }
            Map<String, Object> getParametersSchema() { schema }
            LlmTool.Execution getExecution() { LlmTool.Execution.SERVER }
            Boolean getStrict() { strict }
            Object execute(Map<String, Object> arguments, ExecutionContext ec) { counter.incrementAndGet(); [ok: true] }
        }
    }

    // ---- CC01 refusal -------------------------------------------------------------------------------------------

    def 'CC01 an HTTP refusal is a definitive refusal: one call, no retry, no tool'() {
        given:
        provider.enqueue(new Reply(body: completion([content: null, refusal: "I can't help with that."])))
        def runs = new AtomicInteger()
        def client = client(new RedirectProtocol(url(), requestFactory), emptyRetries, tools)
        when:
        LlmResponse response = client.call()
        then:
        response.finishReason == LlmFinishReason.REFUSAL
        response.refusal == "I can't help with that."
        response.content == null
        provider.hits.size() == 1
        runs.get() == 0
        where:
        emptyRetries | tools
        0            | []
        3            | []
        3            | [countingTool(new AtomicInteger())]
    }

    def 'CC01 a refusal that comes with tool calls runs none of them and says so'() {
        given:
        def runs = new AtomicInteger()
        provider.enqueue(new Reply(body: completion([content: null, refusal: 'No.',
                tool_calls: [[id: 'call_1', type: 'function', function: [name: 'lookup', arguments: '{}']]]], 'tool_calls')))
        def client = client(new RedirectProtocol(url(), requestFactory), 2, [countingTool(runs)])
        when:
        LlmResponse response = client.call()
        then:
        response.finishReason == LlmFinishReason.REFUSAL
        response.toolCalls == [] || response.toolCalls == null
        response.metadata.message.ignoredToolCalls == ['lookup']
        runs.get() == 0
        provider.hits.size() == 1
    }

    def 'CC01 a refusal streamed in fragments is rebuilt exactly and completes once'() {
        given:
        def listener = stream([chunk([role: 'assistant', content: null]), chunk([refusal: "I can"]), chunk([refusal: "'t "]),
                               chunk([refusal: 'do that.']), chunk([:], 'stop'), DONE])
        expect:
        listener.failure == null
        listener.completions == 1
        listener.complete.finishReason == LlmFinishReason.REFUSAL
        listener.complete.refusal == "I can't do that."
        listener.complete.content == null
        listener.refusalDeltas == ["I can", "'t ", 'do that.']
        listener.deltas == []
    }

    def 'CC01 a streamed refusal through the client reports refusal deltas and no content'() {
        given:
        provider.enqueue(new Reply(chunks: [chunk([refusal: 'Not ']), chunk([refusal: 'today.']), chunk([:], 'stop'), DONE]))
        def listener = new ClientListener()
        when:
        LlmResponse response = client(new RedirectProtocol(url(), requestFactory), 3).stream(listener)
        then:
        response.finishReason == LlmFinishReason.REFUSAL
        response.refusal == 'Not today.'
        listener.refusalDeltas == ['Not ', 'today.']
        listener.deltas == []
        listener.completed == 1
        provider.hits.size() == 1
    }

    def 'CC01 an empty or null refusal is no refusal'() {
        given:
        provider.enqueue(new Reply(body: completion([content: 'hello', refusal: refusal])))
        when:
        LlmResponse response = client().call()
        then:
        response.finishReason == LlmFinishReason.STOP
        response.refusal == null
        response.content == 'hello'
        where:
        refusal << [null, '', '  ']
    }

    @Unroll
    def 'CC01 finish reason #finish is not a refusal and keeps its meaning'() {
        given:
        provider.enqueue(new Reply(body: completion(message, finish)))
        def result = new OpenAiCompatProtocol().chat(req())
        expect:
        result.finishReason == expected
        result.refusal == null
        where:
        finish           | message                                                                            | expected
        'content_filter' | [content: null]                                                                    | LlmFinishReason.CONTENT_FILTER
        'length'         | [content: 'cut']                                                                   | LlmFinishReason.LENGTH
        'tool_calls'     | [content: null, tool_calls: [[id: 'c', type: 'function', function: [name: 'f', arguments: '{}']]]] | LlmFinishReason.TOOL_CALLS
        'stop'           | [content: 'plain']                                                                 | LlmFinishReason.STOP
    }

    def 'CC01 a finished completion with an empty string is a STOP and is not retried'() {
        given:
        provider.enqueue(new Reply(body: completion([content: ''])))
        when:
        LlmResponse response = client(new RedirectProtocol(url(), requestFactory), 3).call()
        then:
        response.finishReason == LlmFinishReason.STOP
        response.content == ''
        provider.hits.size() == 1
    }

    def 'CC01 a completion with no content at all is still empty and is retried as before'() {
        given:
        provider.enqueue(new Reply(body: completion([content: null])))
        provider.enqueue(new Reply(body: completion([content: 'second try'])))
        when:
        LlmResponse response = client(new RedirectProtocol(url(), requestFactory), 2).call()
        then:
        response.content == 'second try'
        provider.hits.size() == 2
    }

    def 'CC01 a refusal goes back as a refusal and keeps the role'() {
        given:
        def refused = LlmMessage.assistant(null)
        refused.metadata = [refusal: 'No thanks']
        def messages = OpenAiCompatProtocol.convertMessages([LlmMessage.user('do it'), refused, LlmMessage.user('please')])
        expect:
        messages*.role == ['user', 'assistant', 'user']
        messages[1].refusal == 'No thanks'
        messages[1].content == null
        !messages[0].containsKey('refusal')
    }

    // ---- CC02 streams -------------------------------------------------------------------------------------------

    def 'CC02 text, finish, usage-only chunk and DONE give a complete result'() {
        given:
        def listener = stream([chunk([content: 'Hel']), chunk([content: 'lo']), chunk([:], 'stop'),
                sse(JsonOutput.toJson([id: 'chatcmpl-1', choices: [], usage: [prompt_tokens: 3, completion_tokens: 2, total_tokens: 5,
                        prompt_tokens_details: [cached_tokens: 1], completion_tokens_details: [reasoning_tokens: 0]]])), DONE])
        expect:
        listener.failure == null
        listener.completions == 1
        listener.complete.finishReason == LlmFinishReason.STOP
        listener.complete.content == 'Hello'
        listener.complete.usage.totalTokens == 5
        listener.complete.usage.cachedInputTokens == 1
        listener.complete.usage.reasoningOutputTokens == 0
    }

    def 'CC02 fragmented tool arguments are rebuilt'() {
        given:
        def listener = stream([chunk([tool_calls: [[index: 0, id: 'call_1', type: 'function', function: [name: 'lookup', arguments: '']]]]),
                chunk([tool_calls: [[index: 0, function: [arguments: '{"a":']]]]), chunk([tool_calls: [[index: 0, function: [arguments: '1}']]]]),
                chunk([:], 'tool_calls'), DONE])
        expect:
        listener.complete.finishReason == LlmFinishReason.TOOL_CALLS
        listener.complete.toolCalls*.arguments == ['{"a":1}']
    }

    def 'CC02 a malformed event is an error, nothing completes and no tool runs'() {
        given:
        def runs = new AtomicInteger()
        provider.enqueue(new Reply(chunks: [chunk([content: 'Hel']), 'data: {"choices":[{"delta":{"content":\n\n', chunk([content: 'lo']), chunk([:], 'stop'), DONE]))
        def listener = new ClientListener()
        when:
        client(new RedirectProtocol(url(), requestFactory), 0, [countingTool(runs)]).stream(listener)
        then:
        thrown(LlmException)
        listener.completed == 0
        listener.errors == 1
        runs.get() == 0
        provider.hits.size() == 1
    }

    def 'CC02 a stream cut after text and before finish_reason is an error, never a STOP'() {
        when:
        def listener = stream([chunk([content: 'Hel'])])
        then:
        listener.complete == null
        listener.failures == 1
        listener.failure.message.contains('finish_reason')
    }

    def 'CC02 a stream cut after finish_reason and before DONE is an error unless the profile accepts it'() {
        when:
        def strict = stream([chunk([content: 'Hi']), chunk([:], 'stop')])
        def tolerant = streamWith(new OpenAiCompatEofProtocol(), [chunk([content: 'Hi']), chunk([:], 'stop')])
        then:
        strict.complete == null
        strict.failures == 1
        strict.failure.message.contains('[DONE]')
        tolerant.failure == null
        tolerant.complete.finishReason == LlmFinishReason.STOP
        tolerant.completions == 1
        provider.hits.size() == 2
    }

    def 'CC02 the tolerant profile still needs a finished choice'() {
        when:
        def listener = streamWith(new OpenAiCompatEofProtocol(), [chunk([content: 'Hi'])])
        then:
        listener.complete == null
        listener.failures == 1
    }

    def 'CC02 DONE without any finished choice is an error'() {
        when:
        def listener = stream([DONE])
        then:
        listener.complete == null
        listener.failures == 1
        listener.failure.message.contains('DONE')
    }

    def 'CC02 a provider error inside the stream is that error, with or without the sentinel'() {
        when:
        def withDone = stream([sse('{"error":{"code":"server_error","message":"boom"}}'), DONE])
        def withoutDone = stream([sse('{"error":{"code":"server_error","message":"boom"}}')])
        then:
        withDone.failure == null
        withDone.complete.finishReason == LlmFinishReason.ERROR
        withDone.complete.errorMessage == 'boom'
        withoutDone.failure == null
        withoutDone.complete.finishReason == LlmFinishReason.ERROR
        withoutDone.complete.providerErrorCode == 'server_error'
    }

    def 'CC02 an HTTP error that is not a stream keeps its status and code'() {
        given:
        provider.enqueue(new Reply(status: 429, contentType: 'application/json',
                body: '{"error":{"code":"rate_limit_exceeded","message":"slow down"}}'))
        def listener = new ProtoListener()
        when:
        new OpenAiCompatProtocol().chatStream(req(true), listener)
        then:
        listener.complete.httpStatus == 429
        listener.complete.providerErrorCode == 'rate_limit_exceeded'
        listener.complete.finishReason == LlmFinishReason.ERROR
        listener.completions == 1
    }

    def 'CC02 comments, CRLF framing, multi-line data and split UTF-8 are accepted'() {
        given:
        byte[] euro = 'Café €'.getBytes(StandardCharsets.UTF_8)
        String json = JsonOutput.toJson([id: 'chatcmpl-1', choices: [[index: 0, delta: [content: new String(euro, StandardCharsets.UTF_8)]]]], )
        byte[] frame = ("data: ${json}\r\n\r\n").getBytes(StandardCharsets.UTF_8)
        int split = frame.length - 14
        String finish = 'data: {"choices":[{"index":0,\ndata: "delta":{},"finish_reason":"stop"}]}\n\n'
        def listener = stream([': heartbeat\n\n', Arrays.copyOfRange(frame, 0, split), Arrays.copyOfRange(frame, split, frame.length),
                ': keep alive\r\n\r\n', finish, DONE])
        expect:
        listener.failure == null
        listener.complete.content == 'Café €'
        listener.complete.finishReason == LlmFinishReason.STOP
    }

    def 'CC02 a disconnect after partial output is an error and the request is not sent again'() {
        given:
        provider.enqueue(new Reply(chunks: [chunk([content: 'Hel'])], abort: true))
        def listener = new ClientListener()
        when:
        def client = client(new RedirectProtocol(url(), requestFactory), 3)
        client.stream(listener)
        then:
        thrown(LlmException)
        listener.completed == 0
        provider.hits.size() == 1
    }

    // ---- CC03 one alternative -----------------------------------------------------------------------------------

    @Unroll
    def 'CC03 n=#n is #outcome before anything is sent'() {
        given:
        provider.enqueue(new Reply(body: completion([content: 'ok'])))
        def request = req()
        request.extraBody = n == 'absent' ? [:] : [n: n]
        when:
        def result = new OpenAiCompatProtocol().chat(request)
        then:
        if (ok) {
            assert result.finishReason == LlmFinishReason.STOP
            assert provider.hits.size() == 1
        }
        where:
        n      | ok   | outcome
        'absent' | true | 'accepted'
        1      | true | 'accepted'
    }

    @Unroll
    def 'CC03 n=#label is refused with zero provider calls'() {
        given:
        def request = req()
        request.extraBody = [n: value]
        when:
        new OpenAiCompatProtocol().chat(request)
        then:
        def e = thrown(LlmException)
        e.message.contains('n must be the integer 1')
        provider.hits.size() == 0
        where:
        label    | value
        '2'      | 2
        '0'      | 0
        '-1'     | -1
        '1.5'    | 1.5d
        'string' | '1'
        'true'   | true
        'null'   | null
    }

    def 'CC03 a stream is refused too, before anything is sent'() {
        given:
        def request = req(true)
        request.extraBody = [n: 2]
        when:
        new OpenAiCompatProtocol().chatStream(request, new ProtoListener())
        then:
        thrown(LlmException)
        provider.hits.size() == 0
    }

    def 'CC03 a response with two choices is reported and starts no tool'() {
        given:
        def runs = new AtomicInteger()
        provider.enqueue(new Reply(body: JsonOutput.toJson([id: 'x', choices: [
                [index: 0, message: [role: 'assistant', content: null, tool_calls: [[id: 'c', type: 'function', function: [name: 'lookup', arguments: '{}']]]], finish_reason: 'tool_calls'],
                [index: 1, message: [role: 'assistant', content: 'other'], finish_reason: 'stop']]])))
        when:
        client(new RedirectProtocol(url(), requestFactory), 0, [countingTool(runs)]).call()
        then:
        def e = thrown(LlmException)
        e.message.contains('n=1')
        runs.get() == 0
        provider.hits.size() == 1
    }

    def 'CC03 a streamed choice with index 1 is an error and is not folded into the first'() {
        given:
        provider.enqueue(new Reply(chunks: [chunk([content: 'a']),
                sse(JsonOutput.toJson([choices: [[index: 1, delta: [content: 'b']]]])), chunk([:], 'stop'), DONE]))
        def listener = new ProtoListener()
        Throwable thrown = null
        when:
        try { new OpenAiCompatProtocol().chatStream(req(true), listener) } catch (Throwable t) { thrown = t }
        then:
        thrown != null || listener.failures == 1
        listener.completions == 0
        listener.deltas == ['a'] || listener.deltas == []
    }

    // ---- CC04 strict --------------------------------------------------------------------------------------------

    def 'CC04 strict is sent exactly when the tool says so, beside parameters and not inside them'() {
        given:
        Map schema = [type: 'object', properties: [q: [type: 'string']], required: ['q'], additionalProperties: false]
        Map before = new JsonSlurper().parseText(JsonOutput.toJson(schema)) as Map
        def request = req()
        request.tools = [countingTool(new AtomicInteger(), strict, schema)]
        when:
        Map body = OpenAiCompatProtocol.buildRequestBody(request)
        Map fn = body.tools[0].function
        then:
        fn.containsKey('strict') == (strict != null)
        fn.strict == strict
        !fn.parameters.containsKey('strict')
        fn.parameters == before
        schema == before
        where:
        strict << [null, true, false]
    }

    def 'CC04 a tool that says nothing keeps today\'s body'() {
        given:
        def request = req()
        request.tools = [countingTool(new AtomicInteger(), null, [type: 'object', properties: [:]])]
        expect:
        !OpenAiCompatProtocol.buildRequestBody(request).tools[0].function.containsKey('strict')
    }

    @Unroll
    def 'CC04 a strict tool with #problem is refused before sending and its schema is left alone'() {
        given:
        def request = req()
        request.tools = [countingTool(new AtomicInteger(), true, schema)]
        def before = JsonOutput.toJson(schema)
        when:
        OpenAiCompatProtocol.buildRequestBody(request)
        then:
        def e = thrown(LlmException)
        e.message.contains(expected)
        JsonOutput.toJson(schema) == before
        where:
        problem                       | expected                         | schema
        'open additionalProperties'   | 'additionalProperties to false'  | [type: 'object', properties: [a: [type: 'string']], required: ['a']]
        'an optional property'        | 'as required'                    | [type: 'object', properties: [a: [type: 'string'], b: [type: 'string']], required: ['a'], additionalProperties: false]
        'a nested open object'        | 'parameters.properties.o'        | [type: 'object', properties: [o: [type: 'object', properties: [x: [type: 'string']], required: ['x']]], required: ['o'], additionalProperties: false]
    }

    def 'CC04 tool_choice and parallel_tool_calls from extraBody are kept'() {
        given:
        def request = req()
        request.tools = [countingTool(new AtomicInteger(), true, [type: 'object', properties: [:], required: [], additionalProperties: false])]
        request.extraBody = [tool_choice: 'required', parallel_tool_calls: false]
        when:
        Map body = OpenAiCompatProtocol.buildRequestBody(request)
        then:
        body.tool_choice == 'required'
        body.parallel_tool_calls == false
        body.tools[0].function.strict == true
    }

    // ---- CC05 metadata and usage --------------------------------------------------------------------------------

    def 'CC05 HTTP and SSE carry the same metadata without raw logging'() {
        given:
        Map annotation = [type: 'url_citation', url_citation: [url: 'https://example.com', title: 'Example', start_index: 0, end_index: 4]]
        provider.enqueue(new Reply(body: completion([content: 'Text', annotations: [annotation]], 'stop',
                [service_tier: 'default', system_fingerprint: 'fp_1', usage: [prompt_tokens: 5, completion_tokens: 3, total_tokens: 8,
                        prompt_tokens_details: [cached_tokens: 2, audio_tokens: 0], completion_tokens_details: [reasoning_tokens: 1, accepted_prediction_tokens: 0]]])))
        provider.enqueue(new Reply(chunks: [
                chunk([role: 'assistant'], null, [service_tier: 'default', system_fingerprint: 'fp_1']),
                chunk([content: 'Text']),
                chunk([annotations: [annotation]]),
                chunk([:], 'stop'),
                sse(JsonOutput.toJson([id: 'chatcmpl-1', choices: [], usage: [prompt_tokens: 5, completion_tokens: 3, total_tokens: 8,
                        prompt_tokens_details: [cached_tokens: 2, audio_tokens: 0], completion_tokens_details: [reasoning_tokens: 1, accepted_prediction_tokens: 0]]])),
                DONE]))
        def http = client().call()
        def streamed = client().stream(new ClientListener())
        expect:
        [http, streamed].every { LlmResponse r ->
            r.metadata.response.id == 'chatcmpl-1' && r.metadata.response.service_tier == 'default' &&
            r.metadata.response.system_fingerprint == 'fp_1' && r.metadata.choice.finish_reason == 'stop' &&
            r.metadata.choice.index == 0 && r.metadata.message.annotations[0].url_citation.title == 'Example' &&
            r.metadata.usage.prompt_tokens_details.cached_tokens == 2 && r.metadata.usage.prompt_tokens_details.audio_tokens == 0 &&
            r.metadata.usage.completion_tokens_details.accepted_prediction_tokens == 0 &&
            r.usage.cachedInputTokens == 2 && r.usage.reasoningOutputTokens == 1 && r.rawJson == null
        }
        http.conversationId == null
    }

    def 'CC05 logprobs of the choice are kept and counters that are zero, null or absent are told apart'() {
        given:
        provider.enqueue(new Reply(body: JsonOutput.toJson([id: 'x', choices: [[index: 0, finish_reason: 'stop',
                message: [role: 'assistant', content: 'a'], logprobs: [content: [[token: 'a', logprob: -0.1d]], refusal: null]]],
                usage: [prompt_tokens: 1, completion_tokens: 1, total_tokens: 2, prompt_tokens_details: null, completion_tokens_details: [reasoning_tokens: 0]]])))
        when:
        LlmResponse r = client().call()
        then:
        r.metadata.choice.logprobs.content[0].token == 'a'
        r.usage.cachedInputTokens == null
        r.usage.reasoningOutputTokens == 0
        !r.metadata.usage.containsKey('prompt_tokens_details')
    }

    def 'CC05 a later chunk without a field does not erase what an earlier one told'() {
        given:
        def listener = stream([chunk([role: 'assistant'], null, [service_tier: 'default']),
                chunk([content: 'x'], null, [service_tier: null]), chunk([:], 'stop'), DONE])
        expect:
        listener.complete.metadata.response.service_tier == 'default'
    }

    def 'CC05 the usage of a stream is the last total, not the sum of the snapshots'() {
        given:
        def listener = stream([chunk([content: 'x'], null, [usage: [prompt_tokens: 2, completion_tokens: 1, total_tokens: 3]]),
                chunk([:], 'stop', [usage: [prompt_tokens: 2, completion_tokens: 4, total_tokens: 6]]), DONE])
        expect:
        listener.complete.usage.totalTokens == 6
        listener.complete.usage.completionTokens == 4
    }

    def 'CC05 the maps given by the caller are not changed'() {
        given:
        Map extra = [metadata: [a: 1], top_p: 0.5d]
        Map copy = new JsonSlurper().parseText(JsonOutput.toJson(extra)) as Map
        def request = req()
        request.extraBody = extra
        when:
        OpenAiCompatProtocol.buildRequestBody(request)
        then:
        extra == copy
    }

    // ---- CC06 extraBody -----------------------------------------------------------------------------------------

    def 'CC06 options that reach the provider through extraBody still do'() {
        given:
        def request = req()
        request.extraBody = [response_format: [type: 'json_schema', json_schema: [name: 'r', schema: [type: 'object']]],
                             tool_choice: 'auto', parallel_tool_calls: false, reasoning_effort: 'low', top_p: 0.9d,
                             frequency_penalty: 0.1d, presence_penalty: 0.2d]
        when:
        Map body = OpenAiCompatProtocol.buildRequestBody(request)
        then:
        body.response_format.type == 'json_schema'
        body.tool_choice == 'auto'
        body.parallel_tool_calls == false
        body.reasoning_effort == 'low'
        body.top_p == 0.9d
        body.frequency_penalty == 0.1d
        body.presence_penalty == 0.2d
    }

    def 'CC06 model, messages and stream of the client win over extraBody, and so do the generated tools'() {
        given:
        def request = req()
        request.extraBody = [model: 'other', messages: [[role: 'user', content: [[type: 'image_url']]]], stream: true,
                             tools: [[type: 'function', function: [name: 'ghost']]]]
        request.tools = [countingTool(new AtomicInteger(), null, [type: 'object', properties: [:]])]
        when:
        Map body = OpenAiCompatProtocol.buildRequestBody(request)
        then:
        body.model == 'test-model'
        body.messages[0].content == 'hi'
        body.stream == false
        body.tools*.function.name == ['lookup']
    }

    def 'CC06 the token limit is sent once, with the name the profile uses'() {
        given:
        def request = req()
        request.maxTokens = 50
        request.maxTokensParameter = param
        request.extraBody = extra
        when:
        Map body = OpenAiCompatProtocol.buildRequestBody(request)
        then:
        body[param] == 50
        !body.containsKey(other)
        where:
        param                   | other                    | extra
        'max_tokens'            | 'max_completion_tokens'  | [:]
        'max_completion_tokens' | 'max_tokens'             | [:]
    }

    def 'CC06 a token limit in extraBody under the other name is refused rather than sent twice'() {
        given:
        def request = req()
        request.maxTokens = 50
        request.maxTokensParameter = 'max_completion_tokens'
        request.extraBody = [max_tokens: 99]
        when:
        OpenAiCompatProtocol.buildRequestBody(request)
        then:
        thrown(LlmException)
    }

    // ---- additions: edge cases of the matrix ---------------------------------------------------------------------

    def 'CC01 a refusal that comes with some text keeps both and is still a refusal'() {
        given:
        provider.enqueue(new Reply(body: completion([content: 'Partial answer', refusal: 'But I stop here.'])))
        when:
        LlmResponse r = client(new RedirectProtocol(url(), requestFactory), 2).call()
        then:
        r.finishReason == LlmFinishReason.REFUSAL
        r.content == 'Partial answer'
        r.refusal == 'But I stop here.'
        provider.hits.size() == 1
    }

    def 'CC02 two tool calls whose fragments are interleaved are rebuilt apart, empty chunks are harmless'() {
        given:
        def listener = stream([chunk([tool_calls: [[index: 0, id: 'call_a', type: 'function', function: [name: 'lookup', arguments: '']],
                                                    [index: 1, id: 'call_b', type: 'function', function: [name: 'other', arguments: '']]]]),
                'data: {}\n\n', chunk([:]),
                chunk([tool_calls: [[index: 1, function: [arguments: '{"b":']]]]), chunk([tool_calls: [[index: 0, function: [arguments: '{"a":']]]]),
                chunk([tool_calls: [[index: 0, function: [arguments: '1}']]]]), chunk([tool_calls: [[index: 1, function: [arguments: '2}']]]]),
                chunk([:], 'tool_calls'), DONE])
        expect:
        listener.failure == null
        listener.complete.toolCalls*.id == ['call_a', 'call_b']
        listener.complete.toolCalls*.name == ['lookup', 'other']
        listener.complete.toolCalls*.arguments == ['{"a":1}', '{"b":2}']
    }

    def 'CC03 a single choice that says it is index 1 is an error over HTTP as well'() {
        given:
        provider.enqueue(new Reply(body: JsonOutput.toJson([choices: [[index: 1, message: [role: 'assistant', content: 'x'], finish_reason: 'stop']]])))
        when:
        client().call()
        then:
        def e = thrown(LlmException)
        e.message.contains('n=1')
    }

    def 'CC05 logprobs and annotations are the same over HTTP and SSE'() {
        given:
        Map lp = [content: [[token: 'a', logprob: -0.5d, bytes: [97], top_logprobs: []]], refusal: []]
        provider.enqueue(new Reply(body: JsonOutput.toJson([id: 'x', choices: [[index: 0, finish_reason: 'stop',
                message: [role: 'assistant', content: 'a'], logprobs: lp]]])))
        provider.enqueue(new Reply(chunks: [sse(JsonOutput.toJson([id: 'x', choices: [[index: 0, delta: [content: 'a'], logprobs: lp]]])),
                chunk([:], 'stop'), DONE]))
        def http = client().call()
        def streamed = client().stream(new ClientListener())
        expect:
        http.metadata.choice.logprobs.content[0].token == 'a'
        streamed.metadata.choice.logprobs.content[0].token == 'a'
        http.metadata.choice.logprobs.content[0].logprob == streamed.metadata.choice.logprobs.content[0].logprob
    }

    @Unroll
    def 'CC04 a strict schema is checked through #shape'() {
        given:
        def request = req()
        request.tools = [countingTool(new AtomicInteger(), true, schema)]
        Throwable failure = null
        when:
        try { OpenAiCompatProtocol.buildRequestBody(request) } catch (LlmException e) { failure = e }
        then:
        expected == null ? failure == null : (failure != null && failure.message.contains(expected))
        where:
        shape                           | expected                          | schema
        'an array of strict objects'    | null                              | [type: 'object', properties: [rows: [type: 'array', items: [type: 'object', properties: [x: [type: 'string']], required: ['x'], additionalProperties: false]]], required: ['rows'], additionalProperties: false]
        'an array of open objects'      | 'parameters.properties.rows.items' | [type: 'object', properties: [rows: [type: 'array', items: [type: 'object', properties: [x: [type: 'string']], required: ['x']]]], required: ['rows'], additionalProperties: false]
        'a nullable optional property'  | null                              | [type: 'object', properties: [a: [type: ['string', 'null']]], required: ['a'], additionalProperties: false]
        'anyOf with an open branch'     | 'anyOf[1]'                        | [type: 'object', properties: [u: [anyOf: [[type: 'string'], [type: 'object', properties: [k: [type: 'string']], required: ['k']]]]], required: ['u'], additionalProperties: false]
        'a strict $ref'                 | null                              | [type: 'object', properties: [o: ['$ref': '#/$defs/Inner']], required: ['o'], additionalProperties: false, '$defs': [Inner: [type: 'object', properties: [k: [type: 'string']], required: ['k'], additionalProperties: false]]]
        'an open $ref'                  | 'Inner'                           | [type: 'object', properties: [o: ['$ref': '#/$defs/Inner']], required: ['o'], additionalProperties: false, '$defs': [Inner: [type: 'object', properties: [k: [type: 'string']], required: ['k']]]]
        'a $ref that does not resolve'  | 'cannot be resolved'              | [type: 'object', properties: [o: ['$ref': '#/$defs/Missing']], required: ['o'], additionalProperties: false]
    }

    def 'CC06 a named tool_choice must name a tool that is sent'() {
        given:
        def request = req()
        request.tools = tools ? [countingTool(new AtomicInteger(), null, [type: 'object', properties: [:]])] : null
        request.extraBody = [tool_choice: [type: 'function', function: [name: name]]]
        Map body = null
        Throwable failure = null
        when:
        try { body = OpenAiCompatProtocol.buildRequestBody(request) } catch (LlmException e) { failure = e }
        then:
        ok ? (failure == null && body.tool_choice.function.name == name) : failure != null
        where:
        name     | tools | ok
        'lookup' | true  | true
        'ghost'  | true  | false
        'lookup' | false | false
    }

    // ---- optional live smoke checks (OpenAI), skipped unless llm_openai_api_key is set in the environment -----------

    private static String liveKey() { System.getenv('llm_openai_api_key') ?: System.getProperty('llm_openai_api_key') }

    private ProtocolRequest liveRequest(boolean stream) {
        ProtocolRequest r = new ProtocolRequest()
        r.profileName = 'openai-live'
        r.endpointUrl = ((System.getenv('llm_openai_url') ?: 'https://api.openai.com') - ~/\/+$/) + '/v1/chat/completions'
        r.apiKey = liveKey()
        r.model = System.getenv('llm_openai_model') ?: 'gpt-4o-mini'
        r.window = [LlmMessage.user('Reply with the single word pong.')]
        // no temperature (reasoning models refuse it) and room for reasoning tokens: independent of the model named
        r.maxTokens = 1024
        r.maxTokensParameter = 'max_completion_tokens'
        r.timeoutSeconds = 60
        r.retryMax = 0
        r.stream = stream
        r.requestFactory = requestFactory
        r
    }

    @IgnoreIf({ !(System.getenv('llm_openai_api_key') ?: System.getProperty('llm_openai_api_key')) })
    def 'optional live: text over HTTP carries usage details and metadata'() {
        when:
        def result = new OpenAiCompatProtocol().chat(liveRequest(false))
        then:
        result.finishReason == LlmFinishReason.STOP
        result.content.toLowerCase().contains('pong')
        result.usage.totalTokens > 0
        result.metadata.response.id != null
        result.metadata.choice.finish_reason == 'stop'
        result.metadata.usage.containsKey('prompt_tokens_details')
    }

    @IgnoreIf({ !(System.getenv('llm_openai_api_key') ?: System.getProperty('llm_openai_api_key')) })
    def 'optional live: streaming ends with a finished choice, the usage chunk and DONE'() {
        when:
        def listener = new ProtoListener()
        new OpenAiCompatProtocol().chatStream(liveRequest(true), listener)
        then:
        listener.failure == null
        listener.completions == 1
        listener.complete.finishReason == LlmFinishReason.STOP
        listener.complete.content.toLowerCase().contains('pong')
        listener.complete.usage.totalTokens > 0
        listener.complete.metadata.response.id != null
    }

    @IgnoreIf({ !(System.getenv('llm_openai_api_key') ?: System.getProperty('llm_openai_api_key')) })
    def 'optional live: a strict function tool is accepted and called with valid arguments'() {
        given:
        def request = liveRequest(false)
        request.maxTokens = 1024
        request.window = [LlmMessage.user('Look up alpha with the lookup tool.')]
        request.tools = [countingTool(new AtomicInteger(), true,
                [type: 'object', properties: [query: [type: 'string']], required: ['query'], additionalProperties: false])]
        request.extraBody = [tool_choice: [type: 'function', function: [name: 'lookup']]]
        when:
        def result = new OpenAiCompatProtocol().chat(request)
        then:
        result.finishReason == LlmFinishReason.TOOL_CALLS
        result.toolCalls*.name == ['lookup']
        new JsonSlurper().parseText(result.toolCalls[0].arguments).query != null
    }

    // ---- scripted provider --------------------------------------------------------------------------------------

    static class Reply {
        int status = 200
        String contentType
        String body
        List chunks
        boolean abort
    }

    static class ScriptedProvider extends Handler.Abstract {
        final LinkedBlockingQueue<Reply> replies = new LinkedBlockingQueue<>()
        final List<Map> hits = new CopyOnWriteArrayList<>()

        void reset() { replies.clear(); hits.clear() }
        void enqueue(Reply reply) { replies.add(reply) }

        @Override
        boolean handle(Request request, Response response, Callback callback) throws Exception {
            hits << [body: Content.Source.asString(request)]
            Reply reply = replies.poll()
            if (reply == null) { response.setStatus(500); callback.succeeded(); return true }
            try {
                response.setStatus(reply.status)
                if (reply.chunks != null) {
                    response.getHeaders().put(HttpHeader.CONTENT_TYPE, 'text/event-stream; charset=UTF-8')
                    reply.chunks.eachWithIndex { chunk, int i ->
                        boolean last = !reply.abort && i == reply.chunks.size() - 1
                        byte[] bytes = chunk instanceof byte[] ? (byte[]) chunk : chunk.toString().getBytes(StandardCharsets.UTF_8)
                        Content.Sink.write(response, last, ByteBuffer.wrap(bytes))
                    }
                    if (reply.abort) { callback.failed(new IOException('provider dropped the connection')); return true }
                } else {
                    response.getHeaders().put(HttpHeader.CONTENT_TYPE, reply.contentType ?: 'application/json')
                    Content.Sink.write(response, true, ByteBuffer.wrap(reply.body.getBytes(StandardCharsets.UTF_8)))
                }
                callback.succeeded()
            } catch (Exception e) {
                callback.failed(e)
            }
            true
        }
    }

    /** The real protocol, pointed at the loopback provider whatever endpoint the profile was built with. */
    static class RedirectProtocol extends OpenAiCompatProtocol {
        final String url
        final RestClient.RequestFactory factory
        RedirectProtocol(String url, RestClient.RequestFactory factory) { this.url = url; this.factory = factory }
        @Override ProtocolResult chat(ProtocolRequest request) { point(request); super.chat(request) }
        @Override void chatStream(ProtocolRequest request, ProtocolStreamListener listener) { point(request); super.chatStream(request, listener) }
        private void point(ProtocolRequest request) {
            request.endpointUrl = url
            request.requestFactory = factory
            request.timeoutRetry = false
        }
    }

    static class ProtoListener implements ProtocolStreamListener {
        List<String> deltas = []
        List<String> refusalDeltas = []
        ProtocolResult complete
        Throwable failure
        int completions
        int failures
        @Override void onDelta(String d) { deltas << d }
        @Override void onRefusalDelta(String d) { refusalDeltas << d }
        @Override void onComplete(ProtocolResult r) { complete = r; completions++ }
        @Override void onFailure(Throwable t) { failure = t; failures++ }
    }

    static class ClientListener implements LlmStreamListener {
        List<String> deltas = []
        List<String> refusalDeltas = []
        int completed
        int errors
        @Override void onDelta(String d) { deltas << d }
        @Override void onRefusalDelta(String d) { refusalDeltas << d }
        @Override void onComplete(LlmResponse r) { completed++ }
        @Override void onError(LlmException e) { errors++ }
    }
}
