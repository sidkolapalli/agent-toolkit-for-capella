package com.sidkolapalli.capella.ai.codex;

import static org.junit.Assert.*;

import org.junit.Test;
import com.sidkolapalli.capella.ai.agent.AgentJson;
import com.sidkolapalli.capella.ai.agent.AgentResponseDecoder;
import com.sidkolapalli.capella.ai.agent.ArcadiaAgent;
import com.sidkolapalli.capella.ai.agent.TaskPreparation;
import com.sidkolapalli.capella.ai.agent.TaskTermination;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

/**
 * Native paused tool replies and fresh inference turns, without app-server processes or model
 * writes.
 */
public class CodexReadContinuationTest {
    @Test
    public void oneReadContinuationKeepsThreadCumulativeBudgetsAndActualEvidence() {
        Fake wire = new Fake();
        wire.add(plan(1, "p", "read"), done(1, "complete"), read(2, "r"), done(2, "complete"));
        List<ArcadiaAgent.Progress> progress = new ArrayList<>();
        try (var agent = agent(wire, defaults())) {
            var first = run(agent);
            assertTrue(agent.canContinueReadOnly(first));
            var last = agent.continueRemaining(first, () -> false, progress::add);
            assertEquals(Set.of("step"), last.completedStepIds());
            assertEquals(7, last.requests());
            assertEquals(2, last.toolCalls());
            assertEquals(1, wire.count("thread/start"));
            assertEquals(2, wire.count("turn/start"));
            assertFalse(agent.canContinueReadOnly(first));
            assertFalse(agent.canContinueReadOnly(last));
            assertTrue(
                    progress.stream()
                            .anyMatch(
                                    event ->
                                            "thinking".equals(event.type())
                                                    && event.message().contains("model evidence")));
            assertTrue(input(wire, 1).contains("READ-ONLY CONTINUATION TASK:"));
        }
    }

    @Test
    public void continuationProposalStillPausesForReviewAndCannotCompleteEdits() {
        Fake wire = new Fake();
        wire.add(plan(1, "p", "edit"), done(1, "complete"), proposal(2, "write", "return 1"));
        try (var agent = agent(wire, defaults())) {
            var last = agent.continueRemaining(run(agent), () -> false, null);
            assertTrue(last.hasProposal());
            assertEquals(TaskTermination.State.REVIEW_PENDING, last.termination().state());
            assertEquals(Set.of("step"), last.pendingStepIds());
            assertTrue(last.completedStepIds().isEmpty());
            assertFalse(wire.responses.containsKey("write"));
        }
    }

    @Test
    public void explicitPauseStopForeignSnapshotAndCloseDoNotContinue() {
        for (String state : List.of("blocked", "needs_input")) {
            Fake wire = new Fake();
            wire.add(plan(1, "p", "read"), done(1, state));
            try (var agent = agent(wire, defaults())) {
                var first = run(agent);
                assertFalse(agent.canContinueReadOnly(first));
            }
        }
        Fake wire = new Fake();
        wire.add(plan(1, "p", "read"), done(1, "complete"));
        AtomicBoolean stopped = new AtomicBoolean();
        var agent = agent(wire, defaults());
        var first = agent.run("Prompt", List.of(), "Original task", stopped::get, null);
        var copy =
                new ArcadiaAgent.Turn(
                        first.reply(),
                        first.proposalCode(),
                        first.plan(),
                        first.messages(),
                        first.requests(),
                        first.toolCalls(),
                        first.proposalStepIds(),
                        first.postconditions(),
                        first.termination());
        assertFalse(agent.canContinueReadOnly(copy));
        stopped.set(true);
        assertFalse(agent.canContinueReadOnly(first));
        agent.close();
        assertFalse(agent.canContinueReadOnly(first));
        assertEquals(1, wire.count("turn/start"));
    }

