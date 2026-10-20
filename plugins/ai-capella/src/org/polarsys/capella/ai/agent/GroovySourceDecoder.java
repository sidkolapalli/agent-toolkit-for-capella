package org.polarsys.capella.ai.agent;

import groovy.lang.GroovyShell;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Restores privacy values into quoted source without compiling or evaluating a proposal. */
public final class GroovySourceDecoder {
    private static final Pattern TOKEN = Pattern.compile("\\b(?:ELEM|UUID)_\\d{4}\\b");
    private static final int MAX_SOURCE_CHARS = 120_000;

    private GroovySourceDecoder() {}

    /**
     * Protect quoted data in outbound proposal history. This uses only the trusted lexer and escape
     * decoder: it never compiles, constructs or evaluates any proposed class/expression. Literal
     * payloads are encoded as complete data values, including text segments of GStrings. Comments
     * are omitted from outbound history because they can contain private free text.
     */
    public static String encode(String source, UnaryOperator<String> encodeData) {
        if (source == null || source.length() > MAX_SOURCE_CHARS)
            throw new IllegalArgumentException("Proposal source exceeds its encoding limit");
        java.util.Objects.requireNonNull(encodeData, "Privacy data encoder required");
        List<SourceToken> tokens = lex(source);
        int[] offsets = utf16Offsets(source);
        List<Replacement> replacements = new ArrayList<>();
        Deque<String> interpolated = new ArrayDeque<>();
        for (SourceToken token : tokens) {
            String text = token.text;
            if (text.startsWith("//") || text.startsWith("/*") || text.startsWith("#!")) {
                replacements.add(
                        new Replacement(
                                offsets[token.start],
                                offsets[token.stop + 1],
                                text.replaceAll("[^\\r\\n]", " ")));
                continue;
            }
            String quote = null;
            int start = 0, end = text.length();
            if (token.kind == TokenKind.STRING) {
                quote = quote(text);
                start = quote.length();
                end -= quote.length();
            } else if (token.kind == TokenKind.BEGIN) {
                quote = quote(text);
                interpolated.push(quote);
                start = quote.length();
                end--;
            } else if (token.kind == TokenKind.PART || token.kind == TokenKind.END) {
                if (interpolated.isEmpty())
                    throw new IllegalArgumentException("Unrecognized proposal interpolation");
                quote = interpolated.peek();
                if (token.kind == TokenKind.PART) end--;
                else end -= quote.length();
            }
            if (quote == null) continue;
            if (start > end
                    || end < 0
                    || (token.kind == TokenKind.BEGIN || token.kind == TokenKind.PART)
                            && !text.endsWith("$"))
                throw new IllegalArgumentException("Unrecognized proposal literal boundary");
            String raw = unescape(text.substring(start, end));
            String encoded = encodeData.apply(raw);
            if (encoded == null || encoded.length() > MAX_SOURCE_CHARS)
                throw new IllegalArgumentException("Encoded proposal value exceeds its limit");
            // Canonical escaping protects both quote boundaries and literal dollar signs.
            String replacement =
                    text.substring(0, start) + escape(encoded, style(quote)) + text.substring(end);
            replacements.add(
                    new Replacement(offsets[token.start], offsets[token.stop + 1], replacement));
            if (token.kind == TokenKind.END) interpolated.pop();
        }
        if (!interpolated.isEmpty())
            throw new IllegalArgumentException("Unterminated proposal interpolation");
        StringBuilder output = new StringBuilder();
        int previous = 0;
        for (Replacement replacement : replacements) {
            if (replacement.start < previous)
                throw new IllegalArgumentException("Overlapping proposal source literals");
            output.append(source, previous, replacement.start).append(replacement.value);
            previous = replacement.end;
            if (output.length() > MAX_SOURCE_CHARS)
                throw new IllegalArgumentException("Encoded proposal source exceeds its limit");
        }
        output.append(source, previous, source.length());
        if (output.length() > MAX_SOURCE_CHARS)
            throw new IllegalArgumentException("Encoded proposal source exceeds its limit");
        return output.toString();
    }

    private static String quote(String text) {
        if (text.startsWith("\"\"\"")) return "\"\"\"";
        if (text.startsWith("'''")) return "'''";
        if (text.startsWith("\"")) return "\"";
        if (text.startsWith("'")) return "'";
        throw new IllegalArgumentException(
                "Proposal history requires quoted Groovy literals; slashy literals are"
                    + " unsupported");
    }

