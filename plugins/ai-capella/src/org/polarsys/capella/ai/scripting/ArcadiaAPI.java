package org.polarsys.capella.ai.scripting;

import java.util.List;
import java.util.function.Supplier;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.core.data.capellacore.CapellaElement;
import org.polarsys.capella.core.data.capellacore.Constraint;
import org.polarsys.capella.core.data.cs.Component;
import org.polarsys.capella.core.data.cs.Part;
import org.polarsys.capella.core.data.cs.PhysicalLink;
import org.polarsys.capella.core.data.cs.PhysicalPort;
import org.polarsys.capella.core.data.fa.AbstractFunction;
import org.polarsys.capella.core.data.fa.ComponentExchange;
import org.polarsys.capella.core.data.fa.ComponentExchangeFunctionalExchangeAllocation;
import org.polarsys.capella.core.data.fa.FunctionalChain;
import org.polarsys.capella.core.data.fa.FunctionalExchange;
import org.polarsys.capella.core.data.interaction.AbstractCapability;
import org.polarsys.capella.core.data.interaction.Scenario;
import org.polarsys.capella.core.data.oa.OperationalCapability;
import org.polarsys.capella.core.data.pa.deployment.PartDeploymentLink;

/** Native Arcadia behaviors and physical/interface structure for reviewed scripts. */
public final class ArcadiaAPI {
    private final ArcadiaBehaviorAPI behavior;
    private final ArcadiaStructureAPI structure;
    private final ArcadiaInterfaceAPI interfaces;
    private final ArcadiaConstraintAPI constraints;
    private final Runnable cacheChanged;

    public ArcadiaAPI(Session session) { this(session, () -> {}); }

    ArcadiaAPI(Session session, Runnable cacheChanged) {
        behavior = new ArcadiaBehaviorAPI(session);
        structure = new ArcadiaStructureAPI(session);
        interfaces = new ArcadiaInterfaceAPI(session);
        constraints = new ArcadiaConstraintAPI(session);
        this.cacheChanged = cacheChanged;
    }

    public EObject involveFunction(AbstractCapability capability, AbstractFunction function) {
        return change(() -> behavior.involveFunction(capability, function));
    }

    public EObject involveComponent(AbstractCapability capability, Component component) {
        return change(() -> behavior.involveComponent(capability, component));
    }

    public EObject involveChain(AbstractCapability capability, FunctionalChain chain) {
        return change(() -> behavior.involveChain(capability, chain));
    }

    public EObject realizeCapability(AbstractCapability lower, AbstractCapability upper) {
        return change(() -> behavior.realizeCapability(lower, upper));
    }

    public FunctionalChain linearChain(AbstractFunction owner, String name,
        List<? extends AbstractFunction> functions, List<? extends FunctionalExchange> exchanges) {
        return change(() -> behavior.linearChain(owner, name, functions, exchanges));
    }

    public Scenario operationalScenario(OperationalCapability owner, String name,
        List<? extends FunctionalExchange> orderedInteractions, String precondition, String postcondition) {
        return change(() -> behavior.operationalScenario(owner, name, orderedInteractions, precondition, postcondition));
    }

    public PartDeploymentLink deploy(Part behaviorPart, Part nodePart) {
        return change(() -> structure.deploy(behaviorPart, nodePart));
    }

    public PhysicalLink physicalLink(String name, Part sourcePart, PhysicalPort sourcePort,
        Part targetPart, PhysicalPort targetPort) {
        return change(() -> structure.physicalLink(name, sourcePart, sourcePort, targetPart, targetPort));
    }

    public PhysicalLink physicalLink(String name, Part sourcePart, Part targetPart) {
        return change(() -> structure.physicalLink(name, sourcePart, targetPart));
    }

    public ComponentExchangeFunctionalExchangeAllocation allocateExchange(ComponentExchange transport, FunctionalExchange flow) {
        return change(() -> structure.allocateExchange(transport, flow));
    }

    /** The interface carries the exchange item (ExchangeItemAllocation); repeating it changes nothing. */
    public org.polarsys.capella.core.data.cs.ExchangeItemAllocation allocateExchangeItem(
            org.polarsys.capella.core.data.cs.Interface carrier, org.polarsys.capella.core.data.information.ExchangeItem item) {
        return change(() -> interfaces.allocateExchangeItem(carrier, item));
    }

    /** The functional exchange conveys the exchange item; repeating it changes nothing. */
    public FunctionalExchange exchangeItem(FunctionalExchange exchange, org.polarsys.capella.core.data.information.ExchangeItem item) {
        return change(() -> interfaces.exchangeItem(exchange, item));
    }

    public EObject realizeInterface(EObject lowerExchange, EObject upperExchange) {
        return change(() -> interfaces.realizeInterface(lowerExchange, upperExchange));
    }

    public Constraint constraint(CapellaElement owner, String name, String expression, List<? extends EObject> subjects) {
        return change(() -> constraints.constraint(owner, name, expression, subjects));
    }

    private <T> T change(Supplier<T> operation) {
        try { return operation.get(); }
        finally { cacheChanged.run(); }
    }
}
