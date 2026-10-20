package org.polarsys.capella.ai.scripting;

import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.sirius.business.api.session.Session;
import org.osgi.framework.Bundle;
import org.polarsys.capella.ai.model.ModelOps;
import org.polarsys.capella.ai.model.ModelSummary;
import org.polarsys.capella.ai.session.SessionSafeguards;
import org.polarsys.capella.core.data.capellacore.CapellaElement;
import org.polarsys.capella.core.data.capellacore.Constraint;
import org.polarsys.capella.core.data.cs.BlockArchitecture;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Native ReqIF requirements. The optional viewpoint is loaded from its generated model bundles. */
public final class RequirementsAPI {
    public static final String REQUIREMENTS_NS = "http://www.polarsys.org/kitalpha/requirements";
    public static final String CAPELLA_REQUIREMENTS_NS =
            "http://www.polarsys.org/capella/requirements";
    public static final String VIEWPOINT_ID = "org.polarsys.capella.vp.requirements";
    private static final String TYPE_ID = "capella-ai:requirement-type";
    private final Session session;
    private final ThreadLocal<Map<String, EObject>> importingIdentifiers = new ThreadLocal<>();

    public RequirementsAPI(Session session) {
        this.session = session;
    }

    public boolean available() {
        try {
            nativePackage(REQUIREMENTS_NS);
            nativePackage(CAPELLA_REQUIREMENTS_NS);
            return true;
        } catch (IllegalStateException failure) {
            return false;
        }
    }

    /** Referencing a viewpoint is a native metadata command, separate from semantic batches. */
    public boolean enableViewpoint() {
        requireAvailable();
        if (!Platform.isRunning()) {
            throw new IllegalStateException(
                    "Viewpoint activation requires a running Capella workbench");
        }
        if (SessionSafeguards.isInWriteContext()) {
            throw new IllegalStateException(
                    "Enable the Requirements viewpoint before starting api.batch");
        }
        boolean[] changed = {false};
        SessionSafeguards.runOnUiThread(
                () -> {
                    Object manager = viewpointManager();
                    if (!(Boolean)
                            invoke(
                                    manager,
                                    "isReferenced",
                                    new Class<?>[] {String.class},
                                    VIEWPOINT_ID)) {
                        invoke(manager, "reference", new Class<?>[] {String.class}, VIEWPOINT_ID);
                        changed[0] = true;
                        save();
                    }
                });
        return changed[0];
    }

    public boolean viewpointEnabled() {
        if (!available() || !Platform.isRunning()) return false;
        return (Boolean)
                invoke(
                        viewpointManager(),
                        "isReferenced",
                        new Class<?>[] {String.class},
                        VIEWPOINT_ID);
    }

    public EObject createModule(EObject architecture, String identifier, String title) {
        requireOwned(architecture);
        if (!(architecture instanceof BlockArchitecture)) {
            throw new IllegalArgumentException(
                    "Requirements modules must belong to an Arcadia architecture");
        }
        if (Platform.isRunning() && !viewpointEnabled()) {
            throw new IllegalStateException(
                    "Call api.requirements().enableViewpoint() before creating modules");
        }
        return write(
                "Create requirements module",
                () ->
                        createUnder(
                                architecture,
                                "ownedExtensions",
                                CAPELLA_REQUIREMENTS_NS,
                                "CapellaModule",
                                identifier,
                                title));
    }

    public EObject createFolder(EObject parent, String identifier, String title) {
        requireContainer(parent);
        return write(
                "Create requirements folder",
                () -> {
                    EObject folder =
                            createUnder(
                                    parent,
                                    "ownedRequirements",
                                    REQUIREMENTS_NS,
                                    "Folder",
                                    identifier,
                                    title);
                    set(folder, "requirementType", requirementType(architecture(parent)));
                    return folder;
                });
    }

    public EObject create(EObject parent, String identifier, String title, String shortText) {
        requireContainer(parent);
        return write(
                "Create requirement",
                () -> {
                    EObject requirement =
                            createUnder(
                                    parent,
                                    "ownedRequirements",
                                    REQUIREMENTS_NS,
                                    "Requirement",
                                    identifier,
                                    title);
                    set(requirement, "ReqIFText", shortText);
                    set(requirement, "requirementType", requirementType(architecture(parent)));
                    return requirement;
                });
    }

