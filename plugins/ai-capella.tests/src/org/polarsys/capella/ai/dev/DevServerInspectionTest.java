package org.polarsys.capella.ai.dev;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.eclipse.emf.transaction.RecordingCommand;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.emf.transaction.TransactionalEditingDomainEvent;
import org.eclipse.emf.transaction.TransactionalEditingDomainListenerImpl;
import org.eclipse.emf.transaction.util.TransactionUtil;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.diagram.DiagramFactory;
import org.eclipse.swt.widgets.Display;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.polarsys.capella.ai.agent.AgentJson;
import org.polarsys.capella.core.data.capellamodeller.CapellamodellerFactory;
import org.polarsys.capella.core.data.ctx.CtxFactory;
import org.polarsys.capella.core.data.ctx.SystemFunction;
import org.polarsys.capella.core.data.fa.FaFactory;

/** Real native model and read transactions; Session is the only service proxy. */
public class DevServerInspectionTest {
    private Resource semantic;
    private Resource views;
    private TransactionalEditingDomain domain;
    private final List<SystemFunction> functions = new ArrayList<>();
    private final List<AtomicBoolean> openSessions = new ArrayList<>();
    private final AtomicInteger saves = new AtomicInteger();
    private final AtomicInteger readTransactions = new AtomicInteger();
    private final AtomicReference<Resource> sessionResource = new AtomicReference<>();
    private final AtomicReference<TransactionalEditingDomain> sessionDomain = new AtomicReference<>();
    private Session session;

    @Before public void createModel() {
        Display.getDefault();
        DevServer.stop();
        var resources = new ResourceSetImpl();
        resources.getResourceFactoryRegistry().getExtensionToFactoryMap().put("xmi", new XMIResourceFactoryImpl());
        semantic = resources.createResource(URI.createURI("memory:/inspect-model.xmi"));
        views = resources.createResource(URI.createURI("memory:/inspect-session.xmi"));
        var project = CapellamodellerFactory.eINSTANCE.createProject(); project.setId("inspect-project");
        project.setName("Inspection project"); semantic.getContents().add(project);
        var engineering = CapellamodellerFactory.eINSTANCE.createSystemEngineering();
        project.getOwnedModelRoots().add(engineering);
        var architecture = CtxFactory.eINSTANCE.createSystemAnalysis(); architecture.setName("SA");
        engineering.getOwnedArchitectures().add(architecture);
        var functionPkg = CtxFactory.eINSTANCE.createSystemFunctionPkg(); architecture.setOwnedFunctionPkg(functionPkg);
        architecture.setOwnedSystemComponentPkg(CtxFactory.eINSTANCE.createSystemComponentPkg());
        var root = CtxFactory.eINSTANCE.createSystemFunction(); root.setName("Root function");
        functionPkg.getOwnedSystemFunctions().add(root);
        for (int index = 0; index < 12; index++) {
            var function = CtxFactory.eINSTANCE.createSystemFunction();
            function.setId("inspect-function-" + index); function.setName("Candidate " + index);
            root.getOwnedFunctions().add(function); functions.add(function);
        }
        var diagram = DiagramFactory.eINSTANCE.createDSemanticDiagram();
        diagram.setTarget(architecture); views.getContents().add(diagram);
        var node = DiagramFactory.eINSTANCE.createDNode(); node.setTarget(functions.get(0)); diagram.getOwnedDiagramElements().add(node);
        semantic.setTrackingModification(true); views.setTrackingModification(true);
        domain = TransactionalEditingDomain.Factory.INSTANCE.createEditingDomain(resources);
        sessionResource.set(views); sessionDomain.set(domain); session = newSession(List.of(semantic));
    }

    @After public void closeModel() {
        Thread.interrupted();
        DevServer.stop();
        if (domain != null) domain.dispose();
    }

