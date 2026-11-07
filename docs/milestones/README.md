# Milestone Playbooks

Step-by-step execution guides for each milestone in [PLAN.md](../PLAN.md). The plan says **what** and **why**; these say **how, in what order, and how to know you're done**.

| Milestone | Playbook | Budget | Ends with tag |
|---|---|---|---|
| M0 — Pre-implementation | [M0-pre-implementation.md](M0-pre-implementation.md) | 6–10 h | `m0` |
| M1 — Walking skeleton | [M1-walking-skeleton.md](M1-walking-skeleton.md) | 8–12 h | `m1` |
| M2 — Ingestion & structural index | [M2-ingestion-and-indexing.md](M2-ingestion-and-indexing.md) | 18–25 h | `m2` |
| M3 — Retrieval + evaluation harness | [M3-retrieval-and-evaluation.md](M3-retrieval-and-evaluation.md) | 15–20 h | `m3` |
| M4 — Grounded Q&A | [M4-grounded-qa.md](M4-grounded-qa.md) | 15–20 h | `m4` |
| M5 — Dependency graph & change impact | [M5-change-impact.md](M5-change-impact.md) | 22–30 h | `m5` |
| M6 — Hardening & ship | [M6-hardening-and-ship.md](M6-hardening-and-ship.md) | 12–16 h | `m6` |
| M7 — Stretch: MCP server + bounded agent | [M7-mcp-and-agent.md](M7-mcp-and-agent.md) | 8–12 h | `m7` |
| M8 — Stretch: Angular cross-layer | [M8-angular-cross-layer.md](M8-angular-cross-layer.md) | 12–18 h | `m8` |

## How to use a playbook
1. Read the whole playbook once before starting the milestone.
2. Work the steps in order. Each step lists the **PR** it belongs to, so every PR is small and reviewable (GIT-4).
3. Tick the step's "Done when" before moving on.
4. Code snippets are **skeletons that show shape and intent**, not finished code. They must still pass every rule in [coding-standards.md](../coding-standards.md).
5. If reality contradicts the playbook (a library API changed, an assumption fails), stop. Write it in the devlog, and if it's architectural, write an ADR before changing course (DOC-1).
6. Finish with the exit checklist, a devlog entry, the tag, and the "Current milestone" line in `CLAUDE.md`.

## Conventions used in all playbooks
- Paths are relative to the repo root.
- `./mvnw` means `.\mvnw.cmd` on Windows PowerShell.
- Migration numbers (`V2`, `V3`, …) assume the playbooks are followed in order. If you add a migration in between, shift the numbers; never edit an applied one (DB-1).
- "Fixture repo" = a tiny hand-written Java project under `backend/src/test/resources/fixtures/` used by tests. "Target repo" = the pinned PetClinic checkout under `D:\dev\targets\`.
