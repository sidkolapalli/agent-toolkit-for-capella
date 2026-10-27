package com.sidkolapalli.capella.ai.scripting;

import static org.junit.Assert.*;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.sirius.business.api.session.Session;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import com.sidkolapalli.capella.ai.model.ModelOps;
import com.sidkolapalli.capella.ai.session.SessionSafeguards;
import org.polarsys.capella.core.data.capellacore.CapellacoreFactory;
import org.polarsys.capella.core.data.capellacore.Constraint;
import org.polarsys.capella.core.data.capellamodeller.CapellamodellerFactory;
import org.polarsys.capella.core.data.cs.BlockArchitecture;
import org.polarsys.capella.core.data.ctx.CtxFactory;
import org.polarsys.capella.core.data.epbs.EpbsFactory;
import org.polarsys.capella.core.data.la.LaFactory;
import org.polarsys.capella.core.data.oa.OaFactory;
import org.polarsys.capella.core.data.pa.PaFactory;
import org.polarsys.capella.core.model.handler.helpers.CapellaProjectHelper;
import org.polarsys.capella.core.model.handler.helpers.CapellaProjectHelper.ProjectApproach;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Real generated Requirements Viewpoint model, no dynamic substitute classes. */
public class RequirementsWorkflowTest {
    private Resource resource;
    private TransactionalEditingDomain domain;
    private Session session;
    private RequirementsAPI requirements;
    private CapellaAPI api;
    private byte[] saved;
    private final List<BlockArchitecture> architectures = new ArrayList<>();

    @Before
    public void setUp() {
        Assume.assumeTrue(
                "Optional native Requirements Viewpoint is not installed",
                new RequirementsAPI(null).available());
        ResourceSet resources = resourceSet();
        resource = resources.createResource(URI.createURI("memory:/requirements.xmi"));
        var project = CapellamodellerFactory.eINSTANCE.createProject();
        CapellaProjectHelper.setProjectWithApproach(project, ProjectApproach.SingletonComponents);
        var engineering = CapellamodellerFactory.eINSTANCE.createSystemEngineering();
        project.getOwnedModelRoots().add(engineering);
        resource.getContents().add(project);
        var oa = OaFactory.eINSTANCE.createOperationalAnalysis();
        oa.setOwnedEntityPkg(OaFactory.eINSTANCE.createEntityPkg());
        var sa = CtxFactory.eINSTANCE.createSystemAnalysis();
        sa.setOwnedSystemComponentPkg(CtxFactory.eINSTANCE.createSystemComponentPkg());
        var la = LaFactory.eINSTANCE.createLogicalArchitecture();
        la.setOwnedLogicalComponentPkg(LaFactory.eINSTANCE.createLogicalComponentPkg());
        var pa = PaFactory.eINSTANCE.createPhysicalArchitecture();
        pa.setOwnedPhysicalComponentPkg(PaFactory.eINSTANCE.createPhysicalComponentPkg());
        var epbs = EpbsFactory.eINSTANCE.createEPBSArchitecture();
        epbs.setOwnedConfigurationItemPkg(EpbsFactory.eINSTANCE.createConfigurationItemPkg());
        architectures.addAll(List.of(oa, sa, la, pa, epbs));
        engineering.getOwnedArchitectures().addAll(architectures);
        domain = TransactionalEditingDomain.Factory.INSTANCE.createEditingDomain(resources);
        session =
                (Session)
                        Proxy.newProxyInstance(
                                Session.class.getClassLoader(),
                                new Class<?>[] {Session.class},
                                (proxy, method, args) ->
                                        switch (method.getName()) {
                                            case "getTransactionalEditingDomain" -> domain;
                                            case "getSemanticResources" -> List.of(resource);
                                            case "getSessionResource" -> resource;
                                            case "isOpen" -> true;
                                            case "save" -> {
                                                ByteArrayOutputStream output =
                                                        new ByteArrayOutputStream();
                                                resource.save(output, Map.of());
                                                saved = output.toByteArray();
                                                yield null;
                                            }
                                            case "toString" -> "native requirements workflow";
                                            case "hashCode" -> System.identityHashCode(proxy);
                                            case "equals" -> proxy == args[0];
                                            default -> null;
                                        });
        requirements = new RequirementsAPI(session);
        api = new CapellaAPI(session);
    }

