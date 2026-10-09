package org.moqui.impl.llm

import groovy.json.JsonOutput
import org.moqui.llm.LlmException
import org.moqui.llm.LlmItem
import org.moqui.llm.LlmProtocol.ProtocolRequest
import spock.lang.Specification

/**
 * What a request takes back of what a response returned. The source is the schema of the input items in the pinned contract,
 * not the behavior of a provider or of a library: the stored item is the response as it came, the wire item is its projection.
 */
class OpenResponsesReplayContractTests extends Specification {
    private static Map deepCopy(Object value) { new groovy.json.JsonSlurper().parseText(JsonOutput.toJson(value)) as Map }

    private static List<String> problems(Map wire) { OpenResponsesSchema.get().validate('ItemParam', wire) }

    def 'an assistant message keeps what the input schema declares and drops what only a response carries'() {
        given:
        Map output = [id: 'msg_1', type: 'message', role: 'assistant', status: 'completed', phase: 'final_answer',
                      content: [[type: 'output_text', text: 'hello', annotations: [[type: 'url_citation', start_index: 0, end_index: 2, url: 'https://x.test', title: 't']],
                                 logprobs: [[token: 'h', logprob: -0.1, bytes: [104], top_logprobs: []]]]]]
        LlmItem item = OpenResponsesCodec.mapToItem(output)
        Map before = deepCopy(item.payload)
        when:
        Map wire = OpenResponsesCodec.toInputItem(item)
        then:
        wire.id == 'msg_1'
        wire.phase == 'final_answer'
        wire.content[0].keySet() == ['type', 'text', 'annotations'] as Set
        problems(wire).isEmpty()
        and: 'the stored item is untouched, logprobs included'
        item.payload == before
        item.content[0].logprobs != null
    }

    def 'serializing twice gives the same item and changes nothing'() {
        given:
        LlmItem item = OpenResponsesCodec.mapToItem([id: 'msg_2', type: 'message', role: 'assistant',
                content: [[type: 'output_text', text: 'x', logprobs: []]]])
        Map before = deepCopy(OpenResponsesCodec.itemToMap(item))
        when:
        Map first = OpenResponsesCodec.toInputItem(item)
        Map second = OpenResponsesCodec.toInputItem(item)
        then:
        first == second
        JsonOutput.toJson(first) == JsonOutput.toJson(second)
        deepCopy(OpenResponsesCodec.itemToMap(item)) == before
    }

    def 'an assistant message without a provider id goes without one, and a local id never becomes the identity'() {
        given:
        LlmItem noId = LlmItem.message('assistant', [org.moqui.llm.LlmContentPart.outputText('a')])
        LlmItem local = LlmItem.message('assistant', [org.moqui.llm.LlmContentPart.outputText('b')])
        local.itemId = '10042'
        when:
        Map a = OpenResponsesCodec.toInputItem(noId)
        Map b = OpenResponsesCodec.toInputItem(local)
        then:
        !a.containsKey('id')
        problems(a).isEmpty()
        and: 'only a provider item id is an id on the wire'
        local.providerItemId == null
        !b.containsKey('id') || b.id != '10042' || local.providerItemId == '10042'
    }

    def 'reasoning is replayed by its input schema: summary and encrypted content, never the raw text'() {
        expect:
        Map wire = OpenResponsesCodec.toInputItem(OpenResponsesCodec.mapToItem(output))
        wire.keySet() == keys as Set
        problems(wire).isEmpty()
        wire.summary == summary
        wire.encrypted_content == encrypted
        where:
        output                                                                                                              | keys                                    | summary                                       | encrypted
        [id: 'rs_1', type: 'reasoning', summary: [[type: 'summary_text', text: 's']]]                                           | ['id', 'type', 'summary']                 | [[type: 'summary_text', text: 's']]           | null
        [id: 'rs_2', type: 'reasoning', summary: [], encrypted_content: 'blob']                                                 | ['id', 'type', 'summary', 'encrypted_content'] | []                                          | 'blob'
        [id: 'rs_3', type: 'reasoning', summary: [[type: 'summary_text', text: 's']], encrypted_content: 'blob']                | ['id', 'type', 'summary', 'encrypted_content'] | [[type: 'summary_text', text: 's']]        | 'blob'
        [id: 'rs_4', type: 'reasoning', encrypted_content: 'blob', content: [[type: 'reasoning_text', text: 'raw thought']]]     | ['id', 'type', 'summary', 'encrypted_content'] | []                                          | 'blob'
        [id: 'rs_5', type: 'reasoning', summary: [], content: null]                                                             | ['id', 'type', 'summary', 'content']      | []                                            | null
    }

