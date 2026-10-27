package com.sidkolapalli.capella.ai.scripting;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.sirius.business.api.session.Session;
import com.sidkolapalli.capella.ai.session.OperationInferencer;
import com.sidkolapalli.capella.ai.session.SessionSafeguards;
import org.polarsys.capella.common.data.activity.ActivityNode;
import org.polarsys.capella.core.data.capellacore.CapellaElement;
import org.polarsys.capella.core.data.cs.BlockArchitecture;
import org.polarsys.capella.core.data.cs.Component;
import org.polarsys.capella.core.data.cs.ComponentPkg;
import org.polarsys.capella.core.data.epbs.ConfigurationItem;
import org.polarsys.capella.core.data.epbs.EpbsFactory;
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
import org.polarsys.capella.core.data.oa.Entity;
import org.polarsys.capella.core.data.oa.OaFactory;
import org.polarsys.capella.core.data.oa.OperationalActivity;
import org.polarsys.capella.core.data.pa.PhysicalComponent;
import org.polarsys.capella.core.model.helpers.ComponentExchangeExt;
import org.polarsys.capella.core.model.helpers.FunctionalExchangeExt;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;

/**
 * Factory for creating relationships between Capella model elements. Supports dynamic linking based
 * on element types and relation names.
 *
 * <p>Relation types supported:
 *
 * <ul>
 *   <li>{@code FunctionalExchange} - Exchange between functions
 *   <li>{@code ComponentExchange} - Exchange between components
 *   <li>{@code allocation} - Function-to-component allocation
 *   <li>{@code involvement} - Activity involvement in capability
 *   <li>{@code realization} - Traceability between layers
 *   <li>{@code requirement} - Requirement linkage
 * </ul>
 */
public final class RelationFactory {

    private RelationFactory() {}

    /** Mapping of relation type names to their creation strategies. */
    private static final Map<String, RelationStrategy> STRATEGIES =
            Map.ofEntries(
                    Map.entry("functionalexchange", RelationFactory::createFunctionalExchange),
                    Map.entry("componentexchange", RelationFactory::createComponentExchange),
                    Map.entry("communicationmean", RelationFactory::createComponentExchange),
                    Map.entry(
                            "functionalexchangerealization",
                            (session, source, target, name) -> {
                                if (!(source instanceof FunctionalExchange lower)
                                        || !(target instanceof FunctionalExchange upper))
                                    throw new IllegalArgumentException(
                                            "FunctionalExchangeRealization requires two native"
                                                + " functional exchanges");
                                return new ArcadiaInterfaceAPI(session)
                                        .realizeFunctionalExchange(lower, upper);
                            }),
                    Map.entry(
                            "componentexchangerealization",
                            (session, source, target, name) -> {
                                if (!(source instanceof ComponentExchange lower)
                                        || !(target instanceof ComponentExchange upper))
                                    throw new IllegalArgumentException(
                                            "ComponentExchangeRealization requires two native"
                                                + " component exchanges");
                                return new ArcadiaInterfaceAPI(session)
                                        .realizeComponentExchange(lower, upper);
                            }),
                    Map.entry(
                            "portrealization",
                            (session, source, target, name) -> {
                                if (!(source
                                                instanceof
                                                org.polarsys.capella.core.data.information.Port
                                                                lower)
                                        || !(target
                                                instanceof
                                                org.polarsys.capella.core.data.information.Port
                                                                upper))
                                    throw new IllegalArgumentException(
                                            "PortRealization requires two native interface ports");
                                return new ArcadiaInterfaceAPI(session).realizePort(lower, upper);
                            }),
                    Map.entry("allocation", RelationFactory::createAllocation),
                    Map.entry("involvement", RelationFactory::createInvolvement),
                    Map.entry("realization", RelationFactory::createRealization),
                    Map.entry("trace", RelationFactory::createRealization),
                    Map.entry("functionrealization", RelationFactory::createFunctionRealization),
                    Map.entry("componentrealization", RelationFactory::createComponentRealization),
                    Map.entry(
                            "physicalartifactrealization",
                            RelationFactory::createPhysicalArtifactRealization),
                    Map.entry("requirement", RelationFactory::createRequirementLink),
                    Map.entry("reference", RelationFactory::createGenericReference));

