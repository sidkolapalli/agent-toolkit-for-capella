package com.sidkolapalli.capella.ai.dev;

import static org.junit.Assert.*;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.swt.widgets.Display;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import com.sidkolapalli.capella.ai.agent.AgentJson;
import com.sidkolapalli.capella.ai.preferences.AdvancedPreferences;
import com.sidkolapalli.capella.ai.preferences.LLMPreferences;
import com.sidkolapalli.capella.ai.session.SessionSafeguards;
import org.polarsys.capella.core.data.capellamodeller.CapellamodellerFactory;
import org.polarsys.capella.core.data.la.LaFactory;
import org.polarsys.capella.core.data.la.LogicalFunction;
import org.polarsys.capella.core.model.handler.helpers.CapellaProjectHelper;
import org.polarsys.capella.core.model.handler.helpers.CapellaProjectHelper.ProjectApproach;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP clients may propose edits; only a person in Capella can approve them, and a retry never runs
 * one twice.
 */
public class McpEditsTest {
    private Resource resource;
    private TransactionalEditingDomain domain;
    private Session session;
    private LogicalFunction root;
    private final AtomicInteger reviews = new AtomicInteger();
    private boolean actions, confirm;
    private Object previousReviewer, previousValidator;

    @Before
    public void setUp() throws Exception {
        Display.getDefault();
        actions = LLMPreferences.isToolActionsEnabled();
        confirm = AdvancedPreferences.isConfirmBeforeApplyEnabled();
        LLMPreferences.setToolActionsEnabled(true);
        AdvancedPreferences.setConfirmBeforeApplyEnabled(true);
        var resources = new ResourceSetImpl();
        resources
                .getResourceFactoryRegistry()
                .getExtensionToFactoryMap()
                .put("xmi", new XMIResourceFactoryImpl());
        resource = resources.createResource(URI.createURI("memory:/mcp-edits.xmi"));
        var project = CapellamodellerFactory.eINSTANCE.createProject();
        CapellaProjectHelper.setProjectWithApproach(project, ProjectApproach.SingletonComponents);
        var engineering = CapellamodellerFactory.eINSTANCE.createSystemEngineering();
        project.getOwnedModelRoots().add(engineering);
        resource.getContents().add(project);
        var la = LaFactory.eINSTANCE.createLogicalArchitecture();
        var functions = LaFactory.eINSTANCE.createLogicalFunctionPkg();
        la.setOwnedFunctionPkg(functions);
        root = LaFactory.eINSTANCE.createLogicalFunction();
        root.setName("LA root function");
        functions.getOwnedLogicalFunctions().add(root);
        la.setOwnedLogicalComponentPkg(LaFactory.eINSTANCE.createLogicalComponentPkg());
        engineering.getOwnedArchitectures().add(la);
        domain = TransactionalEditingDomain.Factory.INSTANCE.createEditingDomain(resources);
        session =
                (Session)
                        Proxy.newProxyInstance(
                                Session.class.getClassLoader(),
                                new Class<?>[] {Session.class},
                                (proxy, method, args) ->
                                        switch (method.getName()) {
                                            case "getTransactionalEditingDomain" -> domain;
                                            case "getSemanticResources" -> List.of(resource);
                                            case "getSessionResource" -> resource;
                                            case "isOpen" -> true;
                                            case "save" -> null;
                                            case "hashCode" -> System.identityHashCode(proxy);
                                            case "equals" -> proxy == args[0];
                                            default -> null;
                                        });
        previousReviewer = reviewerField().get(null);
        previousValidator = field("validator").get(null);
        // The native validator needs the Capella platform; a clean snapshot stands in for it here.
        field("validator")
                .set(
                        null,
                        (java.util.function.Function<
                                        Session,
                                        com.sidkolapalli.capella.ai.agent.ModelVerification.Snapshot>)
                                ignored ->
                                        new com.sidkolapalli.capella.ai.agent.ModelVerification
                                                .Snapshot(
                                                true, 0, 0, 0, List.of(), true, false, ""));
        McpEdits.reset();
    }

    @After
    public void tearDown() throws Exception {
        reviewerField().set(null, previousReviewer);
        field("validator").set(null, previousValidator);
        McpEdits.reset();
        LLMPreferences.setToolActionsEnabled(actions);
        AdvancedPreferences.setConfirmBeforeApplyEnabled(confirm);
        if (domain != null) domain.dispose();
        SessionSafeguards.cleanup();
    }

    @Test
    public void approvedProposalRunsOnceAndReportsTheVerifiedReceipt() throws Exception {
        review(true);
        Map<?, ?> proposed = toolJson(call("propose_model_edit", proposal("McpProbe")));
        assertEquals("Queued until its review dialog opens", "queued", proposed.get("status"));
        Map<?, ?> status = await((String) proposed.get("proposal_id"));
        assertEquals("committed", status.get("status"));
        Map<?, ?> receipt = (Map<?, ?>) status.get("receipt");
        assertEquals(Boolean.TRUE, receipt.get("saved"));
        assertEquals(List.of("edit"), receipt.get("completedStepIds"));
        assertEquals(1, probes("McpProbe"));
        assertEquals(1, reviews.get());
    }

    @Test
    public void retryingTheSameProposalNeverRunsItTwice() throws Exception {
        review(true);
        String first =
                (String)
                        toolJson(call("propose_model_edit", proposal("McpOnce")))
                                .get("proposal_id");
        await(first);
        Map<?, ?> again = toolJson(call("propose_model_edit", proposal("McpOnce")));
        assertEquals(first, again.get("proposal_id"));
        assertEquals("committed", again.get("status"));
        assertEquals(1, probes("McpOnce"));
        assertEquals("One person-review for one proposal", 1, reviews.get());
    }