    def 'a function call and its output keep their ids and the output keeps its shape'() {
        given:
        Map call = OpenResponsesCodec.toInputItem(OpenResponsesCodec.mapToItem([id: 'fc_1', type: 'function_call', call_id: 'call_1',
                name: 'lookup', arguments: '{"a":1}', status: 'completed']))
        Map asString = OpenResponsesCodec.toInputItem(new LlmItem(type: 'function_call_output', callId: 'call_1', output: '{"ok":true}'))
        Map asArray = OpenResponsesCodec.toInputItem(LlmItem.functionCallOutput('call_1', [org.moqui.llm.LlmContentPart.inputText('t')]))
        expect:
        call.id == 'fc_1' && call.call_id == 'call_1'
        problems(call).isEmpty() && problems(asString).isEmpty() && problems(asArray).isEmpty()
        asString.output == '{"ok":true}'
        asArray.output instanceof List && asArray.output[0].text == 't'
    }

    def 'an item of a kind the contract does not know is passed on as it is'() {
        given:
        Map extension = [type: 'acme:note', id: 'x_1', payload: [k: 'v']]
        expect:
        OpenResponsesCodec.toInputItem(OpenResponsesCodec.mapToItem(extension)).payload == [k: 'v']
    }

    private static ProtocolRequest request(List<LlmItem> items, String previous = null) {
        ProtocolRequest r = new ProtocolRequest()
        r.profileName = 'replay'
        r.model = 'm'
        r.inputItems = items
        r.previousResponseId = previous
        r
    }

    def 'a call without an output, or an output without its call, is refused before anything is sent'() {
        when:
        new OpenResponsesProtocol().prepareBody(request(items, previous))
        then:
        LlmException e = thrown()
        e.message.contains(fragment)
        where:
        items                                                                                                              | previous | fragment
        [new LlmItem(type: 'function_call', callId: 'c9', name: 'f', arguments: '{}')]                                       | null     | 'no output for it'
        [LlmItem.functionCallOutput('c9', 'x')]                                                                              | null     | 'no call of that id'
    }

    def 'an output continues a call held by the provider when the request says which response it continues'() {
        when:
        def body = new OpenResponsesProtocol().prepareBody(request([LlmItem.functionCallOutput('c9', 'x')], 'resp_1'))
        then:
        noExceptionThrown()
        body != null
    }

    /** One of each kind of input item of the pinned contract, as a request carries it. */
    static final List<Map> INPUT_ITEMS = [
        [type: 'message', role: 'user', content: [[type: 'input_text', text: 'hello']]],
        [type: 'message', role: 'user', content: [[type: 'input_text', text: 'look'], [type: 'input_image', image_url: 'data:image/png;base64,AAAA', detail: 'low']]],
        [type: 'message', role: 'user', content: [[type: 'input_file', filename: 'a.txt', file_data: 'aGk=']]],
        [type: 'message', role: 'user', content: [[type: 'input_file', file_url: 'https://files.example.test/a.pdf']]],
        [type: 'message', role: 'user', content: 'a plain string content'],
        [type: 'message', role: 'system', content: [[type: 'input_text', text: 'be brief']]],
        [type: 'message', role: 'developer', content: [[type: 'input_text', text: 'internal']]],
        [id: 'msg_a', type: 'message', role: 'assistant', content: [[type: 'output_text', text: 'hi', annotations: [[type: 'url_citation', url: 'https://x.test', title: 't', start_index: 0, end_index: 2]]]]],
        [type: 'message', role: 'assistant', content: [[type: 'refusal', refusal: 'no']]],
        [id: 'fc_1', type: 'function_call', call_id: 'call_1', name: 'lookup', arguments: '{"q":"a"}', status: 'completed'],
        [type: 'function_call_output', call_id: 'call_1', output: '{"ok":true}'],
        [type: 'function_call_output', call_id: 'call_2', output: [[type: 'input_text', text: 'part one'], [type: 'input_text', text: 'part two']]],
        [id: 'rs_1', type: 'reasoning', summary: [[type: 'summary_text', text: 'because']], encrypted_content: 'blob'],
        [id: 'rs_2', type: 'reasoning', summary: []],
        [id: 'cmp_1', type: 'compaction', encrypted_content: 'opaque'],
        [type: 'item_reference', id: 'msg_remote_1']
    ]

    def 'every kind of input item of the contract goes wire to item to storage to item to wire without losing a field'() {
        when:
        LlmItem item = OpenResponsesCodec.mapToItem(wire)
        LlmItem stored = LlmJson.itemFromStored(new groovy.json.JsonSlurper().parseText(LlmJson.toJson(item)) as Map)
        Map back = OpenResponsesCodec.toInputItem(stored)
        // a call and its output only make a valid request together; the shape of the item is what is under test here
        then: 'the projection of the item is what was sent'
        back == wire
        and: 'and it is valid for the contract'
        problems(back).isEmpty()
        where:
        wire << INPUT_ITEMS
    }
}
