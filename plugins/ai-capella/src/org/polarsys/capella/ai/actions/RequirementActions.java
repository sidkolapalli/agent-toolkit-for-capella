package org.polarsys.capella.ai.actions;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.model.ElementFactory;
import org.polarsys.capella.ai.model.Finders;
import org.polarsys.capella.ai.model.ModelSummary;
import org.polarsys.capella.ai.model.index.ModelIndex;
import org.polarsys.capella.ai.session.SessionSafeguards;

/** Helpers for requirement authoring and tracing. */
public final class RequirementActions {
    private RequirementActions() {
    }

    public static boolean createRequirement(Session session, String containerClass, String containerName, String type, String name, String description) {
        if (session == null || name == null || name.isBlank()) {
            return false;
        }
        return SessionSafeguards.runWrite(session, "Create requirement", () -> {
            EObject container = resolveContainer(session, containerClass, containerName);
            if (container == null) {
                return false;
            }
            EObject requirement = createRequirementElement(session, type, name, description);
            if (requirement == null) {
                return false;
            }
            EReference containment = findContainment(container, requirement.eClass());
            if (containment == null) {
                return false;
            }
            addChild(container, containment, requirement);
            invalidate(session);
            return true;
        });
    }

    public static boolean linkRequirement(Session session, String requirementName, String targetClass, String targetName) {
        if (session == null || requirementName == null || targetName == null) {
            return false;
        }
        return SessionSafeguards.runWrite(session, "Link requirement", () -> {
            EObject requirement = Finders.findByName(session, "Requirement", requirementName);
            if (requirement == null) {
                requirement = Finders.findByName(session, "SystemRequirement", requirementName);
            }
            if (requirement == null) {
                requirement = Finders.findByName(session, "LogicalRequirement", requirementName);
            }
            if (requirement == null) {
                requirement = Finders.findByName(session, "PhysicalRequirement", requirementName);
            }
            if (requirement == null) {
                return false;
            }
            EObject target = Finders.findByName(session, targetClass, targetName);
            if (target == null) {
                return false;
            }
            boolean linked = addReference(requirement, target, "satisfy", "verify", "trace", "allocate");
            if (linked) {
                invalidate(session);
            }
            return linked;
        });
    }

    public static boolean recordCoverage(Session session, String targetClass, String targetName, String status, String notes) {
        if (session == null || targetClass == null || targetName == null) {
            return false;
        }
        return SessionSafeguards.runWrite(session, "Record requirement coverage", () -> {
            EObject target = Finders.findByName(session, targetClass, targetName);
            if (target == null) {
                return false;
            }
            setIfPresent(target, "status", status);
            setIfPresent(target, "rationale", notes);
            invalidate(session);
            return true;
        });
    }

    private static EObject resolveContainer(Session session, String containerClass, String containerName) {
        if (containerClass != null && containerName != null && !containerName.isBlank()) {
            EObject container = Finders.findByName(session, containerClass, containerName);
            if (container != null) {
                return container;
            }
        }
        // fall back to top-level requirements package
        String[] classes = {"RequirementsPkg", "SystemRequirementsPkg", "LogicalRequirementsPkg", "PhysicalRequirementsPkg"};
        for (String cls : classes) {
            EObject pkg = Finders.findByName(session, cls, containerName);
            if (pkg != null) {
                return pkg;
            }
        }
        // try global search by class only
        if (containerClass != null) {
            EObject container = Finders.findByName(session, containerClass, containerName != null ? containerName : "");
            if (container != null) {
                return container;
            }
        }
        return Finders.findByName(session, "RequirementsPkg", "Requirements");
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
