import groovy.json.JsonSlurper
import org.moqui.impl.llm.OpenAiResponsesProtocol
import org.moqui.impl.llm.OpenResponsesProtocol
import org.moqui.impl.llm.LlmClientImpl
import org.moqui.llm.LlmCompactResult
import org.moqui.llm.LlmContentPart
import org.moqui.impl.llm.LlmFacadeImpl
import org.moqui.llm.LlmFinishReason
import org.moqui.llm.LlmItem
import org.moqui.llm.LlmMessage
import org.moqui.llm.LlmProtocol
import org.moqui.llm.LlmProtocol.ProtocolRequest
import org.moqui.llm.LlmProtocol.ProtocolStreamListener
import org.moqui.llm.LlmResponseOptions
import org.moqui.llm.LlmTool
import org.moqui.llm.LlmToolCall
import org.moqui.llm.OpenResponsesSpec
import org.moqui.util.RestClient
import spock.lang.IgnoreIf
import spock.lang.Specification

import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.sun.net.httpserver.HttpServer

class LlmResponsesProtocolTests extends Specification {

    def "Responses protocol has responses default path"() {
        expect:
        OpenResponsesSpec.VERSION == "2026-04-24"
        OpenResponsesSpec.OPENAPI_SHA256 == "693f26090d206230ed22b336681f547a2882cf5b131e86743966cf71bbdeedab"
        LlmFacadeImpl.ProfileState.defaultPathForProtocol("org.moqui.impl.llm.OpenResponsesProtocol") == OpenResponsesProtocol.DEFAULT_PATH
        LlmFacadeImpl.ProfileState.defaultPathForProtocol("org.moqui.impl.llm.OpenAiResponsesProtocol") == OpenAiResponsesProtocol.DEFAULT_PATH
    }


    private static ProtocolRequest req() {
        ProtocolRequest r = new ProtocolRequest()
        r.profileName = "responses"
        r.endpointUrl = "https://api.openai.com/v1/responses"
        r.model = "gpt-test"
        r.window = [LlmMessage.system("be brief"), LlmMessage.user("hi")]
        return r
    }

    def "Responses body uses input store false and flat tools"() {
        given:
        ProtocolRequest r = req()
        r.stream = true
        r.maxTokens = 123
        r.temperature = 0.2d
        r.tools = [LlmTool.client("lookup", "Lookup thing", [type:"object", properties:[q:[type:"string"]]])]
        when:
        Map body = OpenAiResponsesProtocol.buildRequestBody(r)
        then:
        body.model == "gpt-test"
        body.store == false
        body.stream == true
        body.max_output_tokens == 123
        body.input instanceof List
        body.messages == null
        body.stream_options == null
        body.tools[0].type == "function"
        body.tools[0].name == "lookup"
        body.tools[0].function == null
    }

    def "Responses body preserves canonical options and typed multimodal input"() {
        given:
        ProtocolRequest r = req()
        r.window = null
        def file = new LlmContentPart(type:"input_file", filename:"bom.csv", fileData:"data:text/csv;base64,YSxi")
        def image = new LlmContentPart(type:"input_image", imageUrl:"https://example.test/part.png", detail:"high")
        r.inputItems = [LlmItem.message("user", [LlmContentPart.inputText("check"), file, image])]
        r.responseOptions = new LlmResponseOptions()
                .put("previous_response_id", "resp_123")
                .put("include", ["output_text.logprobs"])
                .put("metadata", [tenant:"moqui"])
                .put("text", [format:[type:"json_object"]])
                .put("top_p", 0.9d)
                .put("presence_penalty", 0.1d)
                .put("frequency_penalty", 0.2d)
                .put("parallel_tool_calls", true)
                .put("stream_options", [include_usage:true])
                .put("background", false)
                .put("max_tool_calls", 4)
                .put("reasoning", [effort:"medium", summary:"auto"])
                .put("safety_identifier", "user_1")
                .put("prompt_cache_key", "bom-v1")
                .put("truncation", "auto")
                .put("instructions", "Prefer citations")
                .put("store", true)
                .put("service_tier", "auto")
                .put("top_logprobs", 2)
                .put("conversation", [id:"conv_123"])
                .put("prompt", [id:"pmpt_123", variables:[part:"BOM"]])
                .put("prompt_cache_options", [ttl:"30m"])
                .put("prompt_cache_retention", "24h")
                .put("access_programs", ["default"])
        when:
        Map body = OpenAiResponsesProtocol.buildRequestBody(r)
        then:
        body.previous_response_id == "resp_123"
        body.store == true
        body.max_tool_calls == 4
        body.reasoning.effort == "medium"
        body.prompt.id == "pmpt_123"
        body.prompt_cache_options.ttl == "30m"
        body.input[0].content*.type == ["input_text", "input_file", "input_image"]
        body.input[0].content[1].filename == "bom.csv"
        body.input[0].content[2].detail == "high"
    }

