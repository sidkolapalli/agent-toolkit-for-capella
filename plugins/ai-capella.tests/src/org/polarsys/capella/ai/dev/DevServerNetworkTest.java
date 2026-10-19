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

        HttpResponse<String> blocked = client.send(HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(5)).header("Origin", "https://example.invalid")
            .POST(HttpRequest.BodyPublishers.ofString("throw new IllegalStateException('must not run')"))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, blocked.statusCode());
        assertTrue(blocked.body().contains("non-browser"));
        assertFalse(blocked.headers().firstValue("Access-Control-Allow-Origin").isPresent());

        HttpResponse<String> local = client.send(HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals("Local command-line clients must still reach the endpoint", 405, local.statusCode());
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
