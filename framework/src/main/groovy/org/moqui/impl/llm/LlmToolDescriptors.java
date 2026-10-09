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

import org.moqui.llm.LlmTool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * What is stored about a run's tools so recovery can offer the model the same tools. A tool is rebuilt only when it
 * can be recreated exactly: a service tool from its service name and alias, a client pass-through tool from its
 * definition, a built-in tool from its class. Any other tool (code supplied by the caller) is recorded as not
 * rebuildable and a run that used it is never resumed automatically.
 */
final class LlmToolDescriptors {
    private static final Set<String> BUILT_INS = Set.of(
            "org.moqui.impl.llm.RequestTool", "org.moqui.impl.llm.WriteUiTool", "org.moqui.impl.llm.BrowseTool",
            "org.moqui.impl.llm.RunServiceTool", "org.moqui.impl.llm.FindSkillTool", "org.moqui.impl.llm.EnterSimTool",
            "org.moqui.impl.llm.PinTool", "org.moqui.impl.llm.FindBasicTool");

    private LlmToolDescriptors() { }

    static Map<String, Object> describe(LlmTool tool) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", tool.getName());
        d.put("description", tool.getDescription());
        d.put("parametersSchema", tool.getParametersSchema());
        d.put("execution", tool.getExecution() != null ? tool.getExecution().name() : null);
        if (tool.getStrict() != null) d.put("strict", tool.getStrict());
        String className = tool.getClass().getName();
        if (tool instanceof ServiceCallTool) {
            d.put("kind", "service");
            d.put("serviceName", ((ServiceCallTool) tool).getServiceName());
        } else if (tool instanceof ClientPassThroughTool) {
            d.put("kind", "client");
        } else if (BUILT_INS.contains(className)) {
            d.put("kind", "builtin");
            d.put("className", className);
        } else {
            d.put("kind", "custom");
            d.put("className", className);
        }
        return d;
    }

    /** The tool recreated from its descriptor, or null when it cannot be recreated exactly. */
    static LlmTool rebuild(Map<?, ?> d) {
        if (d == null) return null;
        String kind = String.valueOf(d.get("kind"));
        try {
            switch (kind) {
                case "service":
                    return new ServiceCallTool(String.valueOf(d.get("serviceName")), String.valueOf(d.get("name")));
                case "client": {
                    Object schema = d.get("parametersSchema");
                    @SuppressWarnings("unchecked") Map<String, Object> map = schema instanceof Map ? (Map<String, Object>) schema : null;
                    return new ClientPassThroughTool(String.valueOf(d.get("name")),
                            d.get("description") != null ? d.get("description").toString() : null, map,
                            d.get("strict") instanceof Boolean ? (Boolean) d.get("strict") : null);
                }
                case "builtin": {
                    String className = String.valueOf(d.get("className"));
                    if (!BUILT_INS.contains(className)) return null;
                    return (LlmTool) Class.forName(className).getDeclaredConstructor().newInstance();
                }
                default:
                    return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }
}
