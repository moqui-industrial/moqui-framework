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
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.impl.llm.SimRunner
import spock.lang.Shared
import spock.lang.Specification

/** SimRunner: the same held overlay as enter_sim, for code that wants to know what would change. */
class SimRunnerTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        assert ec.user.loginUser('john.doe', 'moqui')
    }
    def cleanupSpec() { ec?.destroy() }
    def cleanup() { ec.entity.find('moqui.test.TestEntity').condition('testId', 'in', ['SIMR_1', 'SIMR_2']).disableAuthz().deleteAll() }

    private long count(String id) { ec.entity.find('moqui.test.TestEntity').condition('testId', id).disableAuthz().useCache(false).count() }

    def "writes are reported and discarded"() {
        given:
        boolean off = ec.artifactExecution.disableAuthz()
        ec.entity.makeValue('moqui.test.TestEntity').setAll([testId:'SIMR_2', testMedium:'before']).create()
        if (!off) ec.artifactExecution.enableAuthz()
        when:
        Map out = SimRunner.run(ec) {
            ec.entity.makeValue('moqui.test.TestEntity').setAll([testId:'SIMR_1', testMedium:'new']).create()
            def existing = ec.entity.find('moqui.test.TestEntity').condition('testId', 'SIMR_2').one()
            existing.testMedium = 'after'
            existing.update()
            'result'
        }
        then:
        out.ok
        out.result == 'result'
        (out.changes.collect { "${it.operation}:${it.pk.testId}".toString() } as Set) == (['create:SIMR_1', 'update:SIMR_2'] as Set)
        out.changes.find { it.pk.testId == 'SIMR_1' }.values.testMedium == 'new'
        out.changes.find { it.pk.testId == 'SIMR_2' }.values.testMedium == 'after'
        count('SIMR_1') == 0
        ec.entity.find('moqui.test.TestEntity').condition('testId', 'SIMR_2').disableAuthz().useCache(false).one().testMedium == 'before'
        !ec.entity.isTxCacheActive()
        !((org.moqui.impl.context.ExecutionContextImpl) ec).simSession
    }

    def "a failure is reported with what was changed before it and the overlay is closed"() {
        when:
        Map out = SimRunner.run(ec) {
            ec.entity.makeValue('moqui.test.TestEntity').setAll([testId:'SIMR_1']).create()
            throw new IllegalStateException('stop here')
        }
        then:
        !out.ok
        out.error == 'stop here'
        out.changes*.pk.testId == ['SIMR_1']
        count('SIMR_1') == 0
        !ec.entity.isTxCacheActive()
    }

    def "a simulation cannot start inside another"() {
        when:
        SimRunner.run(ec) { SimRunner.run(ec) { 1 } }
        then:
        def out = SimRunner.run(ec) { try { SimRunner.run(ec) { 1 }; 'no' } catch (IllegalStateException e) { 'refused' } }
        out.result == 'refused'
    }
}
