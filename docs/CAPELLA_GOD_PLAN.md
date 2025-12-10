# Capella “God” Autopilot Plan

This document outlines how the AI assistant will autonomously build production‑grade Capella models from high‑level intent, with robust planning, validation, execution, verification, and logging.

## Vision
- Engineer provides intent + context; AI plans, validates, applies, verifies, and iterates until a production‑level model is built.
- End‑to‑end coverage across OA → SA → LA → PA, requirements, traceability, chains, scenarios, and all related diagrams/views.
- Safe, explainable, reversible: deterministic plans; dry‑run + diffs; single‑transaction undo; detailed telemetry.

## Architecture (Planner → Validator → Executor → Verifier)
- Planner: LLM synthesizes `TOOL_PLAN` from user intent + model snapshot + rules.
- Validator: Preflight checks (existence, layer, typing, reuse, impacts) + Capella validation packs.
- Executor: Applies in one EMF write transaction; streaming progress; cancellation; undo.
- Verifier: Post‑conditions + model validations; auto‑refinement when violations arise.
- Observability: Correlated JSONL logs (conversation + tool calls) and OSGi logs.
- Performance: ModelIndex for fast lookups; reuse existing elements; batched, minimal EMF ops.

## Feature Checklist

### Modeling Fundamentals (EMF semantics)
- [x] Create/rename/delete components, functions, exchanges (OA/SA/LA/PA)
- [x] Batch ops (rename, reparent, allocate, map, delete)
- [x] Cross‑layer ops (propagate, sync, trace)
- [x] Functional chains + scenario attachments
- [x] Requirements create/link/coverage
- [ ] Full EPBS/Physical artifacts and allocation flows
- [x] Change‑set derivation (grouped diffs for review/rollback)

- [x] Create/open representations; reveal/refresh; arrange; add nodes/edges by revealing semantics
- [x] Add/remove view‑only elements (no semantic change)
- [x] Precise geometry: set view bounds (x,y,w,h) (pin/resize/z‑order TBD)
- [x] Selection ops: align, distribute, grid (nudge/group/ungroup TBD)
- [x] Diagram lifecycle: duplicate, rename, delete, reveal
- [x] Styling: colors (label/background/border)
- [x] Styling: icons, labels text (within Capella/Sirius constraints)
- [x] Basic layout heuristics: flow and radial
- [x] Layout heuristics per diagram type (per‑type spacing; minimal crossings, initial)

### Autopilot Loop (Hands‑off build)
- [x] Plan preview + dry‑run validation + diffs + undo
- [x] Streaming responses and progress updates
- [x] Automatic fallback (Responses API for reasoning/o‑series)
- [x] Long‑run orchestration: checkpointing, resumable tasks, periodic auto‑save (initial)
- [x] Self‑healing loop: initial (one iteration refine+reapply on diagnostics)
- [x] Spec‑driven execution: initial importer (LLM-driven TOOL_PLAN from spec)

### Prompting & Tool Schema
- [x] Robust parser (explicit `type` and single‑key action objects), canonical layer mapping (operational/logical/system/physical → OA/LA/SA/PA)
- [x] LLM routing: Chat vs Responses; correct token params; SSE streaming
- [x] Expanded actions implemented:
  - [x] `add_view_node {diagram?, semantic, x?,y?}`
  - [x] `add_view_edge {diagram?, source,target}`
  - [x] `set_view_bounds {diagram?, element, x,y,width,height}`
  - [x] `remove_view_element {diagram?, element}`
  - [x] `arrange_selection {diagram?, strategy}`
  - [x] `style_view {diagram?, element, foreground?, background?, border?, label_text?, show_icon?}`
  - [x] `rename_diagram|delete_diagram|duplicate_diagram|reveal_in_diagram`
- [x] Tight JSON schema (required/optional fields, enums) + in‑prompt examples
- [x] Stable referencing: names + UUID (id)
- [x] Stable referencing: indices

### Validation & Quality Gates
- [x] Preflight existence checks (sources/targets/layers)
- [x] Post‑apply quick validation (bounded EMF Diagnostician) + telemetry summary
- [x] Auto‑fix patterns: remove orphan exchanges with missing endpoints (capped)
- [ ] Capella validation packs (full), deeper auto‑fixers
- [ ] Rule packs per layer (OA/SA/LA/PA) with gating thresholds (scaffold)
- [x] Coverage targets (advisory): component structure + function allocation
- [x] Traceability targets (advisory: requirements→functions; exchanges visibility)

