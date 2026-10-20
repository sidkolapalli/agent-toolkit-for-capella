package org.polarsys.capella.ai.agent;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
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
import org.polarsys.capella.ai.agent.NativeStructuralAssertions.Status;
import org.polarsys.capella.core.data.capellacore.CapellaElement;
import org.polarsys.capella.core.data.capellacore.NamedElement;
import org.polarsys.capella.core.data.capellamodeller.CapellamodellerFactory;
import org.polarsys.capella.core.data.ctx.CtxFactory;
import org.polarsys.capella.core.data.ctx.SystemFunction;
import org.polarsys.capella.core.data.fa.FaFactory;
import org.polarsys.capella.core.data.fa.FunctionRealization;
import org.polarsys.capella.core.data.fa.FunctionalChain;
import org.polarsys.capella.core.data.fa.FunctionalChainInvolvementFunction;
import org.polarsys.capella.core.data.fa.FunctionalChainInvolvementLink;
import org.polarsys.capella.core.data.interaction.InstanceRole;
import org.polarsys.capella.core.data.interaction.InteractionFactory;
import org.polarsys.capella.core.data.interaction.Scenario;
import org.polarsys.capella.core.data.interaction.ScenarioKind;
import org.polarsys.capella.core.data.la.LaFactory;
import org.polarsys.capella.core.data.la.LogicalFunction;
import org.polarsys.capella.core.data.oa.OaFactory;
import org.polarsys.capella.core.data.pa.PaFactory;
import org.polarsys.capella.core.data.epbs.EpbsFactory;
import org.polarsys.kitalpha.ad.metadata.metadata.MetadataFactory;

/** Actual Capella objects and read transactions; one explicitly labeled dynamic fixture tests unordered metadata. */
public class NativeStructuralAssertionsTest {
    private ResourceSetImpl resources;
    private Resource resource;
    private TransactionalEditingDomain domain;
    private Session session;
    private SystemFunction system, secondSystem;
    private LogicalFunction logical;
    private FunctionRealization realization;
    private FunctionalChain chain;
    private FunctionalChainInvolvementFunction start, finish;
    private FunctionalChainInvolvementLink link;
    private Scenario nominal, inhibited;
    private InstanceRole nominalRole, inhibitedRole;
    private boolean open;
    private final AtomicInteger saves = new AtomicInteger();

