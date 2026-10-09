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

import org.moqui.llm.LlmContentAnnotation;
import org.moqui.llm.LlmContentLogprob;
import org.moqui.llm.LlmContentPart;
import org.moqui.llm.LlmException;
import org.moqui.llm.LlmFinishReason;
import org.moqui.llm.LlmItem;
import org.moqui.llm.LlmMessage;
import org.moqui.llm.LlmProtocol;
import org.moqui.llm.LlmResponseOptions;
import org.moqui.llm.LlmTool;
import org.moqui.llm.LlmToolCall;
import org.moqui.llm.LlmUsage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class OpenResponsesCodec {
    static final Set<String> REQUEST_FIELDS = new LinkedHashSet<>(Arrays.asList(
            "model", "input", "previous_response_id", "include", "tools", "tool_choice", "metadata",
            "text", "temperature", "top_p", "presence_penalty", "frequency_penalty", "parallel_tool_calls",
            "stream", "stream_options", "background", "max_output_tokens", "max_tool_calls", "reasoning",
            "safety_identifier", "prompt_cache_key", "truncation", "instructions", "store", "service_tier",
            "top_logprobs"));
    static final Set<String> OPENAI_EXTENSION_FIELDS = new LinkedHashSet<>(Arrays.asList(
            "conversation", "prompt", "prompt_cache_options", "prompt_cache_retention", "user", "modalities",
            "audio", "access_programs", "moderation"));

    private OpenResponsesCodec() { }

    static Map<String, Object> buildRequestBody(LlmProtocol.ProtocolRequest request) {
        return buildRequestBody(request, false);
    }

    static Map<String, Object> buildRequestBody(LlmProtocol.ProtocolRequest request, boolean allowProviderExtensions) {
        Map<String, Object> body = new LinkedHashMap<>();
        applyOptions(body, request.responseOptions, allowProviderExtensions);
        applyExtraBody(body, request.extraBody, allowProviderExtensions);
        if (request.model != null && !request.model.isBlank()) body.put("model", request.model);
        body.put("input", inputFor(request));
        if (request.instructions != null && !request.instructions.isBlank()) {
            // the instructions of a request travel in their own field and are never dropped because the input is a list of items
            if (hasField(request, "instructions")) throw new LlmException("instructions is set more than once");
            body.put("instructions", request.instructions);
        }
        if (request.previousResponseId != null && !request.previousResponseId.isBlank()) {
            if (hasField(request, "previous_response_id"))
                throw new LlmException("previous_response_id is set more than once");
            body.put("previous_response_id", request.previousResponseId);
        }
        if (!hasField(request, "store")) body.put("store", false);
        if (!hasField(request, "stream")) body.put("stream", request.stream);
        if (request.maxTokens != null && !hasField(request, "max_output_tokens")) body.put("max_output_tokens", request.maxTokens);
        if (request.temperature != null && !hasField(request, "temperature")) body.put("temperature", request.temperature);
        if (request.tools != null && !request.tools.isEmpty()) body.put("tools", toolsFor(request.tools));
        requireToolChoiceMatchesTools(body, request.tools);
        return body;
    }

    /**
     * Calls and their outputs travel together. An output whose call is neither in the input nor in a response the request
     * continues from, and a call in the input that nothing answers, are refused before anything is sent: the provider would
     * refuse them, and a client that sent them anyway would have run a tool for nothing.
     */
    static void requireCompleteToolPairs(Map<String, Object> body) {
        Object input = body.get("input");
        if (!(input instanceof List)) return;
        boolean continues = body.get("previous_response_id") instanceof String && !((String) body.get("previous_response_id")).isBlank();
        java.util.Set<String> calls = new java.util.LinkedHashSet<>();
        java.util.Set<String> outputs = new java.util.LinkedHashSet<>();
        for (Object value : (List<?>) input) {
            if (!(value instanceof Map)) continue;
            Map<?, ?> item = (Map<?, ?>) value;
            Object callId = item.get("call_id");
            if (!(callId instanceof String) || ((String) callId).isBlank()) continue;
            if ("function_call".equals(item.get("type"))) calls.add((String) callId);
            else if ("function_call_output".equals(item.get("type"))) {
                if (!continues && !calls.contains(callId))
                    throw new LlmException("The input has the output of call " + callId + " and no call of that id before it");
                outputs.add((String) callId);
            }
        }
        for (String callId : calls)
            if (!outputs.contains(callId)) throw new LlmException("The input has the function call " + callId + " and no output for it");
    }

    /**
     * A tool choice that names a function, or lists allowed ones, must name tools the request carries; a choice that
     * requires a tool needs tools to choose from. The server never sends a choice the provider has to guess about.
     */
    @SuppressWarnings("unchecked")
    private static void requireToolChoiceMatchesTools(Map<String, Object> body, List<LlmTool> tools) {
        Object choice = body.get("tool_choice");
        if (choice == null) return;
        java.util.Set<String> names = new java.util.HashSet<>();
        if (tools != null) for (LlmTool t : tools) if (t != null && t.getName() != null) names.add(t.getName());
        Object extraTools = body.get("tools");
        boolean haveTools = extraTools instanceof List && !((List<?>) extraTools).isEmpty();
        if ("required".equals(choice) && !haveTools)
            throw new LlmException("tool_choice \"required\" needs tools, and the request has none");
        if (!(choice instanceof Map)) return;
        Map<String, Object> map = (Map<String, Object>) choice;
        if ("function".equals(map.get("type")) && map.get("name") instanceof String && !names.contains(map.get("name")))
            throw new LlmException("tool_choice names the function " + map.get("name") + ", which is not one of the tools of this request");
        if ("allowed_tools".equals(map.get("type")) && map.get("tools") instanceof List)
            for (Object allowed : (List<?>) map.get("tools"))
                if (allowed instanceof Map && "function".equals(((Map<?, ?>) allowed).get("type"))
                        && ((Map<?, ?>) allowed).get("name") instanceof String && !names.contains(((Map<?, ?>) allowed).get("name")))
                    throw new LlmException("tool_choice allows the function " + ((Map<?, ?>) allowed).get("name") + ", which is not one of the tools of this request");
    }

    static Map<String, Object> buildCompactRequestBody(LlmProtocol.ProtocolRequest request) {
        Map<String, Object> full = buildRequestBody(request, false);
        if (!(full.get("model") instanceof String) || ((String) full.get("model")).isBlank())
            throw new LlmException("Open Responses compaction requires model");
        Map<String, Object> compact = new LinkedHashMap<>();
        for (String field : Arrays.asList("model", "input", "previous_response_id", "instructions", "prompt_cache_key")) {
            if (full.containsKey(field)) compact.put(field, full.get(field));
        }
        return compact;
    }

    static List<Map<String, Object>> convertInput(List<LlmMessage> window) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (window == null) return out;
        for (LlmMessage msg : window) {
            if (msg == null || msg.role == null) continue;
            switch (msg.role) {
                case SYSTEM:
                    out.add(messageItem(msg, "system", null));
                    break;
                case USER:
                    out.add(messageItem(msg, "user", null));
                    break;
                case CONTEXT:
                    String source = msg.metadata != null && msg.metadata.get("source") != null
                            ? msg.metadata.get("source").toString() : "";
                    LlmMessage contextMsg = msg.copy();
                    contextMsg.content = "<untrusted-context source=\"" + OpenAiCompatProtocol.escapeAttr(source) + "\">"
                            + (msg.content != null ? msg.content : "") + "</untrusted-context>";
                    out.add(messageItem(contextMsg, "user", null));
                    break;
                case ASSISTANT:
                    if (msg.content != null && !msg.content.isBlank()) out.add(messageItem(msg, "assistant", null));
                    if (msg.toolCalls != null) for (LlmToolCall tc : msg.toolCalls) {
                        if (tc == null) continue;
                        Map<String, Object> fc = new LinkedHashMap<>();
                        fc.put("type", "function_call");
                        putIfNotBlank(fc, "call_id", tc.id);
                        putIfNotBlank(fc, "name", tc.name);
                        fc.put("arguments", tc.arguments != null ? tc.arguments : "{}");
                        out.add(fc);
                    }
                    break;
                case TOOL:
                    Map<String, Object> fo = new LinkedHashMap<>();
                    fo.put("type", "function_call_output");
                    putIfNotBlank(fo, "call_id", msg.toolCallId);
                    fo.put("output", msg.content != null ? msg.content : "");
                    out.add(fo);
                    break;
                default:
                    break;
            }
        }
        return out;
    }

    static List<LlmItem> itemsFromMessages(List<LlmMessage> window) {
        List<LlmItem> items = new ArrayList<>();
        for (Map<String, Object> value : convertInput(window)) items.add(mapToItem(value));
        return items;
    }

    /**
     * Items of a stored run context. A context is stored as the JSON of the items themselves (what
     * {@link LlmJson#toJson} writes for {@link LlmItem}), so it is read back as that, field by field; reading it as a
     * wire map lost call ids and item ids and put the Java field names on the wire. A list of wire maps (the shape of an
     * item in a response body) is still accepted.
     */
    static List<LlmItem> itemsFromStored(Object stored) {
        List<LlmItem> items = new ArrayList<>();
        if (!(stored instanceof List)) return items;
        for (Object value : (List<?>) stored) {
            if (!(value instanceof Map)) continue;
            Map<?, ?> map = (Map<?, ?>) value;
            boolean wire = map.containsKey("call_id") || map.containsKey("id") || map.containsKey("encrypted_content")
                    || map.containsKey("reference_id") || map.containsKey("created_by");
            if (wire) items.add(mapToItem(map));
            else items.add(LlmJson.itemFromStored(map));
        }
        return items;
    }

    static Map<String, Object> itemToMap(LlmItem item) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (item == null) return out;
        if (item.payload != null) out.putAll(item.payload);
        // the id of an item on the wire is the one the provider gave it; an id of this application is never an identity there
        putIfNotBlank(out, "id", item.providerItemId);
        putIfNotBlank(out, "type", item.type);
        putIfNotBlank(out, "status", item.status);
        putIfNotBlank(out, "role", item.role);
        putIfNotBlank(out, "phase", item.phase);
        putIfNotBlank(out, "call_id", item.callId);
        putIfNotBlank(out, "name", item.name);
        if (item.arguments != null) out.put("arguments", item.arguments);
        if (item.output != null) out.put("output", outputToWire(item.output));
        putIfNotBlank(out, "encrypted_content", item.encryptedContent);
        putIfNotBlank(out, "created_by", item.createdBy);
        putIfNotBlank(out, "reference_id", item.referenceId);
        if (item.content != null) out.put("content", partsToMaps(item.content));
        if (item.summary != null) out.put("summary", partsToMaps(item.summary));
        // the schema requires the summary of a reasoning item to be there, even when the provider left it out of its output
        else if ("reasoning".equals(item.type)) out.put("summary", new ArrayList<>());
        // and takes no content back: the reasoning text a model returned is not an input, its summary and encrypted content are
        if ("reasoning".equals(item.type) && out.get("content") != null) out.remove("content");
        return out;
    }

    static LlmItem mapToItem(Map<?, ?> itemMap) {
        if (itemMap == null) return null;
        LlmItem item = new LlmItem();
        item.payload = copyMap(itemMap);
        item.providerItemId = str(itemMap.get("id"));
        item.itemId = str(itemMap.get("id"));
        item.type = str(itemMap.get("type"));
        item.status = str(itemMap.get("status"));
        item.role = str(itemMap.get("role"));
        item.phase = str(itemMap.get("phase"));
        item.callId = str(itemMap.get("call_id"));
        item.name = str(itemMap.get("name"));
        Object argsObj = itemMap.get("arguments");
        item.arguments = argsObj == null ? null : argsObj instanceof CharSequence ? argsObj.toString() : LlmJson.toJson(argsObj);
        item.output = itemMap.get("output");
        item.encryptedContent = str(itemMap.get("encrypted_content"));
        item.createdBy = str(itemMap.get("created_by"));
        item.referenceId = str(itemMap.get("reference_id"));
        item.content = partsFromObject(itemMap.get("content"));
        item.summary = partsFromObject(itemMap.get("summary"));
        return item;
    }

    static Map<String, Object> partToMap(LlmContentPart part) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (part == null) return out;
        if (part.payload != null) out.putAll(part.payload);
        putIfNotBlank(out, "type", part.type);
        if (part.text != null) out.put("text", part.text);
        if (part.refusal != null) out.put("refusal", part.refusal);
        putIfNotBlank(out, "image_url", part.imageUrl);
        putIfNotBlank(out, "file_data", part.fileData);
        putIfNotBlank(out, "file_url", part.fileUrl);
        putIfNotBlank(out, "video_url", part.videoUrl);
        putIfNotBlank(out, "filename", part.filename);
        putIfNotBlank(out, "detail", part.detail);
        if (part.annotations != null) out.put("annotations", annotationsToMaps(part.annotations));
        if (part.logprobs != null) out.put("logprobs", logprobsToMaps(part.logprobs));
        return out;
    }

    static LlmContentPart mapToPart(Map<?, ?> partMap) {
        if (partMap == null) return null;
        LlmContentPart part = new LlmContentPart();
        part.payload = copyMap(partMap);
        part.type = str(partMap.get("type"));
        part.text = str(partMap.get("text"));
        part.refusal = str(partMap.get("refusal"));
        part.imageUrl = str(partMap.get("image_url"));
        part.fileData = str(partMap.get("file_data"));
        part.fileUrl = str(partMap.get("file_url"));
        part.videoUrl = str(partMap.get("video_url"));
        part.filename = str(partMap.get("filename"));
        part.detail = str(partMap.get("detail"));
        part.contentLocation = str(partMap.get("content_location"));
        part.mediaType = str(partMap.get("media_type"));
        Number length = partMap.get("content_length") instanceof Number ? (Number) partMap.get("content_length") : null;
        if (length != null) part.contentLength = length.longValue();
        part.contentSha256 = str(partMap.get("content_sha256"));
        part.annotations = annotationsFromObject(partMap.get("annotations"));
        part.logprobs = logprobsFromObject(partMap.get("logprobs"));
        return part;
    }

    static LlmProtocol.ProtocolResult parseResponse(int httpStatus, String rawJson, LlmProtocol.ProtocolRequest request) {
        Map<String, Object> body = null;
        if (rawJson != null && !rawJson.isBlank()) {
            try {
                Object parsed = LlmJson.toObject(rawJson);
                if (parsed instanceof Map) {
                    @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) parsed;
                    body = map;
                }
            } catch (Throwable ignored) { }
        }
        return parseResponse(httpStatus, body, rawJson, request != null ? request.model : null);
    }

    static LlmProtocol.ProtocolResult parseResponse(int httpStatus, Map<String, Object> body, String rawJson, String requestModel) {
        if (httpStatus < 200 || httpStatus >= 300 || body == null || body.get("error") != null) {
            LlmProtocol.ProtocolResult r = LlmRetryClassifier.classify(httpStatus, body, rawJson);
            if (r.model == null) r.model = requestModel;
            r.responsePayload = body;
            r.rawJson = rawJson;
            // a 5xx answer may come from a provider or a gateway that did run the request: it is reported, not repeated
            if (httpStatus >= 500 && httpStatus <= 599) r.retryable = false;
            return r;
        }
        LlmProtocol.ProtocolResult r = new LlmProtocol.ProtocolResult();
        r.httpStatus = httpStatus;
        r.rawJson = rawJson;
        r.responsePayload = body;
        r.responseId = str(body.get("id"));
        r.status = str(body.get("status"));
        r.previousResponseId = str(body.get("previous_response_id"));
        r.createdAt = timestamp(body.get("created_at"));
        r.completedAt = timestamp(body.get("completed_at"));
        r.model = str(body.get("model"));
        if (r.model == null) r.model = requestModel;
        r.responseOptions = optionsFromResponse(body);
        r.usage = parseUsage(LlmRetryClassifier.asMap(body.get("usage")));
        Map<?, ?> error = LlmRetryClassifier.asMap(body.get("error"));
        if (error != null) {
            r.finishReason = LlmFinishReason.ERROR;
            r.providerErrorCode = str(error.get("code"));
            r.providerErrorType = str(error.get("type"));
            r.providerErrorParam = str(error.get("param"));
            r.errorMessage = str(error.get("message"));
            return r;
        }
        ParseOutput parsed = parseOutputItems(body.get("output"));
        r.outputItems = parsed.outputItems;
        r.content = parsed.content.length() > 0 ? parsed.content.toString() : null;
        r.reasoning = parsed.reasoning.length() > 0 ? parsed.reasoning.toString() : null;
        r.toolCalls = parsed.toolCalls.isEmpty() ? null : parsed.toolCalls;
        applyFinishReason(r, body);
        return r;
    }

    static List<LlmItem> requestItems(LlmProtocol.ProtocolRequest request) {
        List<LlmItem> items = new ArrayList<>();
        Object input = inputFor(request);
        if (input instanceof String) {
            List<LlmContentPart> content = new ArrayList<>();
            content.add(LlmContentPart.inputText((String) input));
            items.add(LlmItem.message("user", content));
        } else if (input instanceof List) {
            for (Object value : (List<?>) input) {
                Map<?, ?> map = LlmRetryClassifier.asMap(value);
                if (map != null) items.add(mapToItem(map));
            }
        }
        return items;
    }

    static LlmResponseOptions effectiveRequestOptions(Map<String, Object> body) {
        LlmResponseOptions options = new LlmResponseOptions();
        if (body == null) return options;
        for (Map.Entry<String, Object> entry : body.entrySet()) {
            String key = entry.getKey();
            if ("model".equals(key) || "input".equals(key) || "tools".equals(key)) continue;
            // the WebSocket frame type is framing, not a request option
            if ("type".equals(key) && "response.create".equals(entry.getValue())) continue;
            options.put(key, entry.getValue());
        }
        return options;
    }

    static LlmUsage parseUsage(Map<?, ?> usage) {
        if (usage == null) return null;
        Integer input = LlmRetryClassifier.toInt(usage.get("input_tokens"));
        Integer output = LlmRetryClassifier.toInt(usage.get("output_tokens"));
        Integer total = LlmRetryClassifier.toInt(usage.get("total_tokens"));
        if (input == null && output == null && total == null) return null;
        LlmUsage out = new LlmUsage(input, output, total);
        Map<?, ?> inputDetails = LlmRetryClassifier.asMap(usage.get("input_tokens_details"));
        if (inputDetails != null) out.cachedInputTokens = LlmRetryClassifier.toInt(inputDetails.get("cached_tokens"));
        Map<?, ?> outputDetails = LlmRetryClassifier.asMap(usage.get("output_tokens_details"));
        if (outputDetails != null) out.reasoningOutputTokens = LlmRetryClassifier.toInt(outputDetails.get("reasoning_tokens"));
        return out;
    }

    static Map<String, Object> message(String role, String text) {
        LlmMessage msg = new LlmMessage("assistant".equals(role) ? LlmMessage.Role.ASSISTANT : LlmMessage.Role.USER, text);
        return messageItem(msg, role, null);
    }

    /**
     * An item as the request takes it: what the schema of an input item declares, and nothing a response added that a request
     * does not take back. The stored item is the response as it came; this is a projection of it and changes nothing.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> toInputItem(LlmItem item) {
        Map<String, Object> wire = itemToMap(item);
        Object projected = OpenResponsesSchema.get().project("ItemParam", wire);
        return projected instanceof Map ? (Map<String, Object>) projected : wire;
    }

    private static Object inputFor(LlmProtocol.ProtocolRequest request) {
        if (request.inputText != null) return request.inputText;
        if (request.inputItems != null) {
            List<Map<String, Object>> input = new ArrayList<>();
            for (LlmItem item : request.inputItems) if (item != null) input.add(toInputItem(item));
            return input;
        }
        return convertInput(request.window);
    }

    private static Map<String, Object> messageItem(LlmMessage msg, String role, String phase) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "message");
        out.put("role", role);
        putIfNotBlank(out, "status", statusFromMetadata(msg));
        if (phase != null && !phase.isBlank()) out.put("phase", phase);
        LlmContentPart part = "assistant".equals(role) ? LlmContentPart.outputText(msg.content != null ? msg.content : "")
                : LlmContentPart.inputText(msg.content != null ? msg.content : "");
        List<LlmContentPart> content = new ArrayList<>();
        content.add(part);
        out.put("content", partsToMaps(content));
        return out;
    }

    private static String statusFromMetadata(LlmMessage msg) {
        Object status = msg.metadata != null ? msg.metadata.get("status") : null;
        return status != null ? status.toString() : null;
    }

    private static List<Map<String, Object>> toolsFor(List<LlmTool> tools) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LlmTool tool : tools) {
            if (tool == null || tool.getName() == null || tool.getName().isBlank()) continue;
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("type", "function");
            tm.put("name", tool.getName());
            if (tool.getDescription() != null) tm.put("description", tool.getDescription());
            Map<String, Object> params = tool.getParametersSchema();
            if (params == null) {
                params = new LinkedHashMap<>();
                params.put("type", "object");
                params.put("properties", new LinkedHashMap<>());
            }
            if (tool.getStrict() != null) {
                if (Boolean.TRUE.equals(tool.getStrict())) StrictToolSchema.require(tool.getName(), params);
                tm.put("strict", tool.getStrict());
            }
            tm.put("parameters", params);
            out.add(tm);
        }
        return out;
    }

    private static void applyOptions(Map<String, Object> body, LlmResponseOptions options,
            boolean allowProviderExtensions) {
        if (options == null) return;
        for (Map.Entry<String, Object> entry : options.asMap().entrySet()) {
            String key = entry.getKey();
            if (!isAllowedField(key, allowProviderExtensions))
                throw new LlmException("Unsupported Responses option field: " + key);
            if ("model".equals(key) || "input".equals(key) || "tools".equals(key) || "stream".equals(key))
                throw new LlmException("Responses option may not override protocol field: " + key);
            body.put(key, entry.getValue());
        }
    }

    static void applyExtraBody(Map<String, Object> body, Map<String, Object> extraBody) {
        applyExtraBody(body, extraBody, false);
    }

    static void applyExtraBody(Map<String, Object> body, Map<String, Object> extraBody,
            boolean allowProviderExtensions) {
        if (extraBody == null || extraBody.isEmpty()) return;
        for (Map.Entry<String, Object> e : extraBody.entrySet()) {
            String key = e.getKey();
            if (key == null || key.isBlank()) continue;
            if ("model".equals(key) || "input".equals(key) || "messages".equals(key) || "tools".equals(key)
                    || "stream".equals(key) || "stream_options".equals(key) || "store".equals(key)
                    || "previous_response_id".equals(key) || "max_tokens".equals(key)
                    || "max_completion_tokens".equals(key) || "max_output_tokens".equals(key)) {
                throw new LlmException("Responses extraBody may not override protocol field: " + key);
            }
            if (!isAllowedField(key, allowProviderExtensions))
                throw new LlmException("Unsupported Responses extraBody field: " + key);
            body.put(key, e.getValue());
        }
    }

    private static boolean isAllowedField(String key, boolean allowProviderExtensions) {
        return REQUEST_FIELDS.contains(key) || (allowProviderExtensions && OPENAI_EXTENSION_FIELDS.contains(key));
    }

    private static boolean hasField(LlmProtocol.ProtocolRequest request, String field) {
        return (request.responseOptions != null && request.responseOptions.isPresent(field))
                || (request.extraBody != null && request.extraBody.containsKey(field));
    }

    private static List<Map<String, Object>> partsToMaps(List<LlmContentPart> parts) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LlmContentPart part : parts) if (part != null) out.add(partToMap(part));
        return out;
    }

    private static List<LlmContentPart> partsFromObject(Object contentObj) {
        if (!(contentObj instanceof List)) return null;
        List<LlmContentPart> out = new ArrayList<>();
        for (Object obj : (List<?>) contentObj) {
            Map<?, ?> map = LlmRetryClassifier.asMap(obj);
            if (map != null) out.add(mapToPart(map));
        }
        return out;
    }

    /** The parts of a tool output that is an array; null when the output is a string, a map or absent. */
    static List<LlmContentPart> outputParts(Object output) {
        if (!(output instanceof List)) return null;
        List<LlmContentPart> out = new ArrayList<>();
        for (Object obj : (List<?>) output) {
            if (obj instanceof LlmContentPart) out.add((LlmContentPart) obj);
            else {
                Map<?, ?> map = LlmRetryClassifier.asMap(obj);
                if (map != null) out.add(mapToPart(map));
            }
        }
        return out;
    }

    /**
     * The wire form of the output of a function call: a string or an array of content parts, nothing else. A tool
     * result that is a map, a number or a list of records is written as JSON text; a list is sent as parts only when every
     * element is one.
     */
    static Object outputToWire(Object output) {
        if (output == null) return "";
        if (output instanceof CharSequence) return output.toString();
        if (output instanceof List) {
            List<?> list = (List<?>) output;
            boolean parts = !list.isEmpty();
            for (Object obj : list) {
                if (obj instanceof LlmContentPart) continue;
                Map<?, ?> map = obj instanceof Map ? (Map<?, ?>) obj : null;
                Object type = map != null ? map.get("type") : null;
                if (!"input_text".equals(type) && !"input_image".equals(type) && !"input_file".equals(type)) { parts = false; break; }
            }
            if (!parts) return LlmJson.toJson(output);
            List<Object> out = new ArrayList<>();
            for (Object obj : list) out.add(obj instanceof LlmContentPart ? partToMap((LlmContentPart) obj) : obj);
            return out;
        }
        return LlmJson.toJson(output);
    }
    private static java.sql.Timestamp timestamp(Object epochSeconds) {
        Long seconds = LlmRetryClassifier.toLong(epochSeconds);
        return seconds != null ? new java.sql.Timestamp(seconds * 1000L) : null;
    }

    private static ParseOutput parseOutputItems(Object outputObj) {
        ParseOutput parsed = new ParseOutput();
        if (!(outputObj instanceof List)) return parsed;
        for (Object itemObj : (List<?>) outputObj) {
            Map<?, ?> itemMap = LlmRetryClassifier.asMap(itemObj);
            if (itemMap == null) continue;
            LlmItem item = mapToItem(itemMap);
            parsed.outputItems.add(item);
            String type = item.type;
            if ("message".equals(type)) appendContent(parsed, item.content);
            else if ("function_call".equals(type)) {
                // a call the model did not finish writing is not a call: its arguments may be cut off
                if (item.status == null || "completed".equals(item.status)) parsed.toolCalls.add(functionCallFromItem(item));
            }
            else if ("reasoning".equals(type) || "compaction".equals(type)) {
                appendReasoning(parsed.reasoning, item.content);
                appendReasoning(parsed.reasoning, item.summary);
            }
        }
        return parsed;
    }

    private static void appendContent(ParseOutput parsed, List<LlmContentPart> content) {
        if (content == null) return;
        for (LlmContentPart part : content) {
            if (part == null) continue;
            if ("output_text".equals(part.type) || "text".equals(part.type)) {
                if (part.text != null) parsed.content.append(part.text);
            } else if ("refusal".equals(part.type)) {
                if (part.refusal != null) parsed.content.append(part.refusal);
                parsed.refusal = true;
            } else if ("reasoning_text".equals(part.type) || "summary_text".equals(part.type)) {
                if (part.text != null) parsed.reasoning.append(part.text);
            }
        }
    }

    private static void appendReasoning(StringBuilder sb, List<LlmContentPart> content) {
        if (content == null) return;
        for (LlmContentPart part : content) if (part != null && part.text != null) sb.append(part.text);
    }

    private static LlmToolCall functionCallFromItem(LlmItem item) {
        String id = item.callId;
        if (id == null || id.isBlank()) id = item.providerItemId != null ? item.providerItemId : item.itemId;
        return new LlmToolCall(id, item.name, item.arguments);
    }

    private static void applyFinishReason(LlmProtocol.ProtocolResult r, Map<String, Object> body) {
        String status = str(body.get("status"));
        if ("completed".equalsIgnoreCase(status)) {
            if (r.toolCalls != null && !r.toolCalls.isEmpty()) r.finishReason = LlmFinishReason.TOOL_CALLS;
            else if (r.content != null && !r.content.isBlank()) r.finishReason = LlmFinishReason.STOP;
            else if (r.outputItems != null && !r.outputItems.isEmpty()) r.finishReason = LlmFinishReason.STOP;
            else {
                r.finishReason = LlmFinishReason.EMPTY;
                r.errorMessage = "Responses completed without output items";
            }
        } else if ("incomplete".equalsIgnoreCase(status)) {
            String reason = incompleteReason(body);
            r.finishReason = reason != null && reason.toLowerCase().contains("token") ? LlmFinishReason.LENGTH : LlmFinishReason.ERROR;
            r.errorMessage = reason != null ? reason : "Responses API returned incomplete status";
        } else if ("failed".equalsIgnoreCase(status)) {
            r.finishReason = LlmFinishReason.ERROR;
            r.errorMessage = "Responses API returned failed status";
        } else if ("queued".equalsIgnoreCase(status) || "in_progress".equalsIgnoreCase(status)) {
            // accepted, not finished: not an answer, not an empty answer and not an error; nothing in it is acted on
            r.finishReason = LlmFinishReason.PENDING;
            r.retryable = false;
        } else if ("cancelled".equalsIgnoreCase(status) || "canceled".equalsIgnoreCase(status)) {
            r.finishReason = LlmFinishReason.ERROR;
            r.errorMessage = "Responses API returned cancelled status";
            r.retryable = false;
        } else {
            r.finishReason = LlmFinishReason.ERROR;
            r.errorMessage = "Unsupported Responses API status: " + status;
        }
    }

    static String incompleteReason(Map<String, Object> body) {
        Map<?, ?> details = LlmRetryClassifier.asMap(body.get("incomplete_details"));
        return details != null ? str(details.get("reason")) : null;
    }

    static LlmResponseOptions optionsFromResponse(Map<String, Object> body) {
        LlmResponseOptions options = new LlmResponseOptions();
        for (String field : REQUEST_FIELDS) if (body.containsKey(field) && !"input".equals(field) && !"tools".equals(field))
            options.put(field, body.get(field));
        return options.asMap().isEmpty() ? null : options;
    }

    private static List<Map<String, Object>> annotationsToMaps(List<LlmContentAnnotation> annotations) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LlmContentAnnotation annotation : annotations) {
            if (annotation == null) continue;
            Map<String, Object> map = new LinkedHashMap<>();
            if (annotation.payload != null) map.putAll(annotation.payload);
            putIfNotBlank(map, "type", annotation.type);
            putIfNotBlank(map, "url", annotation.url);
            putIfNotBlank(map, "title", annotation.title);
            if (annotation.startIndex != null) map.put("start_index", annotation.startIndex);
            if (annotation.endIndex != null) map.put("end_index", annotation.endIndex);
            out.add(map);
        }
        return out;
    }

    private static List<LlmContentAnnotation> annotationsFromObject(Object annotationsObj) {
        if (!(annotationsObj instanceof List)) return null;
        List<LlmContentAnnotation> out = new ArrayList<>();
        for (Object obj : (List<?>) annotationsObj) {
            Map<?, ?> map = LlmRetryClassifier.asMap(obj);
            if (map == null) continue;
            LlmContentAnnotation annotation = new LlmContentAnnotation();
            annotation.payload = copyMap(map);
            annotation.type = str(map.get("type"));
            annotation.url = str(map.get("url"));
            annotation.title = str(map.get("title"));
            annotation.startIndex = LlmRetryClassifier.toInt(map.get("start_index"));
            annotation.endIndex = LlmRetryClassifier.toInt(map.get("end_index"));
            out.add(annotation);
        }
        return out;
    }

    private static List<Map<String, Object>> logprobsToMaps(List<LlmContentLogprob> logprobs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LlmContentLogprob logprob : logprobs) {
            if (logprob == null) continue;
            Map<String, Object> map = new LinkedHashMap<>();
            putIfNotBlank(map, "token", logprob.token);
            if (logprob.logprob != null) map.put("logprob", logprob.logprob);
            if (logprob.bytes != null) map.put("bytes", logprob.bytes);
            if (logprob.topLogprobs != null) map.put("top_logprobs", logprob.topLogprobs);
            out.add(map);
        }
        return out;
    }

    private static List<LlmContentLogprob> logprobsFromObject(Object logprobsObj) {
        if (!(logprobsObj instanceof List)) return null;
        List<LlmContentLogprob> out = new ArrayList<>();
        for (Object obj : (List<?>) logprobsObj) {
            Map<?, ?> map = LlmRetryClassifier.asMap(obj);
            if (map == null) continue;
            LlmContentLogprob logprob = new LlmContentLogprob();
            logprob.token = str(map.get("token"));
            Number lp = map.get("logprob") instanceof Number ? (Number) map.get("logprob") : null;
            if (lp != null) logprob.logprob = lp.doubleValue();
            Object bytesObj = map.get("bytes");
            if (bytesObj instanceof List) {
                logprob.bytes = new ArrayList<>();
                for (Object byteObj : (List<?>) bytesObj) {
                    Integer value = LlmRetryClassifier.toInt(byteObj);
                    if (value != null) logprob.bytes.add(value);
                }
            }
            Object topObj = map.get("top_logprobs");
            if (topObj instanceof List) {
                logprob.topLogprobs = new ArrayList<>();
                for (Object top : (List<?>) topObj) {
                    Map<?, ?> topMap = LlmRetryClassifier.asMap(top);
                    if (topMap != null) logprob.topLogprobs.add(copyMap(topMap));
                }
            }
            out.add(logprob);
        }
        return out;
    }

    private static Map<String, Object> copyMap(Map<?, ?> map) {
        if (map == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) if (entry.getKey() != null) out.put(entry.getKey().toString(), entry.getValue());
        return out;
    }

    private static void putIfNotBlank(Map<String, Object> map, String name, String value) {
        if (value != null && !value.isBlank()) map.put(name, value);
    }

    private static String str(Object value) { return value != null ? value.toString() : null; }

    static final class ParseOutput {
        final StringBuilder content = new StringBuilder();
        final StringBuilder reasoning = new StringBuilder();
        final List<LlmToolCall> toolCalls = new ArrayList<>();
        final List<LlmItem> outputItems = new ArrayList<>();
        boolean refusal;
    }
}
