# DevMind frontend

Angular 22 SPA (zoneless, signals, OnPush by default) with PrimeNG. It talks to the backend through `/api` on the
same origin, using the session cookie and the XSRF-TOKEN cookie/header pair.

| Command                           | What it does                                                                                       |
| --------------------------------- | -------------------------------------------------------------------------------------------------- |
| `npm start`                       | Dev server on http://localhost:4200; `/api` is proxied to the backend on :8080 (`proxy.conf.json`) |
| `npm test -- --watch=false`       | Unit tests (Vitest)                                                                                |
| `npm run lint`                    | ESLint: Angular rules, type-aware TypeScript rules, feature boundaries (FE-5), SEC-9 bans          |
| `npm run format` / `format:check` | Prettier                                                                                           |
| `npm run build`                   | Production build into `dist/frontend/browser`                                                      |
| `npm run api:types`               | Regenerates `src/app/core/api/schema.d.ts` from `../backend/openapi.json` (API-10, FE-7)           |

Layout (FE-5): `core/` app-wide singletons (API types, auth, HTTP interceptors, error handling), `shared/`
presentational UI and pipes, `features/<name>/` one lazy-loaded feature each. Features never import each other.

Node: the version in `.nvmrc` (CI uses the same file).
