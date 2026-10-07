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
package org.moqui.llm;

import java.util.ArrayList;
import java.util.List;

public final class LlmResponse {
    public String content;
    /** The model's refusal text when it declined to answer; content is not replaced by it. */
    public String refusal;
    /**
     * What the provider reported besides the text, structured: response (id, object, created, model, service_tier,
     * system_fingerprint), choice (index, finish_reason, logprobs), message (annotations) and usage (the complete
     * usage object with its details). Present also when raw logging is off. Null when nothing was reported.
     */
    public java.util.Map<String, Object> metadata;
    public LlmFinishReason finishReason;
    public List<LlmToolCall> toolCalls;
    public LlmUsage usage;
    public String model;
    public String profileName;
    public String conversationId;
    public int httpStatus;
    public String errorMessage;
    public String providerErrorCode;
    public long durationMs;
    public boolean yielded;
    public String rawJson;
    public List<LlmToolCall> pendingToolCalls;
    public List<LlmToolResult> toolResults;

    public LlmResponse() { }

    public String getContent() { return content; }
    public String getRefusal() { return refusal; }
    public java.util.Map<String, Object> getMetadata() { return metadata; }
    public LlmFinishReason getFinishReason() { return finishReason; }
    public List<LlmToolCall> getToolCalls() { return toolCalls != null ? toolCalls : new ArrayList<>(); }
    public LlmUsage getUsage() { return usage; }
    public String getModel() { return model; }
    public String getProfileName() { return profileName; }
    public String getConversationId() { return conversationId; }
    public int getHttpStatus() { return httpStatus; }
    public String getErrorMessage() { return errorMessage; }
    public String getProviderErrorCode() { return providerErrorCode; }
    public long getDurationMs() { return durationMs; }
    public boolean isYielded() { return yielded; }
    public String getRawJson() { return rawJson; }
    public List<LlmToolCall> getPendingToolCalls() {
        return pendingToolCalls != null ? pendingToolCalls : new ArrayList<>();
    }
    public List<LlmToolResult> getToolResults() {
        return toolResults != null ? toolResults : new ArrayList<>();
    }
}
