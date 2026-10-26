package com.sidkolapalli.capella.ai.codex;

import static org.junit.Assert.*;

import org.junit.Test;
import com.sidkolapalli.capella.ai.agent.AgentJson;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Loopback streams and a fixed Java child process; never starts Codex or accesses a provider. */
public class CodexAppServerTest {
    @Test
    public void correlatesOutOfOrderRequestsWithoutConsumingNotifications() throws Exception {
        try (Wire wire = new Wire()) {
            Future<Map<String, Object>> first =
                    wire.request("first", Map.of("text", "Driver's \"display\"\n🚗"));
            Map<String, Object> a = wire.outbound();
            Future<Map<String, Object>> second = wire.request("second", Map.of());
            Map<String, Object> b = wire.outbound();
            assertEquals("Driver's \"display\"\n🚗", ((Map<?, ?>) a.get("params")).get("text"));
            wire.send(
                    Map.of(
                            "method",
                            "item/agentMessage/delta",
                            "params",
                            Map.of("delta", "hello")));
            wire.send(Map.of("id", b.get("id"), "result", Map.of("value", "second")));
            wire.send(Map.of("id", a.get("id"), "result", Map.of("value", "first")));
            assertEquals("first", first.get(2, TimeUnit.SECONDS).get("value"));
            assertEquals("second", second.get(2, TimeUnit.SECONDS).get("value"));
            assertEquals(
                    "item/agentMessage/delta",
                    wire.server.nextEvent(null, Duration.ofSeconds(1)).get("method"));
            assertNull(wire.server.nextEvent(null, Duration.ZERO));
        }
    }

    @Test
    public void serverRequestsRemainQueuedUntilCallerRespondsOrRejects() throws Exception {
        try (Wire wire = new Wire()) {
            Map<String, Object> tool =
                    Map.of(
                            "id",
                            "approval-1",
                            "method",
                            "item/tool/call",
                            "params",
                            Map.of("arguments", Map.of("id", "native-id")));
            wire.send(tool);
            assertEquals(tool, wire.server.nextEvent(null, Duration.ofSeconds(1)));
            assertNull(wire.output.poll(50, TimeUnit.MILLISECONDS));
            wire.server.respond("approval-1", Map.of("success", true));
            assertEquals(
                    Map.of("id", "approval-1", "result", Map.of("success", true)), wire.outbound());
            wire.server.reject(9L, -32601, "Tool unavailable");
            assertEquals(
                    Map.of(
                            "id",
                            9L,
                            "error",
                            Map.of("code", -32601L, "message", "Tool unavailable")),
                    wire.outbound());
            wire.server.notify("initialized", Map.of());
            assertEquals(Map.of("method", "initialized", "params", Map.of()), wire.outbound());
        }
    }

    @Test
    public void cancellationAbandonsOnlyItsRequestAndLateReplyIsIgnored() throws Exception {
        try (Wire wire = new Wire()) {
            AtomicBoolean cancelled = new AtomicBoolean();
            Future<Map<String, Object>> first =
                    wire.workers.submit(
                            () -> wire.server.request("turn/start", Map.of(), cancelled::get));
            Map<String, Object> a = wire.outbound();
            wire.server.notify("initialized", Map.of());
            wire
                    .outbound(); // Writer ordering proves the first frame completed before
                                 // cancellation.
            cancelled.set(true);
            assertTrue(error(first) instanceof CancellationException);
            wire.send(Map.of("id", a.get("id"), "result", Map.of("late", true)));
            Future<Map<String, Object>> next = wire.request("thread/read", Map.of());
            Map<String, Object> b = wire.outbound();
            wire.send(Map.of("id", b.get("id"), "result", Map.of("ok", true)));
            assertEquals(Boolean.TRUE, next.get(2, TimeUnit.SECONDS).get("ok"));
            assertNull(wire.server.nextEvent(null, Duration.ofMillis(50)));
        }
    }

