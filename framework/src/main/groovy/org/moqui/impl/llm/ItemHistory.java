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

import org.moqui.llm.LlmContentPart;
import org.moqui.llm.LlmItem;
import org.moqui.llm.LlmMessage;
import org.moqui.llm.LlmToolCall;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The view of a structured trajectory as chat messages: user, assistant (with its tool calls) and tool results, with
 * the text of each and a description of its attachments. It is a projection for people and for the parts of the
 * code that think in messages (title, summary, window); the trajectory itself is the list of items, which is what is
 * replayed to the provider and what keeps opaque reasoning, images and ids intact. Nothing here is stored.
 */
final class ItemHistory {
    private ItemHistory() { }

    static List<LlmMessage> toMessages(List<LlmItem> items) {
        List<LlmMessage> out = new ArrayList<>();
        if (items == null) return out;
        Map<String, String> toolNames = new LinkedHashMap<>();
        for (LlmItem item : items) if (item != null && "function_call".equals(item.type) && item.callId != null) toolNames.put(item.callId, item.name);
        LlmMessage lastAssistant = null;
        int[] attachmentIndex = {0};
        for (LlmItem item : items) {
            if (item == null || item.type == null || Boolean.TRUE.equals(item.ephemeral)) continue;
            switch (item.type) {
                case "message": {
                    String role = item.role != null ? item.role : "user";
                    if ("system".equals(role) || "developer".equals(role)) { lastAssistant = null; break; }
                    LlmMessage m = new LlmMessage("assistant".equals(role) ? LlmMessage.Role.ASSISTANT : LlmMessage.Role.USER, text(item.content));
                    m.messageId = item.providerItemId != null ? item.providerItemId : null;
                    List<Map<String, Object>> attachments = attachments(item.content, attachmentIndex);
                    if (!attachments.isEmpty()) put(m, "attachments", attachments);
                    if (item.status != null && !"completed".equals(item.status)) put(m, "status", item.status);
                    if (refused(item.content)) put(m, "refusal", Boolean.TRUE);
                    out.add(m);
                    lastAssistant = m.role == LlmMessage.Role.ASSISTANT ? m : null;
                    break;
                }
                case "function_call": {
                    if (lastAssistant == null) {
                        lastAssistant = new LlmMessage(LlmMessage.Role.ASSISTANT, "");
                        out.add(lastAssistant);
                    }
                    LlmToolCall call = new LlmToolCall(item.callId, item.name, item.arguments);
                    if (lastAssistant.toolCalls == null) lastAssistant.toolCalls = new ArrayList<>();
                    lastAssistant.toolCalls.add(call);
                    break;
                }
                case "function_call_output": {
                    LlmMessage tool = new LlmMessage(LlmMessage.Role.TOOL, outputText(item.output));
                    tool.toolCallId = item.callId;
                    tool.name = toolNames.get(item.callId);
                    out.add(tool);
                    lastAssistant = null;
                    break;
                }
                default:
                    // reasoning, compaction, item references and the like are kept in the trajectory and not shown
                    break;
            }
        }
        return out;
    }

    private static String text(List<LlmContentPart> parts) {
        StringBuilder sb = new StringBuilder();
        if (parts != null) for (LlmContentPart part : parts) {
            if (part == null) continue;
            String piece = part.text != null ? part.text : part.refusal;
            if (piece != null) { if (sb.length() > 0) sb.append('\n'); sb.append(piece); }
        }
        return sb.toString();
    }

    private static boolean refused(List<LlmContentPart> parts) {
        if (parts != null) for (LlmContentPart part : parts) if (part != null && part.refusal != null) return true;
        return false;
    }

    private static boolean isAttachment(LlmContentPart part) {
        return part != null && part.type != null && (part.type.equals("input_image") || part.type.equals("input_file") || part.type.equals("input_video"));
    }

    private static List<Map<String, Object>> attachments(List<LlmContentPart> parts, int[] counter) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (parts == null) return out;
        for (LlmContentPart part : parts) {
            if (!isAttachment(part)) continue;
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("index", counter[0]++);
            a.put("type", part.type);
            Bytes bytes = bytesOf(part);
            String mediaType = part.mediaType != null ? part.mediaType : bytes != null ? bytes.mediaType : null;
            if (mediaType != null) a.put("mediaType", mediaType);
            if (part.filename != null) a.put("filename", part.filename);
            if (bytes != null) { a.put("length", (long) bytes.data.length); a.put("sha256", sha256(bytes.data)); }
            else {
                if (part.contentLength != null) a.put("length", part.contentLength);
                if (part.contentSha256 != null) a.put("sha256", part.contentSha256);
            }
            out.add(a);
        }
        return out;
    }

    /** The decoded content of an attachment kept in the trajectory itself (a data URI or base64 file data); null otherwise. */
    static final class Bytes {
        final byte[] data;
        final String mediaType;
        Bytes(byte[] data, String mediaType) { this.data = data; this.mediaType = mediaType; }
    }

    static Bytes bytesOf(LlmContentPart part) {
        try {
            if (part.imageUrl != null && part.imageUrl.startsWith("data:")) {
                int comma = part.imageUrl.indexOf(',');
                int semi = part.imageUrl.indexOf(';');
                if (comma < 0) return null;
                String mime = part.imageUrl.substring(5, semi > 5 && semi < comma ? semi : comma);
                return new Bytes(java.util.Base64.getDecoder().decode(part.imageUrl.substring(comma + 1)), mime.isEmpty() ? null : mime);
            }
            if (part.fileData != null && !part.fileData.isEmpty())
                return new Bytes(java.util.Base64.getDecoder().decode(part.fileData), part.mediaType);
        } catch (IllegalArgumentException e) { return null; }
        return null;
    }

    private static String sha256(byte[] data) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** The attachment with this index in the trajectory, counted as {@link #toMessages} counts them; null when there is none. */
    static LlmContentPart attachmentAt(List<LlmItem> items, int wanted) {
        int n = 0;
        if (items == null) return null;
        for (LlmItem item : items) {
            if (item == null || item.type == null || Boolean.TRUE.equals(item.ephemeral) || !"message".equals(item.type)) continue;
            String role = item.role != null ? item.role : "user";
            if ("system".equals(role) || "developer".equals(role)) continue;
            if (item.content != null) for (LlmContentPart part : item.content) {
                if (!isAttachment(part)) continue;
                if (n++ == wanted) return part;
            }
        }
        return null;
    }

    private static String outputText(Object output) {
        if (output == null) return "";
        if (output instanceof CharSequence) return output.toString();
        if (output instanceof List) {
            StringBuilder sb = new StringBuilder();
            for (Object part : (List<?>) output) {
                if (part instanceof Map && ((Map<?, ?>) part).get("text") != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(((Map<?, ?>) part).get("text"));
                } else if (part instanceof LlmContentPart && ((LlmContentPart) part).text != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(((LlmContentPart) part).text);
                }
            }
            return sb.toString();
        }
        return LlmJson.toJson(output);
    }

    private static void put(LlmMessage m, String key, Object value) {
        if (m.metadata == null) m.metadata = new LinkedHashMap<>();
        m.metadata.put(key, value);
    }
}
