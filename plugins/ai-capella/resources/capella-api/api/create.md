# api.create()

Create new elements in the Capella model.

## Signatures

```groovy
// Auto-detect parent based on type
EObject create(String type, String name)

// Specify architecture layer
EObject create(String layer, String type, String name)

// Create under specific parent
EObject create(String type, String name, EObject parent)
```

## Examples

```groovy
// Create a SystemFunction (auto-detects SA layer)
def func = api.create("SystemFunction", "Process Input")

// Create in specific layer
def comp = api.create("LA", "LogicalComponent", "Controller")

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
- `OperationalActor` / `Entity` - Who performs activities

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

## Layer Shortcuts

```groovy
api.create("OA", "OperationalActivity", "Deliver Package")
api.create("SA", "SystemFunction", "Track Delivery")
api.create("LA", "LogicalComponent", "GPS Module")
api.create("PA", "PhysicalComponent", "Antenna")
```

## Tips

- Created elements are automatically saved
- Parent is auto-detected from type if not specified
- Use `api.layer("SA")` to get layer root for inspection
