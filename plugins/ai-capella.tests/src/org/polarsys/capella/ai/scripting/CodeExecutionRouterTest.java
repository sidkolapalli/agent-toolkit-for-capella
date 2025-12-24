package org.polarsys.capella.ai.scripting;

import static org.junit.Assert.*;

import org.junit.Test;

public class CodeExecutionRouterTest {

    @Test
    public void testWrapInBatchIfNeededPreservesImports() {
        String code = String.join(
            "\n",
            "import foo.Bar",
            "// header comment",
            "",
            "def x = 1",
            "api.fs.list(\".\")"
        );

        String wrapped = CodeExecutionRouter.wrapInBatchIfNeeded(code, "My label");

        assertTrue(wrapped.startsWith("import foo.Bar\n// header comment\n\napi.batch("));
        assertTrue(wrapped.contains("def x = 1"));
        assertTrue(wrapped.contains("api.fs.list(\".\")"));
        assertTrue(wrapped.contains("api.batch(\"My label\") {"));
    }

    @Test
    public void testWrapInBatchIfNeededSkipsWhenAlreadyBatched() {
        String code = "api.batch(\"Existing\") {\n  api.fs.list(\".\")\n}\n";
        String wrapped = CodeExecutionRouter.wrapInBatchIfNeeded(code, "Ignored label");

        assertEquals(code, wrapped);
    }

    @Test
    public void testWrapInBatchIfNeededEscapesLabel() {
        String code = "api.fs.list(\".\")";
        String labelHint = "Create \"A\" \\\\ B";
        String wrapped = CodeExecutionRouter.wrapInBatchIfNeeded(code, labelHint);

        assertTrue(wrapped.contains("api.batch(\"Create \\\"A\\\" \\\\\\\\ B\") {"));
    }

    @Test
    public void testWrapInBatchIfNeededTruncatesLongLabel() {
        String code = "api.fs.list(\".\")";
        String labelHint = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz";
        String wrapped = CodeExecutionRouter.wrapInBatchIfNeeded(code, labelHint);

        assertTrue(wrapped.contains("api.batch(\"abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcde...\") {"));
    }

    @Test
    public void testPromptMentionsAllocationAndLayerCreate() {
        String prompt = CodeExecutionRouter.buildCodeExecutionPrompt(null);

        assertTrue(
            prompt.contains("api.allocate(function, component)")
        );
        assertTrue(
            prompt.contains("api.createIn(\"LA\", \"LogicalFunction\", \"Name\")")
        );
        assertTrue(
            prompt.contains("api.create(\"LA\", \"LogicalFunction\", \"Name\")")
        );
        assertTrue(
            prompt.contains("api.validate()")
        );
        assertTrue(
            prompt.contains("api.reset()")
        );
    }
}
