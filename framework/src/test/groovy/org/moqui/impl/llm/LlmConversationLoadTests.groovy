package org.moqui.impl.llm

import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.UserFacadeImpl
import org.moqui.llm.LlmException

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Many conversations at once (16 of 5 turns by default) against a provider that answers every request, with deletes and the retention job running in the
 * same time. Nothing may deadlock or time out, every turn that is not refused on purpose has to answer, a turn sends the whole
 * trajectory so far, and what a delete removed leaves nothing behind.
 */
class LlmConversationLoadTests extends LlmConversationSpecBase {
    /** The size of the run; set llm_load_conversations and llm_load_turns in the environment for a heavier one. */
    private static final int CONVERSATIONS = (System.getenv('llm_load_conversations') ?: '16') as int
    private static final int TURNS = (System.getenv('llm_load_turns') ?: '5') as int

    def setupSpec() {
        boolean off = ec.artifactExecution.disableAuthz()
        try {
            ec.transaction.runUseOrBegin(null, 'gateway permission failed') {
                ec.entity.makeValue('moqui.security.UserPermission').setAll([userPermissionId: 'LlmGateway', description: 'LLM gateway']).createOrUpdate()
                ec.entity.makeValue('moqui.security.UserGroup').setAll([userGroupId: 'LLM_LOAD_TEST', description: 'load test']).createOrUpdate()
                ec.entity.makeValue('moqui.security.UserGroupPermission').setAll([userGroupId: 'LLM_LOAD_TEST', userPermissionId: 'LlmGateway',
                        fromDate: new java.sql.Timestamp(1L)]).createOrUpdate()
                ec.entity.makeValue('moqui.security.UserGroupMember').setAll([userGroupId: 'LLM_LOAD_TEST', userId: USER_ID,
                        fromDate: new java.sql.Timestamp(1L)]).createOrUpdate()
            }
        } finally { if (!off) ec.artifactExecution.enableAuthz() }
    }

    def 'many conversations of several turns each, with deletes and retention running beside them'() {
        given:
        AtomicLong ids = new AtomicLong()
        List<Integer> inputSizes = new CopyOnWriteArrayList<>()
        HttpServer server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.executor = Executors.newFixedThreadPool(8)
        server.createContext('/v1/responses') { exchange ->
            Map body = new JsonSlurper().parseText(exchange.requestBody.getText('UTF-8')) as Map
            inputSizes << ((body.input instanceof List) ? ((List) body.input).size() : 1)
            byte[] bytes = answer('resp_load_' + ids.incrementAndGet(), 'ok').getBytes('UTF-8')
            exchange.responseHeaders.set('Content-Type', 'application/json')
            exchange.sendResponseHeaders(200, bytes.length)
            exchange.responseBody.withCloseable { it.write(bytes) }
        }
        server.start()
        def p = profile("http://127.0.0.1:${server.address.port}/v1/responses", 'load-conv')
        register(p)
        List errors = new CopyOnWriteArrayList()
        List<String> deleted = new CopyOnWriteArrayList<>()
        List<String> kept = new CopyOnWriteArrayList<>()
        AtomicLong answered = new AtomicLong()
        AtomicBoolean stop = new AtomicBoolean(false)
        CyclicBarrier gate = new CyclicBarrier(CONVERSATIONS + 1)
        long started = System.currentTimeMillis()

        when:
        List<Thread> workers = (0..<CONVERSATIONS).collect { int n ->
            Thread.start("load-${n}") {
                ExecutionContext tec = Moqui.getExecutionContext()
                try {
                    assert ((UserFacadeImpl) tec.user).internalLoginUser(USERNAME, false)
                    tec.artifactExecution.disableAuthz(); tec.artifactExecution.disableTarpit()
                    gate.await(30, TimeUnit.SECONDS)
                    // the path of a browser: the gateway, which does not hold the caller's transaction while the provider answers
                    String conversationId = null
                    for (int t = 0; t < TURNS; t++) {
                        Map body = [profile: p.name, user: 'turn ' + t + ' of ' + n] as Map
                        if (conversationId != null) body.conversationId = conversationId
                        Map answer = LlmGateway.chat(tec, body)
                        conversationId = answer.conversationId as String
                        assert answer.content == 'ok'
                        answered.incrementAndGet()
                    }
                    if (n % 2 == 0) {
                        LlmGateway.deleteConversationOf(tec, conversationId)
                        deleted << conversationId
                    } else kept << conversationId
                } catch (Throwable x) { errors << x }
                finally { tec.destroy() }
            }
        }
        Thread retention = Thread.start('load-retention') {
            ExecutionContext tec = Moqui.getExecutionContext()
            try {
                assert ((UserFacadeImpl) tec.user).internalLoginUser(USERNAME, false)
                tec.artifactExecution.disableAuthz(); tec.artifactExecution.disableTarpit()
                gate.await(30, TimeUnit.SECONDS)
                // a limit of a day: nothing of this test is that old, so the job has to run beside the turns and remove nothing of theirs
                while (!stop.get()) {
                    tec.service.sync().name('org.moqui.impl.LlmServices.clean#LlmData').parameter('daysToKeep', 1).disableAuthz().call()
                    Thread.sleep(200)
                }
            } catch (Throwable x) { errors << x }
            finally { tec.destroy() }
        }
        workers.each { it.join(180000) }
        stop.set(true); retention.join(30000)
        long ms = System.currentTimeMillis() - started
        errors.take(3).each { println("LOAD-ERROR " + it.toString().take(400)) }
        println("LOAD ${answered.get()} turns by ${CONVERSATIONS} conversations in ${ms} ms, ${deleted.size()} deleted, ${kept.size()} kept")

        then:
        errors.isEmpty() || { throw new AssertionError("errors: " + errors.collect { it.toString() }.take(5), errors[0]) }()
        workers.every { !it.alive }
        answered.get() == CONVERSATIONS * TURNS
        // the provider saw every turn; a conversation sends its whole trajectory, so the inputs grow by two items per turn
        inputSizes.size() == CONVERSATIONS * TURNS
        inputSizes.max() >= TURNS * 2 - 1
        deleted.every { id -> orphansOf(id).isEmpty() }
        kept.every { id -> rowsOfRuns(id) == TURNS }

        cleanup:
        server.stop(0)
        unregister('load-conv')
        kept.each { id -> try { LlmGateway.deleteConversationOf(ec, id) } catch (Throwable ignored) { } }
    }

    private List<String> orphansOf(String conversationId) {
        boolean off = ec.artifactExecution.disableAuthz()
        try {
            ['moqui.llm.LlmConversation', 'moqui.llm.LlmRun', 'moqui.llm.LlmRequest', 'moqui.llm.LlmResponse'].findAll { String entity ->
                ec.entity.find(entity).condition('conversationId', conversationId).useCache(false).count() > 0
            }
        } finally { if (!off) ec.artifactExecution.enableAuthz() }
    }

    private long rowsOfRuns(String conversationId) {
        boolean off = ec.artifactExecution.disableAuthz()
        try { ec.entity.find('moqui.llm.LlmRun').condition('conversationId', conversationId).useCache(false).count() }
        finally { if (!off) ec.artifactExecution.enableAuthz() }
    }
}
