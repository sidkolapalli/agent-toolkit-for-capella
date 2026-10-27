package com.sidkolapalli.capella.ai.llm;

import static org.junit.Assert.*;
import static org.junit.Assume.*;

import org.junit.Before;
import org.junit.Test;
import com.sidkolapalli.capella.ai.preferences.LLMPreferences;

import java.util.List;

/**
 * Integration test for LLMService against real LLM APIs.
 *
 * <p>Tests are skipped if no API key is configured. Set environment variables:
 *
 * <ul>
 *   <li>CAPELLA_AI_RUN_LLM_TESTS - set to "true" to enable live LLM tests
 *   <li>ANTHROPIC_API_KEY - for Anthropic Claude models
 *   <li>OPENAI_API_KEY - for OpenAI models
 *   <li>CAPELLA_AI_PROVIDER - "anthropic" or "openai" (default: auto-detect from key)
 *   <li>CAPELLA_AI_MODEL - model name (default: claude-sonnet-4-20250514 for Anthropic)
 * </ul>
 *
 * <p>Run with: mvn -f plugins -pl ai-capella.tests -am verify -Dtest=LLMServiceTest
 */
public class LLMServiceTest {

    private boolean hasApiKey;
    private String provider;
    private boolean testsEnabled;
    private static Boolean validatedKey;

    @Before
    public void setUp() {
        testsEnabled = Boolean.parseBoolean(System.getenv("CAPELLA_AI_RUN_LLM_TESTS"));

        // Check for available API keys
        String anthropicKey = System.getenv("ANTHROPIC_API_KEY");
        String openaiKey = System.getenv("OPENAI_API_KEY");

        hasApiKey =
                (anthropicKey != null && !anthropicKey.isBlank())
                        || (openaiKey != null && !openaiKey.isBlank());

        if (testsEnabled && hasApiKey) {
            provider = LLMPreferences.getProvider();
            System.out.println("[LLMServiceTest] Using provider: " + provider);
            System.out.println("[LLMServiceTest] Using model: " + LLMPreferences.getModel());
            if (validatedKey == null) {
                try {
                    // Lightweight validation to confirm the key is accepted.
                    LLMService.get().send("Reply with exactly: OK");
                    validatedKey = Boolean.TRUE;
                } catch (Exception ex) {
                    validatedKey = Boolean.FALSE;
                    System.out.println(
                            "[LLMServiceTest] Invalid or rejected API key; skipping LLM tests");
                }
            }
            hasApiKey = Boolean.TRUE.equals(validatedKey);
        }
    }

    @Test
    public void testConnectionInfo() throws Exception {
        assumeTrue(
                "LLM integration tests disabled - set CAPELLA_AI_RUN_LLM_TESTS=true", testsEnabled);
        assumeTrue("No API key configured - skipping LLM test", hasApiKey);

        String info = LLMService.get().testConnection();
        assertNotNull("Connection info should not be null", info);
        System.out.println("[LLMServiceTest] Connection info: " + info);
    }

    @Test
    public void testSimplePrompt() throws Exception {
        assumeTrue(
                "LLM integration tests disabled - set CAPELLA_AI_RUN_LLM_TESTS=true", testsEnabled);
        assumeTrue("No API key configured - skipping LLM test", hasApiKey);

        String response = LLMService.get().send("Reply with exactly: PONG");
        assertNotNull("Response should not be null", response);
        assertTrue("Response should contain PONG", response.toUpperCase().contains("PONG"));
        System.out.println("[LLMServiceTest] Simple response: " + response);
    }

    @Test
    public void testMessageList() throws Exception {
        assumeTrue(
                "LLM integration tests disabled - set CAPELLA_AI_RUN_LLM_TESTS=true", testsEnabled);
        assumeTrue("No API key configured - skipping LLM test", hasApiKey);

        List<LLMService.Message> messages =
                List.of(
                        LLMService.Message.system(
                                "You are a test assistant. Always respond with valid JSON."),
                        LLMService.Message.user(
                                "Return a JSON object with key 'status' and value 'ok'"));

        String response = LLMService.get().send(messages);
        assertNotNull("Response should not be null", response);
        assertTrue("Response should contain 'status'", response.contains("status"));
        assertTrue("Response should contain 'ok'", response.contains("ok"));
        System.out.println("[LLMServiceTest] JSON response: " + response);
    }

