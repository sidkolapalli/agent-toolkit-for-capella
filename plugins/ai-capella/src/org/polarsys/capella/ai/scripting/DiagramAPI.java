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
     * @return the created diagram representation, or null if creation failed
     */
    public DRepresentation create(
        String diagramType,
        String name,
        EObject target
    ) {
        if (session == null || target == null) {
            return null;
        }
        String descriptionName = resolveDiagramDescription(diagramType);

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

    // ========== Finding ==========

    /**
     * Find a diagram by name.
     */
    public DRepresentation find(String name) {
        if (session == null || name == null) {
            return null;
        }
        return DiagramCommands.findRepresentationByNameOrUid(session, name);
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
     */
    public boolean add(DRepresentation diagram, EObject element) {
        if (session == null || diagram == null || element == null) {
            return false;
        }
        return DiagramCommands.createNode(session, diagram, element);
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
     */
    public boolean remove(DRepresentation diagram, EObject element) {
        if (session == null || diagram == null || element == null) {
            return false;
        }
        return DiagramCommands.removeViewElement(session, diagram, element);
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
     */
    public boolean layout(DRepresentation diagram, String strategy) {
        if (session == null || diagram == null) {
            return false;
        }
        return DiagramCommands.arrangeSelection(session, diagram, strategy);
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
     */
    public boolean rename(DRepresentation diagram, String newName) {
        if (session == null || diagram == null || newName == null) {
            return false;
        }
        return DiagramCommands.renameDiagram(session, diagram, newName);
    }

    /**
     * Delete a diagram.
     */
    public boolean delete(DRepresentation diagram) {
        if (session == null || diagram == null) {
            return false;
        }
        return DiagramCommands.deleteDiagram(session, diagram);
    }

    /**
     * Duplicate a diagram.
     */
    public boolean duplicate(DRepresentation diagram, String newName) {
        if (session == null || diagram == null || newName == null) {
            return false;
        }
        return DiagramCommands.duplicateDiagram(session, diagram, newName);
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
     */
    private String resolveDiagramDescription(String type) {
        if (type == null) {
            return "System Architecture Blank";
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
            default -> type; // Use as-is if not recognized
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