    @Before
    public void model() {
        Display.getDefault();
        resources = new ResourceSetImpl();
        resources.getResourceFactoryRegistry().getExtensionToFactoryMap().put("xmi", new XMIResourceFactoryImpl());
        resources.getResourceFactoryRegistry().getExtensionToFactoryMap().put("afm", new XMIResourceFactoryImpl());
        resource = resources.createResource(URI.createURI("memory:/structural.xmi"));
        var project = identified(CapellamodellerFactory.eINSTANCE.createProject(), "project");
        var engineering = identified(CapellamodellerFactory.eINSTANCE.createSystemEngineering(), "engineering");
        project.getOwnedModelRoots().add(engineering); resource.getContents().add(project);
        var sa = identified(CtxFactory.eINSTANCE.createSystemAnalysis(), "sa");
        var saFunctions = identified(CtxFactory.eINSTANCE.createSystemFunctionPkg(), "sa-functions"); sa.setOwnedFunctionPkg(saFunctions);
        var saRoot = named(CtxFactory.eINSTANCE.createSystemFunction(), "sa-root", "Root system function");
        saFunctions.getOwnedSystemFunctions().add(saRoot);
        system = named(CtxFactory.eINSTANCE.createSystemFunction(), "system-demand", "Authorize torque");
        secondSystem = named(CtxFactory.eINSTANCE.createSystemFunction(), "system-command", "Command drive");
        saRoot.getOwnedFunctions().addAll(List.of(system, secondSystem));
        var la = identified(LaFactory.eINSTANCE.createLogicalArchitecture(), "la");
        var laFunctions = identified(LaFactory.eINSTANCE.createLogicalFunctionPkg(), "la-functions"); la.setOwnedFunctionPkg(laFunctions);
        var laRoot = named(LaFactory.eINSTANCE.createLogicalFunction(), "la-root", "Root logical function");
        laFunctions.getOwnedLogicalFunctions().add(laRoot);
        logical = named(LaFactory.eINSTANCE.createLogicalFunction(), "logical-demand", "Authorize torque");
        laRoot.getOwnedFunctions().add(logical);
        realization = identified(FaFactory.eINSTANCE.createFunctionRealization(), "realization");
        realization.setSourceElement(logical); realization.setTargetElement(system); logical.getOwnedFunctionRealizations().add(realization);
        chain = named(FaFactory.eINSTANCE.createFunctionalChain(), "chain", "Torque path");
        start = identified(FaFactory.eINSTANCE.createFunctionalChainInvolvementFunction(), "chain-start"); start.setInvolved(system);
        finish = identified(FaFactory.eINSTANCE.createFunctionalChainInvolvementFunction(), "chain-finish"); finish.setInvolved(secondSystem);
        link = identified(FaFactory.eINSTANCE.createFunctionalChainInvolvementLink(), "chain-link"); link.setSource(start); link.setTarget(finish);
        chain.getOwnedFunctionalChainInvolvements().addAll(List.of(start, link, finish)); saRoot.getOwnedFunctionalChains().add(chain);
        var oa = identified(OaFactory.eINSTANCE.createOperationalAnalysis(), "oa");
        var oaCapabilities = identified(OaFactory.eINSTANCE.createOperationalCapabilityPkg(), "oa-capabilities"); oa.setOwnedAbstractCapabilityPkg(oaCapabilities);
        var capability = named(OaFactory.eINSTANCE.createOperationalCapability(), "oa-capability", "Provide drive");
        oaCapabilities.getOwnedOperationalCapabilities().add(capability);
        nominal = named(InteractionFactory.eINSTANCE.createScenario(), "nominal", "Nominal"); nominal.setKind(ScenarioKind.INTERACTION);
        inhibited = named(InteractionFactory.eINSTANCE.createScenario(), "inhibited", "Inhibited"); inhibited.setKind(ScenarioKind.INTERACTION);
        nominalRole = named(InteractionFactory.eINSTANCE.createInstanceRole(), "nominal-role", "Vehicle");
        inhibitedRole = named(InteractionFactory.eINSTANCE.createInstanceRole(), "inhibited-role", "Vehicle");
        nominal.getOwnedInstanceRoles().add(nominalRole); inhibited.getOwnedInstanceRoles().add(inhibitedRole);
        capability.getOwnedScenarios().addAll(List.of(nominal, inhibited));
        var pa = identified(PaFactory.eINSTANCE.createPhysicalArchitecture(), "pa");
        pa.setOwnedPhysicalComponentPkg(identified(PaFactory.eINSTANCE.createPhysicalComponentPkg(), "pa-components"));
        pa.getOwnedPhysicalComponentPkg().getOwnedPhysicalComponents().add(named(PaFactory.eINSTANCE.createPhysicalComponent(), "physical", "Drive hardware"));
        var epbs = identified(EpbsFactory.eINSTANCE.createEPBSArchitecture(), "epbs");
        epbs.setOwnedConfigurationItemPkg(identified(EpbsFactory.eINSTANCE.createConfigurationItemPkg(), "epbs-items"));
        epbs.getOwnedConfigurationItemPkg().getOwnedConfigurationItems().add(named(EpbsFactory.eINSTANCE.createConfigurationItem(), "item", "Drive item"));
        engineering.getOwnedArchitectures().addAll(List.of(oa, sa, la, pa, epbs));
        domain = TransactionalEditingDomain.Factory.INSTANCE.createEditingDomain(resources); open = true;
        session = session(List.of(resource));
    }

    @After public void dispose() { if (domain != null) domain.dispose(); }

    @Test
    public void nativeRealizationDirectionAndContainmentAreIndependentlyCompared() {
        var results = check(
            reference("realization", "sourceElement", id("logical-demand")),
            reference("realization", "targetElement", id("system-demand")),
            reference("realization", "sourceElement", id("system-demand")),
            contained(id("realization"), id("logical-demand")),
            contained(id("realization"), id("system-demand")));
        assertEquals(List.of(Status.PASSED, Status.PASSED, Status.FAILED, Status.PASSED, Status.FAILED), statuses(results));
        assertEquals("logical-demand", ((Map<?, ?>) ((Map<?, ?>) results.get(0).actual()).get("value")).get("id"));
    }

    @Test
    public void chainEndpointInvolvementsAreCheckedByActualNativeIds() {
        var results = check(reference("chain-link", "source", id("chain-start")), reference("chain-link", "target", id("chain-finish")),
            reference("chain-start", "involved", id("system-demand")), reference("chain-link", "source", id("chain-finish")));
        assertEquals(List.of(Status.PASSED, Status.PASSED, Status.PASSED, Status.FAILED), statuses(results));
    }

    @Test
    public void storedNativeSequenceRequiresExactOrderAndCardinality() {
        assertTrue(chain.eClass().getEStructuralFeature("ownedFunctionalChainInvolvements").isOrdered());
        var results = check(sequence("chain", List.of(id("chain-start"), id("chain-link"), id("chain-finish"))),
            sequence("chain", List.of(id("chain-start"), id("chain-finish"), id("chain-link"))),
            sequence("chain", List.of(id("chain-start"), id("chain-link"))));
        assertEquals(List.of(Status.PASSED, Status.FAILED, Status.FAILED), statuses(results));
        assertEquals(3, ((Map<?, ?>) results.get(0).actual()).get("count"));
    }

