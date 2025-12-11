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
     * Teaches the LLM how to generate Groovy scripts.
     */
    public static String buildCodeExecutionPrompt(Session session) {
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

        sb.append(
            "// === DIAGRAMS (Full creation - not possible in Python4Capella!) ===\n"
        );
        sb.append(
            "def diagram = api.diagram.create(\"SAB\", \"Overview\", root)\n"
        );
        sb.append(
            "api.diagram.add(diagram, element)              // Add element to diagram\n"
        );
        sb.append(
            "api.diagram.addAll(diagram, elements)          // Add multiple elements\n"
        );
        sb.append(
            "api.diagram.layout(diagram, \"flow\")            // Layout: auto/grid/flow/radial\n"
        );
        sb.append(
            "api.diagram.find(\"Diagram Name\")               // Find existing diagram\n\n"
        );

        sb.append("// === CROSS-LAYER TRANSFORMATION ===\n");
        sb.append(
            "api.propagate(element, \"LA\")                   // Propagate to Logical\n"
        );
        sb.append(
            "api.propagate(element, \"PA\")                   // Propagate to Physical\n\n"
        );

        sb.append("// === LOGGING ===\n");
        sb.append(
            "log.info(\"Created: \" + element)                // Log output\n"
        );
        sb.append(
            "log.warn(\"Warning message\")                    // Warning\n"
        );
        sb.append(
            "log.error(\"Error message\")                     // Error\n"
        );
        sb.append("```\n\n");

        sb.append("### Example: Complete Workflow\n\n");
        sb.append("```groovy\n");
        sb.append("// Create a system with functions and diagram\n");
        sb.append("def saRoot = api.layer(\"SA\")\n\n");
        sb.append("// Create components\n");
        sb.append(
            "def controller = api.create(\"SA\", \"SystemComponent\", \"Flight Controller\")\n"
        );
        sb.append(
            "def sensors = api.create(\"SA\", \"SystemComponent\", \"Sensor Array\")\n\n"
        );
        sb.append("// Create functions\n");
        sb.append(
            "def processData = api.create(\"SA\", \"SystemFunction\", \"Process Sensor Data\")\n"
        );
        sb.append(
            "def navigate = api.create(\"SA\", \"SystemFunction\", \"Calculate Navigation\")\n\n"
        );
        sb.append("// Create exchange between functions\n");
        sb.append(
            "def exchange = api.link(processData, navigate, \"FunctionalExchange\")\n"
        );
        sb.append("api.set(exchange, \"name\", \"Sensor Data Flow\")\n\n");
        sb.append("// Allocate functions to components\n");
        sb.append("api.link(processData, sensors, \"allocation\")\n");
        sb.append("api.link(navigate, controller, \"allocation\")\n\n");
        sb.append("// Create and populate diagram\n");
        sb.append(
            "def diagram = api.diagram.create(\"SAB\", \"System Overview\", saRoot)\n"
        );
        sb.append("api.diagram.add(diagram, controller)\n");
        sb.append("api.diagram.add(diagram, sensors)\n");
        sb.append("api.diagram.layout(diagram, \"flow\")\n\n");
        sb.append(
            "log.info(\"Created system with \" + api.findAll(\"SystemFunction\").size() + \" functions\")\n"
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