    @Test
    public void interruptedWaitRetainsInterruptFlag() throws Exception {
        try (Wire wire = new Wire()) {
            AtomicReference<Throwable> error = new AtomicReference<>();
            AtomicBoolean flag = new AtomicBoolean();
            Thread caller =
                    new Thread(
                            () -> {
                                try {
                                    wire.server.request("wait", Map.of(), null);
                                } catch (Exception stopped) {
                                    error.set(stopped);
                                    flag.set(Thread.currentThread().isInterrupted());
                                }
                            });
            caller.start();
            wire.outbound();
            wire.server.notify("initialized", Map.of());
            wire.outbound();
            caller.interrupt();
            caller.join(1500);
            assertFalse(caller.isAlive());
            assertTrue(error.get() instanceof InterruptedException);
            assertTrue(flag.get());
        }
    }

    @Test
    public void requestDeadlineDoesNotTurnLateResponsesIntoEvents() throws Exception {
        try (Wire wire = new Wire(Duration.ofMillis(150))) {
            Future<Map<String, Object>> call = wire.request("wait", Map.of());
            Map<String, Object> sent = wire.outbound();
            assertTrue(error(call) instanceof TimeoutException);
            wire.send(Map.of("id", sent.get("id"), "result", Map.of()));
            assertNull(wire.server.nextEvent(null, Duration.ofMillis(50)));
        }
    }

    @Test
    public void rpcErrorsAndStderrNeverAppearAsRawErrorMessages() throws Exception {
        try (Wire wire = new Wire()) {
            Future<Map<String, Object>> call = wire.request("wait", Map.of());
            Map<String, Object> sent = wire.outbound();
            wire.send(
                    Map.of(
                            "id",
                            sent.get("id"),
                            "error",
                            Map.of(
                                    "code",
                                    -32000,
                                    "message",
                                    "sk-private-test-secret /private/model/name")));
            Throwable failure = error(call);
            assertTrue(failure instanceof IOException);
            assertTrue(failure.getMessage().contains("-32000"));
            assertFalse(failure.toString().contains("private"));
            assertNull(failure.getCause());
        }
    }

    @Test
    public void eofCompletesEveryPendingRequestAndRetainsPriorValidEvents() throws Exception {
        try (Wire wire = new Wire()) {
            Future<Map<String, Object>> call = wire.request("wait", Map.of());
            wire.outbound();
            wire.send(Map.of("method", "turn/completed", "params", Map.of("status", "completed")));
            wire.incoming.close();
            assertTrue(error(call) instanceof IOException);
            assertEquals(
                    "turn/completed",
                    wire.server.nextEvent(null, Duration.ofSeconds(1)).get("method"));
            assertThrows(
                    IOException.class, () -> wire.server.nextEvent(null, Duration.ofSeconds(1)));
        }
    }

    @Test
    public void malformedUtf8AndTruncatedJsonFailClosedWithoutPayloadLeak() throws Exception {
        for (byte[] invalid :
                List.of(
                        new byte[] {(byte) 0xc3, '\n'},
                        "{\"private-secret\":\n".getBytes(StandardCharsets.UTF_8),
                        "{\"id\":1,\"result\":{}}".getBytes(StandardCharsets.UTF_8))) {
            try (Wire wire = new Wire()) {
                Future<Map<String, Object>> call = wire.request("wait", Map.of());
                wire.outbound();
                wire.incoming.write(invalid);
                wire.incoming.flush();
                if (invalid[invalid.length - 1] != '\n') wire.incoming.close();
                Throwable failure = error(call);
                assertTrue(failure instanceof IOException);
                assertFalse(failure.toString().contains("private-secret"));
                assertNull(failure.getCause());
            }
        }
    }

    @Test
    public void oversizedLinesAndEventFloodsCloseTheTransport() throws Exception {
        try (Wire wire = new Wire()) {
            Future<Map<String, Object>> call = wire.request("wait", Map.of());
            wire.outbound();
            wire.incoming.write(new byte[1_048_577]);
            wire.incoming.flush();
            assertTrue(error(call) instanceof IOException);
        }
        try (Wire wire = new Wire()) {
            Future<Map<String, Object>> call = wire.request("wait", Map.of());
            wire.outbound();
            for (int index = 0; index < 257; index++)
                wire.send(Map.of("method", "progress", "params", Map.of("index", index)));
            assertTrue(error(call) instanceof IOException);
            assertThrows(IOException.class, () -> wire.server.nextEvent(null, Duration.ZERO));
        }
    }

