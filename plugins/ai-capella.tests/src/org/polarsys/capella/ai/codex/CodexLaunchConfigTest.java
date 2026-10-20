package org.polarsys.capella.ai.codex;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Capella's tools run through Codex Code Mode, which needs the codex-code-mode-host companion. */
public class CodexLaunchConfigTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void aCompleteInstallPasses() throws Exception {
        Path codex = Files.write(temporary.getRoot().toPath().resolve("codex.exe"), new byte[] {'M', 'Z'});
        Files.createFile(codex.resolveSibling("codex-code-mode-host.exe"));
        CodexLaunchConfig.requireCodeModeHost(codex);
        Path unix = Files.createDirectory(temporary.getRoot().toPath().resolve("unix")).resolve("codex");
        Files.write(unix, new byte[] {0x7f, 'E', 'L', 'F'}); Files.createFile(unix.resolveSibling("codex-code-mode-host"));
        CodexLaunchConfig.requireCodeModeHost(unix);
        assertTrue(CodexLaunchConfig.command(unix).contains("features.code_mode_host=true"));
    }

    @Test public void aBareBinaryWithoutTheHostIsRejectedWithInstructions() throws Exception {
        // Live on Linux: the model reported "native Capella tools unavailable" because the host was missing.
        Path codex = Files.write(temporary.getRoot().toPath().resolve("codex"), new byte[] {0x7f, 'E', 'L', 'F'});
        String message = assertThrows(IOException.class, () -> CodexLaunchConfig.requireCodeModeHost(codex)).getMessage();
        assertTrue(message, message.contains("codex-code-mode-host") && message.contains("Direct provider"));
    }

    @Test public void launcherScriptsAreTrustedToFindTheirOwnHost() throws Exception {
        // npm installs put a script on PATH; the real binaries live in its package.
        Path script = Files.writeString(temporary.getRoot().toPath().resolve("codex"), "#!/usr/bin/env node\n");
        CodexLaunchConfig.requireCodeModeHost(script);
        CodexLaunchConfig.requireCodeModeHost(Files.writeString(temporary.getRoot().toPath().resolve("codex.cmd"), "@node codex.js\r\n"));
    }
}
