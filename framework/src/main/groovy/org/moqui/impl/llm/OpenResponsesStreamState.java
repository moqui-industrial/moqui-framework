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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle rules of a Responses event stream, checked event by event: item and content events come after the response starts (a terminal event carries the whole response, so a stream may consist of it alone),
 * sequence numbers are consecutive, output items are added before they receive content and closed once, content parts
 * and summary parts are opened before their deltas, the "done" text equals the deltas that preceded it, nothing follows
 * a terminal event, and a completed response has no item still open. A violation throws and never turns into a
 * success. Event types outside the contract are tolerated and not tracked.
 */
final class OpenResponsesStreamState {
    private static final Set<String> KNOWN = Set.of("response.created", "response.queued", "response.in_progress",
            "response.completed", "response.failed", "response.incomplete", "error",
            "response.output_item.added", "response.output_item.done",
            "response.content_part.added", "response.content_part.done",
            "response.output_text.delta", "response.output_text.done", "response.output_text.annotation.added",
            "response.refusal.delta", "response.refusal.done",
            "response.function_call_arguments.delta", "response.function_call_arguments.done",
            "response.reasoning.delta", "response.reasoning.done",
            "response.reasoning_summary_part.added", "response.reasoning_summary_part.done",
            "response.reasoning_summary_text.delta", "response.reasoning_summary_text.done");

    private Long lastSequence;
    private boolean started;
    private boolean created;
    private boolean terminal;
    private final Map<Integer, String> itemType = new HashMap<>();
    private final Map<Integer, String> itemId = new HashMap<>();
    private final Set<Integer> openItems = new HashSet<>();
    private final Set<Integer> closedItems = new HashSet<>();
    private final Set<String> openParts = new HashSet<>();
    private final Set<String> closedParts = new HashSet<>();
    private final Set<String> openSummary = new HashSet<>();
    private final Set<String> closedSummary = new HashSet<>();
    private final Map<String, StringBuilder> text = new HashMap<>();
    private final Set<String> closedText = new HashSet<>();
    private final Map<Integer, StringBuilder> arguments = new HashMap<>();
    private final Set<Integer> closedArguments = new HashSet<>();

    boolean isTerminal() { return terminal; }

    void accept(String type, Map<String, Object> chunk) {
        if (type == null) return;
        if (terminal) throw violation(type, "event after the terminal event");
        // the WebSocket error envelope has no sequence_number in the contract; the SSE error event has
        if (!"error".equals(type) || chunk.get("sequence_number") != null) checkSequence(type, chunk);
        if (!KNOWN.contains(type)) return;
        switch (type) {
            case "response.created":
                if (created) throw violation(type, "second response.created");
                created = true; started = true;
                return;
            case "response.queued": case "response.in_progress":
                started = true;
                return;
            case "error":
                terminal = true;
                return;
            case "response.failed": case "response.incomplete":
                terminal = true;
                return;
            case "response.completed":
                if (!openItems.isEmpty()) throw violation(type, "output item " + openItems.iterator().next() + " is still open");
                terminal = true;
                return;
            default:
        }
        requireStarted(type);
        int out = index(type, chunk, "output_index");
        switch (type) {
            case "response.output_item.added": {
                if (openItems.contains(out) || closedItems.contains(out)) throw violation(type, "output_index " + out + " added twice");
                Map<?, ?> item = LlmRetryClassifier.asMap(chunk.get("item"));
                openItems.add(out);
                if (item != null) {
                    String t = LlmRetryClassifier.str(item.get("type"));
                    if (t != null) itemType.put(out, t);
                    String id = LlmRetryClassifier.str(item.get("id"));
                    if (id != null) itemId.put(out, id);
                }
                return;
            }
            case "response.output_item.done": {
                requireOpen(type, out);
                Map<?, ?> item = LlmRetryClassifier.asMap(chunk.get("item"));
                String t = item != null ? LlmRetryClassifier.str(item.get("type")) : null;
                if (t != null && itemType.get(out) != null && !t.equals(itemType.get(out)))
                    throw violation(type, "item type changed from " + itemType.get(out) + " to " + t);
                openItems.remove(out); closedItems.add(out);
                return;
            }
            case "response.content_part.added": {
                requireOpen(type, out, chunk, "message");
                String key = out + ":" + index(type, chunk, "content_index");
                if (openParts.contains(key) || closedParts.contains(key)) throw violation(type, "content part " + key + " added twice");
                openParts.add(key);
                return;
            }
            case "response.content_part.done": {
                requireOpen(type, out, chunk, "message");
                String key = out + ":" + index(type, chunk, "content_index");
                if (!openParts.remove(key)) throw violation(type, "content part " + key + " was not open");
                closedParts.add(key);
                return;
            }
            case "response.output_text.delta": case "response.refusal.delta": {
                String key = partKey(type, out, chunk, "message");
                String kind = type.startsWith("response.refusal") ? "refusal" : "text";
                if (closedText.contains(kind + key)) throw violation(type, "delta after the done event of " + key);
                text.computeIfAbsent(kind + key, k -> new StringBuilder()).append(delta(type, chunk));
                return;
            }
            case "response.output_text.done": case "response.refusal.done": {
                String key = partKey(type, out, chunk, "message");
                String kind = type.startsWith("response.refusal") ? "refusal" : "text";
                closeText(type, kind + key, LlmRetryClassifier.str(chunk.get(kind.equals("text") ? "text" : "refusal")));
                return;
            }
            case "response.output_text.annotation.added":
                partKey(type, out, chunk, "message");
                return;
            case "response.function_call_arguments.delta": {
                requireOpen(type, out, chunk, "function_call");
                if (closedArguments.contains(out)) throw violation(type, "delta after the done event of output_index " + out);
                arguments.computeIfAbsent(out, k -> new StringBuilder()).append(delta(type, chunk));
                return;
            }
            case "response.function_call_arguments.done": {
                requireOpen(type, out, chunk, "function_call");
                if (!closedArguments.add(out)) throw violation(type, "arguments of output_index " + out + " finished twice");
                String full = LlmRetryClassifier.str(chunk.get("arguments"));
                StringBuilder seen = arguments.get(out);
                if (full == null) throw violation(type, "arguments missing");
                if (seen != null && !seen.toString().equals(full)) throw violation(type, "arguments differ from the deltas received");
                return;
            }
            case "response.reasoning.delta": case "response.reasoning.done": {
                requireOpen(type, out, chunk, "reasoning");
                String key = "reasoning" + out + ":" + index(type, chunk, "content_index");
                if (type.endsWith(".delta")) {
                    if (closedText.contains(key)) throw violation(type, "delta after the done event of " + key);
                    text.computeIfAbsent(key, k -> new StringBuilder()).append(delta(type, chunk));
                } else closeText(type, key, LlmRetryClassifier.str(chunk.get("text")));
                return;
            }
            case "response.reasoning_summary_part.added": {
                requireOpen(type, out, chunk, "reasoning");
                String key = out + ":" + index(type, chunk, "summary_index");
                if (openSummary.contains(key) || closedSummary.contains(key)) throw violation(type, "summary part " + key + " added twice");
                openSummary.add(key);
                return;
            }
            case "response.reasoning_summary_part.done": {
                requireOpen(type, out, chunk, "reasoning");
                String key = out + ":" + index(type, chunk, "summary_index");
                if (!openSummary.remove(key)) throw violation(type, "summary part " + key + " was not open");
                closedSummary.add(key);
                return;
            }
            case "response.reasoning_summary_text.delta": case "response.reasoning_summary_text.done": {
                requireOpen(type, out, chunk, "reasoning");
                String key = out + ":" + index(type, chunk, "summary_index");
                if (!openSummary.contains(key)) throw violation(type, "summary part " + key + " is not open");
                String textKey = "summary" + key;
                if (type.endsWith(".delta")) {
                    if (closedText.contains(textKey)) throw violation(type, "delta after the done event of " + textKey);
                    text.computeIfAbsent(textKey, k -> new StringBuilder()).append(delta(type, chunk));
                } else closeText(type, textKey, LlmRetryClassifier.str(chunk.get("text")));
                return;
            }
            default:
        }
    }

