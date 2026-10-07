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

import org.moqui.context.ArtifactAuthorizationException;
import org.moqui.context.ArtifactExecutionFacade;
import org.moqui.context.ArtifactExecutionInfo;
import org.moqui.context.ArtifactTarpitException;
import org.moqui.context.ExecutionContext;
import org.moqui.llm.LlmClient;
import org.moqui.llm.LlmCompactResult;
import org.moqui.llm.LlmConversation;
import org.moqui.llm.LlmException;
import org.moqui.llm.LlmFinishReason;
import org.moqui.llm.LlmItem;
import org.moqui.llm.LlmMessage;
import org.moqui.llm.LlmProtocol;
import org.moqui.llm.LlmProtocol.ProtocolRequest;
import org.moqui.llm.LlmProtocol.ProtocolResult;
import org.moqui.llm.LlmProtocol.ProtocolStreamListener;
import org.moqui.llm.LlmResponse;
import org.moqui.llm.LlmResponseOptions;
import org.moqui.llm.LlmStreamListener;
import org.moqui.llm.LlmTool;
import org.moqui.llm.LlmTransport;
import org.moqui.llm.LlmToolCall;
import org.moqui.llm.LlmToolResult;
import org.moqui.llm.WindowPolicy;
import org.moqui.util.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

public class LlmClientImpl implements LlmClient {
    private static final Logger logger = LoggerFactory.getLogger(LlmClientImpl.class);
    static final int DEFAULT_MAX_ITERATIONS = 8;
    static final int DEFAULT_TOOL_RESULT_MAX_CHARS = 8000;

    final ExecutionContext ec;
    final LlmFacadeImpl.ProfileState profile;
    private final BooleanSupplier transactionInPlace;

    String systemContent = null;
    final List<LlmMessage> extraMessages = new ArrayList<>();
    final List<LlmMessage> userMessages = new ArrayList<>();
    private String modelOverride = null;
    private Double temperature = null;
    private Integer maxTokens = null;
    private Integer timeoutSeconds = null;
    private org.moqui.llm.LlmTransport transport = org.moqui.llm.LlmTransport.HTTP;
    private Map<String, Object> extraBody = null;
    private List<LlmItem> inputItems = null;
    /** Item model conversations: the head version this turn started from; the turn completes only if it is still current. */
    private long baseHeadVersion = 0L;
    private LlmResponseOptions responseOptions = null;
    private String previousResponseId = null;
    String activeRunId = null;
    /** Keeps the lease of the run this client executes alive; null when there is no durable run. */
    private LlmRunLease runLease = null;
    private LlmConversationImpl detachedWriter = null;
    private boolean runAttached = false;
    long activeRunFence = 0L;
    List<LlmItem> activeRunContext = null;
    List<LlmItem> remotePendingItems = null;
    /** The last response of this client's run and how much of the run context it already covers (local runs). */
    private String lastProviderResponseId = null;
    private int contextBoundary = 0;
    private String lastSessionKey = null;
    private final String clientInstanceId = java.util.UUID.randomUUID().toString();
    LlmConversationImpl conversation = null;
    private WindowPolicy windowPolicy = null;
    final List<LlmTool> tools = new ArrayList<>();
    final List<LlmToolResult> resumeToolResults = new ArrayList<>();
    boolean resumeFromYielded = false;
    boolean allowClientTools = false;
    private final Set<String> allowedEntities = new LinkedHashSet<>();
    private final List<LlmFacadeImpl.AllowedPath> allowedPaths = new ArrayList<>();
    private Integer maxIterations = null;
    private int toolResultMaxChars = DEFAULT_TOOL_RESULT_MAX_CHARS;
    private boolean streamingWasPersisted = false;
    private volatile RestClient.RestStream activeStream;
    /** Set when enter_sim persists a proposed skill; cleared after a world-pass admit. */
    String pendingProposedSkillName = null;
    /** Assist Force Skill Use: refuse tools other than find_skill/enter_sim until a skill is selected. */
    boolean forceSkillUse = false;
    /** Conversation-scoped skill activated via find_skill select. */
    String activeSkillName = null;
    /** Digest of each skill at the moment find_skill selected it in this client; see {@link SkillIndex#digest}. */
    final java.util.concurrent.ConcurrentHashMap<String, String> selectedSkillDigests = new java.util.concurrent.ConcurrentHashMap<>();

    public LlmClientImpl(ExecutionContext ec, LlmFacadeImpl.ProfileState profile) {
        this(ec, profile, null);
    }
    /** @param transactionInPlace test hook; null uses ec.transaction.isTransactionInPlace() */
    public LlmClientImpl(ExecutionContext ec, LlmFacadeImpl.ProfileState profile, BooleanSupplier transactionInPlace) {
        if (profile == null) throw new LlmException("LLM profile is required");
        this.ec = ec;
        this.profile = profile;
        this.transactionInPlace = transactionInPlace;
        this.temperature = profile.temperature;
        this.maxTokens = profile.maxTokens;
        this.timeoutSeconds = profile.timeoutSeconds;
        if (profile.allowedEntities != null) this.allowedEntities.addAll(profile.allowedEntities);
        if (profile.confNode != null) {
            this.maxIterations = LlmFacadeImpl.parseInteger(profile.confNode.attribute("max-iterations"));
            int tr = LlmFacadeImpl.parseInt(profile.confNode.attribute("tool-result-max-chars"), DEFAULT_TOOL_RESULT_MAX_CHARS);
            if (tr > 0) this.toolResultMaxChars = tr;
        }
    }

    @Override public String getProfileName() { return profile.name; }

    @Override
    public LlmClient conversation(String conversationId) {
        if (conversationId == null || conversationId.isBlank())
            throw new LlmException("conversationId is required");
        this.conversation = LlmConversationImpl.load(ec, conversationId, true);
        this.conversation.bindProfile(profile);
        applyWindowPolicyToConversation();
        return this;
    }
    @Override
    public LlmClient conversation(LlmConversation conversation) {
        if (conversation == null) {
            this.conversation = null;
            return this;
        }
        if (conversation instanceof LlmConversationImpl) {
            this.conversation = (LlmConversationImpl) conversation;
        } else {
            this.conversation = LlmConversationImpl.load(ec, conversation.getConversationId(), true);
        }
        this.conversation.bindProfile(profile);
        applyWindowPolicyToConversation();
        return this;
    }
    @Override
    public LlmClient newConversation() {
        this.conversation = LlmConversationImpl.create(ec, profile.name, null);
        this.conversation.bindProfile(profile);
        applyWindowPolicyToConversation();
        return this;
    }
    @Override
    public LlmClient injectContext(String source, String content) {
        if (conversation != null) conversation.injectContext(source, content);
        else extraMessages.add(LlmMessage.context(source, content));
        return this;
    }
    @Override
    public LlmClient tool(LlmTool tool) {
        if (tool != null) {
            applyAllowLists(tool);
            tools.add(tool);
        }
        return this;
    }
    @Override
    public LlmClient tools(List<LlmTool> more) {
        if (more != null) for (LlmTool t : more) tool(t);
        return this;
    }
    @Override
    public LlmClient toolResults(List<LlmToolResult> results) {
        if (results != null) {
            for (LlmToolResult r : results) if (r != null) resumeToolResults.add(r);
        }
        return this;
    }
    @Override
    public LlmClient allowClientTools(boolean allow) {
        this.allowClientTools = allow;
        return this;
    }
    @Override
    public LlmClient allowedEntity(String entityName) {
        if (entityName != null && !entityName.isBlank()) {
            allowedEntities.add(entityName);
            for (LlmTool t : tools) {
                if (t instanceof WriteUiTool) ((WriteUiTool) t).addAllowedEntity(entityName);
            }
        }
        return this;
    }
    @Override
    public LlmClient allowedPath(String prefix, String methodsCsv) {
        if (prefix != null && !prefix.isBlank()) {
            LlmFacadeImpl.AllowedPath ap = new LlmFacadeImpl.AllowedPath(prefix, methodsCsv);
            allowedPaths.add(ap);
            for (LlmTool t : tools) {
                if (t instanceof RequestTool) ((RequestTool) t).addAllowedPath(prefix, methodsCsv);
            }
        }
        return this;
    }
    /** Assist Force Skill Use gate. Default false. */
    public LlmClient forceSkillUse(boolean on) {
        this.forceSkillUse = on;
        return this;
    }
    public boolean isForceSkillUse() { return forceSkillUse; }
    public String getActiveSkillName() { return activeSkillName; }
    /** The digest a skill had when it was selected in this client, or null when it was not selected here. */
    public String getSelectedSkillDigest(String skillName) { return skillName == null ? null : selectedSkillDigests.get(skillName); }

    @Override
    public LlmClient maxIterations(int n) {
        if (n < 1) throw new LlmException("maxIterations must be >= 1");
        this.maxIterations = n;
        return this;
    }
    @Override
    public LlmClient windowPolicy(WindowPolicy policy) {
        this.windowPolicy = policy != null ? policy : new WindowPolicy();
        applyWindowPolicyToConversation();
        return this;
    }

