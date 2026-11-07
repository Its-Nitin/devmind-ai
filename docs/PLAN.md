# DevMind AI — Delivery Plan

| | |
|---|---|
| Status | Approved baseline v1 (2026-11-06) |
| Supersedes | `DevMind_AI_Implementation_Blueprint.pdf` (kept as background; this plan wins on conflict) |
| Companion docs | [architecture.md](architecture.md) · [adr/](adr/) · [coding-standards.md](coding-standards.md) · [../CLAUDE.md](../CLAUDE.md) |

---

## 1. Goal

Build a codebase-intelligence system that **answers questions about a Java repository with server-validated evidence** and **predicts the impact of a diff, including which tests to run**. **Measured numbers** must back both claims.

What an interviewer should come away with, in priority order:

1. **Measured quality.** There's a reproducible benchmark with real numbers, including failures.
2. **Trustworthy answers.** The model can only cite evidence the server gave it, and the server validates every citation.
3. **Deterministic change impact.** Parsers, Git and graph traversal do the work. The LLM explains the results. Test recommendations are checked against tests that **actually fail**.
4. **Clean engineering.** A modular monolith with verified module boundaries, migrations, Testcontainers, CI, and threat-model notes.
5. **(Stretch)** An agent or MCP surface over the same read-only tools.

If a task doesn't strengthen one of these five, it waits.

---

## 2. Changes from the original blueprint

