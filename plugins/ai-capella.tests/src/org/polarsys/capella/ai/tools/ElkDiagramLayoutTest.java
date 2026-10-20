package org.polarsys.capella.ai.tools;

import static org.junit.Assert.*;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.gmf.runtime.notation.Bounds;
import org.eclipse.gmf.runtime.notation.Diagram;
import org.eclipse.gmf.runtime.notation.Edge;
import org.eclipse.gmf.runtime.notation.Node;
import org.eclipse.gmf.runtime.notation.NotationFactory;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.diagram.DNode;
import org.eclipse.sirius.diagram.DNodeContainer;
import org.eclipse.sirius.diagram.DSemanticDiagram;
import org.eclipse.sirius.diagram.DiagramFactory;
import org.eclipse.sirius.diagram.EdgeTarget;
import org.eclipse.sirius.viewpoint.description.DescriptionFactory;
import org.eclipse.swt.widgets.Display;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.polarsys.capella.ai.session.SessionSafeguards;
import org.polarsys.capella.core.data.fa.FaFactory;
import org.polarsys.capella.core.data.la.LaFactory;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Hierarchical layered layout of persisted Sirius/GMF architecture diagrams: components contain
 * functions.
 */
public class ElkDiagramLayoutTest {
    private Resource resource;
    private TransactionalEditingDomain domain;
    private Session session;
    private DSemanticDiagram semantic;
    private Diagram notation;
    private final List<Node> containers = new ArrayList<>();
    private final List<Node> functions = new ArrayList<>();

    @Before
    public void setUp() {
        Display.getDefault();
        resource = new ResourceImpl(URI.createURI("memory:/elk-layout.aird"));
        new ResourceSetImpl().getResources().add(resource);
        session =
                (Session)
                        Proxy.newProxyInstance(
                                Session.class.getClassLoader(),
                                new Class<?>[] {Session.class},
                                (proxy, method, args) ->
                                        switch (method.getName()) {
                                            case "getTransactionalEditingDomain" -> domain;
                                            case "getSessionResource" -> resource;
                                            case "getAllSessionResources" -> Set.of(resource);
                                            case "getReferencedSessionResources" -> Set.of();
                                            case "getSemanticResources" -> List.of(resource);
                                            case "isOpen" -> true;
                                            case "equals" -> proxy == args[0];
                                            case "hashCode" -> System.identityHashCode(proxy);
                                            default ->
                                                    method.getReturnType() == boolean.class
                                                            ? false
                                                            : method.getReturnType() == int.class
                                                                    ? 0
                                                                    : null;
                                        });
        semantic = DiagramFactory.eINSTANCE.createDSemanticDiagram();
        resource.getContents().add(semantic);
        notation = NotationFactory.eINSTANCE.createDiagram();
        notation.setElement(semantic);
        var annotation = DescriptionFactory.eINSTANCE.createAnnotationEntry();
        annotation.setSource("GMF_DIAGRAMS");
        annotation.setData(notation);
        semantic.getOwnedAnnotationEntries().add(annotation);
        // Two components, each with two functions, deliberately piled on top of each other.
        for (int c = 0; c < 2; c++) {
            var component = LaFactory.eINSTANCE.createLogicalComponent();
            component.setName("Component " + c);
            resource.getContents().add(component);
            DNodeContainer containerView = DiagramFactory.eINSTANCE.createDNodeContainer();
            containerView.setTarget(component);
            semantic.getOwnedDiagramElements().add(containerView);
            Node container = node(containerView, 10, 10, 100, 100);
            notation.getPersistedChildren().add(container);
            containers.add(container);
            Node compartment = NotationFactory.eINSTANCE.createNode();
            container.getPersistedChildren().add(compartment);
            for (int f = 0; f < 2; f++) {
                var function = LaFactory.eINSTANCE.createLogicalFunction();
                function.setName("Function " + c + "." + f);
                resource.getContents().add(function);
                DNode view = DiagramFactory.eINSTANCE.createDNode();
                view.setTarget(function);
                containerView.getOwnedDiagramElements().add(view);
                Node shape = node(view, 5, 5, 140, 50);
                compartment.getPersistedChildren().add(shape);
                functions.add(shape);
            }
        }
        // Flow: 0.0 -> 0.1 -> 1.0 -> 1.1, declared in reverse so order cannot come from insertion.
        for (int i = functions.size() - 2; i >= 0; i--)
            connect(functions.get(i), functions.get(i + 1));
        domain =
                TransactionalEditingDomain.Factory.INSTANCE.createEditingDomain(
                        resource.getResourceSet());
    }

