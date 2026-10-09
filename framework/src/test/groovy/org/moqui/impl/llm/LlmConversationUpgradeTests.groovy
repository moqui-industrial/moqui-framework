package org.moqui.impl.llm

import org.moqui.llm.LlmContentPart
import org.moqui.llm.LlmItem
import org.moqui.llm.LlmMessage

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp

/** The upgrades of stored Open Responses data: media columns written as ciphertext, conversations moved to the item model. */
class LlmConversationUpgradeTests extends LlmConversationSpecBase {

    def setupSpec() {
        // the upgrades are administrator work: this test user is one for the duration of the class
        boolean off = ec.artifactExecution.disableAuthz()
        try {
            ec.transaction.runUseOrBegin(null, 'admin membership') {
                ec.entity.makeValue('moqui.security.UserGroupMember').setAll([userGroupId: 'ADMIN', userId: USER_ID, fromDate: new Timestamp(0)]).createOrUpdate()
            }
        } finally { if (!off) ec.artifactExecution.enableAuthz() }
        ec.user.logoutUser()
        assert ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser(USERNAME, false)
        assert ec.user.isInGroup('ADMIN')
    }
    def cleanupSpec() {
        boolean off = ec.artifactExecution.disableAuthz()
        try {
            ec.transaction.runUseOrBegin(null, 'admin membership removal') {
                ec.entity.find('moqui.security.UserGroupMember').condition('userGroupId', 'ADMIN').condition('userId', USER_ID).deleteAll()
            }
        } finally { if (!off) ec.artifactExecution.enableAuthz() }
    }

    // ---- raw access: what the column physically holds

    private String rawValue(String entity, String idField, String id, String field) {
        def efi = (org.moqui.impl.entity.EntityFacadeImpl) ec.entity
        def ed = efi.getEntityDefinition(entity)
        String out = null
        ec.transaction.runUseOrBegin(null, 'raw read') {
            Connection con = ec.entity.getConnection(ed.entityGroupName)
            PreparedStatement ps = con.prepareStatement('SELECT ' + ed.getColumnName(field) + ' FROM ' + ed.fullTableName + ' WHERE ' + ed.getColumnName(idField) + ' = ?')
            try { ps.setString(1, id); ResultSet rs = ps.executeQuery(); try { if (rs.next()) out = rs.getString(1) } finally { rs.close() } }
            finally { ps.close() }
        }
        out
    }
    private void rawSet(String entity, String idField, String id, String field, String value) {
        def efi = (org.moqui.impl.entity.EntityFacadeImpl) ec.entity
        def ed = efi.getEntityDefinition(entity)
        ec.transaction.runUseOrBegin(null, 'raw write') {
            Connection con = ec.entity.getConnection(ed.entityGroupName)
            PreparedStatement ps = con.prepareStatement('UPDATE ' + ed.fullTableName + ' SET ' + ed.getColumnName(field) + ' = ? WHERE ' + ed.getColumnName(idField) + ' = ?')
            try { ps.setString(1, value); ps.setString(2, id); ps.executeUpdate() } finally { ps.close() }
        }
    }

