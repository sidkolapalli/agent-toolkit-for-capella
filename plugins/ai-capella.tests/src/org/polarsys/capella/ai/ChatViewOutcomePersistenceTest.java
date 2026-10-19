package org.polarsys.capella.ai;

import static org.junit.Assert.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.polarsys.capella.ai.agent.AgentJson;
import org.polarsys.capella.ai.agent.AgentRuntime;
import org.polarsys.capella.ai.agent.ArcadiaAgent;
import org.polarsys.capella.ai.llm.LLMService.Message;
import org.polarsys.capella.ai.presentation.ConversationState;
import org.polarsys.capella.ai.presentation.ConversationStore;

/** Actual local store round trips after view closure; no widgets, provider or model writes. */
public class ChatViewOutcomePersistenceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private ChatView view;
    private static final String MODEL = "platform:/resource/disposable/disposable.aird";

    @After public void stopUnusedExecutor() throws Exception {
        if (view != null) ((ExecutorService) field("requests")).shutdownNow();
    }

    @Test public void closeAfterCommitPersistsTypedReceiptAndVisibleExecutionFactsAcrossReopen() throws Exception {
        Path directory = temporary.newFolder().toPath(); ConversationStore store = new ConversationStore(directory);
        ConversationState captured = captured(); store.save(MODEL, captured);
        closedView(captured, MODEL, store);
        ArcadiaAgent.ExecutionOutcome outcome = outcome(true);
        ArcadiaAgent.Turn recorded = snapshot(outcome); FakeRuntime runtime = new FakeRuntime(recorded);
        Object request = request(captured, MODEL, store);
        record(runtime, recorded, outcome, request);
        ConversationState reopened = new ConversationStore(directory).load(MODEL);
        assertReceipt(reopened, true);
        assertEquals("Unsent draft", reopened.draft());
        assertEquals("Done · Create fixture", reopened.taskPlan());
        assertTrue(reopened.entries().stream().anyMatch(entry -> outcome.feedback().equals(entry.text())));
        assertEquals(1, runtime.records); assertEquals(0, runtime.inference);
        record(runtime, recorded, outcome, request);
        reopened = store.load(MODEL);
        assertEquals(1, reopened.history().stream().filter(message -> message.getContent().startsWith("AUTHORITATIVE CAPELLA EXECUTION OUTCOME\n")).count());
        assertEquals(1, reopened.entries().stream().filter(entry -> "Execution outcome".equals(entry.role())).count());
    }

    @Test public void closeAfterUnsavedCommitRetainsCommitTruthAndSaveFailure() throws Exception {
        ConversationStore store = new ConversationStore(temporary.newFolder().toPath());
        ConversationState captured = captured(); store.save(MODEL, captured); closedView(captured, MODEL, store);
        ArcadiaAgent.ExecutionOutcome outcome = outcome(false); ArcadiaAgent.Turn recorded = snapshot(outcome);
        record(new FakeRuntime(recorded), recorded, outcome, request(captured, MODEL, store));
        ConversationState reopened = store.load(MODEL); assertReceipt(reopened, false);
        assertTrue(reopened.entries().get(reopened.entries().size() - 1).text().contains("SAVE_FAILED_AFTER_COMMIT"));
    }

    @Test public void lateReceiptMergesWithoutOverwritingNewerSameModelChat() throws Exception {
        ConversationStore store = new ConversationStore(temporary.newFolder().toPath());
        ConversationState captured = captured(); store.save(MODEL, captured); closedView(captured, MODEL, store);
        ConversationState newer = new ConversationState(); newer.draft("New view draft"); newer.codexThreadId("new-thread");
        newer.taskPlan("Pending · New task"); newer.addReply("New user task", "New proposed answer");
        newer.append("You", "New user task"); store.save(MODEL, newer);
        ArcadiaAgent.ExecutionOutcome outcome = outcome(true); ArcadiaAgent.Turn recorded = snapshot(outcome);
        record(new FakeRuntime(recorded), recorded, outcome, request(captured, MODEL, store));
        ConversationState reopened = store.load(MODEL);
        assertEquals("New view draft", reopened.draft()); assertEquals("new-thread", reopened.codexThreadId());
        assertEquals("Pending · New task", reopened.taskPlan());
        assertEquals("New user task", reopened.history().get(0).getContent());
        assertTrue(reopened.entries().stream().anyMatch(entry -> "New user task".equals(entry.text())));
        assertFalse(reopened.history().stream().anyMatch(message -> "Old proposal".equals(message.getContent())));
        assertReceipt(reopened, true);
    }

    @Test public void capturedModelKeyNeverWritesAnotherModelsConversation() throws Exception {
        ConversationStore store = new ConversationStore(temporary.newFolder().toPath());
        ConversationState captured = captured(); store.save(MODEL, captured);
        String anotherModel = "platform:/resource/another/another.aird";
        ConversationState another = new ConversationState(); another.draft("Other model draft");
        another.addReply("Other request", "Other reply"); store.save(anotherModel, another);
        closedView(another, anotherModel, store);
        ArcadiaAgent.ExecutionOutcome outcome = outcome(true); ArcadiaAgent.Turn recorded = snapshot(outcome);
        record(new FakeRuntime(recorded), recorded, outcome, request(captured, MODEL, store));
        ConversationState unchanged = store.load(anotherModel);
        assertEquals(another.draft(), unchanged.draft()); assertEquals(2, unchanged.history().size());
        assertEquals("Other reply", unchanged.history().get(1).getContent()); assertReceipt(store.load(MODEL), true);
    }

    @Test public void directNullSnapshotPreservesExistingFeedbackWithoutInferenceOrFakeReceipt() throws Exception {
        ConversationStore store = new ConversationStore(temporary.newFolder().toPath());
        ConversationState captured = captured(); ArcadiaAgent.ExecutionOutcome outcome = outcome(false);
        captured.addOutcome(outcome.feedback()); store.save(MODEL, captured); closedView(captured, MODEL, store);
        FakeRuntime direct = new FakeRuntime(null);
        record(direct, snapshot(outcome), outcome, request(captured, MODEL, store));
        ConversationState reopened = store.load(MODEL);
        assertEquals(1, reopened.history().stream().filter(message -> outcome.feedback().equals(message.getContent())).count());
        assertFalse(reopened.history().stream().anyMatch(message -> message.getContent().startsWith("AUTHORITATIVE CAPELLA EXECUTION OUTCOME\n")));
        assertEquals(0, direct.inference); assertEquals(1, direct.records);
    }

    @Test public void transportFailureSnapshotCanBePersistedAfterWidgetsAreGone() throws Exception {
        ConversationStore store = new ConversationStore(temporary.newFolder().toPath());
        ConversationState captured = captured(); store.save(MODEL, captured); closedView(captured, MODEL, store);
        ArcadiaAgent.ExecutionOutcome outcome = outcome(false);
        invoke("restoreRequestOutcome", new Class<?>[] {requestType(), ArcadiaAgent.Turn.class, ArcadiaAgent.ExecutionOutcome.class},
            request(captured, MODEL, store), snapshot(outcome), outcome);
        assertReceipt(store.load(MODEL), false);
    }

    @Test public void previousOrUntrustedReceiptCannotReplaceCurrentActualOutcome() throws Exception {
        ConversationStore store = new ConversationStore(temporary.newFolder().toPath());
        ConversationState captured = captured(); store.save(MODEL, captured); closedView(captured, MODEL, store);
        ArcadiaAgent.ExecutionOutcome actual = outcome(false);
        ArcadiaAgent.Turn stale = snapshot(new ArcadiaAgent.ExecutionOutcome(false, false, false, "Unrelated prior receipt", Set.of()));
        invoke("restoreRequestOutcome", new Class<?>[] {requestType(), ArcadiaAgent.Turn.class, ArcadiaAgent.ExecutionOutcome.class},
            request(captured, MODEL, store), stale, actual);
        ConversationState reopened = store.load(MODEL);
        assertTrue(reopened.history().stream().anyMatch(message -> actual.feedback().equals(message.getContent())));
        assertFalse(reopened.history().stream().anyMatch(message -> message.getContent().contains("Unrelated prior receipt")));
    }

    @Test public void failedGenerationSnapshotPersistsOnlyWhileCapturedChatIsStillCurrentOnDisk() throws Exception {
        ConversationStore store = new ConversationStore(temporary.newFolder().toPath());
        ConversationState captured = captured(); store.save(MODEL, captured); closedView(captured, MODEL, store);
        ArcadiaAgent.Turn failure = new ArcadiaAgent.Turn("", null, List.of(),
            List.of(Message.user("Create fixture"), Message.assistant("Read evidence before transport failed")), 2, 1, Set.of());
        invoke("restoreRequestOutcome", new Class<?>[] {requestType(), ArcadiaAgent.Turn.class, ArcadiaAgent.ExecutionOutcome.class},
            request(captured, MODEL, store), failure, null);
        assertEquals("Read evidence before transport failed", store.load(MODEL).history().get(1).getContent());
        ConversationState newer = new ConversationState(); newer.codexThreadId("new-thread");
        newer.addReply("New task", "New answer"); store.save(MODEL, newer);
        invoke("restoreRequestOutcome", new Class<?>[] {requestType(), ArcadiaAgent.Turn.class, ArcadiaAgent.ExecutionOutcome.class},
            request(captured, MODEL, store), failure, null);
        assertEquals("New task", store.load(MODEL).history().get(0).getContent());
        assertEquals("New answer", store.load(MODEL).history().get(1).getContent());
    }

    private void closedView(ConversationState state, String key, ConversationStore store) throws Exception {
        view = new ChatView(); set("conversation", state); set("conversationKey", key); set("conversationStore", store);
        set("disposed", true); // dispose already saved the chat; the worker now finishes its actual receipt.
    }
    private static ConversationState captured() {
        ConversationState state = new ConversationState(); state.draft("Unsent draft"); state.codexThreadId("old-thread");
        state.taskPlan("Pending · Create fixture"); state.addReply("Create fixture", "Old proposal"); return state;
    }
    private static ArcadiaAgent.ExecutionOutcome outcome(boolean saved) {
        return new ArcadiaAgent.ExecutionOutcome(true, true, saved,
            saved ? "Model committed and saved; fixture created." : "SAVE_FAILED_AFTER_COMMIT; do not rerun the committed script.", Set.of("edit"));
    }
    private static ArcadiaAgent.Turn snapshot(ArcadiaAgent.ExecutionOutcome outcome) {
        String receipt = "AUTHORITATIVE CAPELLA EXECUTION OUTCOME\n" + AgentJson.write(Map.of("applied", outcome.applied(),
            "committed", outcome.committed(), "saved", outcome.saved(), "completedStepIds", List.copyOf(outcome.completedStepIds()),
            "feedback", outcome.feedback(), "feedbackTruncated", false));
        return new ArcadiaAgent.Turn("", null, List.of(new ArcadiaAgent.Step("edit", "Create fixture",
            outcome.completedStepIds().contains("edit") ? "completed" : "pending", "edit")),
            List.of(Message.user("Create fixture"), Message.assistant("Old proposal"), Message.user(receipt)), 5, 2, Set.of());
    }
    private static void assertReceipt(ConversationState state, boolean saved) {
        Message last = state.history().get(state.history().size() - 1);
        String prefix = "AUTHORITATIVE CAPELLA EXECUTION OUTCOME\n"; assertTrue(last.getContent().startsWith(prefix));
        Map<?,?> facts = (Map<?,?>) AgentJson.parse(last.getContent().substring(prefix.length()), 40000);
        assertEquals(Boolean.TRUE, facts.get("applied")); assertEquals(Boolean.TRUE, facts.get("committed"));
        assertEquals(saved, facts.get("saved")); assertEquals(List.of("edit"), facts.get("completedStepIds"));
    }
    private Object request(ConversationState state, String key, ConversationStore store) throws Exception {
        Constructor<?> constructor = requestType().getDeclaredConstructor(ConversationState.class, String.class, ConversationStore.class);
        constructor.setAccessible(true); return constructor.newInstance(state, key, store);
    }
    private static Class<?> requestType() throws Exception {
        return Class.forName(ChatView.class.getName() + "$RequestHistory", false, ChatView.class.getClassLoader());
    }
    private void record(AgentRuntime runtime, ArcadiaAgent.Turn turn, ArcadiaAgent.ExecutionOutcome outcome, Object request) throws Exception {
        invoke("recordExecutionOutcome", new Class<?>[] {AgentRuntime.class, ArcadiaAgent.Turn.class, ArcadiaAgent.ExecutionOutcome.class, requestType()},
            runtime, turn, outcome, request);
    }
    private void invoke(String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = ChatView.class.getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(view, arguments);
    }
    private Object field(String name) throws Exception { var field = ChatView.class.getDeclaredField(name); field.setAccessible(true); return field.get(view); }
    private void set(String name, Object value) throws Exception { var field = ChatView.class.getDeclaredField(name); field.setAccessible(true); field.set(view, value); }
    private static final class FakeRuntime implements AgentRuntime {
        final ArcadiaAgent.Turn recorded; int records, inference;
        FakeRuntime(ArcadiaAgent.Turn recorded) { this.recorded = recorded; }
        @Override public ArcadiaAgent.Turn run(String prompt, List<Message> history, String user, BooleanSupplier stop, Consumer<ArcadiaAgent.Progress> progress) {
            inference++; throw new AssertionError("No inference is permitted for persistence");
        }
        @Override public ArcadiaAgent.Turn finish(ArcadiaAgent.Turn previous, ArcadiaAgent.ExecutionOutcome outcome, BooleanSupplier stop, Consumer<ArcadiaAgent.Progress> progress) {
            inference++; throw new AssertionError("No inference is permitted for persistence");
        }
        @Override public String protocolGuidance() { return ""; }
        @Override public void recordOutcome(ArcadiaAgent.Turn turn, ArcadiaAgent.ExecutionOutcome outcome) { records++; }
        @Override public ArcadiaAgent.Turn outcomeSnapshot() { return recorded; }
    }
}
