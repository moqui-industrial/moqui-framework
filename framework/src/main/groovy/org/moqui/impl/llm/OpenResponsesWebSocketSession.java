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
import org.moqui.llm.LlmProtocol.ProtocolRequest;
import org.moqui.llm.LlmProtocol.ProtocolResult;
import org.moqui.llm.LlmProtocol.ProtocolStreamListener;
import org.moqui.llm.LlmTransport;
import org.moqui.util.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One Responses WebSocket connection. At most one response is in flight on it; a second turn started while one runs is
 * refused. A session that is kept (it has a key) stays open after a terminal event so a following turn can continue
 * from the response the connection already holds; a one-shot session closes after its turn. Any connection failure,
 * timeout, cancellation or protocol error closes the session, fails the turn exactly once and releases the socket.
 * The provider's connection-local state is modelled by {@link #continuesFrom}: the id of the last response that
 * completed on this connection.
 */
final class OpenResponsesWebSocketSession {
    private static final Logger logger = LoggerFactory.getLogger(OpenResponsesWebSocketSession.class);

    private enum State { IDLE, BUSY, CLOSED }

    private final OpenResponsesProtocol protocol;
    final String key;
    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private volatile WebSocket socket;
    private volatile Turn turn;
    private volatile String lastCompletedResponseId;
    private volatile long lastUsedMillis = System.currentTimeMillis();
    private final StringBuilder buffer = new StringBuilder();

    private OpenResponsesWebSocketSession(OpenResponsesProtocol protocol, String key) {
        this.protocol = protocol;
        this.key = key;
    }

