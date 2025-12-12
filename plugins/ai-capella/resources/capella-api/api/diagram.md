# Diagram Operations

Create and manipulate Capella diagrams via `api.diagram`.

## Create Diagram

```groovy
// Create a diagram
def diagram = api.diagram.create("SAB", "System Overview")

// Create under specific element
def saRoot = api.layer("SA")
def diagram = api.diagram.create("SAB", "My Diagram", saRoot)
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
def diagram = api.diagram.create("SAB", "System View")
def comp = api.find("SystemComponent", "Main Controller")
def func = api.find("SystemFunction", "Process Data")

// Add elements to diagram
api.diagram.add(diagram, comp)
api.diagram.add(diagram, func)
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

## Complete Example

```groovy
// Create a system overview diagram
def saRoot = api.layer("SA")
def diagram = api.diagram.create("SAB", "Drone System Overview", saRoot)

// Add all system components
def components = api.findAll("SystemComponent")
components.each { comp ->
    api.diagram.add(diagram, comp)
}

// Add key functions
def functions = api.findAll("SystemFunction")
functions.take(10).each { func ->
    api.diagram.add(diagram, func)
}

// Layout
api.diagram.layout(diagram, "hierarchical")

log.info("Created diagram with ${components.size()} components")
```

## Tips

- Diagrams are automatically opened after creation
- Use `layout()` after adding multiple elements
- Diagram types must match the layer (SAB for SA, LAB for LA)
