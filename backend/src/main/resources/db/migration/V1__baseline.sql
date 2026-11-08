-- M1 baseline: users, projects and the job table (worker arrives in M2).
-- Conventions (architecture §4, DB-2/3): bigint identity PKs internally, UUID public_id on user-facing
-- aggregates (API-5), timestamptz everywhere (JAVA-9), CHECK constraints for enums and lengths.

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE app_user (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id     uuid        NOT NULL UNIQUE,
    email         citext      NOT NULL UNIQUE CHECK (char_length(email) <= 254),
    display_name  text        NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 100),
    password_hash text        NOT NULL,
    created_at    timestamptz NOT NULL
);

CREATE TABLE project (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id       uuid        NOT NULL UNIQUE,
    owner_id        bigint      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    name            text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 100),
    source_type     text        NOT NULL CHECK (source_type IN ('LOCAL', 'GIT_PUBLIC')),
    source_location text        NOT NULL CHECK (char_length(source_location) <= 500),
    default_branch  text,
    created_at      timestamptz NOT NULL,
    -- Also serves as the owner_id FK index (leading column).
    CONSTRAINT project_owner_name_uk UNIQUE (owner_id, name)
);

CREATE TABLE job (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id       uuid        NOT NULL UNIQUE,
    project_id      bigint      NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    type            text        NOT NULL CHECK (type IN ('INDEX', 'ANALYSIS', 'EVAL')),
    idempotency_key text        NOT NULL,
    status          text        NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    payload         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    attempts        int         NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts    int         NOT NULL DEFAULT 3 CHECK (max_attempts >= 1),
    run_after       timestamptz NOT NULL,
    locked_by       text,
    locked_until    timestamptz,
    progress        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    error_code      text,
    error_detail    text,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL
);

-- One live job per (project, type, key); a FAILED job may be retried under the same key (ADR-0009).
CREATE UNIQUE INDEX job_idempotency_uk ON job (project_id, type, idempotency_key) WHERE status <> 'FAILED';
-- The idempotency index is partial, so it can't serve the FK (cascade deletes, per-project listing).
CREATE INDEX job_project_idx ON job (project_id);
CREATE INDEX job_claim_idx ON job (status, run_after);
