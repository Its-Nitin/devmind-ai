package com.devmind.indexing.internal.persistence;

import com.devmind.indexing.ParsedSymbol;
import com.devmind.indexing.internal.persistence.IndexingRows.ExistingBlob;
import com.devmind.indexing.internal.persistence.IndexingRows.NewBlob;
import com.devmind.indexing.internal.persistence.IndexingRows.NewChunk;
import com.devmind.indexing.internal.persistence.IndexingRows.NewSymbol;
import com.devmind.indexing.internal.persistence.IndexingRows.Revision;
import com.devmind.indexing.internal.persistence.IndexingRows.RevisionFile;
import com.devmind.indexing.internal.persistence.IndexingRows.RevisionStatus;
import com.devmind.indexing.internal.persistence.IndexingRows.RevisionTotals;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

/**
 * Every statement on the structural index (V3 schema). Bulk writes are JDBC batches, one round trip per table per
 * batch (DB-8). IDs of new blobs and symbols are taken from their identity sequences up front, so symbols can point at
 * their parents and chunks at their symbols within the same batch. The caller owns the transaction (SPRING-2).
 */
@Repository
public class IndexingQueries {

    private static final String INSERT_REVISION = """
            INSERT INTO repository_revision (project_id, commit_sha, index_config_hash, status, created_at)
            VALUES (:projectId, :sha, :configHash, 'INDEXING', :now)
            ON CONFLICT (project_id, commit_sha, index_config_hash) DO NOTHING""";

    private static final String FIND_REVISION = """
            SELECT id, status FROM repository_revision
             WHERE project_id = :projectId AND commit_sha = :sha AND index_config_hash = :configHash""";

    private static final String RESTART_REVISION =
            "UPDATE repository_revision SET status = 'INDEXING', ready_at = NULL WHERE id = :id AND status = 'FAILED'";

    /** A blob's chunks are stale if any was cut by another chunker version (the version is in the config hash). */
    private static final String FIND_BLOBS = """
            SELECT b.id, b.content_hash, b.parser_version,
                   EXISTS (SELECT 1 FROM code_chunk c
                            WHERE c.file_blob_id = b.id AND c.chunker_version <> :chunkerVersion) AS stale
              FROM file_blob b
             WHERE b.project_id = :projectId AND b.content_hash IN (:hashes)""";

    private static final String ALLOCATE_BLOB_IDS =
            "SELECT nextval(pg_get_serial_sequence('file_blob', 'id')) FROM generate_series(1, :count)";

    private static final String ALLOCATE_SYMBOL_IDS =
            "SELECT nextval(pg_get_serial_sequence('code_symbol', 'id')) FROM generate_series(1, :count)";

    /** A concurrent run may have inserted the same blob; it then counts 0 here and that run writes its symbols. */
    private static final String INSERT_BLOB = """
            INSERT INTO file_blob (id, project_id, content_hash, parser_version, language, size_bytes, line_count,
                                   content, parse_status, redaction_count)
            OVERRIDING SYSTEM VALUE
            VALUES (:id, :projectId, :contentHash, :parserVersion, :language, :sizeBytes, :lineCount, :content,
                    :parseStatus, :redactionCount)
            ON CONFLICT (project_id, content_hash, parser_version) DO NOTHING""";

    private static final String DELETE_CHUNKS = "DELETE FROM code_chunk WHERE file_blob_id IN (:blobIds)";
    private static final String DELETE_SYMBOLS = "DELETE FROM code_symbol WHERE file_blob_id IN (:blobIds)";

    private static final String INSERT_SYMBOL = """
            INSERT INTO code_symbol (id, file_blob_id, parent_symbol_id, kind, name, qualified_name, signature,
                                     symbol_key, modifiers, annotations, is_test, start_line, end_line)
            OVERRIDING SYSTEM VALUE
            VALUES (:id, :blobId, :parentId, :kind, :name, :qualifiedName, :signature, :symbolKey, :modifiers,
                    :annotations, :test, :startLine, :endLine)""";

    private static final String INSERT_CHUNK = """
            INSERT INTO code_chunk (file_blob_id, symbol_id, start_line, end_line, header, content, search_tsv,
                                    token_estimate, chunker_version)
            VALUES (:blobId, :symbolId, :startLine, :endLine, :header, :content,
                    to_tsvector('simple', :searchText), :tokenEstimate, :chunkerVersion)""";