    @Override
    public LlmClient system(String content) {
        this.systemContent = content;
        return this;
    }
    @Override
    public LlmClient user(String content) {
        userMessages.add(LlmMessage.user(content));
        return this;
    }
    @Override
    public LlmClient inputItems(List<LlmItem> items) {
        if (items == null) {
            inputItems = null;
        } else {
            inputItems = new ArrayList<>();
            for (LlmItem item : items) if (item != null) inputItems.add(item.copy());
        }
        return this;
    }
    @Override
    public LlmClient responseOptions(LlmResponseOptions options) {
        responseOptions = options != null ? options.copy() : null;
        return this;
    }
    @Override
    public LlmClient previousResponse(String responseId) {
        previousResponseId = responseId != null && !responseId.isBlank() ? responseId : null;
        return this;
    }
    @Override
    public LlmClient messages(List<LlmMessage> extra) {
        if (extra != null) {
            for (LlmMessage m : extra) {
                if (m != null) extraMessages.add(m);
            }
        }
        return this;
    }
    @Override
    public LlmClient model(String model) {
        this.modelOverride = model;
        return this;
    }
    @Override
    public LlmClient temperature(double t) {
        this.temperature = t;
        return this;
    }
    @Override
    public LlmClient maxTokens(int n) {
        this.maxTokens = n;
        return this;
    }
    @Override
    public LlmClient timeout(int seconds) {
        this.timeoutSeconds = seconds;
        return this;
    }
    @Override
    public LlmClient transport(org.moqui.llm.LlmTransport transport) {
        this.transport = transport != null ? transport : org.moqui.llm.LlmTransport.HTTP;
        return this;
    }
    @Override
    public LlmClient extraBody(Map<String, Object> body) {
        if (body == null) this.extraBody = null;
        else this.extraBody = new LinkedHashMap<>(body);
        return this;
    }

    @Override
    public LlmResponse stream(LlmStreamListener listener) {
        if (listener == null) throw new IllegalArgumentException("LlmStreamListener is required");
        failFastIfTransaction();

        String model = requireModel();
        ArtifactExecutionFacade aefi = ec != null ? ec.getArtifactExecution() : null;
        ArtifactExecutionInfo aei = null;
        streamingWasPersisted = false;
        boolean cancelled = false;
        long start = System.currentTimeMillis();
        ProtocolResult lastResult = null;
        boolean fromAgent = !tools.isEmpty() || !resumeToolResults.isEmpty() || resumeFromYielded;
        try {
            // Authz/tarpit after the caller parsed the profile (servlet body) and before Streaming.
            if (aefi != null) {
                aei = aefi.push(profile.name, ArtifactExecutionInfo.AT_LLM,
                        ArtifactExecutionInfo.AUTHZA_VIEW, true);
            }
            if (fromAgent) {
                return new LlmAgentLoop(this).run(start, listener);
            }
            if (conversation != null) {
                conversation.persistIsolated(() -> {
                    conversation.beginTurnStreaming();
                    if (systemContent != null) conversation.replaceSystemInternal(systemContent);
                    for (LlmMessage u : userMessages) conversation.appendInternal(u.copy());
                });
                streamingWasPersisted = true;
                listener.onConversation(conversation.getConversationId());
            }
            List<LlmMessage> window = buildWindow();
            beginDurableRun(window, false);
            ProtocolRequest req = buildRequest(model, window);
            req.stream = true;
            bindUpstreamOpen(req, listener);
            persistOpenResponsesRequest(req, model);
            ProtocolResult[] resultBox = new ProtocolResult[1];
            Throwable[] failBox = new Throwable[1];
            LlmStreamJournal journal = openJournal(req, model);
            LlmTrace.logRequest(this, req);
            long protoStart = System.currentTimeMillis();
            try {
                markProviderInFlight();
                profile.protocol.chatStream(req, new ProtocolStreamListener() {
                    @Override public void onDelta(String textDelta) {
                        if (textDelta != null && !textDelta.isEmpty()) listener.onDelta(textDelta);
                    }
                    @Override public void onEvent(org.moqui.llm.LlmResponseEvent event) {
                        if (journal != null) journal.onEvent(event);
                        listener.onEvent(event);
                    }
                    @Override public void onRefusalDelta(String refusalDelta) {
                        if (refusalDelta != null && !refusalDelta.isEmpty()) listener.onRefusalDelta(refusalDelta);
                    }
                    @Override public void onToolCallDelta(String name, String argumentsSoFar) {
                        listener.onToolCallDelta(name, argumentsSoFar);
                    }
                    @Override public void onComplete(ProtocolResult result) { resultBox[0] = result; }
                    @Override public void onFailure(Throwable t) { failBox[0] = t; }
                });
            } catch (LlmException e) {
                markOpenResponsesRequestFailure(req, e);
                LlmTrace.logResponse(this, resultBox[0], System.currentTimeMillis() - protoStart);
                listener.onError(e);
                throw e;
            } catch (RuntimeException e) {
                markOpenResponsesRequestFailure(req, e);
                LlmTrace.logResponse(this, resultBox[0], System.currentTimeMillis() - protoStart);
                listener.onFailure(e);
                throw new LlmException("LLM protocol stream failed: " + e.getMessage(), e,
                        LlmFinishReason.ERROR, 0, profile.name, convId());
            } finally {
                unregisterInFlight();
            }
            LlmTrace.logResponse(this, resultBox[0], System.currentTimeMillis() - protoStart);

            if (failBox[0] != null) {
                markOpenResponsesRequestFailure(req, failBox[0]);
                listener.onFailure(failBox[0]);
                throw new LlmException("LLM stream failed: " + failBox[0].getMessage(), failBox[0],
                        LlmFinishReason.ERROR, 0, profile.name, convId());
            }
            ProtocolResult result = resultBox[0];
            if (result != null) {
                result.localRequestId = req.localRequestId;
                result.localStreamResponseId = req.localStreamResponseId;
                result.streamEventsPersisted = req.streamEventsPersisted;
            }
            settleOpenResponsesRequest(req, result);
            if (result == null) {
                LlmException le = new LlmException("LLM protocol returned no stream result",
                        null, LlmFinishReason.ERROR, 0, profile.name, convId());
                listener.onError(le);
                throw le;
            }
            lastResult = result;
            LlmFinishReason fr = result.finishReason != null ? result.finishReason : LlmFinishReason.ERROR;
            if (fr == LlmFinishReason.PENDING) {
                LlmResponse parked = parkPending(req, result, start, 1);
                listener.onComplete(parked);
                return parked;
            }
            if (isDefinitive(fr)) {
                persistSuccess(window, result, fr, start);
                recordRunResponse(result, 1);
                finishDurableRun(LlmRunStore.COMPLETE, null);
                LlmResponse streamed = finishStreamResult(listener, result, start);
                try { ConversationSummary.maybe(this); }
                catch (Throwable t) { logger.warn("Conversation summary failed: " + t.getMessage()); }
                return streamed;
            }
            return finishStreamResult(listener, result, start);
        } catch (Throwable t) {
            cancelled = LlmConversationImpl.isCancelThrowable(t);
            if (activeRunId != null) {
                try { finishDurableRun(cancelled ? LlmRunStore.CANCELLED : LlmRunStore.FAILED, t.getMessage()); }
                catch (Throwable runErr) { logger.error("Error terminating LLM run " + activeRunId, runErr); }
            }
            if (streamingWasPersisted && conversation != null && !isMaxIterations(t)) {
                try { persistFailure(lastResult, start, t); }
                catch (Throwable persistErr) {
                    logger.error("Error persisting LLM Failed/Cancelled for conversation " + convId(), persistErr);
                }
            }
            if (fromAgent) {
                if (t instanceof LlmException) listener.onError((LlmException) t);
                else listener.onFailure(t);
            }
            if (t instanceof ArtifactAuthorizationException) throw (ArtifactAuthorizationException) t;
            if (t instanceof ArtifactTarpitException) throw (ArtifactTarpitException) t;
            if (t instanceof LlmException) throw (LlmException) t;
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            throw new LlmException("LLM stream failed: " + t.getMessage(), t,
                    LlmFinishReason.ERROR, 0, profile.name, convId());
        } finally {
            stopLease();
            if (streamingWasPersisted && conversation != null
                    && !LlmConversationImpl.STATUS_COMPLETE.equals(conversation.getStatus())) {
                try { conversation.repairTerminalIfStreaming(cancelled); }
                catch (Throwable persistErr) {
                    logger.error("Error repairing LLM Streaming status for conversation " + convId(), persistErr);
                }
            }
            if (conversation != null) {
                try { conversation.releaseTurnClaim(); }
                catch (Throwable persistErr) {
                    logger.error("Error releasing LLM turn for conversation " + convId(), persistErr);
                }
            }
            if (aefi != null && aei != null) aefi.pop(aei);
        }
    }

