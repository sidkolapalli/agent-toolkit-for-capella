---
name: capella-diagrams
description: Capella diagrams (OAB, OAIB, OCB, SAB, SDFB, LAB, LDFB, PAB, PDFB, CDB and breakdowns). Use when the user asks for a diagram or view, or when a model change should be shown.
---

# Capella diagrams

Capella owns diagram geometry. Build every view with `api.diagram` inside a reviewed script (see
`capella-model-edits`) and let `layout(view, "auto")` place shapes, ports and routes. That keeps the
view under review and stable across Capella's refreshes; a hand-written `.aird` is neither.

## One question per view

| Question | Code |
|---|---|
| Which entities perform which activities? | OAB (alias OES) |
| Which operational activities interact? | OAIB |
| Which capabilities involve whom? | OCB |
| System boundary: system, actors, functions | SAB |
| Which system / logical / physical functions exchange what? | SDFB / LDFB / PDFB |
| Logical components and their exchanges | LAB |
| Physical components, nodes and links | PAB |
| How functions break down | SFB / LFB / PFB |
| Data types and exchange items | CDB |

Architecture blanks (SAB, LAB, PAB) target the layer's root component, e.g.
`api.layer("LA").system`; other targets and full names are in `capella_docs` `api/diagram.md`.
`api.diagram.listAvailableFormatted("<layer>")` lists what this Capella has installed.

Keep a view to about eight components. A bigger question gets an overview of the top-level
components plus one view per subsystem or functional chain, each named after its question.

## Steps

1. **Allocate first.** A function appears inside a component only once allocated to it. Done when
   every function you will show has its component.
2. **Build** in one script:

   ```groovy
   def view = api.diagram.find("Brake system - logical") ?:
              api.diagram.createSafe("LAB", "Brake system - logical", api.layer("LA").system)
   (components + functions).each { api.diagram.addSafe(view, it) }
   api.diagram.refresh(view)
   api.diagram.layout(view, "auto")   // last, after every add
   ```

3. **Verify** with one postcondition naming every element the view must show:
   `{"kind":"native_structure","check":{"kind":"diagram_shows","diagram":"Brake system - logical","elements":[{"id":"<uuid>"}]}}`.
   Done when the receipt reports it passed.

Layout strategies and styling: `capella_docs` `api/diagram-layout.md`, `api/style.md`.
