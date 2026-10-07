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
package org.moqui.impl.llm;

import org.moqui.context.ArtifactExecutionFacade;
import org.moqui.context.ExecutionContext;
import org.moqui.entity.EntityCondition;
import org.moqui.entity.EntityConditionFactory;
import org.moqui.entity.EntityFind;
import org.moqui.entity.EntityList;
import org.moqui.entity.EntityValue;
import org.moqui.impl.entity.FtsSql;
import org.moqui.impl.context.ExecutionContextFactoryImpl;
import org.moqui.resource.ResourceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shipped component://…/skill/*.md plus admitted LlmSkill rows. See framework/plans/LlmSkillLearning.md.
 */
public class SkillIndex {
    private static final Logger logger = LoggerFactory.getLogger(SkillIndex.class);
    public static final int DEFAULT_LIMIT = 5;
    public static final int INJECT_CHARS = 4000;

    public static class SkillDoc {
        public String name, title, description, body, risk, sourceLocation, skillId, statusId, provenanceId;
        public Map<String, String> frontMatter = new LinkedHashMap<>();
        /** Profile names allowed to see this skill; null or empty means every profile. */
        public List<String> profiles;
        /** Folder skills only: location of the skill folder and the files in it, relative to it. */
        public String folderLocation;
        public List<String> files;
        /** Folder skills: true when the listing stopped at {@link #MAX_SKILL_FILES}; the files after that point are not listed. */
        public boolean filesTruncated;
        /** Folder skills: how many files were left out of the listing and why (hidden, oversized, link, not a regular file). */
        public Map<String, Integer> filesSkipped = new LinkedHashMap<>();
        /** Problems found in the front matter (not YAML, a number where a string is required, a duplicate key). */
        public List<String> problems = new ArrayList<>();
        /** SHA-256 of the SKILL.md text as it was read. */
        public String contentDigest;
    }

    private static final java.util.regex.Pattern FRONT_MATTER =
            java.util.regex.Pattern.compile("\\A---[ \\t]*\n(?:(.*?)\n)?---[ \\t]*(?:\n|\\z)", java.util.regex.Pattern.DOTALL);
    static final int MAX_FRONT_MATTER_CHARS = 65536;

    public static SkillDoc parseMarkdown(String text, String sourceLocation) {
        SkillDoc doc = new SkillDoc();
        doc.sourceLocation = sourceLocation;
        if (text == null) { doc.body = ""; return doc; }
        String t = text.replace("\r\n", "\n").replace('\r', '\n');
        if (t.startsWith("\uFEFF")) t = t.substring(1);
        doc.contentDigest = SkillFileAccess.sha256(t.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.util.regex.Matcher m = FRONT_MATTER.matcher(t);
        if (m.find()) {
            String fm = m.group(1) == null ? "" : m.group(1);
            if (fm.length() > MAX_FRONT_MATTER_CHARS) doc.problems.add("front matter is longer than " + MAX_FRONT_MATTER_CHARS + " characters");
            else if (!parseFrontMatterYaml(fm, doc.frontMatter, doc.problems)) {
                // a flat skill written for the old line parser still loads; a folder skill with these problems is skipped
                doc.frontMatter.clear();
                parseFrontMatter(fm, doc.frontMatter);
            }
            doc.body = t.substring(m.end()).trim();
        } else {
            doc.body = t;
        }
        doc.name = nz(doc.frontMatter.get("name"));
        if (doc.name.isEmpty() && sourceLocation != null) doc.name = nameFromLocation(sourceLocation);
        doc.title = nz(doc.frontMatter.get("title"));
        if (doc.title.isEmpty()) doc.title = doc.name;
        doc.description = nz(doc.frontMatter.get("description"));
        doc.risk = nz(extension(doc.frontMatter, "risk"));
        if (doc.risk.isEmpty()) doc.risk = "confirm";
        doc.profiles = splitList(extension(doc.frontMatter, "profiles"));
        doc.statusId = "LsksActive";
        doc.provenanceId = "LskpHuman";
        return doc;
    }

    private static final java.util.Set<String> STRING_KEYS = new java.util.HashSet<>(Arrays.asList(
            "name", "description", "license", "compatibility", "allowed-tools"));

    /**
     * Reads the front matter as YAML (SnakeYAML, safe constructor: no custom types, no aliases, no duplicate keys, bounded
     * nesting) and flattens it into the map the rest of the code uses: a scalar is its string, a list is comma separated,
     * a mapping gives "key.sub" entries. What the Agent Skills specification requires to be a string and is not (a name
     * that is a number, a metadata value that is a list or a number) is reported in problems, not converted silently.
     * Returns false when the text is not YAML at all.
     */
    static boolean parseFrontMatterYaml(String fm, Map<String, String> out, List<String> problems) {
        Object doc;
        try {
            org.yaml.snakeyaml.LoaderOptions lo = new org.yaml.snakeyaml.LoaderOptions();
            lo.setAllowDuplicateKeys(false);
            lo.setMaxAliasesForCollections(0);
            lo.setNestingDepthLimit(8);
            lo.setCodePointLimit(MAX_FRONT_MATTER_CHARS * 2);
            doc = new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(lo)).load(fm);
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().split("\n")[0];
            problems.add("front matter is not valid YAML: " + msg);
            return false;
        }
        if (doc == null) return true;
        if (!(doc instanceof Map)) { problems.add("front matter is not a mapping"); return false; }
        for (Map.Entry<?, ?> e : ((Map<?, ?>) doc).entrySet()) {
            if (!(e.getKey() instanceof String)) { problems.add("front matter key " + e.getKey() + " is not a string"); continue; }
            String k = (String) e.getKey();
            Object v = e.getValue();
            if (v == null) { out.put(k, ""); continue; }
            if (isScalar(v)) {
                if (STRING_KEYS.contains(k) && !(v instanceof String)) problems.add(k + " must be a string");
                out.put(k, String.valueOf(v).strip());
            } else if (v instanceof List) {
                List<String> items = new ArrayList<>();
                for (Object item : (List<?>) v) {
                    if (isScalar(item)) items.add(String.valueOf(item));
                    else problems.add(k + " has a list item that is not a scalar");
                }
                if (STRING_KEYS.contains(k)) problems.add(k + " must be a string");
                out.put(k, String.join(", ", items));
            } else if (v instanceof Map) {
                for (Map.Entry<?, ?> sub : ((Map<?, ?>) v).entrySet()) {
                    String sk = String.valueOf(sub.getKey());
                    Object sv = sub.getValue();
                    if (sv == null) { out.put(k + "." + sk, ""); continue; }
                    if (!isScalar(sv)) { problems.add(k + "." + sk + " must be a string"); continue; }
                    if ("metadata".equals(k) && !(sv instanceof String)) problems.add("metadata." + sk + " must be a string (quote the value)");
                    out.put(k + "." + sk, String.valueOf(sv).strip());
                }
            } else problems.add(k + " has a value that is not supported");
        }
        return true;
    }

