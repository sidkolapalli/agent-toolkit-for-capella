# api.describe()

Summarize a single element with a dense, one-hop graph (attributes + direct relations).

## Signature

```groovy
String describe(EObject element)
```

## Example

```groovy
def comp = api.find("LogicalComponent", "Video Server")
log.info(api.describe(comp))
```

## Output Format

```
LogicalComponent "Video Server" (id=...)
Container: LogicalComponentPkg "Logical Architecture"
Attributes:
- name = Video Server
- description = Streams video content
References:
- ownedFunctions (contains) -> 2 [LogicalFunction "Decode Stream", LogicalFunction "Buffer Content"]
- allocatedFunctions -> 1 [LogicalFunction "Decode Stream"]
```

## Notes

- Output is capped for token efficiency (limited attributes, references, and values).
- Use this before editing existing elements to reduce hallucinations.
