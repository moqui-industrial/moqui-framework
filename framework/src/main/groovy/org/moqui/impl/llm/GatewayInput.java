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
import org.moqui.llm.LlmException;
import org.moqui.llm.LlmFinishReason;
import org.moqui.llm.LlmItem;
import org.moqui.llm.LlmResponseOptions;
import org.moqui.llm.LlmTransport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What a browser may put in a gateway request beyond text: attachments and response options. The browser sends bytes, never
 * a place to fetch them from, and picks options from a short list, never the shape of the provider request.
 */
public final class GatewayInput {
    private GatewayInput() { }

    static final Set<String> IMAGE_TYPES = new LinkedHashSet<>(Arrays.asList("image/png", "image/jpeg", "image/gif", "image/webp"));
    static final Set<String> FILE_TYPES = new LinkedHashSet<>(Arrays.asList("application/pdf", "text/plain", "text/markdown",
            "text/csv", "application/json"));
    static final Set<String> DETAILS = new LinkedHashSet<>(Arrays.asList("low", "high", "auto"));
    /** Response options a browser may set; everything else on the provider request belongs to the server. */
    static final Set<String> OPTIONS = new LinkedHashSet<>(Arrays.asList("reasoning", "max_output_tokens", "temperature", "top_p",
            "text", "metadata", "parallel_tool_calls", "truncation", "safety_identifier", "prompt_cache_key"));
    static final int MAX_ATTACHMENTS = 8;

    private static LlmException bad(String message) {
        return new LlmException(message, null, LlmFinishReason.ERROR, 400, null, null);
    }

    private static long limit(String name, long dflt) {
        String v = org.moqui.util.SystemBinding.getPropOrEnv(name);
        if (v == null || v.isBlank()) return dflt;
        try { return Math.max(Long.parseLong(v.trim()), 0L); } catch (NumberFormatException e) { return dflt; }
    }

