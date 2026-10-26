package com.sidkolapalli.capella.ai.scripting;

import static org.junit.Assert.*;

import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.sirius.business.api.session.Session;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import com.sidkolapalli.capella.ai.model.ModelOps;
import com.sidkolapalli.capella.ai.model.ModelSummary;
import com.sidkolapalli.capella.ai.session.SessionSafeguards;
import org.polarsys.capella.core.data.capellacore.CapellaElement;
import org.polarsys.capella.core.data.capellamodeller.CapellamodellerFactory;
import org.polarsys.capella.core.data.cs.BlockArchitecture;
import org.polarsys.capella.core.data.cs.Component;
import org.polarsys.capella.core.data.cs.Part;
import org.polarsys.capella.core.data.ctx.CtxFactory;
import org.polarsys.capella.core.data.fa.AbstractFunction;
import org.polarsys.capella.core.data.fa.ComponentExchange;
import org.polarsys.capella.core.data.fa.FunctionalChain;
import org.polarsys.capella.core.data.fa.FunctionalChainInvolvementFunction;
import org.polarsys.capella.core.data.fa.FunctionalChainInvolvementLink;
import org.polarsys.capella.core.data.fa.FunctionalChainKind;
import org.polarsys.capella.core.data.fa.FunctionalExchange;
import org.polarsys.capella.core.data.information.datavalue.OpaqueExpression;
import org.polarsys.capella.core.data.interaction.AbstractCapability;
import org.polarsys.capella.core.data.interaction.EventReceiptOperation;
import org.polarsys.capella.core.data.interaction.EventSentOperation;
import org.polarsys.capella.core.data.interaction.Execution;
import org.polarsys.capella.core.data.interaction.ExecutionEnd;
import org.polarsys.capella.core.data.interaction.InteractionFactory;
import org.polarsys.capella.core.data.interaction.MessageKind;
import org.polarsys.capella.core.data.interaction.Scenario;
import org.polarsys.capella.core.data.interaction.ScenarioKind;
import org.polarsys.capella.core.data.interaction.SequenceMessage;
import org.polarsys.capella.core.data.la.LaFactory;
import org.polarsys.capella.core.data.oa.OaFactory;
import org.polarsys.capella.core.data.oa.OperationalCapability;
import org.polarsys.capella.core.data.pa.PaFactory;
import org.polarsys.capella.core.model.handler.helpers.CapellaProjectHelper;
import org.polarsys.capella.core.model.handler.helpers.CapellaProjectHelper.ProjectApproach;
import org.polarsys.capella.core.model.helpers.AbstractFunctionExt;
import org.polarsys.capella.core.model.helpers.ScenarioExt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Actual Capella semantic objects and transactions; only the Sirius session service is a proxy. */
public class ArcadiaBehaviorAPITest {
    private static final String[] LAYERS = {"OA", "SA", "LA", "PA"};
    private static final String[] FUNCTIONS = {
        "OperationalActivity", "SystemFunction", "LogicalFunction", "PhysicalFunction"
    };
    private static final String[] COMPONENTS = {
        "Entity", "SystemComponent", "LogicalComponent", "PhysicalComponent"
    };
    private ResourceSet resources;
    private Resource resource;
    private TransactionalEditingDomain domain;
    private Session session;
    private CapellaAPI api;
    private ArcadiaBehaviorAPI behavior;
    private final BlockArchitecture[] architectures = new BlockArchitecture[4];
    private final AbstractFunction[] roots = new AbstractFunction[4];
    private final AbstractCapability[] capabilities = new AbstractCapability[4];
    private int saves;
    private byte[] saved;
    private boolean open;

    @Before
    public void setUp() {
        resources = resourceSet();
        resource = resources.createResource(URI.createURI("memory:/behavior.xmi"));
        var project = identified(CapellamodellerFactory.eINSTANCE.createProject());
        CapellaProjectHelper.setProjectWithApproach(project, ProjectApproach.SingletonComponents);
        var engineering = identified(CapellamodellerFactory.eINSTANCE.createSystemEngineering());
        project.getOwnedModelRoots().add(engineering);
        resource.getContents().add(project);
        for (int index = 0; index < 4; index++) {
            architectures[index] = architecture(index);
            engineering.getOwnedArchitectures().add(architectures[index]);
        }
        domain = TransactionalEditingDomain.Factory.INSTANCE.createEditingDomain(resources);
        open = true;
        session =
                (Session)
                        Proxy.newProxyInstance(
                                Session.class.getClassLoader(),
                                new Class<?>[] {Session.class},
                                (proxy, method, arguments) ->
                                        switch (method.getName()) {
                                            case "getTransactionalEditingDomain" -> domain;
                                            case "getSemanticResources" -> List.of(resource);
                                            case "getSessionResource" -> resource;
                                            case "isOpen" -> open;
                                            case "save" -> {
                                                ByteArrayOutputStream output =
                                                        new ByteArrayOutputStream();
                                                resource.save(output, Map.of());
                                                saved = output.toByteArray();
                                                saves++;
                                                yield null;
                                            }
                                            case "equals" -> proxy == arguments[0];
                                            case "hashCode" -> System.identityHashCode(proxy);
                                            case "toString" -> "Behavior API session";
                                            default -> null;
                                        });
        api = new CapellaAPI(session);
        behavior = new ArcadiaBehaviorAPI(session);
    }

    @After
    public void tearDown() {
        if (session != null) ModelSummary.invalidate(session);
        if (domain != null) domain.dispose();
    }

