# Open Responses quick start

## A profile

In the `llm-facade` of your runtime configuration, a profile of the protocol (the url, the key and the model come from settings
outside the repository):

```xml
<llm-facade>
    <profile name="responses" protocol="org.moqui.impl.llm.OpenResponsesProtocol"
            url="${llm_responses_url}" api-key="${llm_responses_api_key}" model="${llm_responses_model}"/>
</llm-facade>
```

`OpenResponsesProtocol` speaks the standard. `OpenAiResponsesProtocol` adds what only OpenAI has (retrieve, cancel, list input
items, count input tokens, a stream that ends without `[DONE]`).

## A turn

```groovy
def client = ec.llm.getClient('responses')
def response = client.newConversation().user('What is 2+2?').call()
// response.content, response.responseId, response.conversationId, response.usage
```

The same client calls work with Chat Completions profiles. What is Open Responses only: `inputItems(...)` for images and files,
`responseOptions(...)` for the request fields of the standard (a `text.format` for JSON, `reasoning`, `max_output_tokens`),
`transport(LlmTransport.WEBSOCKET)`, `previousResponse(...)`, and `compact()`.

A second turn of the same conversation: `ec.llm.getClient('responses').conversation(conversationId).user('and times 3?').call()`
(or `.stream(listener)`). The history is the structured trajectory of the runs; nothing is read from `LlmMessage`.

## Tools

Server tools run inside the turn; a call to a tool that is not offered, with arguments that are not JSON, or that the request's
`tool_choice` does not allow, is answered to the model with an error and is not run. A tool call is never run twice by recovery.

## What is stored

Requests, responses, items, content and stream events in `moqui.llm`, encrypted where they hold content. `get#LlmRequest` and
`get#LlmResponse` return them to their owner; media is shown as a size and a hash. `DELETE /llm/v1/conversations/{id}` removes a
conversation with everything of it.

## If something stops

A worker that dies mid-turn leaves its run to the recovery job (every two minutes). What is safe to repeat is repeated; what may
have had an effect waits for a person. See "Durable runs and recovery" in [OpenResponses.md](OpenResponses.md), which also lists
every state and the services to inspect and cancel a run.
