# Capella Metamodel

Capella follows the ARCADIA methodology with four architecture layers.

## Layer Overview

```
OA (Operational Analysis)
  └── What users/operators need to do
  
SA (System Analysis)  
  └── What the system must do
  
LA (Logical Architecture)
  └── How it's logically organized
  
PA (Physical Architecture)
  └── How it's physically implemented
```

## Layer Navigation

```groovy
// Get layer roots
def oa = api.layer("OA")  // or "operational"
def sa = api.layer("SA")  // or "system"
def la = api.layer("LA")  // or "logical"
def pa = api.layer("PA")  // or "physical"

// Explore layer contents
api.children(sa).each { child ->
    log.info("${api.type(child)}: ${api.name(child)}")
}
```

## Layer Details

- `api.fs.read("metamodel/oa/SKILL.md")` - Operational Analysis
- `api.fs.read("metamodel/sa/SKILL.md")` - System Analysis
- `api.fs.read("metamodel/la/SKILL.md")` - Logical Architecture
- `api.fs.read("metamodel/pa/SKILL.md")` - Physical Architecture

## Cross-Layer Relationships

```groovy
// Propagate from SA to LA
def sysFunc = api.find("SystemFunction", "Process Data")
def logFunc = api.propagate(sysFunc, "LA")

// Creates LogicalFunction with realization link
log.info("Created: ${api.name(logFunc)}")
```

## Type Hierarchy

Most Capella types share common patterns:
- All have `name` and `description` properties
- Functions can be linked with exchanges
- Components can contain sub-components
- All can be added to diagrams
