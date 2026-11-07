# ADR-0007: Hybrid retrieval (identifier-aware FTS + vector + symbol), fused with RRF
- Status: Accepted
- Date: 2026-11-06

## Context
Code questions mix exact identifiers ("where is `validateOwner`"), natural language ("how are visits validated"), and structure ("who calls X"). No single retriever handles all three.

## Options considered
| Option | For | Against |
|---|---|---|
| Vector only | Simple | Poor on identifiers and rare tokens; embedding models tokenize camelCase badly |
| Lexical only | Exact matches | Misses paraphrase |
| Weighted score sum | Tunable | Scores from FTS, cosine and trigram aren't on comparable scales; weights overfit a small benchmark |
| **RRF over ranks** | Scale-free, robust, one parameter (k) | Ignores score magnitude |
| Cross-encoder reranker | Often best quality | Extra model, latency and complexity; on CPU it's slow |

## Decision
Three retrievers, each returning the top 30 for the requested revision:
1. **Lexical:** `search_tsv` built with the `simple` config over header + content + **split identifiers** (camelCase/snake_case expansion done in Java). Ranked by `ts_rank_cd`.
2. **Vector:** HNSW cosine over `chunk_embedding` for the revision's active embedding model.
3. **Symbol:** exact `name`/`qualified_name` match first, then `pg_trgm` similarity ≥ 0.4, mapped to the symbol's chunks.

Fused with **RRF (k = 60)**, then graph expansion (depth 1) on the top 5 seeds, then budget packing ([architecture §7](../architecture.md#7-retrieval-details)). All parameters live in `RetrievalConfig` and are recorded in eval reports. Rerankers are deferred until evaluation shows a gap.

## Consequences
- \+ Experiment A (vector vs lexical vs hybrid) runs without any LLM, early (M3).
- \+ Each evidence item carries its per-retriever ranks, which the debug endpoint and eval reports show. That makes failures explainable.
- − Three queries per question. They're fine on this scale and can run concurrently on virtual threads.

## Revisit when
Recall@5 on the hybrid config is under ~0.8 and failure analysis shows ranking (not recall@30) is the issue. Then try a reranker.
