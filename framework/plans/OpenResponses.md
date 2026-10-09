# Open Responses client

Moqui's LLM client (`LlmClient`, profiles in `<llm-facade>`) speaks the [Open Responses](https://www.openresponses.org/specification)
protocol next to the existing Chat Completions one. Contract version `2026-04-24`; the OpenAPI document it was built
against is kept as the framework resource `org/moqui/impl/llm/openapi-2026-04-24.json` (SHA-256
`693f26090d206230ed22b336681f547a2882cf5b131e86743966cf71bbdeedab`; the hash, not the version string, identifies the
contract). Chat Completions (`OpenAiCompatProtocol`) is unchanged and remains the default protocol.

## Using it

A profile selects the protocol class:

```xml
<profile name="responses" protocol="org.moqui.impl.llm.OpenResponsesProtocol"
        url="https://provider.example" api-key="${key}" model="${model}"/>
```

`OpenAiResponsesProtocol` extends it with OpenAI-only operations (retrieve, cancel, list input items, count input
tokens, which are provider extensions and not part of the Open Responses contract). Code uses the same `LlmClient`
calls as before; items and options are added with `inputItems(...)`, `responseOptions(...)` and `transport(...)`
(HTTP, SSE streaming or WebSocket).

## What is implemented

- `POST /responses` with the full item model (message, function call and output, item reference, reasoning,
  compaction), content parts (text, image, file, video, refusal, annotations, logprobs) and provider-prefixed items
  kept as opaque payload.
- SSE semantic events with sequence checks, and `response.create` over WebSocket; `POST /responses/compact`.
- SSE framing follows the specification: the last event MUST be `[DONE]`. `OpenResponsesProtocol` fails a stream that
  ends after its terminal event without it, a `[DONE]` without a terminal event, an event that is not JSON, and an event
  whose `event:` name contradicts its `type`. `OpenAiResponsesProtocol` tolerates the missing sentinel because OpenAI's
  endpoint does not send it; that tolerance is that protocol's, not a claim of standard conformance.
- The agent loop over items: reasoning, tool call, tool result, next inference, with a durable trajectory.
- Local stateless continuation (`store=false`: the whole trajectory is replayed) and remote continuation
  (`previous_response_id`: only new items are sent). A lost previous response is reported, never silently replayed.
- Input files go through Moqui's `ResourceReference`; local locations are never sent as URLs, local content is read
  (32 MiB cap), hashed and sent as base64.

## Data model (`moqui.llm`)

- `LlmRequest`: the request actually prepared for transport, owned by a user, with its local status. The snapshot is
  built by the same protocol method that sends it (HTTP, SSE and WebSocket, provider extensions included). Status:
  `Sending` while in flight; `Acknowledged` when the provider answered; `Failed` when it refused (an HTTP error) or the
  request was refused locally before sending; `Uncertain` when it may have been sent and nothing was read (timeout,
  dropped connection, cancel). An uncertain request is never replayed automatically. `metadataJson` holds the SHA-256 and
  size of the body that was built to send. Request payload, options, tools and metadata are encrypted.
- Persistence does not need a conversation: an authenticated client without one still records its requests, items
  and responses (`conversationId` is null).
- Options are kept in three places that mean three things: `LlmRequest.requestOptionsJson` is what the caller asked
  for, `LlmRequest.effectiveOptionsJson` is what went on the wire (caller options, extra body and the client's own
  fields, taken from the exact body), and `LlmResponse.optionsJson` is what the provider echoed, with its nulls and
  defaults. An explicit null is a value: it is sent, stored and returned as null, not dropped. `LlmResponse.usageJson`
  is the provider's usage object as returned; the token columns are queryable copies.
- `LlmResponse`: the provider response resource, linked to its request; its payload is encrypted and complete. A streamed
  response has its row from the first event (see Limits) and the final result completes it.
