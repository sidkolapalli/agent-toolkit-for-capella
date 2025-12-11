package org.polarsys.capella.ai.model;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.polarsys.capella.ai.model.ActionResult.ErrorCode;

/**
 * Unit tests for ActionResult record and its factory methods.
 */
public class ActionResultTest {

    @Test
    public void testOkWithoutMessage() {
        ActionResult result = ActionResult.ok();
        
        assertTrue("ok() should return success=true", result.success());
        assertFalse("ok() should not be failed", result.failed());
        assertEquals("ok() should have SUCCESS code", ErrorCode.SUCCESS, result.code());
        assertNull("ok() should have null message", result.message());
        assertNull("ok() should have null suggestion", result.suggestion());
        assertTrue("ok() should have empty alternatives", result.alternatives().isEmpty());
    }

    @Test
    public void testOkWithMessage() {
        ActionResult result = ActionResult.ok("Created element successfully");
        
        assertTrue(result.success());
        assertEquals(ErrorCode.SUCCESS, result.code());
        assertEquals("Created element successfully", result.message());
    }

    @Test
    public void testFailWithCodeAndMessage() {
        ActionResult result = ActionResult.fail(ErrorCode.ELEMENT_NOT_FOUND, "Component 'Foo' not found");
        
        assertFalse("fail() should return success=false", result.success());
        assertTrue("fail() should be failed", result.failed());
        assertEquals(ErrorCode.ELEMENT_NOT_FOUND, result.code());
        assertEquals("Component 'Foo' not found", result.message());
        assertNull(result.suggestion());
        assertTrue(result.alternatives().isEmpty());
    }

    @Test
    public void testFailWithSuggestion() {
        ActionResult result = ActionResult.fail(
            ErrorCode.PARENT_NOT_FOUND,
            "Parent 'Container' not found",
            "Check if the parent element exists"
        );
        
        assertFalse(result.success());
        assertEquals(ErrorCode.PARENT_NOT_FOUND, result.code());
        assertEquals("Parent 'Container' not found", result.message());
        assertEquals("Check if the parent element exists", result.suggestion());
    }

    @Test
    public void testFailWithAlternatives() {
        List<String> alternatives = List.of("SensorA", "SensorB", "SensorC");
        ActionResult result = ActionResult.failWithAlternatives(
            ErrorCode.ELEMENT_NOT_FOUND,
            "Sensor 'Sensr' not found",
            alternatives
        );
        
        assertFalse(result.success());
        assertEquals(ErrorCode.ELEMENT_NOT_FOUND, result.code());
        assertTrue(result.hasAlternatives());
        assertEquals(3, result.alternatives().size());
        assertTrue(result.alternatives().contains("SensorA"));
        assertNotNull("Should auto-generate suggestion", result.suggestion());
        assertTrue(result.suggestion().contains("Did you mean"));
    }

    @Test
    public void testFailWithAlternativesAndCustomSuggestion() {
        List<String> alternatives = List.of("ComponentA", "ComponentB");
        ActionResult result = ActionResult.failWithAlternatives(
            ErrorCode.REFERENCE_NOT_FOUND,
            "Source component 'Comp' not found",
            "Try using the full component name",
            alternatives
        );
        
        assertFalse(result.success());
        assertEquals(ErrorCode.REFERENCE_NOT_FOUND, result.code());
        assertEquals("Try using the full component name", result.suggestion());
        assertEquals(2, result.alternatives().size());
    }

    @Test
    public void testIsNotFound() {
        assertTrue(ActionResult.fail(ErrorCode.ELEMENT_NOT_FOUND, "").isNotFound());
        assertTrue(ActionResult.fail(ErrorCode.PARENT_NOT_FOUND, "").isNotFound());
        assertTrue(ActionResult.fail(ErrorCode.REFERENCE_NOT_FOUND, "").isNotFound());
        assertFalse(ActionResult.fail(ErrorCode.CONSTRAINT_VIOLATION, "").isNotFound());
        assertFalse(ActionResult.fail(ErrorCode.SESSION_ERROR, "").isNotFound());
    }

    @Test
    public void testIsAmbiguous() {
        assertTrue(ActionResult.fail(ErrorCode.DUPLICATE_AMBIGUOUS, "").isAmbiguous());
        assertFalse(ActionResult.fail(ErrorCode.ELEMENT_NOT_FOUND, "").isAmbiguous());
    }

    @Test
    public void testImmutableAlternatives() {
        List<String> original = new java.util.ArrayList<>();
        original.add("A");
        original.add("B");
        
        ActionResult result = ActionResult.failWithAlternatives(
            ErrorCode.ELEMENT_NOT_FOUND,
            "Not found",
            original
        );
        
        // Modify original list
        original.add("C");
        
        // Result should not be affected
        assertEquals(2, result.alternatives().size());
        
        // Result alternatives should be immutable
        try {
            result.alternatives().add("D");
            fail("Should throw UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // Expected
        }
    }

    @Test
    public void testFormatForLLM_Success() {
        ActionResult result = ActionResult.ok("Created Mission 'M1'");
        String formatted = result.formatForLLM();
        
        assertTrue(formatted.contains("SUCCESS"));
        assertTrue(formatted.contains("Created Mission"));
    }

    @Test
    public void testFormatForLLM_Failure() {
        ActionResult result = ActionResult.failWithAlternatives(
            ErrorCode.ELEMENT_NOT_FOUND,
            "Component 'Sensr' not found",
            List.of("Sensor", "SensorA")
        );
        String formatted = result.formatForLLM();
        
        assertTrue(formatted.contains("FAILED"));
        assertTrue(formatted.contains("ELEMENT_NOT_FOUND"));
        assertTrue(formatted.contains("Sensr"));
        assertTrue(formatted.contains("Alternatives:"));
    }

    @Test
    public void testNullAlternativesHandled() {
        ActionResult result = ActionResult.failWithAlternatives(
            ErrorCode.ELEMENT_NOT_FOUND,
            "Not found",
            "No suggestion",
            null
        );
        
        assertNotNull(result.alternatives());
        assertTrue(result.alternatives().isEmpty());
    }

    @Test
    public void testEmptyAlternativesNoSuggestion() {
        ActionResult result = ActionResult.failWithAlternatives(
            ErrorCode.ELEMENT_NOT_FOUND,
            "Not found",
            List.of()
        );
        
        // Empty alternatives should result in null suggestion
        assertNull(result.suggestion());
    }
}
