package org.polarsys.capella.ai.scripting;

import static org.junit.Assert.*;

import org.junit.Test;
import org.polarsys.capella.ai.scripting.ExecutionResult.ErrorCode;

import java.util.List;

/** Unit tests for ExecutionResult. */
public class ExecutionResultTest {

    @Test
    public void testSuccessWithValue() {
        ExecutionResult result = ExecutionResult.success("test value");

        assertTrue(result.success());
        assertFalse(result.failed());
        assertEquals("test value", result.value());
        assertEquals(ErrorCode.SUCCESS, result.code());
        assertNull(result.message());
        assertNull(result.stackTrace());
        assertTrue(result.logs().isEmpty());
    }

    @Test
    public void testSuccessWithLogsAndDuration() {
        List<String> logs = List.of("log1", "log2");
        ExecutionResult result = ExecutionResult.success("value", logs, 150);

        assertTrue(result.success());
        assertEquals("value", result.value());
        assertEquals(2, result.logs().size());
        assertEquals("log1", result.logs().get(0));
        assertEquals(150, result.durationMs());
    }

    @Test
    public void testFailWithCodeAndMessage() {
        ExecutionResult result =
                ExecutionResult.fail(ErrorCode.RUNTIME_ERROR, "Something went wrong");

        assertFalse(result.success());
        assertTrue(result.failed());
        assertNull(result.value());
        assertEquals(ErrorCode.RUNTIME_ERROR, result.code());
        assertEquals("Something went wrong", result.message());
        assertTrue(result.isRuntimeError());
        assertFalse(result.isTimeout());
    }

    @Test
    public void testFailWithStackTrace() {
        ExecutionResult result =
                ExecutionResult.fail(
                        ErrorCode.COMPILATION_ERROR, "Syntax error", "at line 5\nat line 10");

        assertFalse(result.success());
        assertTrue(result.isCompilationError());
        assertEquals("Syntax error", result.message());
        assertNotNull(result.stackTrace());
        assertTrue(result.stackTrace().contains("line 5"));
    }

    @Test
    public void testTimeout() {
        List<String> logs = List.of("partial output");
        ExecutionResult result = ExecutionResult.timeout(30000, logs);

        assertFalse(result.success());
        assertTrue(result.isTimeout());
        assertEquals(ErrorCode.TIMEOUT, result.code());
        assertTrue(result.message().contains("30000"));
        assertEquals(1, result.logs().size());
        assertEquals(30000, result.durationMs());
    }

    @Test
    public void testHasLogs() {
        ExecutionResult withLogs = ExecutionResult.success("v", List.of("log"), 0);
        ExecutionResult noLogs = ExecutionResult.success("v");

        assertTrue(withLogs.hasLogs());
        assertFalse(noLogs.hasLogs());
    }

    @Test
    public void testLogsImmutability() {
        List<String> logs = new java.util.ArrayList<>();
        logs.add("log1");
        ExecutionResult result = ExecutionResult.success("v", logs, 0);

        // Original list modification should not affect result
        logs.add("log2");
        assertEquals(1, result.logs().size());

        // Result logs should be immutable
        assertThrows(
                UnsupportedOperationException.class,
                () -> {
                    result.logs().add("log3");
                });
    }

    @Test
    public void testFormatForLLMSuccess() {
        ExecutionResult result =
                ExecutionResult.success("created element", List.of("Created X"), 100);
        String formatted = result.formatForLLM();

        assertTrue(formatted.contains("EXECUTION_SUCCESS"));
        assertTrue(formatted.contains("created element"));
        assertTrue(formatted.contains("Created X"));
        assertTrue(formatted.contains("100ms"));
    }

    @Test
    public void testFormatForLLMFailure() {
        ExecutionResult result =
                ExecutionResult.fail(
                        ErrorCode.RUNTIME_ERROR,
                        "NullPointerException",
                        "at MyScript.run(line:5)",
                        List.of("starting..."),
                        250);
        String formatted = result.formatForLLM();

        assertTrue(formatted.contains("EXECUTION_FAILED"));
        assertTrue(formatted.contains("RUNTIME_ERROR"));
        assertTrue(formatted.contains("NullPointerException"));
        assertTrue(formatted.contains("line:5"));
        assertTrue(formatted.contains("starting..."));
    }

    @Test
    public void testToString() {
        ExecutionResult success = ExecutionResult.success("result");
        ExecutionResult failure = ExecutionResult.fail(ErrorCode.API_ERROR, "No session");

        assertTrue(success.toString().contains("SUCCESS"));
        assertTrue(failure.toString().contains("API_ERROR"));
        assertTrue(failure.toString().contains("No session"));
    }

    @Test
    public void testNullLogsHandling() {
        // Should handle null logs gracefully
        ExecutionResult result =
                new ExecutionResult(true, "v", ErrorCode.SUCCESS, null, null, null, 0);
        assertNotNull(result.logs());
        assertTrue(result.logs().isEmpty());
    }

    @Test
    public void testAllErrorCodes() {
        // Verify all error codes are distinguishable
        for (ErrorCode code : ErrorCode.values()) {
            ExecutionResult result = ExecutionResult.fail(code, "test");
            assertEquals(code, result.code());
        }
    }
}
