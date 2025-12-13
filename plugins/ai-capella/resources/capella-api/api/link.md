# api.link()

Create relationships between Capella elements.

## Signatures

```groovy
// Create typed relationship
EObject link(EObject source, EObject target, String relationType)

// Create simple reference
EObject link(EObject source, EObject target)
```

## Relationship Types

### Functional Exchanges
Connect functions with data/control flow:

```groovy
def func1 = api.find("SystemFunction", "Acquire Data")
def func2 = api.find("SystemFunction", "Process Data")

// Create functional exchange
def exchange = api.link(func1, func2, "FunctionalExchange")
api.set(exchange, "name", "Raw Sensor Data")
```

### Component Exchanges
Connect components with interfaces:

```groovy
def comp1 = api.find("LogicalComponent", "Sensor")
def comp2 = api.find("LogicalComponent", "Controller")

def exchange = api.link(comp1, comp2, "ComponentExchange")
api.set(exchange, "name", "Sensor Interface")
```

### Allocations
Allocate functions/activities to components/entities.

**IMPORTANT**: Use `api.allocate()` instead of `api.link()` for allocations:

```groovy
// Allocate function to component (all layers)
def func = api.find("SystemFunction", "Process Data")
def comp = api.find("SystemComponent", "Processor")
api.allocate(func, comp)  // Use api.allocate(), NOT api.link()

// Allocate activity to entity (OA layer)
def activity = api.find("OperationalActivity", "Monitor")
def entity = api.find("Entity", "Operator")
api.allocate(activity, entity)
```

### Realizations
Link elements across layers:

```groovy
def sysFunc = api.find("SystemFunction", "Process Data")
def logFunc = api.find("LogicalFunction", "Process Data")

api.link(logFunc, sysFunc, "realization")
```

## Common Relationship Types

| Type | Connects | Method |
|------|----------|--------|
| `FunctionalExchange` | Function to Function | `api.link()` |
| `ComponentExchange` | Component to Component | `api.link()` |
| `FunctionRealization` | LA/PA Function to SA/LA Function | `api.link()` |
| `ComponentRealization` | LA/PA Component to SA/LA Component | `api.link()` |
| Allocation | Function to Component | `api.allocate()` |
| `involvement` | Activity to Capability | `api.link()` |

## Examples

```groovy
// Create a complete flow
def acquire = api.create("SA", "SystemFunction", "Acquire")
def process = api.create("SA", "SystemFunction", "Process")
def output = api.create("SA", "SystemFunction", "Output")

api.link(acquire, process, "FunctionalExchange")
api.link(process, output, "FunctionalExchange")

log.info("Created function chain")
```

## Physical Layer Connections

Physical components use `ComponentExchange` (same as other layers):

```groovy
def pc1 = api.find("PhysicalComponent", "Sensor Unit")
def pc2 = api.find("PhysicalComponent", "Processing Unit")

// Use ComponentExchange for physical connections
api.link(pc1, pc2, "ComponentExchange")
```

Note: `PhysicalLink` is a different concept for physical paths/ports.

## Tips

- The created relationship is returned for further configuration
- Use `api.set(link, "name", "...")` to name the relationship
- Links are automatically validated for compatibility