    @After
    public void tearDown() {
        if (domain != null) domain.dispose();
    }

    @Test
    public void realRequirementsCreateTraceEditUndoAndReloadEveryArchitecture() throws Exception {
        List<EObject> modules = new ArrayList<>();
        List<EObject> sourceRequirements = new ArrayList<>();
        String[] layers = {"OA", "SA", "LA", "PA", "EPBS"};
        String[] componentTypes = {
            "Entity", "SystemActor", "LogicalComponent", "PhysicalComponent", "ConfigurationItem"
        };
        SessionSafeguards.runWrite(
                session,
                "Create source requirements workflow",
                () -> {
                    for (int i = 0; i < layers.length; i++) {
                        String layer = layers[i];
                        EObject module =
                                requirements.createModule(
                                        architectures.get(i),
                                        "source:" + layer,
                                        layer + " rulebook requirements");
                        assertSame(
                                module,
                                requirements.createModule(
                                        architectures.get(i),
                                        "source:" + layer,
                                        "ignored duplicate title"));
                        modules.add(module);
                        EObject folder =
                                requirements.createFolder(
                                        module, "section:" + layer, "Source section");
                        EObject requirement =
                                requirements.create(
                                        folder,
                                        "clause:" + layer,
                                        "Source clause",
                                        "A short authored paraphrase");
                        sourceRequirements.add(requirement);
                        assertEquals(
                                "org.polarsys.kitalpha.vp.requirements.Requirements.impl.RequirementImpl",
                                requirement.getClass().getName());
                        requirements.setAttribute(requirement, "applicability", "applicable");
                        requirements.setAttribute(requirement, "source_page", 1);
                        requirements.setAttribute(requirement, "reviewed", false);
                        requirements.setAttribute(requirement, "source_fact", 0.5d);
                        assertSame(
                                requirement,
                                requirements.create(
                                        folder,
                                        "clause:" + layer,
                                        "ignored duplicate title",
                                        "A short authored paraphrase"));
                        EObject component =
                                api.create(
                                        layer,
                                        componentTypes[i],
                                        layer + " responsible design element");
                        EObject satisfy = requirements.satisfy(component, requirement);
                        assertSame(satisfy, requirements.satisfy(component, requirement));
                        assertSame(component, value(satisfy, "source"));
                        assertSame(requirement, value(satisfy, "target"));
                        assertSame(component, satisfy.eContainer());
                        Constraint verification = CapellacoreFactory.eINSTANCE.createConstraint();
                        verification.setName(layer + " verification obligation");
                        architectures.get(i).getOwnedConstraints().add(verification);
                        EObject verify = requirements.verify(requirement, verification);
                        assertSame(requirement, verify.eContainer());
                        assertSame(requirement, value(verify, "source"));
                        assertSame(verification, value(verify, "target"));
                        EObject derived =
                                requirements.create(
                                        folder,
                                        "derived:" + layer,
                                        "Derived design requirement",
                                        "Authored design requirement");
                        EObject derive = requirements.derive(derived, requirement);
                        assertSame(derived, derive.eContainer());
                        assertSame(derive, requirements.derive(derived, requirement));
                    }
                    return null;
                });
        assertEquals(5, requirements.listModules().size());
        assertNotNull(saved);
        domain.getCommandStack().undo();
        assertTrue(requirements.listModules().isEmpty());
        domain.getCommandStack().redo();
        assertEquals(5, requirements.listModules().size());
        requirements.edit(sourceRequirements.get(0), "Edited title", "Edited short text");
        requirements.setAttribute(sourceRequirements.get(0), "reviewed", true);
        assertEquals(true, requirements.attributes(sourceRequirements.get(0)).get("reviewed"));
        Resource reloaded = resourceSet().createResource(URI.createURI("memory:/reloaded.xmi"));
        reloaded.load(new ByteArrayInputStream(saved), Map.of());
        for (String layer : layers) {
            EObject requirement = byIdentifier(reloaded, "clause:" + layer);
            assertNotNull(requirement);
            assertEquals(4, list(requirement, "ownedAttributes").size());
            assertNotNull(value(requirement, "requirementType"));
            EObject incoming = list(requirement, "ownedRelations").get(0);
            assertEquals("CapellaIncomingRelation", incoming.eClass().getName());
            assertFalse(((EObject) value(incoming, "target")).eIsProxy());
            assertEquals(
                    2,
                    requirements
                            .requirements(
                                    modules.get(java.util.Arrays.asList(layers).indexOf(layer)))
                            .size());
        }
        assertEquals("Edited title", value(byIdentifier(reloaded, "clause:OA"), "ReqIFLongName"));
        assertNoDetachedReferences(reloaded);
    }

