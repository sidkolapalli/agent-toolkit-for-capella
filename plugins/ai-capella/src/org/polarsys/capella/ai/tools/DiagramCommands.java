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
import org.eclipse.sirius.viewpoint.RGBValues;
import org.polarsys.capella.ai.telemetry.Telemetry;

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
        String layerHint = null;
        if (strat.matches("^(oa|sa|la|pa|lab)_(flow|radial)$")) {
            int idx = strat.indexOf('_');
            layerHint = strat.substring(0, idx).toUpperCase();
            strat = strat.substring(idx + 1);
        }
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
                // Telemetry pre-metrics
                String diagramName = null;
                try {
                    if (rep instanceof DDiagram) {
                        var dd = (DDiagram) rep;
                        diagramName = dd.getDescription() != null ? dd.getDescription().getName() : "diagram";
                    }
                } catch (Throwable ignore) {}
                int overlapsPre = countOverlaps(bounds);
                int crossingsPre = estimateCrossings(nodes);
                long t0 = System.nanoTime();
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
                    case "flow": {
                        // Left-to-right flow with per-diagram spacing and a simple crossing-minimization heuristic
                        int n = bounds.size();
                        int rows = (int) Math.ceil(Math.sqrt(n));
                        int cols = (int) Math.ceil((double) n / Math.max(1, rows));
                        int[] spacing = (layerHint != null) ? computeSpacing(rep, layerHint) : computeSpacing(rep);
                        int cellW = spacing[0];
                        int cellH = spacing[1];
                        // Compute a simple connectivity score and reorder to reduce crossings
                        java.util.Map<Bounds, Integer> score = computeConnectivityScores(rep, nodes);
                        bounds.sort((a,b) -> Integer.compare(score.getOrDefault(b, 0), score.getOrDefault(a, 0)));
                        int startX = minX;
                        int startY = minY;
                        int i = 0;
                        for (Bounds b : bounds) {
                            int r = i / cols;
                            int c = i % cols;
                            b.setX(startX + c * cellW);
                            b.setY(startY + r * cellH);
                            if (b.getWidth() <= 0) b.setWidth(Math.max(120, cellW - 40));
                            if (b.getHeight() <= 0) b.setHeight(Math.max(80, cellH - 40));
                            i++;
                        }
                        break;
                    }
                    case "radial": {
                        // Arrange in a circle around the current center
                        int n = bounds.size();
                        if (n > 0) {
                            double cx = (minX + maxR) / 2.0;
                            double cy = (minY + maxB) / 2.0;
                            // Radius scaled with area and count
                            double spanX = Math.max(200.0, (maxR - minX));
                            double spanY = Math.max(150.0, (maxB - minY));
                            double radius = Math.max(spanX, spanY) * 0.6 + n * 6.0;
                            for (int i = 0; i < n; i++) {
                                Bounds b = bounds.get(i);
                                double theta = (2.0 * Math.PI * i) / n;
                                int w = Math.max(80, b.getWidth());
                                int h = Math.max(50, b.getHeight());
                                int x = (int) Math.round(cx + radius * Math.cos(theta) - w / 2.0);
                                int y = (int) Math.round(cy + radius * Math.sin(theta) - h / 2.0);
                                b.setX(x);
                                b.setY(y);
                                if (b.getWidth() <= 0) b.setWidth(w);
                                if (b.getHeight() <= 0) b.setHeight(h);
                            }
                        }
                        break;
                    }
                    case "layered": {
                        // Sugiyama-style layered layout (left-to-right) with simple barycenter ordering
                        int[] spacing = (layerHint != null) ? computeSpacing(rep, layerHint) : computeSpacing(rep);
                        int cellW = spacing[0];
                        int cellH = spacing[1];
                        java.util.Map<Node, java.util.Set<Node>> adj = computeAdjacency(nodes);
                        // Map Bounds<->Node for ordering and placement
                        java.util.Map<Node, Bounds> nb = new java.util.HashMap<>();
                        for (Node nNode : nodes) {
                            LayoutConstraint lc = nNode.getLayoutConstraint();
                            if (lc instanceof Bounds) nb.put(nNode, (Bounds) lc);
                        }
                        // Seed roots: nodes with smallest degree (or 0), then highest degree for remaining components
                        java.util.List<Node> all = new java.util.ArrayList<>(nodes);
                        all.sort((a,b) -> Integer.compare(adj.getOrDefault(a, java.util.Set.of()).size(), adj.getOrDefault(b, java.util.Set.of()).size()));
                        java.util.Map<Node, Integer> level = new java.util.HashMap<>();
                        java.util.List<java.util.List<Node>> layers = new java.util.ArrayList<>();
                        java.util.Set<Node> visited = new java.util.HashSet<>();
                        java.util.function.Consumer<Node> bfsFrom = start -> {
                            java.util.ArrayDeque<Node> q = new java.util.ArrayDeque<>();
                            level.put(start, 0); visited.add(start); q.add(start);
                            while (!q.isEmpty()) {
                                Node u = q.removeFirst();
                                int lu = level.get(u);
                                while (layers.size() <= lu) layers.add(new java.util.ArrayList<>());
                                layers.get(lu).add(u);
                                for (Node v : adj.getOrDefault(u, java.util.Set.of())) {
                                    if (!visited.contains(v)) { visited.add(v); level.put(v, lu + 1); q.addLast(v); }
                                }
                            }
                        };
                        for (Node r : all) if (!visited.contains(r)) bfsFrom.accept(r);
                        // Barycenter ordering per layer based on previous layer positions
                        java.util.Map<Node, Integer> prevOrder = new java.util.HashMap<>();
                        for (int li = 0; li < layers.size(); li++) {
                            java.util.List<Node> L = layers.get(li);
                            if (li == 0) {
                                // Order first layer by degree desc
                                L.sort((a,b) -> Integer.compare(adj.getOrDefault(b, java.util.Set.of()).size(), adj.getOrDefault(a, java.util.Set.of()).size()));
                                for (int i=0;i<L.size();i++) prevOrder.put(L.get(i), i);
                            } else {
                                java.util.Map<Node, Double> bary = new java.util.HashMap<>();
                                for (Node u : L) {
                                    java.util.List<Node> preds = new java.util.ArrayList<>(adj.getOrDefault(u, java.util.Set.of()));
                                    // only consider neighbors from previous layer for ordering
                                    preds.removeIf(p -> level.getOrDefault(p, -1) != li - 1);
                                    if (preds.isEmpty()) { bary.put(u, Double.POSITIVE_INFINITY); continue; }
                                    double sum = 0; int cnt = 0;
                                    for (Node p : preds) { Integer po = prevOrder.get(p); if (po != null) { sum += po; cnt++; } }
                                    bary.put(u, cnt == 0 ? Double.POSITIVE_INFINITY : (sum / cnt));
                                }
                                L.sort((a,b) -> Double.compare(bary.getOrDefault(a, Double.POSITIVE_INFINITY), bary.getOrDefault(b, Double.POSITIVE_INFINITY)));
                                for (int i=0;i<L.size();i++) prevOrder.put(L.get(i), i);
                            }
                        }
                        // Place nodes: x by layer, y by index within layer
                        int startX = minX;
                        int startY = minY;
                        for (int li = 0; li < layers.size(); li++) {
                            java.util.List<Node> L = layers.get(li);
                            for (int i = 0; i < L.size(); i++) {
                                Bounds b = nb.get(L.get(i));
                                if (b == null) continue;
                                b.setX(startX + li * cellW);
                                b.setY(startY + i * cellH);
                                if (b.getWidth() <= 0) b.setWidth(Math.max(120, cellW - 40));
                                if (b.getHeight() <= 0) b.setHeight(Math.max(80, cellH - 40));
                            }
                        }
                        break;
                    }
                    case "auto":
                    default:
                        arrange(rep);
                        break;
                }
                try { DialectManager.INSTANCE.refresh(rep, new NullProgressMonitor()); } catch (Exception ignore) {}
                // Telemetry post-metrics
                int overlapsPost = countOverlaps(bounds);
                int crossingsPost = estimateCrossings(nodes);
                long durMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
                String stratLabel = (layerHint != null && !layerHint.isBlank()) ? (layerHint.toLowerCase() + "_" + strat) : strat;
                Telemetry.recordLayout(diagramName == null ? "diagram" : diagramName, stratLabel, bounds.size(), overlapsPre, overlapsPost, crossingsPre, crossingsPost, durMs);
                return true;
            });
        } catch (Exception e) {
            LOG.warning("arrangeSelection failed: " + e.getMessage());
            return false;
        }
    }

    private static int[] computeSpacing(DRepresentation rep) {
        return computeSpacing(rep, null);
    }

    private static int[] computeSpacing(DRepresentation rep, String layerHint) {
        int cellW = 200, cellH = 140;
        if (layerHint != null) {
            String lh = layerHint.toUpperCase();
            if ("LA".equals(lh)) { cellW = 240; cellH = 160; }
            else if ("SA".equals(lh)) { cellW = 240; cellH = 170; }
            else if ("PA".equals(lh)) { cellW = 260; cellH = 180; }
            else if ("OA".equals(lh)) { cellW = 220; cellH = 150; }
            else if ("LAB".equals(lh)) { cellW = 260; cellH = 180; }
        }
        try {
            if (rep instanceof DDiagram) {
                org.eclipse.sirius.diagram.DDiagram d = (org.eclipse.sirius.diagram.DDiagram) rep;
                String name = null;
                try { name = d.getDescription() != null ? d.getDescription().getName() : null; } catch (Throwable ignore) {}
                String n = name != null ? name.toLowerCase() : "";
                if (n.contains("data flow") || n.contains("flow")) { cellW = 240; cellH = 160; }
                else if (n.contains("function breakdown") || n.contains("fbd")) { cellW = 220; cellH = 170; }
                else if (n.contains("logical architecture blank") || n.contains("lab")) { cellW = 260; cellH = 180; }
                else if (n.contains("system architecture blank") || n.contains("sab")) { cellW = 280; cellH = 190; }
                else if (n.contains("physical architecture blank") || n.contains("pab")) { cellW = 300; cellH = 200; }
            }
        } catch (Throwable ignore) {}
        return new int[] {cellW, cellH};
    }

    private static java.util.Map<Bounds, Integer> computeConnectivityScores(DRepresentation rep, java.util.List<Node> nodes) {
        java.util.Map<Bounds, Integer> scores = new java.util.HashMap<>();
        java.util.List<Object[]> pairs = new java.util.ArrayList<>();
        // Map each node to its semantic EObject (if available)
        java.util.Map<Node, org.eclipse.emf.ecore.EObject> sem = new java.util.HashMap<>();
        for (Node n : nodes) {
            org.eclipse.emf.ecore.EObject target = toSemantic(n);
            if (target != null) {
                sem.put(n, target);
            }
        }
        java.util.Set<org.eclipse.emf.ecore.EObject> all = new java.util.HashSet<>(sem.values());
        // For each element, count references to elements within the same set
        for (Node n : nodes) {
            int sc = 0;
            org.eclipse.emf.ecore.EObject t = sem.get(n);
            if (t != null) {
                try {
                    for (org.eclipse.emf.ecore.EStructuralFeature f : t.eClass().getEAllStructuralFeatures()) {
                        if (f instanceof org.eclipse.emf.ecore.EReference) {
                            org.eclipse.emf.ecore.EReference r = (org.eclipse.emf.ecore.EReference) f;
                            if (r.isContainment()) continue;
                            Object v = t.eGet(r);
                            if (v instanceof org.eclipse.emf.ecore.EObject e2) {
                                if (all.contains(e2)) sc++;
                            } else if (v instanceof java.util.Collection<?>) {
                                for (Object o : (java.util.Collection<?>) v) {
                                    if (o instanceof org.eclipse.emf.ecore.EObject e3) {
                                        if (all.contains(e3)) sc++;
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable ignore) {}
            }
            // Save by Bounds (from node)
            Bounds b = (Bounds) n.getLayoutConstraint();
            if (b != null) scores.put(b, sc);
        }
        return scores;
    }

    /** Build undirected adjacency among nodes based on semantic references within the selection. */
    private static java.util.Map<Node, java.util.Set<Node>> computeAdjacency(java.util.List<Node> nodes) {
        java.util.Map<Node, java.util.Set<Node>> adj = new java.util.HashMap<>();
        java.util.Map<Node, org.eclipse.emf.ecore.EObject> sem = new java.util.HashMap<>();
        java.util.Map<org.eclipse.emf.ecore.EObject, Node> bySem = new java.util.HashMap<>();
        for (Node n : nodes) {
            org.eclipse.emf.ecore.EObject t = toSemantic(n);
            if (t != null) { sem.put(n, t); bySem.put(t, n); }
            adj.put(n, new java.util.HashSet<>());
        }
        java.util.Set<org.eclipse.emf.ecore.EObject> all = new java.util.HashSet<>(sem.values());
        for (Node n : nodes) {
            org.eclipse.emf.ecore.EObject t = sem.get(n);
            if (t == null) continue;
            try {
                for (org.eclipse.emf.ecore.EStructuralFeature f : t.eClass().getEAllStructuralFeatures()) {
                    if (f instanceof org.eclipse.emf.ecore.EReference) {
                        org.eclipse.emf.ecore.EReference r = (org.eclipse.emf.ecore.EReference) f;
                        if (r.isContainment()) continue;
                        Object v = t.eGet(r);
                        if (v instanceof org.eclipse.emf.ecore.EObject e2) {
                            if (all.contains(e2)) {
                                Node m = bySem.get(e2);
                                if (m != null) { adj.get(n).add(m); adj.get(m).add(n); }
                            }
                        } else if (v instanceof java.util.Collection<?>) {
                            for (Object o : (java.util.Collection<?>) v) {
                                if (o instanceof org.eclipse.emf.ecore.EObject e3 && all.contains(e3)) {
                                    Node m = bySem.get(e3);
                                    if (m != null) { adj.get(n).add(m); adj.get(m).add(n); }
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignore) {}
        }
        return adj;
    }

    private static org.eclipse.emf.ecore.EObject toSemantic(Node node) {
        try {
            View v = node;
            org.eclipse.emf.ecore.EObject el = v.getElement();
            if (el != null && el.getClass().getName().contains("DDiagramElement")) {
                try {
                    java.lang.reflect.Method m = el.getClass().getMethod("getTarget");
                    Object t = m.invoke(el);
                    if (t instanceof org.eclipse.emf.ecore.EObject) return (org.eclipse.emf.ecore.EObject) t;
                } catch (Throwable ignore) {}
            }
        } catch (Throwable ignore) {}
        return null;
    }

    /** Count rectangle overlaps among given bounds. */
    private static int countOverlaps(java.util.List<Bounds> bounds) {
        int n = bounds.size();
        if (n > 600) return 0; // skip heavy computation for very large selections
        int overlaps = 0;
        for (int i = 0; i < n; i++) {
            Bounds a = bounds.get(i);
            int ax1 = a.getX();
            int ay1 = a.getY();
            int aw = Math.max(1, a.getWidth());
            int ah = Math.max(1, a.getHeight());
            int ax2 = ax1 + aw;
            int ay2 = ay1 + ah;
            for (int j = i + 1; j < n; j++) {
                Bounds b = bounds.get(j);
                int bx1 = b.getX();
                int by1 = b.getY();
                int bw = Math.max(1, b.getWidth());
                int bh = Math.max(1, b.getHeight());
                int bx2 = bx1 + bw;
                int by2 = by1 + bh;
                if (ax1 < bx2 && ax2 > bx1 && ay1 < by2 && ay2 > by1) {
                    overlaps++;
                }
            }
        }
        return overlaps;
    }

    /** Estimate edge crossings using straight-line segments between node centers (undirected). */
    private static int estimateCrossings(java.util.List<Node> nodes) {
        java.util.Map<Node, java.util.Set<Node>> adj = computeAdjacency(nodes);
        java.util.List<Node[]> edges = new java.util.ArrayList<>();
        for (var e : adj.entrySet()) {
            Node u = e.getKey();
            for (Node v : e.getValue()) {
                if (System.identityHashCode(u) < System.identityHashCode(v)) {
                    edges.add(new Node[]{u, v});
                }
            }
        }
        int m = edges.size();
        if (m > 5000) return 0; // avoid heavy O(m^2)
        // centers
        java.util.Map<Node, double[]> center = new java.util.HashMap<>();
        for (Node n : nodes) {
            Bounds b = (Bounds) n.getLayoutConstraint();
            if (b == null) continue;
            double cx = b.getX() + Math.max(1, b.getWidth()) / 2.0;
            double cy = b.getY() + Math.max(1, b.getHeight()) / 2.0;
            center.put(n, new double[]{cx, cy});
        }
        int crossings = 0;
        for (int i = 0; i < m; i++) {
            Node[] e1 = edges.get(i);
            for (int j = i + 1; j < m; j++) {
                Node[] e2 = edges.get(j);
                if (e1[0] == e2[0] || e1[0] == e2[1] || e1[1] == e2[0] || e1[1] == e2[1]) continue;
                double[] a1 = center.get(e1[0]);
                double[] a2 = center.get(e1[1]);
                double[] b1 = center.get(e2[0]);
                double[] b2 = center.get(e2[1]);
                if (a1 == null || a2 == null || b1 == null || b2 == null) continue;
                if (segmentsIntersect(a1[0], a1[1], a2[0], a2[1], b1[0], b1[1], b2[0], b2[1])) crossings++;
            }
        }
        return crossings;
    }

    private static double orient(double ax, double ay, double bx, double by, double cx, double cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }

    private static boolean segmentsIntersect(double ax, double ay, double bx, double by,
                                             double cx, double cy, double dx, double dy) {
        double o1 = orient(ax, ay, bx, by, cx, cy);
        double o2 = orient(ax, ay, bx, by, dx, dy);
        double o3 = orient(cx, cy, dx, dy, ax, ay);
        double o4 = orient(cx, cy, dx, dy, bx, by);
        if (((o1 > 0 && o2 < 0) || (o1 < 0 && o2 > 0)) && ((o3 > 0 && o4 < 0) || (o3 < 0 && o4 > 0))) {
            return true;
        }
        return false;
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

    /** Expose semantic lookup by id/uuid/fragment using the active session's resource set. */
    public static EObject findSemanticById(Session session, String id) {
        if (session == null || id == null || id.isBlank()) return null;
        try {
            var domain = session.getTransactionalEditingDomain();
            if (domain == null) return null;
            var rs = domain.getResourceSet();
            if (rs == null) return null;
            return findById(rs, id);
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

    /** Style a view element (colors) if supported by the underlying style. */
    public static boolean styleViewElement(Session session, DRepresentation rep, EObject semantic, Integer fg, Integer bg, Integer bd) {
        if (session == null || rep == null || semantic == null) return false;
        if (!(rep instanceof DDiagram)) return false;
        try {
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
            if (target == null) return false;
            final org.eclipse.sirius.viewpoint.Style[] styleHolder = new org.eclipse.sirius.viewpoint.Style[1];
            try {
                java.lang.reflect.Method gs = target.getClass().getMethod("getStyle");
                Object st = gs.invoke(target);
                if (st instanceof org.eclipse.sirius.viewpoint.Style) styleHolder[0] = (org.eclipse.sirius.viewpoint.Style) st;
            } catch (Throwable ignore) {}
            if (styleHolder[0] == null) return false;
            RGBValues fgV = (fg != null && fg >= 0) ? toRgb(fg) : null;
            RGBValues bgV = (bg != null && bg >= 0) ? toRgb(bg) : null;
            RGBValues bdV = (bd != null && bd >= 0) ? toRgb(bd) : null;
            final org.eclipse.sirius.diagram.DDiagramElement targetRef = target;
            return SessionSafeguards.runWrite(session, "Style view element", () -> {
                boolean changed = false;
                try {
                    if (fgV != null) {
                        var m = styleHolder[0].getClass().getMethod("setLabelColor", RGBValues.class);
                        m.invoke(styleHolder[0], fgV);
                        changed = true;
                    }
                } catch (Throwable ignore) {}
                try {
                    if (bgV != null) {
                        var m = styleHolder[0].getClass().getMethod("setColor", RGBValues.class);
                        m.invoke(styleHolder[0], bgV);
                        changed = true;
                    }
                } catch (Throwable ignore) {}
                try {
                    if (bdV != null) {
                        var m = styleHolder[0].getClass().getMethod("setBorderColor", RGBValues.class);
                        m.invoke(styleHolder[0], bdV);
                        changed = true;
                    }
                } catch (Throwable ignore) {}
                // Best-effort: show/hide icon if supported by style
                try {
                    java.lang.reflect.Method m = styleHolder[0].getClass().getMethod("setShowIcon", boolean.class);
                    // If method exists, default to true unless explicitly set false by caller
                    Boolean showIcon = null;
                    try {
                        // Try to retrieve a cached value from a thread-local if present; otherwise skip (set below if provided)
                        showIcon = (Boolean) org.polarsys.capella.ai.tools.DiagramCommands.ThreadLocalStyling.get("showIcon");
                    } catch (Throwable ignore) {}
                    if (showIcon != null) {
                        m.invoke(styleHolder[0], showIcon.booleanValue());
                        changed = true;
                    }
                } catch (Throwable ignore) {}
                // Best-effort: set label text on representation element if supported
                try {
                    String labelText = null;
                    try { labelText = (String) org.polarsys.capella.ai.tools.DiagramCommands.ThreadLocalStyling.get("labelText"); } catch (Throwable ignore) {}
                    if (labelText != null) {
                        try {
                            var m = targetRef.getClass().getMethod("setName", String.class);
                            m.invoke(targetRef, labelText);
                            changed = true;
                        } catch (Throwable ig2) {}
                    }
                } catch (Throwable ignore) {}
                if (changed) {
                    try { DialectManager.INSTANCE.refresh(rep, new NullProgressMonitor()); } catch (Exception ignore) {}
                }
                return changed;
            });
        } catch (Exception e) {
            LOG.warning("styleViewElement failed: " + e.getMessage());
            return false;
        }
    }

    /** Internal helper to pass additional styling flags without API changes. */
    public static final class ThreadLocalStyling {
        private static final ThreadLocal<java.util.Map<String, Object>> TL = ThreadLocal.withInitial(java.util.HashMap::new);
        public static void put(String k, Object v) { TL.get().put(k, v); }
        public static Object get(String k) { return TL.get().get(k); }
        public static void clear() { TL.get().clear(); }
    }

    private static RGBValues toRgb(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return RGBValues.create(r, g, b);
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
