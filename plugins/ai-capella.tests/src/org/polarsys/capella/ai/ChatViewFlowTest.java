package org.polarsys.capella.ai;

import static org.junit.Assert.*;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
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
import org.polarsys.capella.ai.agent.ArcadiaAgent;
import org.polarsys.capella.ai.codex.CodexPreferences;
import org.polarsys.capella.ai.llm.LLMService.Message;
import org.polarsys.capella.ai.preferences.LLMPreferences;
import org.polarsys.capella.ai.presentation.ConversationState;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Native dock interaction regressions. No provider or model mutation is involved. */
public class ChatViewFlowTest {
    private ChatView view;
    private Shell shell;
    private String previousRuntime;

    @Before
    public void createDock() throws Exception {
        previousRuntime = CodexPreferences.getRuntime();
        CodexPreferences.setRuntime("direct");
        Display display = Display.getDefault();
        assertSame(display, Display.getCurrent());
        shell = new Shell(display);
        shell.setLayout(new FillLayout());
        shell.setSize(340, 650);
        var toolbar = new ToolBarManager();
        var site =
                stub(
                        IViewSite.class,
                        Map.of(
                                "getActionBars",
                                stub(IActionBars.class, Map.of("getToolBarManager", toolbar)),
                                "getPage",
                                stub(IWorkbenchPage.class, Map.of()),
                                "getShell",
                                shell,
                                "getWorkbenchWindow",
                                stub(
                                        IWorkbenchWindow.class,
                                        Map.of(
                                                "getSelectionService",
                                                stub(ISelectionService.class, Map.of())))));
        view = new ChatView();
        view.init(site);
        view.createPartControl(shell);
        shell.layout(true, true);
        set("conversationStore", null);
    }

    @After
    public void closeDock() {
        if (view != null) view.dispose();
        if (shell != null) shell.dispose();
        if (previousRuntime != null) CodexPreferences.setRuntime(previousRuntime);
    }

    @Test
    public void narrowDockKeepsMultilineComposerAndIdleStopHidden() throws Exception {
        Text input = (Text) field("inputField");
        assertTrue((input.getStyle() & SWT.MULTI) != 0);
        assertTrue(input.getBounds().width > 200);
        assertTrue(input.getBounds().height >= input.getLineHeight() * 2);
        assertFalse(((Button) field("stopButton")).getVisible());
        assertTrue(((Button) field("sendButton")).getVisible());
        Event shifted = new Event();
        shifted.keyCode = SWT.CR;
        shifted.stateMask = SWT.SHIFT;
        shifted.doit = true;
        input.notifyListeners(SWT.KeyDown, shifted);
        assertTrue(shifted.doit);
        Event enter = new Event();
        enter.keyCode = SWT.CR;
        enter.doit = true;
        input.notifyListeners(SWT.KeyDown, enter);
        assertFalse(enter.doit);
    }

    /**
     * Seen in an installed Capella: the Send row was laid out with zero height and the button was
     * clipped away.
     */
    @Test
    public void sendAndComposerStayFullyVisibleAtNarrowAndTallDockSizes() throws Exception {
        for (int[] size : new int[][] {{340, 650}, {802, 1334}, {480, 420}}) {
            shell.setSize(size[0], size[1]);
            shell.layout(true, true);
            Display.getDefault().readAndDispatch();
            Button send = (Button) field("sendButton");
            Text input = (Text) field("inputField");
            var area = shell.getClientArea();
            var sendBounds = Display.getDefault().map(send.getParent(), shell, send.getBounds());
            var row = send.getParent().getBounds();
            assertTrue(
                    "Send row has height at " + size[0] + "x" + size[1] + ": " + row,
                    row.height >= send.computeSize(SWT.DEFAULT, SWT.DEFAULT).y);
            assertTrue(
                    "Send is inside the dock at " + size[0] + "x" + size[1] + ": " + sendBounds,
                    sendBounds.y >= 0
                            && sendBounds.y + sendBounds.height <= area.height
                            && sendBounds.width > 0);
            var inputBounds = Display.getDefault().map(input.getParent(), shell, input.getBounds());
            assertTrue(
                    "Composer is above the Send row",
                    inputBounds.y + inputBounds.height <= sendBounds.y + 2);
            assertTrue(
                    "Composer keeps two lines",
                    input.getBounds().height >= input.getLineHeight() * 2);
        }
    }

