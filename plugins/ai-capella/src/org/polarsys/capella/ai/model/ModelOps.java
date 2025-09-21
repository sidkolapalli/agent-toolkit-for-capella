package org.polarsys.capella.ai.model;

import java.util.logging.Logger;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.transaction.RecordingCommand;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.session.SessionBridge;

/** Generic EMF operations (rename/delete) using the active Capella session. */
public final class ModelOps {
    private static final Logger LOG = Logger.getLogger(ModelOps.class.getName());
    private ModelOps() {}

    /** Rename an element by uuid/id if it has a 'name' feature. */
    public static boolean renameByUuid(String uuid, String newName) {
        Session session = SessionBridge.getActiveSession();
        if (session == null) return false;
        return renameByUuid(session, uuid, newName);
    }

    public static boolean renameByUuid(Session session, String uuid, String newName) {
        if (session == null) return false;
        TransactionalEditingDomain domain = session.getTransactionalEditingDomain();
        if (domain == null) return false;
        EObject target = findById(domain.getResourceSet(), uuid);
        if (target == null) return false;
        EStructuralFeature nameFeat = target.eClass().getEStructuralFeature("name");
        if (nameFeat == null || !nameFeat.isChangeable()) return false;
        domain.getCommandStack().execute(new RecordingCommand(domain) {
            @Override
            protected void doExecute() {
                target.eSet(nameFeat, newName);
            }
        });
        ModelSummary.invalidate(session);
        trySave(session);
        return true;
    }

    /** Delete an element by uuid/id from its container. */
    public static boolean deleteByUuid(String uuid) {
        Session session = SessionBridge.getActiveSession();
        if (session == null) return false;
        return deleteByUuid(session, uuid);
    }

    public static boolean deleteByUuid(Session session, String uuid) {
        if (session == null) return false;
        TransactionalEditingDomain domain = session.getTransactionalEditingDomain();
        if (domain == null) return false;
        EObject target = findById(domain.getResourceSet(), uuid);
        if (target == null) return false;
        domain.getCommandStack().execute(new RecordingCommand(domain) {
            @Override
            protected void doExecute() {
                EObject container = target.eContainer();
                if (container != null) {
                    EStructuralFeature feat = target.eContainingFeature();
                    if (feat.isMany()) {
                        @SuppressWarnings("unchecked")
                        java.util.List<EObject> list = (java.util.List<EObject>) container.eGet(feat);
                        list.remove(target);
                    } else {
                        container.eSet(feat, null);
                    }
                } else if (target.eResource() != null) {
                    target.eResource().getContents().remove(target);
                }
            }
        });
        ModelSummary.invalidate(session);
        trySave(session);
        return true;
    }

    private static void trySave(Session s) {
        try { s.save(new NullProgressMonitor()); } catch (Exception e) { LOG.warning("Save failed: " + e.getMessage()); }
    }

    /** Resolve an EObject by id/uuid/fragment. */
    public static EObject findById(org.eclipse.emf.ecore.resource.ResourceSet rs, String id) {
        try {
            for (Resource res : rs.getResources()) {
                if (res == null) continue;
                var it = res.getAllContents();
                while (it.hasNext()) {
                    EObject eo = it.next();
                    try {
                        var cls = eo.eClass();
                        var idFeat = cls.getEStructuralFeature("id");
                        if (idFeat != null) { Object v = eo.eGet(idFeat); if (id.equals(v)) return eo; }
                        var uuidFeat = cls.getEStructuralFeature("uuid");
                        if (uuidFeat != null) { Object v = eo.eGet(uuidFeat); if (id.equals(v)) return eo; }
                        var uri = org.eclipse.emf.ecore.util.EcoreUtil.getURI(eo);
                        if (uri != null && id.equals(uri.fragment())) return eo;
                    } catch (Exception ignore) {}
                }
            }
        } catch (Exception ignore) {}
        return null;
    }
}
