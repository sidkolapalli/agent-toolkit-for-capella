# Action Surface Expansion Implementation Plan

This plan breaks down Section 3 of the roadmap into deliverable-sized work items. Each block should be implemented, validated inside Capella, documented, and only then marked complete on the roadmap.

## 1. Operational Analysis Automation
- [x] Extend `ToolRouter` schema and prompt to support new action types:
  - `create_mission`, `create_operational_activity`, `link_oa_to_sa`, `create_operational_exchange`.
- [x] Implement EMF helpers in a new `OperationalActions` utility class:
  - Mission creation under OA root, default name + description fields.
  - Activity creation with optional hierarchy and allocation to System Analysis capabilities.
  - Exchange creation between operational actors.
- [x] Update `PlanEditorDialog` diff preview to recognise the new action categories.
- [x] Add UI affordances (buttons or command palette entries) to trigger predefined operational templates.
- [x] Telemetry: log counts of operational actions, validation errors.
- [x] Tests/validation: run `mvn -f plugins clean verify`, manually create OA artefacts inside Capella.

Status: Completed – OA plan actions now include sandbox validation and one-click mission/activity/exchange templates with telemetry coverage.

## 2. System Analysis Modelling Helpers
- [x] New actions: `create_system_component`, `create_system_function`, `create_interface`, `allocate_capability`.
- [x] Utility class `SystemActions` encapsulating component/function hierarchy creation and interface wiring.
- [x] Diagram integrations: ensure LAB or capability diagrams update when actions run. *(covered by existing DiagramCommands refresh when templates invoke creation)*
- [x] Extend dry-run to cover system allocations and interface validation (e.g., missing actors, duplicates).
- [x] Update telemetry categories and Plan Editor messaging.

Status: Completed – System templates now expose guided component/function/interface creation with dry-run validation and telemetry counts.

## 3. Logical ↔ Physical Transformations
- [x] Actions: `propagate_logical_to_physical`, `sync_physical_from_logical`, `trace_component_mapping`.
- [x] Transformation service handling EMF copy, trace links, undo/redo safety via `SessionSafeguards`.
- [x] Plan dry-run: simulate transformations and flag conflicts (existing artefacts, missing mappings).
- [x] UI: add toggle/confirmation for destructive sync operations, summarise affected components.
- [x] Documentation: provide guidance on transformation assumptions and conflict resolution.

Status: Completed – logical to physical propagation, sync, and trace helpers are live with dry-run previews and UI safeguards.

## 4. Functional Chain Authoring
- Actions: `create_functional_chain`, `extend_chain`, `sequence_exchange`, `attach_scenario`.
- Helper class manipulating FunctionalChain elements, ordering exchanges, linking scenarios/state machines.
- Dry-run enhancements to validate chain coherence (cycle detection, missing exchanges).
- Plan Editor diff: show chain names and affected exchanges.

## 5. Requirement Handling
- Actions: `create_requirement`, `link_requirement`, `check_requirement_coverage`, `generate_verification`.
- Requirement utilities leveraging Capella requirements model, ensuring trace links and coverage metrics.
- UI: add quick command or prompt templates for requirement coverage reports.
- Telemetry: log coverage check results and error situations.

## 6. Mass Edit & Allocation Commands
- Actions: `batch_rename`, `batch_allocate`, `batch_map`, `batch_delete` with filter criteria.
- Implement batch executor with preview (apply to subset). Extend Plan Editor to show per-item toggles inside one plan row.
- Safety: ensure dry-run highlights harmful operations, require explicit user confirmation.

## 7. Cross-Cutting Tasks
- Prompt schema & documentation updates after each action block.
- README additions: describe new capabilities, usage examples, and UI elements.
- Roadmap maintenance: tick checkboxes only after validation.
- Telemetry expansion: capture action category, success/failure, duration for analytics.
- Regression testing: run Tycho build, spot-check transformations in Capella workspace, capture manual test notes.

## Execution Notes
- Work through sections sequentially; only start the next block once the previous is merged and validated.
- Keep changesets focused (one feature family per commit/PR) for easier review.
- Maintain undo/redo guarantees via `SessionSafeguards` and update dry-run output whenever new actions are introduced.
- Re-run `ModelSummary` invalidation logic when new EMF utilities mutate the model so analytics stay accurate.
