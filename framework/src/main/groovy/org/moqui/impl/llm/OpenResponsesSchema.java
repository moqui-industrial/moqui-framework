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

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The pinned Open Responses OpenAPI document, and a validator of the subset of JSON Schema it uses (types, enums, ranges,
 * lengths, required, properties, additionalProperties, items, anyOf, oneOf, allOf, $ref). A request is checked against the
 * schema of the contract itself, not against a list of fields someone chose to remember: a number where the schema wants
 * an integer, a value outside an enum, a string where a boolean belongs, a union that matches none of its branches.
 * oneOf requires exactly one match, as the jsonschema library does, so a body this refuses is a body that library refuses.
 */
final class OpenResponsesSchema {
    static final String RESOURCE = "/org/moqui/impl/llm/openapi-2026-04-24.json";
    private static volatile OpenResponsesSchema instance;

    private final JsonNode schemas;

    private OpenResponsesSchema(JsonNode root) { this.schemas = root.path("components").path("schemas"); }

    static OpenResponsesSchema get() {
        OpenResponsesSchema local = instance;
        if (local == null) {
            synchronized (OpenResponsesSchema.class) {
                if (instance == null) {
                    try (InputStream in = OpenResponsesSchema.class.getResourceAsStream(RESOURCE)) {
                        if (in == null) throw new IllegalStateException("The Open Responses schema is missing from the classpath: " + RESOURCE);
                        instance = new OpenResponsesSchema(LlmJson.mapper.readTree(in));
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException("The Open Responses schema cannot be read", e);
                    }
                }
                local = instance;
            }
        }
        return local;
    }

    JsonNode schema(String name) { return schemas.get(name); }

    /** The problems of a value against a named schema of the contract; an empty list means it is valid. */
    List<String> validate(String schemaName, Object value) {
        List<String> problems = new ArrayList<>();
        JsonNode schema = schemas.get(schemaName);
        if (schema == null) throw new IllegalArgumentException("No schema " + schemaName + " in the Open Responses contract");
        check(schema, LlmJson.exact.valueToTree(value), "", problems, 0);
        return problems;
    }

    /** The problems of one property of a named object schema; a property the schema does not know is not a problem. */
    List<String> validateProperty(String schemaName, String property, Object value) {
        List<String> problems = new ArrayList<>();
        JsonNode schema = schemas.get(schemaName);
        JsonNode prop = schema != null ? schema.path("properties").get(property) : null;
        if (prop == null) return problems;
        check(prop, LlmJson.exact.valueToTree(value), property, problems, 0);
        return problems;
    }

    /**
     * A copy of a value cut down to what the named schema declares: what a response returned and a request does not take
     * back (a status of a reasoning item, log probabilities of an output text, who created an item) is left out, what the
     * schema declares is kept as it is, nulls included. A union is resolved by its {@code type} (and {@code role}) tags;
     * a value no branch recognizes, such as a provider extension, is copied whole. The value itself is never changed.
     */
    Object project(String schemaName, Object value) {
        JsonNode schema = schemas.get(schemaName);
        if (schema == null) throw new IllegalArgumentException("No schema " + schemaName + " in the Open Responses contract");
        JsonNode projected = projectNode(schema, LlmJson.exact.valueToTree(value), 0);
        try { return LlmJson.exact.treeToValue(projected, Object.class); }
        catch (java.io.IOException e) { throw new IllegalStateException("The projected value cannot be read back", e); }
    }

    private JsonNode projectNode(JsonNode rawSchema, JsonNode value, int depth) {
        JsonNode schema = resolve(rawSchema);
        if (schema == null || schema.isBoolean() || value == null || depth > 60) return value;
        for (String key : new String[] {"oneOf", "anyOf"}) {
            if (!schema.has(key) || !schema.get(key).isArray()) continue;
            JsonNode branch = branchFor(schema.get(key), value, depth);
            if (branch != null) return projectNode(branch, value, depth + 1);
            return value;
        }
        if (value.isArray()) {
            JsonNode items = schema.get("items");
            if (items == null) return value;
            com.fasterxml.jackson.databind.node.ArrayNode out = LlmJson.exact.createArrayNode();
            for (JsonNode element : value) out.add(projectNode(items, element, depth + 1));
            return out;
        }
        if (!value.isObject()) return value;
        java.util.Map<String, JsonNode> declared = new java.util.LinkedHashMap<>();
        collectProperties(schema, declared, 0);
        if (declared.isEmpty()) return value;
        com.fasterxml.jackson.databind.node.ObjectNode out = LlmJson.exact.createObjectNode();
        java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = value.fields();
        while (fields.hasNext()) {
            java.util.Map.Entry<String, JsonNode> field = fields.next();
            JsonNode propertySchema = declared.get(field.getKey());
            if (propertySchema == null) continue;
            out.set(field.getKey(), projectNode(propertySchema, field.getValue(), depth + 1));
        }
        return out;
    }

