package org.polarsys.capella.ai.model.finder;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.sirius.business.api.session.Session;

/** Typed finder utilities for LogicalComponent elements. */
public final class LogicalComponentFinder {
    private LogicalComponentFinder() {}

    public static EObject findByName(ResourceSet rs, String name) {
        return findByName(rs, name, true);
    }

    public static EObject findByName(ResourceSet rs, String name, boolean exact) {
        if (rs == null || name == null) return null;
        String target = name.trim();
        for (Resource res : rs.getResources()) {
            if (res == null) continue;
            var it = res.getAllContents();
            while (it.hasNext()) {
                EObject eo = it.next();
                if (!"LogicalComponent".equals(eo.eClass().getName())) continue;
                var f = eo.eClass().getEStructuralFeature("name");
                if (f != null) {
                    Object v = eo.eGet(f);
                    if (v != null) {
                        String s = String.valueOf(v);
                        if ((exact && target.equals(s)) || (!exact && s != null && s.toLowerCase().contains(target.toLowerCase()))) {
                            return eo;
                        }
                    }
                }
            }
        }
        return null;
    }

    public static List<EObject> listAll(Session session) {
        List<EObject> out = new ArrayList<>();
        if (session == null || session.getTransactionalEditingDomain() == null) return out;
        ResourceSet rs = session.getTransactionalEditingDomain().getResourceSet();
        for (Resource res : rs.getResources()) {
            if (res == null) continue;
            var it = res.getAllContents();
            while (it.hasNext()) {
                EObject eo = it.next();
                if ("LogicalComponent".equals(eo.eClass().getName())) out.add(eo);
            }
        }
        return out;
    }
}
