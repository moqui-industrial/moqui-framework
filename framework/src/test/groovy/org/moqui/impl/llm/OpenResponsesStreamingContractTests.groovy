package org.moqui.impl.llm

import groovy.json.JsonOutput
import org.moqui.llm.LlmException
import org.moqui.llm.LlmFinishReason
import org.moqui.llm.LlmResponse
import org.moqui.llm.LlmStreamListener
import org.moqui.llm.LlmTool

/** What a stream has to be to become a result: framed as SSE says, consistent with itself, and bounded. */
class OpenResponsesStreamingContractTests extends LlmConversationSpecBase {
    static String frame(String type, long seq, Map fields, String eol = '\n') {
        "event: ${type}${eol}data: ${JsonOutput.toJson([type: type, sequence_number: seq] + fields)}${eol}${eol}".toString()
    }

    static List<String> messageStream(String responseId, String text, String eol = '\n', Map completedOutputItem = null) {
        Map item = [id: 'msg_s1', type: 'message', role: 'assistant', status: 'completed']
        Map done = item + [content: [[type: 'output_text', text: text]]]
        [frame('response.created', 0, [response: [id: responseId, status: 'in_progress', output: []]], eol),
         frame('response.output_item.added', 1, [output_index: 0, item: item + [status: 'in_progress', content: []]], eol),
         frame('response.content_part.added', 2, [item_id: 'msg_s1', output_index: 0, content_index: 0, part: [type: 'output_text', text: '']], eol),
         frame('response.output_text.delta', 3, [item_id: 'msg_s1', output_index: 0, content_index: 0, delta: text], eol),
         frame('response.output_text.done', 4, [item_id: 'msg_s1', output_index: 0, content_index: 0, text: text], eol),
         frame('response.content_part.done', 5, [item_id: 'msg_s1', output_index: 0, content_index: 0, part: done.content[0]], eol),
         frame('response.output_item.done', 6, [output_index: 0, item: done], eol),
         frame('response.completed', 7, [response: [id: responseId, status: 'completed', output: [completedOutputItem ?: done],
                 usage: [input_tokens: 1, output_tokens: 1, total_tokens: 2]]], eol),
         'data: [DONE]' + eol + eol]
    }

    private LlmResponse stream(List<String> frames, List<LlmTool> tools = []) {
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueSse(frames)
        try {
            LlmClientImpl client = new LlmClientImpl(null, profile(provider.endpoint, 'stream-contract'), { false })
            client.user('go')
            tools.each { client.tool(it) }
            return client.stream(new LlmStreamListener() { })
        } finally { provider.close() }
    }

    def 'the same stream with LF, CRLF and CR line ends gives the same result'() {
        expect:
        LlmResponse r = stream(messageStream('resp_f', 'Cafè ☕', eol))
        r.content == 'Cafè ☕'
        r.responseId == 'resp_f'
        r.outputItems*.type == ['message']
        where:
        eol << ['\n', '\r\n', '\r']
    }

    def 'comments, ids, a multi-line data field and the unknown fields of a frame are framing, not content'() {
        given:
        List<String> frames = messageStream('resp_m', 'plain')
        String created = JsonOutput.prettyPrint(JsonOutput.toJson([type: 'response.created', sequence_number: 0, response: [id: 'resp_m', status: 'in_progress', output: []]]))
        frames[0] = ': keep-alive\n' + 'id: 7\n' + 'retry: 1000\n' + 'event: response.created\n' + created.readLines().collect { 'data: ' + it }.join('\n') + '\n\n'
        when:
        LlmResponse r = stream(frames)
        then:
        r.content == 'plain'
        r.responseId == 'resp_m'
    }

    def 'a stream with no normal text is still a result: a call alone, a refusal alone'() {
        given:
        Map call = [id: 'fc_t1', type: 'function_call', call_id: 'call_t1', name: 'lookup', arguments: '{"q":"a"}', status: 'completed']
        List<String> toolOnly = [frame('response.created', 0, [response: [id: 'resp_t', status: 'in_progress', output: []]]),
                frame('response.output_item.added', 1, [output_index: 0, item: call + [arguments: '', status: 'in_progress']]),
                frame('response.function_call_arguments.delta', 2, [item_id: 'fc_t1', output_index: 0, delta: '{"q":"a"}']),
                frame('response.function_call_arguments.done', 3, [item_id: 'fc_t1', output_index: 0, arguments: '{"q":"a"}']),
                frame('response.output_item.done', 4, [output_index: 0, item: call]),
                frame('response.completed', 5, [response: [id: 'resp_t', status: 'completed', output: [call]]]), 'data: [DONE]\n\n']
        Map refusal = [id: 'msg_r1', type: 'message', role: 'assistant', status: 'completed', content: [[type: 'refusal', refusal: 'I cannot help with that']]]
        List<String> refusalOnly = [frame('response.created', 0, [response: [id: 'resp_r', status: 'in_progress', output: []]]),
                frame('response.output_item.added', 1, [output_index: 0, item: refusal + [status: 'in_progress', content: []]]),
                frame('response.content_part.added', 2, [item_id: 'msg_r1', output_index: 0, content_index: 0, part: [type: 'refusal', refusal: '']]),
                frame('response.refusal.delta', 3, [item_id: 'msg_r1', output_index: 0, content_index: 0, delta: 'I cannot help with that']),
                frame('response.refusal.done', 4, [item_id: 'msg_r1', output_index: 0, content_index: 0, refusal: 'I cannot help with that']),
                frame('response.content_part.done', 5, [item_id: 'msg_r1', output_index: 0, content_index: 0, part: refusal.content[0]]),
                frame('response.output_item.done', 6, [output_index: 0, item: refusal]),
                frame('response.completed', 7, [response: [id: 'resp_r', status: 'completed', output: [refusal]]]), 'data: [DONE]\n\n']
        when:
        LlmResponse calls = stream(toolOnly)
        LlmResponse refused = stream(refusalOnly)
        then:
        calls.toolCalls*.name == ['lookup']
        calls.toolCalls[0].arguments == '{"q":"a"}'
        calls.finishReason == LlmFinishReason.TOOL_CALLS
        refused.outputItems*.type == ['message']
        refused.outputItems[0].content[0].refusal == 'I cannot help with that'
    }

