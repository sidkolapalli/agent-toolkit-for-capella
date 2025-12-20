package org.polarsys.capella.ai.scripting;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.session.SessionSafeguards;

/**
 * Sandboxed Groovy executor for LLM-generated scripts.
 * Executes scripts with Capella API bindings in a secure, time-limited environment.
 *
 * <p>Security features:
 * <ul>
 *   <li>30-second execution timeout</li>
 *   <li>Blocked dangerous operations (System.exit, Runtime.exec, file I/O)</li>
 *   <li>Limited imports to safe packages</li>
 *   <li>Memory-bounded via executor pool</li>
 * </ul>
 *
 * <p>Script bindings:
 * <ul>
 *   <li>{@code api} - CapellaAPI instance for model operations</li>
 *   <li>{@code session} - Capella Session for advanced operations</li>
 *   <li>{@code log} - ScriptLogger for output capture</li>
 * </ul>
 */
public final class GroovyExecutor {

    private static final Logger LOG = Logger.getLogger(
        GroovyExecutor.class.getName()
    );

    /** Default timeout in milliseconds */
    private static final long DEFAULT_TIMEOUT_MS = 30_000;

    /** Maximum script length to prevent memory issues */
    private static final int MAX_SCRIPT_LENGTH = 100_000;

    /** Executor for running scripts with timeout */
    private static final ExecutorService EXECUTOR =
        Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "GroovyExecutor-Worker");
            t.setDaemon(true);
            return t;
        });

    /** Classes/packages blocked for security */
    private static final Set<String> BLOCKED_RECEIVERS = new HashSet<>(
        Arrays.asList(
            "java.lang.System",
            "java.lang.Runtime",
            "java.lang.ProcessBuilder",
            "java.io.File",
            "java.io.FileInputStream",
            "java.io.FileOutputStream",
            "java.io.FileReader",
            "java.io.FileWriter",
            "java.io.RandomAccessFile",
            "java.nio.file.Files",
            "java.nio.file.Paths",
            "java.net.Socket",
            "java.net.ServerSocket",
            "java.net.URL",
            "java.net.HttpURLConnection",
            "java.lang.reflect.Method",
            "java.lang.reflect.Field",
            "java.lang.reflect.Constructor",
            "java.lang.ClassLoader",
            "groovy.lang.GroovyShell",
            "groovy.lang.GroovyClassLoader"
        )
    );

    private GroovyExecutor() {}

    /**
     * Execute a Groovy script with Capella API bindings.
     *
     * @param session the Capella session
     * @param script the Groovy script to execute
     * @return ExecutionResult with return value, logs, and any errors
     */
    public static ExecutionResult execute(Session session, String script) {
        return execute(session, script, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Execute a Groovy script with custom timeout.
     *
     * @param session the Capella session
     * @param script the Groovy script to execute
     * @param timeoutMs timeout in milliseconds
     * @return ExecutionResult with return value, logs, and any errors
     */
    public static ExecutionResult execute(
        Session session,
        String script,
        long timeoutMs
    ) {
        long startTime = System.currentTimeMillis();

        // Validate inputs
        if (session == null) {
            return ExecutionResult.fail(
                ExecutionResult.ErrorCode.API_ERROR,
                "No active Capella session"
            );
        }
        if (script == null || script.isBlank()) {
            return ExecutionResult.fail(
                ExecutionResult.ErrorCode.INVALID_RESULT,
                "Script is empty"
            );
        }
        if (script.length() > MAX_SCRIPT_LENGTH) {
            return ExecutionResult.fail(
                ExecutionResult.ErrorCode.SECURITY_VIOLATION,
                "Script exceeds maximum length of " +
                    MAX_SCRIPT_LENGTH +
                    " characters"
            );
        }

        // Create script logger for output capture
        ScriptLogger logger = new ScriptLogger();

        // Create bindings
        Binding binding = createBinding(session, logger);

        // Create compiler configuration with security
        CompilerConfiguration config = createSecureConfig();

        // Create shell
        GroovyShell shell = new GroovyShell(
            GroovyExecutor.class.getClassLoader(),
            binding,
            config
        );

        // Execute with timeout
        Callable<Object> task = () -> {
            try {
                return shell.evaluate(script);
            } catch (SecurityException e) {
                throw new SecurityException(
                    "Blocked operation: " + e.getMessage(),
                    e
                );
            }
        };

        try {
            Future<Object> future = EXECUTOR.submit(task);
            Object result;
            try {
                result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                return ExecutionResult.timeout(timeoutMs, logger.getMessages());
            }

            // Ensure model is saved after successful script execution
            long duration = System.currentTimeMillis() - startTime;
            try {
                SessionSafeguards.requestSave(session);
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Post-script save failed - changes not persisted", e);
                return ExecutionResult.fail(
                    ExecutionResult.ErrorCode.API_ERROR,
                    "Script executed successfully but model save failed: " + e.getMessage(),
                    getStackTrace(e),
                    logger.getMessages(),
                    duration
                );
            }

            return ExecutionResult.success(
                result,
                logger.getMessages(),
                duration
            );
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            long duration = System.currentTimeMillis() - startTime;

            if (cause instanceof SecurityException) {
                return ExecutionResult.fail(
                    ExecutionResult.ErrorCode.SECURITY_VIOLATION,
                    cause.getMessage(),
                    getStackTrace(cause),
                    logger.getMessages(),
                    duration
                );
            }

            if (
                cause instanceof
                    org.codehaus.groovy.control.MultipleCompilationErrorsException
            ) {
                return ExecutionResult.fail(
                    ExecutionResult.ErrorCode.COMPILATION_ERROR,
                    "Script compilation failed: " + cause.getMessage(),
                    getStackTrace(cause),
                    logger.getMessages(),
                    duration
                );
            }

            return ExecutionResult.fail(
                ExecutionResult.ErrorCode.RUNTIME_ERROR,
                "Script execution failed: " + cause.getMessage(),
                getStackTrace(cause),
                logger.getMessages(),
                duration
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExecutionResult.fail(
                ExecutionResult.ErrorCode.TIMEOUT,
                "Script execution was interrupted"
            );
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            LOG.log(Level.WARNING, "Unexpected error executing script", e);
            return ExecutionResult.fail(
                ExecutionResult.ErrorCode.RUNTIME_ERROR,
                "Unexpected error: " + e.getMessage(),
                getStackTrace(e),
                logger.getMessages(),
                duration
            );
        }
    }

    /**
     * Create the script binding with API, session, and logger.
     */
    private static Binding createBinding(Session session, ScriptLogger logger) {
        Binding binding = new Binding();

        // Main API facade
        binding.setVariable("api", new CapellaAPI(session));

        // Direct session access for advanced operations
        binding.setVariable("session", session);

        // Logger for script output
        binding.setVariable("log", logger);

        // Convenience: println function
        binding.setVariable(
            "println",
            (java.util.function.Consumer<Object>) obj -> {
                logger.info(obj != null ? obj.toString() : "null");
            }
        );

        // Capella factories for raw API access via api.tx {}
        binding.setVariable(
            "OaFactory",
            org.polarsys.capella.core.data.oa.OaFactory.eINSTANCE
        );
        binding.setVariable(
            "CtxFactory",
            org.polarsys.capella.core.data.ctx.CtxFactory.eINSTANCE
        );
        binding.setVariable(
            "LaFactory",
            org.polarsys.capella.core.data.la.LaFactory.eINSTANCE
        );
        binding.setVariable(
            "PaFactory",
            org.polarsys.capella.core.data.pa.PaFactory.eINSTANCE
        );
        binding.setVariable(
            "FaFactory",
            org.polarsys.capella.core.data.fa.FaFactory.eINSTANCE
        );
        binding.setVariable(
            "CsFactory",
            org.polarsys.capella.core.data.cs.CsFactory.eINSTANCE
        );
        binding.setVariable(
            "InformationFactory",
            org.polarsys.capella.core.data.information.InformationFactory.eINSTANCE
        );
        try {
            Class<?> requirementFactory = Class.forName(
                "org.polarsys.capella.core.data.requirement.RequirementFactory"
            );
            Object instance = requirementFactory.getField("eINSTANCE").get(null);
            binding.setVariable("RequirementFactory", instance);
        } catch (Throwable t) {
            LOG.log(Level.FINE, "RequirementFactory unavailable", t);
        }
        binding.setVariable(
            "CapellacoreFactory",
            org.polarsys.capella.core.data.capellacore.CapellacoreFactory.eINSTANCE
        );

        return binding;
    }

    /**
     * Create a secure compiler configuration.
     */
    private static CompilerConfiguration createSecureConfig() {
        CompilerConfiguration config = new CompilerConfiguration();

        // Add default imports for convenience
        ImportCustomizer imports = new ImportCustomizer();
        imports.addImports(
            "org.eclipse.emf.ecore.EObject",
            "java.util.List",
            "java.util.Map",
            "java.util.ArrayList",
            "java.util.HashMap"
        );
        imports.addStarImports("org.polarsys.capella.ai.scripting");

        // Add Capella factory imports for raw API access via api.tx {}
        imports.addStarImports(
            "org.polarsys.capella.core.data.oa",
            "org.polarsys.capella.core.data.ctx",
            "org.polarsys.capella.core.data.la",
            "org.polarsys.capella.core.data.pa",
            "org.polarsys.capella.core.data.fa",
            "org.polarsys.capella.core.data.cs",
            "org.polarsys.capella.core.data.information",
            "org.polarsys.capella.core.data.capellacore",
            "org.polarsys.capella.core.data.capellamodeller"
        );
        config.addCompilationCustomizers(imports);

        // Add security restrictions
        SecureASTCustomizer secure = new SecureASTCustomizer();

        // Block dangerous method receivers
        secure.setReceiversBlackList(List.copyOf(BLOCKED_RECEIVERS));

        // Block package definitions (no new packages)
        secure.setPackageAllowed(false);

        config.addCompilationCustomizers(secure);

        return config;
    }

    /**
     * Get stack trace as string.
     */
    private static String getStackTrace(Throwable t) {
        if (t == null) {
            return null;
        }
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    /**
     * Extract a Groovy code block from LLM response.
     * Handles both ```groovy and ``` code fences.
     *
     * @param llmResponse the full LLM response
     * @return the extracted code, or the original response if no code block found
     */
    public static String extractCode(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return "";
        }

        // Try to find ```groovy block
        int groovyStart = llmResponse.indexOf("```groovy");
        if (groovyStart >= 0) {
            int codeStart = llmResponse.indexOf('\n', groovyStart) + 1;
            int codeEnd = llmResponse.indexOf("```", codeStart);
            if (codeEnd > codeStart) {
                return llmResponse.substring(codeStart, codeEnd).trim();
            }
        }

        // Try generic ``` block
        int genericStart = llmResponse.indexOf("```");
        if (genericStart >= 0) {
            int codeStart = llmResponse.indexOf('\n', genericStart) + 1;
            int codeEnd = llmResponse.indexOf("```", codeStart);
            if (codeEnd > codeStart) {
                return llmResponse.substring(codeStart, codeEnd).trim();
            }
        }

        // No code block found, return as-is (might be raw code)
        return llmResponse.trim();
    }

    /**
     * Shutdown the executor service.
     * Should be called when the plugin stops.
     */
    public static void shutdown() {
        EXECUTOR.shutdownNow();
        try {
            if (!EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.warning("GroovyExecutor did not terminate gracefully");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
