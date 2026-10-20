# api.diagram.layout(), align(), distribute()

Control the visual arrangement of diagram elements.

## Layout Strategies

```groovy
// Auto-layout (smart default): hierarchical layered layout by Eclipse ELK.
// Shapes flow left to right along their connections, containers (components,
// actors) grow to fit the functions inside them, and nothing overlaps.
api.diagram.layout(diagram, "auto")

// Grid layout (compact rows/columns)
api.diagram.layout(diagram, "grid")

// Flow layout (left-to-right)
api.diagram.layout(diagram, "flow")

// Radial layout (circular)
api.diagram.layout(diagram, "radial")

// Layered layout: same as "auto"
api.diagram.layout(diagram, "layered")
```

Every layout also tidies the wiring in the same undoable command: connections switch to
orthogonal (Manhattan) routing, stale manual bendpoints are removed, and connected border
ports move to the side of their parent that faces what they connect to. Run `layout` after
adding elements rather than positioning ports or bendpoints by hand.

Layout does not make an overloaded view readable: keep a diagram to about eight components and
split larger content into one view per subsystem (see the diagram skill).

## Alignment

Aligns the **selected** elements (or passed list).

```groovy
// Align selected elements to the left
api.diagram.align(diagram, elements, "left")

// Align to top
api.diagram.align(diagram, elements, "top")

// Center horizontally
api.diagram.align(diagram, elements, "hcenter")
```

## Distribution

Distributes elements evenly.

```groovy
// Distribute horizontally (equal spacing)
api.diagram.distribute(diagram, elements, "horizontal")

// Distribute vertically
api.diagram.distribute(diagram, elements, "vertical")
```

## Examples

```groovy
def diag = api.diagram.create("SAB", saLayer)
def comps = api.findAll("SystemComponent")
api.diagram.addAll(diag, comps)

// Arrange in a grid
api.diagram.layout(diag, "grid")

// Later, align specific nodes
def critical = comps.findAll { it.name.contains("Critical") }
api.diagram.align(diag, critical, "top")
```
