package org.polarsys.capella.ai.scripting;

import groovy.lang.Closure;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.eclipse.emf.common.command.CommandStack;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.model.ArchitectureLocator;
import org.polarsys.capella.ai.model.ElementFactory;
import org.polarsys.capella.ai.model.FindResult;
import org.polarsys.capella.ai.model.Finders;
import org.polarsys.capella.ai.model.ModelAnalytics;
import org.polarsys.capella.ai.model.ModelOps;
import org.polarsys.capella.ai.model.ModelSummary;
import org.polarsys.capella.ai.model.index.ModelIndex;
import org.polarsys.capella.ai.session.OperationInferencer;
import org.polarsys.capella.ai.session.SessionSafeguards;

/**
 * Main API facade for Groovy scripts in Capella AI.
 * Provides a clean, discoverable interface to all Capella operations.
 *
 * <p>This API uses the same internal EMF/Sirius APIs that the Capella UI uses,
 * enabling full control over the model - something external tools like
 * Python4Capella cannot achieve.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Finding elements
 * def component = api.find("SystemComponent", "Flight Controller")
 * def allFunctions = api.findAll("SystemFunction")
 *
 * // Creating elements
 * def newFunc = api.create("SA", "SystemFunction", "Process Data")
 * api.set(newFunc, "description", "Processes incoming sensor data")
 *
 * // Creating relationships
 * api.link(func1, func2, "FunctionalExchange")
 *
 * // Navigation
 * def saRoot = api.layer("SA")
 * def children = api.children(component)
 *
 * // Diagrams (full creation - not possible in Python4Capella!)
 * def diagram = api.diagram.create("SAB", "System Overview", saRoot)
 * api.diagram.add(diagram, component)
 * api.diagram.layout(diagram, "flow")
 * }</pre>
 */
public final class CapellaAPI {

    private final Session session;

    /** Diagram operations sub-API */
    public final DiagramAPI diagram;

    /** Filesystem API for documentation discovery (Anthropic pattern) */
    public final FileSystemAPI fs;

    // ========== Instance-Level Script Cache ==========
    /** Cache for find(type, name) results. Key: "type:name" */
    private final Map<String, EObject> findCache = new HashMap<>();

    /** Cache for findAll(type) results. Key: type */
    private final Map<String, List<EObject>> findAllCache = new HashMap<>();

    /** Cache for layer roots. Key: canonical layer name */
    private final Map<String, EObject> layerCache = new HashMap<>();

    /** Flag indicating cache needs clearing after mutation */
    private boolean cacheDirty = false;

    /**
     * Create a CapellaAPI for the given session.
     */
    public CapellaAPI(Session session) {
        this.session = session;
        this.diagram = new DiagramAPI(session);
        this.fs = new FileSystemAPI();
    }

    // ========== FIND Operations ==========

    /**
     * Find an element by name (any type).
     *
     * @param name the element name
     * @return the element, or null if not found
     */
    public EObject find(String name) {
        if (session == null || name == null) {
            return null;
        }
        ensureCacheValid();
        String cacheKey = findCacheKey("*", name);
        EObject cached = findCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        // Search all semantic resources for any element with this name
        try {
            for (Resource res : session.getSemanticResources()) {
                if (res == null) continue;
                var it = res.getAllContents();
                while (it.hasNext()) {
                    EObject eo = it.next();
                    String elementName = getName(eo);
                    if (name.equals(elementName)) {
                        findCache.put(cacheKey, eo);
                        return eo;
                    }
                }
            }
        } catch (Exception e) {
            // Best effort
        }
        return null;
    }

    /**
     * Find an element by type and name.
     *
     * @param type the simple class name (e.g., "SystemFunction", "LogicalComponent")
     * @param name the element name
     * @return the element, or null if not found
     */
    public EObject find(String type, String name) {
        if (session == null || type == null || name == null) {
            return null;
        }
        ensureCacheValid();
        String cacheKey = findCacheKey(type, name);
        EObject cached = findCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        EObject result = Finders.findByName(session, type, name);
        if (result != null) {
            findCache.put(cacheKey, result);
        }
        return result;
    }

