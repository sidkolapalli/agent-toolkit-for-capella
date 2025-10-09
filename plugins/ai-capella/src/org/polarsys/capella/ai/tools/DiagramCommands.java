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
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.gmf.runtime.diagram.ui.editparts.IGraphicalEditPart;
import org.eclipse.gmf.runtime.notation.Bounds;
import org.eclipse.gmf.runtime.notation.LayoutConstraint;
import org.eclipse.gmf.runtime.notation.Node;
import org.eclipse.gmf.runtime.notation.NotationFactory;
import org.eclipse.gmf.runtime.notation.View;

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

    /**
     * Arrange/align the current selection (or all nodes if selection unavailable) using a simple strategy.
     * Strategies: left, right, top, bottom, hcenter, vcenter, hspace, vspace, grid, auto.
     */
    public static boolean arrangeSelection(Session session, DRepresentation rep, String strategy) {
        if (session == null || rep == null) return false;
        String strat = (strategy == null || strategy.isBlank()) ? "auto" : strategy.toLowerCase();
        try {
            if (!(rep instanceof DDiagram)) {
                arrange(rep);
                return true;
            }
            // Collect candidate edit parts from selection if possible
            IWorkbenchWindow win = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            org.eclipse.gef.EditPartViewer viewer = null;
            IEditorPart part = null;
            try {
                if (win != null && win.getActivePage() != null) {
                    part = win.getActivePage().getActiveEditor();
                    if (part != null) {
                        Class<?> edClass = Class.forName("org.eclipse.sirius.diagram.ui.tools.api.editor.DDiagramEditor");
                        if (edClass.isInstance(part)) {
                            var m = edClass.getMethod("getDiagramGraphicalViewer");
                            Object v = m.invoke(part);
                            if (v instanceof org.eclipse.gef.EditPartViewer) viewer = (org.eclipse.gef.EditPartViewer) v;
                        }
                    }
                }
            } catch (Throwable ignore) {}

            java.util.List<Node> nodes = new java.util.ArrayList<>();
            // Prefer selection if available
            if (viewer != null) {
                var sel = viewer.getSelection();
                if (sel instanceof org.eclipse.jface.viewers.IStructuredSelection) {
                    for (Object o : ((org.eclipse.jface.viewers.IStructuredSelection) sel).toArray()) {
                        if (o instanceof IGraphicalEditPart) {
                            Object model = ((IGraphicalEditPart) o).getModel();
                            if (model instanceof Node) nodes.add((Node) model);
                        }
                    }
                }
            }
            // Fallback to all nodes in diagram
            if (nodes.isEmpty()) {
                DDiagram diagram = (DDiagram) rep;
                var it = diagram.eAllContents();
                while (it.hasNext()) {
                    Object o = it.next();
                    if (o instanceof org.eclipse.sirius.diagram.DNode) {
                        // Try to reach notation Node via editpart registry if possible
                        // Fallback: skip if we can't resolve notation
                        if (viewer != null) {
                            for (Object epObj : viewer.getEditPartRegistry().values()) {
                                if (epObj instanceof IGraphicalEditPart) {
                                    IGraphicalEditPart ep = (IGraphicalEditPart) epObj;
                                    try {
                                        if (o == ep.resolveSemanticElement() && ep.getModel() instanceof Node) {
                                            nodes.add((Node) ep.getModel());
                                            break;
                                        }
                                    } catch (Exception ignore) {}
                                }
                            }
                        }
                    }
                }
            }
            if (nodes.isEmpty()) {
                arrange(rep);
                return true;
            }
            // Compute bounds and apply layout inside write tx
            return SessionSafeguards.runWrite(session, "Arrange selection", () -> {
                java.util.List<Bounds> bounds = new java.util.ArrayList<>(nodes.size());
                for (Node n : nodes) {
                    LayoutConstraint lc = n.getLayoutConstraint();
                    if (!(lc instanceof Bounds)) {
                        n.setLayoutConstraint(NotationFactory.eINSTANCE.createBounds());
                        lc = n.getLayoutConstraint();
                    }
                    bounds.add((Bounds) lc);
                }
                if (bounds.size() < 2) return true; // nothing to do
                int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
                int maxR = Integer.MIN_VALUE, maxB = Integer.MIN_VALUE;
                for (Bounds b : bounds) {
                    minX = Math.min(minX, b.getX());
                    minY = Math.min(minY, b.getY());
                    maxR = Math.max(maxR, b.getX() + Math.max(0, b.getWidth()));
                    maxB = Math.max(maxB, b.getY() + Math.max(0, b.getHeight()));
                }
                switch (strat) {
                    case "left":
                        for (Bounds b : bounds) b.setX(minX);
                        break;
                    case "right":
                        for (Bounds b : bounds) b.setX(maxR - Math.max(0, b.getWidth()));
                        break;
                    case "top":
                        for (Bounds b : bounds) b.setY(minY);
                        break;
                    case "bottom":
                        for (Bounds b : bounds) b.setY(maxB - Math.max(0, b.getHeight()));
                        break;
                    case "hcenter": {
                        int mid = (minX + maxR) / 2;
                        for (Bounds b : bounds) b.setX(mid - Math.max(0, b.getWidth()) / 2);
                        break;
                    }
                    case "vcenter": {
                        int mid = (minY + maxB) / 2;
                        for (Bounds b : bounds) b.setY(mid - Math.max(0, b.getHeight()) / 2);
                        break;
                    }
                    case "hspace": {
                        // Sort by x and distribute across minX..maxR using equal gaps
                        bounds.sort(java.util.Comparator.comparingInt(Bounds::getX));
                        int n = bounds.size();
                        int totalWidth = bounds.stream().mapToInt(b -> Math.max(0, b.getWidth())).sum();
                        int span = Math.max(0, maxR - minX - totalWidth);
                        int gap = (n > 1) ? (span / (n - 1)) : 0;
                        int cursor = minX;
                        for (Bounds b : bounds) { b.setX(cursor); cursor += Math.max(0, b.getWidth()) + gap; }
                        break;
                    }
                    case "vspace": {
                        bounds.sort(java.util.Comparator.comparingInt(Bounds::getY));
                        int n = bounds.size();
                        int totalHeight = bounds.stream().mapToInt(b -> Math.max(0, b.getHeight())).sum();
                        int span = Math.max(0, maxB - minY - totalHeight);
                        int gap = (n > 1) ? (span / (n - 1)) : 0;
                        int cursor = minY;
                        for (Bounds b : bounds) { b.setY(cursor); cursor += Math.max(0, b.getHeight()) + gap; }
                        break;
                    }
                    case "grid": {
                        int n = bounds.size();
                        int cols = (int) Math.ceil(Math.sqrt(n));
                        int rows = (int) Math.ceil((double) n / cols);
                        int cellW = Math.max(120, (maxR - minX) / Math.max(1, cols));
                        int cellH = Math.max(80, (maxB - minY) / Math.max(1, rows));
                        int i = 0;
                        for (Bounds b : bounds) {
                            int c = i % cols; int r = i / cols;
                            b.setX(minX + c * cellW);
                            b.setY(minY + r * cellH);
                            i++;
                        }
                        break;
                    }
                    case "auto":
                    default:
                        arrange(rep);
                        break;
                }
                try { DialectManager.INSTANCE.refresh(rep, new NullProgressMonitor()); } catch (Exception ignore) {}
                return true;
            });
        } catch (Exception e) {
            LOG.warning("arrangeSelection failed: " + e.getMessage());
            return false;
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
        if (session == null || rep == null || semantic == null) return false;
        try {
            if (!(rep instanceof DDiagram)) {
                DialectManager.INSTANCE.refresh(rep, new NullProgressMonitor());
                return false;
            }
            return SessionSafeguards.runWrite(session, "Remove view element", () -> {
                DDiagram diagram = (DDiagram) rep;
                java.util.List<org.eclipse.sirius.diagram.DDiagramElement> toRemove = new java.util.ArrayList<>();
                var it = diagram.eAllContents();
                while (it.hasNext()) {
                    Object obj = it.next();
                    if (obj instanceof org.eclipse.sirius.diagram.DDiagramElement) {
                        org.eclipse.sirius.diagram.DDiagramElement el = (org.eclipse.sirius.diagram.DDiagramElement) obj;
                        if (semantic.equals(el.getTarget())) {
                            toRemove.add(el);
                        }
                    }
                }
                if (toRemove.isEmpty()) return false;
                for (org.eclipse.sirius.diagram.DDiagramElement el : toRemove) {
                    try { org.eclipse.emf.ecore.util.EcoreUtil.delete(el, true); } catch (Exception ignore) {}
                }
                try { DialectManager.INSTANCE.refresh(rep, new NullProgressMonitor()); } catch (Exception ignore) {}
                return true;
            });
        } catch (Exception e) {
            LOG.warning("removeViewElement failed: " + e.getMessage());
            return false;
        }
    }

    /** Best-effort: set bounds for a view element (requires active editor/GMF). Placeholder returns false for now. */
    public static boolean setViewBounds(Session session, DRepresentation rep, EObject semantic, int x, int y, int w, int h) {
        if (session == null || rep == null || semantic == null) return false;
        if (!(rep instanceof DDiagram)) return false;
        try {
            // Find the DDiagramElement that targets the semantic EObject
            DDiagram diagram = (DDiagram) rep;
            org.eclipse.sirius.diagram.DDiagramElement target = null;
            var it = diagram.eAllContents();
            while (it.hasNext()) {
                Object o = it.next();
                if (o instanceof org.eclipse.sirius.diagram.DDiagramElement) {
                    var el = (org.eclipse.sirius.diagram.DDiagramElement) o;
                    if (semantic.equals(el.getTarget())) { target = el; break; }
                }
            }
            if (target == null) {
                // Attempt reveal then search again
                addElementsByUuid(session, rep, java.util.List.of(tryUuid(semantic)));
                DialectManager.INSTANCE.refresh(rep, new NullProgressMonitor());
                it = diagram.eAllContents();
                while (it.hasNext()) {
                    Object o = it.next();
                    if (o instanceof org.eclipse.sirius.diagram.DDiagramElement) {
                        var el = (org.eclipse.sirius.diagram.DDiagramElement) o;
                        if (semantic.equals(el.getTarget())) { target = el; break; }
                    }
                }
                if (target == null) return false;
            }

            // Locate the active Sirius diagram editor and its edit part
            IWorkbenchWindow win = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (win == null) return false;
            IWorkbenchPage page = win.getActivePage();
            if (page == null) return false;
            IEditorPart part = page.getActiveEditor();
            if (part == null) return false;
            org.eclipse.gef.EditPartViewer viewer = null;
            try {
                // Try Sirius Diagram Editor API
                Class<?> edClass = Class.forName("org.eclipse.sirius.diagram.ui.tools.api.editor.DDiagramEditor");
                if (edClass.isInstance(part)) {
                    var m = edClass.getMethod("getDiagramGraphicalViewer");
                    Object v = m.invoke(part);
                    if (v instanceof org.eclipse.gef.EditPartViewer) viewer = (org.eclipse.gef.EditPartViewer) v;
                }
            } catch (Throwable ignore) {}
            if (viewer == null) return false;

            IGraphicalEditPart found = null;
            for (Object epObj : viewer.getEditPartRegistry().values()) {
                if (epObj instanceof IGraphicalEditPart) {
                    IGraphicalEditPart ep = (IGraphicalEditPart) epObj;
                    try {
                        Object semEl = ep.resolveSemanticElement();
                        if (semEl == target) { found = ep; break; }
                        Object model = ep.getModel();
                        if (model instanceof View) {
                            View view = (View) model;
                            if (target.equals(view.getElement())) { found = ep; break; }
                        }
                    } catch (Exception ignore) {}
                }
            }
            if (found == null) return false;

            // Mutate notation bounds inside write transaction
            final IGraphicalEditPart epFound = found;
            return SessionSafeguards.runWrite(session, "Set view bounds", () -> {
                Object model = epFound.getModel();
                if (!(model instanceof View)) return false;
                if (!(model instanceof Node)) return false; // only nodes have bounds
                Node node = (Node) model;
                LayoutConstraint lc = node.getLayoutConstraint();
                if (!(lc instanceof Bounds)) {
                    node.setLayoutConstraint(NotationFactory.eINSTANCE.createBounds());
                    lc = node.getLayoutConstraint();
                }
                Bounds b = (Bounds) lc;
                if (x >= 0) b.setX(x);
                if (y >= 0) b.setY(y);
                if (w > 0) b.setWidth(w);
                if (h > 0) b.setHeight(h);
                try { DialectManager.INSTANCE.refresh(rep, new NullProgressMonitor()); } catch (Exception ignore) {}
                return true;
            });
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

    /** Find a representation by name or UID. */
    public static DRepresentation findRepresentationByNameOrUid(Session session, String idOrName) {
        if (session == null || idOrName == null || idOrName.isBlank()) return null;
        try {
            java.util.Collection<DRepresentation> reps = org.eclipse.sirius.business.api.dialect.DialectManager.INSTANCE.getAllRepresentations(session);
            for (DRepresentation r : reps) {
                if (idOrName.equals(r.getUid())) return r;
            }
            for (DRepresentation r : reps) {
                try {
                    java.lang.reflect.Method getName = r.getClass().getMethod("getName");
                    Object n = getName.invoke(r);
                    if (idOrName.equals(String.valueOf(n))) return r;
                } catch (Throwable ignore) {}
            }
        } catch (Exception ignore) {}
        return null;
    }

    /** Rename a diagram representation. */
    public static boolean renameDiagram(Session session, DRepresentation rep, String newName) {
        if (session == null || rep == null || newName == null || newName.isBlank()) return false;
        try {
            return SessionSafeguards.runWrite(session, "Rename diagram", () -> {
                try {
                    java.lang.reflect.Method setName = rep.getClass().getMethod("setName", String.class);
                    setName.invoke(rep, newName);
                    DialectManager.INSTANCE.refresh(rep, new NullProgressMonitor());
                    return true;
                } catch (Throwable t) {
                    LOG.warning("renameDiagram failed: " + t.getMessage());
                    return false;
                }
            });
        } catch (Exception e) {
            LOG.warning("renameDiagram failed: " + e.getMessage());
            return false;
        }
    }

    /** Delete a diagram representation. */
    public static boolean deleteDiagram(Session session, DRepresentation rep) {
        if (session == null || rep == null) return false;
        try {
            return SessionSafeguards.runWrite(session, "Delete diagram", () -> {
                try {
                    // Prefer descriptor-based deletion for compatibility
                    Object desc = null;
                    try {
                        java.lang.reflect.Method m = DialectManager.class.getMethod("getRepresentationDescriptor", DRepresentation.class);
                        desc = m.invoke(DialectManager.INSTANCE, rep);
                    } catch (Throwable ignore) {}
                    if (desc != null) {
                        java.lang.reflect.Method del = DialectManager.class.getMethod("deleteRepresentation", desc.getClass(), Session.class);
                        del.invoke(DialectManager.INSTANCE, desc, session);
                        return true;
                    }
                    // Fallback no-op
                    return false;
                } catch (Throwable t) {
                    LOG.warning("deleteDiagram failed: " + t.getMessage());
                    return false;
                }
            });
        } catch (Exception e) {
            LOG.warning("deleteDiagram failed: " + e.getMessage());
            return false;
        }
    }

    /** Duplicate a diagram representation (best-effort). */
    public static boolean duplicateDiagram(Session session, DRepresentation rep, String newName) {
        if (session == null || rep == null || newName == null || newName.isBlank()) return false;
        try {
            // Best-effort: try to create a new representation with same description/target, then refresh.
            return SessionSafeguards.runWrite(session, "Duplicate diagram", () -> {
                try {
                    Object descriptor = null;
                    try {
                        java.lang.reflect.Method m = DialectManager.class.getMethod("getRepresentationDescriptor", DRepresentation.class);
                        descriptor = m.invoke(DialectManager.INSTANCE, rep);
                    } catch (Throwable ignore) {}
                    if (descriptor == null) return false;
                    EObject target = null;
                    RepresentationDescription desc = null;
                    try {
                        java.lang.reflect.Method getTarget = descriptor.getClass().getMethod("getTarget");
                        Object t = getTarget.invoke(descriptor);
                        if (t instanceof EObject) target = (EObject) t;
                    } catch (Throwable ignore) {}
                    try {
                        java.lang.reflect.Method getDesc = descriptor.getClass().getMethod("getDescription");
                        Object d = getDesc.invoke(descriptor);
                        if (d instanceof RepresentationDescription) desc = (RepresentationDescription) d;
                    } catch (Throwable ignore) {}
                    if (target == null || desc == null) return false;
                    DRepresentation copy = DialectManager.INSTANCE.createRepresentation(newName, target, desc, session, new NullProgressMonitor());
                    if (copy != null) {
                        DialectManager.INSTANCE.refresh(copy, new NullProgressMonitor());
                        return true;
                    }
                } catch (Throwable t) {
                    LOG.warning("duplicateDiagram failed: " + t.getMessage());
                }
                return false;
            });
        } catch (Exception e) {
            LOG.warning("duplicateDiagram failed: " + e.getMessage());
            return false;
        }
    }

    /** Reveal semantic elements in a diagram editor (reflection-based if needed). */
    public static boolean revealSemantics(Session session, DRepresentation rep, java.util.List<EObject> semantics) {
        if (session == null || rep == null || semantics == null || semantics.isEmpty()) return false;
        try {
            java.lang.reflect.Method m = null;
            for (java.lang.reflect.Method mm : DialectUIManager.class.getMethods()) {
                if (mm.getName().equals("reveal")) { m = mm; break; }
            }
            if (m != null) {
                m.invoke(DialectUIManager.INSTANCE, session, rep, semantics, new NullProgressMonitor());
                DialectManager.INSTANCE.refresh(rep, new NullProgressMonitor());
                return true;
            }
        } catch (Exception e) {
            LOG.warning("revealSemantics failed: " + e.getMessage());
        }
        return false;
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
