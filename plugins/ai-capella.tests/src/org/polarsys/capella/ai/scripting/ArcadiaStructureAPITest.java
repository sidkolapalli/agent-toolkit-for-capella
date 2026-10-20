package org.polarsys.capella.ai.scripting;

import static org.junit.Assert.*;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.sirius.business.api.session.Session;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.polarsys.capella.ai.model.ModelSummary;
import org.polarsys.capella.ai.session.SessionSafeguards;
import org.polarsys.capella.core.data.capellacore.CapellaElement;
import org.polarsys.capella.core.data.capellamodeller.CapellamodellerFactory;
import org.polarsys.capella.core.data.cs.BlockArchitecture;
import org.polarsys.capella.core.data.cs.Component;
import org.polarsys.capella.core.data.cs.ComponentPkg;
import org.polarsys.capella.core.data.cs.CsFactory;
import org.polarsys.capella.core.data.cs.Part;
import org.polarsys.capella.core.data.cs.PhysicalLink;
import org.polarsys.capella.core.data.cs.PhysicalLinkEnd;
import org.polarsys.capella.core.data.cs.PhysicalPort;
import org.polarsys.capella.core.data.ctx.CtxFactory;
import org.polarsys.capella.core.data.fa.AbstractFunction;
import org.polarsys.capella.core.data.fa.ComponentExchange;
import org.polarsys.capella.core.data.fa.ComponentExchangeFunctionalExchangeAllocation;
import org.polarsys.capella.core.data.fa.ComponentExchangeKind;
import org.polarsys.capella.core.data.fa.ComponentPort;
import org.polarsys.capella.core.data.fa.ComponentPortKind;
import org.polarsys.capella.core.data.fa.FaFactory;
import org.polarsys.capella.core.data.fa.FunctionInputPort;
import org.polarsys.capella.core.data.fa.FunctionOutputPort;
import org.polarsys.capella.core.data.fa.FunctionalExchange;
import org.polarsys.capella.core.data.fa.OrientationPortKind;
import org.polarsys.capella.core.data.la.LaFactory;
import org.polarsys.capella.core.data.la.LogicalComponent;
import org.polarsys.capella.core.data.pa.PaFactory;
import org.polarsys.capella.core.data.pa.PhysicalArchitecture;
import org.polarsys.capella.core.data.pa.PhysicalComponent;
import org.polarsys.capella.core.data.pa.PhysicalComponentNature;
import org.polarsys.capella.core.data.pa.deployment.PartDeploymentLink;
import org.polarsys.capella.core.model.handler.helpers.CapellaProjectHelper;
import org.polarsys.capella.core.model.handler.helpers.CapellaProjectHelper.ProjectApproach;
import org.polarsys.capella.core.model.helpers.PhysicalLinkExt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Real Capella metamodel, native transactions and XMI reload; only Session is a proxy. */
public class ArcadiaStructureAPITest {
    private Resource resource;
    private TransactionalEditingDomain domain;
    private Session session;
    private ArcadiaStructureAPI structure;
    private boolean open;
    private int saves;
    private byte[] saved;
    private final BlockArchitecture[] architectures = new BlockArchitecture[3];
    private final AbstractFunction[] functions = new AbstractFunction[3];
    private final Component[] roots = new Component[3];
    private PhysicalComponent behavior, otherBehavior, node, otherNode, actor;
    private Part behaviorPart, otherBehaviorPart, nodePart, otherNodePart, actorPart;

