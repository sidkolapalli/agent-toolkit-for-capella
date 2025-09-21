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
    private Text diffPreview;
    private Label undoHint;

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

        diffPreview = new Text(diffGroup, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
        GridData diffData = new GridData(SWT.FILL, SWT.FILL, true, true);
        diffData.heightHint = 140;
        diffPreview.setLayoutData(diffData);

        undoHint = new Label(diffGroup, SWT.WRAP);
        undoHint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        undoHint.setText("Tip: the assistant runs the plan in a single EMF transaction. Use Edit → Undo immediately after execution to revert the changes.");
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
        if (dryRun == null || !dryRun.isAvailable()) {
            if (dryRun != null && !dryRun.issues().isEmpty()) {
                diffPreview.setText(String.join(System.lineSeparator(), dryRun.issues()));
            } else {
                diffPreview.setText("Dry run unavailable; plan will execute directly.");
            }
            if (undoHint != null && !undoHint.isDisposed()) {
                undoHint.setText("Undo remains available via Edit → Undo immediately after execution.");
            }
            return;
        }
        if (selected == null || selected.isEmpty()) {
            diffPreview.setText("No structural changes selected.");
            if (undoHint != null && !undoHint.isDisposed()) {
                undoHint.setText("Dry run ready. Undo via Edit → Undo immediately after execution.");
            }
            return;
        }
        Map<ChangeCategory, List<PlanItem>> grouped = selected.stream()
                .collect(Collectors.groupingBy(PlanItem::getCategory, LinkedHashMap::new, Collectors.toList()));
        StringBuilder sb = new StringBuilder();
        long failures = 0;
        for (ChangeCategory category : ChangeCategory.values()) {
            List<PlanItem> items = grouped.get(category);
            if (items == null || items.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(System.lineSeparator());
            }
            sb.append(category.label()).append(':').append(System.lineSeparator());
            for (PlanItem item : items) {
                PlanDryRun.Outcome outcome = dryRun.outcomeFor(item.getIndex());
                sb.append(" • ").append(item.getDiffSummary());
                if (item.requiresConfirmation()) {
                    sb.append(" [requires confirmation]");
                }
                if (outcome != null) {
                    if (outcome.isSuccess()) {
                        sb.append(" ✓");
                    } else {
                        failures++;
                        sb.append(" ⚠ ").append(outcome.getMessage());
                    }
                }
                sb.append(System.lineSeparator());
            }
        }
        if (!dryRun.issues().isEmpty()) {
            sb.append(System.lineSeparator()).append("Issues:").append(System.lineSeparator());
            for (String issue : dryRun.issues()) {
                sb.append(" ! ").append(issue).append(System.lineSeparator());
            }
        }
        if (sb.length() == 0) {
            diffPreview.setText("No structural changes selected.");
        } else {
            diffPreview.setText(sb.toString().stripTrailing());
        }
        if (undoHint != null && !undoHint.isDisposed()) {
            if (failures > 0) {
                undoHint.setText(String.format("Dry run flagged %d issue(s). Review warnings before applying. Undo via Edit → Undo remains available.", failures));
            } else {
                undoHint.setText(String.format("Dry run successful for %d action(s). Undo via Edit → Undo after execution.", selected.size()));
            }
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
