#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
import re
import sys
import time
import xml.etree.ElementTree as ElementTree


EXPECTED_SCHEMA_HASH = "693f26090d206230ed22b336681f547a2882cf5b131e86743966cf71bbdeedab"


class SchemaError(Exception):
    pass


class OpenApiValidator:
    def __init__(self, schema):
        self.schema = schema
        self.components = schema.get("components", {}).get("schemas", {})

    def validate(self, schema, value, path="$"):
        schema = self.resolve(schema)
        if not isinstance(schema, dict) or not schema:
            return

        if "allOf" in schema:
            for sub_schema in schema["allOf"]:
                self.validate(sub_schema, value, path)

        if "anyOf" in schema:
            errors = self.collect_union_errors(schema["anyOf"], value, path)
            if len(errors) == len(schema["anyOf"]):
                raise SchemaError(f"{path} did not match anyOf: {'; '.join(errors[:3])}")

        if "oneOf" in schema:
            errors = self.collect_union_errors(schema["oneOf"], value, path)
            matches = len(schema["oneOf"]) - len(errors)
            if matches != 1:
                raise SchemaError(f"{path} matched {matches} oneOf schemas: {'; '.join(errors[:3])}")

        expected_type = schema.get("type")
        if expected_type:
            self.validate_type(expected_type, value, path)

        if "const" in schema and value != schema["const"]:
            raise SchemaError(f"{path} expected const {schema['const']!r}, got {value!r}")
        if "enum" in schema and value not in schema["enum"]:
            raise SchemaError(f"{path} expected one of {schema['enum']!r}, got {value!r}")

        self.validate_constraints(schema, value, path)

        if isinstance(value, dict):
            for field_name in schema.get("required", []):
                if field_name not in value:
                    raise SchemaError(f"{path}.{field_name} is required")
            properties = schema.get("properties", {})
            for field_name, field_value in value.items():
                if field_name in properties:
                    self.validate(properties[field_name], field_value, f"{path}.{field_name}")
                elif schema.get("additionalProperties") is False:
                    raise SchemaError(f"{path}.{field_name} is not allowed")
                elif isinstance(schema.get("additionalProperties"), dict):
                    self.validate(schema["additionalProperties"], field_value, f"{path}.{field_name}")
        elif isinstance(value, list) and "items" in schema:
            for index, item in enumerate(value):
                self.validate(schema["items"], item, f"{path}[{index}]")

    @staticmethod
    def validate_constraints(schema, value, path):
        """The numeric, string and array keywords the pinned schema uses (format and discriminator are annotations)."""
        if isinstance(value, bool):
            return
        if isinstance(value, (int, float)):
            if "minimum" in schema and value < schema["minimum"]:
                raise SchemaError(f"{path} is {value}, below minimum {schema['minimum']}")
            if "maximum" in schema and value > schema["maximum"]:
                raise SchemaError(f"{path} is {value}, above maximum {schema['maximum']}")
            if "exclusiveMinimum" in schema and value <= schema["exclusiveMinimum"]:
                raise SchemaError(f"{path} is {value}, not above {schema['exclusiveMinimum']}")
            if "exclusiveMaximum" in schema and value >= schema["exclusiveMaximum"]:
                raise SchemaError(f"{path} is {value}, not below {schema['exclusiveMaximum']}")
        elif isinstance(value, str):
            if "minLength" in schema and len(value) < schema["minLength"]:
                raise SchemaError(f"{path} is shorter than minLength {schema['minLength']}")
            if "maxLength" in schema and len(value) > schema["maxLength"]:
                raise SchemaError(f"{path} is longer than maxLength {schema['maxLength']}")
            if "pattern" in schema and re.search(schema["pattern"], value) is None:
                raise SchemaError(f"{path} does not match pattern {schema['pattern']}")
        elif isinstance(value, list):
            if "minItems" in schema and len(value) < schema["minItems"]:
                raise SchemaError(f"{path} has fewer than minItems {schema['minItems']}")
            if "maxItems" in schema and len(value) > schema["maxItems"]:
                raise SchemaError(f"{path} has more than maxItems {schema['maxItems']}")
            if schema.get("uniqueItems") and len({json.dumps(item, sort_keys=True) for item in value}) != len(value):
                raise SchemaError(f"{path} items are not unique")

    def collect_union_errors(self, schemas, value, path):
        errors = []
        for sub_schema in schemas:
            try:
                self.validate(sub_schema, value, path)
            except SchemaError as error:
                errors.append(str(error))
        return errors

    def resolve(self, schema):
        while isinstance(schema, dict) and "$ref" in schema:
            ref = schema["$ref"]
            if not ref.startswith("#/components/schemas/"):
                raise SchemaError(f"Unsupported ref {ref}")
            schema = self.components[ref.rsplit("/", 1)[1]]
        return schema

    @staticmethod
    def validate_type(expected_type, value, path):
        expected_types = expected_type if isinstance(expected_type, list) else [expected_type]
        for candidate in expected_types:
            if candidate == "null" and value is None:
                return
            if candidate == "object" and isinstance(value, dict):
                return
            if candidate == "array" and isinstance(value, list):
                return
            if candidate == "string" and isinstance(value, str):
                return
            if candidate == "boolean" and isinstance(value, bool):
                return
            if candidate == "integer" and isinstance(value, int) and not isinstance(value, bool):
                return
            if candidate == "number" and isinstance(value, (int, float)) and not isinstance(value, bool):
                return
        raise SchemaError(f"{path} expected type {expected_types!r}, got {type(value).__name__}")


