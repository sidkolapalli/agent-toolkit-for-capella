# API Methods Overview

The `api` object provides all Capella model operations.

## Categories

| Category | Methods | Description |
|----------|---------|-------------|
| Find | `find`, `findAll`, `query`, `aql` | Locate elements |
| Create | `create`, `ensurePart` | Create new elements (and Parts for diagrams) |
| Modify | `set`, `get`, `rename`, `delete` | Change elements |
| Link | `link` | Create relationships |
| Navigate | `layer`, `root`, `container`, `containerFor`, `parent`, `children` | Traverse model |
| Utilities | `capabilities`, `summarize` | Runtime info and compact summaries |
| Diagram | `api.diagram.*` | Diagram operations |

## Quick Reference

```groovy
// Finding
api.find("ElementName")              // By name only
api.find("SystemFunction", "Name")   // By type and name
api.findAll("LogicalComponent")      // All of type
api.aql("aql:self.name", api.layer("SA"))

// Creating
api.create("SystemFunction", "Name")           // Auto-detect layer
api.create("SA", "SystemFunction", "Name")     // Specify layer
api.create("SystemFunction", "Name", parent)   // Under parent
api.ensurePart(component)                      // Ensure a Part exists (SAB/LAB/PAB)

// Modifying
api.set(element, "description", "text")
api.rename(element, "New Name")
api.delete(element)

// Linking
api.link(source, target, "FunctionalExchange")
api.link(func, component, "allocation")

// Navigation
api.layer("SA")           // Get layer root
api.root("SA")            // Alias for layer
api.container("SA", "function")  // Default function container
api.containerFor(element) // Canonical container for an element
api.parent(element)       // Get parent
api.children(element)     // Get children

// Utilities
api.capabilities()        // JSON of runtime capabilities/fallbacks
api.summarize(list)       // Compact summary for LLM output
```

## Detailed Documentation

- `api.fs.read("api/find.md")` - Finding elements
- `api.fs.read("api/create.md")` - Creating elements
- `api.fs.read("api/modify.md")` - Modifying elements
- `api.fs.read("api/link.md")` - Creating relationships
- `api.fs.read("api/diagram.md")` - Diagram operations
