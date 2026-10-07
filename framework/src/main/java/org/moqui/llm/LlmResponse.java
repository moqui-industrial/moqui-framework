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

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class LlmResponse {
    public String responseId;
    public String object;
    public String status;
    public String previousResponseId;
    public Timestamp createdAt;
    public Timestamp completedAt;
    public List<LlmItem> outputItems;
    public LlmResponseOptions options;
    public Map<String, Object> error;
    public Map<String, Object> incompleteDetails;
    public Map<String, Object> payload;
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
    /** The run and the stored request this response belongs to, when they were kept. */
    public String runId;
    public String requestId;
    public int httpStatus;
    public String errorMessage;
    public String providerErrorCode;
    public String providerErrorType;
    public String providerErrorParam;
    public long durationMs;
    public boolean yielded;
    /** True when the provider has not finished the response (queued or in progress): the turn waits, nothing was executed. */
    public boolean pending;
    /** For a pending response: whether this profile's protocol can ask the provider about it again. */
    public boolean pollable;
    public String rawJson;
    public List<LlmToolCall> pendingToolCalls;
    public List<LlmToolResult> toolResults;

    public LlmResponse() { }

    public String getResponseId() { return responseId; }
    public String getObject() { return object; }
    public String getStatus() { return status; }
    public String getPreviousResponseId() { return previousResponseId; }
    public Timestamp getCreatedAt() { return createdAt; }
    public Timestamp getCompletedAt() { return completedAt; }
    public List<LlmItem> getOutputItems() { return outputItems != null ? outputItems : new ArrayList<>(); }
    public LlmResponseOptions getOptions() { return options; }
    public Map<String, Object> getError() { return error; }
    public Map<String, Object> getIncompleteDetails() { return incompleteDetails; }
    public Map<String, Object> getPayload() { return payload; }
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
    public String getProviderErrorType() { return providerErrorType; }
    public String getProviderErrorParam() { return providerErrorParam; }
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
