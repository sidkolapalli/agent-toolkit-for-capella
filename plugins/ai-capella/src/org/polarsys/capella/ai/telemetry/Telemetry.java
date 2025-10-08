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
    private static final ThreadLocal<String> CORRELATION = new ThreadLocal<>();

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

    /**
     * Record a full LLM conversation (inputs and output) for diagnostics.
     * The caller is responsible for redacting any sensitive data.
     */
    public static void recordConversation(String provider,
                                          String api,
                                          String model,
                                          String messages,
                                          String reply,
                                          boolean streaming,
                                          String status,
                                          long durationMs) {
        if (!isEnabled()) {
            return;
        }
        String meta = String.format("provider=%s api=%s model=%s streaming=%s duration_ms=%d status=%s",
                safe(provider), safe(api), safe(model), streaming, durationMs, safe(status));
        String payload = new StringBuilder()
                .append(meta)
                .append(System.lineSeparator())
                .append("-- messages --").append(System.lineSeparator())
                .append(messages == null ? "" : messages)
                .append(System.lineSeparator())
                .append("-- reply --").append(System.lineSeparator())
                .append(reply == null ? "" : reply)
                .toString();
        logInfo("conversation", payload);
    }

    /** Record a single tool call (planned action) execution step. */
    public static void recordToolCall(int index,
                                      String type,
                                      String description,
                                      String status,
                                      String details) {
        if (!isEnabled()) {
            return;
        }
        String msg = String.format("step=%d type=%s status=%s desc=%s details=%s",
                index, safe(type), safe(status), safe(description), safe(details));
        logInfo("tool-call", msg);
    }

    /** Set/clear correlation id to group logs for a single interaction. */
    public static void setCorrelationId(String id) { CORRELATION.set(id); }
    public static void clearCorrelationId() { CORRELATION.remove(); }
    public static String getCorrelationId() { return CORRELATION.get(); }

    private static String safe(String s) {
        return s == null ? "" : s;
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
        String id = getCorrelationId();
        String prefix = (id == null || id.isBlank()) ? category : ("id=" + id + "] [" + category);
        String payload = String.format("[%s] %s", prefix, message);
        if (Activator.getDefault() != null) {
            Activator.getDefault().getLog().log(new Status(severity, Activator.PLUGIN_ID, payload));
        } else {
            Level level = severity == IStatus.INFO ? Level.INFO : Level.WARNING;
            LOG.log(level, payload);
        }
    }
}
