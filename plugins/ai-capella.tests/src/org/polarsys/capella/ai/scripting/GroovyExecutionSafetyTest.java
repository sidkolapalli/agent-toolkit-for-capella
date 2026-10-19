package org.polarsys.capella.ai.scripting;

import static org.junit.Assert.*;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.swt.widgets.Display;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.polarsys.capella.ai.session.SessionSafeguards;
import org.polarsys.capella.ai.model.index.ModelIndex;
import org.polarsys.capella.ai.preferences.LLMPreferences;
import org.polarsys.capella.ai.preferences.SkillPreferences;
import org.polarsys.capella.ai.privacy.Tokenizer;

/** Actual Groovy execution against an in-memory EMF domain and the SWT dispatch queue. */
public class GroovyExecutionSafetyTest {
    private Display display;
    private TransactionalEditingDomain domain;
    private EObject target;
    private EAttribute name;
    private Session session;
    private final AtomicInteger saves = new AtomicInteger();
    private final AtomicBoolean savedInsideWrite = new AtomicBoolean();
    private final AtomicReference<Object> savedName = new AtomicReference<>();
    private final AtomicBoolean failSave = new AtomicBoolean();
    private final AtomicLong saveDelayMs = new AtomicLong();

    @Before
    public void setUp() throws Exception {
        display = Display.getDefault();
        assertSame("The headless test harness must own its SWT display", display, Display.getCurrent());

        EPackage pkg = EcoreFactory.eINSTANCE.createEPackage();
        pkg.setName("safety");
        pkg.setNsPrefix("safety");
        pkg.setNsURI("urn:capella-ai:safety-test");
        EClass type = EcoreFactory.eINSTANCE.createEClass();
        type.setName("NamedElement");
        name = EcoreFactory.eINSTANCE.createEAttribute();
        name.setName("name");
        name.setEType(EcorePackage.Literals.ESTRING);
        type.getEStructuralFeatures().add(name);
        pkg.getEClassifiers().add(type);
        target = pkg.getEFactoryInstance().create(type);
        target.eSet(name, "original");

        Resource resource = new ResourceImpl(URI.createURI("memory:/safety-test.capella"));
        resource.getContents().add(target);
        ResourceSetImpl resourceSet = new ResourceSetImpl();
        resourceSet.getResources().add(resource);
        domain = TransactionalEditingDomain.Factory.INSTANCE.createEditingDomain(resourceSet);
        session = (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[] {Session.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getTransactionalEditingDomain": return domain;
                    case "getSemanticResources": return List.of(resource);
                    case "getSessionResource": return resource;
                    case "isOpen": return true;
                    case "save":
                        saves.incrementAndGet();
                        savedInsideWrite.set(savedInsideWrite.get() || SessionSafeguards.isInWriteContext());
                        if (saveDelayMs.get() != 0) Thread.sleep(saveDelayMs.get());
                        if (failSave.get()) throw new IllegalStateException("disk unavailable");
                        savedName.set(target.eGet(name));
                        return null;
                    case "equals": return proxy == args[0];
                    case "hashCode": return System.identityHashCode(proxy);
                    case "toString": return "InMemorySafetySession";
                    default:
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class) return 0;
                        return null;
                }
            });

        // Warm up the compiler so short timeout tests exercise executed loops, not cold compilation.
        assertTrue(runScript("return 1", 10_000, Map.of()).success());
        saves.set(0);
        savedInsideWrite.set(false);
        savedName.set(null);
        domain.getCommandStack().flush();
    }

    @After
    public void tearDown() {
        if (domain != null) domain.dispose();
        SessionSafeguards.cleanup();
    }

    @Test
    public void nestedBuiltInSkillCompletesInTheParentCommandWithoutDeadlock() throws Exception {
        ExecutionResult result = runScript(
            "api.batch('Nested built-in skill') { api.set(target, 'name', 'before'); "
                + "def child = api.skills.run('runtime-demo', [message: 'hello']); assert child.success(); "
                + "api.set(target, 'name', 'after'); return child }",
            10_000, Map.of());

        assertTrue(result.message(), result.success());
        assertTrue(result.value() instanceof ExecutionResult);
        ExecutionResult child = (ExecutionResult) result.value();
        assertEquals("runtime-demo: hello", child.value());
        assertTrue(child.logs().stream().anyMatch(line -> line.contains("runtime-demo")));
        assertEquals("after", target.eGet(name));
        assertEquals("Parent and child must save as one operation", 1, saves.get());
        assertFalse(savedInsideWrite.get());
        domain.getCommandStack().undo();
        assertEquals("One Undo must restore all parent and child changes", "original", target.eGet(name));
        assertFalse(domain.getCommandStack().canUndo());
    }

    @Test
    public void ignoredNestedSkillFailureRollsBackChildAndParentMutations() throws Exception {
        AtomicBoolean sawFailure = new AtomicBoolean();
        AtomicBoolean continued = new AtomicBoolean();
        try (UserSkillFixture skill = new UserSkillFixture(
            "api.set(args.target, 'name', 'child partial'); throw new IllegalArgumentException('child failed')")) {
            ExecutionResult result = runScript(
                "api.set(target, 'name', 'parent partial'); "
                    + "def child = api.skills.run('nested-safety', [target: target]); "
                    + "sawFailure.set(child.failed()); continued.set(true); "
                    + "target.eSet(target.eClass().getEStructuralFeature('name'), 'parent continued'); return 42",
                10_000, Map.of("sawFailure", sawFailure, "continued", continued));

            assertTrue("The child must return its failure result", sawFailure.get());
            assertTrue("The test intentionally ignores that failure and continues", continued.get());
            assertFalse(result.success());
            assertTrue(result.message(), result.message().contains("nested script failed"));
            assertTrue("The parent failure must retain the child's diagnostic", result.message().contains("child failed"));
            assertEquals("original", target.eGet(name));
            assertEquals(0, saves.get());
            assertFalse(domain.getCommandStack().canUndo());
        }
    }

    @Test
    public void nestedSkillLoopInheritsParentDeadlineAndRollsBack() throws Exception {
        AtomicBoolean entered = new AtomicBoolean();
        try (UserSkillFixture skill = new UserSkillFixture(
            "api.set(args.target, 'name', 'child partial'); args.entered.set(true); while (true) {}")) {
            ExecutionResult result = runScript(
                "api.set(target, 'name', 'parent partial'); "
                    + "api.skills.run('nested-safety', [target: target, entered: entered]); return 42",
                1_000, Map.of("entered", entered));

            assertTrue("The nested loop must execute before its deadline", entered.get());
            assertEquals(ExecutionResult.ErrorCode.TIMEOUT, result.code());
            assertEquals("original", target.eGet(name));
            assertEquals(0, saves.get());
            assertFalse(domain.getCommandStack().canUndo());
        }
    }

    @Test
    public void callerInterruptionCancelsNestedSkillAndParentAtomically() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        try (UserSkillFixture skill = new UserSkillFixture(
            "api.set(args.target, 'name', 'child partial'); args.entered.countDown(); while (true) {}")) {
            FutureTask<ExecutionResult> execution = new FutureTask<>(() -> GroovyExecutor.execute(session,
                "api.set(target, 'name', 'parent partial'); "
                    + "api.skills.run('nested-safety', [target: target, entered: entered])",
                10_000, Map.of("target", target, "entered", entered)));
            Thread caller = new Thread(execution, "GroovySafetyTest-NestedCaller");
            caller.setDaemon(true);
            Thread stopper = new Thread(() -> {
                try {
                    if (entered.await(5, TimeUnit.SECONDS)) caller.interrupt();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "GroovySafetyTest-NestedStopper");
            stopper.setDaemon(true);
            stopper.start();
            caller.start();
            ExecutionResult result = pumpUntilFinished(execution);
            stopper.join(1_000);

            assertEquals(0, entered.getCount());
            assertEquals(ExecutionResult.ErrorCode.TIMEOUT, result.code());
            assertEquals("original", target.eGet(name));
            assertEquals(0, saves.get());
        }
    }

    @Test
    public void shorterNestedDeadlineAlsoCancelsTheParentOperation() throws Exception {
        AtomicBoolean entered = new AtomicBoolean();
        long started = System.nanoTime();
        ExecutionResult result = runScript(
            "api.set(target, 'name', 'parent partial'); "
                + "GroovyExecutor.execute(session, \"api.set(target, 'name', 'child partial'); entered.set(true); while (true) {}\", "
                + "500L, [target: target, entered: entered]); return 42",
            5_000, Map.of("entered", entered));

        assertTrue(entered.get());
        assertEquals(ExecutionResult.ErrorCode.TIMEOUT, result.code());
        assertTrue("The child deadline must apply before the parent deadline",
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 4_000);
        assertEquals("original", target.eGet(name));
        assertEquals(0, saves.get());
    }

    @Test
    public void topLevelSwtCallerIsRejectedImmediatelyInsteadOfDeadlocking() {
        long started = System.nanoTime();
        ExecutionResult result = GroovyExecutor.execute(session, "api.set(target, 'name', 'unsafe')");

        assertEquals(ExecutionResult.ErrorCode.API_ERROR, result.code());
        assertTrue(result.message().contains("background thread"));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000);
        assertEquals("original", target.eGet(name));
        assertEquals(0, saves.get());
        assertFalse(domain.getCommandStack().canUndo());
    }

    @Test
    public void batchSavesOnceAfterCommitAndRemainsUndoable() throws Exception {
        ExecutionResult result = runScript(
            "api.batch('Rename twice') { api.set(target, 'name', 'first'); api.set(target, 'name', 'second') }",
            10_000, Map.of());

        assertTrue(result.message(), result.success());
        assertEquals("second", target.eGet(name));
        assertEquals(1, saves.get());
        assertEquals("second", savedName.get());
        assertFalse("Resource save must happen after RecordingCommand completes", savedInsideWrite.get());
        assertTrue(domain.getCommandStack().canUndo());
        domain.getCommandStack().undo();
        assertEquals("original", target.eGet(name));
    }

    @Test
    public void failedBatchRollsBackWithoutSavingPartialChanges() throws Exception {
        ExecutionResult result = runScript(
            "api.batch('Failed rename') { api.set(target, 'name', 'partial'); throw new IllegalArgumentException('later step failed') }",
            10_000, Map.of());

        assertFalse(result.success());
        assertEquals("original", target.eGet(name));
        assertEquals("Failed transactions must not write resources", 0, saves.get());
        assertFalse(domain.getCommandStack().canUndo());
    }

    @Test
    public void rawEmfScriptAlsoRollsBackAsOneCommand() throws Exception {
        ExecutionResult result = runScript(
            "target.eSet(target.eClass().getEStructuralFeature('name'), 'partial'); throw new IllegalArgumentException('fail')",
            10_000, Map.of());

        assertFalse(result.success());
        assertEquals("original", target.eGet(name));
        assertEquals(0, saves.get());
    }

    @Test
    public void scriptInstanceInitializerIsPartOfTheSameRollback() throws Exception {
        ExecutionResult result = runScript(
            "import groovy.transform.Field\n@Field def initialized = api.set(target, 'name', 'initializer')\n"
                + "throw new IllegalArgumentException('body failed')",
            10_000, Map.of());

        assertFalse(result.success());
        assertTrue("The initializer must run before the body fails: " + result.message(),
            result.message().contains("body failed"));
        assertEquals("original", target.eGet(name));
        assertEquals(0, saves.get());
    }

    @Test
    public void failedPostCommitSaveReportsAppliedChangesWithoutClaimingRollback() throws Exception {
        failSave.set(true);
        ExecutionResult result = runScript("api.set(target, 'name', 'applied')", 10_000, Map.of());

        assertFalse(result.success());
        assertEquals(ExecutionResult.ErrorCode.SAVE_FAILED_AFTER_COMMIT, result.code());
        assertTrue(result.message().contains("were applied"));
        assertTrue(result.message().contains("do not rerun"));
        assertEquals("applied", target.eGet(name));
        assertEquals(1, saves.get());
        assertFalse(savedInsideWrite.get());
        assertTrue("Committed changes must still be undoable", domain.getCommandStack().canUndo());
    }

    @Test
    public void timeoutDuringPostCommitSaveReportsAppliedAndSavedChanges() throws Exception {
        saveDelayMs.set(1_800);
        ExecutionResult result = runScript("api.set(target, 'name', 'applied')", 1_500, Map.of());

        assertEquals("The command must reach its post-commit save", 1, saves.get());
        assertTrue("A completed save must not be reported as a rolled-back timeout: " + result.message(), result.success());
        assertEquals("applied", target.eGet(name));
        assertEquals("applied", savedName.get());
        assertEquals(1, saves.get());
        assertTrue(result.logs().stream().anyMatch(line -> line.contains("applied and saved")));
    }

    @Test
    public void timeoutDuringFailedPostCommitSaveReportsSaveFailure() throws Exception {
        saveDelayMs.set(1_800);
        failSave.set(true);
        ExecutionResult result = runScript("api.set(target, 'name', 'applied')", 1_500, Map.of());

        assertEquals("The command must reach its post-commit save", 1, saves.get());
        assertFalse(result.success());
        assertEquals(ExecutionResult.ErrorCode.SAVE_FAILED_AFTER_COMMIT, result.code());
        assertEquals("applied", target.eGet(name));
        assertTrue(result.message().contains("do not rerun"));
    }

    @Test
    public void queriesAfterRollbackCannotReuseUncommittedIndexOrStatefulApiCaches() throws Exception {
        // The model index traverses elements under semantic resource roots.
        EClass containerType = EcoreFactory.eINSTANCE.createEClass();
        containerType.setName("ModelRoot");
        EReference children = EcoreFactory.eINSTANCE.createEReference();
        children.setName("children");
        children.setEType(target.eClass());
        children.setContainment(true);
        children.setUpperBound(-1);
        containerType.getEStructuralFeatures().add(children);
        EPackage pkg = target.eClass().getEPackage();
        pkg.getEClassifiers().add(containerType);
        EObject root = pkg.getEFactoryInstance().create(containerType);
        SessionSafeguards.runWrite(session, "Build query fixture", () -> {
            Resource resource = target.eResource();
            resource.getContents().clear();
            resource.getContents().add(root);
            ((List<EObject>) root.eGet(children)).add(target);
            return null;
        });
        domain.getCommandStack().flush();
        String sessionId = "rollback-cache-" + java.util.UUID.randomUUID();
        AtomicBoolean indexedPartial = new AtomicBoolean();
        FutureTask<ExecutionResult> failed = new FutureTask<>(() -> GroovyExecutor.execute(session,
            "api.set(target, 'name', 'partial'); "
                + "assert api.find('partial') == target; "
                + "indexedPartial.set(!org.polarsys.capella.ai.model.index.ModelIndex.search(session, 'partial', 10).isEmpty()); "
                + "throw new IllegalArgumentException('rollback after query')",
            10_000, Map.of("target", target, "indexedPartial", indexedPartial), sessionId));
        Thread failedCaller = new Thread(failed, "GroovySafetyTest-RollbackCache");
        failedCaller.setDaemon(true);
        failedCaller.start();
        assertFalse(pumpUntilFinished(failed).success());
        assertTrue("The failed command must populate its index from uncommitted state", indexedPartial.get());
        assertEquals("original", target.eGet(name));
        assertTrue("Rolled-back names must be removed from the global index", ModelIndex.search(session, "partial", 10).isEmpty());
        assertFalse(ModelIndex.search(session, "original", 10).isEmpty());

        FutureTask<ExecutionResult> next = new FutureTask<>(() -> GroovyExecutor.execute(session,
            "return api.find('partial')", 10_000, Map.of(), sessionId));
        Thread nextCaller = new Thread(next, "GroovySafetyTest-FreshCache");
        nextCaller.setDaemon(true);
        nextCaller.start();
        ExecutionResult nextResult = pumpUntilFinished(next);
        assertTrue(nextResult.message(), nextResult.success());
        assertNull("The reused script session must query the restored model", nextResult.value());
    }

    @Test
    public void sessionWithoutEditingDomainCannotRunMutationSupplier() {
        AtomicBoolean called = new AtomicBoolean();
        Session noDomain = (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[] {Session.class},
            (proxy, method, args) -> null);
        try {
            SessionSafeguards.runWrite(noDomain, "Unsafe mutation", () -> {
                called.set(true);
                return null;
            });
            fail("A session without a write transaction must be rejected");
        } catch (IllegalStateException expected) {
            assertFalse(called.get());
        }
        assertEquals(ExecutionResult.ErrorCode.API_ERROR,
            GroovyExecutor.execute(noDomain, "return 1").code());
    }

    @Test
    public void reviewedDecodedScriptPreservesTokenLikeLiteralsAndSessionState() throws Exception {
        boolean previousPrivacy = LLMPreferences.isPrivacyTokenizationEnabled();
        String sessionId = "reviewed-script-" + java.util.UUID.randomUUID();
        String originalName = "Original Display Name";
        String tokenized = Tokenizer.forSession(sessionId).tokenizeText("\"" + originalName + "\"");
        String token = tokenized.substring(1, tokenized.length() - 1);
        assertTrue(token.startsWith("ELEM_"));
        LLMPreferences.setPrivacyTokenizationEnabled(true);
        try {
            String approvedScript = "reviewedTarget = target; api.set(reviewedTarget, 'name', '" + token + "')";
            FutureTask<ExecutionResult> decoded = new FutureTask<>(() -> GroovyExecutor.executeDecoded(
                session, approvedScript, Map.of("target", target), sessionId));
            new Thread(decoded, "GroovySafetyTest-Reviewed").start();
            assertTrue(pumpUntilFinished(decoded).success());
            assertEquals("The displayed token-like literal must be executed unchanged", token, target.eGet(name));

            FutureTask<ExecutionResult> normal = new FutureTask<>(() -> GroovyExecutor.execute(
                session, "api.set(reviewedTarget, 'name', '" + token + "')", Map.of(), sessionId));
            new Thread(normal, "GroovySafetyTest-Normal").start();
            assertTrue(pumpUntilFinished(normal).success());
            assertEquals("The standard execution path still decodes privacy tokens", originalName, target.eGet(name));
        } finally {
            LLMPreferences.setPrivacyTokenizationEnabled(previousPrivacy);
        }
    }

    @Test
    public void timeoutStopsLoopOnUiThreadAndRollsBackBeforeReturning() throws Exception {
        AtomicBoolean entered = new AtomicBoolean();
        ExecutionResult result = runScript(
            "api.batch('Timed rename') { api.set(target, 'name', 'partial'); entered.set(true); while (true) {} }",
            500, Map.of("entered", entered));

        assertTrue("The test must reach the dispatched UI closure", entered.get());
        assertFalse(result.success());
        assertEquals(ExecutionResult.ErrorCode.TIMEOUT, result.code());
        assertEquals("original", target.eGet(name));
        assertEquals(0, saves.get());
        AtomicBoolean responsive = new AtomicBoolean();
        display.asyncExec(() -> responsive.set(true));
        while (display.readAndDispatch()) { }
        assertTrue("SWT must remain responsive after timeout", responsive.get());
        assertEquals("No queued script mutation may happen after timeout", "original", target.eGet(name));
    }

    @Test
    public void callerInterruptionStopsDispatchedLoopAndRollsBack() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        FutureTask<ExecutionResult> execution = new FutureTask<>(() -> GroovyExecutor.execute(session,
            "api.set(target, 'name', 'partial'); entered.countDown(); while (true) {}",
            10_000, Map.of("target", target, "entered", entered)));
        Thread caller = new Thread(execution, "GroovySafetyTest-Caller");
        Thread stopper = new Thread(() -> {
            try {
                if (entered.await(5, TimeUnit.SECONDS)) caller.interrupt();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "GroovySafetyTest-Stopper");
        stopper.setDaemon(true);
        stopper.start();
        caller.start();
        ExecutionResult result = pumpUntilFinished(execution);
        stopper.join(1_000);

        assertEquals(0, entered.getCount());
        assertFalse(result.success());
        assertEquals(ExecutionResult.ErrorCode.TIMEOUT, result.code());
        assertEquals("original", target.eGet(name));
        assertEquals(0, saves.get());
    }

    @Test
    public void cancelledQueuedWriteCannotRunAfterCancellationIsReported() throws Exception {
        SessionSafeguards.ExecutionControl control = new SessionSafeguards.ExecutionControl(10_000);
        CountDownLatch dispatching = new CountDownLatch(1);
        FutureTask<RuntimeException> execution = new FutureTask<>(() -> {
            try {
                SessionSafeguards.withExecutionControl(control, () -> {
                    dispatching.countDown();
                    return SessionSafeguards.runWrite(session, "Queued rename", () -> {
                        target.eSet(name, "late mutation");
                        return null;
                    });
                });
                return null;
            } catch (RuntimeException e) {
                return e;
            }
        });
        new Thread(execution, "GroovySafetyTest-QueuedWrite").start();
        assertTrue(dispatching.await(5, TimeUnit.SECONDS));
        control.cancel();
        control.awaitWritesFinished();
        assertEquals("original", target.eGet(name));
        assertTrue("Cancellation must reach the requesting worker without escaping the SWT event loop",
            pumpUntilFinished(execution) instanceof SessionSafeguards.ScriptCancelledException);
        assertEquals("original", target.eGet(name));
        assertEquals(0, saves.get());
    }

    private static final class UserSkillFixture implements AutoCloseable {
        private final String previousDirectory = System.getProperty("capella.ai.skills.dir");
        private final boolean previousEnabled = SkillPreferences.isUserSkillsExecutionEnabled();
        private final Path directory;
        private final Path skillDirectory;
        private final Path skillFile;

        UserSkillFixture(String script) throws Exception {
            directory = Files.createTempDirectory("capella-ai-nested-skill-");
            skillDirectory = Files.createDirectory(directory.resolve("nested-safety"));
            skillFile = skillDirectory.resolve("SKILL.md");
            Files.writeString(skillFile,
                "---\nname: nested-safety\ndescription: Nested execution regression fixture\n---\n\n```groovy\n"
                    + script + "\n```\n", StandardCharsets.UTF_8);
            System.setProperty("capella.ai.skills.dir", directory.toString());
            SkillPreferences.setUserSkillsExecutionEnabled(true);
        }

        @Override
        public void close() throws Exception {
            SkillPreferences.setUserSkillsExecutionEnabled(previousEnabled);
            if (previousDirectory == null) System.clearProperty("capella.ai.skills.dir");
            else System.setProperty("capella.ai.skills.dir", previousDirectory);
            Files.deleteIfExists(skillFile);
            Files.deleteIfExists(skillDirectory);
            Files.deleteIfExists(directory);
        }
    }

    private ExecutionResult runScript(String script, long timeoutMs, Map<String, Object> extras) throws Exception {
        java.util.HashMap<String, Object> bindings = new java.util.HashMap<>(extras);
        bindings.put("target", target);
        FutureTask<ExecutionResult> execution = new FutureTask<>(
            () -> GroovyExecutor.execute(session, script, timeoutMs, bindings));
        Thread caller = new Thread(execution, "GroovySafetyTest-Caller");
        caller.setDaemon(true);
        caller.start();
        return pumpUntilFinished(execution);
    }

    private <T> T pumpUntilFinished(FutureTask<T> execution) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!execution.isDone() && System.nanoTime() < deadline) {
            if (!display.readAndDispatch()) Thread.sleep(2);
        }
        assertTrue("Script failed to finish within the regression test deadline", execution.isDone());
        return execution.get(100, TimeUnit.MILLISECONDS);
    }
}
