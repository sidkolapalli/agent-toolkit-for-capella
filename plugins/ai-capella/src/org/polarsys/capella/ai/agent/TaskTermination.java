package org.polarsys.capella.ai.agent;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Why one inference phase ended. This is neither execution authorization nor an engineering verdict. */
public record TaskTermination(State state, String reason, Scope scope, Cause cause) {
    public static final int MAX_REASON_CHARS = 600;
    public enum State { COMPLETE, NEEDS_INPUT, BLOCKED, BUDGET_EXHAUSTED, REVIEW_PENDING }
    public enum Scope { RESPONSE, DECLARED_PLAN }
    /** A host reconciliation cause, never accepted as model-supplied task metadata. */
    public enum Cause { NONE, PREMATURE_COMPLETE }

    public TaskTermination(State state, String reason, Scope scope) { this(state, reason, scope, Cause.NONE); }

    public TaskTermination {
        if (state == null || scope == null || cause == null || reason == null || reason.isBlank()
                || reason.length() > MAX_REASON_CHARS || reason.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid bounded task termination");
        }
        if (cause == Cause.PREMATURE_COMPLETE && (state != State.BLOCKED || scope != Scope.DECLARED_PLAN)) {
            throw new IllegalArgumentException("Premature completion requires a pending declared plan");
        }
    }

    /** Missing optional protocol data remains compatible, with conservative plan reconciliation. */
    public static TaskTermination fromFinal(Object declaration, List<ArcadiaAgent.Step> plan) {
        if (declaration == null) return legacy(false, plan);
        if (!(declaration instanceof Map<?, ?> data) || !Set.of("state", "reason").containsAll(data.keySet())
                || !(data.get("state") instanceof String value)) {
            throw new IllegalArgumentException("A final task state needs bounded state and reason fields");
        }
        State state = switch (value) {
            case "complete" -> State.COMPLETE;
            case "needs_input" -> State.NEEDS_INPUT;
            case "blocked" -> State.BLOCKED;
            default -> throw new IllegalArgumentException("Only complete, needs_input or blocked may be declared in a final answer");
        };
        Object supplied = data.get("reason");
        String reason = !data.containsKey("reason") && state == State.COMPLETE ? "The response is ready."
            : supplied instanceof String text ? text : "";
        return reconcile(new TaskTermination(state, reason, scope(plan)), false, plan);
    }

    public static TaskTermination legacy(boolean proposal, List<ArcadiaAgent.Step> plan) {
        return proposal ? review(plan) : reconcile(new TaskTermination(State.COMPLETE,
            plan.isEmpty() ? "A reply is ready; no model task completion is established."
                : "The declared plan has no remaining pending steps. This does not establish engineering completeness or compliance.", scope(plan)), false, plan);
    }

    public static TaskTermination review(List<ArcadiaAgent.Step> plan) {
        return new TaskTermination(State.REVIEW_PENDING, "Proposed model changes await the configured review or application step. No proposed code has run.", scope(plan));
    }

    public static TaskTermination blocked(String reason, List<ArcadiaAgent.Step> plan) {
        return new TaskTermination(State.BLOCKED, hostReason(reason), scope(plan));
    }

    public static TaskTermination budget(String reason, List<ArcadiaAgent.Step> plan) {
        return new TaskTermination(State.BUDGET_EXHAUSTED, hostReason(reason), scope(plan));
    }

    /** The caller's plan evidence takes precedence over any assistant completion claim. */
    public static TaskTermination reconcile(TaskTermination value, boolean proposal, List<ArcadiaAgent.Step> plan) {
        if (proposal) return review(plan);
        if (value == null) return null; // Local execution receipt snapshot before another inference phase.
        if (value.state == State.COMPLETE && plan.stream().anyMatch(step -> !"completed".equals(step.status()))) {
            var pending = plan.stream().filter(step -> !"completed".equals(step.status())).toList();
            String names = String.join(", ", pending.stream().map(step -> "'" + step.title() + "' (" + step.type() + ")").toList());
            return new TaskTermination(State.BLOCKED, hostReason(pending.size() + " declared plan step" + (pending.size() == 1 ? " remains" : "s remain")
                + " pending or unverified: " + names + ". The answer has not established completion of this plan."), Scope.DECLARED_PLAN, Cause.PREMATURE_COMPLETE);
        }
        Cause cause = !plan.isEmpty() && plan.stream().anyMatch(step -> !"completed".equals(step.status())) ? value.cause : Cause.NONE;
        return new TaskTermination(value.state, value.reason, scope(plan), cause);
    }

    public boolean prematureCompletion() { return cause == Cause.PREMATURE_COMPLETE; }

    public Map<String, Object> feedback() {
        return Map.of("state", state.name().toLowerCase(java.util.Locale.ROOT), "reason", reason,
            "scope", scope.name().toLowerCase(java.util.Locale.ROOT));
    }

    public String activityText() {
        String label = switch (state) {
            case COMPLETE -> scope == Scope.RESPONSE ? "Reply ready" : "Declared plan checked";
            case NEEDS_INPUT -> "Your input is needed";
            case BLOCKED -> "Task paused";
            case BUDGET_EXHAUSTED -> "Task paused at its limit";
            case REVIEW_PENDING -> "Waiting for review";
        };
        String detail = reason.replaceAll("[\\r\\n\\t\\p{Cntrl}]", " ");
        if (detail.length() > 240) {
            int end = 240; if (Character.isHighSurrogate(detail.charAt(end - 1))) end--;
            detail = detail.substring(0, end) + "…";
        }
        return label + " · " + detail;
    }

    private static Scope scope(List<ArcadiaAgent.Step> plan) { return plan.isEmpty() ? Scope.RESPONSE : Scope.DECLARED_PLAN; }
    private static String hostReason(String reason) {
        if (reason == null || reason.isBlank()) return "The task cannot continue from the available evidence.";
        String safe = reason.replace('\0', ' ');
        if (safe.length() <= MAX_REASON_CHARS) return safe;
        int end = MAX_REASON_CHARS - 1; if (Character.isHighSurrogate(safe.charAt(end - 1))) end--;
        return safe.substring(0, end) + "…";
    }
}
