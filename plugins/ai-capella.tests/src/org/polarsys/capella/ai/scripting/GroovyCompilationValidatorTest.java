package org.polarsys.capella.ai.scripting;

import static org.junit.Assert.*;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceImpl;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.sirius.business.api.session.Session;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.polarsys.capella.core.data.capellamodeller.CapellamodellerFactory;
import org.polarsys.capella.core.data.la.LaFactory;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Real Groovy compilation and native model invariants, with no workbench or provider. */
public class GroovyCompilationValidatorTest {
    private TransactionalEditingDomain domain;

    @BeforeClass
    public static void warmCompiler() throws Exception {
        // Cold native import resolution may exceed the production 2s wait; use its explicit bounded
        // overload.
        var report = GroovyExecutor.compileDecoded("return 1", () -> false, 10000);
        assertTrue(report.formatForRepair(), report.success());
        awaitCompilerIdle();
    }

    @After
    public void cleanup() throws Exception {
        Thread.interrupted();
        if (domain != null) domain.dispose();
        awaitCompilerIdle();
    }

    private static GroovyCompilationValidator.Report compile(String source) {
        return GroovyExecutor.compileDecoded(source, () -> false, 10000);
    }

    @Test
    public void nativeImportsBindingsAndReviewedApiCallsCompileWithoutModelEffects()
            throws Exception {
        var project = CapellamodellerFactory.eINSTANCE.createProject();
        project.setId(UUID.randomUUID().toString());
        project.setName("Compile fixture");
        var engineering = CapellamodellerFactory.eINSTANCE.createSystemEngineering();
        engineering.setId(UUID.randomUUID().toString());
        project.getOwnedModelRoots().add(engineering);
        var architecture = LaFactory.eINSTANCE.createLogicalArchitecture();
        architecture.setId(UUID.randomUUID().toString());
        engineering.getOwnedArchitectures().add(architecture);
        var pkg = LaFactory.eINSTANCE.createLogicalFunctionPkg();
        pkg.setId(UUID.randomUUID().toString());
        architecture.setOwnedFunctionPkg(pkg);
        var function = LaFactory.eINSTANCE.createLogicalFunction();
        function.setId(UUID.randomUUID().toString());
        function.setName("Original");
        pkg.getOwnedLogicalFunctions().add(function);
        var resource = new XMIResourceImpl(URI.createURI("memory:/compile-fixture.capella"));
        var resources = new ResourceSetImpl();
        resources.getResources().add(resource);
        resource.getContents().add(project);
        domain = TransactionalEditingDomain.Factory.INSTANCE.createEditingDomain(resources);
        AtomicInteger sessionCalls = new AtomicInteger();
        Session session =
                (Session)
                        Proxy.newProxyInstance(
                                Session.class.getClassLoader(),
                                new Class<?>[] {Session.class},
                                (proxy, method, args) -> {
                                    sessionCalls.incrementAndGet();
                                    throw new AssertionError("Compiler must not use a Session");
                                });
        assertNotNull(session);
        byte[] before = bytes(resource);
        resource.setModified(false);
        List<EObject> elements = new java.util.ArrayList<>();
        elements.add(project);
        project.eAllContents().forEachRemaining(elements::add);
        List<Integer> adapters = elements.stream().map(value -> value.eAdapters().size()).toList();
        Object undo = domain.getCommandStack().getUndoCommand();

        var report =
                compile(
                        "def fresh = LaFactory.eINSTANCE.createLogicalFunction(); "
                                + "api.set(api.findById('"
                                + function.getId()
                                + "'), 'name', 'Changed'); api.fs.write('compile-side-effect.txt',"
                                + " 'must not be written'); session.save(null); while (true) {"
                                + " log.info('must not run') }; return fresh");

        assertTrue(report.formatForRepair(), report.success());
        assertEquals("Original", function.getName());
        assertEquals(0, sessionCalls.get());
        assertFalse(resource.isModified());
        assertArrayEquals(before, bytes(resource));
        assertSame(undo, domain.getCommandStack().getUndoCommand());
        assertFalse(domain.getCommandStack().canUndo());
        assertEquals(adapters, elements.stream().map(value -> value.eAdapters().size()).toList());
        assertEquals(List.of(resource), resources.getResources());
        assertTrue(resource.getErrors().isEmpty());
        assertEquals(false, report.feedback().get("codeExecuted"));
        assertEquals(false, report.feedback().get("applied"));
        assertEquals(false, report.feedback().get("saved"));
    }

