package com.sidkolapalli.capella.ai.model;

import static org.junit.Assert.*;

import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.impl.DynamicEObjectImpl;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.XMLSave;
import org.eclipse.emf.ecore.xmi.impl.XMLResourceImpl;
import org.eclipse.emf.transaction.RecordingCommand;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.junit.Test;
import org.polarsys.capella.common.data.core.gen.xmi.impl.CapellaXMLSaveImpl;
import org.polarsys.capella.core.data.capellamodeller.CapellamodellerFactory;
import org.polarsys.capella.core.data.capellamodeller.Project;
import org.polarsys.capella.core.data.capellamodeller.util.CapellamodellerResourceImpl;
import org.polarsys.capella.core.data.ctx.CtxFactory;
import org.polarsys.capella.core.data.ctx.SystemFunction;
import org.polarsys.capella.core.data.fa.FaFactory;
import org.polarsys.capella.core.data.oa.OaFactory;
import org.polarsys.capella.core.data.oa.OperationalActivity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Uses Capella's real cached-ID resource and XML writer/reader, not a surrogate resource. */
public class PersistentIdsTest {
    private static final String OLD_ID = "target-original";
    private static final String NEW_ID = "target-stable";

    @Test
    public void nativeAttributeAssignmentAloneLeavesTheCachedReferenceIdStale() {
        Fixture fixture = fixture("memory:/original.capella");
        assertEquals(OLD_ID, fixture.resource.getURIFragment(fixture.target));
        fixture.target.setId(NEW_ID);
        assertEquals(NEW_ID, fixture.target.getId());
        assertEquals(
                "Native resource caches the ID at attachment",
                OLD_ID,
                fixture.resource.getURIFragment(fixture.target));
    }

    @Test
    public void changedIdSerializesAndReloadsItsActualCrossReference() throws Exception {
        Fixture fixture = fixture("memory:/changed.capella");
        PersistentIds.setId(fixture.target, NEW_ID);
        assertIdentity(fixture, NEW_ID, OLD_ID);
        assertReloadedReference(fixture, NEW_ID, OLD_ID);
    }

    @Test
    public void commandUndoAndRedoSynchronizeIdsAndSerializedReferences() throws Exception {
        Fixture fixture = fixture("memory:/undo.capella");
        TransactionalEditingDomain domain =
                TransactionalEditingDomain.Factory.INSTANCE.createEditingDomain(fixture.resources);
        try {
            domain.getCommandStack()
                    .execute(
                            new RecordingCommand(domain, "Assign stable ID") {
                                @Override
                                protected void doExecute() {
                                    PersistentIds.setId(fixture.target, NEW_ID);
                                }
                            });
            assertIdentity(fixture, NEW_ID, OLD_ID);
            assertReloadedReference(fixture, NEW_ID, OLD_ID);
            domain.getCommandStack().undo();
            assertIdentity(fixture, OLD_ID, NEW_ID);
            assertReloadedReference(fixture, OLD_ID, NEW_ID);
            domain.getCommandStack().redo();
            assertIdentity(fixture, NEW_ID, OLD_ID);
            assertReloadedReference(fixture, NEW_ID, OLD_ID);
        } finally {
            domain.dispose();
        }
    }

    @Test
    public void sameIdAssignmentRepairsAnAlreadyStaleIndex() throws Exception {
        Fixture fixture = fixture("memory:/stale.capella");
        fixture.target.setId(NEW_ID);
        PersistentIds.setId(fixture.target, NEW_ID);
        assertIdentity(fixture, NEW_ID, OLD_ID);
        assertReloadedReference(fixture, NEW_ID, OLD_ID);
    }

