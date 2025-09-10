package org.polarsys.capella.ai.tools;

import java.util.logging.Logger;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.transaction.RecordingCommand;
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

/** In‑process diagram helpers using Sirius UI APIs (arrange/layout visible). */
public final class DiagramCommands {
    private static final Logger LOG = Logger.getLogger(DiagramCommands.class.getName());

    private DiagramCommands() {}

    public static volatile DRepresentation lastOpened;

    /** Create and open a diagram for the given target using a description name. */
    public static DRepresentation createAndOpenDiagram(EObject target, String descriptionName, String diagramName) {
        Session session = SessionBridge.getActiveSession();
        if (session == null) return null;
        TransactionalEditingDomain domain = session.getTransactionalEditingDomain();
        if (domain == null) return null;

        final DRepresentation[] rep = new DRepresentation[1];
        domain.getCommandStack().execute(new RecordingCommand(domain) {
            @Override
            protected void doExecute() {
                RepresentationDescription desc = findDiagramDescription(session, descriptionName);
                if (desc == null) return;
                DRepresentation created = DialectManager.INSTANCE.createRepresentation(
                    diagramName, target, desc, session, new NullProgressMonitor());
                rep[0] = created;
            }
        });
        if (rep[0] != null) {
            try {
                DialectUIManager.INSTANCE.openEditor(session, rep[0], new NullProgressMonitor());
                lastOpened = rep[0];
            } catch (Exception e) {
                LOG.warning("Failed to open diagram editor: " + e.getMessage());
            }
        }
        return rep[0];
    }

    /** Arrange current diagram using Sirius UI (GMF Arrange). */
    public static void arrange(DRepresentation rep) {
        try {
            Session session = SessionBridge.getActiveSession();
            if (session == null || rep == null) return;
            DialectUIManager.INSTANCE.refreshEditor(session, rep, new NullProgressMonitor());
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
            DialectUIManager.INSTANCE.refreshEditor(session, rep, new NullProgressMonitor());
        } catch (Exception e) {
            LOG.warning("addElementsByUuid failed: " + e.getMessage());
        }
        return resolved;
    }

    /** Attempt explicit Sirius node creation via internal UI command factories; fallback to reveal. */
    public static boolean createNode(Session session, DRepresentation rep, EObject semantic) {
        if (session == null || rep == null || semantic == null) return false;
        try {
            // Reflection path to avoid compile-time dependency on internal APIs
            Class<?> cFactory = Class.forName("org.eclipse.sirius.diagram.ui.tools.api.command.CreateNodeCommand");
            var domain = session.getTransactionalEditingDomain();
            if (!(rep instanceof org.eclipse.sirius.diagram.DDiagram) || domain == null) return false;
            org.eclipse.sirius.diagram.DDiagram dd = (org.eclipse.sirius.diagram.DDiagram) rep;
            Object cmd = null;
            // Try (TransactionalEditingDomain, DDiagram, EObject, int x, int y, int w, int h)
            try {
                cmd = cFactory.getConstructor(org.eclipse.emf.transaction.TransactionalEditingDomain.class,
                        org.eclipse.sirius.diagram.DDiagram.class, org.eclipse.emf.ecore.EObject.class,
                        int.class, int.class, int.class, int.class)
                        .newInstance(domain, dd, semantic, 100, 100, 0, 0);
            } catch (NoSuchMethodException ignore) {}
            if (cmd != null) {
                domain.getCommandStack().execute((org.eclipse.emf.common.command.Command) cmd);
                DialectUIManager.INSTANCE.refreshEditor(session, rep, new NullProgressMonitor());
                return true;
            }
        } catch (ClassNotFoundException notFound) {
            // Fallback to reveal
        } catch (Exception e) {
            LOG.warning("createNode failed: " + e.getMessage());
        }
        // Fallback: reveal
        try {
            addElementsByUuid(session, rep, java.util.List.of(tryUuid(semantic)));
            return true;
        } catch (Exception ignore) {}
        return false;
    }

    /** Attempt explicit Sirius edge creation; fallback to refresh after semantic link creation. */
    public static boolean createEdge(Session session, DRepresentation rep, EObject source, EObject target) {
        if (session == null || rep == null || source == null || target == null) return false;
        try {
            Class<?> cFactory = Class.forName("org.eclipse.sirius.diagram.ui.tools.api.command.CreateEdgeCommand");
            var domain = session.getTransactionalEditingDomain();
            if (!(rep instanceof org.eclipse.sirius.diagram.DDiagram) || domain == null) return false;
            org.eclipse.sirius.diagram.DDiagram dd = (org.eclipse.sirius.diagram.DDiagram) rep;
            Object cmd = null;
            try {
                cmd = cFactory.getConstructor(org.eclipse.emf.transaction.TransactionalEditingDomain.class,
                        org.eclipse.sirius.diagram.DDiagram.class, org.eclipse.emf.ecore.EObject.class,
                        org.eclipse.emf.ecore.EObject.class)
                        .newInstance(domain, dd, source, target);
            } catch (NoSuchMethodException ignore) {}
            if (cmd != null) {
                domain.getCommandStack().execute((org.eclipse.emf.common.command.Command) cmd);
                DialectUIManager.INSTANCE.refreshEditor(session, rep, new NullProgressMonitor());
                return true;
            }
        } catch (ClassNotFoundException notFound) {
            // Fallback
        } catch (Exception e) {
            LOG.warning("createEdge failed: " + e.getMessage());
        }
        try {
            DialectUIManager.INSTANCE.refreshEditor(session, rep, new NullProgressMonitor());
            return true;
        } catch (Exception ignore) {}
        return false;
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
            // Reflection: DialectUIManager.INSTANCE.reveal(session, rep, collection, monitor)
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
