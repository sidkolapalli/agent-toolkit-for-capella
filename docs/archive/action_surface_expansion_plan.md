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
- [x] Actions: `create_functional_chain`, `extend_chain`, `sequence_exchange`, `attach_scenario`.
- [x] Helper class manipulating FunctionalChain elements, ordering exchanges, linking scenarios/state machines.
- [x] Dry-run enhancements to validate chain coherence (missing chain/function/exchange references).
- [x] Plan Editor diff: show chain names and affected exchanges.

Status: Completed – functional chain actions now ensure chains exist, functions/exchanges resolve, and scenario attachments are previewed in dry-run summaries.

## 5. Requirement Handling
- [x] Actions: `create_requirement`, `link_requirement`, `record_requirement_coverage`.
- [x] Requirement utilities leveraging Capella requirements model, ensuring trace links and coverage metrics.
- [x] UI: add quick buttons for requirement creation, linking, and coverage capture.
- [x] Telemetry/plan logging: new action types recorded through existing plan metrics.

Status: Completed – requirement templates now handle creation, linking, and coverage logging with dry-run validation before execution.

## 6. Mass Edit & Allocation Commands
- [x] Actions: `batch_rename`, `batch_reparent`, `batch_allocate`, `batch_map`, `batch_delete`.
- [x] Implemented batch helpers with Plan Editor summaries and dry-run validation for missing elements.
- [x] Safety: sandbox highlights missing or conflicting entries before execution; UI templates prompt for confirmation.

Status: Completed – mass edit templates and TOOL_PLAN actions now support renames, reparenting, allocations, mappings, and deletes with dry-run feedback.

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
