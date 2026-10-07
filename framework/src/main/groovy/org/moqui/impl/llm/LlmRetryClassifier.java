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

import org.moqui.llm.LlmFinishReason;
import org.moqui.llm.LlmProtocol.ProtocolResult;
import org.moqui.llm.LlmToolCall;
import org.moqui.llm.LlmUsage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Layer B classification. First match wins; empty-content retry is last so filter/length
 * never empty-retry.
 */
public final class LlmRetryClassifier {
    private static final Pattern CONTEXT_OVERFLOW_MSG =
            Pattern.compile("context length|maximum context|too many tokens", Pattern.CASE_INSENSITIVE);

    private LlmRetryClassifier() { }

    public static ProtocolResult classify(int httpStatus, String rawJson) {
        Map<String, Object> body = null;
        if (rawJson != null && !rawJson.isBlank()) {
            try {
                Object parsed = LlmJson.toObject(rawJson);
                if (parsed instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) parsed;
                    body = map;
                }
            } catch (Throwable ignored) {
                // non-JSON body still classified from status + raw text
            }
        }
        return classify(httpStatus, body, rawJson);
    }

    public static ProtocolResult classify(int httpStatus, Map<String, Object> body, String rawJson) {
        ProtocolResult result = new ProtocolResult();
        result.httpStatus = httpStatus;
        result.rawJson = rawJson;

        Map<?, ?> error = asMap(body != null ? body.get("error") : null);
        String errorCode = str(error != null ? error.get("code") : null);
        String errorType = str(error != null ? error.get("type") : null);
        String errorMessage = str(error != null ? error.get("message") : null);
        if (errorMessage == null) errorMessage = str(body != null ? body.get("message") : null);
        result.errorMessage = errorMessage;
        result.providerErrorCode = errorCode != null ? errorCode : errorType;

        if (body != null) {
            result.model = str(body.get("model"));
            result.usage = parseUsage(asMap(body.get("usage")));
        }

        List<?> choices = body != null && body.get("choices") instanceof List ? (List<?>) body.get("choices") : null;
        Map<?, ?> firstChoice = (choices != null && !choices.isEmpty()) ? asMap(choices.get(0)) : null;
        Map<?, ?> message = firstChoice != null ? asMap(firstChoice.get("message")) : null;
        String finishReasonStr = str(firstChoice != null ? firstChoice.get("finish_reason") : null);
        if (finishReasonStr == null) finishReasonStr = str(firstChoice != null ? firstChoice.get("finishReason") : null);
        String content = message != null ? str(message.get("content")) : null;
        String refusal = message != null ? str(message.get("refusal")) : null;
        if (refusal != null && refusal.isBlank()) refusal = null;
        List<LlmToolCall> toolCalls = parseToolCalls(message != null ? message.get("tool_calls") : null);
        boolean hasToolCalls = toolCalls != null && !toolCalls.isEmpty();
        result.content = content;
        result.toolCalls = toolCalls;
        result.reasoning = reasoningOf(message);
        result.metadata = metadataOf(body, firstChoice, message, asMap(body != null ? body.get("usage") : null));

        // Exactly one alternative is supported (n=1): more than one choice would silently lose the others.
        if (httpStatus >= 200 && httpStatus < 300 && error == null && choices != null
                && (choices.size() > 1 || (firstChoice != null && firstChoice.get("index") != null
                && !Integer.valueOf(0).equals(toInt(firstChoice.get("index")))))) {
            result.finishReason = LlmFinishReason.ERROR;
            result.errorMessage = "The provider returned " + choices.size() + " choices"
                    + (choices.size() == 1 ? " (index " + firstChoice.get("index") + ")" : "")
                    + "; this client supports exactly one (n=1)";
            result.toolCalls = null;
            return result;
        }

        String overflowHaystack = joinNonNull(errorCode, errorType, errorMessage, rawJson);

        // 1. CONTEXT_OVERFLOW
        if (httpStatus == 400 || httpStatus == 413) {
            if (isContextOverflowCode(errorCode) || CONTEXT_OVERFLOW_MSG.matcher(nullToEmpty(overflowHaystack)).find()) {
                result.finishReason = LlmFinishReason.CONTEXT_OVERFLOW;
                if (result.providerErrorCode == null) result.providerErrorCode = "context_length_exceeded";
                return result;
            }
        }

        // 2. CONTENT_FILTER
        if ((httpStatus == 400 && isContentFilter(errorCode, errorType, errorMessage))
                || "content_filter".equalsIgnoreCase(finishReasonStr)) {
            result.finishReason = LlmFinishReason.CONTENT_FILTER;
            return result;
        }

        // 2b. refusal: the model declined; a definitive result, never empty, never retried, and no tool runs
        if (refusal != null && httpStatus >= 200 && httpStatus < 300) {
            result.finishReason = LlmFinishReason.REFUSAL;
            result.refusal = refusal;
            if (hasToolCalls) {
                List<String> ignored = new ArrayList<>();
                for (LlmToolCall tc : toolCalls) ignored.add(tc.name);
                Map<String, Object> md = result.metadata != null ? result.metadata : new LinkedHashMap<>();
                @SuppressWarnings("unchecked")
                Map<String, Object> messageMd = md.get("message") instanceof Map
                        ? (Map<String, Object>) md.get("message") : new LinkedHashMap<>();
                messageMd.put("ignoredToolCalls", ignored);
                md.put("message", messageMd);
                result.metadata = md;
                result.toolCalls = null;
            }
            return result;
        }

        // 3. finish_reason=length (keep partial content; never empty-retry)
        if ("length".equalsIgnoreCase(finishReasonStr)) {
            result.finishReason = LlmFinishReason.LENGTH;
            return result;
        }

        // 4. finish_reason=tool_calls
        if ("tool_calls".equalsIgnoreCase(finishReasonStr) || "function_call".equalsIgnoreCase(finishReasonStr)
                || hasToolCalls) {
            result.finishReason = LlmFinishReason.TOOL_CALLS;
            return result;
        }

        // 5. finish_reason=stop with a content string: a finished completion, also when the string is empty
        if ("stop".equalsIgnoreCase(finishReasonStr) && message != null && message.get("content") instanceof CharSequence) {
            result.finishReason = LlmFinishReason.STOP;
            return result;
        }

        // 7. JSON error HTTP 200 rate-limit (before empty, so a rate-limit body is not EMPTY)
        if (httpStatus == 200 && error != null && isRateLimit(errorCode, errorType, errorMessage)) {
            result.finishReason = LlmFinishReason.ERROR;
            result.retryable = true;
            if (result.providerErrorCode == null) result.providerErrorCode = "rate_limit_exceeded";
            return result;
        }

        // 6. Empty choices / null/blank content, no tool_calls
        boolean emptyContent = !hasToolCalls && (choices == null || choices.isEmpty()
                || content == null || content.isBlank());
        if (httpStatus >= 200 && httpStatus < 300 && emptyContent && error == null) {
            result.finishReason = LlmFinishReason.EMPTY;
            return result;
        }

        // 8. Other errors
        if (httpStatus < 200 || httpStatus >= 300 || error != null) {
            result.finishReason = LlmFinishReason.ERROR;
            result.retryable = httpStatus >= 500 && httpStatus <= 599;
            return result;
        }

        if ("stop".equalsIgnoreCase(finishReasonStr) || (content != null && !content.isBlank())) {
            result.finishReason = LlmFinishReason.STOP;
            return result;
        }

        result.finishReason = LlmFinishReason.EMPTY;
        return result;
    }

    static boolean isContextOverflowCode(String code) {
        if (code == null) return false;
        String c = code.trim();
        return "context_length_exceeded".equalsIgnoreCase(c)
                || "context_length_exceeded_error".equalsIgnoreCase(c);
    }

    static boolean isContentFilter(String code, String type, String message) {
        String blob = (nullToEmpty(code) + " " + nullToEmpty(type) + " " + nullToEmpty(message)).toLowerCase();
        return blob.contains("content_filter") || blob.contains("invalid_prompt");
    }

    static boolean isRateLimit(String code, String type, String message) {
        String blob = (nullToEmpty(code) + " " + nullToEmpty(type) + " " + nullToEmpty(message)).toLowerCase();
        return blob.contains("rate_limit") || blob.contains("rate-limit") || blob.contains("rate limit")
                || blob.contains("too many requests");
    }

    @SuppressWarnings("unchecked")
    static List<LlmToolCall> parseToolCalls(Object raw) {
        if (!(raw instanceof List)) return null;
        List<?> list = (List<?>) raw;
        if (list.isEmpty()) return null;
        List<LlmToolCall> out = new ArrayList<>(list.size());
        for (Object item : list) {
            Map<?, ?> tc = asMap(item);
            if (tc == null) continue;
            Map<?, ?> fn = asMap(tc.get("function"));
            String id = str(tc.get("id"));
            String name = fn != null ? str(fn.get("name")) : str(tc.get("name"));
            Object argsObj = fn != null ? fn.get("arguments") : tc.get("arguments");
            String args;
            if (argsObj == null) args = null;
            else if (argsObj instanceof CharSequence) args = argsObj.toString();
            else args = LlmJson.toJson(argsObj);
            out.add(new LlmToolCall(id, name, args));
        }
        return out.isEmpty() ? null : out;
    }

    static LlmUsage parseUsage(Map<?, ?> usage) {
        if (usage == null) return null;
        Integer prompt = toInt(usage.get("prompt_tokens"));
        Integer completion = toInt(usage.get("completion_tokens"));
        Integer total = toInt(usage.get("total_tokens"));
        if (prompt == null && completion == null && total == null) return null;
        LlmUsage out = new LlmUsage(prompt, completion, total);
        Map<?, ?> promptDetails = asMap(usage.get("prompt_tokens_details"));
        if (promptDetails != null) out.cachedInputTokens = toInt(promptDetails.get("cached_tokens"));
        Map<?, ?> completionDetails = asMap(usage.get("completion_tokens_details"));
        if (completionDetails != null) out.reasoningOutputTokens = toInt(completionDetails.get("reasoning_tokens"));
        return out;
    }

    /**
     * The structured metadata of a response that is not text: response, choice, message and the complete usage object,
     * copied so the result does not share maps with the parsed body. Absent and null values are left out, zero is kept.
     */
    static Map<String, Object> metadataOf(Map<String, Object> body, Map<?, ?> choice, Map<?, ?> message, Map<?, ?> usage) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> response = new LinkedHashMap<>();
        if (body != null) for (String key : new String[] {"id", "object", "created", "model", "service_tier", "system_fingerprint"})
            if (body.get(key) != null) response.put(key, body.get(key));
        if (!response.isEmpty()) out.put("response", response);
        Map<String, Object> choiceMd = new LinkedHashMap<>();
        if (choice != null) {
            if (choice.get("index") != null) choiceMd.put("index", choice.get("index"));
            Object finish = choice.get("finish_reason") != null ? choice.get("finish_reason") : choice.get("finishReason");
            if (finish != null) choiceMd.put("finish_reason", finish);
            if (choice.get("logprobs") != null) choiceMd.put("logprobs", deepCopy(choice.get("logprobs")));
        }
        if (!choiceMd.isEmpty()) out.put("choice", choiceMd);
        Map<String, Object> messageMd = new LinkedHashMap<>();
        if (message != null && message.get("annotations") instanceof List && !((List<?>) message.get("annotations")).isEmpty())
            messageMd.put("annotations", deepCopy(message.get("annotations")));
        if (!messageMd.isEmpty()) out.put("message", messageMd);
        if (usage != null && !usage.isEmpty()) out.put("usage", deepCopy(usage));
        return out.isEmpty() ? null : out;
    }

    static Object deepCopy(Object value) {
        return LlmJson.toObject(LlmJson.toJson(value));
    }

    static Integer toInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(o.toString().trim()); }
        catch (Exception e) { return null; }
    }

    static String str(Object o) {
        if (o == null) return null;
        String s = o.toString();
        return s;
    }

    static Map<?, ?> asMap(Object o) {
        return o instanceof Map ? (Map<?, ?>) o : null;
    }

    /** DeepSeek/Qwen/llama.cpp thinking fields on message or delta. */
    static String reasoningOf(Map<?, ?> map) {
        if (map == null) return null;
        String s = str(map.get("reasoning_content"));
        if (s == null || s.isBlank()) s = str(map.get("reasoning"));
        if (s == null || s.isBlank()) s = str(map.get("thinking"));
        return s != null && !s.isBlank() ? s : null;
    }

    static String nullToEmpty(String s) { return s == null ? "" : s; }

    static String joinNonNull(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(p);
        }
        return sb.toString();
    }
}
