# Enhance Diagram API Polymorphism (Support Visual Elements)

## Issue Details
- **ID**: #129
- **Title**: Update `api.diagram.align` and `distribute` to accept `DDiagramElement`
- **Description**: 
  The current signature `align(DRepresentation, List<EObject>, String)` forces the user/LLM to pass semantic elements.
  However, in many scripts (especially those iterating `diagram.eAllContents()`), the available objects are `DDiagramElement` (visual nodes).
  
  Currently, if the LLM passes a list of visual elements, the script fails with a `MethodSignature` error.
  
  The API should be polymorphic:
  - `align(..., List<Object> elements, ...)`
  - Internally check if objects are `EObject` or `DDiagramElement` and extract the target automatically.

- **Steps to Reproduce**:
  1. Script: `def visuals = diagram.getOwnedDiagramElements(); api.diagram.align(diagram, visuals, "top")`
  2. Result: `MissingMethodException` (No signature matches List<DDiagramElement>).

- **Expected Result**: 
  The API should handle visual elements gracefully by extracting their semantic targets or using them directly for layout.

- **Actual Result**: 
  Script crash.

- **Severity**: **Minor** (Usability)
- **Priority**: **Medium**
- **Environment**: All

## Linking
- **Related Issues**: #38 (Layout API)
- **Dependencies**: None.

## Acceptance Criteria
- [ ] `DiagramAPI.align` accepts `List<?>`.
- [ ] `DiagramAPI.distribute` accepts `List<?>`.
- [ ] Logic gracefully handles `DDiagramElement` by calling `.getTarget()`.
- [ ] Unit test verifies mixed list support.
