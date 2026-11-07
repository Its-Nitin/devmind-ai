# ADR-0006: Own AI ports; Spring AI inside adapters; local embeddings, configurable chat
- Status: Accepted (embedding model choice finalized in M0)
- Date: 2026-11-06

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
- **Embedding model:** a general text embedding model served by Ollama (candidates: `nomic-embed-text`, 768-d; `mxbai-embed-large`, 1024-d; or a code-specific model if available in Ollama at M0 time). Pick by M0 latency plus a 20-question retrieval spot check. Record model name **and digest** in `embedding_model`.
- **Chat model:** config-only (`devmind.ai.chat.provider/model`). Benchmarks run at temperature 0 with a pinned model version string, and the report records them.
- `FakeChatModel` / `FakeEmbedding` (deterministic) are used by all non-eval tests.
- A response cache keyed by `(promptHash, model, params)` is used in the `eval` profile.

## Consequences
- \+ Interview line: "Provider is a config change. Tests never hit a model."
- \+ Embedding cost is effectively zero and incremental.
- − Hosted chat needs an API key and a spending cap ([PLAN M0](../PLAN.md)).
- − Spring AI's structured-output helpers may not support every provider's native JSON-schema mode. The adapter falls back to "JSON in prompt + validate + repair" uniformly.

## Revisit when
The machine changes (a GPU makes local chat viable), or eval shows the hosted model's answers aren't better than a local 7–8B on this benchmark. Then go fully local.