    /**
     * Find an element by type and name with diagnostic info.
     * Returns FindResult with alternatives if not found.
     */
    public FindResult findWithDiagnostic(String type, String name) {
        if (session == null) {
            return FindResult.error(
                org.polarsys.capella.ai.model.ActionResult.ErrorCode.SESSION_ERROR,
                "No active session"
            );
        }
        return Finders.findByNameWithDiagnostic(session, type, name);
    }

    /**
     * Find all elements of a given type.
     *
     * @param type the simple class name
     * @return list of matching elements (never null)
     */
    public List<EObject> findAll(String type) {
        if (session == null || type == null) {
            return new ArrayList<>();
        }
        ensureCacheValid();
        List<EObject> cached = findAllCache.get(type);
        if (cached != null) {
            return new ArrayList<>(cached);  // Defensive copy
        }
        List<EObject> results = new ArrayList<>();
        try {
            for (Resource res : session.getSemanticResources()) {
                if (res == null) continue;
                var it = res.getAllContents();
                while (it.hasNext()) {
                    EObject eo = it.next();
                    String className = eo.eClass().getName();
                    if (className.equals(type) || className.endsWith(type)) {
                        results.add(eo);
                    }
                }
            }
        } catch (Exception e) {
            // Best effort
        }
        findAllCache.put(type, List.copyOf(results));
        return results;
    }

    /**
     * Query elements using a predicate.
     *
     * @param predicate filter function
     * @return list of matching elements
     */
    public List<EObject> query(Predicate<EObject> predicate) {
        List<EObject> results = new ArrayList<>();
        if (session == null || predicate == null) {
            return results;
        }
        try {
            for (Resource res : session.getSemanticResources()) {
                if (res == null) continue;
                var it = res.getAllContents();
                while (it.hasNext()) {
                    EObject eo = it.next();
                    try {
                        if (predicate.test(eo)) {
                            results.add(eo);
                        }
                    } catch (Exception e) {
                        // Skip elements that cause predicate errors
                    }
                }
            }
        } catch (Exception e) {
            // Best effort
        }
        return results;
    }

    /**
     * Find element by UUID/ID.
     */
    public EObject findById(String id) {
        if (session == null || id == null) {
            return null;
        }
        var domain = session.getTransactionalEditingDomain();
        if (domain == null) {
            return null;
        }
        return ModelOps.findById(domain.getResourceSet(), id);
    }

    // ========== CREATE Operations ==========

    /**
     * Create an element with auto-detected parent.
     *
     * @param type the simple class name
     * @param name the element name
     * @return the created element
     * @throws IllegalStateException if no active session
     * @throws IllegalArgumentException if type is null/blank
     */
    public EObject create(String type, String name) {
        if (session == null) {
            throw new IllegalStateException(
                "No active Capella session. Open a model first."
            );
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException(
                "Element type cannot be null or blank"
            );
        }
        // Guess the layer from the type name
        String layer = guessLayerFromType(type);
        // Resolve type aliases
        String resolvedType = resolveTypeAlias(type, layer);
        return create(layer, resolvedType, name);
    }

    /**
     * Create an element in a specific architecture layer.
     *
     * @param layer the layer (OA, SA, LA, PA)
     * @param type the simple class name
     * @param name the element name
     * @return the created element
     * @throws IllegalStateException if no active session
     * @throws IllegalArgumentException if layer not found or type invalid
     */
    public EObject create(String layer, String type, String name) {
        if (session == null) {
            throw new IllegalStateException(
                "No active Capella session. Open a model first."
            );
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException(
                "Element type cannot be null or blank"
            );
        }
        EObject root = layer(layer);
        if (root == null) {
            throw new IllegalArgumentException(
                "Architecture layer '" +
                    layer +
                    "' not found. " +
                    "Valid layers: OA, SA, LA, PA"
            );
        }
        // Resolve type aliases (e.g., OperationalActor -> Entity)
        String resolvedType = resolveTypeAlias(type, layer);
        boolean isActor = isActorAlias(type);

        EObject created = ElementFactory.createUnder(
            session,
            root,
            resolvedType,
            name
        );
        if (created == null) {
            throw new IllegalArgumentException(
                "Failed to create '" +
                    type +
                    "' in " +
                    layer +
                    " layer. " +
                    "Check type spelling. Common types: SystemFunction, SystemComponent, " +
                    "LogicalFunction, LogicalComponent, PhysicalFunction, PhysicalComponent"
            );
        }
        // If this was an actor alias, set the actor property
        if (isActor) {
            set(created, "actor", true);
        }
        invalidate();
        return created;
    }

