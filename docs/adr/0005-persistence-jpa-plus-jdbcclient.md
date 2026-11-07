# ADR-0005: JPA for aggregates, JdbcClient for search, bulk and graph
- Status: Accepted
- Date: 2026-11-06

## Context
There are two very different workloads. (1) CRUD on small aggregates: users, projects, jobs, conversations, analyses. (2) Bulk ingestion (thousands of rows per index run) plus hand-tuned queries: FTS, vector KNN, recursive CTEs, RRF.

## Options considered
| Option | For | Against |
|---|---|---|
| A. JPA/Hibernate everywhere | Familiar, one style | Vector, FTS and CTE end up as native queries anyway; bulk inserts through the persistence context are slow and memory-heavy; lazy-loading surprises |
| B. jOOQ everywhere | Type-safe SQL, excellent for complex queries | Code generation setup, a new tool to learn under time pressure, more boilerplate for simple CRUD |
| C. Spring Data JDBC + JdbcClient | Simple, no lazy loading, explicit | Less familiar aggregate mapping rules; still hand-written SQL for the hard parts |
| D. **JPA for workload 1, `JdbcClient` (+ batch updates) for workload 2** | Uses each tool where it's strongest; your existing JPA experience stays useful | Two styles to keep coherent |

## Decision
**D**, with hard rules ([coding-standards DB-*](../coding-standards.md#db)):
- JPA entities live only in `<module>.internal.persistence` and never cross a module boundary or reach a controller.
- `spring.jpa.open-in-view=false`, `ddl-auto=validate`. Flyway owns the schema.
- Indexing writes use `JdbcClient` / `JdbcTemplate.batchUpdate` (or `COPY` if profiling demands it) inside one transaction per batch.
- Retrieval and graph SQL live in dedicated `*Queries` classes with SQL in text blocks, and each query has an integration test against Testcontainers Postgres with pgvector.

## Consequences
- \+ The hot paths are explicit SQL you can `EXPLAIN ANALYZE` in an interview.
- − Two styles. The boundary is clear by workload, and enforced by package placement.

## Revisit when
Complex query count grows to the point where compile-time SQL safety pays off. Consider jOOQ for those modules only.