    @Test
    public void nativeCapabilityInvolvementsSupportAllFourArchitecturesAndAreIdempotent() {
        for (int layer = 0; layer < 4; layer++) {
            AbstractFunction function = function(layer, "Involved function " + layer);
            Component component =
                    (Component)
                            api.create(
                                    LAYERS[layer],
                                    COMPONENTS[layer],
                                    "Involved component " + layer);
            var functionLink = behavior.involveFunction(capabilities[layer], function);
            var componentLink = behavior.involveComponent(capabilities[layer], component);
            assertSame(capabilities[layer], functionLink.eContainer());
            assertSame(capabilities[layer], componentLink.eContainer());
            assertSame(function, functionLink.getInvolved());
            assertSame(component, componentLink.getInvolved());
            int saveCount = saves;
            assertSame(functionLink, behavior.involveFunction(capabilities[layer], function));
            assertSame(componentLink, behavior.involveComponent(capabilities[layer], component));
            assertEquals(saveCount, saves);
            assertTrue(
                    capabilities[layer]
                            .getOwnedAbstractFunctionAbstractCapabilityInvolvements()
                            .stream()
                            .anyMatch(link -> link.getInvolved() == function));
            if (Platform.isRunning())
                assertTrue(capabilities[layer].getInvolvedAbstractFunctions().contains(function));
        }
    }

    @Test
    public void adjacentCapabilityRealizationsPersistTheirDirectionAndRejectSkippedLevels()
            throws Exception {
        for (int index = 1; index < 4; index++) {
            var link = behavior.realizeCapability(capabilities[index], capabilities[index - 1]);
            assertSame(capabilities[index], link.eContainer());
            assertSame(capabilities[index], link.getSourceElement());
            assertSame(capabilities[index - 1], link.getTargetElement());
            int saveCount = saves;
            assertSame(
                    link, behavior.realizeCapability(capabilities[index], capabilities[index - 1]));
            assertEquals(saveCount, saves);
            var persisted =
                    (org.polarsys.capella.core.data.interaction.AbstractCapabilityRealization)
                            ModelOps.findById(reload(), link.getId());
            assertEquals(
                    capabilities[index].getId(),
                    ((CapellaElement) persisted.getSourceElement()).getId());
            assertEquals(
                    capabilities[index - 1].getId(),
                    ((CapellaElement) persisted.getTargetElement()).getId());
        }
        unchanged(() -> behavior.realizeCapability(capabilities[2], capabilities[0]));
        unchanged(() -> behavior.realizeCapability(capabilities[0], capabilities[1]));
    }

    @Test
    public void sixFunctionChainHasActualOrderedInvolvementsAndExchangeEndpointsAfterReload()
            throws Exception {
        Flow flow = flow(1, 6);
        FunctionalChain chain =
                behavior.linearChain(
                        roots[1], "Authorise electric drive", flow.functions(), flow.exchanges());
        assertChain(chain, flow);
        var involvement = behavior.involveChain(capabilities[1], chain);
        assertSame(chain, involvement.getInvolved());
        assertSame(capabilities[1], involvement.eContainer());
        ResourceSet reloaded = reload();
        FunctionalChain persisted = (FunctionalChain) ModelOps.findById(reloaded, chain.getId());
        assertEquals(11, persisted.getOwnedFunctionalChainInvolvements().size());
        for (var item : persisted.getOwnedFunctionalChainInvolvements()) {
            assertNotNull(item.getInvolved());
            assertFalse(item.getInvolved().eIsProxy());
            if (item instanceof FunctionalChainInvolvementLink link) {
                assertTrue(link.getSource() instanceof FunctionalChainInvolvementFunction);
                assertTrue(link.getTarget() instanceof FunctionalChainInvolvementFunction);
                assertSame(persisted, link.getSource().eContainer());
            }
        }
    }

    @Test
    public void chainsUseNativeLogicalAndPhysicalFunctions() {
        for (int layer : List.of(2, 3)) {
            Flow flow = flow(layer, 3);
            assertChain(
                    behavior.linearChain(
                            roots[layer], "Chain " + layer, flow.functions(), flow.exchanges()),
                    flow);
        }
    }

    @Test
    public void facadeInvalidatesPrimedLookupInsideOneWriteAndSavesOnlyAfterCommit() {
        Flow flow = flow(1, 3);
        int saveCount = saves;
        FunctionalChain chain =
                SessionSafeguards.runWrite(
                        session,
                        "Reviewed behavior proposal",
                        () -> {
                            assertTrue(api.findAll("FunctionalChain").isEmpty());
                            FunctionalChain created =
                                    api.arcadia.linearChain(
                                            roots[1],
                                            "Newly visible chain",
                                            flow.functions(),
                                            flow.exchanges());
                            assertSame(created, api.find("FunctionalChain", "Newly visible chain"));
                            assertTrue(api.findAll("FunctionalChain").contains(created));
                            assertEquals(saveCount, saves);
                            api.arcadia.involveChain(capabilities[1], created);
                            return created;
                        });
        assertEquals(saveCount + 1, saves);
        assertSame(roots[1], chain.eContainer());
        domain.getCommandStack().undo();
        assertTrue(roots[1].getOwnedFunctionalChains().isEmpty());
        assertTrue(
                capabilities[1].getOwnedFunctionalChainAbstractCapabilityInvolvements().isEmpty());
        domain.getCommandStack().redo();
        assertSame(chain, roots[1].getOwnedFunctionalChains().get(0));
        assertSame(
                chain,
                capabilities[1]
                        .getOwnedFunctionalChainAbstractCapabilityInvolvements()
                        .get(0)
                        .getInvolved());
    }

