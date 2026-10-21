package org.polarsys.capella.ai.scripting;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.common.ef.ExecutionManager;
import org.polarsys.capella.common.ef.command.ICommand;
import org.polarsys.capella.common.helpers.TransactionHelper;
import org.polarsys.capella.common.helpers.operations.LongRunningListenersRegistry;

import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.logging.Logger;

/**
 * API for high-level Capella engineering operations (Transitions, REC/RPL). Wraps complex internal
 * transition engines.
 */
public final class OpsAPI {

    private static final Logger LOG = Logger.getLogger(OpsAPI.class.getName());
    private final Session session;

    public OpsAPI(Session session) {
        this.session = session;
    }

    private static final String HELPER =
            "org.polarsys.capella.core.transition.system.topdown.commands.TransitionCommandHelper";

    /** Each kind's TransitionCommandHelper method, as Capella's Transitions menu runs it. */
    private static final Map<String, String> KINDS =
            Map.ofEntries(
                    Map.entry("la2pa", "getLC2PCTransitionCommand"),
                    Map.entry("pa2epbs", "getPC2CITransitionCommand"),
                    Map.entry("functional", "getFunctionalTransitionCommand"),
                    Map.entry("capability", "getCapabilityTransitionCommand"),
                    Map.entry("actor", "getActorTransitionCommand"),
                    Map.entry("system", "getSystemTransitionCommand"),
                    Map.entry("data", "getDataTransitionCommand"),
                    Map.entry("exchange-item", "getExchangeItemTransitionCommand"),
                    Map.entry("interface", "getInterfaceTransitionCommand"),
                    Map.entry("state-machine", "getStateMachineTransitionCommand"),
                    Map.entry("property-value", "getPropertyValueTransitionCommand"),
                    Map.entry("entity-to-actor", "getOE2ActorTransitionCommand"),
                    Map.entry("entity-to-system", "getOE2SystemTransitionCommand"));

    /**
     * Run Capella's transition of the selected elements to the next perspective, with its
     * realization links. "OA2SA" and "SA2LA" pick the functional, capability or system transition
     * from the first element; the other kinds name the transition (see {@link #KINDS}).
     */
    public boolean transition(Collection<EObject> selection, String kind) {
        if (session == null || selection == null || selection.isEmpty()) {
            return false;
        }
        return triggerTransition(selection, command(kind, selection.iterator().next()));
        }

    public static String command(String kind, EObject first) {
        String k = kind == null ? "" : kind.toLowerCase(Locale.ROOT);
        if (k.equals("sa2la") || k.equals("oa2sa")) {
            String type = first == null ? "" : first.eClass().getName();
            return type.contains("Function") || type.contains("Activity")
                    ? "getFunctionalTransitionCommand"
                    : type.contains("Capability")
                            ? "getCapabilityTransitionCommand"
                            : "getSystemTransitionCommand";
        }
        String method = KINDS.get(k);
        if (method == null)
        throw new IllegalArgumentException(
                    "Unknown transition kind: "
                            + kind
                            + ". Supported: OA2SA, SA2LA, LA2PA, PA2EPBS, "
                            + String.join(", ", new TreeSet<>(KINDS.keySet())));
        return method;
    }

    private boolean triggerTransition(Collection<EObject> selection, String methodName) {
        try {
            Class<?> helperClass = Class.forName(HELPER);
            LOG.info("Using transition method: " + methodName);
            var getInstanceMethod = helperClass.getMethod("getInstance");
            Object helperInstance = getInstanceMethod.invoke(null);

            var method =
                    helperClass.getMethod(
                            methodName,
                            java.util.Collection.class,
                            org.eclipse.core.runtime.IProgressMonitor.class);
            Object command =
                    method.invoke(
                            helperInstance,
                            selection,
                            new org.eclipse.core.runtime.NullProgressMonitor());
            if (command instanceof ICommand) {
                return executeCapellaCommand((ICommand) command, selection);
            }
            if (command instanceof org.eclipse.emf.common.command.Command) {
                var emfCommand = (org.eclipse.emf.common.command.Command) command;
                if (emfCommand.canExecute()) {
                    LOG.info("Executing transition command: " + emfCommand.getLabel());
                    session.getTransactionalEditingDomain().getCommandStack().execute(emfCommand);
                    return true;
                } else {
                    LOG.warning("Transition command cannot execute (preconditions failed).");
                }
            }
        } catch (Exception e) {
            LOG.warning("Transition failed: " + e.getMessage());
            throw new RuntimeException("Transition failed: " + e.getMessage(), e);
        }
        return false;
    }

    private boolean executeCapellaCommand(ICommand command, Collection<EObject> selection) {
        Collection<EObject> semantic = selection == null ? Collections.emptyList() : selection;
        ExecutionManager executionManager = TransactionHelper.getExecutionManager(semantic);
        if (executionManager == null) {
            LOG.warning("Transition command cannot execute (no ExecutionManager for selection).");
            return false;
        }
        try {
            LongRunningListenersRegistry.getInstance().operationStarting(getClass());
            executionManager.execute(command);
            return true;
        } finally {
            LongRunningListenersRegistry.getInstance().operationEnded(getClass());
        }
    }
}
