package org.polarsys.capella.ai.scripting;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.model.ModelSummary;
import org.polarsys.capella.ai.session.SessionSafeguards;
import org.polarsys.capella.common.data.modellingcore.AbstractTrace;
import org.polarsys.capella.common.data.modellingcore.TraceableElement;
import org.polarsys.capella.core.data.capellacore.CapellaElement;
import org.polarsys.capella.core.data.cs.BlockArchitecture;
import org.polarsys.capella.core.data.cs.Component;
import org.polarsys.capella.core.data.cs.Part;
import org.polarsys.capella.core.data.fa.AbstractFunction;
import org.polarsys.capella.core.data.fa.ComponentExchange;
import org.polarsys.capella.core.data.fa.ComponentExchangeEnd;
import org.polarsys.capella.core.data.fa.ComponentExchangeRealization;
import org.polarsys.capella.core.data.fa.ComponentExchangeKind;
import org.polarsys.capella.core.data.fa.ComponentPort;
import org.polarsys.capella.core.data.fa.ComponentPortKind;
import org.polarsys.capella.core.data.fa.FaFactory;
import org.polarsys.capella.core.data.fa.FunctionInputPort;
import org.polarsys.capella.core.data.fa.FunctionOutputPort;
import org.polarsys.capella.core.data.fa.FunctionalExchange;
import org.polarsys.capella.core.data.fa.FunctionalExchangeRealization;
import org.polarsys.capella.core.data.fa.OrientationPortKind;
import org.polarsys.capella.core.data.information.InformationFactory;
import org.polarsys.capella.core.data.information.Port;
import org.polarsys.capella.core.data.information.PortRealization;
import org.polarsys.capella.core.data.oa.OperationalActivity;

/** Stored, directed interface realizations between adjacent Arcadia architectures. */
public final class ArcadiaInterfaceAPI {
    private final Session session;
    public ArcadiaInterfaceAPI(Session session) { this.session = session; }

    public FunctionalExchangeRealization realizeFunctionalExchange(FunctionalExchange lower, FunctionalExchange upper) {
        return write("Realize functional exchange", () -> {
            functionalEndpoints(lower, upper);
            return trace(lower.getOwnedFunctionalExchangeRealizations(), lower, upper,
                () -> FaFactory.eINSTANCE.createFunctionalExchangeRealization());
        });
    }

    public ComponentExchangeRealization realizeComponentExchange(ComponentExchange lower, ComponentExchange upper) {
        return write("Realize component exchange", () -> {
            componentEndpoints(lower, upper);
            return trace(lower.getOwnedComponentExchangeRealizations(), lower, upper,
                () -> FaFactory.eINSTANCE.createComponentExchangeRealization());
        });
    }

    public PortRealization realizePort(Port lower, Port upper) {
        return write("Realize interface port", () -> {
            ports(lower, upper);
            return trace(lower.getOwnedPortRealizations(), lower, upper,
                () -> InformationFactory.eINSTANCE.createPortRealization());
        });
    }

    /** Realize the exchange and, where both layers have ports, both endpoint ports atomically. */
    public EObject realizeInterface(EObject lower, EObject upper) {
        return write("Realize exchange and endpoint ports", () -> {
            EObject sourceLower, sourceUpper, targetLower, targetUpper;
            AbstractTrace relation;
            boolean changed;
            if (lower instanceof FunctionalExchange low && upper instanceof FunctionalExchange high) {
                functionalEndpoints(low, high);
                sourceLower = raw(low, "source"); sourceUpper = raw(high, "source");
                targetLower = raw(low, "target"); targetUpper = raw(high, "target");
                preflightPorts(sourceLower, sourceUpper, targetLower, targetUpper);
                Change<FunctionalExchangeRealization> result = trace(low.getOwnedFunctionalExchangeRealizations(), low, high,
                    () -> FaFactory.eINSTANCE.createFunctionalExchangeRealization());
                relation = result.value(); changed = result.changed();
            } else if (lower instanceof ComponentExchange low && upper instanceof ComponentExchange high) {
                componentEndpoints(low, high);
                sourceLower = componentPort(low, "source"); sourceUpper = componentPort(high, "source");
                targetLower = componentPort(low, "target"); targetUpper = componentPort(high, "target");
                preflightPorts(sourceLower, sourceUpper, targetLower, targetUpper);
                Change<ComponentExchangeRealization> result = trace(low.getOwnedComponentExchangeRealizations(), low, high,
                    () -> FaFactory.eINSTANCE.createComponentExchangeRealization());
                relation = result.value(); changed = result.changed();
            } else {
                throw new IllegalArgumentException("Interface realization requires two functional exchanges or two component exchanges");
            }
            if (sourceLower instanceof Port a && sourceUpper instanceof Port b &&
                targetLower instanceof Port c && targetUpper instanceof Port d) {
                changed |= trace(a.getOwnedPortRealizations(), a, b, () -> InformationFactory.eINSTANCE.createPortRealization()).changed();
                changed |= trace(c.getOwnedPortRealizations(), c, d, () -> InformationFactory.eINSTANCE.createPortRealization()).changed();
            }
            return new Change<>(relation, changed);
        });
    }

