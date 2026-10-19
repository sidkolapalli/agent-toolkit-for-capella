package org.polarsys.capella.ai.codex;

import static org.junit.Assert.*;

import java.io.IOException;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

import org.junit.Test;
import org.polarsys.capella.ai.agent.AgentJson;
import org.polarsys.capella.ai.agent.AgentResponseDecoder;
import org.polarsys.capella.ai.agent.ArcadiaAgent;
import org.polarsys.capella.ai.agent.ArcadiaModelTools;
import org.polarsys.capella.ai.llm.LLMService.Message;

/** Scripted native RPC only: no Codex process, provider, project or model is opened. */
public class CodexArcadiaAgentTest {
    @Test public void nativeReadThenExactReviewPausesUntilAuthoritativeOutcome() {
        Fake transport = new Fake(); AtomicInteger reads = new AtomicInteger(); List<ArcadiaAgent.Progress> progress = new ArrayList<>();
        String script = "api.create('LA', 'LogicalFunction', 'AgentProbe_LA')\nreturn [label: 'literal ``` embedded']";
        transport.add(plan("plan", readStep("inspect"), editStep("create")),
            call("read", "model_summary", map("stepId", "inspect")), proposal("write", script, "create"),
            done("Paused before any execution outcome."), inTurn(done("Created and independently checked."), "turn-2"));
        try (CodexArcadiaAgent agent = agent(transport, (name, args) -> { reads.incrementAndGet(); assertTrue(args.isEmpty()); return "{\"ok\":true}"; })) {
            ArcadiaAgent.Turn review = agent.run("Native API documentation", List.of(), "Create a function", () -> false, progress::add);
            assertEquals(script, review.proposalCode()); assertEquals(Set.of("create"), review.proposalStepIds());
            assertEquals("completed", review.plan().get(0).status()); assertEquals("pending", review.plan().get(1).status());
            assertEquals(1, reads.get()); assertFalse(transport.responses.containsKey("write"));
            assertTrue(progress.stream().anyMatch(item -> "tool_result".equals(item.type()) && "Read completed: model_summary".equals(item.message())));
            ArcadiaAgent.ExecutionOutcome outcome = committed("create");
            agent.recordOutcome(review, outcome); agent.recordOutcome(review, outcome);
            assertEquals(1, transport.responseCounts.get("write").intValue());
            assertTrue(toolText(transport.responses.get("write")).contains("AUTHORITATIVE CAPELLA EXECUTION OUTCOME"));
            ArcadiaAgent.Turn result = agent.finish(review, outcome, () -> false, progress::add);
            assertFalse(result.hasProposal()); assertEquals("Created and independently checked.", result.reply());
            assertEquals("completed", result.plan().get(1).status()); assertEquals(2, transport.count("turn/start"));
            assertFalse(result.messages().stream().anyMatch(message -> message.getContent().equals("Paused before any execution outcome.")));
            assertEquals(0, transport.count("turn/interrupt"));
        }
    }

    @Test public void failedWriteCorrectionIsAFreshUnansweredReviewInOutcomeTurn() {
        Fake transport = new Fake();
        transport.add(plan("plan", editStep("edit")), proposal("first", "api.rename('a', 'first')", "edit"), done("Paused before the first outcome."),
            inTurn(proposal("second", "api.rename('a', 'corrected')", "edit"), "turn-2"), inTurn(done("Paused before the correction outcome."), "turn-2"),
            inTurn(done("The correction was saved."), "turn-3"));
        try (CodexArcadiaAgent agent = agent(transport, successfulReads())) {
            ArcadiaAgent.Turn first = run(agent);
            ArcadiaAgent.Turn correction = agent.finish(first, new ArcadiaAgent.ExecutionOutcome(false, false, false, "Transaction rolled back", Set.of()), () -> false, null);
            assertEquals(Boolean.FALSE, transport.responses.get("first").get("success"));
            assertEquals("api.rename('a', 'corrected')", correction.proposalCode());
            assertFalse(transport.responses.containsKey("second")); assertEquals("pending", correction.plan().get(0).status());
            ArcadiaAgent.Turn result = agent.finish(correction, committed("edit"), () -> false, null);
            assertEquals("completed", result.plan().get(0).status()); assertEquals(3, transport.count("turn/start"));
            assertTrue(inputText(transport.params("turn/start", 1)).contains("\"committed\":false"));
        }
    }

    @Test public void preReceiptFinalBeforeOrDuringReviewCannotBecomePostExecutionAnswer() {
        for (boolean beforeProposal : List.of(true, false)) {
            Fake transport = new Fake(); List<ArcadiaAgent.Progress> progress = new ArrayList<>();
            Map<String, Object> stale = notification("item/completed", map("item", map("type", "agentMessage", "id", "answer", "phase", "final_answer", "text", "No execution outcome received.")));
            transport.add(plan("plan", editStep("edit")));
            if (beforeProposal) transport.add(stale);
            transport.add(proposal("write", "api.rename('id','CreatedName')", "edit"));
            if (!beforeProposal) transport.add(stale);
            transport.add(done("Paused for review."),
                // A late old-turn item after the completion boundary is ignored.
                notification("item/completed", map("item", map("type", "agentMessage", "id", "answer", "phase", "final_answer", "text", "Still no execution outcome."))),
                inTurn(done("Committed, saved and independently checked."), "turn-2"));
            try (CodexArcadiaAgent agent = agent(transport, successfulReads())) {
                ArcadiaAgent.Turn review = agent.run("Captured documentation", List.of(), "Rename the inspected fixture", () -> false, progress::add);
                ArcadiaAgent.Turn result = agent.finish(review, committed("edit"), () -> false, progress::add);
                assertEquals("Committed, saved and independently checked.", result.reply());
                assertEquals(1, progress.stream().filter(item -> "final".equals(item.type())).count());
                assertFalse(result.messages().stream().anyMatch(message -> message.getContent().contains("No execution outcome received.")));
                String continuation = inputText(transport.params("turn/start", 1));
                assertTrue(continuation.startsWith("AUTHORITATIVE CAPELLA EXECUTION OUTCOME\n"));
                assertTrue(continuation.contains("\"committed\":true")); assertTrue(continuation.contains("\"saved\":true"));
                assertTrue(continuation.contains("\"completedStepIds\":[\"edit\"]"));
                assertTrue(continuation.contains("Rename the inspected fixture")); assertTrue(continuation.contains("AUTHORITATIVE CURRENT PLAN"));
                assertFalse(continuation.contains("api.rename"));
                assertEquals(List.of(), transport.params("turn/start", 1).get("environments"));
                assertEquals("thread-1", transport.params("turn/start", 1).get("threadId"));
            }
        }
    }

