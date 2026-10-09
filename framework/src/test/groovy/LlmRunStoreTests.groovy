/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.UserFacadeImpl
import org.moqui.impl.llm.LlmRunStore
import org.moqui.impl.llm.LlmRecoveryWorker
import org.moqui.impl.llm.LlmClientImpl
import org.moqui.impl.llm.LlmFacadeImpl
import org.moqui.llm.LlmContentPart
import org.moqui.llm.LlmItem
import org.moqui.llm.LlmProtocol.ProtocolResult
import org.moqui.llm.LlmTool
import org.moqui.llm.LlmToolCall
import org.moqui.llm.test.FakeLlmProtocol
import spock.lang.Shared
import spock.lang.Specification

import java.sql.Timestamp
import java.util.concurrent.TimeUnit

class LlmRunStoreTests extends Specification {
    static final String USER_ID = 'LLMRUNTEST'
    static final String USERNAME = 'llm.run.test'
    static final String OTHER_USER_ID = 'LLMRUNOTHER'
    static final String OTHER_USERNAME = 'llm.run.other'
    @Shared ExecutionContext ec
    final List<String> runIds = []

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        boolean disabled = ec.artifactExecution.disableAuthz()
        try {
            ec.transaction.runUseOrBegin(null, 'LLM run test user create failed') {
                ec.entity.makeValue('moqui.security.UserAccount')
                        .setAll([userId:USER_ID, username:USERNAME, userFullName:'LLM Run Test']).createOrUpdate()
                ec.entity.makeValue('moqui.security.UserAccount')
                        .setAll([userId:OTHER_USER_ID, username:OTHER_USERNAME, userFullName:'LLM Run Other']).createOrUpdate()
                ec.entity.makeValue('moqui.basic.EnumerationType')
                        .setAll([enumTypeId:'LlmRunStatus', description:'LLM Run Status']).createOrUpdate()
                ec.entity.makeValue('moqui.basic.EnumerationType')
                        .setAll([enumTypeId:'LlmContinuationMode', description:'LLM Continuation Mode']).createOrUpdate()
                ec.entity.makeValue('moqui.basic.EnumerationType')
                        .setAll([enumTypeId:'LlmToolInvocationStatus', description:'LLM Tool Invocation Status']).createOrUpdate()
                ec.entity.makeValue('moqui.basic.EnumerationType')
                        .setAll([enumTypeId:'LlmConversationStatus', description:'LLM Conversation Status']).createOrUpdate()
                ec.entity.makeValue('moqui.basic.EnumerationType')
                        .setAll([enumTypeId:'LlmRequestStatus', description:'LLM Request Status']).createOrUpdate()
                ec.entity.makeValue('moqui.basic.EnumerationType')
                        .setAll([enumTypeId:'LlmContentPurpose', description:'LLM Content Purpose']).createOrUpdate()
                ec.entity.makeValue('moqui.basic.EnumerationType')
                        .setAll([enumTypeId:'LlmTransport', description:'LLM Transport']).createOrUpdate()
                ec.entity.makeValue('moqui.service.job.ServiceJob').setAll([
                        jobName:'recover_AllLlmRuns_frequent', description:'Recover expired durable LLM runs',
                        serviceName:'org.moqui.impl.LlmServices.recover#AllLlmRuns',
                        cronExpression:'0 0/2 * * * ?', paused:'N', localOnly:'Y']).createOrUpdate()
                [workerId:'scheduled-recovery', leaseSeconds:'60', limit:'50'].each { parameterName, parameterValue ->
                    ec.entity.makeValue('moqui.service.job.ServiceJobParameter').setAll([
                            jobName:'recover_AllLlmRuns_frequent', parameterName:parameterName,
                            parameterValue:parameterValue]).createOrUpdate()
                }
                [LlmRunQueued:'LlmRunStatus', LlmRunRunning:'LlmRunStatus', LlmRunWaitClient:'LlmRunStatus',
                 LlmRunWaitConfirm:'LlmRunStatus', LlmRunRecovering:'LlmRunStatus', LlmRunComplete:'LlmRunStatus',
                 LlmRunFailed:'LlmRunStatus', LlmRunCancelled:'LlmRunStatus', LlmContLocal:'LlmContinuationMode',
                 LlmContRemote:'LlmContinuationMode', LlmTiPlanned:'LlmToolInvocationStatus',
                 LlmTiRunning:'LlmToolInvocationStatus', LlmTiComplete:'LlmToolInvocationStatus',
                 LlmTiFailed:'LlmToolInvocationStatus', LlmTiUncertain:'LlmToolInvocationStatus',
                 LlmTiCancelled:'LlmToolInvocationStatus', LlmcsActive:'LlmConversationStatus',
                 LlmcsStreaming:'LlmConversationStatus', LlmcsYielded:'LlmConversationStatus',
                 LlmcsComplete:'LlmConversationStatus', LlmcsFailed:'LlmConversationStatus',
                 LlmcsCancelled:'LlmConversationStatus', LlmReqPrepared:'LlmRequestStatus',
                 LlmReqSending:'LlmRequestStatus', LlmReqAck:'LlmRequestStatus',
                 LlmReqFailed:'LlmRequestStatus', LlmReqUncertain:'LlmRequestStatus',
                 LlmCpUser:'LlmContentPurpose', LlmCpAssistant:'LlmContentPurpose', LlmTrHttp:'LlmTransport',
                 LlmTrSse:'LlmTransport', LlmTrWebSocket:'LlmTransport'].each { enumId, enumTypeId ->
                    ec.entity.makeValue('moqui.basic.Enumeration')
                            .setAll([enumId:enumId, enumTypeId:enumTypeId, description:enumId]).createOrUpdate()
                }
            }
        } finally {
            if (!disabled) ec.artifactExecution.enableAuthz()
        }
        assert ((UserFacadeImpl) ec.user).internalLoginUser(USERNAME, false)
    }

    def cleanup() {
        boolean disabled = ec.artifactExecution.disableAuthz()
        try {
            if (runIds) {
                def responseIds = ec.entity.find('moqui.llm.LlmResponse').condition('runId', 'in', runIds)
                        .selectField('llmResponseId').list()*.llmResponseId
                if (responseIds) {
                    def itemIds = ec.entity.find('moqui.llm.LlmItem').condition('llmResponseId', 'in', responseIds)
                            .selectField('llmItemId').list()*.llmItemId
                    ec.entity.find('moqui.llm.LlmResponseEvent').condition('llmResponseId', 'in', responseIds).deleteAll()
                    if (itemIds) ec.entity.find('moqui.llm.LlmContent').condition('llmItemId', 'in', itemIds).deleteAll()
                    ec.entity.find('moqui.llm.LlmItem').condition('llmResponseId', 'in', responseIds).deleteAll()
                    ec.entity.find('moqui.llm.LlmResponse').condition('llmResponseId', 'in', responseIds).deleteAll()
                }
                def requestIds = ec.entity.find('moqui.llm.LlmRequest').condition('runId', 'in', runIds)
                        .selectField('llmRequestId').list()*.llmRequestId
                if (requestIds) {
                    def requestItemIds = ec.entity.find('moqui.llm.LlmItem').condition('llmRequestId', 'in', requestIds)
                            .selectField('llmItemId').list()*.llmItemId
                    if (requestItemIds) ec.entity.find('moqui.llm.LlmContent').condition('llmItemId', 'in', requestItemIds).deleteAll()
                    ec.entity.find('moqui.llm.LlmItem').condition('llmRequestId', 'in', requestIds).deleteAll()
                    ec.entity.find('moqui.llm.LlmRequest').condition('llmRequestId', 'in', requestIds).deleteAll()
                }
                ec.entity.find('moqui.llm.LlmContextProjection').condition('runId', 'in', runIds).deleteAll()
                ec.entity.find('moqui.llm.LlmToolInvocation').condition('runId', 'in', runIds).deleteAll()
                ec.entity.find('moqui.llm.LlmRunStatus').condition('runId', 'in', runIds).deleteAll()
                ec.entity.find('moqui.llm.LlmRun').condition('runId', 'in', runIds).deleteAll()
            }
        } finally {
            runIds.clear()
            if (!disabled) ec.artifactExecution.enableAuthz()
        }
    }

    def cleanupSpec() {
        if (ec != null) {
            ec.user.logoutUser()
            ec.transaction.runUseOrBegin(null, 'LLM run test user delete failed') {
                ec.entity.find('moqui.security.UserAccount').condition('userId', USER_ID).disableAuthz().deleteAll()
                ec.entity.find('moqui.security.UserAccount').condition('userId', OTHER_USER_ID).disableAuthz().deleteAll()
            }
            ec.destroy()
        }
    }

    def 'run transitions and stale fencing tokens are rejected'() {
        given:
        Map run = createRun()
        when:
        run = LlmRunStore.transition(ec, run.runId as String, LlmRunStore.RUNNING, 'started')
        def claimed = LlmRunStore.claimRecoverable(ec, 'worker-a', 30, 10).find { it.runId == run.runId }
        then:
        claimed.statusId == LlmRunStore.RECOVERING
        claimed.fencingToken == 1L
        when:
        LlmRunStore.checkpoint(ec, run.runId as String, 0L, [], [phase:'stale'], null, 1)
        then:
        thrown(IllegalStateException)
        when:
        def saved = LlmRunStore.checkpoint(ec, run.runId as String, 1L, [], [phase:'safe'], 'resp_1', 1)
        then:
        saved.checkpoint.phase == 'safe'
        saved.previousProviderResponseId == 'resp_1'
    }

    def 'local LLM run services load with the shared store contract'() {
        expect:
        ec.service.getServiceDefinition('org.moqui.impl.LlmServices.create#LlmRun') != null
        ec.service.getServiceDefinition('org.moqui.impl.LlmServices.update#LlmRunCheckpoint') != null
        ec.service.getServiceDefinition('org.moqui.impl.LlmServices.complete#LlmToolInvocation') != null
        ec.service.getServiceDefinition('org.moqui.impl.LlmServices.search#LlmContext') != null
        ec.service.getServiceDefinition('org.moqui.impl.LlmServices.recover#LlmRuns') != null
        ec.service.getServiceDefinition('org.moqui.impl.LlmServices.recover#AllLlmRuns') != null
        ec.service.getServiceDefinition('org.moqui.impl.LlmServices.retrieve#ProviderLlmResponse') != null
        ec.service.getServiceDefinition('org.moqui.impl.LlmServices.count#ProviderLlmResponseInputTokens') != null
        ec.service.getServiceDefinition('org.moqui.impl.LlmServices.compact#LlmResponseContext') != null
    }

    def 'scheduled LLM recovery service job creates ServiceJobRun and recovers owned run'() {
        given:
        Map run = createRun()
        LlmRunStore.transition(ec, run.runId as String, LlmRunStore.RUNNING, null)
        LlmRunStore.checkpoint(ec, run.runId as String, 0L, [], [phase:'provider_in_flight'], null, 0)
        String jobRunId
        Map jobResult
        when:
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        try {
            def job = ec.service.job('recover_AllLlmRuns_frequent').localOnly(true)
                    .parameter('workerId', 'servicejob-test')
                    .parameter('leaseSeconds', 1)
                    .parameter('limit', 5)
            jobRunId = job.run()
            jobResult = job.get(30, TimeUnit.SECONDS)
        } finally {
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
        def stored = LlmRunStore.getRun(ec, run.runId as String)
        def jobRun = ec.entity.find('moqui.service.job.ServiceJobRun')
                .condition('jobRunId', jobRunId).disableAuthz().one()
        then:
        jobRunId
        jobResult.ownerCount == 1
        stored.statusId == LlmRunStore.WAIT_CONFIRM
        stored.statuses[-1].message == 'Provider request outcome is uncertain; automatic replay is forbidden'
        jobRun != null
        jobRun.jobName == 'recover_AllLlmRuns_frequent'
        jobRun.endTime != null
        jobRun.errors == null
    }

    def 'tool result and context checkpoint commit together'() {
        given:
        Map run = createRun()
        LlmRunStore.transition(ec, run.runId as String, LlmRunStore.RUNNING, null)
        Map invocation = LlmRunStore.planTool(ec, run.runId as String,
                [providerCallId:'call-1', toolName:'lookup', arguments:[sku:'A'], idempotencyKey:'idem-1'])
        invocation = LlmRunStore.claimTool(ec, invocation.toolInvocationId as String, 'request-1', 0L)
        List<LlmItem> context = [LlmItem.message('user', [LlmContentPart.inputText('lookup A')]),
                                 LlmItem.functionCallOutput('call-1', [quantity:2])]
        when:
        Map saved = LlmRunStore.completeToolAndCheckpoint(ec, invocation.toolInvocationId as String,
                0L, [quantity:2], true, context, [phase:'tool_result'])
        then:
        saved.invocation.statusId == 'LlmTiComplete'
        saved.invocation.result.quantity == 2
        saved.run.checkpoint.phase == 'tool_result'
        (saved.run.context as List).size() == 2
    }

    def 'context projection upserts the same authorized source'() {
        given:
        Map run = createRun()
        when:
        LlmRunStore.projectText(ec, [runId:run.runId, sourceType:'message', sourceId:"${run.runId}:1",
                                     textContent:'first value'])
        LlmRunStore.projectText(ec, [runId:run.runId, sourceType:'message', sourceId:"${run.runId}:1",
                                     textContent:'updated value'])
        def rows = ec.entity.find('moqui.llm.LlmContextProjection')
                .condition([sourceType:'message', sourceId:"${run.runId}:1"]).disableAuthz().list()
        then:
        rows.size() == 1
        rows[0].textContent == 'updated value'
    }

    def 'context search uses text fts AND semantics within owner scope'() {
        given:
        Map run = createRun()
        String sourcePrefix = run.runId as String
        LlmRunStore.projectText(ec, [runId:run.runId, sourceType:'message', sourceId:"${sourcePrefix}:both",
                                     textContent:'warehouse alpha component'])
        LlmRunStore.projectText(ec, [runId:run.runId, sourceType:'message', sourceId:"${sourcePrefix}:one",
                                     textContent:'alpha component'])
        when:
        def result = LlmRunStore.searchContext(ec, [query:'alpha warehouse', runId:run.runId, limit:20])
        then:
        result.results*.sourceId == ["${sourcePrefix}:both"]
    }

    def 'run and context cannot be read by another user'() {
        given:
        Map run = createRun()
        LlmRunStore.projectText(ec, [runId:run.runId, sourceType:'message', sourceId:"${run.runId}:private",
                                     textContent:'private warehouse note'])
        assert ((UserFacadeImpl) ec.user).internalLoginUser(OTHER_USERNAME, false)
        when:
        LlmRunStore.getRun(ec, run.runId as String)
        then:
        thrown(IllegalArgumentException)
        when:
        def search = LlmRunStore.searchContext(ec, [query:'private warehouse'])
        then:
        search.results.isEmpty()
        cleanup:
        ec.user.logoutUser()
        assert ((UserFacadeImpl) ec.user).internalLoginUser(USERNAME, false)
    }

    def 'recovery marks an in flight tool uncertain instead of replaying it'() {
        given:
        Map run = LlmRunStore.createRun(ec, [profileName:'test-open-responses', context:[],
                checkpoint:[phase:'provider_response'], maxIterations:4])
        runIds << run.runId as String
        LlmRunStore.transition(ec, run.runId as String, LlmRunStore.RUNNING, null)
        Map invocation = LlmRunStore.planTool(ec, run.runId as String,
                [providerCallId:'call-uncertain', toolName:'write', arguments:[value:1]])
        invocation = LlmRunStore.claimTool(ec, invocation.toolInvocationId as String, 'dead-worker', 0L)
        when:
        def result = LlmRecoveryWorker.recover(ec, 'recovery-worker', 30, 10)
        def stored = LlmRunStore.getRun(ec, run.runId as String)
        def tools = LlmRunStore.listToolInvocations(ec, run.runId as String)
        then:
        result.claimed >= 1
        stored.statusId == LlmRunStore.WAIT_CONFIRM
        tools[0].statusId == 'LlmTiUncertain'
    }

    def 'scheduled recovery runs under persisted owner and restores caller identity'() {
        given:
        Map run = LlmRunStore.createRun(ec, [profileName:'test-open-responses', context:[],
                checkpoint:[phase:'provider_in_flight'], maxIterations:4])
        runIds << run.runId as String
        LlmRunStore.transition(ec, run.runId as String, LlmRunStore.RUNNING, null)
        when:
        def result = LlmRecoveryWorker.recoverAll(ec, 'scheduled-test', 30, 200)
        def stored = LlmRunStore.getRun(ec, run.runId as String)
        then:
        result.owners*.userId.contains(USER_ID)
        stored.statusId == LlmRunStore.WAIT_CONFIRM
        ec.user.username == USERNAME
    }

    def 'a client that is executing a run holds its lease so recovery cannot claim it'() {
        given:
        def protocol = new FakeLlmProtocol()
        List claimedWhileRunning = null
        String statusWhileRunning = null
        String workerWhileRunning = null
        protocol.handler = { request ->
            // the recovery job runs while the provider call is in flight
            claimedWhileRunning = LlmRunStore.claimRecoverable(ec, 'recovery-job', 60, 50).findAll { it.runId == request.runId }
            def run = LlmRunStore.getRun(ec, request.runId as String)
            statusWhileRunning = run.statusId
            workerWhileRunning = run.workerId
            FakeLlmProtocol.stop('done')
        }
        def profile = LlmFacadeImpl.ProfileState.forTest('lease-test', protocol, 'test-model', false, 0, 0f, 0)
        def client = new LlmClientImpl(ec, profile, { false })
        client.newConversation().user('hello')
        when:
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        def response
        try { response = client.call() }
        finally { if (!alreadyDisabled) ec.artifactExecution.enableAuthz() }
        def run = ec.entity.find('moqui.llm.LlmRun').condition([userId:USER_ID, profileName:'lease-test',
                conversationId:response.conversationId]).disableAuthz().one()
        runIds << run.runId
        def stored = LlmRunStore.getRun(ec, run.runId as String)
        then:
        claimedWhileRunning != null && claimedWhileRunning.isEmpty()
        statusWhileRunning == LlmRunStore.RUNNING
        workerWhileRunning?.startsWith('client:')
        stored.statusId == LlmRunStore.COMPLETE
        stored.workerId == null
        stored.leaseUntil == null
        stored.fencingToken == 1L
    }

    def 'a worker that lost the lease cannot change the run or plan a tool'() {
        given:
        Map run = createRun()
        LlmRunStore.transition(ec, run.runId as String, LlmRunStore.RUNNING, null)
        long first = LlmRunStore.acquireLease(ec, run.runId as String, 'worker-a', 60)
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        try {
            ec.entity.find('moqui.llm.LlmRun').condition('runId', run.runId).one()
                    .set('leaseUntil', new Timestamp(System.currentTimeMillis() - 1000L)).update()
        } finally { if (!alreadyDisabled) ec.artifactExecution.enableAuthz() }
        when: 'recovery takes the expired run over'
        def claimed = LlmRunStore.claimRecoverable(ec, 'worker-b', 60, 10).find { it.runId == run.runId }
        then:
        first == 1L
        claimed.fencingToken == 2L
        when: 'the old worker tries to act'
        LlmRunStore.transition(ec, run.runId as String, LlmRunStore.COMPLETE, 'late', first)
        then:
        thrown(IllegalStateException)
        when:
        LlmRunStore.planTool(ec, run.runId as String, [providerCallId:'late-call', toolName:'x', arguments:[:]], first)
        then:
        thrown(IllegalStateException)
        and: 'the current worker can'
        LlmRunStore.transition(ec, run.runId as String, LlmRunStore.COMPLETE, 'current', claimed.fencingToken as Long).statusId == LlmRunStore.COMPLETE
    }

    def 'a client that lost its lease cannot add a response to the run or finish it'() {
        given:
        def protocol = new FakeLlmProtocol()
        String takenOverBy = null
        protocol.handler = { request ->
            // while the provider call is in flight the lease runs out and another worker takes the run over
            boolean off = ec.artifactExecution.disableAuthz()
            try {
                ec.entity.find('moqui.llm.LlmRun').condition('runId', request.runId).one()
                        .set('leaseUntil', new Timestamp(System.currentTimeMillis() - 1000L)).update()
            } finally { if (!off) ec.artifactExecution.enableAuthz() }
            def claimed = LlmRunStore.claimRecoverable(ec, 'second-worker', 60, 50).find { it.runId == request.runId }
            takenOverBy = claimed?.workerId
            FakeLlmProtocol.stop('late answer')
        }
        def profile = LlmFacadeImpl.ProfileState.forTest('fence-test', protocol, 'test-model', false, 0, 0f, 0)
        def client = new LlmClientImpl(ec, profile, { false })
        client.newConversation().user('hello')
        when:
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        try { client.call() }
        catch (Throwable expected) { }
        finally { if (!alreadyDisabled) ec.artifactExecution.enableAuthz() }
        def run = ec.entity.find('moqui.llm.LlmRun').condition('profileName', 'fence-test').disableAuthz().orderBy('-createdDate').list().first()
        runIds << run.runId
        def responses = ec.entity.find('moqui.llm.LlmResponse').condition('runId', run.runId).disableAuthz().list()
        def stored = LlmRunStore.getRun(ec, run.runId as String)
        then:
        takenOverBy == 'second-worker'
        responses.isEmpty()
        stored.statusId == LlmRunStore.RECOVERING
        stored.workerId == 'second-worker'
        stored.fencingToken == 2L
    }

    def 'a run that is leased by another worker cannot be leased again and a waiting run is not claimable'() {
        given:
        Map run = createRun()
        LlmRunStore.transition(ec, run.runId as String, LlmRunStore.RUNNING, null)
        LlmRunStore.acquireLease(ec, run.runId as String, 'worker-a', 60)
        when:
        LlmRunStore.acquireLease(ec, run.runId as String, 'worker-b', 60)
        then:
        thrown(IllegalStateException)
        when:
        LlmRunStore.transition(ec, run.runId as String, LlmRunStore.WAIT_CONFIRM, 'waiting', 1L)
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        try {
            ec.entity.find('moqui.llm.LlmRun').condition('runId', run.runId).one()
                    .set('leaseUntil', new Timestamp(System.currentTimeMillis() - 1000L)).update()
        } finally { if (!alreadyDisabled) ec.artifactExecution.enableAuthz() }
        then:
        LlmRunStore.claimRecoverable(ec, 'worker-c', 60, 10).every { it.runId != run.runId }
    }

    def 'agent loop persists the lossless response and tool trajectory'() {
        given:
        def protocol = new FakeLlmProtocol()
        ProtocolResult first = FakeLlmProtocol.toolCalls(new LlmToolCall('call-1', 'lookup', '{"sku":"A"}'))
        first.responseId = 'resp-durable-1'
        first.status = 'completed'
        first.outputItems = [LlmItem.functionCall('call-1', 'lookup', '{"sku":"A"}')]
        first.responsePayload = [id:'resp-durable-1', object:'response', status:'completed',
                service_tier:'default', output:[]]
        ProtocolResult second = FakeLlmProtocol.stop('quantity 2')
        second.responseId = 'resp-durable-2'
        second.status = 'completed'
        second.outputItems = [LlmItem.message('assistant', [LlmContentPart.outputText('quantity 2')])]
        second.responsePayload = [id:'resp-durable-2', object:'response', status:'completed',
                service_tier:'default', output:[]]
        protocol.results = [first, second]
        def profile = LlmFacadeImpl.ProfileState.forTest('durable-test', protocol, 'test-model', false, 0, 0f, 0)
        def client = new LlmClientImpl(ec, profile, { false })
        client.newConversation().user('lookup A').tool(new LlmTool() {
            String getName() { 'lookup' }
            String getDescription() { 'lookup' }
            Map<String, Object> getParametersSchema() { [type:'object', properties:[sku:[type:'string']]] }
            LlmTool.Execution getExecution() { LlmTool.Execution.SERVER }
            Object execute(Map<String, Object> arguments, ExecutionContext ignored) { [quantity:2] }
        })
        when:
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        def response
        try { response = client.call() }
        finally { if (!alreadyDisabled) ec.artifactExecution.enableAuthz() }
        def run = ec.entity.find('moqui.llm.LlmRun').condition([userId:USER_ID,
                profileName:'durable-test', conversationId:response.conversationId]).disableAuthz().one()
        runIds << run.runId
        def stored = LlmRunStore.getRun(ec, run.runId as String)
        def invocations = ec.entity.find('moqui.llm.LlmToolInvocation').condition('runId', run.runId)
                .disableAuthz().list()
        def storedResponses = ec.entity.find('moqui.llm.LlmResponse').condition('runId', run.runId)
                .orderBy('createdDate').disableAuthz().list()
        then:
        response.content == 'quantity 2'
        stored.statusId == LlmRunStore.COMPLETE
        (stored.context as List)*.type == ['message', 'function_call', 'function_call_output', 'message']
        invocations.size() == 1
        invocations[0].statusId == 'LlmTiComplete'
        storedResponses.size() == 2
        storedResponses.every { it.payloadJson?.contains('"service_tier":"default"') }
    }

    def 'retention cleanup preserves active runs and removes old terminal response chains'() {
        given:
        Timestamp oldDate = Timestamp.valueOf('2000-01-01 00:00:00')
        Map activeRun = createRun()
        LlmRunStore.transition(ec, activeRun.runId as String, LlmRunStore.RUNNING, null)
        Map terminalRun = createRun()
        LlmRunStore.transition(ec, terminalRun.runId as String, LlmRunStore.RUNNING, null)
        LlmRunStore.transition(ec, terminalRun.runId as String, LlmRunStore.COMPLETE, null)
        String activeResponseId = makeResponse(activeRun.runId as String, 'resp-clean-active', oldDate)
        String terminalResponseId = makeResponse(terminalRun.runId as String, 'resp-clean-terminal', oldDate)
        boolean disabledForDate = ec.artifactExecution.disableAuthz()
        try {
            ec.entity.find('moqui.llm.LlmRun').condition('runId', terminalRun.runId).disableAuthz().one()
                    .set('completedDate', oldDate).update()
        } finally {
            if (!disabledForDate) ec.artifactExecution.enableAuthz()
        }
        when:
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        Map result
        try {
            result = ec.service.sync().name('org.moqui.impl.LlmServices.clean#LlmData')
                    .parameters([daysToKeep: 3650]).call()
        } finally {
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
        then:
        (result.runsRemoved as Long) >= 1L
        ec.entity.find('moqui.llm.LlmRun').condition('runId', activeRun.runId).disableAuthz().one() != null
        ec.entity.find('moqui.llm.LlmResponse').condition('llmResponseId', activeResponseId).disableAuthz().one() != null
        ec.entity.find('moqui.llm.LlmRun').condition('runId', terminalRun.runId).disableAuthz().one() == null
        ec.entity.find('moqui.llm.LlmResponse').condition('llmResponseId', terminalResponseId).disableAuthz().one() == null
    }

    def 'retention cleanup leaves no provenance pointing at a removed item and keeps the in-progress response of an active run'() {
        given:
        Timestamp oldDate = Timestamp.valueOf('2000-01-01 00:00:00')
        Timestamp recent = new Timestamp(System.currentTimeMillis())
        Map activeRun = createRun()
        LlmRunStore.transition(ec, activeRun.runId as String, LlmRunStore.RUNNING, null)
        Map terminalRun = createRun()
        LlmRunStore.transition(ec, terminalRun.runId as String, LlmRunStore.RUNNING, null)
        LlmRunStore.transition(ec, terminalRun.runId as String, LlmRunStore.COMPLETE, null)
        String oldResponseId = makeResponse(terminalRun.runId as String, 'resp-prov-old', oldDate)
        String suffix = String.valueOf(System.nanoTime())
        String requestId = 'CLEANREQ' + suffix
        String survivorId = 'CLEANITEM' + suffix
        String inProgressId = 'CLEANPROG' + suffix
        boolean off = ec.artifactExecution.disableAuthz()
        String oldItemId
        try {
            ec.entity.find('moqui.llm.LlmRun').condition('runId', terminalRun.runId).one().set('completedDate', oldDate).update()
            oldItemId = ec.entity.find('moqui.llm.LlmItem').condition('llmResponseId', oldResponseId).one().llmItemId
            ec.entity.makeValue('moqui.llm.LlmRequest').setAll([llmRequestId: requestId, ownerUserId: USER_ID,
                    profileName: 'test-open-responses', operation: 'create_response', localStatusEnumId: 'LlmReqAck',
                    createdDate: recent]).create()
            ec.entity.makeValue('moqui.llm.LlmItem').setAll([llmItemId: survivorId, llmRequestId: requestId,
                    sourceLlmItemId: oldItemId, sequenceNum: 1, itemType: 'message', role: 'assistant']).create()
            ec.entity.makeValue('moqui.llm.LlmResponse').setAll([llmResponseId: inProgressId, runId: activeRun.runId,
                    ownerUserId: USER_ID, profileName: 'test-open-responses', status: 'in_progress', createdDate: oldDate]).create()
        } finally { if (!off) ec.artifactExecution.enableAuthz() }
        when:
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        try {
            ec.service.sync().name('org.moqui.impl.LlmServices.clean#LlmData').parameters([daysToKeep: 3650]).call()
        } finally { if (!alreadyDisabled) ec.artifactExecution.enableAuthz() }
        def survivor = ec.entity.find('moqui.llm.LlmItem').condition('llmItemId', survivorId).disableAuthz().one()
        then:
        ec.entity.find('moqui.llm.LlmResponse').condition('llmResponseId', oldResponseId).disableAuthz().one() == null
        survivor != null
        survivor.sourceLlmItemId == null
        ec.entity.find('moqui.llm.LlmResponse').condition('llmResponseId', inProgressId).disableAuthz().one() != null
        cleanup:
        boolean off2 = ec.artifactExecution.disableAuthz()
        try {
            ec.entity.find('moqui.llm.LlmItem').condition('llmItemId', survivorId).deleteAll()
            ec.entity.find('moqui.llm.LlmRequest').condition('llmRequestId', requestId).deleteAll()
        } finally { if (!off2) ec.artifactExecution.enableAuthz() }
    }

    private static org.moqui.llm.LlmTool customTool() {
        new org.moqui.llm.LlmTool() {
            String getName() { 'custom_lookup' }
            String getDescription() { 'caller code' }
            Map<String, Object> getParametersSchema() { [type: 'object'] }
            org.moqui.llm.LlmTool.Execution getExecution() { org.moqui.llm.LlmTool.Execution.SERVER }
            Object execute(Map<String, Object> arguments, org.moqui.context.ExecutionContext executionContext) { [ok: true] }
        }
    }

    def 'the run envelope carries instructions, options, tools and transport and a recovered client uses them'() {
        given:
        def protocol = new FakeLlmProtocol()
        protocol.handler = { request -> FakeLlmProtocol.stop('done') }
        def profile = LlmFacadeImpl.ProfileState.forTest('envelope-test', protocol, 'test-model', false, 0, 0f, 0)
        def client = new LlmClientImpl(ec, profile, { false })
        client.system('be brief').temperature(0.3d).maxTokens(77).timeout(33)
                .transport(org.moqui.llm.LlmTransport.WEBSOCKET)
                .responseOptions(new org.moqui.llm.LlmResponseOptions().put('store', false).put('prompt_cache_key', 'k1'))
                .tool(org.moqui.llm.LlmTool.client('pick', 'choose one', [type: 'object']))
                .tool(customTool())
        client.newConversation().user('hello')
        when:
        boolean off = ec.artifactExecution.disableAuthz()
        try { client.call() } finally { if (!off) ec.artifactExecution.enableAuthz() }
        def run = ec.entity.find('moqui.llm.LlmRun').condition([userId: USER_ID, profileName: 'envelope-test'])
                .disableAuthz().one()
        runIds << run.runId as String
        Map stored = LlmRunStore.getRun(ec, run.runId as String)
        Map envelope = stored.envelope as Map
        then:
        envelope.instructions == 'be brief'
        envelope.model == 'test-model'
        envelope.transport == 'WEBSOCKET'
        envelope.responseOptions.prompt_cache_key == 'k1'
        envelope.tools*.name == ['pick', 'custom_lookup']
        envelope.tools*.kind == ['client', 'custom']
        !(run.envelopeJson as String).toLowerCase().contains('authorization')
        and: 'a run with caller code as a tool is not resumed automatically'
        LlmClientImpl.unrecoverableReason(stored).contains('custom_lookup')
        when: 'every tool can be rebuilt'
        Map rebuildable = new LinkedHashMap(stored)
        rebuildable.envelope = new LinkedHashMap(envelope) + [tools: envelope.tools.findAll { it.kind != 'custom' }]
        def recovered = new LlmClientImpl(ec, profile, { false })
        recovered.attachRecoveredRun(rebuildable)
        def request = recovered.buildRequest('test-model', [])
        then:
        LlmClientImpl.unrecoverableReason(rebuildable) == null
        recovered.systemContent == 'be brief'
        request.transport == org.moqui.llm.LlmTransport.WEBSOCKET
        request.temperature == 0.3d
        request.maxTokens == 77
        request.timeoutSeconds == 33
        request.responseOptions.asMap().prompt_cache_key == 'k1'
        request.tools*.name == ['pick']
    }

    def 'a window policy set after the crash does not change the context a recovered run sends'() {
        given:
        List context = [[type: 'message', role: 'user', content: [[type: 'input_text', text: 'one']]],
                        [type: 'message', role: 'assistant', content: [[type: 'output_text', text: 'two']]],
                        [type: 'message', role: 'user', content: [[type: 'input_text', text: 'three']]]]
        Map created = LlmRunStore.createRun(ec, [profileName: 'test-open-responses', context: context, maxIterations: 4])
        runIds << created.runId as String
        Map stored = LlmRunStore.getRun(ec, created.runId as String)
        def profile = LlmFacadeImpl.ProfileState.forTest('window-test', new FakeLlmProtocol(), 'test-model', false, 0, 0f, 0)
        def client = new LlmClientImpl(ec, profile, { false })
        def tiny = new org.moqui.llm.WindowPolicy()
        tiny.maxMessages = 1
        tiny.maxChars = 5
        client.windowPolicy(tiny)
        when:
        client.attachRecoveredRun(stored)
        def request = client.buildRequest('test-model', client.buildWindow())
        def body = org.moqui.impl.llm.OpenResponsesCodec.buildRequestBody(request)
        then: 'the Open Responses items are the stored ones, whole; the policy trims the Chat Completions transcript only'
        body.input.size() == 3
        body.input*.role == ['user', 'assistant', 'user']
    }

    def 'recovery does not resume a run whose tools cannot be rebuilt'() {
        given:
        Map run = LlmRunStore.createRun(ec, [profileName: 'test-open-responses', context: [],
                checkpoint: [phase: 'ready_provider'], maxIterations: 4,
                envelope: [tools: [[name: 'custom_lookup', kind: 'custom', className: 'x.Y']]]])
        runIds << run.runId as String
        LlmRunStore.transition(ec, run.runId as String, LlmRunStore.RUNNING, null)
        when:
        def result = LlmRecoveryWorker.recover(ec, 'recovery-worker', 30, 10)
        def stored = LlmRunStore.getRun(ec, run.runId as String)
        then:
        result.waitingConfirmation*.runId.contains(run.runId)
        stored.statusId == LlmRunStore.WAIT_CONFIRM
        stored.statuses.last().message.contains('cannot be rebuilt')
    }

    private Map createRun() {
        Map run = LlmRunStore.createRun(ec, [profileName:'test-open-responses', context:[], maxIterations:4])
        runIds << run.runId as String
        return run
    }

    private String makeResponse(String runId, String providerResponseId, Timestamp createdDate) {
        String responseId = ec.entity.sequencedIdPrimary('moqui.llm.LlmResponse', null, null)
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        try {
            ec.entity.makeValue('moqui.llm.LlmResponse').setAll([
                    llmResponseId:responseId, providerResponseId:providerResponseId, runId:runId,
                    ownerUserId:USER_ID, profileName:'test-open-responses', model:'test-model',
                    specVersion:'2026-04-24', objectType:'response', status:'completed',
                    createdDate:createdDate, completedDate:createdDate]).create()
            String itemId = ec.entity.sequencedIdPrimary('moqui.llm.LlmItem', null, null)
            ec.entity.makeValue('moqui.llm.LlmItem').setAll([
                    llmItemId:itemId, llmResponseId:responseId, sequenceNum:1,
                    itemType:'message', status:'completed', role:'assistant']).create()
            ec.entity.makeValue('moqui.llm.LlmContent').setAll([
                    llmContentId:ec.entity.sequencedIdPrimary('moqui.llm.LlmContent', null, null), llmItemId:itemId,
                    sequenceNum:1, contentType:'output_text', contentKind:'content',
                    purposeEnumId:'LlmCpAssistant', textContent:'cleanup']).create()
        } finally {
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
        responseId
    }
}
