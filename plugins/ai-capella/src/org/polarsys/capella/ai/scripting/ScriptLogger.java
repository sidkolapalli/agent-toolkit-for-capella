package org.polarsys.capella.ai.scripting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Thread-safe logger for Groovy scripts.
 * Captures log messages during script execution for inclusion in ExecutionResult.
 *
 * <p>Usage in scripts:
 * <pre>{@code
 * log.info("Created element: " + element)
 * log.warn("Element already exists")
 * log.error("Failed to create link")
 * }</pre>
 */
public final class ScriptLogger {

    /** Maximum number of log messages to retain */
    private static final int MAX_MESSAGES = 1000;

    /** Maximum length of a single message */
    private static final int MAX_MESSAGE_LENGTH = 2000;

    private final List<String> messages = Collections.synchronizedList(new ArrayList<>());

    /**
     * Log an informational message. Any value is accepted: scripts often log lists or maps, and a
     * logging call must never be what makes a model change fail.
     */
    public void info(Object message) {
        log("INFO", String.valueOf(message));
    }

    /**
     * Log a warning message.
     */
    public void warn(Object message) {
        log("WARN", String.valueOf(message));
    }

    /**
     * Log an error message.
     */
    public void error(Object message) {
        log("ERROR", String.valueOf(message));
    }

    /**
     * Log a debug message.
     */
    public void debug(Object message) {
        log("DEBUG", String.valueOf(message));
    }

    /**
     * Log with custom level.
     */
    public void log(String level, String message) {
        if (messages.size() >= MAX_MESSAGES) {
            return; // Prevent memory exhaustion
        }

        String formatted = formatMessage(level, message);
        messages.add(formatted);
    }

    /**
     * Print a message (alias for info, Groovy-friendly).
     */
    public void println(Object obj) {
        info(obj != null ? obj.toString() : "null");
    }

    /**
     * Print a message without newline (accumulates in current line).
     */
    public void print(Object obj) {
        info(obj != null ? obj.toString() : "null");
    }

    /**
     * Get all captured log messages.
     */
    public List<String> getMessages() {
        synchronized (messages) {
            return new ArrayList<>(messages);
        }
    }

    /**
     * Clear all captured messages.
     */
    public void clear() {
        messages.clear();
    }

    /**
     * Get the count of captured messages.
     */
    public int size() {
        return messages.size();
    }

    /**
     * Check if any messages were logged.
     */
    public boolean hasMessages() {
        return !messages.isEmpty();
    }

    /**
     * Format a log message with level prefix.
     */
    private String formatMessage(String level, String message) {
        if (message == null) {
            message = "null";
        }

        // Truncate very long messages
        if (message.length() > MAX_MESSAGE_LENGTH) {
            message = message.substring(0, MAX_MESSAGE_LENGTH) + "... [truncated]";
        }

        return "[" + level + "] " + message;
    }

    @Override
    public String toString() {
        return "ScriptLogger[" + messages.size() + " messages]";
    }
}
