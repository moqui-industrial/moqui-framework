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

import org.moqui.BaseException;
import org.moqui.llm.LlmException;
import org.moqui.llm.LlmCompactResult;
import org.moqui.llm.LlmFinishReason;
import org.moqui.llm.LlmMessage;
import org.moqui.llm.LlmProtocol;
import org.moqui.llm.LlmResponseEvent;
import org.moqui.llm.LlmUsage;
import org.moqui.llm.OpenResponsesSpec;
import org.moqui.util.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class OpenResponsesProtocol implements LlmProtocol {
    private static final Logger logger = LoggerFactory.getLogger(OpenResponsesProtocol.class);
    public static final String DEFAULT_PATH = "/v1/responses";

    @Override public String getName() { return "open-responses"; }
    @Override public boolean supportsTools() { return true; }
    @Override public boolean supportsStreaming() { return true; }
    @Override public boolean supportsCompact() { return true; }
    @Override
    public java.util.Set<Capability> getCapabilities() {
        java.util.EnumSet<Capability> capabilities = java.util.EnumSet.copyOf(LlmProtocol.super.getCapabilities());
        capabilities.add(Capability.ITEM_TRAJECTORY);
        return capabilities;
    }
    @Override public boolean supportsWebSocket() { return true; }
    @Override public String getSpecVersion() { return OpenResponsesSpec.VERSION; }

    @Override
    public ProtocolResult chat(ProtocolRequest request) {
        validate(request);
        if (request.transport == org.moqui.llm.LlmTransport.WEBSOCKET) {
            final ProtocolResult[] result = new ProtocolResult[1];
            final Throwable[] failure = new Throwable[1];
            chatWebSocket(request, new ProtocolStreamListener() {
                @Override public void onDelta(String textDelta) { }
                @Override public void onComplete(ProtocolResult value) { result[0] = value; }
                @Override public void onFailure(Throwable error) { failure[0] = error; }
            });
            if (failure[0] != null) {
                if (failure[0] instanceof RuntimeException) throw (RuntimeException) failure[0];
                throw new LlmException("Responses WebSocket failed: " + failure[0].getMessage(), failure[0]);
            }
            return result[0];
        }
        String json = prepareBody(request);
        Map<String, Object> requestBody = request.preparedBody;
        RestClient.RestResponse response;
        try {
            response = OpenAiCompatProtocol.newChatRestClient(request, json).call();
        } catch (BaseException e) {
            ProtocolResult err = new ProtocolResult(LlmFinishReason.ERROR);
            err.errorMessage = e.getMessage();
            // A create that may have reached the provider is not sent again: a timeout, a reset or a broken answer leaves
            // it Uncertain, because nothing here says the provider would not run it twice. Only a connection that was never
            // established is certainly not sent, and may be tried again.
            if (connectionNeverEstablished(e)) {
                err.providerErrorCode = NOT_SENT;
                err.retryable = true;
            }
            if (logger.isDebugEnabled()) logger.debug("Responses API HTTP call failed for profile " + request.profileName, e);
            return err;
        }
        String raw = response.text();
        ProtocolResult result = parseResponse(response.getStatusCode(), raw, request);
        decorateResult(result, request, requestBody);
        if (!request.logContent) result.rawJson = null;
        return result;
    }

    @Override
    public void chatStream(ProtocolRequest request, ProtocolStreamListener listener) {
        if (listener == null) throw new IllegalArgumentException("ProtocolStreamListener is required");
        validate(request);
        if (request.transport == org.moqui.llm.LlmTransport.WEBSOCKET) {
            chatWebSocket(request, listener);
            return;
        }
        if (!request.stream) {
            // a body prepared for a plain call is not the streaming body
            request.stream = true;
            request.preparedJson = null;
            request.preparedBody = null;
        }
        String json = prepareBody(request);
        Map<String, Object> requestBody = request.preparedBody;
        RestClient restClient = OpenAiCompatProtocol.newChatRestClient(request, json);
        restClient.acceptContentType("text/event-stream");
        restClient.sseLimits(sseLimit("llm_sse_max_event_bytes", 16L * 1024 * 1024), sseLimit("llm_sse_max_stream_bytes", 256L * 1024 * 1024));
        // no timeout retry here either: see chat()

        StreamAssembler assembler = new StreamAssembler(request);
        AtomicBoolean finished = new AtomicBoolean(false);
        restClient.streamSse(new RestClient.SseConsumer() {
            @Override public boolean onEvent(String event, String data, String id) {
                assembler.accept(event, data, listener);
                return true;
            }
            @Override public void onDone() { finishStream(true); }
            @Override public void onComplete() { finishStream(false); }
            private void finishStream(boolean sentinel) {
                if (!finished.compareAndSet(false, true)) return;
                if (sentinel) assembler.markSentinel(); else assembler.markTransportDone();
                if (assembler.isComplete(requireSseSentinel())) {
                    ProtocolResult result;
                    try {
                        result = assembler.toResult(200, null);
                        decorateResult(result, request, requestBody);
                    } catch (RuntimeException e) {
                        // a stream that does not add up is reported as what it is, not as a stream without a result
                        listener.onFailure(e);
                        return;
                    }
                    listener.onComplete(result);
                } else if (assembler.isTerminal() && !sentinel) {
                    listener.onFailure(new java.io.IOException("Responses stream ended after its terminal event "
                            + "without the [DONE] sentinel the specification requires"));
                } else if (sentinel && !assembler.isTerminal()) {
                    listener.onFailure(new java.io.IOException("Responses stream sent [DONE] without a terminal event"));
                } else {
                    listener.onFailure(new java.io.IOException("Responses stream ended without terminal event"));
                }
            }
            @Override public void onFailure(Throwable t) {
                if (!finished.compareAndSet(false, true)) return;
                int status = OpenAiCompatProtocol.httpStatusOf(t);
                if (status >= 400) {
                    ProtocolResult result = assembler.toResult(status, t);
                    decorateResult(result, request, requestBody);
                    listener.onComplete(result);
                }
                else listener.onFailure(t);
            }
        }, request.onStreamOpen);
    }

    /** Persistent connections by session key; a request without a key uses a connection of its own. */
    private final java.util.concurrent.ConcurrentHashMap<String, OpenResponsesWebSocketSession> sessions =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** A kept connection that sat idle this long is closed the next time any session is looked up. */
    volatile long sessionIdleMillis = 5L * 60L * 1000L;

    void chatWebSocket(ProtocolRequest request, ProtocolStreamListener listener) {
        String json = prepareBody(request);
        Map<String, Object> requestBody = request.preparedBody;
        int timeoutSeconds = request.timeoutSeconds != null && request.timeoutSeconds > 0 ? request.timeoutSeconds : 120;
        boolean kept = request.sessionKey != null && !request.sessionKey.isBlank();
        OpenResponsesWebSocketSession session = kept ? sessionFor(request, timeoutSeconds)
                : OpenResponsesWebSocketSession.connect(this, request, null, timeoutSeconds);
        session.runTurn(request, json, requestBody, listener, timeoutSeconds, !kept);
    }

    private static long sseLimit(String name, long dflt) {
        String v = org.moqui.util.SystemBinding.getPropOrEnv(name);
        if (v == null || v.isBlank()) return dflt;
        try { return Math.max(Long.parseLong(v.trim()), 0L); } catch (NumberFormatException e) { return dflt; }
    }

    /** Kept connections at most; past it the one idle longest is closed to make room. */
    volatile int maxSessions = 64;

    private OpenResponsesWebSocketSession sessionFor(ProtocolRequest request, int timeoutSeconds) {
        sweepIdleSessions();
        while (sessions.size() >= maxSessions && !sessions.containsKey(request.sessionKey)) {
            OpenResponsesWebSocketSession oldest = null;
            for (OpenResponsesWebSocketSession candidate : sessions.values())
                if (candidate.isIdle() && (oldest == null || candidate.idleMillis() > oldest.idleMillis())) oldest = candidate;
            if (oldest == null) break;
            oldest.close(new java.io.IOException("Responses WebSocket session closed to make room for another"));
            forget(oldest);
        }
        return sessions.compute(request.sessionKey, (key, existing) ->
                existing != null && !existing.isClosed() ? existing
                        : OpenResponsesWebSocketSession.connect(this, request, key, timeoutSeconds));
    }

    private void sweepIdleSessions() {
        for (OpenResponsesWebSocketSession session : sessions.values())
            if (session.isIdle() && session.idleMillis() > sessionIdleMillis)
                session.close(new java.io.IOException("Responses WebSocket session idle too long"));
    }

    void forget(OpenResponsesWebSocketSession session) {
        if (session.key != null) sessions.remove(session.key, session);
    }

    @Override public boolean continuesOnConnection(String sessionKey, String previousResponseId) {
        if (sessionKey == null) return false;
        OpenResponsesWebSocketSession session = sessions.get(sessionKey);
        return session != null && session.continuesFrom(previousResponseId)
                && session.idleMillis() <= sessionIdleMillis;
    }

    @Override public void closeSession(String sessionKey) {
        OpenResponsesWebSocketSession session = sessionKey != null ? sessions.get(sessionKey) : null;
        if (session != null) session.close(new java.io.IOException("Responses WebSocket session closed"));
    }

    @Override public void closeSessionsOfScope(String scope) {
        if (scope == null || scope.isBlank()) return;
        String marker = "|" + scope + "|";
        for (Map.Entry<String, OpenResponsesWebSocketSession> entry : new ArrayList<>(sessions.entrySet()))
            if (entry.getKey() != null && entry.getKey().contains(marker))
                entry.getValue().close(new java.io.IOException("Responses WebSocket session closed: its " + scope + " ended"));
    }

    @Override public void close() {
        for (OpenResponsesWebSocketSession session : new ArrayList<>(sessions.values()))
            session.close(new java.io.IOException("Responses WebSocket protocol closed"));
    }

    /** providerErrorCode of a result for a request that never left: the connection could not be established. */
    public static final String NOT_SENT = "connection_not_established";

    /** True when the failure happened before any byte of the request was sent: refused, unresolved, no route, TLS handshake, connect timeout. */
    public static boolean connectionNeverEstablished(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof java.net.ConnectException || cur instanceof java.net.UnknownHostException
                    || cur instanceof java.net.NoRouteToHostException || cur instanceof javax.net.ssl.SSLHandshakeException
                    || cur instanceof java.nio.channels.UnresolvedAddressException) return true;
            String message = cur.getMessage();
            if (message != null && message.toLowerCase().contains("connect timeout")) return true;
        }
        return false;
    }

    public static Map<String, Object> buildWebSocketRequestBody(ProtocolRequest request) {
        Map<String, Object> body = new LinkedHashMap<>(OpenResponsesCodec.buildRequestBody(request));
        applyWebSocketFraming(body);
        return body;
    }

    /**
     * Builds the body for the request's transport once and keeps it on the request. The client persists this very
     * text before sending, and the transport sends it, so the stored snapshot is the wire body and not a rebuild.
     */
    public String prepareBody(ProtocolRequest request) {
        if (request.preparedJson != null) return request.preparedJson;
        Map<String, Object> body = request.transport == org.moqui.llm.LlmTransport.WEBSOCKET
                ? buildWebSocketRequestBodyForProtocol(request) : buildRequestBodyForProtocol(request);
        // a value the contract forbids is refused here, before it is stored or sent
        OpenResponsesLimits.validate(body);
        request.preparedBody = body;
        request.preparedJson = LlmJson.toExactJson(body);
        return request.preparedJson;
    }

    /** The compaction body, built once and kept on the request, so what is stored is what is sent. */
    public String prepareCompactBody(ProtocolRequest request) {
        if (request.preparedJson != null) return request.preparedJson;
        Map<String, Object> body = OpenResponsesCodec.buildCompactRequestBody(request);
        OpenResponsesLimits.validateCompact(body);
        request.preparedBody = body;
        request.preparedJson = LlmJson.toExactJson(body);
        return request.preparedJson;
    }

    protected Map<String, Object> buildWebSocketRequestBodyForProtocol(ProtocolRequest request) {
        Map<String, Object> body = new LinkedHashMap<>(buildRequestBodyForProtocol(request));
        applyWebSocketFraming(body);
        return body;
    }

    private static void applyWebSocketFraming(Map<String, Object> body) {
        body.remove("stream");
        body.remove("stream_options");
        body.remove("background");
        body.put("type", "response.create");
    }

    public static URI webSocketUri(String endpointUrl) {
        URI endpoint = URI.create(endpointUrl);
        String scheme;
        if ("https".equalsIgnoreCase(endpoint.getScheme())) scheme = "wss";
        else if ("http".equalsIgnoreCase(endpoint.getScheme())) scheme = "ws";
        else if ("ws".equalsIgnoreCase(endpoint.getScheme()) || "wss".equalsIgnoreCase(endpoint.getScheme())) scheme = endpoint.getScheme();
        else throw new LlmException("Unsupported Responses WebSocket endpoint scheme: " + endpoint.getScheme());
        try {
            return new URI(scheme, endpoint.getUserInfo(), endpoint.getHost(), endpoint.getPort(),
                    endpoint.getPath(), endpoint.getQuery(), endpoint.getFragment());
        } catch (java.net.URISyntaxException e) {
            throw new LlmException("Invalid Responses WebSocket endpoint", e);
        }
    }

    static void applyWebSocketHeaders(WebSocket.Builder builder, ProtocolRequest request) {
        String authName = request.authHeaderName != null && !request.authHeaderName.isBlank()
                ? request.authHeaderName : "Authorization";
        String authValue = request.authHeaderValue;
        if ((authValue == null || authValue.isBlank()) && request.apiKey != null && !request.apiKey.isBlank())
            authValue = "Bearer " + request.apiKey;
        if (authValue != null && !authValue.isBlank()) builder.header(authName, authValue);
        if (request.extraHeaders != null) request.extraHeaders.forEach((name, value) -> {
            if (name != null && !name.isBlank() && value != null) builder.header(name, value);
        });
    }

    static void validate(ProtocolRequest request) {
        if (request == null) throw new LlmException("ProtocolRequest is required");
        if (request.endpointUrl == null || request.endpointUrl.isBlank())
            throw new LlmException("LLM endpoint url is required", LlmFinishReason.ERROR, 0, request.profileName);
    }

    public static Map<String, Object> buildRequestBody(ProtocolRequest request) {
        return OpenResponsesCodec.buildRequestBody(request);
    }

    public static Map<String, Object> buildCompactRequestBody(ProtocolRequest request) {
        return OpenResponsesCodec.buildCompactRequestBody(request);
    }

    /**
     * The specification says a stream's last event MUST be the literal [DONE]. The standard protocol fails a stream
     * that ends after its terminal event without it; a provider protocol that is known to omit it may relax this.
     */
    protected boolean requireSseSentinel() { return true; }

    protected Map<String, Object> buildRequestBodyForProtocol(ProtocolRequest request) {
        return OpenResponsesCodec.buildRequestBody(request);
    }

    public static ProtocolResult retrieve(ProtocolRequest request, String responseId) {
        validate(request);
        if (responseId == null || responseId.isBlank()) throw new LlmException("responseId is required");
        RestClient.RestResponse response = responseRestClient(request, RestClient.GET, responsePath(request, responseId), null).call();
        return parseResponse(response.getStatusCode(), response.text(), request);
    }

    public static ProtocolResult cancel(ProtocolRequest request, String responseId) {
        validate(request);
        if (responseId == null || responseId.isBlank()) throw new LlmException("responseId is required");
        RestClient.RestResponse response = responseRestClient(request, RestClient.POST, responsePath(request, responseId) + "/cancel", "{}").call();
        return parseResponse(response.getStatusCode(), response.text(), request);
    }

    public static Map<String, Object> inputItems(ProtocolRequest request, String responseId) {
        return inputItems(request, responseId, null);
    }

    /**
     * The input items of a stored response, one page. The paging the provider offers is limit (1 to 100), order
     * (asc or desc) and after (the id the page continues from); anything else, or a value outside these, is refused before
     * a request is made. The answer is given as the provider wrote it, with its own first_id, last_id and has_more.
     */
    public static Map<String, Object> inputItems(ProtocolRequest request, String responseId, Map<String, Object> paging) {
        validate(request);
        if (responseId == null || responseId.isBlank()) throw new LlmException("responseId is required");
        StringBuilder query = new StringBuilder();
        if (paging != null) {
            for (Map.Entry<String, Object> e : paging.entrySet()) {
                Object v = e.getValue();
                if (v == null) continue;
                String name = e.getKey();
                if ("limit".equals(name)) {
                    if (!(v instanceof Number) || ((Number) v).doubleValue() != Math.rint(((Number) v).doubleValue())
                            || ((Number) v).intValue() < 1 || ((Number) v).intValue() > 100)
                        throw new LlmException("limit of the input items is an integer from 1 to 100");
                    append(query, "limit", String.valueOf(((Number) v).intValue()));
                } else if ("order".equals(name)) {
                    if (!"asc".equals(v) && !"desc".equals(v)) throw new LlmException("order of the input items is asc or desc");
                    append(query, "order", (String) v);
                } else if ("after".equals(name)) {
                    if (!(v instanceof String) || !((String) v).matches("[A-Za-z0-9_\\-]{1,128}"))
                        throw new LlmException("after of the input items is the id of an item");
                    append(query, "after", (String) v);
                } else throw new LlmException("The input items have no paging option " + name);
            }
        }
        String path = responsePath(request, responseId) + "/input_items" + (query.length() > 0 ? "?" + query : "");
        RestClient.RestResponse response = responseRestClient(request, RestClient.GET, path, null).call();
        if (response.getStatusCode() < 200 || response.getStatusCode() >= 300)
            throw new LlmException("Reading the input items failed with HTTP " + response.getStatusCode(),
                    LlmFinishReason.ERROR, response.getStatusCode(), request.profileName);
        return LlmJson.toMap(response.text());
    }

    private static void append(StringBuilder query, String name, String value) {
        if (query.length() > 0) query.append('&');
        query.append(name).append('=').append(java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8));
    }

    public static Map<String, Object> inputTokens(ProtocolRequest request) {
        validate(request);
        String json = LlmJson.toJson(buildRequestBody(request));
        RestClient.RestResponse response = responseRestClient(request, RestClient.POST, trimResponsesPath(request.endpointUrl) + "/input_tokens", json).call();
        return LlmJson.toMap(response.text());
    }

    @Override
    public LlmCompactResult compact(ProtocolRequest request) {
        validate(request);
        String json = prepareCompactBody(request);
        RestClient.RestResponse response = responseRestClient(request, RestClient.POST,
                trimResponsesPath(request.endpointUrl) + "/compact", json).call();
        if (response.getStatusCode() < 200 || response.getStatusCode() >= 300)
            throw new LlmException("Responses compaction failed with HTTP " + response.getStatusCode(),
                    LlmFinishReason.ERROR, response.getStatusCode(), request.profileName);
        Map<String, Object> payload = LlmJson.toMap(response.text());
        LlmCompactResult result = new LlmCompactResult();
        result.id = LlmRetryClassifier.str(payload.get("id"));
        result.object = LlmRetryClassifier.str(payload.get("object"));
        Long created = LlmRetryClassifier.toLong(payload.get("created_at"));
        if (created != null) result.createdAt = new java.sql.Timestamp(created * 1000L);
        result.output = new ArrayList<>();
        Object output = payload.get("output");
        if (output instanceof List) for (Object value : (List<?>) output) {
            Map<?, ?> map = LlmRetryClassifier.asMap(value);
            if (map != null) result.output.add(OpenResponsesCodec.mapToItem(map));
        }
        result.usage = OpenResponsesCodec.parseUsage(LlmRetryClassifier.asMap(payload.get("usage")));
        result.payload = payload;
        return result;
    }

    public static List<Map<String, Object>> convertInput(List<LlmMessage> window) {
        return OpenResponsesCodec.convertInput(window);
    }

    static void applyAllowedExtraBody(Map<String, Object> body, Map<String, Object> extraBody) {
        OpenResponsesCodec.applyExtraBody(body, extraBody);
    }

    static Map<String, Object> message(String role, String text) {
        return OpenResponsesCodec.message(role, text);
    }

    private static RestClient responseRestClient(ProtocolRequest request, RestClient.Method method, String uri, String json) {
        RestClient restClient = new RestClient()
                .method(method)
                .uri(uri)
                .contentType("application/json")
                .acceptContentType("application/json")
                .timeout(request.timeoutSeconds != null && request.timeoutSeconds > 0 ? request.timeoutSeconds : 120)
                .retry(request.retryInitialSeconds > 0 ? request.retryInitialSeconds : 2.0f,
                        request.retryMax >= 0 ? request.retryMax : 5)
                .redactHeaders(OpenAiCompatProtocol.redactHeaderNames(request))
                .allowInSim(true);
        if (json != null) restClient.text(json);
        if (request.requestFactory != null) restClient.withRequestFactory(request.requestFactory);
        OpenAiCompatProtocol.applyAuthAndHeaders(restClient, request);
        return restClient;
    }

    private static String responsePath(ProtocolRequest request, String responseId) {
        return trimResponsesPath(request.endpointUrl) + "/" + encodeSegment(responseId);
    }

    private static String trimResponsesPath(String endpointUrl) {
        String base = endpointUrl;
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.endsWith("/responses")) return base;
        int idx = base.indexOf("/responses/");
        return idx >= 0 ? base.substring(0, idx + "/responses".length()) : base;
    }

    private static String encodeSegment(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static ProtocolResult parseResponse(int httpStatus, String rawJson, ProtocolRequest request) {
        return OpenResponsesCodec.parseResponse(httpStatus, rawJson, request);
    }

    static ProtocolResult parseResponse(int httpStatus, Map<String, Object> body, String rawJson, String requestModel) {
        return OpenResponsesCodec.parseResponse(httpStatus, body, rawJson, requestModel);
    }

    static String incompleteReason(Map<String, Object> body) {
        return OpenResponsesCodec.incompleteReason(body);
    }

    static LlmUsage parseResponsesUsage(Map<?, ?> usage) {
        return OpenResponsesCodec.parseUsage(usage);
    }

    static void decorateResult(ProtocolResult result, ProtocolRequest request, Map<String, Object> requestBody) {
        if (result == null) return;
        result.runId = request.runId;
        result.runFence = request.runFence;
        result.inputItems = OpenResponsesCodec.requestItems(request);
        result.requestOptions = OpenResponsesCodec.effectiveRequestOptions(requestBody);
        result.transport = request.stream ? org.moqui.llm.LlmTransport.SSE : request.transport;
        result.specVersion = request.specVersion != null ? request.specVersion : OpenResponsesSpec.VERSION;
    }

    public static class StreamAssembler {
        private final ProtocolRequest request;
        private final StringBuilder content = new StringBuilder();
        private final TreeMap<Integer, ToolCallAcc> toolCalls = new TreeMap<>();
        private final Map<String, Integer> idToIndex = new LinkedHashMap<>();
        private Map<String, Object> terminal;
        private Map<String, Object> failed;
        private Map<String, Object> incomplete;
        private Map<String, Object> error;
        private Integer errorStatus;
        private Map<String, Object> usage;
        private String model;
        private String responseId;
        private final List<LlmResponseEvent> events = new ArrayList<>();
        private final StringBuilder raw = new StringBuilder();
        private boolean done;
        private final OpenResponsesStreamState state = new OpenResponsesStreamState();
        private final TreeMap<Integer, Object> doneItems = new TreeMap<>();

        public StreamAssembler(ProtocolRequest request) { this.request = request; }
        public boolean isError() { return error != null; }
        public boolean isTerminal() { return terminal != null || failed != null || incomplete != null || error != null; }
        private boolean sentinel;
        public boolean isComplete() { return isTerminal() && done; }
        /** A terminal event followed by [DONE]; without the sentinel only a protocol that tolerates it is complete. */
        public boolean isComplete(boolean requireSentinel) {
            return isTerminal() && (sentinel || (!requireSentinel && done));
        }
        public void markTransportDone() { done = true; }
        public void markSentinel() { done = true; sentinel = true; }

        public void accept(String event, String data, ProtocolStreamListener listener) {
            if (data == null || data.isBlank()) return;
            if (done) throw new LlmException("Responses stream emitted data after [DONE]");
            if ("[DONE]".equals(data.trim())) {
                done = true;
                return;
            }
            if (request.logContent) {
                if (raw.length() > 0) raw.append('\n');
                raw.append(data);
            }
            Map<String, Object> chunk;
            try { chunk = LlmJson.toMap(data); }
            catch (Throwable t) {
                throw new LlmException("Responses stream event is not valid JSON: " + t.getMessage(), t,
                        LlmFinishReason.ERROR, 0, request.profileName, null);
            }
            String declaredType = LlmRetryClassifier.str(chunk.get("type"));
            if (event != null && !event.isBlank() && declaredType != null && !event.equals(declaredType))
                throw new LlmException("Responses stream event name " + event + " does not match its type "
                        + declaredType, null, LlmFinishReason.ERROR, 0, request.profileName, null);
            if (event == null || event.isBlank()) event = declaredType;
            if (event == null) return;
            LlmResponseEvent responseEvent = new LlmResponseEvent();
            responseEvent.type = event;
            responseEvent.responseId = LlmRetryClassifier.str(chunk.get("response_id"));
            responseEvent.itemId = LlmRetryClassifier.str(chunk.get("item_id"));
            responseEvent.outputIndex = LlmRetryClassifier.toInt(chunk.get("output_index"));
            responseEvent.contentIndex = LlmRetryClassifier.toInt(chunk.get("content_index"));
            responseEvent.sequenceNumber = LlmRetryClassifier.toLong(chunk.get("sequence_number"));
            state.accept(event, chunk);
            responseEvent.terminal = "response.completed".equals(event) || "response.failed".equals(event)
                    || "response.incomplete".equals(event) || "error".equals(event);
            responseEvent.payload = chunk;
            events.add(responseEvent.copy());
            listener.onEvent(responseEvent);
            if ("response.output_text.delta".equals(event)) {
                String delta = LlmRetryClassifier.str(chunk.get("delta"));
                if (delta != null && !delta.isEmpty()) {
                    content.append(delta);
                    listener.onDelta(delta);
                }
            } else if ("response.function_call_arguments.delta".equals(event)) {
                ToolCallAcc acc = accFor(chunk);
                String delta = LlmRetryClassifier.str(chunk.get("delta"));
                if (delta != null) acc.arguments.append(delta);
                if ("write_ui".equals(acc.name) && acc.arguments.length() - acc.lastDeltaLen >= 48) {
                    acc.lastDeltaLen = acc.arguments.length();
                    listener.onToolCallDelta(acc.name, acc.arguments.toString());
                }
            } else if ("response.function_call_arguments.done".equals(event)) {
                ToolCallAcc acc = accFor(chunk);
                String args = LlmRetryClassifier.str(chunk.get("arguments"));
                if (args != null) {
                    acc.arguments.setLength(0);
                    acc.arguments.append(args);
                }
            } else if ("response.output_item.added".equals(event) || "response.output_item.done".equals(event)) {
                Map<?, ?> item = LlmRetryClassifier.asMap(chunk.get("item"));
                Integer doneIndex = LlmRetryClassifier.toInt(chunk.get("output_index"));
                if (item != null && "response.output_item.done".equals(event) && doneIndex != null) doneItems.put(doneIndex, item);
                if (item != null && "function_call".equals(LlmRetryClassifier.str(item.get("type")))) mergeFunctionItem(item, chunk);
            } else if ("response.created".equals(event)) {
                responseMap(chunk);
            } else if ("response.completed".equals(event)) {
                terminal = responseMap(chunk);
            } else if ("response.failed".equals(event)) {
                failed = responseMap(chunk);
            } else if ("response.incomplete".equals(event)) {
                incomplete = responseMap(chunk);
            } else if ("error".equals(event)) {
                Map<?, ?> errorPayload = LlmRetryClassifier.asMap(chunk.get("error"));
                error = new LinkedHashMap<>();
                // the whole envelope is kept: type, status, and the error object with its code, message and param
                errorStatus = LlmRetryClassifier.toInt(chunk.get("status"));
                error.put("type", "error");
                if (errorStatus != null) error.put("status", errorStatus);
                error.put("error", errorPayload != null ? errorPayload : chunk.get("error"));
            }
        }

        Map<String, Object> responseMap(Map<String, Object> chunk) {
            Map<?, ?> response = LlmRetryClassifier.asMap(chunk.get("response"));
            Map<String, Object> map;
            if (response != null) {
                @SuppressWarnings("unchecked") Map<String, Object> cast = (Map<String, Object>) response;
                map = cast;
            } else map = new LinkedHashMap<>();
            if (map.get("model") != null) model = LlmRetryClassifier.str(map.get("model"));
            if (map.get("id") != null) responseId = LlmRetryClassifier.str(map.get("id"));
            Map<?, ?> usageMap = LlmRetryClassifier.asMap(map.get("usage"));
            if (usageMap != null) {
                @SuppressWarnings("unchecked") Map<String, Object> castUsage = (Map<String, Object>) usageMap;
                usage = castUsage;
            }
            return map;
        }

        ToolCallAcc accFor(Map<String, Object> chunk) {
            Integer idx = LlmRetryClassifier.toInt(chunk.get("output_index"));
            String itemId = LlmRetryClassifier.str(chunk.get("item_id"));
            String callId = LlmRetryClassifier.str(chunk.get("call_id"));
            if (idx == null && itemId != null) idx = idToIndex.get(itemId);
            if (idx == null && callId != null) idx = idToIndex.get(callId);
            if (idx == null) idx = toolCalls.isEmpty() ? 0 : toolCalls.lastKey();
            ToolCallAcc acc = toolCalls.get(idx);
            if (acc == null) {
                acc = new ToolCallAcc();
                toolCalls.put(idx, acc);
            }
            if (itemId != null) idToIndex.put(itemId, idx);
            if (callId != null) {
                acc.id = callId;
                idToIndex.put(callId, idx);
            }
            String name = LlmRetryClassifier.str(chunk.get("name"));
            if (name != null) acc.name = name;
            return acc;
        }

        void mergeFunctionItem(Map<?, ?> item, Map<String, Object> chunk) {
            ToolCallAcc acc = accFor(chunk);
            String callId = LlmRetryClassifier.str(item.get("call_id"));
            if (callId == null) callId = LlmRetryClassifier.str(item.get("id"));
            if (callId != null) acc.id = callId;
            String name = LlmRetryClassifier.str(item.get("name"));
            if (name != null) acc.name = name;
            String args = LlmRetryClassifier.str(item.get("arguments"));
            if (args != null && acc.arguments.length() == 0) acc.arguments.append(args);
        }

        public ProtocolResult toResult(int httpStatus, Throwable t) {
            if (error != null) return withEvents(parseResponse(httpStatus >= 400 ? httpStatus
                    : errorStatus != null && errorStatus >= 400 && errorStatus <= 599 ? errorStatus : 500, error,
                    request.logContent ? raw.toString() : null, request.model));
            if (failed != null) return withEvents(parseResponse(httpStatus, failed, request.logContent ? raw.toString() : null, request.model));
            if (incomplete != null) return withEvents(parseResponse(httpStatus, incomplete, request.logContent ? raw.toString() : null, request.model));
            if (terminal != null) {
                Map<String, Object> completed = terminal;
                requireSnapshotMatchesItems(completed);
                if (completed.get("output") == null) {
                    completed = new LinkedHashMap<>(terminal);
                    completed.put("output", new ArrayList<>(doneItems.values()));
                }
                return withEvents(parseResponse(httpStatus, completed, request.logContent ? raw.toString() : null, request.model));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "completed");
            if (responseId != null) body.put("id", responseId);
            if (model != null) body.put("model", model);
            else if (request.model != null) body.put("model", request.model);
            if (usage != null) body.put("usage", usage);
            List<Map<String, Object>> output = new ArrayList<>();
            if (content.length() > 0) output.add(message("assistant", content.toString()));
            for (ToolCallAcc acc : toolCalls.values()) {
                Map<String, Object> fc = new LinkedHashMap<>();
                fc.put("type", "function_call");
                fc.put("call_id", acc.id);
                fc.put("name", acc.name);
                fc.put("arguments", acc.arguments.toString());
                output.add(fc);
            }
            body.put("output", output);
            return withEvents(parseResponse(httpStatus, body, request.logContent ? raw.toString() : null, request.model));
        }

        /**
         * The response of response.completed and the items the stream finished are the same facts told twice. When both are
         * there they have to agree (same count, and per index the same type, id and text); otherwise a result would silently
         * take the one and lose or replace what the other said.
         */
        private void requireSnapshotMatchesItems(Map<String, Object> completed) {
            Object output = completed.get("output");
            if (!(output instanceof List) || doneItems.isEmpty()) return;
            List<?> snapshot = (List<?>) output;
            if (snapshot.size() != doneItems.size())
                throw inconsistent("response.completed has " + snapshot.size() + " output items and the stream finished " + doneItems.size());
            for (Map.Entry<Integer, Object> done : doneItems.entrySet()) {
                int index = done.getKey();
                Map<?, ?> streamed = LlmRetryClassifier.asMap(done.getValue());
                Map<?, ?> final_ = index < snapshot.size() ? LlmRetryClassifier.asMap(snapshot.get(index)) : null;
                if (final_ == null)
                    throw inconsistent("the item at output_index " + index + " was finished in the stream and is not in response.completed");
                if (!java.util.Objects.equals(streamed.get("type"), final_.get("type")))
                    throw inconsistent("the item at output_index " + index + " is a " + streamed.get("type") + " in the stream and a " + final_.get("type") + " in response.completed");
                if (streamed.get("id") != null && final_.get("id") != null && !streamed.get("id").equals(final_.get("id")))
                    throw inconsistent("the item at output_index " + index + " has another id in response.completed");
                if ("message".equals(streamed.get("type")) && !textOf(streamed).equals(textOf(final_)))
                    throw inconsistent("the text of the item at output_index " + index + " differs between the stream and response.completed");
                if ("function_call".equals(streamed.get("type")) && streamed.get("arguments") != null && final_.get("arguments") != null
                        && !streamed.get("arguments").equals(final_.get("arguments")))
                    throw inconsistent("the arguments of the call at output_index " + index + " differ between the stream and response.completed");
            }
        }

        private static String textOf(Map<?, ?> message) {
            StringBuilder text = new StringBuilder();
            Object content = message.get("content");
            if (content instanceof List) for (Object part : (List<?>) content) {
                Map<?, ?> map = LlmRetryClassifier.asMap(part);
                if (map != null && map.get("text") instanceof String) text.append((String) map.get("text"));
                else if (map != null && map.get("refusal") instanceof String) text.append((String) map.get("refusal"));
            }
            return text.toString();
        }

        private LlmException inconsistent(String what) {
            return new LlmException("Responses stream is inconsistent: " + what, null, LlmFinishReason.ERROR, 0, request.profileName, null);
        }

        private ProtocolResult withEvents(ProtocolResult result) {
            if (result != null && !events.isEmpty()) {
                result.events = new ArrayList<>();
                for (LlmResponseEvent event : events) result.events.add(event != null ? event.copy() : null);
            }
            return result;
        }
    }

    static final class ToolCallAcc {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
        int lastDeltaLen;
    }

}
