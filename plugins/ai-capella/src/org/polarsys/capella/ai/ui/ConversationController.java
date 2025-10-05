package org.polarsys.capella.ai.ui;

import java.util.ArrayList;
import java.util.List;

import org.polarsys.capella.ai.llm.LLMService;
import org.polarsys.capella.ai.llm.ToolRouter.ToolPlan;

/**
 * Controller managing conversation state and message history.
 * Coordinates between UI (ConversationPanel) and LLM service.
 */
public class ConversationController {

    private final ConversationPanel panel;
    private final List<LLMService.Message> history;
    private static final int MAX_HISTORY_MESSAGES = 12;

    public ConversationController(ConversationPanel panel) {
        this.panel = panel;
        this.history = new ArrayList<>();
    }

    /**
     * Add a user message to conversation.
     */
    public void addUserMessage(String text) {
        panel.addUserMessage(text);
        history.add(LLMService.Message.user(text));
        trimHistory();
    }

    /**
     * Add an assistant message to conversation.
     */
    public void addAssistantMessage(String text) {
        panel.addAssistantMessage(text);
        history.add(LLMService.Message.assistant(text));
        trimHistory();
    }

    /**
     * Start streaming an assistant response.
     */
    public void startStreaming() {
        panel.startStreamingResponse();
    }

    /**
     * Append text to streaming response.
     */
    public void appendStreaming(String delta) {
        panel.appendStreamingText(delta);
    }

    /**
     * Finish streaming and record message in history.
     */
    public void finishStreaming(String fullText) {
        panel.finishStreaming();
        if (fullText != null && !fullText.isBlank()) {
            history.add(LLMService.Message.assistant(fullText));
            trimHistory();
        }
    }

    /**
     * Show a plan preview inline in conversation.
     */
    public PlanPreviewBox showPlanPreview(ToolPlan plan, List<String> summaryLines) {
        return panel.addPlanPreview(plan, summaryLines);
    }

    /**
     * Add error message.
     */
    public void addError(String error) {
        panel.addErrorMessage(error);
        history.add(LLMService.Message.assistant("ERROR: " + error));
        trimHistory();
    }

    /**
     * Add system message (e.g., "Generation cancelled").
     */
    public void addSystemMessage(String text) {
        panel.addSystemMessage(text);
    }

    /**
     * Get conversation history snapshot for LLM context.
     */
    public List<LLMService.Message> getHistory() {
        return new ArrayList<>(history);
    }

    /**
     * Clear conversation and reset history.
     */
    public void clear() {
        panel.clear();
        history.clear();
    }

    /**
     * Trim history to max size, keeping most recent messages.
     */
    private void trimHistory() {
        while (history.size() > MAX_HISTORY_MESSAGES) {
            history.remove(0);
        }
    }

    /**
     * Check if panel is disposed.
     */
    public boolean isDisposed() {
        return panel.isDisposed();
    }
}
