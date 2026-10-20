package org.polarsys.capella.ai.dev;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.agent.AgentJson;
import org.polarsys.capella.ai.agent.ArcadiaModelTools;
import org.polarsys.capella.ai.scripting.ExecutionResult;
import org.polarsys.capella.ai.scripting.GroovyExecutor;
import org.polarsys.capella.ai.scripting.RequirementsAPI;
import org.polarsys.capella.ai.scripting.ScriptRouter;
import org.polarsys.capella.ai.session.SelectionContext;
import org.polarsys.capella.ai.session.SessionBridge;
import org.polarsys.capella.ai.session.SessionSafeguards;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Development HTTP server for testing AI code execution without Eclipse UI.
 *
 * <p>Exposes endpoints:
 *
 * <ul>
 *   <li>POST /execute - Execute Groovy code directly
 *   <li>POST /inspect - Fixed model read tools with JSON arguments; no script execution
 *   <li>POST /mcp - Model Context Protocol (JSON-RPC 2.0) exposing the same read tools
 *   <li>POST /eval - Score agent tasks headlessly on a disposable model; requires
 *       -Dcapella.ai.eval=true
 *   <li>POST /chat - Send prompt to LLM and execute generated code
 *   <li>GET /status - Check server and session status
 *   <li>GET /prompt - Get the system prompt for code generation
 *   <li>GET /context - Get current selection + active diagram
 *   <li>GET /config - View dev server configuration
 *   <li>GET /debug - View debug information
 * </ul>
 *
 * <p>Start from Eclipse: Window → Show View → Other → Capella AI Dev Server
 *
 * <p>Or programmatically: DevServer.start(8765)
 */
public final class DevServer {

    private static HttpServer server;
    private static volatile String token;
    private static java.nio.file.Path tokenFile;
    private static ExecutorService requestExecutor;
    private static final int DEFAULT_PORT = 8765;
    private static final Logger LOG = Logger.getLogger(DevServer.class.getName());
    private static final int MAX_INSPECTION_BODY_BYTES = 32768;
    private static final int MAX_INSPECTION_SESSIONS = 4;
    private static final long INSPECTION_CONTEXT_TTL_NANOS = TimeUnit.MINUTES.toNanos(10);
    private static final Map<Session, InspectionContext> INSPECTION_CONTEXTS =
            new IdentityHashMap<>();

    private static final class InspectionContext {
        final Session session;
        final Resource resource;
        final TransactionalEditingDomain domain;
        final ArcadiaModelTools tools;
        long usedNanos;

        InspectionContext(Session session, long now) {
            this.session = session;
            resource = session.getSessionResource();
            domain = session.getTransactionalEditingDomain();
            tools = new ArcadiaModelTools(session);
            usedNanos = now;
        }

        boolean available() {
            return session.isOpen()
                    && session.getSessionResource() == resource
                    && session.getTransactionalEditingDomain() == domain
                    && resource != null
                    && resource.getResourceSet() == domain.getResourceSet();
        }
    }

    private record InspectionRequest(
            String sessionUri, String tool, Map<String, Object> arguments) {}