    @Test
    public void invalidOutgoingJsonNeverWritesAndCloseClearsPendingEvents() throws Exception {
        try (Wire wire = new Wire()) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> wire.server.notify("bad", Map.of("number", Double.NaN)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> wire.server.notify("bad", Map.of("text", "\ud800")));
            assertNull(wire.output.poll(50, TimeUnit.MILLISECONDS));
            Future<Map<String, Object>> call = wire.request("wait", Map.of());
            wire.outbound();
            wire.send(Map.of("method", "progress", "params", Map.of()));
            wire.server.close();
            assertTrue(error(call) instanceof IOException);
            assertThrows(IOException.class, () -> wire.server.nextEvent(null, Duration.ZERO));
            assertThrows(IOException.class, () -> wire.server.notify("initialized", Map.of()));
        }
    }

    @Test
    public void blockedWriteStillHonorsDeadlineAndClosesOwnedStreams() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        OutputStream blocked =
                new OutputStream() {
                    @Override
                    public void write(int value) throws IOException {
                        while (!closed.get()) {
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException ignored) {
                            }
                        }
                        throw new IOException("synthetic private-secret");
                    }

                    @Override
                    public void close() {
                        closed.set(true);
                    }
                };
        try (Wire wire = new Wire(Duration.ofMillis(150), blocked)) {
            Future<Map<String, Object>> call = wire.request("wait", Map.of());
            assertTrue(error(call) instanceof TimeoutException);
            assertTrue(closed.get());
        }
    }

    @Test
    public void ownedProcessUsesArgumentVectorEnvironmentAndStopsOnClose() throws Exception {
        Path directory = Files.createTempDirectory("capella-codex-wire-");
        Path classes = directory.resolve("com/sidkolapalli/capella/ai/codex");
        Files.createDirectories(classes);
        try (InputStream compiled =
                CodexAppServerTest.class.getResourceAsStream("CodexAppServerTest$EchoMain.class")) {
            assertNotNull(compiled);
            Files.copy(compiled, classes.resolve("CodexAppServerTest$EchoMain.class"));
        }
        String java =
                Path.of(
                                System.getProperty("java.home"),
                                "bin",
                                System.getProperty("os.name").startsWith("Windows")
                                        ? "java.exe"
                                        : "java")
                        .toString();
        long pid;
        try (CodexAppServer server =
                new CodexAppServer(
                        List.of(java, "-cp", directory.toString(), EchoMain.class.getName()),
                        directory,
                        Map.of("CAPELLA_CODEX_WIRE_TEST", "synthetic-value"),
                        Duration.ofSeconds(3))) {
            Map<String, Object> pong =
                    server.request("ping", Map.of("quoted", "space and \"quotes\""), null);
            assertEquals(Boolean.TRUE, pong.get("pong"));
            assertEquals("synthetic-value", pong.get("environment"));
            assertEquals(directory.toAbsolutePath().toString(), pong.get("cwd"));
            pid = ((Number) pong.get("pid")).longValue();
            assertTrue(ProcessHandle.of(pid).orElseThrow().isAlive());
            server.close();
            assertThrows(IOException.class, () -> server.request("ping", Map.of(), null));
            try (CodexAppServer dying =
                    new CodexAppServer(
                            List.of(java, "-cp", directory.toString(), EchoMain.class.getName()),
                            directory,
                            Map.of(),
                            Duration.ofSeconds(3))) {
                ExecutorService worker = Executors.newSingleThreadExecutor();
                try {
                    Future<Map<String, Object>> waiting =
                            worker.submit(() -> dying.request("wait", Map.of(), null));
                    assertEquals(
                            "fixture/wait",
                            dying.nextEvent(null, Duration.ofSeconds(2)).get("method"));
                    dying.notify("exit", Map.of());
                    assertTrue(error(waiting) instanceof IOException);
                } finally {
                    worker.shutdownNow();
                }
            }
        } finally {
            Files.deleteIfExists(classes.resolve("CodexAppServerTest$EchoMain.class"));
            Files.deleteIfExists(classes);
            Files.deleteIfExists(classes.getParent());
            Files.deleteIfExists(classes.getParent().getParent());
            Files.deleteIfExists(classes.getParent().getParent().getParent());
            Files.deleteIfExists(classes.getParent().getParent().getParent().getParent());
            Files.deleteIfExists(directory);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
                && System.nanoTime() < deadline) Thread.sleep(10);
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    private static Throwable error(Future<?> result) throws Exception {
        try {
            result.get(2, TimeUnit.SECONDS);
            fail("Expected a failed request");
            return null;
        } catch (ExecutionException failed) {
            return failed.getCause();
        }
    }

    private static final class Wire implements AutoCloseable {
        final PipedInputStream input = new PipedInputStream(2_100_000);
        final PipedOutputStream incoming = new PipedOutputStream(input);
        final ArrayBlockingQueue<Map<String, Object>> output = new ArrayBlockingQueue<>(128);
        final ExecutorService workers =
                Executors.newCachedThreadPool(
                        task -> {
                            Thread thread = new Thread(task, "Codex-test-request");
                            thread.setDaemon(true);
                            return thread;
                        });
        final CodexAppServer server;

        Wire() throws IOException {
            this(Duration.ofSeconds(2));
        }

        Wire(Duration timeout) throws IOException {
            this(timeout, null);
        }

        Wire(Duration timeout, OutputStream custom) throws IOException {
            ByteArrayOutputStream capture =
                    new ByteArrayOutputStream() {
                        @SuppressWarnings("unchecked")
                        @Override
                        public synchronized void flush() {
                            output.add(
                                    (Map<String, Object>)
                                            AgentJson.parse(
                                                    toString(StandardCharsets.UTF_8)
                                                            .stripTrailing(),
                                                    1_048_576));
                            reset();
                        }
                    };
            server =
                    new CodexAppServer(
                            input,
                            custom == null ? capture : custom,
                            new ByteArrayInputStream(
                                    "sk-private-stderr-secret".getBytes(StandardCharsets.UTF_8)),
                            timeout);
        }

        Future<Map<String, Object>> request(String method, Map<String, Object> params) {
            return workers.submit(() -> server.request(method, params, null));
        }

        Map<String, Object> outbound() throws InterruptedException {
            Map<String, Object> result = output.poll(2, TimeUnit.SECONDS);
            assertNotNull("No outbound message", result);
            return result;
        }

        void send(Map<String, Object> frame) throws IOException {
            incoming.write((AgentJson.write(frame) + "\n").getBytes(StandardCharsets.UTF_8));
            incoming.flush();
        }

        @Override
        public void close() throws IOException {
            server.close();
            workers.shutdownNow();
            incoming.close();
        }
    }

    /**
     * Standalone fixture bytecode is copied into an isolated classpath; only JDK classes are used.
     */
    public static final class EchoMain {
        public static void main(String[] arguments) throws Exception {
            BufferedReader input =
                    new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            PrintWriter output =
                    new PrintWriter(
                            new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
            System.err.println("sk-synthetic-stderr-secret");
            String line;
            while ((line = input.readLine()) != null) {
                if (line.contains("\"method\":\"exit\"")) return;
                if (line.contains("\"method\":\"wait\"")) {
                    output.println("{\"method\":\"fixture/wait\",\"params\":{}}");
                    continue;
                }
                var match = java.util.regex.Pattern.compile("\"id\":([0-9]+)").matcher(line);
                if (match.find()) {
                    String cwd =
                            Path.of("")
                                    .toAbsolutePath()
                                    .toString()
                                    .replace("\\", "\\\\")
                                    .replace("\"", "\\\"");
                    output.println(
                            "{\"id\":"
                                    + match.group(1)
                                    + ",\"result\":{\"pong\":true,\"pid\":"
                                    + ProcessHandle.current().pid()
                                    + ",\"environment\":\""
                                    + System.getenv("CAPELLA_CODEX_WIRE_TEST")
                                    + "\",\"cwd\":\""
                                    + cwd
                                    + "\"}}");
                }
            }
        }
    }
}