    @Test public void stillRunningReviewedTurnIsInterruptedBeforeContinuationAndLateCallsDoNotExecute() {
        Fake transport = new Fake(); AtomicInteger reads = new AtomicInteger();
        transport.add(plan("plan", editStep("edit")), proposal("write", "api.rename('id','CreatedName')", "edit"));
        transport.onInterrupt = () -> transport.add(call("late-read", "model_summary", Map.of()), done("Obsolete answer."), inTurn(done("Receipt acknowledged."), "turn-2"));
        try (CodexArcadiaAgent agent = agent(transport, (name, args) -> { reads.incrementAndGet(); return "{\"ok\":true}"; })) {
            ArcadiaAgent.Turn review = run(agent), result = agent.finish(review, committed("edit"), () -> false, null);
            assertEquals("Receipt acknowledged.", result.reply()); assertEquals(0, reads.get());
            assertEquals(1, transport.count("turn/interrupt")); assertEquals(2, transport.count("turn/start"));
            assertEquals(Integer.valueOf(-32600), transport.rejected.get("late-read"));
        }
    }

    @Test public void outcomeContinuationRetainsNativeToolBudgetAndCommitEvidence() {
        Fake transport = new Fake(); transport.add(plan("plan", editStep("edit")), proposal("write", "api.rename('id','CreatedName')", "edit"),
            done("Paused."), inTurn(call("excess-read", "model_summary", Map.of()), "turn-2"));
        try (CodexArcadiaAgent agent = agent(transport, successfulReads(), new ArrayList<>(), null, UnaryOperator.identity(), UnaryOperator.identity(),
                new CodexArcadiaAgent.Limits(32, 2, 3, Duration.ofSeconds(5)))) {
            ArcadiaAgent.Turn review = run(agent);
            ArcadiaAgent.AgentException failure = assertThrows(ArcadiaAgent.AgentException.class, () -> agent.finish(review, committed("edit"), () -> false, null));
            assertTrue(failure.getMessage().contains("budget exhausted"));
            assertEquals("completed", failure.turn().plan().get(0).status());
            assertTrue(failure.turn().messages().stream().anyMatch(message -> message.getContent().startsWith("AUTHORITATIVE CAPELLA EXECUTION OUTCOME")));
            assertFalse(transport.responses.containsKey("excess-read")); assertEquals(2, transport.count("turn/start"));
        }
    }

    @Test public void blankStoredThreadStartsFreshAndPersistsOnlyReturnedIdentity() {
        for (String initial : List.of("", "  ")) {
            Fake transport = new Fake(); transport.add(done("Ready.")); List<String> saved = new ArrayList<>();
            try (CodexArcadiaAgent agent = agent(transport, successfulReads(), saved, initial, UnaryOperator.identity(), UnaryOperator.identity(), defaults())) {
                run(agent); assertEquals(1, transport.count("thread/start")); assertEquals(0, transport.count("thread/resume"));
                assertEquals(List.of("thread-1"), saved); assertTrue(transport.params("thread/start").containsKey("dynamicTools"));
                assertEquals("read-only", transport.params("thread/start").get("sandbox"));
                assertEquals(List.of(), transport.params("thread/start").get("environments"));
                assertEquals(List.of(), transport.params("turn/start").get("environments"));
                assertEquals(Boolean.FALSE, object(transport.params("turn/start").get("sandboxPolicy")).get("networkAccess"));
            }
        }
    }

    @Test public void resumeUpdatesInstructionsAndReplaysLocalAuthoritativeHistoryWithoutExecutingIt() {
        Fake transport = new Fake(); transport.thread = "persisted-thread";
        Map<String, Object> completed = done("Saved history read."); object(completed.get("params")).put("threadId", "persisted-thread");
        transport.add(completed); List<String> saved = new ArrayList<>();
        try (CodexArcadiaAgent agent = agent(transport, successfulReads(), saved, "persisted-thread", UnaryOperator.identity(), UnaryOperator.identity(), defaults())) {
            agent.run("Current captured model instructions", List.of(Message.system("Old prompt"), Message.assistant("Historical proposal"),
                Message.user("AUTHORITATIVE CAPELLA EXECUTION OUTCOME\n{\"committed\":true,\"saved\":false}")), "Continue", () -> false, null);
            assertEquals(1, transport.count("thread/resume")); assertEquals(0, transport.count("thread/start")); assertTrue(saved.isEmpty());
            assertFalse(transport.params("thread/resume").containsKey("dynamicTools"));
            assertFalse(transport.params("thread/resume").containsKey("environments"));
            assertEquals(List.of(), transport.params("turn/start").get("environments"));
            assertTrue(String.valueOf(transport.params("thread/resume").get("developerInstructions")).contains("Current captured model"));
            assertTrue(inputText(transport).contains("AUTHORITATIVE CAPELLA EXECUTION OUTCOME")); assertFalse(inputText(transport).contains("Old prompt"));
        }
    }

    @Test public void resumeFailureNeverSilentlyStartsAnotherThread() {
        Fake transport = new Fake(); transport.failMethod = "thread/resume"; transport.failure = new IOException("Codex rejected the request (RPC code -32602).");
        try (CodexArcadiaAgent agent = agent(transport, successfulReads(), new ArrayList<>(), "old-thread", UnaryOperator.identity(), UnaryOperator.identity(), defaults())) {
            ArcadiaAgent.AgentException error = assertThrows(ArcadiaAgent.AgentException.class, () -> run(agent));
            assertTrue(error.getMessage().contains("-32602")); assertEquals(0, transport.count("thread/start")); assertTrue(transport.closed);
        }
    }

