package org.polarsys.capella.ai.agent;

import static org.junit.Assert.*;

import org.junit.Test;
import org.polarsys.capella.ai.codex.CodexArcadiaAgent;
import org.polarsys.capella.ai.codex.CodexToolSchemas;
import org.polarsys.capella.ai.codex.CodexTransport;
import org.polarsys.capella.ai.llm.LLMService.Message;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

/**
 * Deterministic phase termination; no providers, model writes, processes or native UI are invoked.
 */
public class TaskTerminationTest {
    private static final List<ArcadiaAgent.Step> PENDING =
            List.of(new ArcadiaAgent.Step("edit", "Add the interface", "pending", "edit"));

    @Test
    public void oldTurnConstructorsRemainCompatibleAndScopeAnOrdinaryReplyToItsResponse() {
        var oldSeven = new ArcadiaAgent.Turn("Answer", null, List.of(), List.of(), 1, 0, Set.of());
        var oldEight =
                new ArcadiaAgent.Turn(
                        "Answer", null, List.of(), List.of(), 1, 0, Set.of(), List.of());
        assertEquals(oldSeven, oldEight);
        assertEquals(TaskTermination.State.COMPLETE, oldSeven.termination().state());
        assertEquals(TaskTermination.Scope.RESPONSE, oldSeven.termination().scope());
        assertTrue(oldSeven.termination().activityText().startsWith("Reply ready"));
        assertTrue(oldSeven.completedStepIds().isEmpty());
        assertTrue(oldSeven.termination().reason().contains("no model task completion"));
    }

    @Test
    public void completionDeclarationsCannotAdvanceAuthoritativePendingSteps() {
        var turn =
                new ArcadiaAgent.Turn(
                        "Complete",
                        null,
                        PENDING,
                        List.of(),
                        1,
                        0,
                        Set.of(),
                        List.of(),
                        new TaskTermination(
                                TaskTermination.State.COMPLETE,
                                "Done",
                                TaskTermination.Scope.RESPONSE));
        assertEquals(TaskTermination.State.BLOCKED, turn.termination().state());
        assertEquals(TaskTermination.Scope.DECLARED_PLAN, turn.termination().scope());
        assertEquals(Set.of("edit"), turn.pendingStepIds());
        assertTrue(turn.completedStepIds().isEmpty());
        assertEquals("pending", turn.plan().get(0).status());
        assertThrows(UnsupportedOperationException.class, () -> turn.pendingStepIds().clear());
        assertEquals(
                TaskTermination.State.BLOCKED,
                new ArcadiaAgent.Turn("Legacy final", null, PENDING, List.of(), 1, 0, Set.of())
                        .termination()
                        .state());
    }

