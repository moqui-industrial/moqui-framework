import org.moqui.impl.llm.OpenResponsesProtocol
import org.moqui.llm.LlmException
import org.moqui.llm.LlmMessage
import org.moqui.llm.LlmProtocol.ProtocolRequest
import org.moqui.llm.LlmResponseOptions
import org.moqui.llm.LlmTool
import spock.lang.Specification
import spock.lang.Unroll

import java.security.MessageDigest

/**
 * What the client refuses to send, and what it sends, judged by the pinned schema: a value of the wrong type, a number
 * where an integer is required, a value outside an enumeration, a union that matches nothing. The schema in the classpath is
 * the one the scripts check the fixtures against, byte for byte.
 */
class OpenResponsesSchemaTests extends Specification {
    private static ProtocolRequest request() {
        ProtocolRequest r = new ProtocolRequest()
        r.profileName = 'schema'
        r.endpointUrl = 'https://provider.example/v1/responses'
        r.model = 'schema-model'
        r.window = [LlmMessage.user('hi')]
        r
    }

    private static String send(Map<String, Object> options) {
        ProtocolRequest r = request()
        LlmResponseOptions o = new LlmResponseOptions()
        options.each { k, v -> o.put(k, v) }
        r.responseOptions = o
        new OpenResponsesProtocol().prepareBody(r)
    }

    def 'the schema the client checks against is the pinned one, byte for byte'() {
        given:
        byte[] resource = OpenResponsesProtocol.getResourceAsStream('/org/moqui/impl/llm/openapi-2026-04-24.json').bytes
        def manifest = new groovy.json.JsonSlurper().parse(new File(System.getProperty('user.dir'), 'tools/openresponses/compliance-manifest.json'))
        String hash = MessageDigest.getInstance('SHA-256').digest(resource).encodeHex().toString()
        expect: 'the one copy of the schema is the one the compliance runner pins, and the one the coverage matrix records'
        hash == '693f26090d206230ed22b336681f547a2882cf5b131e86743966cf71bbdeedab'
        manifest.files['public/openapi/openapi.json'] == hash
    }

    @Unroll
    def 'a value that the schema refuses is refused before anything is sent: #field = #value'() {
        when:
        send([(field): value])
        then:
        LlmException e = thrown()
        e.message.startsWith('Open Responses request is not valid:')
        e.message.contains(problem)
        where:
        field                | value                                          | problem
        'max_output_tokens'  | 16.5d                                          | 'integer'
        'max_output_tokens'  | '20'                                           | 'max_output_tokens'
        'max_output_tokens'  | true                                           | 'max_output_tokens'
        'max_tool_calls'     | 0                                              | 'at least 1'
        'top_logprobs'       | 21                                             | '0 to 20'
        'top_logprobs'       | 2.5d                                           | 'integer'
        'temperature'        | 'hot'                                          | 'temperature'
        'top_p'              | true                                           | 'top_p'
        'presence_penalty'   | [1]                                            | 'presence_penalty'
        'frequency_penalty'  | [a: 1]                                         | 'frequency_penalty'
        'parallel_tool_calls'| 'yes'                                          | 'parallel_tool_calls'
        'store'              | null                                           | 'store'
        'store'              | 'true'                                         | 'store'
        'background'         | 1                                              | 'background'
        'include'            | ['bogus']                                      | 'include[0]'
        'include'            | 'reasoning.encrypted_content'                  | 'include'
        'truncation'         | 'sometimes'                                    | 'truncation'
        'service_tier'       | 'turbo'                                        | 'service_tier'
        'tool_choice'        | 'sometimes'                                    | 'tool_choice'
        'tool_choice'        | [type: 'function']                             | 'tool_choice'
        'tool_choice'        | [type: 'allowed_tools', tools: []]             | 'tool_choice'
        'metadata'           | (1..17).collectEntries { ["k$it".toString(), 'v'] } | 'metadata'
        'metadata'           | [k: 'x' * 513]                                 | 'metadata.k'
        'metadata'           | [k: 5]                                         | 'metadata.k'
        'text'               | [verbosity: 'loud']                            | 'text'
        'text'               | [format: [type: 'json_schema', name: 5]]       | 'text'
        'text'               | 'plain'                                        | 'text'
        'reasoning'          | [effort: 'extreme']                            | 'reasoning'
        'reasoning'          | [summary: 'long']                              | 'reasoning'
        'stream_options'     | [include_obfuscation: 'x']                     | 'stream_options'
        'safety_identifier'  | 's' * 65                                       | 'safety_identifier'
        'previous_response_id' | 123                                          | 'previous_response_id'
        'instructions'       | []                                             | 'instructions'
    }