    /** First launch: the view is created hidden at zero size, then shown at full size. */
    @Test
    public void viewCreatedHiddenThenShownStillShowsSend() throws Exception {
        view.dispose();
        shell.dispose();
        shell = new Shell(Display.getDefault());
        shell.setLayout(new FillLayout());
        shell.setSize(0, 0);
        var toolbar = new ToolBarManager();
        var site =
                stub(
                        IViewSite.class,
                        Map.of(
                                "getActionBars",
                                stub(IActionBars.class, Map.of("getToolBarManager", toolbar)),
                                "getPage",
                                stub(IWorkbenchPage.class, Map.of()),
                                "getShell",
                                shell,
                                "getWorkbenchWindow",
                                stub(
                                        IWorkbenchWindow.class,
                                        Map.of(
                                                "getSelectionService",
                                                stub(ISelectionService.class, Map.of())))));
        view = new ChatView();
        view.init(site);
        view.createPartControl(shell);
        set("conversationStore", null);
        shell.layout(true, true);
        for (int width = 8; width <= 64; width += 8) {
            shell.setSize(width, width);
            while (Display.getDefault().readAndDispatch()) {}
        }
        shell.setSize(802, 1334);
        while (Display.getDefault().readAndDispatch()) {}
        Button send = (Button) field("sendButton");
        var row = send.getParent();
        assertTrue(
                "Send row fits its composer: "
                        + row.getBounds()
                        + " in "
                        + row.getParent().getBounds(),
                row.getBounds().height > 0
                        && row.getBounds().y + row.getBounds().height
                                <= row.getParent().getBounds().height);
    }

    @Test
    public void wrappingStatusAndReviewRowsNeverPushSendOutOfTheDock() throws Exception {
        shell.setSize(340, 650);
        shell.layout(true, true);
        call(
                "setActivity",
                new Class<?>[] {String.class},
                "Waiting for your review · No code has run · The proposed change touches several"
                    + " layers and needs a careful look before it is applied");
        call(
                "showControl",
                new Class<?>[] {org.eclipse.swt.widgets.Control.class, boolean.class},
                field("reviewArea"),
                true);
        Display.getDefault().readAndDispatch();
        Button send = (Button) field("sendButton");
        var sendBounds = Display.getDefault().map(send.getParent(), shell, send.getBounds());
        var row = send.getParent();
        assertTrue(
                "Send row is inside its composer: "
                        + row.getBounds()
                        + " in "
                        + row.getParent().getBounds(),
                row.getBounds().y + row.getBounds().height <= row.getParent().getBounds().height);
        assertTrue(
                "Send is inside the dock: " + sendBounds,
                sendBounds.y + sendBounds.height <= shell.getClientArea().height);
    }

    @Test
    public void switchingModelsRestoresEachConversationAndDraft() throws Exception {
        var first = session("memory:/chat-test-first.aird");
        var second = session("memory:/chat-test-second.aird");
        call("switchConversation", new Class<?>[] {Session.class}, first);
        call(
                "appendMessage",
                new Class<?>[] {String.class, String.class},
                "Capella AI",
                "First model reply");
        ((Text) field("inputField")).setText("First model draft ⚡");
        call(
                "saveTaskPlan",
                new Class<?>[] {List.class},
                List.of(
                        new ArcadiaAgent.Step("inspect", "Inspect SA", "completed", "read"),
                        new ArcadiaAgent.Step("edit", "Add the interface", "pending", "edit")));
        call("switchConversation", new Class<?>[] {Session.class}, second);
        assertEquals("", ((Text) field("inputField")).getText());
        assertEquals("", ((Text) field("taskPlanText")).getText());
        call(
                "appendMessage",
                new Class<?>[] {String.class, String.class},
                "Capella AI",
                "Second model reply");
        call("switchConversation", new Class<?>[] {Session.class}, first);
        assertEquals("First model draft ⚡", ((Text) field("inputField")).getText());
        assertEquals(
                "Done · Inspect SA\nPending · Add the interface",
                ((Text) field("taskPlanText")).getText().replace("\r\n", "\n"));
        var state = (ConversationState) field("conversation");
        assertTrue(state.entries().stream().anyMatch(e -> e.text().equals("First model reply")));
        assertFalse(state.entries().stream().anyMatch(e -> e.text().equals("Second model reply")));
    }

    @Test
    public void narrowPlanCanCollapseWithoutLosingStatusesAndResetClearsIt() throws Exception {
        call(
                "saveTaskPlan",
                new Class<?>[] {List.class},
                List.of(
                        new ArcadiaAgent.Step(
                                "edit", "Create the requested function", "pending", "edit")));
        Text plan = (Text) field("taskPlanText");
        assertTrue(plan.getVisible());
        assertTrue(plan.getBounds().width > 200);
        ((Button) field("taskPlanToggle")).notifyListeners(SWT.Selection, new Event());
        assertFalse(plan.getVisible());
        assertTrue(((ConversationState) field("conversation")).taskPlan().startsWith("Pending"));
        ((Button) field("taskPlanToggle")).notifyListeners(SWT.Selection, new Event());
        assertTrue(plan.getVisible());
        call("resetConversation", new Class<?>[] {Session.class, boolean.class}, null, true);
        assertEquals("", plan.getText());
        assertFalse(((Composite) field("taskPlanArea")).getVisible());
    }

