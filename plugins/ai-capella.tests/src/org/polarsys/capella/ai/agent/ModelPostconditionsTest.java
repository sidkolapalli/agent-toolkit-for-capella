package org.polarsys.capella.ai.agent;

import static org.junit.Assert.*;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.impl.DynamicEObjectImpl;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.eclipse.emf.transaction.RecordingCommand;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.swt.widgets.Display;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.polarsys.capella.ai.agent.ArcadiaAgent.Postcondition;
import org.polarsys.capella.ai.agent.ModelPostconditions.Status;
import org.polarsys.capella.core.data.capellacore.CapellaElement;
import org.polarsys.capella.core.data.capellacore.CapellacoreFactory;
import org.polarsys.capella.core.data.capellacore.CapellacorePackage;
import org.polarsys.capella.core.data.capellacore.NamedElement;
import org.polarsys.capella.core.data.capellamodeller.CapellamodellerFactory;
import org.polarsys.capella.core.data.cs.CsFactory;
import org.polarsys.capella.core.data.ctx.CtxFactory;
import org.polarsys.capella.core.data.epbs.ConfigurationItem;
import org.polarsys.capella.core.data.epbs.ConfigurationItemKind;
import org.polarsys.capella.core.data.epbs.ConfigurationItemPkg;
import org.polarsys.capella.core.data.epbs.EpbsFactory;
import org.polarsys.capella.core.data.la.LaFactory;
import org.polarsys.capella.core.data.oa.OaFactory;
import org.polarsys.capella.core.data.pa.PaFactory;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Native five-layer semantic resources and editing domain; only Sirius Session is a proxy. */
public class ModelPostconditionsTest {
    private Resource resource;
    private TransactionalEditingDomain domain;
    private Session session;
    private boolean open;
    private final List<EObject> samples = new ArrayList<>();
    private final AtomicInteger saves = new AtomicInteger();

    @Before
    public void model() {
        Display.getDefault();
        var resources = new ResourceSetImpl();
        resources
                .getResourceFactoryRegistry()
                .getExtensionToFactoryMap()
                .put("xmi", new XMIResourceFactoryImpl());
        resource = resources.createResource(URI.createURI("memory:/postconditions.xmi"));
        var project = CapellamodellerFactory.eINSTANCE.createProject();
        var engineering = CapellamodellerFactory.eINSTANCE.createSystemEngineering();
        project.getOwnedModelRoots().add(engineering);
        resource.getContents().add(project);
        var oa = OaFactory.eINSTANCE.createOperationalAnalysis();
        oa.setOwnedEntityPkg(OaFactory.eINSTANCE.createEntityPkg());
        var entity = OaFactory.eINSTANCE.createEntity();
        entity.setActor(true);
        oa.getOwnedEntityPkg().getOwnedEntities().add(entity);
        var sa = CtxFactory.eINSTANCE.createSystemAnalysis();
        sa.setOwnedSystemComponentPkg(CtxFactory.eINSTANCE.createSystemComponentPkg());
        var systemActor = CtxFactory.eINSTANCE.createSystemComponent();
        systemActor.setActor(true);
        sa.getOwnedSystemComponentPkg().getOwnedSystemComponents().add(systemActor);
        var la = LaFactory.eINSTANCE.createLogicalArchitecture();
        la.setOwnedLogicalComponentPkg(LaFactory.eINSTANCE.createLogicalComponentPkg());
        var logical = LaFactory.eINSTANCE.createLogicalComponent();
        logical.setActor(true);
        la.getOwnedLogicalComponentPkg().getOwnedLogicalComponents().add(logical);
        var pa = PaFactory.eINSTANCE.createPhysicalArchitecture();
        pa.setOwnedPhysicalComponentPkg(PaFactory.eINSTANCE.createPhysicalComponentPkg());
        var physical = PaFactory.eINSTANCE.createPhysicalComponent();
        physical.setActor(true);
        pa.getOwnedPhysicalComponentPkg().getOwnedPhysicalComponents().add(physical);
        var epbs = EpbsFactory.eINSTANCE.createEPBSArchitecture();
        epbs.setOwnedConfigurationItemPkg(EpbsFactory.eINSTANCE.createConfigurationItemPkg());
        var item = EpbsFactory.eINSTANCE.createConfigurationItem();
        item.setKind(ConfigurationItemKind.SYSTEM_CI);
        epbs.getOwnedConfigurationItemPkg().getOwnedConfigurationItems().add(item);
        engineering.getOwnedArchitectures().addAll(List.of(oa, sa, la, pa, epbs));
        samples.addAll(List.of(entity, systemActor, logical, physical, item));
        for (int index = 0; index < samples.size(); index++) {
            ((CapellaElement) samples.get(index)).setId("post-" + index);
            ((NamedElement) samples.get(index)).setName("Native candidate ⚡ " + index);
        }
        domain = TransactionalEditingDomain.Factory.INSTANCE.createEditingDomain(resources);
        open = true;
        session = session(List.of(resource));
    }

