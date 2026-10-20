package org.polarsys.capella.ai.scripting;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

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
import org.polarsys.capella.common.data.modellingcore.AbstractTrace;
import org.polarsys.capella.core.data.capellacore.CapellaElement;
import org.polarsys.capella.core.data.capellamodeller.CapellamodellerFactory;
import org.polarsys.capella.core.data.cs.BlockArchitecture;
import org.polarsys.capella.core.data.cs.Component;
import org.polarsys.capella.core.data.cs.ComponentPkg;
import org.polarsys.capella.core.data.cs.CsFactory;
import org.polarsys.capella.core.data.cs.Part;
import org.polarsys.capella.core.data.ctx.CtxFactory;
import org.polarsys.capella.core.data.fa.AbstractFunction;
import org.polarsys.capella.core.data.fa.ComponentExchange;
import org.polarsys.capella.core.data.fa.ComponentExchangeKind;
import org.polarsys.capella.core.data.fa.ComponentPort;
import org.polarsys.capella.core.data.fa.ComponentPortKind;
import org.polarsys.capella.core.data.fa.FaFactory;
import org.polarsys.capella.core.data.fa.FunctionInputPort;
import org.polarsys.capella.core.data.fa.FunctionOutputPort;
import org.polarsys.capella.core.data.fa.FunctionalExchange;
import org.polarsys.capella.core.data.fa.OrientationPortKind;
import org.polarsys.capella.core.data.information.InformationFactory;
import org.polarsys.capella.core.data.information.Port;
import org.polarsys.capella.core.data.la.LaFactory;
import org.polarsys.capella.core.data.oa.OaFactory;
import org.polarsys.capella.core.data.pa.PaFactory;
import org.polarsys.capella.core.data.pa.PhysicalComponentNature;

/** Native metamodel and native command stack; Session alone is a proxy. */
public class ArcadiaInterfaceAPITest {
    private Resource resource;
    private TransactionalEditingDomain domain;
    private Session session;
    private ArcadiaInterfaceAPI interfaces;
    private boolean open;
    private int saves;
    private byte[] saved;
    private final BlockArchitecture[] layers = new BlockArchitecture[4];
    private final AbstractFunction[] roots = new AbstractFunction[4];
    private final Component[] systems = new Component[4];
    private final ComponentPkg[] packages = new ComponentPkg[4];

    @Before public void setUp() {
        resource = resources().createResource(URI.createURI("memory:/interface-realizations.xmi"));
        var project = id(CapellamodellerFactory.eINSTANCE.createProject());
        var engineering = id(CapellamodellerFactory.eINSTANCE.createSystemEngineering());
        project.getOwnedModelRoots().add(engineering); resource.getContents().add(project);
        for (int layer = 0; layer < 4; layer++) {
            EObject functionPackage;
            String functions, components, packageFeature;
            if (layer == 0) {
                layers[layer] = id(OaFactory.eINSTANCE.createOperationalAnalysis());
                packages[layer] = id(OaFactory.eINSTANCE.createEntityPkg());
                roots[layer] = function(layer); systems[layer] = component(layer, false);
                functionPackage = id(OaFactory.eINSTANCE.createOperationalActivityPkg());
                functions = "ownedOperationalActivities"; components = "ownedEntities"; packageFeature = "ownedEntityPkg";
            } else if (layer == 1) {
                layers[layer] = id(CtxFactory.eINSTANCE.createSystemAnalysis());
                packages[layer] = id(CtxFactory.eINSTANCE.createSystemComponentPkg());
                roots[layer] = function(layer); systems[layer] = component(layer, false);
                functionPackage = id(CtxFactory.eINSTANCE.createSystemFunctionPkg());
                functions = "ownedSystemFunctions"; components = "ownedSystemComponents"; packageFeature = "ownedSystemComponentPkg";
            } else if (layer == 2) {
                layers[layer] = id(LaFactory.eINSTANCE.createLogicalArchitecture());
                packages[layer] = id(LaFactory.eINSTANCE.createLogicalComponentPkg());
                roots[layer] = function(layer); systems[layer] = component(layer, false);
                functionPackage = id(LaFactory.eINSTANCE.createLogicalFunctionPkg());
                functions = "ownedLogicalFunctions"; components = "ownedLogicalComponents"; packageFeature = "ownedLogicalComponentPkg";
            } else {
                layers[layer] = id(PaFactory.eINSTANCE.createPhysicalArchitecture());
                packages[layer] = id(PaFactory.eINSTANCE.createPhysicalComponentPkg());
                roots[layer] = function(layer); systems[layer] = id(PaFactory.eINSTANCE.createPhysicalComponent());
                functionPackage = id(PaFactory.eINSTANCE.createPhysicalFunctionPkg());
                functions = "ownedPhysicalFunctions"; components = "ownedPhysicalComponents"; packageFeature = "ownedPhysicalComponentPkg";
            }
            set(layers[layer], packageFeature, packages[layer]); set(layers[layer], "ownedFunctionPkg", functionPackage);
            add(functionPackage, functions, roots[layer]); add(packages[layer], components, systems[layer]);
            engineering.getOwnedArchitectures().add(layers[layer]);
        }
        resource.setTrackingModification(true);
        domain = TransactionalEditingDomain.Factory.INSTANCE.createEditingDomain(resource.getResourceSet()); open = true;
        session = (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[] {Session.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "isOpen" -> open;
                case "getTransactionalEditingDomain" -> domain;
                case "getSemanticResources" -> List.of(resource);
                case "getSessionResource" -> resource;
                case "save" -> {
                    saves++; var output = new ByteArrayOutputStream(); resource.save(output, Map.of()); saved = output.toByteArray(); yield null;
                }
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                case "toString" -> "Native interface test session";
                default -> null;
            });
        interfaces = new ArcadiaInterfaceAPI(session); resource.setModified(false);
    }