    @Test
    public void rejectsDisconnectedDuplicateWrongLayerAndAggregateChainFunctionsBeforeChanges() {
        Flow flow = flow(1, 3);
        unchanged(
                () ->
                        behavior.linearChain(
                                roots[1],
                                "Disconnected",
                                List.of(flow.functions().get(1), flow.functions().get(0)),
                                List.of(flow.exchanges().get(0))));
        unchanged(
                () ->
                        behavior.linearChain(
                                roots[1],
                                "Duplicate",
                                List.of(flow.functions().get(0), flow.functions().get(0)),
                                List.of(flow.exchanges().get(0))));
        unchanged(
                () ->
                        behavior.linearChain(
                                roots[2],
                                "Wrong architecture",
                                flow.functions(),
                                flow.exchanges()));
        unchanged(
                () ->
                        behavior.linearChain(
                                roots[0], "OA unsupported", flow.functions(), flow.exchanges()));
        unchanged(
                () ->
                        behavior.linearChain(
                                roots[1],
                                "Root as leaf",
                                List.of(roots[1], flow.functions().get(0)),
                                List.of(flow.exchanges().get(0))));
        behavior.linearChain(roots[1], "Duplicate name", flow.functions(), flow.exchanges());
        unchanged(
                () ->
                        behavior.linearChain(
                                roots[1], "Duplicate name", flow.functions(), flow.exchanges()));
    }

    @Test
    public void rejectsAChainOwnedByAnotherRootInTheSameArchitecture() {
        Flow flow = flow(1, 2);
        var otherRoot = identified(CtxFactory.eINSTANCE.createSystemFunction());
        SessionSafeguards.runWrite(
                session,
                "Additional root fixture",
                () -> {
                    add(architectures[1].getOwnedFunctionPkg(), "ownedSystemFunctions", otherRoot);
                    return null;
                });
        unchanged(
                () ->
                        behavior.linearChain(
                                otherRoot,
                                "Wrong root ownership",
                                flow.functions(),
                                flow.exchanges()));
    }

    @Test
    public void operationalScenarioBuildsNativeActivityRolesEventsMessagesAndOrderedExecutions()
            throws Exception {
        Flow flow = flow(0, 5);
        for (AbstractFunction activity : flow.functions())
            behavior.involveFunction(capabilities[0], activity);
        Scenario scenario =
                behavior.operationalScenario(
                        (OperationalCapability) capabilities[0],
                        "Nominal drive request",
                        flow.exchanges(),
                        "Driver requests motion and the vehicle is available",
                        "Vehicle reports the resulting motion status");
        assertScenario(scenario, flow, ScenarioKind.INTERACTION);
        assertEquals(
                List.of("capella:linkedText"),
                ((OpaqueExpression) scenario.getPreCondition().getOwnedSpecification())
                        .getLanguages());
        assertEquals(
                "Driver requests motion and the vehicle is available",
                ((OpaqueExpression) scenario.getPreCondition().getOwnedSpecification())
                        .getBodies()
                        .get(0));
        Scenario persisted = (Scenario) ModelOps.findById(reload(), scenario.getId());
        assertEquals(ScenarioKind.INTERACTION, persisted.getKind());
        assertTrue(ScenarioExt.checkOrdering(persisted));
        assertEquals(4, persisted.getOwnedMessages().size());
        for (var message : persisted.getOwnedMessages()) {
            assertNotNull(operation(message));
            assertFalse(operation(message).eIsProxy());
            assertSame(
                    operation(message),
                    ((EventReceiptOperation) message.getReceivingEnd().getEvent()).getOperation());
            if (Platform.isRunning()) assertSame(operation(message), message.getInvokedOperation());
        }
    }

    @Test
    public void nominalAndInhibitedScenariosRemainDistinctModelsWithExplicitConditions() {
        Flow flow = flow(0, 4);
        for (AbstractFunction activity : flow.functions())
            behavior.involveFunction(capabilities[0], activity);
        var bypass =
                (FunctionalExchange)
                        api.link(
                                flow.functions().get(1),
                                flow.functions().get(3),
                                "FunctionalExchange");
        Scenario nominal =
                behavior.operationalScenario(
                        (OperationalCapability) capabilities[0],
                        "Nominal",
                        flow.exchanges(),
                        "Demand is permitted",
                        "Motion is produced and reported");
        Scenario inhibited =
                behavior.operationalScenario(
                        (OperationalCapability) capabilities[0],
                        "Inhibited",
                        List.of(flow.exchanges().get(0), bypass),
                        "Demand is inhibited",
                        "Inhibited status is reported without a motion command");
        assertEquals(3, nominal.getOwnedMessages().size());
        assertEquals(2, inhibited.getOwnedMessages().size());
        assertSame(bypass, operation(inhibited.getOwnedMessages().get(1)));
        assertFalse(
                inhibited.getOwnedInstanceRoles().stream()
                        .anyMatch(
                                role -> role.getRepresentedInstance() == flow.functions().get(2)));
        assertNotEquals(nominal.getPostCondition().getId(), inhibited.getPostCondition().getId());
    }

