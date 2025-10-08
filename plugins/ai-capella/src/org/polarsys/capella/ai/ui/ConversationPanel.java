package org.polarsys.capella.ai.ui;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Control;
import org.polarsys.capella.ai.llm.ToolRouter.ToolPlan;

/**
 * Conversational UI panel displaying message bubbles in a chat-like interface.
 * Replaces the plain Text widget with rich message formatting and inline plan previews.
 */
public class ConversationPanel {

    public final ScrolledComposite scroller;
    private final Composite messagesContainer;
    private final List<MessageBubble> messages;
    private Label emptyStateLabel;
    private MessageBubble streamingBubble;

    public ConversationPanel(Composite parent) {
        this.messages = new ArrayList<>();

        scroller = new ScrolledComposite(parent, SWT.V_SCROLL | SWT.BORDER);
        scroller.setExpandHorizontal(true);
        scroller.setExpandVertical(true);
        scroller.setAlwaysShowScrollBars(false);
        scroller.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        messagesContainer = new Composite(scroller, SWT.NONE);
        messagesContainer.setLayout(new GridLayout(1, false));
        messagesContainer.setBackground(Display.getDefault().getSystemColor(SWT.COLOR_WHITE));
        scroller.setContent(messagesContainer);

        // Recompute min size when the scroller resizes so wrapped text
        // computes height against the actual available width.
        scroller.addListener(SWT.Resize, e -> refreshLayout());

        showEmptyState();
    }

    /**
     * Show welcoming empty state when no messages exist.
     */
    private void showEmptyState() {
        emptyStateLabel = new Label(messagesContainer, SWT.WRAP);
        emptyStateLabel.setText(
            "👋 Welcome to Capella AI Assistant\n\n" +
            "I'm here to help you build Arcadia models through conversation.\n\n" +
            "You can:\n" +
            "• Ask me to create system components, functions, or capabilities\n" +
            "• Request architectural transformations between layers\n" +
            "• Get guidance on next steps in your Arcadia methodology\n" +
            "• Explain selected model elements\n\n" +
            "Try asking: \"Create an operational mission for customer checkout\""
        );
        emptyStateLabel.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, true, true));
        emptyStateLabel.setForeground(Display.getDefault().getSystemColor(SWT.COLOR_DARK_GRAY));
        refreshLayout();
    }

    /**
     * Hide empty state when first message arrives.
     */
    private void hideEmptyState() {
        if (emptyStateLabel != null && !emptyStateLabel.isDisposed()) {
            emptyStateLabel.dispose();
            emptyStateLabel = null;
            refreshLayout();
        }
    }

    /**
     * Add a user message bubble.
     */
    public void addUserMessage(String text) {
        hideEmptyState();
        MessageBubble bubble = new MessageBubble(messagesContainer, MessageBubble.Role.USER);
        bubble.setText(text);
        messages.add(bubble);
        refreshLayout();
        scrollToBottom();
    }

    /**
     * Add an assistant message bubble.
     */
    public void addAssistantMessage(String text) {
        hideEmptyState();
        MessageBubble bubble = new MessageBubble(messagesContainer, MessageBubble.Role.ASSISTANT);
        bubble.setText(text);
        messages.add(bubble);
        refreshLayout();
        scrollToBottom();
    }

    /**
     * Start streaming an assistant response. Returns the streaming bubble.
     */
    public void startStreamingResponse() {
        hideEmptyState();
        streamingBubble = new MessageBubble(messagesContainer, MessageBubble.Role.ASSISTANT);
        streamingBubble.setText("");
        streamingBubble.setStreaming(true);
        messages.add(streamingBubble);
        refreshLayout();
        scrollToBottom();
    }

    /**
     * Append text to the currently streaming response.
     */
    public void appendStreamingText(String delta) {
        if (streamingBubble != null && !streamingBubble.isDisposed()) {
            streamingBubble.appendText(delta);
            scrollToBottom();
        }
    }

    /**
     * Finish streaming and finalize the message.
     */
    public void finishStreaming() {
        if (streamingBubble != null && !streamingBubble.isDisposed()) {
            streamingBubble.setStreaming(false);
            streamingBubble = null;
        }
    }

    /**
     * Add a plan preview box inline in the conversation.
     */
    public PlanPreviewBox addPlanPreview(ToolPlan plan, List<String> summaryLines) {
        hideEmptyState();
        PlanPreviewBox previewBox = new PlanPreviewBox(messagesContainer);
        previewBox.setPlan(plan, summaryLines);
        refreshLayout();
        scrollToBottom();
        return previewBox;
    }

    /**
     * Add an error message.
     */
    public void addErrorMessage(String error) {
        hideEmptyState();
        MessageBubble bubble = new MessageBubble(messagesContainer, MessageBubble.Role.SYSTEM);
        bubble.setText("⚠️ Error: " + error);
        messages.add(bubble);
        refreshLayout();
        scrollToBottom();
    }

    /**
     * Add a system message (e.g., "Generation cancelled").
     */
    public void addSystemMessage(String text) {
        hideEmptyState();
        MessageBubble bubble = new MessageBubble(messagesContainer, MessageBubble.Role.SYSTEM);
        bubble.setText(text);
        messages.add(bubble);
        refreshLayout();
        scrollToBottom();
    }

    /**
     * Clear all messages and show empty state.
     */
    public void clear() {
        for (MessageBubble bubble : messages) {
            bubble.dispose();
        }
        messages.clear();
        streamingBubble = null;
        for (Control child : messagesContainer.getChildren()) {
            if (!child.isDisposed()) {
                child.dispose();
            }
        }
        showEmptyState();
        refreshLayout();
    }

    /**
     * Scroll to the bottom of the conversation.
     */
    private void scrollToBottom() {
        Display.getDefault().asyncExec(() -> {
            if (!scroller.isDisposed() && !messagesContainer.isDisposed()) {
                scroller.setOrigin(0, messagesContainer.getSize().y);
            }
        });
    }

    /**
     * Refresh layout after adding/removing messages.
     */
    private void refreshLayout() {
        messagesContainer.layout(true, true);
        // Use the current client width so wrapped Text widgets compute their
        // height correctly; this avoids extremely tall content and excessive
        // scrolling to reach the end.
        int clientWidth = scroller.getClientArea().width;
        if (clientWidth <= 0) {
            // Fallback for early calls before the control is laid out
            clientWidth = 600;
        }
        scroller.setMinSize(messagesContainer.computeSize(clientWidth, SWT.DEFAULT));
        messagesContainer.layout();
    }

    /**
     * Check if disposed.
     */
    public boolean isDisposed() {
        return scroller.isDisposed();
    }

    /**
     * Dispose resources.
     */
    public void dispose() {
        if (!scroller.isDisposed()) {
            scroller.dispose();
        }
    }
}
