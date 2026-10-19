package org.polarsys.capella.ai.codex;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;

/** Local runtime settings. Codex sign-in and credentials stay with Codex. */
public final class CodexPreferences {
    public static final String CODEX = "codex";
    public static final String DIRECT = "direct";
    public static final int DEFAULT_TIMEOUT_SEC = 300;
    public static final int MAX_TIMEOUT_SEC = 1800;
    private static final String NODE = "org.polarsys.capella.ai/codex";

    private CodexPreferences() {}

    public static boolean isCodex() { return CODEX.equals(getRuntime()); }
    public static String getRuntime() { return configuration().getRuntime(); }
    public static void setRuntime(String value) { configuration().setRuntime(value); }
    public static String getBinary() { return configuration().getBinary(); }
    public static void setBinary(String value) { configuration().setBinary(value); }
    public static String getModel() { return configuration().getModel(); }
    public static void setModel(String value) { configuration().setModel(value); }
    public static int getTimeoutSec() { return configuration().getTimeoutSec(); }
    public static void setTimeoutSec(int value) { configuration().setTimeoutSec(value); }
    public static Path resolveBinary() { return configuration().resolveBinary(); }
    public static void flush() { configuration().flush(); }

    /** Checks an unsaved binary field using the same discovery rules as the runtime. */
    public static Path resolveBinary(String configuredBinary) {
        return configuration().resolveBinary(configuredBinary);
    }

    public static Configuration configuration() {
        return new Configuration(InstanceScope.INSTANCE.getNode(NODE), System::getenv, BundledCodexRuntime::resolveBinary);
    }

    /** Injectable storage/environment keep discovery deterministic without touching global preferences. */
    public static final class Configuration {
        private final IEclipsePreferences preferences;
        private final Function<String, String> environment;
        private final Supplier<Path> bundledRuntime;

        /** Standalone discovery with no dependency on the active Eclipse bundle. */
        public Configuration(IEclipsePreferences preferences, Function<String, String> environment) {
            this(preferences, environment, () -> null);
        }

        public Configuration(IEclipsePreferences preferences, Function<String, String> environment, Supplier<Path> bundledRuntime) {
            this.preferences = Objects.requireNonNull(preferences);
            this.environment = Objects.requireNonNull(environment);
            this.bundledRuntime = Objects.requireNonNull(bundledRuntime);
        }

        public String getRuntime() { return runtime(value("CAPELLA_AI_RUNTIME", "runtime", CODEX)); }
        public void setRuntime(String value) { preferences.put("runtime", runtime(value)); }
        public String getBinary() { return value("CAPELLA_AI_CODEX_BINARY", "binary", ""); }
        public void setBinary(String value) { preferences.put("binary", clean(value)); }
        public String getModel() { return value("CAPELLA_AI_CODEX_MODEL", "model", ""); }
        public void setModel(String value) { preferences.put("model", clean(value)); }
        public boolean isRuntimeOverridden() { return overridden("CAPELLA_AI_RUNTIME"); }
        public boolean isBinaryOverridden() { return overridden("CAPELLA_AI_CODEX_BINARY"); }
        public boolean isModelOverridden() { return overridden("CAPELLA_AI_CODEX_MODEL"); }
        public int getTimeoutSec() {
            int timeout = preferences.getInt("timeoutSec", DEFAULT_TIMEOUT_SEC);
            return timeout > 0 && timeout <= MAX_TIMEOUT_SEC ? timeout : DEFAULT_TIMEOUT_SEC;
        }
        public void setTimeoutSec(int value) {
            if (value <= 0 || value > MAX_TIMEOUT_SEC) {
                throw new IllegalArgumentException("Codex timeout must be a whole number between 1 and 1800 seconds.");
            }
            preferences.putInt("timeoutSec", value);
        }

        public Path resolveBinary() { return resolveBinary(getBinary()); }

