package org.polarsys.capella.ai.importer;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.llm.LLMService;
import org.polarsys.capella.ai.model.ModelSummary;
import org.polarsys.capella.ai.scripting.CodeExecutionRouter;
import org.polarsys.capella.ai.scripting.ExecutionResult;
import org.polarsys.capella.ai.scripting.GroovyExecutor;

/**
 * Spec-driven execution: import natural language requirements/spec text
 * and generate Groovy code to create the model.
 *
 * <p>Updated for Phase 4 code execution architecture.
 */
public final class SpecImporter {

    private SpecImporter() {}

    /**
     * Result of spec import operation.
     */
    public static final class ImportResult {

        public final boolean success;
        public final String generatedCode;
        public final ExecutionResult executionResult;
        public final String error;

        private ImportResult(
            boolean success,
            String generatedCode,
            ExecutionResult executionResult,
            String error
        ) {
            this.success = success;
            this.generatedCode = generatedCode;
            this.executionResult = executionResult;
            this.error = error;
        }

        public static ImportResult success(
            String code,
            ExecutionResult result
        ) {
            return new ImportResult(true, code, result, null);
        }

        public static ImportResult failure(String error) {
            return new ImportResult(false, null, null, error);
        }
    }

    /**
     * Generate and execute code from a specification.
     *
     * @param session the Capella session
     * @param specText the specification text
     * @return import result with generated code and execution status
     */
    public static ImportResult importSpec(Session session, String specText) {
        if (session == null) {
            return ImportResult.failure("No active session");
        }
        if (specText == null || specText.isBlank()) {
            return ImportResult.failure("Empty specification");
        }

        // Build prompt for code generation
        List<LLMService.Message> messages = new ArrayList<>();
        messages.add(LLMService.Message.system(buildSystemPrompt(session)));
        messages.add(
            LLMService.Message.user(buildUserPrompt(specText, session))
        );

        try {
            // Get code from LLM
            String reply = LLMService.get().send(messages);

            // Extract and execute code
            String code = GroovyExecutor.extractCode(reply);
            if (code.isBlank()) {
                return ImportResult.failure(
                    "LLM did not generate executable code"
                );
            }

            ExecutionResult result = GroovyExecutor.execute(session, code);

            if (result.success()) {
                return ImportResult.success(code, result);
            } else {
                return ImportResult.failure(result.formatForLLM());
            }
        } catch (Exception e) {
            return ImportResult.failure("Error: " + e.getMessage());
        }
    }

    /**
     * Generate code from specification without executing.
     * Useful for preview/review before execution.
     */
    public static String generateCode(Session session, String specText) {
        if (session == null || specText == null || specText.isBlank()) {
            return null;
        }

        List<LLMService.Message> messages = new ArrayList<>();
        messages.add(LLMService.Message.system(buildSystemPrompt(session)));
        messages.add(
            LLMService.Message.user(buildUserPrompt(specText, session))
        );

        try {
            String reply = LLMService.get().send(messages);
            return GroovyExecutor.extractCode(reply);
        } catch (Exception e) {
            return null;
        }
    }

    private static String buildSystemPrompt(Session session) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a Capella MBSE modeling assistant.\n\n");
        sb.append(CodeExecutionRouter.buildCodeExecutionPrompt(session));
        sb.append(
            "\n\nIMPORTANT: Always respond with a complete Groovy script that creates the model.\n"
        );
        sb.append("The script should be self-contained and executable.\n");
        return sb.toString();
    }

    private static String buildUserPrompt(String specText, Session session) {
        StringBuilder sb = new StringBuilder();
        sb.append(
            "From the following specification, create a complete ARCADIA model "
        );
        sb.append(
            "covering all relevant layers (OA, SA, LA, PA as appropriate).\n\n"
        );
        sb.append("Generate a Groovy script that:\n");
        sb.append("1. Creates the appropriate elements in each layer\n");
        sb.append("2. Creates relationships between elements\n");
        sb.append("3. Creates at least one overview diagram\n");
        sb.append("4. Logs what was created\n\n");
        sb.append("Specification:\n");
        sb.append("```\n").append(specText).append("\n```\n\n");
        sb.append("Current model state:\n");
        sb
            .append("```\n")
            .append(ModelSummary.summarize(session))
            .append("\n```\n");
        return sb.toString();
    }
}