    def "Open Responses rejects provider-only request fields"() {
        given:
        ProtocolRequest r = req()
        r.responseOptions = new LlmResponseOptions().put("conversation", [id:"conv_123"])
        when:
        OpenResponsesProtocol.buildRequestBody(r)
        then:
        def error = thrown(org.moqui.llm.LlmException)
        error.message.contains("Unsupported Responses option field")
    }

    def "Responses input preserves assistant tool calls and tool outputs in order"() {
        given:
        def assistant = LlmMessage.assistant(null)
        assistant.toolCalls = [new LlmToolCall("call_1", "lookup", '{"q":"a"}'), new LlmToolCall("call_2", "lookup", '{"q":"b"}')]
        ProtocolRequest r = req()
        r.window = [LlmMessage.user("go"), assistant, LlmMessage.tool("call_1", "lookup", "A"), LlmMessage.tool("call_2", "lookup", "B")]
        when:
        List input = OpenAiResponsesProtocol.convertInput(r.window)
        then:
        input*.type == ["message", "function_call", "function_call", "function_call_output", "function_call_output"]
        input[1].call_id == "call_1"
        input[2].call_id == "call_2"
        input[3].output == "A"
        input[4].output == "B"
    }

    def "Responses parser handles text tool calls and usage"() {
        given:
        String raw = '''{
          "status":"completed","model":"gpt-test",
          "usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15},
          "output":[
            {"type":"message","role":"assistant","content":[{"type":"output_text","text":"Hello "},{"type":"output_text","text":"world"}]},
            {"type":"function_call","call_id":"call_1","name":"lookup","arguments":"{\\"q\\":\\"x\\"}"}
          ]}'''
        when:
        def result = OpenAiResponsesProtocol.parseResponse(200, raw, req())
        then:
        result.finishReason == LlmFinishReason.TOOL_CALLS
        result.content == "Hello world"
        result.toolCalls.size() == 1
        result.toolCalls[0].id == "call_1"
        result.usage.promptTokens == 10
        result.usage.completionTokens == 5
        result.usage.totalTokens == 15
    }

    def "Responses parser preserves output items annotations logprobs and token details"() {
        given:
        String raw = '''{
          "id":"resp_1","object":"response","status":"completed","model":"gpt-test","previous_response_id":"resp_0",
          "created_at":1760000000,"completed_at":1760000001,
          "usage":{"input_tokens":10,"output_tokens":8,"total_tokens":18,
            "input_tokens_details":{"cached_tokens":4},
            "output_tokens_details":{"reasoning_tokens":3}},
          "output":[
            {"id":"msg_1","type":"message","status":"completed","role":"assistant","phase":"final_answer",
             "content":[{"type":"output_text","text":"See spec","annotations":[{"type":"url_citation","url":"https://example.test/spec","title":"Spec","start_index":0,"end_index":3}],
              "logprobs":[{"token":"See","logprob":-0.1,"bytes":[83,101,101],"top_logprobs":[{"token":"See","logprob":-0.1}]}]}]},
            {"id":"rs_1","type":"reasoning","content":[{"type":"reasoning_text","text":"thought"}],
             "summary":[{"type":"summary_text","text":"checked"}]},
            {"id":"cmp_1","type":"compaction","summary":[{"type":"summary_text","text":"context"}]}
          ]}'''
        when:
        def result = OpenResponsesProtocol.parseResponse(200, raw, req())
        then:
        result.responseId == "resp_1"
        result.status == "completed"
        result.previousResponseId == "resp_0"
        result.createdAt.time == 1760000000000L
        result.completedAt.time == 1760000001000L
        result.finishReason == LlmFinishReason.STOP
        result.content == "See spec"
        result.reasoning == "thoughtcheckedcontext"
        result.outputItems.size() == 3
        result.outputItems[0].phase == "final_answer"
        result.outputItems[0].content[0].annotations[0].url == "https://example.test/spec"
        result.outputItems[0].content[0].logprobs[0].bytes == [83, 101, 101]
        result.usage.cachedInputTokens == 4
        result.usage.reasoningOutputTokens == 3
        result.responsePayload.id == "resp_1"
    }

