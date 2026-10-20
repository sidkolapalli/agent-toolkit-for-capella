package org.polarsys.capella.ai.preferences;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;
import org.osgi.service.prefs.BackingStoreException;
import org.polarsys.capella.ai.Activator;

import java.util.Locale;
import java.util.function.Function;

public final class LLMPreferences {
    private static final String NODE = Activator.PLUGIN_ID + "/llm";
    private static final String KEY_PROVIDER = "provider";
    private static final String KEY_BASE = "baseUrl";
    private static final String KEY_MODEL = "model";
    private static final String KEY_TEMP = "temperature";
    private static final String KEY_MAXTOK = "maxTokens";
    private static final String KEY_TIMEOUT = "timeoutSec";
    private static final String KEY_ENABLE_ACTIONS = "enableActions";
    private static final String KEY_USE_LLM_MAPPING = "useLlmMapping";
    private static final String KEY_TELEMETRY = "telemetryEnabled";
    private static final String KEY_PRIVACY_TOKENIZE = "privacyTokenize";
    private static final String KEY_DATASET_DROP_ON_ERROR = "datasetDropOnError";
    private static final String KEY_DATASET_DROP_ON_WARN = "datasetDropOnWarn";
    private static final String SEC_NODE = Activator.PLUGIN_ID + "/secure/llm";
    private static final String SEC_APIKEY = "apiKey";

    private LLMPreferences() {}

    public static String getProvider() {
        return getPrefs().get(KEY_PROVIDER, env("CAPELLA_AI_PROVIDER", "openai"));
    }

    public static String getBaseUrl() {
        return getPrefs().get(KEY_BASE, env("CAPELLA_AI_BASE_URL", "https://api.openai.com"));
    }

    public static String getModel() {
        return getPrefs().get(KEY_MODEL, env("CAPELLA_AI_MODEL", "gpt-5"));
    }

    public static double getTemperature() {
        return getPrefs().getDouble(KEY_TEMP, envDouble("CAPELLA_AI_TEMPERATURE", 0.2));
    }

    public static int getMaxTokens() {
        return getPrefs().getInt(KEY_MAXTOK, envInt("CAPELLA_AI_MAX_TOKENS", 2000));
    }

    public static int getTimeoutSec() {
        return getPrefs().getInt(KEY_TIMEOUT, envInt("CAPELLA_AI_TIMEOUT", 60));
    }

    public static boolean isToolActionsEnabled() {
        return getPrefs().getBoolean(KEY_ENABLE_ACTIONS, true);
    }

    public static boolean isUseLlmMapping() {
        return getPrefs().getBoolean(KEY_USE_LLM_MAPPING, false);
    }

    public static boolean isTelemetryEnabled() {
        return getPrefs().getBoolean(KEY_TELEMETRY, false);
    }

    public static boolean isPrivacyTokenizationEnabled() {
        return getPrefs()
                .getBoolean(KEY_PRIVACY_TOKENIZE, envBool("CAPELLA_AI_PRIVACY_TOKENIZE", false));
    }

    public static boolean isDatasetDropOnErrorEnabled() {
        return getPrefs()
                .getBoolean(
                        KEY_DATASET_DROP_ON_ERROR,
                        envBool("CAPELLA_AI_DATASET_DROP_ON_ERROR", true));
    }

    public static boolean isDatasetDropOnWarnEnabled() {
        return getPrefs()
                .getBoolean(
                        KEY_DATASET_DROP_ON_WARN,
                        envBool("CAPELLA_AI_DATASET_DROP_ON_WARN", false));
    }

    public static void setProvider(String v) {
        getPrefs().put(KEY_PROVIDER, v);
    }

    public static void setBaseUrl(String v) {
        getPrefs().put(KEY_BASE, v);
    }

    public static void setModel(String v) {
        getPrefs().put(KEY_MODEL, v);
    }

    public static void setTemperature(double v) {
        getPrefs().putDouble(KEY_TEMP, v);
    }

    public static void setMaxTokens(int v) {
        getPrefs().putInt(KEY_MAXTOK, v);
    }

    public static void setTimeoutSec(int v) {
        getPrefs().putInt(KEY_TIMEOUT, v);
    }

    public static void setToolActionsEnabled(boolean value) {
        getPrefs().putBoolean(KEY_ENABLE_ACTIONS, value);
    }

    public static void setUseLlmMapping(boolean value) {
        getPrefs().putBoolean(KEY_USE_LLM_MAPPING, value);
    }

    public static void setTelemetryEnabled(boolean value) {
        getPrefs().putBoolean(KEY_TELEMETRY, value);
    }

    public static void setPrivacyTokenizationEnabled(boolean value) {
        getPrefs().putBoolean(KEY_PRIVACY_TOKENIZE, value);
    }

    public static void setDatasetDropOnErrorEnabled(boolean value) {
        getPrefs().putBoolean(KEY_DATASET_DROP_ON_ERROR, value);
    }

    public static void setDatasetDropOnWarnEnabled(boolean value) {
        getPrefs().putBoolean(KEY_DATASET_DROP_ON_WARN, value);
    }

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

    public static void flush() {
        try {
            getPrefs().flush();
        } catch (BackingStoreException e) {
            throw new IllegalStateException("Failed to persist LLM preferences", e);
        }
    }

    private static IEclipsePreferences getPrefs() {
        return InstanceScope.INSTANCE.getNode(NODE);
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v != null && !v.isBlank() ? v : def;
    }

    private static int envInt(String k, int def) {
        try {
            return Integer.parseInt(env(k, String.valueOf(def)));
        } catch (Exception e) {
            return def;
        }
    }

    private static double envDouble(String k, double def) {
        try {
            return Double.parseDouble(env(k, String.valueOf(def)));
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean envBool(String k, boolean def) {
        String v = System.getenv(k);
        if (v == null) return def;
        String s = v.trim().toLowerCase();
        if (s.isEmpty()) return def;
        return s.equals("1")
                || s.equals("true")
                || s.equals("yes")
                || s.equals("y")
                || s.equals("on");
    }

    private static String envAnyKey() {
        return environmentKey(getProvider(), System::getenv);
    }

    private static String environmentKey(String provider, Function<String, String> environment) {
        String name =
                switch (provider == null ? "" : provider.strip().toLowerCase(Locale.ROOT)) {
                    case "openai" -> "OPENAI_API_KEY";
                    case "anthropic" -> "ANTHROPIC_API_KEY";
                    case "azure-openai" -> "AZURE_OPENAI_API_KEY";
                    case "openrouter" -> "OPENROUTER_API_KEY";
                        // Custom endpoints may be unrelated to any hosted provider; use a
                        // deliberate generic key.
                    case "custom" -> "CAPELLA_AI_API_KEY";
                    default -> "CAPELLA_AI_API_KEY";
                };
        String value = environment.apply(name);
        return value != null && !value.isBlank() ? value : "";
    }
}