    private static boolean isScalar(Object v) { return v instanceof String || v instanceof Number || v instanceof Boolean || v instanceof java.util.Date; }

    /**
     * Moqui keys (risk, profiles, services, screens). Flat skills may use them as top level keys; folder skills follow
     * the Agent Skills specification, which allows only name, description, license, compatibility, metadata and
     * allowed-tools, so there they live under metadata as moqui-risk, moqui-profiles and so on.
     */
    static String extension(Map<String, String> frontMatter, String key) {
        String v = frontMatter.get("metadata." + EXT_PREFIX + key);
        return v != null ? v : frontMatter.get(key);
    }
    static final String EXT_PREFIX = "moqui-";
    static final java.util.Set<String> STANDARD_KEYS = new java.util.HashSet<>(Arrays.asList(
            "name", "description", "license", "compatibility", "metadata", "allowed-tools"));
    private static final java.util.regex.Pattern STANDARD_NAME =
            java.util.regex.Pattern.compile("^[\\p{Ll}\\p{Nd}]+(-[\\p{Ll}\\p{Nd}]+)*$");

    /**
     * Checks a folder skill against the Agent Skills specification (agentskills.io/specification): required name of at
     * most 64 lowercase alphanumeric characters and single hyphens matching the folder name, required description of
     * at most 1024 characters, compatibility of at most 500, and no field outside the six defined ones.
     * Returns the problems; an empty list means the skill is valid.
     */
    public static List<String> validateAgentSkill(SkillDoc doc, String folderName) {
        List<String> errors = new ArrayList<>(doc.problems);
        String name = doc.frontMatter.get("name");
        if (name == null || name.isEmpty()) errors.add("name is required");
        else {
            if (name.length() > 64) errors.add("name is longer than 64 characters");
            if (!STANDARD_NAME.matcher(name).matches())
                errors.add("name may only contain lowercase letters, digits and single hyphens, not starting or ending with one");
            if (folderName != null && !name.equals(folderName)) errors.add("name must match the folder name " + folderName);
        }
        String description = doc.frontMatter.get("description");
        if (description == null || description.isBlank()) errors.add("description is required");
        else if (description.length() > 1024) errors.add("description is longer than 1024 characters");
        String compatibility = doc.frontMatter.get("compatibility");
        if (compatibility != null && (compatibility.isEmpty() || compatibility.length() > 500))
            errors.add("compatibility must be 1-500 characters");
        for (String key : doc.frontMatter.keySet()) {
            String top = key.indexOf('.') > 0 && key.startsWith("metadata.") ? "metadata" : key;
            if (!STANDARD_KEYS.contains(top)) errors.add("field " + top + " is not defined by the specification; use metadata."
                    + EXT_PREFIX + top + " for Moqui extensions");
        }
        return errors;
    }

    /** foo.md gives foo; foo/SKILL.md gives foo. */
    static String nameFromLocation(String sourceLocation) {
        String loc = sourceLocation;
        while (loc.endsWith("/")) loc = loc.substring(0, loc.length() - 1);
        int slash = loc.lastIndexOf('/');
        String file = slash >= 0 ? loc.substring(slash + 1) : loc;
        if ("SKILL.md".equals(file) && slash > 0) {
            String parent = loc.substring(0, slash);
            int ps = parent.lastIndexOf('/');
            return ps >= 0 ? parent.substring(ps + 1) : parent;
        }
        if (file.endsWith(".md")) file = file.substring(0, file.length() - 3);
        return file;
    }

