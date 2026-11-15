package com.devmind.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    SchemaTest(JdbcClient jdbc) {
        this.jdbc = jdbc;
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
                        "code_chunk");
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

    // The test database isn't cleaned between tests, so each test inserts its own user and project.
    private long insertProject() {
        UUID ownerId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO app_user (public_id, email, display_name, password_hash, created_at)
                        VALUES (?, ?, 'Schema Test', 'x', now())
                        """).params(ownerId, "schema-" + ownerId + "@test.local").update();
        return jdbc.sql("""
                        INSERT INTO project (public_id, owner_id, name, source_type, source_location, created_at)
                        VALUES (?, ?, 'p', 'LOCAL', '/targets/p', now())
                        RETURNING id
                        """)
                .params(UUID.randomUUID(), ownerId)
                .query(Long.class)
                .single();
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
