package com.devmind.indexing.internal.application;

import com.devmind.indexing.CodeParser;
import com.devmind.indexing.ParseStatus;
import com.devmind.indexing.ParsedFile;
import com.devmind.indexing.internal.application.IndexBatch.Blob;
import com.devmind.indexing.internal.application.IndexBatch.BlobKey;
import com.devmind.indexing.internal.domain.RevisionStats;
import com.devmind.indexing.internal.domain.SymbolChunker;
import com.devmind.indexing.internal.persistence.IndexingRows.ExistingBlob;
import com.devmind.indexing.internal.persistence.IndexingRows.Revision;
import com.devmind.indexing.internal.persistence.IndexingRows.RevisionStatus;
import com.devmind.indexing.internal.persistence.IndexingRows.RevisionTotals;
import com.devmind.ingestion.ExclusionReason;
import com.devmind.ingestion.FileOutcome;
import com.devmind.ingestion.IngestionStats;
import com.devmind.ingestion.NormalizedFile;
import com.devmind.ingestion.SnapshotOutcome;
import com.devmind.ingestion.SourceFile;
import com.devmind.ingestion.SourceProblem;
import com.devmind.ingestion.SourceSnapshot;
import com.devmind.ingestion.SourceSnapshotProvider;
import com.devmind.job.JobContext;
import com.devmind.job.JobHandler;
import com.devmind.job.JobOutcome;
import com.devmind.job.JobType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs INDEX jobs (M2 step 7): snapshot, filter, parse, chunk and store a commit, batch by batch, then validate and
 * mark the revision READY. No transaction spans the run (SPRING-2); each batch commits on its own, and a retried run
 * resumes, because every write is an upsert and every stored blob is complete. Re-indexing a READY revision is a
 * no-op.
 */
@Component
class IndexRevisionJobHandler implements JobHandler {

    static final String PHASE = "files";

    private static final Logger log = LoggerFactory.getLogger(IndexRevisionJobHandler.class);

    private final SourceSnapshotProvider sources;
    private final IndexToolchain toolchain;
    private final IndexWriter writer;
    private final IndexingProperties properties;
    private final Clock clock;
    private final JsonMapper json;

    IndexRevisionJobHandler(
            SourceSnapshotProvider sources,
            IndexToolchain toolchain,
            IndexWriter writer,
            IndexingProperties properties,
            Clock clock,
            JsonMapper json) {
        this.sources = sources;
        this.toolchain = toolchain;
        this.writer = writer;
        this.properties = properties;
        this.clock = clock;
        this.json = json;
    }

    @Override
    public JobType type() {
        return JobType.INDEX;
    }

    @Override
    public JobOutcome handle(JobContext context) {
        Optional<IndexRequest> request = IndexRequest.fromPayload(context.payload());
        if (request.isEmpty()) {
            return JobOutcome.permanent("index-request-invalid", "The index job's payload is incomplete.");
        }
        return switch (sources.open(
                request.orElseThrow().source(), request.orElseThrow().ref())) {
            case SnapshotOutcome.Failed(SourceProblem problem) ->
                problem.retryable()
                        ? JobOutcome.retryable(problem.code(), problem.detail())
                        : JobOutcome.permanent(problem.code(), problem.detail());
            case SnapshotOutcome.Opened(SourceSnapshot snapshot) -> {
                try (snapshot) {
                    yield index(context, snapshot);
                }
            }
        };
    }

    private JobOutcome index(JobContext context, SourceSnapshot snapshot) {
        Instant started = clock.instant();
        long projectId = context.projectId();
        Revision revision = writer.startRevision(projectId, snapshot.commitSha(), toolchain.configHash());
        if (revision.status() == RevisionStatus.READY) {
            log.info("Revision {} is already indexed; nothing to do", revision.id());
            return JobOutcome.succeeded();
        }
        log.info(
                "Indexing revision {} ({} files)",
                revision.id(),
                snapshot.stats().files());

        Run run = new Run(IngestionStats.of(snapshot.stats()));
        long total = snapshot.stats().files();
        long done = 0;
        List<NormalizedFile> pending = new ArrayList<>();
        Iterator<SourceFile> files = snapshot.files().iterator();
        while (files.hasNext()) {
            FileOutcome outcome = toolchain.pipeline().process(files.next());
            run.ingestion = run.ingestion.add(outcome);
            done++;
            if (outcome instanceof FileOutcome.Kept(NormalizedFile file)) {
                pending.add(file);
            }
            if (pending.size() >= properties.batchSize() || !files.hasNext()) {
                run.add(writeBatch(projectId, revision.id(), pending));
                pending.clear();
                context.progress(PHASE, done, total);
                if (!context.heartbeat()) {
                    return JobOutcome.retryable("lease-lost", "Another attempt took over the job.");
                }
            }
        }
        return finish(projectId, revision.id(), snapshot.commitSha(), run, Duration.between(started, clock.instant()));
    }

