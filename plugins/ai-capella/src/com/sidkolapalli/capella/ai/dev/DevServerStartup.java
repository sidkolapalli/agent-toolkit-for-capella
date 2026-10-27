package com.sidkolapalli.capella.ai.dev;

import org.eclipse.emf.common.util.URI;
import org.eclipse.ui.IStartup;
import com.sidkolapalli.capella.ai.session.SessionBridge;

public final class DevServerStartup implements IStartup {

    @Override
    public void earlyStartup() {
        if (!shouldAutoStart()) {
            return;
        }
        int port = resolvePort();
        DevServer.start(port);
        openSessionIfRequested();
    }

    private boolean shouldAutoStart() {
        String port = System.getenv("CAPELLA_AI_DEVSERVER_PORT");
        String auto = System.getenv("CAPELLA_AI_DEVSERVER_AUTOSTART");
        return (port != null && !port.isBlank()) || (auto != null && !auto.isBlank());
    }

    private int resolvePort() {
        String port = System.getenv("CAPELLA_AI_DEVSERVER_PORT");
        if (port == null || port.isBlank()) {
            return 8765;
        }
        try {
            int parsed = Integer.parseInt(port.trim());
            if (parsed > 0 && parsed <= 65535) return parsed;
            System.err.println("[DevServer] Port must be between 1 and 65535; using default 8765.");
            return 8765;
        } catch (NumberFormatException ex) {
            System.err.println(
                    "[DevServer] Invalid CAPELLA_AI_DEVSERVER_PORT '"
                            + port
                            + "', using default 8765.");
            return 8765;
        }
    }

    private void openSessionIfRequested() {
        String airdPath = System.getenv("CAPELLA_AI_DEVSERVER_OPEN_AIRD");
        if (airdPath == null || airdPath.isBlank()) {
            return;
        }
        Thread opener =
                new Thread(
                        () -> {
                            try {
                                Thread.sleep(5000);
                            } catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                            }
                            try {
                                URI uri = URI.createFileURI(airdPath);
                                SessionBridge.openSession(uri);
                            } catch (Exception ex) {
                                System.err.println(
                                        "[DevServer] Failed to open session: " + ex.getMessage());
                            }
                        },
                        "capella-ai-devserver-session-opener");
        opener.setDaemon(true);
        opener.start();
    }
}