    def 'the completed response must agree with the items the stream finished'() {
        when:
        stream(messageStream('resp_x', 'streamed text', '\n', completedItem))
        then:
        LlmException e = thrown()
        e.message.contains('inconsistent')
        e.message.contains(fragment)
        where:
        completedItem                                                                                                         | fragment
        [id: 'msg_s1', type: 'message', role: 'assistant', content: [[type: 'output_text', text: 'another text']]]              | 'text of the item'
        [id: 'msg_other', type: 'message', role: 'assistant', content: [[type: 'output_text', text: 'streamed text']]]          | 'another id'
        [id: 'msg_s1', type: 'function_call', call_id: 'c', name: 'n', arguments: '{}']                                        | 'in the stream and a'
    }

    def 'an item that the completed response adds, or loses, is not taken silently'() {
        given:
        List<String> frames = messageStream('resp_y', 'one')
        Map done = [id: 'msg_s1', type: 'message', role: 'assistant', content: [[type: 'output_text', text: 'one']]]
        frames[7] = frame('response.completed', 7, [response: [id: 'resp_y', status: 'completed', output: output(done)]])
        when:
        stream(frames)
        then:
        LlmException e = thrown()
        e.message.contains('inconsistent')
        where:
        output << [{ d -> [] }, { d -> [d, d + [id: 'msg_extra']] }]
    }

    def 'an event or a stream past its bound is an error that names the bound'() {
        given:
        System.setProperty(property, '2000')
        when:
        stream(messageStream('resp_big', 'x' * 5000))
        then:
        LlmException e = thrown()
        e.message.contains('longer than')
        cleanup:
        System.clearProperty(property)
        where:
        property << ['llm_sse_max_event_bytes', 'llm_sse_max_stream_bytes']
    }

    def 'a tool whose arguments were never finished is not run, and the stream is an error'() {
        given:
        boolean ran = false
        LlmTool tool = new LlmTool() {
            String getName() { 'lookup' }
            String getDescription() { 'x' }
            Map<String, Object> getParametersSchema() { [type: 'object', properties: [q: [type: 'string']]] }
            LlmTool.Execution getExecution() { LlmTool.Execution.SERVER }
            Object execute(Map<String, Object> arguments, org.moqui.context.ExecutionContext executionContext) { ran = true; [ok: true] }
        }
        List<String> cut = [frame('response.created', 0, [response: [id: 'resp_c', status: 'in_progress', output: []]]),
                frame('response.output_item.added', 1, [output_index: 0, item: [id: 'fc_c1', type: 'function_call', call_id: 'call_c1', name: 'lookup', arguments: '', status: 'in_progress']]),
                frame('response.function_call_arguments.delta', 2, [item_id: 'fc_c1', output_index: 0, delta: '{"q":'])]
        when:
        stream(cut, [tool])
        then:
        thrown(LlmException)
        !ran
    }

    def 'the same facts as one JSON response and as a stream give the same result'() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, JsonOutput.toJson([id: 'resp_eq', object: 'response', status: 'completed', model: 'gpt-test',
                output: [[id: 'msg_s1', type: 'message', role: 'assistant', status: 'completed', content: [[type: 'output_text', text: 'same']]]],
                usage: [input_tokens: 1, output_tokens: 1, total_tokens: 2]]))
        LlmClientImpl client = new LlmClientImpl(null, profile(provider.endpoint, 'stream-eq'), { false })
        client.user('go')
        LlmResponse plain = client.call()
        provider.close()
        when:
        LlmResponse streamed = stream(messageStream('resp_eq', 'same'))
        then:
        plain.content == streamed.content
        plain.finishReason == streamed.finishReason
        plain.outputItems*.type == streamed.outputItems*.type
        plain.outputItems[0].providerItemId == streamed.outputItems[0].providerItemId
        plain.usage.totalTokens == streamed.usage.totalTokens
    }
}
