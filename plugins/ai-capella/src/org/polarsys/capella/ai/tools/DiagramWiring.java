package org.polarsys.capella.ai.tools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.gmf.runtime.notation.Bounds;
import org.eclipse.gmf.runtime.notation.Diagram;
import org.eclipse.gmf.runtime.notation.Edge;
import org.eclipse.gmf.runtime.notation.Node;
import org.eclipse.gmf.runtime.notation.NotationFactory;
import org.eclipse.gmf.runtime.notation.NotationPackage;
import org.eclipse.gmf.runtime.notation.RelativeBendpoints;
import org.eclipse.gmf.runtime.notation.Routing;
import org.eclipse.gmf.runtime.notation.RoutingStyle;
import org.eclipse.gmf.runtime.notation.View;
import org.eclipse.gmf.runtime.notation.datatype.RelativeBendpoint;
import org.eclipse.sirius.diagram.DEdge;
import org.eclipse.sirius.diagram.EdgeRouting;
import org.eclipse.sirius.diagram.EdgeStyle;

/**
 * Tidies connections after shapes move: orthogonal routing, no stale manual bends, and border
 * ports on the side facing what they connect to. GMF computes the rectilinear path when the
 * diagram is drawn. Callers run this inside the layout's write transaction.
 */
final class DiagramWiring {
    private DiagramWiring() {}

    private enum Side { EAST, WEST, NORTH, SOUTH }

    static void tidy(Diagram diagram) {
        List<Edge> edges = new ArrayList<>();
        for (Object candidate : diagram.getEdges()) if (candidate instanceof Edge edge) edges.add(edge);
        facePorts(edges);
        for (Edge edge : edges) route(edge);
    }

    private static void route(Edge edge) {
        orthogonal(edge);
        RelativeBendpoints anchors = NotationFactory.eINSTANCE.createRelativeBendpoints();
        anchors.setPoints(List.of(new RelativeBendpoint(0, 0, 0, 0), new RelativeBendpoint(0, 0, 0, 0)));
        edge.setBendpoints(anchors);
    }

    /** Rectilinear GMF routing and Manhattan Sirius routing, kept by Sirius refresh. */
    static void orthogonal(Edge edge) {
        RoutingStyle routing = (RoutingStyle) edge.getStyle(NotationPackage.Literals.ROUTING_STYLE);
        if (routing == null) {
            routing = NotationFactory.eINSTANCE.createRoutingStyle();
            edge.getStyles().add(routing);
        }
        routing.setRouting(Routing.RECTILINEAR_LITERAL);
        EdgeStyle style = edge.getElement() instanceof DEdge view ? view.getOwnedStyle() : null;
        if (style != null) {
            style.setRoutingStyle(EdgeRouting.MANHATTAN_LITERAL);
            // Sirius resets non-custom style features to the mapping default on refresh.
            if (!style.getCustomFeatures().contains("routingStyle")) style.getCustomFeatures().add("routingStyle");
        }
    }

    /** Ports connected mostly to the east sit on their parent's east border, and so on; ports on one side keep peer order. */
    private static void facePorts(List<Edge> edges) {
        Map<Node, int[]> pull = new LinkedHashMap<>();
        for (Edge edge : edges) {
            pullTowards(pull, edge.getSource(), edge.getTarget());
            pullTowards(pull, edge.getTarget(), edge.getSource());
        }
        Map<Node, Map<Side, List<Node>>> placement = new LinkedHashMap<>();
        Map<Node, Integer> order = new LinkedHashMap<>();
        for (var entry : pull.entrySet()) {
            Node port = entry.getKey();
            int dx = entry.getValue()[0], dy = entry.getValue()[1];
            if (dx == 0 && dy == 0) continue;
            Side side = Math.abs(dx) >= Math.abs(dy) ? (dx > 0 ? Side.EAST : Side.WEST) : (dy > 0 ? Side.SOUTH : Side.NORTH);
            Node parent = (Node) port.eContainer();
            placement.computeIfAbsent(parent, key -> new LinkedHashMap<>()).computeIfAbsent(side, key -> new ArrayList<>()).add(port);
            order.put(port, side == Side.EAST || side == Side.WEST ? dy : dx);
        }
        placement.forEach((parent, sides) -> {
            Bounds frame = bounds(parent);
            if (frame == null || frame.getWidth() <= 0 || frame.getHeight() <= 0) return;
            sides.forEach((side, ports) -> {
                ports.sort(Comparator.comparingInt(order::get));
                for (int index = 0; index < ports.size(); index++) {
                    Bounds port = bounds(ports.get(index));
                    if (port == null || port.getWidth() <= 0 || port.getHeight() <= 0) continue;
                    boolean vertical = side == Side.EAST || side == Side.WEST;
                    int length = vertical ? frame.getHeight() : frame.getWidth();
                    int size = vertical ? port.getHeight() : port.getWidth();
                    int along = Math.max(0, Math.min(length - size, (index + 1) * length / (ports.size() + 1) - size / 2));
                    switch (side) {
                        case EAST -> { port.setX(frame.getWidth() - port.getWidth() / 2); port.setY(along); }
                        case WEST -> { port.setX(-port.getWidth() / 2); port.setY(along); }
                        case SOUTH -> { port.setY(frame.getHeight() - port.getHeight() / 2); port.setX(along); }
                        case NORTH -> { port.setY(-port.getHeight() / 2); port.setX(along); }
                    }
                }
            });
        });
    }

    private static void pullTowards(Map<Node, int[]> pull, View end, View peer) {
        if (!(end instanceof Node port) || !isBorderNode(port) || peer == null) return;
        int[] from = center(port.eContainer()), to = center(isBorderNode(peer) ? peer.eContainer() : peer);
        if (from == null || to == null) return;
        int[] sum = pull.computeIfAbsent(port, key -> new int[2]);
        sum[0] += to[0] - from[0];
        sum[1] += to[1] - from[1];
    }

    private static boolean isBorderNode(EObject view) {
        if (!(view instanceof Node node) || !(node.eContainer() instanceof Node)) return false;
        EObject element = node.getElement();
        return element != null && element.eContainingFeature() != null
            && "ownedBorderedNodes".equals(element.eContainingFeature().getName());
    }

    /** Absolute centre, summing the offsets of enclosing shapes. */
    private static int[] center(EObject view) {
        if (!(view instanceof Node node) || bounds(node) == null) return null;
        Bounds own = bounds(node);
        int x = own.getX() + Math.max(0, own.getWidth()) / 2, y = own.getY() + Math.max(0, own.getHeight()) / 2;
        for (EObject owner = node.eContainer(); owner instanceof Node parent; owner = parent.eContainer()) {
            Bounds frame = bounds(parent);
            if (frame != null) { x += frame.getX(); y += frame.getY(); }
        }
        return new int[] { x, y };
    }

    private static Bounds bounds(Node node) {
        return node.getLayoutConstraint() instanceof Bounds bounds ? bounds : null;
    }
}
