package com.sidkolapalli.capella.ai.llm;

import com.sidkolapalli.capella.ai.preferences.LLMPreferences;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Minimal LLM client router supporting OpenAI-compatible and Anthropic APIs. */
public final class LLMService {

    private static final LLMService INSTANCE = new LLMService();
    private static final int MAX_RETRIES = 3;
    private static final long BASE_BACKOFF_MS = 1000L;
    private final HttpClient http;
    private final Map<String, ModelCapabilities> capabilitiesCache = new ConcurrentHashMap<>();

    /** Model capabilities (lightweight, heuristic-based) */
    private static class ModelCapabilities {

        final boolean useResponsesEndpoint; // Use /v1/responses instead of Chat Completions
        final boolean supportsTemperature; // Some reasoning models ignore/forbid temperature

        ModelCapabilities(boolean useResponsesEndpoint, boolean supportsTemperature) {
            this.useResponsesEndpoint = useResponsesEndpoint;
            this.supportsTemperature = supportsTemperature;
        }
    }

    private LLMService() {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public static LLMService get() {
        return INSTANCE;
    }

    public String send(String prompt) throws Exception {
        return send(List.of(Message.user(prompt)));
    }

    public String send(List<Message> messages) throws Exception {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("At least one message is required");
        }
        validateConfiguration(messages);
        String provider = LLMPreferences.getProvider();
        if (provider.equalsIgnoreCase("anthropic")) {
            return sendAnthropic(messages);
        }
        return sendOpenAI(messages);
    }

    public String testConnection() throws Exception {
        String provider = LLMPreferences.getProvider();
        String reply = send("Reply with OK.");
        if (reply == null || reply.isBlank()) {
            throw new IllegalStateException(
                    "The provider returned no text for the connection test");
        }
        return provider + " responded using " + LLMPreferences.getModel();
    }

    private String sendOpenAI(List<Message> messages) throws Exception {
        String model = LLMPreferences.getModel();
        ModelCapabilities caps = getModelCapabilities(model);
        if (caps.useResponsesEndpoint) return sendOpenAIResponses(messages, caps);
        // Authentication, quota and invalid requests must not trigger a second endpoint request.
        return sendOpenAIChat(messages, caps);
    }

