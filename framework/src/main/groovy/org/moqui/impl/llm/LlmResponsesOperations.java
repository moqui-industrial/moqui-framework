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

import org.moqui.context.ExecutionContext;
import org.moqui.entity.EntityValue;
import org.moqui.llm.LlmClient;
import org.moqui.llm.LlmCompactResult;
import org.moqui.llm.LlmException;
import org.moqui.llm.LlmItem;
import org.moqui.llm.LlmProtocol;
import org.moqui.llm.LlmResponseOptions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owner-scoped local services for optional OpenAI Responses resource operations. */
public final class LlmResponsesOperations {
    private LlmResponsesOperations() { }

    public static Map<String, Object> retrieve(ExecutionContext ec, String profileName, String responseId) {
        LlmClientImpl client = client(ec, profileName, LlmProtocol.Capability.RETRIEVE);
        requireOwnedResponse(ec, client.profile.name, responseId);
        return resultMap(OpenResponsesProtocol.retrieve(request(client, null, null), responseId));
    }

    public static Map<String, Object> cancel(ExecutionContext ec, String profileName, String responseId) {
        LlmClientImpl client = client(ec, profileName, LlmProtocol.Capability.CANCEL);
        requireOwnedResponse(ec, client.profile.name, responseId);
        return resultMap(OpenResponsesProtocol.cancel(request(client, null, null), responseId));
    }

    public static Map<String, Object> inputItems(ExecutionContext ec, String profileName, String responseId) {
        return inputItems(ec, profileName, responseId, null);
    }

    public static Map<String, Object> inputItems(ExecutionContext ec, String profileName, String responseId, Map<String, Object> paging) {
        LlmClientImpl client = client(ec, profileName, LlmProtocol.Capability.LIST_INPUT_ITEMS);
        requireOwnedResponse(ec, client.profile.name, responseId);
        return OpenResponsesProtocol.inputItems(request(client, null, null), responseId, paging);
    }

    public static Map<String, Object> inputTokens(ExecutionContext ec, String profileName,
            List<LlmItem> inputItems, LlmResponseOptions options) {
        LlmClientImpl client = client(ec, profileName, LlmProtocol.Capability.COUNT_INPUT_TOKENS);
        return OpenResponsesProtocol.inputTokens(request(client, inputItems, options));
    }

    public static Map<String, Object> compact(ExecutionContext ec, String profileName,
            List<LlmItem> inputItems, LlmResponseOptions options) {
        LlmClientImpl client = client(ec, profileName, LlmProtocol.Capability.COMPACT);
        if (inputItems != null) client.inputItems(inputItems);
        if (options != null) client.responseOptions(options);
        LlmCompactResult result = client.compact();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", result.id);
        map.put("object", result.object);
        map.put("createdAt", result.createdAt);
        map.put("output", result.output);
        map.put("usage", result.usage);
        map.put("payload", result.payload);
        return map;
    }

    private static LlmClientImpl client(ExecutionContext ec, String profileName, LlmProtocol.Capability capability) {
        if (ec == null || ec.getUser() == null || ec.getUser().getUserId() == null)
            throw new LlmException("Authenticated user is required for Responses operations");
        LlmClient llmClient = ec.getLlm().getClient(profileName);
        if (!(llmClient instanceof LlmClientImpl)) throw new LlmException("Unsupported LLM client implementation");
        LlmClientImpl client = (LlmClientImpl) llmClient;
        if (!client.profile.protocol.getCapabilities().contains(capability))
            throw new LlmException("LLM profile '" + client.profile.name + "' does not support " + capability);
        return client;
    }

    private static LlmProtocol.ProtocolRequest request(LlmClientImpl client, List<LlmItem> items,
            LlmResponseOptions options) {
        if (items != null) client.inputItems(items);
        if (options != null) client.responseOptions(options);
        return client.buildRequest(client.profile.model, Collections.emptyList());
    }

    private static void requireOwnedResponse(ExecutionContext ec, String profileName, String responseId) {
        if (responseId == null || responseId.isBlank()) throw new LlmException("responseId is required");
        boolean alreadyDisabled = ec.getArtifactExecution().disableAuthz();
        EntityValue response;
        try {
            response = ec.getEntity().find("moqui.llm.LlmResponse")
                    .condition("profileName", profileName).condition("providerResponseId", responseId)
                    .condition("ownerUserId", ec.getUser().getUserId()).useCache(false).one();
        } finally {
            if (!alreadyDisabled) ec.getArtifactExecution().enableAuthz();
        }
        if (response == null) throw new LlmException("LLM response not found");
    }

    private static Map<String, Object> resultMap(LlmProtocol.ProtocolResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("responseId", result.responseId);
        map.put("status", result.status);
        map.put("finishReason", result.finishReason != null ? result.finishReason.name() : null);
        map.put("content", result.content);
        map.put("outputItems", result.outputItems);
        map.put("usage", result.usage);
        map.put("errorMessage", result.errorMessage);
        map.put("httpStatus", result.httpStatus);
        map.put("payload", result.responsePayload);
        return map;
    }
}