def minimal_response():
    return {
        "id": "resp_contract_1",
        "object": "response",
        "created_at": 1760000000,
        "completed_at": 1760000001,
        "status": "completed",
        "incomplete_details": None,
        "model": "contract-test",
        "previous_response_id": "resp_prev_1",
        "instructions": None,
        "output": [
            {
                "type": "message",
                "id": "msg_contract_1",
                "status": "completed",
                "role": "assistant",
                "content": [
                    {"type": "output_text", "text": "ok", "annotations": []}
                ],
                "phase": "final_answer"
            },
            {
                "type": "function_call",
                "id": "fc_contract_1",
                "call_id": "call_contract_1",
                "name": "moqui_search",
                "arguments": "{\"term\":\"order\"}",
                "status": "completed"
            },
            {
                "type": "function_call_output",
                "id": "fco_contract_1",
                "call_id": "call_contract_1",
                "output": [
                    {"type": "input_text", "text": "{\"ok\":true}"}
                ],
                "status": "completed"
            },
            {
                "type": "reasoning",
                "id": "rs_contract_1",
                "summary": [
                    {"type": "summary_text", "text": "picked a deterministic tool path"}
                ],
                "content": [
                    {"type": "reasoning_text", "text": "reasoning fragment"}
                ],
                "encrypted_content": "ciphertext"
            }
        ],
        "error": None,
        "tools": [
            {
                "type": "function",
                "name": "moqui_search",
                "description": "Search Moqui context",
                "parameters": {"type": "object", "properties": {}},
                "strict": True
            }
        ],
        "tool_choice": "auto",
        "truncation": "auto",
        "parallel_tool_calls": True,
        "text": {"format": {"type": "text"}},
        "top_p": 1.0,
        "presence_penalty": 0.0,
        "frequency_penalty": 0.0,
        "top_logprobs": 0,
        "temperature": 0.2,
        "reasoning": {"effort": None, "summary": None},
        "usage": {
            "input_tokens": 10,
            "output_tokens": 5,
            "total_tokens": 15,
            "input_tokens_details": {"cached_tokens": 0},
            "output_tokens_details": {"reasoning_tokens": 1}
        },
        "max_output_tokens": 1024,
        "max_tool_calls": 4,
        "store": False,
        "background": False,
        "service_tier": "auto",
        "metadata": {"purpose": "schema"},
        "safety_identifier": None,
        "prompt_cache_key": None
    }


def constraint_fixtures():
    """Boundary cases for the keywords the adopted schema constrains: each invalid one must be rejected."""
    def create(**extra):
        body = {"model": "contract-test", "input": "hi"}
        body.update(extra)
        return body

    def call(**extra):
        item = {"type": "function_call", "call_id": "call_1", "name": "lookup", "arguments": "{}"}
        item.update(extra)
        return create(input=[item])

    cases = [
        ("max-output-tokens-at-minimum", "pass", create(max_output_tokens=16)),
        ("max-output-tokens-below-minimum", "fail", create(max_output_tokens=15)),
        ("top-logprobs-at-maximum", "pass", create(top_logprobs=20)),
        ("top-logprobs-above-maximum", "fail", create(top_logprobs=21)),
        ("top-logprobs-negative", "fail", create(top_logprobs=-1)),
        ("function-call-name-at-limit", "pass", call(name="a" * 64)),
        ("function-call-name-too-long", "fail", call(name="a" * 65)),
        ("function-call-name-empty", "fail", call(name="")),
        ("function-call-name-bad-characters", "fail", call(name="look up")),
        ("function-call-id-too-long", "fail", call(call_id="c" * 65)),
        ("metadata-value-at-limit", "pass", create(metadata={"k": "v" * 512})),
        ("metadata-value-too-long", "fail", create(metadata={"k": "v" * 513})),
        ("safety-identifier-too-long", "fail", create(safety_identifier="s" * 65)),
    ]
    return [{"id": "constraint-" + name, "schema": "CreateResponseBody", "expect": expect, "payload": payload}
            for name, expect, payload in cases]


