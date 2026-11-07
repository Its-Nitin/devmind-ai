# ADR-0001: Modular monolith, package-by-module, verified with Spring Modulith
- Status: Accepted
- Date: 2026-11-06

## Context
There's one developer, a 5–6 week core timeline and a single deployable. The blueprint's package tree (`domain/ repository/ service/ web`) is organized **by technical layer**. It calls itself a modular monolith, but nothing stops `qa` code from reaching into indexing persistence. In an interview, "modular" has to mean enforced boundaries.

## Options considered
| Option | For | Against |
|---|---|---|
| A. Microservices (indexer, retrieval, chat) | Independent scaling | Distributed transactions, network failure modes, 3× the deployment work. Solves problems this project doesn't have. |
| B. Layered monolith (blueprint) | Familiar, fast start | Features smear across every layer. No real boundaries, and "extract later" becomes a rewrite. |
| C. Package-by-module + layers inside each module, verified with **Spring Modulith** | Real boundaries tested in CI; familiar layering survives inside each module; clean extraction seams (events, public APIs) | Some upfront thinking about what's public; occasional friction when a shortcut is tempting |
| D. Multi-module Maven build (one Maven module per feature) | Compile-time boundaries | Build ceremony and slower iteration for one developer. Modulith gives the same guarantees with less friction. |

## Decision
**C.** Top-level packages `com.devmind.<module>` are Spring Modulith application modules. Each module's top-level package is its public API, and everything under `.internal` is private. `ApplicationModules.verify()` runs as a unit test, and an ArchUnit test adds the layering rules inside modules (web → application → domain; persistence implements application ports; domain depends on nothing framework-specific except JPA annotations on entities).

Cross-module side effects (e.g. "revision ready → warm caches") use Spring application events (`@ApplicationModuleListener`). Direct calls go through the other module's public API interfaces.

## Consequences
- \+ Boundaries are executable. Interview line: "the build fails if `qa` touches `indexing.internal`."
- \+ The Modulith documentation generator can produce module diagrams for the README.
- − Each module has to keep its public surface small, and it takes discipline to stop "just make it public" creep. Code review checks for it ([coding-standards ARCH-3](../coding-standards.md#arch)).

## Revisit when
A module needs independent scaling (most likely embedding/indexing workers). Extract it behind the existing `JobHandler` SPI and events.
