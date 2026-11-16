package com.devmind.ingestion;

import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One regular file of a commit, with its content exactly as committed: not decoded, normalized or redacted yet (that's
 * ingestion's filter step).
 *
 * <p>A record with an array component, so equality, hash code and string form are defined here by content, and the
 * array is copied in and out to keep the record immutable (JAVA-3).
 */
@SuppressWarnings("ArrayRecordComponent") // handled: copied in and out, and equals/hashCode/toString are overridden
public record SourceFile(String path, byte[] content) {

    /** The longest path kept, matching the {@code revision_file.path} limit (V3 migration). */
    public static final int MAX_PATH_LENGTH = 1000;

    /**
     * @param path a path for which {@link #isValidPath} holds
     * @param content the committed bytes; copied
     * @throws IllegalArgumentException if {@code path} isn't valid
     */
    public SourceFile {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(content, "content");
        if (!isValidPath(path)) {
            throw new IllegalArgumentException("path must be relative, normalized and 1-" + MAX_PATH_LENGTH + " chars");
        }
        content = content.clone();
    }

    /**
     * Returns whether {@code path} is a repository-relative, {@code /}-separated path (JAVA-10) of 1 to {@link
     * #MAX_PATH_LENGTH} characters with no empty, {@code .} or {@code ..} segment. Git never writes other paths, but a
     * crafted repository can contain them.
     */
    public static boolean isValidPath(String path) {
        if (path.isEmpty() || path.length() > MAX_PATH_LENGTH) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /** Returns a copy of the committed bytes. */
    @Override
    public byte[] content() {
        return content.clone();
    }

    @Override
    public boolean equals(@Nullable Object other) {
        // Fields, not accessors: content() would copy the array.
        return other instanceof SourceFile that && path.equals(that.path) && Arrays.equals(content, that.content);
    }

    @Override
    public int hashCode() {
        return 31 * path.hashCode() + Arrays.hashCode(content);
    }

    /** Names the path and size only, so logging a file never logs its content (OBS-3). */
    @Override
    public String toString() {
        return "SourceFile[path=" + path + ", bytes=" + content.length + "]";
    }
}
