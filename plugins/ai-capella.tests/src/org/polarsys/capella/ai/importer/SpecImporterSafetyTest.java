package org.polarsys.capella.ai.importer;

import static org.junit.Assert.*;

import org.eclipse.sirius.business.api.session.Session;
import org.junit.After;
import org.junit.Test;
import org.polarsys.capella.ai.scripting.ExecutionResult;
import org.polarsys.capella.ai.scripting.ScriptRouter;
import org.polarsys.capella.ai.session.SelectionContext;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** The draft boundary is tested without credentials, provider calls or model mutation. */
public class SpecImporterSafetyTest {
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final Session session =
            (Session)
                    Proxy.newProxyInstance(
                            Session.class.getClassLoader(),
                            new Class<?>[] {Session.class},
                            (proxy, method, args) ->
                                    switch (method.getName()) {
                                        case "isOpen" -> open.get();
                                        case "hashCode" -> System.identityHashCode(proxy);
                                        case "equals" -> proxy == args[0];
                                        default -> null;
                                    });
    private final Gateway gateway = new Gateway();

    private static class Gateway implements SpecImporter.Gateway {
        String response =
                "Review scope\n"
                    + "```groovy\n"
                    + "println('First')\n"
                    + "println('Second')\n"
                    + "```\n"
                    + "Unresolved: chassis";
        String generatedRequest;
        String generatedConversation;
        String executedReply;
        int executions;

        @Override
        public String generate(
                Session session, String request, String conversation, SelectionContext context) {
            generatedRequest = request;
            generatedConversation = conversation;
            return response;
        }

        @Override
        public ScriptRouter.Result execute(
                Session session,
                String request,
                String reply,
                String conversation,
                SelectionContext context,
                BooleanSupplier confirmation) {
            assertEquals(generatedRequest, request);
            assertEquals(generatedConversation, conversation);
            assertTrue(confirmation.getAsBoolean());
            executedReply = reply;
            executions++;
            return ScriptRouter.Result.executed(reply, ExecutionResult.success("OK"), 1);
        }
    }

    @After
    public void clearInterrupt() {
        Thread.interrupted();
    }

    private SpecImporter.ImportResult draft() {
        return SpecImporter.generateDraft(session, "Source rule EV.3.3.1", null, gateway);
    }

    @Test
    public void generationOnlyCreatesDraftAndCoversFivePerspectives() {
        var draft = draft();
        assertTrue(draft.success);
        assertFalse(draft.wasApplied());
        assertNull(draft.executionResult);
        assertEquals(0, gateway.executions);
        assertTrue(gateway.generatedRequest.contains("OA, SA, LA, PA and EPBS"));
        assertTrue(gateway.generatedRequest.contains("Source rule EV.3.3.1"));
        assertTrue(gateway.generatedRequest.contains("Do not invent"));
    }

    @Test
    public void noReviewerAndDeclinedReviewNeverExecute() {
        assertFalse(SpecImporter.applyReviewed(draft(), null, gateway).success);
        assertFalse(SpecImporter.applyReviewed(draft(), code -> false, gateway).success);
        assertEquals(0, gateway.executions);
    }

    @Test
    public void reviewReceivesExactlyExecutedCodeAndOriginalDraftCannotRunTwice() {
        var draft = draft();
        var applied =
                SpecImporter.applyReviewed(
                        draft,
                        code -> {
                            assertEquals("println('First')\nprintln('Second')", code);
                            return true;
                        },
                        gateway);
        assertTrue(applied.wasApplied());
        assertEquals(draft.reply, gateway.executedReply);
        assertFalse(SpecImporter.applyReviewed(draft, code -> true, gateway).success);
        assertEquals(1, gateway.executions);
    }

    @Test
    public void closingModelDuringReviewPreventsExecution() {
        var result =
                SpecImporter.applyReviewed(
                        draft(),
                        code -> {
                            open.set(false);
                            return true;
                        },
                        gateway);
        assertFalse(result.success);
        assertTrue(result.error.contains("closed"));
        assertEquals(0, gateway.executions);
    }

    @Test
    public void cancellationDuringReviewPreventsExecution() {
        var result =
                SpecImporter.applyReviewed(
                        draft(),
                        code -> {
                            Thread.currentThread().interrupt();
                            return true;
                        },
                        gateway);
        assertFalse(result.success);
        assertTrue(result.error.contains("stopped"));
        assertEquals(0, gateway.executions);
    }

    @Test
    public void closedSessionAndBlankSpecNeverGenerate() {
        open.set(false);
        assertFalse(draft().success);
        open.set(true);
        assertFalse(SpecImporter.generateDraft(session, "  ", null, gateway).success);
        assertNull(gateway.generatedRequest);
    }

    @Test
    public void conversationalReplyDoesNotBecomeExecutableDraft() {
        gateway.response = "Need the source specification before preparing a model.";
        assertFalse(draft().success);
        assertEquals(0, gateway.executions);
    }
}
