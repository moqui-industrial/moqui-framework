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

import org.moqui.context.ArtifactExecutionFacade;
import org.moqui.context.ExecutionContext;
import org.moqui.context.TransactionFacade;
import org.moqui.entity.EntityList;
import org.moqui.entity.EntityValue;
import org.moqui.llm.LlmConversation;
import org.moqui.llm.LlmException;
import org.moqui.llm.LlmFinishReason;
import org.moqui.llm.LlmContentPart;
import org.moqui.llm.LlmItem;
import org.moqui.llm.LlmMessage;
import org.moqui.llm.OpenResponsesSpec;
import org.moqui.llm.LlmResponseEvent;
import org.moqui.llm.LlmProtocol.ProtocolRequest;
import org.moqui.llm.LlmProtocol.ProtocolResult;
import org.moqui.llm.LlmToolCall;
import org.moqui.llm.LlmUsage;
import org.moqui.llm.WindowPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/**
 * Durable conversation. In-memory list is always the working copy; EntityFacade writes happen inside
 * persistIsolated (same-thread suspend/begin/commit/resume — not TransactionFacade.runRequireNew).
 */
public class LlmConversationImpl implements LlmConversation {
    private static final Logger logger = LoggerFactory.getLogger(LlmConversationImpl.class);
    static final String STATUS_ACTIVE = "LlmcsActive";
    static final String STATUS_STREAMING = "LlmcsStreaming";
    static final String STATUS_YIELDED = "LlmcsYielded";
    static final String STATUS_COMPLETE = "LlmcsComplete";
    static final String STATUS_FAILED = "LlmcsFailed";
    static final String STATUS_CANCELLED = "LlmcsCancelled";
    static final int PERSIST_TIMEOUT_SECONDS = 30;

    private static final ThreadLocal<Integer> PERSIST_DEPTH = new ThreadLocal<>();

    private final ExecutionContext ec;
    private String conversationId;
    private String profileName;
    private String userId;
    private String visitId;
    private String statusId = STATUS_ACTIVE;
    private String title;
    private Timestamp createdDate;
    private String purpose;
    private String summary;
    private String searchText;
    private String hasCanvas;
    private String summaryFromLlm;
    private String canvasJson;
    private String systemText;
    private WindowPolicy windowPolicy = new WindowPolicy();
    private final List<LlmToolCall> pendingToolCalls = new ArrayList<>();
    private final Map<String, Object> attributes = new LinkedHashMap<>();
    private Timestamp lastMessageDate;
    /** Token from {@link LlmFacadeImpl#claimTurn}. Zero means this instance does not hold the claim. */
    private long turnToken = 0L;
    private final List<LlmMessage> messages = new ArrayList<>();
    /** MODEL_MESSAGES (null in old rows): the transcript is the LlmMessage rows. MODEL_ITEMS: structured items of the runs. */
    private String messageModel;
    private boolean itemModel;
    /** The completed run that holds the trajectory this conversation continues from, and how many times it moved. */
    private String headRunId;
    private Long headVersion;

    static final String MODEL_MESSAGES = "MESSAGES";
    static final String MODEL_ITEMS = "ITEMS";
    /** Moved to the item model by the upgrade; the old message rows were a copy of the trajectory and are gone. */
    static final String MODEL_ITEMS_MIGRATED = "ITEMS_MIGRATED";
    /** Moved to the item model by the upgrade; the old message rows were not a copy and are kept, never read again. */
    static final String MODEL_ITEMS_LEGACY = "ITEMS_LEGACY";
    static boolean isItemModel(String model) {
        return MODEL_ITEMS.equals(model) || MODEL_ITEMS_MIGRATED.equals(model) || MODEL_ITEMS_LEGACY.equals(model);
    }
    /** Runs of a conversation that was moved were built from a window of messages: instructions and context are in their items. */
    static boolean hasWindowContextInItems(String model) { return MODEL_ITEMS_MIGRATED.equals(model) || MODEL_ITEMS_LEGACY.equals(model); }
    static final String CONTEXT_WRAPPER = "<untrusted-context ";

    LlmConversationImpl(ExecutionContext ec, String conversationId) {
        this.ec = ec;
        this.conversationId = conversationId;
    }

    /**
     * A writer with no conversation: it carries the authenticated user so requests, responses and call logs of a
     * background run can be persisted with a null conversationId. It is never used as a client conversation.
     */
    static LlmConversationImpl detached(ExecutionContext ec) {
        LlmConversationImpl writer = new LlmConversationImpl(ec, null);
        if (ec != null && ec.getUser() != null) {
            writer.userId = ec.getUser().getUserId();
            writer.visitId = ec.getUser().getVisitId();
        }
        return writer;
    }

    /** In-memory conversation for tests and callers without EntityFacade. */
    public static LlmConversationImpl create(ExecutionContext ec, String profileName, Map<String, Object> attributes) {
        LlmConversationImpl conv = new LlmConversationImpl(ec, hasEntity(ec) ? null : newInMemoryId());
        conv.profileName = profileName;
        if (ec != null && ec.getUser() != null) {
            conv.userId = ec.getUser().getUserId();
            conv.visitId = ec.getUser().getVisitId();
        }
        if (attributes != null) {
            conv.attributes.putAll(attributes);
            Object purpose = attributes.get("purpose");
            if (purpose != null && !purpose.toString().isBlank()) conv.purpose = purpose.toString().trim();
        }
        conv.lastMessageDate = now(ec);
        conv.createdDate = conv.lastMessageDate;
        persistIsolated(ec, () -> {
            if (hasEntity(ec)) {
                EntityValue ev = ec.getEntity().makeValue("moqui.llm.LlmConversation");
                ev.setSequencedIdPrimary();
                conv.conversationId = ev.getString("conversationId");
                conv.writeHeader(ev, true);
            }
        }, conv);
        if (conv.conversationId == null) conv.conversationId = newInMemoryId();
        return conv;
    }

    public static LlmConversationImpl load(ExecutionContext ec, String conversationId, boolean checkOwner) {
        if (conversationId == null || conversationId.isBlank())
            throw new LlmException("conversationId is required");
        if (!hasEntity(ec))
            throw new LlmException("Cannot load conversation '" + conversationId + "' without EntityFacade");
        LlmConversationImpl conv = new LlmConversationImpl(ec, conversationId);
        persistIsolated(ec, () -> conv.readFromStore(), conv);
        if (conv.statusId == null)
            throw new LlmException("Conversation not found: " + conversationId,
                    null, LlmFinishReason.ERROR, 404, null, conversationId);
        if (checkOwner) checkCanView(ec, conv.userId);
        return conv;
    }

    /** Owner or ADMIN only (K22). No user on the EC is allowed only for conversations with no owner. */
    public static void checkCanView(ExecutionContext ec, String ownerUserId) {
        if (ec == null || ec.getUser() == null) return;
        String current = ec.getUser().getUserId();
        if (current == null || current.isEmpty()) {
            if (ownerUserId == null || ownerUserId.isEmpty()) return;
            throw new LlmException("User is not authorized to view LLM conversation",
                    null, LlmFinishReason.ERROR, 403, null, null);
        }
        if (current.equals(ownerUserId)) return;
        if (ec.getUser().isInGroup("ADMIN")) return;
        throw new LlmException("User is not authorized to view LLM conversation",
                null, LlmFinishReason.ERROR, 403, null, null);
    }

    /**
     * Same-thread require-new TX (ServiceCallSync.requireNewTransaction pattern).
     * Reentrant: nested calls run in the already-isolated TX. Do not use runRequireNew (that starts a thread).
     * On any failure before a successful commit, {@code conv} memory is restored so rollback cannot leave
     * in-memory status ahead of the DB (K11).
     */
    public static void persistIsolated(ExecutionContext ec, Runnable work) {
        persistIsolated(ec, work, null);
    }
    public void persistIsolated(Runnable work) {
        persistIsolated(ec, work, this);
    }
    static void persistIsolated(ExecutionContext ec, Runnable work, LlmConversationImpl conv) {
        if (work == null) return;
        Integer depth = PERSIST_DEPTH.get();
        if (depth != null && depth > 0) {
            work.run();
            return;
        }
        Snapshot snap = conv != null ? conv.snapshot() : null;
        if (ec == null || ec.getTransaction() == null) {
            try {
                work.run();
            } catch (Throwable t) {
                if (snap != null) conv.restore(snap);
                if (t instanceof RuntimeException) throw (RuntimeException) t;
                throw new LlmException("Error persisting LLM data", t);
            }
            return;
        }
        PERSIST_DEPTH.set(1);
        ArtifactExecutionFacade aefi = ec.getArtifactExecution();
        boolean alreadyDisabled = aefi != null && aefi.disableAuthz();
        TransactionFacade tf = ec.getTransaction();
        boolean suspended = false;
        try {
            if (tf.isTransactionInPlace()) suspended = tf.suspend();
            boolean began = tf.begin(PERSIST_TIMEOUT_SECONDS);
            try {
                work.run();
                if (tf.isTransactionInPlace()) tf.commit(began);
            } catch (Throwable t) {
                if (snap != null) conv.restore(snap);
                try { tf.rollback(began, "Error persisting LLM data", t); }
                catch (Throwable rb) { logger.error("Error rolling back LLM persist", rb); }
                if (t instanceof RuntimeException) throw (RuntimeException) t;
                throw new LlmException("Error persisting LLM data", t);
            }
        } finally {
            if (suspended) {
                try { tf.resume(); }
                catch (Throwable t) { logger.error("Error resuming transaction after LLM persist", t); }
            }
            if (aefi != null && !alreadyDisabled) aefi.enableAuthz();
            PERSIST_DEPTH.remove();
        }
    }

