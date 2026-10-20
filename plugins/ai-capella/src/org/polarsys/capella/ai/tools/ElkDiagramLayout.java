package org.polarsys.capella.ai.tools;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.elk.alg.layered.options.LayeredMetaDataProvider;
import org.eclipse.elk.alg.layered.options.LayeredOptions;
import org.eclipse.elk.core.RecursiveGraphLayoutEngine;
import org.eclipse.elk.core.data.LayoutMetaDataService;
import org.eclipse.elk.core.math.ElkPadding;
import org.eclipse.elk.core.math.KVector;
import org.eclipse.elk.core.options.CoreOptions;
import org.eclipse.elk.core.options.Direction;
import org.eclipse.elk.core.options.EdgeLabelPlacement;
import org.eclipse.elk.core.options.EdgeRouting;
import org.eclipse.elk.core.options.HierarchyHandling;
import org.eclipse.elk.core.options.PortConstraints;
import org.eclipse.elk.core.options.SizeConstraint;
import org.eclipse.elk.core.util.BasicProgressMonitor;
import org.eclipse.elk.graph.ElkBendPoint;
import org.eclipse.elk.graph.ElkConnectableShape;
import org.eclipse.elk.graph.ElkEdge;
import org.eclipse.elk.graph.ElkEdgeSection;
import org.eclipse.elk.graph.ElkLabel;
import org.eclipse.elk.graph.ElkNode;
import org.eclipse.elk.graph.ElkPort;
import org.eclipse.elk.graph.ElkShape;
import org.eclipse.elk.graph.util.ElkGraphUtil;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.gmf.runtime.notation.Bounds;
import org.eclipse.gmf.runtime.notation.Diagram;
import org.eclipse.gmf.runtime.notation.Edge;
import org.eclipse.gmf.runtime.notation.Location;
import org.eclipse.gmf.runtime.notation.Node;
import org.eclipse.gmf.runtime.notation.NotationFactory;
import org.eclipse.gmf.runtime.notation.RelativeBendpoints;
import org.eclipse.gmf.runtime.notation.View;
import org.eclipse.gmf.runtime.notation.datatype.RelativeBendpoint;
import org.eclipse.sirius.diagram.DDiagramElement;
import org.eclipse.sirius.diagram.DNode;
import org.eclipse.sirius.diagram.DNodeContainer;
import org.eclipse.sirius.diagram.DNodeList;
import org.eclipse.sirius.viewpoint.DSemanticDecorator;
import org.polarsys.capella.common.data.modellingcore.AbstractNamedElement;

/**
 * Hierarchical layered layout of a persisted Sirius/GMF diagram using Eclipse ELK: shapes flow
 * left to right along their connections, containers grow to fit their children, ports sit on the
 * border facing their wiring, and connections follow ELK's orthogonal routes around shapes.
 * Every laid-out shape gets an explicit size so the drawing matches the plan. Callers run this
 * inside a write transaction.
 */
final class ElkDiagramLayout {
    /** Height of a Sirius container's title band above its compartment. Calibrate against real editors. */
    static final int HEADER = 30;
    // ponytail: label width is estimated from character count, measure with SWT font metrics if labels clip.
    private static final int CHAR_WIDTH = 8, LABEL_PADDING = 50, MIN_WIDTH = 120, MAX_WIDTH = 640;
    private static final int CONTAINER_HEIGHT = 60, NODE_HEIGHT = 50, LABEL_HEIGHT = 21;
    /** Default Sirius border node size used by Capella ports. */
    private static final int PORT_SIZE = 10;
    private static final int GAP = 20;

    private ElkDiagramLayout() {}