    @After
    public void dispose() {
        if (domain != null) domain.dispose();
    }

    @Test
    public void allFiveNativeTypesAndActorAliasesAreIndependentlyChecked() {
        var conditions = new ArrayList<Postcondition>();
        for (int index = 0; index < samples.size(); index++) {
            conditions.add(
                    named(
                            "s" + index,
                            "element_exists",
                            samples.get(index).eClass().getName(),
                            ((NamedElement) samples.get(index)).getName(),
                            List.of("OA", "SA", "LA", "PA", "EPBS").get(index)));
        }
        var report = ModelPostconditions.check(session, conditions);
        assertTrue(report.markdownSummary(), report.allPassed());
        assertEquals(5, report.completedStepIds().size());
        assertTrue(report.markdownSummary().contains("independently checked"));
        assertTrue(report.markdownSummary().contains("does not establish goal completeness"));
        for (int index = 0; index < 4; index++) {
            assertTrue(
                    ModelPostconditions.check(
                                    session,
                                    List.of(
                                            named(
                                                    "actor",
                                                    "element_exists",
                                                    List.of(
                                                                    "OperationalActor",
                                                                    "SystemActor",
                                                                    "LogicalActor",
                                                                    "PhysicalActor")
                                                            .get(index),
                                                    ((NamedElement) samples.get(index)).getName(),
                                                    List.of("OA", "SA", "LA", "PA").get(index))))
                            .allPassed());
        }
    }

    /** Stress test: checks on a 312,000-object model stopped at the old 250,000-object scan cap. */
    @Test
    public void checksCompleteOnModelsAboveAQuarterMillionObjects() {
        write(
                () -> {
                    for (int i = 0; i < 260_000; i++)
                        resource.getContents()
                                .add(
                                        org.polarsys.capella.core.data.capellacore
                                                .CapellacoreFactory.eINSTANCE
                                                .createKeyValue());
                });
        var name = ((NamedElement) samples.get(2)).getName();
        var nullSelector =
                new java.util.HashMap<String, Object>(
                        Map.of(
                                "kind",
                                "reference_equals",
                                "target",
                                Map.of("id", "post-2"),
                                "feature",
                                "ownedDataPkg"));
        nullSelector.put("expected", null);
        var report =
                ModelPostconditions.check(
                        session,
                        List.of(
                                named("large", "element_exists", "LogicalComponent", name, "LA"),
                                named(
                                        "large",
                                        "element_absent",
                                        "LogicalComponent",
                                        "No such component",
                                        "LA"),
                                new Postcondition(
                                        "large",
                                        "native_structure",
                                        Map.of("check", nullSelector))));
        assertEquals(
                report.markdownSummary(),
                List.of(Status.PASSED, Status.PASSED, Status.PASSED),
                report.results().stream().map(ModelPostconditions.Result::status).toList());
    }

