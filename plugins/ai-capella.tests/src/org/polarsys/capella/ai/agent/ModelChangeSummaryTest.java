package org.polarsys.capella.ai.agent;

import static org.junit.Assert.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.transaction.RecordingCommand;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.diagram.DiagramFactory;
import org.eclipse.swt.widgets.Display;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.polarsys.capella.ai.scripting.ExecutionResult;
import org.polarsys.capella.ai.session.SessionSafeguards;
import org.polarsys.capella.core.data.ctx.CtxFactory;
import org.polarsys.capella.core.data.ctx.SystemFunction;
import org.polarsys.capella.core.data.fa.FaFactory;
import org.polarsys.capella.core.data.fa.FunctionOutputPort;
import org.polarsys.capella.core.data.fa.FunctionalExchange;

/** Native Capella/Sirius objects and actual EMF transactions, without serialized mock models. */
public class ModelChangeSummaryTest {
    private Resource semantic, aird, foreign;
    private TransactionalEditingDomain domain;
    private Session session;
    private SystemFunction root, first, second;
    private FunctionOutputPort firstPort, secondPort;
    private FunctionalExchange exchange;
    private EObject diagram;
    private final AtomicBoolean open = new AtomicBoolean(true);

    @Before public void setUp() {
        assertSame(Display.getDefault(), Display.getCurrent());
        var resources = new ResourceSetImpl();
        semantic = new ResourceImpl(URI.createURI("memory:/change-summary.capella"));
        aird = new ResourceImpl(URI.createURI("memory:/change-summary.aird"));
        foreign = new ResourceImpl(URI.createURI("memory:/foreign.capella"));
        resources.getResources().addAll(List.of(semantic, aird, foreign));
        root = function("root", "Root"); first = function("first", "First"); second = function("second", "Second");
        root.getOwnedFunctions().addAll(List.of(first, second));
        firstPort = port("first-port"); secondPort = port("second-port");
        first.getOutputs().add(firstPort); second.getOutputs().add(secondPort);
        exchange = FaFactory.eINSTANCE.createFunctionalExchange(); exchange.setId("exchange"); exchange.setName("Command");
        exchange.setSource(firstPort);
        semantic.getContents().addAll(List.of(root, exchange));
        diagram = DiagramFactory.eINSTANCE.createDSemanticDiagram();
        diagram.eSet(diagram.eClass().getEStructuralFeature("headerHeight"), 20);
        aird.getContents().add(diagram);
        foreign.getContents().add(function("foreign", "Other model"));
        domain = TransactionalEditingDomain.Factory.INSTANCE.createEditingDomain(resources);
        session = (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[] {Session.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getTransactionalEditingDomain" -> domain;
                case "getSessionResource" -> aird;
                case "getSemanticResources" -> List.of(semantic);
                case "getAllSessionResources" -> Set.of(aird);
                case "getReferencedSessionResources" -> Set.of();
                case "isOpen" -> open.get();
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> method.getReturnType() == boolean.class ? false : null;
            });
    }

    @After public void tearDown() {
        if (domain != null) domain.dispose(); SessionSafeguards.cleanup();
    }

    @Test public void nativeCreationResolvesIdsAssignedAfterContainmentAndDetaches() {
        var recorder = ModelChangeSummary.capture(session);
        write(() -> {
            var child = CtxFactory.eINSTANCE.createSystemFunction();
            root.getOwnedFunctions().add(child);
            child.setId("created-after-add"); child.setName("Demand monitor");
            child.getOutputs().add(port("created-port"));
        });
        var report = recorder.finish(ExecutionResult.success("done"));
        assertTrue(report.markdownSummary(), report.available()); assertTrue(report.committed());
        assertEquals(2, report.createdCount()); assertEquals(1, report.editedCount()); assertEquals(0, report.deletedCount());
        assertTrue(report.created().stream().anyMatch(element -> "created-after-add".equals(element.id())
            && "SystemFunction".equals(element.type()) && "Demand monitor".equals(element.name())));
        assertEquals(2, report.feedback().get("created_count"));
        assertDetached();
        domain.getCommandStack().undo();
        assertEquals("Finishing does not alter the native Undo command", 2, root.getOwnedFunctions().size());
        assertEquals("Reports stay immutable after native Undo", 2, report.createdCount());
        assertSame(report, recorder.finish(false));
    }

    @Test public void nativeScalarAndPortEndpointChangesAreReportedWithFeatureNames() {
        try (var recorder = ModelChangeSummary.capture(session)) {
            write(() -> { first.setName("Demand validation"); exchange.setSource(secondPort); });
            var report = recorder.finish(true);
            assertTrue(report.available()); assertEquals(0, report.createdCount()); assertEquals(0, report.deletedCount());
            assertTrue(report.edited().stream().anyMatch(element -> "first".equals(element.id()) && element.features().contains("name")));
            assertTrue(report.edited().stream().anyMatch(element -> "exchange".equals(element.id()) && element.features().contains("source")));
            assertTrue(report.markdownSummary().contains("Demand validation"));
        }
        assertDetached();
    }

