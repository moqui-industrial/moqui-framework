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

import org.moqui.util.RestClient;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface LlmProtocol {
    String getName();

    /** Blocking chat. Must not run the agent loop. Must not persist. */
    ProtocolResult chat(ProtocolRequest request);

    /** Streaming chat. Implementations that do not support streaming throw UOE. */
    void chatStream(ProtocolRequest request, ProtocolStreamListener listener);

    boolean supportsTools();
    boolean supportsStreaming();
    default boolean supportsCompact() { return false; }
    default boolean supportsWebSocket() { return false; }
    default Set<Capability> getCapabilities() {
        EnumSet<Capability> capabilities = EnumSet.of(Capability.CREATE);
        if (supportsTools()) capabilities.add(Capability.TOOLS);
        if (supportsStreaming()) capabilities.add(Capability.STREAM_SSE);
        if (supportsCompact()) capabilities.add(Capability.COMPACT);
        if (supportsWebSocket()) capabilities.add(Capability.WEBSOCKET);
        return capabilities;
    }
    default String getSpecVersion() { return null; }
    /**
     * Whether a connection-local continuation of {@code previousResponseId} is still possible for the session: the
     * session's connection is open, idle, and produced exactly that response. False for protocols without sessions.
     */
    default boolean continuesOnConnection(String sessionKey, String previousResponseId) { return false; }
    /** Closes the persistent connection of one session, if any. */
    default void closeSession(String sessionKey) { }
    /** Closes every persistent connection whose scope is the given one, for example {@code conv:123} or {@code run:456}. */
    default void closeSessionsOfScope(String scope) { }
    /** Releases every connection this protocol holds; the facade calls it on shutdown. */
    default void close() { }
    default LlmCompactResult compact(ProtocolRequest request) {
        throw new UnsupportedOperationException("Compaction is not supported by " + getName());
    }

    enum Capability {
        CREATE, TOOLS, STREAM_SSE, COMPACT, WEBSOCKET,
        RETRIEVE, CANCEL, LIST_INPUT_ITEMS, COUNT_INPUT_TOKENS,
        /** The conversation is a trajectory of structured items kept by the runs, not a transcript of messages. */
        ITEM_TRAJECTORY
    }

    final class ProtocolRequest {
        public String profileName;
        public String runId;
        /** What this request is stored as: null for a create, {@code compact_response} for a compaction. */
        public String operation;
        /** Instructions of this request, kept apart from the input (the Responses {@code instructions} field). */
        public String instructions;
        public String endpointUrl;
        public String apiKey;
        public String authHeaderName;
        public String authHeaderValue;
        public Map<String, String> extraHeaders;
        public Map<String, String> extraQuery;
        public String model;
        public String inputText;
        public List<LlmItem> inputItems;
        public String previousResponseId;
        public LlmResponseOptions responseOptions;
        public LlmTransport transport = LlmTransport.HTTP;
        public String specVersion;
        public List<LlmMessage> window;
        public List<LlmTool> tools;
        public Double temperature;
        public Integer maxTokens;
        public String maxTokensParameter;
        public Integer timeoutSeconds;
        public boolean stream;
        public Map<String, Object> extraBody;
        public RestClient.RequestFactory requestFactory;
        public float retryInitialSeconds = 2.0f;
        public int retryMax = 5;
        public boolean timeoutRetry = true;
        public boolean logContent;
        public String localRequestId;
        /** The in-progress response row written while the stream is still open, and how many events it already holds. */
        public String localStreamResponseId;
        public int streamEventsPersisted;
        /**
         * Identifies the persistent connection this request may use (owner, conversation or run, profile, endpoint and
         * credential); null when the request must use a connection of its own.
         */
        public String sessionKey;
        /** Fencing token of the run this request belongs to; 0 when the request is not part of a durable run. */
        public long runFence;
        /**
         * The body exactly as it goes on the wire (HTTP body or WebSocket text frame), built once by the protocol.
         * The persisted snapshot and the transport use these same characters.
         */
        public String preparedJson;
        public Map<String, Object> preparedBody;
        /** Set by chatStream so /cancel and client disconnect can RestStream.close(). */
        public java.util.function.Consumer<RestClient.RestStream> onStreamOpen;
    }

    final class ProtocolResult {
        public String content;
        public String runId;
        /** Fencing token of the worker that obtained this result; 0 when not part of a durable run. */
        public long runFence;
        public String localResponseId;
        public String localRequestId;
        public String localStreamResponseId;
        public int streamEventsPersisted;
        public String responseId;
        public String status;
        public String previousResponseId;
        public List<LlmItem> inputItems;
        public List<LlmItem> outputItems;
        public List<LlmResponseEvent> events;
        public LlmResponseOptions requestOptions;
        public LlmResponseOptions responseOptions;
        public Map<String, Object> responsePayload;
        public LlmTransport transport;
        public String specVersion;
        public java.sql.Timestamp createdAt;
        public java.sql.Timestamp completedAt;
        /** message.refusal / delta.refusal when the model declined to answer. */
        public String refusal;
        /** See {@link LlmResponse#metadata}. */
        public Map<String, Object> metadata;
        /** Provider thinking / reasoning_content when present (logged, not persisted). */
        public String reasoning;
        public LlmFinishReason finishReason;
        public String providerErrorCode;
        public String providerErrorType;
        public String providerErrorParam;
        public List<LlmToolCall> toolCalls;
        public LlmUsage usage;
        public String model;
        public int httpStatus;
        public String errorMessage;
        public String rawJson;
        /** Layer B: retry this result (5xx or HTTP 200 rate-limit JSON). */
        public boolean retryable;

        public ProtocolResult() { }
        public ProtocolResult(LlmFinishReason finishReason) { this.finishReason = finishReason; }

        public List<LlmToolCall> getToolCalls() {
            return toolCalls != null ? toolCalls : new ArrayList<>();
        }
    }

    interface ProtocolStreamListener {
        default void onEvent(LlmResponseEvent event) { }
        void onDelta(String textDelta);
        void onComplete(ProtocolResult result);
        void onFailure(Throwable t);
        /** Partial tool-call arguments while streaming (write_ui lang onto the canvas). */
        default void onToolCallDelta(String name, String argumentsSoFar) { }
        /** A fragment of a refusal; refusal text is not content and is reported apart from it. */
        default void onRefusalDelta(String refusalDelta) { }
    }
}