    /**
     * Create a relationship between source and target elements.
     *
     * @param session the Capella session
     * @param source the source element
     * @param target the target element
     * @param relationType the type of relationship to create
     * @return the created relationship element
     * @throws IllegalStateException if no active session
     * @throws IllegalArgumentException if source/target is null or link creation fails
     */
    public static EObject link(
            Session session, EObject source, EObject target, String relationType) {
        if (session == null) {
            throw new IllegalStateException("No active Capella session for linking");
        }
        if (source == null) {
            throw new IllegalArgumentException("Link source element cannot be null");
        }
        if (target == null) {
            throw new IllegalArgumentException("Link target element cannot be null");
        }
        if (relationType == null || relationType.isBlank()) {
            relationType = "reference";
        }

        String normalizedType =
                relationType.trim().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        RelationStrategy strategy = STRATEGIES.get(normalizedType);

        EObject result;
        if (strategy != null) {
            result = strategy.create(session, source, target, relationType);
        } else {
            // Fallback: try to find matching reference by name
            result = createGenericReference(session, source, target, relationType);
        }

        if (result == null) {
            throw new IllegalArgumentException(
                    "Cannot create '"
                            + relationType
                            + "' link from "
                            + source.eClass().getName()
                            + " to "
                            + target.eClass().getName()
                            + ". Supported relation types include FunctionalExchange,"
                            + " ComponentExchange, CommunicationMean, FunctionRealization,"
                            + " ComponentRealization, FunctionalExchangeRealization,"
                            + " ComponentExchangeRealization, PortRealization,"
                            + " PhysicalArtifactRealization, realization, reference. For function"
                            + " allocation, use api.allocate(function, component).");
        }
        return result;
    }

    /** Create a FunctionalExchange between two functions. */
    private static EObject createFunctionalExchange(
            Session session, EObject source, EObject target, String name) {
        AbstractFunction sourceFunction = endpointFunction(source, true);
        AbstractFunction targetFunction = endpointFunction(target, false);
        requireSameArchitecture(session, sourceFunction, targetFunction);
        AbstractFunction container =
                FunctionalExchangeExt.getDefaultContainer(sourceFunction, targetFunction);
        if (container == null) {
            throw new IllegalArgumentException(
                    "Functions require a common root function before creating an exchange");
        }
        return SessionSafeguards.runWrite(
                session,
                "Create FunctionalExchange",
                () -> {
                    ActivityNode sourceNode = (ActivityNode) source;
                    ActivityNode targetNode = (ActivityNode) target;
                    // OA exchanges connect activities directly. Other layers use
                    // output/input ports, matching Capella's native creation tool.
                    if (source instanceof AbstractFunction
                            && !(source instanceof OperationalActivity)) {
                        FunctionOutputPort port = FaFactory.eINSTANCE.createFunctionOutputPort();
                        sourceFunction.getOutputs().add(port);
                        sourceNode = port;
                    }
                    if (target instanceof AbstractFunction
                            && !(target instanceof OperationalActivity)) {
                        FunctionInputPort port = FaFactory.eINSTANCE.createFunctionInputPort();
                        targetFunction.getInputs().add(port);
                        targetNode = port;
                    }
                    FunctionalExchange exchange = FaFactory.eINSTANCE.createFunctionalExchange();
                    exchange.setName(exchangeName(name, "FunctionalExchange"));
                    exchange.setSource(sourceNode);
                    exchange.setTarget(targetNode);
                    container.getOwnedFunctionalExchanges().add(exchange);
                    OperationInferencer.recordLink(
                            source.eClass().getName(),
                            target.eClass().getName(),
                            "FunctionalExchange");
                    return exchange;
                });
    }

