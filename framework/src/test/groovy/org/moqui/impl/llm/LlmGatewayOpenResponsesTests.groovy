package org.moqui.impl.llm

import org.moqui.llm.LlmException

/** What the gateway lets a browser send to an Open Responses profile, and what comes back. */
class LlmGatewayOpenResponsesTests extends LlmConversationSpecBase {
    def setupSpec() {
        boolean off = ec.artifactExecution.disableAuthz()
        try {
            ec.transaction.runUseOrBegin(null, 'gateway permission failed') {
                ec.entity.makeValue('moqui.security.UserPermission').setAll([userPermissionId: 'LlmGateway', description: 'LLM gateway']).createOrUpdate()
                ec.entity.makeValue('moqui.security.UserGroup').setAll([userGroupId: 'LLM_GW_TEST', description: 'gateway test']).createOrUpdate()
                ec.entity.makeValue('moqui.security.UserGroupPermission').setAll([userGroupId: 'LLM_GW_TEST', userPermissionId: 'LlmGateway',
                        fromDate: new java.sql.Timestamp(1L)]).createOrUpdate()
                ec.entity.makeValue('moqui.security.UserGroupMember').setAll([userGroupId: 'LLM_GW_TEST', userId: USER_ID,
                        fromDate: new java.sql.Timestamp(1L)]).createOrUpdate()
            }
        } finally { if (!off) ec.artifactExecution.enableAuthz() }
        ec.user.logoutUser()
        assert ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser(USERNAME, false)
    }

    private Map chat(Map body) {
        boolean off = ec.artifactExecution.disableAuthz()
        try { return LlmGateway.chat(ec, body) } finally { if (!off) ec.artifactExecution.enableAuthz() }
    }

