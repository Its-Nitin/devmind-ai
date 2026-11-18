package com.devmind.indexing.internal.application;

import com.devmind.indexing.ParsedSymbol;
import com.devmind.indexing.RevisionReadyEvent;
import com.devmind.indexing.internal.application.IndexBatch.Blob;
import com.devmind.indexing.internal.application.IndexBatch.BlobKey;
import com.devmind.indexing.internal.domain.Chunk;
import com.devmind.indexing.internal.domain.SymbolChunker;
import com.devmind.indexing.internal.persistence.IndexingQueries;
import com.devmind.indexing.internal.persistence.IndexingRows.ExistingBlob;
import com.devmind.indexing.internal.persistence.IndexingRows.NewBlob;
import com.devmind.indexing.internal.persistence.IndexingRows.NewChunk;
import com.devmind.indexing.internal.persistence.IndexingRows.NewSymbol;
import com.devmind.indexing.internal.persistence.IndexingRows.Revision;
import com.devmind.indexing.internal.persistence.IndexingRows.RevisionFile;
import com.devmind.indexing.internal.persistence.IndexingRows.RevisionTotals;
import com.devmind.ingestion.NormalizedFile;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The index's transactions, each short (SPRING-2): starting a revision, one per batch of files, and finishing. A batch
 * writes a blob together with its symbols and chunks, so a stored blob is always complete, and a retried run can
 * treat every existing blob as done.
 */
@Service
class IndexWriter {

    private final IndexingQueries queries;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    IndexWriter(IndexingQueries queries, ApplicationEventPublisher events, Clock clock) {
        this.queries = queries;
        this.events = events;
        this.clock = clock;
    }

    /** Creates or restarts the revision; a READY one comes back untouched. */
    @Transactional
    Revision startRevision(long projectId, String commitSha, String configHash) {
        return queries.startRevision(projectId, commitSha, configHash, clock.instant());
    }

    /** Returns the stored blobs among {@code keys}. */
    @Transactional(readOnly = true)
    Map<BlobKey, ExistingBlob> existing(long projectId, Collection<BlobKey> keys) {
        return existingByKey(projectId, keys);
    }

    /** Writes one batch in one transaction (DB-8). */
    @Transactional
    IndexBatch.Result write(long projectId, long revisionId, IndexBatch batch) {
        Map<BlobKey, Long> blobIds = new HashMap<>();
        batch.existing().forEach((key, blob) -> blobIds.put(key, blob.id()));

        List<Blob> fresh = batch.blobs().stream()
                .filter(blob -> !batch.existing().containsKey(blob.key()))
                .toList();
        List<Blob> rebuilt = batch.blobs().stream()
                .filter(blob -> batch.existing().containsKey(blob.key()))
                .toList();
        List<Blob> derived = new ArrayList<>(rebuilt);
        int inserted = insertFresh(projectId, fresh, blobIds, derived);

        queries.deleteDerived(
                rebuilt.stream().map(blob -> blobIds.get(blob.key())).toList());
        writeDerived(derived, blobIds);
        queries.upsertRevisionFiles(
                revisionId,
                batch.files().stream()
                        .map(file -> new RevisionFile(
                                file.path(), Objects.requireNonNull(blobIds.get(file.blob()), "blob id")))
                        .toList());

        int distinct = (int)
                batch.files().stream().map(IndexBatch.Path::blob).distinct().count();
        return new IndexBatch.Result(inserted, distinct - inserted);
    }

