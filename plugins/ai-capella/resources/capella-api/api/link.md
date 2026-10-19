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
Connect functions or OA activities in the same architecture and active session. OA exchanges connect activities directly; SA/LA/PA exchanges automatically create function output/input ports. Existing attached output and input ports can also be passed as endpoints.

```groovy
def func1 = api.find("SystemFunction", "Acquire Data")
def func2 = api.find("SystemFunction", "Process Data")

// Create functional exchange
def exchange = api.link(func1, func2, "FunctionalExchange")
api.set(exchange, "name", "Raw Sensor Data")
```

### Component Exchanges
Connect components in the same SA/LA/PA architecture. The API creates FLOW component ports with OUT/IN orientations when components are passed; existing attached ports with compatible orientations can also be used. Between two OA entities, `ComponentExchange` or `CommunicationMean` creates an OA communication mean.

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
Link lower-level elements to higher-level elements in the same session: SA realizes OA, LA realizes SA, and PA realizes LA. Reversing that direction or linking within one layer is rejected.

```groovy
def sysFunc = api.find("SystemFunction", "Process Data")
def logFunc = api.find("LogicalFunction", "Process Data")

api.link(logFunc, sysFunc, "realization")
```

For functional and FLOW component interfaces between adjacent layers, first realize their endpoint functions/components, then call `api.arcadia.realizeInterface(lowerExchange, upperExchange)` to create the native exchange and endpoint port realizations together. Individual `FunctionalExchangeRealization`, `ComponentExchangeRealization` and `PortRealization` types are also supported through `api.link`. These builders require exact stored source/target direction, correct ownership and compatible native endpoint responsibilities; see [api.arcadia](arcadia.md).

### EPBS Physical Artifact Realizations

An EPBS configuration item realizes a PA physical component. Pass the configuration item first and use the native relation type:

```groovy
def item = api.create("EPBS", "ConfigurationItem", "Flight Control Item")
def physicalComponent = api.find("PhysicalComponent", "Flight Firmware")
api.link(item, physicalComponent, "PhysicalArtifactRealization")
```

Generic `realization` also dispatches this endpoint pair to `PhysicalArtifactRealization`. Both endpoints must belong to the active session. EPBS does not support function or component exchanges.

## Common Relationship Types

| Type | Connects | Method |
|------|----------|--------|
| `FunctionalExchange` | Functions/activities or attached output/input ports in the same layer | `api.link()` |
| `ComponentExchange` | Components/ports in one SA/LA/PA layer; two entities in OA | `api.link()` |
| `CommunicationMean` | OA Entity to OA Entity | `api.link()` |
| `FunctionRealization` | SA/LA/PA function to a higher-level activity/function | `api.link()` |
| `ComponentRealization` | SA/LA/PA component to a higher-level OA entity or component | `api.link()` |
| `FunctionalExchangeRealization` | Adjacent lower-layer exchange to upper-layer exchange, with realized endpoint functions | `api.arcadia.realizeInterface()` or `api.link()` |
| `ComponentExchangeRealization` | Adjacent lower-layer FLOW transport to upper-layer FLOW transport, with realized endpoint components | `api.arcadia.realizeInterface()` or `api.link()` |
| `PortRealization` | Compatible ports of realized endpoint functions/components across adjacent layers | `api.arcadia.realizeInterface()` or `api.link()` |
| `PhysicalArtifactRealization` | EPBS ConfigurationItem to PA PhysicalComponent | `api.link()` |
| Allocation | Function to Component | `api.allocate()` |

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

// Use ComponentExchange for behavioral interfaces in PA
api.link(pc1, pc2, "ComponentExchange")
```

`PhysicalLink` and software deployment are separate Capella concepts. They are not supported relation types in `api.link()`. Component exchanges do not create cables or software deployments. Capability activity involvement also requires a typed native Capella operation rather than the generic `involvement` reference helper.

## Tips

- The created relationship is returned for further configuration
- Use `api.set(link, "name", "...")` to name the relationship
- Exchange and realization endpoints are checked for compatible types, architecture and active-session membership.
