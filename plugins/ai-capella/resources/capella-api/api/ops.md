# api.ops.transition()

Perform automated engineering transitions (e.g., System to Logical).

## Signatures

```groovy
// Transition selected elements
boolean transition(Collection<EObject> selection, String kind)
```

## Supported Transitions

Each kind runs the matching entry of Capella's **Transitions** menu on the selection, with its realization links.

| Kind | What it carries to the next perspective |
|------|------------------------------------------|
| `"OA2SA"`, `"SA2LA"` | functions, capabilities or the system, picked from the first selected element |
| `"LA2PA"` | logical components to physical components |
| `"PA2EPBS"` | physical components to configuration items |
| `"functional"` | functions and their exchanges (OA→SA, SA→LA, LA→PA) |
| `"capability"` | capabilities (to capability realizations below SA) |
| `"actor"`, `"system"` | actors; the system and its parts |
| `"entity-to-actor"`, `"entity-to-system"` | operational entities to actors or to the system |
| `"data"`, `"exchange-item"`, `"interface"` | classes and types, exchange items, interfaces |
| `"state-machine"` | a component's modes and states |
| `"property-value"` | property values and groups |

## Examples

```groovy
// Transition all System Functions to Logical
def functions = api.findAll("SystemFunction")
api.ops.transition(functions, "SA2LA")

// Transition specific component and its children
def comp = api.find("Flight Computer")
api.ops.transition([comp], "SA2LA")

// Carry the logical components into the physical architecture, then their modes
api.ops.transition(api.findAll("LogicalComponent"), "LA2PA")
api.ops.transition([api.layer("LA")], "state-machine")
```

## Notes

- The transition engine handles traceability automatically (creating `Realization` links).
- If the target element already exists, it will be updated (synchronized).
- This is equivalent to right-clicking elements and selecting **Automated Transition > System to Logical**.