    @Test public void firstTurnFailureDoesNotPersistAnUnresumableEmptyThread() {
        Fake transport = new Fake(); transport.failMethod = "turn/start"; transport.failure = new IOException("Codex rejected the request (RPC code -32600).");
        List<String> saved = new ArrayList<>();
        try (CodexArcadiaAgent agent = agent(transport, successfulReads(), saved, "", UnaryOperator.identity(), UnaryOperator.identity(), defaults())) {
            assertThrows(ArcadiaAgent.AgentException.class, () -> run(agent)); assertTrue(saved.isEmpty());
            assertEquals(1, transport.count("thread/start")); assertEquals(1, transport.count("turn/start"));
        }
    }

    @Test public void boundedHistoryKeepsStructuredExecutionReceiptsWhole() {
        Fake transport = new Fake(); transport.add(done("History read."));
        String receipt = "AUTHORITATIVE CAPELLA EXECUTION OUTCOME\n" + AgentJson.write(map("applied", true, "completedStepIds", List.of("applied"), "feedback", "a".repeat(15_000)));
        UnaryOperator<String> encode = text -> AgentResponseDecoder.encodeMessage(text, value -> value.replace("applied", "ELEM_0001"));
        try (CodexArcadiaAgent agent = agent(transport, successfulReads(), new ArrayList<>(), null, UnaryOperator.identity(), encode, defaults())) {
            agent.run("Instructions", List.of(Message.user(receipt)), "Continue", () -> false, null);
            String input = inputText(transport); assertTrue(input.contains("\"completedStepIds\":[\"applied\"]"));
            assertTrue(input.contains("a".repeat(15_000))); assertFalse(input.contains("[truncated]"));
        }
    }

    @Test public void resumeIdentityMismatchFailsBeforeModelInference() {
        Fake transport = new Fake(); transport.thread = "wrong-thread";
        try (CodexArcadiaAgent agent = agent(transport, successfulReads(), new ArrayList<>(), "expected-thread", UnaryOperator.identity(), UnaryOperator.identity(), defaults())) {
            assertTrue(assertThrows(ArcadiaAgent.AgentException.class, () -> run(agent)).getMessage().contains("different thread"));
            assertEquals(0, transport.count("turn/start"));
        }
    }

    @Test public void missingSignInFailsClearlyWithoutStartingAuthenticationOrInference() {
        Fake transport = new Fake(); transport.account = map("account", null, "requiresOpenaiAuth", true);
        try (CodexArcadiaAgent agent = agent(transport, successfulReads())) {
            String message = assertThrows(ArcadiaAgent.AgentException.class, () -> run(agent)).getMessage();
            assertTrue(message.contains("Sign in with ChatGPT"));
            assertTrue(message.contains("button in Capella"));
            assertEquals(1, transport.count("account/read")); assertEquals(Boolean.FALSE, transport.params("account/read").get("refreshToken"));
            assertEquals(0, transport.count("thread/start")); assertEquals(0, transport.count("turn/start"));
            assertTrue(transport.requests.stream().noneMatch(item -> item.method.startsWith("account/login")));
        }
    }

    @Test public void foreignAndDuplicateCallsNeverReadTheCapturedModelTwice() {
        Fake transport = new Fake(); AtomicInteger reads = new AtomicInteger();
        Map<String, Object> foreign = call("foreign", "model_summary", Map.of()); object(foreign.get("params")).put("threadId", "other-thread");
        Map<String, Object> late = call("late", "model_summary", Map.of()); object(late.get("params")).put("turnId", "old-turn");
        Map<String, Object> duplicate = call("duplicate-rpc", "model_summary", Map.of()); object(duplicate.get("params")).put("callId", "call-read");
        transport.add(foreign, late, call("read", "model_summary", Map.of()), duplicate, done("One valid read."));
        try (CodexArcadiaAgent agent = agent(transport, (name, args) -> { reads.incrementAndGet(); return "{\"ok\":true}"; })) {
            run(agent); assertEquals(1, reads.get()); assertEquals(Set.of("foreign", "late", "duplicate-rpc"), transport.rejected.keySet());
            assertEquals(-32600, transport.rejected.get("duplicate-rpc").intValue());
        }
    }

    @Test public void genericApprovalsAndUnknownToolsFailClosed() {
        for (Map<String, Object> request : List.of(map("id", "approval", "method", "item/commandExecution/requestApproval", "params", Map.of()),
                call("unknown", "shell", map("command", "do not run")))) {
            Fake transport = new Fake(); transport.add(request); AtomicInteger reads = new AtomicInteger();
            try (CodexArcadiaAgent agent = agent(transport, (name, args) -> { reads.incrementAndGet(); return "{\"ok\":true}"; })) {
                assertThrows(ArcadiaAgent.AgentException.class, () -> run(agent)); assertEquals(0, reads.get());
                assertEquals(1, transport.rejected.size()); assertTrue(transport.closed); assertEquals(1, transport.count("turn/interrupt"));
            }
        }
    }

    @Test public void nativeBuiltinExecutionItemsAreDeniedEvenWithoutApprovalRequests() {
        for (String type : List.of("commandExecution", "fileChange", "imageView", "mcpToolCall", "webSearch", "collabToolCall")) {
            Fake transport = new Fake(); transport.add(notification("item/started", map("item", map("type", type, "id", "builtin"))));
            try (CodexArcadiaAgent agent = agent(transport, successfulReads())) {
                assertThrows(ArcadiaAgent.AgentException.class, () -> run(agent)); assertTrue(transport.closed);
            }
        }
    }

    @Test public void plansCannotSelfCertifyEditsAndFailedReadsCannotCompleteReadSteps() {
        Fake transport = new Fake(); List<ArcadiaAgent.Progress> progress = new ArrayList<>();
        transport.add(plan("fake-completion", map("id", "edit", "title", "Create", "status", "completed", "type", "edit")),
            plan("real-plan", readStep("read")), call("read", "model_summary", map("stepId", "read")), done("Read unavailable."));
        try (CodexArcadiaAgent agent = agent(transport, (name, args) -> "{\"ok\":false,\"message\":\"Unavailable\"}")) {
            ArcadiaAgent.Turn result = agent.run("Instructions", List.of(), "Inspect", () -> false, progress::add);
            assertEquals(Boolean.FALSE, transport.responses.get("fake-completion").get("success"));
            assertEquals("pending", result.plan().get(0).status()); assertEquals(Boolean.FALSE, transport.responses.get("read").get("success"));
            assertTrue(toolText(transport.responses.get("read")).contains("Unavailable"));
            assertTrue(progress.stream().anyMatch(item -> "tool_result".equals(item.type()) && item.message().startsWith("Read failed:")));
        }
    }

