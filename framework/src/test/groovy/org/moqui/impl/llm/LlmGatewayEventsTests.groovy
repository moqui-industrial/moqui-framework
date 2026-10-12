package org.moqui.impl.llm

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.moqui.context.ExecutionContext
import org.moqui.impl.webapp.ServletStreamListener
import org.moqui.impl.webapp.SseSink
import org.moqui.llm.LlmException
import org.moqui.llm.LlmTool

/** The structured events a browser can ask for: correlated, numbered on their own, and free of what the provider keeps to itself. */
class LlmGatewayEventsTests extends LlmConversationSpecBase {
    private static String frame(Map event) { 'event: ' + event.type + '\ndata: ' + JsonOutput.toJson(event) + '\n\n' }

    private static List<String> toolInference(String id, long first) {
        Map call = [id: 'fc_' + id, type: 'function_call', call_id: 'call_' + id, name: 'service_search', arguments: '{"query":"a"}', status: 'completed']
        [frame([type: 'response.created', sequence_number: first, response: [id: id, status: 'in_progress', output: [], instructions: 'SECRETINSTRUCTIONS',
                tools: [[name: 'SECRETTOOL']], metadata: [k: 'SECRETMETA']]]),
         frame([type: 'response.output_item.added', sequence_number: first + 1, output_index: 0, item: call + [arguments: '', status: 'in_progress']]),
         frame([type: 'response.function_call_arguments.done', sequence_number: first + 2, item_id: call.id, output_index: 0, arguments: call.arguments]),
         frame([type: 'response.output_item.done', sequence_number: first + 3, output_index: 0, item: call]),
         frame([type: 'response.completed', sequence_number: first + 4, response: [id: id, status: 'completed', output: [call],
                usage: [input_tokens: 3, output_tokens: 2, total_tokens: 5]]])]
    }

    private static List<String> answerInference(String id, long first) {
        Map reasoning = [id: 'rs_' + id, type: 'reasoning', summary: [], encrypted_content: 'SECRETBLOB']
        Map msg = [id: 'msg_' + id, type: 'message', role: 'assistant', status: 'completed', content: [[type: 'output_text', text: 'all done']]]
        [frame([type: 'response.created', sequence_number: first, response: [id: id, status: 'in_progress', output: []]]),
         frame([type: 'response.output_item.added', sequence_number: first + 1, output_index: 0, item: [id: reasoning.id, type: 'reasoning', summary: []]]),
         frame([type: 'response.reasoning_text.delta', sequence_number: first + 2, item_id: reasoning.id, output_index: 0, delta: 'SECRETTHOUGHT']),
         frame([type: 'response.output_item.done', sequence_number: first + 3, output_index: 0, item: reasoning]),
         frame([type: 'response.output_item.added', sequence_number: first + 4, output_index: 1, item: [id: msg.id, type: 'message', role: 'assistant', status: 'in_progress', content: []]]),
         frame([type: 'response.content_part.added', sequence_number: first + 5, item_id: msg.id, output_index: 1, content_index: 0, part: [type: 'output_text', text: '']]),
         frame([type: 'response.output_text.delta', sequence_number: first + 6, item_id: msg.id, output_index: 1, content_index: 0, delta: 'all ']),
         frame([type: 'response.output_text.delta', sequence_number: first + 7, item_id: msg.id, output_index: 1, content_index: 0, delta: 'done']),
         frame([type: 'response.output_text.done', sequence_number: first + 8, item_id: msg.id, output_index: 1, content_index: 0, text: 'all done']),
         frame([type: 'response.content_part.done', sequence_number: first + 9, item_id: msg.id, output_index: 1, content_index: 0, part: msg.content[0]]),
         frame([type: 'response.output_item.done', sequence_number: first + 10, output_index: 1, item: msg]),
         frame([type: 'response.completed', sequence_number: first + 11, response: [id: id, status: 'completed', output: [reasoning, msg]]])]
    }

