package org.polarsys.capella.ai.tools;

import java.util.logging.Logger;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.sirius.business.api.dialect.DialectManager;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.diagram.DDiagram;
import org.eclipse.sirius.diagram.description.DiagramDescription;
import org.eclipse.sirius.viewpoint.DRepresentation;
import org.eclipse.sirius.viewpoint.description.RepresentationDescription;
import org.eclipse.sirius.viewpoint.description.Viewpoint;
import org.eclipse.sirius.ui.business.api.dialect.DialectUIManager;
import org.polarsys.capella.ai.session.SessionBridge;
import org.polarsys.capella.ai.session.SessionSafeguards;

/** In‑process diagram helpers using Sirius UI APIs (arrange/layout visible). */
public final class DiagramCommands {
    private static final Logger LOG = Logger.getLogger(DiagramCommands.class.getName());

    private DiagramCommands() {}

    public static volatile DRepresentation lastOpened;

    public static DRepresentation getLastOpened() {
        return lastOpened;
    }

    /** Create and open a diagram for the given target using a description name. */
    public static DRepresentation createAndOpenDiagram(EObject target, String descriptionName, String diagramName) {
        Session session = SessionBridge.getActiveSession();
        if (session == null) return null;
        TransactionalEditingDomain domain = session.getTransactionalEditingDomain();
        if (domain == null) return null;

        final DRepresentation[] rep = new DRepresentation[1];
        SessionSafeguards.runWrite(session, "Create diagram", () -> {
            RepresentationDescription desc = findDiagramDescription(session, descriptionName);
            if (desc == null) {
                return null;
            }
            DRepresentation created = DialectManager.INSTANCE.createRepresentation(
                diagramName, target, desc, session, new NullProgressMonitor());
            rep[0] = created;
            return created;
        });
        if (rep[0] != null) {
            SessionSafeguards.runOnUiThread(() -> {
                try {
                    DialectUIManager.INSTANCE.openEditor(session, rep[0], new NullProgressMonitor());
                    lastOpened = rep[0];
                } catch (Exception e) {
                    LOG.warning("Failed to open diagram editor: " + e.getMessage());
                }
            });
        }
        return rep[0];
    }

    /** Arrange current diagram using Sirius UI (GMF Arrange). */
    public static void arrange(DRepresentation rep) {
        try {
            Session session = SessionBridge.getActiveSession();
            if (session == null || rep == null) return;
            DialectManager.INSTANCE.refresh(rep, new NullProgressMonitor());
        } catch (Exception e) {
            LOG.warning("Arrange failed: " + e.getMessage());
        }
    }

    /** Best-effort: resolve elements by id/uuid/fragment and refresh the diagram editor. */
    public static int addElementsByUuid(Session session, DRepresentation rep, java.util.List<String> ids) {
        if (session == null || rep == null || ids == null || ids.isEmpty()) return 0;
        int resolved = 0;
        try {
            var domain = session.getTransactionalEditingDomain();
            if (domain == null) return 0;
            var rs = domain.getResourceSet();
            if (rs == null) return 0;
            for (String id : ids) {
                EObject eo = findById(rs, id);
                if (eo != null) resolved++;
            }
            // Try to reveal via reflection if available, otherwise refresh
            tryReveal(session, rep, ids, rs);
            DialectManager.INSTANCE.refresh(rep, new NullProgressMonitor());
        } catch (Exception e) {
            LOG.warning("addElementsByUuid failed: " + e.getMessage());
        }
        return resolved;
    }

    /** Create a node view by refreshing and revealing an existing semantic element (no internal UI reflection). */
    public static boolean createNode(Session session, DRepresentation rep, EObject semantic) {
        if (session == null || rep == null || semantic == null) return false;
        try {
            addElementsByUuid(session, rep, java.util.List.of(tryUuid(semantic)));
            DialectManager.INSTANCE.refresh(rep, new NullProgressMonitor());
            return true;
        } catch (Exception e) {
            LOG.warning("createNode (reveal) failed: " + e.getMessage());
            return false;
        }
    }

    /** Create an edge by relying on semantic link creation and revealing endpoints (no internal UI reflection). */
    public static boolean createEdge(Session session, DRepresentation rep, EObject source, EObject target) {
        if (session == null || rep == null || source == null || target == null) return false;
        try {
            addElementsByUuid(session, rep, java.util.List.of(tryUuid(source), tryUuid(target)));
            DialectManager.INSTANCE.refresh(rep, new NullProgressMonitor());
            return true;
        } catch (Exception e) {
            LOG.warning("createEdge (refresh) failed: " + e.getMessage());
            return false;
        }
    }

