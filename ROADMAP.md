# Arcadia Copilot Roadmap

> Goal: elevate the Capella AI Assistant from a useful helper to a Cursor-like copilot for Arcadia-based systems engineering. Each checklist item represents a deliverable that should be demonstrably useful before being checked off.

## 1. Foundations & Infrastructure

- [x] **Session Safety Review** – audit every backend entry point (Jobs, EMF commands, Sirius refresh) to guarantee UI-thread access and robust undo/redo behaviour.
  - SessionSafeguards now wraps plan execution in a single UI-thread EMF transaction with deferred saves for reliable undo/redo.
- [x] **Telemetry & Logging Hooks** – add opt-in instrumentation so we can prioritise features based on real usage (prompt categories, plan sizes, error rates).
  - Opt-in telemetry checkbox records anonymised plan metrics to the plugin log (suggested vs executed, errors).
- [x] **Scenario Workspace** – introduce a lightweight sandbox (clone of the active session) so plans can run in “dry-run” mode and display diffs before commit.
  - PlanDryRun builds an in-memory clone to dry-run TOOL_PLAN actions and feeds warnings into the Plan Editor diff preview.

## 2. Rich Model Comprehension

- [x] **Model Indexing** – searchable index of model elements exposed in the assistant UI.
  - ModelIndex powers the new Search Model button and LLM prompts can reuse the cached descriptors.

- [x] **Layer-Aware Summaries** – extend `ModelSummary` to produce per-layer dashboards (Operational, System, Logical, Physical) with allocations, gaps, and coverage metrics.
  - Explain Model now emits per-layer dashboards showing coverage, allocation gaps, and component/function health.
- [x] **Dependency Graph Cache** – maintain quick lookups for component/function relationships, exchanges, and requirement links to answer “impact of change” queries.
  - ModelAnalytics builds a cached dependency graph (component→functions/requirements) exposed via Explain Model and APIs.
- [x] **Validation Insights** – surface Capella validation results, warnings, and TODOs directly in the conversation context.
  - Explain Model lists live validation errors/warnings and Plan Editor dry-runs flag issues inline.
- [x] **Scenario & Functional Chain Snapshots** – summarise functional chains, state machines, and scenarios so the assistant can reason about behaviour, not just structure.
  - Summaries now include functional chain/scenario tallies with representative names.

## 3. Action Surface Expansion

- [x] **Operational Analysis Actions** – create capabilities to generate missions, activities, interactions, and link them to System Analysis elements.
  - UI templates now cover mission/activity/exchange creation and capability linking, backed by dry-run validation and telemetry counts.
- [x] **System Analysis Actions** – support architectural breakdowns, behaviour modelling, interfaces, and capability allocations.
  - System templates trigger component/function/interface scaffolding with sandbox checks and capability allocation helpers wired into telemetry.
- [x] **Logical ↔ Physical Transformations** – automate derivations and synchronisations between layers (e.g., propagate logical components to physical architecture with traceability).
  - Plans can now propagate logical components into PA with optional hierarchy cloning, sync existing physical components from logical definitions, and forge trace links, all with dry-run previews and confirmation gates.
- [x] **Functional Chain Authoring** – allow creation, extension, and validation of functional chains, including exchange sequencing and scenario ties.
  - Chain templates now create chains, extend them with functions/exchanges, and attach scenarios with sandbox validation before execution.
- [x] **Requirement Handling** – enable creation, linking, and coverage checks for requirements and verification elements.
  - Requirement templates support creation, trace linking, and coverage logging with dry-run validation and prompt wiring.
- [ ] **Mass Edit & Allocation Commands** – provide batch rename, re-parent, allocate, and mapping operations guided by the LLM.

## 4. Conversational Experience

- [x] **Plan Editor UI** – rich Plan Editor dialog with checkbox table, per-action details, and bulk toggles now replaces the old confirmation popup.
- [ ] **Diff Visualisation** – show Capella element diffs (added/changed/removed) before executing a plan, and provide a one-click “Undo AI plan”.
  - Plan Editor now previews creates/updates/deletes and surfaces undo guidance; richer visual diffs and one-click rollback still pending.
- [ ] **Context Cards** – display contextual panels (selected element summary, impacted requirements, diagram thumbnails) alongside chat responses.
- [ ] **Inline Suggestions & Autocomplete** – add quick command palette (`/command`) and auto-complete prompts for common modelling tasks.
- [ ] **Streaming Replies** – stream LLM responses live to reduce perceived latency and let users interrupt or refine mid-response.
- [ ] **Clarification Loop** – implement follow-up prompts when plan validation fails (“Component X not found – did you mean Y?”) instead of silently skipping actions.

## 5. Arcadia Guidance & Best Practices

- [ ] **Methodology Coach** – embed Arcadia step guidance (“You’ve defined missions; next define capabilities…”) and checklists.
- [ ] **Design Reviews** – generate architecture review reports (coverage, alignment, inconsistencies) and allow the user to request targeted audits.
- [ ] **Transformation Playbooks** – codify reusable plan templates (e.g., “Break down this system function into logical functions”) that users can trigger via chat or command palette.
- [ ] **Impact Simulations** – estimate downstream effects of changes (interface updates, component migrations) using the dependency cache.

## 6. Reliability & Quality

- [ ] **Automated Test Suite** – build headless JUnit/SWTBot suites covering plan validation, EMF mutations, undo/redo, and draw.io import edge cases.
- [ ] **Chaos Testing for Plans** – fuzz-plan executor with malformed/malicious JSON to harden validation and ensure graceful recovery.
- [ ] **Performance Benchmarks** – establish baseline timings for large models (>100k elements) and optimise caching/queries as needed.
- [ ] **Documentation & Samples** – ship guided tutorials, sample conversations, and model snippets demonstrating advanced scenarios.

## 7. Deployment & Adoption

- [ ] **Installer UX** – bundle the assistant as an easy-to-install Capella feature with update-site discovery and version checks.
- [ ] **Release Notes Pipeline** – automate changelog generation so users see new capabilities at each drop.
- [ ] **Feedback Loop** – add in-IDE feedback (“Was this helpful?”) to capture qualitative insights for future iterations.

---

### Tracking Progress

Update this roadmap during each iteration review. Only tick a checkbox when the capability is demonstrably usable in Capella and has accompanying tests/docs.
