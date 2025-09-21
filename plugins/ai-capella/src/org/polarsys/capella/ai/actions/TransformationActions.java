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
import org.polarsys.capella.ai.model.ArchitectureLocator;
import org.polarsys.capella.ai.model.ElementFactory;
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

    public static boolean propagateLogicalComponent(Session session, String logicalName, String targetLayer) {
        return propagateLogicalComponent(session, logicalName, targetLayer, true);
    }

    public static boolean propagateLogicalComponent(Session session, String logicalName, String targetLayer, boolean includeChildren) {
        if (session == null || logicalName == null || logicalName.isBlank()) {
            return false;
        }
        return SessionSafeguards.runWrite(session, "Propagate logical component", () -> {
            EObject logical = Finders.findByName(session, "LogicalComponent", logicalName);
            if (logical == null) {
                return false;
            }
            EObject physicalRoot = ArchitectureLocator.findTargetElement(session, resolveLayer(targetLayer, "PA"));
            if (physicalRoot == null) {
                return false;
            }
            String componentName = readName(logical, logicalName);
            Placement placement = resolvePlacement(physicalRoot, "PhysicalComponent");
            if (placement == null) {
                return false;
            }
            if (containsByName(placement, componentName)) {
                return false;
            }
            EObject copy = createTargetCopy(logical, placement.containment.getEReferenceType(), includeChildren);
            if (copy == null) {
                return false;
            }
            addChild(placement, copy);
            createTrace(logical, copy, "trace", "allocated", "component");
            invalidate(session);
            return true;
        });
    }

    public static boolean syncPhysicalFromLogical(Session session, String logicalName, String physicalName) {
        return syncPhysicalFromLogical(session, logicalName, physicalName, true);
    }

    public static boolean syncPhysicalFromLogical(Session session, String logicalName, String physicalName, boolean updateAttributes) {
        if (session == null || logicalName == null || physicalName == null) {
            return false;
        }
        return SessionSafeguards.runWrite(session, "Sync physical component", () -> {
            EObject logical = Finders.findByName(session, "LogicalComponent", logicalName);
            EObject physical = Finders.findByName(session, "PhysicalComponent", physicalName);
            if (logical == null || physical == null) {
                return false;
            }
            if (updateAttributes) {
                copyScalarAttributes(logical, physical);
            }
            createTrace(logical, physical, "trace", "allocated", "component");
            invalidate(session);
            return true;
        });
    }

    public static boolean traceComponentMapping(Session session, String logicalName, String physicalName) {
        if (session == null || logicalName == null || physicalName == null) {
            return false;
        }
        return SessionSafeguards.runWrite(session, "Trace logical→physical", () -> {
            EObject logical = Finders.findByName(session, "LogicalComponent", logicalName);
            EObject physical = Finders.findByName(session, "PhysicalComponent", physicalName);
            if (logical == null || physical == null) {
                return false;
            }
            createTrace(logical, physical, "trace", "allocated", "component");
            invalidate(session);
            return true;
        });
    }

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
