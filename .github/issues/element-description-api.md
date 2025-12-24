# Semantic Element Description (`api.describe`)

## Issue Reference
- **GitHub Issue**: N/A (New)
- **Priority**: Tier 2 - Hallucination Reduction (Should-Have)
- **Estimated Effort**: 1-2 days

## Anthropic Pattern Alignment
- **Primary Pattern**: RAG (Retrieval-Augmented Generation) "Vision"
- **How it aligns**: Large models hallucinate structure when they can't "see" it. Providing a text-based graph representation of a model element allows the LLM to ground its reasoning in the actual model topology before making changes.

## Problem Statement

When an LLM wants to modify a component, it often guesses its current state.
*   "Does 'FlightControl' already have an input port?"
*   "Is it linked to 'Sensors'?"

Currently, the LLM has to call `api.summary()` (which dumps the *entire* model, wasting tokens) or write complex traversal scripts just to look at one object.

**Impact**: High rate of "Already exists" errors or "Target not found" errors because the model blindly assumes the state.

## Current State

**Existing Code**:
- `CapellaAPI.summary()` -> Calls `ModelSummary.summarize(session)`.
- This returns a massive JSON/YAML string of the whole model.
- It is too coarse-grained for focused editing.

## Proposed Solution

### API Design

Add a focused `describe` method:

```groovy
// Get a "Vision" of the element
def view = api.describe(component)
log.info(view)
```

**Output format (Text-Graph):**
```text
[LogicalComponent] FlightControl
  Path: /LA/LogicalComponentPkg/FlightControl
  -- Properties --
  isActor: false
  isHuman: false
  -- Relations --
  [IN]  FIP "SensorData" (from Sensors.FOP "Out")
  [OUT] FOP "Command" (to Actuators.FIP "In")
  -- Allocation --
  Allocates: [LogicalFunction] Compute Path
  Allocates: [LogicalFunction] Stabilize
```

### Implementation Approach

1.  **`DescriptionBuilder` Class:** A helper that takes an `EObject` and walks its immediate neighbors (1-hop).
2.  **Smart Filtering:**
    - For `Component`: Show ports, exchanges, allocated functions.
    - For `Function`: Show ports, exchanges, allocation target.
    - For `Exchange`: Show source, target, functional exchange items.
3.  **Integration:** Expose via `CapellaAPI.describe(EObject)`.

## Acceptance Criteria

- [ ] `api.describe(element)` returns a concise string representation.
- [ ] Output includes Name, Type, Path (semantic browser path).
- [ ] Output includes 1-hop relationships (Links, Allocations).
- [ ] Format is token-efficient (dense text, not verbose XML).

## Files to Modify

1.  `plugins/ai-capella/src/org/polarsys/capella/ai/scripting/CapellaAPI.java`
2.  `plugins/ai-capella/src/org/polarsys/capella/ai/model/ModelSummary.java` (Add `describeElement` logic here).

## Labels
`enhancement`, `rag`, `context-awareness`, `hallucination-fix`