    @Test
    public void modelSwitchReleasesPendingReviewInsteadOfLeavingAStuckTurn() throws Exception {
        var pending = new CompletableFuture<Boolean>();
        set("requestSession", session("memory:/foreign-review.aird"));
        set("pendingReview", pending);
        call("setBusy", new Class<?>[] {boolean.class}, true);
        call("refreshContext", new Class<?>[] {});
        assertTrue(pending.isDone());
        assertFalse(pending.get());
        assertTrue((boolean) field("stopRequested"));
        assertTrue(((Label) field("activityLabel")).getText().contains("Stopping"));
    }

    @Test
    public void dismissingProposalNeverGrantsExecutionAndKeepsRevisionDraft() throws Exception {
        var pending = new CompletableFuture<Boolean>();
        set("pendingReview", pending);
        call("setBusy", new Class<?>[] {boolean.class}, true);
        set("requestStarted", System.nanoTime() - 60_000_000_000L);
        call(
                "setActivity",
                new Class<?>[] {String.class},
                "Waiting for your review · No code has run");
        assertEquals(
                "Waiting for your review · No code has run",
                ((Label) field("activityLabel")).getText());
        ((Text) field("inputField")).setText("Use a different name instead");
        call("dismissReview", new Class<?>[] {});
        assertFalse(pending.get());
        assertEquals("Use a different name instead", ((Text) field("inputField")).getText());
    }

    @Test
    public void providerSettingsChangedOutsideTheViewRefreshReadiness() throws Exception {
        String provider = LLMPreferences.getProvider(), model = LLMPreferences.getModel();
        try {
            LLMPreferences.setProvider("custom");
            LLMPreferences.setModel("");
            call("refreshContext", new Class<?>[] {});
            assertFalse((boolean) field("providerConfigured"));
            LLMPreferences.setModel("local-test-model");
            call("refreshContext", new Class<?>[] {});
            assertTrue((boolean) field("providerConfigured"));
        } finally {
            LLMPreferences.setProvider(provider);
            LLMPreferences.setModel(model);
        }
    }

    @Test
    public void closingViewPreservesDraftAndReleasesReview() throws Exception {
        var pending = new CompletableFuture<Boolean>();
        set("pendingReview", pending);
        ((Text) field("inputField")).setText("Resume this tomorrow");
        view.dispose();
        assertFalse(pending.get());
        assertEquals("Resume this tomorrow", ((ConversationState) field("conversation")).draft());
        view = null;
    }

    @Test
    public void chatgptSignInIsDiscoverableWithoutAModelAndDisabledDuringModelWork()
            throws Exception {
        CodexPreferences.setRuntime("codex");
        call("refreshContext", new Class<?>[] {});
        Button signIn = (Button) field("signInButton");
        assertEquals("Sign in with ChatGPT", signIn.getText());
        assertTrue(signIn.getVisible());
        assertTrue(signIn.getEnabled());
        assertTrue(signIn.getSize().x > 100);
        call("setBusy", new Class<?>[] {boolean.class}, true);
        assertFalse(signIn.getEnabled());
        call("signInWithChatGPT", new Class<?>[] {});
        assertFalse("A busy model cannot start account setup", (boolean) field("signingIn"));
        call("setBusy", new Class<?>[] {boolean.class}, false);
        assertTrue(signIn.getEnabled());
        CodexPreferences.setRuntime("direct");
        call("refreshContext", new Class<?>[] {});
        assertFalse(signIn.getVisible());
    }

    @Test
    public void aConfirmedChatgptAccountIsNotAskedToSignInAgainUntilItExpires() throws Exception {
        CodexPreferences.setRuntime("codex");
        call("refreshContext", new Class<?>[] {});
        Button signIn = (Button) field("signInButton");
        call("accountConfirmed", new Class<?>[] {boolean.class}, true);
        assertEquals("ChatGPT account", signIn.getText());
        assertTrue(
                "Switching or reconnecting stays possible",
                signIn.getVisible() && signIn.getEnabled());
        call("accountConfirmed", new Class<?>[] {boolean.class}, false);
        assertEquals("Sign in with ChatGPT", signIn.getText());
    }

