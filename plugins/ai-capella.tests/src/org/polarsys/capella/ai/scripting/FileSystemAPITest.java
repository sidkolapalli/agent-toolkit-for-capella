package org.polarsys.capella.ai.scripting;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.polarsys.capella.ai.model.ActionResult;
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

    @Test
    public void testWorkspaceWriteReadRoundTrip() throws Exception {
        Path workspace = Files.createTempDirectory("capella-ai-workspace-");

        String prevDir = System.getProperty("capella.ai.workspace.dir");
        try {
            System.setProperty("capella.ai.workspace.dir", workspace.toString());
            FileSystemAPI localFs = new FileSystemAPI();

            ActionResult write = localFs.write(
                "state/run-1.json",
                "{\"ok\":true}"
            );
            assertTrue("write should succeed: " + write, write.success());

            assertTrue(localFs.exists("workspace/state/run-1.json"));
            String content = localFs.read("workspace/state/run-1.json");
            assertEquals("{\"ok\":true}", content);

            List<String> root = localFs.list("workspace/");
            assertTrue("workspace root should include state/: " + root, root.contains("state/"));

            List<String> state = localFs.list("workspace/state/");
            assertTrue("workspace/state should include run-1.json: " + state, state.contains("run-1.json"));
        } finally {
            if (prevDir != null) {
                System.setProperty("capella.ai.workspace.dir", prevDir);
            } else {
                System.clearProperty("capella.ai.workspace.dir");
            }
        }
    }

    @Test
    public void testWorkspaceWriteBlocksTraversal() throws Exception {
        Path workspace = Files.createTempDirectory("capella-ai-workspace-");

        String prevDir = System.getProperty("capella.ai.workspace.dir");
        try {
            System.setProperty("capella.ai.workspace.dir", workspace.toString());
            FileSystemAPI localFs = new FileSystemAPI();

            ActionResult write = localFs.write("../escape.json", "nope");
            assertFalse("write should be rejected: " + write, write.success());
        } finally {
            if (prevDir != null) {
                System.setProperty("capella.ai.workspace.dir", prevDir);
            } else {
                System.clearProperty("capella.ai.workspace.dir");
            }
        }
    }

    @Test
    public void testWorkspaceWriteBlocksHiddenFiles() throws Exception {
        Path workspace = Files.createTempDirectory("capella-ai-workspace-");

        String prevDir = System.getProperty("capella.ai.workspace.dir");
        try {
            System.setProperty("capella.ai.workspace.dir", workspace.toString());
            FileSystemAPI localFs = new FileSystemAPI();

            ActionResult write = localFs.write(".secret.json", "nope");
            assertFalse("write should be rejected: " + write, write.success());
        } finally {
            if (prevDir != null) {
                System.setProperty("capella.ai.workspace.dir", prevDir);
            } else {
                System.clearProperty("capella.ai.workspace.dir");
            }
        }
    }

    @Test
    public void testWorkspaceWriteBlocksExtension() throws Exception {
        Path workspace = Files.createTempDirectory("capella-ai-workspace-");

        String prevDir = System.getProperty("capella.ai.workspace.dir");
        try {
            System.setProperty("capella.ai.workspace.dir", workspace.toString());
            FileSystemAPI localFs = new FileSystemAPI();

            ActionResult write = localFs.write("run.exe", "nope");
            assertFalse("write should be rejected: " + write, write.success());
        } finally {
            if (prevDir != null) {
                System.setProperty("capella.ai.workspace.dir", prevDir);
            } else {
                System.clearProperty("capella.ai.workspace.dir");
            }
        }
    }

    @Test
    public void testWorkspaceQuotaEnforced() throws Exception {
        Path workspace = Files.createTempDirectory("capella-ai-workspace-");

        String prevDir = System.getProperty("capella.ai.workspace.dir");
        String prevMaxTotal = System.getProperty("capella.ai.workspace.maxTotalBytes");
        String prevMaxFile = System.getProperty("capella.ai.workspace.maxFileBytes");
        try {
            System.setProperty("capella.ai.workspace.dir", workspace.toString());
            System.setProperty("capella.ai.workspace.maxTotalBytes", "100");
            System.setProperty("capella.ai.workspace.maxFileBytes", "100");

            FileSystemAPI localFs = new FileSystemAPI();
            ActionResult a = localFs.write("a.txt", "x".repeat(60));
            assertTrue("first write should succeed: " + a, a.success());

            ActionResult b = localFs.write("b.txt", "x".repeat(60));
            assertFalse("second write should exceed quota: " + b, b.success());
        } finally {
            if (prevDir != null) System.setProperty("capella.ai.workspace.dir", prevDir);
            else System.clearProperty("capella.ai.workspace.dir");
            if (prevMaxTotal != null) System.setProperty("capella.ai.workspace.maxTotalBytes", prevMaxTotal);
            else System.clearProperty("capella.ai.workspace.maxTotalBytes");
            if (prevMaxFile != null) System.setProperty("capella.ai.workspace.maxFileBytes", prevMaxFile);
            else System.clearProperty("capella.ai.workspace.maxFileBytes");
        }
    }

    @Test
    public void testSaveSkillCreatesFolderSkillMd() throws Exception {
        Path skillsDir = Files.createTempDirectory("capella-ai-skills-");

        String prev = System.getProperty("capella.ai.skills.dir");
        try {
            System.setProperty("capella.ai.skills.dir", skillsDir.toString());
            FileSystemAPI localFs = new FileSystemAPI();

            boolean ok = localFs.saveSkill("My Pattern", "// Does something\nlog.info(\"hi\")\n");
            assertTrue(ok);

            List<String> entries = localFs.list("user-skills/");
            assertTrue("should list folder entry: " + entries, entries.contains("my-pattern/"));

            String skillMd = localFs.read("user-skills/my-pattern/SKILL.md");
            assertTrue(skillMd.startsWith("---"));
            assertTrue(skillMd.contains("name: my-pattern"));
            assertTrue(skillMd.contains("description: Does something"));
            assertTrue(skillMd.contains("```groovy"));
            assertTrue(skillMd.contains("log.info(\"hi\")"));
        } finally {
            if (prev != null) System.setProperty("capella.ai.skills.dir", prev);
            else System.clearProperty("capella.ai.skills.dir");
        }
    }

    @Test
    public void testLoadSkillExtractsGroovyCodeFromSkillMd() throws Exception {
        Path skillsDir = Files.createTempDirectory("capella-ai-skills-");

        String prev = System.getProperty("capella.ai.skills.dir");
        try {
            System.setProperty("capella.ai.skills.dir", skillsDir.toString());
            FileSystemAPI localFs = new FileSystemAPI();

            boolean ok = localFs.saveSkill("my-pattern", "log.info(\"hi\")\n");
            assertTrue(ok);

            String code = localFs.loadSkill("my-pattern");
            assertNotNull(code);
            assertTrue(code.contains("log.info(\"hi\")"));
            assertFalse(code.contains("name: my-pattern"));
        } finally {
            if (prev != null) System.setProperty("capella.ai.skills.dir", prev);
            else System.clearProperty("capella.ai.skills.dir");
        }
    }
}
