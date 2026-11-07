# M5 — Dependency Graph & Change Impact: Step-by-step Plan

| | |
|---|---|
| Milestone | M5 (see [PLAN.md §5](../PLAN.md#5-milestones)) |
| Budget | 22–30 hours, the largest milestone. Watch the cut lines. |
| Prerequisite | `m4` tagged; the mutation trial from M0 step 3.4 confirmed that tests fail on seeded changes |
| Goal | From a base…head diff, deterministically compute changed symbols, impacted symbols and tests to run. Add LLM-explained risks that are clearly labelled `VERIFIED` vs `INFERRED`. Measure test-recommendation recall against tests that actually fail. |
| Key decisions applied | [ADR-0004](../adr/0004-java-parsing-javaparser.md) provenance · [ADR-0011](../adr/0011-evaluation-as-code.md) mutation ground truth · [architecture §9](../architecture.md#9-pipeline-c--change-impact) |
| Cut first if behind | the step 11 graph visualization → a plain list of paths ([PLAN §6](../PLAN.md#6-cut-lines-if-behind-schedule-cut-in-this-order)) |

---

## Progress tracker

| # | Step | PR | Time | Done |
|---|---|---|---|---|
| 1 | Edge schema + `EdgeResolver` with SymbolSolver | `feat/indexing-edges` | 4–5 h | ☐ |
| 2 | Interface→implementation and override edges | `feat/indexing-edges-impl` | 1–2 h | ☐ |
| 3 | Bounded traversal (recursive CTE) + neighbors API | `feat/graph-traversal` | 2–3 h | ☐ |
| 4 | Enable graph expansion in retrieval + Experiment B | `eval/experiment-b` | 2 h | ☐ |
| 5 | Diff → hunks → changed symbols | `feat/impact-diff` | 3 h | ☐ |
| 6 | Impact traversal + test recommendation | `feat/impact-tests` | 2–3 h | ☐ |
| 7 | Deterministic (VERIFIED) findings | `feat/impact-verified-findings` | 1–2 h | ☐ |
| 8 | LLM (INFERRED) findings: prompt, validation, dedup | `feat/impact-inferred-findings` | 2–3 h | ☐ |
| 9 | Analysis schema, job handler, endpoints | `feat/impact-api` | 2 h | ☐ |
| 10 | Mutation ground truth (patches + recorder) | `eval/impact-ground-truth` | 2–3 h | ☐ |
| 11 | UI: analysis form, report, impact graph | `feat/fe-impact` | 3–4 h | ☐ |
| 12 | Impact evaluation run + close out | `eval/impact-baseline` | 1–2 h | ☐ |

---

## Step 1 — Edges with SymbolSolver (4–5 h) · `indexing`

### 1.1 Schema · `V7__symbol_edge.sql`
```sql
CREATE TABLE symbol_edge (
  id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  revision_id      bigint NOT NULL REFERENCES repository_revision(id) ON DELETE CASCADE,
  source_symbol_id bigint NOT NULL REFERENCES code_symbol(id) ON DELETE CASCADE,
  target_symbol_id bigint REFERENCES code_symbol(id) ON DELETE CASCADE,
  target_ref       text,                       -- textual target when unresolved / external
  relation         text NOT NULL CHECK (relation IN ('CALLS','IMPORTS','EXTENDS','IMPLEMENTS','OVERRIDES','REFERENCES','HTTP_CALLS')),
  provenance       text NOT NULL CHECK (provenance IN ('STATIC_RESOLVED','STATIC_NAME_MATCH','HEURISTIC','LLM_INFERRED')),
  line             int,
  CHECK (target_symbol_id IS NOT NULL OR target_ref IS NOT NULL)
);
CREATE INDEX symbol_edge_src_idx ON symbol_edge (revision_id, source_symbol_id);
CREATE INDEX symbol_edge_tgt_idx ON symbol_edge (revision_id, target_symbol_id);
CREATE INDEX symbol_edge_source_fk_idx ON symbol_edge (source_symbol_id);
CREATE INDEX symbol_edge_target_fk_idx ON symbol_edge (target_symbol_id);
```

### 1.2 Port
```java
public interface EdgeResolver {                                   // indexing SPI
  String version();                                               // "edges-1"; joins index_config_hash
  EdgeResolution resolve(RevisionSnapshotView snapshot);          // whole-revision input (ADR-0003)
}
```

### 1.3 `JavaSymbolSolverEdgeResolver`
1. **Type solver** per revision:
   - `CombinedTypeSolver(new ReflectionTypeSolver(false), new JavaParserTypeSolver(<temp dir of source roots>))`;
   - plus a `JarTypeSolver` per JAR when `devmind.indexing.dependency-jars-dir` is configured (optional; record whether it was used in stats).
2. **Source roots:** materialize the revision's Java blobs into a per-job temp dir laid out by path (it's read-only input, deleted after). Detect source roots as the directories above the package path (`…/src/main/java`, `…/src/test/java`).
3. Re-parse each Java blob with the symbol-solver-configured parser, **only for edge resolution**. Declarations stay those from M2.
4. **Extract** (with source symbol = the enclosing method/constructor, or the type for type-level usages):

   | AST node | Relation | Resolve via |
   |---|---|---|
   | `MethodCallExpr` | `CALLS` | `call.resolve()` → `ResolvedMethodDeclaration.getQualifiedSignature()` |
   | `ObjectCreationExpr` | `CALLS` (to the constructor) | `resolve()` |
   | `ClassOrInterfaceType` in fields, params, returns, locals | `REFERENCES` | `resolve()` → the type's qualified name |
   | `ImportDeclaration` | `IMPORTS` (type → type) | name match within the revision |
   | `extends` / `implements` clauses | `EXTENDS` / `IMPLEMENTS` | `resolve()` |
   | `MethodReferenceExpr` | `CALLS` | `resolve()` where supported; otherwise name match |
5. **Map the resolved signature → `code_symbol`:** convert `ResolvedMethodDeclaration` to the same `symbol_key` format as M2 (qualified name + erased simple param types), then look it up in this revision's `symbol_key → symbol_id` map. Use **one map per revision**, built once.
6. **Resolution failure** (`UnsolvedSymbolException`, `UnsupportedOperationException`, `RuntimeException` from the solver) → fall back to a **name match**: method name + argument count among this revision's methods.
   - exactly one candidate → edge with `STATIC_NAME_MATCH`;
   - several or none → **no `target_symbol_id`**, `target_ref = "<name>/<argc>"`, counted as unresolved.
7. **Targets outside the repo** (JDK, Spring) → keep `target_ref = qualified signature`, `target_symbol_id = null`, `STATIC_RESOLVED`. These are useful as "uses Spring Security X" signals, and never traversed.
8. **Stats:** `callSites`, `resolved`, `nameMatched`, `unresolved`, `external`, `resolutionRate = (resolved + nameMatched-in-repo) / in-repo call sites`.
9. **Limits:** a per-file resolution timeout (some generic chains are slow); on timeout, fall back to the name match for that file and count it.
10. **Batch insert** edges (DB-8). Edges are **always recomputed per revision**: delete the revision's edges, then insert, all in the edge phase.

Fixture tests, extended from the M2 golden fixtures with a small multi-file fixture project:
- a direct call;
- a call through a field of interface type;
- an overloaded call resolved by argument type;
- a static call;
- a constructor call;
- a method reference;
- a call to a JDK method (external);
- a deliberately unresolvable call (name match);
- an ambiguous overload by name (unresolved).

Assert relation, provenance and target for each.

**Done when:** PetClinic edges are indexed and `resolutionRate` is recorded in the devlog. If it's below ~70%, try the JAR solver with `mvn dependency:copy-dependencies` output from the target, and record both numbers ([ADR-0004](../adr/0004-java-parsing-javaparser.md) "revisit when").

## Step 2 — Interface→implementation and overrides (1–2 h)

1. **`OVERRIDES` edges:** for each method in a class, if the class `IMPLEMENTS`/`EXTENDS` (transitively) a type that declares a method with the same name and erased parameter types, add `impl#method --OVERRIDES--> iface#method` (`STATIC_RESOLVED` when both sides are in-repo and resolved).
2. **Interface dispatch:** Spring injects interfaces, so callers hit `OwnerService#findById` and never `ClinicServiceImpl#findById`. The traversal (step 3) treats `OVERRIDES` as linking callers of the interface method to the implementation, and the reverse. Store no extra edges; traversal handles it.
3. **Multiple implementations:** all are linked, and `provenance = HEURISTIC` when the interface has more than one implementation (the runtime bean is unknown, [ADR-0004](../adr/0004-java-parsing-javaparser.md)).
4. **Tests:** single impl → `STATIC_RESOLVED` override; two impls → both `HEURISTIC`; abstract class + subclass.

**Done when:** "who calls `ClinicServiceImpl#findOwnerById`" (or the real name) reaches the controller through the interface.

## Step 3 — Bounded traversal + neighbors API (2–3 h)

`GraphQueries.traverse(revisionId, seedSymbolIds, direction, relations, maxDepth, maxFanOut, maxNodes)`, using a recursive CTE with LATERAL fan-out limiting and the `CYCLE` clause (DB-9).

Example for **reverse** traversal, finding who depends on the seeds:
```sql
WITH RECURSIVE walk(symbol_id, depth, via_edge_id, via_relation) AS (
    SELECT s, 0, NULL::bigint, NULL::text FROM unnest(:seedIds::bigint[]) AS s
  UNION ALL
    SELECT nxt.symbol_id, w.depth + 1, nxt.edge_id, nxt.relation
    FROM walk w
    CROSS JOIN LATERAL (
        SELECT e.source_symbol_id AS symbol_id, e.id AS edge_id, e.relation
        FROM symbol_edge e
        WHERE e.revision_id = :revisionId
          AND e.target_symbol_id = w.symbol_id
          AND e.relation = ANY(:relations)
          AND e.provenance = ANY(:provenances)           -- never LLM_INFERRED by default
        ORDER BY e.id
        LIMIT :maxFanOut + 1                             -- +1 lets Java detect truncation
    ) nxt
    WHERE w.depth < :maxDepth
) CYCLE symbol_id SET is_cycle USING cycle_path
SELECT DISTINCT ON (symbol_id) symbol_id, depth, via_edge_id, via_relation, cycle_path
FROM walk
WHERE NOT is_cycle
ORDER BY symbol_id, depth
LIMIT :maxNodes + 1;
```
- **Override hops:** include `OVERRIDES` in `relations`, and add a mirrored branch in the LATERAL (`e.source_symbol_id = w.symbol_id AND e.relation = 'OVERRIDES'` → `e.target_symbol_id`). An interface-method seed then also reaches its implementations, and the reverse. Alternatively, precompute "dispatch groups" in Java and seed with the whole group. Pick one and test it.
- **Truncation:**
  - if a node's fan-out query returned `maxFanOut + 1` rows → mark that node `truncated`. Detect this with a follow-up count query for the frontier, or by counting in Java;
  - if total rows = `maxNodes + 1` → mark the traversal `truncated`.

  Both become **unknowns** in reports ([architecture §9](../architecture.md#9-pipeline-c--change-impact)).
- **Paths:** reconstruct a "why" path per node from `cycle_path` (the array of visited symbol IDs) for display and test rationale.
- **Defaults** (`devmind.impact.traversal`): depth 3, fan-out 50, nodes 500, relations `CALLS, REFERENCES, OVERRIDES`, provenances `STATIC_RESOLVED, STATIC_NAME_MATCH, HEURISTIC`.

**Neighbors API:** `GET /api/projects/{projectId}/revisions/{sha}/symbols/{symbolId}/neighbors?direction=in|out&relation=&depth=1..3`, owner-scoped and in the authz matrix.

Tests:
- A linear chain respects depth.
- A cycle terminates.
- Fan-out truncation is detected.
- Node limit truncation is detected.
- An interface hop.
- `LLM_INFERRED` edges are excluded by default (AI-12).
- Revision isolation: edges from another revision are never followed.

**Done when:** the traversal tests pass, and the neighbors endpoint works for PetClinic symbols.

## Step 4 — Graph expansion in retrieval + Experiment B (2 h)

1. Implement the expansion that was a no-op in M3: for the top `seeds` (5) fused items that map to a method symbol, add their depth-1 neighbors (callers and callees via `CALLS`/`OVERRIDES`).
   - Map neighbor symbols → their member chunks.
   - Mark each one `via = "CALLS(E2)"` or `"CALLED_BY(E2)"`.
   - Insert them **after their seed** in packing order, within the token budget.
2. Show the `via` label in the prompt evidence attributes (`via="CALLED_BY E2"`), so the model sees how the pieces relate. This makes a new prompt version, **`qa-answer.v2`** (AI-1). Keep v1.
3. **Experiment B** on the dev split, focused on the `cross_file_trace` and `dependency_callers` categories (and also reported overall):
   - `hybrid` (expansion off, `qa-answer.v1`) vs `hybrid-graph` (expansion on, `qa-answer.v2`);
   - retrieval metrics (Full-recall@10 matters most here) **and** QA metrics (manual review of the changed answers only).
4. Write `evaluation/reports/experiment-b.md`. Record whether expansion added irrelevant context: count the expansion items cited vs not cited.
5. Set the default `graph-expansion.enabled` according to the result, and say why in the report.

**Done when:** `experiment-b.md` is committed and the default chosen.

## Step 5 — Diff → changed symbols (3 h) · `impact` module

1. **Revisions:** an analysis needs both base and head READY. `ImpactApi.startAnalysis` triggers missing indexing (blob reuse makes head indexing cheap) and waits for it in the job, not in the HTTP request.
2. **Remote sources:** both commits must be present. When the analysis is a remote `GIT_PUBLIC` project indexed shallowly, fetch the base SHA explicitly (`git fetch --depth=N origin <sha>` equivalent in JGit), or deepen until it's reachable, within the clone limits.
3. **Diff** (`GitDiffService`, behind a port in `impact`, adapter in `impact.internal`, using JGit):
   - `DiffFormatter` with `setRepository(repo)`, `setContext(0)`, `setDiffComparator(RawTextComparator.DEFAULT)`, `setDetectRenames(true)`;
   - `scan(baseTree, headTree)` → `DiffEntry` list → `toFileHeader(entry).toEditList()`;
   - each `Edit` has 0-based, end-exclusive `beginA/endA` (base) and `beginB/endB` (head). Convert to 1-based inclusive ranges.
   - Only paths that pass the ingestion filters count. Excluded paths are reported as "changed but not analyzed".
   - **Normalization caveat:** blobs are LF-normalized and redacted, but Git diffs raw bytes. CRLF-only changes appear in Git but not in the blobs. Detect "diff hunk but identical blob hash" and report it as a whitespace or line-ending change, not as a symbol change.
4. **`ChangedSymbolDetector`** (pure logic, unit-tested on hand-built inputs):

   | Case | Rule | `change_type` |
   |---|---|---|
   | Head symbol range intersects an added or modified head line range | the symbol (and its enclosing type) is changed. Prefer the **innermost** member; the type is changed only if a line outside members changed. | `MODIFIED` |
   | `symbol_key` exists in head, not in base | | `ADDED` |
   | `symbol_key` exists in base, not in head, and no head symbol with the same parent + name | | `DELETED` |
   | Same parent + name, different `symbol_key` (parameter types changed) | | `SIGNATURE_CHANGED` |
   | Pure deletion hunk (`endB == beginB`) inside a head member | the member containing that position is `MODIFIED` | `MODIFIED` |
   | Renamed file (`DiffEntry.RENAME`) | match symbols by `symbol_key` across old and new paths. A moved symbol with an identical body hash is unchanged. | — |
5. **Tests:**
   - method body change → `MODIFIED` method only;
   - field added → `ADDED` field + `MODIFIED` type;
   - method removed → `DELETED`;
   - parameter type changed → `SIGNATURE_CHANGED`;
   - file renamed without changes → nothing;
   - CRLF-only change → whitespace notice;
   - change in an excluded path → "not analyzed".

**Done when:** every row has a test using two fixture commits built with JGit.

## Step 6 — Impact traversal + test recommendation (2–3 h)

1. **Seeds:** `MODIFIED`, `SIGNATURE_CHANGED` and `DELETED` symbols.
   - Traverse **head** for modified and signature-changed seeds, and **base** for deleted ones: who used them before deletion.
   - Use reverse direction with the step 3 defaults.
2. **`impacted_symbol` rows:** `symbol_id, depth, via_path (array of symbol ids + relations), is_test`.
3. **Test recommendation (`TestRecommender`):**
   - **Reachability:** impacted symbols with `is_test = true` (a test method that transitively calls a changed symbol). Reason: `"calls <changed symbol> via <path>"`, with confidence by depth: 1 → HIGH, 2 → MEDIUM, 3 → LOW.
   - **Naming convention:** for each changed type `Foo`, test classes named `FooTest`, `FooTests`, `FooIT`, `FooIntegrationTest` in the revision. Reason: `"naming convention"`, confidence MEDIUM, at class level (all its test methods).
   - **Merge** by test method, keeping the strongest reason, and listing others as secondary.
   - **Output:** `RecommendedTest(testSymbolKey, path, startLine, endLine, granularity METHOD|CLASS, confidence, reasons[])`.
   - **Unknowns:** changed symbols with **no** reachable test are listed. They feed the VERIFIED finding "no test reaches this change".
4. **Tests:** a test reaching a change at depth 2 → MEDIUM with the correct path; naming-only match; merge of both; untested change reported.

**Done when:** for a hand-made change in the fixture project, the recommended tests and reasons are exactly as expected.

## Step 7 — Deterministic (VERIFIED) findings (1–2 h)

`VerifiedFindingRules`, each a small class implementing `FindingRule`, unit-tested on its own:

| Rule | Condition | Category · Severity |
|---|---|---|
| Public signature change | `SIGNATURE_CHANGED` or `DELETED` on a `public` or `protected` member with ≥ 1 in-repo caller | `API_COMPATIBILITY` · HIGH if callers ≥ 5, else MEDIUM |
| Dangling reference | `DELETED` symbol still referenced by head symbols (edges in head with `target_ref` matching, or name-match candidates) | `BROKEN_REFERENCE` · HIGH |
| No reachable test | a changed method with no recommended test | `TEST_GAP` · MEDIUM |
| Security-sensitive change | changed member, or its type, carries `@PreAuthorize`, `@Secured`, `@RolesAllowed`, or lives in a class annotated `@Configuration` that defines a `SecurityFilterChain` bean | `SECURITY` · HIGH |
| Transaction boundary change | changed member annotated `@Transactional`, or annotation added or removed | `DATA_INTEGRITY` · MEDIUM |
| HTTP contract change | changed member annotated with `@RequestMapping`/`@GetMapping`/… or a request/response DTO type used by one | `API_CONTRACT` · MEDIUM |
| Wide blast radius | impacted symbol count ≥ 50, or traversal truncated | `BLAST_RADIUS` · MEDIUM |

Each finding has `basis = VERIFIED`, `confidence = HIGH`, evidence = the relevant symbols (path + lines), and a `rationale` generated **from a template** with the facts filled in. No LLM is involved.

**Done when:** each rule has positive and negative tests.

## Step 8 — LLM (INFERRED) findings (2–3 h)

1. **Prompt `impact-risk.v1`** (system + user templates):
   - **system:** the same untrusted-evidence rules as QA; "Identify plausible risks of this change that are NOT already listed as verified findings. Each finding must cite evidence ids. Do not claim certainty. Do not repeat verified findings.";
   - **user:**
     - the diff hunks, with limited context lines, as evidence blocks `D1..Dn`;
     - changed symbols with their change types;
     - impacted symbols as evidence `E1..En` (the member chunk, including the via path), budget-packed with the top-ranked by depth and caller count;
     - the verified findings, as a summary list, so the model avoids duplicates.
2. **Output records:**
   ```java
   record RiskModelOutput(@NotNull @Size(max = 10) List<@Valid RiskItem> findings) {}
   record RiskItem(@NotNull Category category, @NotNull Severity severity, @NotNull Confidence confidence,
                   @NotBlank @Size(max = 200) String title, @NotBlank @Size(max = 2000) String rationale,
                   @NotEmpty @Size(max = 8) List<@Pattern(regexp = "[DE]\\d{1,3}") String> evidenceIds,
                   @NotNull @Size(max = 6) List<@NotBlank String> recommendedChecks) {}
   ```
3. **Validation:** the same citation rules as QA. Findings that lose all evidence are **dropped**, not converted (counted). Severity is capped at `MEDIUM` for INFERRED findings, so an inferred finding can't outrank a verified HIGH. `basis = INFERRED` always (AI-12).
4. **Dedup:** drop an INFERRED finding if a VERIFIED finding has the same category and an overlapping evidence symbol. Merge INFERRED duplicates with the same category and the same primary symbol.
5. **Degradation:** model invalid or unavailable → the report still completes with VERIFIED findings plus a warning "AI risk analysis unavailable".
6. **Tests (FakeChatModel):** valid findings stored as INFERRED; an invalid evidence ID is dropped; severity cap; dedup against verified; model failure → report with a warning.

**Done when:** all paths are tested, and the prompt snapshot is committed.

## Step 9 — Analysis schema, job, endpoints (2 h)

**`V8__analysis.sql`:**
```sql
CREATE TABLE analysis_run (
  id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  public_id         uuid   NOT NULL UNIQUE,
  project_id        bigint NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  base_revision_id  bigint REFERENCES repository_revision(id) ON DELETE CASCADE,
  head_revision_id  bigint REFERENCES repository_revision(id) ON DELETE CASCADE,
  base_ref          text   NOT NULL,
  head_ref          text   NOT NULL,
  status            text   NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
  job_id            bigint REFERENCES job(id) ON DELETE SET NULL,
  config            jsonb  NOT NULL,
  summary           jsonb  NOT NULL DEFAULT '{}'::jsonb,   -- counts, unknowns, warnings, timings
  created_at        timestamptz NOT NULL,
  finished_at       timestamptz
);
CREATE INDEX analysis_run_project_idx ON analysis_run (project_id);
CREATE INDEX analysis_run_base_idx    ON analysis_run (base_revision_id);
CREATE INDEX analysis_run_head_idx    ON analysis_run (head_revision_id);
CREATE INDEX analysis_run_job_idx     ON analysis_run (job_id);

CREATE TABLE changed_symbol (
  analysis_run_id bigint NOT NULL REFERENCES analysis_run(id) ON DELETE CASCADE,
  symbol_key      text   NOT NULL,
  base_symbol_id  bigint REFERENCES code_symbol(id) ON DELETE CASCADE,
  head_symbol_id  bigint REFERENCES code_symbol(id) ON DELETE CASCADE,
  change_type     text   NOT NULL CHECK (change_type IN ('ADDED','MODIFIED','DELETED','SIGNATURE_CHANGED')),
  PRIMARY KEY (analysis_run_id, symbol_key)
);
CREATE INDEX changed_symbol_base_idx ON changed_symbol (base_symbol_id);
CREATE INDEX changed_symbol_head_idx ON changed_symbol (head_symbol_id);

CREATE TABLE impacted_symbol (
  analysis_run_id bigint NOT NULL REFERENCES analysis_run(id) ON DELETE CASCADE,
  symbol_id       bigint NOT NULL REFERENCES code_symbol(id) ON DELETE CASCADE,
  depth           int    NOT NULL,
  via_path        jsonb  NOT NULL,
  is_test         boolean NOT NULL,
  PRIMARY KEY (analysis_run_id, symbol_id)
);
CREATE INDEX impacted_symbol_symbol_idx ON impacted_symbol (symbol_id);

CREATE TABLE risk_finding (
  id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  analysis_run_id   bigint NOT NULL REFERENCES analysis_run(id) ON DELETE CASCADE,
  category          text   NOT NULL,
  severity          text   NOT NULL CHECK (severity IN ('LOW','MEDIUM','HIGH')),
  confidence        text   NOT NULL CHECK (confidence IN ('LOW','MEDIUM','HIGH')),
  basis             text   NOT NULL CHECK (basis IN ('VERIFIED','INFERRED')),
  title             text   NOT NULL,
  rationale         text   NOT NULL,
  evidence          jsonb  NOT NULL,
  recommended_tests jsonb  NOT NULL DEFAULT '[]'::jsonb,
  validation        jsonb  NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX risk_finding_run_idx ON risk_finding (analysis_run_id);
```
(Recommended tests at report level go in `summary.recommendedTests`. The per-finding `recommended_tests` holds checks tied to that finding.)

**`AnalysisJobHandler`** (job type `ANALYSIS`). Each phase is recorded in progress and heartbeated:
1. ensure revisions;
2. diff;
3. changed symbols;
4. traversal;
5. tests;
6. verified findings;
7. inferred findings;
8. persist and finish.

Idempotency key = `baseSha + headSha + configHash`.

**Endpoints** (owner-scoped, added to the authz matrix):
- `POST /api/projects/{projectId}/analyses {baseRef, headRef}` → 202 `{analysisId, jobId}`;
- `GET /api/analyses/{analysisId}` → status, summary, changed symbols, recommended tests, unknowns;
- `GET /api/analyses/{analysisId}/findings?basis=&severity=` → findings;
- `GET /api/analyses/{analysisId}/graph` → nodes and edges for the UI (capped at 150 nodes, with a `truncated` flag).

**Integration test:** the fixture project with a two-commit change → the full report, asserted end to end with FakeChatModel.

**Done when:** an analysis of two PetClinic commits (e.g. pinned SHA vs. a local commit you make) completes through the API.

## Step 10 — Mutation ground truth (2–3 h) · `evaluation/impact/petclinic-rest/`

1. **Write 10–15 mutation patches** against the pinned SHA, on a scratch branch of the target clone. Mix:
   - condition flips in a service method;
   - a changed validation rule;
   - a changed repository query method;
   - a changed DTO field mapping;
   - a changed controller status code;
   - a signature change (with the callers updated, so it still compiles);
   - a deleted unused method (expected: no failing tests, which checks precision);
   - a change in a class with no tests (expected: TEST_GAP).

   Each patch: `git diff > NN-<slug>.patch`, then `git checkout -- .`
2. **Recorder script** `evaluation/impact/record.ps1` (ADR-0011 calls it `record.sh`; the PowerShell version is the Windows equivalent):
   ```powershell
   param([Parameter(Mandatory)][string]$TargetPath, [Parameter(Mandatory)][string]$Sha, [string]$PatchDir = $PSScriptRoot + '\petclinic-rest')
   $ErrorActionPreference = 'Stop'
   Push-Location $TargetPath
   try {
     git checkout --quiet $Sha
     foreach ($p in Get-ChildItem $PatchDir -Filter *.patch | Sort-Object Name) {
       git checkout --quiet -- . ; git clean -fdq
       git apply --whitespace=nowarn $p.FullName
       & .\mvnw.cmd -q test 2>&1 | Out-Null            # failures expected; don't stop
       $failed = Select-Xml -Path target\surefire-reports\TEST-*.xml -XPath '//testcase[failure or error]' |
                 ForEach-Object { "$($_.Node.classname)#$($_.Node.name)" } | Sort-Object -Unique
       $out = [IO.Path]::ChangeExtension($p.FullName, '.failing.txt')
       Set-Content -Encoding UTF8 -Path $out -Value $failed
       Write-Host ("{0}: {1} failing" -f $p.Name, @($failed).Count)
     }
   } finally { git checkout --quiet -- . ; git clean -fdq; Pop-Location }
   ```
   If the target needs JDK 21 (M0 step 3.3), set `JAVA_HOME` before running.
3. **Dataset** `evaluation/datasets/impact-petclinic-rest.yaml`:
   ```yaml
   datasetVersion: 1
   target: petclinic-rest
   baseRevision: <PINNED_SHA>
   cases:
     - id: i01
       patch: evaluation/impact/petclinic-rest/01-owner-lastname-validation.patch
       expectedChangedSymbols: ["<qualified#method(params)>"]
       failingTests: evaluation/impact/petclinic-rest/01-owner-lastname-validation.failing.txt
       notes: "Flip of the lastName length check"
   ```
   To analyze a patch, the evaluation runner needs a **head revision**:
   - on a scratch clone of the target, apply the patch and commit it to a throwaway local branch;
   - index that commit, and use it as head.

   Do this automatically in `--eval.mode=impact`, using a per-case temp clone of the local target.
4. **Sanity checks:** every patch applies cleanly; every behaviour-changing patch has ≥ 1 failing test unless intended otherwise; record those exceptions in `notes`.

**Done when:** patches, `.failing.txt` files and the dataset are committed.

## Step 11 — UI: impact (3–4 h) · `features/impact`

1. **Analysis form** (`/projects/:projectId/analyses/new`): pick base and head from the indexed revisions (or type refs), then submit → poll the job (reuse the M2 polling).
2. **Report page** (`/analyses/:analysisId`), with sections:
   1. **Summary:** counts and unknowns. Unknowns are prominent: truncation, unresolved calls, excluded paths, "frontend not analyzed".
   2. **Changed symbols:** a table with change type → deep link to code (head or base revision).
   3. **Findings:** grouped by severity, each with a **text badge** `VERIFIED` or `INFERRED` (FE-11: never colour alone). Expand to see the rationale and evidence links.
   4. **Recommended tests:** confidence, reason and an expandable "why" path (`Test#method → … → Changed#method`).
   5. **Impact graph:** Cytoscape, ≤ 150 nodes, with the dagre (or elk) layout.
      - node shape by kind; test nodes distinct; changed nodes outlined;
      - edge style by provenance: solid = STATIC_RESOLVED, dashed = STATIC_NAME_MATCH, dotted = HEURISTIC;
      - a legend;
      - clicking a node opens its code.
3. **Cut line:** if behind schedule, replace the graph with an indented path list. The report is complete without it.
4. **Tests:** badge rendering; grouping; graph data mapping (pure function, from the API to Cytoscape elements); the truncation notice.

**Done when:** an analysis in the browser shows all sections, and every link opens the right lines.

## Step 12 — Impact evaluation + close out (1–2 h)

1. **`--eval.mode=impact`**, per case:
   - create the head revision;
   - run the analysis;
   - compare with the ground truth.
2. **Metrics:**
   - **Changed-symbol precision/recall** vs `expectedChangedSymbols`.
   - **Test recall** = |recommended ∩ failing| ÷ |failing|, primary, at **method** level. Class-level recommendations count as covering every method of that class. Report class-level recall separately too.
   - **Test precision** = |recommended ∩ failing| ÷ |recommended|. Report it with the **lower-bound caveat** ([ADR-0011](../adr/0011-evaluation-as-code.md)).
   - **Recall by confidence bucket:** HIGH, MEDIUM, LOW.
   - **TEST_GAP correctness** on the "no tests" cases.
   - **Risk-finding precision** (manual): review INFERRED findings in `impact-petclinic-rest.review.yaml` (actionable and relevant: yes/no), and report VERIFIED and INFERRED separately.
   - **Latency** per analysis, and LLM tokens and cost.
3. Commit the report and the review summary.

### Exit checklist (from PLAN.md M5)
- [ ] Edges with provenance; resolution rate recorded
- [ ] Bounded traversal with cycle, fan-out and node limits; truncation surfaces as unknowns
- [ ] A real diff produces a report in which every finding is labelled VERIFIED or INFERRED, with evidence
- [ ] Recommended tests have reasons and paths
- [ ] Mutation ground truth committed; impact eval report committed (test recall and precision with caveat)
- [ ] Experiment B committed, and the graph-expansion default justified
- [ ] Authz matrix covers all analysis endpoints

### Wrap-up
Devlog entry (resolution rate, test recall, surprises), "Current milestone" → M6, tag `m5`.

## Pitfalls
- **SymbolSolver memory and time:** reuse one `CombinedTypeSolver` per revision (it caches), and clear it after the job. Don't build one per file.
- **Generated sources:** if PetClinic's controllers implement OpenAPI-generated interfaces (excluded from indexing), `IMPLEMENTS` edges to them stay unresolved. The UI should show these as unknowns, not as a clean graph.
- **Lombok-free but MapStruct:** generated mapper implementations are absent. Calls into mapper interfaces stop at the interface. Say so in the limitations.
- **Overfitting test recommendation to the patches:** design the rules first, then run the eval. Don't add a special case per failing patch.
- **Severity inflation:** keep the INFERRED severity cap. Reviewers trust a tool that undersells.