    private LlmTool searchTool() {
        new LlmTool() {
            String getName() { 'service_search' }
            String getDescription() { 'search' }
            Map<String, Object> getParametersSchema() { [type: 'object', properties: [query: [type: 'string']], required: ['query']] }
            LlmTool.Execution getExecution() { LlmTool.Execution.SERVER }
            Object execute(Map<String, Object> arguments, ExecutionContext executionContext) { [results: []] }
        }
    }

    private String streamTurn(boolean structured, SseSink sinkIn = null, StringWriter sw = new StringWriter()) {
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueSse(toolInference('resp_e1', 0) + ['data: [DONE]\n\n'])
        provider.enqueueSse(answerInference('resp_e2', 0) + ['data: [DONE]\n\n'])
        def p = profile(provider.endpoint, 'gw-ev')
        def conv = LlmConversationImpl.create(ec, p.name, null)
        LlmClientImpl client = new LlmClientImpl(ec, p, { false })
        client.conversation(conv).user('search').tool(searchTool())
        def listener = new ServletStreamListener(sinkIn ?: new SseSink(sw), client, structured)
        boolean off = ec.artifactExecution.disableAuthz()
        try { client.stream(listener) } finally { if (!off) ec.artifactExecution.enableAuthz(); provider.close() }
        sw.toString()
    }

    private static List<Map> structuredEvents(String text) {
        text.split('\n\n').findAll { it.startsWith('event: response_event') }.collect {
            new JsonSlurper().parseText(it.readLines().find { l -> l.startsWith('data: ') }.substring(6)) as Map
        }
    }

    def 'a tool loop gives two correlated inferences with a sequence of their own and nothing the provider keeps to itself'() {
        when:
        String text = streamTurn(true)
        List<Map> events = structuredEvents(text)
        def types = events*.type
        then:
        events*.seq == (1..events.size()).toList()
        events*.v.unique() == ['v1']
        events*.inference.unique() == [1, 2]
        events.findAll { it.inference == 1 }*.responseId.unique() == ['resp_e1']
        events.findAll { it.inference == 2 }*.responseId.unique() == ['resp_e2']
        events*.conversationId.unique().size() == 1 && events[0].conversationId != null
        events*.runId.unique().size() == 1
        and: 'the provider sequence is kept apart and restarts per inference'
        events.findAll { it.inference == 2 }*.providerSequence.min() == 0
        events.count { it.terminal } == 2
        types.count('response.completed') == 2
        events.findAll { it.type == 'response.output_text.delta' }.collect { it.data.delta }.join('') == 'all done'
        and: 'neither raw reasoning, nor encrypted content, nor the echo of the request leave the server'
        !text.contains('SECRETTHOUGHT')
        !text.contains('SECRETBLOB')
        !text.contains('SECRETINSTRUCTIONS')
        !text.contains('SECRETTOOL')
        !text.contains('SECRETMETA')
        and: 'the turn still ends with the event that says so'
        text.findAll(/event: (\w+)/) { it[1] }.last() == 'done'
    }

    def 'without the request for them there are no structured events'() {
        when:
        String text = streamTurn(false)
        then:
        !text.contains('response_event')
        text.contains('event: delta')
    }

    def 'a format this server does not know is refused'() {
        when:
        org.moqui.impl.llm.GatewayEvents.negotiate('v9')
        then:
        LlmException e = thrown()
        e.httpStatus == 400
        org.moqui.impl.llm.GatewayEvents.negotiate('v1')
        !org.moqui.impl.llm.GatewayEvents.negotiate(null)
    }

    def 'a browser that goes away stops the stream and the conversation is not left streaming'() {
        given:
        def failing = new Writer() {
            int writes = 0
            void write(char[] c, int o, int l) { if (++writes > 6) throw new IOException('broken pipe') }
            void flush() { }
            void close() { }
        }
        when:
        try { streamTurn(true, new SseSink(failing)) } catch (LlmException expected) { }
        then:
        ec.entity.find('moqui.llm.LlmConversation').condition('statusId', 'LlmcsStreaming').disableAuthz().list()
                .findAll { it.profileName == 'gw-ev' }.isEmpty()
    }
}
