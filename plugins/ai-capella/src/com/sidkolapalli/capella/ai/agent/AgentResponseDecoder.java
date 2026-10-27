package com.sidkolapalli.capella.ai.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolve privacy tokens inside JSON string values without breaking the wire format. */
public final class AgentResponseDecoder {
    private static final Pattern JSON_FENCE = Pattern.compile("^```(?:json|agent)[ \\t]*\\r?\\n");
    private static final Pattern GROOVY_FENCE =
            Pattern.compile("(?is)```groovy[ \\t]*(?:\\r?\\n)?(.*?)```");
    private static final Pattern HISTORY_GROOVY_FENCE =
            Pattern.compile("(?ims)^```groovy[ \\t]*\\r?\\n(.*?)^[ \\t]*```[ \\t]*(?=\\r?$)");
    private static final String OMITTED_SOURCE =
            "/* Prior proposal source omitted for privacy. Regenerate using the original task,"
                + " model evidence and compile-only diagnostics. This history entry is not an"
                + " applied change. */";

    private AgentResponseDecoder() {}

    /** Tokenize data values, preserving protocol keys, enum values and plan identifiers. */
    public static String encodeMessage(String message, UnaryOperator<String> encodeText) {
        return encodeMessage(message, encodeText, encodeText);
    }

    public static String encodeMessage(
            String message, UnaryOperator<String> encodeText, UnaryOperator<String> encodeData) {
        String text = message.strip();
        boolean fenced = JSON_FENCE.matcher(text).find() && text.endsWith("```");
        String json =
                fenced ? text.substring(text.indexOf('\n') + 1, text.length() - 3).strip() : text;
        int start = json.startsWith("{") ? 0 : json.indexOf("\n{");
        if (start < 0) return encodeSourceFences(message, encodeText, encodeData);
        if (start > 0) start++;
        Object data;
        try {
            data = AgentJson.parse(json.substring(start), 200000);
        } catch (IllegalArgumentException prose) {
            return encodeSourceFences(message, encodeText, encodeData);
        }
        // Source encoding errors must never fall back to raw prose tokenization.
        String encoded =
                encodeText.apply(json.substring(0, start))
                        + AgentJson.write(encode(data, encodeText, encodeData));
        return fenced ? "```json\n" + encoded + "\n```" : encoded;
    }

    private static String encodeSourceFences(
            String message, UnaryOperator<String> encodeText, UnaryOperator<String> encodeData) {
        Matcher scripts = HISTORY_GROOVY_FENCE.matcher(message);
        StringBuilder output = new StringBuilder();
        int previous = 0;
        while (scripts.find()) {
            output.append(encodeText.apply(message.substring(previous, scripts.start())));
            output.append(message, scripts.start(), scripts.start(1));
            output.append(encodeHistorySource(scripts.group(1), encodeData));
            output.append(message, scripts.end(1), scripts.end());
            previous = scripts.end();
        }
        String remaining = message.substring(previous);
        int incomplete = remaining.toLowerCase(java.util.Locale.ROOT).indexOf("```groovy");
        if (incomplete >= 0) {
            // The remaining source boundary is unknown; omit its whole tail rather than guess at
            // private chunks.
            output.append(encodeText.apply(remaining.substring(0, incomplete)))
                    .append(OMITTED_SOURCE);
            return output.toString();
        }
        output.append(encodeText.apply(remaining));
        return output.toString();
    }

    private static String encodeHistorySource(String source, UnaryOperator<String> encodeData) {
        try {
            return GroovySourceDecoder.encode(source, encodeData);
        } catch (IllegalArgumentException unsafe) {
            // Syntax-invalid drafts still need a bounded repair request; source excerpts are never
            // a fallback.
            return OMITTED_SOURCE;
        }
    }

