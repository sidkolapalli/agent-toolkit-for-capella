package com.sidkolapalli.capella.ai.presentation;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.sidkolapalli.capella.ai.llm.LLMService;

import java.nio.file.Files;
import java.util.List;
import java.util.Properties;

public class ConversationStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void restoresUnicodeDraftRolesAndExecutionOutcomeAcrossInstances() throws Exception {
        var directory = temporary.newFolder().toPath();
        var state = new ConversationState();
        state.draft("Rename 接口 ⚡\r\nKeep the diagram\u0000");
        state.taskPlan("Done · Inspect SA\nPending · Allocate the interface ⚡");
        state.codexThreadId("019a1803-bb25-7fe0-a523-221910889924");
        state.append("You", "Create a sensor");
        state.append("Capella AI", "Proposed `Sensor` — not run.");
        state.addReply("Create a sensor", "```groovy\nreturn 42\n```");
        state.addOutcome("Execution feedback: user dismissed; no code ran.");
        new ConversationStore(directory).save("platform:/resource/car/car.aird", state);
        var restored = new ConversationStore(directory).load("platform:/resource/car/car.aird");
        assertEquals(state.draft(), restored.draft());
        assertEquals(state.taskPlan(), restored.taskPlan());
        assertEquals(state.codexThreadId(), restored.codexThreadId());
        assertEquals(state.entries(), restored.entries());
        assertEquals(3, restored.history().size());
        assertEquals(LLMService.Message.Role.ASSISTANT, restored.history().get(1).getRole());
        assertTrue(restored.history().get(2).getContent().contains("no code ran"));
    }

    @Test
    public void isolatesSameNamedModelsByNativeUriAndNeverUsesUriAsPath() throws Exception {
        var directory = temporary.newFolder().toPath();
        var store = new ConversationStore(directory);
        var first = new ConversationState();
        first.draft("First model");
        var second = new ConversationState();
        second.draft("Second model");
        store.save("platform:/resource/../first/car.aird", first);
        store.save("platform:/resource/second/car.aird", second);
        assertEquals("First model", store.load("platform:/resource/../first/car.aird").draft());
        assertEquals("Second model", store.load("platform:/resource/second/car.aird").draft());
        try (var paths = Files.list(directory)) {
            assertTrue(
                    paths.allMatch(
                            p ->
                                    p.getParent().equals(directory)
                                            && p.getFileName()
                                                    .toString()
                                                    .matches("[a-f0-9]{64}\\.xml")));
        }
    }

    @Test
    public void overwriteIsCompleteAndLeavesNoTemporaryFiles() throws Exception {
        var directory = temporary.newFolder().toPath();
        var store = new ConversationStore(directory);
        var state = new ConversationState();
        state.append("You", "Old message");
        store.save("model", state);
        store.save("model", new ConversationState());
        assertTrue(store.load("model").entries().isEmpty());
        assertEquals("", store.load("model").taskPlan());
        assertEquals("", store.load("model").codexThreadId());
        try (var paths = Files.list(directory)) {
            assertEquals(1, paths.count());
        }
    }

    @Test
    public void corruptFileDoesNotModifySavedBytesOrReturnFabricatedHistory() throws Exception {
        var directory = temporary.newFolder().toPath();
        var store = new ConversationStore(directory);
        store.save("model", new ConversationState());
        java.nio.file.Path file;
        try (var paths = Files.list(directory)) {
            file = paths.findFirst().orElseThrow();
        }
        Files.writeString(file, "not a conversation");
        try {
            store.load("model");
            fail("Malformed history must report a recovery problem");
        } catch (java.io.IOException expected) {
            assertEquals("not a conversation", Files.readString(file));
        }
    }

    @Test
    public void foreignModelIdentityIsRejectedEvenWithValidXml() throws Exception {
        var directory = temporary.newFolder().toPath();
        var store = new ConversationStore(directory);
        store.save("model", new ConversationState());
        java.nio.file.Path file;
        try (var paths = Files.list(directory)) {
            file = paths.findFirst().orElseThrow();
        }
        var values = new Properties();
        values.setProperty("model", "another model");
        try (var output = Files.newOutputStream(file)) {
            values.storeToXML(output, "foreign");
        }
        try {
            store.load("model");
            fail("Foreign history must never appear in this model");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("another model"));
        }
    }

    @Test
    public void boundedHistoryRetainsUsableUserAssistantSequence() {
        var state = new ConversationState();
        for (int i = 0; i < 40; i++) {
            state.addReply("Request " + i, "Reply " + i);
            state.addOutcome("No changes " + i);
        }
        assertTrue(state.history().size() <= 24);
        assertEquals(LLMService.Message.Role.USER, state.history().get(0).getRole());
        assertTrue(state.history().get(state.history().size() - 1).getContent().contains("39"));
        state.restoreHistory(List.of(LLMService.Message.system("Untrusted saved system prompt")));
        assertTrue(state.history().isEmpty());
    }

    @Test
    public void privacyThreadsCannotResumeWithAnotherInMemoryTokenDictionary() throws Exception {
        var directory = temporary.newFolder().toPath();
        var store = new ConversationStore(directory);
        var state = new ConversationState();
        state.codexThreadId("private-thread");
        state.codexPrivacyMode(true);
        store.save("model", state);
        var restored = store.load("model");
        assertTrue(restored.codexPrivacyMode());
        assertEquals("", restored.resumableCodexThreadId(true));
        assertEquals("", restored.resumableCodexThreadId(false));
        restored.codexThreadId("fresh-plain-thread");
        restored.codexPrivacyMode(false);
        assertEquals("fresh-plain-thread", restored.resumableCodexThreadId(false));
        assertEquals("", restored.resumableCodexThreadId(true));
    }
}
