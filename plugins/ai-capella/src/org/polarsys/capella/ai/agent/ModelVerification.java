package org.polarsys.capella.ai.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.scripting.CapellaAPI;

/** Optional evidence from Capella's installed validator, never a compliance verdict. */
public final class ModelVerification {
    private static final int MAX_DIAGNOSTICS = 200;
    private static final int MAX_DISPLAY_DIAGNOSTICS = 8;
    private ModelVerification() { }

    public enum Verdict {
        UNAVAILABLE,
        ERROR_DETAILS_INCOMPLETE,
        ADDITIONAL_ERROR_DIAGNOSTICS,
        NO_ADDITIONAL_ERROR_DIAGNOSTICS
    }

    /** A validator diagnostic is compared as data; it need not identify a unique model element. */
    public record Issue(int severity, String plugin, int code, String message) {
        public Map<String, Object> feedback() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("severity", severity); value.put("plugin", plugin); value.put("code", code);
            value.put("message", clip(message, 600));
            return Collections.unmodifiableMap(value);
        }
    }

    public record Snapshot(boolean available, long errors, long warnings, long issueCount,
        List<Issue> errorDiagnostics, boolean errorDetailsComplete, boolean truncated, String note) {
        public Snapshot {
            errorDiagnostics = List.copyOf(errorDiagnostics);
        }

        public static Snapshot unavailable(String note) {
            return new Snapshot(false, -1, -1, -1, List.of(), false, false, clip(note, 350));
        }

        public Map<String, Object> feedback() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("available", available); result.put("errors", errors); result.put("warnings", warnings);
            result.put("issue_count", issueCount); result.put("error_details_complete", errorDetailsComplete);
            result.put("details_truncated", truncated); result.put("note", note);
            return Collections.unmodifiableMap(result);
        }
    }

    public record Comparison(Verdict verdict, Snapshot before, Snapshot after,
        long additionalErrorCount, List<Issue> additionalErrors, boolean truncated) {
        public Comparison { additionalErrors = List.copyOf(additionalErrors); }

        public Map<String, Object> feedback() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("verdict", verdict.name()); result.put("before", before.feedback());
            result.put("after", after.feedback()); result.put("additional_error_diagnostic_count", additionalErrorCount);
            result.put("additional_error_diagnostics", additionalErrors.stream().map(Issue::feedback).toList());
            result.put("additional_details_truncated", truncated);
            result.put("scope", "Installed Capella validator diagnostics. This does not verify requirements, safety or compliance.");
            return Collections.unmodifiableMap(result);
        }

        public String markdownSummary() {
            if (verdict == Verdict.UNAVAILABLE) {
                String reason = !after.available() ? after.note() : before.note();
                return "Native validation comparison unavailable. " + reason;
            }
            StringBuilder text = new StringBuilder("Native validation: ").append(before.errors()).append(" → ")
                .append(after.errors()).append(" errors; ").append(before.warnings()).append(" → ")
                .append(after.warnings()).append(" warnings. ");
            if (verdict == Verdict.ERROR_DETAILS_INCOMPLETE) {
                text.append("Error details are incomplete, so new errors could not be determined.");
            } else if (verdict == Verdict.ADDITIONAL_ERROR_DIAGNOSTICS) {
                text.append(additionalErrorCount).append(" additional error diagnostic(s).");
            } else text.append("No additional error diagnostics were observed.");
            for (Issue issue : additionalErrors) text.append("\n- ").append(clip(issue.message(), 600)
                .replace("*", "\\*").replace("`", "\\`"));
            if (truncated) text.append("\nAdditional diagnostic details are truncated.");
            text.append("\nThis does not verify requirements, safety or compliance.");
            return text.toString();
        }
    }

    /**
     * Explicitly run before a reviewed write or after observed committed changes.
     * This uses the installed validator's full-model pass. Its returned evidence
     * is bounded; callers should not invoke it for every read or chat message.
     */
    public static Snapshot snapshot(Session session) {
        return snapshot(session, open -> new CapellaAPI(open).validateNative());
    }

    /**
     * A worker caller validates on its own thread under the domain's exclusive read, like Capella's own
     * validation job: on a 312,000-object model a full pass takes about 48 s, which froze the UI when it ran
     * there. A caller already on the UI thread keeps running there.
     */
    public static Snapshot snapshot(Session session, java.util.function.Function<Session, Map<String, Object>> nativeValidation) {
        AtomicReference<Snapshot> snapshot = new AtomicReference<>();
        Runnable validate = () -> {
            if (session == null || !session.isOpen() || session.getTransactionalEditingDomain() == null) {
                snapshot.set(Snapshot.unavailable("An open Capella model and its editing domain are required.")); return;
            }
            try {
                session.getTransactionalEditingDomain().runExclusive(() -> snapshot.set(fromNativeResult(nativeValidation.apply(session))));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                snapshot.set(Snapshot.unavailable("Native validation was interrupted."));
            }
        };
        try {
            validate.run();
        } catch (RuntimeException failure) {
            snapshot.set(Snapshot.unavailable("Native validation could not run: " + failure.getMessage()));
        }
        return snapshot.get() == null ? Snapshot.unavailable("Native validation returned no evidence.") : snapshot.get();
    }

    /** Converts the existing trusted API's validator result without retaining arbitrary values. */
    public static Snapshot fromNativeResult(Map<String, Object> result) {
        if (result == null) return Snapshot.unavailable("Native validation returned no evidence.");
        long errors = count(result.get("errors")), warnings = count(result.get("warnings")), issues = count(result.get("issueCount"));
        if (errors < 0 || warnings < 0 || issues < errors + warnings || !(result.get("issues") instanceof List<?> raw)) {
            return Snapshot.unavailable("Native validation returned an incomplete result.");
        }
        List<Issue> diagnostics = new ArrayList<>();
        for (Object value : raw) {
            if (!(value instanceof Map<?, ?> issue) || !(issue.get("severity") instanceof Number severity)) continue;
            if (severity.intValue() < IStatus.ERROR) continue;
            if (diagnostics.size() >= MAX_DIAGNOSTICS) break;
            String plugin = issue.get("plugin") instanceof String text ? text : "";
            String message = issue.get("message") instanceof String text ? text : "";
            int code = issue.get("code") instanceof Number number ? number.intValue() : 0;
            // Retain exact bounded messages for multiset comparison. Do not compare clipped messages.
            if (message.length() > 4_000 || plugin.length() > 300) {
                return new Snapshot(true, errors, warnings, issues, diagnostics, false, true,
                    "One or more error messages exceed the diagnostic comparison limit.");
            }
            diagnostics.add(new Issue(severity.intValue(), plugin, code, message));
        }
        boolean truncated = Boolean.TRUE.equals(result.get("truncated")) || raw.size() > MAX_DIAGNOSTICS;
        return new Snapshot(true, errors, warnings, issues, diagnostics, diagnostics.size() == errors,
            truncated, truncated ? "Native diagnostic details were truncated; counts cover the complete validator result." : "");
    }

    /** Compare error diagnostic multisets, so equal error counts cannot hide a replaced error. */
    public static Comparison compare(Snapshot before, Snapshot after) {
        if (before == null) before = Snapshot.unavailable("No baseline native validation was captured.");
        if (after == null) after = Snapshot.unavailable("No subsequent native validation was captured.");
        if (!before.available() || !after.available()) {
            return new Comparison(Verdict.UNAVAILABLE, before, after, -1, List.of(), false);
        }
        if (!before.errorDetailsComplete() || !after.errorDetailsComplete()) {
            return new Comparison(Verdict.ERROR_DETAILS_INCOMPLETE, before, after, -1, List.of(), true);
        }
        Map<Issue, Integer> baseline = new LinkedHashMap<>();
        for (Issue issue : before.errorDiagnostics()) baseline.merge(issue, 1, Integer::sum);
        List<Issue> extra = new ArrayList<>();
        for (Issue issue : after.errorDiagnostics()) {
            int remaining = baseline.getOrDefault(issue, 0);
            if (remaining > 0) baseline.put(issue, remaining - 1); else extra.add(issue);
        }
        return new Comparison(extra.isEmpty() ? Verdict.NO_ADDITIONAL_ERROR_DIAGNOSTICS : Verdict.ADDITIONAL_ERROR_DIAGNOSTICS,
            before, after, extra.size(), extra.subList(0, Math.min(extra.size(), MAX_DISPLAY_DIAGNOSTICS)),
            extra.size() > MAX_DISPLAY_DIAGNOSTICS);
    }

    private static long count(Object value) {
        return value instanceof Number number && number.longValue() >= 0 ? number.longValue() : -1;
    }

    private static String clip(String text, int max) {
        if (text == null) return "";
        String line = text.replaceAll("[\\r\\n\\t\\p{Cntrl}]", " ");
        return line.length() > max ? line.substring(0, max) + "…" : line;
    }
}