    @Test
    public void collisionIsRejectedInTheSameOrAnotherLoadedResource() {
        Fixture fixture = fixture("memory:/collision.capella");
        assertThrows(
                IllegalArgumentException.class,
                () -> PersistentIds.setId(fixture.target, "source-function"));
        var other = new CapellamodellerResourceImpl(URI.createURI("memory:/other.capella"));
        fixture.resources.getResources().add(other);
        Project otherProject = CapellamodellerFactory.eINSTANCE.createProject();
        otherProject.setId("other-project");
        other.getContents().add(otherProject);
        assertThrows(
                IllegalArgumentException.class,
                () -> PersistentIds.setId(fixture.target, "other-project"));
        assertIdentity(fixture, OLD_ID, NEW_ID);
    }

    @Test
    public void unsafeIdsFailWithoutChangingTheObjectOrIndex() {
        Fixture fixture = fixture("memory:/invalid.capella");
        for (String id :
                new String[] {
                    null, "", " ", " id", "id ", "bad/id", "bad#id", "bad?id", "bad%20id", "bad\nid"
                }) {
            assertThrows(
                    IllegalArgumentException.class, () -> PersistentIds.setId(fixture.target, id));
            assertIdentity(fixture, OLD_ID, NEW_ID);
        }
    }

    @Test
    public void attachmentReattachmentAndDeletionDoNotLeaveIdMapEntries() {
        var resources = new ResourceSetImpl();
        var first = new CapellamodellerResourceImpl(URI.createURI("memory:/first.capella"));
        var second = new CapellamodellerResourceImpl(URI.createURI("memory:/second.capella"));
        resources.getResources().add(first);
        resources.getResources().add(second);
        Project project = CapellamodellerFactory.eINSTANCE.createProject();
        PersistentIds.setId(project, "detached-stable");
        first.getContents().add(project);
        assertSame(project, first.getEObject("detached-stable"));
        PersistentIds.setId(project, "attached-stable");
        second.getContents().add(project);
        assertNull(first.getEObject("detached-stable"));
        assertNull(first.getEObject("attached-stable"));
        assertSame(project, second.getEObject("attached-stable"));
        second.getContents().remove(project);
        assertNull(second.getEObject("attached-stable"));
        assertNull(second.getEObject("detached-stable"));
    }

    @Test
    public void nonXmlResourceAndIdUnsetKeepIntrinsicLookupConsistent() {
        var resource = new ResourceImpl(URI.createURI("memory:/plain"));
        Project project = CapellamodellerFactory.eINSTANCE.createProject();
        project.setId(OLD_ID);
        resource.getContents().add(project);
        resource.setIntrinsicIDToEObjectMap(new java.util.HashMap<>());
        assertSame(project, resource.getEObject(OLD_ID));
        PersistentIds.setId(project, NEW_ID);
        assertSame(project, resource.getEObject(NEW_ID));
        assertNull(resource.getEObject(OLD_ID));
        project.eUnset(project.eClass().getEIDAttribute());
        assertNull(resource.getEObject(NEW_ID));
    }

    @Test
    public void unsettableIdNotificationRemovesBothXmlIndexDirections() {
        var type = EcoreFactory.eINSTANCE.createEClass();
        type.setName("IndexedObject");
        var model = EcoreFactory.eINSTANCE.createEPackage();
        model.setName("indexedObjects");
        model.setNsPrefix("indexed");
        model.setNsURI("urn:capella-ai:persistent-id-test");
        model.getEClassifiers().add(type);
        var id = EcoreFactory.eINSTANCE.createEAttribute();
        id.setName("id");
        id.setEType(EcorePackage.Literals.ESTRING);
        id.setID(true);
        id.setUnsettable(true);
        type.getEStructuralFeatures().add(id);
        EObject object = new DynamicEObjectImpl(type);
        var resource = new XMLResourceImpl(URI.createURI("memory:/unset.xmi"));
        resource.getContents().add(object);
        PersistentIds.setId(object, NEW_ID);
        assertSame(object, resource.getEObject(NEW_ID));
        object.eUnset(id);
        assertNull(resource.getID(object));
        assertNull(resource.getEObject(NEW_ID));
    }