    /** Import an explicitly supplied ledger batch; source text is never fetched or inferred. */
    public List<EObject> importRequirements(EObject parent, List<Map<String, Object>> records) {
        requireContainer(parent);
        if (records == null) throw new IllegalArgumentException("Requirement records are required");
        return write(
                "Import source requirements",
                () -> {
                    Map<String, EObject> previous = importingIdentifiers.get();
                    Map<String, EObject> identifiers = new LinkedHashMap<>();
                    for (EObject item : all()) {
                        Object identifier = get(item, "ReqIFIdentifier");
                        if (identifier instanceof String text && !text.startsWith("capella-ai:")) {
                            if (identifiers.put(text, item) != null)
                                throw new IllegalStateException(
                                        "Ambiguous requirement identifier: " + text);
                        }
                    }
                    importingIdentifiers.set(identifiers);
                    try {
                        List<EObject> result = new ArrayList<>();
                        for (Map<String, Object> record : records) {
                            if (record == null
                                    || !(record.get("identifier") instanceof String identifier)) {
                                throw new IllegalArgumentException(
                                        "Every requirement record needs a String identifier");
                            }
                            String title = optionalString(record, "title");
                            String text = optionalString(record, "text");
                            EObject requirement = create(parent, identifier, title, text);
                            if (record.containsKey("chapter"))
                                set(
                                        requirement,
                                        "ReqIFChapterName",
                                        optionalString(record, "chapter"));
                            Object metadata = record.get("attributes");
                            if (metadata != null) {
                                if (!(metadata instanceof Map<?, ?> attributes))
                                    throw new IllegalArgumentException(
                                            "Requirement record attributes must be a map");
                                for (Map.Entry<?, ?> attribute : attributes.entrySet()) {
                                    if (!(attribute.getKey() instanceof String key))
                                        throw new IllegalArgumentException(
                                                "Requirement attribute names must be Strings");
                                    setAttribute(requirement, key, attribute.getValue());
                                }
                            }
                            result.add(requirement);
                        }
                        return result;
                    } finally {
                        if (previous == null) importingIdentifiers.remove();
                        else importingIdentifiers.set(previous);
                    }
                });
    }

    private static String optionalString(Map<String, Object> record, String key) {
        Object value = record.get(key);
        if (value != null && !(value instanceof String))
            throw new IllegalArgumentException("Requirement record '" + key + "' must be a String");
        return (String) value;
    }

    public EObject edit(EObject requirement, String title, String shortText) {
        requireRequirement(requirement);
        return write(
                "Edit requirement",
                () -> {
                    set(requirement, "ReqIFLongName", title);
                    set(requirement, "ReqIFText", shortText);
                    return requirement;
                });
    }

    /** Persistent native attributes, with one reusable definition per name and primitive type. */
    public EObject setAttribute(EObject requirement, String key, Object value) {
        requireRequirement(requirement);
        requireIdentifier(key);
        String attributeClass = attributeClass(value);
        return write(
                "Set requirement attribute",
                () -> {
                    EObject type = requirementType(architecture(requirement));
                    EObject definition =
                            list(type, "ownedAttributes").stream()
                                    .filter(item -> key.equals(get(item, "ReqIFLongName")))
                                    .findFirst()
                                    .orElse(null);
                    if (definition == null) {
                        EObject types = typesFolder(architecture(requirement));
                        EObject dataType =
                                localFind(
                                        types,
                                        "ownedDefinitionTypes",
                                        "capella-ai:datatype:" + attributeClass);
                        if (dataType == null) {
                            dataType =
                                    createUnder(
                                            types,
                                            "ownedDefinitionTypes",
                                            REQUIREMENTS_NS,
                                            "DataTypeDefinition",
                                            "capella-ai:datatype:" + attributeClass,
                                            attributeClass);
                        }
                        definition =
                                createUnder(
                                        type,
                                        "ownedAttributes",
                                        REQUIREMENTS_NS,
                                        "AttributeDefinition",
                                        "capella-ai:attribute:" + key,
                                        key);
                        set(definition, "definitionType", dataType);
                        EObject defaultValue = nativeObject(REQUIREMENTS_NS, attributeClass);
                        set(definition, "defaultValue", defaultValue);
                        set(defaultValue, "definition", definition);
                    } else {
                        EObject defaultValue = (EObject) get(definition, "defaultValue");
                        if (defaultValue == null
                                || !defaultValue.eClass().getName().equals(attributeClass)) {
                            throw new IllegalArgumentException(
                                    "Requirement attribute '"
                                            + key
                                            + "' has a different native value type");
                        }
                    }
                    EObject attribute = null;
                    for (EObject item : list(requirement, "ownedAttributes")) {
                        if (get(item, "definition") == definition) {
                            attribute = item;
                            break;
                        }
                    }
                    if (attribute == null) {
                        attribute = nativeObject(REQUIREMENTS_NS, attributeClass);
                        set(attribute, "definition", definition);
                        list(requirement, "ownedAttributes").add(attribute);
                    }
                    Object storedValue = value;
                    if (value instanceof Number number) {
                        if (attributeClass.equals("RealValueAttribute"))
                            storedValue = number.doubleValue();
                        else if (attributeClass.equals("IntegerValueAttribute"))
                            storedValue = number.intValue();
                    }
                    set(attribute, "value", storedValue);
                    set(requirement, "requirementType", type);
                    return attribute;
                });
    }