    def "Responses parser maps incomplete token limit to LENGTH"() {
        given:
        String raw = '{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[]}'
        when:
        def result = OpenAiResponsesProtocol.parseResponse(200, raw, req())
        then:
        result.finishReason == LlmFinishReason.LENGTH
        result.errorMessage == "max_output_tokens"
    }

    def "Responses parser preserves unknown provider item and content extensions"() {
        given:
        String raw = '''{
          "id":"resp_ext","status":"completed","output":[
            {"id":"ext_1","type":"acme.analysis","status":"completed","vendor_rank":9,
             "content":[{"type":"acme.metric","text":"opaque","vendor_unit":"bar"}]}
          ]}'''
        when:
        def result = OpenResponsesProtocol.parseResponse(200, raw, req())
        then:
        result.finishReason == LlmFinishReason.STOP
        result.outputItems[0].type == 'acme.analysis'
        result.outputItems[0].payload.vendor_rank == 9
        result.outputItems[0].content[0].payload.vendor_unit == 'bar'
    }

    def "Responses extraBody cannot override protocol fields"() {
        given:
        ProtocolRequest r = req()
        r.extraBody = [store:true]
        when:
        OpenAiResponsesProtocol.buildRequestBody(r)
        then:
        thrown(org.moqui.llm.LlmException)
    }

    def "Responses SSE assembles text and function arguments"() {
        given:
        ProtocolRequest r = req()
        def assembler = new OpenAiResponsesProtocol.StreamAssembler(r)
        def listener = new TestListener()
        when:
        assembler.accept("response.created", '{"type":"response.created","sequence_number":0,"response":{"id":"resp_1","status":"in_progress","output":[]}}', listener)
        assembler.accept("response.output_item.added", '{"type":"response.output_item.added","sequence_number":1,"output_index":0,"item":{"type":"message","id":"msg_1","role":"assistant","content":[]}}', listener)
        assembler.accept("response.content_part.added", '{"type":"response.content_part.added","sequence_number":2,"item_id":"msg_1","output_index":0,"content_index":0,"part":{"type":"output_text","text":""}}', listener)
        assembler.accept("response.output_text.delta", '{"type":"response.output_text.delta","sequence_number":3,"item_id":"msg_1","output_index":0,"content_index":0,"delta":"Hi"}', listener)
        assembler.accept("response.output_text.done", '{"type":"response.output_text.done","sequence_number":4,"item_id":"msg_1","output_index":0,"content_index":0,"text":"Hi"}', listener)
        assembler.accept("response.content_part.done", '{"type":"response.content_part.done","sequence_number":5,"item_id":"msg_1","output_index":0,"content_index":0,"part":{"type":"output_text","text":"Hi"}}', listener)
        assembler.accept("response.output_item.done", '{"type":"response.output_item.done","sequence_number":6,"output_index":0,"item":{"type":"message","id":"msg_1","role":"assistant","content":[{"type":"output_text","text":"Hi"}]}}', listener)
        assembler.accept("response.output_item.added", '{"type":"response.output_item.added","sequence_number":7,"output_index":1,"item":{"type":"function_call","id":"item_1","call_id":"call_1","name":"lookup","arguments":""}}', listener)
        assembler.accept("response.function_call_arguments.delta", '{"type":"response.function_call_arguments.delta","sequence_number":8,"item_id":"item_1","output_index":1,"delta":"{\\"q\\":\\"x\\"}"}', listener)
        assembler.accept("response.function_call_arguments.done", '{"type":"response.function_call_arguments.done","sequence_number":9,"item_id":"item_1","output_index":1,"arguments":"{\\"q\\":\\"x\\"}"}', listener)
        assembler.accept("response.output_item.done", '{"type":"response.output_item.done","sequence_number":10,"output_index":1,"item":{"type":"function_call","id":"item_1","call_id":"call_1","name":"lookup","arguments":"{\\"q\\":\\"x\\"}"}}', listener)
        assembler.accept("response.completed", '{"type":"response.completed","sequence_number":11,"response":{"status":"completed","usage":{"input_tokens":1,"output_tokens":2,"total_tokens":3}}}', listener)
        assert !assembler.complete
        assembler.accept(null, '[DONE]', listener)
        def result = assembler.toResult(200, null)
        then:
        assembler.complete
        listener.deltas == ["Hi"]
        result.finishReason == LlmFinishReason.TOOL_CALLS
        result.content == "Hi"
        result.toolCalls[0].id == "call_1"
        result.toolCalls[0].arguments == '{"q":"x"}'
        result.usage.totalTokens == 3
        result.events*.type == ["response.created", "response.output_item.added", "response.content_part.added",
                "response.output_text.delta", "response.output_text.done", "response.content_part.done",
                "response.output_item.done", "response.output_item.added", "response.function_call_arguments.delta",
                "response.function_call_arguments.done", "response.output_item.done", "response.completed"]
        result.events[-1].terminal
        listener.events.size() == 12
    }