    @Test public void deletionIncludesContainedNativePortsAndUsesDetachedObjectIdentity() {
        var recorder = ModelChangeSummary.capture(session);
        write(() -> root.getOwnedFunctions().remove(first));
        var report = recorder.finish(true);
        assertTrue(report.available()); assertEquals(2, report.deletedCount());
        assertTrue(report.deleted().stream().anyMatch(element -> "first".equals(element.id())));
        assertTrue(report.deleted().stream().anyMatch(element -> "first-port".equals(element.id())));
        assertDetached();
        assertFalse(first.eAdapters().stream().anyMatch(ModelChangeSummaryTest::isRecorderAdapter));
        assertFalse(firstPort.eAdapters().stream().anyMatch(ModelChangeSummaryTest::isRecorderAdapter));
        domain.getCommandStack().undo();
        assertSame(root, first.eContainer()); assertSame(first, firstPort.eContainer());
    }

    @Test public void rollbackDiscardsTentativeChangesAndCannotClaimCommittedEdits() {
        var recorder = ModelChangeSummary.capture(session);
        try {
            write(() -> {
                first.setName("partial"); root.getOwnedFunctions().remove(second);
                root.getOwnedFunctions().add(function("tentative", "Temporary"));
                throw new IllegalArgumentException("fail command");
            });
            fail("The native command must fail");
        } catch (IllegalArgumentException expected) { assertEquals("fail command", expected.getMessage()); }
        assertEquals("First", first.getName()); assertEquals(2, root.getOwnedFunctions().size());
        var report = recorder.finish(ExecutionResult.fail(ExecutionResult.ErrorCode.RUNTIME_ERROR, "fail command"));
        assertTrue(report.available()); assertFalse(report.committed()); assertFalse(report.hasChanges());
        assertEquals(0, report.createdCount() + report.editedCount() + report.deletedCount());
        assertTrue(report.created().isEmpty()); assertTrue(report.edited().isEmpty()); assertTrue(report.deleted().isEmpty());
        assertFalse(domain.getCommandStack().canUndo()); assertDetached();
    }

    @Test public void committedSaveFailureStillReportsInMemoryChanges() {
        var recorder = ModelChangeSummary.capture(session);
        write(() -> first.setName("Committed name"));
        var report = recorder.finish(ExecutionResult.fail(ExecutionResult.ErrorCode.SAVE_FAILED_AFTER_COMMIT, "Disk full"));
        assertTrue(report.committed()); assertTrue(report.hasChanges()); assertEquals(1, report.editedCount());
        assertTrue(report.markdownSummary().contains("saving failed"));
        assertEquals("Committed name", first.getName()); assertDetached();
    }

    @Test public void transientCreationAndRevertedScalarReferenceAndListChangesAreNotCounted() {
        var recorder = ModelChangeSummary.capture(session);
        write(() -> {
            first.setName("intermediate"); first.setName("First");
            exchange.setSource(secondPort); exchange.setSource(firstPort);
            var child = function("temporary", "Temporary"); root.getOwnedFunctions().add(child); root.getOwnedFunctions().remove(child);
            root.getOwnedFunctions().move(0, second); root.getOwnedFunctions().move(1, second);
            root.getOwnedFunctions().removeAll(List.of(first, second)); root.getOwnedFunctions().addAll(List.of(first, second));
        });
        var report = recorder.finish(true);
        assertTrue(report.markdownSummary(), report.available()); assertFalse(report.hasChanges());
        assertEquals(0, report.createdCount() + report.editedCount() + report.deletedCount()); assertDetached();
    }

    @Test public void movingAnExistingFunctionPreservesIdentityAndIsAnEdit() {
        var recorder = ModelChangeSummary.capture(session);
        write(() -> second.getOwnedFunctions().add(first));
        var report = recorder.finish(true);
        assertTrue(report.available()); assertEquals(0, report.createdCount()); assertEquals(0, report.deletedCount());
        assertTrue(report.edited().stream().anyMatch(element -> "first".equals(element.id())
            && element.features().contains("containment")));
        assertDetached();
    }

    @Test public void ownedDiagramEditsAreIncludedAndForeignResourcesAreExcluded() {
        var recorder = ModelChangeSummary.capture(session);
        write(() -> {
            diagram.eSet(diagram.eClass().getEStructuralFeature("headerHeight"), 40);
            ((SystemFunction) foreign.getContents().get(0)).setName("Foreign edit");
        });
        var report = recorder.finish(true);
        assertTrue(report.available()); assertEquals(1, report.editedCount());
        assertEquals("DSemanticDiagram", report.edited().get(0).type()); assertDetached();
    }

