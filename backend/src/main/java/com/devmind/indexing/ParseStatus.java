package com.devmind.indexing;

/** Whether a blob's declarations were extracted. Matches the {@code file_blob.parse_status} CHECK constraint. */
public enum ParseStatus {
    /** Parsed; its symbols are the declarations. */
    OK,
    /** The parser rejected the source; the file is chunked as plain text (ERR-4). */
    PARSE_FAILED,
    /** No parser supports the language; set by the indexer, never returned by a {@link CodeParser}. */
    SKIPPED
}
