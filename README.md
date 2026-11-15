# DevMind AI

DevMind AI is a codebase-intelligence system for Java repositories. It answers questions about a codebase using server-validated evidence (every citation is resolved and checked by the server, not trusted from the model), and it predicts the impact of a diff, including which tests to run. Deterministic parsers, Git and graph traversal compute the facts; the LLM only explains them. Both capabilities are backed by a reproducible benchmark with measured numbers.

**Status:** M2 — ingestion and structural index. M1 (walking skeleton) done, tag `m1`. See [docs/PLAN.md](docs/PLAN.md) and [docs/devlog.md](docs/devlog.md).

## Run it

Needs only Docker (Postgres runs in a container).

```sh
cp deploy/.env.example deploy/.env   # set DB_PASSWORD and DEVMIND_SEED_PASSWORD
docker compose --env-file deploy/.env -f deploy/compose.yaml up --build
```

Open http://localhost:8088 and log in as `demo@devmind.local` with your `DEVMIND_SEED_PASSWORD`. Development commands are in [CLAUDE.md](CLAUDE.md#commands).