    /** Parses and chunks the batch's new blobs outside any transaction, then writes it in one. */
    private IndexBatch.Result writeBatch(long projectId, long revisionId, List<NormalizedFile> files) {
        List<IndexBatch.Path> paths = new ArrayList<>(files.size());
        Map<BlobKey, NormalizedFile> distinct = new LinkedHashMap<>();
        for (NormalizedFile file : files) {
            BlobKey key = new BlobKey(file.contentHash(), parserVersion(file.language()));
            paths.add(new IndexBatch.Path(file.path(), key));
            distinct.putIfAbsent(key, file);
        }
        Map<BlobKey, ExistingBlob> existing = writer.existing(projectId, distinct.keySet());
        List<Blob> blobs = new ArrayList<>();
        distinct.forEach((key, file) -> {
            ExistingBlob stored = existing.get(key);
            if (stored == null || stored.staleChunks()) {
                blobs.add(prepare(key, file));
            }
        });
        return writer.write(projectId, revisionId, new IndexBatch(paths, blobs, existing));
    }

    /** Java is parsed into symbols and chunked by them; anything else, or Java that failed to parse, by lines. */
    private Blob prepare(BlobKey key, NormalizedFile file) {
        Optional<CodeParser> parser = toolchain.parserFor(file.language());
        if (parser.isEmpty()) {
            return new Blob(
                    key, file, ParseStatus.SKIPPED, List.of(), SymbolChunker.chunk(file.path(), file.content(), null));
        }
        ParsedFile parsed = parser.orElseThrow().parse(file.path(), file.content());
        return new Blob(
                key, file, parsed.status(), parsed.symbols(), SymbolChunker.chunk(file.path(), file.content(), parsed));
    }

    private String parserVersion(String language) {
        return toolchain.parserFor(language).map(CodeParser::version).orElse(IndexToolchain.NO_PARSER);
    }

    /** Validates the stored revision against what was kept, then marks it READY or FAILED with its stats. */
    private JobOutcome finish(long projectId, long revisionId, String commitSha, Run run, Duration duration) {
        RevisionTotals totals = writer.totals(revisionId);
        String stats = json.writeValueAsString(stats(run, totals, duration));
        if (totals.files() != run.ingestion.kept() || totals.unchunkedFiles() > 0) {
            writer.markFailed(revisionId, stats);
            log.error(
                    "Revision {} is incomplete: {} of {} files stored, {} without chunks",
                    revisionId,
                    totals.files(),
                    run.ingestion.kept(),
                    totals.unchunkedFiles());
            return JobOutcome.permanent("index-incomplete", "Indexing finished with missing files or chunks.");
        }
        writer.markReady(projectId, revisionId, commitSha, stats);
        log.info("Revision {} is ready: {}", revisionId, stats);
        return JobOutcome.succeeded();
    }

    /**
     * {@code repository_revision.stats}. Totals come from the database, so they're right however many attempts the
     * run took; {@code blobsNew} and {@code blobsReused} describe the last attempt.
     */
    private static RevisionStats stats(Run run, RevisionTotals totals, Duration duration) {
        Map<String, Integer> excluded = new LinkedHashMap<>();
        run.ingestion.excluded().forEach((ExclusionReason reason, Integer count) -> excluded.put(reason.key(), count));
        return new RevisionStats(
                totals.files(),
                excluded,
                run.blobsReused,
                run.blobsNew,
                totals.symbols(),
                totals.chunks(),
                totals.parseFailures(),
                totals.redactions(),
                run.ingestion.encodingFallbacks(),
                duration.toMillis());
    }

    /** Counters of one attempt. */
    private static final class Run {

        private IngestionStats ingestion;
        private int blobsNew;
        private int blobsReused;

        Run(IngestionStats ingestion) {
            this.ingestion = ingestion;
        }

        void add(IndexBatch.Result result) {
            blobsNew += result.blobsNew();
            blobsReused += result.blobsReused();
        }
    }
}
