package org.polarsys.capella.ai.llm;

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Actual localhost HTTP transport; no global preferences or provider credentials. */
public class LLMTransportSafetyTest {
    @Test(timeout = 3000)
    public void quotaErrorsAndLongRetryAfterDoNotWaitOrRepeatRequests() throws Exception {
        try (LocalProvider provider = new LocalProvider((attempt, socket) -> respond(socket, 429,
            "Retry-After: 3600\r\n", "{\"error\":{\"code\":\"insufficient_quota\"}}"))) {
            assertEquals(429, send(provider.request(500)).statusCode());
            assertEquals(1, provider.requests.get());
        }
        try (LocalProvider provider = new LocalProvider((attempt, socket) -> respond(socket, 503,
            "Retry-After: 3600\r\n", "temporary"))) {
            assertEquals(503, send(provider.request(500)).statusCode());
            assertEquals(1, provider.requests.get());
        }
    }

    @Test(timeout = 4000)
    public void aTransientFailureRetriesWithinOneTotalDeadline() throws Exception {
        try (LocalProvider provider = new LocalProvider((attempt, socket) ->
                respond(socket, attempt == 1 ? 503 : 200, "", "fixture"))) {
            assertEquals(200, send(provider.request(2500)).statusCode());
            assertEquals(2, provider.requests.get());
        }
    }

    @Test(timeout = 3000)
    public void interruptingRetryBackoffStopsWithoutAnotherRequest() throws Exception {
        CountDownLatch responded = new CountDownLatch(1);
        try (LocalProvider provider = new LocalProvider((attempt, socket) -> {
            respond(socket, 503, "", "temporary");
            responded.countDown();
        })) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<Boolean> interrupted = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { send(provider.request(5000)); }
                catch (Throwable error) { failure.set(error); interrupted.set(Thread.currentThread().isInterrupted()); }
            }, "local-provider-cancellation-test");
            worker.setDaemon(true);
            worker.start();
            assertTrue(responded.await(1, TimeUnit.SECONDS));
            worker.interrupt();
            worker.join(1000);
            assertFalse(worker.isAlive());
            assertTrue(failure.get() instanceof InterruptedException);
            assertEquals(Boolean.TRUE, interrupted.get());
            assertEquals(1, provider.requests.get());
        }
    }

    @Test(timeout = 3000)
    public void aCancelledStreamDoesNotContactTheProvider() throws Exception {
        try (LocalProvider provider = new LocalProvider((attempt, socket) -> respond(socket, 200, "", "unused"))) {
            LLMService.StreamHandle handle = stream(provider.request(1000), true);
            assertThrows(CancellationException.class, () -> handle.completion().get(1, TimeUnit.SECONDS));
            assertEquals(0, provider.requests.get());
        }
    }

    @Test(timeout = 3000)
    public void aTruncatedChatStreamCannotReturnPartialExecutableCode() throws Exception {
        String data = "data: {\"choices\":[{\"delta\":{\"content\":\"```groovy partial\"}}]}\n\n";
        try (LocalProvider provider = new LocalProvider((attempt, socket) ->
                respond(socket, 200, "Content-Type: text/event-stream\r\n", data))) {
            ExecutionException failure = assertThrows(ExecutionException.class,
                () -> stream(provider.request(1000), false).completion().get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause().getMessage().contains("finish reason"));
        }
    }

    @Test(timeout = 3000)
    public void streamingHttpFailureHasSafeRecoveryGuidance() throws Exception {
        try (LocalProvider provider = new LocalProvider((attempt, socket) ->
                respond(socket, 401, "", "<html>fixture-secret</html>"))) {
            ExecutionException failure = assertThrows(ExecutionException.class,
                () -> stream(provider.request(1000), false).completion().get(2, TimeUnit.SECONDS));
            String message = LLMService.userFacingError(failure);
            assertTrue(message.contains("API key"));
            assertFalse(message.contains("fixture-secret"));
            assertFalse(message.contains("html"));
        }
    }

    @Test(timeout = 3000)
    public void idleStreamingBodyStillHasABoundedTimeout() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (LocalProvider provider = new LocalProvider((attempt, socket) -> {
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n"
                .getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            release.await(2, TimeUnit.SECONDS);
        })) {
            LLMService.StreamHandle handle = stream(provider.request(200), false);
            ExecutionException failure = assertThrows(ExecutionException.class,
                () -> handle.completion().get(1, TimeUnit.SECONDS));
            assertTrue(LLMService.userFacingError(failure).contains("Timeout"));
        } finally {
            release.countDown();
        }
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        Method method = LLMService.class.getDeclaredMethod("sendWithRetry", HttpRequest.class,
            String.class, String.class, String.class);
        method.setAccessible(true);
        try {
            return (HttpResponse<String>) method.invoke(LLMService.get(), request, "local", "fixture", "fixture");
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            throw new AssertionError(error.getCause());
        }
    }

    private static LLMService.StreamHandle stream(HttpRequest request, boolean cancelled) throws Exception {
        Class<?> parserType = Arrays.stream(LLMService.class.getDeclaredClasses())
            .filter(type -> type.getSimpleName().equals("ChatStreamParser")).findFirst().orElseThrow();
        Class<?> extractorType = Arrays.stream(LLMService.class.getDeclaredClasses())
            .filter(type -> type.getSimpleName().equals("DeltaExtractor")).findFirst().orElseThrow();
        Constructor<?> constructor = parserType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Method method = LLMService.class.getDeclaredMethod("streamSse", HttpRequest.class,
            LLMService.StreamListener.class, extractorType);
        method.setAccessible(true);
        return (LLMService.StreamHandle) method.invoke(LLMService.get(), request, new LLMService.StreamListener() {
            public boolean isCancelled() { return cancelled; }
            public void onChunk(String text) {}
        }, constructor.newInstance());
    }

    private static void respond(Socket socket, int status, String headers, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " Fixture\r\nConnection: close\r\n" + headers +
            "Content-Length: " + bytes.length + "\r\n\r\n";
        socket.getOutputStream().write(head.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();
    }

    @FunctionalInterface
    private interface Responder { void respond(int attempt, Socket socket) throws Exception; }

    private static final class LocalProvider implements AutoCloseable {
        final AtomicInteger requests = new AtomicInteger();
        final ServerSocket listener;
        final Thread server;
        volatile boolean closed;

        LocalProvider(Responder responder) throws Exception {
            listener = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            server = new Thread(() -> {
                while (!closed) {
                    try {
                        Socket socket = listener.accept();
                        Thread connection = new Thread(() -> {
                            try (socket) {
                                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                                String line;
                                while ((line = reader.readLine()) != null && !line.isEmpty()) {}
                                responder.respond(requests.incrementAndGet(), socket);
                            } catch (Exception ignored) {}
                        }, "local-provider-fixture");
                        connection.setDaemon(true);
                        connection.start();
                    } catch (Exception ignored) { break; }
                }
            }, "local-provider-listener");
            server.setDaemon(true);
            server.start();
        }

        HttpRequest request(int timeoutMs) {
            return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + listener.getLocalPort() + "/fixture"))
                .timeout(Duration.ofMillis(timeoutMs)).GET().build();
        }

        public void close() throws Exception {
            closed = true;
            listener.close();
            server.join(500);
        }
    }
}
