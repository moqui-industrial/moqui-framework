package org.moqui.impl.llm

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.UserFacadeImpl
import org.moqui.llm.LlmContentPart
import org.moqui.llm.LlmException
import org.moqui.llm.LlmItem
import org.moqui.llm.LlmMessage
import org.moqui.llm.LlmResponse
import org.moqui.llm.LlmTool
import org.moqui.llm.LlmToolResult
import org.moqui.llm.WindowPolicy
import org.moqui.llm.test.FakeLlmProtocol
import spock.lang.Shared
import spock.lang.Specification

/** Open Responses conversations: the trajectory is the structured one, the Chat Completions message entity is never touched. */
class LlmResponsesConversationTests extends LlmConversationSpecBase {
    def "an Open Responses turn leaves no message row and no access to the message entity, and the history comes from the items"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, answer('resp_t1', 'first answer'))
        def p = profile(provider.endpoint)
        def conv = LlmConversationImpl.create(ec, p.name, null)
        long before = LlmMessageStore.accessCount()
        when:
        LlmResponse r = turn(p, conv) { it.system('be brief').user('hello there') }
        def reopened = LlmConversationImpl.load(ec, conv.conversationId, true)
        then:
        r.content == 'first answer'
        LlmMessageStore.accessCount() == before
        rowsOf(conv.conversationId) == 0
        reopened.usesItemModel()
        reopened.headRunId != null
        reopened.history.findAll { it.role != LlmMessage.Role.SYSTEM }*.role == [LlmMessage.Role.USER, LlmMessage.Role.ASSISTANT]
        reopened.history.findAll { it.role != LlmMessage.Role.SYSTEM }*.content == ['hello there', 'first answer']
        ec.entity.find('moqui.llm.LlmRun').condition('runId', reopened.headRunId).disableAuthz().one().statusId == 'LlmRunComplete'
        and: 'the instructions travel in their own field, not as a message of the trajectory'
        provider.requests[0].json.instructions == 'be brief'
        provider.requests[0].json.input*.role == ['user']
        cleanup: provider.close()
    }

    def "a second turn with a new client and a new ExecutionContext sends the first turn once and the new input once"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, answer('resp_a', 'first answer'))
        provider.enqueueJson(200, answer('resp_b', 'second answer'))
        def p = profile(provider.endpoint)
        def conv = LlmConversationImpl.create(ec, p.name, null)
        turn(p, conv) { it.system('be brief').user('first question') }
        long before = LlmMessageStore.accessCount()
        when:
        inNewEc(USERNAME) { ExecutionContext other ->
            def again = LlmConversationImpl.load(other, conv.conversationId, true)
            LlmClientImpl client = new LlmClientImpl(other, p, { false })
            client.conversation(again).system('be brief').user('second question')
            client.call()
        }
        def texts = provider.requests[1].json.input.collect { m -> (m.content ?: []).collect { it.text }.join('') }
        then:
        LlmMessageStore.accessCount() == before
        provider.requests[1].json.input*.role == ['user', 'assistant', 'user']
        texts == ['first question', 'first answer', 'second question']
        provider.requests[1].json.instructions == 'be brief'
        !provider.requests[1].json.containsKey('previous_response_id')
        rowsOf(conv.conversationId) == 0
        LlmConversationImpl.load(ec, conv.conversationId, true).history.findAll { it.role != LlmMessage.Role.SYSTEM }*.content ==
                ['first question', 'first answer', 'second question', 'second answer']
        cleanup: provider.close()
    }

    def "an image of an earlier turn is replayed byte for byte, not only the text that described it"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, answer('resp_i1', 'a tiny picture'))
        provider.enqueueJson(200, answer('resp_i2', 'still the same picture'))
        def p = profile(provider.endpoint)
        def conv = LlmConversationImpl.create(ec, p.name, null)
        String dataUri = 'data:image/png;base64,' + PNG
        when:
        turn(p, conv) { it.inputItems([LlmItem.message('user', [LlmContentPart.inputText('what is this?'), image(dataUri)])]) }
        turn(p, conv) { it.user('and now?') }
        def firstTurnParts = provider.requests[1].json.input[0].content
        def view = LlmConversationImpl.load(ec, conv.conversationId, true).history.find { it.role == LlmMessage.Role.USER }
        then:
        firstTurnParts*.type == ['input_text', 'input_image']
        firstTurnParts[1].image_url == dataUri
        provider.requests[1].json.input*.role == ['user', 'assistant', 'user']
        and: 'the view shows that there was an attachment, not its bytes'
        view.metadata.attachments[0].type == 'input_image'
        !LlmJson.toJson(view).contains(PNG)
        rowsOf(conv.conversationId) == 0
        cleanup: provider.close()
    }

    def "a tool loop keeps the call, its output and their ids, and writes no message row"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, toolCallResponse('resp_l1', 'call_77', 'lookup', '{"q":"alpha"}'))
        provider.enqueueJson(200, answer('resp_l2', 'alpha is found'))
        provider.enqueueJson(200, answer('resp_l3', 'done again'))
        def p = profile(provider.endpoint)
        def conv = LlmConversationImpl.create(ec, p.name, null)
        LlmTool lookup = new LlmTool() {
            String getName() { 'lookup' }
            String getDescription() { 'looks up' }
            Map<String, Object> getParametersSchema() { [type: 'object', properties: [q: [type: 'string']]] }
            LlmTool.Execution getExecution() { LlmTool.Execution.SERVER }
            Object execute(Map<String, Object> arguments, ExecutionContext x) { [found: arguments.q] }
        }
        long before = LlmMessageStore.accessCount()
        when:
        turn(p, conv) { it.tool(lookup).user('find alpha') }
        turn(p, conv) { it.tool(lookup).user('anything else?') }
        def second = provider.requests[1].json.input
        def third = provider.requests[2].json.input
        then:
        second*.type == ['message', 'function_call', 'function_call_output']
        second[1].call_id == 'call_77' && second[1].name == 'lookup'
        second[2].call_id == 'call_77'
        third*.type == ['message', 'function_call', 'function_call_output', 'message', 'message']
        third[1].call_id == 'call_77' && third[2].call_id == 'call_77'
        LlmMessageStore.accessCount() == before
        rowsOf(conv.conversationId) == 0
        def view = LlmConversationImpl.load(ec, conv.conversationId, true).history.findAll { it.role != LlmMessage.Role.SYSTEM }
        view*.role == [LlmMessage.Role.USER, LlmMessage.Role.ASSISTANT, LlmMessage.Role.TOOL, LlmMessage.Role.ASSISTANT, LlmMessage.Role.USER, LlmMessage.Role.ASSISTANT]
        view[1].toolCalls[0].id == 'call_77'
        view[2].toolCallId == 'call_77' && view[2].name == 'lookup'
        cleanup: provider.close()
    }

    def "a client tool yields, the answer resumes the same run once, and the trajectory carries the call and its output"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, toolCallResponse('resp_y1', 'call_ui', 'ask_form', '{"title":"Order"}'))
        provider.enqueueJson(200, answer('resp_y2', 'form received'))
        def p = profile(provider.endpoint)
        def conv = LlmConversationImpl.create(ec, p.name, null)
        LlmTool form = new LlmTool() {
            String getName() { 'ask_form' }
            String getDescription() { 'asks the person' }
            Map<String, Object> getParametersSchema() { [type: 'object', properties: [title: [type: 'string']]] }
            LlmTool.Execution getExecution() { LlmTool.Execution.CLIENT }
            Object execute(Map<String, Object> arguments, ExecutionContext x) { null }
        }
        long before = LlmMessageStore.accessCount()
        when:
        LlmResponse first = turn(p, conv) { it.tool(form).allowClientTools(true).user('order please') }
        def yielded = LlmConversationImpl.load(ec, conv.conversationId, true)
        def pendingIds = yielded.getPendingClientToolCalls()*.id
        LlmResponse second = turn(p, yielded) { it.tool(form).allowClientTools(true).toolResults([new LlmToolResult('call_ui', 'ask_form', [submitted: true, qty: 3])]) }
        def input = provider.requests[1].json.input
        then:
        first.yielded
        pendingIds == ['call_ui']
        second.content == 'form received'
        input*.type == ['message', 'function_call', 'function_call_output']
        input.count { it.type == 'function_call_output' } == 1
        input[2].call_id == 'call_ui'
        input[2].output == '{"submitted":true,"qty":3}'
        provider.requests.size() == 2
        LlmMessageStore.accessCount() == before
        rowsOf(conv.conversationId) == 0
        LlmConversationImpl.load(ec, conv.conversationId, true).history.findAll { it.role != LlmMessage.Role.SYSTEM }*.role ==
                [LlmMessage.Role.USER, LlmMessage.Role.ASSISTANT, LlmMessage.Role.TOOL, LlmMessage.Role.ASSISTANT]
        cleanup: provider.close()
    }

    def "opaque reasoning is replayed unchanged and never shown in the view"() {
        given:
        String secret = 'gAAAAABopaque-encrypted-reasoning-payload=='
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, '{"id":"resp_r1","object":"response","status":"completed","model":"gpt-test","output":[' +
                '{"id":"rs_1","type":"reasoning","summary":[{"type":"summary_text","text":"thought"}],"encrypted_content":"' + secret + '"},' +
                '{"id":"msg_r1","type":"message","role":"assistant","content":[{"type":"output_text","text":"answer one"}]}]}')
        provider.enqueueJson(200, answer('resp_r2', 'answer two'))
        def p = profile(provider.endpoint)
        def conv = LlmConversationImpl.create(ec, p.name, null)
        when:
        turn(p, conv) { it.user('think about it') }
        turn(p, conv) { it.user('and again') }
        def input = provider.requests[1].json.input
        def view = LlmConversationImpl.load(ec, conv.conversationId, true).history
        then:
        input*.type == ['message', 'reasoning', 'message', 'message']
        input[1].encrypted_content == secret
        input[1].id == 'rs_1'
        !LlmJson.toJson(view).contains(secret)
        cleanup: provider.close()
    }

    def "another user, another profile and another protocol cannot take over the conversation"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, answer('resp_o1', 'mine'))
        def p = profile(provider.endpoint)
        def conv = LlmConversationImpl.create(ec, p.name, null)
        turn(p, conv) { it.user('private') }
        boolean created = false
        boolean off = ec.artifactExecution.disableAuthz()
        try {
            ec.transaction.runUseOrBegin(null, 'other user') {
                ec.entity.makeValue('moqui.security.UserAccount').setAll([userId: 'LLMCONVOTHER', username: 'llm.conv.other', userFullName: 'Other']).createOrUpdate()
            }
        } finally { if (!off) ec.artifactExecution.enableAuthz() }
        when: 'another user loads it with authorization checks on'
        String refusal = null
        try {
            Object[] box = new Object[1]
            Thread t = Thread.start {
                ExecutionContext other = Moqui.getExecutionContext()
                try {
                    assert ((UserFacadeImpl) other.user).internalLoginUser('llm.conv.other', false)
                    try { LlmConversationImpl.load(other, conv.conversationId, true) } catch (LlmException e) { box[0] = e.message + ' ' + e.httpStatus }
                } finally { other.destroy() }
            }
            t.join(30000)
            refusal = box[0]
        } catch (Throwable ignored) { }
        then:
        refusal != null && refusal.contains('not authorized') && !refusal.contains('private')

        when: 'the same conversation is used with another profile'
        turn(profile(provider.endpoint, 'other-profile'), conv) { it.user('x') }
        then:
        LlmException wrongProfile = thrown()
        wrongProfile.message.contains('belongs to profile')

        when: 'the same conversation is used with a message protocol'
        def cc = LlmFacadeImpl.ProfileState.forTest(p.name, new FakeLlmProtocol(), 'test-model', false, 2, 0f, 5)
        new LlmClientImpl(ec, cc, { false }).conversation(LlmConversationImpl.load(ec, conv.conversationId, true))
        then:
        LlmException wrongModel = thrown()
        wrongModel.message.contains('uses the item model')
        cleanup: provider.close()
    }

    def "a Chat Completions conversation still keeps its transcript in the message rows"() {
        given:
        def proto = new FakeLlmProtocol()
        proto.results = [FakeLlmProtocol.stop('cc answer')]
        def cc = LlmFacadeImpl.ProfileState.forTest('cc-control', proto, 'test-model', false, 2, 0f, 5)
        def conv = LlmConversationImpl.create(ec, 'cc-control', null)
        long before = LlmMessageStore.accessCount()
        boolean off = ec.artifactExecution.disableAuthz()
        when:
        try { new LlmClientImpl(ec, cc, { false }).conversation(conv).user('cc question').call() } finally { if (!off) ec.artifactExecution.enableAuthz() }
        then:
        LlmMessageStore.accessCount() > before
        rowsOf(conv.conversationId) == 2
        !LlmConversationImpl.load(ec, conv.conversationId, true).usesItemModel()
        LlmConversationImpl.load(ec, conv.conversationId, true).history*.content == ['cc question', 'cc answer']
    }

    def "only the message store names the message entity"() {
        expect:
        List<String> offenders = []
        new File('src/main/groovy').eachFileRecurse { File f ->
            if ((f.name.endsWith('.java') || f.name.endsWith('.groovy')) && f.name != 'LlmMessageStore.java'
                    && f.text.contains('moqui.llm.LlmMessage"')) offenders << f.name
        }
        offenders.isEmpty()
    }

    def "a stored run context gives back the items it was written from, ids and opaque fields included"() {
        given:
        LlmItem call = LlmItem.functionCall('call_1', 'lookup', '{"q":"a"}')
        call.providerItemId = 'fc_1'
        LlmItem out = LlmItem.functionCallOutput('call_1', [[type: 'input_text', text: 'result']])
        LlmItem msg = LlmItem.message('user', [LlmContentPart.inputText('hi'), image('data:image/png;base64,' + PNG)])
        msg.providerItemId = 'msg_9'
        LlmItem reasoning = new LlmItem(type: 'reasoning', providerItemId: 'rs_1', encryptedContent: 'xyz==',
                summary: [LlmContentPart.inputText('s')], payload: [extension_field: [a: 1]])
        LlmItem context = LlmItem.message('user', [LlmContentPart.inputText('<untrusted-context source="s">c</untrusted-context>')])
        context.ephemeral = Boolean.TRUE
        List<LlmItem> original = [call, out, msg, reasoning, context]
        when:
        List<LlmItem> back = OpenResponsesCodec.itemsFromStored(LlmJson.toObject(LlmJson.toJson(original)))
        then:
        back.collect { OpenResponsesCodec.itemToMap(it) } == original.collect { OpenResponsesCodec.itemToMap(it) }
        back[0].callId == 'call_1' && back[0].providerItemId == 'fc_1'
        back[3].encryptedContent == 'xyz==' && back[3].payload.extension_field == [a: 1]
        back[4].ephemeral == Boolean.TRUE
    }

    def "a window policy of the message model does not shorten the replayed trajectory"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, answer('resp_w1', 'an answer long enough to be cut by any message window'))
        provider.enqueueJson(200, answer('resp_w2', 'second'))
        def p = profile(provider.endpoint)
        def conv = LlmConversationImpl.create(ec, p.name, null)
        WindowPolicy tiny = new WindowPolicy()
        tiny.maxChars = 10
        when:
        turn(p, conv) { it.windowPolicy(tiny).user('a question that is also quite long') }
        turn(p, conv) { it.windowPolicy(tiny).user('and a follow up') }
        then:
        provider.requests[1].json.input*.role == ['user', 'assistant', 'user']
        provider.requests[1].json.input[0].content[0].text == 'a question that is also quite long'
        cleanup: provider.close()
    }

    def "a remote chain continues from the head and a chain that is not the conversation's is refused"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, answer('resp_c1', 'one'))
        provider.enqueueJson(200, answer('resp_c2', 'two'))
        def p = profile(provider.endpoint)
        def conv = LlmConversationImpl.create(ec, p.name, null)
        when: 'the first turn starts a remote chain'
        turn(p, conv) { it.previousResponse('resp_external').user('start') }
        and: 'the next turn names nothing and continues the chain with only the new input'
        turn(p, conv) { it.user('next') }
        then:
        provider.requests[0].json.previous_response_id == 'resp_external'
        provider.requests[1].json.previous_response_id == 'resp_c1'
        provider.requests[1].json.input*.role == ['user']
        provider.requests[1].json.input[0].content[0].text == 'next'

        when: 'a turn names another chain'
        turn(p, conv) { it.previousResponse('resp_somewhere_else').user('hijack') }
        then:
        LlmException refused = thrown()
        refused.message.contains('does not continue conversation')
        provider.requests.size() == 2
        cleanup: provider.close()
    }

    def "a local conversation refuses a foreign previous_response_id instead of mixing two histories"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, answer('resp_m1', 'one'))
        def p = profile(provider.endpoint)
        def conv = LlmConversationImpl.create(ec, p.name, null)
        turn(p, conv) { it.user('local first') }
        when:
        turn(p, conv) { it.previousResponse('resp_foreign').user('mix') }
        then:
        LlmException refused = thrown()
        refused.message.contains('does not continue conversation')
        provider.requests.size() == 1
        cleanup: provider.close()
    }

    def "a turn that failed is not the head: the next turn continues from the last completed one"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, answer('resp_f1', 'good'))
        provider.enqueueJson(400, '{"error":{"message":"bad request","type":"invalid_request_error"}}')
        provider.enqueueJson(200, answer('resp_f3', 'after the failure'))
        def p = profile(provider.endpoint)
        def conv = LlmConversationImpl.create(ec, p.name, null)
        turn(p, conv) { it.user('first') }
        String headAfterFirst = LlmConversationImpl.load(ec, conv.conversationId, true).headRunId
        when:
        try { turn(p, conv) { it.user('this one fails') } } catch (LlmException expected) { }
        def afterFailure = LlmConversationImpl.load(ec, conv.conversationId, true)
        String headAfterFailure = afterFailure.headRunId
        turn(p, afterFailure) { it.user('third') }
        def last = provider.requests[2].json.input.collect { m -> (m.content ?: []).collect { it.text }.join('') }
        then:
        headAfterFailure == headAfterFirst
        last == ['first', 'good', 'third']
        cleanup: provider.close()
    }

    def "a recovered turn completes into its conversation and becomes the head"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, answer('resp_rec', 'recovered answer'))
        def p = profile(provider.endpoint)
        def conv = LlmConversationImpl.create(ec, p.name, null)
        LlmClientImpl lost = new LlmClientImpl(ec, p, { false })
        lost.conversation(conv).user('please answer')
        boolean was = ec.artifactExecution.disableAuthz()
        String runId
        try {
            lost.beginDurableRun(lost.buildWindow(), false)
            runId = lost.activeRunId
            lost.stopLease()
        } finally { if (!was) ec.artifactExecution.enableAuthz() }
        def stored = LlmRunStore.getRun(ec, runId)
        LlmClientImpl rescuer = new LlmClientImpl(ec, p, { false })
        when:
        rescuer.attachRecoveredRun(stored)
        rescuer.inputItems(OpenResponsesCodec.itemsFromStored(stored.context))
        was = ec.artifactExecution.disableAuthz()
        try { rescuer.call() } finally { if (!was) ec.artifactExecution.enableAuthz() }
        def reopened = LlmConversationImpl.load(ec, conv.conversationId, true)
        then:
        reopened.headRunId == runId
        reopened.history*.content == ['please answer', 'recovered answer']
        provider.requests.size() == 1
        provider.requests[0].json.input.size() == 1
        cleanup: provider.close()
    }

    def "the assistant item of an earlier turn goes back with the provider's id and without what only a response carries"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, '{"id":"resp_p1","object":"response","status":"completed","model":"gpt-test","output":[' +
                '{"id":"rs_p1","type":"reasoning","summary":[{"type":"summary_text","text":"why"}],"encrypted_content":"BLOB","status":"completed"},' +
                '{"id":"msg_p1","type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"first","annotations":[],"logprobs":[]}]}]}')
        provider.enqueueJson(200, answer('resp_p2', 'second'))
        def p = profile(provider.endpoint, 'responses-conv-proj')
        def conv = LlmConversationImpl.create(ec, p.name, null)
        when:
        turn(p, conv) { it.user('q1') }
        turn(p, conv) { it.user('q2') }
        def input = provider.requests[1].json.input
        def assistant = input.find { it.type == 'message' && it.role == 'assistant' }
        def reasoning = input.find { it.type == 'reasoning' }
        then:
        assistant.id == 'msg_p1'
        assistant.content[0].keySet() == ['type', 'text', 'annotations'] as Set
        reasoning.id == 'rs_p1'
        reasoning.encrypted_content == 'BLOB'
        reasoning.summary[0].text == 'why'
        !reasoning.containsKey('status')
        and: 'what is stored is the response as it came'
        ec.entity.find('moqui.llm.LlmContent').condition('contentType', 'output_text').disableAuthz().list().any { it.textContent == 'first' }
        cleanup: provider.close()
    }
}