    public Map<String, Object> attributes(EObject requirement) {
        requireRequirement(requirement);
        Map<String, Object> result = new LinkedHashMap<>();
        for (EObject attribute : list(requirement, "ownedAttributes")) {
            EObject definition = (EObject) get(attribute, "definition");
            if (definition != null)
                result.put((String) get(definition, "ReqIFLongName"), get(attribute, "value"));
        }
        return result;
    }

    /** Native outgoing relation: design element -> source requirement. */
    public EObject satisfy(EObject modelElement, EObject requirement) {
        requireModelElement(modelElement);
        requireRequirement(requirement);
        return write(
                "Trace requirement satisfaction",
                () ->
                        relation(
                                modelElement,
                                "ownedExtensions",
                                CAPELLA_REQUIREMENTS_NS,
                                "CapellaOutgoingRelation",
                                modelElement,
                                requirement,
                                "satisfy"));
    }

    /** Native incoming relation: source requirement -> verification obligation. */
    public EObject verify(EObject requirement, EObject verificationConstraint) {
        requireRequirement(requirement);
        requireOwned(verificationConstraint);
        if (!(verificationConstraint instanceof Constraint)) {
            throw new IllegalArgumentException(
                    "Requirement verification must target a native Capella Constraint");
        }
        return write(
                "Trace requirement verification",
                () ->
                        relation(
                                requirement,
                                "ownedRelations",
                                CAPELLA_REQUIREMENTS_NS,
                                "CapellaIncomingRelation",
                                requirement,
                                verificationConstraint,
                                "verify"));
    }

    public EObject derive(EObject derivedRequirement, EObject sourceRequirement) {
        requireRequirement(derivedRequirement);
        requireRequirement(sourceRequirement);
        if (derivedRequirement == sourceRequirement)
            throw new IllegalArgumentException("A requirement cannot derive itself");
        return write(
                "Trace requirement derivation",
                () ->
                        relation(
                                derivedRequirement,
                                "ownedRelations",
                                REQUIREMENTS_NS,
                                "InternalRelation",
                                derivedRequirement,
                                sourceRequirement,
                                "derive"));
    }

    public List<EObject> listModules() {
        return all().stream()
                .filter(item -> is(item, CAPELLA_REQUIREMENTS_NS, "CapellaModule"))
                .toList();
    }

    public List<EObject> requirements(EObject parent) {
        requireContainer(parent);
        List<EObject> result = new ArrayList<>();
        parent.eAllContents()
                .forEachRemaining(
                        item -> {
                            if (is(item, REQUIREMENTS_NS, "Requirement")
                                    && !is(item, REQUIREMENTS_NS, "Folder")) result.add(item);
                        });
        return result;
    }

    public EObject find(String identifier) {
        requireIdentifier(identifier);
        EObject found = null;
        for (EObject item : all()) {
            if (identifier.equals(get(item, "ReqIFIdentifier"))) {
                if (found != null)
                    throw new IllegalStateException(
                            "Ambiguous requirement identifier: " + identifier);
                found = item;
            }
        }
        return found;
    }

    public boolean delete(EObject requirement) {
        requireRequirement(requirement);
        return ModelOps.deleteByUuid(session, (String) get(requirement, "id"));
    }

    private EObject relation(
            EObject owner,
            String containment,
            String namespace,
            String className,
            EObject source,
            EObject target,
            String relationName) {
        for (EObject existing : list(owner, containment)) {
            if (is(existing, namespace, className)
                    && get(existing, "source") == source
                    && get(existing, "target") == target
                    && get(existing, "relationType") instanceof EObject type
                    && relationName.equals(get(type, "ReqIFLongName"))) return existing;
        }
        EObject relation = nativeObject(namespace, className);
        set(relation, "ReqIFLongName", relationName);
        set(relation, "source", source);
        set(relation, "target", target);
        EObject types = typesFolder(architecture(source));
        String identifier = "capella-ai:relation:" + relationName;
        EObject type = localFind(types, "ownedTypes", identifier);
        if (type == null)
            type =
                    createUnder(
                            types,
                            "ownedTypes",
                            REQUIREMENTS_NS,
                            "RelationType",
                            identifier,
                            relationName);
        set(relation, "relationType", type);
        list(owner, containment).add(relation);
        return relation;
    }