    @Test
    public void deletingRequirementClosesAllNativeRelationsAndUndoRestoresThem() throws Exception {
        EObject module = requirements.createModule(architectures.get(1), "source", "Source rules");
        EObject requirement = requirements.create(module, "rule:1", "Source rule", "Short text");
        EObject derived = requirements.create(module, "rule:derived", "Derived rule", "Short text");
        EObject component = api.create("SA", "SystemActor", "Actor");
        EObject outgoing = requirements.satisfy(component, requirement);
        EObject internal = requirements.derive(derived, requirement);
        assertTrue(requirements.delete(requirement));
        assertNull(requirements.find("rule:1"));
        assertNull(outgoing.eContainer());
        assertNull(internal.eContainer());
        assertNoDetachedReferences(resource);
        domain.getCommandStack().undo();
        assertSame(requirement, requirements.find("rule:1"));
        assertSame(component, outgoing.eContainer());
        assertSame(derived, internal.eContainer());
        assertNoDetachedReferences(resource);
        domain.getCommandStack().redo();
        SessionSafeguards.requestSave(session);
        Resource reloaded =
                resourceSet().createResource(URI.createURI("memory:/delete-reloaded.xmi"));
        reloaded.load(new ByteArrayInputStream(saved), Map.of());
        assertNoDetachedReferences(reloaded);
        assertNull(byIdentifier(reloaded, "rule:1"));
    }

    @Test
    public void deletingDesignEndpointRemovesNativeRequirementRelation() {
        EObject module = requirements.createModule(architectures.get(1), "source", "Source rules");
        EObject requirement = requirements.create(module, "rule:1", "Source rule", "Short text");
        EObject component = api.create("SA", "SystemActor", "Actor");
        EObject relation = requirements.satisfy(component, requirement);
        assertTrue(ModelOps.deleteByUuid(session, (String) value(component, "id")));
        assertNull(relation.eResource());
        assertSame(requirement, requirements.find("rule:1"));
        assertNoDetachedReferences(resource);
        domain.getCommandStack().undo();
        assertSame(component, relation.eContainer());
        assertSame(requirement, value(relation, "target"));
    }

    @Test
    public void rejectsWrongContainersForeignElementsDuplicateIdsAndTypeChanges() {
        EObject module = requirements.createModule(architectures.get(1), "source", "Source rules");
        EObject requirement = requirements.create(module, "rule:1", "Source rule", "Short text");
        requirements.setAttribute(requirement, "applicability", "applicable");
        int before = count();
        rejects(
                () -> requirements.create(architectures.get(1), "invalid", "Title", "Text"),
                "module or folder");
        rejects(() -> requirements.createModule(requirement, "invalid", "Title"), "architecture");
        rejects(
                () -> requirements.setAttribute(requirement, "applicability", true),
                "different native value type");
        rejects(() -> requirements.verify(requirement, architectures.get(1)), "Constraint");
        rejects(
                () -> requirements.satisfy(OaFactory.eINSTANCE.createEntity(), requirement),
                "active model session");
        rejects(() -> requirements.derive(requirement, requirement), "derive itself");
        assertEquals(before, count());
        EObject secondModule =
                requirements.createModule(architectures.get(2), "other", "Other rules");
        int afterModule = count();
        rejects(
                () -> requirements.create(secondModule, "rule:1", "Duplicate", "Text"),
                "already used");
        assertEquals(afterModule, count());
        assertTrue(count() >= before);
        assertEquals("applicable", requirements.attributes(requirement).get("applicability"));
    }