    def "ResourceReference input_file helper preserves location metadata and inline hash"() {
        given:
        File file = File.createTempFile("moqui-open-responses-", ".csv")
        file.deleteOnExit()
        file.text = "sku,qty\nA,2\n"
        def ref = org.moqui.Moqui.getExecutionContext().resource.getLocationReference(file.toURI().toString())
        when:
        LlmContentPart.inputFileReference(ref)
        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains("not reachable by the model provider")
        when:
        LlmContentPart inline = LlmContentPart.inputFileData(ref, 1024)
        then:
        inline.contentLocation?.startsWith("file:")
        inline.fileData == "c2t1LHF0eQpBLDIK"
        !inline.fileData.contains(";base64,")
        inline.contentSha256 == java.security.MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(file.toPath())).collect { String.format("%02x", it & 0xff) }.join()
        inline.contentLength == file.length()
    }

    def "Responses SSE rejects duplicate or discontinuous provider sequences"() {
        given:
        def assembler = new OpenResponsesProtocol.StreamAssembler(req())
        def listener = new TestListener()
        assembler.accept("response.created", '{"type":"response.created","sequence_number":0,"response":{"status":"in_progress"}}', listener)
        when:
        assembler.accept("response.output_text.delta", '{"type":"response.output_text.delta","sequence_number":2,"delta":"gap"}', listener)
        then:
        def error = thrown(org.moqui.llm.LlmException)
        error.message.contains("expected 1")
    }

    def "Responses stream closes on official terminal event without done sentinel"() {
        given:
        def assembler = new OpenResponsesProtocol.StreamAssembler(req())
        def listener = new TestListener()
        when:
        assembler.accept("response.completed", '{"type":"response.completed","sequence_number":0,"response":{"id":"resp_sse","status":"completed","output":[]}}', listener)
        assembler.markTransportDone()
        def result = assembler.toResult(200, null)
        then:
        assembler.complete
        result.responseId == "resp_sse"
        result.events*.type == ["response.completed"]
    }

    def "Responses error event is terminal and preserves standard error payload"() {
        given:
        def assembler = new OpenResponsesProtocol.StreamAssembler(req())
        def listener = new TestListener()
        when:
        assembler.accept("error", '{"type":"error","sequence_number":0,"error":{"code":"server_error","message":"failed"}}', listener)
        assembler.markTransportDone()
        def result = assembler.toResult(200, null)
        then:
        assembler.complete
        result.finishReason == LlmFinishReason.ERROR
        result.providerErrorCode == "server_error"
        result.errorMessage == "failed"
        result.events[0].terminal
    }

    def "Compaction request contains only fields from the frozen standard"() {
        given:
        ProtocolRequest r = req()
        r.previousResponseId = "resp_1"
        r.responseOptions = new LlmResponseOptions()
                .put("instructions", "keep decisions")
                .put("prompt_cache_key", "case-1")
                .put("temperature", 0.2d)
        when:
        Map body = OpenResponsesProtocol.buildCompactRequestBody(r)
        then:
        new OpenResponsesProtocol().supportsCompact()
        body.keySet() == ["model", "input", "previous_response_id", "instructions", "prompt_cache_key"] as Set
        body.temperature == null
        body.store == null
    }

    def "Compaction requires the model mandated by the standard"() {
        given:
        ProtocolRequest r = req()
        r.model = null
        when:
        OpenResponsesProtocol.buildCompactRequestBody(r)
        then:
        def error = thrown(org.moqui.llm.LlmException)
        error.message.contains("requires model")
    }

    def "LlmClient exposes standard compaction through the configured protocol"() {
        given:
        def protocol = new CompactProtocol()
        def profile = LlmFacadeImpl.ProfileState.forTest("responses", protocol, "gpt-test", false, 0, 0f, 0)
        def client = new LlmClientImpl(null, profile, { false })
        def input = [LlmItem.message("user", [LlmContentPart.inputText("compact this")])]
        when:
        def result = client.inputItems(input).responseOptions(new LlmResponseOptions()
                .put("instructions", "retain decisions")).compact()
        then:
        result.id == "cmp_client"
        protocol.lastRequest.model == "gpt-test"
        protocol.lastRequest.inputItems[0].content[0].text == "compact this"
        protocol.lastRequest.responseOptions.get("instructions") == "retain decisions"
    }

    def "WebSocket request uses standard flat response create framing"() {
        given:
        ProtocolRequest r = req()
        r.stream = true
        r.responseOptions = new LlmResponseOptions()
                .put("stream_options", [include_usage:true])
                .put("background", true)
        when:
        Map body = OpenResponsesProtocol.buildWebSocketRequestBody(r)
        then:
        body.type == "response.create"
        body.model == "gpt-test"
        body.input instanceof List
        !body.containsKey("stream")
        !body.containsKey("stream_options")
        !body.containsKey("background")
        OpenResponsesProtocol.webSocketUri("https://api.example.test/v1/responses?q=1").toString() ==
                "wss://api.example.test/v1/responses?q=1"
        OpenResponsesProtocol.webSocketUri("http://localhost:8080/v1/responses").toString() ==
                "ws://localhost:8080/v1/responses"
    }

    def "WebSocket terminal event completes without SSE done sentinel"() {
        given:
        def assembler = new OpenResponsesProtocol.StreamAssembler(req())
        def listener = new TestListener()
        when:
        assembler.accept("response.completed", '{"type":"response.completed","sequence_number":0,"response":{"id":"resp_ws","status":"completed","output":[]}}', listener)
        assembler.markTransportDone()
        def result = assembler.toResult(200, null)
        then:
        assembler.complete
        result.responseId == "resp_ws"
        result.finishReason == LlmFinishReason.EMPTY
    }

    def "WebSocket transport performs upgrade sends create and receives terminal response"() {
        given:
        def server = new LocalWebSocketServer()
        ProtocolRequest r = req()
        r.endpointUrl = "http://127.0.0.1:${server.port}/v1/responses"
        r.transport = org.moqui.llm.LlmTransport.WEBSOCKET
        r.timeoutSeconds = 5
        when:
        def result = new OpenResponsesProtocol().chat(r)
        then:
        server.finished.await(2, TimeUnit.SECONDS)
        server.failure == null
        new JsonSlurper().parseText(server.receivedText).type == 'response.create'
        result.responseId == 'resp_ws_transport'
        result.transport == org.moqui.llm.LlmTransport.WEBSOCKET
        cleanup:
        server.close()
    }

    def "capabilities distinguish Open Responses from OpenAI provider extensions"() {
        expect:
        new OpenResponsesProtocol().capabilities == [LlmProtocol.Capability.CREATE, LlmProtocol.Capability.TOOLS,
                LlmProtocol.Capability.STREAM_SSE, LlmProtocol.Capability.COMPACT,
                LlmProtocol.Capability.WEBSOCKET, LlmProtocol.Capability.ITEM_TRAJECTORY] as Set
        new OpenAiResponsesProtocol().capabilities.containsAll([
                LlmProtocol.Capability.RETRIEVE, LlmProtocol.Capability.CANCEL,
                LlmProtocol.Capability.LIST_INPUT_ITEMS, LlmProtocol.Capability.COUNT_INPUT_TOKENS])
        new OpenResponsesProtocol().capabilities.contains(LlmProtocol.Capability.WEBSOCKET)
    }

    def "Responses management operations use their documented provider paths"() {
        given:
        List<Map> requests = Collections.synchronizedList([])
        HttpServer server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/v1/responses') { exchange ->
            String body = exchange.requestBody.getText('UTF-8')
            requests << [method:exchange.requestMethod, path:exchange.requestURI.path, body:body]
            String response
            if (exchange.requestURI.path.endsWith('/input_items')) response = '{"object":"list","data":[]}'
            else if (exchange.requestURI.path.endsWith('/input_tokens')) response = '{"object":"response.input_tokens","input_tokens":7}'
            else if (exchange.requestURI.path.endsWith('/compact')) response = '{"id":"cmp_1","object":"response.compaction","output":[]}'
            else response = '{"id":"resp_1","status":"completed","output":[]}'
            byte[] bytes = response.getBytes('UTF-8')
            exchange.responseHeaders.set('Content-Type', 'application/json')
            exchange.sendResponseHeaders(200, bytes.length)
            exchange.responseBody.withCloseable { it.write(bytes) }
        }
        server.start()
        ProtocolRequest r = req()
        r.endpointUrl = "http://127.0.0.1:${server.address.port}/v1/responses"
        r.requestFactory = new RestClient.SimpleRequestFactory()
        when:
        def retrieved = OpenResponsesProtocol.retrieve(r, 'resp_1')
        def cancelled = OpenResponsesProtocol.cancel(r, 'resp_1')
        def items = OpenResponsesProtocol.inputItems(r, 'resp_1')
        def tokens = OpenResponsesProtocol.inputTokens(r)
        def compacted = new OpenResponsesProtocol().compact(r)
        then:
        retrieved.responseId == 'resp_1'
        cancelled.responseId == 'resp_1'
        items.object == 'list'
        tokens.input_tokens == 7
        compacted.id == 'cmp_1'
        requests*.method == ['GET', 'POST', 'GET', 'POST', 'POST']
        requests*.path == ['/v1/responses/resp_1', '/v1/responses/resp_1/cancel',
                '/v1/responses/resp_1/input_items', '/v1/responses/input_tokens', '/v1/responses/compact']
        cleanup:
        r.requestFactory?.destroy()
        server.stop(0)
    }

    @IgnoreIf({
        def k = System.getenv("HF_TOKEN") ?: System.getenv("HUGGINGFACE_API_KEY") ?:
                System.getProperty("hf_token") ?: System.getProperty("huggingface_api_key")
        k == null || k.toString().trim().isEmpty()
    })
    def "optional live Hugging Face Responses call when token is set"() {
        given:
        String key = System.getenv("HF_TOKEN") ?: System.getenv("HUGGINGFACE_API_KEY") ?:
                System.getProperty("hf_token") ?: System.getProperty("huggingface_api_key")
        String model = System.getenv("hf_responses_model") ?: System.getProperty("hf_responses_model") ?: "openai/gpt-oss-20b"
        ProtocolRequest r = new ProtocolRequest()
        r.profileName = "hf-responses-live"
        r.endpointUrl = "https://router.huggingface.co/v1/responses"
        r.apiKey = key
        r.authHeaderName = "Authorization"
        r.authHeaderValue = "Bearer " + key
        r.model = model
        r.inputItems = [LlmItem.message("user", [LlmContentPart.inputText("Reply exactly: pong")])]
        r.responseOptions = new LlmResponseOptions().put("reasoning", [effort:"low"])
        r.maxTokens = 128
        r.temperature = 0d
        r.timeoutSeconds = 60
        r.timeoutRetry = true
        r.retryMax = 1
        r.requestFactory = new RestClient.SimpleRequestFactory()
        when:
        def result = new OpenResponsesProtocol().chat(r)
        then:
        result.finishReason == LlmFinishReason.STOP || result.finishReason == LlmFinishReason.LENGTH
        result.content != null && !result.content.isBlank()
        result.responsePayload != null
        cleanup:
        r.requestFactory.destroy()
    }

    static class TestListener implements ProtocolStreamListener {
        List<String> deltas = []
        List events = []
        def result
        Throwable failure
        void onEvent(org.moqui.llm.LlmResponseEvent event) { events << event }
        void onDelta(String textDelta) { deltas << textDelta }
        void onComplete(org.moqui.llm.LlmProtocol.ProtocolResult result) { this.result = result }
        void onFailure(Throwable t) { failure = t }
    }

    static class CompactProtocol implements LlmProtocol {
        ProtocolRequest lastRequest

        @Override String getName() { "compact-test" }
        @Override boolean supportsTools() { false }
        @Override boolean supportsStreaming() { false }
        @Override boolean supportsCompact() { true }
        @Override ProtocolResult chat(ProtocolRequest request) { throw new UnsupportedOperationException() }
        @Override void chatStream(ProtocolRequest request, ProtocolStreamListener listener) {
            throw new UnsupportedOperationException()
        }
        @Override LlmCompactResult compact(ProtocolRequest request) {
            lastRequest = request
            return new LlmCompactResult(id:"cmp_client", object:"response.compaction", output:[])
        }
    }

    static class LocalWebSocketServer implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getByName('127.0.0.1'))
        final int port = server.localPort
        final CountDownLatch finished = new CountDownLatch(1)
        volatile String receivedText
        volatile Throwable failure
        final Thread thread

        LocalWebSocketServer() {
            thread = Thread.startDaemon('llm-responses-websocket-test') {
                try {
                    server.accept().withCloseable { socket ->
                        InputStream input = socket.inputStream
                        OutputStream output = socket.outputStream
                        String headers = readHeaders(input)
                        String key = headers.readLines().find { it.toLowerCase().startsWith('sec-websocket-key:') }
                                ?.substring('sec-websocket-key:'.length())?.trim()
                        String accept = Base64.encoder.encodeToString(MessageDigest.getInstance('SHA-1')
                                .digest((key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').getBytes('UTF-8')))
                        output.write(("HTTP/1.1 101 Switching Protocols\r\n" +
                                "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                                "Sec-WebSocket-Accept: ${accept}\r\n\r\n").getBytes('UTF-8'))
                        output.flush()
                        receivedText = readFrame(input)
                        writeTextFrame(output, '{"type":"response.completed","sequence_number":0,"response":{"id":"resp_ws_transport","status":"completed","output":[]}}')
                    }
                } catch (Throwable t) {
                    failure = t
                } finally {
                    finished.countDown()
                }
            }
        }

        private static String readHeaders(InputStream input) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream()
            int matched = 0
            while (matched < 4) {
                int value = input.read()
                if (value < 0) throw new EOFException('WebSocket handshake ended early')
                bytes.write(value)
                int expected = [13, 10, 13, 10][matched]
                matched = value == expected ? matched + 1 : (value == 13 ? 1 : 0)
            }
            bytes.toString('UTF-8')
        }

        private static String readFrame(InputStream input) {
            int first = input.read()
            int second = input.read()
            if (first < 0 || second < 0) throw new EOFException('WebSocket frame ended early')
            long length = second & 0x7f
            if (length == 126) length = (input.read() << 8) | input.read()
            else if (length == 127) {
                length = 0
                8.times { length = (length << 8) | input.read() }
            }
            byte[] mask = new byte[4]
            if ((second & 0x80) != 0) input.readNBytes(mask, 0, 4)
            byte[] payload = input.readNBytes((int) length)
            if ((second & 0x80) != 0) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4]
            new String(payload, 'UTF-8')
        }

        private static void writeTextFrame(OutputStream output, String text) {
            byte[] payload = text.getBytes('UTF-8')
            output.write(0x81)
            if (payload.length < 126) output.write(payload.length)
            else {
                output.write(126)
                output.write((payload.length >>> 8) & 0xff)
                output.write(payload.length & 0xff)
            }
            output.write(payload)
            output.flush()
        }

        @Override void close() {
            try { server.close() } catch (Throwable ignored) { }
            thread.join(1000)
        }
    }
}
