-- M3 step 2: chunk embeddings (architecture §4.1, ADR-0003, ADR-0006).
-- Embeddings belong to (chunk, embedding model), so they're reused by every revision that maps the chunk's blob,
-- and a new model (or the same name with another digest) gets its own rows next to the old ones.
-- They reach project_id through code_chunk -> file_blob; deleting a project cascades to them (DB-2).

CREATE TABLE embedding_model (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider     text        NOT NULL CHECK (char_length(provider) BETWEEN 1 AND 50),
    model_name   text        NOT NULL CHECK (char_length(model_name) BETWEEN 1 AND 200),
    dimensions   int         NOT NULL CHECK (dimensions > 0),
    -- As the provider reports it; a re-pulled model under the same name is a new row (threat model T17).
    model_digest text        NOT NULL CHECK (char_length(model_digest) BETWEEN 1 AND 200),
    created_at   timestamptz NOT NULL,
    CONSTRAINT embedding_model_uk UNIQUE (provider, model_name, model_digest)
);

CREATE TABLE chunk_embedding (
    chunk_id           bigint       NOT NULL REFERENCES code_chunk (id) ON DELETE CASCADE,
    embedding_model_id bigint       NOT NULL REFERENCES embedding_model (id) ON DELETE CASCADE,
    -- DB-10: the dimension is fixed by the M0 decision (mxbai-embed-large); another size needs a new migration.
    embedding          vector(1024) NOT NULL,
    -- Also serves as the chunk_id FK index (leading column).
    PRIMARY KEY (chunk_id, embedding_model_id)
);
CREATE INDEX chunk_embedding_model_idx ON chunk_embedding (embedding_model_id);
CREATE INDEX chunk_embedding_hnsw ON chunk_embedding USING hnsw (embedding vector_cosine_ops);
