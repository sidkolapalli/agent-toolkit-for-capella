package org.polarsys.capella.ai.agent;

import static org.junit.Assert.*;

import org.junit.Test;

/** Bounded text shared by the tools and receipts sent back to the model. */
public class TextTest {
    @Test
    public void clipMarksTruncationAndNeverSplitsASurrogatePair() {
        assertEquals("", Text.clip(null, 5));
        assertEquals("short", Text.clip("short", 5));
        assertEquals("abcd [truncated]", Text.clip("abcdef", 4));
        assertEquals("ab [truncated]", Text.clip("ab😀cd", 3));
    }

    @Test
    public void oneLineFlattensControlCharactersAndKeepsNull() {
        assertNull(Text.oneLine(null, 5));
        assertEquals("a b c", Text.oneLine("a\nb\tc", 10));
        assertEquals("abc…", Text.oneLine("abcdef", 3));
    }
}