    @Test
    public void actorAliasDoesNotMatchAnOrdinaryComponent() {
        write(
                () ->
                        ((org.polarsys.capella.core.data.ctx.SystemComponent) samples.get(1))
                                .setActor(false));
        var result =
                ModelPostconditions.check(
                        session,
                        List.of(
                                named(
                                        "actor",
                                        "element_exists",
                                        "SystemActor",
                                        "Native candidate ⚡ 1",
                                        "SA")));
        assertEquals(Status.FAILED, result.results().get(0).status());
    }

    @Test
    public void nativeIdExistenceAbsenceAndWrongLayerNamesStayScoped() {
        var report =
                ModelPostconditions.check(
                        session,
                        List.of(
                                id("exists", "element_exists", "post-4"),
                                        id("absent", "element_absent", "unknown-native-id"),
                                id("still-there", "element_absent", "post-4"),
                                        named(
                                                "wrong-layer",
                                                "element_exists",
                                                "ConfigurationItem",
                                                "Native candidate ⚡ 4",
                                                "SA")));
        assertEquals(Set.of("exists", "absent"), report.completedStepIds());
        assertFalse(report.allPassed());
    }

    @Test
    public void sameNamePartIsNotAConfigurationItemAndDuplicateTypedNamesFail() {
        write(
                () -> {
                    var owner = (ConfigurationItem) samples.get(4);
                    var part = CsFactory.eINSTANCE.createPart();
                    part.setName(owner.getName());
                    part.setAbstractType(owner);
                    ((ConfigurationItemPkg) owner.eContainer()).getOwnedParts().add(part);
                });
        Postcondition unique =
                named("ci", "element_exists", "ConfigurationItem", "Native candidate ⚡ 4", "EPBS");
        assertTrue(ModelPostconditions.check(session, List.of(unique)).allPassed());
        write(
                () -> {
                    var duplicate = EpbsFactory.eINSTANCE.createConfigurationItem();
                    duplicate.setName("Native candidate ⚡ 4");
                    ((ConfigurationItem) samples.get(4))
                            .getOwnedConfigurationItems()
                            .add(duplicate);
                });
        assertEquals(
                Status.FAILED,
                ModelPostconditions.check(session, List.of(unique)).results().get(0).status());
        var absent =
                named("ci", "element_absent", "ConfigurationItem", "Native candidate ⚡ 4", "EPBS");
        assertEquals(
                Status.FAILED,
                ModelPostconditions.check(session, List.of(absent)).results().get(0).status());
    }

    @Test
    public void absenceOfUnknownTypesOrDiagramTypesCannotVerifyAStep() {
        for (String type :
                List.of("Unicorn", "DSemanticDiagram", "DDiagram", "LogicalFunctionTypo")) {
            var report =
                    ModelPostconditions.check(
                            session,
                            List.of(named("delete", "element_absent", type, "Anything", null)));
            assertEquals(type, Status.UNAVAILABLE, report.results().get(0).status());
            assertTrue(report.completedStepIds().isEmpty());
        }
    }

    @Test
    public void storedStringBooleanEnumNumberAndNullCompareActualNativeValues() {
        var number = CapellacoreFactory.eINSTANCE.createIntegerPropertyValue();
        number.setId("post-number");
        number.setValue(7);
        write(
                () -> {
                    ((CapellaElement) samples.get(1)).getOwnedPropertyValues().add(number);
                    ((NamedElement) samples.get(0)).setSummary(null);
                });
        Object nullValue =
                samples.get(0)
                        .eGet(samples.get(0).eClass().getEStructuralFeature("summary"), false);
        var report =
                ModelPostconditions.check(
                        session,
                        List.of(
                                attribute("values", "post-1", "name", "Native candidate ⚡ 1"),
                                attribute("values", "post-1", "actor", true),
                                attribute(
                                        "values",
                                        "post-4",
                                        "kind",
                                        ConfigurationItemKind.SYSTEM_CI.getLiteral()),
                                attribute("values", "post-number", "value", 7.0),
                                attribute("values", "post-0", "summary", nullValue)));
        assertTrue(report.markdownSummary(), report.allPassed());
        assertEquals(Set.of("values"), report.completedStepIds());
        assertEquals(7, report.results().get(3).actual());
    }