    @Before
    public void setUp() {
        var resources = resources();
        resource = resources.createResource(URI.createURI("memory:/structure.xmi"));
        var project = CapellamodellerFactory.eINSTANCE.createProject();
        CapellaProjectHelper.setProjectWithApproach(project, ProjectApproach.SingletonComponents);
        var engineering = CapellamodellerFactory.eINSTANCE.createSystemEngineering();
        project.getOwnedModelRoots().add(engineering);
        resource.getContents().add(project);
        for (int layer = 0; layer < 3; layer++) {
            ComponentPkg componentPackage;
            EObject functionPackage;
            String functionFeature, componentFeature;
            if (layer == 0) {
                architectures[layer] = CtxFactory.eINSTANCE.createSystemAnalysis();
                componentPackage = CtxFactory.eINSTANCE.createSystemComponentPkg();
                functionPackage = CtxFactory.eINSTANCE.createSystemFunctionPkg();
                functions[layer] = CtxFactory.eINSTANCE.createSystemFunction();
                roots[layer] = CtxFactory.eINSTANCE.createSystemComponent();
                functionFeature = "ownedSystemFunctions";
                componentFeature = "ownedSystemComponents";
                set(architectures[layer], "ownedSystemComponentPkg", componentPackage);
            } else if (layer == 1) {
                architectures[layer] = LaFactory.eINSTANCE.createLogicalArchitecture();
                componentPackage = LaFactory.eINSTANCE.createLogicalComponentPkg();
                functionPackage = LaFactory.eINSTANCE.createLogicalFunctionPkg();
                functions[layer] = LaFactory.eINSTANCE.createLogicalFunction();
                roots[layer] = LaFactory.eINSTANCE.createLogicalComponent();
                functionFeature = "ownedLogicalFunctions";
                componentFeature = "ownedLogicalComponents";
                set(architectures[layer], "ownedLogicalComponentPkg", componentPackage);
            } else {
                architectures[layer] = PaFactory.eINSTANCE.createPhysicalArchitecture();
                componentPackage = PaFactory.eINSTANCE.createPhysicalComponentPkg();
                functionPackage = PaFactory.eINSTANCE.createPhysicalFunctionPkg();
                functions[layer] = PaFactory.eINSTANCE.createPhysicalFunction();
                roots[layer] = PaFactory.eINSTANCE.createPhysicalComponent();
                functionFeature = "ownedPhysicalFunctions";
                componentFeature = "ownedPhysicalComponents";
                set(architectures[layer], "ownedPhysicalComponentPkg", componentPackage);
            }
            set(architectures[layer], "ownedFunctionPkg", functionPackage);
            add(functionPackage, functionFeature, functions[layer]);
            add(componentPackage, componentFeature, roots[layer]);
            componentPackage.getOwnedParts().add(part(roots[layer]));
            engineering.getOwnedArchitectures().add(architectures[layer]);
        }
        behavior = physical("Behavior", PhysicalComponentNature.BEHAVIOR, false);
        otherBehavior = physical("Other behavior", PhysicalComponentNature.BEHAVIOR, false);
        node = physical("Equipment", PhysicalComponentNature.NODE, false);
        otherNode = physical("Other equipment", PhysicalComponentNature.NODE, false);
        actor = physical("External equipment actor", PhysicalComponentNature.NODE, true);
        behaviorPart = ownedPart(roots[2], behavior);
        otherBehaviorPart = ownedPart(roots[2], otherBehavior);
        nodePart = ownedPart(roots[2], node);
        otherNodePart = ownedPart(roots[2], otherNode);
        actorPart = ownedPart(roots[2], actor);
        domain = TransactionalEditingDomain.Factory.INSTANCE.createEditingDomain(resources);
        open = true;
        session =
                (Session)
                        Proxy.newProxyInstance(
                                Session.class.getClassLoader(),
                                new Class<?>[] {Session.class},
                                (proxy, method, arguments) ->
                                        switch (method.getName()) {
                                            case "isOpen" -> open;
                                            case "getTransactionalEditingDomain" -> domain;
                                            case "getSemanticResources" -> List.of(resource);
                                            case "getSessionResource" -> resource;
                                            case "save" -> {
                                                saves++;
                                                var output = new ByteArrayOutputStream();
                                                resource.save(output, Map.of());
                                                saved = output.toByteArray();
                                                yield null;
                                            }
                                            case "equals" -> proxy == arguments[0];
                                            case "hashCode" -> System.identityHashCode(proxy);
                                            default -> null;
                                        });
        structure = new ArcadiaStructureAPI(session);
    }

    @After
    public void tearDown() {
        if (session != null) ModelSummary.invalidate(session);
        if (domain != null) domain.dispose();
    }

