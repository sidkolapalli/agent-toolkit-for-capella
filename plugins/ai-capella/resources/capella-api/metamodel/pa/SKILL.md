# Physical Architecture (PA)

The PA layer defines the physical implementation of the system.

## Key Types

### PhysicalFunction
Functions implemented in physical components.

```groovy
def func = api.create("PA", "PhysicalFunction", "Execute Control Loop")
api.set(func, "description", "Real-time control loop execution")
```

### PhysicalComponent
Physical hardware/software components.

```groovy
// Hardware component
def board = api.create("PA", "PhysicalComponent", "Main Control Board")
api.set(board, "description", "Primary MCU board")

// Software component
def firmware = api.create("PA", "PhysicalComponent", "Flight Firmware")
api.set(firmware, "description", "Embedded flight control software")
```

## Common Patterns

### Realize from LA

```groovy
// Find logical component
def logComp = api.find("LogicalComponent", "Flight Controller")

// Propagate to PA
def physComp = api.propagate(logComp, "PA")
log.info("Created: ${api.name(physComp)}")
```

### Physical Functions and Allocation

```groovy
// Create hardware
def mcu = api.create("PA", "PhysicalComponent", "STM32 MCU")
def sensors = api.create("PA", "PhysicalComponent", "IMU Sensor Board")

// Create software
def rtos = api.create("PA", "PhysicalComponent", "FreeRTOS")
def app = api.create("PA", "PhysicalComponent", "Flight Application")

// Allocate physical functions to their implementing components
def schedule = api.create("PA", "PhysicalFunction", "Schedule Control Tasks")
def control = api.create("PA", "PhysicalFunction", "Execute Flight Control")
api.allocate(schedule, rtos)
api.allocate(control, app)
api.link(schedule, control, "FunctionalExchange")

// Software deployment onto hardware requires a native Capella deployment operation.
```

### Physical Interfaces

```groovy
def board1 = api.find("PhysicalComponent", "Control Board")
def board2 = api.find("PhysicalComponent", "Sensor Board")

// Behavioral interface between PA components (not a physical cable)
def link = api.link(board1, board2, "ComponentExchange")
api.set(link, "name", "Sensor Data Interface")
```

### Complete Node Architecture

```groovy
// Create a physical node
def drone = api.create("PA", "PhysicalComponent", "Drone Platform")

// Add boards
def mainBoard = api.create("PhysicalComponent", "Main Board", drone)
def powerBoard = api.create("PhysicalComponent", "Power Board", drone)
def commBoard = api.create("PhysicalComponent", "Comm Board", drone)

// Add component exchanges between boards
api.link(mainBoard, powerBoard, "ComponentExchange")
api.link(mainBoard, commBoard, "ComponentExchange")

log.info("Created drone node with 3 boards")
```

## Diagram Types

- `PAB` - Physical Architecture Blank (components)
- `PDFB` - Physical Data Flow Blank (functions)
