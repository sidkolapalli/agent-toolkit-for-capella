package org.polarsys.capella.ai.scripting;

import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.model.ModelSummary;
import org.polarsys.capella.ai.preferences.LLMPreferences;
import org.polarsys.capella.ai.privacy.Tokenizer;
import org.polarsys.capella.ai.session.SelectionContext;
import org.polarsys.capella.ai.session.SelectionSummary;
import org.polarsys.capella.ai.session.SessionBridge;

import java.util.List;

/**
 * Routes LLM responses containing Groovy code to the executor. This is the code execution
 * counterpart to ToolRouter's JSON parsing.
 *
 * <p>The LLM generates Groovy scripts that use the CapellaAPI, which are executed in a sandboxed
 * environment with full Capella access.
 */
public final class CodeExecutionRouter {

    private CodeExecutionRouter() {}

    /** Result of code execution routing. */
    public static final class Result {

        public final boolean success;
        public final String output;
        public final String error;
        public final ExecutionResult executionResult;

        /** The LLM response that was actually executed (after retries, if any). */
        public final String responseUsed;

        /** Number of execution attempts made (1 = initial attempt only). */
        public final int attempts;

        private Result(
                boolean success,
                String output,
                String error,
                ExecutionResult executionResult,
                String responseUsed,
                int attempts) {
            this.success = success;
            this.output = output;
            this.error = error;
            this.executionResult = executionResult;
            this.responseUsed = responseUsed;
            this.attempts = attempts;
        }

        public static Result success(
                String output, ExecutionResult result, String responseUsed, int attempts) {
            return new Result(true, output, null, result, responseUsed, attempts);
        }

        public static Result failure(
                String error, ExecutionResult result, String responseUsed, int attempts) {
            return new Result(false, null, error, result, responseUsed, attempts);
        }

        public static Result noCode(String message, String responseUsed, int attempts) {
            return new Result(false, null, message, null, responseUsed, attempts);
        }
    }

