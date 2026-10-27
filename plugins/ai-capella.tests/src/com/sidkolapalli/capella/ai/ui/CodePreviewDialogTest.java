package com.sidkolapalli.capella.ai.ui;

import static org.junit.Assert.*;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import com.sidkolapalli.capella.ai.llm.LLMService;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Native review controls must preserve the submitted script and require deliberate approval. */
public class CodePreviewDialogTest {
    private Display display;
    private Shell parent;
    private Dialog dialog;

    @Before
    public void setUp() {
        display = Display.getDefault();
        assertSame("Native review tests run on the SWT thread", display, Display.getCurrent());
        parent = new Shell(display);
        parent.setSize(1000, 700);
    }

    @After
    public void tearDown() {
        if (dialog != null && dialog.getShell() != null && !dialog.getShell().isDisposed())
            dialog.close();
        if (parent != null && !parent.isDisposed()) parent.dispose();
    }

    @Test
    public void cancelIsDefaultAndTheGeneratedScriptCannotBeEdited() throws Exception {
        String code = "// Review α & β\r\napi.rename(selection[0], 'Sensor')\n";
        dialog = create(code, "Vehicle & integration", "LA · LogicalComponent: Sensor");
        Text preview = preview();
        assertFalse(preview.getEditable());
        assertEquals(code.replace("\r\n", "\n"), preview.getText().replace("\r\n", "\n"));
        assertTrue((preview.getStyle() & SWT.H_SCROLL) != 0);
        assertTrue((preview.getStyle() & SWT.V_SCROLL) != 0);
        assertEquals("Changes", tabs().getItem(0).getText().replace("&", ""));
        assertEquals(0, tabs().getSelectionIndex());
        assertFalse("The exact script is optional inspection", preview.isVisible());
        assertSame(button(IDialogConstants.CANCEL_ID), dialog.getShell().getDefaultButton());
        assertTrue(
                texts(dialog.getShell()).stream()
                        .anyMatch(text -> "Vehicle & integration".equals(text.getText())));
        button(IDialogConstants.CANCEL_ID).notifyListeners(SWT.Selection, new Event());
        assertEquals(Window.CANCEL, dialog.getReturnCode());
        assertTrue(dialog.getShell() == null || dialog.getShell().isDisposed());
    }

    @Test
    public void tabContentTakesTheThemedTabBackground() throws Exception {
        dialog = create("println 'themed'", "Vehicle", "");
        var themed = new org.eclipse.swt.graphics.Color(display, 41, 41, 41);
        tabs().setBackground(themed);
        Control script = tabs().getItem(1).getControl();
        assertEquals(
                "The theme styles the tab folder but not plain composites inside it",
                themed,
                script.getBackground());
    }

    @Test
    public void onlyTheApplyChangesActionApprovesAReviewableScript() throws Exception {
        dialog = create("println 'reviewed'", "Vehicle", "SA");
        assertTrue(button(IDialogConstants.OK_ID).getEnabled());
        assertEquals("Apply changes", button(IDialogConstants.OK_ID).getText().replace("&", ""));
        button(IDialogConstants.OK_ID).notifyListeners(SWT.Selection, new Event());
        assertEquals(Window.OK, dialog.getReturnCode());
    }

    @Test
    public void emptyCodeCannotBeApprovedEvenByAnExplicitButtonEvent() throws Exception {
        dialog = create(" \r\n\t", "Vehicle", "");
        assertFalse(button(IDialogConstants.OK_ID).getEnabled());
        button(IDialogConstants.OK_ID).notifyListeners(SWT.Selection, new Event());
        assertEquals(Window.CANCEL, dialog.getReturnCode());
        assertFalse(dialog.getShell().isDisposed());
    }

