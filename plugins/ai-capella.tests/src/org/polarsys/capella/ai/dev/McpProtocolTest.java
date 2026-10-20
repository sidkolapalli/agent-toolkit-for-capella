package org.polarsys.capella.ai.dev;

import static org.junit.Assert.*;

import org.junit.Test;
import org.polarsys.capella.ai.agent.AgentJson;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wire behaviour from the MCP specification: modern (2026-07-28, per-request metadata and
 * server/discover) and legacy (initialize handshake) clients are both served on one endpoint. No
 * model is needed; tool calls without an open model are tool errors.
 */
public class McpProtocolTest {
    private static final String MODERN = "2026-07-28";

    @Test
    public void discoverListsSupportedVersionsCapabilitiesAndIdentity() {
        var response = modern("server/discover", Map.of(), MODERN, null);
        assertEquals(200, response.status());
        Map<?, ?> result = result(response);
        assertEquals("complete", result.get("resultType"));
        assertTrue(
                ((List<?>) result.get("supportedVersions"))
                        .containsAll(List.of(MODERN, "2025-11-25", "2025-06-18")));
        assertTrue(((Map<?, ?>) result.get("capabilities")).containsKey("tools"));
        assertEquals(
                "capella-ai",
                ((Map<?, ?>)
                                ((Map<?, ?>) result.get("_meta"))
                                        .get("io.modelcontextprotocol/serverInfo"))
                        .get("name"));
    }

    @Test
    public void modernRequestsGetCompleteResults() {
        var list = modern("tools/list", Map.of(), MODERN, null);
        assertEquals(200, list.status());
        assertEquals("complete", result(list).get("resultType"));
        assertFalse(((List<?>) result(list).get("tools")).isEmpty());
        var call =
                modern(
                        "tools/call",
                        Map.of("name", "model_summary", "arguments", Map.of()),
                        MODERN,
                        "model_summary");
        assertEquals(200, call.status());
        assertEquals("complete", result(call).get("resultType"));
        assertEquals(
                "No model open: a tool error, not a protocol error",
                Boolean.TRUE,
                result(call).get("isError"));
    }

    @Test
    public void cacheableResultsCarryTheRequiredCachingHints() {
        // Spec 2026-07-28 caching: discover and tools/list MUST carry ttlMs >= 0 and cacheScope.
        for (String method : List.of("server/discover", "tools/list")) {
            Map<?, ?> result = result(modern(method, Map.of(), MODERN, null));
            assertEquals(method, 0L, ((Number) result.get("ttlMs")).longValue());
            assertEquals(method, "private", result.get("cacheScope"));
        }
        var call =
                modern(
                        "tools/call",
                        Map.of("name", "model_summary", "arguments", Map.of()),
                        MODERN,
                        "model_summary");
        assertFalse("Tool results are not cacheable", result(call).containsKey("ttlMs"));
    }

    @Test
    public void headersMustMatchTheBody() {
        var missingName = modern("tools/call", Map.of("name", "model_summary"), MODERN, null);
        assertEquals(400, missingName.status());
        assertEquals(-32020, code(missingName));
        var wrongName =
                modern("tools/call", Map.of("name", "model_summary"), MODERN, "model_search");
        assertEquals(-32020, code(wrongName));
        Map<String, String> headers = headers(MODERN, "tools/list", null);
        headers.put("MCP-Protocol-Version", "2025-11-25");
        var mismatch = McpServer.handle(null, headers, body("tools/list", Map.of(), MODERN));
        assertEquals(400, mismatch.status());
        assertEquals(-32020, code(mismatch));
        Map<String, String> methodHeaders = headers(MODERN, "tools/call", "model_summary");
        var wrongMethod =
                McpServer.handle(null, methodHeaders, body("tools/list", Map.of(), MODERN));
        assertEquals(-32020, code(wrongMethod));
    }