    @Test
    public void remainingRpcBudgetAndDeadlineAreNotResetForContinuation() throws Exception {
        Fake wire = new Fake();
        wire.add(plan(1, "p", "read"), done(1, "complete"));
        try (var agent =
                agent(wire, new CodexArcadiaAgent.Limits(5, 4, 1, Duration.ofSeconds(5)))) {
            var first = run(agent);
            var failure =
                    assertThrows(
                            ArcadiaAgent.AgentException.class,
                            () -> agent.continueRemaining(first, () -> false, null));
            assertEquals(
                    TaskTermination.State.BUDGET_EXHAUSTED, failure.turn().termination().state());
            assertEquals(1, wire.count("turn/start"));
        }
        Fake expired = new Fake();
        expired.add(plan(1, "p", "read"), done(1, "complete"));
        try (var agent = agent(expired, defaults())) {
            var first = run(agent);
            var stateField = CodexArcadiaAgent.class.getDeclaredField("state");
            stateField.setAccessible(true);
            Object state = stateField.get(agent);
            var deadline = state.getClass().getDeclaredField("deadline");
            deadline.setAccessible(true);
            deadline.setLong(state, System.nanoTime() - 1);
            var failure =
                    assertThrows(
                            ArcadiaAgent.AgentException.class,
                            () -> agent.continueRemaining(first, () -> false, null));
            assertEquals(
                    TaskTermination.State.BUDGET_EXHAUSTED, failure.turn().termination().state());
            assertEquals(1, expired.count("turn/start"));
        }
    }

    @Test
    public void pendingScopeCannotBeErasedByContinuationPlanRevision() {
        Fake wire = new Fake();
        var changed = plan(2, "changed", "read");
        object(
                        ((List<?>)
                                        object(object(changed.get("params")).get("arguments"))
                                                .get("steps"))
                                .get(0))
                .put("title", "Erase original scope");
        wire.add(plan(1, "p", "read"), done(1, "complete"), changed, done(2, "complete"));
        try (var agent = agent(wire, defaults())) {
            var last = agent.continueRemaining(run(agent), () -> false, null);
            assertEquals(Boolean.FALSE, wire.responses.get("changed").get("success"));
            assertEquals("Inspect scope", last.plan().get(0).title());
            assertEquals(Set.of("step"), last.pendingStepIds());
            assertFalse(agent.canContinueReadOnly(last));
        }
    }

    @Test
    public void compilationRepairAnswersPreparationOnceAndDiscardsOldFinalAnswer() {
        Fake wire = new Fake();
        wire.add(
                plan(1, "p", "edit"),
                proposal(1, "bad", "broken("),
                done(1, "complete"),
                proposal(2, "fixed", "return 1"));
        try (var agent =
                agent(wire, new CodexArcadiaAgent.Limits(32, 24, 1, Duration.ofSeconds(5)))) {
            var first = run(agent);
            var last =
                    agent.repairCompilation(first, "line 1: unexpected token", () -> false, null);
            assertEquals("return 1", last.proposalCode());
            assertEquals(first.proposalStepIds(), last.proposalStepIds());
            assertEquals(8, last.requests());
            assertEquals(3, last.toolCalls());
            assertTrue(last.completedStepIds().isEmpty());
            assertEquals(Boolean.FALSE, wire.responses.get("bad").get("success"));
            assertFalse(wire.responses.containsKey("fixed"));
            assertEquals(1, wire.answerCounts.get("bad").intValue());
            String receipt = text(wire.responses.get("bad"));
            assertTrue(receipt.startsWith(TaskPreparation.PREFIX));
            for (String field :
                    List.of(
                            "compiled",
                            "executed",
                            "applied",
                            "modelChanged",
                            "undoCreated",
                            "saveRequested"))
                assertTrue(receipt.contains("\"" + field + "\":false"));
            assertFalse(receipt.contains("EXECUTION OUTCOME"));
            assertNull(agent.outcomeSnapshot());
            assertFalse(
                    last.messages().stream()
                            .anyMatch(
                                    item ->
                                            item.getContent()
                                                    .contains("The old ungrounded answer")));
            assertTrue(input(wire, 1).contains("COMPILATION REPAIR TASK:"));
            assertFalse(input(wire, 1).contains("broken("));
            assertFalse(agent.canRepairCompilation(first));
        }
    }

