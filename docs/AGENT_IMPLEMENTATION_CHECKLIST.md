# Capella AI Agent — Implementation Checklist (Backend + UI Control)

Purpose: A check-offable, end-to-end plan to deliver Cursor-grade, agent-driven modeling and UI control inside Capella. Each box should be checked only when its tests pass. Keep this file as the single source of truth for progress.

## Phase 0 — Project Prep (Tests + Fixtures + CI)
- [ ] Create PDE tests bundle `plugins/ai-capella.tests` (Tycho)
  - [ ] `pom.xml`, `MANIFEST.MF`, `plugin.xml`, `build.properties`
  - [ ] Depend on Capella target; optional SWTBot for UI tests
- [ ] Add test fixtures
  - [ ] Minimal Capella sample project with `.aird` (OA/SA/LA/PA roots)
  - [ ] Known elements: names, UUIDs, diagram kinds (LAB/PAB/etc.)
  - [ ] Store under `plugins/ai-capella.tests/resources/fixture-model.zip`
- [ ] CI scripts
  - [ ] Tycho with `JAVA_TOOL_OPTIONS` (XML entity limits) for builds/tests
  - [ ] Split test profiles: headless unit/integration vs. UI (optional)
- [ ] Definition of Done
  - [ ] Unit tests for utilities/finders/validators are green
  - [ ] Integration tests for model ops & diagram ops are green
  - [ ] UI critical-path tests (send, session pick, plan apply) are green

## Phase 1 — Session Control (Workspace + Picker + Headless)
- [ ] Headless session lifecycle
  - [ ] `SessionBridge.openSession(URI aird)` returns `Session`
  - [ ] `SessionBridge.closeSession(Session)`
  - [ ] `SessionBridge.listOpenSessions()`
  - [ ] Tests:
    - [ ] Unit: open/close idempotent, non-null Session
    - [ ] Integration: headless open of fixture `.aird`, session listed
- [ ] Session picker in Chat header
  - [ ] Show Combo when >1 sessions open; switch updates context
  - [ ] Tests:
    - [ ] UI: two sessions → picker visible; selection updates summary
- [ ] Attach resolution order
  - [ ] Priority: active editor → selection → single open session → none
  - [ ] Tests: unit (priority), UI (click editor switches session)

## Phase 2 — Incremental Model Index + Typed Finders
- [ ] Incremental `ModelIndex`
  - [ ] Attach `EContentAdapter` to ResourceSet; update on add/remove/set
  - [ ] API: `search(query, limit)`, `resolveUuid(uuid)`, `suggest(name,type,limit)`
  - [ ] Tests:
    - [ ] Unit: add/remove triggers index updates; resolveUuid O(1)
    - [ ] Integration: rename updates search results
- [ ] Typed finders for Capella concepts (`model/finder/*`)
  - [ ] LogicalComponent.byName, FunctionalExchange.byEndpoints, Capability.byName, Requirement.byId, etc.
  - [ ] Prefer UUID resolution; fallback to index+name/type
  - [ ] Tests: unit (resolves expected EObjects), integration (layer-specific)

## Phase 3 — Diagram Catalog + Diagram Service (No Reflection)
- [ ] DiagramCatalog
  - [ ] Map (layer, kind) → Sirius `RepresentationDescription`
  - [ ] Tests: unit (fixture supports LAB/PAB/SAB/OAB → non-null description)
- [ ] DiagramService
  - [ ] `openOrCreate(session, semanticRoot, layer, kind, nameTemplate)`
  - [ ] `revealOrAddNode(session, rep, semantic)` using VSM tools or official APIs
  - [ ] `connect(session, rep, source, target, linkType)` (Component/Functional exchange)
  - [ ] `arrange/zoom/selectAndCenter/export`
  - [ ] Refactor `DiagramCommands` to delegate; remove reflection paths
  - [ ] Tests:
    - [ ] Integration: create PA component → open PAB → reveal/add → arrange/select
    - [ ] Integration: create functional exchange → edge visible on diagram

## Phase 4 — Action DSL v2 + Validation + Structural Diffs
- [ ] DSL v2 schema (JSON)
  - [ ] Typed actions: create/allocate/link/open_diagram/reveal/connect/arrange/zoom/open_view/rename/delete
  - [ ] Fields: enums for `layer/kind`, `uuids`, typed `names`, `container`
  - [ ] Versioned: `tool_plan_version`
  - [ ] Tests: unit (parser accepts valid, rejects invalid)
- [ ] Validator
  - [ ] Resolve names via typed finders/index; prefer UUIDs
  - [ ] Check containment & endpoint compatibility; Issues[] with suggestions
  - [ ] Tests: unit (invalid produces Issues + suggestions), integration (fixture plan)
