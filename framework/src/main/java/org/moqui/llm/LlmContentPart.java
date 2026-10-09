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
package org.moqui.llm;

import org.moqui.resource.ResourceReference;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LlmContentPart {
    public static final int OPEN_RESPONSES_MAX_FILE_BYTES = 33_554_432;
    public String type;
    public String text;
    public String refusal;
    public String imageUrl;
    public String fileData;
    public String fileUrl;
    public String videoUrl;
    public String filename;
    public String detail;
    public String contentLocation;
    public String mediaType;
    public Long contentLength;
    public String contentSha256;
    public List<LlmContentAnnotation> annotations;
    public List<LlmContentLogprob> logprobs;
    public Map<String, Object> payload;

    public static LlmContentPart inputText(String text) { return textPart("input_text", text); }
    public static LlmContentPart outputText(String text) { return textPart("output_text", text); }
    public static LlmContentPart summaryText(String text) { return textPart("summary_text", text); }
    public static LlmContentPart reasoningText(String text) { return textPart("reasoning_text", text); }

    public static LlmContentPart inputFileReference(ResourceReference ref) {
        LlmContentPart part = inputFile(ref, false, 0);
        if (part.fileUrl == null)
            throw new IllegalArgumentException("Resource is not reachable by the model provider; use inputFileData() "
                    + "or publish an authorized HTTP(S) content URL: " + ref.getLocation());
        return part;
    }

    public static LlmContentPart inputFileData(ResourceReference ref, int maxBytes) {
        return inputFile(ref, true, maxBytes);
    }

    public static LlmContentPart inputFile(ResourceReference ref, boolean inlineData, int maxBytes) {
        if (ref == null) throw new IllegalArgumentException("ResourceReference is required");
        LlmContentPart part = new LlmContentPart();
        part.type = "input_file";
        part.filename = ref.getFileName();
        part.mediaType = ref.getContentType();
        part.contentLocation = ref.getLocation();
        if (ref.supportsSize()) {
            long size = ref.getSize();
            if (size >= 0) part.contentLength = size;
        }
        if (inlineData) {
            int effectiveMax = maxBytes > 0 ? Math.min(maxBytes, OPEN_RESPONSES_MAX_FILE_BYTES)
                    : OPEN_RESPONSES_MAX_FILE_BYTES;
            byte[] bytes = readResourceBytes(ref, effectiveMax);
            part.fileData = Base64.getEncoder().encodeToString(bytes);
            part.contentLength = (long) bytes.length;
            part.contentSha256 = sha256Hex(bytes);
        } else if (ref.supportsUrl() && ref.getUrl() != null) {
            String protocol = ref.getUrl().getProtocol();
            if ("http".equalsIgnoreCase(protocol) || "https".equalsIgnoreCase(protocol))
                part.fileUrl = ref.getUrl().toExternalForm();
        }
        return part;
    }

    private static LlmContentPart textPart(String type, String text) {
        LlmContentPart part = new LlmContentPart();
        part.type = type;
        part.text = text;
        return part;
    }

    public LlmContentPart copy() {
        LlmContentPart copy = new LlmContentPart();
        copy.type = type;
        copy.text = text;
        copy.refusal = refusal;
        copy.imageUrl = imageUrl;
        copy.fileData = fileData;
        copy.fileUrl = fileUrl;
        copy.videoUrl = videoUrl;
        copy.filename = filename;
        copy.detail = detail;
        copy.contentLocation = contentLocation;
        copy.mediaType = mediaType;
        copy.contentLength = contentLength;
        copy.contentSha256 = contentSha256;
        if (annotations != null) {
            copy.annotations = new ArrayList<>();
            for (LlmContentAnnotation value : annotations) copy.annotations.add(value != null ? value.copy() : null);
        }
        if (logprobs != null) {
            copy.logprobs = new ArrayList<>();
            for (LlmContentLogprob value : logprobs) copy.logprobs.add(value != null ? value.copy() : null);
        }
        if (payload != null) copy.payload = new LinkedHashMap<>(payload);
        return copy;
    }

    private static byte[] readResourceBytes(ResourceReference ref, int maxBytes) {
        try (InputStream stream = ref.openStream()) {
            byte[] bytes = stream.readAllBytes();
            if (maxBytes > 0 && bytes.length > maxBytes)
                throw new IllegalArgumentException("Resource is too large for inline Responses input_file: "
                        + bytes.length + " > " + maxBytes + " bytes");
            return bytes;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to read resource for Responses input_file: " + ref.getLocation(), e);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 digest not available", e);
        }
    }
}