    /** Lays out every shape of the diagram. Returns false when the diagram has no shapes. */
    static boolean apply(Diagram notation) {
        registerLayered();
        ElkNode root = ElkGraphUtil.createGraph();
        root.setProperty(CoreOptions.ALGORITHM, LayeredOptions.ALGORITHM_ID);
        root.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.INCLUDE_CHILDREN);
        root.setProperty(CoreOptions.PADDING, new ElkPadding(GAP));
        configure(root);
        Graph graph = new Graph();
        graph.isolated = isolatedShapes(notation);
        addChildren(notation, root, 0, graph);
        if (graph.shapes.isEmpty() && graph.isolated.isEmpty()) return false;
        for (Object candidate : notation.getEdges()) {
            if (!(candidate instanceof Edge edge)) continue;
            ElkConnectableShape source = graph.end(edge.getSource()), target = graph.end(edge.getTarget());
            if (source == null || target == null || source == target) continue;
            ElkEdge elkEdge = ElkGraphUtil.createSimpleEdge(source, target);
            ElkGraphUtil.updateContainment(elkEdge);
            graph.edges.put(edge, elkEdge);
            Node label = centerLabel(edge);
            String text = edge.getElement() instanceof DDiagramElement view ? view.getName() : null;
            if (label != null && text != null && !text.isBlank()) {
                // ELK reserves room for the label so parallel wiring and shapes keep clear of it.
                ElkLabel elkLabel = ElkGraphUtil.createLabel(text, elkEdge);
                elkLabel.setDimensions(text.length() * CHAR_WIDTH + 20, LABEL_HEIGHT);
                elkLabel.setProperty(CoreOptions.EDGE_LABELS_PLACEMENT, EdgeLabelPlacement.CENTER);
                graph.labels.put(label, elkLabel);
            }
        }
        new RecursiveGraphLayoutEngine().layout(root, new BasicProgressMonitor());
        graph.shapes.forEach((node, elk) -> {
            Bounds bounds = bounds(node);
            bounds.setX(round(elk.getX()));
            bounds.setY(round(elk.getY()) - graph.offsets.get(node));
            bounds.setWidth(round(elk.getWidth()));
            bounds.setHeight(round(elk.getHeight()));
        });
        graph.ports.forEach((node, elk) -> {
            Bounds bounds = bounds(node);
            bounds.setX(round(elk.getX()));
            bounds.setY(round(elk.getY()));
        });
        graph.edges.forEach((edge, elk) -> writeRoute(edge, elk, graph));
        pack(graph.isolated, root);
        return true;
    }

    /** Top-level shapes with no wiring and no child shapes; ELK layered would stack them in one column. */
    private static List<Node> isolatedShapes(Diagram notation) {
        java.util.Set<Node> wired = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object candidate : notation.getEdges()) {
            if (!(candidate instanceof Edge edge)) continue;
            for (View end : List.of(edge.getSource(), edge.getTarget())) {
                for (EObject view = end; view instanceof Node node; view = node.eContainer()) {
                    if (node.eContainer() == notation) wired.add(node);
                }
            }
        }
        List<Node> isolated = new ArrayList<>();
        for (Object candidate : notation.getChildren()) {
            if (candidate instanceof Node node && isShape(node.getElement()) && bounds(node) != null
                && !wired.contains(node) && !hasChildShapes(node)) isolated.add(node);
        }
        return isolated;
    }

    private static boolean hasChildShapes(Node node) {
        for (Object candidate : node.getChildren()) {
            if (!(candidate instanceof Node child)) continue;
            if (child.isSetElement() && child.getElement() != node.getElement()) {
                if (isShape(child.getElement()) && !isBorderNode(child.getElement())) return true;
            } else if (hasChildShapes(child)) return true;
        }
        return false;
    }

    /** Rows below the laid-out drawing, wide enough for a landscape result. */
    private static void pack(List<Node> isolated, ElkNode root) {
        if (isolated.isEmpty()) return;
        double area = 0;
        for (Node node : isolated) {
            Bounds bounds = bounds(node);
            if (bounds.getWidth() <= 0) bounds.setWidth(labelWidth(node.getElement()));
            if (bounds.getHeight() <= 0) bounds.setHeight(CONTAINER_HEIGHT);
            area += (bounds.getWidth() + 40.0) * (bounds.getHeight() + 40.0);
        }
        int rowWidth = (int) Math.max(root.getWidth() - GAP, Math.sqrt(area * 2.5));
        int x = GAP, y = root.getChildren().isEmpty() ? GAP : round(root.getHeight()) + GAP, rowHeight = 0;
        for (Node node : isolated) {
            Bounds bounds = bounds(node);
            if (x > GAP && x + bounds.getWidth() > rowWidth) { x = GAP; y += rowHeight + 40; rowHeight = 0; }
            bounds.setX(x);
            bounds.setY(y);
            x += bounds.getWidth() + 40;
            rowHeight = Math.max(rowHeight, bounds.getHeight());
        }
    }

    /** GMF bendpoints are offsets from the centres of the connected shapes; ELK points are made absolute first. */
    private static void writeRoute(Edge edge, ElkEdge elk, Graph graph) {
        DiagramWiring.orthogonal(edge);
        if (elk.getSections().isEmpty()) return;
        ElkEdgeSection section = elk.getSections().get(0);
        double[] offset = absolute(elk.getContainingNode());
        List<double[]> points = new ArrayList<>();
        points.add(new double[] { section.getStartX(), section.getStartY() });
        for (ElkBendPoint bend : section.getBendPoints()) points.add(new double[] { bend.getX(), bend.getY() });
        points.add(new double[] { section.getEndX(), section.getEndY() });
        double[] source = center((ElkShape) elk.getSources().get(0)), target = center((ElkShape) elk.getTargets().get(0));
        List<RelativeBendpoint> relative = new ArrayList<>();
        for (double[] point : points) {
            double x = point[0] + offset[0], y = point[1] + offset[1];
            relative.add(new RelativeBendpoint(round(x - source[0]), round(y - source[1]), round(x - target[0]), round(y - target[1])));
        }
        RelativeBendpoints bendpoints = NotationFactory.eINSTANCE.createRelativeBendpoints();
        bendpoints.setPoints(relative);
        edge.setBendpoints(bendpoints);
        Node label = centerLabel(edge);
        ElkLabel placed = label == null ? null : graph.labels.get(label);
        if (placed != null) {
            List<double[]> absolute = new ArrayList<>();
            for (double[] point : points) absolute.add(new double[] { point[0] + offset[0], point[1] + offset[1] });
            placeLabel(label, absolute, placed.getX() + offset[0] + placed.getWidth() / 2, placed.getY() + offset[1] + placed.getHeight() / 2);
        }
    }

    /**
     * Sirius draws a centre label at the route's half-length point plus its stored offset, rotated
     * into the direction of the segment holding that point (measured in a live editor). Store the
     * inverse rotation of the wanted displacement.
     */
    private static void placeLabel(Node label, List<double[]> route, double centerX, double centerY) {
        double total = 0;
        for (int i = 1; i < route.size(); i++) total += Math.hypot(route.get(i)[0] - route.get(i - 1)[0], route.get(i)[1] - route.get(i - 1)[1]);
        double remaining = total / 2;
        for (int i = 1; i < route.size(); i++) {
            double[] from = route.get(i - 1), to = route.get(i);
            double dx = to[0] - from[0], dy = to[1] - from[1], length = Math.hypot(dx, dy);
            if (length == 0) continue;
            if (remaining <= length || i == route.size() - 1) {
                double t = Math.min(1, remaining / length), cos = dx / length, sin = dy / length;
                double wantX = centerX - (from[0] + dx * t), wantY = centerY - (from[1] + dy * t);
                Location location = label.getLayoutConstraint() instanceof Location existing ? existing : NotationFactory.eINSTANCE.createLocation();
                location.setX(round(wantX * cos + wantY * sin));
                location.setY(round(-wantX * sin + wantY * cos));
                label.setLayoutConstraint(location);
                return;
            }
            remaining -= length;
        }
    }

    /** Sirius's edge name label (visual id 6001). */
    private static Node centerLabel(Edge edge) {
        for (Object child : edge.getChildren()) if (child instanceof Node node && "6001".equals(node.getType())) return node;
        return null;
    }

    private static void configure(ElkNode node) {
        node.setProperty(CoreOptions.DIRECTION, Direction.RIGHT);
        node.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL);
        node.setProperty(CoreOptions.SPACING_NODE_NODE, 40.0);
        node.setProperty(CoreOptions.SPACING_EDGE_NODE, 20.0);
        node.setProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS, 60.0);
    }

    /**
     * Adds the shapes under {@code view} to {@code parent}. Children reached through a compartment
     * (a child view of the same element) sit below the container's title band, so their notation
     * y is the ELK y minus {@code offset}.
     */
    private static void addChildren(View view, ElkNode parent, int offset, Graph graph) {
        EObject owner = view.getElement();
        for (Object candidate : view.getChildren()) {
            if (!(candidate instanceof Node child)) continue;
            EObject element = child.getElement();
            if (view instanceof Node && (!child.isSetElement() || element == owner)) {
                // Compartment or label decoration of the same element.
                addChildren(child, parent, HEADER, graph);
            } else if (isBorderNode(element)) {
                Bounds bounds = bounds(child);
                ElkNode shape = view instanceof Node node ? graph.shapes.get(node) : null;
                if (bounds == null || shape == null) continue;
                // Sirius stores -1 for its default size; lay out and write the size it draws.
                if (bounds.getWidth() <= 0) bounds.setWidth(PORT_SIZE);
                if (bounds.getHeight() <= 0) bounds.setHeight(PORT_SIZE);
                ElkPort port = ElkGraphUtil.createPort(shape);
                port.setDimensions(bounds.getWidth(), bounds.getHeight());
                // Sirius draws border nodes centred on the border.
                port.setProperty(CoreOptions.PORT_BORDER_OFFSET, -bounds.getWidth() / 2.0);
                graph.ports.put(child, port);
            } else if (isShape(element) && bounds(child) != null && !graph.isolated.contains(child)) {
                Bounds bounds = bounds(child);
                boolean container = !(element instanceof DNode);
                int width = bounds.getWidth() > 0 ? bounds.getWidth() : labelWidth(element);
                int height = bounds.getHeight() > 0 ? bounds.getHeight() : container ? CONTAINER_HEIGHT : NODE_HEIGHT;
                ElkNode node = ElkGraphUtil.createNode(parent);
                node.setDimensions(width, height);
                node.setProperty(CoreOptions.NODE_SIZE_CONSTRAINTS, EnumSet.of(SizeConstraint.MINIMUM_SIZE));
                node.setProperty(CoreOptions.NODE_SIZE_MINIMUM, new KVector(width, height));
                node.setProperty(CoreOptions.PORT_CONSTRAINTS, PortConstraints.FREE);
                node.setProperty(CoreOptions.PADDING, new ElkPadding(HEADER + GAP / 2.0, GAP, GAP, GAP));
                configure(node);
                graph.shapes.put(child, node);
                graph.offsets.put(child, offset);
                addChildren(child, node, 0, graph);
            }
        }
    }

    private static int labelWidth(EObject element) {
        // Sirius fills the view name on refresh; fall back to the semantic name before that.
        String name = element instanceof DDiagramElement view ? view.getName() : null;
        if ((name == null || name.isBlank()) && element instanceof DSemanticDecorator decorator
            && decorator.getTarget() instanceof AbstractNamedElement named) name = named.getName();
        if (name == null) name = "";
        return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, name.length() * CHAR_WIDTH + LABEL_PADDING));
    }

    private static double[] absolute(ElkNode node) {
        double x = 0, y = 0;
        for (ElkNode current = node; current != null; current = current.getParent()) {
            x += current.getX();
            y += current.getY();
        }
        return new double[] { x, y };
    }

    private static double[] center(ElkShape shape) {
        // A port is positioned relative to its node; a node relative to its parent.
        double[] origin = absolute(shape instanceof ElkPort port ? port.getParent() : ((ElkNode) shape).getParent());
        return new double[] { origin[0] + shape.getX() + shape.getWidth() / 2, origin[1] + shape.getY() + shape.getHeight() / 2 };
    }

    private static boolean isShape(EObject element) {
        return element instanceof DSemanticDecorator decorator && decorator.getTarget() != null
            && (element instanceof DNode || element instanceof DNodeContainer || element instanceof DNodeList);
    }

    private static boolean isBorderNode(EObject element) {
        return element != null && element.eContainingFeature() != null
            && "ownedBorderedNodes".equals(element.eContainingFeature().getName());
    }

    private static Bounds bounds(Node node) {
        if (node.getLayoutConstraint() instanceof Bounds bounds) return bounds;
        if (node.getLayoutConstraint() != null) return null;
        Bounds created = NotationFactory.eINSTANCE.createBounds();
        created.setWidth(-1);
        created.setHeight(-1);
        node.setLayoutConstraint(created);
        return created;
    }

    private static int round(double value) { return (int) Math.round(value); }

    private static synchronized void registerLayered() {
        // OSGi hides ELK's ServiceLoader registration across bundles; register explicitly once.
        LayoutMetaDataService service = LayoutMetaDataService.getInstance();
        if (service.getAlgorithmData(LayeredOptions.ALGORITHM_ID) == null) {
            service.registerLayoutMetaDataProviders(new LayeredMetaDataProvider());
        }
    }

    private static final class Graph {
        final Map<Node, ElkNode> shapes = new IdentityHashMap<>();
        final Map<Node, ElkPort> ports = new IdentityHashMap<>();
        final Map<Node, Integer> offsets = new IdentityHashMap<>();
        final Map<Edge, ElkEdge> edges = new IdentityHashMap<>();
        final Map<Node, ElkLabel> labels = new IdentityHashMap<>();
        List<Node> isolated = List.of();

        ElkConnectableShape end(View view) {
            if (!(view instanceof Node node)) return null;
            return ports.containsKey(node) ? ports.get(node) : shapes.get(node);
        }
    }
}
