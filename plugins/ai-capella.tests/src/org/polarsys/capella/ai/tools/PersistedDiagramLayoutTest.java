package org.polarsys.capella.ai.tools;

import static org.junit.Assert.*;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.gmf.runtime.notation.Bounds;
import org.eclipse.gmf.runtime.notation.Diagram;
import org.eclipse.gmf.runtime.notation.Node;
import org.eclipse.gmf.runtime.notation.NotationFactory;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.diagram.DNode;
import org.eclipse.sirius.diagram.DSemanticDiagram;
import org.eclipse.sirius.diagram.DiagramFactory;
import org.eclipse.sirius.viewpoint.description.DescriptionFactory;
import org.eclipse.swt.widgets.Display;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.polarsys.capella.ai.session.SessionSafeguards;
import org.polarsys.capella.core.data.ctx.CtxFactory;
import org.polarsys.capella.core.data.fa.FaFactory;

/** Layout uses actual persisted Sirius/GMF shapes even when no diagram editor is open. */
public class PersistedDiagramLayoutTest {
    private Resource resource;
    private TransactionalEditingDomain domain;
    private Session session;

    @Before
    public void setUp() {
        assertSame(Display.getDefault(), Display.getCurrent());
        resource = new ResourceImpl(URI.createURI("memory:/persisted-layout.aird"));
        var resources = new ResourceSetImpl();
        resources.getResources().add(resource);
        session = (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[] {Session.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getTransactionalEditingDomain": return domain;
                    case "getSessionResource": return resource;
                    case "getAllSessionResources": return Set.of(resource);
                    case "getReferencedSessionResources": return Set.of();
                    case "getSemanticResources": return List.of(resource);
                    case "isOpen": return true;
                    case "equals": return proxy == args[0];
                    case "hashCode": return System.identityHashCode(proxy);
                    default:
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class) return 0;
                        return null;
                }
            });
    }

    @After
    public void tearDown() {
        if (domain != null) domain.dispose();
        SessionSafeguards.cleanup();
    }

    @Test
    public void hiddenPortConnectedFunctionsFormMultipleLayersAndSupportUndo() {
        Fixture target = fixture(true);
        Fixture unrelated = fixture(true);
        startDomain();

        assertTrue(DiagramCommands.arrangeSelection(session, target.semantic, "layered"));
        assertTrue("The visible port-connected chain must span three columns", bounds(target.nodes[0]).getX() < bounds(target.nodes[1]).getX());
        assertTrue(bounds(target.nodes[1]).getX() < bounds(target.nodes[2]).getX());
        assertTrue("Native shape widths must not overlap adjacent columns", bounds(target.nodes[1]).getX() >= bounds(target.nodes[0]).getX() + 320);
        for (Node node : unrelated.nodes) assertEquals("A second diagram must retain its persisted positions", 17, bounds(node).getX());
        assertEquals("Border ports retain relative positions", 6, bounds(target.ports[0]).getX());
        assertEquals("Label decorations retain relative positions", 11, bounds(target.label).getX());

        assertTrue(domain.getCommandStack().canUndo());
        domain.getCommandStack().undo();
        for (Node node : target.nodes) assertEquals(17, bounds(node).getX());
        assertFalse("One layout is one undoable command", domain.getCommandStack().canUndo());
        domain.getCommandStack().redo();
        assertTrue(bounds(target.nodes[0]).getX() < bounds(target.nodes[2]).getX());
    }

    @Test
    public void autoAppliesActualLayoutToPersistedNotationWithoutAnEditor() {
        Fixture target = fixture(true);
        startDomain();
        assertTrue(DiagramCommands.arrangeSelection(session, target.semantic, "auto"));
        assertTrue(bounds(target.nodes[0]).getX() != bounds(target.nodes[2]).getX());
    }

    @Test
    public void missingNotationAndUnknownStrategyReturnFailureWithoutMutations() {
        Fixture target = fixture(false);
        Fixture other = fixture(true);
        startDomain();
        assertFalse(DiagramCommands.arrangeSelection(session, target.semantic, "layered"));
        assertFalse(DiagramCommands.arrangeSelection(session, other.semantic, "unavailable-layout"));
        for (Node node : other.nodes) assertEquals(17, bounds(node).getX());
        assertFalse(domain.getCommandStack().canUndo());
    }

    @Test
    public void anotherSessionsRepresentationIsRejectedEvenWhenItsResourceSharesTheDomain() {
        Fixture target = fixture(true);
        var foreignResource = new ResourceImpl(URI.createURI("memory:/another-session.aird"));
        resource.getResourceSet().getResources().add(foreignResource);
        foreignResource.getContents().add(target.semantic);
        startDomain();
        assertFalse(DiagramCommands.arrangeSelection(session, target.semantic, "layered"));
        for (Node node : target.nodes) assertEquals(17, bounds(node).getX());
        assertFalse(domain.getCommandStack().canUndo());
    }

    private void startDomain() {
        domain = TransactionalEditingDomain.Factory.INSTANCE.createEditingDomain(resource.getResourceSet());
    }

    private Fixture fixture(boolean withNotation) {
        Fixture fixture = new Fixture();
        fixture.semantic = DiagramFactory.eINSTANCE.createDSemanticDiagram();
        resource.getContents().add(fixture.semantic);
        fixture.notation = NotationFactory.eINSTANCE.createDiagram();
        fixture.notation.setElement(fixture.semantic);
        if (withNotation) {
            var annotation = DescriptionFactory.eINSTANCE.createAnnotationEntry();
            annotation.setSource("GMF_DIAGRAMS");
            annotation.setData(fixture.notation);
            fixture.semantic.getOwnedAnnotationEntries().add(annotation);
        }
        fixture.nodes = new Node[3];
        fixture.ports = new Node[3];
        for (int i = 0; i < 3; i++) {
            var function = CtxFactory.eINSTANCE.createSystemFunction();
            function.setName("Function " + i);
            resource.getContents().add(function);
            DNode view = DiagramFactory.eINSTANCE.createDNode();
            view.setTarget(function);
            fixture.semantic.getOwnedDiagramElements().add(view);
            Node shape = node(view, 17, 30 + i * 180, 320, 90);
            fixture.notation.getPersistedChildren().add(shape);
            fixture.nodes[i] = shape;
            var port = FaFactory.eINSTANCE.createFunctionOutputPort();
            function.getOutputs().add(port);
            DNode portView = DiagramFactory.eINSTANCE.createDNode();
            portView.setTarget(port);
            view.getOwnedBorderedNodes().add(portView);
            fixture.ports[i] = node(portView, 6, 7, 12, 12);
            shape.getPersistedChildren().add(fixture.ports[i]);
        }
        fixture.label = node((DNode) fixture.nodes[0].getElement(), 11, 12, 40, 20);
        fixture.nodes[0].getPersistedChildren().add(fixture.label);
        for (int i = 0; i < 2; i++) {
            var edge = NotationFactory.eINSTANCE.createEdge();
            edge.setSource(fixture.ports[i]);
            edge.setTarget(fixture.ports[i + 1]);
            fixture.notation.getPersistedEdges().add(edge);
        }
        return fixture;
    }

    private static Node node(DNode element, int x, int y, int width, int height) {
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

    private static class Fixture {
        DSemanticDiagram semantic;
        Diagram notation;
        Node[] nodes;
        Node[] ports;
        Node label;
    }
}
