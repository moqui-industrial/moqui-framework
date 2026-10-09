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

import java.util.List;
import java.util.Map;

/**
 * The rules a function schema must meet to be sent as strict: every object closed (additionalProperties false) and every
 * property required, in the schema and in everything it contains, with local references ({@code #/$defs/x},
 * {@code #/definitions/x}) followed once and a cycle not walked again. Anything else is refused with the path of the
 * first thing that breaks the rule; the schema is not changed.
 */
final class StrictToolSchema {
    private StrictToolSchema() { }

    static void require(String toolName, Map<String, Object> schema) {
        if (schema == null || schema.isEmpty())
            throw new LlmException("Tool " + toolName + " is strict and has no parameters schema");
        check(toolName, schema, schema, "parameters", new java.util.HashSet<>(), 0);
    }

    @SuppressWarnings("unchecked")
    private static void check(String tool, Map<String, Object> root, Object node, String path, java.util.Set<String> followed, int depth) {
        if (!(node instanceof Map) || depth > 40) return;
        Map<String, Object> schema = (Map<String, Object>) node;
        Object ref = schema.get("$ref");
        if (ref instanceof String) {
            String r = (String) ref;
            if (!followed.add(r)) return;
            Object target = resolve(root, r);
            if (target == null) throw new LlmException("Tool " + tool + " is strict and " + path + " refers to " + r + ", which is not in its schema");
            check(tool, root, target, path + "->" + r, followed, depth + 1);
            return;
        }
        boolean isObject = "object".equals(schema.get("type")) || schema.get("properties") instanceof Map
                || (schema.get("type") instanceof List && ((List<?>) schema.get("type")).contains("object"));
        if (isObject) {
            if (!Boolean.FALSE.equals(schema.get("additionalProperties")))
                throw new LlmException("Tool " + tool + " is strict and " + path + " does not set additionalProperties to false");
            Object properties = schema.get("properties");
            List<?> required = schema.get("required") instanceof List ? (List<?>) schema.get("required") : java.util.Collections.emptyList();
            if (properties instanceof Map) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) properties).entrySet()) {
                    if (!required.contains(e.getKey()))
                        throw new LlmException("Tool " + tool + " is strict and " + path + " does not require the property " + e.getKey());
                    check(tool, root, e.getValue(), path + "." + e.getKey(), followed, depth + 1);
                }
            }
        }
        if (schema.get("items") != null) check(tool, root, schema.get("items"), path + "[]", followed, depth + 1);
        for (String key : new String[]{"anyOf", "oneOf", "allOf"})
            if (schema.get(key) instanceof List) {
                int i = 0;
                for (Object branch : (List<?>) schema.get(key)) check(tool, root, branch, path + "." + key + "[" + (i++) + "]", followed, depth + 1);
            }
        for (String key : new String[]{"$defs", "definitions"})
            if (schema.get(key) instanceof Map && schema == root) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) schema.get(key)).entrySet())
                    check(tool, root, e.getValue(), path + "." + key + "." + e.getKey(), followed, depth + 1);
            }
    }

    @SuppressWarnings("unchecked")
    private static Object resolve(Map<String, Object> root, String ref) {
        if (!ref.startsWith("#/")) return null;
        Object cur = root;
        for (String part : ref.substring(2).split("/")) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<String, Object>) cur).get(part.replace("~1", "/").replace("~0", "~"));
        }
        return cur;
    }
}
