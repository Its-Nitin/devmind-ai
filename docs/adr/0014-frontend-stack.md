# ADR-0014: Angular (standalone, signals, zoneless), PrimeNG, CodeMirror 6, Cytoscape.js
- Status: Accepted
- Date: 2026-11-06

## Context
The UI needs a project list, job status polling, file tree, read-only code viewer with line highlighting, chat with citation chips, evidence panel, impact graph and findings report. It's a developer tool, so it should be dense and functional, not decorative.

## Decisions and alternatives
| Concern | Decision | Alternatives rejected |
|---|---|---|
| Framework | **Angular, current stable major** at scaffold time: standalone components, signals, new control flow (`@if/@for`), zoneless change detection, strict TS | — (your strength; the point is AI engineering, not learning React) |
| State | **Signals in feature-scoped services** (`*Store` classes with `signal`/`computed`, RxJS only for HTTP and streams). Convert with `toSignal`/`rxResource`/`httpResource` where available. | NgRx: overkill for this app size |
| Components | **PrimeNG** (Tree, Table, Splitter, Tag, Toast) | Angular Material: fewer dense data components; hand-rolled: time sink |
| Code viewer | **CodeMirror 6** read-only with Java language + line highlight decorations | Monaco: ~MBs of bundle, overkill for read-only; Prism: no virtualized large-file viewing |
| Graph | **Cytoscape.js** (dagre/elk layout), capped at 150 nodes, styled by provenance | D3: too low-level for the time box; vis-network: less maintained |
| API client | **Types generated from OpenAPI** (`openapi-typescript`) + thin hand-written services over `HttpClient` | Full generated Angular client: verbose, awkward with signals |
| Tests | The CLI's default unit runner for the chosen Angular version (Vitest in recent versions) + Angular Testing Library style; Playwright for one end-to-end happy-path test | Karma (deprecated) |
| Lint/format | angular-eslint + typescript-eslint strict + Prettier | — |

## Consequences
- \+ Modern Angular idioms are themselves a signal for senior frontend roles.
- − Check PrimeNG's compatibility with the Angular major you scaffold. If it lags, pin Angular to the newest major PrimeNG supports.
