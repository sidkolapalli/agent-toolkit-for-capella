package org.polarsys.capella.ai.agent;

import static org.junit.Assert.*;

import org.junit.Test;
import org.polarsys.capella.ai.privacy.Tokenizer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Source-aware outbound history protection; no proposed source is compiled or evaluated. */
public class ProposalHistoryPrivacyTest {
    @Test
    public void quotedLiteralFormsProtectCompleteDecodedValuesAndRestoreTheirExactData() {
        for (String delimiter : List.of("'", "\"", "'''", "\"\"\"")) {
            for (String value :
                    List.of(
                            "Driver's Battery",
                            "Driver's 'spare' Battery",
                            "Double \"control\"",
                            "C:\\vehicle\\new",
                            "first\nsecond\rthird\tlast",
                            "$expr ${privateValue}",
                            "''' and \"\"\"",
                            "🚗 Driver's Battery")) {
                Tokenizer tokenizer = tokenizer();
                String token = tokenizer.tokenizeDataValue(value);
                String original =
                        GroovySourceDecoder.decode(
                                "// 🔋 harmless context\napi.create('SA', 'SystemFunction', "
                                        + delimiter
                                        + token
                                        + delimiter
                                        + ")",
                                tokenizer::detokenizeText);
                String protectedSource =
                        GroovySourceDecoder.encode(original, tokenizer::tokenizeDataValue);
                assertFalse(protectedSource, protectedSource.contains("Battery"));
                assertFalse(protectedSource, protectedSource.contains("control"));
                assertFalse(protectedSource, protectedSource.contains("vehicle"));
                assertFalse(protectedSource, protectedSource.contains("privateValue"));
                assertTrue(tokenizer.tokenToValueSnapshot().containsValue(value));
                String restored =
                        GroovySourceDecoder.decode(protectedSource, tokenizer::detokenizeText);
                assertEquals(
                        original.substring(original.indexOf('\n') + 1),
                        restored.substring(restored.indexOf('\n') + 1));
            }
        }
    }

    @Test
    public void interpolatedTextProtectsEscapedNamesWithoutChangingExpressionStructure() {
        Tokenizer tokenizer = tokenizer();
        String token = tokenizer.tokenizeDataValue("Driver's \"display\" $expr\n🚗 next");
        String original =
                GroovySourceDecoder.decode(
                        "def text = \"prefix\\t${1 + 1} " + token + " suffix ${'fixed data'}\"",
                        tokenizer::detokenizeText);
        String encoded = GroovySourceDecoder.encode(original, tokenizer::tokenizeDataValue);
        assertFalse(encoded, encoded.contains("Driver"));
        assertFalse(encoded, encoded.contains("display"));
        assertFalse(encoded, encoded.contains("next"));
        assertTrue(encoded, encoded.contains("${1 + 1}"));
        assertEquals(original, GroovySourceDecoder.decode(encoded, tokenizer::detokenizeText));
    }

    @Test
    public void nativeAndDirectHistoryFormatsProtectTheSameSourceIncludingEmbeddedBackticks() {
        Tokenizer tokenizer = tokenizer();
        String token = tokenizer.tokenizeDataValue("Driver's Battery\\new\n$expr 🚗");
        String source =
                GroovySourceDecoder.decode(
                        "api.find('" + token + "')\nreturn [label: 'literal ``` embedded']\n",
                        tokenizer::detokenizeText);
        String json =
                AgentJson.write(
                        Map.of(
                                "kind",
                                "proposal",
                                "message",
                                "Proposed change",
                                "script",
                                source,
                                "stepIds",
                                List.of("edit")));
        String encoded = encode(json, tokenizer);
        assertFalse(encoded, encoded.contains("Battery"));
        assertFalse(encoded, encoded.contains("expr"));
        assertFalse(encoded, encoded.contains("new"));
        assertEquals(json, AgentResponseDecoder.decode(encoded, tokenizer::detokenizeText));
        String history =
                "Proposed change\r\n```Groovy \t\r\n" + source + "```\r\nReview separately.";
        String encodedHistory = encode(history, tokenizer);
        assertFalse(encodedHistory, encodedHistory.contains("Battery"));
        assertFalse(encodedHistory, encodedHistory.contains("expr"));
        assertTrue(encodedHistory, encodedHistory.contains("```Groovy \t\r\n"));
        // The source-aware encoder, rather than the legacy incoming fence parser, handles embedded
        // backticks.
        String encodedSource = encodedHistory.substring(encodedHistory.indexOf('\n') + 1);
        encodedSource =
                encodedSource.substring(
                        encodedSource.indexOf('\n') + 1, encodedSource.lastIndexOf("```"));
        assertEquals(source, GroovySourceDecoder.decode(encodedSource, tokenizer::detokenizeText));
        assertTrue(encodedHistory.endsWith("Review separately."));
    }

