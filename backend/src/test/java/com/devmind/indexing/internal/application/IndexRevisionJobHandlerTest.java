package com.devmind.indexing.internal.application;

import static com.devmind.support.GitFixture.tree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.devmind.indexing.RevisionReadyEvent;
import com.devmind.ingestion.ProjectSource;
import com.devmind.job.JobOutcome;
import com.devmind.project.SourceType;
import com.devmind.support.GitFixture;
import com.devmind.support.IntegrationTest;
import com.devmind.support.TestProjects;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
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
 * the handler run directly. The test profile writes batches of 2 files, so the 5 kept files take 3 batches.
 */
@IntegrationTest
@RecordApplicationEvents
class IndexRevisionJobHandlerTest {

    private static final TypeReference<Map<String, Object>> STATS = new TypeReference<>() {};

    @TempDir
    Path temp; // under java.io.tmpdir, the test profile's allowed root

    private final IndexRevisionJobHandler handler;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TestProjects projects;

    private GitFixture repo;
    private RevCommit commitA;
    private long projectId;

    @Autowired
    IndexRevisionJobHandlerTest(IndexRevisionJobHandler handler, JdbcClient jdbc, JsonMapper json) {
        this.handler = handler;
        this.jdbc = jdbc;
        this.json = json;
        this.projects = new TestProjects(jdbc);
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

        // When
        JobOutcome again = index(projectId, commitA.name());

        // Then
        assertThat(again).isEqualTo(JobOutcome.succeeded());
        assertThat(counts(projectId)).isEqualTo(before);
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

        // Then: done grows to all 9 files, whatever was kept
        assertThat(context.progress()).isSorted().last().isEqualTo(9L);
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
