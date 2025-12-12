# Full ARCADIA Workflow

Create a complete model across all architecture layers (OA→SA→LA→PA).

## Overview

This skill demonstrates creating a system model following the 
ARCADIA methodology, progressing through all four layers.

## Complete Example: Drone Delivery System

```groovy
// ============================================================
// STEP 1: OPERATIONAL ANALYSIS (OA)
// What do users need to accomplish?
// ============================================================

log.info("=== Creating Operational Analysis ===")

// Create operational capability
def deliveryCap = api.create("OA", "OperationalCapability", "Package Delivery")
api.set(deliveryCap, "description", "Ability to deliver packages via drone")

// Create operational activities
def pickUp = api.create("OA", "OperationalActivity", "Pick Up Package")
def transport = api.create("OA", "OperationalActivity", "Transport Package")
def deliver = api.create("OA", "OperationalActivity", "Deliver Package")

// Link activities to capability
[pickUp, transport, deliver].each { act ->
    api.link(act, deliveryCap, "involvement")
}

// Create activity flow
api.link(pickUp, transport, "FunctionalExchange")
api.link(transport, deliver, "FunctionalExchange")

// Create actors
def operator = api.create("OA", "Entity", "Drone Operator")
def customer = api.create("OA", "Entity", "Customer")


// ============================================================
// STEP 2: SYSTEM ANALYSIS (SA)
// What must the system do?
// ============================================================

log.info("=== Creating System Analysis ===")

// Create system component
def droneSystem = api.create("SA", "SystemComponent", "Drone Delivery System")

// Create system functions (realize operational activities)
def navigate = api.create("SA", "SystemFunction", "Navigate Route")
def carryPayload = api.create("SA", "SystemFunction", "Carry Payload")
def detectObstacles = api.create("SA", "SystemFunction", "Detect Obstacles")
def communicate = api.create("SA", "SystemFunction", "Communicate Status")

// Create functional chain
def e1 = api.link(detectObstacles, navigate, "FunctionalExchange")
api.set(e1, "name", "Obstacle Data")

def e2 = api.link(navigate, carryPayload, "FunctionalExchange")
api.set(e2, "name", "Position Data")

def e3 = api.link(carryPayload, communicate, "FunctionalExchange")
api.set(e3, "name", "Delivery Status")

// Create system actors
def groundStation = api.create("SA", "SystemActor", "Ground Station")


// ============================================================
// STEP 3: LOGICAL ARCHITECTURE (LA)
// How is it logically organized?
// ============================================================

log.info("=== Creating Logical Architecture ===")

// Create logical components
def flightController = api.create("LA", "LogicalComponent", "Flight Controller")
def navigationModule = api.create("LA", "LogicalComponent", "Navigation Module")
def sensorSuite = api.create("LA", "LogicalComponent", "Sensor Suite")
def commModule = api.create("LA", "LogicalComponent", "Communication Module")
def payloadHandler = api.create("LA", "LogicalComponent", "Payload Handler")

// Create logical functions (realize system functions)
def computeRoute = api.create("LA", "LogicalFunction", "Compute Route")
def processSensors = api.create("LA", "LogicalFunction", "Process Sensor Data")
def controlFlight = api.create("LA", "LogicalFunction", "Control Flight")
def managePayload = api.create("LA", "LogicalFunction", "Manage Payload")

// Allocate functions to components
api.link(computeRoute, navigationModule, "allocation")
api.link(processSensors, sensorSuite, "allocation")
api.link(controlFlight, flightController, "allocation")
api.link(managePayload, payloadHandler, "allocation")

// Create component interfaces
api.link(sensorSuite, navigationModule, "ComponentExchange")
api.link(navigationModule, flightController, "ComponentExchange")
api.link(flightController, commModule, "ComponentExchange")


// ============================================================
// STEP 4: PHYSICAL ARCHITECTURE (PA)
// How is it physically implemented?
// ============================================================

log.info("=== Creating Physical Architecture ===")

// Create physical components (hardware)
def mainBoard = api.create("PA", "PhysicalComponent", "Main Control Board")
def gpsModule = api.create("PA", "PhysicalComponent", "GPS Module")
def imuSensor = api.create("PA", "PhysicalComponent", "IMU Sensor")
def radioModule = api.create("PA", "PhysicalComponent", "Radio Module")
def servoController = api.create("PA", "PhysicalComponent", "Servo Controller")

// Create physical components (software)
def flightFirmware = api.create("PA", "PhysicalComponent", "Flight Firmware")
def navSoftware = api.create("PA", "PhysicalComponent", "Navigation Software")

// Create physical links
api.link(gpsModule, mainBoard, "PhysicalLink")
api.link(imuSensor, mainBoard, "PhysicalLink")
api.link(radioModule, mainBoard, "PhysicalLink")
api.link(servoController, mainBoard, "PhysicalLink")

// Deploy software to hardware
api.link(flightFirmware, mainBoard, "deployment")
api.link(navSoftware, mainBoard, "deployment")


// ============================================================
// SUMMARY
// ============================================================

log.info("=== Model Creation Complete ===")
log.info("OA: 1 capability, 3 activities, 2 actors")
log.info("SA: 1 system, 4 functions, 1 actor")
log.info("LA: 5 components, 4 functions")
log.info("PA: 7 components (5 HW, 2 SW)")
```

## Customization

### Scaling Up

Add more elements at each layer:
- More capabilities and activities in OA
- More detailed function breakdown in SA
- Deeper component hierarchy in LA
- More hardware detail in PA

### Adding Diagrams

```groovy
// Create overview diagram for each layer
def oaDiagram = api.diagram.create("OAB", "Operational Overview")
def saDiagram = api.diagram.create("SAB", "System Overview")
def laDiagram = api.diagram.create("LAB", "Logical Overview")
def paDiagram = api.diagram.create("PAB", "Physical Overview")
```

## Tips

- Start with OA to understand user needs
- SA translates needs into system requirements
- LA organizes without physical constraints
- PA maps to real hardware/software
