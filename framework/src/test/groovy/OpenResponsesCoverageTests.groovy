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
import org.moqui.impl.llm.OpenResponsesProtocol
import spock.lang.Specification

import java.security.MessageDigest

/**
 * The pinned Open Responses schema and tools/openresponses/OpenResponsesCoverage.json have to agree: every schema and path
 * is mapped and nothing else, the hash is the recorded one, every status is known, the implementation and test files exist,
 * the anchor occurs in the implementation file, and a typed entry names a test. The markdown matrix is generated from the JSON
 * and has to be current; run with -Dopenresponses.coverage.write=true to write it.
 */
class OpenResponsesCoverageTests extends Specification {
    static final File ROOT = new File(System.getProperty('user.dir'))
    static final File TOOLS = new File(ROOT, 'tools/openresponses')

    static String render(Map cov) {
        Map rows = cov.schemas as Map
        Map<String, Integer> counts = new TreeMap<>()
        rows.values().each { e -> counts[e.status] = (counts[e.status] ?: 0) + 1 }
        List<String> out = ['# Open Responses coverage matrix', '',
                'Generated from `OpenResponsesCoverage.json` by `OpenResponsesCoverageTests` (run it with `-Dopenresponses.coverage.write=true`); do not edit by hand.', '',
                "Contract ${cov.contract}, schema SHA-256 `${cov.schemaSha256}`.".toString(), '', '## Status meaning', '']
        (cov.statuses as Map).each { k, v -> out << "- **${k}**: ${v}".toString() }
        out.addAll(['', '## Totals', '', "${rows.size()} schemas: ${counts.collect { k, v -> "${v} ${k}" }.join(', ')}.".toString(), '',
                '## Paths', '', '| Path | Status | Implementation | Tests |', '|---|---|---|---|'])
        (cov.paths as Map).each { p, e ->
            out << "| `${p}` | ${e.status} | `${e.impl.toString().substring(e.impl.toString().lastIndexOf('/') + 1)}` | ${e.tests.collect { t -> '`' + t.toString().substring(t.toString().lastIndexOf('/') + 1) + '`' }.join(', ')} |".toString()
        }
        out.addAll(['', '## Schemas', '', '| Schema | Kind | Status | Implementation | Tests | Note |', '|---|---|---|---|---|---|'])
        rows.each { n, e ->
            String tests = e.tests ? e.tests.collect { t -> '`' + t.toString().substring(t.toString().lastIndexOf('/') + 1) + '`' }.join(', ') : 'none'
            out << "| `${n}` | ${e.kind} | ${e.status} | `${e.impl.toString().substring(e.impl.toString().lastIndexOf('/') + 1)}` | ${tests} | ${e.note} |".toString()
        }
        out.join('\n') + '\n'
    }

    def 'the coverage matrix and the pinned schema agree, and what the matrix points at exists'() {
        given:
        byte[] raw = OpenResponsesProtocol.getResourceAsStream('/org/moqui/impl/llm/openapi-2026-04-24.json').bytes
        Map schema = new JsonSlurper().parse(raw) as Map
        Map cov = new JsonSlurper().parse(new File(TOOLS, 'OpenResponsesCoverage.json')) as Map
        List<String> errors = []
        when:
        if (MessageDigest.getInstance('SHA-256').digest(raw).encodeHex().toString() != cov.schemaSha256)
            errors << 'schema hash differs from the one recorded in the coverage file'
        Set names = (schema.components as Map).schemas.keySet() as Set
        Set mapped = cov.schemas.keySet() as Set
        (names - mapped).sort().each { errors << "schema not mapped: ${it}".toString() }
        (mapped - names).sort().each { errors << "mapped schema not in the contract: ${it}".toString() }
        Set paths = schema.paths.keySet() as Set
        ((paths - cov.paths.keySet()) + (cov.paths.keySet() - paths)).sort().each { errors << "path mismatch: ${it}".toString() }
        (cov.schemas + cov.paths).each { n, e ->
            if (!(e.status in cov.statuses.keySet())) errors << "${n}: unknown status ${e.status}".toString()
            File impl = new File(ROOT, e.impl)
            if (!impl.isFile()) errors << "${n}: implementation file missing ${e.impl}".toString()
            else if (!impl.text.contains(e.anchor.toString().replace('"', ''))) errors << "${n}: anchor ${e.anchor} not found in ${e.impl}".toString()
            e.tests.each { t -> if (!new File(ROOT, t).isFile()) errors << "${n}: test file missing ${t}".toString() }
            if (e.status == 'typed' && !e.tests) errors << "${n}: typed without a test".toString()
        }
        File report = new File(TOOLS, 'OpenResponsesCoverage.md')
        String expected = render(cov)
        if (System.getProperty('openresponses.coverage.write') == 'true') report.text = expected
        else if (!report.isFile() || report.text != expected) errors << 'OpenResponsesCoverage.md is not current; run this test with -Dopenresponses.coverage.write=true'.toString()
        then:
        errors == []
    }
}
