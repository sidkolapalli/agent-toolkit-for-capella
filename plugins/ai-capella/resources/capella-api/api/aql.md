# AQL Interpreter

Use `api.aql(expression, context)` to evaluate AQL using the same Sirius
interpreter Capella uses for diagram mappings and semantic queries.

## Usage

```groovy
// Simple property query
api.aql("aql:self.name", api.layer("SA"))

// Count owned functions for a component
def comp = api.find("LogicalComponent", "Navigation")
api.aql("aql:self.ownedFunctions->size()", comp)

// Boolean predicate
api.aql("aql:self.isAbstract = false", comp)
```

## Notes

- `expression` should include the `aql:` prefix, just like Sirius descriptors.
- `context` defaults to the System Engineering root when omitted.
- Returns the raw AQL result (Boolean, Number, String, EObject, or collection).

## Diagram preflight

Before adding an element to a diagram, check mapping constraints:

```groovy
def diagram = api.diagram.open("System Architecture Blank")
if (api.diagram.isDisplayable(diagram, comp)) {
    api.diagram.add(diagram, comp)
}
```

`api.diagram.isDisplayable` evaluates Sirius preconditions and candidate
expressions with the same interpreter Capella uses internally.