    @Test
    public void nullableSingleReferenceIsComparedWithoutInventingATarget() {
        Map<String, Object> absent = reference("nominal-role", "representedInstance", null);
        assertEquals(Status.PASSED, check(absent).get(0).status());
        assertEquals(Status.FAILED, check(reference("realization", "targetElement", null)).get(0).status());
    }

    @Test
    public void exactOneRelationChecksSourceTargetAndOptionalImmediateOwner() {
        var predicates = Map.of("sourceElement", id("logical-demand"), "targetElement", id("system-demand"));
        var results = check(relation(predicates, id("logical-demand")), relation(predicates, id("system-demand")),
            relation(Map.of("sourceElement", id("system-demand"), "targetElement", id("logical-demand")), null));
        assertEquals(List.of(Status.PASSED, Status.FAILED, Status.FAILED), statuses(results));
        assertEquals(1, ((Map<?, ?>) results.get(0).actual()).get("matchCount"));
    }

    @Test
    public void duplicatedNativeRelationFailsExactlyOneRequirement() {
        write(() -> {
            var duplicate = identified(FaFactory.eINSTANCE.createFunctionRealization(), "duplicate-realization");
            duplicate.setSourceElement(logical); duplicate.setTargetElement(system); logical.getOwnedFunctionRealizations().add(duplicate);
        });
        var result = check(relation(Map.of("sourceElement", id("logical-demand"), "targetElement", id("system-demand")), id("logical-demand"))).get(0);
        assertEquals(Status.FAILED, result.status());
        assertEquals(2, ((Map<?, ?>) result.actual()).get("matchCount"));
    }

    @Test
    public void duplicateRoleNamesAreUnavailableUntilScopedByNativeScenarioContainer() {
        var ambiguous = contained(namedSelector("InstanceRole", "Vehicle", "OA"), id("nominal"));
        assertEquals(Status.UNAVAILABLE, check(ambiguous).get(0).status());
        var scoped = new LinkedHashMap<>(namedSelector("InstanceRole", "Vehicle", "OA"));
        scoped.put("container", namedSelector("Scenario", "Nominal", "OA"));
        assertEquals(Status.PASSED, check(contained(scoped, id("nominal"))).get(0).status());
        assertEquals(Status.FAILED, check(contained(scoped, id("inhibited"))).get(0).status());
    }

    @Test
    public void sameNamedFunctionsStayScopedToNativeClassifierAndLayer() {
        var results = check(contained(namedSelector("SystemFunction", "Authorize torque", "SA"), id("sa-root")),
            contained(namedSelector("LogicalFunction", "Authorize torque", "LA"), id("la-root")),
            contained(namedSelector("SystemFunction", "Authorize torque", "LA"), id("sa-root")),
            contained(namedSelector("PhysicalComponent", "Drive hardware", "PA"), id("pa-components")),
            contained(namedSelector("ConfigurationItem", "Drive item", "EPBS"), id("epbs-items")));
        assertEquals(List.of(Status.PASSED, Status.PASSED, Status.FAILED, Status.PASSED, Status.PASSED), statuses(results));
    }

    @Test
    public void duplicateIntrinsicIdsDoNotUseTheXmlResourceLookupCacheAsUniquenessProof() {
        write(() -> secondSystem.setId(system.getId()));
        assertEquals(Status.UNAVAILABLE, check(reference("realization", "targetElement", id("system-demand"))).get(0).status());
    }

    @Test
    public void resolvingProxyReferenceRemainsUnresolvedAndUnavailable() {
        var proxy = CtxFactory.eINSTANCE.createSystemFunction();
        // Installing a resolvable inverse link may resolve it during transaction commit.
        // First keep it unresolved, then make the detached proxy resolvable immediately
        // before this read, so an accidental resolving getter would change the reference.
        ((InternalEObject) proxy).eSetProxyURI(resource.getURI().appendFragment("not-yet-resolvable"));
        write(() -> realization.setTargetElement(proxy));
        ((InternalEObject) proxy).eSetProxyURI(resource.getURI().appendFragment("system-demand"));
        var feature = realization.eClass().getEStructuralFeature("targetElement");
        assertSame(proxy, realization.eGet(feature, false));
        var result = check(reference("realization", "targetElement", id("system-demand"))).get(0);
        assertEquals(Status.UNAVAILABLE, result.status());
        assertSame(proxy, realization.eGet(feature, false));
        assertTrue(proxy.eIsProxy());
    }

