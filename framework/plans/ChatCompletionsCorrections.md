# Chat Completions corrections

Scope: the Chat Completions client (`OpenAiCompatProtocol`) and what it hands to the agent loop, the history and the
gateway. The reference is the OpenAI Chat Completions API as of 7 October 2026
(<https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create/>), restricted to
the subset Moqui supports: text messages, function tools, one choice, streaming with usage. This patch does not make
the client a complete implementation of that API.

Not supported, and not changed here: several alternatives (`n` other than 1), multimodal message content, the
`developer` role, custom tools, the stored-completions CRUD API, the deprecated `functions` API, and any rewrite of
`LlmMessage` or the Chat Completions entities. It is independent of the Open Responses work.

## What was wrong, how it was shown, what it does now

The "before" column was reproduced against the unchanged `llm-client` of 093b647e with a scripted loopback provider.

| ID | Before | After | Tests |
|---|---|---|---|
| CC01 refusal | `message.refusal` with null content was classified EMPTY; with `empty-retries=1` the provider was called twice and the turn failed with "empty content". | `LlmFinishReason.REFUSAL`, `LlmResponse.refusal`. One provider call, no retry, no tool. HTTP and streaming (`delta.refusal`) give the same result. Content is not replaced by the refusal. | `LlmChatCompletionsProtocolTests` CC01, `LlmChatCompletionsHistoryTests` |
| CC01 empty string | A finished `stop` with an empty content string was EMPTY and retried. | A finished completion with a content string, empty included, is a STOP. A null or absent content is still EMPTY. | same |
| CC02 stream | A malformed event was skipped (`Hello` came out of a stream with a broken frame). EOF after `finish_reason` was a success. `[DONE]` alone produced an EMPTY result. | A malformed event is an error. A finished choice and the `[DONE]` sentinel are needed; EOF before either is an error, with specific messages. A provider error inside the stream is a result with or without the sentinel. | CC02 |
| CC03 one choice | `n=2` was sent and the second choice dropped; a streamed `index=1` choice was merged into the first (`ab`). | `n` must be the integer 1 after extraBody is merged: anything else (2, 0, -1, 1.5, "1", true, null) is refused before sending. A response with more than one choice, or a streamed choice with index other than 0, is an error and no tool runs. | CC03 |
| CC04 strict | No way to send `function.strict`. | `LlmTool.getStrict()` (default null = omitted). When set, exactly `true`/`false` beside `parameters`. A strict tool whose schema has an open object or an optional property is refused before sending, through nested objects, array items, `anyOf`/`oneOf`/`allOf` and local `$ref`/`$defs` (an unresolvable `$ref` is refused); an optional parameter is expressed by listing it as required with a nullable type. The schema is never changed. Only those two rules are checked. | CC04 |
| CC05 metadata | Usage had the three totals only; nothing else of the response was kept. | `LlmResponse.metadata` (response id/object/created/model/service_tier/system_fingerprint, choice index/finish_reason/logprobs, message annotations, the complete usage object), `LlmUsage.cachedInputTokens` and `reasoningOutputTokens`. Present with raw logging off, the same for HTTP and SSE. The history message keeps the refusal, provider response id, finish reason and annotations in its existing metadata. | CC05, history tests |
| CC06 extraBody | Unverified. | Pass-through of `response_format`, `tool_choice`, `parallel_tool_calls`, `reasoning_effort`, `top_p`, penalties is tested. `model`, `messages`, `stream` and the generated `tools` override extraBody. The token limit is sent once, under the profile's `max-tokens-parameter`; the other spelling in extraBody is refused. A named `tool_choice` must name a tool that is really sent. | CC06 |

## API changes (all additive)

- `LlmFinishReason.REFUSAL`; `LlmResponse.refusal`, `LlmResponse.metadata`; the same on `ProtocolResult`.
- `LlmUsage.cachedInputTokens`, `LlmUsage.reasoningOutputTokens`.
- `LlmTool.getStrict()` (default method).
- `LlmStreamListener.onRefusalDelta` and `ProtocolStreamListener.onRefusalDelta` (default methods); the servlet emits a
  `refusal_delta` event, and the `done` event and `responseToMap` carry `refusal` and `metadata`.
- `RestClient.SseConsumer.onDone()`: called for the `[DONE]` sentinel; the default delegates to `onComplete()`, so
  existing consumers behave as before. `onComplete()` now means the connection ended.
- `OpenAiCompatEofProtocol`: for a provider that ends its stream after the last choice without `[DONE]`. Select it
  with `protocol="org.moqui.impl.llm.OpenAiCompatEofProtocol"` on that profile. There is no guessing from host or
  model, and a stream with no `finish_reason` is an error with it too.

## Behaviour to know

- A refusal together with tool calls is a refusal: the calls are not run, not put in the history, and their names are
  in `metadata.message.ignoredToolCalls`.
- A refused turn is stored as an assistant message with null content and `metadata.refusal`; when it is sent back it
  goes as `{"role":"assistant","content":null,"refusal":"..."}`.
- `content_filter` is not a refusal and keeps its own result.
- Usage in a stream is the last total reported, never a sum of snapshots; a chunk without choices that carries usage
  after `finish_reason` is read and kept.
- A stream that is cut after output is not sent again.
- `response_format` controls what the provider produces; the client does not validate the returned JSON.

## Live checks

Three opt-in tests (`optional live: ...`, skipped unless `llm_openai_api_key` is set) call OpenAI directly: text over HTTP
with usage details and metadata, streaming to the usage chunk and `[DONE]`, and a strict function tool called with valid
arguments. The refusal case is proved only with the deterministic scripted provider, by design: no refusal is provoked live.

## Not verified

No other provider was tried. The 9 failures of the full `:framework:test` run (EntityNoSqlCrud needs OpenSearch; EntityFindTests and
ToolsScreenRenderTests) are identical on the unmodified baseline.
