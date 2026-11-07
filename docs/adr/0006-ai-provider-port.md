# ADR-0006: Own AI ports; Spring AI inside adapters; local embeddings, configurable chat
- Status: Accepted. Embedding model locked and chat set to local-only in M0 (2026-11-07, see "M0 measurements" below).
- Date: 2026-11-06 (amended 2026-11-07)

## Context
- Dev machine: Intel i7-1185G7 (4 cores), 32 GB RAM, **integrated graphics only**. CPU inference for a 7–8B model is roughly single-digit tokens/second, and prompt processing on a 6k-token context takes tens of seconds or more. Measure this in M0 rather than assume it.
- Benchmark needs: ~50 QA questions × 3+ configurations, plus impact cases. These must be repeatable and cheap.
- Spring AI is the natural Spring choice, but its API has changed between releases.

## Options considered
**Integration library**
| Option | For | Against |
|---|---|---|
| A. Spring AI used directly in services | Least code | Provider and version churn leaks everywhere; hard to fake in tests |
| B. LangChain4j | Mature, many providers | Less "Spring-native" story; same leakage risk |
| C. Raw HTTP clients | Full control, zero churn | Re-implementing structured output, retries and tool calling |
| D. **Own ports (`ChatModelPort`, `EmbeddingPort`) + Spring AI as the adapter implementation** | Stable internal contract, deterministic fakes for tests, library swappable | A thin mapping layer |

**Where models run**
| Option | For | Against |
|---|---|---|
| Local everything (Ollama) | $0, offline, private | Chat too slow on this hardware for benchmarks and a live demo |
| Hosted everything | Fast, strong models | Per-token cost; embedding calls on every re-index |
| **Local embeddings + configurable chat** (Ollama for dev, hosted OpenAI-compatible endpoint for eval/demo) | Embeddings are cheap on CPU and run once per blob ([ADR-0003](0003-content-addressed-storage.md)); chat quality and speed where it matters | Two providers to configure |

## Decision
- Ports live in `com.devmind.ai`: `EmbeddingPort.embed(List<String>) → List<float[]>`, `ChatModelPort.generate(StructuredRequest<T>) → StructuredResult<T>` (with usage, latency, model ID).
- Adapters in `ai.internal`: `OllamaEmbeddingAdapter`, `OllamaChatAdapter`, `OpenAiCompatibleChatAdapter` (base URL + key from env). The last one covers many hosted providers and Ollama's own OpenAI-compatible API.
- **Embedding model: `mxbai-embed-large` via Ollama, 1024 dimensions**, digest `468836162de7f81e041c43663fedbbba921dcea9b9fefea135685a39b2d83dd8`. Queries use the prefix `Represent this sentence for searching relevant passages: `; documents have no prefix. The model name **and digest** are recorded in `embedding_model`. (Candidates were `nomic-embed-text`, 768-d, and `mxbai-embed-large`; no code-specific embedding model was evaluated.)
- **Chat model:** config-only (`devmind.ai.chat.provider/model`). Benchmarks run at temperature 0 with a pinned model version string, and the report records them. **v1 runs chat locally on Ollama** (`qwen2.5-coder:7b`, digest `dae161e27b0e90dd1856c8bb3209201fd6736d8eb66298e75ed87571486f4364`), for development, eval and demo alike. The owner chose this for $0 cost (no hosted account). `OpenAiCompatibleChatAdapter` stays in the design and is pointed at Ollama's OpenAI-compatible API, so a hosted provider remains a config change.
- `FakeChatModel` / `FakeEmbedding` (deterministic) are used by all non-eval tests.
- A response cache keyed by `(promptHash, model, params)` is used in the `eval` profile.

## Consequences
- \+ Interview line: "Provider is a config change. Tests never hit a model."
- \+ Embedding cost is effectively zero and incremental.
- − Local chat is slow on this CPU (~4.3 min per 4k-token answer, measured). A 50-question eval run takes hours, so eval runs overnight, relies on the `(promptHash, model, params)` cache so reruns cost nothing, and keeps the context budget tight. A live demo becomes a recorded video plus a one-command local run.
- − Embedding is slower than assumed (~1.6 s/chunk, ~2 h per 5,000 chunks). Accepted, because embeddings are computed once per blob and reused ([ADR-0003](0003-content-addressed-storage.md)). Indexing jobs must report progress and be resumable.
- − Spring AI's structured-output helpers may not support every provider's native JSON-schema mode. The adapter falls back to "JSON in prompt + validate + repair" uniformly.

## M0 measurements (2026-11-07)
Ollama 0.40.15 · i7-1185G7 on AC power, "Best performance" mode · target: spring-petclinic-rest @ 5180cc9 · scripts in `tools/m0-smoke/`.

| Model | Role | Dims | Latency | Gen tok/s | Valid JSON | Spot Recall@5 (file-level, 10 paraphrased queries, 276 naive 40-line chunks) |
|---|---|---|---|---|---|---|
| `nomic-embed-text` | embed | 768 | 742.5 ms/chunk (61.9 min / 5k) | – | – | 6/10 |
| **`mxbai-embed-large`** | embed | 1024 | 1558 ms/chunk (129.8 min / 5k) | – | – | **10/10** |
| `qwen2.5-coder:7b` | chat | – | median 255.4 s (4,063 input tokens, ~115 output) | 2.9–3.4 | 3/3 | – |

Decision rule (M0 playbook 5.6): the highest spot-check recall wins. mxbai wins by 4, so the speed and dimension tie-breakers don't apply. Neither model meets the ≤ ~20 min / 5k-chunk guideline; accepted, as stated in the Consequences. Local chat misses the ≤ ~30 s viability bar by about 8×; accepted under the local-only decision.

## Revisit when
The machine changes (a GPU makes local chat viable), or eval shows the hosted model's answers aren't better than a local 7–8B on this benchmark. Then go fully local.
