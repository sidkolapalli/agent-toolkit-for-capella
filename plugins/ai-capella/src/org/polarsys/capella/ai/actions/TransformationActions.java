package org.polarsys.capella.ai.actions;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.model.ActionResult;
import org.polarsys.capella.ai.model.ActionResult.ErrorCode;
import org.polarsys.capella.ai.model.ArchitectureLocator;
import org.polarsys.capella.ai.model.ElementFactory;
import org.polarsys.capella.ai.model.FindResult;
import org.polarsys.capella.ai.model.Finders;
import org.polarsys.capella.ai.model.ModelSummary;
import org.polarsys.capella.ai.model.index.ModelIndex;
import org.polarsys.capella.ai.session.SessionSafeguards;

/**
 * Logical/Physical transformation utilities.
 */
public final class TransformationActions {
    private TransformationActions() {
    }

    public static ActionResult propagateLogicalComponent(Session session, String logicalName, String targetLayer) {
        return propagateLogicalComponent(session, logicalName, targetLayer, true);
    }

    public static ActionResult propagateLogicalComponent(Session session, String logicalName, String targetLayer, boolean includeChildren) {
        if (session == null) {
            return ActionResult.fail(ErrorCode.SESSION_ERROR, "No active Capella session");
        }
        if (logicalName == null || logicalName.isBlank()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Logical component name is required");
        }
        return SessionSafeguards.runWriteWithResult(session, "Propagate logical component", () -> {
            // Find logical component with diagnostics
            FindResult logicalResult = Finders.findByNameWithDiagnostic(session, "LogicalComponent", logicalName);
            if (logicalResult.failed()) {
                return logicalResult.toActionResult();
            }
            EObject logical = logicalResult.element();

            // Find physical layer with diagnostics
            FindResult physicalLayerResult = ArchitectureLocator.findLayerWithDiagnostic(session, resolveLayer(targetLayer, "PA"));
            if (physicalLayerResult.failed()) {
                return physicalLayerResult.toActionResult();
            }
            EObject physicalRoot = physicalLayerResult.element();

            String componentName = readName(logical, logicalName);
            Placement placement = resolvePlacement(physicalRoot, "PhysicalComponent");
            if (placement == null) {
                return ActionResult.fail(ErrorCode.CONSTRAINT_VIOLATION,
                    "Cannot find suitable container for PhysicalComponent in PA layer",
                    "Ensure the Physical Architecture has a container that accepts PhysicalComponent elements");
            }
            if (containsByName(placement, componentName)) {
                return ActionResult.fail(ErrorCode.DUPLICATE_AMBIGUOUS,
                    "PhysicalComponent '" + componentName + "' already exists in PA layer",
                    "Use syncPhysicalFromLogical to update the existing component instead");
            }
            EObject copy = createTargetCopy(logical, placement.containment.getEReferenceType(), includeChildren);
            if (copy == null) {
                return ActionResult.fail(ErrorCode.CONSTRAINT_VIOLATION,
                    "Failed to create PhysicalComponent copy of '" + logicalName + "'",
                    "Check EMF constraints for PhysicalComponent creation");
            }
            addChild(placement, copy);
            createTrace(logical, copy, "trace", "allocated", "component");
            invalidate(session);
            String childrenMsg = includeChildren ? " (with children)" : "";
            return ActionResult.ok("Propagated LogicalComponent '" + logicalName + "' to PA" + childrenMsg);
        });
    }

    public static ActionResult syncPhysicalFromLogical(Session session, String logicalName, String physicalName) {
        return syncPhysicalFromLogical(session, logicalName, physicalName, true);
    }

