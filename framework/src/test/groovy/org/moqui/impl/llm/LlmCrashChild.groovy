package org.moqui.impl.llm

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.UserFacadeImpl
import org.moqui.llm.LlmFinishReason
import org.moqui.llm.LlmItem
import org.moqui.llm.LlmProtocol
import org.moqui.llm.LlmTool
import org.moqui.llm.LlmToolCall
import org.moqui.llm.WindowPolicy

import java.nio.file.Files
import java.nio.file.Paths

final class LlmCrashChild {
    static final String USER_ID = 'LLMCRASHTOOL'
    static final String USERNAME = 'llm.crash.tool'

    static void main(String[] args) {
        if (args.length < 2) throw new IllegalArgumentException('endpoint and ledger path are required')
        ExecutionContext ec = Moqui.getExecutionContext()
        try {
            boolean disabled = ec.artifactExecution.disableAuthz()
            try {
                ec.transaction.runUseOrBegin(null, 'LLM crash child setup failed') {
                    ec.entity.makeValue('moqui.security.UserAccount')
                            .setAll([userId: USER_ID, username: USERNAME, userFullName: 'LLM Crash Tool'])
                            .createOrUpdate()
                    ensureEnum(ec, 'LlmRunStatus', 'LLM Run Status')
                    ensureEnum(ec, 'LlmContinuationMode', 'LLM Continuation Mode')
                    ensureEnum(ec, 'LlmToolInvocationStatus', 'LLM Tool Invocation Status')
                    ensureEnum(ec, 'LlmConversationStatus', 'LLM Conversation Status')
                    ensureEnum(ec, 'LlmRequestStatus', 'LLM Request Status')
                    ensureEnum(ec, 'LlmContentPurpose', 'LLM Content Purpose')
                    ensureEnum(ec, 'LlmTransport', 'LLM Transport')
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
                     LlmCpAssistant: 'LlmContentPurpose', LlmTrHttp: 'LlmTransport',
                     LlmTrSse: 'LlmTransport', LlmTrWebSocket: 'LlmTransport'].each {
                        enumId, enumTypeId ->
                            ec.entity.makeValue('moqui.basic.Enumeration')
                                    .setAll([enumId: enumId, enumTypeId: enumTypeId, description: enumId])
                                    .createOrUpdate()
                    }
                }
            } finally {
                if (!disabled) ec.artifactExecution.enableAuthz()
            }
            assert ((UserFacadeImpl) ec.user).internalLoginUser(USERNAME, false)
            ec.artifactExecution.disableAuthz()
            // H2 writes a commit to disk after a delay of 500 ms by default, so a process that dies right after a commit can lose it,
            // which PostgreSQL does not. Make a commit durable here so that the check is about the client, not about H2's delay.
            ec.transaction.runUseOrBegin(null, 'durable commits failed') {
                try {
                    ec.entity.getConnection('transactional').with { con ->
                        if (con.metaData.databaseProductName.toLowerCase().contains('h2'))
                            con.createStatement().withCloseable { it.execute('SET WRITE_DELAY 0') }
                    }
                } catch (Throwable ignored) { }
            }
            LlmClientImpl client = new LlmClientImpl(ec, responsesProfile(args[0]), { false })
            client.newConversation().user('crash after external effect').tool(new LlmTool() {
                String getName() { 'crash_tool' }
                String getDescription() { 'Writes an external ledger and terminates the worker process.' }
                Map<String, Object> getParametersSchema() {
                    [type: 'object', properties: [ledger: [type: 'string']], required: ['ledger']]
                }
                LlmTool.Execution getExecution() { LlmTool.Execution.SERVER }
                Object execute(Map<String, Object> arguments, ExecutionContext executionContext) {
                    String runId = LlmAgentLoop.currentClient()?.activeRunId
                    Files.writeString(Paths.get(args[1]),
                            '{"externalEffect":"written","tool":"crash_tool","runId":"' + runId + '"}\n')
                    // halt, not exit: no shutdown hook runs, no transaction is committed, no connection is closed in order,
                    // the lease is not released; the process just stops, as it would on a kill
                    Runtime.getRuntime().halt(77)
                    return [ok: true]
                }
            }).call()
        } finally {
            try { ec.destroy() } catch (Throwable ignored) { }
        }
    }

    private static void ensureEnum(ExecutionContext ec, String enumTypeId, String description) {
        ec.entity.makeValue('moqui.basic.EnumerationType')
                .setAll([enumTypeId: enumTypeId, description: description]).createOrUpdate()
    }

    private static LlmFacadeImpl.ProfileState responsesProfile(String endpoint) {
        LlmProtocol protocol = endpoint == 'fake' ? new CrashToolProtocol() : new OpenResponsesProtocol()
        new LlmFacadeImpl.ProfileState('responses-crash-child', null, 'http://127.0.0.1', '/v1/responses', endpoint,
                '', 'Authorization', null, 'gpt-test', 'max_tokens', false, 10, 0f, 0, false, 0,
                WindowPolicy.ContextLimitPolicy.FAIL, null, null, true, Collections.emptyMap(),
                Collections.emptyMap(), null, protocol, Collections.emptySet(),
                Collections.emptyList(), false, 15, null, true, false, false, false, false, false,
                false, Collections.emptyList())
    }

    static final class CrashToolProtocol implements LlmProtocol {
        String getName() { 'crash-tool-protocol' }
        boolean supportsTools() { true }
        boolean supportsStreaming() { false }
        LlmProtocol.ProtocolResult chat(LlmProtocol.ProtocolRequest request) {
            LlmProtocol.ProtocolResult result = new LlmProtocol.ProtocolResult(LlmFinishReason.TOOL_CALLS)
            LlmToolCall call = new LlmToolCall('call_crash_1', 'crash_tool', '{"ledger":"write"}')
            result.responseId = 'resp_crash_tool'
            result.status = 'completed'
            result.model = request.model
            result.httpStatus = 200
            result.toolCalls = [call]
            result.outputItems = [LlmItem.functionCall(call.id, call.name, call.arguments)]
            result.responsePayload = [id: result.responseId, status: result.status, output: []]
            return result
        }
        void chatStream(LlmProtocol.ProtocolRequest request, LlmProtocol.ProtocolStreamListener listener) {
            throw new UnsupportedOperationException('streaming not used by crash child')
        }
    }
}
