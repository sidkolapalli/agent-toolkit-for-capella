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

/** Utility methods for System Analysis actions. */
public final class SystemActions {
    private SystemActions() {}

    public static ActionResult createComponent(Session session, String layer, String name, String parentName) {
        if (session == null) {
            return ActionResult.fail(ErrorCode.SESSION_ERROR, "No active Capella session");
        }
        if (name == null || name.isBlank()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Component name is required");
        }
        return SessionSafeguards.runWriteWithResult(session, "Create system component", () -> {
            // Resolve container with diagnostics
            ContainerResult containerResult = resolveContainerWithDiagnostic(session, layer, parentName, "SystemComponent");
            if (!containerResult.success) {
                return containerResult.error;
            }
            EObject component = ElementFactory.createUnder(session, containerResult.container, "SystemComponent", name);
            if (component == null) {
                return ActionResult.fail(ErrorCode.CONSTRAINT_VIOLATION,
                    "Failed to create SystemComponent '" + name + "'",
                    "Check EMF constraints and ensure the container can hold SystemComponent elements");
            }
            invalidate(session);
            return ActionResult.ok("Created SystemComponent '" + name + "'");
        });
    }

    public static ActionResult createFunction(Session session, String layer, String name, String parentName) {
        if (session == null) {
            return ActionResult.fail(ErrorCode.SESSION_ERROR, "No active Capella session");
        }
        if (name == null || name.isBlank()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Function name is required");
        }
        return SessionSafeguards.runWriteWithResult(session, "Create system function", () -> {
            ContainerResult containerResult = resolveContainerWithDiagnostic(session, layer, parentName, "SystemFunction");
            if (!containerResult.success) {
                return containerResult.error;
            }
            EObject function = ElementFactory.createUnder(session, containerResult.container, "SystemFunction", name);
            if (function == null) {
                return ActionResult.fail(ErrorCode.CONSTRAINT_VIOLATION,
                    "Failed to create SystemFunction '" + name + "'",
                    "Check EMF constraints and ensure the container can hold SystemFunction elements");
            }
            invalidate(session);
            return ActionResult.ok("Created SystemFunction '" + name + "'");
        });
    }

    public static ActionResult createInterface(Session session, String layer, String name, String sourceComponent, String targetComponent) {
        if (session == null) {
            return ActionResult.fail(ErrorCode.SESSION_ERROR, "No active Capella session");
        }
        return SessionSafeguards.runWriteWithResult(session, "Create system interface", () -> {
            // Find SA root with diagnostics
            FindResult layerResult = ArchitectureLocator.findLayerWithDiagnostic(session, resolveLayer(layer, "SA"));
            if (layerResult.failed()) {
                return layerResult.toActionResult();
            }
            EObject saRoot = layerResult.element();

            // Create exchange
            EObject exchange = ElementFactory.createUnder(session, saRoot, "ComponentExchange", defaultName(name, "ComponentExchange"));
            if (exchange == null) {
                return ActionResult.fail(ErrorCode.CONSTRAINT_VIOLATION,
                    "Failed to create ComponentExchange",
                    "Check EMF constraints for ComponentExchange creation");
            }

            // Find source component with diagnostics
            FindResult sourceResult = Finders.findByNameWithDiagnostic(session, "SystemComponent", sourceComponent);
            if (sourceResult.failed()) {
                return ActionResult.failWithAlternatives(ErrorCode.REFERENCE_NOT_FOUND,
                    "Source component '" + sourceComponent + "' not found",
                    sourceResult.hasAlternatives() ? "Did you mean: " + String.join(", ", sourceResult.alternatives()) : null,
                    sourceResult.alternatives());
            }

            // Find target component with diagnostics
            FindResult targetResult = Finders.findByNameWithDiagnostic(session, "SystemComponent", targetComponent);
            if (targetResult.failed()) {
                return ActionResult.failWithAlternatives(ErrorCode.REFERENCE_NOT_FOUND,
                    "Target component '" + targetComponent + "' not found",
                    targetResult.hasAlternatives() ? "Did you mean: " + String.join(", ", targetResult.alternatives()) : null,
                    targetResult.alternatives());
            }

            assignEndpoint(exchange, "source", sourceResult.element());
            assignEndpoint(exchange, "target", targetResult.element());
            invalidate(session);
            return ActionResult.ok("Created ComponentExchange from '" + sourceComponent + "' to '" + targetComponent + "'");
        });
    }