    @Test
    public void accountSetupDisablesModelSendSettingsAndDuplicateSignIn() throws Exception {
        CodexPreferences.setRuntime("codex");
        call("refreshContext", new Class<?>[] {});
        set("activeSession", session("memory:/account-setup.aird"));
        set("providerConfigured", true);
        ((Text) field("inputField")).setText("Explain the current architecture");
        set("signingIn", true);
        call("updateControls", new Class<?>[] {});
        assertFalse(((Button) field("signInButton")).getEnabled());
        assertFalse(((Button) field("sendButton")).getEnabled());
        assertFalse(((Button) field("preferencesButton")).getEnabled());
        call("handleSend", new Class<?>[] {});
        assertFalse((boolean) field("busy"));
        assertEquals("Explain the current architecture", ((Text) field("inputField")).getText());
    }

    @Test
    public void codexUiReadinessDefersBinaryDiscoveryAndExtractionToTheWorker() throws Exception {
        String previousBinary = CodexPreferences.getBinary();
        try {
            CodexPreferences.setRuntime("codex");
            CodexPreferences.setBinary("missing-disposable-codex-runtime.exe");
            call("refreshContext", new Class<?>[] {});
            assertTrue(
                    "SWT readiness validates syntax without resolving a nonexistent runtime",
                    (boolean) field("providerConfigured"));
            assertTrue(((Label) field("providerLabel")).getText().startsWith("Codex · "));
            CodexPreferences.setBinary("invalid\0runtime.exe");
            call("refreshProvider", new Class<?>[] {});
            assertFalse((boolean) field("providerConfigured"));
        } finally {
            CodexPreferences.setBinary(previousBinary);
        }
    }

    @Test
    public void proposalsShowReadableIntentAndChecksWithoutAppendingExecutableGroovy()
            throws Exception {
        String code = "api.rename(selection[0], 'Front Sensor')\r\n";
        Message exact =
                Message.assistant("{\"code\":\"api.rename(selection[0], 'Front Sensor')\\r\\n\"}");
        var condition =
                new ArcadiaAgent.Postcondition(
                        "rename",
                        "element_exists",
                        Map.of("type", "LogicalComponent", "name", "Front Sensor", "layer", "LA"));
        var turn =
                new ArcadiaAgent.Turn(
                        "Rename Sensor to Front Sensor and keep its allocation.",
                        code,
                        List.of(),
                        List.of(exact),
                        1,
                        0,
                        java.util.Set.of("rename"),
                        List.of(condition));
        var method = ChatView.class.getDeclaredMethod("displayedReply", ArcadiaAgent.Turn.class);
        method.setAccessible(true);
        String rendered = (String) method.invoke(null, turn);
        assertTrue(rendered.startsWith(turn.reply()));
        assertTrue(rendered.contains("Expected model checks"));
        assertTrue(rendered.contains("Front Sensor"));
        assertFalse(rendered.contains(code.strip()));
        assertFalse(rendered.contains("```groovy"));
        assertSame(
                "The provider's exact proposal history stays intact",
                exact,
                turn.messages().get(0));
        assertEquals(code, turn.proposalCode());
        assertEquals("Review changes…", ((Button) field("reviewButton")).getText());
    }

    @Test
    public void unexpectedAccountErrorsCannotExposeRemoteUrlsTokensOrRawDetails() throws Exception {
        var method = ChatView.class.getDeclaredMethod("signInFailureMessage", Throwable.class);
        method.setAccessible(true);
        String message =
                (String)
                        method.invoke(
                                null,
                                new IllegalStateException(
                                        "https://auth.openai.com/oauth/authorize?token=private-secret"
                                            + " raw account response"));
        assertEquals("ChatGPT sign-in could not complete. Try again or check Settings.", message);
        assertFalse(message.contains("https"));
        assertFalse(message.contains("private-secret"));
    }

    private Object field(String name) throws Exception {
        var f = ChatView.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(view);
    }

    private void set(String name, Object value) throws Exception {
        var f = ChatView.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(view, value);
    }

    private void call(String name, Class<?>[] types, Object... values) throws Exception {
        var m = ChatView.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        m.invoke(view, values);
    }

    private static Session session(String uri) {
        return stub(
                Session.class,
                Map.of(
                        "isOpen",
                        true,
                        "getSessionResource",
                        new ResourceImpl(URI.createURI(uri)),
                        "getSemanticResources",
                        List.of()));
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, Map<String, Object> methods) {
        return (T)
                Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        (proxy, method, values) -> {
                            if (method.getName().equals("equals")) return proxy == values[0];
                            if (method.getName().equals("hashCode"))
                                return System.identityHashCode(proxy);
                            if (methods.containsKey(method.getName()))
                                return methods.get(method.getName());
                            if (method.getReturnType() == boolean.class) return false;
                            if (method.getReturnType() == int.class) return 0;
                            return null;
                        });
    }
}
