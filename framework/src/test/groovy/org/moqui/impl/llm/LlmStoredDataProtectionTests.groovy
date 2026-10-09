package org.moqui.impl.llm

import org.moqui.llm.LlmContentPart
import org.moqui.llm.LlmItem

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

/** What the tables physically hold of what a turn stored: ciphertext in the sensitive columns, and what a diagnostic view shows of media. */
class LlmStoredDataProtectionTests extends LlmConversationSpecBase {

    /** The value of a column as the database holds it, not as the entity engine returns it. */
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

    def 'the image of a message is ciphertext in the table, and the entity gives back what was stored'() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, answer('resp_m1', 'seen'))
        def p = profile(provider.endpoint)
        def conv = LlmConversationImpl.create(ec, p.name, null)
        String dataUri = 'data:image/png;base64,' + PNG
        when:
        turn(p, conv) { it.inputItems([LlmItem.message('user', [LlmContentPart.inputText('look'), image(dataUri)])]) }
        String contentId = ec.entity.find('moqui.llm.LlmContent').condition('contentType', 'input_image').disableAuthz().useCache(false).list().collect { it.llmContentId }.max()
        String stored = rawValue('moqui.llm.LlmContent', 'llmContentId', contentId, 'imageUrl')
        then:
        stored != null && !stored.contains('data:image') && !stored.contains(PNG)
        ec.entity.find('moqui.llm.LlmContent').condition('llmContentId', contentId).disableAuthz().useCache(false).one().imageUrl == dataUri
        cleanup: provider.close()
    }

    def 'what a provider returned about a response (metadata, incomplete details) is ciphertext in the table'() {
        given:
        def provider = new LlmResponsesClientIntegrationTests.ScriptedResponsesProvider()
        provider.enqueueJson(200, '{"id":"resp_diag","object":"response","status":"incomplete","model":"gpt-test","metadata":{"customer":"ACME-SECRET"},' +
                '"incomplete_details":{"reason":"max_output_tokens"},"output":[{"id":"msg_d","type":"message","role":"assistant","status":"incomplete","content":[{"type":"output_text","text":"cut"}]}]}')
        def p = profile(provider.endpoint)
        def conv = LlmConversationImpl.create(ec, p.name, null)
        when:
        turn(p, conv) { it.user('diag') }
        def row = ec.entity.find('moqui.llm.LlmResponse').condition('providerResponseId', 'resp_diag').disableAuthz().useCache(false).list().last()
        then:
        !(rawValue('moqui.llm.LlmResponse', 'llmResponseId', row.llmResponseId, 'metadataJson') ?: '').contains('ACME-SECRET')
        !(rawValue('moqui.llm.LlmResponse', 'llmResponseId', row.llmResponseId, 'incompleteDetailsJson') ?: '').contains('max_output_tokens')
        row.metadataJson.contains('ACME-SECRET')
        cleanup: provider.close()
    }

    def 'the diagnostic views show where media is and how big, never the data or a signed query'() {
        expect:
        MediaView.safeMedia('data:image/png;base64,' + PNG) == 'data:image/png;base64,(' + PNG.length() + ' characters not shown)'
        MediaView.safeMedia('https://user:pw@files.example.test:8443/a/b.pdf?X-Amz-Signature=abc#frag') == 'https://files.example.test:8443/a/b.pdf?(query not shown)'
        MediaView.safeMedia('https://files.example.test/a/b.pdf') == 'https://files.example.test/a/b.pdf'
        MediaView.safeMedia('component://tools/resource/x.png').startsWith('component:')
        MediaView.safeMedia(null) == null
        MediaView.safeMedia('x' * 300).length() == 203
    }
}
