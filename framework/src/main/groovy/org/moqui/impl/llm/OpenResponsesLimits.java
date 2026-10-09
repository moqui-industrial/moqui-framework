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

import org.moqui.llm.LlmException;
import org.moqui.llm.LlmFinishReason;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Limits the frozen Open Responses schema puts on request values. A value outside them is refused with a precise
 * error before anything is sent or stored; nothing is corrected silently. The numbers come from the pinned OpenAPI
 * document and a test compares each one with it.
 */
final class OpenResponsesLimits {
    static final int MAX_OUTPUT_TOKENS_MIN = 16;
    static final int MAX_TOOL_CALLS_MIN = 1;
    static final int TOP_LOGPROBS_MIN = 0, TOP_LOGPROBS_MAX = 20;
    static final int SHORT_ID_MAX = 64;            // safety_identifier, prompt_cache_key, call_id, function name
    static final int NAME_MIN = 1;
    static final Pattern FUNCTION_NAME = Pattern.compile("^[a-zA-Z0-9_-]+$");
    static final int METADATA_VALUE_MAX = 512;
    static final int TEXT_MAX = 10485760;           // input_text, output_text, refusal, string content, string output
    static final int IMAGE_URL_MAX = 20971520;
    static final int FILE_DATA_MAX = 33554432;
    static final int ALLOWED_TOOLS_MIN = 1, ALLOWED_TOOLS_MAX = 128;

    private OpenResponsesLimits() { }

    /**
     * The request against the contract: the hand-written limits below give the precise message for the values people get
     * wrong most often, then the whole body is checked against the pinned schema (types, integers, enums, ranges, unions),
     * so nothing is accepted because nobody thought to list it. Fields the schema does not know (provider extensions) pass.
     */
    static void validate(Map<String, Object> body) {
        if (body == null) return;
        validateKnownLimits(body);
        List<String> problems = OpenResponsesSchema.get().validate("CreateResponseBody", body);
        if (!problems.isEmpty()) {
            String shown = String.join("; ", problems.subList(0, Math.min(problems.size(), 4)));
            throw new LlmException("Open Responses request is not valid: " + shown + (problems.size() > 4 ? " (and " + (problems.size() - 4) + " more)" : "") + ".",
                    null, LlmFinishReason.ERROR, 0, null, null);
        }
        OpenResponsesCodec.requireCompleteToolPairs(body);
    }

    @SuppressWarnings("unchecked")
    /** A compaction request against the contract of /responses/compact. */
    static void validateCompact(Map<String, Object> body) {
        List<String> problems = OpenResponsesSchema.get().validate("CompactResponseMethodPublicBody", body);
        if (!problems.isEmpty())
            throw new LlmException("Open Responses compaction request is not valid: "
                    + String.join("; ", problems.subList(0, Math.min(problems.size(), 4))) + ".", null, LlmFinishReason.ERROR, 0, null, null);
    }

