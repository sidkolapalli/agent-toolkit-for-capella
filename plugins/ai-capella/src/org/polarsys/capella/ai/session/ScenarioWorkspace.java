package org.polarsys.capella.ai.session;

import java.util.Optional;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil.Copier;
import org.eclipse.sirius.business.api.session.Session;

/**
 * Lightweight, in-memory clone of the active Capella session for sandboxed plan evaluation.
 */
public final class ScenarioWorkspace {
    private final ResourceSet resourceSet;

    private ScenarioWorkspace(ResourceSet resourceSet) {
        this.resourceSet = resourceSet;
    }

    public ResourceSet getResourceSet() {
        return resourceSet;
    }

    /**
     * Attempt to clone the semantic resources of the given session into an isolated ResourceSet.
     */
    public static Optional<ScenarioWorkspace> create(Session session) {
        try {
            if (session == null || session.getTransactionalEditingDomain() == null) {
                return Optional.empty();
            }
            ResourceSet source = session.getTransactionalEditingDomain().getResourceSet();
            if (source == null) {
                return Optional.empty();
            }
            ResourceSetImpl copySet = new ResourceSetImpl();
            Copier copier = new Copier(true, true);
            for (Resource res : source.getResources()) {
                if (res == null || res.getContents().isEmpty()) {
                    continue;
                }
                Resource copyRes = copySet.createResource(res.getURI());
                for (EObject root : res.getContents()) {
                    EObject copyRoot = copier.copy(root);
                    copyRes.getContents().add(copyRoot);
                }
            }
            copier.copyReferences();
            return Optional.of(new ScenarioWorkspace(copySet));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }
}
