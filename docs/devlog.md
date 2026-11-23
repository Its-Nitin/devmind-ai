# Devlog

## 2026-11-07 — M0 (session 1)
- Did:
  - Repo bootstrapped locally: `git init` on `main`, `.gitattributes` / `.editorconfig` / `.gitignore` written before any commit; repo-local `core.autocrlf=false` (the system gitconfig sets `autocrlf=true`, which would override LF rules).
  - Targets cloned to `D:\dev\targets` and pinned on local `devmind-pinned` branches (`evaluation/targets.yaml`).
  - Benchmark seeded: 20 questions in `evaluation/datasets/qa-petclinic-rest.yaml`. The quota is met (4/3/3/3/2/3/2); holdout = q003, q006, q009, q013.
  - Content-borne injection fixture: branch `devmind-injection` (4fd1e36) and patch `evaluation/fixtures/0001-…patch`.
  - Threat model v0 (`docs/threat-model.md`), with 4 threats added beyond the seed list: T13 citation spoofing, T14 git filters/hooks, T15 operator-side code execution in mutation runs, T16 stale-revision answers.
  - M0 smoke scripts + 10 spot queries in `tools/m0-smoke/` (not run yet).
- Broke / surprised:
  - Toolchain is mostly missing: only Git 2.56 and winget are present. No JDK, Node, Docker, Ollama or gh.
  - **WSL2 can't start: CPU virtualization is disabled in firmware.** It must be enabled in BIOS/UEFI (Intel VT-x) and `wsl --install --no-distribution` run as Administrator before Docker Desktop will work.
  - Global git `user.name`/`user.email` are unset, so nothing is committed yet.
  - spring-petclinic-rest is **API-first**: controller interfaces (`OwnersApi`, …) and DTOs are generated from `openapi.yml` into `target/`. URL mappings and the only use of `@PetAgeValidation` (via `x-field-extra-annotation`) exist only in generated code or YAML. This is a real static-analysis blind spot; q012 tests it. Consider indexing `openapi.yml` as a config file.
  - `CallMonitoringAspect` is declared `@Aspect` but is never registered as a bean (no `@Component`, no config references it), so it looks inactive. It's a good candidate for a tricky explanation question among the remaining 30.
  - `spring-boot-starter-cache` is on the classpath but there's no `@EnableCaching`/`@Cacheable`, so "caching" is **not** a clean abstention topic.
  - The target clone uses CRLF in its working copy (system `autocrlf=true`). Line numbers are unaffected.
- Measured:
  - Git 2.56.0.windows.4. Other tool versions: pending install.
  - petclinic-rest @ 5180cc9: 109 Java files (22 under src/test), Spring Boot 4.1.2, Apache-2.0, no Lombok, MapStruct 1.6.3, openapi-generator 7.26.0. Test count/runtime: pending JDK.
  - petclinic-angular @ fba8956: MIT (package.json; no LICENSE file).