    private static final class InspectionRequestException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        InspectionRequestException(String message) {
            super(message);
        }
    }

    private DevServer() {}

    /** Start the dev server on the default port (8765). */
    public static synchronized void start() {
        start(DEFAULT_PORT);
    }

    /** Start the dev server on the specified port. */
    public static synchronized void start(int port) {
        if (server != null) {
            System.out.println(
                    "[DevServer] Already running on port " + server.getAddress().getPort());
            return;
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("DevServer port must be between 0 and 65535");
        }

        try {
            server =
                    HttpServer.create(
                            new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            requestExecutor =
                    Executors.newFixedThreadPool(
                            4,
                            task -> {
                                Thread worker = new Thread(task, "CapellaAI-DevServer");
                                worker.setDaemon(true);
                                return worker;
                            });
            server.setExecutor(requestExecutor);
            publishToken(server.getAddress().getPort());

            // Endpoints
            addLocalContext("/execute", DevServer::handleExecute);
            addLocalContext("/inspect", DevServer::handleInspect);
            addLocalContext("/mcp", DevServer::handleMcp);
            addLocalContext("/eval", DevServer::handleEval);
            addLocalContext("/status", DevServer::handleStatus);
            addLocalContext("/requirements/enable", DevServer::handleEnableRequirements);

            server.start();
            System.out.println(
                    "[DevServer] Started on http://localhost:" + server.getAddress().getPort());
            System.out.println("[DevServer] Send Authorization: Bearer <token> from " + tokenFile);
            System.out.println("[DevServer] Endpoints:");
            System.out.println("  POST /execute - Execute Groovy code");
            System.out.println("  POST /inspect - Fixed native read tools (JSON data only)");
            System.out.println("  POST /mcp     - Model Context Protocol (JSON-RPC) read tools");
            System.out.println(
                    "  POST /eval    - Score agent tasks on a disposable model (opt-in)");
            System.out.println("  POST /chat    - Send prompt to LLM, execute result");
            System.out.println("  GET  /status  - Check server/session status");
            System.out.println("  GET  /prompt  - Get system prompt for LLM");
            System.out.println("  GET  /context - Get selection + active diagram context");
            System.out.println("  GET  /config  - View configuration");
            System.out.println("  GET  /debug   - View debug information");
        } catch (IOException e) {
            stop();
            LOG.log(Level.SEVERE, "DevServer failed to start on port " + port, e);
        }
    }

    /** Stop the dev server. */
    public static synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            System.out.println("[DevServer] Stopped");
        }
        token = null;
        if (tokenFile != null) {
            try {
                java.nio.file.Files.deleteIfExists(tokenFile);
            } catch (IOException ignored) {
            }
            tokenFile = null;
        }
        if (requestExecutor != null) {
            requestExecutor.shutdownNow();
            requestExecutor = null;
        }
        synchronized (INSPECTION_CONTEXTS) {
            INSPECTION_CONTEXTS.clear();
        }
    }

    /** Check if server is running. */
    public static synchronized boolean isRunning() {
        return server != null;
    }

    /** Current loopback address, or null while stopped. */
    public static synchronized InetSocketAddress getAddress() {
        return server != null ? server.getAddress() : null;
    }

    /** Bearer token local clients must send; null while stopped. */
    public static String token() {
        return token;
    }

    /** Where a running server on this port writes its token for local clients such as MCP hosts. */
    public static java.nio.file.Path tokenFile(int port) {
        return java.nio.file.Path.of(
                System.getProperty("user.home"), ".capella-ai", "devserver-" + port + ".token");
    }

    private static void publishToken(int port) throws IOException {
        String configured = System.getenv("CAPELLA_AI_DEVSERVER_TOKEN");
        if (configured != null && configured.matches("[A-Za-z0-9._~+/=-]{16,256}"))
            token = configured;
        else {
            if (configured != null && !configured.isBlank()) {
                LOG.warning(
                        "CAPELLA_AI_DEVSERVER_TOKEN is ignored: use 16 to 256 URL-safe characters."
                                + " A random token is used instead.");
            }
            byte[] random = new byte[32];
            new java.security.SecureRandom().nextBytes(random);
            token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        }
        java.nio.file.Path file = tokenFile(port), directory = file.getParent();
        java.nio.file.Files.createDirectories(directory);
        java.nio.file.Path draft;
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            // Owner-only from creation: other local users must never be able to read the token.
            java.nio.file.Files.setPosixFilePermissions(
                    directory,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            draft =
                    java.nio.file.Files.createTempFile(
                            directory,
                            "devserver-",
                            ".tmp",
                            java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                                    java.nio.file.attribute.PosixFilePermissions.fromString(
                                            "rw-------")));
        } else {
            draft = java.nio.file.Files.createTempFile(directory, "devserver-", ".tmp");
        }
        java.nio.file.Files.writeString(draft, token + "\n", StandardCharsets.UTF_8);
        java.nio.file.Files.move(
                draft,
                file,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        tokenFile = file;
    }

    private static boolean authorized(String header) {
        String expected = token;
        return expected != null
                && header != null
                && header.startsWith("Bearer ")
                && java.security.MessageDigest.isEqual(
                        header.substring(7).getBytes(StandardCharsets.UTF_8),
                        expected.getBytes(StandardCharsets.UTF_8));
    }

    private static void addLocalContext(String path, com.sun.net.httpserver.HttpHandler handler) {
        server.createContext(
                path,
                exchange -> {
                    // This development interface is for local tools that can read the user's token
                    // file.
                    // A webpage must not be able to submit scripts through the user's browser.
                    if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()
                            || exchange.getRequestHeaders().getFirst("Origin") != null) {
                        sendResponse(
                                exchange,
                                403,
                                "{\"error\":\"Only local non-browser clients are allowed\"}");
                        return;
                    }
                    if (!authorized(exchange.getRequestHeaders().getFirst("Authorization"))) {
                        exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                        sendResponse(
                                exchange,
                                401,
                                "{\"error\":\"Send Authorization: Bearer <token> from "
                                        + escapeJson(String.valueOf(tokenFile))
                                        + "\"}");
                        return;
                    }
                    handler.handle(exchange);
                });
    }

    private static void handleEnableRequirements(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        Session session = SessionBridge.getActiveSession();
        if (session == null || !session.isOpen()) {
            sendResponse(exchange, 400, "{\"error\":\"Open the target Capella model first\"}");
            return;
        }
        try {
            boolean changed = new RequirementsAPI(session).enableViewpoint();
            sendResponse(exchange, 200, "{\"success\":true,\"changed\":" + changed + "}");
        } catch (RuntimeException failure) {
            sendResponse(exchange, 400, "{\"error\":\"" + escapeJson(failure.getMessage()) + "\"}");
        }
    }

    /**
     * POST /inspect accepts only {session_uri, tool, arguments}. The exact current session URI is
     * required. The existing fixed read tools run on SWT in native read transactions; no Groovy,
     * live-object binding, save or command is exposed.
     */
    private static void handleInspect(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        byte[] body;
        try (InputStream input = exchange.getRequestBody()) {
            body = input.readNBytes(MAX_INSPECTION_BODY_BYTES + 1);
        }
        if (body.length > MAX_INSPECTION_BODY_BYTES) {
            sendResponse(exchange, 413, "{\"error\":\"Inspection body exceeds 32768 bytes\"}");
            return;
        }
        try {
            InspectionRequest request = inspectionRequest(new String(body, StandardCharsets.UTF_8));
            String result = inspect(SessionBridge.getActiveSession(), request);
            sendResponse(exchange, 200, result);
        } catch (InspectionRequestException | ArcadiaModelTools.ArgumentException rejected) {
            sendResponse(
                    exchange, 400, "{\"error\":\"" + escapeJson(rejected.getMessage()) + "\"}");
        } catch (Exception unavailable) {
            sendResponse(
                    exchange,
                    409,
                    "{\"error\":\"The captured native inspection could not complete\"}");
        }
    }

    /** POST /mcp: one JSON-RPC message per request; no server-initiated stream (GET is refused). */
    private static void handleMcp(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().add("Allow", "POST");
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        byte[] body;
        try (InputStream input = exchange.getRequestBody()) {
            body = input.readNBytes(MAX_INSPECTION_BODY_BYTES + 1);
        }
        if (body.length > MAX_INSPECTION_BODY_BYTES) {
            sendResponse(exchange, 413, "{\"error\":\"MCP message exceeds 32768 bytes\"}");
            return;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders()
                .forEach(
                        (name, values) -> {
                            if (!values.isEmpty()) headers.put(name, values.get(0));
                        });
        McpServer.Response response =
                McpServer.handle(
                        SessionBridge.getActiveSession(),
                        headers,
                        new String(body, StandardCharsets.UTF_8));
        if (response.body() == null) {
            exchange.sendResponseHeaders(response.status(), -1);
            exchange.close();
        } else {
            sendResponse(exchange, response.status(), response.body());
        }
    }

    /**
     * POST /eval runs eval tasks through the agent with every proposal auto-approved and scores
     * them by native checks. Because nothing is reviewed by a person, it is refused unless Capella
     * was started with -Dcapella.ai.eval=true or CAPELLA_AI_EVAL=1. Body: an eval file; "restore":
     * true undoes each task's changes and saves before the next. Use a disposable copy of a model.
     */
    private static void handleEval(HttpExchange exchange) throws IOException {
        if (!"true".equals(System.getProperty("capella.ai.eval"))
                && !"1".equals(System.getenv("CAPELLA_AI_EVAL"))) {
            sendResponse(
                    exchange,
                    403,
                    "{\"error\":\"Evaluation auto-approves model edits; start Capella with"
                            + " -Dcapella.ai.eval=true (or CAPELLA_AI_EVAL=1) on a disposable"
                            + " model\"}");
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        Session session = SessionBridge.getActiveSession();
        if (session == null || !session.isOpen()) {
            sendResponse(
                    exchange, 400, "{\"error\":\"Open the disposable evaluation model first\"}");
            return;
        }
        String body = readBody(exchange);
        List<org.polarsys.capella.ai.agent.EvalRunner.Task> tasks;
        try {
            tasks = org.polarsys.capella.ai.agent.EvalRunner.parse(body);
        } catch (IllegalArgumentException invalid) {
            sendResponse(exchange, 400, "{\"error\":\"" + escapeJson(invalid.getMessage()) + "\"}");
            return;
        }
        boolean restore =
                AgentJson.parse(body, 2_000_000) instanceof Map<?, ?> file
                        && Boolean.TRUE.equals(file.get("restore"));
        List<Object> results = new java.util.ArrayList<>();
        int passed = 0;
        for (var task : tasks) {
            String conversation = "eval-" + task.id();
            Object[] baseline = new Object[1];
            SessionSafeguards.runOnUiThread(
                    () ->
                            baseline[0] =
                                    session.getTransactionalEditingDomain()
                                            .getCommandStack()
                                            .getUndoCommand());
            var result =
                    org.polarsys.capella.ai.agent.EvalRunner.run(
                            session,
                            task,
                            () -> {
                                try {
                                    return ScriptRouter.createRuntime(
                                            session, conversation, null, id -> {});
                                } catch (Exception failure) {
                                    throw new IllegalStateException(
                                            "The agent runtime could not start", failure);
                                }
                            },
                            ScriptRouter.agentSystemPrompt(
                                    session, conversation, SelectionContext.empty()),
                            org.polarsys.capella.ai.agent.ModelVerification::snapshot);
            if (result.passed()) passed++;
            results.add(result.json());
            if (restore) {
                SessionSafeguards.runOnUiThread(
                        () -> {
                            var stack = session.getTransactionalEditingDomain().getCommandStack();
                            while (stack.canUndo() && stack.getUndoCommand() != baseline[0])
                                stack.undo();
                        });
                try {
                    SessionSafeguards.requestSave(session);
                } catch (Exception failure) {
                    LOG.log(Level.WARNING, "Eval restore save failed", failure);
                }
            }
        }
        sendResponse(
                exchange,
                200,
                AgentJson.write(
                        Map.of("passed", passed, "total", tasks.size(), "results", results)));
    }

    /** Package-local entry for isolated tests of the same data-only request path. */
    static String inspect(Session session, String json) throws Exception {
        return inspect(session, inspectionRequest(json));
    }

    /**
     * A fixed read tool on the given session, addressed by that session's own URI (used by MCP).
     */
    static String read(Session session, String tool, Map<String, Object> arguments)
            throws Exception {
        Resource resource = session.getSessionResource();
        if (resource == null || resource.getURI() == null)
            throw new InspectionRequestException("The Capella model has no session resource");
        return inspect(
                session,
                new InspectionRequest(
                        resource.getURI().toString(),
                        tool,
                        java.util.Collections.unmodifiableMap(new LinkedHashMap<>(arguments))));
    }

    private static String inspect(Session session, InspectionRequest request) throws Exception {
        Thread caller = Thread.currentThread();
        if (caller.isInterrupted())
            throw new InspectionRequestException("Inspection was cancelled");
        InspectionContext context = inspectionContext(session, request.sessionUri());
        String result;
        // The fixed tools access cursor state only on SWT inside their native
        // read transaction. Never hold a monitor while waiting for SWT.
        SessionSafeguards.runOnUiThread(
                () -> requireInspectionSession(context, request.sessionUri(), caller));
        result = context.tools.execute(request.tool(), request.arguments());
        SessionSafeguards.runOnUiThread(
                () -> requireInspectionSession(context, request.sessionUri(), caller));
        return "{\"session_uri\":"
                + AgentJson.write(request.sessionUri())
                + ",\"read_only\":true,\"data\":"
                + result
                + "}";
    }

    private static InspectionRequest inspectionRequest(String json) {
        if (json == null
                || json.getBytes(StandardCharsets.UTF_8).length > MAX_INSPECTION_BODY_BYTES) {
            throw new InspectionRequestException("Inspection body exceeds 32768 bytes");
        }
        Object decoded;
        try {
            decoded = AgentJson.parse(json, MAX_INSPECTION_BODY_BYTES);
        } catch (IllegalArgumentException invalid) {
            throw new InspectionRequestException("Inspection requires bounded JSON data");
        }
        if (!(decoded instanceof Map<?, ?> body)
                || !Set.of("session_uri", "tool", "arguments").containsAll(body.keySet())) {
            throw new InspectionRequestException(
                    "Inspection accepts only session_uri, tool and arguments");
        }
        if (!(body.get("session_uri") instanceof String uri)
                || uri.isBlank()
                || uri.length() > 2000
                || uri.indexOf('\0') >= 0) {
            throw new InspectionRequestException(
                    "Inspection requires the exact current session_uri");
        }
        if (!(body.get("tool") instanceof String tool)
                || !ArcadiaModelTools.toolNames().contains(tool)) {
            throw new InspectionRequestException(
                    "Inspection requires an existing fixed model read tool");
        }
        Object argumentValue = body.containsKey("arguments") ? body.get("arguments") : Map.of();
        if (!(argumentValue instanceof Map<?, ?> arguments)) {
            throw new InspectionRequestException("Inspection arguments must be a JSON object");
        }
        Map<String, Object> typed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : arguments.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new InspectionRequestException("Inspection argument names must be text");
            }
            typed.put(key, entry.getValue());
        }
        return new InspectionRequest(uri, tool, java.util.Collections.unmodifiableMap(typed));
    }

    private static InspectionContext inspectionContext(Session session, String expectedUri) {
        AtomicReference<InspectionContext> found = new AtomicReference<>();
        SessionSafeguards.runOnUiThread(
                () -> {
                    long now = System.nanoTime();
                    synchronized (INSPECTION_CONTEXTS) {
                        INSPECTION_CONTEXTS
                                .values()
                                .removeIf(
                                        context ->
                                                !context.available()
                                                        || now - context.usedNanos
                                                                >= INSPECTION_CONTEXT_TTL_NANOS);
                        if (session == null
                                || !session.isOpen()
                                || session.getSessionResource() == null
                                || session.getTransactionalEditingDomain() == null
                                || session.getSessionResource().getURI() == null
                                || !expectedUri.equals(
                                        session.getSessionResource().getURI().toString())
                                || session.getSessionResource().getResourceSet()
                                        != session.getTransactionalEditingDomain()
                                                .getResourceSet()) {
                            throw new InspectionRequestException(
                                    "Inspection session_uri must match the open captured model");
                        }
                        InspectionContext context = INSPECTION_CONTEXTS.get(session);
                        if (context == null) {
                            if (INSPECTION_CONTEXTS.size() == MAX_INSPECTION_SESSIONS) {
                                InspectionContext oldest =
                                        INSPECTION_CONTEXTS.values().stream()
                                                .min(
                                                        java.util.Comparator.comparingLong(
                                                                value -> value.usedNanos))
                                                .orElseThrow();
                                INSPECTION_CONTEXTS.remove(oldest.session);
                            }
                            context = new InspectionContext(session, now);
                            INSPECTION_CONTEXTS.put(session, context);
                        }
                        context.usedNanos = now;
                        found.set(context);
                    }
                });
        return found.get();
    }

    private static void requireInspectionSession(
            InspectionContext context, String expectedUri, Thread caller) {
        if (caller.isInterrupted()
                || Thread.currentThread().isInterrupted()
                || !context.available()
                || context.resource.getURI() == null
                || !expectedUri.equals(context.resource.getURI().toString())) {
            throw new InspectionRequestException(
                    "The captured inspection session changed, closed or was cancelled");
        }
    }

    /**
     * POST /execute - Execute Groovy code directly Body: Groovy code to execute Returns: JSON with
     * result
     */
    private static void handleExecute(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\": \"Method not allowed\"}");
            return;
        }

        Session session = SessionBridge.getActiveSession();
        if (session == null) {
            sendResponse(
                    exchange,
                    400,
                    "{\"error\": \"No active Capella session. Open a model first.\"}");
            return;
        }

        String code = readBody(exchange);
        if (code == null || code.isBlank()) {
            sendResponse(exchange, 400, "{\"error\": \"No code provided\"}");
            return;
        }

        try {
            String conversationId = getConversationId(exchange);
            String sessionKey = GroovyExecutor.buildSessionKey(session, conversationId);
            SelectionContext ctx = SessionBridge.getSelectionContext();
            java.util.HashMap<String, Object> extraBindings = new java.util.HashMap<>();
            extraBindings.put(
                    "selection", ctx != null ? ctx.getSelectedElements() : java.util.List.of());
            extraBindings.put(
                    "activeDiagram", ctx != null ? ctx.getActiveDiagram().orElse(null) : null);
            ExecutionResult result =
                    GroovyExecutor.execute(session, code, extraBindings, sessionKey);
            String json = formatResult(result);
            sendResponse(exchange, result.success() ? 200 : 400, json);
        } catch (Exception e) {
            sendResponse(exchange, 500, "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    /** GET /status - Check server and session status */
    private static void handleStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\": \"Method not allowed\"}");
            return;
        }

        try {
            sendResponse(exchange, 200, status(SessionBridge.getActiveSession()));
        } catch (RuntimeException unavailable) {
            sendResponse(
                    exchange,
                    409,
                    "{\"error\":\"The captured native session status could not complete\"}");
        }
    }

    /** Fixed native state for development verification without creating an Undo entry. */
    static String status(Session session) {
        AtomicReference<Map<String, Object>> state = new AtomicReference<>();
        SessionSafeguards.runOnUiThread(
                () -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("running", true);
                    var bundle = org.osgi.framework.FrameworkUtil.getBundle(DevServer.class);
                    result.put(
                            "plugin_version",
                            bundle == null ? null : bundle.getVersion().toString());
                    result.put("hasSession", session != null && session.isOpen());
                    if (session == null || !session.isOpen()) {
                        state.set(result);
                        return;
                    }
                    Resource resource = session.getSessionResource();
                    TransactionalEditingDomain domain = session.getTransactionalEditingDomain();
                    if (resource == null
                            || resource.getURI() == null
                            || domain == null
                            || resource.getResourceSet() != domain.getResourceSet()) {
                        throw new IllegalStateException(
                                "Native session status requires its editing domain");
                    }
                    try {
                        domain.runExclusive(
                                () -> {
                                    if (!session.isOpen()
                                            || session.getSessionResource() != resource
                                            || session.getTransactionalEditingDomain() != domain
                                            || Thread.currentThread().isInterrupted()) {
                                        throw new IllegalStateException(
                                                "The captured status session is unavailable");
                                    }
                                    var commands = domain.getCommandStack();
                                    var undo = commands.getUndoCommand();
                                    var redo = commands.getRedoCommand();
                                    result.put("sessionName", resource.getURI().lastSegment());
                                    result.put("session_uri", resource.getURI().toString());
                                    result.put("session_modified", resource.isModified());
                                    result.put("can_undo", commands.canUndo());
                                    result.put("can_redo", commands.canRedo());
                                    result.put("undo_label", undo == null ? null : undo.getLabel());
                                    result.put("redo_label", redo == null ? null : redo.getLabel());
                                    List<Object> resources = new ArrayList<>();
                                    for (Resource semantic : session.getSemanticResources()) {
                                        if (semantic.getResourceSet() != domain.getResourceSet()) {
                                            throw new IllegalStateException(
                                                    "Status semantic resource belongs to another"
                                                            + " domain");
                                        }
                                        Map<String, Object> flags = new LinkedHashMap<>();
                                        flags.put(
                                                "uri",
                                                semantic.getURI() == null
                                                        ? null
                                                        : semantic.getURI().toString());
                                        flags.put("modified", semantic.isModified());
                                        resources.add(flags);
                                    }
                                    result.put("semantic_resources", resources);
                                    state.set(result);
                                });
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "Session status was interrupted", interrupted);
                    }
                });
        return AgentJson.write(state.get());
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String getConversationId(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("X-Conversation-Id");
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        String query = exchange.getRequestURI().getQuery();
        return extractQueryParam(query, "conversationId");
    }

    private static String extractQueryParam(String query, String key) {
        if (query == null || query.isBlank() || key == null || key.isBlank()) {
            return null;
        }
        String[] parts = query.split("&");
        for (String part : parts) {
            int idx = part.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String k = part.substring(0, idx);
            if (!k.equals(key)) {
                continue;
            }
            String value = part.substring(idx + 1);
            return value.isBlank() ? null : value;
        }
        return null;
    }

    private static void sendResponse(HttpExchange exchange, int code, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String formatResult(ExecutionResult result) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"success\": ").append(result.success()).append(",");
        String valueOut = result.valueForOutput();
        if (valueOut != null) {
            json.append("\"value\": \"").append(escapeJson(valueOut)).append("\",");
        }
        String messageOut = result.messageForOutput();
        if (messageOut != null) {
            json.append("\"message\": \"").append(escapeJson(messageOut)).append("\",");
        }
        java.util.List<String> logsOut = result.logsForOutput();
        if (!logsOut.isEmpty()) {
            json.append("\"logs\": [");
            for (int i = 0; i < logsOut.size(); i++) {
                if (i > 0) json.append(",");
                json.append("\"").append(escapeJson(logsOut.get(i))).append("\"");
            }
            json.append("],");
        }
        if (result.code() != null) {
            json.append("\"errorCode\": \"").append(result.code().name()).append("\",");
        }
        // Remove trailing comma
        if (json.charAt(json.length() - 1) == ',') {
            json.setLength(json.length() - 1);
        }
        json.append("}");
        return json.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
