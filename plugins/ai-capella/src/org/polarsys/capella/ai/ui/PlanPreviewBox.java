package org.polarsys.capella.ai.ui;

import java.util.List;
import java.util.stream.Collectors;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowData;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.polarsys.capella.ai.llm.ToolRouter.PlanItem;
import org.polarsys.capella.ai.llm.ToolRouter.ToolPlan;

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
    private GridData containerData;

    public PlanPreviewBox(Composite parent) {
        // Wrapper for centering
        Composite wrapper = new Composite(parent, SWT.NONE);
        wrapper.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        wrapper.setLayout(new GridLayout(1, false));
        wrapper.setBackground(
            Display.getDefault().getSystemColor(SWT.COLOR_WHITE)
        );

        // Plan preview container
        backgroundColor = createBackgroundColor();
        headerColor = createHeaderColor();
        container = new Composite(wrapper, SWT.BORDER);
        containerData = new GridData(SWT.CENTER, SWT.TOP, true, false);
        // Dynamic width: ~85% of viewport with safe margins, Apple-like readable width
        wrapper.addListener(SWT.Resize, e -> {
            int availableWidth = wrapper.getClientArea().width;
            if (availableWidth <= 0) availableWidth = wrapper.getSize().x;
            if (availableWidth > 0) {
                containerData.widthHint = computePlanWidth(availableWidth);
                wrapper.layout(true, true);
            }
        });
        // Initial width hint based on current viewport if available
        int initAvailable = wrapper.getClientArea().width;
        if (initAvailable <= 0) initAvailable = 600;
        containerData.widthHint = computePlanWidth(initAvailable);
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
        // Flow layout with wrapping like compact badges
        RowLayout chipLayout = new RowLayout(SWT.HORIZONTAL);
        chipLayout.wrap = true;
        chipLayout.pack = false;
        chipLayout.justify = false;
        chipLayout.marginLeft = 0;
        chipLayout.marginRight = 0;
        chipLayout.marginTop = 0;
        chipLayout.marginBottom = 0;
        chipLayout.spacing = 6;
        chipsRow.setLayout(chipLayout);
        chipsRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        planSummaryText = new Text(
            container,
            SWT.MULTI | SWT.WRAP | SWT.READ_ONLY
        );
        GridData summaryData = new GridData(SWT.FILL, SWT.FILL, true, false);
        summaryData.minimumHeight = 80;
        planSummaryText.setLayoutData(summaryData);
        planSummaryText.setBackground(backgroundColor);
        planSummaryText.setForeground(
            Display.getDefault().getSystemColor(SWT.COLOR_BLACK)
        );

        // Actions row
        Composite actions = new Composite(container, SWT.NONE);
        actions.setLayout(new GridLayout(5, false));
        actions.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));

        Button applyBtn = new Button(actions, SWT.PUSH);
        applyBtn.setText("Apply Plan");
        applyBtn.addSelectionListener(
            new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    if (onAccept != null) onAccept.run();
                }
            }
        );

        Button editBtn = new Button(actions, SWT.PUSH);
        editBtn.setText("Edit…");
        editBtn.addSelectionListener(
            new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    if (onEdit != null) onEdit.run();
                }
            }
        );

        Button dismissBtn = new Button(actions, SWT.PUSH);
        dismissBtn.setText("Dismiss");
        dismissBtn.addSelectionListener(
            new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    if (onCancel != null) onCancel.run();
                }
            }
        );

        Button detailsBtn = new Button(actions, SWT.PUSH);
        detailsBtn.setText("Show details");
        detailsBtn.addSelectionListener(
            new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    toggleDetails();
                    detailsBtn.setText(
                        detailsVisible ? "Hide details" : "Show details"
                    );
                }
            }
        );

        Button viewChangesBtn = new Button(actions, SWT.PUSH);
        viewChangesBtn.setText("View changes…");
        viewChangesBtn.addSelectionListener(
            new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    openChangesDialog();
                }
            }
        );

        // Action items (inline checkbox editor)
        itemsContainer = new Composite(container, SWT.NONE);
        itemsContainer.setLayout(new GridLayout(1, false));
        itemsContainer.setLayoutData(
            new GridData(SWT.FILL, SWT.FILL, true, false)
        );

        // Details area (hidden by default)
        detailsText = new Text(
            container,
            SWT.MULTI | SWT.WRAP | SWT.READ_ONLY | SWT.V_SCROLL
        );
        GridData dgd = new GridData(SWT.FILL, SWT.FILL, true, false);
        dgd.heightHint = 120;
        detailsText.setLayoutData(dgd);
        detailsText.setVisible(false);
        detailsText.setBackground(backgroundColor);
        detailsText.setForeground(
            Display.getDefault().getSystemColor(SWT.COLOR_DARK_GRAY)
        );
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

    public void setOnAccept(Runnable onAccept) {
        this.onAccept = onAccept;
    }

    public void setOnEdit(Runnable onEdit) {
        this.onEdit = onEdit;
    }

    public void setOnCancel(Runnable onCancel) {
        this.onCancel = onCancel;
    }

    /** Set validation chips above the summary (e.g., warnings). */
    public void setChips(List<String> chips) {
        if (chipsRow == null || chipsRow.isDisposed()) return;
        for (org.eclipse.swt.widgets.Control c : chipsRow.getChildren()) {
            c.dispose();
        }
        if (chips == null || chips.isEmpty()) {
            chipsRow.layout(true, true);
            return;
        }
        int shown = 0;
        final int MAX_CHARS = 80;
        for (String chip : chips) {
            if (chip == null || chip.isBlank()) continue;
            Label lbl = new Label(chipsRow, SWT.BORDER | SWT.WRAP);
            lbl.setBackground(
                Display.getDefault().getSystemColor(SWT.COLOR_INFO_BACKGROUND)
            );
            String text = chip.trim();
            String shortText = text.length() > MAX_CHARS
                ? text.substring(0, MAX_CHARS - 1).trim() + "…"
                : text;
            lbl.setText("⚠ " + shortText);
            lbl.setToolTipText(text);
            // RowLayout respects preferred size; allow natural wrap
            shown++;
            if (shown >= 6) break;
        }
        chipsRow.layout(true, true);

        // Recompute initial container size based on the current viewport
        container.addListener(SWT.Show, ev -> {
            Composite c = (Composite) ev.widget;
            int w = c.getParent().getClientArea().width;
            if (w > 0) {
                containerData.widthHint = computePlanWidth(w);
                c.getParent().layout(true, true);
            }
        });
    }

    private static int computePlanWidth(int availableWidth) {
        // Reserve safe area margins (left+right)
        int safe = 32;
        int usable = Math.max(200, availableWidth - safe);
        int target = (int) Math.round(usable * 0.85);
        int min = 360; // minimum comfortable panel width
        int max = 760; // maximum for readability and aesthetics
        int width = Math.min(Math.max(target, min), max);
        return Math.min(width, usable);
    }

    /**
     * Optional detailed dry-run outcomes for preview.
     */
    public void setDetails(List<String> lines) {
        this.lastDetails = (lines == null)
            ? java.util.List.of()
            : java.util.List.copyOf(lines);
        if (detailsText != null && !detailsText.isDisposed()) {
            String text = (lines == null || lines.isEmpty())
                ? ""
                : lines
                      .stream()
                      .collect(Collectors.joining(System.lineSeparator()));
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
        if (
            plan == null ||
            itemsContainer == null ||
            itemsContainer.isDisposed()
        ) {
            return plan;
        }
        java.util.List<Integer> selected = new java.util.ArrayList<>();
        org.eclipse.swt.widgets.Control[] children =
            itemsContainer.getChildren();
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
        return lines
            .stream()
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
        org.eclipse.swt.widgets.Shell dialog =
            new org.eclipse.swt.widgets.Shell(
                shell,
                SWT.DIALOG_TRIM | SWT.RESIZE | SWT.APPLICATION_MODAL
            );
        dialog.setText("Plan Changes");
        dialog.setLayout(new GridLayout(1, false));
        // Summary header
        org.eclipse.swt.widgets.Label summary =
            new org.eclipse.swt.widgets.Label(dialog, SWT.WRAP);
        summary.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        summary.setText(buildChangeSummary());
        // Full details
        org.eclipse.swt.widgets.Text text = new org.eclipse.swt.widgets.Text(
            dialog,
            SWT.MULTI | SWT.WRAP | SWT.READ_ONLY | SWT.V_SCROLL
        );
        text.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        String content = (lastDetails == null || lastDetails.isEmpty())
            ? "(no changes computed)"
            : lastDetails
                  .stream()
                  .collect(Collectors.joining(System.lineSeparator()));
        text.setText(content);
        org.eclipse.swt.widgets.Button close =
            new org.eclipse.swt.widgets.Button(dialog, SWT.PUSH);
        close.setText("Close");
        close.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
        close.addSelectionListener(
            new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    dialog.close();
                }
            }
        );
        dialog.setSize(640, 420);
        dialog.open();
    }

    private String buildChangeSummary() {
        if (
            lastDetails == null || lastDetails.isEmpty()
        ) return "(no changes computed)";
        int created = 0,
            deleted = 0,
            renamed = 0;
        java.util.Map<String, Integer> createdByType =
            new java.util.HashMap<>();
        for (String line : lastDetails) {
            if (line == null) continue;
            String s = line.trim();
            if (s.startsWith("+ Create ")) {
                created++;
                String type = extractType(s, "+ Create ");
                if (type != null) createdByType.merge(type, 1, Integer::sum);
            } else if (s.startsWith("- Delete ")) {
                deleted++;
            } else if (s.startsWith("~ Rename ")) {
                renamed++;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(
            String.format(
                "Summary: created=%d, deleted=%d, renamed=%d",
                created,
                deleted,
                renamed
            )
        );
        if (!createdByType.isEmpty()) {
            sb.append("\nTop creates: ");
            createdByType
                .entrySet()
                .stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(5)
                .forEach(e ->
                    sb
                        .append(e.getKey())
                        .append("=")
                        .append(e.getValue())
                        .append(" ")
                );
        }
        return sb.toString().trim();
    }

    private String extractType(String line, String prefix) {
        try {
            // Expected format: "+ Create TYPE 'name' (path)"
            String rest = line.substring(prefix.length());
            int sp = rest.indexOf(' ');
            if (sp > 0) {
                return rest.substring(0, sp).trim();
            }
            return null;
        } catch (Exception ex) {
            return null;
        }
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