- `LlmItem`: one item occurrence owned by exactly one request or response; an entity ECA (`Llm.eecas.xml`) refuses
  anything else on create and update, whoever writes it, and refuses a source on a response item. A replayed item becomes
  a new request-owned item. Its `sourceStatus` says how its source was found: `named` (the caller gave it; a source of
  another owner is refused with a message that says nothing about it), `matched` (exactly one response item of the
  same owner, profile and item type had that provider id), `unresolved` (several did: none is picked, the replay is kept)
  or `unmatched`. Provider ids are not unique, so they never match across owners or profiles. `LlmContent`: the parts
  of an item by kind (`content`, `summary`, `output` for a tool output array), each kind numbered from 1, with the
  purpose from what produced it: model messages, reasoning, summaries and compaction are `LlmCpAssistant` also when
  replayed; user, system and developer messages and tool outputs are `LlmCpUser`. Free text, refusals, arguments,
  outputs and payloads are encrypted. `encrypted_content` and `file_data` are stored complete (encrypted) in
  `LlmItem.encryptedContent`, `LlmContent.fileData` and the raw payloads.
- `LlmResponseEvent`: stream event log for replay and audit; `get#LlmResponse` returns it page by page
  (`eventOffset` from 0, `eventLimit` 1 to 1000, `eventsNext`; values outside are an error).
- `LlmRun`, `LlmRunStatus`, `LlmToolInvocation`, `LlmContextProjection`: durable agent runs, their state journal, the
  tool call journal and the searchable projection. The entity `LlmMessage` keeps only the Chat Completions transcript: no
