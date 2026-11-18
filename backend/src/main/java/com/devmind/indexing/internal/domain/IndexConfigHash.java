package com.devmind.indexing.internal.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Identifies everything that shapes an index besides the commit: parser, chunker and filter versions and the
 * embedding model (architecture §5). Part of a revision's key, so indexing the same commit under new rules makes a new
 * revision instead of mixing old and new rows.
 */
public final class IndexConfigHash {

    /** No embeddings until M3. */
    public static final String NO_EMBEDDINGS = "none";

    private IndexConfigHash() {}

    /** Returns the SHA-256, as 64 lowercase hex characters, of the versions joined in a fixed order. */
    public static String of(
            List<String> parserVersions, String chunkerVersion, String filterRulesVersion, String embeddingModel) {
        String parsers = parserVersions.stream().sorted().collect(Collectors.joining(","));
        String joined = Stream.of(parsers, chunkerVersion, filterRulesVersion, embeddingModel)
                .collect(Collectors.joining("|"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("every Java platform must support SHA-256", ex);
        }
    }
}