    private String sendOpenAIChat(List<Message> messages, ModelCapabilities caps) throws Exception {
        String api = normalizeBase(LLMPreferences.getBaseUrl()) + "/v1/chat/completions";
        String model = LLMPreferences.getModel();
        int maxT = LLMPreferences.getMaxTokens();
        double temp = LLMPreferences.getTemperature();
        String key = LLMPreferences.getApiKey();
        long start = System.currentTimeMillis();
        String provider = LLMPreferences.getProvider();
        String msgDump = dumpMessages(messages);

        String payloadMessages = buildChatMessagesJson(messages);
        // Chat Completions: valid token param is max_tokens
        StringBuilder body = new StringBuilder();
        body.append('{')
                .append("\"model\":\"")
                .append(esc(model))
                .append("\",")
                .append("\"messages\":")
                .append(payloadMessages)
                .append(',')
                .append("\"max_tokens\":")
                .append(maxT);
        if (caps.supportsTemperature) {
            body.append(',').append("\"temperature\":").append(temp);
        }
        body.append('}');

        HttpRequest req =
                HttpRequest.newBuilder()
                        .uri(URI.create(api))
                        .timeout(Duration.ofSeconds(LLMPreferences.getTimeoutSec()))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + key)
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        body.toString(), StandardCharsets.UTF_8))
                        .build();
        HttpResponse<String> resp = sendWithRetry(req, provider, "openai-chat", model);
        if (resp.statusCode() >= 400) {
            tryLogConversation(
                    provider,
                    "openai-chat",
                    model,
                    msgDump,
                    "",
                    false,
                    "http=" + resp.statusCode(),
                    start);
            throw providerFailure(resp.statusCode(), resp.body());
        }
        String s = resp.body();
        String result = parseChatBody(s);
        tryLogConversation(provider, "openai-chat", model, msgDump, result, false, "ok", start);
        return result;
    }

    private String sendOpenAIResponses(List<Message> messages, ModelCapabilities caps)
            throws Exception {
        String api = normalizeBase(LLMPreferences.getBaseUrl()) + "/v1/responses";
        String model = LLMPreferences.getModel();
        int maxT = LLMPreferences.getMaxTokens();
        double temp = LLMPreferences.getTemperature();
        String key = LLMPreferences.getApiKey();
        long start = System.currentTimeMillis();
        String provider = LLMPreferences.getProvider();
        String msgDump = dumpMessages(messages);

        String body =
                buildResponsesBody(messages, model, maxT, temp, caps.supportsTemperature, false);

        HttpRequest req =
                HttpRequest.newBuilder()
                        .uri(URI.create(api))
                        .timeout(Duration.ofSeconds(LLMPreferences.getTimeoutSec()))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + key)
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
        HttpResponse<String> resp = sendWithRetry(req, provider, "openai-responses", model);
        if (resp.statusCode() >= 400) {
            tryLogConversation(
                    provider,
                    "openai-responses",
                    model,
                    msgDump,
                    "",
                    false,
                    "http=" + resp.statusCode(),
                    start);
            throw providerFailure(resp.statusCode(), resp.body());
        }
        String s = resp.body();
        String result;
        try {
            result = parseResponsesBody(s);
        } catch (RuntimeException ex) {
            tryLogConversation(
                    provider,
                    "openai-responses",
                    model,
                    msgDump,
                    "",
                    false,
                    "error:" + ex.getMessage(),
                    start);
            throw ex;
        }
        tryLogConversation(
                provider, "openai-responses", model, msgDump, result, false, "ok", start);
        return result;
    }

    /** Decode REST output messages before returning text to the code router. */
    private static String parseResponsesBody(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("OpenAI Responses returned an empty response body");
        }

        Object parsed;
        try {
            parsed = SimpleJson.parse(body);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("OpenAI Responses returned invalid JSON", ex);
        }
        return parseResponsesObject(parsed);
    }

    private static String parseResponsesObject(Object parsed) {
        if (!(parsed instanceof Map<?, ?> response)) {
            throw new IllegalStateException("OpenAI Responses expected a JSON object");
        }
        Object error = response.get("error");
        if (error != null) {
            Object detail =
                    error instanceof Map<?, ?> errorFields ? errorFields.get("message") : error;
            throw new IllegalStateException(
                    "OpenAI Responses failed" + (detail == null ? "" : ": " + detail));
        }

        Object status = response.get("status");
        if (status != null && !"completed".equals(status)) {
            Object details = response.get("incomplete_details");
            Object reason = details instanceof Map<?, ?> fields ? fields.get("reason") : null;
            throw new IllegalStateException(
                    "OpenAI Responses did not complete (status="
                            + status
                            + (reason == null ? "" : ", reason=" + reason)
                            + ")");
        }

        StringBuilder text = new StringBuilder();
        Object output = response.get("output");
        if (output != null) {
            if (!(output instanceof List<?> items)) {
                throw new IllegalStateException("OpenAI Responses output must be an array");
            }
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> message)) {
                    throw new IllegalStateException(
                            "OpenAI Responses output item must be an object");
                }
                if (!"message".equals(message.get("type"))) {
                    continue;
                }
                Object messageStatus = message.get("status");
                if (messageStatus != null && !"completed".equals(messageStatus)) {
                    throw new IllegalStateException(
                            "OpenAI Responses contains an incomplete message");
                }
                if (!(message.get("content") instanceof List<?> content)) {
                    throw new IllegalStateException(
                            "OpenAI Responses message content must be an array");
                }
                for (Object part : content) {
                    if (!(part instanceof Map<?, ?> block)) {
                        throw new IllegalStateException(
                                "OpenAI Responses content item must be an object");
                    }
                    if ("refusal".equals(block.get("type"))) {
                        Object refusal = block.get("refusal");
                        throw new IllegalStateException(
                                "OpenAI refused the request"
                                        + (refusal instanceof String reason && !reason.isBlank()
                                                ? ": " + reason
                                                : ""));
                    }
                    if ("output_text".equals(block.get("type"))) {
                        if (!(block.get("text") instanceof String value)) {
                            throw new IllegalStateException(
                                    "OpenAI Responses output text must be a string");
                        }
                        text.append(value);
                    }
                }
            }
        }
        if (!text.toString().isBlank()) {
            return text.toString();
        }
        // Keep compatibility with SDK/proxy responses that expose aggregated text.
        if (response.get("output_text") instanceof String value && !value.isBlank()) {
            return value;
        }
        throw new IllegalStateException("OpenAI Responses returned no text output");
    }

    private static String buildChatMessagesJson(List<Message> messages) {
        StringBuilder payloadMessages = new StringBuilder("[");
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (i > 0) {
                payloadMessages.append(',');
            }
            payloadMessages
                    .append('{')
                    .append("\"role\":\"")
                    .append(msg.role.apiRole())
                    .append("\",")
                    .append("\"content\":\"")
                    .append(esc(msg.content))
                    .append("\"}");
        }
        payloadMessages.append(']');
        return payloadMessages.toString();
    }

    private static Map<?, ?> jsonObject(String body, String provider) {
        if (body == null || body.isBlank())
            throw new IllegalStateException(provider + " returned an empty response body");
        Object parsed;
        try {
            parsed = SimpleJson.parse(body);
        } catch (RuntimeException ex) {
            throw new IllegalStateException(provider + " returned invalid JSON", ex);
        }
        if (!(parsed instanceof Map<?, ?> object))
            throw new IllegalStateException(provider + " expected a JSON object");
        if (object.get("error") != null)
            throw new IllegalStateException(provider + " reported a provider error");
        return object;
    }

    private static String parseChatBody(String body) {
        Map<?, ?> response = jsonObject(body, "OpenAI Chat");
        if (!(response.get("choices") instanceof List<?> choices)
                || choices.isEmpty()
                || !(choices.get(0) instanceof Map<?, ?> choice)) {
            throw new IllegalStateException("OpenAI Chat returned no reply choices");
        }
        validateStopReason(choice.get("finish_reason"), false);
        if (!(choice.get("message") instanceof Map<?, ?> message)) {
            throw new IllegalStateException("OpenAI Chat returned no reply message");
        }
        rejectRefusalAndTools(message);
        return requireText(textContent(message.get("content")), "OpenAI Chat");
    }

    private static String parseAnthropicBody(String body) {
        Map<?, ?> response = jsonObject(body, "Anthropic");
        validateStopReason(response.get("stop_reason"), true);
        return requireText(textContent(response.get("content")), "Anthropic");
    }

    private static String textContent(Object content) {
        if (content instanceof String text) return text;
        if (!(content instanceof List<?> blocks)) {
            if (content == null) return "";
            throw new IllegalStateException("Provider text content must be a string or an array");
        }
        StringBuilder text = new StringBuilder();
        for (Object value : blocks) {
            if (!(value instanceof Map<?, ?> block))
                throw new IllegalStateException("Provider content block must be an object");
            Object type = block.get("type");
            if ("refusal".equals(type))
                throw new IllegalStateException("The provider refused the request");
            if ("tool_use".equals(type) || "tool_call".equals(type)) {
                throw new IllegalStateException("The provider requested an unsupported tool call");
            }
            if ("text".equals(type) || "output_text".equals(type)) {
                if (!(block.get("text") instanceof String part))
                    throw new IllegalStateException("Provider text block must contain a string");
                text.append(part);
            }
        }
        return text.toString();
    }

    private static String requireText(String text, String provider) {
        if (text == null || text.isBlank())
            throw new IllegalStateException(provider + " returned no text output");
        return text;
    }

    private static void rejectRefusalAndTools(Map<?, ?> message) {
        if (message.get("refusal") instanceof String refusal && !refusal.isBlank()) {
            throw new IllegalStateException("The provider refused the request");
        }
        if (message.get("function_call") != null
                || (message.get("tool_calls") instanceof List<?> calls && !calls.isEmpty())) {
            throw new IllegalStateException("The provider requested an unsupported tool call");
        }
    }

    private static void validateStopReason(Object reason, boolean anthropic) {
        if (reason == null)
            return; // Some compatible gateways omit the terminal field in REST responses.
        if ("length".equals(reason) || "max_tokens".equals(reason)) {
            throw new IllegalStateException(
                    "The reply exceeded the output token limit. Increase Max tokens in LLM"
                        + " preferences or request a smaller change.");
        }
        if ("content_filter".equals(reason) || "refusal".equals(reason)) {
            throw new IllegalStateException("The provider refused the request");
        }
        if ("tool_calls".equals(reason)
                || "function_call".equals(reason)
                || "tool_use".equals(reason)) {
            throw new IllegalStateException("The provider requested an unsupported tool call");
        }
        if (anthropic
                ? !"end_turn".equals(reason) && !"stop_sequence".equals(reason)
                : !"stop".equals(reason)) {
            throw new IllegalStateException(
                    "The provider did not complete the reply (stop reason=" + reason + ")");
        }
    }

    private static String buildResponsesBody(
            List<Message> messages,
            String model,
            int maxTokens,
            double temperature,
            boolean supportsTemperature,
            boolean streaming) {
        StringBuilder body = new StringBuilder();
        body.append('{')
                .append("\"model\":\"")
                .append(esc(model))
                .append("\",")
                // Preserve conversation roles and order, including system instructions.
                .append("\"input\":")
                .append(buildChatMessagesJson(messages))
                .append(',')
                .append("\"max_output_tokens\":")
                .append(maxTokens);
        if (streaming) {
            body.append(',').append("\"stream\":true");
        }
        if (supportsTemperature) {
            body.append(',').append("\"temperature\":").append(temperature);
        }
        return body.append('}').toString();
    }

    private String sendAnthropic(List<Message> messages) throws Exception {
        String api = normalizeBase(LLMPreferences.getBaseUrl()) + "/v1/messages";
        String model = LLMPreferences.getModel();
        int maxT = LLMPreferences.getMaxTokens();
        double temp = LLMPreferences.getTemperature();
        String key = LLMPreferences.getApiKey();
        long start = System.currentTimeMillis();
        String provider = LLMPreferences.getProvider();
        String msgDump = dumpMessages(messages);
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
            payloadMessages
                    .append('{')
                    .append("\"role\":\"")
                    .append(msg.role.anthropicRole())
                    .append("\",\"content\":[{\"type\":\"text\",\"text\":\"")
                    .append(esc(msg.content))
                    .append("\"}]}");
        }
        if (first) {
            throw new IllegalArgumentException(
                    "Anthropic payload requires at least one user or assistant message");
        }
        payloadMessages.append(']');
        String systemField =
                system.length() > 0 ? "\"system\":\"" + esc(system.toString()) + "\"," : "";
        String body =
                "{"
                        + "\"model\":\""
                        + esc(model)
                        + "\","
                        + "\"max_tokens\":"
                        + maxT
                        + ","
                        + "\"temperature\":"
                        + temp
                        + ","
                        + systemField
                        + "\"messages\":"
                        + payloadMessages.toString()
                        + "}";
        HttpRequest req =
                HttpRequest.newBuilder()
                        .uri(URI.create(api))
                        .timeout(Duration.ofSeconds(LLMPreferences.getTimeoutSec()))
                        .header("Content-Type", "application/json")
                        .header("x-api-key", key)
                        .header("anthropic-version", "2023-06-01")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
        HttpResponse<String> resp = sendWithRetry(req, provider, "anthropic-messages", model);
        if (resp.statusCode() >= 400) {
            tryLogConversation(
                    provider,
                    "anthropic-messages",
                    model,
                    msgDump,
                    "",
                    false,
                    "http=" + resp.statusCode(),
                    start);
            throw providerFailure(resp.statusCode(), resp.body());
        }
        String s = resp.body();
        String result = parseAnthropicBody(s);
        tryLogConversation(
                provider, "anthropic-messages", model, msgDump, result, false, "ok", start);
        return result;
    }

    private StreamHandle streamOpenAI(List<Message> messages, StreamListener listener)
            throws Exception {
        String model = LLMPreferences.getModel();
        ModelCapabilities caps = getModelCapabilities(model);
        if (caps.useResponsesEndpoint) {
            return streamOpenAIResponses(messages, listener, caps);
        }
        String api = normalizeBase(LLMPreferences.getBaseUrl()) + "/v1/chat/completions";
        int maxT = LLMPreferences.getMaxTokens();
        double temp = LLMPreferences.getTemperature();
        String key = LLMPreferences.getApiKey();
        long start = System.currentTimeMillis();
        String provider = LLMPreferences.getProvider();
        String modelName = LLMPreferences.getModel();
        String msgDump = dumpMessages(messages);
        String payloadMessages = buildChatMessagesJson(messages);
        StringBuilder body = new StringBuilder();
        body.append('{')
                .append("\"model\":\"")
                .append(esc(model))
                .append("\",")
                .append("\"messages\":")
                .append(payloadMessages)
                .append(',')
                .append("\"max_tokens\":")
                .append(maxT)
                .append(',')
                .append("\"stream\":true");
        if (caps.supportsTemperature) {
            body.append(',').append("\"temperature\":").append(temp);
        }
        body.append('}');
        HttpRequest req =
                HttpRequest.newBuilder()
                        .uri(URI.create(api))
                        .timeout(Duration.ofSeconds(LLMPreferences.getTimeoutSec()))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + key)
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        body.toString(), StandardCharsets.UTF_8))
                        .build();
        StreamHandle sh = streamSse(req, listener, new ChatStreamParser());
        sh.completion()
                .whenComplete(
                        (reply, err) -> {
                            String status =
                                    (err == null)
                                            ? "ok"
                                            : (err.getMessage() == null
                                                    ? "error"
                                                    : "error:" + err.getMessage());
                            tryLogConversation(
                                    provider,
                                    "openai-chat(stream)",
                                    modelName,
                                    msgDump,
                                    reply,
                                    true,
                                    status,
                                    start);
                        });
        return sh;
    }

    private StreamHandle streamOpenAIResponses(
            List<Message> messages, StreamListener listener, ModelCapabilities caps)
            throws Exception {
        String api = normalizeBase(LLMPreferences.getBaseUrl()) + "/v1/responses";
        String model = LLMPreferences.getModel();
        int maxT = LLMPreferences.getMaxTokens();
        double temp = LLMPreferences.getTemperature();
        String key = LLMPreferences.getApiKey();

        String body =
                buildResponsesBody(messages, model, maxT, temp, caps.supportsTemperature, true);

        HttpRequest req =
                HttpRequest.newBuilder()
                        .uri(URI.create(api))
                        .timeout(Duration.ofSeconds(LLMPreferences.getTimeoutSec()))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + key)
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
        return streamSse(req, listener, new ResponsesStreamParser());
    }

    private StreamHandle streamAnthropic(List<Message> messages, StreamListener listener)
            throws Exception {
        String api = normalizeBase(LLMPreferences.getBaseUrl()) + "/v1/messages";
        String model = LLMPreferences.getModel();
        int maxT = LLMPreferences.getMaxTokens();
        double temp = LLMPreferences.getTemperature();
        String key = LLMPreferences.getApiKey();
        long start = System.currentTimeMillis();
        String provider = LLMPreferences.getProvider();
        String msgDump = dumpMessages(messages);
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
            payloadMessages
                    .append('{')
                    .append("\"role\":\"")
                    .append(msg.role.anthropicRole())
                    .append("\",\"content\":[{\"type\":\"text\",\"text\":\"")
                    .append(esc(msg.content))
                    .append("\"}]}");
        }
        if (first) {
            throw new IllegalArgumentException(
                    "Anthropic payload requires at least one user or assistant message");
        }
        payloadMessages.append(']');
        String systemField =
                system.length() > 0 ? "\"system\":\"" + esc(system.toString()) + "\"," : "";
        String body =
                "{"
                        + "\"model\":\""
                        + esc(model)
                        + "\","
                        + "\"max_tokens\":"
                        + maxT
                        + ","
                        + "\"temperature\":"
                        + temp
                        + ","
                        + "\"stream\":true,"
                        + systemField
                        + "\"messages\":"
                        + payloadMessages.toString()
                        + "}";
        HttpRequest req =
                HttpRequest.newBuilder()
                        .uri(URI.create(api))
                        .timeout(Duration.ofSeconds(LLMPreferences.getTimeoutSec()))
                        .header("Content-Type", "application/json")
                        .header("x-api-key", key)
                        .header("anthropic-version", "2023-06-01")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
        StreamHandle sh = streamSse(req, listener, new AnthropicStreamParser());
        sh.completion()
                .whenComplete(
                        (reply, err) -> {
                            String status =
                                    (err == null)
                                            ? "ok"
                                            : (err.getMessage() == null
                                                    ? "error"
                                                    : "error:" + err.getMessage());
                            tryLogConversation(
                                    provider,
                                    "anthropic-messages(stream)",
                                    model,
                                    msgDump,
                                    reply,
                                    true,
                                    status,
                                    start);
                        });
        return sh;
    }

    private StreamHandle streamSse(
            HttpRequest request, StreamListener listener, DeltaExtractor extractor) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<InputStream> activeBody = new AtomicReference<>();
        if (listener.isCancelled()) {
            CompletableFuture<String> stopped = new CompletableFuture<>();
            stopped.completeExceptionally(new CancellationException("Request stopped."));
            return new StreamHandle(stopped, () -> {});
        }
        CompletableFuture<HttpResponse<InputStream>> responseFuture =
                http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        CompletableFuture<String> completion = new CompletableFuture<>();
        request.timeout()
                .ifPresent(
                        timeout -> completion.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS));
        completion.whenComplete(
                (reply, error) -> {
                    if (error != null) {
                        closeStream(activeBody.getAndSet(null));
                        responseFuture.cancel(true);
                    }
                });
        responseFuture.whenComplete(
                (response, error) -> {
                    if (error != null) {
                        completion.completeExceptionally(error);
                        return;
                    }
                    if (response == null) {
                        completion.completeExceptionally(
                                new IllegalStateException("No response received"));
                        return;
                    }
                    activeBody.set(response.body());
                    if (completion.isDone() || cancelled.get()) {
                        closeStream(activeBody.getAndSet(null));
                        return;
                    }
                    if (response.statusCode() >= 400) {
                        try (InputStream body = response.body()) {
                            String message =
                                    new String(body.readNBytes(16 * 1024), StandardCharsets.UTF_8);
                            completion.completeExceptionally(
                                    providerFailure(response.statusCode(), message));
                        } catch (Exception ex) {
                            completion.completeExceptionally(ex);
                        }
                        return;
                    }
                    try (InputStream body = response.body();
                            BufferedReader reader =
                                    new BufferedReader(
                                            new InputStreamReader(body, StandardCharsets.UTF_8))) {
                        StringBuilder aggregate = new StringBuilder();
                        StringBuilder eventData = new StringBuilder();
                        String line;
                        while (true) {
                            if (completion.isDone() || cancelled.get() || listener.isCancelled()) {
                                completion.completeExceptionally(
                                        new CancellationException("stream cancelled"));
                                return;
                            }
                            line = reader.readLine();
                            if (line != null && !line.isEmpty()) {
                                if (line.startsWith("data:")) {
                                    String value = line.substring(5);
                                    if (value.startsWith(" ")) value = value.substring(1);
                                    if (eventData.length() > 0) eventData.append('\n');
                                    eventData.append(value);
                                }
                                continue;
                            }
                            String payload = eventData.toString();
                            eventData.setLength(0);
                            if (payload.isEmpty()) {
                                if (line == null) break;
                                continue;
                            }
                            if ("[DONE]".equals(payload.trim())) {
                                break;
                            }
                            String delta = extractor.extract(payload);
                            if (completion.isDone() || cancelled.get() || listener.isCancelled()) {
                                throw new CancellationException("stream cancelled");
                            }
                            if (delta != null && !delta.isEmpty()) {
                                aggregate.append(delta);
                                try {
                                    listener.onChunk(delta);
                                } catch (Exception ignore) {
                                    // UI listener best-effort
                                }
                            }
                            if (extractor.isComplete() || line == null) break;
                        }
                        completion.complete(extractor.finish(aggregate.toString()));
                    } catch (CancellationException ex) {
                        completion.completeExceptionally(ex);
                    } catch (Exception ex) {
                        completion.completeExceptionally(ex);
                    }
                });

        Runnable cancel =
                () -> {
                    cancelled.set(true);
                    completion.completeExceptionally(new CancellationException("stream cancelled"));
                    closeStream(activeBody.getAndSet(null));
                    responseFuture.cancel(true);
                };
        return new StreamHandle(completion, cancel);
    }

    private static void closeStream(InputStream stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (IOException ignore) {
            // Cancellation and timeout must not depend on closing a stalled body.
        }
    }

    private static final class ChatStreamParser implements DeltaExtractor {
        private boolean completed;

        @Override
        public String extract(String payload) {
            Map<?, ?> event = jsonObject(payload, "OpenAI Chat stream");
            if (!(event.get("choices") instanceof List<?> choices)) {
                throw new IllegalStateException("OpenAI Chat stream returned no choices array");
            }
            if (choices.isEmpty()) return ""; // A usage-only event has no choices.
            if (!(choices.get(0) instanceof Map<?, ?> choice)) {
                throw new IllegalStateException("OpenAI Chat stream choice must be an object");
            }
            Object reason = choice.get("finish_reason");
            validateStopReason(reason, false);
            if (reason != null) completed = true;
            if (!(choice.get("delta") instanceof Map<?, ?> delta)) {
                throw new IllegalStateException("OpenAI Chat stream delta must be an object");
            }
            rejectRefusalAndTools(delta);
            return textContent(delta.get("content"));
        }

        @Override
        public boolean isComplete() {
            return completed;
        }

        @Override
        public String finish(String aggregate) {
            if (!completed)
                throw new IllegalStateException(
                        "OpenAI Chat stream ended before its finish reason");
            return requireText(aggregate, "OpenAI Chat stream");
        }
    }

    private static final class AnthropicStreamParser implements DeltaExtractor {
        private boolean stopReasonReceived;
        private boolean completed;

        @Override
        public String extract(String payload) {
            Map<?, ?> event = jsonObject(payload, "Anthropic stream");
            Object type = event.get("type");
            if ("content_block_start".equals(type)) {
                if (!(event.get("content_block") instanceof Map<?, ?> block)) {
                    throw new IllegalStateException(
                            "Anthropic stream content block must be an object");
                }
                return textContent(List.of(block));
            }
            if ("content_block_delta".equals(type)) {
                if (!(event.get("delta") instanceof Map<?, ?> delta)) {
                    throw new IllegalStateException("Anthropic stream delta must be an object");
                }
                if ("input_json_delta".equals(delta.get("type"))) {
                    throw new IllegalStateException(
                            "The provider requested an unsupported tool call");
                }
                if ("text_delta".equals(delta.get("type"))) {
                    if (!(delta.get("text") instanceof String text)) {
                        throw new IllegalStateException(
                                "Anthropic stream text delta must be a string");
                    }
                    return text;
                }
            }
            if ("message_delta".equals(type)) {
                if (!(event.get("delta") instanceof Map<?, ?> delta)) {
                    throw new IllegalStateException(
                            "Anthropic stream message delta must be an object");
                }
                Object reason = delta.get("stop_reason");
                validateStopReason(reason, true);
                stopReasonReceived = reason != null;
            }
            if ("message_stop".equals(type)) {
                if (!stopReasonReceived)
                    throw new IllegalStateException("Anthropic stream ended without a stop reason");
                completed = true;
            }
            return "";
        }

        @Override
        public boolean isComplete() {
            return completed;
        }

        @Override
        public String finish(String aggregate) {
            if (!completed)
                throw new IllegalStateException("Anthropic stream ended before message_stop");
            return requireText(aggregate, "Anthropic stream");
        }
    }

    private static final class ResponsesStreamParser implements DeltaExtractor {
        private String completedText;

        @Override
        public String extract(String payload) {
            Object parsed;
            try {
                parsed = SimpleJson.parse(payload);
            } catch (RuntimeException ex) {
                throw new IllegalStateException(
                        "OpenAI Responses stream returned invalid JSON", ex);
            }
            if (!(parsed instanceof Map<?, ?> event)) {
                throw new IllegalStateException("OpenAI Responses stream event must be an object");
            }
            Object type = event.get("type");
            if ("response.output_text.delta".equals(type)) {
                if (!(event.get("delta") instanceof String text)) {
                    throw new IllegalStateException(
                            "OpenAI Responses stream delta must be a string");
                }
                return text;
            }
            if ("response.refusal.delta".equals(type) || "response.refusal.done".equals(type)) {
                throw new IllegalStateException("OpenAI refused the request");
            }
            if ("error".equals(type)) {
                Object detail = event.get("message");
                throw new IllegalStateException(
                        "OpenAI Responses stream failed" + (detail == null ? "" : ": " + detail));
            }
            if ("response.failed".equals(type) || "response.incomplete".equals(type)) {
                // Use the same status, error and refusal validation as nonstream responses.
                parseResponsesObject(event.get("response"));
                throw new IllegalStateException(
                        "OpenAI Responses stream did not complete: " + type);
            }
            if ("response.completed".equals(type)) {
                completedText = parseResponsesObject(event.get("response"));
            }
            return "";
        }

        @Override
        public boolean isComplete() {
            return completedText != null;
        }

        @Override
        public String finish(String aggregate) {
            if (completedText == null) {
                throw new IllegalStateException(
                        "OpenAI Responses stream ended before response.completed");
            }
            return completedText;
        }
    }

    /**
     * Detects model capabilities using a hybrid approach: 1. Check cache for previously detected
     * capabilities 2. Use pattern matching for known model families 3. Cache result for future
     * requests
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

        // Match actual reasoning families, rather than unrelated compatible models such as "orca".
        if (model.startsWith("gpt-5")
                || model.startsWith("gpt-4.1")
                || model.matches("o[1-9](?:-.*)?")) {
            useResponses = true;
            supportsTemperature = model.startsWith("gpt-4.1");
        } else if (model.startsWith("gpt-4o")
                || model.startsWith("gpt-4")
                || model.startsWith("gpt-3")) {
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

        default boolean isComplete() {
            return false;
        }

        default String finish(String aggregate) {
            return aggregate;
        }
    }

    private static String normalizeBase(String b) {
        String base = b.strip();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        // Accept both host roots and conventional API bases ending in /v1.
        return base.endsWith("/v1") ? base.substring(0, base.length() - 3) : base;
    }

    /** Validate provider settings without making a request or exposing the API key. */
    public static String configurationIssue() {
        if (LLMPreferences.getModel() == null || LLMPreferences.getModel().isBlank()) {
            return "Choose a model in LLM preferences before sending a request.";
        }
        try {
            URI base = URI.create(LLMPreferences.getBaseUrl().strip());
            if (!("http".equalsIgnoreCase(base.getScheme())
                            || "https".equalsIgnoreCase(base.getScheme()))
                    || base.getHost() == null
                    || base.getUserInfo() != null
                    || base.getQuery() != null
                    || base.getFragment() != null) {
                return "Enter a valid HTTP or HTTPS provider base URL in LLM preferences.";
            }
        } catch (RuntimeException ex) {
            return "Enter a valid HTTP or HTTPS provider base URL in LLM preferences.";
        }
        if (LLMPreferences.getMaxTokens() <= 0)
            return "Set Max tokens to a positive number in LLM preferences.";
        if (LLMPreferences.getTimeoutSec() <= 0)
            return "Set Timeout to a positive number in LLM preferences.";
        if (!Double.isFinite(LLMPreferences.getTemperature())
                || LLMPreferences.getTemperature() < 0
                || LLMPreferences.getTemperature() > 2)
            return "Set Temperature between 0 and 2 in LLM preferences.";
        if ("anthropic".equalsIgnoreCase(LLMPreferences.getProvider())
                && LLMPreferences.getTemperature() > 1) {
            return "Set Temperature between 0 and 1 for Anthropic in LLM preferences.";
        }
        if (!"custom".equalsIgnoreCase(LLMPreferences.getProvider())) {
            String key = LLMPreferences.getApiKey();
            if (key == null || key.isBlank())
                return "Add an API key in LLM preferences before sending a request.";
        }
        return null;
    }

    private static void validateConfiguration(List<Message> messages) {
        if (Thread.currentThread().isInterrupted())
            throw new CancellationException("Request stopped.");
        if (messages.stream().anyMatch(message -> message == null))
            throw new IllegalArgumentException("Messages must not contain null entries");
        String issue = configurationIssue();
        if (issue != null) throw new IllegalStateException(issue);
    }

    private static final class ProviderException extends RuntimeException {
        ProviderException(String message) {
            super(message);
        }
    }

    private static ProviderException providerFailure(int status, String body) {
        String code = providerErrorCode(body);
        String guidance;
        if (status == 401)
            guidance = "The provider rejected the API key. Check the key in LLM preferences.";
        else if (status == 403)
            guidance = "The provider denied access. Check the key's permissions and model access.";
        else if (status == 402 || isQuotaError(code))
            guidance =
                    "The provider's quota or credit balance is exhausted. Check the provider's"
                        + " billing and usage limits before retrying.";
        else if (status == 429)
            guidance = "The provider's rate limit was reached. Wait briefly, then retry.";
        else if (status == 404)
            guidance =
                    "The provider endpoint or model was not found. Check the base URL and model in"
                        + " LLM preferences.";
        else if (status >= 500)
            guidance = "The provider is temporarily unavailable. Try again shortly.";
        else if (code.contains("context") || code.contains("token_limit"))
            guidance =
                    "The request exceeds the model's context limit. Start a new conversation or"
                        + " make the request smaller.";
        else
            guidance =
                    "The provider rejected the request. Check the model and request settings in LLM"
                        + " preferences.";
        // Never put the raw response in UI errors: HTML and credential echoes are common in gateway
        // failures.
        return new ProviderException(guidance + " (HTTP " + status + ")");
    }

    private static String providerErrorCode(String body) {
        try {
            Object parsed = SimpleJson.parse(body);
            if (parsed instanceof Map<?, ?> response
                    && response.get("error") instanceof Map<?, ?> error) {
                Object code = error.get("code");
                if (!(code instanceof String) || ((String) code).isBlank())
                    code = error.get("type");
                return code instanceof String text ? text.toLowerCase(java.util.Locale.ROOT) : "";
            }
        } catch (RuntimeException ignore) {
        }
        return "";
    }

    private static boolean isQuotaError(String code) {
        return code.contains("quota")
                || code.contains("credit_balance")
                || code.contains("billing")
                || code.contains("spend_limit")
                || code.contains("usage_limit");
    }

    /** Concise recovery guidance for the chat UI; also unwrap asynchronous failures. */
    public static String userFacingError(Throwable error) {
        if (error == null) return "The request failed. Try again.";
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof CancellationException || current instanceof InterruptedException)
                return "Request stopped.";
            if (current instanceof HttpTimeoutException || current instanceof TimeoutException) {
                return "The provider did not respond in time. Retry, request a smaller change, or"
                           + " increase Timeout in LLM preferences.";
            }
            if (current instanceof ProviderException) return current.getMessage();
            if (current instanceof ConnectException || current instanceof UnknownHostException) {
                return "Could not reach the provider. Check the base URL, network connection and"
                           + " proxy settings, then retry.";
            }
        }
        if (cause instanceof IOException)
            return "The connection to the provider failed. Check your connection and retry.";
        String message = cause.getMessage();
        if (message == null || message.isBlank()) return "The request failed. Try again.";
        if (message.contains("max_output_tokens")) {
            return "The reply exceeded the output token limit. Increase Max tokens in LLM"
                       + " preferences or request a smaller change.";
        }
        if (message.contains("invalid JSON")
                || message.contains("stream ended")
                || message.contains("no text output")
                || message.contains("empty response body")
                || message.contains("did not complete")) {
            return "The provider returned an incomplete or unreadable reply. Retry or check that"
                       + " the provider URL supports the selected model.";
        }
        return message.length() > 400 ? message.substring(0, 397) + "..." : message;
    }

    private static String dumpMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            sb.append(m.getRole().name().toLowerCase())
                    .append(": ")
                    .append(m.getContent() == null ? "" : m.getContent())
                    .append(System.lineSeparator());
        }
        return sb.toString().stripTrailing();
    }

    private static void tryLogConversation(
            String provider,
            String api,
            String model,
            String messages,
            String reply,
            boolean streaming,
            String status,
            long startMs) {
        try {
            long dur = Math.max(0, System.currentTimeMillis() - startMs);
            com.sidkolapalli.capella.ai.telemetry.Telemetry.recordConversation(
                    provider, api, model, messages, reply, streaming, status, dur);
        } catch (Throwable ignore) {
            // Never let logging break the flow
        }
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char value = s.charAt(i);
            switch (value) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (value < 0x20)
                        escaped.append(
                                String.format(java.util.Locale.ROOT, "\\u%04x", (int) value));
                    else escaped.append(value);
            }
        }
        return escaped.toString();
    }

    private HttpResponse<String> sendWithRetry(
            HttpRequest req, String provider, String api, String model) throws Exception {
        int retries = 0;
        long started = System.nanoTime();
        long budget = req.timeout().orElse(Duration.ofSeconds(60)).toNanos();
        while (true) {
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedException("Request stopped.");
            long remaining = budget - (System.nanoTime() - started);
            if (remaining <= 0) throw new HttpTimeoutException("The provider request timed out.");
            HttpRequest bounded =
                    HttpRequest.newBuilder(req, (name, value) -> true)
                            .timeout(Duration.ofNanos(remaining))
                            .build();
            HttpResponse<String> resp;
            try {
                resp = http.send(bounded, HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            int status = resp.statusCode();
            if (!isRetryableStatus(status)
                    || isQuotaError(providerErrorCode(resp.body()))
                    || retries >= MAX_RETRIES) {
                return resp;
            }
            long delayMs =
                    computeBackoffMillis(
                            retries, resp.headers().firstValue("Retry-After").orElse(null));
            remaining = budget - (System.nanoTime() - started);
            // Respect Retry-After, but never sleep past the configured total request deadline.
            if (delayMs >= TimeUnit.NANOSECONDS.toMillis(remaining)) return resp;
            logRetry(provider, api, model, status, delayMs, retries + 1);
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw ex;
            }
            retries++;
        }
    }

    public static boolean isRetryableStatus(int statusCode) {
        return statusCode == 408
                || statusCode == 429
                || statusCode == 500
                || statusCode == 502
                || statusCode == 503
                || statusCode == 504
                || statusCode == 529;
    }

    public static long computeBackoffMillis(int retryIndex, String retryAfterHeader) {
        long delayMs = Math.min(30_000L, BASE_BACKOFF_MS << Math.min(5, Math.max(0, retryIndex)));
        long retryAfterMs = parseRetryAfterMillis(retryAfterHeader);
        if (retryAfterMs > delayMs) {
            delayMs = retryAfterMs;
        }
        return delayMs;
    }

    public static long parseRetryAfterMillis(String header) {
        return parseRetryAfterMillis(header, Instant.now());
    }

    private static long parseRetryAfterMillis(String header, Instant now) {
        if (header == null || header.isBlank()) {
            return -1;
        }
        try {
            long seconds = Long.parseLong(header.trim());
            if (seconds < 0) return -1;
            return Math.multiplyExact(seconds, 1000L);
        } catch (NumberFormatException ex) {
            try {
                Instant target =
                        ZonedDateTime.parse(header.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                                .toInstant();
                return Math.max(0, Duration.between(now, target).toMillis());
            } catch (DateTimeParseException | ArithmeticException invalid) {
                return -1;
            }
        } catch (ArithmeticException ex) {
            return Long.MAX_VALUE;
        }
    }

    private static void logRetry(
            String provider, String api, String model, int status, long delayMs, int attempt) {
        long seconds = Math.max(1L, Math.round(delayMs / 1000.0));
        System.out.println(
                "[LLMService] "
                        + provider
                        + " "
                        + api
                        + " ("
                        + model
                        + ") returned "
                        + status
                        + ", retrying in "
                        + seconds
                        + "s (attempt "
                        + attempt
                        + "/"
                        + MAX_RETRIES
                        + ")");
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
        validateConfiguration(messages);
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
