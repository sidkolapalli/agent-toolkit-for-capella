package org.polarsys.capella.ai.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

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
        String provider = LLMPreferences.getProvider();
        if (provider.equalsIgnoreCase("anthropic")) return sendAnthropic(prompt);
        // default: openai-compatible
        return sendOpenAI(prompt);
    }

    public String testConnection() throws Exception {
        String provider = LLMPreferences.getProvider();
        if (provider.equalsIgnoreCase("anthropic")) {
            // Anthropic expects anthropic-version; we just return provider info
            return "Anthropic configured: " + LLMPreferences.getModel();
        }
        return "OpenAI-compatible configured: " + LLMPreferences.getModel();
    }

    private String sendOpenAI(String prompt) throws Exception {
        String api = normalizeBase(LLMPreferences.getBaseUrl()) + "/v1/chat/completions";
        String model = LLMPreferences.getModel();
        int maxT = LLMPreferences.getMaxTokens();
        double temp = LLMPreferences.getTemperature();
        String key = LLMPreferences.getApiKey();
        String body = "{" +
            "\"model\":\"" + esc(model) + "\"," +
            "\"messages\":[{" +
                "\"role\":\"user\",\"content\":\"" + esc(prompt) + "\"}]," +
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

    private String sendAnthropic(String prompt) throws Exception {
        String api = normalizeBase(LLMPreferences.getBaseUrl()) + "/v1/messages";
        String model = LLMPreferences.getModel();
        int maxT = LLMPreferences.getMaxTokens();
        double temp = LLMPreferences.getTemperature();
        String key = LLMPreferences.getApiKey();
        String body = "{" +
            "\"model\":\"" + esc(model) + "\"," +
            "\"max_tokens\":" + maxT + "," +
            "\"temperature\":" + temp + "," +
            "\"messages\":[{" +
                "\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"" + esc(prompt) + "\"}]}]" +
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
    private static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }
}

