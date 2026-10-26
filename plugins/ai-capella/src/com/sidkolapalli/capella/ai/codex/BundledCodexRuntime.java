package com.sidkolapalli.capella.ai.codex;

import org.eclipse.core.runtime.Platform;
import com.sidkolapalli.capella.ai.Activator;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Extract the pinned runtime into plugin state; neither discovery nor extraction reads credentials.
 */
public final class BundledCodexRuntime {
    public static final String VERSION = "0.160.1";
    private static final String RESOURCE_ROOT = "resources/codex/windows-x64/";
    private static final Object EXTRACTION_LOCK = new Object();
    private static final List<ExecutableSpec> EXECUTABLES =
            List.of(
                    new ExecutableSpec(
                            "codex.exe",
                            326_875_440L,
                            "9e7c59c05cc1ce5677b1f94e835b2ac038ca3be14504e78d558eacdb0ea3f55d"),
                    new ExecutableSpec(
                            "codex-code-mode-host.exe",
                            74_669_360L,
                            "b905b5169bd758dbe3c31ca22c22ad0c24df0047ba7ae32652c706d3ac3ba78d"));

    private final Path stateDirectory;
    private final ResourceSource resources;
    private final String version;
    private final List<ExecutableSpec> executables;

    /**
     * Injectable resource/specification boundary for deterministic integrity tests and standalone
     * loading.
     */
    public BundledCodexRuntime(
            Path stateDirectory,
            ResourceSource resources,
            String version,
            List<ExecutableSpec> executables) {
        this.stateDirectory = Objects.requireNonNull(stateDirectory).toAbsolutePath().normalize();
        this.resources = Objects.requireNonNull(resources);
        if (version == null || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) {
            throw new IllegalArgumentException("Invalid bundled runtime version.");
        }
        this.version = version;
        this.executables = List.copyOf(executables);
        if (this.executables.size() != 2
                || !"codex.exe".equals(this.executables.get(0).name())
                || !"codex-code-mode-host.exe".equals(this.executables.get(1).name())) {
            throw new IllegalArgumentException("Both pinned Codex executables are required.");
        }
    }

    /**
     * Returns null only for an unsupported platform or an installation without packaged
     * executables.
     */
    public static Path resolveBinary() {
        Activator plugin = supportedPlugin();
        if (plugin == null) return null;
        return new BundledCodexRuntime(
                        plugin.getStateLocation().toFile().toPath(),
                        name -> {
                            URL resource = plugin.getBundle().getEntry(RESOURCE_ROOT + name);
                            return resource == null ? null : resource.openStream();
                        },
                        VERSION,
                        EXECUTABLES)
                .resolve();
    }

    /**
     * Cheap availability only. A worker must still resolveBinary() and verify integrity before
     * launch.
     */
    public static boolean isPackagedAvailable() {
        Activator plugin = supportedPlugin();
        return plugin != null
                && EXECUTABLES.stream()
                        .allMatch(
                                spec ->
                                        plugin.getBundle().getEntry(RESOURCE_ROOT + spec.name())
                                                != null);
    }

    private static Activator supportedPlugin() {
        Activator plugin = Activator.getDefault();
        if (plugin == null
                || !Platform.OS_WIN32.equals(Platform.getOS())
                || !Platform.ARCH_X86_64.equals(Platform.getOSArch())) return null;
        return plugin;
    }

    public Path resolve() {
        synchronized (EXTRACTION_LOCK) {
            try (InputStream binary = resources.open(executables.get(0).name());
                    InputStream host = resources.open(executables.get(1).name())) {
                if (binary == null && host == null) return null;
                if (binary == null || host == null) {
                    throw new IOException(
                            "The installation is missing a bundled Codex executable. Reinstall"
                                + " Capella AI.");
                }
                Path runtimeRoot = stateDirectory.resolve("runtime");
                Path directory = runtimeRoot.resolve(version);
                requirePlainDirectory(runtimeRoot);
                requirePlainDirectory(directory);
                if (verified(directory.resolve(executables.get(0).name()), executables.get(0))
                        && verified(
                                directory.resolve(executables.get(1).name()), executables.get(1))) {
                    return directory.resolve(executables.get(0).name());
                }

                Files.createDirectories(runtimeRoot);
                Path staging = Files.createTempDirectory(runtimeRoot, "." + version + "-");
                try {
                    // Check both companions completely before publishing either file.
                    extract(binary, staging.resolve(executables.get(0).name()), executables.get(0));
                    extract(host, staging.resolve(executables.get(1).name()), executables.get(1));
                    Files.createDirectories(directory);
                    for (ExecutableSpec spec : executables) {
                        Path target = directory.resolve(spec.name());
                        if (Files.isSymbolicLink(target))
                            throw new IOException(
                                    "A bundled runtime cache file is a symbolic link.");
                        Files.move(
                                staging.resolve(spec.name()),
                                target,
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                        if (!target.toFile().setExecutable(true, true)
                                || !Files.isExecutable(target)) {
                            throw new IOException(
                                    "The bundled runtime executable cannot be used on this"
                                        + " filesystem.");
                        }
                    }
                    return directory.resolve(executables.get(0).name());
                } finally {
                    // Only delete our two named temporary files and our unique staging directory.
                    for (ExecutableSpec spec : executables)
                        Files.deleteIfExists(staging.resolve(spec.name()));
                    Files.deleteIfExists(staging);
                }
            } catch (IOException | SecurityException failure) {
                throw new IllegalStateException(
                        "Cannot prepare the bundled Codex runtime. "
                                + failure.getMessage()
                                + " Select an explicit Codex executable or reinstall Capella AI.",
                        failure);
            }
        }
    }

    private static void requirePlainDirectory(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)
                || (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException(
                    "The bundled runtime cache directory is unavailable or is a symbolic link.");
        }
    }

    private static boolean verified(Path file, ExecutableSpec spec) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) != spec.size()
                || !Files.isExecutable(file)) return false;
        try (InputStream input = Files.newInputStream(file)) {
            return spec.sha256().equals(digest(input, null, spec));
        }
    }

    private static void extract(InputStream input, Path file, ExecutableSpec spec)
            throws IOException {
        try (OutputStream output = Files.newOutputStream(file)) {
            if (!spec.sha256().equals(digest(input, output, spec))) {
                throw new IOException("Integrity check failed for " + spec.name() + ".");
            }
        }
    }

    private static String digest(InputStream input, OutputStream output, ExecutableSpec spec)
            throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
        byte[] buffer = new byte[65_536];
        long count = 0;
        int read;
        while ((read =
                        input.read(
                                buffer, 0, (int) Math.min(buffer.length, spec.size() - count + 1)))
                >= 0) {
            if (read == 0) continue;
            if (read > spec.size() - count)
                throw new IOException("Unexpected executable size for " + spec.name() + ".");
            count += read;
            digest.update(buffer, 0, read);
            if (output != null) output.write(buffer, 0, read);
        }
        if (count != spec.size())
            throw new IOException("Unexpected executable size for " + spec.name() + ".");
        return HexFormat.of().formatHex(digest.digest());
    }

    @FunctionalInterface
    public interface ResourceSource {
        InputStream open(String name) throws IOException;
    }

    public record ExecutableSpec(String name, long size, String sha256) {
        public ExecutableSpec {
            if (name == null
                    || !name.matches("[A-Za-z0-9._-]+")
                    || size <= 0
                    || size > 1_073_741_824L
                    || sha256 == null
                    || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid bundled executable specification.");
            }
        }
    }
}
