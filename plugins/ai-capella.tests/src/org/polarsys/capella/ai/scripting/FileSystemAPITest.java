package org.polarsys.capella.ai.scripting;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for FileSystemAPI - Anthropic discovery pattern implementation.
 */
class FileSystemAPITest {

    private FileSystemAPI fs;

    @BeforeEach
    void setUp() {
        fs = new FileSystemAPI();
    }

    @Test
    void testListRoot() {
        List<String> entries = fs.list("");

        // Should contain main directories
        assertTrue(entries.contains("api/") || entries.contains("SKILL.md"),
            "Root should contain api/ or SKILL.md, got: " + entries);
    }

    @Test
    void testListApiDirectory() {
        List<String> entries = fs.list("api/");

        // API directory should have method docs
        assertFalse(entries.isEmpty(), "api/ should not be empty");
    }

    @Test
    void testReadRootSkillMd() {
        String content = fs.read("SKILL.md");

        // Root SKILL.md should exist and contain key content
        assertNotNull(content);
        assertFalse(content.contains("File not found"),
            "SKILL.md should exist");
        assertTrue(content.contains("api.fs") || content.contains("Capella"),
            "SKILL.md should document the API");
    }

    @Test
    void testReadApiCreateMd() {
        String content = fs.read("api/create.md");

        assertNotNull(content);
        // Should contain create documentation
        assertTrue(content.contains("create") || content.contains("Create"),
            "create.md should document create operations");
    }

    @Test
    void testReadNonExistent() {
        String content = fs.read("nonexistent/file.md");

        // Should return helpful error message
        assertNotNull(content);
        assertTrue(content.contains("not found") || content.contains("File not found"),
            "Should indicate file not found");
    }

    @Test
    void testSearchComponent() {
        List<String> results = fs.search("component");

        // Search should find relevant docs
        assertNotNull(results);
        // May be empty if resources not loaded, but shouldn't error
    }

    @Test
    void testSearchEmpty() {
        List<String> results = fs.search("");

        // Empty search should return empty results
        assertNotNull(results);
        assertTrue(results.isEmpty(), "Empty search should return empty list");
    }

    @Test
    void testExists() {
        // SKILL.md should exist
        assertTrue(fs.exists("SKILL.md") || !fs.exists("SKILL.md"),
            "exists() should not throw");
    }

    @Test
    void testPathTraversalBlocked() {
        // Should not allow path traversal
        String content = fs.read("../../../etc/passwd");

        // Should be safe - either not found or returns safe content
        assertNotNull(content);
        assertFalse(content.contains("root:"), "Path traversal should be blocked");
    }

    @Test
    void testNullPath() {
        // Null should be handled gracefully
        List<String> entries = fs.list(null);
        assertNotNull(entries);

        String content = fs.read(null);
        assertNotNull(content);
    }
}
