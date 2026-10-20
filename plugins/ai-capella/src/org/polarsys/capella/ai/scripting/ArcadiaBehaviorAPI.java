package org.polarsys.capella.ai.scripting;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.model.ModelAnalytics;
import org.polarsys.capella.ai.model.ModelSummary;
import org.polarsys.capella.ai.model.index.ModelIndex;
import org.polarsys.capella.ai.session.SessionSafeguards;
import org.polarsys.capella.core.data.capellacommon.CapellacommonFactory;
import org.polarsys.capella.core.data.capellacore.CapellaElement;
import org.polarsys.capella.core.data.capellacore.CapellacoreFactory;
import org.polarsys.capella.core.data.capellacore.Constraint;
import org.polarsys.capella.core.data.capellacore.InvolvedElement;
import org.polarsys.capella.core.data.capellacore.Involvement;
import org.polarsys.capella.core.data.capellacore.NamedElement;
import org.polarsys.capella.core.data.cs.BlockArchitecture;
import org.polarsys.capella.core.data.cs.Component;
import org.polarsys.capella.core.data.ctx.Capability;
import org.polarsys.capella.core.data.ctx.CtxFactory;
import org.polarsys.capella.core.data.ctx.SystemAnalysis;
import org.polarsys.capella.core.data.ctx.SystemComponent;
import org.polarsys.capella.core.data.ctx.SystemFunction;
import org.polarsys.capella.core.data.fa.AbstractFunction;
import org.polarsys.capella.core.data.fa.FaFactory;
import org.polarsys.capella.core.data.fa.FunctionInputPort;
import org.polarsys.capella.core.data.fa.FunctionOutputPort;
import org.polarsys.capella.core.data.fa.FunctionPkg;
import org.polarsys.capella.core.data.fa.FunctionalChain;
import org.polarsys.capella.core.data.fa.FunctionalChainInvolvementFunction;
import org.polarsys.capella.core.data.fa.FunctionalChainKind;
import org.polarsys.capella.core.data.fa.FunctionalExchange;
import org.polarsys.capella.core.data.information.datavalue.DatavalueFactory;
import org.polarsys.capella.core.data.interaction.AbstractCapability;
import org.polarsys.capella.core.data.interaction.AbstractCapabilityRealization;
import org.polarsys.capella.core.data.interaction.AbstractFunctionAbstractCapabilityInvolvement;
import org.polarsys.capella.core.data.interaction.FunctionalChainAbstractCapabilityInvolvement;
import org.polarsys.capella.core.data.interaction.InstanceRole;
import org.polarsys.capella.core.data.interaction.InteractionFactory;
import org.polarsys.capella.core.data.interaction.MessageKind;
import org.polarsys.capella.core.data.interaction.Scenario;
import org.polarsys.capella.core.data.interaction.ScenarioKind;
import org.polarsys.capella.core.data.la.CapabilityRealization;
import org.polarsys.capella.core.data.la.LogicalArchitecture;
import org.polarsys.capella.core.data.la.LogicalComponent;
import org.polarsys.capella.core.data.la.LogicalFunction;
import org.polarsys.capella.core.data.oa.Entity;
import org.polarsys.capella.core.data.oa.OaFactory;
import org.polarsys.capella.core.data.oa.OperationalActivity;
import org.polarsys.capella.core.data.oa.OperationalAnalysis;
import org.polarsys.capella.core.data.oa.OperationalCapability;
import org.polarsys.capella.core.data.pa.PhysicalArchitecture;
import org.polarsys.capella.core.data.pa.PhysicalComponent;
import org.polarsys.capella.core.data.pa.PhysicalFunction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Typed behavior builders for the captured semantic session. These model native structure; a
 * modeled scenario/constraint is not a physical verification or a proof of engineering correctness.
 */
public final class ArcadiaBehaviorAPI {
    private static final int MAX_ITEMS = 256;
    private final Session session;