    /**
     * Create an element under a specific parent.
     *
     * @param type the simple class name
     * @param name the element name
     * @param parent the parent element
     * @return the created element
     * @throws IllegalStateException if no active session
     * @throws IllegalArgumentException if type/parent is null or creation fails
     */
    public EObject create(String type, String name, EObject parent) {
        if (session == null) {
            throw new IllegalStateException(
                "No active Capella session. Open a model first."
            );
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException(
                "Element type cannot be null or blank"
            );
        }
        if (parent == null) {
            throw new IllegalArgumentException(
                "Parent element cannot be null. Use create(layer, type, name) " +
                    "to create elements at the layer root."
            );
        }
        EObject created = ElementFactory.createUnder(
            session,
            parent,
            type,
            name
        );
        if (created == null) {
            throw new IllegalArgumentException(
                "Failed to create '" +
                    type +
                    "' under " +
                    parent.eClass().getName() +
                    ". " +
                    "This type may not be valid as a child of this parent element."
            );
        }
        invalidate();
        return created;
    }

    // ========== MODIFY Operations ==========

    /**
     * Set a property value on an element.
     *
     * @param element the element to modify
     * @param property the property name
     * @param value the new value
     * @return true if property was set
     * @throws IllegalStateException if no active session
     * @throws IllegalArgumentException if element/property is null or property not found
     */
    public boolean set(EObject element, String property, Object value) {
        if (session == null) {
            throw new IllegalStateException(
                "No active Capella session. Open a model first."
            );
        }
        if (element == null) {
            throw new IllegalArgumentException(
                "Cannot set property on null element"
            );
        }
        if (property == null || property.isBlank()) {
            throw new IllegalArgumentException(
                "Property name cannot be null or blank"
            );
        }
        return SessionSafeguards.runWrite(session, "Set " + property, () -> {
            EStructuralFeature feature = element
                .eClass()
                .getEStructuralFeature(property);
            if (feature == null) {
                // Try case-insensitive match
                for (EStructuralFeature f : element
                    .eClass()
                    .getEAllStructuralFeatures()) {
                    if (f.getName().equalsIgnoreCase(property)) {
                        feature = f;
                        break;
                    }
                }
            }
            if (feature == null) {
                // Build list of available properties for error message
                List<String> available = element
                    .eClass()
                    .getEAllStructuralFeatures()
                    .stream()
                    .filter(f -> f.isChangeable())
                    .map(f -> f.getName())
                    .limit(15)
                    .toList();
                throw new IllegalArgumentException(
                    "Property '" +
                        property +
                        "' not found on " +
                        element.eClass().getName() +
                        ". " +
                        "Available properties: " +
                        available
                );
            }
            if (!feature.isChangeable()) {
                throw new IllegalArgumentException(
                    "Property '" +
                        property +
                        "' is read-only on " +
                        element.eClass().getName()
                );
            }
            element.eSet(feature, value);
            // Record operation for undo label inference
            OperationInferencer.recordSet(element.eClass().getName(), property);
            invalidate();
            return true;
        });
    }

    /**
     * Get a property value from an element.
     *
     * @param element the element
     * @param property the property name
     * @return the property value, or null
     */
    public Object get(EObject element, String property) {
        if (element == null || property == null) {
            return null;
        }
        EStructuralFeature feature = element
            .eClass()
            .getEStructuralFeature(property);
        if (feature == null) {
            for (EStructuralFeature f : element
                .eClass()
                .getEAllStructuralFeatures()) {
                if (f.getName().equalsIgnoreCase(property)) {
                    feature = f;
                    break;
                }
            }
        }
        return feature != null ? element.eGet(feature) : null;
    }

    /**
     * Rename an element.
     *
     * @param element the element to rename
     * @param newName the new name
     * @return true if renamed
     */
    public boolean rename(EObject element, String newName) {
        return set(element, "name", newName);
    }