    private static void assertIdentity(Fixture fixture, String expected, String absent) {
        assertEquals(expected, fixture.target.getId());
        assertEquals(expected, fixture.resource.getURIFragment(fixture.target));
        assertSame(fixture.target, fixture.resource.getEObject(expected));
        assertNull(fixture.resource.getEObject(absent));
    }

    private static void assertReloadedReference(Fixture fixture, String expected, String absent)
            throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        fixture.resource.save(output, Map.of());
        String xml = output.toString(StandardCharsets.UTF_8);
        assertFalse(
                "The old target ID must not survive in a serialized reference",
                xml.contains(absent));
        var reloaded = new TestNativeResource(URI.createURI("memory:/reloaded.capella"));
        new ResourceSetImpl().getResources().add(reloaded);
        reloaded.load(new ByteArrayInputStream(output.toByteArray()), Map.of());
        assertTrue(reloaded.getErrors().toString(), reloaded.getErrors().isEmpty());
        EObject target = reloaded.getEObject(expected);
        assertNotNull(target);
        SystemFunction source = (SystemFunction) reloaded.getEObject("source-function");
        assertNotNull(source);
        assertSame(target, source.getOwnedFunctionRealizations().get(0).getTargetElement());
        assertFalse(source.getOwnedFunctionRealizations().get(0).getTargetElement().eIsProxy());
        assertNull(reloaded.getEObject(absent));
    }

    private static Fixture fixture(String uri) {
        var resources = new ResourceSetImpl();
        var resource = new TestNativeResource(URI.createURI(uri));
        resources.getResources().add(resource);
        Project project = CapellamodellerFactory.eINSTANCE.createProject();
        project.setId("project");
        var engineering = CapellamodellerFactory.eINSTANCE.createSystemEngineering();
        engineering.setId("engineering");
        project.getOwnedModelRoots().add(engineering);
        var sa = CtxFactory.eINSTANCE.createSystemAnalysis();
        sa.setId("sa");
        engineering.getOwnedArchitectures().add(sa);
        var systemFunctions = CtxFactory.eINSTANCE.createSystemFunctionPkg();
        systemFunctions.setId("sa-functions");
        sa.setOwnedFunctionPkg(systemFunctions);
        SystemFunction source = CtxFactory.eINSTANCE.createSystemFunction();
        source.setId("source-function");
        systemFunctions.getOwnedSystemFunctions().add(source);
        var oa = OaFactory.eINSTANCE.createOperationalAnalysis();
        oa.setId("oa");
        engineering.getOwnedArchitectures().add(oa);
        var activities = OaFactory.eINSTANCE.createOperationalActivityPkg();
        activities.setId("oa-activities");
        oa.setOwnedFunctionPkg(activities);
        OperationalActivity target = OaFactory.eINSTANCE.createOperationalActivity();
        target.setId(OLD_ID);
        activities.getOwnedOperationalActivities().add(target);
        var realization = FaFactory.eINSTANCE.createFunctionRealization();
        realization.setId("realization");
        realization.setSourceElement(source);
        realization.setTargetElement(target);
        source.getOwnedFunctionRealizations().add(realization);
        resource.getContents().add(project);
        return new Fixture(resources, resource, target);
    }

    private record Fixture(
            ResourceSetImpl resources,
            CapellamodellerResourceImpl resource,
            OperationalActivity target) {}

    private static final class TestNativeResource extends CapellamodellerResourceImpl {
        private TestNativeResource(URI uri) {
            super(uri);
        }

        @Override
        protected XMLSave createXMLSave() {
            if (Platform.isRunning()) {
                return super.createXMLSave();
            }
            // The real Capella writer's version-comment hook requires an OSGi FeatureHelper.
            // Omit only that comment in the standalone runner; retain its ID/reference writer.
            return new CapellaXMLSaveImpl(createXMLHelper()) {
                @Override
                protected void addDoctypeInformation() {}
            };
        }
    }
}
