package com.sidkolapalli.capella.ai.model;

import java.util.List;
import java.util.Map;

/**
 * Structured result from action execution with diagnostic information. Replaces boolean returns to
 * enable self-healing and better error reporting.
 */
public record ActionResult(
        boolean success,
        ErrorCode code,
        String message,
        String suggestion,
        List<String> alternatives,
        Map<String, Object> context) {

    /** Error codes covering all failure scenarios in Capella AI actions. */
    public enum ErrorCode {
        /** Action completed successfully */
        SUCCESS,
        /** Target element not found in the model */
        ELEMENT_NOT_FOUND,
        /** Parent container not found */
        PARENT_NOT_FOUND,
        /** Architecture layer (OA/SA/LA/PA) not found */
        LAYER_NOT_FOUND,
        /** Container cannot hold the target element type */
        INVALID_CONTAINER,
        /** Element type does not match expected type */
        TYPE_MISMATCH,
        /** EMF constraint or containment rule violated */
        CONSTRAINT_VIOLATION,
        /** Referenced element (for linking) not found */
        REFERENCE_NOT_FOUND,
        /** Multiple elements match - ambiguous lookup */
        DUPLICATE_AMBIGUOUS,
        /** Session is null or in invalid state */
        SESSION_ERROR,
        /** Invalid or missing required input parameter */
        INVALID_INPUT
    }

    /** Compact constructor ensuring immutable collections. */
    public ActionResult {
        alternatives = alternatives != null ? List.copyOf(alternatives) : List.of();
        context = context != null ? Map.copyOf(context) : Map.of();
    }

    // ========== Factory Methods ==========

    /** Create a success result with no message. */
    public static ActionResult ok() {
        return new ActionResult(true, ErrorCode.SUCCESS, null, null, List.of(), Map.of());
    }

    /** Create a success result with a descriptive message. */
    public static ActionResult ok(String message) {
        return new ActionResult(true, ErrorCode.SUCCESS, message, null, List.of(), Map.of());
    }

    /** Create a failure result with error code and message. */
    public static ActionResult fail(ErrorCode code, String message) {
        return new ActionResult(false, code, message, null, List.of(), Map.of());
    }

    /** Create a failure result with error code, message, and actionable suggestion. */
    public static ActionResult fail(ErrorCode code, String message, String suggestion) {
        return new ActionResult(false, code, message, suggestion, List.of(), Map.of());
    }

    /** Create a failure result with alternatives for "did you mean?" suggestions. */
    public static ActionResult failWithAlternatives(
            ErrorCode code, String message, List<String> alternatives) {
        String suggestion =
                alternatives.isEmpty()
                        ? null
                        : "Did you mean one of: " + String.join(", ", alternatives);
        return new ActionResult(false, code, message, suggestion, alternatives, Map.of());
    }

    /**
     * Create a failure result with custom message, suggestion, and alternatives. Use this when you
     * need a specific error message while preserving alternatives from FindResult.
     */
    public static ActionResult failWithAlternatives(
            ErrorCode code, String message, String suggestion, List<String> alternatives) {
        return new ActionResult(
                false,
                code,
                message,
                suggestion,
                alternatives != null ? alternatives : List.of(),
                Map.of());
    }

    /**
     * Create a result with all fields specified (for partial success scenarios in batch
     * operations).
     */
    public static ActionResult create(
            boolean success,
            ErrorCode code,
            String message,
            String suggestion,
            List<String> alternatives,
            Map<String, Object> context) {
        return new ActionResult(success, code, message, suggestion, alternatives, context);
    }

    // ========== Convenience Methods ==========

    /** Check if result represents a failure. */
    public boolean failed() {
        return !success;
    }

    /** Check if failure was due to element not found (common case for self-healing). */
    public boolean isNotFound() {
        return code == ErrorCode.ELEMENT_NOT_FOUND
                || code == ErrorCode.PARENT_NOT_FOUND
                || code == ErrorCode.REFERENCE_NOT_FOUND;
    }

    /** Check if failure was due to ambiguous match. */
    public boolean isAmbiguous() {
        return code == ErrorCode.DUPLICATE_AMBIGUOUS;
    }

    /** Check if alternatives are available for correction. */
    public boolean hasAlternatives() {
        return !alternatives.isEmpty();
    }

    /** Format result for LLM consumption in self-healing loop. */
    public String formatForLLM() {
        if (success) {
            return "SUCCESS: " + (message != null ? message : "completed");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("FAILED: ").append(code).append("\n");
        if (message != null) {
            sb.append("Error: ").append(message).append("\n");
        }
        if (suggestion != null) {
            sb.append("Suggestion: ").append(suggestion).append("\n");
        }
        if (!alternatives.isEmpty()) {
            sb.append("Alternatives: ").append(String.join(", ", alternatives)).append("\n");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        if (success) {
            return "ActionResult[SUCCESS" + (message != null ? ": " + message : "") + "]";
        }
        return "ActionResult[" + code + ": " + message + "]";
    }
}