    @Test
    public void oneFailedPredicateLeavesItsWholeStepPendingWhileOtherStepsPass() {
        var report =
                ModelPostconditions.check(
                        session,
                        List.of(
                                id("edit", "element_exists", "post-1"),
                                attribute("edit", "post-1", "name", "Unobserved new name"),
                                id("other", "element_exists", "post-2")));
        assertEquals(Set.of("other"), report.completedStepIds());
        assertFalse(report.allPassed());
    }

    @Test
    public void referencesMissingAttributesAndDerivedAttributesAreNeverEvaluated() {
        var klass = EcoreFactory.eINSTANCE.createEClass();
        klass.setName("DerivedProbe");
        klass.getESuperTypes().add(CapellacorePackage.Literals.NAMED_ELEMENT);
        var derived = EcoreFactory.eINSTANCE.createEAttribute();
        derived.setName("computed");
        derived.setEType(EcorePackage.Literals.ESTRING);
        derived.setDerived(true);
        derived.setVolatile(true);
        klass.getEStructuralFeatures().add(derived);
        var model = EcoreFactory.eINSTANCE.createEPackage();
        model.setName("test");
        model.setNsPrefix("test");
        model.setNsURI("memory:/test-metamodel");
        model.getEClassifiers().add(klass);
        var probe = new DynamicEObjectImpl(klass);
        probe.eSet(klass.getEStructuralFeature("id"), "derived-probe");
        write(() -> resource.getContents().add(probe));
        for (Postcondition condition :
                List.of(
                        attribute("unsupported", "post-4", "ownedFeatures", null),
                        attribute("unsupported", "post-4", "missing", null),
                        attribute("unsupported", "derived-probe", "computed", null))) {
            var report = ModelPostconditions.check(session, List.of(condition));
            assertEquals(Status.UNAVAILABLE, report.results().get(0).status());
            assertTrue(report.completedStepIds().isEmpty());
        }
    }

    @Test
    public void boundedSearchCannotClaimAbsenceOrUniqueExistence() throws Exception {
        var missing = named("missing", "element_absent", "ConfigurationItem", "Missing", "EPBS");
        var existing =
                named(
                        "existing",
                        "element_exists",
                        "ConfigurationItem",
                        "Native candidate ⚡ 4",
                        "EPBS");
        // Test bundle and production bundle have distinct OSGi class loaders.
        var method =
                ModelPostconditions.class.getDeclaredMethod(
                        "check", Session.class, List.class, int.class);
        method.setAccessible(true);
        var report =
                (ModelPostconditions.Report)
                        method.invoke(null, session, List.of(missing, existing), 1);
        assertTrue(
                report.results().stream()
                        .allMatch(result -> result.status() == Status.UNAVAILABLE));
        assertTrue(report.completedStepIds().isEmpty());
    }