    public ArcadiaBehaviorAPI(Session session) {
        this.session = session;
    }

    /** Stored involvement pattern from Capella 7 AbstractCapabilityExt.addInvolvedFunction. */
    public AbstractFunctionAbstractCapabilityInvolvement involveFunction(
            AbstractCapability capability, AbstractFunction function) {
        return write(
                "Involve capability function",
                () -> {
                    BlockArchitecture layer = capabilityLayer(capability);
                    requireFunction(function, layer, false);
                    var existing =
                            unique(
                                    capability
                                            .getOwnedAbstractFunctionAbstractCapabilityInvolvements()
                                            .stream()
                                            .filter(link -> link.getInvolved() == function)
                                            .toList(),
                                    "function involvement");
                    if (existing != null) return existing;
                    var link =
                            identify(
                                    InteractionFactory.eINSTANCE
                                            .createAbstractFunctionAbstractCapabilityInvolvement());
                    link.setInvolved(function);
                    capability.getOwnedAbstractFunctionAbstractCapabilityInvolvements().add(link);
                    return changed(link);
                });
    }

    /** Native OA entity, SA system-component or LA/PA capability-realization involvement. */
    public Involvement involveComponent(AbstractCapability capability, Component component) {
        return write(
                "Involve capability component",
                () -> {
                    BlockArchitecture layer = capabilityLayer(capability);
                    requireOwned(component);
                    if (architecture(component) != layer || !componentMatches(component, layer)) {
                        throw invalid(
                                "Capability and component must have matching types in the same"
                                    + " architecture");
                    }
                    List<? extends Involvement> links;
                    if (capability instanceof OperationalCapability operational)
                        links = operational.getOwnedEntityOperationalCapabilityInvolvements();
                    else if (capability instanceof Capability system)
                        links = system.getOwnedCapabilityInvolvements();
                    else
                        links =
                                ((CapabilityRealization) capability)
                                        .getOwnedCapabilityRealizationInvolvements();
                    var existing =
                            unique(
                                    links.stream()
                                            .filter(link -> link.getInvolved() == component)
                                            .toList(),
                                    "component involvement");
                    if (existing != null) return existing;
                    Involvement link;
                    if (capability instanceof OperationalCapability operational) {
                        var value =
                                identify(
                                        OaFactory.eINSTANCE
                                                .createEntityOperationalCapabilityInvolvement());
                        value.setInvolved((InvolvedElement) component);
                        operational.getOwnedEntityOperationalCapabilityInvolvements().add(value);
                        link = value;
                    } else if (capability instanceof Capability system) {
                        var value = identify(CtxFactory.eINSTANCE.createCapabilityInvolvement());
                        value.setInvolved((InvolvedElement) component);
                        system.getOwnedCapabilityInvolvements().add(value);
                        link = value;
                    } else {
                        var value =
                                identify(
                                        CapellacommonFactory.eINSTANCE
                                                .createCapabilityRealizationInvolvement());
                        value.setInvolved((InvolvedElement) component);
                        ((CapabilityRealization) capability)
                                .getOwnedCapabilityRealizationInvolvements()
                                .add(value);
                        link = value;
                    }
                    return changed(link);
                });
    }

    /** Stored involvement pattern from AbstractCapabilityExt.addInvolvedFunctionalChain. */
    public FunctionalChainAbstractCapabilityInvolvement involveChain(
            AbstractCapability capability, FunctionalChain chain) {
        return write(
                "Involve capability chain",
                () -> {
                    BlockArchitecture layer = capabilityLayer(capability);
                    requireOwned(chain);
                    if (layer instanceof OperationalAnalysis || architecture(chain) != layer) {
                        throw invalid(
                                "A functional chain and capability must belong to the same SA, LA"
                                    + " or PA architecture");
                    }
                    var existing =
                            unique(
                                    capability
                                            .getOwnedFunctionalChainAbstractCapabilityInvolvements()
                                            .stream()
                                            .filter(link -> link.getInvolved() == chain)
                                            .toList(),
                                    "chain involvement");
                    if (existing != null) return existing;
                    var link =
                            identify(
                                    InteractionFactory.eINSTANCE
                                            .createFunctionalChainAbstractCapabilityInvolvement());
                    link.setInvolved(chain);
                    capability.getOwnedFunctionalChainAbstractCapabilityInvolvements().add(link);
                    return changed(link);
                });
    }

