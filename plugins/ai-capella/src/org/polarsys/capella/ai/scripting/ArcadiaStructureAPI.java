package org.polarsys.capella.ai.scripting;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.model.ModelSummary;
import org.polarsys.capella.ai.session.SessionSafeguards;
import org.polarsys.capella.core.data.capellacore.CapellaElement;
import org.polarsys.capella.core.data.cs.AbstractPathInvolvedElement;
import org.polarsys.capella.core.data.cs.BlockArchitecture;
import org.polarsys.capella.core.data.cs.Component;
import org.polarsys.capella.core.data.cs.ComponentPkg;
import org.polarsys.capella.core.data.cs.CsFactory;
import org.polarsys.capella.core.data.cs.Part;
import org.polarsys.capella.core.data.cs.PhysicalLink;
import org.polarsys.capella.core.data.cs.PhysicalLinkEnd;
import org.polarsys.capella.core.data.cs.PhysicalPath;
import org.polarsys.capella.core.data.cs.PhysicalPathInvolvement;
import org.polarsys.capella.core.data.cs.PhysicalPort;
import org.polarsys.capella.core.data.fa.AbstractFunction;
import org.polarsys.capella.core.data.fa.ComponentExchange;
import org.polarsys.capella.core.data.fa.ComponentExchangeAllocation;
import org.polarsys.capella.core.data.fa.ComponentExchangeAllocator;
import org.polarsys.capella.core.data.fa.ComponentExchangeEnd;
import org.polarsys.capella.core.data.fa.ComponentExchangeFunctionalExchangeAllocation;
import org.polarsys.capella.core.data.fa.ComponentExchangeKind;
import org.polarsys.capella.core.data.fa.ComponentFunctionalAllocation;
import org.polarsys.capella.core.data.fa.ComponentPort;
import org.polarsys.capella.core.data.fa.ComponentPortKind;
import org.polarsys.capella.core.data.fa.FaFactory;
import org.polarsys.capella.core.data.fa.FunctionInputPort;
import org.polarsys.capella.core.data.fa.FunctionOutputPort;
import org.polarsys.capella.core.data.fa.FunctionalExchange;
import org.polarsys.capella.core.data.fa.OrientationPortKind;
import org.polarsys.capella.core.data.information.InformationFactory;
import org.polarsys.capella.core.data.information.PortAllocation;
import org.polarsys.capella.core.data.pa.PhysicalArchitecture;
import org.polarsys.capella.core.data.pa.PhysicalComponent;
import org.polarsys.capella.core.data.pa.PhysicalComponentNature;
import org.polarsys.capella.core.data.pa.deployment.DeploymentFactory;
import org.polarsys.capella.core.data.pa.deployment.PartDeploymentLink;
import org.polarsys.capella.core.model.helpers.PhysicalComponentExt;
import org.polarsys.capella.core.model.helpers.PhysicalLinkExt;
import org.polarsys.capella.core.model.helpers.PhysicalPathExt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Typed native deployment, physical topology and functional transport operations. */
public final class ArcadiaStructureAPI {
    private static final int MAX_SCANNED_ELEMENTS = 100000;
    private static final int MAX_REACHABLE_COMPONENTS = 4096;
    private final Session session;

    public ArcadiaStructureAPI(Session session) {
        this.session = session;
    }

