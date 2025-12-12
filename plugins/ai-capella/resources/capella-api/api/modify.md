# Modifying Elements

Change properties, rename, or delete elements.

## Set Property

```groovy
// Set any property by name
api.set(element, "propertyName", value)

// Common properties
api.set(func, "description", "Handles sensor data processing")
api.set(func, "name", "New Name")
api.set(comp, "abstract", true)
```

## Get Property

```groovy
// Get property value
def desc = api.get(element, "description")
def name = api.get(element, "name")

log.info("Description: ${desc}")
```

## Rename

```groovy
// Shortcut for setting name
api.rename(element, "New Element Name")

// Equivalent to:
api.set(element, "name", "New Element Name")
```

## Delete

```groovy
// Delete an element
api.delete(element)

// Delete with confirmation
def toDelete = api.find("SystemFunction", "Obsolete Function")
if (toDelete != null) {
    api.delete(toDelete)
    log.info("Deleted obsolete function")
}
```

## Common Properties

| Property | Types | Description |
|----------|-------|-------------|
| `name` | All | Element name |
| `description` | All | Documentation |
| `summary` | All | Short description |
| `visible_in_doc` | All | Show in documentation |
| `abstract` | Components | Is abstract? |

## Bulk Updates

```groovy
// Update all elements of a type
def functions = api.findAll("SystemFunction")
functions.each { func ->
    def desc = api.get(func, "description")
    if (desc == null || desc.isEmpty()) {
        api.set(func, "description", "TODO: Add description")
    }
}
log.info("Updated ${functions.size()} functions")
```

## Tips

- Property names are case-insensitive
- Changes are automatically saved
- Delete removes the element and all its contents
