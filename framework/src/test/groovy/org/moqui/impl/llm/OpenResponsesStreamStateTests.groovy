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
package org.moqui.impl.llm

import org.moqui.llm.LlmException
import spock.lang.Specification
import spock.lang.Unroll

/** Lifecycle rules of the Responses event stream, with the events a provider may really send wrong. */
class OpenResponsesStreamStateTests extends Specification {
    private OpenResponsesStreamState state = new OpenResponsesStreamState()
    private long seq = 0

    private void ev(String type, Map fields = [:]) {
        Map chunk = new LinkedHashMap(fields)
        chunk.type = type
        if (!chunk.containsKey('sequence_number')) chunk.sequence_number = seq
        seq = ((Number) chunk.sequence_number).longValue() + 1
        state.accept(type, chunk)
    }
    private void created() { ev('response.created', [response: [id: 'r1']]) }
    private void message(int out = 0, String id = 'm1') { ev('response.output_item.added', [output_index: out, item: [type: 'message', id: id]]) }
    private void part(int out = 0, int c = 0, String id = 'm1') { ev('response.content_part.added', [item_id: id, output_index: out, content_index: c, part: [type: 'output_text']]) }
    private void call(int out = 0, String id = 'f1') { ev('response.output_item.added', [output_index: out, item: [type: 'function_call', id: id]]) }
    private void reasoning(int out = 0, String id = 'rs1') { ev('response.output_item.added', [output_index: out, item: [type: 'reasoning', id: id]]) }
    private void text(String d, int out = 0, int c = 0, String id = 'm1') { ev('response.output_text.delta', [item_id: id, output_index: out, content_index: c, delta: d]) }
    private void itemDone(int out = 0) { ev('response.output_item.done', [output_index: out, item: [:]]) }

    def 'a complete text and tool call lifecycle is accepted'() {
        when:
        created()
        ev('response.in_progress', [response: [:]])
        message(); part(); text('Hel'); text('lo')
        ev('response.output_text.done', [item_id: 'm1', output_index: 0, content_index: 0, text: 'Hello'])
        ev('response.content_part.done', [item_id: 'm1', output_index: 0, content_index: 0, part: [:]])
        itemDone()
        call(1)
        ev('response.function_call_arguments.delta', [item_id: 'f1', output_index: 1, delta: '{"a"'])
        ev('response.function_call_arguments.delta', [item_id: 'f1', output_index: 1, delta: ':1}'])
        ev('response.function_call_arguments.done', [item_id: 'f1', output_index: 1, arguments: '{"a":1}'])
        itemDone(1)
        ev('response.completed', [response: [:]])
        then:
        state.terminal
    }

    def 'reasoning with summary parts is accepted'() {
        when:
        created(); reasoning()
        ev('response.reasoning_summary_part.added', [item_id: 'rs1', output_index: 0, summary_index: 0, part: [:]])
        ev('response.reasoning_summary_text.delta', [item_id: 'rs1', output_index: 0, summary_index: 0, delta: 'a'])
        ev('response.reasoning_summary_text.done', [item_id: 'rs1', output_index: 0, summary_index: 0, text: 'a'])
        ev('response.reasoning_summary_part.done', [item_id: 'rs1', output_index: 0, summary_index: 0, part: [:]])
        ev('response.reasoning.delta', [item_id: 'rs1', output_index: 0, content_index: 0, delta: 'think'])
        ev('response.reasoning.done', [item_id: 'rs1', output_index: 0, content_index: 0, text: 'think'])
        itemDone()
        ev('response.completed', [response: [:]])
        then:
        state.terminal
    }

    def 'a terminal event alone, an error before the start, and unknown event types are accepted'() {
        when:
        ev('response.future_extension', [x: 1])
        ev('response.completed', [response: [:]])
        then:
        state.terminal
        when:
        def other = new OpenResponsesStreamState()
        other.accept('error', [type: 'error', sequence_number: 0, error: [:]])
        then:
        other.terminal
        when: 'the WebSocket error envelope carries a status and no sequence number'
        def socketError = new OpenResponsesStreamState()
        socketError.accept('error', [type: 'error', status: 404, error: [code: 'previous_response_not_found', message: 'x']])
        then:
        socketError.terminal
    }

    def 'failed and incomplete may end the stream with an item still open'() {
        when:
        created(); message()
        ev(terminalType, [response: [:]])
        then:
        state.terminal
        where:
        terminalType << ['response.failed', 'response.incomplete', 'error']
    }

