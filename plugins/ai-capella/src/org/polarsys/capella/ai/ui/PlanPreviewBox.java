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

        // Action hint
        Label hintLabel = new Label(container, SWT.WRAP);
        hintLabel.setText("💡 Review this plan in the Plan Editor before execution");
        hintLabel.setForeground(Display.getDefault().getSystemColor(SWT.COLOR_DARK_GRAY));
        hintLabel.setBackground(backgroundColor);
        hintLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
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