    @After
    public void tearDown() {
        if (domain != null) domain.dispose();
        SessionSafeguards.cleanup();
    }

    @Test
    public void functionsFlowLeftToRightAcrossAndWithinComponents() {
        assertTrue(DiagramCommands.arrangeSelection(session, semantic, "auto"));
        for (int i = 0; i + 1 < functions.size(); i++) {
            assertTrue(
                    "Function " + i + " must be left of its successor",
                    absolute(functions.get(i))[0] + 140 <= absolute(functions.get(i + 1))[0]);
        }
        assertTrue(
                absolute(containers.get(0))[0] + bounds(containers.get(0)).getWidth()
                        <= absolute(containers.get(1))[0]);
    }

    /**
     * Stress test: 20 chained components came out 90,604 px wide and 221 px tall, one unreadable
     * row.
     */
    @Test
    public void longChainsWrapIntoRowsInsteadOfOneEndlessStrip() {
        domain.getCommandStack()
                .execute(
                        new org.eclipse.emf.transaction.RecordingCommand(domain) {
                            @Override
                            protected void doExecute() {
                                for (int c = 2; c < 12; c++) addContainer(c);
                                connect(functions.get(3), functions.get(4));
                                for (int i = 4; i + 1 < functions.size(); i++)
                                    connect(functions.get(i), functions.get(i + 1));
                            }
                        });
        assertTrue(DiagramCommands.arrangeSelection(session, semantic, "auto"));
        int minX = Integer.MAX_VALUE,
                minY = Integer.MAX_VALUE,
                maxX = Integer.MIN_VALUE,
                maxY = Integer.MIN_VALUE;
        for (Node container : containers) {
            int[] at = absolute(container);
            Bounds size = bounds(container);
            minX = Math.min(minX, at[0]);
            minY = Math.min(minY, at[1]);
            maxX = Math.max(maxX, at[0] + size.getWidth());
            maxY = Math.max(maxY, at[1] + size.getHeight());
        }
        double ratio = (maxX - minX) / (double) (maxY - minY);
        assertTrue(
                "12 chained components should wrap into rows; width/height was " + ratio,
                ratio < 6);
        for (int a = 0; a < containers.size(); a++)
            for (int b = a + 1; b < containers.size(); b++) {
                assertFalse(
                        "Wrapped rows must not overlap",
                        overlapAbsolute(containers.get(a), containers.get(b)));
            }
    }

    private boolean overlapAbsolute(Node a, Node b) {
        int[] pa = absolute(a), pb = absolute(b);
        Bounds sa = bounds(a), sb = bounds(b);
        return pa[0] < pb[0] + sb.getWidth()
                && pb[0] < pa[0] + sa.getWidth()
                && pa[1] < pb[1] + sb.getHeight()
                && pb[1] < pa[1] + sa.getHeight();
    }

    private void addContainer(int c) {
        var component = LaFactory.eINSTANCE.createLogicalComponent();
        component.setName("Component " + c);
        resource.getContents().add(component);
        DNodeContainer containerView = DiagramFactory.eINSTANCE.createDNodeContainer();
        containerView.setTarget(component);
        semantic.getOwnedDiagramElements().add(containerView);
        Node container = node(containerView, 10, 10, 100, 100);
        notation.getPersistedChildren().add(container);
        containers.add(container);
        Node compartment = NotationFactory.eINSTANCE.createNode();
        container.getPersistedChildren().add(compartment);
        for (int f = 0; f < 2; f++) {
            var function = LaFactory.eINSTANCE.createLogicalFunction();
            function.setName("Function " + c + "." + f);
            resource.getContents().add(function);
            DNode view = DiagramFactory.eINSTANCE.createDNode();
            view.setTarget(function);
            containerView.getOwnedDiagramElements().add(view);
            Node shape = node(view, 5, 5, 140, 50);
            compartment.getPersistedChildren().add(shape);
            functions.add(shape);
        }
    }

    @Test
    public void notationIsOnlySynchronizedForDiagramsTheSessionOwns() {
        assertFalse(DiagramCommands.ensureNotation(null, semantic));
        Resource foreign = new ResourceImpl(URI.createURI("memory:/foreign.aird"));
        new ResourceSetImpl().getResources().add(foreign);
        DSemanticDiagram other = DiagramFactory.eINSTANCE.createDSemanticDiagram();
        foreign.getContents().add(other);
        assertFalse(DiagramCommands.ensureNotation(session, other));
        assertTrue(
                "Nothing was added to the foreign diagram",
                other.getOwnedAnnotationEntries().isEmpty());
    }

