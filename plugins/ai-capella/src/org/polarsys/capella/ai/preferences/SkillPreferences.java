package org.polarsys.capella.ai.preferences;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;
import org.polarsys.capella.ai.Activator;

/** Preferences for skill runtime and trust gates. */
public final class SkillPreferences {

    private static final String NODE = Activator.PLUGIN_ID + "/skills";
    private static final String KEY_ALLOW_USER_SKILLS = "allowUserSkills";

    private SkillPreferences() {}

    private static IEclipsePreferences prefs() {
        return InstanceScope.INSTANCE.getNode(NODE);
    }

    /** Whether user-defined skills are allowed to execute. Default: false. */
    public static boolean isUserSkillsExecutionEnabled() {
        return prefs().getBoolean(KEY_ALLOW_USER_SKILLS, false);
    }

    public static void setUserSkillsExecutionEnabled(boolean v) {
        prefs().putBoolean(KEY_ALLOW_USER_SKILLS, v);
    }

    public static void flush() {
        try {
            prefs().flush();
        } catch (BackingStoreException e) {
            // ignore
        }
    }
}
