package org.polarsys.capella.ai.scripting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.sirius.business.api.dialect.DialectManager;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.viewpoint.DRepresentation;
import org.polarsys.capella.ai.tools.DiagramCommands;

/**
 * Diagram operations API for Groovy scripts.
 * Wraps DiagramCommands for a cleaner, script-friendly interface.
 *
 * <p>This provides full diagram creation capabilities that Python4Capella cannot do,
 * using the same Sirius/GMF APIs that the Capella UI uses internally.
 *
 * <p>Example usage in scripts:
 * <pre>{@code
 * def diagram = api.diagram.create("SAB", "System Overview", systemRoot)
 * api.diagram.add(diagram, component1)
 * api.diagram.add(diagram, component2)
 * api.diagram.layout(diagram, "flow")
 * }</pre>
 */
public final class DiagramAPI {

    private final Session session;

    /**
     * Create a DiagramAPI for the given session.
     */
    public DiagramAPI(Session session) {
        this.session = session;
    }

    // ========== Creation ==========

    /**
     * Create and open a new diagram.
     *
     * @param diagramType the diagram type (e.g., "SAB", "LAB", "PAB", "OAB")
     * @param name the diagram name
     * @param target the semantic element to create the diagram for
     * @return the created diagram representation
     * @throws IllegalStateException if no active session
     * @throws IllegalArgumentException if target is null or diagram type is unknown
     */
    public DRepresentation create(
        String diagramType,
        String name,
        EObject target
    ) {
        if (session == null) {
            throw new IllegalStateException("No active session");
        }
        if (target == null) {
            throw new IllegalArgumentException("Target element cannot be null");
        }
        String descriptionName = resolveDiagramDescription(diagramType);
        if (descriptionName == null) {
            throw new IllegalArgumentException(
                "Unknown diagram type: " +
                    diagramType +
                    ". Valid types: OAB, SAB, LAB, PAB, SDFB, LDFB, PDFB, CDB, ES, FS, IS"
            );
        }

        // Find the correct target element if the given target is a layer root
        EObject actualTarget = findDiagramTarget(target, descriptionName);
        if (actualTarget == null) {
            actualTarget = target;
        }

        return DiagramCommands.createAndOpenDiagram(
            actualTarget,
            descriptionName,
            name
        );
    }

    /**
     * Find the correct target element for a diagram type.
     * Architecture blank diagrams need ComponentPkg, not the layer root.
     */
    private EObject findDiagramTarget(EObject target, String descriptionName) {
        if (target == null) return null;

        String targetClass = target.eClass().getName();

        // If target is already a valid type, use it
        if (
            targetClass.contains("ComponentPkg") ||
            targetClass.contains("Component")
        ) {
            return target;
        }

        // If target is a layer root, find the appropriate child
        if (
            targetClass.equals("OperationalAnalysis") ||
            targetClass.equals("SystemAnalysis") ||
            targetClass.equals("LogicalArchitecture") ||
            targetClass.equals("PhysicalArchitecture")
        ) {
            // Look for ComponentPkg or EntityPkg
            for (EObject child : target.eContents()) {
                String childClass = child.eClass().getName();
                if (
                    childClass.contains("ComponentPkg") ||
                    childClass.contains("EntityPkg")
                ) {
                    return child;
                }
            }
        }

        return target;
    }

    /**
     * Create a diagram with auto-generated name.
     */
    public DRepresentation create(String diagramType, EObject target) {
        String name = diagramType + " - " + getElementName(target);
        return create(diagramType, name, target);
    }

    /**
     * Create a diagram (alternate argument order for LLM compatibility).
     * Accepts create(type, target, name) in addition to create(type, name, target).
     */
    public DRepresentation create(
        String diagramType,
        EObject target,
        String name
    ) {
        return create(diagramType, name, target);
    }

    // ========== Finding ==========

