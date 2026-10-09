# Open Responses: behavior matrix

Per behavior: where it is emitted, parsed, persisted, replayed or exposed, the test that proves it, and what that test
found. Test classes are under `framework/src/test/groovy/org/moqui/impl/llm/` unless a path is given. "Result" is the
state at the last run of the class on H2 (see the delivery report for the PostgreSQL run).

| Behavior | Emitted / parsed | Persisted | Replayed | Exposed | Test | Result |
|---|---|---|---|---|---|---|
| Open Responses never reads or writes the entity `LlmMessage` | n/a | head run `contextJson` | from the head | history projected from items | `LlmResponsesConversationTests`: "an Open Responses turn leaves no message row…", "only the message store names the message entity" | pass |
| Two turns across clients and ExecutionContexts | input = trajectory + new input | head advanced with the run | first turn sent once | answer | "a second turn with a new client and a new ExecutionContext…" | pass |
| Image of an earlier turn | `input_image` data URI | `LlmContent.imageUrl` (encrypted) | byte for byte | not in diagnostic views | "an image of an earlier turn is replayed byte for byte…" | pass |
| Tool call, output and their ids | `function_call`, `function_call_output` (string) | in the trajectory | with `call_id` | tool events | "a tool loop keeps the call, its output and their ids…" | pass |
| Client tool yield and resume | pending calls | run waits | same run continues once | `yield` event | "a client tool yields, the answer resumes the same run once…" | pass |
| Opaque reasoning (`encrypted_content`) | stored complete, replayed with `summary`, no `content` | `LlmItem.encryptedContent` | unchanged | never in the view | "opaque reasoning is replayed unchanged…" | pass |
| Ownership, profile and protocol binding | n/a | n/a | refused 403/409 | message names nothing | "another user, another profile and another protocol cannot take over…" | pass |
| Chat Completions unchanged | n/a | `LlmMessage` rows | window policy | n/a | "a Chat Completions conversation still keeps its transcript…" | pass |
| Failed turn is not the head | n/a | run FAILED | previous head | n/a | "a turn that failed is not the head…" | pass |
| Recovery completes into the conversation | n/a | head advanced | n/a | n/a | "a recovered turn completes into its conversation…" | pass |
| `previous_response_id` of a foreign chain | refused 409 | none | none | error | "a remote chain continues from the head…", "a local conversation refuses a foreign previous_response_id…" | pass |
| Delete, whole and owner-scoped | n/a | all rows removed | n/a | 409 with a live run | `LlmConversationDeleteTests` (5 tests) | pass |
| Retention keeps the head | n/a | older rows removed | head replays | n/a | "retention that keeps a conversation also keeps the run it continues from…" | pass |
| Media encrypted, plaintext upgraded | n/a | 4 `LlmContent` columns | n/a | size and hash only | `LlmConversationUpgradeTests` | pass |
| Transcript to items migration | n/a | `ITEMS_MIGRATED` / `ITEMS_LEGACY` | trajectory built from rows | what could not move is reported | "conversations stored as a transcript move…" | pass |
| Request options validated by the pinned schema | whole body vs `CreateResponseBody` | request row, status Failed | n/a | field named in the error | `OpenResponsesSchemaTests` (parameterized) | pass |
| Strict tools | `strict` as given; closed schema | tool descriptor keeps it | n/a | n/a | "a strict tool whose schema is not closed is refused…" | pass |
| `tool_choice` coherent with tools | refused when it names a tool not sent | n/a | n/a | n/a | "a tool choice must name tools the request carries" | pass |
| `instructions` single source | body `instructions` | envelope | n/a | n/a | "instructions given twice are refused, not merged" | pass |
| Background response parked and polled | `queued`/`in_progress` → PENDING | run `LlmRunWaitProvider`, checkpoint | n/a | id returned | `LlmBackgroundResponseTests` (10 tests) | pass |
| Background tool calls are not executed | n/a | run Waiting for Confirmation | n/a | n/a | "a finished response that asks for tools is kept for a person…" | pass |
| Compaction stored as such | `compact_response` | request + response + new head | compacted trajectory | n/a | `LlmCompactionTests` (4 tests) | pass |
| Provider operations by capability, owner, paging | retrieve/cancel/input_items/input_tokens | n/a | n/a | n/a | `LlmProviderOperationsTests` (5 tests) | pass |
| WebSocket kept across turns | `previous_response_id` + new input only | n/a | local replay on a new socket | n/a | `LlmConversationWebSocketTests` (3 tests); `LlmResponsesClientIntegrationTests` WebSocket group | pass |
| Gateway attachments | `input_text` + `input_image`/`input_file` | content (encrypted) | with the trajectory | ids in the answer | `LlmGatewayOpenResponsesTests` | pass |
| Gateway options and transport allow-list | allowed names only | request options | n/a | 400 otherwise | `LlmGatewayOpenResponsesTests` (parameterized) | pass |
| Structured servlet events | `response_event` v1 | provider events journaled as before | n/a | sanitized, correlated, own `seq` | `LlmGatewayEventsTests` | pass |
| Browser disconnect | stream aborted | conversation not left Streaming | n/a | n/a | `LlmGatewayEventsTests` "a browser that goes away…" | pass |
| Input projection by `ItemParam` | `toInputItem` | stored item untouched | replay | n/a | `OpenResponsesReplayContractTests`, "the assistant item of an earlier turn goes back with the provider's id…" | pass |
| Local id never an id on the wire | `itemToMap` | n/a | replay | n/a | `OpenResponsesReplayContractTests` | pass |
| Call and output travel together | `requireCompleteToolPairs` | n/a | n/a | refusal before sending | `OpenResponsesReplayContractTests` | pass |
| Conversation continues after a new JVM, image included | n/a | head run | trajectory | n/a | `testLlmConversationRestartVerify` (seed in one JVM, verify in another) | pass |
| Stream: completed response agrees with finished items | assembler | n/a | n/a | error names the point | `OpenResponsesStreamingContractTests` | pass |
| Stream: LF, CR and CRLF, comments, several data lines | `RestClient.streamSse` | n/a | n/a | n/a | `OpenResponsesStreamingContractTests` (and the byte-by-byte provider) | pass |
| Stream: event and stream bounds | `sseLimits` | n/a | n/a | error names the bound | `OpenResponsesStreamingContractTests` | pass |
| Tool-only and refusal-only streams are results | assembler | items | replay | n/a | `OpenResponsesStreamingContractTests` | pass |
| `tool_choice` enforced before a tool runs | `refusedByToolChoice` | tool journal | n/a | error output to the model | `LlmToolLoopContractTests` | pass |
| Unknown function, arguments that are not JSON: nothing runs | agent loop | trajectory keeps the call | replay | n/a | `LlmToolLoopContractTests` | pass |
| Iteration limit reported, run failed with the reason | agent loop | run status | n/a | error | `LlmToolLoopContractTests` | pass |
| Structured answer checked against the schema asked for | `JsonSchemaCheck` | n/a | n/a | `structuredOutput` | `LlmToolLoopContractTests`, `LlmGatewayOpenResponsesTests` | pass |
| Pending without a retrieval operation ends at its limit with a reason | poller | run failed | n/a | `pollable=false` | `LlmBackgroundResponseTests` | pass |
| Error, metadata and incomplete details of a response encrypted | `LlmResponse` | ciphertext | n/a | n/a | `LlmConversationUpgradeTests` | pass |
| Provider dropped its cache: explicit failure, head kept, new chain next | WebSocket | head unchanged | full trajectory | error | `LlmConversationWebSocketTests` | pass |
| Turn context does not stop a connection from continuing | WebSocket | trajectory without context | new input | n/a | `LlmConversationWebSocketTests` | pass |
| Delete against a turn, retention against a head advance, at the same instant | purge | no orphans | n/a | 409 or whole | `LlmConversationRaceTests` (12 rounds each, barrier) | pass |
| The owner deletes a conversation as a person | gateway | rows removed | n/a | 200 | `LlmGatewayOpenResponsesTests` (authorization checks on) | pass |
| Attachments described, bytes served to the owner | gateway | content (encrypted) | n/a | `/attachments/{n}` | `LlmGatewayOpenResponsesTests` | pass |
| Screen, twelve checks U01 to U12 (real browser, real Moqui, fake standard provider) | Assist.qvue | n/a | n/a | n/a | `base-component/tools/test/e2e` in the runtime repository | pass 12/12 |
| Screen against OpenAI through the existing adapter (compatibility) | same | n/a | n/a | n/a | same run with `ACCEPT_LIVE=1` | pass 11, U12 blocked (needs the fake provider) |

Not covered by a test of its own: provider-specific behavior of fields the client passes through unchanged (`opaque`
in `tools/openresponses/OpenResponsesCoverage.md`); live behavior against a provider other than OpenAI.