    @Test
    public void ledgerImportIsAtomicIdempotentAndKeepsSourceIdentifiers() {
        EObject module = requirements.createModule(architectures.get(1), "source", "Source rules");
        List<Map<String, Object>> records =
                List.of(
                        Map.of(
                                "identifier",
                                "FSAE-2027-V1:EV.1.1:p001",
                                "title",
                                "Source clause",
                                "chapter",
                                "EV.1.1",
                                "attributes",
                                Map.of("applicability", "applicable", "source_page", 1)),
                        Map.of(
                                "identifier",
                                "FSAE-2027-V1:IC.1.1:p001",
                                "title",
                                "Out of scope clause",
                                "attributes",
                                Map.of(
                                        "applicability",
                                        "not_applicable",
                                        "applicability_reason",
                                        "Driver operated electric baseline")));
        List<EObject> imported = requirements.importRequirements(module, records);
        assertEquals(2, imported.size());
        assertEquals("EV.1.1", value(imported.get(0), "ReqIFChapterName"));
        assertNull(value(imported.get(0), "ReqIFText"));
        assertEquals(
                "not_applicable", requirements.attributes(imported.get(1)).get("applicability"));
        int before = count();
        assertEquals(imported, requirements.importRequirements(module, records));
        assertEquals(before, count());
        rejects(
                () ->
                        requirements.importRequirements(
                                module,
                                List.of(
                                        Map.of("identifier", "new:valid", "title", "First record"),
                                        Map.of(
                                                "identifier",
                                                "new:invalid",
                                                "title",
                                                "Second record",
                                                "attributes",
                                                Map.of("bad", List.of("unsupported"))))),
                "String, Boolean, Number or Date");
        assertNull(requirements.find("new:valid"));
        assertNull(requirements.find("new:invalid"));
        assertEquals(before, count());
    }

    private static void rejects(Runnable operation, String text) {
        try {
            operation.run();
            fail("Expected rejection: " + text);
        } catch (IllegalArgumentException failure) {
            assertTrue(failure.getMessage(), failure.getMessage().contains(text));
        }
    }

    private int count() {
        int result = 0;
        var contents = resource.getAllContents();
        while (contents.hasNext()) {
            contents.next();
            result++;
        }
        return result;
    }

    private static ResourceSet resourceSet() {
        ResourceSet resources = new ResourceSetImpl();
        resources
                .getResourceFactoryRegistry()
                .getExtensionToFactoryMap()
                .put("xmi", new XMIResourceFactoryImpl());
        return resources;
    }

    private static EObject byIdentifier(Resource resource, String identifier) {
        var contents = resource.getAllContents();
        while (contents.hasNext()) {
            EObject item = contents.next();
            if (identifier.equals(value(item, "ReqIFIdentifier"))) return item;
        }
        return null;
    }

    private static Object value(EObject object, String name) {
        var feature = object.eClass().getEStructuralFeature(name);
        return feature == null ? null : object.eGet(feature);
    }

    @SuppressWarnings("unchecked")
    private static List<EObject> list(EObject object, String feature) {
        return (List<EObject>) value(object, feature);
    }

    private static void assertNoDetachedReferences(Resource resource) {
        var contents = resource.getAllContents();
        while (contents.hasNext()) {
            EObject owner = contents.next();
            for (var reference : owner.eClass().getEAllReferences()) {
                if (reference.isDerived()
                        || reference.isTransient()
                        || reference.isContainment()
                        || !owner.eIsSet(reference)) continue;
                Object referenced = owner.eGet(reference, false);
                List<?> values = reference.isMany() ? (List<?>) referenced : List.of(referenced);
                for (Object value : values) {
                    if (value instanceof EObject object) {
                        assertFalse(
                                owner.eClass().getName() + "." + reference.getName(),
                                object.eIsProxy());
                        assertNotNull(
                                owner.eClass().getName() + "." + reference.getName(),
                                object.eResource());
                    }
                }
            }
        }
    }
}
