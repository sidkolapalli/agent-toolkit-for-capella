package org.polarsys.capella.ai.codex;

import org.polarsys.capella.ai.agent.AgentJson;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Bounded UTF-8 JSONL transport to one owned Codex process; never executes a tool or approval. */
public final class CodexAppServer implements CodexTransport {
    private static final int MAX_LINE_BYTES = 1_048_576;
    private static final int MAX_EVENTS = 256;
    private static final long MAX_EVENT_BYTES = 8L * MAX_LINE_BYTES;
    private static final int MAX_PENDING = 64;
    private static final long POLL_MILLIS = 50;
    private final Process process;
    private final InputStream stdout;
    private final InputStream stderr;
    private final OutputStream stdin;
    private final long timeoutNanos;
    private final AtomicLong sequence = new AtomicLong();
    private final Map<Object, CompletableFuture<Map<String, Object>>> pending =
            new ConcurrentHashMap<>();
    private final ArrayBlockingQueue<Event> events = new ArrayBlockingQueue<>(MAX_EVENTS);
    private final AtomicLong eventBytes = new AtomicLong();
    private final AtomicReference<IOException> failure = new AtomicReference<>();
    private final ThreadPoolExecutor writer;
    private final Thread reader;

    public CodexAppServer(
            List<String> command,
            Path cwd,
            Map<String, String> environment,
            Duration requestTimeout)
            throws IOException {
        this(start(command, cwd, environment, requestTimeout), requestTimeout);
    }

    private CodexAppServer(Process process, Duration timeout) {
        this(
                process,
                process.getInputStream(),
                process.getOutputStream(),
                process.getErrorStream(),
                timeout);
    }

    /** Injectable owned streams for deterministic wire tests; no external process is started. */
    public CodexAppServer(
            InputStream stdout, OutputStream stdin, InputStream stderr, Duration timeout) {
        this(null, stdout, stdin, stderr, timeout);
    }

    private CodexAppServer(
            Process process,
            InputStream stdout,
            OutputStream stdin,
            InputStream stderr,
            Duration timeout) {
        timeoutNanos = duration(timeout, false);
        this.process = process;
        this.stdout = Objects.requireNonNull(stdout, "stdout required");
        this.stdin = Objects.requireNonNull(stdin, "stdin required");
        this.stderr = Objects.requireNonNull(stderr, "stderr required");
        writer =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(128),
                        task -> daemon("Codex-stdin", task),
                        new ThreadPoolExecutor.AbortPolicy());
        reader = daemon("Codex-stdout", this::readOutput);
        reader.start();
        daemon("Codex-stderr", this::drainError).start();
        if (process != null)
            daemon(
                            "Codex-process",
                            () -> {
                                try {
                                    int exit = process.waitFor();
                                    // Let stdout deliver already-written replies before reporting
                                    // process death.
                                    reader.join(500);
                                    fail(
                                            new IOException(
                                                    "Codex app-server stopped (exit "
                                                            + exit
                                                            + ")."),
                                            false);
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                            })
                    .start();
    }

    private static Process start(
            List<String> command, Path cwd, Map<String, String> environment, Duration timeout)
            throws IOException {
        duration(timeout, false);
        if (command == null
                || command.isEmpty()
                || command.stream().anyMatch(value -> value == null || value.isEmpty())) {
            throw new IllegalArgumentException("A Codex executable command is required");
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(List.copyOf(command));
            if (cwd != null) builder.directory(cwd.toFile());
            if (environment != null) builder.environment().putAll(environment);
            return builder.start();
        } catch (IOException | RuntimeException error) {
            // ProcessBuilder errors can include command arguments, paths or environment values.
            throw new IOException(
                    "Could not start Codex app-server. Check the executable and working"
                        + " directory.");
        }
    }

    @Override
    public Map<String, Object> request(
            String method, Map<String, Object> params, BooleanSupplier cancelled) throws Exception {
        checkCancelled(cancelled);
        ensureOpen();
        long id = sequence.incrementAndGet();
        CompletableFuture<Map<String, Object>> reply = new CompletableFuture<>();
        synchronized (pending) {
            ensureOpen();
            if (pending.size() >= MAX_PENDING)
                throw new IOException("Too many concurrent Codex requests");
            pending.put(id, reply);
        }
        long deadline = System.nanoTime() + timeoutNanos;
        try {
            Map<String, Object> frame = message(method, params);
            frame.put("id", id);
            send(frame, cancelled, deadline);
            return await(reply, cancelled, deadline);
        } finally {
            pending.remove(id, reply);
        }
    }

