package org.polarsys.capella.ai.model;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
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
                if (root instanceof Project) {
                    Project prj = (Project) root;
                    for (EObject child : prj.eContents()) {
                        if ("OA".equalsIgnoreCase(layer) && child instanceof OperationalAnalysis) return child;
                        if ("LA".equalsIgnoreCase(layer) && child instanceof LogicalArchitecture) return child;
                        if ("PA".equalsIgnoreCase(layer) && child instanceof PhysicalArchitecture) return child;
                        // SA (SystemAnalysis) class lives in ctx package; avoid compile dep: compare class name
                        if ("SA".equalsIgnoreCase(layer) && child.eClass().getName().equals("SystemAnalysis")) return child;
                    }
                }
            }
        } catch (Exception ignore) {}
        return null;
    }
}

