# Requirements Viewpoint Skill

Create and manage requirements using the Capella Requirements Viewpoint add-on.

## Prerequisites

The Requirements Viewpoint add-on must be installed in Capella.

## Creating a Requirement

**IMPORTANT**: Use this exact pattern. Do not modify the import or bundle loading code.

```groovy
import org.osgi.framework.FrameworkUtil

// Step 1: Get bundle context from api.class (NOT this.class)
def ctx = FrameworkUtil.getBundle(api.class).bundleContext

// Step 2: Find the requirements model bundle by exact name
def reqBundle = ctx.bundles.find { it.symbolicName == "org.polarsys.kitalpha.vp.requirements.model" }

// Step 3: Load factory and get eINSTANCE
def factory = reqBundle.loadClass("org.polarsys.kitalpha.vp.requirements.Requirements.RequirementsFactory").eINSTANCE

// Step 4: Create requirement
def req = factory.createRequirement()

// Step 5: Set properties (use reqIFLongName, NOT name)
req.reqIFLongName = "My Requirement"
req.reqIFText = "The system shall do something"

log.info("Created requirement: " + req.reqIFLongName)
req
```

## Requirement Properties

Requirements use ReqIF naming conventions:

| Property | Description |
|----------|-------------|
| `reqIFLongName` | The requirement name (use this instead of `name`) |
| `reqIFText` | The requirement text/description |
| `reqIFIdentifier` | Unique identifier |
| `reqIFDescription` | Additional description |

## Creating a Module (Requirement Container)

```groovy
import org.osgi.framework.FrameworkUtil

def ctx = FrameworkUtil.getBundle(api.class).bundleContext
def reqBundle = ctx.bundles.find { it.symbolicName == "org.polarsys.kitalpha.vp.requirements.model" }
def factory = reqBundle.loadClass("org.polarsys.kitalpha.vp.requirements.Requirements.RequirementsFactory").eINSTANCE

// Create module
def module = factory.createModule()
module.reqIFLongName = "System Requirements"

// Create requirements in module
def req1 = factory.createRequirement()
req1.reqIFLongName = "REQ-001: Performance"
req1.reqIFText = "System response time shall be less than 100ms"

def req2 = factory.createRequirement()
req2.reqIFLongName = "REQ-002: Availability"
req2.reqIFText = "System availability shall be 99.9%"

// Add to module
module.ownedRequirements.add(req1)
module.ownedRequirements.add(req2)

log.info("Created module with ${module.ownedRequirements.size()} requirements")
module
```

## Common Errors and Fixes

### Error: "No such property: name"
**Wrong**: `req.name = "..."`
**Correct**: `req.reqIFLongName = "..."`

### Error: "Cannot invoke method getBundleContext() on null object"
**Wrong**: `FrameworkUtil.getBundle(this.class)`
**Correct**: `FrameworkUtil.getBundle(api.class)`

### Error: "ClassNotFoundException"
**Wrong**: Using `api.class.classLoader.loadClass()` for Requirements
**Correct**: Use bundle loading pattern shown above