    @Test
    public void foreignReferenceTargetIsUnavailableEvenWhenInTheSameResourceSet() {
        Resource foreign = resources.createResource(URI.createURI("memory:/foreign.xmi"));
        var foreignTarget = named(CtxFactory.eINSTANCE.createSystemFunction(), "foreign-target", "Foreign");
        write(() -> { foreign.getContents().add(foreignTarget); realization.setTargetElement(foreignTarget); });
        assertEquals(Status.UNAVAILABLE, check(reference("realization", "targetElement", id("system-demand"))).get(0).status());
    }

    @Test
    public void incompleteScanAndLoadErrorCannotEstablishAbsenceOrUniqueness() {
        var request = relation(Map.of("sourceElement", id("logical-demand"), "targetElement", id("missing-id")), null);
        var result = NativeStructuralAssertions.check(session, List.of(request), 1).get(0);
        assertEquals(Status.UNAVAILABLE, result.status());
        assertTrue(result.note().contains("unverified"));
        resource.getErrors().add(new Resource.Diagnostic() {
            public String getMessage() { return "Unresolved native model reference"; }
            public String getLocation() { return "private-model"; }
            public int getLine() { return 1; }
            public int getColumn() { return 1; }
        });
        result = check(request).get(0);
        assertEquals(Status.UNAVAILABLE, result.status());
        assertFalse(result.feedback().toString().contains("private-model"));
    }

    @Test
    public void proxyContainmentAndForeignResourceSessionPreventCompleteEvidence() {
        var proxy = CtxFactory.eINSTANCE.createSystemFunction();
        ((InternalEObject) proxy).eSetProxyURI(URI.createURI("missing:/model#function"));
        write(() -> system.getOwnedFunctions().add(proxy));
        assertEquals(Status.UNAVAILABLE, check(contained(id("realization"), id("logical-demand"))).get(0).status());
        var otherResources = new ResourceSetImpl();
        otherResources.getResourceFactoryRegistry().getExtensionToFactoryMap().put("xmi", new XMIResourceFactoryImpl());
        var foreign = otherResources.createResource(URI.createURI("memory:/other.xmi"));
        assertEquals(Status.UNAVAILABLE, NativeStructuralAssertions.check(session(List.of(foreign)), List.of(contained(id("realization"), id("logical-demand")))).get(0).status());
    }

    @Test
    public void standardNativeViewpointMetadataPreservesCompleteEvidenceWithoutReadEffects() throws Exception {
        Resource metadataResource = resources.createResource(URI.createURI("memory:/structural.afm"));
        var metadata = MetadataFactory.eINSTANCE.createMetadata(); metadata.setId("viewpoint-metadata");
        var viewpoint = MetadataFactory.eINSTANCE.createViewpointReference(); viewpoint.setId("core-viewpoint");
        viewpoint.setVpId("org.polarsys.capella.core.viewpoint"); viewpoint.setVersion(org.osgi.framework.Version.parseVersion("7.0.0"));
        write(() -> { metadata.getViewpointReferences().add(viewpoint); metadataResource.getContents().add(metadata); });
        session = session(List.of(metadataResource, resource));
        var before = new ByteArrayOutputStream(); metadataResource.save(before, Map.of());
        byte[] semanticBefore = serialize(); metadataResource.setModified(false); resource.setModified(false);
        var undo = domain.getCommandStack().getUndoCommand();
        var metadataAdapters = List.copyOf(metadata.eAdapters()); var viewpointAdapters = List.copyOf(viewpoint.eAdapters());
        assertEquals(Status.PASSED, check(reference("realization", "targetElement", id("system-demand"))).get(0).status());
        var after = new ByteArrayOutputStream(); metadataResource.save(after, Map.of());
        assertArrayEquals(before.toByteArray(), after.toByteArray()); assertArrayEquals(semanticBefore, serialize());
        assertFalse(metadataResource.isModified()); assertFalse(resource.isModified()); assertEquals(0, saves.get());
        assertSame(undo, domain.getCommandStack().getUndoCommand());
        assertEquals(metadataAdapters, metadata.eAdapters()); assertEquals(viewpointAdapters, viewpoint.eAdapters());
        assertThrows(IllegalArgumentException.class, () -> NativeStructuralAssertions.declaration(
            contained(namedSelector("Metadata", "viewpoint-metadata", "SA"), id("project"))));
    }

