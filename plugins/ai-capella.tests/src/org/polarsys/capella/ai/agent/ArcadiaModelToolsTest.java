package org.polarsys.capella.ai.agent;

import static org.junit.Assert.*;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.impl.DynamicEObjectImpl;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.eclipse.emf.transaction.RecordingCommand;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.swt.widgets.Display;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.polarsys.capella.core.data.capellacore.CapellaElement;
import org.polarsys.capella.core.data.capellacore.NamedElement;
import org.polarsys.capella.core.data.capellamodeller.CapellamodellerFactory;
import org.polarsys.capella.core.data.cs.BlockArchitecture;
import org.polarsys.capella.core.data.ctx.CtxFactory;
import org.polarsys.capella.core.data.epbs.EpbsFactory;
import org.polarsys.capella.core.data.fa.AbstractFunction;
import org.polarsys.capella.core.data.fa.FaFactory;
import org.polarsys.capella.core.data.la.LaFactory;
import org.polarsys.capella.core.data.oa.OaFactory;
import org.polarsys.capella.core.data.pa.PaFactory;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/** Real Capella metamodel, resource IDs and read transactions; only Session is a proxy. */
public class ArcadiaModelToolsTest {
    private final List<BlockArchitecture> architectures = new ArrayList<>();
    private final List<EObject> samples = new ArrayList<>();
    private Resource resource;
    private TransactionalEditingDomain domain;
    private ArcadiaModelTools tools;
    private boolean open;
    private final AtomicInteger saves = new AtomicInteger();

    @Before
    public void createModel() {
        Display.getDefault();
        var resources = new ResourceSetImpl();
        resources
                .getResourceFactoryRegistry()
                .getExtensionToFactoryMap()
                .put("xmi", new XMIResourceFactoryImpl());
        resource = resources.createResource(URI.createURI("memory:/read-tools.xmi"));
        var project = CapellamodellerFactory.eINSTANCE.createProject();
        project.setName("Read tools project");
        var engineering = CapellamodellerFactory.eINSTANCE.createSystemEngineering();
        project.getOwnedModelRoots().add(engineering);
        resource.getContents().add(project);
        var oa = OaFactory.eINSTANCE.createOperationalAnalysis();
        var oaPkg = OaFactory.eINSTANCE.createOperationalActivityPkg();
        oa.setOwnedFunctionPkg(oaPkg);
        var oaFunction = OaFactory.eINSTANCE.createOperationalActivity();
        oaPkg.getOwnedOperationalActivities().add(oaFunction);
        var sa = CtxFactory.eINSTANCE.createSystemAnalysis();
        var saPkg = CtxFactory.eINSTANCE.createSystemFunctionPkg();
        sa.setOwnedFunctionPkg(saPkg);
        var saFunction = CtxFactory.eINSTANCE.createSystemFunction();
        saPkg.getOwnedSystemFunctions().add(saFunction);
        sa.setOwnedSystemComponentPkg(CtxFactory.eINSTANCE.createSystemComponentPkg());
        var la = LaFactory.eINSTANCE.createLogicalArchitecture();
        var laPkg = LaFactory.eINSTANCE.createLogicalFunctionPkg();
        la.setOwnedFunctionPkg(laPkg);
        var laFunction = LaFactory.eINSTANCE.createLogicalFunction();
        laPkg.getOwnedLogicalFunctions().add(laFunction);
        var pa = PaFactory.eINSTANCE.createPhysicalArchitecture();
        var paPkg = PaFactory.eINSTANCE.createPhysicalFunctionPkg();
        pa.setOwnedFunctionPkg(paPkg);
        var paFunction = PaFactory.eINSTANCE.createPhysicalFunction();
        paPkg.getOwnedPhysicalFunctions().add(paFunction);
        var epbs = EpbsFactory.eINSTANCE.createEPBSArchitecture();
        epbs.setOwnedConfigurationItemPkg(EpbsFactory.eINSTANCE.createConfigurationItemPkg());
        var ci = EpbsFactory.eINSTANCE.createConfigurationItem();
        epbs.getOwnedConfigurationItemPkg().getOwnedConfigurationItems().add(ci);
        architectures.addAll(List.of(oa, sa, la, pa, epbs));
        samples.addAll(List.of(oaFunction, saFunction, laFunction, paFunction, ci));
        engineering.getOwnedArchitectures().addAll(architectures);
        for (int index = 0; index < samples.size(); index++) {
            ((CapellaElement) samples.get(index)).setId("tool-sample-" + index);
            ((NamedElement) samples.get(index)).setName("Candidate function ⚡ " + index);
            architectures.get(index).setName(List.of("OA", "SA", "LA", "PA", "EPBS").get(index));
        }
        domain = TransactionalEditingDomain.Factory.INSTANCE.createEditingDomain(resources);
        open = true;
        tools = new ArcadiaModelTools(session(List.of(resource)));
    }

    @After
    public void closeModel() {
        if (domain != null) domain.dispose();
    }

