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
- [ ] Change‑set derivation (grouped diffs for review/rollback)

### Diagram/View Mastery (Sirius/GMF)
- [x] Create/open representations; reveal/refresh; arrange; add nodes/edges by revealing semantics
- [x] Add/remove view‑only elements (no semantic change)
- [x] Precise geometry: set view bounds (x,y,w,h) (pin/resize/z‑order TBD)
- [x] Selection ops: align, distribute, grid (nudge/group/ungroup TBD)
- [x] Diagram lifecycle: duplicate, rename, delete, reveal
- [ ] Styling: icons, labels, colors (within Capella/Sirius constraints)
- [ ] Layout heuristics per diagram type (grid/flow/radial; minimal crossings; spacing)

### Autopilot Loop (Hands‑off build)
- [x] Plan preview + dry‑run validation + diffs + undo
- [x] Streaming responses and progress updates
- [x] Automatic fallback (Responses API for reasoning/o‑series)
- [ ] Long‑run orchestration: checkpointing, resumable tasks, periodic auto‑save
- [ ] Self‑healing loop: detect violations → refine plan → re‑validate → apply
- [ ] Spec‑driven execution: import requirements/spec text and materialize OA→PA

### Prompting & Tool Schema
- [x] Robust parser (explicit `type` and single‑key action objects), canonical layer mapping (operational/logical/system/physical → OA/LA/SA/PA)
- [x] LLM routing: Chat vs Responses; correct token params; SSE streaming
- [x] Expand actions:
  - [x] `add_view_node {diagram?, semantic, x?,y?}`
  - [x] `add_view_edge {diagram?, source,target}`
  - [x] `set_view_bounds {diagram?, element, x,y,width,height}`
  - [x] `remove_view_element {diagram?, element}`
  - [x] `arrange_selection {diagram?, strategy}`
  - [x] `rename_diagram|delete_diagram|duplicate_diagram|reveal_in_diagram`
- [ ] Tight JSON schema (required/optional fields, enums) + in‑prompt examples
- [ ] Stable referencing (names + UUIDs + indices) and robust resolution

### Validation & Quality Gates
- [x] Preflight existence checks (sources/targets/layers)
- [ ] Post‑apply Capella validation packs; auto‑fix patterns
- [ ] Rule packs per layer (OA/SA/LA/PA) with gating thresholds
- [ ] Coverage/traceability targets (requirements→functions→components→exchanges)

### Safety & Controls
- [x] Single transaction apply + Undo
- [x] Confirmation gates for destructive ops (unless explicitly authorized)
- [x] Telemetry toggle (opt‑in)
- [ ] Budgets (time/ops), pause/resume; quarantine mode for risky plans

### Performance & Scale
- [x] ModelIndex + targeted lookups; reuse existing elements
- [ ] Asynchronous indexing; incremental cache
- [ ] Large‑model heuristics (batching, pagination, viewport management)

### UX & DevX
- [x] Conversation UI (streaming), plan preview, error chips, inline diff
- [x] JSONL logs (~/.capella‑ai/logs) + OSGi logs with correlation id
- [ ] Log Inspector view (filter by id/event/time/status)
- [ ] Advanced Settings: routing, budgets, schema versioning, redaction

## Acceptance Criteria
- Diagram control: view‑only add/remove/place/resize; align/distribute; readable layouts with no overlaps in ≥95% test cases.
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
- GMF: Bounds + layout via editpart commands (SetBoundsCommand, ArrangeRequest), public APIs first; guarded reflection only if needed.
- Transactions & Undo: EMF command stack; single transaction per plan.

## Risk & Mitigation
- View geometry internals brittle → Prefer public APIs; guard/reflection; feature flags.
- Ambiguous name resolution → UUID‑first resolution; naming patterns; reuse preference; validation gates.

## Immediate Next Steps
1) Confirm plan
2) Implement M1:
   - Add view actions (add/remove nodes/edges, set bounds, arrange/align/distribute, lifecycle)
   - Extend ToolRouter + DiagramCommands; update schema & prompt examples
   - Add telemetry for view actions; acceptance tests
3) Iterate using JSONL logs to refine heuristics and prompts
