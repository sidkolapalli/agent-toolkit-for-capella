package org.polarsys.capella.ai.model;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.session.SessionBridge;

public final class Finders {
    private Finders() {}

    public static EObject findByName(String simpleClass, String name) {
        Session session = SessionBridge.getActiveSession();
        if (session == null) return null;
        return findByName(session, simpleClass, name);
    }

    public static EObject findByName(Session session, String simpleClass, String name) {
        if (session == null) return null;
        var rs = session.getTransactionalEditingDomain() != null ? session.getTransactionalEditingDomain().getResourceSet() : null;
        if (rs == null) return null;
        try {
            for (Resource res : rs.getResources()) {
                if (res == null) continue;
                var it = res.getAllContents();
                while (it.hasNext()) {
                    EObject eo = it.next();
                    try {
                        if (!eo.eClass().getName().equals(simpleClass)) continue;
                        var f = eo.eClass().getEStructuralFeature("name");
                        if (f != null) {
                            Object v = eo.eGet(f);
                            if (v != null && name.equals(String.valueOf(v))) return eo;
                        }
                    } catch (Exception ignore) {}
                }
            }
        } catch (Exception ignore) {}
        return null;
    }
}
