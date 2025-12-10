# Cline VS Code Extension — Architecture Mapping (for Capella Agent Parity)

Purpose
- Systematically map Cline’s propose → preview → apply → verify loop and guardrails to Capella’s model/diagram world.
- Identify components and patterns to adopt or adapt, then drive actionable tasks in CAPELLA_GOD_PLAN.md.

Scope
- High‑level only; concrete file references will be added after cloning source under `./cline` (top‑level folder).

What to Inspect (cloned in `./cline`)
- Extension activation & lifecycle: `package.json` (contributes.commands), `src/extension.ts` (activate/deactivate)
- Command handlers and agent loop (proposal → diff → apply → verify)
- Tool registry: how the agent exposes typed tools and validates inputs
- Sandbox/guardrails: FS/network limits, confirmation paths, budgets/timeouts
- Diff/preview surfaces: how changes are summarized and confirmed
- Telemetry/logging: correlation ID, retention/rotation
- Settings: feature flags, provider/model config, unsafe operations gating

Observed Pattern To Mirror (Cline → Capella)
- Propose: minimal plan of typed actions → TOOL_PLAN with strict schema in Capella
- Preview: code diff → ModelChangeSet + dry‑run outcomes (warnings/chips)
- Apply: file patch → EMF transaction (atomic option: all‑or‑nothing)
- Verify: tests → validation/coverage/trace gates
- Guardrails: user confirmation, budgets, timeouts, safe defaults, feature flags
- Logs: correlation ID, compact jsonl, rotation/retention

Concrete Findings (initial pass)
- Activation/events (package.json):
  - `activationEvents`: `onLanguage`, `onStartupFinished`, `workspaceContains:evals.env`
  - `main`: `./dist/extension.js`
  - `contributes.views`: Activity Bar webview id `claude-dev.SidebarProvider`
  - `contributes.commands`: `cline.plusButtonClicked`, `cline.mcpButtonClicked`, `cline.addToChat`, etc.
- Extension entry (`src/extension.ts`):
  - `activate(context)`: sets up HostProvider (webview + diff view + host bridge), registers webview provider and all commands, installs TextDocumentContentProvider for diff view (left side), and hooks URI handler. `deactivate()` tears down and logs.
  - Uses `ExtensionRegistryInfo` (`src/registry.ts`) to centralize command/view IDs; commands are namespaced via extension `name`.
  - Command handlers route to controller methods (e.g., `addToCline`, `fixWithCline`, `explainWithCline`) and send UI events (`subscribeTo*` under `core/controller/ui`).
  - Diff viewer: `VscodeDiffViewProvider` + `DIFF_VIEW_URI_SCHEME` render preview of changes as read‑only left pane.
- Host & bridge:
  - HostProvider wires VS Code host bridge gRPC client, output channel, storage paths (`src/hosts/vscode`).
  - Webview provider (`VscodeWebviewProvider`) drives the sidebar UI/controller.

Parity Mapping (Initial)
- Cline typed tools → Capella ToolRouter action schema (enforce required fields, UUID‑first)
- Diff preview → ModelChangeSet summary (uuid/class/name); dry‑run details
- Confirmation → Global “require confirm before apply” gate across all entry points
- Atomic apply → Option to auto‑undo on first failure; fail‑fast
- Budgets → max actions/time; stop/undo on exceed
- Telemetry → rotate/retain; redact by default; correlation ID propagation

Adoption Plan (See CAPELLA_GOD_PLAN.md – Cline‑Inspired Agentization Track)
- P1: Guardrails & review (confirm‑before‑apply, dry‑run preview mandatory, atomic option)
- P2: Typed tool registry (strict action schema; UUID‑first, layer‑scoped search)
- P3: Extension points (rules/actions/layout strategies)
- P4: Observability (rotation/retention, Log Inspector)
- P5: Quality gates (rule/validation packs pre‑apply)

Deliverables
- Updated CAPELLA_GOD_PLAN.md with parity milestones (done)
- Filled file map with concrete references (above); add deeper call graph references as needed
- Gap report with suggested patches

Next Mapping Steps
- Identify Cline’s “propose → preview → apply” controller flow and mirror with Capella’s `ToolRouter` + `PlanDryRun` + `ModelChangeSet`
- Extract their typed tool registry patterns and validate our action schema coverage (add missing fields/enums)
- Examine how Cline enforces confirmation and budgets at command boundaries; ensure our Chat/Orchestrator paths do the same