    @After public void tearDown() {
        if (session != null) ModelSummary.invalidate(session);
        if (domain != null) domain.dispose();
        SessionSafeguards.cleanup();
    }

    /** Live eval: agents hand-built ExchangeItemAllocation through raw EMF and failed; this is the supported path. */
    @Test public void interfacesAndExchangesCarryExchangeItemsIdempotentlyAsOneUndoableChange() {
        var interfacePkg = id(org.polarsys.capella.core.data.cs.CsFactory.eINSTANCE.createInterfacePkg());
        var dataPkg = id(InformationFactory.eINSTANCE.createDataPkg());
        var iface = id(org.polarsys.capella.core.data.cs.CsFactory.eINSTANCE.createInterface());
        iface.setName("Torque Command Interface");
        var item = id(InformationFactory.eINSTANCE.createExchangeItem());
        item.setName("Torque Setpoint");
        var producer = id(LaFactory.eINSTANCE.createLogicalFunction());
        var consumer = id(LaFactory.eINSTANCE.createLogicalFunction());
        var out = id(FaFactory.eINSTANCE.createFunctionOutputPort());
        var in = id(FaFactory.eINSTANCE.createFunctionInputPort());
        var flow = id(FaFactory.eINSTANCE.createFunctionalExchange());
        domain.getCommandStack().execute(new org.eclipse.emf.transaction.RecordingCommand(domain) {
            @Override protected void doExecute() {
                layers[2].setOwnedInterfacePkg(interfacePkg); layers[2].setOwnedDataPkg(dataPkg);
                interfacePkg.getOwnedInterfaces().add(iface); dataPkg.getOwnedExchangeItems().add(item);
                roots[2].getOwnedFunctions().addAll(List.of(producer, consumer));
                producer.getOutputs().add(out); consumer.getInputs().add(in);
                flow.setSource(out); flow.setTarget(in); roots[2].getOwnedFunctionalExchanges().add(flow);
            }
        });
        int before = saves;
        var allocation = interfaces.allocateExchangeItem(iface, item);
        assertSame(item, allocation.getAllocatedItem());
        assertNotNull("Stored with a persistent id", allocation.getId());
        assertSame(flow, interfaces.exchangeItem(flow, item));
        assertEquals(List.of(item), flow.getExchangedItems());
        assertEquals(before + 2, saves);
        domain.getCommandStack().undo();
        assertTrue(flow.getExchangedItems().isEmpty());
        domain.getCommandStack().undo();
        assertTrue(iface.getOwnedExchangeItemAllocations().isEmpty());
        domain.getCommandStack().redo();
        domain.getCommandStack().redo();
        int afterRedo = saves;
        assertSame("Repeating it reuses the allocation", allocation, interfaces.allocateExchangeItem(iface, item));
        interfaces.exchangeItem(flow, item);
        assertEquals(1, iface.getOwnedExchangeItemAllocations().size());
        assertEquals(List.of(item), flow.getExchangedItems());
        assertEquals("Repeats change and save nothing", afterRedo, saves);
    }

    @Test public void groupedSystemExchangeRealizesOperationalInteractionWithoutInventingOaPorts() throws Exception {
        Pair<Flow> pair = prepare(() -> functionPair(1));
        var relation = (AbstractTrace) interfaces.realizeInterface(pair.lower.exchange, pair.upper.exchange);
        trace(relation, pair.lower.exchange, pair.upper.exchange);
        assertTrue(pair.lower.output.getOwnedPortRealizations().isEmpty());
        assertTrue(pair.lower.input.getOwnedPortRealizations().isEmpty());
        assertTrue(pair.upper.source.getInputs().isEmpty()); assertTrue(pair.upper.source.getOutputs().isEmpty());
        assertEquals(1, saves); reload(relation);
        assertSame(relation, interfaces.realizeInterface(pair.lower.exchange, pair.upper.exchange)); assertEquals(1, saves);
    }