    public static ActionResult syncPhysicalFromLogical(Session session, String logicalName, String physicalName, boolean updateAttributes) {
        if (session == null) {
            return ActionResult.fail(ErrorCode.SESSION_ERROR, "No active Capella session");
        }
        if (logicalName == null || logicalName.isBlank()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Logical component name is required");
        }
        if (physicalName == null || physicalName.isBlank()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Physical component name is required");
        }
        return SessionSafeguards.runWriteWithResult(session, "Sync physical component", () -> {
            // Find logical component with diagnostics
            FindResult logicalResult = Finders.findByNameWithDiagnostic(session, "LogicalComponent", logicalName);
            if (logicalResult.failed()) {
                return ActionResult.failWithAlternatives(ErrorCode.ELEMENT_NOT_FOUND,
                    "LogicalComponent '" + logicalName + "' not found",
                    logicalResult.hasAlternatives() ? "Did you mean: " + String.join(", ", logicalResult.alternatives()) : null,
                    logicalResult.alternatives());
            }
            EObject logical = logicalResult.element();

            // Find physical component with diagnostics
            FindResult physicalResult = Finders.findByNameWithDiagnostic(session, "PhysicalComponent", physicalName);
            if (physicalResult.failed()) {
                return ActionResult.failWithAlternatives(ErrorCode.ELEMENT_NOT_FOUND,
                    "PhysicalComponent '" + physicalName + "' not found",
                    physicalResult.hasAlternatives() ? "Did you mean: " + String.join(", ", physicalResult.alternatives()) : null,
                    physicalResult.alternatives());
            }
            EObject physical = physicalResult.element();

            if (updateAttributes) {
                copyScalarAttributes(logical, physical);
            }
            createTrace(logical, physical, "trace", "allocated", "component");
            invalidate(session);
            String attrMsg = updateAttributes ? " (attributes synced)" : " (trace only)";
            return ActionResult.ok("Synced PhysicalComponent '" + physicalName + "' from LogicalComponent '" + logicalName + "'" + attrMsg);
        });
    }

    public static ActionResult traceComponentMapping(Session session, String logicalName, String physicalName) {
        if (session == null) {
            return ActionResult.fail(ErrorCode.SESSION_ERROR, "No active Capella session");
        }
        if (logicalName == null || logicalName.isBlank()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Logical component name is required");
        }
        if (physicalName == null || physicalName.isBlank()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Physical component name is required");
        }
        return SessionSafeguards.runWriteWithResult(session, "Trace logical→physical", () -> {
            // Find logical component with diagnostics
            FindResult logicalResult = Finders.findByNameWithDiagnostic(session, "LogicalComponent", logicalName);
            if (logicalResult.failed()) {
                return logicalResult.toActionResult();
            }
            EObject logical = logicalResult.element();

            // Find physical component with diagnostics
            FindResult physicalResult = Finders.findByNameWithDiagnostic(session, "PhysicalComponent", physicalName);
            if (physicalResult.failed()) {
                return physicalResult.toActionResult();
            }
            EObject physical = physicalResult.element();

            createTrace(logical, physical, "trace", "allocated", "component");
            invalidate(session);
            return ActionResult.ok("Created trace: LogicalComponent '" + logicalName + "' → PhysicalComponent '" + physicalName + "'");
        });
    }

    // ========== Legacy boolean methods for backward compatibility ==========

    /** @deprecated Use {@link #propagateLogicalComponent(Session, String, String)} instead */
    @Deprecated
    public static boolean propagateLogicalComponentLegacy(Session session, String logicalName, String targetLayer) {
        return propagateLogicalComponent(session, logicalName, targetLayer).success();
    }

    /** @deprecated Use {@link #propagateLogicalComponent(Session, String, String, boolean)} instead */
    @Deprecated
    public static boolean propagateLogicalComponentLegacy(Session session, String logicalName, String targetLayer, boolean includeChildren) {
        return propagateLogicalComponent(session, logicalName, targetLayer, includeChildren).success();
    }

    /** @deprecated Use {@link #syncPhysicalFromLogical(Session, String, String)} instead */
    @Deprecated
    public static boolean syncPhysicalFromLogicalLegacy(Session session, String logicalName, String physicalName) {
        return syncPhysicalFromLogical(session, logicalName, physicalName).success();
    }

    /** @deprecated Use {@link #syncPhysicalFromLogical(Session, String, String, boolean)} instead */
    @Deprecated
    public static boolean syncPhysicalFromLogicalLegacy(Session session, String logicalName, String physicalName, boolean updateAttributes) {
        return syncPhysicalFromLogical(session, logicalName, physicalName, updateAttributes).success();
    }

    /** @deprecated Use {@link #traceComponentMapping(Session, String, String)} instead */
    @Deprecated
    public static boolean traceComponentMappingLegacy(Session session, String logicalName, String physicalName) {
        return traceComponentMapping(session, logicalName, physicalName).success();
    }

    // ========== Helper Methods ==========

    private static EObject createTargetCopy(EObject logical, EClass targetClass, boolean includeChildren) {
        if (logical == null || targetClass == null) {
            return null;
        }
        EObject copy = targetClass.getEPackage().getEFactoryInstance().create(targetClass);
        copyScalarAttributes(logical, copy);
        cleanIdentifiers(copy);
        if (!includeChildren) {
            return copy;
        }
        // Recursively copy logical sub-components to physical components
        EReference nestedContainment = findContainmentFor(copy, targetClass);
        if (nestedContainment == null) {
            return copy;
        }
        for (EObject child : logical.eContents()) {
            if (!"LogicalComponent".equals(child.eClass().getName())) {
                continue;
            }
            EObject nested = createTargetCopy(child, targetClass, true);
            addChild(copy, nestedContainment, nested);
        }
        return copy;
    }

