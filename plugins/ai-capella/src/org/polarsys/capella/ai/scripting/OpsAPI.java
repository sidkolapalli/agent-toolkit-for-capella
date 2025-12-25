package org.polarsys.capella.ai.scripting;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.session.SessionSafeguards;

/**
 * API for high-level Capella engineering operations (Transitions, REC/RPL).
 * Wraps complex internal transition engines.
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
     * @param kind transition kind. Supported: "SA2LA" (System to Logical), "OA2SA" (Operational to System).
     * @return true if transition command was triggered
     */
    public boolean transition(Collection<EObject> selection, String kind) {
        if (session == null || selection == null || selection.isEmpty()) {
            return false;
        }

        String k = kind == null ? "" : kind.toUpperCase();
        
        if ("SA2LA".equals(k) || "SYSTEMTOLOGICAL".equalsIgnoreCase(kind)) {
            return triggerTransition(selection, "org.polarsys.capella.core.transition.system.topdown");
        }
        if ("OA2SA".equals(k) || "OPERATIONALTOSYSTEM".equalsIgnoreCase(kind)) {
            // OA to SA usually shares the same topdown engine, but context differs.
            // For now, mapping both to topdown.
            return triggerTransition(selection, "org.polarsys.capella.core.transition.system.topdown");
        }
        
        throw new IllegalArgumentException("Unknown transition kind: " + kind + ". Supported: OA2SA, SA2LA");
    }

    private boolean triggerTransition(Collection<EObject> selection, String packagePrefix) {
        return SessionSafeguards.runWrite(session, "Transition " + packagePrefix, () -> {
            try {
                // Load the helper class via reflection to avoid strict dependency
                Class<?> helperClass = Class.forName(packagePrefix + ".commands.TransitionCommandHelper");
                
                // Method: createCommand(Collection<EObject> selection, Session session)
                var createMethod = helperClass.getMethod("createCommand", Collection.class, Session.class);
                
                // Invoke static method
                Object command = createMethod.invoke(null, selection, session);
                
                if (command instanceof org.eclipse.emf.common.command.Command) {
                    var emfCommand = (org.eclipse.emf.common.command.Command) command;
                    if (emfCommand.canExecute()) {
                        LOG.info("Executing transition command: " + emfCommand.getLabel());
                        emfCommand.execute();
                        return true;
                    } else {
                        LOG.warning("Transition command cannot execute (preconditions failed).");
                    }
                } else if (command == null) {
                    LOG.warning("Transition helper returned null command.");
                }
            } catch (ClassNotFoundException e) {
                LOG.warning("Transition engine not found: " + packagePrefix);
                throw new RuntimeException("Transition engine not installed: " + packagePrefix, e);
            } catch (Exception e) {
                LOG.warning("Failed to trigger transition: " + e.getMessage());
                throw new RuntimeException("Transition failed: " + e.getMessage(), e);
            }
            return false;
        });
    }
}