    @Test public void groupedAdjacentFunctionalInterfacesCreateExactlyThreePersistentDirectedTraces() throws Exception {
        for (int layer : new int[] {2, 3}) {
            Pair<Flow> pair = prepare(() -> functionPair(layer));
            var relation = (AbstractTrace) interfaces.realizeInterface(pair.lower.exchange, pair.upper.exchange);
            trace(relation, pair.lower.exchange, pair.upper.exchange);
            var output = pair.lower.output.getOwnedPortRealizations().get(0);
            var input = pair.lower.input.getOwnedPortRealizations().get(0);
            trace(output, pair.lower.output, pair.upper.output); trace(input, pair.lower.input, pair.upper.input);
            assertEquals(1, pair.lower.exchange.getOwnedFunctionalExchangeRealizations().size());
            assertEquals(1, pair.lower.output.getOwnedPortRealizations().size()); assertEquals(1, pair.lower.input.getOwnedPortRealizations().size());
            reload(relation, output, input);
            int prior = saves; assertSame(relation, interfaces.realizeInterface(pair.lower.exchange, pair.upper.exchange)); assertEquals(prior, saves);
        }
    }

    @Test public void groupedComponentInterfacesRealizeActorAndSystemPortsInBothAdjacentLayers() throws Exception {
        for (int layer : new int[] {2, 3}) {
            Pair<Transport> pair = prepare(() -> componentPair(layer));
            var relation = (AbstractTrace) interfaces.realizeInterface(pair.lower.exchange, pair.upper.exchange);
            var output = pair.lower.output.getOwnedPortRealizations().get(0);
            var input = pair.lower.input.getOwnedPortRealizations().get(0);
            trace(relation, pair.lower.exchange, pair.upper.exchange);
            trace(output, pair.lower.output, pair.upper.output); trace(input, pair.lower.input, pair.upper.input);
            assertTrue(pair.lower.source.isActor()); assertTrue(pair.upper.source.isActor());
            reload(relation, output, input);
            int prior = saves; assertSame(relation, interfaces.realizeInterface(pair.lower.exchange, pair.upper.exchange)); assertEquals(prior, saves);
        }
    }

    @Test public void instanceComponentExchangeEndsRetainTheirExactNativePartsAndPorts() throws Exception {
        Pair<Transport> pair = prepare(() -> {
            Pair<Transport> value = componentPair(3); instanceEnds(value.lower); instanceEnds(value.upper); return value;
        });
        var relation = (AbstractTrace) interfaces.realizeInterface(pair.lower.exchange, pair.upper.exchange);
        trace(relation, pair.lower.exchange, pair.upper.exchange);
        trace(pair.lower.output.getOwnedPortRealizations().get(0), pair.lower.output, pair.upper.output);
        reload(relation);
        prepare(() -> { ((org.polarsys.capella.core.data.fa.ComponentExchangeEnd) pair.lower.exchange.getSource()).setPart(pair.lower.targetPart); return null; });
        reject(() -> interfaces.realizeInterface(pair.lower.exchange, pair.upper.exchange));
    }

    @Test public void groupedCreationAndBothPortsShareOneNativeUndoAndRedo() {
        Pair<Flow> pair = prepare(() -> functionPair(3)); int before = count();
        var relation = (AbstractTrace) interfaces.realizeInterface(pair.lower.exchange, pair.upper.exchange);
        var output = pair.lower.output.getOwnedPortRealizations().get(0); var input = pair.lower.input.getOwnedPortRealizations().get(0);
        assertEquals(before + 3, count()); assertEquals(1, saves);
        domain.getCommandStack().undo(); assertEquals(before, count());
        assertTrue(pair.lower.exchange.getOwnedFunctionalExchangeRealizations().isEmpty());
        assertTrue(pair.lower.output.getOwnedPortRealizations().isEmpty()); assertTrue(pair.lower.input.getOwnedPortRealizations().isEmpty());
        assertFalse(domain.getCommandStack().canUndo());
        domain.getCommandStack().redo(); assertEquals(before + 3, count());
        trace(relation, pair.lower.exchange, pair.upper.exchange); trace(output, pair.lower.output, pair.upper.output); trace(input, pair.lower.input, pair.upper.input);
    }