    @Test
    public void synchronizingAnOpenedDiagramKeepsItsExistingNotation() {
        DiagramCommands.ensureNotation(session, semantic);
        var gmf =
                semantic.getOwnedAnnotationEntries().stream()
                        .filter(entry -> "GMF_DIAGRAMS".equals(entry.getSource()))
                        .toList();
        assertEquals(1, gmf.size());
        assertSame(notation, gmf.get(0).getData());
        assertEquals("Existing views are not replaced", 2, notation.getPersistedChildren().size());
        assertSame(containers.get(0), notation.getPersistedChildren().get(0));
    }

    @Test
    public void containersFitTheirChildrenBelowTheTitleBandWithoutOverlap() {
        assertTrue(DiagramCommands.arrangeSelection(session, semantic, "layered"));
        for (Node container : containers) {
            Bounds frame = bounds(container);
            for (Object child :
                    ((Node) container.getPersistedChildren().get(0)).getPersistedChildren()) {
                Bounds inner = bounds((Node) child);
                assertTrue(
                        "Child starts inside the compartment",
                        inner.getX() >= 0 && inner.getY() >= 0);
                assertTrue(
                        "Container is wide enough",
                        inner.getX() + inner.getWidth() <= frame.getWidth());
                assertTrue(
                        "Container is tall enough below its title band",
                        ElkDiagramLayout.HEADER + inner.getY() + inner.getHeight()
                                <= frame.getHeight());
                assertEquals("Leaf sizes are kept", 140, inner.getWidth());
            }
        }
        assertFalse(overlap(bounds(containers.get(0)), bounds(containers.get(1))));
        assertFalse(overlap(bounds(functions.get(0)), bounds(functions.get(1))));
    }

    @Test
    public void hierarchicalLayoutIsOneUndoableCommand() {
        assertTrue(DiagramCommands.arrangeSelection(session, semantic, "auto"));
        domain.getCommandStack().undo();
        for (Node container : containers) assertEquals(100, bounds(container).getWidth());
        for (Node function : functions) assertEquals(5, bounds(function).getX());
        assertFalse(domain.getCommandStack().canUndo());
    }

    @Test
    public void autoSizedShapesGetExplicitLabelBasedSizesSoTheDrawingMatchesThePlan() {
        Node lone = node(DiagramFactory.eINSTANCE.createDNodeContainer(), 0, 0, -1, -1);
        var component = LaFactory.eINSTANCE.createLogicalComponent();
        component.setName("A logical subsystem with a deliberately long descriptive name");
        domain.getCommandStack()
                .execute(
                        new org.eclipse.emf.transaction.RecordingCommand(domain) {
                            @Override
                            protected void doExecute() {
                                for (Node container : containers) {
                                    bounds(container).setWidth(-1);
                                    bounds(container).setHeight(-1);
                                }
                                resource.getContents().add(component);
                                ((DNodeContainer) lone.getElement()).setTarget(component);
                                semantic.getOwnedDiagramElements()
                                        .add((DNodeContainer) lone.getElement());
                                notation.getPersistedChildren().add(lone);
                            }
                        });
        assertTrue(DiagramCommands.arrangeSelection(session, semantic, "auto"));
        Bounds frame = bounds(lone);
        assertTrue(
                "A long label needs a wide box, was " + frame.getWidth(),
                frame.getWidth() >= 7 * component.getName().length());
        assertTrue(frame.getHeight() > 0);
        List<Node> top = new ArrayList<>(containers);
        top.add(lone);
        for (Node a : top)
            for (Node b : top)
                if (a != b) assertFalse("Top-level shapes overlap", overlap(bounds(a), bounds(b)));
    }

