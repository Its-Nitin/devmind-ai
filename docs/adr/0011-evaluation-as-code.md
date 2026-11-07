# ADR-0011: Evaluation as code; mutation-derived test ground truth
- Status: Accepted
- Date: 2026-11-06

## Context
Measured quality is the main differentiator of this project. The blueprint's test-recommendation and risk metrics depend on human judgement alone, and its dataset lives in a DB table (`evaluation_case`), which is hard to review and diff.

## Options considered
**Where datasets live:** DB table (blueprint) vs **versioned YAML in `evaluation/datasets`**. Files win: reviewable in PRs, diffable, hashable, and usable without a running app.

**Runner:** Python scripts (rich ecosystem, but a second stack calling the API) vs **a Java runner inside the backend under the `eval` profile** (calls application services directly, shares config, no HTTP noise in latency). Java wins. Python or a notebook is allowed for charts only, reading the JSON reports.

**Ground truth for "which tests should I run":**
| Option | For | Against |
|---|---|---|
| Manual labelling | Easy to start | Subjective, slow |
| Historical commits (tests modified in the same commit) | Real data | Noisy; co-change ≠ coverage |
| Per-test coverage (JaCoCo per test) | Precise "covers" relation | Complex to set up per target repo |
| **Seeded mutations: apply a small curated patch, run the target's test suite, record failing tests** | Objective, reproducible, cheap on a fast suite | Failing tests are a *lower bound* of relevant tests: a test can cover changed code without detecting this mutation |

## Decision
- Datasets: `qa-<repo>.yaml`, `impact-<repo>.yaml`, plus human review files `*.review.yaml` for manual scores.
- Runner: `EvaluationRunner` (profile `eval`) → `evaluation/reports/<UTC-timestamp>-<config-name>.{json,md}`, and a row in `evaluation_run`.
- Impact ground truth: `evaluation/impact/<repo>/NN-<slug>.patch` + `NN-<slug>.failing.txt`, recorded by `evaluation/impact/record.sh` (applies the patch on a clean checkout at the pinned SHA, runs `mvn -q test`, parses Surefire XML). The ground truth is recorded once and committed, so reruns don't need the target build.
- Metrics: test recall = |recommended ∩ failing| / |failing| (primary), and precision as secondary, reported with the lower-bound caveat.
- Every report records: DevMind git SHA, dataset hash, target repo SHA, index config hash, embedding model + digest, chat model + params, retrieval config.
- **Report real numbers, including regressions.** Don't delete reports: newer reports supersede older ones, and old ones remain as history.

## Consequences
- \+ A rare portfolio claim: "test recommendations reach X% recall against tests that actually failed on 12 seeded changes."
- − Recording ground truth requires building the target repo once (local only, a trusted public repo, not part of the product).
