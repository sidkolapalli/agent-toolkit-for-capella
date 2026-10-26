package com.sidkolapalli.capella.ai.agent;

import static org.junit.Assert.*;

import org.eclipse.core.runtime.IStatus;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ModelVerificationTest {
    @Test
    public void comparesExistingErrorsWithoutClaimingWholeModelValidity() {
        var before = snapshot(1, 7, List.of(error("Existing allocation error")), false);
        var after = snapshot(1, 9, List.of(error("Existing allocation error")), false);
        var report = ModelVerification.compare(before, after);
        assertEquals(ModelVerification.Verdict.NO_ADDITIONAL_ERROR_DIAGNOSTICS, report.verdict());
        assertEquals(0, report.additionalErrorCount());
        assertTrue(report.markdownSummary().contains("1 → 1 errors"));
        assertTrue(
                report.markdownSummary()
                        .contains("does not verify requirements, safety or compliance"));
    }

    @Test
    public void equalCountsCannotHideAReplacedErrorDiagnostic() {
        var report =
                ModelVerification.compare(
                        snapshot(1, 0, List.of(error("Before")), false),
                        snapshot(1, 0, List.of(error("After")), false));
        assertEquals(ModelVerification.Verdict.ADDITIONAL_ERROR_DIAGNOSTICS, report.verdict());
        assertEquals(1, report.additionalErrorCount());
        assertEquals("After", report.additionalErrors().get(0).message());
    }

    @Test
    public void duplicateMessagesAreComparedAsAMultiset() {
        var report =
                ModelVerification.compare(
                        snapshot(1, 0, List.of(error("Same")), false),
                        snapshot(2, 0, List.of(error("Same"), error("Same")), false));
        assertEquals(1, report.additionalErrorCount());
    }

    @Test
    public void truncatedWarningDetailsStillAllowCompleteErrorComparison() {
        var before = snapshot(0, 517, List.of(), true);
        var after = snapshot(0, 520, List.of(), true);
        assertTrue(before.errorDetailsComplete());
        assertTrue(before.truncated());
        assertEquals(
                ModelVerification.Verdict.NO_ADDITIONAL_ERROR_DIAGNOSTICS,
                ModelVerification.compare(before, after).verdict());
    }

    @Test
    public void missingErrorDetailsCannotProduceANoNewErrorsVerdict() {
        var report =
                ModelVerification.compare(
                        snapshot(2, 0, List.of(error("Sample")), true),
                        snapshot(2, 0, List.of(error("Sample")), true));
        assertEquals(ModelVerification.Verdict.ERROR_DETAILS_INCOMPLETE, report.verdict());
        assertEquals(-1, report.additionalErrorCount());
    }

    /**
     * Stress test: full validation of a 312,000-object model froze Capella for 48 s on the UI
     * thread.
     */
    @Test
    public void workerRequestsValidateOnTheWorkerUnderAnExclusiveRead() throws Exception {
        org.eclipse.swt.widgets.Display ui = org.eclipse.swt.widgets.Display.getDefault();
        var domain =
                org.eclipse.emf.transaction.TransactionalEditingDomain.Factory.INSTANCE
                        .createEditingDomain();
        try {
            var session =
                    (org.eclipse.sirius.business.api.session.Session)
                            java.lang.reflect.Proxy.newProxyInstance(
                                    org.eclipse.sirius.business.api.session.Session.class
                                            .getClassLoader(),
                                    new Class<?>[] {
                                        org.eclipse.sirius.business.api.session.Session.class
                                    },
                                    (proxy, method, args) ->
                                            switch (method.getName()) {
                                                case "getTransactionalEditingDomain" -> domain;
                                                case "isOpen" -> true;
                                                case "hashCode" -> System.identityHashCode(proxy);
                                                case "equals" -> proxy == args[0];
                                                default -> null;
                                            });
            var ranOn = new java.util.concurrent.atomic.AtomicReference<Thread>();
            var exclusive = new java.util.concurrent.atomic.AtomicBoolean();
            var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var result =
                        worker.submit(
                                () ->
                                        ModelVerification.snapshot(
                                                session,
                                                ignored -> {
                                                    ranOn.set(Thread.currentThread());
                                                    exclusive.set(
                                                            ((org.eclipse.emf.transaction.impl
                                                                                            .InternalTransactionalEditingDomain)
                                                                                    domain)
                                                                            .getActiveTransaction()
                                                                    != null);
                                                    return Map.of(
                                                            "errors",
                                                            0L,
                                                            "warnings",
                                                            2L,
                                                            "issueCount",
                                                            2L,
                                                            "issues",
                                                            List.of(),
                                                            "truncated",
                                                            false);
                                                }));
                // The UI thread does not dispatch events here: a UI-thread validation could not
                // finish.
                var snapshot = result.get(20, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(snapshot.available());
                assertEquals(2, snapshot.warnings());
                assertNotSame(
                        "Validation must not occupy the UI thread", ui.getThread(), ranOn.get());
                assertTrue("It still reads under the domain's exclusive lock", exclusive.get());
            } finally {
                worker.shutdownNow();
            }
        } finally {
            domain.dispose();
        }
    }

    @Test
    public void unavailableValidatorKeepsCountsUnknown() {
        var absent = ModelVerification.snapshot(null);
        assertFalse(absent.available());
        assertEquals(-1, absent.errors());
        var report = ModelVerification.compare(absent, snapshot(0, 0, List.of(), false));
        assertEquals(ModelVerification.Verdict.UNAVAILABLE, report.verdict());
        assertEquals(-1, report.additionalErrorCount());
        assertTrue(report.markdownSummary().contains("unavailable"));
    }

    @Test
    public void additionalDiagnosticsAreBoundedAndFeedbackContainsOnlyStructuredValues() {
        List<Map<String, Object>> errors = new ArrayList<>();
        for (int index = 0; index < 30; index++) errors.add(error("Error " + index));
        var report =
                ModelVerification.compare(
                        snapshot(0, 0, List.of(), false), snapshot(30, 0, errors, false));
        assertEquals(30, report.additionalErrorCount());
        assertEquals(8, report.additionalErrors().size());
        assertTrue(report.truncated());
        assertEquals("ADDITIONAL_ERROR_DIAGNOSTICS", report.feedback().get("verdict"));
        assertTrue(report.feedback().get("after") instanceof Map);
        assertTrue(report.feedback().get("additional_error_diagnostics") instanceof List);
    }

    @Test
    public void oversizedMessagesDoNotCollapseDistinctDiagnosticsIntoFalseMatches() {
        var before = snapshot(1, 0, List.of(error("a".repeat(4001) + "before")), false);
        var after = snapshot(1, 0, List.of(error("a".repeat(4001) + "after")), false);
        assertTrue(before.available());
        assertFalse(before.errorDetailsComplete());
        assertEquals(
                ModelVerification.Verdict.ERROR_DETAILS_INCOMPLETE,
                ModelVerification.compare(before, after).verdict());
    }

    @Test
    public void malformedNativeResultIsUnavailableAndListsAreImmutable() {
        assertFalse(ModelVerification.fromNativeResult(Map.of("errors", 0)).available());
        var before = snapshot(1, 0, List.of(error("Failure")), false);
        try {
            before.errorDiagnostics().clear();
            fail("Must be immutable");
        } catch (UnsupportedOperationException expected) {
        }
    }

    private static ModelVerification.Snapshot snapshot(
            int errors, int warnings, List<Map<String, Object>> issues, boolean truncated) {
        return ModelVerification.fromNativeResult(
                Map.of(
                        "errors",
                        errors,
                        "warnings",
                        warnings,
                        "issueCount",
                        errors + warnings,
                        "issues",
                        issues,
                        "truncated",
                        truncated));
    }

    private static Map<String, Object> error(String message) {
        return Map.of(
                "severity",
                IStatus.ERROR,
                "plugin",
                "native.validator",
                "code",
                7,
                "message",
                message);
    }
}
