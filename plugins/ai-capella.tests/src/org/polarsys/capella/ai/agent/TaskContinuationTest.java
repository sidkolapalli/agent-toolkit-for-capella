package org.polarsys.capella.ai.agent;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.List;

/**
 * A task that ran out of per-request budget continues with its next pending edit; nothing else
 * continues.
 */
public class TaskContinuationTest {
    private static final List<ArcadiaAgent.Step> PLAN =
            List.of(
                    new ArcadiaAgent.Step(
                            "inspect", "Inspect the SA functions", "completed", "read"),
                    new ArcadiaAgent.Step(
                            "create", "Create the shutdown functions", "completed", "edit"),
                    new ArcadiaAgent.Step("link", "Connect the shutdown chain", "pending", "edit"),
                    new ArcadiaAgent.Step(
                            "allocate", "Allocate functions to components", "pending", "edit"));

    @Test
    public void budgetExhaustionWithPendingEditsContinuesWithTheNextEdit() {
        String prompt =
                TaskContinuation.prompt(
                                PLAN, TaskTermination.budget("Three proposals were handled.", PLAN))
                        .orElseThrow();
        assertTrue(prompt.startsWith(TaskContinuation.PREFIX));
        assertTrue("Names the next step", prompt.contains("Connect the shutdown chain"));
        assertTrue(
                "Keeps the rest of the scope", prompt.contains("Allocate functions to components"));
        assertTrue(
                "Reports verified work as done", prompt.contains("Create the shutdown functions"));
        assertTrue("Never authorizes replay", prompt.toLowerCase().contains("do not redo"));
    }

    @Test
    public void otherEndingsNeverContinueAutomatically() {
        assertTrue(
                TaskContinuation.prompt(
                                PLAN, TaskTermination.blocked("Save failed after commit.", PLAN))
                        .isEmpty());
        assertTrue(
                TaskContinuation.prompt(
                                PLAN,
                                new TaskTermination(
                                        TaskTermination.State.NEEDS_INPUT,
                                        "Which component owns the relay?",
                                        TaskTermination.Scope.DECLARED_PLAN))
                        .isEmpty());
        assertTrue(TaskContinuation.prompt(PLAN, TaskTermination.review(PLAN)).isEmpty());
        assertTrue(TaskContinuation.prompt(PLAN, null).isEmpty());
    }

    /**
     * Live Codex stopped by itself after the third proposal ("D requires a new task"); the host
     * knows why.
     */
    @Test
    public void aStopAfterTheLastAllowedProposalIsTheBudgetNotABlocker() {
        TaskTermination modelStopped = TaskTermination.blocked("D requires a new task.", PLAN);
        TaskTermination ending = TaskContinuation.afterRequest(modelStopped, PLAN, 3, 3);
        assertEquals(TaskTermination.State.BUDGET_EXHAUSTED, ending.state());
        assertTrue(TaskContinuation.prompt(PLAN, ending).isPresent());
        assertSame(
                "Budget left: the model's own ending stands",
                modelStopped,
                TaskContinuation.afterRequest(modelStopped, PLAN, 2, 3));
        TaskTermination question =
                new TaskTermination(
                        TaskTermination.State.NEEDS_INPUT,
                        "Which component owns the relay?",
                        TaskTermination.Scope.DECLARED_PLAN);
        assertSame(
                "A question for the user is never skipped",
                question,
                TaskContinuation.afterRequest(question, PLAN, 3, 3));
        var done = List.of(new ArcadiaAgent.Step("edit", "Edit", "completed", "edit"));
        TaskTermination complete =
                TaskTermination.fromFinal(java.util.Map.of("state", "complete"), done);
        assertSame(complete, TaskContinuation.afterRequest(complete, done, 3, 3));
        assertNull(TaskContinuation.afterRequest(null, PLAN, 3, 3));
    }

    @Test
    public void budgetExhaustionWithOnlyReadsOrNothingPendingStops() {
        var reads = List.of(new ArcadiaAgent.Step("inspect", "Inspect", "pending", "read"));
        assertTrue(
                TaskContinuation.prompt(reads, TaskTermination.budget("Out of reads.", reads))
                        .isEmpty());
        var done = List.of(new ArcadiaAgent.Step("edit", "Edit", "completed", "edit"));
        assertTrue(
                TaskContinuation.prompt(done, TaskTermination.budget("Out of requests.", done))
                        .isEmpty());
    }
}
