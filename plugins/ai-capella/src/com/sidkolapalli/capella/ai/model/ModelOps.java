package com.sidkolapalli.capella.ai.model;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.util.InternalEList;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.gmf.runtime.notation.View;
import org.eclipse.sirius.business.api.dialect.command.DeleteRepresentationCommand;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.diagram.DEdge;
import org.eclipse.sirius.viewpoint.DRepresentationDescriptor;
import org.eclipse.sirius.viewpoint.DRepresentationElement;
import com.sidkolapalli.capella.ai.session.SessionBridge;
import com.sidkolapalli.capella.ai.session.SessionSafeguards;
import org.polarsys.capella.core.model.helpers.delete.DeleteHelper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Generic EMF operations (rename/delete) using the active Capella session. */
public final class ModelOps {

    private ModelOps() {}

    /** Rename an element by uuid/id if it has a 'name' feature. */
    public static boolean renameByUuid(String uuid, String newName) {
        Session session = SessionBridge.getActiveSession();
        if (session == null) return false;
        return renameByUuid(session, uuid, newName);
    }

    public static boolean renameByUuid(Session session, String uuid, String newName) {
        if (session == null) return false;
        return SessionSafeguards.runWrite(
                session,
                "Rename element",
                () -> {
                    TransactionalEditingDomain domain = session.getTransactionalEditingDomain();
                    if (domain == null) return false;
                    EObject target = findById(domain.getResourceSet(), uuid);
                    if (target == null) return false;
                    EStructuralFeature nameFeat = target.eClass().getEStructuralFeature("name");
                    if (nameFeat == null || !nameFeat.isChangeable()) return false;
                    target.eSet(nameFeat, newName);
                    ModelSummary.invalidate(session);
                    try {
                        SessionSafeguards.requestSave(session);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to save after rename", e);
                    }
                    return true;
                });
    }

    /** Delete an element and its dependent semantic links by uuid/id. */
    public static boolean deleteByUuid(String uuid) {
        Session session = SessionBridge.getActiveSession();
        if (session == null) return false;
        return deleteByUuid(session, uuid);
    }

    public static boolean deleteByUuid(Session session, String uuid) {
        if (session == null) return false;
        return SessionSafeguards.runWrite(
                session,
                "Delete element",
                () -> {
                    TransactionalEditingDomain domain = session.getTransactionalEditingDomain();
                    if (domain == null) return false;
                    EObject target = findById(domain.getResourceSet(), uuid);
                    if (target == null) return false;
                    deleteSemanticStructure(session, target, domain.getResourceSet());
                    ModelSummary.invalidate(session);
                    try {
                        SessionSafeguards.requestSave(session);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to save after delete", e);
                    }
                    return true;
                });
    }

    private static void deleteSemanticStructure(
            Session session, EObject target, ResourceSet resources) {
        // Removing containment alone leaves exchanges pointing to detached ports,
        // and allocations/realizations pointing to detached functions or components.
        // Use Capella's standard semantic deletion rules before clearing references.
        DeleteHelper helper = new DeleteHelper();
        // Sirius may store representations in lazily loaded fragments. Include
        // their views before building the reference graph used for deletion.
        for (Resource resource : List.copyOf(resources.getResources())) {
            var contents = resource.getAllContents();
            while (contents.hasNext()) {
                if (contents.next() instanceof DRepresentationDescriptor descriptor) {
                    descriptor.getRepresentation();
                }
            }
        }
        Map<EObject, List<EStructuralFeature.Setting>> usages = storedReferences(resources);
        Set<EObject> deletion = new LinkedHashSet<>();
        var pending = new ArrayDeque<EObject>();
        pending.add(target);
        while (!pending.isEmpty()) {
            EObject element = pending.removeFirst();
            if (!deletion.add(element)) continue;
            pending.addAll(element.eContents());
            for (EStructuralFeature.Setting usage : usages.getOrDefault(element, List.of())) {
                EStructuralFeature feature = usage.getEStructuralFeature();
                if (!(feature instanceof EReference reference)
                        || reference.isContainment()
                        || !feature.isChangeable()) continue;
                EObject dependent = usage.getEObject();
                if (isRepresentationUsage(dependent, feature)
                        || isRequirementEndpoint(dependent, feature)
                        || helper.isDeleteSemanticStructure(dependent, element, feature)
                        || helper.isDeleteElement(dependent, element, feature)) {
                    pending.addLast(dependent);
                }
                var additional = helper.getAdditionalElements(dependent, element, feature);
                if (additional != null) pending.addAll(additional);
            }
        }
        Set<DRepresentationDescriptor> representations = new LinkedHashSet<>();
        for (EObject element : deletion) {
            for (EStructuralFeature.Setting usage : usages.getOrDefault(element, List.of())) {
                if (usage.getEObject() instanceof DRepresentationDescriptor descriptor
                        && "target".equals(usage.getEStructuralFeature().getName())) {
                    representations.add(descriptor);
                }
            }
        }
        if (!representations.isEmpty()) {
            DeleteRepresentationCommand command =
                    new DeleteRepresentationCommand(session, representations);
            if (!command.canExecute()) {
                throw new IllegalStateException(
                        "Sirius could not remove representations of the deleted element");
            }
            command.execute();
        }
        for (EObject element : deletion) {
            for (EStructuralFeature.Setting usage : usages.getOrDefault(element, List.of())) {
                if (!deletion.contains(usage.getEObject())) {
                    EcoreUtil.remove(usage, element);
                }
            }
        }
        // Deleting just the roots preserves their internal containment for undo.
        var roots =
                deletion.stream()
                        .filter(element -> !deletion.contains(element.eContainer()))
                        .toList();
        EcoreUtil.removeAll(roots);
    }

