package org.moqui.impl.llm

import org.moqui.llm.LlmCompactResult
import org.moqui.llm.LlmException
import org.moqui.llm.LlmMessage

/** Compaction of a conversation: the real compact request is kept as such, and the head moves only when it succeeded. */
class LlmCompactionTests extends LlmConversationSpecBase {
    static final String COMPACTED = '{"id":"cmp_1","object":"response.compaction","created_at":1760000000,"output":[' +
            '{"id":"msg_keep","type":"message","role":"user","content":[{"type":"input_text","text":"first question"}]},' +
            '{"id":"cmp_item","type":"compaction","encrypted_content":"opaque-compaction-state"}],' +
            '"usage":{"input_tokens":120,"output_tokens":20,"total_tokens":140}}'

    private LlmCompactResult compactNow(LlmFacadeImpl.ProfileState p, LlmConversationImpl conv) {
        LlmClientImpl client = new LlmClientImpl(ec, p, { false })
        client.conversation(conv)
        boolean off = ec.artifactExecution.disableAuthz()
        try { return client.compact() } finally { if (!off) ec.artifactExecution.enableAuthz() }
    }
    private List requests(String conversationId) {
        ec.entity.find('moqui.llm.LlmRequest').condition('conversationId', conversationId).orderBy('createdDate').orderBy('llmRequestId').disableAuthz().useCache(false).list()
    }

    def "a compaction is stored as a compaction, with its response, and the compacted trajectory becomes the head"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, answer('resp_k1', 'first answer'))
        provider.enqueueJson(200, answer('resp_k2', 'second answer'))
        provider.enqueueJson(200, COMPACTED)
        provider.enqueueJson(200, answer('resp_k3', 'after compaction'))
        def p = profile(provider.endpoint)
        def conv = LlmConversationImpl.create(ec, p.name, null)
        turn(p, conv) { it.system('be brief').user('first question') }
        turn(p, conv) { it.user('second question') }
        String headBefore = LlmConversationImpl.load(ec, conv.conversationId, true).headRunId
        when:
        LlmCompactResult result = compactNow(p, LlmConversationImpl.load(ec, conv.conversationId, true))
        def after = LlmConversationImpl.load(ec, conv.conversationId, true)
        def stored = requests(conv.conversationId)
        def compactRequest = stored.find { it.operation == 'compact_response' }
        def compactResponse = ec.entity.find('moqui.llm.LlmResponse').condition('llmRequestId', compactRequest.llmRequestId).disableAuthz().one()
        then: 'the provider got the compact endpoint with the compact body: the whole trajectory, the instructions, no create-only field'
        result.id == 'cmp_1' && result.output*.type == ['message', 'compaction']
        provider.requests[2].path == '/v1/responses/compact'
        ['model', 'input', 'instructions', 'previous_response_id', 'prompt_cache_key'].containsAll(provider.requests[2].json.keySet())
        provider.requests[2].json.instructions == 'be brief'
        provider.requests[2].json.input*.role.findAll { it } == ['user', 'assistant', 'user', 'assistant']
        and: 'it is kept as a compaction, not as a create, with the body that was sent and the response it got'
        stored*.operation.count('create_response') == 2 && stored*.operation.count('compact_response') == 1
        compactRequest.requestPayloadJson == provider.requests[2].body
        compactRequest.localStatusEnumId == 'LlmReqAck'
        compactResponse.providerResponseId == 'cmp_1' && compactResponse.objectType == 'response.compaction'
        ec.entity.find('moqui.llm.LlmItem').condition('llmResponseId', compactResponse.llmResponseId).disableAuthz().list()*.itemType.sort() == ['compaction', 'message']
        and: 'the head is the compacted trajectory and the next turn continues from it'
        after.headRunId != headBefore
        after.status == LlmConversationImpl.STATUS_COMPLETE
        when:
        turn(p, after) { it.user('and now') }
        then:
        provider.requests[3].json.input*.type == ['message', 'compaction', 'message']
        provider.requests[3].json.input[1].encrypted_content == 'opaque-compaction-state'
        provider.requests[3].json.input[0].content[0].text == 'first question'
        and: 'the opaque state is not shown'
        !LlmJson.toJson(LlmConversationImpl.load(ec, conv.conversationId, true).history).contains('opaque-compaction-state')
        cleanup: provider.close()
    }

    def "a compaction that fails leaves the head where it was and the request marked failed"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, answer('resp_f1', 'one'))
        provider.enqueueJson(500, '{"error":{"message":"compaction is down"}}')
        provider.enqueueJson(200, answer('resp_f2', 'two'))
        def p = profile(provider.endpoint)
        def conv = LlmConversationImpl.create(ec, p.name, null)
        turn(p, conv) { it.user('first question') }
        String head = LlmConversationImpl.load(ec, conv.conversationId, true).headRunId
        when:
        compactNow(p, LlmConversationImpl.load(ec, conv.conversationId, true))
        then:
        thrown(LlmException)
        when:
        def after = LlmConversationImpl.load(ec, conv.conversationId, true)
        def compactRequest = requests(conv.conversationId).find { it.operation == 'compact_response' }
        String headAfter = after.headRunId
        turn(p, after) { it.user('second question') }
        then: 'the head did not move, the failed request says so, and the conversation is usable'
        headAfter == head
        compactRequest != null && compactRequest.localStatusEnumId in ['LlmReqFailed', 'LlmReqUncertain']
        after.status == LlmConversationImpl.STATUS_COMPLETE
        provider.requests[2].json.input*.role == ['user', 'assistant', 'user']
        ec.entity.find('moqui.llm.LlmResponse').condition('llmRequestId', compactRequest.llmRequestId).disableAuthz().count() == 0
        cleanup: provider.close()
    }

    def "compaction of a conversation with nothing in it is refused without a request"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        def p = profile(provider.endpoint)
        def conv = LlmConversationImpl.create(ec, p.name, null)
        when: compactNow(p, conv)
        then:
        LlmException e = thrown()
        e.message.contains('nothing to compact') || e.message.contains('no trajectory')
        provider.requests.isEmpty()
        LlmConversationImpl.load(ec, conv.conversationId, true).status == LlmConversationImpl.STATUS_ACTIVE
        cleanup: provider.close()
    }

    def "a compaction while a turn is working on the same conversation is refused"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, answer('resp_w1', 'one'))
        def p = profile(provider.endpoint)
        def conv = LlmConversationImpl.create(ec, p.name, null)
        turn(p, conv) { it.user('first question') }
        boolean off = ec.artifactExecution.disableAuthz()
        try {
            // a turn that began and has not ended holds the conversation
            ec.entity.find('moqui.llm.LlmConversation').condition('conversationId', conv.conversationId).updateAll([statusId: 'LlmcsYielded'] as Map<String, Object>)
        } finally { if (!off) ec.artifactExecution.enableAuthz() }
        when: compactNow(p, LlmConversationImpl.load(ec, conv.conversationId, true))
        then:
        LlmException e = thrown()
        e.httpStatus == 409
        provider.requests.size() == 1
        cleanup: provider.close()
    }
}