    /**
     * Deploy a BEHAVIOR instance onto a NODE instance. Repeated identical deployment is a no-op.
     */
    public PartDeploymentLink deploy(Part behaviorPart, Part nodePart) {
        return write(
                "Deploy physical behavior",
                () -> {
                    PhysicalComponent behavior = physicalType(behaviorPart);
                    PhysicalComponent node = physicalType(nodePart);
                    BlockArchitecture architecture = sameArchitecture(behaviorPart, nodePart);
                    if (behavior.getNature() != PhysicalComponentNature.BEHAVIOR
                            || node.getNature() != PhysicalComponentNature.NODE) {
                        throw new IllegalArgumentException(
                                "Deployment requires a BEHAVIOR Part and a NODE location Part");
                    }
                    if (EcoreUtil.isAncestor(node, behavior)
                            || EcoreUtil.isAncestor(behavior, node)) {
                        throw new IllegalArgumentException(
                                "Deployment cannot duplicate or contradict component containment");
                    }
                    PartDeploymentLink existing = null;
                    Structure graph = scan(architecture);
                    // Capella's default deployment rule permits one deployment of a
                    // physical component, including when it has several representing Parts.
                    for (Part instance : graph.parts.getOrDefault(behavior, List.of())) {
                        requireOwned(instance);
                        for (PartDeploymentLink link :
                                graph.deployments.getOrDefault(instance, List.of())) {
                            requireOwned(link);
                            PartDeploymentLink deployment = link;
                            if (raw(deployment, "deployedElement") != instance
                                    || !(raw(deployment, "location") instanceof Part location)) {
                                throw new IllegalArgumentException(
                                        "Existing deployment is incomplete or incompatible");
                            }
                            requireOwned(location);
                            if (instance != behaviorPart || location != nodePart) {
                                throw new IllegalArgumentException(
                                        "Behavior already has another deployment; remove it"
                                            + " explicitly before redeploying");
                            }
                            if (deployment.eContainer() != nodePart || existing != null) {
                                throw new IllegalArgumentException(
                                        "Existing deployment has ambiguous or incorrect ownership");
                            }
                            existing = deployment;
                        }
                    }
                    if (existing != null) return unchanged(existing);
                    PartDeploymentLink deployment =
                            identified(DeploymentFactory.eINSTANCE.createPartDeploymentLink());
                    deployment.setDeployedElement(behaviorPart);
                    deployment.setLocation(nodePart);
                    nodePart.getOwnedDeploymentLinks().add(deployment);
                    return changed(deployment);
                });
    }

    /** Create one physical link with explicit, unambiguous native Parts and ports. */
    public PhysicalLink physicalLink(
            String name,
            Part sourcePart,
            PhysicalPort sourcePort,
            Part targetPart,
            PhysicalPort targetPort) {
        return write(
                "Create physical link",
                () -> {
                    String label = name(name);
                    var container = physicalLinkContainer(sourcePart, targetPart);
                    physicalPort(sourcePart, sourcePort);
                    physicalPort(targetPart, targetPort);
                    if (sourcePort == targetPort)
                        throw new IllegalArgumentException(
                                "Physical link endpoints must use different ports");
                    return changed(
                            createPhysicalLink(
                                    label,
                                    sourcePart,
                                    sourcePort,
                                    targetPart,
                                    targetPort,
                                    container));
                });
    }

    /** Create dedicated ports on the two NODE types and their physical link in one command. */
    public PhysicalLink physicalLink(String name, Part sourcePart, Part targetPart) {
        return write(
                "Create physical link and ports",
                () -> {
                    String label = name(name);
                    var container = physicalLinkContainer(sourcePart, targetPart);
                    PhysicalComponent source = physicalPortOwner(sourcePart);
                    PhysicalComponent target = physicalPortOwner(targetPart);
                    PhysicalPort sourcePort = identified(CsFactory.eINSTANCE.createPhysicalPort());
                    PhysicalPort targetPort = identified(CsFactory.eINSTANCE.createPhysicalPort());
                    sourcePort.setName(label + " source");
                    targetPort.setName(label + " target");
                    source.getOwnedFeatures().add(sourcePort);
                    target.getOwnedFeatures().add(targetPort);
                    return changed(
                            createPhysicalLink(
                                    label,
                                    sourcePart,
                                    sourcePort,
                                    targetPart,
                                    targetPort,
                                    container));
                });
    }