    @Test
    public void compilationRepairHasIndependentTwoAttemptAllowance() {
        Fake wire = new Fake();
        wire.add(
                plan(1, "p", "edit"),
                proposal(1, "bad1", "bad("),
                done(1, "complete"),
                proposal(2, "bad2", "badAgain("),
                done(2, "complete"),
                proposal(3, "good", "return 1"));
        try (var agent =
                agent(wire, new CodexArcadiaAgent.Limits(32, 24, 1, Duration.ofSeconds(5)))) {
            var second = agent.repairCompilation(run(agent), "line 1", () -> false, null);
            var last = agent.repairCompilation(second, "line 1 again", () -> false, null);
            assertEquals("return 1", last.proposalCode());
            assertEquals(10, last.requests());
            assertEquals(4, last.toolCalls());
            assertFalse(agent.canRepairCompilation(last));
            assertThrows(
                    IllegalStateException.class,
                    () -> agent.repairCompilation(last, "third", () -> false, null));
            assertEquals(3, wire.count("turn/start"));
            assertEquals(1, wire.answerCounts.get("bad1").intValue());
            assertEquals(1, wire.answerCounts.get("bad2").intValue());
        }
    }

    @Test
    public void compileRepairBudgetAndCancellationDoNotTouchPendingExecutionReceipt() {
        Fake wire = new Fake();
        wire.add(plan(1, "p", "edit"), proposal(1, "bad", "bad("));
        try (var agent =
                agent(wire, new CodexArcadiaAgent.Limits(6, 10, 1, Duration.ofSeconds(5)))) {
            var first = run(agent);
            var failure =
                    assertThrows(
                            ArcadiaAgent.AgentException.class,
                            () -> agent.repairCompilation(first, "line 1", () -> false, null));
            assertEquals(
                    TaskTermination.State.BUDGET_EXHAUSTED, failure.turn().termination().state());
            assertFalse(wire.responses.containsKey("bad"));
            assertNull(agent.outcomeSnapshot());
            assertPreparationFailure(failure.turn(), "line 1");
            assertEquals(1, wire.count("turn/start"));
            assertEquals(6, failure.turn().requests());
        }
        Fake stopped = new Fake();
        stopped.add(plan(1, "p", "edit"), proposal(1, "bad", "bad("));
        try (var agent = agent(stopped, defaults())) {
            var first = run(agent);
            var failure =
                    assertThrows(
                            ArcadiaAgent.AgentException.class,
                            () -> agent.repairCompilation(first, "line 1", () -> true, null));
            assertFalse(stopped.responses.containsKey("bad"));
            assertNull(agent.outcomeSnapshot());
            assertPreparationFailure(failure.turn(), "line 1");
            assertEquals(1, stopped.count("turn/start"));
            assertEquals(6, failure.turn().requests());
        }
    }

    @Test
    public void expiredRepairDeadlineRetainsPreparationDiagnosticWithoutNativeToolReply()
            throws Exception {
        Fake wire = new Fake();
        wire.add(plan(1, "p", "edit"), proposal(1, "bad", "bad("));
        try (var agent = agent(wire, defaults())) {
            var first = run(agent);
            var stateField = CodexArcadiaAgent.class.getDeclaredField("state");
            stateField.setAccessible(true);
            Object state = stateField.get(agent);
            var deadline = state.getClass().getDeclaredField("deadline");
            deadline.setAccessible(true);
            deadline.setLong(state, System.nanoTime() - 1);
            var failure =
                    assertThrows(
                            ArcadiaAgent.AgentException.class,
                            () ->
                                    agent.repairCompilation(
                                            first, "line 1 column 5", () -> false, null));
            assertEquals(
                    TaskTermination.State.BUDGET_EXHAUSTED, failure.turn().termination().state());
            assertPreparationFailure(failure.turn(), "line 1 column 5");
            assertFalse(wire.responses.containsKey("bad"));
            assertEquals(1, wire.count("turn/start"));
            assertEquals(6, failure.turn().requests());
            assertEquals(2, failure.turn().toolCalls());
        }
    }

