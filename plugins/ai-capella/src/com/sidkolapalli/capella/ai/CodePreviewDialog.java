package com.sidkolapalli.capella.ai;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.window.Window;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/** Review proposed model changes, with the exact immutable script available for inspection. */
public final class CodePreviewDialog extends Dialog {
    private final String code;
    private final String modelName;
    private final String contextSummary;
    private final String proposalSummary;
    private final String expectedChecks;
    private CTabFolder tabs;
    private Text changes;
    private Text preview;

    /**
     * Modal review for proposals that do not come from the chat view, such as MCP clients. Runs on
     * SWT; returns true only when the person chooses Apply.
     */
    public static boolean review(
            Session session, String code, String origin, String summary, String expectedChecks) {
        org.eclipse.swt.widgets.Display display = org.eclipse.swt.widgets.Display.getDefault();
        Shell shell = display.getActiveShell();
        if (shell == null && org.eclipse.ui.PlatformUI.isWorkbenchRunning()) {
            var window = org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null
                    && org.eclipse.ui.PlatformUI.getWorkbench().getWorkbenchWindowCount() > 0) {
                window = org.eclipse.ui.PlatformUI.getWorkbench().getWorkbenchWindows()[0];
            }
            if (window != null) shell = window.getShell();
        }
        return new CodePreviewDialog(
                                shell,
                                code,
                                ChatContextDetails.modelName(session),
                                origin,
                                summary,
                                expectedChecks)
                        .open()
                == Window.OK;
    }

    CodePreviewDialog(Shell parent, String code) {
        this(parent, code, "Current Capella model", "");
    }

    CodePreviewDialog(Shell parent, String code, String modelName, String contextSummary) {
        this(parent, code, modelName, contextSummary, "", "");
    }

    CodePreviewDialog(
            Shell parent,
            String code,
            String modelName,
            String contextSummary,
            String proposalSummary,
            String expectedChecks) {
        super(parent);
        this.code = code == null ? "" : code;
        this.modelName =
                modelName == null || modelName.isBlank() ? "Current Capella model" : modelName;
        this.contextSummary = contextSummary == null ? "" : contextSummary;
        this.proposalSummary = proposalSummary == null ? "" : proposalSummary;
        this.expectedChecks = expectedChecks == null ? "" : expectedChecks;
        setShellStyle(getShellStyle() | SWT.RESIZE);
        setReturnCode(Window.CANCEL);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Review model changes");
        Point minimum = boundedSize(new Point(480, 480), monitorBounds());
        shell.setMinimumSize(minimum);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 12;
        layout.marginHeight = 10;
        layout.verticalSpacing = 10;
        area.setLayout(layout);

        Composite context = new Composite(area, SWT.NONE);
        GridLayout contextLayout = new GridLayout(2, false);
        contextLayout.marginWidth = 0;
        contextLayout.marginHeight = 0;
        contextLayout.horizontalSpacing = 10;
        context.setLayout(contextLayout);
        context.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        metadata(context, "Model", modelName);
        if (!contextSummary.isBlank()) metadata(context, "Context", contextSummary);

        Label explanation = new Label(area, SWT.WRAP);
        explanation.setText(
                code.indexOf('\0') >= 0
                        ? "The script contains a null character and cannot be applied. Cancel and"
                              + " request a corrected proposal."
                        : code.isBlank()
                                ? "This proposal has no script to apply. Cancel and request a new"
                                      + " proposal."
                                : "Review the proposed changes before applying them. Nothing has"
                                      + " been applied yet.");
        GridData explanationData = new GridData(SWT.FILL, SWT.TOP, true, false);
        explanationData.widthHint = 0;
        explanation.setLayoutData(explanationData);

        // Custom-drawn tabs follow the workbench theme; the native Win32 strip stays light in dark
        // mode.
        tabs = new CTabFolder(area, SWT.BORDER);
        // The theme styles the folder, not plain composites inside it.
        tabs.setBackgroundMode(SWT.INHERIT_DEFAULT);
        GridData tabsData = new GridData(SWT.FILL, SWT.FILL, true, true);
        tabsData.widthHint = 0;
        tabsData.heightHint = 0;
        tabs.setLayoutData(tabsData);
        accessibleName(tabs, "Model change review tabs");

        CTabItem changesTab = new CTabItem(tabs, SWT.NONE);
        changesTab.setText("&Changes");
        changes = new Text(tabs, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.READ_ONLY);
        changes.setText(changesText());
        accessibleName(changes, "Proposed model changes and checks after applying, read-only");
        changesTab.setControl(changes);

        CTabItem scriptTab = new CTabItem(tabs, SWT.NONE);
        scriptTab.setText("&Script");
        Composite script = new Composite(tabs, SWT.NONE);
        GridLayout scriptLayout = new GridLayout(1, false);
        scriptLayout.marginWidth = 8;
        scriptLayout.marginHeight = 8;
        scriptLayout.verticalSpacing = 8;
        script.setLayout(scriptLayout);
        scriptTab.setControl(script);

        Composite toolbar = new Composite(script, SWT.NONE);
        GridLayout toolbarLayout = new GridLayout(3, false);
        toolbarLayout.marginWidth = 0;
        toolbarLayout.marginHeight = 0;
        toolbarLayout.horizontalSpacing = 8;
        toolbar.setLayout(toolbarLayout);
        toolbar.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        Label scriptDetails = new Label(toolbar, SWT.NONE);
        int lines = lineCount(code);
        scriptDetails.setText("Groovy script · " + lines + (lines == 1 ? " line" : " lines"));
        scriptDetails.setToolTipText(code.length() + " characters in the exact generated script");
        scriptDetails.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button selectAll = new Button(toolbar, SWT.PUSH);
        selectAll.setText("Select &all");
        selectAll.setToolTipText("Select the entire read-only script");
        selectAll.setEnabled(!code.isEmpty() && code.indexOf('\0') < 0);
        selectAll.addListener(
                SWT.Selection,
                event -> {
                    preview.setFocus();
                    preview.selectAll();
                });
        Button copy = new Button(toolbar, SWT.PUSH);
        copy.setText("&Copy code");
        copy.setToolTipText("Copy the exact generated script, preserving its original text");
        copy.setEnabled(!code.isEmpty() && code.indexOf('\0') < 0);
        copy.addListener(SWT.Selection, event -> copyCode(copy));

        preview =
                new Text(
                        script,
                        SWT.BORDER | SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.READ_ONLY);
        preview.setFont(JFaceResources.getTextFont());
        GridData previewData = new GridData(SWT.FILL, SWT.FILL, true, true);
        previewData.widthHint = 0;
        previewData.heightHint = 0;
        preview.setLayoutData(previewData);
        // SWT's native Text control cannot display NUL faithfully. Never approve a truncated
        // preview.
        preview.setText(code.indexOf('\0') < 0 ? code : "");
        accessibleName(preview, "Exact generated Groovy script, read-only");
        tabs.setSelection(changesTab);

        Label effects = new Label(area, SWT.WRAP);
        effects.setText("Model changes use one Undo operation. External effects cannot be undone.");
        GridData effectsData = new GridData(SWT.FILL, SWT.BOTTOM, true, false);
        effectsData.widthHint = 0;
        effects.setLayoutData(effectsData);
        return area;
    }

    private String changesText() {
        String summary =
                proposalSummary.isBlank()
                        ? "A model edit was proposed without a readable summary. Review the Script"
                              + " tab before applying, or cancel and ask the assistant to explain"
                              + " the changes."
                        : proposalSummary;
        String checks =
                expectedChecks.isBlank()
                        ? "No specific model checks were declared for this proposal."
                        : expectedChecks;
        // Native Text truncates at NUL; make any such display character visible instead.
        return ("Proposed changes\n\n" + summary + "\n\nChecks after applying\n\n" + checks)
                .replace('\0', '\uFFFD');
    }

    private static void accessibleName(Control control, String name) {
        control.getAccessible()
                .addAccessibleListener(
                        new AccessibleAdapter() {
                            @Override
                            public void getName(AccessibleEvent event) {
                                if (event.childID == ACC.CHILDID_SELF) event.result = name;
                            }
                        });
    }

    private void metadata(Composite parent, String name, String value) {
        Label label = new Label(parent, SWT.NONE);
        label.setText(name);
        // A single native text row remains selectable and horizontally scrollable for long names.
        Text text = new Text(parent, SWT.SINGLE | SWT.READ_ONLY);
        text.setText(value);
        text.setBackground(parent.getBackground());
        text.setToolTipText(value);
        GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
        data.widthHint = 0;
        text.setLayoutData(data);
        text.getAccessible()
                .addAccessibleListener(
                        new AccessibleAdapter() {
                            @Override
                            public void getName(AccessibleEvent event) {
                                event.result = name + ": " + value;
                            }
                        });
    }

    private void copyCode(Button button) {
        Clipboard clipboard = new Clipboard(getShell().getDisplay());
        try {
            // Do not copy preview.getText(): the native widget can normalize line delimiters.
            clipboard.setContents(new Object[] {code}, new Transfer[] {TextTransfer.getInstance()});
            button.setText("Copied");
            button.getDisplay()
                    .timerExec(
                            1800,
                            () -> {
                                if (!button.isDisposed()) button.setText("&Copy code");
                            });
        } catch (SWTError error) {
            if (error.code != DND.ERROR_CANNOT_SET_CLIPBOARD) throw error;
            MessageDialog.openError(
                    getShell(),
                    "Could not copy code",
                    "The clipboard is unavailable. Try Copy code again.");
        } finally {
            clipboard.dispose();
        }
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        Button run = createButton(parent, IDialogConstants.OK_ID, "&Apply changes", false);
        run.setEnabled(canRun());
        run.setToolTipText("Apply this proposal's exact script to the model shown above");
        createButton(parent, IDialogConstants.CANCEL_ID, "&Cancel", true);
    }

    @Override
    protected void okPressed() {
        if (canRun()) super.okPressed();
    }

    private boolean canRun() {
        return !code.isBlank() && code.indexOf('\0') < 0;
    }

    static int lineCount(String code) {
        if (code == null || code.isEmpty()) return 0;
        int count = 1;
        for (int index = 0; index < code.length(); index++) {
            char value = code.charAt(index);
            if (value == '\n') count++;
            else if (value == '\r') {
                count++;
                if (index + 1 < code.length() && code.charAt(index + 1) == '\n') index++;
            }
        }
        return count;
    }

    @Override
    protected Point getInitialSize() {
        return boundedSize(new Point(840, 620), monitorBounds());
    }

    private Rectangle monitorBounds() {
        Shell parent = getParentShell();
        if (parent != null && !parent.isDisposed()) return parent.getMonitor().getClientArea();
        Shell shell = getShell();
        Display display =
                shell != null && !shell.isDisposed() ? shell.getDisplay() : Display.getCurrent();
        return (display != null ? display : Display.getDefault())
                .getPrimaryMonitor()
                .getClientArea();
    }

    static Point boundedSize(Point requested, Rectangle monitor) {
        return new Point(
                Math.max(1, Math.min(requested.x, monitor.width - 32)),
                Math.max(1, Math.min(requested.y, monitor.height - 32)));
    }
}
