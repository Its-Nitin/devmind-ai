package com.devmind.indexing.internal.persistence;

import com.devmind.indexing.ParsedSymbol;
import com.devmind.indexing.internal.domain.Chunk;
import org.jspecify.annotations.Nullable;

/** Rows read and written by {@link IndexingQueries}. */
public final class IndexingRows {

    private IndexingRows() {}

    /** A {@code repository_revision} row's identity and state. */
    public record Revision(long id, RevisionStatus status) {}

    /** Matches the {@code repository_revision.status} CHECK constraint. */
    public enum RevisionStatus {
        INDEXING,
        READY,
        FAILED
    }

    /**
     * An existing blob.
     *
     * @param staleChunks whether some of its chunks were cut by another chunker version and must be rebuilt
     */
    public record ExistingBlob(long id, String contentHash, String parserVersion, boolean staleChunks) {}

    /** A blob to insert, with its pre-allocated ID. */
    public record NewBlob(
            long id,
            String contentHash,
            String parserVersion,
            String language,
            int sizeBytes,
            int lineCount,
            String content,
            String parseStatus,
            int redactionCount) {}

    /** A symbol to insert, with its pre-allocated ID and its parent's. */
    public record NewSymbol(long id, long blobId, @Nullable Long parentId, ParsedSymbol symbol) {}

    /** A chunk to insert. */
    public record NewChunk(long blobId, @Nullable Long symbolId, Chunk chunk) {}

    /** One path of a revision and the blob it holds. */
    public record RevisionFile(String path, long blobId) {}

    /**
     * What a revision holds, counted from the database, so the numbers are right however many attempts it took.
     *
     * @param unchunkedFiles non-empty files without a single chunk; any is an indexing bug
     */
    public record RevisionTotals(
            long files, long parseFailures, long redactions, long unchunkedFiles, long symbols, long chunks) {}
}