    @SuppressWarnings("unchecked")
    private static void validateKnownLimits(Map<String, Object> body) {
        Object maxOutput = body.get("max_output_tokens");
        if (maxOutput instanceof Number && ((Number) maxOutput).intValue() < MAX_OUTPUT_TOKENS_MIN)
            fail("max_output_tokens", "is " + maxOutput + ", the contract requires at least " + MAX_OUTPUT_TOKENS_MIN);
        Object maxCalls = body.get("max_tool_calls");
        if (maxCalls instanceof Number && ((Number) maxCalls).intValue() < MAX_TOOL_CALLS_MIN)
            fail("max_tool_calls", "is " + maxCalls + ", the contract requires at least " + MAX_TOOL_CALLS_MIN);
        Object topLogprobs = body.get("top_logprobs");
        if (topLogprobs instanceof Number) {
            int v = ((Number) topLogprobs).intValue();
            if (v < TOP_LOGPROBS_MIN || v > TOP_LOGPROBS_MAX)
                fail("top_logprobs", "is " + v + ", the contract allows " + TOP_LOGPROBS_MIN + " to " + TOP_LOGPROBS_MAX);
        }
        maxLength(body, "safety_identifier", SHORT_ID_MAX, "safety_identifier");
        maxLength(body, "prompt_cache_key", SHORT_ID_MAX, "prompt_cache_key");
        Object metadata = body.get("metadata");
        if (metadata instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) metadata).entrySet())
                if (e.getValue() instanceof String && ((String) e.getValue()).length() > METADATA_VALUE_MAX)
                    fail("metadata." + e.getKey(), "is longer than " + METADATA_VALUE_MAX + " characters");
        }
        Object input = body.get("input");
        if (input instanceof String) text("input", (String) input);
        else if (input instanceof List) {
            List<Object> items = (List<Object>) input;
            for (int i = 0; i < items.size(); i++)
                if (items.get(i) instanceof Map) item("input[" + i + "]", (Map<String, Object>) items.get(i));
        }
        Object tools = body.get("tools");
        if (tools instanceof List) {
            List<Object> list = (List<Object>) tools;
            for (int i = 0; i < list.size(); i++)
                if (list.get(i) instanceof Map && "function".equals(((Map<String, Object>) list.get(i)).get("type")))
                    functionName("tools[" + i + "].name", ((Map<String, Object>) list.get(i)).get("name"));
        }
        Object choice = body.get("tool_choice");
        if (choice instanceof Map && "allowed_tools".equals(((Map<String, Object>) choice).get("type"))) {
            Object allowed = ((Map<String, Object>) choice).get("tools");
            if (allowed instanceof List) {
                int n = ((List<?>) allowed).size();
                if (n < ALLOWED_TOOLS_MIN || n > ALLOWED_TOOLS_MAX)
                    fail("tool_choice.tools", "has " + n + " entries, the contract allows " + ALLOWED_TOOLS_MIN + " to " + ALLOWED_TOOLS_MAX);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void item(String path, Map<String, Object> item) {
        String type = item.get("type") instanceof String ? (String) item.get("type") : null;
        if ("function_call".equals(type)) {
            functionName(path + ".name", item.get("name"));
            callId(path + ".call_id", item.get("call_id"));
        } else if ("function_call_output".equals(type)) {
            callId(path + ".call_id", item.get("call_id"));
            if (item.get("output") instanceof String) text(path + ".output", (String) item.get("output"));
        }
        Object content = item.get("content");
        if (content instanceof String) text(path + ".content", (String) content);
        else if (content instanceof List) {
            List<Object> parts = (List<Object>) content;
            for (int i = 0; i < parts.size(); i++)
                if (parts.get(i) instanceof Map) part(path + ".content[" + i + "]", (Map<String, Object>) parts.get(i));
        }
    }

    private static void part(String path, Map<String, Object> part) {
        for (String field : new String[]{"text", "refusal"})
            if (part.get(field) instanceof String) text(path + "." + field, (String) part.get(field));
        if (part.get("image_url") instanceof String && ((String) part.get("image_url")).length() > IMAGE_URL_MAX)
            fail(path + ".image_url", "is longer than " + IMAGE_URL_MAX + " characters");
        if (part.get("file_data") instanceof String && ((String) part.get("file_data")).length() > FILE_DATA_MAX)
            fail(path + ".file_data", "is longer than " + FILE_DATA_MAX + " characters");
    }

    private static void text(String path, String value) {
        if (value.length() > TEXT_MAX) fail(path, "is longer than " + TEXT_MAX + " characters");
    }

    private static void maxLength(Map<String, Object> body, String key, int max, String path) {
        if (body.get(key) instanceof String && ((String) body.get(key)).length() > max)
            fail(path, "is longer than " + max + " characters");
    }

    private static void callId(String path, Object value) {
        if (!(value instanceof String)) return;
        int n = ((String) value).length();
        if (n < NAME_MIN || n > SHORT_ID_MAX) fail(path, "has " + n + " characters, the contract allows " + NAME_MIN + " to " + SHORT_ID_MAX);
    }

    private static void functionName(String path, Object value) {
        if (!(value instanceof String)) return;
        String name = (String) value;
        if (name.length() < NAME_MIN || name.length() > SHORT_ID_MAX)
            fail(path, "has " + name.length() + " characters, the contract allows " + NAME_MIN + " to " + SHORT_ID_MAX);
        if (!FUNCTION_NAME.matcher(name).matches())
            fail(path, "\"" + name + "\" may only contain letters, digits, underscore and hyphen");
    }

    private static void fail(String path, String problem) {
        throw new LlmException("Open Responses request is not valid: " + path + " " + problem + ".",
                null, LlmFinishReason.ERROR, 0, null, null);
    }
}
