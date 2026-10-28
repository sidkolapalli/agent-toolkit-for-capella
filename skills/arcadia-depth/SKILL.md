---
name: arcadia-depth
description: Arcadia constructs beyond functions and components. Use when a task involves modes or states, exchange items or the data model, interfaces, missions, capability include/extend/generalization, operational roles or communication means, control functions (duplicate, split, gather, route, select), property values, exchange categories, scenarios, functional chains or operational processes, physical links and paths, or transitions between perspectives.
---

# Arcadia depth

Each construct below has a method rule the outline of Arcadia leaves out and an API pattern verified
on Eclipse Capella® 7. Read the file for every construct the task touches before proposing; read its
`capella_docs` page (`capella-help/<topic>`) when the rule leaves a choice open.

| Construct | File | `capella-help/` |
|---|---|---|
| Modes and states, availability of functions | [MODES.md](MODES.md) | `state-machines`, `diagram-msm` |
| Exchange items, data types, units | [DATA.md](DATA.md) | `exchange-items`, `data-types`, `classes`, `data-modeling-method` |
| Interfaces on components and ports | [INTERFACES.md](INTERFACES.md) | `fa-to-interfaces`, `exchange-items` |
| Missions and capability exploitation | [MISSIONS.md](MISSIONS.md) | `diagram-mcb`, `diagram-crb` |
| Capability include, extend, generalization | [CAPABILITIES.md](CAPABILITIES.md) | `diagram-mcb`, `diagram-ocb` |
| Operational roles, communication means | [OPERATIONAL.md](OPERATIONAL.md) | `diagram-oeb`, `diagram-oaib` |
| Control functions in a dataflow | [CONTROL.md](CONTROL.md) | `diagram-sdfb`, `diagram-ldfb` |
| Property values | [PROPERTIES.md](PROPERTIES.md) | `constraints` |
| Exchange categories | [CATEGORIES.md](CATEGORIES.md) | `categories` |
| Scenarios with their messages (FS, ES) | [SCENARIOS.md](SCENARIOS.md) | `diagram-fs`, `diagram-es` |
| Functional chains that branch, operational processes | [CHAINS.md](CHAINS.md) | `functional-chains-physical-paths`, `diagram-fcd` |
| Physical links and paths carrying component exchanges | [PHYSICAL.md](PHYSICAL.md) | `functional-chains-physical-paths`, `diagram-pab` |
| Transitions to the next perspective (LA to PA, PA to EPBS) | [TRANSITIONS.md](TRANSITIONS.md) | `transitions` |

Done when every construct the task names is built with its file's pattern and its file's check holds.