    private static String unescape(String text) {
        try {
            // The parser's fixed escape helper decodes data only; no AST transform or script can
            // run.
            Class<?> strings =
                    Class.forName(
                            "org.apache.groovy.parser.antlr4.util.StringUtils",
                            true,
                            GroovyShell.class.getClassLoader());
            String normalized =
                    (String) strings.getMethod("removeCR", String.class).invoke(null, text);
            return (String)
                    strings.getMethod("replaceEscapes", String.class, int.class)
                            .invoke(null, normalized, strings.getField("NONE_SLASHY").getInt(null));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError invalid) {
            throw new IllegalArgumentException(
                    "Proposal literal cannot be safely encoded for provider history");
        }
    }

    public static String decode(String source, UnaryOperator<String> decodeText) {
        if (source == null || source.length() > MAX_SOURCE_CHARS)
            throw new IllegalArgumentException("Proposal source exceeds its restoration limit");
        java.util.Objects.requireNonNull(decodeText, "Privacy decoder required");
        Matcher candidates = TOKEN.matcher(source);
        boolean restorationNeeded = false;
        while (candidates.find())
            if (!candidates.group().equals(decodeText.apply(candidates.group()))) {
                restorationNeeded = true;
                break;
            }
        if (!restorationNeeded) return source;

        List<SourceToken> tokens = lex(source);
        // ANTLR CharStreams uses code-point offsets; Java substring uses UTF-16 offsets.
        int[] offsets = utf16Offsets(source);
        List<Replacement> replacements = new ArrayList<>();
        Deque<Style> interpolated = new ArrayDeque<>();
        for (SourceToken token : tokens) {
            TokenKind type = token.kind;
            Style style = null;
            if (type == TokenKind.STRING) style = style(token.text);
            else if (type == TokenKind.BEGIN) {
                style = style(token.text);
                interpolated.push(style);
            } else if (type == TokenKind.PART || type == TokenKind.END) {
                if (interpolated.isEmpty())
                    throw new IllegalArgumentException("Unrecognized tokenized interpolation");
                style = interpolated.peek();
            }
            String text = token.text;
            Matcher matcher = TOKEN.matcher(text);
            StringBuilder changed = new StringBuilder();
            int previous = 0;
            boolean replaced = false;
            while (matcher.find()) {
                String value = decodeText.apply(matcher.group());
                if (matcher.group().equals(value)) continue;
                if (value == null || value.length() > MAX_SOURCE_CHARS)
                    throw new IllegalArgumentException("Restored source value exceeds its limit");
                if (style == null) {
                    // Preserve comments as comments; raw names containing line breaks must not
                    // alter their structure.
                    if (type == TokenKind.COMMENT || token.channel != 0) continue;
                    throw new IllegalArgumentException(
                            "Privacy values in proposal code must use quoted Groovy literals");
                }
                if (style == Style.UNSUPPORTED)
                    throw new IllegalArgumentException(
                            "Tokenized slashy literals are unsupported; use quoted Groovy"
                                + " literals");
                changed.append(text, previous, matcher.start()).append(escape(value, style));
                previous = matcher.end();
                replaced = true;
            }
            if (replaced) {
                changed.append(text, previous, text.length());
                replacements.add(
                        new Replacement(
                                offsets[token.start], offsets[token.stop + 1], changed.toString()));
            }
            if (type == TokenKind.END) interpolated.pop();
        }
        if (!interpolated.isEmpty())
            throw new IllegalArgumentException("Unterminated tokenized interpolation");
        StringBuilder output = new StringBuilder();
        int previous = 0;
        for (Replacement replacement : replacements) {
            if (replacement.start < previous)
                throw new IllegalArgumentException("Overlapping tokenized source literals");
            output.append(source, previous, replacement.start).append(replacement.value);
            previous = replacement.end;
            if (output.length() > MAX_SOURCE_CHARS)
                throw new IllegalArgumentException("Restored proposal source exceeds its limit");
        }
        output.append(source, previous, source.length());
        if (output.length() > MAX_SOURCE_CHARS)
            throw new IllegalArgumentException("Restored proposal source exceeds its limit");
        return output.toString();
    }

    private static Style style(String text) {
        if (text.startsWith("\"\"\"")) return Style.DOUBLE;
        if (text.startsWith("'''")) return Style.SINGLE;
        if (text.startsWith("\"")) return Style.DOUBLE;
        if (text.startsWith("'")) return Style.SINGLE;
        return Style.UNSUPPORTED;
    }

