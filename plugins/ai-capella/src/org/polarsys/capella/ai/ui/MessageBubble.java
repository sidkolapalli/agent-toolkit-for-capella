package org.polarsys.capella.ai.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single message bubble in the conversation.
 * Displays messages with role-specific styling (user/assistant/system).
 */
public class MessageBubble {

    public enum Role {
        USER,       // User messages (right-aligned, blue background)
        ASSISTANT,  // AI responses (left-aligned, light gray background)
        SYSTEM      // System messages (centered, yellow background)
    }

    private final Composite bubble;
    private final Text contentText;
    private final Role role;
    private boolean streaming;
    private final Color bubbleColor;
    private final Color labelColor;
    private Color streamingColor;

    public MessageBubble(Composite parent, Role role) {
        this.role = role;
        this.streaming = false;

        // Wrapper for alignment
        Composite wrapper = new Composite(parent, SWT.NONE);
        wrapper.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        wrapper.setLayout(new GridLayout(3, false));
        wrapper.setBackground(Display.getDefault().getSystemColor(SWT.COLOR_WHITE));

        // Add spacers for alignment based on role
        if (role == Role.USER) {
            // User messages: left spacer pushes bubble right
            Label leftSpacer = new Label(wrapper, SWT.NONE);
            leftSpacer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            leftSpacer.setBackground(Display.getDefault().getSystemColor(SWT.COLOR_WHITE));
        }

        // The actual bubble container
        bubbleColor = getBubbleColor(role);
        labelColor = getRoleLabelColor(role);
        bubble = new Composite(wrapper, SWT.BORDER);
        final GridData bubbleData = new GridData(SWT.FILL, SWT.TOP, role == Role.SYSTEM, false);
        // Dynamic width: proportionally adapt to container like iOS Messages
        if (role != Role.SYSTEM) {
            // Initial width based on viewport: ~70% of available, with safe margins
            int initialAvail = wrapper.getClientArea().width;
            if (initialAvail <= 0) initialAvail = wrapper.getSize().x;
            int initial = computeBubbleWidth(initialAvail);
            bubbleData.widthHint = initial;
            wrapper.addListener(SWT.Resize, e -> {
                int availableWidth = wrapper.getClientArea().width;
                if (availableWidth <= 0) availableWidth = wrapper.getSize().x;
                if (availableWidth > 0) {
                    bubbleData.widthHint = computeBubbleWidth(availableWidth);
                    wrapper.layout(true, true);
                }
            });
        } else {
            bubbleData.widthHint = SWT.DEFAULT;
        }
        bubble.setLayoutData(bubbleData);
        bubble.setLayout(new GridLayout(1, false));
        bubble.setBackground(bubbleColor);
        bubble.addDisposeListener(e -> disposeColors());

        // Role label (e.g., "You" or "Assistant")
        Label roleLabel = new Label(bubble, SWT.NONE);
        roleLabel.setText(getRoleLabel(role));
        roleLabel.setForeground(labelColor);
        roleLabel.setBackground(bubbleColor);
        roleLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        // Message content
        contentText = new Text(bubble, SWT.MULTI | SWT.WRAP | SWT.READ_ONLY);
        GridData textData = new GridData(SWT.FILL, SWT.FILL, true, false);
        textData.minimumHeight = 30;
        contentText.setLayoutData(textData);
        contentText.setBackground(bubbleColor);
        contentText.setForeground(Display.getDefault().getSystemColor(SWT.COLOR_BLACK));

        if (role == Role.ASSISTANT) {
            // Assistant messages: right spacer pushes bubble left
            Label rightSpacer = new Label(wrapper, SWT.NONE);
            rightSpacer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            rightSpacer.setBackground(Display.getDefault().getSystemColor(SWT.COLOR_WHITE));
        } else if (role == Role.USER) {
            // Add small right margin for user messages
            Label rightMargin = new Label(wrapper, SWT.NONE);
            GridData marginData = new GridData(SWT.RIGHT, SWT.CENTER, false, false);
            marginData.widthHint = 10;
            rightMargin.setLayoutData(marginData);
            rightMargin.setBackground(Display.getDefault().getSystemColor(SWT.COLOR_WHITE));
        }
    }

