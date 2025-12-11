package org.polarsys.capella.ai.actions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.model.ActionResult;
import org.polarsys.capella.ai.model.ActionResult.ErrorCode;
import org.polarsys.capella.ai.model.ElementFactory;
import org.polarsys.capella.ai.model.FindResult;
import org.polarsys.capella.ai.model.Finders;
import org.polarsys.capella.ai.model.ModelSummary;
import org.polarsys.capella.ai.model.index.ModelIndex;
import org.polarsys.capella.ai.session.SessionSafeguards;

/** Helpers for requirement authoring and tracing. */
public final class RequirementActions {
    private static final List<String> REQUIREMENT_CLASSES = List.of(
            "Requirement",
            "SystemRequirement",
            "LogicalRequirement",
            "PhysicalRequirement"
    );

    private RequirementActions() {
    }

    public static ActionResult createRequirement(Session session, String containerClass, String containerName, String type, String name, String description) {
        if (session == null) {
            return ActionResult.fail(ErrorCode.SESSION_ERROR, "No active Capella session");
        }
        if (name == null || name.isBlank()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Requirement name is required");
        }
        return SessionSafeguards.runWriteWithResult(session, "Create requirement", () -> {
            ContainerLookupResult containerResult = resolveContainerWithDiagnostic(session, containerClass, containerName);
            if (!containerResult.success) {
                return containerResult.error;
            }
            EObject container = containerResult.container;

            EObject requirement = createRequirementElement(session, type, name, description);
            if (requirement == null) {
                return ActionResult.fail(ErrorCode.CONSTRAINT_VIOLATION,
                    "Failed to create Requirement '" + name + "' of type '" + (type != null ? type : "Requirement") + "'",
                    "Check that the requirement type exists in the metamodel");
            }
            EReference containment = findContainment(container, requirement.eClass());
            if (containment == null) {
                return ActionResult.fail(ErrorCode.INVALID_CONTAINER,
                    "Container cannot hold Requirement elements",
                    "Choose a RequirementsPkg or similar container");
            }
            addChild(container, containment, requirement);
            invalidate(session);
            return ActionResult.ok("Created " + requirement.eClass().getName() + " '" + name + "'");
        });
    }

    public static ActionResult linkRequirement(Session session, String requirementName, String targetClass, String targetName) {
        if (session == null) {
            return ActionResult.fail(ErrorCode.SESSION_ERROR, "No active Capella session");
        }
        if (requirementName == null || requirementName.isBlank()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Requirement name is required");
        }
        if (targetName == null || targetName.isBlank()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Target element name is required");
        }
        if (targetClass == null || targetClass.isBlank()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Target element class is required");
        }
        return SessionSafeguards.runWriteWithResult(session, "Link requirement", () -> {
            // Find requirement with diagnostics (try multiple classes)
            RequirementLookupResult requirementResult = findRequirementWithDiagnostic(session, requirementName);
            if (!requirementResult.success) {
                return requirementResult.error;
            }
            EObject requirement = requirementResult.requirement;

            // Find target with diagnostics
            FindResult targetResult = Finders.findByNameWithDiagnostic(session, targetClass, targetName);
            if (targetResult.failed()) {
                return ActionResult.failWithAlternatives(ErrorCode.REFERENCE_NOT_FOUND,
                    targetClass + " '" + targetName + "' not found",
                    targetResult.hasAlternatives() ? "Did you mean: " + String.join(", ", targetResult.alternatives()) : null,
                    targetResult.alternatives());
            }
            EObject target = targetResult.element();

            boolean linked = addReference(requirement, target, "satisfy", "verify", "trace", "allocate");
            if (!linked) {
                return ActionResult.fail(ErrorCode.CONSTRAINT_VIOLATION,
                    "Failed to link requirement to " + targetClass,
                    "No suitable reference found for requirement linking");
            }
            invalidate(session);
            return ActionResult.ok("Linked Requirement '" + requirementName + "' to " + targetClass + " '" + targetName + "'");
        });
    }