    /** A retried run re-maps the same paths; the latest mapping wins. */
    private static final String UPSERT_REVISION_FILE = """
            INSERT INTO revision_file (revision_id, path, file_blob_id) VALUES (:revisionId, :path, :blobId)
            ON CONFLICT (revision_id, path) DO UPDATE SET file_blob_id = EXCLUDED.file_blob_id""";

    private static final String REVISION_TOTALS = """
            SELECT count(*) AS files,
                   count(*) FILTER (WHERE b.parse_status = 'PARSE_FAILED') AS parse_failures,
                   coalesce(sum(b.redaction_count), 0) AS redactions,
                   count(*) FILTER (WHERE b.line_count > 0
                                      AND NOT EXISTS (SELECT 1 FROM code_chunk c WHERE c.file_blob_id = b.id))
                       AS unchunked,
                   coalesce(sum((SELECT count(*) FROM code_symbol s WHERE s.file_blob_id = b.id)), 0) AS symbols,
                   coalesce(sum((SELECT count(*) FROM code_chunk c WHERE c.file_blob_id = b.id)), 0) AS chunks
              FROM revision_file rf
              JOIN file_blob b ON b.id = rf.file_blob_id
             WHERE rf.revision_id = :revisionId""";

    private static final String MARK_READY = """
            UPDATE repository_revision SET status = 'READY', ready_at = :now, stats = CAST(:stats AS jsonb)
             WHERE id = :id AND status = 'INDEXING'""";

    private static final String MARK_FAILED = """
            UPDATE repository_revision SET status = 'FAILED', stats = CAST(:stats AS jsonb)
             WHERE id = :id AND status = 'INDEXING'""";

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate batch;

    public IndexingQueries(JdbcClient jdbc, NamedParameterJdbcTemplate batch) {
        this.jdbc = jdbc;
        this.batch = batch;
    }

    /** Creates the revision as INDEXING unless it exists, restarts a FAILED one, and returns it. */
    public Revision startRevision(long projectId, String sha, String configHash, Instant now) {
        jdbc.sql(INSERT_REVISION)
                .param("projectId", projectId)
                .param("sha", sha)
                .param("configHash", configHash)
                .param("now", utc(now))
                .update();
        Revision found = jdbc.sql(FIND_REVISION)
                .param("projectId", projectId)
                .param("sha", sha)
                .param("configHash", configHash)
                .query((rs, row) -> new Revision(rs.getLong("id"), RevisionStatus.valueOf(rs.getString("status"))))
                .single();
        if (found.status() == RevisionStatus.FAILED) {
            jdbc.sql(RESTART_REVISION).param("id", found.id()).update();
            return new Revision(found.id(), RevisionStatus.INDEXING);
        }
        return found;
    }

