package org.polarsys.capella.ai.scripting;

import static org.junit.Assert.*;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.polarsys.capella.ai.llm.LLMService;
import org.polarsys.capella.ai.preferences.AdvancedPreferences;
import org.polarsys.capella.ai.preferences.LLMPreferences;
import org.polarsys.capella.ai.privacy.Tokenizer;
import org.polarsys.capella.ai.session.SelectionContext;
import org.eclipse.sirius.business.api.session.Session;

public class ScriptRouterSafetyTest {
    private static final String REPLY = "```groovy\napi.create(\"SA\", \"SystemFunction\", \"Example\")\n```";

    @Test
    public void disablingActionsPreventsExecutionAndSkipsConfirmation() {
        boolean previous = LLMPreferences.isToolActionsEnabled();
        try {
            LLMPreferences.setToolActionsEnabled(false);
            AtomicBoolean prompted = new AtomicBoolean();
            ScriptRouter.Result result = ScriptRouter.executeReply(null, "Create a function", REPLY,
                "test", SelectionContext.empty(), () -> { prompted.set(true); return true; });
            assertFalse(result.codeExecuted);
            assertFalse(prompted.get());
            assertTrue(result.reply.contains("actions are disabled"));
        } finally {
            LLMPreferences.setToolActionsEnabled(previous);
        }
    }

    @Test
    public void requiringConfirmationPreventsUnattendedExecution() {
        boolean previousActions = LLMPreferences.isToolActionsEnabled();
        boolean previousConfirmation = AdvancedPreferences.isConfirmBeforeApplyEnabled();
        try {
            LLMPreferences.setToolActionsEnabled(true);
            AdvancedPreferences.setConfirmBeforeApplyEnabled(true);
            ScriptRouter.Result result = ScriptRouter.executeReply(null, "Create", REPLY,
                "test", SelectionContext.empty(), null);
            assertFalse(result.codeExecuted);
            assertTrue(result.reply.contains("Review and confirm"));
            AtomicBoolean prompted = new AtomicBoolean();
            result = ScriptRouter.executeReply(null, "Create", REPLY, "test", SelectionContext.empty(),
                () -> { prompted.set(true); return false; });
            assertTrue(prompted.get());
            assertFalse(result.codeExecuted);
        } finally {
            LLMPreferences.setToolActionsEnabled(previousActions);
            AdvancedPreferences.setConfirmBeforeApplyEnabled(previousConfirmation);
        }
    }

    @Test
    public void disablingActionsDuringReviewPreventsExecution() {
        boolean previousActions = LLMPreferences.isToolActionsEnabled();
        boolean previousConfirmation = AdvancedPreferences.isConfirmBeforeApplyEnabled();
        try {
            LLMPreferences.setToolActionsEnabled(true);
            AdvancedPreferences.setConfirmBeforeApplyEnabled(true);
            ScriptRouter.Result result = ScriptRouter.executeReply(null, "Create", REPLY,
                "test", SelectionContext.empty(), () -> {
                    LLMPreferences.setToolActionsEnabled(false);
                    return true;
                });
            assertFalse(result.codeExecuted);
            assertTrue(result.reply.contains("actions are disabled"));
        } finally {
            LLMPreferences.setToolActionsEnabled(previousActions);
            AdvancedPreferences.setConfirmBeforeApplyEnabled(previousConfirmation);
        }
    }

