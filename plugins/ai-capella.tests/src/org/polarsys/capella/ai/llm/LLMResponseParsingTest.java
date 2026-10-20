package org.polarsys.capella.ai.llm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.polarsys.capella.ai.llm.LLMService.Message;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/** Deterministic REST request/response fixtures; no API key or network is required. */
public class LLMResponseParsingTest {

    @Test
    public void testNonStreamingRequestPreservesConversationRoles() {
        assertRequestPreservesMessages(false);
    }

    @Test
    public void testStreamingRequestPreservesConversationRoles() {
        assertRequestPreservesMessages(true);
    }

    @Test
    public void testStreamPreservesWhitespaceAndUsesCompletedResponse() {
        Object parser = newStreamParser();
        assertEquals(
                "\n  ",
                invokeStream(
                        parser,
                        "extract",
                        "{\"type\":\"response.output_text.delta\",\"delta\":\"\\n  \"}"));
        invokeStream(
                parser,
                "extract",
                """
                {"type":"response.completed","response":{
                  "status":"completed","output":[{"type":"message","content":[
                    {"type":"output_text","text":"complete\\n"}
                  ]}]
                }}
                """);
        assertEquals("complete\n", invokeStream(parser, "finish", "partial"));
    }

    @Test
    public void testStreamWithoutCompletedEventRejectsPartialCode() {
        Object parser = newStreamParser();
        invokeStream(
                parser,
                "extract",
                "{\"type\":\"response.output_text.delta\",\"delta\":\"partial code\"}");
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> invokeStream(parser, "finish", "partial code"));
        assertTrue(error.getMessage().contains("before response.completed"));
    }

    @Test
    public void testStreamRefusalFailureIncompleteAndMalformedEventsFail() {
        List<String> events =
                List.of(
                        "{\"type\":\"response.refusal.delta\",\"delta\":\"refused\"}",
                        "{\"type\":\"response.refusal.done\",\"refusal\":\"refused\"}",
                        "{\"type\":\"error\",\"message\":\"provider failure\"}",
                        "{\"type\":\"response.failed\",\"response\":{\"status\":\"failed\"}}",
                        "{\"type\":\"response.incomplete\",\"response\":{\"status\":\"incomplete\"}}",
                        "{\"type\":\"response.output_text.delta\",\"delta\":42}",
                        "{invalid JSON}");
        for (String event : events) {
            Object parser = newStreamParser();
            assertThrows(IllegalStateException.class, () -> invokeStream(parser, "extract", event));
        }
    }

    private static void assertRequestPreservesMessages(boolean streaming) {
        List<Message> messages =
                List.of(
                        Message.system("Follow the \"model\" instructions.\n  Keep indentation.\n"),
                        Message.user("Create \"Sensor\" at C:\\models\\test.\r\nNext line.\t"),
                        Message.assistant(
                                "```groovy\n"
                                    + "api.create(\"LA\", \"LogicalComponent\", \"Sensor\")\n"
                                    + "```\n"),
                        Message.user("  Rename it to \"Probe\".\n"));
        Map<?, ?> body = buildRequest(messages, streaming);

        assertEquals("gpt-5", body.get("model"));
        assertEquals(2048, body.get("max_output_tokens"));
        assertEquals(streaming, Boolean.TRUE.equals(body.get("stream")));
        assertEquals(streaming, body.containsKey("temperature"));
        if (streaming) {
            assertEquals(0.2, ((Number) body.get("temperature")).doubleValue(), 0.0);
        }
        assertTrue("Responses input must be a message array", body.get("input") instanceof List<?>);
        List<?> input = (List<?>) body.get("input");
        assertEquals(messages.size(), input.size());
        String[] roles = {"system", "user", "assistant", "user"};
        for (int i = 0; i < messages.size(); i++) {
            assertTrue(input.get(i) instanceof Map<?, ?>);
            Map<?, ?> message = (Map<?, ?>) input.get(i);
            assertEquals(roles[i], message.get("role"));
            assertEquals(messages.get(i).getContent(), message.get("content"));
        }
    }

    @Test
    public void testPreservesDecodedGroovyExactly() {
        String body =
                """
{
  "status": "completed",
  "output": [{
    "type": "message",
    "status": "completed",
    "role": "assistant",
    "content": [{
      "type": "output_text",
      "text": "```groovy\\napi.create(\\"LA\\", \\"LogicalComponent\\", \\"Sensor\\")\\nprintln(\\"C:\\\\temp\\")\\n```\\n"
    }]
  }]
}
""";

        assertEquals(
                "```groovy\napi.create(\"LA\", \"LogicalComponent\", \"Sensor\")\n"
                        + "println(\"C:\\temp\")\n```\n",
                parse(body));
    }

    @Test
    public void testCombinesAllMessageContentInOutputOrder() {
        String body =
                """
                {
                  "status": "completed",
                  "output": [
                    {"type": "reasoning", "summary": []},
                    {"type": "message", "content": [
                      {"type": "output_text", "text": "First\\n"},
                      {"type": "output_text", "text": "  second"}
                    ]},
                    {"type": "function_call", "name": "irrelevant", "arguments": "{}"},
                    {"type": "message", "content": [
                      {"type": "output_text", "text": "\\nThird\\n"}
                    ]}
                  ]
                }
                """;

        assertEquals("First\n  second\nThird\n", parse(body));
    }

    @Test
    public void testSupportsAggregatedProxyText() {
        assertEquals("Proxy reply\n", parse("{\"output_text\":\"Proxy reply\\n\"} \n\t"));
    }

    @Test
    public void testRefusalDoesNotReturnExecutableText() {
        assertResponseError(
                """
                {
                  "status": "completed",
                  "output_text": "```groovy doSomething() ```",
                  "output": [{"type": "message", "content": [
                    {"type": "refusal", "refusal": "I cannot help with that request."}
                  ]}]
                }
                """,
                "refused the request: I cannot help with that request.");
    }

    @Test
    public void testIncompleteResponseDoesNotReturnPartialCode() {
        assertResponseError(
                """
                {
                  "status": "incomplete",
                  "incomplete_details": {"reason": "max_output_tokens"},
                  "output": [{"type": "message", "content": [
                    {"type": "output_text", "text": "```groovy\\napi.create("}
                  ]}]
                }
                """,
                "status=incomplete, reason=max_output_tokens");
    }

    @Test
    public void testIncompleteMessageDoesNotReturnPartialCode() {
        assertResponseError(
                """
                {
                  "status": "completed",
                  "output": [{"type": "message", "status": "incomplete", "content": [
                    {"type": "output_text", "text": "partial"}
                  ]}]
                }
                """,
                "incomplete message");
    }

    @Test
    public void testProviderErrorIncludesUsefulDetail() {
        assertResponseError(
                "{\"status\":\"failed\",\"error\":{\"message\":\"Server unavailable\"}}",
                "failed: Server unavailable");
    }

    @Test
    public void testMalformedAndNonObjectResponsesFailClearly() {
        assertResponseError("{\"output\":", "invalid JSON");
        assertResponseError("{\"output_text\":\"reply\"} {\"extra\":true}", "invalid JSON");
        assertResponseError("{\"output_text\":\"literal\nnewline\"}", "invalid JSON");
        assertResponseError("{\"output_text\":\"literal\ttab\"}", "invalid JSON");
        assertResponseError("[]", "expected a JSON object");
        assertResponseError("{\"output\":{}}", "output must be an array");
        assertResponseError(
                "{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":42}]}]}",
                "output text must be a string");
    }

    @Test
    public void testEmptyResponsesDoNotReturnEnvelopes() {
        assertResponseError(null, "empty response body");
        assertResponseError("  ", "empty response body");
        assertResponseError("{}", "no text output");
        assertResponseError("{\"output\":[]}", "no text output");
        assertResponseError(
                "{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\""
                    + " \\n"
                    + "\"}]}]}",
                "no text output");
    }

    private static void assertResponseError(String body, String expectedMessage) {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> parse(body));
        assertTrue(error.getMessage(), error.getMessage().contains(expectedMessage));
    }

    private static String parse(String body) {
        try {
            // The production and test bundles have separate OSGi class loaders.
            Method parser = LLMService.class.getDeclaredMethod("parseResponsesBody", String.class);
            parser.setAccessible(true);
            return (String) parser.invoke(null, body);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new AssertionError(ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static Map<?, ?> buildRequest(List<Message> messages, boolean streaming) {
        try {
            Method builder =
                    LLMService.class.getDeclaredMethod(
                            "buildResponsesBody",
                            List.class,
                            String.class,
                            int.class,
                            double.class,
                            boolean.class,
                            boolean.class);
            builder.setAccessible(true);
            String json =
                    (String)
                            builder.invoke(
                                    null, messages, "gpt-5", 2048, 0.2, streaming, streaming);
            Class<?> jsonClass =
                    Class.forName(
                            "org.polarsys.capella.ai.llm.SimpleJson",
                            false,
                            LLMService.class.getClassLoader());
            Method parser = jsonClass.getDeclaredMethod("parse", String.class);
            parser.setAccessible(true);
            return (Map<?, ?>) parser.invoke(null, json);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static Object newStreamParser() {
        try {
            Class<?> parserClass =
                    Class.forName(
                            "org.polarsys.capella.ai.llm.LLMService$ResponsesStreamParser",
                            false,
                            LLMService.class.getClassLoader());
            Constructor<?> constructor = parserClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static String invokeStream(Object parser, String method, String value) {
        try {
            Method operation = parser.getClass().getDeclaredMethod(method, String.class);
            operation.setAccessible(true);
            return (String) operation.invoke(parser, value);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException cause) throw cause;
            throw new AssertionError(ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }
}
