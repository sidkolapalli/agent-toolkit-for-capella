# Stateful Groovy Sessions (Session Context Persistence)

## Issue Reference
- **GitHub Issue**: N/A (New)
- **Priority**: Tier 1 - Core Infrastructure (Must-Have)
- **Estimated Effort**: 3 days

## Anthropic Pattern Alignment
- **Primary Pattern**: Stateful Notebook Execution
- **Blog Reference**: https://www.anthropic.com/engineering/code-execution-with-mcp (Section: "Code enables complex workflows")
- **How it aligns**: Allows the LLM to treat the chat session as a continuous "Jupyter Notebook" for systems engineering. Variables, partial results, and definitions persist between messages, enabling complex, multi-turn reasoning chains without forcing the model to re-fetch data every time.

## Problem Statement

The current `GroovyExecutor` is **stateless**. Every time the user sends a message, a brand new `GroovyShell` and `Binding` are created.

**The Consequence:**
If the LLM performs a search in Turn 1:
```groovy
// Turn 1
def components = api.findAll("LogicalComponent")
```

And tries to act on it in Turn 2:
```groovy
// Turn 2
api.delete(components[0])
```

It fails with `MissingPropertyException: No such property: components`. This forces the LLM to re-write the search logic in every single message, increasing token usage, latency, and the chance of logic errors. It breaks the illusion of an "Assistant" that remembers context.

## Current State

**Existing Code**:
- `plugins/ai-capella/src/org/polarsys/capella/ai/scripting/GroovyExecutor.java` (lines 90-110)
  - `createBinding()` is called inside `execute()`.
  - New `GroovyShell` created for every execution.
  - No mechanism to pass an existing `Binding` or `Shell` instance.

**What Works**:
- Single-turn execution works perfectly.
- "One-shot" tasks are safe and isolated.

**What's Missing**:
- **Session Cache**: A registry mapping `SessionID` -> `GroovyShell/Binding`.
- **Lifecycle Management**: Logic to expire sessions after inactivity (to prevent memory leaks).
- **Reset Capability**: An `api.reset()` method to clear variables when the context gets polluted.

## Proposed Solution

### Architecture

We will implement a **Sticky Session** model.

1.  **SessionManager**: A static registry in `CodeExecutionRouter` that holds a `Map<String, Binding>`.
2.  **Binding Reuse**: Instead of creating a new `Binding` each time, we check if one exists for the current user session.
3.  **Thread Safety**: Since Capella access must happen on the UI thread (or via `SessionSafeguards`), but the `Binding` maps variable names to Objects, we must ensure we don't leak `EObjects` from closed transactions (though EMF objects are generally safe to hold if the resource doesn't close).

### API Design

```groovy
// Turn 1
def target = api.find("SystemFunction", "Root")

// Turn 2
log.info(target.name) // Works! "target" is remembered.

// Manual Reset
api.reset() // Clears all user-defined variables
```

### Implementation Approach

**Phase 1: Session Registry** (1 day)
Modify `GroovyExecutor` to accept an optional `sessionId`.
- If `sessionId` is provided, look up `Binding` in a `ConcurrentHashMap`.
- If not found, create new `Binding` and store it.
- **Critical**: The `api` variable (CapellaAPI) must be refreshed if the underlying Eclipse `Session` changes (e.g., user closed/reopened project).

**Phase 2: Router Integration** (1 day)
Update `CodeExecutionRouter.execute(...)` to:
1.  Accept a `conversationId` (from the ChatView or LSP client).
2.  Pass this ID to `GroovyExecutor`.

**Phase 3: Lifecycle & Cleanup** (0.5 days)
- Implement a `cleanUp()` job that removes Bindings not accessed in > 1 hour.
- Implement `api.reset()` to explicitly clear the binding.

## Acceptance Criteria

- [ ] Variable definition in Message 1 is accessible in Message 2.
- [ ] `api.reset()` successfully clears user variables but keeps system bindings (`api`, `log`).
- [ ] Closing the Capella Project invalidates the session (prevents access to dead objects).
- [ ] Memory usage does not grow indefinitely (stale sessions are purged).

## Files to Modify

1.  **Primary**: `plugins/ai-capella/src/org/polarsys/capella/ai/scripting/GroovyExecutor.java`
    - Add `SessionContext` inner class.
    - Add `execute(..., String sessionId)` overload.
2.  **Primary**: `plugins/ai-capella/src/org/polarsys/capella/ai/scripting/CodeExecutionRouter.java`
    - Manage the session ID mapping.
3.  **New**: `plugins/ai-capella/src/org/polarsys/capella/ai/scripting/SessionManager.java` (Optional, if logic is complex).

## Dependencies
- None.

## Labels
`enhancement`, `tier-1-infrastructure`, `state-management`, `assistant-experience`