    private static Object encode(
            Object value, UnaryOperator<String> encodeText, UnaryOperator<String> encodeData) {
        if (value instanceof String text) return encodeText.apply(text);
        if (value instanceof Map<?, ?> values) {
            Map<String, Object> encoded = new LinkedHashMap<>();
            for (var entry : values.entrySet()) {
                String key = (String) entry.getKey();
                Object item = entry.getValue();
                if (Set.of(
                                        "kind",
                                        "toolName",
                                        "stepId",
                                        "stepIds",
                                        "completedStepIds",
                                        "status",
                                        "type")
                                .contains(key)
                        || ("tool".equals(key)
                                && item instanceof String tool
                                && Set.of(
                                                "model_summary",
                                                "model_search",
                                                "model_inspect",
                                                "model_relationships",
                                                "model_validate",
                                                "native_validation",
                                                "capella_docs",
                                                "capella_capabilities",
                                                "capella_plan",
                                                "capella_propose_edit")
                                        .contains(tool))
                        || ("code".equals(key)
                                && item instanceof String code
                                && Set.of("invalid_arguments", "invalid_plan", "invalid_proposal")
                                        .contains(code))
                        || ("id".equals(key)
                                && values.containsKey("title")
                                && values.containsKey("status"))) {
                    encoded.put(key, item);
                } else if ("script".equals(key)
                        && "proposal".equals(values.get("kind"))
                        && item instanceof String source) {
                    encoded.put(key, encodeHistorySource(source, encodeData));
                } else if ("result".equals(key)
                        && item instanceof String text
                        && text.strip().startsWith("{")) {
                    // Tool results are themselves JSON encoded as a string in the feedback
                    // envelope.
                    encoded.put(key, encodeMessage(text, encodeText, encodeData));
                } else if (item instanceof String text
                        && Set.of(
                                        "name",
                                        "label",
                                        "project_name",
                                        "ReqIFName",
                                        "ReqIFLongName",
                                        "ReqIFIdentifier",
                                        "reqif_identifier",
                                        "description",
                                        "query",
                                        "id",
                                        "sourceIdentifier")
                                .contains(key)) {
                    encoded.put(key, encodeData.apply(text));
                } else if ("expected".equals(key)
                        && item instanceof String text
                        && values.get("attribute") instanceof String attribute
                        && Set.of(
                                        "name",
                                        "description",
                                        "ReqIFName",
                                        "ReqIFLongName",
                                        "ReqIFIdentifier")
                                .contains(attribute)) {
                    encoded.put(key, encodeData.apply(text));
                } else encoded.put(key, encode(item, encodeText, encodeData));
            }
            return encoded;
        }
        if (value instanceof List<?> values)
            return values.stream().map(item -> encode(item, encodeText, encodeData)).toList();
        return value;
    }

    public static String decode(String response, UnaryOperator<String> decodeText) {
        if (response == null) return null;
        String text = response.strip();
        boolean fenced = JSON_FENCE.matcher(text).find();
        String json =
                fenced && text.endsWith("```")
                        ? text.substring(text.indexOf('\n') + 1, text.length() - 3).strip()
                        : text;
        if (json.startsWith("{")) {
            // Invalid structured responses remain errors; they never become executable fallback
            // text.
            return AgentJson.write(decode(AgentJson.parse(json, 120000), decodeText));
        }
        Matcher script = GROOVY_FENCE.matcher(response);
        if (script.find()) {
            String before = decodeText.apply(response.substring(0, script.start()));
            String header = response.substring(script.start(), script.start(1));
            String source = GroovySourceDecoder.decode(script.group(1), decodeText);
            String after = response.substring(script.end());
            if (script.find())
                throw new IllegalArgumentException(
                        "Legacy reply must contain exactly one Groovy proposal");
            return before + header + source + "```" + decodeText.apply(after);
        }
        return decodeText.apply(response);
    }

    private static Object decode(Object value, UnaryOperator<String> decodeText) {
        if (value instanceof String text) return decodeText.apply(text);
        if (value instanceof Map<?, ?> values) {
            Map<String, Object> decoded = new LinkedHashMap<>();
            for (var entry : values.entrySet()) {
                Object item = entry.getValue();
                decoded.put(
                        (String) entry.getKey(),
                        "script".equals(entry.getKey())
                                        && "proposal".equals(values.get("kind"))
                                        && item instanceof String script
                                ? GroovySourceDecoder.decode(script, decodeText)
                                : decode(item, decodeText));
            }
            return decoded;
        }
        if (value instanceof List<?> values) {
            List<Object> decoded = new ArrayList<>();
            for (Object item : values) decoded.add(decode(item, decodeText));
            return decoded;
        }
        return value;
    }
}