    @Test
    public void deploymentHasNativeLocationOwnershipAndSurvivesUndoRedoReload() throws Exception {
        PartDeploymentLink deployment = structure.deploy(behaviorPart, nodePart);
        assertSame(nodePart, deployment.eContainer());
        assertSame(nodePart, deployment.getLocation());
        assertSame(behaviorPart, deployment.getDeployedElement());
        assertEquals(1, saves);
        assertEquals(List.of(deployment), nodePart.getOwnedDeploymentLinks());
        domain.getCommandStack().undo();
        assertTrue(nodePart.getOwnedDeploymentLinks().isEmpty());
        assertNotSame(resource, deployment.eResource());
        domain.getCommandStack().redo();
        assertSame(nodePart, deployment.eContainer());
        assertSame(behaviorPart, deployment.getDeployedElement());
        Resource copy = reload();
        PartDeploymentLink loaded = (PartDeploymentLink) copy.getEObject(deployment.getId());
        assertNotNull(loaded);
        assertSame(copy.getEObject(nodePart.getId()), loaded.getLocation());
        assertSame(copy.getEObject(behaviorPart.getId()), loaded.getDeployedElement());
        assertSame(loaded.getLocation(), loaded.eContainer());
    }

    @Test
    public void identicalDeploymentIsIdempotentAndAnotherHostIsRejectedWithoutMutation() {
        PartDeploymentLink deployment = structure.deploy(behaviorPart, nodePart);
        int count = count(), priorSaves = saves;
        assertSame(deployment, structure.deploy(behaviorPart, nodePart));
        assertEquals(count, count());
        assertEquals(priorSaves, saves);
        reject(() -> structure.deploy(behaviorPart, otherNodePart));
        assertTrue(otherNodePart.getOwnedDeploymentLinks().isEmpty());
    }

    @Test
    public void deploymentRejectsWrongNatureDetachedForeignClosedAndCrossLayerBeforeChanges() {
        reject(() -> structure.deploy(nodePart, behaviorPart));
        reject(() -> structure.deploy(behaviorPart, otherBehaviorPart));
        reject(() -> structure.deploy(CsFactory.eINSTANCE.createPart(), nodePart));
        Part logicalPart = prepare(() -> ownedPart(roots[1], logical("Wrong layer", false)));
        reject(() -> structure.deploy(logicalPart, nodePart));
        Resource foreign = resources().createResource(URI.createURI("memory:/foreign.xmi"));
        Part foreignPart = part(PaFactory.eINSTANCE.createPhysicalComponent());
        foreign.getContents().add(foreignPart);
        reject(() -> structure.deploy(foreignPart, nodePart));
        open = false;
        assertThrows(IllegalStateException.class, () -> structure.deploy(behaviorPart, nodePart));
        assertEquals(0, saves);
        assertThrows(
                IllegalStateException.class,
                () -> new ArcadiaStructureAPI(null).deploy(behaviorPart, nodePart));
    }

    @Test
    public void explicitPhysicalLinkHasTwoOwnedNativeEndsWithExactPartPortReferencesAndReload()
            throws Exception {
        PhysicalPort source = prepare(() -> port(node)), target = prepare(() -> port(otherNode));
        PhysicalLink link =
                structure.physicalLink(
                        "Control cable candidate", nodePart, source, otherNodePart, target);
        assertSame(PhysicalLinkExt.getDefaultContainer(nodePart, otherNodePart), link.eContainer());
        assertEquals(2, link.getOwnedPhysicalLinkEnds().size());
        assertEquals(link.getOwnedPhysicalLinkEnds(), link.getLinkEnds());
        PhysicalLinkEnd first = link.getOwnedPhysicalLinkEnds().get(0),
                second = link.getOwnedPhysicalLinkEnds().get(1);
        assertSame(nodePart, first.getPart());
        assertSame(source, first.getPort());
        assertSame(otherNodePart, second.getPart());
        assertSame(target, second.getPort());
        Resource copy = reload();
        PhysicalLink loaded = (PhysicalLink) copy.getEObject(link.getId());
        assertNotNull(loaded);
        assertEquals(2, loaded.getLinkEnds().size());
        for (int i = 0; i < 2; i++) {
            var expected = link.getOwnedPhysicalLinkEnds().get(i);
            var actual = loaded.getOwnedPhysicalLinkEnds().get(i);
            assertSame(loaded, actual.eContainer());
            assertSame(copy.getEObject(expected.getPart().getId()), actual.getPart());
            assertSame(copy.getEObject(expected.getPort().getId()), actual.getPort());
            assertSame(actual, loaded.getLinkEnds().get(i));
        }
    }