def fixtures():
    response = minimal_response()
    return constraint_fixtures() + [
        {
            "id": "create-array-content-file-image-tool-options",
            "schema": "CreateResponseBody",
            "expect": "pass",
            "payload": {
                "model": "contract-test",
                "input": [
                    {
                        "type": "message",
                        "role": "user",
                        "content": [
                            {"type": "input_text", "text": "read the BOM"},
                            {"type": "input_image", "image_url": "https://example.invalid/image.png", "detail": "auto"},
                            {
                                "type": "input_file",
                                "filename": "bom.csv",
                                "file_data": "data:text/csv;base64,YSxiCg=="
                            }
                        ]
                    },
                    {
                        "type": "function_call_output",
                        "call_id": "call_contract_1",
                        "output": [
                            {"type": "input_text", "text": "{\"ok\":true}"}
                        ]
                    }
                ],
                "previous_response_id": "resp_prev_1",
                "include": [],
                "tools": [
                    {
                        "type": "function",
                        "name": "moqui_search",
                        "description": "Search Moqui context",
                        "parameters": {"type": "object", "properties": {}},
                        "strict": True
                    }
                ],
                "tool_choice": "auto",
                "metadata": {"tenant": "test"},
                "text": {"format": {"type": "text"}},
                "temperature": 0.2,
                "top_p": 1.0,
                "presence_penalty": 0.0,
                "frequency_penalty": 0.0,
                "parallel_tool_calls": True,
                "stream": True,
                "stream_options": {"include_usage": True},
                "background": False,
                "max_output_tokens": 1024,
                "max_tool_calls": 4,
                "reasoning": {"effort": "low", "summary": "auto"},
                "safety_identifier": "contract",
                "prompt_cache_key": "contract",
                "truncation": "auto",
                "instructions": "answer briefly",
                "store": False,
                "service_tier": "auto",
                "top_logprobs": 0
            }
        },
        {"id": "response-resource-output-items", "schema": "ResponseResource", "expect": "pass", "payload": response},
        {
            "id": "stream-completed-event",
            "schema": "ResponseCompletedStreamingEvent",
            "expect": "pass",
            "payload": {"type": "response.completed", "sequence_number": 3, "response": response}
        },
        {
            "id": "websocket-create-event-no-http-stream-fields",
            "schema": "WebSocketResponseCreateEvent",
            "expect": "pass",
            "payload": {
                "type": "response.create",
                "model": "contract-test",
                "input": [{"type": "message", "role": "user", "content": [{"type": "input_text", "text": "hi"}]}],
                "previous_response_id": "resp_prev_1"
            }
        },
        {
            "id": "reject-invalid-response-object",
            "schema": "ResponseResource",
            "expect": "fail",
            "payload": dict(response, object="not_response")
        },
        {
            "id": "reject-invalid-message-content-shape",
            "schema": "CreateResponseBody",
            "expect": "fail",
            "payload": {"input": [{"type": "message", "role": "user", "content": {"type": "input_text", "text": "bad"}}]}
        },
        {
            "id": "reject-websocket-stream-field",
            "schema": "WebSocketResponseCreateEvent",
            "expect": "fail",
            "payload": {"type": "response.create", "stream": True, "input": "hi"}
        }
    ]


def write_json_report(path, report):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as report_file:
        json.dump(report, report_file, indent=2, sort_keys=True)
        report_file.write("\n")


