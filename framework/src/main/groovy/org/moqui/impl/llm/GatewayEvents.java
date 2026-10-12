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

import org.moqui.llm.LlmResponseEvent;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The provider events a browser can ask to see (request field {@code events: "v1"}), as the gateway sends them:
 * {@code event: response_event}, one JSON object with a version, a sequence of its own, the ids of the turn, the provider's
 * own sequence number apart, and a payload cut down to what a screen needs. Nothing a provider sent for itself (encrypted or
 * raw reasoning, instructions, tools, request echo) is passed on.
 */
public final class GatewayEvents {
    public static final String VERSION = "v1";
    public static final String SSE_EVENT = "response_event";

    private static final Set<String> TEXT_FIELDS = new LinkedHashSet<>(Arrays.asList("delta", "text", "arguments", "refusal", "name"));
    private static final Set<String> ITEM_FIELDS = new LinkedHashSet<>(Arrays.asList("id", "type", "status", "role", "name", "call_id", "arguments"));
    private static final Set<String> RESPONSE_FIELDS = new LinkedHashSet<>(Arrays.asList("id", "status", "model", "usage", "incomplete_details"));

    private final String conversationId;
    private final String runId;
    private long sequence = 0;
    private int inference = 0;
    private String responseId;

    public GatewayEvents(String conversationId, String runId) {
        this.conversationId = conversationId;
        this.runId = runId;
    }

    /** True for the value of {@code events} that asks for this format; a name this server does not know is refused. */
    public static boolean negotiate(Object requested) {
        if (requested == null) return false;
        String v = String.valueOf(requested).trim();
        if (v.isEmpty() || "none".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) return false;
        if (VERSION.equalsIgnoreCase(v)) return true;
        throw new org.moqui.llm.LlmException("events format " + v + " is not supported; use " + VERSION, null,
                org.moqui.llm.LlmFinishReason.ERROR, 400, null, null);
    }

    public synchronized Map<String, Object> toBrowser(LlmResponseEvent event) {
        if (event == null || event.type == null) return null;
        // raw reasoning text is not for the browser; its summary is
        if (event.type.startsWith("response.reasoning_text.")) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        if ("response.created".equals(event.type)) {
            inference++;
            Object r = event.payload != null ? event.payload.get("response") : null;
            responseId = r instanceof Map && ((Map<?, ?>) r).get("id") != null ? ((Map<?, ?>) r).get("id").toString() : null;
        }
        out.put("v", VERSION);
        out.put("seq", ++sequence);
        out.put("type", event.type);
        out.put("conversationId", conversationId);
        out.put("runId", runId);
        out.put("inference", inference);
        out.put("responseId", event.responseId != null ? event.responseId : responseId);
        out.put("itemId", event.itemId);
        out.put("outputIndex", event.outputIndex);
        out.put("contentIndex", event.contentIndex);
        out.put("providerSequence", event.sequenceNumber);
        // true at the end of one inference; a turn that calls tools has several, and ends with done, yield or error
        out.put("terminal", event.terminal);
        out.put("data", payload(event));
        return out;
    }

    private static Map<String, Object> payload(LlmResponseEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> p = event.payload;
        if (p == null) return data;
        for (String key : TEXT_FIELDS) if (p.get(key) instanceof String) data.put(key, p.get(key));
        if (p.get("item") instanceof Map) data.put("item", pick((Map<?, ?>) p.get("item"), ITEM_FIELDS));
        if (p.get("response") instanceof Map) {
            Map<String, Object> r = pick((Map<?, ?>) p.get("response"), RESPONSE_FIELDS);
            Object error = ((Map<?, ?>) p.get("response")).get("error");
            if (error instanceof Map) r.put("error", pick((Map<?, ?>) error, new LinkedHashSet<>(Arrays.asList("code", "message", "type"))));
            data.put("response", r);
        }
        if ("error".equals(event.type)) {
            for (String key : new String[] {"code", "message", "type"}) if (p.get(key) != null) data.put(key, p.get(key));
        }
        return data;
    }

    private static Map<String, Object> pick(Map<?, ?> from, Set<String> names) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String name : names) if (from.containsKey(name) && from.get(name) != null) out.put(name, from.get(name));
        return out;
    }
}