    @Test
    public void conveniencePhysicalLinkCreatesAndUndoesItsDedicatedPortsIncludingActorEndpoint() {
        int before = count();
        PhysicalLink link = structure.physicalLink("External harness", nodePart, actorPart);
        assertEquals(before + 5, count());
        assertEquals(1, saves);
        assertSame(actor, link.getOwnedPhysicalLinkEnds().get(1).getPort().eContainer());
        domain.getCommandStack().undo();
        assertEquals(before, count());
        assertTrue(node.getContainedPhysicalPorts().isEmpty());
        assertTrue(actor.getContainedPhysicalPorts().isEmpty());
        domain.getCommandStack().redo();
        assertEquals(before + 5, count());
        assertSame(resource, link.eResource());
        assertSame(nodePart, link.getOwnedPhysicalLinkEnds().get(0).getPart());
    }

    @Test
    public void physicalLinkRejectsIncompatiblePortsBehaviorRootProxiesAndNonSemanticResource() {
        PhysicalPort source = prepare(() -> port(node)), target = prepare(() -> port(otherNode));
        reject(() -> structure.physicalLink("Wrong type", nodePart, target, otherNodePart, source));
        reject(() -> structure.physicalLink("No node", behaviorPart, otherNodePart));
        reject(() -> structure.physicalLink("Self", nodePart, nodePart));
        reject(() -> structure.physicalLink(" ", nodePart, otherNodePart));
        Part rootPart =
                ((PhysicalArchitecture) architectures[2])
                        .getOwnedPhysicalComponentPkg()
                        .getOwnedParts()
                        .get(0);
        reject(() -> structure.physicalLink("Root", rootPart, nodePart));
        PhysicalPort proxy = CsFactory.eINSTANCE.createPhysicalPort();
        ((InternalEObject) proxy).eSetProxyURI(URI.createURI("memory:/missing.xmi#port"));
        reject(() -> structure.physicalLink("Proxy", nodePart, proxy, otherNodePart, target));
        Part extra =
                prepare(
                        () -> {
                            var nonSemantic =
                                    domain.getResourceSet()
                                            .createResource(
                                                    URI.createURI("memory:/not-session.xmi"));
                            var value = part(node);
                            nonSemantic.getContents().add(value);
                            return value;
                        });
        reject(() -> structure.physicalLink("Not session", extra, otherNodePart));
    }

    @Test
    public void allocationCreatesStoredExchangeAndPortTracesAcrossSA_LA_PAAndReload()
            throws Exception {
        for (int layer = 0; layer < 3; layer++) {
            final int perspective = layer;
            Flow fixture = prepare(() -> flow(perspective, true));
            var allocation = structure.allocateExchange(fixture.transport, fixture.flow);
            assertSame(fixture.transport, allocation.eContainer());
            assertSame(fixture.transport, allocation.getSourceElement());
            assertSame(fixture.flow, allocation.getTargetElement());
            assertPortAllocation(fixture.source, fixture.output);
            assertPortAllocation(fixture.target, fixture.input);
            int before = count(), savedBefore = saves;
            assertSame(allocation, structure.allocateExchange(fixture.transport, fixture.flow));
            assertEquals(before, count());
            assertEquals(savedBefore, saves);
            Resource copy = reload();
            var loaded =
                    (ComponentExchangeFunctionalExchangeAllocation)
                            copy.getEObject(allocation.getId());
            assertNotNull(loaded);
            assertSame(copy.getEObject(fixture.transport.getId()), loaded.getSourceElement());
            assertSame(copy.getEObject(fixture.flow.getId()), loaded.getTargetElement());
            ComponentPort loadedSource = (ComponentPort) copy.getEObject(fixture.source.getId());
            assertPortAllocation(loadedSource, copy.getEObject(fixture.output.getId()));
        }
    }

    @Test
    public void allocationAndBothPortsAreOneUndoableCommand() {
        Flow fixture = prepare(() -> flow(1, false));
        int before = count();
        var allocation = structure.allocateExchange(fixture.transport, fixture.flow);
        assertEquals(before + 3, count());
        domain.getCommandStack().undo();
        assertEquals(before, count());
        assertTrue(
                fixture.transport
                        .getOwnedComponentExchangeFunctionalExchangeAllocations()
                        .isEmpty());
        assertTrue(fixture.source.getOwnedPortAllocations().isEmpty());
        assertTrue(fixture.target.getOwnedPortAllocations().isEmpty());
        domain.getCommandStack().redo();
        assertEquals(before + 3, count());
        assertSame(fixture.transport, allocation.eContainer());
        assertPortAllocation(fixture.source, fixture.output);
    }

