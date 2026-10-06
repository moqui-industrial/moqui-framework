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
import org.moqui.impl.llm.LlmFacadeImpl
import org.moqui.impl.llm.LlmRunExecutor
import org.moqui.impl.llm.LlmRecoveryWorker
import org.moqui.impl.llm.LlmRunStore
import org.moqui.llm.LlmToolCall
import org.moqui.llm.test.FakeLlmProtocol
import org.moqui.util.MNode
import spock.lang.Shared
import spock.lang.Specification

import java.lang.reflect.Field
import java.sql.Timestamp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Background execution of durable runs through the execute_LlmRun ServiceJob, with a fake provider. */
class LlmRunExecutionTests extends Specification {
    static final String PROFILE = 'bg-exec-test'
    static final String SMALL_PROFILE = 'bg-exec-small'
    @Shared ExecutionContext ec
    @Shared String ownerUserId
    final List<String> runIds = []
    FakeLlmProtocol protocol

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        assert ec.user.loginUser('john.doe', 'moqui')
        ownerUserId = ec.user.userId
        boolean disabled = ec.artifactExecution.disableAuthz()
        try {
            ec.transaction.runUseOrBegin(null, 'LLM execution test seed failed') {
                [LlmRunQueued:'LlmRunStatus', LlmRunRunning:'LlmRunStatus', LlmRunWaitClient:'LlmRunStatus',
                 LlmRunWaitConfirm:'LlmRunStatus', LlmRunRecovering:'LlmRunStatus', LlmRunComplete:'LlmRunStatus',
                 LlmRunFailed:'LlmRunStatus', LlmRunCancelled:'LlmRunStatus', LlmContLocal:'LlmContinuationMode',
                 LlmContRemote:'LlmContinuationMode', LlmTiPlanned:'LlmToolInvocationStatus',
                 LlmTiRunning:'LlmToolInvocationStatus', LlmTiComplete:'LlmToolInvocationStatus',
                 LlmTiFailed:'LlmToolInvocationStatus', LlmTiUncertain:'LlmToolInvocationStatus',
                 LlmTiCancelled:'LlmToolInvocationStatus'].each { enumId, enumTypeId ->
                    ec.entity.makeValue('moqui.basic.EnumerationType')
                            .setAll([enumTypeId:enumTypeId, description:enumTypeId]).createOrUpdate()
                    ec.entity.makeValue('moqui.basic.Enumeration')
                            .setAll([enumId:enumId, enumTypeId:enumTypeId, description:enumId]).createOrUpdate()
                }
                // same rows as framework/data/LlmTypeData.xml; the test database may predate them
                ec.entity.makeValue('moqui.security.ArtifactGroup').setAll([artifactGroupId:'LlmRunServices',
                        description:'LLM run background execution services']).createOrUpdate()
                ec.entity.makeValue('moqui.security.ArtifactGroupMember').setAll([artifactGroupId:'LlmRunServices',
                        artifactName:'org\\.moqui\\.impl\\.LlmServices\\.(start|execute|answer|confirm|suspend)#.*',
                        nameIsPattern:'Y', artifactTypeEnumId:'AT_SERVICE', inheritAuthz:'N']).createOrUpdate()
                ec.entity.makeValue('moqui.security.ArtifactAuthz').setAll([artifactAuthzId:'LlmRunServicesADMIN',
                        userGroupId:'ADMIN', artifactGroupId:'LlmRunServices', authzTypeEnumId:'AUTHZT_ALWAYS',
                        authzActionEnumId:'AUTHZA_ALL']).createOrUpdate()
                ec.entity.makeValue('moqui.security.user.NotificationTopic').setAll([topic:'LlmRunEvents',
                        description:'LLM Run Stopped or Completed', titleTemplate:'LLM run ${parameters.runId} is ${results.statusId}',
                        typeString:'info', showAlert:'Y', persistOnSend:'Y', isPrivate:'Y',
                        receiveNotifications:'Y']).createOrUpdate()
                ec.entity.makeValue('moqui.service.job.ServiceJob').setAll([jobName:'execute_LlmRun',
                        description:'Execute one durable LLM run',
                        serviceName:'org.moqui.impl.LlmServices.execute#LlmRun', topic:'LlmRunEvents', paused:'N',
                        localOnly:'Y']).createOrUpdate()
            }
        } finally {
            if (!disabled) ec.artifactExecution.enableAuthz()
        }
    }

    def setup() {
        protocol = new FakeLlmProtocol()
        registerProfile(PROFILE, null)
    }

    def cleanup() {
        ec.message.clearErrors()
        profiles().remove(PROFILE)
        profiles().remove(SMALL_PROFILE)
        boolean disabled = ec.artifactExecution.disableAuthz()
        try {
            runIds.each { id ->
                ec.entity.find('moqui.llm.LlmToolInvocation').condition('runId', id).deleteAll()
                ec.entity.find('moqui.llm.LlmRunStatus').condition('runId', id).deleteAll()
                ec.entity.find('moqui.llm.LlmRun').condition('runId', id).deleteAll()
            }
            runIds.clear()
        } finally {
            if (!disabled) ec.artifactExecution.enableAuthz()
        }
    }

    def cleanupSpec() { ec?.destroy() }

    private Map profiles() {
        Field field = LlmFacadeImpl.getDeclaredField('profileByName')
        field.setAccessible(true)
        (Map) field.get(ec.llm)
    }

    private void registerProfile(String name, String poolMax) {
        def state = LlmFacadeImpl.ProfileState.forTest(name, protocol, 'test-model', false, 0, 0f, 0)
        def confNode = poolMax ? new MNode('profile', [name:name, 'pool-max':poolMax]) : null
        if (confNode != null) state = new LlmFacadeImpl.ProfileState(name, confNode, state.url, state.path, state.endpointUrl,
                state.apiKey, state.authHeaderName, state.authHeaderValue, state.model, state.maxTokensParameter,
                false, 120, 0f, 0, true, 0, state.contextLimitPolicy, null, null, false, [:], [:], state.requestFactory,
                protocol, [] as Set, [], false, 15, null, true, false, false, false, false, false, false, [])
        state.allowedServices = [
                new LlmFacadeImpl.ServiceAllow('org.moqui.impl.LlmServices.suspend#CurrentLlmRun', 'ask', 'Stop and ask')]
        profiles().put(name, state)
    }

    private Map startRun(String profileName = PROFILE, String objective = 'do the work') {
        Map run = LlmRunExecutor.start(ec, [profileName:profileName, objective:objective, maxIterations:8])
        runIds << (run.runId as String)
        run
    }

    private Map awaitStatus(String runId, String statusId, int seconds = 30) {
        long until = System.currentTimeMillis() + seconds * 1000L
        Map run = LlmRunStore.getRun(ec, runId)
        // the job link is written right after the job starts, so a fast job can finish first
        while ((run.statusId != statusId || !run.jobRunId) && System.currentTimeMillis() < until) {
            Thread.sleep(100)
            run = LlmRunStore.getRun(ec, runId)
        }
        run
    }

    private def jobRun(String jobRunId) {
        ec.entity.find('moqui.service.job.ServiceJobRun').condition('jobRunId', jobRunId).disableAuthz().useCache(false).one()
    }

    private void awaitJobEnd(String jobRunId, int seconds = 30) {
        long until = System.currentTimeMillis() + seconds * 1000L
        while (jobRun(jobRunId)?.endTime == null && System.currentTimeMillis() < until) Thread.sleep(100)
    }

    private static String lastText(List items) {
        def last = items.findAll { it.type == 'message' }.last()
        last.content*.text.join(' ')
    }

    def "a queued run executes in a job as its owner and completes"() {
        given:
        protocol.handler = { req -> FakeLlmProtocol.stop('all done') }
        when:
        Map run = startRun()
        Map finished = awaitStatus(run.runId as String, LlmRunStore.COMPLETE)
        awaitJobEnd(finished.jobRunId as String)
        def jr = jobRun(finished.jobRunId as String)
        Map done = LlmRunStore.getRun(ec, run.runId as String)
        def notes = ec.entity.find('moqui.security.user.NotificationMessage').condition('topic', 'LlmRunEvents')
                .disableAuthz().useCache(false).list().findAll { it.messageJson?.contains(run.runId as String) }
        def noted = notes ? ec.entity.find('moqui.security.user.NotificationMessageUser')
                .condition('notificationMessageId', 'in', notes*.notificationMessageId).condition('userId', ownerUserId)
                .disableAuthz().useCache(false).list() : []

        then:
        done.statusId == LlmRunStore.COMPLETE
        done.jobRunId
        jr.jobName == 'execute_LlmRun'
        jr.userId == ownerUserId
        jr.errors == null
        jr.endTime != null
        (done.context as List)*.type == ['message', 'message']
        lastText(protocol.lastRequest.inputItems as List) == 'do the work'
        done.workerId == null
        done.leaseUntil == null
        notes.size() == 1
        noted.size() == 1
    }

    def "a run that asks for confirmation ends the job and confirm resumes it"() {
        given:
        protocol.handler = { req ->
            def items = req.inputItems as List
            if (items.any { it.type == 'message' && it.content*.text.join(' ').contains('Confirmation: approved') })
                return FakeLlmProtocol.stop('executed after confirmation')
            FakeLlmProtocol.toolCalls(new LlmToolCall('call-1', 'ask', '{"statusId":"LlmRunWaitConfirm","message":"ok to proceed?"}'))
        }
        when:
        Map run = startRun()
        Map waiting = awaitStatus(run.runId as String, LlmRunStore.WAIT_CONFIRM)
        String firstJob = waiting.jobRunId
        awaitJobEnd(firstJob)
        def firstJobRun = jobRun(firstJob)
        int callsBeforeConfirm = protocol.chatCount
        Map confirmed = ec.service.sync().name('org.moqui.impl.LlmServices.confirm#LlmRun')
                .parameters([runId:run.runId, approved:true, comments:'go ahead']).call().run
        Map done = awaitStatus(run.runId as String, LlmRunStore.COMPLETE)

        then:
        waiting.statusId == LlmRunStore.WAIT_CONFIRM
        firstJobRun.endTime != null
        firstJobRun.errors == null
        callsBeforeConfirm == 1
        confirmed.jobRunId != firstJob
        done.statusId == LlmRunStore.COMPLETE
        lastText(done.context as List) == 'executed after confirmation'
        (done.context as List).any { it.type == 'message' && it.role == 'user' &&
                it.content*.text.join(' ').contains('Confirmation: approved. go ahead') }
        done.statuses*.statusId.contains(LlmRunStore.WAIT_CONFIRM)
    }

    def "an answer resumes a run waiting for the client and a rejection cancels a run waiting for confirmation"() {
        given:
        protocol.handler = { req ->
            def items = req.inputItems as List
            if (items.any { it.type == 'message' && it.content*.text.join(' ').contains('blue') })
                return FakeLlmProtocol.stop('thanks')
            FakeLlmProtocol.toolCalls(new LlmToolCall('call-1', 'ask', '{"statusId":"LlmRunWaitClient","message":"which color?"}'))
        }
        when:
        Map run = startRun()
        awaitStatus(run.runId as String, LlmRunStore.WAIT_CLIENT)
        String wrongKind = null
        try { ec.service.sync().name('org.moqui.impl.LlmServices.confirm#LlmRun').parameters([runId:run.runId, approved:true]).call() }
        catch (Throwable t) { wrongKind = t.message }
        if (ec.message.hasError()) wrongKind = ec.message.errorsString
        ec.message.clearErrors()
        ec.service.sync().name('org.moqui.impl.LlmServices.answer#LlmRun').parameters([runId:run.runId, text:'blue']).call()
        Map done = awaitStatus(run.runId as String, LlmRunStore.COMPLETE)

        and: 'a second run is rejected'
        protocol.handler = { req -> FakeLlmProtocol.toolCalls(new LlmToolCall('call-9', 'ask', '{"statusId":"LlmRunWaitConfirm"}')) }
        Map second = startRun()
        awaitStatus(second.runId as String, LlmRunStore.WAIT_CONFIRM)
        int callsBefore = protocol.chatCount
        ec.service.sync().name('org.moqui.impl.LlmServices.confirm#LlmRun')
                .parameters([runId:second.runId, approved:false, comments:'not now']).call()
        Map rejected = LlmRunStore.getRun(ec, second.runId as String)
        Thread.sleep(500)

        then:
        wrongKind != null
        done.statusId == LlmRunStore.COMPLETE
        rejected.statusId == LlmRunStore.CANCELLED
        rejected.statuses[-1].message.contains('not now')
        protocol.chatCount == callsBefore
    }

    def "cancelling a running run stops it and a second execution of the same run does nothing"() {
        given:
        CountDownLatch inProvider = new CountDownLatch(1)
        CountDownLatch release = new CountDownLatch(1)
        protocol.handler = { req ->
            inProvider.countDown()
            release.await(20, TimeUnit.SECONDS)
            FakeLlmProtocol.toolCalls(new LlmToolCall('call-1', 'ask', '{"statusId":"LlmRunWaitConfirm"}'))
        }
        when:
        Map run = startRun()
        boolean entered = inProvider.await(20, TimeUnit.SECONDS)
        Map duplicate = LlmRunExecutor.execute(ec, run.runId as String)
        LlmRunStore.cancel(ec, run.runId as String)
        release.countDown()
        Map cancelled = awaitStatus(run.runId as String, LlmRunStore.CANCELLED)
        awaitJobEnd(LlmRunStore.getRun(ec, run.runId as String).jobRunId as String)
        def jr = jobRun(LlmRunStore.getRun(ec, run.runId as String).jobRunId as String)

        then:
        entered
        duplicate.executed == false
        cancelled.statusId == LlmRunStore.CANCELLED
        jr.errors == null
        protocol.chatCount == 1
    }

    def "a queued run whose job is gone is re-dispatched by recovery"() {
        given:
        protocol.handler = { req -> FakeLlmProtocol.stop('recovered') }
        Map run = LlmRunStore.createRun(ec, [profileName:PROFILE, objective:'orphan', maxIterations:4,
                context:[org.moqui.llm.LlmItem.message('user', [org.moqui.llm.LlmContentPart.inputText('orphan')])],
                checkpoint:[phase:'ready_provider']])
        runIds << (run.runId as String)
        boolean aged = ec.artifactExecution.disableAuthz()
        try {
            ec.entity.find('moqui.llm.LlmRun').condition('runId', run.runId).one()
                    .set('lastUpdatedDate', new Timestamp(System.currentTimeMillis() - 600000L)).update()
        } finally { if (!aged) ec.artifactExecution.enableAuthz() }
        when:
        boolean disabled = ec.artifactExecution.disableAuthz()
        Map result
        try { result = LlmRecoveryWorker.recoverAll(ec, 'queue-test', 60, 50) }
        finally { if (!disabled) ec.artifactExecution.enableAuthz() }
        Map done = awaitStatus(run.runId as String, LlmRunStore.COMPLETE)
        then:
        result.ownerCount >= 1
        done.statusId == LlmRunStore.COMPLETE
        done.jobRunId
    }

    def "the profile pool-max limits concurrent executions and a refused run stays queued"() {
        given:
        registerProfile(SMALL_PROFILE, '1')
        CountDownLatch inProvider = new CountDownLatch(1)
        CountDownLatch release = new CountDownLatch(1)
        protocol.handler = { req ->
            inProvider.countDown()
            release.await(20, TimeUnit.SECONDS)
            FakeLlmProtocol.stop('slot')
        }
        when:
        Map first = startRun(SMALL_PROFILE)
        boolean entered = inProvider.await(20, TimeUnit.SECONDS)
        Map second = startRun(SMALL_PROFILE)
        String secondJob = LlmRunStore.getRun(ec, second.runId as String).jobRunId
        awaitJobEnd(secondJob)
        Map secondAfter = LlmRunStore.getRun(ec, second.runId as String)
        int callsWhileBusy = protocol.chatCount
        release.countDown()
        awaitStatus(first.runId as String, LlmRunStore.COMPLETE)
        then:
        entered
        secondAfter.statusId == LlmRunStore.QUEUED
        jobRun(secondJob).endTime != null
        callsWhileBusy == 1
        LlmRunStore.getRun(ec, first.runId as String).statusId == LlmRunStore.COMPLETE
    }
}