    @Test
    public void nativeValidationPagesEveryFindingOnceWithoutRerunningOrChangingTheModel()
            throws Exception {
        var messages = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < 517; index++)
            messages.add(
                    Map.of(
                            "severity",
                            2,
                            "code",
                            index,
                            "plugin",
                            "native.test",
                            "message",
                            "Native warning " + index));
        AtomicInteger calls = new AtomicInteger();
        tools =
                validationTools(
                        maximum -> {
                            assertEquals(10000, maximum);
                            calls.incrementAndGet();
                            return Map.of(
                                    "severity",
                                    2,
                                    "errors",
                                    0,
                                    "warnings",
                                    517,
                                    "issueCount",
                                    517,
                                    "issues",
                                    messages,
                                    "truncated",
                                    false);
                        },
                        System::nanoTime);
        byte[] before = bytes();
        resource.setModified(false);
        Object undo = domain.getCommandStack().getUndoCommand(),
                redo = domain.getCommandStack().getRedoCommand();
        var seen = new java.util.HashSet<Integer>();
        String cursor = null, validationId = null;
        do {
            var arguments = new java.util.LinkedHashMap<String, Object>();
            arguments.put("limit", 50);
            if (cursor != null) arguments.put("cursor", cursor);
            String encoded = tools.execute("native_validation", arguments);
            Map<?, ?> page = result(encoded);
            assertEquals(517, ((Number) page.get("warnings")).intValue());
            if (validationId == null) validationId = (String) page.get("validation_id");
            assertEquals(validationId, page.get("validation_id"));
            for (Object issue : (List<?>) page.get("issues")) {
                assertTrue(
                        "Finding must not repeat",
                        seen.add(((Number) ((Map<?, ?>) issue).get("code")).intValue()));
            }
            cursor = (String) page.get("cursor");
            assertEquals(cursor == null, page.get("complete"));
            assertEquals(false, page.get("source_truncated"));
        } while (cursor != null);
        assertEquals(517, seen.size());
        assertTrue(seen.contains(516));
        assertEquals(1, calls.get());
        assertFalse(resource.isModified());
        assertEquals(0, saves.get());
        assertSame(undo, domain.getCommandStack().getUndoCommand());
        assertSame(redo, domain.getCommandStack().getRedoCommand());
        assertArrayEquals(before, bytes());
    }

    @Test
    public void validationByteBudgetNeverSkipsDiagnosticsAndSourceTruncationRemainsExplicit()
            throws Exception {
        var messages = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < 64; index++)
            messages.add(
                    Map.of(
                            "severity",
                            2,
                            "code",
                            index,
                            "plugin",
                            "native.test",
                            "message",
                            "⚡".repeat(1800)));
        tools =
                validationTools(
                        maximum ->
                                Map.of(
                                        "severity",
                                        2,
                                        "errors",
                                        0,
                                        "warnings",
                                        68,
                                        "issueCount",
                                        68,
                                        "issues",
                                        messages,
                                        "truncated",
                                        true),
                        System::nanoTime);
        var seen = new java.util.HashSet<Integer>();
        String cursor = null;
        int pages = 0;
        do {
            var arguments = new java.util.LinkedHashMap<String, Object>();
            arguments.put("limit", 50);
            if (cursor != null) arguments.put("cursor", cursor);
            String encoded = tools.execute("native_validation", arguments);
            pages++;
            assertTrue(encoded.length() <= ArcadiaModelTools.MAX_OUTPUT_CHARS);
            assertFalse(
                    "Generic compaction would silently discard diagnostics",
                    encoded.contains("output_truncated"));
            Map<?, ?> page = result(encoded);
            assertEquals(true, page.get("source_truncated"));
            assertEquals(true, page.get("truncated"));
            for (Object value : (List<?>) page.get("issues")) {
                var issue = (Map<?, ?>) value;
                assertEquals(true, issue.get("message_truncated"));
                assertTrue(seen.add(((Number) issue.get("code")).intValue()));
            }
            cursor = (String) page.get("cursor");
        } while (cursor != null);
        assertEquals(64, seen.size());
        assertTrue(pages > 2);
    }

    @Test
    public void validationCursorsAreSingleUseExpireAndRejectCommittedEditsAndUndo()
            throws Exception {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        var messages =
                List.of(
                        Map.of("severity", 2, "code", 1, "plugin", "test", "message", "One"),
                        Map.of("severity", 2, "code", 2, "plugin", "test", "message", "Two"));
        tools =
                validationTools(
                        maximum ->
                                Map.of(
                                        "severity",
                                        2,
                                        "errors",
                                        0,
                                        "warnings",
                                        2,
                                        "issueCount",
                                        2,
                                        "issues",
                                        messages,
                                        "truncated",
                                        false),
                        clock::get);
        String first = cursor(tools.execute("native_validation", Map.of("limit", 1)));
        assertNotNull(first);
        tools.execute("native_validation", Map.of("cursor", first));
        assertThrows(
                IllegalArgumentException.class,
                () -> tools.execute("native_validation", Map.of("cursor", first)));
        String expired = cursor(tools.execute("native_validation", Map.of("limit", 1)));
        clock.addAndGet(300_000_000_001L);
        assertThrows(
                IllegalArgumentException.class,
                () -> tools.execute("native_validation", Map.of("cursor", expired)));
        String changed = cursor(tools.execute("native_validation", Map.of("limit", 1)));
        write(() -> ((NamedElement) samples.get(0)).setName("Changed after validation"));
        assertThrows(
                IllegalStateException.class,
                () -> tools.execute("native_validation", Map.of("cursor", changed)));
        String beforeUndo = cursor(tools.execute("native_validation", Map.of("limit", 1)));
        domain.getCommandStack().undo();
        assertThrows(
                IllegalStateException.class,
                () -> tools.execute("native_validation", Map.of("cursor", beforeUndo)));
        assertThrows(
                IllegalArgumentException.class,
                () -> tools.execute("native_validation", Map.of("cursor", "another-request")));
    }

    private static Map<?, ?> result(String json) {
        return (Map<?, ?>)
                ((Map<?, ?>) AgentJson.parse(json, ArcadiaModelTools.MAX_OUTPUT_CHARS))
                        .get("result");
    }

    private ArcadiaModelTools validationTools(
            java.util.function.IntFunction<Map<String, Object>> validator,
            java.util.function.LongSupplier clock)
            throws Exception {
        // Test and production packages live in distinct OSGi class loaders;
        // keep the dependency-injection constructor outside the public tool API.
        var constructor =
                ArcadiaModelTools.class.getDeclaredConstructor(
                        Session.class,
                        java.util.function.IntFunction.class,
                        java.util.function.LongSupplier.class);
        constructor.setAccessible(true);
        return constructor.newInstance(session(List.of(resource)), validator, clock);
    }

    @Test
    public void summaryAndTypedSearchInspectAllFiveNativePerspectives() throws Exception {
        String summary = tools.execute("model_summary", Map.of());
        assertTrue(summary.contains("Read tools project"));
        for (int index = 0; index < samples.size(); index++) {
            String layer = List.of("OA", "SA", "LA", "PA", "EPBS").get(index);
            assertTrue(summary.contains("\"layer\":\"" + layer + "\""));
            String search =
                    tools.execute(
                            "model_search",
                            Map.of("type", samples.get(index).eClass().getName(), "layer", layer));
            assertTrue(search, search.contains("tool-sample-" + index));
            String inspection =
                    tools.execute("model_inspect", Map.of("id", "tool-sample-" + index));
            assertTrue(inspection, inspection.contains("\"id\":\"tool-sample-" + index + "\""));
            assertTrue(inspection.contains("\"attributes\""));
            assertTrue(inspection.contains("\"parent\""));
        }
    }

    @Test
    public void everyReadPreservesNativeBytesModifiedFlagUndoStackAndNeverSaves() throws Exception {
        byte[] before = bytes();
        resource.setModified(false);
        Object undo = domain.getCommandStack().getUndoCommand(),
                redo = domain.getCommandStack().getRedoCommand();
        tools.execute("model_summary", Map.of());
        tools.execute("model_search", Map.of("query", "Candidate"));
        tools.execute("model_inspect", Map.of("id", "tool-sample-2", "limit", 30));
        tools.execute("model_relationships", Map.of("id", "tool-sample-2"));
        tools.execute("model_validate", Map.of("ids", List.of("tool-sample-2", "tool-sample-4")));
        tools.execute("capella_capabilities", Map.of("module", "diagram"));
        tools.execute("capella_docs", Map.of("path", "api/create.md"));
        assertFalse(resource.isModified());
        assertEquals(0, saves.get());
        assertSame(undo, domain.getCommandStack().getUndoCommand());
        assertSame(redo, domain.getCommandStack().getRedoCommand());
        assertArrayEquals(before, bytes());
    }

    @Test
    public void incomingTypedRealizationsExposePersistentIds() throws Exception {
        write(
                () -> {
                    var realization = FaFactory.eINSTANCE.createFunctionRealization();
                    realization.setId("tool-realization");
                    realization.setSourceElement((AbstractFunction) samples.get(2));
                    realization.setTargetElement((AbstractFunction) samples.get(1));
                    ((AbstractFunction) samples.get(2))
                            .getOwnedFunctionRealizations()
                            .add(realization);
                });
        String incoming = tools.execute("model_relationships", Map.of("id", "tool-sample-1"));
        assertTrue(incoming, incoming.contains("tool-realization"));
        assertTrue(incoming.contains("FunctionRealization"));
        assertTrue(incoming.contains("targetElement"));
        String outgoing = tools.execute("model_inspect", Map.of("id", "tool-realization"));
        assertTrue(outgoing.contains("tool-sample-2"));
        assertTrue(outgoing.contains("tool-sample-1"));
    }

    @Test
    public void boundedSearchContinuesToTargetsBeyondFirstTraversalPage() throws Exception {
        write(
                () -> {
                    AbstractFunction parent = (AbstractFunction) samples.get(2);
                    for (int index = 0; index < 2600; index++) {
                        var function = LaFactory.eINSTANCE.createLogicalFunction();
                        function.setName(
                                index == 2599 ? "Last paged target" : "Other function " + index);
                        function.setId("paged-function-" + index);
                        parent.getOwnedFunctions().add(function);
                    }
                });
        String cursor = null;
        boolean found = false;
        for (int page = 0; page < 20; page++) {
            var args = new java.util.LinkedHashMap<String, Object>();
            args.put("query", "Last paged target");
            if (cursor != null) args.put("cursor", cursor);
            String result = tools.execute("model_search", args);
            found |= result.contains("paged-function-2599");
            cursor = cursor(result);
            if (cursor == null) break;
        }
        assertTrue("Search must reach beyond its per-call scan budget", found);
    }

    @Test
    public void searchCursorsCannotChangeFiltersOrSurviveCommittedEdits() throws Exception {
        String first = tools.execute("model_search", Map.of("query", "Candidate", "limit", 1));
        String cursor = cursor(first);
        assertNotNull(cursor);
        String firstCursor = cursor;
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        tools.execute(
                                "model_search",
                                Map.of("query", "Different", "cursor", firstCursor)));
        cursor = cursor(tools.execute("model_search", Map.of("query", "Candidate", "limit", 1)));
        String oldCursor = cursor;
        write(() -> ((NamedElement) samples.get(0)).setName("Edited"));
        assertThrows(
                IllegalStateException.class,
                () ->
                        tools.execute(
                                "model_search", Map.of("query", "Candidate", "cursor", oldCursor)));
    }

    @Test
    public void layerAndCanonicalTypeSearchStartAtTheRelevantArchitecture() throws Exception {
        write(
                () -> {
                    AbstractFunction parent = (AbstractFunction) samples.get(0);
                    for (int index = 0; index < 2600; index++)
                        parent.getOwnedFunctions()
                                .add(OaFactory.eINSTANCE.createOperationalActivity());
                });
        String explicit =
                tools.execute(
                        "model_search", Map.of("query", "Candidate function", "layer", "EPBS"));
        assertTrue(explicit, explicit.contains("tool-sample-4"));
        assertTrue(explicit.contains("\"complete\":true"));
        String inferred =
                tools.execute(
                        "model_search",
                        Map.of("type", "ConfigurationItem", "query", "Candidate function"));
        assertTrue(inferred, inferred.contains("tool-sample-4"));
        assertTrue(inferred.contains("\"complete\":true"));
    }

    @Test
    public void existingNativeInverseIndexCanServeIncomingLinksWithoutInstallingNewAdapters()
            throws Exception {
        write(
                () -> {
                    var trace = FaFactory.eINSTANCE.createFunctionRealization();
                    trace.setId("indexed-trace");
                    trace.setSourceElement((AbstractFunction) samples.get(2));
                    trace.setTargetElement((AbstractFunction) samples.get(1));
                    ((AbstractFunction) samples.get(2)).getOwnedFunctionRealizations().add(trace);
                });
        var inverse = new org.eclipse.emf.ecore.util.ECrossReferenceAdapter();
        domain.getResourceSet().eAdapters().add(inverse);
        int adapters = domain.getResourceSet().eAdapters().size();
        String relationships = tools.execute("model_relationships", Map.of("id", "tool-sample-1"));
        assertTrue(relationships, relationships.contains("indexed-trace"));
        assertTrue(relationships.contains("Existing native inverse-reference index"));
        assertEquals(adapters, domain.getResourceSet().eAdapters().size());
    }

    @Test
    public void foreignResourcesCannotBeSearchedInspectedOrDereferenced() throws Exception {
        var foreign = domain.getResourceSet().createResource(URI.createURI("memory:/foreign.xmi"));
        var function = LaFactory.eINSTANCE.createLogicalFunction();
        function.setId("foreign-function");
        function.setName("PRIVATE foreign name");
        write(
                () -> {
                    foreign.getContents().add(function);
                    var trace = FaFactory.eINSTANCE.createFunctionRealization();
                    trace.setSourceElement((AbstractFunction) samples.get(2));
                    trace.setTargetElement(function);
                    ((AbstractFunction) samples.get(2)).getOwnedFunctionRealizations().add(trace);
                });
        assertFalse(
                tools.execute("model_search", Map.of("query", "PRIVATE"))
                        .contains("PRIVATE foreign name"));
        assertThrows(
                IllegalArgumentException.class,
                () -> tools.execute("model_inspect", Map.of("id", "foreign-function")));
        String inspection =
                tools.execute(
                        "model_inspect",
                        Map.of(
                                "id",
                                ((AbstractFunction) samples.get(2))
                                        .getOwnedFunctionRealizations()
                                        .get(0)
                                        .getId()));
        assertTrue(inspection.contains("\"external\":true"));
        assertFalse(inspection.contains("PRIVATE foreign name"));
    }

    @Test
    public void detachedAndProxyReferenceDiagnosticsStayChangedIdScoped() throws Exception {
        write(
                () -> {
                    var trace = FaFactory.eINSTANCE.createFunctionRealization();
                    trace.setId("invalid-trace");
                    trace.setSourceElement((AbstractFunction) samples.get(2));
                    var proxy = CtxFactory.eINSTANCE.createSystemFunction();
                    ((InternalEObject) proxy)
                            .eSetProxyURI(URI.createURI("memory:/never-load.xmi#missing"));
                    trace.setTargetElement(proxy);
                    ((AbstractFunction) samples.get(2)).getOwnedFunctionRealizations().add(trace);
                });
        int resourceCount = domain.getResourceSet().getResources().size();
        String validation =
                tools.execute("model_validate", Map.of("ids", List.of("invalid-trace")));
        assertTrue(validation, validation.contains("Unresolved/detached"));
        assertTrue(validation.contains("bounded_structural"));
        assertTrue(validation.contains("\"errors\":1"));
        assertFalse(
                tools.execute("model_validate", Map.of("id", "tool-sample-4"))
                        .contains("invalid-trace"));
        assertEquals(
                "A read must not load the referenced external file",
                resourceCount,
                domain.getResourceSet().getResources().size());
    }

    @Test
    public void closedSessionsMissingDomainsAndForeignDomainsFailClosed() throws Exception {
        open = false;
        assertThrows(IllegalStateException.class, () -> tools.execute("model_summary", Map.of()));
        open = true;
        var foreignSet = new ResourceSetImpl();
        var foreign =
                new org.eclipse.emf.ecore.xmi.impl.XMIResourceImpl(
                        URI.createURI("memory:/wrong-domain.xmi"));
        foreignSet.getResources().add(foreign);
        assertThrows(
                IllegalStateException.class,
                () ->
                        new ArcadiaModelTools(session(List.of(foreign)))
                                .execute("model_summary", Map.of()));
        var noDomain =
                (Session)
                        Proxy.newProxyInstance(
                                Session.class.getClassLoader(),
                                new Class<?>[] {Session.class},
                                (proxy, method, args) ->
                                        method.getName().equals("isOpen")
                                                ? true
                                                : method.getName().equals("getSemanticResources")
                                                        ? List.of(resource)
                                                        : null);
        assertThrows(
                IllegalStateException.class,
                () ->
                        new ArcadiaModelTools(noDomain)
                                .execute("model_inspect", Map.of("id", "tool-sample-1")));
    }

    @Test
    public void toolArgumentsCannotBecomeCodeQueriesReflectionOrWorkspaceReads() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () -> tools.execute("execute_groovy", Map.of("code", "api.delete(...)")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        tools.execute(
                                "model_search",
                                Map.of("query", "Candidate", "aql", "self.eContents()")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        tools.execute(
                                "model_inspect",
                                Map.of("id", "memory:/foreign.xmi#foreign-function")));
        assertThrows(
                IllegalArgumentException.class,
                () -> tools.execute("model_search", Map.of("query", "Candidate", "limit", 1.5)));
        assertThrows(
                IllegalArgumentException.class,
                () -> tools.execute("model_search", Map.of("query", "Candidate", "limit", 51)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        tools.execute(
                                "model_validate",
                                Map.of("ids", List.of("tool-sample-0"), "layer", "LA")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        tools.execute(
                                "model_search", Map.of("type", "LogicalFunction", "layer", "ANY")));
        for (String path :
                List.of(
                        "../README.md",
                        "workspace/secrets.txt",
                        "C:/secret.txt",
                        "api/../../secret.txt",
                        "api/create.md\0")) {
            assertThrows(
                    path,
                    IllegalArgumentException.class,
                    () -> tools.execute("capella_docs", Map.of("path", path)));
        }
    }

    @Test
    public void hugeNativeTextProducesValidBoundedQuotedDataAndExplicitLimits() throws Exception {
        write(
                () ->
                        ((NamedElement) samples.get(0))
                                .setSummary(
                                        "Quoted \" text \\ controls\n\0 emoji ⚡ ".repeat(2000)));
        String result = tools.execute("model_inspect", Map.of("id", "tool-sample-0", "limit", 30));
        assertTrue(result.length() <= ArcadiaModelTools.MAX_OUTPUT_CHARS);
        assertTrue(result.contains("[truncated]"));
        assertTrue(result.contains("\\u0000"));
        assertTrue(result.startsWith("{\"ok\":true"));
        assertTrue(result.endsWith("}"));
        String capabilities = tools.execute("capella_capabilities", Map.of());
        assertTrue(capabilities.contains("limitations"));
        assertTrue(capabilities.contains("not evidence"));
        assertEquals(8, ArcadiaModelTools.toolNames().size());
    }

    @Test
    public void nativeAllocationValidationRejectsDecomposedFunctions() throws Exception {
        write(
                () -> {
                    var actor = CtxFactory.eINSTANCE.createSystemComponent();
                    actor.setActor(true);
                    ((org.polarsys.capella.core.data.ctx.SystemAnalysis) architectures.get(1))
                            .getOwnedSystemComponentPkg()
                            .getOwnedSystemComponents()
                            .add(actor);
                    var child = CtxFactory.eINSTANCE.createSystemFunction();
                    ((AbstractFunction) samples.get(1)).getOwnedFunctions().add(child);
                    var allocation = FaFactory.eINSTANCE.createComponentFunctionalAllocation();
                    allocation.setId("non-leaf-allocation");
                    allocation.setSourceElement(actor);
                    allocation.setTargetElement((AbstractFunction) samples.get(1));
                    actor.getOwnedFunctionalAllocation().add(allocation);
                });
        String result = tools.execute("model_validate", Map.of("id", "non-leaf-allocation"));
        assertTrue(result, result.contains("decomposed function"));
    }

    /**
     * LA root function with leaves A and B, components C1 and C2, and an A to B exchange through
     * ports.
     */
    private record Logical(
            org.polarsys.capella.core.data.la.LogicalFunction a,
            org.polarsys.capella.core.data.la.LogicalFunction b,
            org.polarsys.capella.core.data.la.LogicalComponent c1,
            org.polarsys.capella.core.data.la.LogicalComponent c2,
            org.polarsys.capella.core.data.fa.FunctionalExchange exchange) {}

    private Logical logicalFixture() {
        Logical[] fixture = {null};
        write(
                () -> {
                    var la =
                            (org.polarsys.capella.core.data.la.LogicalArchitecture)
                                    architectures.get(2);
                    var root = LaFactory.eINSTANCE.createLogicalFunction();
                    root.setId("la-root");
                    root.setName("Root Logical Function");
                    ((org.polarsys.capella.core.data.la.LogicalFunctionPkg)
                                    la.getOwnedFunctionPkg())
                            .getOwnedLogicalFunctions()
                            .add(root);
                    var a = LaFactory.eINSTANCE.createLogicalFunction();
                    a.setId("la-a");
                    a.setName("Acquire");
                    var b = LaFactory.eINSTANCE.createLogicalFunction();
                    b.setId("la-b");
                    b.setName("Process");
                    root.getOwnedFunctions().addAll(List.of(a, b));
                    la.setOwnedLogicalComponentPkg(LaFactory.eINSTANCE.createLogicalComponentPkg());
                    var c1 = LaFactory.eINSTANCE.createLogicalComponent();
                    c1.setId("la-c1");
                    c1.setName("Sensor");
                    var c2 = LaFactory.eINSTANCE.createLogicalComponent();
                    c2.setId("la-c2");
                    c2.setName("Computer");
                    la.getOwnedLogicalComponentPkg()
                            .getOwnedLogicalComponents()
                            .addAll(List.of(c1, c2));
                    var out = FaFactory.eINSTANCE.createFunctionOutputPort();
                    a.getOutputs().add(out);
                    var in = FaFactory.eINSTANCE.createFunctionInputPort();
                    b.getInputs().add(in);
                    var exchange = FaFactory.eINSTANCE.createFunctionalExchange();
                    exchange.setId("la-fe");
                    exchange.setName("Samples");
                    exchange.setSource(out);
                    exchange.setTarget(in);
                    root.getOwnedFunctionalExchanges().add(exchange);
                    fixture[0] = new Logical(a, b, c1, c2, exchange);
                });
        return fixture[0];
    }

    private void allocate(
            org.polarsys.capella.core.data.cs.Component component, AbstractFunction function) {
        var allocation = FaFactory.eINSTANCE.createComponentFunctionalAllocation();
        allocation.setSourceElement(component);
        allocation.setTargetElement(function);
        component.getOwnedFunctionalAllocation().add(allocation);
    }

    private static List<Map<?, ?>> findings(String json, String rule) {
        return ((List<?>) result(json).get("issues"))
                .stream()
                        .<Map<?, ?>>map(issue -> (Map<?, ?>) issue)
                        .filter(issue -> rule.equals(issue.get("rule")))
                        .toList();
    }

    @Test
    public void layerValidationReportsUnallocatedAndUnrealizedLeafFunctionsButNotTheRoot()
            throws Exception {
        Logical model = logicalFixture();
        write(
                () -> {
                    allocate(model.c1(), model.a());
                    var realization = FaFactory.eINSTANCE.createFunctionRealization();
                    realization.setSourceElement(model.a());
                    realization.setTargetElement((AbstractFunction) samples.get(1));
                    model.a().getOwnedFunctionRealizations().add(realization);
                });
        String json = tools.execute("model_validate", Map.of("layer", "LA"));
        assertEquals(
                List.of("la-b"),
                findings(json, "unallocated_leaf_function").stream()
                        .map(issue -> issue.get("id"))
                        .toList());
        assertEquals(
                List.of("la-b"),
                findings(json, "unrealized_function").stream()
                        .map(issue -> issue.get("id"))
                        .toList());
        assertEquals("warning", findings(json, "unallocated_leaf_function").get(0).get("severity"));
        assertEquals(2, ((Number) result(json).get("warnings")).intValue());
        assertEquals(0, ((Number) result(json).get("errors")).intValue());
        assertEquals("complete", result(json).get("methodology_scope"));
    }

    @Test
    public void crossComponentFunctionalExchangeNeedsAnAllocatingComponentExchange()
            throws Exception {
        Logical model = logicalFixture();
        write(
                () -> {
                    allocate(model.c1(), model.a());
                    allocate(model.c2(), model.b());
                });
        String before = tools.execute("model_validate", Map.of("layer", "LA"));
        assertEquals(
                List.of("la-fe"),
                findings(before, "unallocated_functional_exchange").stream()
                        .map(issue -> issue.get("id"))
                        .toList());
        write(
                () -> {
                    var carrier = FaFactory.eINSTANCE.createComponentExchange();
                    carrier.setName("Bus");
                    model.c1().getOwnedComponentExchanges().add(carrier);
                    var allocation =
                            FaFactory.eINSTANCE
                                    .createComponentExchangeFunctionalExchangeAllocation();
                    allocation.setSourceElement(carrier);
                    allocation.setTargetElement(model.exchange());
                    carrier.getOwnedComponentExchangeFunctionalExchangeAllocations()
                            .add(allocation);
                });
        assertTrue(
                findings(
                                tools.execute("model_validate", Map.of("layer", "LA")),
                                "unallocated_functional_exchange")
                        .isEmpty());
    }

    @Test
    public void sameComponentExchangeNeedsNoCarrierAndDanglingExchangesAreErrors()
            throws Exception {
        Logical model = logicalFixture();
        write(
                () -> {
                    allocate(model.c1(), model.a());
                    allocate(model.c1(), model.b());
                });
        assertTrue(
                findings(
                                tools.execute("model_validate", Map.of("layer", "LA")),
                                "unallocated_functional_exchange")
                        .isEmpty());
        write(() -> model.exchange().setTarget(null));
        String json = tools.execute("model_validate", Map.of("ids", List.of("la-fe")));
        List<Map<?, ?>> dangling = findings(json, "exchange_missing_end");
        assertEquals(1, dangling.size());
        assertEquals("error", dangling.get(0).get("severity"));
    }

    @Test
    public void idScopedValidationSkipsRulesThatNeedTheWholeLayer() throws Exception {
        logicalFixture();
        String json = tools.execute("model_validate", Map.of("ids", List.of("la-b")));
        assertTrue(findings(json, "unallocated_leaf_function").isEmpty());
        assertEquals(1, findings(json, "unrealized_function").size());
        assertEquals("local_only", result(json).get("methodology_scope"));
    }

    @Test
    public void
            requirementSearchSkipsLargeMetadataBranchesButFindsNestedFolderRequirementsWithinReadBudget()
                    throws Exception {
        var fixture = requirementFixture();
        write(
                () -> {
                    resource.getContents().add(fixture.module());
                    for (int index = 0; index < 2800; index++) {
                        EObject requirement =
                                fixture.requirement("req-" + index, "Other requirement " + index);
                        fixture.children(fixture.module()).add(requirement);
                        for (int attribute = 0; attribute < 7; attribute++) {
                            EObject metadata = new DynamicEObjectImpl(fixture.attribute());
                            EObject value = new DynamicEObjectImpl(fixture.value());
                            value.eSet(
                                    fixture.value().getEStructuralFeature("name"),
                                    "Metadata target " + index + ":" + attribute);
                            fixture.list(metadata, "ownedValues").add(value);
                            fixture.list(requirement, "ownedAttributes").add(metadata);
                        }
                    }
                    EObject folder = new DynamicEObjectImpl(fixture.folder());
                    EObject nestedFolder = new DynamicEObjectImpl(fixture.folder());
                    fixture.children(fixture.module()).add(folder);
                    fixture.children(folder).add(nestedFolder);
                    fixture.children(nestedFolder)
                            .add(fixture.requirement("req-last", "Last nested requirement"));
                });
        Object undo = domain.getCommandStack().getUndoCommand();
        resource.setModified(false);
        int adapters = domain.getResourceSet().eAdapters().size();
        String cursor = null;
        boolean found = false;
        int pages = 0;
        String result = "";
        do {
            var arguments = new java.util.LinkedHashMap<String, Object>();
            arguments.put("type", "Requirement");
            arguments.put("query", "Last nested requirement");
            arguments.put("limit", 5);
            if (cursor != null) arguments.put("cursor", cursor);
            result = tools.execute("model_search", arguments);
            pages++;
            found |= result.contains("req-last");
            cursor = cursor(result);
        } while (!found && cursor != null && pages < 8);
        assertTrue("Metadata must not exhaust the agent's eight-read budget", found);
        assertTrue("Search should leave two read calls for inspect and relationships", pages <= 6);
        var scanned = Pattern.compile("\"scanned_total\":([0-9]+)").matcher(result);
        assertTrue(scanned.find());
        assertTrue(
                "39,200 metadata descendants should be pruned from search traversal",
                Integer.parseInt(scanned.group(1)) < 3000);
        assertFalse(resource.isModified());
        assertSame(undo, domain.getCommandStack().getUndoCommand());
        assertEquals(adapters, domain.getResourceSet().eAdapters().size());
        assertEquals(0, saves.get());
        // The shortcut is specific to Requirement search. Metadata remains
        // searchable when it is the requested native type.
        assertTrue(
                tools.execute(
                                "model_search",
                                Map.of("type", "AttributeValue", "query", "Metadata target 0:0"))
                        .contains("Metadata target 0:0"));
    }

    @Test
    public void genericExtensionSlotsPreserveNestedRequirementsAndUnknownContainmentTypes()
            throws Exception {
        var fixture = requirementFixture();
        var unknownModel = EcoreFactory.eINSTANCE.createEPackage();
        unknownModel.setName("extension");
        unknownModel.setNsURI("memory:/unknown-extension");
        unknownModel.setNsPrefix("extension");
        var unknown = EcoreFactory.eINSTANCE.createEClass();
        unknown.setName("UnknownContainer");
        unknownModel.getEClassifiers().add(unknown);
        containment(unknown, "ownedRequirements", fixture.requirement());
        EObject container = new DynamicEObjectImpl(unknown);
        EObject owner = fixture.requirement("owner", "Generic extension owner");
        EObject target = fixture.requirement("extension-child", "Requirement in generic extension");
        write(
                () -> {
                    resource.getContents().add(fixture.module());
                    fixture.children(fixture.module()).add(owner);
                    fixture.list(owner, "ownedExtensions").add(container);
                    fixture.children(container).add(target);
                });
        String result =
                tools.execute(
                        "model_search",
                        Map.of("type", "Requirement", "query", "Requirement in generic extension"));
        assertTrue(result, result.contains("extension-child"));
        assertTrue(result.contains("\"complete\":true"));
    }

    @Test
    public void requirementMetadataProofDoesNotResolveProxyExtensionValues() throws Exception {
        var fixture = requirementFixture();
        EObject owner = fixture.requirement("proxy-owner", "Proxy extension owner");
        EObject proxy = fixture.requirement("proxy", "Unresolved requirement");
        ((InternalEObject) proxy)
                .eSetProxyURI(URI.createURI("memory:/never-load-requirement.xmi#missing"));
        write(
                () -> {
                    resource.getContents().add(fixture.module());
                    fixture.children(fixture.module()).add(owner);
                    fixture.list(owner, "ownedExtensions").add(proxy);
                    fixture.children(fixture.module())
                            .add(fixture.requirement("after-proxy", "After proxy target"));
                });
        int resources = domain.getResourceSet().getResources().size();
        String result =
                tools.execute(
                        "model_search",
                        Map.of("type", "Requirement", "query", "After proxy target"));
        assertTrue(result, result.contains("after-proxy"));
        assertTrue(proxy.eIsProxy());
        assertEquals(resources, domain.getResourceSet().getResources().size());
    }

    /** Mirrors the optional ReqIF metamodel without importing its absent Tycho target bundle. */
    private RequirementFixture requirementFixture() {
        var model = EcoreFactory.eINSTANCE.createEPackage();
        model.setName("Requirements");
        model.setNsPrefix("req");
        model.setNsURI("http://www.polarsys.org/kitalpha/requirements");
        EClass requirement = EcoreFactory.eINSTANCE.createEClass();
        requirement.setName("Requirement");
        EClass folder = EcoreFactory.eINSTANCE.createEClass();
        folder.setName("Folder");
        folder.getESuperTypes().add(requirement);
        EClass module = EcoreFactory.eINSTANCE.createEClass();
        module.setName("Module");
        EClass attribute = EcoreFactory.eINSTANCE.createEClass();
        attribute.setName("Attribute");
        EClass value = EcoreFactory.eINSTANCE.createEClass();
        value.setName("AttributeValue");
        model.getEClassifiers().addAll(List.of(requirement, folder, module, attribute, value));
        for (String name : List.of("id", "name", "ReqIFIdentifier")) {
            var feature = EcoreFactory.eINSTANCE.createEAttribute();
            feature.setName(name);
            feature.setEType(EcorePackage.Literals.ESTRING);
            feature.setID("id".equals(name));
            requirement.getEStructuralFeatures().add(feature);
        }
        var valueName = EcoreFactory.eINSTANCE.createEAttribute();
        valueName.setName("name");
        valueName.setEType(EcorePackage.Literals.ESTRING);
        value.getEStructuralFeatures().add(valueName);
        containment(module, "ownedRequirements", requirement);
        containment(folder, "ownedRequirements", requirement);
        containment(requirement, "ownedAttributes", attribute);
        containment(attribute, "ownedValues", value);
        // Native EMDE ExtensibleElement contributes a generic extension slot.
        // Empty is safe; nonempty actual children must be proved safe.
        containment(requirement, "ownedExtensions", EcorePackage.Literals.EOBJECT);
        return new RequirementFixture(
                requirement, folder, attribute, value, new DynamicEObjectImpl(module));
    }

    private static void containment(EClass owner, String name, EClass type) {
        EReference feature = EcoreFactory.eINSTANCE.createEReference();
        feature.setName(name);
        feature.setEType(type);
        feature.setContainment(true);
        feature.setUpperBound(-1);
        owner.getEStructuralFeatures().add(feature);
    }

    private record RequirementFixture(
            EClass requirement, EClass folder, EClass attribute, EClass value, EObject module) {
        EObject requirement(String id, String name) {
            EObject object = new DynamicEObjectImpl(requirement);
            object.eSet(requirement.getEStructuralFeature("id"), id);
            object.eSet(requirement.getEStructuralFeature("name"), name);
            object.eSet(requirement.getEStructuralFeature("ReqIFIdentifier"), "SOURCE:" + id);
            return object;
        }

        @SuppressWarnings("unchecked")
        List<EObject> list(EObject owner, String name) {
            return (List<EObject>) owner.eGet(owner.eClass().getEStructuralFeature(name), false);
        }

        List<EObject> children(EObject owner) {
            return list(owner, "ownedRequirements");
        }
    }

    private Session session(List<Resource> semantic) {
        return (Session)
                Proxy.newProxyInstance(
                        Session.class.getClassLoader(),
                        new Class<?>[] {Session.class},
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "isOpen" -> open;
                                    case "getSemanticResources" -> semantic;
                                    case "getTransactionalEditingDomain" -> domain;
                                    case "getSessionResource" -> resource;
                                    case "save" -> {
                                        saves.incrementAndGet();
                                        throw new AssertionError("Read tool saved the model");
                                    }
                                    case "equals" -> proxy == args[0];
                                    case "hashCode" -> System.identityHashCode(proxy);
                                    default -> null;
                                });
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
        var output = new ByteArrayOutputStream();
        resource.save(output, Map.of());
        return output.toByteArray();
    }

    private static String cursor(String json) {
        var matcher = Pattern.compile("\"cursor\":\"([^\"]+)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
