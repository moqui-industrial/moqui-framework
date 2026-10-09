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
import groovy.json.JsonSlurper
import org.moqui.impl.llm.OpenResponsesLimits
import org.moqui.impl.llm.OpenResponsesProtocol
import org.moqui.llm.LlmContentPart
import org.moqui.llm.LlmException
import org.moqui.llm.LlmItem
import org.moqui.llm.LlmProtocol.ProtocolRequest
import org.moqui.llm.LlmResponseOptions
import org.moqui.llm.LlmTool
import spock.lang.Specification
import spock.lang.Unroll

/** Values the frozen schema forbids are refused before anything is sent; nothing is corrected silently. */
class OpenResponsesLimitsTests extends Specification {
    private static ProtocolRequest request() {
        ProtocolRequest r = new ProtocolRequest()
        r.profileName = 'limits'
        r.endpointUrl = 'https://provider.example/v1/responses'
        r.model = 'limits-model'
        r.inputItems = [LlmItem.message('user', [LlmContentPart.inputText('hi')])]
        r
    }

    private Map schemas
    private Map props(String n) { (Map) ((Map) schemas.get(n)).get('properties') }

    private static String prepare(ProtocolRequest r) { new OpenResponsesProtocol().prepareBody(r) }

    def 'the numbers in the limits table are the ones in the pinned schema'() {
        given:
        def schema = new com.fasterxml.jackson.databind.ObjectMapper().readValue(OpenResponsesProtocol.getResourceAsStream('/org/moqui/impl/llm/openapi-2026-04-24.json'), Map)
        schemas = (Map) ((Map) schema.get('components')).get('schemas')
        Map create = (Map) ((Map) schemas.get('CreateResponseBody')).get('properties')
        def call = props('FunctionCallItemParam')
        def tool = props('FunctionToolParam')
        expect:
        create.max_output_tokens.anyOf[0].minimum == OpenResponsesLimits.MAX_OUTPUT_TOKENS_MIN
        create.max_tool_calls.anyOf[0].minimum == OpenResponsesLimits.MAX_TOOL_CALLS_MIN
        create.top_logprobs.anyOf[0].minimum == OpenResponsesLimits.TOP_LOGPROBS_MIN
        create.top_logprobs.anyOf[0].maximum == OpenResponsesLimits.TOP_LOGPROBS_MAX
        create.safety_identifier.anyOf[0].maxLength == OpenResponsesLimits.SHORT_ID_MAX
        create.prompt_cache_key.anyOf[0].maxLength == OpenResponsesLimits.SHORT_ID_MAX
        call.call_id.minLength == OpenResponsesLimits.NAME_MIN
        call.call_id.maxLength == OpenResponsesLimits.SHORT_ID_MAX
        call.name.maxLength == OpenResponsesLimits.SHORT_ID_MAX
        call.name.pattern == OpenResponsesLimits.FUNCTION_NAME.pattern()
        tool.name.pattern == OpenResponsesLimits.FUNCTION_NAME.pattern()
        schemas.get('MetadataParam').additionalProperties.maxLength == OpenResponsesLimits.METADATA_VALUE_MAX
        props('InputTextContentParam').text.maxLength == OpenResponsesLimits.TEXT_MAX
        props('InputFileContentParam').file_data.anyOf[0].maxLength == OpenResponsesLimits.FILE_DATA_MAX
        props('InputImageContentParamAutoParam').image_url.anyOf[0].maxLength == OpenResponsesLimits.IMAGE_URL_MAX
        props('AllowedToolsParam').tools.minItems == OpenResponsesLimits.ALLOWED_TOOLS_MIN
        props('AllowedToolsParam').tools.maxItems == OpenResponsesLimits.ALLOWED_TOOLS_MAX
    }

    @Unroll
    def 'a value at the limit is sent: #name'() {
        when:
        ProtocolRequest r = request()
        mutate.call(r)
        String json = prepare(r)
        then:
        json.startsWith('{')
        where:
        name                         | mutate
        'max_output_tokens 16'       | { ProtocolRequest pr -> pr.maxTokens = 16 }
        'top_logprobs 20'            | { ProtocolRequest pr -> pr.responseOptions = new LlmResponseOptions().put('top_logprobs', 20) }
        'top_logprobs 0'             | { ProtocolRequest pr -> pr.responseOptions = new LlmResponseOptions().put('top_logprobs', 0) }
        'function name of 64'        | { ProtocolRequest pr -> pr.tools = [LlmTool.client('a' * 64, 'd', [type: 'object', properties: [:]])] }
        'metadata value of 512'      | { ProtocolRequest pr -> pr.responseOptions = new LlmResponseOptions().put('metadata', [k: 'v' * 512]) }
    }

    @Unroll
    def 'a value outside the limit is refused before sending, with its path: #name'() {
        when:
        ProtocolRequest r = request()
        mutate.call(r)
        prepare(r)
        then:
        def error = thrown(LlmException)
        error.message.contains(path)
        r.preparedJson == null
        where:
        name                       | path                  | mutate
        'max_output_tokens 15'     | 'max_output_tokens'   | { ProtocolRequest pr -> pr.maxTokens = 15 }
        'top_logprobs 21'          | 'top_logprobs'        | { ProtocolRequest pr -> pr.responseOptions = new LlmResponseOptions().put('top_logprobs', 21) }
        'top_logprobs -1'          | 'top_logprobs'        | { ProtocolRequest pr -> pr.responseOptions = new LlmResponseOptions().put('top_logprobs', -1) }
        'tool name of 65'          | 'tools[0].name'       | { ProtocolRequest pr -> pr.tools = [LlmTool.client('a' * 65, 'd', [type: 'object', properties: [:]])] }
        'tool name with a space'   | 'tools[0].name'       | { ProtocolRequest pr -> pr.tools = [LlmTool.client('look up', 'd', [type: 'object', properties: [:]])] }
        'metadata value of 513'    | 'metadata.k'          | { ProtocolRequest pr -> pr.responseOptions = new LlmResponseOptions().put('metadata', [k: 'v' * 513]) }
        'call id of 65'            | 'input[1].call_id'    | { ProtocolRequest pr -> pr.inputItems << LlmItem.functionCall('c' * 65, 'lookup', '{}') }
    }
}
