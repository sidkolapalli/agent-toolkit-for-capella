# Capella API

You can manipulate Capella models using Groovy code.

## How to Use This Documentation

1. **List directories**: `api.fs.list("api/")` - see available API methods
2. **Read docs**: `api.fs.read("api/create.md")` - learn how to use a method
3. **Search**: `api.fs.search("component")` - find relevant documentation
4. **Search with detail**: `api.fs.search("component", "name")` - name only, "description", or "full"
5. **Skills**: `api.fs.list("skills/")` - see reusable patterns

### Saving Your Own Skills

You can save reusable code patterns for future use:

```groovy
// Save a skill
api.fs.saveSkill("my-pattern", '''
/**
 * Creates a component with standard ports.
 */
def comp = api.create("LA", "LogicalComponent", name)
api.create("ComponentPort", "in", comp)
api.create("ComponentPort", "out", comp)
comp
''')

// List saved skills
api.fs.listSkills()  // ["my-pattern"]

// Load and use a skill
def code = api.fs.loadSkill("my-pattern")

// Delete a skill
api.fs.deleteSkill("my-pattern")
```

Skills are saved to `~/.capella-ai/skills/` and persist across sessions.

## Quick Start

```groovy
// Find an element
def func = api.find("SystemFunction", "Process Data")

// Create an element
def newFunc = api.create("SA", "SystemFunction", "New Function")

// Link elements
api.link(func, newFunc, "FunctionalExchange")

// Set properties
api.set(newFunc, "description", "Processes incoming data")

// Always log results
log.info("Created: ${api.name(newFunc)}")
```

## Available Bindings

- `api` - Main Capella API (find, create, link, etc.)
- `api.fs` - This documentation system
- `api.diagram` - Diagram operations
- `log` - Logger for output (log.info, log.warn, log.error)
- `session` - Raw Capella Session (advanced)

## Directory Structure

- `api/` - Core API method documentation
- `metamodel/` - Capella element types by layer (OA, SA, LA, PA)
- `skills/` - Reusable patterns for common tasks

## ARCADIA Layers

Capella follows the ARCADIA methodology with these layers:

| Layer | Prefix | Description |
|-------|--------|-------------|
| OA | Operational | Operational Analysis - what users need |
| SA | System | System Analysis - system functions |
| LA | Logical | Logical Architecture - logical components |
| PA | Physical | Physical Architecture - physical implementation |

## Next Steps

1. `api.fs.read("api/SKILL.md")` - Learn all API methods
2. `api.fs.list("skills/")` - See available patterns
3. `api.fs.search("your topic")` - Find specific documentation