    /** Create a ComponentExchange between two components. */
    private static EObject createComponentExchange(
            Session session, EObject source, EObject target, String name) {
        requireSameArchitecture(session, source, target);
        if (source instanceof Entity sourceEntity && target instanceof Entity targetEntity) {
            return SessionSafeguards.runWrite(
                    session,
                    "Create CommunicationMean",
                    () -> {
                        var exchange = OaFactory.eINSTANCE.createCommunicationMean();
                        exchange.setName(exchangeName(name, "ComponentExchange"));
                        exchange.setSource(sourceEntity);
                        exchange.setTarget(targetEntity);
                        sourceEntity.getOwnedCommunicationMeans().add(exchange);
                        OperationInferencer.recordLink("Entity", "Entity", "CommunicationMean");
                        return exchange;
                    });
        }
        Component sourceComponent = endpointComponent(source, true);
        Component targetComponent = endpointComponent(target, false);
        CapellaElement container = componentExchangeContainer(sourceComponent, targetComponent);
        if (container == null) {
            throw new IllegalArgumentException(
                    "Components require a common component or package before creating an exchange");
        }
        return SessionSafeguards.runWrite(
                session,
                "Create ComponentExchange",
                () -> {
                    ComponentPort sourcePort = componentPort(source, sourceComponent, true);
                    ComponentPort targetPort = componentPort(target, targetComponent, false);
                    ComponentExchange exchange = FaFactory.eINSTANCE.createComponentExchange();
                    exchange.setName(exchangeName(name, "ComponentExchange"));
                    exchange.setKind(ComponentExchangeKind.FLOW);
                    exchange.setSource(sourcePort);
                    exchange.setTarget(targetPort);
                    if (!ComponentExchangeExt.attachTo(exchange, container)) {
                        throw new IllegalArgumentException(
                                "Cannot attach component exchange to "
                                        + container.eClass().getName());
                    }
                    OperationInferencer.recordLink(
                            source.eClass().getName(),
                            target.eClass().getName(),
                            "ComponentExchange");
                    return exchange;
                });
    }

    /** Create an allocation relationship (e.g., function to component). */
    private static EObject createAllocation(
            Session session, EObject source, EObject target, String name) {
        return SessionSafeguards.runWrite(
                session,
                "Create allocation",
                () -> {
                    boolean linked =
                            addReference(source, target, "allocated", "allocation", "function");
                    if (!linked) {
                        // Try reverse direction
                        linked =
                                addReference(
                                        target, source, "allocating", "allocation", "component");
                    }
                    if (linked) {
                        OperationInferencer.recordAllocate(
                                source.eClass().getName(), target.eClass().getName());
                        return source;
                    }
                    return null;
                });
    }

    /** Create an involvement relationship (e.g., activity to capability). */
    private static EObject createInvolvement(
            Session session, EObject source, EObject target, String name) {
        return SessionSafeguards.runWrite(
                session,
                "Create involvement",
                () -> {
                    // Try to find involvement reference on either side
                    boolean linked =
                            addReference(target, source, "involved", "involvement", "activity");
                    if (!linked) {
                        linked = addReference(source, target, "capability", "involvement");
                    }
                    if (linked) {
                        OperationInferencer.recordLink(
                                source.eClass().getName(),
                                target.eClass().getName(),
                                "Involvement");
                        return source;
                    }
                    return null;
                });
    }

    /** Create a realization/trace relationship between layers. */
    private static EObject createRealization(
            Session session, EObject source, EObject target, String name) {
        if (source instanceof ConfigurationItem && target instanceof PhysicalComponent) {
            return createPhysicalArtifactRealization(session, source, target, name);
        }
        if (source instanceof AbstractFunction && target instanceof AbstractFunction) {
            return createFunctionRealization(session, source, target, name);
        }
        if (source instanceof Component
                && (target instanceof Component || target instanceof Entity)) {
            return createComponentRealization(session, source, target, name);
        }
        throw new IllegalArgumentException(
                "Realization supports functions and components; use a specific relation type for"
                    + " other elements");
    }

    /**
     * Create a FunctionRealization between functions across layers. Source is the lower-level
     * function (SA/LA/PA), target is the higher-level function (OA/SA/LA).
     */
    private static EObject createFunctionRealization(
            Session session, EObject source, EObject target, String name) {
        if (!(source instanceof AbstractFunction) || !(target instanceof AbstractFunction)) {
            throw new IllegalArgumentException(
                    "FunctionRealization requires two functions or operational activities");
        }
        requireRealizationLayers(session, source, target);
        return SessionSafeguards.runWrite(
                session,
                "Create FunctionRealization",
                () -> {
                    try {
                        var faFactory = org.polarsys.capella.core.data.fa.FaFactory.eINSTANCE;
                        var realization = faFactory.createFunctionRealization();
                        realization.setTargetElement(
                                (org.polarsys.capella.common.data.modellingcore.TraceableElement)
                                        target);
                        realization.setSourceElement(
                                (org.polarsys.capella.common.data.modellingcore.TraceableElement)
                                        source);

                        // Add to source function's ownedFunctionRealizations
                        var realizationsRef =
                                source.eClass().getEStructuralFeature("ownedFunctionRealizations");
                        if (realizationsRef != null) {
                            @SuppressWarnings("unchecked")
                            var list = (java.util.List<EObject>) source.eGet(realizationsRef);
                            list.add(realization);
                            OperationInferencer.recordLink(
                                    source.eClass().getName(),
                                    target.eClass().getName(),
                                    "FunctionRealization");
                            return realization;
                        }
                        throw new IllegalArgumentException(
                                "Cannot create FunctionRealization: source "
                                        + source.eClass().getName()
                                        + " does not have ownedFunctionRealizations feature");
                    } catch (ClassCastException e) {
                        throw new IllegalArgumentException(
                                "Cannot create FunctionRealization between "
                                        + source.eClass().getName()
                                        + " and "
                                        + target.eClass().getName()
                                        + ": elements must be TraceableElement types. "
                                        + e.getMessage());
                    } catch (Exception e) {
                        throw new RuntimeException(
                                "Failed to create FunctionRealization: " + e.getMessage(), e);
                    }
                });
    }

