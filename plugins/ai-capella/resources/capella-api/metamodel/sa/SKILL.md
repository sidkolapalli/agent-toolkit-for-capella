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
Building blocks of the system.

```groovy
def comp = api.create("SA", "SystemComponent", "Navigation Module")
api.set(comp, "description", "Handles all navigation functions")
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
def comp = api.find("SystemComponent", "Processor")

// Allocate function to component
api.link(func, comp, "allocation")
```

### Component Hierarchy

```groovy
def system = api.create("SA", "SystemComponent", "Drone System")
def nav = api.create("SystemComponent", "Navigation", system)
def sensor = api.create("SystemComponent", "Sensors", system)
def comms = api.create("SystemComponent", "Communications", system)
```

## Diagram Types

- `SAB` - System Architecture Blank (components)
- `SDFB` - System Data Flow Blank (functions)
- `ES` - Exchange Scenario (sequences)
