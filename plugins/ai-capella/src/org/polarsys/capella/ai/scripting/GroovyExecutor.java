package org.polarsys.capella.ai.scripting;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import groovy.transform.ConditionalInterrupt;

import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.VariableScope;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.swt.widgets.Display;
import org.polarsys.capella.ai.preferences.LLMPreferences;
import org.polarsys.capella.ai.privacy.Tokenizer;
import org.polarsys.capella.ai.session.SessionSafeguards;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sandboxed Groovy executor for LLM-generated scripts. Executes scripts with Capella API bindings,
 * restricted operations, and a cooperative deadline. Injected checks cover Groovy loops, methods,
 * and closures; they cannot preempt an arbitrary blocking Java call.
 *
 * <p>Security features:
 *
 * <ul>
 *   <li>30-second execution timeout
 *   <li>Blocked dangerous operations (System.exit, Runtime.exec, file I/O)
 *   <li>Limited imports to safe packages
 *   <li>Model writes roll back on cancellation before completion
 * </ul>
 *
 * <p>Script bindings:
 *
 * <ul>
 *   <li>{@code api} - CapellaAPI instance for model operations
 *   <li>{@code session} - Capella Session for advanced operations
 *   <li>{@code log} - ScriptLogger for output capture
 * </ul>
 */
public final class GroovyExecutor {

    private static final Logger LOG = Logger.getLogger(GroovyExecutor.class.getName());

    /** Default timeout in milliseconds */
    private static final long DEFAULT_TIMEOUT_MS = 30_000;

    /** Maximum script length to prevent memory issues */
    private static final int MAX_SCRIPT_LENGTH = 100_000;

    /** Stateful session TTL in milliseconds */
    private static final long SESSION_TTL_MS = 60 * 60 * 1000L;

    /** Cleanup cadence for session eviction */
    private static final long CLEANUP_INTERVAL_MS = 5 * 60 * 1000L;

    private static final Map<String, SessionContext> CONTEXTS = new ConcurrentHashMap<>();

    private static volatile long lastCleanupMs = 0L;

    private static final int MAX_SCRIPT_NESTING = 16;
    private static final ThreadLocal<ActiveScript> ACTIVE_SCRIPT = new ThreadLocal<>();

    private record ActiveScript(
            Session session, SessionSafeguards.ExecutionControl control, int depth) {}

    /** Executor for running scripts with timeout */
    private static final ExecutorService EXECUTOR =
            Executors.newCachedThreadPool(
                    r -> {
                        Thread t = new Thread(r, "GroovyExecutor-Worker");
                        t.setDaemon(true);
                        return t;
                    });