    /**
     * Create a ComponentRealization between components across layers. Source is the lower-level
     * component (SA/LA/PA), target is the higher-level entity/component (OA/SA/LA).
     */
    private static EObject createComponentRealization(
            Session session, EObject source, EObject target, String name) {
        if (source instanceof ConfigurationItem || target instanceof ConfigurationItem) {
            throw new IllegalArgumentException(
                    "EPBS configuration items realize PA physical components through"
                        + " PhysicalArtifactRealization");
        }
        if (!(source instanceof Component)
                || !(target instanceof Component || target instanceof Entity)) {
            throw new IllegalArgumentException(
                    "ComponentRealization requires a component realizing a component or operational"
                        + " entity");
        }
        requireRealizationLayers(session, source, target);
        return SessionSafeguards.runWrite(
                session,
                "Create ComponentRealization",
                () -> {
                    try {
                        var csFactory = org.polarsys.capella.core.data.cs.CsFactory.eINSTANCE;
                        var realization = csFactory.createComponentRealization();
                        realization.setTargetElement(
                                (org.polarsys.capella.common.data.modellingcore.TraceableElement)
                                        target);
                        realization.setSourceElement(
                                (org.polarsys.capella.common.data.modellingcore.TraceableElement)
                                        source);

                        // Add to source component's ownedComponentRealizations
                        var realizationsRef =
                                source.eClass().getEStructuralFeature("ownedComponentRealizations");
                        if (realizationsRef != null) {
                            @SuppressWarnings("unchecked")
                            var list = (java.util.List<EObject>) source.eGet(realizationsRef);
                            list.add(realization);
                            OperationInferencer.recordLink(
                                    source.eClass().getName(),
                                    target.eClass().getName(),
                                    "ComponentRealization");
                            return realization;
                        }
                        throw new IllegalArgumentException(
                                "Cannot create ComponentRealization: source "
                                        + source.eClass().getName()
                                        + " does not have ownedComponentRealizations feature");
                    } catch (ClassCastException e) {
                        throw new IllegalArgumentException(
                                "Cannot create ComponentRealization between "
                                        + source.eClass().getName()
                                        + " and "
                                        + target.eClass().getName()
                                        + ": elements must be TraceableElement types. "
                                        + e.getMessage());
                    } catch (Exception e) {
                        throw new RuntimeException(
                                "Failed to create ComponentRealization: " + e.getMessage(), e);
                    }
                });
    }

    /** A configuration item realizes a PA physical artifact through a native EPBS allocation. */
    private static EObject createPhysicalArtifactRealization(
            Session session, EObject source, EObject target, String name) {
        if (!(source instanceof ConfigurationItem item)
                || !(target instanceof PhysicalComponent component)) {
            throw new IllegalArgumentException(
                    "PhysicalArtifactRealization requires an EPBS ConfigurationItem source and a PA"
                        + " PhysicalComponent target");
        }
        if (!"EPBSArchitecture".equals(endpointArchitecture(session, item).eClass().getName())
                || !"PhysicalArchitecture"
                        .equals(endpointArchitecture(session, component).eClass().getName())) {
            throw new IllegalArgumentException(
                    "PhysicalArtifactRealization connects EPBS configuration items to PA physical"
                        + " components");
        }
        return SessionSafeguards.runWrite(
                session,
                "Create PhysicalArtifactRealization",
                () -> {
                    var realization = EpbsFactory.eINSTANCE.createPhysicalArtifactRealization();
                    realization.setSourceElement(item);
                    realization.setTargetElement(component);
                    item.getOwnedPhysicalArtifactRealizations().add(realization);
                    OperationInferencer.recordLink(
                            "ConfigurationItem",
                            "PhysicalComponent",
                            "PhysicalArtifactRealization");
                    return realization;
                });
    }

