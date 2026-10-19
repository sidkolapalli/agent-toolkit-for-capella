package org.polarsys.capella.ai.privacy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Privacy tokenization helper that replaces sensitive strings (element names, UUIDs)
 * with stable tokens before sending prompts to an LLM, and can reverse the mapping
 * before executing locally.
 *
 * <p>Design goals:
 * <ul>
 *   <li>Stable mapping per session key</li>
 *   <li>Low overhead (tokenize only strings present in prompts/messages)</li>
 *   <li>Best-effort audit trail (local-only)</li>
 * </ul>
 */
public final class Tokenizer {

    private static final Map<String, Tokenizer> BY_SESSION =
        new ConcurrentHashMap<>();

    private static final Pattern UUID = Pattern.compile(
        "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b"
    );

    private static final Pattern QUOTED = Pattern.compile(
        "([\"'])([^\\r\\n]{1,200}?)\\1"
    );

    private static final Pattern TOKEN = Pattern.compile(
        "\\b(?:ELEM|UUID)_\\d{4}\\b"
    );

    private static final Pattern LAYER_CODE = Pattern.compile("^(OA|SA|LA|PA)$");
    private static final Pattern ALL_CAPS_SHORT = Pattern.compile("^[A-Z]{2,6}$");
    private static final Pattern TYPE_LIKE = Pattern.compile(
        "^[A-Z][A-Za-z0-9]+$"
    );

    private final String sessionKey;
    private final Map<String, String> valueToToken = new LinkedHashMap<>();
    private final Map<String, String> tokenToValue = new LinkedHashMap<>();
    private int elemSeq = 1;
    private int uuidSeq = 1;
    private long lastAuditWriteMs = 0L;

    private Tokenizer(String sessionKey) {
        this.sessionKey = sessionKey != null ? sessionKey : "default";
    }

    public static Tokenizer forSession(String sessionKey) {
        String key = sessionKey != null ? sessionKey : "default";
        return BY_SESSION.computeIfAbsent(key, Tokenizer::new);
    }

