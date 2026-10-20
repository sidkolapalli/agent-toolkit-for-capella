package org.polarsys.capella.ai.agent;

import static org.junit.Assert.*;

import org.junit.Test;

public class AgentResponseDecoderTest {
    @Test
    public void resolvesQuotedNamesInsideValuesAndKeepsValidJson() {
        String decoded =
                AgentResponseDecoder.decode(
                        "{\"kind\":\"tool\",\"tool\":\"model_search\",\"arguments\":{\"query\":\"ELEM_0001\"}}",
                        text -> text.replace("ELEM_0001", "Driver's \"control\"\nmode"));
        assertEquals(
                "{\"kind\":\"tool\",\"tool\":\"model_search\",\"arguments\":{\"query\":\"Driver's"
                    + " \\\"control\\\"\\n"
                    + "mode\"}}",
                decoded);
        assertEquals(decoded, AgentResponseDecoder.decode(decoded, text -> text));
    }

    @Test
    public void resolvesJsonFenceAndLegacyRepliesWithoutChangingSource() {
        String decoded =
                AgentResponseDecoder.decode(
                        "```json\n{\"kind\":\"final\",\"message\":\"UUID_0001\"}\n```",
                        text -> text.replace("UUID_0001", "native-id"));
        assertEquals("{\"kind\":\"final\",\"message\":\"native-id\"}", decoded);
        assertEquals(
                "Use native-id",
                AgentResponseDecoder.decode(
                        "Use UUID_0001", text -> text.replace("UUID_0001", "native-id")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMalformedStructuredReply() {
        AgentResponseDecoder.decode("{\"kind\":\"proposal\",\"code\":", text -> text);
    }

    @Test
    public void tokenizationPreservesProtocolButCoversNativeIdsAndNestedResults() {
        var encode =
                (java.util.function.UnaryOperator<String>)
                        text ->
                                text.replace("tool", "ELEM_0001")
                                        .replace("secret-native-id", "UUID_0001")
                                        .replace("Secret Name", "ELEM_0002");
        String outgoing =
                AgentResponseDecoder.encodeMessage(
                        "{\"kind\":\"tool\",\"toolName\":\"model_inspect\",\"arguments\":{\"id\":\"secret-native-id\"},\"stepId\":\"tool\"}",
                        encode);
        assertEquals(
                "{\"kind\":\"tool\",\"toolName\":\"model_inspect\",\"arguments\":{\"id\":\"UUID_0001\"},\"stepId\":\"tool\"}",
                outgoing);
        String nested =
                "READ-ONLY TOOL RESULT\n"
                    + "{\"toolName\":\"model_inspect\",\"result\":\"{\\\"id\\\":\\\"secret-native-id\\\",\\\"name\\\":\\\"Secret"
                    + " Name\\\"}\"}";
        String encoded = AgentResponseDecoder.encodeMessage(nested, encode);
        assertTrue(encoded.startsWith("READ-ONLY TOOL RESULT\n"));
        assertFalse(encoded.contains("secret-native-id"));
        assertFalse(encoded.contains("Secret Name"));
        assertTrue(encoded.contains("UUID_0001"));
    }

    @Test
    public void planIdentifiersRemainStableWhenModelNameMatchesProtocolWords() {
        String json =
                "{\"kind\":\"plan\",\"steps\":[{\"id\":\"edit\",\"title\":\"Edit Secret"
                    + " Name\",\"status\":\"pending\",\"type\":\"edit\"}]}";
        assertEquals(
                "{\"kind\":\"plan\",\"steps\":[{\"id\":\"edit\",\"title\":\"Edit"
                    + " ELEM_0001\",\"status\":\"pending\",\"type\":\"edit\"}]}",
                AgentResponseDecoder.encodeMessage(
                        json,
                        text ->
                                text.replace("edit", "ELEM_0002")
                                        .replace("Secret Name", "ELEM_0001")));
    }

    @Test
    public void nativeDiagnosticIdentityStaysStableWhenModelLabelsMatchIt() {
        String json =
                "{\"ok\":false,\"tool\":\"model_inspect\",\"error\":{\"code\":\"invalid_arguments\",\"message\":\"Correct"
                    + " model_inspect arguments\"},\"name\":\"invalid_arguments\"}";
        String encoded =
                AgentResponseDecoder.encodeMessage(
                        json,
                        value ->
                                value.replace("model_inspect", "ELEM_0001")
                                        .replace("invalid_arguments", "ELEM_0002"));
        assertEquals(
                "{\"ok\":false,\"tool\":\"model_inspect\",\"error\":{\"code\":\"invalid_arguments\",\"message\":\"Correct"
                    + " ELEM_0001 arguments\"},\"name\":\"ELEM_0002\"}",
                encoded);
    }

    @Test
    public void actualPrivacyTokenizerRedactsUnquotedNativeNamesAndRoundTripsQuotes() {
        var tokenizer =
                org.polarsys.capella.ai.privacy.Tokenizer.forSession(
                        "agent-privacy-" + java.util.UUID.randomUUID());
        String name = "Secret motor's \"control\"\nmode";
        String json =
                "{\"toolName\":\"model_inspect\",\"result\":{\"name\":\"Secret motor's"
                    + " \\\"control\\\"\\n"
                    + "mode\",\"id\":\"123e4567-e89b-12d3-a456-426614174000\",\"type\":\"LogicalFunction\"}}";
        String encoded =
                AgentResponseDecoder.encodeMessage(
                        json, tokenizer::tokenizeText, tokenizer::tokenizeDataValue);
        assertFalse(encoded.contains("Secret motor"));
        assertFalse(encoded.contains("123e4567"));
        assertTrue(encoded.contains("LogicalFunction"));
        assertEquals(json, AgentResponseDecoder.decode(encoded, tokenizer::detokenizeText));
        assertTrue(tokenizer.tokenToValueSnapshot().containsValue(name));
    }

    @Test
    public void actualPrivacyTokenizerRedactsRegisteredNamesInProseExecutionFeedback() {
        var tokenizer =
                org.polarsys.capella.ai.privacy.Tokenizer.forSession(
                        "agent-feedback-" + java.util.UUID.randomUUID());
        tokenizer.tokenizeDataValue("Secret motor");
        String wire =
                "AUTHORITATIVE CAPELLA EXECUTION OUTCOME\n"
                    + "{\"feedback\":\"Model changes: 1 created. SystemFunction Secret"
                    + " motor\",\"saved\":true}";
        String encoded =
                AgentResponseDecoder.encodeMessage(
                        wire, tokenizer::tokenizeKnownText, tokenizer::tokenizeDataValue);
        assertFalse(encoded.contains("Secret motor"));
        assertTrue(encoded.contains("ELEM_"));
        assertEquals(
                wire,
                AgentResponseDecoder.encodeMessage(
                        encoded, tokenizer::detokenizeText, tokenizer::detokenizeText));
        assertEquals(
                "Unrelated Secret motorbike",
                tokenizer.tokenizeKnownText("Unrelated Secret motorbike"));
    }
}