    @Test
    public void scriptStaticInstanceAndFieldInitializersAreNotExecuted() {
        var report =
                compile(
                        "import groovy.transform.Field\n"
                            + "@Field Object field = { throw new IllegalStateException('Field"
                            + " initializer must not run') }()\n"
                            + "class CompileOnlyInitializer {\n"
                            + " static { throw new IllegalStateException('Static initializer must"
                            + " not run') }\n"
                            + " CompileOnlyInitializer() { throw new"
                            + " IllegalStateException('Constructor must not run') }\n"
                            + "}\n"
                            + "new CompileOnlyInitializer(); throw new"
                            + " IllegalStateException('Script must not run')");
        assertTrue(report.formatForRepair(), report.success());
    }

    @Test
    public void aSyntaxFailureProvidesLocationThenCorrectedSourceCompiles() {
        var invalid = compile("def first = 1\ndef second = (first +\nreturn second");
        assertEquals(GroovyCompilationValidator.Status.COMPILATION_ERROR, invalid.status());
        assertTrue(invalid.repairable());
        assertTrue(invalid.diagnostics().get(0).line() >= 2);
        assertTrue(invalid.diagnostics().get(0).column() >= 1);
        var corrected = compile("def first = 1\ndef second = first + 2\nreturn second");
        assertTrue(corrected.formatForRepair(), corrected.success());
    }

    @Test
    public void unresolvedImportsAndDuplicateLocalVariablesHaveSafeUsefulCategories() {
        var imported = compile("import missing.secret.CustomerPrivateType\nreturn 1");
        assertEquals(GroovyCompilationValidator.Status.COMPILATION_ERROR, imported.status());
        assertTrue(
                imported.diagnostics().stream()
                        .anyMatch(value -> value.message().contains("type cannot be resolved")));
        assertFalse(imported.formatForRepair().contains("CustomerPrivateType"));
        var duplicate = compile("def secretPrivateVariable = 1; def secretPrivateVariable = 2");
        assertEquals(GroovyCompilationValidator.Status.COMPILATION_ERROR, duplicate.status());
        assertTrue(
                duplicate.diagnostics().stream()
                        .anyMatch(value -> value.message().contains("declared twice")));
        assertFalse(duplicate.formatForRepair().contains("secretPrivateVariable"));
    }

    @Test
    public void escapedSensitiveLiteralAndCompilerExcerptNeverAppearInFeedback() {
        var invalid = compile("def name = 'Driver\\'s Battery Secret'\ndef broken = [name,\n");
        assertFalse(invalid.success());
        String summary = invalid.formatForRepair();
        String structured = invalid.feedback().toString();
        for (String secret : List.of("Driver", "Battery", "Secret", "broken", "Proposal.groovy")) {
            assertFalse(summary, summary.contains(secret));
            assertFalse(structured, structured.contains(secret));
        }
        assertTrue(summary.length() <= 8192);
    }

    @Test
    public void maliciousLocalTransformsCannotRunDuringCompilation() {
        for (String source :
                List.of(
                        "import groovy.transform.ASTTest\n"
                            + "@ASTTest(value={ throw new IllegalStateException('Transform"
                            + " executed') }) def x = 1",
                        "@groovy.transform.ASTTest(value={ throw new"
                            + " IllegalStateException('Transform executed') }) class Probe {}",
                        "@groovy.transform.CompileStatic(extensions='file:/must-not-load.groovy')"
                            + " class Probe {}",
                        "@groovy.transform.TypeChecked(extensions='file:/must-not-load.groovy') def"
                            + " probe() { 1 }")) {
            var report = compile(source);
            assertEquals(
                    report.formatForRepair(),
                    GroovyCompilationValidator.Status.SECURITY_VIOLATION,
                    report.status());
            assertTrue(report.diagnostics().get(0).line() > 0);
            assertFalse(report.formatForRepair().contains("Transform executed"));
            assertFalse(report.formatForRepair().contains("must-not-load"));
        }
    }