    /**
     * Carry an SA/LA/PA leaf functional exchange through a FLOW component interface. Requires
     * existing function allocations; creates both stored port allocations. Reversed functional
     * direction is supported when both interface ports are INOUT. Delegation/standard interfaces
     * require their distinct native construction tools.
     */
    public ComponentExchangeFunctionalExchangeAllocation allocateExchange(
            ComponentExchange transport, FunctionalExchange flow) {
        return write(
                "Allocate functional exchange to interface",
                () -> {
                    BlockArchitecture architecture = sameArchitecture(transport, flow);
                    String layer = architecture.eClass().getName();
                    if (!List.of("SystemAnalysis", "LogicalArchitecture", "PhysicalArchitecture")
                            .contains(layer)) {
                        throw new IllegalArgumentException(
                                "Functional transport allocation is supported in SA, LA and PA");
                    }
                    if (transport.getKind() != ComponentExchangeKind.FLOW) {
                        throw new IllegalArgumentException(
                                "Functional transport requires a FLOW component exchange");
                    }
                    Structure graph = scan(architecture);
                    ComponentPort source = componentPort(transport, "source");
                    ComponentPort target = componentPort(transport, "target");
                    if (source.eContainer() == target.eContainer()) {
                        throw new IllegalArgumentException(
                                "Functions in the same component use an internal functional"
                                    + " exchange without a component exchange");
                    }
                    if (!(raw(flow, "source") instanceof FunctionOutputPort output)
                            || !(raw(flow, "target") instanceof FunctionInputPort input)) {
                        throw new IllegalArgumentException(
                                "Functional exchange requires an attached output port and input"
                                    + " port");
                    }
                    leafFunction(output, graph);
                    leafFunction(input, graph);
                    sameArchitecture(transport, output);
                    sameArchitecture(transport, input);
                    Set<Component> outputOwners =
                            functionAllocations((AbstractFunction) output.eContainer(), graph);
                    Set<Component> inputOwners =
                            functionAllocations((AbstractFunction) input.eContainer(), graph);
                    if (source.getOrientation() != OrientationPortKind.OUT
                                    && source.getOrientation() != OrientationPortKind.INOUT
                            || target.getOrientation() != OrientationPortKind.IN
                                    && target.getOrientation() != OrientationPortKind.INOUT) {
                        throw new IllegalArgumentException(
                                "Component exchange must use an OUT/INOUT source and IN/INOUT"
                                    + " target");
                    }
                    Set<Component> sourceHierarchy =
                            hierarchy((Component) source.eContainer(), graph);
                    Set<Component> targetHierarchy =
                            hierarchy((Component) target.eContainer(), graph);
                    boolean forward =
                            !Collections.disjoint(sourceHierarchy, outputOwners)
                                    && !Collections.disjoint(targetHierarchy, inputOwners);
                    boolean reverse =
                            !Collections.disjoint(sourceHierarchy, inputOwners)
                                    && !Collections.disjoint(targetHierarchy, outputOwners);
                    if (forward == reverse) {
                        throw new IllegalArgumentException(
                                "Functional endpoints must have one unambiguous allocation to the"
                                    + " two interface components");
                    }
                    if (reverse
                            && (source.getOrientation() != OrientationPortKind.INOUT
                                    || target.getOrientation() != OrientationPortKind.INOUT)) {
                        throw new IllegalArgumentException(
                                "Reversed functional direction requires two INOUT component ports");
                    }
                    EObject sourceFunctionPort = forward ? output : input;
                    EObject targetFunctionPort = forward ? input : output;
                    var allocation =
                            unique(
                                    transport
                                            .getOwnedComponentExchangeFunctionalExchangeAllocations(),
                                    transport,
                                    flow);
                    var sourceAllocation =
                            unique(source.getOwnedPortAllocations(), source, sourceFunctionPort);
                    var targetAllocation =
                            unique(target.getOwnedPortAllocations(), target, targetFunctionPort);
                    boolean created =
                            allocation == null
                                    || sourceAllocation == null
                                    || targetAllocation == null;
                    if (allocation == null) {
                        allocation =
                                identified(
                                        FaFactory.eINSTANCE
                                                .createComponentExchangeFunctionalExchangeAllocation());
                        allocation.setSourceElement(transport);
                        allocation.setTargetElement(flow);
                        transport
                                .getOwnedComponentExchangeFunctionalExchangeAllocations()
                                .add(allocation);
                    }
                    if (sourceAllocation == null) attachPort(source, sourceFunctionPort);
                    if (targetAllocation == null) attachPort(target, targetFunctionPort);
                    return new Change<>(allocation, created);
                });
    }

