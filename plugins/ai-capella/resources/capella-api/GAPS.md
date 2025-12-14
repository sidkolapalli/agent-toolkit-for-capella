# API Gaps and Known Limitations

This document tracks known limitations in the Capella API.

> **Last Updated**: 2024-12-13
> **Status**: Most cross-layer issues resolved in Issue #36

---

## Summary

| Feature | Status | Notes |
|---------|--------|-------|
| OperationalActor creation | ✅ Fixed | Auto-maps to Entity with `actor=true` |
| ComponentRealization | ✅ Fixed | Works in RelationFactory |
| FunctionRealization | ✅ Working | Always worked |
| Architecture Blank diagrams | ✅ Fixed | SAB, LAB, PAB work |
| Data Flow Blank diagrams | ❌ Not Working | SDFB, LDFB, PDFB fail (Issue #57) |
| OAB diagram | ⚠️ Viewpoint | Requires viewpoint activation |

---

## Working Features

### Cross-Layer Traceability

All cross-layer links now work correctly:

```groovy
// Function Realization (LA → SA)
def sysFunc = api.find("SystemFunction", "Process Data")
def logFunc = api.find("LogicalFunction", "Process Data")
api.link(logFunc, sysFunc, "FunctionRealization")  // ✅ Works

// Component Realization (LA → SA)
def sysComp = api.find("SystemComponent", "Controller")
def logComp = api.find("LogicalComponent", "Controller")
api.link(logComp, sysComp, "ComponentRealization")  // ✅ Works
```

### Actor Types

All actor types work correctly:

```groovy
// OA Layer - OperationalActor
def actor = api.create("OA", "OperationalActor", "Pilot")  // ✅ Creates Entity with actor=true

// SA Layer - SystemActor
def sysActor = api.create("SA", "SystemActor", "User")  // ✅ Creates SystemComponent with actor=true
```

### Architecture Blank Diagrams

```groovy
def saLayer = api.layer("SA")
api.diagram.create("SAB", "Overview", saLayer)  // ✅ Works

def laLayer = api.layer("LA")
api.diagram.create("LAB", "Logical View", laLayer)  // ✅ Works

def paLayer = api.layer("PA")
api.diagram.create("PAB", "Physical View", paLayer)  // ✅ Works
```

---

## Known Limitations

### 1. Data Flow Blank Diagrams (Issue #57)

SDFB, LDFB, and PDFB diagram types return null:

```groovy
def saLayer = api.layer("SA")
api.diagram.create("SDFB", "Data Flow", saLayer)  // ❌ Returns null
```

**Workaround**: Use Architecture Blank diagrams instead, or create Data Flow diagrams manually in Capella UI.

### 2. OAB Diagram Viewpoint

Operational Activity Blank requires viewpoint activation:

```groovy
def oaLayer = api.layer("OA")
api.diagram.create("OAB", "Operational View", oaLayer)  
// ❌ "Diagram description not found in selected viewpoints"
```

**Workaround**: Ensure "Operational Analysis" viewpoint is activated in Capella before creating OAB diagrams.

### 3. PhysicalLink vs ComponentExchange

`PhysicalLink` is not a valid relation type. Physical components use `ComponentExchange`:

```groovy
// Wrong
api.link(pc1, pc2, "PhysicalLink")  // ❌ Returns null

// Correct
api.link(pc1, pc2, "ComponentExchange")  // ✅ Works
```

**Note**: `PhysicalLink` in Capella refers to physical paths/cables, not component connections.

---

## Error Messages

The API now provides descriptive error messages for common issues:

| Error | Meaning |
|-------|---------|
| "No active Capella session" | Open a model first |
| "Element type cannot be null" | Provide a valid type name |
| "Architecture layer 'XX' not found" | Use OA, SA, LA, or PA |
| "Property 'X' not found... Available: [...]" | Check property name spelling |
| "Failed to create 'X'... Common types: [...]" | Check type name spelling |

---

## Reporting New Gaps

If you find an API operation that fails silently or behaves unexpectedly:

1. Test with the dev server: `curl -X POST http://localhost:8765/execute -d 'your code'`
2. Check if error message is helpful
3. If not, file an issue at: https://github.com/akon2997/capella-ai/issues