### Safety & Controls
- [x] Single transaction apply + Undo
- [x] Confirmation gates for destructive ops (unless explicitly authorized)
- [x] Telemetry toggle (opt‑in)
- [x] Budgets (ops): max actions per plan with gating
- [x] Budgets (time), pause/resume (initial); quarantine mode for risky plans

### Performance & Scale
- [x] ModelIndex + targeted lookups; reuse existing elements
- [x] Asynchronous indexing (initial); [ ] incremental cache
- [ ] Large‑model heuristics (batching, pagination, viewport management)
- [ ] Scope heavy scans to semantic resources only (ModelIndex, ChangeSet, Finders)
- [ ] Concurrency‑safe indexing: rebuild under domain.runExclusive; avoid read/write races
- [ ] Dry‑run cloning: copy only semantic resources (exclude .aird/notation)
- [ ] UUID‑first resolution + per‑layer scoped searches to reduce O(N) name scans

### UX & DevX
- [x] Conversation UI (streaming), plan preview, error chips, inline diff
- [x] JSONL logs (~/.capella‑ai/logs) + OSGi logs with correlation id
- [ ] Log Inspector view (filter by id/event/time/status)
- [ ] Advanced Settings: routing, budgets, schema versioning, redaction

## MVP Scope

### Core
- [x] Create/open diagrams; add nodes/edges; arrange (flow + layered) and set bounds
- [x] CRUD on components/functions/exchanges across OA/SA/LA/PA; link requirements
- [x] Plan preview + dry‑run; apply in one transaction; single‑click undo

### Safety & Validation
- [ ] Global “Require confirm before apply” preference
- [x] Quick post‑apply validation (Diagnostician) and basic preflight (existence, layer)
- [ ] Preflight: container/containment compatibility check before create
- [x] Budgets: action/time limits enabled
 - [ ] Headless/CI mode: allow automated confirmation bypass (explicit flag) when no UI preview is possible

### Performance
- [ ] Scope scans to semantic resources (ModelIndex, ChangeSet, Finders)
- [ ] Index rebuild under read lock (`TransactionalEditingDomain.runExclusive`)
- [ ] Dry‑run clones semantic resources only (exclude .aird/notation)

### UX
- [x] Responsive chat; wrapped chips; readable bubble/card widths; cancel in progress
- [x] Inline errors/status in conversation

### Docs/Tests/Release
- [x] Buildable Tycho p2 update site; aligned feature/bundle versions
- [ ] Declare supported Capella/Sirius versions; install + quickstart guide
- [ ] Smoke tests: create LAB, add nodes/edges, arrange; apply plan; undo; validation OK
 - [ ] Plugin tests: confirm-before-apply (block apply without preview), atomic apply (inject step failure → auto‑undo), semantic‑only scans (no .aird traversals)

### Telemetry (Opt‑in)
- [x] Plan summary + layout metrics recorded when enabled
- [ ] Redaction defaults: avoid PII in conversation logs by default; retention/rotation policy

### Out of Scope for MVP (Defer)
- Full Capella validation packs + deeper auto‑fixers
- Complete EPBS/Physical allocation flows end‑to‑end
- Log Inspector UI; advanced settings surface; large‑model heuristics beyond basics

## Post‑MVP Roadmap

### Validation & Quality
- [ ] Integrate Capella validation packs (full) with gating and actionable auto‑fixers
- [ ] Rule packs per layer (OA/SA/LA/PA) with thresholds and per‑step gating

### Modeling & Fidelity
- [ ] Complete EPBS/Physical artifacts and allocation flows with traceability
- [ ] Cross‑layer conflict detection and guided resolution workflows

### Layout & Diagram UX
- [ ] Respect pinned elements and z‑order; preserve bendpoints on arrange
- [ ] Nudge, group/ungroup, multi‑select alignment; per‑diagram spacing by description ID
- [ ] Advanced heuristics (iterative crossing minimization, smarter edge routing)

### Performance & Scale
- [ ] Incremental indexing cache; memory caps and size/time guards
- [ ] Large‑model heuristics (batching, pagination, viewport virtualization)
- [ ] Background task scheduling with fair usage and throttling

### Observability & Telemetry
- [ ] Log Inspector view (filter/search/export)
- [ ] Redaction presets; retention/rotation (size/time); export bundles

### Security & Privacy
- [ ] Redaction policies for conversations and diffs; enterprise privacy modes
- [ ] Audit logs; secrets detection; clearer opt‑in/consent flows

