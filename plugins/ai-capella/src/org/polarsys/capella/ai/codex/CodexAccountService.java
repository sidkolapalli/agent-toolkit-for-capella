package org.polarsys.capella.ai.codex;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * One owned app-server connection for account setup only. Codex owns OAuth and
 * credential storage; this service never receives, copies or saves credentials.
 */
public final class CodexAccountService implements AutoCloseable {
    /** Account identity deliberately contains no email, plan, tokens or OAuth URL. */
    public record Connected(String authMode) {
        public Connected {
            if (!Set.of("chatgpt", "apiKey").contains(authMode)) throw new IllegalArgumentException("Unsupported OpenAI account mode");
        }
    }

    /** Contains only locally authored public guidance, without a remote cause. */
    public static final class AccountException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private AccountException(String message) { super(message); }
    }

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration POLL = Duration.ofMillis(250);
    private final CodexTransport transport;
    private final Consumer<URI> browser;
    private final long timeoutNanos;
    private final AtomicBoolean started = new AtomicBoolean(), closed = new AtomicBoolean();
    private final AtomicReference<String> pendingLogin = new AtomicReference<>();

    public CodexAccountService(CodexTransport ownedTransport, Consumer<URI> openSystemBrowser) {
        this(ownedTransport, openSystemBrowser, DEFAULT_TIMEOUT);
    }

    public CodexAccountService(CodexTransport ownedTransport, Consumer<URI> openSystemBrowser, Duration timeout) {
        transport = Objects.requireNonNull(ownedTransport, "Owned transport required");
        browser = Objects.requireNonNull(openSystemBrowser, "System browser callback required");
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("Sign-in timeout must be positive and at most ten minutes");
        }
        timeoutNanos = timeout.toNanos();
    }

    /** Creates a compatible local runtime without reading or exporting its credential store. */
    public static CodexAccountService configured(Consumer<URI> openSystemBrowser) {
        Objects.requireNonNull(openSystemBrowser, "System browser callback required");
        try {
            Path binary = CodexPreferences.resolveBinary();
            CodexLaunchConfig.verifyVersion(binary);
            return new CodexAccountService(new CodexAppServer(CodexLaunchConfig.command(binary), null,
                Map.of(), Duration.ofSeconds(30)), openSystemBrowser);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw cancelled();
        } catch (IOException | RuntimeException unavailable) {
            throw problem("Codex could not start. Select Codex " + CodexLaunchConfig.SUPPORTED_VERSION + " in Settings and try again.");
        }
    }

    /**
     * Runs off the UI thread. The browser callback owns UI-thread dispatch. This
     * single-use operation closes its transport on every outcome, without logout.
     */
    public Connected connect(BooleanSupplier cancelled, Consumer<String> progress) {
        if (!started.compareAndSet(false, true)) throw problem("This sign-in operation has already been used. Start a new connection.");
        BooleanSupplier userCancelled = cancelled == null ? () -> false : cancelled;
        Consumer<String> display = progress == null ? ignored -> { } : progress;
        long deadline = System.nanoTime() + timeoutNanos;
        BooleanSupplier stop = () -> closed.get() || userCancelled.getAsBoolean() || System.nanoTime() >= deadline;
        try {
            check(userCancelled, deadline);
            display.accept("Checking OpenAI connection…");
            request("initialize", Map.of("clientInfo", Map.of("name", "capella_arcadia", "title", "Capella Arcadia", "version", "0.2.1"),
                "capabilities", Map.of("experimentalApi", true)), stop);
            transport.notify("initialized", Map.of());
            Connected existing = account(stop);
            check(userCancelled, deadline);
            if (existing != null) { display.accept("OpenAI connected."); return existing; }

            Map<String, Object> login = request("account/login/start", Map.of("type", "chatgpt"), stop);
            // Keep the returned identity before checking cancellation, so a race
            // between the RPC reply and Stop still cancels this attempt only.
            String loginId = loginId(login.get("loginId"));
            pendingLogin.set(loginId);
            if (!"chatgpt".equals(login.get("type"))) throw problem("Codex returned an unsupported sign-in response. Try again.");
            URI url = authorizationUrl(login.get("authUrl"));
            check(userCancelled, deadline);
            display.accept("Finish signing in in your browser…");
            try { browser.accept(url); }
            catch (RuntimeException unavailable) { throw problem("The sign-in page could not open. Check your default browser and try again."); }
            for (int count = 0; count < 4096; count++) {
                check(userCancelled, deadline);
                Map<String, Object> event = transport.nextEvent(stop, POLL);
                check(userCancelled, deadline);
                if (event == null) continue;
                if (event.containsKey("id")) {
                    transport.reject(event.get("id"), -32601, "Account setup does not support server requests.");
                    continue;
                }
                if (!"account/login/completed".equals(event.get("method"))) continue;
                Map<?, ?> params = object(event.get("params"));
                if (!loginId.equals(params.get("loginId"))) continue;
                if (!(params.get("success") instanceof Boolean success)) throw problem("Codex returned an incomplete sign-in result. Try again.");
                pendingLogin.compareAndSet(loginId, null);
                if (!success) throw problem("Sign-in did not complete. Try again in your browser.");
                // Completion may be queued before the start reply. Always confirm
                // stored account state rather than trusting the notification.
                Connected connected = account(stop);
                check(userCancelled, deadline);
                if (connected == null || !"chatgpt".equals(connected.authMode())) {
                    throw problem("Codex has not confirmed the ChatGPT connection. Try signing in again.");
                }
                display.accept("OpenAI connected."); return connected;
            }
            throw problem("Codex sent too many sign-in updates. Try again.");
        } catch (CancellationException cancellation) {
            check(userCancelled, deadline); throw cancelled();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw cancelled();
        } catch (AccountException expected) {
            throw expected;
        } catch (Exception failure) {
            throw problem("OpenAI sign-in could not complete. Check your connection and Codex settings, then try again.");
        } finally { close(); }
    }

    private Connected account(BooleanSupplier stop) throws Exception {
        Map<String, Object> result = request("account/read", Map.of("refreshToken", false), stop);
        if (!(result.get("requiresOpenaiAuth") instanceof Boolean required)) throw problem("Codex returned an incomplete account status. Try again.");
        Object value = result.get("account");
        if (value == null) {
            if (!required) throw problem("Codex is configured without OpenAI sign-in. Select its OpenAI provider in Codex settings.");
            return null;
        }
        Object mode = object(value).get("type");
        if ("chatgpt".equals(mode) || "apiKey".equals(mode)) return new Connected((String) mode);
        throw problem("Codex uses a different account provider. Select its OpenAI provider in Codex settings.");
    }

    private Map<String, Object> request(String method, Map<String, Object> params, BooleanSupplier stop) throws Exception {
        Map<String, Object> result = transport.request(method, params, stop);
        if (result == null) throw problem("Codex returned an incomplete sign-in response. Try again.");
        return result;
    }

    private void check(BooleanSupplier cancelled, long deadline) {
        if (closed.get() || cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw cancelled();
        if (System.nanoTime() >= deadline) throw problem("Sign-in timed out. Start again when you are ready to finish in your browser.");
    }

    private static Map<?, ?> object(Object value) {
        if (value instanceof Map<?, ?> map) return map;
        throw problem("Codex returned an incomplete sign-in response. Try again.");
    }

    private static String loginId(Object value) {
        if (value instanceof String id && id.length() == 36) {
            try { if (UUID.fromString(id).toString().equalsIgnoreCase(id)) return id; }
            catch (IllegalArgumentException invalid) { /* Never include a remote identifier in an error. */ }
        }
        throw problem("Codex returned an invalid sign-in identity. Try again.");
    }

    private static URI authorizationUrl(Object value) {
        // Exact pinned source: rust-v0.160.1 login/src/server.rs DEFAULT_ISSUER
        // and build_authorize_url. Reject configurable staging/custom issuers.
        if (value instanceof String text && text.length() <= 8192) {
            try {
                URI uri = new URI(text);
                if ("https".equalsIgnoreCase(uri.getScheme()) && "auth.openai.com".equalsIgnoreCase(uri.getHost()) &&
                    (uri.getPort() == -1 || uri.getPort() == 443) && uri.getUserInfo() == null && uri.getFragment() == null &&
                    "/oauth/authorize".equals(uri.getRawPath()) && uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) return uri;
            } catch (URISyntaxException invalid) { /* Do not expose the URL or query. */ }
        }
        throw problem("Codex returned a sign-in address outside the official OpenAI login. Check Codex settings and try again.");
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        String ownId = pendingLogin.getAndSet(null);
        try {
            if (ownId != null) {
                long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                try { transport.request("account/login/cancel", Map.of("loginId", ownId), () -> System.nanoTime() >= deadline); }
                catch (Exception ignored) { /* Closing the owned process also revokes its pending callback server. */ }
            }
        } finally { transport.close(); }
    }

    private static AccountException problem(String message) { return new AccountException(message); }
    private static CancellationException cancelled() { return new CancellationException("OpenAI sign-in cancelled."); }
}
