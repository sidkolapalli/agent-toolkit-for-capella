package org.polarsys.capella.ai.importer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.scripting.CodeExecutionRouter;
import org.polarsys.capella.ai.scripting.ExecutionResult;
import org.polarsys.capella.ai.scripting.GroovyExecutor;
import org.polarsys.capella.ai.scripting.ScriptRouter;
import org.polarsys.capella.ai.session.SelectionContext;
import org.polarsys.capella.ai.session.SessionBridge;

/** Generates specification drafts through the assistant's privacy and execution path. */
public final class SpecImporter {
    private SpecImporter() {}

    /** A successful draft has no executionResult until it has been reviewed and applied. */
    public static final class ImportResult {
        public final boolean success;
        public final String reply;
        public final String generatedCode;
        public final ExecutionResult executionResult;
        public final String error;
        private final Session session;
        private final String request;
        private final String conversationId;
        private final SelectionContext context;
        private final AtomicBoolean executionClaimed;

        private ImportResult(boolean success, String reply, String code, ExecutionResult execution,
                String error, Session session, String request, String conversationId, SelectionContext context,
                AtomicBoolean executionClaimed) {
            this.success = success;
            this.reply = reply;
            this.generatedCode = code;
            this.executionResult = execution;
            this.error = error;
            this.session = session;
            this.request = request;
            this.conversationId = conversationId;
            this.context = context;
            this.executionClaimed = executionClaimed;
        }

        public boolean wasApplied() {
            return executionResult != null && executionResult.success();
        }

        public static ImportResult success(String code, ExecutionResult result) {
            return new ImportResult(true, null, code, result, null, null, null, null, null, new AtomicBoolean(result != null));
        }

        public static ImportResult failure(String error) {
            return new ImportResult(false, null, null, null, error, null, null, null, null, new AtomicBoolean());
        }

        private ImportResult withExecution(ScriptRouter.Result result) {
            return new ImportResult(result.success(), reply, generatedCode, result.executionResult,
                result.error, session, request, conversationId, context, executionClaimed);
        }

        private ImportResult failed(String message) {
            return new ImportResult(false, reply, generatedCode, null, message,
                session, request, conversationId, context, executionClaimed);
        }
    }

    /** Generate an immutable review draft. This method never executes generated code. */
    public static ImportResult importSpec(Session session, String specText) {
        return generateDraft(session, specText, SessionBridge.getSelectionContext(), ROUTER);
    }

    /** Generate code for a preview without applying changes. */
    public static String generateCode(Session session, String specText) {
        ImportResult draft = importSpec(session, specText);
        return draft.success ? draft.generatedCode : null;
    }

    /**
     * Show generatedCode to the caller's reviewer and apply precisely that draft only on acceptance.
     * Review is mandatory for imports, including when the general confirmation preference is disabled.
     */
    public static ImportResult applyReviewed(ImportResult draft, Predicate<String> review) {
        return applyReviewed(draft, review, ROUTER);
    }

    /** Adapter for the generation and reviewed execution boundary. */
    public interface Gateway {
        String generate(Session session, String request, String conversationId, SelectionContext context) throws Exception;
        ScriptRouter.Result execute(Session session, String request, String reply, String conversationId,
            SelectionContext context, BooleanSupplier confirmation);
    }

    private static final Gateway ROUTER = new Gateway() {
        @Override
        public String generate(Session session, String request, String conversationId, SelectionContext context) throws Exception {
            return ScriptRouter.generateReply(session, request, List.of(), conversationId, context);
        }

        @Override
        public ScriptRouter.Result execute(Session session, String request, String reply, String conversationId,
                SelectionContext context, BooleanSupplier confirmation) {
            return ScriptRouter.executeReply(session, request, reply, conversationId, context, confirmation);
        }
    };

    public static ImportResult generateDraft(Session session, String specText, SelectionContext context, Gateway gateway) {
        if (session == null || !session.isOpen()) return ImportResult.failure("No open Capella session");
        if (specText == null || specText.isBlank()) return ImportResult.failure("Empty specification");
        String request = buildUserPrompt(specText);
        String conversationId = "spec-" + UUID.randomUUID();
        try {
            String reply = gateway.generate(session, request, conversationId, context);
            if (!CodeExecutionRouter.containsCode(reply)) return ImportResult.failure("Assistant did not generate a code draft");
            String code = GroovyExecutor.extractCode(reply);
            if (code.isBlank()) return ImportResult.failure("Assistant generated an empty code draft");
            return new ImportResult(true, reply, code, null, null, session, request, conversationId, context, new AtomicBoolean());
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return ImportResult.failure("Error: " + failure.getMessage());
        }
    }

    public static ImportResult applyReviewed(ImportResult draft, Predicate<String> review, Gateway gateway) {
        if (draft == null || !draft.success || draft.session == null || draft.reply == null) {
            return ImportResult.failure("Generate a specification draft before applying it");
        }
        if (draft.executionClaimed.get()) return draft.failed("This draft has already been executed or is executing");
        if (Thread.currentThread().isInterrupted()) return draft.failed("Import stopped before review");
        if (!draft.session.isOpen()) return draft.failed("The model was closed before review");
        try {
            if (review == null || !review.test(draft.generatedCode)) return draft.failed("Draft was not applied; review and confirm the code first");
            if (Thread.currentThread().isInterrupted()) return draft.failed("Import stopped before execution");
            if (!draft.session.isOpen()) return draft.failed("The model was closed before execution");
            if (!draft.executionClaimed.compareAndSet(false, true)) return draft.failed("This draft is already executing");
            ScriptRouter.Result result = gateway.execute(draft.session, draft.request, draft.reply,
                draft.conversationId, draft.context, () -> true);
            if (!result.codeExecuted) draft.executionClaimed.set(false);
            return draft.withExecution(result);
        } catch (Exception failure) {
            return draft.failed("Error: " + failure.getMessage());
        }
    }

    static String buildUserPrompt(String specText) {
        return "Prepare a Groovy draft from the following specification for review. Cover relevant "
            + "Arcadia perspectives OA, SA, LA, PA and EPBS. Use native elements, relationships and "
            + "supported diagrams only; EPBS supports configuration items and physical artifact realizations, "
            + "not functions or exchanges. Preserve source requirement identifiers and provenance where supplied. "
            + "Separate design requirements from submission, inspection and competition obligations. "
            + "Do not invent missing source details, silently resolve applicability, or claim compliance from "
            + "imported identifiers. Mark unresolved clauses and verification obligations explicitly. "
            + "Do not execute changes or hide follow-up edits. Return one complete fenced Groovy draft "
            + "and state its scope and unresolved decisions.\n\nSpecification:\n" + specText;
    }
}
