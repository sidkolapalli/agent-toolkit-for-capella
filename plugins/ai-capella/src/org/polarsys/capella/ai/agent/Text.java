package org.polarsys.capella.ai.agent;

/** Bounded text for tool results and receipts. */
final class Text {
    private Text() {}

    /** Cuts at {@code max} characters without splitting a surrogate pair; null becomes empty. */
    static String clip(String text, int max) {
        if (text == null) return "";
        if (text.length() <= max) return text;
        int end = max;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + " [truncated]";
    }

    /** One line of at most {@code max} characters, control characters replaced by spaces. */
    static String oneLine(String text, int max) {
        if (text == null) return null;
        String line = text.replaceAll("[\\r\\n\\t\\p{Cntrl}]", " ");
        return line.length() > max ? line.substring(0, max) + "…" : line;
    }
}
