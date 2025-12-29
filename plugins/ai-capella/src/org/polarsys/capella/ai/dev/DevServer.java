package org.polarsys.capella.ai.dev;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.business.api.session.SessionManager;
import org.polarsys.capella.ai.session.SelectionContext;
import org.polarsys.capella.ai.preferences.LLMPreferences;
import org.polarsys.capella.ai.scripting.CodeExecutionRouter;
import org.polarsys.capella.ai.scripting.ExecutionResult;
import org.polarsys.capella.ai.scripting.GroovyExecutor;
import org.polarsys.capella.ai.scripting.ScriptRouter;
import org.polarsys.capella.ai.session.SessionBridge;

/**
 * Development HTTP server for testing AI code execution without Eclipse UI.
 *
 * <p>Exposes endpoints:
 * <ul>
 *   <li>POST /execute - Execute Groovy code directly</li>
 *   <li>POST /chat - Send prompt to LLM and execute generated code</li>
 *   <li>GET /status - Check server and session status</li>
 *   <li>GET /prompt - Get the system prompt for code generation</li>
 *   <li>GET /context - Get current selection + active diagram</li>
 *   <li>GET /config - View dev server configuration</li>
 *   <li>GET /debug - View debug information</li>
 * </ul>
 *
 * <p>Start from Eclipse: Window → Show View → Other → Capella AI Dev Server
 * <p>Or programmatically: DevServer.start(8765)
 */
public final class DevServer {

    private static HttpServer server;
    private static final int DEFAULT_PORT = 8765;
    private static final Logger LOG = Logger.getLogger(DevServer.class.getName());

    private DevServer() {}

    /**
     * Start the dev server on the default port (8765).
     */
    public static synchronized void start() {
        start(DEFAULT_PORT);
    }

