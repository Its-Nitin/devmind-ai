# ADR-0012: Agent capability = read-only MCP server + bounded loop (stretch, M7)
- Status: Accepted (stretch)
- Date: 2026-11-06

## Context
The blueprint's Product 1.2 is "controlled agent that prepares patches". Patch generation is crowded (every coding assistant does it), risky (needs sandboxed builds), and adds little evidence of skill beyond what M1–M6 show. What *is* distinctive: exposing DevMind's verified analysis as tools other agents can call, and showing a bounded agent loop that's measurably better (or not) than the fixed pipeline.

## Options considered
| Option | For | Against |
|---|---|---|
| Patch-proposing agent | Flashy | Sandbox requirement, safety work, low differentiation |
| In-app tool-calling chat only | Self-contained | Tools usable only from DevMind's UI |
| **MCP server (read-only tools) + in-app bounded agent using the same tool registry** | Reusable from any MCP client (e.g. Claude Code, IDEs); the same tools power the in-app agent; strong current-industry signal | MCP needs its own auth/binding story |

## Decision
- Tool registry in `agent`: `search_code`, `get_symbol`, `get_file_slice`, `find_callers`, `find_implementations`, `find_tests_for`, `inspect_diff`. All are **read-only**, take validated, typed arguments, are project-scoped and have per-call timeouts.
- MCP server via Spring AI's MCP server support (verify the current starter at M7 time), bound to localhost by default, with a static bearer token from env when exposed.
- Agent loop: max 6 iterations, max 12 tool calls, 60 s wall clock. Every call is logged to a `tool_call` audit table. The final answer must go through the same citation validator ([ADR-0008](0008-citation-by-evidence-id.md)).
- Eval: agent vs fixed pipeline on cross-file and dependency questions (quality, tool calls, latency, tokens).

## Consequences
- \+ "How is an agent different from a chatbot?" is answered with your own code and numbers.
- − Only after M6. If time runs out, this ADR remains a documented future direction, which is also fine to show.