    @Test public void moreThanEightBoundedReadCallsAreSupported() {
        Fake transport = new Fake(); AtomicInteger reads = new AtomicInteger();
        for (int index = 0; index < 16; index++) transport.add(call("page-" + index, "model_search", map("type", "Requirement", "cursor", "page-" + index)));
        transport.add(done("The requirement was found."));
        try (CodexArcadiaAgent agent = agent(transport, (name, args) -> { reads.incrementAndGet(); return "{\"ok\":true}"; })) {
            ArcadiaAgent.Turn result = run(agent); assertEquals(16, reads.get()); assertEquals(16, result.toolCalls()); assertEquals(20, result.requests());
        }
    }

    @Test public void nativeArgumentDiagnosticsPermitCorrectionInTheSameTurnWithoutRelaxingBounds() {
        Fake transport = new Fake(); AtomicInteger validReads = new AtomicInteger();
        transport.add(plan("plan", readStep("inspect")),
            call("too-many", "model_inspect", map("id", "native-id", "limit", 80, "stepId", "inspect")),
            call("wrong-name", "model_search", map("name", "CodexProbe")),
            call("blank-query", "model_search", map("query", "", "type", "SystemFunction")),
            call("corrected-inspect", "model_inspect", map("id", "native-id", "stepId", "inspect")),
            call("corrected-search", "model_search", map("query", "CodexProbe", "type", "SystemFunction")), done("Read requests corrected."));
        ArcadiaModelTools nativeValidator = new ArcadiaModelTools(null);
        try (CodexArcadiaAgent agent = agent(transport, (name, args) -> {
            if (args.containsKey("name") || "".equals(args.get("query")) || args.get("limit") instanceof Number number && number.intValue() == 80) return nativeValidator.execute(name, args);
            validReads.incrementAndGet(); return "{\"ok\":true}";
        })) {
            ArcadiaAgent.Turn result = run(agent);
            assertEquals(2, validReads.get()); assertEquals("completed", result.plan().get(0).status());
            assertEquals(1, transport.count("turn/start"));
            for (String call : List.of("too-many", "wrong-name", "blank-query")) {
                assertEquals(Boolean.FALSE, transport.responses.get(call).get("success"));
                assertEquals("invalid_arguments", object(readResult(transport, call).get("error")).get("code"));
            }
            assertTrue(toolText(transport.responses.get("too-many")).contains("integer from 1 to 30"));
            assertTrue(toolText(transport.responses.get("wrong-name")).contains("Unexpected tool argument: name"));
            assertTrue(toolText(transport.responses.get("blank-query")).contains("omit unused optional fields"));
        }
    }

    @Test public void arbitraryServiceAndArgumentExceptionsRemainOpaque() {
        for (Exception error : List.of(new IOException("sk-secret-test E:/private/model"), new IllegalArgumentException("sk-secret-test E:/private/model"))) {
            Fake transport = new Fake(); transport.add(call("read", "model_summary", Map.of()), done("Unavailable."));
            try (CodexArcadiaAgent agent = agent(transport, (name, args) -> { throw error; })) {
                run(agent); String feedback = toolText(transport.responses.get("read"));
                assertFalse(feedback.contains("sk-secret")); assertFalse(feedback.contains("E:/private"));
                assertEquals("read_unavailable", object(readResult(transport, "read").get("error")).get("code"));
            }
        }
    }

    @Test public void typedArgumentFeedbackPreservesProtocolIdentityAndPrivacyEncodesMessageData() {
        Fake transport = new Fake(); transport.add(call("invalid", "model_search", map("name", "Probe")), done("Correct the request."));
        UnaryOperator<String> encode = text -> AgentResponseDecoder.encodeMessage(text,
            value -> value.replace("invalid_arguments", "ELEM_0001").replace("model_search", "ELEM_0002").replace("name", "ELEM_0003"));
        ArcadiaModelTools nativeValidator = new ArcadiaModelTools(null);
        try (CodexArcadiaAgent agent = agent(transport, nativeValidator::execute, new ArrayList<>(), null, UnaryOperator.identity(), encode, defaults())) {
            run(agent); Map<String, Object> result = readResult(transport, "invalid"), error = object(result.get("error"));
            assertEquals(Boolean.FALSE, result.get("ok")); assertEquals("model_search", result.get("tool"));
            assertEquals("invalid_arguments", error.get("code")); assertTrue(String.valueOf(error.get("message")).contains("ELEM_0003"));
            assertFalse(result.containsKey("ELEM_0001")); assertFalse(result.containsKey("ELEM_0002"));
        }
    }

    @Test public void nestedOrMissingPostconditionScopeGetsSpecificFeedbackThenFlatProposalPauses() {
        Fake transport = new Fake();
        Map<String, Object> nested = proposal("nested", "return 'candidate'", "edit");
        object(object(nested.get("params")).get("arguments")).put("postconditions", List.of(map("kind", "element_exists", "target", map("type", "LogicalFunction", "name", "Probe", "layer", "LA"))));
        Map<String, Object> missing = proposal("missing", "return 'candidate'", "edit");
        object(object(missing.get("params")).get("arguments")).put("postconditions", List.of(map("kind", "element_exists", "type", "LogicalFunction", "name", "Probe", "layer", "LA")));
        Map<String, Object> correct = proposal("correct", "api.create('LA','LogicalFunction','Probe')", "edit");
        object(object(correct.get("params")).get("arguments")).put("postconditions", List.of(map("stepId", "edit", "kind", "element_exists", "type", "LogicalFunction", "name", "Probe", "layer", "LA")));
        transport.add(plan("plan", editStep("edit")), nested, missing, correct);
        try (CodexArcadiaAgent agent = agent(transport, successfulReads())) {
            ArcadiaAgent.Turn review = run(agent); assertTrue(review.hasProposal()); assertEquals(1, review.postconditions().size());
            assertFalse(transport.responses.containsKey("correct")); assertEquals("pending", review.plan().get(0).status());
            assertTrue(toolText(transport.responses.get("nested")).contains("flat objects, without target or arguments"));
            assertTrue(toolText(transport.responses.get("missing")).contains("own stepId"));
            assertEquals(Boolean.FALSE, transport.responses.get("nested").get("success"));
            assertEquals(Boolean.FALSE, transport.responses.get("missing").get("success"));
            assertEquals(1, transport.count("turn/start"));
        }
    }