    static boolean isCancelThrowable(Throwable t) {
        while (t != null) {
            if (t instanceof InterruptedException || t instanceof CancellationException) return true;
            t = t.getCause();
        }
        return Thread.currentThread().isInterrupted();
    }


    // ---- the conversation model: messages (Chat Completions) or structured items (Open Responses)

    /** True when the trajectory is the structured items of the runs and no LlmMessage row is read or written. */
    boolean usesItemModel() { return itemModel; }
    String getHeadRunId() { return headRunId; }
    long getHeadVersion() { return headVersion != null ? headVersion : 0L; }

    /**
     * Fixes the model of a conversation from the protocol of the profile that uses it, and refuses a profile of the other
     * model afterwards: a conversation is never converted from one protocol to the other by using it.
     */
    void bindProfile(LlmFacadeImpl.ProfileState profile) {
        if (profile == null || profile.protocol == null) return;
        boolean wantItems = profile.protocol.getCapabilities().contains(org.moqui.llm.LlmProtocol.Capability.ITEM_TRAJECTORY);
        // a conversation of the item model is bound to its profile; a transcript of messages is not, as it never was
        if ((itemModel || wantItems) && profileName != null && profile.name != null && !profileName.equals(profile.name))
            throw new LlmException("Conversation " + conversationId + " belongs to profile " + profileName + ", not " + profile.name,
                    null, LlmFinishReason.ERROR, 409, profile.name, conversationId);
        if (messageModel == null) {
            boolean empty = messages.stream().noneMatch(m -> m.role == LlmMessage.Role.USER || m.role == LlmMessage.Role.ASSISTANT);
            if (!empty && wantItems)
                throw new LlmException("Conversation " + conversationId + " already has a message transcript; it cannot continue with an item protocol",
                        null, LlmFinishReason.ERROR, 409, profileName, conversationId);
            messageModel = wantItems ? MODEL_ITEMS : MODEL_MESSAGES;
            itemModel = wantItems;
            if (hasEntity(ec) && conversationId != null) persistIsolated(() -> updateHeader());
        } else if (itemModel != wantItems) {
            throw new LlmException("Conversation " + conversationId + " uses the " + (itemModel ? "item" : "message")
                    + " model; profile " + profile.name + " uses the other one",
                    null, LlmFinishReason.ERROR, 409, profile.name, conversationId);
        }
    }

    /** The run whose context is the trajectory of this conversation, owner, profile and conversation checked; null when none. */
    Map<String, Object> headRun() {
        if (!itemModel || headRunId == null || !hasEntity(ec)) return null;
        Map<String, Object> run = LlmRunStore.getRun(ec, headRunId);
        if (run == null) return null;
        boolean same = conversationId != null && conversationId.equals(String.valueOf(run.get("conversationId")))
                && userId != null && userId.equals(String.valueOf(run.get("userId")))
                && (profileName == null || profileName.equals(String.valueOf(run.get("profileName"))));
        if (!same) throw new LlmException("The head of conversation " + conversationId + " is not one of its own runs",
                null, LlmFinishReason.ERROR, 409, profileName, conversationId);
        return run;
    }

    /** The structured trajectory this conversation continues from: the items of its head run without the context of that turn. */
    List<LlmItem> headItems() {
        Map<String, Object> run = headRun();
        return run == null ? new ArrayList<>() : trajectoryOf(run);
    }

    /** The items of a run that are history: not the context of that turn, and not what an older run took from a message window. */
    private List<LlmItem> trajectoryOf(Map<String, Object> run) {
        List<LlmItem> out = new ArrayList<>();
        boolean fromWindow = hasWindowContextInItems(messageModel);
        for (LlmItem item : OpenResponsesCodec.itemsFromStored(run.get("context")))
            if (item != null && !Boolean.TRUE.equals(item.ephemeral) && !(fromWindow && isWindowLeftover(item))) out.add(item);
        return dropUnansweredCalls(out);
    }

    /**
     * A function call that no output answers (the response ended before the call was finished, or a turn stopped on its
     * length) cannot be replayed: the provider refuses a call without its output. It goes, together with the reasoning
     * items written right before it, which the provider refuses without the item they lead to.
     */
    static List<LlmItem> dropUnansweredCalls(List<LlmItem> items) {
        java.util.Set<String> answered = new java.util.HashSet<>();
        for (LlmItem item : items) if ("function_call_output".equals(item.type) && item.callId != null) answered.add(item.callId);
        List<LlmItem> out = new ArrayList<>();
        List<LlmItem> pendingReasoning = new ArrayList<>();
        for (LlmItem item : items) {
            if ("reasoning".equals(item.type)) { pendingReasoning.add(item); continue; }
            boolean unanswered = "function_call".equals(item.type) && (item.callId == null || !answered.contains(item.callId));
            if (!unanswered) out.addAll(pendingReasoning);
            pendingReasoning.clear();
            if (!unanswered) out.add(item);
        }
        // reasoning at the very end led to nothing the provider can be shown
        return out;
    }

    /** A system message or the wrapped application context that an older run took from the message window into its items. */
    static boolean isWindowLeftover(LlmItem item) {
        if (item == null || !"message".equals(item.type)) return false;
        if ("system".equals(item.role) || "developer".equals(item.role)) return true;
        if (!"user".equals(item.role) || item.content == null || item.content.isEmpty()) return false;
        org.moqui.llm.LlmContentPart first = item.content.get(0);
        return first != null && first.text != null && first.text.startsWith(CONTEXT_WRAPPER);
    }

    /** Items a viewer sees: the run in progress or waiting when there is one, else the head. */
    /** The attachment with this index among those the history shows, with its decoded content; null when there is none. */
    public Object[] attachmentAt(int index) {
        org.moqui.llm.LlmContentPart part = ItemHistory.attachmentAt(trajectoryForView(), index);
        if (part == null) return null;
        ItemHistory.Bytes bytes = ItemHistory.bytesOf(part);
        if (bytes == null) return null;
        String mediaType = part.mediaType != null ? part.mediaType : bytes.mediaType;
        return new Object[] {bytes.data, mediaType, part.filename};
    }

