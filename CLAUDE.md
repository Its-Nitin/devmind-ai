# DevMind AI — Instructions for AI coding assistants

Read these before writing code. They're binding and win over general habits:
1. [docs/PLAN.md](docs/PLAN.md): current milestone and scope. **Don't build anything outside the current milestone.**
2. [docs/coding-standards.md](docs/coding-standards.md): rules with IDs. Cite IDs in commit messages when relevant.
3. [docs/architecture.md](docs/architecture.md) and [docs/adr/](docs/adr/README.md): design. Don't contradict an accepted ADR. Propose a new ADR instead.

## Current milestone
M1 (walking skeleton). M0 closed 2026-11-07 (tag `m0`). Update this line when a milestone tag is created.

## Non-negotiables (summary; details in coding-standards.md)
- Deterministic code computes facts; LLM output is untrusted until validated (GEN-1, AI-3).
- Module boundaries: only top-level module packages are public; never import `..internal..` across modules (ARCH-2).
- Spring AI / provider SDK types only in `com.devmind.ai.internal` (ARCH-8).
- Every project-scoped service method calls `ProjectAccessPolicy` first; non-owners get 404 (SEC-1, API-8).
- Schema changes only via new Flyway migrations; never edit an applied one (DB-1).
- Code reads are revision-scoped (DB-7).
- Prompts are versioned files in `resources/prompts`, never inline strings (AI-1).
- Model cites evidence IDs only; the server resolves and validates (AI-5).
- Tests: no network, no real models, Testcontainers Postgres with pgvector, no H2 (AI-11, TEST-4/5).
- No Lombok, no field injection, no `LocalDateTime` in persistence/APIs, no shell execution (JAVA-5/6/9, SEC-4).
- Never log source code, prompts, model output or secrets (OBS-3).

## Commands
_To be filled in M1 after scaffolding_ (build, test, run locally, run eval, regenerate OpenAPI types).

## When unsure
Stop and ask, or write the question in the PR description. Don't guess on security, schema or public module APIs.