    @Test
    public void scenarioRejectsUninvolvedActivitiesDuplicatesBlankConditionsAndRepeatedNames() {
        Flow flow = flow(0, 2);
        OperationalCapability capability = (OperationalCapability) capabilities[0];
        unchanged(
                () ->
                        behavior.operationalScenario(
                                capability, "Uninvolved", flow.exchanges(), "Before", "After"));
        for (AbstractFunction activity : flow.functions())
            behavior.involveFunction(capability, activity);
        unchanged(
                () ->
                        behavior.operationalScenario(
                                capability,
                                "Duplicate exchange",
                                List.of(flow.exchanges().get(0), flow.exchanges().get(0)),
                                "Before",
                                "After"));
        unchanged(
                () ->
                        behavior.operationalScenario(
                                capability, "Blank condition", flow.exchanges(), " ", "After"));
        unchanged(
                () ->
                        behavior.operationalScenario(
                                capability,
                                "NUL condition",
                                flow.exchanges(),
                                "Before\0hidden",
                                "After"));
        behavior.operationalScenario(capability, "Existing", flow.exchanges(), "Before", "After");
        unchanged(
                () ->
                        behavior.operationalScenario(
                                capability, "Existing", flow.exchanges(), "Before", "After"));
    }

    /** GAPS a2: chains could only be straight lines, and OA had no operational processes. */
    @Test
    public void branchingChainHasOneInvolvementPerFunctionAndOneLinkPerExchange() throws Exception {
        AbstractFunction a = function(1, "Detect"),
                b = function(1, "Left"),
                c = function(1, "Right"),
                d = function(1, "Open");
        List<FunctionalExchange> exchanges = new ArrayList<>();
        for (AbstractFunction[] pair :
                List.of(
                        new AbstractFunction[] {a, b},
                        new AbstractFunction[] {a, c},
                        new AbstractFunction[] {b, d},
                        new AbstractFunction[] {c, d}))
            exchanges.add((FunctionalExchange) api.link(pair[0], pair[1], "FunctionalExchange"));
        FunctionalChain chain = behavior.chain(roots[1], "Trip sources", exchanges);
        assertSame(roots[1], chain.eContainer());
        var involvements = chain.getOwnedFunctionalChainInvolvements();
        assertEquals(8, involvements.size());
        for (var item : involvements)
            if (item instanceof FunctionalChainInvolvementLink link) {
                var exchange = (FunctionalExchange) link.getInvolved();
                assertSame(
                        exchange.getSource().eContainer(),
                        ((FunctionalChainInvolvementFunction) link.getSource()).getInvolved());
                assertSame(
                        exchange.getTarget().eContainer(),
                        ((FunctionalChainInvolvementFunction) link.getTarget()).getInvolved());
            }
        FunctionalChain persisted = (FunctionalChain) ModelOps.findById(reload(), chain.getId());
        assertEquals(8, persisted.getOwnedFunctionalChainInvolvements().size());
    }

    @Test
    public void operationalAnalysisGetsAnOperationalProcess() {
        Flow flow = flow(0, 3);
        FunctionalChain process = behavior.chain(roots[0], "Inspect the car", flow.exchanges());
        assertTrue(process instanceof org.polarsys.capella.core.data.oa.OperationalProcess);
        assertEquals(5, process.getOwnedFunctionalChainInvolvements().size());
    }

    @Test
    public void chainRejectsDisconnectedExchangesAndOtherLayers() {
        Flow first = flow(1, 2), second = flow(1, 2), logical = flow(2, 2);
        unchanged(
                () ->
                        behavior.chain(
                                roots[1],
                                "Disconnected",
                                List.of(first.exchanges().get(0), second.exchanges().get(0))));
        unchanged(() -> behavior.chain(roots[1], "Wrong layer", logical.exchanges()));
        behavior.chain(roots[1], "Existing", first.exchanges());
        unchanged(() -> behavior.chain(roots[1], "Existing", first.exchanges()));
    }

    /** GAPS a1: SA/LA/PA scenarios had no way to get messages. */
    @Test
    public void functionalScenariosHaveOneLifelinePerFunctionInSystemLogicalAndPhysical()
            throws Exception {
        for (int layer = 1; layer < 4; layer++) {
            Flow flow = flow(layer, 4);
            for (AbstractFunction function : flow.functions())
                behavior.involveFunction(capabilities[layer], function);
            Scenario scenario =
                    behavior.functionalScenario(
                            capabilities[layer],
                            "Shutdown " + layer,
                            flow.exchanges(),
                            "A fault is present",
                            "The shutdown path is open");
            assertSame(capabilities[layer], scenario.eContainer());
            assertScenario(scenario, flow, ScenarioKind.FUNCTIONAL);
            Scenario persisted = (Scenario) ModelOps.findById(reload(), scenario.getId());
            assertTrue(ScenarioExt.checkOrdering(persisted));
            assertEquals(3, persisted.getOwnedMessages().size());
        }
    }

    @Test
    public void functionalScenarioRejectsOperationalCapabilitiesAndUninvolvedFunctions() {
        Flow system = flow(1, 2), operational = flow(0, 2);
        unchanged(
                () ->
                        behavior.functionalScenario(
                                capabilities[1],
                                "Uninvolved",
                                system.exchanges(),
                                "Before",
                                "After"));
        for (AbstractFunction activity : operational.functions())
            behavior.involveFunction(capabilities[0], activity);
        unchanged(
                () ->
                        behavior.functionalScenario(
                                capabilities[0],
                                "Operational",
                                operational.exchanges(),
                                "Before",
                                "After"));
    }