    /** Create a requirement link. */
    private static EObject createRequirementLink(
            Session session, EObject source, EObject target, String name) {
        return SessionSafeguards.runWrite(
                session,
                "Create requirement link",
                () -> {
                    boolean linked =
                            addReference(source, target, "requirement", "satisfy", "trace");
                    if (!linked) {
                        linked = addReference(target, source, "element", "satisfy");
                    }
                    if (linked) {
                        OperationInferencer.recordLink(
                                source.eClass().getName(),
                                target.eClass().getName(),
                                "Requirement");
                        return source;
                    }
                    return null;
                });
    }

    /** Create a generic reference by finding a matching feature. */
    private static EObject createGenericReference(
            Session session, EObject source, EObject target, String hintName) {
        return SessionSafeguards.runWrite(
                session,
                "Create reference",
                () -> {
                    // First try with the hint name
                    if (hintName != null && !hintName.isBlank()) {
                        boolean linked =
                                addReference(source, target, hintName.toLowerCase(Locale.ROOT));
                        if (linked) {
                            OperationInferencer.recordLink(
                                    source.eClass().getName(),
                                    target.eClass().getName(),
                                    "Reference");
                            return source;
                        }
                        if (!"reference".equalsIgnoreCase(hintName)) {
                            return null;
                        }
                    }

                    // Try to find any compatible reference
                    for (EStructuralFeature feature : source.eClass().getEAllStructuralFeatures()) {
                        if (!(feature instanceof EReference ref)
                                || ref.isContainment()
                                || !ref.isChangeable()
                                || ref.isDerived()
                                || ref.isTransient()) {
                            continue;
                        }
                        if (ref.getEReferenceType().isInstance(target)) {
                            Object current = source.eGet(ref);
                            if (ref.isMany()) {
                                @SuppressWarnings("unchecked")
                                Collection<Object> list = (Collection<Object>) current;
                                if (!list.contains(target)) {
                                    list.add(target);
                                }
                            } else if (current == null) {
                                source.eSet(ref, target);
                            } else {
                                continue; // Already set, try next
                            }
                            OperationInferencer.recordLink(
                                    source.eClass().getName(),
                                    target.eClass().getName(),
                                    "Reference");
                            return source;
                        }
                    }
                    return null;
                });
    }

    // ========== Helper Methods ==========

    private static String exchangeName(String name, String type) {
        return name == null || name.isBlank() || name.equalsIgnoreCase(type) ? "Exchange" : name;
    }

    private static AbstractFunction endpointFunction(EObject endpoint, boolean source) {
        if (endpoint instanceof AbstractFunction function) return function;
        if ((source && endpoint instanceof FunctionOutputPort)
                || (!source && endpoint instanceof FunctionInputPort)) {
            if (endpoint.eContainer() instanceof AbstractFunction function) return function;
        }
        throw new IllegalArgumentException(
                "FunctionalExchange "
                        + (source ? "source" : "target")
                        + " must be a function/activity or an attached "
                        + (source ? "output" : "input")
                        + " function port");
    }

    private static Component endpointComponent(EObject endpoint, boolean source) {
        if (endpoint instanceof Component component) return component;
        if (endpoint instanceof ComponentPort port
                && port.eContainer() instanceof Component component) {
            if ((source && port.getOrientation() == OrientationPortKind.IN)
                    || (!source && port.getOrientation() == OrientationPortKind.OUT)) {
                throw new IllegalArgumentException(
                        "Component port orientation is incompatible with the exchange direction");
            }
            return component;
        }
        throw new IllegalArgumentException(
                "ComponentExchange endpoints must be components, attached component ports, or two"
                    + " OA entities");
    }

