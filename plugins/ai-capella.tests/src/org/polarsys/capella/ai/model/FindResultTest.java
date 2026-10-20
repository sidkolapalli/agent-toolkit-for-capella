package org.polarsys.capella.ai.model;

import static org.junit.Assert.*;

import org.junit.Test;
import org.polarsys.capella.ai.model.ActionResult.ErrorCode;

import java.util.List;

/** Unit tests for FindResult record and its factory methods. */
public class FindResultTest {

    @Test
    public void testSuccessResult() {
        // Note: We can't easily create EObjects without EMF runtime,
        // but we can test with null to verify the logic
        FindResult result = FindResult.success(null);

        // With null element, success() returns false (element != null check)
        assertFalse("success with null element should return false", result.success());
    }

    @Test
    public void testNotFoundWithAlternatives() {
        List<String> similar = List.of("SensorA", "SensorB");
        FindResult result = FindResult.notFound("Component", "Sensr", similar);

        assertTrue("notFound should return failed=true", result.failed());
        assertFalse(result.success());
        assertEquals(ErrorCode.ELEMENT_NOT_FOUND, result.code());
        assertTrue(result.message().contains("Sensr"));
        assertTrue(result.hasAlternatives());
        assertEquals(2, result.alternatives().size());
    }

    @Test
    public void testNotFoundWithCustomMessage() {
        FindResult result =
                FindResult.notFound(
                        "LogicalComponent",
                        "LC1",
                        List.of("LogicalComponent1"),
                        "Custom error: LC1 does not exist in LA layer");

        assertEquals("Custom error: LC1 does not exist in LA layer", result.message());
    }

    @Test
    public void testAmbiguousResult() {
        List<String> names = List.of("Sensor", "Sensor_v2", "Sensor_backup");
        FindResult result = FindResult.ambiguous(null, 3, names);

        assertTrue(result.ambiguous());
        assertEquals(ErrorCode.DUPLICATE_AMBIGUOUS, result.code());
        assertEquals(3, result.matchCount());
        assertEquals(3, result.alternatives().size());
    }

    @Test
    public void testErrorResult() {
        FindResult result = FindResult.error(ErrorCode.SESSION_ERROR, "No active session");

        assertTrue(result.failed());
        assertEquals(ErrorCode.SESSION_ERROR, result.code());
        assertEquals("No active session", result.message());
        assertFalse(result.hasAlternatives());
    }

    @Test
    public void testToActionResult_NotFound() {
        FindResult findResult =
                FindResult.notFound("Component", "Foo", List.of("FooBar", "FooBaz"));

        ActionResult actionResult = findResult.toActionResult();

        assertFalse(actionResult.success());
        assertEquals(ErrorCode.ELEMENT_NOT_FOUND, actionResult.code());
        assertTrue(actionResult.hasAlternatives());
        assertEquals(2, actionResult.alternatives().size());
    }

    @Test
    public void testToActionResult_Error() {
        FindResult findResult = FindResult.error(ErrorCode.INVALID_INPUT, "Name is blank");

        ActionResult actionResult = findResult.toActionResult();

        assertFalse(actionResult.success());
        assertEquals(ErrorCode.INVALID_INPUT, actionResult.code());
        assertEquals("Name is blank", actionResult.message());
    }

    @Test
    public void testImmutableAlternatives() {
        List<String> original = new java.util.ArrayList<>();
        original.add("A");

        FindResult result = FindResult.notFound("Test", "X", original);
        original.add("B");

        assertEquals("Alternatives should be immutable copy", 1, result.alternatives().size());
    }

    @Test
    public void testNullAlternativesHandled() {
        FindResult result = new FindResult(null, ErrorCode.ELEMENT_NOT_FOUND, "msg", 0, null);

        assertNotNull(result.alternatives());
        assertTrue(result.alternatives().isEmpty());
    }
}
