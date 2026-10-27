package com.sidkolapalli.capella.ai.model;

import static org.junit.Assert.*;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.sirius.business.api.session.Session;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import com.sidkolapalli.capella.ai.model.index.ModelIndex;
import com.sidkolapalli.capella.ai.scripting.CapellaAPI;
import com.sidkolapalli.capella.ai.session.SessionSafeguards;
import org.polarsys.capella.core.data.capellamodeller.CapellamodellerFactory;
import org.polarsys.capella.core.data.capellamodeller.Project;
import org.polarsys.capella.core.data.capellamodeller.SystemEngineering;
import org.polarsys.capella.core.data.cs.BlockArchitecture;
import org.polarsys.capella.core.data.cs.Component;
import org.polarsys.capella.core.data.cs.ComponentRealization;
import org.polarsys.capella.core.data.cs.Part;
import org.polarsys.capella.core.data.ctx.CtxFactory;
import org.polarsys.capella.core.data.epbs.ConfigurationItem;
import org.polarsys.capella.core.data.epbs.EPBSArchitecture;
import org.polarsys.capella.core.data.epbs.EpbsFactory;
import org.polarsys.capella.core.data.epbs.PhysicalArtifactRealization;
import org.polarsys.capella.core.data.fa.AbstractFunction;
import org.polarsys.capella.core.data.fa.ComponentExchange;
import org.polarsys.capella.core.data.fa.ComponentFunctionalAllocation;
import org.polarsys.capella.core.data.fa.ComponentPort;
import org.polarsys.capella.core.data.fa.FunctionInputPort;
import org.polarsys.capella.core.data.fa.FunctionOutputPort;
import org.polarsys.capella.core.data.fa.FunctionRealization;
import org.polarsys.capella.core.data.fa.FunctionalExchange;
import org.polarsys.capella.core.data.la.LaFactory;
import org.polarsys.capella.core.data.oa.CommunicationMean;
import org.polarsys.capella.core.data.oa.OaFactory;
import org.polarsys.capella.core.data.pa.PaFactory;
import org.polarsys.capella.core.data.pa.PhysicalComponent;
import org.polarsys.capella.core.model.handler.helpers.CapellaProjectHelper;
import org.polarsys.capella.core.model.handler.helpers.CapellaProjectHelper.ProjectApproach;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

/** Uses the real Capella metamodel and EMF transactions; only Sirius Session is a proxy. */
public class ArcadiaModelWorkflowTest {
    /**
     * Live eval: an agent's api.set(component, 'nature', 'NODE') failed with a ClassCastException.
     */
    @Test
    public void enumAttributesAcceptTheirLiteralNames() {
        EObject node = api.create("PA", "PhysicalComponent", "Vehicle Control Unit");
        assertTrue(api.set(node, "nature", "NODE"));
        assertEquals("NODE", String.valueOf(api.get(node, "nature")));
        assertTrue(api.set(node, "nature", "behavior"));
        assertEquals(
                "Literal names match case-insensitively",
                "BEHAVIOR",
                String.valueOf(api.get(node, "nature")));
        var invalid =
                assertThrows(
                        IllegalArgumentException.class, () -> api.set(node, "nature", "ROCKET"));
        assertTrue(
                invalid.getMessage(),
                invalid.getMessage().contains("NODE") && invalid.getMessage().contains("BEHAVIOR"));
    }

    /**
     * A second component beside the Logical System is a second root and a Capella validation error.
     */
    @Test
    public void defaultComponentCreationGoesUnderTheLayerRootComponentButActorsStayBeside() {
        var la = (org.polarsys.capella.core.data.la.LogicalArchitecture) api.layer("LA");
        var system = la.getOwnedLogicalComponentPkg().getOwnedLogicalComponents().get(0);
        assertEquals(
                List.of(system),
                List.copyOf(
                        org.polarsys.capella.core.model.helpers.BlockArchitectureExt
                                .getRootComponents(la)));
        EObject controller = api.create("LA", "LogicalComponent", "Battery Controller");
        assertSame(system, controller.eContainer());
        EObject driver = api.create("LA", "LogicalActor", "Driver");
        assertSame(la.getOwnedLogicalComponentPkg(), driver.eContainer());
        assertEquals(
                "Still one root component",
                List.of(system),
                List.copyOf(
                        org.polarsys.capella.core.model.helpers.BlockArchitectureExt
                                .getRootComponents(la)));
    }

    @Test
    public void facadePersistentIdChangeRemainsResolvableAfterUndoRedoAndReload() throws Exception {
        EObject function = api.create("LA", "LogicalFunction", "Stable identity function");
        String previous = api.id(function);
        assertTrue(api.set(function, "id", "stable-function-id"));
        assertSame(function, api.findById("stable-function-id"));
        assertNull(api.findById(previous));
        domain.getCommandStack().undo();
        assertSame(function, api.findById(previous));
        assertNull(api.findById("stable-function-id"));
        domain.getCommandStack().redo();
        save();
        assertNotNull(ModelOps.findById(reload(), "stable-function-id"));
        assertNull(ModelOps.findById(reload(), previous));
    }

    @Test
    public void nativeValidationRejectsAnUnresolvedResourceBeforeRunningConstraints() {
        resource.getErrors()
                .add(
                        new Resource.Diagnostic() {
                            public String getMessage() {
                                return "Unresolved reference to missing function";
                            }

                            public String getLocation() {
                                return resource.getURI().toString();
                            }

                            public int getLine() {
                                return 1;
                            }

                            public int getColumn() {
                                return 1;
                            }
                        });
        IllegalStateException failure =
                assertThrows(IllegalStateException.class, api::validateNative);
        assertTrue(failure.getMessage().contains("Unresolved reference"));
    }

