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

### Later (ideas parked per GEN-3)
- Load Mockito as a `-javaagent` in Surefire to silence the JDK dynamic-agent warning (needs `dependency:properties` + `@{argLine}` alongside JaCoCo).
