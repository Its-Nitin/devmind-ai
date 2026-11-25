package com.devmind.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Guards every future migration against schema-level rule violations (DB-2, DB-3). */
@IntegrationTest
class SchemaTest {

    /**
     * Foreign keys whose columns aren't the leading columns of a valid, non-partial index. A partial index doesn't
     * cover every row, so it can't serve cascade deletes or joins on the FK.
     */
    private static final String UNINDEXED_FOREIGN_KEYS = """
            SELECT c.conrelid::regclass || '.' || c.conname AS fk
            FROM pg_constraint c
            WHERE c.contype = 'f'
              AND c.connamespace = 'public'::regnamespace
              AND NOT EXISTS (
                  SELECT 1
                  FROM pg_index i
                  WHERE i.indrelid = c.conrelid
                    AND i.indisvalid
                    AND i.indpred IS NULL
                    AND (i.indkey::int2[])[0:cardinality(c.conkey) - 1] @> c.conkey
                    AND (i.indkey::int2[])[0:cardinality(c.conkey) - 1] <@ c.conkey)
            ORDER BY fk
            """;

    private final JdbcClient jdbc;
    private final TestProjects projects;

    @Autowired
    SchemaTest(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.projects = new TestProjects(jdbc);
    }

    @Test
    void everyForeignKeyHasASupportingIndex() {
        List<String> unindexed =
                jdbc.sql(UNINDEXED_FOREIGN_KEYS).query(String.class).list();

        assertThat(unindexed)
                .as("DB-2: foreign keys without a supporting index")
                .isEmpty();
    }

    @Test
    void migrationsCreateExpectedTablesAndExtensions() {
        List<String> tables = jdbc.sql("SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY 1")
                .query(String.class)
                .list();
        List<String> extensions = jdbc.sql("SELECT extname FROM pg_extension ORDER BY 1")
                .query(String.class)
                .list();

        assertThat(tables)
                .contains(
                        "app_user",
                        "project",
                        "job",
                        "flyway_schema_history",
                        "repository_revision",
                        "file_blob",
                        "revision_file",
                        "code_symbol",
                        "code_chunk",
                        "embedding_model",
                        "chunk_embedding");
        assertThat(extensions).contains("vector", "pg_trgm", "citext");
    }

