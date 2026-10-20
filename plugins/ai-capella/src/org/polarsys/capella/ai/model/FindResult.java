package org.polarsys.capella.ai.model;

import org.eclipse.emf.ecore.EObject;
import org.polarsys.capella.ai.model.ActionResult.ErrorCode;

import java.util.List;

/**
 * Structured result from element lookup with diagnostic information. Provides alternatives for "did
 * you mean?" suggestions when lookup fails.
 */
public record FindResult(
        EObject element,
        ErrorCode code,
        String message,
        int matchCount,
        List<String> alternatives) {

    /** Compact constructor ensuring immutable collections. */
    public FindResult {
        alternatives = alternatives != null ? List.copyOf(alternatives) : List.of();
    }

    // ========== Query Methods ==========

    /** Check if lookup was successful (exactly one match found). */
    public boolean success() {
        return element != null && code == ErrorCode.SUCCESS;
    }

    /** Check if lookup failed. */
    public boolean failed() {
        return !success();
    }

    /** Check if result is ambiguous (multiple matches found). */
    public boolean ambiguous() {
        return code == ErrorCode.DUPLICATE_AMBIGUOUS;
    }

    /** Check if alternatives are available for correction. */
    public boolean hasAlternatives() {
        return !alternatives.isEmpty();
    }

    // ========== Factory Methods ==========

    /** Create a success result with the found element. */
    public static FindResult success(EObject element) {
        return new FindResult(element, ErrorCode.SUCCESS, null, 1, List.of());
    }

    /** Create a not-found result with similar element suggestions. */
    public static FindResult notFound(String className, String name, List<String> similar) {
        String msg = "No " + className + " named '" + name + "' found";
        return new FindResult(null, ErrorCode.ELEMENT_NOT_FOUND, msg, 0, similar);
    }

    /** Create a not-found result with custom message and suggestions. */
    public static FindResult notFound(
            String className, String name, List<String> similar, String customMessage) {
        return new FindResult(null, ErrorCode.ELEMENT_NOT_FOUND, customMessage, 0, similar);
    }

    /**
     * Create an ambiguous result when multiple elements match. Returns the first match but
     * indicates ambiguity.
     */
    public static FindResult ambiguous(EObject first, int count, List<String> allNames) {
        String msg = count + " elements match - specify index to disambiguate";
        return new FindResult(first, ErrorCode.DUPLICATE_AMBIGUOUS, msg, count, allNames);
    }

    /** Create an error result for session or input issues. */
    public static FindResult error(ErrorCode code, String message) {
        return new FindResult(null, code, message, 0, List.of());
    }

    // ========== Conversion Methods ==========

    /** Convert to ActionResult for use in action handlers. */
    public ActionResult toActionResult() {
        if (success()) {
            return ActionResult.ok();
        }
        if (hasAlternatives()) {
            return ActionResult.failWithAlternatives(code, message, alternatives);
        }
        return ActionResult.fail(code, message);
    }

    /** Convert to ActionResult with custom error code. */
    public ActionResult toActionResult(ErrorCode overrideCode) {
        if (success()) {
            return ActionResult.ok();
        }
        if (hasAlternatives()) {
            return ActionResult.failWithAlternatives(overrideCode, message, alternatives);
        }
        return ActionResult.fail(overrideCode, message);
    }

    /** Format result for LLM consumption. */
    public String formatForLLM() {
        if (success()) {
            return "FOUND: " + getElementDescription();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("NOT_FOUND: ").append(code).append("\n");
        if (message != null) {
            sb.append("Error: ").append(message).append("\n");
        }
        if (!alternatives.isEmpty()) {
            sb.append("Similar: ").append(String.join(", ", alternatives)).append("\n");
        }
        return sb.toString();
    }

    private String getElementDescription() {
        if (element == null) {
            return "null";
        }
        String className = element.eClass().getName();
        Object nameFeature = element.eGet(element.eClass().getEStructuralFeature("name"));
        String name = nameFeature != null ? nameFeature.toString() : "<unnamed>";
        return className + ": " + name;
    }

    @Override
    public String toString() {
        if (success()) {
            return "FindResult[SUCCESS: " + getElementDescription() + "]";
        }
        if (ambiguous()) {
            return "FindResult[AMBIGUOUS: " + matchCount + " matches]";
        }
        return "FindResult[" + code + ": " + message + "]";
    }
}
