package org.polarsys.capella.ai.scripting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.model.ArchitectureLocator;
import org.polarsys.capella.ai.model.ElementFactory;
import org.polarsys.capella.ai.model.FindResult;
import org.polarsys.capella.ai.model.Finders;
import org.polarsys.capella.ai.model.ModelOps;
import org.polarsys.capella.ai.model.ModelSummary;
import org.polarsys.capella.ai.model.index.ModelIndex;
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

    /**
     * Create a CapellaAPI for the given session.
     */
    public CapellaAPI(Session session) {
        this.session = session;
        this.diagram = new DiagramAPI(session);
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
        // Search all semantic resources for any element with this name
        try {
            for (Resource res : session.getSemanticResources()) {
                if (res == null) continue;
                var it = res.getAllContents();
                while (it.hasNext()) {
                    EObject eo = it.next();
                    String elementName = getName(eo);
                    if (name.equals(elementName)) {
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
        if (session == null) {
            return null;
        }
        return Finders.findByName(session, type, name);
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
        List<EObject> results = new ArrayList<>();
        if (session == null || type == null) {
            return results;
        }
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
     * @return the created element, or null if creation failed
     */
    public EObject create(String type, String name) {
        if (session == null || type == null) {
            return null;
        }
        // Guess the layer from the type name
        String layer = guessLayerFromType(type);
        return create(layer, type, name);
    }

    /**
     * Create an element in a specific architecture layer.
     *
     * @param layer the layer (OA, SA, LA, PA)
     * @param type the simple class name
     * @param name the element name
     * @return the created element, or null if creation failed
     */
    public EObject create(String layer, String type, String name) {
        if (session == null || type == null) {
            return null;
        }
        EObject root = layer(layer);
        if (root == null) {
            return null;
        }
        EObject created = ElementFactory.createUnder(session, root, type, name);
        if (created != null) {
            invalidate();
        }
        return created;
    }

    /**
     * Create an element under a specific parent.
     *
     * @param type the simple class name
     * @param name the element name
     * @param parent the parent element
     * @return the created element, or null if creation failed
     */
    public EObject create(String type, String name, EObject parent) {
        if (session == null || type == null || parent == null) {
            return null;
        }
        EObject created = ElementFactory.createUnder(
            session,
            parent,
            type,
            name
        );
        if (created != null) {
            invalidate();
        }
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
     */
    public boolean set(EObject element, String property, Object value) {
        if (session == null || element == null || property == null) {
            return false;
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
            if (feature != null && feature.isChangeable()) {
                element.eSet(feature, value);
                invalidate();
                return true;
            }
            return false;
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
        String uuid = getUuid(element);
        if (uuid == null) {
            return false;
        }
        boolean deleted = ModelOps.deleteByUuid(session, uuid);
        if (deleted) {
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
        return ArchitectureLocator.findTargetElement(session, canonical);
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

    // ========== VALIDATION Operations ==========

    /**
     * Validate the model and return issues.
     */
    public List<String> validate() {
        // TODO: Integrate with Capella validation framework
        return List.of();
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
        if (session != null) {
            ModelSummary.invalidate(session);
            ModelIndex.invalidate(session);
            SessionSafeguards.requestSave(session);
        }
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
}
