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

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The check of a JSON value against the JSON Schema that the caller asked a model to follow (text.format json_schema). It
 * is independent of the model and of the provider: the answer of a model is a claim until this has looked at it. The subset
 * is the one structured outputs use: type, enum, const, properties, required, additionalProperties, items, anyOf, oneOf,
 * allOf, local references, numeric and string limits.
 */
final class JsonSchemaCheck {
    private final JsonNode root;
    private JsonSchemaCheck(JsonNode root) { this.root = root; }

    /** The problems of {@code json} against {@code schema}; empty means it is valid. A text that is not JSON is one problem. */
    static List<String> problems(Object schema, String json) {
        List<String> problems = new ArrayList<>();
        JsonNode value;
        try { value = LlmJson.exact.readTree(json); }
        catch (Exception e) { problems.add("the answer is not JSON: " + e.getMessage()); return problems; }
        if (value == null) { problems.add("the answer is empty"); return problems; }
        JsonNode schemaNode = LlmJson.exact.valueToTree(schema);
        new JsonSchemaCheck(schemaNode).check(schemaNode, value, "$", problems, 0);
        return problems;
    }

    private JsonNode resolve(JsonNode node) {
        int guard = 0;
        while (node != null && node.has("$ref") && guard++ < 50) {
            String ref = node.get("$ref").asText();
            if (ref.equals("#")) { node = root; continue; }
            if (!ref.startsWith("#/")) return null;
            JsonNode target = root;
            for (String part : ref.substring(2).split("/")) {
                target = target.get(part.replace("~1", "/").replace("~0", "~"));
                if (target == null) return null;
            }
            node = target;
        }
        return node;
    }

    private boolean matches(JsonNode schema, JsonNode value, int depth) {
        return check(schema, value, "$", new ArrayList<>(), depth);
    }

    private boolean check(JsonNode raw, JsonNode value, String path, List<String> problems, int depth) {
        if (depth > 60) { problems.add(path + " is nested too deeply"); return false; }
        JsonNode schema = resolve(raw);
        if (schema == null || schema.isBoolean()) return schema == null || schema.asBoolean();
        int before = problems.size();
        if (schema.has("allOf")) for (JsonNode part : schema.get("allOf")) check(part, value, path, problems, depth + 1);
        if (schema.has("anyOf")) {
            boolean any = false;
            for (JsonNode branch : schema.get("anyOf")) if (matches(branch, value, depth + 1)) { any = true; break; }
            if (!any) problems.add(path + " matches none of the allowed forms");
        }
        if (schema.has("oneOf")) {
            int n = 0;
            for (JsonNode branch : schema.get("oneOf")) if (matches(branch, value, depth + 1)) n++;
            if (n != 1) problems.add(path + (n == 0 ? " matches none of the allowed forms" : " matches more than one of the allowed forms"));
        }
        if (schema.has("const") && !schema.get("const").equals(value)) problems.add(path + " is not the required constant");
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode allowed : schema.get("enum")) if (allowed.equals(value)) { found = true; break; }
            if (!found) problems.add(path + " is not one of the allowed values");
        }
        if (schema.has("type") && !typeOk(schema.get("type"), value)) {
            problems.add(path + " is " + kind(value) + ", the schema requires " + schema.get("type").toString());
            return false;
        }
        if (value.isNumber()) {
            BigDecimal n = value.decimalValue();
            if (schema.has("minimum") && n.compareTo(schema.get("minimum").decimalValue()) < 0) problems.add(path + " is below the minimum");
            if (schema.has("maximum") && n.compareTo(schema.get("maximum").decimalValue()) > 0) problems.add(path + " is above the maximum");
        }
        if (value.isTextual()) {
            int len = value.textValue().codePointCount(0, value.textValue().length());
            if (schema.has("minLength") && len < schema.get("minLength").asInt()) problems.add(path + " is shorter than allowed");
            if (schema.has("maxLength") && len > schema.get("maxLength").asInt()) problems.add(path + " is longer than allowed");
            if (schema.has("pattern") && !Pattern.compile(schema.get("pattern").asText()).matcher(value.textValue()).find())
                problems.add(path + " does not match the pattern");
        }
        if (value.isObject()) {
            JsonNode properties = schema.get("properties");
            if (schema.has("required")) for (JsonNode name : schema.get("required"))
                if (!value.has(name.asText())) problems.add(path + "." + name.asText() + " is required");
            java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                java.util.Map.Entry<String, JsonNode> field = fields.next();
                JsonNode declared = properties != null ? properties.get(field.getKey()) : null;
                if (declared != null) check(declared, field.getValue(), path + "." + field.getKey(), problems, depth + 1);
                else if (schema.has("additionalProperties")) {
                    JsonNode extra = schema.get("additionalProperties");
                    if (extra.isBoolean() && !extra.asBoolean()) problems.add(path + "." + field.getKey() + " is not allowed");
                    else if (extra.isObject()) check(extra, field.getValue(), path + "." + field.getKey(), problems, depth + 1);
                }
            }
        }
        if (value.isArray() && schema.has("items")) {
            int i = 0;
            for (JsonNode element : value) check(schema.get("items"), element, path + "[" + i++ + "]", problems, depth + 1);
            if (schema.has("minItems") && value.size() < schema.get("minItems").asInt()) problems.add(path + " has too few items");
            if (schema.has("maxItems") && value.size() > schema.get("maxItems").asInt()) problems.add(path + " has too many items");
        }
        return problems.size() == before;
    }

    private static boolean typeOk(JsonNode type, JsonNode value) {
        if (type.isArray()) { for (JsonNode t : type) if (typeOk(t, value)) return true; return false; }
        switch (type.asText()) {
            case "object": return value.isObject();
            case "array": return value.isArray();
            case "string": return value.isTextual();
            case "boolean": return value.isBoolean();
            case "null": return value.isNull();
            case "integer": return value.isIntegralNumber() || (value.isNumber() && value.decimalValue().stripTrailingZeros().scale() <= 0);
            case "number": return value.isNumber();
            default: return true;
        }
    }

    private static String kind(JsonNode value) {
        if (value.isObject()) return "an object";
        if (value.isArray()) return "an array";
        if (value.isTextual()) return "a string";
        if (value.isBoolean()) return "a boolean";
        if (value.isNull()) return "null";
        return "a number";
    }
}