    @Test public void existingIndividualExchangeRelationCanAcquireOnlyItsMissingPortRealizations() {
        Pair<Flow> pair = prepare(() -> functionPair(2));
        var relation = interfaces.realizeFunctionalExchange(pair.lower.exchange, pair.upper.exchange);
        assertTrue(pair.lower.output.getOwnedPortRealizations().isEmpty());
        assertSame(relation, interfaces.realizeInterface(pair.lower.exchange, pair.upper.exchange));
        assertEquals(1, pair.lower.exchange.getOwnedFunctionalExchangeRealizations().size());
        assertEquals(1, pair.lower.output.getOwnedPortRealizations().size()); assertEquals(1, pair.lower.input.getOwnedPortRealizations().size());
        assertEquals(2, saves);
    }

    @Test public void relationFactoryIndividualBuildersUseTypedNativeDirectedContainment() throws Exception {
        Pair<Flow> flow = prepare(() -> functionPair(2)); Pair<Transport> transport = prepare(() -> componentPair(3));
        var fe = (AbstractTrace) RelationFactory.link(session, flow.lower.exchange, flow.upper.exchange, "FunctionalExchangeRealization");
        var ce = (AbstractTrace) RelationFactory.link(session, transport.lower.exchange, transport.upper.exchange, "ComponentExchangeRealization");
        var fp = (AbstractTrace) RelationFactory.link(session, flow.lower.output, flow.upper.output, "PortRealization");
        var cp = (AbstractTrace) RelationFactory.link(session, transport.lower.input, transport.upper.input, "PortRealization");
        trace(fe, flow.lower.exchange, flow.upper.exchange); trace(ce, transport.lower.exchange, transport.upper.exchange);
        trace(fp, flow.lower.output, flow.upper.output); trace(cp, transport.lower.input, transport.upper.input);
        assertEquals(4, saves); reload(fe, ce, fp, cp);
        assertSame(fe, RelationFactory.link(session, flow.lower.exchange, flow.upper.exchange, "FunctionalExchangeRealization")); assertEquals(4, saves);
        reject(() -> RelationFactory.link(session, transport.lower.exchange, flow.upper.exchange, "FunctionalExchangeRealization"));
        reject(() -> RelationFactory.link(session, flow.lower.output, transport.upper.input, "PortRealization"));
    }

    @Test public void enclosingFailedPhaseRollsBackEveryCreatedRelationWithoutSaving() {
        Pair<Transport> pair = prepare(() -> componentPair(3)); int before = count();
        assertThrows(IllegalArgumentException.class, () -> SessionSafeguards.runWrite(session, "Failed reviewed interface phase", () -> {
            interfaces.realizeInterface(pair.lower.exchange, pair.upper.exchange); throw new IllegalArgumentException("Failure after all three traces");
        }));
        assertEquals(before, count()); assertEquals(0, saves); assertFalse(domain.getCommandStack().canUndo());
        assertTrue(pair.lower.exchange.getOwnedComponentExchangeRealizations().isEmpty());
        assertTrue(pair.lower.output.getOwnedPortRealizations().isEmpty()); assertTrue(pair.lower.input.getOwnedPortRealizations().isEmpty());
        assertFalse(resource.isModified());
    }

    @Test public void reversedSameSkippedLayersAndMixedExchangeFamiliesCannotMutateAnything() throws Exception {
        Pair<Flow> pair = prepare(() -> functionPair(2)); Flow physical = prepare(() -> flow(3));
        Transport transport = prepare(() -> transport(2));
        reject(() -> interfaces.realizeInterface(pair.upper.exchange, pair.lower.exchange));
        reject(() -> interfaces.realizeInterface(pair.lower.exchange, pair.lower.exchange));
        reject(() -> interfaces.realizeInterface(physical.exchange, pair.upper.exchange));
        reject(() -> interfaces.realizeInterface(pair.lower.exchange, transport.exchange));
        reject(() -> interfaces.realizeInterface(null, pair.upper.exchange));
    }

    @Test public void detachedProxyForeignResourceAndDifferentSystemEngineeringAreRejected() throws Exception {
        Pair<Flow> pair = prepare(() -> functionPair(2));
        reject(() -> interfaces.realizeFunctionalExchange(id(FaFactory.eINSTANCE.createFunctionalExchange()), pair.upper.exchange));
        FunctionalExchange proxy = id(FaFactory.eINSTANCE.createFunctionalExchange()); ((InternalEObject) proxy).eSetProxyURI(URI.createURI("memory:/foreign.xmi#unknown"));
        reject(() -> interfaces.realizeFunctionalExchange(proxy, pair.upper.exchange));
        Resource other = resources().createResource(URI.createURI("memory:/foreign.xmi"));
        var foreign = id(FaFactory.eINSTANCE.createFunctionalExchange()); other.getContents().add(foreign);
        reject(() -> interfaces.realizeFunctionalExchange(foreign, pair.upper.exchange));
        prepare(() -> {
            var second = id(CapellamodellerFactory.eINSTANCE.createSystemEngineering());
            ((org.polarsys.capella.core.data.capellamodeller.Project) resource.getContents().get(0)).getOwnedModelRoots().add(second);
            second.getOwnedArchitectures().add(layers[2]); return null;
        });
        reject(() -> interfaces.realizeFunctionalExchange(pair.lower.exchange, pair.upper.exchange));
    }