    public static ActionResult recordCoverage(Session session, String targetClass, String targetName, String status, String notes) {
        if (session == null) {
            return ActionResult.fail(ErrorCode.SESSION_ERROR, "No active Capella session");
        }
        if (targetClass == null || targetClass.isBlank()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Target element class is required");
        }
        if (targetName == null || targetName.isBlank()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Target element name is required");
        }
        return SessionSafeguards.runWriteWithResult(session, "Record requirement coverage", () -> {
            // Find target with diagnostics
            FindResult targetResult = Finders.findByNameWithDiagnostic(session, targetClass, targetName);
            if (targetResult.failed()) {
                return targetResult.toActionResult();
            }
            EObject target = targetResult.element();

            setIfPresent(target, "status", status);
            setIfPresent(target, "rationale", notes);
            invalidate(session);

            StringBuilder msg = new StringBuilder("Recorded coverage on " + targetClass + " '" + targetName + "'");
            if (status != null && !status.isBlank()) {
                msg.append(" (status: ").append(status).append(")");
            }
            return ActionResult.ok(msg.toString());
        });
    }

    // ========== Legacy boolean methods for backward compatibility ==========

    /** @deprecated Use {@link #createRequirement(Session, String, String, String, String, String)} instead */
    @Deprecated
    public static boolean createRequirementLegacy(Session session, String containerClass, String containerName, String type, String name, String description) {
        return createRequirement(session, containerClass, containerName, type, name, description).success();
    }

    /** @deprecated Use {@link #linkRequirement(Session, String, String, String)} instead */
    @Deprecated
    public static boolean linkRequirementLegacy(Session session, String requirementName, String targetClass, String targetName) {
        return linkRequirement(session, requirementName, targetClass, targetName).success();
    }

    /** @deprecated Use {@link #recordCoverage(Session, String, String, String, String)} instead */
    @Deprecated
    public static boolean recordCoverageLegacy(Session session, String targetClass, String targetName, String status, String notes) {
        return recordCoverage(session, targetClass, targetName, status, notes).success();
    }

    // ========== Helper Classes ==========

    /** Result of container lookup with diagnostic info. */
    private static class ContainerLookupResult {
        final boolean success;
        final EObject container;
        final ActionResult error;

        private ContainerLookupResult(EObject container) {
            this.success = true;
            this.container = container;
            this.error = null;
        }

        private ContainerLookupResult(ActionResult error) {
            this.success = false;
            this.container = null;
            this.error = error;
        }

        static ContainerLookupResult ok(EObject container) {
            return new ContainerLookupResult(container);
        }

        static ContainerLookupResult fail(ActionResult error) {
            return new ContainerLookupResult(error);
        }
    }

    /** Result of requirement lookup with diagnostic info. */
    private static class RequirementLookupResult {
        final boolean success;
        final EObject requirement;
        final ActionResult error;

        private RequirementLookupResult(EObject requirement) {
            this.success = true;
            this.requirement = requirement;
            this.error = null;
        }

        private RequirementLookupResult(ActionResult error) {
            this.success = false;
            this.requirement = null;
            this.error = error;
        }

        static RequirementLookupResult ok(EObject requirement) {
            return new RequirementLookupResult(requirement);
        }

        static RequirementLookupResult fail(ActionResult error) {
            return new RequirementLookupResult(error);
        }
    }

    // ========== Helper Methods ==========

    private static RequirementLookupResult findRequirementWithDiagnostic(Session session, String name) {
        List<String> allAlternatives = new ArrayList<>();
        for (String cls : REQUIREMENT_CLASSES) {
            FindResult result = Finders.findByNameWithDiagnostic(session, cls, name);
            if (result.success()) {
                return RequirementLookupResult.ok(result.element());
            }
            if (result.hasAlternatives()) {
                allAlternatives.addAll(result.alternatives());
            }
        }
        String suggestion = allAlternatives.isEmpty() ? null : "Did you mean: " + String.join(", ", allAlternatives);
        return RequirementLookupResult.fail(ActionResult.fail(ErrorCode.ELEMENT_NOT_FOUND,
            "Requirement '" + name + "' not found (tried: " + String.join(", ", REQUIREMENT_CLASSES) + ")",
            suggestion));
    }

