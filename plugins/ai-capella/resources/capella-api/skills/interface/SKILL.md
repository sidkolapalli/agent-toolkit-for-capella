---
name: Interface Definition
summary: Define interfaces between components with exchanges and ports.
keywords: [interface, port, exchange, component exchange, functional exchange]
layer: [SA, LA, PA]
when_to_use: User requests component interfaces or exchanges.
difficulty: intermediate
---

# Interface Definition Pattern

Define interfaces between components with exchanges and ports.

## Overview

Interfaces in Capella connect components through:
- **Component Exchanges** - Connections between components
- **Functional Exchanges** - Data/control flow between functions
- **Ports** - Connection points on components

## Complete Example

```groovy
// 1. Create or find components
def sensor = api.find("LogicalComponent", "Sensor Module")
if (sensor == null) {
    sensor = api.create("LA", "LogicalComponent", "Sensor Module")
}

def controller = api.find("LogicalComponent", "Controller")
if (controller == null) {
    controller = api.create("LA", "LogicalComponent", "Controller")
}

// 2. Create functions under the LA root function
def readSensor = api.create("LA", "LogicalFunction", "Read Sensor")
def processData = api.create("LA", "LogicalFunction", "Process Data")

// 3. Create functional exchange (data flow)
def dataFlow = api.link(readSensor, processData, "FunctionalExchange")
api.set(dataFlow, "name", "Sensor Data")

// 4. Allocate functions to components
api.allocate(readSensor, sensor)
api.allocate(processData, controller)

// 5. Create component exchange (interface)
def interface1 = api.link(sensor, controller, "ComponentExchange")
api.set(interface1, "name", "Sensor Interface")

log.info("Created interface between Sensor and Controller")
```

## Bidirectional Interface

```groovy
// Sensor sends data, Controller sends commands
def sensorData = api.link(sensor, controller, "ComponentExchange")
api.set(sensorData, "name", "Sensor Data")

def commands = api.link(controller, sensor, "ComponentExchange")
api.set(commands, "name", "Sensor Commands")
```

## Interface with Exchange Items

```groovy
// Create exchange item (data type)
def dataType = api.create("ExchangeItem", "SensorReading")
api.set(dataType, "description", "Temperature and pressure readings")

// Associate with exchange
def exchange = api.link(sensor, controller, "ComponentExchange")
api.set(exchange, "name", "Sensor Data")
// Exchange items can be linked to exchanges
```

## Multi-Component Interface

```groovy
// Create a bus connecting multiple components
def sensors = ["GPS", "IMU", "Barometer"].collect { name ->
    api.create("LA", "LogicalComponent", name)
}

def dataFusion = api.create("LA", "LogicalComponent", "Data Fusion")

// Connect all sensors to fusion component
sensors.each { sensor ->
    def exchange = api.link(sensor, dataFusion, "ComponentExchange")
    api.set(exchange, "name", "${api.name(sensor)} Data")
}

log.info("Created ${sensors.size()} sensor interfaces")
```

## Tips

- Name exchanges clearly (they appear in diagrams)
- Functional exchanges define WHAT data flows
- Component exchanges define HOW components connect
- Use ports for complex interface definitions
- Exchanges require endpoints in the same architecture and session. Component/function endpoints create compatible ports automatically.
