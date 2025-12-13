package org.polarsys.capella.ai.scripting;

import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.model.ModelSummary;

/**
 * Routes LLM responses containing Groovy code to the executor.
 * This is the code execution counterpart to ToolRouter's JSON parsing.
 *
 * <p>The LLM generates Groovy scripts that use the CapellaAPI, which are
 * executed in a sandboxed environment with full Capella access.
 */
public final class CodeExecutionRouter {

    private CodeExecutionRouter() {}

    /**
     * Result of code execution routing.
     */
    public static final class Result {

        public final boolean success;
        public final String output;
        public final String error;
        public final ExecutionResult executionResult;

        private Result(
            boolean success,
            String output,
            String error,
            ExecutionResult executionResult
        ) {
            this.success = success;
            this.output = output;
            this.error = error;
            this.executionResult = executionResult;
        }

        public static Result success(String output, ExecutionResult result) {
            return new Result(true, output, null, result);
        }

        public static Result failure(String error, ExecutionResult result) {
            return new Result(false, null, error, result);
        }

        public static Result noCode(String message) {
            return new Result(false, null, message, null);
        }
    }

    /**
     * Check if the LLM response contains executable Groovy code.
     */
    public static boolean containsCode(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return false;
        }
        // Look for Groovy code blocks
        return (
            llmResponse.contains("```groovy") ||
            (llmResponse.contains("```") && llmResponse.contains("api."))
        );
    }

    /**
     * Execute Groovy code from an LLM response.
     *
     * @param session the Capella session
     * @param llmResponse the full LLM response (may contain markdown, explanations, etc.)
     * @return execution result
     */
    public static Result execute(Session session, String llmResponse) {
        if (session == null) {
            return Result.noCode("No active Capella session");
        }
        if (llmResponse == null || llmResponse.isBlank()) {
            return Result.noCode("Empty response");
        }

        // Extract code from the response
        String code = GroovyExecutor.extractCode(llmResponse);
        if (code.isBlank()) {
            return Result.noCode("No executable code found in response");
        }

        // Execute the code
        ExecutionResult execResult = GroovyExecutor.execute(session, code);

        if (execResult.success()) {
            StringBuilder output = new StringBuilder();
            output.append("Execution completed successfully.\n");

            if (execResult.value() != null) {
                output
                    .append("Result: ")
                    .append(formatValue(execResult.value()))
                    .append("\n");
            }

            if (execResult.hasLogs()) {
                output.append("\nScript output:\n");
                for (String log : execResult.logs()) {
                    output.append("  ").append(log).append("\n");
                }
            }

            output
                .append("\nDuration: ")
                .append(execResult.durationMs())
                .append("ms");

            return Result.success(output.toString(), execResult);
        } else {
            return Result.failure(execResult.formatForLLM(), execResult);
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
        java.util.function.BiFunction<String, String, String> llmCallback
    ) {
        Result result = execute(session, llmResponse);

        int attempts = 0;
        while (
            !result.success &&
            attempts < maxRetries &&
            result.executionResult != null
        ) {
            // Get error feedback for LLM
            String errorFeedback = result.executionResult.formatForLLM();

            // Ask LLM to fix the code
            String correctedResponse = llmCallback.apply(
                llmResponse,
                errorFeedback
            );
            if (
                correctedResponse == null ||
                correctedResponse.equals(llmResponse)
            ) {
                break; // LLM couldn't fix it
            }

            // Try again with corrected code
            result = execute(session, correctedResponse);
            llmResponse = correctedResponse;
            attempts++;
        }

        return result;
    }

    /**
     * Build the system prompt section for code execution mode.
     * Uses minimal prompt with filesystem-based discovery (Anthropic pattern).
     *
     * <p>Reference: <a href="https://www.anthropic.com/engineering/code-execution-with-mcp">
     * Anthropic's Code Execution with MCP</a>
     *
     * <p>Key insight: Instead of including ~100 lines of API documentation in the prompt,
     * we teach the LLM to discover capabilities on-demand via api.fs.* methods.
     * This achieves 98% token reduction while enabling unlimited capability.
     */
    public static String buildCodeExecutionPrompt(Session session) {
        StringBuilder sb = new StringBuilder();

        // Minimal system prompt (~20 lines instead of ~100)
        sb.append("## Code Execution\n\n");
        sb.append(
            "You can execute Groovy code to manipulate Capella models.\n\n"
        );

        sb.append("### Available Bindings\n\n");
        sb.append(
            "- `api` - Capella API (find, create, link, diagram, etc.)\n"
        );
        sb.append(
            "- `api.fs` - Documentation discovery (list, read, search)\n"
        );
        sb.append("- `api.diagram` - Diagram operations\n");
        sb.append("- `log` - Logger (log.info, log.warn, log.error)\n\n");

        sb.append("### Discovering Capabilities\n\n");
        sb.append("Use `api.fs.*` to explore available APIs on-demand:\n\n");
        sb.append("```groovy\n");
        sb.append("// List available documentation\n");
        sb.append(
            "api.fs.list(\"\")                    // Root: [\"api/\", \"metamodel/\", \"skills/\", \"SKILL.md\"]\n"
        );
        sb.append("api.fs.list(\"api/\")               // API methods\n");
        sb.append(
            "api.fs.list(\"skills/\")            // Reusable patterns\n\n"
        );
        sb.append("// Read specific documentation\n");
        sb.append(
            "api.fs.read(\"SKILL.md\")           // Root documentation\n"
        );
        sb.append(
            "api.fs.read(\"api/create.md\")      // How to create elements\n"
        );
        sb.append(
            "api.fs.read(\"skills/state-machine/SKILL.md\")  // State machine pattern\n\n"
        );
        sb.append("// Search for topics\n");
        sb.append(
            "api.fs.search(\"component\")        // Find docs about components\n"
        );
        sb.append("```\n\n");

        sb.append("### Quick Start\n\n");
        sb.append("```groovy\n");
        sb.append("// Get architecture layer root\n");
        sb.append("def saLayer = api.layer(\"SA\")  // OA, SA, LA, PA\n\n");
        sb.append("// Find an element\n");
        sb.append(
            "def func = api.find(\"SystemFunction\", \"Process Data\")\n\n"
        );
        sb.append("// Create an element\n");
        sb.append(
            "def newFunc = api.create(\"SA\", \"SystemFunction\", \"New Function\")\n\n"
        );
        sb.append("// Link elements (exchanges, realizations)\n");
        sb.append("api.link(func, newFunc, \"FunctionalExchange\")\n");
        sb.append("api.link(laFunc, saFunc, \"FunctionRealization\")\n");
        sb.append("api.link(laComp, saComp, \"ComponentRealization\")\n\n");
        sb.append(
            "// Allocate function to component (use api.allocate, NOT api.link)\n"
        );
        sb.append("api.allocate(func, comp)\n\n");
        sb.append("// Create diagram: create(type, name, target)\n");
        sb.append(
            "def diagram = api.diagram.create(\"SAB\", \"My Diagram\", saLayer)\n\n"
        );
        sb.append("// Always log results\n");
        sb.append("log.info(\"Created: ${api.name(newFunc)}\")\n");
        sb.append("```\n\n");

        sb.append(
            "**For complex tasks**: First explore the documentation with `api.fs.read(\"SKILL.md\")` "
        );
        sb.append("to discover the full API, then write your code.\n\n");

        // Raw API access for dynamic capabilities
        sb.append("### Raw API Access (api.tx)\n\n");
        sb.append(
            "When standard API methods don't support your use case, use `api.tx {}` to access Capella internals:\n\n"
        );
        sb.append("```groovy\n");
        sb.append("api.tx {\n");
        sb.append(
            "    // Load factory via api.class.classLoader (required for OSGi)\n"
        );
        sb.append("    def factory = api.class.classLoader.loadClass(\n");
        sb.append(
            "        \"org.polarsys.capella.core.data.capellacore.CapellacoreFactory\"\n"
        );
        sb.append("    ).eINSTANCE\n");
        sb.append("    \n");
        sb.append("    // Create element and add to parent\n");
        sb.append(
            "    def func = api.find(\"SystemFunction\", \"My Function\")\n"
        );
        sb.append("    def constraint = factory.createConstraint()\n");
        sb.append("    constraint.name = \"My Constraint\"\n");
        sb.append("    func.ownedConstraints.add(constraint)\n");
        sb.append("}\n");
        sb.append("```\n\n");
        sb.append(
            "**Factories**: CapellacoreFactory (Constraints), OaFactory, CtxFactory, LaFactory, PaFactory, FaFactory, CsFactory, InformationFactory\n\n"
        );

        // Diagram creation pattern with examples (Anthropic multishot pattern)
        sb.append("### Diagram Creation\n\n");
        sb.append(
            "To create diagrams, use this EXACT signature: `create(type, name, target)`\n\n"
        );
        sb.append("<example>\n");
        sb.append("User request: Create a System Architecture Blank diagram\n");
        sb.append("Correct code:\n");
        sb.append("```groovy\n");
        sb.append("def saLayer = api.layer(\"SA\")\n");
        sb.append(
            "def diagram = api.diagram.create(\"SAB\", \"System Overview\", saLayer)\n"
        );
        sb.append("log.info(\"Created diagram: \" + diagram.name)\n");
        sb.append("```\n");
        sb.append("</example>\n\n");
        sb.append("<example>\n");
        sb.append(
            "User request: Create an LAB diagram called My Logical View\n"
        );
        sb.append("Correct code:\n");
        sb.append("```groovy\n");
        sb.append("def laLayer = api.layer(\"LA\")\n");
        sb.append(
            "def diagram = api.diagram.create(\"LAB\", \"My Logical View\", laLayer)\n"
        );
        sb.append("log.info(\"Created diagram: \" + diagram.name)\n");
        sb.append("```\n");
        sb.append("</example>\n\n");

        // Requirements Viewpoint pattern with examples (Anthropic multishot pattern)
        sb.append("### Requirements Viewpoint\n\n");
        sb.append(
            "To create Requirements, use this EXACT pattern (do not modify):\n\n"
        );
        sb.append("<example>\n");
        sb.append("User request: Create a requirement\n");
        sb.append("Correct code:\n");
        sb.append("```groovy\n");
        sb.append("import org.osgi.framework.FrameworkUtil\n");
        sb.append(
            "def ctx = FrameworkUtil.getBundle(api.class).bundleContext\n"
        );
        sb.append(
            "def reqBundle = ctx.bundles.find { it.symbolicName == \"org.polarsys.kitalpha.vp.requirements.model\" }\n"
        );
        sb.append(
            "def factory = reqBundle.loadClass(\"org.polarsys.kitalpha.vp.requirements.Requirements.RequirementsFactory\").eINSTANCE\n"
        );
        sb.append("def req = factory.createRequirement()\n");
        sb.append("req.reqIFLongName = \"My Requirement\"\n");
        sb.append("log.info(\"Created: \" + req.reqIFLongName)\n");
        sb.append("req\n");
        sb.append("```\n");
        sb.append("</example>\n\n");
        sb.append("**CRITICAL rules for Requirements**:\n");
        sb.append(
            "- Use `FrameworkUtil.getBundle(api.class)` - NOT `this.class`\n"
        );
        sb.append("- Use `req.reqIFLongName` - NOT `req.name`\n");
        sb.append(
            "- Bundle name is exactly `org.polarsys.kitalpha.vp.requirements.model`\n\n"
        );

        // Add current model context
        if (session != null) {
            sb.append("### Current Model State\n\n");
            sb.append("```\n");
            sb.append(ModelSummary.summarize(session));
            sb.append("```\n");
        }

        return sb.toString();
    }

    /**
     * Build the legacy detailed prompt (kept for backwards compatibility).
     * @deprecated Use buildCodeExecutionPrompt() with filesystem discovery instead.
     */
    @Deprecated
    public static String buildDetailedCodeExecutionPrompt(Session session) {
        StringBuilder sb = new StringBuilder();

        sb.append("## Code Execution Mode\n\n");
        sb.append(
            "You can execute Groovy scripts to modify the Capella model. "
        );
        sb.append("Write your code in a ```groovy code block.\n\n");

        sb.append("### Available API\n\n");
        sb.append("```groovy\n");
        sb.append("// === FINDING ELEMENTS ===\n");
        sb.append(
            "api.find(\"ElementName\")                      // Find by name (any type)\n"
        );
        sb.append(
            "api.find(\"SystemFunction\", \"Process Data\")   // Find by type and name\n"
        );
        sb.append(
            "api.findAll(\"LogicalComponent\")               // All elements of type\n"
        );
        sb.append(
            "api.query { it.eClass().name.contains(\"Func\") } // Custom predicate\n"
        );
        sb.append(
            "api.layer(\"SA\")                               // Get architecture root\n\n"
        );

        sb.append("// === CREATING ELEMENTS ===\n");
        sb.append(
            "api.create(\"SystemFunction\", \"My Function\")   // Auto-detect layer\n"
        );
        sb.append(
            "api.create(\"SA\", \"SystemFunction\", \"Name\")    // In specific layer\n"
        );
        sb.append(
            "api.create(\"SystemComponent\", \"Name\", parent) // Under specific parent\n\n"
        );

        sb.append("// === MODIFYING ELEMENTS ===\n");
        sb.append(
            "api.set(element, \"description\", \"New desc\")   // Set any property\n"
        );
        sb.append(
            "api.rename(element, \"New Name\")                // Rename element\n"
        );
        sb.append(
            "api.delete(element)                            // Delete element\n\n"
        );

        sb.append("// === CREATING RELATIONSHIPS ===\n");
        sb.append(
            "api.link(func1, func2, \"FunctionalExchange\")  // Create exchange\n"
        );
        sb.append(
            "api.link(comp1, comp2, \"ComponentExchange\")   // Create interface\n"
        );
        sb.append(
            "api.link(func, comp, \"allocation\")            // Allocate function\n\n"
        );

        sb.append("// === NAVIGATION ===\n");
        sb.append(
            "api.parent(element)                            // Get parent\n"
        );
        sb.append(
            "api.children(element)                          // Get children\n"
        );
        sb.append(
            "api.descendants(element)                       // All descendants\n"
        );
        sb.append(
            "api.type(element)                              // Get type name\n"
        );
        sb.append(
            "api.name(element)                              // Get name\n\n"
        );

        sb.append("// === DIAGRAMS ===\n");
        sb.append(
            "def diagram = api.diagram.create(\"SAB\", \"Overview\", root)\n"
        );
        sb.append(
            "api.diagram.add(diagram, element)              // Add element to diagram\n"
        );
        sb.append(
            "api.diagram.layout(diagram, \"flow\")            // Layout: auto/grid/flow/radial\n"
        );
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

    /**
     * Format a value for display.
     */
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
                Object name = eo.eGet(
                    eo.eClass().getEStructuralFeature("name")
                );
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
