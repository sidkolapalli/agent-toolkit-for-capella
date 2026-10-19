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
def initial = api.create("InitialPseudoState", "Initial", region)

// 6. Create native StateTransition objects in their owning region
def connectStates = { source, target, name ->
    def transition = api.create("StateTransition", name, region)
    api.set(transition, "source", source)
    api.set(transition, "target", target)
    transition
}

// Initial -> Idle
def t0 = connectStates(initial, idle, "initialize")

// Idle -> Active (on activate command)
def t1 = connectStates(idle, active, "activate")

// Active -> Idle (on deactivate command)
def t2 = connectStates(active, idle, "deactivate")

// Active -> Error (on fault detected)
def t3 = connectStates(active, error, "on_fault")

// Error -> Idle (on reset)
def t4 = connectStates(error, idle, "reset")

log.info("Created state machine with 3 states and 5 transitions")
```

## Customization

### Adding More States

```groovy
def standby = api.create("State", "Standby", region)
def enterStandby = api.create("StateTransition", "enter_standby", region)
api.set(enterStandby, "source", idle)
api.set(enterStandby, "target", standby)
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
def transition = api.create("StateTransition", "start", region)
api.set(transition, "source", idle)
api.set(transition, "target", active)
api.set(transition, "triggerDescription", "StartCommand")

// guard is a Constraint reference, rather than a text property
def guard = api.create("Constraint", "Battery Guard", transition)
api.set(guard, "description", "Battery charge must exceed 20 percent")
api.set(transition, "guard", guard)
```

Configure a guard's formal expression and typed trigger events through the native Capella editor when needed; the description above records the intended condition.

## Tips

- Every region should have an initial pseudo-state
- Use meaningful transition names (they appear in diagrams)
- Consider error/fault states for robust designs
- Use the concrete `InitialPseudoState` and `StateTransition` metamodel types; `Pseudostate` is abstract and `api.link()` has no transition-creation strategy.