    @Test public void missingWrongOrDuplicateFunctionOwnerRealizationPreventsAnyPartialGroup() throws Exception {
        Pair<Flow> pair = prepare(() -> functionPair(2));
        prepare(() -> { pair.lower.target.getOwnedFunctionRealizations().clear(); return null; });
        reject(() -> interfaces.realizeInterface(pair.lower.exchange, pair.upper.exchange));
        prepare(() -> { functionTrace(pair.lower.target, pair.upper.source); return null; });
        reject(() -> interfaces.realizeInterface(pair.lower.exchange, pair.upper.exchange));
        prepare(() -> { functionTrace(pair.lower.target, pair.upper.target); functionTrace(pair.lower.target, pair.upper.target); return null; });
        reject(() -> interfaces.realizeInterface(pair.lower.exchange, pair.upper.exchange));
    }

    @Test public void missingWrongOrDuplicateComponentOwnerRealizationCannotCreateTransportTrace() throws Exception {
        Pair<Transport> pair = prepare(() -> componentPair(3));
        prepare(() -> { pair.lower.target.getOwnedComponentRealizations().clear(); return null; });
        reject(() -> interfaces.realizeComponentExchange(pair.lower.exchange, pair.upper.exchange));
        prepare(() -> { componentTrace(pair.lower.target, pair.upper.source); return null; });
        reject(() -> interfaces.realizeInterface(pair.lower.exchange, pair.upper.exchange));
        prepare(() -> { componentTrace(pair.lower.target, pair.upper.target); componentTrace(pair.lower.target, pair.upper.target); return null; });
        reject(() -> interfaces.realizeInterface(pair.lower.exchange, pair.upper.exchange));
    }

    @Test public void flowComponentSidesRequireCorrectDirectionKindsAndMatchingPortOrientations() throws Exception {
        Pair<Transport> pair = prepare(() -> componentPair(2));
        prepare(() -> { pair.lower.output.setOrientation(OrientationPortKind.IN); pair.upper.output.setOrientation(OrientationPortKind.IN); return null; });
        reject(() -> interfaces.realizeInterface(pair.lower.exchange, pair.upper.exchange));
        prepare(() -> { pair.lower.output.setOrientation(OrientationPortKind.OUT); pair.upper.output.setOrientation(OrientationPortKind.OUT);
            pair.lower.input.setOrientation(OrientationPortKind.OUT); pair.upper.input.setOrientation(OrientationPortKind.OUT); return null; });
        reject(() -> interfaces.realizeInterface(pair.lower.exchange, pair.upper.exchange));
        prepare(() -> { pair.lower.input.setOrientation(OrientationPortKind.IN); pair.upper.input.setOrientation(OrientationPortKind.IN);
            pair.lower.output.setKind(ComponentPortKind.STANDARD); pair.upper.output.setKind(ComponentPortKind.STANDARD); return null; });
        reject(() -> interfaces.realizeComponentExchange(pair.lower.exchange, pair.upper.exchange));
        prepare(() -> { pair.lower.output.setKind(ComponentPortKind.FLOW); pair.upper.output.setKind(ComponentPortKind.FLOW);
            pair.lower.output.setOrientation(OrientationPortKind.INOUT); return null; });
        reject(() -> interfaces.realizePort(pair.lower.output, pair.upper.output));
    }

    @Test public void portsUnderMigratedElementsAreNotNativeInterfaceContainment() throws Exception {
        Pair<Flow> pair = prepare(() -> functionPair(2));
        prepare(() -> { pair.lower.source.getOwnedMigratedElements().add(pair.lower.output); return null; });
        reject(() -> interfaces.realizeInterface(pair.lower.exchange, pair.upper.exchange));
        reject(() -> interfaces.realizePort(pair.lower.output, pair.upper.output));
        Pair<Transport> transport = prepare(() -> componentPair(3));
        prepare(() -> { transport.lower.source.getOwnedMigratedElements().add(transport.lower.output); return null; });
        reject(() -> interfaces.realizeComponentExchange(transport.lower.exchange, transport.upper.exchange));
    }