    @Test
    public void viewpointMetadataDoesNotHideUnresolvedContainmentOrDuplicateNativeIdentity() {
        Resource metadataResource = resources.createResource(URI.createURI("memory:/structural.afm"));
        var metadata = MetadataFactory.eINSTANCE.createMetadata(); metadata.setId("system-demand");
        write(() -> metadataResource.getContents().add(metadata));
        session = session(List.of(resource, metadataResource));
        assertEquals(Status.UNAVAILABLE, check(reference("realization", "targetElement", id("system-demand"))).get(0).status());
        write(() -> metadata.setId("viewpoint-metadata"));
        var proxy = MetadataFactory.eINSTANCE.createViewpointReference();
        ((InternalEObject) proxy).eSetProxyURI(URI.createURI("missing:/viewpoint#metadata"));
        write(() -> metadata.getViewpointReferences().add(proxy));
        assertEquals(Status.UNAVAILABLE, check(reference("realization", "targetElement", id("system-demand"))).get(0).status());
    }

    @Test
    public void permittingStandardMetadataDoesNotPermitUnknownViewpointNamespaces() {
        var extension = EcoreFactory.eINSTANCE.createEPackage(); extension.setName("unknownViewpoint");
        extension.setNsURI("http://www.polarsys.org/kitalpha/unknown/viewpoint"); extension.setNsPrefix("unknown");
        var type = EcoreFactory.eINSTANCE.createEClass(); type.setName("UnknownExtension"); extension.getEClassifiers().add(type);
        var object = new DynamicEObjectImpl(type);
        Resource foreign = resources.createResource(URI.createURI("memory:/unknown.xmi"));
        write(() -> foreign.getContents().add(object));
        session = session(List.of(resource, foreign));
        assertEquals(Status.UNAVAILABLE, check(reference("realization", "targetElement", id("system-demand"))).get(0).status());
    }

    @Test
    public void checksHaveNoSemanticSaveUndoDirtyOrAdapterEffectsAndReturnDetachedEvidence() throws Exception {
        write(() -> system.setName("Authorize torque"));
        byte[] before = serialize(); resource.setModified(false);
        var undo = domain.getCommandStack().getUndoCommand();
        var resourceAdapters = List.copyOf(resource.eAdapters()); var setAdapters = List.copyOf(resources.eAdapters());
        Map<EObject, List<?>> adapters = new IdentityHashMap<>();
        var iterator = resource.getAllContents(); while (iterator.hasNext()) { EObject object = iterator.next(); adapters.put(object, List.copyOf(object.eAdapters())); }
        var results = check(reference("realization", "targetElement", id("system-demand")),
            sequence("chain", List.of(id("chain-start"), id("chain-link"), id("chain-finish"))),
            relation(Map.of("sourceElement", id("logical-demand"), "targetElement", id("system-demand")), null));
        assertTrue(results.stream().allMatch(result -> result.status() == Status.PASSED));
        assertArrayEquals(before, serialize()); assertEquals(0, saves.get()); assertFalse(resource.isModified());
        assertSame(undo, domain.getCommandStack().getUndoCommand());
        assertEquals(resourceAdapters, resource.eAdapters()); assertEquals(setAdapters, resources.eAdapters());
        adapters.forEach((object, previous) -> assertEquals(previous, object.eAdapters()));
        for (var result : results) assertDetached(result.feedback());
        assertThrows(UnsupportedOperationException.class, () -> ((Map<String, Object>) results.get(0).actual()).put("injected", "value"));
    }

    @Test
    public void rejectsUnknownKindsFieldsClassifiersAndUnsupportedNativeReferenceContracts() {
        assertThrows(IllegalArgumentException.class, () -> check(Map.of("kind", "evaluate", "script", "api.deleteEverything()")));
        var extra = new LinkedHashMap<>(reference("realization", "targetElement", id("system-demand"))); extra.put("expression", "true");
        assertThrows(IllegalArgumentException.class, () -> check(extra));
        assertThrows(IllegalArgumentException.class, () -> check(contained(Map.of("type", "EObject", "name", "Unknown"), id("project"))));
        assertThrows(IllegalArgumentException.class, () -> check(reference("system-demand", "subFunctions", null)));
        assertThrows(IllegalArgumentException.class, () -> check(reference("system-demand", "name", null)));
        assertThrows(IllegalArgumentException.class, () -> check(reference("chain", "ownedFunctionalChainInvolvements", null)));
        assertThrows(IllegalArgumentException.class, () -> check(Map.of("kind", "reference_sequence_equals", "target", id("realization"), "feature", "targetElement", "expected", List.of(id("system-demand")))));
        assertThrows(IllegalArgumentException.class, () -> check(Map.of("kind", "relation_exists", "type", "FunctionRealization", "predicates", Map.of())));
    }

