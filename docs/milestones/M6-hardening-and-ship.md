# M6 — Hardening & Ship: Step-by-step Plan

| | |
|---|---|
| Milestone | M6 (see [PLAN.md §5](../PLAN.md#5-milestones)) |
| Budget | 12–16 hours |
| Prerequisite | `m5` tagged |
| Goal | A stranger can clone the repo, run it, reproduce the benchmark, inspect evidence and understand where it fails. Every claim in the README is backed by a committed report. |
| Definition of portfolio-ready | [PLAN.md §9](../PLAN.md#9-final-acceptance-checklist): all items checked |

---

## Progress tracker

| # | Step | PR | Time | Done |
|---|---|---|---|---|
| 1 | Authorization matrix: complete and strict | `test/authz-matrix-complete` | 1 h | ☐ |
| 2 | Limits and rate limiting | `feat/limits-rate-limiting` | 2 h | ☐ |
| 3 | Failure modes: timeouts, outages, partial failures | `feat/resilience` | 2 h | ☐ |
| 4 | Project deletion cascade + data retention | `feat/project-deletion` | 1 h | ☐ |
| 5 | Observability: metrics, cost summary, evaluation page | `feat/observability-eval-page` | 2 h | ☐ |
| 6 | Security pass: threat model v1 + checks | `docs/threat-model-v1` | 1–2 h | ☐ |
| 7 | Fresh-clone reproducibility test | `docs/setup` | 1 h | ☐ |
| 8 | Documentation: README, evaluation.md, architecture refresh | `docs/readme-evaluation` | 2–3 h | ☐ |
| 9 | Demo: script, recording, GIF | `docs/demo` | 1–2 h | ☐ |
| 10 | Optional: read-only hosted demo (needs a budget; skipped under local-only) | `chore/demo-deploy` | 2 h | ☐ |
| 11 | Final acceptance + close out | — | 30 min | ☐ |

---

## Step 1 — Authorization matrix: complete and strict (1 h)

1. Confirm `AuthorizationMatrixTest` enumerates **every** handler mapping, and that each one is classified (TEST-9).
2. For every `OWNER_SCOPED` endpoint, assert:
   - owner → success;
   - another authenticated user → **404** with a ProblemDetail whose body doesn't reveal existence: no name, no ID echo beyond the request (API-8);
   - anonymous → 401.
3. **Indirect IDs:** check that `jobId`, `analysisId` and `conversationId` lookups enforce the owner of the **resolved project** (SEC-1). Add a test where user B guesses user A's job UUID.
4. **Cross-project mixing:** request `/projects/{A}/revisions/{sha-of-B's-revision}` → 404 / 409, never data from B.
5. Add CSRF checks for every mutating endpoint: missing header → 403.

**Done when:** the matrix has no unclassified endpoints, and the indirect-ID tests pass.

## Step 2 — Limits and rate limiting (2 h)

1. **Input limits** (most exist; verify each has a test, API-2):

   | Input | Limit |
   |---|---|
   | Request body | 64 KB (`server.tomcat.max-swallow-size`, plus a filter rejecting larger `Content-Length` → 413) |
   | Question | 2,000 chars |
   | Ref | 200 chars, pattern `^[A-Za-z0-9._/-]+$` |
   | Page size | ≤ 100 |
   | File slice | ≤ 2,000 lines |
   | Traversal depth / fan-out / nodes | ≤ 3 / 50 / 500 (server-enforced even if the request asks for more) |
   | Retrieval top-k / budget | from config only, never from the request |
   | LLM max output tokens | from config only |
2. **Rate limiting** (SEC-8). Use a per-user token bucket with Bucket4j in-memory, or a small `ConcurrentHashMap<UserId, Bucket>` implementation:

   | Endpoint group | Default |
   |---|---|
   | `POST /chat` | 20 / 10 min |
   | `POST /analyses` | 10 / hour |
   | `POST /index-jobs` | 10 / hour |
   | `POST /retrieve` | 60 / 10 min |
   | Login | 10 / 10 min per IP + email (brute force) |

   Exceeding a limit returns 429 ProblemDetail + `Retry-After`. Limits are in config.
3. **Concurrency:** at most 1 running INDEX or ANALYSIS job per project, enforced at claim time (skip jobs whose project already has a RUNNING job of the same type), and a global `max-concurrent` from M2.
4. **Tests:** each limit → its 4xx; a rate limit → 429 + `Retry-After`; the bucket resets (fixed `Clock`).

**Done when:** every row has a test.

## Step 3 — Failure modes (2 h)

Write one integration test per failure mode, and make the behaviour match:

| Failure | Expected behaviour | Test approach |
|---|---|---|
| Chat model down or timeout | Chat → 503 `MODEL_UNAVAILABLE` with evidence; analysis completes with VERIFIED findings + warning | FakeChatModel throws / sleeps past timeout |
| Embedding model down during indexing | Job retries with backoff, then FAILED `EMBEDDING_UNAVAILABLE`; revision stays INDEXING (not READY) | fake embedding throws |
| DB unavailable | `/actuator/health` DOWN; API returns 503 ProblemDetail, not a stack trace | stop the Testcontainer mid-test (or use a separate test with a bad URL) |
| Clone fails (DNS, 404, timeout) | Job FAILED `SOURCE_UNAVAILABLE` with a sanitized detail | local `file://` path that doesn't exist (test flag) |
| One file fails to parse | Revision READY with `parseFailures ≥ 1`; UI shows a warning badge | fixture with a syntax error (exists from M2) |
| Edge resolution timeout in a file | Falls back to a name match, counted in stats | test hook forcing a timeout |
| Worker crash mid-job | Lease expires → job reclaimed → completes | M2 test (verify it still passes) |
| Request timeout on a slow chat | Server-side timeout returns 503 before the proxy times out | configure the model timeout below the proxy timeout; document both values |

Also:
- Caddy `reverse_proxy` timeouts should be longer than the backend's chat timeout. Document both values in `deploy/Caddyfile` comments.
- Frontend: every 503/429 shows a specific message ("Model unavailable, showing retrieved evidence" / "Rate limit, retry in N s").

**Done when:** all eight tests pass.

## Step 4 — Project deletion cascade (1 h)

1. `DELETE /api/projects/{projectId}`. It's synchronous for small projects; for large ones, use a job. Start synchronous with a statement timeout and test on PetClinic size.
2. **`ProjectDeletionTest`:** index a fixture project → ask a question → run an analysis → delete → assert **zero rows** referencing that project in every project-owned table: `repository_revision`, `file_blob`, `revision_file`, `code_symbol`, `code_chunk`, `chunk_embedding`, `symbol_edge`, `job`, `conversation`, `chat_message`, `analysis_run`, `changed_symbol`, `impacted_symbol`, `risk_finding`, `llm_call`.
   - Write it as a generic test: query `information_schema` for tables with a `project_id` column, plus a fixed list of child tables, so new tables are covered automatically (DB-2).
3. **Temp files:** assert no `devmind-clone-*` or edge-resolution temp dirs remain.
4. **`evaluation_run`** rows aren't project-owned; they reference dataset files. Document this.
5. State the retention policy in the README: "Deleting a project removes all derived data. Model provider retention is governed by the provider's policy."

**Done when:** the deletion test is green.

## Step 5 — Observability, cost summary, evaluation page (2 h)

1. **Metrics check:** run one index, one chat and one analysis, then confirm every metric in [architecture §12](../architecture.md#12-observability) appears at `/actuator/metrics`, with low-cardinality tags only (OBS-4).
2. **Cost summary endpoint:** `GET /api/projects/{projectId}/usage?from=&to=` → per purpose: calls, input and output tokens, p50/p95 latency, estimated cost (pricing from config). Built from `llm_call`.
3. **Evaluation page** (`features/evaluation`, backed by `GET /api/projects/{projectId}/evaluations/latest`):
   - the latest report per mode (retrieval, qa, impact) with headline metrics, dataset version and config name;
   - a link to the committed Markdown report on GitHub (store the repo URL in config);
   - read-only.
4. **Log review:** grep the logs from a full demo run for code fragments, prompt phrases, `password`, `token` and session IDs. The result must be clean (OBS-3, AI-13). Make it a test: capture logs with a Logback `ListAppender` during an integration run and assert no evidence content appears.

**Done when:** the usage endpoint and evaluation page work, and the log-content test passes.

## Step 6 — Security pass: threat model v1 (1–2 h)

1. Update `docs/threat-model.md` to **v1**. For each threat, fill in "Verified by" with the actual test class names. Add any threats discovered during M1–M5.
2. **Checks:**
   - `./mvnw verify` with dependency scanning (Dependabot alerts or OWASP dependency-check) → no unaddressed criticals (SEC-10);
   - `npm audit --omit=dev` → clean, or documented exceptions;
   - gitleaks over the full history → clean;
   - Caddy security headers present (`curl -I`);
   - add a **Content-Security-Policy** suitable for the SPA (`default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'`). Adjust it for PrimeNG and CodeMirror, then test the UI.
   - Session cookie flags in `prod` (`curl -c`).
3. **Manual adversarial test:**
   - try path traversal in `sourceLocation`, `../` in the `file?path=` parameter, an oversized question, an injection question, and an XSS payload in a test repo file (`<img src=x onerror=alert(1)>`) rendered in the code viewer and the chat;
   - record the results in the threat model.

**Done when:** threat model v1 is committed with every row verified.

## Step 7 — Fresh-clone reproducibility (1 h)

On a clean directory (ideally a different user account, VM or Windows Sandbox):
1. Follow **only** the README setup steps. Time it; target < 15 min, excluding model downloads.
2. Note every missing step, wrong command or implicit assumption, and fix the README.
3. Run the documented eval command against the committed dataset with the response cache **off** for one small subset (5 questions). The numbers must be consistent with the committed report.
4. Run with the cache **on**. Committed reports must reproduce exactly, except timestamps and latency.

**Done when:** a fresh setup works from the README alone.

## Step 8 — Documentation (2–3 h)

### 8.1 `README.md` structure
1. **One-line pitch** plus a 15-second GIF (index → ask → click citation → impact report).
2. **The problem**, in 3 bullets.
3. **What it does**, in 4 bullets with screenshots: grounded Q&A, explorer, impact analysis, evaluation page.
4. **Results.** Every number is copied from a committed report and links to it (DOC-2):

   | Metric | Value | Report |
   |---|---|---|
   | Retrieval Recall@5 (hybrid vs vector-only) | x/N vs y/N | experiment-a.md |
   | Citation validity before / after validation | … | experiment-c.md |
   | Answer correctness (manual, mean of 0–2) | … | qa review |
   | Abstention precision / recall | … | qa report |
   | Test recommendation recall vs failing tests | … | impact report |
   | Median / p95 latency, cost per question | … | qa report |

5. **How it works:** the architecture diagram (Mermaid), the principle "deterministic code decides facts; the LLM interprets", and links to `architecture.md` and the ADRs.
6. **Design trade-offs:** 6–8 bullets, each linking an ADR (modular monolith, Postgres-only, evidence IDs, content addressing, hybrid RRF, mutation ground truth, hosted vs local models).
7. **Limitations:** from [architecture §14](../architecture.md#14-known-limitations-to-state-in-the-readme), updated with what you actually observed (resolution rate, generated sources, single-turn, Java-only).
8. **Run it:** prerequisites, `.env`, Ollama model pull, `docker compose up`, the first project, and the eval command.
9. **Project status and roadmap** (M7/M8 as future work if not done).

### 8.2 `docs/evaluation.md`
- method: datasets, categories, labelling procedure, the dev/holdout split, the matcher, metric definitions, mutation ground truth and its lower-bound caveat;
- results: all experiments (A, B, C) and the baselines;
- **failure analysis:** the 5 most instructive failures, with what caused each;
- threats to validity: small N, single primary repo, labeller = builder, model version drift.

### 8.3 `docs/architecture.md` refresh
Note what changed during implementation **as an addendum section** with dated entries, rather than rewriting history. Changed decisions get superseding ADRs (DOC-1).

### 8.4 `docs/devlog.md` cleanup
Keep it raw, but add a short "Highlights" section at the top with 5 lessons learned. This is your interview prep sheet.

**Done when:** a reader understands the project from the README alone in under 5 minutes.

## Step 9 — Demo (1–2 h)

1. **`docs/demo-script.md`**, a 3–4 minute storyline:
   1. Problem statement (15 s).
   2. Index PetClinic at a pinned SHA; show the stats with reused blobs (20 s).
   3. Ask a paraphrased question → answer with citations → click → exact lines (40 s).
   4. Ask an unanswerable question → insufficient evidence, with no hallucination (20 s).
   5. Injection question → resisted (20 s).
   6. Impact analysis on a prepared change → VERIFIED vs INFERRED findings → recommended tests with "why" paths → graph (60 s).
   7. Evaluation page plus one honest limitation (30 s).
2. **Prepare state:** pre-indexed revisions, the prepared change committed on a demo branch, the response cache warm for **every** question you'll ask (local chat takes ~4 min per uncached answer), browser zoom set, notifications off.
3. **Record:** OBS Studio or the Windows Game Bar at 1080p, with a voice-over or captions. Export the short GIF for the README (≤ 10 MB).
4. Upload the video (YouTube unlisted or similar) and link it in the README.

**Done when:** the video is recorded and linked.

## Step 10 — Optional: read-only hosted demo (2 h)

> **Not planned under the local-only decision ([ADR-0006](../adr/0006-ai-provider-port.md)).** This step needs a paid VPS and a hosted chat model. Keep it only for the case where a budget becomes available.

Only if you want a live link. Otherwise the video plus a one-command local run is enough ([PLAN.md M6](../PLAN.md#m6--hardening--ship-1216-h)).

1. **Host:** a small VPS (2–4 vCPU, 4–8 GB RAM) running the same compose stack. **No Ollama for chat:** use the hosted model. For query embeddings, either Ollama with the small embedding model on the VPS (CPU is fine for single queries) or the same hosted provider's embeddings. A provider swap requires re-embedding, so prefer Ollama on the VPS for consistency.
2. **Demo profile:** `devmind.demo.read-only=true`. The demo user can browse, chat and view existing analyses, but **can't** create projects, index or run analyses, so arbitrary repos can't be indexed. Pre-index PetClinic and pre-run 2–3 analyses.
3. Caddy with a domain → automatic HTTPS. Tight rate limits, a low provider spending cap, and the DB not exposed publicly.
4. Monitor spend for the first week.

**Done when:** the live URL works in read-only mode with a spending cap.

## Step 11 — Final acceptance + close out (30 min)

Go through [PLAN.md §9](../PLAN.md#9-final-acceptance-checklist) and check each item **with evidence**:

- [ ] Fresh clone → running app in < 15 min (step 7)
- [ ] Index at a specific commit SHA; idempotent re-index (M2 tests + demo)
- [ ] Symbol and file browsing with line-accurate source
- [ ] Answers cite only validated evidence or abstain (M4 tests + reports)
- [ ] The eval command reproduces the committed reports (step 7)
- [ ] Diff → impact report with VERIFIED/INFERRED labels and tests with reasons (M5)
- [ ] Authz matrix and ingestion-validation tests in CI (step 1, M2)
- [ ] Demo runs end-to-end without manual DB edits (step 9)
- [ ] README with architecture, trade-offs, limitations and actual metrics (step 8)

### Wrap-up
1. Update the resume bullets (blueprint §12) with **measured** numbers only.
2. Devlog final entry.
3. "Current milestone" in `CLAUDE.md` → "M6 complete; stretch M7/M8 optional".
4. Tag `m6` and create a GitHub Release `v1.0.0` with release notes: features, results table, limitations.

## Pitfalls
- **Polishing UI instead of evidence:** the README results table and reproducibility beat visual polish.
- **Numbers drift:** if you change anything after the final eval run, re-run and re-commit, or the README lies.
- **Over-claiming:** "reduces hallucination by X%" is only valid if a report measured exactly that. Prefer concrete counts.
