package com.devmind.indexing.internal.persistence;

import com.devmind.indexing.SymbolKind;
import com.devmind.indexing.internal.domain.RevisionStats;
import com.devmind.indexing.internal.persistence.IndexingRows.RevisionStatus;
import com.devmind.indexing.internal.persistence.RevisionReadRows.DirectoryEntry;
import com.devmind.indexing.internal.persistence.RevisionReadRows.FileContent;
import com.devmind.indexing.internal.persistence.RevisionReadRows.RevisionRow;
import com.devmind.indexing.internal.persistence.RevisionReadRows.SymbolDetail;
import com.devmind.indexing.internal.persistence.RevisionReadRows.SymbolSummary;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads of indexed revisions (M2 step 8). Every query of code data takes an explicit revision and joins
 * {@code revision_file} (DB-7). The caller owns the transaction.
 */
@Repository
public class RevisionQueries {

    private static final String REVISION_COLUMNS = "id, commit_sha, status, created_at, ready_at, stats::text AS stats";

    private static final String LIST_REVISIONS = "SELECT " + REVISION_COLUMNS + """
             FROM repository_revision
            WHERE project_id = :projectId
            ORDER BY created_at DESC, id DESC
            LIMIT :limit OFFSET :offset""";

    private static final String COUNT_REVISIONS =
            "SELECT count(*) FROM repository_revision WHERE project_id = :projectId";

    /** Every revision of a commit SHA or prefix, the most recently READY first. */
    private static final String MATCH_REVISIONS = "SELECT " + REVISION_COLUMNS + """
             FROM repository_revision
            WHERE project_id = :projectId AND starts_with(commit_sha::text, :prefix)
            ORDER BY ready_at DESC NULLS LAST, id DESC
            LIMIT 50""";

    /**
     * The direct children of a directory, from the paths under it: the first segment after the prefix is the child,
     * and it's a directory if more segments follow. Directories first, then by name.
     */
    private static final String LIST_DIRECTORY = """
            SELECT name, bool_or(is_directory) AS is_directory
              FROM (SELECT split_part(substr(path, :start), '/', 1) AS name,
                           strpos(substr(path, :start), '/') > 0 AS is_directory
                      FROM revision_file
                     WHERE revision_id = :revisionId AND starts_with(path, :prefix)) children
             GROUP BY name
             ORDER BY bool_or(is_directory) DESC, lower(name), name
             LIMIT :limit""";

    private static final String FILE_CONTENT = """
            SELECT b.language, b.line_count, b.content
              FROM revision_file rf
              JOIN file_blob b ON b.id = rf.file_blob_id
             WHERE rf.revision_id = :revisionId AND rf.path = :path""";

    /**
     * Symbols whose name or qualified name is {@code q}, then names similar to it by trigram (pg_trgm's {@code %},
     * which uses the threshold {@link #useSimilarityThreshold} sets). Exact matches first, then by similarity;
     * ties put main code before test code and types before members, so a class outranks a test field named after it.
     */
    private static final String SEARCH_WHERE = """
              FROM revision_file rf
              JOIN code_symbol s ON s.file_blob_id = rf.file_blob_id
             WHERE rf.revision_id = :revisionId
               AND (s.name = :q OR s.qualified_name = :q OR s.name % :q)
               AND (CAST(:kind AS text) IS NULL OR s.kind = :kind)""";

    private static final String SEARCH_SYMBOLS = """
            SELECT s.public_id, s.kind, s.name, s.qualified_name, s.signature, rf.path, s.start_line, s.end_line
            """ + SEARCH_WHERE + """

             ORDER BY (s.name = :q OR s.qualified_name = :q) DESC, similarity(s.name, :q) DESC,
                      s.is_test, s.kind NOT IN ('CLASS', 'INTERFACE', 'ENUM', 'RECORD', 'ANNOTATION'),
                      s.qualified_name, rf.path, s.start_line
             LIMIT :limit OFFSET :offset""";

    private static final String COUNT_SYMBOLS = "SELECT count(*)" + SEARCH_WHERE;

    private static final String SET_SIMILARITY_THRESHOLD =
            "SELECT set_config('pg_trgm.similarity_threshold', :threshold, true)";

    /** A symbol of the revision, at the first of its paths (a blob can sit at several). */
    private static final String SYMBOL_DETAIL = """
            SELECT s.id, s.public_id, s.kind, s.name, s.qualified_name, s.signature, s.start_line, s.end_line,
                   s.modifiers, s.annotations, s.is_test, p.public_id AS parent_public_id,
                   (SELECT min(rf.path) FROM revision_file rf
                     WHERE rf.revision_id = :revisionId AND rf.file_blob_id = s.file_blob_id) AS path
              FROM code_symbol s
              LEFT JOIN code_symbol p ON p.id = s.parent_symbol_id
             WHERE s.public_id = :publicId
               AND EXISTS (SELECT 1 FROM revision_file rf
                            WHERE rf.revision_id = :revisionId AND rf.file_blob_id = s.file_blob_id)""";

