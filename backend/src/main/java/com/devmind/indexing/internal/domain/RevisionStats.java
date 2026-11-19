package com.devmind.indexing.internal.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@code repository_revision.stats}: what an index run stored (M2 step 7). Written by the indexer and read back by the
 * revision API, so both use this one shape.
 *
 * @param filesKept files indexed
 * @param excluded entries not indexed, by reason key ({@code path}, {@code secretFile}, ...)
 * @param blobsReused distinct blobs the last attempt found already stored
 * @param blobsNew blobs the last attempt stored
 * @param symbols symbols across the revision's files
 * @param chunks chunks across the revision's files
 * @param parseFailures files indexed as text because parsing failed
 * @param redactions secrets replaced across the revision's files
 * @param encodingFallbacks files read as ISO-8859-1 because they weren't valid UTF-8
 * @param durationMs the last attempt's duration
 */
public record RevisionStats(
        long filesKept,
        Map<String, Integer> excluded,
        int blobsReused,
        int blobsNew,
        long symbols,
        long chunks,
        long parseFailures,
        long redactions,
        int encodingFallbacks,
        long durationMs) {

    public RevisionStats {
        Objects.requireNonNull(excluded, "excluded");
        excluded = Collections.unmodifiableMap(new LinkedHashMap<>(excluded)); // keeps the reasons in order
    }
}
