package org.polarsys.capella.ai.tools;

import java.util.logging.Logger;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.gmf.runtime.diagram.ui.editparts.IGraphicalEditPart;
import org.eclipse.gmf.runtime.notation.Bounds;
import org.eclipse.gmf.runtime.notation.LayoutConstraint;
import org.eclipse.gmf.runtime.notation.Node;
import org.eclipse.gmf.runtime.notation.NotationFactory;
import org.eclipse.gmf.runtime.notation.View;
import org.eclipse.sirius.business.api.dialect.DialectManager;
import org.eclipse.sirius.business.api.dialect.command.DeleteRepresentationCommand;
import org.eclipse.sirius.business.api.dialect.command.RenameRepresentationCommand;
import org.eclipse.sirius.business.api.query.DRepresentationQuery;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.common.tools.api.interpreter.IInterpreter;
import org.eclipse.sirius.diagram.DDiagram;
import org.eclipse.sirius.diagram.DDiagramElement;
import org.eclipse.sirius.diagram.DragAndDropTarget;
import org.eclipse.sirius.diagram.DNode;
import org.eclipse.sirius.diagram.DNodeContainer;
import org.eclipse.sirius.diagram.DSemanticDiagram;
import org.eclipse.sirius.diagram.DiagramFactory;
import org.eclipse.sirius.diagram.business.api.query.DiagramElementMappingQuery;
import org.eclipse.sirius.diagram.description.AbstractNodeMapping;
import org.eclipse.sirius.diagram.description.ContainerMapping;
import org.eclipse.sirius.diagram.description.DiagramDescription;
import org.eclipse.sirius.diagram.description.DiagramElementMapping;
import org.eclipse.sirius.diagram.description.NodeMapping;
import org.eclipse.sirius.ui.business.api.dialect.DialectUIManager;
import org.eclipse.sirius.tools.api.interpreter.InterpreterUtil;
import org.eclipse.sirius.viewpoint.DRepresentation;
import org.eclipse.sirius.viewpoint.DRepresentationDescriptor;
import org.eclipse.sirius.viewpoint.DSemanticDecorator;
import org.eclipse.sirius.viewpoint.RGBValues;
import org.eclipse.sirius.viewpoint.description.RepresentationDescription;
import org.eclipse.sirius.viewpoint.description.Viewpoint;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.Bundle;
import org.polarsys.capella.core.data.cs.Part;
import org.polarsys.capella.core.data.cs.Component;
import org.polarsys.capella.core.data.fa.AbstractFunction;
import org.polarsys.capella.core.model.helpers.AbstractFunctionExt;
import org.polarsys.capella.ai.session.SessionBridge;
import org.polarsys.capella.ai.session.SessionSafeguards;
import org.polarsys.capella.ai.telemetry.Telemetry;

/** In‑process diagram helpers using Sirius UI APIs (arrange/layout visible). */
public final class DiagramCommands {

    private static final Logger LOG = Logger.getLogger(
        DiagramCommands.class.getName()
    );

    private DiagramCommands() {}

    public static volatile DRepresentation lastOpened;

    public static DRepresentation getLastOpened() {
        return lastOpened;
    }

    /** Create and open a diagram for the given target using a description name. */
    public static DRepresentation createAndOpenDiagram(
        EObject target,
        String descriptionName,
        String diagramName
    ) {
        return createAndOpenDiagram(SessionBridge.getActiveSession(), target, descriptionName, diagramName);
    }

    /** Create and open a diagram in the explicitly captured session. */
    public static DRepresentation createAndOpenDiagram(
        Session session,
        EObject target,
        String descriptionName,
        String diagramName
    ) {
        if (session == null) return null;
        TransactionalEditingDomain domain =
            session.getTransactionalEditingDomain();
        if (domain == null) return null;

        final DRepresentation[] rep = new DRepresentation[1];
        final RepresentationDescription[] description = new RepresentationDescription[1];
        SessionSafeguards.runOnUiThread(() -> {
            requireCreationTarget(session, domain, target);
            description[0] = findDiagramDescription(session, descriptionName);
            if (description[0] == null) {
                // Build helpful error message with available descriptors
                java.util.List<String> available = getAvailableDiagramDescriptions(session);
                java.util.List<String> similar = findSimilarDescriptors(available, descriptionName);

                StringBuilder sb = new StringBuilder();
                sb.append("Diagram description '").append(descriptionName).append("' not found.\n");

                if (!similar.isEmpty()) {
                    sb.append("Did you mean: ").append(String.join(", ", similar)).append("?\n");
                }

                sb.append("Available descriptors (").append(available.size()).append("): ");
                sb.append(available.isEmpty() ? "[none - check viewpoint activation]" : String.join(", ", available));
                sb.append("\n\nTroubleshooting:\n");
                sb.append("1. Ensure the correct Capella viewpoints are activated\n");
                sb.append("2. Use api.diagram.listAvailable() to see available types\n");
                sb.append("3. Try using the full descriptor name directly");

                throw new IllegalArgumentException(sb.toString());
            }
        });
        SessionSafeguards.runWrite(session, "Create diagram", () -> {
            requireCreationTarget(session, domain, target);
            if (findDiagramDescription(session, descriptionName) != description[0]) {
                throw new IllegalStateException("The captured session's diagram description changed before creation");
            }
            DRepresentation created =
                DialectManager.INSTANCE.createRepresentation(
                    diagramName,
                    target,
                    description[0],
                    session,
                    new NullProgressMonitor()
                );
            rep[0] = created;
            return created;
        });

        if (rep[0] != null) {
            SessionSafeguards.runOnUiThread(() -> {
                try {
                    DialectUIManager.INSTANCE.openEditor(
                        session,
                        rep[0],
                        new NullProgressMonitor()
                    );
                    lastOpened = rep[0];
                } catch (Exception e) {
                    LOG.warning(
                        "Failed to open diagram editor: " + e.getMessage()
                    );
                }
            });
        }
        return rep[0];
    }