    private static final String SYMBOL_CHILDREN = """
            SELECT c.public_id, c.kind, c.name, c.qualified_name, c.signature, :path AS path, c.start_line, c.end_line
              FROM code_symbol c
             WHERE c.parent_symbol_id = :parentId
             ORDER BY c.start_line, c.id""";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public RevisionQueries(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<RevisionRow> listRevisions(long projectId, int limit, long offset) {
        return jdbc.sql(LIST_REVISIONS)
                .param("projectId", projectId)
                .param("limit", limit)
                .param("offset", offset)
                .query(this::toRevision)
                .list();
    }

    public long countRevisions(long projectId) {
        return jdbc.sql(COUNT_REVISIONS)
                .param("projectId", projectId)
                .query((rs, row) -> rs.getLong(1))
                .single();
    }

    /** Returns the revisions whose commit SHA starts with {@code prefix}, the most recently READY first. */
    public List<RevisionRow> matchRevisions(long projectId, String prefix) {
        return jdbc.sql(MATCH_REVISIONS)
                .param("projectId", projectId)
                .param("prefix", prefix)
                .query(this::toRevision)
                .list();
    }

    /**
     * Returns up to {@code limit} children of {@code directory}.
     *
     * @param directory {@code ""} for the root, otherwise a path without a trailing slash
     */
    public List<DirectoryEntry> listDirectory(long revisionId, String directory, int limit) {
        String prefix = directory.isEmpty() ? "" : directory + "/";
        return jdbc.sql(LIST_DIRECTORY)
                .param("revisionId", revisionId)
                .param("prefix", prefix)
                .param("start", prefix.length() + 1)
                .param("limit", limit)
                .query((rs, row) -> new DirectoryEntry(rs.getString("name"), rs.getBoolean("is_directory")))
                .list();
    }

    public Optional<FileContent> fileContent(long revisionId, String path) {
        return jdbc.sql(FILE_CONTENT)
                .param("revisionId", revisionId)
                .param("path", path)
                .query((rs, row) ->
                        new FileContent(rs.getString("language"), rs.getInt("line_count"), rs.getString("content")))
                .optional();
    }

    /** Sets pg_trgm's similarity threshold for the rest of the current transaction. */
    public void useSimilarityThreshold(double threshold) {
        jdbc.sql(SET_SIMILARITY_THRESHOLD)
                .param("threshold", Double.toString(threshold))
                .query((rs, row) -> rs.getString(1))
                .single();
    }

    public List<SymbolSummary> searchSymbols(
            long revisionId, String q, @Nullable SymbolKind kind, int limit, long offset) {
        return jdbc.sql(SEARCH_SYMBOLS)
                .param("revisionId", revisionId)
                .param("q", q)
                .param("kind", kindParameter(kind))
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, row) -> toSummary(rs, rs.getString("path")))
                .list();
    }

    public long countSymbols(long revisionId, String q, @Nullable SymbolKind kind) {
        return jdbc.sql(COUNT_SYMBOLS)
                .param("revisionId", revisionId)
                .param("q", q)
                .param("kind", kindParameter(kind))
                .query((rs, row) -> rs.getLong(1))
                .single();
    }

    public Optional<SymbolDetail> symbolDetail(long revisionId, UUID publicId) {
        return jdbc.sql(SYMBOL_DETAIL)
                .param("revisionId", revisionId)
                .param("publicId", publicId)
                .query((rs, row) -> new SymbolDetail(
                        rs.getLong("id"),
                        toSummary(rs, rs.getString("path")),
                        strings(rs.getArray("modifiers")),
                        strings(rs.getArray("annotations")),
                        rs.getBoolean("is_test"),
                        rs.getObject("parent_public_id", UUID.class)))
                .optional();
    }

    /** The symbol's direct members, at the parent's path. */
    public List<SymbolSummary> symbolChildren(long parentId, String path) {
        return jdbc.sql(SYMBOL_CHILDREN)
                .param("parentId", parentId)
                .param("path", path)
                .query((rs, row) -> toSummary(rs, rs.getString("path")))
                .list();
    }

    /** A typed null, so Postgres can tell the parameter's type when no kind is given. */
    private static SqlParameterValue kindParameter(@Nullable SymbolKind kind) {
        return new SqlParameterValue(Types.VARCHAR, kind == null ? null : kind.name());
    }

    private RevisionRow toRevision(ResultSet rs, int row) throws SQLException {
        OffsetDateTime readyAt = rs.getObject("ready_at", OffsetDateTime.class);
        return new RevisionRow(
                rs.getLong("id"),
                rs.getString("commit_sha"),
                RevisionStatus.valueOf(rs.getString("status")),
                Objects.requireNonNull(rs.getObject("created_at", OffsetDateTime.class))
                        .toInstant(),
                readyAt == null ? null : readyAt.toInstant(),
                stats(rs.getString("stats")));
    }

    /** {@code {}} until a run finishes; then the {@link RevisionStats} it wrote. */
    private @Nullable RevisionStats stats(String stored) {
        return json.readTree(stored).isEmpty() ? null : json.readValue(stored, RevisionStats.class);
    }

    private static SymbolSummary toSummary(ResultSet rs, String path) throws SQLException {
        return new SymbolSummary(
                Objects.requireNonNull(rs.getObject("public_id", UUID.class)),
                SymbolKind.valueOf(rs.getString("kind")),
                rs.getString("name"),
                rs.getString("qualified_name"),
                rs.getString("signature"),
                path,
                rs.getInt("start_line"),
                rs.getInt("end_line"));
    }

    private static List<String> strings(@Nullable Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        try {
            return List.of((String[]) array.getArray());
        } finally {
            array.free();
        }
    }
}
