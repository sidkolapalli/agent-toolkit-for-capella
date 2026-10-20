package org.polarsys.capella.ai.tools;

import static org.junit.Assert.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
import org.eclipse.sirius.diagram.EdgeTarget;
import org.eclipse.sirius.diagram.DNode;
import org.eclipse.sirius.diagram.DNodeContainer;
import org.eclipse.sirius.diagram.DSemanticDiagram;
import org.eclipse.sirius.diagram.DiagramFactory;
import org.eclipse.sirius.viewpoint.description.DescriptionFactory;
import org.eclipse.swt.widgets.Display;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.polarsys.capella.ai.session.SessionSafeguards;
import org.polarsys.capella.core.data.fa.FaFactory;
import org.polarsys.capella.core.data.la.LaFactory;

/** Hierarchical layered layout of persisted Sirius/GMF architecture diagrams: components contain functions. */
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
        session = (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[] {Session.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getTransactionalEditingDomain" -> domain;
                case "getSessionResource" -> resource;
                case "getAllSessionResources" -> Set.of(resource);
                case "getReferencedSessionResources" -> Set.of();
                case "getSemanticResources" -> List.of(resource);
                case "isOpen" -> true;
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> method.getReturnType() == boolean.class ? false : method.getReturnType() == int.class ? 0 : null;
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
        for (int i = functions.size() - 2; i >= 0; i--) connect(functions.get(i), functions.get(i + 1));
        domain = TransactionalEditingDomain.Factory.INSTANCE.createEditingDomain(resource.getResourceSet());
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
            assertTrue("Function " + i + " must be left of its successor", absolute(functions.get(i))[0] + 140 <= absolute(functions.get(i + 1))[0]);
        }
        assertTrue(absolute(containers.get(0))[0] + bounds(containers.get(0)).getWidth() <= absolute(containers.get(1))[0]);
    }

    @Test
    public void containersFitTheirChildrenBelowTheTitleBandWithoutOverlap() {
        assertTrue(DiagramCommands.arrangeSelection(session, semantic, "layered"));
        for (Node container : containers) {
            Bounds frame = bounds(container);
            for (Object child : ((Node) container.getPersistedChildren().get(0)).getPersistedChildren()) {
                Bounds inner = bounds((Node) child);
                assertTrue("Child starts inside the compartment", inner.getX() >= 0 && inner.getY() >= 0);
                assertTrue("Container is wide enough", inner.getX() + inner.getWidth() <= frame.getWidth());
                assertTrue("Container is tall enough below its title band",
                    ElkDiagramLayout.HEADER + inner.getY() + inner.getHeight() <= frame.getHeight());
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
        notation.getPersistedEdges().add(edge);
    }

    /** Absolute position: containers add their origin plus the title band for compartment children. */
    private static int[] absolute(Node node) {
        Bounds own = bounds(node);
        int x = own.getX(), y = own.getY();
        if (node.eContainer() instanceof Node compartment && compartment.eContainer() instanceof Node container) {
            x += bounds(container).getX();
            y += bounds(container).getY() + ElkDiagramLayout.HEADER;
        }
        return new int[] { x, y };
    }

    private static boolean overlap(Bounds a, Bounds b) {
        return a.getX() < b.getX() + b.getWidth() && b.getX() < a.getX() + a.getWidth()
            && a.getY() < b.getY() + b.getHeight() && b.getY() < a.getY() + a.getHeight();
    }

    private static Node node(org.eclipse.sirius.viewpoint.DSemanticDecorator element, int x, int y, int width, int height) {
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
