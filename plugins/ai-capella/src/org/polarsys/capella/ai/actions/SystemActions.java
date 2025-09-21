package org.polarsys.capella.ai.actions;

import java.util.Collection;
import java.util.Locale;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.model.ArchitectureLocator;
import org.polarsys.capella.ai.model.ElementFactory;
import org.polarsys.capella.ai.model.Finders;
import org.polarsys.capella.ai.model.ModelSummary;
import org.polarsys.capella.ai.model.index.ModelIndex;
import org.polarsys.capella.ai.session.SessionSafeguards;

/** Utility methods for System Analysis actions. */
public final class SystemActions {
    private SystemActions() {}

    public static boolean createComponent(Session session, String layer, String name, String parentName) {
        if (session == null || name == null || name.isBlank()) {
            return false;
        }
        return SessionSafeguards.runWrite(session, "Create system component", () -> {
            EObject container = resolveContainer(session, layer, parentName, "SystemComponent");
            if (container == null) {
                return false;
            }
            EObject component = ElementFactory.createUnder(session, container, "SystemComponent", name);
            if (component == null) {
                return false;
            }
            invalidate(session);
            return true;
        });
    }

    public static boolean createFunction(Session session, String layer, String name, String parentName) {
        if (session == null || name == null || name.isBlank()) {
            return false;
        }
        return SessionSafeguards.runWrite(session, "Create system function", () -> {
            EObject container = resolveContainer(session, layer, parentName, "SystemFunction");
            if (container == null) {
                return false;
            }
            EObject function = ElementFactory.createUnder(session, container, "SystemFunction", name);
            if (function == null) {
                return false;
            }
            invalidate(session);
            return true;
        });
    }

    public static boolean createInterface(Session session, String layer, String name, String sourceComponent, String targetComponent) {
        if (session == null) {
            return false;
        }
        return SessionSafeguards.runWrite(session, "Create system interface", () -> {
            EObject saRoot = ArchitectureLocator.findTargetElement(session, resolveLayer(layer, "SA"));
            if (saRoot == null) {
                return false;
            }
            EObject exchange = ElementFactory.createUnder(session, saRoot, "ComponentExchange", defaultName(name, "ComponentExchange"));
            if (exchange == null) {
                return false;
            }
            EObject source = Finders.findByName(session, "SystemComponent", sourceComponent);
            EObject target = Finders.findByName(session, "SystemComponent", targetComponent);
            assignEndpoint(exchange, "source", source);
            assignEndpoint(exchange, "target", target);
            invalidate(session);
            return true;
        });
    }

    public static boolean allocateCapability(Session session, String componentName, String capabilityName) {
        if (session == null || componentName == null || capabilityName == null) {
            return false;
        }
        return SessionSafeguards.runWrite(session, "Allocate capability to system component", () -> {
            EObject component = Finders.findByName(session, "SystemComponent", componentName);
            EObject capability = Finders.findByName(session, "Capability", capabilityName);
            if (component == null || capability == null) {
                return false;
            }
            boolean linked = addReference(component, capability, "capability", "allocation", "realization");
            if (linked) {
                invalidate(session);
            }
            return linked;
        });
    }

    private static EObject resolveContainer(Session session, String layer, String parentName, String className) {
        if (parentName != null && !parentName.isBlank()) {
            EObject parent = Finders.findByName(session, className, parentName);
            if (parent != null) {
                return parent;
            }
        }
        return ArchitectureLocator.findTargetElement(session, resolveLayer(layer, "SA"));
    }

    private static void invalidate(Session session) {
        ModelSummary.invalidate(session);
        ModelIndex.invalidate(session);
        SessionSafeguards.requestSave(session);
    }

    private static String resolveLayer(String requested, String fallback) {
        if (requested == null || requested.isBlank()) {
            return fallback;
        }
        return requested;
    }

    private static String defaultName(String name, String fallback) {
        return name == null || name.isBlank() ? fallback : name;
    }

    private static boolean addReference(EObject source, EObject target, String... keywords) {
        for (EStructuralFeature feature : source.eClass().getEAllStructuralFeatures()) {
            if (!(feature instanceof org.eclipse.emf.ecore.EReference ref) || ref.isContainment()) {
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

    private static void assignEndpoint(EObject exchange, String role, EObject endpoint) {
        if (exchange == null || endpoint == null) {
            return;
        }
        for (EStructuralFeature feature : exchange.eClass().getEAllStructuralFeatures()) {
            if (!(feature instanceof org.eclipse.emf.ecore.EReference ref) || ref.isContainment()) {
                continue;
            }
            String name = feature.getName().toLowerCase(Locale.ROOT);
            boolean matches = (role.equals("source") && name.contains("source")) || (role.equals("target") && name.contains("target"));
            if (!matches) {
                continue;
            }
            if (ref.getEReferenceType().isInstance(endpoint)) {
                if (ref.isMany()) {
                    @SuppressWarnings("unchecked")
                    Collection<Object> list = (Collection<Object>) exchange.eGet(ref);
                    if (!list.contains(endpoint)) {
                        list.add(endpoint);
                    }
                } else {
                    exchange.eSet(ref, endpoint);
                }
                return;
            }
        }
    }
}