    @Test
    public void reverseDirectionRequiresTwoInoutPortsAndAllocatesToActualFunctionOwner() {
        Flow fixture =
                prepare(
                        () -> {
                            Flow value = flow(1, false);
                            value.transport.setSource(value.target);
                            value.transport.setTarget(value.source);
                            value.source.setOrientation(OrientationPortKind.INOUT);
                            value.target.setOrientation(OrientationPortKind.INOUT);
                            return value;
                        });
        structure.allocateExchange(fixture.transport, fixture.flow);
        assertPortAllocation(fixture.source, fixture.output);
        assertPortAllocation(fixture.target, fixture.input);
    }

    @Test
    public void instanceExchangeEndsRetainExactPartsAndTypes() {
        Flow fixture =
                prepare(
                        () -> {
                            Flow value = flow(1, false);
                            var sourceEnd = FaFactory.eINSTANCE.createComponentExchangeEnd();
                            var targetEnd = FaFactory.eINSTANCE.createComponentExchangeEnd();
                            sourceEnd.setPort(value.source);
                            targetEnd.setPort(value.target);
                            sourceEnd.setPart(
                                    ownedPart(roots[1], (Component) value.source.eContainer()));
                            targetEnd.setPart(
                                    ownedPart(roots[1], (Component) value.target.eContainer()));
                            value.transport
                                    .getOwnedComponentExchangeEnds()
                                    .addAll(List.of(sourceEnd, targetEnd));
                            value.transport.setSource(sourceEnd);
                            value.transport.setTarget(targetEnd);
                            return value;
                        });
        structure.allocateExchange(fixture.transport, fixture.flow);
        assertPortAllocation(fixture.source, fixture.output);
        assertPortAllocation(fixture.target, fixture.input);
        prepare(
                () -> {
                    ((org.polarsys.capella.core.data.fa.ComponentExchangeEnd)
                                    fixture.transport.getSource())
                            .setPart(nodePart);
                    return null;
                });
        reject(() -> structure.allocateExchange(fixture.transport, fixture.flow));
    }

    @Test
    public void nestedUsedResponsibilityIsAcceptedUsingNativeAllocationConsistency() {
        Flow fixture =
                prepare(
                        () -> {
                            Flow value = flow(1, false);
                            LogicalComponent parent = (LogicalComponent) value.source.eContainer();
                            LogicalComponent child = LaFactory.eINSTANCE.createLogicalComponent();
                            child.setName("Nested used responsibility");
                            parent.getOwnedLogicalComponents().add(child);
                            ownedPart(parent, child);
                            var allocation = parent.getOwnedFunctionalAllocation().get(0);
                            parent.getOwnedFunctionalAllocation().remove(allocation);
                            allocation.setSourceElement(child);
                            child.getOwnedFunctionalAllocation().add(allocation);
                            return value;
                        });
        structure.allocateExchange(fixture.transport, fixture.flow);
        assertPortAllocation(fixture.source, fixture.output);
    }

    @Test
    public void existingExchangeAllocationRepairsMissingPortsButRejectsDuplicateTraces() {
        Flow fixture =
                prepare(
                        () -> {
                            Flow value = flow(1, false);
                            exchangeAllocation(value);
                            return value;
                        });
        int before = count();
        var allocation =
                fixture.transport.getOwnedComponentExchangeFunctionalExchangeAllocations().get(0);
        assertSame(allocation, structure.allocateExchange(fixture.transport, fixture.flow));
        assertEquals(before + 2, count());
        prepare(
                () -> {
                    exchangeAllocation(fixture);
                    return null;
                });
        reject(() -> structure.allocateExchange(fixture.transport, fixture.flow));
    }