    @Test
    public void jsonFencedHistoryAndSourceErrorsUseOnlyASafeOmissionWithoutProseSourceFallback() {
        Tokenizer tokenizer = tokenizer();
        String nameToken = tokenizer.tokenizeDataValue("Driver's Battery");
        String source =
                GroovySourceDecoder.decode(
                        "api.find('" + nameToken + "')", tokenizer::detokenizeText);
        String json =
                AgentJson.write(Map.of("kind", "proposal", "message", "Create", "script", source));
        String encoded = encode("```agent\n" + json + "\n```", tokenizer);
        assertFalse(encoded, encoded.contains("Battery"));
        assertEquals(json, AgentResponseDecoder.decode(encoded, tokenizer::detokenizeText));
        for (String unsafe :
                List.of(
                        "api.find('Driver\\'s Battery)",
                        "api.find(/Driver's Battery/)",
                        "api.find($/Driver's Battery/$)",
                        "api.find(\"Driver's Battery ${1}")) {
            String invalid =
                    AgentJson.write(
                            Map.of(
                                    "kind",
                                    "proposal",
                                    "message",
                                    "Create",
                                    "script",
                                    unsafe,
                                    "stepIds",
                                    List.of("edit")));
            String protectedHistory = encode(invalid, tokenizer);
            assertFalse(protectedHistory, protectedHistory.contains("Driver"));
            assertFalse(protectedHistory, protectedHistory.contains("Battery"));
            assertFalse(protectedHistory, protectedHistory.contains("literal boundary"));
            assertTrue(protectedHistory, protectedHistory.contains("source omitted for privacy"));
            @SuppressWarnings("unchecked")
            Map<String, Object> protocol =
                    (Map<String, Object>) AgentJson.parse(protectedHistory, 200000);
            assertEquals("proposal", protocol.get("kind"));
            assertEquals(List.of("edit"), protocol.get("stepIds"));
            assertEquals("Create", protocol.get("message"));
        }
        String incomplete = encode("Draft\n```groovy\napi.find('Driver\\'s Battery')", tokenizer);
        assertFalse(incomplete, incomplete.contains("Driver"));
        assertFalse(incomplete, incomplete.contains("Battery"));
        assertTrue(incomplete, incomplete.startsWith("Draft\n"));
        assertTrue(incomplete.contains("source omitted for privacy"));
    }

    @Test
    public void encodingOmitsPrivateCommentsAndNeverRunsProposedExpressionsOrInitializers() {
        Tokenizer tokenizer = tokenizer();
        String key = "capella.ai.history-privacy." + UUID.randomUUID();
        assertNull(System.getProperty(key));
        String source =
                "// Driver's Battery\n/* Driver's private Battery\ncontinued */\n"
                        + "class Proposed { static { System.setProperty('"
                        + key
                        + "', 'executed') } }\n"
                        + "def ratio = 4 / 2; System.setProperty('"
                        + key
                        + "', 'executed')";
        String encoded = GroovySourceDecoder.encode(source, tokenizer::tokenizeDataValue);
        assertFalse(encoded, encoded.contains("Battery"));
        assertFalse(encoded, encoded.contains("private"));
        assertTrue(encoded, encoded.contains("def ratio = 4 / 2;"));
        assertTrue(encoded, encoded.contains("static { System.setProperty("));
        assertEquals(
                source.chars().filter(value -> value == '\n').count(),
                encoded.chars().filter(value -> value == '\n').count());
        assertNull("Only lexical parsing is allowed", System.getProperty(key));
    }

    @Test
    public void prefixBeforeStructuredHistoryUsesTheProseEncoderIncludingQuotedPrivateData() {
        Tokenizer tokenizer = tokenizer();
        String label = "Driver's Battery\\new $expr 🚗";
        String token = tokenizer.tokenizeDataValue(label);
        String prefix = "COMPILE-ONLY FEEDBACK for " + label + "\n";
        String json = "{\"kind\":\"final\",\"message\":\"Compilation check only\"}";
        String encoded = encode(prefix + json, tokenizer);
        assertTrue(encoded, encoded.startsWith("COMPILE-ONLY FEEDBACK for " + token + "\n"));
        assertFalse(encoded, encoded.contains("Battery"));
        assertFalse(encoded, encoded.contains("expr"));
        assertTrue(encoded.endsWith(json));
        String escapedPrefix = "Prefix contains 'Driver\\'s Battery'\n";
        String protectedPrefix =
                AgentResponseDecoder.encodeMessage(
                        escapedPrefix + json,
                        text -> text.replace(escapedPrefix, "PROTECTED PREFIX\n"),
                        text -> text);
        assertEquals("PROTECTED PREFIX\n" + json, protectedPrefix);
    }

    @Test
    public void protocolIdentityAndUnrelatedProseEncodingRemainUnchanged() {
        Tokenizer tokenizer = tokenizer();
        tokenizer.tokenizeDataValue("Secret motor");
        String wire =
                "AUTHORITATIVE CAPELLA EXECUTION OUTCOME\n"
                    + "{\"feedback\":\"Created Secret"
                    + " motor\",\"saved\":true,\"completedStepIds\":[\"edit\"]}";
        String encoded = encode(wire, tokenizer);
        assertFalse(encoded.contains("Secret motor"));
        assertTrue(encoded.contains("\"saved\":true"));
        assertTrue(encoded.contains("\"completedStepIds\":[\"edit\"]"));
        assertEquals("plain unrelated text", encode("plain unrelated text", tokenizer));
        assertEquals("Unrelated Secret motorbike", encode("Unrelated Secret motorbike", tokenizer));
    }

    private static String encode(String source, Tokenizer tokenizer) {
        return AgentResponseDecoder.encodeMessage(
                source, tokenizer::tokenizeKnownText, tokenizer::tokenizeDataValue);
    }

    private static Tokenizer tokenizer() {
        return Tokenizer.forSession("proposal-history-" + UUID.randomUUID());
    }
}
