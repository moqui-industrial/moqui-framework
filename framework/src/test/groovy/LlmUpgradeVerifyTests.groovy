import groovy.json.JsonSlurper
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.impl.llm.LlmClientImpl
import org.moqui.impl.llm.LlmRunStore
import spock.lang.Specification

import java.sql.Connection

/**
 * Second half of the upgrade check, in a fresh JVM on the database LlmUpgradeSeedTests left in its old shape: the
 * current code adds what is missing without dropping anything, the upgrade service brings the stored responses to the
 * current layout in interruptible batches without inventing anything, and running everything again changes nothing.
 */
class LlmUpgradeVerifyTests extends Specification {
    private ExecutionContext ec

    private Map service(String name, Map parameters) {
        ec.service.sync().name("org.moqui.impl.LlmServices.${name}".toString()).parameters(parameters).call()
    }

    private boolean hasColumn(Connection con, String table, String column) {
        con.metaData.getColumns(null, null, table, column).withCloseable { it.next() } ||
                con.metaData.getColumns(null, null, table.toLowerCase(), column.toLowerCase()).withCloseable { it.next() }
    }

    def 'the current code upgrades what the old shape held, twice, and loses nothing'() {
        given:
        ec = Moqui.getExecutionContext()
        Map marker = new JsonSlurper().parse(new File(System.getProperty('llm.upgrade.marker'))) as Map
        ec.artifactExecution.disableAuthz()
        ec.user.loginUser('john.doe', 'moqui')
        when: 'the first use of the entities adds the missing columns and index'
        long before = ec.entity.find('moqui.llm.LlmResponse').condition('llmResponseId', 'in', marker.responses).count()
        Connection con = ec.entity.getConnection('transactional')
        then:
        before == 2
        [['LLM_REQUEST', 'EFFECTIVE_OPTIONS_JSON'], ['LLM_RESPONSE', 'USAGE_JSON'], ['LLM_RESPONSE', 'DATA_VERSION'],
         ['LLM_ITEM', 'SOURCE_STATUS'], ['LLM_RUN', 'ENVELOPE_JSON']].every { t, c -> hasColumn(con, t, c) }
        when: 'the old rows are as they were left'
        def old = ec.entity.find('moqui.llm.LlmResponse').condition('llmResponseId', marker.responses[0]).one()
        then:
        old.dataVersion == null
        old.usageJson == null
        new JsonSlurper().parseText(old.payloadJson).id == 'resp_up_1'
        old.optionsJson == '{"store":false,"stream":false}'
        when: 'the upgrade runs in batches of one and is interrupted after the first'
        Map first = service('upgrade#LlmOpenResponsesData', [batchSize: 1])
        then:
        first.upgraded == 1
        when: 'it is started again, from the beginning, and finished'
        List<Map> runs = []
        String cursor = null
        int guard = 0
        while (guard++ < 500) {
            Map r = service('upgrade#LlmOpenResponsesData', [afterLlmResponseId: cursor, batchSize: 50])
            runs << r
            cursor = r.lastLlmResponseId
            if (!r.more) break
        }
        Map again = service('upgrade#LlmOpenResponsesData', [batchSize: 1000])
        def r1 = ec.entity.find('moqui.llm.LlmResponse').condition('llmResponseId', marker.responses[0]).useCache(false).one()
        def r2 = ec.entity.find('moqui.llm.LlmResponse').condition('llmResponseId', marker.responses[1]).useCache(false).one()
        def request = ec.entity.find('moqui.llm.LlmRequest').condition('llmRequestId', marker.request).useCache(false).one()
        Map echo = new JsonSlurper().parseText(r1.optionsJson) as Map
        Map usage = new JsonSlurper().parseText(r1.usageJson) as Map
        then:
        again.upgraded == 0
        r1.dataVersion == 2 && r2.dataVersion == 2
        and: 'what was sent moved to the request; what was echoed is read from the stored response'
        request.effectiveOptionsJson == '{"store":false,"stream":false}'
        echo.containsKey('temperature') && echo.temperature == null
        echo.top_p == 1.0
        echo.store == false
        usage.input_tokens_details.cached_tokens == 1
        and: 'a response with no stored raw payload gets nothing invented'
        r2.optionsJson == null
        r2.usageJson == null
        and: 'nothing was lost'
        new JsonSlurper().parseText(r1.payloadJson).id == 'resp_up_1'
        ec.entity.find('moqui.llm.LlmResponse').condition('llmResponseId', 'in', marker.responses).count() == 2
        ec.entity.find('moqui.llm.LlmItem').condition('llmItemId', 'in', ['UPITR' + marker.tag, 'UPIQ' + marker.tag]).count() == 2
        ec.entity.find('moqui.llm.LlmItem').condition('llmItemId', 'UPIQ' + marker.tag).one().sourceLlmItemId == 'UPITR' + marker.tag
        ec.entity.find('moqui.llm.LlmItem').condition('llmItemId', 'UPIQ' + marker.tag).one().sourceStatus == null
        when: 'the old content is made searchable, in batches, repeatedly'
        List<Map> fills = []
        String after = null
        int g2 = 0
        while (g2++ < 2000) {
            Map f = service('backfill#LlmContextProjection', [afterLlmContentId: after, batchSize: 200])
            fills << f
            after = f.lastLlmContentId
            if (!f.more) break
        }
        Map refill = service('backfill#LlmContextProjection', [batchSize: 1000])
        then:
        service('search#LlmContext', [query: 'upgradeunicorn']).results.findAll { it.sourceId == 'UPCQ' + marker.tag }.size() == 1
        service('search#LlmContext', [query: 'upgradeokapi']).results.findAll { it.sourceId == 'UPCR' + marker.tag }.size() == 1
        when:
        Map storedRun = LlmRunStore.getRun(ec, marker.runId as String)
        then: 'a run stored without an envelope is still recoverable with the profile as before'
        storedRun.envelope == null
        LlmClientImpl.unrecoverableReason(storedRun) == null
        cleanup:
        if (ec != null) {
            ec.transaction.runUseOrBegin(null, 'upgrade verify cleanup failed') {
                ec.entity.find('moqui.llm.LlmContextProjection').condition('sourceType', 'content')
                        .condition('sourceId', 'in', ['UPCR' + marker.tag, 'UPCQ' + marker.tag]).deleteAll()
                ec.entity.find('moqui.llm.LlmContent').condition('llmContentId', 'in', ['UPCR' + marker.tag, 'UPCQ' + marker.tag]).deleteAll()
                ec.entity.find('moqui.llm.LlmItem').condition('llmItemId', 'in', ['UPIQ' + marker.tag, 'UPITR' + marker.tag]).deleteAll()
                ec.entity.find('moqui.llm.LlmResponse').condition('llmResponseId', 'in', marker.responses).deleteAll()
                ec.entity.find('moqui.llm.LlmRequest').condition('llmRequestId', marker.request).deleteAll()
                ec.entity.find('moqui.llm.LlmRunStatus').condition('runId', marker.runId).deleteAll()
                ec.entity.find('moqui.llm.LlmRun').condition('runId', marker.runId).deleteAll()
            }
            ec.user.logoutUser()
            ec.destroy()
        }
    }
}