### Reliability & Resilience
- [ ] Robust cancellation, timeouts, retries/backoff; graceful degradation
- [ ] LLM rate‑limit handling; offline/local‑only fallback tools

### Extensibility & API
- [ ] Extension points for rule packs, actions, and layout strategies; public API

### Multi‑Session & Project
- [ ] Explicit session selector UX; isolation guarantees; prevent cross‑session bleed

### Versioning & Distribution
- [ ] p2 signing; semantic versioning; upgrade notes & migrations; feature flags per capability
- [ ] Compatibility matrix (Capella/Sirius); API range checks and guards

### Testing & Tooling
- [ ] Plugin/fragment tests for core flows; layout acceptance suite
- [ ] Performance regression tests on large models; CI with sample projects

### Accessibility & I18n
- [ ] Localizable strings; keyboard navigation; high‑DPI/dark theme polish; screen reader labels

### Importers & Mapping
- [ ] draw.io/spec import idempotency; duplicate detection; mapping review UI

## Critical Gaps to Address
- Performance on large models: scope scans/clones to semantic resources; incremental/index limits; large‑model heuristics
- Validation gating: integrate Capella packs + per‑layer rules to block bad changes pre‑apply
- Creation safety: validate container/containment; constrain EClass resolution to Capella NS URIs
- Indexing concurrency: rebuild under `runExclusive`; avoid read/write races; clean shutdown of executors/listeners
- Editor access fragility: reduce reflection; prefer public APIs/commands; add fallbacks
- Privacy/logging: redaction defaults; retention/rotation; clear opt‑in; avoid PII by default
- Layout completeness: honor pinned/z‑order; nudge/group/ungroup; preserve bendpoints
- Testing/CI: plugin tests and performance suites to catch regressions early
- Approval control: global “require confirm before apply” policy enforced across entry points
- Log management: rotate JSONL logs; size/time caps; export

## Cline‑Inspired Agentization Track

### Mapping & Parity
- [ ] Clone Cline VS Code extension source under `./cline` (top‑level) and map its architecture:
  - Activation, commands lifecycle, tool registry
  - Plan → Diff → Apply → Verify loop
  - Sandbox/guardrails, budgets/timeouts
  - Telemetry/log correlation
- [ ] Produce `capella-ai/docs/integrations/cline/ARCHITECTURE.md` summarizing components and data flow
- [ ] Identify patterns to adopt directly vs adapt for Capella/Sirius

### Parity Milestones
- P1 — Guardrails & Review
  - [ ] Global “confirm before apply” gate across all entry points
  - [ ] Mandatory Dry‑Run + ChangeSet preview before mutate
  - [ ] Atomic apply option (all‑or‑nothing with auto‑undo on failure)
- P2 — Typed Tools & Schema
  - [ ] Central action schema + tool registry (strict typing, required/optional fields)
  - [ ] UUID‑first, layer‑scoped resolution utilities by default
- P3 — Extensibility
  - [ ] Public extension points for rule packs, actions, layout strategies
  - [ ] Feature flags per risky capability
- P4 — Observability
  - [ ] Correlation‑ID propagation end‑to‑end; log rotation/retention presets
  - [ ] In‑tool Log Inspector (filter/search/export)
- P5 — Quality Gates
  - [ ] Pre‑apply rule/validation pipeline with actionable auto‑fixers
  - [ ] Post‑apply verification summary (validation, coverage, traceability)

### Acceptance (Cline Parity Slice)
- All plans show a preview ChangeSet and require explicit confirmation
- Actions execute inside a single transaction with an atomic apply option
- Tools are invoked via a typed registry with enforced schemas
- Logs correlate by ID; retention/rotation enabled by default
- Rule/validation packs run pre‑apply; failures block unless explicitly overridden

## Cline Parity Implementation Plan (Engineer‑Ready)

### Current Parity (Done)
- Typed actions + parser
  - `ToolRouter.ActionType` enum and `Action.fromMap(...)` parse strict action objects
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/llm/ToolRouter.java:1422
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/llm/ToolRouter.java:1629
- Dry‑run + ChangeSet preview
  - Simulate semantics on a cloned ResourceSet: `PlanDryRun.evaluate(...)`
  - Snapshot + diff: `ModelChangeSet.snapshot(...)` + `ModelChangeSet.diff(...)`
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/session/PlanDryRun.java:18
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/model/ModelChangeSet.java:12
- Single‑transaction apply + verify
  - Apply plan in a single EMF write: `ToolRouter.applyPlan(...)` → `SessionSafeguards.runWrite(...)`
  - Post‑apply quick validation/coverage/trace telemetry
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/llm/ToolRouter.java:456
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/session/SessionSafeguards.java:28
- Chat UI + plan preview
  - Inline plan preview and dry‑run details; apply hooks
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/ui/PlanPreviewBox.java:24
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/ChatView.java:1212

