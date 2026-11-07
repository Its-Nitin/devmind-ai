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
| qwen2.5-coder:7b | chat (local) | – | _pending_ | | /3 | – | dae161e27b0e |
| hosted | chat (hosted) | – | N/A (local-only decision) | – | – | – | – |

  - nomic misses: "add a visit for a pet" (returned entities instead of the controller), "pet-type delete cascade" (found the JPA/JDBC impls but not the active Spring Data one), "owners page by page", and "vet mapper" (tiny MapStruct interface).

### Later (ideas parked per GEN-3)
- Index `openapi.yml` (and other spec files) so API-first projects' endpoints are discoverable.
- Benchmark candidate: "Is repository call timing actually active?" (CallMonitoringAspect not registered).
