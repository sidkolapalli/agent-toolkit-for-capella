package org.polarsys.capella.ai.ui;

import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.polarsys.capella.ai.llm.ToolRouter.ToolPlan;
import org.polarsys.capella.ai.llm.ToolRouter.PlanItem;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;

/**
 * Inline plan preview box showing AI-generated actions before execution.
 * Displays plan summary with visual styling distinct from messages.
 */
public class PlanPreviewBox {

    private final Composite container;
    private final Text planSummaryText;
    private Composite chipsRow;
    private Composite itemsContainer;
    private Text detailsText;
    private java.util.List<String> lastDetails;
    private final Color backgroundColor;
    private final Color headerColor;
    private ToolPlan plan;
    private Runnable onAccept;
    private Runnable onEdit;
    private Runnable onCancel;
    private boolean detailsVisible = false;

    public PlanPreviewBox(Composite parent) {
        // Wrapper for centering
        Composite wrapper = new Composite(parent, SWT.NONE);
        wrapper.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        wrapper.setLayout(new GridLayout(1, false));
        wrapper.setBackground(Display.getDefault().getSystemColor(SWT.COLOR_WHITE));

        // Plan preview container
        backgroundColor = createBackgroundColor();
        headerColor = createHeaderColor();
        container = new Composite(wrapper, SWT.BORDER);
        final GridData containerData = new GridData(SWT.CENTER, SWT.TOP, true, false);
        // Dynamic width: shrink to fit on narrow views, cap at 600px on wide views
        wrapper.addListener(SWT.Resize, e -> {
            int availableWidth = wrapper.getSize().x;
            if (availableWidth > 0) {
                // Leave margins for padding
                int maxWidth = Math.min(availableWidth - 40, 600);
                containerData.widthHint = maxWidth;
                wrapper.layout(true);
            }
        });
        // Initial width hint
        containerData.widthHint = 600;
        container.setLayoutData(containerData);
        container.setLayout(new GridLayout(1, false));
        container.setBackground(backgroundColor);
        container.addDisposeListener(e -> disposeColors());

        // Plan header
        Label headerLabel = new Label(container, SWT.NONE);
        headerLabel.setText("📋 Proposed Plan");
        headerLabel.setForeground(headerColor);
        headerLabel.setBackground(backgroundColor);
        headerLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        // Plan summary
        chipsRow = new Composite(container, SWT.NONE);
        chipsRow.setLayout(new GridLayout(8, false));
        chipsRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        planSummaryText = new Text(container, SWT.MULTI | SWT.WRAP | SWT.READ_ONLY);
        GridData summaryData = new GridData(SWT.FILL, SWT.FILL, true, false);
        summaryData.minimumHeight = 80;
        planSummaryText.setLayoutData(summaryData);
        planSummaryText.setBackground(backgroundColor);
        planSummaryText.setForeground(Display.getDefault().getSystemColor(SWT.COLOR_BLACK));

        // Actions row
        Composite actions = new Composite(container, SWT.NONE);
        actions.setLayout(new GridLayout(5, false));
        actions.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));

        Button applyBtn = new Button(actions, SWT.PUSH);
        applyBtn.setText("Apply Plan");
        applyBtn.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) { if (onAccept != null) onAccept.run(); }
        });

        Button editBtn = new Button(actions, SWT.PUSH);
        editBtn.setText("Edit…");
        editBtn.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) { if (onEdit != null) onEdit.run(); }
        });

        Button dismissBtn = new Button(actions, SWT.PUSH);
        dismissBtn.setText("Dismiss");
        dismissBtn.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) { if (onCancel != null) onCancel.run(); }
        });

        Button detailsBtn = new Button(actions, SWT.PUSH);
        detailsBtn.setText("Show details");
        detailsBtn.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                toggleDetails();
                detailsBtn.setText(detailsVisible ? "Hide details" : "Show details");
            }
        });

        Button viewChangesBtn = new Button(actions, SWT.PUSH);
        viewChangesBtn.setText("View changes…");
        viewChangesBtn.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                openChangesDialog();
            }
        });

        // Action items (inline checkbox editor)
        itemsContainer = new Composite(container, SWT.NONE);
        itemsContainer.setLayout(new GridLayout(1, false));
        itemsContainer.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));

        // Details area (hidden by default)
        detailsText = new Text(container, SWT.MULTI | SWT.WRAP | SWT.READ_ONLY | SWT.V_SCROLL);
        GridData dgd = new GridData(SWT.FILL, SWT.FILL, true, false);
        dgd.heightHint = 120;
        detailsText.setLayoutData(dgd);
        detailsText.setVisible(false);
        detailsText.setBackground(backgroundColor);
        detailsText.setForeground(Display.getDefault().getSystemColor(SWT.COLOR_DARK_GRAY));
    }

    /**
     * Set the plan and summary text.
     */
    public void setPlan(ToolPlan plan, List<String> summaryLines) {
        this.plan = plan;
        if (!planSummaryText.isDisposed()) {
            planSummaryText.setText(formatSummary(summaryLines));
        }
        rebuildItems();
    }

    public void setOnAccept(Runnable onAccept) { this.onAccept = onAccept; }
    public void setOnEdit(Runnable onEdit) { this.onEdit = onEdit; }
    public void setOnCancel(Runnable onCancel) { this.onCancel = onCancel; }

    /** Set validation chips above the summary (e.g., warnings). */
    public void setChips(List<String> chips) {
        if (chipsRow == null || chipsRow.isDisposed()) return;
        for (org.eclipse.swt.widgets.Control c : chipsRow.getChildren()) { c.dispose(); }
        if (chips == null || chips.isEmpty()) {
            chipsRow.layout(true, true);
            return;
        }
        int shown = 0;
        for (String chip : chips) {
            if (chip == null || chip.isBlank()) continue;
            Label lbl = new Label(chipsRow, SWT.BORDER);
            lbl.setBackground(Display.getDefault().getSystemColor(SWT.COLOR_INFO_BACKGROUND));
            lbl.setText("⚠ " + chip.trim());
            lbl.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
            shown++;
            if (shown >= 6) break;
        }
        chipsRow.layout(true, true);
    }

    /**
     * Optional detailed dry-run outcomes for preview.
     */
    public void setDetails(List<String> lines) {
        this.lastDetails = (lines == null) ? java.util.List.of() : java.util.List.copyOf(lines);
        if (detailsText != null && !detailsText.isDisposed()) {
            String text = (lines == null || lines.isEmpty()) ? "" : lines.stream().collect(Collectors.joining(System.lineSeparator()));
            detailsText.setText(text);
        }
    }

    /**
     * Get the associated plan.
     */
    public ToolPlan getPlan() {
        return plan;
    }

    /**
     * Return a filtered plan based on currently checked items.
     */
    public ToolPlan getSelectedPlan() {
        if (plan == null || itemsContainer == null || itemsContainer.isDisposed()) {
            return plan;
        }
        java.util.List<Integer> selected = new java.util.ArrayList<>();
        org.eclipse.swt.widgets.Control[] children = itemsContainer.getChildren();
        for (org.eclipse.swt.widgets.Control c : children) {
            if (c instanceof Button) {
                Button cb = (Button) c;
                Object data = cb.getData("index");
                if (data instanceof Integer && cb.getSelection()) {
                    selected.add((Integer) data);
                }
            }
        }
        return plan.filterByIndex(selected);
    }

    /**
     * Check if disposed.
     */
    public boolean isDisposed() {
        return container.isDisposed();
    }

    /**
     * Dispose this preview box.
     */
    public void dispose() {
        if (!container.isDisposed()) {
            Composite wrapper = container.getParent();
            container.dispose();
            if (wrapper != null && !wrapper.isDisposed()) {
                wrapper.dispose();
            }
        }
        disposeColors();
    }

    /**
     * Get plan preview background color (light lavender).
     */
    private Color createBackgroundColor() {
        return new Color(Display.getDefault(), 240, 240, 255);
    }

    /**
     * Get plan header color (dark blue).
     */
    private Color createHeaderColor() {
        return new Color(Display.getDefault(), 60, 60, 150);
    }

    private String formatSummary(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "(No TOOL_PLAN actions provided)";
        }
        return lines.stream()
                .map(line -> "• " + line)
                .collect(Collectors.joining(System.lineSeparator()));
    }

    private void disposeColors() {
        disposeIfNeeded(backgroundColor);
        disposeIfNeeded(headerColor);
    }

    private void disposeIfNeeded(Color color) {
        if (color != null && !color.isDisposed()) {
            color.dispose();
        }
    }

    private void toggleDetails() {
        if (detailsText == null || detailsText.isDisposed()) return;
        detailsVisible = !detailsVisible;
        detailsText.setVisible(detailsVisible);
        Composite parent = detailsText.getParent();
        if (parent != null && !parent.isDisposed()) {
            parent.layout(true, true);
        }
    }

    private void openChangesDialog() {
        org.eclipse.swt.widgets.Shell shell = container.getShell();
        org.eclipse.swt.widgets.Shell dialog = new org.eclipse.swt.widgets.Shell(shell, SWT.DIALOG_TRIM | SWT.RESIZE | SWT.APPLICATION_MODAL);
        dialog.setText("Plan Changes");
        dialog.setLayout(new GridLayout(1, false));
        org.eclipse.swt.widgets.Text text = new org.eclipse.swt.widgets.Text(dialog, SWT.MULTI | SWT.WRAP | SWT.READ_ONLY | SWT.V_SCROLL);
        text.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        String content = (lastDetails == null || lastDetails.isEmpty()) ? "(no changes computed)" : lastDetails.stream().collect(Collectors.joining(System.lineSeparator()));
        text.setText(content);
        org.eclipse.swt.widgets.Button close = new org.eclipse.swt.widgets.Button(dialog, SWT.PUSH);
        close.setText("Close");
        close.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
        close.addSelectionListener(new SelectionAdapter() { @Override public void widgetSelected(SelectionEvent e) { dialog.close(); } });
        dialog.setSize(640, 420);
        dialog.open();
    }

    private void rebuildItems() {
        if (itemsContainer == null || itemsContainer.isDisposed()) return;
        for (org.eclipse.swt.widgets.Control child : itemsContainer.getChildren()) {
            child.dispose();
        }
        if (plan == null || plan.isEmpty()) {
            itemsContainer.layout(true, true);
            return;
        }
        for (PlanItem item : plan.items()) {
            Button cb = new Button(itemsContainer, SWT.CHECK);
            cb.setData("index", Integer.valueOf(item.getIndex()));
            cb.setText(item.getTypeLabel());
            cb.setSelection(true); // default: all selected
            cb.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        }
        itemsContainer.layout(true, true);
    }
}