    /** The realizing lower capability owns an OA→SA, SA→LA or LA→PA native realization. */
    public AbstractCapabilityRealization realizeCapability(
            AbstractCapability lower, AbstractCapability upper) {
        return write(
                "Realize adjacent capability",
                () -> {
                    BlockArchitecture lowerLayer = capabilityLayer(lower),
                            upperLayer = capabilityLayer(upper);
                    if (level(lowerLayer) != level(upperLayer) + 1
                            || lowerLayer.eContainer() != upperLayer.eContainer()) {
                        throw invalid(
                                "Capability realization requires adjacent architectures of the same"
                                    + " system engineering model");
                    }
                    var matches =
                            lower.getOwnedAbstractCapabilityRealizations().stream()
                                    .filter(link -> link.getTargetElement() == upper)
                                    .toList();
                    var existing = unique(matches, "capability realization");
                    if (existing != null) {
                        if (existing.getSourceElement() != lower)
                            throw invalid(
                                    "Existing capability realization has an inconsistent source");
                        return existing;
                    }
                    var link =
                            identify(
                                    InteractionFactory.eINSTANCE
                                            .createAbstractCapabilityRealization());
                    link.setSourceElement(lower);
                    link.setTargetElement(upper);
                    lower.getOwnedAbstractCapabilityRealizations().add(link);
                    return changed(link);
                });
    }

    /**
     * An ordered simple chain under the architecture root function. Every link connects its actual
     * function involvements, following
     * FunctionalChainExt.createFunctionalChain/createInvolvementLink.
     */
    public FunctionalChain linearChain(
            AbstractFunction owner,
            String name,
            List<? extends AbstractFunction> functions,
            List<? extends FunctionalExchange> exchanges) {
        String label = text(name, "Chain name", 2400);
        var orderedFunctions = ordered(functions, "Chain functions");
        var orderedExchanges = ordered(exchanges, "Chain exchanges");
        return write(
                "Create linear functional chain",
                () -> {
                    requireOwned(owner);
                    BlockArchitecture layer = architecture(owner);
                    if (layer instanceof OperationalAnalysis || level(layer) < 1)
                        throw invalid("Linear functional chains support SA, LA and PA");
                    requireFunction(owner, layer, false);
                    if (owner.eContainer() != layer.getOwnedFunctionPkg())
                        throw invalid(
                                "A functional chain must be owned by the architecture root"
                                    + " function");
                    if (orderedFunctions.size() < 2
                            || orderedExchanges.size() != orderedFunctions.size() - 1) {
                        throw invalid(
                                "A linear chain requires at least two functions and exactly one"
                                    + " exchange between each adjacent pair");
                    }
                    requireUniqueName(owner.getOwnedFunctionalChains(), label, "functional chain");
                    for (AbstractFunction function : orderedFunctions) {
                        requireFunction(function, layer, true);
                        if (!below(function, owner))
                            throw invalid(
                                    "Every chain function must be contained under its owning root"
                                        + " function");
                    }
                    for (int index = 0; index < orderedExchanges.size(); index++) {
                        FunctionalExchange exchange = orderedExchanges.get(index);
                        requireExchange(exchange, layer);
                        if (!below(exchange, owner))
                            throw invalid(
                                    "Every chain exchange must be contained under its owning root"
                                        + " function");
                        if (source(exchange) != orderedFunctions.get(index)
                                || target(exchange) != orderedFunctions.get(index + 1)) {
                            throw invalid(
                                    "Each chain exchange must connect its adjacent ordered source"
                                        + " and target functions");
                        }
                    }
                    FunctionalChain chain =
                            named(FaFactory.eINSTANCE.createFunctionalChain(), label);
                    chain.setKind(FunctionalChainKind.SIMPLE);
                    List<FunctionalChainInvolvementFunction> nodes = new ArrayList<>();
                    for (AbstractFunction function : orderedFunctions) {
                        var node =
                                identify(
                                        FaFactory.eINSTANCE
                                                .createFunctionalChainInvolvementFunction());
                        node.setInvolved(function);
                        chain.getOwnedFunctionalChainInvolvements().add(node);
                        nodes.add(node);
                    }
                    for (int index = 0; index < orderedExchanges.size(); index++) {
                        var link =
                                identify(
                                        FaFactory.eINSTANCE.createFunctionalChainInvolvementLink());
                        link.setInvolved(orderedExchanges.get(index));
                        link.setSource(nodes.get(index));
                        link.setTarget(nodes.get(index + 1));
                        chain.getOwnedFunctionalChainInvolvements().add(link);
                    }
                    owner.getOwnedFunctionalChains().add(chain);
                    return changed(chain);
                });
    }