    @Test public void sharedNativeToolAndRequestBudgetsStopFurtherCalls() {
        for (CodexArcadiaAgent.Limits limits : List.of(new CodexArcadiaAgent.Limits(32, 2, 3, Duration.ofSeconds(5)),
                new CodexArcadiaAgent.Limits(6, 24, 3, Duration.ofSeconds(5)))) {
            Fake transport = new Fake(); AtomicInteger reads = new AtomicInteger();
            for (int index = 0; index < 3; index++) transport.add(call("read-" + index, "model_summary", Map.of()));
            try (CodexArcadiaAgent agent = agent(transport, (name, args) -> { reads.incrementAndGet(); return "{\"ok\":true}"; }, new ArrayList<>(), null,
                    UnaryOperator.identity(), UnaryOperator.identity(), limits)) {
                assertTrue(assertThrows(ArcadiaAgent.AgentException.class, () -> run(agent)).getMessage().contains("budget exhausted"));
                assertEquals(2, reads.get()); assertTrue(transport.closed);
            }
        }
    }

    @Test public void proposalBudgetIsSharedAcrossFailedCorrectionContinuations() {
        Fake transport = new Fake(); transport.add(plan("plan", editStep("edit")), proposal("a", "first()", "edit"), done("Paused."),
            inTurn(proposal("b", "second()", "edit"), "turn-2"), inTurn(done("Paused."), "turn-2"),
            inTurn(proposal("c", "third()", "edit"), "turn-3"), inTurn(done("Paused."), "turn-3"), inTurn(proposal("d", "fourth()", "edit"), "turn-4"));
        try (CodexArcadiaAgent agent = agent(transport, successfulReads())) {
            ArcadiaAgent.ExecutionOutcome failed = new ArcadiaAgent.ExecutionOutcome(false, false, false, "Rolled back", Set.of());
            ArcadiaAgent.Turn a = run(agent), b = agent.finish(a, failed, () -> false, null), c = agent.finish(b, failed, () -> false, null);
            assertTrue(assertThrows(ArcadiaAgent.AgentException.class, () -> agent.finish(c, failed, () -> false, null)).getMessage().contains("proposal budget"));
            assertFalse(transport.responses.containsKey("d")); assertEquals(4, transport.count("turn/start"));
        }
    }

    @Test public void cancellationInterruptsNativeTurnWithoutReadingOrExecutingAProposal() {
        Fake transport = new Fake(); AtomicBoolean cancelled = new AtomicBoolean(); AtomicInteger reads = new AtomicInteger();
        transport.beforeEvent = () -> cancelled.set(true); transport.add(call("read", "model_summary", Map.of()));
        try (CodexArcadiaAgent agent = agent(transport, (name, args) -> { reads.incrementAndGet(); return "{\"ok\":true}"; })) {
            assertThrows(ArcadiaAgent.AgentException.class, () -> agent.run("Prompt", List.of(), "Stop", cancelled::get, null));
            assertEquals(0, reads.get()); assertEquals(1, transport.count("turn/interrupt")); assertTrue(transport.closed);
        }
    }

    @Test public void committedUnsavedOutcomeSurvivesCancellationAndIsAnsweredOnlyOnce() {
        Fake transport = new Fake(); transport.add(plan("plan", editStep("edit")), proposal("write", "api.rename('a', 'b')", "edit"));
        try (CodexArcadiaAgent agent = agent(transport, successfulReads())) {
            ArcadiaAgent.Turn review = run(agent); ArcadiaAgent.ExecutionOutcome outcome = new ArcadiaAgent.ExecutionOutcome(true, true, false, "SAVE_FAILED_AFTER_COMMIT", Set.of("edit"));
            agent.recordOutcome(review, outcome);
            assertEquals(Boolean.TRUE, transport.responses.get("write").get("success"));
            ArcadiaAgent.AgentException error = assertThrows(ArcadiaAgent.AgentException.class, () -> agent.finish(review, outcome, () -> true, null));
            assertEquals("completed", error.turn().plan().get(0).status()); assertFalse(error.turn().hasProposal());
            assertTrue(error.turn().messages().stream().anyMatch(message -> message.getContent().contains("SAVE_FAILED_AFTER_COMMIT")));
            assertEquals(1, transport.responseCounts.get("write").intValue());
            assertEquals(1, transport.count("turn/start"));
        }
    }

    @Test public void recordingUnsavedCommitWithoutFinishNeverStartsOutcomeInference() {
        Fake transport = new Fake(); transport.add(plan("plan", editStep("edit")), proposal("write", "api.rename('a','b')", "edit"), done("Pre-execution paused answer."));
        try (CodexArcadiaAgent agent = agent(transport, successfulReads())) {
            ArcadiaAgent.Turn review = run(agent);
            agent.recordOutcome(review, new ArcadiaAgent.ExecutionOutcome(true, true, false, "SAVE_FAILED_AFTER_COMMIT", Set.of("edit")));
            assertEquals(1, transport.count("turn/start")); assertEquals(1, transport.responseCounts.get("write").intValue());
            assertTrue(toolText(transport.responses.get("write")).contains("\"saved\":false"));
        }
        assertEquals(1, transport.count("turn/start"));
    }

