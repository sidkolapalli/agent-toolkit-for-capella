# System Analysis (SA)

The SA layer defines what the system must do to fulfill operational needs.

## Key Types

### SystemFunction
Functions performed by the system.

```groovy
def func = api.create("SA", "SystemFunction", "Process Sensor Data")
api.set(func, "description", "Processes raw sensor input into usable data")
```

### SystemComponent
The single system under study, treated as a black box. Allocate system functions to this component. Put internal component decomposition in LA and PA.

```groovy
def comp = api.find("SystemComponent", "System")
if (comp == null) throw new IllegalArgumentException("Inspect the SA system component and use its actual name or UUID")
api.set(comp, "description", "System boundary under study")
```

### SystemActor
External entities that interact with the system.

```groovy
def actor = api.create("SA", "SystemActor", "Operator")
api.set(actor, "description", "Human operator controlling the system")
```

## Common Patterns

### Function Chain

```groovy
def acquire = api.create("SA", "SystemFunction", "Acquire Data")
def validate = api.create("SA", "SystemFunction", "Validate Data")
def process = api.create("SA", "SystemFunction", "Process Data")
def output = api.create("SA", "SystemFunction", "Output Results")

// Create exchanges
def e1 = api.link(acquire, validate, "FunctionalExchange")
api.set(e1, "name", "Raw Data")

def e2 = api.link(validate, process, "FunctionalExchange")
api.set(e2, "name", "Valid Data")

def e3 = api.link(process, output, "FunctionalExchange")
api.set(e3, "name", "Processed Data")
```

### Function Allocation

```groovy
def func = api.find("SystemFunction", "Process Data")
def comp = api.find("SystemComponent", "System")
if (func == null || comp == null) throw new IllegalArgumentException("Inspect the actual function and system identifiers first")

// Allocate function to component
api.allocate(func, comp)
```

### Internal Component Decomposition

```groovy
// Internal decomposition belongs to Logical Architecture.
def nav = api.create("LA", "LogicalComponent", "Navigation")
def sensor = api.create("LA", "LogicalComponent", "Sensors")
def comms = api.create("LA", "LogicalComponent", "Communications")
```

## Diagram Types

- `SAB` - System Architecture Blank (components)
- `SDFB` - System Data Flow Blank (functions)
- `ES` - Component Exchanges Scenario (sequences). Creating it on a capability also creates the scenario: `api.diagram.create("ES", "Scenario name", capability)`
