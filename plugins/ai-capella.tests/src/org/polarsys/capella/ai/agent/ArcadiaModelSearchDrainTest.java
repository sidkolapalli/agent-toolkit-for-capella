package org.polarsys.capella.ai.agent;

import static org.junit.Assert.*;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.eclipse.emf.transaction.RecordingCommand;
import org.eclipse.emf.transaction.Transaction;
import org.eclipse.emf.transaction.TransactionalCommandStack;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.swt.widgets.Display;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.polarsys.capella.ai.session.SessionSafeguards;
import org.polarsys.capella.core.data.capellamodeller.CapellamodellerFactory;
import org.polarsys.capella.core.data.la.LaFactory;
import org.polarsys.capella.core.data.la.LogicalFunction;
import org.polarsys.capella.core.data.la.impl.LogicalFunctionImpl;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;

/**
 * Native Capella objects, an actual EMF transaction domain and separately dispatched SWT slices.
 */
public class ArcadiaModelSearchDrainTest {
    private Resource resource;
    private TransactionalEditingDomain domain;
    private Session session;
    private LogicalFunction root;
    private final List<LogicalFunction> functions = new ArrayList<>();
    private volatile boolean open;
    private final AtomicInteger saves = new AtomicInteger();

    @Before
    public void initializeSwt() {
        Display.getDefault();
    }

    @After
    public void disposeDomain() {
        if (domain != null) domain.dispose();
    }

    @Test
    public void sparseSearchReachesNativeObject86000InOneWorkerCallWithoutModelEffects()
            throws Exception {
        createModel(86000);
        functions.get(85999).setName("Unique late sparse target");
        activateDomain();
        byte[] before = bytes();
        resource.setModified(false);
        Object undo = domain.getCommandStack().getUndoCommand(),
                redo = domain.getCommandStack().getRedoCommand();
        int adapters = adapterCount();
        AtomicInteger uiYield = new AtomicInteger();
        ArcadiaModelTools tools =
                tools(
                        250000,
                        2_000_000_000L,
                        System::nanoTime,
                        () -> Display.getDefault().asyncExec(uiYield::incrementAndGet));
        Map<?, ?> page = result(onWorker(tools, Map.of("query", "Unique late sparse target")));
        assertEquals(true, page.get("complete"));
        assertEquals(true, page.get("traversal_complete"));
        assertEquals(true, page.get("evidence_available"));
        assertNull(page.get("cursor"));
        assertEquals(1, ((List<?>) page.get("matches")).size());
        assertEquals(
                functions.get(85999).getId(),
                ((Map<?, ?>) ((List<?>) page.get("matches")).get(0)).get("id"));
        assertTrue(((Number) page.get("scanned_this_page")).intValue() >= 86000);
        assertTrue(((Number) page.get("read_slices")).intValue() >= 35);
        assertTrue("Unrelated UI work must run between slices", uiYield.get() > 0);
        assertEquals(adapters, adapterCount());
        assertFalse(resource.isModified());
        assertEquals(0, saves.get());
        assertSame(undo, domain.getCommandStack().getUndoCommand());
        assertSame(redo, domain.getCommandStack().getRedoCommand());
        assertArrayEquals(before, bytes());
    }

    @Test
    public void broadSearchPagesExactlyFiftyMatchesAndPreservesEveryNativeId() throws Exception {
        createModel(121);
        activateDomain();
        var tools = new ArcadiaModelTools(session);
        var ids = new HashSet<String>();
        String cursor = null;
        int pages = 0;
        do {
            var args = new java.util.LinkedHashMap<String, Object>();
            args.put("query", "Search member");
            args.put("limit", 50);
            if (cursor != null) args.put("cursor", cursor);
            String encoded = onWorker(tools, args);
            Map<?, ?> page = result(encoded);
            pages++;
            assertTrue(encoded.length() <= ArcadiaModelTools.MAX_OUTPUT_CHARS);
            assertFalse(encoded.contains("output_truncated"));
            for (Object value : (List<?>) page.get("matches"))
                assertTrue(ids.add((String) ((Map<?, ?>) value).get("id")));
            assertTrue(((List<?>) page.get("matches")).size() <= 50);
            cursor = (String) page.get("cursor");
            assertEquals(cursor == null, page.get("complete"));
        } while (cursor != null);
        assertEquals(3, pages);
        assertEquals(121, ids.size());
        assertTrue(ids.contains(functions.get(120).getId()));
    }

