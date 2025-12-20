# Diagram Operations

Create and manipulate Capella diagrams via `api.diagram`.

## Create Diagram

```groovy
// Create a diagram
def diagram = api.diagram.create("SAB", "System Overview")

// Create under specific element
def saRoot = api.layer("SA")
def diagram = api.diagram.create("SAB", "My Diagram", saRoot)

// Safer creation with target validation
def diagram = api.diagram.createSafe("SAB", "My Diagram", saRoot)
```

## Diagram Types

| Type | Name | Layer |
|------|------|-------|
| `OAB` | Operational Activity Blank | OA |
| `OEBD` | Operational Entity Breakdown | OA |
| `SAB` | System Architecture Blank | SA |
| `SDFB` | System Data Flow Blank | SA |
| `LAB` | Logical Architecture Blank | LA |
| `LDFB` | Logical Data Flow Blank | LA |
| `PAB` | Physical Architecture Blank | PA |
| `PDFB` | Physical Data Flow Blank | PA |

## Add Elements

```groovy
def diagram = api.diagram.find("SAB")
def comp = api.find("SystemComponent", "Main Controller")

// Add element to diagram
api.diagram.add(diagram, comp)

// Safer add with fallback
api.diagram.addSafe(diagram, comp)
```

## Target Rules (important)

- **Operational Activity Breakdown** diagrams must target an **OperationalActivity**
- **Operational Entity** diagrams target **EntityPkg** (or an **Entity**)
- **Operational Capability** diagrams target **OperationalCapabilityPkg** (or a capability)
- Architecture blanks (SAB/LAB/PAB) should target the **ComponentPkg** under the layer

If diagram creation fails, use:

```groovy
api.diagram.listAvailableFormatted()
```

## Adding Functions/Activities to Diagrams

Functions and activities must be **allocated** to a component/entity before they appear on architecture diagrams (OAB, SAB, LAB, PAB).

```groovy
// For OAB: allocate activity to entity first
def activity = api.create("OA", "OperationalActivity", "Process Data")
def entity = api.find("Entity", "Aircraft")
api.allocate(activity, entity)  // Required!
api.diagram.add(oab, activity)  // Now visible inside Aircraft

// For SAB: allocate function to component first
def func = api.create("SA", "SystemFunction", "Control Flight")
def comp = api.find("SystemComponent", "Flight Controller")
api.allocate(func, comp)  // Required!
api.diagram.add(sab, func)  // Now visible inside Flight Controller
```

## Layout

```groovy
// Auto-layout the diagram
api.diagram.layout(diagram)

// Layout with style
api.diagram.layout(diagram, "hierarchical")
api.diagram.layout(diagram, "flow")
```

## Refresh

```groovy
// Refresh diagram to show model changes
api.diagram.refresh(diagram)
```

## Complete Example: OAB

```groovy
// Find existing OAB diagram
def oab = api.diagram.find("[OAB] All Operational Activities")

// Create new activity and allocate to entity
def activity = api.create("OA", "OperationalActivity", "Monitor System")
def entity = api.find("Entity", "Ground Operator")
api.allocate(activity, entity)

// Add to diagram - appears inside Ground Operator
api.diagram.add(oab, activity)

// Refresh and layout
api.diagram.refresh(oab)
api.diagram.layout(oab, "flow")

log.info("Added activity to OAB")
```

## Safer Example: Operational Activity Breakdown

```groovy
// Create or reuse an OperationalActivity
def parent = api.find("OperationalActivity", "Monitor System")
if (!parent) { parent = api.create("OA", "OperationalActivity", "Monitor System") }

// Child activity
def child = api.create("OperationalActivity", "Collect Status", parent)

// Use full descriptor + safe creation
def diagram = api.diagram.createSafe("Operational Activity Breakdown", "Monitor Breakdown", parent)
api.diagram.add(diagram, child)
api.diagram.refresh(diagram)
api.diagram.layout(diagram, "flow")
```

## Complete Example: SAB

```groovy
// Create a system overview diagram
def saRoot = api.layer("SA")
def diagram = api.diagram.create("SAB", "Drone System Overview", saRoot)

// Add components (containers - no allocation needed)
def components = api.findAll("SystemComponent")
components.each { comp ->
    api.diagram.add(diagram, comp)
}

// Add functions (must be allocated to appear inside components)
def functions = api.findAll("SystemFunction")
functions.each { func ->
    api.diagram.add(diagram, func)
}

// Layout
api.diagram.layout(diagram, "hierarchical")

log.info("Created diagram with ${components.size()} components")
```

## Tips

- **Components/Entities** can be added directly to diagrams
- **Functions/Activities** must be allocated first with `api.allocate(func, comp)`
- Use `api.diagram.refresh()` after model changes
- Use `layout()` after adding multiple elements
- Use `api.help("diagram")` to see valid diagram methods in the current build
- Use `api.diagram.addSafe()` to handle models where `add()` fails
- Diagram types must match the layer (SAB for SA, LAB for LA)