    public Map<String, String> tokenToValueSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(tokenToValue));
    }

    public String tokenizeText(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }

        String withUuids = tokenizeByPattern(input, UUID, "UUID");
        String withQuoted = tokenizeQuotedStrings(withUuids);
        maybeWriteAudit();
        return withQuoted;
    }

    public String detokenizeText(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }

        // Fast path: if no tokens appear, return as-is.
        if (!TOKEN.matcher(input).find()) {
            return input;
        }

        String out = input;
        // Replace longer tokens first is unnecessary (fixed width), but keep deterministic ordering.
        for (Map.Entry<String, String> e : tokenToValue.entrySet()) {
            out = out.replace(e.getKey(), e.getValue());
        }
        return out;
    }

    /** A schema-identified model name or identifier, including quotes and line breaks. */
    public String tokenizeDataValue(String input) {
        if (input == null || input.isBlank() || TOKEN.matcher(input).matches()) return input;
        String result = tokenForValue(input, UUID.matcher(input).matches() ? "UUID" : "ELEM");
        maybeWriteAudit();
        return result;
    }

    /** Redact registered model values in prose feedback as well as quoted labels and UUIDs. */
    public String tokenizeKnownText(String input) {
        String result = tokenizeText(input);
        if (result == null || result.isBlank()) return result;
        List<Map.Entry<String, String>> known;
        synchronized (this) { known = new ArrayList<>(valueToToken.entrySet()); }
        known.sort((left, right) -> Integer.compare(right.getKey().length(), left.getKey().length()));
        for (var entry : known) {
            String value = entry.getKey();
            if (value.isEmpty()) continue;
            String start = Character.isLetterOrDigit(value.charAt(0)) ? "(?<![\\p{L}\\p{N}_])" : "";
            String end = Character.isLetterOrDigit(value.charAt(value.length() - 1)) ? "(?![\\p{L}\\p{N}_])" : "";
            result = Pattern.compile(start + Pattern.quote(value) + end).matcher(result)
                .replaceAll(Matcher.quoteReplacement(entry.getValue()));
        }
        return result;
    }

    private String tokenizeByPattern(String input, Pattern pattern, String kind) {
        Matcher m = pattern.matcher(input);
        StringBuffer sb = new StringBuffer(input.length() + 64);
        while (m.find()) {
            String value = m.group();
            String token = tokenForValue(value, kind);
            m.appendReplacement(sb, Matcher.quoteReplacement(token));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String tokenizeQuotedStrings(String input) {
        Matcher m = QUOTED.matcher(input);
        StringBuffer sb = new StringBuffer(input.length() + 64);
        while (m.find()) {
            String quote = m.group(1);
            String value = m.group(2);
            String replacement = quote + maybeTokenizeQuotedValue(value) + quote;
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String maybeTokenizeQuotedValue(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return value;
        }
        // If it already looks like a token, keep it.
        if (TOKEN.matcher(trimmed).matches()) {
            return trimmed;
        }
        // Avoid tokenizing common non-sensitive identifiers in prompts (layer codes, type names).
        if (LAYER_CODE.matcher(trimmed).matches()) {
            return trimmed;
        }
        if (ALL_CAPS_SHORT.matcher(trimmed).matches()) {
            return trimmed;
        }
        if (isLikelyMetamodelType(trimmed)) {
            return trimmed;
        }
        if (TYPE_LIKE.matcher(trimmed).matches() && !looksLikeHumanName(trimmed)) {
            return trimmed;
        }
        // Tokenize any likely human-provided name/label.
        return tokenForValue(trimmed, "ELEM");
    }

    private static boolean isLikelyMetamodelType(String s) {
        if (s == null || s.isBlank()) {
            return false;
        }
        if (!TYPE_LIKE.matcher(s).matches()) {
            return false;
        }
        // Capella/EMF type names are usually CamelCase with known suffixes.
        String[] suffixes = new String[] {
            "Function",
            "Component",
            "Exchange",
            "Architecture",
            "Analysis",
            "Pkg",
            "Package",
            "Port",
            "Diagram",
            "Allocation",
            "Capability",
            "Activity",
            "Entity",
            "Actor",
            "StateMachine",
            "State",
            "Mode",
            "Transition",
            "Requirement"
        };
        for (String suf : suffixes) {
            if (s.endsWith(suf)) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeHumanName(String s) {
        // PascalCase types are usually non-sensitive; names often have spaces, punctuation, or lowercase words.
        if (s.indexOf(' ') >= 0 || s.indexOf('-') >= 0 || s.indexOf('_') >= 0) {
            return true;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLowerCase(c)) {
                return true;
            }
        }
        return false;
    }

    private synchronized String tokenForValue(String value, String kind) {
        String existing = valueToToken.get(value);
        if (existing != null) {
            return existing;
        }
        String token;
        if ("UUID".equals(kind)) {
            token = String.format("UUID_%04d", uuidSeq++);
        } else {
            token = String.format("ELEM_%04d", elemSeq++);
        }
        valueToToken.put(value, token);
        tokenToValue.put(token, value);
        return token;
    }

    private void maybeWriteAudit() {
        long now = System.currentTimeMillis();
        if (now - lastAuditWriteMs < 2000L) {
            return;
        }
        lastAuditWriteMs = now;
        try {
            Path dir = Paths.get(
                System.getProperty("user.home"),
                ".capella-ai",
                "privacy",
                "token-maps"
            );
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            Path file = dir.resolve(safeFileName(sessionKey) + ".jsonl");
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, String> e : tokenToValue.entrySet()) {
                String token = e.getKey();
                String value = e.getValue();
                lines.add(
                    "{" +
                    "\"ts\":\"" + jsonEscape(Instant.now().toString()) + "\"," +
                    "\"session\":\"" + jsonEscape(sessionKey) + "\"," +
                    "\"token\":\"" + jsonEscape(token) + "\"," +
                    "\"value\":\"" + jsonEscape(value) + "\"" +
                    "}"
                );
            }
            if (!lines.isEmpty()) {
                Files.write(
                    file,
                    (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
                );
            }
        } catch (Exception ignore) {
            // Best effort: never break core flow.
        }
    }

    private static String safeFileName(String input) {
        if (input == null || input.isBlank()) {
            return "default";
        }
        return input.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.toString();
    }
}