    /**
     * An empty SA, LA or PA scenario in its capability, as Capella's "New
     * Exchange/Functional/Interface Scenario" creates it; its sequence diagram is then created on
     * the scenario. Kind is exchange, functional or interface. Operational scenarios use {@link
     * #operationalScenario}.
     */
    public Scenario scenario(AbstractCapability owner, String name, String kind) {
        String label = text(name, "Scenario name", 2400);
        ScenarioKind nativeKind =
                switch (text(kind, "Scenario kind", 40).toLowerCase(java.util.Locale.ROOT)) {
                    case "exchange" -> ScenarioKind.DATA_FLOW;
                    case "functional" -> ScenarioKind.FUNCTIONAL;
                    case "interface" -> ScenarioKind.INTERFACE;
                    default ->
                            throw invalid(
                                    "Scenario kind must be exchange, functional or interface");
                };
        return write(
                "Create scenario",
                () -> {
                    if (capabilityLayer(owner) instanceof OperationalAnalysis) {
                        throw invalid(
                                "Operational scenarios use"
                                    + " api.arcadia.operationalScenario(capability, name,"
                                    + " interactions, pre, post)");
                    }
                    requireUniqueName(owner.getOwnedScenarios(), label, "scenario");
                    Scenario scenario = named(InteractionFactory.eINSTANCE.createScenario(), label);
                    scenario.setKind(nativeKind);
                    owner.getOwnedScenarios().add(scenario);
                    return changed(scenario);
                });
    }

