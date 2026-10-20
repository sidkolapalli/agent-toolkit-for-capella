package org.polarsys.capella.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ScrollBar;
import org.polarsys.capella.ai.presentation.TranscriptFormat;
import org.polarsys.capella.ai.presentation.TranscriptFormat.CodeBlock;
import org.polarsys.capella.ai.presentation.TranscriptFormat.Formatted;
import org.polarsys.capella.ai.presentation.TranscriptFormat.LineKind;

/** Selectable native conversation text. Formatting never changes reviewed source. */
final class ChatTranscript {
    private final StyledText control;
    private final List<Message> messages = new ArrayList<>();
    private final Map<Integer, Font> headingFonts = new HashMap<>();
    private final Listener settingsListener;
    private final IPropertyChangeListener fontListener;
    private Color codeBackground;
    private RGB background, foreground;
    private Consumer<Boolean> unreadListener = ignored -> {};
    private boolean unread, themeRefreshQueued;
    /** The reader is following the newest message; resizes and late wrapping keep the end in view. */
    private boolean pinned = true;
    private CodeBlock selectedCode;

    ChatTranscript(Composite parent) {
        control = new StyledText(parent, SWT.MULTI | SWT.V_SCROLL | SWT.WRAP | SWT.READ_ONLY);
        control.setFont(JFaceResources.getDefaultFont());
        control.setMargins(14, 12, 14, 12);
        control.setLineSpacing(5);
        control.setTabs(4);
        control.getAccessible().addAccessibleListener(new org.eclipse.swt.accessibility.AccessibleAdapter() {
            @Override
            public void getName(org.eclipse.swt.accessibility.AccessibleEvent event) {
                event.result = "Conversation with Capella AI";
            }
        });
        installCopyMenu();
        if (control.getVerticalBar() != null) control.getVerticalBar().addListener(SWT.Selection, event -> readerScrolled());
        control.addListener(SWT.MouseWheel, event -> control.getDisplay().asyncExec(this::readerScrolled));
        control.addListener(SWT.KeyUp, event -> readerScrolled());
        control.addListener(SWT.Resize, event -> { if (pinned) jumpToLatest(); });
        control.addListener(SWT.Paint, event -> {
            if (!control.getBackground().getRGB().equals(background) || !control.getForeground().getRGB().equals(foreground)) queueThemeRefresh();
        });
        settingsListener = event -> queueThemeRefresh();
        fontListener = event -> {
            if (JFaceResources.DEFAULT_FONT.equals(event.getProperty()) || JFaceResources.TEXT_FONT.equals(event.getProperty())) queueThemeRefresh();
        };
        control.getDisplay().addListener(SWT.Settings, settingsListener);
        JFaceResources.getFontRegistry().addListener(fontListener);
        control.addListener(SWT.Dispose, event -> {
            control.getDisplay().removeListener(SWT.Settings, settingsListener);
            JFaceResources.getFontRegistry().removeListener(fontListener);
            disposeOwnedResources();
        });
        updateCodeBackground();
    }

    StyledText control() { return control; }
    boolean isEmpty() { return control.isDisposed() || control.getCharCount() == 0; }
    boolean hasUnreadMessages() { return unread; }

    void setUnreadListener(Consumer<Boolean> listener) {
        unreadListener = listener == null ? ignored -> {} : listener;
        unreadListener.accept(unread);
    }

    void clear() {
        if (control.isDisposed()) return;
        messages.clear();
        selectedCode = null;
        control.setText("");
        setUnread(false);
    }

    void jumpToLatest() {
        if (control.isDisposed()) return;
        // StyledText clamps to its final pixel, including soft-wrapped lines.
        control.setTopPixel(Integer.MAX_VALUE);
        pinned = true;
        setUnread(false);
    }

    void append(String role, String message) {
        if (control.isDisposed()) return;
        boolean follow = isEmpty() || (atBottom() && control.getSelectionCount() == 0);
        int topPixel = control.getTopPixel();
        Point selection = control.getSelectionRange();
        String title = role == null || role.isBlank() ? "Capella AI" : role.replace('\n', ' ').replace('\r', ' ');
        Formatted body = TranscriptFormat.parse(message);
        String separator = isEmpty() ? "" : control.getTextRange(control.getCharCount() - 1, 1).equals("\n") ? "\n" : "\n\n";
        int start = control.getCharCount() + separator.length();
        Message rendered = new Message(start, title.length(), start + title.length() + 1, body);
        control.setRedraw(false);
        try {
            control.append(separator + title + "\n" + body.text());
            messages.add(rendered);
            applyStyles(rendered);
            control.setSelectionRange(selection.x, selection.y);
            if (follow) jumpToLatest();
            else {
                control.setTopPixel(topPixel);
                setUnread(true);
            }
        } finally {
            control.setRedraw(true);
        }
        // Wrapped line heights are exact only once painted; settle on the true end afterwards.
        if (follow) control.getDisplay().asyncExec(() -> { if (!control.isDisposed() && pinned) jumpToLatest(); });
    }

