# ADR-0003: Content-addressed blobs; revisions are path→blob snapshots
- Status: Accepted
- Date: 2026-11-06

## Context
Change analysis needs **two** indexed revisions (base and head). The blueprint's schema (`source_file.revision_id → code_symbol → code_chunk(embedding)`) copies every row per revision. Its "skip unchanged files" goal then only saves parse time, while embedding (the expensive step) or row duplication grows with every revision.

## Options considered
| Option | For | Against |
|---|---|---|
| A. Per-revision copies (blueprint) | Simplest queries | Re-embedding or copying everything per revision; storage grows linearly; incremental indexing is fake |
| B. Content-addressed `file_blob` per project, with symbols/chunks/embeddings attached to the blob; `revision_file(revision, path, blob)` snapshot | Identical content is parsed and embedded once, ever; head indexing after a 5-file change embeds only those 5 files | One more join; symbol IDs are per blob, so cross-revision identity needs a separate `symbol_key` |
| C. Global (cross-project) blob dedup | Max reuse | Complicates deletion and data retention, and couples tenants. Not worth it. |

## Decision
**B.** Blobs are keyed by `(project_id, sha256(LF-normalized content), parser_version)`. Symbols and chunks belong to blobs, and embeddings belong to `(chunk, embedding_model)`. **Edges belong to revisions**, because resolution depends on the whole snapshot. They're recomputed per revision, which is CPU-only and cheap.

Cross-revision symbol identity uses `symbol_key = qualified_name + '(' + erased parameter types + ')'`.

## Consequences
- \+ "A small change doesn't trigger full re-embedding" is true by construction, and measurable (stats show reused vs new blobs).
- \+ Changing the embedding model = a new `embedding_model` row plus a backfill job. Old and new can coexist for an A/B eval.
- − Every read query has to join through `revision_file` for the requested revision. That's enforced by putting all reads in revision-scoped query methods ([coding-standards DB-7](../coding-standards.md#db)).
- − Orphaned blobs (no `revision_file` references) need a GC job, or are simply left alone until project deletion. v1: leave them; they're bounded by project lifetime.

## Revisit when
Never for v1. If cross-project sharing ever matters, reconsider C with reference counting.
