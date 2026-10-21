---
name: capella-diagrams
description: Create, populate and lay out Capella diagrams (OAIB, OAB, SAB, LAB, PAB, data flow and breakdown views) through the Capella API in a reviewed edit. Use when the user asks for a diagram or view, or when a modelling change should be shown.
---

# Capella diagrams

Diagrams are made by a reviewed script (see `capella-model-edits`) using `api.diagram`. **Never
write or patch `.aird` / Sirius XML by hand.** It bypasses Capella's diagram rules, and Capella
redraws or breaks hand-placed views.

## Pick the view for the question

| Question | View | Target |
|---|---|---|
| Which operational activities interact? | OAIB (Operational Activity Interaction Blank) | operational activity package |
| Which entities do which activities? | OAB (Operational Entity Blank) | EntityPkg or an entity |
| Which capabilities, involving whom? | OCB (Operational Capabilities Blank) | OperationalCapabilityPkg or a capability |
| System boundary: system, actors, functions | SAB | root System component |
| Which system functions exchange what? | SDFB | SA function package or function |
| Logical components and their exchanges | LAB | root logical component (`api.layer("LA").system`) |
| Logical function flow | LDFB | LA function package or function |
| Physical components, nodes and links | PAB | root physical component |
| Physical function flow | PDFB | PA function package or function |
| Data types and exchange items | CDB | data package |
| How an activity breaks down | Operational Activity Breakdown | an OperationalActivity |

Check what is installed before guessing a name: `api.diagram.listAvailableFormatted("SA")`.

## Keep it readable

About eight components per view. For more, make an overview of the top-level components and then
one view per subsystem or functional chain, named after what it shows.

## Build it

```groovy
def view = api.diagram.find("Brake system - logical") ?:
           api.diagram.createSafe("LAB", "Brake system - logical", api.layer("LA").system)
[controller, sensor].each { api.diagram.addSafe(view, it) }   // components
functions.each { api.diagram.addSafe(view, it) }               // shown inside their components only if allocated
api.diagram.refresh(view)
api.diagram.layout(view, "auto")                               // ELK layered layout + orthogonal wiring
log.info("View ${view.name} shows ${functions.size()} functions")
```

- Allocate functions before adding them; an unallocated function has no box to sit in.
- Run `layout(view, "auto")` last, after every add. Don't position shapes, ports or bendpoints by hand.
- Verify with a postcondition: `{"kind":"native_structure","check":{"kind":"diagram_shows","diagram":"Brake system - logical","elements":[{"id":"<uuid>"}, ...]}}`.

Full API: `capella_docs` paths `api/diagram.md`, `api/diagram-layout.md`, `api/style.md`,
`skills/diagram/SKILL.md`.