    private static ContainerLookupResult resolveContainerWithDiagnostic(Session session, String containerClass, String containerName) {
        // Try specified class and name first
        if (containerClass != null && !containerClass.isBlank() && containerName != null && !containerName.isBlank()) {
            FindResult result = Finders.findByNameWithDiagnostic(session, containerClass, containerName);
            if (result.success()) {
                return ContainerLookupResult.ok(result.element());
            }
            // Return error with alternatives if explicitly specified container not found
            return ContainerLookupResult.fail(ActionResult.failWithAlternatives(
                ErrorCode.PARENT_NOT_FOUND,
                containerClass + " '" + containerName + "' not found",
                result.alternatives()));
        }

        // Try requirements packages
        String[] packageClasses = {"RequirementsPkg", "SystemRequirementsPkg", "LogicalRequirementsPkg", "PhysicalRequirementsPkg"};
        List<String> allAlternatives = new ArrayList<>();
        for (String cls : packageClasses) {
            FindResult result = Finders.findByNameWithDiagnostic(session, cls, containerName != null ? containerName : "");
            if (result.success()) {
                return ContainerLookupResult.ok(result.element());
            }
            if (result.hasAlternatives()) {
                allAlternatives.addAll(result.alternatives());
            }
        }

        // Fall back to default "Requirements" package
        FindResult defaultResult = Finders.findByNameWithDiagnostic(session, "RequirementsPkg", "Requirements");
        if (defaultResult.success()) {
            return ContainerLookupResult.ok(defaultResult.element());
        }

        String suggestion = allAlternatives.isEmpty() ? "Create a RequirementsPkg first" : "Available: " + String.join(", ", allAlternatives);
        return ContainerLookupResult.fail(ActionResult.fail(ErrorCode.PARENT_NOT_FOUND,
            "No suitable requirements container found",
            suggestion));
    }

    private static EObject createRequirementElement(Session session, String type, String name, String description) {
        String effectiveType = (type != null && !type.isBlank()) ? type : "Requirement";
        EClass eClass = ElementFactory.findEClass(effectiveType);
        if (eClass == null) {
            eClass = ElementFactory.findEClass("Requirement");
        }
        if (eClass == null) {
            return null;
        }
        EObject requirement = eClass.getEPackage().getEFactoryInstance().create(eClass);
        setIfPresent(requirement, "name", name);
        setIfPresent(requirement, "description", description);
        return requirement;
    }

    private static void addChild(EObject container, EReference containment, EObject child) {
        Object current = container.eGet(containment);
        if (current instanceof Collection<?>) {
            @SuppressWarnings("unchecked")
            Collection<EObject> collection = (Collection<EObject>) current;
            collection.add(child);
        }
    }

    private static EReference findContainment(EObject container, EClass type) {
        for (EStructuralFeature feature : container.eClass().getEAllStructuralFeatures()) {
            if (feature instanceof EReference ref && ref.isContainment() && ref.isMany()) {
                if (ref.getEReferenceType().isSuperTypeOf(type) || ref.getEReferenceType() == type) {
                    return ref;
                }
            }
        }
        return null;
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

    private static void setIfPresent(EObject element, String featureName, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        EStructuralFeature feature = element.eClass().getEStructuralFeature(featureName);
        if (feature != null && feature.isChangeable()) {
            element.eSet(feature, value);
        }
    }

    private static void invalidate(Session session) {
        ModelSummary.invalidate(session);
        ModelIndex.invalidate(session);
        SessionSafeguards.requestSave(session);
    }
}