    @Test public void fixedReadsPreserveSemanticAndViewBytesDirtyFlagsUndoRedoAndAdapters() throws Exception {
        write(() -> functions.get(0).setSummary("Keep this undo command"));
        write(() -> functions.get(1).setSummary("Keep this redo command"));
        domain.getCommandStack().undo();
        Object undo = domain.getCommandStack().getUndoCommand(), redo = domain.getCommandStack().getRedoCommand();
        assertNotNull(undo); assertNotNull(redo);
        byte[] before = bytes(semantic), viewsBefore = bytes(views);
        semantic.setModified(false); views.setModified(false);
        int adapters = domain.getResourceSet().eAdapters().size(), resourceCount = domain.getResourceSet().getResources().size();
        TransactionUtil.getAdapter(domain, TransactionalEditingDomain.Lifecycle.class)
            .addTransactionalEditingDomainListener(new TransactionalEditingDomainListenerImpl() {
                @Override public void transactionStarted(TransactionalEditingDomainEvent event) {
                    assertTrue("Every model transaction must be native read-only", event.getTransaction().isReadOnly());
                    assertSame(Display.getDefault(), Display.getCurrent()); readTransactions.incrementAndGet();
                }
            });
        String summary = inspect("model_summary", Map.of());
        assertTrue(summary.contains("Inspection project"));
        inspect("model_search", Map.of("query", "Candidate", "type", "SystemFunction", "layer", "SA"));
        inspect("model_inspect", Map.of("id", "inspect-function-0", "limit", 30));
        inspect("model_relationships", Map.of("id", "inspect-function-0"));
        inspect("model_validate", Map.of("ids", List.of("inspect-function-0", "inspect-function-1")));
        inspect("capella_capabilities", Map.of("module", "diagram"));
        inspect("capella_docs", Map.of("path", "api/create.md"));
        assertTrue(readTransactions.get() >= 5);
        assertFalse(semantic.isModified()); assertFalse(views.isModified()); assertEquals(0, saves.get());
        assertSame(undo, domain.getCommandStack().getUndoCommand()); assertSame(redo, domain.getCommandStack().getRedoCommand());
        assertEquals(adapters, domain.getResourceSet().eAdapters().size());
        assertEquals(resourceCount, domain.getResourceSet().getResources().size());
        assertArrayEquals(before, bytes(semantic)); assertArrayEquals(viewsBefore, bytes(views));
        semantic.setModified(true); views.setModified(true);
        inspect("model_summary", Map.of());
        assertTrue("An existing dirty state must survive inspection", semantic.isModified()); assertTrue(views.isModified());
    }

    @Test public void pagingUsesCapturedSessionToolsAndCursorsCannotCrossSessionsOrEdits() throws Exception {
        var seen = new HashSet<String>(); String cursor = null; int pages = 0;
        do {
            var args = new LinkedHashMap<String, Object>(); args.put("query", "Candidate"); args.put("limit", 1);
            if (cursor != null) args.put("cursor", cursor);
            Map<?, ?> result = result(inspect("model_search", args));
            for (Object match : (List<?>) result.get("matches")) assertTrue(seen.add((String) ((Map<?, ?>) match).get("id")));
            cursor = (String) result.get("cursor");
            assertTrue("Paging must terminate", ++pages <= 20);
        } while (cursor != null);
        assertEquals(12, seen.size()); assertEquals(0, saves.get());
        String foreignCursor = (String) result(inspect("model_search", Map.of("query", "Candidate", "limit", 1))).get("cursor");
        Session other = newSession(List.of(semantic));
        assertThrows(IllegalArgumentException.class, () -> inspect(other, request("model_search",
            Map.of("query", "Candidate", "cursor", foreignCursor))));
        String editedCursor = (String) result(inspect("model_search", Map.of("query", "Candidate", "limit", 1))).get("cursor");
        write(() -> functions.get(0).setSummary("A native committed edit invalidates the cursor"));
        assertThrows(IllegalStateException.class, () -> inspect("model_search", Map.of("query", "Candidate", "cursor", editedCursor)));
    }