    @Test
    public void missingAllocationAggregateFunctionsWrongOrientationKindsAndAmbiguityAreRejected() {
        Flow fixture = prepare(() -> flow(1, false));
        prepare(
                () -> {
                    fixture.source.setOrientation(OrientationPortKind.IN);
                    return null;
                });
        reject(() -> structure.allocateExchange(fixture.transport, fixture.flow));
        prepare(
                () -> {
                    fixture.source.setOrientation(OrientationPortKind.OUT);
                    fixture.transport.setKind(ComponentExchangeKind.DELEGATION);
                    return null;
                });
        reject(() -> structure.allocateExchange(fixture.transport, fixture.flow));
        prepare(
                () -> {
                    fixture.transport.setKind(ComponentExchangeKind.FLOW);
                    fixture.source.setKind(ComponentPortKind.STANDARD);
                    return null;
                });
        reject(() -> structure.allocateExchange(fixture.transport, fixture.flow));
        prepare(
                () -> {
                    fixture.source.setKind(ComponentPortKind.FLOW);
                    ((Component) fixture.source.eContainer())
                            .getOwnedFunctionalAllocation()
                            .clear();
                    return null;
                });
        reject(() -> structure.allocateExchange(fixture.transport, fixture.flow));
        prepare(
                () -> {
                    allocate(
                            (Component) fixture.source.eContainer(),
                            (AbstractFunction) fixture.output.eContainer());
                    ((AbstractFunction) fixture.output.eContainer())
                            .getOwnedFunctions()
                            .add(LaFactory.eINSTANCE.createLogicalFunction());
                    return null;
                });
        reject(() -> structure.allocateExchange(fixture.transport, fixture.flow));
        prepare(
                () -> {
                    ((AbstractFunction) fixture.output.eContainer()).getOwnedFunctions().clear();
                    allocate(
                            (Component) fixture.target.eContainer(),
                            (AbstractFunction) fixture.output.eContainer());
                    allocate(
                            (Component) fixture.source.eContainer(),
                            (AbstractFunction) fixture.input.eContainer());
                    return null;
                });
        reject(() -> structure.allocateExchange(fixture.transport, fixture.flow));
    }

    @Test
    public void flowCrossLayerDetachedAndForeignEndpointsAreRejectedWithoutWrites() {
        Flow logical = prepare(() -> flow(1, false)), physical = prepare(() -> flow(2, false));
        reject(() -> structure.allocateExchange(logical.transport, physical.flow));
        reject(
                () ->
                        structure.allocateExchange(
                                FaFactory.eINSTANCE.createComponentExchange(), logical.flow));
        prepare(
                () -> {
                    logical.flow.setSource(physical.output);
                    return null;
                });
        reject(() -> structure.allocateExchange(logical.transport, logical.flow));
    }

    @Test
    public void malformedFunctionAllocationOwnershipAndDuplicatePortAllocationsAreRejected() {
        Flow fixture = prepare(() -> flow(1, false));
        prepare(
                () -> {
                    var allocation =
                            ((Component) fixture.source.eContainer())
                                    .getOwnedFunctionalAllocation()
                                    .get(0);
                    allocation.setSourceElement((Component) fixture.target.eContainer());
                    return null;
                });
        reject(() -> structure.allocateExchange(fixture.transport, fixture.flow));
        prepare(
                () -> {
                    var owner = (Component) fixture.source.eContainer();
                    owner.getOwnedFunctionalAllocation().get(0).setSourceElement(owner);
                    return null;
                });
        structure.allocateExchange(fixture.transport, fixture.flow);
        prepare(
                () -> {
                    var duplicate =
                            org.polarsys.capella.core.data.information.InformationFactory.eINSTANCE
                                    .createPortAllocation();
                    duplicate.setSourceElement(fixture.source);
                    duplicate.setTargetElement(fixture.output);
                    fixture.source.getOwnedPortAllocations().add(duplicate);
                    return null;
                });
        reject(() -> structure.allocateExchange(fixture.transport, fixture.flow));
    }

    @Test
    public void functionDecomposedThroughNativePackageIsNotMistakenForALeaf() {
        Flow fixture =
                prepare(
                        () -> {
                            Flow value = flow(1, false);
                            var functionPackage = LaFactory.eINSTANCE.createLogicalFunctionPkg();
                            functionPackage
                                    .getOwnedLogicalFunctions()
                                    .add(LaFactory.eINSTANCE.createLogicalFunction());
                            ((org.polarsys.capella.core.data.la.LogicalFunction)
                                            value.output.eContainer())
                                    .getOwnedLogicalFunctionPkgs()
                                    .add(functionPackage);
                            return value;
                        });
        reject(() -> structure.allocateExchange(fixture.transport, fixture.flow));
    }

