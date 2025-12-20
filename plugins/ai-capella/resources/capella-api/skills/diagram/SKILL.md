---
name: Diagram Creation
summary: Create and populate Capella diagrams.
keywords: [diagram, sab, lab, pab, layout, view]
layer: [OA, SA, LA, PA]
when_to_use: User asks to create or modify diagrams.
difficulty: beginner
---

# Diagram Creation Skill

Create and populate Capella diagrams.

## Diagram Types

| Abbrev | Full Name | Layer |
|--------|-----------|-------|
| OAB | Operational Activity Blank | OA |
| OES | Operational Entity Blank | OA |
| OCB | Operational Capabilities Blank | OA |
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
def diagram = api.diagram.createSafe("SAB", "System Overview", saLayer)
log.info("Created diagram: " + diagram.name)
```
</example>

<example>
User: Create an LAB diagram for the logical architecture
Correct code:
```groovy
def laLayer = api.layer("LA")
def diagram = api.diagram.createSafe("LAB", "Logical View", laLayer)
log.info("Created diagram: " + diagram.name)
```
</example>

<example>
User: Create a diagram and add all system components to it
Correct code:
```groovy
def saLayer = api.layer("SA")
def diagram = api.diagram.createSafe("SAB", "All Components", saLayer)

// Add all components to diagram
def components = api.findAll("SystemComponent")
components.each { comp ->
    api.diagram.addSafe(diagram, comp)
}

// Layout for readability
api.diagram.layout(diagram, "layered")

log.info("Created diagram with ${components.size()} components")
```
</example>

<example>
User: Create a diagram showing a specific component and its functions
Correct code:
```groovy
def comp = api.find("SystemComponent", "Flight Controller")
def diagram = api.diagram.createSafe("SAB", "Flight Controller View", comp)

// Add the component
api.diagram.addSafe(diagram, comp)

// Add allocated functions (must be allocated first)
def functions = api.findAll("SystemFunction").findAll { func ->
    api.isAllocatedTo(func, comp)
}
functions.each { func ->
    api.diagram.addSafe(diagram, func)
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
    api.diagram.addSafe(diagram, func)
}

api.diagram.refresh(diagram)
api.diagram.layout(diagram, "flow")

log.info("Added ${funcs.size()} functions")
```
</example>

## Important Notes

1. **Prefer createSafe**: `createSafe()` validates descriptors and targets before creation
2. **Prefer addSafe**: `addSafe()` falls back to UUID reveal when `add()` fails
3. **Target element**: The third parameter to `create()`/`createSafe()` is the semantic target element
2. **Functions need allocation**: Functions/activities only appear inside components if allocated with `api.allocate(func, comp)`
3. **Layout after adding**: Always call `layout()` after adding multiple elements
4. **Refresh after changes**: Call `refresh()` to sync diagram with model changes
5. **OABD target**: Operational Activity Breakdown diagrams must target an **OperationalActivity**
6. **Displayability check**: Use `api.diagram.isDisplayable(diagram, element)` before add for clarity

## Error Handling

The `api.diagram.create()` method throws descriptive exceptions:
- `IllegalStateException` - No active session
- `IllegalArgumentException` - Invalid diagram type or null target

```groovy
try {
    def diagram = api.diagram.createSafe("SAB", "My Diagram", saLayer)
} catch (IllegalArgumentException e) {
    log.error("Failed to create diagram: " + e.message)
}
```

## Troubleshooting

### Diagram Creation Fails or Returns Null

If `api.diagram.create()` fails:

1. **Check available diagram types:**
```groovy
// List all available types for a layer
log.info(api.diagram.listAvailableFormatted("SA"))

// Get just the abbreviations
def types = api.diagram.listAvailable("SA")
log.info("Available: " + types)
```

Use `api.help("diagram")` to see the exact diagram methods available in this build.

2. **Verify viewpoint activation:**
   - In Capella UI: Right-click project → Viewpoints Selection
   - Ensure the relevant analysis viewpoint is checked

3. **Use the correct target element:**
   - Architecture Blank (SAB, LAB, PAB): Use the layer root or component package
   - Data Flow Blank (SDFB, LDFB, PDFB): Use the function package or a function

4. **Try the full descriptor name:**
```groovy
// If abbreviation doesn't work, try full name
api.diagram.create("System Data Flow Blank", "My Diagram", target)
```

### Common Error Messages

| Error | Cause | Solution |
|-------|-------|----------|
| "Diagram description 'X' not found" | Descriptor name mismatch | Use `listAvailable()` to find correct name |
| "Did you mean: ..." | Similar descriptor found | Try the suggested name |
| "viewpoints not activated" | Missing viewpoint | Enable viewpoint in Capella UI |
| "Target element cannot be null" | Invalid target | Pass a valid model element |
| Returns `null` silently | Various | Check Capella console for detailed errors |

### Data Flow Diagrams (SDFB, LDFB, PDFB)

These may fail if the viewpoint isn't activated. The API now provides:
- Fuzzy matching (case-insensitive, partial match)
- Suggestions for similar descriptor names
- List of all available descriptors in error messages

If SDFB/LDFB/PDFB fail, use `listAvailableFormatted()` to see what Sirius descriptors
are actually registered, then try using the full name directly.
