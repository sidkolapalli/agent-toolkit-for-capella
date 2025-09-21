package org.polarsys.capella.ai.model;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.core.data.capellamodeller.Project;
import org.polarsys.capella.core.data.la.LogicalArchitecture;
import org.polarsys.capella.core.data.oa.OperationalAnalysis;
import org.polarsys.capella.core.data.pa.PhysicalArchitecture;

/** Find ARCADIA architecture roots in the current model. */
public final class ArchitectureLocator {
    private ArchitectureLocator() {}

    public static EObject findTargetElement(Session session, String layer) {
        try {
            for (Resource res : session.getSemanticResources()) {
                if (res.getContents().isEmpty()) continue;
                EObject root = res.getContents().get(0);
                EObject found = findInProject(root, layer);
                if (found != null) {
                    return found;
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    public static EObject findTargetElement(ResourceSet resourceSet, String layer) {
        if (resourceSet == null) {
            return null;
        }
        try {
            for (Resource res : resourceSet.getResources()) {
                if (res == null || res.getContents().isEmpty()) {
                    continue;
                }
                EObject root = res.getContents().get(0);
                EObject found = findInProject(root, layer);
                if (found != null) {
                    return found;
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    private static EObject findInProject(EObject root, String layer) {
        if (!(root instanceof Project)) {
            return null;
        }
        Project project = (Project) root;
        for (EObject child : project.eContents()) {
            if ("OA".equalsIgnoreCase(layer) && child instanceof OperationalAnalysis) return child;
            if ("LA".equalsIgnoreCase(layer) && child instanceof LogicalArchitecture) return child;
            if ("PA".equalsIgnoreCase(layer) && child instanceof PhysicalArchitecture) return child;
            if ("SA".equalsIgnoreCase(layer) && child.eClass().getName().equals("SystemAnalysis")) return child;
        }
        return null;
    }
}