    /**
     * Inserts the new blobs with pre-allocated IDs. Blobs inserted meanwhile by a concurrent run are looked up instead;
     * that run writes their symbols and chunks.
     *
     * @return how many were inserted here; their symbols and chunks are added to {@code derived}
     */
    private int insertFresh(long projectId, List<Blob> fresh, Map<BlobKey, Long> blobIds, List<Blob> derived) {
        List<Long> ids = queries.allocateBlobIds(fresh.size());
        List<NewBlob> rows = new ArrayList<>();
        for (int i = 0; i < fresh.size(); i++) {
            rows.add(row(ids.get(i), fresh.get(i)));
        }
        boolean[] insertedRows = rows.isEmpty() ? new boolean[0] : queries.insertBlobs(projectId, rows);
        int inserted = 0;
        List<BlobKey> lostRaces = new ArrayList<>();
        for (int i = 0; i < fresh.size(); i++) {
            if (insertedRows[i]) {
                blobIds.put(fresh.get(i).key(), ids.get(i));
                derived.add(fresh.get(i));
                inserted++;
            } else {
                lostRaces.add(fresh.get(i).key());
            }
        }
        existingByKey(projectId, lostRaces).forEach((key, blob) -> blobIds.put(key, blob.id()));
        return inserted;
    }

    /** Symbols with pre-allocated IDs, so parents and chunks can refer to them in the same batch. */
    private void writeDerived(List<Blob> blobs, Map<BlobKey, Long> blobIds) {
        int symbolCount = blobs.stream().mapToInt(blob -> blob.symbols().size()).sum();
        List<Long> symbolIds = queries.allocateSymbolIds(symbolCount);
        List<NewSymbol> symbols = new ArrayList<>(symbolCount);
        List<NewChunk> chunks = new ArrayList<>();
        int offset = 0;
        for (Blob blob : blobs) {
            long blobId = Objects.requireNonNull(blobIds.get(blob.key()), "blob id");
            List<ParsedSymbol> parsed = blob.symbols();
            for (int i = 0; i < parsed.size(); i++) {
                Integer parent = parsed.get(i).parentIndex();
                symbols.add(new NewSymbol(
                        symbolIds.get(offset + i),
                        blobId,
                        parent == null ? null : symbolIds.get(offset + parent),
                        parsed.get(i)));
            }
            for (Chunk chunk : blob.chunks()) {
                Integer symbol = chunk.symbolIndex();
                chunks.add(new NewChunk(blobId, symbol == null ? null : symbolIds.get(offset + symbol), chunk));
            }
            offset += parsed.size();
        }
        queries.insertSymbols(symbols);
        queries.insertChunks(chunks, SymbolChunker.VERSION);
    }

    @Transactional(readOnly = true)
    RevisionTotals totals(long revisionId) {
        return queries.totals(revisionId);
    }

    /**
     * Marks the revision READY and publishes {@link RevisionReadyEvent} in the same transaction, so listeners see the
     * finished revision (ARCH-6). Nothing is published if it wasn't INDEXING any more.
     */
    @Transactional
    void markReady(long projectId, long revisionId, String commitSha, String statsJson) {
        if (queries.markReady(revisionId, statsJson, clock.instant())) {
            events.publishEvent(new RevisionReadyEvent(projectId, revisionId, commitSha));
        }
    }

    @Transactional
    void markFailed(long revisionId, String statsJson) {
        queries.markFailed(revisionId, statsJson);
    }

    private Map<BlobKey, ExistingBlob> existingByKey(long projectId, Collection<BlobKey> keys) {
        Set<BlobKey> wanted = new HashSet<>(keys);
        Map<BlobKey, ExistingBlob> found = new HashMap<>();
        List<String> hashes =
                wanted.stream().map(BlobKey::contentHash).distinct().toList();
        for (ExistingBlob blob : queries.findBlobs(projectId, hashes, SymbolChunker.VERSION)) {
            BlobKey key = new BlobKey(blob.contentHash(), blob.parserVersion());
            if (wanted.contains(key)) {
                found.put(key, blob);
            }
        }
        return found;
    }

    private static NewBlob row(long id, Blob blob) {
        NormalizedFile file = blob.file();
        return new NewBlob(
                id,
                blob.key().contentHash(),
                blob.key().parserVersion(),
                file.language(),
                file.sizeBytes(),
                file.lineCount(),
                file.content(),
                blob.status().name(),
                file.redactionCount());
    }
}
