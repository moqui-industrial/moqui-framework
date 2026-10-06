# Agent skill folders and profile filtering

Scope: series A of the Agent Executor work. Changes `SkillIndex`, `FindSkillTool` and the callers that pass the active profile. Open Responses code is untouched.

## Formats

- Flat skill: `component://*/skill/<name>.md`, unchanged.
- Folder skill: `component://*/skill/<name>/SKILL.md` plus any files under the folder (`references/`, `assets/`, `scripts/`...). The skill name is the `name` front matter key, or the folder name when absent.

## Front matter

Single-line `key: value`, quoted values and `[a, b]` lists keep working. Added, from the Agent Skills standard:

- block scalars (`>` folded, `|` literal) and plain values continued on indented lines;
- `- item` lists;
- nested mappings such as `metadata:` are skipped.

Lists are stored comma separated in `frontMatter`. New optional key `profiles` (list of LLM profile names).

## Profile filtering

A skill with `profiles` is visible only to those profiles, in `retrieve`, `getByName`, the injected catalog (`formatInjectForQuery`), `activeWidgetText`, the risk gate and the A2A extended card (`options.profile`). A skill without `profiles` is visible to every profile. Callers that do not know a profile (the old method signatures, or a null profile) see only unrestricted skills. `nameReserved` and the sim shadowing check still consider every shipped skill, so a hidden skill's name cannot be taken by a proposed one.

## Files of a folder skill

`SkillDoc.files` lists files relative to the folder, sorted, excluding:

- `SKILL.md`;
- the top-level `agents/` folder (Codex metadata);
- dot files and folders;
- files larger than `moqui.llm.skill.file.max.bytes` (default 262144);
- anything beyond 500 files.

When `find_skill` selects a folder skill the body gets a `## Skill files` section listing up to 100 of them. Catalog entries (query results and injection) do not.

## Not in this series

Reading those files is done by services in the executor component (series C); this series only discovers and lists them.

## Tests

`LlmSkillFolderTests` (offline): flat skill found, folder skill found, folded description and list parsed, nested mapping skipped, `profiles` visibility, file list exclusions, single-line compatibility, file list on select. Existing `LlmSkillTests`, `LlmSkillAgentTests`, `LlmClientTests` and `A2ACoreTests` pass.
