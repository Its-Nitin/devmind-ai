package com.devmind.ingestion;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A file ready to index: decoded, normalized (no BOM, LF line endings) and redacted, so {@link #content()} is exactly
 * what may be stored and shown (SEC-5). Line numbers match the committed file, because redaction never adds or removes
 * a line.
 *
 * @param path repository-relative, {@code /}-separated ({@link SourceFile#isValidPath})
 * @param language lowercase language ID from the extension, e.g. {@code java}, {@code yaml}, {@code markdown}
 * @param content normalized, redacted text
 * @param contentHash SHA-256 of {@code content} as UTF-8, 64 lowercase hex characters; identifies the blob (ADR-0003)
 * @param sizeBytes length of {@code content} as UTF-8
 * @param lineCount lines of {@code content}; a final line without a newline counts, an empty file has 0
 * @param redactionCount secrets replaced in {@code content}
 * @param encodingFallback whether the bytes weren't valid UTF-8 and were decoded as ISO-8859-1
 */
public record NormalizedFile(
        String path,
        String language,
        String content,
        String contentHash,
        int sizeBytes,
        int lineCount,
        int redactionCount,
        boolean encodingFallback) {

    private static final Pattern SHA_256_HEX = Pattern.compile("[0-9a-f]{64}");

    /** @throws IllegalArgumentException if the path or hash is malformed or a count is negative */
    public NormalizedFile {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(contentHash, "contentHash");
        if (!SourceFile.isValidPath(path)) {
            throw new IllegalArgumentException("invalid path");
        }
        if (!SHA_256_HEX.matcher(contentHash).matches()) {
            throw new IllegalArgumentException("contentHash must be 64 lowercase hex characters");
        }
        if (sizeBytes < 0 || lineCount < 0 || redactionCount < 0) {
            throw new IllegalArgumentException("counts must not be negative");
        }
    }

    /** Leaves out the content, so logging a file never logs code or what survived redaction (OBS-3). */
    @Override
    public String toString() {
        return "NormalizedFile[path=" + path + ", language=" + language + ", hash=" + contentHash + ", lines="
                + lineCount + ", redactions=" + redactionCount + "]";
    }
}
