package org.polarsys.capella.ai.llm;

import static org.junit.Assert.*;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Provider protocol fixtures: no preferences, credentials or external requests. */
public class LLMProviderContractTest {
    @Test
    public void chatRestPreservesReplyWhitespaceAndRejectsTruncatedCode() {
        assertEquals(
                "\n  reply\n",
                parse(
                        "parseChatBody",
                        "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"\\n"
                            + "  reply\\n"
                            + "\"}}]}"));
        assertProtocolFailure(
                "parseChatBody",
                "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"```groovy"
                    + " partial\"}}]}",
                "output token limit");
    }

    @Test
    public void chatRestRefusalsAndToolCallsCannotBecomeExecutableReplies() {
        for (String message :
                List.of(
                        "{\"refusal\":\"no\",\"content\":\"```groovy partial\"}",
                        "{\"tool_calls\":[{}],\"content\":\"```groovy partial\"}",
                        "{\"function_call\":{},\"content\":\"partial\"}")) {
            assertThrows(
                    IllegalStateException.class,
                    () -> parse("parseChatBody", "{\"choices\":[{\"message\":" + message + "}]}"));
        }
        assertProtocolFailure(
                "parseChatBody",
                "{\"choices\":[{\"finish_reason\":\"content_filter\",\"message\":{\"content\":\"partial\"}}]}",
                "refused");
    }

    @Test
    public void malformedAndEmptyChatResponsesNeverReturnRawEnvelopes() {
        for (String body :
                List.of(
                        "{broken}",
                        "{}",
                        "[]",
                        "{\"choices\":[]}",
                        "{\"choices\":[{\"message\":{\"content\":\"  \"}}]}",
                        "{\"choices\":[{\"message\":{\"content\":42}}]}")) {
            assertThrows(body, IllegalStateException.class, () -> parse("parseChatBody", body));
        }
    }

    @Test
    public void anthropicRestIgnoresThinkingButPreservesAllText() {
        assertEquals(
                "first\n  second",
                parse(
                        "parseAnthropicBody",
                        """
                        {"stop_reason":"end_turn","content":[
                          {"type":"thinking","thinking":"internal"},
                          {"type":"text","text":"first\\n"},{"type":"text","text":"  second"}]}
                        """));
    }

    @Test
    public void anthropicRestRejectsTruncationRefusalAndUnsupportedTools() {
        assertProtocolFailure(
                "parseAnthropicBody",
                "{\"stop_reason\":\"max_tokens\",\"content\":[{\"type\":\"text\",\"text\":\"partial"
                    + " code\"}]}",
                "output token limit");
        assertProtocolFailure(
                "parseAnthropicBody",
                "{\"stop_reason\":\"refusal\",\"content\":[{\"type\":\"text\",\"text\":\"refused\"}]}",
                "refused");
        assertProtocolFailure(
                "parseAnthropicBody",
                "{\"content\":[{\"type\":\"tool_use\",\"name\":\"edit\"},{\"type\":\"text\",\"text\":\"partial\"}]}",
                "unsupported tool");
        assertProtocolFailure("parseAnthropicBody", "{\"content\":[]}", "no text output");
    }

    @Test
    public void chatStreamRequiresAValidFinishReasonAndPreservesWhitespace() {
        Object parser = parser("ChatStreamParser");
        assertEquals(
                "\n  ",
                stream(
                        parser,
                        "extract",
                        "{\"choices\":[{\"delta\":{\"content\":\"\\n"
                            + "  \"},\"finish_reason\":null}]}"));
        assertThrows(IllegalStateException.class, () -> stream(parser, "finish", "partial"));
        stream(parser, "extract", "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}");
        assertEquals("\n  reply\n", stream(parser, "finish", "\n  reply\n"));
        assertThrows(IllegalStateException.class, () -> stream(parser, "finish", " "));
    }

    @Test
    public void chatStreamRejectsLengthRefusalMalformedAndErrorEvents() {
        for (String event :
                List.of(
                        "{\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}",
                        "{\"choices\":[{\"delta\":{\"refusal\":\"no\"}}]}",
                        "{\"choices\":[{\"delta\":{\"tool_calls\":[{}]}}]}",
                        "{\"error\":{\"message\":\"fixture\"}}",
                        "{bad}",
                        "{\"choices\":[{\"delta\":42}]}")) {
            assertThrows(
                    event,
                    IllegalStateException.class,
                    () -> stream(parser("ChatStreamParser"), "extract", event));
        }
    }

    @Test
    public void anthropicStreamRequiresStopReasonAndMessageStop() {
        Object parser = parser("AnthropicStreamParser");
        assertEquals(
                "\n  ",
                stream(
                        parser,
                        "extract",
                        "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"\\n"
                            + "  \"}}"));
        assertThrows(IllegalStateException.class, () -> stream(parser, "finish", "partial"));
        stream(
                parser,
                "extract",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}");
        assertThrows(IllegalStateException.class, () -> stream(parser, "finish", "partial"));
        stream(parser, "extract", "{\"type\":\"message_stop\"}");
        assertEquals("\n  reply\n", stream(parser, "finish", "\n  reply\n"));
    }

    @Test
    public void anthropicStreamRejectsMissingStopReasonTruncationAndTools() {
        for (String event :
                List.of(
                        "{\"type\":\"message_stop\"}",
                        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"}}",
                        "{\"type\":\"content_block_start\",\"content_block\":{\"type\":\"tool_use\"}}",
                        "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}")) {
            assertThrows(
                    event,
                    IllegalStateException.class,
                    () -> stream(parser("AnthropicStreamParser"), "extract", event));
        }
    }

    @Test
    public void actionableHttpFailuresDoNotExposeResponseBodiesOrCredentialEchoes() {
        Throwable auth =
                (Throwable)
                        invoke(
                                "providerFailure",
                                new Class<?>[] {int.class, String.class},
                                401,
                                "<html>Incorrect API key provided: fixture-secret</html>");
        String message = LLMService.userFacingError(new CompletionException(auth));
        assertTrue(message.contains("API key"));
        assertTrue(message.contains("401"));
        assertFalse(message.contains("fixture-secret"));
        assertFalse(message.contains("<html>"));
        Throwable quota =
                (Throwable)
                        invoke(
                                "providerFailure",
                                new Class<?>[] {int.class, String.class},
                                429,
                                "{\"error\":{\"code\":\"insufficient_quota\"}}");
        assertTrue(LLMService.userFacingError(quota).contains("billing"));
    }

    @Test
    public void errorRecoverySeparatesStopTimeoutConnectionAndIncompleteOutput() {
        assertEquals(
                "Request stopped.",
                LLMService.userFacingError(new ExecutionException(new InterruptedException())));
        assertEquals("Request stopped.", LLMService.userFacingError(new CancellationException()));
        assertTrue(
                LLMService.userFacingError(new HttpTimeoutException("fixture"))
                        .contains("Timeout"));
        assertTrue(
                LLMService.userFacingError(new ConnectException("fixture")).contains("base URL"));
        assertTrue(
                LLMService.userFacingError(
                                new IllegalStateException(
                                        "status=incomplete, reason=max_output_tokens"))
                        .contains("Max tokens"));
        assertTrue(
                LLMService.userFacingError(
                                new IllegalStateException("stream ended before finish reason"))
                        .contains("incomplete"));
    }

    @Test
    public void jsonRequestsEscapeEveryLiteralControlCharacter() throws Exception {
        StringBuilder text = new StringBuilder("quoted \"\\");
        for (char value = 0; value < 32; value++) text.append(value);
        String json =
                (String)
                        invoke(
                                "buildChatMessagesJson",
                                new Class<?>[] {List.class},
                                List.of(LLMService.Message.user(text.toString())));
        Class<?> jsonType =
                Class.forName(
                        "org.polarsys.capella.ai.llm.SimpleJson",
                        false,
                        LLMService.class.getClassLoader());
        Method parser = jsonType.getDeclaredMethod("parse", String.class);
        parser.setAccessible(true);
        List<?> messages = (List<?>) parser.invoke(null, json);
        assertEquals(text.toString(), ((Map<?, ?>) messages.get(0)).get("content"));
    }

    @Test
    public void retryAfterSupportsHttpDatesAndRejectsNegativeDelays() {
        assertEquals(
                3000L,
                invoke(
                        "parseRetryAfterMillis",
                        new Class<?>[] {String.class, Instant.class},
                        "Wed, 21 Oct 2015 07:28:03 GMT",
                        Instant.parse("2015-10-21T07:28:00Z")));
        assertEquals(-1L, LLMService.parseRetryAfterMillis("-3"));
        assertEquals(
                Long.MAX_VALUE, LLMService.parseRetryAfterMillis(Long.toString(Long.MAX_VALUE)));
        assertEquals(30_000L, LLMService.computeBackoffMillis(Integer.MAX_VALUE, null));
    }

    private static void assertProtocolFailure(String method, String body, String expected) {
        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> parse(method, body));
        assertTrue(error.getMessage(), error.getMessage().contains(expected));
    }

    private static String parse(String method, String body) {
        return (String) invoke(method, new Class<?>[] {String.class}, body);
    }

    private static Object parser(String name) {
        try {
            Class<?> type =
                    java.util.Arrays.stream(LLMService.class.getDeclaredClasses())
                            .filter(candidate -> candidate.getSimpleName().equals(name))
                            .findFirst()
                            .orElseThrow();
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static String stream(Object parser, String method, String text) {
        try {
            Method target = parser.getClass().getDeclaredMethod(method, String.class);
            target.setAccessible(true);
            return (String) target.invoke(parser, text);
        } catch (InvocationTargetException error) {
            throw propagate(error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static Object invoke(String method, Class<?>[] types, Object... arguments) {
        try {
            Method target = LLMService.class.getDeclaredMethod(method, types);
            target.setAccessible(true);
            return target.invoke(null, arguments);
        } catch (InvocationTargetException error) {
            throw propagate(error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static RuntimeException propagate(Throwable error) {
        if (error instanceof RuntimeException runtime) return runtime;
        throw new AssertionError(error);
    }
}