    @Test
    public void foreignReferencesAreNotTraversedOrResolvedAndForeignSemanticSetsFailClosed() {
        Resource foreign =
                domain.getResourceSet().createResource(URI.createURI("memory:/foreign.xmi"));
        var foreignItem = EpbsFactory.eINSTANCE.createConfigurationItem();
        foreignItem.setName("PRIVATE FOREIGN VALUE");
        foreignItem.setId("foreign-ci");
        var proxy = LaFactory.eINSTANCE.createLogicalComponent();
        ((InternalEObject) proxy)
                .eSetProxyURI(URI.createURI("memory:/never-load.xmi#private-target"));
        write(
                () -> {
                    foreign.getContents().add(foreignItem);
                    var part = CsFactory.eINSTANCE.createPart();
                    part.setAbstractType(proxy);
                    ((ConfigurationItem) samples.get(4)).getOwnedFeatures().add(part);
                });
        int resources = domain.getResourceSet().getResources().size();
        var report =
                ModelPostconditions.check(
                        session,
                        List.of(
                                id("valid", "element_exists", "post-4"),
                                named(
                                        "foreign",
                                        "element_absent",
                                        "ConfigurationItem",
                                        "PRIVATE FOREIGN VALUE",
                                        "EPBS")));
        assertTrue(report.allPassed());
        assertFalse(report.feedback().toString().contains("foreign-ci"));
        assertEquals(resources, domain.getResourceSet().getResources().size());
        assertTrue(proxy.eIsProxy());
        var foreignSet = new ResourceSetImpl();
        Resource wrong =
                new org.eclipse.emf.ecore.xmi.impl.XMIResourceImpl(
                        URI.createURI("memory:/wrong.xmi"));
        foreignSet.getResources().add(wrong);
        assertEquals(
                Status.UNAVAILABLE,
                ModelPostconditions.check(
                                session(List.of(wrong)), List.of(id("x", "element_absent", "none")))
                        .results()
                        .get(0)
                        .status());
    }

    @Test
    public void aContainedProxyMakesNamedAbsenceUnavailable() {
        var proxy = EpbsFactory.eINSTANCE.createConfigurationItem();
        ((InternalEObject) proxy).eSetProxyURI(URI.createURI("memory:/never-load.xmi#missing"));
        write(() -> ((ConfigurationItem) samples.get(4)).getOwnedConfigurationItems().add(proxy));
        int resources = domain.getResourceSet().getResources().size();
        var report =
                ModelPostconditions.check(
                        session,
                        List.of(
                                named(
                                        "x",
                                        "element_absent",
                                        "ConfigurationItem",
                                        "Missing",
                                        "EPBS")));
        assertEquals(Status.UNAVAILABLE, report.results().get(0).status());
        assertTrue(proxy.eIsProxy());
        assertEquals(resources, domain.getResourceSet().getResources().size());
    }

    @Test
    public void checksNeverSaveChangeBytesUndoRedoOrInstallAdapters() throws Exception {
        byte[] before = bytes();
        resource.setModified(false);
        Object undo = domain.getCommandStack().getUndoCommand(),
                redo = domain.getCommandStack().getRedoCommand();
        int setAdapters = domain.getResourceSet().eAdapters().size(),
                resourceAdapters = resource.eAdapters().size();
        int elementAdapters = samples.get(4).eAdapters().size();
        assertTrue(
                ModelPostconditions.check(
                                session,
                                List.of(
                                        id("id", "element_exists", "post-4"),
                                        named(
                                                "name",
                                                "element_exists",
                                                "ConfigurationItem",
                                                "Native candidate ⚡ 4",
                                                "EPBS"),
                                        attribute(
                                                "attr",
                                                "post-4",
                                                "kind",
                                                ConfigurationItemKind.SYSTEM_CI.getLiteral())))
                        .allPassed());
        assertFalse(resource.isModified());
        assertEquals(0, saves.get());
        assertSame(undo, domain.getCommandStack().getUndoCommand());
        assertSame(redo, domain.getCommandStack().getRedoCommand());
        assertEquals(setAdapters, domain.getResourceSet().eAdapters().size());
        assertEquals(resourceAdapters, resource.eAdapters().size());
        assertEquals(elementAdapters, samples.get(4).eAdapters().size());
        assertArrayEquals(before, bytes());
    }

