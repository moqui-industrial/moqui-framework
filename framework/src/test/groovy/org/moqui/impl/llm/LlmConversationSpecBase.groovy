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
import java.lang.reflect.Field
import spock.lang.Shared
import spock.lang.Specification

/** What the conversation tests of the Open Responses model share: a user, the enumerations, a scripted provider and helpers. */
abstract class LlmConversationSpecBase extends Specification {
    static final String USER_ID = 'LLMCONVTEST'
    static final String USERNAME = 'llm.conv.test'

    @Shared ExecutionContext ec

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        boolean disabled = ec.artifactExecution.disableAuthz()
        try {
            ec.transaction.runUseOrBegin(null, 'LLM conversation test user failed') {
                ec.entity.makeValue('moqui.security.UserAccount')
                        .setAll([userId: USER_ID, username: USERNAME, userFullName: 'LLM Conversation Test']).createOrUpdate()
                [LlmRunStatus: 'LLM Run Status', LlmContinuationMode: 'LLM Continuation Mode', LlmToolInvocationStatus: 'x',
                 LlmConversationStatus: 'x', LlmRequestStatus: 'x', LlmContentPurpose: 'x', LlmTransport: 'x'].each { type, d ->
                    ec.entity.makeValue('moqui.basic.EnumerationType').setAll([enumTypeId: type, description: d]).createOrUpdate()
                }
                [LlmRunQueued: 'LlmRunStatus', LlmRunRunning: 'LlmRunStatus', LlmRunWaitClient: 'LlmRunStatus',
                 LlmRunWaitConfirm: 'LlmRunStatus', LlmRunWaitProvider: 'LlmRunStatus', LlmRunRecovering: 'LlmRunStatus', LlmRunComplete: 'LlmRunStatus',
                 LlmRunFailed: 'LlmRunStatus', LlmRunCancelled: 'LlmRunStatus', LlmContLocal: 'LlmContinuationMode',
                 LlmContRemote: 'LlmContinuationMode', LlmTiPlanned: 'LlmToolInvocationStatus', LlmTiRunning: 'LlmToolInvocationStatus',
                 LlmTiComplete: 'LlmToolInvocationStatus', LlmTiFailed: 'LlmToolInvocationStatus', LlmTiUncertain: 'LlmToolInvocationStatus',
                 LlmTiCancelled: 'LlmToolInvocationStatus', LlmcsActive: 'LlmConversationStatus', LlmcsStreaming: 'LlmConversationStatus',
                 LlmcsYielded: 'LlmConversationStatus', LlmcsComplete: 'LlmConversationStatus', LlmcsFailed: 'LlmConversationStatus',
                 LlmcsCancelled: 'LlmConversationStatus', LlmReqPrepared: 'LlmRequestStatus', LlmReqSending: 'LlmRequestStatus',
                 LlmReqAck: 'LlmRequestStatus', LlmReqFailed: 'LlmRequestStatus', LlmReqUncertain: 'LlmRequestStatus',
                 LlmCpUser: 'LlmContentPurpose', LlmCpAssistant: 'LlmContentPurpose', LlmTrHttp: 'LlmTransport',
                 LlmTrSse: 'LlmTransport', LlmTrWebSocket: 'LlmTransport'].each { enumId, type ->
                    ec.entity.makeValue('moqui.basic.Enumeration').setAll([enumId: enumId, enumTypeId: type, description: enumId]).createOrUpdate()
                }
            }
        } finally { if (!disabled) ec.artifactExecution.enableAuthz() }
        assert ((UserFacadeImpl) ec.user).internalLoginUser(USERNAME, false)
        // the suite shares one hit cache with the 30/60s LlmProfiles tarpit on the profile name
        ec.artifactExecution.disableTarpit()
    }
    def cleanupSpec() {
        if (ec != null) {
            ec.artifactExecution.enableTarpit()
            ec.user.logoutUser()
            ec.destroy()
        }
    }

    protected static LlmFacadeImpl.ProfileState profile(String endpoint, String name = 'responses-conv') {
        profile(endpoint, name, new OpenResponsesProtocol())
    }
    protected static LlmFacadeImpl.ProfileState profile(String endpoint, String name, org.moqui.llm.LlmProtocol protocol) {
        new LlmFacadeImpl.ProfileState(name, null, 'http://127.0.0.1', '/v1/responses', endpoint,
                '', 'Authorization', null, 'gpt-test', 'max_tokens', false, 10, 0f, 0, false, 0,
                WindowPolicy.ContextLimitPolicy.FAIL, null, null, false, Collections.emptyMap(),
                Collections.emptyMap(), null, protocol, Collections.emptySet(),
                Collections.emptyList(), false, 15, null, true, false, false, false, false, false,
                false, Collections.emptyList())
    }
    protected static String answer(String id, String text) {
        '{"id":"' + id + '","object":"response","status":"completed","model":"gpt-test","output":[{"id":"msg_' + id + '","type":"message","role":"assistant","content":[{"type":"output_text","text":"' + text + '"}]}]}'
    }

    protected static final String PNG = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='

    protected static LlmContentPart image(String uri) {
        LlmContentPart part = new LlmContentPart()
        part.type = 'input_image'
        part.imageUrl = uri
        part.detail = 'low'
        part
    }

    protected static String toolCallResponse(String id, String callId, String name, String args) {
        '{"id":"' + id + '","object":"response","status":"completed","model":"gpt-test","output":[{"id":"fc_' + id + '","type":"function_call","call_id":"' + callId +
                '","name":"' + name + '","arguments":' + groovy.json.JsonOutput.toJson(args) + ',"status":"completed"}]}'
    }

    protected LlmResponse turn(LlmFacadeImpl.ProfileState p, LlmConversationImpl conv, Closure config) {
        LlmClientImpl client = new LlmClientImpl(ec, p, { false })
        client.conversation(conv)
        config.call(client)
        boolean was = ec.artifactExecution.disableAuthz()
        try { return client.call() } finally { if (!was) ec.artifactExecution.enableAuthz() }
    }

    protected long rowsOf(String conversationId) {
        ec.entity.find('moqui.llm.LlmMessage').condition('conversationId', conversationId).disableAuthz().count()
    }

    /** Runs the work in another thread, so with another ExecutionContext: nothing of the first one is shared. */
    protected Object inNewEc(String username, Closure work) {
        Object[] box = new Object[2]
        Thread t = Thread.start {
            ExecutionContext other = Moqui.getExecutionContext()
            try {
                assert ((UserFacadeImpl) other.user).internalLoginUser(username, false)
                other.artifactExecution.disableAuthz()
                other.artifactExecution.disableTarpit()
                box[0] = work.call(other)
            } catch (Throwable x) { box[1] = x }
            finally { other.destroy() }
        }
        t.join(60000)
        if (box[1] != null) throw (Throwable) box[1]
        box[0]
    }


    protected void register(LlmFacadeImpl.ProfileState state) {
        Field f = LlmFacadeImpl.getDeclaredField('profileByName')
        f.setAccessible(true)
        ((Map) f.get(ec.llm)).put(state.name, state)
    }
    protected void unregister(String name) {
        Field f = LlmFacadeImpl.getDeclaredField('profileByName')
        f.setAccessible(true)
        ((Map) f.get(ec.llm)).remove(name)
    }
}