    @Test
    public void nativeConstraintsUseConcreteCapellaClassifier() {
        assertSame(
                org.polarsys.capella.core.data.capellacore.CapellacorePackage.Literals.CONSTRAINT,
                ElementFactory.findEClass("Constraint"));
        assertFalse(ElementFactory.findEClass("Constraint").isAbstract());
    }

    @Test
    public void lookupsRefreshAfterAnotherFacadeChangesTheModelAndAfterUndoRedo() {
        EObject function = api.create("LA", "LogicalFunction", "Cached function");
        assertSame(function, api.find("LogicalFunction", "Cached function"));
        assertTrue(api.findAll("LogicalFunction").contains(function));
        CapellaAPI other = new CapellaAPI(session);
        assertTrue(other.rename(function, "Renamed cached function"));
        assertNull(api.find("LogicalFunction", "Cached function"));
        assertSame(function, api.find("LogicalFunction", "Renamed cached function"));
        assertTrue(other.delete(function));
        assertNull(api.find("LogicalFunction", "Renamed cached function"));
        assertFalse(api.findAll("LogicalFunction").contains(function));
        domain.getCommandStack().undo();
        assertSame(function, api.find("LogicalFunction", "Renamed cached function"));
        assertTrue(api.findAll("LogicalFunction").contains(function));
        domain.getCommandStack().redo();
        assertNull(api.find("LogicalFunction", "Renamed cached function"));
        assertFalse(api.findAll("LogicalFunction").contains(function));
    }

    private static final String[] LAYERS = {"OA", "SA", "LA", "PA"};
    private static final String[] FUNCTION_TYPES = {
        "OperationalActivity", "SystemFunction", "LogicalFunction", "PhysicalFunction"
    };
    private static final String[] COMPONENT_TYPES = {
        "Entity", "SystemComponent", "LogicalComponent", "PhysicalComponent"
    };

    private ResourceSet resources;
    private Resource resource;
    private TransactionalEditingDomain domain;
    private Session session;
    private CapellaAPI api;
    private final AbstractFunction[] rootFunctions = new AbstractFunction[4];
    private byte[] savedModel;