    /**
     * Set the message text.
     */
    public void setText(String text) {
        if (!contentText.isDisposed()) {
            contentText.setText(breakLongTokens(text));
        }
    }

    /**
     * Append text to existing content (for streaming).
     */
    public void appendText(String delta) {
        if (!contentText.isDisposed()) {
            contentText.append(breakLongTokens(delta));
        }
    }

    /**
     * Mark this bubble as streaming (shows indicator).
     */
    public void setStreaming(boolean streaming) {
        this.streaming = streaming;
        if (streaming && !contentText.isDisposed()) {
            // Add visual streaming indicator
            Display.getDefault().asyncExec(() -> {
                if (!contentText.isDisposed()) {
                    contentText.setBackground(getStreamingColor());
                }
            });
        } else if (!contentText.isDisposed()) {
            contentText.setBackground(bubbleColor);
        }
    }

    /**
     * Check if disposed.
     */
    public boolean isDisposed() {
        return bubble.isDisposed();
    }

    /**
     * Dispose this bubble.
     */
    public void dispose() {
        if (!bubble.isDisposed()) {
            Composite wrapper = bubble.getParent();
            bubble.dispose();
            if (wrapper != null && !wrapper.isDisposed()) {
                wrapper.dispose();
            }
        }
        disposeColors();
    }

    /**
     * Get background color for role.
     */
    private Color getBubbleColor(Role role) {
        Display display = Display.getDefault();
        switch (role) {
            case USER:
                return new Color(display, 220, 235, 255);
            case ASSISTANT:
                return new Color(display, 245, 245, 245);
            case SYSTEM:
                return new Color(display, 255, 250, 220);
            default:
                return display.getSystemColor(SWT.COLOR_WHITE);
        }
    }

    /**
     * Get color for streaming indicator.
     */
    private Color getStreamingColor() {
        // Slightly darker background during streaming
        if (streamingColor == null || streamingColor.isDisposed()) {
            streamingColor = new Color(Display.getDefault(), 235, 235, 235);
        }
        return streamingColor;
    }

    /**
     * Get role label text.
     */
    private String getRoleLabel(Role role) {
        switch (role) {
            case USER:
                return "You";
            case ASSISTANT:
                return "Assistant";
            case SYSTEM:
                return "System";
            default:
                return "";
        }
    }

    /**
     * Get role label color.
     */
    private Color getRoleLabelColor(Role role) {
        Display display = Display.getDefault();
        switch (role) {
            case USER:
                return new Color(display, 0, 100, 200);
            case ASSISTANT:
                return new Color(display, 100, 100, 100);
            case SYSTEM:
                return new Color(display, 150, 120, 0);
            default:
                return display.getSystemColor(SWT.COLOR_BLACK);
        }
    }

    private void disposeColors() {
        disposeIfNeeded(bubbleColor);
        disposeIfNeeded(labelColor);
        disposeIfNeeded(streamingColor);
        streamingColor = null;
    }

    private void disposeIfNeeded(Color color) {
        if (color != null && !color.isDisposed()) {
            color.dispose();
        }
    }

    private static int computeBubbleWidth(int availableWidth) {
        if (availableWidth <= 0) return 360;
        // Reserve safe area margins (left+right)
        int safe = 32;
        int usable = Math.max(120, availableWidth - safe);
        // Target 70% of usable width, clamp to readability range
        int target = (int) Math.round(usable * 0.70);
        int min = 280;   // comfortable minimum
        int max = 680;   // comfortable maximum for readability
        int width = Math.min(Math.max(target, min), max);
        // Ensure we never exceed usable width
        return Math.min(width, usable);
    }

    /**
     * Insert zero-width spaces into very long unbroken tokens to allow wrap.
     */
    private static String breakLongTokens(String s) {
        if (s == null || s.isBlank()) return s;
        // Find sequences of non-whitespace of length >= 50
        Pattern p = Pattern.compile("\\S{50,}");
        Matcher m = p.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String token = m.group();
            StringBuilder broken = new StringBuilder();
            int chunk = 20; // insert ZWSP every 20 chars
            for (int i = 0; i < token.length(); i += chunk) {
                int end = Math.min(i + chunk, token.length());
                broken.append(token, i, end);
                if (end < token.length()) broken.append('\u200B');
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(broken.toString()));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
