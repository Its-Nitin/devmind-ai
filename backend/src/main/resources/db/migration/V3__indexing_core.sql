-- M2 step 1: structural index (architecture §4.1, ADR-0003 content-addressed storage).
-- Blobs, symbols and chunks are content-addressed per project and reused across revisions. A revision only maps
-- paths to blobs (revision_file), and every code read joins it for an explicit revision (DB-7).
-- Symbols and chunks reach project_id through file_blob; deleting a project cascades through all of them (DB-2).
-- Secret redaction happens before the blob content is persisted, so the redaction count lives on file_blob and
-- chunks (cut from the redacted content) carry none of their own.
-- Embeddings (M3) and symbol edges (M5) get their own tables later.

CREATE TABLE repository_revision (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id        bigint      NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    commit_sha        char(40)    NOT NULL CHECK (commit_sha ~ '^[0-9a-f]{40}$'),
    -- Covers parser version, chunker version, filter rules and embedding model (architecture §5).
    index_config_hash char(64)    NOT NULL CHECK (index_config_hash ~ '^[0-9a-f]{64}$'),
    status            text        NOT NULL CHECK (status IN ('INDEXING', 'READY', 'FAILED')),
    stats             jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at        timestamptz NOT NULL,
    ready_at          timestamptz,
    -- Idempotency key of an index run; also serves as the project_id FK index (leading column).
    CONSTRAINT revision_uk UNIQUE (project_id, commit_sha, index_config_hash)
);

CREATE TABLE file_blob (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id      bigint   NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    -- SHA-256 of the normalized (UTF-8, LF) content before redaction.
    content_hash    char(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    parser_version  text     NOT NULL,
    language        text     NOT NULL,
    size_bytes      int      NOT NULL CHECK (size_bytes >= 0),
    line_count      int      NOT NULL CHECK (line_count >= 0),
    content         text     NOT NULL,
    parse_status    text     NOT NULL CHECK (parse_status IN ('OK', 'PARSE_FAILED', 'SKIPPED')),
    redaction_count int      NOT NULL DEFAULT 0 CHECK (redaction_count >= 0),
    -- Also serves as the project_id FK index (leading column).
    CONSTRAINT file_blob_uk UNIQUE (project_id, content_hash, parser_version)
);

CREATE TABLE revision_file (
    revision_id  bigint NOT NULL REFERENCES repository_revision (id) ON DELETE CASCADE,
    -- Repository-relative, '/'-separated.
    path         text   NOT NULL CHECK (char_length(path) BETWEEN 1 AND 1000),
    file_blob_id bigint NOT NULL REFERENCES file_blob (id) ON DELETE CASCADE,
    PRIMARY KEY (revision_id, path)
);
CREATE INDEX revision_file_blob_idx ON revision_file (file_blob_id);

CREATE TABLE code_symbol (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    file_blob_id     bigint  NOT NULL REFERENCES file_blob (id) ON DELETE CASCADE,
    parent_symbol_id bigint  REFERENCES code_symbol (id) ON DELETE CASCADE,
    kind             text    NOT NULL CHECK (kind IN ('CLASS', 'INTERFACE', 'ENUM', 'RECORD', 'ANNOTATION', 'METHOD',
                                                      'CONSTRUCTOR', 'FIELD')),
    name             text    NOT NULL,
    qualified_name   text    NOT NULL,
    signature        text,
    -- qualified_name + erased parameter types; stable across revisions, used by change analysis (M5).
    symbol_key       text    NOT NULL,
    modifiers        text[]  NOT NULL DEFAULT '{}',
    -- Full annotation source text, e.g. @GetMapping("/{id}").
    annotations      text[]  NOT NULL DEFAULT '{}',
    is_test          boolean NOT NULL DEFAULT false,
    -- 1-based, inclusive, on LF-normalized content (architecture §4.2).
    start_line       int     NOT NULL CHECK (start_line >= 1),
    end_line         int     NOT NULL CHECK (end_line >= start_line)
);
CREATE INDEX code_symbol_blob_idx   ON code_symbol (file_blob_id);
CREATE INDEX code_symbol_parent_idx ON code_symbol (parent_symbol_id);
CREATE INDEX code_symbol_key_idx    ON code_symbol (symbol_key);
CREATE INDEX code_symbol_name_trgm  ON code_symbol USING gin (name gin_trgm_ops);
CREATE INDEX code_symbol_qname_trgm ON code_symbol USING gin (qualified_name gin_trgm_ops);

CREATE TABLE code_chunk (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    file_blob_id    bigint   NOT NULL REFERENCES file_blob (id) ON DELETE CASCADE,
    -- Null for fixed-window chunks of non-Java or PARSE_FAILED files.
    symbol_id       bigint   REFERENCES code_symbol (id) ON DELETE CASCADE,
    start_line      int      NOT NULL CHECK (start_line >= 1),
    end_line        int      NOT NULL CHECK (end_line >= start_line),
    header          text     NOT NULL,
    content         text     NOT NULL,
    -- Built at index time from header, content and Java-side identifier splitting (architecture §7.2).
    search_tsv      tsvector NOT NULL,
    token_estimate  int      NOT NULL CHECK (token_estimate >= 0),
    -- Chunks of a blob are re-created when the chunker version changes (it is part of index_config_hash).
    chunker_version text     NOT NULL
);
CREATE INDEX code_chunk_blob_idx   ON code_chunk (file_blob_id);
CREATE INDEX code_chunk_symbol_idx ON code_chunk (symbol_id);
CREATE INDEX code_chunk_tsv_idx    ON code_chunk USING gin (search_tsv);