    @Test public void outcomeSnapshotPreservesDismissalFailureAndCommitLocallyWithoutInference() {
        List<ArcadiaAgent.ExecutionOutcome> outcomes = List.of(
            new ArcadiaAgent.ExecutionOutcome(false, false, false, "Review dismissed; no code ran", Set.of()),
            new ArcadiaAgent.ExecutionOutcome(false, false, false, "Transaction rolled back", Set.of()),
            new ArcadiaAgent.ExecutionOutcome(true, true, false, "SAVE_FAILED_AFTER_COMMIT", Set.of("edit")),
            committed("edit"));
        for (ArcadiaAgent.ExecutionOutcome outcome : outcomes) {
            Fake transport = new Fake();
            transport.add(plan("plan", editStep("edit")), proposal("write", "api.rename('a', 'b')", "edit"), done("Old paused answer."));
            try (CodexArcadiaAgent agent = agent(transport, successfulReads())) {
                assertNull(agent.outcomeSnapshot());
                ArcadiaAgent.Turn review = run(agent); assertNull(agent.outcomeSnapshot());
                agent.recordOutcome(review, outcome);
                int requests = transport.requests.size(), queuedEvents = transport.events.size();
                ArcadiaAgent.Turn snapshot = agent.outcomeSnapshot();
                assertNotNull(snapshot); assertFalse(snapshot.hasProposal());
                assertEquals(outcome.completedStepIds().contains("edit") ? "completed" : "pending", snapshot.plan().get(0).status());
                Message receipt = snapshot.messages().get(snapshot.messages().size() - 1);
                String prefix = "AUTHORITATIVE CAPELLA EXECUTION OUTCOME\n";
                assertTrue(receipt.getContent().startsWith(prefix));
                Map<String, Object> facts = object(AgentJson.parse(receipt.getContent().substring(prefix.length()), 40_000));
                assertEquals(outcome.applied(), facts.get("applied")); assertEquals(outcome.committed(), facts.get("committed"));
                assertEquals(outcome.saved(), facts.get("saved")); assertEquals(outcome.feedback(), facts.get("feedback"));
                assertEquals(List.copyOf(outcome.completedStepIds()), facts.get("completedStepIds"));
                assertEquals(requests, transport.requests.size()); assertEquals(queuedEvents, transport.events.size());
                assertEquals(1, transport.count("turn/start"));
                assertThrows(UnsupportedOperationException.class, () -> snapshot.messages().clear());
                agent.close(); requests = transport.requests.size();
                assertEquals(snapshot, agent.outcomeSnapshot()); assertEquals(requests, transport.requests.size());
                assertEquals(queuedEvents, transport.events.size());
            }
        }
    }

    @Test public void directRuntimeOutcomeSnapshotDefaultsToNullWithoutCallingProvider() {
        AtomicInteger requests = new AtomicInteger();
        ArcadiaAgent direct = new ArcadiaAgent(messages -> { requests.incrementAndGet(); return "unused"; },
            successfulReads(), ArcadiaModelTools.toolNames(), ArcadiaAgent.Limits.defaults());
        assertNull(direct.outcomeSnapshot()); assertEquals(0, requests.get());
    }

    @Test public void transportFailureWhileReportingCommitRetainsLocalReceiptWithoutReplayingWrite() {
        Fake transport = new Fake(); transport.add(plan("plan", editStep("edit")), proposal("write", "api.rename('a', 'b')", "edit"));
        try (CodexArcadiaAgent agent = agent(transport, successfulReads())) {
            ArcadiaAgent.Turn review = run(agent); transport.failRespond = "write";
            ArcadiaAgent.ExecutionOutcome outcome = committed("edit");
            ArcadiaAgent.AgentException error = assertThrows(ArcadiaAgent.AgentException.class, () -> agent.recordOutcome(review, outcome));
            assertFalse(error.turn().hasProposal()); assertEquals("completed", error.turn().plan().get(0).status());
            assertTrue(error.turn().messages().stream().anyMatch(message -> message.getContent().startsWith("AUTHORITATIVE CAPELLA EXECUTION OUTCOME")));
            assertEquals(error.turn(), agent.outcomeSnapshot());
            agent.recordOutcome(review, outcome); assertEquals(1, transport.responseCounts.get("write").intValue());
            assertThrows(IllegalArgumentException.class, () -> agent.recordOutcome(review, new ArcadiaAgent.ExecutionOutcome(false, false, false, "different", Set.of())));
        }
    }

    @Test public void completedCommentaryIsEmittedOnceAndNeverBecomesFinalAnswer() {
        Fake transport = new Fake(); List<ArcadiaAgent.Progress> progress = new ArrayList<>();
        Map<String, Object> commentary = map("type", "agentMessage", "id", "comment", "phase", "commentary", "text", "Checking the captured model.");
        transport.add(notification("item/started", map("item", map("type", "agentMessage", "id", "comment", "phase", "commentary", "text", ""))),
            notification("item/completed", map("item", commentary)), notification("item/completed", map("item", commentary)),
            notification("item/started", map("item", map("type", "agentMessage", "id", "answer", "phase", "final_answer", "text", ""))),
            notification("item/agentMessage/delta", map("itemId", "answer", "delta", "Final answer.")),
            notification("turn/completed", map("turn", map("id", "turn-1", "status", "completed", "items", List.of(commentary)))));
        try (CodexArcadiaAgent agent = agent(transport, successfulReads())) {
            ArcadiaAgent.Turn result = agent.run("Prompt", List.of(), "Read", () -> false, progress::add);
            assertEquals("Final answer.", result.reply()); assertEquals(1, progress.stream().filter(item -> "commentary".equals(item.type())).count());
            assertEquals("Checking the captured model.", progress.stream().filter(item -> "commentary".equals(item.type())).findFirst().orElseThrow().message());
        }
    }

    @Test public void commentaryOnlyCompletionFailsInsteadOfClaimingSuccess() {
        Fake transport = new Fake(); transport.add(notification("turn/completed", map("turn", map("id", "turn-1", "status", "completed", "items",
            List.of(map("type", "agentMessage", "id", "comment", "phase", "commentary", "text", "Still checking."))))));
        try (CodexArcadiaAgent agent = agent(transport, successfulReads())) {
            assertTrue(assertThrows(ArcadiaAgent.AgentException.class, () -> run(agent)).getMessage().contains("without a final answer"));
        }
    }

    @Test public void ordinaryAssistantCodeAndJsonAreNeverParsedAsNativeToolCalls() {
        Fake transport = new Fake(); String prose = "```groovy\napi.delete('do-not-run')\n```\n{\"kind\":\"proposal\"}"; transport.add(done(prose));
        try (CodexArcadiaAgent agent = agent(transport, successfulReads())) { ArcadiaAgent.Turn result = run(agent); assertFalse(result.hasProposal()); assertEquals(prose, result.reply()); }
    }

