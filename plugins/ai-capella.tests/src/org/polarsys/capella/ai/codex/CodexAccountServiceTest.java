package org.polarsys.capella.ai.codex;

import static org.junit.Assert.*;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.Test;

/** Account-only scripted protocol: no process, browser, OAuth or credential store is used. */
public class CodexAccountServiceTest {
    private static final String OWN_ID = "0b102d52-a9aa-4b36-a6e1-f29bd0238bbc";
    private static final String OTHER_ID = "11fbad9c-f0e9-49d8-b0c8-6547c6230662";
    private static final String AUTH_URL = "https://auth.openai.com/oauth/authorize?state=synthetic&code_challenge=synthetic";

    @Test public void existingOpenAiAccountsConnectWithoutLoginBrowserOrInference() {
        for (String mode : List.of("chatgpt", "apiKey")) {
            Fake fake = new Fake(); fake.accounts.add(account(mode)); List<URI> opened = new ArrayList<>();
            List<String> progress = new ArrayList<>();
            CodexAccountService service = new CodexAccountService(fake, opened::add);
            assertEquals(mode, service.connect(() -> false, progress::add).authMode());
            assertTrue(opened.isEmpty()); assertEquals(0, fake.count("account/login/start"));
            assertEquals(List.of("initialize", "account/read"), fake.methods());
            assertEquals(Map.of("refreshToken", false), fake.params("account/read"));
            assertEquals(Boolean.TRUE, object(fake.params("initialize").get("capabilities")).get("experimentalApi"));
            assertEquals(List.of("initialized"), fake.notifications); assertEquals(1, fake.closes);
            assertTrue(progress.stream().noneMatch(text -> text.contains("private@example") || text.contains("secret-token")));
            assertEquals("Connected[authMode=" + mode + "]", new CodexAccountService.Connected(mode).toString());
            assertAllowedMethods(fake);
        }
    }

    @Test public void matchingQueuedCompletionRechecksAccountAndReturnsOnlyAuthMode() {
        Fake fake = new Fake(); fake.accounts.add(account(null)); fake.accounts.add(account("chatgpt"));
        fake.afterStart = () -> fake.events.add(completed(OWN_ID, true));
        List<URI> opened = new ArrayList<>(); List<String> progress = new ArrayList<>();
        CodexAccountService.Connected connected = new CodexAccountService(fake, opened::add).connect(() -> false, progress::add);
        assertEquals("chatgpt", connected.authMode()); assertEquals(URI.create(AUTH_URL), opened.get(0));
        assertEquals(2, fake.count("account/read")); assertEquals(Map.of("type", "chatgpt"), fake.params("account/login/start"));
        assertEquals(0, fake.count("account/login/cancel")); assertEquals(1, fake.closes);
        assertTrue(progress.stream().noneMatch(text -> text.contains("https:") || text.contains(OWN_ID) || text.contains("private@example")));
        assertAllowedMethods(fake);
    }

    @Test public void foreignAndNullLoginIdsNeverCompleteThisLogin() {
        Fake fake = new Fake(); fake.accounts.add(account(null)); fake.accounts.add(account("chatgpt"));
        fake.events.add(completed(OTHER_ID, true)); fake.events.add(completed(null, true));
        fake.events.add(map("method", "account/updated", "params", map("authMode", "chatgpt")));
        fake.events.add(completed(OWN_ID, true));
        assertEquals("chatgpt", new CodexAccountService(fake, ignored -> {}).connect(() -> false, null).authMode());
        assertEquals(4, fake.eventReads); assertEquals(2, fake.count("account/read")); assertAllowedMethods(fake);
    }

