package com.sidkolapalli.capella.ai.scripting;

import org.eclipse.sirius.business.api.session.Session;
import com.sidkolapalli.capella.ai.preferences.SkillPreferences;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime API for executing reusable skills.
 *
 * <p>Usage from Groovy code execution:
 *
 * <pre>{@code
 * api.skills.list()
 * api.skills.metadata("runtime-demo")
 * api.skills.run("runtime-demo", [message: "hello"])
 * }</pre>
 *
 * When called by a running model script, the skill shares that script's undo command and
 * cancellation deadline. Any failed skill makes the entire parent command roll back, even when its
 * returned result is ignored.
 */
public final class SkillsAPI {

    private final Session session;

    public SkillsAPI(Session session) {
        this.session = session;
    }

    public List<String> list() {
        return SkillRegistry.listAll().stream().map(SkillRegistry.SkillInfo::name).toList();
    }

    public Map<String, Object> metadata(String name) {
        SkillRegistry.SkillInfo skill = SkillRegistry.find(name);
        if (skill == null) {
            return Map.of("ok", false, "error", "Skill not found: " + name);
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("ok", true);
        meta.put("name", skill.name());
        meta.put("description", skill.description());
        meta.put("version", skill.version());
        meta.put("trust", skill.trust().name().toLowerCase());
        meta.put("requiredInputs", skill.requiredInputs());
        meta.put("skillMdPath", skill.skillMdPath());
        if (skill.codePath() != null) {
            meta.put("codePath", skill.codePath());
        }
        return meta;
    }

    public ExecutionResult run(String name, Map<String, Object> args) {
        SkillRegistry.SkillInfo skill = SkillRegistry.find(name);
        if (skill == null) {
            return GroovyExecutor.recordNestedFailure(
                    ExecutionResult.fail(
                            ExecutionResult.ErrorCode.INVALID_RESULT, "Skill not found: " + name));
        }

        if (skill.trust() == SkillRegistry.Trust.USER
                && !SkillPreferences.isUserSkillsExecutionEnabled()) {
            return GroovyExecutor.recordNestedFailure(
                    ExecutionResult.fail(
                            ExecutionResult.ErrorCode.SECURITY_VIOLATION,
                            "User skill execution is disabled. Enable it in preferences before"
                                + " running user-defined skills."));
        }

        Map<String, Object> safeArgs =
                Collections.unmodifiableMap(new LinkedHashMap<>(args != null ? args : Map.of()));
        if (safeArgs.containsKey(null)) {
            return GroovyExecutor.recordNestedFailure(
                    ExecutionResult.fail(
                            ExecutionResult.ErrorCode.INVALID_RESULT,
                            "Skill argument names cannot be null"));
        }
        for (String req : skill.requiredInputs()) {
            if (!safeArgs.containsKey(req) || safeArgs.get(req) == null) {
                return GroovyExecutor.recordNestedFailure(
                        ExecutionResult.fail(
                                ExecutionResult.ErrorCode.INVALID_RESULT,
                                "Missing required input '"
                                        + req
                                        + "' for skill '"
                                        + skill.name()
                                        + "'"));
            }
        }

        String code = SkillRegistry.loadCode(skill);
        if (code == null || code.isBlank()) {
            return GroovyExecutor.recordNestedFailure(
                    ExecutionResult.fail(
                            ExecutionResult.ErrorCode.INVALID_RESULT,
                            "No executable code found for skill '" + skill.name() + "'"));
        }

        return GroovyExecutor.recordNestedFailure(
                GroovyExecutor.execute(session, code, Map.of("args", safeArgs)));
    }
}
