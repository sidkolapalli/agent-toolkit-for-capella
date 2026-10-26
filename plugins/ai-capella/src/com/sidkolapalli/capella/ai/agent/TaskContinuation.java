package com.sidkolapalli.capella.ai.agent;

import java.util.List;
import java.util.Optional;

/**
 * The next request for a task that stopped only because its per-request budget ran out. Each
 * continuation is a fresh request with its own budget; its proposals are reviewed like any other.
 * Blocked, unconfirmed or failed-save endings never continue automatically.
 */
public final class TaskContinuation {
    public static final String PREFIX = "CONTINUE TASK:";

    private TaskContinuation() {}

    /**
     * A request that used its last allowed proposal and still has edit steps pending was stopped by
     * the budget, however the model phrased its ending. A question for the user is never
     * overridden.
     */
    public static TaskTermination afterRequest(
            TaskTermination ending,
            List<ArcadiaAgent.Step> plan,
            int proposalsUsed,
            int proposalBudget) {
        if (ending == null
                || proposalsUsed < proposalBudget
                || ending.state() != TaskTermination.State.BLOCKED) return ending;
        boolean editPending =
                plan.stream()
                        .anyMatch(
                                step ->
                                        "edit".equals(step.type())
                                                && !"completed".equals(step.status()));
        return editPending
                ? TaskTermination.budget(
                        "All "
                                + proposalBudget
                                + " proposals of this request were used; the remaining planned"
                                + " edits continue in a fresh request.",
                        plan)
                : ending;
    }

    public static Optional<String> prompt(
            List<ArcadiaAgent.Step> plan, TaskTermination termination) {
        if (termination == null || termination.state() != TaskTermination.State.BUDGET_EXHAUSTED)
            return Optional.empty();
        Optional<ArcadiaAgent.Step> next =
                plan.stream()
                        .filter(
                                step ->
                                        "edit".equals(step.type())
                                                && !"completed".equals(step.status()))
                        .findFirst();
        if (next.isEmpty()) return Optional.empty();
        StringBuilder text =
                new StringBuilder(PREFIX)
                        .append(
                                " The previous request used up its per-request budget. Continue the"
                                    + " same task.\n");
        List<ArcadiaAgent.Step> done =
                plan.stream().filter(step -> "completed".equals(step.status())).toList();
        if (!done.isEmpty()) {
            text.append("Verified completed steps (do not redo them):\n");
            done.forEach(step -> text.append("- ").append(step.title()).append('\n'));
        }
        text.append("Remaining steps:\n");
        plan.stream()
                .filter(step -> !"completed".equals(step.status()))
                .forEach(
                        step ->
                                text.append("- ")
                                        .append(step.title())
                                        .append(" (")
                                        .append(step.type())
                                        .append(")\n"));
        return Optional.of(
                text.append("Work on \"")
                        .append(next.get().title())
                        .append("\" now. ")
                        .append(
                                "Re-inspect the current model first; earlier results are history,"
                                    + " not current state. ")
                        .append("Declare a plan containing only the remaining steps, all pending.")
                        .toString());
    }
}