    /**
     * OA Activity Interaction Scenario from Capella 7 oa.odesign's asynchronous activity message
     * tool: INTERACTION kind, activity instance roles, operation events, sending/receiving ends and
     * receiver executions. Conditions are natural-language native Constraints, not evaluated
     * control predicates.
     */
    public Scenario operationalScenario(
            OperationalCapability owner,
            String name,
            List<? extends FunctionalExchange> orderedInteractions,
            String precondition,
            String postcondition) {
        String label = text(name, "Scenario name", 2400);
        String pre = text(precondition, "Scenario precondition", 16000),
                post = text(postcondition, "Scenario postcondition", 16000);
        var interactions = ordered(orderedInteractions, "Scenario interactions");
        return write(
                "Create operational activity scenario",
                () -> {
                    BlockArchitecture layer = capabilityLayer(owner);
                    requireUniqueName(owner.getOwnedScenarios(), label, "scenario");
                    Map<AbstractFunction, InstanceRole> roles = new LinkedHashMap<>();
                    // Complete preflight before constructing any semantic objects or touching the
                    // capability.
                    for (FunctionalExchange exchange : interactions) {
                        requireExchange(exchange, layer);
                        text(exchange.getName(), "Scenario interaction name", 2400);
                        for (AbstractFunction activity :
                                List.of(source(exchange), target(exchange))) {
                            requireFunction(activity, layer, true);
                            if (owner
                                    .getOwnedAbstractFunctionAbstractCapabilityInvolvements()
                                    .stream()
                                    .noneMatch(link -> link.getInvolved() == activity)) {
                                throw invalid(
                                        "Every scenario activity must already be involved in its"
                                            + " operational capability");
                            }
                            roles.putIfAbsent(activity, null);
                        }
                    }
                    Scenario scenario = named(InteractionFactory.eINSTANCE.createScenario(), label);
                    scenario.setKind(ScenarioKind.INTERACTION);
                    for (var entry : roles.entrySet()) {
                        InstanceRole role =
                                named(
                                        InteractionFactory.eINSTANCE.createInstanceRole(),
                                        entry.getKey().getName());
                        role.setRepresentedInstance(entry.getKey());
                        scenario.getOwnedInstanceRoles().add(role);
                        entry.setValue(role);
                    }
                    for (FunctionalExchange exchange : interactions)
                        addActivityMessage(
                                scenario,
                                exchange,
                                roles.get(source(exchange)),
                                roles.get(target(exchange)));
                    Constraint before = condition("Precondition", pre, scenario),
                            after = condition("Postcondition", post, scenario);
                    scenario.getOwnedConstraints().addAll(List.of(before, after));
                    scenario.setPreCondition(before);
                    scenario.setPostCondition(after);
                    owner.getOwnedScenarios().add(scenario);
                    return changed(scenario);
                });
    }

    private static void addActivityMessage(
            Scenario scenario,
            FunctionalExchange exchange,
            InstanceRole sender,
            InstanceRole receiver) {
        var message =
                named(InteractionFactory.eINSTANCE.createSequenceMessage(), exchange.getName());
        message.setKind(MessageKind.ASYNCHRONOUS_CALL);
        var sent =
                named(
                        InteractionFactory.eINSTANCE.createEventSentOperation(),
                        "Send " + exchange.getName());
        var received =
                named(
                        InteractionFactory.eINSTANCE.createEventReceiptOperation(),
                        "Receive " + exchange.getName());
        var finished =
                named(
                        InteractionFactory.eINSTANCE.createExecutionEvent(),
                        "Finish " + exchange.getName());
        sent.setOperation(exchange);
        received.setOperation(exchange);
        scenario.getOwnedEvents().addAll(List.of(sent, received, finished));
        var sendingEnd =
                named(
                        InteractionFactory.eINSTANCE.createMessageEnd(),
                        "Send " + exchange.getName());
        sendingEnd.getCoveredInstanceRoles().add(sender);
        sendingEnd.setEvent(sent);
        var receivingEnd =
                named(
                        InteractionFactory.eINSTANCE.createMessageEnd(),
                        "Receive " + exchange.getName());
        receivingEnd.getCoveredInstanceRoles().add(receiver);
        receivingEnd.setEvent(received);
        var executionEnd =
                named(
                        InteractionFactory.eINSTANCE.createExecutionEnd(),
                        "Finish " + exchange.getName());
        executionEnd.getCoveredInstanceRoles().add(receiver);
        executionEnd.setEvent(finished);
        scenario.getOwnedInteractionFragments()
                .addAll(List.of(sendingEnd, receivingEnd, executionEnd));
        message.setSendingEnd(sendingEnd);
        message.setReceivingEnd(receivingEnd);
        scenario.getOwnedMessages().add(message);
        var execution =
                named(
                        InteractionFactory.eINSTANCE.createExecution(),
                        "Execute " + exchange.getName());
        execution.setStart(receivingEnd);
        execution.setFinish(executionEnd);
        scenario.getOwnedTimeLapses().add(execution);
    }