    @Test
    public void derivedMessageOperationIsRejectedBeforeReviewWithoutReadingOrEditingAModel() throws Exception {
        var messageType = InteractionFactory.eINSTANCE.createSequenceMessage().eClass();
        var operation = messageType.getEStructuralFeature("invokedOperation");
        assertTrue(operation.isDerived()); assertTrue(operation.isTransient()); assertTrue(operation.isVolatile());
        byte[] before = serialize(); resource.setModified(false);
        Object undo = domain.getCommandStack().getUndoCommand(), redo = domain.getCommandStack().getRedoCommand();
        var adapters = List.copyOf(resources.eAdapters());
        var rejected = assertThrows(IllegalArgumentException.class, () -> NativeStructuralAssertions.declaration(Map.of(
            "kind", "reference_equals", "target", Map.of("type", "SequenceMessage", "name", "Future message", "layer", "OA"),
            "feature", "invokedOperation", "expected", id("future-exchange"))));
        assertTrue(rejected.getMessage().contains("SequenceMessage.invokedOperation"));
        assertArrayEquals(before, serialize()); assertFalse(resource.isModified()); assertEquals(0, saves.get());
        assertSame(undo, domain.getCommandStack().getUndoCommand()); assertSame(redo, domain.getCommandStack().getRedoCommand());
        assertEquals(adapters, resources.eAdapters());
        assertTrue(operation.isDerived()); assertTrue(operation.isTransient()); assertTrue(operation.isVolatile());
    }

    @Test
    public void storedEventOperationAndRelationContractsAreCheckedBeforeReview() {
        InteractionFactory.eINSTANCE.createEventSentOperation();
        var stored = Map.<String, Object>of("kind", "reference_equals",
            "target", Map.of("type", "EventSentOperation", "name", "Future event"),
            "feature", "operation", "expected", id("future-exchange"));
        assertEquals(stored, NativeStructuralAssertions.declaration(stored));
        assertThrows(IllegalArgumentException.class, () -> NativeStructuralAssertions.declaration(Map.of(
            "kind", "relation_exists", "type", "FunctionRealization", "predicates", Map.of("unknownReference", id("future-function")))));
        assertThrows(IllegalArgumentException.class, () -> NativeStructuralAssertions.declaration(Map.of(
            "kind", "reference_equals", "target", Map.of("type", "Scenario", "name", "Future scenario"),
            "feature", "ownedMessages", "expected", id("future-message"))));
    }

    @Test
    public void anonymousNativeChainInvolvementsCannotBeNamedSelectorsBeforeReview() {
        var involvement = FaFactory.eINSTANCE.createFunctionalChainInvolvementFunction();
        assertNull(involvement.eClass().getEStructuralFeature("name"));
        var rejected = assertThrows(IllegalArgumentException.class, () -> NativeStructuralAssertions.declaration(Map.of(
            "kind", "contained_by", "target", Map.of("type", "FunctionalChainInvolvementFunction", "name", "Invented label"),
            "container", id("future-chain"))));
        assertTrue(rejected.getMessage().contains("has no stored name"));
        var supported = Map.<String, Object>of("kind", "relation_exists", "type", "FunctionalChainInvolvementFunction",
            "predicates", Map.of("involved", Map.of("type", "SystemFunction", "name", "Future function", "layer", "SA")),
            "owner", Map.of("type", "FunctionalChain", "name", "Future chain", "layer", "SA"));
        assertEquals(supported, NativeStructuralAssertions.declaration(supported));
    }

    @Test
    public void commonAbstractClassifierDoesNotHideTheConcreteNamedConstraintOrExpression() {
        org.polarsys.capella.core.data.capellacore.CapellacoreFactory.eINSTANCE.createConstraint();
        org.polarsys.capella.core.data.information.datavalue.DatavalueFactory.eINSTANCE.createOpaqueExpression();
        var check = Map.<String, Object>of("kind", "reference_equals",
            "target", Map.of("type", "Constraint", "name", "Future constraint", "layer", "SA"),
            "feature", "ownedSpecification", "expected", Map.of("type", "OpaqueExpression", "name", "Future expression", "layer", "SA"));
        assertEquals(check, NativeStructuralAssertions.declaration(check));
    }


