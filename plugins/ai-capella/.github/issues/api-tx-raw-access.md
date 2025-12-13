# Issue #53: Add `api.tx {}` for Dynamic Raw API Access

## Problem

The current wrapper APIs (`api.create`, `api.link`, `api.diagram`) cover ~80% of use cases but fail for edge cases:

- `api.create("SA", "Requirement", "X")` returns null
- `api.create("SA", "Constraint", "X")` returns null  
- `api.link(capability, function, "involvement")` fails
- Many diagram types (SDFB, OEBD, etc.) fail to create

Every gap requires us to add more wrapper code. This doesn't scale - Capella has:
- 200+ element types
- 50+ relationship types
- 30+ diagram types

## Solution: Follow Anthropic's Code Execution Pattern

Reference: [Anthropic's Code Execution with MCP](https://www.anthropic.com/engineering/code-execution-with-mcp)

Instead of wrapping everything, expose raw API access with transaction safety:

```groovy
api.tx {
    def factory = org.polarsys.capella.core.data.requirement.RequirementFactory.eINSTANCE
    def req = factory.createSystemUserRequirement()
    req.name = "REQ-GPS-001"
    req.requirementId = "REQ-001"
    
    def saLayer = api.layer("SA")
    // Find requirements package and add
    saLayer.ownedRequirementPkgs[0].ownedRequirements.add(req)
    req
}
```

## Implementation

### 1. Add `tx(Closure)` to CapellaAPI.java

```java
/**
 * Execute raw Capella API code within a safe transaction.
 * Use this when standard API methods don't support your use case.
 * 
 * <p>Example:
 * <pre>{@code
 * api.tx {
 *     def factory = CtxFactory.eINSTANCE
 *     def func = factory.createSystemFunction()
 *     func.name = "My Function"
 *     container.ownedFunctions.add(func)
 *     func
 * }
 * }</pre>
 *
 * @param closure the code to execute
 * @return the result of the closure
 */
public Object tx(Closure<?> closure) {
    if (session == null) {
        return null;
    }
    return SessionSafeguards.runWrite(session, "Raw API execution", () -> {
        try {
            Object result = closure.call();
            invalidate();
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Raw API execution failed: " + e.getMessage(), e);
        }
    });
}
```

### 2. Update SKILL.md

Add minimal documentation:

```markdown
## Advanced: Raw API Access

When standard API doesn't cover your use case, access Capella internals directly:

```groovy
api.tx {
    // Your raw Capella code here - safely wrapped in a transaction
    def factory = org.polarsys.capella.core.data.ctx.CtxFactory.eINSTANCE
    def func = factory.createSystemFunction()
    func.name = "My Function"
    
    def container = api.layer("SA").ownedFunctionPkg
    container.ownedSystemFunctions.add(func)
    func
}
```

### Common Factories

| Layer | Factory |
|-------|---------|
| OA | `org.polarsys.capella.core.data.oa.OaFactory.eINSTANCE` |
| SA | `org.polarsys.capella.core.data.ctx.CtxFactory.eINSTANCE` |
| LA | `org.polarsys.capella.core.data.la.LaFactory.eINSTANCE` |
| PA | `org.polarsys.capella.core.data.pa.PaFactory.eINSTANCE` |
| Requirements | `org.polarsys.capella.core.data.requirement.RequirementFactory.eINSTANCE` |
| Information | `org.polarsys.capella.core.data.information.InformationFactory.eINSTANCE` |
| Diagrams | `org.eclipse.sirius.business.api.dialect.DialectManager.INSTANCE` |
```

## Acceptance Criteria

- [ ] `api.tx { }` method added to CapellaAPI
- [ ] Closure executes within EMF transaction
- [ ] Model invalidation happens after successful execution
- [ ] Errors are caught and reported clearly
- [ ] SKILL.md documents raw API access pattern
- [ ] LLM can create Requirements using raw API
- [ ] LLM can create any element type dynamically

## Why This Is Better

| Approach | Pros | Cons |
|----------|------|------|
| More wrappers | Simple for LLM | Never complete, maintenance burden |
| Raw access | Truly dynamic, zero maintenance | LLM needs to learn patterns |
| **api.tx {} (hybrid)** | **Safe + dynamic, minimal docs** | **None** |

## Labels

`enhancement`, `phase-4`, `anthropic-pattern`