    @Test
    void deletingAProjectRemovesItsWholeIndex() {
        long projectId = insertProject();
        long revisionId = insertRevision(projectId, "a".repeat(40));
        long blobId = insertBlob(projectId);
        jdbc.sql("INSERT INTO revision_file (revision_id, path, file_blob_id) VALUES (?, ?, ?)")
                .params(revisionId, "src/main/java/Owner.java", blobId)
                .update();
        long typeId = insertSymbol(blobId, null, "CLASS", 1, 20);
        long methodId = insertSymbol(blobId, typeId, "METHOD", 5, 9);
        jdbc.sql("""
                        INSERT INTO code_chunk (file_blob_id, symbol_id, start_line, end_line, header, content,
                                                search_tsv, token_estimate, chunker_version)
                        VALUES (?, ?, 5, 9, 'Owner.java', 'void find() {}', to_tsvector('simple', 'find'), 4, 'c1')
                        """).params(blobId, methodId).update();
        long modelId = jdbc.sql("""
                        INSERT INTO embedding_model (provider, model_name, dimensions, model_digest, created_at)
                        VALUES ('fake', 'fake-embed', 1024, 'd1', now()) RETURNING id
                        """).query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO chunk_embedding (chunk_id, embedding_model_id, embedding)
                        SELECT id, ?, array_fill(0.5::real, ARRAY[1024])::vector FROM code_chunk WHERE file_blob_id = ?
                        """).params(modelId, blobId).update();

        jdbc.sql("DELETE FROM project WHERE id = ?").param(projectId).update();

        assertThat(count("SELECT count(*) FROM repository_revision WHERE project_id = ?", projectId))
                .isZero();
        assertThat(count("SELECT count(*) FROM file_blob WHERE project_id = ?", projectId))
                .isZero();
        assertThat(count("SELECT count(*) FROM revision_file WHERE revision_id = ?", revisionId))
                .isZero();
        assertThat(count("SELECT count(*) FROM code_symbol WHERE file_blob_id = ?", blobId))
                .isZero();
        assertThat(count("SELECT count(*) FROM code_chunk WHERE file_blob_id = ?", blobId))
                .isZero();
        assertThat(count("SELECT count(*) FROM chunk_embedding WHERE embedding_model_id = ?", modelId))
                .isZero();
    }

    @Test
    void revisionIsUniquePerShaAndConfigAndShaMustBeLowercaseHex() {
        long projectId = insertProject();
        String sha = "b".repeat(40);
        insertRevision(projectId, sha);

        assertThatThrownBy(() -> insertRevision(projectId, sha))
                .as("idempotency key (project, commit_sha, index_config_hash)")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRevision(projectId, "B".repeat(40)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void symbolLineRangeIsOneBasedAndOrderedAndKindIsKnown() {
        long blobId = insertBlob(insertProject());

        assertThatThrownBy(() -> insertSymbol(blobId, null, "CLASS", 0, 3))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertSymbol(blobId, null, "CLASS", 5, 4))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertSymbol(blobId, null, "PACKAGE", 1, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void embeddingsHaveTheSchemaDimensionAndOneVectorPerChunkAndModel() {
        long blobId = insertBlob(insertProject());
        jdbc.sql("""
                        INSERT INTO code_chunk (file_blob_id, start_line, end_line, header, content, search_tsv,
                                                token_estimate, chunker_version)
                        VALUES (?, 1, 1, 'Owner.java', 'class Owner {}', to_tsvector('simple', 'owner'), 4, 'c1')
                        """).param(blobId).update();
        long modelId = jdbc.sql("""
                        INSERT INTO embedding_model (provider, model_name, dimensions, model_digest, created_at)
                        VALUES ('fake', 'fake-embed', 1024, 'd2', now()) RETURNING id
                        """).query(Long.class).single();
        String insert = """
                INSERT INTO chunk_embedding (chunk_id, embedding_model_id, embedding)
                SELECT id, ?, array_fill(0.5::real, ARRAY[?])::vector FROM code_chunk WHERE file_blob_id = ?""";

        assertThatThrownBy(() -> jdbc.sql(insert).params(modelId, 768, blobId).update())
                .as("DB-10: vector(1024)")
                .isInstanceOf(DataAccessException.class);
        jdbc.sql(insert).params(modelId, 1024, blobId).update();
        assertThatThrownBy(() -> jdbc.sql(insert).params(modelId, 1024, blobId).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                                INSERT INTO embedding_model (provider, model_name, dimensions, model_digest, created_at)
                                VALUES ('fake', 'fake-embed', 1024, 'd2', now())""").update())
                .as("embedding_model_uk")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private long insertProject() {
        return projects.insertProject().id();
    }

    private long insertRevision(long projectId, String commitSha) {
        return jdbc.sql("""
                        INSERT INTO repository_revision (project_id, commit_sha, index_config_hash, status, created_at)
                        VALUES (?, ?, ?, 'INDEXING', now())
                        RETURNING id
                        """)
                .params(projectId, commitSha, "c".repeat(64))
                .query(Long.class)
                .single();
    }

    private long insertBlob(long projectId) {
        return jdbc.sql("""
                        INSERT INTO file_blob (project_id, content_hash, parser_version, language, size_bytes,
                                               line_count, content, parse_status)
                        VALUES (?, ?, 'p1', 'java', 14, 1, 'class Owner {}', 'OK')
                        RETURNING id
                        """).params(projectId, "d".repeat(64)).query(Long.class).single();
    }

    private long insertSymbol(long blobId, @Nullable Long parentId, String kind, int startLine, int endLine) {
        return jdbc.sql("""
                        INSERT INTO code_symbol (file_blob_id, parent_symbol_id, kind, name, qualified_name,
                                                 symbol_key, start_line, end_line)
                        VALUES (?, ?, ?, 'Owner', 'com.example.Owner', 'com.example.Owner', ?, ?)
                        RETURNING id
                        """)
                .params(blobId, parentId, kind, startLine, endLine)
                .query(Long.class)
                .single();
    }

    private long count(String sql, long id) {
        return jdbc.sql(sql).param(id).query(Long.class).single();
    }
}
