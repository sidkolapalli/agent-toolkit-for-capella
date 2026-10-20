package org.polarsys.capella.ai.model;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;

/** Unit tests for Finders utility class, particularly the Levenshtein distance algorithm. */
public class FindersTest {

    private Method levenshteinMethod;

    @Before
    public void setUp() throws Exception {
        // Access the private levenshteinDistance method via reflection
        levenshteinMethod =
                Finders.class.getDeclaredMethod("levenshteinDistance", String.class, String.class);
        levenshteinMethod.setAccessible(true);
    }

    private int levenshtein(String a, String b) throws Exception {
        return (int) levenshteinMethod.invoke(null, a, b);
    }

    // ========== Basic Levenshtein Tests ==========

    @Test
    public void testIdenticalStrings() throws Exception {
        assertEquals("Identical strings should have distance 0", 0, levenshtein("hello", "hello"));
        assertEquals(0, levenshtein("", ""));
        assertEquals(0, levenshtein("a", "a"));
    }

    @Test
    public void testEmptyStrings() throws Exception {
        assertEquals("Empty to 'abc' should be 3", 3, levenshtein("", "abc"));
        assertEquals("'abc' to empty should be 3", 3, levenshtein("abc", ""));
    }

    @Test
    public void testSingleCharacterDifference() throws Exception {
        // Substitution
        assertEquals(1, levenshtein("cat", "bat"));
        assertEquals(1, levenshtein("cat", "cut"));
        assertEquals(1, levenshtein("cat", "car"));

        // Insertion
        assertEquals(1, levenshtein("cat", "cats"));
        assertEquals(1, levenshtein("at", "cat"));

        // Deletion
        assertEquals(1, levenshtein("cats", "cat"));
        assertEquals(1, levenshtein("cat", "at"));
    }

    @Test
    public void testTypicalTypos() throws Exception {
        // Common typos should have small distance
        assertEquals(1, levenshtein("sensor", "sensr")); // missing 'o'
        assertEquals(1, levenshtein("component", "coponent")); // missing 'm'
        assertEquals(1, levenshtein("function", "funcion")); // missing 't'
        assertEquals(2, levenshtein("logical", "logcial")); // transposition-like
    }

    @Test
    public void testCaseSensitivity() throws Exception {
        // Case differences count as substitutions
        assertEquals(1, levenshtein("Sensor", "sensor"));
        // Distance exceeds MAX_EDIT_DISTANCE so it is capped at MAX_EDIT_DISTANCE + 1
        assertEquals(4, levenshtein("HELLO", "hello"));
    }

    @Test
    public void testCompletelyDifferent() throws Exception {
        // Completely different strings
        assertEquals(3, levenshtein("abc", "xyz"));
        assertEquals(4, levenshtein("java", "rust"));
    }

    @Test
    public void testSymmetry() throws Exception {
        // Levenshtein distance should be symmetric
        assertEquals(levenshtein("kitten", "sitting"), levenshtein("sitting", "kitten"));
        assertEquals(levenshtein("sensor", "sensr"), levenshtein("sensr", "sensor"));
    }

    // ========== Early Termination Tests ==========

    @Test
    public void testEarlyTerminationByLengthDifference() throws Exception {
        // If length difference > MAX_EDIT_DISTANCE (3), should return > 3
        int distance = levenshtein("a", "abcdefgh");
        assertTrue("Large length difference should trigger early termination", distance > 3);
    }

    @Test
    public void testEarlyTerminationByDistance() throws Exception {
        // Strings with distance > MAX_EDIT_DISTANCE should return > 3
        int distance = levenshtein("abcd", "wxyz");
        assertTrue("Distance of 4 should exceed threshold", distance > 3);
    }

    // ========== Edge Cases ==========

    @Test
    public void testSingleCharacterStrings() throws Exception {
        assertEquals(0, levenshtein("a", "a"));
        assertEquals(1, levenshtein("a", "b"));
        assertEquals(1, levenshtein("a", ""));
        assertEquals(1, levenshtein("", "a"));
    }

    @Test
    public void testLongStringsWithSmallDistance() throws Exception {
        // Long strings with only 1 difference
        String a = "ThisIsAVeryLongComponentName";
        String b = "ThisIsAVeryLongComponantName"; // 'e' -> 'a'
        assertEquals(1, levenshtein(a, b));
    }

    @Test
    public void testRepeatedCharacters() throws Exception {
        assertEquals(1, levenshtein("aaa", "aaaa"));
        assertEquals(2, levenshtein("aaa", "aaaaa"));
        assertEquals(3, levenshtein("aaa", "aaaaaa"));
    }

    // ========== Known Distance Values ==========

    @Test
    public void testKnownDistances() throws Exception {
        // Classic example: kitten -> sitting
        assertEquals(3, levenshtein("kitten", "sitting"));

        // Saturday -> Sunday
        assertEquals(3, levenshtein("saturday", "sunday"));
    }

    @Test
    public void testSpaceOptimization() throws Exception {
        // Test that shorter string is handled correctly regardless of order
        int d1 = levenshtein("short", "muchlongerstring");
        int d2 = levenshtein("muchlongerstring", "short");
        assertEquals("Distance should be same regardless of order", d1, d2);
    }
}
