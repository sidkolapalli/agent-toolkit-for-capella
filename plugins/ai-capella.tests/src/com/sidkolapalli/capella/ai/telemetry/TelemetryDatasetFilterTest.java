package com.sidkolapalli.capella.ai.telemetry;

import static org.junit.Assert.*;

import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import com.sidkolapalli.capella.ai.preferences.LLMPreferences;
import com.sidkolapalli.capella.ai.scripting.ExecutionResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class TelemetryDatasetFilterTest {

    @Test
    public void testDatasetEntrySkippedOnErrorLogs() throws Exception {
        String originalHome = System.getProperty("user.home");
        boolean originalTelemetry = LLMPreferences.isTelemetryEnabled();
        boolean originalDropOnError = LLMPreferences.isDatasetDropOnErrorEnabled();
        boolean originalDropOnWarn = LLMPreferences.isDatasetDropOnWarnEnabled();

        Path home = Files.createTempDirectory("capella-ai-home-");
        try {
            System.setProperty("user.home", home.toString());
            LLMPreferences.setTelemetryEnabled(true);
            LLMPreferences.setDatasetDropOnErrorEnabled(true);
            LLMPreferences.setDatasetDropOnWarnEnabled(false);

            ExecutionResult okButLogicalFailure =
                    ExecutionResult.success(null, List.of("[ERROR] Target not found"), 5);

            invokeRecordDatasetEntry(
                    "Connect \"A\" to \"B\"",
                    "log.error(\"Target not found\")",
                    okButLogicalFailure);

            Path dataset = home.resolve(".capella-ai").resolve("dataset.jsonl");
            assertFalse(
                    "Dataset entry should be skipped when logs contain [ERROR]",
                    Files.exists(dataset));
        } finally {
            System.setProperty("user.home", originalHome);
            LLMPreferences.setTelemetryEnabled(originalTelemetry);
            LLMPreferences.setDatasetDropOnErrorEnabled(originalDropOnError);
            LLMPreferences.setDatasetDropOnWarnEnabled(originalDropOnWarn);
        }
    }

    @Test
    public void testDatasetEntrySkippedOnWarnLogsWhenEnabled() throws Exception {
        String originalHome = System.getProperty("user.home");
        boolean originalTelemetry = LLMPreferences.isTelemetryEnabled();
        boolean originalDropOnError = LLMPreferences.isDatasetDropOnErrorEnabled();
        boolean originalDropOnWarn = LLMPreferences.isDatasetDropOnWarnEnabled();

        Path home = Files.createTempDirectory("capella-ai-home-");
        try {
            System.setProperty("user.home", home.toString());
            LLMPreferences.setTelemetryEnabled(true);
            LLMPreferences.setDatasetDropOnErrorEnabled(false);
            LLMPreferences.setDatasetDropOnWarnEnabled(true);

            ExecutionResult okWithWarning =
                    ExecutionResult.success(null, List.of("[WARN] Element already exists"), 5);

            invokeRecordDatasetEntry(
                    "Create \"X\"", "log.warn(\"Element already exists\")", okWithWarning);

            Path dataset = home.resolve(".capella-ai").resolve("dataset.jsonl");
            assertFalse(
                    "Dataset entry should be skipped when logs contain [WARN] and filtering is"
                        + " enabled",
                    Files.exists(dataset));
        } finally {
            System.setProperty("user.home", originalHome);
            LLMPreferences.setTelemetryEnabled(originalTelemetry);
            LLMPreferences.setDatasetDropOnErrorEnabled(originalDropOnError);
            LLMPreferences.setDatasetDropOnWarnEnabled(originalDropOnWarn);
        }
    }

    private static void invokeRecordDatasetEntry(String prompt, String code, ExecutionResult result)
            throws Exception {
        Bundle aiBundle = FrameworkUtil.getBundle(LLMPreferences.class);
        assertNotNull("Expected the AI Assistant for Capella bundle for LLMPreferences", aiBundle);
        Class<?> telemetry = aiBundle.loadClass("com.sidkolapalli.capella.ai.telemetry.Telemetry");
        telemetry
                .getMethod("recordDatasetEntry", String.class, String.class, ExecutionResult.class)
                .invoke(null, prompt, code, result);
    }
}