    /** Returns the project's blobs with any of {@code hashes}, under every parser version. */
    public List<ExistingBlob> findBlobs(long projectId, Collection<String> hashes, String chunkerVersion) {
        if (hashes.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(FIND_BLOBS)
                .param("projectId", projectId)
                .param("hashes", List.copyOf(hashes))
                .param("chunkerVersion", chunkerVersion)
                .query((rs, row) -> new ExistingBlob(
                        rs.getLong("id"),
                        rs.getString("content_hash"),
                        rs.getString("parser_version"),
                        rs.getBoolean("stale")))
                .list();
    }

    public List<Long> allocateBlobIds(int count) {
        return allocate(ALLOCATE_BLOB_IDS, count);
    }

    public List<Long> allocateSymbolIds(int count) {
        return allocate(ALLOCATE_SYMBOL_IDS, count);
    }

    private List<Long> allocate(String sql, int count) {
        if (count == 0) {
            return List.of();
        }
        return jdbc.sql(sql)
                .param("count", count)
                .query((rs, row) -> rs.getLong(1))
                .list();
    }

    /** Inserts the blobs; returns, per blob, whether it was inserted (false: a concurrent run got there first). */
    public boolean[] insertBlobs(long projectId, List<NewBlob> blobs) {
        int[] counts = batch.batchUpdate(
                INSERT_BLOB,
                blobs.stream()
                        .map(blob -> new MapSqlParameterSource()
                                .addValue("id", blob.id())
                                .addValue("projectId", projectId)
                                .addValue("contentHash", blob.contentHash())
                                .addValue("parserVersion", blob.parserVersion())
                                .addValue("language", blob.language())
                                .addValue("sizeBytes", blob.sizeBytes())
                                .addValue("lineCount", blob.lineCount())
                                .addValue("content", blob.content())
                                .addValue("parseStatus", blob.parseStatus())
                                .addValue("redactionCount", blob.redactionCount()))
                        .toArray(SqlParameterSource[]::new));
        boolean[] inserted = new boolean[counts.length];
        for (int i = 0; i < counts.length; i++) {
            inserted[i] = counts[i] != 0; // 1, or SUCCESS_NO_INFO; 0 means the conflict clause skipped it
        }
        return inserted;
    }

    /** Removes the blobs' symbols and chunks, so they can be rebuilt. */
    public void deleteDerived(Collection<Long> blobIds) {
        if (blobIds.isEmpty()) {
            return;
        }
        jdbc.sql(DELETE_CHUNKS).param("blobIds", List.copyOf(blobIds)).update();
        jdbc.sql(DELETE_SYMBOLS).param("blobIds", List.copyOf(blobIds)).update();
    }

    public void insertSymbols(List<NewSymbol> symbols) {
        if (symbols.isEmpty()) {
            return;
        }
        batch.batchUpdate(
                INSERT_SYMBOL,
                symbols.stream().map(IndexingQueries::symbolParameters).toArray(SqlParameterSource[]::new));
    }

    private static SqlParameterSource symbolParameters(NewSymbol row) {
        ParsedSymbol symbol = row.symbol();
        return new MapSqlParameterSource()
                .addValue("id", row.id())
                .addValue("blobId", row.blobId())
                .addValue("parentId", row.parentId())
                .addValue("kind", symbol.kind().name())
                .addValue("name", symbol.name())
                .addValue("qualifiedName", symbol.qualifiedName())
                .addValue("signature", symbol.signature())
                .addValue("symbolKey", symbol.symbolKey())
                .addValue(
                        "modifiers",
                        new SqlArrayValue("text", symbol.modifiers().toArray()))
                .addValue(
                        "annotations",
                        new SqlArrayValue("text", symbol.annotations().toArray()))
                .addValue("test", symbol.test())
                .addValue("startLine", symbol.startLine())
                .addValue("endLine", symbol.endLine());
    }

    public void insertChunks(List<NewChunk> chunks, String chunkerVersion) {
        if (chunks.isEmpty()) {
            return;
        }
        batch.batchUpdate(
                INSERT_CHUNK,
                chunks.stream()
                        .map(row -> new MapSqlParameterSource()
                                .addValue("blobId", row.blobId())
                                .addValue("symbolId", row.symbolId())
                                .addValue("startLine", row.chunk().startLine())
                                .addValue("endLine", row.chunk().endLine())
                                .addValue("header", row.chunk().header())
                                .addValue("content", row.chunk().content())
                                .addValue("searchText", row.chunk().searchText())
                                .addValue("tokenEstimate", row.chunk().tokenEstimate())
                                .addValue("chunkerVersion", chunkerVersion))
                        .toArray(SqlParameterSource[]::new));
    }

    public void upsertRevisionFiles(long revisionId, List<RevisionFile> files) {
        if (files.isEmpty()) {
            return;
        }
        batch.batchUpdate(
                UPSERT_REVISION_FILE,
                files.stream()
                        .map(file -> new MapSqlParameterSource()
                                .addValue("revisionId", revisionId)
                                .addValue("path", file.path())
                                .addValue("blobId", file.blobId()))
                        .toArray(SqlParameterSource[]::new));
    }

    public RevisionTotals totals(long revisionId) {
        return jdbc.sql(REVISION_TOTALS)
                .param("revisionId", revisionId)
                .query((rs, row) -> new RevisionTotals(
                        rs.getLong("files"),
                        rs.getLong("parse_failures"),
                        rs.getLong("redactions"),
                        rs.getLong("unchunked"),
                        rs.getLong("symbols"),
                        rs.getLong("chunks")))
                .single();
    }

    /** Returns whether the revision moved from INDEXING to READY. */
    public boolean markReady(long revisionId, String statsJson, Instant now) {
        return jdbc.sql(MARK_READY)
                        .param("id", revisionId)
                        .param("stats", statsJson)
                        .param("now", utc(now))
                        .update()
                > 0;
    }

    public void markFailed(long revisionId, String statsJson) {
        jdbc.sql(MARK_FAILED).param("id", revisionId).param("stats", statsJson).update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