    @Test public void malformedProposalAndUndeclaredPostconditionsAreAnsweredWithoutReview() {
        Fake transport = new Fake();
        Map<String, Object> badScope = proposal("bad-condition", "api.rename('a','b')", "edit");
        object(object(badScope.get("params")).get("arguments")).put("postconditions", List.of(map("stepId", "other", "kind", "element_exists", "id", "a")));
        transport.add(plan("plan", editStep("edit")), proposal("fenced", "```groovy\napi.rename('a','b')\n```", "edit"), badScope, done("No changes were proposed."));
        try (CodexArcadiaAgent agent = agent(transport, successfulReads())) {
            assertFalse(run(agent).hasProposal()); assertEquals(Boolean.FALSE, transport.responses.get("fenced").get("success"));
            assertEquals(Boolean.FALSE, transport.responses.get("bad-condition").get("success"));
        }
    }

    @Test public void nullExpectedPostconditionAndExactEmbeddedFencesArePreserved() {
        Fake transport = new Fake(); Map<String, Object> write = proposal("write", "return [note:'``` literal']", "edit");
        object(object(write.get("params")).get("arguments")).put("postconditions", List.of(map("stepId", "edit", "kind", "attribute_equals", "id", "native-id", "attribute", "description", "expected", null)));
        transport.add(plan("plan", editStep("edit")), write);
        try (CodexArcadiaAgent agent = agent(transport, successfulReads())) {
            ArcadiaAgent.Turn review = run(agent); assertTrue(review.hasProposal()); assertEquals(1, review.postconditions().size());
            assertTrue(review.postconditions().get(0).arguments().containsKey("expected")); assertNull(review.postconditions().get(0).arguments().get("expected"));
        }
    }

    @Test public void privacyPreservesInstructionsProtocolKeysStepIdsAndTypedReceiptValues() {
        Fake transport = new Fake();
        UnaryOperator<String> tokenize = text -> text.replace("completedStepIds", "ELEM_0003").replace("applied", "ELEM_0002")
            .replace("VehicleName", "ELEM_0001").replace("LogicalFunction", "ELEM_0004");
        UnaryOperator<String> detokenize = text -> text.replace("ELEM_0001", "VehicleName");
        UnaryOperator<String> encode = text -> AgentResponseDecoder.encodeMessage(text, tokenize);
        UnaryOperator<String> decode = text -> AgentResponseDecoder.decode(text, detokenize);
        transport.add(plan("plan", editStep("applied")), call("read", "model_summary", Map.of()),
            proposal("write", "api.rename('id', 'ELEM_0001')", "applied"), done("Paused before execution."), inTurn(done("ELEM_0001 changed."), "turn-2"));
        try (CodexArcadiaAgent agent = agent(transport, (name, args) -> "{\"ok\":true,\"name\":\"VehicleName\",\"type\":\"LogicalFunction\",\"applied\":true}", new ArrayList<>(), null, decode, encode, defaults())) {
            ArcadiaAgent.Turn review = agent.run("API keyword applied; field completedStepIds", List.of(Message.user("AUTHORITATIVE CAPELLA EXECUTION OUTCOME\n{\"applied\":true,\"completedStepIds\":[\"applied\"]}")), "VehicleName", () -> false, null);
            assertTrue(String.valueOf(transport.params("thread/start").get("developerInstructions")).startsWith("API keyword applied; field completedStepIds"));
            assertTrue(inputText(transport).contains("\"completedStepIds\":[\"applied\"]")); assertTrue(inputText(transport).contains("ELEM_0001"));
            assertEquals("api.rename('id', 'VehicleName')", review.proposalCode());
            assertTrue(toolText(transport.responses.get("read")).contains("\"applied\":true")); assertTrue(toolText(transport.responses.get("read")).contains("ELEM_0001"));
            assertTrue(toolText(transport.responses.get("read")).contains("\"type\":\"LogicalFunction\""));
            ArcadiaAgent.ExecutionOutcome outcome = committed("applied"); agent.recordOutcome(review, outcome);
            String receipt = toolText(transport.responses.get("write"));
            String json = receipt.substring(receipt.indexOf('\n') + 1, receipt.indexOf("\nCommitted changes"));
            Map<String, Object> facts = object(AgentJson.parse(json, 32_000));
            assertEquals(Boolean.TRUE, facts.get("applied")); assertEquals(List.of("applied"), facts.get("completedStepIds"));
            assertFalse(facts.containsKey("ELEM_0002"));
            assertEquals("VehicleName changed.", agent.finish(review, outcome, () -> false, null).reply());
        }
    }

    @Test public void privacyCannotChangeNativeToolIdentity() {
        Fake transport = new Fake(); transport.add(call("read", "model_summary", Map.of())); AtomicInteger reads = new AtomicInteger();
        UnaryOperator<String> decode = json -> json.replace("model_summary", "model_search");
        try (CodexArcadiaAgent agent = agent(transport, (name, args) -> { reads.incrementAndGet(); return "{\"ok\":true}"; }, new ArrayList<>(), null, decode, UnaryOperator.identity(), defaults())) {
            assertThrows(ArcadiaAgent.AgentException.class, () -> run(agent)); assertEquals(0, reads.get());
        }
    }

    @Test public void sanitizedConnectionFailuresRetainUsefulFixedDiagnosticsWithoutRemotePayloads() {
        for (String detail : List.of("Codex rejected the request (RPC code -32000).", "sk-secret-test /private/model/name")) {
            Fake transport = new Fake(); transport.failMethod = "turn/start"; transport.failure = new IOException(detail);
            try (CodexArcadiaAgent agent = agent(transport, successfulReads())) {
                ArcadiaAgent.AgentException error = assertThrows(ArcadiaAgent.AgentException.class, () -> run(agent));
                assertFalse(error.toString().contains("sk-secret")); assertFalse(error.toString().contains("/private")); assertNull(error.getCause());
                if (detail.startsWith("Codex rejected")) assertTrue(error.getMessage().contains("-32000"));
            }
        }
    }

