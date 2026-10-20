package org.polarsys.capella.ai.scripting;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.ECrossReferenceAdapter;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceImpl;
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
import org.junit.Before;
import org.junit.Test;
import org.polarsys.capella.ai.session.SessionSafeguards;
import org.polarsys.capella.ai.tools.DiagramCommands;
import org.polarsys.capella.core.data.ctx.CtxFactory;
import org.polarsys.capella.core.data.ctx.SystemComponentPkg;

/** Referenced Sirius resources are visible to queries but are not writable diagrams of the captured session. */
public class DiagramSessionOwnershipTest {
    private Fixture fixture;

    @Before
    public void setUp() { assertSame(Display.getDefault(), Display.getCurrent()); }

    @After
    public void tearDown() {
        if (fixture != null) fixture.domain.dispose();
        SessionSafeguards.cleanup();
    }

    @Test
    public void referencedDescriptorAndRepresentationRejectRenameAndDeleteWithoutAnyNativeEffects() throws Exception {
        fixture = new Fixture(true, true);
        assertRejectedLifecycle();
    }

    @Test
    public void referencedDescriptorCannotBeRenamedOrDeletedEvenWhenItsRepresentationIsInThePrimaryResource() throws Exception {
        fixture = new Fixture(true, false);
        assertSame(fixture.main, fixture.diagram.eResource());
        assertSame(fixture.referenced, fixture.descriptor.eResource());
        assertRejectedLifecycle();
    }

    @Test
    public void primaryDescriptorCannotAuthorizeARepresentationStoredInAReferencedResource() throws Exception {
        fixture = new Fixture(false, true);
        assertSame(fixture.main, fixture.descriptor.eResource());
        assertSame(fixture.referenced, fixture.diagram.eResource());
        assertRejectedLifecycle();
    }

    /** Live eval: an idempotent script checked find(name) == null before creating, and find threw instead. */
    @Test
    public void findReturnsNullForAMissingDiagramSoScriptsCanCheckBeforeCreating() throws Exception {
        fixture = new Fixture(false, false);
        DiagramAPI api = new DiagramAPI(fixture.session);
        assertNull(api.find("A diagram that does not exist"));
        assertThrows(IllegalArgumentException.class, () -> api.find(" "));
    }

    @Test
    public void apiCreationUsesItsCapturedSessionsDescriptorsWithoutAnActiveEditor() throws Exception {
        fixture = new Fixture(false, false);
        byte[] main = bytes(fixture.main), referenced = bytes(fixture.referenced), semantic = bytes(fixture.semantic);
        DiagramAPI api = new DiagramAPI(fixture.session);
        IllegalArgumentException creation = assertThrows(IllegalArgumentException.class,
            () -> api.create("SAB", "Requested diagram", fixture.target));
        assertTrue(creation.getMessage(), creation.getMessage().contains("Captured Session Marker"));
        IllegalArgumentException safeCreation = assertThrows(IllegalArgumentException.class,
            () -> api.createSafe("SAB", "Requested diagram", fixture.target));
        assertTrue(safeCreation.getMessage(), safeCreation.getMessage().contains("Captured Session Marker"));
        assertUnchanged(main, referenced, semantic);
    }

    @Test
    public void creationRejectsDetachedAndForeignResourceTargetsBeforeReadingNativeDescriptors() throws Exception {
        fixture = new Fixture(false, false);
        byte[] main = bytes(fixture.main), referenced = bytes(fixture.referenced), semantic = bytes(fixture.semantic);
        var foreignSet = new ResourceSetImpl();
        Resource foreign = new XMIResourceImpl(URI.createURI("memory:/foreign.capella")); foreignSet.getResources().add(foreign);
        SystemComponentPkg foreignTarget = CtxFactory.eINSTANCE.createSystemComponentPkg(); foreign.getContents().add(foreignTarget);
        foreign.setTrackingModification(true); foreign.setModified(false);
        assertThrows(IllegalArgumentException.class,
            () -> new DiagramAPI(fixture.session).create("SAB", "Foreign", foreignTarget));
        assertThrows(IllegalArgumentException.class,
            () -> DiagramCommands.createAndOpenDiagram(fixture.session,
                CtxFactory.eINSTANCE.createSystemComponentPkg(), "System Architecture Blank", "Detached"));
        assertFalse(foreign.isModified()); assertUnchanged(main, referenced, semantic);
    }