    /**
     * Delete an element from the model.
     *
     * @param element the element to delete
     * @return true if deleted
     */
    public boolean delete(EObject element) {
        if (session == null || element == null) {
            return false;
        }
        // Capture element info before deletion for undo label inference
        String typeName = element.eClass().getName();
        String elemName = name(element);
        String uuid = getUuid(element);
        if (uuid == null) {
            return false;
        }
        boolean deleted = ModelOps.deleteByUuid(session, uuid);
        if (deleted) {
            // Record operation for undo label inference
            OperationInferencer.recordDelete(typeName, elemName != null ? elemName : "unnamed");
            invalidate();
        }
        return deleted;
    }

    // ========== LINK Operations ==========

    /**
     * Create a relationship between two elements.
     *
     * @param source the source element
     * @param target the target element
     * @param relationType the type of relationship (e.g., "FunctionalExchange", "allocation")
     * @return the created relationship element, or null if failed
     */
    public EObject link(EObject source, EObject target, String relationType) {
        if (session == null || source == null || target == null) {
            return null;
        }
        EObject result = RelationFactory.link(
            session,
            source,
            target,
            relationType
        );
        if (result != null) {
            invalidate();
        }
        return result;
    }

    /**
     * Create a simple reference from source to target.
     */
    public EObject link(EObject source, EObject target) {
        return link(source, target, "reference");
    }

    /**
     * Allocate a function/activity to a component/entity.
     * This is required for the function to appear inside the component on diagrams.
     *
     * <p>Example:
     * <pre>{@code
     * def activity = api.create("OA", "OperationalActivity", "Process Data")
     * def entity = api.find("Entity", "Aircraft")
     * api.allocate(activity, entity)  // Activity now appears inside Aircraft on OAB
     * }</pre>
     *
     * @param function the function or activity to allocate
     * @param component the component or entity to allocate to
     * @return true if allocation was successful
     * @throws IllegalStateException if no active session
     * @throws IllegalArgumentException if function/component is null or allocation fails
     */
    public boolean allocate(EObject function, EObject component) {
        if (session == null) {
            throw new IllegalStateException(
                "No active Capella session. Open a model first."
            );
        }
        if (function == null) {
            throw new IllegalArgumentException(
                "Function/activity to allocate cannot be null"
            );
        }
        if (component == null) {
            throw new IllegalArgumentException(
                "Component/entity to allocate to cannot be null"
            );
        }
        return SessionSafeguards.runWrite(session, "Allocate function", () -> {
            try {
                // Create ComponentFunctionalAllocation
                var faFactory =
                    org.polarsys.capella.core.data.fa.FaFactory.eINSTANCE;
                var allocation =
                    faFactory.createComponentFunctionalAllocation();
                allocation.setTargetElement(
                    (org.polarsys.capella.common.data.modellingcore.TraceableElement) function
                );
                allocation.setSourceElement(
                    (org.polarsys.capella.common.data.modellingcore.TraceableElement) component
                );

                // Add to component's ownedFunctionalAllocation
                var allocRef = component
                    .eClass()
                    .getEStructuralFeature("ownedFunctionalAllocation");
                if (allocRef == null) {
                    throw new IllegalArgumentException(
                        "Component '" +
                            component.eClass().getName() +
                            "' does not support function allocation. " +
                            "Only SystemComponent, LogicalComponent, PhysicalComponent, " +
                            "and Entity types support allocation."
                    );
                }
                @SuppressWarnings("unchecked")
                var allocList = (java.util.List<EObject>) component.eGet(
                    allocRef
                );
                allocList.add(allocation);
                // Record operation for undo label inference
                OperationInferencer.recordAllocate(
                    function.eClass().getName(),
                    component.eClass().getName()
                );
                invalidate();
                return true;
            } catch (ClassCastException e) {
                throw new IllegalArgumentException(
                    "Cannot allocate '" +
                        function.eClass().getName() +
                        "' to '" +
                        component.eClass().getName() +
                        "'. " +
                        "Allocation requires a function (SystemFunction, LogicalFunction, " +
                        "PhysicalFunction, OperationalActivity) and a component (SystemComponent, " +
                        "LogicalComponent, PhysicalComponent, Entity)."
                );
            } catch (IllegalArgumentException e) {
                throw e; // Re-throw our own exceptions
            } catch (Exception e) {
                throw new IllegalArgumentException(
                    "Failed to allocate " +
                        function.eClass().getName() +
                        " to " +
                        component.eClass().getName() +
                        ": " +
                        e.getMessage()
                );
            }
        });
    }