    @Test
    public void rejectedOrUnreviewableProposalsRunNothing() throws Exception {
        review(false);
        AdvancedPreferences.setConfirmBeforeApplyEnabled(false);
        Map<?, ?> status =
                await(
                        (String)
                                toolJson(call("propose_model_edit", proposal("McpRejected")))
                                        .get("proposal_id"));
        assertEquals("rejected", status.get("status"));
        assertEquals(
                "Review happens even when the chat's review preference is off", 1, reviews.get());
        assertEquals(0, probes("McpRejected"));
        assertFalse(domain.getCommandStack().canUndo());
    }

    @Test
    public void scriptsThatDoNotCompileAndDisabledActionsAreRefusedUpFront() throws Exception {
        review(true);
        Map<?, ?> broken =
                call("propose_model_edit", Map.of("summary", "Broken", "script", "def x = ("));
        assertEquals(Boolean.TRUE, broken.get("isError"));
        LLMPreferences.setToolActionsEnabled(false);
        Map<?, ?> disabled = call("propose_model_edit", proposal("McpDisabled"));
        assertEquals(Boolean.TRUE, disabled.get("isError"));
        assertEquals(0, reviews.get());
        Map<?, ?> unknown = call("edit_status", Map.of("proposal_id", "no-such-proposal"));
        assertEquals(Boolean.TRUE, unknown.get("isError"));
    }

    @Test
    public void aProposalWaitingBehindAnotherReviewSaysQueuedNotAwaitingReview() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        reviewerField()
                .set(
                        null,
                        (McpEdits.Reviewer)
                                (reviewed, summary, code, checks) -> {
                                    reviews.incrementAndGet();
                                    try {
                                        release.await(30, TimeUnit.SECONDS);
                                    } catch (InterruptedException stopped) {
                                        Thread.currentThread().interrupt();
                                    }
                                    return false;
                                });
        String first =
                (String)
                        toolJson(call("propose_model_edit", proposal("McpFirst")))
                                .get("proposal_id");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!"awaiting_review"
                .equals(
                        toolJson(call("edit_status", Map.of("proposal_id", first)))
                                .get("status"))) {
            assertTrue("The first review never opened", System.nanoTime() < deadline);
            Thread.sleep(5);
        }
        String second =
                (String)
                        toolJson(call("propose_model_edit", proposal("McpSecond")))
                                .get("proposal_id");
        assertEquals(
                "queued",
                toolJson(call("edit_status", Map.of("proposal_id", second))).get("status"));
        release.countDown();
        assertEquals("rejected", await(second).get("status"));
    }

    @Test
    public void toolsListOffersTheReviewedWriteTools() {
        List<?> tools =
                (List<?>)
                        ((Map<?, ?>)
                                        response(
                                                        Map.of(
                                                                "jsonrpc",
                                                                "2.0",
                                                                "id",
                                                                1,
                                                                "method",
                                                                "tools/list"))
                                                .get("result"))
                                .get("tools");
        var names = tools.stream().map(tool -> ((Map<?, ?>) tool).get("name")).toList();
        assertTrue(names.contains("propose_model_edit"));
        assertTrue(names.contains("edit_status"));
    }

    private static Map<String, Object> proposal(String name) {
        return Map.of(
                "summary",
                "Create " + name,
                "script",
                "api.create('LA', 'LogicalFunction', '" + name + "')",
                "postconditions",
                List.of(
                        Map.of(
                                "kind",
                                "element_exists",
                                "type",
                                "LogicalFunction",
                                "name",
                                name,
                                "layer",
                                "LA")));
    }

    private long probes(String name) {
        return root.getOwnedFunctions().stream()
                .filter(function -> name.equals(function.getName()))
                .count();
    }

    private void review(boolean approve) throws Exception {
        reviewerField()
                .set(
                        null,
                        (McpEdits.Reviewer)
                                (reviewed, summary, code, checks) -> {
                                    reviews.incrementAndGet();
                                    return approve;
                                });
    }

    private static Field reviewerField() throws Exception {
        return field("reviewer");
    }

    private static Field field(String name) throws Exception {
        Field field = McpEdits.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /** Poll edit_status while pumping SWT, as model execution needs the UI thread. */
    private Map<?, ?> await(String id) throws Exception {
        Display display = Display.getDefault();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (System.nanoTime() < deadline) {
            Map<?, ?> status = toolJson(call("edit_status", Map.of("proposal_id", id)));
            if (!List.of("queued", "awaiting_review", "running").contains(status.get("status")))
                return status;
            if (!display.readAndDispatch()) Thread.sleep(2);
        }
        throw new AssertionError("Proposal did not finish");
    }

    private Map<?, ?> call(String tool, Map<String, Object> arguments) {
        return (Map<?, ?>)
                response(
                                Map.of(
                                        "jsonrpc",
                                        "2.0",
                                        "id",
                                        "c",
                                        "method",
                                        "tools/call",
                                        "params",
                                        Map.of("name", tool, "arguments", arguments)))
                        .get("result");
    }

    private Map<?, ?> response(Map<String, Object> message) {
        return (Map<?, ?>)
                AgentJson.parse(McpServer.handle(session, AgentJson.write(message)), 400_000);
    }

    private static Map<?, ?> toolJson(Map<?, ?> result) {
        assertEquals(result.toString(), Boolean.FALSE, result.get("isError"));
        String text = (String) ((Map<?, ?>) ((List<?>) result.get("content")).get(0)).get("text");
        return (Map<?, ?>) AgentJson.parse(text, 400_000);
    }
}