    @Test public void cancellationImmediatelyAfterStartReplyCancelsOwnIdWithoutOpeningBrowser() {
        Fake fake = new Fake(); fake.accounts.add(account(null)); AtomicBoolean cancelled = new AtomicBoolean();
        fake.afterStart = () -> cancelled.set(true); List<URI> opened = new ArrayList<>();
        CodexAccountService service = new CodexAccountService(fake, opened::add);
        assertThrows(CancellationException.class, () -> service.connect(cancelled::get, null));
        assertTrue(opened.isEmpty()); assertEquals(Map.of("loginId", OWN_ID), fake.params("account/login/cancel"));
        assertEquals(1, fake.count("account/login/cancel")); service.close(); assertEquals(1, fake.closes);
        assertAllowedMethods(fake);
    }

    @Test public void cancellationWhileWaitingIgnoresForeignCompletionAndCancelsOnlyOwnLogin() {
        Fake fake = new Fake(); fake.accounts.add(account(null)); AtomicBoolean cancelled = new AtomicBoolean();
        fake.events.add(completed(OTHER_ID, true)); fake.beforeEvent = () -> cancelled.set(true);
        assertThrows(CancellationException.class, () -> new CodexAccountService(fake, ignored -> {}).connect(cancelled::get, null));
        assertEquals(Map.of("loginId", OWN_ID), fake.params("account/login/cancel"));
        assertEquals(1, fake.count("account/read")); assertAllowedMethods(fake);
    }

    @Test public void explicitCloseDuringBrowserCallbackCancelsExactlyOnce() {
        Fake fake = new Fake(); fake.accounts.add(account(null)); AtomicReference<CodexAccountService> reference = new AtomicReference<>();
        CodexAccountService service = new CodexAccountService(fake, ignored -> reference.get().close()); reference.set(service);
        assertThrows(CancellationException.class, () -> service.connect(() -> false, null));
        service.close(); assertEquals(1, fake.count("account/login/cancel")); assertEquals(1, fake.closes);
    }

    @Test public void nonOfficialOrMalformedUrlsAreRejectedAndNeverOpened() {
        for (String url : List.of("http://auth.openai.com/oauth/authorize?state=synthetic",
                "https://auth.openai.com.evil.example/oauth/authorize?state=synthetic",
                "https://auth.openai.com@evil.example/oauth/authorize?state=synthetic",
                "https://user@auth.openai.com/oauth/authorize?state=synthetic",
                "https://auth.openai.com:444/oauth/authorize?state=synthetic",
                "https://auth.openai.com/oauth/authorize?state=synthetic#secret",
                "https://auth.openai.com/other?state=synthetic", "file:///private/auth.json", "not a URI")) {
            Fake fake = new Fake(); fake.accounts.add(account(null)); fake.start.put("authUrl", url); List<URI> opened = new ArrayList<>();
            CodexAccountService.AccountException error = assertThrows(CodexAccountService.AccountException.class,
                () -> new CodexAccountService(fake, opened::add).connect(() -> false, null));
            assertTrue(opened.isEmpty()); assertFalse(error.getMessage().contains(url)); assertNull(error.getCause());
            assertEquals(Map.of("loginId", OWN_ID), fake.params("account/login/cancel")); assertEquals(1, fake.closes);
        }
    }

    @Test public void browserFailureHasSafeGuidanceAndCancelsOwnLogin() {
        Fake fake = new Fake(); fake.accounts.add(account(null));
        CodexAccountService.AccountException error = assertThrows(CodexAccountService.AccountException.class,
            () -> new CodexAccountService(fake, ignored -> { throw new IllegalStateException(AUTH_URL); }).connect(() -> false, null));
        assertTrue(error.getMessage().contains("default browser")); assertFalse(error.getMessage().contains("state="));
        assertNull(error.getCause()); assertEquals(1, fake.count("account/login/cancel"));
    }

