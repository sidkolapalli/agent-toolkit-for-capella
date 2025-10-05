package org.polarsys.capella.ai;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.jface.bindings.keys.KeyStroke;
import org.eclipse.jface.bindings.keys.ParseException;
import org.eclipse.jface.fieldassist.ContentProposalAdapter;
import org.eclipse.jface.fieldassist.IContentProposal;
import org.eclipse.jface.fieldassist.IContentProposalListener;
import org.eclipse.jface.fieldassist.IContentProposalProvider;
import org.eclipse.jface.fieldassist.TextContentAdapter;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.business.api.session.SessionManager;
import org.eclipse.sirius.viewpoint.DRepresentation;
import org.eclipse.sirius.viewpoint.DSemanticDecorator;
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
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.part.ViewPart;
import org.osgi.service.prefs.BackingStoreException;
import org.polarsys.capella.ai.actions.FunctionalChainActions;
import org.polarsys.capella.ai.actions.OperationalActions;
import org.polarsys.capella.ai.actions.RequirementActions;
import org.polarsys.capella.ai.actions.SystemActions;
import org.polarsys.capella.ai.llm.LLMService;
import org.polarsys.capella.ai.llm.ToolRouter;
import org.polarsys.capella.ai.llm.ToolRouter.PlanExecutionResult;
import org.polarsys.capella.ai.llm.ToolRouter.PlanItem;
import org.polarsys.capella.ai.llm.ToolRouter.ToolPlan;
import org.polarsys.capella.ai.model.MethodologyCoach;
import org.polarsys.capella.ai.model.MethodologyCoach.Guidance;
import org.polarsys.capella.ai.model.MethodologyCoach.Step;
import org.polarsys.capella.ai.model.ModelSummary;
import org.polarsys.capella.ai.model.index.ModelIndex;
import org.polarsys.capella.ai.model.index.ModelIndex.IndexedElement;
import org.polarsys.capella.ai.preferences.LLMPreferences;
import org.polarsys.capella.ai.session.SessionBridge;
import org.polarsys.capella.ai.tools.DiagramCommands;
import org.polarsys.capella.ai.session.PlanDryRun;
import org.polarsys.capella.ai.session.PlanDryRun.DryRunResult;
import org.polarsys.capella.ai.telemetry.Telemetry;
import org.polarsys.capella.ai.ui.ConversationController;
import org.polarsys.capella.ai.ui.ConversationPanel;
import org.polarsys.capella.ai.ui.PlanEditorDialog;

/**
 * Chat-centric view providing inline model tools and immediate LLM configuration.
 */
public class ChatView extends ViewPart {

    private static final String[] PROVIDER_DISPLAY_NAMES = {"OpenAI", "Anthropic", "Azure", "Custom"};
    private static final String[] OPENAI_MODELS = {"gpt-5", "gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1"};
    private static final String[] ANTHROPIC_MODELS = {"claude-3-haiku", "claude-3-sonnet", "claude-3-opus"};
    private static final int MAX_HISTORY_MESSAGES = 12;
    private static final char[] COMMAND_AUTO_ACTIVATION = {'/'};
    private static final List<QuickCommand> QUICK_COMMANDS = List.of(
            new QuickCommand(
                    MethodologyCoach.COMMAND_ARCADIA_NEXT,
                    "Arcadia next steps",
                    "Analyse the current model state and recommend the next Arcadia methodology steps. Provide concrete TOOL_PLAN ideas to progress the outstanding work items.",
                    "Ask the assistant for guided Arcadia next steps based on the current model."),
            new QuickCommand(
                    "/explain-selection",
                    "Explain selection",
                    "Explain the currently selected element in Capella. Summarise its purpose, allocated functions, linked requirements, and open validation warnings. Recommend the next Arcadia modelling steps.",
                    "Summarise the selected element with allocations, requirements, validation hints, and next steps."),
            new QuickCommand(
                    "/scaffold-component",
                    "Scaffold logical component",
                    "Draft a TOOL_PLAN that creates a logical component named <ComponentName>, adds two placeholder logical functions, and connects it to relevant capabilities or parent components if they exist. Include short descriptions for the new elements.",
                    "Create a logical component scaffold with functions and capability links."),
            new QuickCommand(
                    "/extend-chain",
                    "Extend functional chain",
                    "Draft a TOOL_PLAN that extends the functional chain <ChainName> by sequencing functions [Function A, Function B] with functional exchanges and attaching relevant scenarios. Warn if referenced elements are missing.",
                    "Extend an existing functional chain with new functions, exchanges, and scenarios."),
            new QuickCommand(
                    "/sync-physical",
                    "Sync logical ↔ physical",
                    "Draft a TOOL_PLAN that synchronises the logical component <LogicalComponent> into the physical architecture, creating missing physical components, cloning child hierarchy when useful, and adding trace links.",
                    "Synchronise logical and physical components, ensuring traces and optional hierarchy copy."),
            new QuickCommand(
                    "/cover-requirements",
                    "Cover requirements",
                    "Draft a TOOL_PLAN that creates requirements for <Goal>, links them to the components/functions mentioned in recent context, and records coverage status notes.",
                    "Author requirements and link them to model elements with coverage notes."),
            new QuickCommand(
                    "/diagram-walkthrough",
                    "Open diagram walkthrough",
                    "Draft a TOOL_PLAN that opens or creates a LAB diagram focused on the current selection and summarises the impacted elements. Include any follow-up recommendations in plain text after the plan.",
                    "Open or create a LAB diagram centred on the current selection and recap the changes.")
    );

    private ConversationPanel conversationPanel;
    private ConversationController conversationController;
    private Text prompt;
    private Button sendButton;
    private Button enableActions;
    private Button useLlmMapping;
    private Button telemetryToggle;
    private Button stopButton;

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
    private final List<String> lastPlanSummary = new ArrayList<>();
    private Text contextSummary;
    private ISelectionListener selectionListener;
    private ToolRouter.ToolStream activeStream;
    private volatile boolean streamingCancelled;
    private boolean streamingReplyStarted;
    private boolean lastReplyStreamed;
    private StringBuilder streamingBuffer;
    private ScrolledComposite scroller;
    private Composite rootComposite;
    private IPartListener2 partListener;
    private static final boolean SHOW_SESSION_PICKER = true;
    private static final boolean SHOW_ADVANCED = false;

    // Session picker state
    private Combo sessionCombo;
    private java.util.List<Session> sessionChoices = new ArrayList<>();
    private Session selectedSession;

    @Override
    public void createPartControl(Composite parent) {
        scroller = new ScrolledComposite(parent, SWT.V_SCROLL | SWT.H_SCROLL);
        scroller.setExpandHorizontal(true);
        scroller.setExpandVertical(true);
        scroller.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        rootComposite = new Composite(scroller, SWT.NONE);
        rootComposite.setLayout(new GridLayout(1, false));
        scroller.setContent(rootComposite);

        // Tabs: Chat (minimal) and Advanced (everything else)
        org.eclipse.swt.widgets.TabFolder tabs = new org.eclipse.swt.widgets.TabFolder(rootComposite, SWT.NONE);
        tabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // Chat tab
        org.eclipse.swt.widgets.TabItem chatTab = new org.eclipse.swt.widgets.TabItem(tabs, SWT.NONE);
        chatTab.setText("Chat");
        Composite chatComposite = new Composite(tabs, SWT.NONE);
        chatComposite.setLayout(new GridLayout(1, false));
        chatTab.setControl(chatComposite);
        createChatOnlySection(chatComposite);

        // Optional Advanced tab (hidden for simple chat)
        if (SHOW_ADVANCED) {
            org.eclipse.swt.widgets.TabItem advTab = new org.eclipse.swt.widgets.TabItem(tabs, SWT.NONE);
            advTab.setText("Advanced");
            Composite advComposite = new Composite(tabs, SWT.NONE);
            advComposite.setLayout(new GridLayout(1, false));
            advTab.setControl(advComposite);
            createAdvancedBasicsSection(advComposite);
            createModelToolsSection(advComposite);
            createSettingsSection(advComposite);
        }
        refreshScrollArea();

        loadPreferencesIntoUi();
        updateConnectionSummary();
        updateSendButtonState();
        registerSelectionListener();
        updateContextSummary(getSelectedOrActiveSession());
        initializeFromActiveSession();
    }