    // ========== NAVIGATION Operations ==========

    /**
     * Get an architecture layer root.
     *
     * @param name the layer name (OA, SA, LA, PA, or full names like "operational", "logical")
     * @return the layer root element, or null if not found
     */
    public EObject layer(String name) {
        if (session == null || name == null) {
            return null;
        }
        String canonical = ArchitectureLocator.canonicalizeLayer(name);
        if (canonical == null) {
            canonical = "SA"; // Default
        }
        // Layer roots never change during script - no need for ensureCacheValid()
        EObject cached = layerCache.get(canonical);
        if (cached != null) {
            return cached;
        }
        EObject result = ArchitectureLocator.findTargetElement(session, canonical);
        if (result != null) {
            layerCache.put(canonical, result);
        }
        return result;
    }

    /**
     * Get the parent of an element.
     */
    public EObject parent(EObject element) {
        return element != null ? element.eContainer() : null;
    }

    /**
     * Get the children of an element.
     */
    public List<EObject> children(EObject element) {
        if (element == null) {
            return List.of();
        }
        return new ArrayList<>(element.eContents());
    }

    /**
     * Get all descendants of an element.
     */
    public List<EObject> descendants(EObject element) {
        List<EObject> results = new ArrayList<>();
        if (element == null) {
            return results;
        }
        var it = element.eAllContents();
        while (it.hasNext()) {
            results.add(it.next());
        }
        return results;
    }

    /**
     * Get the type name of an element.
     */
    public String type(EObject element) {
        return element != null ? element.eClass().getName() : null;
    }

    /**
     * Get the name of an element.
     */
    public String name(EObject element) {
        return getName(element);
    }

    /**
     * Get the ID/UUID of an element.
     */
    public String id(EObject element) {
        return getUuid(element);
    }

    // ========== RAW API ACCESS ==========

    /**
     * Execute raw Capella API code within a safe transaction.
     * Use this when standard API methods don't support your use case.
     *
     * <p>This enables dynamic access to ALL Capella functionality by allowing
     * direct use of internal Capella factories and EMF APIs.
     *
     * <p>Example:
     * <pre>{@code
     * api.tx {
     *     def factory = org.polarsys.capella.core.data.requirement.RequirementFactory.eINSTANCE
     *     def req = factory.createSystemUserRequirement()
     *     req.name = "REQ-001"
     *     req.requirementId = "REQ-001"
     *
     *     def reqPkg = api.layer("SA").ownedRequirementPkgs[0]
     *     reqPkg.ownedRequirements.add(req)
     *     req
     * }
     * }</pre>
     *
     * @param closure the Groovy closure containing raw API code
     * @return the result of the closure (typically the created/modified element)
     */
    public Object tx(Closure<?> closure) {
        if (session == null) {
            throw new IllegalStateException("No active Capella session");
        }
        return SessionSafeguards.runWrite(session, "Raw API execution", () -> {
            try {
                Object result = closure.call();
                invalidate();
                return result;
            } catch (Exception e) {
                throw new RuntimeException(
                    "Raw API execution failed: " + e.getMessage(),
                    e
                );
            }
        });
    }

    /**
     * Execute raw Capella API code with a descriptive label.
     *
     * @param label description for undo/redo stack
     * @param closure the Groovy closure containing raw API code
     * @return the result of the closure
     */
    public Object tx(String label, Closure<?> closure) {
        if (session == null) {
            throw new IllegalStateException("No active Capella session");
        }
        return SessionSafeguards.runWrite(session, label, () -> {
            try {
                Object result = closure.call();
                invalidate();
                return result;
            } catch (Exception e) {
                throw new RuntimeException(
                    label + " failed: " + e.getMessage(),
                    e
                );
            }
        });
    }

