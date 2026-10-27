package com.sidkolapalli.capella.ai.privacy;

import static org.junit.Assert.*;

import org.junit.Test;

public class TokenizerTest {

    @Test
    public void testTokenizeDetokenizeQuotedName() {
        Tokenizer t = Tokenizer.forSession("test");
        String in = "Create a component named \"Seat Display\"";
        String tok = t.tokenizeText(in);
        assertTrue(tok.contains("\"ELEM_"));
        assertFalse(tok.contains("Seat Display"));

        String out = t.detokenizeText(tok);
        assertEquals(in, out);
    }

    @Test
    public void testTokenizeDoesNotChangeLayerCodes() {
        Tokenizer t = Tokenizer.forSession("test-layer");
        String in = "api.create(\"LA\", \"LogicalFunction\", \"Render UI\")";
        String tok = t.tokenizeText(in);
        assertTrue(tok.contains("\"LA\""));
        assertTrue(tok.contains("\"LogicalFunction\""));
        assertTrue(tok.contains("\"ELEM_"));
    }

    @Test
    public void testTokenizeUuid() {
        Tokenizer t = Tokenizer.forSession("test-uuid");
        String in = "id=123e4567-e89b-12d3-a456-426614174000";
        String tok = t.tokenizeText(in);
        assertTrue(tok.contains("UUID_"));
        assertFalse(tok.contains("123e4567-e89b-12d3-a456-426614174000"));
        assertEquals(in, t.detokenizeText(tok));
    }
}
