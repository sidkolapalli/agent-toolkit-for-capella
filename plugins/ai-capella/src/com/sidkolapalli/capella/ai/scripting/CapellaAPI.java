package com.sidkolapalli.capella.ai.scripting;

import groovy.lang.Closure;

import org.eclipse.emf.common.command.CommandStack;
import org.eclipse.emf.common.util.Diagnostic;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.Diagnostician;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.tools.api.interpreter.InterpreterUtil;
import com.sidkolapalli.capella.ai.model.ArchitectureLocator;
import com.sidkolapalli.capella.ai.model.ElementFactory;
import com.sidkolapalli.capella.ai.model.FindResult;
import com.sidkolapalli.capella.ai.model.Finders;
import com.sidkolapalli.capella.ai.model.ModelAnalytics;
import com.sidkolapalli.capella.ai.model.ModelOps;
import com.sidkolapalli.capella.ai.model.ModelSummary;
import com.sidkolapalli.capella.ai.model.PersistentIds;
import com.sidkolapalli.capella.ai.model.index.ModelIndex;
import com.sidkolapalli.capella.ai.session.OperationInferencer;
import com.sidkolapalli.capella.ai.session.SessionSafeguards;
import org.polarsys.capella.core.data.capellamodeller.Project;
import org.polarsys.capella.core.data.cs.BlockArchitecture;
import org.polarsys.capella.core.data.cs.CsFactory;
import org.polarsys.capella.core.data.cs.Part;
import org.polarsys.capella.core.data.fa.ComponentExchange;
import org.polarsys.capella.core.data.fa.FunctionalExchange;
import org.polarsys.capella.core.model.helpers.BlockArchitectureExt;
import org.polarsys.capella.core.model.helpers.ComponentExchangeExt;
import org.polarsys.capella.core.model.helpers.FunctionalExchangeExt;
import org.polarsys.capella.core.model.helpers.SystemEngineeringExt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Main API facade for Groovy scripts in Capella AI. Provides a clean, discoverable interface to all
 * Capella operations.
 *
 * <p>This API uses the same internal EMF/Sirius APIs that the Capella UI uses, enabling full
 * control over the model - something external tools like Python4Capella cannot achieve.
 *
 * <p>Example usage:
 *
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

    /** Skills runtime API (trust-gated) */
    public final SkillsAPI skills;

    /** Style API for visual formatting */
    public final StyleAPI style;

    /** Operations API for engineering transitions */
    public final OpsAPI ops;

    /** Native requirements and traceability, when the Requirements viewpoint is installed. */
    public final RequirementsAPI requirements;

    /** Typed capability, scenario, chain, interface and deployment helpers. */
    public final ArcadiaAPI arcadia;

    /** Optional reset hook for stateful Groovy sessions */
    private final Runnable resetHandler;

    // ========== Instance-Level Script Cache ==========
    /** Cache for find(type, name) results. Key: "type:name" */
    private final Map<String, EObject> findCache = new HashMap<>();

    /** Cache for findAll(type) results. Key: type */
    private final Map<String, List<EObject>> findAllCache = new HashMap<>();

    /** Cache for layer roots. Key: canonical layer name */
    private final Map<String, EObject> layerCache = new HashMap<>();

    /** Flag indicating cache needs clearing after mutation */
    private boolean cacheDirty = false;

    private org.eclipse.emf.common.command.Command cachedUndoCommand;
    private org.eclipse.emf.common.command.Command cachedRedoCommand;

    /** Create a CapellaAPI for the given session. */
    public CapellaAPI(Session session) {
        this(session, null);
    }

    /** Create a CapellaAPI with an optional reset hook for stateful scripts. */
    public CapellaAPI(Session session, Runnable resetHandler) {
        this.session = session;
        this.diagram = new DiagramAPI(session);
        this.fs = new FileSystemAPI();
        this.skills = new SkillsAPI(session);
        this.style = new StyleAPI(session, this.diagram);
        this.ops = new OpsAPI(session);
        this.requirements = new RequirementsAPI(session);
        this.arcadia = new ArcadiaAPI(session, this::invalidateArcadiaCaches);
        this.resetHandler = resetHandler;
    }

    public RequirementsAPI requirements() {
        return requirements;
    }

    public ArcadiaAPI arcadia() {
        return arcadia;
    }

    private void invalidateArcadiaCaches() {
        cacheDirty = true;
        ModelSummary.invalidate(session);
        ModelIndex.invalidate(session);
        ModelAnalytics.invalidate(session);
    }

    /** Get a structured JSON catalog of available API methods. */
    public String help() {
        return ApiCatalog.help();
    }

    /** Get a structured JSON catalog for a specific module (e.g., "diagram", "fs"). */
    public String help(String module) {
        return ApiCatalog.help(module);
    }

    /** Report runtime capabilities so scripts can adapt safely. */
    public String capabilities() {
        boolean systemEngineeringHelper =
                isClassPresent("org.polarsys.capella.core.model.helpers.SystemEngineeringExt");
        boolean blockArchitectureHelper =
                isClassPresent("org.polarsys.capella.core.model.helpers.BlockArchitectureExt");
        boolean interpreterAvailable = getInterpreter(session) != null;

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"helpers\":{");
        sb.append("\"systemEngineering\":").append(systemEngineeringHelper).append(",");
        sb.append("\"blockArchitecture\":").append(blockArchitectureHelper);
        sb.append("},");
        sb.append("\"interpreter\":{");
        sb.append("\"available\":").append(interpreterAvailable);
        sb.append("},");
        sb.append("\"requirements\":{");
        sb.append("\"available\":").append(requirements.available());
        sb.append("},");
        sb.append("\"fallbacks\":{");
        sb.append("\"semanticServices\":")
                .append(!(systemEngineeringHelper && blockArchitectureHelper));
        sb.append("}");
        sb.append("}");
        return sb.toString();
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
        if ("Requirement".equals(type)) {
            return com.sidkolapalli.capella.ai.model.finder.RequirementFinder.findByName(session, name);
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
     * Find an element by type and name with diagnostic info. Returns FindResult with alternatives
     * if not found.
     */
    public FindResult findWithDiagnostic(String type, String name) {
        if (session == null) {
            return FindResult.error(
                    com.sidkolapalli.capella.ai.model.ActionResult.ErrorCode.SESSION_ERROR,
                    "No active session");
        }
        return Finders.findByNameWithDiagnostic(session, type, name);
    }

    /**
     * Find all elements of a given native type, including its actual subtypes. Actor aliases select
     * only actor instances of their corresponding component type.
     *
     * @param type the simple class name
     * @return list of matching elements (never null)
     */
    public List<EObject> findAll(String type) {
        if (session == null || type == null || type.isBlank()) {
            return new ArrayList<>();
        }
        ensureCacheValid();
        List<EObject> cached = findAllCache.get(type);
        if (cached != null) {
            return new ArrayList<>(cached); // Defensive copy
        }
        String nativeType = resolveTypeAlias(type, null);
        boolean actorOnly = isActorAlias(type);
        List<EObject> results = new ArrayList<>();
        try {
            for (Resource res : session.getSemanticResources()) {
                if (res == null) continue;
                var it = res.getAllContents();
                while (it.hasNext()) {
                    EObject eo = it.next();
                    EClass nativeClass = eo.eClass();
                    boolean matches =
                            nativeType.equals(nativeClass.getName())
                                    || nativeClass.getEAllSuperTypes().stream()
                                            .anyMatch(
                                                    superType ->
                                                            nativeType.equals(superType.getName()));
                    EStructuralFeature actor =
                            actorOnly ? nativeClass.getEStructuralFeature("actor") : null;
                    if (matches
                            && (!actorOnly
                                    || actor != null && Boolean.TRUE.equals(eo.eGet(actor)))) {
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

    /** Find element by UUID/ID. */
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
            throw new IllegalStateException("No active Capella session. Open a model first.");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Element type cannot be null or blank");
        }
        String trimmedType = type.trim();
        if (isLayerToken(trimmedType)) {
            String layer = trimmedType.toUpperCase();
            throw new IllegalArgumentException(
                    "Looks like you passed a layer code ('"
                            + layer
                            + "') as the element type. "
                            + "Use create('"
                            + layer
                            + "', '<Type>', '<Name>') or createIn('"
                            + layer
                            + "', '<Type>', '<Name>'). "
                            + "For function allocation, use api.allocate(function, component).");
        }
        // Guess the layer from the type name
        String layer = guessLayerFromType(trimmedType);
        // Preserve the alias so the layer overload can apply actor semantics.
        return create(layer, trimmedType, name);
    }

    /**
     * Create an element in a specific architecture layer.
     *
     * @param layer the layer (OA, SA, LA, PA, EPBS)
     * @param type the simple class name
     * @param name the element name
     * @return the created element
     * @throws IllegalStateException if no active session
     * @throws IllegalArgumentException if layer not found or type invalid
     */
    public EObject create(String layer, String type, String name) {
        if (session == null) {
            throw new IllegalStateException("No active Capella session. Open a model first.");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Element type cannot be null or blank");
        }
        EObject root = layer(layer);
        if (root == null) {
            throw new IllegalArgumentException(
                    "Architecture layer '"
                            + layer
                            + "' not found. "
                            + "Valid layers: OA, SA, LA, PA, EPBS");
        }
        // Resolve type aliases (e.g., OperationalActor -> Entity)
        String resolvedType = resolveTypeAlias(type, layer);
        boolean isActor = isActorAlias(type);
        EObject parent = isActor ? root : rootComponentFor(root, resolvedType);
        return SessionSafeguards.runWrite(
                session,
                "Create " + type,
                () -> {
                    EObject created =
                            ElementFactory.createUnder(session, parent, resolvedType, name);
                    if (created == null) {
                        throw new IllegalArgumentException(
                                "Failed to create '"
                                        + type
                                        + "' in "
                                        + layer
                                        + " layer. Check type spelling. Common types:"
                                        + " SystemFunction, SystemComponent, LogicalFunction,"
                                        + " LogicalComponent, PhysicalFunction, PhysicalComponent");
                    }
                    // If this was an actor alias, set the actor property
                    if (isActor) {
                        set(created, "actor", true);
                        if ("OperationalActor".equals(type)) set(created, "human", true);
                    }
                    invalidate();
                    return created;
                });
    }

    /**
     * New LA/PA components belong inside the layer's single root component; a second component
     * beside it is a second root, which Capella validation reports as an error. Actors stay beside
     * it; the SA system is a black box.
     */
    private static EObject rootComponentFor(EObject layer, String type) {
        EClass target = ElementFactory.findEClass(type);
        if (!(layer instanceof org.polarsys.capella.core.data.cs.BlockArchitecture architecture)
                || layer instanceof org.polarsys.capella.core.data.oa.OperationalAnalysis
                || layer instanceof org.polarsys.capella.core.data.ctx.SystemAnalysis
                || layer instanceof org.polarsys.capella.core.data.epbs.EPBSArchitecture
                || target == null
                || !org.polarsys.capella.core.data.cs.CsPackage.Literals.COMPONENT.isSuperTypeOf(
                        target)) return layer;
        var roots =
                org.polarsys.capella.core.model.helpers.BlockArchitectureExt.getRootComponents(
                        architecture);
        return roots != null && roots.size() == 1 ? roots.iterator().next() : layer;
    }

    /**
     * Create an element in a specific architecture layer (unambiguous helper).
     *
     * @param layer the layer (OA, SA, LA, PA, EPBS)
     * @param type the simple class name
     * @param name the element name
     * @return the created element
     */
    public EObject createIn(String layer, String type, String name) {
        return create(layer, type, name);
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
            throw new IllegalStateException("No active Capella session. Open a model first.");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Element type cannot be null or blank");
        }
        if (parent == null) {
            throw new IllegalArgumentException(
                    "Parent element cannot be null. Use create(layer, type, name) "
                            + "to create elements at the layer root.");
        }
        String parentLayer = guessLayerFromType(type);
        for (EObject ancestor = parent; ancestor != null; ancestor = ancestor.eContainer()) {
            if (ancestor instanceof BlockArchitecture) {
                parentLayer = guessLayerFromType(ancestor.eClass().getName());
                break;
            }
        }
        String resolvedType = resolveTypeAlias(type, parentLayer);
        boolean actor = isActorAlias(type);
        return SessionSafeguards.runWrite(
                session,
                "Create " + type,
                () -> {
                    EObject created =
                            ElementFactory.createUnder(session, parent, resolvedType, name);
                    if (created == null) {
                        throw new IllegalArgumentException(
                                "Failed to create '"
                                        + type
                                        + "' under "
                                        + parent.eClass().getName()
                                        + ". This type may not be valid as a child of this parent"
                                        + " element.");
                    }
                    if (actor) {
                        set(created, "actor", true);
                        if ("OperationalActor".equals(type)) set(created, "human", true);
                    }
                    invalidate();
                    return created;
                });
    }

    /**
     * Ensure a Part exists for the given component (useful for architecture diagrams).
     *
     * @param component the component to create a Part for
     * @return the existing or newly created Part, or null if unavailable
     */
    public EObject ensurePart(EObject component) {
        return ensurePart(component, null);
    }

    /**
     * Ensure a Part exists for the given component, optionally scoped to a root component.
     *
     * @param component the component to create a Part for
     * @param preferredRoot the preferred root component/container for the Part
     * @return the existing or newly created Part, or null if unavailable
     */
    public EObject ensurePart(EObject component, EObject preferredRoot) {
        if (session == null || component == null) {
            return null;
        }
        if (isPart(component)) {
            return component;
        }
        if (!isComponentLike(component)) {
            return null;
        }
        EObject existing = findPartForComponent(component);
        if (existing != null) {
            return existing;
        }
        EObject container = resolvePartContainer(component, preferredRoot);
        if (container == null) {
            return null;
        }
        String partName = getName(component);
        if (partName == null || partName.isBlank()) {
            partName = component.eClass().getName();
        }
        String finalPartName = partName;
        return SessionSafeguards.runWrite(
                session,
                "Create Part",
                () -> {
                    Part part = CsFactory.eINSTANCE.createPart();
                    part.setName(finalPartName);
                    setFeatureIfPresent(part, "abstractType", component);
                    setFeatureIfPresent(part, "type", component);
                    if (!addToContainment(container, part)) {
                        return null;
                    }
                    OperationInferencer.recordCreate("Part", finalPartName);
                    invalidate();
                    try {
                        SessionSafeguards.requestSave(session);
                    } catch (Exception e) {
                        throw new IllegalStateException("Failed to save after creating Part", e);
                    }
                    return part;
                });
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
            throw new IllegalStateException("No active Capella session. Open a model first.");
        }
        if (element == null) {
            throw new IllegalArgumentException("Cannot set property on null element");
        }
        if (property == null || property.isBlank()) {
            throw new IllegalArgumentException("Property name cannot be null or blank");
        }
        return SessionSafeguards.runWrite(
                session,
                "Set " + property,
                () -> {
                    EStructuralFeature feature = element.eClass().getEStructuralFeature(property);
                    if (feature == null) {
                        // Try case-insensitive match
                        for (EStructuralFeature f : element.eClass().getEAllStructuralFeatures()) {
                            if (f.getName().equalsIgnoreCase(property)) {
                                feature = f;
                                break;
                            }
                        }
                    }
                    if (feature == null) {
                        // Build list of available properties for error message
                        List<String> available =
                                element.eClass().getEAllStructuralFeatures().stream()
                                        .filter(f -> f.isChangeable())
                                        .map(f -> f.getName())
                                        .limit(15)
                                        .toList();
                        throw new IllegalArgumentException(
                                "Property '"
                                        + property
                                        + "' not found on "
                                        + element.eClass().getName()
                                        + ". "
                                        + "Available properties: "
                                        + available);
                    }
                    if (!feature.isChangeable()) {
                        throw new IllegalArgumentException(
                                "Property '"
                                        + property
                                        + "' is read-only on "
                                        + element.eClass().getName());
                    }
                    if (feature instanceof org.eclipse.emf.ecore.EAttribute attribute
                            && attribute.isID()) {
                        if (!(value instanceof String identifier)) {
                            throw new IllegalArgumentException(
                                    "Persistent model ID must be a nonblank String");
                        }
                        PersistentIds.setId(element, identifier);
                    } else if (feature.getEType() instanceof org.eclipse.emf.ecore.EEnum type
                            && value instanceof String text) {
                        // Scripts name enum values as text, e.g. api.set(component, 'nature',
                        // 'NODE').
                        org.eclipse.emf.ecore.EEnumLiteral literal =
                                type.getELiterals().stream()
                                        .filter(
                                                candidate ->
                                                        candidate.getName().equalsIgnoreCase(text)
                                                                || candidate
                                                                        .getLiteral()
                                                                        .equalsIgnoreCase(text))
                                        .findFirst()
                                        .orElseThrow(
                                                () ->
                                                        new IllegalArgumentException(
                                                                "'"
                                                                        + text
                                                                        + "' is not a "
                                                                        + type.getName()
                                                                        + " value. Use one of "
                                                                        + type
                                                                                .getELiterals()
                                                                                .stream()
                                                                                .map(
                                                                                        org.eclipse
                                                                                                        .emf
                                                                                                        .ecore
                                                                                                        .EEnumLiteral
                                                                                                ::getName)
                                                                                .toList()));
                        element.eSet(feature, literal.getInstance());
                    } else {
                        element.eSet(feature, value);
                    }
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
        EStructuralFeature feature = element.eClass().getEStructuralFeature(property);
        if (feature == null) {
            for (EStructuralFeature f : element.eClass().getEAllStructuralFeatures()) {
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
        EObject result = RelationFactory.link(session, source, target, relationType);
        if (result != null) {
            invalidate();
        }
        return result;
    }

    /** Create a simple reference from source to target. */
    public EObject link(EObject source, EObject target) {
        return link(source, target, "reference");
    }

    /**
     * Allocate a function/activity to a component/entity. This is required for the function to
     * appear inside the component on diagrams.
     *
     * <p>Example:
     *
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
            throw new IllegalStateException("No active Capella session. Open a model first.");
        }
        if (function == null) {
            throw new IllegalArgumentException("Function/activity to allocate cannot be null");
        }
        if (component == null) {
            throw new IllegalArgumentException("Component/entity to allocate to cannot be null");
        }
        if (!(function instanceof org.polarsys.capella.core.data.fa.AbstractFunction)
                || !(component instanceof org.polarsys.capella.core.data.cs.Component)) {
            throw new IllegalArgumentException(
                    "Allocation requires a function/activity and a component/entity.");
        }
        BlockArchitecture functionArchitecture = containingArchitecture(function);
        BlockArchitecture componentArchitecture = containingArchitecture(component);
        if (functionArchitecture == null
                || functionArchitecture != componentArchitecture
                || "EPBSArchitecture".equals(componentArchitecture.eClass().getName())) {
            throw new IllegalArgumentException(
                    "Function allocation requires endpoints in the same OA, SA, LA or PA"
                        + " architecture. EPBS items use PhysicalArtifactRealization links.");
        }
        var allocationDomain = session.getTransactionalEditingDomain();
        if (allocationDomain == null
                || function.eResource() == null
                || component.eResource() == null
                || function.eResource().getResourceSet() != allocationDomain.getResourceSet()
                || component.eResource().getResourceSet() != allocationDomain.getResourceSet()) {
            throw new IllegalArgumentException(
                    "Allocation endpoints must belong to the active model session.");
        }
        return SessionSafeguards.runWrite(
                session,
                "Allocate function",
                () -> {
                    try {
                        // Create ComponentFunctionalAllocation
                        var faFactory = org.polarsys.capella.core.data.fa.FaFactory.eINSTANCE;
                        var allocation = faFactory.createComponentFunctionalAllocation();
                        allocation.setTargetElement(
                                (org.polarsys.capella.common.data.modellingcore.TraceableElement)
                                        function);
                        allocation.setSourceElement(
                                (org.polarsys.capella.common.data.modellingcore.TraceableElement)
                                        component);

                        // Add to component's ownedFunctionalAllocation
                        var allocRef =
                                component
                                        .eClass()
                                        .getEStructuralFeature("ownedFunctionalAllocation");
                        if (allocRef == null) {
                            throw new IllegalArgumentException(
                                    "Component '"
                                            + component.eClass().getName()
                                            + "' does not support function allocation. Only"
                                            + " SystemComponent, LogicalComponent,"
                                            + " PhysicalComponent, and Entity types support"
                                            + " allocation.");
                        }
                        @SuppressWarnings("unchecked")
                        var allocList = (java.util.List<EObject>) component.eGet(allocRef);
                        for (EObject existing : allocList) {
                            if (existing
                                            instanceof
                                            org.polarsys.capella.core.data.fa
                                                                    .ComponentFunctionalAllocation
                                                            typed
                                    && typed.getTargetElement() == function) return true;
                        }
                        allocList.add(allocation);
                        // Record operation for undo label inference
                        OperationInferencer.recordAllocate(
                                function.eClass().getName(), component.eClass().getName());
                        invalidate();
                        return true;
                    } catch (ClassCastException e) {
                        throw new IllegalArgumentException(
                                "Cannot allocate '"
                                        + function.eClass().getName()
                                        + "' to '"
                                        + component.eClass().getName()
                                        + "'. Allocation requires a function (SystemFunction,"
                                        + " LogicalFunction, PhysicalFunction, OperationalActivity)"
                                        + " and a component (SystemComponent, LogicalComponent,"
                                        + " PhysicalComponent, Entity).");
                    } catch (IllegalArgumentException e) {
                        throw e; // Re-throw our own exceptions
                    } catch (Exception e) {
                        throw new IllegalArgumentException(
                                "Failed to allocate "
                                        + function.eClass().getName()
                                        + " to "
                                        + component.eClass().getName()
                                        + ": "
                                        + e.getMessage());
                    }
                });
    }

    private static BlockArchitecture containingArchitecture(EObject element) {
        for (EObject parent = element; parent != null; parent = parent.eContainer()) {
            if (parent instanceof BlockArchitecture architecture) return architecture;
        }
        return null;
    }

    // ========== NAVIGATION Operations ==========

    /** Get the SystemEngineering root (semantic services). */
    public EObject systemEngineering() {
        if (session == null) {
            return null;
        }
        try {
            for (Resource res : session.getSemanticResources()) {
                if (res == null || res.getContents().isEmpty()) {
                    continue;
                }
                EObject root = res.getContents().get(0);
                if (root instanceof Project) {
                    EObject se = SystemEngineeringExt.getSystemEngineering((Project) root);
                    if (se != null) {
                        return se;
                    }
                }
            }
        } catch (Throwable e) {
            // Best effort
        }
        // Fallback: pure EMF traversal
        try {
            for (Resource res : session.getSemanticResources()) {
                if (res == null || res.getContents().isEmpty()) {
                    continue;
                }
                EObject root = res.getContents().get(0);
                if (!(root instanceof Project)) {
                    continue;
                }
                for (EObject child : root.eContents()) {
                    if ("SystemEngineering".equals(child.eClass().getName())) {
                        return child;
                    }
                }
            }
        } catch (Throwable ignored) {
            // Best effort
        }
        return null;
    }

    /**
     * Get an architecture layer root.
     *
     * @param name the layer name (OA, SA, LA, PA, EPBS, or full names like "operational",
     *     "logical")
     * @return the layer root element, or null if not found
     */
    public EObject layer(String name) {
        if (session == null || name == null) {
            return null;
        }
        String canonical = ArchitectureLocator.canonicalizeLayer(name);
        if (canonical == null) {
            throw new IllegalArgumentException(
                    "Unknown architecture layer '"
                            + name
                            + "'. Valid layers: OA, SA, LA, PA, EPBS.");
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

    /** Alias for {@link #layer(String)} for clarity in scripts. */
    public EObject root(String layer) {
        return layer(layer);
    }

    /** Get the End-Product Breakdown Structure architecture. */
    public EObject epbs() {
        return layer("EPBS");
    }

    /**
     * Get a standard container (package/root) for a given layer and kind.
     *
     * <p>Examples:
     *
     * <pre>{@code
     * def compPkg = api.container("SA", "component")
     * def funcPkg = api.container("LA", "function")
     * def rootFn = api.container("PA", "root-function")
     * }</pre>
     */
    public EObject container(String layer, String kind) {
        if (session == null || kind == null || kind.isBlank()) {
            return null;
        }
        EObject root = layer(layer);
        if (root instanceof org.polarsys.capella.core.data.epbs.EPBSArchitecture epbsArchitecture) {
            String normalized = kind.trim().toLowerCase(Locale.ROOT);
            if (normalized.equals("configuration-item")
                    || normalized.equals("configuration item")
                    || normalized.equals("configurationitem")
                    || normalized.equals("component")
                    || normalized.equals("components")) {
                return epbsArchitecture.getOwnedConfigurationItemPkg();
            }
        }
        if (!(root instanceof BlockArchitecture)) {
            return null;
        }
        try {
            BlockArchitecture architecture = (BlockArchitecture) root;
            String normalized = kind.trim().toLowerCase(Locale.ROOT);
            switch (normalized) {
                case "component":
                case "components":
                case "componentpkg":
                case "component-pkg":
                case "component package":
                    return BlockArchitectureExt.getComponentPkg(architecture, false);
                case "function":
                case "functions":
                case "functionpkg":
                case "function-pkg":
                case "function package":
                    return BlockArchitectureExt.getFunctionPkg(architecture, false);
                case "root-function":
                case "root function":
                case "rootfunction":
                    return BlockArchitectureExt.getRootFunction(architecture, false);
                case "data":
                case "datapkg":
                case "data-pkg":
                case "data package":
                    return BlockArchitectureExt.getDataPkg(architecture, false);
                case "interface":
                case "interfacepkg":
                case "interface-pkg":
                case "interface package":
                    return BlockArchitectureExt.getInterfacePkg(architecture, false);
                case "capability":
                case "capabilitypkg":
                case "capability-pkg":
                case "capability package":
                case "abstract capability":
                case "abstractcapability":
                    return BlockArchitectureExt.getAbstractCapabilityPkg(architecture, false);
                case "actor":
                case "actorpkg":
                case "actor-pkg":
                case "actor package":
                    return BlockArchitectureExt.getActorPkg(architecture, false);
                case "context":
                case "contextpkg":
                case "context-pkg":
                case "context package":
                    return BlockArchitectureExt.getContext(architecture, false);
                default:
                    return null;
            }
        } catch (Throwable ignored) {
            // Fall through to EMF-based resolution
        }
        return findContainerFallback(root, kind);
    }

    /**
     * Get a standard container for the given element using Capella semantic helpers.
     *
     * <p>Useful when you have a reference element and want the canonical package/root for similar
     * elements in the same layer.
     */
    public EObject containerFor(EObject element) {
        if (element == null) {
            return null;
        }
        try {
            if (element instanceof FunctionalExchange) {
                EObject container =
                        FunctionalExchangeExt.getDefaultContainer((FunctionalExchange) element);
                if (container != null) {
                    return container;
                }
            }
            if (element instanceof ComponentExchange) {
                EObject container =
                        ComponentExchangeExt.getDefaultContainer((ComponentExchange) element);
                if (container != null) {
                    return container;
                }
            }
        } catch (Throwable ignored) {
            // Fall back to kind-based resolution
        }

        String kind = guessContainerKind(element);
        if (kind == null) {
            return element.eContainer();
        }
        String layer = guessLayerFromType(element.eClass().getName());
        EObject container = container(layer, kind);
        return container != null ? container : element.eContainer();
    }

    /** Get the parent of an element. */
    public EObject parent(EObject element) {
        return element != null ? element.eContainer() : null;
    }

    /** Get the children of an element. */
    public List<EObject> children(EObject element) {
        if (element == null) {
            return List.of();
        }
        return new ArrayList<>(element.eContents());
    }

    /** Get all descendants of an element. */
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

    /** Get the type name of an element. */
    public String type(EObject element) {
        return element != null ? element.eClass().getName() : null;
    }

    /** Get the name of an element. */
    public String name(EObject element) {
        return getName(element);
    }

    /** Get the ID/UUID of an element. */
    public String id(EObject element) {
        return getUuid(element);
    }

    /** Summarize a value for compact logging/LLM output. */
    public String summarize(Object value) {
        return summarize(value, 5);
    }

    /** Describe an element with a dense, one-hop graph of attributes and references. */
    public String describe(EObject element) {
        if (element == null) {
            return "No element provided.";
        }
        return ModelSummary.describeElement(element);
    }

    /** Summarize a value with a maximum number of items. */
    public String summarize(Object value, int maxItems) {
        int limit = maxItems > 0 ? maxItems : 5;
        if (value == null) {
            return "null";
        }
        if (value instanceof EObject) {
            EObject element = (EObject) value;
            String name = getName(element);
            return name != null
                    ? name + " (" + element.eClass().getName() + ")"
                    : element.eClass().getName();
        }
        if (value instanceof java.util.Map<?, ?> map) {
            java.util.List<String> keys = new java.util.ArrayList<>();
            for (Object key : map.keySet()) {
                if (key == null) continue;
                keys.add(key.toString());
                if (keys.size() >= limit) break;
            }
            return "map(size=" + map.size() + ", keys=" + keys + ")";
        }
        if (value instanceof java.util.Collection<?> collection) {
            java.util.List<String> items = new java.util.ArrayList<>();
            for (Object item : collection) {
                if (items.size() >= limit) break;
                items.add(formatSummaryItem(item));
            }
            return "list(size=" + collection.size() + ", items=" + items + ")";
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            java.util.List<String> items = new java.util.ArrayList<>();
            for (int i = 0; i < length && i < limit; i++) {
                Object item = java.lang.reflect.Array.get(value, i);
                items.add(formatSummaryItem(item));
            }
            return "array(size=" + length + ", items=" + items + ")";
        }
        if (value instanceof String) {
            String str = (String) value;
            if (str.length() > 200) {
                return str.substring(0, 200) + "...";
            }
            return str;
        }
        return String.valueOf(value);
    }

    /**
     * Evaluate an AQL expression using the Sirius interpreter.
     *
     * @param expression AQL expression (e.g., "aql:self.name")
     * @return the evaluation result, or null if unavailable
     */
    public Object aql(String expression) {
        return aql(expression, null);
    }

    /**
     * Evaluate an AQL expression using the Sirius interpreter with a context element.
     *
     * @param expression AQL expression (e.g., "aql:self.name")
     * @param context context EObject (defaults to SystemEngineering/root if null)
     * @return the evaluation result, or null if unavailable
     */
    public Object aql(String expression, EObject context) {
        if (session == null || expression == null || expression.isBlank()) {
            return null;
        }
        EObject ctx = context != null ? context : defaultAqlContext();
        if (ctx == null) {
            return null;
        }
        Object interpreter = getInterpreter(session);
        if (interpreter == null) {
            interpreter = getInterpreter(ctx);
        }
        return evaluate(interpreter, ctx, expression);
    }

    // ========== RAW API ACCESS ==========

    /**
     * Execute raw Capella API code within a safe transaction. Use this when standard API methods
     * don't support your use case.
     *
     * <p>This enables dynamic access to ALL Capella functionality by allowing direct use of
     * internal Capella factories and EMF APIs.
     *
     * <p>Example:
     *
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
        return SessionSafeguards.runWrite(
                session,
                "Raw API execution",
                () -> {
                    try {
                        Object result = closure.call();
                        invalidate();
                        return result;
                    } catch (SessionSafeguards.ScriptCancelledException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new RuntimeException(
                                "Raw API execution failed: " + e.getMessage(), e);
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
        return SessionSafeguards.runWrite(
                session,
                label,
                () -> {
                    try {
                        Object result = closure.call();
                        invalidate();
                        return result;
                    } catch (SessionSafeguards.ScriptCancelledException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new RuntimeException(label + " failed: " + e.getMessage(), e);
                    }
                });
    }

    /**
     * Execute multiple operations as a single undoable batch. All operations within the closure
     * will appear as a single entry in the undo/redo stack.
     *
     * <p>Use this when an LLM generates multiple related operations that should be undone together,
     * such as creating a component with its functions and exchanges.
     *
     * <p>Example:
     *
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
        if (SessionSafeguards.isInWriteContext()) {
            OperationInferencer.hintLabel(finalLabel);
        }

        // runWrite owns save deferral on the UI thread and saves after commit.
        return SessionSafeguards.runWrite(
                session,
                finalLabel,
                () -> {
                    try {
                        OperationInferencer.hintLabel(finalLabel);
                        Object result = closure.call();
                        invalidate();
                        return result;
                    } catch (SessionSafeguards.ScriptCancelledException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new RuntimeException(finalLabel + " failed: " + e.getMessage(), e);
                    }
                });
    }

    // ========== VALIDATION Operations ==========

    /** Run Capella's installed batch validator, including its live constraints. */
    public Map<String, Object> validateNative() {
        return validateNative(200);
    }

    /** Complete counts with a caller-selected, bounded number of diagnostic messages. */
    public Map<String, Object> validateNative(int maximumMessages) {
        if (maximumMessages < 1 || maximumMessages > 10000) {
            throw new IllegalArgumentException(
                    "Native validation message limit must be between 1 and 10000");
        }
        if (session == null || !session.isOpen()) {
            throw new IllegalStateException("Native validation requires an open Capella model");
        }
        for (Resource resource : session.getSemanticResources()) {
            if (!resource.getErrors().isEmpty()) {
                throw new IllegalStateException(
                        "Model resource failed to load: "
                                + resource.getURI()
                                + ". "
                                + resource.getErrors().get(0).getMessage()
                                + ". Repair and reopen it before native validation.");
            }
        }
        List<EObject> projects =
                session.getSemanticResources().stream()
                        .flatMap(resource -> resource.getContents().stream())
                        .filter(element -> element instanceof Project)
                        .toList();
        if (projects.size() != 1) {
            throw new IllegalStateException(
                    "Native validation requires exactly one Project in the active session");
        }
        var bundle =
                org.eclipse.core.runtime.Platform.getBundle("org.polarsys.capella.core.validation");
        if (bundle == null)
            throw new IllegalStateException("Capella native validation is unavailable");
        try {
            Object adapter =
                    bundle.loadClass("org.polarsys.capella.core.validation.CapellaValidatorAdapter")
                            .getConstructor()
                            .newInstance();
            Object validator = adapter.getClass().getMethod("getValidator").invoke(adapter);
            Class<?> contract =
                    bundle.loadClass("org.eclipse.emf.validation.service.IBatchValidator");
            contract.getMethod("setIncludeLiveConstraints", boolean.class).invoke(validator, true);
            var status =
                    (org.eclipse.core.runtime.IStatus)
                            contract.getMethod(
                                            "validate",
                                            EObject.class,
                                            org.eclipse.core.runtime.IProgressMonitor.class)
                                    .invoke(
                                            validator,
                                            projects.get(0),
                                            new org.eclipse.core.runtime.NullProgressMonitor());
            List<org.eclipse.core.runtime.IStatus> issues = new ArrayList<>();
            collectNativeIssues(status, issues);
            NativeValidationDiagnostics diagnostics = new NativeValidationDiagnostics(bundle);
            List<Map<String, Object>> messages =
                    issues.stream()
                            .sorted(
                                    java.util.Comparator.comparingInt(
                                                    org.eclipse.core.runtime.IStatus::getSeverity)
                                            .reversed())
                            .limit(maximumMessages)
                            .map(diagnostics::message)
                            .toList();
            return Map.of(
                    "severity",
                    status.getSeverity(),
                    "errors",
                    issues.stream()
                            .filter(
                                    issue ->
                                            issue.getSeverity()
                                                    >= org.eclipse.core.runtime.IStatus.ERROR)
                            .count(),
                    "warnings",
                    issues.stream()
                            .filter(
                                    issue ->
                                            issue.getSeverity()
                                                    == org.eclipse.core.runtime.IStatus.WARNING)
                            .count(),
                    "issueCount",
                    issues.size(),
                    "issues",
                    messages,
                    "truncated",
                    issues.size() > messages.size());
        } catch (ReflectiveOperationException failure) {
            Throwable cause =
                    failure instanceof java.lang.reflect.InvocationTargetException invocation
                            ? invocation.getTargetException()
                            : failure;
            throw new IllegalStateException(
                    "Native Capella validation failed: " + cause.getMessage(), cause);
        }
    }

    private static void collectNativeIssues(
            org.eclipse.core.runtime.IStatus status,
            List<org.eclipse.core.runtime.IStatus> issues) {
        if (status.isMultiStatus()) {
            for (var child : status.getChildren()) collectNativeIssues(child, issues);
        } else if (!status.isOK()) {
            issues.add(status);
        }
    }

    /** Validate the model and return issues. */
    public List<String> validate() {
        if (session == null) {
            return List.of("Validation unavailable: no active Capella session.");
        }
        var domain = session.getTransactionalEditingDomain();
        if (domain == null) {
            return List.of("Validation unavailable: no editing domain.");
        }
        ModelAnalytics.ValidationReport report =
                ModelAnalytics.ValidationReport.fromSession(domain.getResourceSet());
        return formatValidationReport("Model validation", report);
    }

    /**
     * Validate a specific architecture layer.
     *
     * @param layer the layer (OA, SA, LA, PA, EPBS)
     */
    public List<String> validate(String layer) {
        if (session == null) {
            return List.of("Validation unavailable: no active Capella session.");
        }
        if (layer == null || layer.isBlank()) {
            return List.of("Validation unavailable: layer cannot be blank.");
        }
        EObject root = layer(layer);
        if (root == null) {
            return List.of(
                    "Validation unavailable: architecture layer '"
                            + layer
                            + "' not found. Valid layers: OA, SA, LA, PA, EPBS.");
        }
        return validateRoot(root, "Layer validation (" + layer.trim().toUpperCase() + ")");
    }

    /** Validate a specific element (and a limited subset of its descendants). */
    public List<String> validate(EObject element) {
        if (element == null) {
            return List.of("Validation unavailable: element is null.");
        }
        return validateRoot(element, "Element validation");
    }

    // ========== UNDO/REDO Operations ==========

    /**
     * Undo the last operation.
     *
     * @return true if successful
     */
    public boolean undo() {
        if (session == null) return false;
        java.util.concurrent.atomic.AtomicBoolean success =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        SessionSafeguards.runOnUiThread(
                () -> {
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
     *
     * @return true if successful
     */
    public boolean redo() {
        if (session == null) return false;
        java.util.concurrent.atomic.AtomicBoolean success =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        SessionSafeguards.runOnUiThread(
                () -> {
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
     * Propagate an element to another layer. Creates a corresponding element in the target layer
     * with a realization link.
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

    /** Get the current session. */
    public Session getSession() {
        return session;
    }

    /** Check if a session is active. */
    public boolean hasSession() {
        return session != null;
    }

    /** Get a model summary. */
    public String summary() {
        if (session == null) {
            return "No active session";
        }
        return ModelSummary.summarize(session);
    }

    /** Reset the current Groovy execution context (clears user variables). */
    public void reset() {
        if (resetHandler == null) {
            throw new IllegalStateException(
                    "reset() is only available inside the code execution context.");
        }
        resetHandler.run();
    }

    // ========== Private Helpers ==========

    private static List<String> formatValidationReport(
            String label, ModelAnalytics.ValidationReport report) {
        if (report == null || !report.isAvailable()) {
            return List.of(label + ": validation unavailable.");
        }
        List<String> messages = new ArrayList<>();
        messages.add(
                label
                        + ": "
                        + report.errorCount()
                        + " errors, "
                        + report.warningCount()
                        + " warnings.");
        messages.addAll(report.messages());
        return messages;
    }

    private List<String> validateRoot(EObject root, String label) {
        List<String> messages = new ArrayList<>();
        int errors = 0;
        int warnings = 0;
        int checked = 0;
        final int MAX_CHECK = 250;
        final int MAX_MESSAGES = 75;

        Diagnostic diag = Diagnostician.INSTANCE.validate(root);
        CountResult count = collectDiagnostics(diag, messages, MAX_MESSAGES);
        errors += count.errors;
        warnings += count.warnings;
        checked++;

        var it = root.eAllContents();
        while (it.hasNext() && checked < MAX_CHECK) {
            EObject eo = it.next();
            Diagnostic d = Diagnostician.INSTANCE.validate(eo);
            CountResult result = collectDiagnostics(d, messages, MAX_MESSAGES);
            errors += result.errors;
            warnings += result.warnings;
            checked++;
            if (messages.size() >= MAX_MESSAGES) {
                break;
            }
        }

        List<String> out = new ArrayList<>();
        if (messages.isEmpty()) {
            out.add(label + ": no issues found in quick scan.");
            return out;
        }
        out.add(label + ": " + errors + " errors, " + warnings + " warnings.");
        out.addAll(messages);
        if (checked >= MAX_CHECK) {
            out.add("Validation note: quick scan capped at " + MAX_CHECK + " elements.");
        }
        if (messages.size() >= MAX_MESSAGES) {
            out.add("Validation note: message list capped at " + MAX_MESSAGES + " entries.");
        }
        return out;
    }

    private static CountResult collectDiagnostics(
            Diagnostic diagnostic, List<String> messages, int maxMessages) {
        if (diagnostic == null) {
            return new CountResult(0, 0);
        }
        int errors = 0;
        int warnings = 0;
        if (diagnostic.getSeverity() != Diagnostic.OK) {
            if (diagnostic.getSeverity() == Diagnostic.ERROR) {
                errors++;
            } else if (diagnostic.getSeverity() == Diagnostic.WARNING) {
                warnings++;
            }
            if (messages.size() < maxMessages) {
                messages.add(formatDiagnostic(diagnostic));
            }
        }
        for (Diagnostic child : diagnostic.getChildren()) {
            if (messages.size() >= maxMessages) {
                break;
            }
            CountResult childCount = collectDiagnostics(child, messages, maxMessages);
            errors += childCount.errors;
            warnings += childCount.warnings;
        }
        return new CountResult(errors, warnings);
    }

    private static String formatDiagnostic(Diagnostic diagnostic) {
        String source = diagnostic.getSource() != null ? diagnostic.getSource() : "Unknown";
        String severity =
                switch (diagnostic.getSeverity()) {
                    case Diagnostic.ERROR -> "ERROR";
                    case Diagnostic.WARNING -> "WARNING";
                    case Diagnostic.INFO -> "INFO";
                    default -> "UNKNOWN";
                };
        return severity + " " + source + ": " + diagnostic.getMessage();
    }

    private static final class CountResult {
        private final int errors;
        private final int warnings;

        private CountResult(int errors, int warnings) {
            this.errors = errors;
            this.warnings = warnings;
        }
    }

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

    /** Clear caches if they've been invalidated by a mutation. */
    private void ensureCacheValid() {
        var domain = session != null ? session.getTransactionalEditingDomain() : null;
        if (domain != null) {
            var stack = domain.getCommandStack();
            var undo = stack.getUndoCommand();
            var redo = stack.getRedoCommand();
            if (undo != cachedUndoCommand || redo != cachedRedoCommand) {
                cacheDirty = true;
                cachedUndoCommand = undo;
                cachedRedoCommand = redo;
            }
        }
        if (cacheDirty) {
            findCache.clear();
            findAllCache.clear();
            cacheDirty = false;
        }
    }

    /** Generate cache key for find(type, name). */
    private static String findCacheKey(String type, String name) {
        return type + ":" + name;
    }

    private String getName(EObject element) {
        if (element == null) {
            return null;
        }
        try {
            EStructuralFeature feature = element.eClass().getEStructuralFeature("name");
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
            EStructuralFeature feature = element.eClass().getEStructuralFeature("id");
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

    private static boolean isClassPresent(String className) {
        if (className == null || className.isBlank()) {
            return false;
        }
        try {
            Class.forName(className, false, CapellaAPI.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private EObject findContainerFallback(EObject root, String kind) {
        if (root == null || kind == null || kind.isBlank()) {
            return null;
        }
        String normalized = kind.trim().toLowerCase(Locale.ROOT);

        if (normalized.equals("root-function")
                || normalized.equals("root function")
                || normalized.equals("rootfunction")) {
            EObject functionPkg = findContainerFallback(root, "function");
            EObject rootFunction =
                    findFirstChildByClassName(
                            functionPkg != null ? functionPkg : root,
                            List.of(
                                    "SystemFunction",
                                    "LogicalFunction",
                                    "PhysicalFunction",
                                    "OperationalActivity"));
            if (rootFunction != null) {
                return rootFunction;
            }
        }

        List<String> classHints =
                switch (normalized) {
                    case "component",
                                    "components",
                                    "componentpkg",
                                    "component-pkg",
                                    "component package",
                                    "actor",
                                    "actorpkg",
                                    "actor-pkg",
                                    "actor package",
                                    "context",
                                    "contextpkg",
                                    "context-pkg",
                                    "context package" ->
                            List.of(
                                    "ComponentPkg",
                                    "SystemComponentPkg",
                                    "LogicalComponentPkg",
                                    "PhysicalComponentPkg",
                                    "EntityPkg");
                    case "function",
                                    "functions",
                                    "functionpkg",
                                    "function-pkg",
                                    "function package" ->
                            List.of(
                                    "FunctionPkg",
                                    "SystemFunctionPkg",
                                    "LogicalFunctionPkg",
                                    "PhysicalFunctionPkg",
                                    "OperationalActivityPkg");
                    case "data", "datapkg", "data-pkg", "data package" -> List.of("DataPkg");
                    case "interface", "interfacepkg", "interface-pkg", "interface package" ->
                            List.of("InterfacePkg");
                    case "capability",
                                    "capabilitypkg",
                                    "capability-pkg",
                                    "capability package",
                                    "abstract capability",
                                    "abstractcapability" ->
                            List.of("CapabilityPkg", "AbstractCapabilityPkg");
                    default -> List.of();
                };

        EObject direct = findContainedByTypeName(root, classHints);
        if (direct != null) {
            return direct;
        }
        return findFirstChildByClassName(root, classHints);
    }

    private boolean isPart(EObject element) {
        return element != null && "Part".equals(element.eClass().getName());
    }

    private boolean isComponentLike(EObject element) {
        if (element == null) {
            return false;
        }
        String name = element.eClass().getName();
        if (name == null) {
            return false;
        }
        return name.endsWith("Component")
                || name.equals("Entity")
                || name.equals("ConfigurationItem");
    }

    private String guessContainerKind(EObject element) {
        if (element == null) {
            return null;
        }
        String name = element.eClass().getName();
        if (name == null) {
            return null;
        }
        if (isFunctionType(element, name)) {
            return "root-function";
        }
        if (isFunctionPkgType(name)) {
            return "function";
        }
        if (isComponentLike(element) || isComponentPkgType(name)) {
            return "component";
        }
        if (isInterfaceType(name) || isInterfacePkgType(name)) {
            return "interface";
        }
        if (isDataType(name) || isDataPkgType(name)) {
            return "data";
        }
        if (isCapabilityType(name)) {
            return "capability";
        }
        return null;
    }

    private boolean isFunctionType(EObject element, String name) {
        if (name.endsWith("Function") || name.equals("OperationalActivity")) {
            return true;
        }
        if (element == null) {
            return false;
        }
        for (EClass superType : element.eClass().getEAllSuperTypes()) {
            if ("AbstractFunction".equals(superType.getName())) {
                return true;
            }
        }
        return false;
    }

    private boolean isFunctionPkgType(String name) {
        return name.endsWith("FunctionPkg")
                || "FunctionPkg".equals(name)
                || "OperationalActivityPkg".equals(name);
    }

    private boolean isComponentPkgType(String name) {
        return name.endsWith("ComponentPkg")
                || name.endsWith("EntityPkg")
                || name.equals("ConfigurationItemPkg");
    }

    private boolean isInterfacePkgType(String name) {
        return name.endsWith("InterfacePkg") || "InterfacePkg".equals(name);
    }

    private boolean isInterfaceType(String name) {
        return name.endsWith("Interface") && !name.endsWith("InterfacePkg");
    }

    private boolean isDataPkgType(String name) {
        return name.endsWith("DataPkg") || "DataPkg".equals(name);
    }

    private boolean isDataType(String name) {
        return name.endsWith("DataType")
                || name.equals("Class")
                || name.equals("Enumeration")
                || name.equals("ExchangeItem");
    }

    private boolean isCapabilityType(String name) {
        return name.endsWith("Capability")
                || name.endsWith("CapabilityRealization")
                || name.endsWith("AbstractCapability");
    }

    private EObject findPartForComponent(EObject component) {
        if (session == null || component == null) {
            return null;
        }
        try {
            for (Resource res : session.getSemanticResources()) {
                if (res == null) {
                    continue;
                }
                var it = res.getAllContents();
                while (it.hasNext()) {
                    EObject eo = it.next();
                    // abstractType is the persistent native reference. Part.type
                    // is derived and can invoke additional Capella adapters.
                    if (eo instanceof Part part && component.equals(part.getAbstractType())) {
                        return eo;
                    }
                }
            }
        } catch (Exception e) {
            // Best effort
        }
        return null;
    }

    private EObject resolvePartContainer(EObject component, EObject preferredRoot) {
        EObject normalized = normalizePartContainer(preferredRoot, component);
        if (normalized != null) {
            return normalized;
        }
        // Native component creation places the Part alongside its type, under
        // the immediate owning Component or ComponentPkg.
        EObject immediateOwner = component.eContainer();
        if (canContainPart(immediateOwner)) {
            return immediateOwner;
        }
        EObject componentPkg = findComponentPackage(component);
        if (componentPkg == null) {
            componentPkg = findComponentPackageFromLayer(component);
        }
        if (componentPkg != null) {
            if (canContainPart(componentPkg)) {
                return componentPkg;
            }
            EObject rootComponent = findRootComponentCandidate(componentPkg);
            if (rootComponent != null) {
                return rootComponent;
            }
        }
        EObject layerRoot = findLayerRootForComponent(component);
        if (layerRoot != null) {
            EObject rootComponent = findRootComponentCandidate(layerRoot);
            if (rootComponent != null) {
                return rootComponent;
            }
        }
        EObject container = component != null ? component.eContainer() : null;
        if (canContainPart(container)) {
            return container;
        }
        return null;
    }

    private EObject normalizePartContainer(EObject preferredRoot, EObject component) {
        if (preferredRoot == null) {
            return null;
        }
        String name = preferredRoot.eClass().getName();
        if (isComponentLike(preferredRoot)) {
            EObject pkg = findComponentPackage(component);
            if (pkg == null) {
                pkg = findComponentPackageFromLayer(component);
            }
            if (pkg != null && canContainPart(pkg)) {
                return pkg;
            }
            return preferredRoot;
        }
        if (isComponentPkgType(name)) {
            EObject rootComponent = findRootComponentCandidate(preferredRoot);
            if (canContainPart(preferredRoot)) {
                return preferredRoot;
            }
            if (rootComponent != null) {
                return rootComponent;
            }
        }
        if (name.endsWith("Analysis") || name.endsWith("Architecture")) {
            EObject pkg = findComponentPackage(component);
            if (pkg == null) {
                pkg = findComponentPackageFromLayer(component);
            }
            if (pkg != null && canContainPart(pkg)) {
                return pkg;
            }
            EObject rootComponent = findRootComponentCandidate(preferredRoot);
            if (rootComponent != null) {
                return rootComponent;
            }
        }
        return null;
    }

    private EObject findComponentPackage(EObject element) {
        EObject current = element;
        while (current != null) {
            String name = current.eClass().getName();
            if (isComponentPkgType(name)) {
                return current;
            }
            current = current.eContainer();
        }
        return null;
    }

    private EObject findComponentPackageFromLayer(EObject element) {
        if (element == null) {
            return null;
        }
        String layer = guessLayerFromType(element.eClass().getName());
        if (layer == null) {
            return null;
        }
        EObject root = layer(layer);
        if (root == null) {
            return null;
        }
        return findContainerFallback(root, "component");
    }

    private EObject findRootComponentCandidate(EObject root) {
        return findFirstChildByClassName(
                root,
                List.of(
                        "SystemComponent",
                        "LogicalComponent",
                        "PhysicalComponent",
                        "Entity",
                        "ConfigurationItem"));
    }

    private EObject findLayerRootForComponent(EObject component) {
        if (component == null) {
            return null;
        }
        String layer = guessLayerFromType(component.eClass().getName());
        if (layer == null) {
            return null;
        }
        return layer(layer);
    }

    private boolean canContainPart(EObject container) {
        if (container == null) {
            return false;
        }
        EClass partClass = CsFactory.eINSTANCE.createPart().eClass();
        for (EStructuralFeature feature : container.eClass().getEAllStructuralFeatures()) {
            if (!(feature instanceof EReference)) {
                continue;
            }
            EReference ref = (EReference) feature;
            if (!isSemanticContainment(ref)) {
                continue;
            }
            if (!(ref.getEType() instanceof EClass)) {
                continue;
            }
            EClass type = (EClass) ref.getEType();
            if (type == partClass || type.isSuperTypeOf(partClass)) {
                return true;
            }
        }
        return false;
    }

    private boolean addToContainment(EObject container, EObject child) {
        if (container == null || child == null) {
            return false;
        }
        EReference preferred =
                findContainmentByName(container, List.of("ownedFeatures", "ownedParts"));
        if (preferred != null) {
            return addToReference(container, preferred, child);
        }
        EClass childClass = child.eClass();
        for (EStructuralFeature feature : container.eClass().getEAllStructuralFeatures()) {
            if (!(feature instanceof EReference)) {
                continue;
            }
            EReference ref = (EReference) feature;
            if (!isSemanticContainment(ref)) {
                continue;
            }
            if (!(ref.getEType() instanceof EClass)) {
                continue;
            }
            EClass type = (EClass) ref.getEType();
            if (type == childClass || type.isSuperTypeOf(childClass)) {
                return addToReference(container, ref, child);
            }
        }
        return false;
    }

    private EReference findContainmentByName(EObject container, List<String> names) {
        if (container == null || names == null || names.isEmpty()) {
            return null;
        }
        for (EStructuralFeature feature : container.eClass().getEAllStructuralFeatures()) {
            if (!(feature instanceof EReference)) {
                continue;
            }
            EReference ref = (EReference) feature;
            if (!isSemanticContainment(ref)) {
                continue;
            }
            for (String name : names) {
                if (name.equalsIgnoreCase(ref.getName())) {
                    return ref;
                }
            }
        }
        return null;
    }

    private boolean addToReference(EObject container, EReference ref, EObject child) {
        if (!isSemanticContainment(ref) || !ref.getEReferenceType().isInstance(child)) {
            return false;
        }
        try {
            if (ref.isMany()) {
                @SuppressWarnings("unchecked")
                java.util.List<EObject> list = (java.util.List<EObject>) container.eGet(ref);
                list.add(child);
                return true;
            }
            container.eSet(ref, child);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isSemanticContainment(EReference ref) {
        return ref.isContainment()
                && ref.isChangeable()
                && !ref.isDerived()
                && !ref.isTransient()
                && !ref.isVolatile()
                && !"ownedMigratedElements".equals(ref.getName())
                && !"ownedExtensions".equals(ref.getName());
    }

    private boolean setFeatureIfPresent(EObject target, String featureName, Object value) {
        if (target == null || featureName == null) {
            return false;
        }
        EStructuralFeature feature = target.eClass().getEStructuralFeature(featureName);
        if (feature == null || !feature.isChangeable()) {
            return false;
        }
        try {
            target.eSet(feature, value);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private EObject findContainedByTypeName(EObject root, List<String> classNames) {
        if (root == null || classNames == null || classNames.isEmpty()) {
            return null;
        }
        for (EStructuralFeature feature : root.eClass().getEAllStructuralFeatures()) {
            if (!(feature instanceof org.eclipse.emf.ecore.EReference)) {
                continue;
            }
            var ref = (org.eclipse.emf.ecore.EReference) feature;
            if (!ref.isContainment()) {
                continue;
            }
            if (ref.getEType() instanceof org.eclipse.emf.ecore.EClass) {
                String typeName = ref.getEType().getName();
                if (typeName != null && classNames.contains(typeName)) {
                    Object value = root.eGet(ref);
                    if (value instanceof java.util.List<?>) {
                        java.util.List<?> list = (java.util.List<?>) value;
                        for (Object item : list) {
                            if (item instanceof EObject) {
                                return (EObject) item;
                            }
                        }
                    } else if (value instanceof EObject) {
                        return (EObject) value;
                    }
                }
            }
        }
        return null;
    }

    private EObject findFirstChildByClassName(EObject root, List<String> classNames) {
        if (root == null || classNames == null || classNames.isEmpty()) {
            return null;
        }
        java.util.ArrayDeque<EObject> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            EObject current = queue.removeFirst();
            String name = current.eClass().getName();
            if (name != null && classNames.contains(name)) {
                return current;
            }
            for (EObject child : current.eContents()) {
                queue.addLast(child);
            }
        }
        return null;
    }

    private String formatSummaryItem(Object item) {
        if (item == null) {
            return "null";
        }
        if (item instanceof EObject) {
            EObject eo = (EObject) item;
            String name = getName(eo);
            return name != null ? name + " (" + eo.eClass().getName() + ")" : eo.eClass().getName();
        }
        return item.toString();
    }

    private EObject defaultAqlContext() {
        EObject se = systemEngineering();
        if (se != null) {
            return se;
        }
        EObject sa = layer("SA");
        if (sa != null) {
            return sa;
        }
        try {
            for (Resource res : session.getSemanticResources()) {
                if (res == null || res.getContents().isEmpty()) {
                    continue;
                }
                return res.getContents().get(0);
            }
        } catch (Exception e) {
            // Best effort
        }
        return null;
    }

    private static Object getInterpreter(Session session) {
        if (session == null) {
            return null;
        }
        try {
            var method = session.getClass().getMethod("getInterpreter");
            return method.invoke(session);
        } catch (Exception ex) {
            return null;
        }
    }

    private static Object getInterpreter(EObject context) {
        if (context == null) {
            return null;
        }
        try {
            return InterpreterUtil.getInterpreter(context);
        } catch (Exception ex) {
            return null;
        }
    }

    private static Object evaluate(Object interpreter, EObject context, String expression) {
        if (interpreter == null || context == null || expression == null) {
            return null;
        }
        try {
            var method =
                    interpreter
                            .getClass()
                            .getMethod("evaluateExpression", EObject.class, String.class);
            return unwrapResult(method.invoke(interpreter, context, expression));
        } catch (Exception ignored) {
            // fallback to evaluate
        }
        try {
            var method = interpreter.getClass().getMethod("evaluate", EObject.class, String.class);
            return unwrapResult(method.invoke(interpreter, context, expression));
        } catch (Exception ignored) {
            // fallback to evaluateBoolean
        }
        try {
            var method =
                    interpreter
                            .getClass()
                            .getMethod("evaluateBoolean", EObject.class, String.class);
            return method.invoke(interpreter, context, expression);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Object unwrapResult(Object result) {
        if (result == null) {
            return null;
        }
        if ("org.eclipse.sirius.common.tools.api.interpreter.EvaluationResult"
                .equals(result.getClass().getName())) {
            try {
                // Check status: OK, ERROR, WARNING, etc.
                // EvaluationResult.getValue() returns the raw object.
                var valueMethod = result.getClass().getMethod("getValue");
                return valueMethod.invoke(result);
            } catch (Exception e) {
                return result; // Fallback: return wrapper
            }
        }
        return result;
    }

    private String guessLayerFromType(String type) {
        if (type == null) return "SA";
        String lower = type.toLowerCase();
        if (lower.startsWith("configurationitem") || lower.startsWith("epbs")) return "EPBS";
        if (lower.equals("entity") || lower.startsWith("operational") || lower.startsWith("oa"))
            return "OA";
        if (lower.startsWith("logical") || lower.startsWith("la")) return "LA";
        if (lower.startsWith("physical") || lower.startsWith("pa")) return "PA";
        return "SA"; // Default to System Analysis
    }

    private static boolean isLayerToken(String type) {
        if (type == null) return false;
        String upper = type.trim().toUpperCase();
        return "OA".equals(upper)
                || "SA".equals(upper)
                || "LA".equals(upper)
                || "PA".equals(upper)
                || "EPBS".equals(upper);
    }

    private String mapTypeToLayer(String type, String targetLayer) {
        if (type == null || targetLayer == null) return null;

        // Map common types between layers
        String layer = targetLayer.toUpperCase();
        return switch (layer) {
            case "LA" ->
                    switch (type) {
                        case "SystemComponent" -> "LogicalComponent";
                        case "SystemFunction" -> "LogicalFunction";
                        default -> type.replace("System", "Logical");
                    };
            case "PA" ->
                    switch (type) {
                        case "SystemComponent", "LogicalComponent" -> "PhysicalComponent";
                        case "SystemFunction", "LogicalFunction" -> "PhysicalFunction";
                        default ->
                                type.replace("System", "Physical").replace("Logical", "Physical");
                    };
            case "SA" ->
                    switch (type) {
                        case "OperationalActivity" -> "SystemFunction";
                        case "Entity" -> "SystemComponent";
                        default -> type;
                    };
            default -> type;
        };
    }

    /**
     * Resolve type aliases to actual Capella types. For example, OperationalActor -> Entity,
     * SystemActor -> SystemComponent.
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

    /** Check if a type name is an actor alias that needs the actor property set. */
    private boolean isActorAlias(String type) {
        if (type == null) return false;
        return switch (type) {
            case "OperationalActor", "SystemActor", "LogicalActor", "PhysicalActor" -> true;
            default -> false;
        };
    }
}
