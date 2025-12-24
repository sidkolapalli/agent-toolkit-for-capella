# Visual Style API for Diagram Highlighting

## Issue Details
- **ID**: #114
- **Title**: Implement `api.style` for Programmatic Diagram Formatting
- **Description**: 
  The current `DiagramAPI` allows creating diagrams and adding nodes, but it offers zero control over their appearance. All nodes appear with default colors and styles.
  
  An intelligent Assistant needs to use **Visual Feedback** to communicate with the user. For example:
  *   "I have highlighted the invalid components in **Red**."
  *   "I have colored the critical path in **Orange**."
  *   "I have hidden the non-essential layers."
  
  Without this, the Assistant's output is limited to text logs, which breaks the "visual" nature of MBSE.

- **Steps to Reproduce**:
  1. Open a ChatView session.
  2. Create a diagram: `def d = api.diagram.create(...)`.
  3. Try to change a node's color: `api.diagram.setStyle(node, [color: "RED"])`.
  4. **Observation**: Method does not exist.

- **Expected Result**: 
  The diagram updates immediately to show the node in red.

- **Actual Result**: 
  User has to manually format the diagram after the Assistant generates it.

- **Severity**: **Minor** (Usability/UX feature)
- **Priority**: **Low** (Nice-to-have, but high "Wow" factor)
- **Environment**: All OS, Capella 6.0+, Sirius

## Linking
- **Related Issues**: 
  - `element-description-api.md` (Visual understanding vs. visual manipulation)
  - `validation-api.md` (Validation errors should be visualized)
- **Dependencies**: 
  - None.

## Acceptance Criteria

### 1. Color Customization
- **Specific**: Implement `api.style.setColor(DDiagramElement element, String colorName)`.
- **Measurable**: The element's `.getOwnedStyle().getLabelColor()` must match the RGB of the requested color.
- **Testable**: 
  ```groovy
  def diag = api.diagram.lastOpened()
  def node = api.diagram.findNode(diag, myComponent)
  api.style.setColor(node, "RED")
  ```

### 2. Visibility Toggle
- **Specific**: Implement `api.style.hide(DDiagramElement element)` and `api.style.show(DDiagramElement element)`.
- **Measurable**: `element.isVisible()` returns the expected boolean.

### 3. Layer Management
- **Specific**: Implement `api.style.activateLayer(DDiagram diag, String layerName)`.
- **Measurable**: The layer (e.g., "Functional Chains") becomes active in the Sirius viewpoint.

### 4. Batch Styling
- **Specific**: `api.style.apply(List<EObject> semanticElements, Map styles)`.
- **Measurable**: Should find the graphical representations for all listed semantic elements on the current diagram and apply the style in one transaction.

## Implementation Notes
- Requires accessing the Sirius `DNode` / `DContainer` models.
- Color names should map to Sirius `VSM` colors or standard SVG names.
- Must handle `Refresh` automatically to update the editor.
