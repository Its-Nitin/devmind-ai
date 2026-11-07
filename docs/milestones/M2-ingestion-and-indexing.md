# M2 — Ingestion & Structural Index: Step-by-step Plan

| | |
|---|---|
| Milestone | M2 (see [PLAN.md §5](../PLAN.md#5-milestones)) |
| Budget | 18–25 hours |
| Prerequisite | `m1` tagged |
| Goal | Index a Java repository at a pinned commit, idempotently and incrementally. Browse files, symbols and line-accurate source in the UI. |
| Key decisions applied | [ADR-0003](../adr/0003-content-addressed-storage.md) content-addressed blobs · [ADR-0004](../adr/0004-java-parsing-javaparser.md) JavaParser · [ADR-0009](../adr/0009-db-backed-job-queue.md) DB job queue |
| Not in M2 | Embeddings (M3) and dependency edges (M5). Leave clean seams for both. |

**First milestone check (from the blueprint):** given PetClinic and a class name, return the correct file, qualified name, line range and source.

---

## Progress tracker

| # | Step | PR | Time | Done |
|---|---|---|---|---|
| 1 | Schema for revisions, blobs, symbols, chunks | `feat/indexing-schema` | 60 min | ☐ |
| 2 | Job worker (claim, lease, heartbeat, retry) | `feat/job-worker` | 2–3 h | ☐ |
| 3 | Ingestion: local and remote source snapshots | `feat/ingestion-sources` | 3–4 h | ☐ |
| 4 | Ingestion: filters, normalization, secret redaction | `feat/ingestion-filters` | 2–3 h | ☐ |
| 5 | Java parser and symbol extraction (golden tests) | `feat/java-parser` | 3–4 h | ☐ |
| 6 | Identifier splitting and symbol-aware chunking | `feat/chunking` | 2 h | ☐ |
| 7 | Indexing orchestration (blob reuse, batching, READY) | `feat/indexing-pipeline` | 3 h | ☐ |
| 8 | Read APIs (revisions, files, source slices, symbols) | `feat/indexing-read-api` | 2 h | ☐ |
| 9 | UI: indexing status, file tree, code viewer, symbol search | `feat/fe-explorer` | 3–4 h | ☐ |
| 10 | Index PetClinic end-to-end, then close out | — | 60 min | ☐ |

Module placement: steps 3–4 go in **`ingestion`**, steps 1 and 5–8 in **`indexing`**, step 2 in **`job`**.

---

## Step 1 — Schema (60 min) · `V2__indexing_core.sql`

```sql
CREATE TABLE repository_revision (
  id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id        bigint      NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  commit_sha        char(40)    NOT NULL CHECK (commit_sha ~ '^[0-9a-f]{40}$'),
  index_config_hash char(64)    NOT NULL,
  status            text        NOT NULL CHECK (status IN ('INDEXING','READY','FAILED')),
  stats             jsonb       NOT NULL DEFAULT '{}'::jsonb,
  created_at        timestamptz NOT NULL,
  ready_at          timestamptz,
  CONSTRAINT revision_uk UNIQUE (project_id, commit_sha, index_config_hash)
);

CREATE TABLE file_blob (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id     bigint   NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  content_hash   char(64) NOT NULL,
  parser_version text     NOT NULL,
  language       text     NOT NULL,
  size_bytes     int      NOT NULL CHECK (size_bytes >= 0),
  line_count     int      NOT NULL CHECK (line_count >= 0),
  content        text     NOT NULL,
  parse_status   text     NOT NULL CHECK (parse_status IN ('OK','PARSE_FAILED','SKIPPED')),
  redaction_count int     NOT NULL DEFAULT 0,
  CONSTRAINT file_blob_uk UNIQUE (project_id, content_hash, parser_version)
);

CREATE TABLE revision_file (
  revision_id  bigint NOT NULL REFERENCES repository_revision(id) ON DELETE CASCADE,
  path         text   NOT NULL CHECK (char_length(path) <= 1000),
  file_blob_id bigint NOT NULL REFERENCES file_blob(id) ON DELETE CASCADE,
  PRIMARY KEY (revision_id, path)
);
CREATE INDEX revision_file_blob_idx ON revision_file (file_blob_id);

CREATE TABLE code_symbol (
  id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  file_blob_id     bigint NOT NULL REFERENCES file_blob(id) ON DELETE CASCADE,
  parent_symbol_id bigint REFERENCES code_symbol(id) ON DELETE CASCADE,
  kind             text   NOT NULL CHECK (kind IN ('CLASS','INTERFACE','ENUM','RECORD','ANNOTATION','METHOD','CONSTRUCTOR','FIELD')),
  name             text   NOT NULL,
  qualified_name   text   NOT NULL,
  signature        text,
  symbol_key       text   NOT NULL,
  modifiers        text[] NOT NULL DEFAULT '{}',
  annotations      text[] NOT NULL DEFAULT '{}',   -- full annotation source text, e.g. @GetMapping("/{id}")
  is_test          boolean NOT NULL DEFAULT false,
  start_line       int    NOT NULL CHECK (start_line >= 1),
  end_line         int    NOT NULL CHECK (end_line >= start_line)
);
CREATE INDEX code_symbol_blob_idx   ON code_symbol (file_blob_id);
CREATE INDEX code_symbol_parent_idx ON code_symbol (parent_symbol_id);
CREATE INDEX code_symbol_key_idx    ON code_symbol (symbol_key);
CREATE INDEX code_symbol_name_trgm  ON code_symbol USING gin (name gin_trgm_ops);
CREATE INDEX code_symbol_qname_trgm ON code_symbol USING gin (qualified_name gin_trgm_ops);

CREATE TABLE code_chunk (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  file_blob_id    bigint   NOT NULL REFERENCES file_blob(id) ON DELETE CASCADE,
  symbol_id       bigint   REFERENCES code_symbol(id) ON DELETE CASCADE,
  start_line      int      NOT NULL CHECK (start_line >= 1),
  end_line        int      NOT NULL CHECK (end_line >= start_line),
  header          text     NOT NULL,
  content         text     NOT NULL,
  search_tsv      tsvector NOT NULL,
  token_estimate  int      NOT NULL,
  chunker_version text     NOT NULL
);
CREATE INDEX code_chunk_blob_idx   ON code_chunk (file_blob_id);
CREATE INDEX code_chunk_symbol_idx ON code_chunk (symbol_id);
CREATE INDEX code_chunk_tsv_idx    ON code_chunk USING gin (search_tsv);
```
Notes:
- Chunks belong to blobs, so the chunker version must be part of reuse. When the chunker version changes, the indexer deletes and re-creates the chunks for affected blobs. It's simpler to include `chunker_version` in `index_config_hash` and re-chunk blobs whose stored chunks have an older version.
- The step-4 M1 schema test now also covers these FKs.

**Done when:** migration applied, the schema test passes, and ER matches [architecture §4.1](../architecture.md#41-tables-essential-columns).

## Step 2 — Job worker (2–3 h) · `job` module

Public API:
```java
public interface JobApi {
  /** Idempotent: returns the existing non-failed job for the same (project, type, key). */
  JobRef submit(long projectId, JobType type, String idempotencyKey, Map<String, Object> payload);
  JobView get(UserId user, JobId id);                 // resolves project → ProjectAccessPolicy (SEC-1)
}
public interface JobHandler {                          // SPI, implemented by indexing (M2), impact (M5)
  JobType type();
  void handle(JobContext ctx);                         // ctx: payload, progress(...), heartbeat(), isCancelled()
}
```
Internals:
1. **Submit:** `INSERT … ON CONFLICT DO NOTHING`, then `SELECT` the existing row (the partial unique index decides). Return 202 with the same ID on resubmit.
2. **Claim:** a single statement, with the time passed in from `Clock` (JAVA-9):
   ```sql
   UPDATE job SET status = 'RUNNING', locked_by = :worker, locked_until = :now + make_interval(secs => :leaseSec),
                  attempts = attempts + 1, updated_at = :now
   WHERE id = (
     SELECT id FROM job
     WHERE (status = 'QUEUED' AND run_after <= :now)
        OR (status = 'RUNNING' AND locked_until < :now)          -- expired lease = crashed worker
     ORDER BY run_after
     LIMIT 1
     FOR UPDATE SKIP LOCKED)
   RETURNING *;
   ```
3. **Worker loop:** a `SmartLifecycle` bean polls every `devmind.job.poll-interval` (1 s), runs handlers on a virtual-thread executor with a concurrency limit (`devmind.job.max-concurrent`, default 2), and stops gracefully.
4. **Heartbeat:** `ctx.heartbeat()` extends `locked_until`. Handlers call it between phases or every N files.
5. **Outcome:** success → `SUCCEEDED`. A retryable failure → `QUEUED` with `run_after = now + backoff(attempt)` (exponential + jitter, ERR-3) while attempts < max. Otherwise → `FAILED` with `error_code` + sanitized `error_detail` (ERR-5).
6. **Progress:** a `ctx.progress(phase, done, total)` JSON update, throttled to at most 1 write/second.
7. **Endpoint:** `GET /api/jobs/{jobId}` → `{jobId, type, status, progress, attempts, errorCode, createdAt, updatedAt}`. Add it to the authz matrix.

Tests:
- Idempotent submit.
- Two workers never claim the same job (two threads, one claim each, distinct IDs).
- An expired lease is reclaimed.
- Retry with backoff, then FAILED after max attempts.
- A non-owner gets 404 on `GET /jobs/{id}`.

**Done when:** a dummy test-only handler runs through success, retry and failure paths in integration tests.

## Step 3 — Source snapshots (3–4 h) · `ingestion` module

Public API:
```java
public interface SourceSnapshotProvider {
  /** Resolves ref → commit and streams the tracked files AT THAT COMMIT (not the working tree). */
  SourceSnapshot open(ProjectSource source, String ref);   // must be closed (temp dirs, repos)
}
public record SourceSnapshot(String commitSha, Stream<SourceFile> files) implements AutoCloseable { … }
public record SourceFile(String path, byte[] rawBytes) {}   // repo-relative, '/'-separated (JAVA-10)
```

### 3.1 Local sources (`LOCAL`)
1. Config: `devmind.ingestion.allowed-roots: [D:/dev/targets]`, validated at startup (each must exist).
2. Validation (SEC-2):
   ```java
   Path real = Path.of(sourceLocation).toRealPath();             // resolves symlinks and ..
   boolean allowed = allowedRoots.stream().anyMatch(root -> real.startsWith(root.toRealPath()));
   if (!allowed) throw new SourceRejected("LOCATION_NOT_ALLOWED");
   ```
3. Open with JGit (`Git.open(real.toFile())`), resolve `ref` (branch, tag or SHA) via `repo.resolve(ref + "^{commit}")`.
4. **Read files from the commit's tree** with `TreeWalk` (recursive) over `RevCommit.getTree()`. Skip entries whose `FileMode` is `SYMLINK` or `GITLINK` (submodules), and count them. Reading from the Git object database means:
   - the pinned SHA is exactly what's indexed, regardless of local uncommitted changes;
   - symlinks in the repo can't escape, because their content is never followed.

### 3.2 Remote sources (`GIT_PUBLIC`)
1. URL validation (SEC-3): `https` scheme only; host in `devmind.ingestion.allowed-hosts` (`github.com`, `gitlab.com`); no user-info, no port, no IP literal; path matches `^/[\w.-]+/[\w.-]+(\.git)?$`.
2. Clone into `Files.createTempDirectory("devmind-clone-")` with JGit: `setURI`, `setCloneSubmodules(false)`, `setNoCheckout(true)` (only the object DB is needed), `setTimeout(seconds)`. Use a shallow clone (`setDepth`) for indexing a branch tip. Analysis (M5) needs both commits reachable, so add a deepen/fetch-by-SHA path then.
3. Limits: clone wall-clock (`devmind.ingestion.clone-timeout`), maximum total bytes (check the directory size after clone and abort if exceeded), and a maximum file count during the walk.
4. Delete the temp dir in `close()`, even on failure. Add a startup sweep for stale `devmind-clone-*` dirs.

Tests (fixture repos are created **in the test** with JGit `Git.init()` + commits, so nothing binary is checked in):
- Resolves a branch, a tag and a SHA.
- Reads committed content, not the dirty working tree.
- Skips symlink entries.
- Rejects: a path outside the allowed root, `..` traversal, a root that is a symlink to outside, `http://`, an IP host, a non-allow-listed host, user-info in the URL.
- Remote clone is tested against a local `file://` bare repo only when a test-only flag allows the `file` scheme. **No network in tests** (TEST-5).

**Done when:** all rejection cases are tested, and a local fixture repo produces the expected file list at a given SHA.

## Step 4 — Filters, normalization, secret redaction (2–3 h) · `ingestion`

A pipeline of small, individually tested steps, applied to each `SourceFile` in this order:

| # | Step | Rule | Outcome counted as |
|---|---|---|---|
| 1 | Path deny-list | `target/`, `build/`, `out/`, `node_modules/`, `dist/`, `.git/`, `.idea/`, `.gradle/`, `generated/`, `generated-sources/` (any depth) | `excluded.path` |
| 2 | Secret-file patterns | `.env*`, `*.pem`, `*.key`, `*.p12`, `*.jks`, `*.keystore`, `id_rsa*`, `id_ed25519*`, `credentials*`, `*.tfstate` | `excluded.secretFile` |
| 3 | Size cap | `> devmind.ingestion.max-file-bytes` (default 512 KB) | `excluded.size` |
| 4 | Binary sniff | NUL byte in the first 8 KB | `excluded.binary` |
| 5 | Extension allow-list | `.java .kt .xml .yml .yaml .properties .sql .md .gradle .kts .json .ts .html .scss` (only `.java` is parsed; the rest are chunked as text) | `excluded.extension` |
| 6 | Decode | strict UTF-8 (`CodingErrorAction.REPORT`); on failure, fall back to ISO-8859-1 and flag `encodingFallback` | `encodingFallback` |
| 7 | Normalize | strip a UTF-8 BOM; CRLF/CR → LF | — |
| 8 | Secret redaction | regex detectors (private-key blocks, AWS access key IDs, GitHub tokens, generic `(?i)(password|secret|token|api[_-]?key)\s*[:=]\s*['"][^'"]{8,}['"]`), plus an optional high-entropy check on quoted strings. Replace the match with `«REDACTED»` **within the line** so line numbers are unchanged. | `redactions` |
| 9 | Hash | SHA-256 of the normalized, redacted content → `content_hash` | — |

Output: `NormalizedFile(path, language, content, contentHash, lineCount, redactionCount)` plus `IngestionStats`.

Tests:
- One test per row.
- A CRLF file and an LF file with the same text produce the **same hash**.
- Redaction keeps the line count.
- A private-key block spanning lines keeps its line structure. Redact each line of the block.

**Done when:** each filter has a positive and a negative test, and the stats add up: kept + excluded = total.

## Step 5 — Java parser and symbol extraction (3–4 h) · `indexing`

Port (`com.devmind.indexing`, public SPI):
```java
public interface CodeParser {
  boolean supports(String language);
  ParsedFile parse(String path, String content);       // never throws on bad input; returns ParseStatus.PARSE_FAILED
  String version();                                    // e.g. "java-1"; part of blob identity
}
public record ParsedSymbol(String kind, String name, String qualifiedName, @Nullable String signature,
    String symbolKey, List<String> modifiers, List<String> annotations, boolean isTest,
    int startLine, int endLine, @Nullable Integer parentIndex) {}
```
`JavaParserCodeParser` (`indexing.internal.parsing`):
1. Configure: `new JavaParser(new ParserConfiguration().setLanguageLevel(<highest supported>).setAttributeComments(true))`. Use one instance per thread, because the parser isn't thread-safe.
2. Walk `CompilationUnit` with a visitor:
   - package name;
   - type declarations, including nested ones (`ClassOrInterfaceDeclaration`, `EnumDeclaration`, `RecordDeclaration`, `AnnotationDeclaration`);
   - members (`MethodDeclaration`, `ConstructorDeclaration`, `FieldDeclaration` — one symbol per variable).
3. **Ranges:** `node.getRange()` → `begin.line`/`end.line`, 1-based. Include Javadoc and annotations: if `node.getComment()` is a Javadoc comment, start at the comment's begin line, and also take the minimum with the annotations' begin lines.
4. **Qualified names:** `pkg.Outer.Inner`, and members `pkg.Outer#method`.
5. **`symbol_key`** ([ADR-0003](../adr/0003-content-addressed-storage.md)): `qualifiedName + "(" + erased param simple types joined by "," + ")"` for methods and constructors (`com.x.OwnerService#findById(int)`), and the qualified name for types and fields. Use the **source-level** types as written, without generics (`List<Owner>` → `List`), because there's no resolution at this stage.
6. **Signature:** `method.getDeclarationAsString(true, false, true)`, or the type header for types.
7. **Annotations:** the full source text, e.g. `@GetMapping("/owners/{ownerId}")`. M5 and M8 rely on it.
8. **`is_test`:** the path contains `/src/test/`, **or** the method or class has an annotation whose simple name is in `{Test, ParameterizedTest, RepeatedTest, TestFactory, SpringBootTest, WebMvcTest, DataJpaTest}`.
9. **Failure:** parse problems → `PARSE_FAILED` with a list of problem messages (no code). The orchestrator still chunks the file as plain text (ERR-4).

**Golden tests (TEST-3).** Put 6–10 fixture files under `src/test/resources/golden/java/` covering:
- a plain class with fields, constructor and methods;
- nested and inner classes;
- an enum with a body and a record with a compact constructor;
- an interface with default methods;
- an annotated Spring controller and a JUnit test class;
- generics and overloads (distinct `symbol_key`s);
- a file with a syntax error.

Each has an expected `*.symbols.json`. A test helper re-writes the expectations when you pass `-DupdateGolden=true`. **Review the diff of every golden update.**

**Done when:** golden tests pass, and the overload and nested cases have distinct, correct keys and ranges.

## Step 6 — Identifier splitting and chunking (2 h) · `indexing`

### 6.1 `IdentifierSplitter` (pure function, heavily unit-tested)
- `findOwnerById` → `find owner by id`
- `HTTPServerError` → `http server error`
- `OWNER_ID` → `owner id`
- `parseXML2Json` → `parse xml 2 json`
- `owner-details` → `owner details`

`searchText = header + "\n" + content + "\n" + split(identifiers found in content)`, stored with `to_tsvector('simple', :searchText)` at insert. Query time (M3) uses the same splitter.

### 6.2 `SymbolChunker` (rules from [architecture §7.1](../architecture.md#71-chunking))
| Input | Chunks |
|---|---|
| Method/constructor ≤ 120 lines | 1 chunk = the member range |
| Method/constructor > 120 lines | 80-line windows with 10-line overlap, within the member range |
| Type | 1 "type header" chunk: declaration line(s) + fields + annotations, **excluding** member bodies |
| Type < 60 lines total | additionally 1 whole-type chunk |
| `PARSE_FAILED` Java or non-Java text | 60-line windows, 10-line overlap |

- **Header format:** `path · package · class signature · member signature` (for text files, `path`).
- **`token_estimate`** = `ceil(chars / 4)`.
- **`chunker_version`** = `"chunk-1"`. Bump it whenever the rules change.

Tests:
- Exact chunk boundaries for the golden fixtures, as a golden snapshot.
- Window overlap is correct at the file end.
- Every chunk's lines are within file bounds.
- The header contains the member signature.

**Done when:** golden chunk snapshots are committed and reviewed.

## Step 7 — Indexing orchestration (3 h) · `indexing`

`IndexRevisionJobHandler implements JobHandler` (type `INDEX`). The payload is `{projectId, ref}`.

1. `snapshot = provider.open(source, ref)` → `commitSha`.
2. `configHash = sha256(parserVersion | chunkerVersion | filterRulesVersion | embeddingModelId-or-"none")`. The embedding model joins in M3.
3. Upsert `repository_revision (project, sha, configHash)`. If it's already `READY` → finish (**idempotent no-op**). If it's `INDEXING` or `FAILED` → continue, because every write below is an upsert.
4. Stream the files through the ingestion filters. In batches of 500 (DB-8):
   - look up existing blobs: `SELECT content_hash, id FROM file_blob WHERE project_id=? AND parser_version=? AND content_hash = ANY(?)`;
   - for new hashes, parse → insert the blob, symbols and chunks with `JdbcClient` batch updates, in one transaction per batch;
   - insert the `revision_file` rows for all files in the batch.
5. Call `ctx.heartbeat()` and `ctx.progress("files", n, total)` per batch.
6. **Validation phase:**
   - count of `revision_file` = kept files;
   - every blob has `parse_status`;
   - every Java blob with `OK` has ≥ 1 symbol.

   Then set status `READY`, `ready_at`, and `stats` = `{filesKept, excluded{…}, blobsReused, blobsNew, symbols, chunks, parseFailures, redactions, durationMs}`.
7. Publish `RevisionReadyEvent(projectId, revisionId, commitSha)` (ARCH-6). M3 listens for it.
8. **Transactions:** no transaction spans the whole job (SPRING-2). Each batch commits on its own. A crash mid-way leaves the revision `INDEXING`, and a retry resumes thanks to the upserts.

API: `POST /api/projects/{projectId}/index-jobs {ref}` → `ProjectAccessPolicy` → `JobApi.submit(project, INDEX, key = ref-resolved-sha + configHash)` → `202` + `Location` (API-4).
- Resolving the SHA requires opening the source, so for the idempotency key, resolve the ref synchronously first. It's cheap for local repos. For remote ones, use `ref` as the key and dedupe on the revision row instead.

Tests (Testcontainers, a fixture repo built with JGit):
- Index commit A → READY with the expected counts.
- Index A again → no new rows (assert table counts unchanged).
- Commit B changes 1 of 5 files → `blobsNew = 1`, `blobsReused = 4`.
- A file with a syntax error → revision READY, `parseFailures = 1`, and text chunks exist for it.
- Kill mid-way (a test hook throws after batch 1) → the retry completes with correct counts.

**Done when:** all five scenarios pass.

## Step 8 — Read APIs (2 h) · `indexing.internal.web`

Every query is revision-scoped (DB-7) and every endpoint goes through `ProjectAccessPolicy` (SEC-1). Add each one to the authz matrix.

| Endpoint | Behaviour |
|---|---|
| `GET /api/projects/{projectId}/revisions` | Lists revisions with status and stats, newest first, paginated |
| `GET /api/projects/{projectId}/revisions/{sha}/files?path=src/main` | Direct children of `path` (dirs + files), computed from `revision_file.path` prefix queries. Dirs first, sorted. |
| `GET /api/projects/{projectId}/revisions/{sha}/file?path=&from=&to=` | Source slice. `from/to` default to the whole file; cap 2,000 lines per call. Returns `{path, sha, language, lineCount, from, to, content}`. 404 if the path isn't in the revision. |
| `GET /api/projects/{projectId}/revisions/{sha}/symbols?q=&kind=&page=&size=` | Exact name/qualified-name match first, then trigram `similarity(name, :q) >= 0.4`, ordered by exact desc, then similarity desc. Each result has `{symbolId, kind, name, qualifiedName, signature, path, startLine, endLine}`. |
| `GET /api/projects/{projectId}/revisions/{sha}/symbols/{symbolId}` | Symbol detail plus its children |

- `{sha}` accepts a full SHA, or a unique ≥ 7-char prefix within the project's READY revisions. Ambiguous → 409.
- Only **READY** revisions are readable, otherwise 409 `REVISION_NOT_READY`.

SQL sketch for the symbol search, as a text block in `SymbolQueries`:
```sql
SELECT s.id, s.kind, s.name, s.qualified_name, s.signature, rf.path, s.start_line, s.end_line,
       (s.name = :q OR s.qualified_name = :q) AS exact, similarity(s.name, :q) AS sim
FROM revision_file rf
JOIN code_symbol s ON s.file_blob_id = rf.file_blob_id
WHERE rf.revision_id = :revisionId
  AND (s.name = :q OR s.qualified_name = :q OR s.name % :q)
  AND (:kind::text IS NULL OR s.kind = :kind)
ORDER BY exact DESC, sim DESC, s.qualified_name
LIMIT :limit OFFSET :offset;
```
Check `EXPLAIN` once on PetClinic-sized data and note it in the PR (DB-6). Then regenerate OpenAPI and the frontend types (API-10).

**Done when:** integration tests cover every endpoint, including 404/409 paths and a non-owner 404.

## Step 9 — UI: explorer (3–4 h) · `features/explorer`

1. **Project detail page** (`/projects/:projectId`): source info, a revisions table (status, short SHA, stats), and an "Index" button that opens a dialog for the `ref`.
2. **Job polling:** `JobsApi.poll(jobId)` polls with RxJS `timer(0, 1500)` + `switchMap` + `takeWhile(non-terminal, inclusive)`, exposed as a signal through `toSignal`. Show a progress bar (`phase`, `done/total`), and a failure message from `errorCode`.
3. **Explorer page** (`/projects/:projectId/revisions/:sha`), in a split layout:
   - left: PrimeNG `Tree` with lazy loading of children on expand (`/files?path=`);
   - right: `CodeViewerComponent`.
4. **`CodeViewerComponent`** (shared, presentational):
   - CodeMirror 6, read-only (`EditorState.readOnly.of(true)`, `EditorView.editable.of(false)`), with Java language support, line numbers, and a highlight decoration for `[highlightFrom, highlightTo]`.
   - It scrolls to the highlight.
   - Inputs: `content`, `language`, `firstLine`, `highlight`.
   - Content goes in as editor text, never as HTML (SEC-9, FE-12).
5. **Deep links:** route query params `?path=…&from=…&to=…` open the file at those lines. M4 citation chips use this.
6. **Symbol search:** a debounced search box (300 ms) → result list (kind tag, qualified name, path:lines) → a click opens the deep link.
7. **States:** loading, empty ("no files"), errors, and revision-not-ready (FE-10).
8. **Tests:** job polling stops on terminal states; the deep-link params map to the viewer's inputs; search debounce.

**Done when:** the full flow works in the browser: index → watch progress → browse the tree → open a file → search a symbol → land on its lines.

## Step 10 — Index PetClinic end-to-end, then close out (60 min)

1. Create a project with `LOCAL` source `D:/dev/targets/spring-petclinic-rest`. In compose, mount `D:/dev/targets` read-only into the backend container (e.g. `/targets`) and configure `allowed-roots: [/targets]`.
2. Index the pinned SHA from `evaluation/targets.yaml`. Record the stats in the devlog: files, symbols, chunks, parse failures, redactions, duration.
3. Re-index the same SHA and confirm it's a no-op (same revision, no new rows).
4. **First milestone check:** search `OwnerRestController` (or the actual controller name) → open it → the line range matches the IDE.
5. Spot-check 5 random symbols against the IDE for exact line ranges.
6. **Lombok / generated-code check:** if PetClinic's controllers implement interfaces generated from an OpenAPI spec, those interfaces live in generated sources, which are excluded by design. Record this in the devlog as a known limitation. It matters again in M5 (unresolved `IMPLEMENTS`) and M8 (mappings may be found via the OpenAPI spec instead).

### Exit checklist (from PLAN.md M2)
- [ ] PetClinic indexed at the pinned SHA; the class-name lookup returns the correct file, qualified name, lines and source
- [ ] Re-indexing the same SHA is a no-op
- [ ] Indexing a second SHA re-parses only changed blobs (shown in stats)
- [ ] Path traversal, symlink escape, URL and secret tests pass
- [ ] Parser and chunker golden tests committed
- [ ] UI: status polling, tree, code viewer with line highlight, symbol search
- [ ] Authz matrix updated for every new endpoint

### Wrap-up
Devlog entry (index stats, surprises), "Current milestone" → M3 in `CLAUDE.md`, then tag `m2`.

## Pitfalls
- **JavaParser language level:** set it to the highest level your JavaParser version supports. Very new syntax in a target repo fails parsing, and those files become `PARSE_FAILED` text chunks.
- **Comment ranges:** orphan comments and Javadoc placement shift ranges. Golden tests catch this.
- **`text[]` with JdbcClient:** bind arrays via `connection.createArrayOf("text", …)` or a small `SqlParameterValue` helper. Test it.
- **Windows paths in tests:** always build repo paths as `/`-joined strings from Git tree walks, never from `File.separator`.
- **Docker volume performance:** mounting `D:\` into containers is slow but fine for read-only indexing of a small repo.
