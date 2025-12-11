package org.polarsys.capella.ai.actions;

import java.util.Collection;
import java.util.Locale;

import org.eclipse.emf.ecore.EObject;
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

/** Utility methods for Operational Analysis actions. */
public final class OperationalActions {
    private OperationalActions() {}

    public static ActionResult createMission(Session session, String layer, String name, String description) {
        if (session == null) {
            return ActionResult.fail(ErrorCode.SESSION_ERROR, "No active Capella session");
        }
        if (name == null || name.isBlank()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Mission name is required");
        }
        return SessionSafeguards.runWriteWithResult(session, "Create mission", () -> {
            FindResult layerResult = ArchitectureLocator.findLayerWithDiagnostic(session, resolveLayer(layer, "OA"));
            if (layerResult.failed()) {
                return layerResult.toActionResult();
            }
            EObject mission = ElementFactory.createUnder(session, layerResult.element(), "Mission", name);
            if (mission == null) {
                return ActionResult.fail(ErrorCode.CONSTRAINT_VIOLATION,
                    "Failed to create Mission '" + name + "'",
                    "Check EMF constraints and ensure the OA layer can hold Mission elements");
            }
            setIfPresent(mission, "description", description);
            invalidate(session);
            return ActionResult.ok("Created Mission '" + name + "'");
        });
    }

    public static ActionResult createOperationalActivity(Session session, String layer, String name, String parentName) {
        if (session == null) {
            return ActionResult.fail(ErrorCode.SESSION_ERROR, "No active Capella session");
        }
        if (name == null || name.isBlank()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Operational activity name is required");
        }
        return SessionSafeguards.runWriteWithResult(session, "Create operational activity", () -> {
            EObject container = null;
            // Try to find named parent first
            if (parentName != null && !parentName.isBlank()) {
                FindResult parentResult = Finders.findByNameWithDiagnostic(session, "OperationalActivity", parentName);
                if (parentResult.failed()) {
                    return ActionResult.failWithAlternatives(
                        ErrorCode.PARENT_NOT_FOUND,
                        "Parent OperationalActivity '" + parentName + "' not found",
                        parentResult.alternatives());
                }
                container = parentResult.element();
            }
            // Fall back to OA root
            if (container == null) {
                FindResult layerResult = ArchitectureLocator.findLayerWithDiagnostic(session, resolveLayer(layer, "OA"));
                if (layerResult.failed()) {
                    return layerResult.toActionResult();
                }
                container = layerResult.element();
            }
            EObject activity = ElementFactory.createUnder(session, container, "OperationalActivity", name);
            if (activity == null) {
                return ActionResult.fail(ErrorCode.CONSTRAINT_VIOLATION,
                    "Failed to create OperationalActivity '" + name + "'",
                    "Check EMF constraints for OperationalActivity creation");
            }
            invalidate(session);
            return ActionResult.ok("Created OperationalActivity '" + name + "'");
        });
    }

    public static ActionResult createOperationalExchange(Session session, String layer, String name, String sourceActor, String targetActor) {
        if (session == null) {
            return ActionResult.fail(ErrorCode.SESSION_ERROR, "No active Capella session");
        }
        return SessionSafeguards.runWriteWithResult(session, "Create operational exchange", () -> {
            FindResult layerResult = ArchitectureLocator.findLayerWithDiagnostic(session, resolveLayer(layer, "OA"));
            if (layerResult.failed()) {
                return layerResult.toActionResult();
            }
            EObject exchange = ElementFactory.createUnder(session, layerResult.element(), "OperationalExchange", defaultName(name, "OperationalExchange"));
            if (exchange == null) {
                return ActionResult.fail(ErrorCode.CONSTRAINT_VIOLATION,
                    "Failed to create OperationalExchange",
                    "Check EMF constraints for OperationalExchange creation");
            }

            // Find source actor with diagnostics
            FindResult sourceResult = Finders.findByNameWithDiagnostic(session, "OperationalActor", sourceActor);
            if (sourceResult.failed()) {
                return ActionResult.failWithAlternatives(ErrorCode.REFERENCE_NOT_FOUND,
                    "Source actor '" + sourceActor + "' not found",
                    sourceResult.hasAlternatives() ? "Did you mean: " + String.join(", ", sourceResult.alternatives()) : null,
                    sourceResult.alternatives());
            }

            // Find target actor with diagnostics
            FindResult targetResult = Finders.findByNameWithDiagnostic(session, "OperationalActor", targetActor);
            if (targetResult.failed()) {
                return ActionResult.failWithAlternatives(ErrorCode.REFERENCE_NOT_FOUND,
                    "Target actor '" + targetActor + "' not found",
                    targetResult.hasAlternatives() ? "Did you mean: " + String.join(", ", targetResult.alternatives()) : null,
                    targetResult.alternatives());
            }

            assignEndpoint(exchange, "source", sourceResult.element());
            assignEndpoint(exchange, "target", targetResult.element());
            invalidate(session);
            return ActionResult.ok("Created OperationalExchange from '" + sourceActor + "' to '" + targetActor + "'");
        });
    }

