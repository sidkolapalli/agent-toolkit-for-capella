package org.polarsys.capella.ai.preferences;

import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.polarsys.capella.ai.codex.BundledCodexRuntime;
import org.polarsys.capella.ai.codex.CodexLaunchConfig;
import org.polarsys.capella.ai.codex.CodexPreferences;
import org.polarsys.capella.ai.llm.LLMService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public class LLMPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {
    private final CodexPreferences.Configuration runtimeSettings;
    private Combo runtime;
    private Group codexGroup;
    private Group directGroup;
    private Text codexBinary;
    private Text codexModel;
    private Text codexTimeout;
    private Label runtimeCaption;
    private Combo provider;
    private Text baseUrl;
    private Text model;
    private Text temperature;
    private Text maxTokens;
    private Text timeoutSec;
    private Text apiKey;
    private String initialApiKey;
    private Button enableActions;
    private Button confirmBeforeApply;
    private Button privacyTokenization;
    private Label status;
    private Button testButton;
    private volatile Thread connectionTest;

    public LLMPreferencePage() {
        this(CodexPreferences.configuration());
    }

    /** Injectable settings let native UI tests avoid changing saved runtime preferences. */
    public LLMPreferencePage(CodexPreferences.Configuration runtimeSettings) {
        this.runtimeSettings = Objects.requireNonNull(runtimeSettings);
    }

    @Override
    public void init(IWorkbench workbench) {}

    @Override
    protected Control createContents(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(2, false);
        layout.verticalSpacing = 8;
        root.setLayout(layout);
        mkLabel(root, "Runtime:");
        runtime = new Combo(root, SWT.DROP_DOWN | SWT.READ_ONLY);
        runtime.setItems(new String[] {"Codex app-server", "Direct provider"});
        runtime.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        try {
            runtime.select(CodexPreferences.CODEX.equals(runtimeSettings.getRuntime()) ? 0 : 1);
        } catch (IllegalArgumentException exception) {
            runtime.select(0);
            setErrorMessage(exception.getMessage());
        }
        runtime.setEnabled(!runtimeSettings.isRuntimeOverridden());
        if (runtimeSettings.isRuntimeOverridden()) {
            runtime.setToolTipText("CAPELLA_AI_RUNTIME selects the runtime for this launch.");
        }
        runtimeCaption = new Label(root, SWT.WRAP);
        GridData captionData = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
        captionData.widthHint = 440;
        runtimeCaption.setLayoutData(captionData);

        codexGroup = group(root, "Codex");
        mkLabel(codexGroup, "Binary path:");
        Composite binaryRow = new Composite(codexGroup, SWT.NONE);
        GridLayout binaryLayout = new GridLayout(2, false);
        binaryLayout.marginWidth = 0;
        binaryLayout.marginHeight = 0;
        binaryRow.setLayout(binaryLayout);
        binaryRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        codexBinary = text(binaryRow, runtimeSettings.getBinary());
        codexBinary.setMessage("Auto-discover installed Codex");
        codexBinary.setEnabled(!runtimeSettings.isBinaryOverridden());
        if (runtimeSettings.isBinaryOverridden())
            codexBinary.setToolTipText("Set by CAPELLA_AI_CODEX_BINARY for this launch.");
        Button browse = new Button(binaryRow, SWT.PUSH);
        browse.setText("Browse...");
        browse.setEnabled(!runtimeSettings.isBinaryOverridden());
        browse.addListener(SWT.Selection, event -> browseBinary());
        mkLabel(codexGroup, "Model (optional):");
        codexModel = text(codexGroup, runtimeSettings.getModel());
        codexModel.setMessage("Use Codex runtime default");
        codexModel.setEnabled(!runtimeSettings.isModelOverridden());
        if (runtimeSettings.isModelOverridden())
            codexModel.setToolTipText("Set by CAPELLA_AI_CODEX_MODEL for this launch.");
        mkLabel(codexGroup, "Timeout (sec):");
        codexTimeout = text(codexGroup, String.valueOf(runtimeSettings.getTimeoutSec()));
        codexTimeout.setToolTipText(
                "1 to 1800 seconds of active Codex work. Time waiting for your review is"
                    + " excluded.");

        directGroup = group(root, "Direct provider");
        mkLabel(directGroup, "Provider:");
        provider = new Combo(directGroup, SWT.DROP_DOWN | SWT.READ_ONLY);
        provider.setItems(
                new String[] {"openai", "anthropic", "azure-openai", "openrouter", "custom"});
        provider.select(indexOf(provider, LLMPreferences.getProvider()));
        provider.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        mkLabel(directGroup, "API Base URL:");
        baseUrl = text(directGroup, LLMPreferences.getBaseUrl());
        mkLabel(directGroup, "Model:");
        model = text(directGroup, LLMPreferences.getModel());
        mkLabel(directGroup, "Temperature:");
        temperature = text(directGroup, String.valueOf(LLMPreferences.getTemperature()));
        mkLabel(directGroup, "Max Tokens:");
        maxTokens = text(directGroup, String.valueOf(LLMPreferences.getMaxTokens()));
        mkLabel(directGroup, "Timeout (sec):");
        timeoutSec = text(directGroup, String.valueOf(LLMPreferences.getTimeoutSec()));
        mkLabel(directGroup, "API Key:");
        apiKey = new Text(directGroup, SWT.BORDER | SWT.PASSWORD);
        apiKey.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        // Read secure storage only when the user selects Direct provider.

        enableActions =
                checkbox(
                        root, "Allow generated code to run", LLMPreferences.isToolActionsEnabled());
        confirmBeforeApply =
                checkbox(
                        root,
                        "Review code before running",
                        AdvancedPreferences.isConfirmBeforeApplyEnabled());
        privacyTokenization =
                checkbox(
                        root,
                        "Replace quoted names and UUIDs before sending",
                        LLMPreferences.isPrivacyTokenizationEnabled());
        testButton = new Button(root, SWT.PUSH);
        testButton.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 2, 1));
        status = new Label(root, SWT.WRAP);
        GridData statusData = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
        statusData.widthHint = 440;
        status.setLayoutData(statusData);
        runtime.addListener(SWT.Selection, event -> updateRuntimeControls());
        testButton.addListener(SWT.Selection, event -> doTest());
        codexBinary.addModifyListener(event -> updateCodexStatus());
        updateRuntimeControls();
        return root;
    }

    private void browseBinary() {
        FileDialog dialog = new FileDialog(getShell(), SWT.OPEN);
        dialog.setText("Choose the Codex executable");
        dialog.setFilterExtensions(new String[] {"codex.exe", "*"});
        dialog.setFilterNames(new String[] {"Codex executable", "All files"});
        String selected = dialog.open();
        if (selected != null) codexBinary.setText(selected);
    }

    private boolean codexSelected() {
        return runtime.getSelectionIndex() == 0;
    }

    private void updateRuntimeControls() {
        boolean codex = codexSelected();
        show(codexGroup, codex);
        show(directGroup, !codex);
        runtimeCaption.setText(
                codex
                        ? "Integration preview · Uses the local Codex app-server and your Codex"
                              + " sign-in. No provider API key is needed here."
                        : "Connect directly to a provider using its model, endpoint and API key.");
        if (runtimeSettings.isRuntimeOverridden()) {
            runtimeCaption.setText(
                    runtimeCaption.getText()
                            + "\nCAPELLA_AI_RUNTIME selects the runtime for this launch.");
        }
        testButton.setText(codex ? "Check Codex runtime" : "Test Connection");
        if (codex) {
            updateCodexStatus();
        } else {
            if (initialApiKey == null) {
                initialApiKey = LLMPreferences.getApiKey();
                apiKey.setText(initialApiKey);
            }
            status.setText("");
        }
        runtime.getParent().layout(true, true);
    }

    private void updateCodexStatus() {
        if (status == null || status.isDisposed() || !codexSelected()) return;
        try {
            String configured = codexBinary.getText().strip();
            if (configured.isEmpty()) {
                status.setText(
                        BundledCodexRuntime.isPackagedAvailable()
                                ? "Bundled Codex " + BundledCodexRuntime.VERSION + " is available."
                                : "Codex will be discovered when checked or when a conversation"
                                      + " starts.");
            } else {
                Path binary = Path.of(configured);
                if (!Files.isRegularFile(binary) || !Files.isExecutable(binary)) {
                    throw new IllegalArgumentException(
                            "The configured Codex binary is not an executable file. Choose the"
                                + " Codex executable (codex.exe on Windows, codex elsewhere) or"
                                + " clear the path to discover it automatically.");
                }
                status.setText("Codex executable selected: " + binary);
            }
            status.setText(
                    status.getText()
                            + "\nConnection and sign-in are checked when a conversation starts.");
        } catch (RuntimeException exception) {
            status.setText(
                    "Check the Codex binary path. Choose the Codex executable (codex.exe on"
                        + " Windows, codex elsewhere) or clear the path to discover it"
                        + " automatically.");
            if (exception instanceof IllegalArgumentException
                    && !(exception instanceof java.nio.file.InvalidPathException)) {
                status.setText(exception.getMessage());
            }
        }
        status.getParent().layout(true, true);
    }

    private void doTest() {
        if (connectionTest != null) return;
        boolean codex = codexSelected();
        try {
            savePrefs(false);
        } catch (Exception exception) {
            status.setText("ERROR: " + exception.getMessage());
            status.getParent().layout(true, true);
            return;
        }
        var display = status.getDisplay();
        testButton.setEnabled(false);
        runtime.setEnabled(false);
        String configuredBinary = codex ? runtimeSettings.getBinary() : "";
        status.setText(codex ? "Preparing and checking Codex runtime…" : "Testing connection...");
        status.getParent().layout(true, true);
        Thread worker =
                new Thread(
                        () -> {
                            String result;
                            try {
                                if (codex) {
                                    Path binary = runtimeSettings.resolveBinary(configuredBinary);
                                    verifyCodexRuntime(binary);
                                    result =
                                            "OK: Codex "
                                                    + CodexLaunchConfig.SUPPORTED_VERSION
                                                    + " is ready.\n"
                                                    + "Connection and sign-in are checked when a"
                                                    + " conversation starts.";
                                } else result = "OK: " + LLMService.get().testConnection();
                            } catch (Exception exception) {
                                if (exception instanceof InterruptedException)
                                    Thread.currentThread().interrupt();
                                String detail = String.valueOf(exception.getMessage());
                                result =
                                        codex && detail.contains("codex-code-mode-host")
                                                ? "ERROR: " + detail
                                                : codex
                                                        ? "ERROR: Codex runtime could not be"
                                                              + " checked. Select Codex "
                                                                + CodexLaunchConfig
                                                                        .SUPPORTED_VERSION
                                                                + " or reinstall Capella AI, then"
                                                                + " try again."
                                                        : "ERROR: " + exception.getMessage();
                            } finally {
                                connectionTest = null;
                            }
                            String message = result;
                            if (!display.isDisposed()) {
                                display.asyncExec(
                                        () -> {
                                            if (status.isDisposed() || testButton.isDisposed())
                                                return;
                                            status.setText(message);
                                            testButton.setEnabled(true);
                                            runtime.setEnabled(
                                                    !runtimeSettings.isRuntimeOverridden());
                                            status.getParent().layout(true, true);
                                        });
                            }
                        },
                        "Capella AI connection test");
        worker.setDaemon(true);
        connectionTest = worker;
        worker.start();
    }

    /** Runs only on the connection-test worker; tests can supply a deterministic version check. */
    protected void verifyCodexRuntime(Path binary) throws Exception {
        CodexLaunchConfig.verifyVersion(binary);
        CodexLaunchConfig.requireCodeModeHost(binary);
    }

    @Override
    public void dispose() {
        Thread worker = connectionTest;
        if (worker != null) worker.interrupt();
        super.dispose();
    }

    @Override
    public boolean performOk() {
        try {
            savePrefs(true);
            setErrorMessage(null);
            return true;
        } catch (Exception exception) {
            setErrorMessage(exception.getMessage());
            return false;
        }
    }

    private void savePrefs(boolean flush) throws Exception {
        if (codexSelected()) {
            int timeout = positiveInteger(codexTimeout, "Codex timeout");
            runtimeSettings.setTimeoutSec(timeout);
            runtimeSettings.setBinary(codexBinary.getText());
            runtimeSettings.setModel(codexModel.getText());
        } else {
            double parsedTemperature = Double.parseDouble(temperature.getText().trim());
            int parsedMaxTokens = positiveInteger(maxTokens, "Max tokens");
            int parsedTimeout = positiveInteger(timeoutSec, "Provider timeout");
            if (!Double.isFinite(parsedTemperature)
                    || parsedTemperature < 0
                    || parsedTemperature > 2) {
                throw new IllegalArgumentException("Temperature must be a number between 0 and 2.");
            }
            if (model.getText().isBlank())
                throw new IllegalArgumentException("Enter a provider model name.");
            java.net.URI endpoint = java.net.URI.create(baseUrl.getText().trim());
            if (!("https".equalsIgnoreCase(endpoint.getScheme())
                            || "http".equalsIgnoreCase(endpoint.getScheme()))
                    || endpoint.getHost() == null) {
                throw new IllegalArgumentException(
                        "API Base URL must be a complete HTTP or HTTPS URL.");
            }
            LLMPreferences.setProvider(provider.getText());
            LLMPreferences.setBaseUrl(baseUrl.getText().trim());
            LLMPreferences.setModel(model.getText().trim());
            LLMPreferences.setTemperature(parsedTemperature);
            LLMPreferences.setMaxTokens(parsedMaxTokens);
            LLMPreferences.setTimeoutSec(parsedTimeout);
            String enteredApiKey = apiKey.getText().trim();
            if (!enteredApiKey.isEmpty() && !enteredApiKey.equals(initialApiKey.trim())) {
                LLMPreferences.setApiKey(enteredApiKey);
                initialApiKey = enteredApiKey;
            }
        }
        runtimeSettings.setRuntime(
                codexSelected() ? CodexPreferences.CODEX : CodexPreferences.DIRECT);
        runtimeSettings
                .getRuntime(); // Reject an invalid environment override instead of implying it was
                               // corrected.
        LLMPreferences.setToolActionsEnabled(enableActions.getSelection());
        AdvancedPreferences.setConfirmBeforeApplyEnabled(confirmBeforeApply.getSelection());
        LLMPreferences.setPrivacyTokenizationEnabled(privacyTokenization.getSelection());
        if (flush) {
            runtimeSettings.flush();
            LLMPreferences.flush();
            AdvancedPreferences.flush();
        }
    }

    private static int positiveInteger(Text field, String label) {
        try {
            int value = Integer.parseInt(field.getText().trim());
            if (value > 0) return value;
        } catch (NumberFormatException exception) {
            // Report the field name and recovery instead of a raw parse exception.
        }
        throw new IllegalArgumentException(label + " must be a positive whole number.");
    }

    private static Group group(Composite parent, String title) {
        Group group = new Group(parent, SWT.NONE);
        group.setText(title);
        group.setLayout(new GridLayout(2, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
        return group;
    }

    private static void show(Control control, boolean visible) {
        ((GridData) control.getLayoutData()).exclude = !visible;
        control.setVisible(visible);
    }

    private static Text text(Composite parent, String value) {
        Text text = new Text(parent, SWT.BORDER);
        text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        text.setText(value);
        return text;
    }

    private static Button checkbox(Composite parent, String label, boolean selected) {
        Button button = new Button(parent, SWT.CHECK);
        button.setText(label);
        button.setSelection(selected);
        button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        return button;
    }

    private static void mkLabel(Composite parent, String text) {
        Label label = new Label(parent, SWT.NONE);
        label.setText(text);
    }

    private static int indexOf(Combo combo, String value) {
        String[] items = combo.getItems();
        for (int index = 0; index < items.length; index++) {
            if (items[index].equalsIgnoreCase(value)) return index;
        }
        return 0;
    }
}