    public static ActionResult allocateCapability(Session session, String componentName, String capabilityName) {
        if (session == null) {
            return ActionResult.fail(ErrorCode.SESSION_ERROR, "No active Capella session");
        }
        if (componentName == null || componentName.isBlank()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Component name is required");
        }
        if (capabilityName == null || capabilityName.isBlank()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Capability name is required");
        }
        return SessionSafeguards.runWriteWithResult(session, "Allocate capability to system component", () -> {
            // Find component with diagnostics
            FindResult componentResult = Finders.findByNameWithDiagnostic(session, "SystemComponent", componentName);
            if (componentResult.failed()) {
                return componentResult.toActionResult();
            }

            // Find capability with diagnostics
            FindResult capabilityResult = Finders.findByNameWithDiagnostic(session, "Capability", capabilityName);
            if (capabilityResult.failed()) {
                return ActionResult.failWithAlternatives(ErrorCode.REFERENCE_NOT_FOUND,
                    "Capability '" + capabilityName + "' not found",
                    capabilityResult.hasAlternatives() ? "Did you mean: " + String.join(", ", capabilityResult.alternatives()) : null,
                    capabilityResult.alternatives());
            }

            boolean linked = addReference(componentResult.element(), capabilityResult.element(), "capability", "allocation", "realization");
            if (!linked) {
                return ActionResult.fail(ErrorCode.CONSTRAINT_VIOLATION,
                    "Failed to link SystemComponent to Capability",
                    "No suitable reference feature found for capability allocation");
            }
            invalidate(session);
            return ActionResult.ok("Allocated '" + capabilityName + "' to '" + componentName + "'");
        });
    }

    // ========== Legacy boolean methods for backward compatibility ==========

    /** @deprecated Use {@link #createComponent(Session, String, String, String)} instead */
    @Deprecated
    public static boolean createComponentLegacy(Session session, String layer, String name, String parentName) {
        return createComponent(session, layer, name, parentName).success();
    }

    /** @deprecated Use {@link #createFunction(Session, String, String, String)} instead */
    @Deprecated
    public static boolean createFunctionLegacy(Session session, String layer, String name, String parentName) {
        return createFunction(session, layer, name, parentName).success();
    }

    /** @deprecated Use {@link #createInterface(Session, String, String, String, String)} instead */
    @Deprecated
    public static boolean createInterfaceLegacy(Session session, String layer, String name, String sourceComponent, String targetComponent) {
        return createInterface(session, layer, name, sourceComponent, targetComponent).success();
    }

    /** @deprecated Use {@link #allocateCapability(Session, String, String)} instead */
    @Deprecated
    public static boolean allocateCapabilityLegacy(Session session, String componentName, String capabilityName) {
        return allocateCapability(session, componentName, capabilityName).success();
    }

    // ========== Helper Classes ==========

    /** Result of container resolution with diagnostic info. */
    private static class ContainerResult {
        final boolean success;
        final EObject container;
        final ActionResult error;

        private ContainerResult(EObject container) {
            this.success = true;
            this.container = container;
            this.error = null;
        }

        private ContainerResult(ActionResult error) {
            this.success = false;
            this.container = null;
            this.error = error;
        }

        static ContainerResult ok(EObject container) {
            return new ContainerResult(container);
        }

        static ContainerResult fail(ActionResult error) {
            return new ContainerResult(error);
        }
    }

    // ========== Helper Methods ==========

    private static ContainerResult resolveContainerWithDiagnostic(Session session, String layer, String parentName, String className) {
        // First try to find named parent
        if (parentName != null && !parentName.isBlank()) {
            FindResult parentResult = Finders.findByNameWithDiagnostic(session, className, parentName);
            if (parentResult.success()) {
                return ContainerResult.ok(parentResult.element());
            }
            // Parent specified but not found - return error with alternatives
            return ContainerResult.fail(ActionResult.failWithAlternatives(
                ErrorCode.PARENT_NOT_FOUND,
                "Parent " + className + " '" + parentName + "' not found",
                parentResult.alternatives()));
        }
        // No parent specified - use layer root
        FindResult layerResult = ArchitectureLocator.findLayerWithDiagnostic(session, resolveLayer(layer, "SA"));
        if (layerResult.failed()) {
            return ContainerResult.fail(layerResult.toActionResult());
        }
        return ContainerResult.ok(layerResult.element());
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