    private static ArcadiaAgent.Turn run(CodexArcadiaAgent agent) { return agent.run("Captured Capella API instructions", List.of(), "Help with this model", () -> false, null); }
    private static ArcadiaAgent.ReadTools successfulReads() { return (name, args) -> "{\"ok\":true}"; }
    private static ArcadiaAgent.ExecutionOutcome committed(String step) { return new ArcadiaAgent.ExecutionOutcome(true, true, true, "Independently checked", Set.of(step)); }
    private static CodexArcadiaAgent.Limits defaults() { return new CodexArcadiaAgent.Limits(32, 24, 3, Duration.ofSeconds(5)); }
    private static CodexArcadiaAgent agent(Fake fake, ArcadiaAgent.ReadTools reads) { return agent(fake, reads, new ArrayList<>(), null, UnaryOperator.identity(), UnaryOperator.identity(), defaults()); }
    private static CodexArcadiaAgent agent(Fake fake, ArcadiaAgent.ReadTools reads, List<String> saved, String thread, UnaryOperator<String> decode, UnaryOperator<String> encode, CodexArcadiaAgent.Limits limits) {
        return new CodexArcadiaAgent(fake, reads, ArcadiaModelTools.toolNames(), saved::add, thread, null, "E:/synthetic-capella", decode, encode, limits);
    }
    private static Map<String, Object> readStep(String id) { return map("id", id, "title", "Inspect captured model", "status", "pending", "type", "read"); }
    private static Map<String, Object> editStep(String id) { return map("id", id, "title", "Create the requested native element", "status", "pending", "type", "edit"); }
    @SafeVarargs private static Map<String, Object> plan(String id, Map<String, Object>... steps) { return call(id, CodexToolSchemas.PLAN, map("message", "Plan", "steps", List.of(steps))); }
    private static Map<String, Object> proposal(String id, String source, String step) { return call(id, CodexToolSchemas.PROPOSE, map("message", "Review this exact change", "script", source, "stepIds", List.of(step))); }
    private static Map<String, Object> call(String id, String name, Map<String, Object> arguments) { return map("id", id, "method", "item/tool/call", "params", map("threadId", "thread-1", "turnId", "turn-1", "callId", "call-" + id, "tool", name, "arguments", arguments)); }
    private static Map<String, Object> done(String text) { return notification("turn/completed", map("turn", map("id", "turn-1", "status", "completed", "items", List.of(map("type", "agentMessage", "id", "answer", "phase", "final_answer", "text", text))))); }
    private static Map<String, Object> notification(String method, Map<String, Object> params) { params.put("threadId", "thread-1"); params.put("turnId", "turn-1"); return map("method", method, "params", params); }
    private static String inputText(Fake transport) { return inputText(transport.params("turn/start")); }
    private static String inputText(Map<String, Object> params) { return String.valueOf(object(((List<?>) params.get("input")).get(0)).get("text")); }
    private static Map<String, Object> inTurn(Map<String, Object> event, String turnId) {
        Map<String, Object> params = object(event.get("params")); params.put("turnId", turnId);
        if (params.get("turn") instanceof Map<?, ?>) object(params.get("turn")).put("id", turnId);
        return event;
    }
    private static String toolText(Map<String, Object> response) { return String.valueOf(object(((List<?>) response.get("contentItems")).get(0)).get("text")); }
    private static Map<String, Object> readResult(Fake transport, String call) {
        String text = toolText(transport.responses.get(call)); return object(AgentJson.parse(text.substring(text.indexOf('\n') + 1), 32_000));
    }
    private static Map<String, Object> map(Object... entries) { Map<String, Object> result = new LinkedHashMap<>(); for (int index = 0; index < entries.length; index += 2) result.put((String) entries[index], entries[index + 1]); return result; }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }

    private record Request(String method, Map<String, Object> params) { }
    private static final class Fake implements CodexTransport {
        final Deque<Map<String, Object>> events = new ArrayDeque<>(); final List<Request> requests = new ArrayList<>();
        final Map<Object, Map<String, Object>> responses = new LinkedHashMap<>(); final Map<Object, Integer> responseCounts = new LinkedHashMap<>();
        final Map<Object, Integer> rejected = new LinkedHashMap<>();
        Map<String, Object> account = map("requiresOpenaiAuth", true, "account", map("type", "chatgpt"));
        String thread = "thread-1", failMethod, failRespond; Exception failure; Runnable beforeEvent, onInterrupt; boolean closed;
        @SafeVarargs final void add(Map<String, Object>... values) { events.addAll(List.of(values)); }
        long count(String method) { return requests.stream().filter(item -> method.equals(item.method)).count(); }
        Map<String, Object> params(String method) { return params(method, 0); }
        Map<String, Object> params(String method, int index) { return requests.stream().filter(item -> method.equals(item.method)).skip(index).findFirst().orElseThrow().params; }
        @Override public Map<String, Object> request(String method, Map<String, Object> params, BooleanSupplier cancelled) throws Exception {
            if (cancelled != null && cancelled.getAsBoolean()) throw new CancellationException();
            requests.add(new Request(method, params)); if (method.equals(failMethod)) throw failure;
            if ("turn/interrupt".equals(method) && onInterrupt != null) { Runnable callback = onInterrupt; onInterrupt = null; callback.run(); }
            return switch (method) { case "initialize", "turn/interrupt" -> Map.of(); case "account/read" -> account;
                case "thread/start", "thread/resume" -> map("thread", map("id", thread)); case "turn/start" -> map("turn", map("id", "turn-" + count("turn/start")));
                default -> throw new IOException("Unexpected fake RPC"); };
        }
        @Override public void notify(String method, Map<String, Object> params) { assertEquals("initialized", method); }
        @Override public Map<String, Object> nextEvent(BooleanSupplier cancelled, Duration timeout) {
            if (beforeEvent != null) { Runnable once = beforeEvent; beforeEvent = null; once.run(); }
            if (cancelled.getAsBoolean()) throw new CancellationException();
            if (events.isEmpty()) return null; return events.removeFirst();
        }
        @Override public void respond(Object id, Map<String, Object> response) throws Exception {
            responseCounts.merge(id, 1, Integer::sum);
            if (id.equals(failRespond)) throw new IOException("Could not write to Codex app-server");
            if (closed) throw new IOException("Codex app-server connection was closed.");
            assertFalse("Native request was answered twice", responses.containsKey(id)); responses.put(id, response);
        }
        @Override public void reject(Object id, int code, String message) { rejected.put(id, code); }
        @Override public void close() { closed = true; }
    }
}