    @Test public void fixedStatusReportsNativeHistoryAndResourceFlagsWithoutChangingThem() throws Exception {
        write(() -> functions.get(0).setSummary("Undo fixture"));
        write(() -> functions.get(1).setSummary("Redo fixture")); domain.getCommandStack().undo();
        Object undo = domain.getCommandStack().getUndoCommand(), redo = domain.getCommandStack().getRedoCommand();
        byte[] before = bytes(semantic), viewsBefore = bytes(views); semantic.setModified(true); views.setModified(false);
        TransactionUtil.getAdapter(domain, TransactionalEditingDomain.Lifecycle.class)
            .addTransactionalEditingDomainListener(new TransactionalEditingDomainListenerImpl() {
                @Override public void transactionStarted(TransactionalEditingDomainEvent event) {
                    assertTrue(event.getTransaction().isReadOnly()); assertSame(Display.getDefault(), Display.getCurrent());
                    readTransactions.incrementAndGet();
                }
            });
        var method = DevServer.class.getDeclaredMethod("status", Session.class); method.setAccessible(true);
        Map<?, ?> status = (Map<?, ?>) AgentJson.parse((String) method.invoke(null, session), 10000);
        assertEquals(true, status.get("hasSession")); assertEquals(views.getURI().toString(), status.get("session_uri"));
        assertEquals(true, status.get("can_undo")); assertEquals(true, status.get("can_redo"));
        assertEquals("Prepare native fixture", status.get("undo_label")); assertEquals("Prepare native fixture", status.get("redo_label"));
        assertEquals(false, status.get("session_modified"));
        assertEquals(List.of(Map.of("uri", semantic.getURI().toString(), "modified", true)), status.get("semantic_resources"));
        assertEquals(1, readTransactions.get()); assertTrue(semantic.isModified()); assertFalse(views.isModified());
        assertSame(undo, domain.getCommandStack().getUndoCommand()); assertSame(redo, domain.getCommandStack().getRedoCommand());
        assertEquals(0, saves.get()); assertArrayEquals(before, bytes(semantic)); assertArrayEquals(viewsBefore, bytes(views));
    }

    @Test public void storedProxyReferencesAreReportedWithoutResolvingLoadingOrDirtyingTheModel() throws Exception {
        var proxy = CtxFactory.eINSTANCE.createSystemFunction();
        ((InternalEObject) proxy).eSetProxyURI(URI.createURI("memory:/never-load.xmi#missing"));
        write(() -> {
            var trace = FaFactory.eINSTANCE.createFunctionRealization(); trace.setId("inspect-unresolved-trace");
            trace.setSourceElement(functions.get(0)); trace.setTargetElement(proxy);
            functions.get(0).getOwnedFunctionRealizations().add(trace);
        });
        byte[] before = bytes(semantic), viewsBefore = bytes(views); semantic.setModified(false); views.setModified(false);
        int count = domain.getResourceSet().getResources().size(); Object undo = domain.getCommandStack().getUndoCommand();
        assertTrue(inspect("model_inspect", Map.of("id", "inspect-unresolved-trace")).contains("\"unresolved\":true"));
        assertTrue(inspect("model_validate", Map.of("id", "inspect-unresolved-trace")).contains("Unresolved/detached"));
        assertTrue(proxy.eIsProxy()); assertEquals(count, domain.getResourceSet().getResources().size());
        assertFalse(semantic.isModified()); assertFalse(views.isModified()); assertEquals(0, saves.get());
        assertSame(undo, domain.getCommandStack().getUndoCommand());
        assertArrayEquals(before, bytes(semantic)); assertArrayEquals(viewsBefore, bytes(views));
    }