    @Test
    public void
            annotationsOnClosureParametersAndTheirDefaultInitializersAreCheckedBeforeTransforms() {
        for (String source :
                List.of(
                        "def closure = { @groovy.transform.ASTTest(value={ throw new"
                            + " IllegalStateException('run') }) Object input -> input }",
                        "def closure = { Object input = { @groovy.transform.ASTTest(value={ throw"
                            + " new IllegalStateException('run') }) def nested=1; nested }() ->"
                            + " input }")) {
            var report = compile(source);
            assertEquals(
                    report.formatForRepair(),
                    GroovyCompilationValidator.Status.SECURITY_VIOLATION,
                    report.status());
            assertTrue(report.diagnostics().get(0).line() > 0);
        }
        assertTrue(
                compile(
                                "def closure = { Object input = { throw new"
                                    + " IllegalStateException('Do not run') }() -> input }; return"
                                    + " closure")
                        .success());
    }

    @Test
    public void grapeAndAnnotationAliasesAreBlockedBeforeDependencyResolution() {
        for (String source :
                List.of(
                        "@Grab('invalid.group:must-not-download:0') def value = 1",
                        "@GrabResolver(name='private',root='https://private.example.invalid/') def"
                            + " value = 1",
                        "import groovy.transform.ASTTest as Harmless\n"
                            + "@Harmless(value={ throw new IllegalStateException('run') }) def"
                            + " value=1",
                        "import groovy.transform.Field\n@Field(value='unsupported') def value=1")) {
            var report = compile(source);
            assertEquals(
                    report.formatForRepair(),
                    GroovyCompilationValidator.Status.SECURITY_VIOLATION,
                    report.status());
        }
        assertTrue(
                compile("import groovy.transform.Field as F\n@F def value = 1\nreturn value")
                        .success());
    }

    @Test
    public void compilerGeneratedRunMarkerDoesNotAllowSourceWrittenAnnotations() {
        assertTrue(compile("return 1").success());
        var explicit =
                compile(
                        "class SourceAnnotated { @java.lang.Override String toString() { 'value' }"
                            + " }");
        assertEquals(
                explicit.formatForRepair(),
                GroovyCompilationValidator.Status.SECURITY_VIOLATION,
                explicit.status());
        assertTrue(
                "The rejected marker must have a real source location",
                explicit.diagnostics().get(0).line() > 0);
    }

    @Test
    public void methodDefaultInitializersAreGuardedBeforeSemanticAnnotationDiscovery() {
        var report =
                compile(
                        "def probe(Object input = {\n"
                            + " @groovy.transform.ASTTest(value={ throw new"
                            + " IllegalStateException('Transform must not run') }) def nested=1\n"
                            + " nested }()) { input }\n"
                            + "return 1");
        assertEquals(
                report.formatForRepair(),
                GroovyCompilationValidator.Status.SECURITY_VIOLATION,
                report.status());
        assertEquals(
                "The source guard must reject the nested annotation before any semantic transform",
                2,
                report.diagnostics().get(0).line());
        assertTrue(
                compile(
                                "def probe(Object input = { throw new"
                                    + " IllegalStateException('Default must not run') }()) { input"
                                    + " }\n"
                                    + "return 1")
                        .success());
    }

    @Test
    public void constructorDefaultInitializersAreGuardedBeforeSemanticAnnotationDiscovery() {
        var report =
                compile(
                        "class ConstructorProbe {\n"
                            + " ConstructorProbe(Object input = {\n"
                            + " @groovy.transform.ASTTest(value={ throw new"
                            + " IllegalStateException('Transform must not run') }) def nested=1\n"
                            + " nested }()) { }\n"
                            + "}\n"
                            + "return 1");
        assertEquals(
                report.formatForRepair(),
                GroovyCompilationValidator.Status.SECURITY_VIOLATION,
                report.status());
        assertEquals(
                "The source guard must reject the nested annotation before any semantic transform",
                3,
                report.diagnostics().get(0).line());
        assertTrue(
                compile(
                                "class ConstructorProbe { ConstructorProbe(Object input = { throw"
                                    + " new IllegalStateException('Default must not run') }()) { }"
                                    + " }\n"
                                    + "return 1")
                        .success());
    }

