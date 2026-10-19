package org.polarsys.capella.ai.scripting;

import static org.junit.Assert.*;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.ECrossReferenceAdapter;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.sirius.business.api.query.DRepresentationQuery;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.diagram.DSemanticDiagram;
import org.eclipse.sirius.diagram.DiagramFactory;
import org.eclipse.sirius.diagram.description.DiagramDescription;
import org.eclipse.sirius.viewpoint.DAnalysis;
import org.eclipse.sirius.viewpoint.DRepresentationDescriptor;
import org.eclipse.sirius.viewpoint.DView;
import org.eclipse.sirius.viewpoint.ViewpointFactory;
import org.eclipse.sirius.viewpoint.description.Viewpoint;
import org.eclipse.swt.widgets.Display;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.polarsys.capella.ai.session.SessionSafeguards;
import org.polarsys.capella.core.data.la.LaFactory;

/** Sirius descriptor names and representation removal must participate in one Undo command. */
public class DiagramDescriptorLifecycleTest {
    private TransactionalEditingDomain domain;
    private Resource resource;
    private DView view;
    private DSemanticDiagram diagram;
    private DRepresentationDescriptor descriptor;
    private DiagramAPI api;
    private Session session;
    private final AtomicInteger saves = new AtomicInteger();
    private final AtomicBoolean savedInsideWrite = new AtomicBoolean();

