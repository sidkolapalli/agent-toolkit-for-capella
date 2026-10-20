package org.polarsys.capella.ai.agent;

import static org.junit.Assert.*;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
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
import org.polarsys.capella.ai.preferences.AdvancedPreferences;
import org.polarsys.capella.ai.preferences.LLMPreferences;
import org.polarsys.capella.ai.session.SessionSafeguards;
import org.polarsys.capella.core.data.capellamodeller.CapellamodellerFactory;
import org.polarsys.capella.core.data.la.LaFactory;
import org.polarsys.capella.core.data.la.LogicalFunction;
import org.polarsys.capella.core.model.handler.helpers.CapellaProjectHelper;
import org.polarsys.capella.core.model.handler.helpers.CapellaProjectHelper.ProjectApproach;

/** The eval harness drives the real agent protocol headlessly and scores tasks by native checks. */
public class EvalRunnerTest {
    private Resource resource;
    private TransactionalEditingDomain domain;
    private Session session;
    private LogicalFunction root;
    private boolean actions, confirm;

    @Before public void setUp() {
        Display.getDefault();
        actions = LLMPreferences.isToolActionsEnabled();
        confirm = AdvancedPreferences.isConfirmBeforeApplyEnabled();
        LLMPreferences.setToolActionsEnabled(true);
        AdvancedPreferences.setConfirmBeforeApplyEnabled(true);
        var resources = new ResourceSetImpl();
        resources.getResourceFactoryRegistry().getExtensionToFactoryMap().put("xmi", new XMIResourceFactoryImpl());
        resource = resources.createResource(URI.createURI("memory:/eval.xmi"));
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
        session = (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[] {Session.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getTransactionalEditingDomain" -> domain;
                case "getSemanticResources" -> List.of(resource);
                case "getSessionResource" -> resource;
                case "isOpen" -> true;
                case "save" -> null;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> null;
            });
    }

    @After public void tearDown() {
        LLMPreferences.setToolActionsEnabled(actions);
        AdvancedPreferences.setConfirmBeforeApplyEnabled(confirm);
        if (domain != null) domain.dispose();
        SessionSafeguards.cleanup();
    }

    @Test public void tasksParseFromTheEvalFileFormat() {
        List<EvalRunner.Task> tasks = EvalRunner.parse("{\"tasks\":[{\"id\":\"la-probe\",\"prompt\":\"Create Probe\","
            + "\"checks\":[{\"kind\":\"element_exists\",\"type\":\"LogicalFunction\",\"name\":\"Probe\",\"layer\":\"LA\"}]}]}");
        assertEquals(1, tasks.size());
        assertEquals("la-probe", tasks.get(0).id());
        assertEquals("element_exists", tasks.get(0).checks().get(0).kind());
        assertThrows(IllegalArgumentException.class, () -> EvalRunner.parse("{\"tasks\":[{\"id\":\"no-checks\",\"prompt\":\"x\"}]}"));
    }

    @Test public void shippedStarterTasksAreValidDeclarations() throws Exception {
        java.nio.file.Path file = null;
        for (var dir = java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath(); dir != null && file == null; dir = dir.getParent()) {
            var candidate = dir.resolve("test-models/evals/arcadia-starter.json");
            if (java.nio.file.Files.isRegularFile(candidate)) file = candidate;
        }
        assertNotNull("test-models/evals/arcadia-starter.json must be reachable from the build directory", file);
        List<EvalRunner.Task> tasks = EvalRunner.parse(java.nio.file.Files.readString(file));
        assertTrue(tasks.size() >= 12);
        assertEquals("Task ids are unique", tasks.size(), tasks.stream().map(EvalRunner.Task::id).distinct().count());
    }

    @Test public void solvedTaskPassesItsNativeChecksAndRecordsTheEffort() throws Exception {
        var result = run(task("Probe"), plan(), proposal("Probe"), finalReply());
        assertTrue(result.toString(), result.passed());
        assertEquals(1, result.proposals());
        assertEquals(1, result.committed());
        assertEquals(0, result.repairs());
        assertTrue(result.requests() >= 3);
    }

    @Test public void wrongModelChangeFailsEvenWhenTheAgentClaimsSuccess() throws Exception {
        var result = run(task("Probe"), plan(), proposal("Other"), finalReply());
        assertFalse(result.passed());
        assertEquals(1, result.committed());
        assertTrue(result.checks().contains("Failed"));
    }

    @Test public void aBlockedTaskReportsWhyItsProposalDidNotApply() throws Exception {
        String failing = AgentJson.write(Map.of("kind", "proposal", "message", "Fails at runtime",
            "script", "assert false : 'deliberate runtime failure'", "stepIds", List.of("edit")));
        var result = run(task("Probe"), plan(), failing, finalReply());
        assertFalse(result.passed());
        assertTrue(result.ending(), result.ending().startsWith("blocked"));
        assertTrue("The failure reason travels with the result: " + result.detail(), result.detail().contains("deliberate runtime failure"));
        assertEquals(result.detail(), result.json().get("detail"));
    }

    @Test public void compileFailureIsRepairedBeforeReviewAndCounted() throws Exception {
        String broken = AgentJson.write(Map.of("kind", "proposal", "message", "Broken", "script", "def x = (", "stepIds", List.of("edit")));
        var result = run(task("Probe"), plan(), broken, proposal("Probe"), finalReply());
        assertTrue(result.toString(), result.passed());
        assertEquals(1, result.repairs());
        assertEquals(1, root.getOwnedFunctions().size());
    }

    private EvalRunner.Result run(EvalRunner.Task task, String... replies) throws Exception {
        Deque<String> queue = new ArrayDeque<>(List.of(replies));
        Supplier<AgentRuntime> runtimes = () -> new ArcadiaAgent(messages -> queue.isEmpty() ? finalReply() : queue.removeFirst(),
            (tool, arguments) -> "{}", Set(), ArcadiaAgent.Limits.defaults());
        var work = new FutureTask<>(() -> EvalRunner.run(session, task, runtimes, "You edit Capella models.",
            ignored -> new ModelVerification.Snapshot(true, 0, 0, 0, List.of(), true, false, "")));
        Thread worker = new Thread(work, "EvalRunnerTest");
        worker.setDaemon(true);
        worker.start();
        Display display = Display.getDefault();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!work.isDone() && System.nanoTime() < deadline) {
            if (!display.readAndDispatch()) Thread.sleep(2);
        }
        return work.get(1, TimeUnit.SECONDS);
    }

    private static java.util.Set<String> Set() { return java.util.Set.of("model_summary"); }

    private static EvalRunner.Task task(String expected) {
        return new EvalRunner.Task("la-probe", "Create the logical function " + expected,
            List.of(new ArcadiaAgent.Postcondition("eval", "element_exists", Map.of("type", "LogicalFunction", "name", expected, "layer", "LA"))));
    }

    private static String plan() {
        return AgentJson.write(Map.of("kind", "plan", "message", "Create it", "steps",
            List.of(Map.of("id", "edit", "title", "Create the function", "type", "edit", "status", "pending"))));
    }

    private static String proposal(String name) {
        return AgentJson.write(Map.of("kind", "proposal", "message", "Create " + name,
            "script", "api.create('LA', 'LogicalFunction', '" + name + "')", "stepIds", List.of("edit"),
            "postconditions", List.of(Map.of("stepId", "edit", "kind", "element_exists", "type", "LogicalFunction", "name", name, "layer", "LA"))));
    }

    private static String finalReply() {
        return AgentJson.write(Map.of("kind", "final", "message", "Done.", "task", Map.of("state", "complete")));
    }
}