    @Test
    public void connectionsFollowElkOrthogonalRoutes() {
        assertTrue(DiagramCommands.arrangeSelection(session, semantic, "auto"));
        for (Object candidate : notation.getPersistedEdges()) {
            Edge edge = (Edge) candidate;
            var points =
                    ((org.eclipse.gmf.runtime.notation.RelativeBendpoints) edge.getBendpoints())
                            .getPoints();
            assertTrue("A route has at least its two ends", points.size() >= 2);
            int[] source = center((Node) edge.getSource());
            int previousX = 0, previousY = 0;
            for (int i = 0; i < points.size(); i++) {
                var point =
                        (org.eclipse.gmf.runtime.notation.datatype.RelativeBendpoint) points.get(i);
                int x = source[0] + point.getSourceX(), y = source[1] + point.getSourceY();
                if (i > 0)
                    assertTrue(
                            "Segment " + i + " must be horizontal or vertical",
                            x == previousX || y == previousY);
                previousX = x;
                previousY = y;
            }
            int[] target = absolute((Node) edge.getTarget());
            Bounds end = bounds((Node) edge.getTarget());
            assertTrue(
                    "The route ends at its target",
                    previousX >= target[0] - 15
                            && previousX <= target[0] + end.getWidth() + 15
                            && previousY >= target[1] - 15
                            && previousY <= target[1] + end.getHeight() + 15);
        }
    }

    @Test
    public void unconnectedShapesArePackedInsteadOfStackedInOneColumn() {
        List<Node> loose = new ArrayList<>();
        domain.getCommandStack()
                .execute(
                        new org.eclipse.emf.transaction.RecordingCommand(domain) {
                            @Override
                            protected void doExecute() {
                                for (int i = 0; i < 12; i++) {
                                    var component = LaFactory.eINSTANCE.createLogicalComponent();
                                    component.setName("Unconnected subsystem " + i);
                                    resource.getContents().add(component);
                                    DNodeContainer view =
                                            DiagramFactory.eINSTANCE.createDNodeContainer();
                                    view.setTarget(component);
                                    semantic.getOwnedDiagramElements().add(view);
                                    Node shape = node(view, 0, 0, 200, 60);
                                    notation.getPersistedChildren().add(shape);
                                    loose.add(shape);
                                }
                            }
                        });
        assertTrue(DiagramCommands.arrangeSelection(session, semantic, "auto"));
        long columns = loose.stream().map(node -> bounds(node).getX()).distinct().count();
        assertTrue(
                "Twelve unconnected shapes should spread over several columns, used " + columns,
                columns >= 3);
        int right = 0, bottom = 0;
        for (Object child : notation.getPersistedChildren()) {
            Bounds b = bounds((Node) child);
            right = Math.max(right, b.getX() + b.getWidth());
            bottom = Math.max(bottom, b.getY() + b.getHeight());
        }
        assertTrue(
                "The drawing should not be a tall strip: " + right + "x" + bottom,
                bottom <= 2 * right);
        for (Node a : loose)
            for (Node b : loose) if (a != b) assertFalse(overlap(bounds(a), bounds(b)));
    }

    @Test
    public void edgeLabelsSitBesideTheirOwnRouteClearOfShapes() {
        assertTrue(DiagramCommands.arrangeSelection(session, semantic, "auto"));
        for (Object candidate : notation.getPersistedEdges()) {
            Edge edge = (Edge) candidate;
            String text = ((org.eclipse.sirius.diagram.DEdge) edge.getElement()).getName();
            double[][] route = route(edge);
            double[] label = labelCenter(edge, route);
            double width = text.length() * 8 + 20, height = 21;
            assertTrue(
                    "Label of " + text + " is far from its route",
                    distance(label, route) <= height);
            for (Node function : functions) {
                int[] origin = absolute(function);
                Bounds b = bounds(function);
                boolean overlaps =
                        label[0] - width / 2 < origin[0] + b.getWidth()
                                && origin[0] < label[0] + width / 2
                                && label[1] - height / 2 < origin[1] + b.getHeight()
                                && origin[1] < label[1] + height / 2;
                assertFalse("Label of " + text + " covers a function", overlaps);
            }
        }
    }

    /** Absolute route points: GMF stores bendpoints as offsets from the source shape's centre. */
    private static double[][] route(Edge edge) {
        int[] source = center((Node) edge.getSource());
        var points =
                ((org.eclipse.gmf.runtime.notation.RelativeBendpoints) edge.getBendpoints())
                        .getPoints();
        double[][] route = new double[points.size()][];
        for (int i = 0; i < points.size(); i++) {
            var point = (org.eclipse.gmf.runtime.notation.datatype.RelativeBendpoint) points.get(i);
            route[i] =
                    new double[] {source[0] + point.getSourceX(), source[1] + point.getSourceY()};
        }
        return route;
    }