    @Test
    public void aReachableExternalGroovyDependencyCannotBypassTheSourceAnnotationGuard()
            throws Exception {
        var directory = Files.createTempDirectory("capella-compile-only-source-trap-");
        var source = directory.resolve("SourceTrap.groovy");
        Files.writeString(
                source,
                "@groovy.transform.ASTTest(value={ throw new IllegalStateException('External"
                    + " transformation ran') }) class SourceTrap {}",
                StandardCharsets.UTF_8);
        // Test-only reflection keeps this trusted loader seam private across OSGi bundle
        // boundaries.
        var loaderType =
                Class.forName(GroovyCompilationValidator.class.getName() + "$CompileLoader");
        var constructor =
                loaderType.getDeclaredConstructor(
                        org.codehaus.groovy.control.CompilerConfiguration.class);
        constructor.setAccessible(true);
        var configuration = new org.codehaus.groovy.control.CompilerConfiguration();
        try (var loader = (groovy.lang.GroovyClassLoader) constructor.newInstance(configuration)) {
            loader.addClasspath(directory.toString());
            assertNotNull(
                    "The trap is reachable on the loader's classpath",
                    loader.getResource("SourceTrap.groovy"));
            assertNull(
                    "External source lookup must be explicitly denied",
                    loader.getResourceLoader().loadGroovySource("SourceTrap"));
            assertFalse(
                    loader.getResources(
                                    "META-INF/services/org.codehaus.groovy.transform.ASTTransformation")
                            .hasMoreElements());
            var factory =
                    GroovyCompilationValidator.class.getDeclaredMethod(
                            "compilationUnit",
                            org.codehaus.groovy.control.CompilerConfiguration.class,
                            groovy.lang.GroovyClassLoader.class);
            factory.setAccessible(true);
            var unit =
                    (org.codehaus.groovy.control.CompilationUnit)
                            factory.invoke(null, configuration, loader);
            assertSame(
                    "Source resolution must use the filtered loader",
                    loader,
                    unit.getClassLoader());
            assertSame(
                    "Transform discovery must use the same filtered loader",
                    loader,
                    unit.getTransformLoader());
            unit.addSource("Proposal.groovy", "import SourceTrap\nreturn 1");
            var failure =
                    assertThrows(
                            org.codehaus.groovy.control.MultipleCompilationErrorsException.class,
                            () ->
                                    unit.compile(
                                            org.codehaus.groovy.control.Phases.CLASS_GENERATION));
            assertFalse(
                    failure.getMessage(),
                    failure.getMessage().contains("External transformation ran"));
            assertEquals(
                    "No additional unguarded dependency module was parsed",
                    1,
                    unit.getAST().getModules().size());
        } finally {
            Files.deleteIfExists(source);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void sharedRuntimePolicyAndApiCatalogStillRejectUnsupportedCode() {
        var dangerous = compile("java.lang.System.exit(1)");
        assertEquals(
                dangerous.formatForRepair(),
                GroovyCompilationValidator.Status.SECURITY_VIOLATION,
                dangerous.status());
        var unknownApi = compile("api.arcadia.nonexistentMethod()");
        assertEquals(GroovyCompilationValidator.Status.COMPILATION_ERROR, unknownApi.status());
        assertTrue(unknownApi.formatForRepair().contains("API"));
        assertFalse(unknownApi.formatForRepair().contains("nonexistentMethod"));
        assertFalse(compile("package secret.privatepackage\nreturn 1").success());
    }

    @Test
    public void diagnosticsAreBoundedAndDoNotCopySourceOrStack() {
        String source =
                java.util.stream.IntStream.range(0, 40)
                        .mapToObj(index -> "import hidden.missing.SecretType" + index)
                        .collect(java.util.stream.Collectors.joining("\n"));
        var report = compile(source + "\nreturn 1");
        assertFalse(report.success());
        assertEquals(12, report.diagnostics().size());
        assertTrue(report.truncated());
        assertTrue(report.formatForRepair().length() <= 8192);
        assertFalse(report.feedback().toString().contains("SecretType"));
        assertFalse(report.formatForRepair().contains("Exception"));
        assertFalse(report.formatForRepair().contains(".java:"));
    }

    @Test
    public void cancellationInterruptionAndTimeoutNeverBecomeRepairableCompileFailures() {
        var cancelled = GroovyExecutor.compileDecoded("return 1", () -> true);
        assertEquals(GroovyCompilationValidator.Status.CANCELLED, cancelled.status());
        assertFalse(cancelled.repairable());
        Thread.currentThread().interrupt();
        var interrupted = compile("return 1");
        assertEquals(GroovyCompilationValidator.Status.CANCELLED, interrupted.status());
        assertFalse(interrupted.repairable());
        assertTrue(Thread.currentThread().isInterrupted());
        Thread.interrupted();
        var timedOut =
                GroovyExecutor.compileDecoded(
                        "def values = [" + "1,".repeat(20000) + "1]", () -> false, 1);
        assertEquals(
                timedOut.formatForRepair(),
                GroovyCompilationValidator.Status.TIMED_OUT,
                timedOut.status());
        assertFalse(timedOut.repairable());
    }

    @Test
    public void anUninterruptibleCompilerKeepsItsSlotAndCallerCancellationRemainsBounded()
            throws Exception {
        CountDownLatch workerEntered = new CountDownLatch(1), releaseWorker = new CountDownLatch(1);
        AtomicBoolean cancel = new AtomicBoolean();
        FutureTask<GroovyCompilationValidator.Report> first =
                new FutureTask<>(
                        () ->
                                GroovyExecutor.compileDecoded(
                                        "return 1",
                                        () -> {
                                            if (Thread.currentThread()
                                                    .getName()
                                                    .equals("Capella-Groovy-Compile-Only")) {
                                                workerEntered.countDown();
                                                while (releaseWorker.getCount() != 0) {
                                                    try {
                                                        releaseWorker.await();
                                                    } catch (InterruptedException ignored) {
                                                        /* Simulate a compiler dependency that ignores interruption. */
                                                    }
                                                }
                                            }
                                            return cancel.get();
                                        },
                                        10000));
        Thread caller = new Thread(first, "Compile-only-test-caller");
        caller.setDaemon(true);
        caller.start();
        try {
            assertTrue(workerEntered.await(5, TimeUnit.SECONDS));
            assertEquals(
                    GroovyCompilationValidator.Status.UNAVAILABLE, compile("return 2").status());
            cancel.set(true);
            var result = first.get(2, TimeUnit.SECONDS);
            assertEquals(GroovyCompilationValidator.Status.CANCELLED, result.status());
            assertFalse(result.repairable());
            assertEquals(
                    GroovyCompilationValidator.Status.UNAVAILABLE, compile("return 3").status());
        } finally {
            releaseWorker.countDown();
            caller.join(3000);
        }
    }

    @Test
    public void inputBoundsAreStrictAndDoNotInvokeCancellationOrModelWorkAfterRejection() {
        assertEquals(GroovyCompilationValidator.Status.INVALID_INPUT, compile(null).status());
        assertEquals(GroovyCompilationValidator.Status.INVALID_INPUT, compile("  ").status());
        assertEquals(
                GroovyCompilationValidator.Status.INVALID_INPUT,
                compile(" ".repeat(100001) + "1").status());
        assertThrows(
                IllegalArgumentException.class,
                () -> GroovyExecutor.compileDecoded("1", () -> false, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> GroovyExecutor.compileDecoded("1", () -> false, 10001));
        assertThrows(
                UnsupportedOperationException.class,
                () -> compile("return 1").feedback().put("saved", true));
    }

    private static byte[] bytes(XMIResourceImpl resource) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        resource.save(output, Map.of());
        return output.toByteArray();
    }

    private static void awaitCompilerIdle() throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            var report = GroovyExecutor.compileDecoded("return 1", () -> false, 10000);
            if (report.status() != GroovyCompilationValidator.Status.UNAVAILABLE) {
                assertTrue(report.formatForRepair(), report.success());
                return;
            }
            Thread.sleep(20);
        }
        fail("The controlled compile-only test worker did not finish");
    }
}
