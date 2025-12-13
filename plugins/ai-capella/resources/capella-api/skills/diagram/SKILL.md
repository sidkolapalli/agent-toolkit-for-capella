# Diagram Creation Skill

Create and populate Capella diagrams.

## Diagram Types

| Abbrev | Full Name | Layer |
|--------|-----------|-------|
| OAB | Operational Activity Blank | OA |
| SAB | System Architecture Blank | SA |
| SDFB | System Data Flow Blank | SA |
| LAB | Logical Architecture Blank | LA |
| LDFB | Logical Data Flow Blank | LA |
| PAB | Physical Architecture Blank | PA |
| PDFB | Physical Data Flow Blank | PA |
| CDB | Class Diagram Blank | - |

## Examples

<example>
User: Create a System Architecture Blank diagram
Correct code:
```groovy
def saLayer = api.layer("SA")
def diagram = api.diagram.create("SAB", "System Overview", saLayer)
log.info("Created diagram: " + diagram.name)
```
</example>

<example>
User: Create an LAB diagram for the logical architecture
Correct code:
```groovy
def laLayer = api.layer("LA")
def diagram = api.diagram.create("LAB", "Logical View", laLayer)
log.info("Created diagram: " + diagram.name)
```
</example>

<example>
User: Create a diagram and add all system components to it
Correct code:
```groovy
def saLayer = api.layer("SA")
def diagram = api.diagram.create("SAB", "All Components", saLayer)

// Add all components to diagram
def components = api.findAll("SystemComponent")
components.each { comp ->
    api.diagram.add(diagram, comp)
}

// Layout for readability
api.diagram.layout(diagram, "hierarchical")

log.info("Created diagram with ${components.size()} components")
```
</example>

<example>
User: Create a diagram showing a specific component and its functions
Correct code:
```groovy
def comp = api.find("SystemComponent", "Flight Controller")
def diagram = api.diagram.create("SAB", "Flight Controller View", comp)

// Add the component
api.diagram.add(diagram, comp)

// Add allocated functions (must be allocated first)
def functions = api.findAll("SystemFunction").findAll { func ->
    api.isAllocatedTo(func, comp)
}
functions.each { func ->
    api.diagram.add(diagram, func)
}

api.diagram.layout(diagram)
log.info("Added ${functions.size()} functions to diagram")
```
</example>

<example>
User: Add functions to an existing diagram
Correct code:
```groovy
// Find existing diagram
def diagram = api.diagram.find("System Overview")

// Find functions to add
def funcs = api.findAll("SystemFunction")
funcs.each { func ->
    api.diagram.add(diagram, func)
}

api.diagram.refresh(diagram)
api.diagram.layout(diagram, "flow")

log.info("Added ${funcs.size()} functions")
```
</example>

## Important Notes

1. **Target element**: The third parameter to `create()` is the semantic target element
2. **Functions need allocation**: Functions/activities only appear inside components if allocated with `api.allocate(func, comp)`
3. **Layout after adding**: Always call `layout()` after adding multiple elements
4. **Refresh after changes**: Call `refresh()` to sync diagram with model changes

## Error Handling

The `api.diagram.create()` method throws descriptive exceptions:
- `IllegalStateException` - No active session
- `IllegalArgumentException` - Invalid diagram type or null target

```groovy
try {
    def diagram = api.diagram.create("SAB", "My Diagram", saLayer)
} catch (IllegalArgumentException e) {
    log.error("Failed to create diagram: " + e.message)
}
```
