package org.polarsys.capella.ai.presentation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A text-only Markdown subset: no HTML, scripts, images, or navigation actions. */
public final class TranscriptFormat {
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})[ \\t]+(.*)$");
    private static final Pattern LIST = Pattern.compile("^( *)([-+*]|[0-9]{1,9}[.)])[ \\t]+(.*)$");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]\\r\\n]+)\\]\\(([^\\s()]+)\\)");
    private static final Style PLAIN = new Style(false, false, false, false, 0);
    private static final Style CODE = new Style(false, false, true, false, 0);

    public enum LineKind {
        PROSE,
        HEADING,
        LIST,
        QUOTE,
        CODE
    }

    public record Style(
            boolean bold, boolean italic, boolean code, boolean inlineCode, int headingLevel) {
        Style bolded() {
            return new Style(true, italic, code, inlineCode, headingLevel);
        }

        Style italicized() {
            return new Style(bold, true, code, inlineCode, headingLevel);
        }

        Style inline() {
            return new Style(bold, italic, true, true, headingLevel);
        }
    }

    public record Run(int offset, int length, Style style) {}

    public record Line(int offset, int length, LineKind kind, int indent) {}

    /** Source retains original code whitespace and line endings for exact copying. */
    public record CodeBlock(int offset, int length, String language, String source) {}

    public record Formatted(
            String text, List<Run> runs, List<Line> lines, List<CodeBlock> codeBlocks) {
        public Formatted {
            runs = List.copyOf(runs);
            lines = List.copyOf(lines);
            codeBlocks = List.copyOf(codeBlocks);
        }
    }

    private TranscriptFormat() {}

    public static Formatted parse(String message) {
        Builder output = new Builder();
        String input = message == null ? "" : message;
        char fenceCharacter = 0;
        int fenceLength = 0, codeStart = 0;
        String language = "";
        StringBuilder codeSource = new StringBuilder();
        int cursor = 0;
        while (cursor < input.length()) {
            int end = cursor;
            while (end < input.length() && input.charAt(end) != '\r' && input.charAt(end) != '\n')
                end++;
            int next = end;
            if (next < input.length()) {
                if (input.charAt(next++) == '\r'
                        && next < input.length()
                        && input.charAt(next) == '\n') next++;
            }
            String content = input.substring(cursor, end);
            String delimiter = input.substring(end, next);
            Matcher fence = FENCE.matcher(content);
            if (fenceCharacter != 0) {
                if (fence.matches()
                        && fence.group(1).charAt(0) == fenceCharacter
                        && fence.group(1).length() >= fenceLength
                        && fence.group(2).isBlank()) {
                    output.blocks.add(
                            new CodeBlock(
                                    codeStart,
                                    output.text.length() - codeStart,
                                    language,
                                    codeSource.toString()));
                    fenceCharacter = 0;
                } else {
                    int offset = output.text.length();
                    output.append(content, CODE);
                    output.lines.add(new Line(offset, content.length(), LineKind.CODE, 0));
                    if (!delimiter.isEmpty()) output.append("\n", CODE);
                    codeSource.append(content).append(delimiter);
                }
            } else if (fence.matches()
                    && !(fence.group(1).charAt(0) == '`' && fence.group(2).contains("`"))) {
                fenceCharacter = fence.group(1).charAt(0);
                fenceLength = fence.group(1).length();
                language = fence.group(2).strip();
                codeStart = output.text.length();
                codeSource.setLength(0);
            } else {
                proseLine(content, output);
                if (!delimiter.isEmpty()) output.append("\n", PLAIN);
            }
            cursor = next;
        }
        if (fenceCharacter != 0) {
            output.blocks.add(
                    new CodeBlock(
                            codeStart,
                            output.text.length() - codeStart,
                            language,
                            codeSource.toString()));
        }
        return new Formatted(output.text.toString(), output.runs, output.lines, output.blocks);
    }

    private static void proseLine(String content, Builder output) {
        int offset = output.text.length();
        LineKind kind = LineKind.PROSE;
        int indent = 0;
        Style style = PLAIN;
        Matcher heading = HEADING.matcher(content);
        Matcher list = LIST.matcher(content);
        if (heading.matches()) {
            content = heading.group(2).replaceFirst("[ \\t]+#+[ \\t]*$", "");
            style = new Style(true, false, false, false, heading.group(1).length());
            kind = LineKind.HEADING;
        } else if (list.matches()) {
            kind = LineKind.LIST;
            indent = Math.min(8, list.group(1).length() / 2);
            String marker = list.group(2);
            output.append((marker.matches("[-+*]") ? "\u2022" : marker) + " ", PLAIN.bolded());
            content = list.group(3);
        } else if (content.startsWith("> ")) {
            kind = LineKind.QUOTE;
            indent = 1;
            content = content.substring(2);
            style = PLAIN.italicized();
        }
        inline(content, style, output, 0);
        output.lines.add(new Line(offset, output.text.length() - offset, kind, indent));
    }

    private static void inline(String content, Style base, Builder output, int depth) {
        // Extremely long single-line output stays selectable without costly
        // repeated delimiter searches. Nothing is truncated or interpreted.
        if (depth >= 12 || content.length() > 8192) {
            output.append(content, base);
            return;
        }
        int position = 0, plainStart = 0;
        while (position < content.length()) {
            char character = content.charAt(position);
            if (character == '\\'
                    && position + 1 < content.length()
                    && "\\`*_{}[]()>#+-.!".indexOf(content.charAt(position + 1)) >= 0) {
                output.append(content.substring(plainStart, position), base);
                output.append(content.substring(position + 1, position + 2), base);
                position += 2;
                plainStart = position;
                continue;
            }
            if (character == '`') {
                int ticks = 1;
                while (position + ticks < content.length()
                        && content.charAt(position + ticks) == '`') ticks++;
                String marker = "`".repeat(ticks);
                int close = closing(content, marker, position + ticks);
                if (close >= 0) {
                    output.append(content.substring(plainStart, position), base);
                    output.append(content.substring(position + ticks, close), base.inline());
                    position = close + ticks;
                    plainStart = position;
                    continue;
                }
                position += ticks;
                continue;
            }
            String marker =
                    content.startsWith("**", position)
                            ? "**"
                            : content.startsWith("__", position)
                                            && (position == 0
                                                    || !Character.isLetterOrDigit(
                                                            content.charAt(position - 1)))
                                    ? "__"
                                    : character == '*' ? "*" : null;
            if (marker != null) {
                int close = closing(content, marker, position + marker.length());
                if (close > position + marker.length()) {
                    output.append(content.substring(plainStart, position), base);
                    inline(
                            content.substring(position + marker.length(), close),
                            marker.length() == 2 ? base.bolded() : base.italicized(),
                            output,
                            depth + 1);
                    position = close + marker.length();
                    plainStart = position;
                    continue;
                }
            }
            if (character == '[' && (position == 0 || content.charAt(position - 1) != '!')) {
                Matcher link = LINK.matcher(content);
                link.region(position, content.length());
                if (link.lookingAt()) {
                    output.append(content.substring(plainStart, position), base);
                    inline(link.group(1), base, output, depth + 1);
                    output.append(" (" + link.group(2) + ")", base);
                    position = link.end();
                    plainStart = position;
                    continue;
                }
            }
            position++;
        }
        output.append(content.substring(plainStart), base);
    }

    private static int closing(String content, String marker, int start) {
        int candidate = content.indexOf(marker, start);
        while (candidate >= 0) {
            int escapes = 0;
            for (int index = candidate - 1; index >= 0 && content.charAt(index) == '\\'; index--)
                escapes++;
            boolean runBoundary =
                    marker.charAt(0) != '`'
                            || ((candidate == 0 || content.charAt(candidate - 1) != '`')
                                    && (candidate + marker.length() == content.length()
                                            || content.charAt(candidate + marker.length()) != '`'));
            if ((escapes & 1) == 0 && runBoundary) return candidate;
            candidate = content.indexOf(marker, candidate + marker.length());
        }
        return -1;
    }

    private static final class Builder {
        final StringBuilder text = new StringBuilder();
        final List<Run> runs = new ArrayList<>();
        final List<Line> lines = new ArrayList<>();
        final List<CodeBlock> blocks = new ArrayList<>();

        void append(String value, Style style) {
            if (value.isEmpty()) return;
            int offset = text.length();
            text.append(value);
            if (style.equals(PLAIN)) return;
            if (!runs.isEmpty()) {
                Run previous = runs.get(runs.size() - 1);
                if (previous.offset() + previous.length() == offset
                        && previous.style().equals(style)) {
                    runs.set(
                            runs.size() - 1,
                            new Run(previous.offset(), previous.length() + value.length(), style));
                    return;
                }
            }
            runs.add(new Run(offset, value.length(), style));
        }
    }
}
