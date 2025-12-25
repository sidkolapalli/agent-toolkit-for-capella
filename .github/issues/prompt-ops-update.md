# Update System Prompt to Advertise api.ops (Operations API)

## Issue Details
- **ID**: #127
- **Title**: Update System Prompt to include `api.ops` for Transitions
- **Description**: 
  We implemented `api.ops.transition()` in PR #125, but the LLM system prompt in `CodeExecutionRouter.java` was not updated to mention this capability.
  
  As a result, when users ask to "Transition this component", the LLM hallucinates manual creation logic (`api.create("LA", ...)`) instead of using the robust internal transition engine.

- **Steps to Reproduce**:
  1. Ask: "Transition 'IFE System' to Logical."
  2. Observe response: The LLM writes code to manually create a Logical Component.
  3. Expected: LLM writes `api.ops.transition([comp], "SA2LA")`.

- **Expected Result**: 
  The System Prompt should list `api.ops` under "Available Bindings" and provide a "Recipe" or "Guidance" on when to use it (Transitions, Instantiating RPLs).

- **Actual Result**: 
  `api.ops` is a hidden feature.

- **Severity**: **Major** (Core feature is undiscoverable)
- **Priority**: **High**
- **Environment**: All

## Linking
- **Related Issues**: #113 (Implementation of Ops API)
- **Dependencies**: None.

## Acceptance Criteria
- [ ] `CodeExecutionRouter.buildCodeExecutionPrompt` includes `- api.ops - Engineering operations (transitions, reuse)`
- [ ] Guidance section includes: "Use `api.ops.transition(selection, "SA2LA")` for system-to-logical transitions."
