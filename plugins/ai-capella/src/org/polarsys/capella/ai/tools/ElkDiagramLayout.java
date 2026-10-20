package org.polarsys.capella.ai.tools;

import java.util.IdentityHashMap;
import java.util.Map;

import org.eclipse.elk.alg.layered.options.LayeredMetaDataProvider;
import org.eclipse.elk.alg.layered.options.LayeredOptions;
import org.eclipse.elk.core.RecursiveGraphLayoutEngine;
import org.eclipse.elk.core.data.LayoutMetaDataService;
import org.eclipse.elk.core.math.ElkPadding;
import org.eclipse.elk.core.options.CoreOptions;
import org.eclipse.elk.core.options.Direction;
import org.eclipse.elk.core.options.EdgeRouting;
import org.eclipse.elk.core.options.HierarchyHandling;
import org.eclipse.elk.core.options.PortConstraints;
import org.eclipse.elk.core.util.BasicProgressMonitor;
import org.eclipse.elk.graph.ElkConnectableShape;
import org.eclipse.elk.graph.ElkEdge;
import org.eclipse.elk.graph.ElkNode;
import org.eclipse.elk.graph.ElkPort;
import org.eclipse.elk.graph.util.ElkGraphUtil;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.gmf.runtime.notation.Bounds;
import org.eclipse.gmf.runtime.notation.Diagram;
import org.eclipse.gmf.runtime.notation.Edge;
import org.eclipse.gmf.runtime.notation.Node;
import org.eclipse.gmf.runtime.notation.NotationFactory;
import org.eclipse.gmf.runtime.notation.View;
import org.eclipse.sirius.diagram.DNode;
import org.eclipse.sirius.diagram.DNodeContainer;
import org.eclipse.sirius.diagram.DNodeList;
import org.eclipse.sirius.viewpoint.DSemanticDecorator;

/**
 * Hierarchical layered layout of a persisted Sirius/GMF diagram using Eclipse ELK: shapes flow
 * left to right along their connections, containers grow to fit their children, and nothing
 * overlaps. Only notation bounds change; callers run this in a write transaction and tidy the
 * wiring afterwards with {@link DiagramWiring}.
 */
final class ElkDiagramLayout {
    /** Height of a Sirius container's title band above its compartment. Calibrate against real editors. */
    static final int HEADER = 30;
    /** Size assumed for shapes whose notation keeps Sirius's "default size" marker (-1). */
    private static final int DEFAULT_WIDTH = 150, DEFAULT_HEIGHT = 60;
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
        Map<Node, ElkNode> shapes = new IdentityHashMap<>();
        Map<Node, ElkPort> ports = new IdentityHashMap<>();
        Map<Node, Integer> offsets = new IdentityHashMap<>();
        addChildren(notation, root, 0, shapes, ports, offsets);
        if (shapes.isEmpty()) return false;
        for (Object candidate : notation.getEdges()) {
            if (!(candidate instanceof Edge edge)) continue;
            ElkConnectableShape source = end(edge.getSource(), shapes, ports), target = end(edge.getTarget(), shapes, ports);
            if (source == null || target == null || source == target) continue;
            ElkEdge elkEdge = ElkGraphUtil.createSimpleEdge(source, target);
            ElkGraphUtil.updateContainment(elkEdge);
        }
        new RecursiveGraphLayoutEngine().layout(root, new BasicProgressMonitor());
        shapes.forEach((node, elk) -> {
            Bounds bounds = bounds(node);
            bounds.setX((int) Math.round(elk.getX()));
            bounds.setY((int) Math.round(elk.getY()) - offsets.get(node));
            if (!elk.getChildren().isEmpty()) {
                bounds.setWidth((int) Math.round(elk.getWidth()));
                bounds.setHeight((int) Math.round(elk.getHeight()));
            }
        });
        return true;
    }

    private static void configure(ElkNode node) {
        node.setProperty(CoreOptions.DIRECTION, Direction.RIGHT);
        node.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL);
        node.setProperty(CoreOptions.SPACING_NODE_NODE, 40.0);
        node.setProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS, 60.0);
    }

    /**
     * Adds the shapes under {@code view} to {@code parent}. Children reached through a compartment
     * (a child view of the same element) sit below the container's title band, so their notation
     * y is the ELK y minus {@code offset}.
     */
    private static void addChildren(View view, ElkNode parent, int offset, Map<Node, ElkNode> shapes,
            Map<Node, ElkPort> ports, Map<Node, Integer> offsets) {
        EObject owner = view.getElement();
        for (Object candidate : view.getChildren()) {
            if (!(candidate instanceof Node child)) continue;
            EObject element = child.getElement();
            if (view instanceof Node && (!child.isSetElement() || element == owner)) {
                // Compartment or label decoration of the same element.
                addChildren(child, parent, HEADER, shapes, ports, offsets);
            } else if (isBorderNode(element)) {
                Bounds bounds = bounds(child);
                if (bounds == null || !(view instanceof Node) || shapes.get(view) == null) continue;
                ElkPort port = ElkGraphUtil.createPort(shapes.get(view));
                port.setDimensions(Math.max(1, bounds.getWidth()), Math.max(1, bounds.getHeight()));
                ports.put(child, port);
            } else if (isShape(element) && bounds(child) != null) {
                Bounds bounds = bounds(child);
                ElkNode node = ElkGraphUtil.createNode(parent);
                node.setDimensions(bounds.getWidth() > 0 ? bounds.getWidth() : DEFAULT_WIDTH,
                    bounds.getHeight() > 0 ? bounds.getHeight() : DEFAULT_HEIGHT);
                node.setProperty(CoreOptions.PORT_CONSTRAINTS, PortConstraints.FREE);
                node.setProperty(CoreOptions.PADDING, new ElkPadding(HEADER + GAP / 2.0, GAP, GAP, GAP));
                configure(node);
                shapes.put(child, node);
                offsets.put(child, offset);
                addChildren(child, node, 0, shapes, ports, offsets);
            }
        }
    }

    private static ElkConnectableShape end(View view, Map<Node, ElkNode> shapes, Map<Node, ElkPort> ports) {
        if (!(view instanceof Node node)) return null;
        return ports.containsKey(node) ? ports.get(node) : shapes.get(node);
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

    private static synchronized void registerLayered() {
        // OSGi hides ELK's ServiceLoader registration across bundles; register explicitly once.
        LayoutMetaDataService service = LayoutMetaDataService.getInstance();
        if (service.getAlgorithmData(LayeredOptions.ALGORITHM_ID) == null) {
            service.registerLayoutMetaDataProviders(new LayeredMetaDataProvider());
        }
    }
}
