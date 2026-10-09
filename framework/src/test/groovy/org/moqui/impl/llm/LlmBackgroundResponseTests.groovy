package org.moqui.impl.llm

import org.moqui.context.ExecutionContext
import org.moqui.llm.LlmException
import org.moqui.llm.LlmFinishReason
import org.moqui.llm.LlmMessage
import org.moqui.llm.LlmResponse
import org.moqui.llm.LlmTool

import java.sql.Timestamp

/** A response the provider has not finished: parked, polled, completed or ended, and never created twice. */
class LlmBackgroundResponseTests extends LlmConversationSpecBase {
    static final String QUEUED = '{"id":"resp_bg1","object":"response","status":"queued","model":"gpt-test","output":[]}'
    static final String WORKING = '{"id":"resp_bg1","object":"response","status":"in_progress","model":"gpt-test","output":[]}'

    def setup() {
        System.setProperty('llm_background_poll_seconds', '0')
        cancelWaitingRuns()
    }
    def cleanup() {
        System.clearProperty('llm_background_poll_seconds')
        System.clearProperty('llm_background_timeout_seconds')
        cancelWaitingRuns()
    }

    /** A run that still waits for a provider belongs to a test that is over: no other test may poll it. */
    private void cancelWaitingRuns() {
        boolean off = ec.artifactExecution.disableAuthz()
        try {
            ec.entity.find('moqui.llm.LlmRun').condition('statusId', LlmRunStore.WAIT_PROVIDER).condition('userId', USER_ID)
                    .useCache(false).list().each { LlmRunStore.cancel(ec, it.runId as String) }
        } finally { if (!off) ec.artifactExecution.enableAuthz() }
    }

    private LlmFacadeImpl.ProfileState openAiProfile(String endpoint) {
        def p = profile(endpoint, 'background-it', new OpenAiResponsesProtocol())
        register(p)
        p
    }
    private static List gets(provider) { provider.requests.findAll { it.method == 'GET' } }
    private static List posts(provider) { provider.requests.findAll { it.method == 'POST' } }
    private Map run(String id) { LlmRunStore.getRun(ec, id) }

    private Map park(provider, LlmFacadeImpl.ProfileState p, String text = 'a long job', Closure more = null) {
        provider.enqueueJson(200, QUEUED)
        def conv = LlmConversationImpl.create(ec, p.name, null)
        LlmResponse r = turn(p, conv) { LlmClientImpl c -> c.user(text); if (more) more.call(c) }
        [conv: conv, response: r, runId: LlmConversationImpl.load(ec, conv.conversationId, true).getAttributes().get('activeLlmRunId') as String]
    }

    private Map pollNow(String runId) {
        boolean off = ec.artifactExecution.disableAuthz()
        try { return LlmBackgroundPoller.pollRun(ec, runId, 'test-poller', 30) } finally { if (!off) ec.artifactExecution.enableAuthz() }
    }

