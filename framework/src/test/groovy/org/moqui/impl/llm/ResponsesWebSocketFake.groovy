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

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A Responses WebSocket provider that behaves like the real one where it matters to a client: it accepts any number of
 * connections, answers every response.create with a conforming event stream, and keeps the response of a store=false
 * turn only on the connection that produced it. A previous_response_id that the connection does not hold is answered
 * with the previous_response_not_found error envelope and evicts the cached response. It counts connections and
 * response.create messages, and can drop a connection with a request in flight, close one between turns, or hold a
 * response until released.
 */
class ResponsesWebSocketFake implements AutoCloseable {
    static class Connection {
        int index
        volatile String cachedResponseId
        volatile boolean closed
        final List<Map> creates = new CopyOnWriteArrayList<>()
    }

    final ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName('127.0.0.1'))
    final String endpoint = "http://127.0.0.1:${server.localPort}/v1/responses"
    final List<Connection> connections = new CopyOnWriteArrayList<>()
    final List<Map> creates = new CopyOnWriteArrayList<>()
    final AtomicInteger responseCounter = new AtomicInteger()
    /** Number of leading turns that answer with a function call before the final answer. */
    volatile int toolTurns = 0
    /** Connection indexes that the provider closes right after answering. */
    final Set<Integer> closeAfterTurn = new HashSet<>()
    /** The next this-many response.create messages get no answer: the connection is dropped. */
    final AtomicInteger dropNext = new AtomicInteger()
    /** When set, every response waits for this latch before it is answered. */
    volatile CountDownLatch hold
    private volatile boolean stopping

    ResponsesWebSocketFake() {
        Thread.startDaemon('responses-ws-fake-accept') {
            while (!stopping) {
                try {
                    Socket socket = server.accept()
                    Connection connection = new Connection(index: connections.size())
                    connections << connection
                    Thread.startDaemon("responses-ws-fake-${connection.index}") { serve(socket, connection) }
                } catch (Throwable ignored) { }
            }
        }
    }

    private void serve(Socket socket, Connection connection) {
        try {
            socket.withCloseable {
                socket.soTimeout = 30000
                InputStream input = socket.inputStream
                OutputStream output = socket.outputStream
                String headers = readHeaders(input)
                String key = headers.readLines().find { it.toLowerCase().startsWith('sec-websocket-key:') }
                        ?.substring('sec-websocket-key:'.length())?.trim()
                String accept = Base64.encoder.encodeToString(MessageDigest.getInstance('SHA-1')
                        .digest((key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').getBytes('UTF-8')))
                output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: ${accept}\r\n\r\n").getBytes('UTF-8'))
                output.flush()
                while (true) {
                    Map frame = readFrame(input)
                    if (frame.opcode == 0x8) { writeFrame(output, 0x8, new byte[0]); break }
                    if (frame.opcode != 0x1) continue
                    Map body = new JsonSlurper().parseText(frame.text as String) as Map
                    connection.creates << body
                    creates << [connection: connection.index, body: body]
                    if (dropNext.get() > 0 && dropNext.decrementAndGet() >= 0) break
                    CountDownLatch latch = hold
                    if (latch != null) latch.await(10, TimeUnit.SECONDS)
                    reply(output, connection, body).each { String message -> writeFrame(output, 0x1, message.getBytes('UTF-8')) }
                    if (closeAfterTurn.contains(connection.index)) { writeFrame(output, 0x8, new byte[0]); break }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            connection.closed = true
        }
    }

    private List<String> reply(OutputStream output, Connection connection, Map body) {
        String previous = body.previous_response_id
        if (previous != null && previous != connection.cachedResponseId) {
            connection.cachedResponseId = null
            return [JsonOutput.toJson([type: 'error', status: 404, error: [type: 'invalid_request_error',
                    code: 'previous_response_not_found', message: "Previous response with id '${previous}' not found.".toString(),
                    param: 'previous_response_id']])]
        }
        int n = responseCounter.incrementAndGet()
        String id = "resp_ws_${n}"
        boolean tool = n <= toolTurns
        Map item = tool ? [id: "fc_${n}", type: 'function_call', call_id: "call_${n}", name: 'service_search',
                           arguments: '{"query":"alpha"}', status: 'completed']
                : [id: "msg_${n}", type: 'message', role: 'assistant', status: 'completed',
                   content: [[type: 'output_text', text: "answer ${n}".toString()]]]
        long seq = 0
        List<Map> events = [[type: 'response.created', response: [id: id, object: 'response', status: 'in_progress', output: []]]]
        if (tool) {
            events << [type: 'response.output_item.added', output_index: 0, item: item + [arguments: '', status: 'in_progress']]
            events << [type: 'response.function_call_arguments.delta', item_id: item.id, output_index: 0, delta: item.arguments]
            events << [type: 'response.function_call_arguments.done', item_id: item.id, output_index: 0, arguments: item.arguments]
            events << [type: 'response.output_item.done', output_index: 0, item: item]
        } else {
            Map skeleton = [id: item.id, type: 'message', role: 'assistant', status: 'in_progress', content: []]
            String text = item.content[0].text
            events << [type: 'response.output_item.added', output_index: 0, item: skeleton]
            events << [type: 'response.content_part.added', item_id: item.id, output_index: 0, content_index: 0, part: [type: 'output_text', text: '']]
            events << [type: 'response.output_text.delta', item_id: item.id, output_index: 0, content_index: 0, delta: text]
            events << [type: 'response.output_text.done', item_id: item.id, output_index: 0, content_index: 0, text: text]
            events << [type: 'response.content_part.done', item_id: item.id, output_index: 0, content_index: 0, part: item.content[0]]
            events << [type: 'response.output_item.done', output_index: 0, item: item]
        }
        events << [type: 'response.completed', response: [id: id, object: 'response', status: 'completed',
                   previous_response_id: previous, output: [item], usage: [input_tokens: 1, output_tokens: 1, total_tokens: 2]]]
        connection.cachedResponseId = id
        events.collect { Map e -> JsonOutput.toJson(e + [sequence_number: seq++]) } as List<String>
    }

    boolean waitUntil(long millis, Closure<Boolean> condition) {
        long end = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < end) {
            if (condition.call()) return true
            Thread.sleep(20)
        }
        condition.call()
    }

    private static String readHeaders(InputStream input) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream()
        int matched = 0
        List<Integer> terminator = [13, 10, 13, 10]
        while (matched < terminator.size()) {
            int value = input.read()
            if (value < 0) throw new EOFException('handshake ended early')
            bytes.write(value)
            matched = value == terminator[matched] ? matched + 1 : (value == 13 ? 1 : 0)
        }
        bytes.toString('UTF-8')
    }

    private static Map readFrame(InputStream input) {
        int first = input.read()
        int second = input.read()
        if (first < 0 || second < 0) throw new EOFException('frame ended early')
        long length = second & 0x7f
        if (length == 126) length = (input.read() << 8) | input.read()
        else if (length == 127) { length = 0; 8.times { length = (length << 8) | input.read() } }
        byte[] mask = new byte[4]
        if ((second & 0x80) != 0) input.readNBytes(mask, 0, 4)
        byte[] payload = input.readNBytes((int) length)
        if ((second & 0x80) != 0) for (int i = 0; i < payload.length; i++) payload[i] = (byte) (payload[i] ^ mask[i % 4])
        [opcode: first & 0x0f, text: new String(payload, 'UTF-8')]
    }

    private static void writeFrame(OutputStream output, int opcode, byte[] payload) {
        output.write(0x80 | opcode)
        if (payload.length < 126) output.write(payload.length)
        else if (payload.length < 65536) { output.write(126); output.write((payload.length >>> 8) & 0xff); output.write(payload.length & 0xff) }
        else { output.write(127); 8.times { int i -> output.write((int) (((long) payload.length) >>> (8 * (7 - i)) & 0xff)) } }
        output.write(payload)
        output.flush()
    }

    @Override void close() {
        stopping = true
        try { server.close() } catch (Throwable ignored) { }
    }
}