    private void checkSequence(String type, Map<String, Object> chunk) {
        Long seq = LlmRetryClassifier.toLong(chunk.get("sequence_number"));
        if (seq == null) throw violation(type, "sequence_number is required");
        if (lastSequence != null && seq != lastSequence + 1L)
            throw new LlmException("Responses stream sequence gap or duplicate: expected " + (lastSequence + 1L)
                    + " but received " + seq);
        lastSequence = seq;
    }

    private void requireStarted(String type) {
        if (!started) throw violation(type, "received before response.created");
    }

    private void requireOpen(String type, int out) {
        if (!openItems.contains(out)) throw violation(type, closedItems.contains(out)
                ? "output_index " + out + " is already done" : "output_index " + out + " was never added");
    }

    private void requireOpen(String type, int out, Map<String, Object> chunk, String expectedType) {
        requireOpen(type, out);
        String actual = itemType.get(out);
        if (actual != null && !actual.equals(expectedType)) throw violation(type, "output_index " + out + " is a " + actual
                + " item, not a " + expectedType);
        String id = LlmRetryClassifier.str(chunk.get("item_id"));
        if (id == null) throw violation(type, "item_id is required");
        String known = itemId.get(out);
        if (known != null && !known.equals(id)) throw violation(type, "item_id " + id + " does not match " + known);
    }

    private String partKey(String type, int out, Map<String, Object> chunk, String expectedType) {
        requireOpen(type, out, chunk, expectedType);
        String key = out + ":" + index(type, chunk, "content_index");
        if (!openParts.contains(key)) throw violation(type, "content part " + key + " is not open");
        return key;
    }

    private void closeText(String type, String key, String full) {
        if (full == null) throw violation(type, "final text missing");
        if (!closedText.add(key)) throw violation(type, key + " finished twice");
        StringBuilder seen = text.get(key);
        if (seen != null && !seen.toString().equals(full)) throw violation(type, "final text differs from the deltas received");
    }

    private String delta(String type, Map<String, Object> chunk) {
        Object d = chunk.get("delta");
        if (!(d instanceof String)) throw violation(type, "delta must be a string");
        return (String) d;
    }

    private int index(String type, Map<String, Object> chunk, String field) {
        Integer v = LlmRetryClassifier.toInt(chunk.get(field));
        if (v == null || v < 0) throw violation(type, field + " is required and must not be negative");
        return v;
    }

    private static LlmException violation(String type, String problem) {
        return new LlmException("Responses stream violates the event lifecycle at " + type + ": " + problem + ".");
    }
}
