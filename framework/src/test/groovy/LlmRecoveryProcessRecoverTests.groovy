import groovy.json.JsonSlurper
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.UserFacadeImpl
import org.moqui.impl.llm.LlmRecoveryWorker
import org.moqui.impl.llm.LlmRunStore
import spock.lang.Specification

class LlmRecoveryProcessRecoverTests extends Specification {
    def 'recover run seeded by a previous JVM process'() {
        given:
        File marker = new File(System.getProperty('llm.recovery.marker'))
        assert marker.exists()
        Map markerJson = new JsonSlurper().parse(marker) as Map
        ExecutionContext ec = Moqui.getExecutionContext()
        assert ((UserFacadeImpl) ec.user).internalLoginUser(markerJson.username as String, false)
        when:
        Map result = LlmRecoveryWorker.recoverAll(ec, 'process-recovery-worker', 30, 10)
        Map run = LlmRunStore.getRun(ec, markerJson.runId as String)
        then:
        result.owners*.userId.contains(markerJson.userId)
        run.statusId == LlmRunStore.WAIT_CONFIRM
        run.checkpoint.phase == 'provider_in_flight'
        cleanup:
        boolean disabled = ec?.artifactExecution?.disableAuthz()
        try {
            // recoverAll runs without a transaction and leaves none open: delete inside one of our own
            ec.transaction.runUseOrBegin(null, 'process recovery test cleanup failed') {
                if (markerJson?.runId) {
                    ec.entity.find('moqui.llm.LlmRunStatus').condition('runId', markerJson.runId).disableAuthz().deleteAll()
                    ec.entity.find('moqui.llm.LlmRun').condition('runId', markerJson.runId).disableAuthz().deleteAll()
                }
                if (markerJson?.userId) {
                    ec.entity.find('moqui.security.UserAccount').condition('userId', markerJson.userId).disableAuthz().deleteAll()
                }
            }
        } finally {
            if (!disabled) ec?.artifactExecution?.enableAuthz()
            ec?.user?.logoutUser()
            ec?.destroy()
            marker.delete()
        }
    }
}