    @Override
    public LlmResponse call() {
        // Fail-fast BEFORE any status=Streaming write. persistIsolated resumes the caller TX, so a
        // check after Streaming would still see a ServiceJob TX and wedge the conversation (K11).
        if (isTransactionInPlace() && !profile.allowTxOverHttp) {
            throw new LlmException(
                    "Cannot call LLM while a JTA transaction is active (default TX timeout 60s vs LLM timeout "
                            + (timeoutSeconds != null ? timeoutSeconds : profile.timeoutSeconds)
                            + "s). Commit first, or set allow-tx-over-http=true.",
                    null, LlmFinishReason.ERROR, 0, profile.name, convId());
        }

        String model = resolveModel();
        if (model == null || model.isBlank()) {
            throw new LlmException("profile '" + profile.name + "' has no model",
                    null, LlmFinishReason.ERROR, 0, profile.name, convId());
        }

        ArtifactExecutionFacade aefi = ec != null ? ec.getArtifactExecution() : null;
        ArtifactExecutionInfo aei = null;
        streamingWasPersisted = false;
        boolean cancelled = false;
        int emptyAttempts = 0;
        int errorAttempts = 0;
        float waitSeconds = profile.retryInitialSeconds > 0 ? profile.retryInitialSeconds : 0;
        long start = System.currentTimeMillis();
        ProtocolResult lastResult = null;
        try {
            // Authz/tarpit before any Streaming write so a 403/429 cannot wedge the row.
            if (aefi != null) {
                aei = aefi.push(profile.name, ArtifactExecutionInfo.AT_LLM,
                        ArtifactExecutionInfo.AUTHZA_VIEW, true);
            }
            if (!tools.isEmpty() || !resumeToolResults.isEmpty() || resumeFromYielded) {
                return new LlmAgentLoop(this).run(start);
            }
            if (conversation != null) {
                conversation.persistIsolated(() -> {
                    conversation.beginTurnStreaming();
                    if (systemContent != null) conversation.replaceSystemInternal(systemContent);
                    for (LlmMessage u : userMessages) conversation.appendInternal(u.copy());
                });
                streamingWasPersisted = true;
            }
            while (true) {
                List<LlmMessage> window = buildWindow();
                if (activeRunId == null) beginDurableRun(window, false);
                ProtocolRequest req = buildRequest(model, window);
                persistOpenResponsesRequest(req, model);
                ProtocolResult result;
                LlmTrace.logRequest(this, req);
                long protoStart = System.currentTimeMillis();
                try {
                    markProviderInFlight();
                    result = profile.protocol.chat(req);
                    if (result != null) result.localRequestId = req.localRequestId;
                } catch (ArtifactAuthorizationException | ArtifactTarpitException e) {
                    markOpenResponsesRequestFailure(req, e);
                    LlmTrace.logResponse(this, null, System.currentTimeMillis() - protoStart);
                    throw e;
                } catch (LlmException e) {
                    markOpenResponsesRequestFailure(req, e);
                    LlmTrace.logResponse(this, null, System.currentTimeMillis() - protoStart);
                    throw e;
                } catch (RuntimeException e) {
                    markOpenResponsesRequestFailure(req, e);
                    LlmTrace.logResponse(this, null, System.currentTimeMillis() - protoStart);
                    throw new LlmException("LLM protocol call failed: " + e.getMessage(), e,
                            LlmFinishReason.ERROR, 0, profile.name, convId());
                }
                LlmTrace.logResponse(this, result, System.currentTimeMillis() - protoStart);
                settleOpenResponsesRequest(req, result);
                if (result == null) {
                    throw new LlmException("LLM protocol returned no result",
                            null, LlmFinishReason.ERROR, 0, profile.name, convId());
                }
                lastResult = result;
                LlmFinishReason fr = result.finishReason != null ? result.finishReason : LlmFinishReason.ERROR;

                if (fr == LlmFinishReason.PENDING) return parkPending(req, result, start, 1);
                if (isDefinitive(fr)) {
                    persistSuccess(window, result, fr, start);
                    recordRunResponse(result, 1);
                    finishDurableRun(LlmRunStore.COMPLETE, null);
                    LlmResponse done = toResponse(result, fr, start);
                    try { ConversationSummary.maybe(this); }
                    catch (Throwable t) { logger.warn("Conversation summary failed: " + t.getMessage()); }
                    return done;
                }
                if (fr == LlmFinishReason.CONTENT_FILTER) {
                    throw new LlmException(nvl(result.errorMessage, "LLM content filter"),
                            null, LlmFinishReason.CONTENT_FILTER, result.httpStatus, profile.name, convId());
                }
                if (fr == LlmFinishReason.CONTEXT_OVERFLOW) {
                    throw new LlmException(nvl(result.errorMessage, "LLM context length exceeded"),
                            null, LlmFinishReason.CONTEXT_OVERFLOW, result.httpStatus, profile.name, convId());
                }
                if (fr == LlmFinishReason.EMPTY) {
                    if (emptyAttempts < profile.emptyRetries) {
                        emptyAttempts++;
                        sleepBackoff(waitSeconds);
                        waitSeconds = nextWait(waitSeconds);
                        continue;
                    }
                    throw new LlmException("LLM returned empty content after " + profile.emptyRetries + " retries",
                            null, LlmFinishReason.EMPTY, result.httpStatus, profile.name, convId());
                }

                boolean retryable = result.retryable;
                if (retryable && errorAttempts < profile.retryMax) {
                    errorAttempts++;
                    sleepBackoff(waitSeconds);
                    waitSeconds = nextWait(waitSeconds);
                    continue;
                }
                throw new LlmException(nvl(result.errorMessage, "LLM call failed"),
                        null, LlmFinishReason.ERROR, result.httpStatus, profile.name, convId());
            }
        } catch (Throwable t) {
            cancelled = LlmConversationImpl.isCancelThrowable(t);
            if (activeRunId != null) {
                try { finishDurableRun(cancelled ? LlmRunStore.CANCELLED : LlmRunStore.FAILED, t.getMessage()); }
                catch (Throwable runErr) { logger.error("Error terminating LLM run " + activeRunId, runErr); }
            }
            // MAX_ITERATIONS already wrote Active so the caller can continue; do not overwrite with Failed.
            if (streamingWasPersisted && conversation != null && !isMaxIterations(t)) {
                try { persistFailure(lastResult, start, t); }
                catch (Throwable persistErr) {
                    logger.error("Error persisting LLM Failed/Cancelled for conversation " + convId(), persistErr);
                }
            }
            if (t instanceof ArtifactAuthorizationException) throw (ArtifactAuthorizationException) t;
            if (t instanceof ArtifactTarpitException) throw (ArtifactTarpitException) t;
            if (t instanceof LlmException) throw (LlmException) t;
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            throw new LlmException("LLM call failed: " + t.getMessage(), t,
                    LlmFinishReason.ERROR, 0, profile.name, convId());
        } finally {
            stopLease();
            // DB is source of truth: if this turn persisted Streaming and did not Complete, re-read
            // the header and Failed/Cancelled if still Streaming (memory may already have been mutated).
            if (streamingWasPersisted && conversation != null
                    && !LlmConversationImpl.STATUS_COMPLETE.equals(conversation.getStatus())) {
                try { conversation.repairTerminalIfStreaming(cancelled); }
                catch (Throwable persistErr) {
                    logger.error("Error repairing LLM Streaming status for conversation " + convId(), persistErr);
                }
            }
            if (conversation != null) {
                try { conversation.releaseTurnClaim(); }
                catch (Throwable persistErr) {
                    logger.error("Error releasing LLM turn for conversation " + convId(), persistErr);
                }
            }
            if (aefi != null && aei != null) aefi.pop(aei);
        }
    }

    @Override
    public LlmCompactResult compact() {
        failFastIfTransaction();
        if (!profile.protocol.getCapabilities().contains(LlmProtocol.Capability.COMPACT))
            throw new LlmException("LLM profile '" + profile.name + "' does not support Open Responses compaction");
        String model = requireModel();
        boolean ofConversation = conversation != null && conversation.usesItemModel();
        ProtocolRequest request;
        long baseVersion = 0L;
        if (ofConversation) {
            // what is compacted is the trajectory of the conversation, taken in one piece, and nothing else
            conversation.persistIsolated(() -> conversation.beginTurnStreaming());
            streamingWasPersisted = true;
            baseVersion = conversation.getHeadVersion();
            List<LlmItem> trajectory = conversation.headItems();
            if (trajectory.isEmpty()) {
                conversation.persistIsolated(() -> conversation.setStatusInternal(LlmConversationImpl.STATUS_ACTIVE));
                conversation.releaseTurnClaim();
                throw new LlmException("Conversation " + convId() + " has no trajectory to compact");
            }
            inputItems(trajectory);
            request = buildRequest(model, java.util.Collections.emptyList());
        } else {
            request = buildRequest(model, buildWindow());
        }
        request.operation = "compact_response";
        request.instructions = ofConversation ? conversation.instructions() : systemContent;
        OpenResponsesProtocol protocol = profile.protocol instanceof OpenResponsesProtocol ? (OpenResponsesProtocol) profile.protocol : null;
        if (protocol != null) protocol.prepareCompactBody(request);
        LlmConversationImpl target = persistenceTarget();
        boolean persisted = false;
        try {
            if (protocol != null && target != null) {
                String bodyJson = request.preparedJson;
                Map<String, Object> body = request.preparedBody;
                target.persistIsolated(() -> target.writeOpenResponsesRequest(profile.name, profile.protocol.getName(), model, request, body, bodyJson));
                persisted = true;
            }
            LlmCompactResult compacted;
            try {
                compacted = profile.protocol.compact(request);
            } catch (RuntimeException e) {
                if (persisted) markOpenResponsesRequestFailure(request, e);
                throw e;
            }
            ProtocolResult stored = new ProtocolResult();
            stored.responseId = compacted.id;
            stored.status = "completed";
            stored.httpStatus = 200;
            stored.finishReason = LlmFinishReason.STOP;
            stored.model = model;
            stored.createdAt = compacted.createdAt;
            stored.usage = compacted.usage;
            stored.outputItems = compacted.output;
            stored.responsePayload = compacted.payload;
            stored.localRequestId = request.localRequestId;
            if (persisted) {
                // the response and, for a conversation, the new head in one transaction; a failure leaves the head as it was
                final long expected = baseVersion;
                target.persistIsolated(() -> {
                    target.writeCallLog(profile.name, profile.protocol.getName(), model, false, null, stored, 0L, 1, false);
                    if (ofConversation) {
                        Map<String, Object> create = new LinkedHashMap<>();
                        create.put("conversationId", convId());
                        create.put("profileName", profile.name);
                        create.put("continuationModeEnumId", "LlmContLocal");
                        create.put("context", copyItems(compacted.output));
                        create.put("checkpoint", java.util.Collections.singletonMap("phase", "compaction"));
                        create.put("maxIterations", 1);
                        create.put("envelope", runEnvelope());
                        Map<String, Object> created = LlmRunStore.createRun(ec, create);
                        String runId = created.get("runId").toString();
                        LlmRunStore.transition(ec, runId, LlmRunStore.RUNNING, "Compaction");
                        LlmRunStore.transition(ec, runId, LlmRunStore.COMPLETE, "Compaction");
                        conversation.advanceHead(runId, expected);
                    }
                });
            }
            return compacted;
        } finally {
            if (ofConversation) {
                try {
                    conversation.persistIsolated(() -> conversation.setStatusInternal(LlmConversationImpl.STATUS_COMPLETE));
                    conversation.releaseTurnClaim();
                } catch (Throwable t) { logger.error("Error closing the compaction of conversation " + convId(), t); }
            }
        }
    }

