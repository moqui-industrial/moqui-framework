import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.UserFacadeImpl
import org.moqui.impl.llm.LlmCrashChild
import org.moqui.impl.llm.LlmJson
import org.moqui.impl.llm.LlmRecoveryWorker
import org.moqui.impl.llm.LlmRunStore
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp

/**
 * Runs after LlmCrashChild exited in the middle of a tool: the external effect is written, the result is not.
 * The run still holds its lease, so recovery must leave it alone until the lease runs out, and then must not repeat
 * the tool.
 */
class LlmCrashToolRecoverTests extends Specification {
    def 'recovery waits for the lease of a crashed worker and then refuses to repeat the tool'() {
        given:
        Path ledger = Path.of(System.getProperty('llm.crash.ledger'))
        assert Files.exists(ledger)
        Map ledgerJson = (Map) LlmJson.toObject(Files.readString(ledger))
        ExecutionContext ec = Moqui.getExecutionContext()
        assert ((UserFacadeImpl) ec.user).internalLoginUser(LlmCrashChild.USERNAME, false)
        String runId = ledgerJson.runId as String

        when: 'recovery runs while the dead worker is still within its lease'
        Map early = LlmRecoveryWorker.recoverAll(ec, 'crash-tool-early', 30, 10)
        def stillRunning = LlmRunStore.getRun(ec, runId)

        and: 'time passes: the lease of the crashed worker expires'
        boolean disabled = ec.artifactExecution.disableAuthz()
        try {
            ec.entity.find('moqui.llm.LlmRun').condition('runId', runId).one()
                    .set('leaseUntil', new Timestamp(System.currentTimeMillis() - 1000L)).update()
        } finally { if (!disabled) ec.artifactExecution.enableAuthz() }
        Map recovered = LlmRecoveryWorker.recoverAll(ec, 'crash-tool-recover-test', 30, 10)
        def run = ec.entity.find('moqui.llm.LlmRun').condition('runId', runId).disableAuthz().one()
        List invocations = ec.entity.find('moqui.llm.LlmToolInvocation').condition('runId', runId)
                .disableAuthz().list()

        then:
        stillRunning.statusId == LlmRunStore.RUNNING
        !early.owners.any { owner -> owner.result.waitingConfirmation.any { it.runId == runId } }
        recovered.ownerCount >= 1
        run.statusId == LlmRunStore.WAIT_CONFIRM
        invocations.size() == 1
        invocations[0].statusId == 'LlmTiUncertain'
        invocations[0].providerCallId == 'call_crash_1'
        ledgerJson.externalEffect == 'written'

        cleanup:
        try { ec?.destroy() } catch (Throwable ignored) { }
    }
}
