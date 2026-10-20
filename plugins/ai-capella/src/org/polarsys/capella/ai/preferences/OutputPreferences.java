package org.polarsys.capella.ai.preferences;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;
import org.polarsys.capella.ai.Activator;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Preferences for normalizing/redacting execution output before returning it to the LLM and to
 * external clients (e.g., the dev server).
 *
 * <p>These are intentionally "backend only" settings; they can be surfaced in a preference page
 * later.
 */
public final class OutputPreferences {

    private static final String NODE = Activator.PLUGIN_ID + "/output";

    private static final String KEY_MAX_LOG_LINES = "maxLogLines";
    private static final String KEY_MAX_LOG_LINE_CHARS = "maxLogLineChars";
    private static final String KEY_MAX_VALUE_CHARS = "maxValueChars";
    private static final String KEY_MAX_COLLECTION_SAMPLE = "maxCollectionSample";
    private static final String KEY_REDACTION_ENABLED = "redactionEnabled";
    private static final String KEY_REDACTION_REGEX_LIST = "redactionRegexList";

    private OutputPreferences() {}

    private static IEclipsePreferences prefs() {
        return InstanceScope.INSTANCE.getNode(NODE);
    }

    public static int getMaxLogLines() {
        return prefs().getInt(KEY_MAX_LOG_LINES, 200);
    }

    public static void setMaxLogLines(int v) {
        prefs().putInt(KEY_MAX_LOG_LINES, v);
    }

    public static int getMaxLogLineChars() {
        return prefs().getInt(KEY_MAX_LOG_LINE_CHARS, 500);
    }

    public static void setMaxLogLineChars(int v) {
        prefs().putInt(KEY_MAX_LOG_LINE_CHARS, v);
    }

    public static int getMaxValueChars() {
        return prefs().getInt(KEY_MAX_VALUE_CHARS, 8 * 1024);
    }

    public static void setMaxValueChars(int v) {
        prefs().putInt(KEY_MAX_VALUE_CHARS, v);
    }

    public static int getMaxCollectionSample() {
        return prefs().getInt(KEY_MAX_COLLECTION_SAMPLE, 10);
    }

    public static void setMaxCollectionSample(int v) {
        prefs().putInt(KEY_MAX_COLLECTION_SAMPLE, v);
    }

    public static boolean isRedactionEnabled() {
        return prefs().getBoolean(KEY_REDACTION_ENABLED, true);
    }

    public static void setRedactionEnabled(boolean v) {
        prefs().putBoolean(KEY_REDACTION_ENABLED, v);
    }

    /**
     * Additional redaction regex patterns to apply. One pattern per line. Blank lines and lines
     * starting with '#' are ignored.
     */
    public static List<Pattern> getAdditionalRedactionPatterns() {
        String raw = prefs().get(KEY_REDACTION_REGEX_LIST, "");
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        List<Pattern> patterns = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            try {
                patterns.add(Pattern.compile(trimmed));
            } catch (Exception e) {
                // Ignore invalid patterns; this must never break execution output.
            }
        }
        return List.copyOf(patterns);
    }

    public static void setRedactionRegexList(String v) {
        prefs().put(KEY_REDACTION_REGEX_LIST, v != null ? v : "");
    }

    public static void flush() {
        try {
            prefs().flush();
        } catch (BackingStoreException e) {
            // ignore
        }
    }
}
