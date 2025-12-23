# Capella API

You can manipulate Capella models using Groovy code.

## How to Use This Documentation

1. **List directories**: `api.fs.list("api/")` - see available API methods
2. **Read docs**: `api.fs.read("api/create.md")` - learn how to use a method
3. **Search**: `api.fs.search("component")` - find relevant documentation
4. **Search with detail**: `api.fs.search("component", "name")` - name only, "description", or "full"
5. **Skills**: `api.fs.list("skills/")` - see reusable patterns
6. **Skill metadata**: `api.fs.listSkillMetadata()` - view skill summaries without loading full files

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

// Read the saved SKILL.md (Anthropic folder format)
api.fs.list("user-skills/")                     // ["my-pattern/"]
api.fs.read("user-skills/my-pattern/SKILL.md")  // contains YAML frontmatter + code

// Load and use the Groovy code (extracted from SKILL.md)
def code = api.fs.loadSkill("my-pattern")

// Delete a skill
api.fs.deleteSkill("my-pattern")
```

Skills are saved to `~/.capella-ai/skills/<skill-name>/SKILL.md` and persist across sessions.

## Quick Start

```groovy
// Get architecture layer root
def saLayer = api.layer("SA")  // Returns SystemAnalysis root
def laLayer = api.layer("LA")  // Returns LogicalArchitecture root

// Find an element by type and name
def func = api.find("SystemFunction", "Process Data")

// Create an element in a layer
def newFunc = api.create("SA", "SystemFunction", "New Function")

// Link elements
api.link(func, newFunc, "FunctionalExchange")

// Set properties
api.set(newFunc, "description", "Processes incoming data")

// Create a diagram using the layer root
def diagram = api.diagram.create("SAB", saLayer)

// Always log results
log.info("Created: ${api.name(newFunc)}")
```

## Key Methods

| Method | Purpose |
|--------|---------|
| `api.layer("SA")` | Get layer root (OA, SA, LA, PA) |
| `api.find(type, name)` | Find element by type and name |
| `api.findAll(type)` | Find all elements of type |
| `api.create(layer, type, name)` | Create element in layer |
| `api.link(src, tgt, type)` | Create relationship |
| `api.set(elem, prop, val)` | Set property value |
| `api.allocate(func, comp)` | Allocate function to component |
| `api.diagram.create(type, target)` | Create diagram |
| `api.diagram.add(diagram, elem)` | Add element to diagram |

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

## Advanced: Raw API Access

When standard API methods don't support your use case, use `api.tx {}` to access Capella's internal APIs directly:

**Sandbox note**: `ClassLoader` and Java reflection are blocked in code execution. Use the pre-bound factory instances instead:
`CapellacoreFactory`, `OaFactory`, `CtxFactory`, `LaFactory`, `PaFactory`, `FaFactory`, `CsFactory`, `InformationFactory`.

```groovy
api.tx {
    // Create a Constraint on a function
    def func = api.findAll("SystemFunction")[0]
    def constraint = CapellacoreFactory.createConstraint()
    constraint.name = "Timing Constraint"
    func.ownedConstraints.add(constraint)
    
    log.info("Created: " + constraint.name)
    constraint
}
```

### Loading Factories

Use the pre-bound factory variables (no classloading needed):

| Purpose | Factory Class |
|---------|---------------|
| OA elements | `OaFactory` |
| SA elements | `CtxFactory` |
| LA elements | `LaFactory` |
| PA elements | `PaFactory` |
| Functions | `FaFactory` |
| Components | `CsFactory` |
| Core (Constraints) | `CapellacoreFactory` |
| Data types | `InformationFactory` |

**Note**: Requirements require the Capella Requirements Viewpoint add-on to be installed.

### Exploring the Model

```groovy
// Navigate model structure
def project = session.semanticResources[0].contents[0]
project.eContents().each { log.info(it.eClass().name) }

// Find available features on an element
element.eClass().eAllStructuralFeatures.each { log.info(it.name) }

// Get a factory for any EClass
def eClass = org.polarsys.capella.ai.model.ElementFactory.findEClass("SystemUserRequirement")
def factory = eClass.EPackage.EFactoryInstance
```

### When to Use api.tx {}

- Standard API returns null or fails
- Need to create element types not covered by wrappers
- Need to set properties not exposed by api.set()
- Need to navigate complex containment hierarchies

## Next Steps

1. `api.fs.read("api/SKILL.md")` - Learn all API methods
2. `api.fs.list("skills/")` - See available patterns
3. `api.fs.search("your topic")` - Find specific documentation
