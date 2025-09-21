package org.polarsys.capella.ai.actions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.model.Finders;
import org.polarsys.capella.ai.model.ModelSummary;
import org.polarsys.capella.ai.model.index.ModelIndex;
import org.polarsys.capella.ai.session.SessionSafeguards;

/** Batch operations for mass edits guided by AI plans. */
public final class MassEditActions {
    private MassEditActions() {
    }

    public static boolean batchRename(Session session, String elementClass, List<RenameItem> items) {
        if (session == null || elementClass == null || items == null || items.isEmpty()) {
            return false;
        }
        return SessionSafeguards.runWrite(session, "Batch rename", () -> {
            boolean changed = false;
            for (RenameItem item : items) {
                if (item == null || item.from == null || item.to == null || item.from.isBlank()) {
                    continue;
                }
                EObject target = Finders.findByName(session, elementClass, item.from);
                if (target == null) {
                    continue;
                }
                EStructuralFeature nameFeature = target.eClass().getEStructuralFeature("name");
                if (nameFeature != null && nameFeature.isChangeable()) {
                    target.eSet(nameFeature, item.to);
                    changed = true;
                }
            }
            if (changed) {
                invalidate(session);
            }
            return changed;
        });
    }

    public static boolean batchReparent(Session session, String elementClass, String newParentName, List<String> elementNames) {
        if (session == null || elementClass == null || newParentName == null || newParentName.isBlank()
                || elementNames == null || elementNames.isEmpty()) {
            return false;
        }
        return SessionSafeguards.runWrite(session, "Batch reparent", () -> {
            EObject newParent = Finders.findByName(session, elementClass, newParentName);
            if (newParent == null) {
                return false;
            }
            EReference containment = findPrimaryContainment(newParent);
            if (containment == null) {
                return false;
            }
            boolean changed = false;
            for (String name : elementNames) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                EObject element = Finders.findByName(session, elementClass, name);
                if (element == null || element == newParent) {
                    continue;
                }
                EObject container = element.eContainer();
                if (container == newParent) {
                    continue;
                }
                if (!containment.getEReferenceType().isInstance(element)) {
                    continue;
                }
                removeFromContainer(element);
                addChild(newParent, containment, element);
                changed = true;
            }
            if (changed) {
                invalidate(session);
            }
            return changed;
        });
    }

    public static boolean batchAllocateCapability(Session session, String capabilityName, List<String> componentNames) {
        if (session == null || capabilityName == null || capabilityName.isBlank()
                || componentNames == null || componentNames.isEmpty()) {
            return false;
        }
        return SessionSafeguards.runWrite(session, "Batch capability allocation", () -> {
            EObject capability = Finders.findByName(session, "Capability", capabilityName);
            if (capability == null) {
                return false;
            }
            boolean changed = false;
            for (String componentName : componentNames) {
                if (componentName == null || componentName.isBlank()) {
                    continue;
                }
                EObject component = Finders.findByName(session, "SystemComponent", componentName);
                if (component == null) {
                    continue;
                }
                if (addReference(component, capability, "allocation", "capability", "realization")) {
                    changed = true;
                }
            }
            if (changed) {
                invalidate(session);
            }
            return changed;
        });
    }

    public static boolean batchDelete(Session session, String elementClass, List<String> names) {
        if (session == null || elementClass == null || names == null || names.isEmpty()) {
            return false;
        }
        return SessionSafeguards.runWrite(session, "Batch delete", () -> {
            boolean changed = false;
            for (String name : names) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                EObject element = Finders.findByName(session, elementClass, name);
                if (element == null) {
                    continue;
                }
                removeFromContainer(element);
                changed = true;
            }
            if (changed) {
                invalidate(session);
            }
            return changed;
        });
    }

    public static boolean batchMap(Session session, String sourceClass, String targetClass, List<MappingItem> entries) {
        if (session == null || sourceClass == null || targetClass == null || entries == null || entries.isEmpty()) {
            return false;
        }
        return SessionSafeguards.runWrite(session, "Batch map", () -> {
            boolean changed = false;
            for (MappingItem entry : entries) {
                if (entry == null || entry.source == null || entry.target == null
                        || entry.source.isBlank() || entry.target.isBlank()) {
                    continue;
                }
                EObject src = Finders.findByName(session, sourceClass, entry.source);
                EObject tgt = Finders.findByName(session, targetClass, entry.target);
                if (src == null || tgt == null) {
                    continue;
                }
                if (addReference(src, tgt)) {
                    changed = true;
                }
            }
            if (changed) {
                invalidate(session);
            }
            return changed;
        });
    }

    public static final class RenameItem {
        public final String from;
        public final String to;

        public RenameItem(String from, String to) {
            this.from = from;
            this.to = to;
        }
    }

    public static final class MappingItem {
        public final String source;
        public final String target;

        public MappingItem(String source, String target) {
            this.source = source;
            this.target = target;
        }
    }

    private static void removeFromContainer(EObject element) {
        EObject container = element.eContainer();
        if (container == null) {
            if (element.eResource() != null) {
                element.eResource().getContents().remove(element);
            }
            return;
        }
        EStructuralFeature feature = element.eContainingFeature();
        if (feature == null) {
            return;
        }
        if (feature.isMany()) {
            @SuppressWarnings("unchecked")
            Collection<EObject> list = (Collection<EObject>) container.eGet(feature);
            list.remove(element);
        } else {
            container.eSet(feature, null);
        }
    }

    private static void addChild(EObject container, EReference containment, EObject child) {
        Object current = container.eGet(containment);
        if (current instanceof Collection<?>) {
            @SuppressWarnings("unchecked")
            Collection<EObject> collection = (Collection<EObject>) current;
            collection.add(child);
        }
    }

    private static boolean addReference(EObject source, EObject target, String... keywords) {
        for (EStructuralFeature feature : source.eClass().getEAllStructuralFeatures()) {
            if (!(feature instanceof EReference ref) || ref.isContainment()) {
                continue;
            }
            String name = feature.getName().toLowerCase(Locale.ROOT);
            boolean matches = keywords.length == 0;
            for (String keyword : keywords) {
                if (name.contains(keyword.toLowerCase(Locale.ROOT))) {
                    matches = true;
                    break;
                }
            }
            if (!matches) {
                continue;
            }
            if (!ref.getEReferenceType().isInstance(target) && !ref.getEReferenceType().isSuperTypeOf(target.eClass())) {
                continue;
            }
            Object current = source.eGet(ref);
            if (ref.isMany()) {
                if (current instanceof Collection<?>) {
                    @SuppressWarnings("unchecked")
                    Collection<EObject> list = (Collection<EObject>) current;
                    if (!list.contains(target)) {
                        list.add(target);
                    }
                }
            } else {
                source.eSet(ref, target);
            }
            return true;
        }
        return false;
    }

    private static EReference findPrimaryContainment(EObject parent) {
        List<EReference> candidates = new ArrayList<>();
        for (EStructuralFeature feature : parent.eClass().getEAllStructuralFeatures()) {
            if (feature instanceof EReference ref && ref.isContainment() && ref.isMany()) {
                candidates.add(ref);
            }
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        return candidates.stream()
                .filter(ref -> ref.getName().toLowerCase(Locale.ROOT).contains("owned") || ref.getName().toLowerCase(Locale.ROOT).contains("contained"))
                .findFirst()
                .orElse(candidates.isEmpty() ? null : candidates.get(0));
    }

    private static void invalidate(Session session) {
        ModelSummary.invalidate(session);
        ModelIndex.invalidate(session);
        SessionSafeguards.requestSave(session);
    }
}
