# Open Responses: completion against the standard

Date: 9 October 2026. The source of the requirements is the Open Responses contract, not the behavior of any provider and not
LangChain. LangChain was used as a source of cases to test; nothing was changed because it does something differently.

## 1. The contract that is frozen, and what moved upstream

| What | Pinned here | Observed upstream `main` (1e33c10f) | Verdict |
|---|---|---|---|
| OpenAPI document | `693f26090d20…eedab` (the one framework resource) | `1f469fc044bc…87f6a` | Not the same bytes; **the same contract**: both have the same 2 paths and 108 schemas, 53 differ as text, and after taking out descriptions, titles, `x-` keys, defaults and single-branch wrappers (`allOf`/`oneOf` of one), the only differences are empty `required: []` that are gone and `additionalProperties: true` written as `{}` |
| Compliance runner `bin/compliance-test.ts` | `6c250eae…` | `6c250eae…` | identical |
| `package.json`, `bun.lock` | `3d1d6dc6…`, `029148e8…` | `5dbab623…`, `898732d0…` | differ: upstream moved schema generation to TypeSpec; no effect on the runner |

The pin is not moved: a moved pin changes the schema, the runner and the fixtures together, and nothing normative moved. When
it has to move, the schema resource, the manifest and the fixtures change in one commit.

## 2. What kind of requirement each thing is

- **Protocol requirement**: the shape of items, content and events; the lifecycle of a stream; `[DONE]` ends an SSE stream;
  `previous_response_id` and `store` as the contract defines them; the compact endpoint; the WebSocket `response.create`
  and its error envelope.
- **Optional capability**: retrieve, cancel, input items and input token count (not in the pinned contract, OpenAI adapter
  only); `background` (the contract has the pending statuses, not an operation to ask again); hosted tools; file upload.
  A capability not adopted is not offered by the screen.
- **Moqui policy**: ownership and authorization, encryption at rest, retention, one head run per conversation, the allow-list of
  what a browser may send, the limits on attachments and on SSE events, the ids the gateway shows, the structured events
  format `v1`. These are not claimed to come from the standard.
- **Provider extension**: anything only `OpenAiResponsesProtocol` accepts. Not widened here, not a prerequisite of anything new.

## 3. The comparison with LangChain, case by case

The LangChain cases below are the ones the plan for this work named. They were not run: the sources were not read again for this
document, and the table says what Moqui does and why.

| Case | Adopted as | Result |
|---|---|---|
| With `store=false` an assistant id, and a reasoning item with no `encrypted_content`, are dropped | A test, not a rule: what a request takes back is decided by the schema of the input item, not by what LangChain drops | `OpenResponsesReplayContractTests`: ids that the input schema declares stay (provider ids only), output-only fields go, reasoning keeps `summary` and `encrypted_content`, a local id is never an identity on the wire |
| A reasoning item with a summary and no encrypted content | Kept (it is a valid input item); not read as an assistant message | same class |
| Several function calls in one response, outputs as an array, the same `call_id` back | Tests | `LlmToolLoopContractTests`, `LlmResponsesConversationTests` |
| Arguments that are not JSON, a function that is not offered | Tests | the call is answered with an error output and the tool is not run |
| Refusal, `phase`, incomplete | Tests | `OpenResponsesStreamingContractTests` (refusal-only and tool-only streams are results, not empty answers) |
| Structured output with a JSON schema | Tests plus an independent check of the answer | `JsonSchemaCheck`, `structuredOutput` of the gateway |
| Model-name rules, removing `temperature`, hosted tools, an upload API, routing to other endpoints, tolerance for a stream with no `[DONE]` | **Excluded**: proprietary, not in the standard | nothing added; the existing OpenAI adapter is untouched and its tolerance stays its own |

## 4. What was found and corrected in this pass

| Finding | Where it came from | Correction |
|---|---|---|
| A request carried output-only fields of earlier items | schema of the input items vs the codec | input projection by `ItemParam`; stored items untouched |
| A local item id could become an id on the wire | review of `itemToMap` | only the provider id is an id |
| A call with no output, or an output with no call, would have been sent | plan P01 h | refused before sending |
| `response.completed` and the finished items could disagree and the result silently took one | review of the stream assembler | consistency check, error with the point named |
| A stream that does not add up surfaced as "no stream result" | test | the real error is reported |
| An SSE event or stream had no bound | plan | bounds, explicit error |
| `tool_choice` was only a request to the provider | plan | enforced before a tool runs |
| A structured answer was never checked | plan | checked after it is final, status in the gateway answer |
| A pending response of a protocol that cannot ask again waited for ever | plan P04 | says so at once, ends at its limit with the reason |
| `LlmResponse.errorJson`, `metadataJson`, `incompleteDetailsJson` were plaintext | plan F5 | encrypted, with `protect#LlmResponseDiagnostics` |
| A conversation did not continue on its connection when a turn brought session context | the acceptance run (U09) | continuation counts only the trajectory; the turn's own context is sent again as new input |
| **The owner could not delete a conversation through the gateway** (entity delete refused for the person) | the acceptance run (U11) | removal after the owner check runs as bookkeeping; a test with the checks on |
| The development configuration limited AT_LLM calls to 30 a minute | the acceptance run | AT_LLM tarpit off in `MoquiDevConf.xml` only |

## 5. Limits that remain

- The standard protocol has no operation to ask a pending response again: a background response of a protocol without
  `RETRIEVE` ends at its limit. Polling exists only for the OpenAI adapter, and its tool calls wait for a person.
- Reasoning text a model returns is never sent back and never shown; only the summary is.
- Capabilities that are exposed and have no test of their own stay `IMPLEMENTED_NOT_VERIFIED` in the behavior matrix.
- A live run against an endpoint of the standard protocol was **not** done: the only live endpoint available is OpenAI through the
  existing adapter, reported as a compatibility run, not as conformity of the standard client.