        public Path resolveBinary(String configuredBinary) {
            String configured = clean(configuredBinary);
            if (!configured.isEmpty()) {
                Path candidate = path(configured);
                if (usable(candidate)) return candidate.toAbsolutePath().normalize();
                throw new IllegalStateException("The configured Codex binary is not an executable file: " + configured
                    + ". Choose codex.exe in Capella AI preferences, or clear the path to discover it automatically.");
            }
            Path packaged = bundledRuntime.get();
            if (packaged != null) {
                if (usable(packaged)) return packaged.toAbsolutePath().normalize();
                throw new IllegalStateException("The bundled Codex runtime did not provide a usable executable. Reinstall Capella AI.");
            }
            String searchPath = environment.apply("PATH");
            if (searchPath != null) {
                for (String directory : searchPath.split(Pattern.quote(File.pathSeparator))) {
                    // Empty PATH entries refer to cwd in some shells; never discover a runtime that way.
                    if (directory.isBlank()) continue;
                    Path parent = optionalPath(unquote(directory.strip()));
                    if (parent == null) continue;
                    for (String name : List.of("codex.exe", "codex")) {
                        Path candidate = parent.resolve(name);
                        if (usable(candidate)) return candidate.toAbsolutePath().normalize();
                    }
                }
            }
            Path local = optionalPath(environment.apply("LOCALAPPDATA"));
            if (local != null) {
                Path directory = local.resolve("OpenAI").resolve("Codex").resolve("bin");
                if (Files.isDirectory(directory)) {
                    try (var children = Files.list(directory)) {
                        // Hashed install directories do not encode a version. The current executable has
                        // the newest file timestamp; sorting the executable avoids newer unrelated rg dirs.
                        Path candidate = children.filter(Files::isDirectory).map(child -> child.resolve("codex.exe"))
                            .filter(Configuration::usable)
                            .sorted(Comparator.comparingLong(Configuration::modified).reversed()
                                .thenComparing(Path::toString))
                            .findFirst().orElse(null);
                        if (candidate != null) return candidate.toAbsolutePath().normalize();
                    } catch (IOException | SecurityException exception) {
                        throw new IllegalStateException("Cannot inspect the installed Codex binaries in " + directory
                            + ". Choose the Codex executable in Capella AI preferences.", exception);
                    }
                }
            }
            throw new IllegalStateException("Codex was not found, and this installation has no bundled runtime for this platform."
                + " Choose an installed codex executable in Capella AI preferences or set CAPELLA_AI_CODEX_BINARY."
                + " You can also select Direct provider.");
        }

        public void flush() {
            try {
                preferences.flush();
            } catch (BackingStoreException exception) {
                throw new IllegalStateException("Failed to persist Codex runtime preferences.", exception);
            }
        }

        private String value(String environmentKey, String preferenceKey, String fallback) {
            String override = environment.apply(environmentKey);
            return override != null && !override.isBlank() ? override.strip() : preferences.get(preferenceKey, fallback).strip();
        }

        private boolean overridden(String key) {
            String value = environment.apply(key);
            return value != null && !value.isBlank();
        }

        private static String runtime(String value) {
            String runtime = clean(value).toLowerCase(Locale.ROOT);
            if (!CODEX.equals(runtime) && !DIRECT.equals(runtime)) {
                throw new IllegalArgumentException("Capella AI runtime must be codex or direct. Check CAPELLA_AI_RUNTIME"
                    + " or the runtime selection in preferences.");
            }
            return runtime;
        }

        private static String clean(String value) { return Objects.requireNonNull(value).strip(); }

        private static Path path(String value) {
            try {
                return Path.of(value);
            } catch (InvalidPathException exception) {
                throw new IllegalArgumentException("The Codex binary path is invalid. Choose the executable file.", exception);
            }
        }

        private static Path optionalPath(String value) {
            if (value == null || value.isBlank()) return null;
            try { return Path.of(value.strip()); }
            catch (InvalidPathException exception) { return null; }
        }

        private static String unquote(String value) {
            return value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2
                ? value.substring(1, value.length() - 1) : value;
        }

        private static boolean usable(Path path) {
            return Files.isRegularFile(path) && Files.isExecutable(path);
        }

        private static long modified(Path path) {
            try { return Files.getLastModifiedTime(path).toMillis(); }
            catch (IOException exception) { return Long.MIN_VALUE; }
        }
    }
}