    @Test
    public void exchangeScenarioPutsMessagesBetweenTheAllocatingComponentsParts() {
        for (int layer = 1; layer < 4; layer++) {
            Flow flow = flow(layer, 3);
            List<Component> components = new ArrayList<>();
            for (int index = 0; index < 3; index++) {
                var component =
                        (Component)
                                api.create(
                                        LAYERS[layer],
                                        COMPONENTS[layer],
                                        "Unit " + layer + " " + index);
                api.allocate(flow.functions().get(index), component);
                behavior.involveComponent(capabilities[layer], component);
                components.add(component);
            }
            Scenario scenario =
                    behavior.exchangeScenario(
                            capabilities[layer],
                            "Exchanges " + layer,
                            flow.exchanges(),
                            "Before",
                            "After");
            assertEquals(ScenarioKind.DATA_FLOW, scenario.getKind());
            assertEquals(3, scenario.getOwnedInstanceRoles().size());
            assertTrue(ScenarioExt.checkOrdering(scenario));
            for (int index = 0; index < 2; index++) {
                var message = scenario.getOwnedMessages().get(index);
                assertSame(flow.exchanges().get(index), operation(message));
                assertSame(components.get(index), lifeline(message.getSendingEnd()));
                assertSame(components.get(index + 1), lifeline(message.getReceivingEnd()));
            }
            var exchange =
                    (ComponentExchange)
                            api.link(components.get(0), components.get(1), "ComponentExchange");
            Scenario carried =
                    behavior.exchangeScenario(
                            capabilities[layer],
                            "Component exchanges " + layer,
                            List.of(exchange),
                            "Before",
                            "After");
            assertSame(exchange, operation(carried.getOwnedMessages().get(0)));
            assertSame(
                    components.get(1),
                    lifeline(carried.getOwnedMessages().get(0).getReceivingEnd()));
            int selected = layer;
            unchanged(
                    () ->
                            behavior.exchangeScenario(
                                    capabilities[selected],
                                    "Mixed",
                                    List.of(flow.exchanges().get(0), exchange),
                                    "Before",
                                    "After"));
        }
    }

    @Test
    public void exchangeScenarioRejectsUninvolvedComponents() {
        Flow flow = flow(2, 2);
        for (int index = 0; index < 2; index++)
            api.allocate(
                    flow.functions().get(index),
                    api.create("LA", "LogicalComponent", "Uninvolved " + index));
        unchanged(
                () ->
                        behavior.exchangeScenario(
                                capabilities[2],
                                "Uninvolved",
                                flow.exchanges(),
                                "Before",
                                "After"));
    }

    private static Component lifeline(org.polarsys.capella.core.data.interaction.MessageEnd end) {
        return (Component)
                ((Part) end.getCoveredInstanceRoles().get(0).getRepresentedInstance())
                        .getAbstractType();
    }

    /** Live FSAE run: the agent had no way to create a system exchange scenario. */
    @Test
    public void systemLogicalAndPhysicalScenariosGetTheirNativeKindUnderTheCapability() {
        Scenario exchange = behavior.scenario(capabilities[1], "IMD fault shutdown", "exchange");
        assertSame(capabilities[1], exchange.eContainer());
        assertEquals(ScenarioKind.DATA_FLOW, exchange.getKind());
        assertNotNull(exchange.getId());
        assertEquals(
                ScenarioKind.FUNCTIONAL,
                behavior.scenario(capabilities[2], "Shutdown functions", "functional").getKind());
        assertEquals(
                ScenarioKind.INTERFACE,
                behavior.scenario(capabilities[3], "Shutdown interfaces", "interface").getKind());
        unchanged(() -> behavior.scenario(capabilities[1], "IMD fault shutdown", "exchange"));
        unchanged(() -> behavior.scenario(capabilities[1], "Unknown kind", "sequence"));
        unchanged(() -> behavior.scenario(capabilities[0], "Operational", "exchange"));
    }

    @Test
    public void failedOuterProposalRollsBackAllBehaviorObjectsAndDoesNotSave() {
        Flow system = flow(1, 2), operational = flow(0, 2);
        for (AbstractFunction activity : operational.functions())
            behavior.involveFunction(capabilities[0], activity);
        domain.getCommandStack().flush();
        int count = count(), saveCount = saves;
        assertThrows(
                IllegalStateException.class,
                () ->
                        SessionSafeguards.runWrite(
                                session,
                                "Fail reviewed proposal",
                                () -> {
                                    behavior.linearChain(
                                            roots[1],
                                            "Must roll back",
                                            system.functions(),
                                            system.exchanges());
                                    behavior.operationalScenario(
                                            (OperationalCapability) capabilities[0],
                                            "Must roll back",
                                            operational.exchanges(),
                                            "Before",
                                            "After");
                                    behavior.realizeCapability(capabilities[1], capabilities[0]);
                                    throw new IllegalStateException("A later proposal step failed");
                                }));
        assertEquals(count, count());
        assertEquals(saveCount, saves);
        assertTrue(roots[1].getOwnedFunctionalChains().isEmpty());
        assertTrue(capabilities[0].getOwnedScenarios().isEmpty());
        assertTrue(capabilities[1].getOwnedAbstractCapabilityRealizations().isEmpty());
        assertFalse(domain.getCommandStack().canUndo());
    }

