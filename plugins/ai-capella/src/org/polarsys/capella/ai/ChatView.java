package org.polarsys.capella.ai;

import java.io.File;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.viewpoint.DRepresentation;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;
import org.osgi.service.prefs.BackingStoreException;
import org.polarsys.capella.ai.llm.LLMService;
import org.polarsys.capella.ai.llm.ToolRouter;
import org.polarsys.capella.ai.llm.ToolRouter.PlanExecutionResult;
import org.polarsys.capella.ai.llm.ToolRouter.ToolPlan;
import org.polarsys.capella.ai.preferences.LLMPreferences;
import org.polarsys.capella.ai.session.SessionBridge;
import org.polarsys.capella.ai.tools.DiagramCommands;
import org.polarsys.capella.ai.ui.PlanEditorDialog;

/**
 * Chat-centric view providing inline model tools and immediate LLM configuration.
 */
public class ChatView extends ViewPart {

    private static final String[] PROVIDER_DISPLAY_NAMES = {"OpenAI", "Anthropic", "Azure", "Custom"};
    private static final String[] OPENAI_MODELS = {"gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1"};
    private static final String[] ANTHROPIC_MODELS = {"claude-3-haiku", "claude-3-sonnet", "claude-3-opus"};

    private Text chatLog;
    private Text prompt;
    private Button sendButton;
    private Button enableActions;
    private Button useLlmMapping;

    private Text elementUuid;
    private Text newName;
    private Text newElementName;

    private Combo providerCombo;
    private Combo modelCombo;
    private Text baseUrlText;
    private Text temperatureText;
    private Text maxTokensText;
    private Text timeoutText;
    private Label apiKeyStatus;
    private Label settingsStatus;
    private Label connectionSummary;

