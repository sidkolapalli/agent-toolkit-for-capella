package org.polarsys.capella.ai.presentation;

import static org.junit.Assert.*;

import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.FrameworkUtil;
import org.polarsys.capella.ai.scripting.ScriptRouter;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Native selection, wrapping, scrolling, and resource-lifecycle regressions. */
public class ChatTranscriptTest {
    private Shell shell;
    private Object transcript;
    private Class<?> type;
    private StyledText control;

    @Before
    public void setUp() throws Exception {
        assertSame(
                "The platform harness must use its SWT UI thread",
                Display.getDefault(),
                Display.getCurrent());
        shell = new Shell(Display.getCurrent());
        shell.setLayout(new FillLayout());
        shell.setSize(300, 190);
        var bundle = FrameworkUtil.getBundle(ScriptRouter.class);
        type =
                bundle == null
                        ? Class.forName("org.polarsys.capella.ai.ChatTranscript")
                        : bundle.loadClass("org.polarsys.capella.ai.ChatTranscript");
        var constructor = type.getDeclaredConstructor(org.eclipse.swt.widgets.Composite.class);
        constructor.setAccessible(true);
        transcript = constructor.newInstance(shell);
        control = (StyledText) invoke("control");
        shell.layout(true, true);
    }

    @After
    public void tearDown() {
        if (shell != null && !shell.isDisposed()) shell.dispose();
    }

    @Test
    public void nativeTextHasSemanticStylesAndSafeSelectableCode() throws Exception {
        append(
                "Capella AI",
                "## Plan\nUse **review** and `api.find`.\n```groovy\nprintln '**literal**'\n```");
        assertEquals(
                "Capella AI\nPlan\nUse review and api.find.\nprintln '**literal**'\n",
                control.getText());
        assertEquals(SWT.BOLD, control.getStyleRangeAtOffset(0).fontStyle);
        int code = control.getText().indexOf("println");
        assertSame(JFaceResources.getTextFont(), control.getStyleRangeAtOffset(code).font);
        assertNotNull(control.getStyleRangeAtOffset(code).background);
        int selectionStart = control.getText().indexOf("**literal**");
        control.setSelectionRange(selectionStart, "**literal**".length());
        assertEquals("**literal**", control.getSelectionText());
    }

    @Test
    public void replyDoesNotMoveEarlierReadingPositionOrSelection() throws Exception {
        fillConversation();
        control.setTopPixel(0);
        control.setSelectionRange(0, 6);
        Point before = control.getSelectionRange();
        append("Capella AI", "A new response at the end of the conversation.");
        assertEquals(0, control.getTopPixel());
        assertEquals(before, control.getSelectionRange());
        assertEquals(Boolean.TRUE, invoke("hasUnreadMessages"));
    }

    @Test
    public void latestActionFollowsSoftWrappedRepliesAndClearsUnread() throws Exception {
        fillConversation();
        control.setTopPixel(0);
        append("Capella AI", "Unread response");
        assertEquals(Boolean.TRUE, invoke("hasUnreadMessages"));
        invoke("jumpToLatest");
        assertEquals(Boolean.FALSE, invoke("hasUnreadMessages"));
        append("Capella AI", "This new reply wraps across the narrow pane. ".repeat(20));
        assertEquals(Boolean.FALSE, invoke("hasUnreadMessages"));
        var scrollbar = control.getVerticalBar();
        assertTrue(scrollbar.getSelection() + scrollbar.getThumb() >= scrollbar.getMaximum() - 2);
        assertEquals(0, control.getHorizontalPixel());
    }

    @Test
    public void clearResetsUnreadAndSignalsStateChanges() throws Exception {
        List<Boolean> states = new ArrayList<>();
        invoke(
                "setUnreadListener",
                new Class<?>[] {Consumer.class},
                (Consumer<Boolean>) states::add);
        fillConversation();
        control.setTopPixel(0);
        append("Capella AI", "Unread reply");
        invoke("clear");
        assertTrue(control.getText().isEmpty());
        assertEquals(Boolean.FALSE, invoke("hasUnreadMessages"));
        assertEquals(List.of(false, true, false), states);
    }

    @Test
    public void disposalReleasesOwnedFontsAndCodeColor() throws Exception {
        append("Capella AI", "## A heading\n```groovy\nprintln 1\n```");
        var heading = control.getStyleRangeAtOffset(control.getText().indexOf("A heading")).font;
        Color color =
                control.getStyleRangeAtOffset(control.getText().indexOf("println")).background;
        shell.dispose();
        assertTrue(color.isDisposed());
        assertTrue(heading.isDisposed());
    }

    private void fillConversation() throws Exception {
        for (int index = 0; index < 20; index++)
            append(
                    "Capella AI",
                    "Response "
                            + index
                            + ": a readable line that wraps in this narrow native pane. "
                                    .repeat(3));
    }

    /**
     * Seen in Capella: the last wrapped lines of a reply stayed below the fold while following the
     * chat.
     */
    @Test
    public void followingTheChatShowsTheLastWrappedLineEvenAfterTheTranscriptShrinks()
            throws Exception {
        String paragraph =
                "The execution feedback and model reads describe the actual result and native"
                    + " validation diagnostics are reported separately, so this long reply must"
                    + " wrap across many lines in a narrow dock. ";
        for (int i = 0; i < 6; i++)
            append(i % 2 == 0 ? "Capella AI" : "Model changes", paragraph.repeat(3));
        while (Display.getCurrent().readAndDispatch()) {}
        assertTrue("Scrolled to the very end after appending", atEnd());
        shell.setSize(300, 140);
        shell.layout(true, true);
        while (Display.getCurrent().readAndDispatch()) {}
        assertTrue("Still at the end after the transcript got shorter", atEnd());
    }

    private boolean atEnd() {
        // getLinePixel is relative to the visible area: the end of the text must be within it.
        return control.getLinePixel(control.getLineCount()) <= control.getClientArea().height + 2;
    }

    private void append(String role, String text) throws Exception {
        invoke("append", new Class<?>[] {String.class, String.class}, role, text);
    }

    private Object invoke(String method) throws Exception {
        return invoke(method, new Class<?>[0]);
    }

    private Object invoke(String name, Class<?>[] parameters, Object... values) throws Exception {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(transcript, values);
    }
}
