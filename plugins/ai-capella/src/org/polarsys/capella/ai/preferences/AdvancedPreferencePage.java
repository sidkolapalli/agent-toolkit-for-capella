package org.polarsys.capella.ai.preferences;

import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

public class AdvancedPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {
    private Button quickValidation;
    private Button coverageGates;
    private Button traceabilityGates;
    private Button autoFixes;
    private Text maxPlanActions;
    private Text maxAutoFixRemovals;
    private Button orchestrationEnabled;
    private Text autoSaveInterval;
    private Button selfHealingEnabled;
    private Text selfHealingMaxIters;

    @Override
    public void init(IWorkbench workbench) {}

    @Override
    protected Control createContents(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(2, false));

        quickValidation = checkbox(root, "Enable quick validation after apply", AdvancedPreferences.isQuickValidationEnabled());
        coverageGates = checkbox(root, "Enable coverage gates (advisory)", AdvancedPreferences.isCoverageGatesEnabled());
        traceabilityGates = checkbox(root, "Enable traceability gates (advisory)", AdvancedPreferences.isTraceabilityGatesEnabled());
        autoFixes = checkbox(root, "Enable auto-fixes (safe cleanups)", AdvancedPreferences.isAutoFixesEnabled());

        orchestrationEnabled = checkbox(root, "Enable orchestration (checkpoint/resume)", AdvancedPreferences.isOrchestrationEnabled());

        mkLabel(root, "Auto-save interval (sec):");
        autoSaveInterval = new Text(root, SWT.BORDER);
        autoSaveInterval.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        autoSaveInterval.setText(String.valueOf(AdvancedPreferences.getAutoSaveIntervalSec()));

        selfHealingEnabled = checkbox(root, "Enable self-healing (refine and reapply)", AdvancedPreferences.isSelfHealingEnabled());

        mkLabel(root, "Self-healing iterations:");
        selfHealingMaxIters = new Text(root, SWT.BORDER);
        selfHealingMaxIters.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        selfHealingMaxIters.setText(String.valueOf(AdvancedPreferences.getSelfHealingMaxIters()));

        mkLabel(root, "Max plan actions:");
        maxPlanActions = new Text(root, SWT.BORDER);
        maxPlanActions.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        maxPlanActions.setText(String.valueOf(AdvancedPreferences.getMaxPlanActions()));

        mkLabel(root, "Max auto-fix removals:");
        maxAutoFixRemovals = new Text(root, SWT.BORDER);
        maxAutoFixRemovals.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        maxAutoFixRemovals.setText(String.valueOf(AdvancedPreferences.getMaxAutoFixRemovals()));
        return root;
    }

    private Button checkbox(Composite parent, String label, boolean initial) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(label);
        Button b = new Button(parent, SWT.CHECK);
        b.setSelection(initial);
        return b;
    }

    private static void mkLabel(Composite parent, String text) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(text);
    }

    @Override
    public boolean performOk() {
        try {
            AdvancedPreferences.setQuickValidationEnabled(quickValidation.getSelection());
            AdvancedPreferences.setCoverageGatesEnabled(coverageGates.getSelection());
            AdvancedPreferences.setTraceabilityGatesEnabled(traceabilityGates.getSelection());
            AdvancedPreferences.setAutoFixesEnabled(autoFixes.getSelection());
            AdvancedPreferences.setMaxPlanActions(parseInt(maxPlanActions.getText(), 200));
            AdvancedPreferences.setMaxAutoFixRemovals(parseInt(maxAutoFixRemovals.getText(), 25));
            AdvancedPreferences.setOrchestrationEnabled(orchestrationEnabled.getSelection());
            AdvancedPreferences.setAutoSaveIntervalSec(parseInt(autoSaveInterval.getText(), 30));
            AdvancedPreferences.setSelfHealingEnabled(selfHealingEnabled.getSelection());
            AdvancedPreferences.setSelfHealingMaxIters(parseInt(selfHealingMaxIters.getText(), 1));
            AdvancedPreferences.flush();
            return true;
        } catch (Exception e) {
            setErrorMessage(e.getMessage());
            return false;
        }
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }
}
