# ADR-0004: JavaParser + SymbolSolver; edges carry provenance
- Status: Accepted
- Date: 2026-11-06

## Context
The system needs declarations with exact line ranges (straightforward) and a call/reference graph (hard: resolving `ownerService.findById(id)` to a declaration requires type resolution across the repo, the JDK and dependencies).

## Options considered
| Option | For | Against |
|---|---|---|
| A. JavaParser + JavaSymbolSolver | Pure Java, embeddable, good line positions, combined type solvers (reflection, source, JARs) | Resolution fails silently-ish on missing dependencies, Lombok and some generics; performance is fine at this scale |
| B. Tree-sitter (via JNI/bindings) | Multi-language, fast, incremental | Syntax only, with no type resolution, so call edges become name guesses; Java bindings are less mature |
| C. Eclipse JDT core | Compiler-grade resolution | Heavy API, needs a classpath setup akin to a build; steeper learning curve |
| D. Spoon | Rich model, refactoring-grade | Builds a full model in memory; also needs a classpath; heavier than needed |
| E. Run the build and use compiled bytecode (ASM) for calls | Exact call targets | Requires building arbitrary repos, which is a security and sandboxing problem the MVP shouldn't take on |

## Decision
**A**, behind a `CodeParser` port (declarations) and an `EdgeResolver` port (relationships).

- The type solver chain is `ReflectionTypeSolver` (JDK) + `JavaParserTypeSolver` per source root + optional `JarTypeSolver`s when a dependency directory is supplied via config. DevMind does **not** run the target's build.
- Every edge records its `provenance`: `STATIC_RESOLVED` (the symbol solver resolved it), `STATIC_NAME_MATCH` (resolution failed; matched by simple name + arity within the repo), `HEURISTIC` (e.g. interface→impl when there's exactly one implementation, or HTTP URL matching), `LLM_INFERRED` (never used by traversal unless explicitly requested).
- The edge resolution rate (`resolved / total call sites`) is a reported index metric.

## Consequences
- \+ Honest graph: the UI and reports can say "verified" only when it is.
- \+ The ports allow Tree-sitter or a TypeScript extractor (M8) for other languages.
- − Spring DI semantics (qualifiers, profiles, multiple implementations) aren't modelled, so an interface with N implementations gets edges to all of them with `HEURISTIC` provenance. This is documented as a limitation.
- − Lombok members don't exist in source. Calls to them stay unresolved.

## Revisit when
Resolution rate on the benchmark repo is below ~70% even with JARs supplied. Evaluate JDT then.
