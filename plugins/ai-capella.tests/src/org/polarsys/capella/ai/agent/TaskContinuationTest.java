package org.polarsys.capella.ai.agent;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

/** A task that ran out of per-request budget continues with its next pending edit; nothing else continues. */
public class TaskContinuationTest {
    private static final List<ArcadiaAgent.Step> PLAN = List.of(
        new ArcadiaAgent.Step("inspect", "Inspect the SA functions", "completed", "read"),
        new ArcadiaAgent.Step("create", "Create the shutdown functions", "completed", "edit"),
        new ArcadiaAgent.Step("link", "Connect the shutdown chain", "pending", "edit"),
        new ArcadiaAgent.Step("allocate", "Allocate functions to components", "pending", "edit"));

    @Test public void budgetExhaustionWithPendingEditsContinuesWithTheNextEdit() {
        String prompt = TaskContinuation.prompt(PLAN, TaskTermination.budget("Three proposals were handled.", PLAN)).orElseThrow();
        assertTrue(prompt.startsWith(TaskContinuation.PREFIX));
        assertTrue("Names the next step", prompt.contains("Connect the shutdown chain"));
        assertTrue("Keeps the rest of the scope", prompt.contains("Allocate functions to components"));
        assertTrue("Reports verified work as done", prompt.contains("Create the shutdown functions"));
        assertTrue("Never authorizes replay", prompt.toLowerCase().contains("do not redo"));
    }

    @Test public void otherEndingsNeverContinueAutomatically() {
        assertTrue(TaskContinuation.prompt(PLAN, TaskTermination.blocked("Save failed after commit.", PLAN)).isEmpty());
        assertTrue(TaskContinuation.prompt(PLAN, new TaskTermination(TaskTermination.State.NEEDS_INPUT,
            "Which component owns the relay?", TaskTermination.Scope.DECLARED_PLAN)).isEmpty());
        assertTrue(TaskContinuation.prompt(PLAN, TaskTermination.review(PLAN)).isEmpty());
        assertTrue(TaskContinuation.prompt(PLAN, null).isEmpty());
    }

    @Test public void budgetExhaustionWithOnlyReadsOrNothingPendingStops() {
        var reads = List.of(new ArcadiaAgent.Step("inspect", "Inspect", "pending", "read"));
        assertTrue(TaskContinuation.prompt(reads, TaskTermination.budget("Out of reads.", reads)).isEmpty());
        var done = List.of(new ArcadiaAgent.Step("edit", "Edit", "completed", "edit"));
        assertTrue(TaskContinuation.prompt(done, TaskTermination.budget("Out of requests.", done)).isEmpty());
    }
}
