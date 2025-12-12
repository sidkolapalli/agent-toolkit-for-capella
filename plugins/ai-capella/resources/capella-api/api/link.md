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
Allocate functions to components:

```groovy
def func = api.find("SystemFunction", "Process Data")
def comp = api.find("SystemComponent", "Processor")

api.link(func, comp, "allocation")
```

### Realizations
Link elements across layers:

```groovy
def sysFunc = api.find("SystemFunction", "Process Data")
def logFunc = api.find("LogicalFunction", "Process Data")

api.link(logFunc, sysFunc, "realization")
```

## Common Relationship Types

| Type | Connects | Purpose |
|------|----------|---------|
| `FunctionalExchange` | Function to Function | Data/control flow |
| `ComponentExchange` | Component to Component | Interface |
| `allocation` | Function to Component | Where function runs |
| `realization` | LA/PA to SA | Traces between layers |
| `involvement` | Activity to Capability | What achieves what |

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

## Tips

- The created relationship is returned for further configuration
- Use `api.set(link, "name", "...")` to name the relationship
- Links are automatically validated for compatibility
