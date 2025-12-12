package org.polarsys.capella.ai.scripting;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for ScriptLogger.
 */
public class ScriptLoggerTest {

    private ScriptLogger logger;

    @Before
    public void setUp() {
        logger = new ScriptLogger();
    }

    @Test
    public void testInfoLogging() {
        logger.info("Test message");

        List<String> messages = logger.getMessages();
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("[INFO]"));
        assertTrue(messages.get(0).contains("Test message"));
    }

    @Test
    public void testWarnLogging() {
        logger.warn("Warning message");

        List<String> messages = logger.getMessages();
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("[WARN]"));
    }

    @Test
    public void testErrorLogging() {
        logger.error("Error message");

        List<String> messages = logger.getMessages();
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("[ERROR]"));
    }

    @Test
    public void testDebugLogging() {
        logger.debug("Debug message");

        List<String> messages = logger.getMessages();
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("[DEBUG]"));
    }

    @Test
    public void testPrintln() {
        logger.println("Printed message");
        logger.println(123);
        logger.println(null);

        List<String> messages = logger.getMessages();
        assertEquals(3, messages.size());
        assertTrue(messages.get(0).contains("Printed message"));
        assertTrue(messages.get(1).contains("123"));
        assertTrue(messages.get(2).contains("null"));
    }

    @Test
    public void testPrint() {
        logger.print("Hello");
        logger.print(" World");

        List<String> messages = logger.getMessages();
        assertEquals(2, messages.size());
    }

    @Test
    public void testMultipleMessages() {
        logger.info("First");
        logger.warn("Second");
        logger.error("Third");

        List<String> messages = logger.getMessages();
        assertEquals(3, messages.size());
        assertEquals(3, logger.size());
        assertTrue(logger.hasMessages());
    }

    @Test
    public void testClear() {
        logger.info("Message 1");
        logger.info("Message 2");
        assertEquals(2, logger.size());

        logger.clear();

        assertEquals(0, logger.size());
        assertFalse(logger.hasMessages());
    }

    @Test
    public void testEmptyLogger() {
        assertFalse(logger.hasMessages());
        assertEquals(0, logger.size());
        assertTrue(logger.getMessages().isEmpty());
    }

    @Test
    public void testNullMessageHandling() {
        logger.info(null);

        List<String> messages = logger.getMessages();
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("null"));
    }

    @Test
    public void testCustomLevel() {
        logger.log("CUSTOM", "Custom level message");

        List<String> messages = logger.getMessages();
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("[CUSTOM]"));
    }

    @Test
    public void testMessageTruncation() {
        // Create a very long message (over 2000 chars)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append("0123456789");
        }
        String longMessage = sb.toString();
        assertEquals(3000, longMessage.length());

        logger.info(longMessage);

        List<String> messages = logger.getMessages();
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("[truncated]"));
        assertTrue(messages.get(0).length() < longMessage.length() + 50);
    }

    @Test
    public void testGetMessagesReturnsCopy() {
        logger.info("Original");
        List<String> messages1 = logger.getMessages();

        logger.info("Added");
        List<String> messages2 = logger.getMessages();

        assertEquals(1, messages1.size());
        assertEquals(2, messages2.size());
    }

    @Test
    public void testToString() {
        logger.info("msg1");
        logger.info("msg2");

        String str = logger.toString();
        assertTrue(str.contains("2 messages"));
    }

    @Test
    public void testThreadSafety() throws InterruptedException {
        // Basic concurrency test
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                logger.info("Thread 1: " + i);
            }
        });
        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                logger.info("Thread 2: " + i);
            }
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        assertEquals(200, logger.size());
    }
}