    @Test public void wrongFunctionClassifierAndWrongFunctionPortDirectionAreRejected() throws Exception {
        Pair<Flow> pair = prepare(() -> functionPair(2));
        prepare(() -> { pair.lower.exchange.setSource(pair.lower.input); return null; });
        reject(() -> interfaces.realizeFunctionalExchange(pair.lower.exchange, pair.upper.exchange));
        prepare(() -> {
            var wrong = id(OaFactory.eINSTANCE.createOperationalActivity()); roots[2].getOwnedFunctions().add(wrong);
            wrong.getOutputs().add(pair.lower.output); pair.lower.exchange.setSource(pair.lower.output);
            functionTrace(wrong, pair.upper.source); return null;
        });
        reject(() -> interfaces.realizeInterface(pair.lower.exchange, pair.upper.exchange));
        reject(() -> interfaces.realizePort(pair.lower.output, pair.upper.output));
    }

    @Test public void duplicatePortTraceAndMalformedExistingExchangeTracePreventAtomicRepair() throws Exception {
        Pair<Flow> pair = prepare(() -> functionPair(3));
        prepare(() -> { portTrace(pair.lower.input, pair.upper.input); portTrace(pair.lower.input, pair.upper.input); return null; });
        reject(() -> interfaces.realizeInterface(pair.lower.exchange, pair.upper.exchange));
        assertTrue(pair.lower.exchange.getOwnedFunctionalExchangeRealizations().isEmpty());
        assertTrue(pair.lower.output.getOwnedPortRealizations().isEmpty());
        prepare(() -> {
            pair.lower.input.getOwnedPortRealizations().clear();
            var malformed = id(FaFactory.eINSTANCE.createFunctionalExchangeRealization());
            malformed.setSourceElement(pair.lower.source); malformed.setTargetElement(pair.upper.exchange);
            pair.lower.exchange.getOwnedFunctionalExchangeRealizations().add(malformed); return null;
        });
        reject(() -> interfaces.realizeFunctionalExchange(pair.lower.exchange, pair.upper.exchange));
    }

    @Test public void malformedOwnerTraceAndDuplicateExchangeRelationsAreRejected() throws Exception {
        Pair<Transport> pair = prepare(() -> componentPair(2));
        prepare(() -> { pair.lower.source.getOwnedComponentRealizations().get(0).setSourceElement(pair.lower.target); return null; });
        reject(() -> interfaces.realizeInterface(pair.lower.exchange, pair.upper.exchange));
        prepare(() -> {
            pair.lower.source.getOwnedComponentRealizations().get(0).setSourceElement(pair.lower.source);
            for (int i = 0; i < 2; i++) {
                var relation = id(FaFactory.eINSTANCE.createComponentExchangeRealization()); relation.setSourceElement(pair.lower.exchange);
                relation.setTargetElement(pair.upper.exchange); pair.lower.exchange.getOwnedComponentExchangeRealizations().add(relation);
            }
            return null;
        });
        reject(() -> interfaces.realizeInterface(pair.lower.exchange, pair.upper.exchange));
    }

    @Test public void instanceEndsUnderMigratedElementsAreNotNativeExchangeContainment() throws Exception {
        Pair<Transport> pair = prepare(() -> {
            Pair<Transport> value = componentPair(3); instanceEnds(value.lower); instanceEnds(value.upper);
            value.lower.exchange.getOwnedMigratedElements().add((CapellaElement) value.lower.exchange.getSource()); return value;
        });
        assertSame(pair.lower.exchange, ((EObject) pair.lower.exchange.getSource()).eContainer());
        reject(() -> interfaces.realizeInterface(pair.lower.exchange, pair.upper.exchange));
        assertTrue(pair.lower.exchange.getOwnedComponentExchangeRealizations().isEmpty());
    }

    @Test public void unrelatedMalformedNativeTargetCannotBeIgnoredWhileAddingACorrectTrace() throws Exception {
        Pair<Flow> pair = prepare(() -> functionPair(3));
        prepare(() -> {
            var malformed = id(FaFactory.eINSTANCE.createFunctionalExchangeRealization());
            malformed.setSourceElement(pair.lower.exchange); malformed.setTargetElement(systems[2]);
            pair.lower.exchange.getOwnedFunctionalExchangeRealizations().add(malformed); return null;
        });
        reject(() -> interfaces.realizeInterface(pair.lower.exchange, pair.upper.exchange));
        assertEquals(1, pair.lower.exchange.getOwnedFunctionalExchangeRealizations().size());
        assertTrue(pair.lower.output.getOwnedPortRealizations().isEmpty());
        prepare(() -> {
            pair.lower.exchange.getOwnedFunctionalExchangeRealizations().clear();
            portTrace(pair.lower.output, pair.upper.input); return null;
        });
        reject(() -> interfaces.realizePort(pair.lower.output, pair.upper.output));
    }

