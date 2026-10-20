package org.polarsys.capella.ai.codex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Versioned local integration: Capella tools own model access and reviewed writes. */
public final class CodexLaunchConfig {
    public static final String SUPPORTED_VERSION = "0.160.1";
    private CodexLaunchConfig() {}

    public static List<String> command(Path binary) {
        List<String> command = new ArrayList<>();
        command.add(binary.toString());
        for (String setting : List.of("features.shell_tool=false", "features.unified_exec=false",
                "features.apps=false", "features.plugins=false", "features.hooks=false",
                "features.computer_use=false", "features.browser_use=false",
                "features.multi_agent=false", "features.code_mode_host=true", "features.view_image=false",
                "features.sleep_tool=false", "features.token_budget=false", "features.deferred_executor=false",
                "tools.update_plan.enabled=false", "tools.experimental_request_user_input.enabled=false",
                "web_search=\"disabled\"", "mcp_servers={}")) {
            command.add("-c");
            command.add(setting);
        }
        command.add("app-server");
        command.add("--listen");
        command.add("stdio://");
        return List.copyOf(command);
    }

    /**
     * Capella's tools reach the model through Code Mode, which runs in the codex-code-mode-host companion
     * shipped beside codex. Without it the model only sees "host unavailable", so fail early and say why.
     * A launcher script (npm) finds its own packaged binaries and is not checked.
     */
    public static void requireCodeModeHost(Path binary) throws IOException {
        Path directory = binary.toAbsolutePath().getParent();
        if (directory != null && (Files.isRegularFile(directory.resolve("codex-code-mode-host"))
                || Files.isRegularFile(directory.resolve("codex-code-mode-host.exe")))) return;
        String name = String.valueOf(binary.getFileName()).toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".cmd") || name.endsWith(".bat") || name.endsWith(".ps1") || name.endsWith(".js")) return;
        try (var input = Files.newInputStream(binary)) {
            byte[] start = input.readNBytes(2);
            if (start.length == 2 && start[0] == '#' && start[1] == '!') return;
        }
        throw new IOException("The Codex executable at " + directory + " has no codex-code-mode-host beside it, and Capella's tools need it."
            + " Install the complete Codex release for this platform (codex and codex-code-mode-host in the same folder), or select Direct provider.");
    }

    public static void verifyVersion(Path binary) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(binary.toString(), "--version")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IOException("Codex version check timed out. Check the executable in Settings.");
            String version = new String(process.getInputStream().readNBytes(256), StandardCharsets.UTF_8).strip();
            if (process.exitValue() != 0 || !version.equals("codex-cli " + SUPPORTED_VERSION)) {
                throw new IOException("This Capella integration requires Codex " + SUPPORTED_VERSION
                    + ". Select a compatible executable in Settings.");
            }
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