    /** Classes/packages blocked for security */
    private static final Set<String> BLOCKED_RECEIVERS =
            new HashSet<>(
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
                            "groovy.lang.GroovyClassLoader"));

    private GroovyExecutor() {}

    /**
     * Validate exact decoded proposal source without bindings, execution or a model transaction.
     */
    public static GroovyCompilationValidator.Report compileDecoded(
            String script, java.util.function.BooleanSupplier cancelled) {
        return GroovyCompilationValidator.validate(script, cancelled);
    }

    /**
     * A bounded wait for cold compiler startup; cancellation remains separate from a compile error.
     */
    public static GroovyCompilationValidator.Report compileDecoded(
            String script, java.util.function.BooleanSupplier cancelled, long timeoutMs) {
        return GroovyCompilationValidator.validate(script, cancelled, timeoutMs);
    }

    /**
     * Execute a Groovy script with Capella API bindings.
     *
     * @param session the Capella session
     * @param script the Groovy script to execute
     * @return ExecutionResult with return value, logs, and any errors
     */
    public static ExecutionResult execute(Session session, String script) {
        return execute(session, script, DEFAULT_TIMEOUT_MS, null, null);
    }

    /**
     * Execute a Groovy script with additional variable bindings.
     *
     * <p>Example: pass {@code selection} or {@code activeDiagram} for grounding.
     *
     * @param session the Capella session
     * @param script the Groovy script to execute
     * @param extraBindings additional variables to inject into the script binding
     */
    public static ExecutionResult execute(
            Session session, String script, java.util.Map<String, Object> extraBindings) {
        return execute(session, script, DEFAULT_TIMEOUT_MS, extraBindings, null);
    }

    /**
     * Execute a Groovy script with custom timeout.
     *
     * @param session the Capella session
     * @param script the Groovy script to execute
     * @param timeoutMs timeout in milliseconds
     * @return ExecutionResult with return value, logs, and any errors
     */
    public static ExecutionResult execute(Session session, String script, long timeoutMs) {
        return execute(session, script, timeoutMs, null, null);
    }

    public static ExecutionResult execute(
            Session session,
            String script,
            long timeoutMs,
            java.util.Map<String, Object> extraBindings) {
        return execute(session, script, timeoutMs, extraBindings, null);
    }

    public static ExecutionResult execute(Session session, String script, String sessionId) {
        return execute(session, script, DEFAULT_TIMEOUT_MS, null, sessionId);
    }

    /**
     * Execute a Groovy script with additional bindings and a session id (used for privacy
     * detokenization of the incoming script text).
     */
    public static ExecutionResult execute(
            Session session,
            String script,
            java.util.Map<String, Object> extraBindings,
            String sessionId) {
        return execute(session, script, DEFAULT_TIMEOUT_MS, extraBindings, sessionId);
    }

    public static ExecutionResult execute(
            Session session,
            String script,
            long timeoutMs,
            java.util.Map<String, Object> extraBindings,
            String sessionId) {
        return executeInternal(session, script, timeoutMs, extraBindings, sessionId, true);
    }

    /**
     * Execute exactly the already-decoded script approved by the user, preserving session
     * variables.
     */
    public static ExecutionResult executeDecoded(
            Session session,
            String script,
            java.util.Map<String, Object> extraBindings,
            String sessionId) {
        return executeInternal(
                session, script, DEFAULT_TIMEOUT_MS, extraBindings, sessionId, false);
    }

    private static ExecutionResult executeInternal(
            Session session,
            String script,
            long timeoutMs,
            java.util.Map<String, Object> extraBindings,
            String sessionId,
            boolean decodePrivacy) {
        long startTime = System.currentTimeMillis();

        final String resolvedScript =
                decodePrivacy
                                && sessionId != null
                                && !sessionId.isBlank()
                                && LLMPreferences.isPrivacyTokenizationEnabled()
                        ? Tokenizer.forSession(sessionId).detokenizeText(script)
                        : script;

        // Validate inputs
        if (session == null) {
            return ExecutionResult.fail(
                    ExecutionResult.ErrorCode.API_ERROR, "No active Capella session");
        }
        if (resolvedScript == null || resolvedScript.isBlank()) {
            return ExecutionResult.fail(
                    ExecutionResult.ErrorCode.INVALID_RESULT, "Script is empty");
        }
        if (resolvedScript.length() > MAX_SCRIPT_LENGTH) {
            return ExecutionResult.fail(
                    ExecutionResult.ErrorCode.SECURITY_VIOLATION,
                    "Script exceeds maximum length of " + MAX_SCRIPT_LENGTH + " characters");
        }
        if (timeoutMs <= 0) {
            return ExecutionResult.fail(
                    ExecutionResult.ErrorCode.INVALID_RESULT, "Script timeout must be positive");
        }
        if (session.getTransactionalEditingDomain() == null) {
            return ExecutionResult.fail(
                    ExecutionResult.ErrorCode.API_ERROR,
                    "No transactional editing domain available for model writes");
        }
        if (SessionSafeguards.isInWriteContext()) {
            ActiveScript parent = ACTIVE_SCRIPT.get();
            if (parent == null) {
                return ExecutionResult.fail(
                        ExecutionResult.ErrorCode.API_ERROR,
                        "Run scripts from a background thread outside an existing model"
                            + " transaction");
            }
            if (parent.session() != session) {
                return recordNestedFailure(
                        ExecutionResult.fail(
                                ExecutionResult.ErrorCode.API_ERROR,
                                "Nested scripts must use the parent Capella session"));
            }
            if (parent.depth() >= MAX_SCRIPT_NESTING) {
                return recordNestedFailure(
                        ExecutionResult.fail(
                                ExecutionResult.ErrorCode.SECURITY_VIOLATION,
                                "Maximum nested script depth exceeded"));
            }
            return executeNested(
                    session, resolvedScript, timeoutMs, extraBindings, parent, startTime);
        }
        // A worker writes through Display.syncExec. Waiting for it on SWT would deadlock.
        if (Display.getCurrent() != null) {
            return ExecutionResult.fail(
                    ExecutionResult.ErrorCode.API_ERROR,
                    "Run scripts from a background thread; the SWT UI thread cannot wait for model"
                        + " execution");
        }
        SessionSafeguards.ExecutionControl control =
                new SessionSafeguards.ExecutionControl(timeoutMs);

        // Create script logger for output capture
        ScriptLogger logger = new ScriptLogger();

        // Create bindings (stateful if sessionId provided)
        Binding binding = prepareBinding(session, logger, extraBindings, sessionId);

        // Create compiler configuration with security
        CompilerConfiguration config = createSecureConfig();

        // Create shell
        GroovyShell shell = new GroovyShell(GroovyExecutor.class.getClassLoader(), binding, config);

        // Execute with timeout
        Callable<Object> task =
                () -> {
                    return SessionSafeguards.withExecutionControl(
                            control,
                            () -> {
                                try {
                                    // Compile off SWT; execute all mutations as one undoable
                                    // command.
                                    Class<?> compiled =
                                            shell.getClassLoader().parseClass(resolvedScript);
                                    control.checkCancelled();
                                    return SessionSafeguards.runWrite(
                                            session,
                                            "Execute Script",
                                            () -> {
                                                // Instance initializers may use the API, so
                                                // construct inside the command too.
                                                Object value =
                                                        runCompiled(
                                                                compiled,
                                                                binding,
                                                                new ActiveScript(
                                                                        session, control, 1));
                                                control.checkCancelled();
                                                try {
                                                    // Also persist raw factory/EMF mutations after
                                                    // commit.
                                                    SessionSafeguards.requestSave(session);
                                                } catch (Exception e) {
                                                    throw new IllegalStateException(
                                                            "Model save failed", e);
                                                }
                                                return value;
                                            });
                                } catch (SecurityException e) {
                                    throw new SecurityException(
                                            "Blocked operation: " + e.getMessage(), e);
                                }
                            });
                };

        Future<Object> future = null;
        try {
            future = EXECUTOR.submit(task);
            Object result;
            try {
                result = future.get(control.remainingNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                control.cancel();
                future.cancel(true);
                control.awaitWritesFinished();
                return cancellationResult(control, timeoutMs, logger, startTime);
            }

            long duration = System.currentTimeMillis() - startTime;

            return ExecutionResult.success(result, logger.getMessages(), duration);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            long duration = System.currentTimeMillis() - startTime;

            Throwable saveFailure = findSaveFailure(cause);
            if (saveFailure != null) {
                return ExecutionResult.fail(
                        ExecutionResult.ErrorCode.SAVE_FAILED_AFTER_COMMIT,
                        saveFailure.getMessage(),
                        getStackTrace(saveFailure),
                        logger.getMessages(),
                        duration);
            }

            if (control.isCancelled() || containsCancellation(cause)) {
                control.cancel();
                control.awaitWritesFinished();
                return cancellationResult(control, timeoutMs, logger, startTime);
            }

            if (cause instanceof SecurityException) {
                String message = withSecurityHint(cause.getMessage());
                return ExecutionResult.fail(
                        ExecutionResult.ErrorCode.SECURITY_VIOLATION,
                        message,
                        getStackTrace(cause),
                        logger.getMessages(),
                        duration);
            }

            if (cause instanceof org.codehaus.groovy.control.MultipleCompilationErrorsException) {
                String msg = cause.getMessage();
                if (msg != null) {
                    String lower = msg.toLowerCase();
                    if (lower.contains("securityexception")
                            || lower.contains("method calls not allowed on")) {
                        return ExecutionResult.fail(
                                ExecutionResult.ErrorCode.SECURITY_VIOLATION,
                                withSecurityHint(msg),
                                getStackTrace(cause),
                                logger.getMessages(),
                                duration);
                    }
                }
                return ExecutionResult.fail(
                        ExecutionResult.ErrorCode.COMPILATION_ERROR,
                        "Script compilation failed: " + cause.getMessage(),
                        getStackTrace(cause),
                        logger.getMessages(),
                        duration);
            }

            return ExecutionResult.fail(
                    ExecutionResult.ErrorCode.RUNTIME_ERROR,
                    "Script execution failed: " + cause.getMessage(),
                    getStackTrace(cause),
                    logger.getMessages(),
                    duration);
        } catch (InterruptedException e) {
            control.cancel();
            if (future != null) {
                future.cancel(true);
            }
            control.awaitWritesFinished();
            Thread.currentThread().interrupt();
            if (control.wasCommitted()) {
                return cancellationResult(control, timeoutMs, logger, startTime);
            }
            return ExecutionResult.fail(
                    ExecutionResult.ErrorCode.TIMEOUT, "Script execution was interrupted");
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            LOG.log(Level.WARNING, "Unexpected error executing script", e);
            return ExecutionResult.fail(
                    ExecutionResult.ErrorCode.RUNTIME_ERROR,
                    "Unexpected error: " + e.getMessage(),
                    getStackTrace(e),
                    logger.getMessages(),
                    duration);
        }
    }

    /**
     * Child skills run in the already-owned SWT transaction, never by submitting a worker which
     * would dispatch back to this blocked thread. Their bindings are local, and their deadline is
     * bounded by the parent's remaining budget.
     */
    private static ExecutionResult executeNested(
            Session session,
            String script,
            long timeoutMs,
            Map<String, Object> extraBindings,
            ActiveScript parent,
            long startTime) {
        ScriptLogger logger = new ScriptLogger();
        SessionSafeguards.ExecutionControl control =
                new SessionSafeguards.ExecutionControl(timeoutMs, parent.control());
        try {
            Object value =
                    SessionSafeguards.withExecutionControl(
                            control,
                            () -> {
                                Binding binding =
                                        createBinding(session, logger, extraBindings, null, null);
                                GroovyShell shell =
                                        new GroovyShell(
                                                GroovyExecutor.class.getClassLoader(),
                                                binding,
                                                createSecureConfig());
                                Class<?> compiled = shell.getClassLoader().parseClass(script);
                                control.checkCancelled();
                                Object result =
                                        runCompiled(
                                                compiled,
                                                binding,
                                                new ActiveScript(
                                                        session, control, parent.depth() + 1));
                                control.checkCancelled();
                                return result;
                            });
            return ExecutionResult.success(
                    value, logger.getMessages(), System.currentTimeMillis() - startTime);
        } catch (Throwable failure) {
            parent.control().markRollbackOnly(failure);
            if (control.isCancelled() || containsCancellation(failure)) {
                control.cancel();
                return ExecutionResult.timeout(timeoutMs, logger.getMessages());
            }
            ExecutionResult.ErrorCode code =
                    failure instanceof SecurityException
                            ? ExecutionResult.ErrorCode.SECURITY_VIOLATION
                            : failure
                                            instanceof
                                            org.codehaus.groovy.control
                                                    .MultipleCompilationErrorsException
                                    ? ExecutionResult.ErrorCode.COMPILATION_ERROR
                                    : ExecutionResult.ErrorCode.RUNTIME_ERROR;
            return ExecutionResult.fail(
                    code,
                    "Nested script failed: " + failure.getMessage(),
                    getStackTrace(failure),
                    logger.getMessages(),
                    System.currentTimeMillis() - startTime);
        }
    }

    private static Object runCompiled(Class<?> compiled, Binding binding, ActiveScript current) {
        ActiveScript previous = ACTIVE_SCRIPT.get();
        ACTIVE_SCRIPT.set(current);
        try {
            return InvokerHelper.createScript(compiled, binding).run();
        } finally {
            if (previous == null) ACTIVE_SCRIPT.remove();
            else ACTIVE_SCRIPT.set(previous);
        }
    }

    /** Failed skills make the outer operation atomic even if callers ignore their result. */
    static ExecutionResult recordNestedFailure(ExecutionResult result) {
        ActiveScript active = ACTIVE_SCRIPT.get();
        if (result.failed() && active != null && SessionSafeguards.isInWriteContext()) {
            active.control().markRollbackOnly(new IllegalStateException(result.message()));
        }
        return result;
    }

    private static ExecutionResult cancellationResult(
            SessionSafeguards.ExecutionControl control,
            long timeoutMs,
            ScriptLogger logger,
            long startTime) {
        long duration = System.currentTimeMillis() - startTime;
        if (control.wasCommitted()) {
            if (control.wasSaved()) {
                logger.warn("Model changes were applied and saved before cancellation completed.");
                return ExecutionResult.success(
                        control.committedValue(), logger.getMessages(), duration);
            }
            Throwable saveFailure = control.saveFailure();
            return ExecutionResult.fail(
                    ExecutionResult.ErrorCode.SAVE_FAILED_AFTER_COMMIT,
                    saveFailure != null
                            ? saveFailure.getMessage()
                            : "Model changes were applied, but saving was not confirmed. Save the"
                                  + " model; do not rerun the script.",
                    saveFailure != null ? getStackTrace(saveFailure) : null,
                    logger.getMessages(),
                    duration);
        }
        return ExecutionResult.timeout(timeoutMs, logger.getMessages());
    }

    private static boolean containsCancellation(Throwable cause) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof SessionSafeguards.ScriptCancelledException) {
                return true;
            }
        }
        return false;
    }

    private static Throwable findSaveFailure(Throwable cause) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof SessionSafeguards.SaveFailedAfterCommitException) {
                return current;
            }
        }
        return null;
    }

    private static String withSecurityHint(String message) {
        String safe = message != null ? message : "Security violation";
        String lower = safe.toLowerCase();
        if (lower.contains(".classloader")
                || lower.contains("api.class.classloader")
                || lower.contains("loadclass(")
                || lower.contains("[java.lang.classloader]")) {
            return safe
                    + "\n"
                    + "Hint: ClassLoader is blocked in the Groovy sandbox. Use pre-bound factories"
                    + " (CapellacoreFactory, OaFactory, CtxFactory, LaFactory, PaFactory,"
                    + " EpbsFactory, FaFactory, CsFactory, InformationFactory) or OSGi bundle"
                    + " loading via FrameworkUtil.getBundle(api.class).";
        }
        if (lower.contains("java.lang.reflect") || lower.contains("reflect")) {
            return safe
                    + "\nHint: Java reflection (Method/Constructor/Field) is blocked. "
                    + "Prefer CapellaAPI methods or factory-based creation inside api.tx {}.";
        }
        return safe
                + "\n"
                + "Hint: The Groovy sandbox blocked an unsafe operation"
                + " (reflection/ClassLoader/file I/O/network are restricted).";
    }

    /** Create the script binding with API, session, and logger. */
    private static Binding createBinding(
            Session session,
            ScriptLogger logger,
            java.util.Map<String, Object> extraBindings,
            Set<String> reservedKeys,
            Runnable resetHook) {
        Binding binding = new Binding();
        applySystemBindings(binding, session, logger, extraBindings, reservedKeys, resetHook);
        return binding;
    }

    private static void applySystemBindings(
            Binding binding,
            Session session,
            ScriptLogger logger,
            java.util.Map<String, Object> extraBindings,
            Set<String> reservedKeys,
            Runnable resetHook) {
        // Queries can cache objects created during a subsequently rolled-back
        // command. Keep user variables, but start each execution with fresh API caches.
        setBindingVar(binding, reservedKeys, "api", new CapellaAPI(session, resetHook));

        setBindingVar(binding, reservedKeys, "requirements", new RequirementsAPI(session));

        // Direct session access for advanced operations
        setBindingVar(binding, reservedKeys, "session", session);

        // Logger for script output
        setBindingVar(binding, reservedKeys, "log", logger);

        // Convenience: println function
        setBindingVar(
                binding,
                reservedKeys,
                "println",
                (java.util.function.Consumer<Object>)
                        obj -> {
                            logger.info(obj != null ? obj.toString() : "null");
                        });

        // Capella factories for raw API access via api.tx {}
        setBindingVar(
                binding,
                reservedKeys,
                "OaFactory",
                org.polarsys.capella.core.data.oa.OaFactory.eINSTANCE);
        setBindingVar(
                binding,
                reservedKeys,
                "CtxFactory",
                org.polarsys.capella.core.data.ctx.CtxFactory.eINSTANCE);
        setBindingVar(
                binding,
                reservedKeys,
                "LaFactory",
                org.polarsys.capella.core.data.la.LaFactory.eINSTANCE);
        setBindingVar(
                binding,
                reservedKeys,
                "PaFactory",
                org.polarsys.capella.core.data.pa.PaFactory.eINSTANCE);
        setBindingVar(
                binding,
                reservedKeys,
                "EpbsFactory",
                org.polarsys.capella.core.data.epbs.EpbsFactory.eINSTANCE);
        setBindingVar(
                binding,
                reservedKeys,
                "FaFactory",
                org.polarsys.capella.core.data.fa.FaFactory.eINSTANCE);
        setBindingVar(
                binding,
                reservedKeys,
                "CsFactory",
                org.polarsys.capella.core.data.cs.CsFactory.eINSTANCE);
        setBindingVar(
                binding,
                reservedKeys,
                "InformationFactory",
                org.polarsys.capella.core.data.information.InformationFactory.eINSTANCE);
        try {
            Class<?> requirementFactory =
                    Class.forName("org.polarsys.capella.core.data.requirement.RequirementFactory");
            Object instance = requirementFactory.getField("eINSTANCE").get(null);
            setBindingVar(binding, reservedKeys, "RequirementFactory", instance);
        } catch (Throwable t) {
            LOG.log(Level.FINE, "RequirementFactory unavailable", t);
        }
        setBindingVar(
                binding,
                reservedKeys,
                "CapellacoreFactory",
                org.polarsys.capella.core.data.capellacore.CapellacoreFactory.eINSTANCE);

        if (extraBindings != null && !extraBindings.isEmpty()) {
            for (java.util.Map.Entry<String, Object> e : extraBindings.entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank()) {
                    continue;
                }
                setBindingVar(binding, reservedKeys, e.getKey(), e.getValue());
            }
        }
    }

    private static void setBindingVar(
            Binding binding, Set<String> reservedKeys, String key, Object value) {
        binding.setVariable(key, value);
        if (reservedKeys != null) {
            reservedKeys.add(key);
        }
    }

    public static String buildSessionKey(Session session, String conversationId) {
        if (session == null) {
            return null;
        }
        String base =
                session.getSessionResource() != null
                        ? session.getSessionResource().getURI().toString()
                        : "session-" + System.identityHashCode(session);
        if (conversationId == null || conversationId.isBlank()) {
            return base;
        }
        return base + "::" + conversationId.trim();
    }

    public static void resetSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        SessionContext context = CONTEXTS.get(sessionId);
        if (context == null) {
            return;
        }
        synchronized (context.lock) {
            context.resetUserVariables();
        }
    }

    private static Binding prepareBinding(
            Session session,
            ScriptLogger logger,
            java.util.Map<String, Object> extraBindings,
            String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return createBinding(session, logger, extraBindings, null, null);
        }

        SessionContext context = getOrCreateContext(sessionId, session);
        synchronized (context.lock) {
            context.touch();
            if (context.session != session) {
                context.resetSession(session);
            }
            applySystemBindings(
                    context.binding,
                    session,
                    logger,
                    extraBindings,
                    context.reservedKeys,
                    () -> resetSession(sessionId));
            return context.binding;
        }
    }

    private static SessionContext getOrCreateContext(String sessionId, Session session) {
        purgeExpiredContexts();
        return CONTEXTS.compute(
                sessionId,
                (key, existing) -> {
                    if (existing == null) {
                        return new SessionContext(session);
                    }
                    return existing;
                });
    }

    private static void purgeExpiredContexts() {
        long now = System.currentTimeMillis();
        if (now - lastCleanupMs < CLEANUP_INTERVAL_MS) {
            return;
        }
        lastCleanupMs = now;
        for (Map.Entry<String, SessionContext> entry : CONTEXTS.entrySet()) {
            SessionContext context = entry.getValue();
            if (context == null) {
                CONTEXTS.remove(entry.getKey());
                continue;
            }
            if (now - context.lastAccessMs > SESSION_TTL_MS) {
                CONTEXTS.remove(entry.getKey());
            }
        }
    }

    private static final class SessionContext {
        private final Object lock = new Object();
        private Binding binding;
        private Session session;
        private final Set<String> reservedKeys = new java.util.HashSet<>();
        private volatile long lastAccessMs;

        private SessionContext(Session session) {
            this.session = session;
            this.binding = new Binding();
            this.lastAccessMs = System.currentTimeMillis();
        }

        private void touch() {
            this.lastAccessMs = System.currentTimeMillis();
        }

        private void resetSession(Session newSession) {
            this.session = newSession;
            this.binding = new Binding();
            this.reservedKeys.clear();
        }

        private void resetUserVariables() {
            if (binding == null) {
                return;
            }
            Map<String, Object> vars = binding.getVariables();
            vars.keySet().removeIf(key -> !reservedKeys.contains(key));
        }

        private java.util.List<VariableSummary> describeUserVariables() {
            if (binding == null) {
                return java.util.List.of();
            }
            Map<String, Object> vars = binding.getVariables();
            if (vars == null || vars.isEmpty()) {
                return java.util.List.of();
            }
            java.util.List<VariableSummary> summaries = new java.util.ArrayList<>();
            for (Map.Entry<String, Object> entry : vars.entrySet()) {
                String key = entry.getKey();
                if (key == null || key.isBlank()) {
                    continue;
                }
                if (reservedKeys.contains(key)) {
                    continue;
                }
                Object value = entry.getValue();
                String summary = summarizeVariable(value);
                if (summary == null || summary.isBlank()) {
                    continue;
                }
                summaries.add(new VariableSummary(key, summary));
                if (summaries.size() >= MAX_VARIABLE_SUMMARIES) {
                    break;
                }
            }
            return summaries;
        }
    }

    public static final class VariableSummary {
        public final String name;
        public final String summary;

        private VariableSummary(String name, String summary) {
            this.name = name;
            this.summary = summary;
        }
    }

    private static final int MAX_VARIABLE_SUMMARIES = 12;
    private static final int MAX_VALUE_LENGTH = 120;

    public static java.util.List<VariableSummary> describeSessionVariables(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return java.util.List.of();
        }
        SessionContext context = CONTEXTS.get(sessionId);
        if (context == null) {
            return java.util.List.of();
        }
        synchronized (context.lock) {
            return context.describeUserVariables();
        }
    }

    private static String summarizeVariable(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof EObject) {
            return formatEObject((EObject) value, true);
        }
        if (value instanceof java.util.Collection<?>) {
            java.util.Collection<?> collection = (java.util.Collection<?>) value;
            int size = collection.size();
            Object sample = null;
            for (Object item : collection) {
                if (item != null) {
                    sample = item;
                    break;
                }
            }
            if (sample instanceof EObject) {
                String type = formatEObject((EObject) sample, false);
                return "List<" + type + ">(size=" + size + ")";
            }
            return null;
        }
        return null;
    }

    private static String formatEObject(EObject obj, boolean includeTarget) {
        if (obj == null) {
            return "null";
        }
        String type =
                obj.eClass() != null ? obj.eClass().getName() : obj.getClass().getSimpleName();
        String name = extractName(obj);
        String base = name == null || name.isBlank() ? type : type + " \"" + truncate(name) + "\"";
        if (includeTarget) {
            EObject target = extractTarget(obj);
            if (target != null && target != obj) {
                base += " -> " + formatEObject(target, false);
            }
        }
        return base;
    }

    private static String extractName(EObject obj) {
        try {
            var feature = obj.eClass().getEStructuralFeature("name");
            if (feature != null) {
                Object value = obj.eGet(feature);
                if (value != null) {
                    return value.toString();
                }
            }
        } catch (Exception ignored) {
            // best-effort
        }
        return null;
    }

    private static EObject extractTarget(EObject obj) {
        try {
            var method = obj.getClass().getMethod("getTarget");
            Object value = method.invoke(obj);
            if (value instanceof EObject) {
                return (EObject) value;
            }
        } catch (Exception ignored) {
            // optional
        }
        return null;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() <= MAX_VALUE_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_VALUE_LENGTH - 3) + "...";
    }

    /** Create a secure compiler configuration. */
    static CompilerConfiguration createSecureConfig() {
        CompilerConfiguration config = new CompilerConfiguration();

        // Add default imports for convenience
        ImportCustomizer imports = new ImportCustomizer();
        imports.addImports(
                "org.eclipse.emf.ecore.EObject",
                "java.util.List",
                "java.util.Map",
                "java.util.ArrayList",
                "java.util.HashMap");
        imports.addStarImports("org.polarsys.capella.ai.scripting");

        // Add Capella factory imports for raw API access via api.tx {}
        imports.addStarImports(
                "org.polarsys.capella.core.data.oa",
                "org.polarsys.capella.core.data.ctx",
                "org.polarsys.capella.core.data.la",
                "org.polarsys.capella.core.data.pa",
                "org.polarsys.capella.core.data.epbs",
                "org.polarsys.capella.core.data.fa",
                "org.polarsys.capella.core.data.cs",
                "org.polarsys.capella.core.data.information",
                "org.polarsys.capella.core.data.capellacore",
                "org.polarsys.capella.core.data.capellamodeller");
        config.addCompilationCustomizers(imports);

        ClosureExpression cancellationCondition =
                new ClosureExpression(
                        Parameter.EMPTY_ARRAY,
                        new ReturnStatement(
                                new StaticMethodCallExpression(
                                        ClassHelper.make(SessionSafeguards.class),
                                        "isScriptCancellationRequested",
                                        ArgumentListExpression.EMPTY_ARGUMENTS)));
        cancellationCondition.setVariableScope(new VariableScope());
        config.addCompilationCustomizers(
                new ASTTransformationCustomizer(
                        Map.of(
                                "value",
                                cancellationCondition,
                                "thrown",
                                SessionSafeguards.ScriptCancelledException.class),
                        ConditionalInterrupt.class));

        // Add security restrictions
        SecureASTCustomizer secure = new SecureASTCustomizer();

        // Block dangerous method receivers
        secure.setReceiversBlackList(List.copyOf(BLOCKED_RECEIVERS));

        // Block package definitions (no new packages)
        secure.setPackageAllowed(false);

        config.addCompilationCustomizers(secure);

        return config;
    }

    /** Get stack trace as string. */
    private static String getStackTrace(Throwable t) {
        if (t == null) {
            return null;
        }
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    /**
     * Extract a Groovy code block from LLM response. Handles both ```groovy and ``` code fences.
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

    /** Shutdown the executor service. Should be called when the plugin stops. */
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
