# End-Product Breakdown Structure (EPBS)

EPBS defines configuration items and their realization of the PA physical architecture.

## Configuration Items

```groovy
// Get the EPBS architecture; both forms refer to the same perspective
def epbs = api.epbs()
def epbsRoot = api.layer("EPBS")

// Create a root configuration item and a nested item
def flightItem = api.create("EPBS", "ConfigurationItem", "Flight Control Item")
def navigationItem = api.create("ConfigurationItem", "Navigation Item", flightItem)
```

## Physical Artifact Realization

```groovy
// CI is the source; the PA physical component is the target
def firmware = api.find("PhysicalComponent", "Flight Firmware")
api.link(flightItem, firmware, "PhysicalArtifactRealization")

// Generic realization dispatch supports the same endpoint pair
def navigationSoftware = api.find("PhysicalComponent", "Navigation Software")
api.link(navigationItem, navigationSoftware, "realization")
```

Use endpoints in the active session. Default architecture-level creation places configuration items beneath the existing unique product root; use an explicit parent for a particular breakdown. Same-named native Parts also exist, so use `api.find("ConfigurationItem", name)` to retrieve the item. EPBS supports configuration item creation, decomposition and typed physical artifact realization. It does not contain functions, functional exchanges or component exchanges. Generate code for review and confirmation before model changes are applied.