    /**
     * Execute multiple operations as a single undoable batch.
     * All operations within the closure will appear as a single entry
     * in the undo/redo stack.
     *
     * <p>Use this when an LLM generates multiple related operations that
     * should be undone together, such as creating a component with its
     * functions and exchanges.
     *
     * <p>Example:
     * <pre>{@code
     * api.batch("Create Aircraft System") {
     *     def aircraft = api.create("SA", "SystemComponent", "Aircraft")
     *     def navigate = api.create("SA", "SystemFunction", "Navigate")
     *     def control = api.create("SA", "SystemFunction", "Control Flight")
     *     api.allocate(navigate, aircraft)
     *     api.allocate(control, aircraft)
     *     api.link(navigate, control, "FunctionalExchange")
     * }
     * }</pre>
     *
     * @param label the label to show in the undo/redo menu
     * @param closure the Groovy closure containing operations to batch
     * @return the result of the closure (typically the last created element)
     */
    public Object batch(String label, Closure<?> closure) {
        if (session == null) {
            throw new IllegalStateException("No active Capella session");
        }
        if (label == null || label.isBlank()) {
            label = "LLM batch operation";
        }

        final String finalLabel = label;

        // Use deferSaves to prevent intermediate saves
        return SessionSafeguards.deferSaves(session, () -> {
            // Execute everything in a single transaction with explicit label
            return SessionSafeguards.runWrite(session, finalLabel, () -> {
                try {
                    Object result = closure.call();
                    invalidate();
                    return result;
                } catch (Exception e) {
                    throw new RuntimeException(
                        finalLabel + " failed: " + e.getMessage(),
                        e
                    );
                }
            });
        });
    }

    // ========== VALIDATION Operations ==========

    /**
     * Validate the model and return issues.
     */
    public List<String> validate() {
        // TODO: Integrate with Capella validation framework
        return List.of();
    }

    // ========== UNDO/REDO Operations ==========

    /**
     * Undo the last operation.
     * @return true if successful
     */
    public boolean undo() {
        if (session == null) return false;
        java.util.concurrent.atomic.AtomicBoolean success =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        SessionSafeguards.runOnUiThread(() -> {
            var domain = session.getTransactionalEditingDomain();
            if (domain == null) return;
            CommandStack stack = domain.getCommandStack();
            if (stack.canUndo()) {
                stack.undo();
                success.set(true);
            }
        });
        return success.get();
    }

    /**
     * Redo the last undone operation.
     * @return true if successful
     */
    public boolean redo() {
        if (session == null) return false;
        java.util.concurrent.atomic.AtomicBoolean success =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        SessionSafeguards.runOnUiThread(() -> {
            var domain = session.getTransactionalEditingDomain();
            if (domain == null) return;
            CommandStack stack = domain.getCommandStack();
            if (stack.canRedo()) {
                stack.redo();
                success.set(true);
            }
        });
        return success.get();
    }

    /** Check if undo is available. */
    public boolean canUndo() {
        if (session == null) return false;
        var domain = session.getTransactionalEditingDomain();
        return domain != null && domain.getCommandStack().canUndo();
    }

    /** Check if redo is available. */
    public boolean canRedo() {
        if (session == null) return false;
        var domain = session.getTransactionalEditingDomain();
        return domain != null && domain.getCommandStack().canRedo();
    }

    // ========== TRANSFORMATION Operations ==========

    /**
     * Propagate an element to another layer.
     * Creates a corresponding element in the target layer with a realization link.
     *
     * @param element the source element
     * @param targetLayer the target layer (LA, PA)
     * @return the created element in the target layer, or null
     */
    public EObject propagate(EObject element, String targetLayer) {
        if (session == null || element == null || targetLayer == null) {
            return null;
        }
        // Get the element type and name
        String type = element.eClass().getName();
        String name = getName(element);

        // Map type to target layer equivalent
        String targetType = mapTypeToLayer(type, targetLayer);
        if (targetType == null) {
            return null;
        }

        // Create in target layer
        EObject created = create(targetLayer, targetType, name);
        if (created != null) {
            // Create realization link
            link(created, element, "realization");
        }
        return created;
    }

    // ========== UTILITY Methods ==========

    /**
     * Get the current session.
     */
    public Session getSession() {
        return session;
    }

