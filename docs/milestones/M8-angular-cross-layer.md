# M8 — Stretch: Angular Cross-layer Analysis: Step-by-step Plan

| | |
|---|---|
| Milestone | M8, stretch (see [PLAN.md §5](../PLAN.md#5-milestones)) |
| Budget | 12–18 hours |
| Prerequisite | `m6` tagged (M7 optional) |
| Goal | Index the Angular frontend, extract `HttpClient` calls, and link them to Spring endpoints as `HTTP_CALLS` edges (`HEURISTIC` provenance). This restores the original flagship demo: *"If I change the authentication/owner service, what in the **frontend and backend** could be affected, and which tests should I run?"* |
| Key decisions applied | [ADR-0004](../adr/0004-java-parsing-javaparser.md) parser ports · SEC-4 subprocess rules · [ADR-0003](../adr/0003-content-addressed-storage.md) blob reuse |

---

## Progress tracker

| # | Step | PR | Time | Done |
|---|---|---|---|---|
| 1 | ADR: multi-repo projects + TypeScript extractor | `docs/adr-0015-ts-extractor` | 45 min | ☐ |
| 2 | `tools/ts-extractor` (TypeScript Compiler API) | `feat/ts-extractor` | 3–4 h | ☐ |
| 3 | Subprocess adapter + `TypeScriptCodeParser` | `feat/indexing-typescript` | 2–3 h | ☐ |
| 4 | Spring endpoint catalog | `feat/indexing-spring-endpoints` | 2 h | ☐ |
| 5 | URL normalization + HTTP linker → `HTTP_CALLS` | `feat/indexing-http-linker` | 2–3 h | ☐ |
| 6 | Cross-layer traversal, Q&A and impact | `feat/cross-layer-impact` | 1–2 h | ☐ |
| 7 | UI updates | `feat/fe-cross-layer` | 1–2 h | ☐ |
| 8 | Evaluation: link precision/recall + cross-layer questions | `eval/cross-layer` | 2 h | ☐ |
| 9 | Close out M8 | — | 15 min | ☐ |

---

## Step 1 — ADR-0015: multi-repo projects + TS extractor (45 min)

PetClinic's backend and frontend are **separate repositories**, so a cross-layer project needs two sources. Decide and record:

| Option | For | Against |
|---|---|---|
| A. A project has N sources; a "revision set" = one commit per source | Models reality; works for monorepos (N = 1) too | Schema change: `project_source` table; revision gets a `source_id` |
| B. Link two projects ("frontend project ↔ backend project") at analysis time | No schema change for indexing | Cross-project edges complicate isolation and traversal |

Recommended: **A**.
- `project_source(id, project_id, role BACKEND|FRONTEND|OTHER, source_type, source_location)`.
- `repository_revision.source_id`.
- A `revision_set(id, project_id)` with `revision_set_member(revision_set_id, revision_id)`.
- `HTTP_CALLS` edges belong to the revision set: add `revision_set_id` (nullable) to `symbol_edge`, or a separate `cross_edge` table. Choose the separate table, so per-revision edge recomputation stays untouched.

Also record:
- **Extractor runtime:** a Node subprocess running a bundled script (rejected alternatives: GraalJS, which is heavy and not the real TS compiler; a regex extractor, too inaccurate).
- **Subprocess rules:** SEC-4.

**Done when:** ADR-0015 is accepted, and the PLAN's scope note is updated via PR.

## Step 2 — `tools/ts-extractor` (3–4 h)

A standalone Node + TypeScript package: `tools/ts-extractor/{package.json, src/main.ts, test/}`. Dependency: `typescript`, pinned.

**Contract:**
- Input: a JSON request on **stdin**: `{ "root": "<dir>", "files": ["src/app/owners/owner.service.ts", …], "tsconfig": "tsconfig.json"? }`.
- Output: JSON on **stdout**. Errors go to stderr. Exit codes: 0 = ok, 2 = partial (per-file errors reported), 1 = fatal.

```json
{
  "extractorVersion": "ts-1",
  "files": [{
    "path": "src/app/owners/owner.service.ts",
    "symbols": [
      { "kind": "CLASS", "name": "OwnerService", "qualifiedName": "src/app/owners/owner.service.ts#OwnerService",
        "decorators": ["@Injectable({providedIn:'root'})"], "startLine": 12, "endLine": 58 },
      { "kind": "METHOD", "name": "getOwnerById", "qualifiedName": "…#OwnerService.getOwnerById",
        "signature": "getOwnerById(ownerId: string): Observable<Owner>", "startLine": 30, "endLine": 33 }
    ],
    "imports": [{ "from": "./owner", "names": ["Owner"] }],
    "httpCalls": [{
      "enclosingSymbol": "…#OwnerService.getOwnerById",
      "method": "GET",
      "urlTemplate": "{env.REST_API_URL}owners/{*}",
      "urlParts": [{ "kind": "ENV", "value": "REST_API_URL" }, { "kind": "LITERAL", "value": "owners/" }, { "kind": "DYNAMIC" }],
      "line": 31, "confidence": "HIGH"
    }],
    "components": [{ "name": "OwnerListComponent", "selector": "app-owner-list", "templateUrl": "./owner-list.component.html" }],
    "diagnostics": []
  }]
}
```

**Implementation:**
1. Build a `ts.Program` over the given files (with the project's `tsconfig` if present, else defaults), and get the `TypeChecker`.
2. **Symbols:** classes (with decorators `@Component`, `@Injectable`, `@NgModule`, `@Pipe`, `@Directive`), methods, constructors, top-level functions. Lines come from `getLineAndCharacterOfPosition` and are 1-based. Qualified names are path-based, since TS has no packages.
3. **HTTP calls:** a `CallExpression` whose callee is a property access named `get|post|put|delete|patch|request`, where the receiver's type symbol (via the checker) is `HttpClient`. Fallback heuristic: the receiver property name is `http` or `httpClient`, with confidence MEDIUM.
4. **URL resolution** (`resolveUrl(expr)`, recursive, depth-limited to 5):
   - string literal → LITERAL;
   - template literal → LITERAL heads plus DYNAMIC for `${…}` spans;
   - `a + b` → concatenate;
   - an identifier or `this.x` → follow to its initializer: a `const`, a class property initializer, or a constructor assignment `this.x = …`;
   - `environment.X` → ENV(`X`). Read `src/environments/environment*.ts` to resolve the actual value when possible, and include both;
   - anything else → DYNAMIC.

   Set confidence LOW if more than half the parts are DYNAMIC.
5. **Components:** selector, `templateUrl`, and template references to other components (optional: parse the template for `<app-…>` tags to create component → component edges).
6. **Robustness:** never throw on a single file. Collect diagnostics per file.
7. **Tests** (Node test runner or Vitest): fixtures covering a literal URL; a template URL; base URL in a field set in the constructor; `environment` + concatenation; a non-HttpClient `get` (ignored); a `request('GET', …)` form.
8. **Build:** `npm run build` → a single bundled `dist/main.js` (esbuild), so the backend runs `node dist/main.js` without `node_modules` at runtime. Commit the lockfile, and CI builds and tests the extractor.

**Done when:** running the extractor on the pinned `spring-petclinic-angular` produces HTTP calls for every service method that calls the API. Spot-check 10 by hand.

## Step 3 — Subprocess adapter + `TypeScriptCodeParser` (2–3 h) · `indexing`

1. **`TsExtractorProcess`** (`indexing.internal.parsing`), following SEC-4 strictly:
   - fixed executable from config: `devmind.indexing.ts-extractor.node-path` (absolute) and `script-path` (absolute, from the app's install dir);
   - `ProcessBuilder(List.of(nodePath, scriptPath))`, **never** a shell, with no arguments derived from repository content (file lists go through stdin JSON);
   - minimal env: `processBuilder.environment().clear()`, then set only `PATH`/`NODE_OPTIONS=--max-old-space-size=1024`;
   - working dir = the per-job temp dir holding the materialized source files;
   - timeout (e.g. 120 s per batch) → `destroyForcibly()`;
   - stdout read with a size cap (e.g. 50 MB); stderr captured, truncated and logged at WARN without code content;
   - batch the files (e.g. 300 per invocation).
2. **Container image:** the backend image needs Node. Either use a multi-stage build that copies the Node runtime and `dist/main.js`, or install Node in the runtime image. Document the image size impact.
3. **`TypeScriptCodeParser implements CodeParser`:** `supports("typescript")`, with a `version()` that combines the parser and extractor versions. Map the extractor output to `ParsedSymbol` with `symbol_key = qualifiedName` (+ `(paramCount)` for methods). Store `httpCalls` as **pending link records** for step 5 (a `ts_http_call` table, or JSON on the blob — choose and migrate).
4. `is_test` for TS: `*.spec.ts`, plus symbols in files using `describe(`/`it(` at top level.
5. **Chunking:** reuse the symbol-aware chunker (method-level chunks with headers). HTML templates are chunked as text.
6. **Tests:** the process adapter with a fake script (echo JSON, hang → timeout, huge output → cap, non-zero exit → partial or fatal handling); mapping tests from a captured extractor output fixture.

**Done when:** indexing the Angular repo produces TS symbols and chunks, with blob reuse working just as for Java.

## Step 4 — Spring endpoint catalog (2 h) · `indexing`

Build `EndpointCatalog(revisionId)` = list of `(httpMethod, pathTemplate, handlerSymbolId)`:
1. From the M2 annotations (full source text): class-level `@RequestMapping("…")` + method-level `@GetMapping/@PostMapping/@PutMapping/@DeleteMapping/@PatchMapping/@RequestMapping(method=…)`. Parse `value`/`path` (string or array) and `method`. Join the class and method paths.
2. Resolve constants in annotation values: `@RequestMapping(ApiPaths.OWNERS)` → look up the static final String field via the symbol table (one level).
3. **API-first projects:** if the controllers implement **generated** interfaces, the mapping annotations live in generated code, which isn't indexed (see the M2 step 10 and M5 pitfalls). Contingency:
   - parse the OpenAPI spec in the repo (`src/main/resources/**/openapi*.y*ml`) for `paths` → `operationId` + method;
   - map `operationId` to the controller method with the same name that overrides an interface method (`@Override`, same name);
   - mark these with confidence MEDIUM.
4. Also apply the server prefix: `server.servlet.context-path` and `spring.mvc.servlet.path` from `application*.properties|yml` (the default profile only; record if profiles differ).
5. **Tests:** class + method join; array paths; a constant reference; the OpenAPI contingency on a fixture; the context-path prefix.

**Done when:** the PetClinic REST endpoint catalog matches its documented API. Cross-check against its OpenAPI spec or Swagger UI.

## Step 5 — URL normalization + HTTP linker (2–3 h)

1. **Normalize the frontend URL template:**
   - drop the ENV base-URL part, or strip the known base URL value (e.g. `http://localhost:9966/petclinic/api/`) to a path;
   - collapse `//`;
   - ensure a leading `/`;
   - DYNAMIC segments → `{*}`;
   - drop query strings.
2. **Normalize the backend template:** `{ownerId}` → `{*}`; apply the context-path prefix.
3. **Matching** (`HttpLinker`, pure function, unit-tested):
   - the method must match (`request()` with a non-literal method → any method, confidence LOW);
   - segment-wise comparison: literal = literal (case-sensitive), or either side is `{*}`;
   - a trailing-slash difference is tolerated;
   - multiple backend matches → prefer the most literal segments; if still tied, link all with LOW.
4. **Confidence:** HIGH = method and all literal segments matched and ≤ 1 wildcard; MEDIUM = otherwise matched; LOW = an ambiguous or DYNAMIC-heavy frontend URL.
5. **Persist:** cross edges `frontend_method --HTTP_CALLS--> backend_handler`, `provenance = HEURISTIC`, with a confidence column, in the `cross_edge` table from ADR-0015 (scoped to the revision set).
6. **Unmatched calls** → stored as unresolved, with the URL template. They're reported as unknowns ("frontend calls an endpoint not found in backend at this revision"). That's useful in itself, as a contract-drift signal.
7. **Tests:** exact match; path variables; base-URL stripping; method mismatch; ambiguous match; unmatched call.

**Done when:** link precision is spot-checked manually on 15 PetClinic calls (all correct, or explained).

## Step 6 — Cross-layer traversal, Q&A, impact (1–2 h)

1. **Traversal:** extend `GraphQueries` so that, when a revision set is given, `HTTP_CALLS` cross edges join the walk (backend handler → frontend callers in reverse direction). Keep `HTTP_CALLS` in the default relation set only for revision-set analyses.
2. **Impact:**
   - backend change → impacted frontend methods/components (via the services that call the endpoint, then the components that inject the service: add an `INJECTS` edge from constructor/`inject()` parameter types in the extractor, or treat it as REFERENCES);
   - **frontend tests** (`*.spec.ts`) that reach those symbols → recommended tests, with "via HTTP" in the reason path.
3. **New VERIFIED rule:** "endpoint contract change with frontend callers". An `API_CONTRACT` finding lists the frontend call sites (HIGH when the link confidence is HIGH).
4. **Q&A:** graph expansion can now include cross-layer neighbors; label them `via="HTTP_CALLS"`. New prompt version `qa-answer.v3` if the attribute set changes (AI-1).
5. **Unknowns:** keep "frontend not analyzed" only when no frontend source is in the revision set.

**Done when:** the flagship question works end to end: a backend change shows the affected Angular service and component, and the relevant `.spec.ts` tests.

## Step 7 — UI updates (1–2 h)
1. **Project settings:** add or edit sources (backend/frontend), and index a revision set (both refs).
2. **Explorer:** a source switcher (backend/frontend), and TS syntax support in CodeMirror.
3. **Impact report and graph:** frontend nodes styled distinctly; `HTTP_CALLS` edges labelled with method + path; the confidence shown.
4. **Chat:** citations from both repos open in the correct source.

**Done when:** the cross-layer demo is fully navigable in the UI.

## Step 8 — Evaluation (2 h)
1. **Link ground truth:** label all `HttpClient` calls in the pinned Angular repo → expected backend handler. This is usually 20–40 calls. Store it in `evaluation/datasets/http-links-petclinic.yaml`.
2. **Metrics:**
   - link precision and recall overall and per confidence bucket;
   - unmatched rate.
3. **Cross-layer QA questions:** add ~8 questions ("Which Angular component triggers the backend call that creates a visit?") and evaluate them with the M4 pipeline. Report them separately and in the overall total.
4. **Cross-layer impact cases:** 3–4 backend mutations whose effect should show frontend impact.
   - Ground truth for frontend tests: run `npm test` in the Angular repo with an equivalent frontend-affecting change? That usually isn't possible, because a backend change doesn't fail frontend unit tests.
   - Instead, evaluate **impacted frontend symbols** against manual labels, and state this in the report.
5. Write `evaluation/reports/experiment-e-cross-layer.md`. Update the README results and the flagship demo section.

**Done when:** the cross-layer report is committed.

## Step 9 — Close out M8
- [ ] ADR-0015 accepted; schema migrated without breaking single-source projects
- [ ] Extractor built and tested in CI; subprocess rules (SEC-4) tested
- [ ] Endpoint catalog (including the OpenAPI contingency if needed) and linker with tests
- [ ] Cross-layer traversal, impact, Q&A and UI working
- [ ] Link and cross-layer eval reports committed; README updated; threat model updated (new subprocess, SEC-11)

Tag `m8`, devlog entry, README flagship section rewritten with the full cross-layer demo.

## Pitfalls
- **Base URLs from environment files:** different environment files (`environment.prod.ts`) can change URLs. Pick the default one, and record the choice.
- **Over-confident links:** dynamic URL building (interceptors that prefix URLs, generic `CrudService<T>` classes) defeats static matching. Mark such links LOW, and show them as heuristic.
- **Node in the backend image:** keep the extractor optional (`devmind.indexing.ts-extractor.enabled`), so Java-only users don't need Node.
- **Scope creep:** templates, routing, NgRx effects and component trees are tempting. Stop at HttpClient links + injection + spec tests, and record the rest under "Later".