- Next:
  - Enable VT-x → WSL2 → install toolchain (step 1) → record versions here.
  - Set git identity → first commit → `gh repo create` (step 2).
  - Run target tests + mutation trial (steps 3.3–3.4).
  - Hosted LLM key + spending cap (step 4) → model smoke tests + embedding decision → ADR-0006 (step 5).
  - Second pass over the 20 questions in a fresh session (step 6.6).
  - Generalization repo: deferred (cut line #4) unless time allows.

## 2026-11-07 — M0 (session 2)
- Did:
  - Toolchain installed and verified: Temurin 25.0.5 LTS, Node 26.13.0 / npm 11.20.0, Docker 29.9.2 (Compose v5.6.1, WSL2 backend; `hello-world` OK), Ollama 0.40.15 (API OK), gh 2.103.3 (logged in as Its-Nitin), Git 2.56.0. VT-x enabled.
  - First commit `8fd8842` (docs + repo config); `git ls-files --eol` shows i/lf w/lf for all 34 text files.
- Decision: **local Ollama only, for chat as well as embeddings.** No hosted LLM account (no budget). M0 step 4 is skipped, and the hosted row in the step 5 table is N/A.
  - This is within ADR-0006: the chat provider is config-only, and the "Revisit when" clause allows going fully local. ADR-0006 gets an amendment in the step-5 PR, alongside the embedding choice.
  - Expected cost: slow CPU chat makes the 50-question × N-config eval long. Mitigations to evaluate in step 5: a smaller model (3B), a tighter context budget, overnight eval runs, and the planned response cache (reruns are free). Plan risk "Local LLM too slow" is now accepted rather than mitigated by hosted chat. The demo becomes a video plus a one-command local run (already the PLAN M6 fallback).
  - T9 (denial of wallet) mostly falls away. Local CPU exhaustion remains a DoS concern.
- Broke / surprised:
  - No `.wslconfig` at first, so Docker saw 16.6 GB RAM. Fixed: `memory=8GB, processors=4`, and Docker now reports 8.3 GB.
  - The first embedding timings were taken **on battery with the Balanced power plan**, about 8–25% slower. They were re-run plugged in with "Best performance", and only those numbers are used below.
  - Embedding on this CPU is much slower than assumed. Neither model meets the playbook's ≤ ~20 min per 5,000 chunks. Accepted, because embeddings are computed once per blob (ADR-0003). PetClinic (276 naive chunks) takes ~7 min with mxbai.
- Measured (step 5; Ollama 0.40.15, AC power, Best performance, 40-line naive chunks, batch 16):

| Model | Role | Dims | ms/chunk or median s | Gen tok/s | Valid JSON | Spot Recall@5 | Ollama ID |
|---|---|---|---|---|---|---|---|
| nomic-embed-text | embed | 768 | 742.5 ms (61.9 min / 5k) | – | – | 6/10 | 0a109f422b47 |
| mxbai-embed-large | embed | 1024 | 1558 ms (129.8 min / 5k) | – | – | 10/10 | 468836162de7 |
| qwen2.5-coder:7b | chat (local) | – | median 255.4 s (runs 255.4 / 252.3 / 270.1; warm-up 251.5) | 2.9–3.4 | 3/3 | – | dae161e27b0e |
| hosted | chat (hosted) | – | N/A (local-only decision) | – | – | – | – |

**Decision:** embedding = `mxbai-embed-large` (dims 1024, digest 468836162de7…). Chat for eval/demo = local `qwen2.5-coder:7b`. Reason: 10/10 vs 6/10 spot recall is decisive; local chat is the owner's $0 choice despite missing the 30 s bar about 8×. Recorded in ADR-0006; `architecture.md` §4.1 and the M3 migration snippet are updated to `vector(1024)`.
  - Chat bench input: 4,063 prompt tokens (num_ctx 8192, no truncation), about 115 output tokens. **Prompt processing dominates the time**: at ~3 tok/s, generation is only ~35–40 s of the ~255 s. So cutting the context budget is the biggest lever, more than a smaller output.
  - Answer quality: the script sends the first 11 files alphabetically (config, mappers, model), which contain **no** controllers. The model correctly answered `insufficientEvidence: true`, a good abstention. But it listed all 11 evidence IDs while saying none was relevant (over-citation). The M4 citation validator should treat "abstain + cite everything" as zero valid citations.

  - nomic misses: "add a visit for a pet" (returned entities instead of the controller), "pet-type delete cascade" (found the JPA/JDBC impls but not the active Spring Data one), "owners page by page", and "vet mapper" (tiny MapStruct interface).

## 2026-11-07 — M0 (session 2, cont.)
- Measured (step 3.3): petclinic-rest test suite at 5180cc9 on JDK 25: **248 tests, 19 classes, all pass, 56 s** (second run, warm cache). No JDK 21 fallback needed. At ~1 min per run, the 10–15 mutation runs in M5 take ~15 min total.
- Measured (step 3.4), mutation trial: flipping `isEmpty()` → `!isEmpty()` in `UserServiceImpl#saveUser` gave 1 failure + 4 errors, all of them user-creation tests: the controller test plus `shouldAddUser` in each of the 4 persistence-profile variants (H2 JDBC, HSQL JDBC, JPA, Spring Data JPA). ADR-0011's mutation ground truth is feasible.
  - Insight for M5: the service tests come from an abstract base class run once per persistence profile, so one change fans out to N test classes. Test-impact prediction must follow test inheritance (`extends AbstractUserServiceTests`), not just direct references, or recall will look bad for the wrong reason.
- Broke / surprised:
  - The first mutation attempt wasn't saved in the editor, so it ran 248/248 green against unchanged code. Lesson: check with `git diff` before trusting a "nothing failed" result.
  - Switching branches in the target clone under the new global `autocrlf=false` left `ClinicServiceImpl.java` with a CRLF↔LF-only diff on `devmind-pinned`. Restored with `git checkout --`. Always check `git status` in the target before a mutation run.

## 2026-11-07 — M0 benchmark second pass (step 6.6)
- Done by Claude with fresh eyes, using a review sheet that inlined each expected snippet. The owner looked through it but deferred the judgement, so this is **not an independent human review**; say so if the benchmark is cited. All 31 line ranges start and end on method/annotation boundaries.
- Fixed:
  - q004, q007, q014: notes said "either/any one is acceptable", which an automatic Recall@k matcher can't apply. Now the labelled location is the single expected item, and equivalents are "correct in an answer but don't count toward recall".
  - q006: "RFC 7807" → "RFC 9457 (formerly 7807)".
  - q018: added the second visit-creation path, `VisitRestControllerV1#addVisit`.
- Noted: smoke spot-query 1 ("adds a new visit for a pet") actually has two valid answers (`OwnerRestControllerV1#addVisitToOwner` and `VisitRestControllerV1#addVisit`). It doesn't change the M0 result: nomic's top 5 contained neither main class, and mxbai hit the expected one.

## 2026-11-07 — M0 closed
**Exit checklist** (PLAN M0 / playbook 8.1):
- [x] Toolchain verified (versions above)
- [x] Repo on GitHub (Its-Nitin/devmind-ai, public) with `.gitattributes`/`.editorconfig`/`.gitignore`; LF verified
- [x] Targets pinned in `evaluation/targets.yaml`; primary suite passes (248/248, 56 s); mutation trial recorded
- [~] Hosted LLM key + spending cap: **N/A**, local-only decision (ADR-0006)
- [x] Model measurements recorded; embedding model + dimension locked in ADR-0006 (`mxbai-embed-large`, 1024-d)
- [x] 20 labelled questions committed (quota met, 4 holdout, injection fixture committed)
- [x] `docs/threat-model.md` v0 committed
- [x] No product code written

**Summary:** the whole stack runs locally at $0. Retrieval quality looks promising (mxbai 10/10 on naive chunks). Speed is the constraint: ~1.6 s per embedded chunk and ~4.3 min per chat answer on this CPU.

**Deviations from the playbook:**
- Solo workflow: commits go straight to `main`, with no PRs or branch ruleset until a collaborator joins (overrides GIT-3/GIT-4 for now).
- The benchmark second pass was not done independently by a human (see above).
- Generalization repo deferred (cut line #4).

**Next (M1, walking skeleton):** monorepo with a Spring Boot backend and an Angular shell; `deploy/compose.yaml` (pgvector, backend, frontend, Caddy); Flyway V1 baseline; health, ProblemDetail, correlation-ID and JSON logs; quality gates; GitHub Actions CI; login with a seeded user plus `POST/GET /api/projects` with ownership enforced. Lessons to carry forward: watch the space in the repo path for Docker bind mounts, and plan context-budget work early, since prompt processing dominates local chat time.

### Later (ideas parked per GEN-3)
- Index `openapi.yml` (and other spec files) so API-first projects' endpoints are discoverable.
- Benchmark candidate: "Is repository call timing actually active?" (CallMonitoringAspect not registered).

## 2026-11-08 — M1 (session 1): steps 1–3
- Did:
  - Step 1: backend generated from start.spring.io: Spring Boot 4.1.2 (current GA), Java 25, Maven wrapper 3.10.0 (script-only, no wrapper jar). Main class `DevMindApplication`. Added springdoc 3.2.0, pgvector 0.1.6, JSpecify 1.0.1, ArchUnit 1.5.1, Awaitility.
  - Step 2: modules `shared` (OPEN), `identity`, `project`, `job`; every package has an `@NullMarked` `package-info.java`.
  - Step 3: quality gates bound to `verify`: Enforcer (no Lombok/H2, Java ≥ 25, dependency convergence), Error Prone 2.52.0 + NullAway 0.14.3 (`OnlyNullMarked`, NullAway at ERROR) with `-Xlint:all,-processing` and `failOnWarning`, Spotless 3.11.0 + palantir-java-format 2.103.0 (+ sortPom), Checkstyle 14.4.0 (no `System.out`/`printStackTrace`, no star imports, GEN-4 type-name ban, GEN-5 TODO format, GEN-7 length warnings), JaCoCo 0.8.16 (80% line floor on indexing/retrieval/qa/impact internals). `ModularityTest` + `ArchitectureTest` (13 rules: ARCH-3/5/8, API-1, ADR-0005, JAVA-6/9/11, SEC-4, SPRING-1/2).
- Decision: **formatter = palantir-java-format** (ADR-0013 updated).
- Deviation: Initializr's Modulith JPA event registry, observability, actuator and runtime artifacts were dropped. The JPA registry needs an `event_publication` table, which `ddl-auto=validate` rejects; add it back with a Flyway migration in the milestone that first publishes events (ARCH-6).
- Verified (deliberate violations, then removed): NullAway (null return), Error Prone `ReferenceEquality` (fails via `failOnWarning`), ArchUnit (field `@Autowired`, `Instant.now()`), Checkstyle (`*Helper`, bare TODO, `System.out`), Enforcer (Lombok), Spotless (unformatted file).
- Measured: `./mvnw clean verify` 34 s warm (16 tests; context test on `pgvector/pgvector:pg17` ~14 s).
- Broke / surprised:
  - This terminal's PATH lacks `java`; Maven runs via `JAVA_HOME`. The user's shell is fine.
  - Spotless's sortPom option is `nrOfIndentSpace`, not `nrIndentSpace`.
  - ArchUnit `owner(...)` needs `JavaClass.Predicates` (`assignableTo`/`equivalentTo`), not `HasType.rawType`.
  - `archRule.failOnEmptyShould=false` is set, because many rules target layers that don't exist yet.
- Next: step 12 (CI), then step 4 (Testcontainers base + V1 migration).
- Step 12 (same session): `.github/workflows/ci.yml` with `backend` (`./mvnw -B -ntp verify`) and `secrets` (pinned `ghcr.io/gitleaks/gitleaks:v8.30.1` image, full history) jobs; `.github/dependabot.yml` for maven + github-actions. Actions pinned to current majors (checkout v7, setup-java v6). gitleaks-action v3 was dropped after its binary download (old 8.24.3 from the renamed `zricethezav` repo) returned 504 on two runs. The frontend job and npm Dependabot entry come with step 9. Local pre-checks: gitleaks 8.30.1 over all 7 commits found no leaks; actionlint clean.

### Later (ideas parked per GEN-3)
- ~~Load Mockito as a `-javaagent` in Surefire~~ Done 2026-11-08 (modernization pass, below).

## 2026-11-08 — M1 (session 1, cont.): step 4
- Did: `support/TestcontainersConfiguration` (pinned image, reuse when enabled locally), `@IntegrationTest` (SpringBootTest + Testcontainers + `test` profile), `V1__baseline.sql` (`app_user`, `project`, `job`; extensions vector, pg_trgm, citext), `SchemaTest` (DB-2: every FK has a supporting index, via `pg_catalog`), `PersistenceSettingsTest` (SPRING-3). Minimal `application.yaml` (open-in-view off, ddl-auto validate); step 5 extends it. The Initializr sample test is removed.
- Decisions / deviations from the step text:
  - Image pinned to **`pgvector/pgvector:0.8.7-pg17`** instead of the floating `pg17` tag (TEST-4). Step 11's compose file must use the same tag.
  - **Added `job_project_idx`.** In the plan's DDL, `job.project_id` was covered only by the *partial* `job_idempotency_uk` index, which excludes FAILED rows and so can't serve cascade deletes. The schema test treats partial indexes as non-supporting. Verified: dropping the index fails the test with `job.job_project_id_fkey`.
  - Extra DB-3 constraints: `email` ≤ 254 chars, `attempts >= 0`, `max_attempts >= 1`.
- Measured: `./mvnw verify` 36 s, 19 tests. Testcontainers reuse isn't enabled on this machine yet, so each run starts a fresh container (~13–18 s).
- Next: step 5 (errors, correlation ID, logging, clock, profiles).

## 2026-11-08 — M1 (session 1, cont.): step 5
- Did: `Clock` bean (`shared.internal`); `CorrelationIdFilter` (highest precedence, validated `X-Correlation-Id` or a generated UUID, MDC + response header, MDC cleared in `finally`); `ProblemDetailsAdvice` extending `ResponseEntityExceptionHandler`, so Spring MVC's own errors get the same shape. Every problem has `type = https://devmind.dev/problems/<code>`, `code` and `correlationId`; validation adds `errors[]`; unexpected errors are logged at ERROR and answered with a generic 500. Shared public API: `DomainException` → `NotFoundException` (404), `ConflictException` (409). `application.yaml` baseline plus `local` / `test` / `prod` profiles (ECS JSON logs in local/prod, actuator on 8081 in prod, no secrets). `@ConfigurationPropertiesScan` on the app.
- Tests (30 total, 44 s): correlation filter unit tests (echo, generate, reject a log-forging value, MDC cleared on failure); ProblemDetail mappings (unknown endpoint 404, not-found, conflict, validation 400, 500 without internals); actuator exposes only health/info/metrics.
- Decisions:
  - Web infrastructure lives in `shared.internal(.web)`; the `shared` top level stays value types plus the exception bases. Exceptions are classes, not records; ARCH-3's list doesn't name them, so noted here.
  - The catch-all handler rethrows `AccessDeniedException`/`AuthenticationException`, so Spring Security still answers 401/403 (matters from step 6).
  - Test-only controllers are nested in the test class: `TestTypeExcludeFilter` keeps them out of component scanning, so they won't show up in the step 7 authorization matrix.
- Deferred: the "invalid config fails startup" binding test comes with the first `@ConfigurationProperties` record (step 6 seed config). M1 has no tunables yet, and adding one just for the test would be dead code (GEN-5).

## 2026-11-08 — M1 (session 1, cont.): modernization pass before step 6
- Web tests moved from classic MockMvc + Hamcrest `jsonPath` to **`MockMvcTester`** (AssertJ, Spring 6.2+/7), matching TEST-7. ProblemDetail bodies are checked with `bodyJson().isLenientlyEqualTo(...)`. Actuator checks are now `@ParameterizedTest`s.
- **NullAway JSpecify mode** on (`-XepOpt:NullAway:JSpecifyMode=true`), the mode Spring 7 is checked with; it also covers nullness inside generics. The existing code compiled clean.
- **Mockito loaded as `-javaagent`** (`dependency:properties` + `@{argLine}` after JaCoCo) instead of self-attaching, which the JDK is phasing out (JEP 451). `-Xshare:off` on the test JVM only silences the CDS notice the agent causes.
- Result: 37 tests, `./mvnw verify` 32 s, no JVM/Mockito warnings left.
- **Modernizer Maven Plugin 3.5.0** added as a `verify` gate (JAVA-15, MUST; standards v1.2): legacy APIs the Java 25 target has replaced fail the build, in main and test bytecode. Probe: `Collections.emptyList()` → "Prefer java.util.List.of()", build failed. `Vector` and charset-name probes were already caught earlier by Error Prone (`JdkObsolete`, `StringCharset`); the two gates overlap.
- Still unverified: springdoc 3.2.0 on Boot 4.1 (step 8).

## 2026-11-10 — M1 (session 2): step 6
- Did: identity public API `UserId` (record) and `CurrentUser` (interface, backed by the security context; throws an AuthenticationException → 401 when nobody is signed in). `AppUser` entity + repository (email lookups `CAST(:email AS citext)`: a varchar parameter would make Postgres compare as text, case-sensitively). `UserRegistrationService` (delegating encoder → `{bcrypt}`), `AppUserDetailsService`, `AuthenticatedUser` principal (serializable plain values, hash erased after login). `SecurityConfiguration`: `csrf.spa()` with our own `CookieCsrfTokenRepository` (SameSite=Lax), session fixation `changeSessionId`, form login/basic/request cache off, entry point and access-denied handler delegate to the MVC exception resolver so 401/403 are the usual ProblemDetails. `AuthController`: `POST /api/auth/login`, `GET /api/auth/me`, `GET /api/auth/csrf` (204, issues the cookie). `UserIdMdcFilter` adds `userId` to the MDC (OBS-1). `local` seed user via validated `DemoUserProperties` (`DEVMIND_SEED_PASSWORD`, 12–72 chars, fail fast). Session cookie `HttpOnly; Secure; SameSite=Lax`, cookie-only tracking; `local` turns off `Secure` for plain-http localhost.
- New problem codes: `invalid-credentials` (401, same for unknown email and wrong password), `unauthenticated` (401), `invalid-csrf-token` (403), `access-denied` (403).
- Decisions / deviations from the step text:
  - **Logout is Spring Security's `logout()` filter** on `POST /api/auth/logout` (204, invalidates the session, clears the context and the CSRF token), not a controller method (GEN-9). So it won't appear in the step 7 handler matrix or the step 8 OpenAPI spec; the frontend calls it directly.
  - **`ProblemDetailsAdvice` now handles `AuthenticationException`/`AccessDeniedException`** instead of rethrowing them (step 5 behaviour). No method security is used; if it ever is, an anonymous AccessDenied must become 401.
  - **Non-health actuator endpoints need a login** rather than `denyAll`: SPRING-5's exposure test needs to tell 200 from 404, and in `prod` actuator is only on internal port 8081 anyway.
  - The controller login applies the session strategy itself (new session ID + CSRF token rotation) and reads the rotated token, so the login response already carries a new XSRF-TOKEN cookie.
  - `CurrentUser` is a singleton reading the thread-bound security context, not a request-scoped proxy.
  - The registration existence check isn't race-proof; the unique index is the backstop (no public sign-up in v1).
  - The config-binding test deferred from step 5 is `DemoUserPropertiesTest` (`ApplicationContextRunner`).
- Broke / surprised:
  - MockMvc's `csrf()` post-processor permanently swaps the CsrfFilter's repository for a session-backed test one in the shared context, so cookie assertions under MockMvc see no XSRF-TOKEN. And a `MockHttpSession` has no requested session ID, so the fixation strategy doesn't rotate it. Both are tested in `SessionCookieTest` instead: `RANDOM_PORT` + JDK `HttpClient` over loopback, the same flow as curl (csrf → login with cookie + header → me), plus cookie flags, token rotation and session ID rotation.
  - Bash heredocs containing apostrophes fail in this agent shell; use the editor tools for Java sources.
- Measured: 55 tests, `./mvnw verify` green.
- Not verified by hand: curl against a running `local` app. That needs the compose database (step 11); `SessionCookieTest` automates the same flow.
- Next: step 7 (project module, `ProjectAccessPolicy`, authorization matrix). It needs a way to resolve `UserId` → `app_user.id` for `project.owner_id`, which is a public identity API decision.

## 2026-11-10 — M1 (session 2, cont.): step 7
- **Decision (owner's call): `project.owner_id` is a uuid FK on `app_user(public_id)`** (`V2__project_owner_public_id.sql`), not a bigint FK on `app_user(id)`. The project module only knows the public `UserId`, so ownership checks are one indexed query with no call into identity, and no internal ID crosses a module API. This is the one FK that departs from the "bigint FKs internally" convention; architecture §4's table is updated. Rejected: an identity lookup API (internal IDs across modules, an extra query per request) and an internal ID in `CurrentUser`.
- Did: public API `ProjectId`, `SourceType`, `ProjectSummary`, `ProjectAccessPolicy` (`long requireOwner(UserId, ProjectId)`, 404 `project-not-found` for missing and foreign alike), `ProjectApi`. `Project` entity + repository (every query filters by owner). `OwnerProjectAccessPolicy`; `ProjectService` (create, listOwn, get, delete; get/delete call the policy first, SEC-1). `ProjectController`: `POST /api/projects` → 201 + relative `Location`, `GET /api/projects?page&size&sort`, `GET/DELETE /api/projects/{projectId}`. New codes: `project-not-found` (404), `project-name-taken` (409).
- Paging (API-6): explicit `page ≥ 0`, `1 ≤ size ≤ 100`, `sort` matching `^(name|createdAt)(,(asc|desc))?$` via Bean Validation, so anything else is a 400 `validation-failed`, not a silent clamp. The `ProjectSort` whitelist maps to entity properties, with `id` as a tie-breaker for stable pages. Responses are Spring Data's `PagedModel` (`content` + `page`), the stable JSON shape for pages (GEN-9), instead of a hand-written page DTO.
- Duplicate names: an existence check, plus `saveAndFlush` catching `DataIntegrityViolationException` for the race. Known gap: an FK violation (owner deleted mid-request) would also map to 409; it's harmless in v1.
- `AuthorizationMatrixTest` (TEST-9): enumerates `requestMappingHandlerMapping`, and each `method + pattern` must be in `MATRIX` as `Public`, `Authenticated` or `OwnerScoped(pathVariable)` (sealed). Dynamic tests: public endpoints admit anonymous callers; every other endpoint gives anonymous callers 401 `unauthenticated`; owner-scoped endpoints give a stranger 404 first, then the owner 2xx. Infrastructure rows: `* /error` and springdoc's four endpoints, all `Authenticated` until step 8 decides. Verified: adding an unclassified `GET /api/projects/{projectId}/probe-unclassified` fails the test and names the endpoint; the probe was removed afterwards.
- `support/TestUsers` registers a unique user and signs in through the real login endpoint (a genuine session, not a mock principal).
- Measured: 88 tests, `./mvnw verify` green. The matrix test's own cases take about 6 s; the first integration class pays container and context startup.
- Next: step 8 (OpenAPI contract). Decide whether `/v3/api-docs` and swagger-ui are public, and update the matrix rows.
- Idiom audit of steps 6–7 (owner request). Modernizer (JAVA-15) passed on main and test bytecode; it only sees legacy JDK and library APIs, so framework idioms were checked by hand against GEN-9. Fixed: `SessionCookieTest` moved from the JDK `HttpClient` with hand-parsed `Set-Cookie` headers to Spring Framework 7's **`RestTestClient`** (`@AutoConfigureRestTestClient`, new test dependency `spring-boot-resttestclient`), with built-in `expectCookie().httpOnly/secure/sameSite` assertions; it also gained an explicit check on the XSRF cookie's flags. Smaller: `Comparator.comparing` in the matrix test, and the `ProjectSort` lookup over `values()` (`Sort.Direction.fromString` is already case-insensitive). Kept on purpose: the `sessionFixation(f -> f.changeSessionId())` lambda; the method reference goes through a generic inner class and trips `-Werror` raw-type lint. 89 tests, `verify` green.

## 2026-11-11 — M1 (session 3): step 8
- **Decision (owner's call): the API docs need a session.** `/v3/api-docs` and swagger-ui stay `Authenticated` in the matrix; swagger-ui works after logging in, because the browser sends the session cookie.
- Did: springdoc spec at `/v3/api-docs`, OpenAPI 3.1, `/api/**` only, `application/json` by default, keys sorted so the file diffs cleanly. `OpenApiContractTest` signs in, fetches the spec through `MockMvcTester`, pretty-prints it with Boot's Jackson 3 `JsonMapper` (LF, final newline) and compares it with the committed `backend/openapi.json`. `-DupdateOpenApi=true` rewrites the file. Verified: raising a DTO's `@Size(max)` without regenerating fails the build with the regenerate command in the message; reverted.
- Making the spec complete enough to generate frontend types from:
  - **Errors by rule** (`shared.internal.web.OpenApiConfiguration`, runs last): every operation gets 500. Any operation with a body or parameters gets 400, a path variable adds 404, and every 4xx/5xx response's body is `application/problem+json` with a `Problem` schema. `Problem` is a doc-only record mirroring what `ProblemDetailsAdvice` emits. `override-with-generic-response` is off, since springdoc can't read statuses from our `ResponseEntity<Object>` handlers. Operation-specific errors go on the handler: 409 `project-name-taken`, and the login's 401 `invalid-credentials`.
  - **Security by rule** (`identity.internal.web.SecurityApiDocumentation`, runs first): `session` (JSESSIONID cookie) and `csrf` (`X-XSRF-TOKEN` header) schemes. The public operations are read from `SecurityConfiguration.PUBLIC_ENDPOINTS`, now the single list the filter chain's `permitAll` uses too. Everything else gets `session` + 401, and unsafe methods add `csrf` + 403.
  - **Logout is in the spec after all**, unlike what step 6 expected: the customizer adds `POST /api/auth/logout` (204), since springdoc can't see the filter.
  - **Nullability:** springdoc's `java-nullable-property-customizer` (off by default) turns JSpecify `@Nullable` into `type: [x, "null"]`. Since the code is `@NullMarked`, every other property is made `required`, so generated TS types aren't all optional (`defaultBranch`, `correlationId`, `errors` and `instance` stay optional).
  - Real statuses instead of springdoc's guessed 200s: `@ResponseStatus(NO_CONTENT)` on delete and csrf, which now return `void`, and `@ApiResponse` 201 with a `Location` header on create. The `CsrfToken` argument is `@Parameter(hidden = true)`.
  - **Operation IDs are the method names**, so they're now explicit and unique (`createProject`, `listProjects`, `getProject`, `deleteProject`, `logIn`, `getCurrentUser`, `issueCsrfToken`, `logOut`). springdoc would silently suffix a duplicate such as `list` with `_1`.
- Broke: a lambda over swagger-models' raw `Map<String, Schema>` entries tripped `-Werror` rawtypes. I iterate the key set instead.
- Measured: 90 tests, `./mvnw verify` green (Modernizer included).
- Next: step 9 (frontend scaffold); its `api:types` script consumes `backend/openapi.json`.

## 2026-11-11 — M1 (session 3, cont.): step 9
- Did: `frontend/` from Angular CLI **22.2.6** (Node 26 LTS via `.nvmrc`, TypeScript 6.0, Vitest 5, standalone, no `zone.js`). angular-eslint 22.6, Prettier (`printWidth: 120`), PrimeNG 22.1 + `@angular/cdk` + Aura theme via `providePrimeNG`, primeicons. Dev proxy `/api` → `:8080` wired into `ng serve`. `api:types` generates `src/app/core/api/schema.d.ts` from `backend/openapi.json` (and runs Prettier on it, so the output is stable). Required fields come out non-optional, nullable ones as `?: T | null`, thanks to step 8's nullability rule.
- Angular 22 defaults that change the step text: **zoneless and OnPush are the defaults**, so `app.config.ts` needs no `provideZonelessChangeDetection()` and components no `changeDetection`. `prefer-on-push-component-change-detection` now flags opting *out* (`Eager`/`Default`), so it stays on as the FE-2 gate. `tsconfig` spells out `strict` and `strictTemplates` anyway (FE-1), plus `noUncheckedIndexedAccess` and `exactOptionalPropertyTypes`.
- ESLint (`eslint.config.mjs`, ESM): typescript-eslint **`strictTypeChecked` + `stylisticTypeChecked`** with `projectService`, angular-eslint TS + template recommended + template accessibility, plus `prefer-inject`, `prefer-signals`, `prefer-output-emitter-ref`, `no-uncalled-signals`, `no-implicit-take-until-destroyed`, template `prefer-control-flow`/`no-any`/`button-has-type`. SEC-9: `no-restricted-syntax` bans `bypassSecurityTrust*` in TS and `[innerHTML]`/`[outerHTML]` bindings in templates. FE-5: `eslint-plugin-boundaries` 7 with elements `app`, `bootstrap`, `core`, `shared`, `feature` (captured name); a feature may import core, shared and its own files only, shared only shared, core only core/shared. `no-extraneous-class` allows decorated empty classes.
- Verified with throwaway probes (deleted): a feature importing another feature, shared importing core, `ChangeDetectionStrategy.Eager`, `bypassSecurityTrustHtml`, `[innerHTML]` and `any` each produced exactly one error; feature → core and feature → own file passed.
- Broke / surprised:
  - `openapi-typescript` 7.13 (latest, also `next`) declares `peer typescript ^5.x`; Angular 22 uses TS 6. Solved with a package-scoped npm `overrides` entry (`"openapi-typescript": { "typescript": "$typescript" }`) rather than a global `--legacy-peer-deps`; output verified. Remove the override once it supports TS 6.
  - npm 11 no longer runs dependency install scripts unless approved (esbuild, lmdb, @parcel/watcher, msgpackr-extract). Build, tests and `ng serve` don't need them (prebuilt optional binaries), so none are approved.
  - eslint-plugin-boundaries 7 element patterns match folders, not files: `src/app` and `src` work because a file belongs to its deepest matching element.
- CI: new `frontend` job: `npm ci`, regenerate API types and `git diff --exit-code` (stale types fail), `format:check`, `lint`, `test`, `build`. Dependabot: npm `/frontend`, weekly, with Angular and PrimeNG packages grouped so they upgrade together.
- Folders: only `core/api` exists so far; git doesn't track empty folders, so `core/auth`, `core/http`, `shared/` and `features/projects` arrive with their code in step 10.
- Measured: lint, 1 test, build (initial 398 kB raw / 90 kB transfer) and format check all green after a clean `npm ci`.
- Next: step 10 (login page, auth store, projects feature).

## 2026-11-14 — M1 (session 4): step 10
- Did: `core/http`: `correlationIdInterceptor` (a `crypto.randomUUID()` per request in `X-Correlation-Id`), `errorInterceptor`, `ErrorNotifier` (PrimeNG `MessageService`, `<p-toast>` in the shell), `problem.ts` (narrows an `HttpErrorResponse` to the generated `Problem`, builds the toast text with the correlation ID). `provideHttpClient(withInterceptors(...), withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }))`. `core/auth`: `AuthApi`, `AuthStore` (`user`, `isAuthenticated`, `initialize`/`loadCurrentUser`/`logIn`/`logOut`), functional `authGuard`. `provideAppInitializer` runs csrf → me before the first route. `features/login` (login page) and `features/projects` (lazy `projects.routes.ts` that provides `ProjectsApi` + `ProjectsStore` at route level; `projects-page` container; presentational `project-list` and `create-project-dialog`, FE-6). Shell header shows the user's name and a sign-out button.
- Deviations from the step text:
  - **File names follow FE-14** (`projects-page.ts`, `auth-store.ts`, `projects-api.ts`), not the step's `*.component.ts` / `projects.store.ts`.
  - **Signal forms instead of reactive forms** for login and create-project: `form()`, `[formRoot]`, `[formField]`, `submission.action` are `@publicApi 22.0` (stable), and keep form state in signals like the rest of the app (FE-3, GEN-9 latest idioms).
  - **The interceptor doesn't handle every 401.** An `HttpContextToken` (`LOCALLY_HANDLED_STATUSES`) lets `AuthApi` mark 401 as an expected answer on `GET /api/auth/me` (signed out) and `POST /api/auth/login` (wrong credentials), so app start doesn't bounce to `/login` and a typo doesn't toast. Any other 401 forgets the user (`AuthStore.forgetUser`) and navigates to `/login`.
  - Logout clears the XSRF cookie server-side, so `AuthStore.logOut` fetches a fresh token right away; otherwise the next login gets 403.
  - Login messages: "Email or password is incorrect." for 401, "Could not sign in. Try again." otherwise; neither says whether the email exists.
  - Projects list loads the first page only (`size=100`, the server max, `createdAt,desc`) and says how many older projects are hidden; paging comes when someone has 100+ projects. Delete has no UI yet (the step doesn't ask for it).
- PrimeNG 22: `pButton` no longer has `label`/`loading` inputs, so buttons are `<button pButton type=…>` with text content and `[disabled]`. The source-type picker is a native `<select>` (styled `p-inputtext`) bound with `[formField]`.
- Lint caught: `get<void>` (`no-invalid-void-type`, now `get<null>` for 204s) and comparing `error.status` with the `HttpStatusCode` enum (`no-unsafe-enum-comparison`); one `isUnauthorized()` helper in `problem.ts` compares with `401`.
- Tests (Vitest, `HttpTestingController`): `AuthStore` through the real error interceptor (login ok / 401 / 500, initialize restores session / 401 / server down, logout refreshes csrf), `errorInterceptor` (unexpected 401 → forget + `/login`, expected 401 untouched, Problem → toast with correlation ID, non-Problem fallback), `correlationIdInterceptor`, `authGuard`, `ProjectsStore` (load, hidden count, load error, create prepends, create 409). 20 tests.
- Verified end to end: throwaway `pgvector/pgvector:0.8.7-pg17` container, backend on `local`, `ng serve`, then the browser's request sequence with curl through the dev proxy (cookies + `X-XSRF-TOKEN`): csrf 204 → me 401 → bad password 401 `invalid-credentials` → login 200 → create 201 → duplicate 409 `project-name-taken` → list 200 with the project → `/projects` deep link served → logout 204 → me 401. Container removed afterwards. Not done: clicking through the UI in a real browser.
- Measured: format, lint, 20 tests, production build green. **Initial bundle 637 kB raw / 140 kB transfer** (step 9: 398 kB), over Angular's default 500 kB *warning* budget (error at 1 MB); about 370 kB of it is PrimeNG + the Aura theme. Open question for the owner: raise the warning budget or trim PrimeNG from the shell.
- Next: step 11 (Docker Compose stack).

## 2026-11-14 — M1 (session 4, cont.): step 11
- Did: `backend/Dockerfile` (multi-stage: `eclipse-temurin:25.0.5_8-jdk-noble` runs `./mvnw -DskipTests package` with a BuildKit cache for `~/.m2`, then `java -Djarmode=tools … extract --layers`; runtime `eclipse-temurin:25.0.5_8-jre-noble`, UID 10001, layers copied least- to most-changed, `java -XX:MaxRAMPercentage=75 -jar app.jar`). `frontend/Dockerfile` (`node:26.13.1-alpine` → `npm ci && npm run build`; runtime `caddy:2.11.9-alpine` with `/srv` + Caddyfile). `deploy/Caddyfile` (zstd/gzip, `/api/*` → `backend:8080`, SPA fallback, nosniff / no-referrer / DENY, `Server` header dropped). `deploy/compose.yaml`, `deploy/.env.example`, `.dockerignore` for both images. Dependabot now covers the Dockerfiles (`docker`) and `deploy/` (`docker-compose`).
- Choices:
  - **Dockerfile, not Buildpacks**: explicit, readable, and non-root is one line. Image tags are pinned to exact versions found by pulling the floating tags and reading `JAVA_VERSION`/`NODE_VERSION`/`CADDY_VERSION`.
  - **The Caddyfile stays in `deploy/`** and reaches the web image through a Compose `additional_contexts: { deploy: . }` named context (`COPY --from=deploy`), so the frontend build context stays `frontend/`. Standalone: `docker build --build-context deploy=deploy frontend`.
  - **`demo` is an add-on profile owning the seed config** (`application-demo.yaml`); `DemoUserSeeder`/`DemoUserProperties` are `@Profile("demo")`. `local` includes it through `spring.profiles.group.local: demo` instead of a second copy of the seed block. Compose runs `prod,demo`.
  - **Required env vars fail fast in Compose** (`${DB_PASSWORD:?…}`, `${DEVMIND_SEED_PASSWORD:?…}`), not only at Spring startup.
  - **Backend healthcheck** without curl/wget (the JRE image has neither): bash `/dev/tcp` to the internal actuator port 8081. `web` waits for `backend` healthy, `backend` for `db` healthy, so `up --wait` means usable.
  - **Left out `DEVMIND_AI_OLLAMA_BASE_URL`** from the step text: nothing reads it until the AI module exists (M3); add it then.
- Verified (separate project `-p devmind-verify`, throwaway env file, torn down with `down -v --rmi local`): build 71 s cold; `up --wait` all healthy; at `http://localhost:8088`: index 200, `/projects` deep link 200, security headers present, zstd encoding; csrf 204 → login 200 → me 200 → create 201 → list shows it. Ports 8080/8081 not reachable from the host; `/actuator` and `/v3/api-docs` through Caddy get the SPA, not the backend. Container runs as `uid=10001`. Backend restart: the seeder is idempotent (one "Seeded" line across two starts). `local` against the compose DB logs profiles `local`, `demo`. `./mvnw clean verify` green (90 tests); gitleaks clean.
- Noted:
  - The session cookie is `Secure` under `prod`, served over plain http at `localhost:8088`. Chrome and Firefox treat `localhost` as a secure context and accept it; Safari may not. HTTPS (Caddy's internal CA or a real host) is a deployment question for later, not M1.
  - Image build repeats the known npm install-script warnings (step 9) and the 500 kB budget warning (step 10).
- Next: step 13 (close out M1). The CLAUDE.md "Commands" section is still a placeholder; fill it there.

## 2026-11-15 — M1 closed
- **Exit met** (PLAN.md M1): the owner ran a fresh `docker compose up` in their own browser at `localhost:8088`: login, create a project, F5 keeps the session (the `Secure` cookie is accepted on localhost), duplicate name shows the 409 toast, sign out. CI on `main` is green for the last commit with every gate enforcing (backend `verify`, frontend types/format/lint/tests/build, gitleaks full history). Exit checklist in the M1 tracker all ticked, with the evidence in earlier entries: deliberate-violation probes (steps 3, 9), `AuthorizationMatrixTest` failing on an unclassified endpoint (step 7), committed `openapi.json` + types checked in CI (steps 8, 9).
- Wrap-up: CLAUDE.md "Commands" filled in (backend gates, OpenAPI + types regeneration, dev run, compose) and the current milestone set to M2. README gained the status and a "Run it" section.
- What fought back in M1:
  - **Error Prone + NullAway on JDK 25** worked without the planned JDK 21 toolchain fallback, but only with the `--add-exports`/`--add-opens` lines for `jdk.compiler` in `backend/.mvn/jvm.config`; NullAway in JSpecify mode came free once `@NullMarked` was everywhere.
  - **CSRF with a SPA:** `csrf.spa()` plus our own `CookieCsrfTokenRepository` (SameSite), the JSON login rotating the token itself, MockMvc's `csrf()` silently replacing the repository (cookie tests had to move to a real server), logout clearing the cookie so the SPA must fetch a new token, and the SPA's expected 401s (`/me`, `/login`) needing to bypass the global 401 redirect.
  - **Zoneless/OnPush as Angular 22 defaults** made several step instructions obsolete (no providers to add); the lint rule became "never opt out". Signal forms being stable in 22 replaced reactive forms.
  - Tooling on this machine: Java, Node and the Docker CLI are not on the agent's PATH (see the commands in each session); a space in the repo path didn't break Docker build contexts.
- Time: not tracked per step; spread over sessions on 2026-11-08, -10, -11, -14 and -15.
- Carried into M2 (not blockers):
  - Initial bundle 637 kB over the 500 kB warning budget (step 10); deferred, the owner still decides between raising the budget and trimming PrimeNG.
  - Open Dependabot PRs to triage: TypeScript 7 (check Angular 22 supports it before merging), `eclipse-temurin` 26 (a non-LTS Java; the project targets 25 LTS, so likely ignore and add a Dependabot `ignore` for non-LTS majors), jsdom 31 (major), plus routine Angular/PrimeNG/typescript-eslint bumps.
  - HTTPS for compose (only needed beyond localhost); `DEVMIND_AI_OLLAMA_BASE_URL` arrives with the AI module.
- Next: tag `m1`, then M2 (ingestion and structural index), starting from docs/milestones/M2-ingestion-and-indexing.md.

## 2026-11-15 — M2 (session 1): step 1
- Did: `V3__indexing_core.sql` with `repository_revision`, `file_blob`, `revision_file`, `code_symbol`, `code_chunk`, following the step-1 SQL. `SchemaTest` now also checks the new tables, that deleting a project cascades through revision → files → blob → symbols → chunks (architecture §4.2), the revision idempotency key, lowercase-hex SHA, symbol line ranges and the symbol-kind CHECK. The generic FK-index check covers the new FKs with no changes needed.
- Choices / deviations:
  - **`V3`, not `V2`**: `V2__project_owner_public_id.sql` was taken in M1 step 7. Fixed the file name in the M2 plan.
  - **Added CHECKs the step SQL didn't have** (DB-3): `index_config_hash` and `content_hash` must be 64 lowercase hex characters; `redaction_count`, `token_estimate` ≥ 0; `revision_file.path` at least 1 character.
  - **`redaction_count` on `file_blob`, `chunker_version` on `code_chunk`** (as in the step SQL). Architecture §4.1 had `redaction_count` on `code_chunk` instead; updated it. Redaction has to happen before the blob content is stored, so the blob is where the count belongs.
- Verified: `./mvnw clean verify` green (93 tests).
- Next: step 2 (job worker).

## 2026-11-15 — M2 (session 1, cont.): step 2
- Did: the `job` module. Public API `JobApi` (submit, get), the `JobHandler` SPI with `JobContext` (payload, progress, heartbeat) and a sealed `JobOutcome`, plus `JobId`, `JobType`, `JobStatus`, `JobRef`, `JobView`, `JobProgress`. Internals: `JobQueries` (JdbcClient: insert-if-absent, `SKIP LOCKED` claim, lease-guarded writes), `JobRunner` (claim, run, record outcome), `RunningJob` (the context), `JobWorker` (`SmartLifecycle` poller on virtual threads, `Semaphore` for `max-concurrent`), `RetryBackoff` (exponential with equal jitter), `JobProperties` (`devmind.job.*`), and `GET /api/jobs/{jobId}`. A `devmind.jobs{type,status}` counter (OBS-4) and `jobId` in the MDC while a handler runs (OBS-1).
- Choices / deviations from the step text:
  - **`ProjectAccessPolicy.requireOwner(UserId, long)`** added (owner's decision): a job row only has the internal `project_id`, and ADR-0010 wants job lookups to go through the policy. One indexed `exists` query. The `job` module now depends on `project` and `identity` (no cycle); architecture §3.2 updated.
  - **A stranger gets `job-not-found`, not `project-not-found`.** Otherwise the code would reveal that the job exists (API-8). `JobService` translates the policy's 404.
  - **`JobOutcome handle(JobContext)` instead of `void handle`.** A sealed `Succeeded | RetryableFailure | PermanentFailure` makes "never retry validation errors" (ERR-3) the handler's explicit choice (JAVA-7, ERR-2). A thrown exception counts as retryable `unexpected-error`, and only the exception's class name is stored (ERR-5).
  - **Lease fencing by attempt number.** Every write while running is guarded by `locked_by` *and* `attempts`, so a worker whose lease expired can't overwrite the attempt that took over, even when the same worker reclaimed its own job.
  - **Expired lease on the last attempt → `FAILED` (`lease-expired`).** The claim only reclaims expired jobs with attempts left; a sweep each poll fails the rest. Without it, a job whose worker died on attempt 3 would sit in RUNNING forever, or be run a fourth time.
  - **The worker only claims types that have a handler.** No handler exists yet in production, so nothing is claimed until indexing registers one in step 7.
  - **Left out `isCancelled()`.** Nothing cancels jobs yet (no endpoint in M2); add it with the first cancel use case. `CANCELLED` stays in the enum because the CHECK constraint has it.
  - **JdbcClient, not JPA, for `job`.** ADR-0005 lists jobs under JPA aggregates, but the claim and every lease-guarded write are native SQL, so one style for the module is simpler.
  - `max-attempts` comes from config (default 3) instead of the column default (GEN-6).
- Found while testing: **Jackson 3 turned `FAIL_ON_NULL_FOR_PRIMITIVES` on by default**, so the `progress` column's `{}` default couldn't bind to `JobProgress`'s `long`s. `JobQueries` reads the column through its own `StoredProgress` record with nullable fields instead of relying on mapper defaults.
- Tests: `JobRunnerTest` (success with payload and progress; retry with growing backoff, then FAILED at max attempts; permanent failure; exception message not stored; 4 workers draining 20 jobs with no duplicate claims; expired lease reclaimed and the stale attempt fenced; same-worker fencing; heartbeat; last-attempt lease expiry; progress throttle), `JobServiceTest` (idempotent submit, also with 8 concurrent submits; new job after FAILED; key validation; owner-only get), `JobWorkerTest` (start, drain, stop; never more than `max-concurrent` running), `JobControllerTest`, `RetryBackoffTest`, `JobOutcomeTest`, and a `JobOwnerScoped` row in the authz matrix. Time is a `MutableClock` bean; waits use Awaitility (TEST-5). The test profile sets `worker-enabled: false`.
- Verified: `./mvnw clean verify` green (135 tests); OpenAPI and frontend types regenerated; frontend `format:check` clean.
- Next: step 3 (ingestion: local and remote source snapshots).

## 2026-11-16 — M2 (session 2): step 3
- Did: the `ingestion` module. Public API `SourceSnapshotProvider.open(ProjectSource, ref)` returning a sealed `SnapshotOutcome` (`Opened(SourceSnapshot)` | `Failed(SourceProblem)`), plus `SourceSnapshot` (commit SHA, `SnapshotStats`, lazy `Stream<SourceFile>`), `SourceFile` and `SourceProblem` (stable code, retryable flag, fixed user-safe detail). Internals: `RefName` and `RemoteUrlPolicy` (pure rules), `SnapshotLimits`, `IngestionProperties` (`devmind.ingestion.*`), `SourceSnapshotService` (the boundary: every rejection becomes `Failed`, logged once), and the JGit adapter: `LocalRepositories` (SEC-2), `RemoteCloner` + `CloneGuard` + `CloneWorkspace` (SEC-3), `CommitSnapshots`/`GitSnapshot` (tree walk, lazy blobs), `JGitIsolation`. New dependency: JGit 7.8.1 (latest, GEN-9).
- Choices / deviations from the step text:
  - **A result type, not a `SourceRejected` exception.** ARCH-3 keeps exception classes out of public APIs, and a rejected source is an expected outcome (ERR-2). `SourceProblem.retryable()` lets step 7 map straight to `JobOutcome.retryable/permanent`, and `detail()` is fixed text, safe to store (ERR-5).
  - **`SourceSnapshot` is an interface, not a record.** It owns an open repository (and a clone directory), so a public record constructor would invite misuse. Blobs load only when the stream reaches them.
  - **The per-file size cap is applied in the snapshot**, from the object header, so an oversized blob is never loaded into memory (T3). Step 4 should take `stats().oversizedSkipped()` as `excluded.size` instead of checking again. As a result, an oversized file under `target/` counts as size, not path.
  - **Invalid paths are skipped and counted** (`invalidPathsSkipped`): longer than the `revision_file.path` limit (1000), or with empty, `.` or `..` segments, which only a crafted tree can contain. `SourceFile.isValidPath` is the single rule.
  - **Local confinement goes beyond `toRealPath()`.** Git can redirect reads through a `.git` gitfile, a worktree `commondir`, `objects/info/alternates`, or symlinks inside `.git`. The resolved Git and common dirs must be inside the root, contain no symlinks or junctions, and have no alternates. A lexical root check runs first, so a rejection never reveals whether a path outside the roots exists.
  - **JGit is cut off from the host's Git setup** (`JGitIsolation`, a `SystemReader` delegate): no system, user or JGit config, and no `GIT_*` env. Found while reading JGit: looking up the system config makes JGit run `git` on POSIX (SEC-4), and a user `url.<base>.insteadOf` would rewrite URLs past the host allow-list (SEC-3).
  - **Remote: `ls-remote` first, then fetch into a fresh bare repo.** A missing ref fails before any download. Branches and tags are fetched at depth 1. A SHA ref fetches every branch and tag with full history, bounded by the same limits, until M5 adds fetch-by-SHA. Bare instead of `setNoCheckout`, so there's no working tree at all. No submodules, no extra tags.
  - **No redirects at all** (`http.followRedirects=false`), stricter than "no cross-host redirects". A renamed GitHub repository must be entered with its new URL.
  - **Clone limits are enforced mid-transfer.** `CloneGuard` is the fetch's `ProgressMonitor`. JGit polls `isCancelled()`, which checks the wall-clock deadline and, every 500 ms, the clone's size on disk. Both are checked exactly again afterwards. It uses `System.nanoTime()` (elapsed time, not a stored instant, so JAVA-9 doesn't apply). JGit's own timeout bounds each read.
  - **Auto-gc is off in clones** (`gc.auto=0`). Found in the Linux run: after a fetch, JGit starts a background gc that races the clone's deletion and can leave files behind.
  - **Dropped JGit's fsck (`setCheckFetchedObjects`).** Its connectivity check fails on shallow clones ("peer did not supply a complete object graph"). Nothing is checked out, so the path rule in the tree walk covers what fsck would have protected.
  - **No test-only `file`-scheme flag in production config.** `RemoteCloner` trusts its caller and is tested directly against `file://` bare repos; `SourceSnapshotServiceTest` shows a `file://` URL can't get past the service.
  - **Refs are a conservative subset of Git names** (ASCII letters, digits, `._/-`, Git's segment rules). Resolution looks up exactly `refs/heads/x`, then `refs/tags/x`, then a SHA prefix (7-40 hex), so revision expressions (`main~1`, `HEAD@{1}`, `:/text`) are never evaluated.
  - **Clones go to a dedicated `devmind.ingestion.clone-directory`**, swept at startup, instead of the shared temp dir. One instance per directory.
  - Config: `allowed-roots` is empty by default (LOCAL rejected until configured); `local` lists `D:/dev/targets`; `test` uses `java.io.tmpdir`, where `@TempDir` fixtures live. Each root must exist at startup.
  - The `ingestion → job` arrow in architecture §3.2 isn't used yet.
- Tests: `RefNameTest`, `RemoteUrlPolicyTest` (29 rejected URL shapes, normalization, allow-list entry checks), `LocalSourceTest`:
  - `Contents`: branch, lightweight and annotated tags, full and short SHAs, branch over tag, committed vs dirty tree, bare repo, symlink and submodule entries, size, path and file-count limits, a crafted `..` tree, filters/fsmonitor/hooks never run (T14), close semantics.
  - `Confinement`: outside root, traversal, relative and NUL paths, no existence oracle, missing and non-repository paths, gitfile out vs in, alternates, root symlink, symlink in `.git`, no roots, bad roots at startup.

  Also `RemoteSourceTest` (shallow bare clone with redirects off, tag, SHA, clone deleted on close, missing ref and repository, size and time limits, nothing left behind), `CloneGuardTest`, `CloneWorkspaceTest`, `SourceSnapshotServiceTest`, `SourceSnapshotProviderIntegrationTest` (wiring and binding), `SourceFileTest`. `GitFixture` (test support) writes commits straight into the object DB, so SHAs are deterministic and symlink and submodule entries work on any OS. Step 7 reuses it.
- Verified: `./mvnw clean verify` green (273 tests; 2 skipped on Windows, the symlink escapes, because this account can't create symlinks). The Git adapter tests also ran in an `eclipse-temurin:25-jdk` Linux container, where the symlink tests run and pass. No API change, so OpenAPI and frontend types are unchanged.
- Next: step 4 (filters, normalization, secret redaction).

## 2026-11-16 — M2 (session 2, cont.): step 4
- Did: the ingestion pipeline. Public API `IngestionPipeline` (`rulesVersion()`, `process(SourceFile)` → sealed `FileOutcome` = `Kept(NormalizedFile)` | `Excluded(ExclusionReason)`), `NormalizedFile` (path, language, content, SHA-256, size, line count, redactions, encoding fallback), `ExclusionReason` (with stats keys such as `secretFile`), and `IngestionStats` (immutable fold: `of(SnapshotStats)` then `add(outcome)`, kept + excluded = total). Pure rules in `internal.domain`: `PathRules`, `BinarySniffer`, `TextNormalizer`, `SecretRedactor`, `ContentHash`. `SourcePipeline` (application) chains them in the table's order. Rules version `filters-1`, for the step 7 config hash.
- Plan changes (docs updated):
  - **Filter order:** architecture §5 and the step 4 table disagreed. I followed the table (secret-file patterns second, before anything reads content) and rewrote §5. The size cap ran in step 3, in the snapshot.
  - **The deny-list stops at Java/Kotlin source roots.** A `build`, `out` or `generated` directory below `src/*/java/` is a package, not build output (`com/acme/build/Builder.java`). The source-set name itself still counts, so `src/generated/java/...` is excluded.
  - **`credentials*` doesn't apply to code files** (Java, Kotlin, TypeScript), e.g. `credentials.service.ts`. Name patterns are case-sensitive, so `Credentials.java` is kept. Key-store extensions are case-insensitive.
  - **One more detector: unquoted values in properties and YAML** for keys ending in password/passwd/secret/token/api-key (`spring.datasource.password=...`). The plan's regex only caught quoted values, which would have left the most common Spring secret in place. Placeholders (`${...}`, `{cipher}`), URLs and values under 8 characters are skipped.
  - **The quoted-value rule also catches JSON keys** (`"apiKey": "..."`) and names that end in a secret word (`DB_PASSWORD`, `authToken`). It never catches names that only contain one (`passwordEncoder`, `tokenizer`). Only the value is replaced, so the code still reads sensibly.
  - **Also detected: Slack and Google API tokens and GitHub fine-grained tokens**, alongside AWS and classic GitHub tokens.
  - **No entropy check yet.** It's optional in the plan and flags hashes, IDs and test fixtures far more often than secrets in code. Recorded as a residual risk in T5.
- Details:
  - Private-key blocks keep their BEGIN and END lines; each body line becomes `«REDACTED»` and blank lines stay blank; one count per block. A key held on one line (JSON with `\n` escapes) is replaced between the markers. An unterminated block is redacted to the end of the file.
  - A token under a secret-like name is counted once, not twice.
  - Line count: a final line without a newline counts; an empty file has 0.
  - `NormalizedFile.toString()` leaves out the content (OBS-3).
- Tests: `PathRulesTest` (deny-list at any depth vs package and look-alike paths, secret files vs code, every extension → language), `TextNormalizerTest` (strict UTF-8, ISO-8859-1 fallback flagged, BOM, CRLF/CR, line counts), `BinarySnifferAndHashTest`, `SecretRedactorTest` (every detector positive and negative, key block structure, one-line and unterminated keys, placeholders, URLs, no double count, line count kept), `SourcePipelineTest` (each row, secret-file before extension, CRLF+BOM and LF hash the same, redaction before hashing, encoding fallback, and a real fixture commit whose stats add up to every tracked entry), `IngestionStatsTest`.
- Verified: `./mvnw clean verify` green (408 tests; 2 skipped on Windows, the symlink escapes from step 3). No API change, so OpenAPI and frontend types are unchanged.
- Next: step 5 (Java parser and symbol extraction, golden tests).

## 2026-11-16 — M2 (session 2, cont.): step 5
- Did: the `indexing` module's first code. Public SPI `CodeParser` (`supports`, `parse`, `version`) with `ParsedFile` (status, symbols, problems), `ParsedSymbol`, `SymbolKind` and `ParseStatus`. `JavaParserCodeParser` (`indexing.internal.parsing`, version `java-1`) collects types at any depth (class, interface, enum, record, annotation) and their methods, constructors (compact ones too) and fields (one per variable). New dependency: JavaParser 3.28.2 (latest, GEN-9) at language level `JAVA_26`, the highest it knows.
- Choices / deviations from the step text:
  - **Signatures are built from the AST, not with `getDeclarationAsString(true, false, true)`.** The golden review caught that it adds implicit modifiers (`abstract` on interface methods), drops `default` and method type parameters (`<U>`), pads with a leading space, and renders a compact constructor as `Visit()`. Types come from the source tokens (`Map<String, List<Integer>>` keeps its spacing). Parameter annotations are left out of signatures for readability; the symbol's own annotations are a separate list.
  - **`kind` is an enum (`SymbolKind`), and `isTest` is `test`**: the CHECK constraint's values are a closed set (JAVA-7), and the record component reads as `symbol.test()`.
  - **Problems are positions only** (`line 7, column 23: syntax error`). JavaParser's messages quote the offending tokens, which could include a secret that survived redaction (OBS-3). At most 20 per file.
  - **Never throws:** a parser crash or `StackOverflowError` on pathologically nested input becomes `PARSE_FAILED` (ERR-4). This is the one place an `Error` is caught: per-file input, logged by type only.
  - **A parser per call** rather than per thread: JavaParser isn't thread-safe and building one is cheap, so nothing is shared at all.
  - **Keys:** erasure as written (`List<Owner>` → `List`, `java.util.Map.Entry` keeps its qualifier, `T` stays `T`), varargs as arrays (`find(String[])`), constructors as `pkg.Type#Type(...)`, and a compact constructor takes the record components. Overloads get distinct keys.
  - **Test code:** under `src/test/`, or the symbol or an enclosing type has a test annotation by simple name (so `@SpringBootTest` marks its `@BeforeEach` helpers too).
  - **Not symbols in M2:** local and anonymous classes, enum constants (and their bodies), initializer blocks, annotation members, record components. The schema has no kind for them and nothing in M2-M4 needs them.
- Golden tests (TEST-3): 9 fixtures in `src/test/resources/golden/java` (plain class with Javadoc, comments and multi-variable fields; nested, inner, local and anonymous classes; enum with constant bodies and a record with compact and canonical constructors; interface with default and static methods and a nested `@interface`; a Spring controller with a multi-line `@GetMapping`; a JUnit class; generics, overloads, varargs and arrays; default package; a syntax error). Every snapshot reviewed by hand against its fixture. Regenerate with `./mvnw -q test -Dtest=JavaParserGoldenTest -DupdateGolden=true`, then review the diff. Snapshots are written LF on every OS.
- Tests: also `JavaParserCodeParserTest` (path-based test detection, annotated method in a plain class, current syntax: sealed types, record patterns, unnamed variables, text blocks; empty file; problems never quote the source; problem cap; 50,000-deep nesting → `PARSE_FAILED`; overload and constructor keys) and `ParsedFileTest` (parent indexes, status invariants, line ranges).
- Verified: `./mvnw clean verify` green (433 tests, 2 symlink tests skipped on Windows); the TEST-8 coverage gate now applies to `indexing.internal.parsing` and passes (about 99% of lines). No API change, so OpenAPI and frontend types are unchanged.
- Next: step 6 (identifier splitting and symbol-aware chunking).

## 2026-11-16 — M2 (session 2, cont.): step 6
- Did: `IdentifierSplitter` (`split`, and `terms` for the identifiers in a text) and `SymbolChunker` (version `chunk-1`) with its `Chunk` record, all pure, in `indexing.internal.domain`. Chunks carry the lines, the owning symbol's index, the header, the content, the search text (header + content + split identifiers, for `to_tsvector('simple', …)` in step 7) and `ceil(chars / 4)` tokens.
- Choices / deviations from the step text (architecture §7.1 updated):
  - **The type header chunk keeps the type's shape.** Each multi-line method, constructor or nested type becomes one line, its signature followed by `…`, at its own indentation. Javadoc, fields and comments stay. Single-line members, such as abstract methods, stay as written. "What does OwnerService offer?" can then be answered from one chunk.
  - **No duplicate whole-type chunk.** A small type whose header chunk elided nothing (`enum Mode`, an annotation type) gets one chunk, not two identical ones, which would only skew retrieval.
  - **Header for a type chunk:** `path · package · type signature`, the step's member format without the member. The default package leaves out its segment. A nested member's header uses its immediate type.
  - **Java that declares nothing** (`package-info.java`, a file of imports) is chunked as text, like `PARSE_FAILED`, so it stays searchable. **Step 7 must not require ≥ 1 symbol per `OK` Java blob** (step 7, validation phase), or every `package-info.java` would fail its revision.
  - **The splitter stays internal for now.** The plan has M3 queries use the same splitter, but ARCH-3 allows only interfaces, records, enums and events in a module API. M3 has to expose it, e.g. through an interface method in the indexing API.
  - `terms` skips identifiers that are one word in any case (`id`, `Owner`): `to_tsvector('simple', …)` lowercases on its own.
- Golden tests: `ChunkerGoldenTest` writes `*.chunks.json` next to each fixture (lines, symbol, tokens, header, content), and a new `LongMethods.java` fixture (a 131-line member, so windows 8–87 and 78–138; a 141-line type, so no whole-type chunk). Reviewed by hand. Regenerate with `./mvnw -q test -Dtest=ChunkerGoldenTest -DupdateGolden=true`.
- Tests: `IdentifierSplitterTest` (the plan's five examples plus acronyms, digits, `$`, dots and non-ASCII; `terms` dedupes and skips single words), `SymbolChunkerTest`:
  - window arithmetic (exact fit, one line over, the end of the file, empty)
  - text windows and every fallback to them
  - the 120 vs 121 line edge
  - headers, header-chunk elision, the small-type rule and its dedupe
  - symbols past the end of the file rejected
  - search text and token estimate
- Verified: `./mvnw clean verify` green (476 tests, 2 symlink tests skipped on Windows; coverage gate met). Step 7's validation rule changed in the milestone doc from "≥ 1 symbol per `OK` Java blob" to "≥ 1 chunk".
- Next: step 7 (indexing orchestration: blob reuse, batching, READY).

## 2026-11-16 — CI fix: gitleaks
- CI's `secrets` job (gitleaks, full history) had failed since the step 4 commit `3c95018`. Its 7 findings were all the fake secrets in `SecretRedactorTest` (AWS, Google, private-key blocks, a generic token), the fixtures that check the redactor catches them. Nothing real leaked. My miss: the steps 3 to 6 hand-offs ran `verify` but not the gitleaks pre-check M1 used.
- Fix:
  - The test now builds every fake secret from pieces at runtime (`"AKIA" + "IOSF…"`, `"-----BEGIN RSA " + "PRIVATE KEY-----"`), so no source line has a secret's shape.
  - `.gitleaksignore` lists the 7 historic findings by exact fingerprint (commit:file:rule:line), not by path or rule, so a real secret anywhere still fails CI. History can't be rewritten (CLAUDE.md).
- Verified: gitleaks 8.30.1 (CI's image) over all 31 commits and over `backend/src` finds no leaks; `./mvnw clean verify` green (476 tests).
- From now on, every step's verification includes the gitleaks scan.

## 2026-11-17 — M2 (session 3): step 7
- Did: indexing orchestration.
  - `POST /api/projects/{projectId}/index-jobs {ref}`: 202, `Location: /api/jobs/{id}`, body `{jobId}` (API-4).
  - `IndexJobService`: policy first, then resolve the ref, then submit.
  - `IndexRevisionJobHandler` (`INDEX`): snapshot, then the ingestion pipeline, then parse and chunk the new blobs, then batched writes.
  - `IndexWriter`: one short transaction per batch, plus start and finish. `IndexingQueries`: JdbcClient plus batched inserts.
  - `IndexToolchain`: parsers, pipeline and config hash. `IndexingProperties`: `devmind.indexing.batch-size`, 500.
  - Public `RevisionReadyEvent`, published in the transaction that marks the revision READY.
- Owner decisions (asked before building, CLAUDE.md "don't guess on public module APIs"):
  - **Source in the job payload** (`{ref, sourceType, sourceLocation}`), captured at submit after the owner check. The handler runs without a user and needs no new project API. Projects aren't editable in v1.
  - **`SourceSnapshotProvider.resolve(source, ref)`** (new, returns `RefResolution`). Local repos are resolved in place; remote ones with one `ls-remote` from a throwaway bare repo (redirects off, no download). This fixes a plan bug: with the planned `ref` key for remote sources, `JobApi.submit` returns the existing SUCCEEDED job, so a moved branch could never be re-indexed. The key is now `commit:configHash` for every source.
  - **422 / 503 at submit** through new shared `UnprocessableException` and `UnavailableException`, mapped in `ProblemDetailsAdvice`. A rejected ref or source is 422 with the `SourceProblem` code; a transient failure is 503.
- Choices / deviations from the step text:
  - A local job's payload carries the resolved SHA, so it indexes exactly the commit its key names. A remote job keeps the ref as given, so the clone stays shallow (a SHA would fetch full history, step 3). If the branch moves between submit and run, the revision row is keyed by the commit actually indexed.
  - **IDs are pre-allocated** (`nextval` on the identity sequences, `INSERT … OVERRIDING SYSTEM VALUE`). Blobs, symbols with their parent links, and chunks with their symbol links all go in JDBC batches: a few round trips per batch, none per row (DB-8).
  - **A blob is written together with its symbols and chunks, in one transaction.** A stored blob is therefore always complete, which is what makes retries resume safely. Concurrent runs that race on the same blob: `ON CONFLICT DO NOTHING`, and the loser looks up the winner's row.
  - **Stale chunks are rebuilt.** A reused blob whose chunks came from another chunker version has its symbols and chunks deleted and rebuilt (the step 1 note).
  - **Validation:** `revision_file` count = files kept, and no non-empty file is without a chunk. That's broader than the step text's "every OK Java blob" (step 6), and an empty file has none. "Every blob has `parse_status`" is already a NOT NULL constraint. A failed check marks the revision FAILED with `index-incomplete`.
  - **Stats** from the database: `filesKept`, `symbols`, `chunks`, `parseFailures`, `redactions`. They're right however many attempts a run took. `blobsNew` and `blobsReused` describe the last attempt; `encodingFallbacks` was added.
  - Blobs with no parser get `parser_version = none` and `parse_status = SKIPPED`.
- Found (not fixed, for later):
  - **Chunk headers include the file path, but chunks belong to content-addressed blobs.** A file that's moved or copied unchanged reuses the old chunks, header path included. Retrieval (M3) should take the path from `revision_file` for the requested revision and not trust the stored header's path, e.g. by storing headers without the path. To decide in M3, before embeddings are built from headers.
  - **`file_blob.content_hash`'s comment in V3 says "before redaction"**; the hash is of the redacted content (step 4 row 9), which is right: it's the stored text's identity. The applied migration can't be edited (DB-1); recorded here.
  - A job that fails on its last attempt leaves its revision INDEXING. Step 8's revision list will show it as such; a cleanup isn't in M2.
- Tests:
  - `IndexRevisionJobHandlerTest` (Testcontainers; a fixture repo with 5 kept and 4 excluded files; batches of 2 in the test profile):
    - commit A → READY with exact stats and the event
    - redacted content, symbol parent links, `package-info` as a text chunk
    - re-indexing A → no new rows
    - commit B changing 1 file → `blobsNew = 1`, `blobsReused = 4`
    - a syntax error → READY with `parseFailures = 1` and text chunks
    - a crash after batch 1 → INDEXING with 2 files, then the retry ends with exactly the rows of an uninterrupted run
    - stale chunks rebuilt, not duplicated
    - permanent failures for a missing ref and a bad payload
    - progress per batch
  - `IndexJobControllerTest`: 202 + Location + QUEUED job; the same job for `main` and its SHA, a new one after the branch moves; 422 `ref-invalid` and `ref-not-found`; 422 `source-location-not-allowed`; 400; 404 for a stranger before any source check.
  - Authz matrix: a new `OwnerScopedCommand` kind (body, stranger 404, owner past authorization).
  - `ProblemDetailsTest` for 422 and 503; `resolve` tests for local, remote (`ls-remote`, peeled tag, SHA prefix, nothing left behind) and the service boundary; `IndexRequestTest`.
- Also: `JobRunnerTest.claimsNothingOfATypeWithoutAHandler` used `INDEX` as its type without a handler; it now uses `ANALYSIS` (handler in M5).
- DB-6: the new queries are covered by the Testcontainers tests above. Their `EXPLAIN` check moves to step 10, on PetClinic-sized data, where it means something.
- Verified: `./mvnw clean verify` green (503 tests, 2 symlink tests skipped on Windows; coverage gate met); OpenAPI and frontend types regenerated, `format:check` clean; gitleaks (CI image) over history and `backend/src` clean.
- Next: step 8 (read APIs: revisions, file tree, source slices, symbol search).

## 2026-11-18 — M2 (session 4): step 8
- Did: the revision read API, `RevisionController` → `RevisionReadService` → `RevisionQueries`:
  - `GET …/revisions`: paginated, newest first, every status; `stats` is null until a run finishes.
  - `GET …/{sha}/files?path=`: direct children, directories first, then by name, case-insensitively.
  - `GET …/{sha}/file?path=&from=&to=`: a source slice.
  - `GET …/{sha}/symbols?q=&kind=&page=&size=`: search.
  - `GET …/{sha}/symbols/{symbolId}`: detail plus direct members.

  Every call checks the project policy first (SEC-1), reads one explicit READY revision through `revision_file` (DB-7), and echoes the full SHA (API-7).
- Owner decision: **`code_symbol.public_id`** (V4, `gen_random_uuid()` default plus a unique index). Symbols are addressed by UUID like projects and jobs (API-5). Existing rows were filled by the default, so no re-index was needed.
- Choices / deviations from the step text:
  - `{sha}`: a full SHA or a ≥ 7-character prefix (`[0-9a-f]{7,40}`, else 400).
    - A prefix matching several commits → 409 `revision-ambiguous`.
    - A commit with no READY revision → 409 `revision-not-ready`.
    - No match → 404 `revision-not-found`.
    - A commit indexed under several index configs → the most recently READY revision.
  - **Directory listings are capped at 1000 entries** with a `truncated` flag rather than paginated: the tree loads one directory at a time (step 9), and a directory that big is an outlier. A path that's a file, missing, or malformed (`..`, `//`) → 404 `directory-not-found`; leading and trailing slashes are ignored.
  - **Source slices:** without `to`, the slice runs to the end or 2000 lines, whichever comes first; a `to` past the end is cut to the end. A start past the end, `to < from`, or a span over 2000 lines → 422 `line-range-invalid`. An empty file reads as `from = to = 0`. Content is as stored, so it's redacted (SEC-5).
  - **Search:** pg_trgm's `%` operator with `similarity_threshold` set to 0.4 for the read transaction only (`set_config(…, true)`), so the GIN trigram index can serve it. That's instead of `similarity() >= 0.4`, which can't use the index. Exact name or qualified-name matches first, then by similarity, qualified name, path. A blob at two paths gives one hit per path (same `symbolId`).
  - **Symbol detail:** the first of the blob's paths in the revision; children at the same path. `parentId` is the enclosing type's `symbolId`.
  - **One stats type:** `RevisionStats` (domain) is what the indexer writes and the API reads; the handler's map became this record.
  - `SourceLines` is the single definition of lines (the chunker uses it too).
  - Authz matrix: a new `RevisionScoped` kind. The stranger gets `project-not-found`; the owner gets past the project check, since the matrix indexes nothing.
- DB-6: the search, directory and totals queries are covered by Testcontainers tests. Their `EXPLAIN` on PetClinic-sized data is in step 10 (noted in step 7 too).
- Tests: `RevisionControllerTest` over a fixture repo indexed for real:
  - revisions: list order and stats; prefix plus full-SHA echo; not ready, ambiguous, unknown and malformed SHAs
  - files: root and subdirectory listings with and without slashes; 404s, including `..` paths
  - source slices: whole file, range, clamp, the 2000-line cap, bad ranges, empty file, redacted content
  - symbols: exact-first search with location, typo tolerance, kind filter, qualified name, paging, validation; detail with members and parent; unknown and malformed IDs
- Verified: `./mvnw clean verify` green (528 tests, 2 symlink tests skipped on Windows; coverage gate met); OpenAPI and frontend types regenerated, `format:check` clean; gitleaks (CI image) over history and `backend/src` clean.
- Next: step 9 (UI: indexing status, file tree, code viewer, symbol search).

## 2026-11-19 — M2 (session 5): step 9
- Did: `features/explorer` (lazy, under `/projects/:projectId`) and `shared/code-viewer`.
  - **Project page:** source, a revisions table (status tag in words, short SHA linking to the explorer, stats), and an "Index" dialog for a ref. Job progress shows the phase, `done / total`, a bar, and failures in words per `errorCode`. When a job succeeds, the revision list reloads.
  - **Explorer page** (`/projects/:projectId/revisions/:sha`): symbol search and a lazy PrimeNG tree on the left, the code viewer on the right, and breadcrumbs.
  - **`CodeViewer`** (CodeMirror 6): read-only, Java highlighting, line numbers offset by `firstLine`, a line-range highlight decoration, scrolls to it. Content goes in as editor text only (SEC-9, FE-12).
  - **`ExplorerApi`** (FE-8): `pollJob` = `timer(0, 1500)` + `switchMap` + inclusive `takeWhile`. `ProjectStore` and `ExplorerStore` hold signals (FE-3).
  - The project list's names now link to the project page. New dependencies: `@codemirror/state`, `view`, `language` and `lang-java` (latest, each released over two weeks earlier).
- Choices / deviations from the step text:
  - **The URL is the state of the open file.** Tree clicks, symbol clicks and deep links (`?path=&from=&to=`) all navigate, and one effect shows what the URL names, so M4's citation chips only need to build that URL. Route and query params bind to inputs (`withComponentInputBinding`; `paramsInheritanceStrategy: 'always'` so child pages get `:projectId`).
  - **Long files:** a link past line 2000 reads from 100 lines above the range, and the viewer numbers lines from the slice's start.
  - **Both pages live in one feature, `explorer`:** FE-5 forbids features importing each other, and the project page and explorer share the API.
  - **States (FE-10):** loading, empty ("No files", "Nothing indexed yet"), error with retry, and the revision states not-ready, ambiguous and not-found. The 404 and 409 from revision reads are handled locally, not toasted (`LOCALLY_HANDLED_STATUSES`).
  - Stale responses are dropped: a slower file or search answer can't overwrite a newer one.
  - A CSS-grid split instead of PrimeNG Splitter, for a fixed, simple layout. Resizing can come later.
- Found and fixed on real data:
  - **Search ranking.** PetClinic's controller is `OwnerRestControllerV1`, so `OwnerRestController` only matches fuzzily, and with ties ordered by qualified name, a test field ranked above the class.
  - Ties now order main code before test code, then types before members (`RevisionQueries`, step 8's query).
  - `RevisionControllerTest.ranksMainCodeAndTypesFirstAmongEquallySimilarNames` checks it; I confirmed it fails without the tiebreak.
- Smoke run on the dev stack (compose DB, `local` backend, `ng serve` proxy), as the demo user, PetClinic at the pinned SHA `5180cc9`:
  - create the project → `POST index-jobs` (202) → poll → SUCCEEDED in about 1 s
  - stats: 142 files kept; 2 binary and 10 by extension excluded; 792 symbols, 839 chunks, 0 parse failures, 2 redactions, 1 encoding fallback
  - tree, source slice and symbol search all answer as the UI uses them
  - the SPA serves deep-link URLs
  - The project stays in the local DB for the browser check.
- Pre-existing: the initial bundle is over its 500 kB *warning* budget (637 kB on `main` before this step; 665 kB now, the extra mostly router input binding). The error budget is 1 MB. CodeMirror and the tree are in the lazy `explorer-routes` chunk (463 kB). Trimming the initial bundle (PrimeNG theme, toast) is a separate task.
- Tests: `explorer-api.spec` (polling stops after SUCCEEDED, FAILED and CANCELLED, emits the terminal state last, and stops on a failed poll), `explorer-store.spec` (deep-link parsing; revision states from 404/409 codes; lazy expand once; deep link → read start, `firstLine`, highlight; out-of-order answers; search), `symbol-search.spec` (300 ms debounce, trim, no repeats), `project-store.spec` (index → poll → revisions reload; rejected ref), `code-viewer.spec` (text not markup, read-only, offset numbers, highlighted lines). 45 frontend tests.
- Verified: frontend lint, `format:check`, tests and build; backend `./mvnw clean verify` green (529 tests); gitleaks over history, `backend/src` and `frontend/src` clean.
- **Open:** the step's done-when is a browser click-through (index → progress → tree → file → search → lines). The API flow behind it is verified above; the owner still has to click through it. Tick the tracker after that.
- Next: step 10 (PetClinic end to end, EXPLAIN checks, close M2).

## 2026-11-20 — M2 (session 6): step 10
- Did: indexed PetClinic end to end through the compose stack, then ran the exit checks.
  - New `deploy/compose.targets.yaml`, an optional override. It bind-mounts `D:/dev/targets` read-only at `/targets` (override with `DEVMIND_TARGETS_DIR`, `create_host_path: false`) and sets `DEVMIND_INGESTION_ALLOWEDROOTS=/targets`. It's a separate file so `compose.yaml` still starts on a machine without the targets folder. The command is in `CLAUDE.md`.
  - Rebuilt the stack: it was still on the pre-M2 image.
- **Pinned SHA `5180cc9`** (project source `/targets/spring-petclinic-rest`): READY in 1537 ms.
  - 142 files kept; 2 binary and 10 by extension excluded
  - 792 symbols, 839 chunks, 0 parse failures, 2 redactions, 1 encoding fallback
  - same counts as the step 9 smoke run through the `local` profile
- **Re-index, same SHA:** a no-op. Submitting the full SHA and the prefix `5180cc9` both returned the original job (`a70edd5c`, SUCCEEDED). The revision kept its `readyAt`, and the revision, file, blob, symbol, chunk and job row counts didn't change.
- **Second SHA, the parent `d350090`:** READY in 332 ms; `blobsReused` 133, `blobsNew` 9. `git diff --stat d350090 5180cc9` lists exactly 9 changed files. The 9 new blobs added 117 symbols and 154 chunks; nothing else was re-parsed.
- **Milestone lookup:** `OwnerRestController` → `OwnerRestControllerV1` (CLASS) first, then V2. Its qualified name is `org.springframework.samples.petclinic.rest.controller.v1.OwnerRestControllerV1`, file `src/main/java/.../rest/controller/v1/OwnerRestControllerV1.java`, lines 51–203.
  - Checked against `git show 5180cc9:<path>`: line 51 is `@RestController` (the first annotation), and 203 is the closing `}` and the file's last line. The slice API returns the same lines with `lineCount` 203.
  - The class Javadoc (47–49) sits outside the range because a blank line separates it, as the golden tests expect.
- **Spot check, 5 symbols picked by `md5(qualified_name)`:** all 5 ranges are exact against the git text, each from the first annotation or declaration line to the closing `}` or `;`.
  - `OwnerRestControllerV1Tests#testGetOwnerPetSuccess` 559–571
  - `BaseEntity#id` 33–35
  - `OwnerRestControllerV1Tests#testUpdateOwnerError` 306–316
  - `UserRepository#save` 8
  - `Role` 12–39
- **Known limitation: generated code (step 10.6).** The controllers implement `OwnersApi` and the other `*Api` interfaces. openapi-generator writes those into `target/` from `src/main/resources/openapi.yml`; they're not in git and aren't indexed (0 `*Api.java` at the SHA, and an INTERFACE search for `OwnersApi` finds nothing). `OwnerRestControllerV1` carries only `@RequestMapping("/api")`, because the per-endpoint mappings are in the generated interface. M5 will see unresolved `IMPLEMENTS` edges. M8 has to take mappings from the OpenAPI spec (already noted in `evaluation/targets.yaml`).
- **DB-6, `EXPLAIN (ANALYZE)` on PetClinic data** (1701 symbols, 426 revision files, 2 revisions):
  - symbol search + count: 3.5 ms / 4.0 ms
  - directory listing, root and deep: 0.3 ms / 0.2 ms
  - revision totals: 0.7 ms
  - All use seq scans at this size. The totals subqueries already use index-only scans on `code_chunk_blob_idx` and `code_symbol_blob_idx`.
  - With seq scans off, the indexes are usable: `revision_file_pkey` (revision, path) for the revision filter and the path prefix. The search predicate becomes a BitmapOr over `code_symbol_name_trgm` (`=` and `%`) and `code_symbol_qname_trgm` (`=`); pg_trgm 1.6 supports `=` in GIN. Revisit at the M3 dataset size.
- **What fought me: two job workers on one database.** The `local` backend from session 5 was still running against the compose database. It claimed the first INDEX jobs and rejected `/targets/...` (`source-location-not-allowed`), since its only root is `D:/dev/targets`. The container had resolved the ref fine at submit time. That's correct SEC-2 behaviour, but which instance runs a job decides whether it's allowed. Same single-instance assumption as the clone directory. Noted in `CLAUDE.md`; no code change in M2. If several instances ever share a database, roots need to agree, or jobs need routing.
- Exit checklist: everything is ticked except the UI line.
  - Symlink-escape tests: skipped on Windows, but CI on `main` (`aeacdd6`, ubuntu) ran all 529 tests with 0 skipped.
  - Authz matrix: step 8.
  - The UI line is still open. It needs the owner's browser click-through from step 9 (index → progress → tree → file → search → lines). The data for it is in the compose stack now: project "spring-petclinic-rest (compose)" at http://localhost:8088.
- Not changed: no Java or frontend code this step, so no `./mvnw verify` rerun. The last green run was step 9's.
- Next: owner click-through; then "Current milestone" → M3 in `CLAUDE.md` and tag `m2`.

## 2026-11-21 — M3 (session 1): step 1, embedding port
- Bookkeeping first. Tag `m2` exists on `06c254d`, so "Current milestone" in `CLAUDE.md` now says M3. The M2 UI exit line (PLAN.md M2, and steps 9–10 in the M2 tracker) is still unticked: only the owner's browser click-through can tick it.
- The M3 plan's migration numbers were stale, because M2 used V3 and V4. They now read: M3 `V5__embeddings`, `V6__evaluation_run`; M4 `V7__llm_call`, `V8__conversation`; M5 `V9__symbol_edge`, `V10__analysis`; M7 `V11__tool_call`.
- Did: the new `ai` module.
  - Public API: `EmbeddingPort` (`model()`, `embedDocuments`, `embedQuery`), `EmbeddingModelRef(provider, name, dimensions, digest)`, and `EmbeddingUnavailableException`. It extends the shared 503 type, with codes `embedding-unavailable`, `embedding-model-not-found` and `embedding-invalid-response`.
  - `OllamaEmbeddingAdapter` in `ai.internal.embedding`: `POST /api/embed` in batches (`batch-size` 16), `GET /api/tags` for the digest. A JDK `HttpClient` with connect and read timeouts (5 s / 2 min), HTTP/1.1, and no redirects.
  - `EmbeddingProperties` (`devmind.ai.embedding.*`): ADR-0006's model, 1024 dimensions and query prefix. The base URL must be absolute `http`/`https`, with no user info, query or fragment.
  - Metrics `devmind.embedding.latency` and `devmind.embedding.texts`, tagged `kind=document|query`.
- Choices / deviations from the step text:
  - **`RestClient`, not Spring AI.** The plan allows either. Spring AI's Ollama module pulls in `spring-webflux`. Its 2.1 line targets Framework 7.1, but this app runs on 7.0.10. Two endpoints of plain JSON don't justify that. ADR-0006's ports are unchanged, and ARCH-8 still confines any SDK to `ai.internal`.
  - **The app starts without Ollama.** The model is resolved on first use and cached, and an `ApplicationRunner` tries it at startup:
    - wrong vector length → the app refuses to start (a configuration error);
    - server unreachable → WARN, and the explorer keeps working.
    - The plan said "fail fast"; that now holds only for the dimension, the one failure that can't fix itself. A failed resolution isn't cached, so the next call retries.
  - **No retries in the adapter.** The INDEX job retries with backoff (ERR-3). A failed query embedding will degrade retrieval, not fail it (step 4).
  - `provider: ollama | fake`. With `fake` (the `test` profile), main code registers no adapter, and `@IntegrationTest` imports `FakeEmbeddingConfiguration`.
  - `FakeEmbedding` (test sources) hashes split identifier words into signed buckets, then L2-normalizes. Shared words give a higher cosine similarity, so step 3's vector-ranking tests mean something. A text with no words gets a fixed unit vector, because pgvector's cosine distance is undefined for a zero vector.
  - Compose: the backend gets `DEVMIND_AI_EMBEDDING_BASEURL=http://host.docker.internal:11434`, plus `extra_hosts: host-gateway` for Linux. Ollama's port isn't published.
  - Threat model: new T17 (model endpoint or model swap), and a note on `host.docker.internal` under the assumptions.
- **Found: `mxbai-embed-large` has a 512-token context** (`/api/tags` → `context_length: 512`). The adapter sends `truncate: true`, so Ollama silently cuts long inputs. Member chunks run up to 120 lines and window chunks to 60, so many are well over 512 tokens (~4 chars per token). Only their beginning gets embedded. M0's spot check used 40-line chunks. Lexical search still covers the whole text. Measure it in step 2: count chunks over ~2,000 characters, and check what it does to vector recall in Experiment A before changing chunking (AI-10).
- Tests: `OllamaEmbeddingAdapterTest` runs against a stand-in Ollama (JDK `HttpServer`, loopback only). It covers digest resolution and caching, the dimension guard, startup without a server, retry after a failed resolution, a missing model (tags listing and 404), batching with prefixes and order, no call for an empty list, a real read timeout, too few vectors, a length change after resolution, and the metrics. `EmbeddingPropertiesTest` checks the conditional wiring and the validation, and loads the shipped `application.yaml`, because test property values get trimmed and would hide a lost trailing space. `FakeEmbeddingTest` covers determinism, ranking, splitting, and the no-words vector.
- Verified: those 25 tests, plus `ArchitectureTest` and `ModularityTest`, green. `./mvnw -o verify -DskipTests` is green (compile with Error Prone and NullAway, Spotless, Checkstyle, Modernizer).
- 2026-11-23, with Docker up: `./mvnw clean verify` green, 554 tests (529 + 25), 2 skipped (the Windows-only symlink skips). gitleaks clean: full history (45 commits) and `backend/src`, uncommitted files included. One gotcha: in PowerShell, `-v "$PWD:/repo"` mounts an empty directory, and gitleaks then reports "0 commits scanned … no leaks found". Pass the absolute path.
- **Not verified yet:** the step's done-when, the app starting against local Ollama with the guard passing. Outside the app, Ollama answers with digest `4688361…83dd8` (matches ADR-0006) and 1024-length vectors.
- **Open question (AI-2):** "every model call goes through the ports and writes an `llm_call` row." Embedding calls are model calls, but `llm_call` arrives in M4 (`V7`), and its `prompt_id`/`prompt_version` columns don't fit embeddings. Re-indexing PetClinic is ~60 batches. Proposal: embeddings report through metrics plus the revision stats (`embeddingsNew`, `embeddingMs`, step 2) and don't write `llm_call` rows. Then amend AI-2 to cover chat calls only. Owner to decide before M4 step 1.
- Next: owner runs the checks above. Then step 2: `V5__embeddings.sql` and the embedding phase in indexing.
