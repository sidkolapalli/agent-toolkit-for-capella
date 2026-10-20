package org.polarsys.capella.ai.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON parser to decode LLM tool plans without external deps. */
final class SimpleJson {
    private SimpleJson() {}

    static Object parse(String json) {
        Parser parser = new Parser(json);
        return parser.parseDocument();
    }

    private static final class Parser {
        private final String text;
        private final int length;
        private int pos;

        Parser(String text) {
            this.text = text;
            this.length = text.length();
        }

        Object parseDocument() {
            Object value = parseValue();
            skipWs();
            if (pos != length) {
                throw error("Unexpected trailing input");
            }
            return value;
        }

        Object parseValue() {
            skipWs();
            if (pos >= length) {
                throw error("Unexpected end of input");
            }
            char c = text.charAt(pos);
            if (c == '{') {
                return parseObject();
            }
            if (c == '[') {
                return parseArray();
            }
            if (c == '"') {
                return parseString();
            }
            if (c == '-' || (c >= '0' && c <= '9')) {
                return parseNumber();
            }
            if (peek("true")) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (peek("false")) {
                pos += 5;
                return Boolean.FALSE;
            }
            if (peek("null")) {
                pos += 4;
                return null;
            }
            throw error("Unexpected character: " + c);
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (consume('}')) {
                return map;
            }
            while (true) {
                String key = parseString();
                skipWs();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWs();
                if (consume('}')) {
                    break;
                }
                expect(',');
            }
            return map;
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (consume(']')) {
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWs();
                if (consume(']')) {
                    break;
                }
                expect(',');
            }
            return list;
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < length) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= length) {
                        throw error("Invalid escape sequence");
                    }
                    char e = text.charAt(pos++);
                    switch (e) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            if (pos + 4 > length) {
                                throw error("Invalid unicode escape");
                            }
                            String hex = text.substring(pos, pos + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            pos += 4;
                            break;
                        default:
                            throw error("Unknown escape: \\" + e);
                    }
                } else {
                    if (c < 0x20) {
                        throw error("Unescaped control character in string");
                    }
                    sb.append(c);
                }
            }
            throw error("Unterminated string");
        }

        private Number parseNumber() {
            int start = pos;
            if (text.charAt(pos) == '-') {
                pos++;
            }
            while (pos < length && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
            if (pos < length && text.charAt(pos) == '.') {
                pos++;
                while (pos < length && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < length && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                pos++;
                if (pos < length && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                    pos++;
                }
                while (pos < length && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
            }
            String num = text.substring(start, pos);
            try {
                if (num.indexOf('.') >= 0 || num.indexOf('e') >= 0 || num.indexOf('E') >= 0) {
                    return Double.parseDouble(num);
                }
                long value = Long.parseLong(num);
                if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
                    return (int) value;
                }
                return value;
            } catch (NumberFormatException e) {
                throw error("Invalid number: " + num);
            }
        }

        private void skipWs() {
            while (pos < length) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private void expect(char c) {
            skipWs();
            if (pos >= length || text.charAt(pos) != c) {
                throw error("Expected '" + c + "'");
            }
            pos++;
        }

        private boolean consume(char c) {
            skipWs();
            if (pos < length && text.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        private boolean peek(String s) {
            return text.startsWith(s, pos);
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at position " + pos);
        }
    }
}