    @Override
    public void notify(String method, Map<String, Object> params) throws Exception {
        send(message(method, params), null, System.nanoTime() + timeoutNanos);
    }

    @Override
    public Map<String, Object> nextEvent(BooleanSupplier cancelled, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + duration(timeout, true);
        while (true) {
            checkCancelled(cancelled);
            Event event = events.poll();
            if (event != null) {
                eventBytes.addAndGet(-event.bytes);
                return event.value;
            }
            ensureOpen();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return null;
            try {
                event =
                        events.poll(
                                Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(POLL_MILLIS)),
                                TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            if (event != null) {
                eventBytes.addAndGet(-event.bytes);
                return event.value;
            }
        }
    }

    @Override
    public void respond(Object requestId, Map<String, Object> result) throws Exception {
        requireId(requestId);
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("id", requestId);
        frame.put("result", result == null ? Map.of() : result);
        send(frame, null, System.nanoTime() + timeoutNanos);
    }

    @Override
    public void reject(Object requestId, int code, String message) throws Exception {
        requireId(requestId);
        if (message == null || message.length() > 2000)
            throw new IllegalArgumentException("A bounded rejection message is required");
        send(
                Map.of("id", requestId, "error", Map.of("code", code, "message", message)),
                null,
                System.nanoTime() + timeoutNanos);
    }

    private void send(Map<String, Object> frame, BooleanSupplier cancelled, long deadline)
            throws Exception {
        checkCancelled(cancelled);
        ensureOpen();
        byte[] bytes;
        try {
            String text = AgentJson.write(frame);
            if (text.length() > MAX_LINE_BYTES) throw new IllegalArgumentException();
            AgentJson.parse(
                    text,
                    MAX_LINE_BYTES); // Also rejects nonfinite numbers, cycles and unsupported
                                     // schema depth.
            ByteBuffer encoded =
                    StandardCharsets.UTF_8
                            .newEncoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .encode(java.nio.CharBuffer.wrap(text + "\n"));
            bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            if (bytes.length > MAX_LINE_BYTES + 1) throw new IllegalArgumentException();
        } catch (CharacterCodingException | RuntimeException | StackOverflowError invalid) {
            throw new IllegalArgumentException("Codex request contains invalid or oversized JSON");
        }
        Future<Void> written;
        try {
            written =
                    writer.submit(
                            () -> {
                                ensureOpen();
                                try {
                                    stdin.write(bytes);
                                    stdin.flush();
                                } catch (IOException error) {
                                    throw new IOException("Could not write to Codex app-server");
                                }
                                return null;
                            });
        } catch (RejectedExecutionException unavailable) {
            throw new IOException("Codex app-server cannot accept another message");
        }
        try {
            await(written, cancelled, deadline);
        } catch (Exception error) {
            boolean completed = false;
            if (written.isDone() && !written.isCancelled()) {
                try {
                    written.get();
                    completed = true;
                } catch (Exception ignored) {
                }
            }
            if (!completed) {
                written.cancel(true);
                // A partially written frame cannot safely be retried on the same connection.
                fail(
                        new IOException("The Codex connection stopped while writing a message."),
                        true);
            }
            throw error;
        }
    }

    private <T> T await(Future<T> future, BooleanSupplier cancelled, long deadline)
            throws Exception {
        while (true) {
            checkCancelled(cancelled);
            if (!future.isDone()) ensureOpen();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0)
                throw new TimeoutException(
                        "Codex app-server did not respond before the request deadline");
            try {
                return future.get(
                        Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(POLL_MILLIS)),
                        TimeUnit.NANOSECONDS);
            } catch (TimeoutException waiting) {
                /* Poll cancellation without blocking the reader. */
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (ExecutionException completed) {
                if (completed.getCause() instanceof IOException error) throw error;
                throw new IOException("The Codex request could not complete");
            }
        }
    }

    private void readOutput() {
        try (InputStream input = new BufferedInputStream(stdout)) {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int value;
            while ((value = input.read()) != -1) {
                if (value == '\n') {
                    if (line.size() != 0) accept(decode(line.toByteArray()), line.size());
                    line.reset();
                } else {
                    if (line.size() >= MAX_LINE_BYTES)
                        throw new IOException("Codex emitted an oversized protocol message");
                    line.write(value);
                }
            }
            fail(
                    new IOException(
                            line.size() == 0
                                    ? "Codex app-server closed its output stream."
                                    : "Codex app-server ended an incomplete protocol message."),
                    false);
        } catch (IOException | RuntimeException malformed) {
            fail(
                    new IOException(
                            "Codex app-server emitted an unreadable or unsupported protocol"
                                + " message."),
                    true);
        }
    }