    private void preflightPorts(EObject a, EObject b, EObject c, EObject d) {
        if (a instanceof Port lowSource && b instanceof Port highSource && c instanceof Port lowTarget && d instanceof Port highTarget) {
            ports(lowSource, highSource); ports(lowTarget, highTarget);
            existing(lowSource.getOwnedPortRealizations(), lowSource, highSource);
            existing(lowTarget.getOwnedPortRealizations(), lowTarget, highTarget);
        } else if (!(b instanceof OperationalActivity && d instanceof OperationalActivity &&
            a instanceof FunctionOutputPort && c instanceof FunctionInputPort)) {
            throw new IllegalArgumentException("Both exchanges require compatible native source and target endpoints");
        }
    }

    private void functionalEndpoints(FunctionalExchange lower, FunctionalExchange upper) {
        adjacent(lower, upper);
        ownerRealizes(functionEndpoint(lower, "source"), functionEndpoint(upper, "source"), "ownedFunctionRealizations");
        ownerRealizes(functionEndpoint(lower, "target"), functionEndpoint(upper, "target"), "ownedFunctionRealizations");
    }

    private AbstractFunction functionEndpoint(FunctionalExchange exchange, String side) {
        EObject endpoint = raw(exchange, side); owned(endpoint);
        if (endpoint instanceof OperationalActivity activity && architecture(exchange) == architecture(activity)) {
            nativeFunction(activity); return activity;
        }
        if (("source".equals(side) && endpoint instanceof FunctionOutputPort ||
            "target".equals(side) && endpoint instanceof FunctionInputPort) && endpoint.eContainer() instanceof AbstractFunction function &&
            architecture(exchange) == architecture(function)) {
            nativeFunction(function); attachedPort((Port) endpoint); return function;
        }
        throw new IllegalArgumentException("Functional exchange endpoints must be native activities or correctly directed function ports");
    }

    private void componentEndpoints(ComponentExchange lower, ComponentExchange upper) {
        adjacent(lower, upper);
        if (lower.getKind() != ComponentExchangeKind.FLOW || upper.getKind() != ComponentExchangeKind.FLOW) {
            throw new IllegalArgumentException("This interface realization helper requires two native FLOW component exchanges");
        }
        ports(componentPort(lower, "source"), componentPort(upper, "source"));
        ports(componentPort(lower, "target"), componentPort(upper, "target"));
    }

