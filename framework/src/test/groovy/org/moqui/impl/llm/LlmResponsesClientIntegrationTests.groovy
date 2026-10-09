package org.moqui.impl.llm

import com.sun.net.httpserver.HttpServer
import groovy.json.JsonSlurper
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition
import org.moqui.impl.context.UserFacadeImpl
import org.moqui.llm.LlmCompactResult
import org.moqui.llm.LlmContentPart
import org.moqui.llm.LlmException
import org.moqui.llm.LlmFinishReason
import org.moqui.llm.LlmItem
import org.moqui.llm.LlmResponse
import org.moqui.llm.LlmResponseOptions
import org.moqui.llm.LlmStreamListener
import org.moqui.llm.LlmTool
import org.moqui.llm.LlmToolCall
import org.moqui.llm.LlmProtocol
import org.moqui.llm.LlmTransport
import org.moqui.llm.WindowPolicy
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification

import java.security.MessageDigest
import java.sql.Timestamp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LlmResponsesClientIntegrationTests extends Specification {
    static final String USER_ID = 'LLMHTTPTEST'
    static final String USERNAME = 'llm.http.test'

    @Shared ExecutionContext ec

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        boolean disabled = ec.artifactExecution.disableAuthz()
        try {
            ec.transaction.runUseOrBegin(null, 'LLM HTTP test user create failed') {
                ec.entity.makeValue('moqui.security.UserAccount')
                        .setAll([userId: USER_ID, username: USERNAME, userFullName: 'LLM HTTP Test'])
                        .createOrUpdate()
                ensureEnum('LlmRunStatus', 'LLM Run Status')
                ensureEnum('LlmContinuationMode', 'LLM Continuation Mode')
                ensureEnum('LlmToolInvocationStatus', 'LLM Tool Invocation Status')
                ensureEnum('LlmConversationStatus', 'LLM Conversation Status')
                ensureEnum('LlmRequestStatus', 'LLM Request Status')
                ensureEnum('LlmContentPurpose', 'LLM Content Purpose')
                ensureEnum('LlmTransport', 'LLM Transport')
                [LlmRunQueued: 'LlmRunStatus', LlmRunRunning: 'LlmRunStatus',
                 LlmRunWaitClient: 'LlmRunStatus', LlmRunWaitConfirm: 'LlmRunStatus',
                 LlmRunRecovering: 'LlmRunStatus', LlmRunComplete: 'LlmRunStatus',
                 LlmRunFailed: 'LlmRunStatus', LlmRunCancelled: 'LlmRunStatus',
                 LlmContLocal: 'LlmContinuationMode', LlmContRemote: 'LlmContinuationMode',
                 LlmTiPlanned: 'LlmToolInvocationStatus', LlmTiRunning: 'LlmToolInvocationStatus',
                 LlmTiComplete: 'LlmToolInvocationStatus', LlmTiFailed: 'LlmToolInvocationStatus',
                 LlmTiUncertain: 'LlmToolInvocationStatus', LlmTiCancelled: 'LlmToolInvocationStatus',
                 LlmcsActive: 'LlmConversationStatus', LlmcsStreaming: 'LlmConversationStatus',
                 LlmcsYielded: 'LlmConversationStatus', LlmcsComplete: 'LlmConversationStatus',
                 LlmcsFailed: 'LlmConversationStatus', LlmcsCancelled: 'LlmConversationStatus',
                 LlmReqPrepared: 'LlmRequestStatus', LlmReqSending: 'LlmRequestStatus',
                 LlmReqAck: 'LlmRequestStatus', LlmReqFailed: 'LlmRequestStatus',
                 LlmReqUncertain: 'LlmRequestStatus', LlmCpUser: 'LlmContentPurpose',
                 LlmCpAssistant: 'LlmContentPurpose', LlmTrHttp: 'LlmTransport', LlmTrSse: 'LlmTransport',
                 LlmTrWebSocket: 'LlmTransport'].each { enumId, enumTypeId ->
                    ec.entity.makeValue('moqui.basic.Enumeration')
                            .setAll([enumId: enumId, enumTypeId: enumTypeId, description: enumId])
                            .createOrUpdate()
                }
            }
        } finally {
            if (!disabled) ec.artifactExecution.enableAuthz()
        }
        assert ((UserFacadeImpl) ec.user).internalLoginUser(USERNAME, false)
    }

    def cleanupSpec() {
        if (ec != null) {
            ec.user.logoutUser()
            ec.transaction.runUseOrBegin(null, 'LLM HTTP test cleanup failed') {
                ec.entity.find('moqui.security.UserAccount').condition('userId', USER_ID).disableAuthz().deleteAll()
            }
            ec.destroy()
        }
    }

    def 'client HTTP path sends canonical Open Responses request and parses output'() {
        given:
        def provider = new ScriptedResponsesProvider()
        provider.enqueueJson(200, '{"id":"resp_http_1","object":"response","status":"completed","model":"gpt-test","output":[{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"output_text","text":"ok"}]}]}')
        def client = new LlmClientImpl(null, responsesProfile(provider.endpoint, false), { false })
        client.inputItems([LlmItem.message('user', [LlmContentPart.inputText('hello')])])
                .responseOptions(new LlmResponseOptions().put('previous_response_id', 'resp_prior').put('store', false))
        when:
        LlmResponse response = client.call()
        then:
        response.finishReason == LlmFinishReason.STOP
        response.responseId == 'resp_http_1'
        response.content == 'ok'
        provider.requests.size() == 1
        provider.requests[0].method == 'POST'
        provider.requests[0].path == '/v1/responses'
        provider.requests[0].json.model == 'gpt-test'
        provider.requests[0].json.previous_response_id == 'resp_prior'
        provider.requests[0].json.input[0].content[0].text == 'hello'
        cleanup:
        provider.close()
    }

    def 'streaming client handles fragmented SSE bytes and forwards semantic events'() {
        given:
        def provider = new ScriptedResponsesProvider()
        provider.enqueueSse([
                'event: response.created\ndata: {"type":"response.created","sequence_number":0,"response":{"id":"resp_sse_1","status":"in_progress","output":[]}}\n\n',
                'event: response.output_item.added\ndata: {"type":"response.output_item.added","sequence_number":1,"output_index":0,"item":{"id":"msg_sse_1","type":"message","role":"assistant","content":[]}}\n\n',
                'event: response.content_part.added\ndata: {"type":"response.content_part.added","sequence_number":2,"item_id":"msg_sse_1","output_index":0,"content_index":0,"part":{"type":"output_text","text":""}}\n\n',
                'event: response.output_text.delta\ndata: {"type":"response.output_text.delta","sequence_number":3,"item_id":"msg_sse_1","output_index":0,"content_index":0,"delta":"Caf"}\n\n',
                'event: response.output_text.delta\ndata: {"type":"response.output_text.delta","sequence_number":4,"item_id":"msg_sse_1","output_index":0,"content_index":0,"delta":"è"}\n\n',
                'event: response.output_text.done\ndata: {"type":"response.output_text.done","sequence_number":5,"item_id":"msg_sse_1","output_index":0,"content_index":0,"text":"Cafè"}\n\n',
                'event: response.content_part.done\ndata: {"type":"response.content_part.done","sequence_number":6,"item_id":"msg_sse_1","output_index":0,"content_index":0,"part":{"type":"output_text","text":"Cafè"}}\n\n',
                'event: response.output_item.done\ndata: {"type":"response.output_item.done","sequence_number":7,"output_index":0,"item":{"id":"msg_sse_1","type":"message","role":"assistant","content":[{"type":"output_text","text":"Cafè"}]}}\n\n',
                'event: response.completed\ndata: {"type":"response.completed","sequence_number":8,"response":{"id":"resp_sse_1","status":"completed","output":[{"id":"msg_sse_1","type":"message","role":"assistant","content":[{"type":"output_text","text":"Cafè"}]}]}}\n\n',
                'data: [DONE]\n\n'
        ])
        def client = new LlmClientImpl(null, responsesProfile(provider.endpoint, false), { false })
        client.user('stream please')
        def listener = new CaptureListener()
        when:
        LlmResponse response = client.stream(listener)
        then:
        response.responseId == 'resp_sse_1'
        listener.completed.await(1, TimeUnit.SECONDS)
        listener.deltas.join('') == 'Cafè'
        listener.events*.type == ['response.created', 'response.output_item.added', 'response.content_part.added',
                'response.output_text.delta', 'response.output_text.delta', 'response.output_text.done',
                'response.content_part.done', 'response.output_item.done', 'response.completed']
        provider.requests[0].json.stream == true
        cleanup:
        provider.close()
    }

    private def callNoAuthz(LlmClientImpl client) {
        boolean off = ec.artifactExecution.disableAuthz()
        try { return client.call() } finally { if (!off) ec.artifactExecution.enableAuthz() }
    }

    private void deleteStored(String profileName) {
        boolean disabled = ec.artifactExecution.disableAuthz()
        try {
            ec.transaction.runUseOrBegin(null, 'LLM persistence test cleanup failed') {
                def requests = ec.entity.find('moqui.llm.LlmRequest').condition('profileName', profileName).list()
                def responses = ec.entity.find('moqui.llm.LlmResponse').condition('profileName', profileName).list()
                def itemFind = ec.entity.find('moqui.llm.LlmItem')
                List itemIds = []
                if (requests) itemIds += ec.entity.find('moqui.llm.LlmItem')
                        .condition('llmRequestId', EntityCondition.IN, requests*.llmRequestId).list()*.llmItemId
                if (responses) itemIds += ec.entity.find('moqui.llm.LlmItem')
                        .condition('llmResponseId', EntityCondition.IN, responses*.llmResponseId).list()*.llmItemId
                if (itemIds) {
                    ec.entity.find('moqui.llm.LlmContent').condition('llmItemId', EntityCondition.IN, itemIds).deleteAll()
                    ec.entity.find('moqui.llm.LlmItem').condition('llmItemId', EntityCondition.IN, itemIds).deleteAll()
                }
                if (responses) {
                    ec.entity.find('moqui.llm.LlmResponseEvent').condition('llmResponseId', EntityCondition.IN, responses*.llmResponseId).deleteAll()
                    ec.entity.find('moqui.llm.LlmResponse').condition('llmResponseId', EntityCondition.IN, responses*.llmResponseId).deleteAll()
                }
                ec.entity.find('moqui.llm.LlmRequest').condition('profileName', profileName).deleteAll()
                ec.entity.find('moqui.llm.LlmCallLog').condition('profileName', profileName).deleteAll()
                def runIdsOfProfile = ec.entity.find('moqui.llm.LlmRun').condition('profileName', profileName).list()*.runId
                if (runIdsOfProfile) {
                    ec.entity.find('moqui.llm.LlmToolInvocation').condition('runId', EntityCondition.IN, runIdsOfProfile).deleteAll()
                    ec.entity.find('moqui.llm.LlmContextProjection').condition('runId', EntityCondition.IN, runIdsOfProfile).deleteAll()
                }
                ec.entity.find('moqui.llm.LlmRunStatus').condition('runId', EntityCondition.IN,
                        ec.entity.find('moqui.llm.LlmRun').condition('profileName', profileName).list()*.runId ?: ['-']).deleteAll()
                ec.entity.find('moqui.llm.LlmRun').condition('profileName', profileName).deleteAll()
            }
        } finally {
            if (!disabled) ec.artifactExecution.enableAuthz()
        }
    }

    def 'a call without a conversation persists its request, items and response'() {
        given:
        def provider = new ScriptedResponsesProvider()
        provider.enqueueJson(200, '{"id":"resp_nc_1","object":"response","status":"completed","model":"gpt-test","output":[{"id":"msg_nc","type":"message","role":"assistant","content":[{"type":"output_text","text":"ok"}]}]}')
        def client = new LlmClientImpl(ec, responsesProfile(provider.endpoint, false, new OpenResponsesProtocol(), 'responses-it-noconv'), { false })
        client.inputItems([LlmItem.message('user', [LlmContentPart.inputText('no conversation here')])])
        when:
        callNoAuthz(client)
        def requests = ec.entity.find('moqui.llm.LlmRequest').condition('profileName', 'responses-it-noconv').disableAuthz().list()
        def responses = ec.entity.find('moqui.llm.LlmResponse').condition('profileName', 'responses-it-noconv').disableAuthz().list()
        def requestItems = requests ? ec.entity.find('moqui.llm.LlmItem').condition('llmRequestId', requests[0].llmRequestId).disableAuthz().list() : []
        def responseItems = responses ? ec.entity.find('moqui.llm.LlmItem').condition('llmResponseId', responses[0].llmResponseId).disableAuthz().list() : []
        then:
        requests.size() == 1
        requests[0].conversationId == null
        requests[0].ownerUserId == USER_ID
        requests[0].localStatusEnumId == 'LlmReqAck'
        requests[0].requestPayloadJson.contains('no conversation here')
        responses.size() == 1
        responses[0].providerResponseId == 'resp_nc_1'
        responses[0].llmRequestId == requests[0].llmRequestId
        responses[0].conversationId == null
        requestItems*.itemType == ['message']
        responseItems*.itemType == ['message']
        cleanup:
        provider.close()
        deleteStored('responses-it-noconv')
    }

    def 'the stored request is the body that was sent, including OpenAI extension fields over HTTP and WebSocket'() {
        given:
        def http = new ScriptedResponsesProvider()
        http.enqueueJson(200, '{"id":"resp_snap_1","object":"response","status":"completed","output":[{"id":"msg_s1","type":"message","role":"assistant","content":[{"type":"output_text","text":"one"}]}]}')
        def ws = new ScriptedWebSocketProvider(['{"type":"response.completed","sequence_number":0,"response":{"id":"resp_snap_2","object":"response","status":"completed","output":[{"id":"msg_s2","type":"message","role":"assistant","content":[{"type":"output_text","text":"two"}]}]}}'])
        def httpClient = new LlmClientImpl(ec, responsesProfile(http.endpoint, false, new OpenAiResponsesProtocol(), 'responses-it-snap'), { false })
        httpClient.inputItems([LlmItem.message('user', [LlmContentPart.inputText('snapshot http')])])
                .extraBody([prompt_cache_retention: '24h'])
        def wsClient = new LlmClientImpl(ec, responsesProfile(ws.endpoint, false, new OpenAiResponsesProtocol(), 'responses-it-snap'), { false })
        wsClient.transport(LlmTransport.WEBSOCKET)
                .inputItems([LlmItem.message('user', [LlmContentPart.inputText('snapshot ws')])])
                .extraBody([prompt_cache_retention: '24h'])
        when:
        callNoAuthz(httpClient)
        callNoAuthz(wsClient)
        def stored = ec.entity.find('moqui.llm.LlmRequest').condition('profileName', 'responses-it-snap')
                .orderBy('createdDate').disableAuthz().list()
        Map httpStored = new JsonSlurper().parseText(stored[0].requestPayloadJson)
        Map wsStored = new JsonSlurper().parseText(stored[1].requestPayloadJson)
        then:
        ws.finished.await(2, TimeUnit.SECONDS)
        stored.size() == 2
        stored[0].requestPayloadJson == http.requests[0].body
        httpStored == http.requests[0].json
        httpStored.prompt_cache_retention == '24h'
        stored[1].requestPayloadJson == ws.receivedText
        wsStored == ws.receivedJson
        wsStored.prompt_cache_retention == '24h'
        wsStored.type == 'response.create'
        cleanup:
        http.close()
        ws.close()
        deleteStored('responses-it-snap')
    }

    def 'an HTTP error is a failed request and a dropped connection leaves it uncertain'() {
        given:
        def provider = new ScriptedResponsesProvider()
        provider.enqueueJson(500, '{"error":{"code":"server_error","message":"provider down"}}')
        provider.enqueueHangUp()
        def failing = new LlmClientImpl(ec, responsesProfile(provider.endpoint, false, new OpenResponsesProtocol(), 'responses-it-status'), { false })
        failing.inputItems([LlmItem.message('user', [LlmContentPart.inputText('first')])])
        def dropped = new LlmClientImpl(ec, responsesProfile(provider.endpoint, false, new OpenResponsesProtocol(), 'responses-it-status'), { false })
        dropped.inputItems([LlmItem.message('user', [LlmContentPart.inputText('second')])])
        when:
        try { callNoAuthz(failing) } catch (LlmException ignored) { }
        try { callNoAuthz(dropped) } catch (LlmException ignored) { }
        def stored = ec.entity.find('moqui.llm.LlmRequest').condition('profileName', 'responses-it-status')
                .orderBy('createdDate').disableAuthz().list()
        then:
        stored.size() == 2
        stored[0].localStatusEnumId == 'LlmReqFailed'
        stored[0].httpStatus == 500
        stored[1].localStatusEnumId == 'LlmReqUncertain'
        cleanup:
        provider.close()
        deleteStored('responses-it-status')
    }

    private static final String COMPLETED_FRAME = 'event: response.completed\ndata: {"type":"response.completed","sequence_number":0,"response":{"id":"resp_sse_x","status":"completed","output":[{"id":"msg_x","type":"message","role":"assistant","content":[{"type":"output_text","text":"fine"}]}]}}\n\n'

    private LlmResponse streamOnce(LlmProtocol protocol, List<String> frames) {
        def provider = new ScriptedResponsesProvider()
        provider.enqueueSse(frames)
        def client = new LlmClientImpl(null, responsesProfile(provider.endpoint, false, protocol, 'responses-it-sse'), { false })
        client.user('stream')
        try { return client.stream(new CaptureListener()) } finally { provider.close() }
    }

    def 'the standard protocol requires the DONE sentinel, the OpenAI protocol tolerates its absence'() {
        when: 'a terminal event followed by the sentinel is a complete stream'
        LlmResponse complete = streamOnce(new OpenResponsesProtocol(), [COMPLETED_FRAME, 'data: [DONE]\n\n'])
        then:
        complete.responseId == 'resp_sse_x'
        when: 'a terminal event and then EOF is not a standard stream'
        streamOnce(new OpenResponsesProtocol(), [COMPLETED_FRAME])
        then:
        def missing = thrown(LlmException)
        missing.message.contains('[DONE]')
        when: 'OpenAI ends the stream after the terminal event'
        LlmResponse tolerated = streamOnce(new OpenAiResponsesProtocol(), [COMPLETED_FRAME])
        then:
        tolerated.responseId == 'resp_sse_x'
    }

    def 'a stream that is cut short or broken is an error, never a synthesized success'() {
        when: 'the sentinel without any terminal event'
        streamOnce(new OpenResponsesProtocol(), ['data: [DONE]\n\n'])
        then:
        thrown(LlmException)
        when: 'EOF before the terminal event'
        streamOnce(new OpenResponsesProtocol(), ['event: response.output_text.delta\ndata: {"type":"response.output_text.delta","sequence_number":0,"delta":"par"}\n\n'])
        then:
        thrown(LlmException)
        when: 'an event that is not JSON'
        streamOnce(new OpenResponsesProtocol(), ['event: response.output_text.delta\ndata: {not json\n\n', COMPLETED_FRAME, 'data: [DONE]\n\n'])
        then:
        def malformed = thrown(LlmException)
        malformed.message.contains('not valid JSON')
        when: 'an event name that contradicts its type'
        streamOnce(new OpenResponsesProtocol(), ['event: response.in_progress\ndata: {"type":"response.completed","sequence_number":0}\n\n', 'data: [DONE]\n\n'])
        then:
        def mismatch = thrown(LlmException)
        mismatch.message.contains('does not match its type')
    }

    private static String sseFrame(String type, long seq, Map fields) {
        "event: ${type}\ndata: ${groovy.json.JsonOutput.toJson([type: type, sequence_number: seq] + fields)}\n\n".toString()
    }

    /** created .. output_item.done of one message, the events a provider sends before the terminal event. */
    private static List<String> messageFrames(String responseId, String text) {
        Map item = [id: 'msg_j1', type: 'message', role: 'assistant']
        [sseFrame('response.created', 0, [response: [id: responseId, status: 'in_progress', output: []]]),
         sseFrame('response.output_item.added', 1, [output_index: 0, item: item + [content: []]]),
         sseFrame('response.content_part.added', 2, [item_id: 'msg_j1', output_index: 0, content_index: 0, part: [type: 'output_text', text: '']]),
         sseFrame('response.output_text.delta', 3, [item_id: 'msg_j1', output_index: 0, content_index: 0, delta: text]),
         sseFrame('response.output_text.done', 4, [item_id: 'msg_j1', output_index: 0, content_index: 0, text: text]),
         sseFrame('response.content_part.done', 5, [item_id: 'msg_j1', output_index: 0, content_index: 0, part: [type: 'output_text', text: text]]),
         sseFrame('response.output_item.done', 6, [output_index: 0, item: item + [content: [[type: 'output_text', text: text]]]])]
    }

    def 'a streamed response is journaled once, its events are not written twice, and the final result completes the same row'() {
        given:
        def provider = new ScriptedResponsesProvider()
        List<String> frames = messageFrames('resp_j1', 'journal') +
                [sseFrame('response.completed', 7, [response: [id: 'resp_j1', object: 'response', status: 'completed',
                        output: [[id: 'msg_j1', type: 'message', role: 'assistant', content: [[type: 'output_text', text: 'journal']]]]]]),
                 'data: [DONE]\n\n']
        provider.enqueueSse(frames)
        def client = new LlmClientImpl(ec, responsesProfile(provider.endpoint, false, new OpenResponsesProtocol(), 'responses-it-journal'), { false })
        client.user('journal please')
        def listener = new CaptureListener()
        when:
        boolean off = ec.artifactExecution.disableAuthz()
        try { client.stream(listener) } finally { if (!off) ec.artifactExecution.enableAuthz() }
        def responses = ec.entity.find('moqui.llm.LlmResponse').condition('profileName', 'responses-it-journal').disableAuthz().list()
        def events = ec.entity.find('moqui.llm.LlmResponseEvent').condition('llmResponseId', responses[0].llmResponseId).orderBy('sequenceNum').disableAuthz().list()
        then:
        responses.size() == 1
        responses[0].status == 'completed'
        responses[0].providerResponseId == 'resp_j1'
        events*.sequenceNum == (1..8).collect { it }
        events*.providerSequenceNum*.longValue() == (0..7).collect { it as long }
        events*.eventType.count('response.created') == 1
        events.last().terminal == 'Y'
        when: 'the events are read page by page through the service'
        def page1 = service('get#LlmResponse', [llmResponseId: responses[0].llmResponseId, includeEvents: true, eventOffset: 0, eventLimit: 3]).response
        def page3 = service('get#LlmResponse', [llmResponseId: responses[0].llmResponseId, includeEvents: true, eventOffset: 6, eventLimit: 3]).response
        then:
        page1.events*.sequenceNum == [1, 2, 3]
        page1.eventsNext == 3
        page3.events*.sequenceNum == [7, 8]
        page3.eventsNext == null
        cleanup:
        provider.close()
        deleteStored('responses-it-journal')
    }

    def 'a stream cut after a finished item keeps the events received and marks the response interrupted'() {
        given:
        def provider = new ScriptedResponsesProvider()
        provider.enqueueSse(messageFrames('resp_j2', 'partial'))
        def client = new LlmClientImpl(ec, responsesProfile(provider.endpoint, false, new OpenResponsesProtocol(), 'responses-it-cut'), { false })
        client.user('cut please')
        when:
        boolean off = ec.artifactExecution.disableAuthz()
        try { client.stream(new CaptureListener()) } finally { if (!off) ec.artifactExecution.enableAuthz() }
        then:
        thrown(LlmException)
        when:
        def responses = ec.entity.find('moqui.llm.LlmResponse').condition('profileName', 'responses-it-cut').disableAuthz().list()
        def events = ec.entity.find('moqui.llm.LlmResponseEvent').condition('llmResponseId', responses[0].llmResponseId).orderBy('sequenceNum').disableAuthz().list()
        def request = ec.entity.find('moqui.llm.LlmRequest').condition('profileName', 'responses-it-cut').disableAuthz().one()
        then:
        responses.size() == 1
        responses[0].status == 'interrupted'
        responses[0].providerResponseId == 'resp_j2'
        events.size() == 7
        events*.eventType.last() == 'response.output_item.done'
        request.localStatusEnumId == 'LlmReqUncertain'
        cleanup:
        provider.close()
        deleteStored('responses-it-cut')
    }

    def 'the stored request is the exact body that went on the wire, opaque and large fields included, encrypted at rest'() {
        given:
        def provider = new ScriptedResponsesProvider()
        provider.enqueueJson(200, '{"id":"resp_opq_1","object":"response","status":"completed","output":[{"id":"msg_o","type":"message","role":"assistant","content":[{"type":"output_text","text":"done"}]}]}')
        String secretBlob = 'opaque-reasoning-bytes-' + ('x' * 200)
        String fileBytes = Base64.encoder.encodeToString(('file body ' * 50).getBytes('UTF-8'))
        LlmItem reasoning = new LlmItem()
        reasoning.type = 'reasoning'
        reasoning.encryptedContent = secretBlob
        LlmContentPart file = new LlmContentPart()
        file.type = 'input_file'
        file.filename = 'note.txt'
        file.fileData = fileBytes
        def client = new LlmClientImpl(ec, responsesProfile(provider.endpoint, false, new OpenResponsesProtocol(), 'responses-it-opaque'), { false })
        client.inputItems([LlmItem.message('user', [LlmContentPart.inputText('with a file'), file]), reasoning])
        when:
        callNoAuthz(client)
        def request = ec.entity.find('moqui.llm.LlmRequest').condition('profileName', 'responses-it-opaque').disableAuthz().one()
        def items = ec.entity.find('moqui.llm.LlmItem').condition('llmRequestId', request.llmRequestId).orderBy('sequenceNum').disableAuthz().list()
        def parts = ec.entity.find('moqui.llm.LlmContent').condition('llmItemId', items[0].llmItemId).orderBy('sequenceNum').disableAuthz().list()
        Map meta = new JsonSlurper().parseText(request.metadataJson)
        String sent = provider.requests[0].body
        String sentSha = MessageDigest.getInstance('SHA-256').digest(sent.getBytes('UTF-8')).collect { String.format('%02x', it & 0xff) }.join()
        String rawColumn = null
        ec.transaction.runUseOrBegin(null, 'raw read failed') {
            def ps = ec.entity.getConnection('transactional').prepareStatement('SELECT REQUEST_PAYLOAD_JSON FROM LLM_REQUEST WHERE LLM_REQUEST_ID = ?')
            try { ps.setString(1, request.llmRequestId as String); def rs = ps.executeQuery(); rs.next(); rawColumn = rs.getString(1) }
            finally { ps.close() }
        }
        then: 'the stored text is the sent text, character for character'
        request.requestPayloadJson == sent
        meta.sentBodySha256 == sentSha
        meta.sentBodyBytes == sent.getBytes('UTF-8').length
        and: 'every opaque and large field is in it, where it was'
        Map stored = new JsonSlurper().parseText(request.requestPayloadJson)
        stored.input[0].content[1].file_data == fileBytes
        stored.input[1].encrypted_content == secretBlob
        and: 'the normalized columns hold them too'
        items[1].encryptedContent == secretBlob
        parts.find { it.contentType == 'input_file' }.fileData == fileBytes
        and: 'the database column is encrypted'
        rawColumn != null && !rawColumn.contains(secretBlob) && !rawColumn.contains(fileBytes) && !rawColumn.contains('with a file')
        cleanup:
        provider.close()
        deleteStored('responses-it-opaque')
    }

    def 'HTTP error matrix is classified through the real Open Responses client'() {
        given:
        def provider = new ScriptedResponsesProvider()
        provider.enqueueBody(status, body, contentType)
        def client = new LlmClientImpl(null, responsesProfile(provider.endpoint, false), { false })
        client.inputItems([LlmItem.message('user', [LlmContentPart.inputText('classify error')])])
        when:
        client.call()
        then:
        def error = thrown(LlmException)
        error.reason == finishReason
        error.httpStatus == expectedHttpStatus
        if (messagePart) assert error.message.contains(messagePart)
        provider.requests.size() == 1
        provider.requests[0].json.input[0].content[0].text == 'classify error'
        cleanup:
        provider.close()
        where:
        status | contentType        | body                                                                     | finishReason                      | expectedHttpStatus | messagePart
        400    | 'application/json' | '{"error":{"code":"context_length_exceeded","message":"too many tokens"}}' | LlmFinishReason.CONTEXT_OVERFLOW | 400                | 'too many tokens'
        401    | 'application/json' | '{"error":{"code":"unauthorized","message":"bad key"}}'                    | LlmFinishReason.ERROR            | 0                  | 'Error calling HTTP request'
        429    | 'application/json' | '{"error":{"code":"rate_limit_exceeded","message":"too many requests"}}'   | LlmFinishReason.ERROR            | 429                | 'too many requests'
        500    | 'application/json' | '{"error":{"code":"server_error","message":"provider down"}}'              | LlmFinishReason.ERROR            | 500                | 'provider down'
        502    | 'text/plain'       | 'upstream gateway returned html'                                           | LlmFinishReason.ERROR            | 502                | 'LLM call failed'
    }

    def 'websocket client sends continuation create and handles fragmented provider message'() {
        given:
        def provider = new ScriptedWebSocketProvider([
                '{"type":"response.completed","sequence_number":0,"response":{"id":"resp_ws_1","object":"response","status":"completed","previous_response_id":"resp_prior_ws","output":[{"id":"msg_ws_1","type":"message","role":"assistant","content":[{"type":"output_text","text":"ws ok"}]}]}}'
        ])
        def client = new LlmClientImpl(null, responsesProfile(provider.endpoint, false), { false })
        client.transport(LlmTransport.WEBSOCKET)
                .previousResponse('resp_prior_ws')
                .inputItems([LlmItem.message('user', [LlmContentPart.inputText('continue')])])
                .responseOptions(new LlmResponseOptions()
                        .put('stream_options', [include_usage: true])
                        .put('background', true)
                        .put('store', false))
        when:
        LlmResponse response = client.call()
        then:
        provider.finished.await(2, TimeUnit.SECONDS)
        provider.failure == null
        response.responseId == 'resp_ws_1'
        response.previousResponseId == 'resp_prior_ws'
        response.content == 'ws ok'
        provider.receivedJson.type == 'response.create'
        provider.receivedJson.previous_response_id == 'resp_prior_ws'
        provider.receivedJson.input[0].content[0].text == 'continue'
        !provider.receivedJson.containsKey('stream')
        !provider.receivedJson.containsKey('stream_options')
        !provider.receivedJson.containsKey('background')
        cleanup:
        provider.close()
    }

    def 'websocket client surfaces previous response loss as provider error'() {
        given:
        def provider = new ScriptedWebSocketProvider([
                '{"type":"error","status":404,"error":{"code":"previous_response_not_found","message":"previous response missing","param":"previous_response_id"}}'
        ])
        def client = new LlmClientImpl(null, responsesProfile(provider.endpoint, false), { false })
        client.transport(LlmTransport.WEBSOCKET)
                .previousResponse('resp_missing')
                .inputItems([LlmItem.message('user', [LlmContentPart.inputText('continue')])])
        when:
        client.call()
        then:
        def error = thrown(LlmException)
        error.message.contains('previous response missing')
        provider.finished.await(2, TimeUnit.SECONDS)
        provider.receivedJson.previous_response_id == 'resp_missing'
        cleanup:
        provider.close()
    }

    def 'websocket store false turn can be explicitly replayed after reconnect'() {
        given:
        List<LlmItem> input = [LlmItem.message('user', [LlmContentPart.inputText('stateless retry')])]
        def firstProvider = new ScriptedWebSocketProvider([])
        def secondProvider = null
        def firstClient = new LlmClientImpl(null, responsesProfile(firstProvider.endpoint, false), { false })
        firstClient.transport(LlmTransport.WEBSOCKET)
                .inputItems(input)
                .responseOptions(new LlmResponseOptions().put('store', false))
        when:
        firstClient.call()
        then:
        thrown(LlmException)
        firstProvider.finished.await(2, TimeUnit.SECONDS)
        firstProvider.receivedJson.store == false
        firstProvider.receivedJson.input[0].content[0].text == 'stateless retry'
        when:
        secondProvider = new ScriptedWebSocketProvider([
                '{"type":"response.completed","sequence_number":0,"response":{"id":"resp_ws_retry","object":"response","status":"completed","output":[{"id":"msg_ws_retry","type":"message","role":"assistant","content":[{"type":"output_text","text":"retry ok"}]}]}}'
        ])
        def secondClient = new LlmClientImpl(null, responsesProfile(secondProvider.endpoint, false), { false })
        secondClient.transport(LlmTransport.WEBSOCKET)
                .inputItems(input)
                .responseOptions(new LlmResponseOptions().put('store', false))
        LlmResponse response = secondClient.call()
        then:
        secondProvider.finished.await(2, TimeUnit.SECONDS)
        response.responseId == 'resp_ws_retry'
        response.content == 'retry ok'
        secondProvider.receivedJson.store == false
        secondProvider.receivedJson.input[0].content[0].text == 'stateless retry'
        cleanup:
        firstProvider?.close()
        secondProvider?.close()
    }

    def 'compaction output starts a new local chain without previous response id'() {
        given:
        def provider = new ScriptedResponsesProvider()
        provider.enqueueJson(200, '''{"id":"cmp_new_chain","object":"response.compaction","created_at":1760000000,"output":[
            {"id":"cmp_item_1","type":"compaction","encrypted_content":"enc-prior-context"}
        ],"usage":{"input_tokens":8,"output_tokens":3,"total_tokens":11}}''')
        provider.enqueueJson(200, '''{"id":"resp_after_compact","object":"response","status":"completed","output":[
            {"id":"msg_after_compact","type":"message","role":"assistant","content":[{"type":"output_text","text":"new chain ok"}]}
        ]}''')
        def compactClient = new LlmClientImpl(null, responsesProfile(provider.endpoint, false), { false })
        compactClient.inputItems([LlmItem.message('user', [LlmContentPart.inputText('summarize long context')])])
                .responseOptions(new LlmResponseOptions()
                        .put('instructions', 'retain decisions')
                        .put('prompt_cache_key', 'case-compact'))
        when:
        LlmCompactResult compact = compactClient.compact()
        def nextClient = new LlmClientImpl(null, responsesProfile(provider.endpoint, false), { false })
        nextClient.inputItems(compact.output + [LlmItem.message('user', [LlmContentPart.inputText('continue locally')])])
                .responseOptions(new LlmResponseOptions().put('store', false))
        LlmResponse response = nextClient.call()
        then:
        compact.id == 'cmp_new_chain'
        compact.output[0].type == 'compaction'
        response.responseId == 'resp_after_compact'
        response.content == 'new chain ok'
        provider.requests*.path == ['/v1/responses/compact', '/v1/responses']
        provider.requests[0].json.input[0].content[0].text == 'summarize long context'
        provider.requests[0].json.previous_response_id == null
        provider.requests[1].json.input*.type == ['compaction', 'message']
        provider.requests[1].json.previous_response_id == null
        provider.requests[1].json.store == false
        cleanup:
        provider.close()
    }


    @IgnoreIf({
        def key = System.getenv('llm_openai_api_key') ?: System.getProperty('llm_openai_api_key')
        key == null || key.toString().trim().isEmpty()
    })
    def 'optional live OpenAI Responses client persists request and response through Moqui'() {
        given:
        Timestamp requestStart = new Timestamp(System.currentTimeMillis() - 1000L)
        def client = ec.llm.getClient('openai-responses')
        client.newConversation()
                .inputItems([LlmItem.message('user', [LlmContentPart.inputText('Reply with the single word pong.')])])
                .responseOptions(new LlmResponseOptions().put('store', false))
                // no temperature and room for reasoning tokens: the test must not depend on which model the profile names
                .maxTokens(1024)
        when:
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        LlmResponse response
        try {
            response = client.call()
        } finally {
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
        List storedRequests = ec.entity.find('moqui.llm.LlmRequest')
                .condition('ownerUserId', USER_ID)
                .condition('profileName', 'openai-responses')
                .condition('createdDate', EntityCondition.GREATER_THAN_EQUAL_TO, requestStart)
                .orderBy('createdDate').disableAuthz().list()
        List storedResponses = ec.entity.find('moqui.llm.LlmResponse')
                .condition('profileName', 'openai-responses')
                .condition('createdDate', EntityCondition.GREATER_THAN_EQUAL_TO, requestStart)
                .orderBy('createdDate').disableAuthz().list()
        then:
        response.responseId
        response.content != null && !response.content.isBlank()
        storedRequests.size() == 1
        storedRequests[0].localStatusEnumId == 'LlmReqAck'
        storedRequests[0].requestPayloadJson.contains('Reply with the single word pong.')
        storedResponses.size() == 1
        storedResponses[0].llmRequestId == storedRequests[0].llmRequestId
        storedResponses[0].providerResponseId == response.responseId
        ec.entity.find('moqui.llm.LlmItem').condition('llmRequestId', storedRequests[0].llmRequestId)
                .disableAuthz().count() == 1
        ec.entity.find('moqui.llm.LlmItem').condition('llmResponseId', storedResponses[0].llmResponseId)
                .disableAuthz().count() >= 1
    }

    @IgnoreIf({
        def key = System.getenv('llm_openai_api_key') ?: System.getProperty('llm_openai_api_key')
        key == null || key.toString().trim().isEmpty()
    })
    def 'optional live OpenAI Responses stream completes and is persisted without a DONE sentinel'() {
        given:
        def client = ec.llm.getClient('openai-responses')
        client.inputItems([LlmItem.message('user', [LlmContentPart.inputText('Reply with the single word pong.')])])
                .responseOptions(new LlmResponseOptions().put('store', false))
                // no temperature and room for reasoning tokens: the test must not depend on which model the profile names
                .maxTokens(1024)
        def listener = new CaptureListener()
        when:
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        LlmResponse response
        try { response = client.stream(listener) }
        finally { if (!alreadyDisabled) ec.artifactExecution.enableAuthz() }
        then:
        listener.failure == null
        response.content?.toLowerCase()?.contains('pong')
        listener.events*.type.contains('response.completed')
        response.responseId != null
        cleanup:
        deleteStored('openai-responses')
    }

    def 'agent loop posts reasoning call and service result in second HTTP request even without content logs'() {
        given:
        def provider = new ScriptedResponsesProvider()
        provider.enqueueJson(200, '''{"id":"resp_loop_1","object":"response","status":"completed","output":[
            {"id":"rs_1","type":"reasoning","content":[{"type":"reasoning_text","text":"need service"}]},
            {"id":"fc_item_1","type":"function_call","call_id":"call_search_1","name":"service_search","arguments":"{\\"query\\":\\"alpha\\"}"}
        ]}''')
        provider.enqueueJson(200, '''{"id":"resp_loop_2","object":"response","status":"completed","output":[
            {"id":"msg_2","type":"message","role":"assistant","content":[{"type":"output_text","text":"done"}]}
        ]}''')
        Timestamp requestStart = new Timestamp(System.currentTimeMillis() - 1000L)
        def client = new LlmClientImpl(ec, responsesProfile(provider.endpoint, false), { false })
        client.newConversation().user('search alpha').tool(new LlmTool() {
            String getName() { 'service_search' }
            String getDescription() { 'Call search#LlmContext as a Moqui service' }
            Map<String, Object> getParametersSchema() {
                [type: 'object', properties: [query: [type: 'string']], required: ['query']]
            }
            LlmTool.Execution getExecution() { LlmTool.Execution.SERVER }
            Object execute(Map<String, Object> arguments, ExecutionContext executionContext) {
                executionContext.service.sync().name('org.moqui.impl.LlmServices.search#LlmContext')
                        .parameters([query: arguments.query ?: 'alpha', limit: 1]).call()
            }
        })
        when:
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        LlmResponse response
        try {
            response = client.call()
        } finally {
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
        then:
        response.responseId == 'resp_loop_2'
        response.content == 'done'
        provider.requests.size() == 2
        provider.requests[1].json.input*.type == ['message', 'reasoning', 'function_call', 'function_call_output']
        provider.requests[1].json.previous_response_id == null
        provider.requests[1].json.input[1].id == 'rs_1'
        provider.requests[1].json.input[2].id == 'fc_item_1'
        provider.requests[1].json.input[2].call_id == 'call_search_1'
        provider.requests[1].json.input[3].call_id == 'call_search_1'
        // the output of a function call is text or an array of parts on the wire, so a service result travels as JSON text
        provider.requests[1].json.input[3].output instanceof String
        new JsonSlurper().parseText(provider.requests[1].json.input[3].output).results.every { it.sourceType == 'content' }
        response.rawJson == null
        List storedRequests = ec.entity.find('moqui.llm.LlmRequest')
                .condition('ownerUserId', USER_ID)
                .condition('createdDate', EntityCondition.GREATER_THAN_EQUAL_TO, requestStart)
                .orderBy('createdDate').disableAuthz().list()
        storedRequests.size() == 2
        storedRequests*.localStatusEnumId == ['LlmReqAck', 'LlmReqAck']
        storedRequests[0].requestPayloadJson != null
        storedRequests[0].requestPayloadJson.contains('search alpha')
        def storedResponses = ec.entity.find('moqui.llm.LlmResponse')
                .condition('llmRequestId', EntityCondition.IN, storedRequests*.llmRequestId)
                .orderBy('createdDate').disableAuthz().list()
        storedResponses*.providerResponseId == ['resp_loop_1', 'resp_loop_2']
        Map requestServiceResult
        Map responseServiceResult
        boolean serviceAuthzDisabled = ec.artifactExecution.disableAuthz()
        try {
            requestServiceResult = ec.service.sync().name('org.moqui.impl.LlmServices.get#LlmRequest')
                    .parameters([llmRequestId: storedRequests[1].llmRequestId]).call()
            responseServiceResult = ec.service.sync().name('org.moqui.impl.LlmServices.get#LlmResponse')
                    .parameters([llmResponseId: storedResponses[1].llmResponseId]).call()
        } finally {
            if (!serviceAuthzDisabled) ec.artifactExecution.enableAuthz()
        }
        requestServiceResult.request.items*.itemType == ['message', 'reasoning', 'function_call', 'function_call_output']
        responseServiceResult.response.llmRequestId == storedRequests[1].llmRequestId
        responseServiceResult.response.items[0].content[0].purposeEnumId == 'LlmCpAssistant'
        def firstRequestItems = ec.entity.find('moqui.llm.LlmItem')
                .condition('llmRequestId', storedRequests[0].llmRequestId)
                .orderBy('sequenceNum').disableAuthz().list()
        firstRequestItems*.itemType == ['message']
        def firstRequestContent = ec.entity.find('moqui.llm.LlmContent')
                .condition('llmItemId', firstRequestItems[0].llmItemId)
                .disableAuthz().one()
        firstRequestContent.purposeEnumId == 'LlmCpUser'
        def secondRequestItems = ec.entity.find('moqui.llm.LlmItem')
                .condition('llmRequestId', storedRequests[1].llmRequestId)
                .orderBy('sequenceNum').disableAuthz().list()
        secondRequestItems*.itemType == ['message', 'reasoning', 'function_call', 'function_call_output']
        def secondContent = ec.entity.find('moqui.llm.LlmContent')
                .condition('llmItemId', EntityCondition.IN, secondRequestItems*.llmItemId)
                .orderBy('sequenceNum').disableAuthz().list()
        secondContent*.purposeEnumId == ['LlmCpUser', 'LlmCpAssistant']
        cleanup:
        provider.close()
    }

    private Map service(String name, Map parameters) {
        boolean off = ec.artifactExecution.disableAuthz()
        try { return ec.service.sync().name("org.moqui.impl.LlmServices.${name}".toString()).parameters(parameters).call() }
        finally { if (!off) ec.artifactExecution.enableAuthz() }
    }

    private List contentOf(String itemId) {
        ec.entity.find('moqui.llm.LlmContent').condition('llmItemId', itemId).orderBy(['contentKind', 'sequenceNum']).disableAuthz().list()
    }

    def 'a tool output array and every kind of content are stored on their own, numbered from one, with the purpose of who produced them'() {
        given:
        def provider = new ScriptedResponsesProvider()
        provider.enqueueJson(200, '{"id":"resp_items_1","object":"response","status":"completed","output":[{"id":"rs_1","type":"reasoning","summary":[{"type":"summary_text","text":"why"}],"content":[{"type":"reasoning_text","text":"think"}]},{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"output_text","text":"done"}]}]}')
        LlmItem reasoning = new LlmItem()
        reasoning.type = 'reasoning'
        reasoning.summary = [new LlmContentPart(type: 'summary_text', text: 'earlier why')]
        reasoning.encryptedContent = 'opaque'
        def client = new LlmClientImpl(ec, responsesProfile(provider.endpoint, false, new OpenResponsesProtocol(), 'items-it'), { false })
        client.inputItems([LlmItem.message('user', [LlmContentPart.inputText('q')]),
                           LlmItem.message('assistant', [new LlmContentPart(type: 'output_text', text: 'a1'), new LlmContentPart(type: 'output_text', text: 'a2')]),
                           reasoning,
                           new LlmItem(type: 'function_call', callId: 'call_1', name: 'lookup', arguments: '{}'),
                           LlmItem.functionCallOutput('call_1', [LlmContentPart.inputText('one'), LlmContentPart.inputText('two')])])
        when:
        callNoAuthz(client)
        def request = ec.entity.find('moqui.llm.LlmRequest').condition('profileName', 'items-it').disableAuthz().one()
        def items = ec.entity.find('moqui.llm.LlmItem').condition('llmRequestId', request.llmRequestId).orderBy('sequenceNum').disableAuthz().list()
        def response = ec.entity.find('moqui.llm.LlmResponse').condition('profileName', 'items-it').disableAuthz().one()
        def outItems = ec.entity.find('moqui.llm.LlmItem').condition('llmResponseId', response.llmResponseId).orderBy('sequenceNum').disableAuthz().list()
        then: 'the user message is the application, the replayed assistant message is the model'
        contentOf(items[0].llmItemId)*.purposeEnumId == ['LlmCpUser']
        contentOf(items[1].llmItemId)*.purposeEnumId == ['LlmCpAssistant', 'LlmCpAssistant']
        contentOf(items[1].llmItemId)*.sequenceNum == [1, 2]
        and: 'a replayed summary is the model, in its own numbering'
        contentOf(items[2].llmItemId)*.contentKind == ['summary']
        contentOf(items[2].llmItemId)*.sequenceNum == [1]
        contentOf(items[2].llmItemId)*.purposeEnumId == ['LlmCpAssistant']
        and: 'a tool output array becomes output parts of the application'
        items[4].outputShape == 'array'
        contentOf(items[4].llmItemId)*.contentKind == ['output', 'output']
        contentOf(items[4].llmItemId)*.sequenceNum == [1, 2]
        contentOf(items[4].llmItemId)*.textContent == ['one', 'two']
        contentOf(items[4].llmItemId)*.purposeEnumId == ['LlmCpUser', 'LlmCpUser']
        and: 'the response reasoning keeps content and summary apart, each from one'
        contentOf(outItems[0].llmItemId)*.contentKind == ['content', 'summary']
        contentOf(outItems[0].llmItemId)*.sequenceNum == [1, 1]
        contentOf(outItems[1].llmItemId)*.purposeEnumId == ['LlmCpAssistant']
        and: 'the service returns the output parts apart from the content parts'
        service('get#LlmRequest', [llmRequestId: request.llmRequestId]).request.items[4].outputContent*.text == ['one', 'two']
        service('get#LlmRequest', [llmRequestId: request.llmRequestId]).request.items[4].content == []
        cleanup:
        provider.close()
        deleteStored('items-it')
    }

    def 'a replayed response item is linked to its source, another owner is never matched or accepted'() {
        given:
        def provider = new ScriptedResponsesProvider()
        provider.enqueueJson(200, '{"id":"resp_src_1","object":"response","status":"completed","output":[{"id":"msg_src","type":"message","role":"assistant","content":[{"type":"output_text","text":"first"}]}]}')
        provider.enqueueJson(200, '{"id":"resp_src_2","object":"response","status":"completed","output":[{"id":"msg_2","type":"message","role":"assistant","content":[{"type":"output_text","text":"second"}]}]}')
        provider.enqueueJson(200, '{"id":"resp_src_3","object":"response","status":"completed","output":[{"id":"msg_3","type":"message","role":"assistant","content":[{"type":"output_text","text":"third"}]}]}')
        String otherItemId = 'ITEMOTHER' + System.currentTimeMillis()
        boolean off = ec.artifactExecution.disableAuthz()
        try {
            ec.transaction.runUseOrBegin(null, 'other owner rows failed') {
                ec.entity.makeValue('moqui.security.UserAccount').setAll([userId: 'LLMHTTPOTHER', username: 'llm.http.other', userFullName: 'Other']).createOrUpdate()
                ec.entity.makeValue('moqui.llm.LlmResponse').setAll([llmResponseId: otherItemId + 'R', providerResponseId: 'resp_other',
                        ownerUserId: 'LLMHTTPOTHER', profileName: 'source-it', status: 'completed', createdDate: new Timestamp(System.currentTimeMillis())]).create()
                ec.entity.makeValue('moqui.llm.LlmItem').setAll([llmItemId: otherItemId, llmResponseId: otherItemId + 'R', sequenceNum: 1,
                        providerItemId: 'msg_foreign', itemType: 'message', role: 'assistant']).create()
            }
        } finally { if (!off) ec.artifactExecution.enableAuthz() }
        def profile = responsesProfile(provider.endpoint, false, new OpenResponsesProtocol(), 'source-it')
        when: 'the first answer is stored, then replayed by its provider item id'
        def first = new LlmClientImpl(ec, profile, { false })
        first.inputItems([LlmItem.message('user', [LlmContentPart.inputText('go')])])
        callNoAuthz(first)
        LlmItem replay = LlmItem.message('assistant', [new LlmContentPart(type: 'output_text', text: 'first')])
        replay.providerItemId = 'msg_src'
        LlmItem foreignId = LlmItem.message('assistant', [new LlmContentPart(type: 'output_text', text: 'x')])
        foreignId.providerItemId = 'msg_foreign'
        def second = new LlmClientImpl(ec, profile, { false })
        second.inputItems([LlmItem.message('user', [LlmContentPart.inputText('again')]), replay, foreignId])
        callNoAuthz(second)
        def sourceRow = ec.entity.find('moqui.llm.LlmItem').condition('providerItemId', 'msg_src').condition('llmResponseId', EntityCondition.NOT_EQUAL, null).disableAuthz().one()
        def requests = ec.entity.find('moqui.llm.LlmRequest').condition('profileName', 'source-it').orderBy('createdDate').disableAuthz().list()
        def replayRows = ec.entity.find('moqui.llm.LlmItem').condition('llmRequestId', requests.last().llmRequestId).orderBy('sequenceNum').disableAuthz().list()
        then:
        replayRows[1].sourceLlmItemId == sourceRow.llmItemId
        replayRows[2].sourceLlmItemId == null
        when: 'a source of another owner is named explicitly'
        LlmItem stolen = LlmItem.message('assistant', [new LlmContentPart(type: 'output_text', text: 'y')])
        stolen.sourceItemId = otherItemId
        def third = new LlmClientImpl(ec, profile, { false })
        third.inputItems([LlmItem.message('user', [LlmContentPart.inputText('steal')]), stolen])
        callNoAuthz(third)
        then:
        Throwable t = thrown()
        causes(t).any { it.message?.contains('The source item is not available') }
        !causes(t).any { it.message?.contains(otherItemId) }
        cleanup:
        provider.close()
        boolean off2 = ec.artifactExecution.disableAuthz()
        try {
            ec.transaction.runUseOrBegin(null, 'other owner cleanup failed') {
                ec.entity.find('moqui.llm.LlmItem').condition('llmItemId', otherItemId).deleteAll()
                ec.entity.find('moqui.llm.LlmResponse').condition('llmResponseId', otherItemId + 'R').deleteAll()
                ec.entity.find('moqui.security.UserAccount').condition('userId', 'LLMHTTPOTHER').deleteAll()
            }
        } finally { if (!off2) ec.artifactExecution.enableAuthz() }
        deleteStored('source-it')
    }

    private static List<Throwable> causes(Throwable t) {
        List<Throwable> out = []
        while (t != null && !out.contains(t)) { out << t; t = t.cause }
        out
    }

    def 'requested, effective and returned options are kept apart and the provider usage is kept as returned'() {
        given:
        def provider = new ScriptedResponsesProvider()
        provider.enqueueJson(200, '{"id":"resp_opt_1","object":"response","status":"completed","temperature":null,"top_p":1.0,"store":false,"truncation":"disabled","output":[{"id":"msg_o","type":"message","role":"assistant","content":[{"type":"output_text","text":"ok"}]}],"usage":{"input_tokens":5,"output_tokens":2,"total_tokens":7,"input_tokens_details":{"cached_tokens":3},"output_tokens_details":{"reasoning_tokens":0}}}')
        def client = new LlmClientImpl(ec, responsesProfile(provider.endpoint, false, new OpenResponsesProtocol(), 'options-it'), { false })
        client.inputItems([LlmItem.message('user', [LlmContentPart.inputText('hi')])])
                .responseOptions(new LlmResponseOptions().put('prompt_cache_key', 'k1').put('max_tool_calls', null))
        when:
        callNoAuthz(client)
        def request = ec.entity.find('moqui.llm.LlmRequest').condition('profileName', 'options-it').disableAuthz().one()
        def response = ec.entity.find('moqui.llm.LlmResponse').condition('profileName', 'options-it').disableAuthz().one()
        Map requested = new JsonSlurper().parseText(request.requestOptionsJson)
        Map effective = new JsonSlurper().parseText(request.effectiveOptionsJson)
        Map returned = new JsonSlurper().parseText(response.optionsJson)
        Map usage = new JsonSlurper().parseText(response.usageJson)
        then:
        requested == [prompt_cache_key: 'k1', max_tool_calls: null]
        and: 'an explicit null is a value: it is on the wire and in the stored body'
        provider.requests[0].body.contains('"max_tool_calls":null')
        request.requestPayloadJson.contains('"max_tool_calls":null')
        effective.containsKey('max_tool_calls')
        effective.prompt_cache_key == 'k1'
        effective.store == false
        effective.stream == false
        and: 'what the provider echoed keeps its null and default values'
        returned.containsKey('temperature')
        returned.temperature == null
        returned.top_p == 1.0
        returned.truncation == 'disabled'
        !returned.containsKey('prompt_cache_key')
        and: 'the usage is the provider object, the columns are copies'
        usage.input_tokens_details.cached_tokens == 3
        usage.output_tokens_details.reasoning_tokens == 0
        response.cachedInputTokens == 3
        when: 'the services return the raw authorized values, and a provider id is read for its owner only'
        def viaRequest = service('get#LlmRequest', [llmRequestId: request.llmRequestId]).request
        def viaResponse = service('get#LlmResponse', [providerResponseId: 'resp_opt_1', profileName: 'options-it']).response
        then:
        new JsonSlurper().parseText(viaRequest.effectiveOptionsJson).store == false
        viaRequest.requestPayloadJson == request.requestPayloadJson
        new JsonSlurper().parseText(viaResponse.optionsJson).top_p == 1.0
        new JsonSlurper().parseText(viaResponse.payloadJson).id == 'resp_opt_1'
        cleanup:
        provider.close()
        deleteStored('options-it')
    }

    def 'readable text is projected for search as it is stored, reasoning is not, and a backfill completes what was skipped'() {
        given:
        def provider = new ScriptedResponsesProvider()
        2.times { provider.enqueueJson(200, '{"id":"resp_fts_x","object":"response","status":"completed","output":[{"id":"rs_f","type":"reasoning","summary":[{"type":"summary_text","text":"secretsummaryword"}]},{"id":"msg_f","type":"message","role":"assistant","content":[{"type":"output_text","text":"zebrafish answer"}]}]}') }
        LlmItem reasoning = new LlmItem()
        reasoning.type = 'reasoning'
        reasoning.summary = [new LlmContentPart(type: 'summary_text', text: 'hiddenreasonword')]
        reasoning.encryptedContent = 'opaque'
        def profile = responsesProfile(provider.endpoint, false, new OpenResponsesProtocol(), 'fts-it')
        def client = new LlmClientImpl(ec, profile, { false })
        client.inputItems([LlmItem.message('user', [LlmContentPart.inputText('quokka question')]), reasoning,
                           new LlmItem(type: 'function_call', callId: 'call_f', name: 'lookup', arguments: '{}'),
                           LlmItem.functionCallOutput('call_f', [LlmContentPart.inputText('narwhal output')])])
        when: 'the call is stored with the projection on'
        callNoAuthz(client)
        def found = { String q -> service('search#LlmContext', [query: q]).results }
        then:
        found('quokka').size() == 1
        found('narwhal').size() == 1
        found('zebrafish').size() == 1
        found('hiddenreasonword').isEmpty()
        found('secretsummaryword').isEmpty()
        found('quokka')[0].sourceType == 'content'
        found('quokka')[0].userId == USER_ID
        when: 'the projection is switched off, a second call is stored, and then it is backfilled twice'
        System.setProperty('moqui.llm.projection', 'false')
        def second = new LlmClientImpl(ec, profile, { false })
        second.inputItems([LlmItem.message('user', [LlmContentPart.inputText('pangolin question')])])
        try { callNoAuthz(second) } finally { System.clearProperty('moqui.llm.projection') }
        then:
        found('pangolin').isEmpty()
        when:
        def backfillAll = {
            long written = 0
            String after = null
            boolean more = true
            while (more) {
                Map page = service('backfill#LlmContextProjection', [batchSize: 1000, afterLlmContentId: after])
                written += (page.written ?: 0) as long
                more = page.more as boolean
                after = page.lastLlmContentId
            }
            [written: written, more: false]
        }
        Map first = backfillAll()
        Map again = backfillAll()
        then:
        found('pangolin').size() == 1
        found('quokka').size() == 1
        first.written >= 1
        again.written == 0 || again.more
        cleanup:
        provider.close()
        boolean off = ec.artifactExecution.disableAuthz()
        try {
            ec.transaction.runUseOrBegin(null, 'projection cleanup failed') {
                ec.entity.find('moqui.llm.LlmContextProjection').condition('userId', USER_ID).condition('sourceType', 'content').deleteAll()
            }
        } finally { if (!off) ec.artifactExecution.enableAuthz() }
        deleteStored('fts-it')
    }

    def 'retention removes the searchable copy together with the content'() {
        given:
        def provider = new ScriptedResponsesProvider()
        provider.enqueueJson(200, '{"id":"resp_fts_c","object":"response","status":"completed","output":[{"id":"msg_c","type":"message","role":"assistant","content":[{"type":"output_text","text":"axolotl answer"}]}]}')
        def client = new LlmClientImpl(ec, responsesProfile(provider.endpoint, false, new OpenResponsesProtocol(), 'fts-clean-it'), { false })
        client.inputItems([LlmItem.message('user', [LlmContentPart.inputText('capybara question')])])
        callNoAuthz(client)
        boolean off = ec.artifactExecution.disableAuthz()
        try {
            ec.transaction.runUseOrBegin(null, 'age failed') {
                Timestamp old = Timestamp.valueOf('2000-01-01 00:00:00')
                ec.entity.find('moqui.llm.LlmResponse').condition('profileName', 'fts-clean-it').updateAll([createdDate: old])
                ec.entity.find('moqui.llm.LlmRequest').condition('profileName', 'fts-clean-it').updateAll([createdDate: old])
                ec.entity.find('moqui.llm.LlmRun').condition('profileName', 'fts-clean-it').updateAll([completedDate: old])
            }
        } finally { if (!off) ec.artifactExecution.enableAuthz() }
        expect:
        service('search#LlmContext', [query: 'capybara']).results.size() == 1
        when:
        service('clean#LlmData', [daysToKeep: 3650])
        then:
        service('search#LlmContext', [query: 'capybara']).results.isEmpty()
        service('search#LlmContext', [query: 'axolotl']).results.isEmpty()
        cleanup:
        provider.close()
        deleteStored('fts-clean-it')
    }

    def 'replay provenance names its source, or says why it has none, and never picks among equals'() {
        given:
        def provider = new ScriptedResponsesProvider()
        provider.enqueueJson(200, '{"id":"resp_pv_1","object":"response","status":"completed","output":[{"id":"msg_dup","type":"message","role":"assistant","content":[{"type":"output_text","text":"one"}]},{"id":"fc_one","type":"function_call","call_id":"c1","name":"x","arguments":"{}"}]}')
        provider.enqueueJson(200, '{"id":"resp_pv_2","object":"response","status":"completed","output":[{"id":"msg_dup","type":"message","role":"assistant","content":[{"type":"output_text","text":"two"}]}]}')
        provider.enqueueJson(200, '{"id":"resp_pv_3","object":"response","status":"completed","output":[{"id":"msg_3","type":"message","role":"assistant","content":[{"type":"output_text","text":"three"}]}]}')
        def profile = responsesProfile(provider.endpoint, false, new OpenResponsesProtocol(), 'prov-it')
        [ 'a', 'b' ].each { String t ->
            def c = new LlmClientImpl(ec, profile, { false })
            c.inputItems([LlmItem.message('user', [LlmContentPart.inputText(t)])])
            callNoAuthz(c)
        }
        LlmItem ambiguous = LlmItem.message('assistant', [new LlmContentPart(type: 'output_text', text: 'x')])
        ambiguous.providerItemId = 'msg_dup'
        LlmItem unique = new LlmItem()
        unique.type = 'function_call'
        unique.providerItemId = 'fc_one'
        unique.callId = 'c1'
        unique.name = 'x'
        unique.arguments = '{}'
        LlmItem wrongType = LlmItem.message('assistant', [new LlmContentPart(type: 'output_text', text: 'y')])
        wrongType.providerItemId = 'fc_one'
        LlmItem none = LlmItem.message('assistant', [new LlmContentPart(type: 'output_text', text: 'z')])
        LlmItem unknown = LlmItem.message('assistant', [new LlmContentPart(type: 'output_text', text: 'w')])
        unknown.providerItemId = 'msg_nowhere'
        def third = new LlmClientImpl(ec, profile, { false })
        third.inputItems([LlmItem.message('user', [LlmContentPart.inputText('c')]), ambiguous, unique, LlmItem.functionCallOutput('c1', 'ok'), wrongType, none, unknown])
        when:
        callNoAuthz(third)
        def request = ec.entity.find('moqui.llm.LlmRequest').condition('profileName', 'prov-it').orderBy('-createdDate').disableAuthz().list().first()
        def rows = ec.entity.find('moqui.llm.LlmItem').condition('llmRequestId', request.llmRequestId).orderBy('sequenceNum').disableAuthz().list()
        then:
        rows*.sourceStatus == [null, 'unresolved', 'matched', null, 'unmatched', null, 'unmatched']
        rows[1].sourceLlmItemId == null
        rows[2].sourceLlmItemId != null
        rows[2].providerItemId == 'fc_one'
        service('get#LlmRequest', [llmRequestId: request.llmRequestId]).request.items[1].sourceStatus == 'unresolved'
        cleanup:
        provider.close()
        deleteStored('prov-it')
    }

    def 'whoever writes an item, it belongs to one request or one response'() {
        given:
        String suffix = String.valueOf(System.nanoTime())
        def failures = []
        boolean off = ec.artifactExecution.disableAuthz()
        when:
        try {
            [[llmItemId: 'XOR1' + suffix, sequenceNum: 1, itemType: 'message'],
             [llmItemId: 'XOR2' + suffix, sequenceNum: 1, itemType: 'message', llmRequestId: 'R' + suffix, llmResponseId: 'S' + suffix],
             [llmItemId: 'XOR3' + suffix, sequenceNum: 1, itemType: 'message', llmResponseId: 'S' + suffix, sourceLlmItemId: 'Z' + suffix]].each { Map row ->
                try {
                    ec.transaction.runUseOrBegin(null, 'item create refused') { ec.entity.makeValue('moqui.llm.LlmItem').setAll(row).create() }
                    failures << "accepted ${row.llmItemId}"
                } catch (Throwable ignored) { }
            }
            ec.transaction.runUseOrBegin(null, 'item create failed') {
                ec.entity.makeValue('moqui.llm.LlmItem').setAll([llmItemId: 'XOR4' + suffix, sequenceNum: 1, itemType: 'message', llmRequestId: 'R' + suffix]).create()
            }
            try {
                ec.transaction.runUseOrBegin(null, 'item update refused') {
                    ec.entity.find('moqui.llm.LlmItem').condition('llmItemId', 'XOR4' + suffix).one().set('llmResponseId', 'S' + suffix).update()
                }
                failures << 'update accepted'
            } catch (Throwable ignored) { }
        } finally {
            ec.transaction.runUseOrBegin(null, 'item cleanup') { ec.entity.find('moqui.llm.LlmItem').condition('llmItemId', 'LIKE', 'XOR%' + suffix).deleteAll() }
            if (!off) ec.artifactExecution.enableAuthz()
        }
        then:
        failures == []
    }

    def 'event paging refuses a negative offset and a limit that is not allowed'() {
        given:
        def provider = new ScriptedResponsesProvider()
        provider.enqueueJson(200, '{"id":"resp_pg","object":"response","status":"completed","output":[{"id":"msg_pg","type":"message","role":"assistant","content":[{"type":"output_text","text":"page"}]}]}')
        def client = new LlmClientImpl(ec, responsesProfile(provider.endpoint, false, new OpenResponsesProtocol(), 'page-it'), { false })
        client.inputItems([LlmItem.message('user', [LlmContentPart.inputText('p')])])
        callNoAuthz(client)
        def stored = ec.entity.find('moqui.llm.LlmResponse').condition('profileName', 'page-it').disableAuthz().one()
        def hugeFailed = false
        def negativeRefused = false
        when:
        boolean off = ec.artifactExecution.disableAuthz()
        Map negative
        Map huge
        try {
            negative = ec.service.sync().name('org.moqui.impl.LlmServices.get#LlmResponse').parameters([llmResponseId: stored.llmResponseId, includeEvents: true, eventOffset: -1]).call()
            boolean negativeFailed = ec.message.hasError()
            ec.message.clearErrors()
            huge = ec.service.sync().name('org.moqui.impl.LlmServices.get#LlmResponse').parameters([llmResponseId: stored.llmResponseId, includeEvents: true, eventLimit: 1001]).call()
            hugeFailed = ec.message.hasError()
            ec.message.clearErrors()
            negativeRefused = negativeFailed
        } finally { if (!off) ec.artifactExecution.enableAuthz() }
        then:
        negativeRefused
        hugeFailed
        cleanup:
        provider.close()
        deleteStored('page-it')
    }

    def 'a null option reaches the WebSocket frame as a null'() {
        given:
        def fake = new ResponsesWebSocketFake()
        def client = new LlmClientImpl(ec, responsesProfile(fake.endpoint, false, new OpenResponsesProtocol(), 'ws-null'), { false })
        client.transport(LlmTransport.WEBSOCKET)
        client.inputItems([LlmItem.message('user', [LlmContentPart.inputText('hi')])])
                .responseOptions(new LlmResponseOptions().put('max_tool_calls', null))
        when:
        callNoAuthz(client)
        then:
        fake.creates[0].body.containsKey('max_tool_calls')
        fake.creates[0].body.max_tool_calls == null
        cleanup:
        fake.close()
        deleteStored('ws-null')
    }

    def 'what is stored about the options does not change when the caller changes its own copy afterwards'() {
        given:
        def provider = new ScriptedResponsesProvider()
        provider.enqueueJson(200, '{"id":"resp_snap","object":"response","status":"completed","output":[{"id":"msg_sn","type":"message","role":"assistant","content":[{"type":"output_text","text":"snap"}]}]}')
        def options = new LlmResponseOptions().put('metadata', [k: 'v'])
        def client = new LlmClientImpl(ec, responsesProfile(provider.endpoint, false, new OpenResponsesProtocol(), 'snap-it'), { false })
        client.inputItems([LlmItem.message('user', [LlmContentPart.inputText('s')])]).responseOptions(options)
        when:
        callNoAuthz(client)
        options.put('metadata', [k: 'changed'])
        def request = ec.entity.find('moqui.llm.LlmRequest').condition('profileName', 'snap-it').disableAuthz().one()
        then:
        new JsonSlurper().parseText(request.requestOptionsJson).metadata == [k: 'v']
        new JsonSlurper().parseText(request.effectiveOptionsJson).metadata == [k: 'v']
        cleanup:
        provider.close()
        deleteStored('snap-it')
    }

    private static LlmTool searchTool(Closure body) {
        new LlmTool() {
            String getName() { 'service_search' }
            String getDescription() { 'search' }
            Map<String, Object> getParametersSchema() { [type: 'object', properties: [query: [type: 'string']], required: ['query']] }
            LlmTool.Execution getExecution() { LlmTool.Execution.SERVER }
            Object execute(Map<String, Object> arguments, ExecutionContext executionContext) { body.call(arguments) }
        }
    }

    private LlmClientImpl wsRunClient(ResponsesWebSocketFake fake, OpenResponsesProtocol protocol, String name, Closure toolBody) {
        def client = new LlmClientImpl(ec, responsesProfile(fake.endpoint, false, protocol, name), { false })
        client.transport(LlmTransport.WEBSOCKET)
        client.newConversation().user('search alpha').tool(searchTool(toolBody))
        client
    }

    private static LlmProtocol.ProtocolRequest wsRequest(String endpoint, String key, String previous = null, String text = 'hi') {
        def r = new LlmProtocol.ProtocolRequest()
        r.profileName = 'ws-proto'
        r.endpointUrl = endpoint
        r.model = 'gpt-test'
        r.transport = LlmTransport.WEBSOCKET
        r.sessionKey = key
        r.timeoutSeconds = 5
        r.inputItems = [LlmItem.message('user', [LlmContentPart.inputText(text)])]
        r.previousResponseId = previous
        r
    }

    def 'two turns of a store=false run continue on one connection with only the new items'() {
        given:
        def fake = new ResponsesWebSocketFake()
        fake.toolTurns = 1
        def client = wsRunClient(fake, new OpenResponsesProtocol(), 'ws-continue', { args -> [results: []] })
        when:
        LlmResponse response = callNoAuthz(client)
        then:
        response.content == 'answer 2'
        fake.connections.size() == 1
        fake.creates.size() == 2
        fake.creates[0].body.type == 'response.create'
        fake.creates[0].body.previous_response_id == null
        fake.creates[0].body.input*.type == ['message']
        fake.creates[1].body.previous_response_id == 'resp_ws_1'
        fake.creates[1].body.input*.type == ['function_call_output']
        fake.creates[1].body.store == false
        and: 'the connection of a conversation is kept for its next turn, and released when the conversation ends'
        !fake.connections[0].closed
        when:
        client.profile.protocol.closeSessionsOfScope('conv:' + client.conversation.conversationId)
        then:
        fake.waitUntil(3000) { fake.connections[0].closed }
        cleanup:
        fake.close()
        deleteStored('ws-continue')
    }

    def 'a connection lost between turns starts a new chain from the full local context'() {
        given:
        def fake = new ResponsesWebSocketFake()
        fake.toolTurns = 1
        fake.closeAfterTurn << 0
        def client = wsRunClient(fake, new OpenResponsesProtocol(), 'ws-lost-between', { args ->
            fake.waitUntil(3000) { fake.connections[0].closed }
            Thread.sleep(300)
            [results: []]
        })
        when:
        LlmResponse response = callNoAuthz(client)
        then:
        response.content == 'answer 2'
        fake.connections.size() == 2
        fake.creates[1].connection == 1
        fake.creates[1].body.previous_response_id == null
        fake.creates[1].body.input*.type == ['message', 'function_call', 'function_call_output']
        cleanup:
        fake.close()
        deleteStored('ws-lost-between')
    }

    def 'a request in flight on a connection that is lost is uncertain and is never sent again'() {
        given:
        def fake = new ResponsesWebSocketFake()
        fake.dropNext.set(1)
        def client = new LlmClientImpl(ec, responsesProfile(fake.endpoint, false, new OpenResponsesProtocol(), 'ws-in-flight'), { false })
        client.transport(LlmTransport.WEBSOCKET)
        client.newConversation().user('hello')
        when:
        callNoAuthz(client)
        then:
        thrown(LlmException)
        when:
        Thread.sleep(300)
        def request = ec.entity.find('moqui.llm.LlmRequest').condition('profileName', 'ws-in-flight').disableAuthz().one()
        then:
        fake.creates.size() == 1
        request.localStatusEnumId == 'LlmReqUncertain'
        cleanup:
        fake.close()
        deleteStored('ws-in-flight')
    }

    def 'a second turn on a busy connection is refused and the first turn is not disturbed'() {
        given:
        def fake = new ResponsesWebSocketFake()
        fake.hold = new CountDownLatch(1)
        def protocol = new OpenResponsesProtocol()
        def first = null
        Thread worker = Thread.start { first = protocol.chat(wsRequest(fake.endpoint, 'k-busy')) }
        assert fake.waitUntil(3000) { fake.creates.size() == 1 }
        when:
        protocol.chat(wsRequest(fake.endpoint, 'k-busy'))
        then:
        def busy = thrown(LlmException)
        busy.message.contains('busy')
        when:
        fake.hold.countDown()
        worker.join(5000)
        then:
        first.responseId == 'resp_ws_1'
        fake.connections.size() == 1
        fake.creates.size() == 1
        cleanup:
        protocol.close()
        fake.close()
    }

    def 'the error envelope keeps status code type and param, and the failed continuation discards the connection'() {
        given:
        def fake = new ResponsesWebSocketFake()
        def protocol = new OpenResponsesProtocol()
        when:
        def first = protocol.chat(wsRequest(fake.endpoint, 'k-error'))
        then:
        first.responseId == 'resp_ws_1'
        protocol.continuesOnConnection('k-error', 'resp_ws_1')
        when:
        def failed = protocol.chat(wsRequest(fake.endpoint, 'k-error', 'resp_nope'))
        then:
        failed.finishReason == LlmFinishReason.ERROR
        failed.httpStatus == 404
        failed.providerErrorCode == 'previous_response_not_found'
        failed.providerErrorType == 'invalid_request_error'
        failed.providerErrorParam == 'previous_response_id'
        failed.responsePayload.status == 404
        !protocol.continuesOnConnection('k-error', 'resp_ws_1')
        when:
        def again = protocol.chat(wsRequest(fake.endpoint, 'k-error'))
        then:
        again.responseId == 'resp_ws_2'
        fake.connections.size() == 2
        cleanup:
        protocol.close()
        fake.close()
    }

    def 'parallel sessions use separate connections and a session without a key never outlives its turn'() {
        given:
        def fake = new ResponsesWebSocketFake()
        def protocol = new OpenResponsesProtocol()
        when:
        def results = ['a', 'b'].collect { String key -> Thread.start { protocol.chat(wsRequest(fake.endpoint, "k-par-${key}")) } }
        results*.join(5000)
        then:
        fake.connections.size() == 2
        fake.creates*.connection.toSet() == [0, 1].toSet()
        when:
        protocol.chat(wsRequest(fake.endpoint, null))
        then:
        fake.connections.size() == 3
        fake.waitUntil(3000) { fake.connections[2].closed }
        cleanup:
        protocol.close()
        fake.close()
    }

    def 'cancelling a turn aborts the connection and the session starts clean afterwards'() {
        given:
        def fake = new ResponsesWebSocketFake()
        fake.hold = new CountDownLatch(1)
        def protocol = new OpenResponsesProtocol()
        def stream = null
        def failure = null
        def request = wsRequest(fake.endpoint, 'k-cancel')
        request.onStreamOpen = { s -> stream = s }
        Thread worker = Thread.start { try { protocol.chat(request) } catch (Throwable t) { failure = t } }
        assert fake.waitUntil(3000) { fake.creates.size() == 1 && stream != null }
        when:
        stream.close()
        worker.join(5000)
        then:
        failure != null
        !protocol.continuesOnConnection('k-cancel', 'resp_ws_1')
        when: 'the provider, which was holding the answer, finds the connection gone'
        fake.hold.countDown()
        fake.hold = null
        then:
        fake.waitUntil(3000) { fake.connections[0].closed }
        when:
        def next = protocol.chat(wsRequest(fake.endpoint, 'k-cancel'))
        then:
        next.responseId != null
        fake.connections.size() == 2
        cleanup:
        protocol.close()
        fake.close()
    }

    def 'an idle connection expires and closing the protocol releases every connection'() {
        given:
        def fake = new ResponsesWebSocketFake()
        def protocol = new OpenResponsesProtocol()
        protocol.sessionIdleMillis = 50
        when:
        def first = protocol.chat(wsRequest(fake.endpoint, 'k-idle'))
        Thread.sleep(200)
        then:
        !protocol.continuesOnConnection('k-idle', first.responseId)
        when:
        def second = protocol.chat(wsRequest(fake.endpoint, 'k-idle'))
        then:
        fake.connections.size() == 2
        fake.waitUntil(3000) { fake.connections[0].closed }
        when:
        protocol.sessionIdleMillis = 60000
        protocol.close()
        then:
        fake.waitUntil(3000) { fake.connections[1].closed }
        cleanup:
        protocol.close()
        fake.close()
    }

    /** A profile that is allowed to retry (timeouts, rate limits, server errors) and waits 1 second for an answer. */
    private static LlmFacadeImpl.ProfileState retryingProfile(String endpoint, String name, LlmProtocol protocol = new OpenResponsesProtocol()) {
        new LlmFacadeImpl.ProfileState(name, null, 'http://127.0.0.1', '/v1/responses', endpoint,
                '', 'Authorization', null, 'gpt-test', 'max_tokens', false, 1, 0.01f, 3, true, 3,
                WindowPolicy.ContextLimitPolicy.FAIL, null, null, false, Collections.emptyMap(),
                Collections.emptyMap(), null, protocol, Collections.emptySet(),
                Collections.emptyList(), false, 15, null, true, false, false, false, false, false,
                false, Collections.emptyList())
    }

    private LlmClientImpl retryingClient(ScriptedResponsesProvider provider, String name) {
        def client = new LlmClientImpl(ec, retryingProfile(provider.endpoint, name), { false })
        client.inputItems([LlmItem.message('user', [LlmContentPart.inputText('create once')])])
        client
    }

    private String requestStatus(String profileName) {
        ec.entity.find('moqui.llm.LlmRequest').condition('profileName', profileName).disableAuthz().list().last().localStatusEnumId
    }

    private static String OK_BODY = '{"id":"resp_retry_ok","object":"response","status":"completed","output":[{"id":"msg_r","type":"message","role":"assistant","content":[{"type":"output_text","text":"fine"}]}]}'

    def 'a create that may have been accepted is never sent again, whichever way the answer is lost'() {
        given:
        def provider = new ScriptedResponsesProvider()
        scenario(provider)
        def client = retryingClient(provider, 'retry-uncertain')
        when:
        callNoAuthz(client)
        then:
        thrown(LlmException)
        provider.requests.size() == 1
        requestStatus('retry-uncertain') == 'LlmReqUncertain'
        cleanup:
        provider.close()
        deleteStored('retry-uncertain')
        where:
        scenario << [{ p -> p.enqueueStall(2500); p.enqueueJson(200, OK_BODY) },
                     { p -> p.enqueueHangUp(); p.enqueueJson(200, OK_BODY) }]
    }

    def 'a streamed create that times out is not sent again either'() {
        given:
        def provider = new ScriptedResponsesProvider()
        provider.enqueueStall(2500)
        provider.enqueueSse(['data: [DONE]\n\n'])
        def client = retryingClient(provider, 'retry-stream')
        when:
        boolean off = ec.artifactExecution.disableAuthz()
        try { client.stream(new CaptureListener()) } finally { if (!off) ec.artifactExecution.enableAuthz() }
        then:
        thrown(LlmException)
        provider.requests.size() == 1
        requestStatus('retry-stream') == 'LlmReqUncertain'
        cleanup:
        provider.close()
        deleteStored('retry-stream')
    }

    def 'a rate limit is retried, a server error is reported and not repeated'() {
        given:
        def limited = new ScriptedResponsesProvider()
        limited.enqueueJson(429, '{"error":{"code":"rate_limit_exceeded","message":"slow down"}}')
        limited.enqueueJson(200, OK_BODY)
        def broken = new ScriptedResponsesProvider()
        broken.enqueueJson(503, '{"error":{"code":"server_error","message":"overloaded"}}')
        broken.enqueueJson(200, OK_BODY)
        when:
        LlmResponse first = callNoAuthz(retryingClient(limited, 'retry-429'))
        then:
        first.responseId == 'resp_retry_ok'
        limited.requests.size() == 2
        when:
        callNoAuthz(retryingClient(broken, 'retry-503'))
        then:
        thrown(LlmException)
        broken.requests.size() == 1
        requestStatus('retry-503') == 'LlmReqFailed'
        cleanup:
        limited.close()
        broken.close()
        deleteStored('retry-429')
        deleteStored('retry-503')
    }

    def 'a connection that cannot be opened is a failed request, certainly not sent'() {
        given:
        def provider = new ScriptedResponsesProvider()
        String endpoint = provider.endpoint
        provider.close()
        def client = new LlmClientImpl(ec, retryingProfile(endpoint, 'retry-refused'), { false })
        client.inputItems([LlmItem.message('user', [LlmContentPart.inputText('nobody home')])])
        when:
        callNoAuthz(client)
        then:
        thrown(LlmException)
        requestStatus('retry-refused') == 'LlmReqFailed'
        cleanup:
        deleteStored('retry-refused')
    }

    private static LlmFacadeImpl.ProfileState responsesProfile(String endpoint, boolean logContent) {
        responsesProfile(endpoint, logContent, new OpenResponsesProtocol(), 'responses-it')
    }

    private static LlmFacadeImpl.ProfileState responsesProfile(String endpoint, boolean logContent,
            LlmProtocol protocol, String name) {
        new LlmFacadeImpl.ProfileState(name, null, 'http://127.0.0.1', '/v1/responses', endpoint,
                '', 'Authorization', null, 'gpt-test', 'max_tokens', false, 10, 0f, 0, false, 0,
                WindowPolicy.ContextLimitPolicy.FAIL, null, null, logContent, Collections.emptyMap(),
                Collections.emptyMap(), null, protocol, Collections.emptySet(),
                Collections.emptyList(), false, 15, null, true, false, false, false, false, false,
                false, Collections.emptyList())
    }

    private void ensureEnum(String enumTypeId, String description) {
        ec.entity.makeValue('moqui.basic.EnumerationType')
                .setAll([enumTypeId: enumTypeId, description: description]).createOrUpdate()
    }

    static class CaptureListener implements LlmStreamListener {
        final CountDownLatch completed = new CountDownLatch(1)
        final List<String> deltas = []
        final List events = []
        Throwable failure
        void onDelta(String textDelta) { deltas << textDelta }
        void onEvent(org.moqui.llm.LlmResponseEvent event) { events << event }
        void onComplete(LlmResponse response) { completed.countDown() }
        void onFailure(Throwable t) { failure = t; completed.countDown() }
        void onError(org.moqui.llm.LlmException error) { failure = error; completed.countDown() }
    }

    static class ScriptedResponsesProvider implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        final List<Map> requests = Collections.synchronizedList([])
        final Queue<Map> responses = new ArrayDeque<>()
        final String endpoint

        ScriptedResponsesProvider() {
            endpoint = "http://127.0.0.1:${server.address.port}/v1/responses"
            server.createContext('/v1/responses') { exchange ->
                String body = exchange.requestBody.getText('UTF-8')
                Map request = [method: exchange.requestMethod, path: exchange.requestURI.path, body: body,
                        json: body ? new JsonSlurper().parseText(body) : [:]]
                requests << request
                Map next = responses.poll()
                if (next != null && next.stall) {
                    // accepted and read, then no answer until the client has given up
                    Thread.sleep(next.stall as long)
                    exchange.close()
                    return
                }
                if (next != null && next.hangUp) {
                    // accepted and read, then dropped without any answer: the outcome is unknown to the client
                    exchange.close()
                    return
                }
                if (next == null) {
                    byte[] bytes = '{"error":{"message":"unexpected request"}}'.getBytes('UTF-8')
                    exchange.responseHeaders.set('Content-Type', 'application/json')
                    exchange.sendResponseHeaders(500, bytes.length)
                    exchange.responseBody.withCloseable { it.write(bytes) }
                    return
                }
                if (next.sse) {
                    exchange.responseHeaders.set('Content-Type', 'text/event-stream')
                    exchange.sendResponseHeaders(next.status as int, 0)
                    exchange.responseBody.withCloseable { out ->
                        next.frames.each { String frame -> writeFragmented(out, frame.getBytes('UTF-8')) }
                    }
                } else {
                    byte[] bytes = (next.body as String).getBytes('UTF-8')
                    exchange.responseHeaders.set('Content-Type', next.contentType as String ?: 'application/json')
                    exchange.sendResponseHeaders(next.status as int, bytes.length)
                    exchange.responseBody.withCloseable { it.write(bytes) }
                }
            }
            server.start()
        }

        void enqueueJson(int status, String body) { responses.add([status: status, body: body, sse: false, contentType: 'application/json']) }
        void enqueueBody(int status, String body, String contentType) {
            responses.add([status: status, body: body, sse: false, contentType: contentType])
        }
        void enqueueHangUp() { responses.add([hangUp: true]) }
        void enqueueStall(long millis) { responses.add([stall: millis]) }
        void enqueueSse(List<String> frames) { responses.add([status: 200, frames: frames, sse: true]) }

        private static void writeFragmented(OutputStream out, byte[] bytes) {
            for (byte value : bytes) {
                out.write([value] as byte[])
                out.flush()
            }
        }

        void close() { server.stop(0) }
    }

    static class ScriptedWebSocketProvider implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getByName('127.0.0.1'))
        final int port = server.localPort
        final String endpoint = "http://127.0.0.1:${port}/v1/responses"
        final CountDownLatch finished = new CountDownLatch(1)
        final List<String> messages
        volatile String receivedText
        volatile Map receivedJson
        volatile Throwable failure
        final Thread thread

        ScriptedWebSocketProvider(List<String> messages) {
            this.messages = messages
            thread = Thread.startDaemon('llm-responses-client-websocket-test') {
                try {
                    server.accept().withCloseable { socket ->
                        socket.soTimeout = 1000
                        InputStream input = socket.inputStream
                        OutputStream output = socket.outputStream
                        String headers = readHeaders(input)
                        String key = headers.readLines().find { it.toLowerCase().startsWith('sec-websocket-key:') }
                                ?.substring('sec-websocket-key:'.length())?.trim()
                        String accept = Base64.encoder.encodeToString(MessageDigest.getInstance('SHA-1')
                                .digest((key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').getBytes('UTF-8')))
                        output.write(("HTTP/1.1 101 Switching Protocols\r\n" +
                                "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                                "Sec-WebSocket-Accept: ${accept}\r\n\r\n").getBytes('UTF-8'))
                        output.flush()
                        receivedText = readFrame(input)
                        receivedJson = receivedText ? new JsonSlurper().parseText(receivedText) : [:]
                        messages.each { String message -> writeFragmentedTextFrame(output, message) }
                        try { readFrame(input) } catch (Throwable ignored) { }
                        writeFrame(output, true, 0x8, new byte[0])
                        output.flush()
                    }
                } catch (Throwable t) {
                    failure = t
                } finally {
                    finished.countDown()
                }
            }
        }

        private static String readHeaders(InputStream input) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream()
            int matched = 0
            List<Integer> terminator = [13, 10, 13, 10]
            while (matched < terminator.size()) {
                int value = input.read()
                if (value < 0) throw new EOFException('WebSocket handshake ended early')
                bytes.write(value)
                matched = value == terminator[matched] ? matched + 1 : (value == 13 ? 1 : 0)
            }
            bytes.toString('UTF-8')
        }

        private static String readFrame(InputStream input) {
            int first = input.read()
            int second = input.read()
            if (first < 0 || second < 0) throw new EOFException('WebSocket frame ended early')
            long length = second & 0x7f
            if (length == 126) length = (input.read() << 8) | input.read()
            else if (length == 127) {
                length = 0
                8.times { length = (length << 8) | input.read() }
            }
            byte[] mask = new byte[4]
            if ((second & 0x80) != 0) input.readNBytes(mask, 0, 4)
            byte[] payload = input.readNBytes((int) length)
            if ((second & 0x80) != 0) for (int i = 0; i < payload.length; i++) payload[i] = (byte) (payload[i] ^ mask[i % 4])
            new String(payload, 'UTF-8')
        }

        private static void writeFragmentedTextFrame(OutputStream output, String text) {
            byte[] payload = text.getBytes('UTF-8')
            int splitAt = Math.max(1, payload.length / 2)
            writeFrame(output, false, 0x1, Arrays.copyOfRange(payload, 0, splitAt))
            writeFrame(output, true, 0x0, Arrays.copyOfRange(payload, splitAt, payload.length))
            output.flush()
        }

        private static void writeFrame(OutputStream output, boolean last, int opcode, byte[] payload) {
            output.write((last ? 0x80 : 0) | opcode)
            if (payload.length < 126) output.write(payload.length)
            else {
                output.write(126)
                output.write((payload.length >>> 8) & 0xff)
                output.write(payload.length & 0xff)
            }
            output.write(payload)
        }

        @Override void close() {
            try { server.close() } catch (Throwable ignored) { }
            thread.join(1000)
        }
    }
}