    private void collectProperties(JsonNode rawSchema, java.util.Map<String, JsonNode> into, int depth) {
        JsonNode schema = resolve(rawSchema);
        if (schema == null || depth > 20) return;
        if (schema.has("allOf")) for (JsonNode part : schema.get("allOf")) collectProperties(part, into, depth + 1);
        JsonNode properties = schema.get("properties");
        if (properties != null) {
            java.util.Iterator<java.util.Map.Entry<String, JsonNode>> it = properties.fields();
            while (it.hasNext()) { java.util.Map.Entry<String, JsonNode> e = it.next(); into.putIfAbsent(e.getKey(), e.getValue()); }
        }
    }

    /** The branch of a union a value belongs to, by its tags first and then by validity; null when none is recognized. */
    private JsonNode branchFor(JsonNode branches, JsonNode value, int depth) {
        java.util.List<JsonNode> candidates = new ArrayList<>();
        for (JsonNode branch : branches) {
            JsonNode resolved = resolve(branch);
            if (resolved == null) continue;
            if (value.isObject()) {
                if (!tagAllows(resolved, "type", value) || !tagAllows(resolved, "role", value)) continue;
                if (!resolved.path("properties").has("type") && !resolved.has("oneOf") && !resolved.has("anyOf") && !resolved.has("allOf")) continue;
            } else if (value.isArray()) {
                if (!resolved.has("type") || !typeOk(resolved.get("type"), value)) continue;
            } else if (value.isNull()) {
                if (resolved.has("type") && typeOk(resolved.get("type"), value)) return branch;
                continue;
            } else if (!(resolved.has("type") && typeOk(resolved.get("type"), value))) continue;
            candidates.add(branch);
        }
        if (candidates.size() == 1) return candidates.get(0);
        for (JsonNode candidate : candidates) if (matches(candidate, value, depth + 1)) return candidate;
        return null;
    }

    /** True unless the branch declares the values of the tag and the value carries one that is not among them. */
    private boolean tagAllows(JsonNode branch, String tag, JsonNode value) {
        JsonNode actual = value.get(tag);
        if (actual == null || actual.isNull()) return true;
        java.util.List<JsonNode> declared = new ArrayList<>();
        collectEnum(branch.path("properties").get(tag), declared, 0);
        if (declared.isEmpty()) return true;
        for (JsonNode allowed : declared) if (allowed.equals(actual)) return true;
        return false;
    }

    private void collectEnum(JsonNode raw, java.util.List<JsonNode> into, int depth) {
        JsonNode schema = resolve(raw);
        if (schema == null || depth > 10) return;
        if (schema.has("enum")) for (JsonNode allowed : schema.get("enum")) into.add(allowed);
        for (String key : new String[] {"anyOf", "oneOf", "allOf"})
            if (schema.has(key)) for (JsonNode part : schema.get(key)) collectEnum(part, into, depth + 1);
    }

    private JsonNode resolve(JsonNode node) {
        int guard = 0;
        while (node != null && node.has("$ref") && guard++ < 50) {
            String ref = node.get("$ref").asText();
            String prefix = "#/components/schemas/";
            if (!ref.startsWith(prefix)) throw new IllegalStateException("Unsupported reference " + ref);
            node = schemas.get(ref.substring(prefix.length()));
        }
        return node;
    }

    /** True when the value is valid for the schema; used to pick among the branches of a union. */
    private boolean matches(JsonNode schema, JsonNode value, int depth) {
        return check(schema, value, "", new ArrayList<>(), depth);
    }

