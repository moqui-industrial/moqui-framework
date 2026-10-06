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
risk: confirm
profiles:
  - executor
  - reviewer
metadata:
  owner: someone
---
# Folder skill
Read references/model.md first.
'''
        new File(folder, 'references/model.md').text = 'model'
        new File(folder, 'references/huge.md').text = 'x' * 2048
        new File(folder, 'agents/openai.yaml').text = 'interface: {}'
        new File(folder, '.hidden').text = 'hidden'
        File unnamed = new File(root, 'unnamed-folder')
        unnamed.mkdirs()
        new File(unnamed, 'SKILL.md').text = '---\ndescription: no name key\n---\nBody'
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
        doc.body.startsWith('# Folder skill')
        !doc.frontMatter.containsKey('owner')
        doc.folderLocation.contains('folder-skill')
        and: 'a folder without a name key takes the folder name; a folder without SKILL.md is ignored'
        docs['unnamed-folder'] != null
        !docs.containsKey('not-a-skill')
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
