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
package org.moqui.impl.llm

import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityValue
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.llm.LlmMessage
import org.moqui.llm.LlmProtocol

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * Upgrades of stored Open Responses data that cannot be done by adding a column. Every method works a batch at a time
 * from a key, can be stopped and run again, never deletes what it cannot show to be a copy, and needs an administrator.
 */
final class LlmDataUpgrade {
    static final List<String> CONTENT_COLUMNS = ['imageUrl', 'fileUrl', 'videoUrl', 'contentLocation']

    private LlmDataUpgrade() { }

    /**
     * What a diagnostic view may show of a media location: a data URI as its type and size, a URL without user info,
     * query and fragment (a signed URL carries its credential there), any other reference shortened. The value itself
     * is available only to the replay of its own conversation.
     */
    static String safeMedia(String value) {
        if (value == null) return null
        if (value.startsWith('data:')) {
            int comma = value.indexOf(',')
            String head = comma > 0 ? value.substring(0, comma) : value.take(40)
            return head + ',(' + Math.max(value.length() - (comma + 1), 0) + ' characters not shown)'
        }
        try {
            URI uri = new URI(value)
            if (uri.scheme != null && uri.rawAuthority != null) {
                String host = uri.host ?: ''
                return uri.scheme + '://' + host + (uri.port > 0 ? ':' + uri.port : '') + (uri.rawPath ?: '') + (uri.rawQuery != null || uri.rawFragment != null ? '?(query not shown)' : '')
            }
        } catch (URISyntaxException ignored) { }
        value.length() > 200 ? value.take(200) + '...' : value
    }

    private static void requireAdmin(ExecutionContext ec) {
        if (!ec.user.isInGroup('ADMIN')) throw new IllegalStateException('Upgrading stored LLM data needs an administrator')
    }

    /**
     * Writes the media columns of stored content (image, file and video locations, which can hold a whole data URI or a
     * signed URL) as ciphertext, like the other sensitive columns. A value that already decrypts is left alone, so the
     * method can be repeated; each value is replaced only if it is still what was read, so a concurrent writer is not
     * overwritten. The columns are read and written with SQL because a row with a plain value in a column that is now
     * encrypted cannot be read as an entity. Back up first.
     */
    static Map<String, Object> protectContentMedia(ExecutionContext ec, String afterLlmContentId, int batchSize) {
        Map<String, Object> r = protectColumns(ec, 'moqui.llm.LlmContent', 'llmContentId', CONTENT_COLUMNS, afterLlmContentId, batchSize)
        r.put('lastLlmContentId', r.remove('last'))
        r
    }

    /** What a provider returned about a response besides its payload: errors, metadata and incomplete details. */
    static final List<String> RESPONSE_COLUMNS = ['errorJson', 'metadataJson', 'incompleteDetailsJson']

    /** Same as {@link #protectContentMedia} for the columns of LlmResponse that now hold ciphertext. */
    static Map<String, Object> protectResponseDiagnostics(ExecutionContext ec, String afterLlmResponseId, int batchSize) {
        Map<String, Object> r = protectColumns(ec, 'moqui.llm.LlmResponse', 'llmResponseId', RESPONSE_COLUMNS, afterLlmResponseId, batchSize)
        r.put('lastLlmResponseId', r.remove('last'))
        r
    }

    private static Map<String, Object> protectColumns(ExecutionContext ec, String entityName, String idField, List<String> fields,
                                                      String afterId, int batchSize) {
        requireAdmin(ec)
        int size = Math.min(Math.max(batchSize, 1), 1000)
        EntityFacadeImpl efi = (EntityFacadeImpl) ec.entity
        EntityDefinition ed = efi.getEntityDefinition(entityName)
        String table = ed.getFullTableName()
        String idColumn = ed.getColumnName(idField)
        Map<String, String> columns = fields.collectEntries { [(it): ed.getColumnName(it)] } as Map<String, String>
        int converted = 0, already = 0, rowsSeen = 0
        String last = afterId ?: ''
        boolean more = false
        boolean disabled = ec.artifactExecution.disableAuthz()
        try {
            ec.transaction.runRequireNew(300, 'Error protecting LLM stored columns', {
                Connection con = ec.entity.getConnection(ed.getEntityGroupName())
                String select = 'SELECT ' + idColumn + ', ' + columns.values().join(', ') + ' FROM ' + table +
                        ' WHERE ' + idColumn + ' > ? ORDER BY ' + idColumn
                List<Map<String, String>> rows = []
                PreparedStatement ps = con.prepareStatement(select)
                try {
                    ps.setString(1, last)
                    ps.setMaxRows(size + 1)
                    ResultSet rs = ps.executeQuery()
                    try {
                        while (rs.next()) {
                            Map<String, String> row = ['id': rs.getString(1)]
                            int i = 2
                            for (String field : columns.keySet()) row.put(field, rs.getString(i++))
                            rows.add(row)
                        }
                    } finally { rs.close() }
                } finally { ps.close() }
                more = rows.size() > size
                for (Map<String, String> row : rows.take(size)) {
                    rowsSeen++
                    last = row.id
                    for (String field : columns.keySet()) {
                        String value = row.get(field)
                        if (value == null || value.isEmpty()) continue
                        if (isCiphertext(efi, value)) { already++; continue }
                        String cipher = (String) EntityJavaUtilBridge.encrypt(efi, value)
                        PreparedStatement up = con.prepareStatement('UPDATE ' + table + ' SET ' + columns.get(field) +
                                ' = ? WHERE ' + idColumn + ' = ? AND ' + columns.get(field) + ' = ?')
                        try {
                            up.setString(1, cipher); up.setString(2, row.id); up.setString(3, value)
                            if (up.executeUpdate() == 1) converted++
                        } finally { up.close() }
                    }
                }
            })
        } finally { if (!disabled) ec.artifactExecution.enableAuthz() }
        [converted: converted, alreadyProtected: already, rows: rowsSeen, last: last, more: more]
    }

