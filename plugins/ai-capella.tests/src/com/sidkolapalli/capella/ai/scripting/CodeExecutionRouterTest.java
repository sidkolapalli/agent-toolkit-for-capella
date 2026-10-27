package com.sidkolapalli.capella.ai.scripting;

import static org.junit.Assert.*;

import org.junit.Test;

public class CodeExecutionRouterTest {

    @Test
    public void testPromptMentionsAllocationAndLayerCreate() {
        String prompt = CodeExecutionRouter.buildCodeExecutionPrompt(null);

        assertTrue(prompt.contains("api.allocate(function, component)"));
        assertTrue(prompt.contains("api.createIn(\"LA\", \"LogicalFunction\", \"Name\")"));
        assertTrue(prompt.contains("api.create(\"LA\", \"LogicalFunction\", \"Name\")"));
        assertTrue(prompt.contains("api.validate()"));
        assertTrue(prompt.contains("api.reset()"));
        assertTrue(prompt.contains("api.describe(element)"));
        assertTrue(prompt.contains("api.ops"));
        assertTrue(prompt.contains("api.ops.transition"));
    }
}
