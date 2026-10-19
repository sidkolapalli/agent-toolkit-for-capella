package org.polarsys.capella.ai.scripting;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.polarsys.capella.ai.preferences.OutputPreferences;

/**
 * Structured result from Groovy script execution.
 * Contains execution status, return value, logs, and error information.
 */
public record ExecutionResult(
    boolean success,
    Object value,
    ErrorCode code,
    String message,
    String stackTrace,
    List<String> logs,
    long durationMs
) {

    /**
     * Error codes for script execution failures.
     */
    public enum ErrorCode {
        /** Script executed successfully */
        SUCCESS,
        /** Script compilation failed (syntax error) */
        COMPILATION_ERROR,
        /** Script execution threw an exception */
        RUNTIME_ERROR,
        /** Script exceeded timeout limit */
        TIMEOUT,
        /** Script attempted blocked operation (security) */
        SECURITY_VIOLATION,
        /** Session or API not available */
        API_ERROR,
        /** Model changes committed, but writing them to disk failed; do not repeat the script. */
        SAVE_FAILED_AFTER_COMMIT,
        /** Script returned null or invalid result */
        INVALID_RESULT
    }

    /**
     * Compact constructor ensuring immutable logs list.
     */
    public ExecutionResult {
        logs = logs != null ? List.copyOf(logs) : List.of();
    }

    // ========== Factory Methods ==========

    /**
     * Create a success result with return value and logs.
     */
    public static ExecutionResult success(Object value, List<String> logs, long durationMs) {
        return new ExecutionResult(true, value, ErrorCode.SUCCESS, null, null, logs, durationMs);
    }

    /**
     * Create a success result with return value only.
     */
    public static ExecutionResult success(Object value) {
        return new ExecutionResult(true, value, ErrorCode.SUCCESS, null, null, List.of(), 0);
    }

    /**
     * Create a failure result with error code and message.
     */
    public static ExecutionResult fail(ErrorCode code, String message) {
        return new ExecutionResult(false, null, code, message, null, List.of(), 0);
    }

    /**
     * Create a failure result with error code, message, and stack trace.
     */
    public static ExecutionResult fail(ErrorCode code, String message, String stackTrace) {
        return new ExecutionResult(false, null, code, message, stackTrace, List.of(), 0);
    }

    /**
     * Create a failure result with full context including logs.
     */
    public static ExecutionResult fail(ErrorCode code, String message, String stackTrace,
                                       List<String> logs, long durationMs) {
        return new ExecutionResult(false, null, code, message, stackTrace, logs, durationMs);
    }

    /**
     * Create a timeout failure result.
     */
    public static ExecutionResult timeout(long timeoutMs, List<String> logs) {
        return new ExecutionResult(false, null, ErrorCode.TIMEOUT,
            "Script execution exceeded " + timeoutMs + "ms timeout",
            null, logs, timeoutMs);
    }

    // ========== Query Methods ==========

    /**
     * Check if execution failed.
     */
    public boolean failed() {
        return !success;
    }

    /**
     * Check if failure was due to timeout.
     */
    public boolean isTimeout() {
        return code == ErrorCode.TIMEOUT;
    }

    /**
     * Check if failure was a compilation error (syntax).
     */
    public boolean isCompilationError() {
        return code == ErrorCode.COMPILATION_ERROR;
    }

    /**
     * Check if failure was a runtime error.
     */
    public boolean isRuntimeError() {
        return code == ErrorCode.RUNTIME_ERROR;
    }

    /**
     * Check if there are log messages.
     */
    public boolean hasLogs() {
        return !logs.isEmpty();
    }

    // ========== Output normalization ==========

    private static final Pattern UUID_PATTERN = Pattern.compile(
        "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b"
    );

    private static final Pattern HEX_32_PATTERN = Pattern.compile(
        "\\b[0-9a-fA-F]{32}\\b"
    );

    /**
     * Return a redacted + size-capped representation of the return value for
     * external output (LLM feedback and dev server).
     */
    public String valueForOutput() {
        if (value == null) {
            return null;
        }
        String formatted = formatValue(value);
        return normalizeText(formatted, OutputPreferences.getMaxValueChars());
    }

    /**
     * Return redacted + size-capped log lines suitable for external output.
     */
    public List<String> logsForOutput() {
        return normalizeLines(
            logs,
            OutputPreferences.getMaxLogLines(),
            OutputPreferences.getMaxLogLineChars()
        );
    }

    /**
     * Return a redacted representation of the message for external output.
     */
    public String messageForOutput() {
        if (message == null) {
            return null;
        }
        return normalizeText(message, OutputPreferences.getMaxValueChars());
    }

    // ========== Formatting Methods ==========

    /**
     * Format result for LLM consumption in error feedback loop.
     * This helps the LLM understand what went wrong and self-correct.
     */
    public String formatForLLM() {
        StringBuilder sb = new StringBuilder();

        if (success) {
            sb.append("EXECUTION_SUCCESS\n");
            String valueOut = valueForOutput();
            if (valueOut != null) {
                sb.append("Return value: ").append(valueOut).append("\n");
            }
        } else {
            sb.append("EXECUTION_FAILED\n");
            sb.append("Error type: ").append(code).append("\n");
            String messageOut = messageForOutput();
            if (messageOut != null) {
                sb.append("Error: ").append(messageOut).append("\n");
            }
            if (stackTrace != null) {
                // Include first few lines of stack trace for context
                String[] lines = redact(stackTrace).split("\n");
                int maxLines = Math.min(5, lines.length);
                sb.append("Stack trace (first ").append(maxLines).append(" lines):\n");
                for (int i = 0; i < maxLines; i++) {
                    sb.append("  ").append(lines[i]).append("\n");
                }
            }
        }

        List<String> logsOut = logsForOutput();
        if (!logsOut.isEmpty()) {
            sb.append("Script output:\n");
            for (String log : logsOut) {
                sb.append("  ").append(log).append("\n");
            }
        }

        sb.append("Duration: ").append(durationMs).append("ms\n");
        return sb.toString();
    }

    /**
     * Format value for display, handling common types.
     */
    private String formatValue(Object val) {
        if (val == null) {
            return "null";
        }
        if (val instanceof Map<?, ?> map) {
            int maxSample = OutputPreferences.getMaxCollectionSample();
            StringBuilder sb = new StringBuilder();
            sb.append("Map[").append(map.size()).append(" entries]");
            if (!map.isEmpty()) {
                sb.append(" sampleKeys=[");
                int i = 0;
                for (Object key : map.keySet()) {
                    if (i >= maxSample) break;
                    if (i > 0) sb.append(", ");
                    sb.append(String.valueOf(key));
                    i++;
                }
                if (map.size() > maxSample) {
                    sb.append(", …");
                }
                sb.append("]");
            }
            return sb.toString();
        }
        if (val instanceof Collection<?> col) {
            int maxSample = OutputPreferences.getMaxCollectionSample();
            StringBuilder sb = new StringBuilder();
            sb.append(col.getClass().getSimpleName())
                .append("[")
                .append(col.size())
                .append(" items]");
            if (!col.isEmpty()) {
                sb.append(" sample=[");
                int i = 0;
                for (Object item : col) {
                    if (i >= maxSample) break;
                    if (i > 0) sb.append(", ");
                    sb.append(String.valueOf(item));
                    i++;
                }
                if (col.size() > maxSample) {
                    sb.append(", …");
                }
                sb.append("]");
            }
            return sb.toString();
        }
        if (val instanceof List<?> list) {
            return "List[" + list.size() + " items]";
        }
        if (val instanceof org.eclipse.emf.ecore.EObject eo) {
            try {
                String className = eo.eClass().getName();
                Object name = eo.eGet(eo.eClass().getEStructuralFeature("name"));
                return className + ": " + (name != null ? name : "<unnamed>");
            } catch (Exception e) {
                return eo.getClass().getSimpleName();
            }
        }
        String str = val.toString();
        if (str.length() > 200) {
            return str.substring(0, 200) + "...";
        }
        return str;
    }

    private static List<String> normalizeLines(
        List<String> lines,
        int maxLines,
        int maxCharsPerLine
    ) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }

        int limit = Math.max(0, maxLines);
        int take = Math.min(lines.size(), limit);
        java.util.ArrayList<String> out = new java.util.ArrayList<>(take + 1);
        for (int i = 0; i < take; i++) {
            String line = lines.get(i);
            if (line == null) {
                line = "null";
            }
            out.add(normalizeText(line, maxCharsPerLine));
        }
        int remaining = lines.size() - take;
        if (remaining > 0) {
            out.add("…(truncated " + remaining + " more lines)");
        }
        return List.copyOf(out);
    }

    private static String normalizeText(String text, int maxChars) {
        if (text == null) {
            return null;
        }
        String redacted = redact(text);
        int limit = Math.max(0, maxChars);
        if (redacted.length() <= limit) {
            return redacted;
        }
        if (limit == 0) {
            return "…(truncated)";
        }
        return redacted.substring(0, limit) + "…(truncated)";
    }

    private static String redact(String text) {
        if (text == null) {
            return null;
        }
        if (!OutputPreferences.isRedactionEnabled()) {
            return text;
        }
        String out = UUID_PATTERN.matcher(text).replaceAll("<uuid>");
        out = HEX_32_PATTERN.matcher(out).replaceAll("<id>");
        for (Pattern p : OutputPreferences.getAdditionalRedactionPatterns()) {
            try {
                out = p.matcher(out).replaceAll("<redacted>");
            } catch (Exception e) {
                // Ignore invalid pattern applications.
            }
        }
        return out;
    }

    @Override
    public String toString() {
        if (success) {
            return "ExecutionResult[SUCCESS" + (value != null ? ": " + formatValue(value) : "") + "]";
        }
        return "ExecutionResult[" + code + ": " + message + "]";
    }
}
