package com.devmind.indexing.internal.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

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
 * @param embeddings the embedding phase (M3); null for revisions indexed before it existed
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
        long durationMs,
        @Nullable EmbeddingStats embeddings) {

    public RevisionStats {
        Objects.requireNonNull(excluded, "excluded");
        excluded = Collections.unmodifiableMap(new LinkedHashMap<>(excluded)); // keeps the reasons in order
    }

    /**
     * The embedding phase of a run.
     *
     * @param model the model's {@code provider/name@digest}
     * @param embeddingsNew chunks the last attempt embedded
     * @param embeddingsReused distinct chunks of the revision already embedded by the model before the last attempt
     * @param embeddingMs the last attempt's embedding phase, model calls and writes
     */
    public record EmbeddingStats(String model, long embeddingsNew, long embeddingsReused, long embeddingMs) {}
}
