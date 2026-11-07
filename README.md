# DevMind AI

DevMind AI is a codebase-intelligence system for Java repositories. It answers questions about a codebase using server-validated evidence (every citation is resolved and checked by the server, not trusted from the model), and it predicts the impact of a diff, including which tests to run. Deterministic parsers, Git and graph traversal compute the facts; the LLM only explains them. Both capabilities are backed by a reproducible benchmark with measured numbers.

**Status:** M1 — walking skeleton (M0 pre-implementation done, tag `m0`). See [docs/PLAN.md](docs/PLAN.md) and [docs/devlog.md](docs/devlog.md).
