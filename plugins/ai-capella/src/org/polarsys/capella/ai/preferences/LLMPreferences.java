package org.polarsys.capella.ai.preferences;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;
import org.polarsys.capella.ai.Activator;

public final class LLMPreferences {
    private static final String NODE = Activator.PLUGIN_ID + "/llm";
    private static final String KEY_PROVIDER = "provider";
    private static final String KEY_BASE = "baseUrl";
    private static final String KEY_MODEL = "model";
    private static final String KEY_TEMP = "temperature";
    private static final String KEY_MAXTOK = "maxTokens";
    private static final String KEY_TIMEOUT = "timeoutSec";
    private static final String SEC_NODE = Activator.PLUGIN_ID + "/secure/llm";
    private static final String SEC_APIKEY = "apiKey";

    private LLMPreferences() {}

    public static String getProvider() { return getPrefs().get(KEY_PROVIDER, env("CAPELLA_AI_PROVIDER", "openai")); }
    public static String getBaseUrl() { return getPrefs().get(KEY_BASE, env("CAPELLA_AI_BASE_URL", "https://api.openai.com")); }
    public static String getModel() { return getPrefs().get(KEY_MODEL, env("CAPELLA_AI_MODEL", "gpt-4o-mini")); }
    public static double getTemperature() { return getPrefs().getDouble(KEY_TEMP, envDouble("CAPELLA_AI_TEMPERATURE", 0.2)); }
    public static int getMaxTokens() { return getPrefs().getInt(KEY_MAXTOK, envInt("CAPELLA_AI_MAX_TOKENS", 2000)); }
    public static int getTimeoutSec() { return getPrefs().getInt(KEY_TIMEOUT, envInt("CAPELLA_AI_TIMEOUT", 60)); }

    public static void setProvider(String v) { getPrefs().put(KEY_PROVIDER, v); }
    public static void setBaseUrl(String v) { getPrefs().put(KEY_BASE, v); }
    public static void setModel(String v) { getPrefs().put(KEY_MODEL, v); }
    public static void setTemperature(double v) { getPrefs().putDouble(KEY_TEMP, v); }
    public static void setMaxTokens(int v) { getPrefs().putInt(KEY_MAXTOK, v); }
    public static void setTimeoutSec(int v) { getPrefs().putInt(KEY_TIMEOUT, v); }

    public static String getApiKey() {
        try {
            ISecurePreferences sec = SecurePreferencesFactory.getDefault();
            return sec.node(SEC_NODE).get(SEC_APIKEY, envAnyKey());
        } catch (Exception e) {
            return envAnyKey();
        }
    }

    public static void setApiKey(String key) throws StorageException {
        ISecurePreferences sec = SecurePreferencesFactory.getDefault();
        sec.node(SEC_NODE).put(SEC_APIKEY, key, true);
    }

    public static void flush() throws java.util.prefs.BackingStoreException {
        getPrefs().flush();
    }

    private static IEclipsePreferences getPrefs() {
        return InstanceScope.INSTANCE.getNode(NODE);
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v != null && !v.isBlank() ? v : def;
    }

    private static int envInt(String k, int def) {
        try { return Integer.parseInt(env(k, String.valueOf(def))); } catch (Exception e) { return def; }
    }

    private static double envDouble(String k, double def) {
        try { return Double.parseDouble(env(k, String.valueOf(def))); } catch (Exception e) { return def; }
    }

    private static String envAnyKey() {
        String[] keys = new String[] {"OPENAI_API_KEY", "ANTHROPIC_API_KEY", "AZURE_OPENAI_API_KEY", "OPENROUTER_API_KEY"};
        for (String k : keys) { String v = System.getenv(k); if (v != null && !v.isBlank()) return v; }
        return "";
    }
}