    /** The parts an attachments array stands for, checked: kind, media type, size, and that the data is base64 and nothing else. */
    public static List<LlmContentPart> attachments(Object raw) {
        List<LlmContentPart> parts = new ArrayList<>();
        if (raw == null) return parts;
        if (!(raw instanceof List)) throw bad("attachments must be an array");
        List<?> list = (List<?>) raw;
        if (list.size() > MAX_ATTACHMENTS) throw bad("at most " + MAX_ATTACHMENTS + " attachments are accepted");
        long each = limit("llm_gateway_attachment_max_bytes", 10L * 1024 * 1024);
        long total = limit("llm_gateway_attachments_max_bytes", 20L * 1024 * 1024);
        long sum = 0;
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map)) throw bad("attachments[" + i + "] must be an object");
            Map<?, ?> a = (Map<?, ?>) list.get(i);
            for (Object key : a.keySet())
                if (!Arrays.asList("kind", "mimeType", "data", "filename", "detail").contains(String.valueOf(key)))
                    throw bad("attachments[" + i + "]." + key + " is not accepted; send the content as base64 in data");
            String kind = text(a.get("kind"));
            String mime = text(a.get("mimeType"));
            mime = mime != null ? mime.toLowerCase(Locale.ROOT) : null;
            String data = text(a.get("data"));
            if (data == null) throw bad("attachments[" + i + "].data is required");
            if (data.regionMatches(true, 0, "data:", 0, 5) || data.contains("://"))
                throw bad("attachments[" + i + "].data must be bare base64, not a URL or data URI");
            byte[] bytes;
            try { bytes = Base64.getDecoder().decode(data); }
            catch (IllegalArgumentException e) { throw bad("attachments[" + i + "].data is not valid base64"); }
            if (bytes.length == 0) throw bad("attachments[" + i + "] is empty");
            if (bytes.length > each) throw bad("attachments[" + i + "] is larger than " + each + " bytes");
            sum += bytes.length;
            if (sum > total) throw bad("attachments together are larger than " + total + " bytes");
            LlmContentPart part = new LlmContentPart();
            if ("image".equals(kind)) {
                if (mime == null || !IMAGE_TYPES.contains(mime)) throw bad("attachments[" + i + "].mimeType must be one of " + IMAGE_TYPES);
                part.type = "input_image";
                part.mediaType = mime;
                part.imageUrl = "data:" + mime + ";base64," + data;
                String detail = text(a.get("detail"));
                if (detail != null && !DETAILS.contains(detail)) throw bad("attachments[" + i + "].detail must be one of " + DETAILS);
                part.detail = detail != null ? detail : "auto";
            } else if ("file".equals(kind)) {
                if (mime == null || !FILE_TYPES.contains(mime)) throw bad("attachments[" + i + "].mimeType must be one of " + FILE_TYPES);
                String name = text(a.get("filename"));
                if (name == null || name.length() > 255 || name.contains("/") || name.contains("\\") || name.contains(".."))
                    throw bad("attachments[" + i + "].filename is required and must be a plain file name");
                part.type = "input_file";
                part.mediaType = mime;
                part.filename = name;
                part.fileData = data;
            } else throw bad("attachments[" + i + "].kind must be image or file");
            parts.add(part);
        }
        return parts;
    }

    /** One user message: the text and then the attachments, in the order the person gave them. */
    public static LlmItem userMessage(String text, List<LlmContentPart> attachments) {
        List<LlmContentPart> content = new ArrayList<>();
        if (text != null && !text.isEmpty()) content.add(LlmContentPart.inputText(text));
        content.addAll(attachments);
        return LlmItem.message("user", content);
    }

    /** The options a browser sent, if every name is on the list. */
    public static LlmResponseOptions options(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof Map)) throw bad("options must be an object");
        LlmResponseOptions options = new LlmResponseOptions();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
            String name = String.valueOf(e.getKey());
            if (!OPTIONS.contains(name)) throw bad("option " + name + " is not accepted; allowed: " + OPTIONS);
            options.put(name, e.getValue());
        }
        return options;
    }

    /** Transport to the provider; it has nothing to do with how the browser receives the answer. */
    public static LlmTransport upstreamTransport(Object raw) {
        if (raw == null) return null;
        String v = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if ("http".equals(v)) return LlmTransport.HTTP;
        if ("websocket".equals(v)) return LlmTransport.WEBSOCKET;
        throw bad("upstreamTransport must be http or websocket");
    }

    private static String text(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * What the gateway can say about an answer that was asked to follow a JSON schema: only for a finished answer, only after
     * it was checked here against the schema of the request. Null when no schema was asked for. A refusal, an incomplete or
     * a failed answer is reported as that and is never cast to a valid object.
     */
    public static Map<String, Object> structuredOutput(LlmClientImpl client, org.moqui.llm.LlmResponse r) {
        Object schema = client != null ? client.structuredOutputSchema() : null;
        if (schema == null || r == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        boolean refused = r.outputItems != null && r.outputItems.stream().anyMatch(i -> i != null && i.content != null
                && i.content.stream().anyMatch(c -> c != null && "refusal".equals(c.type)));
        if (r.finishReason != org.moqui.llm.LlmFinishReason.STOP) {
            out.put("status", r.finishReason == org.moqui.llm.LlmFinishReason.LENGTH ? "incomplete" : "not_checked");
            out.put("valid", null);
            return out;
        }
        if (refused) { out.put("status", "refused"); out.put("valid", null); return out; }
        java.util.List<String> problems = JsonSchemaCheck.problems(schema, r.content != null ? r.content : "");
        out.put("status", problems.isEmpty() ? "valid" : "invalid");
        out.put("valid", problems.isEmpty());
        if (!problems.isEmpty()) out.put("problems", problems.subList(0, Math.min(problems.size(), 5)));
        return out;
    }

    /** Ids a browser may be shown for a turn; nothing the provider gave back beyond its response id. */
    static Map<String, Object> ids(org.moqui.llm.LlmResponse r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("conversationId", r.conversationId);
        out.put("runId", r.runId);
        out.put("requestId", r.requestId);
        out.put("responseId", r.responseId);
        return out;
    }
}