    @Test
    public void outputBoundaryRetainsTheAlreadyTraversedMatchWithoutCompactionOrLoss()
            throws Exception {
        createModel(50);
        for (int index = 0; index < functions.size(); index++) {
            functions.get(index).setId("native-id-" + index + "\u0001".repeat(180));
            functions.get(index).setName("Search member " + index + "\u0002".repeat(180));
        }
        activateDomain();
        var tools = new ArcadiaModelTools(session);
        var ids = new HashSet<String>();
        String cursor = null;
        int pages = 0;
        do {
            var args = new java.util.LinkedHashMap<String, Object>();
            args.put("query", "Search member");
            args.put("limit", 50);
            if (cursor != null) args.put("cursor", cursor);
            String encoded = onWorker(tools, args);
            Map<?, ?> page = result(encoded);
            pages++;
            assertTrue(encoded.length() <= ArcadiaModelTools.MAX_OUTPUT_CHARS);
            assertFalse(encoded.contains("output_truncated"));
            for (Object value : (List<?>) page.get("matches"))
                assertTrue(ids.add((String) ((Map<?, ?>) value).get("id")));
            cursor = (String) page.get("cursor");
        } while (cursor != null);
        assertTrue(pages > 1);
        assertEquals(50, ids.size());
        assertTrue(ids.contains(functions.get(49).getId()));
    }

    @Test
    public void objectBudgetReturnsPartialReceiptAndResumesWithIdenticalFilters() throws Exception {
        createModel(3100);
        functions.get(3099).setName("Late budget target");
        activateDomain();
        var tools = tools(1000, 2_000_000_000L, System::nanoTime, () -> {});
        String cursor = null;
        boolean found = false;
        int pages = 0, previousTotal = 0;
        do {
            var args = new java.util.LinkedHashMap<String, Object>();
            args.put("query", "Late budget target");
            if (cursor != null) args.put("cursor", cursor);
            Map<?, ?> page = result(onWorker(tools, args));
            pages++;
            int inspected = ((Number) page.get("scanned_this_page")).intValue();
            assertTrue(inspected <= 1000);
            assertEquals(
                    previousTotal + inspected, ((Number) page.get("scanned_total")).intValue());
            previousTotal += inspected;
            found |= !((List<?>) page.get("matches")).isEmpty();
            cursor = (String) page.get("cursor");
            if (cursor != null) {
                assertEquals(false, page.get("complete"));
                assertEquals("object_budget", page.get("stopped_by"));
            } else assertEquals(true, page.get("complete"));
        } while (cursor != null);
        assertEquals(4, pages);
        assertTrue(found);
    }

    @Test
    public void timeBudgetReturnsCursorRatherThanFalseAbsenceAndCanResume() throws Exception {
        createModel(100);
        functions.get(99).setName("Late timed target");
        activateDomain();
        AtomicLong clock = new AtomicLong();
        var tools = tools(250000, 100_000_000L, () -> clock.getAndAdd(1_000_000L), () -> {});
        Map<?, ?> first = result(onWorker(tools, Map.of("query", "Late timed target")));
        assertEquals(false, first.get("complete"));
        assertEquals(false, first.get("traversal_complete"));
        assertEquals("time_budget", first.get("stopped_by"));
        assertNotNull(first.get("cursor"));
        String cursor = (String) first.get("cursor");
        boolean found = false;
        for (int attempt = 0; cursor != null && attempt < 10; attempt++) {
            Map<?, ?> page =
                    result(onWorker(tools, Map.of("query", "Late timed target", "cursor", cursor)));
            found |= !((List<?>) page.get("matches")).isEmpty();
            cursor = (String) page.get("cursor");
        }
        assertTrue(found);
        assertNull(cursor);
    }

