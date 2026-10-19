package org.polarsys.capella.ai.codex;

import static org.junit.Assert.*;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** No global preference nodes, process environment, auth files or child processes are used. */
public class CodexPreferencesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void defaultUsesCodexRuntimeAndItsModelDefaultWithFiveMinuteTimeout() {
        var settings = settings(Map.of());
        assertEquals("codex", settings.getRuntime());
        assertEquals("", settings.getBinary());
        assertEquals("", settings.getModel());
        assertEquals(300, settings.getTimeoutSec());
    }

    @Test
    public void writesCanBeReadByAnotherConfigurationWithoutChangingOtherStorage() {
        MemoryPreferences memory = new MemoryPreferences();
        var first = new CodexPreferences.Configuration(memory.node(), ignored -> null);
        first.setRuntime(" DIRECT ");
        first.setBinary(" C:/fixture/codex.exe ");
        first.setModel(" fixture-model ");
        first.setTimeoutSec(45);
        var reopened = new CodexPreferences.Configuration(memory.node(), ignored -> null);
        assertEquals("direct", reopened.getRuntime());
        assertEquals("C:/fixture/codex.exe", reopened.getBinary());
        assertEquals("fixture-model", reopened.getModel());
        assertEquals(45, reopened.getTimeoutSec());
        assertEquals("codex", settings(Map.of()).getRuntime());
    }

    @Test
    public void deliberateEnvironmentOverridesTakePriorityOverSavedSettings() {
        Map<String, String> environment = Map.of("CAPELLA_AI_RUNTIME", " CODEX ",
            "CAPELLA_AI_CODEX_BINARY", " /fixture/environment/codex.exe ",
            "CAPELLA_AI_CODEX_MODEL", " environment-model ");
        var settings = settings(environment);
        settings.setRuntime("direct");
        settings.setBinary("stored.exe");
        settings.setModel("stored-model");
        assertEquals("codex", settings.getRuntime());
        assertEquals("/fixture/environment/codex.exe", settings.getBinary());
        assertEquals("environment-model", settings.getModel());
        assertTrue(settings.isRuntimeOverridden());
        assertTrue(settings.isBinaryOverridden());
        assertTrue(settings.isModelOverridden());
    }

    @Test
    public void blankEnvironmentValuesUseSavedValuesAndBlankModelRemainsValid() {
        var settings = settings(Map.of("CAPELLA_AI_RUNTIME", " ", "CAPELLA_AI_CODEX_MODEL", ""));
        settings.setRuntime("direct");
        settings.setModel("");
        assertEquals("direct", settings.getRuntime());
        assertEquals("", settings.getModel());
        assertFalse(settings.isRuntimeOverridden());
        assertFalse(settings.isModelOverridden());
    }

    @Test
    public void invalidRuntimeCannotSilentlySelectAnotherBackend() {
        var settings = settings(Map.of("CAPELLA_AI_RUNTIME", "unknown-runtime"));
        assertTrue(assertThrows(IllegalArgumentException.class, settings::getRuntime).getMessage().contains("codex or direct"));
        assertThrows(IllegalArgumentException.class, () -> settings.setRuntime("unknown-runtime"));
    }

    @Test
    public void timeoutMustBePositiveAndDamagedStoredTimeoutFallsBackSafely() {
        MemoryPreferences memory = new MemoryPreferences();
        var settings = new CodexPreferences.Configuration(memory.node(), ignored -> null);
        settings.setTimeoutSec(123);
        assertThrows(IllegalArgumentException.class, () -> settings.setTimeoutSec(0));
        assertEquals(123, settings.getTimeoutSec());
        memory.values.put("timeoutSec", "-1");
        assertEquals(300, settings.getTimeoutSec());
        memory.values.put("timeoutSec", "not-a-number");
        assertEquals(300, settings.getTimeoutSec());
    }

    @Test
    public void timeoutMatchesTheRuntimeBudgetAtBothBoundsAndRejectsOutOfRangeStorage() {
        MemoryPreferences memory = new MemoryPreferences();
        var settings = new CodexPreferences.Configuration(memory.node(), ignored -> null);
        settings.setTimeoutSec(1);
        assertEquals(1, settings.getTimeoutSec());
        settings.setTimeoutSec(1800);
        assertEquals(1800, settings.getTimeoutSec());
        String message = assertThrows(IllegalArgumentException.class, () -> settings.setTimeoutSec(1801)).getMessage();
        assertTrue(message.contains("1 and 1800"));
        assertEquals(1800, settings.getTimeoutSec());
        memory.values.put("timeoutSec", "1801");
        assertEquals(300, settings.getTimeoutSec());
    }

    @Test
    public void explicitExecutableWinsOverPathAndInstalledBinaries() throws Exception {
        Path explicit = executable("explicit codex/codex.exe");
        Path pathBinary = executable("path/codex.exe");
        Path local = temporary.getRoot().toPath().resolve("local");
        executable("local/OpenAI/Codex/bin/current/codex.exe");
        var settings = settings(Map.of("PATH", pathBinary.getParent().toString(), "LOCALAPPDATA", local.toString()));
        settings.setBinary(explicit.toString());
        assertEquals(explicit, settings.resolveBinary());
    }

    @Test
    public void missingExplicitBinaryFailsWithoutRunningDifferentDiscoveredBinary() throws Exception {
        Path fallback = executable("path/codex.exe");
        var settings = settings(Map.of("PATH", fallback.getParent().toString()));
        settings.setBinary(temporary.getRoot().toPath().resolve("missing.exe").toString());
        String message = assertThrows(IllegalStateException.class, settings::resolveBinary).getMessage();
        assertTrue(message.contains("configured Codex binary"));
        assertTrue(message.contains("clear the path"));
    }

    @Test
    public void directoryAndInvalidPathsAreNotExecutables() throws Exception {
        var settings = settings(Map.of());
        settings.setBinary(temporary.newFolder("directory.exe").toString());
        assertThrows(IllegalStateException.class, settings::resolveBinary);
        settings.setBinary("bad\u0000path");
        assertTrue(assertThrows(IllegalArgumentException.class, settings::resolveBinary).getMessage().contains("path is invalid"));
    }

    @Test
    public void pathDiscoveryHonorsOrderAndQuotedDirectoryWithSpaces() throws Exception {
        Path first = executable("first directory/codex.exe");
        Path second = executable("second/codex.exe");
        String searchPath = "\"" + first.getParent() + "\"" + File.pathSeparator + second.getParent();
        assertEquals(first, settings(Map.of("PATH", searchPath)).resolveBinary());
    }

    @Test
    public void emptyAndMalformedPathEntriesAreSkipped() throws Exception {
        Path candidate = executable("usable/codex.exe");
        String searchPath = File.pathSeparator + "bad\u0000path" + File.pathSeparator + candidate.getParent();
        assertEquals(candidate, settings(Map.of("PATH", searchPath)).resolveBinary());
        assertThrows(IllegalStateException.class, settings(Map.of("PATH", File.pathSeparator))::resolveBinary);
    }

    @Test
    public void installedDiscoveryPrefersNewestExecutableNotNewestUnrelatedDirectory() throws Exception {
        Path older = executable("local/OpenAI/Codex/bin/older/codex.exe");
        Path current = executable("local/OpenAI/Codex/bin/current/codex.exe");
        Path unrelated = executable("local/OpenAI/Codex/bin/new-rg/rg.exe");
        Files.setLastModifiedTime(older, FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(current, FileTime.fromMillis(2_000));
        Files.setLastModifiedTime(unrelated, FileTime.fromMillis(3_000));
        Path local = temporary.getRoot().toPath().resolve("local");
        assertEquals(current, settings(Map.of("LOCALAPPDATA", local.toString())).resolveBinary());
    }

    @Test
    public void discoveryDoesNotSearchArbitraryNestedFolders() throws Exception {
        executable("local/OpenAI/Codex/bin/folder/nested/codex.exe");
        Path local = temporary.getRoot().toPath().resolve("local");
        assertThrows(IllegalStateException.class, settings(Map.of("LOCALAPPDATA", local.toString()))::resolveBinary);
    }

    @Test
    public void missingBinaryExplainsInstallationPathAndDirectProviderRecovery() {
        String message = assertThrows(IllegalStateException.class, settings(Map.of())::resolveBinary).getMessage();
        assertTrue(message.contains("no bundled runtime for this platform"));
        assertTrue(message.contains("CAPELLA_AI_CODEX_BINARY"));
        assertTrue(message.contains("Direct provider"));
    }

    @Test
    public void packagedRuntimeWinsOverPathWithoutInspectingDesktopInstallations() throws Exception {
        Path packaged = executable("packaged/codex.exe");
        Path discovered = executable("path/codex.exe");
        var settings = new CodexPreferences.Configuration(new MemoryPreferences().node(), key -> {
            throw new AssertionError("Verified packaged runtime must precede PATH/desktop discovery: " + key);
        }, () -> packaged);
        assertEquals(packaged, settings.resolveBinary(""));
        assertTrue(Files.exists(discovered));
    }

    @Test
    public void explicitBinaryDoesNotExtractOrUseThePackagedRuntime() throws Exception {
        Path explicit = executable("explicit/codex.exe");
        var settings = new CodexPreferences.Configuration(new MemoryPreferences().node(), ignored -> null,
            () -> { throw new AssertionError("Explicit binary must take precedence"); });
        settings.setBinary(explicit.toString());
        assertEquals(explicit, settings.resolveBinary());
    }

    @Test
    public void missingPackagedResourcesFallBackToExistingPathDiscovery() throws Exception {
        Path discovered = executable("path/codex.exe");
        var settings = new CodexPreferences.Configuration(new MemoryPreferences().node(),
            Map.of("PATH", discovered.getParent().toString())::get, () -> null);
        assertEquals(discovered, settings.resolveBinary());
    }

    @Test
    public void aBrokenPackagedRuntimeCannotSilentlySelectAnotherDiscoveredBinary() throws Exception {
        Path discovered = executable("path/codex.exe");
        var settings = new CodexPreferences.Configuration(new MemoryPreferences().node(),
            Map.of("PATH", discovered.getParent().toString())::get,
            () -> { throw new IllegalStateException("Fixture integrity failure"); });
        assertEquals("Fixture integrity failure", assertThrows(IllegalStateException.class, settings::resolveBinary).getMessage());
    }

    @Test
    public void discoveryNeverRequestsAuthenticationEnvironmentVariables() throws Exception {
        Path binary = executable("path/codex.exe");
        var settings = new CodexPreferences.Configuration(new MemoryPreferences().node(), key -> {
            assertTrue(key, key.equals("PATH") || key.equals("LOCALAPPDATA") || key.startsWith("CAPELLA_AI_"));
            return key.equals("PATH") ? binary.getParent().toString() : null;
        });
        assertEquals(binary, settings.resolveBinary());
    }

    @Test
    public void flushTargetsOnlyInjectedStorage() {
        MemoryPreferences memory = new MemoryPreferences();
        new CodexPreferences.Configuration(memory.node(), ignored -> null).flush();
        assertEquals(1, memory.flushes);
    }

    private CodexPreferences.Configuration settings(Map<String, String> environment) {
        return new CodexPreferences.Configuration(new MemoryPreferences().node(), environment::get);
    }

    private Path executable(String relativePath) throws Exception {
        Path path = temporary.getRoot().toPath().resolve(relativePath).toAbsolutePath().normalize();
        Files.createDirectories(path.getParent());
        Files.writeString(path, "disposable executable fixture; never run");
        assertTrue(path.toFile().setExecutable(true));
        return path;
    }

    private static final class MemoryPreferences {
        final Map<String, String> values = new HashMap<>();
        int flushes;

        IEclipsePreferences node() {
            return (IEclipsePreferences) Proxy.newProxyInstance(IEclipsePreferences.class.getClassLoader(),
                new Class<?>[] {IEclipsePreferences.class}, (proxy, method, arguments) -> {
                    return switch (method.getName()) {
                        case "get" -> values.getOrDefault((String) arguments[0], (String) arguments[1]);
                        case "getInt" -> {
                            try { yield Integer.parseInt(values.get((String) arguments[0])); }
                            catch (RuntimeException exception) { yield arguments[1]; }
                        }
                        case "put", "putInt" -> { values.put((String) arguments[0], String.valueOf(arguments[1])); yield null; }
                        case "flush" -> { flushes++; yield null; }
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
        }
    }
}
