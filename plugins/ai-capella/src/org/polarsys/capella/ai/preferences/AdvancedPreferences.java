package org.polarsys.capella.ai.preferences;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;
import org.polarsys.capella.ai.Activator;

/** Advanced/quality gates settings. */
public final class AdvancedPreferences {
    private static final String NODE = Activator.PLUGIN_ID + "/advanced";

    private static final String KEY_QUICK_VALIDATION = "quickValidation";
    private static final String KEY_COVERAGE_GATES = "coverageGates";
    private static final String KEY_TRACEABILITY_GATES = "traceabilityGates";
    private static final String KEY_AUTO_FIXES = "autoFixes";
    private static final String KEY_MAX_PLAN_ACTIONS = "maxPlanActions";
    private static final String KEY_MAX_AUTOFIX_REMOVALS = "maxAutoFixRemovals";

    private AdvancedPreferences() {}

    private static IEclipsePreferences prefs() { return InstanceScope.INSTANCE.getNode(NODE); }

    public static boolean isQuickValidationEnabled() { return prefs().getBoolean(KEY_QUICK_VALIDATION, true); }
    public static void setQuickValidationEnabled(boolean v) { prefs().putBoolean(KEY_QUICK_VALIDATION, v); }

    public static boolean isCoverageGatesEnabled() { return prefs().getBoolean(KEY_COVERAGE_GATES, true); }
    public static void setCoverageGatesEnabled(boolean v) { prefs().putBoolean(KEY_COVERAGE_GATES, v); }

    public static boolean isTraceabilityGatesEnabled() { return prefs().getBoolean(KEY_TRACEABILITY_GATES, true); }
    public static void setTraceabilityGatesEnabled(boolean v) { prefs().putBoolean(KEY_TRACEABILITY_GATES, v); }

    public static boolean isAutoFixesEnabled() { return prefs().getBoolean(KEY_AUTO_FIXES, true); }
    public static void setAutoFixesEnabled(boolean v) { prefs().putBoolean(KEY_AUTO_FIXES, v); }

    public static int getMaxPlanActions() { return prefs().getInt(KEY_MAX_PLAN_ACTIONS, 200); }
    public static void setMaxPlanActions(int v) { prefs().putInt(KEY_MAX_PLAN_ACTIONS, v); }

    public static int getMaxAutoFixRemovals() { return prefs().getInt(KEY_MAX_AUTOFIX_REMOVALS, 25); }
    public static void setMaxAutoFixRemovals(int v) { prefs().putInt(KEY_MAX_AUTOFIX_REMOVALS, v); }

    public static void flush() {
        try { prefs().flush(); } catch (BackingStoreException e) { /* ignore */ }
    }
}

