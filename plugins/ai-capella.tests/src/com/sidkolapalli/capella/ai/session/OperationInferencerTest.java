package com.sidkolapalli.capella.ai.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class OperationInferencerTest {

    @Before
    public void setUp() {
        OperationInferencer.cleanup();
    }

    @After
    public void tearDown() {
        OperationInferencer.cleanup();
    }

    @Test
    public void testSingleCreate() {
        OperationInferencer.begin();
        OperationInferencer.recordCreate("SystemFunction", "Func 1");
        String label = OperationInferencer.endAndGenerateLabel();

        assertEquals("Create SystemFunction 'Func 1'", label);
    }

    @Test
    public void testMultipleCreateSameType() {
        OperationInferencer.begin();
        OperationInferencer.recordCreate("LogicalComponent", "Comp 1");
        OperationInferencer.recordCreate("LogicalComponent", "Comp 2");
        OperationInferencer.recordCreate("LogicalComponent", "Comp 3");
        String label = OperationInferencer.endAndGenerateLabel();

        assertEquals("Create 3 LogicalComponents", label);
    }

    @Test
    public void testMultipleCreateDifferentTypes() {
        OperationInferencer.begin();
        OperationInferencer.recordCreate("SystemFunction", "Func 1");
        OperationInferencer.recordCreate("SystemComponent", "Comp 1");
        String label = OperationInferencer.endAndGenerateLabel();

        assertEquals("Create 2 elements", label);
    }

    @Test
    public void testMixedOperations() {
        OperationInferencer.begin();
        OperationInferencer.recordCreate("SystemFunction", "Func 1");
        OperationInferencer.recordCreate("SystemFunction", "Func 2");
        OperationInferencer.recordLink("SystemFunction", "SystemFunction", "FunctionalExchange");
        String label = OperationInferencer.endAndGenerateLabel();

        // Expected: mixed-operation summary with generic counts
        assertTrue(label.contains("Create 2 elements"));
        assertTrue(label.contains("link 1 relationship"));
    }

    @Test
    public void testNestedTracking() {
        // Outer transaction
        assertTrue(OperationInferencer.begin());

        OperationInferencer.recordCreate("SystemFunction", "Outer");

        // Inner transaction (e.g., nested method call)
        // begin() returns false for nested calls
        assertTrue(!OperationInferencer.begin());

        OperationInferencer.recordCreate("SystemFunction", "Inner");

        // Inner end() returns null
        assertNull(OperationInferencer.endAndGenerateLabel());

        // Outer end() returns combined label
        String label = OperationInferencer.endAndGenerateLabel();
        assertNotNull(label);
        assertEquals("Create 2 SystemFunctions", label);
    }

    @Test
    public void testTruncation() {
        OperationInferencer.begin();
        OperationInferencer.recordCreate(
                "SystemFunction",
                "Very Long Name That Should Cause The Label To Be Truncated Because It Exceeds The"
                    + " Limit");
        String label = OperationInferencer.endAndGenerateLabel();

        assertTrue(label.length() <= 60);
        assertTrue(label.endsWith("..."));
    }
}
