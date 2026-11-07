# M3 — Retrieval + Evaluation Harness: Step-by-step Plan

| | |
|---|---|
| Milestone | M3 (see [PLAN.md §5](../PLAN.md#5-milestones)) |
| Budget | 15–20 hours |
| Prerequisite | `m2` tagged; embedding model + dimension locked (ADR-0006) |
| Goal | Hybrid retrieval (lexical + vector + symbol, RRF), plus a reproducible evaluation runner. Experiment A is run and committed **before any LLM is called**. |
| Key decisions applied | [ADR-0006](../adr/0006-ai-provider-port.md) · [ADR-0007](../adr/0007-hybrid-retrieval-rrf.md) · [ADR-0011](../adr/0011-evaluation-as-code.md) |
| Not in M3 | Chat models, answers, citations (M4). Graph expansion stays a no-op until edges exist (M5). |

---

## Progress tracker

| # | Step | PR | Time | Done |
|---|---|---|---|---|
| 1 | `ai` module: `EmbeddingPort` + Ollama adapter + fake | `feat/ai-embedding-port` | 2 h | ☐ |
| 2 | Embedding schema + embedding phase in indexing | `feat/indexing-embeddings` | 2–3 h | ☐ |
| 3 | Retrievers: lexical, vector, symbol | `feat/retrieval-retrievers` | 3 h | ☐ |
| 4 | Fusion, packing, `RetrievalApi`, debug endpoint | `feat/retrieval-fusion` | 2 h | ☐ |
| 5 | Evaluation module: dataset, matcher, metrics, reports | `feat/evaluation-runner` | 3–4 h | ☐ |
| 6 | Experiment A: vector vs lexical vs hybrid | `eval/experiment-a` | 1–2 h | ☐ |
| 7 | Failure analysis + dataset growth to 35–40 | `eval/dataset-v2` | 2 h | ☐ |
| 8 | Close out M3 | — | 30 min | ☐ |

---

## Step 1 — `ai` module: embedding port (2 h)

Public API (`com.devmind.ai`):
```java
public interface EmbeddingPort {
  EmbeddingModelRef model();                                  // provider, name, dimensions, digest
  List<float[]> embedDocuments(List<String> texts);           // applies the model's document prefix
  float[] embedQuery(String text);                            // applies the model's query prefix
}
public record EmbeddingModelRef(String provider, String name, int dimensions, String digest) {}
```
Internals (`ai.internal`):
1. `AiProperties` (`devmind.ai.embedding.{provider, base-url, model, dimensions, document-prefix, query-prefix, batch-size, timeout}`), validated (SPRING-1). The values come from the M0 decision.
2. `OllamaEmbeddingAdapter`: use Spring AI's Ollama embedding client, **or** a plain `RestClient` call to `/api/embed` if Spring AI's surface gets in the way. Both stay inside `ai.internal` (ARCH-8). Explicit connect and read timeouts (SPRING-6). Batches of `batch-size`.
3. **Dimension guard:** at startup, embed `"dimension check"` once. If the length ≠ configured dimensions, **fail fast** with a clear message.
4. **Digest:** read it from Ollama (`/api/show` or the tags listing) at startup, for `EmbeddingModelRef`.
5. `FakeEmbeddingAdapter` (test sources): deterministic vectors from a hash of the text's tokens. Similar texts get similar vectors (e.g. bag-of-split-identifiers hashed into dimensions, then normalized), so retrieval tests are meaningful without a model (AI-11).
6. Metrics: `devmind.embedding.latency`, `devmind.embedding.texts`.

Tests:
- The adapter against a WireMock or `MockRestServiceServer` stub (request shape, batching, timeout → exception type).
- Dimension-guard failure.
- The fake is deterministic.

**Done when:** the app starts against local Ollama with the guard passing, and every test uses the fake.

## Step 2 — Embedding schema + indexing phase (2–3 h)

`V3__embeddings.sql` (replace `768` with the M0 dimension):
```sql
CREATE TABLE embedding_model (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  provider    text NOT NULL,
  model_name  text NOT NULL,
  dimensions  int  NOT NULL CHECK (dimensions > 0),
  model_digest text NOT NULL,
  created_at  timestamptz NOT NULL,
  CONSTRAINT embedding_model_uk UNIQUE (provider, model_name, model_digest)
);

CREATE TABLE chunk_embedding (
  chunk_id           bigint NOT NULL REFERENCES code_chunk(id) ON DELETE CASCADE,
  embedding_model_id bigint NOT NULL REFERENCES embedding_model(id) ON DELETE CASCADE,
  embedding          vector(768) NOT NULL,
  PRIMARY KEY (chunk_id, embedding_model_id)
);
CREATE INDEX chunk_embedding_model_idx ON chunk_embedding (embedding_model_id);
CREATE INDEX chunk_embedding_hnsw ON chunk_embedding USING hnsw (embedding vector_cosine_ops);
```
Indexing changes:
1. On startup, upsert the active `embedding_model` row and expose its ID via `IndexingConfig`.
2. `index_config_hash` now includes the embedding model ID. Re-indexing an existing SHA creates a new revision row that reuses all blobs, symbols and chunks, and computes **only missing embeddings**.
3. **Embedding phase** (after the parse batches, before validation):
   - select the chunks of this revision's blobs that have no `chunk_embedding` for the active model;
   - embed `header + "\n" + content` in batches;
   - insert via batch update. Bind vectors with `com.pgvector.PGvector` (`new PGvector(float[])`).
   - Call `ctx.progress("embedding", done, total)` and heartbeat per batch.
4. Validation now also checks that every chunk of the revision has an embedding for the active model.
5. Stats: `embeddingsNew`, `embeddingsReused`, `embeddingMs`.
6. **Transactions:** embedding calls happen **outside** DB transactions (SPRING-2). Embed a batch, then open a short transaction to insert it.

Tests (fake embedding):
- Embeddings are created for all chunks.
- Re-indexing the same content reuses all embeddings.
- A model change (a second fake model with a different digest) creates a new revision with new embeddings while the old ones remain.

**Done when:** PetClinic re-indexed with real embeddings and the stats recorded in the devlog (embedding time vs. the M0 projection).

## Step 3 — Retrievers (3 h) · `retrieval` module

Common contract (`retrieval.internal`):
```java
interface Retriever {
  RetrieverName name();                                       // LEXICAL, VECTOR, SYMBOL
  List<RankedChunk> retrieve(RetrievalQuery query, int topN); // ranks are 1-based
}
record RetrievalQuery(long revisionId, String text, List<String> identifierTokens, long embeddingModelId) {}
record RankedChunk(long chunkId, int rank, double rawScore) {}
```
Query preprocessing (`QueryAnalyzer`, pure and unit-tested):
- extract identifier-like tokens (`[A-Za-z_][A-Za-z0-9_]*` containing an upper-case letter after the first char, or `_`, or `#`);
- build the lexical query text: the original text plus the split identifiers, using the same `IdentifierSplitter` as indexing.

### 3.1 Lexical (`LexicalRetriever`)
```sql
SELECT c.id, ts_rank_cd(c.search_tsv, q) AS score
FROM revision_file rf
JOIN code_chunk c ON c.file_blob_id = rf.file_blob_id,
     websearch_to_tsquery('simple', :queryText) q
WHERE rf.revision_id = :revisionId AND c.search_tsv @@ q
ORDER BY score DESC, c.id
LIMIT :topN;
```
`websearch_to_tsquery` ANDs terms, which can be too strict for long natural-language questions. Use an **OR-query** fallback: if the AND query returns fewer than `topN / 3` rows, re-run with terms joined by `|` via `to_tsquery('simple', …)`, after sanitizing the terms to `[a-z0-9]+`. Keep both modes configurable and record which one fired.

### 3.2 Vector (`VectorRetriever`)
```sql
SET LOCAL hnsw.ef_search = :efSearch;            -- default 100; inside the query's transaction
-- if supported by your pgvector version: SET LOCAL hnsw.iterative_scan = relaxed_order;
SELECT c.id, 1 - (e.embedding <=> :q) AS score
FROM chunk_embedding e
JOIN code_chunk c     ON c.id = e.chunk_id
JOIN revision_file rf ON rf.file_blob_id = c.file_blob_id AND rf.revision_id = :revisionId
WHERE e.embedding_model_id = :modelId
ORDER BY e.embedding <=> :q
LIMIT :topN;
```
- Run `EXPLAIN ANALYZE` on PetClinic (DB-6). If the HNSW index isn't used because of the join filter, results are still correct, just an exact scan, which is fine at this scale. Note the plan in the PR, and the `ef_search`/iterative-scan decision.
- **Correctness check:** for 5 queries, compare the HNSW results with exact search (`SET LOCAL enable_indexscan = off`). They should overlap ≥ 90% at top 10.

### 3.3 Symbol (`SymbolRetriever`)
For each identifier token (and the whole query if it's short):
1. exact `name` / `qualified_name` / `symbol_key` prefix match;
2. otherwise trigram `similarity ≥ 0.4`.

Map matched symbols → their chunks (the member chunk, or the type-header chunk for types). Rank by: exact first, then similarity, then symbol kind (type > method > field).

Tests (Testcontainers + fake embedding, small fixture repo):
- Lexical finds `findOwnerById` from the query "find owner by id".
- The OR-fallback triggers on long questions.
- Vector returns chunks only from the requested revision (index two revisions with different content and assert no leakage).
- Symbol exact beats fuzzy.

**Done when:** each retriever has revision-isolation and ranking tests.

## Step 4 — Fusion, packing, API (2 h)

`RetrievalConfig` (`devmind.retrieval.*`, recorded in every report, AI-10):
```yaml
devmind.retrieval:
  retrievers: [LEXICAL, VECTOR, SYMBOL]   # Experiment A toggles this
  top-n-per-retriever: 30
  rrf-k: 60
  final-k: 12
  graph-expansion: { enabled: false, depth: 1, seeds: 5 }   # turned on in M5
  context-budget-tokens: 6000
  vector: { ef-search: 100 }
  lexical: { or-fallback: true }
```
1. **Concurrency:** run the enabled retrievers in parallel on virtual threads with a per-retriever timeout (ERR-6). A failed retriever doesn't fail retrieval: record it in `diagnostics` and continue.
2. **`RrfFusion`** (pure function): `score(d) = Σ 1/(k + rank_r(d))`. Keep the per-retriever ranks on each fused item. Tie-break by best single rank, then chunk ID (determinism).
3. **`EvidencePacker`** (pure function):
   - load chunk details (path via `revision_file`, symbol, lines, header, content);
   - merge overlapping chunks from the same file (union of line ranges, re-sliced from the blob content);
   - walk in fused order until `context-budget-tokens` or `final-k` is reached;
   - assign evidence IDs `E1..En` in packed order.
4. **Public API:**
   ```java
   public interface RetrievalApi {
     RetrievalResult retrieve(long projectInternalId, String commitSha, String question, RetrievalConfig config);
   }
   public record Evidence(String evidenceId, long chunkId, String path, @Nullable String symbol,
       int startLine, int endLine, String header, String content, double fusedScore,
       Map<String, Integer> ranks, @Nullable Double bestVectorSimilarity, boolean exactSymbolHit) {}
   public record RetrievalResult(String commitSha, List<Evidence> evidence, RetrievalDiagnostics diagnostics) {}
   ```
   `bestVectorSimilarity` and `exactSymbolHit` feed the M4 abstention gate.
5. **Debug endpoint:** `POST /api/projects/{projectId}/retrieve {question, revision, retrievers?}` → ranked evidence with per-retriever ranks and timings. Content is truncated to 40 lines per item. It goes through the authz matrix and is rate-limited later (M6).
6. **Unit tests:** RRF math on hand-built inputs; deterministic tie-breaks; the packer respects the budget and merges overlaps; IDs are contiguous.

**Done when:** the debug endpoint returns sensible results for 5 PetClinic questions. Do a quick manual check and save the output in the devlog.

## Step 5 — Evaluation module (3–4 h) · `evaluation` (profile `eval` only)

1. **Dataset loading:**
   - Jackson YAML → records matching `evaluation/datasets/qa-petclinic-rest.yaml` (the M0 format, including `split`).
   - Validate on load: unique IDs, known categories, `mustAbstain ⇒ expected = []`, and every expected path exists in the indexed revision (fail with a list of bad IDs).
   - `datasetHash` = SHA-256 of the file bytes.
2. **Runner** (`EvaluationRunner`, an `ApplicationRunner` active only with `@Profile("eval")`). Arguments:
   ```
   --eval.dataset=evaluation/datasets/qa-petclinic-rest.yaml
   --eval.mode=retrieval            # retrieval | qa (M4) | impact (M5)
   --eval.config-name=hybrid        # label for the report
   --eval.split=dev                 # dev | holdout | all
   --devmind.retrieval.retrievers=LEXICAL,VECTOR,SYMBOL
   ```
   Run command (document it in `CLAUDE.md` Commands):
   ```powershell
   ./mvnw -q spring-boot:run "-Dspring-boot.run.profiles=local,eval" "-Dspring-boot.run.arguments=--eval.mode=retrieval --eval.config-name=hybrid --eval.split=dev"
   ```
   The runner uses an already-indexed READY revision for the dataset's SHA. If it's missing, it indexes it first via `IndexingApi`. It exits when done (`SpringApplication.exit`).
3. **Matching (`EvidenceMatcher`, pure, unit-tested):** an expected item matches a retrieved item when the paths are equal **and** either the expected `symbol` equals the retrieved symbol's qualified name / symbol key, or the line ranges overlap by ≥ 1 line. If the expected item has neither symbol nor lines, a path match is enough.
4. **Metrics** (non-abstention questions only):
   - **Recall@k** (k = 5, 10) = questions with ≥ 1 expected item matched in the top k ÷ questions.
   - **Full-recall@10** = questions with all expected items in the top 10 ÷ questions (useful for cross-file traces).
   - **MRR** = mean of 1/rank of the first match (0 if none).
   - All of the above **per category** and **overall**, with counts (`7/10`), not just percentages.
   - Latency p50/p95 of retrieval.
5. **Reports** in `evaluation/reports/<UTC yyyyMMdd-HHmmss>-<config-name>.json` and `.md`:
   - **Header:** DevMind git SHA (`git rev-parse HEAD`, read at runtime or injected via build-info), dataset path + hash, split, target SHA, `index_config_hash`, embedding model + digest, full `RetrievalConfig`.
   - **Per question:** expected items, top-10 retrieved items with per-retriever ranks, hit rank, pass/fail.
   - **Summary table** in the Markdown.
6. Insert an `evaluation_run` row (`V4__evaluation_run.sql`: `id, dataset, dataset_hash, mode, config_name, config jsonb, metrics jsonb, report_path, created_at`). It's used by the UI's "latest results" in M6.
7. **Tests:** matcher cases; metric math on a synthetic run; the report header has all reproducibility fields; the runner bean is **absent** outside the `eval` profile (TEST-11).

**Done when:** one run produces both report files with a complete header.

## Step 6 — Experiment A (1–2 h)

Run **the dev split** for four configurations, identical except for `retrievers`:

| Config name | Retrievers |
|---|---|
| `lexical-only` | LEXICAL |
| `vector-only` | VECTOR |
| `symbol-only` | SYMBOL |
| `hybrid` | LEXICAL, VECTOR, SYMBOL |

Then:
1. Write `evaluation/reports/experiment-a.md`:
   - a comparison table (Recall@5, Recall@10, MRR overall and per category, with counts);
   - which questions each config uniquely won or lost;
   - a 3–5 sentence conclusion that **states the actual result**, even if hybrid isn't best everywhere.
2. Run `hybrid` once on the **holdout** split, and report it separately. Don't tune anything after seeing it.
3. Commit the reports (DOC-2). These are the first README-worthy numbers.

**Done when:** `experiment-a.md` and the 4 + 1 reports are committed.

## Step 7 — Failure analysis + dataset growth (2 h)

1. For every miss in `hybrid` (dev), classify the cause in `evaluation/reports/experiment-a.md` under "Failure analysis":
   - expected evidence not in top 30 of any retriever (a recall problem);
   - in the top 30 but lost in fusion (a ranking problem);
   - chunking split the relevant code badly;
   - a label problem: the dataset is wrong. Fix the label, and note the fix.
2. Make at most **two** targeted improvements (e.g. header content, OR-fallback threshold, symbol kind weighting). Each needs a before/after report **on dev only** (AI-10).
3. Grow the dataset to **35–40** questions by adding ~15–20 following the M0 labelling procedure (category quota toward the final 50 in [architecture §13](../architecture.md#13-evaluation-architecture)). Keep ~20% holdout. Bump `datasetVersion`.
4. Re-run `hybrid` on the new dataset version, and commit it as the new baseline.

**Done when:** the failure analysis is written, improvements are justified by reports, and the dataset is at v2 with 35–40 questions.

## Step 8 — Close out M3 (30 min)

### Exit checklist (from PLAN.md M3)
- [ ] Embeddings persisted with model + digest; re-indexing reuses embeddings
- [ ] Lexical, vector and symbol retrievers with revision-isolation tests
- [ ] RRF fusion + packer; every parameter in `RetrievalConfig`
- [ ] Evaluation runner (profile `eval`) writes JSON + MD reports with full reproducibility headers
- [ ] **Experiment A** committed: 4 configs on dev + hybrid on holdout, with failure analysis
- [ ] Dataset v2: 35–40 questions
- [ ] No LLM call anywhere in the codebase yet

### Wrap-up
Devlog entry with the headline numbers, "Current milestone" → M4, tag `m3` (with the tag message linking `experiment-a.md`).

## Pitfalls
- **Tuning on the test set:** only the dev split informs changes. The holdout is reported, never optimized against.
- **Vector filter starvation:** if `vector-only` recall is suspiciously low, check whether the HNSW post-filter is dropping in-revision chunks. Compare against exact search (step 3.2).
- **Prefixes:** forgetting the model's query/document prefixes quietly hurts vector recall. Assert in a test that the adapter applies them.
- **Non-determinism:** ties and concurrent retrieval order must not change results. Run `hybrid` twice and diff the reports. Only timestamps and latency should differ.
