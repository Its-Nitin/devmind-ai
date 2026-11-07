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

### Later (ideas parked per GEN-3)
- Index `openapi.yml` (and other spec files) so API-first projects' endpoints are discoverable.
- Benchmark candidate: "Is repository call timing actually active?" (CallMonitoringAspect not registered).