    /**
     * Start the dev server on the specified port.
     */
    public static synchronized void start(int port) {
        if (server != null) {
            System.out.println("[DevServer] Already running on port " + port);
            return;
        }

        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.setExecutor(Executors.newFixedThreadPool(4));

            // Endpoints
            server.createContext("/execute", DevServer::handleExecute);
            server.createContext("/chat", DevServer::handleChat);
            server.createContext("/status", DevServer::handleStatus);
            server.createContext("/prompt", DevServer::handlePrompt);
            server.createContext("/debug", DevServer::handleDebug);
            server.createContext("/config", DevServer::handleConfig);
            server.createContext("/context", DevServer::handleContext);

            server.start();
            System.out.println(
                "[DevServer] Started on http://localhost:" + port
            );
            System.out.println("[DevServer] Endpoints:");
            System.out.println("  POST /execute - Execute Groovy code");
            System.out.println(
                "  POST /chat    - Send prompt to LLM, execute result"
            );
            System.out.println("  GET  /status  - Check server/session status");
            System.out.println("  GET  /prompt  - Get system prompt for LLM");
            System.out.println(
                "  GET  /context - Get selection + active diagram context"
            );
            System.out.println("  GET  /config  - View configuration");
            System.out.println("  GET  /debug   - View debug information");
        } catch (IOException e) {
            System.err.println(
                "[DevServer] Failed to start: " + e.getMessage()
            );
        }
    }

    /**
     * Stop the dev server.
     */
    public static synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            System.out.println("[DevServer] Stopped");
        }
    }

    /**
     * Check if server is running.
     */
    public static boolean isRunning() {
        return server != null;
    }

    /**
     * POST /execute - Execute Groovy code directly
     * Body: Groovy code to execute
     * Returns: JSON with result
     */
    private static void handleExecute(HttpExchange exchange)
        throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\": \"Method not allowed\"}");
            return;
        }

        Session session = SessionBridge.getActiveSession();
        if (session == null) {
            sendResponse(
                exchange,
                400,
                "{\"error\": \"No active Capella session. Open a model first.\"}"
            );
            return;
        }

        String code = readBody(exchange);
        if (code == null || code.isBlank()) {
            sendResponse(exchange, 400, "{\"error\": \"No code provided\"}");
            return;
        }

        try {
            String conversationId = getConversationId(exchange);
            String sessionKey = GroovyExecutor.buildSessionKey(
                session,
                conversationId
            );
            SelectionContext ctx = SessionBridge.getSelectionContext();
            java.util.HashMap<String, Object> extraBindings =
                new java.util.HashMap<>();
            extraBindings.put(
                "selection",
                ctx != null ? ctx.getSelectedElements() : java.util.List.of()
            );
            extraBindings.put(
                "activeDiagram",
                ctx != null ? ctx.getActiveDiagram().orElse(null) : null
            );
            ExecutionResult result = GroovyExecutor.execute(
                session,
                code,
                extraBindings,
                sessionKey
            );
            String json = formatResult(result);
            sendResponse(exchange, result.success() ? 200 : 400, json);
        } catch (Exception e) {
            sendResponse(
                exchange,
                500,
                "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}"
            );
        }
    }

    /**
     * POST /chat - Send prompt to LLM and execute generated code
     * Body: User message/prompt
     * Returns: JSON with LLM response and execution result
     */
    private static void handleChat(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\": \"Method not allowed\"}");
            return;
        }

        Session session = SessionBridge.getActiveSession();
        if (session == null) {
            sendResponse(
                exchange,
                400,
                "{\"error\": \"No active Capella session. Open a model first.\"}"
            );
            return;
        }

        String prompt = readBody(exchange);
        if (prompt == null || prompt.isBlank()) {
            sendResponse(exchange, 400, "{\"error\": \"No prompt provided\"}");
            return;
        }

        try {
            String conversationId = getConversationId(exchange);
            ScriptRouter.Result result = ScriptRouter.handleChat(
                session,
                prompt,
                List.of(),
                conversationId
            );

            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"success\": ").append(result.success()).append(",");
            json
                .append("\"reply\": \"")
                .append(escapeJson(result.reply))
                .append("\",");
            json.append("\"codeExecuted\": ").append(result.codeExecuted);
            if (result.codeExecuted) {
                json.append(",\"attempts\": ").append(result.attempts);
            }

            if (result.executionResult != null) {
                json
                    .append(",\"execution\": ")
                    .append(formatResult(result.executionResult));
            }
            if (result.error != null) {
                json
                    .append(",\"error\": \"")
                    .append(escapeJson(result.error))
                    .append("\"");
            }
            json.append("}");

            sendResponse(
                exchange,
                result.success() ? 200 : 400,
                json.toString()
            );
        } catch (Exception e) {
            sendResponse(
                exchange,
                500,
                "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}"
            );
        }
    }

    /**
     * GET /status - Check server and session status
     */
    private static void handleStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\": \"Method not allowed\"}");
            return;
        }

        Session session = SessionBridge.getActiveSession();
        boolean hasSession = session != null;
        String sessionName = hasSession
            ? session.getSessionResource().getURI().lastSegment()
            : null;

        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"running\": true,");
        json.append("\"hasSession\": ").append(hasSession);
        if (sessionName != null) {
            json
                .append(",\"sessionName\": \"")
                .append(escapeJson(sessionName))
                .append("\"");
        }
        json.append("}");

        sendResponse(exchange, 200, json.toString());
    }

    /**
     * GET /prompt - Get the system prompt for code generation
     */
    private static void handlePrompt(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\": \"Method not allowed\"}");
            return;
        }

        Session session = SessionBridge.getActiveSession();
        String conversationId = getConversationId(exchange);
        String sessionKey = GroovyExecutor.buildSessionKey(
            session,
            conversationId
        );
        String prompt = CodeExecutionRouter.buildCodeExecutionPrompt(
            session,
            sessionKey,
            SessionBridge.getSelectionContext()
        );

        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"prompt\": \"").append(escapeJson(prompt)).append("\"");
        json.append("}");

        sendResponse(exchange, 200, json.toString());
    }

    /**
     * GET /context - return current UI selection + active diagram context.
     */
    private static void handleContext(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\": \"Method not allowed\"}");
            return;
        }

        SelectionContext ctx = SessionBridge.getSelectionContext();
        boolean loggedNameError = false;
        boolean loggedIdError = false;
        StringBuilder json = new StringBuilder();
        json.append("{");
        boolean hasDiag = ctx != null && ctx.hasDiagram();
        boolean hasSel = ctx != null && ctx.hasSelection();
        json.append("\"hasDiagram\": ").append(hasDiag).append(",");
        json.append("\"hasSelection\": ").append(hasSel);
        if (hasDiag) {
            json
                .append(",\"activeDiagram\":{")
                .append("\"name\":\"")
                .append(escapeJson(ctx.getActiveDiagramName()))
                .append("\",\"uid\":\"")
                .append(escapeJson(ctx.getActiveDiagramUid()))
                .append("\",\"type\":\"")
                .append(escapeJson(ctx.getActiveDiagramType()))
                .append("\"}");
        }
        if (hasSel) {
            json.append(",\"selection\":[");
            List<org.eclipse.emf.ecore.EObject> sel = ctx.getSelectedElements();
            for (int i = 0; i < sel.size(); i++) {
                if (i > 0) json.append(",");
                org.eclipse.emf.ecore.EObject eo = sel.get(i);
                String type = eo != null && eo.eClass() != null
                    ? eo.eClass().getName()
                    : "EObject";
                String name = "";
                try {
                    if (eo != null && eo.eClass() != null) {
                        var f = eo.eClass().getEStructuralFeature("name");
                        if (f != null) {
                            Object v = eo.eGet(f);
                            if (v != null) name = v.toString();
                        }
                    }
                } catch (Exception ex) {
                    if (!loggedNameError) {
                        loggedNameError = true;
                        LOG.log(
                            Level.FINE,
                            "Failed to read selection element 'name' feature",
                            ex
                        );
                    }
                }
                String id = "";
                try {
                    if (eo != null && eo.eClass() != null) {
                        var f = eo.eClass().getEStructuralFeature("id");
                        if (f != null) {
                            Object v = eo.eGet(f);
                            if (v != null) id = v.toString();
                        }
                    }
                } catch (Exception ex) {
                    if (!loggedIdError) {
                        loggedIdError = true;
                        LOG.log(
                            Level.FINE,
                            "Failed to read selection element 'id' feature",
                            ex
                        );
                    }
                }
                json
                    .append("{\"type\":\"")
                    .append(escapeJson(type))
                    .append("\",\"name\":\"")
                    .append(escapeJson(name))
                    .append("\",\"id\":\"")
                    .append(escapeJson(id))
                    .append("\"}");
            }
            json.append("]");
        }
        json.append("}");
        sendResponse(exchange, 200, json.toString());
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String getConversationId(HttpExchange exchange) {
        String header = exchange
            .getRequestHeaders()
            .getFirst("X-Conversation-Id");
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

    private static void sendResponse(
        HttpExchange exchange,
        int code,
        String body
    ) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
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
            json
                .append("\"value\": \"")
                .append(escapeJson(valueOut))
                .append("\",");
        }
        String messageOut = result.messageForOutput();
        if (messageOut != null) {
            json
                .append("\"message\": \"")
                .append(escapeJson(messageOut))
                .append("\",");
        }
        java.util.List<String> logsOut = result.logsForOutput();
        if (!logsOut.isEmpty()) {
            json.append("\"logs\": [");
            for (int i = 0; i < logsOut.size(); i++) {
                if (i > 0) json.append(",");
                json
                    .append("\"")
                    .append(escapeJson(logsOut.get(i)))
                    .append("\"");
            }
            json.append("],");
        }
        if (result.code() != null) {
            json
                .append("\"errorCode\": \"")
                .append(result.code().name())
                .append("\",");
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
        return s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    /**
     * GET/POST /config - Get or set LLM configuration
     * GET: Returns current config
     * POST: Set config (JSON body with provider, baseUrl, model, apiKey)
     */
    private static void handleConfig(HttpExchange exchange) throws IOException {
        if ("GET".equals(exchange.getRequestMethod())) {
            StringBuilder json = new StringBuilder();
            json.append("{");
            json
                .append("\"provider\": \"")
                .append(escapeJson(LLMPreferences.getProvider()))
                .append("\",");
            json
                .append("\"baseUrl\": \"")
                .append(escapeJson(LLMPreferences.getBaseUrl()))
                .append("\",");
            json
                .append("\"model\": \"")
                .append(escapeJson(LLMPreferences.getModel()))
                .append("\",");
            json
                .append("\"maxTokens\": ")
                .append(LLMPreferences.getMaxTokens())
                .append(",");
            json
                .append("\"temperature\": ")
                .append(LLMPreferences.getTemperature())
                .append(",");
            json
                .append("\"hasApiKey\": ")
                .append(!LLMPreferences.getApiKey().isEmpty());
            json.append("}");
            sendResponse(exchange, 200, json.toString());
            return;
        }

        if ("POST".equals(exchange.getRequestMethod())) {
            String body = readBody(exchange);
            try {
                // Simple JSON parsing for config
                if (body.contains("\"provider\"")) {
                    String provider = extractJsonString(body, "provider");
                    if (provider != null) LLMPreferences.setProvider(provider);
                }
                if (body.contains("\"baseUrl\"")) {
                    String baseUrl = extractJsonString(body, "baseUrl");
                    if (baseUrl != null) LLMPreferences.setBaseUrl(baseUrl);
                }
                if (body.contains("\"model\"")) {
                    String model = extractJsonString(body, "model");
                    if (model != null) LLMPreferences.setModel(model);
                }
                if (body.contains("\"apiKey\"")) {
                    String apiKey = extractJsonString(body, "apiKey");
                    if (apiKey != null) LLMPreferences.setApiKey(apiKey);
                }
                if (body.contains("\"maxTokens\"")) {
                    String maxTokens = extractJsonString(body, "maxTokens");
                    if (maxTokens != null) LLMPreferences.setMaxTokens(
                        Integer.parseInt(maxTokens)
                    );
                }
                LLMPreferences.flush();
                sendResponse(exchange, 200, "{\"success\": true}");
            } catch (Exception e) {
                sendResponse(
                    exchange,
                    400,
                    "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}"
                );
            }
            return;
        }

        sendResponse(exchange, 405, "{\"error\": \"Method not allowed\"}");
    }

    private static String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int colonIdx = json.indexOf(":", idx);
        if (colonIdx < 0) return null;
        int startQuote = json.indexOf("\"", colonIdx);
        if (startQuote < 0) return null;
        int endQuote = json.indexOf("\"", startQuote + 1);
        if (endQuote < 0) return null;
        return json.substring(startQuote + 1, endQuote);
    }

    /**
     * GET /debug - Debug session manager state
     */
    private static void handleDebug(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\": \"Method not allowed\"}");
            return;
        }

        StringBuilder json = new StringBuilder();
        json.append("{");

        try {
            var sessions = SessionManager.INSTANCE.getSessions();
            json
                .append("\"sessionCount\": ")
                .append(sessions.size())
                .append(",");
            json.append("\"sessions\": [");
            int i = 0;
            for (Session s : sessions) {
                if (i > 0) json.append(",");
                json.append("{");
                json
                    .append("\"isOpen\": ")
                    .append(s != null && s.isOpen())
                    .append(",");
                if (s != null && s.getSessionResource() != null) {
                    json
                        .append("\"uri\": \"")
                        .append(
                            escapeJson(
                                s.getSessionResource().getURI().toString()
                            )
                        )
                        .append("\"");
                } else {
                    json.append("\"uri\": null");
                }
                json.append("}");
                i++;
            }
            json.append("]");
        } catch (Exception e) {
            json
                .append("\"error\": \"")
                .append(escapeJson(e.getMessage()))
                .append("\"");
        }

        json.append("}");
        sendResponse(exchange, 200, json.toString());
    }
}
