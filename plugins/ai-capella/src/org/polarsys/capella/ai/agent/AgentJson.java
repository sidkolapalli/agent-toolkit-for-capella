package org.polarsys.capella.ai.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Strict, bounded JSON for the agent wire protocol. No executable expressions are accepted. */
public final class AgentJson {
    private AgentJson() {}

    public static Object parse(String source, int maximumLength) {
        if (source == null || source.length() > maximumLength)
            throw new IllegalArgumentException("Agent JSON exceeds the response limit");
        Parser parser = new Parser(source);
        Object value = parser.value(0);
        parser.space();
        if (parser.position != source.length())
            throw new IllegalArgumentException("Unexpected text after agent JSON");
        return value;
    }

    public static String write(Object value) {
        StringBuilder output = new StringBuilder();
        append(output, value);
        return output.toString();
    }

    private static void append(StringBuilder output, Object value) {
        if (value == null) output.append("null");
        else if (value instanceof String text) {
            output.append('"');
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '"':
                        output.append("\\\"");
                        break;
                    case '\\':
                        output.append("\\\\");
                        break;
                    case '\n':
                        output.append("\\n");
                        break;
                    case '\r':
                        output.append("\\r");
                        break;
                    case '\t':
                        output.append("\\t");
                        break;
                    default:
                        if (c < 32)
                            output.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                        else output.append(c);
                }
            }
            output.append('"');
        } else if (value instanceof Number || value instanceof Boolean) output.append(value);
        else if (value instanceof Map<?, ?> map) {
            output.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String))
                    throw new IllegalArgumentException("JSON keys must be strings");
                if (!first) output.append(',');
                first = false;
                append(output, entry.getKey());
                output.append(':');
                append(output, entry.getValue());
            }
            output.append('}');
        } else if (value instanceof Collection<?> collection) {
            output.append('[');
            boolean first = true;
            for (Object item : collection) {
                if (!first) output.append(',');
                first = false;
                append(output, item);
            }
            output.append(']');
        } else throw new IllegalArgumentException("Unsupported JSON value");
    }

    private static final class Parser {
        final String source;
        int position;

        Parser(String source) {
            this.source = source;
        }

        void space() {
            while (position < source.length() && " \t\r\n".indexOf(source.charAt(position)) >= 0)
                position++;
        }

        Object value(int depth) {
            if (depth > 16) throw invalid("JSON nesting exceeds the protocol limit");
            space();
            if (position >= source.length()) throw invalid("Missing JSON value");
            char c = source.charAt(position);
            if (c == '"') return string();
            if (c == '{') {
                position++;
                Map<String, Object> map = new LinkedHashMap<>();
                space();
                if (take('}')) return map;
                do {
                    space();
                    if (position >= source.length() || source.charAt(position) != '"')
                        throw invalid("JSON object key must be a string");
                    String key = string();
                    if (map.containsKey(key)) throw invalid("Duplicate JSON key");
                    expect(':');
                    map.put(key, value(depth + 1));
                    if (map.size() > 64) throw invalid("Too many JSON object fields");
                    space();
                    if (take('}')) return map;
                    expect(',');
                } while (true);
            }
            if (c == '[') {
                position++;
                ArrayList<Object> values = new ArrayList<>();
                space();
                if (take(']')) return values;
                do {
                    values.add(value(depth + 1));
                    if (values.size() > 256) throw invalid("Too many JSON array items");
                    space();
                    if (take(']')) return values;
                    expect(',');
                } while (true);
            }
            if (source.startsWith("true", position)) {
                position += 4;
                return true;
            }
            if (source.startsWith("false", position)) {
                position += 5;
                return false;
            }
            if (source.startsWith("null", position)) {
                position += 4;
                return null;
            }
            return number();
        }

        String string() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (position < source.length()) {
                char c = source.charAt(position++);
                if (c == '"') return result.toString();
                if (c < 32) throw invalid("Literal control character in JSON string");
                if (c != '\\') {
                    result.append(c);
                    continue;
                }
                if (position >= source.length()) throw invalid("Incomplete JSON escape");
                char escape = source.charAt(position++);
                switch (escape) {
                    case '"':
                    case '\\':
                    case '/':
                        result.append(escape);
                        break;
                    case 'b':
                        result.append('\b');
                        break;
                    case 'f':
                        result.append('\f');
                        break;
                    case 'n':
                        result.append('\n');
                        break;
                    case 'r':
                        result.append('\r');
                        break;
                    case 't':
                        result.append('\t');
                        break;
                    case 'u':
                        if (position + 4 > source.length())
                            throw invalid("Incomplete Unicode escape");
                        for (int i = position; i < position + 4; i++) {
                            char digit = source.charAt(i);
                            if (!((digit >= '0' && digit <= '9')
                                    || (digit >= 'a' && digit <= 'f')
                                    || (digit >= 'A' && digit <= 'F'))) {
                                throw invalid("Invalid Unicode escape");
                            }
                        }
                        try {
                            result.append(
                                    (char)
                                            Integer.parseInt(
                                                    source.substring(position, position + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw invalid("Invalid Unicode escape");
                        }
                        position += 4;
                        break;
                    default:
                        throw invalid("Invalid JSON escape");
                }
            }
            throw invalid("Unterminated JSON string");
        }

        Number number() {
            int start = position;
            take('-');
            if (take('0')) {
                if (position < source.length() && Character.isDigit(source.charAt(position)))
                    throw invalid("Leading zero in JSON number");
            } else {
                if (position >= source.length()
                        || source.charAt(position) < '1'
                        || source.charAt(position) > '9') throw invalid("Invalid JSON number");
                digits();
            }
            boolean decimal = false;
            if (take('.')) {
                decimal = true;
                requireDigits();
            }
            if (take('e') || take('E')) {
                decimal = true;
                if (!take('+')) take('-');
                requireDigits();
            }
            String literal = source.substring(start, position);
            try {
                if (!decimal) return Long.parseLong(literal);
                double value = Double.parseDouble(literal);
                if (!Double.isFinite(value)) throw invalid("Non-finite JSON number");
                return value;
            } catch (NumberFormatException ex) {
                throw invalid("JSON number is out of range");
            }
        }

        void digits() {
            while (position < source.length()
                    && source.charAt(position) >= '0'
                    && source.charAt(position) <= '9') position++;
        }

        void requireDigits() {
            int start = position;
            digits();
            if (start == position) throw invalid("Missing JSON number digits");
        }

        boolean take(char c) {
            if (position < source.length() && source.charAt(position) == c) {
                position++;
                return true;
            }
            return false;
        }

        void expect(char c) {
            space();
            if (!take(c)) throw invalid("Missing JSON delimiter");
        }

        IllegalArgumentException invalid(String message) {
            return new IllegalArgumentException(message + " at character " + position);
        }
    }
}
