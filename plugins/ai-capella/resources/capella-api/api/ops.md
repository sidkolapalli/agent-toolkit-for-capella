# api.ops.transition()

Perform automated engineering transitions (e.g., System to Logical).

## Signatures

```groovy
// Transition selected elements
boolean transition(Collection<EObject> selection, String kind)
```

## Supported Transitions

| Kind | Description | Source Layer | Target Layer |
|------|-------------|--------------|--------------|
| `"OA2SA"` | Operational to System | OA | SA |
| `"SA2LA"` | System to Logical | SA | LA |

## Examples

```groovy
// Transition all System Functions to Logical
def functions = api.findAll("SystemFunction")
api.ops.transition(functions, "SA2LA")

// Transition specific component and its children
def comp = api.find("Flight Computer")
api.ops.transition([comp], "SA2LA")
```

## Notes

- The transition engine handles traceability automatically (creating `Realization` links).
- If the target element already exists, it will be updated (synchronized).
- This is equivalent to right-clicking elements and selecting **Automated Transition > System to Logical**.