    @Before
    public void setUp() {
        resources = resourceSet();
        resource = resources.createResource(URI.createURI("memory:/workflow.xmi"));
        Project project = CapellamodellerFactory.eINSTANCE.createProject();
        CapellaProjectHelper.setProjectWithApproach(project, ProjectApproach.SingletonComponents);
        SystemEngineering engineering = CapellamodellerFactory.eINSTANCE.createSystemEngineering();
        project.getOwnedModelRoots().add(engineering);
        resource.getContents().add(project);
        for (int i = 0; i < LAYERS.length; i++) {
            BlockArchitecture architecture = createArchitecture(i);
            engineering.getOwnedArchitectures().add(architecture);
            architecture.setName(LAYERS[i]);
            rootFunctions[i].setName(LAYERS[i] + " root function");
        }
        EPBSArchitecture epbs = EpbsFactory.eINSTANCE.createEPBSArchitecture();
        epbs.setName("EPBS");
        epbs.setOwnedConfigurationItemPkg(EpbsFactory.eINSTANCE.createConfigurationItemPkg());
        engineering.getOwnedArchitectures().add(epbs);
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
                                                savedModel = output.toByteArray();
                                                yield null;
                                            }
                                            case "hashCode" -> System.identityHashCode(proxy);
                                            case "equals" -> proxy == args[0];
                                            case "toString" -> "Arcadia workflow test session";
                                            default -> null;
                                        });
        api = new CapellaAPI(session);
    }

    @After
    public void tearDown() {
        if (session != null) ModelSummary.invalidate(session);
        if (domain != null) domain.dispose();
    }

    @Test
    public void deletionRemovesObsoleteSiriusViewsAndNotationEdgesAndUndoRestoresThem() {
        EObject producer = api.create("LA", "LogicalComponent", "Visual producer");
        EObject consumer = api.create("LA", "LogicalComponent", "Visual consumer");
        EObject exchange = api.link(producer, consumer, "ComponentExchange");
        var diagram = org.eclipse.sirius.diagram.DiagramFactory.eINSTANCE.createDSemanticDiagram();
        var sourceView = org.eclipse.sirius.diagram.DiagramFactory.eINSTANCE.createDNode();
        var targetView = org.eclipse.sirius.diagram.DiagramFactory.eINSTANCE.createDNode();
        var edge = org.eclipse.sirius.diagram.DiagramFactory.eINSTANCE.createDEdge();
        var notation = org.eclipse.gmf.runtime.notation.NotationFactory.eINSTANCE.createDiagram();
        var sourceNode = org.eclipse.gmf.runtime.notation.NotationFactory.eINSTANCE.createNode();
        var targetNode = org.eclipse.gmf.runtime.notation.NotationFactory.eINSTANCE.createNode();
        var notationEdge = org.eclipse.gmf.runtime.notation.NotationFactory.eINSTANCE.createEdge();
        SessionSafeguards.runWrite(
                session,
                "Create diagram fixture",
                () -> {
                    Resource views =
                            resources.createResource(URI.createURI("memory:/deletion-views.xmi"));
                    diagram.setTarget(api.layer("LA"));
                    sourceView.setTarget(producer);
                    targetView.setTarget(consumer);
                    edge.setTarget(exchange);
                    edge.setSourceNode(sourceView);
                    edge.setTargetNode(targetView);
                    diagram.getOwnedDiagramElements().addAll(List.of(sourceView, targetView, edge));
                    views.getContents().add(diagram);
                    notation.setElement(diagram);
                    sourceNode.setElement(sourceView);
                    targetNode.setElement(targetView);
                    notationEdge.setElement(edge);
                    notationEdge.setSource(sourceNode);
                    notationEdge.setTarget(targetNode);
                    notation.getPersistedChildren().addAll(List.of(sourceNode, targetNode));
                    notation.getPersistedEdges().add(notationEdge);
                    views.getContents().add(notation);
                    return null;
                });
        domain.getCommandStack().flush();
        assertTrue(api.delete(producer));
        assertFalse(diagram.getOwnedDiagramElements().contains(sourceView));
        assertFalse(diagram.getOwnedDiagramElements().contains(edge));
        assertTrue(diagram.getOwnedDiagramElements().contains(targetView));
        assertFalse(notation.getPersistedChildren().contains(sourceNode));
        assertFalse(notation.getPersistedEdges().contains(notationEdge));
        assertTrue(notation.getPersistedChildren().contains(targetNode));
        domain.getCommandStack().undo();
        assertSame(producer, sourceView.getTarget());
        assertSame(exchange, edge.getTarget());
        assertSame(sourceView, edge.getSourceNode());
        assertSame(sourceNode, notationEdge.getSource());
        assertTrue(diagram.getOwnedDiagramElements().contains(sourceView));
        assertTrue(notation.getPersistedChildren().contains(sourceNode));
        assertTrue(notation.getPersistedEdges().contains(notationEdge));
        assertFalse(domain.getCommandStack().canUndo());
        domain.getCommandStack().redo();
        assertFalse(diagram.getOwnedDiagramElements().contains(sourceView));
        assertFalse(notation.getPersistedEdges().contains(notationEdge));
    }

    @Test
    public void createLinkAllocateRenameUndoAndReloadEveryLayer() throws Exception {
        for (int i = 0; i < LAYERS.length; i++) {
            AbstractFunction source =
                    (AbstractFunction)
                            api.create(LAYERS[i], FUNCTION_TYPES[i], "Acquire " + LAYERS[i]);
            AbstractFunction target =
                    (AbstractFunction)
                            api.create(LAYERS[i], FUNCTION_TYPES[i], "Process " + LAYERS[i]);
            assertSame(
                    "New functions belong under the layer root",
                    rootFunctions[i],
                    source.eContainer());
            assertEquals("ownedFunctions", source.eContainingFeature().getName());
            FunctionalExchange exchange =
                    (FunctionalExchange) api.link(source, target, "FunctionalExchange");
            assertSame(rootFunctions[i], exchange.eContainer());
            if (i == 0) {
                assertSame(source, exchange.getSource());
                assertSame(target, exchange.getTarget());
                assertTrue(source.getOutputs().isEmpty());
                assertTrue(target.getInputs().isEmpty());
            } else {
                assertTrue(exchange.getSource() instanceof FunctionOutputPort);
                assertTrue(exchange.getTarget() instanceof FunctionInputPort);
                assertSame(source, exchange.getSource().eContainer());
                assertSame(target, exchange.getTarget().eContainer());
            }

            EObject component = api.create(LAYERS[i], COMPONENT_TYPES[i], "Producer " + LAYERS[i]);
            EObject consumer = api.create(LAYERS[i], COMPONENT_TYPES[i], "Consumer " + LAYERS[i]);
            assertEquals(
                    "Components must use semantic containment",
                    switch (i) {
                        case 0 -> "ownedEntities";
                        case 1 -> "ownedSystemComponents";
                        case 2 -> "ownedLogicalComponents";
                        case 3 -> "ownedPhysicalComponents";
                        default -> throw new AssertionError(i);
                    },
                    component.eContainingFeature().getName());
            assertTrue(api.allocate(source, component));
            ComponentExchange componentExchange =
                    (ComponentExchange) api.link(component, consumer, "ComponentExchange");
            if (i == 0) {
                assertTrue(componentExchange instanceof CommunicationMean);
                assertSame(component, componentExchange.getSource());
                assertSame(consumer, componentExchange.getTarget());
            } else {
                assertTrue(componentExchange.getSource() instanceof ComponentPort);
                assertTrue(componentExchange.getTarget() instanceof ComponentPort);
                assertSame(component, componentExchange.getSource().eContainer());
                assertSame(consumer, componentExchange.getTarget().eContainer());
            }

            String oldName = source.getName();
            assertTrue(api.rename(source, "Renamed " + LAYERS[i]));
            assertTrue(domain.getCommandStack().canUndo());
            domain.getCommandStack().undo();
            assertEquals(oldName, source.getName());
            domain.getCommandStack().redo();
            assertEquals("Renamed " + LAYERS[i], source.getName());
            save();
            ResourceSet reloaded = reload();
            EObject reloadedExchange = ModelOps.findById(reloaded, exchange.getId());
            assertTrue(reloadedExchange instanceof FunctionalExchange);
            FunctionalExchange persisted = (FunctionalExchange) reloadedExchange;
            assertNotNull(persisted.getSource());
            assertNotNull(persisted.getTarget());
            assertFalse(persisted.getSource().eIsProxy());
            assertFalse(persisted.getTarget().eIsProxy());
            EObject persistedSource = ModelOps.findById(reloaded, source.getId());
            assertEquals("Renamed " + LAYERS[i], ((AbstractFunction) persistedSource).getName());
            EObject persistedComponent =
                    ModelOps.findById(
                            reloaded,
                            ((org.polarsys.capella.core.data.capellacore.CapellaElement) component)
                                    .getId());
            @SuppressWarnings("unchecked")
            List<ComponentFunctionalAllocation> persistedAllocations =
                    (List<ComponentFunctionalAllocation>)
                            persistedComponent.eGet(
                                    persistedComponent
                                            .eClass()
                                            .getEStructuralFeature("ownedFunctionalAllocation"));
            assertEquals(1, persistedAllocations.size());
            assertSame(persistedSource, persistedAllocations.get(0).getTargetElement());
            ComponentExchange persistedComponentExchange =
                    (ComponentExchange) ModelOps.findById(reloaded, componentExchange.getId());
            assertNotNull(persistedComponentExchange.getSource());
            assertNotNull(persistedComponentExchange.getTarget());
            assertFalse(persistedComponentExchange.getSource().eIsProxy());
            assertFalse(persistedComponentExchange.getTarget().eIsProxy());
        }
    }

    @Test
    public void realizationCreatesPersistentTraceObjectsBetweenLayers() throws Exception {
        EObject[] functions = new EObject[4];
        EObject[] components = new EObject[4];
        for (int i = 0; i < LAYERS.length; i++) {
            functions[i] = api.create(LAYERS[i], FUNCTION_TYPES[i], "Function " + LAYERS[i]);
            components[i] = api.create(LAYERS[i], COMPONENT_TYPES[i], "Component " + LAYERS[i]);
        }
        for (int i = 1; i < LAYERS.length; i++) {
            FunctionRealization functionTrace =
                    (FunctionRealization) api.link(functions[i], functions[i - 1], "realization");
            ComponentRealization componentTrace =
                    (ComponentRealization)
                            api.link(components[i], components[i - 1], "realization");
            assertSame(functions[i], functionTrace.eContainer());
            assertSame(functions[i - 1], functionTrace.getTargetElement());
            assertSame(components[i], componentTrace.eContainer());
            save();
            ResourceSet reloaded = reload();
            FunctionRealization persisted =
                    (FunctionRealization) ModelOps.findById(reloaded, functionTrace.getId());
            assertNotNull(persisted);
            assertNotNull(persisted.getTargetElement());
            assertFalse(persisted.getTargetElement().eIsProxy());
            ComponentRealization persistedComponent =
                    (ComponentRealization) ModelOps.findById(reloaded, componentTrace.getId());
            assertNotNull(persistedComponent.getTargetElement());
            assertFalse(persistedComponent.getTargetElement().eIsProxy());
        }
    }

    @Test
    public void exchangeUndoAlsoRemovesNewPorts() {
        AbstractFunction source = (AbstractFunction) api.create("LA", "LogicalFunction", "Source");
        AbstractFunction target = (AbstractFunction) api.create("LA", "LogicalFunction", "Target");
        FunctionalExchange exchange =
                (FunctionalExchange) api.link(source, target, "FunctionalExchange");
        domain.getCommandStack().undo();
        assertNotSame(resource, exchange.eResource());
        assertFalse(rootFunctions[2].getOwnedFunctionalExchanges().contains(exchange));
        assertTrue(source.getOutputs().isEmpty());
        assertTrue(target.getInputs().isEmpty());
        domain.getCommandStack().redo();
        assertSame(rootFunctions[2], exchange.eContainer());
        assertEquals(1, source.getOutputs().size());
        assertEquals(1, target.getInputs().size());
    }

    @Test
    public void deletingFunctionRemovesDependentLinksAndUndoRestoresThem() throws Exception {
        AbstractFunction source = (AbstractFunction) api.create("LA", "LogicalFunction", "Source");
        AbstractFunction target = (AbstractFunction) api.create("LA", "LogicalFunction", "Target");
        EObject component = api.create("LA", "LogicalComponent", "Producer");
        EObject realizingFunction = api.create("PA", "PhysicalFunction", "Realizing function");
        FunctionalExchange exchange =
                (FunctionalExchange) api.link(source, target, "FunctionalExchange");
        FunctionRealization trace =
                (FunctionRealization) api.link(realizingFunction, source, "FunctionRealization");
        assertTrue(api.allocate(source, component));
        @SuppressWarnings("unchecked")
        List<EObject> allocations =
                (List<EObject>)
                        component.eGet(
                                component
                                        .eClass()
                                        .getEStructuralFeature("ownedFunctionalAllocation"));
        EObject allocation = allocations.get(0);
        String sourceId = source.getId();
        String exchangeId = exchange.getId();
        String traceId = trace.getId();

        assertTrue(api.delete(source));
        assertNull(ModelOps.findById(resources, sourceId));
        assertFalse(rootFunctions[2].getOwnedFunctionalExchanges().contains(exchange));
        assertNotSame(resource, trace.eResource());
        assertTrue(allocations.isEmpty());
        save();
        ResourceSet reloaded = reload();
        assertNull(ModelOps.findById(reloaded, sourceId));
        assertNull(ModelOps.findById(reloaded, exchangeId));
        assertNull(ModelOps.findById(reloaded, traceId));

        domain.getCommandStack().undo();
        assertSame(rootFunctions[2], source.eContainer());
        assertSame(source, exchange.getSource().eContainer());
        assertSame(source, trace.getTargetElement());
        assertSame(allocation, allocations.get(0));
        save();
        assertNotNull(ModelOps.findById(reload(), sourceId));
    }

    @Test
    public void linksExistingPortsWithoutCreatingDuplicatePorts() {
        AbstractFunction source = (AbstractFunction) api.create("PA", "PhysicalFunction", "Source");
        AbstractFunction target = (AbstractFunction) api.create("PA", "PhysicalFunction", "Target");
        FunctionalExchange first =
                (FunctionalExchange) api.link(source, target, "FunctionalExchange");
        FunctionalExchange second =
                (FunctionalExchange)
                        api.link(first.getSource(), first.getTarget(), "FunctionalExchange");
        assertSame(first.getSource(), second.getSource());
        assertSame(first.getTarget(), second.getTarget());
        assertEquals(1, source.getOutputs().size());
        assertEquals(1, target.getInputs().size());
        assertThrows(
                IllegalArgumentException.class,
                () -> api.link(first.getTarget(), first.getSource(), "FunctionalExchange"));

        EObject producer = api.create("PA", "PhysicalComponent", "Producer");
        EObject consumer = api.create("PA", "PhysicalComponent", "Consumer");
        ComponentExchange firstComponent =
                (ComponentExchange) api.link(producer, consumer, "ComponentExchange");
        ComponentExchange secondComponent =
                (ComponentExchange)
                        api.link(
                                firstComponent.getSource(),
                                firstComponent.getTarget(),
                                "ComponentExchange");
        assertSame(firstComponent.getSource(), secondComponent.getSource());
        assertSame(firstComponent.getTarget(), secondComponent.getTarget());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        api.link(
                                firstComponent.getTarget(),
                                firstComponent.getSource(),
                                "ComponentExchange"));
    }

    @Test
    public void rejectsWrongTypesCrossLayerExchangesAndReversedRealizationsWithoutChanges() {
        EObject systemFunction = api.create("SA", "SystemFunction", "System function");
        EObject logicalFunction = api.create("LA", "LogicalFunction", "Logical function");
        EObject logicalComponent = api.create("LA", "LogicalComponent", "Logical component");
        int count = elementCount();
        assertThrows(
                IllegalArgumentException.class,
                () -> api.link(systemFunction, logicalFunction, "FunctionalExchange"));
        assertThrows(
                IllegalArgumentException.class,
                () -> api.link(logicalComponent, logicalFunction, "FunctionalExchange"));
        assertThrows(
                IllegalArgumentException.class,
                () -> api.link(systemFunction, logicalFunction, "FunctionRealization"));
        assertThrows(
                IllegalArgumentException.class,
                () -> api.link(logicalFunction, logicalComponent, "ComponentExchange"));
        assertThrows(
                IllegalArgumentException.class,
                () -> api.link(logicalFunction, logicalComponent, "Transition"));
        assertThrows(
                IllegalArgumentException.class,
                () -> api.create("LA", "SystemFunction", "Wrong layer"));
        assertThrows(
                IllegalArgumentException.class,
                () -> api.create("SystemFunction", "Wrong parent layer", rootFunctions[2]));
        assertEquals(count, elementCount());
    }

    @Test
    public void indexesEachArchitectureIndependentlyWithinOneProject() {
        for (int i = 0; i < LAYERS.length; i++) {
            EObject function = api.create(LAYERS[i], FUNCTION_TYPES[i], "Indexed " + LAYERS[i]);
            assertEquals(LAYERS[i], ModelIndex.describe(session, function).layer);
        }
        for (int i = 0; i < LAYERS.length; i++) {
            assertEquals(
                    LAYERS[i], ModelIndex.search(session, "Indexed " + LAYERS[i], 1).get(0).layer);
        }
    }

    @Test
    public void epbsConfigurationItemsRealizePhysicalArtifactsAndPersist() throws Exception {
        EPBSArchitecture architecture = (EPBSArchitecture) api.epbs();
        assertSame(architecture, api.root("EPBS"));
        assertSame(architecture, api.layer("end-product breakdown structure"));
        assertTrue(ArchitectureLocator.findLayerWithDiagnostic(session, "EPBS").success());
        assertSame(
                architecture.getOwnedConfigurationItemPkg(),
                api.container("EPBS", "configuration-item"));
        ConfigurationItem product = (ConfigurationItem) api.create("ConfigurationItem", "Product");
        ConfigurationItem software =
                (ConfigurationItem) api.create("ConfigurationItem", "Software", product);
        EObject nestedPackage = api.create("ConfigurationItemPkg", "Equipment", product);
        ConfigurationItem hardware =
                (ConfigurationItem) api.create("ConfigurationItem", "Hardware", nestedPackage);
        assertSame(architecture.getOwnedConfigurationItemPkg(), product.eContainer());
        assertEquals("ownedConfigurationItems", product.eContainingFeature().getName());
        assertSame(product, software.eContainer());
        assertEquals("ownedConfigurationItems", software.eContainingFeature().getName());
        assertSame(product, nestedPackage.eContainer());
        assertEquals("ownedConfigurationItemPkgs", nestedPackage.eContainingFeature().getName());
        assertSame(nestedPackage, hardware.eContainer());
        assertEquals("EPBS", ModelIndex.describe(session, software).layer);
        assertEquals("EPBS", ModelIndex.search(session, "Software", 1).get(0).layer);
        assertTrue(api.set(software, "itemIdentifier", "CI-001"));
        assertTrue(api.rename(software, "Flight software"));
        domain.getCommandStack().undo();
        assertEquals("Software", software.getName());
        domain.getCommandStack().redo();

        PhysicalComponent physical =
                (PhysicalComponent) api.create("PA", "PhysicalComponent", "Flight computer");
        PhysicalArtifactRealization trace =
                (PhysicalArtifactRealization)
                        api.link(software, physical, "PhysicalArtifactRealization");
        assertSame(software, trace.eContainer());
        assertSame(software, trace.getSourceElement());
        assertSame(physical, trace.getTargetElement());
        domain.getCommandStack().undo();
        assertTrue(software.getOwnedPhysicalArtifactRealizations().isEmpty());
        domain.getCommandStack().redo();
        PhysicalArtifactRealization generic =
                (PhysicalArtifactRealization) api.link(hardware, physical, "realization");
        assertSame(hardware, generic.getSourceElement());
        save();
        ResourceSet reloaded = reload();
        ConfigurationItem persisted =
                (ConfigurationItem) ModelOps.findById(reloaded, software.getId());
        assertEquals("Flight software", persisted.getName());
        assertEquals("CI-001", persisted.getItemIdentifier());
        PhysicalArtifactRealization persistedTrace =
                (PhysicalArtifactRealization) ModelOps.findById(reloaded, trace.getId());
        assertSame(persisted, persistedTrace.getSourceElement());
        assertNotNull(persistedTrace.getTargetElement());
        assertFalse(persistedTrace.getTargetElement().eIsProxy());
    }

    @Test
    public void rejectsUnsupportedEpbsOperationsWithoutChangingTheModel() {
        EObject item = api.create("EPBS", "ConfigurationItem", "Product");
        EObject physical = api.create("PA", "PhysicalComponent", "Computer");
        EObject logical = api.create("LA", "LogicalComponent", "Controller");
        int count = elementCount();
        assertThrows(
                IllegalArgumentException.class,
                () -> api.link(physical, item, "PhysicalArtifactRealization"));
        assertThrows(
                IllegalArgumentException.class,
                () -> api.link(item, logical, "PhysicalArtifactRealization"));
        assertThrows(IllegalArgumentException.class, () -> api.link(item, item, "realization"));
        assertThrows(
                IllegalArgumentException.class,
                () -> api.link(item, physical, "ComponentRealization"));
        assertThrows(
                IllegalArgumentException.class, () -> api.link(item, item, "ComponentExchange"));
        assertThrows(
                IllegalArgumentException.class,
                () -> api.create("EPBS", "PhysicalFunction", "Invalid function"));
        assertThrows(
                IllegalArgumentException.class,
                () -> api.create("PA", "ConfigurationItem", "Wrong layer"));
        assertThrows(IllegalArgumentException.class, () -> api.layer("invalid-layer"));
        assertEquals(count, elementCount());
    }

    @Test
    public void allocationsAreIdempotentAndRejectInvalidEndpoints() {
        EObject function = api.create("LA", "LogicalFunction", "Function");
        EObject component = api.create("LA", "LogicalComponent", "Controller");
        EObject systemFunction = api.create("SA", "SystemFunction", "System function");
        EObject item = api.create("EPBS", "ConfigurationItem", "Product");
        assertTrue(api.allocate(function, component));
        int count = elementCount();
        assertTrue(api.allocate(function, component));
        @SuppressWarnings("unchecked")
        List<EObject> allocations =
                (List<EObject>)
                        component.eGet(
                                component
                                        .eClass()
                                        .getEStructuralFeature("ownedFunctionalAllocation"));
        assertEquals(1, allocations.size());
        assertThrows(IllegalArgumentException.class, () -> api.allocate(systemFunction, component));
        assertThrows(IllegalArgumentException.class, () -> api.allocate(component, component));
        assertThrows(IllegalArgumentException.class, () -> api.allocate(function, item));
        assertThrows(
                IllegalArgumentException.class,
                () -> api.allocate(LaFactory.eINSTANCE.createLogicalFunction(), component));
        assertThrows(
                IllegalArgumentException.class,
                () -> api.allocate(function, LaFactory.eINSTANCE.createLogicalComponent()));
        ResourceSet foreignResources = resourceSet();
        Resource foreignResource =
                foreignResources.createResource(URI.createURI("memory:/foreign.xmi"));
        EObject foreignFunction = LaFactory.eINSTANCE.createLogicalFunction();
        foreignResource.getContents().add(foreignFunction);
        assertThrows(
                IllegalArgumentException.class, () -> api.allocate(foreignFunction, component));
        assertEquals(count, elementCount());
    }

    @Test
    public void ensurePartUsesSemanticFeaturesAndIsIdempotent() {
        EObject system = api.create("LA", "LogicalComponent", "Logical system");
        EObject child = api.create("LogicalComponent", "Child component", system);
        EObject part = api.ensurePart(child, system);
        assertNotNull(part);
        assertSame(system, part.eContainer());
        assertEquals("ownedFeatures", part.eContainingFeature().getName());
        assertSame(child, api.get(part, "abstractType"));
        assertSame(part, api.ensurePart(child, system));
    }

    @Test
    public void creatingComponentsAutomaticallyCreatesNativePartsInEveryPerspective()
            throws Exception {
        String[] layers = {"OA", "SA", "LA", "PA", "EPBS"};
        String[] types = {
            "Entity",
            "SystemComponent",
            "LogicalComponent",
            "PhysicalComponent",
            "ConfigurationItem"
        };
        for (int i = 0; i < layers.length; i++) {
            Component parent =
                    (Component)
                            api.create(
                                    layers[i],
                                    i == 1 ? "SystemActor" : types[i],
                                    "Parent " + layers[i]);
            assertEquals(1, storedPartsOf(parent).size());
            Part parentPart = storedPartsOf(parent).get(0);
            assertSame(parent.eContainer(), parentPart.eContainer());
            // LA/PA components are created inside the layer's root system, whose parts are owned
            // features.
            assertEquals(
                    i == 2 || i == 3 ? "ownedFeatures" : "ownedParts",
                    parentPart.eContainingFeature().getName());
            assertEquals(parent.getName(), parentPart.getName());
            assertSame(parent, parentPart.getAbstractType());
            assertSame(parentPart, api.ensurePart(parent));

            Component child = (Component) api.create(types[i], "Child " + layers[i], parent);
            assertEquals(1, storedPartsOf(child).size());
            Part childPart = storedPartsOf(child).get(0);
            assertSame(parent, childPart.eContainer());
            assertEquals("ownedFeatures", childPart.eContainingFeature().getName());
            assertEquals(child.getName(), childPart.getName());
            assertSame(child, childPart.getAbstractType());
            assertSame(childPart, api.ensurePart(child, parent));
            assertEquals(1, storedPartsOf(child).size());

            domain.getCommandStack().undo();
            assertNull(child.eResource());
            assertNull(childPart.eResource());
            assertFalse(parent.getOwnedFeatures().contains(childPart));
            domain.getCommandStack().redo();
            assertSame(parent, child.eContainer());
            assertSame(parent, childPart.eContainer());
            assertEquals(1, storedPartsOf(child).size());
            save();
            ResourceSet reloaded = reload();
            Part persistedPart = (Part) ModelOps.findById(reloaded, childPart.getId());
            Component persistedChild = (Component) ModelOps.findById(reloaded, child.getId());
            assertNotNull(persistedPart);
            assertSame(persistedChild, persistedPart.getAbstractType());
            assertEquals("ownedFeatures", persistedPart.eContainingFeature().getName());
            assertEquals(parent.getId(), api.id(persistedPart.eContainer()));
        }
    }

    @Test
    public void rejectsSystemAnalysisSystemDecompositionBeforeChangingTheModel() {
        EObject system = api.create("SA", "SystemComponent", "System black box");
        int count = elementCount();
        IllegalArgumentException componentFailure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> api.create("SystemComponent", "Invalid subsystem", system));
        assertTrue(componentFailure.getMessage().contains("black box"));
        assertThrows(
                IllegalArgumentException.class,
                () -> api.create("Part", "Invalid contained part", system));
        assertThrows(
                IllegalArgumentException.class,
                () -> api.create("SystemActor", "Invalid contained actor", system));
        assertEquals(count, elementCount());
        assertNull(api.find("SystemComponent", "Invalid subsystem"));
        assertNull(api.find("Part", "Invalid contained part"));

        Component actor = (Component) api.create("SA", "SystemActor", "External system");
        assertTrue(actor.isActor());
        assertSame(system.eContainer(), actor.eContainer());
        assertEquals(1, storedPartsOf(actor).size());
        assertTrue(((Component) system).getOwnedFeatures().isEmpty());
    }

    @Test
    public void systemActorAliasesPreserveActorFlagAndAtomicCreationInEveryOverload()
            throws Exception {
        for (int variant = 0; variant < 3; variant++) {
            EObject componentPackage = api.container("SA", "component");
            Component actor =
                    (Component)
                            switch (variant) {
                                case 0 -> api.create("SystemActor", "Implicit actor");
                                case 1 -> api.create("SA", "SystemActor", "Layer actor");
                                case 2 ->
                                        api.create("SystemActor", "Parent actor", componentPackage);
                                default -> throw new AssertionError(variant);
                            };
            assertTrue(actor.isActor());
            assertEquals("SystemComponent", actor.eClass().getName());
            assertSame(componentPackage, actor.eContainer());
            assertEquals(1, storedPartsOf(actor).size());
            Part part = storedPartsOf(actor).get(0);
            assertSame(componentPackage, part.eContainer());
            save();
            Component persistedActor = (Component) ModelOps.findById(reload(), actor.getId());
            assertTrue(persistedActor.isActor());

            domain.getCommandStack().undo();
            assertNull(actor.eResource());
            assertNull(part.eResource());
            assertNull(ModelOps.findById(resources, actor.getId()));
            domain.getCommandStack().redo();
            assertTrue(actor.isActor());
            assertSame(componentPackage, actor.eContainer());
            assertSame(componentPackage, part.eContainer());
            assertEquals(1, storedPartsOf(actor).size());
        }
    }

    @Test
    public void deletingRepresentedComponentRemovesItsPartAndRelationsAndUndoRestoresThem()
            throws Exception {
        EObject component = api.create("LA", "LogicalComponent", "Represented producer");
        EObject consumer = api.create("LA", "LogicalComponent", "Preserved consumer");
        EObject part = api.ensurePart(component);
        EObject consumerPart = api.ensurePart(consumer);
        EObject function = api.create("LA", "LogicalFunction", "Preserved function");
        EObject realizing = api.create("PA", "PhysicalComponent", "Realizing producer");
        ComponentExchange exchange =
                (ComponentExchange) api.link(component, consumer, "ComponentExchange");
        ComponentRealization realization =
                (ComponentRealization) api.link(realizing, component, "ComponentRealization");
        assertTrue(api.allocate(function, component));
        String componentId = api.id(component);
        String partId = api.id(part);
        String exchangeId = exchange.getId();
        String realizationId = realization.getId();
        EObject partOwner = part.eContainer();

        assertTrue(api.delete(component));
        assertNull(ModelOps.findById(resources, componentId));
        assertNull(ModelOps.findById(resources, partId));
        assertNull(ModelOps.findById(resources, exchangeId));
        assertNull(ModelOps.findById(resources, realizationId));
        assertSame(resource, consumerPart.eResource());
        assertSame(resource, function.eResource());
        save();
        ResourceSet reloaded = reload();
        assertNull(ModelOps.findById(reloaded, componentId));
        assertNull(ModelOps.findById(reloaded, partId));
        assertNotNull(ModelOps.findById(reloaded, api.id(consumerPart)));

        domain.getCommandStack().undo();
        assertSame(resource, component.eResource());
        assertSame(partOwner, part.eContainer());
        assertSame(component, api.get(part, "abstractType"));
        assertSame(component, exchange.getSource().eContainer());
        assertSame(component, realization.getTargetElement());
        ComponentFunctionalAllocation restoredAllocation =
                (ComponentFunctionalAllocation)
                        ((List<?>) api.get(component, "ownedFunctionalAllocation")).get(0);
        assertSame(function, restoredAllocation.getTargetElement());
    }

    private BlockArchitecture createArchitecture(int layer) {
        BlockArchitecture architecture;
        EObject functionPackage;
        EObject componentPackage;
        String functionsFeature;
        String componentsFeature;
        switch (layer) {
            case 0:
                architecture = OaFactory.eINSTANCE.createOperationalAnalysis();
                functionPackage = OaFactory.eINSTANCE.createOperationalActivityPkg();
                componentPackage = OaFactory.eINSTANCE.createEntityPkg();
                rootFunctions[layer] = OaFactory.eINSTANCE.createOperationalActivity();
                functionsFeature = "ownedOperationalActivities";
                componentsFeature = "ownedEntityPkg";
                break;
            case 1:
                architecture = CtxFactory.eINSTANCE.createSystemAnalysis();
                functionPackage = CtxFactory.eINSTANCE.createSystemFunctionPkg();
                componentPackage = CtxFactory.eINSTANCE.createSystemComponentPkg();
                rootFunctions[layer] = CtxFactory.eINSTANCE.createSystemFunction();
                functionsFeature = "ownedSystemFunctions";
                componentsFeature = "ownedSystemComponentPkg";
                break;
            case 2:
                architecture = LaFactory.eINSTANCE.createLogicalArchitecture();
                functionPackage = LaFactory.eINSTANCE.createLogicalFunctionPkg();
                componentPackage = LaFactory.eINSTANCE.createLogicalComponentPkg();
                rootFunctions[layer] = LaFactory.eINSTANCE.createLogicalFunction();
                functionsFeature = "ownedLogicalFunctions";
                componentsFeature = "ownedLogicalComponentPkg";
                break;
            case 3:
                architecture = PaFactory.eINSTANCE.createPhysicalArchitecture();
                functionPackage = PaFactory.eINSTANCE.createPhysicalFunctionPkg();
                componentPackage = PaFactory.eINSTANCE.createPhysicalComponentPkg();
                rootFunctions[layer] = PaFactory.eINSTANCE.createPhysicalFunction();
                functionsFeature = "ownedPhysicalFunctions";
                componentsFeature = "ownedPhysicalComponentPkg";
                break;
            default:
                throw new AssertionError(layer);
        }
        set(architecture, "ownedFunctionPkg", functionPackage);
        set(architecture, componentsFeature, componentPackage);
        add(functionPackage, functionsFeature, rootFunctions[layer]);
        // Capella projects start with a Logical/Physical System root component.
        if (layer == 2)
            add(
                    componentPackage,
                    "ownedLogicalComponents",
                    named(LaFactory.eINSTANCE.createLogicalComponent(), "Logical System"));
        if (layer == 3)
            add(
                    componentPackage,
                    "ownedPhysicalComponents",
                    named(PaFactory.eINSTANCE.createPhysicalComponent(), "Physical System"));
        return architecture;
    }

    private static <T extends org.polarsys.capella.core.data.capellacore.NamedElement> T named(
            T element, String name) {
        element.setName(name);
        return element;
    }

    private static ResourceSet resourceSet() {
        ResourceSet result = new ResourceSetImpl();
        result.getResourceFactoryRegistry()
                .getExtensionToFactoryMap()
                .put("xmi", new XMIResourceFactoryImpl());
        return result;
    }

    private void save() throws Exception {
        session.save(null);
        assertNotNull(savedModel);
    }

    private ResourceSet reload() throws Exception {
        ResourceSet reloaded = resourceSet();
        Resource copy = reloaded.createResource(resource.getURI());
        copy.load(new ByteArrayInputStream(savedModel), Map.of());
        assertTrue(copy.getErrors().toString(), copy.getErrors().isEmpty());
        return reloaded;
    }

    private int elementCount() {
        int count = 0;
        var contents = resource.getAllContents();
        while (contents.hasNext()) {
            contents.next();
            count++;
        }
        return count;
    }

    private static List<Part> storedPartsOf(Component component) {
        var parts = new java.util.ArrayList<Part>();
        var contents = component.eResource().getAllContents();
        while (contents.hasNext()) {
            EObject element = contents.next();
            if (element instanceof Part part && part.getAbstractType() == component)
                parts.add(part);
        }
        return parts;
    }

    private static void set(EObject object, String feature, EObject value) {
        EStructuralFeature reference = object.eClass().getEStructuralFeature(feature);
        assertNotNull(feature, reference);
        object.eSet(reference, value);
    }

    @SuppressWarnings("unchecked")
    private static void add(EObject object, String feature, EObject value) {
        EStructuralFeature reference = object.eClass().getEStructuralFeature(feature);
        assertNotNull(feature, reference);
        ((List<EObject>) object.eGet(reference)).add(value);
    }
}
