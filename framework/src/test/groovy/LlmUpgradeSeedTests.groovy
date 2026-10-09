import groovy.json.JsonOutput
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.impl.llm.LlmRunStore
import spock.lang.Specification

import java.sql.Connection
import java.sql.Timestamp

/**
 * First half of the upgrade check, in its own JVM: stores Open Responses data the way an installation from before the
 * options and usage were kept apart holds it (the new columns do not exist and are not set), then removes those columns
 * and the index from the database so that it has exactly the old shape, with the data in it. LlmUpgradeVerifyTests
 * starts the current code on that database.
 */
class LlmUpgradeSeedTests extends Specification {
    def 'store legacy shaped data, then give the database its old shape'() {
        given:
        ExecutionContext ec = Moqui.getExecutionContext()
        File marker = new File(System.getProperty('llm.upgrade.marker'))
        ec.artifactExecution.disableAuthz()
        ec.user.loginUser('john.doe', 'moqui')
        String userId = ec.user.userId
        String tag = String.valueOf(System.nanoTime())
        Timestamp now = new Timestamp(System.currentTimeMillis())
        String payload = JsonOutput.toJson([id: 'resp_up_1', object: 'response', status: 'completed', temperature: null, top_p: 1.0,
                store: false, usage: [input_tokens: 3, output_tokens: 2, total_tokens: 5, input_tokens_details: [cached_tokens: 1]],
                output: [[id: 'msg_up', type: 'message', role: 'assistant', content: [[type: 'output_text', text: 'upgradeokapi answer']]]]])
        when:
        Map run = LlmRunStore.createRun(ec, [profileName: 'upgrade-test', context: [], maxIterations: 2])
        ec.transaction.runUseOrBegin(null, 'upgrade seed failed') {
            ec.entity.makeValue('moqui.llm.LlmRequest').setAll([llmRequestId: 'UPREQ' + tag, ownerUserId: userId, profileName: 'upgrade-test',
                    operation: 'create_response', localStatusEnumId: 'LlmReqAck', runId: run.runId, createdDate: now,
                    requestPayloadJson: '{"model":"m","input":"hi"}']).create()
            ec.entity.makeValue('moqui.llm.LlmResponse').setAll([llmResponseId: 'UPRES1' + tag, llmRequestId: 'UPREQ' + tag, ownerUserId: userId,
                    runId: run.runId, profileName: 'upgrade-test', providerResponseId: 'resp_up_1', status: 'completed', createdDate: now,
                    optionsJson: '{"store":false,"stream":false}', payloadJson: payload]).create()
            ec.entity.makeValue('moqui.llm.LlmResponse').setAll([llmResponseId: 'UPRES2' + tag, ownerUserId: userId, profileName: 'upgrade-test',
                    providerResponseId: 'resp_up_2', status: 'completed', createdDate: now, optionsJson: '{"store":true}']).create()
            ec.entity.makeValue('moqui.llm.LlmItem').setAll([llmItemId: 'UPITR' + tag, llmResponseId: 'UPRES1' + tag, sequenceNum: 1,
                    providerItemId: 'msg_up', itemType: 'message', role: 'assistant']).create()
            ec.entity.makeValue('moqui.llm.LlmContent').setAll([llmContentId: 'UPCR' + tag, llmItemId: 'UPITR' + tag, sequenceNum: 1,
                    contentType: 'output_text', contentKind: 'content', purposeEnumId: 'LlmCpAssistant', textContent: 'upgradeokapi answer']).create()
            ec.entity.makeValue('moqui.llm.LlmItem').setAll([llmItemId: 'UPIQ' + tag, llmRequestId: 'UPREQ' + tag, sourceLlmItemId: 'UPITR' + tag,
                    sequenceNum: 1, providerItemId: 'msg_up', itemType: 'message', role: 'assistant']).create()
            ec.entity.makeValue('moqui.llm.LlmContent').setAll([llmContentId: 'UPCQ' + tag, llmItemId: 'UPIQ' + tag, sequenceNum: 1,
                    contentType: 'output_text', contentKind: 'content', purposeEnumId: 'LlmCpAssistant', textContent: 'upgradeunicorn replayed']).create()
        }
        // the old shape: the columns and the index this version added are not there
        Connection con = ec.entity.getConnection('transactional')
        boolean h2 = con.metaData.databaseProductName.toLowerCase().contains('h2')
        ec.transaction.runUseOrBegin(null, 'drop new columns failed') {
            Connection c = ec.entity.getConnection('transactional')
            c.createStatement().withCloseable { st ->
                st.execute('DROP INDEX IF EXISTS LLMITEM_PROVIDER')
                ['LLM_REQUEST.EFFECTIVE_OPTIONS_JSON', 'LLM_RESPONSE.USAGE_JSON', 'LLM_RESPONSE.DATA_VERSION',
                 'LLM_ITEM.SOURCE_STATUS', 'LLM_RUN.ENVELOPE_JSON'].each { String col ->
                    def (t, c1) = col.split('\\.')
                    st.execute("ALTER TABLE ${t} DROP COLUMN ${c1}".toString())
                }
            }
        }
        marker.parentFile.mkdirs()
        marker.text = JsonOutput.toJson([tag: tag, runId: run.runId, userId: userId, request: 'UPREQ' + tag,
                responses: ['UPRES1' + tag, 'UPRES2' + tag], database: con.metaData.databaseProductName])
        then:
        marker.exists()
        cleanup:
        ec?.user?.logoutUser()
        ec?.destroy()
    }
}
