package org.polarsys.capella.ai;

import static org.junit.Assert.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.ISelectionService;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.polarsys.capella.ai.agent.TaskTermination;
import org.polarsys.capella.ai.codex.CodexPreferences;
import org.polarsys.capella.ai.presentation.ConversationState;
import org.polarsys.capella.ai.session.SelectionContext;

/** Native activity rendering and request lifecycle; queued workers are captured, never run. */
public class ChatTaskTerminationTest {
    private ChatView view;
    private Shell shell;
    private String priorRuntime;
    private Session first;

    @Before public void dock() throws Exception {
        priorRuntime = CodexPreferences.getRuntime(); CodexPreferences.setRuntime("direct");
        shell = new Shell(Display.getDefault()); shell.setLayout(new FillLayout()); shell.setSize(480, 650);
        var site = stub(IViewSite.class, Map.of("getActionBars", stub(IActionBars.class, Map.of("getToolBarManager", new ToolBarManager())),
            "getPage", stub(IWorkbenchPage.class, Map.of()), "getShell", shell,
            "getWorkbenchWindow", stub(IWorkbenchWindow.class, Map.of("getSelectionService", stub(ISelectionService.class, Map.of())))));
        view = new ChatView(); view.init(site); view.createPartControl(shell); shell.layout(true, true); set("conversationStore", null);
        first = session("memory:/terminal-first.aird"); call("switchConversation", new Class<?>[] { Session.class }, first);
        set("activeSession", first); set("providerConfigured", true);
    }
    @After public void close() { if (view != null) view.dispose(); if (shell != null) shell.dispose(); if (priorRuntime != null) CodexPreferences.setRuntime(priorRuntime); }

    @Test public void idleActivityRetainsClarificationReasonRatherThanReturningToGenericReady() throws Exception {
        remember(new TaskTermination(TaskTermination.State.NEEDS_INPUT, "Choose which system owns the interface.", TaskTermination.Scope.DECLARED_PLAN));
        set("busy", false); call("setReadyActivity", new Class<?>[] {});
        String text = ((Label) field("activityLabel")).getText();
        assertTrue(text, text.startsWith("Your input is needed")); assertTrue(text.contains("Choose which system"));
        call("setReadyActivity", new Class<?>[] {}); assertEquals(text, ((Label) field("activityLabel")).getText());
    }

    @Test public void switchingModelExpiresStatusAndStaleConversationCannotReapplyIt() throws Exception {
        var old = (ConversationState) field("conversation"); remember(TaskTermination.blocked("Old model is missing a boundary.", List.of()));
        Session second = session("memory:/terminal-second.aird"); call("switchConversation", new Class<?>[] { Session.class }, second);
        assertNull(field("taskTermination"));
        call("rememberTaskTermination", new Class<?>[] { Session.class, ConversationState.class, TaskTermination.class }, first, old,
            TaskTermination.blocked("Stale model failure", List.of()));
        assertNull(field("taskTermination"));
    }

    @Test public void stopExpiresPriorTerminationAndReleasesReviewWithoutApplyingAnything() throws Exception {
        remember(TaskTermination.budget("Previous budget exhausted.", List.of()));
        var review = new CompletableFuture<Boolean>(); set("pendingReview", review); set("busy", true);
        call("stopRequest", new Class<?>[] {});
        assertNull(field("taskTermination")); assertTrue(review.isDone()); assertFalse(review.get());
        assertTrue(((Label) field("activityLabel")).getText().startsWith("Stopping"));
        assertTrue((boolean) field("stopRequested"));
    }

    @Test public void newRequestExpiresOldClarificationAndQueuesOneFreshTaskWithoutExecutingTheOldTask() throws Exception {
        remember(new TaskTermination(TaskTermination.State.NEEDS_INPUT, "Old question", TaskTermination.Scope.DECLARED_PLAN));
        ((ExecutorService) field("requests")).shutdownNow(); var queue = new CapturingExecutor(); set("requests", queue);
        ((Text) field("inputField")).setText("Explain the architecture");
        call("startRequest", new Class<?>[] { Session.class, String.class, SelectionContext.class, boolean.class },
            first, "Explain the architecture", SelectionContext.empty(), true);
        assertNull(field("taskTermination")); assertEquals(1, queue.queued.size()); assertTrue((boolean) field("busy"));
        assertTrue(((Label) field("activityLabel")).getText().startsWith("Thinking"));
        assertEquals("", ((Text) field("inputField")).getText());
        var state = (ConversationState) field("conversation"); assertTrue(state.history().stream().anyMatch(message -> message.getContent().equals("Explain the architecture")));
    }

    private void remember(TaskTermination value) throws Exception { call("rememberTaskTermination", new Class<?>[] { Session.class, ConversationState.class, TaskTermination.class }, first, field("conversation"), value); }
    private Object field(String name) throws Exception { var field = ChatView.class.getDeclaredField(name); field.setAccessible(true); return field.get(view); }
    private void set(String name, Object value) throws Exception { var field = ChatView.class.getDeclaredField(name); field.setAccessible(true); field.set(view, value); }
    private Object call(String name, Class<?>[] types, Object... args) throws Exception { var method = ChatView.class.getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(view, args); }
    private static Session session(String uri) { return stub(Session.class, Map.of("getSessionResource", new ResourceImpl(URI.createURI(uri)), "isOpen", true)); }
    @SuppressWarnings("unchecked") private static <T> T stub(Class<T> type, Map<String, Object> methods) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            return methods.get(method.getName());
        });
    }
    private static final class CapturingExecutor extends AbstractExecutorService {
        final List<Runnable> queued = new ArrayList<>(); private boolean stopped;
        @Override public void execute(Runnable task) { queued.add(task); }
        @Override public void shutdown() { stopped = true; }
        @Override public List<Runnable> shutdownNow() { stopped = true; return List.copyOf(queued); }
        @Override public boolean isShutdown() { return stopped; }
        @Override public boolean isTerminated() { return stopped; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return stopped; }
    }
}