    def "a queued response parks the turn: the caller gets the id, the run waits, the conversation waits, and nothing is created twice"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        def p = openAiProfile(provider.endpoint)
        when:
        Map parked = park(provider, p)
        LlmResponse r = parked.response
        def stored = run(parked.runId)
        def reopened = LlmConversationImpl.load(ec, parked.conv.conversationId, true)
        then:
        r.pending && r.finishReason == LlmFinishReason.PENDING && r.responseId == 'resp_bg1' && r.status == 'queued'
        stored.statusId == LlmRunStore.WAIT_PROVIDER
        stored.checkpoint.phase == 'provider_pending' && stored.checkpoint.providerResponseId == 'resp_bg1' && stored.checkpoint.pollable == true
        reopened.status == LlmConversationImpl.STATUS_YIELDED
        reopened.headRunId == null
        reopened.history.findAll { it.role == LlmMessage.Role.ASSISTANT }.isEmpty()
        posts(provider).size() == 1 && gets(provider).isEmpty()
        and: 'the request was accepted, and no response row was made up'
        ec.entity.find('moqui.llm.LlmRequest').condition('runId', parked.runId).disableAuthz().one().localStatusEnumId == 'LlmReqAck'
        ec.entity.find('moqui.llm.LlmResponse').condition('runId', parked.runId).disableAuthz().count() == 0
        cleanup: unregister('background-it'); provider.close()
    }

    def "polling reads the response until it is final, then the run and the conversation complete like any turn"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        def p = openAiProfile(provider.endpoint)
        Map parked = park(provider, p)
        provider.enqueueJson(200, WORKING)
        provider.enqueueJson(200, answer('resp_bg1', 'finished job'))
        long before = LlmMessageStore.accessCount()
        when:
        Map first = pollNow(parked.runId)
        Map stillWaiting = run(parked.runId)
        Map second = pollNow(parked.runId)
        def done = run(parked.runId)
        def reopened = LlmConversationImpl.load(ec, parked.conv.conversationId, true)
        then:
        first.outcome == 'pending' && stillWaiting.statusId == LlmRunStore.WAIT_PROVIDER
        second.outcome == 'finished' && second.finishReason == 'STOP'
        done.statusId == LlmRunStore.COMPLETE
        reopened.status == LlmConversationImpl.STATUS_COMPLETE
        reopened.headRunId == parked.runId
        reopened.history.findAll { it.role != LlmMessage.Role.SYSTEM }*.content == ['a long job', 'finished job']
        ec.entity.find('moqui.llm.LlmResponse').condition('runId', parked.runId).disableAuthz().one().providerResponseId == 'resp_bg1'
        and: 'one create, two reads, and no message row touched'
        posts(provider).size() == 1 && gets(provider).size() == 2
        gets(provider).every { it.path == '/v1/responses/resp_bg1' }
        LlmMessageStore.accessCount() == before
        and: 'the next turn replays the finished one'
        when:
        provider.enqueueJson(200, answer('resp_bg2', 'next'))
        turn(p, reopened) { it.user('and then?') }
        then:
        posts(provider).last().json.input.collect { m -> (m.content ?: []).collect { it.text }.join('') } == ['a long job', 'finished job', 'and then?']
        cleanup: unregister('background-it'); provider.close()
    }

    def "a finished response that asks for tools is kept for a person: no tool is run and nothing is created"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        def p = openAiProfile(provider.endpoint)
        List ran = []
        LlmTool tool = new LlmTool() {
            String getName() { 'lookup' }
            String getDescription() { 'looks up' }
            Map<String, Object> getParametersSchema() { [type: 'object', properties: [:]] }
            LlmTool.Execution getExecution() { LlmTool.Execution.SERVER }
            Object execute(Map<String, Object> arguments, ExecutionContext x) { ran << arguments; [found: true] }
        }
        Map parked = park(provider, p, 'look it up') { LlmClientImpl c -> c.tool(tool) }
        provider.enqueueJson(200, toolCallResponse('resp_bg1', 'call_bg', 'lookup', '{}'))
        when:
        Map out = pollNow(parked.runId)
        then:
        out.outcome == 'finished' && out.finishReason == 'TOOL_CALLS'
        run(parked.runId).statusId == LlmRunStore.WAIT_CONFIRM
        ran.isEmpty()
        posts(provider).size() == 1
        cleanup: unregister('background-it'); provider.close()
    }

    def "a response that failed or was cancelled by the provider ends the run and the conversation"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        def p = openAiProfile(provider.endpoint)
        Map parked = park(provider, p)
        provider.enqueueJson(200, '{"id":"resp_bg1","object":"response","status":"' + status + '","model":"gpt-test","output":[],"error":{"code":"server_error","message":"it broke"}}')
        when:
        Map out = pollNow(parked.runId)
        then:
        out.outcome == 'finished'
        run(parked.runId).statusId == LlmRunStore.FAILED
        LlmConversationImpl.load(ec, parked.conv.conversationId, true).status == LlmConversationImpl.STATUS_FAILED
        LlmConversationImpl.load(ec, parked.conv.conversationId, true).headRunId == null
        cleanup: unregister('background-it'); provider.close()
        where:
        status << ['failed', 'cancelled']
    }

    def "a provider that cannot read a response is not polled and nothing is invented"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        def standard = profile(provider.endpoint, 'background-standard', new OpenResponsesProtocol())
        register(standard)
        provider.enqueueJson(200, QUEUED)
        def conv = LlmConversationImpl.create(ec, standard.name, null)
        LlmResponse r = turn(standard, conv) { it.user('standard provider') }
        String runId = LlmConversationImpl.load(ec, conv.conversationId, true).getAttributes().get('activeLlmRunId')
        when:
        Map out = pollNow(runId)
        then:
        r.pending && r.responseId == 'resp_bg1'
        run(runId).checkpoint.pollable == false
        out.outcome == 'not_pollable'
        gets(provider).isEmpty() && posts(provider).size() == 1
        cleanup: unregister('background-standard'); provider.close()
    }

    def "a protocol that cannot ask again says so at once, and the run ends at its limit with that reason and no request"() {
        given:
        System.setProperty('llm_background_timeout_seconds', '0')
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        def standard = profile(provider.endpoint, 'background-standard2', new OpenResponsesProtocol())
        register(standard)
        provider.enqueueJson(200, QUEUED)
        def conv = LlmConversationImpl.create(ec, standard.name, null)
        LlmResponse r = turn(standard, conv) { it.user('standard provider') }
        String runId = LlmConversationImpl.load(ec, conv.conversationId, true).getAttributes().get('activeLlmRunId')
        Thread.sleep(20)
        when:
        Map out = pollNow(runId)
        then:
        r.pending && r.pollable == false
        r.errorMessage.contains('no operation to ask it again')
        out.outcome == 'expired_not_pollable'
        run(runId).statusId == 'LlmRunFailed'
        gets(provider).isEmpty() && posts(provider).size() == 1
        cleanup: unregister('background-standard2'); provider.close()
    }

    def "one poller at a time, and a poller that lost its lease cannot write"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        def p = openAiProfile(provider.endpoint)
        Map parked = park(provider, p)
        boolean off = ec.artifactExecution.disableAuthz()
        long fenceA, fenceB, denied
        try {
            fenceA = LlmRunStore.claimPoll(ec, parked.runId, 'poller-a', 30)
            denied = LlmRunStore.claimPoll(ec, parked.runId, 'poller-b', 30)
            // poller A stalls; its lease runs out
            ec.transaction.runUseOrBegin(null, 'expire') {
                ec.entity.find('moqui.llm.LlmRun').condition('runId', parked.runId).updateAll([leaseUntil: new Timestamp(System.currentTimeMillis() - 1000)] as Map<String, Object>)
            }
            fenceB = LlmRunStore.claimPoll(ec, parked.runId, 'poller-b', 30)
        } finally { if (!off) ec.artifactExecution.enableAuthz() }
        when: 'the stale poller tries to record what it saw'
        LlmRunStore.checkpoint(ec, parked.runId, fenceA, [], [phase: 'stale'], 'resp_bg1', 1)
        then:
        fenceA > 0 && denied == 0L && fenceB == fenceA + 1
        thrown(IllegalStateException)
        and: 'the run is still waiting and was not touched by the stale poller'
        run(parked.runId).statusId == LlmRunStore.WAIT_PROVIDER
        run(parked.runId).checkpoint.phase == 'provider_pending'
        cleanup: unregister('background-it'); provider.close()
    }

    def "only the owner's runs are polled, and a restart picks the run up from what is stored"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        def p = openAiProfile(provider.endpoint)
        Map parked = park(provider, p)
        provider.enqueueJson(200, answer('resp_bg1', 'done after a restart'))
        boolean off = ec.artifactExecution.disableAuthz()
        try {
            ec.transaction.runUseOrBegin(null, 'other') {
                ec.entity.makeValue('moqui.security.UserAccount').setAll([userId: 'LLMBGOTHER', username: 'llm.bg.other', userFullName: 'Other']).createOrUpdate()
            }
        } finally { if (!off) ec.artifactExecution.enableAuthz() }
        when: 'another user polls'
        Map foreign = (Map) inNewEc('llm.bg.other') { ExecutionContext other -> LlmBackgroundPoller.poll(other, 'foreign-poller', 30, 50) }
        then:
        foreign.polled == 0
        gets(provider).isEmpty()
        when: 'the scheduled job, in another context, completes it as the owner'
        Map all = (Map) inNewEc(USERNAME) { ExecutionContext other -> LlmBackgroundPoller.pollAll(other, 'scheduled', 30, 50) }
        then:
        run(parked.runId).statusId == LlmRunStore.COMPLETE
        all.owners.any { it.userId == USER_ID }
        cleanup: unregister('background-it'); provider.close()
    }

    def "a response that is not finished by the deadline is cancelled at the provider and the run ends"() {
        given:
        System.setProperty('llm_background_timeout_seconds', '0')
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        def p = openAiProfile(provider.endpoint)
        Map parked = park(provider, p)
        provider.enqueueJson(200, '{"id":"resp_bg1","object":"response","status":"cancelled","model":"gpt-test","output":[]}')
        when:
        sleepFor(5)
        Map out = pollNow(parked.runId)
        then:
        out.outcome == 'timeout'
        run(parked.runId).statusId == LlmRunStore.FAILED
        run(parked.runId).errorMessage.contains('in time')
        provider.requests.any { it.method == 'POST' && it.path == '/v1/responses/resp_bg1/cancel' }
        cleanup: unregister('background-it'); provider.close()
    }

    private static void sleepFor(int millis) { Thread.sleep(millis) }

    def "a provider that keeps failing is given up on after a number of tries, not asked forever"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        def p = openAiProfile(provider.endpoint)
        Map parked = park(provider, p)
        LlmBackgroundPoller.MAX_CONSECUTIVE_ERRORS.times { provider.enqueueJson(500, '{"error":{"message":"down"}}') }
        when:
        List outcomes = (1..LlmBackgroundPoller.MAX_CONSECUTIVE_ERRORS).collect { pollNow(parked.runId).outcome }
        then:
        outcomes.take(LlmBackgroundPoller.MAX_CONSECUTIVE_ERRORS - 1).every { it == 'error' }
        outcomes.last() == 'gave_up'
        run(parked.runId).statusId == LlmRunStore.FAILED
        cleanup: unregister('background-it'); provider.close()
    }

    def "a conversation waiting for the provider cannot be deleted until its run ended"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        def p = openAiProfile(provider.endpoint)
        Map parked = park(provider, p)
        boolean off = ec.artifactExecution.disableAuthz()
        Map refused
        try { refused = LlmGateway.deleteConversationOf(ec, parked.conv.conversationId) } finally { if (!off) ec.artifactExecution.enableAuthz() }
        expect:
        refused.deleted == false && refused.httpStatus == 409
        cleanup: unregister('background-it'); provider.close()
    }
}
