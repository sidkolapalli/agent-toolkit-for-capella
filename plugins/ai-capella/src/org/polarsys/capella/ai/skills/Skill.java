package org.polarsys.capella.ai.skills;

import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.model.ActionResult;

/**
 * A Skill encapsulates a single action capability that the LLM can invoke.
 * Skills are the fundamental building blocks of the Capella AI assistant,
 * providing modular, self-describing operations that return structured results
 * for self-healing and error reporting.
 */
public interface Skill {

    /**
     * Returns the unique identifier for this skill.
     * This ID corresponds to the action type in TOOL_PLAN JSON.
     * Example: "create_component", "rename", "batch_delete"
     */
    String getId();

    /**
     * Returns metadata describing this skill's capabilities.
     * Used for prompt generation and LLM context.
     */
    SkillMetadata getMetadata();

    /**
     * Executes the skill with the provided context.
     *
     * @param session The active Capella session (may be null for validation-only)
     * @param context The execution context containing action parameters
     * @return ActionResult with success/failure status and diagnostic information
     */
    ActionResult execute(Session session, SkillContext context);

    /**
     * Validates whether this skill can execute with the given context.
     * Called before execution to provide early feedback.
     *
     * @param context The execution context to validate
     * @return ActionResult indicating validation success or explaining why validation failed
     */
    default ActionResult validate(SkillContext context) {
        return ActionResult.ok();
    }

    /**
     * Returns the Arcadia layer(s) this skill operates on.
     * Empty array means the skill is layer-agnostic.
     */
    default String[] getSupportedLayers() {
        return new String[0];
    }

    /**
     * Returns true if this skill requires user confirmation before execution.
     * Typically true for destructive operations like delete or batch operations.
     */
    default boolean requiresConfirmation() {
        return false;
    }
}
