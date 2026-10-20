package org.polarsys.capella.ai;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IPartListener;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.part.ViewPart;
import org.polarsys.capella.ai.llm.LLMService;
import org.polarsys.capella.ai.agent.ArcadiaAgent;
import org.polarsys.capella.ai.agent.AgentRuntime;
import org.polarsys.capella.ai.agent.AgentJson;
import org.polarsys.capella.ai.agent.ReviewedEdit;
import org.polarsys.capella.ai.agent.TaskContinuation;
import org.polarsys.capella.ai.agent.TaskTermination;
import org.polarsys.capella.ai.codex.CodexPreferences;
import org.polarsys.capella.ai.codex.BundledCodexRuntime;
import org.polarsys.capella.ai.codex.CodexAccountService;
import org.polarsys.capella.ai.agent.ModelChangeSummary;
import org.polarsys.capella.ai.agent.ModelVerification;
import org.polarsys.capella.ai.agent.ModelPostconditions;
import org.polarsys.capella.ai.preferences.AdvancedPreferences;
import org.polarsys.capella.ai.preferences.LLMPreferences;
import org.polarsys.capella.ai.presentation.ConversationState;
import org.polarsys.capella.ai.presentation.ConversationStore;
import org.polarsys.capella.ai.scripting.ExecutionResult;
import org.polarsys.capella.ai.scripting.GroovyExecutor;
import org.polarsys.capella.ai.scripting.GroovyCompilationValidator;
import org.polarsys.capella.ai.scripting.ScriptRouter;
import org.polarsys.capella.ai.scripting.RequirementsAPI;
import org.polarsys.capella.ai.session.SelectionContext;
import org.polarsys.capella.ai.session.SessionBridge;

/** Docked assistant grounded in the current model, with review before execution. */
public class ChatView extends ViewPart {
    public static final String ID = "org.polarsys.capella.ai.chatview";
    private final ExecutorService requests = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Capella AI chat");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, ConversationState> conversations = new HashMap<>();
    private ConversationState conversation = new ConversationState();
    private ConversationStore conversationStore;
    private String conversationKey;
    private final String conversationId = UUID.randomUUID().toString();
    private Session conversationSession, activeSession, reviewSession, requestSession;
    private SelectionContext selectionContext = SelectionContext.empty();
    private ChatTranscript transcript;
    private Composite conversationArea, welcomeArea, reviewArea, taskPlanArea;
    private ScrolledComposite welcomeScroll;
    private StackLayout conversationLayout;
    private Label modelLabel, contextLabel, providerLabel, welcomeTitle, welcomeDescription, activityLabel, reviewLabel;
    private Text inputField;
    private Text taskPlanText;
    private Button taskPlanToggle;
    private Button sendButton, stopButton, reviewButton, dismissButton, retryButton, latestButton,
        contextButton, preferencesButton, signInButton, explainButton, newModelButton;
    private ProgressBar progress;
    private Display display;
    private Action newConversationAction, preferencesAction, requirementsAction;
    private ISelectionListener selectionListener;
    private IPartListener partListener;
    private volatile boolean stopRequested, disposed;
    private volatile Thread requestThread;
    private volatile CompletableFuture<Boolean> pendingReview;
    private String reviewCode;
    private String reviewModelName, reviewContextSummary, reviewProposalSummary, reviewExpectedChecks, retryInput, stopReason;
    private String connectionStatus;
    private SelectionContext retryContext;
    private SelectionContext requestContext;
    private Session retrySession;
    private boolean busy;
    private boolean signingIn;
    private boolean requestCancellable = true;
    private boolean providerConfigured, restoringConversation, historySaveFailed;
    private long requestStarted;
    private String activityText = "";
    private TaskTermination taskTermination;
    /** Fresh requests started for one user message when a task runs out of budget with edits pending. */
    static final int MAX_AUTOMATIC_CONTINUATIONS = 4;
    private int automaticContinuations;
    private Session terminationSession;
    private ConversationState terminationConversation;
    private Runnable activityTimer;
    private Runnable draftSaver;

    @Override
    public void createPartControl(Composite parent) {
        display = parent.getDisplay();
        if (Activator.getDefault() != null) {
            conversationStore = new ConversationStore(Activator.getDefault().getStateLocation().toFile().toPath().resolve("conversations"));
        }
        refreshProvider();
        Composite container = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 10;
        layout.marginHeight = 10;
        layout.verticalSpacing = 10;
        container.setLayout(layout);
        createContextHeader(container);
        createConversation(container);
        createTaskPlan(container);
        // The composer owns the bottom rows and never yields height; only the conversation shrinks.
        Composite composer = new Composite(container, SWT.NONE);
        composer.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
        GridLayout composerLayout = new GridLayout(1, false);
        composerLayout.marginWidth = 0;
        composerLayout.marginHeight = 0;
        composerLayout.verticalSpacing = 8;
        composer.setLayout(composerLayout);
        // A resize re-lays the composer from cached child sizes; refresh them first (runs before that layout).
        composer.addListener(SWT.Resize, event -> composer.layout(true, true));
        createComposer(composer);
        createToolbar();
        installContextListeners();
        refreshContext();
        setBusy(false);
        // The workbench theme restyles widgets after creation and resize layouts reuse cached sizes,
        // which left the Send row clipped in a themed workbench. Flush on every resize and once styled.
        container.addListener(SWT.Resize, event -> container.layout(true, true));
        display.timerExec(500, () -> { if (!container.isDisposed()) container.layout(true, true); });
    }