    private void applyStyles(Message message) {
        List<StyleRange> styles = new ArrayList<>();
        styles.add(new StyleRange(message.start(), message.roleLength(), null, null, SWT.BOLD));
        for (TranscriptFormat.Run run : message.body().runs()) {
            var format = run.style();
            StyleRange style = new StyleRange(message.bodyStart() + run.offset(), run.length(), null, null);
            style.fontStyle = (format.bold() ? SWT.BOLD : SWT.NORMAL) | (format.italic() ? SWT.ITALIC : SWT.NORMAL);
            if (format.code()) {
                style.font = JFaceResources.getTextFont();
                style.background = codeBackground;
            } else if (format.headingLevel() > 0) {
                style.font = headingFont(format.headingLevel());
            }
            styles.add(style);
        }
        int[] ranges = new int[styles.size() * 2];
        for (int index = 0; index < styles.size(); index++) {
            ranges[index * 2] = styles.get(index).start;
            ranges[index * 2 + 1] = styles.get(index).length;
        }
        control.setStyleRanges(message.start(), message.roleLength() + 1 + message.body().text().length(), ranges, styles.toArray(StyleRange[]::new));
        for (TranscriptFormat.Line line : message.body().lines()) {
            int lineIndex = control.getLineAtOffset(message.bodyStart() + line.offset());
            if (line.kind() == LineKind.CODE) control.setLineBackground(lineIndex, 1, codeBackground);
            int indent = line.kind() == LineKind.CODE ? 10 : line.indent() * 14;
            control.setLineIndent(lineIndex, 1, indent);
            control.setLineWrapIndent(lineIndex, 1, line.kind() == LineKind.LIST ? 20 : 0);
        }
    }

    private Font headingFont(int level) {
        int sizeStep = level == 1 ? 2 : level == 2 ? 1 : 0;
        return headingFonts.computeIfAbsent(sizeStep, ignored -> {
            FontData[] data = JFaceResources.getDefaultFont().getFontData();
            for (FontData part : data) {
                part.setHeight(part.getHeight() + sizeStep);
                part.setStyle(SWT.BOLD);
            }
            return new Font(control.getDisplay(), data);
        });
    }

    private void installCopyMenu() {
        Menu menu = new Menu(control);
        control.addListener(SWT.Dispose, event -> { if (!menu.isDisposed()) menu.dispose(); });
        MenuItem copy = new MenuItem(menu, SWT.PUSH);
        copy.setText("Copy selection");
        copy.addListener(SWT.Selection, event -> control.copy());
        MenuItem code = new MenuItem(menu, SWT.PUSH);
        code.setText("Copy code block");
        code.addListener(SWT.Selection, event -> {
            if (selectedCode == null) return;
            Clipboard clipboard = new Clipboard(control.getDisplay());
            try {
                clipboard.setContents(new Object[] {selectedCode.source()}, new Transfer[] {TextTransfer.getInstance()});
            } finally {
                clipboard.dispose();
            }
        });
        new MenuItem(menu, SWT.SEPARATOR);
        MenuItem select = new MenuItem(menu, SWT.PUSH);
        select.setText("Select all");
        select.addListener(SWT.Selection, event -> control.selectAll());
        control.setMenu(menu);
        control.addListener(SWT.MenuDetect, event -> {
            int offset = control.getCaretOffset();
            if (event.detail != SWT.MENU_KEYBOARD) {
                try {
                    offset = control.getOffsetAtPoint(control.toControl(event.x, event.y));
                } catch (IllegalArgumentException ignored) {
                    offset = -1;
                }
            }
            selectedCode = codeAtOffset(offset);
            code.setEnabled(selectedCode != null && !selectedCode.source().isEmpty());
            copy.setEnabled(control.getSelectionCount() > 0);
            select.setEnabled(!isEmpty());
        });
    }

    private CodeBlock codeAtOffset(int offset) {
        for (Message message : messages) {
            int local = offset - message.bodyStart();
            if (local < 0 || local >= message.body().text().length()) continue;
            for (CodeBlock block : message.body().codeBlocks()) {
                if (local >= block.offset() && local < block.offset() + block.length()) return block;
            }
        }
        return null;
    }

    private boolean atBottom() {
        ScrollBar scroll = control.getVerticalBar();
        return scroll == null || scroll.getSelection() + scroll.getThumb() >= scroll.getMaximum() - 2;
    }

    /** The reader moved: following resumes only when they are back at the end. */
    private void readerScrolled() {
        if (control.isDisposed()) return;
        pinned = atBottom();
        if (pinned) setUnread(false);
    }

    private void setUnread(boolean value) {
        if (unread == value) return;
        unread = value;
        unreadListener.accept(value);
    }

    private void updateCodeBackground() {
        background = control.getBackground().getRGB();
        foreground = control.getForeground().getRGB();
        codeBackground = new Color(control.getDisplay(),
            (background.red * 94 + foreground.red * 6) / 100,
            (background.green * 94 + foreground.green * 6) / 100,
            (background.blue * 94 + foreground.blue * 6) / 100);
    }

    private void queueThemeRefresh() {
        if (control.isDisposed() || themeRefreshQueued) return;
        themeRefreshQueued = true;
        control.getDisplay().asyncExec(() -> {
            themeRefreshQueued = false;
            if (control.isDisposed()) return;
            boolean follow = atBottom();
            int top = control.getTopPixel();
            Color previousColor = codeBackground;
            List<Font> previousFonts = new ArrayList<>(headingFonts.values());
            headingFonts.clear();
            control.setFont(JFaceResources.getDefaultFont());
            updateCodeBackground();
            control.setRedraw(false);
            try {
                for (Message message : messages) applyStyles(message);
                if (follow) jumpToLatest();
                else control.setTopPixel(top);
            } finally {
                control.setRedraw(true);
                if (previousColor != null && !previousColor.isDisposed()) previousColor.dispose();
                previousFonts.forEach(font -> { if (!font.isDisposed()) font.dispose(); });
            }
        });
    }

    private void disposeOwnedResources() {
        if (codeBackground != null && !codeBackground.isDisposed()) codeBackground.dispose();
        headingFonts.values().forEach(font -> { if (!font.isDisposed()) font.dispose(); });
        headingFonts.clear();
    }

    private record Message(int start, int roleLength, int bodyStart, Formatted body) {}
}