    @SuppressWarnings("unchecked")
    private void accept(String line, int bytes) throws IOException {
        if (line.isBlank()) return;
        Object parsed = AgentJson.parse(line, MAX_LINE_BYTES);
        if (!(parsed instanceof Map<?, ?> raw))
            throw new IOException("Codex protocol must contain objects");
        Map<String, Object> frame = (Map<String, Object>) raw;
        if (frame.containsKey("method")) {
            if (!(frame.get("method") instanceof String method)
                    || method.isBlank()
                    || method.length() > 200
                    || frame.containsKey("params") && !(frame.get("params") instanceof Map<?, ?>))
                throw new IOException("Invalid Codex event");
            if (frame.containsKey("id")) requireId(frame.get("id"));
            synchronized (events) {
                ensureOpen();
                if (eventBytes.addAndGet(bytes) > MAX_EVENT_BYTES
                        || !events.offer(new Event(Collections.unmodifiableMap(frame), bytes))) {
                    throw new IOException("Codex event queue exceeded its limit");
                }
            }
            return;
        }
        requireId(frame.get("id"));
        if (frame.containsKey("result") == frame.containsKey("error"))
            throw new IOException("Invalid Codex response");
        CompletableFuture<Map<String, Object>> reply = pending.remove(frame.get("id"));
        if (reply == null) return; // Late replies to cancelled/timed-out requests are not events.
        if (frame.containsKey("error")) {
            Object error = frame.get("error");
            String code =
                    error instanceof Map<?, ?> fields && fields.get("code") instanceof Number number
                            ? " (RPC code " + number.intValue() + ")"
                            : "";
            reply.completeExceptionally(new IOException("Codex rejected the request" + code + "."));
        } else if (frame.get("result") == null) reply.complete(Map.of());
        else if (frame.get("result") instanceof Map<?, ?> result)
            reply.complete(Collections.unmodifiableMap((Map<String, Object>) result));
        else
            reply.completeExceptionally(
                    new IOException("Codex returned an unsupported response result"));
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private void drainError() {
        // Drain with fixed memory; stderr is never retained, logged or included in exceptions.
        try (InputStream input = stderr) {
            byte[] buffer = new byte[2048];
            while (input.read(buffer) != -1) {}
        } catch (IOException ignored) {
        }
    }

    private void ensureOpen() throws IOException {
        IOException error = failure.get();
        if (error != null) throw error;
    }

    private void fail(IOException error, boolean clearEvents) {
        boolean first = failure.compareAndSet(null, error);
        // Clear before waking callers: one woken by a failed reply must not read stale events.
        if (clearEvents)
            synchronized (events) {
                events.clear();
                eventBytes.set(0);
            }
        if (first) {
            synchronized (pending) {
                pending.forEach((id, reply) -> reply.completeExceptionally(error));
                pending.clear();
            }
            writer.shutdownNow();
            if (process != null && process.isAlive()) {
                process.destroy();
                if (process.isAlive()) process.destroyForcibly();
            }
            closeQuietly(stdin);
            closeQuietly(stdout);
            closeQuietly(stderr);
        }
    }

    @Override
    public void close() {
        fail(new IOException("Codex app-server connection was closed."), true);
    }

    private static Map<String, Object> message(String method, Map<String, Object> params) {
        if (method == null || method.isBlank() || method.length() > 200)
            throw new IllegalArgumentException("A bounded Codex method name is required");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("method", method);
        value.put("params", params == null ? Map.of() : params);
        return value;
    }

    private static void requireId(Object id) {
        if (!(id instanceof String text && !text.isBlank() && text.length() <= 200
                || id instanceof Byte
                || id instanceof Short
                || id instanceof Integer
                || id instanceof Long)) {
            throw new IllegalArgumentException("A string or integer Codex request ID is required");
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedException {
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedException("Codex request interrupted");
        if (cancelled != null && cancelled.getAsBoolean())
            throw new CancellationException("Codex request stopped");
    }

    private static long duration(Duration value, boolean allowZero) {
        if (value == null
                || value.isNegative()
                || !allowZero && value.isZero()
                || value.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException(
                    "Codex timeout must be positive and at most one hour");
        }
        return value.toNanos();
    }

    private static Thread daemon(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void closeQuietly(AutoCloseable stream) {
        try {
            stream.close();
        } catch (Exception ignored) {
        }
    }

    private record Event(Map<String, Object> value, int bytes) {}
}
