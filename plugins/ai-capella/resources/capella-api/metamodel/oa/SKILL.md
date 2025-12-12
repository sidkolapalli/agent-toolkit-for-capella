# Operational Analysis (OA)

The OA layer captures what operators/users need to accomplish.

## Key Types

### OperationalCapability
What the system must achieve from an operational perspective.

```groovy
def cap = api.create("OA", "OperationalCapability", "Deliver Package")
api.set(cap, "description", "Ability to deliver packages to customers")
```

### OperationalActivity
Activities performed by actors to achieve capabilities.

```groovy
def activity = api.create("OA", "OperationalActivity", "Pick Up Package")
api.set(activity, "description", "Operator picks up package from warehouse")

// Link activity to capability
api.link(activity, cap, "involvement")
```

### Entity / OperationalActor
Who performs the activities.

```groovy
def actor = api.create("OA", "Entity", "Delivery Driver")
api.set(actor, "description", "Person who delivers packages")
```

## Common Patterns

### Create Capability with Activities

```groovy
// Create capability
def cap = api.create("OA", "OperationalCapability", "Package Delivery")

// Create activities
def pickup = api.create("OA", "OperationalActivity", "Pick Up")
def transport = api.create("OA", "OperationalActivity", "Transport")
def deliver = api.create("OA", "OperationalActivity", "Deliver")

// Link to capability
[pickup, transport, deliver].each { act ->
    api.link(act, cap, "involvement")
}

// Create flow between activities
api.link(pickup, transport, "FunctionalExchange")
api.link(transport, deliver, "FunctionalExchange")

log.info("Created OA structure")
```

## Diagram Types

- `OAB` - Operational Activity Blank
- `OEBD` - Operational Entity Breakdown
- `OCB` - Operational Capabilities Blank