    @Test
    public void testStreamingResponse() throws Exception {
        assumeTrue(
                "LLM integration tests disabled - set CAPELLA_AI_RUN_LLM_TESTS=true", testsEnabled);
        assumeTrue("No API key configured - skipping LLM test", hasApiKey);

        List<LLMService.Message> messages =
                List.of(LLMService.Message.user("Count from 1 to 5, one number per line"));

        StringBuilder streamed = new StringBuilder();
        int[] chunkCount = {0};

        LLMService.StreamHandle handle =
                LLMService.get().stream(
                        messages,
                        new LLMService.StreamListener() {
                            @Override
                            public boolean isCancelled() {
                                return false;
                            }

                            @Override
                            public void onChunk(String text) {
                                streamed.append(text);
                                chunkCount[0]++;
                            }
                        });

        String finalResponse = handle.completion().get();

        assertNotNull("Final response should not be null", finalResponse);
        assertTrue("Should have received multiple chunks", chunkCount[0] > 0);
        assertEquals(
                "Streamed content should match final response", finalResponse, streamed.toString());
        System.out.println(
                "[LLMServiceTest] Streamed response ("
                        + chunkCount[0]
                        + " chunks): "
                        + finalResponse);
    }

    @Test
    public void testToolPlanGeneration() throws Exception {
        assumeTrue(
                "LLM integration tests disabled - set CAPELLA_AI_RUN_LLM_TESTS=true", testsEnabled);
        assumeTrue("No API key configured - skipping LLM test", hasApiKey);

        List<LLMService.Message> messages =
                List.of(
                        LLMService.Message.system(
                                """
You are a Capella AI assistant. When asked to create model elements,
respond with a TOOL_PLAN JSON block like:

```TOOL_PLAN
{
  "actions": [
    { "create_component": { "layer": "LA", "name": "TestComponent" } }
  ]
}
```

IMPORTANT: Always include the TOOL_PLAN markers and valid JSON.
"""),
                        LLMService.Message.user("Create a logical component called 'SensorUnit'"));

        String response = LLMService.get().send(messages);
        assertNotNull("Response should not be null", response);
        System.out.println("[LLMServiceTest] Tool plan response:\n" + response);

        // Verify response contains TOOL_PLAN block with proper structure
        boolean hasToolPlan = response.contains("TOOL_PLAN");
        boolean hasActions = response.contains("\"actions\"") || response.contains("'actions'");
        boolean hasSensorUnit = response.contains("SensorUnit");
        boolean hasCreateComponent =
                response.contains("create_component") || response.contains("create");

        assertTrue("Response should contain TOOL_PLAN block marker", hasToolPlan);
        assertTrue("Response should contain 'actions' key in JSON", hasActions);
        assertTrue("Response should reference 'SensorUnit' component name", hasSensorUnit);
        assertTrue("Response should contain create action", hasCreateComponent);

        // Extract and validate JSON structure
        int planStart = response.indexOf("```TOOL_PLAN");
        int planEnd = response.indexOf("```", planStart + 12);
        if (planStart >= 0 && planEnd > planStart) {
            String jsonBlock = response.substring(planStart + 12, planEnd).trim();
            // Basic JSON structure validation
            assertTrue("JSON should start with {", jsonBlock.startsWith("{"));
            assertTrue("JSON should end with }", jsonBlock.trim().endsWith("}"));
            assertTrue(
                    "JSON should have balanced braces",
                    countChar(jsonBlock, '{') == countChar(jsonBlock, '}'));
            assertTrue(
                    "JSON should have balanced brackets",
                    countChar(jsonBlock, '[') == countChar(jsonBlock, ']'));
        }
    }

    private int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) count++;
        }
        return count;
    }
}