    @Test
    public void privacyReplacesNamesInUserAndAssistantHistoryWithoutChangingInputs() throws Exception {
        Tokenizer tokenizer = Tokenizer.forSession("router-test-" + UUID.randomUUID());
        String instructions = "Use api.set(element, \"name\", value) and api.create(\"SA\", \"SystemFunction\", value).";
        List<LLMService.Message> history = List.of(
            LLMService.Message.system(instructions),
            LLMService.Message.user("Rename \"Secret Project Name\" with id 123e4567-e89b-12d3-a456-426614174000"),
            LLMService.Message.assistant("```groovy\napi.find(\"Secret Project Name\")\n```"));
        Method method = ScriptRouter.class.getDeclaredMethod("tokenizeMessages", Tokenizer.class, List.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<LLMService.Message> outgoing = (List<LLMService.Message>) method.invoke(null, tokenizer, history);
        assertEquals(instructions, outgoing.get(0).getContent());
        assertFalse(outgoing.get(1).getContent().contains("Secret Project Name"));
        assertFalse(outgoing.get(1).getContent().contains("123e4567-e89b-12d3-a456-426614174000"));
        assertFalse(outgoing.get(2).getContent().contains("Secret Project Name"));
        assertTrue(history.get(1).getContent().contains("Secret Project Name"));
        assertEquals(history.get(2).getContent(), tokenizer.detokenizeText(outgoing.get(2).getContent()));
    }

    @Test
    public void historyKeepsOnlyRecentConversationMessagesAndStartsWithAUserRequest() throws Exception {
        List<LLMService.Message> history = new ArrayList<>();
        history.addAll(Arrays.asList(null, LLMService.Message.system("old instructions"), LLMService.Message.user(" ")));
        for (int i = 0; i < 7; i++) {
            history.add(LLMService.Message.user("request " + i));
            history.add(LLMService.Message.assistant("draft " + i));
        }
        history.add(LLMService.Message.user("latest follow-up"));
        Method method = ScriptRouter.class.getDeclaredMethod("recentHistory", List.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<LLMService.Message> outgoing = (List<LLMService.Message>) method.invoke(null, history);
        assertEquals(9, outgoing.size());
        assertEquals(LLMService.Message.Role.USER, outgoing.get(0).getRole());
        assertEquals("request 3", outgoing.get(0).getContent());
        assertEquals("latest follow-up", outgoing.get(outgoing.size() - 1).getContent());
        assertTrue(outgoing.stream().noneMatch(message -> message.getRole() == LLMService.Message.Role.SYSTEM));
        assertEquals(18, history.size());
    }

    @Test public void structuredProposalCannotCaptureOrRunWithoutReview() {
        boolean actions = LLMPreferences.isToolActionsEnabled(), confirm = AdvancedPreferences.isConfirmBeforeApplyEnabled();
        try {
            LLMPreferences.setToolActionsEnabled(true); AdvancedPreferences.setConfirmBeforeApplyEnabled(true);
            AtomicBoolean captured = new AtomicBoolean();
            var result = ScriptRouter.executeProposal(null, "Create", "Proposed change", "return 42", "test",
                SelectionContext.empty(), () -> false, () -> captured.set(true));
            assertFalse(result.codeExecuted); assertFalse(captured.get());
            assertTrue(result.reply.contains("Review and confirm"));
        } finally { LLMPreferences.setToolActionsEnabled(actions); AdvancedPreferences.setConfirmBeforeApplyEnabled(confirm); }
    }

    @Test public void enablingReviewDuringPreparationDoesNotRunUnreviewedSource() {
        boolean actions = LLMPreferences.isToolActionsEnabled(), confirm = AdvancedPreferences.isConfirmBeforeApplyEnabled();
        try {
            LLMPreferences.setToolActionsEnabled(true); AdvancedPreferences.setConfirmBeforeApplyEnabled(false);
            var result = ScriptRouter.executeProposal(openSession(), "Create", "Proposed change", "return 42", "test",
                SelectionContext.empty(), null, () -> AdvancedPreferences.setConfirmBeforeApplyEnabled(true));
            assertFalse(result.codeExecuted); assertTrue(result.error.contains("review was enabled"));
        } finally { LLMPreferences.setToolActionsEnabled(actions); AdvancedPreferences.setConfirmBeforeApplyEnabled(confirm); }
    }

    @Test public void disablingActionsDuringPreparationDoesNotRunApprovedSource() {
        boolean actions = LLMPreferences.isToolActionsEnabled(), confirm = AdvancedPreferences.isConfirmBeforeApplyEnabled();
        try {
            LLMPreferences.setToolActionsEnabled(true); AdvancedPreferences.setConfirmBeforeApplyEnabled(true);
            var result = ScriptRouter.executeProposal(openSession(), "Create", "Proposed change", "return 42", "test",
                SelectionContext.empty(), () -> true, () -> LLMPreferences.setToolActionsEnabled(false));
            assertFalse(result.codeExecuted); assertTrue(result.error.contains("disabled before execution"));
        } finally { LLMPreferences.setToolActionsEnabled(actions); AdvancedPreferences.setConfirmBeforeApplyEnabled(confirm); }
    }

    private static Session openSession() {
        return (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[] {Session.class}, (proxy, method, args) ->
            method.getName().equals("isOpen") ? true : null);
    }

}