    @Test
    public void repairFreshInputNeverResendsDecodedQuotedGroovySource() {
        String label = "Driver's \\ Battery\n$ Ω";
        Fake wire = new Fake();
        wire.add(
                plan(1, "p", "edit"),
                proposal(1, "bad", "api.rename('id', 'ELEM_0001'); broken("),
                done(1, "complete"),
                proposal(2, "good", "api.rename('id', 'ELEM_0001')"),
                done(2, "complete"),
                done(3, "complete"));
        UnaryOperator<String> decode =
                json ->
                        AgentResponseDecoder.decode(
                                json, value -> value.replace("ELEM_0001", label));
        UnaryOperator<String> encode =
                value ->
                        AgentResponseDecoder.encodeMessage(
                                value, text -> text.replace(label, "ELEM_0001"));
        try (var agent =
                new CodexArcadiaAgent(
                        wire,
                        (name, args) -> "{\"ok\":true}",
                        Set.of("model_summary"),
                        ignored -> {},
                        null,
                        null,
                        "E:/fixture",
                        decode,
                        encode,
                        defaults())) {
            var repaired =
                    agent.repairCompilation(
                            run(agent), "line 1 column 40: unexpected token", () -> false, null);
            assertTrue(repaired.proposalCode().contains("Driver\\'s"));
            assertFalse(input(wire, 1).contains("Driver"));
            assertFalse(input(wire, 1).contains("api.rename"));
            var result =
                    agent.finish(
                            repaired,
                            new ArcadiaAgent.ExecutionOutcome(
                                    true, true, true, "Independently checked", Set.of("step")),
                            () -> false,
                            null);
            assertFalse(input(wire, 2).contains("Driver"));
            assertFalse(input(wire, 2).contains("api.rename"));
            assertEquals(Set.of("step"), result.completedStepIds());
        }
    }

    @Test
    public void committedSaveFailureAndDismissedOutcomeBlockAutomaticReadContinuation() {
        for (var outcome :
                List.of(
                        new ArcadiaAgent.ExecutionOutcome(
                                true, true, false, "Save failed", Set.of()),
                        new ArcadiaAgent.ExecutionOutcome(
                                false, false, false, "Dismissed", Set.of()))) {
            Fake wire = new Fake();
            wire.add(
                    plan(1, "p", "edit"),
                    proposal(1, "write", "return 1"),
                    done(1, "complete"),
                    done(2, "complete"));
            try (var agent = agent(wire, defaults())) {
                var last = agent.finish(run(agent), outcome, () -> false, null);
                assertFalse(agent.canContinueReadOnly(last));
                assertNotNull(agent.outcomeSnapshot());
                assertTrue(text(wire.responses.get("write")).contains("EXECUTION OUTCOME"));
            }
        }
    }

    private static CodexArcadiaAgent.Limits defaults() {
        return new CodexArcadiaAgent.Limits(32, 24, 3, Duration.ofSeconds(5));
    }

    private static CodexArcadiaAgent agent(Fake wire, CodexArcadiaAgent.Limits limits) {
        return new CodexArcadiaAgent(
                wire,
                (name, args) -> "{\"ok\":true}",
                Set.of("model_summary"),
                ignored -> {},
                null,
                null,
                "E:/fixture",
                UnaryOperator.identity(),
                UnaryOperator.identity(),
                limits);
    }

    private static ArcadiaAgent.Turn run(CodexArcadiaAgent agent) {
        return agent.run("Prompt", List.of(), "Original task", () -> false, null);
    }

    private static Map<String, Object> plan(int turn, String id, String type) {
        return call(
                turn,
                id,
                CodexToolSchemas.PLAN,
                map(
                        "message",
                        "Plan",
                        "steps",
                        List.of(
                                map(
                                        "id",
                                        "step",
                                        "title",
                                        "Inspect scope",
                                        "type",
                                        type,
                                        "status",
                                        "pending"))));
    }

    private static Map<String, Object> read(int turn, String id) {
        return call(turn, id, "model_summary", map("stepId", "step"));
    }

    private static Map<String, Object> proposal(int turn, String id, String script) {
        return call(
                turn,
                id,
                CodexToolSchemas.PROPOSE,
                map(
                        "message",
                        "Review this exact change",
                        "script",
                        script,
                        "stepIds",
                        List.of("step")));
    }