def write_junit_report(path, report):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    testsuite = ElementTree.Element("testsuite", {
        "name": "OpenResponsesSchemaFixtures",
        "tests": str(len(report["results"])),
        "failures": str(sum(1 for result in report["results"] if result["status"] != "passed")),
        "skipped": "0",
        "time": f"{report['durationSeconds']:.3f}"
    })
    for result in report["results"]:
        testcase = ElementTree.SubElement(testsuite, "testcase", {
            "classname": "OpenResponsesSchemaFixtures",
            "name": result["id"],
            "time": f"{result['durationSeconds']:.3f}"
        })
        if result["status"] != "passed":
            failure = ElementTree.SubElement(testcase, "failure", {
                "message": result.get("message", "fixture failed")
            })
            failure.text = result.get("message", "fixture failed")
    ElementTree.ElementTree(testsuite).write(path, encoding="utf-8", xml_declaration=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--schema", required=True)
    parser.add_argument("--json-report", required=True)
    parser.add_argument("--junit-report", required=True)
    parser.add_argument("--wire-dir", help="directory of {schema, payload} files built by the Moqui codec; must not be empty")
    parser.add_argument("--require-library", action="store_true",
                        help="fail when the maintained jsonschema library (requirements.txt) is not importable")
    args = parser.parse_args()

    with open(args.schema, "rb") as schema_file:
        raw_schema = schema_file.read()
    schema_hash = hashlib.sha256(raw_schema).hexdigest()
    if schema_hash != EXPECTED_SCHEMA_HASH:
        raise SystemExit(f"Schema hash mismatch: expected {EXPECTED_SCHEMA_HASH}, got {schema_hash}")

    schema = json.loads(raw_schema)
    validator = OpenApiValidator(schema)
    library = None
    library_version = None
    try:
        import jsonschema
        import importlib.metadata
        library_version = importlib.metadata.version("jsonschema")
        library = jsonschema
    except ImportError:
        if args.require_library:
            raise SystemExit("The jsonschema library is required (pip install -r tools/openresponses/requirements.txt)")
    started = time.time()
    results = []
    all_fixtures = fixtures()
    if args.wire_dir:
        wire_files = sorted(f for f in os.listdir(args.wire_dir) if f.endswith(".json"))
        if not wire_files:
            raise SystemExit(f"No wire fixtures in {args.wire_dir}: run the Moqui wire fixture tests first")
        for name in wire_files:
            with open(os.path.join(args.wire_dir, name), "r", encoding="utf-8") as wire_file:
                wire = json.load(wire_file)
            all_fixtures.append({"id": "wire-" + name[:-5], "schema": wire["schema"], "expect": "pass",
                                 "payload": wire["payload"]})
    for fixture in all_fixtures:
        fixture_started = time.time()
        try:
            validator.validate({"$ref": f"#/components/schemas/{fixture['schema']}"}, fixture["payload"])
            actual = "pass"
            message = None
        except SchemaError as error:
            actual = "fail"
            message = str(error)
        library_actual = None
        if library is not None:
            wrapper = {"$schema": "https://json-schema.org/draft/2020-12/schema",
                       "$ref": f"#/components/schemas/{fixture['schema']}", "components": schema["components"]}
            library_actual = "pass" if library.Draft202012Validator(wrapper).is_valid(fixture["payload"]) else "fail"
            if library_actual != actual:
                message = (message or "") + f" [the maintained library says {library_actual}, the local validator says {actual}]"
        passed = actual == fixture["expect"] and (library_actual is None or library_actual == actual)
        results.append({
            "id": fixture["id"],
            "schema": fixture["schema"],
            "expected": fixture["expect"],
            "actual": actual,
            "libraryActual": library_actual,
            "status": "passed" if passed else "failed",
            "message": message,
            "durationSeconds": round(time.time() - fixture_started, 6)
        })

    report = {
        "schema": os.path.abspath(args.schema),
        "schemaSha256": schema_hash,
        "libraryValidator": ("jsonschema " + library_version) if library is not None else "unavailable",
        "fixtureCount": len(results),
        "passedCount": sum(1 for result in results if result["status"] == "passed"),
        "failedCount": sum(1 for result in results if result["status"] != "passed"),
        "results": results,
        "durationSeconds": round(time.time() - started, 6)
    }
    write_json_report(args.json_report, report)
    write_junit_report(args.junit_report, report)
    if report["failedCount"]:
        print(json.dumps(report, indent=2))
        return 1
    print(f"Validated {report['fixtureCount']} Open Responses schema fixtures against {schema_hash} "
          f"(independent check: {report['libraryValidator']})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
