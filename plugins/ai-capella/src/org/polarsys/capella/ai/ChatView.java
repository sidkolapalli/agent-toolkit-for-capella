package org.polarsys.capella.ai;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;

public class ChatView extends ViewPart {
    private Text chatLog;
    private Text prompt;
    private Button send;
    private Button listDiagrams;
    private Button createLab;
    private Button importDrawio;
    private Button useLlm;
    private Button enableActions;
    private Button explainModel;
    private Text elementUuid;
    private Button addElement;
    private Text newName;
    private Button renameElement;
    private Button deleteElement;
    private Text newElementName;
    private Button createLogicalComponent;
    private Button createLogicalFunction;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(2, false));

        chatLog = new Text(parent, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.READ_ONLY | SWT.WRAP);
        chatLog.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1));

        prompt = new Text(parent, SWT.BORDER);
        prompt.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        send = new Button(parent, SWT.PUSH);
        send.setText("Send");
        send.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));

        send.addListener(SWT.Selection, e -> onSend());

        // Controls row 2: actions
        listDiagrams = new Button(parent, SWT.PUSH);
        listDiagrams.setText("List Diagrams");
        listDiagrams.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        listDiagrams.addListener(SWT.Selection, e -> onListDiagrams());

        createLab = new Button(parent, SWT.PUSH);
        createLab.setText("Create LAB (LA)");
        createLab.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        createLab.addListener(SWT.Selection, e -> onCreateLab());

        importDrawio = new Button(parent, SWT.PUSH);
        importDrawio.setText("Import draw.io → Capella");
        importDrawio.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        importDrawio.addListener(SWT.Selection, e -> onImportDrawio());

        useLlm = new Button(parent, SWT.CHECK);
        useLlm.setText("Use LLM Mapping");
        GridData gdu = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        gdu.horizontalSpan = 2;
        useLlm.setLayoutData(gdu);

        // Tool actions toggle and Explain Model button
        enableActions = new Button(parent, SWT.CHECK);
        enableActions.setText("Enable Tool Actions (apply plans)");
        GridData gda = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        gda.horizontalSpan = 2; enableActions.setLayoutData(gda);

        explainModel = new Button(parent, SWT.PUSH);
        explainModel.setText("Explain Model");
        explainModel.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        explainModel.addListener(SWT.Selection, e -> onExplainModel());

        // Row 3: add element by UUID
        Label lbl = new Label(parent, SWT.NONE);
        lbl.setText("Element UUID:");
        lbl.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        elementUuid = new Text(parent, SWT.BORDER);
        elementUuid.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        addElement = new Button(parent, SWT.PUSH);
        addElement.setText("Add Element to Current Diagram");
        GridData gd = new GridData(SWT.RIGHT, SWT.CENTER, false, false);
        gd.horizontalSpan = 2;
        addElement.setLayoutData(gd);
        addElement.addListener(SWT.Selection, e -> onAddElement());

        // Row 4: rename/delete by UUID
        Label lbl2 = new Label(parent, SWT.NONE);
        lbl2.setText("New Name:");
        lbl2.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        newName = new Text(parent, SWT.BORDER);
        newName.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Composite row = new Composite(parent, SWT.NONE);
        row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        GridLayout rl = new GridLayout(2, true);
        rl.marginWidth = 0; rl.marginHeight = 0; row.setLayout(rl);

        renameElement = new Button(row, SWT.PUSH);
        renameElement.setText("Rename by UUID");
        renameElement.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        renameElement.addListener(SWT.Selection, e -> onRenameElement());

        deleteElement = new Button(row, SWT.PUSH);
        deleteElement.setText("Delete by UUID");
        deleteElement.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        deleteElement.addListener(SWT.Selection, e -> onDeleteElement());

        // Row 5: Create elements by name (LA)
        Label lbl3 = new Label(parent, SWT.NONE);
        lbl3.setText("New Element Name:");
        lbl3.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        newElementName = new Text(parent, SWT.BORDER);
        newElementName.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Composite row2 = new Composite(parent, SWT.NONE);
        row2.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        GridLayout rl2 = new GridLayout(2, true);
        rl2.marginWidth = 0; rl2.marginHeight = 0; row2.setLayout(rl2);

        createLogicalComponent = new Button(row2, SWT.PUSH);
        createLogicalComponent.setText("Create Logical Component");
        createLogicalComponent.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        createLogicalComponent.addListener(SWT.Selection, e -> onCreateElement("LogicalComponent"));

        createLogicalFunction = new Button(row2, SWT.PUSH);
        createLogicalFunction.setText("Create Logical Function");
        createLogicalFunction.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        createLogicalFunction.addListener(SWT.Selection, e -> onCreateElement("LogicalFunction"));
    }

    private void onSend() {
        String text = prompt.getText().trim();
        if (text.isEmpty()) return;
        append("You: " + text);
        prompt.setText("");
        // Run LLM call off the UI thread
        org.eclipse.core.runtime.jobs.Job job = new org.eclipse.core.runtime.jobs.Job("LLM Request") {
            @Override
            protected org.eclipse.core.runtime.IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor) {
                try {
                    var res = org.polarsys.capella.ai.llm.ToolRouter.handleChat(text, enableActions.getSelection());
                    String reply = (res.reply != null ? res.reply : "");
                    String suffix = res.actionsApplied > 0 ? ("\n[Applied " + res.actionsApplied + " action(s)]") : "";
                    String finalReply = reply + suffix;
                    org.eclipse.swt.widgets.Display.getDefault().asyncExec(() -> append("Assistant: " + finalReply));
                } catch (Exception ex) {
                    org.eclipse.swt.widgets.Display.getDefault().asyncExec(() -> append("Assistant ERROR: " + ex.getMessage()));
                }
                return org.eclipse.core.runtime.Status.OK_STATUS;
            }
        };
        job.setUser(false);
        job.schedule();
    }

    private void onExplainModel() {
        try {
            String summary = org.polarsys.capella.ai.model.ModelSummary.summarize();
            append(summary);
        } catch (Exception ex) {
            append("Explain failed: " + ex.getMessage());
        }
    }

    private void append(String line) {
        chatLog.append(line + System.lineSeparator());
    }

    private void onListDiagrams() {
        try {
            var list = org.polarsys.capella.ai.browse.DiagramBrowser.listActive();
            if (list.isEmpty()) {
                append("No diagrams found in active session.");
                return;
            }
            append("Active diagrams:");
            for (var d : list) append(" - " + d.toString());
        } catch (Exception ex) {
            append("Error listing diagrams: " + ex.getMessage());
        }
    }

    private void onCreateLab() {
        try {
            var session = org.polarsys.capella.ai.session.SessionBridge.getActiveSession();
            if (session == null) { append("No active session."); return; }
            var target = org.polarsys.capella.ai.model.ArchitectureLocator.findTargetElement(session, "LA");
            if (target == null) { append("LA architecture not found."); return; }
            String desc = org.polarsys.capella.ai.tools.DiagramMappings.descriptionFor("LA", "LAB");
            var rep = org.polarsys.capella.ai.tools.DiagramCommands.createAndOpenDiagram(target, desc, "[LAB] New Logical Diagram");
            if (rep != null) append("Created and opened diagram: " + rep.getName());
            else append("Failed to create diagram.");
        } catch (Exception ex) {
            append("Create LAB failed: " + ex.getMessage());
        }
    }

    private void onImportDrawio() {
        try {
            org.eclipse.swt.widgets.FileDialog fd = new org.eclipse.swt.widgets.FileDialog(getSite().getShell(), SWT.OPEN);
            fd.setFilterExtensions(new String[] {"*.drawio", "*.*"});
            String path = fd.open();
            if (path == null) return;
            append("Importing draw.io: " + path);
            boolean use = useLlm.getSelection();
            org.eclipse.core.runtime.jobs.Job job = new org.eclipse.core.runtime.jobs.Job("Import draw.io") {
                @Override
                protected org.eclipse.core.runtime.IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor) {
                    try {
                        var res = org.polarsys.capella.ai.importer.DrawioImporter.importLogical(new java.io.File(path), use);
                        org.eclipse.swt.widgets.Display.getDefault().asyncExec(() -> {
                            append("Imported nodes=" + res.nodesCreated + ", edges=" + res.edgesProcessed + ". Diagram=" + res.diagramName);
                        });
                    } catch (Exception ex) {
                        org.eclipse.swt.widgets.Display.getDefault().asyncExec(() -> append("Import ERROR: " + ex.getMessage()));
                    }
                    return org.eclipse.core.runtime.Status.OK_STATUS;
                }
            };
            job.schedule();
        } catch (Exception ex) {
            append("Import failed: " + ex.getMessage());
        }
    }

    private void onAddElement() {
        String id = elementUuid.getText().trim();
        if (id.isEmpty()) { append("Enter an element UUID."); return; }
        try {
            var session = org.polarsys.capella.ai.session.SessionBridge.getActiveSession();
            if (session == null) { append("No active session."); return; }
            var rep = org.polarsys.capella.ai.tools.DiagramCommands.lastOpened;
            if (rep == null) { append("No current diagram (use Create LAB or open a diagram first)."); return; }
            int resolved = org.polarsys.capella.ai.tools.DiagramCommands.addElementsByUuid(session, rep, java.util.List.of(id));
            org.polarsys.capella.ai.tools.DiagramCommands.arrange(rep);
            append("Add element requested (resolved=" + resolved + ") and diagram refreshed.");
        } catch (Exception ex) {
            append("Add element failed: " + ex.getMessage());
        }
    }

    private void onRenameElement() {
        String id = elementUuid.getText().trim();
        String nn = newName.getText().trim();
        if (id.isEmpty() || nn.isEmpty()) { append("Enter element UUID and New Name."); return; }
        boolean ok = org.polarsys.capella.ai.model.ModelOps.renameByUuid(id, nn);
        append(ok ? "Renamed element." : "Rename failed.");
    }

    private void onDeleteElement() {
        String id = elementUuid.getText().trim();
        if (id.isEmpty()) { append("Enter element UUID."); return; }
        boolean ok = org.polarsys.capella.ai.model.ModelOps.deleteByUuid(id);
        append(ok ? "Deleted element." : "Delete failed.");
    }

    private void onCreateElement(String simpleEClass) {
        String nm = newElementName.getText().trim();
        if (nm.isEmpty()) { append("Enter New Element Name."); return; }
        try {
            var session = org.polarsys.capella.ai.session.SessionBridge.getActiveSession();
            if (session == null) { append("No active session."); return; }
            var la = org.polarsys.capella.ai.model.ArchitectureLocator.findTargetElement(session, "LA");
            if (la == null) { append("LA architecture not found."); return; }
            var created = org.polarsys.capella.ai.model.ElementFactory.createUnder(la, simpleEClass, nm);
            if (created != null) {
                append("Created " + simpleEClass + ": " + nm);
                var rep = org.polarsys.capella.ai.tools.DiagramCommands.lastOpened;
                if (rep != null) {
                    org.polarsys.capella.ai.tools.DiagramCommands.addElementsByUuid(
                        session, rep, java.util.List.of(getUuid(created))
                    );
                    org.polarsys.capella.ai.tools.DiagramCommands.arrange(rep);
                    append("Revealed new element on current diagram and arranged.");
                }
            } else {
                append("Creation failed for " + simpleEClass);
            }
        } catch (Exception ex) {
            append("Create element failed: " + ex.getMessage());
        }
    }

    private String getUuid(Object eo) {
        try {
            if (eo instanceof org.eclipse.emf.ecore.EObject) {
                org.eclipse.emf.ecore.EObject o = (org.eclipse.emf.ecore.EObject) eo;
                var f = o.eClass().getEStructuralFeature("uuid");
                if (f != null) {
                    Object v = o.eGet(f);
                    return v != null ? String.valueOf(v) : String.valueOf(o.hashCode());
                }
            }
        } catch (Exception ignore) {}
        return String.valueOf(eo.hashCode());
    }

    @Override
    public void setFocus() {
        prompt.setFocus();
    }
}