    public static ActionResult linkActivityToCapability(Session session, String operationalActivity, String capabilityName) {
        if (session == null) {
            return ActionResult.fail(ErrorCode.SESSION_ERROR, "No active Capella session");
        }
        if (operationalActivity == null || operationalActivity.isBlank()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Operational activity name is required");
        }
        if (capabilityName == null || capabilityName.isBlank()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Capability name is required");
        }
        return SessionSafeguards.runWriteWithResult(session, "Link operational activity to capability", () -> {
            // Find activity with diagnostics
            FindResult activityResult = Finders.findByNameWithDiagnostic(session, "OperationalActivity", operationalActivity);
            if (activityResult.failed()) {
                return activityResult.toActionResult();
            }

            // Find capability with diagnostics
            FindResult capabilityResult = Finders.findByNameWithDiagnostic(session, "Capability", capabilityName);
            if (capabilityResult.failed()) {
                return ActionResult.failWithAlternatives(ErrorCode.REFERENCE_NOT_FOUND,
                    "Capability '" + capabilityName + "' not found",
                    capabilityResult.hasAlternatives() ? "Did you mean: " + String.join(", ", capabilityResult.alternatives()) : null,
                    capabilityResult.alternatives());
            }

            boolean linked = addReference(activityResult.element(), capabilityResult.element(), "capability", "allocation", "realization");
            if (!linked) {
                return ActionResult.fail(ErrorCode.CONSTRAINT_VIOLATION,
                    "Failed to link OperationalActivity to Capability",
                    "No suitable reference feature found for capability linking");
            }
            invalidate(session);
            return ActionResult.ok("Linked '" + operationalActivity + "' to '" + capabilityName + "'");
        });
    }

    // ========== Legacy boolean methods for backward compatibility ==========

    /** @deprecated Use {@link #createMission(Session, String, String, String)} instead */
    @Deprecated
    public static boolean createMissionLegacy(Session session, String layer, String name, String description) {
        return createMission(session, layer, name, description).success();
    }

    /** @deprecated Use {@link #createOperationalActivity(Session, String, String, String)} instead */
    @Deprecated
    public static boolean createOperationalActivityLegacy(Session session, String layer, String name, String parentName) {
        return createOperationalActivity(session, layer, name, parentName).success();
    }

    /** @deprecated Use {@link #createOperationalExchange(Session, String, String, String, String)} instead */
    @Deprecated
    public static boolean createOperationalExchangeLegacy(Session session, String layer, String name, String sourceActor, String targetActor) {
        return createOperationalExchange(session, layer, name, sourceActor, targetActor).success();
    }

    /** @deprecated Use {@link #linkActivityToCapability(Session, String, String)} instead */
    @Deprecated
    public static boolean linkActivityToCapabilityLegacy(Session session, String operationalActivity, String capabilityName) {
        return linkActivityToCapability(session, operationalActivity, capabilityName).success();
    }

    // ========== Helper Methods ==========

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