    @Unroll
    def 'violation: #name'() {
        when:
        scenario.call(this)
        then:
        LlmException e = thrown()
        e.message.contains(expected)
        where:
        name                                  | expected                          | scenario
        'item event before the response start' | 'received before response.created' | { OpenResponsesStreamStateTests t -> t.message() }
        'second response.created'              | 'second response.created'          | { OpenResponsesStreamStateTests t -> t.created(); t.created() }
        'event after the terminal event'       | 'after the terminal event'          | { OpenResponsesStreamStateTests t -> t.ev('response.completed', [response: [:]]); t.created() }
        'missing sequence number'              | 'sequence_number is required'       | { OpenResponsesStreamStateTests t -> t.state.accept('response.created', [type: 'response.created']) }
        'sequence gap'                         | 'sequence gap or duplicate'         | { OpenResponsesStreamStateTests t -> t.created(); t.ev('response.in_progress', [sequence_number: 5, response: [:]]) }
        'sequence repeated'                    | 'sequence gap or duplicate'         | { OpenResponsesStreamStateTests t -> t.created(); t.ev('response.in_progress', [sequence_number: 0, response: [:]]) }
        'item added twice'                     | 'added twice'                       | { OpenResponsesStreamStateTests t -> t.created(); t.message(); t.message() }
        'item added again after done'          | 'added twice'                       | { OpenResponsesStreamStateTests t -> t.created(); t.message(); t.itemDone(); t.message() }
        'delta before the item was added'      | 'was never added'                   | { OpenResponsesStreamStateTests t -> t.created(); t.text('x') }
        'delta before the content part'        | 'is not open'                       | { OpenResponsesStreamStateTests t -> t.created(); t.message(); t.text('x') }
        'delta after the item is done'         | 'already done'                      | { OpenResponsesStreamStateTests t -> t.created(); t.message(); t.part(); t.itemDone(); t.text('x') }
        'delta after text done'                | 'after the done event'              | { OpenResponsesStreamStateTests t -> t.created(); t.message(); t.part(); t.text('a'); t.ev('response.output_text.done', [item_id: 'm1', output_index: 0, content_index: 0, text: 'a']); t.text('b') }
        'text done differs from the deltas'    | 'differs from the deltas'           | { OpenResponsesStreamStateTests t -> t.created(); t.message(); t.part(); t.text('a'); t.ev('response.output_text.done', [item_id: 'm1', output_index: 0, content_index: 0, text: 'b']) }
        'text done twice'                      | 'finished twice'                    | { OpenResponsesStreamStateTests t -> t.created(); t.message(); t.part(); t.ev('response.output_text.done', [item_id: 'm1', output_index: 0, content_index: 0, text: '']); t.ev('response.output_text.done', [item_id: 'm1', output_index: 0, content_index: 0, text: '']) }
        'content part closed but never opened' | 'was not open'                      | { OpenResponsesStreamStateTests t -> t.created(); t.message(); t.ev('response.content_part.done', [item_id: 'm1', output_index: 0, content_index: 0, part: [:]]) }
        'wrong item_id'                        | 'does not match'                    | { OpenResponsesStreamStateTests t -> t.created(); t.message(); t.part(0, 0, 'other') }
        'missing item_id'                      | 'item_id is required'               | { OpenResponsesStreamStateTests t -> t.created(); t.message(); t.ev('response.content_part.added', [output_index: 0, content_index: 0, part: [:]]) }
        'missing output_index'                 | 'output_index is required'          | { OpenResponsesStreamStateTests t -> t.created(); t.ev('response.output_item.added', [item: [type: 'message']]) }
        'arguments on a message item'          | 'not a function_call'               | { OpenResponsesStreamStateTests t -> t.created(); t.message(); t.ev('response.function_call_arguments.delta', [item_id: 'm1', output_index: 0, delta: '{}']) }
        'arguments done differs'               | 'differ from the deltas'            | { OpenResponsesStreamStateTests t -> t.created(); t.call(); t.ev('response.function_call_arguments.delta', [item_id: 'f1', output_index: 0, delta: '{']); t.ev('response.function_call_arguments.done', [item_id: 'f1', output_index: 0, arguments: '{}']) }
        'arguments delta after done'           | 'after the done event'              | { OpenResponsesStreamStateTests t -> t.created(); t.call(); t.ev('response.function_call_arguments.done', [item_id: 'f1', output_index: 0, arguments: '{}']); t.ev('response.function_call_arguments.delta', [item_id: 'f1', output_index: 0, delta: 'x']) }
        'summary text before its part'         | 'is not open'                       | { OpenResponsesStreamStateTests t -> t.created(); t.reasoning(); t.ev('response.reasoning_summary_text.delta', [item_id: 'rs1', output_index: 0, summary_index: 0, delta: 'x']) }
        'completed with an item still open'    | 'is still open'                     | { OpenResponsesStreamStateTests t -> t.created(); t.message(); t.ev('response.completed', [response: [:]]) }
        'item type changes on done'            | 'item type changed'                 | { OpenResponsesStreamStateTests t -> t.created(); t.message(); t.ev('response.output_item.done', [output_index: 0, item: [type: 'function_call']]) }
        'non-string delta'                     | 'delta must be a string'            | { OpenResponsesStreamStateTests t -> t.created(); t.message(); t.part(); t.ev('response.output_text.delta', [item_id: 'm1', output_index: 0, content_index: 0, delta: 5]) }
    }
}