    private static Constraint condition(String name, String body, Scenario scenario) {
        Constraint constraint = named(CapellacoreFactory.eINSTANCE.createConstraint(), name);
        var expression = identify(DatavalueFactory.eINSTANCE.createOpaqueExpression());
        expression.getLanguages().add("capella:linkedText");
        expression.getBodies().add(body);
        constraint.setOwnedSpecification(expression);
        constraint.getConstrainedElements().add(scenario);
        return constraint;
    }

    private BlockArchitecture capabilityLayer(AbstractCapability capability) {
        requireOwned(capability);
        BlockArchitecture layer = architecture(capability);
        if (!(capability instanceof OperationalCapability && layer instanceof OperationalAnalysis
                || capability instanceof Capability && layer instanceof SystemAnalysis
                || capability instanceof CapabilityRealization
                        && (layer instanceof LogicalArchitecture
                                || layer instanceof PhysicalArchitecture))) {
            throw invalid("Capabilities must have the native OA, SA, LA or PA capability type");
        }
        return layer;
    }

    private void requireFunction(AbstractFunction function, BlockArchitecture layer, boolean leaf) {
        requireOwned(function);
        boolean correct =
                layer instanceof OperationalAnalysis && function instanceof OperationalActivity
                        || layer instanceof SystemAnalysis && function instanceof SystemFunction
                        || layer instanceof LogicalArchitecture
                                && function instanceof LogicalFunction
                        || layer instanceof PhysicalArchitecture
                                && function instanceof PhysicalFunction;
        if (architecture(function) != layer || !correct)
            throw invalid("Functions must have matching native types in the same architecture");
        if (leaf && hasDecomposition(function))
            throw invalid(
                    "Chains and activity scenarios require leaf functions without decomposition");
    }

    private static boolean hasDecomposition(AbstractFunction function) {
        if (!function.getOwnedFunctions().isEmpty()) return true;
        // Native FunctionExt.getFirstLevelAbstractFunctions includes functions beneath nested
        // owned FunctionPkgs. Check those stored containments without an Eclipse adapter lookup.
        for (EObject child : function.eContents())
            if (child instanceof FunctionPkg pkg && containsFunction(pkg)) return true;
        return false;
    }

    private static boolean containsFunction(FunctionPkg pkg) {
        for (EObject child : pkg.eContents()) {
            if (child instanceof AbstractFunction
                    || child instanceof FunctionPkg nested && containsFunction(nested)) return true;
        }
        return false;
    }

    private void requireExchange(FunctionalExchange exchange, BlockArchitecture layer) {
        requireOwned(exchange);
        if (architecture(exchange) != layer
                || !(exchange.eContainer() instanceof AbstractFunction)
                || !"ownedFunctionalExchanges".equals(exchange.eContainingFeature().getName())) {
            throw invalid("Exchanges must be stored under a function in the same architecture");
        }
        if (layer instanceof OperationalAnalysis
                && (!(exchange.getSource() instanceof OperationalActivity)
                        || !(exchange.getTarget() instanceof OperationalActivity))) {
            throw invalid(
                    "An operational interaction requires native activity source and target"
                        + " endpoints");
        }
        if (!(layer instanceof OperationalAnalysis)
                && (!(exchange.getSource() instanceof FunctionOutputPort)
                        || !(exchange.getTarget() instanceof FunctionInputPort))) {
            throw invalid("A functional exchange requires a native output port and input port");
        }
        requireFunction(source(exchange), layer, true);
        requireFunction(target(exchange), layer, true);
    }

    private void requireOwned(EObject object) {
        requireSession();
        if (object == null
                || object.eIsProxy()
                || object.eResource() == null
                || object.eResource().getResourceSet()
                        != session.getTransactionalEditingDomain().getResourceSet()
                || !session.getSemanticResources().contains(object.eResource())
                || session.getTransactionalEditingDomain().isReadOnly(object.eResource())) {
            throw invalid(
                    "Behavior operations require writable elements from the captured semantic"
                        + " session");
        }
    }

