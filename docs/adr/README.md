# Architecture Decision Records

Format: a lightweight MADR. One decision per file, numbered, never deleted. To change a decision, write a new ADR that supersedes the old one, and set the old one's status to `Superseded by ADR-XXXX`.

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-modular-monolith-spring-modulith.md) | Modular monolith, package-by-module, verified with Spring Modulith | Accepted |
| [0002](0002-postgres-pgvector-single-store.md) | PostgreSQL + pgvector + pg_trgm as the only datastore | Accepted |
| [0003](0003-content-addressed-storage.md) | Content-addressed blobs; revisions are path→blob snapshots | Accepted |
| [0004](0004-java-parsing-javaparser.md) | JavaParser + SymbolSolver; edges carry provenance | Accepted |
| [0005](0005-persistence-jpa-plus-jdbcclient.md) | JPA for aggregates, JdbcClient for search/bulk/graph | Accepted |
| [0006](0006-ai-provider-port.md) | Own AI ports; Spring AI inside adapters; local embeddings, configurable chat | Accepted |
| [0007](0007-hybrid-retrieval-rrf.md) | Hybrid retrieval (identifier-aware FTS + vector + symbol) fused with RRF | Accepted |
| [0008](0008-citation-by-evidence-id.md) | Model cites evidence IDs; server resolves and validates | Accepted |
| [0009](0009-db-backed-job-queue.md) | Background jobs on a Postgres-backed queue; no broker | Accepted |
| [0010](0010-session-auth-same-origin.md) | Session auth behind a same-origin proxy; no JWT | Accepted |
| [0011](0011-evaluation-as-code.md) | Evaluation as code; mutation-derived test ground truth | Accepted |
| [0012](0012-mcp-and-bounded-agent.md) | Agent capability = read-only MCP server + bounded loop (stretch) | Accepted (stretch) |
| [0013](0013-toolchain-and-build.md) | Java 25, Maven wrapper, single backend module, monorepo | Accepted |
| [0014](0014-frontend-stack.md) | Angular (standalone, signals, zoneless), PrimeNG, CodeMirror 6, Cytoscape.js | Accepted |

Template:

```markdown
# ADR-XXXX: <title>
- Status: Proposed | Accepted | Superseded by ADR-YYYY
- Date: YYYY-MM-DD
## Context
## Options considered
## Decision
## Consequences (positive / negative)
## Revisit when
```
