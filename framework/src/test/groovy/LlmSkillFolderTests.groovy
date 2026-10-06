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
import org.moqui.impl.llm.SkillIndex
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files

class LlmSkillFolderTests extends Specification {
    @Shared ExecutionContext ec
    @Shared File root

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        root = Files.createTempDirectory('skill-folders').toFile()
        new File(root, 'flat-skill.md').text = '---\nname: flat-skill\ndescription: flat one\nrisk: reversible\n---\nFlat body'

        File folder = new File(root, 'folder-skill')
        new File(folder, 'references').mkdirs()
        new File(folder, 'agents').mkdirs()
        new File(folder, 'SKILL.md').text = '''---
name: folder-skill
description: >
  Builds Moqui data files.
  Use it for seed data.
license: Apache-2.0
compatibility: Moqui with the tools component
allowed-tools: Read Grep
metadata:
  owner: someone
  moqui-risk: reversible
  moqui-profiles: "executor reviewer"
---
# Folder skill
Read references/model.md first.
'''
        new File(folder, 'references/model.md').text = 'model'
        new File(folder, 'references/huge.md').text = 'x' * 2048
        new File(folder, 'agents/openai.yaml').text = 'interface: {}'
        new File(folder, '.hidden').text = 'hidden'
        // invalid per the specification: each must be skipped
        [['unnamed-folder', '---\ndescription: no name key\n---\nBody'],
         ['mismatch-dir', '---\nname: other-name\ndescription: d\n---\nBody'],
         ['Bad--Name', '---\nname: Bad--Name\ndescription: d\n---\nBody'],
         ['no-description', '---\nname: no-description\n---\nBody'],
         ['top-level-ext', '---\nname: top-level-ext\ndescription: d\nprofiles: [executor]\n---\nBody']].each { n, text ->
            File d = new File(root, n)
            d.mkdirs()
            new File(d, 'SKILL.md').text = text
        }
        new File(root, 'not-a-skill').mkdirs()
        System.setProperty('moqui.llm.skill.file.max.bytes', '1024')
    }

    def cleanupSpec() {
        System.clearProperty('moqui.llm.skill.file.max.bytes')
        root?.deleteDir()
        ec?.destroy()
    }

    private Map<String, SkillIndex.SkillDoc> scan() {
        List<SkillIndex.SkillDoc> docs = []
        SkillIndex.scanSkillDir(ec, root.toURI().toString().replaceAll('/$', ''), docs)
        docs.collectEntries { [(it.name): it] }
    }

    def "flat skill is still found and has no folder data"() {
        when:
        def doc = scan()['flat-skill']
        then:
        doc != null
        doc.risk == 'reversible'
        doc.folderLocation == null
        doc.files == null
        doc.profiles == null
    }

    def "folder skill is found with the standard multi-line front matter"() {
        when:
        def docs = scan()
        def doc = docs['folder-skill']
        then:
        doc != null
        doc.description == 'Builds Moqui data files. Use it for seed data.'
        doc.profiles == ['executor', 'reviewer']
        doc.risk == 'reversible'
        doc.frontMatter['license'] == 'Apache-2.0'
        doc.frontMatter['allowed-tools'] == 'Read Grep'
        doc.frontMatter['metadata.owner'] == 'someone'
        doc.body.startsWith('# Folder skill')
        doc.folderLocation.contains('folder-skill')
        and: 'a folder without SKILL.md is ignored'
        !docs.containsKey('not-a-skill')
    }

    def "folder skills that break the Agent Skills specification are skipped"() {
        when:
        def docs = scan()
        then:
        !docs.containsKey('unnamed-folder')
        !docs.containsKey('other-name')
        !docs.containsKey('mismatch-dir')
        !docs.containsKey('Bad--Name')
        !docs.containsKey('no-description')
        !docs.containsKey('top-level-ext')
    }

    def "validateAgentSkill reports every broken rule"() {
        expect:
        SkillIndex.validateAgentSkill(SkillIndex.parseMarkdown('---\nname: ok-name\ndescription: fine\n---\nb', null), 'ok-name').isEmpty()
        SkillIndex.validateAgentSkill(SkillIndex.parseMarkdown('---\nname: -lead\ndescription: d\n---\nb', null), '-lead')
                .any { it.contains('lowercase') }
        SkillIndex.validateAgentSkill(SkillIndex.parseMarkdown('---\nname: ' + ('a' * 65) + '\ndescription: d\n---\nb', null), null)
                .any { it.contains('64') }
        SkillIndex.validateAgentSkill(SkillIndex.parseMarkdown('---\nname: n\ndescription: ' + ('d' * 1025) + '\n---\nb', null), 'n')
                .any { it.contains('1024') }
        SkillIndex.validateAgentSkill(SkillIndex.parseMarkdown('---\nname: n\ndescription: d\ncompatibility: ' + ('c' * 501) + '\n---\nb', null), 'n')
                .any { it.contains('500') }
        SkillIndex.validateAgentSkill(SkillIndex.parseMarkdown('---\nname: n\ndescription: d\nrisk: confirm\n---\nb', null), 'n')
                .any { it.contains('metadata.moqui-risk') }
    }

    def "file list skips agents, dot files, oversized files and SKILL.md"() {
        expect:
        scan()['folder-skill'].files == ['references/model.md']
    }

    def "profiles hide a skill from other profiles and keep unrestricted skills visible to all"() {
        given:
        def docs = scan()
        expect:
        SkillIndex.visibleTo(docs['folder-skill'], 'executor')
        SkillIndex.visibleTo(docs['folder-skill'], 'reviewer')
        !SkillIndex.visibleTo(docs['folder-skill'], 'assist')
        !SkillIndex.visibleTo(docs['folder-skill'], null)
        SkillIndex.visibleTo(docs['flat-skill'], 'assist')
        SkillIndex.visibleTo(docs['flat-skill'], null)
    }

    def "single line front matter forms keep working"() {
        when:
        def doc = SkillIndex.parseMarkdown('---\nname: x\nservices: [a.b#C, d.e#F]\nprofiles: [executor]\ndescription: "quoted: text"\n---\nb', null)
        then:
        doc.frontMatter.services == 'a.b#C, d.e#F'
        doc.profiles == ['executor']
        doc.description == 'quoted: text'
    }

    def "selected folder skill lists its files in the body"() {
        when:
        def map = org.moqui.impl.llm.FindSkillTool.toMap(ec, scan()['folder-skill'], true)
        then:
        map.body.contains('## Skill files')
        map.body.contains('- references/model.md')
        !org.moqui.impl.llm.FindSkillTool.toMap(ec, scan()['folder-skill'], false).body.contains('## Skill files')
    }
}
