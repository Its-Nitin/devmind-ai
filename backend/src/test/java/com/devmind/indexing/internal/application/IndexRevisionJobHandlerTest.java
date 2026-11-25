package com.devmind.indexing.internal.application;

import static com.devmind.support.GitFixture.tree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.devmind.ai.EmbeddingModelRef;
import com.devmind.ai.EmbeddingPort;
import com.devmind.ai.EmbeddingUnavailableException;
import com.devmind.indexing.CodeParser;
import com.devmind.indexing.RevisionReadyEvent;
import com.devmind.ingestion.IngestionPipeline;
import com.devmind.ingestion.ProjectSource;
import com.devmind.ingestion.SourceSnapshotProvider;
import com.devmind.job.JobOutcome;
import com.devmind.project.SourceType;
import com.devmind.support.FakeEmbedding;
import com.devmind.support.GitFixture;
import com.devmind.support.IntegrationTest;
import com.devmind.support.TestProjects;
import com.pgvector.PGvector;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * M2 step 7, end to end on Testcontainers Postgres: a fixture repository, the real ingestion, parser and chunker, and
 * the handler run directly. The test profile writes batches of 2 files, so the 5 kept files take 3 batches, and
 * embeds 3 chunks per batch, with the deterministic {@link FakeEmbedding} (AI-11).
 */
@IntegrationTest
@RecordApplicationEvents
class IndexRevisionJobHandlerTest {

    private static final TypeReference<Map<String, Object>> STATS = new TypeReference<>() {};

    private static final JobOutcome UNREACHABLE = JobOutcome.retryable(
            EmbeddingUnavailableException.UNREACHABLE, "The embedding model can't be reached right now.");

    @TempDir
    Path temp; // under java.io.tmpdir, the test profile's allowed root

    private final IndexRevisionJobHandler handler;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TestProjects projects;
    private final FakeEmbedding embedding;
    private final Collaborators collaborators;

    private GitFixture repo;
    private RevCommit commitA;
    private long projectId;

    @Autowired
    IndexRevisionJobHandlerTest(
            IndexRevisionJobHandler handler,
            JdbcClient jdbc,
            JsonMapper json,
            FakeEmbedding embedding,
            SourceSnapshotProvider sources,
            List<CodeParser> parsers,
            IngestionPipeline pipeline,
            IndexWriter writer,
            IndexingProperties properties,
            Clock clock) {
        this.handler = handler;
        this.jdbc = jdbc;
        this.json = json;
        this.projects = new TestProjects(jdbc);
        this.embedding = embedding;
        this.collaborators = new Collaborators(sources, parsers, pipeline, writer, properties, clock);
    }

    @BeforeEach
    void createRepositoryAndProject() {
        repo = GitFixture.init(temp.resolve("app"));
        commitA = repo.commit("main", fiveFilesAnd(tree()));
        projectId = projects.insertProject().id();
    }

    @AfterEach
    void closeRepository() {
        repo.close();
    }

    @Test
    void indexesACommitAndMarksItReadyWithItsStats(ApplicationEvents events) {
        // When
        JobOutcome outcome = index(projectId, commitA.name());

        // Then
        assertThat(outcome).isEqualTo(JobOutcome.succeeded());
        Map<String, @Nullable Object> revision = revision(projectId, commitA.name());
        assertThat(revision).containsEntry("status", "READY");
        assertThat(revision.get("ready_at")).isNotNull();
        Map<String, Object> stats = stats(revision);
        assertThat(stats)
                .containsEntry("filesKept", 5)
                .containsEntry("blobsNew", 5)
                .containsEntry("blobsReused", 0)
                .containsEntry("symbols", 6) // Owner: class, field, constructor, method; OwnerService: class, method
                .containsEntry("parseFailures", 0)
                .containsEntry("redactions", 1);
        assertThat(stats.get("excluded"))
                .isEqualTo(Map.of(
                        "path", 1,
                        "secretFile", 1,
                        "size", 0,
                        "binary", 1,
                        "extension", 1,
                        "symlink", 0,
                        "submodule", 0,
                        "invalidPath", 0));
        assertThat(counts(projectId)).isEqualTo(new Counts(1, 5, 5, 6, number(stats, "chunks")));
        assertThat(events.stream(RevisionReadyEvent.class))
                .containsExactly(new RevisionReadyEvent(projectId, id(revision), commitA.name()));
    }

