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

## Front matter

The front matter is read as YAML with SnakeYAML's safe constructor (no custom types, no duplicate keys, no collection aliases, nesting and size bounded). A scalar is its string, a list is comma separated, a mapping gives `key.sub` entries. What the specification requires to be a string and is not (a numeric `name`, a `description` that is a list, a `metadata` value that is a number or a list) is a problem of the skill, not text: a folder skill with a problem is skipped and the reason is in `SkillIndex.shippedDiagnostics`. A flat `skill/*.md` file whose front matter is not YAML still loads through the old line parser. The front matter ends at the first line that is only `---`, so a line of dashes in the text does not end it; a byte order mark and CRLF are accepted.

## Order and names

Components are scanned in load order and the entries of a skill directory by file name, so nothing depends on the order a file system lists directories in. When two skills have the same name, a folder skill wins over a flat file in the same directory, otherwise the first one stays; the others are left out and named in `shippedDiagnostics`. The reserved-name rule still covers every shipped skill.

## Files of a folder skill

`SkillDoc.files` lists files relative to the folder in path order (each directory walked by name), excluding:

- `SKILL.md`;
- the top-level `agents/` folder (Codex metadata);
- dot files and folders;
- links and anything that is not a regular file;
- files larger than `moqui.llm.skill.file.max.bytes` (default 262144);
- anything beyond 500 files: the list stops there, `SkillDoc.filesTruncated` is true and the files after the cut are not available.

`SkillDoc.filesSkipped` counts what was left out and why. When `find_skill` selects a folder skill the body gets a `## Skill files` section listing up to 100 of them; the result also carries `digest` and `filesTruncated`. Catalog entries (query results and injection) do not.

`SkillFileAccess` is the only way the files are read: the file must be a listed one, it is opened without following a link, must be a regular file, its real path must stay inside the real path of the skill folder before opening and after reading, and the size is bounded while reading. A location that is not on a file system has no links; the same bounds and the listed-file rule apply. `SkillFileAccess.Content` says whether the bytes are text (valid UTF-8 without NUL) and carries their SHA-256.

`SkillIndex.digest` is the SHA-256 over SKILL.md and every listed file (path, size, hash). `find_skill select` records it on the client (`getSelectedSkillDigest`) so a run can notice that a skill changed after it was selected.

## Not in this series

Reading those files is done by services in the executor component (series C); this series only discovers and lists them.

## Tests

`LlmSkillAuditTests` (offline): YAML types and refusals, dashes, BOM and CRLF, legacy flat skills, duplicate names and order, deterministic truncation, links and swapped files, growth during read, binary detection, digests. `LlmSkillFolderTests` (offline): flat skill found, folder skill found, folded description, license, compatibility, allowed-tools and metadata parsed, every specification rule rejected (name, folder match, description, compatibility, unknown top-level field), `profiles` visibility, file list exclusions, single-line compatibility, file list on select. Existing `LlmSkillTests`, `LlmSkillAgentTests`, `LlmClientTests` and `A2ACoreTests` pass.
