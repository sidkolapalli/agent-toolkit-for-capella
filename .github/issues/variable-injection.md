# Inject Active Groovy Variables into System Prompt

## Issue Details
- **ID**: #130
- **Title**: Inject Active Groovy Variables into System Prompt for Stateful Context
- **Description**: 
  We implemented Stateful Sessions (#110), so Groovy variables persist between chat turns.
  However, the LLM **does not know this**. It has no visibility into the current `Binding`.
  
  As a result, if the user says "Rename it", the LLM tries to `api.find()` the element again (often failing or guessing the wrong type) instead of using the variable `myComponent` it created in the previous turn.

- **Steps to Reproduce**:
  1. Turn 1: "Create a component." -> `def c = api.create(...)`
  2. Turn 2: "Rename it." -> LLM generates `api.find(...)` instead of `api.rename(c, ...)` because it doesn't know `c` exists.

- **Expected Result**: 
  The System Prompt (or a "Context" block in the user message) should list available variables:
  ```
  [Context]
  Active variables:
  - c: SystemComponent "MyComp"
  - d: SystemArchitectureBlank "Overview"
  ```

- **Actual Result**: 
  LLM is blind to the session state.

- **Severity**: **Major** (Undermines the value of stateful sessions)
- **Priority**: **High**
- **Environment**: All

## Linking
- **Related Issues**: #110 (Stateful Sessions)
- **Dependencies**: None.

## Acceptance Criteria
- [ ] `CodeExecutionRouter` extracts variable names and types from the `Binding` before building the prompt.
- [ ] Only variables of interest (EObject, DRepresentation) are listed (filter out `api`, `log`, etc.).
- [ ] Prompt includes a section "## Current Session State".