    private List<LlmItem> trajectoryForView() {
        try {
            Object active = attributes.get("activeLlmRunId");
            if (active != null && hasEntity(ec)) {
                Map<String, Object> run = LlmRunStore.getRun(ec, active.toString());
                if (run != null && conversationId != null && conversationId.equals(String.valueOf(run.get("conversationId"))))
                    return trajectoryOf(run);
            }
            return headItems();
        } catch (LlmException e) {
            throw e;
        } catch (RuntimeException e) {
            logger.warn("Could not read the trajectory of conversation " + conversationId + ": " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Makes the completed run the head, if the head is still the one this turn started from. Another turn that moved it
     * first (a second client, a recovery) makes this one fail instead of dropping the other turn's items.
     */
    void advanceHead(String runId, long expectedVersion) {
        if (!itemModel || !hasEntity(ec) || conversationId == null) return;
        persistIsolated(() -> {
            EntityValue ev = ec.getEntity().find("moqui.llm.LlmConversation").condition("conversationId", conversationId)
                    .forUpdate(true).useCache(false).one();
            if (ev == null) throw new LlmException("Conversation not found: " + conversationId);
            long current = ev.getLong("headVersion") != null ? ev.getLong("headVersion") : 0L;
            if (current != expectedVersion)
                throw new LlmException("Conversation " + conversationId + " moved on while this turn ran (head version "
                        + current + ", expected " + expectedVersion + ")", null, LlmFinishReason.ERROR, 409, profileName, conversationId);
            ev.set("headRunId", runId);
            ev.set("headVersion", current + 1);
            ev.update();
            headRunId = runId;
            headVersion = current + 1;
        });
    }

    /** The instructions of this conversation: the current system text, which is not part of the trajectory. */
    String instructions() { return systemText; }

    /** Application context of this turn (session facts, pins, skills); it is rebuilt for every turn, never stored as history. */
    List<LlmMessage> contextMessages() {
        List<LlmMessage> out = new ArrayList<>();
        for (LlmMessage m : messages) if (m != null && m.role == LlmMessage.Role.CONTEXT) out.add(m.copy());
        return out;
    }

    @Override public String getConversationId() { return conversationId; }
    @Override public String getProfileName() { return profileName; }
    @Override public String getUserId() { return userId; }
    @Override public String getStatus() { return statusId; }
    @Override public String getTitle() { return title; }
    public String getSummary() { return summary; }
    public boolean isSummaryFromLlm() { return "Y".equals(summaryFromLlm); }
    public Timestamp getCreatedDate() { return createdDate; }
    public Timestamp getLastMessageDate() { return lastMessageDate; }
    public String getCanvasJson() { return canvasJson; }
    public boolean hasCanvas() { return "Y".equals(hasCanvas); }

    /** Parsed canvas, or null. A new map each call. */
    public Map<String, Object> getCanvasMap() {
        if (canvasJson == null || canvasJson.isBlank()) return null;
        return LlmJson.tryToMap(canvasJson);
    }

    /** In memory only. The yield commit writes the header. */
    void setCanvasMap(Map<String, Object> canvas) {
        if (canvas == null || canvas.isEmpty()) {
            canvasJson = null;
            hasCanvas = "N";
            return;
        }
        canvasJson = LlmJson.toJson(canvas);
        hasCanvas = "Y";
    }

    void assignSummary(String summary, String searchText, boolean fromLlm) {
        this.summary = summary;
        this.searchText = searchText;
        if (fromLlm) this.summaryFromLlm = "Y";
        else if (this.summaryFromLlm == null) this.summaryFromLlm = "N";
    }

    void markSummaryAttempted() { this.summaryFromLlm = "Y"; }

    String firstUserContent() {
        for (LlmMessage m : messages) {
            if (m != null && m.role == LlmMessage.Role.USER && m.content != null && !m.content.isBlank())
                return m.content;
        }
        return null;
    }

    /** Point the latest assistant tool calls at the enriched yield payload and write that row. */
    void syncYieldedToolCalls(List<LlmToolCall> pending) {
        if (pending == null || pending.isEmpty()) return;
        Map<String, LlmToolCall> byId = new LinkedHashMap<>();
        for (LlmToolCall call : pending) {
            if (call != null && call.id != null) byId.put(call.id, call);
        }
        if (byId.isEmpty()) return;
        for (int i = messages.size() - 1; i >= 0; i--) {
            LlmMessage message = messages.get(i);
            if (message == null || message.role != LlmMessage.Role.ASSISTANT || message.toolCalls == null) continue;
            boolean changed = false;
            List<LlmToolCall> next = new ArrayList<>();
            for (LlmToolCall call : message.toolCalls) {
                LlmToolCall repl = call != null ? byId.get(call.id) : null;
                if (repl != null) {
                    next.add(repl.copy());
                    changed = true;
                } else {
                    next.add(call);
                }
            }
            if (changed) {
                message.toolCalls = next;
                writeMessage(message, false);
            }
            return;
        }
    }

    /** Removes the conversation and everything of it through the one procedure the retention also uses; 409 while a turn or run is working. */
    void deleteStored() {
        if (!hasEntity(ec) || conversationId == null) return;
        // the owner (or an administrator) was checked when the conversation was loaded; what belongs to it is removed as the
        // framework's own bookkeeping, as every other write of this class is, not as an entity delete of the person
        boolean was = ec.getArtifactExecution().disableAuthz();
        try {
            LlmConversationPurge.deleteAll(ec, conversationId);
        } finally {
            if (!was) ec.getArtifactExecution().enableAuthz();
        }
        messages.clear();
        pendingToolCalls.clear();
        statusId = null;
    }

    @Override
    public void setTitle(String title) {
        persistIsolated(() -> {
            this.title = title;
            updateHeader();
        });
    }

    @Override
    public List<LlmMessage> getHistory() { return new ArrayList<>(messages); }

    @Override
    public List<LlmMessage> buildWindow() { return buildWindow(windowPolicy); }

    @Override
    public List<LlmMessage> buildWindow(WindowPolicy p) {
        WindowPolicy policy = p != null ? p : windowPolicy;
        if (policy == null) policy = new WindowPolicy();
        LlmMessage system = null;
        List<LlmMessage> context = new ArrayList<>();
        List<LlmMessage> rest = new ArrayList<>();
        for (LlmMessage m : messages) {
            if (m == null || m.role == null) continue;
            if (m.role == LlmMessage.Role.SYSTEM) {
                if (system == null) system = m;
            } else if (m.role == LlmMessage.Role.CONTEXT) {
                if (policy.includeContext) context.add(m);
            } else {
                rest.add(m);
            }
        }
        // a window policy shortens a transcript of messages; the trajectory of the item model is replayed whole, so it is not applied
        if (!itemModel) rest = trimRest(rest, policy, charsOf(system) + charsOfAll(context));
        List<LlmMessage> window = new ArrayList<>();
        if (policy.keepSystemFirst && system != null) window.add(system);
        window.addAll(context);
        window.addAll(rest);
        return window;
    }

    @Override
    public LlmConversation append(LlmMessage message) {
        if (message == null) return this;
        persistIsolated(() -> appendInternal(message.copy()));
        return this;
    }
    @Override
    public LlmConversation appendUser(String content) {
        return append(LlmMessage.user(content));
    }
    @Override
    public LlmConversation appendAssistant(String content) {
        return append(LlmMessage.assistant(content));
    }
    @Override
    public LlmConversation appendToolResult(String toolCallId, String name, Object content) {
        String text;
        if (content == null) text = "";
        else if (content instanceof String) text = (String) content;
        else text = LlmJson.toJson(content);
        return append(LlmMessage.tool(toolCallId, name, text));
    }

    @Override
    public LlmConversation replaceSystem(String content) {
        persistIsolated(() -> replaceSystemInternal(content));
        return this;
    }

    @Override
    public LlmConversation injectContext(String source, String content) {
        LlmMessage ctx = LlmMessage.context(source, content);
        persistIsolated(() -> appendInternal(ctx));
        return this;
    }

    @Override
    public LlmConversation removeContextBySource(String source) {
        persistIsolated(() -> {
            List<LlmMessage> removed = new ArrayList<>();
            for (LlmMessage m : messages) {
                if (m.role == LlmMessage.Role.CONTEXT && sourceEquals(m, source)) removed.add(m);
            }
            for (LlmMessage m : removed) removeInternal(m);
        });
        return this;
    }

    @Override
    public LlmConversation clearContext() {
        persistIsolated(() -> {
            List<LlmMessage> removed = new ArrayList<>();
            for (LlmMessage m : messages) {
                if (m.role == LlmMessage.Role.CONTEXT) removed.add(m);
            }
            for (LlmMessage m : removed) removeInternal(m);
        });
        return this;
    }

    @Override
    public LlmConversation replaceMessage(String messageId, LlmMessage replacement) {
        if (messageId == null || replacement == null) return this;
        persistIsolated(() -> {
            for (int i = 0; i < messages.size(); i++) {
                LlmMessage cur = messages.get(i);
                if (messageId.equals(cur.messageId)) {
                    LlmMessage copy = replacement.copy();
                    copy.messageId = cur.messageId;
                    copy.ordinal = cur.ordinal;
                    if (copy.sentDate == null) copy.sentDate = cur.sentDate != null ? cur.sentDate : now(ec);
                    if (copy.role == null) copy.role = cur.role;
                    messages.set(i, copy);
                    if (copy.role == LlmMessage.Role.SYSTEM) systemText = copy.content;
                    lastMessageDate = copy.sentDate;
                    writeMessage(copy, false);
                    updateHeader();
                    return;
                }
            }
            throw new LlmException("Message not found: " + messageId);
        });
        return this;
    }

    @Override
    public LlmConversation removeMessage(String messageId) {
        if (messageId == null) return this;
        persistIsolated(() -> {
            LlmMessage found = null;
            for (LlmMessage m : messages) {
                if (messageId.equals(m.messageId)) { found = m; break; }
            }
            if (found != null) removeInternal(found);
        });
        return this;
    }

    @Override
    public LlmConversation setWindowPolicy(WindowPolicy policy) {
        persistIsolated(() -> {
            this.windowPolicy = policy != null ? policy : new WindowPolicy();
            updateHeader();
        });
        return this;
    }
    @Override
    public WindowPolicy getWindowPolicy() { return windowPolicy != null ? windowPolicy.copy() : new WindowPolicy(); }

    @Override
    public List<LlmToolCall> getPendingClientToolCalls() { return new ArrayList<>(pendingToolCalls); }

    @Override
    public Map<String, Object> getAttributes() { return new LinkedHashMap<>(attributes); }

    @Override
    public void setAttribute(String name, Object value) {
        persistIsolated(() -> {
            if (name == null) return;
            if (value == null) attributes.remove(name);
            else attributes.put(name, value);
            updateHeader();
        });
    }

    @Override
    public void cancel() {
        persistIsolated(() -> {
            if (STATUS_COMPLETE.equals(statusId) || STATUS_FAILED.equals(statusId)
                    || STATUS_CANCELLED.equals(statusId)) return;
            statusId = STATUS_CANCELLED;
            pendingToolCalls.clear();
            updateHeader();
        });
        abortInFlight();
        closeConnections();
    }

    private void closeConnections() {
        if (ec != null && ec.getLlm() instanceof LlmFacadeImpl && conversationId != null)
            ((LlmFacadeImpl) ec.getLlm()).closeSessionsOfScope("conv:" + conversationId);
    }

    private void abortInFlight() {
        if (ec == null || conversationId == null) return;
        try {
            if (ec.getLlm() instanceof LlmFacadeImpl)
                ((LlmFacadeImpl) ec.getLlm()).abortInFlight(conversationId);
        } catch (Throwable t) {
            logger.warn("Error aborting in-flight LLM stream for conversation " + conversationId, t);
        }
    }
    private void clearCancelledFlag() {
        if (ec == null || conversationId == null) return;
        try {
            if (ec.getLlm() instanceof LlmFacadeImpl)
                ((LlmFacadeImpl) ec.getLlm()).clearCancelled(conversationId);
        } catch (Throwable ignored) { }
    }

    @Override
    public void persist() {
        persistIsolated(() -> {
            updateHeader();
            for (LlmMessage m : messages) writeMessage(m, m.messageId == null);
        });
    }

    /**
     * FOR UPDATE: refuse to leave Cancelled so /cancel wins over persistSuccess/yield/complete.
     */
    void setStatusInternal(String status) {
        if (hasEntity(ec) && conversationId != null) {
            EntityValue ev = ec.getEntity().find("moqui.llm.LlmConversation")
                    .condition("conversationId", conversationId).forUpdate(true).useCache(false).one();
            String dbStatus = ev != null ? ev.getString("statusId") : statusId;
            if (STATUS_CANCELLED.equals(dbStatus) && !STATUS_CANCELLED.equals(status)) {
                statusId = STATUS_CANCELLED;
                return;
            }
            statusId = status;
            if (ev != null) writeHeader(ev, false);
            else updateHeader();
        } else {
            if (STATUS_CANCELLED.equals(statusId) && !STATUS_CANCELLED.equals(status)) return;
            this.statusId = status;
            updateHeader();
        }
    }

    /**
     * Single-flight inside the isolated TX: re-read (FOR UPDATE). Yielded is 409 on a new turn;
     * resume (Yielded to Streaming) is allowed when {@code resumeFromYielded} is true.
     * Streaming is 409 only while this JVM still has the turn claimed. A Streaming row with no
     * claim is a turn that already ended, and this call continues it.
     */
    void beginTurnStreaming() { beginTurnStreaming(false); }
    void beginTurnStreaming(boolean resumeFromYielded) {
        clearCancelledFlag();
        if (hasEntity(ec) && conversationId != null) {
            EntityValue ev = ec.getEntity().find("moqui.llm.LlmConversation")
                    .condition("conversationId", conversationId).forUpdate(true).useCache(false).one();
            if (ev == null) throw new LlmException("Conversation not found: " + conversationId);
            String dbStatus = ev.getString("statusId");
            if (STATUS_YIELDED.equals(dbStatus) && !resumeFromYielded) {
                throw new LlmException("Conversation is " + dbStatus + " (single-flight)",
                        null, LlmFinishReason.ERROR, 409, profileName, conversationId);
            }
            // Streaming with a live claim is the real single-flight. A Streaming row and no claim
            // means the turn that set it already left this JVM (request ended, or the process restarted).
            if (STATUS_STREAMING.equals(dbStatus) && turnClaimed()) {
                throw new LlmException("Conversation is " + dbStatus + " (single-flight)",
                        null, LlmFinishReason.ERROR, 409, profileName, conversationId);
            }
            if (STATUS_STREAMING.equals(dbStatus))
                logger.warn("Conversation " + conversationId
                        + " was LlmcsStreaming with no in-flight turn; continuing");
            claimTurn();
            statusId = STATUS_STREAMING;
            writeHeader(ev, false);
        } else {
            if (STATUS_STREAMING.equals(statusId)
                    || (STATUS_YIELDED.equals(statusId) && !resumeFromYielded)) {
                throw new LlmException("Conversation is " + statusId + " (single-flight)",
                        null, LlmFinishReason.ERROR, 409, profileName, conversationId);
            }
            statusId = STATUS_STREAMING;
        }
    }

    /** Package-visible for the gateway's early 409, before it rewrites context messages. */
    static boolean turnInFlight(ExecutionContext ec, String conversationId) {
        if (ec == null || conversationId == null || !(ec.getLlm() instanceof LlmFacadeImpl)) return false;
        return ((LlmFacadeImpl) ec.getLlm()).turnClaimed(conversationId);
    }
    private boolean turnClaimed() {
        // No facade means we cannot tell a live turn from a leftover row, so keep the strict 409.
        if (ec == null || !(ec.getLlm() instanceof LlmFacadeImpl)) return true;
        return ((LlmFacadeImpl) ec.getLlm()).turnClaimed(conversationId);
    }
    private void claimTurn() {
        if (ec == null || !(ec.getLlm() instanceof LlmFacadeImpl)) return;
        long token = ((LlmFacadeImpl) ec.getLlm()).claimTurn(conversationId);
        if (token > 0L) turnToken = token;
    }
    void releaseTurnClaim() {
        long token = turnToken;
        turnToken = 0L;
        if (token <= 0L || ec == null || !(ec.getLlm() instanceof LlmFacadeImpl)) return;
        ((LlmFacadeImpl) ec.getLlm()).releaseTurn(conversationId, token);
    }

    void setPendingToolCallsInternal(List<LlmToolCall> calls) {
        pendingToolCalls.clear();
        if (calls != null) pendingToolCalls.addAll(calls);
        updateHeader();
    }

    /** Re-read DB (source of truth) and only then Failed/Cancelled if still Streaming. */
    void repairTerminalIfStreaming(boolean cancelled) {
        persistIsolated(() -> {
            String dbStatus = statusId;
            EntityValue ev = null;
            if (hasEntity(ec) && conversationId != null) {
                ev = ec.getEntity().find("moqui.llm.LlmConversation")
                        .condition("conversationId", conversationId).forUpdate(true).useCache(false).one();
                if (ev != null) dbStatus = ev.getString("statusId");
            }
            if (STATUS_STREAMING.equals(dbStatus)) {
                statusId = cancelled ? STATUS_CANCELLED : STATUS_FAILED;
                pendingToolCalls.clear();
                if (ev != null) writeHeader(ev, false);
                else updateHeader();
            } else if (ev != null && dbStatus != null) {
                statusId = dbStatus;
            }
        });
    }

    void replaceSystemInternal(String content) {
        LlmMessage existing = findSystem();
        if (existing != null) {
            existing.content = content;
            existing.sentDate = now(ec);
            systemText = content;
            lastMessageDate = existing.sentDate;
            writeMessage(existing, false);
            updateHeader();
        } else {
            LlmMessage sys = LlmMessage.system(content);
            appendInternal(sys);
        }
    }

    void appendInternal(LlmMessage message) {
        if (message.role == LlmMessage.Role.SYSTEM) {
            LlmMessage existing = findSystem();
            if (existing != null) {
                existing.content = message.content;
                existing.name = message.name;
                existing.metadata = message.metadata;
                existing.sentDate = now(ec);
                systemText = message.content;
                lastMessageDate = existing.sentDate;
                writeMessage(existing, false);
                updateHeader();
                return;
            }
        }
        message.ordinal = nextOrdinal();
        message.sentDate = message.sentDate != null ? message.sentDate : now(ec);
        if (message.messageId == null && !hasEntity(ec)) message.messageId = newInMemoryId();
        messages.add(message);
        if (message.role == LlmMessage.Role.SYSTEM) systemText = message.content;
        if (message.role == LlmMessage.Role.USER) ConversationSummary.noteFirstUser(this, message.content);
        lastMessageDate = message.sentDate;
        writeMessage(message, true);
        updateHeader();
    }

    String writeCallLog(String profileName, String protocolName, String model, boolean logContent,
            List<LlmMessage> window, ProtocolResult result, long durationMs, int iteration, boolean wasError) {
        if (!hasEntity(ec)) return null;
        EntityValue ev = ec.getEntity().makeValue("moqui.llm.LlmCallLog");
        ev.setSequencedIdPrimary();
        String callId = ev.getString("callId");
        ev.set("conversationId", conversationId);
        ev.set("profileName", profileName);
        ev.set("model", model);
        ev.set("protocolName", protocolName);
        ev.set("userId", userId);
        ev.set("visitId", visitId);
        if (result != null) {
            ev.set("httpStatus", result.httpStatus);
            ev.set("finishReason", result.finishReason != null ? result.finishReason.name() : null);
            if (result.usage != null) {
                LlmUsage u = result.usage;
                ev.set("promptTokens", u.promptTokens);
                ev.set("completionTokens", u.completionTokens);
                ev.set("totalTokens", u.totalTokens);
            }
            ev.set("errorMessage", result.errorMessage);
            if (logContent) ev.set("responseJson", result.rawJson);
        }
        ev.set("durationMs", durationMs);
        ev.set("iteration", iteration);
        ev.set("wasError", wasError ? "Y" : "N");
        ev.set("startDate", now(ec));
        if (logContent && window != null) {
            List<Map<String, Object>> win = new ArrayList<>();
            for (LlmMessage m : window) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("role", m.role != null ? m.role.name() : null);
                row.put("content", m.content);
                row.put("name", m.name);
                row.put("toolCallId", m.toolCallId);
                win.add(row);
            }
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("model", model);
            req.put("messages", win);
            ev.set("requestJson", LlmJson.toJson(req));
        }
        ev.create();
        if (result != null) result.localResponseId = writeOpenResponsesResource(callId, profileName, model,
                protocolName, result, logContent);
        return callId;
    }

    String writeOpenResponsesRequest(String profileName, String protocolName, String model, ProtocolRequest request,
            Map<String, Object> requestBody, String bodyJson) {
        if (!hasEntity(ec) || request == null || requestBody == null) return null;
        LlmRunStore.assertFence(ec, request.runId, request.runFence);
        EntityValue rv = ec.getEntity().makeValue("moqui.llm.LlmRequest");
        rv.setSequencedIdPrimary();
        String llmRequestId = rv.getString("llmRequestId");
        rv.set("conversationId", conversationId);
        rv.set("runId", request.runId);
        rv.set("ownerUserId", userId);
        rv.set("profileName", profileName);
        rv.set("model", model);
        rv.set("protocolName", protocolName);
        rv.set("transportEnumId", transportEnumId(request.transport, protocolName));
        rv.set("operation", request.operation != null ? request.operation : "create_response");
        rv.set("specVersion", request.specVersion);
        rv.set("schemaSha256", OpenResponsesSpec.OPENAPI_SHA256);
        rv.set("previousProviderResponseId", request.previousResponseId);
        rv.set("inputShape", inputShape(requestBody));
        rv.set("localStatusEnumId", "LlmReqSending");
        // the complete body as sent (encrypted column): opaque and large fields included, so the record is exact
        rv.set("requestPayloadJson", bodyJson);
        Map<String, Object> sentMeta = new LinkedHashMap<>();
        sentMeta.put("sentBodySha256", sha256Hex(bodyJson));
        sentMeta.put("sentBodyBytes", bodyJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        rv.set("metadataJson", LlmJson.toJson(sentMeta));
        rv.set("requestOptionsJson", request.responseOptions != null ? LlmJson.toExactJson(request.responseOptions.asMap()) : null);
        rv.set("effectiveOptionsJson", LlmJson.toExactJson(OpenResponsesCodec.effectiveRequestOptions(requestBody).asMap()));
        rv.set("toolsJson", request.tools != null ? LlmJson.toJson(requestBody.get("tools")) : null);
        Timestamp now = now(ec);
        rv.set("createdDate", now);
        rv.set("sendStartedDate", now);
        rv.create();
        List<LlmItem> items = request.inputItems != null ? request.inputItems : OpenResponsesCodec.requestItems(request);
        writeItems(llmRequestId, null, items, 1, profileName, request.runId);
        request.localRequestId = llmRequestId;
        return llmRequestId;
    }

    /**
     * Writes the response row while the stream is still open (status in_progress) and appends events to it, so a crash
     * in the middle of a stream leaves the events received so far. The final result completes the same row.
     */
    void appendStreamEvents(ProtocolRequest request, String profileName, String protocolName, String model,
            List<LlmResponseEvent> batch) {
        if (!hasEntity(ec) || request == null || batch == null || batch.isEmpty()) return;
        LlmRunStore.assertFence(ec, request.runId, request.runFence);
        String providerResponseId = null;
        for (LlmResponseEvent event : batch) {
            if (event.responseId != null) providerResponseId = event.responseId;
            else if (event.payload != null && event.payload.get("response") instanceof Map)
                providerResponseId = LlmRetryClassifier.str(((Map<?, ?>) event.payload.get("response")).get("id"));
            if (providerResponseId != null) break;
        }
        EntityValue rv = request.localStreamResponseId != null ? ec.getEntity().find("moqui.llm.LlmResponse")
                .condition("llmResponseId", request.localStreamResponseId).disableAuthz().one() : null;
        if (rv == null) {
            rv = ec.getEntity().makeValue("moqui.llm.LlmResponse");
            rv.setSequencedIdPrimary();
            rv.set("llmRequestId", request.localRequestId);
            rv.set("conversationId", conversationId);
            rv.set("runId", request.runId);
            rv.set("ownerUserId", userId);
            rv.set("profileName", profileName);
            rv.set("model", model);
            rv.set("specVersion", request.specVersion);
            rv.set("status", "in_progress");
            rv.set("dataVersion", 2);
            rv.set("transportEnumId", transportEnumId(org.moqui.llm.LlmTransport.SSE, protocolName));
            rv.set("createdDate", now(ec));
            if (providerResponseId != null) rv.set("providerResponseId", providerResponseId);
            rv.create();
            request.localStreamResponseId = rv.getString("llmResponseId");
        } else if (providerResponseId != null && rv.get("providerResponseId") == null) {
            rv.set("providerResponseId", providerResponseId);
            rv.update();
        }
        writeResponseEvents(request.localStreamResponseId, providerResponseId, batch, 0, request.streamEventsPersisted);
        request.streamEventsPersisted += batch.size();
    }

    /** A stream that ended without a usable response leaves its row marked interrupted, with the reason. */
    void markStreamInterrupted(ProtocolRequest request, Object error) {
        if (!hasEntity(ec) || request == null || request.localStreamResponseId == null) return;
        EntityValue rv = ec.getEntity().find("moqui.llm.LlmResponse")
                .condition("llmResponseId", request.localStreamResponseId).disableAuthz().one();
        if (rv == null || !"in_progress".equals(rv.getString("status"))) return;
        rv.set("status", "interrupted");
        rv.set("errorJson", error != null ? LlmJson.toExactJson(error) : null);
        rv.update();
    }

    void updateOpenResponsesRequestStatus(String llmRequestId, String statusId, Integer httpStatus, Object error) {
        if (!hasEntity(ec) || llmRequestId == null) return;
        EntityValue rv = ec.getEntity().find("moqui.llm.LlmRequest").condition("llmRequestId", llmRequestId)
                .disableAuthz().one();
        if (rv == null) return;
        rv.set("localStatusEnumId", statusId);
        rv.set("httpStatus", httpStatus);
        rv.set("sendFinishedDate", now(ec));
        rv.set("errorJson", error != null ? LlmJson.toExactJson(error) : null);
        rv.update();
    }

    private String writeOpenResponsesResource(String callId, String profileName, String model, String protocolName,
            ProtocolResult result, boolean logContent) {
        if (result == null || (result.responseId == null && (result.outputItems == null || result.outputItems.isEmpty())
                && result.responsePayload == null)) return null;
        LlmRunStore.assertFence(ec, result.runId, result.runFence);
        EntityValue rv = result.localStreamResponseId != null ? ec.getEntity().find("moqui.llm.LlmResponse")
                .condition("llmResponseId", result.localStreamResponseId).disableAuthz().one() : null;
        boolean existing = rv != null;
        if (!existing) {
            rv = ec.getEntity().makeValue("moqui.llm.LlmResponse");
            rv.setSequencedIdPrimary();
        }
        String llmResponseId = rv.getString("llmResponseId");
        rv.set("providerResponseId", result.responseId);
        rv.set("dataVersion", 2);
        rv.set("llmRequestId", result.localRequestId);
        rv.set("conversationId", conversationId);
        rv.set("runId", result.runId);
        rv.set("ownerUserId", userId);
        rv.set("callId", callId);
        rv.set("profileName", profileName);
        rv.set("model", result.model != null ? result.model : model);
        rv.set("specVersion", result.specVersion);
        rv.set("objectType", result.responsePayload != null && result.responsePayload.get("object") != null
                ? result.responsePayload.get("object").toString() : null);
        rv.set("status", result.status);
        rv.set("previousProviderResponseId", result.previousResponseId);
        rv.set("transportEnumId", transportEnumId(result.transport, protocolName));
        rv.set("finishReason", result.finishReason != null ? result.finishReason.name() : null);
        rv.set("httpStatus", result.httpStatus);
        if (result.usage != null) {
            rv.set("promptTokens", result.usage.promptTokens);
            rv.set("completionTokens", result.usage.completionTokens);
            rv.set("totalTokens", result.usage.totalTokens);
            rv.set("cachedInputTokens", result.usage.cachedInputTokens);
            rv.set("reasoningOutputTokens", result.usage.reasoningOutputTokens);
        }
        // what the provider echoed, null and default values included; what was sent is on the request
        rv.set("optionsJson", result.responseOptions != null ? LlmJson.toExactJson(result.responseOptions.asMap()) : null);
        if (result.responsePayload != null) {
            Object metadata = result.responsePayload.get("metadata");
            Object error = result.responsePayload.get("error");
            Object incomplete = result.responsePayload.get("incomplete_details");
            rv.set("metadataJson", metadata != null ? LlmJson.toExactJson(metadata) : null);
            rv.set("errorJson", error != null ? LlmJson.toExactJson(error) : null);
            rv.set("incompleteDetailsJson", incomplete != null ? LlmJson.toExactJson(incomplete) : null);
            rv.set("payloadJson", LlmJson.toExactJson(result.responsePayload));
            if (result.responsePayload.containsKey("usage"))
                rv.set("usageJson", LlmJson.toExactJson(result.responsePayload.get("usage")));
        }
        Timestamp now = now(ec);
        rv.set("createdDate", result.createdAt != null ? result.createdAt : now);
        if ("completed".equalsIgnoreCase(result.status) || "failed".equalsIgnoreCase(result.status)
                || "incomplete".equalsIgnoreCase(result.status))
            rv.set("completedDate", result.completedAt != null ? result.completedAt : now);
        if (existing) rv.update(); else rv.create();
        if (result.outputItems != null) writeItems(null, llmResponseId, result.outputItems, 1, profileName, result.runId);
        if (result.events != null) writeResponseEvents(llmResponseId, result.responseId, result.events,
                existing ? result.streamEventsPersisted : 0);
        if (result.localRequestId != null)
            updateOpenResponsesRequestStatus(result.localRequestId, "LlmReqAck", result.httpStatus, null);
        return llmResponseId;
    }

    private int writeItems(String llmRequestId, String llmResponseId, List<LlmItem> items, int itemSeq, String profileName,
            String runId) {
        if ((llmRequestId == null) == (llmResponseId == null))
            throw new IllegalArgumentException("An item belongs to exactly one request or one response");
        for (LlmItem item : items) {
            if (item == null) continue;
            String[] source = llmRequestId != null ? resolveSource(item, profileName) : new String[] {null, null};
            EntityValue iv = ec.getEntity().makeValue("moqui.llm.LlmItem");
            iv.setSequencedIdPrimary();
            String llmItemId = iv.getString("llmItemId");
            iv.set("llmRequestId", llmRequestId);
            iv.set("llmResponseId", llmResponseId);
            iv.set("sourceLlmItemId", source[0]);
            iv.set("sourceStatus", source[1]);
            iv.set("sequenceNum", itemSeq);
            iv.set("providerItemId", item.providerItemId);
            iv.set("itemType", item.type);
            iv.set("status", item.status);
            iv.set("role", item.role);
            iv.set("phase", item.phase);
            iv.set("providerCallId", item.callId);
            iv.set("toolName", item.name);
            iv.set("argumentsJson", item.arguments);
            iv.set("outputJson", item.output != null ? LlmJson.toJson(item.output) : null);
            iv.set("outputShape", outputShape(item.output));
            iv.set("encryptedContent", item.encryptedContent);
            iv.set("createdBy", item.createdBy);
            iv.set("referenceId", item.referenceId);
            iv.set("payloadJson", item.payload != null ? LlmJson.toExactJson(item.payload) : null);
            iv.create();
            // each kind of content is numbered from 1 on its own, so no kind depends on how many another has
            boolean responseOwned = llmResponseId != null;
            writeContent(llmItemId, item, item.content, "content", responseOwned, llmResponseId, runId);
            writeContent(llmItemId, item, item.summary, "summary", responseOwned, llmResponseId, runId);
            writeContent(llmItemId, item, OpenResponsesCodec.outputParts(item.output), "output", responseOwned, llmResponseId, runId);
            itemSeq++;
        }
        return itemSeq;
    }

    /**
     * The earlier response item this request item replays. A source the caller names must belong to the same owner; the
     * refusal says nothing about whether the row exists. Without one, an item carrying a provider item id is matched to
     * the latest response item with that id of the same owner and profile; provider ids are not unique across owners
     * or endpoints, so nothing else is matched.
     */
    private String[] resolveSource(LlmItem item, String profileName) {
        if (item.sourceItemId != null && !item.sourceItemId.isBlank()) {
            EntityValue source = ec.getEntity().find("moqui.llm.LlmItem").condition("llmItemId", item.sourceItemId)
                    .disableAuthz().useCache(false).one();
            if (source == null || !ownedByThisUser(source))
                throw new IllegalArgumentException("The source item is not available");
            return new String[] {item.sourceItemId, "named"};
        }
        if (item.providerItemId == null || item.providerItemId.isBlank() || userId == null) return new String[] {null, null};
        List<String> compatible = new ArrayList<>();
        for (EntityValue candidate : ec.getEntity().find("moqui.llm.LlmItem").condition("providerItemId", item.providerItemId)
                .condition("llmResponseId", org.moqui.entity.EntityCondition.NOT_EQUAL, null)
                .disableAuthz().useCache(false).limit(50).list()) {
            // provider ids are not unique: the owner, the profile and the kind of item all have to agree
            if (item.type != null && !item.type.equals(candidate.get("itemType"))) continue;
            EntityValue response = ec.getEntity().find("moqui.llm.LlmResponse")
                    .condition("llmResponseId", candidate.get("llmResponseId")).disableAuthz().useCache(false).one();
            if (response == null || !userId.equals(response.get("ownerUserId"))) continue;
            if (profileName != null && !profileName.equals(response.get("profileName"))) continue;
            compatible.add(candidate.getString("llmItemId"));
        }
        if (compatible.size() == 1) return new String[] {compatible.get(0), "matched"};
        // none matched, or several could be it: nothing is picked, and the item says which
        return new String[] {null, compatible.isEmpty() ? "unmatched" : "unresolved"};
    }

    private boolean ownedByThisUser(EntityValue item) {
        String owner = null;
        if (item.get("llmResponseId") != null) {
            EntityValue response = ec.getEntity().find("moqui.llm.LlmResponse")
                    .condition("llmResponseId", item.get("llmResponseId")).disableAuthz().useCache(false).one();
            owner = response != null ? (String) response.get("ownerUserId") : null;
        } else if (item.get("llmRequestId") != null) {
            EntityValue request = ec.getEntity().find("moqui.llm.LlmRequest")
                    .condition("llmRequestId", item.get("llmRequestId")).disableAuthz().useCache(false).one();
            owner = request != null ? (String) request.get("ownerUserId") : null;
        }
        return owner != null && owner.equals(userId);
    }

    private void writeContent(String llmItemId, LlmItem ownerItem, List<LlmContentPart> content,
            String contentKind, boolean responseOwned, String llmResponseId, String runId) {
        if (content == null) return;
        int contentSeq = 1;
        for (LlmContentPart part : content) {
            if (part == null) continue;
            EntityValue cv = ec.getEntity().makeValue("moqui.llm.LlmContent");
            cv.setSequencedIdPrimary();
            cv.set("llmItemId", llmItemId);
            cv.set("sequenceNum", contentSeq);
            cv.set("contentType", part.type);
            cv.set("contentKind", contentKind);
            cv.set("purposeEnumId", contentPurposeEnumId(ownerItem, contentKind, responseOwned));
            cv.set("textContent", part.text);
            cv.set("refusal", part.refusal);
            cv.set("imageUrl", part.imageUrl);
            cv.set("fileData", part.fileData);
            cv.set("fileUrl", part.fileUrl);
            cv.set("videoUrl", part.videoUrl);
            cv.set("filename", part.filename);
            cv.set("detail", part.detail);
            cv.set("contentLocation", part.contentLocation);
            cv.set("mediaType", part.mediaType);
            cv.set("contentLength", part.contentLength != null ? part.contentLength.intValue() : null);
            cv.set("contentSha256", part.contentSha256);
            cv.set("annotationsJson", part.annotations != null ? LlmJson.toJson(part.annotations) : null);
            cv.set("logprobsJson", part.logprobs != null ? LlmJson.toJson(part.logprobs) : null);
            cv.set("payloadJson", part.payload != null ? LlmJson.toExactJson(part.payload) : null);
            cv.create();
            projectContent(cv.getString("llmContentId"), ownerItem, contentKind, part, llmResponseId, runId);
            contentSeq++;
        }
    }

    /**
     * The searchable copy of readable text. Only what the application and the model said in plain text is copied:
     * messages and tool outputs, not reasoning, summaries, compaction, files, images or refusals' payloads. The exact,
     * encrypted record stays in LlmContent; the copy is plain text in LlmContextProjection, scoped to its owner,
     * conversation and run, written in the same transaction as the content, and removed with it by the retention
     * service. It can be switched off with the system property moqui.llm.projection=false or the environment variable
     * llm_context_projection=false.
     */
    private void projectContent(String llmContentId, LlmItem item, String contentKind, LlmContentPart part,
            String llmResponseId, String runId) {
        if (userId == null || part == null || part.text == null || part.text.isBlank() || llmContentId == null) return;
        if (!projectionEnabled() || !isProjectable(item, contentKind, part)) return;
        EntityValue pv = ec.getEntity().makeValue("moqui.llm.LlmContextProjection");
        pv.setSequencedIdPrimary();
        pv.set("userId", userId);
        pv.set("conversationId", conversationId);
        pv.set("runId", runId);
        pv.set("llmResponseId", llmResponseId);
        pv.set("sourceType", CONTENT_SOURCE);
        pv.set("sourceId", llmContentId);
        pv.set("textContent", part.text);
        pv.set("createdDate", now(ec));
        pv.create();
    }

    /** sourceType of the projection rows made from content; the sourceId is the LlmContent id. */
    static final String CONTENT_SOURCE = "content";

    static boolean isProjectable(LlmItem item, String contentKind, LlmContentPart part) {
        if (item == null || "summary".equals(contentKind)) return false;
        if (!"message".equals(item.type) && !"function_call_output".equals(item.type)) return false;
        return part.type == null || "input_text".equals(part.type) || "output_text".equals(part.type) || "text".equals(part.type);
    }

    static boolean projectionEnabled() {
        String value = System.getProperty("moqui.llm.projection");
        if (value == null) value = System.getenv("llm_context_projection");
        return value == null || !"false".equalsIgnoreCase(value.trim());
    }

    private static String inputShape(Map<String, Object> requestBody) {
        if (requestBody == null || !requestBody.containsKey("input")) return "absent";
        Object input = requestBody.get("input");
        if (input == null) return "null";
        if (input instanceof String) return "string";
        if (input instanceof List) return "array";
        return input.getClass().getSimpleName();
    }

    private static String outputShape(Object output) {
        if (output == null) return "null";
        if (output instanceof String) return "string";
        if (output instanceof List) return "array";
        if (output instanceof Map) return "object";
        return output.getClass().getSimpleName();
    }

    /**
     * Who produced the content, from what it is and not from where the row sits: the model's messages, reasoning,
     * summaries and compaction are Assistant also when replayed in a request; what the application supplied (user,
     * system and developer messages, tool output arrays) is User.
     */
    private static String contentPurposeEnumId(LlmItem item, String contentKind, boolean responseOwned) {
        if ("summary".equals(contentKind)) return "LlmCpAssistant";
        if (item == null) return responseOwned ? "LlmCpAssistant" : "LlmCpUser";
        if ("function_call_output".equals(item.type)) return "LlmCpUser";
        if ("reasoning".equals(item.type) || "compaction".equals(item.type) || "assistant".equals(item.role))
            return "LlmCpAssistant";
        if (item.role != null) return "LlmCpUser";
        return responseOwned ? "LlmCpAssistant" : "LlmCpUser";
    }

    /** Writes the events after the first {@code alreadyWritten}; the local sequence continues where the earlier batch ended. */
    private void writeResponseEvents(String llmResponseId, String providerResponseId, List<LlmResponseEvent> events,
            int alreadyWritten) {
        writeResponseEvents(llmResponseId, providerResponseId, events, alreadyWritten, alreadyWritten);
    }

    private void writeResponseEvents(String llmResponseId, String providerResponseId, List<LlmResponseEvent> events,
            int alreadyWritten, int sequenceBase) {
        if (events == null) return;
        int sequenceNum = sequenceBase + 1;
        int position = 0;
        for (LlmResponseEvent event : events) {
            if (position++ < alreadyWritten) continue;
            if (event == null || event.type == null || event.type.isBlank()) continue;
            EntityValue ev = ec.getEntity().makeValue("moqui.llm.LlmResponseEvent");
            ev.setSequencedIdPrimary();
            ev.set("llmResponseId", llmResponseId);
            ev.set("providerResponseId", event.responseId != null ? event.responseId : providerResponseId);
            ev.set("providerItemId", event.itemId);
            ev.set("eventType", event.type);
            int localSeq = sequenceNum++;
            ev.set("sequenceNum", localSeq);
            ev.set("providerSequenceNum", event.sequenceNumber);
            ev.set("localSequenceNum", localSeq);
            ev.set("outputIndex", event.outputIndex);
            ev.set("contentIndex", event.contentIndex);
            ev.set("terminal", event.terminal ? "Y" : "N");
            String payloadJson = event.payload != null ? LlmJson.toExactJson(event.payload) : "{}";
            ev.set("payloadJson", payloadJson);
            ev.set("payloadSha256", sha256Hex(payloadJson));
            ev.set("eventDate", now(ec));
            ev.create();
        }
    }

    private static String transportEnumId(org.moqui.llm.LlmTransport transport, String protocolName) {
        if (transport == org.moqui.llm.LlmTransport.SSE) return "LlmTrSse";
        if (transport == org.moqui.llm.LlmTransport.WEBSOCKET) return "LlmTrWebSocket";
        return protocolName != null && protocolName.toLowerCase().contains("responses") ? "LlmTrHttp" : null;
    }

    boolean isStreamingOrYielded() {
        return STATUS_STREAMING.equals(statusId) || STATUS_YIELDED.equals(statusId);
    }

    private void removeInternal(LlmMessage found) {
        messages.remove(found);
        if (found.role == LlmMessage.Role.SYSTEM) systemText = findSystem() != null ? findSystem().content : null;
        if (hasEntity(ec) && found.messageId != null && !itemModel) LlmMessageStore.remove(ec, found.messageId);
        updateHeader();
    }

    private LlmMessage findSystem() {
        for (LlmMessage m : messages) {
            if (m.role == LlmMessage.Role.SYSTEM) return m;
        }
        return null;
    }

    private int nextOrdinal() {
        int next = 0;
        for (LlmMessage m : messages) if (m.ordinal >= next) next = m.ordinal + 1;
        if (hasEntity(ec) && conversationId != null && !itemModel) next = Math.max(next, LlmMessageStore.nextOrdinal(ec, conversationId));
        return next;
    }

    private Snapshot snapshot() {
        Snapshot s = new Snapshot();
        s.conversationId = conversationId;
        s.profileName = profileName;
        s.userId = userId;
        s.visitId = visitId;
        s.statusId = statusId;
        s.title = title;
        s.createdDate = createdDate;
        s.purpose = purpose;
        s.summary = summary;
        s.searchText = searchText;
        s.hasCanvas = hasCanvas;
        s.summaryFromLlm = summaryFromLlm;
        s.canvasJson = canvasJson;
        s.systemText = systemText;
        s.windowPolicy = windowPolicy != null ? windowPolicy.copy() : new WindowPolicy();
        s.pendingToolCalls.addAll(pendingToolCalls);
        s.attributes.putAll(attributes);
        s.lastMessageDate = lastMessageDate;
        s.messageModel = messageModel; s.headRunId = headRunId; s.headVersion = headVersion;
        for (LlmMessage m : messages) s.messages.add(m != null ? m.copy() : null);
        return s;
    }

    private void restore(Snapshot s) {
        conversationId = s.conversationId;
        profileName = s.profileName;
        userId = s.userId;
        visitId = s.visitId;
        statusId = s.statusId;
        title = s.title;
        createdDate = s.createdDate;
        purpose = s.purpose;
        summary = s.summary;
        searchText = s.searchText;
        hasCanvas = s.hasCanvas;
        summaryFromLlm = s.summaryFromLlm;
        canvasJson = s.canvasJson;
        systemText = s.systemText;
        windowPolicy = s.windowPolicy != null ? s.windowPolicy : new WindowPolicy();
        pendingToolCalls.clear();
        pendingToolCalls.addAll(s.pendingToolCalls);
        attributes.clear();
        attributes.putAll(s.attributes);
        lastMessageDate = s.lastMessageDate;
        messageModel = s.messageModel; itemModel = isItemModel(messageModel); headRunId = s.headRunId; headVersion = s.headVersion;
        messages.clear();
        messages.addAll(s.messages);
    }

    private static final class Snapshot {
        String conversationId, profileName, userId, visitId, statusId, title, systemText;
        String purpose, summary, searchText, hasCanvas, summaryFromLlm, canvasJson;
        Timestamp createdDate;
        WindowPolicy windowPolicy;
        final List<LlmToolCall> pendingToolCalls = new ArrayList<>();
        final Map<String, Object> attributes = new LinkedHashMap<>();
        Timestamp lastMessageDate;
        String messageModel, headRunId;
        Long headVersion;
        final List<LlmMessage> messages = new ArrayList<>();
    }

    private void readFromStore() {
        EntityValue header = ec.getEntity().find("moqui.llm.LlmConversation")
                .condition("conversationId", conversationId).one();
        if (header == null) {
            statusId = null;
            return;
        }
        profileName = header.getString("profileName");
        userId = header.getString("userId");
        visitId = header.getString("visitId");
        statusId = header.getString("statusId");
        title = header.getString("title");
        createdDate = header.getTimestamp("createdDate");
        purpose = header.getString("purpose");
        summary = header.getString("summary");
        searchText = header.getString("searchText");
        hasCanvas = header.getString("hasCanvas");
        summaryFromLlm = header.getString("summaryFromLlm");
        canvasJson = header.getString("canvasJson");
        systemText = header.getString("systemText");
        windowPolicy = WindowPolicy.fromMap(LlmJson.toMap(header.getString("windowPolicyJson")));
        pendingToolCalls.clear();
        pendingToolCalls.addAll(LlmMessageStore.parseToolCalls(header.getString("pendingToolCallsJson")));
        attributes.clear();
        Map<String, Object> attrs = LlmJson.toMap(header.getString("attributesJson"));
        if (attrs != null) attributes.putAll(attrs);
        boolean migratedCanvas = false;
        if ((canvasJson == null || canvasJson.isBlank()) && attrs != null
                && attrs.get(WriteUiTool.ATTR_LAST_WRITE_UI) instanceof Map) {
            canvasJson = LlmJson.toJson(attrs.get(WriteUiTool.ATTR_LAST_WRITE_UI));
            hasCanvas = "Y";
            attributes.remove(WriteUiTool.ATTR_LAST_WRITE_UI);
            migratedCanvas = true;
        }
        lastMessageDate = header.getTimestamp("lastMessageDate");
        messageModel = header.getString("messageModel");
        headRunId = header.getString("headRunId");
        headVersion = header.getLong("headVersion");
        itemModel = isItemModel(messageModel);
        messages.clear();
        if (itemModel) {
            if (systemText != null && !systemText.isBlank()) messages.add(LlmMessage.system(systemText));
            messages.addAll(ItemHistory.toMessages(trajectoryForView()));
            for (int i = 0; i < messages.size(); i++) messages.get(i).ordinal = i;
        } else {
            messages.addAll(LlmMessageStore.readAll(ec, conversationId));
        }
        if (migratedCanvas) updateHeader();
    }

    private void writeHeader(EntityValue ev, boolean create) {
        ev.set("profileName", profileName);
        ev.set("userId", userId);
        ev.set("visitId", visitId);
        ev.set("statusId", statusId != null ? statusId : STATUS_ACTIVE);
        ev.set("title", title);
        ev.set("createdDate", createdDate);
        ev.set("purpose", purpose);
        ev.set("summary", summary);
        ev.set("searchText", searchText);
        ev.set("hasCanvas", hasCanvas);
        ev.set("summaryFromLlm", summaryFromLlm);
        ev.set("canvasJson", canvasJson);
        ev.set("systemText", systemText);
        ev.set("windowPolicyJson", LlmJson.toJson(windowPolicy.toMap()));
        ev.set("pendingToolCallsJson", pendingToolCalls.isEmpty() ? null : LlmJson.toJson(pendingToolCalls));
        ev.set("attributesJson", attributes.isEmpty() ? null : LlmJson.toJson(attributes));
        ev.set("lastMessageDate", lastMessageDate);
        ev.set("messageCount", visibleMessageCount(messages));
        ev.set("messageModel", messageModel);
        if (create) {
            ev.set("headRunId", headRunId);
            ev.set("headVersion", headVersion);
            ev.create();
        }
        else ev.update();
    }

    /** Rows the chat shows. System and context stay in the transcript but are not counted in the list. */
    private static int visibleMessageCount(List<LlmMessage> messages) {
        int count = 0;
        if (messages == null) return 0;
        for (LlmMessage message : messages) {
            if (message == null || message.role == null) continue;
            if (message.role == LlmMessage.Role.SYSTEM || message.role == LlmMessage.Role.CONTEXT) continue;
            count++;
        }
        return count;
    }

    void updateHeader() {
        if (!hasEntity(ec) || conversationId == null) return;
        EntityValue ev = ec.getEntity().find("moqui.llm.LlmConversation")
                .condition("conversationId", conversationId).one();
        if (ev == null) {
            ev = ec.getEntity().makeValue("moqui.llm.LlmConversation");
            ev.set("conversationId", conversationId);
            writeHeader(ev, true);
        } else {
            writeHeader(ev, false);
        }
    }

    /** A conversation of the item model keeps no message rows: its trajectory is the structured items of its runs. */
    private void writeMessage(LlmMessage m, boolean create) {
        if (!hasEntity(ec) || itemModel) return;
        LlmMessageStore.write(ec, conversationId, m, create);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte item : digest) out.append(String.format("%02x", item & 0xff));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static List<LlmMessage> trimRest(List<LlmMessage> rest, WindowPolicy policy, int reservedChars) {
        if (rest.isEmpty()) return rest;
        List<LlmMessage> kept = new ArrayList<>(rest);
        LlmMessage latestUser = null;
        for (LlmMessage message : rest) {
            if (message.role == LlmMessage.Role.USER) latestUser = message;
        }
        while (!kept.isEmpty()) {
            int messageLimit = policy.maxMessages;
            if (messageLimit > 0 && latestUser != null && kept.contains(latestUser)) messageLimit++;
            boolean overMessages = messageLimit > 0 && kept.size() > messageLimit;
            boolean overChars = policy.maxChars > 0 && (reservedChars + charsOfAll(kept)) > policy.maxChars;
            if (!overMessages && !overChars) break;
            int dropIndex = 0;
            int drop = policy.keepToolPairs ? pairLength(kept, 0) : 1;
            if (drop < 1) drop = 1;
            int latestUserIndex = latestUser != null ? kept.indexOf(latestUser) : -1;
            if (latestUserIndex >= 0 && latestUserIndex < drop) {
                if (latestUserIndex + 1 >= kept.size()) break;
                dropIndex = latestUserIndex + 1;
                drop = policy.keepToolPairs ? pairLength(kept, dropIndex) : 1;
                if (drop < 1) drop = 1;
            }
            int dropEnd = Math.min(dropIndex + drop, kept.size());
            kept.subList(dropIndex, dropEnd).clear();
        }
        return kept;
    }

    private static int pairLength(List<LlmMessage> msgs, int i) {
        LlmMessage first = msgs.get(i);
        if (first.role == LlmMessage.Role.ASSISTANT && first.toolCalls != null && !first.toolCalls.isEmpty()) {
            Set<String> ids = new LinkedHashSet<>();
            for (LlmToolCall tc : first.toolCalls) if (tc != null && tc.id != null) ids.add(tc.id);
            int n = 1;
            for (int j = i + 1; j < msgs.size(); j++) {
                LlmMessage t = msgs.get(j);
                if (t.role == LlmMessage.Role.TOOL && (t.toolCallId == null || ids.contains(t.toolCallId))) n++;
                else break;
            }
            return n;
        }
        return 1;
    }

    private static int charsOf(LlmMessage m) {
        if (m == null || m.content == null) return 0;
        return m.content.length();
    }
    private static int charsOfAll(List<LlmMessage> list) {
        int n = 0;
        for (LlmMessage m : list) n += charsOf(m);
        return n;
    }
    private static int tokenEstimate(String content) {
        if (content == null || content.isEmpty()) return 0;
        return Math.max(1, content.length() / 4);
    }
    private static boolean sourceEquals(LlmMessage m, String source) {
        if (m.metadata == null) return source == null;
        Object s = m.metadata.get("source");
        if (source == null) return s == null;
        return source.equals(s);
    }
    private static boolean hasEntity(ExecutionContext ec) {
        return ec != null;
    }
    private static Timestamp now(ExecutionContext ec) {
        if (ec != null && ec.getUser() != null) return ec.getUser().getNowTimestamp();
        return new Timestamp(System.currentTimeMillis());
    }
    private static String newInMemoryId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
