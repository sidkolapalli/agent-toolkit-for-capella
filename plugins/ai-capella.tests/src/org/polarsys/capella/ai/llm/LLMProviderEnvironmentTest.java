package org.polarsys.capella.ai.llm;

import static org.junit.Assert.*;

import org.junit.Test;
import org.polarsys.capella.ai.preferences.LLMPreferences;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Function;

/** Fictitious keys only; never reads process environment or secure preferences. */
public class LLMProviderEnvironmentTest {
    @Test
    public void eachProviderChoosesOnlyItsOwnEnvironmentKeyWhenAllArePresent() throws Exception {
        Map<String, String> environment =
                Map.of(
                        "OPENAI_API_KEY",
                        "openai-fixture",
                        "ANTHROPIC_API_KEY",
                        "anthropic-fixture",
                        "AZURE_OPENAI_API_KEY",
                        "azure-fixture",
                        "OPENROUTER_API_KEY",
                        "router-fixture",
                        "CAPELLA_AI_API_KEY",
                        "custom-fixture");
        assertEquals("openai-fixture", key("openai", environment));
        assertEquals("anthropic-fixture", key("anthropic", environment));
        assertEquals("azure-fixture", key("azure-openai", environment));
        assertEquals("router-fixture", key("openrouter", environment));
        assertEquals("custom-fixture", key("custom", environment));
        assertEquals("anthropic-fixture", key(" ANTHROPIC ", environment));
    }

    @Test
    public void aMissingProviderKeyNeverFallsBackToAnotherProvidersCredential() throws Exception {
        Map<String, String> environment =
                Map.of("OPENAI_API_KEY", "openai-fixture", "ANTHROPIC_API_KEY", " ");
        assertEquals("", key("anthropic", environment));
        assertEquals("", key("azure-openai", environment));
        assertEquals("", key("openrouter", environment));
        assertEquals("", key("custom", environment));
    }

    private static String key(String provider, Map<String, String> environment) throws Exception {
        Method method =
                LLMPreferences.class.getDeclaredMethod(
                        "environmentKey", String.class, Function.class);
        method.setAccessible(true);
        return (String) method.invoke(null, provider, (Function<String, String>) environment::get);
    }
}