    private void requireSession() {
        if (session == null
                || !session.isOpen()
                || session.getTransactionalEditingDomain() == null) {
            throw new IllegalStateException(
                    "An open captured Capella session is required for behavior operations");
        }
    }

    private static AbstractFunction source(FunctionalExchange exchange) {
        EObject source = exchange.getSource();
        return source instanceof AbstractFunction function
                ? function
                : source instanceof FunctionOutputPort
                                && source.eContainer() instanceof AbstractFunction function
                        ? function
                        : null;
    }

    private static AbstractFunction target(FunctionalExchange exchange) {
        EObject target = exchange.getTarget();
        return target instanceof AbstractFunction function
                ? function
                : target instanceof FunctionInputPort
                                && target.eContainer() instanceof AbstractFunction function
                        ? function
                        : null;
    }

    private static BlockArchitecture architecture(EObject object) {
        for (EObject owner = object; owner != null; owner = owner.eContainer())
            if (owner instanceof BlockArchitecture layer) return layer;
        throw invalid("Behavior elements must belong to an Arcadia architecture");
    }

    private static boolean below(EObject object, EObject ancestor) {
        for (EObject owner = object.eContainer(); owner != null; owner = owner.eContainer())
            if (owner == ancestor) return true;
        return false;
    }

    private static int level(BlockArchitecture layer) {
        if (layer instanceof OperationalAnalysis) return 0;
        if (layer instanceof SystemAnalysis) return 1;
        if (layer instanceof LogicalArchitecture) return 2;
        if (layer instanceof PhysicalArchitecture) return 3;
        return -1;
    }

    private static boolean componentMatches(Component component, BlockArchitecture layer) {
        return layer instanceof OperationalAnalysis && component instanceof Entity
                || layer instanceof SystemAnalysis && component instanceof SystemComponent
                || layer instanceof LogicalArchitecture && component instanceof LogicalComponent
                || layer instanceof PhysicalArchitecture && component instanceof PhysicalComponent;
    }

    private static <T> List<T> ordered(List<? extends T> elements, String label) {
        if (elements == null || elements.isEmpty() || elements.size() > MAX_ITEMS)
            throw invalid(label + " require 1.." + MAX_ITEMS + " ordered elements");
        Set<T> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<T> result = new ArrayList<>();
        for (T element : elements) {
            if (element == null || !seen.add(element))
                throw invalid(label + " cannot contain null or duplicate elements");
            result.add(element);
        }
        return List.copyOf(result);
    }

    private static <T> T unique(List<T> links, String kind) {
        if (links.size() > 1)
            throw invalid("Duplicate existing " + kind + " links require model repair");
        return links.isEmpty() ? null : links.get(0);
    }

    private static void requireUniqueName(
            List<? extends NamedElement> elements, String name, String kind) {
        if (elements.stream().anyMatch(element -> name.equals(element.getName())))
            throw invalid("A " + kind + " with this name already exists");
    }

    private static String text(String value, String label, int limit) {
        if (value == null || value.isBlank() || value.length() > limit || value.indexOf('\0') >= 0)
            throw invalid(label + " must be nonblank, bounded text without null characters");
        return value.strip();
    }

    private static <T extends CapellaElement> T identify(T element) {
        // Assign persistent IDs while detached; attached intrinsic IDs must never bypass
        // XMLResource's ID cache.
        element.setId(UUID.randomUUID().toString());
        return element;
    }

    private static <T extends NamedElement> T named(T element, String name) {
        identify(element);
        element.setName(name);
        return element;
    }

    private <T> T write(String label, Supplier<T> operation) {
        requireSession();
        return SessionSafeguards.runWrite(
                session,
                label,
                () -> {
                    requireSession();
                    return operation.get();
                });
    }

    private <T extends EObject> T changed(T result) {
        ModelSummary.invalidate(session);
        ModelIndex.invalidate(session);
        ModelAnalytics.invalidate(session);
        try {
            SessionSafeguards.requestSave(session);
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to save Arcadia behavior changes", failure);
        }
        return result;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
