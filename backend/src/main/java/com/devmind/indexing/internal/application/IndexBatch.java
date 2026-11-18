package com.devmind.indexing.internal.application;

import com.devmind.indexing.ParseStatus;
import com.devmind.indexing.ParsedSymbol;
import com.devmind.indexing.internal.domain.Chunk;
import com.devmind.indexing.internal.persistence.IndexingRows.ExistingBlob;
import com.devmind.ingestion.NormalizedFile;
import java.util.List;
import java.util.Map;

/**
 * One write batch, prepared outside any transaction: parsing and chunking are CPU work that shouldn't hold one open
 * (SPRING-2).
 *
 * @param files every kept path of the batch and the blob it holds
 * @param blobs blobs whose rows, symbols and chunks must be written: new ones, and existing ones with stale chunks
 * @param existing blobs already stored, by key (including the stale ones, which keep their IDs)
 */
record IndexBatch(List<Path> files, List<Blob> blobs, Map<BlobKey, ExistingBlob> existing) {

    IndexBatch {
        files = List.copyOf(files);
        blobs = List.copyOf(blobs);
        existing = Map.copyOf(existing);
    }

    /** A blob's identity: its content, under one parser version ({@code file_blob_uk}). */
    record BlobKey(String contentHash, String parserVersion) {}

    /** A path of the revision and its blob. */
    record Path(String path, BlobKey blob) {}

    /** A blob's content and what the parser and chunker made of it. */
    record Blob(BlobKey key, NormalizedFile file, ParseStatus status, List<ParsedSymbol> symbols, List<Chunk> chunks) {

        Blob {
            symbols = List.copyOf(symbols);
            chunks = List.copyOf(chunks);
        }
    }

    /**
     * What a write did.
     *
     * @param blobsNew blobs this batch inserted
     * @param blobsReused distinct blobs of the batch that already existed
     */
    record Result(int blobsNew, int blobsReused) {}
}
