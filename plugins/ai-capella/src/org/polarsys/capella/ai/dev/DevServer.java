package org.polarsys.capella.ai.dev;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import org.eclipse.sirius.business.api.session.Session;
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
 * </ul>
 *
 * <p>Start from Eclipse: Window → Show View → Other → Capella AI Dev Server
 * <p>Or programmatically: DevServer.start(8765)
 */
public final class DevServer {

    private static HttpServer server;
    private static final int DEFAULT_PORT = 8765;

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
            ExecutionResult result = GroovyExecutor.execute(session, code);
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
            ScriptRouter.Result result = ScriptRouter.handleChat(
                session,
                prompt
            );

            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"success\": ").append(result.success()).append(",");
            json
                .append("\"reply\": \"")
                .append(escapeJson(result.reply))
                .append("\",");
            json.append("\"codeExecuted\": ").append(result.codeExecuted);

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
        String prompt = CodeExecutionRouter.buildCodeExecutionPrompt(session);

        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"prompt\": \"").append(escapeJson(prompt)).append("\"");
        json.append("}");

        sendResponse(exchange, 200, json.toString());
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
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
        if (result.value() != null) {
            json
                .append("\"value\": \"")
                .append(escapeJson(String.valueOf(result.value())))
                .append("\",");
        }
        if (result.message() != null) {
            json
                .append("\"message\": \"")
                .append(escapeJson(result.message()))
                .append("\",");
        }
        if (result.logs() != null && !result.logs().isEmpty()) {
            json.append("\"logs\": [");
            for (int i = 0; i < result.logs().size(); i++) {
                if (i > 0) json.append(",");
                json
                    .append("\"")
                    .append(escapeJson(result.logs().get(i)))
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
}
