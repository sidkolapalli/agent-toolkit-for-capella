package org.polarsys.capella.ai.dev;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import org.junit.After;
import org.junit.Test;

/** The optional development interface must remain local and release its workers. */
public class DevServerNetworkTest {
    @After
    public void stopServer() {
        DevServer.stop();
    }

    @Test
    public void serverBindsOnlyLoopbackAndRejectsBrowserScriptRequests() throws Exception {
        DevServer.start(0);
        InetSocketAddress address = DevServer.getAddress();
        assertNotNull(address);
        assertTrue("The unauthenticated dev server must not listen on network interfaces",
            address.getAddress().isLoopbackAddress());
        URI endpoint = new URI("http", null, address.getAddress().getHostAddress(), address.getPort(),
            "/execute", null, null);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        HttpResponse<String> blocked = client.send(request(endpoint).header("Origin", "https://example.invalid")
            .POST(HttpRequest.BodyPublishers.ofString("throw new IllegalStateException('must not run')"))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, blocked.statusCode());
        assertTrue(blocked.body().contains("non-browser"));
        assertFalse(blocked.headers().firstValue("Access-Control-Allow-Origin").isPresent());

        HttpResponse<String> local = client.send(request(endpoint).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals("Local command-line clients must still reach the endpoint", 405, local.statusCode());
    }

    @Test
    public void everyEndpointNeedsTheBearerTokenWrittenForLocalClients() throws Exception {
        DevServer.start(0);
        InetSocketAddress address = DevServer.getAddress();
        String token = DevServer.token();
        assertTrue("A generated token is long and URL-safe", token.matches("[A-Za-z0-9_-]{43}"));
        java.nio.file.Path file = DevServer.tokenFile(address.getPort());
        assertEquals(token, java.nio.file.Files.readString(file).trim());
        HttpClient client = HttpClient.newHttpClient();
        for (String path : new String[] {"/status", "/execute", "/mcp"}) {
            URI endpoint = new URI("http", null, address.getAddress().getHostAddress(), address.getPort(), path, null, null);
            for (String header : new String[] {null, "Bearer wrong", "Bearer " + token + "x", token}) {
                var request = HttpRequest.newBuilder().uri(endpoint).timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString("throw new IllegalStateException('must not run')"));
                if (header != null) request.header("Authorization", header);
                HttpResponse<String> refused = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(path + " with " + header, 401, refused.statusCode());
                assertEquals("Bearer", refused.headers().firstValue("WWW-Authenticate").orElse(""));
            }
        }
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals("Only the owner can read the token", "rw-------",
                java.nio.file.attribute.PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(file)));
            assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(file.getParent())));
        }
        DevServer.stop();
        assertFalse("The token file is removed with the server", java.nio.file.Files.exists(file));
    }

    @Test
    public void mcpEndpointSpeaksJsonRpcOverPostOnlyAndRejectsBrowsers() throws Exception {
        DevServer.start(0);
        InetSocketAddress address = DevServer.getAddress();
        URI endpoint = new URI("http", null, address.getAddress().getHostAddress(), address.getPort(), "/mcp", null, null);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String initialize = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}";

        HttpResponse<String> ok = client.send(request(endpoint)
            .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(initialize)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, ok.statusCode());
        assertTrue(ok.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertTrue(ok.body().contains("\"protocolVersion\""));

        HttpResponse<String> note = client.send(request(endpoint)
            .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals("Notifications are accepted without a body", 202, note.statusCode());

        HttpResponse<String> stream = client.send(request(endpoint).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals("No server-initiated stream is offered", 405, stream.statusCode());

        HttpResponse<String> browser = client.send(request(endpoint)
            .header("Origin", "https://example.invalid").POST(HttpRequest.BodyPublishers.ofString(initialize)).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(403, browser.statusCode());
    }

    @Test
    public void evalEndpointStaysOffUnlessCapellaWasStartedForEvaluation() throws Exception {
        String previous = System.clearProperty("capella.ai.eval");
        try {
            DevServer.start(0);
            InetSocketAddress address = DevServer.getAddress();
            URI endpoint = new URI("http", null, address.getAddress().getHostAddress(), address.getPort(), "/eval", null, null);
            HttpResponse<String> refused = HttpClient.newHttpClient().send(request(endpoint).POST(HttpRequest.BodyPublishers.ofString("{\"tasks\":[]}")).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals("Auto-approved evaluation needs an explicit opt-in", 403, refused.statusCode());
            assertTrue(refused.body().contains("capella.ai.eval"));
        } finally {
            if (previous != null) System.setProperty("capella.ai.eval", previous);
        }
    }

    @Test
    public void stoppingServerShutsDownWorkersAndAllowsRestart() throws Exception {
        DevServer.start(0);
        Field field = DevServer.class.getDeclaredField("requestExecutor");
        field.setAccessible(true);
        ExecutorService executor = (ExecutorService) field.get(null);
        assertNotNull(executor);

        DevServer.stop();
        assertTrue(executor.isShutdown());
        assertNull(DevServer.getAddress());
        assertFalse(DevServer.isRunning());
        DevServer.start(0);
        assertTrue(DevServer.isRunning());
        assertNotSame(executor, field.get(null));
    }

    private static HttpRequest.Builder request(URI endpoint) {
        return HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + DevServer.token());
    }

    @Test
    public void invalidPortsDoNotCreateAListener() {
        for (int port : new int[] {-1, 65536}) {
            try {
                DevServer.start(port);
                fail("Out-of-range port must be rejected");
            } catch (IllegalArgumentException expected) {
                assertFalse(DevServer.isRunning());
                assertNull(DevServer.getAddress());
            }
        }
    }
}
