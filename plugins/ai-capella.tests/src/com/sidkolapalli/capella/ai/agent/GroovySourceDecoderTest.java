package com.sidkolapalli.capella.ai.agent;

import static org.junit.Assert.*;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;

import org.junit.Test;
import com.sidkolapalli.capella.ai.privacy.Tokenizer;

import java.util.List;
import java.util.UUID;

/** Evaluates only fixed synthetic scripts with an API stub; no model or provider is accessed. */
public class GroovySourceDecoderTest {
    @Test
    public void allQuotedFormsRestoreExactDataWithoutInterpolationOrExtraExecution() {
        for (String delimiter : List.of("'", "\"", "'''", "\"\"\"")) {
            for (String value :
                    List.of(
                            "Driver's display",
                            "Double \"control\"",
                            "C:\\vehicle\\new",
                            "$expr ${api.create('unsafe','unsafe','injected')}",
                            "first\nsecond\rthird\tlast",
                            "''' and \"\"\"",
                            "🚗 Driver's display")) {
                Tokenizer tokenizer = tokenizer();
                String token = tokenizer.tokenizeDataValue(value);
                String source =
                        "api.create('SA', 'SystemFunction', " + delimiter + token + delimiter + ")";
                String restored = GroovySourceDecoder.decode(source, tokenizer::detokenizeText);
                ApiStub api = evaluate(restored);
                assertEquals(delimiter + " / " + value, value, api.value);
                assertEquals(1, api.calls);
                assertEquals(
                        restored, GroovySourceDecoder.decode(restored, tokenizer::detokenizeText));
            }
        }
    }

    @Test
    public void existingEscapesAndGStringExpressionsArePreservedWhileReplacementIsLiteralData() {
        Tokenizer tokenizer = tokenizer();
        String value = "Driver's \\\"display\\\" $expr\nnext";
        String token = tokenizer.tokenizeDataValue(value);
        String source =
                "api.create('SA', 'SystemFunction', \"prefix\\t${1 + 1} " + token + " suffix\")";
        String restored = GroovySourceDecoder.decode(source, tokenizer::detokenizeText);
        ApiStub api = evaluate(restored);
        assertEquals("prefix\t2 " + value + " suffix", api.value);
        assertTrue(restored.contains("${1 + 1}"));
        assertEquals(1, api.calls);
    }

    @Test
    public void codePointOffsetsPreserveUnicodeBeforeAndInsideReplacedLiterals() {
        Tokenizer tokenizer = tokenizer();
        String token = tokenizer.tokenizeDataValue("🚗 Driver's display");
        String source =
                "// 🚗 emoji before the literal\n"
                    + " def unrelated = '🔋'; api.create('SA', 'SystemFunction', '"
                        + token
                        + "')";
        String restored = GroovySourceDecoder.decode(source, tokenizer::detokenizeText);
        assertTrue(restored.startsWith("// 🚗 emoji before the literal\n def unrelated = '🔋';"));
        assertEquals("🚗 Driver's display", evaluate(restored).value);
    }

    @Test
    public void uuidAndNameLiteralsDoNotDoubleEscapeOnJsonRoundTrip() {
        Tokenizer tokenizer = tokenizer();
        String name = "Driver's display\\new\n$expr";
        String token = tokenizer.tokenizeDataValue(name);
        String source = "api.create('SA', 'SystemFunction', '" + token + "')";
        String protocol =
                "{\"kind\":\"proposal\",\"message\":\"Create\",\"script\":" + json(source) + "}";
        String expected =
                "{\"kind\":\"proposal\",\"message\":\"Create\",\"script\":"
                        + json(GroovySourceDecoder.decode(source, tokenizer::detokenizeText))
                        + "}";
        assertEquals(expected, AgentResponseDecoder.decode(protocol, tokenizer::detokenizeText));
        String id = "123e4567-e89b-12d3-a456-426614174000";
        String idToken = tokenizer.tokenizeDataValue(id);
        assertEquals(
                "api.create('SA', 'SystemFunction', '" + id + "')",
                GroovySourceDecoder.decode(
                        "api.create('SA', 'SystemFunction', '" + idToken + "')",
                        tokenizer::detokenizeText));
    }

    @Test
    public void legacyGroovyAndAgentJsonFencesRestoreBeforeReview() {
        Tokenizer tokenizer = tokenizer();
        String token = tokenizer.tokenizeDataValue("Driver's display");
        String source = "api.create('SA', 'SystemFunction', '" + token + "')\n";
        String restored = GroovySourceDecoder.decode(source, tokenizer::detokenizeText);
        assertEquals(
                "Proposed change.\n```groovy\n" + restored + "```",
                AgentResponseDecoder.decode(
                        "Proposed change.\n```groovy\n" + source + "```",
                        tokenizer::detokenizeText));
        assertEquals(
                "Proposed change.\r\n```Groovy \t\r\n" + restored + "```",
                AgentResponseDecoder.decode(
                        "Proposed change.\r\n```Groovy \t\r\n" + source + "```",
                        tokenizer::detokenizeText));
        String protocol =
                "{\"kind\":\"proposal\",\"message\":\"Create\",\"script\":" + json(source) + "}";
        assertEquals(
                AgentResponseDecoder.decode(protocol, tokenizer::detokenizeText),
                AgentResponseDecoder.decode(
                        "```agent\r\n" + protocol + "\r\n```", tokenizer::detokenizeText));
    }

    @Test
    public void unsafeSourceContextsFailBeforeAProposalCanBeReviewed() {
        Tokenizer tokenizer = tokenizer();
        String token = tokenizer.tokenizeDataValue("Driver's display");
        for (String source :
                List.of(
                        "api.create('SA', 'SystemFunction', " + token + ")",
                        "api.create('SA', 'SystemFunction', /" + token + "/)",
                        "api.create('SA', 'SystemFunction', $/" + token + "/$)",
                        "api.create('SA', 'SystemFunction', \"" + token + " ${1 + 1}",
                        "api.create('SA', 'SystemFunction', '" + token + ")")) {
            assertThrows(
                    source,
                    IllegalArgumentException.class,
                    () -> GroovySourceDecoder.decode(source, tokenizer::detokenizeText));
        }
    }

    @Test
    public void commentValuesCannotInjectStatementsAndArithmeticIsUnchanged() {
        Tokenizer tokenizer = tokenizer();
        String token =
                tokenizer.tokenizeDataValue("name\napi.create('unsafe','unsafe','injected')");
        String source =
                "// "
                        + token
                        + "\n/* "
                        + token
                        + " */\ndef ratio = 4 / 2; api.create('SA', 'SystemFunction', '"
                        + token
                        + "')";
        String restored = GroovySourceDecoder.decode(source, tokenizer::detokenizeText);
        assertTrue(restored.startsWith("// " + token + "\n/* " + token + " */"));
        assertEquals(1, evaluate(restored).calls);
    }

    private static Tokenizer tokenizer() {
        return Tokenizer.forSession("source-restoration-" + UUID.randomUUID());
    }

    private static ApiStub evaluate(String source) {
        ApiStub api = new ApiStub();
        Binding binding = new Binding();
        binding.setProperty("api", api);
        new GroovyShell(GroovySourceDecoderTest.class.getClassLoader(), binding).evaluate(source);
        return api;
    }

    private static String json(String text) {
        return "\""
                + text.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                        .replace("\t", "\\t")
                + "\"";
    }

    public static final class ApiStub {
        String value;
        int calls;

        public Object create(String layer, String type, Object name) {
            calls++;
            value = name.toString();
            return value;
        }
    }
}