    private void createContextHeader(Composite parent) {
        Composite header = new Composite(parent, SWT.NONE);
        header.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.verticalSpacing = 4;
        header.setLayout(layout);
        modelLabel = new Label(header, SWT.WRAP);
        modelLabel.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT));
        GridData modelData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        modelData.widthHint = 1;
        modelLabel.setLayoutData(modelData);
        preferencesButton = new Button(header, SWT.PUSH);
        preferencesButton.setText("Settings");
        preferencesButton.setToolTipText("Configure the AI provider, model and execution settings");
        preferencesButton.addListener(SWT.Selection, event -> openPreferences());
        contextLabel = new Label(header, SWT.WRAP);
        GridData contextData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        contextData.widthHint = 1;
        contextLabel.setLayoutData(contextData);
        contextButton = new Button(header, SWT.PUSH);
        contextButton.setText("Context");
        contextButton.setToolTipText("Inspect the selected model elements locally");
        contextButton.addListener(SWT.Selection, event -> inspectContext());
        providerLabel = new Label(header, SWT.WRAP);
        GridData providerData = new GridData(SWT.FILL, SWT.TOP, true, false);
        providerData.widthHint = 1;
        providerLabel.setLayoutData(providerData);
        signInButton = new Button(header, SWT.PUSH);
        signInButton.setText("Sign in with ChatGPT");
        signInButton.setToolTipText("Use your existing OpenAI connection or sign in through your browser");
        signInButton.addListener(SWT.Selection, event -> signInWithChatGPT());
        modelLabel.setToolTipText("Conversation and draft are saved locally for this model");
        Label separator = new Label(parent, SWT.SEPARATOR | SWT.HORIZONTAL);
        separator.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    private void createConversation(Composite parent) {
        conversationArea = new Composite(parent, SWT.NONE);
        GridData conversationData = new GridData(SWT.FILL, SWT.FILL, true, true);
        conversationData.heightHint = 180;
        conversationData.minimumHeight = 0;
        conversationData.widthHint = 1;
        conversationArea.setLayoutData(conversationData);
        conversationLayout = new StackLayout();
        conversationArea.setLayout(conversationLayout);
        transcript = new ChatTranscript(conversationArea);
        transcript.setUnreadListener(unread -> {
            if (latestButton != null && !latestButton.isDisposed()) {
                showControl(latestButton, unread);
            }
        });
        welcomeScroll = new ScrolledComposite(conversationArea, SWT.V_SCROLL);
        welcomeScroll.setExpandHorizontal(true);
        welcomeScroll.setExpandVertical(true);
        welcomeArea = new Composite(welcomeScroll, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 14;
        layout.marginHeight = 20;
        layout.verticalSpacing = 12;
        welcomeArea.setLayout(layout);
        welcomeTitle = new Label(welcomeArea, SWT.WRAP);
        welcomeTitle.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT));
        welcomeTitle.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        welcomeDescription = new Label(welcomeArea, SWT.WRAP);
        welcomeDescription.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        newModelButton = new Button(welcomeArea, SWT.PUSH);
        newModelButton.setText("New Capella model…");
        newModelButton.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        newModelButton.addListener(SWT.Selection, event -> openNewModelWizard());
        explainButton = starter(welcomeArea, "Explain selection",
            "Explain the selected elements, their responsibilities and their relationships in this model.");
        starter(welcomeArea, "Summarize model",
            "Summarize this model across OA, SA, LA, PA and EPBS. Identify the main architecture and any gaps.");
        starter(welcomeArea, "Plan a change",
            "Help me plan the next change in this architecture. Ask for missing details before proposing model changes.");
        welcomeScroll.setContent(welcomeArea);
        welcomeScroll.addListener(SWT.Resize, event -> layoutWelcome());
        conversationLayout.topControl = welcomeScroll;
    }

    private Button starter(Composite parent, String label, String prompt) {
        Button button = new Button(parent, SWT.PUSH);
        button.setText(label);
        button.setToolTipText(prompt);
        button.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        button.addListener(SWT.Selection, event -> {
            inputField.setText(prompt);
            inputField.setSelection(inputField.getCharCount());
            inputField.setFocus();
        });
        return button;
    }

    private void createTaskPlan(Composite parent) {
        taskPlanArea = new Composite(parent, SWT.NONE);
        taskPlanArea.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        taskPlanArea.setLayout(layout);
        Label title = new Label(taskPlanArea, SWT.NONE);
        title.setText("Task plan");
        title.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT));
        title.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        taskPlanToggle = new Button(taskPlanArea, SWT.PUSH);
        taskPlanToggle.setText("Hide");
        taskPlanToggle.setToolTipText("Show or hide the current task plan");
        taskPlanToggle.addListener(SWT.Selection, event -> {
            boolean visible = !taskPlanText.getVisible();
            showControl(taskPlanText, visible);
            taskPlanToggle.setText(visible ? "Hide" : "Show");
        });
        taskPlanText = new Text(taskPlanArea, SWT.READ_ONLY | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        taskPlanText.setFont(JFaceResources.getDefaultFont());
        GridData data = new GridData(SWT.FILL, SWT.TOP, true, false);
        data.horizontalSpan = 2;
        data.widthHint = 1;
        data.heightHint = taskPlanText.getLineHeight() * 4;
        taskPlanText.setLayoutData(data);
        taskPlanText.getAccessible().addAccessibleListener(new org.eclipse.swt.accessibility.AccessibleAdapter() {
            @Override public void getName(org.eclipse.swt.accessibility.AccessibleEvent event) {
                event.result = "Arcadia task plan and step status";
            }
        });
        showControl(taskPlanArea, false);
    }

    private void renderTaskPlan(String text) {
        if (taskPlanText == null || taskPlanText.isDisposed()) return;
        taskPlanText.setText(text == null ? "" : text);
        showControl(taskPlanArea, !taskPlanText.getText().isBlank());
    }

    private void createComposer(Composite parent) {
        Composite activity = new Composite(parent, SWT.NONE);
        activity.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout activityLayout = new GridLayout(3, false);
        activityLayout.marginWidth = 0;
        activityLayout.marginHeight = 0;
        activity.setLayout(activityLayout);
        activityLabel = new Label(activity, SWT.WRAP);
        GridData activityData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        activityData.widthHint = 1;
        activityLabel.setLayoutData(activityData);
        retryButton = new Button(activity, SWT.PUSH);
        retryButton.setText("Retry reply");
        retryButton.setToolTipText("Generate the failed reply again using its original selection. Existing changes are not applied again.");
        retryButton.addListener(SWT.Selection, event -> retryRequest());
        showControl(retryButton, false);
        latestButton = new Button(activity, SWT.PUSH);
        latestButton.setText("Latest response");
        latestButton.addListener(SWT.Selection, event -> transcript.jumpToLatest());
        showControl(latestButton, false);
        progress = new ProgressBar(parent, SWT.INDETERMINATE);
        GridData progressData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        progressData.heightHint = 3;
        progress.setLayoutData(progressData);
        showControl(progress, false);
        reviewArea = new Composite(parent, SWT.BORDER);
        reviewArea.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout reviewLayout = new GridLayout(2, false);
        reviewLayout.marginWidth = 10;
        reviewLayout.marginHeight = 10;
        reviewArea.setLayout(reviewLayout);
        reviewLabel = new Label(reviewArea, SWT.WRAP);
        GridData reviewLabelData = new GridData(SWT.FILL, SWT.TOP, true, false);
        reviewLabelData.horizontalSpan = 2;
        reviewLabelData.widthHint = 1;
        reviewLabel.setLayoutData(reviewLabelData);
        reviewButton = new Button(reviewArea, SWT.PUSH);
        reviewButton.setText("Review changes…");
        reviewButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        reviewButton.setToolTipText("Review the proposed model changes and checks; the exact script is available separately");
        reviewButton.addListener(SWT.Selection, event -> reviewPendingCode());
        dismissButton = new Button(reviewArea, SWT.PUSH);
        dismissButton.setText("Dismiss");
        dismissButton.addListener(SWT.Selection, event -> dismissReview());
        hideReview();
        inputField = new Text(parent, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        inputField.setFont(JFaceResources.getDefaultFont());
        GridData inputData = new GridData(SWT.FILL, SWT.BOTTOM, true, false);
        inputData.heightHint = inputField.getLineHeight() * 4;
        inputData.widthHint = 1;
        inputField.setLayoutData(inputData);
        inputField.setMessage("Ask about this model or describe a change…");
        inputField.getAccessible().addAccessibleListener(new org.eclipse.swt.accessibility.AccessibleAdapter() {
            @Override
            public void getName(org.eclipse.swt.accessibility.AccessibleEvent event) {
                event.result = "Message to Capella AI. Enter to send; Shift Enter for a new line.";
            }
        });
        inputField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if ((event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR)
                    && (event.stateMask & SWT.SHIFT) == 0) {
                    event.doit = false;
                    handleSend();
                } else if (event.keyCode == SWT.ESC && busy) {
                    event.doit = false;
                    stopRequest();
                }
            }
        });
        inputField.addModifyListener(event -> {
            if (!restoringConversation) {
                conversation.draft(inputField.getText());
                scheduleDraftSave();
            }
            resizeComposer();
            updateControls();
        });
        inputField.addListener(SWT.Resize, event -> resizeComposer());
        Composite actions = new Composite(parent, SWT.NONE);
        actions.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
        GridLayout actionLayout = new GridLayout(3, false);
        actionLayout.marginWidth = 0;
        actionLayout.marginHeight = 0;
        actions.setLayout(actionLayout);
        Label hint = new Label(actions, SWT.WRAP);
        hint.setText("Enter to send · Shift+Enter for a new line");
        GridData hintData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        hintData.widthHint = 1;
        hint.setLayoutData(hintData);
        stopButton = new Button(actions, SWT.PUSH);
        stopButton.setText("Stop");
        stopButton.setToolTipText("Stop generation or pending review. Model writes stop at their next cancellation check. Escape also stops.");
        stopButton.addListener(SWT.Selection, event -> stopRequest());
        sendButton = new Button(actions, SWT.PUSH);
        sendButton.setText("Send");
        sendButton.addListener(SWT.Selection, event -> handleSend());
    }

    private void createToolbar() {
        IToolBarManager toolbar = getViewSite().getActionBars().getToolBarManager();
        newConversationAction = new Action("New conversation") {
            @Override
            public void run() {
                if (!busy) resetConversation(SessionBridge.getActiveSession(), true);
            }
        };
        newConversationAction.setToolTipText("Start a new conversation for this model and clear saved script variables");
        toolbar.add(newConversationAction);
        preferencesAction = new Action("Settings") {
            @Override
            public void run() { openPreferences(); }
        };
        preferencesAction.setToolTipText("Configure the AI provider and model");
        // Settings remains a named header action rather than a duplicate toolbar item.
        requirementsAction = new Action("Enable requirements") {
            @Override
            public void run() { enableRequirements(); }
        };
        requirementsAction.setId("enable-requirements");
        requirementsAction.setToolTipText("Enable the native Requirements viewpoint for this model");
        toolbar.add(requirementsAction);
    }

    private void installContextListeners() {
        selectionListener = (part, selection) -> refreshContext();
        getSite().getWorkbenchWindow().getSelectionService().addPostSelectionListener(selectionListener);
        partListener = new IPartListener() {
            @Override public void partActivated(IWorkbenchPart part) { refreshContext(); }
            @Override public void partClosed(IWorkbenchPart part) { refreshContext(); }
            @Override public void partOpened(IWorkbenchPart part) { refreshContext(); }
            @Override public void partBroughtToTop(IWorkbenchPart part) { refreshContext(); }
            @Override public void partDeactivated(IWorkbenchPart part) {}
        };
        getSite().getPage().addPartListener(partListener);
    }

    private void refreshContext() {
        if (!isUsable()) return;
        refreshProvider();
        activeSession = SessionBridge.getActiveSession();
        if (busy && requestSession != null && activeSession != requestSession && !stopRequested) {
            stopReason = "The active model changed. The previous request was stopped; its conversation remains with that model.";
            stopRequest();
        }
        if (!busy && activeSession != conversationSession) switchConversation(activeSession);
        selectionContext = busy && requestContext != null ? requestContext : SessionBridge.getSelectionContext();
        Session shownSession = busy && requestSession != null ? requestSession : activeSession;
        String model = ChatContextDetails.modelName(shownSession);
        modelLabel.setText(model);
        modelLabel.setToolTipText(model + "\nConversation and draft are saved in this workspace.");
        contextLabel.setText(ChatContextDetails.selectionSummary(selectionContext));
        contextLabel.setToolTipText(ChatContextDetails.selectionSummary(selectionContext)
            + (busy ? "\nThis is the context captured for the current request." : "\nCaptured when you send a message."));
        providerLabel.setText(runtimeLabel()
            + (usesCodexRuntime() && connectionStatus != null ? " · " + connectionStatus : "")
            + (providerConfigured ? "" : " · Setup needed"));
        welcomeTitle.setText(activeSession == null ? "Work with your architecture" : "Work with " + model);
        welcomeDescription.setText(activeSession == null
            ? "Open a Capella model to start. Select an element or open a diagram to give the assistant context."
            : !providerConfigured ? "Choose Settings to configure the assistant. Then ask about this model or describe a change."
            : "Ask about this architecture or describe a change. Your selected elements travel with the request. " + reviewGuidance());
        explainButton.setEnabled(!busy && selectionContext.hasSelection());
        contextButton.setEnabled(!busy && activeSession != null);
        boolean canCreate = PlatformUI.isWorkbenchRunning()
            && PlatformUI.getWorkbench().getNewWizardRegistry().findWizard("capella.project.wizard") != null;
        newModelButton.setVisible(activeSession == null && canCreate);
        ((GridData) newModelButton.getLayoutData()).exclude = !newModelButton.getVisible();
        modelLabel.getParent().getParent().layout(true, true);
        welcomeArea.layout(true, true);
        layoutWelcome();
        updateControls();
        refreshRequirementsControls();
        if (!busy) setReadyActivity();
    }

    private void inspectContext() {
        refreshContext();
        appendMessage("Context", ChatContextDetails.inspect(activeSession, selectionContext));
    }

    private void openPreferences() {
        if (busy || signingIn) return;
        var dialog = PreferencesUtil.createPreferenceDialogOn(getSite().getShell(),
            "org.polarsys.capella.ai.preferences.llm", null, null);
        if (dialog != null) dialog.open();
        connectionStatus = null;
        refreshProvider();
        refreshContext();
    }

    private void signInWithChatGPT() {
        if (busy || signingIn || !usesCodexRuntime() || !isUsable()) return;
        signingIn = true;
        updateControls();
        CodexAccountService.Connected[] connected = { null };
        String message;
        try {
            new ProgressMonitorDialog(getSite().getShell()).run(true, true, monitor -> {
                monitor.beginTask("Connecting to ChatGPT", org.eclipse.core.runtime.IProgressMonitor.UNKNOWN);
                try (CodexAccountService account = CodexAccountService.configured(uri ->
                        openSignInBrowser(uri, () -> disposed || monitor.isCanceled()))) {
                    connected[0] = account.connect(() -> disposed || monitor.isCanceled(), monitor::subTask);
                } catch (CancellationException cancelled) {
                    throw new InterruptedException("ChatGPT sign-in cancelled.");
                } catch (RuntimeException failure) {
                    if (disposed || monitor.isCanceled()) throw new InterruptedException("ChatGPT sign-in cancelled.");
                    throw new InvocationTargetException(failure);
                } finally { monitor.done(); }
            });
            if (connected[0] == null) throw new IllegalStateException("No account connection was confirmed.");
            connectionStatus = "chatgpt".equals(connected[0].authMode())
                ? "ChatGPT connected" : "OpenAI connected";
            message = connectionStatus + ". You can now send a message about your model.";
        } catch (InterruptedException cancelled) {
            message = "ChatGPT sign-in cancelled. Your existing connection was kept.";
        } catch (InvocationTargetException failure) {
            message = signInFailureMessage(failure.getTargetException());
        } catch (RuntimeException unavailable) {
            message = "ChatGPT sign-in could not start. Try again or check Settings.";
        } finally { signingIn = false; }
        if (isUsable()) {
            refreshContext();
            setActivity(message);
        }
    }

    private static String signInFailureMessage(Throwable failure) {
        return failure instanceof CodexAccountService.AccountException safe
            ? safe.getMessage() : "ChatGPT sign-in could not complete. Try again or check Settings.";
    }

    private void openSignInBrowser(URI officialUrl, java.util.function.BooleanSupplier cancelled) {
        if (display == null || display.isDisposed() || cancelled.getAsBoolean()) throw new CancellationException();
        RuntimeException[] failure = { null };
        display.syncExec(() -> {
            try {
                if (!isUsable() || cancelled.getAsBoolean()) throw new CancellationException();
                PlatformUI.getWorkbench().getBrowserSupport().getExternalBrowser().openURL(officialUrl.toURL());
            } catch (Exception unavailable) {
                // Never put an OAuth URL, query, remote exception or credentials in the chat or an error.
                failure[0] = new IllegalStateException("The sign-in page could not open. Check your default browser and try again.");
            }
        });
        if (failure[0] != null) throw failure[0];
    }

    private void openNewModelWizard() {
        if (busy || !PlatformUI.isWorkbenchRunning()) return;
        var workbench = PlatformUI.getWorkbench();
        var descriptor = workbench.getNewWizardRegistry().findWizard("capella.project.wizard");
        if (descriptor == null) return;
        try {
            var wizard = descriptor.createWizard();
            wizard.init(workbench, StructuredSelection.EMPTY);
            new WizardDialog(getSite().getShell(), wizard).open();
            refreshContext();
        } catch (org.eclipse.core.runtime.CoreException failure) {
            appendMessage("Could not open the model wizard", failure.getMessage());
        }
    }

    private void enableRequirements() {
        if (busy) return;
        Session session = SessionBridge.getActiveSession();
        if (session == null || !session.isOpen()) {
            appendMessage("Model unavailable", "Open a Capella model before enabling requirements.");
            return;
        }
        stopRequested = false;
        requestCancellable = false;
        setBusy(true);
        setActivity("Enabling the Requirements viewpoint…");
        requests.execute(() -> {
            requestThread = Thread.currentThread();
            try {
                if (disposed || !isCurrentSession(session)) return;
                boolean changed = new RequirementsAPI(session).enableViewpoint();
                ui(() -> appendMessage("Requirements", changed
                    ? "The native Requirements viewpoint is enabled and the model was saved. You can now create and edit requirements."
                    : "The native Requirements viewpoint is already enabled for this model."));
            } catch (RuntimeException failure) {
                ui(() -> appendMessage("Requirements activation failed", failure.getMessage()));
            } finally {
                requestThread = null;
                ui(() -> {
                    requestCancellable = true;
                    setBusy(false);
                });
            }
        });
    }

    private void resetConversation(Session session, boolean clearDraft) {
        clearTaskTermination();
        if (conversationSession != null) {
            GroovyExecutor.resetSession(GroovyExecutor.buildSessionKey(conversationSession, conversationId));
        }
        if (session != null && session != conversationSession) {
            GroovyExecutor.resetSession(GroovyExecutor.buildSessionKey(session, conversationId));
        }
        conversationSession = session;
        conversationKey = modelKey(session);
        conversation = new ConversationState();
        renderTaskPlan("");
        if (conversationKey != null) conversations.put(conversationKey, conversation);
        transcript.clear();
        conversationLayout.topControl = welcomeScroll;
        conversationArea.layout();
        if (clearDraft) inputField.setText("");
        retryInput = null;
        persistConversation();
        refreshContext();
        inputField.setFocus();
    }

    private void switchConversation(Session session) {
        clearTaskTermination();
        if (draftSaver != null) display.timerExec(-1, draftSaver);
        conversation.draft(inputField.getText());
        persistConversation();
        conversationSession = session;
        conversationKey = modelKey(session);
        ConversationState restored = conversationKey == null ? new ConversationState() : conversations.get(conversationKey);
        String restoreError = null;
        if (restored == null) {
            try { restored = conversationStore == null ? new ConversationState() : conversationStore.load(conversationKey); }
            catch (java.io.IOException failure) {
                restored = new ConversationState();
                restoreError = "The saved conversation could not be read. This chat starts empty; your Capella model is unaffected.";
            }
            conversations.put(conversationKey, restored);
        }
        conversation = restored;
        renderTaskPlan(conversation.taskPlan());
        transcript.clear();
        for (var entry : conversation.entries()) transcript.append(entry.role(), entry.text());
        conversationLayout.topControl = transcript.isEmpty() ? welcomeScroll : transcript.control();
        conversationArea.layout();
        restoringConversation = true;
        try { inputField.setText(conversation.draft()); }
        finally { restoringConversation = false; }
        retryInput = null;
        retrySession = null;
        if (restoreError != null) appendMessage("Conversation recovery", restoreError);
    }

    private static String modelKey(Session session) {
        return session == null || session.getSessionResource() == null || session.getSessionResource().getURI() == null
            ? null : session.getSessionResource().getURI().toString();
    }

    private void refreshProvider() {
        providerConfigured = configurationIssue() == null;
    }

    private static String configurationIssue() {
        try {
            if (!CodexPreferences.isCodex()) return LLMService.configurationIssue();
            // SWT readiness must never extract/hash the runtime or enumerate installed binaries.
            // Actual launch workers resolve and verify it before any account or model request.
            String configured = CodexPreferences.getBinary();
            if (!configured.isBlank()) Path.of(configured);
            else if (BundledCodexRuntime.isPackagedAvailable()) return null;
            return null; // PATH/desktop discovery, when needed, belongs to the launch worker.
        }
        catch (InvalidPathException invalid) { return "The Codex executable path is invalid. Choose the executable in Settings."; }
        catch (RuntimeException unavailable) { return unavailable.getMessage(); }
    }

    private static boolean usesCodexRuntime() {
        try { return CodexPreferences.isCodex(); }
        catch (RuntimeException invalid) { return false; }
    }

    private static String reviewGuidance() {
        if (!LLMPreferences.isToolActionsEnabled()) return "Model changes are disabled in Settings.";
        return AdvancedPreferences.isConfirmBeforeApplyEnabled()
            ? "Proposed model changes stay unapplied until you review them."
            : "Your settings allow model changes to apply without review.";
    }

    private static String runtimeLabel() {
        try {
            return CodexPreferences.isCodex() ? "Codex · " +
                (CodexPreferences.getModel().isBlank() ? "Runtime default" : CodexPreferences.getModel())
                : LLMPreferences.getProvider() + " · " + LLMPreferences.getModel();
        } catch (RuntimeException invalid) { return "Assistant configuration"; }
    }

    private void scheduleDraftSave() {
        if (draftSaver != null) display.timerExec(-1, draftSaver);
        draftSaver = () -> { if (isUsable()) persistConversation(); };
        display.timerExec(500, draftSaver);
    }

    private void persistConversation() {
        if (conversationStore == null || conversationKey == null) return;
        try {
            synchronized (ConversationStore.class) { conversationStore.save(conversationKey, conversation); }
            historySaveFailed = false;
        }
        catch (java.io.IOException failure) {
            historySaveFailed = true;
            if (activityLabel != null && !activityLabel.isDisposed() && !busy) {
                activityLabel.setText("Chat is available · Local history could not be saved");
            }
        }
    }

    private void setBusy(boolean value) {
        busy = value;
        if (activityTimer != null) display.timerExec(-1, activityTimer);
        if (value) {
            requestStarted = System.nanoTime();
            activityTimer = () -> {
                if (!isUsable() || !busy) return;
                renderActivity();
                display.timerExec(1000, activityTimer);
            };
            display.timerExec(1000, activityTimer);
        }
        updateControls();
        if (!value) {
            hideReview();
            refreshContext();
            inputField.setFocus();
        }
    }

    private void updateControls() {
        if (sendButton == null || sendButton.isDisposed()) return;
        sendButton.setEnabled(!busy && !signingIn && providerConfigured && activeSession != null && !inputField.getText().isBlank());
        stopButton.setEnabled(busy && requestCancellable && !stopRequested);
        showControl(stopButton, busy);
        showControl(sendButton, !busy);
        showControl(progress, busy && pendingReview == null);
        showControl(retryButton, !busy && retryInput != null && retrySession == activeSession);
        preferencesButton.setEnabled(!busy && !signingIn);
        boolean codex = usesCodexRuntime();
        showControl(signInButton, codex);
        signInButton.setEnabled(codex && !busy && !signingIn);
        ((GridData) providerLabel.getLayoutData()).horizontalSpan = codex ? 1 : 2;
        if (newConversationAction != null) newConversationAction.setEnabled(!busy && !signingIn && activeSession != null);
        if (preferencesAction != null) preferencesAction.setEnabled(!busy && !signingIn);
    }

    private void refreshRequirementsControls() {
        if (requirementsAction != null) {
            RequirementsAPI requirements = new RequirementsAPI(activeSession);
            boolean available = requirements.available();
            boolean enabled = false;
            if (available && activeSession != null) {
                try { enabled = requirements.viewpointEnabled(); }
                catch (RuntimeException unavailableStatus) { /* Activation reports native diagnostics on click. */ }
            }
            requirementsAction.setText(enabled ? "Requirements enabled" : "Enable requirements");
            requirementsAction.setEnabled(available && activeSession != null && !busy && !enabled);
            var item = getViewSite().getActionBars().getToolBarManager().find("enable-requirements");
            if (item != null && item.isVisible() != available) {
                item.setVisible(available);
                getViewSite().getActionBars().getToolBarManager().update(true);
            }
        }
    }

    private void setReadyActivity() {
        if (activeSession == null) setActivity("Open a model to start");
        else if (!providerConfigured) setActivity(configurationIssue());
        else if (taskTermination != null && terminationSession == activeSession && terminationConversation == conversation) setActivity(taskTermination.activityText());
        else if (historySaveFailed) setActivity("Chat is available · Local history could not be saved");
        else if (!LLMPreferences.isToolActionsEnabled()) setActivity("Ready · Model actions are disabled");
        else if (AdvancedPreferences.isConfirmBeforeApplyEnabled()) setActivity("Ready · Model changes require review");
        else setActivity("Ready · Changes apply without review");
    }

    private void setActivity(String message) {
        activityText = message;
        renderActivity();
    }

    private void renderActivity() {
        long seconds = busy && pendingReview == null ? (System.nanoTime() - requestStarted) / 1000000000L : 0;
        String text = activityText + (seconds >= 3 ? " · " + seconds + "s" : "");
        if (text.equals(activityLabel.getText())) return;
        activityLabel.setText(text);
        relayoutView(activityLabel);
    }

    private static void showControl(org.eclipse.swt.widgets.Control control, boolean visible) {
        if (control == null || control.isDisposed()) return;
        if (control.getLayoutData() == null) control.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        boolean changed = control.getVisible() != visible || ((GridData) control.getLayoutData()).exclude == visible;
        control.setVisible(visible);
        ((GridData) control.getLayoutData()).exclude = !visible;
        if (changed) relayoutView(control);
    }

    /**
     * Flushes cached sizes and lays out the whole view. Composer rows change height (wrapped status,
     * review, composer growth), and the workbench theme restyles widgets after creation, so laying out
     * only the changed control's parent leaves stale positions that clip the Send row.
     */
    private static void relayoutView(org.eclipse.swt.widgets.Control control) {
        Composite top = control instanceof Composite composite ? composite : control.getParent();
        while (top.getParent() != null && top.getParent().getLayout() instanceof GridLayout) top = top.getParent();
        top.layout(true, true);
    }

    private void stopRequest() {
        if (!busy || !requestCancellable || stopRequested) return;
        clearTaskTermination();
        stopRequested = true;
        setActivity("Stopping · Waiting for the current operation to finish safely…");
        CompletableFuture<Boolean> review = pendingReview;
        if (review != null) review.complete(false);
        hideReview();
        updateControls();
        Thread worker = requestThread;
        if (worker != null) worker.interrupt();
    }

    private void handleSend() {
        if (busy || signingIn) return;
        String input = inputField.getText().trim();
        if (input.isEmpty()) return;
        Session session = SessionBridge.getActiveSession();
        if (session == null || !session.isOpen()) {
            appendMessage("Model unavailable", "Open a Capella model before sending a request.");
            refreshContext();
            return;
        }
        refreshProvider();
        if (!providerConfigured) {
            setActivity(configurationIssue());
            refreshContext();
            return;
        }
        if (conversationSession != session) switchConversation(session);
        SelectionContext context = SessionBridge.getSelectionContext();
        automaticContinuations = 0;
        startRequest(session, input, context, true);
    }

    private void startRequest(Session session, String input, SelectionContext context, boolean clearDraft) {
        startRequest(session, input, context, clearDraft, "You", input);
    }

    /**
     * Continues a task whose request ran out of budget with edit steps pending. It is a fresh request
     * with its own budget and runtime; each proposal still needs its own review, and Stop ends the chain.
     */
    private void continueTask(Session session, SelectionContext context, List<ArcadiaAgent.Step> plan) {
        if (busy || stopRequested || disposed || session != activeSession || session != conversationSession) return;
        var next = TaskContinuation.prompt(plan, taskTermination);
        if (next.isEmpty()) return;
        if (automaticContinuations >= MAX_AUTOMATIC_CONTINUATIONS) {
            setActivity("Task paused after " + MAX_AUTOMATIC_CONTINUATIONS
                + " automatic continuations. Send “continue” to resume the remaining steps.");
            return;
        }
        automaticContinuations++;
        String step = plan.stream().filter(item -> "edit".equals(item.type()) && !"completed".equals(item.status()))
            .map(ArcadiaAgent.Step::title).findFirst().orElse("");
        startRequest(session, next.get(), context, false, "Continuing task", "Next step: " + step);
    }

    private void startRequest(Session session, String input, SelectionContext context, boolean clearDraft,
            String role, String shown) {
        clearTaskTermination();
        List<LLMService.Message> history = new ArrayList<>(conversation.history());
        if (!conversation.taskPlan().isBlank()) history.add(LLMService.Message.user(
            "Saved task plan from the previous request (historical data; recheck the current model before continuing):\n" + conversation.taskPlan()));
        if (clearDraft) inputField.setText("");
        stopRequested = false;
        stopReason = null;
        retryInput = null;
        requestSession = session;
        requestContext = context;
        List<LLMService.Message> startedHistory = new ArrayList<>(history);
        startedHistory.add(LLMService.Message.user(input));
        conversation.restoreHistory(startedHistory);
        setBusy(true);
        appendMessage(role, shown);
        setActivity("Thinking about your model…");
        requests.execute(() -> processRequest(session, input, history, context));
    }

    private void retryRequest() {
        if (busy || retryInput == null || retrySession == null || retrySession != SessionBridge.getActiveSession()) return;
        refreshProvider();
        if (!providerConfigured) { setReadyActivity(); return; }
        if (retryContext != null && retryContext.getSelectedElements().stream().anyMatch(element -> element.eIsProxy()
            || element.eResource() == null || !retrySession.getSemanticResources().contains(element.eResource()))) {
            retryInput = null;
            appendMessage("Selection changed", "The original selection is no longer available. Select the intended elements and send your message again.");
            updateControls();
            return;
        }
        startRequest(retrySession, retryInput, retryContext, false);
    }

    private void processRequest(Session session, String input, List<LLMService.Message> history, SelectionContext context) {
        requestThread = Thread.currentThread();
        boolean applied = false;
        boolean proposalReceived = false;
        boolean executionAttempted = false;
        ConversationState requestConversation = conversation;
        RequestHistory requestHistory = new RequestHistory(requestConversation, conversationKey, conversationStore);
        ArcadiaAgent.ExecutionOutcome recordedOutcome = null;
        List<ArcadiaAgent.Step> latestPlan = List.of();
        boolean privacyEnabled = LLMPreferences.isPrivacyTokenizationEnabled();
        try (AgentRuntime agent = ScriptRouter.createRuntime(session, conversationId,
                requestConversation.resumableCodexThreadId(privacyEnabled), id -> display.syncExec(() -> {
                    requestConversation.codexThreadId(id);
                    requestConversation.codexPrivacyMode(privacyEnabled);
                    if (conversation == requestConversation) persistConversation();
                }))) {
            if (stopRequested || disposed) return;
            ArcadiaAgent.Turn turn = agent.run(ScriptRouter.agentSystemPrompt(session, conversationId, context),
                history, input, this::requestCancelled, this::showAgentProgress);
            int proposals = 0;
            for (;;) {
                latestPlan = turn.plan();
                if (requestCancelled()) return;
                if (!turn.hasProposal() && agent.canContinueReadOnly(turn)) {
                    if (!isCurrentSession(session)) return;
                    ArcadiaAgent.Turn remaining = turn;
                    ui(() -> {
                        conversation.restoreHistory(remaining.messages());
                        saveTaskPlan(remaining.plan());
                        setActivity("Checking the remaining task…");
                        persistConversation();
                    });
                    turn = agent.continueRemaining(turn, this::requestCancelled, this::showAgentProgress);
                    continue;
                }
                ArcadiaAgent.Turn displayed = turn;
                proposalReceived |= turn.hasProposal();
                display.syncExec(() -> {
                    if (!isUsable()) return;
                    conversation.restoreHistory(displayed.messages());
                    appendMessage("Capella AI", displayedReply(displayed));
                    saveTaskPlan(displayed.plan());
                    rememberTaskTermination(session, requestConversation, displayed.termination());
                    if (!displayed.hasProposal() && displayed.termination() != null) {
                        conversation.addOutcome("TASK STATUS (declared plan or response only; no engineering completeness claim)\n"
                            + AgentJson.write(displayed.termination().feedback()));
                        persistConversation();
                    }
                });
                if (!turn.hasProposal()) return;
                // A correction receives its own review. No prompt can silently run an unbounded sequence.
                if (proposals >= 3) {
                    ui(() -> {
                        appendMessage("Request budget reached", "Three proposals were handled in this request. Remaining planned edits continue in a fresh request; each proposal still needs your review.");
                        rememberTaskTermination(session, requestConversation, TaskTermination.budget("Three proposals were handled. Review the results and send a follow-up to continue; no additional proposal was run.", displayed.plan()));
                    });
                    return;
                }
                if (!isCurrentSession(session)) {
                    ui(() -> appendMessage("Model changed", "The active model changed. Send the request again for the model you want to edit."));
                    return;
                }
                final String code = turn.proposalCode();
                final String proposalSummary = turn.reply();
                final String expectedChecks = org.polarsys.capella.ai.presentation.NativeCheckDescription.describeAll(turn.postconditions());
                ui(() -> setActivity("Checking the proposal…"));
                GroovyCompilationValidator.Report compilation = GroovyExecutor.compileDecoded(code,
                    this::requestCancelled, 10_000);
                if (requestCancelled() || !isCurrentSession(session)) return;
                if (!compilation.success()) {
                    String preparationFeedback = compilation.formatForRepair();
                    boolean repairing = compilation.repairable() && agent.canRepairCompilation(turn);
                    ui(() -> {
                        appendMessage(repairing ? "Correcting proposal" : "Proposal needs attention", repairing
                            ? "The proposed script did not pass its checks. I’m correcting it before review. No code has run or model changes have been made."
                            : preparationFeedback);
                        conversation.addOutcome(preparationFeedback);
                        persistConversation();
                    });
                    if (repairing) {
                        ui(() -> setActivity("Correcting the proposal…"));
                        turn = agent.repairCompilation(turn, preparationFeedback,
                            this::requestCancelled, this::showAgentProgress);
                        continue;
                    }
                    ui(() -> rememberTaskTermination(session, requestConversation,
                        TaskTermination.blocked("The proposed script could not pass its preparation check. No code was executed. Review the preceding diagnostics before continuing.", displayed.plan())));
                    return;
                }
                proposals++;
                // Recorded as soon as the script returns, so a later capture failure still reports committed changes.
                boolean[] ranAttempt = { false }, ranCommit = { false };
                ReviewedEdit.Outcome edit;
                try {
                    edit = ReviewedEdit.apply(session, new ReviewedEdit.Request(input, turn.reply(), code,
                        turn.postconditions(), conversationId, context, () -> confirmCode(session, code, proposalSummary, expectedChecks),
                        this::requestCancelled, () -> isCurrentSession(session), ModelVerification::snapshot), new ReviewedEdit.Listener() {
                            @Override public void activity(String text) { ui(() -> setActivity(text)); }
                            @Override public void ran(ScriptRouter.Result executed) {
                                ranAttempt[0] |= executed.attempts > 0;
                                ranCommit[0] |= executed.executionResult != null && (executed.executionResult.success()
                                    || executed.executionResult.code() == ExecutionResult.ErrorCode.SAVE_FAILED_AFTER_COMMIT);
                            }
                            @Override public void executed(ScriptRouter.Result executed, String feedback, ModelChangeSummary.ChangeReport report) {
                                ui(() -> {
                                    if (executed.executionResult != null) showExecutionResult(executed.executionResult);
                                    else appendMessage("Model action", feedback);
                                    if (report != null) appendMessage("Model changes", report.markdownSummary());
                                    conversation.addOutcome(feedback);
                                    persistConversation();
                                });
                            }
                            @Override public void checks(String markdown) {
                                ui(() -> { appendMessage("Model result checks", markdown); conversation.addOutcome(markdown); persistConversation(); });
                            }
                            @Override public void validation(String markdown) {
                                ui(() -> { appendMessage("Capella validation", markdown); conversation.addOutcome(markdown); persistConversation(); });
                            }
                        });
                } finally {
                    executionAttempted |= ranAttempt[0];
                    // A committed save failure must never be mistaken for rollback or an invitation to retry.
                    applied |= ranCommit[0];
                }
                ScriptRouter.Result result = edit.result();
                boolean committed = edit.committed();
                ModelChangeSummary.ChangeReport changes = edit.changes();
                ArcadiaAgent.ExecutionOutcome outcome = edit.execution();
                recordedOutcome = outcome;
                if (requestCancelled() || !result.codeExecuted
                    || result.executionResult.code() == ExecutionResult.ErrorCode.SAVE_FAILED_AFTER_COMMIT) {
                    recordExecutionOutcome(agent, turn, outcome, requestHistory);
                    if (!requestCancelled()) {
                        String reason = committed && result.executionResult != null && result.executionResult.code() == ExecutionResult.ErrorCode.SAVE_FAILED_AFTER_COMMIT
                            ? "The model change committed, but saving failed. Save the model in Capella; do not rerun the committed change."
                            : "The proposal was not applied. Review the model action result before continuing.";
                        ui(() -> rememberTaskTermination(session, requestConversation, TaskTermination.blocked(reason, displayed.plan())));
                    }
                    return;
                }
                if (committed && (changes == null || !changes.available())) {
                    recordExecutionOutcome(agent, turn, outcome, requestHistory);
                    ui(() -> appendMessage("Task paused", "The script committed, but its model changes could not be confirmed. Inspect the model before continuing."));
                    ui(() -> rememberTaskTermination(session, requestConversation, TaskTermination.blocked("The script committed, but its model changes could not be confirmed. Inspect the model before continuing; do not replay the committed change.", displayed.plan())));
                    return;
                }
                ui(() -> setActivity("Reviewing the execution result…"));
                turn = agent.finish(turn, outcome,
                    this::requestCancelled, this::showAgentProgress);
                if (requestCancelled()) restoreRequestOutcome(requestHistory, turn, outcome);
            }
        } catch (Exception ex) {
            if (ex instanceof ArcadiaAgent.AgentException failure) {
                restoreRequestOutcome(requestHistory, failure.turn(), recordedOutcome);
                ui(() -> rememberTaskTermination(session, requestConversation, failure.turn().termination()));
            } else if (recordedOutcome != null) {
                restoreRequestOutcome(requestHistory, null, recordedOutcome);
            }
            if (!stopRequested && !disposed) {
                List<ArcadiaAgent.Step> failedPlan = latestPlan;
                boolean anyApplied = applied;
                boolean retrySafe = !proposalReceived && !anyApplied;
                ui(() -> {
                    appendMessage(anyApplied ? "Changes applied · Task could not finish" : "Request could not finish", LLMService.userFacingError(ex));
                    if (!(ex instanceof ArcadiaAgent.AgentException)) rememberTaskTermination(session, requestConversation,
                        TaskTermination.blocked(anyApplied ? "Changes committed, but the task could not finish. Check the preceding execution/save facts; do not replay the change."
                            : "The request could not finish. Review the reported connection or model action error.", failedPlan));
                    if (retrySafe) {
                        retryInput = input;
                        retrySession = session;
                        retryContext = context;
                    }
                    if (!anyApplied && inputField.getText().isBlank()) inputField.setText(input);
                });
            }
        } finally {
            requestThread = null;
            boolean anyApplied = applied;
            boolean attempted = executionAttempted;
            List<ArcadiaAgent.Step> stoppedPlan = latestPlan;
            ui(() -> {
                if (stopRequested) {
                    rememberTaskTermination(session, requestConversation, TaskTermination.blocked(anyApplied
                        ? "Stopped after committed execution. Check the preceding execution/save facts before continuing; do not replay the change."
                        : "The request was stopped. Pending model work is not complete.", stoppedPlan));
                    conversation.addOutcome(anyApplied
                        ? "Request stopped after committed execution. The preceding execution/save facts remain authoritative; do not rerun the script automatically."
                        : attempted ? "Request stopped. No committed changes were confirmed; preceding execution feedback remains authoritative."
                            : "Request stopped before execution. No code was run.");
                    appendMessage("Request stopped", stopReason == null
                        ? anyApplied ? "The request stopped after committed execution. Check the execution and validation results before continuing."
                            : "The request stopped. You can revise your message and send it again." : stopReason);
                    if (!anyApplied && inputField.getText().isBlank()) inputField.setText(input);
                }
                requestSession = null;
                requestContext = null;
                persistConversation();
                setBusy(false);
                continueTask(session, context, stoppedPlan);
            });
        }
    }

    private boolean requestCancelled() { return stopRequested || disposed || Thread.currentThread().isInterrupted(); }

    private void clearTaskTermination() {
        taskTermination = null; terminationSession = null; terminationConversation = null;
    }

    /** Transient status belongs only to this captured model and conversation, never to a future request. */
    private void rememberTaskTermination(Session session, ConversationState owner, TaskTermination value) {
        if (value == null || conversation != owner || conversationSession != session) return;
        taskTermination = value; terminationSession = session; terminationConversation = owner;
        if (activeSession == session) setActivity(value.activityText());
    }

    private void recordExecutionOutcome(AgentRuntime agent, ArcadiaAgent.Turn turn,
            ArcadiaAgent.ExecutionOutcome outcome, RequestHistory requestHistory) {
        agent.recordOutcome(turn, outcome);
        restoreRequestOutcome(requestHistory, agent.outcomeSnapshot(), outcome);
    }

    private record RequestHistory(ConversationState state, String key, ConversationStore store) {}

    private void restoreRequestOutcome(RequestHistory request, ArcadiaAgent.Turn recorded,
            ArcadiaAgent.ExecutionOutcome outcome) {
        Runnable restore = () -> {
            if (isUsable() && conversation == request.state()) {
                // Direct runtimes retain the execution feedback already appended by the UI.
                if (recorded == null) return;
                conversation.restoreHistory(recorded.messages());
                saveTaskPlan(recorded.plan());
                persistConversation();
                return;
            }
            try { persistClosedOutcome(request, recorded, outcome); }
            catch (java.io.IOException failure) { historySaveFailed = true; }
        };
        if (display == null || display.isDisposed()) restore.run();
        else {
            try { display.syncExec(restore); }
            catch (org.eclipse.swt.SWTException failure) {
                if (failure.code != SWT.ERROR_DEVICE_DISPOSED) throw failure;
                restore.run();
            }
        }
    }

    private static void persistClosedOutcome(RequestHistory request, ArcadiaAgent.Turn recorded,
            ArcadiaAgent.ExecutionOutcome outcome) throws java.io.IOException {
        String priorPlan = request.state().taskPlan();
        List<LLMService.Message> priorHistory = request.state().history();
        if (recorded != null) request.state().restoreHistory(recorded.messages());
        // Only actual caller-produced execution facts may be merged into a reopened chat.
        // A stale request's full history, draft, thread binding and plan must not replace it.
        if (request.store() == null || request.key() == null) return;
        if (outcome == null) {
            if (recorded == null) return;
            synchronized (ConversationStore.class) {
                ConversationState latest = request.store().load(request.key());
                if (!sameHistory(latest.history(), priorHistory)
                    || !latest.codexThreadId().equals(request.state().codexThreadId())) return;
                latest.restoreHistory(recorded.messages());
                if (!recorded.plan().isEmpty() && latest.taskPlan().equals(priorPlan)) {
                    latest.taskPlan(formatTaskPlan(recorded.plan()));
                }
                request.store().save(request.key(), latest);
            }
            return;
        }
        String receipt = null;
        if (recorded != null) {
            for (var message : recorded.messages()) {
                if (message.getRole() == LLMService.Message.Role.USER
                    && matchesOutcomeReceipt(message.getContent(), outcome)) receipt = message.getContent();
            }
        }
        String facts = receipt == null ? outcome.feedback() : receipt;
        synchronized (ConversationStore.class) {
            ConversationState latest = request.store().load(request.key());
            if (latest.history().stream().noneMatch(message -> message.getContent().equals(facts))) {
                List<LLMService.Message> history = new ArrayList<>(latest.history());
                history.add(LLMService.Message.user(facts));
                latest.restoreHistory(history);
            }
            if (latest.entries().stream().noneMatch(entry -> "Execution outcome".equals(entry.role())
                && outcome.feedback().equals(entry.text()))) latest.append("Execution outcome", outcome.feedback());
            if (recorded != null && !recorded.plan().isEmpty() && latest.taskPlan().equals(priorPlan)
                && latest.codexThreadId().equals(request.state().codexThreadId())) latest.taskPlan(formatTaskPlan(recorded.plan()));
            request.store().save(request.key(), latest);
        }
    }

    private static boolean sameHistory(List<LLMService.Message> first, List<LLMService.Message> second) {
        if (first.size() != second.size()) return false;
        for (int index = 0; index < first.size(); index++) {
            if (first.get(index).getRole() != second.get(index).getRole()
                || !first.get(index).getContent().equals(second.get(index).getContent())) return false;
        }
        return true;
    }

    private static boolean matchesOutcomeReceipt(String text, ArcadiaAgent.ExecutionOutcome outcome) {
        String prefix = "AUTHORITATIVE CAPELLA EXECUTION OUTCOME\n";
        if (!text.startsWith(prefix)) return false;
        try {
            if (!(AgentJson.parse(text.substring(prefix.length()), 200000) instanceof Map<?, ?> facts)) return false;
            if (!Boolean.valueOf(outcome.applied()).equals(facts.get("applied"))
                || !Boolean.valueOf(outcome.committed()).equals(facts.get("committed"))
                || !Boolean.valueOf(outcome.saved()).equals(facts.get("saved"))
                || !(facts.get("completedStepIds") instanceof List<?> ids)
                || !new java.util.HashSet<>(ids).equals(outcome.completedStepIds())
                || !(facts.get("feedback") instanceof String feedback)) return false;
            return outcome.feedback().equals(feedback) || Boolean.TRUE.equals(facts.get("feedbackTruncated"))
                && feedback.startsWith(outcome.feedback().substring(0, Math.min(32000, outcome.feedback().length())));
        } catch (IllegalArgumentException malformed) { return false; }
    }

    private static String displayedReply(ArcadiaAgent.Turn turn) {
        String text = turn.reply();
        if (turn.hasProposal()) {
            if (text == null || text.isBlank()) text = "Proposed model changes are ready for review.";
            if (!turn.postconditions().isEmpty()) text += "\n\n**Expected model checks**\n" + org.polarsys.capella.ai.presentation.NativeCheckDescription.describeAll(turn.postconditions());
        }
        return text == null ? "" : text;
    }

    private void showAgentProgress(ArcadiaAgent.Progress event) {
        ui(() -> {
            if (stopRequested) return;
            saveTaskPlan(event.plan());
            if ("commentary".equals(event.type())) appendMessage("Codex", event.message());
            if ("plan".equals(event.type()) && !"Read evidence received".equals(event.message())) {
                appendMessage("Task plan", event.message());
            }
            if ("tool".equals(event.type()) || "tool_result".equals(event.type())) {
                String action = event.message().replace("Reading ", "").replace("Read completed: ", "").replace("Read failed: ", "");
                String label = switch (action) {
                    case "model_summary" -> "Summarizing the model";
                    case "model_search" -> "Finding model elements";
                    case "model_inspect" -> "Inspecting model elements";
                    case "model_relationships" -> "Checking model relationships";
                    case "model_validate" -> "Checking model structure";
                    case "native_validation" -> "Running Capella validation";
                    case "capella_docs" -> "Reading Capella guidance";
                    case "capella_capabilities" -> "Checking available model actions";
                    default -> "Reading model evidence";
                };
                setActivity(label + "…");
                if ("tool_result".equals(event.type()) && event.message().startsWith("Read failed:")) {
                    appendMessage("Model inspection", label + " failed. The agent has the error details.");
                }
            } else if ("thinking".equals(event.type())) setActivity("Working through the task…");
        });
    }

    private void saveTaskPlan(List<ArcadiaAgent.Step> steps) {
        if (steps.isEmpty()) return;
        conversation.taskPlan(formatTaskPlan(steps));
        renderTaskPlan(conversation.taskPlan());
        persistConversation();
    }

    private static String formatTaskPlan(List<ArcadiaAgent.Step> steps) {
        return steps.stream().map(step -> switch (step.status()) {
            case "completed" -> "Done · ";
            case "in_progress" -> "In progress · ";
            default -> "Pending · ";
        } + step.title()).collect(java.util.stream.Collectors.joining("\n"));
    }

    private boolean isCurrentSession(Session session) {
        boolean[] current = { false };
        if (display == null || display.isDisposed() || disposed) return false;
        display.syncExec(() -> current[0] = isUsable() && !stopRequested
            && session.isOpen() && SessionBridge.getActiveSession() == session);
        return current[0];
    }

    private boolean confirmCode(Session session, String code) {
        return confirmCode(session, code, "", "");
    }

    private boolean confirmCode(Session session, String code, String proposalSummary, String expectedChecks) {
        CompletableFuture<Boolean> review = new CompletableFuture<>();
        if (display == null || display.isDisposed() || disposed) return false;
        display.syncExec(() -> {
            if (!isUsable() || stopRequested || !session.isOpen()
                || SessionBridge.getActiveSession() != session) {
                review.complete(false);
                return;
            }
            reviewSession = session;
            reviewCode = code;
            reviewProposalSummary = proposalSummary;
            reviewExpectedChecks = expectedChecks;
            reviewModelName = ChatContextDetails.modelName(session);
            reviewContextSummary = ChatContextDetails.selectionSummary(requestContext == null ? selectionContext : requestContext);
            pendingReview = review;
            reviewLabel.setText("Proposed model changes\n" + reviewModelName + "\n"
                + "Review the changes and expected checks before applying. Dismiss to revise your request.");
            showControl(reviewArea, true);
            updateControls();
            setActivity("Waiting for your review · No code has run");
        });
        try {
            return review.get() && !stopRequested && isCurrentSession(session);
        } catch (InterruptedException ex) {
            review.complete(false);
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException ex) {
            return false;
        } finally {
            if (pendingReview == review) pendingReview = null;
            ui(this::hideReview);
        }
    }

    private void reviewPendingCode() {
        CompletableFuture<Boolean> review = pendingReview;
        if (review == null || review.isDone() || stopRequested) return;
        if (reviewSession == null || !reviewSession.isOpen()
            || SessionBridge.getActiveSession() != reviewSession) {
            appendMessage("Model changed", "The review was cancelled because its model is no longer active.");
            review.complete(false);
            hideReview();
            return;
        }
        boolean accepted = new CodePreviewDialog(getSite().getShell(), reviewCode, reviewModelName, reviewContextSummary,
            reviewProposalSummary, reviewExpectedChecks).open() == Window.OK;
        accepted = accepted && !stopRequested && !disposed && reviewSession.isOpen()
            && SessionBridge.getActiveSession() == reviewSession;
        review.complete(accepted);
        hideReview();
        if (accepted) requestStarted = System.nanoTime();
        setActivity(accepted ? "Applying reviewed model changes…" : "Changes were not applied");
    }

    private void dismissReview() {
        CompletableFuture<Boolean> review = pendingReview;
        if (review == null || review.isDone()) return;
        review.complete(false);
        hideReview();
        setActivity("Proposal dismissed · Edit your message to try another approach");
    }

    private void hideReview() {
        showControl(reviewArea, false);
    }

    private void layoutWelcome() {
        if (welcomeScroll == null || welcomeScroll.isDisposed()) return;
        int width = welcomeScroll.getClientArea().width;
        if (width > 0) welcomeScroll.setMinSize(welcomeArea.computeSize(width, SWT.DEFAULT));
    }

    private void resizeComposer() {
        // At creation a hidden view is a few pixels wide; sizing for wrapped text then would stick.
        if (inputField == null || inputField.isDisposed() || inputField.getClientArea().width < 100) return;
        int lineHeight = inputField.getLineHeight();
        int preferred = inputField.computeSize(inputField.getSize().x, SWT.DEFAULT).y;
        int height = Math.max(lineHeight * 3, Math.min(lineHeight * 7, preferred));
        GridData data = (GridData) inputField.getLayoutData();
        if (data.heightHint != height) {
            data.heightHint = height;
            relayoutView(inputField);
        }
    }

    private void showExecutionResult(ExecutionResult result) {
        if (result == null) return;
        StringBuilder message = new StringBuilder();
        String role;
        if (result.success()) {
            role = "Script completed";
            message.append("Finished in ").append(result.durationMs()).append(" ms. Use Capella Undo for model changes.");
            if (result.value() != null) message.append("\n\n**Result**\n").append(ExecutionFeedback.valueForDisplay(result.value()));
        } else if (result.code() == ExecutionResult.ErrorCode.SAVE_FAILED_AFTER_COMMIT) {
            role = "Changes applied · Save failed";
            message.append(result.message());
        } else {
            role = "Model action failed";
            message.append(result.message());
        }
        if (!result.logsForOutput().isEmpty()) {
            message.append("\n\n**Execution output**");
            for (String log : result.logsForOutput()) message.append("\n").append(log);
        }
        appendMessage(role, message.toString());
    }

    private boolean isUsable() {
        return !disposed && transcript != null && !transcript.control().isDisposed()
            && inputField != null && !inputField.isDisposed();
    }

    private void ui(Runnable operation) {
        if (display == null || display.isDisposed() || disposed) return;
        display.asyncExec(() -> { if (isUsable()) operation.run(); });
    }

    private void appendMessage(String role, String text) {
        if (!isUsable()) return;
        if (conversationLayout.topControl != transcript.control()) {
            conversationLayout.topControl = transcript.control();
            conversationArea.layout();
        }
        transcript.append(role, text);
        conversation.append(role, text);
        persistConversation();
    }

    @Override
    public void setFocus() {
        if (inputField != null && !inputField.isDisposed()) {
            refreshContext();
            inputField.setFocus();
        }
    }

    @Override
    public void dispose() {
        if (inputField != null && !inputField.isDisposed()) conversation.draft(inputField.getText());
        persistConversation();
        if (display != null && !display.isDisposed()) {
            if (activityTimer != null) display.timerExec(-1, activityTimer);
            if (draftSaver != null) display.timerExec(-1, draftSaver);
        }
        disposed = true;
        stopRequested = true;
        CompletableFuture<Boolean> review = pendingReview;
        if (review != null) review.complete(false);
        Thread worker = requestThread;
        if (worker != null) worker.interrupt();
        requests.shutdownNow();
        if (selectionListener != null) {
            getSite().getWorkbenchWindow().getSelectionService().removePostSelectionListener(selectionListener);
        }
        if (partListener != null) getSite().getPage().removePartListener(partListener);
        super.dispose();
    }
}