    private EObject requirementType(EObject architecture) {
        EObject types = typesFolder(architecture);
        EObject type = localFind(types, "ownedTypes", TYPE_ID);
        return type == null
                ? createUnder(
                        types,
                        "ownedTypes",
                        REQUIREMENTS_NS,
                        "RequirementType",
                        TYPE_ID,
                        "Source and derived requirements")
                : type;
    }

    private EObject typesFolder(EObject architecture) {
        String identifier = "capella-ai:types:" + EcoreUtil.getURI(architecture).fragment();
        EObject types = localFind(architecture, "ownedExtensions", identifier);
        return types == null
                ? createUnder(
                        architecture,
                        "ownedExtensions",
                        CAPELLA_REQUIREMENTS_NS,
                        "CapellaTypesFolder",
                        identifier,
                        "Requirements types")
                : types;
    }

    private EObject createUnder(
            EObject owner,
            String containment,
            String namespace,
            String className,
            String identifier,
            String title) {
        requireIdentifier(identifier);
        EObject existing = localFind(owner, containment, identifier);
        if (existing != null) {
            if (!existing.eClass().getName().equals(className)
                    || !existing.eClass().getEPackage().getNsURI().equals(namespace)) {
                throw new IllegalArgumentException(
                        "Identifier already belongs to a different native requirement type: "
                                + identifier);
            }
            return existing;
        }
        // Public requirement identifiers are unique in the session. Type definitions are per
        // architecture.
        Map<String, EObject> importing = importingIdentifiers.get();
        if (!identifier.startsWith("capella-ai:")
                && (importing == null ? find(identifier) : importing.get(identifier)) != null) {
            throw new IllegalArgumentException(
                    "Requirement identifier is already used in another container: " + identifier);
        }
        EObject created = nativeObject(namespace, className);
        set(created, "ReqIFIdentifier", identifier);
        set(created, "ReqIFLongName", title);
        list(owner, containment).add(created);
        if (importing != null && !identifier.startsWith("capella-ai:"))
            importing.put(identifier, created);
        return created;
    }

    private EObject localFind(EObject owner, String containment, String identifier) {
        return list(owner, containment).stream()
                .filter(item -> identifier.equals(get(item, "ReqIFIdentifier")))
                .findFirst()
                .orElse(null);
    }

