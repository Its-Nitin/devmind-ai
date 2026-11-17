package com.devmind.indexing.internal.domain;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One retrieval unit of a file ({@code code_chunk}, architecture §7.1).
 *
 * @param startLine first line of the source range, 1-based
 * @param endLine last line, inclusive
 * @param symbolIndex index into the file's parsed symbols of the symbol this chunk belongs to; null for text windows
 * @param header context prepended for embedding and shown to the model, e.g. {@code path · package · class · member}
 * @param content the chunk's text: the source lines, except a type header chunk, whose member bodies are elided
 * @param searchText what full-text search indexes: header, content and the split identifiers of the content
 * @param tokenEstimate {@code ceil(content characters / 4)}
 */
public record Chunk(
        int startLine,
        int endLine,
        @Nullable Integer symbolIndex,
        String header,
        String content,
        String searchText,
        int tokenEstimate) {

    /** @throws IllegalArgumentException if the line range is invalid or the token estimate is negative */
    public Chunk {
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(searchText, "searchText");
        if (startLine < 1 || endLine < startLine || tokenEstimate < 0) {
            throw new IllegalArgumentException("invalid chunk " + startLine + "-" + endLine);
        }
    }

    /** Leaves out the text, which is source code (OBS-3). */
    @Override
    public String toString() {
        return "Chunk[lines=" + startLine + "-" + endLine + ", symbol=" + symbolIndex + ", tokens=" + tokenEstimate
                + "]";
    }
}
