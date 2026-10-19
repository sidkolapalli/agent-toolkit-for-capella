package org.polarsys.capella.ai.presentation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Properties;
import org.polarsys.capella.ai.llm.LLMService;

/** Workspace-local history and draft persistence, separated by the model's native URI. */
public final class ConversationStore {
    private final Path directory;
    public ConversationStore(Path directory) { this.directory = directory; }

    public ConversationState load(String modelUri) throws IOException {
        Path file = file(modelUri);
        ConversationState state = new ConversationState();
        if (!Files.exists(file)) return state;
        if (Files.size(file) > 4000000) throw new IOException("Saved conversation exceeds its size limit");
        Properties values = new Properties();
        try (var input = Files.newInputStream(file)) { values.loadFromXML(input); }
        if (!modelUri.equals(values.getProperty("model"))) throw new IOException("Saved conversation belongs to another model");
        state.draft(text(values, "draft"));
        state.taskPlan(text(values, "plan"));
        try { state.codexThreadId(values.getProperty("codexThreadId", "")); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid saved Codex thread identity", invalid); }
        state.codexPrivacyMode(Boolean.parseBoolean(values.getProperty("codexPrivacyMode", "false")));
        int count = count(values, "entries", 100);
        for (int index = 0; index < count; index++) {
            state.append(values.getProperty("entry." + index + ".role", "Capella AI"),
                text(values, "entry." + index + ".text"));
        }
        var history = new ArrayList<LLMService.Message>();
        for (int index = 0; index < count(values, "history", 24); index++) {
            String role = values.getProperty("history." + index + ".role", "user");
            String text = text(values, "history." + index + ".text");
            if ("USER".equals(role)) history.add(LLMService.Message.user(text));
            else if ("ASSISTANT".equals(role)) history.add(LLMService.Message.assistant(text));
        }
        state.restoreHistory(history);
        return state;
    }

    public void save(String modelUri, ConversationState state) throws IOException {
        Files.createDirectories(directory);
        Properties values = new Properties();
        values.setProperty("format", "2");
        values.setProperty("model", modelUri);
        values.setProperty("draft", encode(state.draft()));
        values.setProperty("plan", encode(state.taskPlan()));
        values.setProperty("codexThreadId", state.codexThreadId());
        values.setProperty("codexPrivacyMode", Boolean.toString(state.codexPrivacyMode()));
        values.setProperty("entries", Integer.toString(state.entries().size()));
        for (int index = 0; index < state.entries().size(); index++) {
            var entry = state.entries().get(index);
            values.setProperty("entry." + index + ".role", entry.role());
            values.setProperty("entry." + index + ".text", encode(entry.text()));
        }
        values.setProperty("history", Integer.toString(state.history().size()));
        for (int index = 0; index < state.history().size(); index++) {
            var entry = state.history().get(index);
            values.setProperty("history." + index + ".role", entry.getRole().name());
            values.setProperty("history." + index + ".text", encode(entry.getContent()));
        }
        Path file = file(modelUri);
        Path temporary = Files.createTempFile(directory, "conversation-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) {
                values.storeToXML(output, "Capella AI local conversation; no execution approvals", StandardCharsets.UTF_8);
            }
            try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    private Path file(String modelUri) {
        if (modelUri == null || modelUri.isBlank()) throw new IllegalArgumentException("A model URI is required");
        try {
            return directory.resolve(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(modelUri.getBytes(StandardCharsets.UTF_8))) + ".xml");
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static int count(Properties values, String name, int max) throws IOException {
        try {
            int count = Integer.parseInt(values.getProperty(name, "0"));
            if (count < 0 || count > max) throw new IOException("Invalid saved conversation count");
            return count;
        } catch (NumberFormatException invalid) { throw new IOException("Invalid saved conversation count", invalid); }
    }

    private static String encode(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(Properties values, String key) throws IOException {
        String value = values.getProperty(key, "");
        if (!"2".equals(values.getProperty("format"))) return value;
        try { return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid saved conversation text", invalid); }
    }
}