    /**
     * Sirius centre labels: offset from the route's half-length point, rotated into that segment's
     * direction.
     */
    private static double[] labelCenter(Edge edge, double[][] route) {
        double total = 0;
        for (int i = 1; i < route.length; i++)
            total += Math.hypot(route[i][0] - route[i - 1][0], route[i][1] - route[i - 1][1]);
        double remaining = total / 2;
        for (int i = 1; i < route.length; i++) {
            double dx = route[i][0] - route[i - 1][0],
                    dy = route[i][1] - route[i - 1][1],
                    length = Math.hypot(dx, dy);
            if (length == 0) continue;
            if (remaining <= length || i == route.length - 1) {
                double t = Math.min(1, remaining / length), cos = dx / length, sin = dy / length;
                double rx = route[i - 1][0] + dx * t, ry = route[i - 1][1] + dy * t;
                var location =
                        (org.eclipse.gmf.runtime.notation.Location)
                                ((Node) edge.getPersistedChildren().get(0)).getLayoutConstraint();
                return new double[] {
                    rx + location.getX() * cos - location.getY() * sin,
                    ry + location.getX() * sin + location.getY() * cos
                };
            }
            remaining -= length;
        }
        return route[0];
    }

    private static double distance(double[] point, double[][] route) {
        double best = Double.MAX_VALUE;
        for (int i = 1; i < route.length; i++) {
            double ax = route[i - 1][0],
                    ay = route[i - 1][1],
                    dx = route[i][0] - ax,
                    dy = route[i][1] - ay;
            double t =
                    dx == 0 && dy == 0
                            ? 0
                            : Math.max(
                                    0,
                                    Math.min(
                                            1,
                                            ((point[0] - ax) * dx + (point[1] - ay) * dy)
                                                    / (dx * dx + dy * dy)));
            best = Math.min(best, Math.hypot(point[0] - (ax + dx * t), point[1] - (ay + dy * t)));
        }
        return best;
    }

    private static int[] center(Node node) {
        int[] origin = absolute(node);
        return new int[] {
            origin[0] + bounds(node).getWidth() / 2, origin[1] + bounds(node).getHeight() / 2
        };
    }

    private void connect(Node source, Node target) {
        var edgeView = DiagramFactory.eINSTANCE.createDEdge();
        edgeView.setSourceNode((EdgeTarget) source.getElement());
        edgeView.setTargetNode((EdgeTarget) target.getElement());
        edgeView.setOwnedStyle(DiagramFactory.eINSTANCE.createEdgeStyle());
        var exchange = FaFactory.eINSTANCE.createFunctionalExchange();
        resource.getContents().add(exchange);
        edgeView.setTarget(exchange);
        semantic.getOwnedDiagramElements().add(edgeView);
        Edge edge = NotationFactory.eINSTANCE.createEdge();
        edge.setElement(edgeView);
        edge.setSource(source);
        edge.setTarget(target);
        edgeView.setName(
                "Exchange carrying a fairly long descriptive label "
                        + notation.getPersistedEdges().size());
        Node label = NotationFactory.eINSTANCE.createNode();
        label.setType("6001");
        var location = NotationFactory.eINSTANCE.createLocation();
        location.setY(-10);
        label.setLayoutConstraint(location);
        edge.getPersistedChildren().add(label);
        notation.getPersistedEdges().add(edge);
    }

    /**
     * Absolute position: containers add their origin plus the title band for compartment children.
     */
    private static int[] absolute(Node node) {
        Bounds own = bounds(node);
        int x = own.getX(), y = own.getY();
        if (node.eContainer() instanceof Node compartment
                && compartment.eContainer() instanceof Node container) {
            x += bounds(container).getX();
            y += bounds(container).getY() + ElkDiagramLayout.HEADER;
        }
        return new int[] {x, y};
    }

    private static boolean overlap(Bounds a, Bounds b) {
        return a.getX() < b.getX() + b.getWidth()
                && b.getX() < a.getX() + a.getWidth()
                && a.getY() < b.getY() + b.getHeight()
                && b.getY() < a.getY() + a.getHeight();
    }

    private static Node node(
            org.eclipse.sirius.viewpoint.DSemanticDecorator element,
            int x,
            int y,
            int width,
            int height) {
        Node node = NotationFactory.eINSTANCE.createNode();
        node.setElement(element);
        Bounds bounds = NotationFactory.eINSTANCE.createBounds();
        bounds.setX(x);
        bounds.setY(y);
        bounds.setWidth(width);
        bounds.setHeight(height);
        node.setLayoutConstraint(bounds);
        return node;
    }

    private static Bounds bounds(Node node) {
        return (Bounds) node.getLayoutConstraint();
    }
}
