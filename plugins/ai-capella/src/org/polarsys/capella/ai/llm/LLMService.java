package org.polarsys.capella.ai.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.polarsys.capella.ai.preferences.LLMPreferences;

/** Minimal LLM client router supporting OpenAI-compatible and Anthropic APIs. */
public final class LLMService {
    private static final LLMService INSTANCE = new LLMService();
    private final HttpClient http;

    private LLMService() {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public static LLMService get() { return INSTANCE; }

    public String send(String prompt) throws Exception {
        return send(List.of(Message.user(prompt)));
    }

    public String send(List<Message> messages) throws Exception {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("At least one message is required");
        }
        String provider = LLMPreferences.getProvider();
        if (provider.equalsIgnoreCase("anthropic")) {
            return sendAnthropic(messages);
        }
        return sendOpenAI(messages);
    }

    public String testConnection() throws Exception {
        String provider = LLMPreferences.getProvider();
        if (provider.equalsIgnoreCase("anthropic")) {
            // Anthropic expects anthropic-version; we just return provider info
            return "Anthropic configured: " + LLMPreferences.getModel();
        }
        return "OpenAI-compatible configured: " + LLMPreferences.getModel();
    }

    private String sendOpenAI(List<Message> messages) throws Exception {
        String api = normalizeBase(LLMPreferences.getBaseUrl()) + "/v1/chat/completions";
        String model = LLMPreferences.getModel();
        int maxT = LLMPreferences.getMaxTokens();
        double temp = LLMPreferences.getTemperature();
        String key = LLMPreferences.getApiKey();
        StringBuilder payloadMessages = new StringBuilder("[");
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (i > 0) {
                payloadMessages.append(',');
            }
            payloadMessages.append('{')
                .append("\"role\":\"").append(msg.role.apiRole()).append("\",")
                .append("\"content\":\"").append(esc(msg.content)).append("\"}");
        }
        payloadMessages.append(']');
        String body = "{" +
            "\"model\":\"" + esc(model) + "\"," +
            "\"messages\":" + payloadMessages + ',' +
            "\"temperature\":" + temp + "," +
            "\"max_tokens\":" + maxT +
        "}";
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(api))
            .timeout(Duration.ofSeconds(LLMPreferences.getTimeoutSec()))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + key)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) throw new RuntimeException("OpenAI error: " + resp.statusCode() + ": " + resp.body());
        // Very light parsing: find first "content":"..."
        String s = resp.body();
        int i = s.indexOf("\"content\":");
        if (i >= 0) {
            int q1 = s.indexOf('"', i + 10);
            int q2 = s.indexOf('"', q1 + 1);
            if (q1 >= 0 && q2 > q1) return s.substring(q1 + 1, q2);
        }
        return s;
    }

    private String sendAnthropic(List<Message> messages) throws Exception {
        String api = normalizeBase(LLMPreferences.getBaseUrl()) + "/v1/messages";
        String model = LLMPreferences.getModel();
        int maxT = LLMPreferences.getMaxTokens();
        double temp = LLMPreferences.getTemperature();
        String key = LLMPreferences.getApiKey();
        StringBuilder system = new StringBuilder();
        StringBuilder payloadMessages = new StringBuilder("[");
        boolean first = true;
        for (Message msg : messages) {
            if (msg.role == Message.Role.SYSTEM) {
                if (system.length() > 0) {
                    system.append("\n\n");
                }
                system.append(msg.content);
                continue;
            }
            if (!first) {
                payloadMessages.append(',');
            }
            first = false;
            payloadMessages.append('{')
                .append("\"role\":\"").append(msg.role.anthropicRole()).append("\",\"content\":[{\"type\":\"text\",\"text\":\"")
                .append(esc(msg.content))
                .append("\"}]}");
        }
        if (first) {
            throw new IllegalArgumentException("Anthropic payload requires at least one user or assistant message");
        }
        payloadMessages.append(']');
        String systemField = system.length() > 0 ? "\"system\":\"" + esc(system.toString()) + "\"," : "";
        String body = "{" +
            "\"model\":\"" + esc(model) + "\"," +
            "\"max_tokens\":" + maxT + "," +
            "\"temperature\":" + temp + "," +
            systemField +
            "\"messages\":" + payloadMessages.toString() +
        "}";
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(api))
            .timeout(Duration.ofSeconds(LLMPreferences.getTimeoutSec()))
            .header("Content-Type", "application/json")
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) throw new RuntimeException("Anthropic error: " + resp.statusCode() + ": " + resp.body());
        // Light parse: find "text":"..." from content
        String s = resp.body();
        int i = s.indexOf("\"text\":");
        if (i >= 0) {
            int q1 = s.indexOf('"', i + 7);
            int q2 = s.indexOf('"', q1 + 1);
            if (q1 >= 0 && q2 > q1) return s.substring(q1 + 1, q2);
        }
        return s;
    }

    private static String normalizeBase(String b) { return b.endsWith("/") ? b.substring(0, b.length()-1) : b; }
    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static final class Message {
        public enum Role {
            SYSTEM("system"),
            USER("user"),
            ASSISTANT("assistant");

            private final String apiRole;

            Role(String apiRole) {
                this.apiRole = apiRole;
            }

            String apiRole() {
                return apiRole;
            }

            String anthropicRole() {
                return this == ASSISTANT ? "assistant" : "user";
            }
        }

        private final Role role;
        private final String content;

        private Message(Role role, String content) {
            this.role = role;
            this.content = content == null ? "" : content;
        }

        public static Message system(String content) {
            return new Message(Role.SYSTEM, content);
        }

        public static Message user(String content) {
            return new Message(Role.USER, content);
        }

        public static Message assistant(String content) {
            return new Message(Role.ASSISTANT, content);
        }

        public Role getRole() {
            return role;
        }

        public String getContent() {
            return content;
        }
    }
}