    @Unroll
    def 'a value that the schema accepts is sent: #field = #value'() {
        when:
        String json = send([(field): value])
        then:
        json.startsWith('{')
        where:
        field                | value
        'max_output_tokens'  | 16.0d
        'max_output_tokens'  | 4294967312L
        'max_output_tokens'  | null
        'temperature'        | 2.5d
        'temperature'        | null
        'top_p'              | 0
        'parallel_tool_calls'| null
        'parallel_tool_calls'| false
        'include'            | ['reasoning.encrypted_content', 'message.output_text.logprobs']
        'truncation'         | 'disabled'
        'service_tier'       | 'priority'
        'tool_choice'        | 'none'
        'tool_choice'        | null
        'metadata'           | (1..16).collectEntries { ["k$it".toString(), 'v' * 512] }
        'text'               | [format: [type: 'text'], verbosity: 'low']
        'text'               | [format: [type: 'json_schema', name: 'answer', schema: [type: 'object']]]
        'reasoning'          | [effort: 'xhigh', summary: 'auto']
        'reasoning'          | [effort: null]
        'stream_options'     | [include_obfuscation: false]
    }

    def 'an input item that matches no form of the contract is refused with the item named'() {
        given:
        ProtocolRequest r = request()
        r.window = null
        r.inputItems = [org.moqui.llm.LlmItem.message('user', [org.moqui.llm.LlmContentPart.inputText('ok')]),
                        org.moqui.llm.LlmItem.functionCallOutput('call_1', 'fine'),
                        new org.moqui.llm.LlmItem(type: 'function_call', callId: 'call_1', name: 'bad name!', arguments: '{}')]
        when: new OpenResponsesProtocol().prepareBody(r)
        then:
        LlmException e = thrown()
        e.message.contains('input[2]')
    }

    def 'a function tool with parameters that are not an object or a strict flag that is not a boolean is refused'() {
        given:
        ProtocolRequest r = request()
        r.tools = [LlmTool.client('lookup', 'Lookup', [type: 'object', properties: [:]])]
        r.extraBody = extra
        when: new OpenResponsesProtocol().prepareBody(r)
        then:
        thrown(LlmException)
        where:
        extra << [[tools: [[type: 'function', name: 'x', parameters: 'not an object']]],
                  [tools: [[type: 'function', name: 'x', parameters: [type: 'object'], strict: 'yes']]]]
    }

    private static Map wireTool(Boolean strict, Map schema) {
        ProtocolRequest r = request()
        r.tools = [LlmTool.client('lookup', 'Lookup', schema, strict)]
        new groovy.json.JsonSlurper().parseText(new OpenResponsesProtocol().prepareBody(r)).tools[0] as Map
    }

    private static final Map CLOSED = [type: 'object', additionalProperties: false, properties: [q: [type: 'string']], required: ['q']]

    def 'strict is sent exactly as given, and omitted when it is not given'() {
        expect:
        wireTool(true, CLOSED).strict == true
        wireTool(false, [type: 'object', properties: [q: [type: 'string']]]).strict == false
        !wireTool(null, [type: 'object', properties: [q: [type: 'string']]]).containsKey('strict')
        and: 'the schema of the caller is sent as it is'
        wireTool(true, CLOSED).parameters == CLOSED
    }

