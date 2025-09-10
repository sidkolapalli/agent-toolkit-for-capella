package org.polarsys.capella.ai.preferences;

import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.polarsys.capella.ai.llm.LLMService;

public class LLMPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {
    private Combo provider;
    private Text baseUrl;
    private Text model;
    private Text temperature;
    private Text maxTokens;
    private Text timeoutSec;
    private Text apiKey;
    private Label status;

    @Override
    public void init(IWorkbench workbench) {}

    @Override
    protected Control createContents(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(2, false));

        mkLabel(root, "Provider:");
        provider = new Combo(root, SWT.DROP_DOWN | SWT.READ_ONLY);
        provider.setItems(new String[] {"openai", "anthropic", "azure-openai", "openrouter", "custom"});
        provider.select(indexOf(provider, LLMPreferences.getProvider()));

        mkLabel(root, "API Base URL:");
        baseUrl = new Text(root, SWT.BORDER);
        baseUrl.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        baseUrl.setText(LLMPreferences.getBaseUrl());

        mkLabel(root, "Model:");
        model = new Text(root, SWT.BORDER);
        model.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        model.setText(LLMPreferences.getModel());

        mkLabel(root, "Temperature:");
        temperature = new Text(root, SWT.BORDER);
        temperature.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        temperature.setText(String.valueOf(LLMPreferences.getTemperature()));

        mkLabel(root, "Max Tokens:");
        maxTokens = new Text(root, SWT.BORDER);
        maxTokens.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        maxTokens.setText(String.valueOf(LLMPreferences.getMaxTokens()));

        mkLabel(root, "Timeout (sec):");
        timeoutSec = new Text(root, SWT.BORDER);
        timeoutSec.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        timeoutSec.setText(String.valueOf(LLMPreferences.getTimeoutSec()));

        mkLabel(root, "API Key:");
        apiKey = new Text(root, SWT.BORDER | SWT.PASSWORD);
        apiKey.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        apiKey.setText(LLMPreferences.getApiKey());

        Button test = new Button(root, SWT.PUSH);
        test.setText("Test Connection");
        GridData gd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        gd.horizontalSpan = 2;
        test.setLayoutData(gd);
        status = new Label(root, SWT.WRAP);
        status.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
        status.setText("");

        test.addListener(SWT.Selection, e -> doTest());
        return root;
    }

    private void doTest() {
        try {
            savePrefs(false);
            String msg = LLMService.get().testConnection();
            status.setText("OK: " + msg);
        } catch (Exception ex) {
            status.setText("ERROR: " + ex.getMessage());
        }
        status.getParent().layout();
    }

    @Override
    public boolean performOk() {
        try {
            savePrefs(true);
            return true;
        } catch (Exception e) {
            setErrorMessage(e.getMessage());
            return false;
        }
    }

    private void savePrefs(boolean flush) throws Exception {
        LLMPreferences.setProvider(provider.getText());
        LLMPreferences.setBaseUrl(baseUrl.getText().trim());
        LLMPreferences.setModel(model.getText().trim());
        LLMPreferences.setTemperature(parseDouble(temperature.getText(), 0.2));
        LLMPreferences.setMaxTokens(parseInt(maxTokens.getText(), 2000));
        LLMPreferences.setTimeoutSec(parseInt(timeoutSec.getText(), 60));
        if (!apiKey.getText().trim().isEmpty()) {
            LLMPreferences.setApiKey(apiKey.getText().trim());
        }
        if (flush) LLMPreferences.flush();
    }

    private static int parseInt(String s, int def) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; } }
    private static double parseDouble(String s, double def) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; } }

    private static void mkLabel(Composite parent, String text) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(text);
    }

    private static int indexOf(Combo c, String value) {
        String[] items = c.getItems();
        for (int i=0;i<items.length;i++) if (items[i].equalsIgnoreCase(value)) return i;
        return 0;
    }
}

