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
import org.moqui.impl.llm.SkillFileAccess
import org.moqui.impl.llm.SkillIndex
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

/** Loader audit: real YAML, fixed order, duplicates, truncation, links, bounded reads, binary files and digests. */
class LlmSkillAuditTests extends Specification {
    @Shared ExecutionContext ec
    Path root

    def setupSpec() { ec = Moqui.getExecutionContext() }
    def cleanupSpec() { ec?.destroy() }
    def setup() { root = Files.createTempDirectory('skill-audit') }
    def cleanup() { root.toFile().deleteDir() }

    private File skill(String dirName, String front, String body = 'Body', Path parent = root) {
        File d = new File(parent.toFile(), dirName)
        d.mkdirs()
        new File(d, 'SKILL.md').text = "---\n${front}\n---\n${body}"
        d
    }
    private List<SkillIndex.SkillDoc> scan(Path dir = root, List<String> diag = null) {
        List<SkillIndex.SkillDoc> docs = []
        SkillIndex.scanSkillDir(ec, dir.toUri().toString().replaceAll('/$', ''), docs, diag)
        docs
    }
    private SkillIndex.SkillDoc one(String name) { scan().find { it.name == name } }

    def "the front matter is real YAML: types are checked, not flattened into valid-looking text"() {
        given:
        skill('numeric-name', 'name: 123\ndescription: d')
        skill('ok-skill', 'name: ok-skill\ndescription: "Colon: inside, # and quotes"\nmetadata:\n  version: "1.0"\n  moqui-tools: "a b"')
        skill('number-meta', 'name: number-meta\ndescription: d\nmetadata:\n  version: 1.0')
        skill('list-meta', 'name: list-meta\ndescription: d\nmetadata:\n  tags: [a, b]')
        skill('list-desc', 'name: list-desc\ndescription: [a, b]')
        skill('dup-key', 'name: dup-key\ndescription: d\ndescription: e')
        skill('bad-yaml', 'name: bad-yaml\ndescription: d\n\tmetadata: x')
        skill('java-tag', 'name: java-tag\ndescription: !!java.lang.ProcessBuilder [[ls]]')
        expect:
        scan()*.name == ['ok-skill']
        one('ok-skill').description == 'Colon: inside, # and quotes'
        one('ok-skill').frontMatter['metadata.version'] == '1.0'
        and: 'each refusal says why'
        List<String> diag = []
        scan(root, diag)
        diag.any { it.contains('numeric-name') && it.contains('name must be a string') }
        diag.any { it.contains('number-meta') && it.contains('metadata.version must be a string') }
        diag.any { it.contains('list-meta') && it.contains('metadata.tags must be a string') }
        diag.any { it.contains('list-desc') && it.contains('description must be a string') }
        diag.any { it.contains('dup-key') && it.contains('not valid YAML') }
        diag.any { it.contains('bad-yaml') && it.contains('not valid YAML') }
        diag.any { it.contains('java-tag') && it.contains('not valid YAML') }
    }

    def "a line of dashes inside the text does not end the front matter, and BOM and CRLF are read"() {
        given:
        new File(root.toFile(), 'folder-skill').mkdirs()
        new File(root.toFile(), 'folder-skill/SKILL.md').bytes = ('﻿---\r\nname: folder-skill\r\ndescription: "a\\n----\\nb"\r\n---\r\n# Title\r\n----\r\nrest').getBytes('UTF-8')
        when: SkillIndex.SkillDoc doc = one('folder-skill')
        then:
        doc != null
        doc.description == 'a\n----\nb'
        doc.body.startsWith('# Title') && doc.body.contains('rest')
        doc.contentDigest ==~ /[0-9a-f]{64}/
    }

    def "a flat skill written for the old line parser still loads"() {
        given: new File(root.toFile(), 'legacy.md').text = '---\nname: legacy\ndescription: Use it: now, always\nrisk: reversible\n---\nBody'
        when: SkillIndex.SkillDoc doc = one('legacy')
        then:
        doc != null
        doc.risk == 'reversible'
        doc.description == 'Use it: now, always'
        doc.folderLocation == null
    }

    def "a name that two directories share is decided by order, not by the file system, and said so"() {
        given:
        Path a = Files.createDirectory(root.resolve('a-dir'))
        Path b = Files.createDirectory(root.resolve('b-dir'))
        skill('same-name', 'name: same-name\ndescription: from b', 'B body', b)
        skill('same-name', 'name: same-name\ndescription: from a', 'A body', a)
        new File(a.toFile(), 'other.md').text = '---\nname: other\ndescription: flat\n---\nFlat'
        new File(a.toFile(), 'aaa-flat.md').text = '---\nname: same-name\ndescription: flat twin\n---\nFlat twin'
        when:
        List<String> diag = []
        List<SkillIndex.SkillDoc> docs = scan(a, diag)
        then: 'the folder skill wins over the flat file with its name, whatever the file names sort like'
        docs.findAll { it.name == 'same-name' }.size() == 1
        docs.find { it.name == 'same-name' }.description == 'from a'
        diag.any { it.contains('same-name') && it.contains('is used') }
        docs*.name == ['same-name', 'other'] || docs*.name.sort() == ['other', 'same-name']
    }

