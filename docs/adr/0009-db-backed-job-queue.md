# ADR-0009: Background jobs on a Postgres-backed queue; no broker
- Status: Accepted
- Date: 2026-11-06

## Context
Indexing and analysis take seconds to minutes and must survive restarts, be retry-safe and report progress. Single instance for v1.

## Options considered
| Option | For | Against |
|---|---|---|
| `@Async` only | Trivial | Lost on restart, no retries, no status |
| JobRunr | Dashboard, retries | Another library and schema; less to talk about |
| Redis/RabbitMQ/Kafka | Scales out | New infrastructure with no current need |
| **`job` table + worker polling with `FOR UPDATE SKIP LOCKED`** | Transactional with domain data, durable, multi-worker-safe, ~150 lines | You own retry/backoff/lease code |

## Decision
`job` table ([architecture §4.1](../architecture.md#41-tables-essential-columns)). The worker runs on a virtual-thread executor, polls every 1 s (configurable), claims one job with `SKIP LOCKED` and sets a lease (`locked_until`). A heartbeat extends the lease. An expired lease makes the job claimable again. Retries use exponential backoff up to `max_attempts`, and failures store `error_code` plus a sanitized detail.

**Idempotency:** a partial unique index on `(project_id, type, idempotency_key)` for non-failed jobs. Re-submitting returns the existing job (202 with the same ID). Handlers are written to be re-runnable: upserts keyed by content hash, and a revision stays `INDEXING` until a final validating step flips it to `READY`.

## Consequences
- \+ The "how do you make jobs idempotent and crash-safe?" interview answer is in your code.
- − Polling adds trivial load. `LISTEN/NOTIFY` can wake workers later if latency matters.

## Revisit when
More than one app instance, or job throughput over ~10/s. Then consider a broker.