    private boolean sending;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));

        createConversationSection(parent);
        createModelToolsSection(parent);
        createSettingsSection(parent);

        loadPreferencesIntoUi();
        updateConnectionSummary();
        updateSendButtonState();
    }

    private void createConversationSection(Composite parent) {
        Group conversation = new Group(parent, SWT.NONE);
        conversation.setText("Conversation");
        conversation.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        conversation.setLayout(new GridLayout(1, false));

        connectionSummary = new Label(conversation, SWT.WRAP);
        connectionSummary.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        chatLog = new Text(conversation, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.READ_ONLY | SWT.WRAP);
        GridData logData = new GridData(SWT.FILL, SWT.FILL, true, true);
        logData.heightHint = 280;
        chatLog.setLayoutData(logData);

        Composite promptRow = new Composite(conversation, SWT.NONE);
        promptRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        promptRow.setLayout(new GridLayout(2, false));

        prompt = new Text(promptRow, SWT.BORDER);
        prompt.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        prompt.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if ((e.character == SWT.CR || e.character == SWT.LF) && !sending && sendButton.isEnabled()) {
                    onSend();
                }
            }
        });

        sendButton = new Button(promptRow, SWT.PUSH);
        sendButton.setText("Send");
        sendButton.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        sendButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onSend();
            }
        });

        Composite toggles = new Composite(conversation, SWT.NONE);
        toggles.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        toggles.setLayout(new GridLayout(3, false));

        enableActions = new Button(toggles, SWT.CHECK);
        enableActions.setText("Enable tool actions");
        enableActions.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                persistPreference(() -> LLMPreferences.setToolActionsEnabled(enableActions.getSelection()));
                updateConnectionSummary();
            }
        });

        useLlmMapping = new Button(toggles, SWT.CHECK);
        useLlmMapping.setText("Use LLM mapping for imports");
        useLlmMapping.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                persistPreference(() -> LLMPreferences.setUseLlmMapping(useLlmMapping.getSelection()));
                updateConnectionSummary();
            }
        });

        Button explainModel = new Button(toggles, SWT.PUSH);
        explainModel.setText("Explain model");
        explainModel.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onExplainModel();
            }
        });
    }

    private void createModelToolsSection(Composite parent) {
        Group tools = new Group(parent, SWT.NONE);
        tools.setText("Model & Diagram Tools");
        tools.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        tools.setLayout(new GridLayout(3, false));

        Button listDiagrams = new Button(tools, SWT.PUSH);
        listDiagrams.setText("List diagrams");
        listDiagrams.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        listDiagrams.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onListDiagrams();
            }
        });

        Button createLab = new Button(tools, SWT.PUSH);
        createLab.setText("Create LAB diagram");
        createLab.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        createLab.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onCreateLab();
            }
        });

        Button importDrawio = new Button(tools, SWT.PUSH);
        importDrawio.setText("Import draw.io");
        importDrawio.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        importDrawio.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onImportDrawio();
            }
        });

        Group advanced = new Group(tools, SWT.NONE);
        advanced.setText("Element operations");
        advanced.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
        advanced.setLayout(new GridLayout(3, false));

        new Label(advanced, SWT.NONE).setText("Element UUID");
        elementUuid = new Text(advanced, SWT.BORDER);
        elementUuid.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        Button addToDiagram = new Button(advanced, SWT.PUSH);
        addToDiagram.setText("Reveal on current diagram");
        addToDiagram.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 3, 1));
        addToDiagram.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onAddElement();
            }
        });

        new Label(advanced, SWT.NONE).setText("New name");
        newName = new Text(advanced, SWT.BORDER);
        newName.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button rename = new Button(advanced, SWT.PUSH);
        rename.setText("Rename");
        rename.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        rename.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onRenameElement();
            }
        });

        Button delete = new Button(advanced, SWT.PUSH);
        delete.setText("Delete");
        delete.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        delete.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onDeleteElement();
            }
        });

        new Label(advanced, SWT.NONE).setText("New element name");
        newElementName = new Text(advanced, SWT.BORDER);
        newElementName.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button createComponent = new Button(advanced, SWT.PUSH);
        createComponent.setText("Create Logical Component");
        createComponent.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        createComponent.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onCreateElement("LogicalComponent");
            }
        });

        Button createFunction = new Button(advanced, SWT.PUSH);
        createFunction.setText("Create Logical Function");
        createFunction.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        createFunction.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onCreateElement("LogicalFunction");
            }
        });
    }

    private void createSettingsSection(Composite parent) {
        Group settings = new Group(parent, SWT.NONE);
        settings.setText("LLM Connection");
        settings.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        settings.setLayout(new GridLayout(6, false));

        new Label(settings, SWT.NONE).setText("Provider");
        providerCombo = new Combo(settings, SWT.DROP_DOWN | SWT.READ_ONLY);
        providerCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        providerCombo.setItems(PROVIDER_DISPLAY_NAMES);
        providerCombo.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onProviderChanged();
            }
        });

        new Label(settings, SWT.NONE).setText("Model");
        modelCombo = new Combo(settings, SWT.DROP_DOWN);
        modelCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(settings, SWT.NONE);
        new Label(settings, SWT.NONE);

        new Label(settings, SWT.NONE).setText("Base URL");
        baseUrlText = new Text(settings, SWT.BORDER);
        baseUrlText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 5, 1));

        new Label(settings, SWT.NONE).setText("Temperature");
        temperatureText = new Text(settings, SWT.BORDER);
        temperatureText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(settings, SWT.NONE).setText("Max tokens");
        maxTokensText = new Text(settings, SWT.BORDER);
        maxTokensText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(settings, SWT.NONE).setText("Timeout (s)");
        timeoutText = new Text(settings, SWT.BORDER);
        timeoutText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(settings, SWT.NONE);
        new Label(settings, SWT.NONE);
        new Label(settings, SWT.NONE);

        Composite buttonRow = new Composite(settings, SWT.NONE);
        buttonRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 6, 1));
        buttonRow.setLayout(new GridLayout(4, false));

        Button saveButton = new Button(buttonRow, SWT.PUSH);
        saveButton.setText("Save");
        saveButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                saveSettings();
            }
        });

        Button testButton = new Button(buttonRow, SWT.PUSH);
        testButton.setText("Test connection");
        testButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                testConnection();
            }
        });

        Button refreshButton = new Button(buttonRow, SWT.PUSH);
        refreshButton.setText("Reload from preferences");
        refreshButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                loadPreferencesIntoUi();
                setSettingsStatus("Reloaded from preferences", true);
            }
        });

        Button apiKeyButton = new Button(buttonRow, SWT.PUSH);
        apiKeyButton.setText("Store API key…");
        apiKeyButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                promptForApiKey();
            }
        });

        Composite apiRow = new Composite(settings, SWT.NONE);
        apiRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 6, 1));
        apiRow.setLayout(new GridLayout(2, false));

        apiKeyStatus = new Label(apiRow, SWT.NONE);
        apiKeyStatus.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        settingsStatus = new Label(settings, SWT.WRAP);
        GridData statusData = new GridData(SWT.FILL, SWT.CENTER, true, false, 6, 1);
        statusData.widthHint = 380;
        settingsStatus.setLayoutData(statusData);
    }

    private void onSend() {
        if (sending) {
            return;
        }
        final String text = prompt.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        final Session session = SessionBridge.getActiveSession();
        if (session == null) {
            append("No active Capella session.");
            return;
        }
        final DRepresentation currentDiagram = DiagramCommands.getLastOpened();
        final boolean actionsEnabled = enableActions.getSelection();

        append("You: " + text);
        prompt.setText("");
        sending = true;
        updateSendButtonState();

        Job job = new Job("Capella AI request") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                ToolRouter.Result res = ToolRouter.handleChat(session, text);
                Display.getDefault().asyncExec(() -> handleChatResult(session, currentDiagram, actionsEnabled, res));
                return Status.OK_STATUS;
            }
        };
        job.setUser(false);
        job.schedule();
    }

    private void handleChatResult(Session session, DRepresentation diagram, boolean actionsEnabled, ToolRouter.Result result) {
        try {
            if (result.error != null) {
                append("Assistant ERROR: " + result.error);
            }
            if (result.reply != null && !result.reply.isBlank()) {
                append("Assistant: " + result.reply);
            }

            ToolPlan plan = result.plan;
            if (plan != null && !plan.isEmpty()) {
                appendPlanSummary(plan.describe(), actionsEnabled);
                if (actionsEnabled) {
                    PlanEditorDialog dialog = new PlanEditorDialog(getSite().getShell(), plan);
                    if (dialog.open() == IDialogConstants.OK_ID) {
                        ToolPlan selection = dialog.getFilteredPlan();
                        if (selection != null && !selection.isEmpty()) {
                            schedulePlanExecution(session, diagram, selection);
                        } else {
                            append("Plan not applied (no actions selected).");
                        }
                    } else {
                        append("Plan not applied (cancelled by user).");
                    }
                }
            }
        } finally {
            sending = false;
            updateSendButtonState();
        }
    }

    private void schedulePlanExecution(Session session, DRepresentation diagram, ToolPlan plan) {
        Job job = new Job("Apply AI plan") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                PlanExecutionResult execution = ToolRouter.applyPlan(session, diagram, plan);
                Display.getDefault().asyncExec(() -> {
                    append("Plan actions applied: " + execution.applied);
                    for (String error : execution.errors) {
                        append("Plan warning: " + error);
                    }
                    updateConnectionSummary();
                });
                return Status.OK_STATUS;
            }
        };
        job.schedule();
    }

    private void appendPlanSummary(List<String> lines, boolean actionsEnabled) {
        append("Plan contains " + lines.size() + " action(s):");
        for (String line : lines) {
            append(" - " + line);
        }
        if (!actionsEnabled) {
            append("Tool actions are disabled; plan will not be applied.");
        }
    }

    private void onExplainModel() {
        try {
            Session session = SessionBridge.getActiveSession();
            append(org.polarsys.capella.ai.model.ModelSummary.summarize(session));
        } catch (Exception ex) {
            append("Explain failed: " + ex.getMessage());
        }
    }

    private void append(String text) {
        Display display = Display.getDefault();
        if (display.isDisposed()) {
            return;
        }
        display.asyncExec(() -> {
            if (chatLog != null && !chatLog.isDisposed()) {
                chatLog.append(text + System.lineSeparator());
                chatLog.setTopIndex(chatLog.getLineCount() - 1);
            }
        });
    }

    private void onListDiagrams() {
        try {
            List<org.polarsys.capella.ai.browse.DiagramBrowser.DiagramInfo> diagrams = org.polarsys.capella.ai.browse.DiagramBrowser.listActive();
            if (diagrams.isEmpty()) {
                append("No diagrams found in active session.");
            } else {
                append("Active diagrams:");
                diagrams.forEach(d -> append(" - " + d));
            }
        } catch (Exception ex) {
            append("Error listing diagrams: " + ex.getMessage());
        }
    }

    private void onCreateLab() {
        try {
            Session session = SessionBridge.getActiveSession();
            if (session == null) {
                append("No active session.");
                return;
            }
            var target = org.polarsys.capella.ai.model.ArchitectureLocator.findTargetElement(session, "LA");
            if (target == null) {
                append("LA architecture not found.");
                return;
            }
            String description = org.polarsys.capella.ai.tools.DiagramMappings.descriptionFor("LA", "LAB");
            var rep = org.polarsys.capella.ai.tools.DiagramCommands.createAndOpenDiagram(target, description, "New Logical Architecture");
            append(rep != null ? "Created and opened: " + rep.getName() : "Failed to create diagram.");
        } catch (Exception ex) {
            append("Create LAB failed: " + ex.getMessage());
        }
    }

    private void onImportDrawio() {
        FileDialog fd = new FileDialog(getSite().getShell(), SWT.OPEN);
        fd.setFilterExtensions(new String[] {"*.drawio", "*.*"});
        String path = fd.open();
        if (path == null) {
            return;
        }
        append("Importing draw.io: " + path);
        boolean useMapping = useLlmMapping.getSelection();
        Job job = new Job("Draw.io import") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                try {
                    var res = org.polarsys.capella.ai.importer.DrawioImporter.importLogical(new File(path), useMapping);
                    append(String.format("Import complete: nodes=%d, links=%d, diagram=%s", res.nodesCreated, res.edgesProcessed, res.diagramName));
                } catch (Exception ex) {
                    append("Import ERROR: " + ex.getMessage());
                }
                return Status.OK_STATUS;
            }
        };
        job.schedule();
    }

    private void onAddElement() {
        String id = elementUuid.getText().trim();
        if (id.isEmpty()) {
            append("Enter an element UUID.");
            return;
        }
        try {
            Session session = SessionBridge.getActiveSession();
            if (session == null) {
                append("No active session.");
                return;
            }
            var rep = DiagramCommands.getLastOpened();
            if (rep == null) {
                append("Open or create a diagram first.");
                return;
            }
            int resolved = DiagramCommands.addElementsByUuid(session, rep, List.of(id));
            DiagramCommands.arrange(rep);
            append("Reveal requested; resolved " + resolved + " item(s).");
        } catch (Exception ex) {
            append("Reveal failed: " + ex.getMessage());
        }
    }

    private void onRenameElement() {
        String id = elementUuid.getText().trim();
        String nn = newName.getText().trim();
        if (id.isEmpty() || nn.isEmpty()) {
            append("Provide UUID and new name.");
            return;
        }
        Session session = SessionBridge.getActiveSession();
        if (session == null) {
            append("No active session.");
            return;
        }
        boolean ok = org.polarsys.capella.ai.model.ModelOps.renameByUuid(session, id, nn);
        append(ok ? "Renamed element." : "Rename failed.");
    }

    private void onDeleteElement() {
        String id = elementUuid.getText().trim();
        if (id.isEmpty()) {
            append("Provide UUID.");
            return;
        }
        Session session = SessionBridge.getActiveSession();
        if (session == null) {
            append("No active session.");
            return;
        }
        boolean ok = org.polarsys.capella.ai.model.ModelOps.deleteByUuid(session, id);
        append(ok ? "Deleted element." : "Delete failed.");
    }

    private void onCreateElement(String simpleEClass) {
        String nm = newElementName.getText().trim();
        if (nm.isEmpty()) {
            append("Enter a name before creating.");
            return;
        }
        try {
            Session session = SessionBridge.getActiveSession();
            if (session == null) {
                append("No active session.");
                return;
            }
            var la = org.polarsys.capella.ai.model.ArchitectureLocator.findTargetElement(session, "LA");
            if (la == null) {
                append("LA architecture not found.");
                return;
            }
            var created = org.polarsys.capella.ai.model.ElementFactory.createUnder(session, la, simpleEClass, nm);
            if (created == null) {
                append("Creation failed for " + simpleEClass);
                return;
            }
            append("Created " + simpleEClass + ": " + nm);
            var rep = DiagramCommands.getLastOpened();
            if (rep != null) {
                DiagramCommands.addElementsByUuid(session, rep, List.of(getUuid(created)));
                DiagramCommands.arrange(rep);
                append("Diagram refreshed with new element.");
            }
        } catch (Exception ex) {
            append("Create element failed: " + ex.getMessage());
        }
    }

    private void loadPreferencesIntoUi() {
        String provider = LLMPreferences.getProvider();
        providerCombo.setText(displayNameForProvider(provider));
        modelCombo.setItems(modelSuggestionsFor(provider));
        modelCombo.setText(LLMPreferences.getModel());
        baseUrlText.setText(LLMPreferences.getBaseUrl());
        temperatureText.setText(String.valueOf(LLMPreferences.getTemperature()));
        maxTokensText.setText(String.valueOf(LLMPreferences.getMaxTokens()));
        timeoutText.setText(String.valueOf(LLMPreferences.getTimeoutSec()));
        if (enableActions != null && !enableActions.isDisposed()) {
            enableActions.setSelection(LLMPreferences.isToolActionsEnabled());
        }
        if (useLlmMapping != null && !useLlmMapping.isDisposed()) {
            useLlmMapping.setSelection(LLMPreferences.isUseLlmMapping());
        }

        String key = LLMPreferences.getApiKey();
        boolean hasKey = key != null && !key.isBlank();
        apiKeyStatus.setText(hasKey ? "API key stored" : "API key not set (using env?)");
        updateConnectionSummary();
    }

    private void onProviderChanged() {
        String provider = providerPreference(providerCombo.getText());
        modelCombo.setItems(modelSuggestionsFor(provider));
        if (modelCombo.getItemCount() > 0) {
            modelCombo.select(0);
        }
        if (!"custom".equals(provider)) {
            baseUrlText.setText(defaultBaseUrl(provider));
        }
        updateConnectionSummary();
    }

    private void saveSettings() {
        try {
            String provider = providerPreference(providerCombo.getText());
            String base = baseUrlText.getText().trim();
            String model = modelCombo.getText().trim();
            double temperature = parseDouble(temperatureText.getText(), 0.2, "temperature");
            int tokens = parseInt(maxTokensText.getText(), 2000, "max tokens");
            int timeout = parseInt(timeoutText.getText(), 60, "timeout");

            LLMPreferences.setProvider(provider);
            if (!base.isBlank()) {
                LLMPreferences.setBaseUrl(base);
            }
            if (!model.isBlank()) {
                LLMPreferences.setModel(model);
            }
            LLMPreferences.setTemperature(temperature);
            LLMPreferences.setMaxTokens(tokens);
            LLMPreferences.setTimeoutSec(timeout);
            LLMPreferences.flush();

            setSettingsStatus("Preferences saved", true);
            updateConnectionSummary();
        } catch (IllegalArgumentException | IllegalStateException ex) {
            setSettingsStatus(ex.getMessage(), false);
        }
    }

    private void promptForApiKey() {
        InputDialog dialog = new InputDialog(
            getSite().getShell(),
            "Store API key",
            "Enter the API key for " + providerCombo.getText() + ":",
            "",
            value -> (value == null || value.isBlank()) ? "API key must not be empty" : null
        );
        if (dialog.open() == Window.OK) {
            try {
                LLMPreferences.setApiKey(dialog.getValue().trim());
                setSettingsStatus("API key stored", true);
                loadPreferencesIntoUi();
            } catch (Exception e) {
                MessageDialog.openError(getSite().getShell(), "API key error", e.getMessage());
                setSettingsStatus("Failed to store API key", false);
            }
        }
    }

    private void testConnection() {
        setSettingsStatus("Testing…", true);
        Job job = new Job("Test LLM connection") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                try {
                    String result = LLMService.get().testConnection();
                    setSettingsStatus(result, true);
                } catch (Exception ex) {
                    setSettingsStatus(ex.getMessage(), false);
                }
                return Status.OK_STATUS;
            }
        };
        job.schedule();
    }

    private void setSettingsStatus(String message, boolean ok) {
        Display display = Display.getDefault();
        if (display.isDisposed()) {
            return;
        }
        display.asyncExec(() -> {
            if (settingsStatus != null && !settingsStatus.isDisposed()) {
                settingsStatus.setText(message);
                Color color = display.getSystemColor(ok ? SWT.COLOR_DARK_GREEN : SWT.COLOR_DARK_RED);
                settingsStatus.setForeground(color);
            }
        });
    }

    private void updateConnectionSummary() {
        String provider = providerPreference(providerCombo.getText());
        String message = String.format(
            "Provider: %s • Model: %s • Tool actions: %s • LLM mapping: %s",
            providerCombo.getText(),
            modelCombo != null ? modelCombo.getText() : LLMPreferences.getModel(),
            enableActions != null && enableActions.getSelection() ? "ON" : "OFF",
            useLlmMapping != null && useLlmMapping.getSelection() ? "ON" : "OFF"
        );
        if (connectionSummary != null && !connectionSummary.isDisposed()) {
            connectionSummary.setText(message);
        }
    }

    private void updateSendButtonState() {
        boolean hasText = prompt != null && !prompt.isDisposed() && !prompt.getText().trim().isEmpty();
        boolean enabled = hasText && !sending;
        if (sendButton != null && !sendButton.isDisposed()) {
            sendButton.setEnabled(enabled);
        }
    }

    private void persistPreference(Runnable write) {
        try {
            write.run();
            LLMPreferences.flush();
        } catch (IllegalStateException ex) {
            Activator.getDefault().getLog().log(new Status(IStatus.ERROR, Activator.PLUGIN_ID, ex.getMessage(), ex));
        }
    }

    private static String getUuid(Object eo) {
        try {
            if (eo instanceof org.eclipse.emf.ecore.EObject) {
                var obj = (org.eclipse.emf.ecore.EObject) eo;
                var feature = obj.eClass().getEStructuralFeature("uuid");
                if (feature != null) {
                    Object value = obj.eGet(feature);
                    if (value != null) {
                        return String.valueOf(value);
                    }
                }
            }
        } catch (Exception ignore) {}
        return String.valueOf(eo.hashCode());
    }

    private static String providerPreference(String displayName) {
        if (displayName == null) {
            return "openai";
        }
        switch (displayName.toLowerCase()) {
            case "openai":
                return "openai";
            case "anthropic":
                return "anthropic";
            case "azure":
                return "azure";
            default:
                return "custom";
        }
    }

    private static String displayNameForProvider(String provider) {
        if (provider == null) {
            return "OpenAI";
        }
        switch (provider.toLowerCase()) {
            case "openai":
                return "OpenAI";
            case "anthropic":
                return "Anthropic";
            case "azure":
                return "Azure";
            default:
                return "Custom";
        }
    }

    private static String[] modelSuggestionsFor(String provider) {
        switch (provider) {
            case "anthropic":
                return ANTHROPIC_MODELS;
            case "openai":
            case "azure":
                return OPENAI_MODELS;
            default:
                return new String[0];
        }
    }

    private static String defaultBaseUrl(String provider) {
        switch (provider) {
            case "anthropic":
                return "https://api.anthropic.com";
            case "azure":
                return "";
            case "openai":
            default:
                return "https://api.openai.com";
        }
    }

    private static int parseInt(String text, int fallback, String label) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid " + label + ": " + trimmed);
        }
    }

    private static double parseDouble(String text, double fallback, String label) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid " + label + ": " + trimmed);
        }
    }

    @Override
    public void setFocus() {
        if (prompt != null && !prompt.isDisposed()) {
            prompt.setFocus();
        }
    }

}
