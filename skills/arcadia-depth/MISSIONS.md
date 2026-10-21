# Missions and capability exploitation

**Rule.** In SA a **mission** is a high-level goal the system serves for its users; it **exploits**
capabilities, and every system capability should be exploited by some mission (otherwise nothing
justifies it). Missions live in `ownedMissionPkg`; the exploitation is a child of the mission.
How capabilities depend on each other is in [CAPABILITIES.md](CAPABILITIES.md).

```groovy
def mission = api.create('Mission', 'Compete in dynamic events', api.layer('SA').ownedMissionPkg)
def ex = api.create('CapabilityExploitation', 'exploits ' + capability.name, mission)
api.set(ex, 'capability', capability)
```

**Check.** Each mission has at least one exploitation; every SA capability is the `capability` of some
exploitation.