    private boolean check(JsonNode rawSchema, JsonNode value, String path, List<String> problems, int depth) {
        if (depth > 60) { problems.add(at(path) + "is nested too deeply"); return false; }
        JsonNode schema = resolve(rawSchema);
        if (schema == null || schema.isBoolean()) return true;
        int before = problems.size();

        if (schema.has("allOf")) for (JsonNode part : schema.get("allOf")) check(part, value, path, problems, depth + 1);
        if (schema.has("anyOf")) {
            boolean any = false;
            for (JsonNode branch : schema.get("anyOf")) if (matches(branch, value, depth + 1)) { any = true; break; }
            JsonNode only = onlyNonNullBranch(schema.get("anyOf"));
            if (!any && only != null && !value.isNull()) {
                // "this or null": the value is not null, so say what is wrong with it against the one real form
                check(only, value, path, problems, depth + 1);
            } else if (!any && sameKindBranch(schema.get("anyOf"), value) != null) {
                // of several forms only one is of the kind of the value: say what is wrong inside it
                check(sameKindBranch(schema.get("anyOf"), value), value, path, problems, depth + 1);
            } else if (!any) problems.add(at(path) + "matches none of the allowed forms (" + describe(schema.get("anyOf")) + ")" + hint(schema.get("anyOf"), value, depth));
        }
        if (schema.has("oneOf")) {
            int matched = 0;
            for (JsonNode branch : schema.get("oneOf")) if (matches(branch, value, depth + 1)) matched++;
            if (matched == 0 && sameKindBranch(schema.get("oneOf"), value) != null)
                check(sameKindBranch(schema.get("oneOf"), value), value, path, problems, depth + 1);
            else if (matched == 0) problems.add(at(path) + "matches none of the allowed forms (" + describe(schema.get("oneOf")) + ")" + hint(schema.get("oneOf"), value, depth));
            else if (matched > 1) problems.add(at(path) + "matches more than one of the allowed forms");
        }
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode allowed : schema.get("enum")) if (allowed.equals(value)) { found = true; break; }
            if (!found) problems.add(at(path) + "is " + show(value) + ", the contract allows " + enumValues(schema.get("enum")));
        }
        if (schema.has("type") && !typeOk(schema.get("type"), value)) {
            problems.add(at(path) + "is " + kind(value) + ", the contract requires " + schema.get("type").asText());
            return false;
        }
        if (value.isNumber()) {
            BigDecimal n = value.decimalValue();
            if (schema.has("minimum") && n.compareTo(schema.get("minimum").decimalValue()) < 0)
                problems.add(at(path) + "is " + n.toPlainString() + ", the contract requires at least " + schema.get("minimum").asText());
            if (schema.has("maximum") && n.compareTo(schema.get("maximum").decimalValue()) > 0)
                problems.add(at(path) + "is " + n.toPlainString() + ", the contract allows at most " + schema.get("maximum").asText());
        }
        if (value.isTextual()) {
            int length = value.textValue().codePointCount(0, value.textValue().length());
            if (schema.has("maxLength") && length > schema.get("maxLength").asInt())
                problems.add(at(path) + "is longer than " + schema.get("maxLength").asInt() + " characters");
            if (schema.has("minLength") && length < schema.get("minLength").asInt())
                problems.add(at(path) + "is shorter than " + schema.get("minLength").asInt() + " characters");
            if (schema.has("pattern") && !Pattern.compile(schema.get("pattern").asText()).matcher(value.textValue()).find())
                problems.add(at(path) + "does not match " + schema.get("pattern").asText());
        }
        if (value.isArray()) {
            if (schema.has("maxItems") && value.size() > schema.get("maxItems").asInt())
                problems.add(at(path) + "has " + value.size() + " entries, the contract allows at most " + schema.get("maxItems").asInt());
            if (schema.has("minItems") && value.size() < schema.get("minItems").asInt())
                problems.add(at(path) + "has " + value.size() + " entries, the contract requires at least " + schema.get("minItems").asInt());
            if (schema.has("items")) for (int i = 0; i < value.size(); i++) check(schema.get("items"), value.get(i), path + "[" + i + "]", problems, depth + 1);
        }
        if (value.isObject()) {
            if (schema.has("maxProperties") && value.size() > schema.get("maxProperties").asInt())
                problems.add(at(path) + "has " + value.size() + " entries, the contract allows at most " + schema.get("maxProperties").asInt());
            if (schema.has("required")) for (JsonNode name : schema.get("required"))
                if (!value.has(name.asText())) problems.add(at(path.isEmpty() ? name.asText() : path + "." + name.asText()) + "is required");
            JsonNode properties = schema.get("properties");
            JsonNode additional = schema.get("additionalProperties");
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String child = path.isEmpty() ? field.getKey() : path + "." + field.getKey();
                if (properties != null && properties.has(field.getKey())) check(properties.get(field.getKey()), field.getValue(), child, problems, depth + 1);
                else if (additional != null && additional.isObject()) check(additional, field.getValue(), child, problems, depth + 1);
                else if (additional != null && additional.isBoolean() && !additional.booleanValue())
                    problems.add(at(child) + "is not a field of this object");
            }
        }
        return problems.size() == before;
    }

    /** The single branch of an anyOf that is not the null type, or null when there are several or none. */
    private JsonNode onlyNonNullBranch(JsonNode branches) {
        JsonNode found = null;
        for (JsonNode b : branches) {
            JsonNode r = resolve(b);
            if (r != null && r.has("type") && "null".equals(r.get("type").asText())) continue;
            if (found != null) return null;
            found = b;
        }
        return found;
    }

    private static boolean typeOk(JsonNode type, JsonNode value) {
        if (type.isArray()) { for (JsonNode t : type) if (typeOk(t, value)) return true; return false; }
        switch (type.asText()) {
            case "string": return value.isTextual();
            case "boolean": return value.isBoolean();
            case "null": return value.isNull();
            case "object": return value.isObject();
            case "array": return value.isArray();
            case "number": return value.isNumber();
            case "integer": return value.isIntegralNumber() || (value.isNumber() && value.decimalValue().stripTrailingZeros().scale() <= 0);
            default: return true;
        }
    }

    private static String kind(JsonNode value) {
        if (value.isNull()) return "null";
        if (value.isTextual()) return "a string";
        if (value.isBoolean()) return "a boolean";
        if (value.isIntegralNumber()) return "an integer";
        if (value.isNumber()) return "a number with a fraction";
        if (value.isArray()) return "an array";
        return "an object";
    }

    private static String show(JsonNode value) {
        String s = value.isTextual() ? "\"" + value.textValue() + "\"" : value.toString();
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }

    private static String enumValues(JsonNode values) {
        List<String> out = new ArrayList<>();
        for (JsonNode v : values) out.add(v.isTextual() ? v.textValue() : v.toString());
        return String.join(", ", out);
    }

    private String describe(JsonNode branches) {
        List<String> out = new ArrayList<>();
        for (JsonNode b : branches) {
            JsonNode r = resolve(b);
            String name = b.has("$ref") ? b.get("$ref").asText().substring(b.get("$ref").asText().lastIndexOf('/') + 1) : null;
            if (name == null && r != null && r.has("type")) name = r.get("type").asText();
            if (name != null && !out.contains(name)) out.add(name);
        }
        return String.join(" | ", out);
    }

    /** When one branch of a union is what the value looks like (same type tag), say what is wrong with that one. */
    private JsonNode sameKindBranch(JsonNode branches, JsonNode value) {
        JsonNode found = null;
        for (JsonNode b : branches) {
            JsonNode r = resolve(b);
            if (r == null || !r.has("type") || !typeOk(r.get("type"), value)) continue;
            if (found != null) return null;
            found = b;
        }
        return found;
    }

    private String hint(JsonNode branches, JsonNode value, int depth) {
        if (!value.isObject() || !value.has("type")) return "";
        for (JsonNode b : branches) {
            JsonNode r = resolve(b);
            JsonNode tag = r != null ? r.path("properties").path("type") : null;
            JsonNode tagSchema = tag != null ? resolve(tag) : null;
            if (tagSchema != null && tagSchema.has("enum")) {
                for (JsonNode allowed : tagSchema.get("enum")) {
                    if (allowed.equals(value.get("type"))) {
                        List<String> inner = new ArrayList<>();
                        check(r, value, "", inner, depth + 1);
                        return inner.isEmpty() ? "" : ": " + String.join("; ", inner.subList(0, Math.min(3, inner.size())));
                    }
                }
            }
        }
        return "";
    }

    private static String at(String path) { return path.isEmpty() ? "request " : path + " "; }
}
