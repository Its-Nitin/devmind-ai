package com.devmind.indexing;

/**
 * Extracts declarations from one file's source (ADR-0004). Implementations are stateless and thread-safe. Java is
 * parsed in M2; the port leaves room for other languages (M8).
 */
public interface CodeParser {

    /** Returns whether this parser handles {@code language}, a language ID such as {@code java}. */
    boolean supports(String language);

    /**
     * Parses {@code content}. Never throws for bad input: a file that can't be parsed comes back as {@link
     * ParseStatus#PARSE_FAILED}, so it can still be chunked as text (ERR-4).
     *
     * @param path repository-relative, {@code /}-separated; used to tell test code from main code
     * @param content normalized text (LF line endings), as produced by ingestion
     */
    ParsedFile parse(String path, String content);

    /**
     * Returns the parser version, e.g. {@code java-1}. Part of a blob's identity ({@code file_blob.parser_version}):
     * bump it whenever the output for the same input changes.
     */
    String version();
}
