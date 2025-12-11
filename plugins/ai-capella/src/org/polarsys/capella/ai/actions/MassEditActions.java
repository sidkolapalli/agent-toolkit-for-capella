package org.polarsys.capella.ai.actions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.model.ActionResult;
import org.polarsys.capella.ai.model.ActionResult.ErrorCode;
import org.polarsys.capella.ai.model.FindResult;
import org.polarsys.capella.ai.model.Finders;
import org.polarsys.capella.ai.model.ModelSummary;
import org.polarsys.capella.ai.model.index.ModelIndex;
import org.polarsys.capella.ai.session.SessionSafeguards;

/** Batch operations for mass edits guided by AI plans. */
public final class MassEditActions {
    private MassEditActions() {
    }

    public static ActionResult batchRename(Session session, String elementClass, List<RenameItem> items) {
        if (session == null) {
            return ActionResult.fail(ErrorCode.SESSION_ERROR, "No active Capella session");
        }
        if (elementClass == null || elementClass.isBlank()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Element class is required");
        }
        if (items == null || items.isEmpty()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Rename items list is required");
        }
        return SessionSafeguards.runWriteWithResult(session, "Batch rename", () -> {
            int renamed = 0;
            List<String> notFound = new ArrayList<>();
            for (RenameItem item : items) {
                if (item == null || item.from == null || item.to == null || item.from.isBlank()) {
                    continue;
                }
                FindResult result = Finders.findByNameWithDiagnostic(session, elementClass, item.from);
                if (result.failed()) {
                    notFound.add(item.from);
                    continue;
                }
                EObject target = result.element();
                EStructuralFeature nameFeature = target.eClass().getEStructuralFeature("name");
                if (nameFeature != null && nameFeature.isChangeable()) {
                    target.eSet(nameFeature, item.to);
                    renamed++;
                }
            }
            if (renamed > 0) {
                invalidate(session);
            }
            if (renamed == 0 && !notFound.isEmpty()) {
                return ActionResult.failWithAlternatives(ErrorCode.ELEMENT_NOT_FOUND,
                    "No elements renamed - " + notFound.size() + " element(s) not found",
                    notFound);
            }
            String msg = "Renamed " + renamed + "/" + items.size() + " " + elementClass + " elements";
            if (!notFound.isEmpty()) {
                return ActionResult.create(true, ErrorCode.SUCCESS, msg,
                    "Elements not found: " + String.join(", ", notFound),
                    notFound, Map.of("renamed", renamed, "notFound", notFound.size()));
            }
            return ActionResult.ok(msg);
        });
    }

