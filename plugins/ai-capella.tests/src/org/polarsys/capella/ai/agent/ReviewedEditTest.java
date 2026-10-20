package org.polarsys.capella.ai.agent;

import static org.junit.Assert.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.polarsys.capella.ai.session.SelectionContext;
import org.polarsys.capella.ai.session.SessionSafeguards;
import org.polarsys.capella.core.data.capellamodeller.CapellamodellerFactory;
import org.polarsys.capella.core.data.la.LaFactory;
import org.polarsys.capella.core.data.la.LogicalFunction;
import org.polarsys.capella.core.model.handler.helpers.CapellaProjectHelper;
import org.polarsys.capella.core.model.handler.helpers.CapellaProjectHelper.ProjectApproach;

/** One reviewed edit runs the exact source once, then reports only independently verified completion. */
public class ReviewedEditTest {
    private Resource resource;
    private TransactionalEditingDomain domain;
    private Session session;
    private LogicalFunction root;
    private final AtomicInteger saves = new AtomicInteger();
    private final List<String> events = new ArrayList<>();
    private boolean actions, confirm;

    @Before public void setUp() {
        Display.getDefault();
        actions = LLMPreferences.isToolActionsEnabled();
        confirm = AdvancedPreferences.isConfirmBeforeApplyEnabled();
        LLMPreferences.setToolActionsEnabled(true);
        AdvancedPreferences.setConfirmBeforeApplyEnabled(true);
        var resources = new ResourceSetImpl();
        resources.getResourceFactoryRegistry().getExtensionToFactoryMap().put("xmi", new XMIResourceFactoryImpl());
        resource = resources.createResource(URI.createURI("memory:/reviewed-edit.xmi"));
        var project = CapellamodellerFactory.eINSTANCE.createProject();
        CapellaProjectHelper.setProjectWithApproach(project, ProjectApproach.SingletonComponents);
        var engineering = CapellamodellerFactory.eINSTANCE.createSystemEngineering();
        project.getOwnedModelRoots().add(engineering);
        resource.getContents().add(project);
        var la = LaFactory.eINSTANCE.createLogicalArchitecture();
        la.setName("LA");
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
                case "save" -> { saves.incrementAndGet(); yield null; }
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

    @Test public void approvedEditCommitsSavesAndCompletesStepsWhoseChecksPass() throws Exception {
        var outcome = run(() -> ReviewedEdit.apply(session, request("Probe", "Probe", () -> true), listener()));
        assertTrue(outcome.attempted());
        assertTrue(outcome.committed());
        assertTrue(outcome.changed());
        assertEquals(Set.of("create"), outcome.execution().completedStepIds());
        assertTrue(outcome.execution().saved());
        assertEquals(1, saves.get());
        assertTrue(root.getOwnedFunctions().stream().anyMatch(function -> "Probe".equals(function.getName())));
        assertEquals(List.of("executed", "checks", "validation"), events.stream().filter(event -> !event.startsWith("activity")).toList());
    }

    @Test public void failedCheckLeavesTheStepPendingEvenThoughTheChangeCommitted() throws Exception {
        var outcome = run(() -> ReviewedEdit.apply(session, request("Probe", "Something else", () -> true), listener()));
        assertTrue(outcome.committed());
        assertTrue(outcome.execution().completedStepIds().isEmpty());
        assertTrue(outcome.execution().feedback(), outcome.execution().feedback().toLowerCase().contains("fail"));
    }

    @Test public void rejectedReviewRunsNothing() throws Exception {
        var outcome = run(() -> ReviewedEdit.apply(session, request("Probe", "Probe", () -> false), listener()));
        assertFalse(outcome.attempted());
        assertFalse(outcome.committed());
        assertTrue(root.getOwnedFunctions().isEmpty());
        assertEquals(0, saves.get());
        assertFalse(domain.getCommandStack().canUndo());
    }

    private ReviewedEdit.Request request(String created, String expected, java.util.function.BooleanSupplier review) {
        var check = new ArcadiaAgent.Postcondition("create", "element_exists",
            Map.of("type", "LogicalFunction", "name", expected, "layer", "LA"));
        return new ReviewedEdit.Request("Create a probe function", "Create " + created,
            "api.create('LA', 'LogicalFunction', '" + created + "')", List.of(check), "reviewed-edit-test",
            SelectionContext.empty(), review, () -> false, () -> true,
            ignored -> new ModelVerification.Snapshot(true, 0, 0, 0, List.of(), true, false, ""));
    }

    private ReviewedEdit.Listener listener() {
        return new ReviewedEdit.Listener() {
            @Override public void activity(String text) { events.add("activity " + text); }
            @Override public void executed(org.polarsys.capella.ai.scripting.ScriptRouter.Result result, String feedback,
                    ModelChangeSummary.ChangeReport changes) { events.add("executed"); }
            @Override public void checks(String markdown) { events.add("checks"); }
            @Override public void validation(String markdown) { events.add("validation"); }
        };
    }

    /** Model execution must not run on SWT; pump SWT while the edit runs on a worker. */
    private static <T> T run(Supplier<T> work) throws Exception {
        var task = new FutureTask<>(work::get);
        Thread worker = new Thread(task, "ReviewedEditTest");
        worker.setDaemon(true);
        worker.start();
        Display display = Display.getDefault();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (!task.isDone() && System.nanoTime() < deadline) {
            if (!display.readAndDispatch()) Thread.sleep(2);
        }
        return task.get(1, TimeUnit.SECONDS);
    }
}
