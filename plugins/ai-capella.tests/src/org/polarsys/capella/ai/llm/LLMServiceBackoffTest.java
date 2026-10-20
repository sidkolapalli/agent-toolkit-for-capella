package org.polarsys.capella.ai.llm;

import static org.junit.Assert.*;

import org.junit.Test;

public class LLMServiceBackoffTest {

    @Test
    public void testIsRetryableStatus() {
        assertTrue(LLMService.isRetryableStatus(429));
        assertTrue(LLMService.isRetryableStatus(502));
        assertTrue(LLMService.isRetryableStatus(503));
        assertTrue(LLMService.isRetryableStatus(504));
        assertFalse(LLMService.isRetryableStatus(400));
        assertFalse(LLMService.isRetryableStatus(401));
        assertTrue(LLMService.isRetryableStatus(500));
        assertTrue(LLMService.isRetryableStatus(408));
        assertTrue(LLMService.isRetryableStatus(529));
    }

    @Test
    public void testComputeBackoffMillisUsesBase() {
        long first = LLMService.computeBackoffMillis(0, null);
        long second = LLMService.computeBackoffMillis(1, null);
        long third = LLMService.computeBackoffMillis(2, null);

        assertEquals(1000L, first);
        assertEquals(2000L, second);
        assertEquals(4000L, third);
    }

    @Test
    public void testComputeBackoffMillisHonorsRetryAfter() {
        long delay = LLMService.computeBackoffMillis(0, "5");
        assertEquals(5000L, delay);

        long lower = LLMService.computeBackoffMillis(1, "1");
        assertEquals(2000L, lower);
    }

    @Test
    public void testParseRetryAfterMillis() {
        assertEquals(3000L, LLMService.parseRetryAfterMillis("3"));
        assertEquals(-1L, LLMService.parseRetryAfterMillis("invalid"));
        assertEquals(-1L, LLMService.parseRetryAfterMillis(""));
        assertEquals(-1L, LLMService.parseRetryAfterMillis(null));
    }
}