    @Test
    public void hostOwnsReviewAndBudgetAndScopesCompletedPlansWithoutEngineeringClaims() {
        var review =
                new ArcadiaAgent.Turn(
                        "Review",
                        "return 1",
                        PENDING,
                        List.of(),
                        1,
                        0,
                        Set.of("edit"),
                        List.of(),
                        new TaskTermination(
                                TaskTermination.State.COMPLETE,
                                "Done",
                                TaskTermination.Scope.RESPONSE));
        assertEquals(TaskTermination.State.REVIEW_PENDING, review.termination().state());
        for (String fake : List.of("review_pending", "budget_exhausted")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            TaskTermination.fromFinal(
                                    Map.of("state", fake, "reason", "Fake host claim"), PENDING));
        }
        var completed =
                List.of(new ArcadiaAgent.Step("edit", "Observed interface", "completed", "edit"));
        var result =
                new ArcadiaAgent.Turn("Actual outcome", null, completed, List.of(), 2, 0, Set.of());
        assertEquals(TaskTermination.State.COMPLETE, result.termination().state());
        assertEquals(Set.of("edit"), result.completedStepIds());
        assertTrue(
                result.termination()
                        .reason()
                        .contains("does not establish engineering completeness or compliance"));
        assertTrue(result.termination().activityText().startsWith("Declared plan checked"));
    }

    @Test
    public void inputAndBlockedReasonsAreBoundedDataAndDoNotInventStepCompletion() {
        for (String state : List.of("needs_input", "blocked")) {
            var status =
                    TaskTermination.fromFinal(
                            Map.of(
                                    "state",
                                    state,
                                    "reason",
                                    "Choose the intended system boundary."),
                            PENDING);
            assertEquals(TaskTermination.Scope.DECLARED_PLAN, status.scope());
            assertTrue(status.feedback().get("state").equals(state));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> status.feedback().put("state", "complete"));
        }
        for (Map<String, Object> bad :
                List.of(
                        Map.<String, Object>of("state", "needs_input"),
                        Map.<String, Object>of("state", "blocked", "reason", " "),
                        Map.<String, Object>of("state", "needs_input", "reason", "x".repeat(601)),
                        Map.<String, Object>of("state", "blocked", "reason", "bad\0reason"),
                        Map.<String, Object>of("state", "complete", "scope", "engineering"),
                        Map.<String, Object>of(
                                "state", "complete", "reason", Map.of("eval", "anything")))) {
            assertThrows(
                    IllegalArgumentException.class, () -> TaskTermination.fromFinal(bad, PENDING));
        }
        assertEquals(
                TaskTermination.State.COMPLETE,
                TaskTermination.fromFinal(Map.of("state", "complete"), List.of()).state());
        assertEquals(600, TaskTermination.blocked("x".repeat(1000), List.of()).reason().length());
    }

    @Test
    public void intermediateReceiptSnapshotCanRemainNonterminalWithoutLosingPlanEvidence() {
        var turn =
                new ArcadiaAgent.Turn(
                        "Recorded execution",
                        null,
                        PENDING,
                        List.of(),
                        2,
                        0,
                        Set.of(),
                        List.of(),
                        null);
        assertNull(turn.termination());
        assertEquals(Set.of("edit"), turn.pendingStepIds());
        assertFalse(turn.hasProposal());
    }

    @Test
    public void directFinalClarificationAndLegacyQuestionRemainDistinct() {
        var provider =
                new Provider(
                        plan(),
                        finalAnswer(
                                "Which boundary should own the interface?",
                                "needs_input",
                                "Choose the intended system boundary."));
        var agent = direct(provider, ArcadiaAgent.Limits.defaults(), new AtomicInteger());
        var result = run(agent);
        assertEquals(TaskTermination.State.NEEDS_INPUT, result.termination().state());
        assertEquals(Set.of("edit"), result.pendingStepIds());
        assertEquals(2, provider.calls);
        assertTrue(result.reply().startsWith("Which boundary"));
        var question =
                run(
                        direct(
                                new Provider("A question mark is ordinary prose?"),
                                ArcadiaAgent.Limits.defaults(),
                                new AtomicInteger()));
        assertEquals(TaskTermination.State.COMPLETE, question.termination().state());
        assertEquals(TaskTermination.Scope.RESPONSE, question.termination().scope());
    }

    @Test
    public void directContradictoryCompleteAndExplicitBlockedNeverCompletePendingWork() {
        for (String state : List.of("complete", "blocked")) {
            var result =
                    run(
                            direct(
                                    new Provider(
                                            plan(),
                                            finalAnswer(
                                                    "The final response stopped.",
                                                    state,
                                                    "Prerequisite missing.")),
                                    ArcadiaAgent.Limits.defaults(),
                                    new AtomicInteger()));
            assertEquals(TaskTermination.State.BLOCKED, result.termination().state());
            assertEquals(Set.of("edit"), result.pendingStepIds());
        }
    }

    @Test
    public void directBudgetsAreHostFactsAndPreservePendingStepsWithoutMoreReads() {
        var limit = new ArcadiaAgent.Limits(1, 0, 200_000, 32_000, 120_000, false);
        AtomicInteger reads = new AtomicInteger();
        var provider = new Provider(plan());
        var failure =
                assertThrows(
                        ArcadiaAgent.AgentException.class,
                        () -> run(direct(provider, limit, reads)));
        assertEquals(TaskTermination.State.BUDGET_EXHAUSTED, failure.turn().termination().state());
        assertEquals(Set.of("edit"), failure.turn().pendingStepIds());
        assertEquals(1, provider.calls);
        assertEquals(0, reads.get());
        var tool =
                AgentJson.write(
                        map("kind", "tool", "toolName", "model_summary", "arguments", Map.of()));
        failure =
                assertThrows(
                        ArcadiaAgent.AgentException.class,
                        () ->
                                run(
                                        direct(
                                                new Provider(tool),
                                                new ArcadiaAgent.Limits(
                                                        2, 0, 200_000, 32_000, 120_000, false),
                                                reads)));
        assertEquals(TaskTermination.State.BUDGET_EXHAUSTED, failure.turn().termination().state());
        assertEquals(0, reads.get());
    }

    @Test
    public void
            freshDirectRequestDoesNotInheritPreviousClarificationOrBudgetsAndStopStaysCancellation() {
        var provider =
                new Provider(
                        plan(),
                        finalAnswer("Which boundary?", "needs_input", "Choose a boundary."),
                        finalAnswer("Evidence read.", "complete", "Reply ready."));
        var agent = direct(provider, ArcadiaAgent.Limits.defaults(), new AtomicInteger());
        var first = run(agent);
        var next =
                agent.run(
                        "Native instructions",
                        first.messages(),
                        "Just explain the architecture",
                        () -> false,
                        null);
        assertEquals(TaskTermination.State.NEEDS_INPUT, first.termination().state());
        assertEquals(TaskTermination.State.COMPLETE, next.termination().state());
        assertTrue(next.plan().isEmpty());
        assertEquals(1, next.requests());
        AtomicInteger calls = new AtomicInteger();
        var stopped =
                new ArcadiaAgent(
                        messages -> {
                            calls.incrementAndGet();
                            return finalAnswer("Ignored", "complete", "Ignored");
                        },
                        (tool, args) -> "unused",
                        Set.of(),
                        ArcadiaAgent.Limits.defaults());
        assertThrows(
                CancellationException.class,
                () -> stopped.run("Native", List.of(), "Stop", () -> true, null));
        assertEquals(0, calls.get());
    }

    @Test
    public void directPostExecutionFinalCannotCompleteRemainingReadSteps() {
        var read =
                map(
                        "id",
                        "verify",
                        "title",
                        "Inspect diagnostics",
                        "type",
                        "read",
                        "status",
                        "pending");
        var provider =
                new Provider(
                        AgentJson.write(map("kind", "plan", "steps", List.of(editStep(), read))),
                        AgentJson.write(
                                map(
                                        "kind",
                                        "proposal",
                                        "message",
                                        "Review",
                                        "script",
                                        "return 1",
                                        "stepIds",
                                        List.of("edit"))),
                        finalAnswer("Change applied.", "complete", "Everything done."));
        var agent = direct(provider, ArcadiaAgent.Limits.defaults(), new AtomicInteger());
        var review = run(agent);
        var done =
                agent.finish(
                        review,
                        new ArcadiaAgent.ExecutionOutcome(
                                true, true, true, "Actual committed receipt", Set.of("edit")),
                        () -> false,
                        null);
        assertEquals(TaskTermination.State.BLOCKED, done.termination().state());
        assertEquals(Set.of("edit"), done.completedStepIds());
        assertEquals(Set.of("verify"), done.pendingStepIds());
        assertTrue(
                done.messages().stream()
                        .anyMatch(
                                message ->
                                        message.getContent().contains("Actual committed receipt")));
    }

    @Test
    public void codexChosenFinalSupportsClarificationAndPendingCompleteIsBlocked() {
        for (String state : List.of("needs_input", "complete")) {
            var fake = new Fake();
            fake.events.add(planEvent());
            fake.events.add(done(finalAnswer("Which boundary?", state, "Choose a boundary.")));
            try (var agent = codex(fake, 24)) {
                var result = run(agent);
                assertEquals("Which boundary?", result.reply());
                assertEquals(Set.of("edit"), result.pendingStepIds());
                assertEquals(
                        state.equals("complete")
                                ? TaskTermination.State.BLOCKED
                                : TaskTermination.State.NEEDS_INPUT,
                        result.termination().state());
                assertEquals(TaskTermination.Scope.DECLARED_PLAN, result.termination().scope());
            }
        }
    }

    @Test
    public void codexCommentaryDoesNotBecomeTerminalMetadataAndOrdinaryJsonRemainsProse() {
        var fake = new Fake();
        fake.events.add(
                notification(
                        "item/completed",
                        map(
                                "item",
                                item(
                                        "comment",
                                        "commentary",
                                        finalAnswer("Question", "needs_input", "Choose.")))));
        fake.events.add(done("A grounded reply."));
        try (var agent = codex(fake, 24)) {
            var result = run(agent);
            assertEquals("A grounded reply.", result.reply());
            assertEquals(TaskTermination.State.COMPLETE, result.termination().state());
            assertEquals(TaskTermination.Scope.RESPONSE, result.termination().scope());
        }
        fake = new Fake();
        fake.events.add(done("{\"observed\":\"ordinary model JSON\"}"));
        try (var agent = codex(fake, 24)) {
            assertEquals("{\"observed\":\"ordinary model JSON\"}", run(agent).reply());
        }
    }

    @Test
    public void codexRejectsInvalidChosenFinalMetadataAndOwnsBudgetExhaustion() {
        for (String invalid :
                List.of(
                        finalAnswer("Invalid", "review_pending", "Fake host state"),
                        finalAnswer("Invalid", "needs_input", "x".repeat(601)),
                        "{\"kind\":\"final\",\"message\":\"Malformed\",\"task\":",
                        AgentJson.write(
                                map("kind", "final", "message", "Invalid", "task", null)))) {
            var fake = new Fake();
            fake.events.add(done(invalid));
            try (var agent = codex(fake, 24)) {
                var failure = assertThrows(ArcadiaAgent.AgentException.class, () -> run(agent));
                assertEquals(TaskTermination.State.BLOCKED, failure.turn().termination().state());
                assertFalse(failure.turn().hasProposal());
            }
        }
        var fake = new Fake();
        fake.events.add(planEvent());
        fake.events.add(call("read", "model_summary", Map.of()));
        try (var agent = codex(fake, 1)) {
            var failure = assertThrows(ArcadiaAgent.AgentException.class, () -> run(agent));
            assertEquals(
                    TaskTermination.State.BUDGET_EXHAUSTED, failure.turn().termination().state());
            assertEquals(Set.of("edit"), failure.turn().pendingStepIds());
            assertEquals(0, fake.reads.get());
            assertEquals(-32000, fake.rejected.get("read").intValue());
        }
    }

    private static ArcadiaAgent direct(
            Provider provider, ArcadiaAgent.Limits limits, AtomicInteger reads) {
        return new ArcadiaAgent(
                provider,
                (name, args) -> {
                    reads.incrementAndGet();
                    return "{\"ok\":true}";
                },
                Set.of("model_summary"),
                limits);
    }

    private static ArcadiaAgent.Turn run(AgentRuntime agent) {
        return agent.run(
                "Captured native instructions",
                List.of(),
                "Help with the architecture",
                () -> false,
                null);
    }

    private static CodexArcadiaAgent codex(Fake fake, int tools) {
        return new CodexArcadiaAgent(
                fake,
                (name, args) -> {
                    fake.reads.incrementAndGet();
                    return "{\"ok\":true}";
                },
                Set.of("model_summary"),
                thread -> {},
                null,
                null,
                "E:/synthetic-capella",
                UnaryOperator.identity(),
                UnaryOperator.identity(),
                new CodexArcadiaAgent.Limits(32, tools, 3, Duration.ofSeconds(5)));
    }

    private static String plan() {
        return AgentJson.write(map("kind", "plan", "steps", List.of(editStep())));
    }

    private static Map<String, Object> editStep() {
        return map("id", "edit", "title", "Add the interface", "type", "edit", "status", "pending");
    }

    private static String finalAnswer(String reply, String state, String reason) {
        return AgentJson.write(
                map(
                        "kind",
                        "final",
                        "message",
                        reply,
                        "task",
                        map("state", state, "reason", reason)));
    }

    private static Map<String, Object> planEvent() {
        return call(
                "plan",
                CodexToolSchemas.PLAN,
                map("message", "Plan", "steps", List.of(editStep())));
    }

    private static Map<String, Object> call(String id, String tool, Map<String, Object> arguments) {
        return map(
                "id",
                id,
                "method",
                "item/tool/call",
                "params",
                map(
                        "threadId",
                        "thread-1",
                        "turnId",
                        "turn-1",
                        "callId",
                        "call-" + id,
                        "tool",
                        tool,
                        "arguments",
                        arguments));
    }

    private static Map<String, Object> done(String reply) {
        return notification(
                "turn/completed",
                map(
                        "turn",
                        map(
                                "id",
                                "turn-1",
                                "status",
                                "completed",
                                "items",
                                List.of(item("answer", "final_answer", reply)))));
    }

    private static Map<String, Object> item(String id, String phase, String text) {
        return map("type", "agentMessage", "id", id, "phase", phase, "text", text);
    }

    private static Map<String, Object> notification(String method, Map<String, Object> parameters) {
        parameters.put("threadId", "thread-1");
        parameters.put("turnId", "turn-1");
        return map("method", method, "params", parameters);
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }

    private static final class Provider implements ArcadiaAgent.Provider {
        final Deque<String> replies;
        int calls;

        Provider(String... replies) {
            this.replies = new ArrayDeque<>(List.of(replies));
        }

        @Override
        public String send(List<Message> messages) {
            calls++;
            assertFalse(replies.isEmpty());
            return replies.removeFirst();
        }
    }

    private static final class Fake implements CodexTransport {
        final Deque<Map<String, Object>> events = new ArrayDeque<>();
        final AtomicInteger reads = new AtomicInteger();
        final Map<Object, Integer> rejected = new LinkedHashMap<>();

        @Override
        public Map<String, Object> request(
                String method, Map<String, Object> parameters, BooleanSupplier cancelled) {
            return switch (method) {
                case "initialize", "turn/interrupt" -> Map.of();
                case "account/read" ->
                        map("requiresOpenaiAuth", true, "account", map("type", "chatgpt"));
                case "thread/start", "thread/resume" -> map("thread", map("id", "thread-1"));
                case "turn/start" -> map("turn", map("id", "turn-1"));
                default -> throw new AssertionError(method);
            };
        }

        @Override
        public void notify(String method, Map<String, Object> parameters) {
            assertEquals("initialized", method);
        }

        @Override
        public Map<String, Object> nextEvent(BooleanSupplier cancelled, Duration timeout) {
            return events.isEmpty() ? null : events.removeFirst();
        }

        @Override
        public void respond(Object id, Map<String, Object> response) {}

        @Override
        public void reject(Object id, int code, String reason) {
            rejected.put(id, code);
        }

        @Override
        public void close() {}
    }
}
