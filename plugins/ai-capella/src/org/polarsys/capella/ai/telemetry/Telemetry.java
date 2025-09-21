package org.polarsys.capella.ai.telemetry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.polarsys.capella.ai.Activator;
import org.polarsys.capella.ai.llm.ToolRouter.PlanExecutionResult;
import org.polarsys.capella.ai.llm.ToolRouter.PlanItem;
import org.polarsys.capella.ai.llm.ToolRouter.ToolPlan;
import org.polarsys.capella.ai.preferences.LLMPreferences;

/** Lightweight opt-in telemetry for understanding assistant usage patterns. */
public final class Telemetry {
    private static final Logger LOG = Logger.getLogger(Telemetry.class.getName());

    private Telemetry() {}

    public static void recordPlanSuggested(ToolPlan plan) {
        if (!isEnabled() || plan == null || plan.isEmpty()) {
            return;
        }
        Map<String, Long> counts = plan.items().stream()
            .collect(Collectors.groupingBy(PlanItem::getTypeId, LinkedHashMap::new, Collectors.counting()));
        StringJoiner joiner = new StringJoiner(", ");
        counts.forEach((type, count) -> joiner.add(type + "=" + count));
        logInfo("plan-suggested", String.format("size=%d actions=[%s]", plan.size(), joiner));
    }

    public static void recordPlanExecution(ToolPlan plan, PlanExecutionResult result) {
        if (!isEnabled() || plan == null || result == null) {
            return;
        }
        Map<String, Long> counts = plan.items().stream()
            .collect(Collectors.groupingBy(PlanItem::getTypeId, LinkedHashMap::new, Collectors.counting()));
        String actionSummary = counts.isEmpty()
            ? "none"
            : counts.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
        String errorSummary = result.errors.isEmpty()
            ? "none"
            : result.errors.stream().limit(3).collect(Collectors.joining(" | "));
        logInfo("plan-executed", String.format("size=%d applied=%d errors=%d actions=[%s] details=%s",
                plan.size(), result.applied, result.errors.size(), actionSummary, errorSummary));
    }

    public static void recordPlanError(String context, String message) {
        if (!isEnabled() || message == null || message.isBlank()) {
            return;
        }
        logWarning("plan-error", context + " → " + message);
    }

    private static boolean isEnabled() {
        return LLMPreferences.isTelemetryEnabled();
    }

    private static void logInfo(String category, String message) {
        log(IStatus.INFO, category, message);
    }

    private static void logWarning(String category, String message) {
        log(IStatus.WARNING, category, message);
    }

    private static void log(int severity, String category, String message) {
        String payload = String.format("[%s] %s", category, message);
        if (Activator.getDefault() != null) {
            Activator.getDefault().getLog().log(new Status(severity, Activator.PLUGIN_ID, payload));
        } else {
            Level level = severity == IStatus.INFO ? Level.INFO : Level.WARNING;
            LOG.log(level, payload);
        }
    }
}
