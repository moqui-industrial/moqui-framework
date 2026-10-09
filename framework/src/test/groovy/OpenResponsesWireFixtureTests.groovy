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
import groovy.json.JsonOutput
import org.moqui.impl.llm.OpenResponsesCodec
import org.moqui.impl.llm.OpenResponsesProtocol
import org.moqui.llm.LlmContentPart
import org.moqui.llm.LlmItem
import org.moqui.llm.LlmMessage
import org.moqui.llm.LlmProtocol.ProtocolRequest
import org.moqui.llm.LlmResponseOptions
import org.moqui.llm.LlmTool
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

/**
 * Builds request bodies with the real codec and protocol, for representative inputs, and writes them to
 * build/openresponses-wire, and checks each of them against the pinned schema in the JVM. The optional independent check
 * (tools/openresponses, needs Python) judges the same files with another implementation of the schema.
 */
class OpenResponsesWireFixtureTests extends Specification {
    static Path outputDir() {
        Path dir = Path.of(System.getProperty('user.dir'), 'build', 'openresponses-wire')
        Files.createDirectories(dir)
        dir
    }

    private static void write(String name, String schema, Map payload) {
        def problems = org.moqui.impl.llm.OpenResponsesSchema.get().validate(schema, payload)
        assert problems.isEmpty(), "${name} is not valid against ${schema}: ${problems}"
        Files.writeString(outputDir().resolve(name + '.json'), JsonOutput.prettyPrint(JsonOutput.toJson([schema: schema, payload: payload])))
    }

    private static ProtocolRequest request() {
        ProtocolRequest r = new ProtocolRequest()
        r.profileName = 'wire'
        r.endpointUrl = 'https://provider.example/v1/responses'
        r.model = 'wire-model'
        r
    }

    def 'a text window with options and a function tool'() {
        given:
        ProtocolRequest r = request()
        r.window = [LlmMessage.system('be brief'), LlmMessage.user('hi')]
        r.maxTokens = 64
        r.temperature = 0.2d
        r.tools = [LlmTool.client('lookup', 'Lookup thing', [type: 'object', properties: [q: [type: 'string']]])]
        r.responseOptions = new LlmResponseOptions().put('store', false).put('top_p', 0.9d).put('top_logprobs', 5)
        when:
        Map body = OpenResponsesCodec.buildRequestBody(r)
        write('request-window-options-tool', 'CreateResponseBody', body)
        then:
        body.model == 'wire-model'
    }

    def 'array items with images, files and a function call trajectory'() {
        given:
        ProtocolRequest r = request()
        LlmContentPart image = new LlmContentPart()
        image.type = 'input_image'
        image.imageUrl = 'https://example.invalid/image.png'
        image.detail = 'auto'
        LlmContentPart file = new LlmContentPart()
        file.type = 'input_file'
        file.filename = 'bom.csv'
        file.fileData = 'data:text/csv;base64,YSxiCg=='
        r.inputItems = [LlmItem.message('user', [LlmContentPart.inputText('read the BOM'), image, file]),
                LlmItem.functionCall('call_1', 'lookup', '{"q":"a"}'),
                LlmItem.functionCallOutput('call_1', '{"result":"found"}')]
        r.previousResponseId = 'resp_prior'
        when:
        Map body = OpenResponsesCodec.buildRequestBody(r)
        write('request-items-trajectory', 'CreateResponseBody', body)
        then:
        body.previous_response_id == 'resp_prior'
    }

    def 'the WebSocket create event and the compact request'() {
        given:
        ProtocolRequest r = request()
        r.inputItems = [LlmItem.message('user', [LlmContentPart.inputText('continue')])]
        r.previousResponseId = 'resp_prior'
        r.responseOptions = new LlmResponseOptions().put('store', false)
        when:
        Map ws = OpenResponsesProtocol.buildWebSocketRequestBody(r)
        Map compact = OpenResponsesCodec.buildCompactRequestBody(r)
        write('request-websocket-create', 'WebSocketResponseCreateEvent', ws)
        write('request-compact', 'CompactResponseMethodPublicBody', compact)
        then:
        ws.type == 'response.create'
        !ws.containsKey('stream')
        compact.model == 'wire-model'
    }
}
