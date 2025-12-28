package org.polarsys.capella.ai.scripting;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.llm.LLMService;
import org.polarsys.capella.ai.model.ModelSummary;
import org.polarsys.capella.ai.preferences.LLMPreferences;
import org.polarsys.capella.ai.privacy.Tokenizer;
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
    private static final int MAX_EXECUTION_RETRIES = 1;

    /**
     * Result of a chat interaction.
     */
    public static final class Result {

        /** The LLM's full response text */
        public final String reply;
        /** Whether code was executed */
        public final boolean codeExecuted;
        /** Number of execution attempts made (0 when no code executed). */
        public final int attempts;
        /** The execution result (if code was executed) */
        public final ExecutionResult executionResult;
        /** Error message (if any) */
        public final String error;

        private Result(
            String reply,
            boolean codeExecuted,
            int attempts,
            ExecutionResult executionResult,
            String error
        ) {
            this.reply = reply;
            this.codeExecuted = codeExecuted;
            this.attempts = attempts;
            this.executionResult = executionResult;
            this.error = error;
        }

        public boolean success() {
            return (
                error == null &&
                (executionResult == null || executionResult.success())
            );
        }

        public boolean hasCode() {
            return codeExecuted;
        }

        public static Result chat(String reply) {
            return new Result(reply, false, 0, null, null);
        }

        public static Result executed(
            String reply,
            ExecutionResult result,
            int attempts
        ) {
            return new Result(
                reply,
                true,
                attempts,
                result,
                result.failed() ? result.message() : null
            );
        }

        public static Result error(String error) {
            return new Result(null, false, 0, null, error);
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
        return handleChat(session, userMessage, List.of(), null);
    }

    /**
     * Handle a chat message with conversation history.
     *
     * @param session the Capella session
     * @param userMessage the user's message
     * @param history previous messages in the conversation
     * @return the result
     */
    public static Result handleChat(
        Session session,
        String userMessage,
        List<LLMService.Message> history
    ) {
        return handleChat(session, userMessage, history, null);
    }

    /**
     * Handle a chat message with conversation history and optional conversation ID.
     */
    public static Result handleChat(
        Session session,
        String userMessage,
        List<LLMService.Message> history,
        String conversationId
    ) {
        String correlationId = UUID.randomUUID().toString();
        Telemetry.setCorrelationId(correlationId);

        try {
            if (session == null) {
                return Result.error("No active Capella session");
            }

            String sessionKey = GroovyExecutor.buildSessionKey(
                session,
                conversationId
            );

            // Build messages with code execution system prompt
            List<LLMService.Message> messages = buildMessages(
                session,
                userMessage,
                history,
                sessionKey
            );

            String tokenizedUserMessage = userMessage;
            Tokenizer tokenizer = null;
            if (LLMPreferences.isPrivacyTokenizationEnabled()) {
                tokenizer = Tokenizer.forSession(sessionKey);
                tokenizedUserMessage = tokenizer.tokenizeText(userMessage);
                messages = tokenizeMessages(tokenizer, messages);
            }

            // Get LLM response
            String reply = LLMService.get().send(messages);
            String userVisibleReply =
                tokenizer != null ? tokenizer.detokenizeText(reply) : reply;

            // Check if response contains executable code
            if (CodeExecutionRouter.containsCode(reply)) {
                String labelHint = buildUndoLabelHint(userMessage);
                // Execute the code (with one correction attempt on error)
                CodeExecutionRouter.Result execResult =
                    CodeExecutionRouter.executeWithRetry(
                        session,
                        reply,
                        MAX_EXECUTION_RETRIES,
                        (previousResponse, errorFeedback) ->
                            requestCorrection(
                                session,
                                userMessage,
                                history,
                                previousResponse,
                                errorFeedback,
                                sessionKey
                            ),
                        labelHint,
                        sessionKey
                    );

                if (execResult.success) {
                    if (tokenizer != null) {
                        Telemetry.recordDatasetEntry(
                            tokenizedUserMessage,
                            execResult.responseUsed
                        );
                    } else {
                        Telemetry.recordDatasetEntry(
                            userMessage,
                            execResult.responseUsed
                        );
                    }
                    return Result.executed(
                        tokenizer != null
                            ? tokenizer.detokenizeText(execResult.responseUsed)
                            : execResult.responseUsed,
                        execResult.executionResult,
                        execResult.attempts
                    );
                } else {
                    // Return error but include the reply for context
                    return new Result(
                        tokenizer != null
                            ? tokenizer.detokenizeText(execResult.responseUsed)
                            : execResult.responseUsed,
                        true,
                        execResult.attempts,
                        execResult.executionResult,
                        execResult.error
                    );
                }
            } else {
                // No code to execute, just a chat response
                return Result.chat(userVisibleReply);
            }
        } catch (Exception e) {
            return Result.error("Error: " + e.getMessage());
        } finally {
            Telemetry.clearCorrelationId();
        }
    }

    private static String buildUndoLabelHint(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return "LLM script";
        }
        String cleaned = userMessage.trim().replaceAll("\\s+", " ");
        int max = 60;
        if (cleaned.length() <= max) {
            return cleaned;
        }
        return cleaned.substring(0, max - 3) + "...";
    }

    /**
     * Build messages for the LLM with code execution system prompt.
     */
    private static List<LLMService.Message> buildMessages(
        Session session,
        String userMessage,
        List<LLMService.Message> history,
        String sessionKey
    ) {
        List<LLMService.Message> messages = new ArrayList<>();

        // System prompt with code execution instructions
        String systemPrompt = buildSystemPrompt(session, sessionKey);
        messages.add(LLMService.Message.system(systemPrompt));

        // Add conversation history (limited to recent messages)
        int historyLimit = 10;
        int startIdx = Math.max(0, history.size() - historyLimit);
        for (int i = startIdx; i < history.size(); i++) {
            messages.add(history.get(i));
        }

        // Add current user message
        messages.add(LLMService.Message.user(userMessage));

        return messages;
    }

    private static String requestCorrection(
        Session session,
        String userMessage,
        List<LLMService.Message> history,
        String previousResponse,
        String errorFeedback,
        String sessionKey
    ) {
        Tokenizer tokenizer = null;
        if (LLMPreferences.isPrivacyTokenizationEnabled()) {
            tokenizer = Tokenizer.forSession(sessionKey);
        }

        List<LLMService.Message> messages = new ArrayList<>();
        String systemPrompt = buildSystemPrompt(session, sessionKey);
        messages.add(LLMService.Message.system(systemPrompt));

        int historyLimit = 10;
        int startIdx = Math.max(0, history.size() - historyLimit);
        for (int i = startIdx; i < history.size(); i++) {
            messages.add(history.get(i));
        }

        messages.add(LLMService.Message.user(userMessage));
        messages.add(LLMService.Message.assistant(previousResponse));
        messages.add(
            LLMService.Message.user(
                "The previous code failed to execute.\n\n" +
                "Error:\n" +
                errorFeedback +
                "\n\n" +
                "Fix the code and return ONLY the corrected Groovy code inside a ```groovy``` block. " +
                "Do not include explanations.\n\n" +
                "Important API constraints:\n" +
                "- Diagram methods are: " + diagramMethodList() + "\n" +
                "- Do NOT use addElement() or show() (they do not exist)\n" +
                "- If add() fails for diagram elements, use addByUuids() with element UUIDs\n" +
                "- Use api.diagram.find(...) to locate diagrams (not api.find with DRepresentationDescriptor)\n"
            )
        );

        if (tokenizer != null) {
            messages = tokenizeMessages(tokenizer, messages);
        }

        try {
            return LLMService.get().send(messages);
        } catch (Exception e) {
            return previousResponse;
        }
    }

    private static List<LLMService.Message> tokenizeMessages(
        Tokenizer tokenizer,
        List<LLMService.Message> messages
    ) {
        if (tokenizer == null || messages == null || messages.isEmpty()) {
            return messages;
        }
        List<LLMService.Message> out = new ArrayList<>(messages.size());
        for (LLMService.Message m : messages) {
            if (m == null) {
                continue;
            }
            switch (m.getRole()) {
                case SYSTEM:
                    out.add(LLMService.Message.system(m.getContent()));
                    break;
                case ASSISTANT:
                    out.add(
                        LLMService.Message.assistant(
                            tokenizer.tokenizeText(m.getContent())
                        )
                    );
                    break;
                case USER:
                default:
                    out.add(
                        LLMService.Message.user(
                            tokenizer.tokenizeText(m.getContent())
                        )
                    );
                    break;
            }
        }
        return out;
    }

    private static String diagramMethodList() {
        return ApiCatalog.methodNames("diagram")
            .stream()
            .sorted(String::compareToIgnoreCase)
            .reduce((a, b) -> a + ", " + b)
            .orElse("create, createSafe, find, add, addSafe, addAll, addByUuids, refresh, layout, list, listAvailable, listAvailableFormatted");
    }

    /**
     * Build the system prompt for code execution mode.
     */
    private static String buildSystemPrompt(Session session, String sessionKey) {
        StringBuilder sb = new StringBuilder();

        sb.append(
            "You are the Capella AI assistant with full model control via code execution.\n\n"
        );

        sb.append("## Your Capabilities\n\n");
        sb.append(
            "You can execute Groovy scripts to create, modify, and query the Capella model.\n"
        );
        sb.append(
            "When the user requests model changes, respond with executable code in a ```groovy block.\n"
        );
        sb.append(
            "For questions or explanations, respond normally without code.\n\n"
        );

        sb.append("## ARCADIA Methodology\n\n");
        sb.append("Follow this order and maintain traceability:\n");
        sb.append(
            "1. OA: Operational Analysis - actors, activities, exchanges, capabilities\n"
        );
        sb.append(
            "2. SA: System Analysis - components, functions, interfaces\n"
        );
        sb.append(
            "3. LA: Logical Architecture - decomposition, exchanges, functional chains\n"
        );
        sb.append(
            "4. PA: Physical Architecture - physical components, map from Logical\n\n"
        );

        if (LLMPreferences.isPrivacyTokenizationEnabled()) {
            sb.append("## Privacy Mode\n\n");
            sb.append(
                "Some sensitive element names and IDs are replaced with tokens like `ELEM_0001` and `UUID_0001`.\n"
            );
            sb.append(
                "Treat these tokens as exact literals: do not paraphrase, rename, or substitute them.\n"
            );
            sb.append(
                "Use the tokens exactly as provided in your generated Groovy; the runtime will resolve tokens back to real values during execution.\n\n"
            );
        }

        // Add API documentation
        sb.append(
            CodeExecutionRouter.buildCodeExecutionPrompt(session, sessionKey)
        );

        sb.append("\n## Guidelines\n\n");
        sb.append(
            "- Always use `log.info()` to report what was created/modified\n"
        );
        sb.append("- Check if elements exist before creating duplicates\n");
        sb.append("- Use descriptive names following Capella conventions\n");
        sb.append(
            "- For large changes, break into logical steps with logging\n"
        );
        sb.append(
            "- If an error occurs, explain the issue and suggest a fix\n"
        );

        return sb.toString();
    }

    /**
     * Execute code directly (for programmatic use).
     *
     * @param session the Capella session
     * @param groovyCode the Groovy code to execute
     * @return the execution result
     */
    public static ExecutionResult executeCode(
        Session session,
        String groovyCode
    ) {
        if (session == null) {
            return ExecutionResult.fail(
                ExecutionResult.ErrorCode.API_ERROR,
                "No active Capella session"
            );
        }
        return GroovyExecutor.execute(session, groovyCode);
    }

    /**
     * Get the system prompt for external tools.
     */
    public static String getSystemPrompt(Session session) {
        return buildSystemPrompt(session, null);
    }
}