### P1 — Guardrails & Review (Confirm + Atomic)
- Global “Confirm Before Apply” preference and enforcement
  - [x] Add `KEY_CONFIRM_BEFORE_APPLY` in advanced prefs; expose toggle in Chat settings
  - [x] Enforce confirm in direct and orchestrated paths with plan digest binding
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/preferences/AdvancedPreferences.java:1
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/ChatView.java:916
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/ChatView.java:1212
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/session/PlanOrchestrator.java:75
- Atomic apply option (all‑or‑nothing)
  - [x] Preference `KEY_ATOMIC_APPLY`; on first failure or budget/time exceed, stop and auto‑undo the just‑applied RecordingCommand (cannot roll back mid‑command)
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/llm/ToolRouter.java:456
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/ChatView.java:1103
- Budgets/timeouts enforcement with atomic fallback
  - [x] Tie `CURRENT_BUDGET_MS` checks to atomic option; stop and undo if exceeded (undo wired at plan level)
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/llm/ToolRouter.java:539
 - Self‑heal gating
 - [x] Refined plans (self‑heal) must respect the global confirm policy (show preview and require confirm unless an explicit “auto‑apply self‑heal” pref is enabled)
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/llm/ToolRouter.java:488
 - Orchestrator semantics
 - [x] Atomic apply applies only to single‑transaction path; for orchestrated runs, stop on first failure and optionally rollback last batch; always require confirm before start
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/session/PlanOrchestrator.java:75

### P2 — Typed Tool Registry & Strict Validation
- Centralize tool schema validation
  - Add `ToolSchema` helper consolidating required/optional fields for each action type
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/llm/ToolRouter.java:1629
- UUID‑first, layer‑scoped resolution by default
  - Add `findByUuid(...)` and layer‑scoped name finders to avoid global scans
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/model/Finders.java:12
- Container/containment preflight before create
  - Validate container supports target EClass; block apply when invalid
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/model/ElementFactory.java:106
- Constrain EClass resolution to Capella NS URIs
  - Reduce collision risk in `findEClass(...)` by whitelisting Capella package NS URIs; fail if ambiguous
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/model/ElementFactory.java:61

### P1 — Mandatory Dry‑Run + ChangeSet UI
- Require preview before apply when confirm is on
  - [x] Gate `schedulePlanExecution(...)` behind “previewed” state (digest check)
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/ChatView.java:1212
- “View Changes…” modal with ModelChangeSet summary
  - Extend `PlanEditorDialog` to show ChangeSet (uuid/class/name)
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/ui/PlanEditorDialog.java:10
 - Bind preview to plan digest
  - [x] Compute a digest (e.g., SHA‑256 of canonicalized TOOL_PLAN) when preview is shown; require the same digest at Apply time to ensure user confirmed the exact plan content
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/ui/PlanPreviewBox.java:24
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/ChatView.java:1212

### P4 — Telemetry Rotation/Retention & Redaction
- Rotation/retention defaults (size/time caps)
  - Implement retention policy in `writeJsonLine(...)` and throttle rotation checks (e.g., once per N minutes or when day changes)
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/telemetry/Telemetry.java:24
- Redaction defaults for conversations
  - Redact message bodies by default; provide opt‑in to include content
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/telemetry/Telemetry.java:86

### P3 — Extensibility (Rules/Actions/Layouts)
- Define SPI interfaces and service discovery for rule packs/actions/layouts
  - Introduce `org.polarsys.capella.ai.api.*` and wire pre/post hooks in `ToolRouter.applyPlan(...)`
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/llm/ToolRouter.java:456

### P1/P4 — Performance & Scale
- [x] Semantic‑only traversals and dry‑run clone
  - Limit ModelIndex/ChangeSet/Finders to `session.getSemanticResources()`
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/model/index/ModelIndex.java:144
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/model/ModelChangeSet.java:18
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/model/Finders.java:41
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/session/ScenarioWorkspace.java:24
- [x] Concurrency‑safe index rebuild
  - Prefer `TransactionalEditingDomain.runExclusive`; fallback to direct read; detach listeners on session close
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/model/index/ModelIndex.java:120