    /**
     * A physical path through contiguous physical links, in order, as Capella's "Physical Path"
     * tool builds it (PhysicalPathExt.createPhysicalPath), owned by the links' common component.
     */
    public PhysicalPath physicalPath(String name, List<PhysicalLink> orderedLinks) {
        return write(
                "Create physical path",
                () -> {
                    String label = name(name);
                    if (orderedLinks == null || orderedLinks.isEmpty())
                        throw new IllegalArgumentException("A physical path needs physical links");
                    List<Part> stops = route(orderedLinks);
                    var container =
                            PhysicalLinkExt.getDefaultContainer(
                                    stops.get(0), stops.get(stops.size() - 1));
                    Component owner =
                            container instanceof Component component
                                    ? component
                                    : (Component) roots(orderedLinks.get(0));
                    // PhysicalPathExt.createPhysicalPath's structure (part, link, part, ...)
                    // without
                    // its workbench-only creation command.
                    PhysicalPath path = identified(CsFactory.eINSTANCE.createPhysicalPath());
                    path.setName(label);
                    owner.getOwnedPhysicalPath().add(path);
                    PhysicalPathInvolvement previous = involve(path, stops.get(0));
                    for (int index = 0; index < orderedLinks.size(); index++) {
                        PhysicalPathInvolvement link = involve(path, orderedLinks.get(index));
                        previous.getNextInvolvements().add(link);
                        previous = involve(path, stops.get(index + 1));
                        link.getNextInvolvements().add(previous);
                    }
                    return changed(path);
                });
    }

    /**
     * Carry a PA component exchange on a physical link or physical path, as Capella's allocation
     * tools do: a component exchange allocation on the carrier, then the native port allocations
     * (PhysicalLinkExt/PhysicalPathExt.synchronizeAllocations). The exchange's two components must
     * be deployed on the nodes at the carrier's two ends.
     */
    public ComponentExchangeAllocation allocateOnLink(
            ComponentExchange exchange, ComponentExchangeAllocator carrier) {
        return write(
                "Allocate component exchange to physical link",
                () -> {
                    BlockArchitecture architecture = sameArchitecture(exchange, carrier);
                    if (!(architecture instanceof PhysicalArchitecture))
                        throw new IllegalArgumentException(
                                "Component exchanges are carried by physical links in PA");
                    List<Part> ends;
                    if (carrier instanceof PhysicalLink link) ends = route(List.of(link));
                    else if (carrier instanceof PhysicalPath path) ends = route(links(path));
                    else
                        throw new IllegalArgumentException(
                                "A component exchange is carried by a physical link or path");
                    Structure graph = scan(architecture);
                    Component source = (Component) componentPort(exchange, "source").eContainer(),
                            target = (Component) componentPort(exchange, "target").eContainer();
                    Set<Component> first = hierarchy(physicalType(ends.get(0)), graph),
                            last = hierarchy(physicalType(ends.get(ends.size() - 1)), graph);
                    if (!(first.contains(source) && last.contains(target)
                            || first.contains(target) && last.contains(source)))
                        throw new IllegalArgumentException(
                                "The exchange's components must be deployed on the nodes at the"
                                        + " two ends of the physical link or path");
                    for (var existing : carrier.getOwnedComponentExchangeAllocations())
                        if (existing.getComponentExchangeAllocated() == exchange)
                            return unchanged(existing);
                    var allocation =
                            identified(FaFactory.eINSTANCE.createComponentExchangeAllocation());
                    allocation.setSourceElement(carrier);
                    allocation.setTargetElement(exchange);
                    carrier.getOwnedComponentExchangeAllocations().add(allocation);
                    if (carrier instanceof PhysicalLink link)
                        PhysicalLinkExt.synchronizeAllocations(link, exchange);
                    else PhysicalPathExt.synchronizeAllocations((PhysicalPath) carrier, exchange);
                    return changed(allocation);
                });
    }