    def "the file list is in path order and a cut is deterministic and reported"() {
        given:
        File d = skill('big-skill', 'name: big-skill\ndescription: d')
        new File(d, 'references').mkdirs()
        (1..510).each { new File(d, "references/f${String.format('%04d', it)}.md").text = 'x' }
        new File(d, 'assets').mkdirs()
        new File(d, 'assets/z.xml').text = '<x/>'
        new File(d, '.hidden').text = 'h'
        new File(d, 'agents').mkdirs()
        new File(d, 'agents/o.yaml').text = 'x'
        when:
        SkillIndex.SkillDoc doc = one('big-skill')
        SkillIndex.SkillDoc again = one('big-skill')
        then:
        doc.files.size() == 500
        doc.filesTruncated
        doc.files == again.files
        doc.files.first() == 'assets/z.xml'
        doc.files[1] == 'references/f0001.md'
        doc.files.last() == 'references/f0499.md'
        doc.filesSkipped.hidden == 1 && doc.filesSkipped.agents == 1
        and:
        scan(root, [])
        List<String> diag = []
        scan(root, diag)
        diag.any { it.contains('big-skill') && it.contains('stops at 500') }
    }

    def "links are not listed and a file swapped for a link after the listing is refused"() {
        given:
        File outside = Files.createTempFile('outside', '.md').toFile()
        outside.text = 'SECRET'
        File d = skill('link-skill', 'name: link-skill\ndescription: d')
        new File(d, 'references').mkdirs()
        new File(d, 'references/real.md').text = 'real'
        new File(d, 'references/swap.md').text = 'swap'
        Files.createSymbolicLink(new File(d, 'references/out.md').toPath(), outside.toPath())
        Files.createSymbolicLink(new File(d, 'references/loop').toPath(), d.toPath())
        Files.createSymbolicLink(new File(d, 'references/dir').toPath(), outside.parentFile.toPath())
        when:
        SkillIndex.SkillDoc doc = one('link-skill')
        then:
        doc.files.sort() == ['references/real.md', 'references/swap.md']
        doc.filesSkipped.link == 3
        SkillFileAccess.read(ec, doc, 'references/real.md', 1000).asText() == 'real'

        when: 'after the listing the file is replaced by a link to a file outside the folder'
        new File(d, 'references/swap.md').delete()
        Files.createSymbolicLink(new File(d, 'references/swap.md').toPath(), outside.toPath())
        SkillFileAccess.read(ec, doc, 'references/swap.md', 1000)
        then:
        thrown(SkillFileAccess.SkillFileException)

        when: 'after the listing the folder of the file is replaced by a link'
        new File(d, 'references/real.md').delete()
        new File(d, 'references').deleteDir()
        Files.createSymbolicLink(new File(d, 'references').toPath(), outside.parentFile.toPath())
        SkillFileAccess.read(ec, doc, 'references/real.md', 1000)
        then:
        thrown(SkillFileAccess.SkillFileException)
        cleanup: outside?.delete()
    }

    def "a file that grew after the listing is refused while it is read, and a name outside the list is refused"() {
        given:
        File d = skill('grow-skill', 'name: grow-skill\ndescription: d')
        new File(d, 'references').mkdirs()
        new File(d, 'references/a.md').text = 'small'
        new File(d, 'references/b.md').text = 'b'
        SkillIndex.SkillDoc doc = one('grow-skill')
        new File(d, 'references/a.md').text = 'x' * 5000
        when: SkillFileAccess.read(ec, doc, 'references/a.md', 1000)
        then: thrown(SkillFileAccess.SkillFileException)
        when: SkillFileAccess.read(ec, doc, '../SKILL.md', 1000)
        then: thrown(SkillFileAccess.SkillFileException)
        when: SkillFileAccess.read(ec, doc, 'references/c.md', 1000)
        then: thrown(SkillFileAccess.SkillFileException)
    }

    def "binary content is told apart from text and described, not decoded"() {
        expect:
        SkillFileAccess.isText('plain'.bytes)
        SkillFileAccess.isText('àèì €'.getBytes('UTF-8'))
        !SkillFileAccess.isText([0x89, 0x50, 0x4E, 0x47, 0x00, 0x01] as byte[])
        !SkillFileAccess.isText([0xC3, 0x28] as byte[])
        SkillFileAccess.mimeType('logo.png') == 'image/png'
        SkillFileAccess.mimeType('x.unknown') == 'application/octet-stream'
        SkillFileAccess.textExtension('run.py') && SkillFileAccess.textExtension('a.XML') && !SkillFileAccess.textExtension('a.png')
    }

    def "the digest changes with any file of the skill and with nothing else"() {
        given:
        File d = skill('digest-skill', 'name: digest-skill\ndescription: d')
        new File(d, 'references').mkdirs()
        new File(d, 'references/a.md').text = 'one'
        String first = SkillIndex.digest(ec, one('digest-skill'))
        expect:
        first ==~ /[0-9a-f]{64}/
        SkillIndex.digest(ec, one('digest-skill')) == first
        when: new File(d, 'references/a.md').text = 'two'
        then: SkillIndex.digest(ec, one('digest-skill')) != first
        when:
        new File(d, 'references/a.md').text = 'one'
        String restored = SkillIndex.digest(ec, one('digest-skill'))
        new File(d, 'references/new.md').text = 'n'
        then:
        restored == first
        SkillIndex.digest(ec, one('digest-skill')) != first
        when:
        new File(d, 'references/new.md').delete()
        new File(d, 'SKILL.md').text = new File(d, 'SKILL.md').text + '\nmore'
        then: SkillIndex.digest(ec, one('digest-skill')) != first
    }
}