    /**
     * Front matter subset used by shipped skills: "key: value" lines, quoted or [a, b] values, and the Agent Skills
     * forms: folded or literal block scalars (&gt; |), plain scalars continued on indented lines, and "- item" lists.
     * Nested mappings (for example metadata:) are skipped. Lists are stored comma separated.
     */
    static void parseFrontMatter(String fm, Map<String, String> out) {
        String[] lines = fm.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            int colon = line.indexOf(':');
            boolean indented = !line.isEmpty() && Character.isWhitespace(line.charAt(0));
            if (colon <= 0 || indented || line.startsWith("-")) { i++; continue; }
            String k = line.substring(0, colon).trim();
            String v = line.substring(colon + 1).trim();
            i++;
            List<String> cont = new ArrayList<>();
            while (i < lines.length && (lines[i].isEmpty() || Character.isWhitespace(lines[i].charAt(0))
                    || lines[i].startsWith("- "))) {
                cont.add(lines[i]);
                i++;
            }
            while (!cont.isEmpty() && cont.get(cont.size() - 1).trim().isEmpty()) cont.remove(cont.size() - 1);
            if (v.startsWith(">") || v.startsWith("|")) {
                boolean literal = v.startsWith("|");
                StringBuilder sb = new StringBuilder();
                for (String c : cont) {
                    String piece = c.trim();
                    if (sb.length() > 0) sb.append(literal ? "\n" : (piece.isEmpty() ? "\n" : " "));
                    sb.append(piece);
                }
                out.put(k, sb.toString().trim());
            } else if (v.isEmpty()) {
                List<String> items = new ArrayList<>();
                for (String c : cont) {
                    String piece = c.trim();
                    if (piece.startsWith("- ")) items.add(unquote(piece.substring(2).trim()));
                }
                if (!items.isEmpty()) out.put(k, String.join(", ", items));
                // one level of nested mapping (metadata:) is kept as "key.sub" entries
                for (String c : cont) {
                    String piece = c.trim();
                    int sub = piece.indexOf(':');
                    if (piece.startsWith("- ") || sub <= 0) continue;
                    out.put(k + "." + piece.substring(0, sub).trim(), unquote(piece.substring(sub + 1).trim()));
                }
            } else {
                if (v.startsWith("[") && v.endsWith("]")) v = v.substring(1, v.length() - 1);
                else {
                    StringBuilder sb = new StringBuilder(v);
                    for (String c : cont) if (!c.trim().isEmpty()) sb.append(' ').append(c.trim());
                    v = sb.toString();
                }
                out.put(k, unquote(v));
            }
        }
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))))
            return v.substring(1, v.length() - 1);
        return v;
    }

    private static List<String> splitList(String v) {
        if (v == null || v.isBlank()) return null;
        List<String> out = new ArrayList<>();
        for (String part : v.split("[,\\s]+")) {
            String p = unquote(part.trim());
            if (!p.isEmpty()) out.add(p);
        }
        return out.isEmpty() ? null : out;
    }

    /** A skill without profiles is visible to every profile; one with profiles only to those. */
    public static boolean visibleTo(SkillDoc doc, String profileName) {
        if (doc == null) return false;
        if (doc.profiles == null || doc.profiles.isEmpty()) return true;
        return profileName != null && doc.profiles.contains(profileName);
    }

    /** Largest file listed for a folder skill; set -Dmoqui.llm.skill.file.max.bytes to change. */
    static long maxSkillFileBytes() {
        try { return Long.parseLong(System.getProperty("moqui.llm.skill.file.max.bytes", "262144")); }
        catch (NumberFormatException e) { return 262144L; }
    }
    static final int MAX_SKILL_FILES = 500;

    /**
     * Every shipped skill, whatever its profiles. The order is fixed: components in their load order, and inside a
     * skill directory by file name. When two skills have the same name the first one wins and the others are left out
     * (see {@link #shippedDiagnostics}); the result never depends on the order a file system lists directories in.
     */
    public static List<SkillDoc> scanShipped(ExecutionContext ec) { return scanShipped(ec, (List<String>) null); }

    /** What the scan left out and why: duplicate names, invalid skills, truncated listings. */
    public static List<String> shippedDiagnostics(ExecutionContext ec) {
        List<String> diag = new ArrayList<>();
        scanShipped(ec, diag);
        return diag;
    }

    static List<SkillDoc> scanShipped(ExecutionContext ec, List<String> diag) {
        List<SkillDoc> out = new ArrayList<>();
        if (ec == null || ec.getFactory() == null) return out;
        ExecutionContextFactoryImpl ecfi = (ExecutionContextFactoryImpl) ec.getFactory();
        Map<String, String> comps = ecfi.getComponentBaseLocations();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Map.Entry<String, String> e : comps.entrySet()) {
            String loc = e.getValue();
            if (loc == null) continue;
            String skillDir = loc.endsWith("/") ? loc + "skill" : loc + "/skill";
            List<SkillDoc> found = new ArrayList<>();
            scanSkillDir(ec, skillDir, found, diag);
            for (SkillDoc doc : found) {
                if (seen.add(doc.name)) out.add(doc);
                else if (diag != null) diag.add("skill " + doc.name + " at " + doc.sourceLocation + " is shadowed by an earlier skill with the same name");
            }
        }
        return out;
    }

    /** Reads flat skill/*.md files and skill/&lt;name&gt;/SKILL.md folders from one skill directory. */
    static void scanSkillDir(ExecutionContext ec, String skillDir, List<SkillDoc> out) { scanSkillDir(ec, skillDir, out, null); }

    static void scanSkillDir(ExecutionContext ec, String skillDir, List<SkillDoc> out, List<String> diag) {
        try {
            ResourceReference dir = ec.getResource().getLocationReference(skillDir);
            if (dir == null || !dir.getExists() || !dir.isDirectory()) return;
            List<ResourceReference> entries = sortedEntries(dir);
            Map<String, SkillDoc> inDir = new LinkedHashMap<>();
            for (ResourceReference child : entries) {
                String name = child.getFileName();
                if (name == null || name.startsWith(".")) continue;
                SkillDoc doc = null;
                if (child.isDirectory()) {
                    ResourceReference skillFile = child.getChild("SKILL.md");
                    if (skillFile != null && skillFile.getExists() && skillFile.isFile()) {
                        doc = parseMarkdown(skillFile.getText(), skillFile.getLocation());
                        List<String> problems = validateAgentSkill(doc, name);
                        if (!problems.isEmpty()) {
                            String msg = "Skipping skill folder " + child.getLocation() + ": " + String.join("; ", problems);
                            logger.warn("Skipping skill folder {}: {}", child.getLocation(), String.join("; ", problems));
                            if (diag != null) diag.add(msg);
                            continue;
                        }
                        doc.folderLocation = child.getLocation();
                        listSkillFiles(child, doc);
                        if (doc.filesTruncated && diag != null)
                            diag.add("skill " + doc.name + ": the file list stops at " + MAX_SKILL_FILES + " files, the rest is not available");
                    }
                } else if (name.endsWith(".md")) {
                    doc = parseMarkdown(child.getText(), child.getLocation());
                }
                if (doc == null || doc.name == null || doc.name.isEmpty()) continue;
                SkillDoc earlier = inDir.get(doc.name);
                if (earlier == null) inDir.put(doc.name, doc);
                else {
                    // a folder skill wins over a flat file of the same name; otherwise the first by file name stays
                    boolean replace = earlier.folderLocation == null && doc.folderLocation != null;
                    if (diag != null) diag.add("skills named " + doc.name + " at " + earlier.sourceLocation + " and " + doc.sourceLocation
                            + " in one directory; " + (replace ? doc.sourceLocation : earlier.sourceLocation) + " is used");
                    if (replace) inDir.put(doc.name, doc);
                }
            }
            out.addAll(inDir.values());
        } catch (Throwable t) {
            if (logger.isDebugEnabled()) logger.debug("Skill scan skipped for " + skillDir + ": " + t.getMessage());
        }
    }

    /** Directory entries ordered by name, so what is listed or cut off never depends on the file system. */
    private static List<ResourceReference> sortedEntries(ResourceReference dir) {
        List<ResourceReference> entries = new ArrayList<>(dir.getDirectoryEntries());
        entries.removeIf(r -> r == null || r.getFileName() == null);
        entries.sort((x, y) -> x.getFileName().compareTo(y.getFileName()));
        return entries;
    }

    /** Shipped skills the profile may see. A null profile sees only skills without a profiles restriction. */
    public static List<SkillDoc> scanShipped(ExecutionContext ec, String profileName) {
        List<SkillDoc> out = new ArrayList<>();
        for (SkillDoc doc : scanShipped(ec)) if (visibleTo(doc, profileName)) out.add(doc);
        return out;
    }

    /** Files under a skill folder, relative paths, in a fixed order. See {@link #listSkillFiles(ResourceReference, SkillDoc)}. */
    static List<String> listSkillFiles(ResourceReference folder) {
        SkillDoc scratch = new SkillDoc();
        listSkillFiles(folder, scratch);
        return scratch.files;
    }

    /**
     * Fills doc.files with the files under the folder, in path order (each directory is walked by name), up to
     * {@link #MAX_SKILL_FILES}. It leaves out, and counts in doc.filesSkipped, agents/ (tool metadata), dot files,
     * files over the size limit, links and anything that is not a regular file; when the limit cuts the list,
     * doc.filesTruncated says so and the files after the cut are not available.
     */
    static void listSkillFiles(ResourceReference folder, SkillDoc doc) {
        doc.files = new ArrayList<>();
        doc.filesSkipped = new LinkedHashMap<>();
        doc.filesTruncated = false;
        collectSkillFiles(folder, "", doc, maxSkillFileBytes());
    }

    private static void skipped(SkillDoc doc, String why) { doc.filesSkipped.merge(why, 1, Integer::sum); }

    private static void collectSkillFiles(ResourceReference dir, String prefix, SkillDoc doc, long maxBytes) {
        for (ResourceReference child : sortedEntries(dir)) {
            if (doc.filesTruncated) return;
            String name = child.getFileName();
            if (name.startsWith(".")) { skipped(doc, "hidden"); continue; }
            java.nio.file.Path local = SkillFileAccess.localPath(child);
            if (local != null && java.nio.file.Files.isSymbolicLink(local)) { skipped(doc, "link"); continue; }
            if (child.isDirectory()) {
                if (prefix.isEmpty() && "agents".equals(name)) { skipped(doc, "agents"); continue; }
                collectSkillFiles(child, prefix + name + "/", doc, maxBytes);
            } else if (!(prefix.isEmpty() && "SKILL.md".equals(name))) {
                if (local != null && !java.nio.file.Files.isRegularFile(local, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { skipped(doc, "not a regular file"); continue; }
                if (child.getSize() > maxBytes) { skipped(doc, "over " + maxBytes + " bytes"); continue; }
                if (doc.files.size() >= MAX_SKILL_FILES) { doc.filesTruncated = true; return; }
                doc.files.add(prefix + name);
            }
        }
    }

    /**
     * What the skill is made of: SHA-256 over the SKILL.md text and, for a folder skill, every listed file by path,
     * size and content hash. A run records it when the skill is first used and refuses to go on when it changes.
     * Throws when a listed file cannot be read, because a digest that skipped it would hide a change.
     */
    public static String digest(ExecutionContext ec, SkillDoc doc) {
        if (doc == null) return null;
        StringBuilder sb = new StringBuilder(doc.contentDigest == null ? "" : doc.contentDigest).append('\n');
        if (doc.folderLocation != null && doc.files != null) {
            for (String f : doc.files) {
                SkillFileAccess.Content c = SkillFileAccess.read(ec, doc, f, maxSkillFileBytes());
                sb.append(f).append('\0').append(c.size()).append('\0').append(c.sha256).append('\n');
            }
            if (doc.filesTruncated) sb.append("truncated\n");
        }
        return SkillFileAccess.sha256(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Exact name lookup for find_skill select.
     * A shipped file or an active human/world row wins over a proposed, sim, infer, or mixed row
     * with the same name. A proposed row is selectable when nothing stronger has that name.
     * Superseded / rejected / deprecated rows are not selectable.
     */
    public static SkillDoc getByName(ExecutionContext ec, String name) { return getByName(ec, name, null); }

    /** As {@link #getByName(ExecutionContext, String)} but a shipped skill restricted by profiles needs a matching profile. */
    public static SkillDoc getByName(ExecutionContext ec, String name, String profileName) {
        if (name == null || name.isBlank()) return null;
        String n = name.trim();
        SkillDoc shipped = shippedByName(ec, n, profileName);
        SkillDoc entity = entityByName(ec, n);
        return prefer(shipped, entity);
    }

    /**
     * Search for inject and find_skill query. Active human/world rows and shipped files only,
     * plus an active row with no shipped file of that name. Proposed rows are not listed;
     * {@link #getByName} still returns one when select asks for it and the name is not reserved.
     */
    public static List<SkillDoc> retrieve(ExecutionContext ec, String query, int limit) {
        return retrieve(ec, query, limit, null);
    }

    public static List<SkillDoc> retrieve(ExecutionContext ec, String query, int limit, String profileName) {
        if (limit <= 0) limit = DEFAULT_LIMIT;
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        // names of every shipped skill stay reserved even when this profile cannot see them
        java.util.Set<String> shippedNames = new java.util.HashSet<>();
        for (SkillDoc doc : scanShipped(ec)) if (doc.name != null) shippedNames.add(doc.name);
        List<SkillDoc> shipped = scanShipped(ec, profileName);
        List<Scored> scored = new ArrayList<>();
        for (SkillDoc doc : shipped) {
            int s = score(doc, q);
            if (s > 0 || q.isEmpty()) scored.add(new Scored(s, doc, true));
        }
        if (ec != null && ec.getEntity() != null) {
            try {
                EntityList rows = withAuthzDisabled(ec, () -> activeRows(ec, q));
                int n = rows == null ? 0 : rows.size();
                for (int i = 0; i < n; i++) {
                    SkillDoc doc = fromEntity(rows.get(i));
                    if (doc.name != null && shippedNames.contains(doc.name) && !isHumanOrWorld(doc)) continue;
                    int s = score(doc, q);
                    if (s > 0 || q.isEmpty()) scored.add(new Scored(s, doc, false));
                }
            } catch (Throwable t) {
                logger.warn("LlmSkill retrieve: {} {}", t.getMessage(),
                        t.getCause() != null ? t.getCause().toString() : "");
            }
        }
        scored.sort((a, b) -> {
            int c = Integer.compare(b.score, a.score);
            if (c != 0) return c;
            if (a.shipped == b.shipped) return 0;
            return a.shipped ? 1 : -1;
        });
        List<SkillDoc> out = new ArrayList<>();
        Map<String, Integer> seenAt = new LinkedHashMap<>();
        for (Scored s : scored) {
            if (s.doc.name == null) continue;
            Integer at = seenAt.get(s.doc.name);
            if (at == null) {
                seenAt.put(s.doc.name, out.size());
                out.add(s.doc);
            } else if (!s.shipped && isHumanOrWorld(s.doc) && !isHumanOrWorld(out.get(at))) {
                out.set(at, s.doc);
            }
        }
        if (out.size() > limit) return new ArrayList<>(out.subList(0, limit));
        return out;
    }

    /** Shipped file or active human/world row already owns this name. Proposed sim rows must not take it. */
    public static boolean nameReserved(ExecutionContext ec, String name) {
        if (name == null || name.isBlank()) return false;
        String n = name.trim();
        for (SkillDoc doc : scanShipped(ec)) if (n.equals(doc.name)) return true;
        SkillDoc entity = entityByName(ec, n);
        return entity != null && "LsksActive".equals(entity.statusId) && isHumanOrWorld(entity);
    }

    static boolean isHumanOrWorld(SkillDoc doc) {
        if (doc == null) return false;
        return "LskpHuman".equals(doc.provenanceId) || "LskpWorld".equals(doc.provenanceId);
    }

    private static SkillDoc prefer(SkillDoc shipped, SkillDoc entity) {
        if (entity == null) return shipped;
        if (shipped == null) return selectable(entity) ? entity : null;
        if ("LsksActive".equals(entity.statusId) && isHumanOrWorld(entity)) return entity;
        return shipped;
    }

    private static boolean selectable(SkillDoc doc) {
        return doc != null && ("LsksActive".equals(doc.statusId) || "LsksProposed".equals(doc.statusId));
    }

    private static SkillDoc shippedByName(ExecutionContext ec, String name, String profileName) {
        for (SkillDoc doc : scanShipped(ec, profileName)) {
            if (name.equals(doc.name)) return doc;
        }
        return null;
    }

    private static SkillDoc entityByName(ExecutionContext ec, String name) {
        if (ec == null || ec.getEntity() == null) return null;
        try {
            EntityValue ev = withAuthzDisabled(ec, () -> ec.getEntity().find("moqui.llm.LlmSkill")
                    .condition("name", name).useCache(false).one());
            if (ev == null) return null;
            SkillDoc doc = fromEntity(ev);
            return selectable(doc) ? doc : null;
        } catch (Throwable t) {
            logger.warn("LlmSkill getByName: {}", t.getMessage());
            return null;
        }
    }

    /** True for concept cards such as marble-party-roles. They are not write playbooks. */
    public static boolean isReference(SkillDoc doc) {
        return doc != null && doc.name != null && doc.name.startsWith("marble-");
    }

    /** Widget lines are kept on the stored body and on the selected skill, not in catalog inject. */
    public static String withoutWidgets(String body) {
        if (body == null) return null;
        int i = widgetIndex(body);
        if (i < 0) return body;
        return body.substring(0, i).stripTrailing();
    }
    public static String widgetsSection(String body) {
        if (body == null) return null;
        int i = widgetIndex(body);
        if (i < 0) return null;
        String section = body.substring(i).trim();
        return section.isEmpty() ? null : section;
    }
    public static String activeWidgetText(ExecutionContext ec, String skillName) {
        return activeWidgetText(ec, skillName, null);
    }
    public static String activeWidgetText(ExecutionContext ec, String skillName, String profileName) {
        if (skillName == null || skillName.isBlank() || ec == null) return null;
        SkillDoc doc = getByName(ec, skillName, profileName);
        if (doc == null) return null;
        return widgetsSection(doc.body);
    }
    private static int widgetIndex(String body) {
        int i = body.indexOf("\n## Widgets");
        if (i >= 0) return i + 1;
        if (body.startsWith("## Widgets")) return 0;
        return -1;
    }

    /**
     * Procedure skills fill the inject slots. Reference cards are a short gloss beside them,
     * not one of the three procedure slots.
     */
    public static String formatInjectForQuery(ExecutionContext ec, String query) {
        return formatInjectForQuery(ec, query, null);
    }
    public static String formatInjectForQuery(ExecutionContext ec, String query, String profileName) {
        List<SkillDoc> found = retrieve(ec, query, 15, profileName);
        List<Map<String, Object>> skills = new ArrayList<>();
        List<Map<String, Object>> references = new ArrayList<>();
        if (found != null) {
            for (SkillDoc d : found) {
                if (isReference(d)) {
                    if (references.size() >= 2) continue;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", d.name);
                    m.put("title", d.title);
                    m.put("description", d.description);
                    String body = withoutWidgets(d.body);
                    if (body == null) body = "";
                    else body = body.trim();
                    if (body.length() > 400) body = body.substring(0, 400);
                    m.put("body", body);
                    references.add(m);
                } else if (skills.size() < 3) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", d.name);
                    m.put("title", d.title);
                    m.put("description", d.description);
                    m.put("risk", d.risk);
                    m.put("body", withoutWidgets(d.body));
                    m.put("lessons", lessonLines(ec, d.skillId));
                    skills.add(m);
                }
            }
        }
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("skills", skills);
        ctx.put("references", references);
        String text = LlmGateway.renderPrompt(ec, LlmGateway.PROMPT_SKILL_INJECT, ctx);
        if (text == null) return "";
        if (text.length() > INJECT_CHARS) return text.substring(0, INJECT_CHARS);
        return text;
    }

    /** Active rows for a query. A non-empty query is an entity find, not a full table load. */
    static EntityList activeRows(ExecutionContext ec, String query) {
        EntityFind find = ec.getEntity().find("moqui.llm.LlmSkill")
                .condition("statusId", "LsksActive").useCache(false);
        List<String> toks = FtsSql.tokens(query);
        if (toks.isEmpty()) return find.list();
        EntityConditionFactory cf = ec.getEntity().getConditionFactory();
        List<EntityCondition> ors = new ArrayList<>();
        for (String tok : toks) {
            String like = "%" + tok.replace("%", "").replace("_", "") + "%";
            ors.add(cf.makeCondition("name", EntityCondition.ComparisonOperator.LIKE, like));
            ors.add(cf.makeCondition("title", EntityCondition.ComparisonOperator.LIKE, like));
        }
        String docLike = "%" + String.join(" ", toks) + "%";
        ors.add(cf.makeCondition("description", EntityCondition.ComparisonOperator.LIKE, docLike));
        ors.add(cf.makeCondition("body", EntityCondition.ComparisonOperator.LIKE, docLike));
        return find.condition(cf.makeCondition(ors, EntityCondition.JoinOperator.OR)).limit(40).list();
    }

    public static String formatInject(ExecutionContext ec, List<SkillDoc> docs) {
        List<Map<String, Object>> skills = new ArrayList<>();
        if (docs != null) {
            int remaining = INJECT_CHARS;
            for (SkillDoc d : docs) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", d.name);
                m.put("title", d.title);
                m.put("description", d.description);
                m.put("risk", d.risk);
                m.put("body", withoutWidgets(d.body));
                m.put("lessons", lessonLines(ec, d.skillId));
                skills.add(m);
                int approx = (d.body != null ? d.body.length() : 0) + 80;
                remaining -= approx;
                if (remaining <= 0) break;
            }
        }
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("skills", skills);
        String text = LlmGateway.renderPrompt(ec, LlmGateway.PROMPT_SKILL_INJECT, ctx);
        if (text == null) return "";
        if (text.length() > INJECT_CHARS) return text.substring(0, INJECT_CHARS);
        return text;
    }

    static int score(SkillDoc doc, String q) {
        if (q == null || q.isEmpty()) return 1;
        int s = 0;
        String name = nz(doc.name).toLowerCase(Locale.ROOT);
        String title = nz(doc.title).toLowerCase(Locale.ROOT);
        String desc = nz(doc.description).toLowerCase(Locale.ROOT);
        String body = nz(doc.body).toLowerCase(Locale.ROOT);
        if (name.equals(q) || title.equals(q)) s += 100;
        if (name.contains(q) || title.contains(q)) s += 40;
        for (String tok : q.split("\\s+")) {
            if (tok.length() < 3) continue;
            if (name.contains(tok) || title.contains(tok)) s += 20;
            else if (desc.contains(tok)) s += 8;
            else if (body.contains(tok)) s += 2;
        }
        return s;
    }

    static SkillDoc fromEntity(EntityValue ev) {
        SkillDoc d = new SkillDoc();
        d.skillId = ev.getString("skillId");
        d.name = ev.getString("name");
        d.title = ev.getString("title");
        d.description = ev.getString("description");
        d.body = ev.getString("body");
        d.sourceLocation = ev.getString("sourceLocation");
        d.statusId = ev.getString("statusId");
        d.provenanceId = ev.getString("provenanceId");
        String riskId = ev.getString("riskId");
        if ("LskReversible".equals(riskId)) d.risk = "reversible";
        else if ("LskIrreversible".equals(riskId)) d.risk = "irreversible";
        else d.risk = "confirm";
        return d;
    }

    public static EntityValue persistProposed(ExecutionContext ec, SkillDoc doc, String rawBody) {
        if (ec == null || doc == null || doc.name == null || doc.name.isEmpty()) return null;
        if (nameReserved(ec, doc.name)) return null;
        return withAuthzDisabled(ec, () -> {
            EntityValue existing = ec.getEntity().find("moqui.llm.LlmSkill")
                    .condition("name", doc.name).useCache(false).one();
            if (existing != null) return existing;
            EntityValue ev = ec.getEntity().makeValue("moqui.llm.LlmSkill")
                    .set("name", doc.name)
                    .set("title", doc.title)
                    .set("description", doc.description)
                    .set("body", doc.body != null && !doc.body.isEmpty() ? doc.body : rawBody)
                    .set("riskId", riskId(doc.risk))
                    .set("statusId", "LsksProposed")
                    .set("provenanceId", "LskpSim")
                    .set("speaker", "sim")
                    .set("version", 1)
                    .set("worldSuccessCount", 0)
                    .set("simSuccessCount", 0);
            ev.setSequencedIdPrimary();
            return ev.create();
        });
    }

    /** Promote a sim-proposed skill after a successful world act. */
    public static EntityValue admitWorldPass(ExecutionContext ec, String skillName) {
        if (ec == null || skillName == null || skillName.isEmpty()) return null;
        return withAuthzDisabled(ec, () -> {
            EntityValue sk = ec.getEntity().find("moqui.llm.LlmSkill")
                    .condition("name", skillName).useCache(false).one();
            if (sk == null) return null;
            Object worldObj = sk.get("worldSuccessCount");
            long world = worldObj instanceof Number ? ((Number) worldObj).longValue() : 0L;
            sk.set("worldSuccessCount", world + 1L);
            if ("LsksProposed".equals(sk.getString("statusId"))) {
                sk.set("statusId", "LsksActive");
                if ("LskpSim".equals(sk.getString("provenanceId")) || "LskpInfer".equals(sk.getString("provenanceId")))
                    sk.set("provenanceId", "LskpMixed");
            }
            sk.set("lastUsedDate", ec.getUser().getNowTimestamp());
            sk.update();
            try {
                EntityValue use = ec.getEntity().makeValue("moqui.llm.LlmSkillUse")
                        .set("skillId", sk.get("skillId"))
                        .set("contact", "world")
                        .set("outcome", "pass")
                        .set("usedDate", ec.getUser().getNowTimestamp());
                use.setSequencedIdPrimary();
                use.create();
            } catch (Throwable t) {
                logger.warn("LlmSkillUse write: {}", t.getMessage());
            }
            return sk;
        });
    }

    /** Pass/fail for the active skill. A fail also stores one lesson. Does not promote. */
    public static void recordOutcome(ExecutionContext ec, String skillName, String contact, String outcome,
            String lessonBody, String conversationId) {
        if (ec == null || skillName == null || skillName.isBlank()) return;
        if (outcome == null || outcome.isBlank()) return;
        withAuthzDisabled(ec, () -> {
            EntityValue sk = ec.getEntity().find("moqui.llm.LlmSkill")
                    .condition("name", skillName).useCache(false).one();
            if (sk == null) return null;
            boolean pass = "pass".equals(outcome);
            if (pass) {
                String countField = "sim".equals(contact) ? "simSuccessCount" : "worldSuccessCount";
                Object cur = sk.get(countField);
                long n = cur instanceof Number ? ((Number) cur).longValue() : 0L;
                sk.set(countField, n + 1L);
            }
            sk.set("lastUsedDate", ec.getUser().getNowTimestamp());
            sk.update();
            try {
                EntityValue use = ec.getEntity().makeValue("moqui.llm.LlmSkillUse")
                        .set("skillId", sk.get("skillId"))
                        .set("conversationId", conversationId)
                        .set("contact", contact)
                        .set("outcome", outcome)
                        .set("notes", cap(lessonBody, 500))
                        .set("usedDate", ec.getUser().getNowTimestamp());
                use.setSequencedIdPrimary();
                use.create();
            } catch (Throwable t) {
                logger.warn("LlmSkillUse write: {}", t.getMessage());
            }
            if (!pass && lessonBody != null && !lessonBody.isBlank()) {
                String title = lessonTitle(lessonBody);
                EntityValue existing = ec.getEntity().find("moqui.llm.LlmLesson")
                        .condition("skillId", sk.get("skillId")).condition("title", title).useCache(false).one();
                if (existing == null) {
                    EntityValue lesson = ec.getEntity().makeValue("moqui.llm.LlmLesson")
                            .set("skillId", sk.get("skillId"))
                            .set("title", title)
                            .set("body", cap(lessonBody, 800))
                            .set("provenanceId", "sim".equals(contact) ? "LskpSim" : "LskpWorld")
                            .set("statusId", "LsksActive")
                            .set("createdDate", ec.getUser().getNowTimestamp());
                    lesson.setSequencedIdPrimary();
                    lesson.create();
                }
            }
            return sk;
        });
    }

    public static void recordOutcomeInTx(ExecutionContext ec, String skillName, String contact, String outcome,
            String lessonBody, String conversationId) {
        if (ec == null || ec.getTransaction() == null) {
            recordOutcome(ec, skillName, contact, outcome, lessonBody, conversationId);
            return;
        }
        boolean began = false;
        try {
            began = ec.getTransaction().begin(60);
            recordOutcome(ec, skillName, contact, outcome, lessonBody, conversationId);
            ec.getTransaction().commit(began);
        } catch (Throwable t) {
            try { ec.getTransaction().rollback(began, "record LlmSkill outcome", t); }
            catch (Throwable ignored) { }
            logger.warn("recordOutcome: {}", t.getMessage());
        }
    }

    static List<String> lessonLines(ExecutionContext ec, String skillId) {
        if (ec == null || skillId == null || skillId.isBlank()) return Collections.emptyList();
        try {
            return withAuthzDisabled(ec, () -> {
                List<String> lines = new ArrayList<>();
                EntityList list = ec.getEntity().find("moqui.llm.LlmLesson")
                        .condition("skillId", skillId).condition("statusId", "LsksActive")
                        .orderBy("-createdDate").limit(3).list();
                int size = list.size();
                for (int i = 0; i < size; i++) {
                    String body = list.get(i).getString("body");
                    if (body != null && !body.isBlank()) lines.add(cap(body.trim(), 400));
                }
                return lines;
            });
        } catch (Throwable t) {
            logger.warn("LlmLesson read: {}", t.getMessage());
            return Collections.emptyList();
        }
    }

    static String lessonTitle(String body) {
        String line = body == null ? "" : body.trim();
        int nl = line.indexOf('\n');
        if (nl >= 0) line = line.substring(0, nl).trim();
        if (line.length() > 120) line = line.substring(0, 120).trim();
        return line.isEmpty() ? "failed" : line;
    }

    static String cap(String text, int max) {
        if (text == null) return null;
        String t = text.trim();
        if (t.length() <= max) return t;
        return t.substring(0, max);
    }

    /** Same as {@link #admitWorldPass} but begins a short TX when the caller is not in one. */
    public static EntityValue admitWorldPassInTx(ExecutionContext ec, String skillName) {
        if (ec == null || ec.getTransaction() == null) return admitWorldPass(ec, skillName);
        boolean began = false;
        try {
            began = ec.getTransaction().begin(60);
            EntityValue ev = admitWorldPass(ec, skillName);
            ec.getTransaction().commit(began);
            return ev;
        } catch (Throwable t) {
            try { ec.getTransaction().rollback(began, "admit LlmSkill world pass", t); }
            catch (Throwable ignored) { }
            logger.warn("admitWorldPass: {}", t.getMessage());
            return null;
        }
    }

    /**
     * LlmServlet is not a screen, so inheritAuthz from ASSIST_APP does not cover these rows.
     * Same pattern as {@code LlmConversationImpl} persist.
     */
    static <T> T withAuthzDisabled(ExecutionContext ec, Supplier<T> work) {
        if (work == null) return null;
        ArtifactExecutionFacade aefi = ec != null ? ec.getArtifactExecution() : null;
        boolean alreadyDisabled = aefi != null && aefi.disableAuthz();
        try {
            return work.get();
        } finally {
            if (aefi != null && !alreadyDisabled) aefi.enableAuthz();
        }
    }

    static String riskId(String risk) {
        if ("reversible".equalsIgnoreCase(risk)) return "LskReversible";
        if ("irreversible".equalsIgnoreCase(risk)) return "LskIrreversible";
        return "LskConfirm";
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static final class Scored {
        final int score;
        final SkillDoc doc;
        final boolean shipped;
        Scored(int score, SkillDoc doc, boolean shipped) {
            this.score = score;
            this.doc = doc;
            this.shipped = shipped;
        }
    }
}
