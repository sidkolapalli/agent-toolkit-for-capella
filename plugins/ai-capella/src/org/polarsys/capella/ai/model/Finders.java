package org.polarsys.capella.ai.model;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.session.SessionBridge;

public final class Finders {
    private Finders() {}

    public static EObject findByName(String simpleClass, String name) {
        Session session = SessionBridge.getActiveSession();
        if (session == null) {
            return null;
        }
        return findByName(session, simpleClass, name);
    }

    public static EObject findByName(Session session, String simpleClass, String name) {
        if (session == null) {
            return null;
        }
        ResourceSet rs = session.getTransactionalEditingDomain() != null
                ? session.getTransactionalEditingDomain().getResourceSet()
                : null;
        return findByName(rs, simpleClass, name);
    }

    public static EObject findByName(ResourceSet rs, String simpleClass, String name) {
        if (rs == null || simpleClass == null || name == null) {
            return null;
        }
        try {
            for (Resource res : rs.getResources()) {
                if (res == null) {
                    continue;
                }
                var it = res.getAllContents();
                while (it.hasNext()) {
                    EObject eo = it.next();
                    try {
                        if (!eo.eClass().getName().equals(simpleClass)) {
                            continue;
                        }
                        var feature = eo.eClass().getEStructuralFeature("name");
                        if (feature != null) {
                            Object value = eo.eGet(feature);
                            if (value != null && name.equals(String.valueOf(value))) {
                                return eo;
                            }
                        }
                    } catch (Exception ignore) {
                        // keep searching
                    }
                }
            }
        } catch (Exception ignore) {
            // best effort lookup
        }
        return null;
    }
}
