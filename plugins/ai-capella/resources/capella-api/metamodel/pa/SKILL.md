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

### Hardware/Software Mapping

```groovy
// Create hardware
def mcu = api.create("PA", "PhysicalComponent", "STM32 MCU")
def sensors = api.create("PA", "PhysicalComponent", "IMU Sensor Board")

// Create software
def rtos = api.create("PA", "PhysicalComponent", "FreeRTOS")
def app = api.create("PA", "PhysicalComponent", "Flight Application")

// Software runs on hardware
api.link(rtos, mcu, "deployment")
api.link(app, mcu, "deployment")
```

### Physical Interfaces

```groovy
def board1 = api.find("PhysicalComponent", "Control Board")
def board2 = api.find("PhysicalComponent", "Sensor Board")

// Physical link (e.g., SPI bus)
def link = api.link(board1, board2, "PhysicalLink")
api.set(link, "name", "SPI Bus")
```

### Complete Node Architecture

```groovy
// Create a physical node
def drone = api.create("PA", "PhysicalComponent", "Drone Platform")

// Add boards
def mainBoard = api.create("PhysicalComponent", "Main Board", drone)
def powerBoard = api.create("PhysicalComponent", "Power Board", drone)
def commBoard = api.create("PhysicalComponent", "Comm Board", drone)

// Add links between boards
api.link(mainBoard, powerBoard, "PhysicalLink")
api.link(mainBoard, commBoard, "PhysicalLink")

log.info("Created drone node with 3 boards")
```

## Diagram Types

- `PAB` - Physical Architecture Blank (components)
- `PDFB` - Physical Data Flow Blank (functions)