    /**
     * Find a diagram by name.
     *
     * @param name the diagram name or UID to find
     * @return the found diagram representation
     * @throws IllegalStateException if no active session
     * @throws IllegalArgumentException if name is null/blank or diagram not found
     */
    public DRepresentation find(String name) {
        if (session == null) {
            throw new IllegalStateException("No active session");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                "Diagram name cannot be null or blank"
            );
        }
        DRepresentation result = DiagramCommands.findRepresentationByNameOrUid(
            session,
            name
        );
        if (result == null) {
            List<String> available = list()
                .stream()
                .map(d -> d.getName())
                .limit(5)
                .toList();
            throw new IllegalArgumentException(
                "Diagram '" +
                    name +
                    "' not found. " +
                    (available.isEmpty()
                        ? "No diagrams in model."
                        : "Available: " + available)
            );
        }
        return result;
    }

    /**
     * Find a diagram by UID.
     */
    public DRepresentation findByUid(String uid) {
        if (session == null || uid == null) {
            return null;
        }
        return DiagramCommands.findRepresentationByUid(session, uid);
    }

    /**
     * Get all diagrams in the session.
     */
    public List<DRepresentation> list() {
        if (session == null) {
            return List.of();
        }
        try {
            Collection<DRepresentation> reps =
                DialectManager.INSTANCE.getAllRepresentations(session);
            return new ArrayList<>(reps);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Get the last opened/created diagram.
     */
    public DRepresentation lastOpened() {
        return DiagramCommands.getLastOpened();
    }

    // ========== Modification ==========

    /**
     * Add a semantic element to a diagram.
     *
     * @param diagram the diagram to add to
     * @param element the semantic element to add
     * @return true if the element was added
     * @throws IllegalStateException if no active session
     * @throws IllegalArgumentException if diagram or element is null, or add fails
     */
    public boolean add(DRepresentation diagram, EObject element) {
        if (session == null) {
            throw new IllegalStateException("No active session");
        }
        if (diagram == null) {
            throw new IllegalArgumentException("Diagram cannot be null");
        }
        if (element == null) {
            throw new IllegalArgumentException("Element to add cannot be null");
        }
        boolean success = DiagramCommands.createNode(session, diagram, element);
        if (!success) {
            throw new IllegalArgumentException(
                "Failed to add " +
                    element.eClass().getName() +
                    " to diagram '" +
                    diagram.getName() +
                    "'. Element may not be displayable in this diagram type."
            );
        }
        return true;
    }

    /**
     * Add multiple elements to a diagram.
     */
    public int addAll(DRepresentation diagram, List<EObject> elements) {
        if (session == null || diagram == null || elements == null) {
            return 0;
        }
        int count = 0;
        for (EObject element : elements) {
            if (add(diagram, element)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Add elements by their UUIDs.
     */
    public int addByUuids(DRepresentation diagram, List<String> uuids) {
        if (session == null || diagram == null || uuids == null) {
            return 0;
        }
        return DiagramCommands.addElementsByUuid(session, diagram, uuids);
    }

    /**
     * Create an edge between two elements in the diagram.
     */
    public boolean addEdge(
        DRepresentation diagram,
        EObject source,
        EObject target
    ) {
        if (
            session == null ||
            diagram == null ||
            source == null ||
            target == null
        ) {
            return false;
        }
        return DiagramCommands.createEdge(session, diagram, source, target);
    }

    /**
     * Remove an element's view from the diagram (does not delete the semantic element).
     *
     * @param diagram the diagram to remove from
     * @param element the semantic element whose view should be removed
     * @return true if the view was removed
     * @throws IllegalStateException if no active session
     * @throws IllegalArgumentException if diagram or element is null, or removal fails
     */
    public boolean remove(DRepresentation diagram, EObject element) {
        if (session == null) {
            throw new IllegalStateException("No active session");
        }
        if (diagram == null) {
            throw new IllegalArgumentException("Diagram cannot be null");
        }
        if (element == null) {
            throw new IllegalArgumentException(
                "Element to remove cannot be null"
            );
        }
        boolean success = DiagramCommands.removeViewElement(
            session,
            diagram,
            element
        );
        if (!success) {
            throw new IllegalArgumentException(
                "Failed to remove " +
                    element.eClass().getName() +
                    " from diagram '" +
                    diagram.getName() +
                    "'. Element may not exist in this diagram."
            );
        }
        return true;
    }

    // ========== Layout ==========

    /**
     * Auto-layout the diagram using default strategy.
     */
    public boolean layout(DRepresentation diagram) {
        return layout(diagram, "auto");
    }

    /**
     * Layout the diagram using a specific strategy.
     *
     * @param diagram the diagram to layout
     * @param strategy layout strategy: "auto", "grid", "flow", "radial", "layered",
     *                 "left", "right", "top", "bottom", "hcenter", "vcenter",
     *                 "hspace", "vspace"
     * @return true if layout was applied
     * @throws IllegalStateException if no active session
     * @throws IllegalArgumentException if diagram is null or layout fails
     */
    public boolean layout(DRepresentation diagram, String strategy) {
        if (session == null) {
            throw new IllegalStateException("No active session");
        }
        if (diagram == null) {
            throw new IllegalArgumentException("Diagram cannot be null");
        }
        boolean success = DiagramCommands.arrangeSelection(
            session,
            diagram,
            strategy
        );
        if (!success) {
            throw new IllegalArgumentException(
                "Failed to apply layout '" +
                    strategy +
                    "' to diagram '" +
                    diagram.getName() +
                    "'. " +
                    "Valid strategies: auto, grid, flow, radial, layered, left, right, top, bottom"
            );
        }
        return true;
    }

    /**
     * Refresh the diagram to sync with model changes.
     */
    public void refresh(DRepresentation diagram) {
        if (diagram != null) {
            DiagramCommands.arrange(diagram);
        }
    }

    // ========== Diagram Management ==========

    /**
     * Rename a diagram.
     *
     * @param diagram the diagram to rename
     * @param newName the new name for the diagram
     * @return true if renamed successfully
     * @throws IllegalStateException if no active session
     * @throws IllegalArgumentException if diagram or newName is null, or rename fails
     */
    public boolean rename(DRepresentation diagram, String newName) {
        if (session == null) {
            throw new IllegalStateException("No active session");
        }
        if (diagram == null) {
            throw new IllegalArgumentException("Diagram cannot be null");
        }
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException(
                "New diagram name cannot be null or blank"
            );
        }
        boolean success = DiagramCommands.renameDiagram(
            session,
            diagram,
            newName
        );
        if (!success) {
            throw new IllegalArgumentException(
                "Failed to rename diagram '" +
                    diagram.getName() +
                    "' to '" +
                    newName +
                    "'"
            );
        }
        return true;
    }

    /**
     * Delete a diagram.
     *
     * @param diagram the diagram to delete
     * @return true if deleted successfully
     * @throws IllegalStateException if no active session
     * @throws IllegalArgumentException if diagram is null or delete fails
     */
    public boolean delete(DRepresentation diagram) {
        if (session == null) {
            throw new IllegalStateException("No active session");
        }
        if (diagram == null) {
            throw new IllegalArgumentException("Diagram cannot be null");
        }
        String diagramName = diagram.getName();
        boolean success = DiagramCommands.deleteDiagram(session, diagram);
        if (!success) {
            throw new IllegalArgumentException(
                "Failed to delete diagram '" + diagramName + "'"
            );
        }
        return true;
    }

    /**
     * Duplicate a diagram.
     *
     * @param diagram the diagram to duplicate
     * @param newName the name for the duplicated diagram
     * @return true if duplicated successfully
     * @throws IllegalStateException if no active session
     * @throws IllegalArgumentException if diagram or newName is null, or duplication fails
     */
    public boolean duplicate(DRepresentation diagram, String newName) {
        if (session == null) {
            throw new IllegalStateException("No active session");
        }
        if (diagram == null) {
            throw new IllegalArgumentException("Diagram cannot be null");
        }
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException(
                "New diagram name cannot be null or blank"
            );
        }
        boolean success = DiagramCommands.duplicateDiagram(
            session,
            diagram,
            newName
        );
        if (!success) {
            throw new IllegalArgumentException(
                "Failed to duplicate diagram '" +
                    diagram.getName() +
                    "' as '" +
                    newName +
                    "'"
            );
        }
        return true;
    }

    // ========== View Positioning ==========

    /**
     * Set the position and size of an element's view in the diagram.
     *
     * @param diagram the diagram
     * @param element the semantic element
     * @param x x position (-1 to keep current)
     * @param y y position (-1 to keep current)
     * @param width width (0 to keep current)
     * @param height height (0 to keep current)
     * @return true if bounds were set
     */
    public boolean setBounds(
        DRepresentation diagram,
        EObject element,
        int x,
        int y,
        int width,
        int height
    ) {
        if (session == null || diagram == null || element == null) {
            return false;
        }
        return DiagramCommands.setViewBounds(
            session,
            diagram,
            element,
            x,
            y,
            width,
            height
        );
    }

    /**
     * Move an element's view to a specific position.
     */
    public boolean move(
        DRepresentation diagram,
        EObject element,
        int x,
        int y
    ) {
        return setBounds(diagram, element, x, y, 0, 0);
    }

    /**
     * Resize an element's view.
     */
    public boolean resize(
        DRepresentation diagram,
        EObject element,
        int width,
        int height
    ) {
        return setBounds(diagram, element, -1, -1, width, height);
    }

    // ========== Helper Methods ==========

    /**
     * Resolve diagram type abbreviation to full description name.
     *
     * @param type diagram type abbreviation (e.g., "SAB", "LAB")
     * @return the full description name, or null if type is unknown
     */
    private String resolveDiagramDescription(String type) {
        if (type == null) {
            return null;
        }
        return switch (type.toUpperCase()) {
            case "OAB", "OPERATIONAL" -> "Operational Activity Blank";
            case "OAIB" -> "Operational Interaction Blank";
            case "OCB" -> "Operational Capabilities Blank";
            case "OES" -> "Operational Entity Blank";
            case "SAB", "SYSTEM" -> "System Architecture Blank";
            case "SDFB" -> "System Data Flow Blank";
            case "SFB" -> "[SFBD] System Function Breakdown";
            case "LAB", "LOGICAL" -> "Logical Architecture Blank";
            case "LDFB" -> "Logical Data Flow Blank";
            case "LFB" -> "[LFBD] Logical Function Breakdown";
            case "PAB", "PHYSICAL" -> "Physical Architecture Blank";
            case "PDFB" -> "Physical Data Flow Blank";
            case "PFB" -> "[PFBD] Physical Function Breakdown";
            case "CDB" -> "Class Diagram Blank";
            case "ES", "SCENARIO" -> "Exchange Scenario";
            case "FS" -> "Functional Scenario";
            case "IS" -> "Interface Scenario";
            default -> null; // Unknown type
        };
    }

    /**
     * Get the name of an element for auto-generated diagram names.
     */
    private String getElementName(EObject element) {
        if (element == null) {
            return "Unknown";
        }
        try {
            Object name = element.eGet(
                element.eClass().getEStructuralFeature("name")
            );
            return name != null ? name.toString() : element.eClass().getName();
        } catch (Exception e) {
            return element.eClass().getName();
        }
    }
}
