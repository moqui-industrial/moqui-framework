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

import org.moqui.llm.LlmProtocol.ProtocolRequest;
import org.moqui.llm.LlmResponseEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists a streamed response while it arrives. Events are buffered and written, each batch in its own transaction, at
 * the semantic boundaries of the stream (the response start, every finished output item, the terminal event) and at
 * most {@link #MAX_BUFFER} events apart, so a crash or a lost connection leaves the events received so far. The final
 * result completes the same response row. A failure to write is logged and stops the journal; the final write then
 * stores whatever was not journaled. A lost run lease is not swallowed: the stale worker stops.
 */
final class LlmStreamJournal {
    private static final Logger logger = LoggerFactory.getLogger(LlmStreamJournal.class);
    static final int MAX_BUFFER = 32;

    private final LlmConversationImpl target;
    private final ProtocolRequest request;
    private final String profileName;
    private final String protocolName;
    private final String model;
    private final List<LlmResponseEvent> buffer = new ArrayList<>();
    private boolean disabled;

    LlmStreamJournal(LlmConversationImpl target, ProtocolRequest request, String profileName, String protocolName, String model) {
        this.target = target;
        this.request = request;
        this.profileName = profileName;
        this.protocolName = protocolName;
        this.model = model;
    }

    void onEvent(LlmResponseEvent event) {
        if (disabled || event == null) return;
        buffer.add(event.copy());
        if (buffer.size() >= MAX_BUFFER || isBoundary(event.type) || event.terminal) flush();
    }

    static boolean isBoundary(String type) {
        return "response.created".equals(type) || "response.output_item.done".equals(type);
    }

    /** Events buffered and not yet written; zero after a flush. */
    int pending() { return buffer.size(); }

    void flush() {
        if (disabled || buffer.isEmpty()) return;
        List<LlmResponseEvent> batch = new ArrayList<>(buffer);
        buffer.clear();
        try {
            target.persistIsolated(() -> target.appendStreamEvents(request, profileName, protocolName, model, batch));
        } catch (IllegalStateException stale) {
            throw stale;
        } catch (RuntimeException e) {
            disabled = true;
            logger.warn("Streamed response events could not be journaled, the final write will store them: {}", e.toString());
        }
    }
}