    @Test
    public void nativeNullCharacterTruncationCannotProduceAnApprovedPartialPreview()
            throws Exception {
        dialog = create("println 'safe'\0api.deleteByUuid('later')", "Vehicle", "");
        assertEquals("", preview().getText());
        assertFalse(button(IDialogConstants.OK_ID).getEnabled());
        button(IDialogConstants.OK_ID).notifyListeners(SWT.Selection, new Event());
        assertEquals(Window.CANCEL, dialog.getReturnCode());
        assertFalse(dialog.getShell().isDisposed());
    }

    @Test
    public void longContentFitsANarrowWindowWithoutPushingTheRunAndCancelControlsAway()
            throws Exception {
        String line = "println 'A long script line stays unwrapped and horizontally scrollable'\n";
        dialog =
                create(
                        line.repeat(1200),
                        "A long vehicle project name ".repeat(30),
                        "Selected context ".repeat(60));
        Shell shell = dialog.getShell();
        shell.setSize(500, 420);
        tabs().setSelection(1);
        shell.layout(true, true);
        Text preview = preview();
        assertTrue("The script retains usable vertical space", preview.getSize().y > 40);
        assertTrue("The script retains usable horizontal space", preview.getSize().x > 200);
        assertEquals(1201, preview.getLineCount());
        for (int id : new int[] {IDialogConstants.OK_ID, IDialogConstants.CANCEL_ID}) {
            Button button = button(id);
            Point point = display.map(button, shell, 0, 0);
            Rectangle client = shell.getClientArea();
            assertTrue(
                    "Review actions remain inside the resized window",
                    point.x >= 0
                            && point.y >= 0
                            && point.x + button.getSize().x <= client.width
                            && point.y + button.getSize().y <= client.height);
        }
        Text changes = changes();
        assertTrue(
                texts(shell).stream()
                        .filter(text -> text != preview && text != changes)
                        .allMatch(text -> text.getLineCount() == 1));
    }

    @Test
    public void readableProposalAndExpectedChecksOpenBeforeTheOptionalExactScript()
            throws Exception {
        String summary =
                "Rename Sensor to Front Sensor and retain its allocation.\n"
                    + "Delete the unused diagnostic function.";
        String checks = "Front Sensor keeps the same ID.\nThe diagnostic function is absent.";
        dialog =
                create(
                        "api.rename(selection[0], 'Front Sensor')\r\n",
                        "Vehicle",
                        "LA",
                        summary,
                        checks);
        Text changes = changes();
        assertEquals(0, tabs().getSelectionIndex());
        assertEquals(
                "Proposed changes\n\n" + summary + "\n\nChecks after applying\n\n" + checks,
                changes.getText().replace("\r\n", "\n"));
        assertFalse(changes.getEditable());
        assertTrue((changes.getStyle() & SWT.WRAP) != 0);
        assertTrue((changes.getStyle() & SWT.V_SCROLL) != 0);
        assertEquals(0, changes.getStyle() & SWT.H_SCROLL);
        assertSame(button(IDialogConstants.CANCEL_ID), dialog.getShell().getDefaultButton());
        tabs().setSelection(1);
        dialog.getShell().layout(true, true);
        assertEquals("Script", tabs().getItem(1).getText().replace("&", ""));
        assertEquals(
                "api.rename(selection[0], 'Front Sensor')\n",
                preview().getText().replace("\r\n", "\n"));
        var original = type().getDeclaredField("code");
        original.setAccessible(true);
        assertEquals(
                "The Copy code backing value retains original line delimiters",
                "api.rename(selection[0], 'Front Sensor')\r\n",
                original.get(dialog));
    }

    @Test
    public void omittedProposalDetailsAreReportedWithoutInventedChangesOrChecks() throws Exception {
        dialog = create("println 'read this first'", "Vehicle", "");
        assertTrue(changes().getText().contains("without a readable summary"));
        assertTrue(changes().getText().contains("Review the Script tab"));
        assertTrue(changes().getText().contains("No specific model checks were declared"));
        assertTrue(button(IDialogConstants.OK_ID).getEnabled());
    }

