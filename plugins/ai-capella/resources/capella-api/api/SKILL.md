# API Methods Overview

The `api` object provides all Capella model operations.

## Categories

| Category | Methods | Description |
|----------|---------|-------------|
| Find | `find`, `findAll`, `query` | Locate elements |
| Create | `create` | Create new elements |
| Modify | `set`, `get`, `rename`, `delete` | Change elements |
| Link | `link` | Create relationships |
| Navigate | `layer`, `parent`, `children` | Traverse model |
| Diagram | `api.diagram.*` | Diagram operations |

## Quick Reference

```groovy
// Finding
api.find("ElementName")              // By name only
api.find("SystemFunction", "Name")   // By type and name
api.findAll("LogicalComponent")      // All of type

// Creating
api.create("SystemFunction", "Name")           // Auto-detect layer
api.create("SA", "SystemFunction", "Name")     // Specify layer
api.create("SystemFunction", "Name", parent)   // Under parent

// Modifying
api.set(element, "description", "text")
api.rename(element, "New Name")
api.delete(element)

// Linking
api.link(source, target, "FunctionalExchange")
api.link(func, component, "allocation")

// Navigation
api.layer("SA")           // Get layer root
api.parent(element)       // Get parent
api.children(element)     // Get children
```

## Detailed Documentation

- `api.fs.read("api/find.md")` - Finding elements
- `api.fs.read("api/create.md")` - Creating elements
- `api.fs.read("api/modify.md")` - Modifying elements
- `api.fs.read("api/link.md")` - Creating relationships
- `api.fs.read("api/diagram.md")` - Diagram operations