    @Test public void largeResultKeepsExactCountsButCapsAllDisplayedElementsTogether() {
        var recorder = ModelChangeSummary.capture(session);
        write(() -> {
            List<SystemFunction> children = new ArrayList<>();
            for (int index = 0; index < 65; index++) children.add(function("bulk-" + index, "Function " + index));
            root.getOwnedFunctions().addAll(children);
        });
        var report = recorder.finish(true);
        assertTrue(report.available()); assertEquals(65, report.createdCount()); assertEquals(1, report.editedCount());
        assertTrue(report.truncated());
        assertEquals(ModelChangeSummary.MAX_DISPLAY_ITEMS, report.created().size() + report.edited().size() + report.deleted().size());
        assertTrue(report.markdownSummary().contains("counts include all observed changes")); assertDetached();
    }

    @Test public void unrelatedNativeCommandDoesNotBecomeScriptEvidence() {
        var recorder = ModelChangeSummary.capture(session);
        domain.getCommandStack().execute(new RecordingCommand(domain) {
            @Override protected void doExecute() { first.setName("Native UI edit"); }
        });
        write(() -> second.setName("Own script edit"));
        var report = recorder.finish(true);
        assertFalse(report.available()); assertEquals(-1, report.editedCount());
        assertTrue(report.note().contains("outside the reviewed script")); assertDetached();
    }

    @Test public void closeWithoutFinishDetachesAndCannotReturnFalseZeroCounts() {
        var recorder = ModelChangeSummary.capture(session);
        assertTrue(semantic.eAdapters().stream().anyMatch(ModelChangeSummaryTest::isRecorderAdapter));
        recorder.close(); recorder.close(); assertDetached();
        var report = recorder.finish(true);
        assertFalse(report.available()); assertEquals(-1, report.createdCount());
    }

    @Test public void closedSessionReturnsUnavailableAndReleasesAllAdapters() {
        var recorder = ModelChangeSummary.capture(session);
        write(() -> first.setName("Committed")); open.set(false);
        var report = recorder.finish(true);
        assertFalse(report.available()); assertEquals(-1, report.editedCount()); assertDetached();
    }

    @Test public void absentSessionReturnsUnavailableInsteadOfInventedCounts() {
        try (var recorder = ModelChangeSummary.capture(null)) {
            var report = recorder.finish(ExecutionResult.success(1));
            assertFalse(report.available()); assertEquals(-1, report.createdCount());
            assertTrue(report.markdownSummary().contains("unavailable"));
        }
    }

    @Test public void deletedElementUsesItsLastContainedIdAndName() {
        var recorder = ModelChangeSummary.capture(session);
        write(() -> {
            first.setName("Intermediate"); first.setId("renamed-id");
            first.setName("Last contained name"); root.getOwnedFunctions().remove(first);
        });
        var report = recorder.finish(true);
        assertTrue(report.available());
        assertTrue(report.deleted().stream().anyMatch(element -> "renamed-id".equals(element.id())
            && "Last contained name".equals(element.name())));
        assertDetached();
    }

    @Test public void backgroundCallerCapturesAndFinishesOnTheRealSwtEventLoop() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try {
            var future = worker.submit(() -> {
                try (var recorder = ModelChangeSummary.capture(session)) {
                    write(() -> first.setName("Background request"));
                    return recorder.finish(ExecutionResult.success("done"));
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!future.isDone() && System.nanoTime() < deadline) {
                if (!Display.getCurrent().readAndDispatch()) Thread.sleep(1);
            }
            assertTrue("Capture and finish must not deadlock a background request", future.isDone());
            var report = future.get(1, TimeUnit.SECONDS);
            assertTrue(report.available()); assertEquals(1, report.editedCount());
            assertEquals("Background request", first.getName()); assertDetached();
        } finally { worker.shutdownNow(); }
    }

    private void write(Runnable action) {
        SessionSafeguards.runWrite(session, "Evidence fixture", () -> { action.run(); return null; });
    }

    private void assertDetached() {
        for (Resource resource : List.of(semantic, aird, foreign)) {
            assertFalse(resource.eAdapters().stream().anyMatch(ModelChangeSummaryTest::isRecorderAdapter));
            var objects = resource.getAllContents();
            while (objects.hasNext()) assertFalse(objects.next().eAdapters().stream().anyMatch(ModelChangeSummaryTest::isRecorderAdapter));
        }
    }

    private static boolean isRecorderAdapter(Object adapter) {
        return adapter.getClass().getName().startsWith(ModelChangeSummary.class.getName() + "$Recorder$");
    }
    private static SystemFunction function(String id, String name) {
        var function = CtxFactory.eINSTANCE.createSystemFunction(); function.setId(id); function.setName(name); return function;
    }
    private static FunctionOutputPort port(String id) {
        var port = FaFactory.eINSTANCE.createFunctionOutputPort(); port.setId(id); port.setName("Command"); return port;
    }
}
