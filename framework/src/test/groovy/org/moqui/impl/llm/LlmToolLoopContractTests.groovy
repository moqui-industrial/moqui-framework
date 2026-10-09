package org.moqui.impl.llm

import groovy.json.JsonOutput
import org.moqui.context.ExecutionContext
import org.moqui.llm.LlmException
import org.moqui.llm.LlmResponseOptions
import org.moqui.llm.LlmTool

/** The tool loop of an Open Responses turn: what runs is decided here, not by the provider; what the model said stays on record. */
class LlmToolLoopContractTests extends LlmConversationSpecBase {
    final List<String> ran = []

    private LlmTool tool(String name) {
        def runs = ran
        new LlmTool() {
            String getName() { name }
            String getDescription() { name }
            Map<String, Object> getParametersSchema() { [type: 'object', properties: [q: [type: 'string']]] }
            LlmTool.Execution getExecution() { LlmTool.Execution.SERVER }
            Object execute(Map<String, Object> arguments, ExecutionContext x) { runs << name; [found: arguments.q] }
        }
    }

    private static String calls(String id, List<Map> calls) {
        JsonOutput.toJson([id: id, object: 'response', status: 'completed', model: 'gpt-test',
                output: calls.collect { c -> [id: 'fc_' + c.call_id, type: 'function_call', call_id: c.call_id, name: c.name, arguments: c.arguments, status: 'completed'] }])
    }

    def 'a call to a tool that is not offered, or with arguments that are not JSON, runs nothing and the model is told'() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, calls('resp_u1', [[call_id: 'c_unknown', name: 'ghost', arguments: '{}'], [call_id: 'c_bad', name: 'lookup', arguments: '{not json']]))
        provider.enqueueJson(200, answer('resp_u2', 'I could not use them'))
        def p = profile(provider.endpoint, 'loop-unknown')
        def conv = LlmConversationImpl.create(ec, p.name, null)
        when:
        def r = turn(p, conv) { it.tool(tool('lookup')).user('go') }
        def second = provider.requests[1].json.input
        then:
        r.content == 'I could not use them'
        ran.isEmpty()
        second*.type == ['message', 'function_call', 'function_call', 'function_call_output', 'function_call_output']
        second.findAll { it.type == 'function_call_output' }*.call_id == ['c_unknown', 'c_bad']
        second.findAll { it.type == 'function_call_output' }.every { it.output.toString().toLowerCase().contains('error') || it.output.toString().contains('unknown') || it.output.toString().contains('not') }
        cleanup: provider.close()
    }

    def 'a tool_choice restricts what runs, whatever the provider returned'() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, calls('resp_c1', [[call_id: 'c_b', name: 'beta', arguments: '{"q":"x"}']]))
        provider.enqueueJson(200, answer('resp_c2', 'ok'))
        def p = profile(provider.endpoint, 'loop-choice')
        def conv = LlmConversationImpl.create(ec, p.name, null)
        when:
        turn(p, conv) { it.tool(tool('alpha')).tool(tool('beta')).user('go').responseOptions(new LlmResponseOptions().put('tool_choice', choice)) }
        then:
        ran.isEmpty()
        provider.requests[1].json.input.find { it.type == 'function_call_output' }.call_id == 'c_b'
        cleanup: provider.close()
        where:
        choice << [[type: 'function', name: 'alpha'], 'none', [type: 'allowed_tools', mode: 'auto', tools: [[type: 'function', name: 'alpha']]]]
    }

    def 'two calls in one response are both answered, in order, with their own ids, each call once'() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, calls('resp_m1', [[call_id: 'c_1', name: 'alpha', arguments: '{"q":"one"}'], [call_id: 'c_2', name: 'beta', arguments: '{"q":"two"}']]))
        provider.enqueueJson(200, answer('resp_m2', 'both'))
        def p = profile(provider.endpoint, 'loop-many')
        def conv = LlmConversationImpl.create(ec, p.name, null)
        when:
        turn(p, conv) { it.tool(tool('alpha')).tool(tool('beta')).user('go') }
        def second = provider.requests[1].json.input
        then:
        ran == ['alpha', 'beta']
        second*.type == ['message', 'function_call', 'function_call', 'function_call_output', 'function_call_output']
        second.findAll { it.type == 'function_call' }*.call_id == ['c_1', 'c_2']
        second.findAll { it.type == 'function_call_output' }*.call_id == ['c_1', 'c_2']
        cleanup: provider.close()
    }

    def 'a tool that keeps failing does not loop for ever: the limit is reported and the run says why'() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        4.times { provider.enqueueJson(200, calls('resp_l' + it, [[call_id: 'c_' + it, name: 'ghost', arguments: '{}']])) }
        def p = profile(provider.endpoint, 'loop-limit')
        def conv = LlmConversationImpl.create(ec, p.name, null)
        when:
        turn(p, conv) { it.tool(tool('lookup')).user('go').maxIterations(2) }
        then:
        LlmException e = thrown()
        e.message.contains('maxIterations')
        provider.requests.size() == 2
        ran.isEmpty()
        and: 'the run ended failed, with the reason'
        def run = ec.entity.find('moqui.llm.LlmRun').condition('conversationId', conv.conversationId).disableAuthz().list().last()
        run.statusId == 'LlmRunFailed'
        cleanup: provider.close()
    }

    def 'the schema the caller asked for decides whether an answer is valid, not the model'() {
        given:
        Map schema = [type: 'object', properties: [code: [type: 'string'], n: [type: 'integer']], required: ['code', 'n'], additionalProperties: false]
        expect:
        JsonSchemaCheck.problems(schema, text).isEmpty() == valid
        where:
        text                                  | valid
        '{"code":"A","n":3}'                  | true
        '{"code":"A","n":"3"}'                | false
        '{"code":"A"}'                        | false
        '{"code":"A","n":3,"extra":1}'        | false
        '{"code":"A","n":'                    | false
        'plain words'                         | false
    }

    def 'a local reference in the schema is followed, and a cycle does not loop'() {
        given:
        Map schema = [type: 'object', properties: [child: [anyOf: [['$ref': '#'], [type: 'null']]], name: [type: 'string']],
                      required: ['name', 'child'], additionalProperties: false]
        expect:
        JsonSchemaCheck.problems(schema, '{"name":"a","child":{"name":"b","child":null}}').isEmpty()
        !JsonSchemaCheck.problems(schema, '{"name":"a","child":{"name":2,"child":null}}').isEmpty()
    }
}
