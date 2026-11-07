# M7 — Stretch: Read-only MCP Server + Bounded Agent: Step-by-step Plan

| | |
|---|---|
| Milestone | M7, stretch (see [PLAN.md §5](../PLAN.md#5-milestones), [ADR-0012](../adr/0012-mcp-and-bounded-agent.md)) |
| Budget | 8–12 hours |
| Prerequisite | **`m6` tagged.** Don't start before the core is shipped. |
| Goal | Expose DevMind's verified analysis as read-only tools. (1) An MCP server that any MCP client can use. (2) An in-app bounded agent loop. Measure whether the agent beats the fixed pipeline. |
| Hard constraints | Read-only tools only · typed and validated arguments · project-scoped · per-call timeouts · iteration and tool-call caps · full audit · final answers pass the same citation validator |

---

## Progress tracker

| # | Step | PR | Time | Done |
|---|---|---|---|---|
| 1 | Module design without dependency cycles | `docs/adr-0012-addendum` | 30 min | ☐ |
| 2 | Tool registry + 7 read-only tools | `feat/agent-tools` | 3 h | ☐ |
| 3 | Tool-call audit table | `feat/agent-audit` | 45 min | ☐ |
| 4 | MCP server binding | `feat/mcp-server` | 2 h | ☐ |
| 5 | Bounded agent loop | `feat/agent-loop` | 2–3 h | ☐ |
| 6 | Agent endpoint + UI toggle | `feat/agent-api-ui` | 1–2 h | ☐ |
| 7 | Agent vs pipeline evaluation | `eval/agent-vs-pipeline` | 1–2 h | ☐ |
| 8 | Close out M7 | — | 15 min | ☐ |

---

## Step 1 — Module design without dependency cycles (30 min)

Problem: the agent loop needs `ai` (ChatModelPort), and the MCP binding needs Spring AI types, which may live only in `ai.internal` (ARCH-8). If `ai` depended on `agent`, that would be a cycle (ARCH-4).

Resolution (record it as an addendum in ADR-0012 or a new ADR, DOC-1):
- `ai` (public API) defines a **plain-Java SPI**:
  ```java
  public interface ToolCatalog { List<ToolSpec> tools(); }
  public record ToolSpec(String name, String description, String inputJsonSchema, ToolInvoker invoker) {}
  public interface ToolInvoker { ToolResult invoke(ToolCallContext ctx, String argumentsJson); }
  ```
- `agent` implements `ToolCatalog`, depending on `ai`, `retrieval`, `indexing` and `impact`. It contains the tools and the loop.
- `ai.internal.mcp` adapts every `ToolCatalog` bean to the MCP server using Spring AI's MCP server support. It depends only on its own SPI, so there's no cycle.
- `ModularityTest` must stay green.

**Done when:** the ADR addendum is merged and the module dependency diagram is updated.

## Step 2 — Tool registry + read-only tools (3 h) · `agent`

| Tool | Arguments (validated) | Returns | Backed by |
|---|---|---|---|
| `search_code` | `projectId`, `revision`, `query (≤500)`, `limit (≤10)` | evidence items (id, path, lines, symbol, 40-line excerpt) | `RetrievalApi` |
| `get_symbol` | `projectId`, `revision`, `qualifiedName` or `symbolKey` | symbol detail + source (≤ 200 lines) | indexing read API |
| `get_file_slice` | `projectId`, `revision`, `path`, `from`, `to` (≤ 300 lines) | source slice | indexing read API |
| `find_callers` | `projectId`, `revision`, `symbolKey`, `depth (1..2)` | callers with provenance + paths | graph traversal |
| `find_implementations` | `projectId`, `revision`, `symbolKey` | implementations / overrides | graph traversal |
| `find_tests_for` | `projectId`, `revision`, `symbolKey` | tests reaching the symbol, with reasons | `TestRecommender` |
| `inspect_diff` | `projectId`, `baseRef`, `headRef` | changed symbols + hunk summary (no full diff > 300 lines) | impact diff service |

Rules:
1. **Arguments:** parse JSON into a record per tool, then apply Bean Validation. Unknown fields are rejected. On a validation error, return a `ToolResult.error("INVALID_ARGUMENTS", details)` to the model, without throwing.
2. **Authorization:** every invocation runs as a user (`ToolCallContext.user`) and calls `ProjectAccessPolicy` (SEC-1). For MCP, the user is the token's configured owner (step 4).
3. **Output caps:** a maximum of ~8 KB of text per result, truncated with a marker. Evidence returned to the agent gets registry-assigned IDs (`T1-E1`, …) so final citations can be validated (step 5).
4. **Timeouts:** 10 s per tool call (ERR-6).
5. **Read-only enforcement:** tools may call only read methods of other modules' public APIs. Add an ArchUnit rule: `agent.internal.tools..` must not call methods named `create*|delete*|update*|start*|submit*` on other modules. `inspect_diff` reads analysis data; it must **not** start an analysis job.
6. **No shell, filesystem or network tools** (SEC-4, AI-7).

Tests: per tool, valid args → expected shape; invalid args → `INVALID_ARGUMENTS`; non-owner → `NOT_FOUND`; output truncation; the read-only ArchUnit rule.

**Done when:** all seven tools are tested in isolation.

## Step 3 — Tool-call audit (45 min) · `V9__tool_call.sql`

```sql
CREATE TABLE tool_call (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id     bigint REFERENCES project(id) ON DELETE CASCADE,
  user_id        bigint REFERENCES app_user(id) ON DELETE CASCADE,
  channel        text NOT NULL CHECK (channel IN ('AGENT','MCP')),
  session_id     text NOT NULL,          -- agent run id or MCP session id
  tool_name      text NOT NULL,
  arguments_hash char(64) NOT NULL,      -- hash, not content (OBS-3)
  outcome        text NOT NULL CHECK (outcome IN ('OK','INVALID_ARGUMENTS','NOT_FOUND','TIMEOUT','ERROR')),
  latency_ms     int  NOT NULL,
  result_bytes   int  NOT NULL,
  created_at     timestamptz NOT NULL
);
CREATE INDEX tool_call_project_idx ON tool_call (project_id);
CREATE INDEX tool_call_user_idx    ON tool_call (user_id);
CREATE INDEX tool_call_session_idx ON tool_call (session_id);
```
Every invocation through `ToolInvoker` writes a row via a decorator. Add the table to the M6 deletion test.

**Done when:** each tool call produces exactly one audit row.

## Step 4 — MCP server binding (2 h) · `ai.internal.mcp`

1. Add the Spring AI MCP server starter for WebMVC. **Check the current artifact name and annotation/registration API** in the Spring AI reference at implementation time, since this area moves quickly.
2. Register each `ToolSpec` as an MCP tool (name, description, input JSON schema), with an invoker that adapts to `ToolInvoker`.
3. **Transport:** the HTTP transport offered by the starter, served on a **separate path** (e.g. `/mcp`) excluded from the SPA session security chain.
4. **Auth** ([ADR-0010](../adr/0010-session-auth-same-origin.md) consequence):
   - disabled by default (`devmind.mcp.enabled=false`);
   - when enabled: bound to localhost, or behind the proxy with a static bearer token from env `DEVMIND_MCP_TOKEN`, mapped to one configured DevMind user (`devmind.mcp.user-email`);
   - a separate `SecurityFilterChain` for `/mcp/**` with a bearer token filter, no CSRF (non-browser client) and no session;
   - rate-limited like `/retrieve`.
5. **Prompt-injection note:** MCP clients will feed tool results to their own models. Results are repository data; prefix each result with `"Untrusted repository content follows."`, and document it in the threat model.
6. **Manual test:** connect an MCP client, such as Claude Code with an MCP server entry pointing at `http://localhost:8080/mcp` and the token header. Then ask it "using devmind, who calls `<symbol>`?" and verify the audit rows.
7. **Tests:** token missing or wrong → 401; MCP disabled → 404; the tool listing contains exactly the 7 tools; one tool invocation end-to-end through the MCP endpoint (if the starter offers a test client; otherwise test the adapter layer).

**Done when:** an external MCP client successfully calls `find_callers` against PetClinic.

## Step 5 — Bounded agent loop (2–3 h) · `agent`

A provider-agnostic **JSON action protocol** keeps the loop transparent and testable with the fake model. Native tool calling is an alternative, but would tie the loop to provider features.

1. **Prompt `agent-step.v1`:** the system prompt states the rules (untrusted evidence, read-only tools, cite IDs, stop when sufficient) plus the tool list with JSON schemas. The user message holds the question plus a **scratchpad** of previous steps (tool name, arguments, truncated result with evidence IDs).
2. **Step output:**
   ```java
   sealed interface AgentAction {
     record CallTool(@NotBlank String tool, @NotNull JsonNode arguments, @NotBlank @Size(max = 300) String reason) implements AgentAction {}
     record Finish(@Valid QaModelOutput answer) implements AgentAction {}     // same schema as Q&A
   }
   ```
   It's validated like any structured output: one repair, then stop (AI-3/4).
3. **Loop** (`AgentRunner`). Each limit comes from config, and reaching one is reported:
   ```
   for step in 1..maxIterations (6):
       if toolCalls >= maxToolCalls (12) or elapsed > wallClock (60 s): stop → BUDGET_EXCEEDED
       action = model.generate(agent-step, scratchpad)
       if Finish → validate citations against ALL evidence IDs returned by tools in this run → return
       if CallTool → reject unknown tools; detect repeats (same tool + same args hash twice → inject "already called" note);
                     invoke with timeout; append result to scratchpad (truncated)
   on stop without Finish → return EvidenceOnly(collected evidence, reason)
   ```
4. **Citation validation:** the same `CitationValidator`, with the evidence map = everything tools returned during the run ([ADR-0008](../adr/0008-citation-by-evidence-id.md)).
5. **Trace:** `AgentTrace` (steps: action, tool, args summary, outcome, latency) is returned to the UI and stored as JSON on the assistant `chat_message`.
6. **Tests (FakeChatModel scripts):** a direct finish; tool → tool → finish; an invalid tool name; repeated-call handling; hitting each budget; a finish citing an ID not returned by tools → dropped; the model outputting an action for a non-read tool name → rejected.

**Done when:** every budget path and validation path is tested.

## Step 6 — Agent endpoint + UI toggle (1–2 h)

1. `POST /api/projects/{projectId}/chat` accepts `mode: PIPELINE | AGENT` (default PIPELINE). The response adds `trace` in agent mode. Add it to the authz matrix and rate limits. Agent mode counts as **3** chat requests in the bucket.
2. **UI:** a mode toggle in the chat input; a collapsible **trace view** listing each tool call with its reason, outcome and latency; evidence from tools is clickable like normal citations.
3. **Tests:** trace rendering; the toggle sends the correct mode.

**Done when:** agent mode works in the browser with a visible trace.

## Step 7 — Agent vs pipeline evaluation (1–2 h)

1. `--eval.mode=qa --eval.chat-mode=AGENT` on the dev split, focusing on `cross_file_trace` and `dependency_callers`, plus overall.
2. **Compare** with the pipeline baseline (same model, temperature 0, cache on):
   - automatic QA metrics;
   - manual review of changed answers only;
   - mean and p95 tool calls, iterations, latency, tokens and cost per question;
   - budget-exceeded rate.
3. Write `evaluation/reports/experiment-d-agent.md`, with an honest conclusion: if the agent is better only on some categories, or worse and more expensive, say so. That's still a strong interview story.

**Done when:** the report is committed.

## Step 8 — Close out M7 (15 min)
- [ ] No module cycles; ADR updated
- [ ] 7 read-only tools, validated, project-scoped, audited
- [ ] MCP server usable from an external client with a token; disabled by default
- [ ] Agent loop with all budgets enforced and tested; citations validated
- [ ] Experiment D committed; README results and limitations updated
- [ ] Threat model updated for MCP and the agent (SEC-11)

Tag `m7`, devlog entry, README "Agent & MCP" section.

## Pitfalls
- **MCP starter churn:** pin the version, and keep the adapter thin so an API change touches one package.
- **Context bloat in the scratchpad:** truncate tool results aggressively. Long scratchpads raise cost and lower quality.
- **Agent "looks smarter" bias:** judge by the eval, not by impressive traces.