    @Test
    public void scenarioUndoRedoRestoresAllEventAndConditionRelationships() {
        Flow flow = flow(0, 3);
        for (AbstractFunction activity : flow.functions())
            behavior.involveFunction(capabilities[0], activity);
        Scenario scenario =
                behavior.operationalScenario(
                        (OperationalCapability) capabilities[0],
                        "Undo scenario",
                        flow.exchanges(),
                        "Before",
                        "After");
        domain.getCommandStack().undo();
        assertFalse(capabilities[0].getOwnedScenarios().contains(scenario));
        assertNull(scenario.eResource());
        domain.getCommandStack().redo();
        assertSame(capabilities[0], scenario.eContainer());
        assertScenario(scenario, flow, ScenarioKind.INTERACTION);
        assertSame(scenario, scenario.getPostCondition().getConstrainedElements().get(0));
    }

    @Test
    public void allGeneratedIdentitiesResolveImmediatelyAndAfterPersistence() throws Exception {
        Flow flow = flow(0, 3);
        for (AbstractFunction activity : flow.functions())
            behavior.involveFunction(capabilities[0], activity);
        Scenario scenario =
                behavior.operationalScenario(
                        (OperationalCapability) capabilities[0],
                        "Identity scenario",
                        flow.exchanges(),
                        "Before",
                        "After");
        List<EObject> objects = new ArrayList<>(List.of(scenario));
        var iterator = scenario.eAllContents();
        while (iterator.hasNext()) objects.add(iterator.next());
        var ids = new HashSet<String>();
        ResourceSet reloaded = reload();
        for (EObject object : objects)
            if (object instanceof CapellaElement element) {
                assertNotNull(element.getId());
                assertTrue(ids.add(element.getId()));
                assertSame(element, resource.getEObject(element.getId()));
                assertNotNull(ModelOps.findById(reloaded, element.getId()));
            }
        assertTrue(ids.size() > 20);
    }

    @Test
    public void rejectsDetachedForeignWrongLayerAndClosedSessionInputs() {
        AbstractFunction system = function(1, "System function");
        AbstractFunction logical = function(2, "Logical function");
        unchanged(() -> behavior.involveFunction(capabilities[1], logical));
        unchanged(
                () ->
                        behavior.involveFunction(
                                capabilities[1], CtxFactory.eINSTANCE.createSystemFunction()));
        ResourceSet foreign = resourceSet();
        Resource foreignResource = foreign.createResource(URI.createURI("memory:/foreign.xmi"));
        var foreignFunction = CtxFactory.eINSTANCE.createSystemFunction();
        foreignResource.getContents().add(foreignFunction);
        unchanged(() -> behavior.involveFunction(capabilities[1], foreignFunction));
        open = false;
        assertThrows(
                IllegalStateException.class,
                () -> behavior.involveFunction(capabilities[1], system));
    }

    @Test
    public void malformedExistingDuplicateInvolvementsAreRejectedWithoutAddingAnother() {
        AbstractFunction function = function(1, "Already duplicated");
        SessionSafeguards.runWrite(
                session,
                "Malformed fixture",
                () -> {
                    for (int index = 0; index < 2; index++) {
                        var link =
                                identified(
                                        InteractionFactory.eINSTANCE
                                                .createAbstractFunctionAbstractCapabilityInvolvement());
                        link.setInvolved(function);
                        capabilities[1]
                                .getOwnedAbstractFunctionAbstractCapabilityInvolvements()
                                .add(link);
                    }
                    return null;
                });
        unchanged(() -> behavior.involveFunction(capabilities[1], function));
        assertEquals(
                2, capabilities[1].getOwnedAbstractFunctionAbstractCapabilityInvolvements().size());
    }

    @Test
    public void noSessionFacadeRemainsConstructibleButBehaviorWritesAreRejected() {
        CapellaAPI noSession = new CapellaAPI(null);
        assertNotNull(noSession.arcadia);
        AbstractFunction function = function(1, "Captured function");
        int count = count(), saveCount = saves;
        assertThrows(
                IllegalStateException.class,
                () -> noSession.arcadia.involveFunction(capabilities[1], function));
        assertThrows(
                IllegalStateException.class,
                () ->
                        new ArcadiaBehaviorAPI(null)
                                .involveComponent(
                                        capabilities[0], OaFactory.eINSTANCE.createEntity()));
        assertEquals(count, count());
        assertEquals(saveCount, saves);
    }

    @Test
    public void packagedDecompositionIsRejectedAcrossAllLayersWithoutChangesSaveOrUndo() {
        String[] packageFeatures = {
            "ownedOperationalActivityPkgs",
            "ownedSystemFunctionPkgs",
            "ownedLogicalFunctionPkgs",
            "ownedPhysicalFunctionPkgs"
        };
        String[] functionFeatures = {
            "ownedOperationalActivities",
            "ownedSystemFunctions",
            "ownedLogicalFunctions",
            "ownedPhysicalFunctions"
        };
        for (int layer = 0; layer < 4; layer++) {
            Flow flow = flow(layer, 2);
            int selected = layer;
            if (layer == 0)
                for (AbstractFunction activity : flow.functions())
                    behavior.involveFunction(capabilities[0], activity);
            SessionSafeguards.runWrite(
                    session,
                    "Packaged decomposition fixture",
                    () -> {
                        EObject outer = newPackage(selected), inner = newPackage(selected);
                        AbstractFunction child = newFunction(selected);
                        add(flow.functions().get(0), packageFeatures[selected], outer);
                        add(outer, packageFeatures[selected], inner);
                        add(inner, functionFeatures[selected], child);
                        return null;
                    });
            assertTrue(flow.functions().get(0).getOwnedFunctions().isEmpty());
            if (Platform.isRunning())
                assertFalse(AbstractFunctionExt.isLeaf(flow.functions().get(0)));
            var lastCommand = domain.getCommandStack().getUndoCommand();
            if (layer == 0)
                unchanged(
                        () ->
                                behavior.operationalScenario(
                                        (OperationalCapability) capabilities[0],
                                        "Reject packaged OA decomposition",
                                        flow.exchanges(),
                                        "Before",
                                        "After"));
            else
                unchanged(
                        () ->
                                behavior.linearChain(
                                        roots[selected],
                                        "Reject packaged decomposition " + selected,
                                        flow.functions(),
                                        flow.exchanges()));
            assertSame(lastCommand, domain.getCommandStack().getUndoCommand());
        }
    }