    @Test public void requestSchemaAndFixedToolsRejectCodeWritesForeignUrisAndWorkspacePaths() throws Exception {
        byte[] before = bytes(semantic), viewsBefore = bytes(views); semantic.setModified(false); views.setModified(false);
        Object undo = domain.getCommandStack().getUndoCommand(), redo = domain.getCommandStack().getRedoCommand();
        assertThrows(IllegalArgumentException.class, () -> inspect(session, AgentJson.write(Map.of(
            "session_uri", "memory:/another-session.xmi", "tool", "model_summary"))));
        assertThrows(IllegalArgumentException.class, () -> inspect(session, request("execute_groovy", Map.of("code", "api.delete(...)"))));
        for (String key : List.of("code", "script", "shell", "fs", "path")) {
            var body = new LinkedHashMap<String, Object>(); body.put("session_uri", views.getURI().toString());
            body.put("tool", "model_summary"); body.put(key, "target.eSet(...)");
            assertThrows(key, IllegalArgumentException.class, () -> inspect(session, AgentJson.write(body)));
        }
        assertThrows(IllegalArgumentException.class, () -> inspect(session, "target.eSet(target.eClass().getEStructuralFeature('name'), 'changed')"));
        assertThrows(IllegalArgumentException.class, () -> inspect("model_search", Map.of("query", "Candidate", "aql", "self.eContents()")));
        assertThrows(IllegalArgumentException.class, () -> inspect("model_inspect", Map.of("id", "inspect-function-0", "code", "eSet(...)")));
        for (String path : List.of("../README.md", "C:/secret.txt", "workspace/secrets.txt", "api/../../secret.txt")) {
            assertThrows(IllegalArgumentException.class, () -> inspect("capella_docs", Map.of("path", path)));
        }
        assertThrows(IllegalArgumentException.class, () -> inspect(session, " ".repeat(32769)));
        assertThrows(IllegalArgumentException.class, () -> inspect(session, "⚡".repeat(11000)));
        assertFalse(semantic.isModified()); assertFalse(views.isModified()); assertEquals(0, saves.get());
        assertSame(undo, domain.getCommandStack().getUndoCommand()); assertSame(redo, domain.getCommandStack().getRedoCommand());
        assertArrayEquals(before, bytes(semantic)); assertArrayEquals(viewsBefore, bytes(views));
    }

    @Test public void boundedContextCacheExpiresEvictsClosesAndRebindsNativeResourceIdentity() throws Exception {
        for (int index = 0; index < 6; index++) inspect(newSession(List.of(semantic)), request("model_summary", Map.of()));
        assertEquals(4, contexts().size());
        for (Object context : contexts().values()) {
            var used = context.getClass().getDeclaredField("usedNanos"); used.setAccessible(true);
            used.setLong(context, System.nanoTime() - TimeUnit.MINUTES.toNanos(11));
        }
        inspect("model_summary", Map.of()); assertEquals(1, contexts().size());
        String oldCursor = (String) result(inspect("model_search", Map.of("query", "Candidate", "limit", 1))).get("cursor");
        Resource replacement = new org.eclipse.emf.ecore.xmi.impl.XMIResourceImpl(views.getURI());
        domain.getResourceSet().getResources().add(replacement); sessionResource.set(replacement);
        assertThrows(IllegalArgumentException.class, () -> inspect("model_search", Map.of("query", "Candidate", "cursor", oldCursor)));
        assertEquals(1, contexts().size());
        for (AtomicBoolean open : openSessions) open.set(false);
        assertThrows(IllegalArgumentException.class, () -> inspect("model_summary", Map.of()));
        assertEquals(0, contexts().size()); assertEquals(0, saves.get());
        DevServer.stop(); assertEquals(0, contexts().size());
    }

    @Test public void closedMissingAndForeignDomainsAndInterruptedRequestsFailWithoutCommands() throws Exception {
        semantic.setModified(false); views.setModified(false);
        assertThrows(IllegalArgumentException.class, () -> inspect(null, request("model_summary", Map.of())));
        sessionDomain.set(null);
        assertThrows(IllegalArgumentException.class, () -> inspect("model_summary", Map.of())); sessionDomain.set(domain);
        var foreignSet = new ResourceSetImpl(); var foreign = new org.eclipse.emf.ecore.xmi.impl.XMIResourceImpl(URI.createURI("memory:/foreign.xmi"));
        foreignSet.getResources().add(foreign);
        assertThrows(IllegalStateException.class, () -> inspect(newSession(List.of(foreign)), request("model_summary", Map.of())));
        Thread.currentThread().interrupt();
        try { assertThrows(IllegalArgumentException.class, () -> inspect("model_summary", Map.of())); }
        finally { Thread.interrupted(); }
        assertNull(domain.getCommandStack().getUndoCommand()); assertNull(domain.getCommandStack().getRedoCommand());
        assertFalse(semantic.isModified()); assertFalse(views.isModified()); assertEquals(0, saves.get());
    }

