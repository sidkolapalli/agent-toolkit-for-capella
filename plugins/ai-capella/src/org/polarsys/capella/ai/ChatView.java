package org.polarsys.capella.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;
import org.polarsys.capella.ai.llm.LLMService;
import org.polarsys.capella.ai.scripting.CodeExecutionRouter;
import org.polarsys.capella.ai.scripting.ExecutionResult;
import org.polarsys.capella.ai.scripting.GroovyExecutor;
import org.polarsys.capella.ai.session.SelectionContext;
import org.polarsys.capella.ai.session.SessionBridge;

/**
 * Minimal chat view for code execution architecture.
 *
 * <p>This is a simplified UI that:
 * <ul>
 *   <li>Takes natural language input</li>
 *   <li>Sends to LLM for code generation</li>
 *   <li>Executes generated Groovy code</li>
 *   <li>Displays results</li>
 * </ul>
 *
 * <p>Replaces the complex legacy ChatView (~3000 lines) with a minimal
 * implementation (~400 lines) following the code execution pattern.
 */
public class ChatView extends ViewPart {

    public static final String ID = "org.polarsys.capella.ai.chatview";

    private StyledText outputArea;
    private Text inputField;
    private Font monoFont;
    private Color codeColor;
    private Color errorColor;
    private Color successColor;
    private Color userColor;

    private List<LLMService.Message> conversationHistory = new ArrayList<>();
    private final String conversationId = UUID.randomUUID().toString();