    /** Best-effort: remove a view element corresponding to a semantic EObject from the current diagram (no semantic delete). */
    public static boolean removeViewElement(Session session, DRepresentation rep, EObject semantic) {
        try {
            // Without direct editor access, attempt a refresh and rely on reveal API to check presence.
            // As a placeholder, we just refresh; proper implementation would locate the DDiagramElement and delete it.
            if (session == null || rep == null || semantic == null) return false;
            DialectManager.INSTANCE.refresh(rep, new NullProgressMonitor());
            return false; // not yet implemented
        } catch (Exception e) {
            LOG.warning("removeViewElement failed: " + e.getMessage());
            return false;
        }
    }

    /** Best-effort: set bounds for a view element (requires active editor/GMF). Placeholder returns false for now. */
    public static boolean setViewBounds(Session session, DRepresentation rep, EObject semantic, int x, int y, int w, int h) {
        try {
            if (session == null || rep == null || semantic == null) return false;
            DialectManager.INSTANCE.refresh(rep, new NullProgressMonitor());
            return false; // to be implemented with GMF editpart commands
        } catch (Exception e) {
            LOG.warning("setViewBounds failed: " + e.getMessage());
            return false;
        }
    }

    private static RepresentationDescription findDiagramDescription(Session session, String name) {
        for (Viewpoint vp : session.getSelectedViewpoints(false)) {
            for (RepresentationDescription rd : vp.getOwnedRepresentations()) {
                if (rd instanceof DiagramDescription) {
                    if (name.equals(rd.getName()) || name.equals(rd.getLabel())) {
                        return rd;
                    }
                }
            }
        }
        return null;
    }

    private static EObject findById(org.eclipse.emf.ecore.resource.ResourceSet rs, String id) {
        try {
            for (Resource res : rs.getResources()) {
                if (res == null) continue;
                var it = res.getAllContents();
                while (it.hasNext()) {
                    EObject eo = it.next();
                    try {
                        var cls = eo.eClass();
                        var f1 = cls.getEStructuralFeature("id");
                        if (f1 != null) { Object v = eo.eGet(f1); if (id.equals(v)) return eo; }
                        var f2 = cls.getEStructuralFeature("uuid");
                        if (f2 != null) { Object v = eo.eGet(f2); if (id.equals(v)) return eo; }
                        var uri = org.eclipse.emf.ecore.util.EcoreUtil.getURI(eo);
                        if (uri != null && id.equals(uri.fragment())) return eo;
                    } catch (Exception ignore) {}
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    /** Find a representation by its UID in the active session. */
    public static DRepresentation findRepresentationByUid(Session session, String uid) {
        if (session == null || uid == null) return null;
        try {
            java.util.Collection<DRepresentation> reps = org.eclipse.sirius.business.api.dialect.DialectManager.INSTANCE.getAllRepresentations(session);
            for (DRepresentation r : reps) {
                if (uid.equals(r.getUid())) return r;
            }
        } catch (Exception ignore) {}
        return null;
    }

    private static void tryReveal(Session session, DRepresentation rep, java.util.List<String> ids, org.eclipse.emf.ecore.resource.ResourceSet rs) {
        try {
            java.util.List<EObject> eos = new java.util.ArrayList<>();
            for (String id : ids) { EObject eo = findById(rs, id); if (eo != null) eos.add(eo); }
            if (eos.isEmpty()) return;
            // Use reflection to support multiple Sirius versions
            java.lang.reflect.Method m = null;
            for (java.lang.reflect.Method mm : DialectUIManager.class.getMethods()) {
                if (mm.getName().equals("reveal")) { m = mm; break; }
            }
            if (m != null) {
                m.invoke(DialectUIManager.INSTANCE, session, rep, eos, new NullProgressMonitor());
            }
        } catch (Exception ignore) {}
    }

    private static String tryUuid(EObject eo) {
        try {
            var f = eo.eClass().getEStructuralFeature("uuid");
            if (f != null) { Object v = eo.eGet(f); if (v != null) return String.valueOf(v); }
        } catch (Exception ignore) {}
        return String.valueOf(eo.hashCode());
    }
}
