package org.polarsys.capella.ai.session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.viewpoint.DRepresentation;
import org.polarsys.capella.ai.Activator;
import org.polarsys.capella.ai.llm.ToolRouter;
import org.polarsys.capella.ai.llm.ToolRouter.PlanExecutionResult;
import org.polarsys.capella.ai.llm.ToolRouter.ToolPlan;
import org.polarsys.capella.ai.preferences.AdvancedPreferences;
import org.polarsys.capella.ai.telemetry.Telemetry;

/**
 * Orchestrates long-running plan applications step-by-step with pause/resume/cancel and checkpoints.
 */
public final class PlanOrchestrator {
    private PlanOrchestrator() {}

    public static Handle start(Session session, DRepresentation rep, ToolPlan plan) {
        OrchestrationState state = new OrchestrationState(session, rep, plan);
        OrchestrationJob job = new OrchestrationJob(state);
        job.schedule();
        return new Handle(state, job);
    }

    public static final class Handle {
        private final OrchestrationState state;
        private final OrchestrationJob job;
        Handle(OrchestrationState state, OrchestrationJob job) { this.state = state; this.job = job; }
        public void pause() { state.paused.set(true); }
        public void resume() { state.paused.set(false); job.schedule(100L); }
        public void cancel() { state.cancelled.set(true); job.cancel(); }
        public int applied() { return state.applied; }
        public int remaining() { return Math.max(0, state.plan.size() - state.applied); }
        public String checkpointPath() { return state.checkpointPath.toString(); }
    }

    private static final class OrchestrationState {
        final Session session; final DRepresentation rep; final ToolPlan plan;
        final AtomicBoolean paused = new AtomicBoolean(false);
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final Path checkpointPath;
        int applied = 0;
        OrchestrationState(Session s, DRepresentation r, ToolPlan p) {
            this.session = s; this.rep = r; this.plan = p;
            Path dir = Paths.get(System.getProperty("user.home"), ".capella-ai", "checkpoints");
            try { if (!Files.exists(dir)) Files.createDirectories(dir); } catch (IOException ignore) {}
            String id = Telemetry.getCorrelationId();
            if (id == null || id.isBlank()) id = String.valueOf(System.currentTimeMillis());
            this.checkpointPath = dir.resolve("orchestrate-" + id + ".json");
        }
    }

    private static final class OrchestrationJob extends Job {
        private final OrchestrationState state;
        private long lastSave = 0L;
        OrchestrationJob(OrchestrationState st) {
            super("Apply AI Plan (orchestrated)");
            this.state = st;
            setUser(true);
        }
        @Override
        protected IStatus run(IProgressMonitor monitor) {
            if (state.cancelled.get()) return Status.CANCEL_STATUS;
            if (state.paused.get()) {
                schedule(250L);
                return Status.OK_STATUS;
            }
            List<String> errors = new ArrayList<>();
            int appliedNow = 0;
            int autosaveSec = Math.max(5, AdvancedPreferences.getAutoSaveIntervalSec());
            int budget = Math.max(1, AdvancedPreferences.getMaxPlanActions());
            for (int i = state.applied; i < state.plan.size(); i++) {
                if (i - state.applied >= 5) break; // apply in small batches
                if (i >= budget) { errors.add("budget: plan exceeds max actions"); break; }
                if (monitor.isCanceled() || state.cancelled.get()) return Status.CANCEL_STATUS;
                try {
                    boolean ok = ToolRouter.applyPlanStep(state.session, state.rep, state.plan, i);
                    if (ok) {
                        appliedNow++;
                    } else {
                        errors.add("failed at step " + i);
                        // Stop on first failure
                        handleBatchFailureRollback(appliedNow);
                        state.cancelled.set(true);
                        break;
                    }
                } catch (Exception ex) {
                    errors.add("error at step " + i + ": " + ex.getMessage());
                    handleBatchFailureRollback(appliedNow);
                    state.cancelled.set(true);
                    break;
                }
                state.applied++;
                writeCheckpoint(state);
                long now = System.currentTimeMillis();
                if (now - lastSave > autosaveSec * 1000L) {
                    lastSave = now;
                    try { SessionSafeguards.requestSave(state.session); } catch (Exception ignore) {}
                }
            }
            if (state.applied < state.plan.size() && !state.cancelled.get()) {
                schedule(200L);
            }
            return Status.OK_STATUS;
        }

        private void handleBatchFailureRollback(int appliedInBatch) {
            try {
                if (appliedInBatch <= 0) return;
                if (!AdvancedPreferences.isOrchestratorRollbackOnFailureEnabled()) return;
                var domain = state.session != null ? state.session.getTransactionalEditingDomain() : null;
                if (domain != null && domain.getCommandStack() != null) {
                    for (int k = 0; k < appliedInBatch; k++) {
                        if (domain.getCommandStack().canUndo()) {
                            domain.getCommandStack().undo();
                        }
                    }
                }
            } catch (Exception ignore) {}
        }

    }

    private static void writeCheckpoint(OrchestrationState s) {
        try {
            String json = String.format("{\"applied\":%d,\"total\":%d,\"path\":\"%s\"}\n", s.applied, s.plan.size(), s.checkpointPath.toString());
            Files.writeString(s.checkpointPath, json, StandardCharsets.UTF_8);
        } catch (IOException ignore) {}
    }

    // No ToolPlan proxy needed; we apply steps via ToolRouter.applyPlanStep
}
