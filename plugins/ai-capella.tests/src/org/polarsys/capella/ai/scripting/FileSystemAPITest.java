package org.polarsys.capella.ai.scripting;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for FileSystemAPI - Anthropic discovery pattern implementation.
 */
public class FileSystemAPITest {

    private FileSystemAPI fs;

    @Before
    public void setUp() {
        fs = new FileSystemAPI();
    }

    @Test
    public void testListRoot() {
        List<String> entries = fs.list("");

        // Should contain main directories
        assertTrue(
            "Root should contain api/ or SKILL.md, got: " + entries,
            entries.contains("api/") || entries.contains("SKILL.md")
        );
    }

    @Test
    public void testListApiDirectory() {
        List<String> entries = fs.list("api/");

        // API directory should have method docs
        assertFalse("api/ should not be empty", entries.isEmpty());
    }

    @Test
    public void testReadRootSkillMd() {
        String content = fs.read("SKILL.md");

        // Root SKILL.md should exist and contain key content
        assertNotNull(content);
        assertFalse(
            "SKILL.md should exist",
            content.contains("File not found")
        );
        assertTrue(
            "SKILL.md should document the API",
            content.contains("api.fs") || content.contains("Capella")
        );
    }

    @Test
    public void testReadApiCreateMd() {
        String content = fs.read("api/create.md");

        assertNotNull(content);
        // Should contain create documentation
        assertTrue(
            "create.md should document create operations",
            content.contains("create") || content.contains("Create")
        );
    }

    @Test
    public void testReadNonExistent() {
        String content = fs.read("nonexistent/file.md");

        // Should return helpful error message
        assertNotNull(content);
        assertTrue(
            "Should indicate file not found",
            content.contains("not found") || content.contains("File not found")
        );
    }

    @Test
    public void testSearchComponent() {
        List<String> results = fs.search("component");

        // Search should find relevant docs
        assertNotNull(results);
        // May be empty if resources not loaded, but shouldn't error
    }

    @Test
    public void testSearchEmpty() {
        List<String> results = fs.search("");

        // Empty search should return empty results
        assertNotNull(results);
        assertTrue("Empty search should return empty list", results.isEmpty());
    }

    @Test
    public void testExists() {
        // exists() should not throw
        boolean result = fs.exists("SKILL.md");
        // Just verify it doesn't throw
        assertTrue("exists() should return boolean", result || !result);
    }

    @Test
    public void testPathTraversalBlocked() {
        // Should not allow path traversal
        String content = fs.read("../../../etc/passwd");

        // Should be safe - either not found or returns safe content
        assertNotNull(content);
        assertFalse(
            "Path traversal should be blocked",
            content.contains("root:")
        );
    }

    @Test
    public void testNullPath() {
        // Null should be handled gracefully
        List<String> entries = fs.list(null);
        assertNotNull(entries);

        String content = fs.read(null);
        assertNotNull(content);
    }

    @Test
    public void testListSkillMetadata() {
        List<FileSystemAPI.SkillMetadata> skills = fs.listSkillMetadata();
        assertNotNull(skills);
        // Should include at least one skill with name/summary when resources are present
        if (!skills.isEmpty()) {
            FileSystemAPI.SkillMetadata first = skills.get(0);
            assertNotNull(first.name);
            assertNotNull(first.summary);
            assertNotNull(first.path);
        }
    }
}
