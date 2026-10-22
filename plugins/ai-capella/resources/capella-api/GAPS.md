# API Gaps and Known Limitations

This document tracks known limitations in the Capella API.

> **Last Updated**: 2026-10-09
> **Status**: Most cross-layer issues resolved in Issue #36, diagram errors improved in Issue #59

---

## Summary

| Feature | Status | Notes |
|---------|--------|-------|
| OperationalActor creation | ✅ Fixed | Auto-maps to Entity with `actor=true` |
| ComponentRealization | ✅ Fixed | Works in RelationFactory |
| FunctionRealization | ✅ Working | Typed endpoints, correct function containment and architecture checks |
| EPBS configuration items | ✅ Supported | Root/nested creation and CI→PA PhysicalArtifactRealization |
| Architecture Blank diagrams | ✅ Fixed | SAB, LAB, PAB work |
| Data Flow Blank diagrams | ⚠️ Improved | Better errors, fuzzy matching (Issue #59) |
| OAB diagram | ⚠️ Viewpoint | Requires viewpoint activation |
| Capability involvement/realization | Supported | `api.arcadia` native typed relationships |
| Linear functional chain | Supported | Ordered leaf functions and actual exchanges; branching requires native services |
| Operational interaction scenario | Supported | Activity roles, native messages/events/ends; richer fragments require native services |
| Physical deployment/topology | Supported | Explicit BEHAVIOR/NODE Parts and actual PhysicalLink ends |
| Functional transport | Supported | Native CE→FE and component/function port allocations |
| Interface realization | Supported | Adjacent-layer FE/FLOW CE realization and matching endpoint ports, with native ownership and direction checks |

---

## Working Features

### Cross-Layer Traceability

Function/component realization links require the lower-level source first (SA→OA, LA→SA, PA→LA), with both endpoints in the active session. EPBS configuration items use `PhysicalArtifactRealization` to realize PA physical components:

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

### 1. Data Flow Blank Diagrams (Issue #57, #59)

SDFB, LDFB, and PDFB diagram types may fail if:
- The Sirius descriptor name doesn't exactly match our mapping
- The required viewpoint isn't activated in Capella

**Improved in Issue #59:**
- Better error messages showing available descriptors
- Fuzzy matching (case-insensitive, partial match)
- `api.diagram.listAvailable(layer)` to discover available types
- `api.diagram.listAvailableFormatted()` for detailed descriptions

**Troubleshooting:**
```groovy
// Check what's actually available
log.info(api.diagram.listAvailableFormatted("SA"))

// Try creating with full descriptor name if abbreviation fails
api.diagram.create("System Data Flow Blank", "My SDFB", saLayer)
```

**Workaround if still failing**: Use Architecture Blank diagrams (SAB, LAB, PAB) which reliably work, or create Data Flow diagrams manually in Capella UI.

### 2. OAB Diagram Viewpoint

Operational Entity Blank (OAB) requires viewpoint activation:

```groovy
def oaLayer = api.layer("OA")
api.diagram.create("OAB", "Operational View", oaLayer)  
// ❌ "Diagram description not found in selected viewpoints"
```

**Workaround**: Ensure "Operational Analysis" viewpoint is activated in Capella before creating OAB diagrams.

### 3. PhysicalLink vs ComponentExchange

`PhysicalLink` is not a supported `api.link()` relation type. For behavioral interfaces between PA components, use `ComponentExchange`:

```groovy
// Wrong
api.link(pc1, pc2, "PhysicalLink")  // ❌ Unsupported relation; throws an error

// Correct
api.link(pc1, pc2, "ComponentExchange")  // ✅ Works
```

**Note**: Use `api.arcadia.physicalLink(...)` for actual NODE topology and `api.arcadia.deploy(...)` for BEHAVIOR→NODE deployment. Component exchanges do not create those structures. Use `api.arcadia.allocateExchange(...)` for native transport/port allocations and the typed capability involvement helpers. See `api/arcadia.md` for ownership and direction rules.

---

## Error Messages

The API now provides descriptive error messages for common issues:

| Error | Meaning |
|-------|---------|
| "No active Capella session" | Open a model first |
| "Element type cannot be null" | Provide a valid type name |
| "Architecture layer 'XX' not found" | Use OA, SA, LA, PA, or EPBS |
| "Property 'X' not found... Available: [...]" | Check property name spelling |
| "Failed to create 'X'... Common types: [...]" | Check type name spelling |

---

## Reporting New Gaps

If you find an API operation that fails silently or behaves unexpectedly:

1. Test with the dev server: `curl -X POST http://localhost:8765/execute -H "Authorization: Bearer $(cat ~/.capella-ai/devserver-8765.token)" -d 'your code'`
2. Check if error message is helpful
3. If not, file an issue in the project's repository