    @Before
    public void setUp() {
        assertSame(Display.getDefault(), Display.getCurrent());
        DAnalysis analysis = ViewpointFactory.eINSTANCE.createDAnalysis();
        view = ViewpointFactory.eINSTANCE.createDView();
        analysis.getOwnedViews().add(view);
        Viewpoint viewpoint = org.eclipse.sirius.viewpoint.description.DescriptionFactory.eINSTANCE.createViewpoint();
        DiagramDescription description = org.eclipse.sirius.diagram.description.DescriptionFactory.eINSTANCE.createDiagramDescription();
        viewpoint.getOwnedRepresentations().add(description);
        view.setViewpoint(viewpoint);
        EObject semantic = LaFactory.eINSTANCE.createLogicalComponent();
        diagram = DiagramFactory.eINSTANCE.createDSemanticDiagram();
        diagram.setUid(java.util.UUID.randomUUID().toString());
        diagram.setTarget(semantic);
        diagram.setDescription(description);
        descriptor = ViewpointFactory.eINSTANCE.createDRepresentationDescriptor();
        descriptor.setName("Original diagram");
        descriptor.setDescription(description);
        descriptor.setTarget(semantic);
        view.getOwnedRepresentationDescriptors().add(descriptor);
        resource = new ResourceImpl(URI.createURI("memory:/descriptor-lifecycle.aird"));
        resource.getContents().add(analysis);
        resource.getContents().add(semantic);
        resource.getContents().add(diagram);
        descriptor.setRepresentation(diagram);
        ResourceSetImpl resources = new ResourceSetImpl();
        resources.getResources().add(resource);
        ECrossReferenceAdapter crossReferences = new ECrossReferenceAdapter();
        resources.eAdapters().add(crossReferences);
        domain = TransactionalEditingDomain.Factory.INSTANCE.createEditingDomain(resources);
        session = (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[] {Session.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getTransactionalEditingDomain": return domain;
                    case "getSessionResource": return resource;
                    case "getSemanticResources": return List.of(resource);
                    case "getAllSessionResources": return Set.of(resource);
                    case "getReferencedSessionResources": return Set.of();
                    case "getSemanticCrossReferencer": return crossReferences;
                    case "getSelectedViewpoints": return Set.of(viewpoint);
                    case "isOpen": return true;
                    case "save":
                        saves.incrementAndGet();
                        savedInsideWrite.set(SessionSafeguards.isInWriteContext());
                        return null;
                    case "equals": return proxy == args[0];
                    case "hashCode": return System.identityHashCode(proxy);
                    default:
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class) return 0;
                        return null;
                }
            });
        api = new DiagramAPI(session);
        assertSame(descriptor, new DRepresentationQuery(diagram).getRepresentationDescriptor());
    }

    @After
    public void tearDown() {
        if (domain != null) domain.dispose();
        SessionSafeguards.cleanup();
    }

    @Test
    public void semanticTargetDeletionRemovesItsRepresentationAndUndoRestoresIt() {
        Assume.assumeTrue("Deletion uses Sirius dialect extensions from the OSGi test harness", Platform.isRunning());
        EObject target = diagram.getTarget();
        SessionSafeguards.runWrite(session, "Identify semantic fixture", () -> {
            target.eSet(target.eClass().getEStructuralFeature("id"), "semantic-representation-target");
            return null;
        });
        domain.getCommandStack().flush();
        assertTrue(new CapellaAPI(session).delete(target));
        assertNotSame(view, descriptor.eContainer());
        assertNull(descriptor.eResource());
        assertFalse(resource.getContents().contains(diagram));
        domain.getCommandStack().undo();
        assertSame(view, descriptor.eContainer());
        assertSame(target, descriptor.getTarget());
        assertSame(target, diagram.getTarget());
        assertTrue(resource.getContents().contains(diagram));
        assertTrue(resource.getContents().contains(target));
        assertFalse(domain.getCommandStack().canUndo());
        domain.getCommandStack().redo();
        assertNotSame(view, descriptor.eContainer());
        assertNull(descriptor.eResource());
        assertFalse(resource.getContents().contains(diagram));
    }

    @Test
    public void renameChangesTheDescriptorAndIsUndoableWithoutRefreshingDiagramContents() {
        assertTrue(api.rename(diagram, "Renamed diagram"));
        assertEquals("Renamed diagram", descriptor.getName());
        assertEquals("Renamed diagram", diagram.getName());
        assertEquals(1, saves.get());
        assertFalse(savedInsideWrite.get());
        assertTrue(domain.getCommandStack().canUndo());
        domain.getCommandStack().undo();
        assertEquals("Original diagram", descriptor.getName());
        assertEquals("Original diagram", diagram.getName());
        domain.getCommandStack().redo();
        assertEquals("Renamed diagram", descriptor.getName());
    }

    @Test
    public void deletionRemovesDescriptorAndRepresentationAndUndoRestoresBoth() {
        Assume.assumeTrue("Deletion uses Sirius dialect extensions from the OSGi test harness", Platform.isRunning());
        assertTrue(api.delete(diagram));
        // The Undo change description retains deleted objects in its own
        // containment. They must be detached from the session model instead.
        assertNotSame(view, descriptor.eContainer());
        assertNull(descriptor.eResource());
        assertNull(diagram.eResource());
        assertFalse(view.getOwnedRepresentationDescriptors().contains(descriptor));
        assertEquals(1, saves.get());
        assertFalse(savedInsideWrite.get());
        assertTrue(domain.getCommandStack().canUndo());
        domain.getCommandStack().undo();
        assertSame(view, descriptor.eContainer());
        assertSame(resource, diagram.eResource());
        assertSame(diagram, descriptor.getRepresentation());
        assertEquals("Original diagram", diagram.getName());
        domain.getCommandStack().redo();
        assertNotSame(view, descriptor.eContainer());
        assertNull(descriptor.eResource());
        assertNull(diagram.eResource());
    }

    @Test
    public void laterFailureRollsBackDescriptorRenameAndDeletionWithoutSaving() {
        Assume.assumeTrue("Deletion uses Sirius dialect extensions from the OSGi test harness", Platform.isRunning());
        try {
            SessionSafeguards.runWrite(session, "Failed diagram workflow", () -> {
                assertTrue(api.rename(diagram, "Temporary name"));
                assertTrue(api.delete(diagram));
                throw new IllegalArgumentException("later diagram step failed");
            });
            fail("The workflow must fail");
        } catch (IllegalArgumentException expected) {
            assertEquals("later diagram step failed", expected.getMessage());
        }
        assertSame(view, descriptor.eContainer());
        assertSame(resource, diagram.eResource());
        assertSame(diagram, descriptor.getRepresentation());
        assertEquals("Original diagram", descriptor.getName());
        assertEquals(0, saves.get());
        assertFalse(domain.getCommandStack().canUndo());
    }
}