| # | Blueprint says | This plan does | Why |
|---|---|---|---|
| 1 | 4 weeks / 60–100 h for everything, agent included | Core (M0–M6) ≈ **100–135 h**, about 5–6 weeks at 3–5 h/day. Agent and TypeScript work are stretch goals. | Symbol resolution and benchmark labelling regularly overrun. An honest estimate beats a missed one. |
| 2 | Benchmark built in Week 4 | The benchmark is **seeded in M0** and grows each milestone. Retrieval eval runs in M3, before any LLM. | The blueprint says "build a benchmark before tuning" but schedules it last. |
| 3 | The flagship demo traces frontend → backend | v1 flagship is **backend-only**. The output lists "frontend not analyzed" under unknowns. Cross-layer linking is stretch M8. | TypeScript parsing was deferred in the blueprint but promised in the demo. That contradiction would show in an interview. |
| 4 | Layered packages (`domain/ repository/ service/ web`) under the "modular monolith" label | **Package-by-module** (`indexing`, `retrieval`, `qa`, `impact`, …). Spring Modulith verifies the boundaries in a test. | Layered packages aren't modules. See [ADR-0001](adr/0001-modular-monolith-spring-modulith.md). |
| 5 | Model cites `filePath` + line numbers | Model cites **evidence IDs** (`E3`). The server maps them to path and lines and rejects unknown IDs. | This removes hallucinated paths and line numbers by construction. [ADR-0008](adr/0008-citation-by-evidence-id.md). |
| 6 | Per-revision copies of files, symbols and chunks | **Content-addressed blobs.** Revisions map path → blob, and embeddings are reused across revisions. | Change analysis needs *two* indexed revisions. Without reuse, the head revision is re-embedded in full. [ADR-0003](adr/0003-content-addressed-storage.md). |
| 7 | "Recommend tests" with no method given | Tests are found by deterministic reverse traversal (test method → … → changed symbol) plus naming conventions. They're **validated against failing tests from seeded mutations**. | This turns "suggested tests" from LLM guesswork into a measurable metric. [ADR-0011](adr/0011-evaluation-as-code.md). |
| 8 | Ollama for local inference | **Everything local on Ollama in v1**: embeddings and chat (owner's $0 decision in M0). Chat goes through a configurable provider, so a hosted OpenAI-compatible endpoint stays a config change if a budget appears. | Measured in M0: ~4.3 min per 4k-token answer on this CPU (no GPU). Accepted: eval runs overnight with a response cache, and the context budget is kept tight. [ADR-0006](adr/0006-ai-provider-port.md). |
| 9 | Edge `confidence` number | Edge **`provenance` enum**: `STATIC_RESOLVED`, `STATIC_NAME_MATCH`, `HEURISTIC`, `LLM_INFERRED`. | A number invites fake precision. Provenance is what the "verified vs inferred" UI actually needs. |
| 10 | Keyword search unspecified | Postgres FTS on **identifier-split text** with the `simple` config, plus `pg_trgm` on symbol names. | The default `english` config stems words and can't split `findOwnerById`. |
| 11 | Product 1.2 = an agent that prepares patches | Product 1.2 = **read-only MCP server** + bounded agent loop. Patch generation is dropped. | Patch generation is a crowded space and risky. An MCP server is cheap, current, and reuses the tools you already built. [ADR-0012](adr/0012-mcp-and-bounded-agent.md). |
| 12 | `password_hash` auth, implied JWT | **Session auth** behind a same-origin proxy, with seeded users and GitHub OAuth as an option. One `ProjectAccessPolicy` enforces ownership. | It's less code and more secure for a same-origin SPA. [ADR-0010](adr/0010-session-auth-same-origin.md). |
| 13 | Java 21 | **Java 25 LTS** (21 is acceptable; it's a one-line change) | 25 is the current LTS. Nothing here depends on it, but "why not the latest LTS?" is an easy question to avoid. |
| 14 | Adversarial input mentioned only as policy | The benchmark includes **prompt-injection cases**, such as a comment saying "ignore previous instructions…". | It turns a security claim into a tested one. |

---

## 3. Scope

### In scope for core (Product 1.0 + 1.1)
- Index a **Java** repository (local path under an allowed root, or a public HTTPS Git URL) at a pinned commit SHA.
- Browse files and symbols, and view source with line anchors.
- Hybrid retrieval (lexical + vector + symbol), with graph expansion.
- Grounded Q&A that returns validated citations, uncertainties and abstention. Single-turn: conversations are stored, but earlier turns aren't rewritten into the query.
- Change impact: base…head diff → changed symbols → bounded dependency traversal → impacted symbols → recommended tests → LLM risk findings labelled `VERIFIED` or `INFERRED`.
- Evaluation runner and committed reports (retrieval, answers, citations, abstention, impact/test recall, latency/cost).
- Auth, project isolation, limits, timeouts, observability, Docker Compose, README, demo video.

### Explicitly out of scope (v1)
Private repos and credential handling · languages other than Java · automatic patching or commits · multi-turn query condensation · streaming responses · multi-tenant SaaS concerns (billing, orgs) · Kubernetes.

### Stretch (only after M6 is done)
- **M7:** Read-only MCP server + bounded tool-calling agent.
- **M8:** Angular/TypeScript extractor + `HTTP_CALLS` edges (Angular `HttpClient` URL ↔ Spring `@RequestMapping`). This restores the cross-layer flagship.

---

## 4. Target repositories (benchmark subjects)

| Role | Candidate | Notes |
|---|---|---|
| Primary | `spring-petclinic/spring-petclinic-rest` | Small, well-known REST backend with tests. Pin a SHA. |
| Frontend (M8) | `spring-petclinic/spring-petclinic-angular` | Matching Angular client for cross-layer linking. |
| Generalization check | One mid-size Spring Boot repo you know (≈200–600 Java files) | Shows the system wasn't overfitted to PetClinic. Pick it in M0. |

In M0, check each candidate's license, that `mvn test` passes at the pinned SHA, test-suite runtime (it must be fast for the mutation runs), and Lombok usage. Lombok-generated members are invisible to source parsing, so list that as a known limitation.

---

## 5. Milestones

Each milestone ends with a Git tag (`m1`, `m2`, …), a green CI run, and a short entry in `docs/devlog.md` covering what was built, what broke, and what was measured. The devlog is your interview material.

### M0 — Pre-implementation (6–10 h). No product code.
- [x] Install toolchain: JDK 25 (Temurin), Maven wrapper (generated), Node LTS + npm, Docker Desktop (WSL2 backend), Ollama, IDE plugins (Spotless/Checkstyle, ESLint, Prettier).
- [x] `git init`, add `.gitattributes` (`* text=auto eol=lf`) **before the first commit**, `.gitignore`, `.editorconfig`. Create a GitHub repo.
- [x] **Hardware smoke test** (record results in the devlog):
  - Ollama embedding model: time to embed 100 code chunks.
  - Ollama 7–8B chat model: tokens/sec and total latency for a 4k-token prompt + 400-token JSON answer.
  - ~~Hosted model (OpenAI-compatible endpoint) on the same prompt.~~ N/A: local-only decision (ADR-0006).
  - Lock the embedding model and dimension, and record the decision in ADR-0006.
- [x] Pick and pin target repos (§4). Run their tests locally and record runtimes.
- [x] **Seed the benchmark:** write 20 questions with expected files/symbols across all categories (architecture.md §13), including 3 abstention and 2 injection cases. Read the code to label them, not the tool.
- [x] Write `docs/threat-model.md` v0 (assets, trust boundaries, top 10 threats, mitigations → rule IDs).
- [x] ~~Set a hosted-LLM spending cap on the provider dashboard.~~ N/A: local-only decision (ADR-0006).

M0 closed 2026-11-07 (tag `m0`). Results and deviations are in [devlog.md](devlog.md).

**Exit:** the toolchain works, models are measured, the embedding model is locked, 20 labelled questions are committed, and the threat model v0 is committed.

### M1 — Walking skeleton (8–12 h)
- [ ] Monorepo layout (architecture.md §3.4). Spring Boot app (Web, Security, Validation, Actuator, Data JPA, JDBC, Flyway, Modulith, springdoc). Angular app (standalone, strict, zoneless).
- [ ] `deploy/compose.yaml`: `pgvector/pgvector` (pinned tag), backend, frontend (built static files), Caddy reverse proxy. Ollama runs on the host.
- [ ] Flyway `V1__baseline.sql`: extensions (`vector`, `pg_trgm`, `citext`), `app_user`, `project`, `job`.
- [ ] Health endpoint, ProblemDetail error handler, correlation-ID filter, JSON logs.
- [ ] Quality gates wired up: Spotless, Checkstyle, Error Prone + NullAway, ArchUnit/Modulith `verify()` test, JaCoCo, ESLint, Prettier, Vitest/Angular test runner.
- [ ] GitHub Actions: backend build + tests (Testcontainers), frontend lint + tests + build.
- [ ] Login (seeded user), `POST/GET /api/projects`, ownership enforced and tested.

**Exit:** `docker compose up` starts everything, you can log in and create a project, and CI is green with all gates enforcing.

### M2 — Ingestion & structural index (18–25 h)
- [ ] Source acquisition: local path (canonicalized, must be under `devmind.ingestion.allowed-roots`) or public HTTPS clone (JGit, depth-limited, no submodules, size/file-count limits). Resolve and pin the commit SHA.
- [ ] File filter: allow-list of extensions, deny-list of paths (`target/`, `build/`, `node_modules/`, `.git/`, generated sources), binary detection, size cap, secret-file patterns, and symlinks that escape the root.
- [ ] Normalization: UTF-8 decode (reject or flag otherwise), CRLF→LF, then SHA-256 → `file_blob` (content-addressed per project).
- [ ] `CodeParser` port + `JavaParserCodeParser`: classes, interfaces, enums, records, methods, constructors, fields; qualified names, signatures, annotations, modifiers, 1-based inclusive line ranges; `is_test` detection.
- [ ] Symbol-aware chunking (architecture.md §7.1) with a contextual header.
- [ ] Secret scanning on chunk content: redact before persistence, and count redactions.
- [ ] DB-backed job queue (`SKIP LOCKED`), idempotent on `(project, commit_sha, index_config_hash)`, with progress reporting.
- [ ] APIs: start index job (202), job status, list revisions, browse files, file content by line range, symbol search (exact + trigram).
- [ ] UI: project list, indexing status (polling), file tree, symbol list, read-only code viewer with line highlight.
- [ ] Tests: parser golden tests (fixture Java files → expected symbols JSON), filter tests (including path traversal and symlink escape), and an indexing integration test on a fixture repo.

**Exit:** index PetClinic at a pinned SHA; *"OwnerController" → correct file, qualified name, line range, source*. Re-indexing the same SHA is a no-op. Indexing a second SHA only re-parses changed blobs.

### M3 — Retrieval + evaluation harness (15–20 h)
- [ ] `EmbeddingModel` port + Ollama adapter. Batch embedding, persisted in `chunk_embedding` with `embedding_model_id`, plus an HNSW index.
- [ ] Lexical retriever (FTS over identifier-split `search_tsv`), vector retriever, symbol retriever, and RRF fusion (k = 60). Every parameter lives in configuration.
- [ ] `POST /api/projects/{id}/retrieve` (debug endpoint returning ranked evidence with per-retriever ranks).
- [ ] Evaluation runner (`evaluation` module, run with `./mvnw -pl backend spring-boot:run -Dspring-boot.run.profiles=eval …` or a CLI runner). It reads `evaluation/datasets/*.yaml` and writes `evaluation/reports/<timestamp>-<config>.json` plus `.md`.
- [ ] Metrics: Recall@5, Recall@10, MRR, per category.
- [ ] **Experiment A: vector-only vs lexical-only vs hybrid.** Commit the report.
- [ ] Grow the dataset to 35–40 questions.

**Exit:** a committed retrieval report with real numbers for three configurations. No LLM has been called yet.

### M4 — Grounded Q&A (15–20 h)
- [ ] `ChatModel` port + adapters (Ollama, OpenAI-compatible). Provider, model and timeouts in config. `llm_call` audit rows store tokens, latency, model and prompt version, but not content by default.
- [ ] Versioned prompt templates (`prompts/qa-answer.v1.st`). Evidence goes in delimited blocks with IDs, and untrusted-content instructions sit in the system prompt.
- [ ] Structured output: a JSON schema → `QaModelOutput` record, Bean Validation, **one** repair retry. If that fails, degrade to an "evidence only" response.
- [ ] Citation validator: unknown evidence ID → dropped and counted. Every surviving citation maps to `(path, startLine, endLine, revision)`.
- [ ] Abstention gate before the LLM: if no evidence clears the fused-score threshold, return `INSUFFICIENT_EVIDENCE` without a model call.
- [ ] `POST /api/projects/{id}/chat` (sync, with a timeout), and conversation persistence.
- [ ] UI: chat panel, answer with inline `[E3]` chips → evidence panel → code viewer at those lines. Uncertainties list and an insufficient-evidence state.
- [ ] Eval: answer correctness (rubric graded by you, scores stored in the dataset review file), citation validity rate, citation support rate (sampled, manual), abstention precision/recall, injection-resistance pass rate, latency p50/p95, tokens per question.
- [ ] **Experiment C: validator on vs off** (how many invalid citations it caught).

**Exit:** all benchmark questions answered or correctly abstained, and the answer report is committed.

### M5 — Dependency graph & change impact (22–30 h)
- [ ] Edge extraction with JavaParser SymbolSolver: `CALLS`, `IMPORTS`, `EXTENDS`, `IMPLEMENTS`, `REFERENCES` (type usage), `OVERRIDES`, each with `provenance`. Unresolved calls are kept with `target_ref` text and `STATIC_NAME_MATCH` or none.
- [ ] Interface → implementation hop (Spring injects interfaces, so callers of `OwnerService` must reach `ClinicServiceImpl`).
- [ ] Bounded traversal: a recursive CTE with depth limit, `CYCLE` clause, a fan-out cap, and an allowed relation set.
- [ ] Diff: JGit `DiffFormatter` (0 context, rename detection) → hunks → changed symbols (line-range intersection plus a base↔head `symbol_key` match for deletions and signature changes).
- [ ] Test recommendation: reverse traversal from changed symbols to `is_test` methods, plus naming-convention matches (`FooTests`, `FooTest`, `FooIT`). Each recommendation carries its path.
- [ ] Risk findings: the LLM sees changed symbols, impacted paths and evidence, and returns structured findings that cite evidence IDs. `basis` is `VERIFIED` (a deterministic fact such as "public signature changed and 4 callers exist") or `INFERRED` (an LLM judgement). Deduplicate and validate.
- [ ] `POST /api/projects/{id}/analyses` (202 + job), `GET /api/analyses/{id}`, `GET …/findings`.
- [ ] UI: analysis form (pick two indexed revisions), changed-symbol list, impact graph (Cytoscape, max 150 nodes, provenance styling), findings grouped by severity with a VERIFIED/INFERRED badge, recommended tests with "why" paths.
- [ ] **Mutation ground truth:** `evaluation/impact/` holds 10–15 curated patches against the primary repo. The script applies each patch, runs the tests, and records failing test methods.
- [ ] Eval: test-recommendation recall and precision vs failing tests, risk-finding precision (manual review), and analysis latency.
- [ ] **Experiment B: retrieved chunks vs graph-expanded context** on cross-file questions.

**Exit:** a real diff produces a report in which every item is labelled verified or inferred. The impact eval report is committed.

### M6 — Hardening & ship (12–16 h)
- [ ] Authorization test matrix: every endpoint × {owner, other user, anonymous}.
- [ ] Limits: request size, question length, retrieval top-k, traversal depth/fan-out, LLM max tokens, per-user rate limit on chat and analysis.
- [ ] Timeouts and failure modes: model down, DB down, clone fails, parse error in one file (file marked `PARSE_FAILED`, revision still `READY` with warnings).
- [ ] Project deletion cascades (blobs, chunks, embeddings, edges, conversations, analyses), with a test.
- [ ] Metrics dashboard (Actuator/Micrometer) and an LLM cost summary from `llm_call`.
- [ ] README: problem, demo GIF, architecture diagram, **results table from committed reports**, limitations, how to reproduce, trade-offs (link the ADRs).
- [ ] `docs/demo-script.md`, a 3–4 minute recorded demo, and `docs/evaluation.md` (method + results + failure analysis).
- [ ] Ship a demo video plus a one-command local run. (A live read-only demo needs a VPS and a hosted model with a spending cap, so it's out of scope under the local-only decision unless a budget appears.)

**Exit:** every item in §9 is checked.

### M7 — Stretch: MCP server + bounded agent (8–12 h)
Read-only tools `search_code`, `get_symbol`, `find_callers`, `find_tests_for`, `inspect_diff`, exposed through an MCP server and used by a bounded agent loop (max iterations, per-tool timeouts, argument validation, full audit). Eval: agent vs. fixed pipeline on the cross-file questions.

### M8 — Stretch: Angular cross-layer (12–18 h)
A Node extractor using the TypeScript Compiler API (run as a subprocess at index time, JSON out). It extracts components, services and `HttpClient` calls with URL templates. Each URL is matched to a Spring mapping, and the result becomes an `HTTP_CALLS` edge with `HEURISTIC` provenance. Restores the full flagship demo.

---

## 6. Cut lines (if behind schedule, cut in this order)
1. M8, then M7.
2. Impact graph visualization → a plain list of paths.
3. Conversation history UI.
4. Generalization repo (keep PetClinic only, and say so in the README).
5. GitHub OAuth (keep the seeded user).

**Never cut:** evaluation reports, citation validation, authorization tests, the reproducible one-command run, honest limitations.

---

## 7. Risk register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| SymbolSolver can't resolve calls into libraries or Lombok code | High | Medium | Add a JAR type solver from `mvn dependency:copy-dependencies` when available. Keep unresolved edges labelled. Report the resolution rate as a metric. |
| Local LLM too slow on this laptop | **Happened** (M0: ~4.3 min/answer) | High | Accepted under the local-only decision: overnight eval runs, response cache keyed by prompt hash, tight context budget (prompt processing dominates), demo from a warm cache. A hosted provider is a config switch if a budget appears. |
| Benchmark labelling takes longer than planned | High | High | Seed it in M0, add 5–10 questions per milestone, and cap it at 50. |
| Spring AI API churn | Medium | Medium | Spring AI is used only inside `ai.internal` adapters behind your own ports. |
| Scope creep (UI polish, extra languages) | High | High | §6 cut lines; nothing outside §3 before M6 is done. |
| Hosted model cost or quota surprises | N/A while local-only | Medium | Only applies if a hosted provider is configured later: spending cap, `llm_call` accounting, benchmark caching keyed by prompt hash. |
| Windows-specific issues (CRLF, paths, Docker file sharing) | Medium | Medium | `.gitattributes`, LF normalization, `Path`-based code with tests using `/` and `\`, repos inside WSL or a Docker volume. |
| Pinned target repo stops building | Low | Medium | Pin the SHA and keep a vendored copy of the fixture subset in `backend/src/test/resources/fixtures`. |

---

## 8. Working agreement
- One milestone at a time. Don't start M(n+1) code until M(n) meets its exit criteria.
- Every architectural change → new or updated ADR **before** the code.
- Every claim in the README links to a committed report.
- A devlog entry per working session: 3–5 bullets.

## 9. Final acceptance checklist
- [ ] Fresh clone → `docker compose up` + documented model setup → working app in < 15 min.
- [ ] Index a repo at a specific commit SHA, and re-index idempotently.
- [ ] Symbol and file browsing with line-accurate source.
- [ ] Answers cite only validated evidence, or explicitly abstain.
- [ ] `evaluate` command reproduces the committed reports (same dataset, model, index config).
- [ ] Diff → impact report with VERIFIED/INFERRED labels and recommended tests with reasons.
- [ ] Authorization matrix and ingestion-validation tests pass in CI.
- [ ] Demo runs end-to-end without manual DB edits.
- [ ] README documents architecture, trade-offs, limitations and **actual** metrics.
