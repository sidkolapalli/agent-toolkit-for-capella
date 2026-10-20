package org.polarsys.capella.ai.scripting;

import static org.junit.Assert.*;

import org.junit.Test;
import org.polarsys.capella.ai.preferences.SkillPreferences;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class SkillsAPITest {

    @Test
    public void testRegistryFindBuiltInSkill() {
        SkillRegistry.SkillInfo info = SkillRegistry.find("runtime-demo");
        assertNotNull(info);
        assertEquals(SkillRegistry.Trust.BUILT_IN, info.trust());
        assertTrue(info.skillMdPath().contains("skills/runtime-demo/"));
    }

    @Test
    public void testSkillsMetadata() {
        SkillsAPI api = new SkillsAPI(null);
        Map<String, Object> meta = api.metadata("runtime-demo");
        assertEquals(true, meta.get("ok"));
        assertEquals("runtime-demo", meta.get("name"));
    }

    @Test
    public void testSkillsRunValidatesArgsBeforeExecution() {
        SkillsAPI api = new SkillsAPI(null);
        ExecutionResult res = api.run("runtime-demo", Map.of());
        assertTrue(res.failed());
        assertEquals(ExecutionResult.ErrorCode.INVALID_RESULT, res.code());
        assertTrue(res.message().contains("Missing required input"));
    }

    @Test
    public void testSkillsRunFailsWithoutSession() {
        SkillsAPI api = new SkillsAPI(null);
        ExecutionResult res = api.run("runtime-demo", Map.of("message", "hi"));
        assertTrue(res.failed());
        assertEquals(ExecutionResult.ErrorCode.API_ERROR, res.code());
        assertTrue(res.message().contains("No active Capella session"));
    }

    @Test
    public void testUserSkillTrustGateBlocksByDefault() throws Exception {
        Path skillsDir = Files.createTempDirectory("capella-ai-user-skills-");

        String prevSkillsDir = System.getProperty("capella.ai.skills.dir");
        try {
            System.setProperty("capella.ai.skills.dir", skillsDir.toString());
            SkillPreferences.setUserSkillsExecutionEnabled(false);
            SkillPreferences.flush();

            Path userSkill = skillsDir.resolve("user-skill").resolve("SKILL.md");
            Files.createDirectories(userSkill.getParent());
            Files.writeString(
                    userSkill,
                    "---\n"
                            + "name: user-skill\n"
                            + "description: demo user skill\n"
                            + "---\n\n"
                            + "```groovy\n"
                            + "return 1\n"
                            + "```\n",
                    StandardCharsets.UTF_8);

            SkillsAPI api = new SkillsAPI(null);
            ExecutionResult res = api.run("user-skill", Map.of());
            assertTrue(res.failed());
            assertEquals(ExecutionResult.ErrorCode.SECURITY_VIOLATION, res.code());
        } finally {
            if (prevSkillsDir != null) {
                System.setProperty("capella.ai.skills.dir", prevSkillsDir);
            } else {
                System.clearProperty("capella.ai.skills.dir");
            }
        }
    }
}
