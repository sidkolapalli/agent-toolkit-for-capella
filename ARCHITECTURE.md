# Capella AI Architecture

Capella AI lets a language model work on a live Arcadia model in Capella 7 without ever changing it unreviewed. The model reads through typed, read-only tools and changes the model only by proposing a Groovy script against a curated API. A person reviews every proposal; the plugin runs the exact reviewed source once, in one undoable transaction, and then checks the result independently before the model is told what happened.

All Java code is under `plugins/ai-capella/src/com/sidkolapalli/capella/ai/`.

## Why scripts for writes and typed tools for reads

- **Writes are scripts.** One proposal can create a whole subsystem with loops, transitions between layers and diagrams, which would take dozens of single-purpose tool calls. Scripts run against `api.*` (below), not raw EMF, so they inherit the API's safety checks.
- **Reads are typed tools.** Reading must never change the model or run generated code, so the eight read tools take data arguments only and run in native read transactions.
- **The API is discovered, not memorised.** `api.fs` and the `capella_docs` tool serve the markdown in `resources/capella-api/` on demand, and `ApiCatalog` lists capabilities, so the system prompt stays small.

## One request, end to end

```
ChatView (captures model + selection)
  -> ScriptRouter.createRuntime
       CodexArcadiaAgent  (default: bundled Codex app-server over JSONL stdio, ChatGPT sign-in)
       ArcadiaAgent       (Direct provider: OpenAI, Anthropic, Azure, OpenRouter, custom)
  -> read tools (ArcadiaModelTools)              model_summary, model_search, model_inspect,
                                                 model_relationships, model_validate,
                                                 native_validation, capella_docs, capella_capabilities
  -> proposal: Groovy + typed postconditions
  -> GroovyCompilationValidator (compile only, nothing runs; bounded automatic repair)
  -> person reviews in CodePreviewDialog (Changes and Script tabs)
  -> ReviewedEdit
       GroovyExecutor: one EMF transaction, deadline, cooperative cancellation, save after commit
       ModelChangeSummary: net created / edited / deleted, with a per-perspective digest
       ModelPostconditions + NativeStructuralAssertions: declared checks read back
       ModelVerification: native validator diagnostics before vs after
  -> ExecutionOutcome (authoritative receipt) -> agent continues in a fresh bounded turn
  -> TaskContinuation: a budget-limited task with edits pending continues as a fresh request
```

A step is marked complete only when its change committed with observed changes, its declared checks passed and no native error diagnostics were added. Rollback, a save failure after commit and unavailable evidence are reported as distinct outcomes. A committed script is never replayed.

## Hosts that share the pipeline

| Host | Entry | Review |
| --- | --- | --- |
| Chat view | `ChatView.processRequest` | person, in the chat's review area and dialog |
| MCP clients (Claude Code, Codex, any MCP client) | `POST /mcp`, `dev/McpServer` + `dev/McpEdits` | person, always, in Capella's dialog |
| Evaluation | `POST /eval`, `agent/EvalRunner` | auto-approved; opt-in with `-Dcapella.ai.eval=true`, disposable models only |

`McpServer` is dual-era per the MCP specification: 2026-07-28 clients (per-request `_meta`, required headers, `server/discover`) and handshake clients (2025-11-25 and earlier). MCP edits are polled (`propose_model_edit` then `edit_status`), keyed by session, summary, script and checks so a retried request never runs twice.

## Script API

Bindings: `api`, `selection`, `activeDiagram`, `log`.

- `api` (`CapellaAPI`): find, create, link and modify across OA, SA, LA, PA and EPBS; `set` converts enum literal names.
- `api.diagram` (`DiagramAPI`, `tools/DiagramCommands`): create, populate and lay out diagrams. `layout(d, "auto")` runs Eclipse ELK layered with hierarchy, writes orthogonal routes, port positions and label offsets back to the GMF notation (`tools/ElkDiagramLayout`), and gives auto-sized shapes explicit sizes so the drawing matches the plan.
- `api.ops`, `api.arcadia`: native engineering operations (allocations, realizations, chains, deployment, physical links, interfaces).
- `api.requirements` (Requirements Viewpoint), `api.style`, `api.skills`, `api.fs`.

## Model checks the agent can run

`model_validate` reports stored-reference faults as errors and Arcadia method gaps as warnings with a `rule` id: unallocated or unrealized leaf functions, functional exchanges between components without a component exchange, dangling exchanges. Rules that need to see every allocation run only on whole-layer reads (`methodology_scope`). `native_validation` runs Capella's own validator and pages its findings.

## Safety model

- Read tools take data only: no Groovy, AQL, reflection, shell or workspace paths.
- Generated code never runs without review; review cannot be skipped for MCP edits.
- `GroovyExecutor` is an in-process executor with a sandbox (blocked classes such as `java.lang.System`) and cooperative cancellation. It is not an isolation boundary for hostile code, and effects outside the EMF model are not undone by rollback.
- The Codex child runtime has shell, filesystem, patch, MCP, browser and web-search tools disabled, runs read-only with network access disabled, and generic approval requests are declined.
- The dev server binds loopback only, rejects browser requests (`Origin`) and requires a bearer token on every request. The token is random per start and written to `~/.capella-ai/devserver-<port>.token`, so a local process needs read access to the user's home to call `/execute` or `/mcp`.
- Optional tokenization (`privacy/Tokenizer`) replaces quoted names and UUIDs before anything reaches a provider.

## Measuring quality

`test-models/evals/` holds tasks scored only by native checks. `POST /eval` reports pass/fail, requests, tool calls, proposals, commits, repairs, continuations, duration and, for blocked tasks, the failure feedback. A change to prompts, tools or models is an improvement only if this score rises.