    @Test
    void storesRedactedContentLinkedSymbolsAndChunks() {
        // When
        index(projectId, commitA.name());

        // Then: the secret never reaches the database
        assertThat(blobContent(projectId, "src/main/resources/application.properties"))
                .doesNotContain("hunter2")
                .contains("«REDACTED»");
        // members point at their type, and member chunks at their member
        assertThat(jdbc.sql("""
                        SELECT s.name FROM code_symbol s
                          JOIN code_symbol p ON p.id = s.parent_symbol_id
                          JOIN file_blob b ON b.id = s.file_blob_id
                         WHERE b.project_id = ? AND p.name = 'Owner' ORDER BY s.start_line""").param(projectId).query(String.class).list()).containsExactly("name", "Owner", "name");
        // package-info declares nothing, yet is searchable as text
        assertThat(chunksOf(projectId, "src/main/java/com/acme/package-info.java"))
                .isEqualTo(new FileChunks(1, 0));
    }

    @Test
    void reindexingTheSameCommitWritesNothing(ApplicationEvents events) {
        // Given
        index(projectId, commitA.name());
        Counts before = counts(projectId);
        int embeddedBefore = embedding.documentsEmbedded();

        // When
        JobOutcome again = index(projectId, commitA.name());

        // Then
        assertThat(again).isEqualTo(JobOutcome.succeeded());
        assertThat(counts(projectId)).isEqualTo(before);
        assertThat(embedding.documentsEmbedded()).isEqualTo(embeddedBefore);
        assertThat(events.stream(RevisionReadyEvent.class)).hasSize(1);
    }

    @Test
    void aCommitChangingOneFileParsesOnlyThatFile() {
        // Given
        index(projectId, commitA.name());
        RevCommit commitB = repo.commit("main", fiveFilesAnd(tree()).file("README.md", "# Owners\n\nNow with pets.\n"));

        // When
        index(projectId, commitB.name());

        // Then
        assertThat(stats(revision(projectId, commitB.name())))
                .containsEntry("blobsNew", 1)
                .containsEntry("blobsReused", 4)
                .containsEntry("filesKept", 5);
        assertThat(counts(projectId).blobs()).isEqualTo(6);
    }

    @Test
    void aFileThatFailsToParseStillLetsTheRevisionBecomeReady() {
        // Given
        RevCommit broken = repo.commit(
                "main",
                fiveFilesAnd(tree()).file("src/main/java/com/acme/Broken.java", "class Broken {\n  void x( {\n}\n"));

        // When
        JobOutcome outcome = index(projectId, broken.name());

        // Then: indexed as text (ERR-4)
        assertThat(outcome).isEqualTo(JobOutcome.succeeded());
        Map<String, @Nullable Object> revision = revision(projectId, broken.name());
        assertThat(revision).containsEntry("status", "READY");
        assertThat(stats(revision)).containsEntry("parseFailures", 1).containsEntry("filesKept", 6);
        assertThat(chunksOf(projectId, "src/main/java/com/acme/Broken.java")).isEqualTo(new FileChunks(1, 0));
    }

    @Test
    void resumesAfterACrashMidwayAndEndsWithTheSameRowsAsAnUninterruptedRun() {
        // Given: the worker dies right after the first batch committed
        assertThatIllegalStateException()
                .isThrownBy(() -> handler.handle(FakeJobContext.crashingAt(1, projectId, payload(commitA.name()))));
        assertThat(revision(projectId, commitA.name())).containsEntry("status", "INDEXING");
        assertThat(counts(projectId).revisionFiles()).isEqualTo(2);

        // When: the retry
        JobOutcome retry = index(projectId, commitA.name());

        // Then: exactly what a run without the crash leaves behind
        long cleanProject = projects.insertProject().id();
        index(cleanProject, commitA.name());
        assertThat(retry).isEqualTo(JobOutcome.succeeded());
        assertThat(revision(projectId, commitA.name())).containsEntry("status", "READY");
        assertThat(counts(projectId)).isEqualTo(counts(cleanProject));
        assertThat(embeddings(projectId, FakeEmbedding.MODEL))
                .isEqualTo(embeddings(cleanProject, FakeEmbedding.MODEL))
                .isEqualTo(counts(projectId).chunks());
        assertThat(stats(revision(projectId, commitA.name())))
                .containsEntry("filesKept", 5)
                .containsEntry("symbols", 6);
    }