### P5 — Layout & Diagram UX
- Honor pinned/z‑order; preserve bendpoints; selective arrange
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/tools/DiagramCommands.java:140
- Finalize layered/type‑specific spacing
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/tools/DiagramCommands.java:165
 - Scope adjacency for layered layout to diagram‑relevant relationships (e.g., functions↔exchanges, components↔interfaces) and only among current selection
  - capella-ai/plugins/ai-capella/src/org/polarsys/capella/ai/tools/DiagramCommands.java:309

### Acceptance (Engineer)
- Apply requires preview + explicit confirmation when enabled
- Atomic apply option stops and auto‑undos on failure/budget/time exceed
- Action schemas enforce UUID‑first + layer‑scoped resolution; invalid container/containment blocks apply
- Index/ChangeSet/Dry‑run use semantic resources; index rebuild under read lock; UI stays responsive
- Telemetry rotates/retains and redacts by default; logs correlate via ID

## Acceptance Criteria
- Diagram control: view‑only add/remove/place/resize; align/distribute; readable layouts with no overlaps in ≥95% test cases on test suite.
- Cross‑layer transforms: propagate/sync/trace produce expected mappings; validated; <2% false positives.
- Autopilot: from a requirement spec, generates OA→PA skeletons + key diagrams; no fatal validation errors; undo restores pre‑state.
- Safety & logging: destructive actions require confirmation or explicit instruction; all events logged with correlation id and timing.

## Milestones & Roadmap
- M1: Complete View Control
  - Implement view actions + parsers; DiagramCommands wiring; update schema + prompt examples.
  - Acceptance tests across LAB/Operational/System diagrams.
- M2: Autopilot v1
  - Job orchestration (checkpointing, cancellation, resume) + self‑healing loop.
  - Import simple specs → OA; seed SA with core diagrams.
- M3: Quality & Patterns
  - Pattern library (naming, decomposition, interfaces); Capella validation packs + auto‑fixers; coverage targets; telemetry dashboards.
- M4: Performance & Scale
  - Incremental indexing; large‑model heuristics; advanced layout heuristics.

## Implementation Notes
- Semantics: EMF transactions (single write per plan); Finders/ModelIndex for lookups.
- Sirius: DialectManager (create/open/refresh), DRepresentation, reveal if available.
- GMF: Prefer editpart commands (ArrangeRequest, SetBoundsCommand) for bounds/layout; avoid direct notation mutation when possible. Use public APIs first; guarded reflection only if needed.
- Transactions & Undo: EMF command stack; single transaction per plan.
- Indexing: perform (re)builds under `TransactionalEditingDomain.runExclusive` to avoid concurrent reads during writes.
- Resolution: resolve by UUID first; constrain EClass resolution to Capella NS URIs; validate container/containment before create.
 - Atomic apply semantics: cannot roll back mid‑RecordingCommand; on failure/over‑budget, complete command then call undo on the command stack.
 - Non‑UI work: run validation/coverage/changeset and layout metrics off the write transaction on a background job (read lock) to keep UI responsive.

## Risk & Mitigation
- View geometry internals brittle → Prefer public APIs; guard/reflection; feature flags.
- Ambiguous name resolution → UUID‑first resolution; naming patterns; reuse preference; validation gates.
- Model‑wide scans and cloning impact large projects → Scope to semantic resources; add large‑model mode; concurrency‑safe index rebuilds.
- Element creation heuristics may pick wrong containment → Pre‑validate container/containment, run validation packs pre/post.

## Immediate Next Steps
1) Confirm plan
2) Implement M1:
   - [x] Add view actions (add/remove nodes/edges, set bounds, arrange/align/distribute, lifecycle)
   - [x] Extend ToolRouter + DiagramCommands; update schema & prompt examples
   - [x] Add telemetry for view actions; acceptance tests
3) Next
   - Tighten JSON schema + add in‑prompt examples for new actions (completed)
   - Diagram targeting by name/UID across all actions (completed)
   - Layout heuristics per diagram type (flow/radial basics implemented; add per‑type spacing and crossing minimization)
   - Optional: Log Inspector view for JSONL analysis
   - Scope heavy traversals to semantic resources (ModelIndex, ChangeSet, Finders)
   - Make ModelIndex rebuild concurrency‑safe (runExclusive) and add size/time guards
   - Restrict ScenarioWorkspace dry‑run cloning to semantic resources
   - Promote UUID‑first resolution and per‑layer scoped search utilities
   - Harden ElementFactory: resolve EClasses by Capella NS URIs; validate container/containment
   - Prefer GMF/Sirius commands for bounds/layout over direct notation edits