- [ ] Structural diffs (EMF Compare)
  - [ ] Simulate plan in in-memory clone; compute create/update/delete
  - [ ] Inline preview presents structured diffs
  - [ ] Tests: integration (accurate diffs for create/rename/delete)

## Phase 5 — Executor (Commands) + Undo/Redo
- [ ] Map each Action → typed EMF `Command`
  - [ ] Compose `CompoundCommand`; single `runWrite` + deferred save
  - [ ] Return result: applied count, errors list
  - [ ] Tests: integration (atomic apply; single Undo reverts everything)
- [ ] Batch diagram updates
  - [ ] Group reveal/add/connect with single arrange/refresh
  - [ ] Tests: performance check on large fixture; UI remains responsive

## Phase 6 — Workbench UI Controller (View/Editor/Focus)
- [ ] `WorkbenchUiController`
  - [ ] `showView(viewId)`, `openEditor(session, rep)`, `bringToFront(viewId)`
  - [ ] `focusDiagram(rep)`, `setPerspective("capella.perspective")`
  - [ ] Expose actions in DSL: `open_view`, `focus_diagram`, `reveal_select`
  - [ ] Tests: UI (open Properties view, focus diagram, selection visible)

## Phase 7 — Headless Runner (Optional)
- [ ] CLI/OSGi app `capella-ai-exec`
  - [ ] Args: `--aird PATH --plan plan.json --apply`
  - [ ] Prints Issues & structured diffs; exit codes reflect success
  - [ ] Tests: headless CI run on fixture, plan applied, exit 0

## Phase 8 — Cleanup & Deletions
- [ ] Remove reflection-based diagram commands; fully replace with DiagramService
- [ ] Replace `Finders.findByName` usages with typed finders / index
- [ ] Unify resolve-by-id via ModelIndex UUID map
- [ ] Keep PlanEditorDialog only for “Edit…” path (inline Apply/Dismiss default)
- [ ] Prune Chat tab to conversation only; keep Advanced for context/tools/settings
- [ ] Tests: build is green; no references to removed code

## Phase 9 — Performance & Reliability
- [ ] Index perf on 10k+ elements: ensure lookups <50ms typical
- [ ] Batch diagram ops tuned; avoid repeated refresh
- [ ] Logging/telemetry (opt-in) for validation/apply durations & errors

## Phase 10 — Documentation & Examples
- [ ] Developer docs: DSL schema + services (Session/Index/Diagram/Validator/Executor)
- [ ] User docs: Chat vs Advanced, Quick Setup, Session picker, inline diffs
- [ ] Example plans for LAB/PAB flows, requirement linking, capability allocation

---

## Test Matrix (Detailed)

Unit tests (headless)
- [ ] ModelIndex incremental updates (add/remove/rename)
- [ ] Typed finders (each concept resolves correct EObjects)
- [ ] DSL v2 parser (valid/invalid), Validator rules (containment/endpoints)

Integration tests (PDE)
- [ ] Session: open/close/list; attach via editor/selection/picker
- [ ] Model ops: create/rename/delete; batch operations; undo/redo
- [ ] Diagram ops: open/create diagram; reveal/add nodes; connect edges; arrange/select/zoom
- [ ] Structural diffs: EMF Compare yields accurate change sets

UI tests (SWTBot or minimal harness)
- [ ] Chat: Send via Enter; Send enables on typing; Stop works
- [ ] Session picker: switches context; context summary updates
- [ ] Inline plan: Apply applies; Edit opens editor; Dismiss removes preview
- [ ] Quick Setup: saves provider/model/key; Test connection feedback

Headless tests
- [ ] CLI run applies a plan on a `.aird`; prints Issues and diffs; exit codes as expected

---

## Acceptance Criteria (End-to-End)
- [ ] “Create PA component ‘ECU‑1’ and open it on PAB. Connect to ‘Sensor‑A’.” → Inline diff correct; Apply works; diagram opens; undo reverts
- [ ] “Create LAB centered on ‘System’.” → Diagram created/opened; selection centered
- [ ] “Allocate capability ‘Broadcast’ to ‘ECU‑1’ and link requirement R‑123.” → Preflight resolves names or suggests alternatives; inline diff shows updates; Apply succeeds

---

## Milestones & Ownership
- Milestone A (≈1 week): Session picker + headless open/close; incremental index; typed finders
- Milestone B (≈1–2 weeks): DiagramService (no reflection) + Catalog; robust reveal/connect
- Milestone C (≈1–2 weeks): DSL v2 + Validator + EMF Compare diffs; inline Apply/Edit/Dismiss
- Milestone D (≈1 week, optional): Workbench controller + headless runner

Each milestone must pass all relevant tests above before closing.