    private static String attributeClass(Object value) {
        if (value instanceof String) return "StringValueAttribute";
        if (value instanceof Boolean) return "BooleanValueAttribute";
        if (value instanceof Byte || value instanceof Short || value instanceof Integer)
            return "IntegerValueAttribute";
        if (value instanceof Long number) {
            if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "ReqIF Integer attributes must fit a signed 32-bit integer; use a String"
                            + " for larger exact values");
            }
            return "IntegerValueAttribute";
        }
        if (value instanceof Number) return "RealValueAttribute";
        if (value instanceof Date) return "DateValueAttribute";
        throw new IllegalArgumentException(
                "Requirement attributes support String, Boolean, Number or Date values");
    }

    private void requireContainer(EObject parent) {
        requireOwned(parent);
        if (!is(parent, REQUIREMENTS_NS, "Module") && !is(parent, REQUIREMENTS_NS, "Folder")) {
            throw new IllegalArgumentException(
                    "A requirement or folder must belong to a native Requirements module or"
                        + " folder");
        }
    }

    private void requireRequirement(EObject requirement) {
        requireOwned(requirement);
        if (!is(requirement, REQUIREMENTS_NS, "Requirement")) {
            throw new IllegalArgumentException("Expected a native ReqIF Requirement");
        }
    }

    private void requireModelElement(EObject modelElement) {
        requireOwned(modelElement);
        if (!(modelElement instanceof CapellaElement)
                || modelElement instanceof BlockArchitecture) {
            throw new IllegalArgumentException(
                    "Requirement satisfaction must originate from a native Capella design element");
        }
    }

    private void requireOwned(EObject element) {
        requireAvailable();
        if (element == null
                || element.eResource() == null
                || element.eResource().getResourceSet() != resources()) {
            throw new IllegalArgumentException(
                    "Requirement operations require elements in the active model session");
        }
    }

    private ResourceSet resources() {
        if (session == null || session.getTransactionalEditingDomain() == null) {
            throw new IllegalStateException(
                    "An open Capella session is required for requirements operations");
        }
        return session.getTransactionalEditingDomain().getResourceSet();
    }

    private List<EObject> all() {
        List<EObject> result = new ArrayList<>();
        for (var resource : List.copyOf(resources().getResources()))
            resource.getAllContents().forEachRemaining(result::add);
        return result;
    }

    private static EObject architecture(EObject element) {
        for (EObject owner = element; owner != null; owner = owner.eContainer()) {
            if (owner instanceof BlockArchitecture) return owner;
        }
        throw new IllegalArgumentException(
                "Requirement operation needs an element inside an Arcadia architecture");
    }

    private <T> T write(String label, Supplier<T> action) {
        return SessionSafeguards.runWrite(
                session,
                label,
                () -> {
                    T result = action.get();
                    ModelSummary.invalidate(session);
                    save();
                    return result;
                });
    }

    private void save() {
        try {
            SessionSafeguards.requestSave(session);
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to save requirements changes", failure);
        }
    }

    private Object viewpointManager() {
        Class<?> manager =
                nativeClass(
                        "org.polarsys.kitalpha.ad.services",
                        "org.polarsys.kitalpha.ad.services.manager.ViewpointManager");
        return invoke(manager, "getInstance", new Class<?>[] {ResourceSet.class}, resources());
    }

    private static Object invoke(
            Object target, String method, Class<?>[] signature, Object... args) {
        try {
            Class<?> type = target instanceof Class<?> clazz ? clazz : target.getClass();
            return type.getMethod(method, signature)
                    .invoke(target instanceof Class<?> ? null : target, args);
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException(
                    "Requirements viewpoint operation failed: " + failure.getCause().getMessage(),
                    failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Requirements viewpoint API is incompatible", failure);
        }
    }

    private static void requireIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank())
            throw new IllegalArgumentException("A stable requirement identifier is required");
    }

    private static void requireAvailable() {
        nativePackage(REQUIREMENTS_NS);
        nativePackage(CAPELLA_REQUIREMENTS_NS);
    }

    private static EPackage nativePackage(String namespace) {
        EPackage registered = EPackage.Registry.INSTANCE.getEPackage(namespace);
        if (registered != null) return registered;
        String bundle =
                namespace.equals(REQUIREMENTS_NS)
                        ? "org.polarsys.kitalpha.vp.requirements.model"
                        : "org.polarsys.capella.vp.requirements.model";
        String className =
                namespace.equals(REQUIREMENTS_NS)
                        ? "org.polarsys.kitalpha.vp.requirements.Requirements.RequirementsPackage"
                        : "org.polarsys.capella.vp.requirements.CapellaRequirements.CapellaRequirementsPackage";
        try {
            return (EPackage) nativeClass(bundle, className).getField("eINSTANCE").get(null);
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IllegalStateException(
                    "Install Capella Requirements Viewpoint 0.14.x for Capella 7 before using"
                        + " requirements",
                    failure);
        }
    }

    private static Class<?> nativeClass(String bundleName, String className) {
        try {
            Bundle bundle = Platform.isRunning() ? Platform.getBundle(bundleName) : null;
            return bundle == null ? Class.forName(className) : bundle.loadClass(className);
        } catch (ClassNotFoundException | LinkageError failure) {
            throw new IllegalStateException(
                    "Missing native Requirements viewpoint bundle: " + bundleName, failure);
        }
    }

    private static EObject nativeObject(String namespace, String className) {
        EPackage model = nativePackage(namespace);
        EObject object =
                model.getEFactoryInstance().create((EClass) model.getEClassifier(className));
        set(object, "id", UUID.randomUUID().toString());
        return object;
    }

    private static boolean is(EObject object, String namespace, String className) {
        return object != null
                && ((EClass) nativePackage(namespace).getEClassifier(className)).isInstance(object);
    }

    private static Object get(EObject object, String featureName) {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        return feature == null ? null : object.eGet(feature);
    }

    private static void set(EObject object, String featureName, Object value) {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(featureName);
        if (feature == null || !feature.isChangeable())
            throw new IllegalArgumentException(
                    "Unsupported native requirement feature: " + featureName);
        object.eSet(feature, value);
    }

    @SuppressWarnings("unchecked")
    private static List<EObject> list(EObject owner, String featureName) {
        EStructuralFeature feature = owner.eClass().getEStructuralFeature(featureName);
        if (feature == null || !feature.isMany())
            throw new IllegalArgumentException(
                    "Unsupported native requirement containment: " + featureName);
        return (List<EObject>) owner.eGet(feature);
    }
}