    /**
     * Check if a session is active.
     */
    public boolean hasSession() {
        return session != null;
    }

    /**
     * Get a model summary.
     */
    public String summary() {
        if (session == null) {
            return "No active session";
        }
        return ModelSummary.summarize(session);
    }

    // ========== Private Helpers ==========

    private void invalidate() {
        cacheDirty = true;
        if (session != null) {
            ModelSummary.invalidate(session);
            ModelIndex.invalidate(session);
            ModelAnalytics.invalidate(session);
            try {
                SessionSafeguards.requestSave(session);
            } catch (Exception e) {
                // Wrap in RuntimeException to propagate without changing method signatures
                // This ensures save failures are visible and not silently ignored
                throw new RuntimeException("Failed to save model after operation", e);
            }
        }
    }

    /**
     * Clear caches if they've been invalidated by a mutation.
     */
    private void ensureCacheValid() {
        if (cacheDirty) {
            findCache.clear();
            findAllCache.clear();
            cacheDirty = false;
        }
    }

    /**
     * Generate cache key for find(type, name).
     */
    private static String findCacheKey(String type, String name) {
        return type + ":" + name;
    }

    private String getName(EObject element) {
        if (element == null) {
            return null;
        }
        try {
            EStructuralFeature feature = element
                .eClass()
                .getEStructuralFeature("name");
            if (feature != null) {
                Object value = element.eGet(feature);
                return value != null ? value.toString() : null;
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private String getUuid(EObject element) {
        if (element == null) {
            return null;
        }
        try {
            EStructuralFeature feature = element
                .eClass()
                .getEStructuralFeature("id");
            if (feature != null) {
                Object value = element.eGet(feature);
                if (value != null) return value.toString();
            }
            feature = element.eClass().getEStructuralFeature("uuid");
            if (feature != null) {
                Object value = element.eGet(feature);
                if (value != null) return value.toString();
            }
            // Fall back to URI fragment
            var uri = org.eclipse.emf.ecore.util.EcoreUtil.getURI(element);
            return uri != null ? uri.fragment() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String guessLayerFromType(String type) {
        if (type == null) return "SA";
        String lower = type.toLowerCase();
        if (
            lower.startsWith("operational") || lower.startsWith("oa")
        ) return "OA";
        if (lower.startsWith("logical") || lower.startsWith("la")) return "LA";
        if (lower.startsWith("physical") || lower.startsWith("pa")) return "PA";
        return "SA"; // Default to System Analysis
    }

    private String mapTypeToLayer(String type, String targetLayer) {
        if (type == null || targetLayer == null) return null;

        // Map common types between layers
        String layer = targetLayer.toUpperCase();
        return switch (layer) {
            case "LA" -> switch (type) {
                case "SystemComponent" -> "LogicalComponent";
                case "SystemFunction" -> "LogicalFunction";
                default -> type.replace("System", "Logical");
            };
            case "PA" -> switch (type) {
                case
                    "SystemComponent",
                    "LogicalComponent" -> "PhysicalComponent";
                case "SystemFunction", "LogicalFunction" -> "PhysicalFunction";
                default -> type
                    .replace("System", "Physical")
                    .replace("Logical", "Physical");
            };
            case "SA" -> switch (type) {
                case "OperationalActivity" -> "SystemFunction";
                case "Entity" -> "SystemComponent";
                default -> type;
            };
            default -> type;
        };
    }

    /**
     * Resolve type aliases to actual Capella types.
     * For example, OperationalActor -> Entity, SystemActor -> SystemComponent.
     */
    private String resolveTypeAlias(String type, String layer) {
        if (type == null) return null;
        return switch (type) {
            case "OperationalActor" -> "Entity";
            case "SystemActor" -> "SystemComponent";
            case "LogicalActor" -> "LogicalComponent";
            case "PhysicalActor" -> "PhysicalComponent";
            default -> type;
        };
    }

    /**
     * Check if a type name is an actor alias that needs the actor property set.
     */
    private boolean isActorAlias(String type) {
        if (type == null) return false;
        return switch (type) {
            case
                "OperationalActor",
                "SystemActor",
                "LogicalActor",
                "PhysicalActor" -> true;
            default -> false;
        };
    }
}
