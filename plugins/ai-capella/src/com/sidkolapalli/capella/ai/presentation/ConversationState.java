package com.sidkolapalli.capella.ai.presentation;

import com.sidkolapalli.capella.ai.llm.LLMService;

import java.util.ArrayList;
import java.util.List;

/** Local conversation data. No live model objects or execution approvals are stored. */
public final class ConversationState {
    public record Entry(String role, String text) {}

    private final List<Entry> entries = new ArrayList<>();
    private final List<LLMService.Message> history = new ArrayList<>();
    private String draft = "";
    private String taskPlan = "";
    private String codexThreadId = "";
    private boolean codexPrivacyMode;

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public List<LLMService.Message> history() {
        return List.copyOf(history);
    }

    public String draft() {
        return draft;
    }

    public void draft(String value) {
        draft = bounded(value, 20000);
    }

    public String taskPlan() {
        return taskPlan;
    }

    public void taskPlan(String value) {
        taskPlan = bounded(value, 6000);
    }

    public String codexThreadId() {
        return codexThreadId;
    }

    public boolean codexPrivacyMode() {
        return codexPrivacyMode;
    }

    public void codexPrivacyMode(boolean value) {
        codexPrivacyMode = value;
    }

    /** Token dictionaries are memory-only, so their remote history cannot be resumed safely. */
    public String resumableCodexThreadId(boolean privacyEnabled) {
        return privacyEnabled || codexPrivacyMode ? "" : codexThreadId;
    }

    public void codexThreadId(String value) {
        if (value == null) value = "";
        if (!value.isEmpty() && !value.matches("[A-Za-z0-9_-]{1,200}")) {
            throw new IllegalArgumentException("Invalid saved Codex thread identity");
        }
        codexThreadId = value;
    }

    public void append(String role, String text) {
        entries.add(new Entry(bounded(role, 120), bounded(text, 100000)));
        while (entries.size() > 100 || totalText() > 400000) entries.remove(0);
    }

    public void addReply(String prompt, String reply) {
        history.add(LLMService.Message.user(bounded(prompt, 20000)));
        history.add(LLMService.Message.assistant(bounded(reply, 100000)));
        trimHistory();
    }

    public void addOutcome(String outcome) {
        history.add(LLMService.Message.user(bounded(outcome, 8000)));
        trimHistory();
    }

    public void restoreHistory(List<LLMService.Message> values) {
        history.clear();
        for (var message : values) {
            if (message != null
                    && (message.getRole() == LLMService.Message.Role.USER
                            || message.getRole() == LLMService.Message.Role.ASSISTANT)) {
                history.add(
                        message.getRole() == LLMService.Message.Role.ASSISTANT
                                ? LLMService.Message.assistant(
                                        bounded(message.getContent(), 100000))
                                : LLMService.Message.user(bounded(message.getContent(), 100000)));
            }
        }
        trimHistory();
    }

    private void trimHistory() {
        while (history.size() > 24
                || history.stream().mapToInt(m -> m.getContent().length()).sum() > 120000) {
            history.remove(0);
            while (!history.isEmpty() && history.get(0).getRole() != LLMService.Message.Role.USER)
                history.remove(0);
        }
    }

    private int totalText() {
        return entries.stream().mapToInt(e -> e.text().length()).sum();
    }

    private static String bounded(String value, int limit) {
        if (value == null) return "";
        if (value.length() <= limit) return value;
        int end = limit;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end) + "\n[Earlier content was shortened.]";
    }
}
