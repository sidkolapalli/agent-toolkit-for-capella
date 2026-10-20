package org.polarsys.capella.ai.dev;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;

/** Eclipse view for managing the DevServer. */
public class DevServerView extends ViewPart {

    public static final String ID = "org.polarsys.capella.ai.dev.DevServerView";

    private Text portText;
    private Button startButton;
    private Button stopButton;
    private Label statusLabel;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(2, false));

        // Port input
        Label portLabel = new Label(parent, SWT.NONE);
        portLabel.setText("Port:");

        portText = new Text(parent, SWT.BORDER);
        portText.setText("8765");
        portText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Start button
        startButton = new Button(parent, SWT.PUSH);
        startButton.setText("Start Server");
        startButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        startButton.addListener(
                SWT.Selection,
                e -> {
                    int port = Integer.parseInt(portText.getText().trim());
                    DevServer.start(port);
                    updateStatus();
                });

        // Stop button
        stopButton = new Button(parent, SWT.PUSH);
        stopButton.setText("Stop Server");
        stopButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        stopButton.addListener(
                SWT.Selection,
                e -> {
                    DevServer.stop();
                    updateStatus();
                });

        // Status
        statusLabel = new Label(parent, SWT.WRAP);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1));

        // Instructions
        Label instructions = new Label(parent, SWT.WRAP);
        instructions.setText(
                "Dev Server Endpoints:\n\n"
                    + "POST /execute - Execute Groovy code\n"
                    + "GET /status - Check status\n"
                    + "POST /mcp - Model Context Protocol (reads; edits are reviewed)\n\n"
                    + "Every request needs Authorization: Bearer <token>;\n"
                    + "the running server writes it to ~/.capella-ai/devserver-<port>.token.\n\n"
                    + "Example:\n"
                    + "curl -X POST http://localhost:8765/execute \\\n"
                    + "  -H \"Authorization: Bearer $(cat ~/.capella-ai/devserver-8765.token)\""
                    + " \\\n"
                    + "  -d 'api.findAll(\"SystemFunction\")'");
        instructions.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1));

        updateStatus();
    }

    private void updateStatus() {
        boolean running = DevServer.isRunning();
        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
        portText.setEnabled(!running);
        statusLabel.setText(running ? "Server is RUNNING" : "Server is STOPPED");
    }

    @Override
    public void setFocus() {
        startButton.setFocus();
    }
}
