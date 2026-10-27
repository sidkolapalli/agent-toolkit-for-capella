package com.sidkolapalli.capella.ai.presentation;

import static org.junit.Assert.*;

import org.junit.Test;
import com.sidkolapalli.capella.ai.presentation.TranscriptFormat.Formatted;
import com.sidkolapalli.capella.ai.presentation.TranscriptFormat.LineKind;

public class TranscriptFormatTest {
    @Test
    public void headingsEmphasisAndInlineCodeBecomeReadableNativeText() {
        Formatted value =
                TranscriptFormat.parse(
                        "## Plan\nUse **native `SystemFunction`** and *review* first.");
        assertEquals("Plan\nUse native SystemFunction and review first.", value.text());
        assertEquals(LineKind.HEADING, value.lines().get(0).kind());
        assertEquals(2, styleAt(value, "Plan").headingLevel());
        assertTrue(styleAt(value, "native").bold());
        assertTrue(styleAt(value, "SystemFunction").bold());
        assertTrue(styleAt(value, "SystemFunction").inlineCode());
        assertTrue(styleAt(value, "review").italic());
        assertDisjointStyles(value);
    }

    @Test
    public void exactFencedCodeCopyPreservesCrlfTabsTrailingSpacesAndBlankLines() {
        String source = "\tdef value = '**literal**'  \r\n\r\nreturn value\r\n";
        Formatted value =
                TranscriptFormat.parse("Before\r\n```groovy\r\n" + source + "```\r\nAfter");
        assertEquals(1, value.codeBlocks().size());
        var block = value.codeBlocks().get(0);
        assertEquals("groovy", block.language());
        assertEquals(source, block.source());
        assertEquals(
                source.replace("\r\n", "\n"),
                value.text().substring(block.offset(), block.offset() + block.length()));
        assertTrue(value.text().endsWith("After"));
        assertTrue(styleAt(value, "**literal**").code());
        assertFalse(styleAt(value, "**literal**").bold());
        assertDisjointStyles(value);
    }

    @Test
    public void longerFenceAllowsShorterLiteralFencesInsideCode() {
        Formatted value = TranscriptFormat.parse("````text\n```\n**keep this**\n````\nDone");
        assertEquals("```\n**keep this**\nDone", value.text());
        assertEquals("```\n**keep this**\n", value.codeBlocks().get(0).source());
    }

    @Test
    public void unfinishedCodeBlockStaysLiteralAndSelectable() {
        Formatted value = TranscriptFormat.parse("~~~groovy\n  println '**not bold**'  ");
        assertEquals("  println '**not bold**'  ", value.text());
        assertEquals(value.text(), value.codeBlocks().get(0).source());
        assertTrue(styleAt(value, "**not bold**").code());
    }

    @Test
    public void listsCarryHangingIndentWithoutLosingOrderedNumbers() {
        Formatted value =
                TranscriptFormat.parse(
                        "- Read model\n"
                            + "  * Inspect **selection**\n"
                            + "12. Review code\n"
                            + "> The model is unchanged.");
        assertEquals(
                "\u2022 Read model\n"
                    + "\u2022 Inspect selection\n"
                    + "12. Review code\n"
                    + "The model is unchanged.",
                value.text());
        assertEquals(LineKind.LIST, value.lines().get(0).kind());
        assertEquals(1, value.lines().get(1).indent());
        assertEquals(LineKind.LIST, value.lines().get(2).kind());
        assertEquals(LineKind.QUOTE, value.lines().get(3).kind());
        assertDisjointStyles(value);
    }

    @Test
    public void escapedAndUnmatchedMarkersRemainLiteral() {
        Formatted value =
                TranscriptFormat.parse("\\*literal\\* parameter_name unmatched ` and **unfinished");
        assertEquals("*literal* parameter_name unmatched ` and **unfinished", value.text());
        assertTrue(value.runs().isEmpty());
    }

    @Test
    public void multipleBackticksAllowBackticksInInlineCode() {
        Formatted value = TranscriptFormat.parse("Use ``a`b`` safely.");
        assertEquals("Use a`b safely.", value.text());
        assertTrue(styleAt(value, "a`b").inlineCode());
    }

    @Test
    public void linksShowTheirDestinationAndHtmlIsNeverInterpreted() {
        Formatted value =
                TranscriptFormat.parse(
                        "[Capella](https://example.invalid/docs)\n"
                            + "<script>alert('x')</script>\n"
                            + "![image](https://example.invalid/p.png)");
        assertEquals(
                "Capella (https://example.invalid/docs)\n"
                    + "<script>alert('x')</script>\n"
                    + "![image](https://example.invalid/p.png)",
                value.text());
        assertTrue(value.codeBlocks().isEmpty());
    }

    @Test
    public void emptyMessagesAndEmptyCodeBlocksAreWellFormed() {
        assertEquals("", TranscriptFormat.parse(null).text());
        Formatted value = TranscriptFormat.parse("```groovy\n```\nNext");
        assertEquals("Next", value.text());
        assertEquals("", value.codeBlocks().get(0).source());
        assertEquals(0, value.codeBlocks().get(0).length());
    }

    @Test
    public void largeSingleLineOutputIsPreservedWithoutFormattingSearches() {
        String source = "[**".repeat(20000);
        Formatted value = TranscriptFormat.parse(source);
        assertEquals(source, value.text());
        assertTrue(value.runs().isEmpty());
    }

    private static TranscriptFormat.Style styleAt(Formatted value, String text) {
        int position = value.text().indexOf(text);
        assertTrue(position >= 0);
        return value.runs().stream()
                .filter(run -> run.offset() <= position && position < run.offset() + run.length())
                .findFirst()
                .orElseThrow(() -> new AssertionError("No style for " + text))
                .style();
    }

    private static void assertDisjointStyles(Formatted value) {
        int end = 0;
        for (var run : value.runs()) {
            assertTrue(run.offset() >= end);
            assertTrue(run.length() > 0);
            end = run.offset() + run.length();
            assertTrue(end <= value.text().length());
        }
    }
}
