package org.polarsys.capella.ai.scripting;

import java.util.List;

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

    // ========== Formatting Methods ==========

    /**
     * Format result for LLM consumption in error feedback loop.
     * This helps the LLM understand what went wrong and self-correct.
     */
    public String formatForLLM() {
        StringBuilder sb = new StringBuilder();

        if (success) {
            sb.append("EXECUTION_SUCCESS\n");
            if (value != null) {
                sb.append("Return value: ").append(formatValue(value)).append("\n");
            }
        } else {
            sb.append("EXECUTION_FAILED\n");
            sb.append("Error type: ").append(code).append("\n");
            if (message != null) {
                sb.append("Error: ").append(message).append("\n");
            }
            if (stackTrace != null) {
                // Include first few lines of stack trace for context
                String[] lines = stackTrace.split("\n");
                int maxLines = Math.min(5, lines.length);
                sb.append("Stack trace (first ").append(maxLines).append(" lines):\n");
                for (int i = 0; i < maxLines; i++) {
                    sb.append("  ").append(lines[i]).append("\n");
                }
            }
        }

        if (!logs.isEmpty()) {
            sb.append("Script output:\n");
            for (String log : logs) {
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

    @Override
    public String toString() {
        if (success) {
            return "ExecutionResult[SUCCESS" + (value != null ? ": " + formatValue(value) : "") + "]";
        }
        return "ExecutionResult[" + code + ": " + message + "]";
    }
}