    /** A result that ends the turn: an answer, a limit that was reached, tool calls to run, or a refusal. */
    static boolean isDefinitive(LlmFinishReason fr) {
        return fr == LlmFinishReason.STOP || fr == LlmFinishReason.LENGTH || fr == LlmFinishReason.TOOL_CALLS
                || fr == LlmFinishReason.REFUSAL;
    }

    /**
     * What the history keeps about the assistant message besides its text: the refusal, so a replayed refusal goes back as
     * one, and the provider's identifiers and finish reason. Null when there is nothing to keep.
     */
    static Map<String, Object> messageMetadata(ProtocolResult result) {
        Map<String, Object> md = new LinkedHashMap<>();
        if (result.refusal != null) md.put("refusal", result.refusal);
        if (result.metadata != null) {
            Object response = result.metadata.get("response");
            if (response instanceof Map && ((Map<?, ?>) response).get("id") != null)
                md.put("providerResponseId", ((Map<?, ?>) response).get("id"));
            Object choice = result.metadata.get("choice");
            if (choice instanceof Map && ((Map<?, ?>) choice).get("finish_reason") != null)
                md.put("providerFinishReason", ((Map<?, ?>) choice).get("finish_reason"));
            Object message = result.metadata.get("message");
            if (message instanceof Map && ((Map<?, ?>) message).get("annotations") != null)
                md.put("annotations", ((Map<?, ?>) message).get("annotations"));
        }
        return md.isEmpty() ? null : md;
    }

    private void persistSuccess(List<LlmMessage> window, ProtocolResult result, LlmFinishReason fr, long start) {
        if (result != null) { result.runId = activeRunId; result.runFence = activeRunFence; }
        if (conversation == null) {
            persistDetachedResponse(window, result, start, 1, false);
            return;
        }
        conversation.persistIsolated(() -> {
            LlmMessage asst = LlmMessage.assistant(result.content);
            asst.toolCalls = result.toolCalls;
            asst.metadata = messageMetadata(result);
            conversation.appendInternal(asst);
            conversation.writeCallLog(profile.name, profile.protocol != null ? profile.protocol.getName() : null,
                    result.model != null ? result.model : resolveModel(), profile.logContent,
                    window, result, System.currentTimeMillis() - start, 1, false);
            conversation.setStatusInternal(LlmConversationImpl.STATUS_COMPLETE);
        });
        throwIfCancelled();
    }

    private void persistFailure(ProtocolResult result, long start, Throwable t) {
        if (conversation == null) {
            if (!isMaxIterations(t)) persistDetachedResponse(null, result, start, 1, true);
            return;
        }
        if (isMaxIterations(t)) return;
        boolean cancelled = LlmConversationImpl.isCancelThrowable(t);
        String status = cancelled ? LlmConversationImpl.STATUS_CANCELLED : LlmConversationImpl.STATUS_FAILED;
        conversation.persistIsolated(() -> {
            if (result != null) {
                conversation.writeCallLog(profile.name, profile.protocol != null ? profile.protocol.getName() : null,
                        result.model != null ? result.model : resolveModel(), profile.logContent,
                        null, result, System.currentTimeMillis() - start, 1, true);
            }
            conversation.setStatusInternal(status);
        });
    }


    /**
     * A response the provider accepted and is still working on. The turn is parked, not completed and not failed: nothing
     * in it is acted on and no second request is made. The run waits for the provider (with the response id, the local
     * request, when to look again and when to give up in its checkpoint), the conversation waits with it so no other turn
     * starts, and the caller gets the response id with {@code pending=true}. Where the provider has an operation to read a
     * response, a poller completes the run; where it has not, the reference is all there is and nothing is promised.
     */
    LlmResponse parkPending(ProtocolRequest req, ProtocolResult result, long start, int iteration) {
        boolean pollable = profile.protocol.getCapabilities().contains(org.moqui.llm.LlmProtocol.Capability.RETRIEVE)
                && result.responseId != null;
        if (activeRunId != null) {
            long now = System.currentTimeMillis();
            Map<String, Object> checkpoint = new LinkedHashMap<>();
            checkpoint.put("phase", "provider_pending");
            checkpoint.put("providerResponseId", result.responseId);
            checkpoint.put("localRequestId", req != null ? req.localRequestId : result.localRequestId);
            checkpoint.put("status", result.status);
            checkpoint.put("pollable", pollable);
            checkpoint.put("iteration", iteration);
            int every = backgroundSetting("llm_background_poll_seconds", 5);
            checkpoint.put("pollEverySeconds", every);
            checkpoint.put("nextPollAt", now + every * 1000L);
            checkpoint.put("deadlineAt", now + backgroundSetting("llm_background_timeout_seconds", 3600) * 1000L);
            LlmRunStore.checkpoint(ec, activeRunId, activeRunFence, activeRunContext, checkpoint, result.responseId, iteration);
            if (conversation != null) conversation.persistIsolated(() -> conversation.setStatusInternal(LlmConversationImpl.STATUS_YIELDED));
            finishDurableRun(LlmRunStore.WAIT_PROVIDER, "Waiting for the provider to finish response " + result.responseId);
        }
        LlmResponse r = toResponse(result, LlmFinishReason.PENDING, start);
        r.pending = true;
        r.pollable = pollable;
        if (!pollable) r.errorMessage = "The provider has not finished response " + result.responseId
                + " and this protocol has no operation to ask it again; the run waits until llm_background_timeout_seconds and then ends";
        r.httpStatus = result.httpStatus;
        return r;
    }

    private static int backgroundSetting(String name, int dflt) {
        String v = org.moqui.util.SystemBinding.getPropOrEnv(name);
        if (v == null || v.isBlank()) return dflt;
        try { return Math.max(Integer.parseInt(v.trim()), 0); } catch (NumberFormatException e) { return dflt; }
    }

    private LlmResponse finishStreamResult(LlmStreamListener listener, ProtocolResult result, long start) {
        LlmFinishReason fr = result.finishReason != null ? result.finishReason : LlmFinishReason.ERROR;
        if (isDefinitive(fr)) {
            LlmResponse r = toResponse(result, fr, start);
            if (r.toolCalls != null) {
                for (LlmToolCall tc : r.toolCalls) {
                    if (tc != null) listener.onToolCall(tc, LlmTool.Execution.SERVER);
                }
            }
            listener.onComplete(r);
            return r;
        }
        LlmException le;
        if (fr == LlmFinishReason.CONTENT_FILTER) {
            le = new LlmException(nvl(result.errorMessage, "LLM content filter"),
                    LlmFinishReason.CONTENT_FILTER, result.httpStatus, profile.name);
        } else if (fr == LlmFinishReason.CONTEXT_OVERFLOW) {
            le = new LlmException(nvl(result.errorMessage, "LLM context length exceeded"),
                    LlmFinishReason.CONTEXT_OVERFLOW, result.httpStatus, profile.name);
        } else if (fr == LlmFinishReason.EMPTY) {
            le = new LlmException("LLM returned empty content", LlmFinishReason.EMPTY, result.httpStatus, profile.name);
        } else {
            le = new LlmException(nvl(result.errorMessage, "LLM call failed"),
                    LlmFinishReason.ERROR, result.httpStatus, profile.name);
        }
        listener.onError(le);
        throw le;
    }

    private void failFastIfTransaction() {
        if (isTransactionInPlace() && !profile.allowTxOverHttp) {
            throw new LlmException(
                    "Cannot call LLM while a JTA transaction is active (default TX timeout 60s vs LLM timeout "
                            + (timeoutSeconds != null ? timeoutSeconds : profile.timeoutSeconds)
                            + "s). Commit first, or set allow-tx-over-http=true.",
                    LlmFinishReason.ERROR, 0, profile.name);
        }
    }

    private boolean isTransactionInPlace() {
        if (transactionInPlace != null) return transactionInPlace.getAsBoolean();
        if (ec == null || ec.getTransaction() == null) return false;
        return ec.getTransaction().isTransactionInPlace();
    }

    private String requireModel() {
        String model = resolveModel();
        if (model == null || model.isBlank()) {
            throw new LlmException("profile '" + profile.name + "' has no model",
                    LlmFinishReason.ERROR, 0, profile.name);
        }
        return model;
    }

