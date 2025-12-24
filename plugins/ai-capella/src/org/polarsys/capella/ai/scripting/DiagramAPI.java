package org.polarsys.capella.ai.scripting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.sirius.business.api.dialect.DialectManager;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.diagram.DDiagram;
import org.eclipse.sirius.viewpoint.DRepresentation;
import org.polarsys.capella.ai.tools.DiagramCommands;
import org.polarsys.capella.core.data.cs.BlockArchitecture;
import org.polarsys.capella.core.data.cs.Component;
import org.polarsys.capella.core.data.fa.AbstractFunction;
import org.polarsys.capella.core.model.helpers.BlockArchitectureExt;

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
     * <p>Supports abbreviations (SAB, LAB, PAB, etc.) and full Sirius descriptor names.
     * Use {@link #listAvailable(String)} to see all supported diagram types for a layer.
     *
     * @param diagramType the diagram type abbreviation (e.g., "SAB", "SDFB") or full
     *                    Sirius descriptor name (e.g., "System Architecture Blank")
     * @param name the diagram name
     * @param target the semantic element to create the diagram for (layer root or package)
     * @return the created diagram representation
     * @throws IllegalStateException if no active session
     * @throws IllegalArgumentException if target is null or diagram type is unknown
     * @see #listAvailable(String)
     * @see #listAvailableFormatted(String)
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

        // If abbreviation not found, check if it might be a full descriptor name
        if (descriptionName == null) {
            List<String> available = DiagramCommands.getAvailableDiagramDescriptions(session);
            if (available != null && available.contains(diagramType)) {
                // User passed a full descriptor name directly
                descriptionName = diagramType;
            } else {
                // Build helpful error message
                StringBuilder sb = new StringBuilder();
                sb.append("Unknown diagram type: '").append(diagramType).append("'\n");
                sb.append("Valid abbreviations: OAB, SAB, LAB, PAB, SDFB, LDFB, PDFB, CDB, ES, FS, IS\n");
                sb.append("\nUse api.diagram.listAvailable('SA') to see available types for a layer.\n");
                sb.append("Use api.diagram.listAvailableFormatted() for detailed descriptions.");
                throw new IllegalArgumentException(sb.toString());
            }
        }

        // Find the correct target element for this diagram description.
        EObject actualTarget = resolveTargetForDescription(
            descriptionName,
            target
        );
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
     * Create a diagram with safety checks for descriptor and target.
     *
     * <p>This method validates the diagram descriptor against the active session and
     * attempts to resolve a valid target element if the provided target is unsuitable.
     * It throws a descriptive error when it cannot create the diagram.
     */
    public DRepresentation createSafe(
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

        List<String> available = DiagramCommands.getAvailableDiagramDescriptions(session);
        String descriptionName = resolveDiagramDescription(diagramType);

        if (descriptionName == null) {
            if (available != null && available.contains(diagramType)) {
                descriptionName = diagramType;
            } else {
                throw new IllegalArgumentException(
                    buildUnknownDiagramMessage(diagramType, available)
                );
            }
        }

        EObject resolvedTarget = resolveTargetForDescription(descriptionName, target);
        if (resolvedTarget == null) {
            throw new IllegalArgumentException(
                buildTargetError(descriptionName, target)
            );
        }

        DRepresentation created = DiagramCommands.createAndOpenDiagram(
            resolvedTarget,
            descriptionName,
            name
        );
        if (created == null) {
            throw new IllegalArgumentException(
                buildTargetError(descriptionName, resolvedTarget)
            );
        }
        return created;
    }

    /**
     * Find the correct target element for a diagram type.
     * Architecture blank diagrams need ComponentPkg, not the layer root.
     */
    private EObject findDiagramTarget(EObject target, String descriptionName) {
        if (target == null) return null;

        String description =
            descriptionName != null ? descriptionName.toLowerCase() : "";

        if (description.contains("function breakdown")) {
            EObject rootFunction = findRootFunctionTarget(target);
            if (rootFunction != null) return rootFunction;
            EObject functionPkg = findFunctionPkgTarget(target);
            if (functionPkg != null) return functionPkg;
        }

        if (description.contains("data flow blank")) {
            EObject functionPkg = findFunctionPkgTarget(target);
            if (functionPkg != null) return functionPkg;
            EObject rootFunction = findRootFunctionTarget(target);
            if (rootFunction != null) return rootFunction;
        }

        if (description.contains("architecture blank")) {
            EObject rootComponent = findRootComponentTarget(target);
            if (rootComponent != null) return rootComponent;
            EObject componentPkg = findComponentPkgTarget(target);
            if (componentPkg != null) return componentPkg;
        }

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
            BlockArchitecture architecture = resolveArchitecture(target);
            if (architecture != null) {
                EObject componentPkg = BlockArchitectureExt.getComponentPkg(
                    architecture,
                    false
                );
                if (componentPkg != null) {
                    return componentPkg;
                }
            }
            // Fallback: look for ComponentPkg or EntityPkg
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

    private EObject findRootComponentTarget(EObject target) {
        BlockArchitecture architecture = resolveArchitecture(target);
        if (architecture != null) {
            Collection<Component> roots =
                BlockArchitectureExt.getRootComponents(architecture);
            if (roots != null && !roots.isEmpty()) {
                return roots.iterator().next();
            }
        }
        return findFirstByClassName(
            target,
            List.of("SystemComponent", "LogicalComponent", "PhysicalComponent", "Entity")
        );
    }

    private EObject findComponentPkgTarget(EObject target) {
        BlockArchitecture architecture = resolveArchitecture(target);
        if (architecture != null) {
            EObject pkg = BlockArchitectureExt.getComponentPkg(
                architecture,
                false
            );
            if (pkg != null) {
                return pkg;
            }
        }
        return findFirstByClassName(
            target,
            List.of(
                "SystemComponentPkg",
                "LogicalComponentPkg",
                "PhysicalComponentPkg",
                "EntityPkg",
                "ComponentPkg"
            )
        );
    }

    private EObject findRootFunctionTarget(EObject target) {
        BlockArchitecture architecture = resolveArchitecture(target);
        if (architecture != null) {
            AbstractFunction rootFunction = BlockArchitectureExt.getRootFunction(
                architecture,
                false
            );
            if (rootFunction != null) {
                return rootFunction;
            }
        }
        return findFirstByClassName(
            target,
            List.of(
                "SystemFunction",
                "LogicalFunction",
                "PhysicalFunction",
                "OperationalActivity"
            )
        );
    }

    private EObject findFunctionPkgTarget(EObject target) {
        BlockArchitecture architecture = resolveArchitecture(target);
        if (architecture != null) {
            EObject pkg = BlockArchitectureExt.getFunctionPkg(
                architecture,
                false
            );
            if (pkg != null) {
                return pkg;
            }
        }
        return findFirstByClassName(
            target,
            List.of(
                "SystemFunctionPkg",
                "LogicalFunctionPkg",
                "PhysicalFunctionPkg",
                "OperationalActivityPkg",
                "FunctionPkg"
            )
        );
    }

    private static BlockArchitecture resolveArchitecture(EObject target) {
        if (target == null) {
            return null;
        }
        if (target instanceof BlockArchitecture) {
            return (BlockArchitecture) target;
        }
        try {
            return BlockArchitectureExt.getRootBlockArchitecture(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private EObject resolveTargetForDescription(
        String descriptionName,
        EObject preferredTarget
    ) {
        if (preferredTarget == null) {
            return null;
        }

        if (descriptionName == null) {
            return preferredTarget;
        }

        String desc = descriptionName.toLowerCase();

        // Architecture blanks use ComponentPkg/EntityPkg targets.
        if (desc.contains("architecture blank") || desc.contains("data flow blank") ||
            desc.contains("function breakdown")) {
            return findDiagramTarget(preferredTarget, descriptionName);
        }

        if (desc.contains("operational activity")) {
            if (classNameContains(preferredTarget, "OperationalActivity")) {
                return preferredTarget;
            }
            EObject found = findFirstByClassName(preferredTarget, "OperationalActivity");
            if (found != null) return found;
        }

        if (desc.contains("operational entity")) {
            if (classNameContains(preferredTarget, "EntityPkg") ||
                classNameContains(preferredTarget, "Entity")) {
                return preferredTarget;
            }
            EObject found = findFirstByClassName(preferredTarget, "EntityPkg");
            if (found != null) return found;
            found = findFirstByClassName(preferredTarget, "Entity");
            if (found != null) return found;
        }

        if (desc.contains("operational capabilities")) {
            if (classNameContains(preferredTarget, "OperationalCapabilityPkg") ||
                classNameContains(preferredTarget, "OperationalCapability")) {
                return preferredTarget;
            }
            EObject found = findFirstByClassName(preferredTarget, "OperationalCapabilityPkg");
            if (found != null) return found;
            found = findFirstByClassName(preferredTarget, "OperationalCapability");
            if (found != null) return found;
        }

        if (desc.contains("operational role")) {
            if (classNameContains(preferredTarget, "RolePkg") ||
                classNameContains(preferredTarget, "Role")) {
                return preferredTarget;
            }
            EObject found = findFirstByClassName(preferredTarget, "RolePkg");
            if (found != null) return found;
            found = findFirstByClassName(preferredTarget, "Role");
            if (found != null) return found;
        }

        return preferredTarget;
    }

    private static boolean classNameContains(EObject obj, String token) {
        return obj != null && obj.eClass().getName().contains(token);
    }

    private static EObject findFirstByClassName(
        EObject root,
        List<String> tokens
    ) {
        if (root == null || tokens == null || tokens.isEmpty()) {
            return null;
        }
        for (String token : tokens) {
            EObject found = findFirstByClassName(root, token);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static EObject findFirstByClassName(EObject root, String token) {
        if (root == null) return null;
        if (classNameContains(root, token)) return root;
        for (java.util.Iterator<EObject> it = root.eAllContents(); it.hasNext();) {
            EObject next = it.next();
            if (classNameContains(next, token)) {
                return next;
            }
        }
        return null;
    }

    private String buildUnknownDiagramMessage(
        String diagramType,
        List<String> available
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("Unknown diagram type: '").append(diagramType).append("'\n");
        sb.append("Use api.diagram.listAvailableFormatted() for supported types.\n");
        if (available != null && !available.isEmpty()) {
            sb.append("Available descriptors (").append(available.size()).append("): ");
            sb.append(String.join(", ", available));
        }
        return sb.toString();
    }

    private String buildTargetError(String descriptionName, EObject target) {
        StringBuilder sb = new StringBuilder();
        sb.append("Diagram creation failed for '").append(descriptionName).append("'.\n");
        sb.append("Target type must match diagram requirements.\n");
        sb.append("Provided target: ");
        sb.append(target == null ? "null" : target.eClass().getName());
        sb.append("\n");
        sb.append("Use api.diagram.listAvailableFormatted() to see valid types.\n");
        sb.append("If using Operational Activity diagrams, pass an OperationalActivity.\n");
        sb.append("If using Operational Entity diagrams, pass an EntityPkg or Entity.\n");
        sb.append("If using Operational Capability diagrams, pass a CapabilityPkg or Capability.\n");
        return sb.toString();
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
            List<String> supported = supportedNodeTypes(diagram);
            String context = describeDiagramContext(diagram, supported);
            throw new IllegalArgumentException(
                "Failed to add " +
                    element.eClass().getName() +
                    " to diagram '" +
                    diagram.getName() +
                    "'. " +
                    context
            );
        }
        return true;
    }

    /**
     * Add a semantic element to a diagram with fallbacks.
     *
     * <p>Tries {@link #add(DRepresentation, EObject)} first, then falls back to
     * {@link #addByUuids(DRepresentation, List)} when mappings vary across models.
     */
    public boolean addSafe(DRepresentation diagram, EObject element) {
        if (session == null) {
            throw new IllegalStateException("No active session");
        }
        if (diagram == null) {
            throw new IllegalArgumentException("Diagram cannot be null");
        }
        if (element == null) {
            throw new IllegalArgumentException("Element to add cannot be null");
        }

        List<String> supported = supportedNodeTypes(diagram);
        EObject adapted = adaptElementForDiagram(diagram, element, supported);
        EObject candidate = adapted != null ? adapted : element;
        if (!DiagramCommands.isDisplayable(session, diagram, candidate)) {
            throw new IllegalArgumentException(
                "Element " +
                    candidate.eClass().getName() +
                    " is not displayable per viewpoint rules in diagram '" +
                    diagram.getName() +
                    "'. " +
                    describeDiagramContext(diagram, supported)
            );
        }

        try {
            return add(diagram, candidate);
        } catch (IllegalArgumentException ex) {
            if (adapted != null && adapted != element) {
                try {
                    return add(diagram, adapted);
                } catch (IllegalArgumentException adaptEx) {
                    String adaptedId = getElementId(adapted);
                    if (adaptedId != null) {
                        int added = addByUuids(diagram, List.of(adaptedId));
                        if (added > 0) {
                            return true;
                        }
                    }
                    ex = adaptEx;
                }
            }

            if (!expectsPart(supported) || !isComponentLike(element)) {
                String id = getElementId(element);
                if (id != null) {
                    int added = addByUuids(diagram, List.of(id));
                    if (added > 0) {
                        return true;
                    }
                }
            }
            throw new IllegalArgumentException(
                "Failed to add " +
                    element.eClass().getName() +
                    " to diagram '" +
                    diagram.getName() +
                    "'. " +
                    describeDiagramContext(diagram, supported),
                ex
            );
        }
    }

    private EObject adaptElementForDiagram(
        DRepresentation diagram,
        EObject element,
        List<String> supported
    ) {
        if (!expectsPart(supported) || !isComponentLike(element)) {
            return null;
        }
        EObject target = DiagramCommands.getDiagramTarget(diagram);
        CapellaAPI api = new CapellaAPI(session);
        return api.ensurePart(element, target);
    }

    private boolean expectsPart(List<String> supported) {
        if (supported == null || supported.isEmpty()) {
            return false;
        }
        for (String entry : supported) {
            if (entry == null) {
                continue;
            }
            if (entry.equals("Part") || entry.endsWith(".Part")) {
                return true;
            }
        }
        return false;
    }

    private boolean isComponentLike(EObject element) {
        if (element == null) {
            return false;
        }
        String name = element.eClass().getName();
        if (name == null) {
            return false;
        }
        return name.endsWith("Component") || name.equals("Entity");
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
     * Return supported node types for the diagram mappings.
     */
    public List<String> supportedNodeTypes(DRepresentation diagram) {
        if (diagram == null) {
            return List.of();
        }
        return DiagramCommands.getSupportedDomainClasses(diagram);
    }

    /**
     * Check whether the diagram has a mapping for this element.
     */
    public boolean isDisplayable(DRepresentation diagram, EObject element) {
        if (diagram == null || element == null) {
            return false;
        }
        return DiagramCommands.isDisplayable(session, diagram, element);
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

    private String describeDiagramContext(
        DRepresentation diagram,
        List<String> supported
    ) {
        StringBuilder sb = new StringBuilder();
        String descriptor = getDescriptorName(diagram);
        if (descriptor != null && !descriptor.isBlank()) {
            sb.append("Descriptor: ").append(descriptor).append(". ");
        }
        if (supported != null && !supported.isEmpty()) {
            sb.append("Supported types: ").append(supported);
        } else if (sb.length() == 0) {
            sb.append("Element may not be displayable in this diagram type.");
        }
        return sb.toString().trim();
    }

    private String getDescriptorName(DRepresentation diagram) {
        if (diagram instanceof DDiagram) {
            var desc = ((DDiagram) diagram).getDescription();
            if (desc != null) {
                return desc.getName();
            }
        }
        return null;
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

    private String getElementId(EObject element) {
        if (element == null) {
            return null;
        }
        String[] features = {"id", "uid", "sid"};
        for (String featureName : features) {
            EStructuralFeature feature = element
                .eClass()
                .getEStructuralFeature(featureName);
            if (feature != null) {
                Object value = element.eGet(feature);
                if (value != null) {
                    return value.toString();
                }
            }
        }
        return null;
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

    /**
     * Align elements on the diagram.
     *
     * @param diagram the diagram
     * @param elements list of semantic elements to align
     * @param alignment "left", "right", "top", "bottom", "hcenter" (center horizontally), "vcenter" (center vertically)
     * @return true if alignment was applied
     */
    public boolean align(
        DRepresentation diagram,
        List<EObject> elements,
        String alignment
    ) {
        String strat = alignment != null ? alignment.toLowerCase() : "";
        if (!List.of("left", "right", "top", "bottom", "hcenter", "vcenter").contains(strat)) {
            throw new IllegalArgumentException("Invalid alignment: " + alignment + ". Use left, right, top, bottom, hcenter, vcenter.");
        }
        if (session == null || diagram == null || elements == null || elements.isEmpty()) {
            return false;
        }
        // Note: DiagramCommands.arrangeSelection currently operates on the *selection* or *all nodes*.
        // To support aligning specific elements, we would need to select them first or update DiagramCommands.
        // For now, we assume the user has set the selection or wants to align the passed elements (if DiagramCommands supports specific list).
        // Update: DiagramCommands.arrangeSelection logic relies on "current selection" in the editor.
        // To make this robust for headless/scripting, we should select the elements first.
        DiagramCommands.revealSemantics(session, diagram, elements);
        return DiagramCommands.arrangeSelection(session, diagram, strat);
    }

    /**
     * Distribute elements on the diagram.
     *
     * @param diagram the diagram
     * @param elements list of semantic elements to distribute
     * @param distribution "horizontal" (hspace), "vertical" (vspace)
     * @return true if distribution was applied
     */
    public boolean distribute(
        DRepresentation diagram,
        List<EObject> elements,
        String distribution
    ) {
        String strat = distribution != null ? distribution.toLowerCase() : "";
        if (strat.startsWith("h")) strat = "hspace";
        else if (strat.startsWith("v")) strat = "vspace";
        
        if (!List.of("hspace", "vspace").contains(strat)) {
            throw new IllegalArgumentException("Invalid distribution: " + distribution + ". Use horizontal, vertical.");
        }

        if (session == null || diagram == null || elements == null || elements.isEmpty()) {
            return false;
        }
        DiagramCommands.revealSemantics(session, diagram, elements);
        return DiagramCommands.arrangeSelection(session, diagram, strat);
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

    // ========== Discovery ==========

    /**
     * List available diagram types for a given architecture layer.
     * Combines known abbreviations with actual Sirius descriptors from the session.
     *
     * @param layer the architecture layer (OA, SA, LA, PA) or null for all
     * @return list of available diagram type abbreviations
     */
    public List<String> listAvailable(String layer) {
        List<String> result = new ArrayList<>();

        // Add known abbreviations for the layer
        if (layer == null || "OA".equalsIgnoreCase(layer)) {
            result.add("OAB");
            result.add("OAIB");
            result.add("OCB");
            result.add("OES");
        }
        if (layer == null || "SA".equalsIgnoreCase(layer)) {
            result.add("SAB");
            result.add("SDFB");
            result.add("SFB");
            result.add("ES");
            result.add("FS");
            result.add("IS");
        }
        if (layer == null || "LA".equalsIgnoreCase(layer)) {
            result.add("LAB");
            result.add("LDFB");
            result.add("LFB");
        }
        if (layer == null || "PA".equalsIgnoreCase(layer)) {
            result.add("PAB");
            result.add("PDFB");
            result.add("PFB");
        }
        if (layer == null) {
            result.add("CDB");
        }

        return result;
    }

    /**
     * List available diagram types with their full Sirius descriptor names.
     * Returns a formatted string for LLM consumption.
     *
     * @param layer the architecture layer (OA, SA, LA, PA) or null for all
     * @return formatted string of available diagram types
     */
    public String listAvailableFormatted(String layer) {
        StringBuilder sb = new StringBuilder();
        sb.append("Available diagram types");
        if (layer != null) {
            sb.append(" for ").append(layer.toUpperCase());
        }
        sb.append(":\n\n");

        // List abbreviations with their full names
        if (layer == null || "OA".equalsIgnoreCase(layer)) {
            sb.append("Operational Analysis (OA):\n");
            sb.append("  OAB  - Operational Activity Blank\n");
            sb.append("  OAIB - Operational Interaction Blank\n");
            sb.append("  OCB  - Operational Capabilities Blank\n");
            sb.append("  OES  - Operational Entity Blank\n");
        }
        if (layer == null || "SA".equalsIgnoreCase(layer)) {
            sb.append("System Analysis (SA):\n");
            sb.append("  SAB  - System Architecture Blank\n");
            sb.append("  SDFB - System Data Flow Blank\n");
            sb.append("  SFB  - System Function Breakdown\n");
            sb.append("  ES   - Exchange Scenario\n");
            sb.append("  FS   - Functional Scenario\n");
            sb.append("  IS   - Interface Scenario\n");
        }
        if (layer == null || "LA".equalsIgnoreCase(layer)) {
            sb.append("Logical Architecture (LA):\n");
            sb.append("  LAB  - Logical Architecture Blank\n");
            sb.append("  LDFB - Logical Data Flow Blank\n");
            sb.append("  LFB  - Logical Function Breakdown\n");
        }
        if (layer == null || "PA".equalsIgnoreCase(layer)) {
            sb.append("Physical Architecture (PA):\n");
            sb.append("  PAB  - Physical Architecture Blank\n");
            sb.append("  PDFB - Physical Data Flow Blank\n");
            sb.append("  PFB  - Physical Function Breakdown\n");
        }
        if (layer == null) {
            sb.append("Cross-cutting:\n");
            sb.append("  CDB  - Class Diagram Blank\n");
        }

        // Also show actual Sirius descriptors from session
        if (session != null) {
            List<String> actual = DiagramCommands.getAvailableDiagramDescriptions(session);
            if (actual != null && !actual.isEmpty()) {
                sb.append("\nActual Sirius descriptors in session (").append(actual.size()).append("):\n");
                for (String desc : actual) {
                    sb.append("  - ").append(desc).append("\n");
                }
            }
        }

        return sb.toString();
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