    @Test
    public void nativeUiCallerGetsOneSliceWithoutDispatchingOrBlockingItsOwnEventLoop()
            throws Exception {
        createModel(3100);
        functions.get(3099).setName("Late UI target");
        activateDomain();
        AtomicInteger slices = new AtomicInteger();
        var tools = tools(250000, 2_000_000_000L, System::nanoTime, slices::incrementAndGet);
        final Map<?, ?>[] captured = new Map<?, ?>[1];
        SessionSafeguards.runOnUiThread(
                () -> {
                    try {
                        captured[0] =
                                result(
                                        tools.execute(
                                                "model_search", Map.of("query", "Late UI target")));
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                });
        assertEquals(1, slices.get());
        assertEquals(false, captured[0].get("complete"));
        assertEquals("ui_slice", captured[0].get("stopped_by"));
        assertNotNull(captured[0].get("cursor"));
    }

    @Test
    public void nonUndoWriteBetweenSlicesInvalidatesTheWholeLogicalResult() throws Exception {
        createModel(3100);
        activateDomain();
        AtomicInteger slices = new AtomicInteger();
        var tools =
                tools(
                        250000,
                        2_000_000_000L,
                        System::nanoTime,
                        () -> {
                            if (slices.incrementAndGet() == 1)
                                SessionSafeguards.runOnUiThread(
                                        () -> {
                                            try {
                                                ((TransactionalCommandStack)
                                                                domain.getCommandStack())
                                                        .execute(
                                                                new RecordingCommand(domain) {
                                                                    @Override
                                                                    protected void doExecute() {
                                                                        functions
                                                                                .get(3099)
                                                                                .setName(
                                                                                        "Changed"
                                                                                            + " without"
                                                                                            + " Undo");
                                                                    }
                                                                },
                                                                Map.of(
                                                                        Transaction.OPTION_NO_UNDO,
                                                                        true));
                                                // A write followed by native history flush has the
                                                // same
                                                // command-head identities as the initial empty
                                                // stack.
                                                domain.getCommandStack().flush();
                                            } catch (Exception failure) {
                                                throw new AssertionError(failure);
                                            }
                                        });
                        });
        // A fresh search restarts from scratch, so its single result reflects one consistent model
        // state.
        Map<?, ?> page = result(onWorker(tools, Map.of("query", "Changed without Undo")));
        assertEquals(
                "The restarted search sees the write", 1, ((List<?>) page.get("matches")).size());
        assertEquals(Boolean.TRUE, page.get("complete"));
        assertNull(
                "The revision guard must work even when command-stack identity is unchanged",
                domain.getCommandStack().getUndoCommand());
        assertEquals("Changed without Undo", functions.get(3099).getName());
    }

    @Test
    public void aModelThatKeepsChangingStillFailsAfterBoundedRestarts() throws Exception {
        createModel(3100);
        activateDomain();
        AtomicInteger slices = new AtomicInteger();
        var tools =
                tools(
                        250000,
                        2_000_000_000L,
                        System::nanoTime,
                        () ->
                                SessionSafeguards.runOnUiThread(
                                        () ->
                                                write(
                                                        () ->
                                                                functions
                                                                        .get(3099)
                                                                        .setName(
                                                                                "Write "
                                                                                        + slices
                                                                                                .incrementAndGet()))));
        Throwable failure = workerFailure(tools, Map.of("query", "Absent name"));
        assertTrue(failure instanceof IllegalStateException);
        assertTrue(failure.getMessage().contains("Model changed"));
        assertTrue("Restarts are bounded", slices.get() <= 4);
    }

    @Test
    public void committedEditAndDomainChangeInvalidateRetainedCursors() throws Exception {
        createModel(20);
        activateDomain();
        var tools = new ArcadiaModelTools(session);
        String cursor =
                (String)
                        result(onWorker(tools, Map.of("query", "Search member", "limit", 1)))
                                .get("cursor");
        write(() -> functions.get(19).setName("Changed after partial search"));
        assertTrue(
                workerFailure(tools, Map.of("query", "Search member", "cursor", cursor))
                        instanceof IllegalStateException);
        var next = new ArcadiaModelTools(session);
        String nextCursor =
                (String)
                        result(onWorker(next, Map.of("query", "Search member", "limit", 1)))
                                .get("cursor");
        TransactionalEditingDomain old = domain;
        domain =
                TransactionalEditingDomain.Factory.INSTANCE.createEditingDomain(
                        new ResourceSetImpl());
        try {
            assertTrue(
                    workerFailure(next, Map.of("query", "Search member", "cursor", nextCursor))
                            instanceof IllegalStateException);
        } finally {
            domain.dispose();
            domain = old;
        }
    }

    @Test
    public void retainedCursorRejectsInterveningWriteEvenAfterItsCommandHistoryIsFlushed()
            throws Exception {
        createModel(20);
        activateDomain();
        var tools = new ArcadiaModelTools(session);
        String cursor =
                (String)
                        result(onWorker(tools, Map.of("query", "Search member", "limit", 1)))
                                .get("cursor");
        write(() -> functions.get(19).setName("Changed between logical calls"));
        domain.getCommandStack().flush();
        assertNull(domain.getCommandStack().getUndoCommand());
        assertTrue(
                workerFailure(tools, Map.of("query", "Search member", "cursor", cursor))
                        instanceof IllegalStateException);
        assertEquals("Changed between logical calls", functions.get(19).getName());
        assertEquals(0, saves.get());
    }

    @Test
    public void skippedNativeProxyDoesNotResolveOrEstablishFalseAbsence() throws Exception {
        createModel(1);
        var proxy = LaFactory.eINSTANCE.createLogicalFunction();
        proxy.setId(UUID.randomUUID().toString());
        ((InternalEObject) proxy).eSetProxyURI(URI.createURI("memory:/must-not-load.xmi#missing"));
        root.getOwnedFunctions().add(proxy);
        activateDomain();
        int resources = domain.getResourceSet().getResources().size(), adapters = adapterCount();
        Map<?, ?> page =
                result(onWorker(new ArcadiaModelTools(session), Map.of("query", "Missing name")));
        assertEquals(true, page.get("traversal_complete"));
        assertEquals(false, page.get("complete"));
        assertEquals(false, page.get("evidence_available"));
        assertNull(page.get("cursor"));
        assertEquals(
                1,
                ((Number)
                                ((Map<?, ?>) page.get("unavailable_evidence"))
                                        .get("unresolved_containment"))
                        .intValue());
        assertTrue(proxy.eIsProxy());
        assertEquals(resources, domain.getResourceSet().getResources().size());
        assertEquals(adapters, adapterCount());
    }

    @Test
    public void skippedForeignContainmentIsUnavailableEvenAfterTraversalFinishes()
            throws Exception {
        createModel(1);
        Resource foreign =
                new org.eclipse.emf.ecore.xmi.impl.XMIResourceImpl(
                        URI.createURI("memory:/foreign.xmi"));
        root.getOwnedFunctions()
                .add(
                        new LogicalFunctionImpl() {
                            @Override
                            public Resource eResource() {
                                return foreign;
                            }
                        });
        activateDomain();
        Map<?, ?> page =
                result(onWorker(new ArcadiaModelTools(session), Map.of("query", "Missing name")));
        assertEquals(true, page.get("traversal_complete"));
        assertEquals(false, page.get("complete"));
        assertEquals(
                1,
                ((Number) ((Map<?, ?>) page.get("unavailable_evidence")).get("foreign_containment"))
                        .intValue());
        assertEquals(1, domain.getResourceSet().getResources().size());
    }

    @Test
    public void loadErrorsAndUnloadedResourcesRemainExplicitUnavailableEvidence() throws Exception {
        createModel(1);
        resource.getErrors()
                .add(
                        new Resource.Diagnostic() {
                            @Override
                            public String getMessage() {
                                return "Private native diagnostic text must not be returned";
                            }

                            @Override
                            public String getLocation() {
                                return "private-location";
                            }

                            @Override
                            public int getLine() {
                                return 1;
                            }

                            @Override
                            public int getColumn() {
                                return 1;
                            }
                        });
        Resource unloaded =
                resource.getResourceSet().createResource(URI.createURI("memory:/unloaded.xmi"));
        activateDomain();
        session = session(List.of(resource, unloaded));
        String encoded = onWorker(new ArcadiaModelTools(session), Map.of("query", "Missing name"));
        Map<?, ?> page = result(encoded);
        assertEquals(true, page.get("traversal_complete"));
        assertEquals(false, page.get("complete"));
        assertEquals(false, page.get("evidence_available"));
        assertEquals(
                1,
                ((Number) ((Map<?, ?>) page.get("unavailable_evidence")).get("load_errors"))
                        .intValue());
        assertEquals(
                1,
                ((Number) ((Map<?, ?>) page.get("unavailable_evidence")).get("unloaded_resources"))
                        .intValue());
        assertFalse(encoded.contains("Private native"));
        assertFalse(encoded.contains("private-location"));
        assertFalse(unloaded.isLoaded());
    }

    @Test
    public void cancelledOrClosedModelBetweenSlicesCannotReturnSuccessOrContinueScanning()
            throws Exception {
        createModel(3100);
        activateDomain();
        AtomicInteger slices = new AtomicInteger();
        var cancelled =
                tools(
                        250000,
                        2_000_000_000L,
                        System::nanoTime,
                        () -> {
                            slices.incrementAndGet();
                            Thread.currentThread().interrupt();
                        });
        assertTrue(
                workerFailure(cancelled, Map.of("query", "Missing name"))
                        instanceof IllegalStateException);
        assertEquals(1, slices.get());
        var closed = tools(250000, 2_000_000_000L, System::nanoTime, () -> open = false);
        assertTrue(
                workerFailure(closed, Map.of("query", "Missing name"))
                        instanceof IllegalStateException);
        open = true;
        assertEquals(0, saves.get());
        assertNull(domain.getCommandStack().getUndoCommand());
    }

    @Test
    public void cursorRetainsFiltersAndUnavailableEvidenceAcrossBoundedCalls() throws Exception {
        createModel(20);
        var proxy = LaFactory.eINSTANCE.createLogicalFunction();
        ((InternalEObject) proxy).eSetProxyURI(URI.createURI("memory:/never.xmi#proxy"));
        root.getOwnedFunctions().add(0, proxy);
        activateDomain();
        var tools = tools(10, 2_000_000_000L, System::nanoTime, () -> {});
        Map<?, ?> first = result(onWorker(tools, Map.of("query", "No match")));
        String cursor = (String) first.get("cursor");
        assertNotNull(cursor);
        assertEquals(false, first.get("evidence_available"));
        assertTrue(
                workerFailure(tools, Map.of("query", "Changed filter", "cursor", cursor))
                        instanceof IllegalArgumentException);
        String valid = (String) result(onWorker(tools, Map.of("query", "No match"))).get("cursor");
        Map<?, ?> last = null;
        while (valid != null) {
            last = result(onWorker(tools, Map.of("query", "No match", "cursor", valid)));
            valid = (String) last.get("cursor");
        }
        assertEquals(true, last.get("traversal_complete"));
        assertEquals(false, last.get("complete"));
        assertEquals(false, last.get("evidence_available"));
    }

    private void createModel(int count) {
        var resources = new ResourceSetImpl();
        resources
                .getResourceFactoryRegistry()
                .getExtensionToFactoryMap()
                .put("xmi", new XMIResourceFactoryImpl());
        resource = resources.createResource(URI.createURI("memory:/search-drain.xmi"));
        var project = CapellamodellerFactory.eINSTANCE.createProject();
        project.setId(UUID.randomUUID().toString());
        project.setName("Search project");
        var engineering = CapellamodellerFactory.eINSTANCE.createSystemEngineering();
        project.getOwnedModelRoots().add(engineering);
        var architecture = LaFactory.eINSTANCE.createLogicalArchitecture();
        engineering.getOwnedArchitectures().add(architecture);
        var pkg = LaFactory.eINSTANCE.createLogicalFunctionPkg();
        architecture.setOwnedFunctionPkg(pkg);
        root = LaFactory.eINSTANCE.createLogicalFunction();
        root.setId(UUID.randomUUID().toString());
        root.setName("Search root");
        pkg.getOwnedLogicalFunctions().add(root);
        for (int index = 0; index < count; index++) {
            var function = LaFactory.eINSTANCE.createLogicalFunction();
            function.setId(new UUID(1L, index).toString());
            function.setName("Search member " + index);
            root.getOwnedFunctions().add(function);
            functions.add(function);
        }
        resource.getContents().add(project);
    }

    private void activateDomain() {
        domain =
                TransactionalEditingDomain.Factory.INSTANCE.createEditingDomain(
                        resource.getResourceSet());
        open = true;
        session = session(List.of(resource));
    }

    private Session session(List<Resource> resources) {
        return (Session)
                Proxy.newProxyInstance(
                        Session.class.getClassLoader(),
                        new Class<?>[] {Session.class},
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "isOpen" -> open;
                                    case "getSemanticResources" -> resources;
                                    case "getTransactionalEditingDomain" -> domain;
                                    case "getSessionResource" -> resource;
                                    case "save" -> {
                                        saves.incrementAndGet();
                                        throw new AssertionError("Search must never save");
                                    }
                                    case "equals" -> proxy == args[0];
                                    case "hashCode" -> System.identityHashCode(proxy);
                                    default -> null;
                                });
    }