    String resolveModel() {
        if (modelOverride != null && !modelOverride.isBlank()) return modelOverride;
        if (profile.model != null && !profile.model.isBlank()) return profile.model;
        if (extraBody != null && extraBody.get("model") != null) {
            String m = extraBody.get("model").toString();
            if (m != null && !m.isBlank()) return m;
        }
        return null;
    }

    List<LlmMessage> buildWindow() {
        if (conversation != null) {
            WindowPolicy policy = windowPolicy != null ? windowPolicy : conversation.getWindowPolicy();
            List<LlmMessage> window = conversation.buildWindow(policy);
            for (LlmMessage extra : extraMessages) {
                if (extra == null || extra.role == LlmMessage.Role.SYSTEM) continue;
                window.add(extra);
            }
            return window;
        }
        List<LlmMessage> window = new ArrayList<>();
        if (systemContent != null) window.add(LlmMessage.system(systemContent));
        window.addAll(extraMessages);
        window.addAll(userMessages);
        return window;
    }

    ProtocolRequest buildRequest(String model, List<LlmMessage> window) {
        ProtocolRequest req = new ProtocolRequest();
        req.profileName = profile.name;
        req.runId = activeRunId;
        req.runFence = activeRunFence;
        req.endpointUrl = profile.endpointUrl;
        req.apiKey = profile.apiKey;
        req.authHeaderName = profile.authHeaderName;
        req.authHeaderValue = profile.authHeaderValue;
        req.extraHeaders = profile.extraHeaders;
        req.extraQuery = profile.extraQuery;
        req.model = model;
        req.window = window;
        List<LlmItem> effectiveItems = remotePendingItems != null ? remotePendingItems
                : activeRunContext != null ? activeRunContext : inputItems;
        if (effectiveItems != null) {
            req.inputItems = new ArrayList<>();
            for (LlmItem item : effectiveItems) req.inputItems.add(item != null ? item.copy() : null);
            // the items carry no system message: the instructions of the turn travel in their own field
            req.instructions = conversation != null && conversation.usesItemModel() ? conversation.instructions() : systemContent;
        }
        req.previousResponseId = previousResponseId;
        req.responseOptions = responseOptions != null ? responseOptions.copy() : null;
        req.transport = transport;
        req.sessionKey = sessionKey();
        applyConnectionContinuation(req);
        req.specVersion = profile.protocol != null ? profile.protocol.getSpecVersion() : null;
        req.temperature = temperature;
        req.maxTokens = maxTokens;
        req.maxTokensParameter = profile.maxTokensParameter;
        req.timeoutSeconds = timeoutSeconds != null ? timeoutSeconds : profile.timeoutSeconds;
        req.stream = false;
        req.extraBody = extraBody;
        req.requestFactory = profile.requestFactory;
        req.retryInitialSeconds = profile.retryInitialSeconds;
        req.retryMax = profile.retryMax;
        req.timeoutRetry = profile.timeoutRetry;
        req.logContent = profile.logContent;
        if (!tools.isEmpty()) req.tools = tools;
        return req;
    }

    /**
     * The persistent WebSocket connection this client may use: scoped to the owner, the run (or conversation), the
     * profile, the endpoint and the credential, so no two users, runs or credentials ever share a connection. A call
     * that is neither part of a run nor of a conversation has nothing to continue and uses a connection of its own.
     */
    private String sessionKey() {
        if (transport != org.moqui.llm.LlmTransport.WEBSOCKET || !(profile.protocol instanceof OpenResponsesProtocol)) return null;
        // an interactive conversation keeps one connection across its turns; a run that belongs to no conversation has its own
        String scope = conversation != null && convId() != null ? "conv:" + convId()
                : activeRunId != null ? "run:" + activeRunId : null;
        if (scope == null) return null;
        String owner = canPersistRun() ? ec.getUser().getUserId() : "-";
        String credential = String.valueOf(profile.apiKey) + "\n" + profile.authHeaderName + "\n" + profile.authHeaderValue;
        String key = owner + "|" + scope + "|" + profile.name + "|" + profile.endpointUrl + "|" + sha256Short(credential);
        lastSessionKey = key;
        return key;
    }

    private static String sha256Short(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 8; i++) out.append(String.format("%02x", digest[i] & 0xff));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean storeRequested(LlmResponseOptions options) {
        Object store = options != null ? options.asMap().get("store") : null;
        return store != null && "true".equalsIgnoreCase(String.valueOf(store));
    }

    /**
     * A run that keeps its whole context locally continues on the same connection with only the items the provider has
     * not seen, when that connection still holds the previous response (store=false, which only this connection can
     * continue). Otherwise the full local context is sent as a new chain, with no previous_response_id. A run that was
     * started from a remote chain has no local copy of what came before and cannot do that: its failed continuation is
     * reported by the provider, never replaced by a guess.
     */
    private void applyConnectionContinuation(ProtocolRequest req) {
        if (req.sessionKey == null || remotePendingItems != null || activeRunContext == null || lastProviderResponseId == null) return;
        if (storeRequested(responseOptions)) return;
        if (!profile.protocol.continuesOnConnection(req.sessionKey, lastProviderResponseId)) return;
        int from = Math.min(contextBoundary, activeRunContext.size());
        if (from >= activeRunContext.size()) return;
        req.previousResponseId = lastProviderResponseId;
        req.inputItems = new ArrayList<>();
        for (LlmItem item : activeRunContext.subList(from, activeRunContext.size())) req.inputItems.add(item != null ? item.copy() : null);
    }

    /**
     * Where Open Responses requests and responses are persisted: the conversation when there is one, otherwise a
     * detached writer for an authenticated user, so a background run keeps its request and response records too.
     * Null for other protocols and for callers that cannot persist.
     */
    LlmConversationImpl persistenceTarget() {
        if (conversation != null) return conversation;
        if (!(profile.protocol instanceof OpenResponsesProtocol) || !canPersistRun()) return null;
        if (detachedWriter == null) detachedWriter = LlmConversationImpl.detached(ec);
        return detachedWriter;
    }

    void persistOpenResponsesRequest(ProtocolRequest req, String model) {
        LlmConversationImpl target = persistenceTarget();
        if (target == null || req == null || !(profile.protocol instanceof OpenResponsesProtocol)) return;
        // the protocol builds the body once; the snapshot stores those very characters and the transport sends them
        OpenResponsesProtocol protocol = (OpenResponsesProtocol) profile.protocol;
        String bodyJson = protocol.prepareBody(req);
        Map<String, Object> body = req.preparedBody;
        target.persistIsolated(() -> target.writeOpenResponsesRequest(profile.name,
                profile.protocol != null ? profile.protocol.getName() : null, model, req, body, bodyJson));
    }

    /** The journal that persists a streamed response while it arrives; null when there is nowhere to persist. */
    LlmStreamJournal openJournal(ProtocolRequest req, String model) {
        LlmConversationImpl target = persistenceTarget();
        if (target == null || req == null || req.localRequestId == null || !(profile.protocol instanceof OpenResponsesProtocol)) return null;
        return new LlmStreamJournal(target, req, profile.name, profile.protocol.getName(), model);
    }

    /**
     * A provider result that is not a usable response (an HTTP error, an empty answer) is not persisted as a response,
     * so the request would stay Sending. Settle it: Failed when the provider refused it, Acknowledged when it answered,
     * Uncertain when there was no answer to read.
     */
    void settleOpenResponsesRequest(ProtocolRequest req, ProtocolResult result) {
        if (req == null || result == null || req.localRequestId == null) return;
        LlmFinishReason fr = result.finishReason;
        if (fr == LlmFinishReason.STOP || fr == LlmFinishReason.LENGTH || fr == LlmFinishReason.TOOL_CALLS) return;
        LlmConversationImpl target = persistenceTarget();
        if (target == null) return;
        int status = result.httpStatus;
        boolean notSent = OpenResponsesProtocol.NOT_SENT.equals(result.providerErrorCode);
        String statusId = status >= 400 || notSent ? "LlmReqFailed" : status > 0 ? "LlmReqAck" : "LlmReqUncertain";
        Map<String, Object> errorMap = null;
        if (status >= 400 || status <= 0) {
            errorMap = new LinkedHashMap<>();
            errorMap.put("message", result.errorMessage);
            errorMap.put("finishReason", fr != null ? fr.name() : null);
        }
        final Map<String, Object> error = errorMap;
        target.persistIsolated(() -> target.updateOpenResponsesRequestStatus(req.localRequestId, statusId,
                status > 0 ? status : null, error));
    }

    /** Persists the provider response of a call that has no conversation to carry it. */
    void persistDetachedResponse(List<LlmMessage> window, ProtocolResult result, long start, int iteration, boolean wasError) {
        if (conversation != null || result == null) return;
        LlmConversationImpl target = persistenceTarget();
        if (target == null) return;
        target.persistIsolated(() -> target.writeCallLog(profile.name,
                profile.protocol != null ? profile.protocol.getName() : null,
                result.model != null ? result.model : resolveModel(), profile.logContent,
                window, result, System.currentTimeMillis() - start, iteration, wasError));
    }

