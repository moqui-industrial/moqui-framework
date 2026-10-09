package org.moqui.impl.llm

import org.moqui.context.ExecutionContext
import org.moqui.llm.LlmException
import org.moqui.llm.LlmItem
import org.moqui.llm.LlmContentPart

/** The provider operations beyond create and compact (OpenAI extensions): offered by capability, scoped to the owner, paged. */
class LlmProviderOperationsTests extends LlmConversationSpecBase {

    private Map ownResponse(provider, LlmFacadeImpl.ProfileState p) {
        provider.enqueueJson(200, answer('resp_own', 'mine'))
        def conv = LlmConversationImpl.create(ec, p.name, null)
        turn(p, conv) { it.user('a question') }
        [conv: conv]
    }
    private <T> T asOwner(Closure<T> work) {
        boolean off = ec.artifactExecution.disableAuthz()
        try { return work.call() } finally { if (!off) ec.artifactExecution.enableAuthz() }
    }

    def "a provider that lacks an operation is not offered it, whatever the caller asks"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        def standard = profile(provider.endpoint, 'ops-standard', new OpenResponsesProtocol())
        register(standard)
        when:
        asOwner { call.call() }
        then:
        LlmException e = thrown()
        e.message.contains('does not support')
        provider.requests.isEmpty()
        cleanup: unregister('ops-standard'); provider.close()
        where:
        call << [{ LlmResponsesOperations.retrieve(ec, 'ops-standard', 'resp_x') },
                 { LlmResponsesOperations.cancel(ec, 'ops-standard', 'resp_x') },
                 { LlmResponsesOperations.inputItems(ec, 'ops-standard', 'resp_x') },
                 { LlmResponsesOperations.inputTokens(ec, 'ops-standard', [LlmItem.message('user', [LlmContentPart.inputText('x')])], null) }]
    }

    def "retrieve and cancel work on the response of the owner and never reach the provider for anyone else's"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        def p = profile(provider.endpoint, 'ops-openai', new OpenAiResponsesProtocol())
        register(p)
        ownResponse(provider, p)
        boolean off = ec.artifactExecution.disableAuthz()
        try {
            ec.transaction.runUseOrBegin(null, 'other') {
                ec.entity.makeValue('moqui.security.UserAccount').setAll([userId: 'LLMOPSOTHER', username: 'llm.ops.other', userFullName: 'Other']).createOrUpdate()
            }
        } finally { if (!off) ec.artifactExecution.enableAuthz() }
        provider.enqueueJson(200, answer('resp_own', 'as stored'))
        provider.enqueueJson(200, '{"id":"resp_own","object":"response","status":"cancelled","model":"gpt-test","output":[]}')
        when:
        Map got = asOwner { LlmResponsesOperations.retrieve(ec, 'ops-openai', 'resp_own') }
        Map cancelled = asOwner { LlmResponsesOperations.cancel(ec, 'ops-openai', 'resp_own') }
        then:
        got.responseId == 'resp_own' && got.content == 'as stored'
        cancelled.status == 'cancelled'
        provider.requests*.path.takeRight(2) == ['/v1/responses/resp_own', '/v1/responses/resp_own/cancel']
        provider.requests*.method.takeRight(2) == ['GET', 'POST']

        when: 'another user names the same response, or one that does not exist'
        int before = provider.requests.size()
        List refusals = []
        ['resp_own', 'resp_nobody'].each { String id ->
            try {
                inNewEc('llm.ops.other') { ExecutionContext other -> LlmResponsesOperations.retrieve(other, 'ops-openai', id) }
            } catch (Throwable t) { refusals << t.message }
        }
        then:
        refusals.size() == 2 && refusals.every { it.contains('LLM response not found') }
        provider.requests.size() == before
        cleanup: unregister('ops-openai'); provider.close()
    }

    def "input items are paged with the options the provider has, checked before a request is made"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        def p = profile(provider.endpoint, 'ops-openai-pages', new OpenAiResponsesProtocol())
        register(p)
        ownResponse(provider, p)
        provider.enqueueJson(200, '{"object":"list","data":[{"id":"item_1"}],"first_id":"item_1","last_id":"item_1","has_more":true}')
        when:
        Map page = asOwner { LlmResponsesOperations.inputItems(ec, 'ops-openai-pages', 'resp_own', [limit: 1, order: 'desc', after: 'item_0']) }
        then:
        page.has_more == true && page.last_id == 'item_1' && page.data*.id == ['item_1']
        provider.requests.last().method == 'GET'
        provider.requests.last().path == '/v1/responses/resp_own/input_items'

        when:
        int before = provider.requests.size()
        asOwner { LlmResponsesOperations.inputItems(ec, 'ops-openai-pages', 'resp_own', paging) }
        then:
        thrown(LlmException)
        provider.requests.size() == before
        cleanup: unregister('ops-openai-pages'); provider.close()
        where:
        paging << [[limit: 0], [limit: 101], [limit: 1.5d], [order: 'sideways'], [after: 'a&b=c'], [after: ''], [include: 'x']]
    }

    def "the paging options reach the provider in the query"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        // the scripted provider keeps the path; the query is what the real client sent in the request line
        String seen = null
        provider.server.createContext('/v1/responses/resp_q/input_items') { exchange ->
            seen = exchange.requestURI.rawQuery
            byte[] bytes = '{"object":"list","data":[],"has_more":false}'.getBytes('UTF-8')
            exchange.responseHeaders.set('Content-Type', 'application/json')
            exchange.sendResponseHeaders(200, bytes.length)
            exchange.responseBody.withCloseable { it.write(bytes) }
        }
        def p = profile(provider.endpoint, 'ops-openai-query', new OpenAiResponsesProtocol())
        register(p)
        provider.enqueueJson(200, answer('resp_q', 'x'))
        def conv = LlmConversationImpl.create(ec, p.name, null)
        turn(p, conv) { it.user('q') }
        when:
        asOwner { LlmResponsesOperations.inputItems(ec, 'ops-openai-query', 'resp_q', [limit: 5, order: 'asc', after: 'item_9']) }
        then:
        seen == 'limit=5&order=asc&after=item_9'
        cleanup: unregister('ops-openai-query'); provider.close()
    }

    def "counting tokens sends the request body that a create would send"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        def p = profile(provider.endpoint, 'ops-openai-count', new OpenAiResponsesProtocol())
        register(p)
        provider.enqueueJson(200, '{"object":"response.input_tokens","input_tokens":42}')
        when:
        Map out = asOwner { LlmResponsesOperations.inputTokens(ec, 'ops-openai-count', [LlmItem.message('user', [LlmContentPart.inputText('how long is this')])], null) }
        then:
        out.input_tokens == 42
        provider.requests[0].path == '/v1/responses/input_tokens'
        provider.requests[0].json.input[0].content[0].text == 'how long is this'
        provider.requests[0].json.model == 'gpt-test'
        cleanup: unregister('ops-openai-count'); provider.close()
    }
}