    /** True when the value is what an encrypted column holds, shown by decrypting it, not by its look. */
    static boolean isCiphertext(EntityFacadeImpl efi, String value) {
        if (!(value ==~ /[A-Za-z0-9_\-]*:[A-Za-z0-9_=\-]+/) && !(value ==~ /[0-9A-Fa-f]+/)) return false
        try { EntityJavaUtilBridge.decrypt(efi, value); return true } catch (Exception ignored) { return false }
    }

    /**
     * Moves the conversations of an item protocol that were stored as a transcript of messages to the item model. The
     * trajectory is taken only from a source that is Open Responses data: the context of the latest completed run of the
     * conversation. When the old message rows say the same as that trajectory they were a copy and are deleted;
     * when they say something else they are kept (ITEMS_LEGACY) and are never read again. A conversation without a
     * completed run has no such source: it is reported and left as it is, and a conversation of a message protocol is
     * not touched. Nothing is invented, and a conversation that was moved is not looked at again.
     */
    static Map<String, Object> migrateConversationModel(ExecutionContext ec, String afterConversationId, int batchSize) {
        requireAdmin(ec)
        int size = Math.min(Math.max(batchSize, 1), 500)
        List<String> migrated = [], keptRows = [], noSource = [], noProfile = []
        int skippedMessageProtocol = 0
        String last = afterConversationId
        boolean more = false
        boolean disabled = ec.artifactExecution.disableAuthz()
        try {
            ec.transaction.runRequireNew(600, 'Error moving LLM conversations to the item model', {
                def find = ec.entity.find('moqui.llm.LlmConversation').condition('messageModel', EntityCondition.EQUALS, null)
                        .orderBy('conversationId').limit(size + 1).useCache(false)
                if (afterConversationId) find.condition('conversationId', EntityCondition.GREATER_THAN, afterConversationId)
                List<EntityValue> rows = find.list() as List<EntityValue>
                more = rows.size() > size
                for (EntityValue conv : rows.take(size)) {
                    String id = conv.getString('conversationId')
                    last = id
                    LlmFacadeImpl.ProfileState profile = ec.llm instanceof LlmFacadeImpl ? ((LlmFacadeImpl) ec.llm).getProfileState(conv.getString('profileName')) : null
                    if (profile == null || profile.protocol == null) { noProfile.add(id); continue }
                    if (!profile.protocol.getCapabilities().contains(LlmProtocol.Capability.ITEM_TRAJECTORY)) { skippedMessageProtocol++; continue }
                    EntityValue locked = ec.entity.find('moqui.llm.LlmConversation').condition('conversationId', id).forUpdate(true).useCache(false).one()
                    if (locked == null || locked.getString('messageModel') != null) continue
                    List<LlmMessage> rowsOfMessages = LlmMessageStore.readAll(ec, id)
                    EntityValue run = ec.entity.find('moqui.llm.LlmRun').condition('conversationId', id)
                            .condition('statusId', LlmRunStore.COMPLETE).orderBy('-completedDate').orderBy('-runId').limit(1).useCache(false).list().find { true }
                    if (run == null) {
                        if (rowsOfMessages.any { it.role == LlmMessage.Role.USER || it.role == LlmMessage.Role.ASSISTANT }) { noSource.add(id); continue }
                        locked.messageModel = LlmConversationImpl.MODEL_ITEMS
                        locked.update()
                        migrated.add(id)
                        continue
                    }
                    List items = OpenResponsesCodec.itemsFromStored(LlmJson.toObject(run.getString('contextJson')))
                            .findAll { it != null && !Boolean.TRUE.equals(it.ephemeral) && !LlmConversationImpl.isWindowLeftover(it) }
                    boolean copy = sameTranscript(rowsOfMessages, ItemHistory.toMessages(items))
                    locked.messageModel = copy ? LlmConversationImpl.MODEL_ITEMS_MIGRATED : LlmConversationImpl.MODEL_ITEMS_LEGACY
                    locked.headRunId = run.getString('runId')
                    locked.headVersion = 1L
                    locked.update()
                    if (copy) LlmMessageStore.deleteAll(ec, id) else keptRows.add(id)
                    migrated.add(id)
                }
            })
        } finally { if (!disabled) ec.artifactExecution.enableAuthz() }
        [migrated: migrated, keptMessageRows: keptRows, withoutSource: noSource, withoutProfile: noProfile,
         skippedMessageProtocol: skippedMessageProtocol, lastConversationId: last, more: more]
    }

    /** The rows say what the trajectory says: user and assistant text and tool results, in the same order. */
    static boolean sameTranscript(List<LlmMessage> rows, List<LlmMessage> view) {
        List<List> a = shape(rows), b = shape(view)
        a == b
    }

    private static List<List> shape(List<LlmMessage> messages) {
        messages.findAll { it.role in [LlmMessage.Role.USER, LlmMessage.Role.ASSISTANT, LlmMessage.Role.TOOL] }
                .collect { [it.role.name(), (it.content ?: '').trim(), it.toolCallId ?: '', (it.toolCalls ?: []).collect { c -> c.id }] }
    }
}