    private static void requireCreationTarget(Session session, TransactionalEditingDomain domain, EObject target) {
        Resource resource = target == null ? null : target.eResource();
        java.util.Collection<Resource> semantic = session.getSemanticResources();
        if (!session.isOpen() || session.getTransactionalEditingDomain() != domain) {
            throw new IllegalStateException("The captured diagram session or editing domain is no longer available");
        }
        if (target == null || target.eIsProxy() || resource == null
                || resource.getResourceSet() != domain.getResourceSet()
                || semantic == null || !semantic.contains(resource)) {
            throw new IllegalArgumentException("Diagram target must belong to the captured session's semantic resources");
        }
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
     * Arrange/align the requested representation using its persisted GMF shapes.
     * Strategies: left, right, top, bottom, hcenter, vcenter, hspace, vspace, grid, auto.
     */
    public static boolean arrangeSelection(
        Session session,
        DRepresentation rep,
        String strategy
    ) {
        if (session == null || rep == null) return false;
        String stratInit = (strategy == null || strategy.isBlank())
            ? "auto"
            : strategy.toLowerCase();
        String layerHintInit = null;
        if (stratInit.matches("^(oa|sa|la|pa|lab)_(flow|radial)$")) {
            int idx = stratInit.indexOf('_');
            layerHintInit = stratInit.substring(0, idx).toUpperCase();
            stratInit = stratInit.substring(idx + 1);
        }
        if ("auto".equals(stratInit)) stratInit = "layered";
        if (!java.util.Set.of("left", "right", "top", "bottom", "hcenter", "vcenter",
            "hspace", "vspace", "grid", "flow", "radial", "layered").contains(stratInit)) return false;
        final String strat = stratInit;
        final String layerHint = layerHintInit;
        try {
            if (!(rep instanceof DDiagram) || !ownsRepresentation(session, rep)) return false;
            // Unavailable notation is a failed request, not an empty undo command.
            org.eclipse.gmf.runtime.notation.Diagram persisted = findNotationDiagram(session, rep);
            if (persisted == null || collectLayoutShapes(persisted).isEmpty()) return false;
            // Compute bounds and apply layout inside write tx
            return SessionSafeguards.runWrite(
                session,
                "Arrange diagram",
                () -> {
                    org.eclipse.gmf.runtime.notation.Diagram notation = findNotationDiagram(session, rep);
                    if (notation == null) return false;
                    java.util.List<Node> nodes = collectLayoutShapes(notation);
                    if (nodes.isEmpty()) return false;
                    java.util.List<Bounds> bounds = new java.util.ArrayList<>(
                        nodes.size()
                    );
                    for (Node n : nodes) {
                        LayoutConstraint lc = n.getLayoutConstraint();
                        if (!(lc instanceof Bounds)) {
                            n.setLayoutConstraint(
                                NotationFactory.eINSTANCE.createBounds()
                            );
                            lc = n.getLayoutConstraint();
                        }
                        bounds.add((Bounds) lc);
                    }
                    if (bounds.size() < 2) return true; // nothing to do
                    int minX = Integer.MAX_VALUE,
                        minY = Integer.MAX_VALUE;
                    int maxR = Integer.MIN_VALUE,
                        maxB = Integer.MIN_VALUE;
                    for (Bounds b : bounds) {
                        minX = Math.min(minX, b.getX());
                        minY = Math.min(minY, b.getY());
                        maxR = Math.max(
                            maxR,
                            b.getX() + Math.max(0, b.getWidth())
                        );
                        maxB = Math.max(
                            maxB,
                            b.getY() + Math.max(0, b.getHeight())
                        );
                    }
                    // Telemetry pre-metrics
                    String diagramName = null;
                    try {
                        if (rep instanceof DDiagram) {
                            var dd = (DDiagram) rep;
                            diagramName = dd.getDescription() != null
                                ? dd.getDescription().getName()
                                : "diagram";
                        }
                    } catch (Throwable ex) { LOG.fine("[DiagramCommands] " + ex.getMessage()); }
                    int overlapsPre = countOverlaps(bounds);
                    int crossingsPre = estimateCrossings(nodes);
                    long t0 = System.nanoTime();
                    switch (strat) {
                        case "left":
                            for (Bounds b : bounds) b.setX(minX);
                            break;
                        case "right":
                            for (Bounds b : bounds)
                                b.setX(maxR - Math.max(0, b.getWidth()));
                            break;
                        case "top":
                            for (Bounds b : bounds) b.setY(minY);
                            break;
                        case "bottom":
                            for (Bounds b : bounds)
                                b.setY(maxB - Math.max(0, b.getHeight()));
                            break;
                        case "hcenter": {
                            int mid = (minX + maxR) / 2;
                            for (Bounds b : bounds)
                                b.setX(mid - Math.max(0, b.getWidth()) / 2);
                            break;
                        }
                        case "vcenter": {
                            int mid = (minY + maxB) / 2;
                            for (Bounds b : bounds)
                                b.setY(mid - Math.max(0, b.getHeight()) / 2);
                            break;
                        }
                        case "hspace": {
                            // Sort by x and distribute across minX..maxR using equal gaps
                            bounds.sort(
                                java.util.Comparator.comparingInt(Bounds::getX)
                            );
                            int n = bounds.size();
                            int totalWidth = bounds
                                .stream()
                                .mapToInt(b -> Math.max(0, b.getWidth()))
                                .sum();
                            int span = Math.max(0, maxR - minX - totalWidth);
                            int gap = (n > 1) ? (span / (n - 1)) : 0;
                            int cursor = minX;
                            for (Bounds b : bounds) {
                                b.setX(cursor);
                                cursor += Math.max(0, b.getWidth()) + gap;
                            }
                            break;
                        }
                        case "vspace": {
                            bounds.sort(
                                java.util.Comparator.comparingInt(Bounds::getY)
                            );
                            int n = bounds.size();
                            int totalHeight = bounds
                                .stream()
                                .mapToInt(b -> Math.max(0, b.getHeight()))
                                .sum();
                            int span = Math.max(0, maxB - minY - totalHeight);
                            int gap = (n > 1) ? (span / (n - 1)) : 0;
                            int cursor = minY;
                            for (Bounds b : bounds) {
                                b.setY(cursor);
                                cursor += Math.max(0, b.getHeight()) + gap;
                            }
                            break;
                        }
                        case "grid": {
                            int n = bounds.size();
                            int cols = (int) Math.ceil(Math.sqrt(n));
                            int rows = (int) Math.ceil((double) n / cols);
                            int cellW = Math.max(
                                120,
                                (maxR - minX) / Math.max(1, cols)
                            );
                            int cellH = Math.max(
                                80,
                                (maxB - minY) / Math.max(1, rows)
                            );
                            for (Bounds b : bounds) {
                                cellW = Math.max(cellW, Math.max(120, b.getWidth()) + 60);
                                cellH = Math.max(cellH, Math.max(80, b.getHeight()) + 40);
                            }
                            int i = 0;
                            for (Bounds b : bounds) {
                                int c = i % cols;
                                int r = i / cols;
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
                            int cols = (int) Math.ceil(
                                (double) n / Math.max(1, rows)
                            );
                            int[] spacing = (layerHint != null)
                                ? computeSpacing(rep, layerHint)
                                : computeSpacing(rep);
                            int cellW = spacing[0];
                            int cellH = spacing[1];
                            // Compute a simple connectivity score and reorder to reduce crossings
                            java.util.Map<Bounds, Integer> score =
                                computeConnectivityScores(rep, nodes);
                            bounds.sort((a, b) ->
                                Integer.compare(
                                    score.getOrDefault(b, 0),
                                    score.getOrDefault(a, 0)
                                )
                            );
                            int startX = minX;
                            int startY = minY;
                            int i = 0;
                            for (Bounds b : bounds) {
                                int r = i / cols;
                                int c = i % cols;
                                b.setX(startX + c * cellW);
                                b.setY(startY + r * cellH);
                                if (b.getWidth() <= 0) b.setWidth(
                                    Math.max(120, cellW - 40)
                                );
                                if (b.getHeight() <= 0) b.setHeight(
                                    Math.max(80, cellH - 40)
                                );
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
                                double radius =
                                    Math.max(spanX, spanY) * 0.6 + n * 6.0;
                                for (int i = 0; i < n; i++) {
                                    Bounds b = bounds.get(i);
                                    double theta = (2.0 * Math.PI * i) / n;
                                    int w = Math.max(80, b.getWidth());
                                    int h = Math.max(50, b.getHeight());
                                    int x = (int) Math.round(
                                        cx + radius * Math.cos(theta) - w / 2.0
                                    );
                                    int y = (int) Math.round(
                                        cy + radius * Math.sin(theta) - h / 2.0
                                    );
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
                            int[] spacing = (layerHint != null)
                                ? computeSpacing(rep, layerHint)
                                : computeSpacing(rep);
                            int cellW = spacing[0];
                            int cellH = spacing[1];
                            for (Bounds bound : bounds) {
                                cellW = Math.max(cellW, Math.max(120, bound.getWidth()) + 60);
                                cellH = Math.max(cellH, Math.max(80, bound.getHeight()) + 40);
                            }
                            java.util.Map<Node, java.util.Set<Node>> adj =
                                computeAdjacency(nodes);
                            // Map Bounds<->Node for ordering and placement
                            java.util.Map<Node, Bounds> nb =
                                new java.util.HashMap<>();
                            for (Node nNode : nodes) {
                                LayoutConstraint lc =
                                    nNode.getLayoutConstraint();
                                if (lc instanceof Bounds) nb.put(
                                    nNode,
                                    (Bounds) lc
                                );
                            }
                            // Seed roots: nodes with smallest degree (or 0), then highest degree for remaining components
                            java.util.List<Node> all =
                                new java.util.ArrayList<>(nodes);
                            all.sort((a, b) ->
                                Integer.compare(
                                    adj
                                        .getOrDefault(a, java.util.Set.of())
                                        .size(),
                                    adj
                                        .getOrDefault(b, java.util.Set.of())
                                        .size()
                                )
                            );
                            java.util.Map<Node, Integer> level =
                                new java.util.HashMap<>();
                            java.util.List<java.util.List<Node>> layers =
                                new java.util.ArrayList<>();
                            java.util.Set<Node> visited =
                                new java.util.HashSet<>();
                            java.util.function.Consumer<Node> bfsFrom =
                                start -> {
                                    java.util.ArrayDeque<Node> q =
                                        new java.util.ArrayDeque<>();
                                    level.put(start, 0);
                                    visited.add(start);
                                    q.add(start);
                                    while (!q.isEmpty()) {
                                        Node u = q.removeFirst();
                                        int lu = level.get(u);
                                        while (layers.size() <= lu) layers.add(
                                            new java.util.ArrayList<>()
                                        );
                                        layers.get(lu).add(u);
                                        for (Node v : adj.getOrDefault(
                                            u,
                                            java.util.Set.of()
                                        )) {
                                            if (!visited.contains(v)) {
                                                visited.add(v);
                                                level.put(v, lu + 1);
                                                q.addLast(v);
                                            }
                                        }
                                    }
                                };
                            for (Node r : all)
                                if (!visited.contains(r)) bfsFrom.accept(r);
                            // Barycenter ordering per layer based on previous layer positions
                            java.util.Map<Node, Integer> prevOrder =
                                new java.util.HashMap<>();
                            for (int li = 0; li < layers.size(); li++) {
                                final int layerIdx = li;
                                java.util.List<Node> L = layers.get(li);
                                if (li == 0) {
                                    // Order first layer by degree desc
                                    L.sort((a, b) ->
                                        Integer.compare(
                                            adj
                                                .getOrDefault(
                                                    b,
                                                    java.util.Set.of()
                                                )
                                                .size(),
                                            adj
                                                .getOrDefault(
                                                    a,
                                                    java.util.Set.of()
                                                )
                                                .size()
                                        )
                                    );
                                    for (
                                        int i = 0;
                                        i < L.size();
                                        i++
                                    ) prevOrder.put(L.get(i), i);
                                } else {
                                    java.util.Map<Node, Double> bary =
                                        new java.util.HashMap<>();
                                    for (Node u : L) {
                                        java.util.List<Node> preds =
                                            new java.util.ArrayList<>(
                                                adj.getOrDefault(
                                                    u,
                                                    java.util.Set.of()
                                                )
                                            );
                                        // only consider neighbors from previous layer for ordering
                                        preds.removeIf(
                                            p ->
                                                level.getOrDefault(p, -1) !=
                                                layerIdx - 1
                                        );
                                        if (preds.isEmpty()) {
                                            bary.put(
                                                u,
                                                Double.POSITIVE_INFINITY
                                            );
                                            continue;
                                        }
                                        double sum = 0;
                                        int cnt = 0;
                                        for (Node p : preds) {
                                            Integer po = prevOrder.get(p);
                                            if (po != null) {
                                                sum += po;
                                                cnt++;
                                            }
                                        }
                                        bary.put(
                                            u,
                                            cnt == 0
                                                ? Double.POSITIVE_INFINITY
                                                : (sum / cnt)
                                        );
                                    }
                                    L.sort((a, b) ->
                                        Double.compare(
                                            bary.getOrDefault(
                                                a,
                                                Double.POSITIVE_INFINITY
                                            ),
                                            bary.getOrDefault(
                                                b,
                                                Double.POSITIVE_INFINITY
                                            )
                                        )
                                    );
                                    for (
                                        int i = 0;
                                        i < L.size();
                                        i++
                                    ) prevOrder.put(L.get(i), i);
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
                                    if (b.getWidth() <= 0) b.setWidth(
                                        Math.max(120, cellW - 40)
                                    );
                                    if (b.getHeight() <= 0) b.setHeight(
                                        Math.max(80, cellH - 40)
                                    );
                                }
                            }
                            break;
                        }
                        default:
                            throw new IllegalArgumentException("Unsupported layout strategy " + strat);
                    }
                    // Native notation notifications update an open editor after commit.
                    // A semantic refresh is neither needed nor a substitute for layout.
                    // Telemetry post-metrics
                    int overlapsPost = countOverlaps(bounds);
                    int crossingsPost = estimateCrossings(nodes);
                    long durMs =
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                            System.nanoTime() - t0
                        );
                    String stratLabel = (layerHint != null &&
                            !layerHint.isBlank())
                        ? (layerHint.toLowerCase() + "_" + strat)
                        : strat;
                    Telemetry.recordLayout(
                        diagramName == null ? "diagram" : diagramName,
                        stratLabel,
                        bounds.size(),
                        overlapsPre,
                        overlapsPost,
                        crossingsPre,
                        crossingsPost,
                        durMs
                    );
                    return true;
                }
            );
        } catch (Exception e) {
            LOG.warning("arrangeSelection failed: " + e.getMessage());
            return false;
        }
    }

    private static int[] computeSpacing(DRepresentation rep) {
        return computeSpacing(rep, null);
    }

    private static int[] computeSpacing(DRepresentation rep, String layerHint) {
        int cellW = 200,
            cellH = 140;
        if (layerHint != null) {
            String lh = layerHint.toUpperCase();
            if ("LA".equals(lh)) {
                cellW = 240;
                cellH = 160;
            } else if ("SA".equals(lh)) {
                cellW = 240;
                cellH = 170;
            } else if ("PA".equals(lh)) {
                cellW = 260;
                cellH = 180;
            } else if ("OA".equals(lh)) {
                cellW = 220;
                cellH = 150;
            } else if ("LAB".equals(lh)) {
                cellW = 260;
                cellH = 180;
            }
        }
        try {
            if (rep instanceof DDiagram) {
                org.eclipse.sirius.diagram.DDiagram d =
                    (org.eclipse.sirius.diagram.DDiagram) rep;
                String name = null;
                try {
                    name = d.getDescription() != null
                        ? d.getDescription().getName()
                        : null;
                } catch (Throwable ex) { LOG.fine("[DiagramCommands] " + ex.getMessage()); }
                String n = name != null ? name.toLowerCase() : "";
                if (n.contains("data flow") || n.contains("flow")) {
                    cellW = 240;
                    cellH = 160;
                } else if (
                    n.contains("function breakdown") || n.contains("fbd")
                ) {
                    cellW = 220;
                    cellH = 170;
                } else if (
                    n.contains("logical architecture blank") ||
                    n.contains("lab")
                ) {
                    cellW = 260;
                    cellH = 180;
                } else if (
                    n.contains("system architecture blank") || n.contains("sab")
                ) {
                    cellW = 280;
                    cellH = 190;
                } else if (
                    n.contains("physical architecture blank") ||
                    n.contains("pab")
                ) {
                    cellW = 300;
                    cellH = 200;
                }
            }
        } catch (Throwable ex) { LOG.fine("[DiagramCommands] " + ex.getMessage()); }
        return new int[] { cellW, cellH };
    }

    private static java.util.Map<Bounds, Integer> computeConnectivityScores(
        DRepresentation rep,
        java.util.List<Node> nodes
    ) {
        java.util.Map<Bounds, Integer> scores = new java.util.HashMap<>();
        java.util.List<Object[]> pairs = new java.util.ArrayList<>();
        // Map each node to its semantic EObject (if available)
        java.util.Map<Node, org.eclipse.emf.ecore.EObject> sem =
            new java.util.HashMap<>();
        for (Node n : nodes) {
            org.eclipse.emf.ecore.EObject target = toSemantic(n);
            if (target != null) {
                sem.put(n, target);
            }
        }
        java.util.Set<org.eclipse.emf.ecore.EObject> all =
            new java.util.HashSet<>(sem.values());
        // For each element, count references to elements within the same set
        for (Node n : nodes) {
            int sc = 0;
            org.eclipse.emf.ecore.EObject t = sem.get(n);
            if (t != null) {
                try {
                    for (org.eclipse.emf.ecore.EStructuralFeature f : t
                        .eClass()
                        .getEAllStructuralFeatures()) {
                        if (f instanceof org.eclipse.emf.ecore.EReference) {
                            org.eclipse.emf.ecore.EReference r =
                                (org.eclipse.emf.ecore.EReference) f;
                            if (r.isContainment()) continue;
                            Object v = t.eGet(r);
                            if (v instanceof org.eclipse.emf.ecore.EObject e2) {
                                if (all.contains(e2)) sc++;
                            } else if (v instanceof java.util.Collection<?>) {
                                for (Object o : (java.util.Collection<?>) v) {
                                    if (
                                        o instanceof
                                            org.eclipse.emf.ecore.EObject e3
                                    ) {
                                        if (all.contains(e3)) sc++;
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable ex) { LOG.fine("[DiagramCommands] " + ex.getMessage()); }
            }
            // Save by Bounds (from node)
            Bounds b = (Bounds) n.getLayoutConstraint();
            if (b != null) scores.put(b, sc);
        }
        return scores;
    }

    /** The diagram must belong to this session's editing domain and owned resources. */
    private static boolean ownsRepresentation(Session session, DRepresentation rep) {
        return ownsLayoutResource(session, rep.eResource());
    }

    private static boolean ownsLayoutResource(Session session, Resource resource) {
        TransactionalEditingDomain domain = session.getTransactionalEditingDomain();
        if (resource == null || domain == null || resource.getResourceSet() != domain.getResourceSet()) return false;
        if (resource == session.getSessionResource()) return true;
        java.util.Collection<Resource> owned = session.getAllSessionResources();
        java.util.Collection<Resource> referenced = session.getReferencedSessionResources();
        return owned != null && owned.contains(resource) && (referenced == null || !referenced.contains(resource));
    }

    /** Sirius normally contains the GMF diagram in an annotation on the representation. */
    private static org.eclipse.gmf.runtime.notation.Diagram findNotationDiagram(Session session, DRepresentation rep) {
        var contents = rep.eAllContents();
        while (contents.hasNext()) {
            EObject element = contents.next();
            if (element instanceof org.eclipse.gmf.runtime.notation.Diagram notation && notation.getElement() == rep) return notation;
        }
        for (Resource resource : session.getTransactionalEditingDomain().getResourceSet().getResources()) {
            if (!ownsLayoutResource(session, resource)) continue;
            var resourceContents = resource.getAllContents();
            while (resourceContents.hasNext()) {
                EObject element = resourceContents.next();
                if (element instanceof org.eclipse.gmf.runtime.notation.Diagram notation && notation.getElement() == rep
                    && ownsRepresentation(session, rep)) return notation;
            }
        }
        return null;
    }

    /** Keep outer semantic shapes; labels, compartment wrappers and border ports never move independently. */
    private static java.util.List<Node> collectLayoutShapes(org.eclipse.gmf.runtime.notation.Diagram diagram) {
        java.util.List<Node> shapes = new java.util.ArrayList<>();
        collectLayoutShapes(diagram, shapes);
        return shapes;
    }

    private static void collectLayoutShapes(View container, java.util.List<Node> shapes) {
        for (Object child : container.getChildren()) {
            if (!(child instanceof Node node)) continue;
            EObject semanticView = node.getElement();
            boolean shape = semanticView instanceof DNode || semanticView instanceof DNodeContainer
                || semanticView instanceof org.eclipse.sirius.diagram.DNodeList;
            if (shape && semanticView instanceof DSemanticDecorator decorator && decorator.getTarget() != null
                && !(decorator.getTarget() instanceof org.polarsys.capella.core.data.information.Port)
                && (semanticView.eContainingFeature() == null || !"ownedBorderedNodes".equals(semanticView.eContainingFeature().getName()))) {
                shapes.add(node);
            } else if (!(semanticView instanceof DSemanticDecorator)) {
                // Only notation compartments may wrap top-level shapes. A port is never a wrapper.
                collectLayoutShapes(node, shapes);
            }
        }
    }

    /** Native GMF edges already carry the exact visible endpoints, including function-port views. */
    private static java.util.Map<Node, java.util.Set<Node>> computeAdjacency(java.util.List<Node> nodes) {
        java.util.Map<Node, java.util.Set<Node>> adjacency = new java.util.LinkedHashMap<>();
        for (Node node : nodes) adjacency.put(node, new java.util.LinkedHashSet<>());
        java.util.Set<org.eclipse.gmf.runtime.notation.Diagram> diagrams = new java.util.HashSet<>();
        for (Node node : nodes) if (node.getDiagram() != null) diagrams.add(node.getDiagram());
        for (org.eclipse.gmf.runtime.notation.Diagram diagram : diagrams) {
            for (Object candidate : diagram.getEdges()) {
                if (!(candidate instanceof org.eclipse.gmf.runtime.notation.Edge edge)) continue;
                Node source = owningLayoutShape(edge.getSource(), adjacency.keySet());
                Node target = owningLayoutShape(edge.getTarget(), adjacency.keySet());
                if (source != null && target != null && source != target) {
                    adjacency.get(source).add(target);
                    adjacency.get(target).add(source);
                }
            }
        }
        return adjacency;
    }

    private static Node owningLayoutShape(View endpoint, java.util.Set<Node> shapes) {
        EObject current = endpoint;
        while (current instanceof View) {
            if (current instanceof Node node && shapes.contains(node)) return node;
            current = current.eContainer();
        }
        return null;
    }

    private static EObject toSemantic(Node node) {
        return node.getElement() instanceof DSemanticDecorator decorator ? decorator.getTarget() : null;
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
                    edges.add(new Node[] { u, v });
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
            center.put(n, new double[] { cx, cy });
        }
        int crossings = 0;
        for (int i = 0; i < m; i++) {
            Node[] e1 = edges.get(i);
            for (int j = i + 1; j < m; j++) {
                Node[] e2 = edges.get(j);
                if (
                    e1[0] == e2[0] ||
                    e1[0] == e2[1] ||
                    e1[1] == e2[0] ||
                    e1[1] == e2[1]
                ) continue;
                double[] a1 = center.get(e1[0]);
                double[] a2 = center.get(e1[1]);
                double[] b1 = center.get(e2[0]);
                double[] b2 = center.get(e2[1]);
                if (
                    a1 == null || a2 == null || b1 == null || b2 == null
                ) continue;
                if (
                    segmentsIntersect(
                        a1[0],
                        a1[1],
                        a2[0],
                        a2[1],
                        b1[0],
                        b1[1],
                        b2[0],
                        b2[1]
                    )
                ) crossings++;
            }
        }
        return crossings;
    }

    private static double orient(
        double ax,
        double ay,
        double bx,
        double by,
        double cx,
        double cy
    ) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }

    private static boolean segmentsIntersect(
        double ax,
        double ay,
        double bx,
        double by,
        double cx,
        double cy,
        double dx,
        double dy
    ) {
        double o1 = orient(ax, ay, bx, by, cx, cy);
        double o2 = orient(ax, ay, bx, by, dx, dy);
        double o3 = orient(cx, cy, dx, dy, ax, ay);
        double o4 = orient(cx, cy, dx, dy, bx, by);
        if (
            ((o1 > 0 && o2 < 0) || (o1 < 0 && o2 > 0)) &&
            ((o3 > 0 && o4 < 0) || (o3 < 0 && o4 > 0))
        ) {
            return true;
        }
        return false;
    }

    /** Best-effort: resolve elements by id/uuid/fragment and refresh the diagram editor. */
    public static int addElementsByUuid(
        Session session,
        DRepresentation rep,
        java.util.List<String> ids
    ) {
        if (
            session == null || rep == null || ids == null || ids.isEmpty()
        ) return 0;
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

    /**
     * Create a node view for a semantic element on a diagram.
     * Uses Sirius's DDiagramElementSynchronizer for proper synchronized diagram support.
     */
    public static boolean createNode(
        Session session,
        DRepresentation rep,
        EObject semantic
    ) {
        if (session == null || rep == null || semantic == null) return false;
        if (!(rep instanceof DDiagram)) return false;

        DDiagram diagram = (DDiagram) rep;

        try {
            // Check if already on diagram
            if (isOnDiagram(diagram, semantic)) {
                LOG.info("Element already on diagram: " + semantic);
                return true;
            }

            DSemanticDecorator containerView =
                resolveContainerView(diagram, semantic);
            if (
                containerView != null &&
                tryCreateViewTask(
                    session,
                    diagram,
                    containerView,
                    semantic
                )
            ) {
                return true;
            }

            // Use Sirius's proper element creation via DDiagramElementSynchronizer
            return SessionSafeguards.runWrite(
                session,
                "Add element to diagram",
                () -> {
                    try {
                        // Get the diagram description
                        DiagramDescription desc = diagram.getDescription();
                        if (desc == null) {
                            LOG.warning("Diagram has no description");
                            return false;
                        }

                        // Find appropriate mapping for this element type
                        AbstractNodeMapping mapping = findNodeMapping(
                            desc,
                            semantic
                        );
                        if (mapping == null) {
                            LOG.warning(
                                "No mapping found for: " +
                                    semantic.eClass().getName()
                            );
                            return false;
                        }

                        // Use Sirius synchronizer to create element properly
                        Class<?> syncClass = Class.forName(
                            "org.eclipse.sirius.diagram.business.internal.sync.DDiagramElementSynchronizer"
                        );

                        // Get interpreter from session
                        Object interpreter = null;
                        try {
                            var getInterpreter = session
                                .getClass()
                                .getMethod("getInterpreter");
                            interpreter = getInterpreter.invoke(session);
                        } catch (Exception e) {
                            // Try alternative: get from Sirius runtime
                            Class<?> siriusPlugin = Class.forName(
                                "org.eclipse.sirius.business.api.componentization.ViewpointRegistry"
                            );
                            var getInstance = siriusPlugin.getMethod(
                                "getInstance"
                            );
                            var registry = getInstance.invoke(null);
                        }

                        // Create synchronizer
                        var syncConstructor = syncClass.getConstructor(
                            DDiagram.class,
                            Class.forName(
                                "org.eclipse.sirius.common.tools.api.interpreter.IInterpreter"
                            ),
                            Class.forName(
                                "org.eclipse.sirius.ecore.extender.business.api.accessor.ModelAccessor"
                            )
                        );

                        // Get model accessor
                        var getAccessor = session
                            .getClass()
                            .getMethod("getModelAccessor");
                        var accessor = getAccessor.invoke(session);

                        // Get interpreter
                        var getInterp = session
                            .getClass()
                            .getMethod("getInterpreter");
                        var interp = getInterp.invoke(session);

                        var synchronizer = syncConstructor.newInstance(
                            diagram,
                            interp,
                            accessor
                        );

                        // Call createNewNode method
                        if (mapping instanceof ContainerMapping) {
                            var createMethod = syncClass.getMethod(
                                "createNewNode",
                                Class.forName(
                                    "org.eclipse.sirius.diagram.DDiagramElementContainer"
                                ),
                                EObject.class,
                                ContainerMapping.class
                            );
                            // null parent = add to diagram root
                            var result = createMethod.invoke(
                                synchronizer,
                                null,
                                semantic,
                                mapping
                            );
                            if (result != null) {
                                diagram
                                    .getOwnedDiagramElements()
                                    .add((DDiagramElement) result);
                                LOG.info(
                                    "Created container for: " +
                                        semantic.eClass().getName()
                                );
                            }
                        } else if (mapping instanceof NodeMapping) {
                            var createMethod = syncClass.getMethod(
                                "createNewNode",
                                Class.forName(
                                    "org.eclipse.sirius.diagram.DDiagramElementContainer"
                                ),
                                EObject.class,
                                NodeMapping.class
                            );
                            var result = createMethod.invoke(
                                synchronizer,
                                null,
                                semantic,
                                mapping
                            );
                            if (result != null) {
                                diagram
                                    .getOwnedDiagramElements()
                                    .add((DDiagramElement) result);
                                LOG.info(
                                    "Created node for: " +
                                        semantic.eClass().getName()
                                );
                            }
                        }

                        // Refresh to apply styles
                        DialectManager.INSTANCE.refresh(
                            diagram,
                            new NullProgressMonitor()
                        );
                        return isOnDiagram(diagram, semantic);
                    } catch (Exception e) {
                        LOG.warning(
                            "Synchronizer approach failed: " + e.getMessage()
                        );

                        // Fallback: manually create and add element to correct container
                        DiagramDescription desc = diagram.getDescription();
                        AbstractNodeMapping mapping = findNodeMapping(
                            desc,
                            semantic
                        );

                        if (mapping != null) {
                            DDiagramElement newElement = createDiagramElement(
                                mapping,
                                semantic
                            );
                            if (newElement != null) {
                                // Find correct parent container based on allocations
                                DNodeContainer parentContainer =
                                    findParentContainer(diagram, semantic);
                                if (parentContainer != null) {
                                    parentContainer
                                        .getOwnedDiagramElements()
                                        .add(newElement);
                                    LOG.info(
                                        "Added to container: " +
                                            parentContainer.getTarget()
                                    );
                                } else {
                                    diagram
                                        .getOwnedDiagramElements()
                                        .add(newElement);
                                    LOG.info("Added to diagram root");
                                }
                                DialectManager.INSTANCE.refresh(
                                    diagram,
                                    new NullProgressMonitor()
                                );
                                return isOnDiagram(diagram, semantic);
                            }
                        }
                        return false;
                    }
                }
            );
        } catch (Exception e) {
            LOG.warning("createNode failed: " + e.getMessage());
            return false;
        }
    }

    private static boolean tryCreateViewTask(
        Session session,
        DDiagram diagram,
        DSemanticDecorator containerView,
        EObject semantic
    ) {
        Bundle bundle = Platform.getBundle(
            "org.polarsys.capella.core.sirius.analysis"
        );
        if (bundle == null) {
            return false;
        }
        try {
            Class<?> taskClass = bundle.loadClass(
                "org.polarsys.capella.core.sirius.analysis.task.CreateViewTask"
            );
            return SessionSafeguards.runWrite(
                session,
                "Add element to diagram",
                () -> {
                    try {
                        var ctor = taskClass.getConstructor(
                            EObject.class,
                            DSemanticDecorator.class,
                            EObject.class,
                            String.class,
                            String.class
                        );
                        Object task = ctor.newInstance(
                            semantic,
                            containerView,
                            semantic,
                            null,
                            "view"
                        );
                        taskClass.getMethod("execute").invoke(task);
                    } catch (Exception ex) {
                        LOG.fine(
                            "CreateViewTask failed: " + ex.getMessage()
                        );
                        return false;
                    }
                    return isOnDiagram(diagram, semantic);
                }
            );
        } catch (Exception ex) {
            LOG.fine("CreateViewTask not available: " + ex.getMessage());
            return false;
        }
    }

    private static DSemanticDecorator resolveContainerView(
        DDiagram diagram,
        EObject semantic
    ) {
        if (diagram == null || semantic == null) {
            return null;
        }
        DNodeContainer parent = findParentContainer(diagram, semantic);
        if (parent != null) {
            return parent;
        }
        if (diagram instanceof DSemanticDiagram) {
            return (DSemanticDiagram) diagram;
        }
        return null;
    }

    private static boolean isOnDiagram(DDiagram diagram, EObject semantic) {
        if (diagram == null || semantic == null) {
            return false;
        }
        for (DDiagramElement elem : diagram.getDiagramElements()) {
            if (semantic.equals(elem.getTarget())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Find the parent container on the diagram for a semantic element.
     * Looks for allocation relationships (e.g., activity allocated to entity).
     */
    private static DNodeContainer findParentContainer(
        DDiagram diagram,
        EObject semantic
    ) {
        EObject semanticParent = findSemanticParent(semantic);

        if (semanticParent != null) {
            for (DDiagramElement elem : diagram.getDiagramElements()) {
                if (!(elem instanceof DNodeContainer)) {
                    continue;
                }
                EObject target = elem.getTarget();
                if (semanticParent.equals(target)) {
                    return (DNodeContainer) elem;
                }
                if (target instanceof Part) {
                    EObject abstractType = ((Part) target).getAbstractType();
                    if (semanticParent.equals(abstractType)) {
                        return (DNodeContainer) elem;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Find the semantic parent of an element via allocations.
     */
    private static EObject findSemanticParent(EObject semantic) {
        try {
            try {
                if (semantic instanceof AbstractFunction) {
                    java.util.List<Component> components =
                        AbstractFunctionExt.getAllocatingComponents(
                            (AbstractFunction) semantic
                        );
                    if (components != null && !components.isEmpty()) {
                        return components.get(0);
                    }
                    java.util.List<Component> parents =
                        AbstractFunctionExt.getMotherAllFunctionAllocation(
                            (AbstractFunction) semantic
                        );
                    if (parents != null && !parents.isEmpty()) {
                        return parents.get(0);
                    }
                }
            } catch (Throwable ignored) {
                // Fall through to generic allocation lookup
            }

            var eClass = semantic.eClass();

            // Check componentFunctionalAllocations and get source
            var allocsRef = eClass.getEStructuralFeature(
                "componentFunctionalAllocations"
            );
            if (allocsRef != null) {
                Object val = semantic.eGet(allocsRef);
                if (val instanceof java.util.Collection<?>) {
                    for (Object alloc : (java.util.Collection<?>) val) {
                        if (alloc instanceof EObject) {
                            var sourceRef =
                                ((EObject) alloc).eClass().getEStructuralFeature(
                                    "sourceElement"
                                );
                            if (sourceRef != null) {
                                Object source = ((EObject) alloc).eGet(
                                    sourceRef
                                );
                                if (source instanceof EObject) {
                                    return (EObject) source;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.fine("Could not find semantic parent: " + e.getMessage());
        }
        return null;
    }

    /**
     * Find the appropriate node or container mapping for a semantic element.
     */
    private static AbstractNodeMapping findNodeMapping(
        DiagramDescription desc,
        EObject semantic
    ) {
        String targetClass = semantic.eClass().getName();
        String targetPackage = semantic.eClass().getEPackage().getName();

        // Check default layer's container mappings
        if (desc.getDefaultLayer() != null) {
            for (ContainerMapping cm : desc
                .getDefaultLayer()
                .getContainerMappings()) {
                if (mappingMatchesClass(cm, targetClass, targetPackage)) {
                    return cm;
                }
                // Check sub-mappings
                for (NodeMapping subNm : cm.getSubNodeMappings()) {
                    if (
                        mappingMatchesClass(subNm, targetClass, targetPackage)
                    ) {
                        return subNm;
                    }
                }
                for (ContainerMapping subCm : cm.getSubContainerMappings()) {
                    if (
                        mappingMatchesClass(subCm, targetClass, targetPackage)
                    ) {
                        return subCm;
                    }
                }
            }

            // Check default layer's node mappings
            for (NodeMapping nm : desc.getDefaultLayer().getNodeMappings()) {
                if (mappingMatchesClass(nm, targetClass, targetPackage)) {
                    return nm;
                }
            }
        }

        // Check additional layers
        for (var layer : desc.getAdditionalLayers()) {
            for (ContainerMapping cm : layer.getContainerMappings()) {
                if (mappingMatchesClass(cm, targetClass, targetPackage)) {
                    return cm;
                }
            }
            for (NodeMapping nm : layer.getNodeMappings()) {
                if (mappingMatchesClass(nm, targetClass, targetPackage)) {
                    return nm;
                }
            }
        }

        return null;
    }

    private static java.util.List<AbstractNodeMapping> findNodeMappings(
        DiagramDescription desc,
        EObject semantic
    ) {
        java.util.List<AbstractNodeMapping> results =
            new java.util.ArrayList<>();
        if (desc == null || semantic == null) {
            return results;
        }
        String targetClass = semantic.eClass().getName();
        String targetPackage = semantic.eClass().getEPackage().getName();

        if (desc.getDefaultLayer() != null) {
            collectNodeMappings(
                desc.getDefaultLayer(),
                targetClass,
                targetPackage,
                results
            );
        }

        for (var layer : desc.getAdditionalLayers()) {
            collectNodeMappings(layer, targetClass, targetPackage, results);
        }

        return results;
    }

    private static void collectNodeMappings(
        org.eclipse.sirius.diagram.description.Layer layer,
        String targetClass,
        String targetPackage,
        java.util.List<AbstractNodeMapping> results
    ) {
        if (layer == null) {
            return;
        }
        for (ContainerMapping cm : layer.getContainerMappings()) {
            if (mappingMatchesClass(cm, targetClass, targetPackage)) {
                results.add(cm);
            }
            for (NodeMapping subNm : cm.getSubNodeMappings()) {
                if (mappingMatchesClass(subNm, targetClass, targetPackage)) {
                    results.add(subNm);
                }
            }
            for (ContainerMapping subCm : cm.getSubContainerMappings()) {
                if (mappingMatchesClass(subCm, targetClass, targetPackage)) {
                    results.add(subCm);
                }
            }
        }
        for (NodeMapping nm : layer.getNodeMappings()) {
            if (mappingMatchesClass(nm, targetClass, targetPackage)) {
                results.add(nm);
            }
        }
    }

    /**
     * Return the list of domain classes supported by the diagram's node/container mappings.
     */
    public static java.util.List<String> getSupportedDomainClasses(
        DRepresentation rep
    ) {
        if (!(rep instanceof DDiagram)) {
            return java.util.List.of();
        }
        DDiagram diagram = (DDiagram) rep;
        DiagramDescription desc = diagram.getDescription();
        if (desc == null) {
            return java.util.List.of();
        }

        java.util.Set<String> results = new java.util.LinkedHashSet<>();
        collectMappingDomains(desc.getDefaultLayer(), results);
        for (var layer : desc.getAdditionalLayers()) {
            collectMappingDomains(layer, results);
        }
        return new java.util.ArrayList<>(results);
    }

    /** Best-effort: return the semantic target for a diagram representation. */
    public static EObject getDiagramTarget(DRepresentation rep) {
        if (rep == null) {
            return null;
        }
        try {
            java.lang.reflect.Method m = DialectManager.class.getMethod(
                "getRepresentationDescriptor",
                DRepresentation.class
            );
            Object descriptor = m.invoke(DialectManager.INSTANCE, rep);
            if (descriptor == null) {
                return null;
            }
            java.lang.reflect.Method getTarget = descriptor
                .getClass()
                .getMethod("getTarget");
            Object target = getTarget.invoke(descriptor);
            return target instanceof EObject ? (EObject) target : null;
        } catch (Throwable ex) {
            LOG.fine(
                "[DiagramCommands] getDiagramTarget failed: " + ex.getMessage()
            );
            return null;
        }
    }

    /**
     * Check whether a diagram has a mapping for the given semantic element.
     */
    public static boolean supportsElement(DRepresentation rep, EObject semantic) {
        if (!(rep instanceof DDiagram) || semantic == null) {
            return false;
        }
        DDiagram diagram = (DDiagram) rep;
        DiagramDescription desc = diagram.getDescription();
        if (desc == null) {
            return false;
        }
        return findNodeMapping(desc, semantic) != null;
    }

    /**
     * Check whether an element is displayable on a diagram, evaluating mapping preconditions.
     */
    public static boolean isDisplayable(
        Session session,
        DRepresentation rep,
        EObject semantic
    ) {
        if (!(rep instanceof DDiagram) || semantic == null) {
            return false;
        }
        DDiagram diagram = (DDiagram) rep;
        DiagramDescription desc = diagram.getDescription();
        if (desc == null) {
            return false;
        }
        DSemanticDecorator containerView =
            resolveContainerView(diagram, semantic);
        java.util.List<AbstractNodeMapping> mappings =
            findNodeMappings(desc, semantic);
        if (mappings.isEmpty()) {
            return false;
        }
        Object interpreter = getInterpreter(session);
        IInterpreter siriusInterpreter = getSiriusInterpreter(semantic);
        for (AbstractNodeMapping mapping : mappings) {
            if (
                !preconditionSatisfiedWithViewpointServices(
                    mapping,
                    semantic,
                    containerView,
                    interpreter
                )
            ) {
                continue;
            }
            if (
                !candidatesSatisfiedWithViewpointServices(
                    diagram,
                    mapping,
                    semantic,
                    containerView,
                    siriusInterpreter,
                    interpreter
                )
            ) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static IInterpreter getSiriusInterpreter(EObject semantic) {
        if (semantic == null) {
            return null;
        }
        try {
            return InterpreterUtil.getInterpreter(semantic);
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean preconditionSatisfiedWithViewpointServices(
        AbstractNodeMapping mapping,
        EObject semantic,
        DSemanticDecorator containerView,
        Object legacyInterpreter
    ) {
        if (mapping == null || semantic == null) {
            return false;
        }
        Boolean evaluated = invokeSiriusPrecondition(
            mapping,
            semantic,
            containerView
        );
        if (evaluated != null) {
            return evaluated.booleanValue();
        }
        return preconditionSatisfied(legacyInterpreter, semantic, mapping);
    }

    private static Boolean invokeSiriusPrecondition(
        AbstractNodeMapping mapping,
        EObject semantic,
        DSemanticDecorator containerView
    ) {
        try {
            Class<?> ops = Class.forName(
                "org.eclipse.sirius.diagram.business.internal.metamodel.description.operations.SiriusElementMappingSpecOperations"
            );
            var method = ops.getMethod(
                "checkPrecondition",
                DiagramElementMapping.class,
                EObject.class,
                EObject.class,
                EObject.class
            );
            EObject containerSemantic = null;
            if (containerView != null) {
                containerSemantic = containerView.getTarget();
            }
            Object result = method.invoke(
                null,
                mapping,
                semantic,
                containerSemantic,
                containerView
            );
            if (result instanceof Boolean) {
                return (Boolean) result;
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private static boolean candidatesSatisfiedWithViewpointServices(
        DDiagram diagram,
        AbstractNodeMapping mapping,
        EObject semantic,
        DSemanticDecorator containerView,
        IInterpreter siriusInterpreter,
        Object legacyInterpreter
    ) {
        if (diagram instanceof DSemanticDiagram &&
            mapping instanceof DiagramElementMapping &&
            siriusInterpreter != null) {
            DiagramElementMappingQuery query =
                new DiagramElementMappingQuery(
                    (DiagramElementMapping) mapping
                );
            if (!query.hasCandidatesExpression()) {
                return true;
            }
            DragAndDropTarget target =
                containerView instanceof DragAndDropTarget
                    ? (DragAndDropTarget) containerView
                    : diagram instanceof DragAndDropTarget
                        ? (DragAndDropTarget) diagram
                        : null;
            if (target != null) {
                try {
                    java.util.Collection<EObject> candidates =
                        query.evaluateCandidateExpression(
                            (DSemanticDiagram) diagram,
                            siriusInterpreter,
                            target
                        );
                    if (candidates == null) {
                        return false;
                    }
                    return candidates.contains(semantic);
                } catch (Exception ignored) {
                    // fallback below
                }
            }
        }
        return candidatesSatisfied(legacyInterpreter, diagram, mapping, semantic);
    }

    private static void collectMappingDomains(
        org.eclipse.sirius.diagram.description.Layer layer,
        java.util.Set<String> results
    ) {
        if (layer == null) {
            return;
        }
        for (ContainerMapping cm : layer.getContainerMappings()) {
            collectMappingDomains(cm, results);
        }
        for (NodeMapping nm : layer.getNodeMappings()) {
            addDomainClass(nm, results);
        }
    }

    private static void collectMappingDomains(
        ContainerMapping mapping,
        java.util.Set<String> results
    ) {
        if (mapping == null) {
            return;
        }
        addDomainClass(mapping, results);
        for (NodeMapping sub : mapping.getSubNodeMappings()) {
            addDomainClass(sub, results);
        }
        for (ContainerMapping sub : mapping.getSubContainerMappings()) {
            collectMappingDomains(sub, results);
        }
    }

    private static void addDomainClass(
        AbstractNodeMapping mapping,
        java.util.Set<String> results
    ) {
        if (mapping == null) {
            return;
        }
        String domainClass = mapping.getDomainClass();
        if (domainClass != null && !domainClass.isBlank()) {
            results.add(domainClass);
        }
    }

    private static Object getInterpreter(Session session) {
        if (session == null) {
            return null;
        }
        try {
            var method = session.getClass().getMethod("getInterpreter");
            return method.invoke(session);
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean preconditionSatisfied(
        Object interpreter,
        EObject semantic,
        AbstractNodeMapping mapping
    ) {
        String expr = getExpression(mapping, "getPreconditionExpression");
        if (expr == null || expr.isBlank()) {
            return true;
        }
        if (interpreter == null) {
            return true; // best-effort when interpreter unavailable
        }
        Object result = evaluate(interpreter, semantic, expr);
        if (result instanceof Boolean) {
            return (Boolean) result;
        }
        return false;
    }

    private static boolean candidatesSatisfied(
        Object interpreter,
        DDiagram diagram,
        AbstractNodeMapping mapping,
        EObject semantic
    ) {
        String expr = getExpression(mapping, "getSemanticCandidatesExpression");
        if (expr == null || expr.isBlank()) {
            return true;
        }
        EObject target = getDiagramTarget(diagram);
        if (interpreter == null || target == null) {
            return true;
        }
        Object result = evaluate(interpreter, target, expr);
        if (result == null) {
            return false;
        }
        if (result instanceof java.util.Collection<?>) {
            return ((java.util.Collection<?>) result).contains(semantic);
        }
        return result.equals(semantic);
    }

    private static String getExpression(
        Object mapping,
        String methodName
    ) {
        if (mapping == null) {
            return null;
        }
        try {
            var method = mapping.getClass().getMethod(methodName);
            Object value = method.invoke(mapping);
            return value != null ? value.toString() : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static Object evaluate(
        Object interpreter,
        EObject context,
        String expression
    ) {
        if (interpreter == null || context == null || expression == null) {
            return null;
        }
        try {
            var method = interpreter.getClass().getMethod(
                "evaluateBoolean",
                EObject.class,
                String.class
            );
            return method.invoke(interpreter, context, expression);
        } catch (Exception ignored) {
            // fallback to evaluateExpression if available
        }
        try {
            var method = interpreter.getClass().getMethod(
                "evaluateExpression",
                EObject.class,
                String.class
            );
            return method.invoke(interpreter, context, expression);
        } catch (Exception ignored) {
            // fallback to evaluate
        }
        try {
            var method = interpreter.getClass().getMethod(
                "evaluate",
                EObject.class,
                String.class
            );
            return method.invoke(interpreter, context, expression);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static EObject getDiagramTarget(DDiagram diagram) {
        if (diagram == null) {
            return null;
        }
        try {
            var method = diagram.getClass().getMethod("getTarget");
            Object value = method.invoke(diagram);
            if (value instanceof EObject) {
                return (EObject) value;
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    /**
     * Check if a mapping's domain class matches the element's class.
     */
    private static boolean mappingMatchesClass(
        AbstractNodeMapping mapping,
        String className,
        String packageName
    ) {
        String domainClass = mapping.getDomainClass();
        if (domainClass == null || domainClass.isBlank()) return false;

        // Domain class can be:
        // - Short name: "OperationalActivity"
        // - Qualified: "org.polarsys.capella.core.data.oa.OperationalActivity"
        // - EPackage:Class: "oa:OperationalActivity" or "oa::OperationalActivity"

        return (
            domainClass.equals(className) ||
            domainClass.endsWith("." + className) ||
            domainClass.endsWith(":" + className) ||
            domainClass.endsWith("::" + className) ||
            domainClass.equalsIgnoreCase(packageName + "." + className) ||
            domainClass.equalsIgnoreCase(packageName + ":" + className)
        );
    }

    /**
     * Create the appropriate DDiagramElement based on mapping type.
     */
    private static DDiagramElement createDiagramElement(
        AbstractNodeMapping mapping,
        EObject semantic
    ) {
        if (mapping instanceof ContainerMapping) {
            DNodeContainer container =
                DiagramFactory.eINSTANCE.createDNodeContainer();
            container.setTarget(semantic);
            container.setActualMapping((ContainerMapping) mapping);
            return container;
        } else if (mapping instanceof NodeMapping) {
            DNode node = DiagramFactory.eINSTANCE.createDNode();
            node.setTarget(semantic);
            node.setActualMapping((NodeMapping) mapping);
            return node;
        }
        return null;
    }

    /** Create an edge by relying on semantic link creation and revealing endpoints (no internal UI reflection). */
    public static boolean createEdge(
        Session session,
        DRepresentation rep,
        EObject source,
        EObject target
    ) {
        if (
            session == null || rep == null || source == null || target == null
        ) return false;
        try {
            addElementsByUuid(
                session,
                rep,
                java.util.List.of(tryUuid(source), tryUuid(target))
            );
            DialectManager.INSTANCE.refresh(rep, new NullProgressMonitor());
            return true;
        } catch (Exception e) {
            LOG.warning("createEdge (refresh) failed: " + e.getMessage());
            return false;
        }
    }

    /** Best-effort: remove a view element corresponding to a semantic EObject from the current diagram (no semantic delete). */
    public static boolean removeViewElement(
        Session session,
        DRepresentation rep,
        EObject semantic
    ) {
        if (session == null || rep == null || semantic == null) return false;
        try {
            if (!(rep instanceof DDiagram)) {
                DialectManager.INSTANCE.refresh(rep, new NullProgressMonitor());
                return false;
            }
            return SessionSafeguards.runWrite(
                session,
                "Remove view element",
                () -> {
                    DDiagram diagram = (DDiagram) rep;
                    java.util.List<
                        org.eclipse.sirius.diagram.DDiagramElement
                    > toRemove = new java.util.ArrayList<>();
                    var it = diagram.eAllContents();
                    while (it.hasNext()) {
                        Object obj = it.next();
                        if (
                            obj instanceof
                                org.eclipse.sirius.diagram.DDiagramElement
                        ) {
                            org.eclipse.sirius.diagram.DDiagramElement el =
                                (org.eclipse.sirius.diagram.DDiagramElement) obj;
                            if (semantic.equals(el.getTarget())) {
                                toRemove.add(el);
                            }
                        }
                    }
                    if (toRemove.isEmpty()) return false;
                    for (org.eclipse.sirius.diagram.DDiagramElement el : toRemove) {
                        try {
                            org.eclipse.emf.ecore.util.EcoreUtil.delete(
                                el,
                                true
                            );
                        } catch (Exception ex) { LOG.fine("[DiagramCommands] " + ex.getMessage()); }
                    }
                    try {
                        DialectManager.INSTANCE.refresh(
                            rep,
                            new NullProgressMonitor()
                        );
                    } catch (Exception ex) { LOG.fine("[DiagramCommands] " + ex.getMessage()); }
                    return true;
                }
            );
        } catch (Exception e) {
            LOG.warning("removeViewElement failed: " + e.getMessage());
            return false;
        }
    }

    /** Best-effort: set bounds for a view element (requires active editor/GMF). Placeholder returns false for now. */
    public static boolean setViewBounds(
        Session session,
        DRepresentation rep,
        EObject semantic,
        int x,
        int y,
        int w,
        int h
    ) {
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
                    if (semantic.equals(el.getTarget())) {
                        target = el;
                        break;
                    }
                }
            }
            if (target == null) {
                // Attempt reveal then search again
                addElementsByUuid(
                    session,
                    rep,
                    java.util.List.of(tryUuid(semantic))
                );
                DialectManager.INSTANCE.refresh(rep, new NullProgressMonitor());
                it = diagram.eAllContents();
                while (it.hasNext()) {
                    Object o = it.next();
                    if (
                        o instanceof org.eclipse.sirius.diagram.DDiagramElement
                    ) {
                        var el = (org.eclipse.sirius.diagram.DDiagramElement) o;
                        if (semantic.equals(el.getTarget())) {
                            target = el;
                            break;
                        }
                    }
                }
                if (target == null) return false;
            }

            // Locate the active Sirius diagram editor and its edit part
            IWorkbenchWindow win =
                PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (win == null) return false;
            IWorkbenchPage page = win.getActivePage();
            if (page == null) return false;
            IEditorPart part = page.getActiveEditor();
            if (part == null) return false;
            org.eclipse.gef.EditPartViewer viewer = null;
            try {
                // Try Sirius Diagram Editor API
                Class<?> edClass = Class.forName(
                    "org.eclipse.sirius.diagram.ui.tools.api.editor.DDiagramEditor"
                );
                if (edClass.isInstance(part)) {
                    var m = edClass.getMethod("getDiagramGraphicalViewer");
                    Object v = m.invoke(part);
                    if (v instanceof org.eclipse.gef.EditPartViewer) viewer =
                        (org.eclipse.gef.EditPartViewer) v;
                }
            } catch (Throwable ex) { LOG.fine("[DiagramCommands] " + ex.getMessage()); }
            if (viewer == null) return false;

            IGraphicalEditPart found = null;
            for (Object epObj : viewer.getEditPartRegistry().values()) {
                if (epObj instanceof IGraphicalEditPart) {
                    IGraphicalEditPart ep = (IGraphicalEditPart) epObj;
                    try {
                        Object semEl = ep.resolveSemanticElement();
                        if (semEl == target) {
                            found = ep;
                            break;
                        }
                        Object model = ep.getModel();
                        if (model instanceof View) {
                            View view = (View) model;
                            if (target.equals(view.getElement())) {
                                found = ep;
                                break;
                            }
                        }
                    } catch (Exception ex) { LOG.fine("[DiagramCommands] " + ex.getMessage()); }
                }
            }
            if (found == null) return false;

            // Mutate notation bounds inside write transaction
            final IGraphicalEditPart epFound = found;
            return SessionSafeguards.runWrite(
                session,
                "Set view bounds",
                () -> {
                    Object model = epFound.getModel();
                    if (!(model instanceof View)) return false;
                    if (!(model instanceof Node)) return false; // only nodes have bounds
                    Node node = (Node) model;
                    LayoutConstraint lc = node.getLayoutConstraint();
                    if (!(lc instanceof Bounds)) {
                        node.setLayoutConstraint(
                            NotationFactory.eINSTANCE.createBounds()
                        );
                        lc = node.getLayoutConstraint();
                    }
                    Bounds b = (Bounds) lc;
                    if (x >= 0) b.setX(x);
                    if (y >= 0) b.setY(y);
                    if (w > 0) b.setWidth(w);
                    if (h > 0) b.setHeight(h);
                    try {
                        DialectManager.INSTANCE.refresh(
                            rep,
                            new NullProgressMonitor()
                        );
                    } catch (Exception ex) { LOG.fine("[DiagramCommands] " + ex.getMessage()); }
                    return true;
                }
            );
        } catch (Exception e) {
            LOG.warning("setViewBounds failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Find a diagram description by name with fuzzy matching support.
     * Tries exact match first, then case-insensitive, then partial match.
     */
    private static RepresentationDescription findDiagramDescription(
        Session session,
        String name
    ) {
        java.util.List<String> available = new java.util.ArrayList<>();
        RepresentationDescription fuzzyMatch = null;
        String fuzzyMatchName = null;

        for (Viewpoint vp : session.getSelectedViewpoints(false)) {
            for (RepresentationDescription rd : vp.getOwnedRepresentations()) {
                if (rd instanceof DiagramDescription) {
                    String rdName = rd.getName();
                    String rdLabel = rd.getLabel();
                    available.add(rdName);

                    // Exact match on name or label
                    if (name.equals(rdName) || name.equals(rdLabel)) {
                        LOG.fine("[DiagramCommands] Exact match found: " + rdName);
                        return rd;
                    }

                    // Case-insensitive match
                    if (name.equalsIgnoreCase(rdName) ||
                        (rdLabel != null && name.equalsIgnoreCase(rdLabel))) {
                        LOG.fine("[DiagramCommands] Case-insensitive match: " + rdName);
                        return rd;
                    }

                    // Partial match (contains) - save as fallback
                    String nameLower = name.toLowerCase();
                    String rdNameLower = rdName.toLowerCase();
                    if (rdNameLower.contains(nameLower) || nameLower.contains(rdNameLower)) {
                        if (fuzzyMatch == null) {
                            fuzzyMatch = rd;
                            fuzzyMatchName = rdName;
                        }
                    }
                }
            }
        }

        // Return fuzzy match if found (warn user about non-exact match)
        if (fuzzyMatch != null) {
            LOG.warning("[DiagramCommands] No exact match for '" + name +
                "'. Using fuzzy match: '" + fuzzyMatchName + "'");
            return fuzzyMatch;
        }

        // Not found - log helpful message
        LOG.warning("[DiagramCommands] Description '" + name + "' not found. Available: " + available);
        return null;
    }

    /**
     * Get list of available diagram description names from selected viewpoints.
     * Only returns descriptions from viewpoints that are currently activated.
     * If viewpoints are not activated, fewer descriptions will be available.
     *
     * @param session the Sirius session (must not be null for useful results)
     * @return sorted list of available diagram description names, empty if session
     *         is null or no viewpoints are selected
     */
    public static java.util.List<String> getAvailableDiagramDescriptions(Session session) {
        java.util.List<String> available = new java.util.ArrayList<>();
        if (session == null) {
            LOG.warning("[DiagramCommands] getAvailableDiagramDescriptions called with null session");
            return available;
        }

        for (Viewpoint vp : session.getSelectedViewpoints(false)) {
            for (RepresentationDescription rd : vp.getOwnedRepresentations()) {
                if (rd instanceof DiagramDescription) {
                    available.add(rd.getName());
                }
            }
        }

        java.util.Collections.sort(available);
        return available;
    }

    /**
     * Find similar descriptor names for error suggestions.
     *
     * @param available list of available descriptor names
     * @param target the name that wasn't found
     * @return list of similar names (max 3)
     */
    public static java.util.List<String> findSimilarDescriptors(
        java.util.List<String> available,
        String target
    ) {
        java.util.List<String> similar = new java.util.ArrayList<>();
        if (target == null || available == null) {
            return similar;
        }

        String targetLower = target.toLowerCase();
        final int maxSuggestions = 3;

        for (String name : available) {
            if (similar.size() >= maxSuggestions) break;

            String nameLower = name.toLowerCase();

            // Substring containment
            if (nameLower.contains(targetLower) || targetLower.contains(nameLower)) {
                similar.add(name);
                continue;
            }

            // Check if significant words match
            // Use ceiling division to require at least half the words to match
            String[] targetWords = targetLower.split("\\s+");
            int matchedWords = 0;
            for (String word : targetWords) {
                if (word.length() > 2 && nameLower.contains(word)) {
                    matchedWords++;
                }
            }
            // Ceiling division: (length + 1) / 2 ensures single-word requires 1 match
            int threshold = (targetWords.length + 1) / 2;
            if (matchedWords >= threshold && similar.size() < maxSuggestions) {
                similar.add(name);
            }
        }

        return similar;
    }

    private static EObject findById(
        org.eclipse.emf.ecore.resource.ResourceSet rs,
        String id
    ) {
        try {
            for (Resource res : rs.getResources()) {
                if (res == null) continue;
                var it = res.getAllContents();
                while (it.hasNext()) {
                    EObject eo = it.next();
                    try {
                        var cls = eo.eClass();
                        var f1 = cls.getEStructuralFeature("id");
                        if (f1 != null) {
                            Object v = eo.eGet(f1);
                            if (id.equals(v)) return eo;
                        }
                        var f2 = cls.getEStructuralFeature("uuid");
                        if (f2 != null) {
                            Object v = eo.eGet(f2);
                            if (id.equals(v)) return eo;
                        }
                        var uri = org.eclipse.emf.ecore.util.EcoreUtil.getURI(
                            eo
                        );
                        if (uri != null && id.equals(uri.fragment())) return eo;
                    } catch (Exception ex) { LOG.fine("[DiagramCommands] " + ex.getMessage()); }
                }
            }
        } catch (Exception ex) { LOG.fine("[DiagramCommands] " + ex.getMessage()); }
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
        } catch (Exception ex) { LOG.fine("[DiagramCommands] " + ex.getMessage()); }
        return null;
    }

    /** Find a representation by its UID in the active session. */
    public static DRepresentation findRepresentationByUid(
        Session session,
        String uid
    ) {
        if (session == null || uid == null) return null;
        try {
            java.util.Collection<DRepresentation> reps =
                org.eclipse.sirius.business.api.dialect.DialectManager.INSTANCE.getAllRepresentations(
                    session
                );
            for (DRepresentation r : reps) {
                if (uid.equals(r.getUid())) return r;
            }
        } catch (Exception ex) { LOG.fine("[DiagramCommands] " + ex.getMessage()); }
        return null;
    }

    /** Find a representation by name or UID. */
    public static DRepresentation findRepresentationByNameOrUid(
        Session session,
        String idOrName
    ) {
        if (
            session == null || idOrName == null || idOrName.isBlank()
        ) return null;
        try {
            java.util.Collection<DRepresentation> reps =
                org.eclipse.sirius.business.api.dialect.DialectManager.INSTANCE.getAllRepresentations(
                    session
                );
            for (DRepresentation r : reps) {
                if (idOrName.equals(r.getUid())) return r;
            }
            for (DRepresentation r : reps) {
                try {
                    java.lang.reflect.Method getName = r
                        .getClass()
                        .getMethod("getName");
                    Object n = getName.invoke(r);
                    if (idOrName.equals(String.valueOf(n))) return r;
                } catch (Throwable ex) { LOG.fine("[DiagramCommands] " + ex.getMessage()); }
            }
        } catch (Exception ex) { LOG.fine("[DiagramCommands] " + ex.getMessage()); }
        return null;
    }

    /** Rename a diagram representation. */
    public static boolean renameDiagram(
        Session session,
        DRepresentation rep,
        String newName
    ) {
        if (
            session == null ||
            rep == null ||
            newName == null ||
            newName.isBlank()
        ) return false;
        boolean nested = SessionSafeguards.isInWriteContext();
        try {
            DRepresentationDescriptor expected = ownedDescriptorOnUiThread(session, rep);
            if (expected == null) return false;
            return SessionSafeguards.runWrite(session, "Rename diagram", () -> {
                DRepresentationDescriptor descriptor = ownedDescriptor(session, rep);
                if (descriptor != expected) throw new IllegalStateException("The diagram's owned descriptor changed before rename");
                RenameRepresentationCommand command = new RenameRepresentationCommand(
                    session.getTransactionalEditingDomain(), descriptor, newName);
                if (!command.canExecute()) return false;
                // The enclosing RecordingCommand owns the single Undo operation.
                command.execute();
                requestDiagramSave(session);
                return true;
            });
        } catch (SessionSafeguards.SaveFailedAfterCommitException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.warning("renameDiagram failed: " + e.getMessage());
            if (nested) throw e;
            return false;
        }
    }

    /** Delete a diagram representation. */
    public static boolean deleteDiagram(Session session, DRepresentation rep) {
        if (session == null || rep == null) return false;
        boolean nested = SessionSafeguards.isInWriteContext();
        try {
            DRepresentationDescriptor expected = ownedDescriptorOnUiThread(session, rep);
            if (expected == null) return false;
            return SessionSafeguards.runWrite(session, "Delete diagram", () -> {
                DRepresentationDescriptor descriptor = ownedDescriptor(session, rep);
                if (descriptor != expected) throw new IllegalStateException("The diagram's owned descriptor changed before deletion");
                DeleteRepresentationCommand command = new DeleteRepresentationCommand(session, java.util.Set.of(descriptor));
                if (!command.canExecute()) return false;
                command.execute();
                if (descriptor.eContainer() != null || rep.eResource() != null) {
                    throw new IllegalStateException("Sirius could not delete the diagram representation and descriptor");
                }
                requestDiagramSave(session);
                return true;
            });
        } catch (SessionSafeguards.SaveFailedAfterCommitException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.warning("deleteDiagram failed: " + e.getMessage());
            if (nested) throw e;
            return false;
        }
    }

    private static DRepresentationDescriptor ownedDescriptor(Session session, DRepresentation representation) {
        if (!session.isOpen() || !ownsRepresentation(session, representation)) return null;
        DRepresentationDescriptor descriptor = new DRepresentationQuery(representation).getRepresentationDescriptor();
        if (descriptor == null || descriptor.eContainer() == null || descriptor.eResource() == null
            || !ownsLayoutResource(session, descriptor.eResource())
            || descriptor.getRepresentation() != representation) return null;
        return descriptor;
    }

    private static DRepresentationDescriptor ownedDescriptorOnUiThread(Session session, DRepresentation representation) {
        java.util.concurrent.atomic.AtomicReference<DRepresentationDescriptor> descriptor = new java.util.concurrent.atomic.AtomicReference<>();
        SessionSafeguards.runOnUiThread(() -> descriptor.set(ownedDescriptor(session, representation)));
        return descriptor.get();
    }

    private static void requestDiagramSave(Session session) {
        try {
            SessionSafeguards.requestSave(session);
        } catch (Exception e) {
            throw new IllegalStateException("Diagram save failed", e);
        }
    }

    /** Duplicate a diagram representation (best-effort). */
    public static boolean duplicateDiagram(
        Session session,
        DRepresentation rep,
        String newName
    ) {
        if (
            session == null ||
            rep == null ||
            newName == null ||
            newName.isBlank()
        ) return false;
        try {
            // Best-effort: try to create a new representation with same description/target, then refresh.
            return SessionSafeguards.runWrite(
                session,
                "Duplicate diagram",
                () -> {
                    try {
                        Object descriptor = null;
                        try {
                            java.lang.reflect.Method m =
                                DialectManager.class.getMethod(
                                    "getRepresentationDescriptor",
                                    DRepresentation.class
                                );
                            descriptor = m.invoke(DialectManager.INSTANCE, rep);
                        } catch (Throwable ex) { LOG.fine("[DiagramCommands] " + ex.getMessage()); }
                        if (descriptor == null) return false;
                        EObject target = null;
                        RepresentationDescription desc = null;
                        try {
                            java.lang.reflect.Method getTarget = descriptor
                                .getClass()
                                .getMethod("getTarget");
                            Object t = getTarget.invoke(descriptor);
                            if (t instanceof EObject) target = (EObject) t;
                        } catch (Throwable ex) { LOG.fine("[DiagramCommands] " + ex.getMessage()); }
                        try {
                            java.lang.reflect.Method getDesc = descriptor
                                .getClass()
                                .getMethod("getDescription");
                            Object d = getDesc.invoke(descriptor);
                            if (d instanceof RepresentationDescription) desc =
                                (RepresentationDescription) d;
                        } catch (Throwable ex) { LOG.fine("[DiagramCommands] " + ex.getMessage()); }
                        if (target == null || desc == null) return false;
                        DRepresentation copy =
                            DialectManager.INSTANCE.createRepresentation(
                                newName,
                                target,
                                desc,
                                session,
                                new NullProgressMonitor()
                            );
                        if (copy != null) {
                            DialectManager.INSTANCE.refresh(
                                copy,
                                new NullProgressMonitor()
                            );
                            return true;
                        }
                    } catch (Throwable t) {
                        LOG.warning(
                            "duplicateDiagram failed: " + t.getMessage()
                        );
                    }
                    return false;
                }
            );
        } catch (Exception e) {
            LOG.warning("duplicateDiagram failed: " + e.getMessage());
            return false;
        }
    }

    /** Reveal semantic elements in a diagram editor (reflection-based if needed). */
    public static boolean revealSemantics(
        Session session,
        DRepresentation rep,
        java.util.List<EObject> semantics
    ) {
        if (
            session == null ||
            rep == null ||
            semantics == null ||
            semantics.isEmpty()
        ) return false;
        try {
            java.lang.reflect.Method m = null;
            for (java.lang.reflect.Method mm : DialectUIManager.class.getMethods()) {
                if (mm.getName().equals("reveal")) {
                    m = mm;
                    break;
                }
            }
            if (m != null) {
                m.invoke(
                    DialectUIManager.INSTANCE,
                    session,
                    rep,
                    semantics,
                    new NullProgressMonitor()
                );
                DialectManager.INSTANCE.refresh(rep, new NullProgressMonitor());
                return true;
            }
        } catch (Exception e) {
            LOG.warning("revealSemantics failed: " + e.getMessage());
        }
        return false;
    }

    /** Style a view element (colors) if supported by the underlying style. */
    public static boolean styleViewElement(
        Session session,
        DRepresentation rep,
        EObject semantic,
        Integer fg,
        Integer bg,
        Integer bd
    ) {
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
                    if (isVisualFor(el, semantic)) {
                        target = el;
                        break;
                    }
                }
            }
            if (target == null) return false;
            final org.eclipse.sirius.viewpoint.Style[] styleHolder =
                new org.eclipse.sirius.viewpoint.Style[1];
            try {
                java.lang.reflect.Method gs = target
                    .getClass()
                    .getMethod("getStyle");
                Object st = gs.invoke(target);
                if (
                    st instanceof org.eclipse.sirius.viewpoint.Style
                ) styleHolder[0] = (org.eclipse.sirius.viewpoint.Style) st;
            } catch (Throwable ex) { LOG.fine("[DiagramCommands] " + ex.getMessage()); }
            if (styleHolder[0] == null) return false;
            RGBValues fgV = (fg != null && fg >= 0) ? toRgb(fg) : null;
            RGBValues bgV = (bg != null && bg >= 0) ? toRgb(bg) : null;
            RGBValues bdV = (bd != null && bd >= 0) ? toRgb(bd) : null;
            final org.eclipse.sirius.diagram.DDiagramElement targetRef = target;
            return SessionSafeguards.runWrite(
                session,
                "Style view element",
                () -> {
                    boolean changed = false;
                    try {
                        if (fgV != null) {
                            var m = styleHolder[0].getClass().getMethod(
                                "setLabelColor",
                                RGBValues.class
                            );
                            m.invoke(styleHolder[0], fgV);
                            changed = true;
                        }
                    } catch (Throwable ex) { LOG.fine("[DiagramCommands] " + ex.getMessage()); }
                    try {
                        if (bgV != null) {
                            var m = styleHolder[0].getClass().getMethod(
                                "setColor",
                                RGBValues.class
                            );
                            m.invoke(styleHolder[0], bgV);
                            changed = true;
                        }
                    } catch (Throwable ex) { LOG.fine("[DiagramCommands] " + ex.getMessage()); }
                    try {
                        if (bdV != null) {
                            var m = styleHolder[0].getClass().getMethod(
                                "setBorderColor",
                                RGBValues.class
                            );
                            m.invoke(styleHolder[0], bdV);
                            changed = true;
                        }
                    } catch (Throwable ex) { LOG.fine("[DiagramCommands] " + ex.getMessage()); }
                    // Best-effort: show/hide icon if supported by style
                    try {
                        java.lang.reflect.Method m =
                            styleHolder[0].getClass().getMethod(
                                "setShowIcon",
                                boolean.class
                            );
                        // If method exists, default to true unless explicitly set false by caller
                        Boolean showIcon = null;
                        try {
                            // Try to retrieve a cached value from a thread-local if present; otherwise skip (set below if provided)
                            showIcon =
                                (Boolean) org.polarsys.capella.ai.tools.DiagramCommands.ThreadLocalStyling.get(
                                    "showIcon"
                                );
                        } catch (Throwable ex) { LOG.fine("[DiagramCommands] " + ex.getMessage()); }
                        if (showIcon != null) {
                            m.invoke(styleHolder[0], showIcon.booleanValue());
                            changed = true;
                        }
                    } catch (Throwable ex) { LOG.fine("[DiagramCommands] " + ex.getMessage()); }
                    // Best-effort: set label text on representation element if supported
                    try {
                        String labelText = null;
                        try {
                            labelText =
                                (String) org.polarsys.capella.ai.tools.DiagramCommands.ThreadLocalStyling.get(
                                    "labelText"
                                );
                        } catch (Throwable ex) { LOG.fine("[DiagramCommands] " + ex.getMessage()); }
                        if (labelText != null) {
                            try {
                                var m = targetRef
                                    .getClass()
                                    .getMethod("setName", String.class);
                                m.invoke(targetRef, labelText);
                                changed = true;
                            } catch (Throwable ex) { LOG.fine("[DiagramCommands] nested: " + ex.getMessage()); }
                        }
                    } catch (Throwable ex) { LOG.fine("[DiagramCommands] " + ex.getMessage()); }
                    if (changed) {
                        try {
                            DialectManager.INSTANCE.refresh(
                                rep,
                                new NullProgressMonitor()
                            );
                        } catch (Exception ex) { LOG.fine("[DiagramCommands] " + ex.getMessage()); }
                    }
                    return changed;
                }
            );
        } catch (Exception e) {
            LOG.warning("styleViewElement failed: " + e.getMessage());
            return false;
        }
    }

    /** Internal helper to pass additional styling flags without API changes. */
    public static final class ThreadLocalStyling {

        private static final ThreadLocal<java.util.Map<String, Object>> TL =
            ThreadLocal.withInitial(java.util.HashMap::new);

        public static void put(String k, Object v) {
            TL.get().put(k, v);
        }

        public static Object get(String k) {
            return TL.get().get(k);
        }

        public static void clear() {
            TL.get().clear();
        }
    }

    private static RGBValues toRgb(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return RGBValues.create(r, g, b);
    }

    private static void tryReveal(
        Session session,
        DRepresentation rep,
        java.util.List<String> ids,
        org.eclipse.emf.ecore.resource.ResourceSet rs
    ) {
        try {
            java.util.List<EObject> eos = new java.util.ArrayList<>();
            for (String id : ids) {
                EObject eo = findById(rs, id);
                if (eo != null) eos.add(eo);
            }
            if (eos.isEmpty()) return;
            // Use reflection to support multiple Sirius versions
            java.lang.reflect.Method m = null;
            for (java.lang.reflect.Method mm : DialectUIManager.class.getMethods()) {
                if (mm.getName().equals("reveal")) {
                    m = mm;
                    break;
                }
            }
            if (m != null) {
                m.invoke(
                    DialectUIManager.INSTANCE,
                    session,
                    rep,
                    eos,
                    new NullProgressMonitor()
                );
            }
        } catch (Exception ex) { LOG.fine("[DiagramCommands] " + ex.getMessage()); }
    }

    private static boolean isVisualFor(DDiagramElement el, EObject semantic) {
        if (el == null || semantic == null) return false;
        EObject target = el.getTarget();
        if (target == null) return false;

        // 1. Direct equality
        if (semantic.equals(target)) return true;

        // 2. Part-to-Component mapping (Common in SAB/LAB/PAB)
        if (target instanceof Part) {
            EObject abstractType = ((Part) target).getAbstractType();
            if (semantic.equals(abstractType)) return true;
        }

        // 3. UUID matching (Fallback for proxies/reloads)
        String sId = tryUuid(semantic);
        String tId = tryUuid(target);
        if (sId != null && sId.equals(tId)) return true;

        return false;
    }

    private static String tryUuid(EObject eo) {
        try {
            var f = eo.eClass().getEStructuralFeature("uuid");
            if (f != null) {
                Object v = eo.eGet(f);
                if (v != null) return String.valueOf(v);
            }
        } catch (Exception ex) { LOG.fine("[DiagramCommands] " + ex.getMessage()); }
        return String.valueOf(eo.hashCode());
    }
}