    @Test
    public void equipmentInterfaceAllocatesTheFunctionsOfItsActuallyDeployedBehavior() {
        Flow fixture =
                prepare(
                        () -> {
                            Flow value = flow(2, false);
                            var left =
                                    org.polarsys.capella.core.data.pa.deployment.DeploymentFactory
                                            .eINSTANCE
                                            .createPartDeploymentLink();
                            left.setDeployedElement(behaviorPart);
                            left.setLocation(nodePart);
                            nodePart.getOwnedDeploymentLinks().add(left);
                            var right =
                                    org.polarsys.capella.core.data.pa.deployment.DeploymentFactory
                                            .eINSTANCE
                                            .createPartDeploymentLink();
                            right.setDeployedElement(otherBehaviorPart);
                            right.setLocation(otherNodePart);
                            otherNodePart.getOwnedDeploymentLinks().add(right);
                            node.getOwnedFeatures().add(value.source);
                            otherNode.getOwnedFeatures().add(value.target);
                            return value;
                        });
        structure.allocateExchange(fixture.transport, fixture.flow);
        assertPortAllocation(fixture.source, fixture.output);
        assertPortAllocation(fixture.target, fixture.input);
        prepare(
                () -> {
                    var link = nodePart.getOwnedDeploymentLinks().get(0);
                    nodePart.getOwnedDeploymentLinks().remove(link);
                    otherNodePart.getOwnedDeploymentLinks().add(link);
                    return null;
                });
        reject(() -> structure.allocateExchange(fixture.transport, fixture.flow));
    }

    @Test
    public void anotherInstanceOfTheSameBehaviorCannotSilentlyAcquireASecondDeployment() {
        structure.deploy(behaviorPart, nodePart);
        Part secondInstance = prepare(() -> ownedPart(roots[2], behavior));
        reject(() -> structure.deploy(secondInstance, otherNodePart));
        assertTrue(otherNodePart.getOwnedDeploymentLinks().isEmpty());
    }

    private void reject(Supplier<?> action) {
        int before = count(), priorSaves = saves;
        Object undo = domain.getCommandStack().getUndoCommand();
        assertThrows(IllegalArgumentException.class, action::get);
        assertEquals(before, count());
        assertEquals(priorSaves, saves);
        assertSame(undo, domain.getCommandStack().getUndoCommand());
    }

    private Flow flow(int layer, boolean actorSource) {
        Component source, target;
        if (layer == 0) {
            source = CtxFactory.eINSTANCE.createSystemComponent();
            source.setActor(true);
            target = roots[0];
            add(roots[0].eContainer(), "ownedSystemComponents", source);
        } else if (layer == 1) {
            source = logical("Source logical", actorSource);
            target = logical("Target logical", false);
        } else {
            source = actorSource ? actor : behavior;
            target = otherBehavior;
        }
        AbstractFunction outputFunction = function(layer), inputFunction = function(layer);
        functions[layer].getOwnedFunctions().addAll(List.of(outputFunction, inputFunction));
        allocate(source, outputFunction);
        allocate(target, inputFunction);
        FunctionOutputPort output = id(FaFactory.eINSTANCE.createFunctionOutputPort());
        FunctionInputPort input = id(FaFactory.eINSTANCE.createFunctionInputPort());
        outputFunction.getOutputs().add(output);
        inputFunction.getInputs().add(input);
        FunctionalExchange flow = id(FaFactory.eINSTANCE.createFunctionalExchange());
        flow.setSource(output);
        flow.setTarget(input);
        functions[layer].getOwnedFunctionalExchanges().add(flow);
        ComponentPort sourcePort = componentPort(source, true),
                targetPort = componentPort(target, false);
        ComponentExchange transport = id(FaFactory.eINSTANCE.createComponentExchange());
        transport.setKind(ComponentExchangeKind.FLOW);
        transport.setSource(sourcePort);
        transport.setTarget(targetPort);
        roots[layer].getOwnedComponentExchanges().add(transport);
        return new Flow(transport, flow, sourcePort, targetPort, output, input);
    }

    private record Flow(
            ComponentExchange transport,
            FunctionalExchange flow,
            ComponentPort source,
            ComponentPort target,
            FunctionOutputPort output,
            FunctionInputPort input) {}

    private static ComponentExchangeFunctionalExchangeAllocation exchangeAllocation(Flow fixture) {
        var allocation = FaFactory.eINSTANCE.createComponentExchangeFunctionalExchangeAllocation();
        allocation.setSourceElement(fixture.transport);
        allocation.setTargetElement(fixture.flow);
        fixture.transport.getOwnedComponentExchangeFunctionalExchangeAllocations().add(allocation);
        return allocation;
    }

