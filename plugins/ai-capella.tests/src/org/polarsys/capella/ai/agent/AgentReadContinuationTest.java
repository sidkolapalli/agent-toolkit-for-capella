package org.polarsys.capella.ai.agent;

import static org.junit.Assert.*;

import org.junit.Test;
import org.polarsys.capella.ai.llm.LLMService.Message;
import org.polarsys.capella.ai.privacy.Tokenizer;
import org.polarsys.capella.ai.scripting.GroovyCompilationValidator;
import org.polarsys.capella.ai.scripting.ScriptRouter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cumulative provider/read budgets and preparation receipts, with no model or execution callback.
 */
public class AgentReadContinuationTest {
    @Test
    public void onlyHostReconciledPrematureCompletionIsEligible() {
        var pending = List.of(new ArcadiaAgent.Step("inspect", "Inspect", "pending", "read"));
        var premature = TaskTermination.fromFinal(Map.of("state", "complete"), pending);
        assertTrue(premature.prematureCompletion());
        assertFalse(TaskTermination.blocked(premature.reason(), pending).prematureCompletion());
        assertFalse(
                TaskTermination.fromFinal(
                                Map.of("state", "needs_input", "reason", "Choose a boundary"),
                                pending)
                        .prematureCompletion());
        assertFalse(TaskTermination.budget("Limit", pending).prematureCompletion());
        assertFalse(TaskTermination.review(pending).prematureCompletion());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TaskTermination.fromFinal(
                                Map.of("state", "complete", "cause", "premature_complete"),
                                pending));
    }

    @Test
    public void prematureCompletionNamesThePendingSteps() {
        var plan =
                List.of(
                        new ArcadiaAgent.Step("create", "Create components", "completed", "edit"),
                        new ArcadiaAgent.Step(
                                "diagram", "Lay out the BMS diagram", "pending", "edit"));
        String reason = TaskTermination.fromFinal(Map.of("state", "complete"), plan).reason();
        assertTrue(
                reason,
                reason.startsWith(
                        "1 declared plan step remains pending or unverified: 'Lay out the BMS"
                            + " diagram' (edit)."));
        assertFalse(reason.contains("Create components"));
        var many = new ArrayList<ArcadiaAgent.Step>();
        for (int i = 0; i < 12; i++)
            many.add(new ArcadiaAgent.Step("s" + i, "x".repeat(240), "pending", "read"));
        assertTrue(
                TaskTermination.fromFinal(Map.of("state", "complete"), many).reason().length()
                        <= TaskTermination.MAX_REASON_CHARS);
    }

    @Test
    public void oneReadContinuationUsesActualEvidenceAndCumulativeCounters() {
        Provider provider = new Provider(plan("read"), complete(), read(), complete());
        AtomicInteger reads = new AtomicInteger();
        var agent = agent(provider, reads, ArcadiaAgent.Limits.defaults());
        var first = run(agent);
        assertTrue(agent.canContinueReadOnly(first));
        var last = agent.continueRemaining(first, () -> false, null);
        assertEquals(Set.of("inspect"), last.completedStepIds());
        assertEquals(4, last.requests());
        assertEquals(1, last.toolCalls());
        assertEquals(1, reads.get());
        assertEquals(TaskTermination.State.COMPLETE, last.termination().state());
        assertFalse(agent.canContinueReadOnly(first));
        assertFalse(agent.canContinueReadOnly(last));
        assertTrue(
                provider.inputs.get(2).stream()
                        .anyMatch(
                                item ->
                                        item.getContent()
                                                .startsWith("READ-ONLY CONTINUATION TASK:")));
    }

    @Test
    public void continuationCanReturnOnlyANewUnexecutedReviewProposal() {
        Provider provider = new Provider(plan("edit"), complete(), proposal("return 1"));
        AtomicInteger reads = new AtomicInteger();
        var agent = agent(provider, reads, ArcadiaAgent.Limits.defaults());
        var last = agent.continueRemaining(run(agent), () -> false, null);
        assertTrue(last.hasProposal());
        assertEquals(Set.of("inspect"), last.pendingStepIds());
        assertTrue(last.completedStepIds().isEmpty());
        assertEquals(TaskTermination.State.REVIEW_PENDING, last.termination().state());
        assertEquals(0, reads.get());
        assertFalse(agent.canContinueReadOnly(last));
    }

    @Test
    public void explicitPausePlainReplyStopAndForeignTurnDoNotContinue() {
        for (String terminal : List.of("blocked", "needs_input")) {
            Provider provider = new Provider(plan("read"), terminal(terminal));
            var agent = agent(provider, new AtomicInteger(), ArcadiaAgent.Limits.defaults());
            var first = run(agent);
            assertFalse(agent.canContinueReadOnly(first));
            assertThrows(
                    IllegalStateException.class,
                    () -> agent.continueRemaining(first, () -> false, null));
            assertEquals(2, provider.inputs.size());
        }
        Provider provider = new Provider(complete(), plan("read"), complete());
        var agent = agent(provider, new AtomicInteger(), ArcadiaAgent.Limits.defaults());
        assertFalse(agent.canContinueReadOnly(run(agent)));
        AtomicBoolean stopped = new AtomicBoolean();
        var first = agent.run("Prompt", List.of(), "Original request", stopped::get, null);
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
    }

    @Test
    public void remainingBudgetIsCheckedBeforeAnotherProviderRequest() {
        Provider provider = new Provider(plan("read"), complete());
        var limits = new ArcadiaAgent.Limits(2, 1, 200_000, 32_000, 120_000, false);
        var agent = agent(provider, new AtomicInteger(), limits);
        var first = run(agent);
        var failure =
                assertThrows(
                        ArcadiaAgent.AgentException.class,
                        () -> agent.continueRemaining(first, () -> false, null));
        assertEquals(TaskTermination.State.BUDGET_EXHAUSTED, failure.turn().termination().state());
        assertEquals(2, provider.inputs.size());
        assertEquals(Set.of("inspect"), failure.turn().pendingStepIds());
    }

    @Test
    public void continuationCannotEraseOrRetitlePendingScope() {
        String changed =
                AgentJson.write(
                        Map.of(
                                "kind",
                                "plan",
                                "steps",
                                List.of(
                                        Map.of(
                                                "id",
                                                "other",
                                                "title",
                                                "Smaller task",
                                                "type",
                                                "read",
                                                "status",
                                                "pending"))));
        Provider provider = new Provider(plan("read"), complete(), changed);
        var agent = agent(provider, new AtomicInteger(), ArcadiaAgent.Limits.defaults());
        var failure =
                assertThrows(
                        ArcadiaAgent.AgentException.class,
                        () -> agent.continueRemaining(run(agent), () -> false, null));
        assertEquals(Set.of("inspect"), failure.turn().pendingStepIds());
        assertTrue(failure.getMessage().contains("declared task scope"));
    }

    @Test
    public void compilationRepairHasTwoAttemptsAndOnlyPreparationFacts() {
        Provider provider =
                new Provider(
                        plan("edit"),
                        proposal("broken("),
                        proposal("stillBroken("),
                        proposal("return 1"));
        var agent = agent(provider, new AtomicInteger(), ArcadiaAgent.Limits.defaults());
        var first = run(agent);
        var second =
                agent.repairCompilation(
                        first, "line 1 column 8: unexpected input", () -> false, null);
        assertFalse(agent.canRepairCompilation(first));
        assertTrue(agent.canRepairCompilation(second));
        var last =
                agent.repairCompilation(
                        second, "line 1: expected closing token", () -> false, null);
        assertEquals("return 1", last.proposalCode());
        assertEquals(4, last.requests());
        assertEquals(0, last.toolCalls());
        assertEquals(first.proposalStepIds(), last.proposalStepIds());
        assertTrue(last.completedStepIds().isEmpty());
        assertFalse(agent.canRepairCompilation(last));
        assertThrows(
                IllegalStateException.class,
                () -> agent.repairCompilation(last, "third error", () -> false, null));
        var receipts =
                last.messages().stream()
                        .filter(item -> item.getContent().startsWith(TaskPreparation.PREFIX))
                        .toList();
        assertEquals(2, receipts.size());
        Map<?, ?> facts =
                (Map<?, ?>)
                        AgentJson.parse(
                                receipts.get(0)
                                        .getContent()
                                        .substring(TaskPreparation.PREFIX.length() + 1),
                                10_000);
        for (String key :
                List.of(
                        "compiled",
                        "executed",
                        "applied",
                        "modelChanged",
                        "undoCreated",
                        "saveRequested")) assertEquals(Boolean.FALSE, facts.get(key));
        assertTrue(
                last.messages().stream()
                        .noneMatch(
                                item ->
                                        item.getContent()
                                                .startsWith(
                                                        "AUTHORITATIVE CAPELLA EXECUTION"
                                                            + " OUTCOME")));
    }

    @Test
    public void compilationRepairPreservesScopeAndRejectsInvalidOrStaleDiagnostics() {
        Provider provider = new Provider(plan("edit"), proposal("bad("), proposal("return 1"));
        var agent = agent(provider, new AtomicInteger(), ArcadiaAgent.Limits.defaults());
        var first = run(agent);
        assertThrows(
                IllegalArgumentException.class,
                () -> agent.repairCompilation(first, "bad\0diagnostic", () -> false, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> agent.repairCompilation(first, " ", () -> false, null));
        var last = agent.repairCompilation(first, "x".repeat(9000), () -> false, null);
        String receipt =
                last.messages().stream()
                        .filter(item -> item.getContent().startsWith(TaskPreparation.PREFIX))
                        .findFirst()
                        .orElseThrow()
                        .getContent();
        Map<?, ?> facts =
                (Map<?, ?>)
                        AgentJson.parse(
                                receipt.substring(TaskPreparation.PREFIX.length() + 1), 10_000);
        assertEquals(8192, ((String) facts.get("diagnostic")).length());
        assertEquals(Boolean.TRUE, facts.get("diagnosticTruncated"));
        assertThrows(
                IllegalStateException.class,
                () -> agent.repairCompilation(first, "stale", () -> false, null));
    }

    @Test
    public void repairBudgetCancellationAndNewRequestDoNotResetTaskAllowances() {
        Provider provider = new Provider(plan("edit"), proposal("bad("));
        var agent =
                agent(
                        provider,
                        new AtomicInteger(),
                        new ArcadiaAgent.Limits(2, 4, 200_000, 32_000, 120_000, false));
        var first = run(agent);
        var failure =
                assertThrows(
                        ArcadiaAgent.AgentException.class,
                        () -> agent.repairCompilation(first, "line 1", () -> false, null));
        assertEquals(TaskTermination.State.BUDGET_EXHAUSTED, failure.turn().termination().state());
        assertEquals(2, provider.inputs.size());
        assertPreparationFailure(failure.turn(), "line 1");
        Provider nextProvider = new Provider(plan("edit"), proposal("bad("), complete());
        var next = agent(nextProvider, new AtomicInteger(), ArcadiaAgent.Limits.defaults());
        var old = run(next);
        var stopped =
                assertThrows(
                        ArcadiaAgent.AgentException.class,
                        () -> next.repairCompilation(old, "line 1", () -> true, null));
        assertEquals(2, nextProvider.inputs.size());
        assertEquals(TaskTermination.State.BLOCKED, stopped.turn().termination().state());
        assertPreparationFailure(stopped.turn(), "line 1");
        var newer = run(next);
        assertFalse(next.canRepairCompilation(old));
        assertFalse(next.canContinueReadOnly(newer));
    }

    @Test
    public void committedSaveFailureAndDismissedExecutionDoNotAutoContinue() {
        for (var outcome :
                List.of(
                        new ArcadiaAgent.ExecutionOutcome(
                                true, true, false, "Save failed; committed", Set.of()),
                        new ArcadiaAgent.ExecutionOutcome(
                                false, false, false, "Dismissed", Set.of()))) {
            Provider provider = new Provider(plan("edit"), proposal("return 1"), complete());
            var agent = agent(provider, new AtomicInteger(), ArcadiaAgent.Limits.defaults());
            var last = agent.finish(run(agent), outcome, () -> false, null);
            assertFalse(agent.canContinueReadOnly(last));
            assertTrue(
                    last.messages().stream()
                            .anyMatch(
                                    item ->
                                            item.getContent()
                                                    .contains(
                                                            "AUTHORITATIVE CAPELLA EXECUTION"
                                                                + " OUTCOME")));
        }
    }

    @Test
    public void malformedQuotedSourceRepairsThroughActualRouterPrivacyEncoderWithoutLeaking()
            throws Exception {
        Tokenizer tokenizer = Tokenizer.forSession("compilation-repair-" + UUID.randomUUID());
        String label = "Driver's \\ Battery\n$ Ω";
        String token = tokenizer.tokenizeDataValue(label);
        String badSource = "return 'Driver\\'s \\\\ Battery\\n$ Ω";
        Deque<String> replies =
                new ArrayDeque<>(
                        List.of(
                                plan("edit"),
                                proposal(badSource),
                                proposal("return '" + token + "'")));
        List<List<Message>> wireHistory = new ArrayList<>();
        var encoder =
                ScriptRouter.class.getDeclaredMethod(
                        "tokenizeAgentMessages", Tokenizer.class, List.class);
        encoder.setAccessible(true);
        var agent =
                new ArcadiaAgent(
                        messages -> {
                            @SuppressWarnings("unchecked")
                            List<Message> encoded =
                                    (List<Message>) encoder.invoke(null, tokenizer, messages);
                            wireHistory.add(encoded);
                            return AgentResponseDecoder.decode(
                                    replies.removeFirst(), tokenizer::detokenizeText);
                        },
                        (name, args) -> "{\"ok\":true}",
                        Set.of("model_summary"),
                        ArcadiaAgent.Limits.defaults());
        var first = run(agent);
        var failed = GroovyCompilationValidator.validate(first.proposalCode(), () -> false, 10000);
        assertEquals(GroovyCompilationValidator.Status.COMPILATION_ERROR, failed.status());
        var fixed = agent.repairCompilation(first, failed.formatForRepair(), () -> false, null);
        assertTrue(
                GroovyCompilationValidator.validate(fixed.proposalCode(), () -> false, 10000)
                        .success());
        String sent =
                wireHistory.get(2).stream()
                        .map(Message::getContent)
                        .reduce("", (left, right) -> left + "\n" + right);
        assertFalse(sent.contains("Driver"));
        assertFalse(sent.contains("Battery"));
        assertFalse(sent.contains("Ω"));
        assertTrue(sent.contains("Prior proposal source omitted for privacy"));
        assertTrue(sent.contains(TaskPreparation.PREFIX));
        assertEquals(Set.of("inspect"), fixed.pendingStepIds());
        assertTrue(fixed.completedStepIds().isEmpty());
    }

    private static ArcadiaAgent agent(
            Provider provider, AtomicInteger reads, ArcadiaAgent.Limits limits) {
        return new ArcadiaAgent(
                provider,
                (name, args) -> {
                    reads.incrementAndGet();
                    return "{\"ok\":true}";
                },
                Set.of("model_summary"),
                limits);
    }

    private static ArcadiaAgent.Turn run(ArcadiaAgent agent) {
        return agent.run("Prompt", List.of(), "Original request", () -> false, null);
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
        assertTrue(turn.completedStepIds().isEmpty());
        assertEquals(Set.of("inspect"), turn.pendingStepIds());
        assertFalse(
                turn.messages().stream()
                        .anyMatch(
                                message ->
                                        message.getContent()
                                                .startsWith(
                                                        "AUTHORITATIVE CAPELLA EXECUTION"
                                                            + " OUTCOME")));
    }

    private static String plan(String type) {
        return AgentJson.write(
                Map.of(
                        "kind",
                        "plan",
                        "steps",
                        List.of(
                                Map.of(
                                        "id",
                                        "inspect",
                                        "title",
                                        "Inspect scope",
                                        "type",
                                        type,
                                        "status",
                                        "pending"))));
    }

    private static String complete() {
        return terminal("complete");
    }

    private static String terminal(String state) {
        return AgentJson.write(
                Map.of(
                        "kind",
                        "final",
                        "message",
                        "The response ended",
                        "task",
                        Map.of("state", state, "reason", "Observed response")));
    }

    private static String read() {
        return AgentJson.write(
                Map.of(
                        "kind",
                        "tool",
                        "toolName",
                        "model_summary",
                        "arguments",
                        Map.of(),
                        "stepId",
                        "inspect"));
    }

    private static String proposal(String code) {
        return AgentJson.write(
                Map.of(
                        "kind",
                        "proposal",
                        "message",
                        "Review the repaired change",
                        "script",
                        code,
                        "stepIds",
                        List.of("inspect")));
    }

    private static final class Provider implements ArcadiaAgent.Provider {
        final Deque<String> replies = new ArrayDeque<>();
        final List<List<Message>> inputs = new ArrayList<>();

        Provider(String... replies) {
            this.replies.addAll(List.of(replies));
        }

        @Override
        public String send(List<Message> messages) {
            inputs.add(messages);
            assertFalse("Unexpected provider call", replies.isEmpty());
            return replies.removeFirst();
        }
    }
}
