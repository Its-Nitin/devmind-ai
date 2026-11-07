# DevMind AI — Coding Standards & Engineering Rules

| | |
|---|---|
| Status | v1.0 (2026-11-06). Change via PR that updates this file **and** the changelog at the bottom. |
| Applies to | All code, SQL, prompts, config and docs in this repository, whether written by a human or an AI assistant |

**Keywords.** **MUST** / **MUST NOT** = blocking; a PR that violates one doesn't merge. **SHOULD** = default; deviation needs a one-line justification in the PR or a code comment. **MAY** = allowed option.

**Enforcement.** Each rule names how it's checked: `[fmt]` Spotless/Prettier · `[lint]` Checkstyle/Error Prone/NullAway/ESLint · `[arch]` ArchUnit/Spring Modulith tests · `[test]` a test must exist · `[ci]` CI job · `[review]` self-review checklist. Rules that can be automated **must** be automated by the end of M1. `[review]`-only rules are kept to those that can't be.

Cite rules by ID in commits and reviews (e.g. "fix: SEC-4 canonicalize path before root check").

---

## Contents
[GEN](#gen) · [ARCH](#arch) · [JAVA](#java) · [SPRING](#spring) · [API](#api) · [DB](#db) · [AI](#ai) · [SEC](#sec) · [ERR](#err) · [OBS](#obs) · [TEST](#test) · [FE](#fe) · [GIT](#git) · [DOC](#doc) · [DoD](#dod) · [Rejected rules](#rejected) · [Changelog](#changelog)

---

<a id="gen"></a>
## GEN — General

| ID | Rule | Level | Check |
|---|---|---|---|
| GEN-1 | Deterministic code decides facts; the LLM only interprets them. Never ask a model for something a parser, Git, SQL or a validator can compute. | MUST | review |
| GEN-2 | Every user-visible claim is either backed by validated evidence or labelled `INFERRED`/uncertain. | MUST | test, review |
| GEN-3 | Don't add scope outside the current milestone in [PLAN.md](PLAN.md). Write ideas in `docs/devlog.md` under "Later". | MUST | review |
| GEN-4 | Names say *what*, not *how*: `ChangedSymbolDetector`, not `DiffHelper`. No `Util`, `Helper`, `Manager` or `Misc` classes. | MUST | lint (Checkstyle `TypeName` regex), review |
| GEN-5 | No dead code, commented-out code or `TODO` without an issue reference (`TODO(#12): …`). | MUST | lint |
| GEN-6 | Magic numbers that are tunable (top-k, depth, timeouts, thresholds) live in typed `@ConfigurationProperties`, never inline. | MUST | review |
| GEN-7 | Keep files focused: a class over ~300 lines or a method over ~40 lines is a smell to split, not a hard limit. | SHOULD | lint (warning) |
| GEN-8 | Text files are UTF-8 with LF line endings; `.editorconfig` and `.gitattributes` are authoritative. | MUST | fmt, ci |

<a id="arch"></a>
## ARCH — Architecture & module boundaries

| ID | Rule | Level | Check |
|---|---|---|---|
| ARCH-1 | Code lives in `com.devmind.<module>` per [architecture §3.2](architecture.md#32-modules-spring-modulith-application-modules). New modules require an ADR. | MUST | arch |
| ARCH-2 | Other modules may use only a module's **top-level package** (public API). `..internal..` is private. | MUST | arch (`ApplicationModules.verify()`) |
| ARCH-3 | A module's public API contains only interfaces, records, enums and events. No JPA entities, no Spring MVC types, no `@Service` implementations. | MUST | arch |
| ARCH-4 | No module dependency cycles. | MUST | arch |
| ARCH-5 | Inside a module: `web → application → domain`; `persistence` implements application-defined ports. `domain` must not depend on `web`, `persistence`, Spring MVC, `JdbcClient` or AI adapters. | MUST | arch (ArchUnit) |
| ARCH-6 | Cross-module side effects use events (`@ApplicationModuleListener`). Synchronous queries use the other module's API interface. | SHOULD | review |
| ARCH-7 | External systems (models, Git, parsers, clock, filesystem roots) are reached only through ports defined by the consuming module, with adapters in `internal`. | MUST | arch, review |
| ARCH-8 | Third-party AI SDK types (Spring AI, provider clients) appear only in `com.devmind.ai.internal..`. | MUST | arch |
| ARCH-9 | Controllers contain no business logic: they validate input, call one application service, then map the result. | MUST | review |

<a id="java"></a>
## JAVA — Language & style

| ID | Rule | Level | Check |
|---|---|---|---|
| JAVA-1 | Formatting by Spotless (formatter chosen in M1). Never hand-format against it. | MUST | fmt, ci |
| JAVA-2 | Every package is `@NullMarked` (JSpecify). Nullable values are annotated `@Nullable`. Use `Optional` only as a return type, never for fields or parameters. | MUST | lint (NullAway) |
| JAVA-3 | Prefer immutability: `record` for DTOs, value objects, events and query results. Fields are `final` unless JPA requires otherwise. Use defensive copies (`List.copyOf`) in record compact constructors. | MUST | lint, review |
| JAVA-4 | Strongly typed IDs for aggregates (`record ProjectId(UUID value)`), not raw `UUID`/`long` across module APIs. | MUST | review |
| JAVA-5 | No Lombok. | MUST | ci (dependency ban via Maven Enforcer) |
| JAVA-6 | Constructor injection only, `final` fields. No field `@Autowired`. One public constructor. | MUST | lint (Error Prone / ArchUnit) |
| JAVA-7 | Use `sealed` interfaces + `switch` pattern matching for closed result types (e.g. `QaOutcome = Answered \| EvidenceOnly \| InsufficientEvidence`), with exhaustive switches and no `default` on sealed types. | SHOULD | review |
| JAVA-8 | Never catch `Exception`/`Throwable` except at defined boundaries (job runner, global handler), and there always log + translate. Never swallow. | MUST | lint (Error Prone), review |
| JAVA-9 | Time comes from an injected `Clock`. Store and transmit `Instant` (UTC); no `LocalDateTime` in persistence or APIs. | MUST | arch (ban `LocalDateTime.now()`, `Instant.now()` without Clock) |
| JAVA-10 | Use `java.nio.file.Path` for file paths, never string concatenation. Repository-relative paths are stored with `/` separators. | MUST | review, test |
| JAVA-11 | Concurrency via virtual threads / structured executors. No raw `new Thread`, no `parallelStream()` on I/O work. | MUST | arch |
| JAVA-12 | Public methods of public API types have Javadoc stating contract, nullability and thrown exceptions. Internal code is commented only for *why*, not *what*. | SHOULD | review |
| JAVA-13 | No preview features. | MUST | ci (compiler flags) |
| JAVA-14 | Log through SLF4J with placeholders; no `System.out`/`printStackTrace`. | MUST | lint |

<a id="spring"></a>
## SPRING — Spring Boot specifics

| ID | Rule | Level | Check |
|---|---|---|---|
| SPRING-1 | Configuration is bound to typed, validated `@ConfigurationProperties` records (`@Validated`), prefixed `devmind.<module>`. No `@Value` scattered in code. | MUST | arch |
| SPRING-2 | Transactions are declared on application services, never on controllers or repositories. Keep transactions short, and **never call a model or Git inside a DB transaction**. | MUST | review, arch |
| SPRING-3 | `spring.jpa.open-in-view=false`, `spring.jpa.hibernate.ddl-auto=validate`. | MUST | test (context test asserts properties) |
| SPRING-4 | Profiles: `local`, `test`, `eval`, `prod`. No secrets in any `application-*.yaml`; secrets come from env vars only. | MUST | ci (secret scan), review |
| SPRING-5 | Expose only `health`, `info`, `metrics` Actuator endpoints, on the internal port in `prod`. | MUST | test |
| SPRING-6 | Outbound HTTP clients (model providers) have explicit connect and read timeouts. | MUST | test |

<a id="api"></a>
## API — REST contract

| ID | Rule | Level | Check |
|---|---|---|---|
| API-1 | Request and response bodies are dedicated `record` DTOs in `<module>.internal.web`. Entities and domain objects are never serialized. | MUST | arch |
| API-2 | Validate all input with Bean Validation (`@Valid`, size/pattern constraints). Strings have max lengths, collections have max sizes. | MUST | test |
| API-3 | Errors are RFC 9457 `ProblemDetail` with a stable `type` URI/`code` (e.g. `devmind:project-not-found`) and the `correlationId`. No stack traces in responses. | MUST | test |
| API-4 | Long-running operations return `202 Accepted` + `Location: /api/jobs/{id}` + a body `{jobId}`. | MUST | test |
| API-5 | Resource IDs in URLs are UUID `public_id`s; internal numeric IDs never leave the server. | MUST | arch, test |
| API-6 | Lists are paginated (`page`, `size ≤ 100`, `sort` whitelist). | MUST | test |
| API-7 | Revision-scoped endpoints take the commit SHA explicitly, and responses echo the revision. | MUST | test |
| API-8 | Accessing another user's resource returns **404**. | MUST | test (authz matrix) |
| API-9 | JSON: camelCase fields, ISO-8601 UTC timestamps, enums as UPPER_SNAKE strings, no `null` collections (empty instead). | MUST | test |
| API-10 | The OpenAPI spec is generated by springdoc and committed (`backend/openapi.json`). The frontend generates types from it. A CI check fails if the spec is stale. | MUST | ci |
| API-11 | Breaking API changes in the same PR update the frontend. No `/v2` endpoints in v1. | MUST | ci (frontend build) |

<a id="db"></a>
## DB — Database & persistence

| ID | Rule | Level | Check |
|---|---|---|---|
| DB-1 | The schema changes **only** through Flyway migrations `V<n>__<snake_desc>.sql`. Applied migrations are never edited. Fix forward with a new version. | MUST | ci (`flyway validate`) |
| DB-2 | Every FK has an index. Every project-owned table has `project_id` (directly or via a parent with `ON DELETE CASCADE`). | MUST | test (schema test queries `pg_catalog`) |
| DB-3 | Use DB constraints for invariants (NOT NULL, CHECK on enums, UNIQUE for idempotency keys), not just application checks. | MUST | review |
| DB-4 | JPA only for aggregates listed in [ADR-0005](adr/0005-persistence-jpa-plus-jdbcclient.md). Search, bulk and graph use `JdbcClient` with SQL in text blocks inside `*Queries` classes. | MUST | arch |
| DB-5 | No string concatenation of SQL with user input. Always bind parameters. Dynamic `ORDER BY` uses a whitelist enum. | MUST | lint (Error Prone), review |
| DB-6 | Every non-trivial query (FTS, KNN, CTE, fusion) has an integration test on Testcontainers Postgres + pgvector, and its `EXPLAIN` is checked once and noted in the PR when it's added. | MUST | test |
| DB-7 | Reads of code data are **revision-scoped**: they join `revision_file` for an explicit revision. There is no "latest" implicit query in the `indexing`/`retrieval` modules. | MUST | review, test |
| DB-8 | Bulk writes are batched (≥ 500 rows per batch), one transaction per batch. No per-row round trips in indexing. | MUST | review, test (perf smoke) |
| DB-9 | Recursive queries have an explicit depth limit, a `CYCLE` clause and a row limit. | MUST | review, test |
| DB-10 | Vector columns state their dimension. Embeddings are always stored with `embedding_model_id`. | MUST | review |

<a id="ai"></a>
## AI — Models, prompts, retrieval, output

| ID | Rule | Level | Check |
|---|---|---|---|
| AI-1 | Prompts live in `src/main/resources/prompts/<name>.v<N>.st`. They are **never inlined in Java**. Changing a prompt means a new version file, and the old one stays until no eval report references it. | MUST | arch (no long string literals in `ai`/`qa`/`impact`), review |
| AI-2 | Every model call goes through `ChatModelPort`/`EmbeddingPort` and writes an `llm_call` row (purpose, model, prompt id+version, tokens, latency, outcome). | MUST | test |
| AI-3 | Every model output that drives behaviour is parsed into a record and **validated** (Bean Validation + domain checks) before use. Free text is never parsed with regex. | MUST | test |
| AI-4 | On invalid output: at most **one** repair attempt that includes the validation errors, then degrade gracefully. Never loop. | MUST | test |
| AI-5 | Citations are evidence IDs from the provided set, resolved server-side ([ADR-0008](adr/0008-citation-by-evidence-id.md)). Unknown IDs are dropped and counted. | MUST | test |
| AI-6 | Repository content sent to a model is wrapped in delimited `<evidence>` blocks. The system prompt declares it untrusted data. Repository content is never placed in the system role. | MUST | test (prompt snapshot) |
| AI-7 | Q&A and impact prompts have **no tool access**. Tools exist only in the agent module (M7), and they are read-only. | MUST | arch |
| AI-8 | Every call sets max output tokens, a timeout and temperature (0 for eval). The context budget is enforced before the call, never by provider truncation. | MUST | test |
| AI-9 | Abstain before calling the model when retrieval evidence is below threshold. Don't spend tokens to say "I don't know". | MUST | test |
| AI-10 | Retrieval, chunking and fusion parameters are config (`RetrievalConfig`), included in `index_config_hash`/eval reports. Changing a default requires an eval run in the same PR. | MUST | review, ci (report presence) |
| AI-11 | Unit and integration tests use deterministic fakes (`FakeChatModel`, `FakeEmbedding`). Only the `eval` profile may call real models. | MUST | arch, ci (no network in tests) |
| AI-12 | No LLM-derived fact is stored as `VERIFIED` or as an edge with static provenance. | MUST | test |
| AI-13 | Prompt and response content is not logged outside `local` with an explicit debug flag. | MUST | review, test |

<a id="sec"></a>
## SEC — Security

| ID | Rule | Level | Check |
|---|---|---|---|
| SEC-1 | Every application service method taking a project-scoped identifier calls `ProjectAccessPolicy` **first**. Lookups by `jobId`/`analysisId`/`conversationId` resolve the owning project and check it. | MUST | test (authz matrix covers every endpoint) |
| SEC-2 | Local ingestion: resolve with `toRealPath()` and require `startsWith(allowedRoot)` for the root **and every file**. Reject symlinks that escape. Allowed roots come only from server config. | MUST | test (traversal + symlink cases) |
| SEC-3 | Remote ingestion: HTTPS only, host allow-list, no IP literals, no credentials in URLs, no submodules, shallow clone, size/file-count/time limits, per-job temp dir deleted afterwards. | MUST | test |
| SEC-4 | Never invoke a shell. Subprocesses (only if unavoidable, e.g. the M8 TS extractor) use `ProcessBuilder` with a fixed executable, an argument list, a timeout, a bounded output size and a minimal env. Model output never becomes a command or argument. | MUST | arch (ban `Runtime.exec`), review |
| SEC-5 | Secret files are excluded (`.env*`, `*.pem`, `*.key`, `id_rsa*`, `*.p12`, `*.jks`, `credentials*`, …) and chunk content is scanned and redacted before persistence or model submission. | MUST | test |
| SEC-6 | Secrets (DB password, API keys) only via env/secret files. `.env` is git-ignored, and `.env.example` documents the variables. | MUST | ci (gitleaks) |
| SEC-7 | CSRF protection on, session cookie `HttpOnly; Secure; SameSite=Lax`, passwords hashed with BCrypt/Argon2. | MUST | test |
| SEC-8 | Rate-limit chat, analysis and index endpoints per user. Enforce request body size limits. | MUST | test |
| SEC-9 | Frontend never uses `bypassSecurityTrust*` or `innerHTML` with repository or model content. Code and answers render as text or through a sanitizing Markdown renderer. | MUST | lint (ESLint rule), review |
| SEC-10 | Dependency vulnerability scanning in CI (OWASP dependency-check or GitHub Dependabot + `npm audit --omit=dev`), with no ignored criticals without a note. | MUST | ci |
| SEC-11 | Threat model (`docs/threat-model.md`) is updated in any PR that adds an input, an external call or a tool. | MUST | review |

<a id="err"></a>
## ERR — Error handling & resilience

| ID | Rule | Level | Check |
|---|---|---|---|
| ERR-1 | Domain failures are specific exceptions or sealed result types (`ProjectNotFound`, `RevisionNotReady`), mapped to ProblemDetail in one `@RestControllerAdvice`. | MUST | test |
| ERR-2 | Expected outcomes (abstention, evidence-only) are **results**, not exceptions. | MUST | review |
| ERR-3 | Retries only for idempotent operations with bounded attempts and backoff with jitter. Never retry validation errors. | MUST | review, test |
| ERR-4 | Per-file failures never fail a whole index run. They're recorded (`PARSE_FAILED`) and surfaced in stats. | MUST | test |
| ERR-5 | Jobs store a machine-readable `error_code` and a sanitized `error_detail` (no stack traces, no source code, no secrets). | MUST | test |
| ERR-6 | Every external call (model, clone) has a timeout. Use no unbounded waits anywhere. | MUST | review, test |

<a id="obs"></a>
## OBS — Observability

| ID | Rule | Level | Check |
|---|---|---|---|
| OBS-1 | Structured JSON logs. MDC carries `correlationId`, and `userId`/`projectId`/`jobId` when known. The correlation ID comes from `X-Correlation-Id` or is generated, and is echoed in responses and ProblemDetails. | MUST | test |
| OBS-2 | Log levels: `ERROR` = needs action; `WARN` = degraded but handled; `INFO` = lifecycle events (job started/finished, revision ready); `DEBUG` = details. No per-chunk INFO logs. | MUST | review |
| OBS-3 | Never log source code, prompts, model outputs, secrets, session IDs or passwords (see AI-13). | MUST | review |
| OBS-4 | Metrics listed in [architecture §12](architecture.md#12-observability) are emitted via Micrometer with low-cardinality tags (no paths or IDs as tags). | MUST | review |

<a id="test"></a>
## TEST — Testing

| ID | Rule | Level | Check |
|---|---|---|---|
| TEST-1 | Every behaviour change ships with tests in the same PR. Bug fixes start with a failing test. | MUST | review, ci |
| TEST-2 | Test pyramid: pure unit tests for parsing, splitting, fusion, diff mapping, validators and traversal logic; slice tests (`@WebMvcTest`, `@DataJpaTest`, `@JdbcTest`) for adapters; full `@SpringBootTest` + Testcontainers for pipelines. | MUST | review |
| TEST-3 | Parser and chunker use **golden tests**: fixture `.java` files → expected JSON snapshots under `src/test/resources/golden`. Updating a snapshot is an explicit, reviewed act. | MUST | test |
| TEST-4 | Integration tests use Testcontainers Postgres with the **same pinned pgvector image** as compose. No H2. Reuse containers per test JVM. | MUST | ci |
| TEST-5 | Tests are deterministic: fixed `Clock`, fake models, no network, no sleep-based waits (use Awaitility with a timeout for async). | MUST | ci (network-blocked job), review |
| TEST-6 | Test names describe behaviour: `rejectsSymlinkEscapingAllowedRoot()`, `returnsInsufficientEvidenceWhenNoChunkClearsThreshold()`. Use the Given/When/Then structure in the body. | MUST | review |
| TEST-7 | Assertions with AssertJ. Mock only ports you own (model, Git, clock), never JPA repositories or types you don't own. | SHOULD | review |
| TEST-8 | Coverage gate (JaCoCo, line): ≥ 80% for `indexing`, `retrieval`, `qa`, `impact` internals; no global gate. Coverage is a floor, not a goal. | MUST | ci |
| TEST-9 | An authorization matrix test enumerates **all** mapped endpoints (via `RequestMappingHandlerMapping`) and fails if a new endpoint isn't classified. | MUST | test |
| TEST-10 | An architecture test suite (`ArchitectureTest`) holds all `[arch]` rules from this document. | MUST | ci |
| TEST-11 | Eval runs are **not** part of `mvn verify`. They run via the `eval` profile, and their reports are committed. | MUST | ci |

<a id="fe"></a>
## FE — Angular frontend

| ID | Rule | Level | Check |
|---|---|---|---|
| FE-1 | TypeScript `strict: true`, plus `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes` and Angular `strictTemplates`. No `any` (use `unknown` + narrowing). No non-null `!` assertions without a comment. | MUST | lint, ci |
| FE-2 | Standalone components only, `ChangeDetectionStrategy.OnPush` on all components, zoneless app config. | MUST | lint |
| FE-3 | State in signals (`signal`, `computed`, `linkedSignal`/resources where helpful). RxJS only for HTTP/event streams, converted at the service boundary. No manual `subscribe` in components without `takeUntilDestroyed`. | MUST | lint, review |
| FE-4 | Use `inject()` for DI. Use new control flow (`@if`, `@for` with `track`, `@switch`). Use `input()`/`output()`/`model()` signal APIs. | MUST | lint |
| FE-5 | Structure: `core/` (singletons: auth, http interceptors, error handling), `shared/` (presentational, dependency-free UI), `features/<feature>/` (routes, pages, feature store, data-access). Features don't import from other features. | MUST | lint (eslint boundaries rule) |
| FE-6 | Smart/dumb split: pages/containers talk to stores; presentational components take inputs and emit outputs, and never inject data services. | MUST | review |
| FE-7 | API types come only from the generated OpenAPI types. No hand-written duplicates of DTOs. | MUST | review, ci |
| FE-8 | All HTTP through feature `*Api` services. Interceptors add the correlation ID, handle 401 → login, and map ProblemDetail → a user-facing toast. | MUST | review |
| FE-9 | Routes are lazy-loaded per feature, with guards for auth. | MUST | review |
| FE-10 | Every async view has explicit loading, empty, error and (for chat) insufficient-evidence states. | MUST | review, test |
| FE-11 | Accessibility: semantic elements, labelled controls, keyboard-navigable citation chips and tree, colour not the only signal (VERIFIED/INFERRED badges have text). | MUST | lint (angular-eslint template a11y), review |
| FE-12 | Repository and model content are rendered as text (SEC-9). Code goes only through CodeMirror. | MUST | lint |
| FE-13 | Unit tests for stores, pipes and non-trivial components; one Playwright E2E for the happy path (login → index → ask → open citation). | MUST | ci |
| FE-14 | Prettier formatting; file naming `kebab-case.<type>.ts`. Selector prefix `dm-`. | MUST | fmt, lint |

<a id="git"></a>
## GIT — Version control & workflow

| ID | Rule | Level | Check |
|---|---|---|---|
| GIT-1 | Trunk-based: `main` is always releasable. Use short-lived branches `feat/…`, `fix/…`, `chore/…`, `docs/…`, `eval/…`. | MUST | review |
| GIT-2 | Conventional Commits: `type(scope): summary` (scope = module, e.g. `feat(retrieval): add trigram symbol retriever`). Imperative mood, ≤ 72 chars. The body explains *why* and cites rule or ADR IDs when relevant. | MUST | ci (commitlint) |
| GIT-3 | Every change goes through a PR (even solo) with the template checklist, a green CI run and squash merge. | MUST | ci (branch protection) |
| GIT-4 | One logical change per PR. Keep PRs under ~400 changed lines excluding generated files and snapshots. | SHOULD | review |
| GIT-5 | Never commit secrets, `.env`, local datasets of private code, model weights or `target/`/`node_modules/`. | MUST | ci (gitleaks), .gitignore |
| GIT-6 | Tag milestones `m0`…`m6`. The tag message summarizes exit criteria met and links the eval report. | MUST | review |

<a id="doc"></a>
## DOC — Documentation

| ID | Rule | Level | Check |
|---|---|---|---|
| DOC-1 | Architectural decisions (a new module, datastore, library with a broad footprint, changed contract, changed security boundary) need an ADR **before** the code. | MUST | review |
| DOC-2 | README metrics are copied from committed reports and link to them. Never state a number that isn't in a report. | MUST | review |
| DOC-3 | Limitations are documented where they apply (README "Limitations", ADR consequences, `architecture.md` §14). | MUST | review |
| DOC-4 | Append to `docs/devlog.md` per working session: what changed, what failed, what was measured, what's next. | SHOULD | review |
| DOC-5 | Diagrams are Mermaid in Markdown (diffable), not binary images, except README screenshots/GIFs. | SHOULD | review |

---

<a id="dod"></a>
## Definition of Done (per PR)

- [ ] Scope belongs to the current milestone (GEN-3).
- [ ] `./mvnw verify` and the frontend `lint`/`test`/`build` pass locally and in CI.
- [ ] Tests added or updated; a bug fix has a failing-first test (TEST-1).
- [ ] No new `[arch]` violations; module public API unchanged, or the change is intentional and minimal (ARCH-2/3).
- [ ] New input, external call or tool → threat model updated (SEC-11); authz matrix still complete (TEST-9).
- [ ] New or changed endpoint → OpenAPI regenerated, frontend types regenerated (API-10).
- [ ] Schema change → new Flyway migration; FK indexes present (DB-1/2).
- [ ] Prompt, retrieval or chunking change → new prompt version or config, plus an eval report committed (AI-1/10).
- [ ] No source, prompts or secrets in logs (OBS-3).
- [ ] ADR written or updated if architectural (DOC-1). Devlog entry (DOC-4).

## Definition of Done (per milestone)
- [ ] All exit criteria in [PLAN.md](PLAN.md) met and demonstrated from a clean `docker compose up`.
- [ ] Tag `mN` created, with the eval report linked if the milestone has one.

---

<a id="rejected"></a>
## Rules considered and rejected (review pass log)

These came up while drafting and were removed or changed on purpose. They're kept here so they don't get re-proposed without new arguments.

| Proposed rule | Outcome | Reason |
|---|---|---|
| "100% test coverage" | Rejected → TEST-8 (80% on core modules, no global gate) | Leads to testing getters and config; scarce time belongs on golden and pipeline tests. |
| "Every service must have an interface" | Rejected → ARCH-7 (interfaces for ports only) | Interface-per-class doubles files with no polymorphism. Ports where there's a real seam: models, Git, parsers. |
| "Use Lombok to reduce boilerplate" | Rejected → JAVA-5 | Records cover most cases; Lombok hides members from source parsers and NullAway. |
| "Hexagonal architecture everywhere (ports/adapters per module)" | Narrowed → ARCH-5/7 | Full hexagonal ceremony in CRUD-ish modules (`identity`, `project`) is overhead; used where external systems exist. |
| "Hard limits: max 200 lines per class, 20 per method" | Softened → GEN-7 (SHOULD, warning) | Hard limits produce artificial splits (parsers have long visitor methods). |
| "All SQL through JPA" | Rejected → DB-4 | Vector, FTS and CTE workloads are native SQL anyway ([ADR-0005](adr/0005-persistence-jpa-plus-jdbcclient.md)). |
| "Model outputs paths and line numbers; validate them" | Replaced → AI-5 | Evidence IDs make invalid locations impossible ([ADR-0008](adr/0008-citation-by-evidence-id.md)). |
| "Mock repositories in service tests" | Rejected → TEST-7 | Mocked persistence hides SQL bugs; Testcontainers is fast enough. |
| "Return 403 for unauthorized project access" | Changed → API-8 (404) | 403 reveals that the resource exists. |
| "Use NgRx for state" | Rejected → FE-3 | Signals in feature stores are enough at this size. |
| "Rule: always stream LLM responses" | Deferred | Streaming complicates validation-before-display (AI-3). Revisit after M6. |
| "Log full prompts for debugging" | Restricted → AI-13 | Prompts contain source code; privacy and noise. Local-only flag. |

<a id="changelog"></a>
## Changelog
- **v1.0 — 2026-11-06:** initial standards. Drafted, reviewed against the plan/ADRs, and revised (see the rejected-rules table): unenforceable rules removed, every MUST given a check, and AI/SEC rules aligned with ADR-0006/0008/0010.
