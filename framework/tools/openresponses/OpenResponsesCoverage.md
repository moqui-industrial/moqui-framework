# Open Responses coverage matrix

Generated from `OpenResponsesCoverage.json` by `OpenResponsesCoverageTests` (run it with `-Dopenresponses.coverage.write=true`); do not edit by hand.

Contract 2026-04-24, schema SHA-256 `693f26090d206230ed22b336681f547a2882cf5b131e86743966cf71bbdeedab`.

## Status meaning

- **typed**: parsed or emitted through the typed model and covered by a test
- **partial**: typed in part or without a dedicated test
- **recorded**: accepted and persisted, no semantic handling
- **opaque**: passed through unchanged, not validated
- **none**: not handled
- **container**: union or wrapper, covered by its members

## Totals

108 schemas: 4 container, 23 opaque, 3 partial, 78 typed.

## Paths

| Path | Status | Implementation | Tests |
|---|---|---|---|
| `/responses` | typed | `OpenResponsesProtocol.java` | `LlmResponsesClientIntegrationTests.groovy` |
| `/responses/compact` | typed | `OpenResponsesProtocol.java` | `LlmResponsesProtocolTests.groovy` |

## Schemas

| Schema | Kind | Status | Implementation | Tests | Note |
|---|---|---|---|---|---|
| `AllowedToolChoice` | request | opaque | `OpenResponsesLimits.java` | `OpenResponsesLimitsTests.groovy` |  |
| `AllowedToolsParam` | request | opaque | `OpenResponsesLimits.java` | `OpenResponsesLimitsTests.groovy` | size-checked, otherwise passed through |
| `Annotation` | content | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy` |  |
| `AssistantMessageItemParam` | request item | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy`, `OpenResponsesWireFixtureTests.groovy` |  |
| `CompactResource` | compact | typed | `OpenResponsesProtocol.java` | `LlmResponsesProtocolTests.groovy` |  |
| `CompactResponseMethodPublicBody` | compact | typed | `OpenResponsesProtocol.java` | `LlmResponsesProtocolTests.groovy` |  |
| `CompactionBody` | compact | typed | `OpenResponsesProtocol.java` | `LlmResponsesProtocolTests.groovy` |  |
| `CompactionSummaryItemParam` | request item | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy` |  |
| `CreateResponseBody` | request | partial | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy`, `OpenResponsesWireFixtureTests.groovy`, `OpenResponsesLimitsTests.groovy` | typed: model, input, previous_response_id, store, stream, max_output_tokens, temperature, tools, parallel_tool_calls, max_tool_calls; the rest passes through LlmResponseOptions and is only range-checked by OpenResponsesLimits |
| `DetailEnum` | enum | typed | `OpenResponsesCodec.java` | `OpenResponsesWireFixtureTests.groovy` |  |
| `DeveloperMessageItemParam` | request item | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy`, `OpenResponsesWireFixtureTests.groovy` |  |
| `EmptyModelParam` | request | opaque | `OpenResponsesCodec.java` | `OpenResponsesWireFixtureTests.groovy` | passed through as a response option; no typed handling |
| `Error` | response | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy` |  |
| `ErrorPayload` | response | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy` |  |
| `ErrorStreamingEvent` | stream event | typed | `OpenResponsesStreamState.java` | `OpenResponsesStreamStateTests.groovy`, `LlmResponsesClientIntegrationTests.groovy` | lifecycle checked by OpenResponsesStreamState and persisted incrementally as it arrives; also feeds the assembled result |
| `FunctionCall` | response item | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy`, `LlmResponsesClientIntegrationTests.groovy` |  |
| `FunctionCallItemParam` | request item | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy`, `OpenResponsesWireFixtureTests.groovy`, `OpenResponsesLimitsTests.groovy` |  |
| `FunctionCallItemStatus` | enum | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy` | carried as string; not validated against the enum |
| `FunctionCallOutput` | response item | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy`, `LlmResponsesClientIntegrationTests.groovy` |  |
| `FunctionCallOutputItemParam` | request item | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy`, `OpenResponsesWireFixtureTests.groovy` |  |
| `FunctionCallOutputStatusEnum` | enum | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy` | carried as string; not validated against the enum |
| `FunctionCallStatus` | enum | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy` | carried as string; not validated against the enum |
| `FunctionTool` | response | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy` |  |
| `FunctionToolChoice` | request | opaque | `OpenResponsesCodec.java` | `OpenResponsesWireFixtureTests.groovy` | passed through as a response option; no typed handling |
| `FunctionToolParam` | request | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy`, `OpenResponsesLimitsTests.groovy` |  |
| `ImageDetail` | enum | typed | `OpenResponsesCodec.java` | `OpenResponsesWireFixtureTests.groovy` |  |
| `IncludeEnum` | request | opaque | `OpenResponsesCodec.java` | `OpenResponsesWireFixtureTests.groovy` | passed through as a response option; not validated |
| `IncompleteDetails` | response | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy` |  |
| `InputFileContent` | content | typed | `OpenResponsesCodec.java` | `OpenResponsesWireFixtureTests.groovy` |  |
| `InputFileContentParam` | content | typed | `OpenResponsesCodec.java` | `OpenResponsesWireFixtureTests.groovy`, `OpenResponsesLimitsTests.groovy` |  |
| `InputImageContent` | content | typed | `OpenResponsesCodec.java` | `OpenResponsesWireFixtureTests.groovy` |  |
| `InputImageContentParamAutoParam` | content | typed | `OpenResponsesCodec.java` | `OpenResponsesWireFixtureTests.groovy`, `OpenResponsesLimitsTests.groovy` |  |
| `InputTextContent` | content | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy`, `OpenResponsesWireFixtureTests.groovy` |  |
| `InputTextContentParam` | content | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy`, `OpenResponsesWireFixtureTests.groovy` |  |
| `InputTokensDetails` | response | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy`, `LlmResponsesClientIntegrationTests.groovy` |  |
| `InputVideoContent` | content | partial | `OpenResponsesCodec.java` | none | typed field, no test |
| `ItemField` | union | container | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy` | union of response items; unknown types are kept raw |
| `ItemParam` | union | container | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy` | union of the item params above |
| `ItemReferenceParam` | request item | partial | `OpenResponsesCodec.java` | none | carried as LlmItem.referenceId; no dedicated test |
| `JsonObjectResponseFormat` | request | opaque | `OpenResponsesCodec.java` | `OpenResponsesWireFixtureTests.groovy` | passed through as a response option; not validated |
| `JsonSchemaResponseFormat` | request | opaque | `OpenResponsesCodec.java` | `OpenResponsesWireFixtureTests.groovy` | passed through as a response option; not validated |
| `JsonSchemaResponseFormatParam` | request | opaque | `OpenResponsesCodec.java` | `OpenResponsesWireFixtureTests.groovy` | passed through as a response option; not validated |
| `LogProb` | content | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy` |  |
| `Message` | response item | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy`, `LlmResponsesClientIntegrationTests.groovy` |  |
| `MessageRole` | enum | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy` | carried as string; not validated against the enum |
| `MessageStatus` | enum | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy` | carried as string; not validated against the enum |
| `MetadataParam` | request | typed | `OpenResponsesLimits.java` | `OpenResponsesLimitsTests.groovy` |  |
| `OutputTextContent` | content | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy`, `OpenResponsesWireFixtureTests.groovy` |  |
| `OutputTextContentParam` | content | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy`, `OpenResponsesWireFixtureTests.groovy` |  |
| `OutputTokensDetails` | response | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy`, `LlmResponsesClientIntegrationTests.groovy` |  |
| `Reasoning` | response item | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy`, `LlmResponsesClientIntegrationTests.groovy` |  |
| `ReasoningBody` | response item | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy`, `LlmResponsesClientIntegrationTests.groovy` |  |
| `ReasoningEffortEnum` | request | opaque | `OpenResponsesCodec.java` | `OpenResponsesWireFixtureTests.groovy` | passed through as a response option; not validated |
| `ReasoningItemParam` | request item | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy` | summary and encrypted_content round-trip; replay is the caller's responsibility |
| `ReasoningParam` | request | opaque | `OpenResponsesCodec.java` | `OpenResponsesWireFixtureTests.groovy` | passed through as a response option; not validated |
| `ReasoningSummaryContentParam` | content | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy`, `OpenResponsesWireFixtureTests.groovy` |  |
| `ReasoningSummaryEnum` | request | opaque | `OpenResponsesCodec.java` | `OpenResponsesWireFixtureTests.groovy` | passed through as a response option; not validated |
| `ReasoningTextContent` | content | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy`, `OpenResponsesWireFixtureTests.groovy` |  |
| `RefusalContent` | content | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy` |  |
| `RefusalContentParam` | content | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy` |  |
| `ResponseCompletedStreamingEvent` | stream event | typed | `OpenResponsesStreamState.java` | `OpenResponsesStreamStateTests.groovy`, `LlmResponsesClientIntegrationTests.groovy` | lifecycle checked by OpenResponsesStreamState and persisted incrementally as it arrives; also feeds the assembled result |
| `ResponseContentPartAddedStreamingEvent` | stream event | typed | `OpenResponsesStreamState.java` | `OpenResponsesStreamStateTests.groovy`, `LlmResponsesClientIntegrationTests.groovy` | lifecycle checked by OpenResponsesStreamState and persisted incrementally as it arrives; not used to build the result, which comes from the terminal response |
| `ResponseContentPartDoneStreamingEvent` | stream event | typed | `OpenResponsesStreamState.java` | `OpenResponsesStreamStateTests.groovy`, `LlmResponsesClientIntegrationTests.groovy` | lifecycle checked by OpenResponsesStreamState and persisted incrementally as it arrives; not used to build the result, which comes from the terminal response |
| `ResponseCreatedStreamingEvent` | stream event | typed | `OpenResponsesStreamState.java` | `OpenResponsesStreamStateTests.groovy`, `LlmResponsesClientIntegrationTests.groovy` | lifecycle checked by OpenResponsesStreamState and persisted incrementally as it arrives; also feeds the assembled result |
| `ResponseFailedStreamingEvent` | stream event | typed | `OpenResponsesStreamState.java` | `OpenResponsesStreamStateTests.groovy`, `LlmResponsesClientIntegrationTests.groovy` | lifecycle checked by OpenResponsesStreamState and persisted incrementally as it arrives; also feeds the assembled result |
| `ResponseFunctionCallArgumentsDeltaStreamingEvent` | stream event | typed | `OpenResponsesStreamState.java` | `OpenResponsesStreamStateTests.groovy`, `LlmResponsesClientIntegrationTests.groovy` | lifecycle checked by OpenResponsesStreamState and persisted incrementally as it arrives; also feeds the assembled result |
| `ResponseFunctionCallArgumentsDoneStreamingEvent` | stream event | typed | `OpenResponsesStreamState.java` | `OpenResponsesStreamStateTests.groovy`, `LlmResponsesClientIntegrationTests.groovy` | lifecycle checked by OpenResponsesStreamState and persisted incrementally as it arrives; also feeds the assembled result |
| `ResponseInProgressStreamingEvent` | stream event | typed | `OpenResponsesStreamState.java` | `OpenResponsesStreamStateTests.groovy`, `LlmResponsesClientIntegrationTests.groovy` | lifecycle checked by OpenResponsesStreamState and persisted incrementally as it arrives; not used to build the result, which comes from the terminal response |
| `ResponseIncompleteStreamingEvent` | stream event | typed | `OpenResponsesStreamState.java` | `OpenResponsesStreamStateTests.groovy`, `LlmResponsesClientIntegrationTests.groovy` | lifecycle checked by OpenResponsesStreamState and persisted incrementally as it arrives; also feeds the assembled result |
| `ResponseOutputItemAddedStreamingEvent` | stream event | typed | `OpenResponsesStreamState.java` | `OpenResponsesStreamStateTests.groovy`, `LlmResponsesClientIntegrationTests.groovy` | lifecycle checked by OpenResponsesStreamState and persisted incrementally as it arrives; also feeds the assembled result |
| `ResponseOutputItemDoneStreamingEvent` | stream event | typed | `OpenResponsesStreamState.java` | `OpenResponsesStreamStateTests.groovy`, `LlmResponsesClientIntegrationTests.groovy` | lifecycle checked by OpenResponsesStreamState and persisted incrementally as it arrives; also feeds the assembled result |
| `ResponseOutputTextAnnotationAddedStreamingEvent` | stream event | typed | `OpenResponsesStreamState.java` | `OpenResponsesStreamStateTests.groovy`, `LlmResponsesClientIntegrationTests.groovy` | lifecycle checked by OpenResponsesStreamState and persisted incrementally as it arrives; not used to build the result, which comes from the terminal response |
| `ResponseOutputTextDeltaStreamingEvent` | stream event | typed | `OpenResponsesStreamState.java` | `OpenResponsesStreamStateTests.groovy`, `LlmResponsesClientIntegrationTests.groovy` | lifecycle checked by OpenResponsesStreamState and persisted incrementally as it arrives; also feeds the assembled result |
| `ResponseOutputTextDoneStreamingEvent` | stream event | typed | `OpenResponsesStreamState.java` | `OpenResponsesStreamStateTests.groovy`, `LlmResponsesClientIntegrationTests.groovy` | lifecycle checked by OpenResponsesStreamState and persisted incrementally as it arrives; not used to build the result, which comes from the terminal response |
| `ResponseQueuedStreamingEvent` | stream event | typed | `OpenResponsesStreamState.java` | `OpenResponsesStreamStateTests.groovy`, `LlmResponsesClientIntegrationTests.groovy` | lifecycle checked by OpenResponsesStreamState and persisted incrementally as it arrives; not used to build the result, which comes from the terminal response |
| `ResponseReasoningDeltaStreamingEvent` | stream event | typed | `OpenResponsesStreamState.java` | `OpenResponsesStreamStateTests.groovy`, `LlmResponsesClientIntegrationTests.groovy` | lifecycle checked by OpenResponsesStreamState and persisted incrementally as it arrives; not used to build the result, which comes from the terminal response |
| `ResponseReasoningDoneStreamingEvent` | stream event | typed | `OpenResponsesStreamState.java` | `OpenResponsesStreamStateTests.groovy`, `LlmResponsesClientIntegrationTests.groovy` | lifecycle checked by OpenResponsesStreamState and persisted incrementally as it arrives; not used to build the result, which comes from the terminal response |
| `ResponseReasoningSummaryDeltaStreamingEvent` | stream event | typed | `OpenResponsesStreamState.java` | `OpenResponsesStreamStateTests.groovy`, `LlmResponsesClientIntegrationTests.groovy` | lifecycle checked by OpenResponsesStreamState and persisted incrementally as it arrives; not used to build the result, which comes from the terminal response |
| `ResponseReasoningSummaryDoneStreamingEvent` | stream event | typed | `OpenResponsesStreamState.java` | `OpenResponsesStreamStateTests.groovy`, `LlmResponsesClientIntegrationTests.groovy` | lifecycle checked by OpenResponsesStreamState and persisted incrementally as it arrives; not used to build the result, which comes from the terminal response |
| `ResponseReasoningSummaryPartAddedStreamingEvent` | stream event | typed | `OpenResponsesStreamState.java` | `OpenResponsesStreamStateTests.groovy`, `LlmResponsesClientIntegrationTests.groovy` | lifecycle checked by OpenResponsesStreamState and persisted incrementally as it arrives; not used to build the result, which comes from the terminal response |
| `ResponseReasoningSummaryPartDoneStreamingEvent` | stream event | typed | `OpenResponsesStreamState.java` | `OpenResponsesStreamStateTests.groovy`, `LlmResponsesClientIntegrationTests.groovy` | lifecycle checked by OpenResponsesStreamState and persisted incrementally as it arrives; not used to build the result, which comes from the terminal response |
| `ResponseRefusalDeltaStreamingEvent` | stream event | typed | `OpenResponsesStreamState.java` | `OpenResponsesStreamStateTests.groovy`, `LlmResponsesClientIntegrationTests.groovy` | lifecycle checked by OpenResponsesStreamState and persisted incrementally as it arrives; not used to build the result, which comes from the terminal response |
| `ResponseRefusalDoneStreamingEvent` | stream event | typed | `OpenResponsesStreamState.java` | `OpenResponsesStreamStateTests.groovy`, `LlmResponsesClientIntegrationTests.groovy` | lifecycle checked by OpenResponsesStreamState and persisted incrementally as it arrives; not used to build the result, which comes from the terminal response |
| `ResponseResource` | response | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy`, `LlmResponsesClientIntegrationTests.groovy` | returned options are not yet recorded separately from requested ones (gap G13) |
| `ResponsesToolParam` | union | container | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy` | only function tools are typed |
| `ServiceTierEnum` | request | opaque | `OpenResponsesCodec.java` | `OpenResponsesWireFixtureTests.groovy` | passed through as a response option; not validated |
| `SpecificFunctionParam` | request | opaque | `OpenResponsesCodec.java` | `OpenResponsesWireFixtureTests.groovy` | passed through as a response option; no typed handling |
| `SpecificToolChoiceParam` | request | opaque | `OpenResponsesCodec.java` | `OpenResponsesWireFixtureTests.groovy` | passed through as a response option; no typed handling |
| `StreamOptionsParam` | request | opaque | `OpenResponsesCodec.java` | `OpenResponsesWireFixtureTests.groovy` | passed through as a response option; not validated |
| `SummaryTextContent` | content | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy`, `OpenResponsesWireFixtureTests.groovy` |  |
| `SystemMessageItemParam` | request item | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy`, `OpenResponsesWireFixtureTests.groovy` |  |
| `TextContent` | content | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy`, `OpenResponsesWireFixtureTests.groovy` |  |
| `TextField` | request | opaque | `OpenResponsesCodec.java` | `OpenResponsesWireFixtureTests.groovy` | passed through as a response option; not validated |
| `TextFormatParam` | request | opaque | `OpenResponsesCodec.java` | `OpenResponsesWireFixtureTests.groovy` | passed through as a response option; not validated |
| `TextParam` | request | opaque | `OpenResponsesCodec.java` | `OpenResponsesWireFixtureTests.groovy` | passed through as a response option; not validated |
| `TextResponseFormat` | request | opaque | `OpenResponsesCodec.java` | `OpenResponsesWireFixtureTests.groovy` | passed through as a response option; not validated |
| `Tool` | union | container | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy` |  |
| `ToolChoiceParam` | request | opaque | `OpenResponsesCodec.java` | `OpenResponsesWireFixtureTests.groovy` | passed through as a response option; no typed handling |
| `ToolChoiceValueEnum` | request | opaque | `OpenResponsesCodec.java` | `OpenResponsesWireFixtureTests.groovy` | passed through as a response option; no typed handling |
| `TopLogProb` | content | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy` |  |
| `TruncationEnum` | request | opaque | `OpenResponsesCodec.java` | `OpenResponsesWireFixtureTests.groovy` | passed through as a response option; not validated |
| `UrlCitationBody` | content | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy` |  |
| `UrlCitationParam` | content | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy` |  |
| `Usage` | response | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy`, `LlmResponsesClientIntegrationTests.groovy` |  |
| `UserMessageItemParam` | request item | typed | `OpenResponsesCodec.java` | `LlmResponsesProtocolTests.groovy`, `OpenResponsesWireFixtureTests.groovy` |  |
| `VerbosityEnum` | request | opaque | `OpenResponsesCodec.java` | `OpenResponsesWireFixtureTests.groovy` | passed through as a response option; not validated |
| `WebSocketErrorEvent` | websocket | typed | `OpenResponsesStreamState.java` | `LlmResponsesClientIntegrationTests.groovy` | envelope kept in the result: status, type, code, message, param |
| `WebSocketResponseCreateEvent` | websocket | typed | `OpenResponsesProtocol.java` | `LlmResponsesClientIntegrationTests.groovy` | sent over a session that stays open between turns (OpenResponsesWebSocketSession) |