    @Test
    public void longProposalWrapsAndScrollsAtNarrowSizesWhileActionsRemainVisible()
            throws Exception {
        dialog =
                create(
                        "println 'unchanged script'",
                        "Vehicle",
                        "SA",
                        "A readable change description ".repeat(900),
                        "Expected native model state ".repeat(600));
        Shell shell = dialog.getShell();
        shell.setSize(500, 420);
        shell.layout(true, true);
        assertTrue(changes().getSize().x > 200);
        assertTrue(changes().getSize().y > 40);
        assertFalse(changes().getEditable());
        for (int id : new int[] {IDialogConstants.OK_ID, IDialogConstants.CANCEL_ID}) {
            Button button = button(id);
            Point point = display.map(button, shell, 0, 0);
            Rectangle client = shell.getClientArea();
            assertTrue(
                    "Actions stay inside the narrow review",
                    point.x >= 0
                            && point.y >= 0
                            && point.x + button.getSize().x <= client.width
                            && point.y + button.getSize().y <= client.height);
        }
        assertSame(button(IDialogConstants.CANCEL_ID), shell.getDefaultButton());
    }

    @Test
    public void monitorBoundsAndMixedLineDelimitersAreHandledWithoutInflatedCounts()
            throws Exception {
        Method size = type().getDeclaredMethod("boundedSize", Point.class, Rectangle.class);
        size.setAccessible(true);
        Point small =
                (Point) size.invoke(null, new Point(840, 620), new Rectangle(100, 50, 640, 480));
        assertTrue(small.x <= 608 && small.y <= 448 && small.x > 0 && small.y > 0);
        Point regular =
                (Point) size.invoke(null, new Point(840, 620), new Rectangle(0, 0, 1920, 1080));
        assertEquals(new Point(840, 620), regular);
        Method lines = type().getDeclaredMethod("lineCount", String.class);
        lines.setAccessible(true);
        assertEquals(0, lines.invoke(null, ""));
        assertEquals(1, lines.invoke(null, "println 1"));
        assertEquals(4, lines.invoke(null, "first\r\nsecond\nthird\r"));
    }

    private Dialog create(String code, String model, String context) throws Exception {
        var constructor =
                type().getDeclaredConstructor(
                                Shell.class, String.class, String.class, String.class);
        constructor.setAccessible(true);
        Dialog result = (Dialog) constructor.newInstance(parent, code, model, context);
        result.create();
        return result;
    }

    private Dialog create(String code, String model, String context, String summary, String checks)
            throws Exception {
        var constructor =
                type().getDeclaredConstructor(
                                Shell.class,
                                String.class,
                                String.class,
                                String.class,
                                String.class,
                                String.class);
        constructor.setAccessible(true);
        Dialog result =
                (Dialog) constructor.newInstance(parent, code, model, context, summary, checks);
        result.create();
        return result;
    }

    private static Class<?> type() throws ClassNotFoundException {
        return Class.forName(
                "com.sidkolapalli.capella.ai.CodePreviewDialog",
                true,
                LLMService.class.getClassLoader());
    }

    private Text preview() throws Exception {
        var field = type().getDeclaredField("preview");
        field.setAccessible(true);
        return (Text) field.get(dialog);
    }

    private Text changes() throws Exception {
        var field = type().getDeclaredField("changes");
        field.setAccessible(true);
        return (Text) field.get(dialog);
    }

    private CTabFolder tabs() throws Exception {
        var field = type().getDeclaredField("tabs");
        field.setAccessible(true);
        return (CTabFolder) field.get(dialog);
    }

    private Button button(int id) throws Exception {
        Method method = Dialog.class.getDeclaredMethod("getButton", int.class);
        method.setAccessible(true);
        return (Button) method.invoke(dialog, id);
    }

    private static List<Text> texts(Composite root) {
        List<Text> result = new ArrayList<>();
        for (Control child : root.getChildren()) {
            if (child instanceof Text text) result.add(text);
            if (child instanceof Composite composite) result.addAll(texts(composite));
        }
        return result;
    }
}