    @Test
    void rebuildsChunksCutByAnotherChunkerVersion() {
        // Given: chunks of every blob look as if an older chunker made them
        index(projectId, commitA.name());
        long chunks = counts(projectId).chunks();
        jdbc.sql("""
                        UPDATE code_chunk SET chunker_version = 'chunk-0'
                         WHERE file_blob_id IN (SELECT id FROM file_blob WHERE project_id = ?)""").param(projectId).update();
        RevCommit commitB = repo.commit("main", fiveFilesAnd(tree()).file("NOTES.md", "new file\n"));

        // When
        index(projectId, commitB.name());

        // Then: rebuilt, not duplicated
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM code_chunk c JOIN file_blob b ON b.id = c.file_blob_id
                         WHERE b.project_id = ? AND c.chunker_version <> 'chunk-1'""").param(projectId).query(Long.class).single()).isZero();
        assertThat(counts(projectId).chunks()).isEqualTo(chunks + 1);
    }

    @Test
    void failsPermanentlyForARefThatNamesNothingOrAMalformedPayload() {
        assertThat(index(projectId, "develop"))
                .isEqualTo(JobOutcome.permanent("ref-not-found", "No commit matches the ref."));
        assertThat(handler.handle(FakeJobContext.of(projectId, Map.of("ref", "main"))))
                .isEqualTo(JobOutcome.permanent("index-request-invalid", "The index job's payload is incomplete."));
        assertThat(counts(projectId).revisions()).isZero();
    }

    @Test
    void reportsProgressAfterEveryBatch() {
        // When
        FakeJobContext context = FakeJobContext.of(projectId, payload(commitA.name()));
        handler.handle(context);

        // Then: files grow to all 9 entries, whatever was kept; then embedding, from 0 to every chunk
        long chunks = counts(projectId).chunks();
        assertThat(context.phases())
                .containsExactly(IndexRevisionJobHandler.PHASE, IndexRevisionJobHandler.EMBEDDING_PHASE);
        assertThat(context.progress(IndexRevisionJobHandler.PHASE))
                .extracting(FakeJobContext.Report::done)
                .isSorted()
                .last()
                .isEqualTo(9L);
        assertThat(context.progress(IndexRevisionJobHandler.EMBEDDING_PHASE))
                .allSatisfy(report -> assertThat(report.total()).isEqualTo(chunks))
                .extracting(FakeJobContext.Report::done)
                .isSorted()
                .startsWith(0L)
                .endsWith(chunks);
    }

    @Test
    void embedsEveryChunkOfTheRevisionFromItsHeaderAndContent() throws SQLException {
        // Given
        int embeddedBefore = embedding.documentsEmbedded();

        // When
        index(projectId, commitA.name());

        // Then: one vector per chunk, for the fake model
        long chunks = counts(projectId).chunks();
        assertThat(embeddings(projectId, FakeEmbedding.MODEL)).isEqualTo(chunks);
        assertThat(embedding.documentsEmbedded() - embeddedBefore).isEqualTo(chunks);
        assertThat(embeddingStats(projectId, commitA.name()))
                .isEqualTo(new EmbeddingStatsRow("fake/fake-embed@fake-digest-1", chunks, 0));
        // and the vector is the model's for header + "\n" + content
        Map<String, @Nullable Object> chunk =
                jdbc.sql("""
                        SELECT c.header, c.content, e.embedding::text AS embedding
                          FROM code_chunk c JOIN chunk_embedding e ON e.chunk_id = c.id
                          JOIN file_blob b ON b.id = c.file_blob_id
                         WHERE b.project_id = ? ORDER BY c.id LIMIT 1""").param(projectId).query().singleRow();
        String text = chunk.get("header") + "\n" + chunk.get("content");
        assertThat(new PGvector((String) Objects.requireNonNull(chunk.get("embedding"))).toArray())
                .containsExactly(embedding.embedQuery(text));
    }

    @Test
    void aCommitChangingOneFileEmbedsOnlyTheNewChunks() {
        // Given
        index(projectId, commitA.name());
        long chunksBefore = counts(projectId).chunks();
        RevCommit commitB = repo.commit("main", fiveFilesAnd(tree()).file("README.md", "# Owners\n\nNow with pets.\n"));
        int embeddedBefore = embedding.documentsEmbedded();

        // When
        index(projectId, commitB.name());

        // Then: only the new README blob's chunks went to the model
        long fresh = counts(projectId).chunks() - chunksBefore;
        assertThat(fresh).isPositive();
        assertThat(embedding.documentsEmbedded() - embeddedBefore).isEqualTo(fresh);
        long revisionChunks = jdbc.sql("""
                        SELECT count(*) FROM code_chunk c
                         WHERE c.file_blob_id IN (SELECT rf.file_blob_id FROM revision_file rf
                                                    JOIN repository_revision r ON r.id = rf.revision_id
                                                   WHERE r.project_id = ? AND r.commit_sha = ?)""")
                .params(projectId, commitB.name())
                .query(Long.class)
                .single();
        assertThat(embeddingStats(projectId, commitB.name()))
                .isEqualTo(new EmbeddingStatsRow("fake/fake-embed@fake-digest-1", fresh, revisionChunks - fresh));
    }

    @Test
    void anotherModelIndexesTheSameCommitAsANewRevisionWithItsOwnVectorsAndKeepsTheOld() {
        // Given
        index(projectId, commitA.name());
        Counts before = counts(projectId);
        EmbeddingModelRef repulled = new EmbeddingModelRef("fake", "fake-embed", 1024, "fake-digest-2");
        FakeEmbedding other = new FakeEmbedding(repulled);

        // When: the same model name with another digest (threat model T17)
        JobOutcome outcome = handlerWith(other).handle(FakeJobContext.of(projectId, payload(commitA.name())));

        // Then: a second READY revision of the commit; blobs and chunks reused, every chunk embedded again
        assertThat(outcome).isEqualTo(JobOutcome.succeeded());
        assertThat(jdbc.sql("SELECT status FROM repository_revision WHERE project_id = ? AND commit_sha = ?")
                        .params(projectId, commitA.name())
                        .query(String.class)
                        .list())
                .containsExactly("READY", "READY");
        assertThat(counts(projectId)).isEqualTo(new Counts(2, 10, before.blobs(), before.symbols(), before.chunks()));
        assertThat(other.documentsEmbedded()).isEqualTo(before.chunks());
        assertThat(embeddings(projectId, repulled)).isEqualTo(before.chunks());
        assertThat(embeddings(projectId, FakeEmbedding.MODEL)).isEqualTo(before.chunks());
    }

    @Test
    void aModelServerThatFailsMidwayFailsTheAttemptRetryablyAndTheRetryResumes() {
        // Given: the model server fails on its second batch
        IndexRevisionJobHandler flaky = handlerWith(new FlakyEmbedding(embedding, 2));

        // When
        JobOutcome first = flaky.handle(FakeJobContext.of(projectId, payload(commitA.name())));

        // Then: retryable, with the first batch of 3 kept
        assertThat(first).isEqualTo(UNREACHABLE);
        assertThat(revision(projectId, commitA.name())).containsEntry("status", "INDEXING");
        assertThat(embeddings(projectId, FakeEmbedding.MODEL)).isEqualTo(3);

        // When: the retry, with the server back
        JobOutcome retry = flaky.handle(FakeJobContext.of(projectId, payload(commitA.name())));

        // Then: only what was missing is embedded
        long chunks = counts(projectId).chunks();
        assertThat(retry).isEqualTo(JobOutcome.succeeded());
        assertThat(embeddings(projectId, FakeEmbedding.MODEL)).isEqualTo(chunks);
        assertThat(embeddingStats(projectId, commitA.name()))
                .isEqualTo(new EmbeddingStatsRow("fake/fake-embed@fake-digest-1", chunks - 3, 3));
    }

    @Test
    void aModelServerDownAtTheStartCreatesNoRevision() {
        // When: the server can't even say which model it serves
        JobOutcome outcome = handlerWith(new FlakyEmbedding(embedding, 0))
                .handle(FakeJobContext.of(projectId, payload(commitA.name())));

        // Then: the revision is keyed by the model, so nothing is written yet
        assertThat(outcome).isEqualTo(UNREACHABLE);
        assertThat(counts(projectId).revisions()).isZero();
    }

    @Test
    void aModelOfAnotherDimensionIsRefusedBeforeAnythingIsWritten() {
        // Given
        FakeEmbedding small = new FakeEmbedding(new EmbeddingModelRef("fake", "small-embed", 768, "fake-digest-3"));

        // When / Then: the schema stores vector(1024)
        assertThatIllegalStateException()
                .isThrownBy(() -> handlerWith(small).handle(FakeJobContext.of(projectId, payload(commitA.name()))))
                .withMessageContaining("768 dimensions");
        assertThat(counts(projectId).revisions()).isZero();
    }

    /** 5 kept files (2 Java with 6 symbols, a package-info, a properties file with a secret, a README), 4 excluded. */
    private static GitFixture.Tree fiveFilesAnd(GitFixture.Tree tree) {
        return tree.file("src/main/java/com/acme/Owner.java", """
                        package com.acme;

                        public class Owner {
                            private final String name;

                            public Owner(String name) {
                                this.name = name;
                            }

                            public String name() {
                                return name;
                            }
                        }
                        """)
                .file("src/main/java/com/acme/OwnerService.java", """
                        package com.acme;

                        class OwnerService {
                            Owner find(String name) {
                                return new Owner(name);
                            }
                        }
                        """)
                .file("src/main/java/com/acme/package-info.java", "package com.acme;\n")
                .file("src/main/resources/application.properties", "spring.datasource.password=hunter2hunter2\n")
                .file("README.md", "# Owners\n")
                .file("target/classes/Owner.class", "compiled") // path
                .file(".env", "TOKEN=x") // secret file
                .file("docs/logo.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0}) // binary
                .file("docs/notes.txt", "notes"); // extension
    }

    private JobOutcome index(long project, String ref) {
        return handler.handle(FakeJobContext.of(project, payload(ref)));
    }

    /** The application's beans an INDEX handler needs besides its embedding model. */
    private record Collaborators(
            SourceSnapshotProvider sources,
            List<CodeParser> parsers,
            IngestionPipeline pipeline,
            IndexWriter writer,
            IndexingProperties properties,
            Clock clock) {}

    /** A handler like the application's, embedding with {@code port} instead. */
    private IndexRevisionJobHandler handlerWith(EmbeddingPort port) {
        Collaborators c = collaborators;
        ActiveEmbeddingModel model = new ActiveEmbeddingModel(port, c.writer());
        return new IndexRevisionJobHandler(
                c.sources(),
                new IndexToolchain(c.parsers(), c.pipeline(), model),
                c.writer(),
                c.properties(),
                c.clock(),
                json);
    }

    /**
     * The fake model behind a server that fails: on the {@code failingCall}th document batch, or, for 0, when asked
     * which model it serves. Every other call succeeds.
     */
    private static final class FlakyEmbedding implements EmbeddingPort {

        private final FakeEmbedding model;
        private final int failingCall;
        private final AtomicInteger calls = new AtomicInteger();

        FlakyEmbedding(FakeEmbedding model, int failingCall) {
            this.model = model;
            this.failingCall = failingCall;
        }

        @Override
        public EmbeddingModelRef model() {
            if (failingCall == 0) {
                throw unreachable();
            }
            return model.model();
        }

        @Override
        public List<float[]> embedDocuments(List<String> texts) {
            if (calls.incrementAndGet() == failingCall) {
                throw unreachable();
            }
            return model.embedDocuments(texts);
        }

        @Override
        public float[] embedQuery(String text) {
            return model.embedQuery(text);
        }

        private static EmbeddingUnavailableException unreachable() {
            return new EmbeddingUnavailableException(EmbeddingUnavailableException.UNREACHABLE, "connection refused");
        }
    }

    /** Vectors of the project's chunks for {@code model}. */
    private long embeddings(long project, EmbeddingModelRef model) {
        return jdbc.sql("""
                        SELECT count(*) FROM chunk_embedding e
                          JOIN embedding_model m ON m.id = e.embedding_model_id
                          JOIN code_chunk c ON c.id = e.chunk_id
                          JOIN file_blob b ON b.id = c.file_blob_id
                         WHERE b.project_id = ? AND m.provider = ? AND m.model_name = ? AND m.model_digest = ?""")
                .params(project, model.provider(), model.name(), model.digest())
                .query(Long.class)
                .single();
    }

    /** The embedding part of a revision's stats, without its duration. */
    record EmbeddingStatsRow(String model, long embeddingsNew, long embeddingsReused) {}

    private EmbeddingStatsRow embeddingStats(long project, String sha) {
        return jdbc.sql("""
                        SELECT stats->'embeddings'->>'model' AS model,
                               (stats->'embeddings'->>'embeddingsNew')::bigint AS fresh,
                               (stats->'embeddings'->>'embeddingsReused')::bigint AS reused
                          FROM repository_revision WHERE project_id = ? AND commit_sha = ?""")
                .params(project, sha)
                .query((rs, row) ->
                        new EmbeddingStatsRow(rs.getString("model"), rs.getLong("fresh"), rs.getLong("reused")))
                .single();
    }

    private Map<String, Object> payload(String ref) {
        return new IndexRequest(
                        ref,
                        new ProjectSource(SourceType.LOCAL, temp.resolve("app").toString()))
                .toPayload();
    }

    private Map<String, @Nullable Object> revision(long project, String sha) {
        return jdbc.sql("SELECT id, status, ready_at, stats::text AS stats FROM repository_revision"
                        + " WHERE project_id = ? AND commit_sha = ?")
                .params(project, sha)
                .query()
                .singleRow();
    }

    private Map<String, Object> stats(Map<String, @Nullable Object> revision) {
        return json.readValue((String) revision.get("stats"), STATS);
    }

    private static long id(Map<String, @Nullable Object> revision) {
        return number(revision, "id");
    }

    private static long number(Map<String, ? extends @Nullable Object> row, String column) {
        return ((Number) Objects.requireNonNull(row.get(column), column)).longValue();
    }

    private String blobContent(long project, String path) {
        return jdbc.sql("""
                        SELECT b.content FROM revision_file rf
                          JOIN repository_revision r ON r.id = rf.revision_id
                          JOIN file_blob b ON b.id = rf.file_blob_id
                         WHERE r.project_id = ? AND rf.path = ? LIMIT 1""").params(project, path).query(String.class).single();
    }

    /** Chunks of the blob at {@code path}: how many, and how many belong to a symbol. */
    record FileChunks(long chunks, long withSymbol) {}

    private FileChunks chunksOf(long project, String path) {
        return jdbc.sql("""
                        SELECT count(*) AS chunks, count(c.symbol_id) AS with_symbol FROM code_chunk c
                         WHERE c.file_blob_id = (SELECT rf.file_blob_id FROM revision_file rf
                                                   JOIN repository_revision r ON r.id = rf.revision_id
                                                  WHERE r.project_id = ? AND rf.path = ? LIMIT 1)""")
                .params(project, path)
                .query((rs, row) -> new FileChunks(rs.getLong("chunks"), rs.getLong("with_symbol")))
                .single();
    }

    /** Rows a project owns, table by table. */
    record Counts(long revisions, long revisionFiles, long blobs, long symbols, long chunks) {}

    private Counts counts(long project) {
        return jdbc.sql("""
                        SELECT (SELECT count(*) FROM repository_revision WHERE project_id = :p) AS revisions,
                               (SELECT count(*) FROM revision_file rf JOIN repository_revision r ON r.id = rf.revision_id
                                 WHERE r.project_id = :p) AS revision_files,
                               (SELECT count(*) FROM file_blob WHERE project_id = :p) AS blobs,
                               (SELECT count(*) FROM code_symbol s JOIN file_blob b ON b.id = s.file_blob_id
                                 WHERE b.project_id = :p) AS symbols,
                               (SELECT count(*) FROM code_chunk c JOIN file_blob b ON b.id = c.file_blob_id
                                 WHERE b.project_id = :p) AS chunks""")
                .param("p", project)
                .query((rs, row) -> new Counts(
                        rs.getLong("revisions"),
                        rs.getLong("revision_files"),
                        rs.getLong("blobs"),
                        rs.getLong("symbols"),
                        rs.getLong("chunks")))
                .single();
    }
}