    @Test public void failedCompletionDoesNotExposeRemoteErrorsOrCancelCompletedLogin() {
        Fake fake = new Fake(); fake.accounts.add(account(null));
        Map<String, Object> event = completed(OWN_ID, false); object(event.get("params")).put("error", AUTH_URL + " secret-token"); fake.events.add(event);
        CodexAccountService.AccountException error = assertThrows(CodexAccountService.AccountException.class,
            () -> new CodexAccountService(fake, ignored -> {}).connect(() -> false, null));
        assertEquals("Sign-in did not complete. Try again in your browser.", error.getMessage()); assertNull(error.getCause());
        assertEquals(0, fake.count("account/login/cancel")); assertEquals(1, fake.count("account/read")); assertAllowedMethods(fake);
    }

    @Test public void successfulNotificationWithoutConfirmedChatGptAccountCannotReportConnected() {
        for (String mode : new String[] { null, "apiKey" }) {
            Fake fake = new Fake(); fake.accounts.add(account(null)); fake.accounts.add(account(mode)); fake.events.add(completed(OWN_ID, true));
            CodexAccountService.AccountException error = assertThrows(CodexAccountService.AccountException.class,
                () -> new CodexAccountService(fake, ignored -> {}).connect(() -> false, null));
            assertTrue(error.getMessage().contains("not confirmed")); assertEquals(2, fake.count("account/read"));
            assertEquals(0, fake.count("account/login/cancel")); assertAllowedMethods(fake);
        }
    }

    @Test public void timeoutCancelsOwnLoginAndClosesWithoutSigningOut() {
        Fake fake = new Fake(); fake.accounts.add(account(null)); fake.eventDelay = Duration.ofMillis(60);
        CodexAccountService.AccountException error = assertThrows(CodexAccountService.AccountException.class,
            () -> new CodexAccountService(fake, ignored -> {}, Duration.ofMillis(50)).connect(() -> false, null));
        assertTrue(error.getMessage().contains("timed out")); assertEquals(1, fake.count("account/login/cancel"));
        assertEquals(1, fake.closes); assertAllowedMethods(fake);
    }

    @Test public void transportSecretsAreNotCopiedToErrorsOrCauses() {
        Fake fake = new Fake(); fake.failMethod = "account/read";
        CodexAccountService.AccountException error = assertThrows(CodexAccountService.AccountException.class,
            () -> new CodexAccountService(fake, ignored -> {}).connect(() -> false, null));
        assertFalse(error.getMessage().contains("secret-token")); assertFalse(error.getMessage().contains("https:")); assertNull(error.getCause());
        assertEquals(1, fake.closes); assertAllowedMethods(fake);
    }

    @Test public void unexpectedServerRequestsAreRejectedWithoutHandlingTokensOrApprovals() {
        Fake fake = new Fake(); fake.accounts.add(account(null)); fake.accounts.add(account("chatgpt"));
        fake.events.add(map("id", "server-request", "method", "account/chatgptAuthTokens/refresh", "params", map("previousAccountId", "private")));
        fake.events.add(completed(OWN_ID, true));
        new CodexAccountService(fake, ignored -> {}).connect(() -> false, null);
        assertEquals(List.of("server-request"), fake.rejected); assertEquals(0, fake.responses); assertAllowedMethods(fake);
    }

    @Test public void preCancelledOrClosedServiceNeverStartsAuthentication() {
        Fake fake = new Fake(); CodexAccountService service = new CodexAccountService(fake, ignored -> {});
        assertThrows(CancellationException.class, () -> service.connect(() -> true, null)); assertTrue(fake.methods().isEmpty()); assertEquals(1, fake.closes);
        assertThrows(CodexAccountService.AccountException.class, () -> service.connect(() -> false, null));
        Fake other = new Fake(); CodexAccountService closed = new CodexAccountService(other, ignored -> {}); closed.close();
        assertThrows(CancellationException.class, () -> closed.connect(() -> false, null)); assertTrue(other.methods().isEmpty()); assertEquals(1, other.closes);
    }

