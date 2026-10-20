package org.polarsys.capella.ai.views;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Simple inspector for ~/.capella-ai/logs JSONL telemetry files. */
public class LogInspectorView extends ViewPart {

    public static final String ID = "org.polarsys.capella.ai.logs";

    private Text filterId;
    private Text filterEvent;
    private Text filterText;
    private Table table;

    @Override
    public void createPartControl(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(6, false));

        new Label(root, SWT.NONE).setText("Correlation id:");
        filterId = new Text(root, SWT.BORDER);
        filterId.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        new Label(root, SWT.NONE).setText("Event:");
        filterEvent = new Text(root, SWT.BORDER);
        filterEvent.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        new Label(root, SWT.NONE).setText("Text contains:");
        filterText = new Text(root, SWT.BORDER);
        filterText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button refresh = new Button(root, SWT.PUSH);
        refresh.setText("Refresh");
        GridData gd = new GridData(SWT.RIGHT, SWT.CENTER, false, false);
        gd.horizontalSpan = 6;
        refresh.setLayoutData(gd);
        refresh.addListener(SWT.Selection, e -> reload());

        table = new Table(root, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        GridData td = new GridData(SWT.FILL, SWT.FILL, true, true);
        td.horizontalSpan = 6;
        table.setLayoutData(td);

        String[] cols = {"ts", "id", "event", "desc", "details"};
        for (String c : cols) {
            TableColumn col = new TableColumn(table, SWT.LEFT);
            col.setText(c);
            col.setWidth(180);
        }

        reload();
    }

    private void reload() {
        table.removeAll();
        String id = filterId.getText() != null ? filterId.getText().trim() : "";
        String ev = filterEvent.getText() != null ? filterEvent.getText().trim() : "";
        String tx = filterText.getText() != null ? filterText.getText().trim() : "";
        for (Path p : listLatestLogs()) {
            readJsonl(p, id, ev, tx).forEach(this::addRow);
        }
        for (TableColumn c : table.getColumns()) c.pack();
    }

    private void addRow(Map<String, String> m) {
        TableItem it = new TableItem(table, SWT.NONE);
        it.setText(0, value(m, "ts"));
        it.setText(1, value(m, "id"));
        it.setText(2, value(m, "event"));
        it.setText(3, value(m, "desc"));
        it.setText(4, value(m, "details"));
    }

    private String value(Map<String, String> m, String k) {
        String v = m.get(k);
        if (v != null) return v;
        // fallback to common keys
        if ("desc".equals(k)) return m.getOrDefault("actions", m.getOrDefault("context", ""));
        return "";
    }

    private List<Path> listLatestLogs() {
        List<Path> out = new ArrayList<>();
        try {
            Path dir = Paths.get(System.getProperty("user.home"), ".capella-ai", "logs");
            if (!Files.exists(dir)) return out;
            try (var s = Files.list(dir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                        .sorted(
                                (a, b) ->
                                        b.getFileName()
                                                .toString()
                                                .compareTo(a.getFileName().toString()))
                        .limit(5)
                        .forEach(out::add);
            }
        } catch (Exception ex) {
            System.err.println("[LogInspectorView] Error listing log files: " + ex.getMessage());
        }
        return out;
    }

    private List<Map<String, String>> readJsonl(
            Path p, String idFilter, String eventFilter, String textFilter) {
        List<Map<String, String>> rows = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                Map<String, String> m = parse(line);
                if (m == null) continue;
                if (!idFilter.isBlank() && !m.getOrDefault("id", "").contains(idFilter)) continue;
                if (!eventFilter.isBlank() && !m.getOrDefault("event", "").contains(eventFilter))
                    continue;
                if (!textFilter.isBlank() && !line.contains(textFilter)) continue;
                rows.add(m);
            }
        } catch (IOException ex) {
            System.err.println("[LogInspectorView] Error reading log file: " + ex.getMessage());
        }
        return rows;
    }

    // Minimal JSON field extractor for flat {"k":"v",...}; tolerates escaped quotes
    private static final Pattern FIELD =
            Pattern.compile("\\\"([^\\\\\"]+)\\\":\\\"([^\\\\\"]*)\\\"");

    private Map<String, String> parse(String json) {
        try {
            java.util.HashMap<String, String> m = new java.util.HashMap<>();
            Matcher matcher = FIELD.matcher(json);
            while (matcher.find()) {
                String k = matcher.group(1);
                String v = matcher.group(2);
                m.put(k, unescape(v));
            }
            if (!m.containsKey("ts")) {
                m.put("ts", Instant.now().toString());
            }
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    private String unescape(String s) {
        return s.replace("\\\"", "\"")
                .replace("\\n", " ")
                .replace("\\r", " ")
                .replace("\\t", " ")
                .replace("\\\\", "\\");
    }

    @Override
    public void setFocus() {
        if (table != null && !table.isDisposed()) table.setFocus();
    }
}
