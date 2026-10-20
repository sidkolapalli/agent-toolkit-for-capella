package org.polarsys.capella.ai.model.finder;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.scripting.RequirementsAPI;

import java.util.ArrayList;
import java.util.List;

/** Finder for native ReqIF identifiers and titles, with legacy name support. */
public final class RequirementFinder {
    private RequirementFinder() {}

    public static EObject findByIdentifier(Session session, String identifier) {
        if (session == null || identifier == null || identifier.isBlank()) return null;
        RequirementsAPI requirements = new RequirementsAPI(session);
        if (requirements.available()) {
            EObject found = requirements.find(identifier.trim());
            return isRequirement(found) ? found : null;
        }
        return findByIdentifier(resources(session), identifier);
    }

    public static EObject findByIdentifier(ResourceSet resources, String identifier) {
        return find(resources, identifier, "ReqIFIdentifier");
    }

    public static EObject findByName(Session session, String name) {
        if (name == null || name.isBlank()) return null;
        EObject identified = findByIdentifier(session, name);
        return identified != null ? identified : findByName(resources(session), name);
    }

    public static EObject findByName(ResourceSet resources, String name) {
        EObject identified = findByIdentifier(resources, name);
        return identified != null
                ? identified
                : find(resources, name, "ReqIFLongName", "ReqIFName", "name");
    }

    public static List<EObject> listAll(Session session) {
        return listAll(resources(session));
    }

    public static List<EObject> listAll(ResourceSet resources) {
        List<EObject> result = new ArrayList<>();
        if (resources == null) return result;
        for (Resource resource : List.copyOf(resources.getResources())) {
            if (resource == null) continue;
            resource.getAllContents()
                    .forEachRemaining(
                            item -> {
                                if (isRequirement(item)) result.add(item);
                            });
        }
        return result;
    }

    private static EObject find(ResourceSet resources, String value, String... features) {
        if (resources == null || value == null || value.isBlank()) return null;
        String target = value.trim();
        EObject found = null;
        for (EObject requirement : listAll(resources)) {
            for (String featureName : features) {
                var feature = requirement.eClass().getEStructuralFeature(featureName);
                if (feature != null && target.equals(requirement.eGet(feature))) {
                    if (found != null && found != requirement) {
                        throw new IllegalStateException("Ambiguous requirement lookup: " + target);
                    }
                    found = requirement;
                    break;
                }
            }
        }
        return found;
    }

    private static ResourceSet resources(Session session) {
        return session != null && session.getTransactionalEditingDomain() != null
                ? session.getTransactionalEditingDomain().getResourceSet()
                : null;
    }

    private static boolean isRequirement(EObject object) {
        if (object == null || "Folder".equals(object.eClass().getName())) return false;
        return "Requirement".equals(object.eClass().getName())
                || object.eClass().getEAllSuperTypes().stream()
                        .anyMatch(type -> "Requirement".equals(type.getName()));
    }
}