    private static ComponentPort componentPort(
            EObject endpoint, Component component, boolean source) {
        if (endpoint instanceof ComponentPort port) return port;
        ComponentPort port = FaFactory.eINSTANCE.createComponentPort();
        port.setKind(ComponentPortKind.FLOW);
        port.setOrientation(source ? OrientationPortKind.OUT : OrientationPortKind.IN);
        component.getOwnedFeatures().add(port);
        return port;
    }

    private static CapellaElement componentExchangeContainer(Component source, Component target) {
        // These endpoints are component types or their ports. Their structural
        // common ancestor avoids arbitrarily picking a representing Part when
        // a component has multiple instances in the architecture.
        for (EObject ancestor = source; ancestor != null; ancestor = ancestor.eContainer()) {
            if ((ancestor instanceof Component || ancestor instanceof ComponentPkg)
                    && EcoreUtil.isAncestor(ancestor, target)) {
                return (CapellaElement) ancestor;
            }
        }
        return null;
    }

    private static BlockArchitecture endpointArchitecture(Session session, EObject endpoint) {
        if (session.getTransactionalEditingDomain() == null
                || endpoint.eResource() == null
                || endpoint.eResource().getResourceSet()
                        != session.getTransactionalEditingDomain().getResourceSet()) {
            throw new IllegalArgumentException(
                    "Relationship endpoints must belong to the active session");
        }
        for (EObject ancestor = endpoint; ancestor != null; ancestor = ancestor.eContainer()) {
            if (ancestor instanceof BlockArchitecture architecture) return architecture;
        }
        throw new IllegalArgumentException(
                "Relationship endpoint is outside an OA/SA/LA/PA architecture");
    }

    private static void requireSameArchitecture(Session session, EObject source, EObject target) {
        BlockArchitecture sourceArchitecture = endpointArchitecture(session, source);
        if (sourceArchitecture != endpointArchitecture(session, target)) {
            throw new IllegalArgumentException(
                    "Exchanges must connect elements in the same architecture; use realization for"
                        + " cross-layer links");
        }
        if ("EPBSArchitecture".equals(sourceArchitecture.eClass().getName())) {
            throw new IllegalArgumentException(
                    "EPBS supports configuration items and physical artifact realizations;"
                        + " functional and component exchanges are unavailable");
        }
    }

    private static void requireRealizationLayers(Session session, EObject source, EObject target) {
        int sourceLayer = architectureLevel(endpointArchitecture(session, source));
        int targetLayer = architectureLevel(endpointArchitecture(session, target));
        if (sourceLayer <= targetLayer) {
            throw new IllegalArgumentException(
                    "Realization source must be in a lower-level architecture than its target (SA"
                        + " realizes OA, LA realizes SA, PA realizes LA)");
        }
    }

    private static int architectureLevel(BlockArchitecture architecture) {
        return switch (architecture.eClass().getName()) {
            case "OperationalAnalysis" -> 0;
            case "SystemAnalysis" -> 1;
            case "LogicalArchitecture" -> 2;
            case "PhysicalArchitecture" -> 3;
            case "EPBSArchitecture" -> 4;
            default ->
                    throw new IllegalArgumentException(
                            "Unsupported architecture " + architecture.eClass().getName());
        };
    }

    /** Add a reference from source to target using keyword matching. */
    private static boolean addReference(EObject source, EObject target, String... keywords) {
        for (EStructuralFeature feature : source.eClass().getEAllStructuralFeatures()) {
            if (!(feature instanceof EReference ref)
                    || ref.isContainment()
                    || !ref.isChangeable()
                    || ref.isDerived()
                    || ref.isTransient()) {
                continue;
            }
            if (!ref.getEReferenceType().isInstance(target)) {
                continue;
            }

            String name = feature.getName().toLowerCase(Locale.ROOT);
            boolean matches = keywords.length == 0;
            for (String keyword : keywords) {
                if (name.contains(keyword.toLowerCase(Locale.ROOT))) {
                    matches = true;
                    break;
                }
            }
            if (!matches) {
                continue;
            }

            Object current = source.eGet(ref);
            if (ref.isMany()) {
                @SuppressWarnings("unchecked")
                Collection<Object> list = (Collection<Object>) current;
                if (!list.contains(target)) {
                    list.add(target);
                }
                return true;
            }
            source.eSet(ref, target);
            return true;
        }
        return false;
    }

    /** Functional interface for relation creation strategies. */
    @FunctionalInterface
    private interface RelationStrategy {
        EObject create(Session session, EObject source, EObject target, String name);
    }
}