    @Test
    public void duplicateSelectorsOversizedDataAndExcessContainerDepthAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> check(sequence("chain", List.of(id("chain-start"), id("chain-start")))));
        assertThrows(IllegalArgumentException.class, () -> check(sequence("chain", List.of(id("chain-start"), namedSelector("FunctionalChainInvolvementFunction", "", "SA")))));
        var scoped = new LinkedHashMap<>(namedSelector("InstanceRole", "Vehicle", "OA"));
        var parent = new LinkedHashMap<>(namedSelector("Scenario", "Nominal", "OA")); parent.put("container", id("oa-capability")); scoped.put("container", parent);
        assertThrows(IllegalArgumentException.class, () -> check(contained(scoped, id("nominal"))));
        assertThrows(IllegalArgumentException.class, () -> check(contained(Map.of("id", "nominal-role", "type", "InstanceRole"), id("nominal"))));
        assertThrows(IllegalArgumentException.class, () -> NativeStructuralAssertions.check(session, java.util.Collections.nCopies(13, contained(id("realization"), id("logical-demand")))));
        assertThrows(IllegalArgumentException.class, () -> check(sequence("chain", java.util.Collections.nCopies(33, id("chain-start")))));
        List<Map<String, Object>> longSelectors = new ArrayList<>();
        for (int index = 0; index < 32; index++) longSelectors.add(id("long-" + index + "a".repeat(2390)));
        assertThrows(IllegalArgumentException.class, () -> check(sequence("chain", longSelectors)));
    }

    @Test
    public void emptyOrderedNativeReferenceCanMatchAnEmptyExpectedSequence() {
        var result = check(Map.of("kind", "reference_sequence_equals", "target", id("nominal"), "feature", "ownedMessages", "expected", List.of())).get(0);
        assertEquals(Status.PASSED, result.status());
    }

    @Test
    public void unorderedStoredReferenceIsRejectedUsingAnExplicitDynamicEmfMetadataFixture() {
        // Installed native Capella has no stored unordered many reference. This separate real-EMF
        // fixture proves the metadata guard without changing any generated Capella EReference.
        var pkg = EcoreFactory.eINSTANCE.createEPackage(); pkg.setName("structuralFixture"); pkg.setNsPrefix("structuralFixture");
        pkg.setNsURI("http://www.polarsys.org/capella/core/structural-assertion-test/7.0.0");
        var type = EcoreFactory.eINSTANCE.createEClass(); type.setName("UnorderedStructuralFixture"); pkg.getEClassifiers().add(type);
        var id = EcoreFactory.eINSTANCE.createEAttribute(); id.setName("id"); id.setID(true); id.setEType(EcorePackage.Literals.ESTRING); type.getEStructuralFeatures().add(id);
        var references = EcoreFactory.eINSTANCE.createEReference(); references.setName("unorderedTargets"); references.setEType(type);
        references.setUpperBound(-1); references.setOrdered(false); type.getEStructuralFeatures().add(references);
        Object previous = EPackage.Registry.INSTANCE.put(pkg.getNsURI(), pkg);
        try {
            EObject fixture = new DynamicEObjectImpl(type); fixture.eSet(id, "unordered-fixture");
            write(() -> resource.getContents().add(fixture));
            assertThrows(IllegalArgumentException.class, () -> check(Map.of("kind", "reference_sequence_equals", "target", id("unordered-fixture"), "feature", "unorderedTargets", "expected", List.of())));
        } finally {
            if (previous == null) EPackage.Registry.INSTANCE.remove(pkg.getNsURI()); else EPackage.Registry.INSTANCE.put(pkg.getNsURI(), previous);
        }
    }

    @Test
    public void closedAndMissingSessionsReturnUnavailableRatherThanSuccess() {
        open = false;
        assertEquals(Status.UNAVAILABLE, check(contained(id("realization"), id("logical-demand"))).get(0).status());
        assertEquals(Status.UNAVAILABLE, NativeStructuralAssertions.check(null, List.of(contained(id("realization"), id("logical-demand")))).get(0).status());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void preReviewDeclarationRejectsUnknownDataAndDeeplyFreezesNestedSelectors() {
        var selector = new LinkedHashMap<>(id("chain-start"));
        var expected = new ArrayList<Map<String, Object>>(); expected.add(selector);
        var input = sequence("chain", expected);
        var frozen = NativeStructuralAssertions.declaration(input);
        selector.put("id", "changed-after-review"); expected.clear();
        var copied = (List<Map<String, Object>>) frozen.get("expected");
        assertEquals(List.of(id("chain-start")), copied);
        assertThrows(UnsupportedOperationException.class, () -> frozen.put("kind", "evaluate"));
        assertThrows(UnsupportedOperationException.class, () -> copied.add(id("chain-link")));
        assertThrows(UnsupportedOperationException.class, () -> copied.get(0).put("id", "changed"));
        assertThrows(IllegalArgumentException.class, () -> NativeStructuralAssertions.declaration(Map.of("kind", "evaluate", "script", "anything")));
        assertThrows(IllegalArgumentException.class, () -> NativeStructuralAssertions.declaration(contained(Map.of("id", "nominal-role", "getter", "arbitrary"), id("nominal"))));
        assertEquals(0, saves.get());
    }

    @Test
    public void diagramShowsRequiresOneNamedDiagramWithAViewOfEveryElement() {
        Resource aird = new org.eclipse.emf.ecore.resource.impl.ResourceImpl(URI.createURI("memory:/structural.aird"));
        write(() -> {
            resources.getResources().add(aird);
            var diagram = org.eclipse.sirius.diagram.DiagramFactory.eINSTANCE.createDSemanticDiagram();
            var container = org.eclipse.sirius.diagram.DiagramFactory.eINSTANCE.createDNodeContainer(); container.setTarget(chain); container.getSemanticElements().add(secondSystem);
            var node = org.eclipse.sirius.diagram.DiagramFactory.eINSTANCE.createDNode(); node.setTarget(system);
            container.getOwnedDiagramElements().add(node); diagram.getOwnedDiagramElements().add(container); aird.getContents().add(diagram);
            var descriptor = org.eclipse.sirius.viewpoint.ViewpointFactory.eINSTANCE.createDRepresentationDescriptor();
            descriptor.setName("Torque view"); aird.getContents().add(descriptor); descriptor.setRepresentation(diagram);
        });
        session = session(List.of(resource), aird);
        var results = check(shows("Torque view", id("system-demand"), id("system-command")),
            shows("Torque view", id("system-demand"), id("logical-demand")), shows("Missing view", id("system-demand")));
        assertEquals(List.of(Status.PASSED, Status.FAILED, Status.FAILED), statuses(results));
        assertEquals(List.of("logical-demand"), ((Map<?, ?>) results.get(1).actual()).get("missing"));
        assertEquals(0, ((Map<?, ?>) results.get(2).actual()).get("diagrams"));
        assertThrows(IllegalArgumentException.class, () -> check(Map.of("kind", "diagram_shows", "diagram", "Torque view", "elements", List.of())));
        assertDetached(results.get(0).actual());
    }

    @SafeVarargs private static Map<String, Object> shows(String diagram, Map<String, Object>... elements) { return Map.of("kind", "diagram_shows", "diagram", diagram, "elements", List.of(elements)); }
    @SafeVarargs private final List<NativeStructuralAssertions.Result> check(Map<String, Object>... checks) { return NativeStructuralAssertions.check(session, List.of(checks)); }
    private static List<Status> statuses(List<NativeStructuralAssertions.Result> results) { return results.stream().map(NativeStructuralAssertions.Result::status).toList(); }
    private static Map<String, Object> id(String id) { return Map.of("id", id); }
    private static Map<String, Object> namedSelector(String type, String name, String layer) { return Map.of("type", type, "name", name, "layer", layer); }
    private static Map<String, Object> reference(String target, String feature, Object expected) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("kind", "reference_equals"); result.put("target", id(target)); result.put("feature", feature); result.put("expected", expected); return result;
    }
    private static Map<String, Object> sequence(String target, List<Map<String, Object>> expected) { return Map.of("kind", "reference_sequence_equals", "target", id(target), "feature", "ownedFunctionalChainInvolvements", "expected", expected); }
    private static Map<String, Object> contained(Map<String, Object> target, Map<String, Object> container) { return Map.of("kind", "contained_by", "target", target, "container", container); }
    private static Map<String, Object> relation(Map<String, Map<String, Object>> predicates, Map<String, Object> owner) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("kind", "relation_exists"); result.put("type", "FunctionRealization"); result.put("predicates", predicates); if (owner != null) result.put("owner", owner); return result;
    }
    private Session session(List<Resource> semantic) { return session(semantic, null); }
    private Session session(List<Resource> semantic, Resource aird) {
        return (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[] { Session.class },
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getTransactionalEditingDomain" -> domain; case "getSemanticResources" -> semantic; case "isOpen" -> open;
                case "getSessionResource" -> aird; case "getAllSessionResources" -> aird == null ? Set.of() : Set.of(aird);
                case "save" -> { saves.incrementAndGet(); yield null; }
                case "hashCode" -> System.identityHashCode(proxy); case "equals" -> proxy == arguments[0]; default -> null;
            });
    }
    private void write(Runnable action) { domain.getCommandStack().execute(new RecordingCommand(domain) { @Override protected void doExecute() { action.run(); } }); }
    private byte[] serialize() throws Exception { var output = new ByteArrayOutputStream(); resource.save(output, Map.of()); return output.toByteArray(); }
    private static <T extends CapellaElement> T identified(T value, String id) { value.setId(id); return value; }
    private static <T extends NamedElement> T named(T value, String id, String name) { identified(value, id); value.setName(name); return value; }
    private static void assertDetached(Object value) {
        assertFalse(value instanceof EObject); assertFalse(value instanceof Resource); assertFalse(value instanceof Session);
        if (value instanceof Map<?, ?> map) map.forEach((key, item) -> assertDetached(item));
        else if (value instanceof List<?> list) list.forEach(NativeStructuralAssertionsTest::assertDetached);
        else assertTrue(value == null || value instanceof String || value instanceof Number || value instanceof Boolean);
    }
}