    @Test
    public void base64EncodedNameHeadersAreDecodedBeforeComparison() {
        String encoded =
                "=?base64?"
                        + Base64.getEncoder()
                                .encodeToString("model_summary".getBytes(StandardCharsets.UTF_8))
                        + "?=";
        var call =
                modern(
                        "tools/call",
                        Map.of("name", "model_summary", "arguments", Map.of()),
                        MODERN,
                        encoded);
        assertEquals(200, call.status());
    }

    @Test
    public void unsupportedVersionsAndUnknownMethodsUseTheSpecifiedErrors() {
        var old = modern("tools/list", Map.of(), "1900-01-01", null);
        assertEquals(400, old.status());
        assertEquals(-32022, code(old));
        Map<?, ?> data = (Map<?, ?>) error(old).get("data");
        assertEquals("1900-01-01", data.get("requested"));
        assertTrue(((List<?>) data.get("supported")).contains(MODERN));
        var unknown = modern("resources/list", Map.of(), MODERN, null);
        assertEquals(404, unknown.status());
        assertEquals(-32601, code(unknown));
    }

    @Test
    public void legacyClientsNegotiateThroughInitialize() {
        var known =
                McpServer.handle(
                        null,
                        Map.of(),
                        AgentJson.write(
                                Map.of(
                                        "jsonrpc",
                                        "2.0",
                                        "id",
                                        1,
                                        "method",
                                        "initialize",
                                        "params",
                                        Map.of(
                                                "protocolVersion",
                                                "2025-06-18",
                                                "capabilities",
                                                Map.of()))));
        assertEquals(200, known.status());
        assertEquals("2025-06-18", result(known).get("protocolVersion"));
        var unknown =
                McpServer.handle(
                        null,
                        Map.of(),
                        AgentJson.write(
                                Map.of(
                                        "jsonrpc",
                                        "2.0",
                                        "id",
                                        2,
                                        "method",
                                        "initialize",
                                        "params",
                                        Map.of("protocolVersion", "2024-01-01"))));
        assertEquals(
                "The latest legacy version is offered instead",
                "2025-11-25",
                result(unknown).get("protocolVersion"));
        var note =
                McpServer.handle(
                        null,
                        Map.of(),
                        "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertEquals(202, note.status());
        assertNull(note.body());
        var badHeader =
                McpServer.handle(
                        null,
                        Map.of("mcp-protocol-version", "1999-01-01"),
                        "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}");
        assertEquals(400, badHeader.status());
        var legacyList =
                McpServer.handle(
                        null,
                        Map.of("MCP-Protocol-Version", "2025-06-18"),
                        "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\"}");
        assertEquals(200, legacyList.status());
    }

    private static McpServer.Response modern(
            String method, Map<String, Object> params, String version, String name) {
        return McpServer.handle(
                null, headers(version, method, name), body(method, params, version));
    }

    private static Map<String, String> headers(String version, String method, String name) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("MCP-Protocol-Version", version);
        headers.put("Mcp-Method", method);
        if (name != null) headers.put("Mcp-Name", name);
        return headers;
    }

    private static String body(String method, Map<String, Object> params, String version) {
        Map<String, Object> withMeta = new LinkedHashMap<>(params);
        withMeta.put(
                "_meta",
                Map.of(
                        "io.modelcontextprotocol/protocolVersion",
                        version,
                        "io.modelcontextprotocol/clientInfo",
                        Map.of("name", "test", "version", "1"),
                        "io.modelcontextprotocol/clientCapabilities",
                        Map.of()));
        return AgentJson.write(
                Map.of("jsonrpc", "2.0", "id", 9, "method", method, "params", withMeta));
    }

    private static Map<?, ?> result(McpServer.Response response) {
        return (Map<?, ?>) ((Map<?, ?>) AgentJson.parse(response.body(), 400_000)).get("result");
    }

    private static Map<?, ?> error(McpServer.Response response) {
        return (Map<?, ?>) ((Map<?, ?>) AgentJson.parse(response.body(), 400_000)).get("error");
    }

    private static int code(McpServer.Response response) {
        return ((Number) error(response).get("code")).intValue();
    }
}
