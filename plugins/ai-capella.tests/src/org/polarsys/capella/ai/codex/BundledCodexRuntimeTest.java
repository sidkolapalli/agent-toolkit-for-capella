package org.polarsys.capella.ai.codex;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Small resource fixtures test integrity/extraction without installing, starting or authenticating Codex. */
public class BundledCodexRuntimeTest {
    private static final byte[] BINARY = "pinned binary fixture".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HOST = "pinned code mode host".getBytes(StandardCharsets.UTF_8);
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void bothVerifiedExecutablesAreExtractedBesideEachOtherIntoVersionedPluginState() throws Exception {
        Path binary = loader(validResources()).resolve();
        assertEquals(cache().resolve("codex.exe"), binary);
        assertArrayEquals(BINARY, Files.readAllBytes(binary));
        assertArrayEquals(HOST, Files.readAllBytes(binary.resolveSibling("codex-code-mode-host.exe")));
        assertTrue(Files.isExecutable(binary));
        assertNoStaging();
    }

    @Test
    public void aValidCacheIsVerifiedAndReusedWithoutRewritingTheFiles() throws Exception {
        Path binary = loader(validResources()).resolve();
        FileTime original = FileTime.fromMillis(1_234_000);
        Files.setLastModifiedTime(binary, original);
        assertEquals(binary, loader(validResources()).resolve());
        assertEquals(original, Files.getLastModifiedTime(binary));
        assertNoStaging();
    }

    @Test
    public void sameSizeTamperingWithPreservedTimestampIsDetectedAndRepairedFromPinnedResources() throws Exception {
        Path binary = loader(validResources()).resolve();
        FileTime timestamp = Files.getLastModifiedTime(binary);
        byte[] tampered = BINARY.clone();
        tampered[0] ^= 1;
        Files.write(binary, tampered);
        Files.setLastModifiedTime(binary, timestamp);
        assertEquals(binary, loader(validResources()).resolve());
        assertArrayEquals(BINARY, Files.readAllBytes(binary));
        assertArrayEquals(HOST, Files.readAllBytes(binary.resolveSibling("codex-code-mode-host.exe")));
        assertNoStaging();
    }

    @Test
    public void missingCachedCompanionIsRepairedBeforeTheBinaryCanBeReturned() throws Exception {
        Path binary = loader(validResources()).resolve();
        Files.delete(binary.resolveSibling("codex-code-mode-host.exe"));
        assertEquals(binary, loader(validResources()).resolve());
        assertArrayEquals(HOST, Files.readAllBytes(binary.resolveSibling("codex-code-mode-host.exe")));
    }

    @Test
    public void aSameSizeTamperedHostIsVerifiedEvenWhenTheMainBinaryIsValid() throws Exception {
        Path binary = loader(validResources()).resolve();
        Path host = binary.resolveSibling("codex-code-mode-host.exe");
        byte[] tampered = HOST.clone();
        tampered[0] ^= 1;
        Files.write(host, tampered);
        assertEquals(binary, loader(validResources()).resolve());
        assertArrayEquals(HOST, Files.readAllBytes(host));
        assertNoStaging();
    }

    @Test
    public void anInstallationWithoutResourcesReturnsNullEvenIfAnOldCacheExists() throws Exception {
        loader(validResources()).resolve();
        assertNull(loader(Map.of()).resolve());
    }

    @Test
    public void missingResourcesDoNotCreateTheCache() throws Exception {
        assertNull(loader(Map.of()).resolve());
        assertFalse(Files.exists(cache()));
    }

    @Test
    public void aPartialPackageCannotPublishOrReturnAnyExecutable() throws Exception {
        String message = assertThrows(IllegalStateException.class,
            () -> loader(Map.of("codex.exe", BINARY)).resolve()).getMessage();
        assertTrue(message.contains("missing a bundled Codex executable"));
        assertFalse(Files.exists(cache()));
    }

    @Test
    public void anIncorrectCompanionDigestPreventsPublishingTheValidFirstExecutable() throws Exception {
        byte[] badHost = HOST.clone();
        badHost[0] ^= 1;
        assertThrows(IllegalStateException.class,
            () -> loader(Map.of("codex.exe", BINARY, "codex-code-mode-host.exe", badHost)).resolve());
        assertFalse(Files.exists(cache().resolve("codex.exe")));
        assertFalse(Files.exists(cache().resolve("codex-code-mode-host.exe")));
        assertNoStaging();
    }