    def "media written in plain by an older version is converted to ciphertext, in batches, and is then read back unchanged"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, answer('resp_m1', 'seen'))
        def p = profile(provider.endpoint)
        def conv = LlmConversationImpl.create(ec, p.name, null)
        String dataUri = 'data:image/png;base64,' + PNG
        turn(p, conv) { it.inputItems([LlmItem.message('user', [LlmContentPart.inputText('look'), image(dataUri)])]) }
        def imageRows = ec.entity.find('moqui.llm.LlmContent').condition('contentType', 'input_image').disableAuthz().useCache(false).list()
        assert imageRows.size() >= 1
        String contentId = imageRows.collect { it.llmContentId }.max()
        and: 'the stored value is ciphertext now, not the data URI'
        String stored = rawValue('moqui.llm.LlmContent', 'llmContentId', contentId, 'imageUrl')
        assert stored != null && !stored.contains('data:image') && !stored.contains(PNG)

        and: 'an older version wrote the same columns in plain'
        rawSet('moqui.llm.LlmContent', 'llmContentId', contentId, 'imageUrl', dataUri)
        rawSet('moqui.llm.LlmContent', 'llmContentId', contentId, 'fileUrl', 'https://files.example.test/doc.pdf?X-Amz-Signature=abc123')
        rawSet('moqui.llm.LlmContent', 'llmContentId', contentId, 'contentLocation', 'component://secret/place')
        when: 'the entity reads the plain value of such a column'
        String readBeforeUpgrade = ec.entity.find('moqui.llm.LlmContent').condition('llmContentId', contentId).disableAuthz().useCache(false).one().imageUrl
        then: 'the engine hands back its placeholder for a value it cannot decrypt, so the data is unusable until it is upgraded'
        readBeforeUpgrade == org.moqui.impl.entity.FieldInfo.decryptFailedMagicString

        when: 'it is upgraded one row at a time until nothing is left'
        Map first = LlmDataUpgrade.protectContentMedia(ec, '', 1)
        String after = first.lastLlmContentId
        int converted = first.converted as int
        int rounds = 1
        while (first.more && rounds < 5000) {
            first = LlmDataUpgrade.protectContentMedia(ec, after, 1)
            after = first.lastLlmContentId
            converted += first.converted as int
            rounds++
        }
        Map again = LlmDataUpgrade.protectContentMedia(ec, '', 1000)
        def back = ec.entity.find('moqui.llm.LlmContent').condition('llmContentId', contentId).disableAuthz().useCache(false).one()
        then: 'the physical columns hold no plain value, the entity gives back what was written, and a second pass has nothing to do'
        converted >= 3
        again.converted == 0
        ['imageUrl', 'fileUrl', 'contentLocation'].every { f -> !rawValue('moqui.llm.LlmContent', 'llmContentId', contentId, f).contains('://') && !rawValue('moqui.llm.LlmContent', 'llmContentId', contentId, f).contains('base64') }
        back.imageUrl == dataUri
        back.fileUrl == 'https://files.example.test/doc.pdf?X-Amz-Signature=abc123'
        back.contentLocation == 'component://secret/place'
        cleanup: provider.close()
    }

    def "a person who is not an administrator cannot run the upgrades"() {
        given:
        boolean off = ec.artifactExecution.disableAuthz()
        try { ec.transaction.runUseOrBegin(null, 'drop admin') { ec.entity.find('moqui.security.UserGroupMember').condition('userGroupId', 'ADMIN').condition('userId', USER_ID).deleteAll() } }
        finally { if (!off) ec.artifactExecution.enableAuthz() }
        ec.user.logoutUser()
        assert ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser(USERNAME, false)
        when: LlmDataUpgrade.protectContentMedia(ec, '', 10)
        then: thrown(IllegalStateException)
        when: LlmDataUpgrade.migrateConversationModel(ec, null, 10)
        then: thrown(IllegalStateException)
        cleanup:
        off = ec.artifactExecution.disableAuthz()
        try { ec.transaction.runUseOrBegin(null, 'admin back') { ec.entity.makeValue('moqui.security.UserGroupMember').setAll([userGroupId: 'ADMIN', userId: USER_ID, fromDate: new Timestamp(0)]).createOrUpdate() } }
        finally { if (!off) ec.artifactExecution.enableAuthz() }
        ec.user.logoutUser()
        assert ((org.moqui.impl.context.UserFacadeImpl) ec.user).internalLoginUser(USERNAME, false)
    }

    // ---- conversations stored by the version that kept every Open Responses conversation as a transcript of messages

    private String legacyConversation(String profileName, List<List> transcript, List<LlmItem> runContext, boolean completeRun = true) {
        def conv = LlmConversationImpl.create(ec, profileName, null)
        boolean off = ec.artifactExecution.disableAuthz()
        try {
            ec.transaction.runUseOrBegin(null, 'legacy transcript') {
                // the older version wrote no model; the row looks like the message model's
                ec.entity.find('moqui.llm.LlmConversation').condition('conversationId', conv.conversationId).updateAll([messageModel: null] as Map<String, Object>)
                int ordinal = 0
                transcript.each { List row ->
                    LlmMessage m = new LlmMessage(row[0] as LlmMessage.Role, row[1] as String)
                    m.ordinal = ordinal++
                    LlmMessageStore.write(ec, conv.conversationId, m, true)
                }
                if (runContext != null) {
                    Map run = LlmRunStore.createRun(ec, [conversationId: conv.conversationId, profileName: profileName, context: runContext, checkpoint: [phase: 'ready_provider'], maxIterations: 4])
                    LlmRunStore.transition(ec, run.runId as String, LlmRunStore.RUNNING, null)
                    if (completeRun) LlmRunStore.transition(ec, run.runId as String, LlmRunStore.COMPLETE, null)
                }
            }
        } finally { if (!off) ec.artifactExecution.enableAuthz() }
        conv.conversationId
    }

    private static LlmItem msg(String role, String text) {
        LlmItem.message(role, [role == 'assistant' ? LlmContentPart.outputText(text) : LlmContentPart.inputText(text)])
    }

    private String modelOf(String id) { ec.entity.find('moqui.llm.LlmConversation').condition('conversationId', id).disableAuthz().useCache(false).one().messageModel }

    def "conversations stored as a transcript move to the item model only from a proven source, and say what they could not move"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, answer('resp_mg1', 'new answer'))
        def p = profile(provider.endpoint, 'migrate-profile')
        register(p)
        def ccProfile = profile(provider.endpoint, 'migrate-cc-profile', new org.moqui.llm.test.FakeLlmProtocol())
        register(ccProfile)
        // a copy: the rows say what the run's trajectory says; the run's items also carry the system message and the wrapped context of the old window
        String copy = legacyConversation(p.name, [[LlmMessage.Role.SYSTEM, 'be brief'], [LlmMessage.Role.USER, 'old question'], [LlmMessage.Role.ASSISTANT, 'old answer']],
                [msg('system', 'be brief'), msg('user', '<untrusted-context source="session">user facts</untrusted-context>'), msg('user', 'old question'), msg('assistant', 'old answer')])
        // not a copy: the rows were edited or came from somewhere else
        String different = legacyConversation(p.name, [[LlmMessage.Role.USER, 'something else'], [LlmMessage.Role.ASSISTANT, 'rows that differ']],
                [msg('user', 'old question'), msg('assistant', 'old answer')])
        // text but no completed run: nothing of Open Responses to take the trajectory from
        String noSource = legacyConversation(p.name, [[LlmMessage.Role.USER, 'a question'], [LlmMessage.Role.ASSISTANT, 'an answer']], null)
        String failedOnly = legacyConversation(p.name, [[LlmMessage.Role.USER, 'a question'], [LlmMessage.Role.ASSISTANT, 'an answer']], [msg('user', 'a question')], false)
        // nothing at all: an empty conversation can simply be of the new model
        String empty = legacyConversation(p.name, [], null)
        // a message protocol is none of this upgrade's business
        String cc = legacyConversation(ccProfile.name, [[LlmMessage.Role.USER, 'cc question'], [LlmMessage.Role.ASSISTANT, 'cc answer']], null)
        Set mine = [copy, different, noSource, failedOnly, empty, cc] as Set
        when:
        Map report = null
        String after = ''
        List<String> migrated = [], kept = [], without = []
        for (int i = 0; i < 200; i++) {
            report = LlmDataUpgrade.migrateConversationModel(ec, after, 2)
            migrated.addAll(report.migrated as List); kept.addAll(report.keptMessageRows as List); without.addAll(report.withoutSource as List)
            after = report.lastConversationId
            if (!report.more) break
        }
        Map second = LlmDataUpgrade.migrateConversationModel(ec, null, 500)
        then:
        migrated.findAll { mine.contains(it) } as Set == [copy, different, empty] as Set
        kept.findAll { mine.contains(it) } == [different]
        without.findAll { mine.contains(it) } as Set == [noSource, failedOnly] as Set
        modelOf(copy) == 'ITEMS_MIGRATED'
        modelOf(different) == 'ITEMS_LEGACY'
        modelOf(empty) == 'ITEMS'
        modelOf(noSource) == null && modelOf(failedOnly) == null && modelOf(cc) == null
        LlmMessageStore.count(ec, copy) == 0
        LlmMessageStore.count(ec, different) == 2
        LlmMessageStore.count(ec, noSource) == 2 && LlmMessageStore.count(ec, cc) == 2
        and: 'a second pass moves nothing of ours and reads no message row of a moved conversation'
        !(second.migrated as List).contains(copy) && !(second.migrated as List).contains(different)

        when: 'the copy continues: history and the next wire are the trajectory, without the old window leftovers'
        long before = LlmMessageStore.accessCount()
        turn(p, LlmConversationImpl.load(ec, copy, true)) { it.system('be brief').user('next question') }
        def input = provider.requests[0].json.input.collect { m -> (m.content ?: []).collect { it.text }.join('') }
        def view = LlmConversationImpl.load(ec, copy, true).history.findAll { it.role != LlmMessage.Role.SYSTEM }*.content
        then:
        input == ['old question', 'old answer', 'next question']
        provider.requests[0].json.instructions == 'be brief'
        view == ['old question', 'old answer', 'next question', 'new answer']
        LlmMessageStore.accessCount() == before

        when: 'a conversation that could not be moved says so when an item protocol is used on it'
        turn(p, LlmConversationImpl.load(ec, noSource, true)) { it.user('go on') }
        then:
        org.moqui.llm.LlmException refused = thrown()
        refused.message.contains('already has a message transcript')
        cleanup:
        unregister(p.name); unregister(ccProfile.name)
        provider.close()
    }

    def "the diagnostic views show where media is and how big, never the data or a signed query"() {
        expect:
        LlmDataUpgrade.safeMedia('data:image/png;base64,' + PNG) == 'data:image/png;base64,(' + PNG.length() + ' characters not shown)'
        LlmDataUpgrade.safeMedia('https://user:pw@files.example.test:8443/a/b.pdf?X-Amz-Signature=abc#frag') == 'https://files.example.test:8443/a/b.pdf?(query not shown)'
        LlmDataUpgrade.safeMedia('https://files.example.test/a/b.pdf') == 'https://files.example.test/a/b.pdf'
        LlmDataUpgrade.safeMedia('component://tools/resource/x.png') == 'component:///resource/x.png' || LlmDataUpgrade.safeMedia('component://tools/resource/x.png').startsWith('component:')
        LlmDataUpgrade.safeMedia(null) == null
        LlmDataUpgrade.safeMedia('x' * 300).length() == 203
    }

    def "the errors, metadata and incomplete details a provider returned are ciphertext, and plain ones of an older version are converted"() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, '{"id":"resp_diag","object":"response","status":"incomplete","model":"gpt-test","metadata":{"customer":"ACME-SECRET"},' +
                '"incomplete_details":{"reason":"max_output_tokens"},"output":[{"id":"msg_d","type":"message","role":"assistant","status":"incomplete","content":[{"type":"output_text","text":"cut"}]}]}')
        def p = profile(provider.endpoint)
        def conv = LlmConversationImpl.create(ec, p.name, null)
        turn(p, conv) { it.user('diag') }
        def row = ec.entity.find('moqui.llm.LlmResponse').condition('providerResponseId', 'resp_diag').disableAuthz().useCache(false).list().last()
        String id = row.llmResponseId
        expect: 'what the provider said about the response is not readable in the table'
        !(rawValue('moqui.llm.LlmResponse', 'llmResponseId', id, 'metadataJson') ?: '').contains('ACME-SECRET')
        !(rawValue('moqui.llm.LlmResponse', 'llmResponseId', id, 'incompleteDetailsJson') ?: '').contains('max_output_tokens')
        when: 'an older version wrote them in plain'
        rawSet('moqui.llm.LlmResponse', 'llmResponseId', id, 'metadataJson', '{"customer":"ACME-SECRET"}')
        rawSet('moqui.llm.LlmResponse', 'llmResponseId', id, 'errorJson', '{"message":"echo of the prompt"}')
        Map first = LlmDataUpgrade.protectResponseDiagnostics(ec, '', 1000)
        Map again = LlmDataUpgrade.protectResponseDiagnostics(ec, '', 1000)
        def back = ec.entity.find('moqui.llm.LlmResponse').condition('llmResponseId', id).disableAuthz().useCache(false).one()
        then:
        first.converted >= 2
        again.converted == 0
        !rawValue('moqui.llm.LlmResponse', 'llmResponseId', id, 'metadataJson').contains('ACME-SECRET')
        !rawValue('moqui.llm.LlmResponse', 'llmResponseId', id, 'errorJson').contains('echo')
        back.metadataJson.contains('ACME-SECRET')
        back.errorJson.contains('echo of the prompt')
        cleanup: provider.close()
    }
}
