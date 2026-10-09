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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LlmItem {
    public String itemId;
    public String sourceItemId;
    public String providerItemId;
    public String type;
    public String status;
    public String role;
    public String phase;
    public String callId;
    public String name;
    public String arguments;
    public Object output;
    public String encryptedContent;
    public String createdBy;
    public String referenceId;
    public List<LlmContentPart> content;
    public List<LlmContentPart> summary;
    public Map<String, Object> payload;
    /** True for application context of one turn (session facts, pins, skills): sent with that turn, never part of the history that continues. Never on the wire. */
    public Boolean ephemeral;

    public static LlmItem message(String role, List<LlmContentPart> content) {
        LlmItem item = new LlmItem();
        item.type = "message";
        item.role = role;
        item.content = content;
        return item;
    }

    public static LlmItem functionCall(String callId, String name, String arguments) {
        LlmItem item = new LlmItem();
        item.type = "function_call";
        item.callId = callId;
        item.name = name;
        item.arguments = arguments;
        return item;
    }

    public static LlmItem functionCallOutput(String callId, Object output) {
        LlmItem item = new LlmItem();
        item.type = "function_call_output";
        item.callId = callId;
        item.output = output;
        return item;
    }

    public LlmItem copy() {
        LlmItem copy = new LlmItem();
        copy.itemId = itemId;
        copy.sourceItemId = sourceItemId;
        copy.providerItemId = providerItemId;
        copy.type = type;
        copy.status = status;
        copy.role = role;
        copy.phase = phase;
        copy.callId = callId;
        copy.name = name;
        copy.arguments = arguments;
        copy.output = output;
        copy.encryptedContent = encryptedContent;
        copy.createdBy = createdBy;
        copy.referenceId = referenceId;
        copy.content = copyParts(content);
        copy.summary = copyParts(summary);
        if (payload != null) copy.payload = new LinkedHashMap<>(payload);
        copy.ephemeral = ephemeral;
        return copy;
    }

    private static List<LlmContentPart> copyParts(List<LlmContentPart> values) {
        if (values == null) return null;
        List<LlmContentPart> copies = new ArrayList<>();
        for (LlmContentPart value : values) copies.add(value != null ? value.copy() : null);
        return copies;
    }
}