    private static boolean isRepresentationUsage(EObject owner, EStructuralFeature feature) {
        String name = feature.getName();
        // Delete obsolete views instead of saving an empty semantic target. Also
        // remove incident diagram/notation edges when their endpoint view goes.
        if (owner instanceof DRepresentationElement && "target".equals(name)) return true;
        if (owner instanceof DEdge && ("sourceNode".equals(name) || "targetNode".equals(name)))
            return true;
        return owner instanceof View
                && ("element".equals(name) || "source".equals(name) || "target".equals(name));
    }

    private static boolean isRequirementEndpoint(EObject owner, EStructuralFeature feature) {
        // The optional ReqIF viewpoint's relations are not core Capella Trace subclasses.
        // Removing either endpoint must delete the relation rather than persist an empty link.
        if (!"source".equals(feature.getName()) && !"target".equals(feature.getName()))
            return false;
        String namespace = owner.eClass().getEPackage().getNsURI();
        if (!"http://www.polarsys.org/kitalpha/requirements".equals(namespace)
                && !"http://www.polarsys.org/capella/requirements".equals(namespace)) return false;
        return owner.eClass().getName().endsWith("Relation");
    }

    private static Map<EObject, List<EStructuralFeature.Setting>> storedReferences(
            ResourceSet resources) {
        Map<EObject, List<EStructuralFeature.Setting>> usages = new IdentityHashMap<>();
        for (Resource resource : List.copyOf(resources.getResources())) {
            var contents = resource.getAllContents();
            while (contents.hasNext()) {
                EObject owner = contents.next();
                for (EReference reference : owner.eClass().getEAllReferences()) {
                    // Derived/transient views are recomputed from stored features;
                    // reading them can invoke Capella business services unnecessarily.
                    if (reference.isContainment()
                            || reference.isDerived()
                            || reference.isTransient()
                            || !reference.isChangeable()
                            || !owner.eIsSet(reference)) continue;
                    EStructuralFeature.Setting setting =
                            ((InternalEObject) owner).eSetting(reference);
                    Object value = owner.eGet(reference, false);
                    if (reference.isMany()) {
                        List<?> values =
                                value instanceof InternalEList<?> list
                                        ? list.basicList()
                                        : (List<?>) value;
                        for (Object referenced : values) {
                            if (referenced instanceof EObject element) {
                                usages.computeIfAbsent(
                                                resolveLoaded(resources, element),
                                                key -> new ArrayList<>())
                                        .add(setting);
                            }
                        }
                    } else if (value instanceof EObject element) {
                        usages.computeIfAbsent(
                                        resolveLoaded(resources, element), key -> new ArrayList<>())
                                .add(setting);
                    }
                }
            }
        }
        return usages;
    }

    private static EObject resolveLoaded(ResourceSet resources, EObject element) {
        if (element.eIsProxy()) {
            EObject resolved = resources.getEObject(((InternalEObject) element).eProxyURI(), false);
            if (resolved != null) return resolved;
        }
        return element;
    }

    /** Resolve an EObject by id/uuid/fragment. */
    public static EObject findById(org.eclipse.emf.ecore.resource.ResourceSet rs, String id) {
        if (rs == null || id == null) {
            return null;
        }
        try {
            // Native Capella resources resolve persistent IDs directly. Avoid a
            // full model walk for each lookup in large rule and architecture models.
            for (Resource res : rs.getResources()) {
                if (res == null) continue;
                try {
                    EObject indexed = res.getEObject(id);
                    if (indexed != null && matchesIdentifier(indexed, id)) return indexed;
                } catch (RuntimeException unsupportedFragment) {
                    // Other resources can use a different fragment format.
                }
            }
            for (Resource res : rs.getResources()) {
                if (res == null) continue;
                var it = res.getAllContents();
                while (it.hasNext()) {
                    EObject eo = it.next();
                    try {
                        var cls = eo.eClass();
                        var idFeat = cls.getEStructuralFeature("id");
                        if (idFeat != null) {
                            Object v = eo.eGet(idFeat);
                            if (id.equals(v)) return eo;
                        }
                        var uuidFeat = cls.getEStructuralFeature("uuid");
                        if (uuidFeat != null) {
                            Object v = eo.eGet(uuidFeat);
                            if (id.equals(v)) return eo;
                        }
                        // getEObject above checked resource fragments. This
                        // fallback covers legacy id/uuid attributes.
                    } catch (Exception ex) {
                        // Log per-element errors but keep searching
                        System.err.println(
                                "[ModelOps] Error checking element for id '"
                                        + id
                                        + "': "
                                        + ex.getMessage());
                    }
                }
            }
        } catch (Exception ex) {
            System.err.println(
                    "[ModelOps] Error searching for id '" + id + "': " + ex.getMessage());
        }
        return null;
    }

    private static boolean matchesIdentifier(EObject element, String id) {
        for (String featureName : List.of("id", "uuid")) {
            var feature = element.eClass().getEStructuralFeature(featureName);
            if (feature != null && id.equals(element.eGet(feature))) return true;
        }
        var uri = EcoreUtil.getURI(element);
        return uri != null && id.equals(uri.fragment());
    }
}