    /** Check if the LLM response contains executable Groovy code. */
    public static boolean containsCode(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return false;
        }
        // Look for Groovy code blocks
        return (llmResponse.contains("```groovy")
                || (llmResponse.contains("```") && llmResponse.contains("api.")));
    }

    /**
     * Execute Groovy code from an LLM response.
     *
     * @param session the Capella session
     * @param llmResponse the full LLM response (may contain markdown, explanations, etc.)
     * @return execution result
     */
    public static Result execute(Session session, String llmResponse) {
        return execute(session, llmResponse, null);
    }

    public static Result execute(Session session, String llmResponse, String sessionId) {
        return executeAttempt(session, llmResponse, null, 1, sessionId, null, false);
    }

    /** Execute an already decoded reply, preserving the exact code the user reviewed. */
    public static Result executeReviewed(
            Session session,
            String reply,
            String labelHint,
            String sessionId,
            java.util.Map<String, Object> extraBindings) {
        return executeAttempt(session, reply, labelHint, 1, sessionId, extraBindings, true);
    }

    private static Result executeAttempt(
            Session session,
            String llmResponse,
            String labelHint,
            int attempts,
            String sessionId,
            java.util.Map<String, Object> extraBindings,
            boolean alreadyDecoded) {
        if (session == null) {
            return Result.noCode("No active Capella session", llmResponse, attempts);
        }
        if (llmResponse == null || llmResponse.isBlank()) {
            return Result.noCode("Empty response", llmResponse, attempts);
        }

        // Extract code from the response
        String code = GroovyExecutor.extractCode(llmResponse);
        if (code.isBlank()) {
            return Result.noCode("No executable code found in response", llmResponse, attempts);
        }

        String validationError = ApiCatalog.validateCode(code);
        if (validationError != null) {
            ExecutionResult execResult =
                    ExecutionResult.fail(ExecutionResult.ErrorCode.INVALID_RESULT, validationError);
            return Result.failure(execResult.formatForLLM(), execResult, llmResponse, attempts);
        }

        // GroovyExecutor owns the script transaction. Reviewed source must keep
        // its original scope and field initializers as well as its exact text.
        String wrapped = alreadyDecoded ? code : wrapInBatchIfNeeded(code, labelHint);

        // Execute the code
        ExecutionResult execResult =
                alreadyDecoded
                        ? GroovyExecutor.executeDecoded(session, wrapped, extraBindings, sessionId)
                        : GroovyExecutor.execute(session, wrapped, extraBindings, sessionId);

        if (execResult.success()) {
            StringBuilder output = new StringBuilder();
            output.append("Execution completed successfully.\n");

            if (execResult.value() != null) {
                output.append("Result: ").append(formatValue(execResult.value())).append("\n");
            }

            if (execResult.hasLogs()) {
                output.append("\nScript output:\n");
                for (String log : execResult.logs()) {
                    output.append("  ").append(log).append("\n");
                }
            }

            output.append("\nDuration: ").append(execResult.durationMs()).append("ms");

            return Result.success(output.toString(), execResult, llmResponse, attempts);
        } else {
            return Result.failure(execResult.formatForLLM(), execResult, llmResponse, attempts);
        }
    }

    /**
     * Execute with error retry - feeds errors back to LLM for self-correction.
     *
     * @param session the Capella session
     * @param llmResponse the LLM response with code
     * @param maxRetries maximum retry attempts
     * @param llmCallback callback to get corrected code from LLM
     * @return final execution result
     */
    public static Result executeWithRetry(
            Session session,
            String llmResponse,
            int maxRetries,
            java.util.function.BiFunction<String, String, String> llmCallback) {
        return executeWithRetry(session, llmResponse, maxRetries, llmCallback, null, null);
    }

    public static Result executeWithRetry(
            Session session,
            String llmResponse,
            int maxRetries,
            java.util.function.BiFunction<String, String, String> llmCallback,
            String labelHint) {
        return executeWithRetry(session, llmResponse, maxRetries, llmCallback, labelHint, null);
    }

    public static Result executeWithRetry(
            Session session,
            String llmResponse,
            int maxRetries,
            java.util.function.BiFunction<String, String, String> llmCallback,
            String labelHint,
            String sessionId) {
        return executeWithRetry(
                session, llmResponse, maxRetries, llmCallback, labelHint, sessionId, null);
    }

    public static Result executeWithRetry(
            Session session,
            String llmResponse,
            int maxRetries,
            java.util.function.BiFunction<String, String, String> llmCallback,
            String labelHint,
            String sessionId,
            java.util.Map<String, Object> extraBindings) {
        String currentResponse = llmResponse;
        int attemptCount = 1;
        Result result =
                executeAttempt(
                        session,
                        currentResponse,
                        labelHint,
                        attemptCount,
                        sessionId,
                        extraBindings,
                        false);

        int retriesUsed = 0;
        while (!result.success
                && retriesUsed < maxRetries
                && result.executionResult != null
                && result.executionResult.code()
                        != ExecutionResult.ErrorCode.SAVE_FAILED_AFTER_COMMIT
                && result.executionResult.code() != ExecutionResult.ErrorCode.TIMEOUT
                && llmCallback != null) {
            // Get error feedback for LLM
            String errorFeedback = result.executionResult.formatForLLM();

            // Ask LLM to fix the code
            String correctedResponse = llmCallback.apply(currentResponse, errorFeedback);
            if (correctedResponse == null || correctedResponse.equals(currentResponse)) {
                break; // LLM couldn't fix it
            }

            // Try again with corrected code
            retriesUsed++;
            attemptCount++;
            currentResponse = correctedResponse;
            result =
                    executeAttempt(
                            session,
                            currentResponse,
                            labelHint,
                            attemptCount,
                            sessionId,
                            extraBindings,
                            false);
        }

        return result;
    }

    public static String wrapInBatchIfNeeded(String code, String labelHint) {
        if (code == null || code.isBlank()) {
            return code;
        }

        // If the script already batches itself, don't interfere.
        if (code.matches("(?s).*\\bapi\\s*\\.\\s*batch\\s*\\(.*")
                || code.matches("(?s).*\\bapi\\s*\\.\\s*tx\\s*\\(\\s*['\"].*")) {
            return code;
        }

        String label = normalizeLabel(labelHint);
        String escapedLabel = escapeGroovyString(label);

        StringBuilder prefix = new StringBuilder();
        StringBuilder body = new StringBuilder();
        splitImports(code, prefix, body);

        StringBuilder out = new StringBuilder(code.length() + 128);
        out.append(prefix);
        if (prefix.length() > 0 && !prefix.toString().endsWith("\n")) {
            out.append("\n");
        }
        out.append("api.batch(\"").append(escapedLabel).append("\") {\n");
        out.append(body);
        if (body.length() > 0 && !body.toString().endsWith("\n")) {
            out.append("\n");
        }
        out.append("}\n");
        return out.toString();
    }

    private static void splitImports(String code, StringBuilder prefix, StringBuilder body) {
        boolean inPrefix = true;
        for (String line : code.split("\\R", -1)) {
            String trimmed = line.trim();
            boolean isPrefixLine =
                    trimmed.isEmpty()
                            || trimmed.startsWith("//")
                            || trimmed.startsWith("/*")
                            || trimmed.startsWith("*")
                            || trimmed.startsWith("*/")
                            || trimmed.startsWith("import ");

            if (inPrefix && isPrefixLine) {
                prefix.append(line).append("\n");
            } else {
                inPrefix = false;
                body.append(line).append("\n");
            }
        }
    }

    private static String normalizeLabel(String labelHint) {
        String base = labelHint != null ? labelHint.trim() : "";
        if (base.isBlank()) {
            return "LLM script";
        }
        // Keep undo labels short and stable.
        String cleaned = base.replaceAll("\\s+", " ");
        int max = 60;
        if (cleaned.length() <= max) {
            return cleaned;
        }
        return cleaned.substring(0, max - 3) + "...";
    }

    private static String escapeGroovyString(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Build the system prompt section for code execution mode. Uses minimal prompt with
     * filesystem-based discovery (Anthropic pattern).
     *
     * <p>Reference: <a href="https://www.anthropic.com/engineering/code-execution-with-mcp">
     * Anthropic's Code Execution with MCP</a>
     *
     * <p>Key insight: Instead of including ~100 lines of API documentation in the prompt, we teach
     * the LLM to discover capabilities on-demand via api.fs.* methods. This achieves 98% token
     * reduction while enabling unlimited capability.
     */
    public static String buildCodeExecutionPrompt(Session session) {
        return buildCodeExecutionPrompt(session, null);
    }

    /**
     * Build the system prompt for code execution mode, grounding it with the current selection and
     * active diagram (captured on-demand from the UI).
     *
     * <p>When privacy tokenization is enabled, the dynamic context block is tokenized, but the rest
     * of the prompt remains stable across sessions.
     */
    public static String buildCodeExecutionPrompt(Session session, String sessionId) {
        return buildCodeExecutionPrompt(session, sessionId, SessionBridge.getSelectionContext());
    }

    /**
     * Build the system prompt for code execution mode, using a provided UI context snapshot
     * (preferred for deterministic retries).
     *
     * @param session the active Capella session
     * @param sessionId optional session id (used for privacy tokenization)
     * @param selectionContext immutable UI snapshot (selection + active diagram)
     */
    public static String buildCodeExecutionPrompt(
            Session session, String sessionId, SelectionContext selectionContext) {
        StringBuilder sb = new StringBuilder();

        // Minimal system prompt (~20 lines instead of ~100)
        sb.append("## Code Execution\n\n");
        sb.append("You can execute Groovy code to manipulate Capella models.\n\n");

        sb.append("### Available Bindings\n\n");
        sb.append("- `api` - Capella API (find, create, link, diagram, etc.)\n");
        sb.append(
                "- `api.fs` - Documentation discovery + safe workspace I/O (list, read, search,"
                    + " write)\n");
        sb.append("- `api.skills` - Reusable skill runtime (list, metadata, run)\n");
        sb.append("- `api.diagram` - Diagram operations\n");
        sb.append("- `api.ops` - Engineering operations (transitions, reuse)\n");
        sb.append("- `api.help(\"diagram\")` - JSON method catalog for a module\n");
        sb.append("- `selection` - List<EObject> of currently selected semantic elements\n");
        sb.append(
                "- `activeDiagram` - DRepresentation for the active diagram editor (may be"
                    + " null)\n");
        sb.append("- `log` - Logger (log.info, log.warn, log.error)\n\n");

        sb.append("### Operations\n\n");
        sb.append(
                "Use `api.ops.transition(selection, \"SA2LA\")` for system-to-logical"
                    + " transitions.\n\n");

        java.util.List<GroovyExecutor.VariableSummary> vars =
                GroovyExecutor.describeSessionVariables(sessionId);
        if (!vars.isEmpty()) {
            sb.append("## Current Session State\n\n");
            Tokenizer tokenizer =
                    LLMPreferences.isPrivacyTokenizationEnabled()
                                    && sessionId != null
                                    && !sessionId.isBlank()
                            ? Tokenizer.forSession(sessionId)
                            : null;
            for (GroovyExecutor.VariableSummary summary : vars) {
                String safeSummary =
                        tokenizer != null
                                ? tokenizer.tokenizeText(summary.summary)
                                : summary.summary;
                sb.append("- ").append(summary.name).append(": ").append(safeSummary).append("\n");
            }
            sb.append("\n");
        }

        Tokenizer tokenizer =
                LLMPreferences.isPrivacyTokenizationEnabled()
                                && sessionId != null
                                && !sessionId.isBlank()
                        ? Tokenizer.forSession(sessionId)
                        : null;

        String ctxBlock = SelectionSummary.summarize(selectionContext, tokenizer);
        if (ctxBlock != null && !ctxBlock.isBlank()) {
            sb.append(ctxBlock).append("\n");
        }

        // Skill metadata (lightweight discovery)
        FileSystemAPI fs = new FileSystemAPI();
        List<FileSystemAPI.SkillMetadata> skills = fs.listSkillMetadata();
        if (!skills.isEmpty()) {
            sb.append("### Available Skills (metadata)\n\n");
            for (FileSystemAPI.SkillMetadata skill : skills) {
                sb.append("- ")
                        .append(skill.name)
                        .append(": ")
                        .append(skill.summary != null ? skill.summary : "")
                        .append("\n");
                if (skill.whenToUse != null && !skill.whenToUse.isBlank()) {
                    sb.append("  - When: ").append(skill.whenToUse).append("\n");
                }
                if (!skill.keywords.isEmpty()) {
                    sb.append("  - Keywords: ")
                            .append(String.join(", ", skill.keywords))
                            .append("\n");
                }
                sb.append("  - Load: `api.fs.read(\"").append(skill.path).append("\")`\n");
            }
            sb.append("\n");
        }

        sb.append("### Discovering Capabilities\n\n");
        sb.append("Use `api.fs.*` to explore available APIs on-demand:\n\n");
        sb.append("```groovy\n");
        sb.append("// List available documentation\n");
        sb.append(
                "api.fs.list(\"\")                    // Root: [\"api/\", \"metamodel/\","
                    + " \"skills/\", \"workspace/\", \"SKILL.md\"]\n");
        sb.append("api.fs.list(\"api/\")               // API methods\n");
        sb.append("api.fs.list(\"skills/\")            // Reusable patterns\n\n");
        sb.append("// Persist small state between steps (safe workspace)\n");
        sb.append("api.fs.write(\"state/run-1.json\", \"{\\\"ok\\\":true}\")\n");
        sb.append("api.fs.read(\"workspace/state/run-1.json\")\n\n");
        sb.append("// Read specific documentation\n");
        sb.append("api.fs.read(\"SKILL.md\")           // Root documentation\n");
        sb.append("api.fs.read(\"api/create.md\")      // How to create elements\n");
        sb.append("api.fs.read(\"skills/state-machine/SKILL.md\")  // State machine pattern\n\n");
        sb.append("// Search for topics\n");
        sb.append("api.fs.search(\"component\")        // Find docs about components\n");
        sb.append("```\n\n");

        sb.append("### Quick Start\n\n");
        sb.append("```groovy\n");
        sb.append("// Get architecture layer root\n");
        sb.append("def saLayer = api.layer(\"SA\")  // OA, SA, LA, PA, EPBS\n\n");
        sb.append(
                "// EPBS uses ConfigurationItem breakdowns and PhysicalArtifactRealization links to"
                    + " PA components.\n\n");
        sb.append("// Optional: summarize current model (on-demand)\n");
        sb.append("log.info(api.summary())\n\n");
        sb.append("// Find an element (type + name)\n");
        sb.append("def func = api.find(\"SystemFunction\", \"Process Data\")\n");
        sb.append("log.info(\"Found func: \" + (func != null ? api.name(func) : \"<none>\"))\n\n");
        sb.append("// Create an element\n");
        sb.append("def newFunc = api.create(\"SA\", \"SystemFunction\", \"New Function\")\n");
        sb.append("log.info(\"Created func: \" + api.name(newFunc))\n\n");
        sb.append("// Create a diagram\n");
        sb.append("def diagram = api.diagram.createSafe(\"SAB\", \"My Diagram\", saLayer)\n");
        sb.append("log.info(\"Created diagram: \" + diagram.name)\n");
        sb.append("```\n\n");

        sb.append("### Guidance\n\n");
        sb.append(
                "- Prefer discovery over guessing. Use `api.help(\"diagram\")` before diagram"
                    + " operations.\n");
        sb.append(
                "- Prefer skills for reusable tasks: `api.skills.list()` + `api.skills.run(name,"
                    + " args)`.\n");
        sb.append("- For complex tasks, load documentation first: `api.fs.read(\"SKILL.md\")`.\n");
        sb.append(
                "- Use `api.create(\"LA\", \"LogicalFunction\", \"Name\")` or `api.createIn(\"LA\","
                    + " \"LogicalFunction\", \"Name\")` to create in a layer.\n");
        sb.append(
                "- Do NOT create allocation objects directly; use `api.allocate(function,"
                    + " component)`.\n\n");
        sb.append("- Use `api.describe(element)` to inspect an element before editing it.\n");
        sb.append("- After changes, use `api.validate()` to surface model issues early.\n\n");
        sb.append(
                "- Execution context is stateful; call `api.reset()` to clear remembered"
                    + " variables.\n\n");
        sb.append("- Use worked examples from skill files instead of inventing APIs.\n\n");

        sb.append("### Recipes (Load On Demand)\n\n");
        sb.append("Load the relevant skill file for complete patterns:\n\n");
        sb.append("- Diagrams: `api.fs.read(\"skills/diagram/SKILL.md\")`\n");
        sb.append("- Requirements: `api.fs.read(\"skills/requirements/SKILL.md\")`\n");
        sb.append("- Interfaces: `api.fs.read(\"skills/interface/SKILL.md\")`\n");
        sb.append("- State machines: `api.fs.read(\"skills/state-machine/SKILL.md\")`\n");
        sb.append("- Arcadia workflow: `api.fs.read(\"skills/arcadia-workflow/SKILL.md\")`\n\n");

        sb.append("### Sandbox Constraints\n\n");
        sb.append(
                "If you hit a `SECURITY_VIOLATION`, the sandbox blocked an unsafe operation. Follow"
                    + " the hint in the error.\n");

        return sb.toString();
    }

    /**
     * Build the legacy detailed prompt (kept for backwards compatibility).
     *
     * @deprecated Use buildCodeExecutionPrompt() with filesystem discovery instead.
     */
    @Deprecated
    public static String buildDetailedCodeExecutionPrompt(Session session) {
        StringBuilder sb = new StringBuilder();

        sb.append("## Code Execution Mode\n\n");
        sb.append("You can execute Groovy scripts to modify the Capella model. ");
        sb.append("Write your code in a ```groovy code block.\n\n");

        sb.append("### Available API\n\n");
        sb.append("```groovy\n");
        sb.append("// === FINDING ELEMENTS ===\n");
        sb.append("api.find(\"ElementName\")                      // Find by name (any type)\n");
        sb.append("api.find(\"SystemFunction\", \"Process Data\")   // Find by type and name\n");
        sb.append("api.findAll(\"LogicalComponent\")               // All elements of type\n");
        sb.append("api.query { it.eClass().name.contains(\"Func\") } // Custom predicate\n");
        sb.append("api.layer(\"SA\")                               // Get architecture root\n\n");

        sb.append("// === CREATING ELEMENTS ===\n");
        sb.append("api.create(\"SystemFunction\", \"My Function\")   // Auto-detect layer\n");
        sb.append("api.create(\"SA\", \"SystemFunction\", \"Name\")    // In specific layer\n");
        sb.append("api.create(\"SystemComponent\", \"Name\", parent) // Under specific parent\n\n");

        sb.append("// === MODIFYING ELEMENTS ===\n");
        sb.append("api.set(element, \"description\", \"New desc\")   // Set any property\n");
        sb.append("api.rename(element, \"New Name\")                // Rename element\n");
        sb.append("api.delete(element)                            // Delete element\n\n");

        sb.append("// === CREATING RELATIONSHIPS ===\n");
        sb.append("api.link(func1, func2, \"FunctionalExchange\")  // Create exchange\n");
        sb.append("api.link(comp1, comp2, \"ComponentExchange\")   // Create interface\n");
        sb.append("api.link(func, comp, \"allocation\")            // Allocate function\n\n");

        sb.append("// === OPERATIONS ===\n");
        sb.append(
                "api.ops.transition([component], \"SA2LA\")      // System -> Logical"
                    + " transition\n\n");

        sb.append("// === NAVIGATION ===\n");
        sb.append("api.parent(element)                            // Get parent\n");
        sb.append("api.children(element)                          // Get children\n");
        sb.append("api.descendants(element)                       // All descendants\n");
        sb.append("api.type(element)                              // Get type name\n");
        sb.append("api.name(element)                              // Get name\n\n");

        sb.append("// === DIAGRAMS ===\n");
        sb.append("def diagram = api.diagram.create(\"SAB\", \"Overview\", root)\n");
        sb.append("api.diagram.add(diagram, element)              // Add element to diagram\n");
        sb.append(
                "api.diagram.layout(diagram, \"flow\")            // Layout:"
                    + " auto/grid/flow/radial\n");
        sb.append("```\n\n");

        // Add current model context
        if (session != null) {
            sb.append("### Current Model State\n\n");
            sb.append("```\n");
            sb.append(ModelSummary.summarize(session));
            sb.append("```\n");
        }

        return sb.toString();
    }

    /** Format a value for display. */
    private static String formatValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof java.util.List<?> list) {
            return "List[" + list.size() + " items]";
        }
        if (value instanceof org.eclipse.emf.ecore.EObject eo) {
            try {
                String className = eo.eClass().getName();
                Object name = eo.eGet(eo.eClass().getEStructuralFeature("name"));
                return className + ": " + (name != null ? name : "<unnamed>");
            } catch (Exception e) {
                return eo.getClass().getSimpleName();
            }
        }
        String str = value.toString();
        if (str.length() > 200) {
            return str.substring(0, 200) + "...";
        }
        return str;
    }
}
