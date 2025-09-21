package org.polarsys.capella.ai.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.CheckStateChangedEvent;
import org.eclipse.jface.viewers.CheckboxTableViewer;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbenchCommandConstants;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.IHandlerService;
import org.polarsys.capella.ai.llm.ToolRouter.ChangeCategory;
import org.polarsys.capella.ai.llm.ToolRouter.PlanItem;
import org.polarsys.capella.ai.llm.ToolRouter.ToolPlan;
import org.polarsys.capella.ai.session.PlanDryRun;

/**
 * Rich preview dialog that allows users to granularly review and toggle TOOL_PLAN actions.
 */
public final class PlanEditorDialog extends TitleAreaDialog {

    private final ToolPlan plan;
    private final PlanDryRun.DryRunResult dryRun;
    private ToolPlan filteredPlan;

    private CheckboxTableViewer viewer;
    private Text details;
    private Label selectionSummary;
    private StyledText diffPreview;
    private Label undoHint;
    private Button undoButton;

    public PlanEditorDialog(Shell parentShell, ToolPlan plan, PlanDryRun.DryRunResult dryRun) {
        super(parentShell);
        this.plan = plan;
        this.dryRun = dryRun;
        this.filteredPlan = plan;
    }

    @Override
    public void create() {
        super.create();
        setTitle("Review AI Plan");
        setMessage("Toggle any action you do not want to apply, then confirm the execution.");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        Composite container = new Composite(area, SWT.NONE);
        container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        container.setLayout(new GridLayout(1, false));

        selectionSummary = new Label(container, SWT.WRAP);
        selectionSummary.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        createTable(container);
        createButtonsRow(container);
        createDetailsSection(container);
        createDiffSection(container);

        populatePlan();
        updateSelectionSummary();
        selectFirstRow();
        return area;
    }

    private void createTable(Composite parent) {
        viewer = CheckboxTableViewer.newCheckList(parent, SWT.BORDER | SWT.FULL_SELECTION);
        Table table = viewer.getTable();
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        table.setHeaderVisible(true);
        table.setLinesVisible(true);

        viewer.setContentProvider(ArrayContentProvider.getInstance());

        TableViewerColumn stepColumn = new TableViewerColumn(viewer, SWT.NONE);
        stepColumn.getColumn().setText("#");
        stepColumn.getColumn().setWidth(40);
        stepColumn.setLabelProvider(new PlanLabelProvider(item -> Integer.toString(item.getIndex() + 1)));

        TableViewerColumn typeColumn = new TableViewerColumn(viewer, SWT.NONE);
        typeColumn.getColumn().setText("Type");
        typeColumn.getColumn().setWidth(150);
        typeColumn.setLabelProvider(new PlanLabelProvider(item -> formatType(item)));

        TableViewerColumn layerColumn = new TableViewerColumn(viewer, SWT.NONE);
        layerColumn.getColumn().setText("Layer");
        layerColumn.getColumn().setWidth(80);
        layerColumn.setLabelProvider(new PlanLabelProvider(item -> defaultString(item.getLayer())));

        TableViewerColumn descriptionColumn = new TableViewerColumn(viewer, SWT.NONE);
        descriptionColumn.getColumn().setText("Description");
        descriptionColumn.getColumn().setWidth(420);
        descriptionColumn.setLabelProvider(new PlanLabelProvider(PlanItem::getDescription));

        viewer.addCheckStateListener(this::handleCheckChanged);
        viewer.addSelectionChangedListener(this::handleSelectionChanged);
    }

    private static String formatType(PlanItem item) {
        return prettifyTypeId(item.getTypeId());
    }

    private static String prettifyTypeId(String typeId) {
        String lower = typeId.toLowerCase().replace('_', ' ');
        return lower.isEmpty() ? lower : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }


    private void createButtonsRow(Composite parent) {
        Composite row = new Composite(parent, SWT.NONE);
        row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout layout = new GridLayout(3, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        row.setLayout(layout);

        Button selectAll = new Button(row, SWT.PUSH);
        selectAll.setText("Select all");
        selectAll.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                viewer.setAllChecked(true);
                updateSelectionSummary();
            }
        });

        Button clearAll = new Button(row, SWT.PUSH);
        clearAll.setText("Clear all");
        clearAll.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                viewer.setAllChecked(false);
                updateSelectionSummary();
            }
        });

        Button invert = new Button(row, SWT.PUSH);
        invert.setText("Invert selection");
        invert.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                Table table = viewer.getTable();
                for (TableItem item : table.getItems()) {
                    Object element = item.getData();
                    viewer.setChecked(element, !viewer.getChecked(element));
                }
                updateSelectionSummary();
            }
        });
    }

    private void createDetailsSection(Composite parent) {
        Group detailsGroup = new Group(parent, SWT.NONE);
        detailsGroup.setText("Selected action");
        detailsGroup.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));
        detailsGroup.setLayout(new GridLayout(1, false));

        details = new Text(detailsGroup, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
        GridData data = new GridData(SWT.FILL, SWT.FILL, true, true);
        data.heightHint = 140;
        details.setLayoutData(data);
    }

    private void createDiffSection(Composite parent) {
        Group diffGroup = new Group(parent, SWT.NONE);
        diffGroup.setText("Diff preview");
        diffGroup.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));
        diffGroup.setLayout(new GridLayout(1, false));

        diffPreview = new StyledText(diffGroup, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
        diffPreview.setEditable(false);
        GridData diffData = new GridData(SWT.FILL, SWT.FILL, true, true);
        diffData.heightHint = 140;
        diffPreview.setLayoutData(diffData);

        Composite undoRow = new Composite(diffGroup, SWT.NONE);
        undoRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout undoLayout = new GridLayout(2, false);
        undoLayout.marginWidth = 0;
        undoLayout.marginHeight = 0;
        undoLayout.horizontalSpacing = 8;
        undoRow.setLayout(undoLayout);

        undoButton = new Button(undoRow, SWT.PUSH);
        undoButton.setText("Undo last plan");
        undoButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                performUndo();
            }
        });

        undoHint = new Label(undoRow, SWT.WRAP);
        undoHint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        undoHint.setText("Tip: the assistant runs the plan in a single EMF transaction. Undo immediately after execution if needed.");
    }

    private void populatePlan() {
        List<PlanItem> items = plan.items();
        viewer.setInput(items);
        viewer.setAllChecked(true);
        for (PlanItem item : items) {
            if (item.requiresConfirmation()) {
                viewer.setChecked(item, false);
            }
        }
    }

    private void selectFirstRow() {
        Table table = viewer.getTable();
        if (table.getItemCount() > 0) {
            table.setSelection(0);
            updateDetails((PlanItem) table.getItem(0).getData());
        } else {
            updateDetails(null);
        }
    }

    private void handleSelectionChanged(SelectionChangedEvent event) {
        IStructuredSelection selection = (IStructuredSelection) event.getSelection();
        PlanItem item = (PlanItem) selection.getFirstElement();
        updateDetails(item);
    }

    private void handleCheckChanged(CheckStateChangedEvent event) {
        updateSelectionSummary();
    }

    private List<PlanItem> checkedItems() {
        Object[] checked = viewer.getCheckedElements();
        List<PlanItem> selected = new ArrayList<>(checked.length);
        for (Object obj : checked) {
            selected.add((PlanItem) obj);
        }
        return selected;
    }

    private void updateSelectionSummary() {
        List<PlanItem> selected = checkedItems();
        int total = plan.size();
        if (selected.isEmpty()) {
            selectionSummary.setText(String.format("No actions selected (0 of %d).", total));
        } else {
            Map<String, Long> typeCounts = selected.stream()
                    .collect(Collectors.groupingBy(PlanItem::getTypeId, LinkedHashMap::new, Collectors.counting()));
            String summary = typeCounts.entrySet().stream()
                    .map(e -> prettifyTypeId(e.getKey()) + " × " + e.getValue())
                    .collect(Collectors.joining(", "));
            selectionSummary.setText(String.format("Selected %d of %d actions (%s)", selected.size(), total, summary));
        }
        updateDiffPreview(selected);
        selectionSummary.getParent().layout();
    }

    private void updateDiffPreview(List<PlanItem> selected) {
        if (diffPreview == null || diffPreview.isDisposed()) {
            return;
        }
        if (undoButton != null && !undoButton.isDisposed()) {
            undoButton.setEnabled(selected != null && !selected.isEmpty());
        }
        if (dryRun == null || !dryRun.isAvailable()) {
            String message = (dryRun != null && !dryRun.issues().isEmpty())
                    ? String.join(System.lineSeparator(), dryRun.issues())
                    : "Dry run unavailable; plan will execute directly.";
            applyDiffText(message, List.of());
            if (undoHint != null && !undoHint.isDisposed()) {
                undoHint.setText("Undo remains available via Edit → Undo immediately after execution.");
            }
            return;
        }
        if (selected == null || selected.isEmpty()) {
            applyDiffText("No structural changes selected.", List.of());
            if (undoHint != null && !undoHint.isDisposed()) {
                undoHint.setText("Dry run ready. Undo via Edit → Undo immediately after execution.");
            }
            return;
        }
        Map<ChangeCategory, List<PlanItem>> grouped = selected.stream()
                .collect(Collectors.groupingBy(PlanItem::getCategory, LinkedHashMap::new, Collectors.toList()));
        StringBuilder buffer = new StringBuilder();
        List<StyleRange> styles = new ArrayList<>();
        long failures = 0;
        org.eclipse.swt.graphics.Color createColor = diffPreview.getDisplay().getSystemColor(SWT.COLOR_DARK_GREEN);
        org.eclipse.swt.graphics.Color updateColor = diffPreview.getDisplay().getSystemColor(SWT.COLOR_DARK_BLUE);
        org.eclipse.swt.graphics.Color deleteColor = diffPreview.getDisplay().getSystemColor(SWT.COLOR_DARK_RED);
        org.eclipse.swt.graphics.Color warnColor = diffPreview.getDisplay().getSystemColor(SWT.COLOR_DARK_YELLOW);

        for (ChangeCategory category : ChangeCategory.values()) {
            List<PlanItem> items = grouped.get(category);
            if (items == null || items.isEmpty()) {
                continue;
            }
            if (buffer.length() > 0) {
                buffer.append(System.lineSeparator());
            }
            appendStyledLine(buffer, styles, category.label() + ':', diffPreview.getDisplay().getSystemColor(SWT.COLOR_WIDGET_DARK_SHADOW));
            buffer.append(System.lineSeparator());
            ChangeColors colors = changeColors(category, createColor, updateColor, deleteColor);
            for (PlanItem item : items) {
                PlanDryRun.Outcome outcome = dryRun.outcomeFor(item.getIndex());
                String prefix = changePrefix(category);
                appendStyledLine(buffer, styles, " " + prefix + " " + item.getDiffSummary(), colors.primary);
                buffer.append(System.lineSeparator());
                if (outcome != null) {
                    if (!outcome.isSuccess()) {
                        failures++;
                        appendStyledLine(buffer, styles, "    ⚠ " + outcome.getMessage(), warnColor);
                        buffer.append(System.lineSeparator());
                    }
                    for (String detail : outcome.getDetails()) {
                        appendStyledLine(buffer, styles, "    • " + detail, colors.secondary);
                        buffer.append(System.lineSeparator());
                    }
                }
            }
        }
        if (!dryRun.issues().isEmpty()) {
            appendStyledLine(buffer, styles, "Issues:", warnColor);
            buffer.append(System.lineSeparator());
            for (String issue : dryRun.issues()) {
                appendStyledLine(buffer, styles, " ! " + issue, warnColor);
                buffer.append(System.lineSeparator());
            }
        }
        String content = buffer.length() == 0 ? "No structural changes selected." : buffer.toString().stripTrailing();
        applyDiffText(content, styles);
        if (undoHint != null && !undoHint.isDisposed()) {
            if (failures > 0) {
                undoHint.setText(String.format("Dry run flagged %d issue(s). Review warnings before applying. Undo via Edit → Undo remains available.", failures));
            } else {
                undoHint.setText(String.format("Dry run successful for %d action(s). Undo via Edit → Undo after execution.", selected.size()));
            }
        }
    }

    private static ChangeColors changeColors(ChangeCategory category,
            org.eclipse.swt.graphics.Color createColor,
            org.eclipse.swt.graphics.Color updateColor,
            org.eclipse.swt.graphics.Color deleteColor) {
        return switch (category) {
            case CREATE -> new ChangeColors(createColor, createColor);
            case UPDATE -> new ChangeColors(updateColor, updateColor);
            case DELETE -> new ChangeColors(deleteColor, deleteColor);
            default -> new ChangeColors(null, null);
        };
    }

    private static String changePrefix(ChangeCategory category) {
        return switch (category) {
            case CREATE -> "[+]";
            case UPDATE -> "[~]";
            case DELETE -> "[-]";
            default -> "[•]";
        };
    }

    private void applyDiffText(String text, List<StyleRange> styles) {
        diffPreview.setRedraw(false);
        diffPreview.setText(text);
        if (styles != null && !styles.isEmpty()) {
            diffPreview.setStyleRanges(styles.toArray(new StyleRange[0]));
        } else {
            diffPreview.setStyleRanges(new StyleRange[0]);
        }
        diffPreview.setRedraw(true);
    }

    private void appendStyledLine(StringBuilder buffer, List<StyleRange> styles, String line,
            org.eclipse.swt.graphics.Color color) {
        int start = buffer.length();
        buffer.append(line);
        StyleRange range = new StyleRange();
        range.start = start;
        range.length = line.length();
        range.foreground = color;
        styles.add(range);
    }

    private void performUndo() {
        try {
            IHandlerService handlerService = PlatformUI.getWorkbench().getService(IHandlerService.class);
            if (handlerService != null) {
                handlerService.executeCommand(IWorkbenchCommandConstants.EDIT_UNDO, null);
                return;
            }
        } catch (Exception ex) {
            // fall through to message dialog
        }
        MessageDialog.openInformation(getShell(), "Undo unavailable",
                "Undo the plan via Edit → Undo immediately after closing this dialog.");
    }

    private static final class ChangeColors {
        final org.eclipse.swt.graphics.Color primary;
        final org.eclipse.swt.graphics.Color secondary;

        ChangeColors(org.eclipse.swt.graphics.Color primary, org.eclipse.swt.graphics.Color secondary) {
            this.primary = primary;
            this.secondary = secondary;
        }
    }

    private void updateDetails(PlanItem item) {
        if (item == null) {
            details.setText("No action selected.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(item.getDescription()).append(System.lineSeparator());
        sb.append("Type: ").append(formatType(item)).append(System.lineSeparator());
        if (item.getLayer() != null) {
            sb.append("Layer: ").append(item.getLayer()).append(System.lineSeparator());
        }
        if (item.getTargetLayer() != null && !item.getTargetLayer().isBlank() && (item.getLayer() == null || !item.getLayer().equals(item.getTargetLayer()))) {
            sb.append("Target layer: ").append(item.getTargetLayer()).append(System.lineSeparator());
        }
        if (item.getName() != null) {
            sb.append("Name: ").append(item.getName()).append(System.lineSeparator());
        }
        if (item.getSourceName() != null || item.getTargetName() != null) {
            sb.append("Source → Target: ").append(defaultString(item.getSourceName())).append(" → ")
                    .append(defaultString(item.getTargetName())).append(System.lineSeparator());
        }
        if (item.getLogicalName() != null) {
            sb.append("Logical: ").append(item.getLogicalName()).append(System.lineSeparator());
        }
        if (item.getPhysicalName() != null) {
            sb.append("Physical: ").append(item.getPhysicalName()).append(System.lineSeparator());
        }
        if (item.getChainName() != null) {
            sb.append("Chain: ").append(item.getChainName()).append(System.lineSeparator());
        }
        if (item.getFunctionName() != null) {
            sb.append("Function: ").append(item.getFunctionName());
            if (item.getFunctionClass() != null && !item.getFunctionClass().isBlank()) {
                sb.append(" (class: ").append(item.getFunctionClass()).append(')');
            }
            sb.append(System.lineSeparator());
        }
        if (item.getExchangeName() != null) {
            sb.append("Exchange: ").append(item.getExchangeName()).append(System.lineSeparator());
        }
        if (item.getScenarioName() != null) {
            sb.append("Scenario: ").append(item.getScenarioName()).append(System.lineSeparator());
        }
        String containerName = item.getContainerName();
        String containerClass = item.getContainerClass();
        if ((containerName != null && !containerName.isBlank()) || (containerClass != null && !containerClass.isBlank())) {
            sb.append("Container: ");
            if (containerName != null && !containerName.isBlank()) {
                sb.append(containerName);
                if (containerClass != null && !containerClass.isBlank()) {
                    sb.append(" (").append(containerClass).append(')');
                }
            } else {
                sb.append(containerClass);
            }
            sb.append(System.lineSeparator());
        }
        if (item.getRequirementType() != null) {
            sb.append("Requirement type: ").append(item.getRequirementType()).append(System.lineSeparator());
        }
        if (item.getRequirementTargetClass() != null) {
            sb.append("Target class: ").append(item.getRequirementTargetClass()).append(System.lineSeparator());
        }
        if (item.getRequirementStatus() != null && !item.getRequirementStatus().isBlank()) {
            sb.append("Status: ").append(item.getRequirementStatus()).append(System.lineSeparator());
        }
        if (item.getRequirementNotes() != null && !item.getRequirementNotes().isBlank()) {
            sb.append("Notes: ").append(item.getRequirementNotes()).append(System.lineSeparator());
        }
        if (item.getRenameItems() != null && !item.getRenameItems().isEmpty()) {
            sb.append("Renames:").append(System.lineSeparator());
            for (var rename : item.getRenameItems()) {
                sb.append("  - ").append(rename.from).append(" → ").append(rename.to).append(System.lineSeparator());
            }
        }
        if (item.getItemNames() != null && !item.getItemNames().isEmpty() && item.getRenameItems().isEmpty()) {
            sb.append("Items: ").append(String.join(", ", item.getItemNames())).append(System.lineSeparator());
        }
        if (item.getMappingItems() != null && !item.getMappingItems().isEmpty()) {
            sb.append("Mappings:").append(System.lineSeparator());
            for (var map : item.getMappingItems()) {
                sb.append("  - ").append(map.source).append(" → ").append(map.target).append(System.lineSeparator());
            }
        }
        if (item.getExistingName() != null) {
            sb.append("Existing: ").append(item.getExistingName()).append(System.lineSeparator());
        }
        if (item.getNewName() != null) {
            sb.append("New: ").append(item.getNewName()).append(System.lineSeparator());
        }
        if (item.getElementClass() != null) {
            sb.append("Element class: ").append(item.getElementClass()).append(System.lineSeparator());
        }
        if (item.getDiagramType() != null) {
            sb.append("Diagram: ").append(item.getDiagramType()).append(System.lineSeparator());
        }
        if (item.includeChildren()) {
            sb.append("Includes hierarchy: yes").append(System.lineSeparator());
        }
        if (!item.updateAttributes()) {
            sb.append("Sync mode: trace only").append(System.lineSeparator());
        }
        if (item.requiresConfirmation()) {
            sb.append("Confirmation required: enable this action explicitly before applying.").append(System.lineSeparator());
        }
        details.setText(sb.toString());
    }

    private static String defaultString(String value) {
        return value == null ? "" : value;
    }

    @Override
    protected void okPressed() {
        Object[] checked = viewer.getCheckedElements();
        if (checked.length == 0) {
            MessageDialog.openInformation(getShell(), "No actions selected", "Select at least one action before applying the plan.");
            return;
        }
        List<Integer> indices = new ArrayList<>(checked.length);
        for (Object obj : checked) {
            indices.add(((PlanItem) obj).getIndex());
        }
        filteredPlan = plan.filterByIndex(indices);
        super.okPressed();
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
        createButton(parent, IDialogConstants.OK_ID, "Apply selected", true);
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    public ToolPlan getFilteredPlan() {
        return filteredPlan;
    }

    private interface PlanLabelFormatter {
        String format(PlanItem item);
    }

    private static final class PlanLabelProvider extends org.eclipse.jface.viewers.ColumnLabelProvider {
        private final PlanLabelFormatter formatter;

        private PlanLabelProvider(PlanLabelFormatter formatter) {
            this.formatter = formatter;
        }

        @Override
        public String getText(Object element) {
            return formatter.format((PlanItem) element);
        }
    }
}