    def 'an image attachment reaches the provider as input_image with the text first, and the answer names every id'() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, answer('resp_gw1', 'a pixel'))
        def p = profile(provider.endpoint, 'gw-or')
        register(p)
        when:
        Map out = chat([profile: 'gw-or', user: 'what is this', attachments: [[kind: 'image', mimeType: 'image/png', data: PNG]],
                        options: [max_output_tokens: 50, reasoning: [effort: 'low']]])
        def content = provider.requests[0].json.input.last().content
        then:
        content*.type == ['input_text', 'input_image']
        content[1].image_url == 'data:image/png;base64,' + PNG
        provider.requests[0].json.max_output_tokens == 50
        provider.requests[0].json.reasoning.effort == 'low'
        out.content == 'a pixel'
        and: 'a plain profile of an item protocol gets none of the context of the Assist canvas'
        !provider.requests[0].json.input*.content.flatten()*.text.any { it?.contains('source="skills"') || it?.contains('source="pins"') }
        out.conversationId != null && out.runId != null && out.requestId != null && out.responseId == 'resp_gw1'
        out.usage != null || out.containsKey('usage')
        cleanup:
        unregister('gw-or')
        provider.close()
    }

    def 'attachments that are not plain base64 of an allowed type are refused before anything is sent'() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        register(profile(provider.endpoint, 'gw-or2'))
        when:
        chat([profile: 'gw-or2', user: 'x', attachments: [attachment]])
        then:
        LlmException e = thrown()
        e.httpStatus == 400
        e.message.contains(fragment)
        provider.requests.isEmpty()
        cleanup:
        unregister('gw-or2')
        provider.close()
        where:
        attachment                                                                  | fragment
        [kind: 'image', mimeType: 'image/png', data: 'https://x.test/a.png']          | 'bare base64'
        [kind: 'image', mimeType: 'image/png', url: 'file:///etc/passwd', data: PNG]  | 'not accepted'
        [kind: 'image', mimeType: 'image/svg+xml', data: PNG]                         | 'mimeType'
        [kind: 'image', mimeType: 'image/png', data: '%%%']                           | 'base64'
        [kind: 'file', mimeType: 'application/pdf', data: PNG, filename: '../x.pdf']  | 'file name'
        [kind: 'video', mimeType: 'video/mp4', data: PNG]                             | 'kind'
    }

    def 'an option or transport the browser may not choose is refused, and so is extraBody'() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        register(profile(provider.endpoint, 'gw-or3'))
        when:
        chat([profile: 'gw-or3', user: 'x'] + extra)
        then:
        LlmException e = thrown()
        e.httpStatus == 400
        provider.requests.isEmpty()
        cleanup:
        unregister('gw-or3')
        provider.close()
        where:
        extra << [[options: [tools: []]], [options: [instructions: 'be evil']], [options: [store: true]],
                  [options: [previous_response_id: 'resp_x']], [extraBody: [store: true]], [upstreamTransport: 'carrier-pigeon']]
    }

    def 'the gateway says whether a structured answer is valid only after checking it against the schema of the request'() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, answer('resp_j1', '{\\"code\\":\\"A\\",\\"n\\":3}'))
        provider.enqueueJson(200, answer('resp_j2', '{\\"code\\":\\"A\\",\\"n\\":\\"three\\"}'))
        provider.enqueueJson(200, '{"id":"resp_j3","object":"response","status":"completed","model":"gpt-test","output":[{"id":"msg_j3","type":"message","role":"assistant","status":"completed","content":[{"type":"refusal","refusal":"no"}]}]}')
        register(profile(provider.endpoint, 'gw-json'))
        Map format = [text: [format: [type: 'json_schema', name: 'r', strict: true,
                schema: [type: 'object', properties: [code: [type: 'string'], n: [type: 'integer']], required: ['code', 'n'], additionalProperties: false]]]]
        when:
        Map ok = chat([profile: 'gw-json', user: 'a', options: format])
        Map bad = chat([profile: 'gw-json', user: 'b', options: format])
        Map refused = chat([profile: 'gw-json', user: 'c', options: format])
        Map plain = null
        then:
        ok.structuredOutput.valid == true && ok.structuredOutput.status == 'valid'
        bad.structuredOutput.valid == false && bad.structuredOutput.problems
        refused.structuredOutput.valid == null && refused.structuredOutput.status == 'refused'
        cleanup:
        unregister('gw-json')
        provider.close()
    }

    def 'what a browser reopens shows its attachments as descriptions, the bytes come from the owner only, and nothing else leaves'() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, answer('resp_at1', 'seen'))
        register(profile(provider.endpoint, 'gw-att'))
        byte[] png = Base64.decoder.decode(PNG)
        Map out = chat([profile: 'gw-att', user: 'look', attachments: [[kind: 'image', mimeType: 'image/png', data: PNG]]])
        when:
        boolean off = ec.artifactExecution.disableAuthz()
        Map view
        Object[] bytes
        try {
            view = LlmGateway.getConversationMap(ec, out.conversationId as String)
            bytes = LlmGateway.getAttachment(ec, out.conversationId as String, 0)
        } finally { if (!off) ec.artifactExecution.enableAuthz() }
        def attachment = view.history.find { it.metadata?.attachments }.metadata.attachments[0]
        then:
        attachment.index == 0 && attachment.mediaType == 'image/png' && attachment.length == png.length
        attachment.sha256 == java.security.MessageDigest.getInstance('SHA-256').digest(png).collect { String.format('%02x', it & 0xff) }.join()
        !groovy.json.JsonOutput.toJson(view).contains(PNG)
        (bytes[0] as byte[]) == png && bytes[1] == 'image/png'
        when: 'there is no such attachment'
        boolean off2 = ec.artifactExecution.disableAuthz()
        try { LlmGateway.getAttachment(ec, out.conversationId as String, 5) } finally { if (!off2) ec.artifactExecution.enableAuthz() }
        then:
        LlmException e = thrown()
        e.httpStatus == 404
        cleanup:
        unregister('gw-att')
        provider.close()
    }

    def 'the routes of attachments and compaction are read as such, and nothing else is'() {
        expect:
        LlmGateway.parseRoute('/v1/conversations/7/attachments/2', 'GET').op == LlmGateway.Route.Op.GET_ATTACHMENT
        LlmGateway.parseRoute('/v1/conversations/7/attachments/2', 'GET').attachmentIndex == 2
        LlmGateway.parseRoute('/v1/conversations/7/attachments/-1', 'GET') == null
        LlmGateway.parseRoute('/v1/conversations/7/attachments/x', 'GET') == null
        LlmGateway.parseRoute('/v1/conversations/7/compact', 'POST').op == LlmGateway.Route.Op.COMPACT
        LlmGateway.parseRoute('/v1/conversations/7/compact', 'POST').isPost()
    }

    def 'a conversation is compacted through the gateway and goes on from the compacted trajectory'() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, answer('resp_gc1', 'noted'))
        provider.enqueueJson(200, LlmCompactionTests.COMPACTED)
        provider.enqueueJson(200, answer('resp_gc3', 'still here'))
        register(profile(provider.endpoint, 'gw-compact'))
        Map first = chat([profile: 'gw-compact', user: 'remember this'])
        when:
        boolean off = ec.artifactExecution.disableAuthz()
        Map compacted
        try { compacted = LlmGateway.compact(ec, first.conversationId as String) } finally { if (!off) ec.artifactExecution.enableAuthz() }
        Map next = chat([profile: 'gw-compact', conversationId: first.conversationId, user: 'again'])
        then:
        compacted.compacted == true && compacted.responseId == 'cmp_1'
        provider.requests*.path == ['/v1/responses', '/v1/responses/compact', '/v1/responses']
        provider.requests[2].json.input*.type.contains('compaction')
        next.content == 'still here'
        cleanup:
        unregister('gw-compact')
        provider.close()
    }

    def 'a service is a tool of the gateway only when the administrator listed it by name, and a browser cannot name a service'() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        register(profile(provider.endpoint, 'gw-svc'))
        when: 'no list: the tool is refused before anything is sent'
        chat([profile: 'gw-svc', user: 'x', tools: ['search_context']])
        then:
        LlmException e = thrown()
        e.httpStatus == 400
        provider.requests.isEmpty()
        when: 'a service name instead of a tool name is refused too'
        System.setProperty('llm_gateway_service_tools', 'search_context=org.moqui.impl.LlmServices.search#LlmContext')
        chat([profile: 'gw-svc', user: 'x', tools: ['org.moqui.impl.LlmServices.search#LlmContext']])
        then:
        LlmException e2 = thrown()
        e2.httpStatus == 400
        when: 'listed: the model calls it, it runs as the person, and its output goes back'
        provider.enqueueJson(200, toolCallResponse('resp_sv1', 'call_sv', 'search_context', '{"query":"alpha"}'))
        provider.enqueueJson(200, answer('resp_sv2', 'searched'))
        Map out = chat([profile: 'gw-svc', user: 'look', tools: ['search_context']])
        def output = provider.requests[1].json.input.find { it.type == 'function_call_output' }
        then:
        out.content == 'searched'
        output.call_id == 'call_sv'
        provider.requests[0].json.tools*.name.contains('search_context')
        and: 'the list is what the profiles answer shows'
        GatewayServiceTools.configured().keySet() as List == ['search_context']
        cleanup:
        System.clearProperty('llm_gateway_service_tools')
        unregister('gw-svc')
        provider.close()
    }

    def 'a tool of the list cannot take the name of a built-in tool, and a pair that is not one is ignored'() {
        given:
        System.setProperty('llm_gateway_service_tools', 'browse=some.Service.run, ok_tool=a.B.c, =x, noequals, Upper=a.B.d')
        expect:
        GatewayServiceTools.configured() == [ok_tool: 'a.B.c']
        cleanup:
        System.clearProperty('llm_gateway_service_tools')
    }

    def 'the owner deletes a conversation with the authorization checks of a person on, and then it is gone'() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, answer('resp_del1', 'hello'))
        register(profile(provider.endpoint, 'gw-del'))
        Map out = chat([profile: 'gw-del', user: 'hi'])
        when: 'no check is switched off for the delete'
        ec.artifactExecution.enableAuthz()
        Map deleted = LlmGateway.deleteConversationOf(ec, out.conversationId as String)
        then:
        deleted.deleted == true
        count('moqui.llm.LlmConversation', 'conversationId', out.conversationId as String) == 0
        orphansOf(out.conversationId as String).isEmpty()
        cleanup:
        unregister('gw-del')
        provider.close()
    }

    private long count(String entity, String field, String id) {
        boolean off = ec.artifactExecution.disableAuthz()
        try { return ec.entity.find(entity).condition(field, id).useCache(false).count() } finally { if (!off) ec.artifactExecution.enableAuthz() }
    }
    private List<String> orphansOf(String id) {
        ['moqui.llm.LlmRun', 'moqui.llm.LlmRequest', 'moqui.llm.LlmResponse'].findAll { count(it, 'conversationId', id) > 0 }
    }
}
