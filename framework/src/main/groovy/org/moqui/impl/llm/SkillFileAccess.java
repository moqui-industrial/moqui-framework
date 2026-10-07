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

import org.moqui.context.ExecutionContext;
import org.moqui.resource.ResourceReference;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;

/**
 * Reads the files of a folder skill without ever leaving the skill folder. A file must be one of the files the skill
 * lists. On a file system it is opened without following a link, must be a regular file, and its real path must be
 * inside the real path of the skill folder both before it is opened and after it was read, so a link added, or a folder
 * swapped for a link, between the listing and the read is refused. The size is bounded while reading, never after
 * reading the whole file. A location that is not on a file system (a jar, for example) has no links: the same bounds and
 * the listed-file rule apply, and nothing else is claimed.
 */
public final class SkillFileAccess {
    private SkillFileAccess() { }

    /** What was read: the bytes, their SHA-256 and whether they are text (valid UTF-8 without a NUL). */
    public static final class Content {
        public final byte[] bytes;
        public final String sha256;
        public final boolean text;
        Content(byte[] bytes, String sha256, boolean text) { this.bytes = bytes; this.sha256 = sha256; this.text = text; }
        public long size() { return bytes.length; }
        /** The text; only for a file that {@link #text} says is text. */
        public String asText() { return new String(bytes, StandardCharsets.UTF_8); }
    }

    public static final class SkillFileException extends IllegalArgumentException {
        public SkillFileException(String message) { super(message); }
    }

    /** The file system path of a location, or null when it is not on the file system. */
    public static Path localPath(ResourceReference ref) {
        try {
            URI uri = ref.getUri();
            if (uri != null && "file".equalsIgnoreCase(uri.getScheme())) return Paths.get(uri);
        } catch (Exception ignored) { }
        return null;
    }

    /** Reads one listed file of the skill, at most maxBytes. */
    public static Content read(ExecutionContext ec, SkillIndex.SkillDoc doc, String relative, long maxBytes) {
        if (doc == null || doc.folderLocation == null || doc.files == null) throw new SkillFileException("not a folder skill");
        if (!doc.files.contains(relative)) throw new SkillFileException(relative + " is not a file of skill " + doc.name);
        ResourceReference folder = ec.getResource().getLocationReference(doc.folderLocation);
        ResourceReference file = ec.getResource().getLocationReference(doc.folderLocation + "/" + relative);
        return read(folder, file, relative, maxBytes);
    }

    static Content read(ResourceReference folder, ResourceReference file, String relative, long maxBytes) {
        Path folderPath = localPath(folder), filePath = localPath(file);
        byte[] bytes;
        try {
            if (folderPath != null && filePath != null) {
                Path realFolder = folderPath.toRealPath();
                if (!filePath.toAbsolutePath().normalize().startsWith(folderPath.toAbsolutePath().normalize()))
                    throw new SkillFileException(relative + " is outside the skill folder");
                Path before = filePath.toRealPath(LinkOption.NOFOLLOW_LINKS);
                if (!before.startsWith(realFolder)) throw new SkillFileException(relative + " is outside the skill folder");
                BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attrs.isRegularFile()) throw new SkillFileException(relative + " is not a regular file");
                if (attrs.size() > maxBytes) throw new SkillFileException(relative + " is larger than " + maxBytes + " bytes");
                try (SeekableByteChannel ch = Files.newByteChannel(filePath, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    bytes = readBounded(java.nio.channels.Channels.newInputStream(ch), maxBytes, relative);
                }
                // a folder of the path swapped for a link while the file was read
                if (!filePath.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(realFolder))
                    throw new SkillFileException(relative + " left the skill folder while it was read");
            } else {
                if (!file.getExists() || !file.isFile()) throw new SkillFileException(relative + " does not exist");
                try (InputStream in = file.openStream()) { bytes = readBounded(in, maxBytes, relative); }
            }
        } catch (java.nio.file.NoSuchFileException | java.nio.file.FileSystemLoopException e) {
            throw new SkillFileException(relative + " does not exist or is a link");
        } catch (IOException e) {
            throw new SkillFileException(relative + " could not be read: " + e.getMessage());
        }
        return new Content(bytes, sha256(bytes), isText(bytes));
    }

    static byte[] readBounded(InputStream in, long max, String what) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(max, 8192));
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) throw new SkillFileException(what + " is larger than " + max + " bytes");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** Valid UTF-8 without a NUL byte. */
    public static boolean isText(byte[] bytes) {
        for (byte b : bytes) if (b == 0) return false;
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) { return false; }
    }

    public static String sha256(byte[] bytes) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static final java.util.Map<String, String> MIME = new java.util.HashMap<>();
    static {
        String[][] m = {{"md", "text/markdown"}, {"xml", "application/xml"}, {"xsd", "application/xml"}, {"json", "application/json"},
                {"yaml", "application/yaml"}, {"yml", "application/yaml"}, {"csv", "text/csv"}, {"txt", "text/plain"},
                {"png", "image/png"}, {"jpg", "image/jpeg"}, {"jpeg", "image/jpeg"}, {"gif", "image/gif"}, {"pdf", "application/pdf"},
                {"zip", "application/zip"}, {"html", "text/html"}, {"ftl", "text/plain"}, {"groovy", "text/plain"}, {"java", "text/plain"}};
        for (String[] e : m) MIME.put(e[0], e[1]);
    }
    public static String mimeType(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return MIME.getOrDefault(ext, "application/octet-stream");
    }

    /** Extensions read as text even when they are scripts: never executed, only read. */
    public static final Set<String> TEXT_EXTENSIONS = new java.util.LinkedHashSet<>(java.util.Arrays.asList(
            ".md", ".xml", ".xsd", ".txt", ".json", ".csv", ".ftl", ".groovy", ".yaml", ".yml", ".java", ".sql", ".html", ".js",
            ".py", ".sh", ".bat", ".ps1"));
    public static boolean textExtension(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        for (String e : TEXT_EXTENSIONS) if (n.endsWith(e)) return true;
        return false;
    }
}