    private ComponentPort componentPort(ComponentExchange exchange, String side) {
        EObject endpoint = raw(exchange, side); owned(endpoint);
        if (endpoint instanceof ComponentExchangeEnd end) {
            if (end.eContainer() != exchange || end.eContainingFeature() == null ||
                !"ownedComponentExchangeEnds".equals(end.eContainingFeature().getName()) || !(raw(end, "part") instanceof Part part) ||
                !(raw(end, "port") instanceof ComponentPort port)) throw new IllegalArgumentException("An instance exchange end needs its owned Part and port");
            owned(part); owned(port);
            if (raw(part, "abstractType") != port.eContainer() || architecture(part) != architecture(exchange)) {
                throw new IllegalArgumentException("The instance Part must have the exact type that owns its port");
            }
            endpoint = port;
        }
        if (!(endpoint instanceof ComponentPort port) || !(port.eContainer() instanceof Component) ||
            architecture(port) != architecture(exchange)) throw new IllegalArgumentException("Component exchange endpoints require contained component ports");
        attachedPort(port);
        if (port.getKind() != ComponentPortKind.FLOW ||
            "source".equals(side) && port.getOrientation() != OrientationPortKind.OUT && port.getOrientation() != OrientationPortKind.INOUT ||
            "target".equals(side) && port.getOrientation() != OrientationPortKind.IN && port.getOrientation() != OrientationPortKind.INOUT) {
            throw new IllegalArgumentException("FLOW exchanges require an OUT/INOUT source port and an IN/INOUT target port");
        }
        return port;
    }

    private void ports(Port lower, Port upper) {
        adjacent(lower, upper);
        attachedPort(lower); attachedPort(upper);
        if (lower instanceof FunctionInputPort && upper instanceof FunctionInputPort ||
            lower instanceof FunctionOutputPort && upper instanceof FunctionOutputPort) {
            if (!(lower.eContainer() instanceof AbstractFunction) || !(upper.eContainer() instanceof AbstractFunction)) {
                throw new IllegalArgumentException("Function ports must be contained by native functions");
            }
            ownerRealizes(lower.eContainer(), upper.eContainer(), "ownedFunctionRealizations");
        } else if (lower instanceof ComponentPort a && upper instanceof ComponentPort b) {
            if (!(a.eContainer() instanceof Component) || !(b.eContainer() instanceof Component) ||
                a.getKind() != b.getKind() || a.getOrientation() != b.getOrientation()) {
                throw new IllegalArgumentException("Component ports require matching kind, orientation and native component owners");
            }
            ownerRealizes(a.eContainer(), b.eContainer(), "ownedComponentRealizations");
        } else throw new IllegalArgumentException("Port realization requires matching input ports, output ports or component ports");
    }

    private void nativeFunction(AbstractFunction function) {
        String expected = switch (architecture(function).eClass().getName()) {
            case "OperationalAnalysis" -> "OperationalActivity"; case "SystemAnalysis" -> "SystemFunction";
            case "LogicalArchitecture" -> "LogicalFunction"; case "PhysicalArchitecture" -> "PhysicalFunction";
            default -> "unsupported";
        };
        if (!expected.equals(function.eClass().getName())) throw new IllegalArgumentException("Function classifier must match its native architecture");
    }

    private void attachedPort(Port port) {
        String feature = port.eContainingFeature() == null ? "" : port.eContainingFeature().getName();
        if (port instanceof FunctionInputPort && "inputs".equals(feature) || port instanceof FunctionOutputPort && "outputs".equals(feature)) {
            if (!(port.eContainer() instanceof AbstractFunction function)) throw new IllegalArgumentException("Function ports require native function ownership");
            nativeFunction(function);
        } else if (port instanceof ComponentPort && "ownedFeatures".equals(feature) && port.eContainer() instanceof Component component) {
            String expected = switch (architecture(component).eClass().getName()) {
                case "SystemAnalysis" -> "SystemComponent"; case "LogicalArchitecture" -> "LogicalComponent";
                case "PhysicalArchitecture" -> "PhysicalComponent"; default -> "unsupported";
            };
            if (!expected.equals(component.eClass().getName())) throw new IllegalArgumentException("Component classifier must match its native architecture");
        } else throw new IllegalArgumentException("Ports must use their native inputs, outputs or ownedFeatures containment");
    }

    private void ownerRealizes(EObject lower, EObject upper, String feature) {
        owned(lower); owned(upper);
        Object values = lower.eGet(lower.eClass().getEStructuralFeature(feature), false);
        if (!(values instanceof List<?> relations) || existing(relations, lower, upper) == null) {
            throw new IllegalArgumentException("Exchange endpoint owners must already have one correctly directed native realization");
        }
    }

