# Agent skill folders and profile filtering

Scope: series A of the Agent Executor work. Changes `SkillIndex`, `FindSkillTool` and the callers that pass the active profile. Open Responses code is untouched.

## Formats

- Flat skill: `component://*/skill/<name>.md`, unchanged.
- Folder skill: `component://*/skill/<name>/SKILL.md` plus any files under the folder (`references/`, `assets/`, `scripts/`...). The skill name is the `name` front matter key, or the folder name when absent.

## Front matter

Folder skills follow the Agent Skills specification (https://agentskills.io/specification). The specification publishes no separate JSON Schema file, so `SkillIndex.validateAgentSkill` implements its rules, and a folder skill that breaks any of them is skipped with a warning:

- `name` required, at most 64 characters, lowercase letters and digits with single hyphens, not starting or ending with one, equal to the folder name;
- `description` required, at most 1024 characters;
- `compatibility` optional, 1 to 500 characters;
- `license`, `metadata` (string to string map) and `allowed-tools` (space separated) optional;
- no other top-level field.

Moqui extensions therefore live under `metadata` with a `moqui-` prefix: `moqui-risk`, `moqui-profiles` (space or comma separated), `moqui-services`, `moqui-screens`. Flat `skill/*.md` files are not part of the specification and keep their top-level `risk`, `profiles`, `services`, `screens` keys; single-line values, quotes and `[a, b]` lists keep working.

The parser reads folded and literal block scalars, plain values continued on indented lines, `- item` lists and one level of nested mapping (`metadata:`, kept as `metadata.<key>`).

## Profile filtering

A skill with `moqui-profiles` (or top-level `profiles` in a flat file) is visible only to those profiles, in `retrieve`, `getByName`, the injected catalog (`formatInjectForQuery`), `activeWidgetText`, the risk gate and the A2A extended card (`options.profile`). A skill without `profiles` is visible to every profile. Callers that do not know a profile (the old method signatures, or a null profile) see only unrestricted skills. `nameReserved` and the sim shadowing check still consider every shipped skill, so a hidden skill's name cannot be taken by a proposed one.

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

`LlmSkillFolderTests` (offline): flat skill found, folder skill found, folded description, license, compatibility, allowed-tools and metadata parsed, every specification rule rejected (name, folder match, description, compatibility, unknown top-level field), `profiles` visibility, file list exclusions, single-line compatibility, file list on select. Existing `LlmSkillTests`, `LlmSkillAgentTests`, `LlmClientTests` and `A2ACoreTests` pass.
