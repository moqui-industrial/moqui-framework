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
package org.moqui.impl.llm;

import org.moqui.context.ExecutionContext;
import org.moqui.entity.EntityList;
import org.moqui.entity.EntityValue;
import org.moqui.llm.LlmMessage;
import org.moqui.llm.LlmToolCall;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The only code that reads or writes the {@code moqui.llm.LlmMessage} entity. That entity is the transcript of a
 * conversation of a message protocol (Chat Completions); a conversation of the Open Responses model keeps its
 * trajectory as structured items in its runs and never comes here. A test asserts both that no other class names the
 * entity and, through {@link #accessCount}, that an Open Responses turn performs no access at all.
 */
final class LlmMessageStore {
    static final String ENTITY = "moqui.llm.LlmMessage";
    private static final AtomicLong ACCESSES = new AtomicLong();

    private LlmMessageStore() { }

    /** Number of entity operations since the JVM started; only differences between two reads mean anything. */
    static long accessCount() { return ACCESSES.get(); }

    private static void touch() { ACCESSES.incrementAndGet(); }

    static void write(ExecutionContext ec, String conversationId, LlmMessage m, boolean create) {
        if (ec == null) return;
        touch();
        EntityValue ev;
        if (create || m.messageId == null) {
            ev = ec.getEntity().makeValue(ENTITY);
            ev.setSequencedIdPrimary();
            m.messageId = ev.getString("messageId");
            ev.set("conversationId", conversationId);
            fill(ec, ev, m);
            ev.create();
        } else {
            ev = ec.getEntity().find(ENTITY).condition("messageId", m.messageId).one();
            if (ev == null) {
                ev = ec.getEntity().makeValue(ENTITY);
                ev.set("messageId", m.messageId);
                ev.set("conversationId", conversationId);
                fill(ec, ev, m);
                ev.create();
            } else {
                fill(ec, ev, m);
                ev.update();
            }
        }
    }

    static void remove(ExecutionContext ec, String messageId) {
        if (ec == null || messageId == null) return;
        touch();
        EntityValue ev = ec.getEntity().find(ENTITY).condition("messageId", messageId).one();
        if (ev != null) ev.delete();
    }

    static List<LlmMessage> readAll(ExecutionContext ec, String conversationId) {
        touch();
        List<LlmMessage> out = new ArrayList<>();
        EntityList list = ec.getEntity().find(ENTITY).condition("conversationId", conversationId).orderBy("ordinal").list();
        int size = list.size();
        for (int i = 0; i < size; i++) out.add(fromEntity(list.get(i)));
        return out;
    }

    static int nextOrdinal(ExecutionContext ec, String conversationId) {
        touch();
        EntityList el = ec.getEntity().find(ENTITY).condition("conversationId", conversationId)
                .orderBy("-ordinal").limit(1).useCache(false).list();
        if (el != null && el.size() > 0) {
            Long o = el.getFirst().getLong("ordinal");
            if (o != null) return o.intValue() + 1;
        }
        return 0;
    }

    static long deleteAll(ExecutionContext ec, String conversationId) {
        touch();
        return ec.getEntity().find(ENTITY).condition("conversationId", conversationId).deleteAll();
    }

    static long count(ExecutionContext ec, String conversationId) {
        touch();
        return ec.getEntity().find(ENTITY).condition("conversationId", conversationId).useCache(false).disableAuthz().count();
    }

    /** Only for the summary backfill of a conversation of the message model. */
    static Timestamp earliestSentDate(ExecutionContext ec, String conversationId) {
        touch();
        EntityList list = ec.getEntity().find(ENTITY).condition("conversationId", conversationId).orderBy("sentDate").limit(1).list();
        return list == null || list.isEmpty() ? null : list.get(0).getTimestamp("sentDate");
    }

    static String firstUserContent(ExecutionContext ec, String conversationId) {
        touch();
        EntityList list = ec.getEntity().find(ENTITY).condition("conversationId", conversationId)
                .condition("role", "USER").orderBy("ordinal").limit(1).list();
        return list == null || list.isEmpty() ? null : list.get(0).getString("content");
    }

    private static void fill(ExecutionContext ec, EntityValue ev, LlmMessage m) {
        ev.set("ordinal", m.ordinal);
        ev.set("role", m.role != null ? m.role.name() : LlmMessage.Role.USER.name());
        ev.set("content", m.content);
        ev.set("name", m.name);
        ev.set("toolCallId", m.toolCallId);
        ev.set("toolCallsJson", m.toolCalls == null || m.toolCalls.isEmpty() ? null : LlmJson.toJson(m.toolCalls));
        ev.set("metadataJson", m.metadata == null || m.metadata.isEmpty() ? null : LlmJson.toJson(m.metadata));
        ev.set("tokenEstimate", tokenEstimate(m.content));
        ev.set("sentDate", m.sentDate != null ? m.sentDate
                : ec.getUser() != null ? ec.getUser().getNowTimestamp() : new Timestamp(System.currentTimeMillis()));
    }

    private static int tokenEstimate(String content) {
        if (content == null || content.isEmpty()) return 0;
        return Math.max(1, content.length() / 4);
    }

    private static LlmMessage fromEntity(EntityValue ev) {
        LlmMessage m = new LlmMessage();
        m.messageId = ev.getString("messageId");
        Long ord = ev.getLong("ordinal");
        m.ordinal = ord != null ? ord.intValue() : 0;
        String role = ev.getString("role");
        try {
            m.role = role != null ? LlmMessage.Role.valueOf(role) : LlmMessage.Role.USER;
        } catch (IllegalArgumentException e) {
            m.role = LlmMessage.Role.USER;
        }
        m.content = ev.getString("content");
        m.name = ev.getString("name");
        m.toolCallId = ev.getString("toolCallId");
        m.toolCalls = parseToolCalls(ev.getString("toolCallsJson"));
        m.metadata = LlmJson.toMap(ev.getString("metadataJson"));
        m.sentDate = ev.getTimestamp("sentDate");
        return m;
    }

    /** Tool calls as stored in a message row and in the pending tool calls of a conversation header. */
    @SuppressWarnings("unchecked")
    static List<LlmToolCall> parseToolCalls(String json) {
        List<LlmToolCall> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        Object obj = LlmJson.toObject(json);
        if (!(obj instanceof List)) return out;
        for (Object item : (List<?>) obj) {
            if (item instanceof LlmToolCall) {
                out.add((LlmToolCall) item);
            } else if (item instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) item;
                LlmToolCall tc = new LlmToolCall();
                Object id = map.get("id");
                Object name = map.get("name");
                Object args = map.get("arguments");
                tc.id = id != null ? id.toString() : null;
                tc.name = name != null ? name.toString() : null;
                if (args instanceof Map || args instanceof List) tc.arguments = LlmJson.toJson(args);
                else tc.arguments = args != null ? args.toString() : null;
                Object exec = map.get("execution");
                if (exec != null) {
                    try {
                        tc.execution = org.moqui.llm.LlmTool.Execution.valueOf(exec.toString().toUpperCase());
                    } catch (Exception ignored) { }
                }
                if (Boolean.TRUE.equals(map.get("confirm")) || "true".equals(String.valueOf(map.get("confirm"))))
                    tc.confirm = Boolean.TRUE;
                Object risk = map.get("risk");
                if (risk != null) tc.risk = risk.toString();
                out.add(tc);
            }
        }
        return out;
    }
}