    private static EObject newPackage(int layer) {
        return identified(
                switch (layer) {
                    case 0 -> OaFactory.eINSTANCE.createOperationalActivityPkg();
                    case 1 -> CtxFactory.eINSTANCE.createSystemFunctionPkg();
                    case 2 -> LaFactory.eINSTANCE.createLogicalFunctionPkg();
                    default -> PaFactory.eINSTANCE.createPhysicalFunctionPkg();
                });
    }

    private static AbstractFunction newFunction(int layer) {
        return identified(
                switch (layer) {
                    case 0 -> OaFactory.eINSTANCE.createOperationalActivity();
                    case 1 -> CtxFactory.eINSTANCE.createSystemFunction();
                    case 2 -> LaFactory.eINSTANCE.createLogicalFunction();
                    default -> PaFactory.eINSTANCE.createPhysicalFunction();
                });
    }

    private void assertChain(FunctionalChain chain, Flow flow) {
        assertEquals(FunctionalChainKind.SIMPLE, chain.getKind());
        assertEquals(
                flow.functions().size() + flow.exchanges().size(),
                chain.getOwnedFunctionalChainInvolvements().size());
        for (int index = 0; index < flow.functions().size(); index++) {
            assertSame(
                    flow.functions().get(index),
                    ((FunctionalChainInvolvementFunction)
                                    chain.getOwnedFunctionalChainInvolvements().get(index))
                            .getInvolved());
        }
        for (int index = 0; index < flow.exchanges().size(); index++) {
            var link =
                    (FunctionalChainInvolvementLink)
                            chain.getOwnedFunctionalChainInvolvements()
                                    .get(flow.functions().size() + index);
            assertSame(flow.exchanges().get(index), link.getInvolved());
            assertSame(chain.getOwnedFunctionalChainInvolvements().get(index), link.getSource());
            assertSame(
                    chain.getOwnedFunctionalChainInvolvements().get(index + 1), link.getTarget());
        }
    }

    private void assertScenario(Scenario scenario, Flow flow, ScenarioKind kind) {
        assertEquals(kind, scenario.getKind());
        assertEquals(flow.functions().size(), scenario.getOwnedInstanceRoles().size());
        assertEquals(flow.exchanges().size(), scenario.getOwnedMessages().size());
        assertEquals(flow.exchanges().size() * 3, scenario.getOwnedInteractionFragments().size());
        assertEquals(flow.exchanges().size() * 3, scenario.getOwnedEvents().size());
        assertEquals(flow.exchanges().size(), scenario.getOwnedTimeLapses().size());
        assertTrue(ScenarioExt.isFunctionalScenario(scenario));
        assertTrue(ScenarioExt.checkOrdering(scenario));
        for (int index = 0; index < flow.exchanges().size(); index++) {
            var message = scenario.getOwnedMessages().get(index);
            assertEquals(MessageKind.ASYNCHRONOUS_CALL, message.getKind());
            assertSame(flow.exchanges().get(index), operation(message));
            if (Platform.isRunning())
                assertSame(flow.exchanges().get(index), message.getInvokedOperation());
            assertSame(
                    flow.exchanges().get(index),
                    ((EventSentOperation) message.getSendingEnd().getEvent()).getOperation());
            assertSame(
                    flow.exchanges().get(index),
                    ((EventReceiptOperation) message.getReceivingEnd().getEvent()).getOperation());
            assertSame(
                    flow.functions().get(index),
                    message.getSendingEnd()
                            .getCoveredInstanceRoles()
                            .get(0)
                            .getRepresentedInstance());
            assertSame(
                    flow.functions().get(index + 1),
                    message.getReceivingEnd()
                            .getCoveredInstanceRoles()
                            .get(0)
                            .getRepresentedInstance());
            Execution execution = (Execution) scenario.getOwnedTimeLapses().get(index);
            assertSame(message.getReceivingEnd(), execution.getStart());
            assertTrue(execution.getFinish() instanceof ExecutionEnd);
            assertEquals(
                    index * 3 + 2,
                    scenario.getOwnedInteractionFragments().indexOf(execution.getFinish()));
        }
    }

    private AbstractFunction function(int layer, String name) {
        return (AbstractFunction) api.create(LAYERS[layer], FUNCTIONS[layer], name);
    }

    private static EObject operation(SequenceMessage message) {
        return ((EventSentOperation) message.getSendingEnd().getEvent()).getOperation();
    }