    void markOpenResponsesRequestFailure(ProtocolRequest req, Throwable error) {
        LlmConversationImpl target = persistenceTarget();
        if (target == null || req == null || req.localRequestId == null) return;
        Map<String, Object> errorMap = new LinkedHashMap<>();
        errorMap.put("type", error != null ? error.getClass().getName() : null);
        errorMap.put("message", error != null ? error.getMessage() : null);
        Integer httpStatus = error instanceof LlmException ? ((LlmException) error).getHttpStatus() : null;
        // Failed only when the outcome is known: the provider answered with an error, or the request was refused
        // locally before it was sent. A timeout, a broken stream or a cancel after sending may have had an effect.
        boolean refusedLocally = error instanceof ArtifactAuthorizationException || error instanceof ArtifactTarpitException;
        boolean answered = httpStatus != null && httpStatus > 0;
        boolean notSent = OpenResponsesProtocol.connectionNeverEstablished(error);
        String statusId = refusedLocally || answered || notSent ? "LlmReqFailed" : "LlmReqUncertain";
        target.persistIsolated(() -> {
            target.updateOpenResponsesRequestStatus(req.localRequestId, statusId, httpStatus, errorMap);
            target.markStreamInterrupted(req, errorMap);
        });
    }

    void beginDurableRun(List<LlmMessage> window, boolean resume) {
        if (!canPersistRun()) return;
        // a run attached with attachRecoveredRun is continued, not recreated
        if (runAttached) { runAttached = false; return; }
        if (resume && conversation != null) {
            Object storedRunId = conversation.getAttributes().get("activeLlmRunId");
            if (storedRunId != null) {
                Map<String, Object> stored = LlmRunStore.getRun(ec, storedRunId.toString());
                if (stored == null || !String.valueOf(stored.get("conversationId")).equals(convId())
                        || !String.valueOf(stored.get("userId")).equals(String.valueOf(ec.getUser().getUserId())))
                    throw new LlmException("The run " + storedRunId + " is not a run of conversation " + convId() + " of this user",
                            null, LlmFinishReason.ERROR, 409, profile.name, convId());
                if (stored.get("envelope") instanceof Map && ((Map<?, ?>) stored.get("envelope")).get("conversationHeadVersion") instanceof Number)
                    baseHeadVersion = ((Number) ((Map<?, ?>) stored.get("envelope")).get("conversationHeadVersion")).longValue();
                activeRunId = stored.get("runId") != null ? stored.get("runId").toString() : null;
                activeRunFence = stored.get("fencingToken") instanceof Number
                        ? ((Number) stored.get("fencingToken")).longValue() : 0L;
                activeRunContext = OpenResponsesCodec.itemsFromStored(stored.get("context"));
                if ("LlmContRemote".equals(stored.get("continuationModeEnumId"))) {
                    previousResponseId = stored.get("previousProviderResponseId") != null
                            ? stored.get("previousProviderResponseId").toString() : previousResponseId;
                    remotePendingItems = new ArrayList<>();
                }
                String status = stored.get("statusId") != null ? stored.get("statusId").toString() : null;
                if (LlmRunStore.WAIT_CLIENT.equals(status) || LlmRunStore.WAIT_CONFIRM.equals(status)
                        || LlmRunStore.RECOVERING.equals(status))
                    LlmRunStore.transition(ec, activeRunId, LlmRunStore.RUNNING, "Agent loop resumed");
                takeLease();
                return;
            }
        }
        if (conversation != null && conversation.usesItemModel()) {
            assembleItemTurn();
        } else {
            activeRunContext = inputItems != null ? copyItems(inputItems) : OpenResponsesCodec.itemsFromMessages(window);
            if (previousResponseId != null) remotePendingItems = inputItems != null
                    ? copyItems(inputItems) : OpenResponsesCodec.itemsFromMessages(userMessages);
        }
        Map<String, Object> createRequest = new LinkedHashMap<>();
        createRequest.put("conversationId", convId());
        createRequest.put("profileName", profile.name);
        createRequest.put("continuationModeEnumId", previousResponseId != null ? "LlmContRemote" : "LlmContLocal");
        createRequest.put("context", activeRunContext);
        createRequest.put("checkpoint", java.util.Collections.singletonMap("phase", "ready_provider"));
        createRequest.put("maxIterations", maxIterationsEffective());
        createRequest.put("previousProviderResponseId", previousResponseId);
        createRequest.put("envelope", runEnvelope());
        Map<String, Object> created = LlmRunStore.createRun(ec, createRequest);
        activeRunId = created.get("runId").toString();
        LlmRunStore.transition(ec, activeRunId, LlmRunStore.RUNNING, "Agent loop started");
        takeLease();
        if (conversation != null) conversation.setAttribute("activeLlmRunId", activeRunId);
    }


    /**
     * The context of a new turn of a conversation of the item model, built from one source: the trajectory of the head
     * run (its own items, with ids, images, tool calls and opaque reasoning exactly as they were), the application
     * context of this turn marked as not part of the history, and the new input once. A head that continues a remote
     * chain continues it, and a caller cannot point the turn at a chain that is not the conversation's.
     */
    /** How many items of a stored run context belong to the trajectory: all but the per-turn context. */
    private static long countKept(Object storedContext) {
        long kept = 0;
        for (LlmItem item : OpenResponsesCodec.itemsFromStored(storedContext)) if (!Boolean.TRUE.equals(item.ephemeral)) kept++;
        return kept;
    }

    private void assembleItemTurn() {
        Map<String, Object> head = conversation.headRun();
        baseHeadVersion = conversation.getHeadVersion();
        List<LlmItem> trajectory = conversation.headItems();
        List<LlmItem> input = inputItems != null ? copyItems(inputItems) : OpenResponsesCodec.itemsFromMessages(userMessages);
        List<LlmItem> context = new ArrayList<>();
        for (LlmMessage m : conversation.contextMessages())
            for (LlmItem item : OpenResponsesCodec.itemsFromMessages(java.util.Collections.singletonList(m))) {
                item.ephemeral = Boolean.TRUE;
                context.add(item);
            }
        String chain = head != null && "LlmContRemote".equals(head.get("continuationModeEnumId"))
                && head.get("previousProviderResponseId") != null ? head.get("previousProviderResponseId").toString() : null;
        if (head != null && previousResponseId != null && !previousResponseId.equals(chain)
                && !previousResponseId.equals(String.valueOf(head.get("previousProviderResponseId"))))
            throw new LlmException("previous_response_id " + previousResponseId + " does not continue conversation " + convId(),
                    null, LlmFinishReason.ERROR, 409, profile.name, convId());
        if (chain != null && previousResponseId == null) previousResponseId = chain;
        activeRunContext = new ArrayList<>(trajectory);
        // the provider holds what this turn would continue from only if the head says the same: its last response, and a stored
        // context that is exactly the trajectory (nothing taken out of it). The context of a turn (session, pins) went to the
        // provider with that turn and is not part of the trajectory; this turn sends its own context again, as new input.
        if (head != null && chain == null && previousResponseId == null
                && head.get("previousProviderResponseId") != null && head.get("context") instanceof List
                && countKept(head.get("context")) == trajectory.size()) {
            lastProviderResponseId = head.get("previousProviderResponseId").toString();
            contextBoundary = trajectory.size();
        }
        activeRunContext.addAll(context);
        activeRunContext.addAll(input);
        if (previousResponseId != null) {
            remotePendingItems = new ArrayList<>(context);
            remotePendingItems.addAll(copyItems(input));
        }
    }

    /**
     * Everything a recovery needs to run the next turn the way this client would: instructions, response options,
     * tool definitions, transport and limits. Credentials and endpoints come from the profile and are never stored.
     */
    Map<String, Object> runEnvelope() {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("instructions", systemContent);
        // the model in force now, not only an override: a profile edited before the recovery must not change it
        e.put("model", resolveModel());
        e.put("temperature", temperature);
        e.put("maxTokens", maxTokens);
        e.put("timeoutSeconds", timeoutSeconds);
        e.put("transport", transport.name());
        e.put("extraBody", extraBody);
        e.put("responseOptions", responseOptions != null ? responseOptions.asMap() : null);
        e.put("allowClientTools", allowClientTools);
        e.put("allowedEntities", new ArrayList<>(allowedEntities));
        List<Map<String, Object>> paths = new ArrayList<>();
        for (LlmFacadeImpl.AllowedPath ap : allowedPaths) {
            Map<String, Object> path = new LinkedHashMap<>();
            path.put("prefix", ap.prefix);
            path.put("methodsCsv", ap.methodsCsv);
            paths.add(path);
        }
        e.put("allowedPaths", paths);
        e.put("toolResultMaxChars", toolResultMaxChars);
        List<Map<String, Object>> toolList = new ArrayList<>();
        for (LlmTool t : tools) toolList.add(LlmToolDescriptors.describe(t));
        e.put("tools", toolList);
        if (conversation != null && conversation.usesItemModel()) e.put("conversationHeadVersion", baseHeadVersion);
        return e;
    }

    /**
     * Why a stored run cannot be resumed automatically, or null when it can. A run whose tools were supplied as caller
     * code, or whose stored tools can no longer be rebuilt, must not continue with a different set of tools.
     */
    static String unrecoverableReason(Map<String, Object> stored) {
        Object envelope = stored.get("envelope");
        if (!(envelope instanceof Map)) return null;
        Object toolList = ((Map<?, ?>) envelope).get("tools");
        if (!(toolList instanceof List)) return null;
        for (Object d : (List<?>) toolList) {
            if (!(d instanceof Map)) continue;
            if (LlmToolDescriptors.rebuild((Map<?, ?>) d) == null)
                return "Tool " + ((Map<?, ?>) d).get("name") + " (" + ((Map<?, ?>) d).get("kind")
                        + ") cannot be rebuilt after a restart; its run needs a caller that provides it";
        }
        return null;
    }