    public static ActionResult batchReparent(Session session, String elementClass, String newParentName, List<String> elementNames) {
        if (session == null) {
            return ActionResult.fail(ErrorCode.SESSION_ERROR, "No active Capella session");
        }
        if (elementClass == null || elementClass.isBlank()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Element class is required");
        }
        if (newParentName == null || newParentName.isBlank()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "New parent name is required");
        }
        if (elementNames == null || elementNames.isEmpty()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Element names list is required");
        }
        return SessionSafeguards.runWriteWithResult(session, "Batch reparent", () -> {
            FindResult parentResult = Finders.findByNameWithDiagnostic(session, elementClass, newParentName);
            if (parentResult.failed()) {
                return ActionResult.fail(ErrorCode.PARENT_NOT_FOUND,
                    "Parent " + elementClass + " '" + newParentName + "' not found",
                    parentResult.hasAlternatives() ? "Did you mean: " + String.join(", ", parentResult.alternatives()) : null);
            }
            EObject newParent = parentResult.element();
            EReference containment = findPrimaryContainment(newParent);
            if (containment == null) {
                return ActionResult.fail(ErrorCode.INVALID_CONTAINER,
                    "Parent '" + newParentName + "' has no containment reference for children",
                    "Choose a different parent element");
            }

            int moved = 0;
            List<String> notFound = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            for (String name : elementNames) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                FindResult result = Finders.findByNameWithDiagnostic(session, elementClass, name);
                if (result.failed()) {
                    notFound.add(name);
                    continue;
                }
                EObject element = result.element();
                if (element == newParent) {
                    skipped.add(name + " (is parent)");
                    continue;
                }
                EObject container = element.eContainer();
                if (container == newParent) {
                    skipped.add(name + " (already child)");
                    continue;
                }
                if (!containment.getEReferenceType().isInstance(element)) {
                    skipped.add(name + " (type mismatch)");
                    continue;
                }
                removeFromContainer(element);
                addChild(newParent, containment, element);
                moved++;
            }
            if (moved > 0) {
                invalidate(session);
            }
            if (moved == 0) {
                String detail = "";
                if (!notFound.isEmpty()) {
                    detail += "Not found: " + String.join(", ", notFound) + ". ";
                }
                if (!skipped.isEmpty()) {
                    detail += "Skipped: " + String.join(", ", skipped);
                }
                return ActionResult.fail(ErrorCode.CONSTRAINT_VIOLATION,
                    "No elements reparented",
                    detail.isBlank() ? null : detail.trim());
            }
            String msg = "Reparented " + moved + "/" + elementNames.size() + " element(s) to '" + newParentName + "'";
            if (!notFound.isEmpty() || !skipped.isEmpty()) {
                StringBuilder detail = new StringBuilder();
                if (!notFound.isEmpty()) {
                    detail.append("Not found: ").append(String.join(", ", notFound)).append(". ");
                }
                if (!skipped.isEmpty()) {
                    detail.append("Skipped: ").append(String.join(", ", skipped));
                }
                return ActionResult.create(true, ErrorCode.SUCCESS, msg, detail.toString().trim(),
                    notFound, Map.of("moved", moved, "notFound", notFound.size(), "skipped", skipped.size()));
            }
            return ActionResult.ok(msg);
        });
    }

    public static ActionResult batchAllocateCapability(Session session, String capabilityName, List<String> componentNames) {
        if (session == null) {
            return ActionResult.fail(ErrorCode.SESSION_ERROR, "No active Capella session");
        }
        if (capabilityName == null || capabilityName.isBlank()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Capability name is required");
        }
        if (componentNames == null || componentNames.isEmpty()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Component names list is required");
        }
        return SessionSafeguards.runWriteWithResult(session, "Batch capability allocation", () -> {
            FindResult capabilityResult = Finders.findByNameWithDiagnostic(session, "Capability", capabilityName);
            if (capabilityResult.failed()) {
                return ActionResult.failWithAlternatives(ErrorCode.ELEMENT_NOT_FOUND,
                    "Capability '" + capabilityName + "' not found",
                    capabilityResult.hasAlternatives() ? "Did you mean: " + String.join(", ", capabilityResult.alternatives()) : null,
                    capabilityResult.alternatives());
            }
            EObject capability = capabilityResult.element();

            int allocated = 0;
            List<String> notFound = new ArrayList<>();
            for (String componentName : componentNames) {
                if (componentName == null || componentName.isBlank()) {
                    continue;
                }
                FindResult componentResult = Finders.findByNameWithDiagnostic(session, "SystemComponent", componentName);
                if (componentResult.failed()) {
                    notFound.add(componentName);
                    continue;
                }
                EObject component = componentResult.element();
                if (addReference(component, capability, "allocation", "capability", "realization")) {
                    allocated++;
                }
            }
            if (allocated > 0) {
                invalidate(session);
            }
            if (allocated == 0 && !notFound.isEmpty()) {
                return ActionResult.failWithAlternatives(ErrorCode.ELEMENT_NOT_FOUND,
                    "No components allocated - " + notFound.size() + " component(s) not found",
                    notFound);
            }
            String msg = "Allocated capability '" + capabilityName + "' to " + allocated + "/" + componentNames.size() + " component(s)";
            if (!notFound.isEmpty()) {
                return ActionResult.create(true, ErrorCode.SUCCESS, msg,
                    "Components not found: " + String.join(", ", notFound),
                    notFound, Map.of("allocated", allocated, "notFound", notFound.size()));
            }
            return ActionResult.ok(msg);
        });
    }

    public static ActionResult batchDelete(Session session, String elementClass, List<String> names) {
        if (session == null) {
            return ActionResult.fail(ErrorCode.SESSION_ERROR, "No active Capella session");
        }
        if (elementClass == null || elementClass.isBlank()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Element class is required");
        }
        if (names == null || names.isEmpty()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Element names list is required");
        }
        return SessionSafeguards.runWriteWithResult(session, "Batch delete", () -> {
            int deleted = 0;
            List<String> notFound = new ArrayList<>();
            for (String name : names) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                FindResult result = Finders.findByNameWithDiagnostic(session, elementClass, name);
                if (result.failed()) {
                    notFound.add(name);
                    continue;
                }
                EObject element = result.element();
                removeFromContainer(element);
                deleted++;
            }
            if (deleted > 0) {
                invalidate(session);
            }
            if (deleted == 0 && !notFound.isEmpty()) {
                return ActionResult.failWithAlternatives(ErrorCode.ELEMENT_NOT_FOUND,
                    "No elements deleted - " + notFound.size() + " element(s) not found",
                    notFound);
            }
            String msg = "Deleted " + deleted + "/" + names.size() + " " + elementClass + " element(s)";
            if (!notFound.isEmpty()) {
                return ActionResult.create(true, ErrorCode.SUCCESS, msg,
                    "Elements not found: " + String.join(", ", notFound),
                    notFound, Map.of("deleted", deleted, "notFound", notFound.size()));
            }
            return ActionResult.ok(msg);
        });
    }

    public static ActionResult batchMap(Session session, String sourceClass, String targetClass, List<MappingItem> entries) {
        if (session == null) {
            return ActionResult.fail(ErrorCode.SESSION_ERROR, "No active Capella session");
        }
        if (sourceClass == null || sourceClass.isBlank()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Source class is required");
        }
        if (targetClass == null || targetClass.isBlank()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Target class is required");
        }
        if (entries == null || entries.isEmpty()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Mapping entries list is required");
        }
        return SessionSafeguards.runWriteWithResult(session, "Batch map", () -> {
            int mapped = 0;
            List<String> notFoundSources = new ArrayList<>();
            List<String> notFoundTargets = new ArrayList<>();
            for (MappingItem entry : entries) {
                if (entry == null || entry.source == null || entry.target == null
                        || entry.source.isBlank() || entry.target.isBlank()) {
                    continue;
                }
                FindResult srcResult = Finders.findByNameWithDiagnostic(session, sourceClass, entry.source);
                if (srcResult.failed()) {
                    notFoundSources.add(entry.source);
                    continue;
                }
                FindResult tgtResult = Finders.findByNameWithDiagnostic(session, targetClass, entry.target);
                if (tgtResult.failed()) {
                    notFoundTargets.add(entry.target);
                    continue;
                }
                EObject src = srcResult.element();
                EObject tgt = tgtResult.element();
                if (addReference(src, tgt)) {
                    mapped++;
                }
            }
            if (mapped > 0) {
                invalidate(session);
            }
            List<String> allNotFound = new ArrayList<>();
            allNotFound.addAll(notFoundSources);
            allNotFound.addAll(notFoundTargets);
            if (mapped == 0 && !allNotFound.isEmpty()) {
                String detail = "";
                if (!notFoundSources.isEmpty()) {
                    detail += "Sources not found: " + String.join(", ", notFoundSources) + ". ";
                }
                if (!notFoundTargets.isEmpty()) {
                    detail += "Targets not found: " + String.join(", ", notFoundTargets);
                }
                return ActionResult.fail(ErrorCode.ELEMENT_NOT_FOUND,
                    "No mappings created - elements not found",
                    detail.trim());
            }
            String msg = "Mapped " + mapped + "/" + entries.size() + " " + sourceClass + " → " + targetClass + " pair(s)";
            if (!allNotFound.isEmpty()) {
                StringBuilder detail = new StringBuilder();
                if (!notFoundSources.isEmpty()) {
                    detail.append("Sources not found: ").append(String.join(", ", notFoundSources)).append(". ");
                }
                if (!notFoundTargets.isEmpty()) {
                    detail.append("Targets not found: ").append(String.join(", ", notFoundTargets));
                }
                return ActionResult.create(true, ErrorCode.SUCCESS, msg, detail.toString().trim(),
                    allNotFound, Map.of("mapped", mapped, "notFoundSources", notFoundSources.size(), "notFoundTargets", notFoundTargets.size()));
            }
            return ActionResult.ok(msg);
        });
    }

    // ========== Legacy boolean methods for backward compatibility ==========

    /** @deprecated Use {@link #batchRename(Session, String, List)} instead */
    @Deprecated
    public static boolean batchRenameLegacy(Session session, String elementClass, List<RenameItem> items) {
        return batchRename(session, elementClass, items).success();
    }

    /** @deprecated Use {@link #batchReparent(Session, String, String, List)} instead */
    @Deprecated
    public static boolean batchReparentLegacy(Session session, String elementClass, String newParentName, List<String> elementNames) {
        return batchReparent(session, elementClass, newParentName, elementNames).success();
    }

    /** @deprecated Use {@link #batchAllocateCapability(Session, String, List)} instead */
    @Deprecated
    public static boolean batchAllocateCapabilityLegacy(Session session, String capabilityName, List<String> componentNames) {
        return batchAllocateCapability(session, capabilityName, componentNames).success();
    }

    /** @deprecated Use {@link #batchDelete(Session, String, List)} instead */
    @Deprecated
    public static boolean batchDeleteLegacy(Session session, String elementClass, List<String> names) {
        return batchDelete(session, elementClass, names).success();
    }

    /** @deprecated Use {@link #batchMap(Session, String, String, List)} instead */
    @Deprecated
    public static boolean batchMapLegacy(Session session, String sourceClass, String targetClass, List<MappingItem> entries) {
        return batchMap(session, sourceClass, targetClass, entries).success();
    }

    // ========== Data Classes ==========

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

    // ========== Helper Methods ==========

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
