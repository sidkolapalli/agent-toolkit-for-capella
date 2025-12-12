# api.find()

Find elements in the Capella model.

## Signatures

```groovy
// Find by name (any type)
EObject find(String name)

// Find by type and name
EObject find(String type, String name)

// Find all elements of type
List<EObject> findAll(String type)

// Find with custom predicate
List<EObject> query(Predicate<EObject> predicate)

// Find by ID/UUID
EObject findById(String id)
```

## Examples

```groovy
// Find any element named "Flight Controller"
def element = api.find("Flight Controller")

// Find a specific SystemFunction
def func = api.find("SystemFunction", "Process Sensor Data")

// Find all LogicalComponents
def components = api.findAll("LogicalComponent")
log.info("Found ${components.size()} components")

// Find with predicate
def namedElements = api.query { e ->
    api.name(e)?.startsWith("Process")
}

// Check if found
if (func != null) {
    log.info("Found: ${api.name(func)}")
} else {
    log.warn("Element not found")
}
```

## Common Types

| Layer | Types |
|-------|-------|
| OA | OperationalCapability, OperationalActivity, Entity |
| SA | SystemFunction, SystemComponent, SystemActor |
| LA | LogicalFunction, LogicalComponent |
| PA | PhysicalFunction, PhysicalComponent |

## Tips

- Type names are case-sensitive
- Use `findAll` to discover what exists in the model
- `find` returns `null` if not found - always check!