    private static Placement resolvePlacement(EObject root, String simpleEClassName) {
        EClass targetClass = ElementFactory.findEClass(simpleEClassName);
        if (targetClass == null) {
            return null;
        }
        EObject container = findBestContainer(root, targetClass);
        if (container == null) {
            container = root;
        }
        EReference containment = findContainmentFor(container, targetClass);
        if (containment == null) {
            return null;
        }
        return new Placement(container, containment);
    }

    private static EObject findBestContainer(EObject root, EClass targetClass) {
        Deque<EObject> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            EObject current = queue.removeFirst();
            EReference ref = findContainmentFor(current, targetClass);
            if (ref != null) {
                return current;
            }
            queue.addAll(current.eContents());
        }
        return null;
    }

    private static boolean containsByName(Placement placement, String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        Object current = placement.container.eGet(placement.containment);
        if (!(current instanceof List<?> list)) {
            return false;
        }
        for (Object child : list) {
            if (child instanceof EObject eo && name.equals(readName(eo, null))) {
                return true;
            }
        }
        return false;
    }

    private static void addChild(Placement placement, EObject child) {
        Object current = placement.container.eGet(placement.containment);
        if (current instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<EObject> typed = (List<EObject>) list;
            typed.add(child);
        }
    }

    private static void addChild(EObject container, EReference containment, EObject child) {
        Object value = container.eGet(containment);
        if (value instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<EObject> typed = (List<EObject>) list;
            typed.add(child);
        }
    }

    private static void copyScalarAttributes(EObject source, EObject target) {
        for (EStructuralFeature feature : source.eClass().getEAllStructuralFeatures()) {
            if (!(feature instanceof EAttribute attr)) {
                continue;
            }
            if (!attr.isChangeable() || attr.isDerived()) {
                continue;
            }
            String name = attr.getName().toLowerCase(Locale.ROOT);
            if (name.contains("uuid") || name.endsWith("id")) {
                continue;
            }
            EStructuralFeature targetFeature = target.eClass().getEStructuralFeature(attr.getName());
            if (targetFeature instanceof EAttribute targetAttr && targetAttr.isChangeable()) {
                Object value = source.eGet(attr);
                target.eSet(targetAttr, value);
            }
        }
    }

    private static void cleanIdentifiers(EObject eo) {
        for (EStructuralFeature feature : eo.eClass().getEAllStructuralFeatures()) {
            if (!feature.isChangeable()) {
                continue;
            }
            String name = feature.getName().toLowerCase(Locale.ROOT);
            if (name.contains("uuid") || name.endsWith("id")) {
                eo.eSet(feature, null);
            }
        }
        for (EObject child : eo.eContents()) {
            cleanIdentifiers(child);
        }
    }

    private static void createTrace(EObject logical, EObject physical, String... keywords) {
        addReference(logical, physical, keywords);
        addReference(physical, logical, keywords);
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
            Object current = source.eGet(ref);
            if (ref.isMany()) {
                @SuppressWarnings("unchecked")
                Collection<Object> list = (Collection<Object>) current;
                if (!list.contains(target)) {
                    list.add(target);
                }
                return true;
            }
            source.eSet(ref, target);
            return true;
        }
        return false;
    }

    private static EReference findContainmentFor(EObject container, EClass target) {
        for (EStructuralFeature feature : container.eClass().getEAllStructuralFeatures()) {
            if (feature instanceof EReference ref && ref.isContainment() && ref.isMany()) {
                EClass type = (EClass) ref.getEType();
                if (type == target || type.isSuperTypeOf(target)) {
                    return ref;
                }
            }
        }
        return null;
    }

    private static String resolveLayer(String requested, String fallback) {
        if (requested == null || requested.isBlank()) {
            return fallback;
        }
        return requested;
    }

    private static String readName(EObject element, String fallback) {
        if (element == null) {
            return fallback;
        }
        EStructuralFeature feature = element.eClass().getEStructuralFeature("name");
        if (feature != null) {
            Object value = element.eGet(feature);
            if (value != null) {
                return value.toString();
            }
        }
        return fallback;
    }

    private static void invalidate(Session session) {
        ModelSummary.invalidate(session);
        ModelIndex.invalidate(session);
        SessionSafeguards.requestSave(session);
    }

    private record Placement(EObject container, EReference containment) {
    }
}