    private void applyEnvelope(Object stored) {
        if (!(stored instanceof Map)) return;
        Map<?, ?> e = (Map<?, ?>) stored;
        if (e.get("instructions") != null) systemContent = e.get("instructions").toString();
        if (e.get("model") != null) modelOverride = e.get("model").toString();
        if (e.get("temperature") instanceof Number) temperature = ((Number) e.get("temperature")).doubleValue();
        if (e.get("maxTokens") instanceof Number) maxTokens = ((Number) e.get("maxTokens")).intValue();
        if (e.get("timeoutSeconds") instanceof Number) timeoutSeconds = ((Number) e.get("timeoutSeconds")).intValue();
        if (e.get("transport") != null) {
            try { transport = org.moqui.llm.LlmTransport.valueOf(e.get("transport").toString()); }
            catch (IllegalArgumentException ignored) { }
        }
        if (e.get("extraBody") instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) e.get("extraBody");
            extraBody = new LinkedHashMap<>(body);
        }
        if (e.get("responseOptions") instanceof Map) {
            LlmResponseOptions options = new LlmResponseOptions();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) e.get("responseOptions")).entrySet())
                options.put(String.valueOf(entry.getKey()), entry.getValue());
            responseOptions = options;
        }
        allowClientTools = Boolean.TRUE.equals(e.get("allowClientTools"));
        if (e.get("conversationHeadVersion") instanceof Number) baseHeadVersion = ((Number) e.get("conversationHeadVersion")).longValue();
        if (e.get("toolResultMaxChars") instanceof Number) toolResultMaxChars = ((Number) e.get("toolResultMaxChars")).intValue();
        if (e.get("allowedEntities") instanceof List)
            for (Object name : (List<?>) e.get("allowedEntities")) allowedEntities.add(String.valueOf(name));
        if (e.get("allowedPaths") instanceof List)
            for (Object path : (List<?>) e.get("allowedPaths"))
                if (path instanceof Map) allowedPaths.add(new LlmFacadeImpl.AllowedPath(
                        String.valueOf(((Map<?, ?>) path).get("prefix")),
                        ((Map<?, ?>) path).get("methodsCsv") != null ? ((Map<?, ?>) path).get("methodsCsv").toString() : null));
        tools.clear();
        if (e.get("tools") instanceof List)
            for (Object d : (List<?>) e.get("tools"))
                if (d instanceof Map) {
                    LlmTool rebuilt = LlmToolDescriptors.rebuild((Map<?, ?>) d);
                    if (rebuilt != null) tool(rebuilt);
                }
    }

    /** The recovery job must never claim a run that is being executed: hold the lease and renew it. */
    private void takeLease() {
        stopLease();
        runLease = LlmRunLease.acquire(ec, activeRunId, "client:" + java.util.UUID.randomUUID());
        activeRunFence = runLease.getFence();
    }
    private void stopLease() {
        LlmRunLease lease = runLease;
        runLease = null;
        if (lease != null) lease.close();
    }

    void recordRunResponse(ProtocolResult result, int iteration) {
        if (activeRunId == null || result == null) return;
        if (result.outputItems != null) {
            for (LlmItem item : result.outputItems) if (item != null) activeRunContext.add(item.copy());
        } else {
            LlmMessage assistant = LlmMessage.assistant(result.content);
            assistant.toolCalls = result.toolCalls;
            activeRunContext.addAll(OpenResponsesCodec.itemsFromMessages(java.util.Collections.singletonList(assistant)));
        }
        if (remotePendingItems != null) remotePendingItems.clear();
        if (result.responseId != null) {
            lastProviderResponseId = result.responseId;
            contextBoundary = activeRunContext.size();
            // only a remote chain continues from the provider's response; a local run sends its own context
            if (remotePendingItems != null) previousResponseId = result.responseId;
        }
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("phase", "provider_response");
        checkpoint.put("localResponseId", result.localResponseId);
        checkpoint.put("providerResponseId", result.responseId);
        checkpoint.put("iteration", iteration);
        LlmRunStore.checkpoint(ec, activeRunId, activeRunFence, activeRunContext, checkpoint,
                result.responseId, iteration);
    }

    void attachRecoveredRun(Map<String, Object> stored) {
        applyEnvelope(stored.get("envelope"));
        runAttached = true;
        activeRunId = stored.get("runId") != null ? stored.get("runId").toString() : null;
        activeRunFence = stored.get("fencingToken") instanceof Number
                ? ((Number) stored.get("fencingToken")).longValue() : 0L;
        // a run claimed by recovery already holds a lease: keep renewing it while this client works
        stopLease();
        if (activeRunId != null && stored.get("workerId") != null && activeRunFence > 0L)
            runLease = LlmRunLease.start(ec, activeRunId, stored.get("workerId").toString(), activeRunFence);
        activeRunContext = OpenResponsesCodec.itemsFromStored(stored.get("context"));
        if ("LlmContRemote".equals(stored.get("continuationModeEnumId"))) {
            previousResponseId = stored.get("previousProviderResponseId") != null
                    ? stored.get("previousProviderResponseId").toString() : null;
            remotePendingItems = new ArrayList<>();
        } else previousResponseId = null;
        // a recovered turn of a conversation completes into that conversation, like the turn that was lost would have
        Object recoveredConversation = stored.get("conversationId");
        if (conversation == null && recoveredConversation != null) {
            LlmConversationImpl recovered = LlmConversationImpl.load(ec, recoveredConversation.toString(), false);
            recovered.bindProfile(profile);
            conversation = recovered;
        }
    }

    void markProviderInFlight() {
        if (activeRunId == null) return;
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("phase", "provider_in_flight");
        LlmRunStore.checkpoint(ec, activeRunId, activeRunFence, activeRunContext, checkpoint,
                previousResponseId, null);
    }

    Map<String, Object> planRunTool(LlmToolCall call, ProtocolResult result) {
        if (activeRunId == null || call == null) return null;
        Map<String, Object> args = LlmJson.tryToMap(call.arguments);
        Map<String, Object> toolRequest = new LinkedHashMap<>();
        toolRequest.put("llmResponseId", result != null ? result.localResponseId : null);
        toolRequest.put("providerCallId", call.id);
        toolRequest.put("toolName", call.name);
        toolRequest.put("arguments", args);
        toolRequest.put("idempotencyKey", activeRunId + ":" + call.id);
        Map<String, Object> planned = LlmRunStore.planTool(ec, activeRunId, toolRequest, activeRunFence > 0L ? activeRunFence : null);
        if ("LlmTiPlanned".equals(planned.get("statusId"))) {
            String workerId = "request:" + Thread.currentThread().getId();
            return LlmRunStore.claimTool(ec, planned.get("toolInvocationId").toString(), workerId, activeRunFence);
        }
        return planned;
    }

    void recordRunToolResult(Map<String, Object> invocation, LlmToolCall call, Object result, int iteration) {
        if (activeRunId == null || call == null) return;
        activeRunContext.add(LlmItem.functionCallOutput(call.id, result));
        if (remotePendingItems != null) remotePendingItems.add(LlmItem.functionCallOutput(call.id, result));
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("phase", "tool_result"); checkpoint.put("providerCallId", call.id);
        checkpoint.put("iteration", iteration);
        if (invocation != null && invocation.get("toolInvocationId") != null
                && "LlmTiRunning".equals(invocation.get("statusId"))) {
            boolean success = !(result instanceof Map && ((Map<?, ?>) result).containsKey("error"));
            LlmRunStore.completeToolAndCheckpoint(ec, invocation.get("toolInvocationId").toString(),
                    activeRunFence, result, success, activeRunContext, checkpoint);
        } else {
            LlmRunStore.checkpoint(ec, activeRunId, activeRunFence, activeRunContext, checkpoint,
                    null, iteration);
        }
    }

    void finishDurableRun(String statusId, String message) {
        if (activeRunId == null) return;
        LlmRunLease lease = runLease;
        runLease = null;
        if (lease != null) lease.close();
        if (conversation != null && conversation.usesItemModel() && LlmRunStore.COMPLETE.equals(statusId)) {
            // the run completes and becomes the head of its conversation in one transaction, or neither happens
            final String runId = activeRunId;
            conversation.persistIsolated(() -> {
                conversation.advanceHead(runId, baseHeadVersion);
                LlmRunStore.transition(ec, runId, statusId, message, activeRunFence > 0L ? activeRunFence : null);
            });
        } else {
            LlmRunStore.transition(ec, activeRunId, statusId, message, activeRunFence > 0L ? activeRunFence : null);
        }
        if (lease != null) {
            try { LlmRunStore.releaseLease(ec, activeRunId, lease.getWorkerId(), lease.getFence()); }
            catch (Throwable t) { logger.warn("Could not release the lease of LLM run " + activeRunId + ": " + t.getMessage()); }
        }
        if (conversation != null && !LlmRunStore.WAIT_CLIENT.equals(statusId)
                && !LlmRunStore.WAIT_CONFIRM.equals(statusId) && !LlmRunStore.WAIT_PROVIDER.equals(statusId))
            conversation.setAttribute("activeLlmRunId", null);
        // the run's connection is of no use once the run is over; a waiting run may continue on it
        // the connection of a run is of no use once it is over; that of a conversation outlives its turns unless the turn failed
        boolean runScoped = lastSessionKey != null && lastSessionKey.contains("|run:");
        boolean turnFailed = LlmRunStore.FAILED.equals(statusId) || LlmRunStore.CANCELLED.equals(statusId);
        if (lastSessionKey != null && !LlmRunStore.WAIT_CLIENT.equals(statusId) && !LlmRunStore.WAIT_CONFIRM.equals(statusId)
                && !LlmRunStore.WAIT_PROVIDER.equals(statusId) && (runScoped || turnFailed)) {
            try { profile.protocol.closeSession(lastSessionKey); }
            catch (Throwable t) { logger.warn("Could not close the connection of LLM run " + activeRunId + ": " + t.getMessage()); }
        }
    }

    private boolean canPersistRun() {
        return ec != null && ec.getEntity() != null && ec.getUser() != null && ec.getUser().getUserId() != null;
    }

    private static List<LlmItem> copyItems(List<LlmItem> source) {
        List<LlmItem> copy = new ArrayList<>();
        if (source != null) for (LlmItem item : source) if (item != null) copy.add(item.copy());
        return copy;
    }

    LlmResponse toResponse(ProtocolResult result, LlmFinishReason fr, long start) {
        LlmResponse r = new LlmResponse();
        r.responseId = result.responseId;
        r.object = result.responsePayload != null && result.responsePayload.get("object") != null
                ? result.responsePayload.get("object").toString() : null;
        r.status = result.status;
        r.previousResponseId = result.previousResponseId;
        r.createdAt = result.createdAt;
        r.completedAt = result.completedAt;
        r.outputItems = result.outputItems;
        r.options = result.responseOptions;
        r.error = result.responsePayload != null
                ? LlmRetryClassifier.asStringObjectMap(result.responsePayload.get("error")) : null;
        r.incompleteDetails = result.responsePayload != null
                ? LlmRetryClassifier.asStringObjectMap(result.responsePayload.get("incomplete_details")) : null;
        r.payload = result.responsePayload;
        r.content = result.content;
        r.refusal = result.refusal;
        r.metadata = result.metadata;
        r.finishReason = fr;
        r.toolCalls = result.toolCalls;
        r.usage = result.usage;
        r.model = result.model != null ? result.model : resolveModel();
        r.profileName = profile.name;
        r.conversationId = convId();
        r.runId = result.runId != null ? result.runId : activeRunId;
        r.requestId = result.localRequestId;
        r.httpStatus = result.httpStatus;
        r.errorMessage = result.errorMessage;
        r.providerErrorCode = result.providerErrorCode;
        r.providerErrorType = result.providerErrorType;
        r.providerErrorParam = result.providerErrorParam;
        r.durationMs = System.currentTimeMillis() - start;
        r.rawJson = profile.logContent ? result.rawJson : null;
        r.yielded = false;
        return r;
    }

    private void applyWindowPolicyToConversation() {
        if (conversation != null && windowPolicy != null) conversation.setWindowPolicy(windowPolicy);
    }

    /** The run the current turn is recorded in, once it has begun. */
    public String currentRunId() { return activeRunId; }
    public String convId() { return conversation != null ? conversation.getConversationId() : null; }
    public int ssePingSeconds() { return profile != null ? profile.ssePingSeconds : 15; }

    void sleepBackoff(float waitSeconds) {
        if (waitSeconds <= 0) return;
        try {
            Thread.sleep(Math.round(waitSeconds * 1000.0f));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("LLM retry sleep interrupted", e, LlmFinishReason.ERROR, 0, profile.name, convId());
        }
    }

    float nextWait(float waitSeconds) {
        float initial = profile.retryInitialSeconds > 0 ? profile.retryInitialSeconds : 2.0f;
        if (waitSeconds <= 0) return initial;
        return waitSeconds * initial;
    }

    private static String nvl(String v, String def) { return v == null || v.isBlank() ? def : v; }

    static boolean isMaxIterations(Throwable t) {
        return t instanceof LlmException && ((LlmException) t).getReason() == LlmFinishReason.MAX_ITERATIONS;
    }

    private static UnsupportedOperationException uoe(String method) {
        return new UnsupportedOperationException("LlmClient." + method + " is not yet implemented");
    }

    void markStreamingPersisted() { this.streamingWasPersisted = true; }
    LlmClientImpl markResumeFromYielded() { this.resumeFromYielded = true; return this; }

    boolean isExternallyCancelled() {
        if (conversation != null && LlmConversationImpl.STATUS_CANCELLED.equals(conversation.getStatus()))
            return true;
        // another worker took the run over: stop instead of acting on a run this client no longer owns
        if (runLease != null && runLease.isLost()) return true;
        // a background run has no conversation to carry the cancel flag: ask the run itself
        if (conversation == null && activeRunId != null && ec != null && LlmRunStore.isCancelRequested(ec, activeRunId))
            return true;
        LlmFacadeImpl f = facadeOrNull();
        return f != null && f.isCancelled(convId());
    }
    void throwIfCancelled() {
        if (isExternallyCancelled()) throw new CancellationException("LLM conversation cancelled");
    }

    /** Track the provider stream and tell the listener once its response headers arrive. */
    void bindUpstreamOpen(ProtocolRequest req, LlmStreamListener listener) {
        req.onStreamOpen = stream -> {
            registerInFlight(stream);
            if (listener != null) listener.onUpstreamOpen();
        };
    }
    void registerInFlight(RestClient.RestStream stream) {
        this.activeStream = stream;
        LlmFacadeImpl f = facadeOrNull();
        if (f != null) f.registerInFlight(convId(), stream);
    }
    void unregisterInFlight() {
        RestClient.RestStream s = activeStream;
        activeStream = null;
        LlmFacadeImpl f = facadeOrNull();
        if (f != null) f.unregisterInFlight(convId(), s);
    }
    public void abortActiveStream() {
        RestClient.RestStream s = activeStream;
        if (s != null) {
            try { s.close(); } catch (Throwable ignored) { }
        }
        LlmFacadeImpl f = facadeOrNull();
        if (f != null) f.abortInFlight(convId());
    }
    private LlmFacadeImpl facadeOrNull() {
        if (ec == null) return null;
        try {
            if (ec.getLlm() instanceof LlmFacadeImpl) return (LlmFacadeImpl) ec.getLlm();
        } catch (Throwable ignored) { }
        return null;
    }

    /** A tool (suspend#CurrentLlmRun) put the run into a waiting state; the loop must stop without finishing it. */
    boolean runSuspendedByTool() {
        if (activeRunId == null || ec == null) return false;
        String status = LlmRunStore.runStatus(ec, activeRunId);
        return LlmRunStore.WAIT_CONFIRM.equals(status) || LlmRunStore.WAIT_CLIENT.equals(status);
    }

    boolean hasResumeResults() { return !resumeToolResults.isEmpty(); }

    int maxIterationsEffective() {
        if (maxIterations != null && maxIterations > 0) return maxIterations;
        return DEFAULT_MAX_ITERATIONS;
    }

    /** Nested sim agent: same profile/protocol and parent tools except write_ui / enter_sim / find_skill / pin. */
    LlmClientImpl nestForSim(int maxIter) {
        LlmClientImpl nested = new LlmClientImpl(ec, profile, transactionInPlace);
        nested.maxIterations(maxIter > 0 ? maxIter : 32);
        nested.activeSkillName = activeSkillName;
        nested.allowedPaths.addAll(allowedPaths);
        nested.allowedEntities.addAll(allowedEntities);
        for (LlmTool t : tools) {
            if (t == null) continue;
            String n = t.getName();
            if (WriteUiTool.NAME.equals(n) || EnterSimTool.NAME.equals(n) || FindSkillTool.NAME.equals(n)
                    || PinTool.NAME.equals(n)) continue;
            if (t instanceof RequestTool) {
                boolean unprefixed = profile != null && profile.allowUnprefixedRequest;
                LlmTool rt = LlmGateway.requestToolForServlet(allowedPaths, unprefixed);
                if (rt != null) nested.tool(rt);
            } else {
                nested.tool(t);
            }
        }
        return nested;
    }

    /** The JSON schema this turn asked the model to follow (text.format json_schema), or null when it asked for none. */
    Object structuredOutputSchema() {
        Object text = responseOptions != null && responseOptions.isPresent("text") ? responseOptions.get("text")
                : extraBody != null ? extraBody.get("text") : null;
        if (!(text instanceof Map)) return null;
        Object format = ((Map<?, ?>) text).get("format");
        if (format instanceof Map && "json_schema".equals(((Map<?, ?>) format).get("type"))) return ((Map<?, ?>) format).get("schema");
        return null;
    }

    /** The tool_choice of this request as set by the caller (options first, then the extra body); null when there is none. */
    Object requestedToolChoice() {
        if (responseOptions != null && responseOptions.isPresent("tool_choice")) return responseOptions.get("tool_choice");
        return extraBody != null ? extraBody.get("tool_choice") : null;
    }

    LlmTool findTool(String name) {
        if (name == null) return null;
        for (LlmTool t : tools) {
            if (t != null && name.equals(t.getName())) return t;
        }
        return null;
    }

    Object truncateResult(Object result) {
        return ToolResultTrim.limit(result, toolResultMaxChars);
    }

    private void applyAllowLists(LlmTool tool) {
        if (tool instanceof RequestTool) {
            RequestTool rt = (RequestTool) tool;
            for (LlmFacadeImpl.AllowedPath ap : allowedPaths) rt.addAllowedPath(ap.prefix, ap.methodsCsv);
        } else if (tool instanceof WriteUiTool) {
            WriteUiTool wt = (WriteUiTool) tool;
            for (String ent : allowedEntities) wt.addAllowedEntity(ent);
        }
    }
}
