# api.create()

Create new elements in the Capella model.

## Signatures

```groovy
// Auto-detect parent based on type
EObject create(String type, String name)

// Specify architecture layer
EObject create(String layer, String type, String name)

// Unambiguous layer helper
EObject createIn(String layer, String type, String name)

// Create under specific parent
EObject create(String type, String name, EObject parent)
```

## Examples

```groovy
// Create a SystemFunction (auto-detects SA layer)
def func = api.create("SystemFunction", "Process Input")

// Create in specific layer
def comp = api.create("LA", "LogicalComponent", "Controller")

// Create in specific layer (explicit helper)
def func = api.createIn("LA", "LogicalFunction", "Decode Stream")

// Create under specific parent
def subFunc = api.create("SystemFunction", "Validate Data", func)

// Create with properties
def newFunc = api.create("SA", "SystemFunction", "Handle Request")
api.set(newFunc, "description", "Handles incoming requests")
api.set(newFunc, "visible_in_doc", true)
```

## Supported Types by Layer

### OA - Operational Analysis
- `OperationalCapability` - What the system must achieve
- `OperationalActivity` - Activities performed by actors
- `Entity` - Operational actors and components
  - For actors: `api.set(entity, "actor", true)`
- `OperationalActor` - Alias for `Entity` with `actor=true`

### SA - System Analysis
- `SystemFunction` - Functions the system performs
- `SystemComponent` - System building blocks
- `SystemActor` - External entities interacting with system

### LA - Logical Architecture
- `LogicalFunction` - Logical functions
- `LogicalComponent` - Logical building blocks

### PA - Physical Architecture
- `PhysicalFunction` - Physical functions
- `PhysicalComponent` - Physical building blocks

### EPBS - End-Product Breakdown Structure
- `ConfigurationItem` - Configuration items realizing PA physical components
- Use `api.create("EPBS", "ConfigurationItem", "Control Item")` to create beneath the existing unique product root. If the architecture has no root item, this creates its first root; multiple existing roots cause an error rather than choosing a parent.
- Nest items with `api.create("ConfigurationItem", "Navigation Item", parentItem)`.
- Configuration items have same-named native representing Parts. Use `api.find("ConfigurationItem", name)` when selecting an item by name.
- Use `api.epbs()` or `api.layer("EPBS")` for the architecture root. EPBS does not support functions or exchanges.

## Layer Shortcuts

```groovy
api.create("OA", "OperationalActivity", "Deliver Package")
api.create("SA", "SystemFunction", "Track Delivery")
api.create("LA", "LogicalComponent", "GPS Module")
api.create("PA", "PhysicalComponent", "Antenna")
api.create("EPBS", "ConfigurationItem", "Antenna Item")
```

## Tips

- Functions must match their architecture: OA `OperationalActivity`, SA `SystemFunction`, LA `LogicalFunction`, PA `PhysicalFunction`.
- Layer-based function creation places new functions under that layer's existing root function. To decompose a function, pass a parent function of the same layer.
- Components do not contain functions. Create functions in their layer, then use `api.allocate(function, component)`.
- Successful script changes are committed together and saved after execution; failed or cancelled scripts are rolled back.
- Parent is auto-detected from type if not specified
- Use `api.layer("SA")` to get layer root for inspection
- If you need to allocate a function to a component, use `api.allocate(function, component)` (do not create allocation objects directly)