    @Override
    public void createPartControl(Composite parent) {
        // Initialize colors
        Display display = parent.getDisplay();
        codeColor = new Color(display, 100, 100, 180);
        errorColor = new Color(display, 200, 80, 80);
        successColor = new Color(display, 80, 160, 80);
        userColor = new Color(display, 80, 80, 200);
        monoFont = JFaceResources.getTextFont();

        // Layout
        Composite container = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 5;
        layout.marginHeight = 5;
        container.setLayout(layout);

        // Output area (top, takes most space)
        outputArea = new StyledText(
            container,
            SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.WRAP | SWT.READ_ONLY
        );
        outputArea.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        outputArea.setFont(monoFont);

        // Input field (bottom)
        inputField = new Text(container, SWT.BORDER | SWT.SINGLE);
        inputField.setLayoutData(
            new GridData(SWT.FILL, SWT.CENTER, true, false)
        );
        inputField.setMessage("Ask anything about Capella... (Enter to send)");

        // Handle Enter key
        inputField.addKeyListener(
            new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR) {
                        handleSend();
                    }
                }
            }
        );

        // Toolbar actions
        createToolbar();

        // Initial message
        appendOutput("Capella AI Assistant ready.\n", null);
        appendOutput(
            "Type a request and press Enter. I'll generate and execute Groovy code.\n\n",
            null
        );
        appendOutput("Tips:\n", null);
        appendOutput(
            "  - \"Create a system function called Process Data\"\n",
            null
        );
        appendOutput("  - \"Show me all components in the model\"\n", null);
        appendOutput("  - \"Create a state machine for Controller\"\n\n", null);
    }

    private void createToolbar() {
        IToolBarManager toolbar = getViewSite()
            .getActionBars()
            .getToolBarManager();

        // Clear action
        Action clearAction = new Action("Clear") {
            @Override
            public void run() {
                outputArea.setText("");
                conversationHistory.clear();
            }
        };
        clearAction.setToolTipText("Clear conversation");
        toolbar.add(clearAction);
    }

    private void handleSend() {
        String input = inputField.getText().trim();
        if (input.isEmpty()) {
            return;
        }

        inputField.setText("");
        inputField.setEnabled(false);

        // Show user message
        appendOutput("\n> " + input + "\n", userColor);

        Session session = SessionBridge.getActiveSession();
        if (session == null) {
            appendOutput(
                "\nError: No active Capella session. Open a model first.\n",
                errorColor
            );
            inputField.setEnabled(true);
            return;
        }

        // Process async
        CompletableFuture.runAsync(() -> {
            try {
                processRequest(session, input);
            } catch (Exception e) {
                Display.getDefault().asyncExec(() -> {
                    appendOutput(
                        "\nError: " + e.getMessage() + "\n",
                        errorColor
                    );
                    inputField.setEnabled(true);
                });
            }
        });
    }

    private void processRequest(Session session, String userInput) {
        // Build messages
        String sessionKey = GroovyExecutor.buildSessionKey(
            session,
            conversationId
        );
        SelectionContext selectionContext = SessionBridge.getSelectionContext();
        String systemPrompt = CodeExecutionRouter.buildCodeExecutionPrompt(
            session,
            sessionKey,
            selectionContext
        );
        if (conversationHistory.isEmpty()) {
            conversationHistory.add(LLMService.Message.system(systemPrompt));
        } else if (
            conversationHistory.get(0).getRole() == LLMService.Message.Role.SYSTEM
        ) {
            conversationHistory.set(
                0,
                LLMService.Message.system(systemPrompt)
            );
        } else {
            conversationHistory.add(0, LLMService.Message.system(systemPrompt));
        }
        conversationHistory.add(LLMService.Message.user(userInput));

        Display.getDefault().asyncExec(() -> {
            appendOutput("\nGenerating code...\n", null);
        });

        try {
            // Get LLM response
            String reply = LLMService.get().send(conversationHistory);
            conversationHistory.add(LLMService.Message.assistant(reply));

            // Check for code
            if (!CodeExecutionRouter.containsCode(reply)) {
                Display.getDefault().asyncExec(() -> {
                    appendOutput("\n" + reply + "\n", null);
                    inputField.setEnabled(true);
                });
                return;
            }

            // Extract and show code
            String code = GroovyExecutor.extractCode(reply);
            Display.getDefault().asyncExec(() -> {
                appendOutput("\nGenerated code:\n", null);
                appendOutput("```groovy\n" + code + "\n```\n", codeColor);
                appendOutput("\nExecuting...\n", null);
            });

            // Execute
            HashMap<String, Object> extraBindings = new HashMap<>();
            extraBindings.put(
                "selection",
                selectionContext != null
                    ? selectionContext.getSelectedElements()
                    : java.util.List.of()
            );
            extraBindings.put(
                "activeDiagram",
                selectionContext != null
                    ? selectionContext.getActiveDiagram().orElse(null)
                    : null
            );
            ExecutionResult result = GroovyExecutor.execute(
                session,
                code,
                extraBindings,
                sessionKey
            );

            Display.getDefault().asyncExec(() -> {
                if (result.success()) {
                    appendOutput("\nSuccess! ", successColor);
                    appendOutput("(" + result.durationMs() + "ms)\n", null);

                    if (result.value() != null) {
                        appendOutput(
                            "Result: " + formatValue(result.value()) + "\n",
                            null
                        );
                    }

                    if (result.hasLogs()) {
                        appendOutput("\nOutput:\n", null);
                        for (String log : result.logs()) {
                            appendOutput("  " + log + "\n", null);
                        }
                    }
                } else {
                    appendOutput("\nExecution failed: ", errorColor);
                    appendOutput(result.message() + "\n", null);

                    if (result.hasLogs()) {
                        appendOutput("\nLogs before error:\n", null);
                        for (String log : result.logs()) {
                            appendOutput("  " + log + "\n", null);
                        }
                    }

                    // Add error to conversation for self-correction opportunity
                    conversationHistory.add(
                        LLMService.Message.user(
                            "The code failed with: " +
                                result.message() +
                                "\nPlease fix the issue and provide corrected code."
                        )
                    );
                }
                inputField.setEnabled(true);
            });
        } catch (Exception e) {
            Display.getDefault().asyncExec(() -> {
                appendOutput(
                    "\nLLM Error: " + e.getMessage() + "\n",
                    errorColor
                );
                inputField.setEnabled(true);
            });
        }
    }

    private void appendOutput(String text, Color color) {
        int start = outputArea.getCharCount();
        outputArea.append(text);

        if (color != null) {
            StyleRange style = new StyleRange();
            style.start = start;
            style.length = text.length();
            style.foreground = color;
            outputArea.setStyleRange(style);
        }

        // Auto-scroll to bottom
        outputArea.setTopIndex(outputArea.getLineCount() - 1);
    }

    private String formatValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof java.util.List<?> list) {
            return "List[" + list.size() + " items]";
        }
        if (value instanceof org.eclipse.emf.ecore.EObject eo) {
            try {
                String className = eo.eClass().getName();
                Object name = eo.eGet(
                    eo.eClass().getEStructuralFeature("name")
                );
                return className + ": " + (name != null ? name : "<unnamed>");
            } catch (Exception e) {
                return eo.getClass().getSimpleName();
            }
        }
        String str = value.toString();
        if (str.length() > 100) {
            return str.substring(0, 100) + "...";
        }
        return str;
    }

    @Override
    public void setFocus() {
        inputField.setFocus();
    }

    @Override
    public void dispose() {
        if (codeColor != null && !codeColor.isDisposed()) codeColor.dispose();
        if (
            errorColor != null && !errorColor.isDisposed()
        ) errorColor.dispose();
        if (
            successColor != null && !successColor.isDisposed()
        ) successColor.dispose();
        if (userColor != null && !userColor.isDisposed()) userColor.dispose();
        super.dispose();
    }
}