    private static Map<String, Object> call(
            int turn, String id, String tool, Map<String, Object> args) {
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
                        "turn-" + turn,
                        "callId",
                        "call-" + id,
                        "tool",
                        tool,
                        "arguments",
                        args));
    }

    private static Map<String, Object> done(int turn, String state) {
        return map(
                "method",
                "turn/completed",
                "params",
                map(
                        "threadId",
                        "thread-1",
                        "turnId",
                        "turn-" + turn,
                        "turn",
                        map(
                                "id",
                                "turn-" + turn,
                                "status",
                                "completed",
                                "items",
                                List.of(
                                        map(
                                                "type",
                                                "agentMessage",
                                                "id",
                                                "answer-" + turn,
                                                "phase",
                                                "final_answer",
                                                "text",
                                                AgentJson.write(
                                                        map(
                                                                "kind",
                                                                "final",
                                                                "message",
                                                                "The old ungrounded answer",
                                                                "task",
                                                                map(
                                                                        "state",
                                                                        state,
                                                                        "reason",
                                                                        "Response ended"))))))));
    }

    private static String input(Fake wire, int index) {
        return String.valueOf(
                object(
                                ((List<?>)
                                                wire.requests.stream()
                                                        .filter(
                                                                request ->
                                                                        "turn/start"
                                                                                .equals(
                                                                                        request.method))
                                                        .skip(index)
                                                        .findFirst()
                                                        .orElseThrow()
                                                        .params
                                                        .get("input"))
                                        .get(0))
                        .get("text"));
    }

    private static String text(Map<String, Object> response) {
        return String.valueOf(object(((List<?>) response.get("contentItems")).get(0)).get("text"));
    }

    private static void assertPreparationFailure(ArcadiaAgent.Turn turn, String diagnostic) {
        String receipt =
                turn.messages().stream()
                        .filter(message -> message.getContent().startsWith(TaskPreparation.PREFIX))
                        .findFirst()
                        .orElseThrow()
                        .getContent();
        Map<?, ?> facts =
                (Map<?, ?>)
                        AgentJson.parse(
                                receipt.substring(TaskPreparation.PREFIX.length() + 1), 10_000);
        assertEquals(diagnostic, facts.get("diagnostic"));
        assertEquals(Boolean.FALSE, facts.get("executed"));
        assertEquals(Boolean.FALSE, facts.get("modelChanged"));
        assertEquals(Boolean.FALSE, facts.get("undoCreated"));
        assertEquals(Boolean.FALSE, facts.get("saveRequested"));
        assertFalse(turn.hasProposal());
        assertTrue(turn.completedStepIds().isEmpty());
        assertEquals(Set.of("step"), turn.pendingStepIds());
        assertFalse(
                turn.messages().stream()
                        .anyMatch(
                                message ->
                                        message.getContent()
                                                .startsWith(
                                                        "AUTHORITATIVE CAPELLA EXECUTION"
                                                            + " OUTCOME")));
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private record Request(String method, Map<String, Object> params) {}

    private static final class Fake implements CodexTransport {
        final Deque<Map<String, Object>> events = new ArrayDeque<>();
        final List<Request> requests = new ArrayList<>();
        final Map<Object, Map<String, Object>> responses = new LinkedHashMap<>();
        final Map<Object, Integer> answerCounts = new LinkedHashMap<>();
        boolean closed;

        @SafeVarargs
        final void add(Map<String, Object>... events) {
            this.events.addAll(List.of(events));
        }

        long count(String method) {
            return requests.stream().filter(request -> method.equals(request.method)).count();
        }

        @Override
        public Map<String, Object> request(
                String method, Map<String, Object> params, BooleanSupplier cancelled) {
            if (cancelled.getAsBoolean()) throw new CancellationException();
            requests.add(new Request(method, params));
            return switch (method) {
                case "initialize", "turn/interrupt" -> Map.of();
                case "account/read" ->
                        map("requiresOpenaiAuth", true, "account", map("type", "chatgpt"));
                case "thread/start", "thread/resume" -> map("thread", map("id", "thread-1"));
                case "turn/start" -> map("turn", map("id", "turn-" + count("turn/start")));
                default -> throw new AssertionError("Unexpected RPC " + method);
            };
        }

        @Override
        public void notify(String method, Map<String, Object> params) {
            assertEquals("initialized", method);
        }

        @Override
        public Map<String, Object> nextEvent(BooleanSupplier cancelled, Duration timeout) {
            if (cancelled.getAsBoolean()) throw new CancellationException();
            return events.pollFirst();
        }

        @Override
        public void respond(Object id, Map<String, Object> result) {
            assertFalse("Duplicate response", responses.containsKey(id));
            responses.put(id, result);
            answerCounts.merge(id, 1, Integer::sum);
        }

        @Override
        public void reject(Object id, int code, String message) {
            throw new AssertionError("Unexpected rejected RPC " + id);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