    /** Opens a connection; whatever was opened is released when the connection cannot be established. */
    static OpenResponsesWebSocketSession connect(OpenResponsesProtocol protocol, ProtocolRequest request, String key,
            int timeoutSeconds) {
        OpenResponsesWebSocketSession session = new OpenResponsesWebSocketSession(protocol, key);
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build();
        WebSocket.Builder builder = httpClient.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds));
        OpenResponsesProtocol.applyWebSocketHeaders(builder, request);
        CompletableFuture<WebSocket> opening = builder.buildAsync(OpenResponsesProtocol.webSocketUri(request.endpointUrl),
                session.new Listener());
        try {
            session.socket = opening.get(timeoutSeconds, TimeUnit.SECONDS);
            return session;
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            session.discard(cause);
            if (cause instanceof LlmException) throw (LlmException) cause;
            throw new LlmException("Responses WebSocket failed: " + cause.getMessage(), cause);
        } catch (java.util.concurrent.TimeoutException e) {
            opening.cancel(true);
            session.discard(e);
            throw new LlmException("Responses WebSocket timed out", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            opening.cancel(true);
            session.discard(e);
            throw new LlmException("Responses WebSocket interrupted", e);
        }
    }

    boolean isClosed() { return state.get() == State.CLOSED; }
    boolean isIdle() { return state.get() == State.IDLE; }
    long idleMillis() { return System.currentTimeMillis() - lastUsedMillis; }

    /** True when this open, idle connection produced the response {@code previousResponseId} and still holds it. */
    boolean continuesFrom(String previousResponseId) {
        return previousResponseId != null && isIdle() && previousResponseId.equals(lastCompletedResponseId);
    }

    private void closeOrderly() {
        if (state.getAndSet(State.CLOSED) == State.CLOSED) return;
        lastCompletedResponseId = null;
        protocol.forget(this);
        WebSocket s = socket;
        if (s != null) {
            try { s.sendClose(WebSocket.NORMAL_CLOSURE, "complete"); } catch (Throwable ignored) { }
        }
    }

    /** Closes the session; a turn in flight fails with {@code cause}. */
    void close(Throwable cause) { discard(cause); }

    private void discard(Throwable cause) {
        State previous = state.getAndSet(State.CLOSED);
        if (previous == State.CLOSED) return;
        lastCompletedResponseId = null;
        protocol.forget(this);
        WebSocket s = socket;
        if (s != null) {
            try { s.abort(); } catch (Throwable ignored) { }
        }
        Turn t = turn;
        if (t != null) t.fail(cause != null ? cause : new java.io.IOException("Responses WebSocket session closed"));
    }

    /**
     * Sends one {@code response.create} and delivers its events to {@code listener}. The listener receives either one
     * onComplete or one onFailure. Returns after the turn has ended (or failed).
     */
    void runTurn(ProtocolRequest request, String json, Map<String, Object> requestBody, ProtocolStreamListener listener,
            int timeoutSeconds, boolean closeAfter) {
        if (!state.compareAndSet(State.IDLE, State.BUSY)) {
            throw new LlmException(state.get() == State.CLOSED
                    ? "Responses WebSocket session is closed"
                    : "Responses WebSocket session is busy: one response may be in flight per connection",
                    LlmFinishReason.ERROR, 0, request.profileName);
        }
        Turn t = new Turn(request, requestBody, listener, closeAfter);
        turn = t;
        lastUsedMillis = System.currentTimeMillis();
        try {
            if (request.onStreamOpen != null) request.onStreamOpen.accept(new SocketStream());
            socket.sendText(json, true);
            t.completion.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            discard(cause);
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new LlmException("Responses WebSocket failed: " + cause.getMessage(), cause);
        } catch (java.util.concurrent.TimeoutException e) {
            discard(e);
            throw new LlmException("Responses WebSocket timed out", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            discard(e);
            throw new LlmException("Responses WebSocket interrupted", e);
        } catch (RuntimeException e) {
            discard(e);
            throw e;
        } finally {
            if (turn == t) turn = null;
        }
    }

    private final class Listener implements WebSocket.Listener {
        @Override public void onOpen(WebSocket s) { s.request(1); }

        @Override public CompletionStage<?> onText(WebSocket s, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String payload = buffer.toString();
                buffer.setLength(0);
                Turn t = turn;
                if (t == null) discard(new java.io.IOException("Responses WebSocket sent a message outside a turn"));
                else t.onMessage(payload);
            }
            if (!isClosed()) s.request(1);
            return null;
        }

        @Override public CompletionStage<?> onClose(WebSocket s, int statusCode, String reason) {
            discard(new java.io.IOException("Responses WebSocket closed without terminal event (" + statusCode + "): " + reason));
            return null;
        }

        @Override public void onError(WebSocket s, Throwable error) { discard(error); }
    }

    private final class Turn {
        private final ProtocolRequest request;
        private final Map<String, Object> requestBody;
        private final ProtocolStreamListener listener;
        private final boolean closeAfter;
        private final OpenResponsesProtocol.StreamAssembler assembler;
        private final AtomicBoolean finished = new AtomicBoolean(false);
        final CompletableFuture<Void> completion = new CompletableFuture<>();

        Turn(ProtocolRequest request, Map<String, Object> requestBody, ProtocolStreamListener listener, boolean closeAfter) {
            this.request = request;
            this.requestBody = requestBody;
            this.listener = listener;
            this.closeAfter = closeAfter;
            this.assembler = new OpenResponsesProtocol.StreamAssembler(request);
        }

        void onMessage(String payload) {
            if (finished.get()) return;
            try {
                Map<String, Object> event = LlmJson.toMap(payload);
                String type = LlmRetryClassifier.str(event.get("type"));
                assembler.accept(type, payload, listener);
                if (!assembler.isTerminal() || !finished.compareAndSet(false, true)) return;
                assembler.markTransportDone();
                ProtocolResult result = assembler.toResult(200, null);
                OpenResponsesProtocol.decorateResult(result, request, requestBody);
                result.transport = LlmTransport.WEBSOCKET;
                boolean keep = !closeAfter && !assembler.isError() && "completed".equals(result.status)
                        && result.responseId != null;
                // the session is idle again before the caller hears about the end, so it may start the next turn
                if (keep) {
                    lastCompletedResponseId = result.responseId;
                    lastUsedMillis = System.currentTimeMillis();
                    turn = null;
                    state.set(State.IDLE);
                }
                try { listener.onComplete(result); }
                finally {
                    completion.complete(null);
                    if (!keep) {
                        if (closeAfter && !assembler.isError()) closeOrderly();
                        else discard(null);
                    }
                }
            } catch (Throwable t) {
                fail(t);
            }
        }

        void fail(Throwable cause) {
            if (!finished.compareAndSet(false, true)) return;
            try { listener.onFailure(cause); }
            catch (Throwable t) { logger.warn("Responses WebSocket listener failed while reporting an error: {}", t.toString()); }
            completion.completeExceptionally(cause);
            discard(cause);
        }
    }

    /** Lets a cancel of the caller abort the connection; the session is then gone, not reused. */
    private final class SocketStream implements RestClient.RestStream {
        @Override public int getStatusCode() { return 101; }
        @Override public String getReasonPhrase() { return "Switching Protocols"; }
        @Override public String getContentType() { return "application/json"; }
        @Override public Map<String, ArrayList<String>> headers() { return new LinkedHashMap<>(); }
        @Override public java.io.InputStream getInputStream() { throw new UnsupportedOperationException(); }
        @Override public java.io.BufferedReader reader() { throw new UnsupportedOperationException(); }
        @Override public RestClient getClient() { return null; }
        @Override public RestClient.RestStream checkError() { return this; }
        @Override public void close() { discard(new java.util.concurrent.CancellationException("Responses WebSocket cancelled")); }
    }
}