    @Test public void httpInspectionRejectsBrowserMalformedExecutableAndOversizeRequestsBeforeSessionCapture() throws Exception {
        DevServer.start(0); var address = DevServer.getAddress(); assertNotNull(address);
        assertTrue(address.getAddress().isLoopbackAddress());
        var endpoint = new java.net.URI("http", null, address.getAddress().getHostAddress(), address.getPort(), "/inspect", null, null);
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var get = client.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, get.statusCode());
        var browser = client.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
            .header("Origin", "https://example.invalid").POST(HttpRequest.BodyPublishers.ofString(request("model_summary", Map.of())))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, browser.statusCode()); assertFalse(browser.headers().firstValue("Access-Control-Allow-Origin").isPresent());
        for (String body : List.of("api.delete(...)", request("execute_groovy", Map.of("code", "api.delete(...)")),
            AgentJson.write(Map.of("session_uri", views.getURI().toString(), "tool", "model_summary", "code", "eSet(...)")))) {
            var response = client.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(body, 400, response.statusCode());
        }
        var large = client.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
            .POST(HttpRequest.BodyPublishers.ofString(" ".repeat(32769))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(413, large.statusCode()); assertEquals(0, saves.get());
        assertNull(domain.getCommandStack().getUndoCommand()); assertNull(domain.getCommandStack().getRedoCommand());
    }

    private Session newSession(List<Resource> semanticResources) {
        AtomicBoolean open = new AtomicBoolean(true); openSessions.add(open);
        return (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[] {Session.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "isOpen" -> open.get();
                case "getSemanticResources" -> semanticResources;
                case "getSessionResource" -> sessionResource.get();
                case "getTransactionalEditingDomain" -> sessionDomain.get();
                case "save" -> { saves.incrementAndGet(); throw new AssertionError("Inspection must never save"); }
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "Inspection test session";
                default -> null;
            });
    }

    private String request(String tool, Map<String, Object> args) {
        return AgentJson.write(Map.of("session_uri", views.getURI().toString(), "tool", tool, "arguments", args));
    }
    private String inspect(String tool, Map<String, Object> args) throws Exception { return inspect(session, request(tool, args)); }
    private static String inspect(Session session, String json) throws Exception {
        // OSGi test and production packages are loaded separately. Exercise the
        // exact production request path without adding a public development API.
        var method = DevServer.class.getDeclaredMethod("inspect", Session.class, String.class); method.setAccessible(true);
        try { return (String) method.invoke(null, session, json); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            if (failure.getCause() instanceof Error cause) throw cause;
            throw failure;
        }
    }
    private static Map<?, ?> result(String json) {
        Map<?, ?> envelope = (Map<?, ?>) AgentJson.parse(json, 40000);
        assertEquals(true, envelope.get("read_only")); assertEquals("memory:/inspect-session.xmi", envelope.get("session_uri"));
        Map<?, ?> data = (Map<?, ?>) envelope.get("data"); assertEquals(true, data.get("ok"));
        return (Map<?, ?>) data.get("result");
    }
    private static Map<?, ?> contexts() throws Exception {
        var field = DevServer.class.getDeclaredField("INSPECTION_CONTEXTS"); field.setAccessible(true);
        return (Map<?, ?>) field.get(null);
    }
    private void write(Runnable operation) {
        domain.getCommandStack().execute(new RecordingCommand(domain, "Prepare native fixture") {
            @Override protected void doExecute() { operation.run(); }
        });
    }
    private static byte[] bytes(Resource resource) throws Exception {
        var output = new ByteArrayOutputStream(); resource.save(output, Map.of()); return output.toByteArray();
    }
}
