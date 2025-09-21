package org.polarsys.capella.ai.actions;

import java.util.Collection;
import java.util.Locale;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.model.ArchitectureLocator;
import org.polarsys.capella.ai.model.ElementFactory;
import org.polarsys.capella.ai.model.Finders;
import org.polarsys.capella.ai.model.ModelSummary;
import org.polarsys.capella.ai.model.index.ModelIndex;
import org.polarsys.capella.ai.session.SessionSafeguards;

/** Utility methods for Operational Analysis actions. */
public final class OperationalActions {
    private OperationalActions() {}

    public static boolean createMission(Session session, String layer, String name, String description) {
        if (session == null || name == null || name.isBlank()) {
            return false;
        }
        return SessionSafeguards.runWrite(session, "Create mission", () -> {
            EObject oaRoot = ArchitectureLocator.findTargetElement(session, resolveLayer(layer, "OA"));
            if (oaRoot == null) {
                return false;
            }
            EObject mission = ElementFactory.createUnder(session, oaRoot, "Mission", name);
            if (mission == null) {
                return false;
            }
            setIfPresent(mission, "description", description);
            invalidate(session);
            return true;
        });
    }

    public static boolean createOperationalActivity(Session session, String layer, String name, String parentName) {
        if (session == null || name == null || name.isBlank()) {
            return false;
        }
        return SessionSafeguards.runWrite(session, "Create operational activity", () -> {
            EObject container = null;
            if (parentName != null && !parentName.isBlank()) {
                container = Finders.findByName(session, "OperationalActivity", parentName);
            }
            if (container == null) {
                container = ArchitectureLocator.findTargetElement(session, resolveLayer(layer, "OA"));
            }
            if (container == null) {
                return false;
            }
            EObject activity = ElementFactory.createUnder(session, container, "OperationalActivity", name);
            if (activity == null) {
                return false;
            }
            invalidate(session);
            return true;
        });
    }

    public static boolean createOperationalExchange(Session session, String layer, String name, String sourceActor, String targetActor) {
        if (session == null) {
            return false;
        }
        return SessionSafeguards.runWrite(session, "Create operational exchange", () -> {
            EObject oaRoot = ArchitectureLocator.findTargetElement(session, resolveLayer(layer, "OA"));
            if (oaRoot == null) {
                return false;
            }
            EObject exchange = ElementFactory.createUnder(session, oaRoot, "OperationalExchange", defaultName(name, "OperationalExchange"));
            if (exchange == null) {
                return false;
            }
            EObject source = Finders.findByName(session, "OperationalActor", sourceActor);
            EObject target = Finders.findByName(session, "OperationalActor", targetActor);
            assignEndpoint(exchange, "source", source);
            assignEndpoint(exchange, "target", target);
            invalidate(session);
            return true;
        });
    }

    public static boolean linkActivityToCapability(Session session, String operationalActivity, String capabilityName) {
        if (session == null || operationalActivity == null || capabilityName == null) {
            return false;
        }
        return SessionSafeguards.runWrite(session, "Link operational activity to capability", () -> {
            EObject activity = Finders.findByName(session, "OperationalActivity", operationalActivity);
            EObject capability = Finders.findByName(session, "Capability", capabilityName);
            if (activity == null || capability == null) {
                return false;
            }
            boolean linked = addReference(activity, capability, "capability", "allocation", "realization");
            if (linked) {
                invalidate(session);
            }
            return linked;
        });
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

    private static void setIfPresent(EObject element, String featureName, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        EStructuralFeature feature = element.eClass().getEStructuralFeature(featureName);
        if (feature != null && feature.isChangeable()) {
            element.eSet(feature, value);
        }
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