    private static void allocate(Component component, AbstractFunction function) {
        var allocation = FaFactory.eINSTANCE.createComponentFunctionalAllocation();
        allocation.setSourceElement(component);
        allocation.setTargetElement(function);
        component.getOwnedFunctionalAllocation().add(allocation);
    }

    private static ComponentPort componentPort(Component component, boolean source) {
        ComponentPort port = id(FaFactory.eINSTANCE.createComponentPort());
        port.setKind(ComponentPortKind.FLOW);
        port.setOrientation(source ? OrientationPortKind.OUT : OrientationPortKind.IN);
        component.getOwnedFeatures().add(port);
        return port;
    }

    private static void assertPortAllocation(ComponentPort port, EObject functionPort) {
        var allocations = port.getOwnedPortAllocations();
        assertEquals(1, allocations.size());
        var allocation = allocations.get(0);
        assertSame(port, allocation.eContainer());
        assertSame(port, allocation.getSourceElement());
        assertSame(functionPort, allocation.getTargetElement());
    }

    private LogicalComponent logical(String name, boolean isActor) {
        var component = id(LaFactory.eINSTANCE.createLogicalComponent());
        component.setName(name);
        component.setActor(isActor);
        ((LogicalComponent) roots[1]).getOwnedLogicalComponents().add(component);
        return component;
    }

    private PhysicalComponent physical(
            String name, PhysicalComponentNature nature, boolean isActor) {
        var component = id(PaFactory.eINSTANCE.createPhysicalComponent());
        component.setName(name);
        component.setNature(nature);
        component.setActor(isActor);
        ((PhysicalComponent) roots[2]).getOwnedPhysicalComponents().add(component);
        return component;
    }

    private static AbstractFunction function(int layer) {
        return id(
                switch (layer) {
                    case 0 -> CtxFactory.eINSTANCE.createSystemFunction();
                    case 1 -> LaFactory.eINSTANCE.createLogicalFunction();
                    default -> PaFactory.eINSTANCE.createPhysicalFunction();
                });
    }

    private static Part part(Component type) {
        Part part = id(CsFactory.eINSTANCE.createPart());
        part.setAbstractType(type);
        part.setName(type.getName());
        return part;
    }

    private static Part ownedPart(Component container, Component type) {
        Part part = part(type);
        container.getOwnedFeatures().add(part);
        return part;
    }

    private static PhysicalPort port(PhysicalComponent component) {
        PhysicalPort port = id(CsFactory.eINSTANCE.createPhysicalPort());
        component.getOwnedFeatures().add(port);
        return port;
    }

    private static <T extends CapellaElement> T id(T value) {
        value.setId(UUID.randomUUID().toString());
        return value;
    }

    private <T> T prepare(Supplier<T> action) {
        return SessionSafeguards.runWrite(session, "Prepare structure fixture", action);
    }

    private int count() {
        int count = 0;
        var iterator = resource.getAllContents();
        while (iterator.hasNext()) {
            iterator.next();
            count++;
        }
        return count;
    }

    private Resource reload() throws Exception {
        var copy = resources().createResource(resource.getURI());
        copy.load(new ByteArrayInputStream(saved), Map.of());
        assertTrue(copy.getErrors().toString(), copy.getErrors().isEmpty());
        var iterator = copy.getAllContents();
        while (iterator.hasNext()) {
            EObject object = iterator.next();
            assertFalse(object.eIsProxy());
            for (var reference : object.eClass().getEAllReferences())
                if (!reference.isContainment() && !reference.isDerived()) {
                    Object value = object.eGet(reference, false);
                    if (value instanceof EObject target)
                        assertFalse(reference.getName(), target.eIsProxy());
                    else if (value instanceof List<?> values)
                        for (Object target : values)
                            if (target instanceof EObject element)
                                assertFalse(reference.getName(), element.eIsProxy());
                }
        }
        return copy;
    }

    private static ResourceSetImpl resources() {
        var set = new ResourceSetImpl();
        set.getResourceFactoryRegistry()
                .getExtensionToFactoryMap()
                .put("xmi", new XMIResourceFactoryImpl());
        return set;
    }

    private static void set(EObject owner, String feature, EObject value) {
        owner.eSet(owner.eClass().getEStructuralFeature(feature), value);
    }

    @SuppressWarnings("unchecked")
    private static void add(EObject owner, String feature, EObject value) {
        ((List<EObject>) owner.eGet(owner.eClass().getEStructuralFeature(feature))).add(value);
    }
}
