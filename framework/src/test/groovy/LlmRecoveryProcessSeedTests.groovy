import groovy.json.JsonOutput
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.UserFacadeImpl
import org.moqui.impl.llm.LlmRunStore
import spock.lang.Specification

class LlmRecoveryProcessSeedTests extends Specification {
    static final String USER_ID = 'LLMPROCREC'
    static final String USERNAME = 'llm.proc.recovery'

    def 'seed recoverable run for a separate JVM'() {
        given:
        ExecutionContext ec = Moqui.getExecutionContext()
        File marker = new File(System.getProperty('llm.recovery.marker'))
        boolean disabled = ec.artifactExecution.disableAuthz()
        try {
            ec.transaction.runUseOrBegin(null, 'LLM process recovery seed setup failed') {
                ec.entity.makeValue('moqui.security.UserAccount')
                        .setAll([userId:USER_ID, username:USERNAME, userFullName:'LLM Process Recovery'])
                        .createOrUpdate()
                ec.entity.makeValue('moqui.basic.EnumerationType')
                        .setAll([enumTypeId:'LlmRunStatus', description:'LLM Run Status']).createOrUpdate()
                ec.entity.makeValue('moqui.basic.EnumerationType')
                        .setAll([enumTypeId:'LlmContinuationMode', description:'LLM Continuation Mode']).createOrUpdate()
                [LlmRunQueued:'LlmRunStatus', LlmRunRunning:'LlmRunStatus',
                 LlmRunWaitConfirm:'LlmRunStatus', LlmRunRecovering:'LlmRunStatus',
                 LlmRunComplete:'LlmRunStatus', LlmRunFailed:'LlmRunStatus',
                 LlmRunCancelled:'LlmRunStatus', LlmContLocal:'LlmContinuationMode'].each { enumId, enumTypeId ->
                    ec.entity.makeValue('moqui.basic.Enumeration')
                            .setAll([enumId:enumId, enumTypeId:enumTypeId, description:enumId]).createOrUpdate()
                }
            }
        } finally {
            if (!disabled) ec.artifactExecution.enableAuthz()
        }
        assert ((UserFacadeImpl) ec.user).internalLoginUser(USERNAME, false)
        when:
        Map run = LlmRunStore.createRun(ec, [profileName:'process-recovery-test', context:[],
                checkpoint:[phase:'provider_in_flight'], maxIterations:2])
        LlmRunStore.transition(ec, run.runId as String, LlmRunStore.RUNNING, null)
        marker.parentFile.mkdirs()
        marker.text = JsonOutput.toJson([runId:run.runId, userId:USER_ID, username:USERNAME])
        then:
        marker.exists()
        cleanup:
        ec?.user?.logoutUser()
        ec?.destroy()
    }
}