    @Test
    public void aTruncatedResourceFailsItsExactSizeCheckAndLeavesNoPartialExecutable() throws Exception {
        String message = assertThrows(IllegalStateException.class,
            () -> loader(Map.of("codex.exe", new byte[1], "codex-code-mode-host.exe", HOST)).resolve()).getMessage();
        assertTrue(message.contains("Unexpected executable size"));
        assertFalse(Files.exists(cache().resolve("codex.exe")));
        assertNoStaging();
    }

    @Test
    public void oversizedResourcesAreReadOnlyToTheExpectedSizePlusOneByte() throws Exception {
        AtomicInteger readBytes = new AtomicInteger();
        var runtime = new BundledCodexRuntime(state(), name -> {
            if (!name.equals("codex.exe")) return new ByteArrayInputStream(HOST);
            return new ByteArrayInputStream(new byte[BINARY.length + 100_000]) {
                @Override public synchronized int read(byte[] data, int offset, int length) {
                    int count = super.read(data, offset, length);
                    if (count > 0) readBytes.addAndGet(count);
                    return count;
                }
            };
        }, BundledCodexRuntime.VERSION, specs());
        assertThrows(IllegalStateException.class, runtime::resolve);
        assertEquals(BINARY.length + 1, readBytes.get());
        assertFalse(Files.exists(cache().resolve("codex.exe")));
        assertNoStaging();
    }

    @Test
    public void aTamperedCacheCannotBeUsedWhenThePackagedRepairIsAlsoInvalid() throws Exception {
        Path binary = loader(validResources()).resolve();
        Files.writeString(binary, "broken cache");
        byte[] badHost = HOST.clone();
        badHost[0] ^= 1;
        assertThrows(IllegalStateException.class,
            () -> loader(Map.of("codex.exe", BINARY, "codex-code-mode-host.exe", badHost)).resolve());
        assertEquals("The valid staged binary must not replace the cache before its companion passes", "broken cache", Files.readString(binary));
        assertNoStaging();
    }

    @Test
    public void allOpenedResourcesAreClosedOnFailure() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        var runtime = new BundledCodexRuntime(state(), name -> new ByteArrayInputStream(new byte[1]) {
            @Override public void close() { closes.incrementAndGet(); }
        }, BundledCodexRuntime.VERSION, specs());
        assertThrows(IllegalStateException.class, runtime::resolve);
        assertEquals(2, closes.get());
    }

    @Test
    public void executableSpecificationsAndVersionsCannotEscapeTheCacheDirectory() throws Exception {
        assertThrows(IllegalArgumentException.class,
            () -> new BundledCodexRuntime.ExecutableSpec("../codex.exe", 1, "0".repeat(64)));
        assertThrows(IllegalArgumentException.class,
            () -> new BundledCodexRuntime.ExecutableSpec("codex.exe", Long.MAX_VALUE, "0".repeat(64)));
        assertThrows(IllegalArgumentException.class,
            () -> new BundledCodexRuntime(state(), name -> null, "../other", specs()));
    }

    @Test
    public void anUnrelatedFileAtTheCacheDirectoryIsNeverDeletedOrOverwritten() throws Exception {
        Path blocked = state().resolve("runtime");
        Files.writeString(blocked, "unrelated file");
        assertThrows(IllegalStateException.class, () -> loader(validResources()).resolve());
        assertEquals("unrelated file", Files.readString(blocked));
    }

    private BundledCodexRuntime loader(Map<String, byte[]> bytes) throws Exception {
        return new BundledCodexRuntime(state(), name -> {
            byte[] resource = bytes.get(name);
            return resource == null ? null : new ByteArrayInputStream(resource);
        }, BundledCodexRuntime.VERSION, specs());
    }

    private static Map<String, byte[]> validResources() {
        return Map.of("codex.exe", BINARY, "codex-code-mode-host.exe", HOST);
    }

    private static List<BundledCodexRuntime.ExecutableSpec> specs() throws Exception {
        return List.of(spec("codex.exe", BINARY), spec("codex-code-mode-host.exe", HOST));
    }

    private static BundledCodexRuntime.ExecutableSpec spec(String name, byte[] bytes) throws Exception {
        return new BundledCodexRuntime.ExecutableSpec(name, bytes.length,
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    private Path state() { return temporary.getRoot().toPath().toAbsolutePath().normalize(); }
    private Path cache() { return state().resolve("runtime").resolve(BundledCodexRuntime.VERSION); }

    private void assertNoStaging() throws Exception {
        Path runtime = state().resolve("runtime");
        if (!Files.exists(runtime)) return;
        try (var files = Files.list(runtime)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().startsWith(".")));
        }
    }
}
