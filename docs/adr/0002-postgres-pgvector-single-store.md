# ADR-0002: PostgreSQL + pgvector + pg_trgm as the only datastore
- Status: Accepted
- Date: 2026-11-06

## Context
The system needs relational metadata, full-text search, fuzzy name search, vector similarity search and bounded graph traversal. Target scale: a few repos of up to ~5k files, i.e. up to ~100k chunks.

## Options considered
| Option | For | Against |
|---|---|---|
| A. Postgres + pgvector + pg_trgm (FTS built in, recursive CTEs for graph) | One system, transactional consistency between metadata and vectors, joins for revision scoping, Testcontainers-friendly | HNSW build is memory-hungry at very large scale; graph queries are less ergonomic than Cypher |
| B. Postgres + dedicated vector DB (Qdrant/Weaviate) | Better vector features at scale, filtering | Two sources of truth, sync and consistency bugs, revision scoping becomes a cross-system filter |
| C. Postgres + Neo4j for the graph | Elegant traversal queries | Third system to operate; depth-3 bounded traversal is trivial in SQL |
| D. Elasticsearch/OpenSearch for lexical + vector | Strong BM25 | Heavy to run on a laptop alongside Ollama; duplicates data |

## Decision
**A.** Postgres 17+ (pinned `pgvector/pgvector` image tag) with `vector`, `pg_trgm` and `citext`. Vector index: HNSW, cosine. Graph: recursive CTE with `CYCLE`, depth and fan-out limits.

## Consequences
- \+ Revision scoping is a join, so a "stale citation" can't happen ([ADR-0003](0003-content-addressed-storage.md)).
- \+ One backup, one migration tool, one Testcontainers image.
- − Postgres FTS ranking (`ts_rank`) is weaker than BM25. That's acceptable because fusion ([ADR-0007](0007-hybrid-retrieval-rrf.md)) only uses ranks, not scores.
- − HNSW and filters: post-filtering by revision can starve results. Mitigation: oversample (`ef_search`, LIMIT × 4) and filter via a join on `revision_file`. If recall suffers, use pgvector iterative index scans (verify availability in the pinned version).

## Revisit when
Chunk counts exceed ~1M, or vector p95 latency goes above 300 ms on the benchmark machine.
