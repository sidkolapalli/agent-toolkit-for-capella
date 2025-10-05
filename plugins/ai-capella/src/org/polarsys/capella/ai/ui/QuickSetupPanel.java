package org.polarsys.capella.ai.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.polarsys.capella.ai.preferences.LLMPreferences;

/** Minimal inline configuration card to quickly start chatting. */
public class QuickSetupPanel {
    private final Composite container;
    private final Combo providerCombo;
    private final Combo modelCombo;
    private final Text apiKeyText;
    private Runnable onSave;
    private Runnable onTest;

    public QuickSetupPanel(Composite parent) {
        container = new Composite(parent, SWT.BORDER);
        container.setLayout(new GridLayout(4, false));
        container.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label title = new Label(container, SWT.NONE);
        title.setText("🔧 Quick Setup");
        GridData tgd = new GridData(SWT.LEFT, SWT.CENTER, false, false, 4, 1);
        title.setLayoutData(tgd);

        new Label(container, SWT.NONE).setText("Provider");
        providerCombo = new Combo(container, SWT.DROP_DOWN | SWT.READ_ONLY);
        providerCombo.setItems(new String[]{"OpenAI", "Anthropic", "Azure", "Custom"});
        providerCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(container, SWT.NONE).setText("Model");
        modelCombo = new Combo(container, SWT.DROP_DOWN);
        modelCombo.setItems(new String[]{"gpt-4o-mini", "gpt-4o", "gpt-5", "claude-3-haiku", "claude-3-sonnet"});
        modelCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(container, SWT.NONE).setText("API Key");
        apiKeyText = new Text(container, SWT.BORDER | SWT.PASSWORD);
        apiKeyText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));

        Button test = new Button(container, SWT.PUSH);
        test.setText("Test");
        test.addListener(SWT.Selection, e -> { if (onTest != null) onTest.run(); });

        Button save = new Button(container, SWT.PUSH);
        save.setText("Save & Start");
        save.addListener(SWT.Selection, e -> {
            try {
                LLMPreferences.setProvider(providerCombo.getText());
                LLMPreferences.setModel(modelCombo.getText());
                LLMPreferences.setApiKey(apiKeyText.getText());
                LLMPreferences.flush();
                if (onSave != null) onSave.run();
                container.setVisible(false);
                container.dispose();
            } catch (Exception ignore) {}
        });

        Button openAdvanced = new Button(container, SWT.PUSH);
        openAdvanced.setText("Advanced settings →");
        openAdvanced.addListener(SWT.Selection, e -> {
            // Intentionally no-op; caller can switch tab if desired
        });

        // Prefill from preferences
        try {
            if (LLMPreferences.getProvider() != null) providerCombo.setText(LLMPreferences.getProvider());
            if (LLMPreferences.getModel() != null) modelCombo.setText(LLMPreferences.getModel());
        } catch (Exception ignore) {}
    }

    public Composite getControl() { return container; }
    public void setOnSave(Runnable r) { this.onSave = r; }
    public void setOnTest(Runnable r) { this.onTest = r; }
}