    /**
     * Minimal chat UI (no context cards, no toggles, no settings).
     */
    private void createChatOnlySection(Composite parent) {
        if (SHOW_SESSION_PICKER) {
            Composite sessionRow = new Composite(parent, SWT.NONE);
            sessionRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            sessionRow.setLayout(new GridLayout(3, false));
            new Label(sessionRow, SWT.NONE).setText("Session");
            sessionCombo = new Combo(sessionRow, SWT.DROP_DOWN | SWT.READ_ONLY);
            sessionCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            Button refreshSessions = new Button(sessionRow, SWT.PUSH);
            refreshSessions.setText("Refresh");
            refreshSessions.addSelectionListener(new SelectionAdapter() { @Override public void widgetSelected(SelectionEvent e) { refreshSessionChoices(); }});
            sessionCombo.addSelectionListener(new SelectionAdapter() {
                @Override public void widgetSelected(SelectionEvent e) {
                    int idx = sessionCombo.getSelectionIndex();
                    if (idx <= 0) { selectedSession = null; }
                    else if (idx - 1 < sessionChoices.size()) { selectedSession = sessionChoices.get(idx - 1); }
                    updateContextSummary(getSelectedOrActiveSession());
                }
            });
            refreshSessionChoices();
        }

        // Minimal connection bar
        createConnectionBar(parent);
        Group conversation = new Group(parent, SWT.NONE);
        conversation.setText("Conversation");
        conversation.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        conversation.setLayout(new GridLayout(1, false));

        // No quick setup card; use the inline connection bar

        conversationPanel = new ConversationPanel(conversation);
        conversationController = new ConversationController(conversationPanel);
        GridData panelData = new GridData(SWT.FILL, SWT.FILL, true, true);
        panelData.heightHint = 400;
        conversationPanel.scroller.setLayoutData(panelData);

        Composite promptRow = new Composite(conversation, SWT.NONE);
        promptRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        promptRow.setLayout(new GridLayout(2, false));

        prompt = new Text(promptRow, SWT.BORDER);
        prompt.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        prompt.setToolTipText("Type / for quick commands, Ctrl+Space for suggestions, or Cmd/Ctrl+P for the palette.");
        prompt.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if ((e.character == SWT.CR || e.character == SWT.LF) && !sending && sendButton.isEnabled()) {
                    onSend();
                    return;
                }
                if ((e.stateMask & SWT.MOD1) != 0 && (e.keyCode == 'p' || e.keyCode == 'P')) {
                    openCommandPalette();
                    e.doit = false;
                }
            }
        });
        prompt.addModifyListener(e -> updateSendButtonState());
        installPromptAssist(prompt);

        // No gear or commands button in minimal chat

        sendButton = new Button(promptRow, SWT.PUSH);
        sendButton.setText("Send");
        sendButton.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        sendButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onSend();
            }
        });

        // No Stop button; let responses finish naturally
    }

    private boolean needsQuickSetup() {
        try {
            String provider = LLMPreferences.getProvider();
            String model = LLMPreferences.getModel();
            String key = LLMPreferences.getApiKey();
            return provider == null || provider.isBlank() || model == null || model.isBlank() || key == null || key.isBlank();
        } catch (Exception ex) {
            return true;
        }
    }

    private void createConnectionBar(Composite parent) {
        Composite bar = new Composite(parent, SWT.NONE);
        bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        bar.setLayout(new GridLayout(6, false));
        new Label(bar, SWT.NONE).setText("Model");
        Combo model = new Combo(bar, SWT.DROP_DOWN);
        model.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        String provider = LLMPreferences.getProvider();
        if (provider == null || provider.isBlank()) provider = "openai";
        model.setItems(modelSuggestionsFor(provider));
        String currentModel = LLMPreferences.getModel();
        if (currentModel != null) model.setText(currentModel);
        model.addSelectionListener(new SelectionAdapter() { @Override public void widgetSelected(SelectionEvent e) {
            String m = model.getText(); if (m != null && !m.isBlank()) { try { LLMPreferences.setModel(m); LLMPreferences.flush(); } catch (Exception ignore) {} updateConnectionSummary(); }
        }});
        model.addModifyListener(e -> { String m = model.getText(); if (m != null && !m.isBlank()) { try { LLMPreferences.setModel(m); LLMPreferences.flush(); } catch (Exception ignore) {} updateConnectionSummary(); }});

        new Label(bar, SWT.NONE).setText("API Key");
        Text key = new Text(bar, SWT.BORDER | SWT.PASSWORD);
        key.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        key.addModifyListener(e -> { String v = key.getText(); if (v != null) { try { LLMPreferences.setApiKey(v.trim()); LLMPreferences.flush(); } catch (Exception ignore) {} }});

        Button test = new Button(bar, SWT.PUSH);
        test.setText("Test");
        test.addSelectionListener(new SelectionAdapter() { @Override public void widgetSelected(SelectionEvent e) { testConnection(); }});

        // Quick open when no session is active (always available for convenience)
        Button openModel = new Button(bar, SWT.PUSH);
        openModel.setText("Open model…");
        openModel.setToolTipText("Open a Capella .aird file and activate the session");
        openModel.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                FileDialog dlg = new FileDialog(getSite().getShell(), SWT.OPEN);
                dlg.setText("Open Capella model (.aird)");
                dlg.setFilterExtensions(new String[] {"*.aird"});
                String path = dlg.open();
                if (path != null && !path.isBlank()) {
                    try {
                        org.eclipse.emf.common.util.URI uri = org.eclipse.emf.common.util.URI.createFileURI(path);
                        org.eclipse.sirius.business.api.session.Session s = org.polarsys.capella.ai.session.SessionBridge.openSession(uri);
                        if (s != null && s.isOpen()) {
                            selectedSession = s;
                            if (sessionCombo != null && !sessionCombo.isDisposed()) {
                                refreshSessionChoices();
                            }
                            updateContextSummary(getSelectedOrActiveSession());
                            conversationController.addSystemMessage("Opened model: " + new java.io.File(path).getName());
                        } else {
                            conversationController.addSystemMessage("Failed to open model.");
                        }
                    } catch (Exception ex) {
                        conversationController.addSystemMessage("Open failed: " + ex.getMessage());
                    }
                }
            }
        });
    }

    /**
     * Advanced basics: context summary and primary toggles.
     */
    private void createAdvancedBasicsSection(Composite parent) {
        Group togglesGroup = new Group(parent, SWT.NONE);
        togglesGroup.setText("Controls");
        togglesGroup.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        togglesGroup.setLayout(new GridLayout(3, false));

        enableActions = new Button(togglesGroup, SWT.CHECK);
        enableActions.setText("Enable tool actions");
        enableActions.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                persistPreference(() -> LLMPreferences.setToolActionsEnabled(enableActions.getSelection()));
                updateConnectionSummary();
            }
        });

        useLlmMapping = new Button(togglesGroup, SWT.CHECK);
        useLlmMapping.setText("Use LLM mapping for imports");
        useLlmMapping.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                persistPreference(() -> LLMPreferences.setUseLlmMapping(useLlmMapping.getSelection()));
                updateConnectionSummary();
            }
        });

        Button explainModel = new Button(togglesGroup, SWT.PUSH);
        explainModel.setText("Explain model");
        explainModel.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onExplainModel();
            }
        });

        Group contextGroup = new Group(parent, SWT.NONE);
        contextGroup.setText("Context");
        contextGroup.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));
        contextGroup.setLayout(new GridLayout(1, false));

        contextSummary = new Text(contextGroup, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.WRAP | SWT.READ_ONLY);
        GridData contextData = new GridData(SWT.FILL, SWT.FILL, true, true);
        contextData.heightHint = 200;
        contextSummary.setLayoutData(contextData);
    }

    private void initializeFromActiveSession() {
        try {
            Session session = SessionBridge.getActiveSession();
            if (session != null) {
                // Proactively surface current model context into the conversation
                String summary = ModelSummary.summarize(session);
                if (summary != null && !summary.isBlank() && conversationController != null) {
                    conversationController.addSystemMessage(summary);
                }
            } else if (conversationController != null) {
                conversationController.addSystemMessage("No active Capella session detected. Open a model to begin.");
            }
        } catch (Exception ignore) {}

        // Listen for part activation to refresh context when an editor becomes active
        if (partListener == null && getSite() != null && getSite().getWorkbenchWindow() != null) {
            var page = getSite().getWorkbenchWindow().getActivePage();
            if (page != null) {
                partListener = new IPartListener2() {
                    @Override public void partActivated(IWorkbenchPartReference partRef) { updateContextSummary(SessionBridge.getActiveSession(), null); }
                    @Override public void partBroughtToTop(IWorkbenchPartReference partRef) {}
                    @Override public void partClosed(IWorkbenchPartReference partRef) {}
                    @Override public void partDeactivated(IWorkbenchPartReference partRef) {}
                    @Override public void partOpened(IWorkbenchPartReference partRef) {}
                    @Override public void partHidden(IWorkbenchPartReference partRef) {}
                    @Override public void partVisible(IWorkbenchPartReference partRef) {}
                    @Override public void partInputChanged(IWorkbenchPartReference partRef) {}
                };
                page.addPartListener(partListener);
            }
        }
    }

    private void createConversationSection(Composite parent) {
        Group conversation = new Group(parent, SWT.NONE);
        conversation.setText("Conversation");
        conversation.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        conversation.setLayout(new GridLayout(1, false));

        connectionSummary = new Label(conversation, SWT.WRAP);
        connectionSummary.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        conversationPanel = new ConversationPanel(conversation);
        conversationController = new ConversationController(conversationPanel);
        GridData panelData = new GridData(SWT.FILL, SWT.FILL, true, true);
        panelData.heightHint = 400;
        conversationPanel.scroller.setLayoutData(panelData);

        Composite promptRow = new Composite(conversation, SWT.NONE);
        promptRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        promptRow.setLayout(new GridLayout(4, false));

        prompt = new Text(promptRow, SWT.BORDER);
        prompt.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        prompt.setToolTipText("Type / for quick commands, Ctrl+Space for suggestions, or Cmd/Ctrl+P for the palette.");
        prompt.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if ((e.character == SWT.CR || e.character == SWT.LF) && !sending && sendButton.isEnabled()) {
                    onSend();
                    return;
                }
                if ((e.stateMask & SWT.MOD1) != 0 && (e.keyCode == 'p' || e.keyCode == 'P')) {
                    openCommandPalette();
                    e.doit = false;
                }
            }
        });
        // Enable/disable Send live as the user types
        prompt.addModifyListener(e -> updateSendButtonState());
        installPromptAssist(prompt);

        Button commandsButton = new Button(promptRow, SWT.PUSH);
        commandsButton.setText("Commands");
        commandsButton.setToolTipText("Open the quick command palette (Cmd/Ctrl+P)");
        commandsButton.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        commandsButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                openCommandPalette();
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

        stopButton = new Button(promptRow, SWT.PUSH);
        stopButton.setText("Stop");
        stopButton.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        stopButton.setEnabled(false);
        stopButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                cancelStreaming();
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

        Group contextGroup = new Group(conversation, SWT.NONE);
        contextGroup.setText("Context cards");
        contextGroup.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));
        contextGroup.setLayout(new GridLayout(1, false));

        contextSummary = new Text(contextGroup, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.WRAP | SWT.READ_ONLY);
        GridData contextData = new GridData(SWT.FILL, SWT.FILL, true, true);
        contextData.heightHint = 200;
        contextSummary.setLayoutData(contextData);
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

        Group operational = new Group(tools, SWT.NONE);
        operational.setText("Operational templates");
        operational.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
        operational.setLayout(new GridLayout(3, false));

        Button createMission = new Button(operational, SWT.PUSH);
        createMission.setText("Create mission");
        createMission.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        createMission.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onCreateMission();
            }
        });

        Button createOperationalActivity = new Button(operational, SWT.PUSH);
        createOperationalActivity.setText("Create activity");
        createOperationalActivity.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        createOperationalActivity.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onCreateOperationalActivity();
            }
        });

        Button createOperationalExchange = new Button(operational, SWT.PUSH);
        createOperationalExchange.setText("Create exchange");
        createOperationalExchange.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        createOperationalExchange.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onCreateOperationalExchange();
            }
        });

        Button linkCapability = new Button(operational, SWT.PUSH);
        linkCapability.setText("Link activity↔capability");
        GridData linkData = new GridData(SWT.FILL, SWT.CENTER, false, false, 3, 1);
        linkCapability.setLayoutData(linkData);
        linkCapability.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onLinkOperationalToCapability();
            }
        });

        Group system = new Group(tools, SWT.NONE);
        system.setText("System templates");
        system.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
        system.setLayout(new GridLayout(3, false));

        Button systemComponent = new Button(system, SWT.PUSH);
        systemComponent.setText("Create system component");
        systemComponent.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        systemComponent.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onCreateSystemComponent();
            }
        });

        Button systemFunction = new Button(system, SWT.PUSH);
        systemFunction.setText("Create system function");
        systemFunction.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        systemFunction.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onCreateSystemFunction();
            }
        });

        Button systemInterface = new Button(system, SWT.PUSH);
        systemInterface.setText("Create system interface");
        systemInterface.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        systemInterface.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onCreateSystemInterface();
            }
        });

        Button allocateCapability = new Button(system, SWT.PUSH);
        allocateCapability.setText("Allocate capability");
        GridData allocateData = new GridData(SWT.FILL, SWT.CENTER, false, false, 3, 1);
        allocateCapability.setLayoutData(allocateData);
        allocateCapability.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onAllocateCapability();
            }
        });

        Group functional = new Group(tools, SWT.NONE);
        functional.setText("Functional chain templates");
        functional.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
        functional.setLayout(new GridLayout(3, false));

        Button createChain = new Button(functional, SWT.PUSH);
        createChain.setText("Create chain");
        createChain.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        createChain.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onCreateFunctionalChain();
            }
        });

        Button addFunction = new Button(functional, SWT.PUSH);
        addFunction.setText("Add function");
        addFunction.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        addFunction.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onExtendFunctionalChain();
            }
        });

        Button addExchange = new Button(functional, SWT.PUSH);
        addExchange.setText("Add exchange");
        addExchange.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        addExchange.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onSequenceFunctionalExchange();
            }
        });

        Button attachScenario = new Button(functional, SWT.PUSH);
        attachScenario.setText("Attach scenario");
        GridData attachData = new GridData(SWT.FILL, SWT.CENTER, false, false, 3, 1);
        attachScenario.setLayoutData(attachData);
        attachScenario.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onAttachScenario();
            }
        });

        Group requirements = new Group(tools, SWT.NONE);
        requirements.setText("Requirement templates");
        requirements.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
        requirements.setLayout(new GridLayout(3, false));

        Button createRequirement = new Button(requirements, SWT.PUSH);
        createRequirement.setText("Create requirement");
        createRequirement.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        createRequirement.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onCreateRequirement();
            }
        });

        Button linkRequirement = new Button(requirements, SWT.PUSH);
        linkRequirement.setText("Link requirement");
        linkRequirement.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        linkRequirement.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onLinkRequirement();
            }
        });

        Button coverageRequirement = new Button(requirements, SWT.PUSH);
        coverageRequirement.setText("Record coverage");
        GridData coverageData = new GridData(SWT.FILL, SWT.CENTER, false, false, 3, 1);
        coverageRequirement.setLayoutData(coverageData);
        coverageRequirement.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onRecordRequirementCoverage();
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

        Button searchModel = new Button(advanced, SWT.PUSH);
        searchModel.setText("Search model");
        searchModel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        searchModel.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onSearchModel();
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

        telemetryToggle = new Button(buttonRow, SWT.CHECK);
        telemetryToggle.setText("Telemetry (opt-in)");
        telemetryToggle.setToolTipText("Log anonymous assistant usage metrics locally to prioritise improvements.");
        telemetryToggle.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        telemetryToggle.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                persistPreference(() -> LLMPreferences.setTelemetryEnabled(telemetryToggle.getSelection()));
                updateConnectionSummary();
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
        // Handle built-in commands (no LLM)
        if ("/undo".equalsIgnoreCase(text) || "/undo-last".equalsIgnoreCase(text)) {
            Session s = getSelectedOrActiveSession();
            if (s == null) {
                conversationController.addSystemMessage("No active Capella session.");
            } else {
                if (undoLastPlan(s)) {
                    conversationController.addSystemMessage("Undid last change.");
                } else {
                    conversationController.addSystemMessage("Nothing to undo.");
                }
            }
            prompt.setText("");
            return;
        }
        final Session session = getSelectedOrActiveSession();
        if (session == null) {
            conversationController.addSystemMessage("No active Capella session.");
            return;
        }
        final DRepresentation currentDiagram = DiagramCommands.getLastOpened();
        final boolean actionsEnabled = isActionsEnabled();
        final List<LLMService.Message> history = conversationController.getHistory();

        conversationController.addUserMessage(text);
        prompt.setText("");
        sending = true;
        streamingCancelled = false;
        streamingReplyStarted = false;
        lastReplyStreamed = false;
        streamingBuffer = new StringBuilder();
        updateSendButtonState();
        if (stopButton != null && !stopButton.isDisposed()) {
            stopButton.setEnabled(true);
        }

        try {
            activeStream = ToolRouter.handleChatStreaming(session, text, history, new ToolRouter.StreamingConsumer() {
                @Override
                public void onPartial(String delta) {
                    handleStreamPartial(delta);
                }

                @Override
                public void onComplete(ToolRouter.Result result) {
                    Display.getDefault().asyncExec(() -> handleStreamCompleted(session, currentDiagram, actionsEnabled, result));
                }

                @Override
                public void onError(String message) {
                    Display.getDefault().asyncExec(() -> handleStreamError(message));
                }

                @Override
                public void onCancelled() {
                    Display.getDefault().asyncExec(() -> handleStreamCancelled());
                }

                @Override
                public boolean isCancelled() {
                    return streamingCancelled;
                }
            });
        } catch (Exception ex) {
            conversationController.addSystemMessage("Streaming unavailable: " + ex.getMessage());
            finishStreaming();
            runSynchronousRequest(session, text, currentDiagram, actionsEnabled, history);
            return;
        }
    }

    private boolean undoLastPlan(Session session) {
        try {
            var domain = session.getTransactionalEditingDomain();
            if (domain != null && domain.getCommandStack().canUndo()) {
                domain.getCommandStack().undo();
                return true;
            }
        } catch (Exception ignore) {}
        return false;
    }

    private void handleChatResult(Session session, DRepresentation diagram, boolean actionsEnabled, ToolRouter.Result result) {
        try {
            if (result.error != null) {
                conversationController.addError(result.error);
                Telemetry.recordPlanError("plan-generation", result.error);
            }
            if (result.reply != null && !result.reply.isBlank()) {
                if (!lastReplyStreamed) {
                    conversationController.addAssistantMessage(result.reply);
                }
            }

            lastReplyStreamed = false;

            ToolPlan plan = result.plan;
            if (plan != null && !plan.isEmpty()) {
                Telemetry.recordPlanSuggested(plan);
                DryRunResult dryRun = PlanDryRun.evaluate(session, plan);
                // Build preview summary with dry-run warnings inline
                List<String> summary = new ArrayList<>(plan.describe());
                if (dryRun != null && dryRun.isAvailable() && dryRun.issues() != null && !dryRun.issues().isEmpty()) {
                    summary.add("");
                    summary.add("Warnings:");
                    for (String issue : dryRun.issues()) {
                        summary.add(" • " + issue);
                    }
                }
                var preview = conversationController.showPlanPreview(plan, summary);
                // Merge validator issues (from parse) and dry-run issues for chips
                java.util.List<String> chips = new java.util.ArrayList<>();
                if (result.validationIssues != null) chips.addAll(result.validationIssues);
                if (dryRun != null && dryRun.issues() != null) chips.addAll(dryRun.issues());
                if (!chips.isEmpty()) {
                    preview.setChips(chips);
                }
                // Attach dry-run details for inline diff-like preview
                {
                    java.util.List<String> details = new java.util.ArrayList<>();
                    int step = 1;
                    for (var item : plan.items()) {
                        var oc = (dryRun != null) ? dryRun.outcomeFor(item.getIndex()) : null;
                        String label = String.format("%02d. %s", step++, item.getTypeLabel());
                        if (oc == null) {
                            details.add(label + " — (no simulation)");
                        } else if (oc.isSuccess()) {
                            details.add(label + " ✓ " + oc.getMessage());
                            for (String d : oc.getDetails()) details.add("    • " + d);
                        } else {
                            details.add(label + " ✕ " + oc.getMessage());
                            for (String d : oc.getDetails()) details.add("    • " + d);
                        }
                    }
                    preview.setDetails(details);
                }
                // Inline actions: Apply (with selected items), Edit…, Dismiss
                preview.setOnAccept(() -> {
                    if (!isActionsEnabled()) {
                        conversationController.addSystemMessage("Tool actions are disabled; enable them in Advanced > Controls.");
                        return;
                    }
                    ToolPlan selected = preview.getSelectedPlan();
                    if (selected == null || selected.isEmpty()) {
                        conversationController.addSystemMessage("Plan not applied (no actions selected).");
                        return;
                    }
                    schedulePlanExecution(session, diagram, selected);
                });
                preview.setOnEdit(() -> {
                    PlanEditorDialog dialog = new PlanEditorDialog(getSite().getShell(), plan, dryRun);
                    if (dialog.open() == IDialogConstants.OK_ID) {
                        ToolPlan selection = dialog.getFilteredPlan();
                        if (selection != null && !selection.isEmpty()) {
                            schedulePlanExecution(session, diagram, selection);
                        } else {
                            conversationController.addSystemMessage("Plan not applied (no actions selected).");
                        }
                    } else {
                        conversationController.addSystemMessage("Plan editor cancelled.");
                    }
                });
                preview.setOnCancel(() -> {
                    // Remove the inline preview box to avoid lingering actions after dismiss
                    try {
                        if (preview != null && !preview.isDisposed()) {
                            preview.dispose();
                        }
                    } catch (Exception ignore) {
                        // Best-effort dispose; continue to inform the user
                    }
                    conversationController.addSystemMessage("Plan dismissed.");
                });
            }
            updateContextSummary(session, null);
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
                    conversationController.addSystemMessage("✓ Plan actions applied: " + execution.applied);
                    for (String error : execution.errors) {
                        conversationController.addSystemMessage("⚠ Plan warning: " + error);
                    }
                    Telemetry.recordPlanExecution(plan, execution);
                    updateConnectionSummary();
                });
                return Status.OK_STATUS;
            }
        };
        job.schedule();
    }


    private void handleStreamPartial(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        if (streamingBuffer != null) {
            streamingBuffer.append(delta);
        }
        Display display = Display.getDefault();
        display.asyncExec(() -> {
            if (conversationController.isDisposed()) {
                return;
            }
            if (!streamingReplyStarted) {
                conversationController.startStreaming();
                streamingReplyStarted = true;
                lastReplyStreamed = true;
            }
            conversationController.appendStreaming(delta);
        });
    }

    private void handleStreamCompleted(Session session, DRepresentation diagram, boolean actionsEnabled, ToolRouter.Result result) {
        if (!conversationController.isDisposed() && streamingReplyStarted) {
            conversationController.finishStreaming(streamingBuffer != null ? streamingBuffer.toString() : "");
        }
        lastReplyStreamed = streamingReplyStarted;
        handleChatResult(session, diagram, actionsEnabled, result);
        finishStreaming();
    }

    private void handleStreamError(String message) {
        if (!streamingCancelled && message != null && !message.isBlank()) {
            conversationController.addError(message);
            Telemetry.recordPlanError("plan-stream", message);
        }
        finishStreaming();
    }

    private void handleStreamCancelled() {
        if (!conversationController.isDisposed()) {
            conversationController.addSystemMessage("[generation cancelled]");
        }
        finishStreaming();
    }

    private void cancelStreaming() {
        streamingCancelled = true;
        if (activeStream != null) {
            activeStream.cancel();
        }
        updateSendButtonState();
    }

    private void finishStreaming() {
        sending = false;
        streamingReplyStarted = false;
        streamingCancelled = false;
        activeStream = null;
        updateSendButtonState();
        if (stopButton != null && !stopButton.isDisposed()) {
            stopButton.setEnabled(false);
        }
    }

    private void installPromptAssist(Text target) {
        if (target == null || target.isDisposed()) {
            return;
        }
        KeyStroke keyStroke = null;
        try {
            keyStroke = KeyStroke.getInstance("CTRL+SPACE");
        } catch (ParseException ex) {
            // fall back to auto activation triggered by '/'
        }
        ContentProposalAdapter adapter = new ContentProposalAdapter(
                target,
                new TextContentAdapter(),
                new CommandProposalProvider(),
                keyStroke,
                COMMAND_AUTO_ACTIVATION);
        adapter.setAutoActivationDelay(120);
        adapter.setProposalAcceptanceStyle(ContentProposalAdapter.PROPOSAL_IGNORE);
        adapter.addContentProposalListener(new IContentProposalListener() {
            @Override
            public void proposalAccepted(IContentProposal proposal) {
                if (proposal instanceof QuickCommandProposal) {
                    applyQuickCommand(((QuickCommandProposal) proposal).command());
                }
            }
        });
    }

    private void openCommandPalette() {
        if (prompt == null || prompt.isDisposed()) {
            return;
        }
        ElementListSelectionDialog dialog = new ElementListSelectionDialog(getSite().getShell(), new LabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof QuickCommand) {
                    QuickCommand command = (QuickCommand) element;
                    return String.format("%s — %s", command.trigger, command.title);
                }
                return super.getText(element);
            }
        });
        dialog.setMultipleSelection(false);
        dialog.setElements(QUICK_COMMANDS.toArray());
        dialog.setTitle("Quick command palette");
        dialog.setMessage("Select a quick command or type to filter:");
        dialog.setEmptyListMessage("No commands match the filter.");
        dialog.setBlockOnOpen(true);
        dialog.setHelpAvailable(false);
        if (dialog.open() == Window.OK) {
            Object[] result = dialog.getResult();
            if (result != null && result.length > 0 && result[0] instanceof QuickCommand) {
                applyQuickCommand((QuickCommand) result[0]);
            }
        }
    }

    private void applyQuickCommand(QuickCommand command) {
        if (command == null || prompt == null || prompt.isDisposed()) {
            return;
        }
        String text = prompt.getText();
        int caret = prompt.getCaretPosition();
        int start = commandStartIndex(text, caret);
        int end = commandEndIndex(text, caret);
        if (start >= 0 && end >= start) {
            prompt.setSelection(start, end);
            prompt.insert(command.prompt);
            prompt.setSelection(start + command.prompt.length());
        } else {
            prompt.setText(command.prompt);
            prompt.setSelection(command.prompt.length());
        }
        prompt.setFocus();
    }

    private static int commandStartIndex(String text, int caret) {
        if (text == null || text.isEmpty()) {
            return -1;
        }
        int idx = Math.max(0, Math.min(caret, text.length()));
        int start = idx - 1;
        while (start >= 0) {
            char ch = text.charAt(start);
            if (Character.isWhitespace(ch)) {
                return -1;
            }
            if (ch == '/') {
                return start;
            }
            start--;
        }
        return -1;
    }

    private static int commandEndIndex(String text, int caret) {
        if (text == null) {
            return -1;
        }
        int len = text.length();
        int idx = Math.max(0, Math.min(caret, len));
        while (idx < len) {
            char ch = text.charAt(idx);
            if (Character.isWhitespace(ch)) {
                break;
            }
            idx++;
        }
        return idx;
    }

    private static String extractCommandToken(String text, int caret) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        int len = text.length();
        int pos = Math.max(0, Math.min(caret, len));
        int start = pos - 1;
        while (start >= 0) {
            char ch = text.charAt(start);
            if (ch == '/') {
                return text.substring(start, pos);
            }
            if (Character.isWhitespace(ch)) {
                return null;
            }
            start--;
        }
        return null;
    }

    private static boolean matchesCommand(QuickCommand command, String query) {
        if (command == null) {
            return false;
        }
        if (query == null || query.isBlank() || "/".equals(query)) {
            return true;
        }
        String triggerLower = command.trigger.toLowerCase(Locale.ROOT);
        String titleLower = command.title.toLowerCase(Locale.ROOT);
        String descriptionLower = command.description.toLowerCase(Locale.ROOT);
        String normalized = query.startsWith("/") ? query.substring(1) : query;
        return triggerLower.contains(query)
                || triggerLower.startsWith(query)
                || titleLower.contains(normalized)
                || descriptionLower.contains(normalized);
    }

    private static final class CommandProposalProvider implements IContentProposalProvider {
        @Override
        public IContentProposal[] getProposals(String contents, int position) {
            String token = extractCommandToken(contents, position);
            if (token == null) {
                return new IContentProposal[0];
            }
            List<QuickCommandProposal> proposals = QUICK_COMMANDS.stream()
                    .filter(command -> matchesCommand(command, token.toLowerCase(Locale.ROOT)))
                    .map(QuickCommandProposal::new)
                    .toList();
            if (proposals.isEmpty()) {
                proposals = QUICK_COMMANDS.stream().map(QuickCommandProposal::new).toList();
            }
            return proposals.toArray(IContentProposal[]::new);
        }
    }

    private static final class QuickCommandProposal implements IContentProposal {
        private final QuickCommand command;

        QuickCommandProposal(QuickCommand command) {
            this.command = command;
        }

        QuickCommand command() {
            return command;
        }

        @Override
        public String getContent() {
            return command.prompt;
        }

        @Override
        public int getCursorPosition() {
            return command.prompt.length();
        }

        @Override
        public String getDescription() {
            return command.description;
        }

        @Override
        public String getLabel() {
            return String.format("%s — %s", command.trigger, command.title);
        }
    }

    private static final class QuickCommand {
        final String trigger;
        final String title;
        final String prompt;
        final String description;

        QuickCommand(String trigger, String title, String prompt, String description) {
            this.trigger = trigger;
            this.title = title;
            this.prompt = prompt;
            this.description = description;
        }
    }

    private void runSynchronousRequest(Session session, String text, DRepresentation diagram, boolean actionsEnabled, List<LLMService.Message> history) {
        sending = true;
        updateSendButtonState();
        Job job = new Job("Capella AI request") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                ToolRouter.Result res = ToolRouter.handleChat(session, text, history);
                Display.getDefault().asyncExec(() -> handleChatResult(session, diagram, actionsEnabled, res));
                return Status.OK_STATUS;
            }
        };
        job.setUser(false);
        job.schedule();
    }


    private boolean offerClarificationLoop(Session session, ToolPlan plan, DryRunResult dryRun) {
        if (dryRun == null || dryRun.issues().isEmpty()) {
            return true;
        }
        List<String> summary = buildClarificationSummary(session, plan, dryRun);
        if (summary.isEmpty()) {
            return true;
        }
        StringBuilder message = new StringBuilder("Dry run flagged issues:\n\n");
        for (String line : summary) {
            message.append("• ").append(line).append(System.lineSeparator());
        }
        message.append(System.lineSeparator()).append("Ask the assistant to resolve these before applying the plan?");

        MessageDialog dialog = new MessageDialog(
                getSite().getShell(),
                "Plan needs clarification",
                null,
                message.toString(),
                MessageDialog.QUESTION,
                new String[] {"Ask Assistant", "Review Plan"},
                0);
        int choice = dialog.open();
        if (choice == 0) {
            sendClarificationRequest(summary);
            return false;
        }
        return true;
    }

    private List<String> buildClarificationSummary(Session session, ToolPlan plan, DryRunResult dryRun) {
        if (plan == null || dryRun == null || !dryRun.isAvailable()) {
            return List.of();
        }
        List<String> summary = new ArrayList<>();
        for (PlanItem item : plan.items()) {
            PlanDryRun.Outcome outcome = dryRun.outcomeFor(item.getIndex());
            if (outcome == null || outcome.isSuccess()) {
                continue;
            }
            StringBuilder line = new StringBuilder();
            line.append(String.format("Step %d (%s): %s",
                    item.getIndex() + 1,
                    item.getTypeLabel(),
                    outcome.getMessage()));
            List<String> hints = suggestAlternatives(session, item, outcome);
            if (!hints.isEmpty()) {
                line.append(" — Did you mean ").append(String.join(", ", hints)).append('?');
            }
            summary.add(line.toString());
        }
        if (summary.isEmpty()) {
            summary.addAll(dryRun.issues());
        }
        return summary;
    }

    private List<String> suggestAlternatives(Session session, PlanItem item, PlanDryRun.Outcome outcome) {
        if (session == null || item == null || outcome == null) {
            return List.of();
        }
        String message = outcome.getMessage();
        String lower = message != null ? message.toLowerCase(Locale.ROOT) : "";
        if (!lower.contains("not found") && !lower.contains("missing")) {
            return List.of();
        }
        Set<String> candidates = gatherCandidateNames(item);
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<String> suggestions = new ArrayList<>();
        for (String candidate : candidates) {
            List<IndexedElement> matches = ModelIndex.search(session, candidate, 5);
            for (IndexedElement match : matches) {
                if (match == null || match.name == null || match.name.isBlank()) {
                    continue;
                }
                if (candidate.equalsIgnoreCase(match.name)) {
                    continue;
                }
                String label = match.name;
                if (match.type != null && !match.type.isBlank()) {
                    label = label + " (" + match.type + ")";
                }
                if (!suggestions.contains(label)) {
                    suggestions.add(label);
                }
                if (suggestions.size() >= 3) {
                    break;
                }
            }
            if (suggestions.size() >= 3) {
                break;
            }
        }
        return suggestions;
    }

    private Set<String> gatherCandidateNames(PlanItem item) {
        Set<String> names = new LinkedHashSet<>();
        addCandidate(names, item.getExistingName());
        addCandidate(names, item.getSourceName());
        addCandidate(names, item.getTargetName());
        addCandidate(names, item.getParentName());
        addCandidate(names, item.getCapabilityName());
        addCandidate(names, item.getLogicalName());
        addCandidate(names, item.getPhysicalName());
        addCandidate(names, item.getChainName());
        addCandidate(names, item.getFunctionName());
        addCandidate(names, item.getExchangeName());
        addCandidate(names, item.getScenarioName());
        if (item.getItemNames() != null) {
            for (String name : item.getItemNames()) {
                addCandidate(names, name);
            }
        }
        if (item.getSecondaryNames() != null) {
            for (String name : item.getSecondaryNames()) {
                addCandidate(names, name);
            }
        }
        return names;
    }

    private void addCandidate(Set<String> names, String raw) {
        if (raw == null) {
            return;
        }
        String value = raw.trim();
        if (!value.isEmpty()) {
            names.add(value);
        }
    }

    private void sendClarificationRequest(List<String> summary) {
        String followUp = composeClarificationPrompt(summary);
        append("Plan validator: requesting clarification from the assistant.");
        Display.getDefault().asyncExec(() -> {
            if (prompt != null && !prompt.isDisposed()) {
                prompt.setText(followUp);
                prompt.setSelection(followUp.length());
                prompt.setFocus();
                onSend();
            }
        });
    }

    private String composeClarificationPrompt(List<String> summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("Dry run validation failed with the following issues:\n");
        for (String line : summary) {
            sb.append(" - ").append(line).append('\n');
        }
        sb.append("Please respond with an updated TOOL_PLAN that resolves each issue, choosing the correct existing elements or creating missing ones as needed.");
        return sb.toString();
    }

    private void registerSelectionListener() {
        if (selectionListener != null) {
            return;
        }
        selectionListener = new ISelectionListener() {
            @Override
            public void selectionChanged(IWorkbenchPart part, org.eclipse.jface.viewers.ISelection selection) {
                updateContextSummary(getSelectedOrActiveSession(), selection);
            }
        };
        getSite().getWorkbenchWindow().getSelectionService().addPostSelectionListener(selectionListener);
    }

    private void updateContextSummary(Session session) {
        if (contextSummary == null || contextSummary.isDisposed() || getSite() == null || getSite().getWorkbenchWindow() == null) {
            return;
        }
        org.eclipse.jface.viewers.ISelection selection = getSite().getWorkbenchWindow().getSelectionService().getSelection();
        updateContextSummary(session, selection);
    }

    private void updateContextSummary(Session session, org.eclipse.jface.viewers.ISelection selection) {
        if (contextSummary == null || contextSummary.isDisposed()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(buildSelectionCard(session, selection)).append(System.lineSeparator()).append(System.lineSeparator());
        sb.append(buildModelCard(session)).append(System.lineSeparator()).append(System.lineSeparator());
        sb.append(buildMethodologyCard(session)).append(System.lineSeparator()).append(System.lineSeparator());
        sb.append(buildPlanCard());
        contextSummary.setText(sb.toString().stripTrailing());
        refreshScrollArea();
    }

    private String buildSelectionCard(Session session, org.eclipse.jface.viewers.ISelection selection) {
        StringBuilder sb = new StringBuilder("Selection:\n");
        if (!(selection instanceof org.eclipse.jface.viewers.IStructuredSelection)) {
            sb.append(" • No semantic element selected.");
            return sb.toString();
        }
        org.eclipse.jface.viewers.IStructuredSelection structured = (org.eclipse.jface.viewers.IStructuredSelection) selection;
        if (structured.isEmpty()) {
            sb.append(" • No semantic element selected.");
            return sb.toString();
        }
        Object first = structured.getFirstElement();
        EObject eo = adaptToEObject(first);
        if (eo != null) {
            ModelIndex.IndexedElement descriptor = ModelIndex.describe(session, eo);
            if (descriptor != null) {
                sb.append(String.format(" • %s (%s)", descriptor.name, descriptor.type));
                if (descriptor.layer != null && !descriptor.layer.isBlank()) {
                    sb.append(String.format(" — Layer %s", descriptor.layer));
                }
                if (descriptor.path != null && !descriptor.path.isBlank()) {
                    sb.append(System.lineSeparator()).append("   Path: ").append(descriptor.path);
                }
            } else {
                sb.append(String.format(" • %s (%s)", safeName(eo), eo.eClass().getName()));
            }
        } else {
            sb.append(String.format(" • %s", first.getClass().getSimpleName()));
        }
        if (structured.size() > 1) {
            sb.append(System.lineSeparator()).append(String.format(" • + %d more item(s)", structured.size() - 1));
        }
        return sb.toString();
    }

    private String buildModelCard(Session session) {
        String summary = ModelSummary.summarize(session);
        StringBuilder sb = new StringBuilder("Model snapshot:\n");
        Arrays.stream(summary.split("\\R"))
                .filter(line -> !line.isBlank())
                .limit(6)
                .forEach(line -> sb.append(" • ").append(line).append(System.lineSeparator()));
        if (sb.charAt(sb.length() - 1) == '\n') {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.toString();
    }

    private String buildMethodologyCard(Session session) {
        StringBuilder sb = new StringBuilder("Methodology coach:\n");
        Guidance guidance = MethodologyCoach.guidance(session);
        if (!guidance.isAvailable()) {
            String reason = guidance.reason() != null ? guidance.reason() : "Guidance unavailable.";
            sb.append(" • ").append(reason);
            return sb.toString();
        }
        List<Step> steps = guidance.steps();
        if (steps.isEmpty()) {
            sb.append(" • No methodology insights available.");
            return sb.toString();
        }
        for (Step step : steps) {
            sb.append(" • ")
                    .append(step.status().symbol())
                    .append(' ')
                    .append(step.title());
            if (step.headline() != null && !step.headline().isBlank()) {
                sb.append(" — ").append(step.headline());
            }
            sb.append(System.lineSeparator());
            for (String detail : step.details()) {
                if (detail == null || detail.isBlank()) {
                    continue;
                }
                sb.append("   · ").append(detail).append(System.lineSeparator());
            }
            if (!step.suggestions().isEmpty()) {
                sb.append("   → ").append(String.join(", ", step.suggestions())).append(System.lineSeparator());
            }
        }
        if (!guidance.alerts().isEmpty()) {
            for (String alert : guidance.alerts()) {
                if (alert == null || alert.isBlank()) {
                    continue;
                }
                sb.append(" ⚠ ").append(alert).append(System.lineSeparator());
            }
        }
        if (sb.charAt(sb.length() - 1) == '\n') {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.toString();
    }

    private String buildPlanCard() {
        StringBuilder sb = new StringBuilder("Last plan:\n");
        if (lastPlanSummary.isEmpty()) {
            sb.append(" • No plan executed yet.");
        } else {
            lastPlanSummary.stream()
                    .limit(6)
                    .forEach(line -> sb.append(" • ").append(line).append(System.lineSeparator()));
            if (sb.charAt(sb.length() - 1) == '\n') {
                sb.deleteCharAt(sb.length() - 1);
            }
        }
        return sb.toString();
    }

    private void refreshScrollArea() {
        if (scroller != null && !scroller.isDisposed() && rootComposite != null && !rootComposite.isDisposed()) {
            scroller.setMinSize(rootComposite.computeSize(SWT.DEFAULT, SWT.DEFAULT));
        }
    }

    private EObject adaptToEObject(Object candidate) {
        if (candidate instanceof EObject) {
            return (EObject) candidate;
        }
        if (candidate instanceof DSemanticDecorator) {
            return ((DSemanticDecorator) candidate).getTarget();
        }
        if (candidate instanceof IAdaptable) {
            Object adapted = ((IAdaptable) candidate).getAdapter(EObject.class);
            if (adapted instanceof EObject) {
                return (EObject) adapted;
            }
        }
        Object adapted = Platform.getAdapterManager().getAdapter(candidate, EObject.class);
        if (adapted instanceof EObject) {
            return (EObject) adapted;
        }
        return null;
    }

    private String safeName(EObject eo) {
        org.eclipse.emf.ecore.EStructuralFeature feature = eo.eClass().getEStructuralFeature("name");
        if (feature != null) {
            Object value = eo.eGet(feature);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return eo.eClass().getName();
    }

    @Override
    public void dispose() {
        if (selectionListener != null) {
            getSite().getWorkbenchWindow().getSelectionService().removePostSelectionListener(selectionListener);
            selectionListener = null;
        }
        try {
            var page = getSite() != null && getSite().getWorkbenchWindow() != null ? getSite().getWorkbenchWindow().getActivePage() : null;
            if (page != null && partListener != null) {
                page.removePartListener(partListener);
            }
        } catch (Exception ignore) {}
        super.dispose();
    }

    private void onExplainModel() {
        try {
            Session session = SessionBridge.getActiveSession();
            append(ModelSummary.summarize(session));
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
            if (!conversationController.isDisposed()) {
                conversationController.addSystemMessage(text);
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

    private void onCreateMission() {
        InputDialog nameDialog = new InputDialog(
            getSite().getShell(),
            "Create mission",
            "Enter mission name:",
            "",
            value -> (value == null || value.trim().isEmpty()) ? "Name must not be empty" : null
        );
        if (nameDialog.open() != Window.OK) {
            return;
        }
        String name = nameDialog.getValue().trim();

        InputDialog descDialog = new InputDialog(
            getSite().getShell(),
            "Mission description",
            "Optional description:",
            "",
            null
        );
        String description = "";
        if (descDialog.open() == Window.OK && descDialog.getValue() != null) {
            description = descDialog.getValue().trim();
        }

        Session session = SessionBridge.getActiveSession();
        if (session == null) {
            append("No active session.");
            return;
        }
        boolean ok = OperationalActions.createMission(session, "OA", name, description);
        append(ok ? String.format("Created mission '%s'.", name) : "Mission creation failed.");
    }

    private void onCreateOperationalActivity() {
        InputDialog nameDialog = new InputDialog(
            getSite().getShell(),
            "Create operational activity",
            "Enter activity name:",
            "",
            value -> (value == null || value.trim().isEmpty()) ? "Name must not be empty" : null
        );
        if (nameDialog.open() != Window.OK) {
            return;
        }
        String name = nameDialog.getValue().trim();

        InputDialog parentDialog = new InputDialog(
            getSite().getShell(),
            "Parent activity",
            "Optional parent activity name:",
            "",
            null
        );
        String parent = "";
        if (parentDialog.open() == Window.OK && parentDialog.getValue() != null) {
            parent = parentDialog.getValue().trim();
        }

        Session session = SessionBridge.getActiveSession();
        if (session == null) {
            append("No active session.");
            return;
        }
        boolean ok = OperationalActions.createOperationalActivity(session, "OA", name, parent.isBlank() ? null : parent);
        append(ok ? String.format("Created operational activity '%s'%s.", name, parent.isBlank() ? "" : " under " + parent)
                : "Operational activity creation failed.");
    }

    private void onCreateOperationalExchange() {
        InputDialog sourceDialog = new InputDialog(
            getSite().getShell(),
            "Operational exchange",
            "Source operational actor:",
            "",
            value -> (value == null || value.trim().isEmpty()) ? "Source actor required" : null
        );
        if (sourceDialog.open() != Window.OK) {
            return;
        }
        String source = sourceDialog.getValue().trim();

        InputDialog targetDialog = new InputDialog(
            getSite().getShell(),
            "Operational exchange",
            "Target operational actor:",
            "",
            value -> (value == null || value.trim().isEmpty()) ? "Target actor required" : null
        );
        if (targetDialog.open() != Window.OK) {
            return;
        }
        String target = targetDialog.getValue().trim();

        InputDialog nameDialog = new InputDialog(
            getSite().getShell(),
            "Operational exchange",
            "Optional exchange name:",
            "",
            null
        );
        String exchangeName = "";
        if (nameDialog.open() == Window.OK && nameDialog.getValue() != null) {
            exchangeName = nameDialog.getValue().trim();
        }

        Session session = SessionBridge.getActiveSession();
        if (session == null) {
            append("No active session.");
            return;
        }
        boolean ok = OperationalActions.createOperationalExchange(session, "OA", exchangeName, source, target);
        append(ok ? String.format("Created operational exchange %s (%s → %s).",
                exchangeName.isBlank() ? "(auto-named)" : "'" + exchangeName + "'", source, target)
                : "Operational exchange creation failed.");
    }

    private void onLinkOperationalToCapability() {
        InputDialog activityDialog = new InputDialog(
            getSite().getShell(),
            "Link activity to capability",
            "Operational activity name:",
            "",
            value -> (value == null || value.trim().isEmpty()) ? "Activity required" : null
        );
        if (activityDialog.open() != Window.OK) {
            return;
        }
        String activity = activityDialog.getValue().trim();

        InputDialog capabilityDialog = new InputDialog(
            getSite().getShell(),
            "Link activity to capability",
            "Capability name:",
            "",
            value -> (value == null || value.trim().isEmpty()) ? "Capability required" : null
        );
        if (capabilityDialog.open() != Window.OK) {
            return;
        }
        String capability = capabilityDialog.getValue().trim();

        Session session = SessionBridge.getActiveSession();
        if (session == null) {
            append("No active session.");
            return;
        }
        boolean ok = OperationalActions.linkActivityToCapability(session, activity, capability);
        append(ok ? String.format("Linked activity '%s' to capability '%s'.", activity, capability)
                : "Failed to link operational activity to capability.");
    }

    private void onCreateSystemComponent() {
        InputDialog nameDialog = new InputDialog(
            getSite().getShell(),
            "Create system component",
            "Component name:",
            "",
            value -> (value == null || value.trim().isEmpty()) ? "Name required" : null
        );
        if (nameDialog.open() != Window.OK) {
            return;
        }
        String name = nameDialog.getValue().trim();

        InputDialog parentDialog = new InputDialog(
            getSite().getShell(),
            "Parent component",
            "Optional parent component name:",
            "",
            null
        );
        String parent = "";
        if (parentDialog.open() == Window.OK && parentDialog.getValue() != null) {
            parent = parentDialog.getValue().trim();
        }

        Session session = SessionBridge.getActiveSession();
        if (session == null) {
            append("No active session.");
            return;
        }
        boolean ok = SystemActions.createComponent(session, "SA", name, parent.isBlank() ? null : parent);
        append(ok ? String.format("Created system component '%s'%s.", name, parent.isBlank() ? "" : " under " + parent)
                : "System component creation failed.");
    }

    private void onCreateSystemFunction() {
        InputDialog nameDialog = new InputDialog(
            getSite().getShell(),
            "Create system function",
            "Function name:",
            "",
            value -> (value == null || value.trim().isEmpty()) ? "Name required" : null
        );
        if (nameDialog.open() != Window.OK) {
            return;
        }
        String name = nameDialog.getValue().trim();

        InputDialog parentDialog = new InputDialog(
            getSite().getShell(),
            "Parent function",
            "Optional parent function name:",
            "",
            null
        );
        String parent = "";
        if (parentDialog.open() == Window.OK && parentDialog.getValue() != null) {
            parent = parentDialog.getValue().trim();
        }

        Session session = SessionBridge.getActiveSession();
        if (session == null) {
            append("No active session.");
            return;
        }
        boolean ok = SystemActions.createFunction(session, "SA", name, parent.isBlank() ? null : parent);
        append(ok ? String.format("Created system function '%s'%s.", name, parent.isBlank() ? "" : " under " + parent)
                : "System function creation failed.");
    }

    private void onCreateSystemInterface() {
        InputDialog sourceDialog = new InputDialog(
            getSite().getShell(),
            "System interface",
            "Source system component:",
            "",
            value -> (value == null || value.trim().isEmpty()) ? "Source component required" : null
        );
        if (sourceDialog.open() != Window.OK) {
            return;
        }
        String source = sourceDialog.getValue().trim();

        InputDialog targetDialog = new InputDialog(
            getSite().getShell(),
            "System interface",
            "Target system component:",
            "",
            value -> (value == null || value.trim().isEmpty()) ? "Target component required" : null
        );
        if (targetDialog.open() != Window.OK) {
            return;
        }
        String target = targetDialog.getValue().trim();

        InputDialog nameDialog = new InputDialog(
            getSite().getShell(),
            "System interface",
            "Optional interface name:",
            "",
            null
        );
        String name = "";
        if (nameDialog.open() == Window.OK && nameDialog.getValue() != null) {
            name = nameDialog.getValue().trim();
        }

        Session session = SessionBridge.getActiveSession();
        if (session == null) {
            append("No active session.");
            return;
        }
        boolean ok = SystemActions.createInterface(session, "SA", name, source, target);
        append(ok ? String.format("Created system interface %s (%s → %s).",
                name.isBlank() ? "(auto-named)" : "'" + name + "'", source, target)
                : "System interface creation failed.");
    }

    private void onAllocateCapability() {
        InputDialog componentDialog = new InputDialog(
            getSite().getShell(),
            "Allocate capability",
            "System component name:",
            "",
            value -> (value == null || value.trim().isEmpty()) ? "Component required" : null
        );
        if (componentDialog.open() != Window.OK) {
            return;
        }
        String component = componentDialog.getValue().trim();

        InputDialog capabilityDialog = new InputDialog(
            getSite().getShell(),
            "Allocate capability",
            "Capability name:",
            "",
            value -> (value == null || value.trim().isEmpty()) ? "Capability required" : null
        );
        if (capabilityDialog.open() != Window.OK) {
            return;
        }
        String capability = capabilityDialog.getValue().trim();

        Session session = SessionBridge.getActiveSession();
        if (session == null) {
            append("No active session.");
            return;
        }
        boolean ok = SystemActions.allocateCapability(session, component, capability);
        append(ok ? String.format("Allocated capability '%s' to system component '%s'.", capability, component)
                : "Capability allocation failed.");
    }

    private void onCreateFunctionalChain() {
        InputDialog nameDialog = new InputDialog(
            getSite().getShell(),
            "Create functional chain",
            "Chain name:",
            "",
            value -> (value == null || value.trim().isEmpty()) ? "Name required" : null
        );
        if (nameDialog.open() != Window.OK) {
            return;
        }
        String name = nameDialog.getValue().trim();

        InputDialog descriptionDialog = new InputDialog(
            getSite().getShell(),
            "Chain description",
            "Optional description:",
            "",
            null
        );
        String description = "";
        if (descriptionDialog.open() == Window.OK && descriptionDialog.getValue() != null) {
            description = descriptionDialog.getValue().trim();
        }

        Session session = SessionBridge.getActiveSession();
        if (session == null) {
            append("No active session.");
            return;
        }
        boolean ok = FunctionalChainActions.createChain(session, "LA", name, description);
        append(ok ? String.format("Created functional chain '%s'.", name) : "Functional chain creation failed.");
    }

    private void onExtendFunctionalChain() {
        InputDialog chainDialog = new InputDialog(
            getSite().getShell(),
            "Extend functional chain",
            "Chain name:",
            "",
            value -> (value == null || value.trim().isEmpty()) ? "Chain name required" : null
        );
        if (chainDialog.open() != Window.OK) {
            return;
        }
        String chain = chainDialog.getValue().trim();

        InputDialog functionDialog = new InputDialog(
            getSite().getShell(),
            "Extend functional chain",
            "Function name:",
            "",
            value -> (value == null || value.trim().isEmpty()) ? "Function name required" : null
        );
        if (functionDialog.open() != Window.OK) {
            return;
        }
        String function = functionDialog.getValue().trim();

        InputDialog classDialog = new InputDialog(
            getSite().getShell(),
            "Function class",
            "Optional function class (LogicalFunction/SystemFunction/PhysicalFunction):",
            "",
            null
        );
        String functionClass = "";
        if (classDialog.open() == Window.OK && classDialog.getValue() != null) {
            functionClass = classDialog.getValue().trim();
        }

        Session session = SessionBridge.getActiveSession();
        if (session == null) {
            append("No active session.");
            return;
        }
        boolean ok = FunctionalChainActions.addFunction(session, chain, function, functionClass.isBlank() ? null : functionClass);
        append(ok ? String.format("Extended chain '%s' with function '%s'.", chain, function) : "Failed to extend functional chain.");
    }

    private void onSequenceFunctionalExchange() {
        InputDialog chainDialog = new InputDialog(
            getSite().getShell(),
            "Add exchange to chain",
            "Chain name:",
            "",
            value -> (value == null || value.trim().isEmpty()) ? "Chain name required" : null
        );
        if (chainDialog.open() != Window.OK) {
            return;
        }
        String chain = chainDialog.getValue().trim();

        InputDialog exchangeDialog = new InputDialog(
            getSite().getShell(),
            "Add exchange to chain",
            "Functional exchange name:",
            "",
            value -> (value == null || value.trim().isEmpty()) ? "Exchange name required" : null
        );
        if (exchangeDialog.open() != Window.OK) {
            return;
        }
        String exchange = exchangeDialog.getValue().trim();

        Session session = SessionBridge.getActiveSession();
        if (session == null) {
            append("No active session.");
            return;
        }
        boolean ok = FunctionalChainActions.addExchange(session, chain, exchange);
        append(ok ? String.format("Added exchange '%s' to chain '%s'.", exchange, chain) : "Failed to add exchange to chain.");
    }

    private void onAttachScenario() {
        InputDialog chainDialog = new InputDialog(
            getSite().getShell(),
            "Attach scenario",
            "Chain name:",
            "",
            value -> (value == null || value.trim().isEmpty()) ? "Chain name required" : null
        );
        if (chainDialog.open() != Window.OK) {
            return;
        }
        String chain = chainDialog.getValue().trim();

        InputDialog scenarioDialog = new InputDialog(
            getSite().getShell(),
            "Attach scenario",
            "Scenario name:",
            "",
            value -> (value == null || value.trim().isEmpty()) ? "Scenario name required" : null
        );
        if (scenarioDialog.open() != Window.OK) {
            return;
        }
        String scenario = scenarioDialog.getValue().trim();

        Session session = SessionBridge.getActiveSession();
        if (session == null) {
            append("No active session.");
            return;
        }
        boolean ok = FunctionalChainActions.attachScenario(session, chain, scenario);
        append(ok ? String.format("Attached scenario '%s' to chain '%s'.", scenario, chain) : "Failed to attach scenario to chain.");
    }

    private void onCreateRequirement() {
        InputDialog nameDialog = new InputDialog(
            getSite().getShell(),
            "Create requirement",
            "Requirement name:",
            "",
            value -> (value == null || value.trim().isEmpty()) ? "Name required" : null
        );
        if (nameDialog.open() != Window.OK) {
            return;
        }
        String name = nameDialog.getValue().trim();

        InputDialog containerClassDialog = new InputDialog(
            getSite().getShell(),
            "Container class",
            "Optional container class (e.g., RequirementsPkg):",
            "",
            null
        );
        String containerClass = null;
        if (containerClassDialog.open() == Window.OK && containerClassDialog.getValue() != null) {
            containerClass = blankToNull(containerClassDialog.getValue());
        }

        InputDialog containerNameDialog = new InputDialog(
            getSite().getShell(),
            "Container name",
            "Optional container element name:",
            "",
            null
        );
        String containerName = null;
        if (containerNameDialog.open() == Window.OK && containerNameDialog.getValue() != null) {
            containerName = blankToNull(containerNameDialog.getValue());
        }

        InputDialog typeDialog = new InputDialog(
            getSite().getShell(),
            "Requirement type",
            "Optional requirement type (Requirement/SystemRequirement/...):",
            "",
            null
        );
        String reqType = null;
        if (typeDialog.open() == Window.OK && typeDialog.getValue() != null) {
            reqType = blankToNull(typeDialog.getValue());
        }

        InputDialog descriptionDialog = new InputDialog(
            getSite().getShell(),
            "Requirement description",
            "Optional description:",
            "",
            null
        );
        String description = "";
        if (descriptionDialog.open() == Window.OK && descriptionDialog.getValue() != null) {
            description = descriptionDialog.getValue().trim();
        }

        Session session = SessionBridge.getActiveSession();
        if (session == null) {
            append("No active session.");
            return;
        }
        boolean ok = RequirementActions.createRequirement(session, containerClass, containerName, reqType, name, description);
        append(ok ? String.format("Created requirement '%s'.", name) : "Requirement creation failed.");
    }

    private void onLinkRequirement() {
        InputDialog requirementDialog = new InputDialog(
            getSite().getShell(),
            "Link requirement",
            "Requirement name:",
            "",
            value -> (value == null || value.trim().isEmpty()) ? "Requirement required" : null
        );
        if (requirementDialog.open() != Window.OK) {
            return;
        }
        String requirement = requirementDialog.getValue().trim();

        InputDialog targetClassDialog = new InputDialog(
            getSite().getShell(),
            "Link requirement",
            "Target element class (e.g., SystemComponent):",
            "",
            value -> (value == null || value.trim().isEmpty()) ? "Target class required" : null
        );
        if (targetClassDialog.open() != Window.OK) {
            return;
        }
        String targetClass = targetClassDialog.getValue().trim();

        InputDialog targetNameDialog = new InputDialog(
            getSite().getShell(),
            "Link requirement",
            "Target element name:",
            "",
            value -> (value == null || value.trim().isEmpty()) ? "Target name required" : null
        );
        if (targetNameDialog.open() != Window.OK) {
            return;
        }
        String targetName = targetNameDialog.getValue().trim();

        Session session = SessionBridge.getActiveSession();
        if (session == null) {
            append("No active session.");
            return;
        }
        boolean ok = RequirementActions.linkRequirement(session, requirement, targetClass, targetName);
        append(ok ? String.format("Linked requirement '%s' to %s '%s'.", requirement, targetClass, targetName)
                : "Failed to link requirement.");
    }

    private void onRecordRequirementCoverage() {
        InputDialog targetClassDialog = new InputDialog(
            getSite().getShell(),
            "Record coverage",
            "Target element class:",
            "",
            value -> (value == null || value.trim().isEmpty()) ? "Target class required" : null
        );
        if (targetClassDialog.open() != Window.OK) {
            return;
        }
        String targetClass = targetClassDialog.getValue().trim();

        InputDialog targetNameDialog = new InputDialog(
            getSite().getShell(),
            "Record coverage",
            "Target element name:",
            "",
            value -> (value == null || value.trim().isEmpty()) ? "Target name required" : null
        );
        if (targetNameDialog.open() != Window.OK) {
            return;
        }
        String targetName = targetNameDialog.getValue().trim();

        InputDialog statusDialog = new InputDialog(
            getSite().getShell(),
            "Record coverage",
            "Optional status (e.g., Satisfied, Verified):",
            "",
            null
        );
        String status = null;
        if (statusDialog.open() == Window.OK && statusDialog.getValue() != null) {
            status = blankToNull(statusDialog.getValue());
        }

        InputDialog notesDialog = new InputDialog(
            getSite().getShell(),
            "Record coverage",
            "Optional notes:",
            "",
            null
        );
        String notes = null;
        if (notesDialog.open() == Window.OK && notesDialog.getValue() != null) {
            notes = blankToNull(notesDialog.getValue());
        }

        Session session = SessionBridge.getActiveSession();
        if (session == null) {
            append("No active session.");
            return;
        }
        boolean ok = RequirementActions.recordCoverage(session, targetClass, targetName, status, notes);
        append(ok ? String.format("Recorded coverage for %s '%s'.", targetClass, targetName)
                : "Failed to record requirement coverage.");
    }

    private void onSearchModel() {
        InputDialog dialog = new InputDialog(
            getSite().getShell(),
            "Search model",
            "Enter element name, type, or keyword:",
            "",
            value -> (value == null || value.trim().isEmpty()) ? "Query must not be empty" : null
        );
        if (dialog.open() != Window.OK) {
            return;
        }
        final String query = dialog.getValue().trim();
        append("Searching model: " + query);
        Job job = new Job("Capella model search") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                Session session = SessionBridge.getActiveSession();
                List<IndexedElement> results = ModelIndex.search(session, query, 20);
                Display.getDefault().asyncExec(() -> appendSearchResults(query, results));
                return Status.OK_STATUS;
            }
        };
        job.setUser(false);
        job.schedule();
    }

    private void appendSearchResults(String query, List<IndexedElement> results) {
        if (results == null || results.isEmpty()) {
            append("No matches for '" + query + "'.");
            return;
        }
        append(String.format("Top %d match(es):", results.size()));
        results.forEach(element -> append(formatIndexedElement(element)));
    }

    private String formatIndexedElement(IndexedElement element) {
        StringBuilder sb = new StringBuilder();
        sb.append(" - ").append(element.name);
        if (element.layer != null && !element.layer.isBlank()) {
            sb.append(" [").append(element.layer).append("]");
        }
        sb.append(" · ").append(element.type);
        sb.append(" · ").append(element.path);
        sb.append(" · uuid=").append(element.uuid);
        return sb.toString();
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

        if (telemetryToggle != null && !telemetryToggle.isDisposed()) {
            telemetryToggle.setSelection(LLMPreferences.isTelemetryEnabled());
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
            "Provider: %s • Model: %s • Tool actions: %s • LLM mapping: %s • Telemetry: %s",
            providerCombo.getText(),
            modelCombo != null ? modelCombo.getText() : LLMPreferences.getModel(),
            enableActions != null && enableActions.getSelection() ? "ON" : "OFF",
            useLlmMapping != null && useLlmMapping.getSelection() ? "ON" : "OFF",
            telemetryToggle != null && telemetryToggle.getSelection() ? "ON" : "OFF"
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
        if (stopButton != null && !stopButton.isDisposed()) {
            stopButton.setEnabled(sending && !streamingCancelled);
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

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isActionsEnabled() {
        if (enableActions != null && !enableActions.isDisposed()) {
            return enableActions.getSelection();
        }
        try { return LLMPreferences.isToolActionsEnabled(); } catch (Exception ex) { return false; }
    }

    private void openQuickSettingsDialog() {
        Shell parent = getSite().getShell();
        Shell dialog = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        dialog.setText("Quick Settings");
        dialog.setLayout(new GridLayout(1, false));
        org.polarsys.capella.ai.ui.QuickSetupPanel quick = new org.polarsys.capella.ai.ui.QuickSetupPanel(dialog);
        quick.setOnTest(this::testConnection);
        quick.setOnSave(() -> {
            updateConnectionSummary();
            dialog.close();
        });
        dialog.pack();
        dialog.setSize(420, 260);
        dialog.open();
    }

    private void refreshSessionChoices() {
        try {
            java.util.List<Session> open = new ArrayList<>();
            for (Session s : SessionManager.INSTANCE.getSessions()) {
                if (s != null && s.isOpen()) open.add(s);
            }
            this.sessionChoices = open;
            java.util.List<String> labels = new ArrayList<>();
            labels.add("Auto (Active)");
            for (Session s : open) labels.add(describeSession(s));
            if (sessionCombo != null && !sessionCombo.isDisposed()) {
                sessionCombo.setItems(labels.toArray(new String[0]));
                sessionCombo.select(0);
            }
        } catch (Exception ignore) {
        }
    }

    private String describeSession(Session s) {
        try {
            var res = s.getSessionResource();
            if (res != null && res.getURI() != null) {
                String uri = res.getURI().toString();
                int idx = uri.lastIndexOf('/') + 1;
                return idx > 0 && idx < uri.length() ? uri.substring(idx) : uri;
            }
        } catch (Exception ignore) {}
        return "Session";
    }

    private Session getSelectedOrActiveSession() {
        if (selectedSession != null && selectedSession.isOpen()) return selectedSession;
        return SessionBridge.getActiveSession();
    }

    @Override
    public void setFocus() {
        if (prompt != null && !prompt.isDisposed()) {
            prompt.setFocus();
        }
    }

}