    private AbstractTrace existing(List<?> values, EObject lower, EObject upper) {
        if (values.size() > 4096) throw new IllegalArgumentException("Native realization lookup exceeds the bounded relationship budget");
        AbstractTrace found = null;
        for (Object value : values) {
            if (SessionSafeguards.isScriptCancellationRequested() || Thread.currentThread().isInterrupted()) {
                throw new SessionSafeguards.ScriptCancelledException("Interface realization was cancelled or timed out");
            }
            if (!(value instanceof AbstractTrace relation)) throw new IllegalArgumentException("Expected a native stored realization");
            owned(relation);
            if (relation.eContainer() != lower || raw(relation, "sourceElement") != lower) {
                throw new IllegalArgumentException("A realization must be owned by its exact source element");
            }
            EObject target = raw(relation, "targetElement"); owned(target);
            if (!(lower instanceof FunctionalExchange && target instanceof FunctionalExchange ||
                lower instanceof ComponentExchange && target instanceof ComponentExchange ||
                lower instanceof Port && target instanceof Port && lower.eClass() == target.eClass() ||
                lower instanceof AbstractFunction && target instanceof AbstractFunction ||
                lower instanceof Component && target instanceof Component)) {
                throw new IllegalArgumentException("Stored realization target must have the native classifier required by its source");
            }
            if (target == upper) {
                if (found != null) throw new IllegalArgumentException("Duplicate native realizations must be corrected explicitly");
                found = relation;
            }
        }
        return found;
    }

    @SuppressWarnings("unchecked")
    private <T extends AbstractTrace> Change<T> trace(List<T> values, TraceableElement lower, TraceableElement upper, Supplier<T> factory) {
        T prior = (T) existing(values, lower, upper);
        if (prior != null) return new Change<>(prior, false);
        T relation = factory.get(); ((CapellaElement) relation).setId(UUID.randomUUID().toString());
        relation.setSourceElement(lower); relation.setTargetElement(upper); values.add(relation);
        return new Change<>(relation, true);
    }

    private void adjacent(EObject lower, EObject upper) {
        BlockArchitecture low = architecture(lower), high = architecture(upper);
        if (low.eContainer() != high.eContainer() || level(low) != level(high) + 1) {
            throw new IllegalArgumentException("Realization must be lower-to-upper in one model: SA to OA, LA to SA, or PA to LA");
        }
    }
    private int level(BlockArchitecture value) {
        return switch (value.eClass().getName()) { case "OperationalAnalysis" -> 0; case "SystemAnalysis" -> 1;
            case "LogicalArchitecture" -> 2; case "PhysicalArchitecture" -> 3; default -> -100; };
    }
    private BlockArchitecture architecture(EObject value) {
        owned(value);
        for (EObject owner = value; owner != null; owner = owner.eContainer()) if (owner instanceof BlockArchitecture architecture) return architecture;
        throw new IllegalArgumentException("Endpoint must belong to a native Arcadia architecture");
    }
    private void owned(EObject value) {
        requireSession();
        if (value == null || value.eIsProxy() || value.eResource() == null ||
            value.eResource().getResourceSet() != session.getTransactionalEditingDomain().getResourceSet() ||
            !session.getSemanticResources().contains(value.eResource())) throw new IllegalArgumentException("Resolved semantic endpoints in the captured session are required");
    }
    private void requireSession() {
        if (session == null || !session.isOpen() || session.getTransactionalEditingDomain() == null) throw new IllegalStateException("An open native Capella session is required");
    }
    private static EObject raw(EObject value, String name) {
        var feature = value.eClass().getEStructuralFeature(name);
        Object result = feature == null ? null : value.eGet(feature, false);
        return result instanceof EObject object ? object : null;
    }
    private record Change<T>(T value, boolean changed) {}
    private <T> T write(String label, Supplier<Change<T>> action) {
        requireSession();
        return SessionSafeguards.runWrite(session, label, () -> {
            requireSession(); Change<T> result = action.get();
            if (result.changed()) {
                ModelSummary.invalidate(session);
                try { SessionSafeguards.requestSave(session); }
                catch (Exception failure) { throw new IllegalStateException("Could not save native interface realizations", failure); }
            }
            return result.value();
        });
    }
}