    private static String escape(String value, Style style) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    escaped.append("\\\\");
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
                case '\b':
                    escaped.append("\\b");
                    break;
                case '\f':
                    escaped.append("\\f");
                    break;
                case '\'':
                    escaped.append(style == Style.SINGLE ? "\\'" : "'");
                    break;
                case '"':
                    escaped.append(style == Style.DOUBLE ? "\\\"" : "\"");
                    break;
                case '$':
                    escaped.append(style == Style.DOUBLE ? "\\$" : "$");
                    break;
                default:
                    if (c < 32)
                        escaped.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    else escaped.append(c);
            }
        }
        return escaped.toString();
    }

    private static int[] utf16Offsets(String source) {
        int[] offsets = new int[source.codePointCount(0, source.length()) + 1];
        int point = 0;
        for (int index = 0; index < source.length(); ) {
            offsets[point++] = index;
            index += Character.charCount(source.codePointAt(index));
        }
        offsets[point] = source.length();
        return offsets;
    }

    private static List<SourceToken> lex(String source) {
        try {
            // Capella's wrapped Groovy bundle does not export these implementation packages.
            // Resolve only fixed, trusted lexer classes through Groovy's own bundle loader.
            ClassLoader loader = GroovyShell.class.getClassLoader();
            Class<?> streams =
                    Class.forName("groovyjarjarantlr4.v4.runtime.CharStreams", true, loader);
            Class<?> stream =
                    Class.forName("groovyjarjarantlr4.v4.runtime.CharStream", false, loader);
            Class<?> listener =
                    Class.forName(
                            "groovyjarjarantlr4.v4.runtime.ANTLRErrorListener", false, loader);
            Class<?> lexerType =
                    Class.forName("org.apache.groovy.parser.antlr4.GroovyLexer", true, loader);
            Class<?> tokenType =
                    Class.forName("groovyjarjarantlr4.v4.runtime.Token", false, loader);
            Constructor<?> constructor = lexerType.getConstructor(stream);
            Object lexer =
                    constructor.newInstance(
                            streams.getMethod("fromString", String.class).invoke(null, source));
            lexerType.getMethod("removeErrorListeners").invoke(lexer);
            Object errors =
                    Proxy.newProxyInstance(
                            loader,
                            new Class<?>[] {listener},
                            (proxy, method, arguments) -> {
                                if ("syntaxError".equals(method.getName()))
                                    throw new IllegalArgumentException(
                                            "Invalid tokenized source literal");
                                if ("toString".equals(method.getName()))
                                    return "Proposal lexer error listener";
                                if ("hashCode".equals(method.getName()))
                                    return System.identityHashCode(proxy);
                                if ("equals".equals(method.getName())) return proxy == arguments[0];
                                return null;
                            });
            lexerType.getMethod("addErrorListener", listener).invoke(lexer, errors);
            int string = lexerType.getField("StringLiteral").getInt(null);
            int begin = lexerType.getField("GStringBegin").getInt(null);
            int part = lexerType.getField("GStringPart").getInt(null);
            int end = lexerType.getField("GStringEnd").getInt(null);
            int newline = lexerType.getField("NL").getInt(null);
            int shellComment = lexerType.getField("SH_COMMENT").getInt(null);
            Method type = tokenType.getMethod("getType");
            Method text = tokenType.getMethod("getText");
            Method start = tokenType.getMethod("getStartIndex");
            Method stop = tokenType.getMethod("getStopIndex");
            Method channel = tokenType.getMethod("getChannel");
            List<SourceToken> result = new ArrayList<>();
            for (Object token : (List<?>) lexerType.getMethod("getAllTokens").invoke(lexer)) {
                int value = (Integer) type.invoke(token);
                TokenKind kind =
                        value == string
                                ? TokenKind.STRING
                                : value == begin
                                        ? TokenKind.BEGIN
                                        : value == part
                                                ? TokenKind.PART
                                                : value == end
                                                        ? TokenKind.END
                                                        : value == newline || value == shellComment
                                                                ? TokenKind.COMMENT
                                                                : TokenKind.OTHER;
                result.add(
                        new SourceToken(
                                kind,
                                (String) text.invoke(token),
                                (Integer) start.invoke(token),
                                (Integer) stop.invoke(token),
                                (Integer) channel.invoke(token)));
            }
            return result;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError invalid) {
            // Do not attach lexer diagnostics: they can include private proposal source.
            throw new IllegalArgumentException(
                    "Tokenized proposal contains an invalid or unsupported source literal");
        }
    }

    private enum Style {
        SINGLE,
        DOUBLE,
        UNSUPPORTED
    }

    private enum TokenKind {
        STRING,
        BEGIN,
        PART,
        END,
        COMMENT,
        OTHER
    }

    private record SourceToken(TokenKind kind, String text, int start, int stop, int channel) {}

    private record Replacement(int start, int end, String value) {}
}
