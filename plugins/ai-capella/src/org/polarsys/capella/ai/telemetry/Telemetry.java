package org.polarsys.capella.ai.telemetry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.polarsys.capella.ai.Activator;
import org.polarsys.capella.ai.preferences.LLMPreferences;
import org.polarsys.capella.ai.scripting.ExecutionResult;

/**
 * Lightweight opt-in telemetry for understanding assistant usage patterns.
 * Updated for code execution architecture (Phase 4).
 */
public final class Telemetry {

    private static final Logger LOG = Logger.getLogger(
        Telemetry.class.getName()
    );
    private static final ThreadLocal<String> CORRELATION = new ThreadLocal<>();
    private static final DateTimeFormatter TS =
        DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

    private Telemetry() {}

    /**
     * Record code execution result.
     */
    public static void recordCodeExecution(
        String script,
        ExecutionResult result
    ) {
        if (!isEnabled() || result == null) {
            return;
        }
        String status = result.success() ? "success" : result.code().name();
        int logCount = result.logs() != null ? result.logs().size() : 0;
        logInfo(
            "code-execution",
            String.format(
                "status=%s duration_ms=%d logs=%d",
                status,
                result.durationMs(),
                logCount
            )
        );
        writeJsonLine(
            "code_execution",
            "status",
            status,
            "duration_ms",
            String.valueOf(result.durationMs()),
            "log_count",
            String.valueOf(logCount),
            "script_length",
            String.valueOf(script != null ? script.length() : 0)
        );
    }

    /**
     * Record code execution error.
     */
    public static void recordCodeError(String context, String message) {
        if (!isEnabled() || message == null || message.isBlank()) {
            return;
        }
        logWarning("code-error", context + " -> " + message);
        writeJsonLine(
            "code_error",
            "context",
            safe(context),
            "message",
            safe(message)
        );
    }

    /**
     * Record a full LLM conversation (inputs and output) for diagnostics.
     * The caller is responsible for redacting any sensitive data.
     */
    public static void recordConversation(
        String provider,
        String api,
        String model,
        String messages,
        String reply,
        boolean streaming,
        String status,
        long durationMs
    ) {
        if (!isEnabled()) {
            return;
        }
        String meta = String.format(
            "provider=%s api=%s model=%s streaming=%s duration_ms=%d status=%s",
            safe(provider),
            safe(api),
            safe(model),
            streaming,
            durationMs,
            safe(status)
        );
        String payload = new StringBuilder()
            .append(meta)
            .append(System.lineSeparator())
            .append("-- messages --")
            .append(System.lineSeparator())
            .append(messages == null ? "" : messages)
            .append(System.lineSeparator())
            .append("-- reply --")
            .append(System.lineSeparator())
            .append(reply == null ? "" : reply)
            .toString();
        logInfo("conversation", payload);
        writeJsonLine(
            "conversation",
            "provider",
            safe(provider),
            "api",
            safe(api),
            "model",
            safe(model),
            "streaming",
            String.valueOf(streaming),
            "duration_ms",
            String.valueOf(durationMs),
            "status",
            safe(status),
            "messages",
            safe(messages),
            "reply",
            safe(reply)
        );
    }

    /**
     * Record layout metrics for arrange operations (pre/post overlaps and crossings).
     */
    public static void recordLayout(
        String diagram,
        String strategy,
        int selectionSize,
        int overlapsPre,
        int overlapsPost,
        int crossingsPre,
        int crossingsPost,
        long durationMs
    ) {
        if (!isEnabled()) return;
        String msg = String.format(
            "diagram=%s strategy=%s size=%d overlaps %d->%d crossings %d->%d duration_ms=%d",
            safe(diagram),
            safe(strategy),
            selectionSize,
            overlapsPre,
            overlapsPost,
            crossingsPre,
            crossingsPost,
            durationMs
        );
        logInfo("layout", msg);
        writeJsonLine(
            "layout",
            "diagram",
            safe(diagram),
            "strategy",
            safe(strategy),
            "selection_size",
            String.valueOf(selectionSize),
            "overlaps_pre",
            String.valueOf(overlapsPre),
            "overlaps_post",
            String.valueOf(overlapsPost),
            "crossings_pre",
            String.valueOf(crossingsPre),
            "crossings_post",
            String.valueOf(crossingsPost),
            "duration_ms",
            String.valueOf(durationMs)
        );
    }

    /** Set/clear correlation id to group logs for a single interaction. */
    public static void setCorrelationId(String id) {
        CORRELATION.set(id);
    }

    public static void clearCorrelationId() {
        CORRELATION.remove();
    }

    public static String getCorrelationId() {
        return CORRELATION.get();
    }

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
        String prefix = (id == null || id.isBlank())
            ? category
            : ("id=" + id + "] [" + category);
        String payload = String.format("[%s] %s", prefix, message);
        if (Activator.getDefault() != null) {
            Activator.getDefault()
                .getLog()
                .log(new Status(severity, Activator.PLUGIN_ID, payload));
        } else {
            Level level = severity == IStatus.INFO ? Level.INFO : Level.WARNING;
            LOG.log(level, payload);
        }
    }

    private static void writeJsonLine(String event, String... kv) {
        if (!isEnabled()) return;
        try {
            Path dir = Paths.get(
                System.getProperty("user.home"),
                ".capella-ai",
                "logs"
            );
            if (!Files.exists(dir)) Files.createDirectories(dir);
            String date = java.time.LocalDate.now(ZoneOffset.UTC).format(
                java.time.format.DateTimeFormatter.BASIC_ISO_DATE
            );
            Path file = dir.resolve("capella-ai-" + date + ".jsonl");
            StringBuilder sb = new StringBuilder(256);
            sb.append('{');
            sb
                .append("\"ts\":\"")
                .append(jsonEscape(TS.format(Instant.now())))
                .append("\",");
            String id = getCorrelationId();
            if (id != null && !id.isBlank()) {
                sb.append("\"id\":\"").append(jsonEscape(id)).append("\",");
            }
            sb.append("\"event\":\"").append(jsonEscape(event)).append("\"");
            // kv pairs
            for (int i = 0; i + 1 < kv.length; i += 2) {
                String k = kv[i];
                String v = kv[i + 1];
                sb
                    .append(',')
                    .append('"')
                    .append(jsonEscape(k))
                    .append('"')
                    .append(':')
                    .append('"')
                    .append(jsonEscape(v))
                    .append('"');
            }
            sb.append('}').append('\n');
            Files.write(
                file,
                sb.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (Exception ex) {
            // Telemetry write failure - don't interrupt main flow
            System.err.println(
                "[Telemetry] Error writing log: " + ex.getMessage()
            );
        }
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }
}