    @Test public void closedOrMissingSessionHasNoSideEffects() {
        open = false; assertThrows(IllegalStateException.class, () -> interfaces.realizeInterface(null, null));
        assertThrows(IllegalStateException.class, () -> new ArcadiaInterfaceAPI(null).realizeInterface(null, null));
        assertEquals(0, saves); assertFalse(domain.getCommandStack().canUndo());
    }

    private record Pair<T>(T lower, T upper) {}
    private record Flow(FunctionalExchange exchange, AbstractFunction source, AbstractFunction target,
        FunctionOutputPort output, FunctionInputPort input) {}
    private record Transport(ComponentExchange exchange, Component source, Component target,
        ComponentPort output, ComponentPort input, Part sourcePart, Part targetPart) {}

    private Pair<Flow> functionPair(int lower) {
        Flow low = flow(lower), high = flow(lower - 1);
        functionTrace(low.source, high.source); functionTrace(low.target, high.target); return new Pair<>(low, high);
    }

    private Flow flow(int layer) {
        AbstractFunction source = function(layer), target = function(layer); roots[layer].getOwnedFunctions().addAll(List.of(source, target));
        var exchange = id(FaFactory.eINSTANCE.createFunctionalExchange()); roots[layer].getOwnedFunctionalExchanges().add(exchange);
        FunctionOutputPort output = null; FunctionInputPort input = null;
        if (layer == 0) { exchange.setSource(source); exchange.setTarget(target); }
        else {
            output = id(FaFactory.eINSTANCE.createFunctionOutputPort()); input = id(FaFactory.eINSTANCE.createFunctionInputPort());
            source.getOutputs().add(output); target.getInputs().add(input); exchange.setSource(output); exchange.setTarget(input);
        }
        return new Flow(exchange, source, target, output, input);
    }

    private Pair<Transport> componentPair(int lower) {
        Transport low = transport(lower), high = transport(lower - 1);
        componentTrace(low.source, high.source); componentTrace(low.target, high.target); return new Pair<>(low, high);
    }

    private Transport transport(int layer) {
        Component source = component(layer, true), target = layer == 1 ? systems[layer] : component(layer, false);
        String feature = layer == 1 ? "ownedSystemComponents" : layer == 2 ? "ownedLogicalComponents" : "ownedPhysicalComponents";
        add(packages[layer], feature, source); if (layer != 1) add(systems[layer], feature, target);
        var output = id(FaFactory.eINSTANCE.createComponentPort()); output.setKind(ComponentPortKind.FLOW); output.setOrientation(OrientationPortKind.OUT);
        var input = id(FaFactory.eINSTANCE.createComponentPort()); input.setKind(ComponentPortKind.FLOW); input.setOrientation(OrientationPortKind.IN);
        source.getOwnedFeatures().add(output); target.getOwnedFeatures().add(input);
        var sourcePart = id(CsFactory.eINSTANCE.createPart()); sourcePart.setAbstractType(source); packages[layer].getOwnedParts().add(sourcePart);
        var targetPart = id(CsFactory.eINSTANCE.createPart()); targetPart.setAbstractType(target);
        if (layer == 1) packages[layer].getOwnedParts().add(targetPart); else systems[layer].getOwnedFeatures().add(targetPart);
        var exchange = id(FaFactory.eINSTANCE.createComponentExchange()); exchange.setKind(ComponentExchangeKind.FLOW);
        exchange.setSource(output); exchange.setTarget(input); add(packages[layer], "ownedComponentExchanges", exchange);
        return new Transport(exchange, source, target, output, input, sourcePart, targetPart);
    }

    private static void instanceEnds(Transport value) {
        var source = id(FaFactory.eINSTANCE.createComponentExchangeEnd()); source.setPort(value.output); source.setPart(value.sourcePart);
        var target = id(FaFactory.eINSTANCE.createComponentExchangeEnd()); target.setPort(value.input); target.setPart(value.targetPart);
        value.exchange.getOwnedComponentExchangeEnds().addAll(List.of(source, target)); value.exchange.setSource(source); value.exchange.setTarget(target);
    }

    private static AbstractFunction function(int layer) {
        return id(switch (layer) { case 0 -> OaFactory.eINSTANCE.createOperationalActivity(); case 1 -> CtxFactory.eINSTANCE.createSystemFunction();
            case 2 -> LaFactory.eINSTANCE.createLogicalFunction(); default -> PaFactory.eINSTANCE.createPhysicalFunction(); });
    }

    private static Component component(int layer, boolean actor) {
        Component result = id(switch (layer) { case 0 -> OaFactory.eINSTANCE.createEntity(); case 1 -> CtxFactory.eINSTANCE.createSystemComponent();
            case 2 -> LaFactory.eINSTANCE.createLogicalComponent(); default -> PaFactory.eINSTANCE.createPhysicalComponent(); });
        result.setActor(actor);
        if (result instanceof org.polarsys.capella.core.data.pa.PhysicalComponent physical) physical.setNature(actor ? PhysicalComponentNature.NODE : PhysicalComponentNature.BEHAVIOR);
        return result;
    }