    @Test public void unsupportedConfiguredProvidersDoNotOverwriteExistingAuth() {
        for (Map<String, Object> account : List.of(account("amazonBedrock"), map("requiresOpenaiAuth", false, "account", null))) {
            Fake fake = new Fake(); fake.accounts.add(account);
            assertThrows(CodexAccountService.AccountException.class, () -> new CodexAccountService(fake, ignored -> {}).connect(() -> false, null));
            assertEquals(0, fake.count("account/login/start")); assertAllowedMethods(fake);
        }
    }

    @Test public void interruptionPreservesFlagAndClosesOwnedTransport() {
        Fake fake = new Fake(); fake.accounts.add(account(null)); fake.interruptEvents = true;
        try {
            assertThrows(CancellationException.class, () -> new CodexAccountService(fake, ignored -> {}).connect(() -> false, null));
            assertTrue(Thread.currentThread().isInterrupted()); assertEquals(1, fake.closes); assertAllowedMethods(fake);
        } finally { Thread.interrupted(); }
    }

    private static void assertAllowedMethods(Fake fake) {
        assertTrue(fake.methods().stream().allMatch(Set.of("initialize", "account/read", "account/login/start", "account/login/cancel")::contains));
    }
    private static Map<String, Object> account(String mode) {
        return map("requiresOpenaiAuth", true, "account", mode == null ? null : map("type", mode,
            "email", "private@example.invalid", "planType", "pro", "accessToken", "secret-token"));
    }
    private static Map<String, Object> completed(String id, boolean success) {
        return map("method", "account/login/completed", "params", map("loginId", id, "success", success, "error", null));
    }
    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]); return result;
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }
    private record Request(String method, Map<String, Object> params) {}
    private static final class Fake implements CodexTransport {
        final List<Request> requests = new ArrayList<>(); final List<String> notifications = new ArrayList<>(); final List<Object> rejected = new ArrayList<>();
        final Deque<Map<String, Object>> accounts = new ArrayDeque<>(), events = new ArrayDeque<>();
        final Map<String, Object> start = map("type", "chatgpt", "loginId", OWN_ID, "authUrl", AUTH_URL);
        int closes, responses, eventReads; String failMethod; Runnable afterStart, beforeEvent; boolean interruptEvents; Duration eventDelay;
        List<String> methods() { return requests.stream().map(Request::method).toList(); }
        long count(String method) { return requests.stream().filter(request -> method.equals(request.method())).count(); }
        Map<String, Object> params(String method) { return requests.stream().filter(request -> method.equals(request.method())).findFirst().orElseThrow().params(); }
        @Override public Map<String, Object> request(String method, Map<String, Object> params, BooleanSupplier cancelled) throws Exception {
            if (cancelled.getAsBoolean()) throw new CancellationException(); requests.add(new Request(method, params));
            if (method.equals(failMethod)) throw new IOException(AUTH_URL + " secret-token");
            return switch (method) {
                case "initialize" -> Map.of();
                case "account/read" -> accounts.removeFirst();
                case "account/login/start" -> { if (afterStart != null) afterStart.run(); yield start; }
                case "account/login/cancel" -> Map.of("status", "canceled");
                default -> throw new IOException("Unexpected protocol request");
            };
        }
        @Override public void notify(String method, Map<String, Object> params) { notifications.add(method); }
        @Override public Map<String, Object> nextEvent(BooleanSupplier cancelled, Duration timeout) throws Exception {
            eventReads++; if (interruptEvents) throw new InterruptedException("private");
            if (beforeEvent != null) beforeEvent.run();
            if (eventDelay != null) Thread.sleep(eventDelay.toMillis());
            if (cancelled.getAsBoolean()) throw new CancellationException(); return events.pollFirst();
        }
        @Override public void respond(Object id, Map<String, Object> result) { responses++; }
        @Override public void reject(Object id, int code, String message) { assertEquals(-32601, code); rejected.add(id); }
        @Override public void close() { closes++; }
    }
}
