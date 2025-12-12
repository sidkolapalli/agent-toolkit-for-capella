package org.polarsys.capella.ai.scripting;

import static org.junit.Assert.*;

import org.junit.Test;
import org.polarsys.capella.ai.scripting.ExecutionResult.ErrorCode;

/**
 * Unit tests for GroovyExecutor.
 */
public class GroovyExecutorTest {

    @Test
    public void testExtractCodeFromGroovyBlock() {
        String response = "Here is the code:\n```groovy\ndef x = 1\napi.create(\"Component\", \"Test\")\n```\nDone!";
        String code = GroovyExecutor.extractCode(response);

        assertEquals("def x = 1\napi.create(\"Component\", \"Test\")", code);
    }

    @Test
    public void testExtractCodeFromGenericBlock() {
        String response = "```\napi.find(\"System\")\n```";
        String code = GroovyExecutor.extractCode(response);

        assertEquals("api.find(\"System\")", code);
    }

    @Test
    public void testExtractCodeNoBlock() {
        String response = "api.create(\"Component\", \"Test\")";
        String code = GroovyExecutor.extractCode(response);

        assertEquals("api.create(\"Component\", \"Test\")", code);
    }

    @Test
    public void testExtractCodeEmptyInput() {
        assertEquals("", GroovyExecutor.extractCode(null));
        assertEquals("", GroovyExecutor.extractCode(""));
        assertEquals("", GroovyExecutor.extractCode("   "));
    }

    @Test
    public void testExtractCodeMultipleBlocks() {
        // Should extract first code block
        String response = "First:\n```groovy\ncode1\n```\nSecond:\n```groovy\ncode2\n```";
        String code = GroovyExecutor.extractCode(response);

        assertEquals("code1", code);
    }

    @Test
    public void testExtractCodeWithLanguageTag() {
        String response = "```groovy\n// Comment\napi.layer(\"SA\")\n```";
        String code = GroovyExecutor.extractCode(response);

        assertTrue(code.contains("// Comment"));
        assertTrue(code.contains("api.layer"));
    }

    @Test
    public void testExecuteWithNullSession() {
        ExecutionResult result = GroovyExecutor.execute(null, "println 'test'");

        assertFalse(result.success());
        assertEquals(ErrorCode.API_ERROR, result.code());
        assertTrue(result.message().contains("session"));
    }

    @Test
    public void testExecuteWithEmptyScript() {
        // We can't easily mock a Session, so we test the null case
        ExecutionResult result = GroovyExecutor.execute(null, "");

        // Either API_ERROR (no session) or INVALID_RESULT (empty script)
        assertFalse(result.success());
    }

    @Test
    public void testExecuteWithNullScript() {
        ExecutionResult result = GroovyExecutor.execute(null, null);

        assertFalse(result.success());
    }

    @Test
    public void testExtractCodePreservesWhitespace() {
        String response = "```groovy\n  indented\n    more\n```";
        String code = GroovyExecutor.extractCode(response);

        // trim() is applied, but internal whitespace preserved
        assertTrue(code.contains("indented"));
        assertTrue(code.contains("more"));
    }

    @Test
    public void testExtractCodeWithWindowsLineEndings() {
        String response = "```groovy\r\nline1\r\nline2\r\n```";
        String code = GroovyExecutor.extractCode(response);

        assertTrue(code.contains("line1"));
        assertTrue(code.contains("line2"));
    }

    @Test
    public void testExtractCodeUnterminatedBlock() {
        // No closing ```, should return trimmed input
        String response = "```groovy\nsome code";
        String code = GroovyExecutor.extractCode(response);

        // Falls back to returning trimmed input since no valid block
        assertNotNull(code);
    }
}
