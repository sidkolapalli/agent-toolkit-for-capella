package org.polarsys.capella.ai.scripting;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.common.ef.ExecutionManager;
import org.polarsys.capella.common.ef.command.ICommand;
import org.polarsys.capella.common.helpers.TransactionHelper;
import org.polarsys.capella.common.helpers.operations.LongRunningListenersRegistry;

import java.util.Collection;
import java.util.Collections;
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

    /**
     * Perform a transition (e.g., System to Logical) on the selected elements.
     *
     * @param selection elements to transition
     * @param kind transition kind. Supported: "SA2LA" (System to Logical), "OA2SA" (Operational to
     *     System).
     * @return true if transition command was triggered
     */
    public boolean transition(Collection<EObject> selection, String kind) {
        if (session == null || selection == null || selection.isEmpty()) {
            return false;
        }

        String k = kind == null ? "" : kind.toUpperCase();

        if ("SA2LA".equals(k) || "SYSTEMTOLOGICAL".equalsIgnoreCase(kind)) {
            return triggerTransition(
                    selection, "org.polarsys.capella.core.transition.system.topdown");
        }
        if ("OA2SA".equals(k) || "OPERATIONALTOSYSTEM".equalsIgnoreCase(kind)) {
            // OA to SA usually shares the same topdown engine, but context differs.
            // For now, mapping both to topdown.
            return triggerTransition(
                    selection, "org.polarsys.capella.core.transition.system.topdown");
        }

        throw new IllegalArgumentException(
                "Unknown transition kind: " + kind + ". Supported: OA2SA, SA2LA");
    }

    private boolean triggerTransition(Collection<EObject> selection, String packagePrefix) {
        try {
            Class<?> helperClass =
                    Class.forName(packagePrefix + ".commands.TransitionCommandHelper");

            // Determine the correct method based on selection type
            String methodName = "getSystemTransitionCommand";
            if (!selection.isEmpty()) {
                EObject first = selection.iterator().next();
                String type = first.eClass().getName();
                if (type.contains("Function") || type.contains("Activity")) {
                    methodName = "getFunctionalTransitionCommand";
                } else if (type.contains("Capability")) {
                    methodName = "getCapabilityTransitionCommand";
                }
            }

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
