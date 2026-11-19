package com.devmind.indexing.internal.domain;

import java.util.List;

/**
 * Line access on stored content, counted the way the chunker and the database's {@code line_count} count: 1-based,
 * and a final newline doesn't start another line.
 */
public final class SourceLines {

    private SourceLines() {}

    /** Returns the lines of {@code content}; empty for empty content. */
    public static List<String> of(String content) {
        if (content.isEmpty()) {
            return List.of();
        }
        List<String> lines = List.of(content.split("\n", -1));
        return content.endsWith("\n") ? lines.subList(0, lines.size() - 1) : lines;
    }

    /**
     * Returns lines {@code from} to {@code to}, inclusive, joined by {@code \n}.
     *
     * @throws IndexOutOfBoundsException unless {@code 1 <= from <= to + 1} and {@code to <= line count}
     */
    public static String slice(String content, int from, int to) {
        return String.join("\n", of(content).subList(from - 1, to));
    }
}