    @Test
    public void closedMissingDomainAndEmptyDeclarationsCannotCompleteAnything() {
        open = false;
        assertEquals(
                Status.UNAVAILABLE,
                ModelPostconditions.check(session, List.of(id("x", "element_exists", "post-4")))
                        .results()
                        .get(0)
                        .status());
        open = true;
        var noDomain =
                (Session)
                        Proxy.newProxyInstance(
                                Session.class.getClassLoader(),
                                new Class<?>[] {Session.class},
                                (proxy, method, args) ->
                                        method.getName().equals("isOpen") ? true : null);
        assertFalse(
                ModelPostconditions.check(noDomain, List.of(id("x", "element_absent", "none")))
                        .allPassed());
        assertFalse(ModelPostconditions.check(session, List.of()).allPassed());
        assertTrue(ModelPostconditions.check(session, List.of()).completedStepIds().isEmpty());
    }

    @Test
    public void comparisonUsesWholeValueWhileFeedbackAndMarkdownAreBoundedData() {
        String name = "<script>** ⚡ ".repeat(150);
        write(() -> ((NamedElement) samples.get(1)).setName(name));
        var report =
                ModelPostconditions.check(
                        session,
                        List.of(
                                attribute("exact", "post-1", "name", name),
                                attribute(
                                        "different",
                                        "post-1",
                                        "name",
                                        name.substring(0, name.length() - 1))));
        assertEquals(Set.of("exact"), report.completedStepIds());
        assertTrue(report.feedback().toString().contains("[truncated]"));
        assertFalse(report.markdownSummary().contains("<script>"));
        assertTrue(report.markdownSummary().contains("&lt;script&gt;"));
        assertTrue(report.markdownSummary().length() < 5000);
    }

    @Test
    public void invalidSelectorsAndInputBoundsCannotBeExecutablePredicates() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Postcondition(
                                "x",
                                "attribute_equals",
                                Map.of(
                                        "id",
                                        "post-1",
                                        "attribute",
                                        "name",
                                        "expected",
                                        "x".repeat(2401))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelPostconditions.check(
                                session,
                                java.util.Collections.nCopies(
                                        13, id("x", "element_exists", "post-1"))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Postcondition(
                                "x",
                                "element_exists",
                                Map.of("type", "SystemComponent", "name", "x", "aql", "self")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Postcondition(
                                "x", "element_exists", Map.of("id", "post-1", "layer", "SA")));
        var uri = id("x", "element_exists", "memory:/foreign.xmi#secret");
        assertEquals(
                Status.UNAVAILABLE,
                ModelPostconditions.check(session, List.of(uri)).results().get(0).status());
    }

    private Postcondition id(String step, String kind, String id) {
        return new Postcondition(step, kind, Map.of("id", id));
    }

    private Postcondition named(String step, String kind, String type, String name, String layer) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("type", type);
        args.put("name", name);
        if (layer != null) args.put("layer", layer);
        return new Postcondition(step, kind, args);
    }

    private Postcondition attribute(String step, String id, String name, Object expected) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("id", id);
        args.put("attribute", name);
        args.put("expected", expected);
        return new Postcondition(step, "attribute_equals", args);
    }

    private Session session(List<Resource> semantic) {
        return (Session)
                Proxy.newProxyInstance(
                        Session.class.getClassLoader(),
                        new Class<?>[] {Session.class},
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "isOpen" -> open;
                                    case "getTransactionalEditingDomain" -> domain;
                                    case "getSemanticResources" -> semantic;
                                    case "getSessionResource" -> resource;
                                    case "save" -> {
                                        saves.incrementAndGet();
                                        throw new AssertionError(
                                                "Postcondition check saved the model");
                                    }
                                    case "hashCode" -> System.identityHashCode(proxy);
                                    case "equals" -> proxy == args[0];
                                    default -> null;
                                });
    }

    private void write(Runnable action) {
        domain.getCommandStack()
                .execute(
                        new RecordingCommand(domain) {
                            @Override
                            protected void doExecute() {
                                action.run();
                            }
                        });
    }

    private byte[] bytes() throws Exception {
        var output = new ByteArrayOutputStream();
        resource.save(output, Map.of());
        return output.toByteArray();
    }
}
