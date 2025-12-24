# Operations API for Engineering Transitions & Reusability

## Issue Details
- **ID**: #113
- **Title**: Implement `api.ops` for Automated Transitions and RPL Instantiation
- **Description**: 
  The current API focuses on CRUD (Create/Read/Update/Delete) operations on model elements. However, core Capella engineering workflows rely on "Transitions" (e.g., transforming System Functions to Logical Functions) and "Replicable Elements" (REC/RPL) for pattern reuse.
  
  Currently, an LLM cannot perform these transitions. It has to manually recreate elements in the next layer, breaking the traceability and synchronization logic inherent to Capella. The LLM acts as a "drawing tool" rather than an "engineering automation" tool.
  
  We need an `api.ops` module that exposes Capella's internal transition engines and library instantiation services.

- **Steps to Reproduce**:
  1. Open a ChatView session.
  2. Ask the Assistant: "Transition the 'Watch Movie' System Function to the Logical Architecture."
  3. **Observation**: The Assistant fails or attempts to create a new, unconnected Logical Function manually via `api.create()`.

- **Expected Result**: 
  The Assistant should be able to execute the standard Capella "System to Logical" transition, preserving links and traceability, just as if the user right-clicked and selected "Transition."

- **Actual Result**: 
  Assistant has no binding to the transition engine and must "hallucinate" the operation by manually creating elements, leading to disconnected models.

- **Severity**: **Major** (Blocks advanced engineering automation)
- **Priority**: **Medium** (Core feature for "Assistant" capability)
- **Environment**: All OS, Capella 6.0+

## Linking
- **Related Issues**: 
  - `traceability-api.md` (Traceability is the result of transitions)
  - `functional-chains-api.md` (Chains need to be transitioned)
- **Dependencies**: 
  - `stateful-sessions.md` (Transitions often require multi-step configuration)

## Acceptance Criteria

### 1. System to Logical Transition
- **Specific**: Implement `api.ops.transition(Collection<EObject> selection, "SA2LA")`.
- **Measurable**: Invoking this on a `SystemFunction` must result in a `LogicalFunction` appearing in the LA layer with a `realization` link back to the source.
- **Testable**: 
  ```groovy
  def saFunc = api.find("SystemFunction", "Root")
  api.ops.transition([saFunc], "SA2LA")
  assert api.realizes(saFunc).size() > 0
  ```

### 2. REC/RPL Instantiation
- **Specific**: Implement `api.ops.instantiate(String rplName, EObject targetContainer)`.
- **Measurable**: Must create a complete copy of the defined Replica in the target container.
- **Testable**: Verify that all sub-elements (parts, ports) are created and named correctly.

### 3. Diff/Merge (Optional but Planned)
- **Specific**: Implement `api.ops.updateFromLibrary()` to sync instantiated replicas with their definitions.

### 4. Headless Support
- **Specific**: The transition must work in `headless` mode (without UI dialogs popping up). This may require configuring the `Transposer` context programmatically to suppress prompts.

## Implementation Notes
- Use `org.polarsys.capella.core.transition.system.topdown.SystemTopDownTransition`.
- For RPLs, investigate `org.polarsys.capella.core.re.handlers.replicable.ReplicableHandler`.
- Ensure the transaction is committed properly (Transitions often use their own CommandStack).