Open Responses code path reads or writes it (see Conversations).
- The projection (`search#LlmContext`) is fed by the content writer, in the same transaction: the text of messages and
  tool outputs, request and response, one row per content row (`sourceType=content`, `sourceId`=the content id), with
  the owner, conversation and run. Reasoning, summaries, compaction, files, images and opaque payloads are never copied.
  It is a plain-text copy of text that is also stored encrypted, so it is a deliberate choice: switch it off with the
  system property `moqui.llm.projection=false` or the environment variable `llm_context_projection=false`. Content
  stored while it was off, or before it existed, is projected by `backfill#LlmContextProjection` (batches, repeatable,
  caller's own content only). `clean#LlmData` removes the projection with the content. Search is always limited to the
  caller's own rows.

## Conversations

`LlmConversation.messageModel` says where the history of a conversation lives. Empty or `MESSAGES`: the Chat Completions
transcript, in `LlmMessage` rows. `ITEMS`: Open Responses; the history is the trajectory of structured items stored (encrypted)
in the `contextJson` of the **head run** (`headRunId`, `headVersion`), the last completed run. A turn starts from that
trajectory, adds the context of the turn (session, pins, skills: marked `ephemeral`, sent but never part of the head) and the
new input, and, when the run completes, the head moves to it in the same transaction (optimistic `headVersion`, so two
turns cannot both win). A failed or cancelled turn leaves the head where it was. Instructions go in the `instructions`
field, not as a message. The model is fixed when the profile is bound (`ITEM_TRAJECTORY` capability of the protocol); a
conversation of one model cannot be used with a profile of the other. `LlmMessageStore` is the only class that touches
the entity `LlmMessage`; a test scans the sources to keep it so. The DTO `org.moqui.llm.LlmMessage` is unchanged: the
history shown to a screen is projected from the items (`ItemHistory`).

## Deleting and retention

`DELETE /v1/conversations/{id}` (`LlmGateway.deleteConversationOf`) and `clean#LlmData` share one procedure
(`LlmConversationPurge`). Delete refuses (409) a conversation with a live run or turn, closes its WebSocket sessions, and
removes runs, requests, responses, items, content, events, projection and the conversation itself. Retention removes what
is older than the limit, keeps the conversation while a newer or open run exists, and never removes a head run.

## Media

`LlmContent.imageUrl`, `fileUrl`, `videoUrl` and `contentLocation` are encrypted at rest (a data URI holds the picture).
Rows written before that hold plaintext, which Moqui reads back as the placeholder `_DECRYPT_FAILED_`; they are readable
again after `protect#LlmContentMedia` (administrator, batched, compare-and-set, repeatable). Diagnostic services return
media as a size and a hash, not the bytes.

## Installing and upgrading

**New installation** (from the official `llm-client`): nothing to run. The entity engine creates the tables, columns
and indexes at startup. For PostgreSQL set `entity_ds_schema` (for example `public`); without it the engine cannot see
existing tables and tries to create them again.

**An installation that already holds Open Responses data** (the draft published as `Add Open Responses protocol
support`, before the options and usage were kept apart) needs no table to be dropped:

1. Back up the database. The steps below only add and fill; there is no automatic way back from the second one.
2. Start the new code once. With the default `entity_add_missing_startup=true` the entity engine adds the missing nullable
   columns (`LlmRequest.effectiveOptionsJson`, `LlmResponse.usageJson`, `LlmResponse.dataVersion`,
   `LlmItem.sourceStatus`, `LlmRun.envelopeJson`) and the index `LLMITEM_PROVIDER`, and drops nothing.
3. As an administrator, run `upgrade#LlmOpenResponsesData` with `batchSize` and, while it answers `more=true`,
   `afterLlmResponseId` = the `lastLlmResponseId` it returned. It handles the responses whose `dataVersion` is empty:
   the options column of an old response held what was sent, so it moves to the request's `effectiveOptionsJson` when that is
   empty; the echoed options and `usageJson` are read from the stored raw response, and stay empty for a response that has none;
   nothing is derived from anything else. Items keep an empty `sourceStatus` (the way their source was found was not
   recorded), and runs keep an empty `envelopeJson` (they are recovered from the profile, as before). It can be
   interrupted and repeated; a response at `dataVersion` 2 is never touched again.
4. Optionally make the stored text searchable with `backfill#LlmContextProjection` (same batch protocol).
5. Load the seed data again (`gradlew load`, types `seed` or `seed-initial`): the new run status `LlmRunWaitProvider` and the
   service job `poll_AllLlmBackgroundResponses` are seed data.
6. As an administrator, run `protect#LlmContentMedia` and `protect#LlmResponseDiagnostics` (both batched, resumable, repeatable)
   to encrypt the media columns of `LlmContent` and the error, metadata and incomplete-details columns of `LlmResponse` that
   earlier versions wrote in plaintext. Run them before anything reads those rows: until then the media columns read as
   `_DECRYPT_FAILED_` and the entity engine can refuse to read the other three.
7. Conversations of Open Responses profiles that were kept in `LlmMessage` rows are moved to the item model by
   `migrate#LlmConversationModel` (administrator): it builds the trajectory from the rows, marks the conversation
   `ITEMS_MIGRATED` (rows deleted, they were a copy) or `ITEMS_LEGACY` (rows kept), and can be repeated. Back up first.

Verified by `testLlmResponsesUpgradeVerify`: a first JVM stores rows in the old layout and removes the new columns and
index from the database; a second JVM starts the current code on it, checks that the columns come back, runs the upgrade
interrupted and again, runs it a third time (nothing to do), backfills the search copy, and checks that nothing was lost
and that nothing was invented. It was run on H2 and on PostgreSQL 18.1. The tables are never dropped; the only DROP COLUMN
in the test is the one that gives the database its old shape.

## Durable runs and recovery

A run keeps its item trajectory (`contextJson`, encrypted) and a checkpoint. A client that executes a run takes its lease
and a fencing token first and renews the lease from its own thread while it works, so the recovery job never claims a run
that is being executed. State changes, tool planning and checkpoints carry the token: a worker that lost the lease is
rejected and stops. Recovery re-checks the state under the lock, so a run that went to a wait in between is not claimed.
`recover_AllLlmRuns_frequent` (ServiceJob, every two minutes) resumes expired runs as their owner. A run whose last
checkpoint is `ready_provider` is replayed; `provider_in_flight`, planned tools and uncertain external effects move to
`LlmRunWaitConfirm` and are never repeated automatically. An operator checks the run and its `LlmToolInvocation`
journal, reconciles the outside world, then resumes or cancels through the owner-scoped services.

## What the client implements

| Operation or field | In the pinned contract | Moqui |
|---|---|---|
| `POST /responses`, JSON | standard | implemented, wire body checked against the schema |
| `POST /responses` with `stream`, SSE with `[DONE]` | standard | implemented, lifecycle checked |
| `response.create` over WebSocket | standard | implemented with persistent sessions; tested against a simulated provider with a per-connection cache, and by the official compliance suite, which tests the provider, not Moqui. The Moqui WebSocket client was not run live |
| `POST /responses/compact` | standard | implemented; the result starts a new chain |
| Request fields of `CreateResponseBody` | standard | typed in part, the rest passed through and range-checked (see the coverage matrix) |
| `prompt_cache_retention`, `safety_identifier` use and other OpenAI-only fields, SSE without `[DONE]` | provider extension | accepted only by `OpenAiResponsesProtocol`; never required by the standard protocol |
| Retrieve, cancel, list input items (paged, `limit` 1 to 100, `order`, `after`), count input tokens, `background` | not in the pinned contract (provider extension) | `OpenAiResponsesProtocol` only, gated by capability and scoped to the owner of the response; a background response is parked and polled (see Background responses) |
| File upload and hosted tools | outside the contract | not provided |

The coverage matrix (`tools/openresponses/OpenResponsesCoverage.md`) maps schemas, not operations: a schema being mapped
does not mean every field of it is exercised.

## Request validation

What a request carries of an earlier response is the **input projection** of the item (`OpenResponsesCodec.toInputItem`): the
fields the schema of the input item declares (`ItemParam`), by its `type` and `role`, and nothing a response added that a request
does not take back (a status of a reasoning item, log probabilities of an output text, who created an item). The stored item
is the response as it came and is never changed by this. The id of an item on the wire is the one the provider gave it; an id
of this application is never an identity there. A reasoning item always has its `summary` (empty if the provider gave none).
A function call without its output, or an output without its call (unless the request continues a response the provider
holds), is refused before anything is sent.

The whole request body is checked against `CreateResponseBody` of the pinned schema (a copy of the document is in the
framework resources) before anything is sent, and a compaction body against `CompactResponseMethodPublicBody`:
types, enums, limits, `oneOf` exactly one, required fields. A refusal names the field. Provider extension fields pass.
Strict tools get a closed schema (`additionalProperties:false`, every property required, local `$ref`s kept);
`tool_choice` must name a tool of the request and `required` needs tools. Replayed reasoning items carry `summary` and no
`content`, as the schema demands.

## Streaming

The stream is checked as the contract defines it (see `OpenResponsesStreamState`) and then against itself: the response of
`response.completed` and the items the stream finished must agree (same count, and per index the same type, id, text and
call arguments); a stream that does not add up is an error with the point named, never a result. SSE framing follows the
specification (LF, CR or CRLF line ends, comments, several `data:` lines, a separator split between two reads); one event is
bounded by `llm_sse_max_event_bytes` (16 MiB) and a stream by `llm_sse_max_stream_bytes` (256 MiB), and a stream past a bound
fails explicitly. The stream is journaled in batches, so the last durable point is the last batch written, not the last delta.
A tool is run only after its call item is finished and its arguments are valid JSON; the provider is not the barrier between a
model and a tool: a call to a tool the request's own `tool_choice` does not allow is refused here. A structured answer
(`text.format` json_schema) is checked against the schema the caller asked for, by the gateway, after the answer is final:
`structuredOutput.status` is `valid`, `invalid`, `refused`, `incomplete` or `not_checked`.

## Background responses

A response that comes back `queued` or `in_progress` parks the run in `LlmRunWaitProvider` with a checkpoint (provider
response id, next poll, deadline). `poll_AllLlmBackgroundResponses` (every minute) retrieves the ones whose protocol can
(`RETRIEVE`), completes the run when the provider finishes, cancels at the deadline and gives up after 5 errors. A response
that finishes with tool calls moves to Waiting for Confirmation; tools are never run from the poller. A protocol that cannot ask
again (no `RETRIEVE` capability: the standard protocol has no such operation) says so at once in the pending answer
(`pollable=false`), is never asked, and its run ends at the limit of the wait with that reason. Settings:
`llm_background_poll_seconds` (default 5), `llm_background_timeout_seconds` (default 3600).

## Compaction

`LlmClient.compact()` stores the real compact request and response (`operation=compact_response`); on success a new
completed run whose trajectory is the compaction output becomes the head, on failure the head is untouched. It refuses an
empty trajectory and a conversation with a turn in flight (409).

## Gateway (browser)

Routes (`/llm/v1`): `POST /chat`, `/chat/{id}/resume`, `/chat/{id}/cancel`, `GET /conversations`, `GET /conversations/{id}`,
`DELETE /conversations/{id}`, `GET /conversations/{id}/attachments/{n}` (the bytes of an attachment of the caller's
conversation: an image type is shown inline, anything else is a download, always `nosniff` and sandboxed), `POST
/conversations/{id}/compact`, `GET /profiles`. The history of a conversation describes an attachment (index, media type,
length, sha256), never carries its bytes. Deleting a conversation is checked for the owner or an administrator when it is
loaded, and the removal itself is the framework's own bookkeeping, not an entity delete of the person.

A browser sends text, attachments and a short list of options, never the shape of the provider request.
`attachments`: up to 8 items `{kind: image|file, mimeType, data, filename, detail}`; `data` is bare base64 (no URL, no
data URI), size capped by `llm_gateway_attachment_max_bytes` (10 MiB) and `llm_gateway_attachments_max_bytes` (20 MiB), media types
from an allow-list, file names plain. They become one user message after the text. `options` accepts `reasoning`,
`max_output_tokens`, `temperature`, `top_p`, `text`, `metadata`, `parallel_tool_calls`, `truncation`, `safety_identifier`,
`prompt_cache_key`; anything else (tools, instructions, store, previous_response_id, input...) is a 400, as is `extraBody` on an Open Responses
profile. `upstreamTransport` (`http` or `websocket`) is the transport to the provider and has nothing to do with how the
browser receives the answer (`stream` or `Accept: text/event-stream`). The answer carries `conversationId`, `runId`,
`requestId`, `responseId`, usage. With `events: "v1"` a streaming turn also sends `event: response_event` objects `{v, seq,
type, conversationId, runId, inference, responseId, itemId, outputIndex, contentIndex, providerSequence, terminal, data}`:
`seq` is the browser's own numbering, `providerSequence` the provider's (it restarts with every inference), `terminal`
ends one inference (a turn with tools has several) and the turn ends with `done`, `yield` or `error` as before. Raw
reasoning text, encrypted content and the echo of the request (instructions, tools, metadata) are never sent.

## The Assist screen

The screen (`runtime/base-component/tools/screen/Assist.qvue`, a separate commit in the runtime repository, which needs this
framework commit) lets a person choose any profile of the gateway; a thread belongs to one profile, and choosing another
starts a new one. For a profile of an Open Responses protocol it adds attachments, the choice of the transport to the provider,
a JSON result switch, compaction, and a Details panel with ids, usage, the timeline of inferences, items and parts, and an
export of the test report. It asks for the structured events (`events: "v1"`) and takes the text from them only. A stream that
ends without the end of the turn the gateway sends (`done`, `yield` or `error`) is shown as interrupted, never as completed.
The acceptance run (twelve checks, a real browser against a real Moqui) and its runbook are in
`base-component/tools/test/e2e` of the runtime repository.

## Retries

A create is not idempotent and nothing in the contract says it is. After the request may have reached the provider it is
never sent again by the client: a read timeout, a reset or a broken answer leaves the request **Uncertain**, over HTTP, SSE
and WebSocket. A 5xx answer is reported as it is (**Failed**) and not repeated, because a gateway may answer 5xx for a
request that was run. What is repeated: a 429 (the provider rejected it before running it, handled in `RestClient` with the
profile's backoff), a rate-limit error in a 200 body, and a connection that could not be opened at all (refused, no route,
unresolved host, TLS handshake, connect timeout), which is recorded **Failed** because nothing was sent. No idempotency
header is sent: the contract does not define one. Chat Completions is outside this note.

## Cleanup and recovery together

`clean#LlmData` locks each old conversation, decides again with what is true at that moment (a conversation that was
picked up again, or has a run that is not finished, is skipped), removes only what is older than the limit, and removes the
conversation only when nothing of it is left. Runs that are not finished, and everything they own, are never selected. The
concurrency tests (`LlmCleanupRecoveryConcurrencyTests`) use latches and a barrier, no sleeps: a pickup that holds the
conversation lock while cleanup starts; cleanup against a recovery claim of an expired run, which keeps its response,
context, checkpoint, envelope and fence, and gives a higher fence to a second claim that refuses the first token; and a
finished run that is removed with nothing left pointing at it. They run on H2 and on PostgreSQL.

## Stopping a worker

`testLlmResponsesCrashToolChild` stops a child JVM with `Runtime.halt` right after an external effect: no shutdown hook,
no commit, no orderly close, the lease is not released. A second JVM then recovers the run and finds the tool invocation
Uncertain, refuses to repeat the tool and leaves the run waiting for confirmation. H2 writes a commit to disk 500 ms later by
default, so the child sets `WRITE_DELAY 0` to make a commit durable as it is on PostgreSQL. The window policy and
listeners are not part of the envelope: the policy trims the Chat Completions transcript and does not touch the items of an
Open Responses request (tested: a policy set after the crash leaves the recovered context whole); listeners belong to the
caller that resumes the run.

## Retention

`clean#LlmData` removes requests, responses, items, content and events older than the retention period, except those of
runs that are not finished. An item that survives and was replayed from a removed one loses its `sourceLlmItemId`
instead of pointing at a missing row. A streamed response left `in_progress` or `interrupted` is removed with the rest
once it is old and its run is finished or absent.

## Tests

- `./gradlew :framework:testLlmResponsesOffline`: the schema validator against the pinned OpenAPI document (numeric, string
  and array limits, patterns, unions), the request bodies the real codec builds for representative inputs (each one is
  checked against the schema in the JVM when `OpenResponsesWireFixtureTests` writes it), the coverage matrix
  (`OpenResponsesCoverageTests`), protocol and codec tests, the client against loopback HTTP, SSE (fragmented byte by byte),
  WebSocket (fragmented frames) and an HTTP error matrix, the run store, recovery across separate JVMs (including a child
  process that exits after an external tool effect) and a conversation continued by another JVM. Java, Groovy and Gradle only. No
  network and no credentials.
- Optional, and not part of the build: `./gradlew :framework:testLlmResponsesOffline :framework:testOpenResponsesIndependent`
  checks the schema fixtures and the bodies the codec builds with another implementation of the schema written in Python
  (`tools/openresponses/validate-schema-fixtures.py`, needs `python3`; with `-PopenResponsesLibraryRequired=true` it builds a
  virtual environment with the pinned `jsonschema` library, once, with network, and a disagreement between the two fails).
  The schema exists once, as the framework resource `org/moqui/impl/llm/openapi-2026-04-24.json`; every tool reads that file.
- `./gradlew :framework:test`: the existing LLM and A2A suites (Chat Completions regression).
- Opt-in live tests: `LlmResponsesClientIntegrationTests` (OpenAI, set `llm_openai_api_key` and a profile named
  `openai-responses`) and `LlmResponsesProtocolTests` (Hugging Face, `hf_token`). They are skipped without credentials.
- `./gradlew :framework:testOpenResponsesProviderCompliance`: runs the official Open Responses compliance suite
  against a provider endpoint (`OPENRESPONSES_BASE_URL`, `OPENRESPONSES_API_KEY`, `OPENRESPONSES_MODEL`). It needs
  Bun `1.2.22` and network access to clone the pinned suite (see `tools/openresponses/compliance-manifest.json`) and
  reports blocked, never passed, when no endpoint is configured. It checks the provider, not Moqui.

## Limits

- `OpenResponsesStreamState` checks the lifecycle of the 24 stream events of the contract (consecutive
  `sequence_number`, items added before content and closed once, content and summary parts opened before their deltas,
  "done" text equal to the deltas, nothing after the terminal event, no completed response with an item still open). A
  violation is an error, never a success. It is strict about fields the schema marks required (`sequence_number`,
  indexes, `item_id`); it is tested with scripted fixtures and was run live against OpenAI (`gpt-4o-mini`, streaming through the client and the
  official compliance suite, 17 of 17) on 7 October 2026. No other provider was tried: one that omits a required field will fail.
- WebSocket sessions: a conversation keeps one connection across its turns (key: owner, `conv:<id>`, profile, endpoint and a
  hash of the credential); a run that belongs to no conversation has its own (`run:<id>`) and closes it when it ends. At most 64 are kept; the one idle longest is closed to make room. The next turn of a conversation continues on the
  connection with `previous_response_id` and only the new items when the head's stored context is exactly the trajectory and
  the turn has no context of its own; otherwise it replays the local trajectory. Delete, cancel and a failed turn close it. At most one response is in flight on a
  connection and a second turn started meanwhile is refused with an explicit error. A turn that ends normally leaves the
  connection open; it is closed when its run ends (a run with no conversation), after 5 minutes idle (checked whenever a session is looked up), on an
  error event, on cancellation, on any connection failure and when the facade shuts down. A call that is neither part
  of a run nor of a conversation uses a connection of its own and closes it. Policy: (1) same connection, previous turn
  completed, `store=false`: `previous_response_id` and only the new items; (2) the connection is gone or expired:
  a new chain from the full local context with no `previous_response_id` (only possible for a run that holds its whole
  context; a run started from a remote chain reports the provider's `previous_response_not_found` instead);
  (3) the connection is lost with a request in flight: the request is Uncertain and nothing is sent again;
  (4) `store=true` chains are never continued automatically. The error envelope (`status`, `error.type`, `code`,
  `message`, `param`) is kept in the result and the stored response.
- A streamed response is journaled while it arrives: the response row is written (`in_progress`) at the first event
  and events are appended in their own transactions at `response.created`, each finished output item, the terminal
  event and at most 32 events apart. The final result completes that row without writing events twice. A stream that
  ends without a terminal event leaves the row `interrupted` (a local value, not a provider status) and the request
  Uncertain. Items and content of the response are still written only with the final result.
- A run stores an encrypted envelope (`LlmRun.envelopeJson`): instructions, model, temperature, token and time limits,
  transport, response options, extra body, allow-lists and the tool definitions; never credentials or endpoints, which
  come from the profile. Recovery restores it. Tools are rebuilt only when they can be recreated exactly (service tools,
  client pass-through tools, built-in tools); a run that used caller-supplied tool code is moved to Waiting for
  Confirmation with the reason instead of continuing with a different set of tools. The window policy and per-call
  listeners are not part of the envelope.
- `OpenResponsesLimits` refuses the numeric, length and pattern limits listed in the pinned schema (for example
  `max_output_tokens` of at least 16) before anything is sent, with the path of the offending field; values are never
  corrected silently. The rest of the pinned schema is checked too (see Request validation).
- `tools/openresponses/OpenResponsesCoverage.md` maps every schema and path of the contract to its implementation,
  tests and status (typed, partial, recorded, opaque). `OpenResponsesCoverageTests` (part of the offline suite, no Python) fails when a schema is unmapped or a
  mapping points at a missing file, anchor or test. A `typed` status means a test exists, not that every field of the
  schema is exercised.
- Recovery was exercised across JVMs and a controlled process exit, not under SIGKILL fault injection on a production
  database and transaction manager.
- Full text projection and field encryption were verified on H2 and PostgreSQL; other databases need their own run.
- Non-idempotent business writes need deployment-specific reconciliation services before automatic recovery is
  enabled for those tools.
- Provider file upload APIs are out of scope. A WebSocket is never reconnected in the middle of a turn, by design.
