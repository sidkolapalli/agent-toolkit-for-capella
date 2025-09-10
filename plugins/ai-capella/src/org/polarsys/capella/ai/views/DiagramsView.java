package org.polarsys.capella.ai.views;

import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.sirius.viewpoint.DRepresentation;
import org.polarsys.capella.ai.browse.DiagramBrowser;
import org.polarsys.capella.ai.session.SessionBridge;
import org.polarsys.capella.ai.tools.DiagramCommands;

public class DiagramsView extends ViewPart {
    private Table table;
    private Button refresh;
    private Button open;
    private Button arrange;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(3, false));

        table = new Table(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 3, 1));
        String[] cols = {"Name", "Type", "UID"};
        for (String c : cols) {
            TableColumn tc = new TableColumn(table, SWT.LEFT);
            tc.setText(c);
            tc.setWidth(240);
        }

        refresh = new Button(parent, SWT.PUSH);
        refresh.setText("Refresh");
        refresh.addListener(SWT.Selection, e -> loadData());

        open = new Button(parent, SWT.PUSH);
        open.setText("Open");
        open.addListener(SWT.Selection, e -> doOpen());

        arrange = new Button(parent, SWT.PUSH);
        arrange.setText("Arrange");
        arrange.addListener(SWT.Selection, e -> doArrange());

        loadData();
    }

    private void loadData() {
        table.removeAll();
        List<DiagramBrowser.DiagramInfo> list = DiagramBrowser.listActive();
        for (DiagramBrowser.DiagramInfo d : list) {
            TableItem it = new TableItem(table, SWT.NONE);
            it.setText(new String[] { d.name, d.type, d.uid });
            it.setData(d);
        }
        for (TableColumn c : table.getColumns()) c.pack();
    }

    private void doOpen() {
        TableItem[] sel = table.getSelection();
        if (sel.length == 0) return;
        DiagramBrowser.DiagramInfo d = (DiagramBrowser.DiagramInfo) sel[0].getData();
        var session = SessionBridge.getActiveSession();
        if (session == null) return;
        DRepresentation rep = DiagramCommands.findRepresentationByUid(session, d.uid);
        if (rep != null) {
            try {
                org.eclipse.sirius.ui.business.api.dialect.DialectUIManager.INSTANCE.openEditor(session, rep, new org.eclipse.core.runtime.NullProgressMonitor());
                DiagramCommands.lastOpened = rep;
            } catch (Exception ignore) {}
        }
    }

    private void doArrange() {
        var session = SessionBridge.getActiveSession();
        if (session == null) return;
        TableItem[] sel = table.getSelection();
        if (sel.length == 0) return;
        DiagramBrowser.DiagramInfo d = (DiagramBrowser.DiagramInfo) sel[0].getData();
        DRepresentation rep = DiagramCommands.findRepresentationByUid(session, d.uid);
        if (rep != null) {
            DiagramCommands.arrange(rep);
        }
    }

    @Override
    public void setFocus() {
        table.setFocus();
    }
}

