# ADR-0013: Java 25, Maven wrapper, single backend module, monorepo
- Status: Accepted
- Date: 2026-11-06

## Context
Toolchain choices with low stakes, but they need to be made once and not debated again.

## Decisions and alternatives
| Topic | Decision | Alternatives rejected (why) |
|---|---|---|
| JDK | **Java 25 LTS** (Temurin). Java 21 is acceptable if you prefer it: change `java.version` only. | 21 is fine, but 25 is the current LTS. Nothing here needs preview features (don't enable them). |
| Framework | **Spring Boot 4.x** (current GA at scaffold time, via start.spring.io), Spring Modulith, Spring AI version compatible with that Boot line | Boot 3.x: still viable, but new work should start on the current line |
| Build | **Maven + wrapper** (`./mvnw`), single module `backend/` | Gradle: equally fine; Maven is more common in enterprise and simpler to reason about. Multi-module: see ADR-0001. |
| Formatting | **Spotless + palantir-java-format** (or google-java-format, pick one in M1 and never debate it again) | Manual style: unenforceable |
| Static analysis | **Error Prone + NullAway** with JSpecify `@NullMarked` packages; Checkstyle for the few rules the formatter doesn't cover | SpotBugs: overlapping, noisier |
| Boilerplate | **No Lombok.** Records for DTOs/values; IDE-generated or explicit code for entities | Lombok hides members from source parsers (ironic for this project), complicates NullAway and is a common interview flashpoint |
| Mapping | Hand-written static mappers | MapStruct: adds annotation processing for little gain at this size |
| Migrations | **Flyway**, SQL only | Liquibase XML/YAML: more ceremony |
| API docs | **springdoc-openapi**; frontend types generated from the OpenAPI spec (`openapi-typescript`) | Hand-written TS interfaces: drift |
| Tests | JUnit 5/Jupiter, AssertJ, Mockito (sparingly), **Testcontainers** (`pgvector/pgvector` image), Spring Boot test slices, ArchUnit + Modulith | H2 for tests: no pgvector, no FTS parity |
| CI | GitHub Actions: backend `./mvnw verify`, frontend lint/test/build, on every push and PR | — |
| Line endings | `.gitattributes`: `* text=auto eol=lf`; runtime normalizes CRLF→LF before hashing | — |
| Repo | **Monorepo** (`backend/`, `frontend/`, `evaluation/`, `deploy/`, `docs/`) | Polyrepo: no benefit for one developer, and it breaks atomic API+UI changes |

## Revisit when
A dependency forces a different JDK or Boot line.