    /** A simple path's links in order, following the involvements from the first one. */
    private static List<PhysicalLink> links(PhysicalPath path) {
        var involvements = path.getOwnedPhysicalPathInvolvements();
        PhysicalPathInvolvement step =
                involvements.stream()
                        .filter(
                                i ->
                                        involvements.stream()
                                                .noneMatch(
                                                        o -> o.getNextInvolvements().contains(i)))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "A physical path needs a first part"));
        List<PhysicalLink> links = new ArrayList<>();
        for (int guard = 0; step != null && guard <= involvements.size(); guard++) {
            if (step.getInvolved() instanceof PhysicalLink link) links.add(link);
            if (step.getNextInvolvements().size() > 1)
                throw new IllegalArgumentException("Branching physical paths are not supported");
            step = step.getNextInvolvements().isEmpty() ? null : step.getNextInvolvements().get(0);
        }
        return links;
    }

    private static PhysicalPathInvolvement involve(
            PhysicalPath path, AbstractPathInvolvedElement element) {
        var involvement = identified(CsFactory.eINSTANCE.createPhysicalPathInvolvement());
        involvement.setInvolved(element);
        path.getOwnedPhysicalPathInvolvements().add(involvement);
        return involvement;
    }

    /** The parts a path of links passes through, in order; consecutive links share a part. */
    private List<Part> route(List<PhysicalLink> links) {
        List<Part> stops = new ArrayList<>();
        for (PhysicalLink link : links) {
            requireOwned(link);
            List<Part> ends = new ArrayList<>();
            for (var end : link.getLinkEnds())
                if (end instanceof PhysicalLinkEnd linkEnd && linkEnd.getPart() != null)
                    ends.add(linkEnd.getPart());
            if (ends.size() != 2)
                throw new IllegalArgumentException(
                        "Each physical link needs two part ends: " + link.getName());
            if (stops.isEmpty()) {
                stops.addAll(ends);
                continue;
            }
            Part last = stops.get(stops.size() - 1);
            if (stops.size() == 2 && !ends.contains(last) && ends.contains(stops.get(0)))
                Collections.reverse(stops);
            last = stops.get(stops.size() - 1);
            if (!ends.contains(last))
                throw new IllegalArgumentException(
                        "Physical path links must be contiguous, in order: " + link.getName());
            Part next = ends.get(0) == last ? ends.get(1) : ends.get(0);
            if (stops.contains(next))
                throw new IllegalArgumentException(
                        "A physical path cannot loop: " + link.getName());
            stops.add(next);
        }
        return stops;
    }

    /** The PA root physical component, which owns paths whose links sit in a package. */
    private static EObject roots(PhysicalLink link) {
        BlockArchitecture layer = null;
        for (EObject o = link; o != null && layer == null; o = o.eContainer())
            if (o instanceof BlockArchitecture found) layer = found;
        if (layer != null && layer.getSystem() != null) return layer.getSystem();
        throw new IllegalArgumentException("A physical path needs the PA root component");
    }

    private PhysicalComponent physicalType(Part part) {
        requireOwned(part);
        if (!(part.eContainer() instanceof Component || part.eContainer() instanceof ComponentPkg)
                || !(raw(part, "abstractType") instanceof PhysicalComponent type)) {
            throw new IllegalArgumentException(
                    "Physical operations require a contained Part typed by a physical component");
        }
        if (!(sameArchitecture(part, type) instanceof PhysicalArchitecture)) {
            throw new IllegalArgumentException("Deployment and physical links belong to PA");
        }
        return type;
    }

    private PhysicalComponent physicalPortOwner(Part part) {
        PhysicalComponent component = physicalType(part);
        if (component.getNature() != PhysicalComponentNature.NODE
                || PhysicalComponentExt.isPhysicalComponentRoot(component)) {
            throw new IllegalArgumentException(
                    "Physical ports require a non-root NODE physical component or actor");
        }
        return component;
    }

    private void physicalPort(Part part, PhysicalPort port) {
        PhysicalComponent component = physicalPortOwner(part);
        requireOwned(port);
        if (port.eContainer() != component || !component.getOwnedFeatures().contains(port)) {
            throw new IllegalArgumentException(
                    "Physical port must belong to the exact component type of its endpoint Part");
        }
        sameArchitecture(part, port);
    }

    private CapellaElement physicalLinkContainer(Part source, Part target) {
        physicalType(source);
        physicalType(target);
        sameArchitecture(source, target);
        if (source == target)
            throw new IllegalArgumentException(
                    "Physical link requires two distinct endpoint Parts");
        var container = PhysicalLinkExt.getDefaultContainer(source, target);
        requireOwned(container);
        if (!(container instanceof Component || container instanceof ComponentPkg)) {
            throw new IllegalArgumentException(
                    "Physical link requires a common component or component package");
        }
        return container;
    }

    private PhysicalLink createPhysicalLink(
            String name,
            Part sourcePart,
            PhysicalPort sourcePort,
            Part targetPart,
            PhysicalPort targetPort,
            CapellaElement container) {
        PhysicalLink link = identified(CsFactory.eINSTANCE.createPhysicalLink());
        link.setName(name);
        addEnd(link, sourcePart, sourcePort);
        addEnd(link, targetPart, targetPort);
        if (!PhysicalLinkExt.attachTo(link, container) || link.eContainer() != container) {
            throw new IllegalArgumentException(
                    "Cannot attach physical link to its native common container");
        }
        return link;
    }

    private static void addEnd(PhysicalLink link, Part part, PhysicalPort port) {
        PhysicalLinkEnd end = identified(CsFactory.eINSTANCE.createPhysicalLinkEnd());
        end.setPart(part);
        end.setPort(port);
        link.getOwnedPhysicalLinkEnds().add(end);
        link.getLinkEnds().add(end);
    }

    private ComponentPort componentPort(ComponentExchange exchange, String feature) {
        Object endpoint = raw(exchange, feature);
        if (endpoint instanceof ComponentExchangeEnd end) {
            requireOwned(end);
            if (end.eContainer() != exchange
                    || !(raw(end, "part") instanceof Part part)
                    || !(raw(end, "port") instanceof ComponentPort port)) {
                throw new IllegalArgumentException(
                        "Component exchange end requires its owned Part and component port");
            }
            requireOwned(part);
            if (raw(part, "abstractType") != port.eContainer()) {
                throw new IllegalArgumentException(
                        "Component exchange port and instance Part have incompatible types");
            }
            sameArchitecture(exchange, part);
            endpoint = port;
        }
        if (!(endpoint instanceof ComponentPort port)) {
            throw new IllegalArgumentException(
                    "Component exchange must connect native component ports");
        }
        requireOwned(port);
        sameArchitecture(exchange, port);
        if (!(port.eContainer() instanceof Component component)
                || !component.getOwnedFeatures().contains(port)
                || port.getKind() != ComponentPortKind.FLOW) {
            throw new IllegalArgumentException(
                    "Interface endpoints must be attached FLOW component ports");
        }
        return port;
    }

    private void leafFunction(EObject port, Structure graph) {
        requireOwned(port);
        if (!(port.eContainer() instanceof AbstractFunction function)
                || graph.nonLeaves.contains(function)) {
            throw new IllegalArgumentException(
                    "Transport must connect ports of leaf functions; decompose or delegate"
                        + " aggregate interfaces explicitly");
        }
    }

    private Set<Component> functionAllocations(AbstractFunction function, Structure graph) {
        Set<Component> owners = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ComponentFunctionalAllocation allocation :
                graph.allocations.getOrDefault(function, List.of())) {
            requireOwned(allocation);
            if (!(raw(allocation, "sourceElement") instanceof Component component)
                    || raw(allocation, "targetElement") != function
                    || allocation.eContainer() != component
                    || !component.getOwnedFunctionalAllocation().contains(allocation)) {
                throw new IllegalArgumentException(
                        "Function allocation must be owned by its exact allocating component");
            }
            sameArchitecture(function, component);
            if (!owners.add(component))
                throw new IllegalArgumentException(
                        "Duplicate function allocations must be corrected before allocating an"
                            + " interface");
        }
        return owners;
    }

    /**
     * Stored equivalents of PartExt/ComponentExt's used/deployed hierarchy and native inverse
     * allocations. No Sirius cross-reference index is assumed ready, and no resolving derived
     * getter is needed for these guards.
     */
    private Structure scan(BlockArchitecture architecture) {
        Structure graph = new Structure();
        var iterator = architecture.eAllContents();
        int scanned = 0;
        while (iterator.hasNext()) {
            checkCancellation();
            if (++scanned > MAX_SCANNED_ELEMENTS) {
                throw new IllegalArgumentException(
                        "Architecture is too large for this bounded structure operation");
            }
            EObject value = iterator.next();
            // ReqIF packages contain requirements, values and trace metadata,
            // rather than native structural components/functions/Parts.
            String namespace = value.eClass().getEPackage().getNsURI();
            if ("http://www.polarsys.org/kitalpha/requirements".equals(namespace)
                    || "http://www.polarsys.org/capella/requirements".equals(namespace)) {
                iterator.prune();
                continue;
            }
            if (value instanceof Part part && raw(part, "abstractType") instanceof Component type) {
                graph.parts.computeIfAbsent(type, ignored -> new ArrayList<>()).add(part);
                // A part in a nested component package is used by the nearest
                // containing component, exactly as ComponentExt.getSubParts.
                for (EObject owner = part.eContainer();
                        owner != null && owner != architecture;
                        owner = owner.eContainer()) {
                    if (owner instanceof Component component) {
                        graph.used
                                .computeIfAbsent(component, ignored -> new ArrayList<>())
                                .add(type);
                        break;
                    }
                }
            } else if (value instanceof PartDeploymentLink deployment
                    && raw(deployment, "deployedElement") instanceof Part part) {
                graph.deployments
                        .computeIfAbsent(part, ignored -> new ArrayList<>())
                        .add(deployment);
                if (raw(deployment, "location") instanceof Part location) {
                    graph.hosted
                            .computeIfAbsent(location, ignored -> new ArrayList<>())
                            .add(deployment);
                }
            } else if (value instanceof ComponentFunctionalAllocation allocation
                    && raw(allocation, "targetElement") instanceof AbstractFunction function) {
                graph.allocations
                        .computeIfAbsent(function, ignored -> new ArrayList<>())
                        .add(allocation);
            }
            if (value instanceof AbstractFunction) {
                for (EObject owner = value.eContainer();
                        owner != null && owner != architecture;
                        owner = owner.eContainer()) {
                    if (owner instanceof AbstractFunction function) {
                        graph.nonLeaves.add(function);
                        break;
                    }
                }
            }
        }
        return graph;
    }

    private Set<Component> hierarchy(Component endpoint, Structure graph) {
        Set<Component> result = Collections.newSetFromMap(new IdentityHashMap<>());
        var remaining = new ArrayDeque<Component>();
        remaining.add(endpoint);
        while (!remaining.isEmpty()) {
            checkCancellation();
            Component component = remaining.removeFirst();
            if (!result.add(component)) continue;
            if (result.size() > MAX_REACHABLE_COMPONENTS) {
                throw new IllegalArgumentException(
                        "Component usage hierarchy exceeds the bounded allocation check");
            }
            sameArchitecture(endpoint, component);
            for (Component child : graph.used.getOrDefault(component, List.of())) {
                sameArchitecture(endpoint, child);
                remaining.add(child);
            }
            if (component instanceof PhysicalComponent) {
                for (Part location : graph.parts.getOrDefault(component, List.of())) {
                    requireOwned(location);
                    for (PartDeploymentLink deployment :
                            graph.hosted.getOrDefault(location, List.of())) {
                        requireOwned(deployment);
                        if (deployment.eContainer() != location
                                || raw(deployment, "location") != location
                                || !(raw(deployment, "deployedElement")
                                        instanceof Part deployedPart)) {
                            throw new IllegalArgumentException(
                                    "Deployment in interface hierarchy has incorrect ownership");
                        }
                        PhysicalComponent deployed = physicalType(deployedPart);
                        if (((PhysicalComponent) component).getNature()
                                        != PhysicalComponentNature.NODE
                                || deployed.getNature() != PhysicalComponentNature.BEHAVIOR) {
                            throw new IllegalArgumentException(
                                    "Interface hierarchy requires BEHAVIOR deployment on NODE"
                                        + " equipment");
                        }
                        remaining.add(deployed);
                    }
                }
            }
        }
        return result;
    }

    private static final class Structure {
        final Map<Component, List<Part>> parts = new IdentityHashMap<>();
        final Map<Component, List<Component>> used = new IdentityHashMap<>();
        final Map<Part, List<PartDeploymentLink>> deployments = new IdentityHashMap<>();
        final Map<Part, List<PartDeploymentLink>> hosted = new IdentityHashMap<>();
        final Map<AbstractFunction, List<ComponentFunctionalAllocation>> allocations =
                new IdentityHashMap<>();
        final Set<AbstractFunction> nonLeaves = Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static void checkCancellation() {
        if (SessionSafeguards.isScriptCancellationRequested()
                || Thread.currentThread().isInterrupted()) {
            throw new SessionSafeguards.ScriptCancelledException(
                    "Arcadia structure operation was cancelled or timed out");
        }
    }

    private static void attachPort(ComponentPort componentPort, EObject functionPort) {
        PortAllocation allocation = identified(InformationFactory.eINSTANCE.createPortAllocation());
        allocation.setSourceElement(componentPort);
        allocation.setTargetElement(
                (org.polarsys.capella.common.data.modellingcore.TraceableElement) functionPort);
        componentPort.getOwnedPortAllocations().add(allocation);
    }

    private <T extends org.polarsys.capella.common.data.modellingcore.AbstractTrace> T unique(
            List<T> relations, EObject source, EObject target) {
        T found = null;
        for (T relation : relations) {
            requireOwned(relation);
            if (raw(relation, "sourceElement") != source || relation.eContainer() != source) {
                throw new IllegalArgumentException(
                        "Stored allocation must be owned by its exact source element");
            }
            if (raw(relation, "sourceElement") == source
                    && raw(relation, "targetElement") == target) {
                if (found != null)
                    throw new IllegalArgumentException(
                            "Duplicate stored allocations must be corrected before allocating the"
                                + " interface");
                if (relation.eContainer() != source)
                    throw new IllegalArgumentException("Stored allocation has an incorrect owner");
                found = relation;
            }
        }
        return found;
    }

    private BlockArchitecture sameArchitecture(EObject first, EObject second) {
        BlockArchitecture architecture = architecture(first);
        if (architecture != architecture(second))
            throw new IllegalArgumentException(
                    "Endpoints must belong to the same active architecture");
        return architecture;
    }

    private BlockArchitecture architecture(EObject element) {
        requireOwned(element);
        for (EObject owner = element; owner != null; owner = owner.eContainer()) {
            if (owner instanceof BlockArchitecture architecture) return architecture;
        }
        throw new IllegalArgumentException("Element must belong to a native Arcadia architecture");
    }

    private void requireSession() {
        if (session == null
                || !session.isOpen()
                || session.getTransactionalEditingDomain() == null) {
            throw new IllegalStateException(
                    "An open Capella session with a transactional domain is required");
        }
    }

    private void requireOwned(EObject element) {
        requireSession();
        if (element == null
                || element.eIsProxy()
                || element.eResource() == null
                || element.eResource().getResourceSet()
                        != session.getTransactionalEditingDomain().getResourceSet()
                || !session.getSemanticResources().contains(element.eResource())) {
            throw new IllegalArgumentException(
                    "Endpoints must be resolved semantic elements in the active model session");
        }
    }

    private static Object raw(EObject element, String name) {
        EStructuralFeature feature = element.eClass().getEStructuralFeature(name);
        return feature == null ? null : element.eGet(feature, false);
    }

    private static String name(String value) {
        if (value == null || value.isBlank() || value.length() > 2400) {
            throw new IllegalArgumentException(
                    "A nonblank physical link name of at most 2400 characters is required");
        }
        return value;
    }

    private static <T extends CapellaElement> T identified(T element) {
        element.setId(UUID.randomUUID().toString());
        return element;
    }

    private record Change<T>(T value, boolean changed) {}

    private static <T> Change<T> changed(T value) {
        return new Change<>(value, true);
    }

    private static <T> Change<T> unchanged(T value) {
        return new Change<>(value, false);
    }

    private <T> T write(String label, Supplier<Change<T>> action) {
        requireSession();
        return SessionSafeguards.runWrite(
                session,
                label,
                () -> {
                    requireSession();
                    Change<T> result = action.get();
                    if (result.changed()) {
                        ModelSummary.invalidate(session);
                        try {
                            SessionSafeguards.requestSave(session);
                        } catch (Exception failure) {
                            throw new IllegalStateException(
                                    "Could not save Arcadia structure changes", failure);
                        }
                    }
                    return result.value();
                });
    }
}
