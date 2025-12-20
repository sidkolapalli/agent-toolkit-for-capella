---
name: State Machine Creation
summary: Create state machines with modes and transitions.
keywords: [state machine, state, mode, transition, fsm]
layer: [LA, PA]
when_to_use: User requests state-based behavior modeling.
difficulty: intermediate
---

# State Machine Pattern

Create a complete state machine with modes and transitions.

## Overview

State machines in Capella define behavioral modes for components.
They consist of regions, states, and transitions.

## Complete Example

```groovy
// 1. Find or create the component
def component = api.find("LogicalComponent", "Controller")
if (component == null) {
    component = api.create("LA", "LogicalComponent", "Controller")
}

// 2. Create state machine
def sm = api.create("StateMachine", "Controller Modes", component)

// 3. Create main region
def region = api.create("Region", "Main Region", sm)

// 4. Create states
def idle = api.create("State", "Idle", region)
def active = api.create("State", "Active", region)
def error = api.create("State", "Error", region)

// 5. Create initial pseudo-state
def initial = api.create("Pseudostate", "Initial", region)
api.set(initial, "kind", "INITIAL")

// 6. Create transitions
// Initial -> Idle
def t0 = api.link(initial, idle, "Transition")

// Idle -> Active (on activate command)
def t1 = api.link(idle, active, "Transition")
api.set(t1, "name", "activate")

// Active -> Idle (on deactivate command)
def t2 = api.link(active, idle, "Transition")
api.set(t2, "name", "deactivate")

// Active -> Error (on fault detected)
def t3 = api.link(active, error, "Transition")
api.set(t3, "name", "on_fault")

// Error -> Idle (on reset)
def t4 = api.link(error, idle, "Transition")
api.set(t4, "name", "reset")

log.info("Created state machine with 3 states and 5 transitions")
```

## Customization

### Adding More States

```groovy
def standby = api.create("State", "Standby", region)
api.link(idle, standby, "Transition").tap { 
    api.set(it, "name", "enter_standby") 
}
```

### Nested States (Sub-machines)

```groovy
// Create sub-region inside a state
def subRegion = api.create("Region", "Active Modes", active)
def normalMode = api.create("State", "Normal", subRegion)
def degradedMode = api.create("State", "Degraded", subRegion)
```

### Adding Guards/Triggers

```groovy
def transition = api.link(idle, active, "Transition")
api.set(transition, "name", "start")
api.set(transition, "guard", "battery > 20%")
api.set(transition, "trigger", "StartCommand")
```

## Tips

- Every region should have an initial pseudo-state
- Use meaningful transition names (they appear in diagrams)
- Consider error/fault states for robust designs
