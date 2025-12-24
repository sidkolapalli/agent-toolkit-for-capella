# api.validate()

Validate the model (or a subset) and return a list of issues suitable for LLM feedback loops.

## Signatures

```groovy
// Validate the whole model (quick scan)
List<String> validate()

// Validate a specific architecture layer (OA, SA, LA, PA)
List<String> validate(String layer)

// Validate a specific element (and limited descendants)
List<String> validate(EObject element)
```

## Examples

```groovy
// Validate the entire model
def issues = api.validate()
issues.each { log.warn(it) }

// Validate a layer
api.validate("LA").each { log.warn(it) }

// Validate a specific element after creation
def comp = api.create("LA", "LogicalComponent", "Video Server")
api.validate(comp).each { log.warn(it) }
```

## Notes

- Validation is a **quick scan** (bounded for performance).
- Messages are formatted as: `SEVERITY SOURCE: message`.
- If you created new exchanges or allocations, validate immediately to catch missing interfaces or invalid relationships early.
