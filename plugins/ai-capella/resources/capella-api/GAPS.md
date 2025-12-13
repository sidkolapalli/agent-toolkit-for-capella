# ARCADIA Workflow Testing - Identified Gaps

This document summarizes gaps found during comprehensive testing of the Capella API across all ARCADIA layers.

## Summary

| Layer | Issue | Severity | Status |
|-------|-------|----------|--------|
| OA | `OperationalActor` returns null | Medium | Document workaround |
| All | Diagram creation fails on layer root | High | Fixed (pending deploy) |
| PA | `PhysicalLink` returns null | Medium | Document workaround |
| Cross-layer | `ComponentRealization` fails | Medium | Needs implementation |
| Cross-layer | `FunctionRealization` works | - | Working |

---

## Detailed Findings

### 1. OA Layer: OperationalActor Type

**Issue**: `api.create("OA", "OperationalActor", "Pilot")` returns `null`

**Root Cause**: In Capella's metamodel, there is no separate `OperationalActor` class. Actors at the OA layer are represented as `Entity` objects with the `actor` property set to `true`.

**Workaround**:
```groovy
// Instead of:
def actor = api.create("OA", "OperationalActor", "Pilot")  // Returns null

// Use:
def actor = api.create("OA", "Entity", "Pilot")
api.set(actor, "actor", true)  // Mark as actor
```

**Fix Options**:
1. Update documentation to use `Entity` with actor property
2. Add type alias in ElementFactory to map `OperationalActor` → `Entity` with actor=true

---

### 2. All Layers: Diagram Creation Target

**Issue**: `api.diagram.create("SAB", saLayer)` returns `false` when passing architecture layer root

**Root Cause**: Architecture Blank diagrams (OAB, SAB, LAB, PAB) require a `ComponentPkg` or `EntityPkg` as target, not the architecture layer root.

**Example**:
```groovy
// This fails:
def saLayer = api.layer("SA")
api.diagram.create("SAB", saLayer)  // Returns false

// This works:
def saLayer = api.layer("SA")
def systemCompPkg = saLayer.eContents().find { it.eClass().name.contains("ComponentPkg") }
api.diagram.create("SAB", systemCompPkg)  // Returns true
```

**Status**: Fixed in `DiagramAPI.findDiagramTarget()` - automatically resolves to correct child package. Pending deployment.

---

### 3. PA Layer: PhysicalLink Type

**Issue**: `api.link(comp1, comp2, "PhysicalLink")` returns `null`

**Root Cause**: The `RelationFactory` does not have a handler for `PhysicalLink`. Physical connections use `ComponentExchange` at all architecture levels.

**Workaround**:
```groovy
// Instead of:
api.link(comp1, comp2, "PhysicalLink")  // Returns null

// Use:
api.link(comp1, comp2, "ComponentExchange")  // Works correctly
```

**Note**: This is actually correct behavior - Capella uses `ComponentExchange` for component-level connections across all layers. Physical links are a different concept related to physical paths/ports.

---

### 4. Cross-Layer: ComponentRealization

**Issue**: `api.link(laComp, saComp, "ComponentRealization")` fails

**Root Cause**: `ComponentRealization` requires special handling:
1. The realization must be owned by the source component (LA component)
2. It uses `targetElement` to reference the SA component
3. Standard `api.link()` doesn't handle this pattern

**Current Behavior**:
```groovy
def saComp = api.find("SystemComponent", "System")
def laComp = api.find("LogicalComponent", "Controller")

// This fails:
api.link(laComp, saComp, "ComponentRealization")  // Returns null
```

**Fix Required**: Add `ComponentRealization` handler to `RelationFactory.java`:
```java
case "ComponentRealization":
    var crFactory = CsFactory.eINSTANCE;
    var realization = crFactory.createComponentRealization();
    realization.setTargetElement((TraceableElement) target);
    ((Component) source).getOwnedComponentRealizations().add(realization);
    return realization;
```

---

### 5. Cross-Layer: FunctionRealization (Working)

**Status**: Working correctly

```groovy
def sysFunc = api.find("SystemFunction", "Process Data")
def logFunc = api.find("LogicalFunction", "Process Data")

// This works:
def realization = api.link(logFunc, sysFunc, "FunctionRealization")
log.info("Created: ${realization}")  // Shows realization object
```

---

## Documentation Updates Needed

### create.md Updates

Replace:
```markdown
### OA - Operational Analysis
- `OperationalActor` / `Entity` - Who performs activities
```

With:
```markdown
### OA - Operational Analysis
- `Entity` - Operational actors and components
  - Set `actor=true` for actors: `api.set(entity, "actor", true)`
```

### link.md Updates

Add clarification:
```markdown
### Physical Connections
Physical components use ComponentExchange (same as other layers):

```groovy
def pc1 = api.find("PhysicalComponent", "Sensor Unit")
def pc2 = api.find("PhysicalComponent", "Processing Unit")

// Use ComponentExchange for physical connections
api.link(pc1, pc2, "ComponentExchange")
```

Note: `PhysicalLink` is a different concept related to physical paths.
```

---

## Priority Fixes

### High Priority
1. **Deploy DiagramAPI fix** - Diagram creation with layer root already fixed, needs rebuild

### Medium Priority  
2. **Add ComponentRealization support** - Enable cross-layer component traceability
3. **Add OperationalActor alias** - Map to Entity with actor=true

### Low Priority (Documentation)
4. **Update create.md** - Clarify Entity vs OperationalActor
5. **Update link.md** - Clarify ComponentExchange vs PhysicalLink

---

## Test Commands Used

```groovy
// OA Layer Test
def entity = api.create("OA", "Entity", "Aircraft")
def activity = api.create("OA", "OperationalActivity", "Navigate")
api.allocate(activity, entity)

// SA Layer Test  
def sysFunc = api.create("SA", "SystemFunction", "Control Flight")
def sysComp = api.create("SA", "SystemComponent", "Flight Controller")

// LA Layer Test
def logFunc = api.create("LA", "LogicalFunction", "Control Flight")
def logComp = api.create("LA", "LogicalComponent", "Navigation Computer")

// PA Layer Test
def physFunc = api.create("PA", "PhysicalFunction", "Control Flight")
def physComp = api.create("PA", "PhysicalComponent", "Flight Computer")

// Cross-Layer Traceability
api.link(logFunc, sysFunc, "FunctionRealization")  // Works
api.link(logComp, sysComp, "ComponentRealization")  // Fails - needs fix
```