    private Flow flow(int layer, int size) {
        List<AbstractFunction> functions = new ArrayList<>();
        List<FunctionalExchange> exchanges = new ArrayList<>();
        for (int index = 0; index < size; index++)
            functions.add(function(layer, "Step " + layer + " " + index));
        for (int index = 0; index < size - 1; index++)
            exchanges.add(
                    (FunctionalExchange)
                            api.link(
                                    functions.get(index),
                                    functions.get(index + 1),
                                    "FunctionalExchange"));
        return new Flow(List.copyOf(functions), List.copyOf(exchanges));
    }

    private record Flow(List<AbstractFunction> functions, List<FunctionalExchange> exchanges) {}

    private void unchanged(Runnable operation) {
        int count = count(), saveCount = saves;
        assertThrows(IllegalArgumentException.class, operation::run);
        assertEquals(count, count());
        assertEquals(saveCount, saves);
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

    private ResourceSet reload() throws Exception {
        assertNotNull(saved);
        ResourceSet copy = resourceSet();
        Resource loaded = copy.createResource(resource.getURI());
        loaded.load(new ByteArrayInputStream(saved), Map.of());
        assertTrue(loaded.getErrors().toString(), loaded.getErrors().isEmpty());
        return copy;
    }

    private BlockArchitecture architecture(int index) {
        BlockArchitecture result;
        EObject functions, components, capabilityPkg;
        String functionFeature, componentFeature, capabilityFeature;
        switch (index) {
            case 0:
                result = OaFactory.eINSTANCE.createOperationalAnalysis();
                functions = OaFactory.eINSTANCE.createOperationalActivityPkg();
                components = OaFactory.eINSTANCE.createEntityPkg();
                roots[index] = OaFactory.eINSTANCE.createOperationalActivity();
                capabilityPkg = OaFactory.eINSTANCE.createOperationalCapabilityPkg();
                capabilities[index] = OaFactory.eINSTANCE.createOperationalCapability();
                functionFeature = "ownedOperationalActivities";
                componentFeature = "ownedEntityPkg";
                capabilityFeature = "ownedOperationalCapabilities";
                break;
            case 1:
                result = CtxFactory.eINSTANCE.createSystemAnalysis();
                functions = CtxFactory.eINSTANCE.createSystemFunctionPkg();
                components = CtxFactory.eINSTANCE.createSystemComponentPkg();
                roots[index] = CtxFactory.eINSTANCE.createSystemFunction();
                capabilityPkg = CtxFactory.eINSTANCE.createCapabilityPkg();
                capabilities[index] = CtxFactory.eINSTANCE.createCapability();
                functionFeature = "ownedSystemFunctions";
                componentFeature = "ownedSystemComponentPkg";
                capabilityFeature = "ownedCapabilities";
                break;
            case 2:
                result = LaFactory.eINSTANCE.createLogicalArchitecture();
                functions = LaFactory.eINSTANCE.createLogicalFunctionPkg();
                components = LaFactory.eINSTANCE.createLogicalComponentPkg();
                roots[index] = LaFactory.eINSTANCE.createLogicalFunction();
                capabilityPkg = LaFactory.eINSTANCE.createCapabilityRealizationPkg();
                capabilities[index] = LaFactory.eINSTANCE.createCapabilityRealization();
                functionFeature = "ownedLogicalFunctions";
                componentFeature = "ownedLogicalComponentPkg";
                capabilityFeature = "ownedCapabilityRealizations";
                break;
            default:
                result = PaFactory.eINSTANCE.createPhysicalArchitecture();
                functions = PaFactory.eINSTANCE.createPhysicalFunctionPkg();
                components = PaFactory.eINSTANCE.createPhysicalComponentPkg();
                roots[index] = PaFactory.eINSTANCE.createPhysicalFunction();
                capabilityPkg = LaFactory.eINSTANCE.createCapabilityRealizationPkg();
                capabilities[index] = LaFactory.eINSTANCE.createCapabilityRealization();
                functionFeature = "ownedPhysicalFunctions";
                componentFeature = "ownedPhysicalComponentPkg";
                capabilityFeature = "ownedCapabilityRealizations";
        }
        identified(result);
        identified((CapellaElement) functions);
        identified((CapellaElement) components);
        identified((CapellaElement) capabilityPkg);
        identified(roots[index]);
        roots[index].setName(LAYERS[index] + " root function");
        identified(capabilities[index]);
        capabilities[index].setName(LAYERS[index] + " capability");
        result.setName(LAYERS[index]);
        set(result, "ownedFunctionPkg", functions);
        set(result, componentFeature, components);
        set(result, "ownedAbstractCapabilityPkg", capabilityPkg);
        add(functions, functionFeature, roots[index]);
        add(capabilityPkg, capabilityFeature, capabilities[index]);
        return result;
    }

    private static <T extends CapellaElement> T identified(T element) {
        element.setId(UUID.randomUUID().toString());
        return element;
    }

    private static void set(EObject owner, String feature, EObject value) {
        owner.eSet(owner.eClass().getEStructuralFeature(feature), value);
    }

    @SuppressWarnings("unchecked")
    private static void add(EObject owner, String feature, EObject value) {
        ((List<EObject>) owner.eGet(owner.eClass().getEStructuralFeature(feature))).add(value);
    }

    private static ResourceSet resourceSet() {
        ResourceSet result = new ResourceSetImpl();
        result.getResourceFactoryRegistry()
                .getExtensionToFactoryMap()
                .put("xmi", new XMIResourceFactoryImpl());
        return result;
    }
}