    private ArcadiaModelTools tools(int objects, long nanos, LongSupplier clock, Runnable observer)
            throws Exception {
        var constructor =
                ArcadiaModelTools.class.getDeclaredConstructor(
                        Session.class,
                        IntFunction.class,
                        LongSupplier.class,
                        int.class,
                        long.class,
                        LongSupplier.class,
                        Runnable.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                session,
                (IntFunction<Map<String, Object>>) maximum -> Map.of(),
                (LongSupplier) System::nanoTime,
                objects,
                nanos,
                clock,
                observer);
    }

    private String onWorker(ArcadiaModelTools tools, Map<String, Object> args) throws Exception {
        FutureTask<String> task = new FutureTask<>(() -> tools.execute("model_search", args));
        Thread worker = new Thread(task, "native-search-test");
        worker.setDaemon(true);
        worker.start();
        Display display = Display.getDefault();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!task.isDone() && System.nanoTime() < deadline) {
            if (Display.getCurrent() == display) {
                if (!display.readAndDispatch()) Thread.sleep(1);
            } else Thread.sleep(1);
        }
        try {
            return task.get(1, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            throw new AssertionError(failure.getCause());
        } finally {
            if (!task.isDone()) worker.interrupt();
        }
    }

    private Throwable workerFailure(ArcadiaModelTools tools, Map<String, Object> args) {
        try {
            onWorker(tools, args);
            fail("Incomplete or changed native evidence must not return success");
            return null;
        } catch (Exception failure) {
            return failure;
        }
    }

    private static Map<?, ?> result(String encoded) {
        return (Map<?, ?>)
                ((Map<?, ?>) AgentJson.parse(encoded, ArcadiaModelTools.MAX_OUTPUT_CHARS))
                        .get("result");
    }

    private void write(Runnable action) {
        domain.getCommandStack()
                .execute(
                        new RecordingCommand(domain) {
                            @Override
                            protected void doExecute() {
                                action.run();
                            }
                        });
    }

    private byte[] bytes() throws Exception {
        var stream = new ByteArrayOutputStream();
        resource.save(stream, Map.of());
        return stream.toByteArray();
    }

    private int adapterCount() {
        int count = domain.getResourceSet().eAdapters().size() + resource.eAdapters().size();
        var iterator =
                org.eclipse.emf.ecore.util.EcoreUtil.<EObject>getAllContents(resource, false);
        while (iterator.hasNext()) count += iterator.next().eAdapters().size();
        return count;
    }
}