    @Test
    public void creationRejectsUnregisteredSemanticResourcesEvenWithinTheSameNativeDomain() throws Exception {
        fixture = new Fixture(false, false);
        var unregistered = new XMIResourceImpl(URI.createURI("memory:/unregistered.capella"));
        var target = CtxFactory.eINSTANCE.createSystemComponentPkg(); unregistered.getContents().add(target);
        SessionSafeguards.runWrite(fixture.session, "Attach foreign resource fixture", () -> {
            fixture.domain.getResourceSet().getResources().add(unregistered); return null;
        });
        fixture.domain.getCommandStack().flush();
        fixture.main.setModified(false); fixture.referenced.setModified(false); fixture.semantic.setModified(false);
        unregistered.setTrackingModification(true); unregistered.setModified(false);
        byte[] main = bytes(fixture.main), referenced = bytes(fixture.referenced), semantic = bytes(fixture.semantic);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> DiagramCommands.createAndOpenDiagram(fixture.session, target, "System Architecture Blank", "Foreign"));
        assertTrue(failure.getMessage().contains("semantic resources"));
        assertFalse(unregistered.isModified()); assertUnchanged(main, referenced, semantic);
    }

    private void assertRejectedLifecycle() throws Exception {
        assertSame(fixture.descriptor, new DRepresentationQuery(fixture.diagram).getRepresentationDescriptor());
        byte[] main = bytes(fixture.main), referenced = bytes(fixture.referenced), semantic = bytes(fixture.semantic);
        assertThrows(IllegalArgumentException.class,
            () -> new DiagramAPI(fixture.session).rename(fixture.diagram, "Rejected rename"));
        assertThrows(IllegalArgumentException.class,
            () -> new DiagramAPI(fixture.session).delete(fixture.diagram));
        assertEquals("Original diagram", fixture.descriptor.getName());
        assertSame(fixture.diagram, fixture.descriptor.getRepresentation());
        assertNotNull(fixture.diagram.eResource()); assertNotNull(fixture.descriptor.eContainer());
        assertUnchanged(main, referenced, semantic);
    }

    private void assertUnchanged(byte[] main, byte[] referenced, byte[] semantic) throws Exception {
        assertArrayEquals(main, bytes(fixture.main)); assertArrayEquals(referenced, bytes(fixture.referenced));
        assertArrayEquals(semantic, bytes(fixture.semantic));
        assertFalse(fixture.main.isModified()); assertFalse(fixture.referenced.isModified()); assertFalse(fixture.semantic.isModified());
        assertFalse(fixture.domain.getCommandStack().canUndo()); assertFalse(fixture.domain.getCommandStack().canRedo());
        assertEquals(0, fixture.saves.get()); assertFalse(SessionSafeguards.isInWriteContext());
    }

    private static byte[] bytes(Resource resource) throws Exception {
        boolean dirty = resource.isModified();
        var output = new ByteArrayOutputStream();
        try { resource.save(output, Map.of()); return output.toByteArray(); }
        finally { resource.setModified(dirty); }
    }

    private static final class Fixture {
        final Resource main = new XMIResourceImpl(URI.createURI("memory:/primary.aird"));
        final Resource referenced = new XMIResourceImpl(URI.createURI("memory:/referenced.aird"));
        final Resource semantic = new XMIResourceImpl(URI.createURI("memory:/primary.capella"));
        final SystemComponentPkg target = CtxFactory.eINSTANCE.createSystemComponentPkg();
        final DSemanticDiagram diagram = DiagramFactory.eINSTANCE.createDSemanticDiagram();
        final DRepresentationDescriptor descriptor = ViewpointFactory.eINSTANCE.createDRepresentationDescriptor();
        final TransactionalEditingDomain domain;
        final Session session;
        final AtomicInteger saves = new AtomicInteger();

        Fixture(boolean referencedDescriptor, boolean referencedRepresentation) {
            var resources = new ResourceSetImpl();
            resources.getResources().addAll(List.of(main, referenced, semantic));
            semantic.getContents().add(target); target.setId("owned-target"); target.setName("Owned target");
            Viewpoint viewpoint = org.eclipse.sirius.viewpoint.description.DescriptionFactory.eINSTANCE.createViewpoint();
            DiagramDescription description = org.eclipse.sirius.diagram.description.DescriptionFactory.eINSTANCE.createDiagramDescription();
            viewpoint.setName("Captured Viewpoint"); description.setName("Captured Session Marker");
            viewpoint.getOwnedRepresentations().add(description);
            Resource design = new XMIResourceImpl(URI.createURI("memory:/captured.odesign"));
            resources.getResources().add(design); design.getContents().add(viewpoint);
            DView mainView = view(main, viewpoint), referencedView = view(referenced, viewpoint);
            diagram.setUid("diagram-ownership"); diagram.setTarget(target); diagram.setDescription(description);
            main.getContents().add(diagram);
            descriptor.setName("Original diagram"); descriptor.setTarget(target); descriptor.setDescription(description);
            mainView.getOwnedRepresentationDescriptors().add(descriptor);
            descriptor.setRepresentation(diagram);
            // Cache the real native descriptor link as Sirius does for a loaded representation.
            // This also exercises split descriptor/representation resources without an OSGi SessionManager.
            assertSame(descriptor, new DRepresentationQuery(diagram).getRepresentationDescriptor());
            if (referencedRepresentation) referenced.getContents().add(diagram);
            if (referencedDescriptor) referencedView.getOwnedRepresentationDescriptors().add(descriptor);
            descriptor.setRepresentation(diagram);
            ECrossReferenceAdapter crossReferences = new ECrossReferenceAdapter(); resources.eAdapters().add(crossReferences);
            domain = TransactionalEditingDomain.Factory.INSTANCE.createEditingDomain(resources);
            session = (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[] {Session.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getTransactionalEditingDomain" -> domain;
                    case "getSessionResource" -> main;
                    case "getSemanticResources" -> List.of(semantic);
                    case "getAllSessionResources" -> Set.of(main, referenced);
                    case "getReferencedSessionResources" -> Set.of(referenced);
                    case "getSemanticCrossReferencer" -> crossReferences;
                    case "getSelectedViewpoints" -> Set.of(viewpoint);
                    case "isOpen" -> true;
                    case "save" -> { saves.incrementAndGet(); yield null; }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
            for (Resource resource : List.of(main, referenced, semantic, design)) {
                resource.setTrackingModification(true); resource.setModified(false);
            }
        }

        private static DView view(Resource resource, Viewpoint viewpoint) {
            DAnalysis analysis = ViewpointFactory.eINSTANCE.createDAnalysis(); resource.getContents().add(analysis);
            DView view = ViewpointFactory.eINSTANCE.createDView(); view.setViewpoint(viewpoint); analysis.getOwnedViews().add(view);
            return view;
        }
    }
}
