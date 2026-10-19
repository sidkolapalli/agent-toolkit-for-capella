package org.polarsys.capella.ai.preferences;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.polarsys.capella.ai.codex.CodexPreferences;

/** Native SWT rendering with read-only global defaults and entirely isolated runtime storage. */
public class CodexPreferencePageTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Display display;
    private Shell shell;
    private Page page;
    private Path binary;
    private final AtomicInteger resolutions = new AtomicInteger(), verifications = new AtomicInteger();

    @Before
    public void create() throws Exception {
        binary = temporary.getRoot().toPath().resolve("codex.exe");
        Files.writeString(binary, "disposable executable fixture; never run");
        assertTrue(binary.toFile().setExecutable(true));
        Map<String, Object> values = new HashMap<>();
        IEclipsePreferences storage = (IEclipsePreferences) Proxy.newProxyInstance(IEclipsePreferences.class.getClassLoader(),
            new Class<?>[] {IEclipsePreferences.class}, (proxy, method, arguments) -> switch (method.getName()) {
                case "get", "getInt" -> values.getOrDefault(arguments[0], arguments[1]);
                case "put", "putInt" -> { values.put((String) arguments[0], arguments[1]); yield null; }
                default -> throw new AssertionError("Unexpected test preference operation: " + method.getName());
            });
        var settings = new CodexPreferences.Configuration(storage, key -> key.equals("PATH") ? binary.getParent().toString() : null,
            () -> {
                assertNull("Runtime extraction must not run on SWT", Display.getCurrent());
                resolutions.incrementAndGet(); return binary;
            });
        display = Display.getDefault();
        onUi(() -> {
            shell = new Shell(display);
            page = new Page(settings, verifications);
            page.create(shell);
        });
    }

    @After
    public void dispose() {
        if (display != null && !display.isDisposed()) onUi(() -> {
            if (page != null) page.dispose();
            if (shell != null) shell.dispose();
        });
    }

    @Test
    public void codexSettingsDoNotReadOrDemandProviderCredentials() {
        onUi(() -> {
            assertNull(field("initialApiKey", String.class));
            assertEquals("", field("apiKey", Text.class).getText());
            assertNull(page.getErrorMessage());
            assertTrue(field("runtimeCaption", Label.class).getText().contains("No provider API key"));
        });
    }

    @Test
    public void defaultCodexFieldsExplainDiscoveryAndOptionalRuntimeModel() {
        onUi(() -> {
            assertEquals("", field("codexBinary", Text.class).getText());
            assertEquals("Auto-discover installed Codex", field("codexBinary", Text.class).getMessage());
            assertEquals("", field("codexModel", Text.class).getText());
            assertEquals("Use Codex runtime default", field("codexModel", Text.class).getMessage());
            assertEquals("300", field("codexTimeout", Text.class).getText());
            assertEquals("Check Codex runtime", field("testButton", Button.class).getText());
        });
    }

    @Test
    public void onlyRelevantRuntimeGroupOccupiesLayoutAndStatusDoesNotClaimAuthentication() {
        onUi(() -> {
            Group codex = field("codexGroup", Group.class);
            Group direct = field("directGroup", Group.class);
            assertTrue(codex.getVisible());
            assertFalse(((GridData) codex.getLayoutData()).exclude);
            assertFalse(direct.getVisible());
            assertTrue(((GridData) direct.getLayoutData()).exclude);
            String status = field("status", Label.class).getText();
            assertTrue(status.contains("Bundled Codex") || status.contains("Codex will be discovered"));
            assertTrue(status.contains("Connection and sign-in are checked when a conversation starts"));
            assertEquals("Rendering must not resolve or extract the runtime", 0, resolutions.get());
        });
    }

    @Test
    public void unavailableExplicitBinaryHasActionableInlineRecovery() {
        onUi(() -> {
            field("codexBinary", Text.class).setText(binary.resolveSibling("missing.exe").toString());
            String status = field("status", Label.class).getText();
            assertTrue(status.contains("configured Codex binary"));
            assertTrue(status.contains("clear the path"));
        });
    }

    @Test
    public void runtimeCheckResolvesAndVerifiesOnWorkerWhileUiRemainsResponsive() throws Exception {
        onUi(() -> field("testButton", Button.class).notifyListeners(SWT.Selection, new Event()));
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        AtomicReference<String> status = new AtomicReference<>();
        while (System.nanoTime() < deadline) {
            onUi(() -> status.set(field("status", Label.class).getText()));
            if (status.get().startsWith("OK:")) break;
            if (Display.getCurrent() == display) { while (display.readAndDispatch()) { } }
            Thread.sleep(5);
        }
        assertTrue(status.get(), status.get().startsWith("OK: Codex 0.160.1 is ready."));
        assertEquals(1, resolutions.get()); assertEquals(1, verifications.get());
        onUi(() -> assertTrue(field("testButton", Button.class).getEnabled()));
    }

    private static <T> T field(LLMPreferencePage page, String name, Class<T> type) {
        try {
            Field field = LLMPreferencePage.class.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(page));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private <T> T field(String name, Class<T> type) { return field(page, name, type); }

    private void onUi(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        display.syncExec(() -> {
            try { action.run(); }
            catch (Throwable exception) { failure.set(exception); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static final class Page extends LLMPreferencePage {
        final AtomicInteger verifications;
        Page(CodexPreferences.Configuration settings, AtomicInteger verifications) { super(settings); this.verifications = verifications; }
        void create(Composite parent) { createContents(parent); }
        @Override protected void verifyCodexRuntime(Path binary) {
            assertNull("Version check must not run on SWT", Display.getCurrent());
            assertTrue(Files.isRegularFile(binary)); verifications.incrementAndGet();
        }
    }
}
