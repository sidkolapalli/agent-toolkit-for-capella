package org.polarsys.capella.ai.scripting;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.llm.LLMService;
import org.polarsys.capella.ai.model.ModelSummary;
import org.polarsys.capella.ai.telemetry.Telemetry;

/**
 * Routes LLM requests through code execution.
 * Replaces the JSON-based TOOL_PLAN approach with direct Groovy script execution.
 *
 * <p>This is the main entry point for the Code Execution Architecture.
 * The LLM generates Groovy scripts using the CapellaAPI, which are executed
 * in a sandboxed environment with full Capella model access.
 */
public final class ScriptRouter {

    private ScriptRouter() {}

    /**
     * Result of a chat interaction.
     */
    public static final class Result {
        /** The LLM's full response text */
        public final String reply;
        /** Whether code was executed */
        public final boolean codeExecuted;
        /** The execution result (if code was executed) */
        public final ExecutionResult executionResult;
        /** Error message (if any) */
        public final String error;

        private Result(String reply, boolean codeExecuted, ExecutionResult executionResult, String error) {
            this.reply = reply;
            this.codeExecuted = codeExecuted;
            this.executionResult = executionResult;
            this.error = error;
        }

        public boolean success() {
            return error == null && (executionResult == null || executionResult.success());
        }

        public boolean hasCode() {
            return codeExecuted;
        }

        public static Result chat(String reply) {
            return new Result(reply, false, null, null);
        }

        public static Result executed(String reply, ExecutionResult result) {
            return new Result(reply, true, result, result.failed() ? result.message() : null);
        }

        public static Result error(String error) {
            return new Result(null, false, null, error);
        }
    }

    /**
     * Handle a chat message, potentially executing Groovy code.
     *
     * @param session the Capella session
     * @param userMessage the user's message
     * @return the result
     */
    public static Result handleChat(Session session, String userMessage) {
        return handleChat(session, userMessage, List.of());
    }

    /**
     * Handle a chat message with conversation history.
     *
     * @param session the Capella session
     * @param userMessage the user's message
     * @param history previous messages in the conversation
     * @return the result
     */
    public static Result handleChat(Session session, String userMessage, List<LLMService.Message> history) {
        String correlationId = UUID.randomUUID().toString();
        Telemetry.setCorrelationId(correlationId);

        try {
            if (session == null) {
                return Result.error("No active Capella session");
            }

            // Build messages with code execution system prompt
            List<LLMService.Message> messages = buildMessages(session, userMessage, history);

            // Get LLM response
            String reply = LLMService.get().send(messages);

            // Check if response contains executable code
            if (CodeExecutionRouter.containsCode(reply)) {
                // Execute the code
                CodeExecutionRouter.Result execResult = CodeExecutionRouter.execute(session, reply);

                if (execResult.success) {
                    return Result.executed(reply, execResult.executionResult);
                } else {
                    // Return error but include the reply for context
                    return new Result(reply, true, execResult.executionResult, execResult.error);
                }
            } else {
                // No code to execute, just a chat response
                return Result.chat(reply);
            }

        } catch (Exception e) {
            return Result.error("Error: " + e.getMessage());
        } finally {
            Telemetry.clearCorrelationId();
        }
    }

    /**
     * Build messages for the LLM with code execution system prompt.
     */
    private static List<LLMService.Message> buildMessages(Session session, String userMessage,
                                                          List<LLMService.Message> history) {
        List<LLMService.Message> messages = new ArrayList<>();

        // System prompt with code execution instructions
        String systemPrompt = buildSystemPrompt(session);
        messages.add(new LLMService.Message("system", systemPrompt));

        // Add conversation history (limited to recent messages)
        int historyLimit = 10;
        int startIdx = Math.max(0, history.size() - historyLimit);
        for (int i = startIdx; i < history.size(); i++) {
            messages.add(history.get(i));
        }

        // Add current user message
        messages.add(new LLMService.Message("user", userMessage));

        return messages;
    }

    /**
     * Build the system prompt for code execution mode.
     */
    private static String buildSystemPrompt(Session session) {
        StringBuilder sb = new StringBuilder();

        sb.append("You are the Capella AI assistant with full model control via code execution.\n\n");

        sb.append("## Your Capabilities\n\n");
        sb.append("You can execute Groovy scripts to create, modify, and query the Capella model.\n");
        sb.append("When the user requests model changes, respond with executable code in a ```groovy block.\n");
        sb.append("For questions or explanations, respond normally without code.\n\n");

        sb.append("## ARCADIA Methodology\n\n");
        sb.append("Follow this order and maintain traceability:\n");
        sb.append("1. OA: Operational Analysis - actors, activities, exchanges, capabilities\n");
        sb.append("2. SA: System Analysis - components, functions, interfaces\n");
        sb.append("3. LA: Logical Architecture - decomposition, exchanges, functional chains\n");
        sb.append("4. PA: Physical Architecture - physical components, map from Logical\n\n");

        // Add API documentation
        sb.append(CodeExecutionRouter.buildCodeExecutionPrompt(session));

        sb.append("\n## Guidelines\n\n");
        sb.append("- Always use `log.info()` to report what was created/modified\n");
        sb.append("- Check if elements exist before creating duplicates\n");
        sb.append("- Use descriptive names following Capella conventions\n");
        sb.append("- For large changes, break into logical steps with logging\n");
        sb.append("- If an error occurs, explain the issue and suggest a fix\n");

        return sb.toString();
    }

    /**
     * Execute code directly (for programmatic use).
     *
     * @param session the Capella session
     * @param groovyCode the Groovy code to execute
     * @return the execution result
     */
    public static ExecutionResult executeCode(Session session, String groovyCode) {
        if (session == null) {
            return ExecutionResult.fail(ExecutionResult.ErrorCode.API_ERROR, "No active Capella session");
        }
        return GroovyExecutor.execute(session, groovyCode);
    }

    /**
     * Get the system prompt for external tools.
     */
    public static String getSystemPrompt(Session session) {
        return buildSystemPrompt(session);
    }
}
