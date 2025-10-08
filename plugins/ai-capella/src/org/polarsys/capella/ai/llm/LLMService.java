package org.polarsys.capella.ai.llm;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.polarsys.capella.ai.preferences.LLMPreferences;
import org.polarsys.capella.ai.llm.SimpleJson;

/** Minimal LLM client router supporting OpenAI-compatible and Anthropic APIs. */
public final class LLMService {
    private static final LLMService INSTANCE = new LLMService();
    private final HttpClient http;
    private final Map<String, ModelCapabilities> capabilitiesCache = new ConcurrentHashMap<>();

    /** Model capabilities (lightweight, heuristic-based) */
    private static class ModelCapabilities {
        final boolean useResponsesEndpoint;   // Use /v1/responses instead of Chat Completions
        final boolean supportsTemperature;    // Some reasoning models ignore/forbid temperature

        ModelCapabilities(boolean useResponsesEndpoint, boolean supportsTemperature) {
            this.useResponsesEndpoint = useResponsesEndpoint;
            this.supportsTemperature = supportsTemperature;
        }
    }

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
        String model = LLMPreferences.getModel();
        ModelCapabilities caps = getModelCapabilities(model);
        try {
            if (caps.useResponsesEndpoint) {
                return sendOpenAIResponses(messages, caps);
            }
            return sendOpenAIChat(messages, caps);
        } catch (RuntimeException ex) {
            String body = ex.getMessage();
            // Fallback: if Chat Completions fails for a model that may require Responses, retry once
            boolean retryResponses = !caps.useResponsesEndpoint && body != null && body.contains("OpenAI error:");
            if (retryResponses) {
                return sendOpenAIResponses(messages, caps);
            }
            throw ex;
        }
    }

    private String sendOpenAIChat(List<Message> messages, ModelCapabilities caps) throws Exception {
        String api = normalizeBase(LLMPreferences.getBaseUrl()) + "/v1/chat/completions";
        String model = LLMPreferences.getModel();
        int maxT = LLMPreferences.getMaxTokens();
        double temp = LLMPreferences.getTemperature();
        String key = LLMPreferences.getApiKey();

        String payloadMessages = buildChatMessagesJson(messages);
        // Chat Completions: valid token param is max_tokens
        StringBuilder body = new StringBuilder();
        body.append('{')
            .append("\"model\":\"").append(esc(model)).append("\",")
            .append("\"messages\":").append(payloadMessages).append(',')
            .append("\"max_tokens\":").append(maxT);
        if (caps.supportsTemperature) {
            body.append(',').append("\"temperature\":").append(temp);
        }
        body.append('}');

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(api))
            .timeout(Duration.ofSeconds(LLMPreferences.getTimeoutSec()))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + key)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) throw new RuntimeException("OpenAI error: " + resp.statusCode() + ": " + resp.body());
        String s = resp.body();
        try {
            Object parsed = SimpleJson.parse(s);
            if (parsed instanceof Map<?, ?> map) {
                Object choicesObj = map.get("choices");
                if (choicesObj instanceof List<?> choices && !choices.isEmpty()) {
                    Object first = choices.get(0);
                    if (first instanceof Map<?, ?> choice) {
                        Object msg = choice.get("message");
                        if (msg instanceof Map<?, ?> message) {
                            Object content = message.get("content");
                            if (content instanceof String str) return str;
                            if (content instanceof List<?> blocks) {
                                StringBuilder sb = new StringBuilder();
                                for (Object block : blocks) {
                                    if (block instanceof Map<?, ?> b && "text".equals(b.get("type"))) {
                                        Object text = b.get("text");
                                        if (text instanceof String) sb.append((String) text);
                                    }
                                }
                                if (sb.length() > 0) return sb.toString();
                            }
                        }
                    }
                }
            }
        } catch (Exception ignore) {}
        return s;
    }

    private String sendOpenAIResponses(List<Message> messages, ModelCapabilities caps) throws Exception {
        String api = normalizeBase(LLMPreferences.getBaseUrl()) + "/v1/responses";
        String model = LLMPreferences.getModel();
        int maxT = LLMPreferences.getMaxTokens();
        double temp = LLMPreferences.getTemperature();
        String key = LLMPreferences.getApiKey();

        // Build a simple textual transcript for input
        String input = buildResponsesInput(messages);
        StringBuilder body = new StringBuilder();
        body.append('{')
            .append("\"model\":\"").append(esc(model)).append("\",")
            .append("\"input\":\"").append(esc(input)).append("\",")
            // Responses API: use max_output_tokens
            .append("\"max_output_tokens\":").append(maxT);
        if (caps.supportsTemperature) {
            body.append(',').append("\"temperature\":").append(temp);
        }
        body.append('}');

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(api))
            .timeout(Duration.ofSeconds(LLMPreferences.getTimeoutSec()))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + key)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) throw new RuntimeException("OpenAI error: " + resp.statusCode() + ": " + resp.body());
        String s = resp.body();
        try {
            Object parsed = SimpleJson.parse(s);
            if (parsed instanceof Map<?, ?> map) {
                // output_text convenience field present in SDKs/aggregated outputs
                Object outText = map.get("output_text");
                if (outText instanceof String str && !str.isEmpty()) return str;
                // Generic output array
                Object output = map.get("output");
                if (output instanceof List<?> items && !items.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (Object item : items) {
                        if (item instanceof Map<?, ?> itm) {
                            Object type = itm.get("type");
                            if ("output_text".equals(type)) {
                                Object text = itm.get("text");
                                if (text instanceof String t) sb.append(t);
                            }
                        }
                    }
                    if (sb.length() > 0) return sb.toString();
                }
            }
        } catch (Exception ignore) {}
        return s;
    }

    private String buildChatMessagesJson(List<Message> messages) {
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
        return payloadMessages.toString();
    }

    private String buildResponsesInput(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        // Put system instructions first if present
        for (Message msg : messages) {
            if (msg.role == Message.Role.SYSTEM && msg.content != null && !msg.content.isBlank()) {
                sb.append("System:\n").append(msg.content).append("\n\n");
            }
        }
        for (Message msg : messages) {
            if (msg.role == Message.Role.SYSTEM) continue;
            sb.append(capitalize(msg.role.name().toLowerCase())).append(':').append('\n')
              .append(msg.content == null ? "" : msg.content).append("\n\n");
        }
        return sb.toString().trim();
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

    private StreamHandle streamOpenAI(List<Message> messages, StreamListener listener) throws Exception {
        String model = LLMPreferences.getModel();
        ModelCapabilities caps = getModelCapabilities(model);
        if (caps.useResponsesEndpoint) {
            return streamOpenAIResponses(messages, listener, caps);
        }
        String api = normalizeBase(LLMPreferences.getBaseUrl()) + "/v1/chat/completions";
        int maxT = LLMPreferences.getMaxTokens();
        double temp = LLMPreferences.getTemperature();
        String key = LLMPreferences.getApiKey();
        String payloadMessages = buildChatMessagesJson(messages);
        StringBuilder body = new StringBuilder();
        body.append('{')
            .append("\"model\":\"").append(esc(model)).append("\",")
            .append("\"messages\":").append(payloadMessages).append(',')
            .append("\"max_tokens\":").append(maxT).append(',')
            .append("\"stream\":true");
        if (caps.supportsTemperature) {
            body.append(',').append("\"temperature\":").append(temp);
        }
        body.append('}');
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(api))
            .timeout(Duration.ofSeconds(LLMPreferences.getTimeoutSec()))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + key)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
            .build();
        return streamSse(req, listener, this::extractOpenAIDelta);
    }

    private StreamHandle streamOpenAIResponses(List<Message> messages, StreamListener listener, ModelCapabilities caps) throws Exception {
        String api = normalizeBase(LLMPreferences.getBaseUrl()) + "/v1/responses";
        String model = LLMPreferences.getModel();
        int maxT = LLMPreferences.getMaxTokens();
        double temp = LLMPreferences.getTemperature();
        String key = LLMPreferences.getApiKey();

        String input = buildResponsesInput(messages);
        StringBuilder body = new StringBuilder();
        body.append('{')
            .append("\"model\":\"").append(esc(model)).append("\",")
            .append("\"input\":\"").append(esc(input)).append("\",")
            .append("\"max_output_tokens\":").append(maxT).append(',')
            .append("\"stream\":true");
        if (caps.supportsTemperature) {
            body.append(',').append("\"temperature\":").append(temp);
        }
        body.append('}');

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(api))
            .timeout(Duration.ofSeconds(LLMPreferences.getTimeoutSec()))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + key)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
            .build();
        return streamSse(req, listener, this::extractResponsesDelta);
    }

    private StreamHandle streamAnthropic(List<Message> messages, StreamListener listener) throws Exception {
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
            "\"stream\":true," +
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
        return streamSse(req, listener, this::extractAnthropicDelta);
    }

    private StreamHandle streamSse(HttpRequest request, StreamListener listener, DeltaExtractor extractor) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        CompletableFuture<HttpResponse<InputStream>> responseFuture = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        CompletableFuture<String> completion = new CompletableFuture<>();
        responseFuture.whenComplete((response, error) -> {
            if (error != null) {
                completion.completeExceptionally(error);
                return;
            }
            if (response == null) {
                completion.completeExceptionally(new IllegalStateException("No response received"));
                return;
            }
            if (response.statusCode() >= 400) {
                try (InputStream body = response.body()) {
                    String message = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                    completion.completeExceptionally(new RuntimeException("LLM error: " + response.statusCode() + ": " + message));
                } catch (Exception ex) {
                    completion.completeExceptionally(ex);
                }
                return;
            }
            try (InputStream body = response.body();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                StringBuilder aggregate = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancelled.get() || listener.isCancelled()) {
                        completion.completeExceptionally(new CancellationException("stream cancelled"));
                        return;
                    }
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String payload = line.substring(5).trim();
                    if (payload.isEmpty()) {
                        continue;
                    }
                    if ("[DONE]".equals(payload)) {
                        break;
                    }
                    String delta = extractor.extract(payload);
                    if (delta != null && !delta.isBlank()) {
                        aggregate.append(delta);
                        try {
                            listener.onChunk(delta);
                        } catch (Exception ignore) {
                            // UI listener best-effort
                        }
                    }
                }
                completion.complete(aggregate.toString());
            } catch (CancellationException ex) {
                completion.completeExceptionally(ex);
            } catch (Exception ex) {
                completion.completeExceptionally(ex);
            }
        });

        Runnable cancel = () -> {
            cancelled.set(true);
            responseFuture.cancel(true);
        };
        return new StreamHandle(completion, cancel);
    }

    private String extractOpenAIDelta(String payload) {
        try {
            Object parsed = SimpleJson.parse(payload);
            if (parsed instanceof Map<?, ?> map) {
                Object choicesObj = map.get("choices");
                if (choicesObj instanceof List<?> choices && !choices.isEmpty()) {
                    Object first = choices.get(0);
                    if (first instanceof Map<?, ?> choice) {
                        Object deltaObj = choice.get("delta");
                        if (deltaObj instanceof Map<?, ?> delta) {
                            Object content = delta.get("content");
                            if (content instanceof String str) {
                                return str;
                            }
                            if (content instanceof List<?> blocks) {
                                StringBuilder sb = new StringBuilder();
                                for (Object block : blocks) {
                                    if (block instanceof Map<?, ?> b && "text".equals(b.get("type"))) {
                                        Object text = b.get("text");
                                        if (text instanceof String) {
                                            sb.append((String) text);
                                        }
                                    }
                                }
                                return sb.toString();
                            }
                        }
                    }
                }
            }
        } catch (Exception ignore) {
        }
        return "";
    }

    private String extractAnthropicDelta(String payload) {
        try {
            Object parsed = SimpleJson.parse(payload);
            if (parsed instanceof Map<?, ?> event) {
                Object type = event.get("type");
                if ("content_block_delta".equals(type)) {
                    Object deltaObj = event.get("delta");
                    if (deltaObj instanceof Map<?, ?> delta) {
                        Object text = delta.get("text");
                        if (text instanceof String) {
                            return (String) text;
                        }
                    }
                } else if ("message_delta".equals(type)) {
                    Object deltaObj = event.get("delta");
                    if (deltaObj instanceof Map<?, ?> delta) {
                        Object text = delta.get("text");
                        if (text instanceof String) {
                            return (String) text;
                        }
                    }
                }
            }
        } catch (Exception ignore) {
        }
        return "";
    }

    private String extractResponsesDelta(String payload) {
        try {
            Object parsed = SimpleJson.parse(payload);
            if (parsed instanceof Map<?, ?> event) {
                Object type = event.get("type");
                if (type instanceof String t) {
                    // Stream text deltas from Responses API
                    if ("response.output_text.delta".equals(t) || "response.refusal.delta".equals(t)) {
                        Object delta = event.get("delta");
                        if (delta instanceof String) {
                            return (String) delta;
                        }
                    }
                }
            }
        } catch (Exception ignore) {
        }
        return "";
    }

    /**
     * Detects model capabilities using a hybrid approach:
     * 1. Check cache for previously detected capabilities
     * 2. Use pattern matching for known model families
     * 3. Cache result for future requests
     */
    private ModelCapabilities getModelCapabilities(String model) {
        // Check cache first
        ModelCapabilities cached = capabilitiesCache.get(model);
        if (cached != null) {
            return cached;
        }

        // Pattern-based detection for known families
        boolean useResponses = false;
        boolean supportsTemperature = true;

        // GPT-5, GPT-4.1 and o-series (reasoning) are Responses-first in OpenAI docs
        if (model.startsWith("gpt-5") || model.startsWith("gpt-4.1") || model.startsWith("o")) {
            useResponses = true;
            supportsTemperature = false; // reasoning models typically ignore temperature
        } else if (model.startsWith("gpt-4o") || model.startsWith("gpt-4") || model.startsWith("gpt-3")) {
            useResponses = false; // Chat Completions compatible
            supportsTemperature = true;
        } else {
            // Default to Chat Completions but allow fallback if needed
            useResponses = false;
            supportsTemperature = true;
        }

        ModelCapabilities capabilities = new ModelCapabilities(useResponses, supportsTemperature);
        capabilitiesCache.put(model, capabilities);
        return capabilities;
    }

    @FunctionalInterface
    private interface DeltaExtractor {
        String extract(String payload);
    }

    private static String normalizeBase(String b) { return b.endsWith("/") ? b.substring(0, b.length()-1) : b; }
    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + (s.length() > 1 ? s.substring(1) : "");
    }
    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\b", "\\b")
                .replace("\f", "\\f");
    }

    public interface StreamListener {
        boolean isCancelled();
        void onChunk(String text);
    }

    public static final class StreamHandle {
        private final CompletableFuture<String> completion;
        private final Runnable cancel;

        StreamHandle(CompletableFuture<String> completion, Runnable cancel) {
            this.completion = completion;
            this.cancel = cancel;
        }

        public CompletableFuture<String> completion() {
            return completion;
        }

        public void cancel() {
            cancel.run();
        }
    }

    public StreamHandle stream(List<Message> messages, StreamListener listener) throws Exception {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("At least one message is required");
        }
        if (listener == null) {
            throw new IllegalArgumentException("Stream listener required");
        }
        String provider = LLMPreferences.getProvider();
        if (provider.equalsIgnoreCase("anthropic")) {
            return streamAnthropic(messages, listener);
        }
        return streamOpenAI(messages, listener);
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