    def 'a strict tool whose schema is not closed is refused, with the place that breaks the rule'() {
        when:
        wireTool(true, schema)
        then:
        LlmException e = thrown()
        e.message.contains(problem)
        where:
        schema                                                                                                         | problem
        [type: 'object', properties: [q: [type: 'string']], required: ['q']]                                           | 'additionalProperties'
        [type: 'object', additionalProperties: false, properties: [q: [type: 'string'], r: [type: 'string']], required: ['q']] | 'does not require the property r'
        [type: 'object', additionalProperties: false, properties: [o: [type: 'object', properties: [x: [type: 'string']], required: ['x']]], required: ['o']] | 'parameters.o'
        [type: 'object', additionalProperties: false, properties: [a: [type: 'array', items: [type: 'object', properties: [x: [type: 'string']]]]], required: ['a']] | 'parameters.a[]'
        [type: 'object', additionalProperties: false, properties: [d: ['$ref': '#/$defs/Open']], required: ['d'], '$defs': [Open: [type: 'object', properties: [x: [type: 'string']], required: ['x']]]] | '#/$defs/Open'
        [type: 'object', additionalProperties: false, properties: [d: ['$ref': '#/$defs/Missing']], required: ['d']]     | 'not in its schema'
        [:]                                                                                                            | 'no parameters schema'
    }

    def 'a strict schema with a reference to itself is accepted and does not loop'() {
        given:
        Map tree = [type: 'object', additionalProperties: false, properties: [name: [type: 'string'], child: [anyOf: [['$ref': '#/$defs/Node'], [type: 'null']]]], required: ['name', 'child'],
                    '$defs': [Node: [type: 'object', additionalProperties: false, properties: [name: [type: 'string'], child: [anyOf: [['$ref': '#/$defs/Node'], [type: 'null']]]], required: ['name', 'child']]]]
        expect:
        wireTool(true, tree).strict == true
    }

    def 'strict survives the stored description of a tool'() {
        given:
        LlmTool tool = LlmTool.client('lookup', 'Lookup', CLOSED, true)
        when:
        Map descriptor = org.moqui.impl.llm.LlmToolDescriptors.describe(tool)
        LlmTool rebuilt = org.moqui.impl.llm.LlmToolDescriptors.rebuild(descriptor)
        then:
        descriptor.strict == true
        rebuilt.strict == true
        rebuilt.parametersSchema == CLOSED
    }

    def 'a tool choice must name tools the request carries'() {
        given:
        ProtocolRequest r = request()
        r.tools = [LlmTool.client('lookup', 'Lookup', [type: 'object', properties: [:]])]
        r.responseOptions = new LlmResponseOptions().put('tool_choice', choice)
        String failure = null
        String json = null
        when:
        try { json = new OpenResponsesProtocol().prepareBody(r) } catch (LlmException e) { failure = e.message }
        then:
        problem == null ? json.startsWith('{') : (failure != null && failure.contains(problem))
        where:
        choice                                                                                  | problem
        [type: 'function', name: 'lookup']                                                      | null
        'required'                                                                              | null
        'none'                                                                                  | null
        [type: 'function', name: 'other']                                                       | 'which is not one of the tools'
        [type: 'allowed_tools', tools: [[type: 'function', name: 'lookup']]]                    | null
        [type: 'allowed_tools', tools: [[type: 'function', name: 'lookup'], [type: 'function', name: 'nope']]] | 'nope'
    }

    def 'a choice that requires a tool needs tools'() {
        given:
        ProtocolRequest r = request()
        r.responseOptions = new LlmResponseOptions().put('tool_choice', 'required')
        when: new OpenResponsesProtocol().prepareBody(r)
        then:
        LlmException e = thrown()
        e.message.contains('needs tools')
    }

    def 'instructions given twice are refused, not merged'() {
        given:
        ProtocolRequest r = request()
        r.window = null
        r.inputItems = [org.moqui.llm.LlmItem.message('user', [org.moqui.llm.LlmContentPart.inputText('hi')])]
        r.instructions = 'from the profile'
        r.responseOptions = new LlmResponseOptions().put('instructions', 'from the options')
        when: new OpenResponsesProtocol().prepareBody(r)
        then:
        LlmException e = thrown()
        e.message.contains('instructions is set more than once')
    }
}
