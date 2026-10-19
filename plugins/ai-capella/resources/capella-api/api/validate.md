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

// Full installed Capella batch validator, including live constraints
Map<String, Object> validateNative()

// Same complete counts, with a bounded diagnostic capture limit (1..10000)
Map<String, Object> validateNative(int maximumMessages)
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
- `validateNative()` reports the complete error/warning counts and up to 200 issue messages, with errors first. `truncated` indicates omitted messages. It rejects semantic resources with load errors: fix their references and reopen them before validating.
- The trusted `native_validation` read tool captures up to 10000 diagnostics once and returns bounded pages. Continue its returned `cursor` to review the same validation result without rerunning it. Cursors expire after five minutes or a model command/Undo/Redo. `source_truncated` reports findings beyond the capture ceiling; `message_truncated` marks clipped individual text. `complete` means all captured messages have been delivered, so also inspect `source_truncated`.
- Native constraint diagnostics include `target_id` and `constraint_id` when available, alongside bounded display names. Use the exact native target ID to inspect an issue; names and numeric status codes do not uniquely identify its target or validation rule. `metadata_unavailable` or `metadata_truncated` discloses missing optional context.
- Zero native errors establishes metamodel consistency. It does not prove source-rule coverage, physical performance or vehicle compliance.
