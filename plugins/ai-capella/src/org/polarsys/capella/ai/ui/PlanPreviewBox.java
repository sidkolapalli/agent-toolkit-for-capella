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
    private final Color backgroundColor;
    private final Color headerColor;
    private ToolPlan plan;
    private Runnable onAccept;
    private Runnable onEdit;
    private Runnable onCancel;

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
        GridData containerData = new GridData(SWT.CENTER, SWT.TOP, true, false);
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
        planSummaryText = new Text(container, SWT.MULTI | SWT.WRAP | SWT.READ_ONLY);
        GridData summaryData = new GridData(SWT.FILL, SWT.FILL, true, false);
        summaryData.minimumHeight = 80;
        planSummaryText.setLayoutData(summaryData);
        planSummaryText.setBackground(backgroundColor);
        planSummaryText.setForeground(Display.getDefault().getSystemColor(SWT.COLOR_BLACK));

        // Actions row
        Composite actions = new Composite(container, SWT.NONE);
        actions.setLayout(new GridLayout(3, false));
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
    }

    /**
     * Set the plan and summary text.
     */
    public void setPlan(ToolPlan plan, List<String> summaryLines) {
        this.plan = plan;
        if (!planSummaryText.isDisposed()) {
            planSummaryText.setText(formatSummary(summaryLines));
        }
    }

    public void setOnAccept(Runnable onAccept) { this.onAccept = onAccept; }
    public void setOnEdit(Runnable onEdit) { this.onEdit = onEdit; }
    public void setOnCancel(Runnable onCancel) { this.onCancel = onCancel; }

    /**
     * Get the associated plan.
     */
    public ToolPlan getPlan() {
        return plan;
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
}
