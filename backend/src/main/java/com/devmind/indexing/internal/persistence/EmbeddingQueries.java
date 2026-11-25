package com.devmind.indexing.internal.persistence;

import com.devmind.ai.EmbeddingModelRef;
import com.devmind.indexing.internal.persistence.IndexingRows.ChunkText;
import com.pgvector.PGvector;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Statements on embeddings (V5 schema): the model registry and the chunk vectors of a revision. Reads are
 * revision-scoped through {@code revision_file} (DB-7); vectors are always stored with their model (DB-10). The caller
 * owns the transaction (SPRING-2).
 */
@Repository
public class EmbeddingQueries {

    private static final String INSERT_MODEL = """
            INSERT INTO embedding_model (provider, model_name, dimensions, model_digest, created_at)
            VALUES (:provider, :name, :dimensions, :digest, :now)
            ON CONFLICT (provider, model_name, model_digest) DO NOTHING""";

    private static final String FIND_MODEL = """
            SELECT id, dimensions FROM embedding_model
             WHERE provider = :provider AND model_name = :name AND model_digest = :digest""";

    /** The revision's chunks without a vector for the model, after {@code :after} in ID order (keyset paging). */
    private static final String UNEMBEDDED = """
            SELECT c.id, c.header, c.content
              FROM code_chunk c
             WHERE c.file_blob_id IN (SELECT rf.file_blob_id FROM revision_file rf WHERE rf.revision_id = :revisionId)
               AND c.id > :after
               AND NOT EXISTS (SELECT 1 FROM chunk_embedding e
                                WHERE e.chunk_id = c.id AND e.embedding_model_id = :modelId)
             ORDER BY c.id
             LIMIT :limit""";

    private static final String COUNT_UNEMBEDDED = """
            SELECT count(*)
              FROM code_chunk c
             WHERE c.file_blob_id IN (SELECT rf.file_blob_id FROM revision_file rf WHERE rf.revision_id = :revisionId)
               AND NOT EXISTS (SELECT 1 FROM chunk_embedding e
                                WHERE e.chunk_id = c.id AND e.embedding_model_id = :modelId)""";

    /**
     * A concurrent run of another revision may embed the same chunk first; it then counts 0 here. A chunk rebuilt by a
     * newer chunker meanwhile is gone, and its insert is skipped instead of failing the batch.
     */
    private static final String INSERT_EMBEDDING = """
            INSERT INTO chunk_embedding (chunk_id, embedding_model_id, embedding)
            SELECT c.id, :modelId, :embedding FROM code_chunk c WHERE c.id = :chunkId
            ON CONFLICT (chunk_id, embedding_model_id) DO NOTHING""";

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate batch;

    public EmbeddingQueries(JdbcClient jdbc, NamedParameterJdbcTemplate batch) {
        this.jdbc = jdbc;
        this.batch = batch;
    }

    /**
     * Registers the model unless it exists and returns its ID.
     *
     * @throws IllegalStateException if a stored row of the same identity has another dimension count
     */
    public long registerModel(EmbeddingModelRef model, Instant now) {
        jdbc.sql(INSERT_MODEL)
                .param("provider", model.provider())
                .param("name", model.name())
                .param("dimensions", model.dimensions())
                .param("digest", model.digest())
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update();
        StoredModel stored = jdbc.sql(FIND_MODEL)
                .param("provider", model.provider())
                .param("name", model.name())
                .param("digest", model.digest())
                .query((rs, row) -> new StoredModel(rs.getLong("id"), rs.getInt("dimensions")))
                .single();
        if (stored.dimensions() != model.dimensions()) {
            throw new IllegalStateException("Embedding model " + model.name() + " is registered with "
                    + stored.dimensions() + " dimensions, not " + model.dimensions());
        }
        return stored.id();
    }

    private record StoredModel(long id, int dimensions) {}

    /** Returns up to {@code limit} chunks of the revision with no vector for the model and an ID above {@code after}. */
    public List<ChunkText> unembedded(long revisionId, long modelId, long after, int limit) {
        return jdbc.sql(UNEMBEDDED)
                .param("revisionId", revisionId)
                .param("modelId", modelId)
                .param("after", after)
                .param("limit", limit)
                .query((rs, row) -> new ChunkText(rs.getLong("id"), rs.getString("header"), rs.getString("content")))
                .list();
    }

    public long countUnembedded(long revisionId, long modelId) {
        return jdbc.sql(COUNT_UNEMBEDDED)
                .param("revisionId", revisionId)
                .param("modelId", modelId)
                .query(Long.class)
                .single();
    }

    /**
     * Inserts one vector per chunk, {@code vectors.get(i)} for {@code chunkIds.get(i)}, in one JDBC batch (DB-8).
     *
     * @return how many were inserted
     */
    public int insert(long modelId, List<Long> chunkIds, List<float[]> vectors) {
        if (chunkIds.size() != vectors.size()) {
            throw new IllegalArgumentException(chunkIds.size() + " chunks but " + vectors.size() + " vectors");
        }
        if (chunkIds.isEmpty()) {
            return 0;
        }
        int[] counts = batch.batchUpdate(
                INSERT_EMBEDDING,
                IntStream.range(0, chunkIds.size())
                        .mapToObj(i -> new MapSqlParameterSource()
                                .addValue("modelId", modelId)
                                .addValue("chunkId", chunkIds.get(i))
                                .addValue("embedding", new PGvector(vectors.get(i))))
                        .toArray(SqlParameterSource[]::new));
        // 1, or SUCCESS_NO_INFO; 0 means the chunk was embedded meanwhile or is gone
        return (int) IntStream.of(counts).filter(count -> count != 0).count();
    }
}
