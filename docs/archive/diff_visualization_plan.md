# Diff Visualisation Implementation Plan

Goal: give users a richer preview of the AI plan before execution, including per-element diffs and explicit undo entry points.

## Current State
- `PlanEditorDialog` lists plan items and shows a dry-run summary line per action.
- `PlanDryRun` returns success/failure and issues but not a detailed element diff.
- Users rely on Undo menu manually; no direct affordance in the dialog.

## Requirements
1. **Change Breakdown**
   - Show created, updated, and deleted elements grouped by layer/action.
   - Display key attribute changes for updates (name, allocation, mapping, etc.).
   - Link entries back to the model element (UUID) for potential future interactions.
2. **Visual Cues**
   - Use colour/iconography within SWT (e.g., green add, blue update, red delete) while keeping accessibility.
   - Provide a compact summary banner (“Creates: 3, Updates: 2, Deletes: 1”).
3. **Undo Guidance**
   - Highlight that the entire plan runs in one transaction with explicit `Undo AI Plan` button that triggers `Edit → Undo` programmatically when possible.
   - Fall back to existing tip text if undo cannot be invoked.
4. **Dry-Run Integration**
   - Extend `PlanDryRun.Outcome` to surface structural diffs (attribute name/value pairs) when available.
   - Preserve warning messages for display alongside diffs.
5. **Performance & Safety**
   - Avoid loading full EMF objects on the UI thread post dry-run; serialize diff data during the sandbox evaluation.
   - Cap list length with expandable sections for large plans.

## Work Items
1. Extend `PlanDryRun` to gather diff metadata per action (created element label, old/new attributes, affected UUIDs).
2. Update `ToolRouter.PlanItem` to carry optional diff payload for the UI layer.
3. Redesign the Plan Editor diff section:
   - Replace plain `Text` widget with a scrollable composite containing styled labels.
   - Group entries under Creates/Updates/Deletes.
   - Add Undo button tied to the active Capella `IUndoContext` if available.
4. Telemetry: log when diff view is expanded and when undo is triggered.
5. Documentation: update README and ROADMAP after implementation.

## Open Questions
- Should we show before/after diagrams or links? (scoped out for now.)
- Do we need per-field opt-out toggles for massive updates?
- How to handle actions that touch multiple elements (mass edits) without overwhelming the user? Possibly collapse with counts and expandable details.

## Definition of Done
- Plan Editor displays grouped diff visuals with attribute deltas.
- Undo button works (or gracefully falls back) immediately after plan execution.
- Dry-run warning/errors remain visible.
- README and roadmap mention the capability.
- Build passes (`mvn -f plugins clean verify`).
