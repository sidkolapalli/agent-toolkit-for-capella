# Logical Architecture (LA)

The LA layer defines the logical organization of the system.

## Key Types

### LogicalFunction
Logical functions that realize system functions.

```groovy
def func = api.create("LA", "LogicalFunction", "Compute Position")
api.set(func, "description", "Calculates current position from GPS data")
```

### LogicalComponent
Logical building blocks that will be implemented physically.

```groovy
def comp = api.create("LA", "LogicalComponent", "Flight Controller")
api.set(comp, "description", "Central flight control logic")
```

## Common Patterns

### Realize from SA

```groovy
// Find system function
def sysFunc = api.find("SystemFunction", "Navigate")

// Propagate to LA (creates with realization link)
def logFunc = api.propagate(sysFunc, "LA")

// Or create manually with link
def logFunc2 = api.create("LA", "LogicalFunction", "Navigate")
api.link(logFunc2, sysFunc, "realization")
```

### Component Decomposition

```groovy
def controller = api.create("LA", "LogicalComponent", "Flight Controller")
def attitude = api.create("LogicalComponent", "Attitude Control", controller)
def altitude = api.create("LogicalComponent", "Altitude Control", controller)
def heading = api.create("LogicalComponent", "Heading Control", controller)

// Each sub-component can have its own functions
def stabilize = api.create("LogicalFunction", "Stabilize", attitude)
def maintain = api.create("LogicalFunction", "Maintain Altitude", altitude)
```

### Interface Definition

```groovy
def sensor = api.find("LogicalComponent", "Sensor Module")
def controller = api.find("LogicalComponent", "Controller")

// Create interface (component exchange)
def interface1 = api.link(sensor, controller, "ComponentExchange")
api.set(interface1, "name", "Sensor Data Interface")
```

## Diagram Types

- `LAB` - Logical Architecture Blank (components)
- `LDFB` - Logical Data Flow Blank (functions)