    private static void functionTrace(AbstractFunction lower, AbstractFunction upper) {
        var relation = id(FaFactory.eINSTANCE.createFunctionRealization()); relation.setSourceElement(lower); relation.setTargetElement(upper);
        lower.getOwnedFunctionRealizations().add(relation);
    }

    private static void componentTrace(Component lower, Component upper) {
        var relation = id(CsFactory.eINSTANCE.createComponentRealization()); relation.setSourceElement(lower); relation.setTargetElement(upper);
        lower.getOwnedComponentRealizations().add(relation);
    }

    private static void portTrace(Port lower, Port upper) {
        var relation = id(InformationFactory.eINSTANCE.createPortRealization()); relation.setSourceElement(lower); relation.setTargetElement(upper);
        lower.getOwnedPortRealizations().add(relation);
    }

    private <T> T prepare(Supplier<T> action) {
        T result = SessionSafeguards.runWrite(session, "Native interface fixture", action);
        domain.getCommandStack().flush(); resource.setModified(false); return result;
    }

    private void reject(Supplier<?> action) throws Exception {
        int before = count(), priorSaves = saves; byte[] bytes = bytes(resource);
        Object undo = domain.getCommandStack().getUndoCommand(), redo = domain.getCommandStack().getRedoCommand();
        boolean dirty = resource.isModified();
        assertThrows(IllegalArgumentException.class, action::get);
        assertEquals(before, count()); assertEquals(priorSaves, saves);
        assertSame(undo, domain.getCommandStack().getUndoCommand()); assertSame(redo, domain.getCommandStack().getRedoCommand());
        assertEquals(dirty, resource.isModified()); assertArrayEquals(bytes, bytes(resource));
    }

    private static void trace(AbstractTrace relation, EObject lower, EObject upper) {
        assertSame(lower, relation.eContainer()); assertSame(lower, relation.getSourceElement()); assertSame(upper, relation.getTargetElement());
        String uuid = ((CapellaElement) relation).getId(); assertNotNull(UUID.fromString(uuid));
        assertSame(relation, relation.eResource().getEObject(uuid));
    }

    private void reload(AbstractTrace... relations) throws Exception {
        assertNotNull(saved); Resource copy = resources().createResource(resource.getURI()); copy.load(new ByteArrayInputStream(saved), Map.of());
        assertTrue(copy.getErrors().toString(), copy.getErrors().isEmpty());
        for (AbstractTrace original : relations) {
            var relation = (AbstractTrace) copy.getEObject(((CapellaElement) original).getId());
            assertNotNull(relation); assertEquals(original.eClass(), relation.eClass());
            assertEquals(((CapellaElement) original.getSourceElement()).getId(), ((CapellaElement) relation.getSourceElement()).getId());
            assertEquals(((CapellaElement) original.getTargetElement()).getId(), ((CapellaElement) relation.getTargetElement()).getId());
            assertSame(relation.eContainer(), relation.getSourceElement());
        }
        var iterator = copy.getAllContents(); while (iterator.hasNext()) {
            EObject object = iterator.next(); assertFalse(object.eIsProxy());
            for (var reference : object.eClass().getEAllReferences()) if (!reference.isDerived() && !reference.isTransient() && !reference.isContainer()) {
                Object value = object.eGet(reference, false);
                if (value instanceof EObject target) assertFalse(reference.getName(), target.eIsProxy());
                else if (value instanceof List<?> values) for (Object target : values) if (target instanceof EObject child) assertFalse(reference.getName(), child.eIsProxy());
            }
        }
    }

    private int count() { int result = 0; var iterator = resource.getAllContents(); while (iterator.hasNext()) { iterator.next(); result++; } return result; }
    private static ResourceSetImpl resources() { var set = new ResourceSetImpl(); set.getResourceFactoryRegistry().getExtensionToFactoryMap().put("xmi", new XMIResourceFactoryImpl()); return set; }
    private static <T extends CapellaElement> T id(T value) { value.setId(UUID.randomUUID().toString()); return value; }
    private static void set(EObject owner, String feature, EObject value) { owner.eSet(owner.eClass().getEStructuralFeature(feature), value); }
    @SuppressWarnings("unchecked") private static void add(EObject owner, String feature, EObject value) { ((List<EObject>) owner.eGet(owner.eClass().getEStructuralFeature(feature))).add(value); }
    private static byte[] bytes(Resource value) throws Exception {
        boolean dirty = value.isModified(); var output = new ByteArrayOutputStream();
        try { value.save(output, Map.of()); return output.toByteArray(); } finally { value.setModified(dirty); }
    }
}
