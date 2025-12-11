package org.polarsys.capella.ai.skills;

import java.util.List;

/**
 * Metadata describing a skill's capabilities for LLM prompt generation.
 * This information helps the LLM understand when and how to use each skill.
 */
public record SkillMetadata(
    /** Human-readable name for the skill (e.g., "Create Component") */
    String displayName,

    /** Brief description of what the skill does */
    String description,

    /** Category for grouping skills (e.g., "Model Creation", "Transformation", "Batch Operations") */
    SkillCategory category,

    /** List of required parameters with their descriptions */
    List<ParameterInfo> requiredParameters,

    /** List of optional parameters with their descriptions */
    List<ParameterInfo> optionalParameters,

    /** Example usage in TOOL_PLAN JSON format */
    String exampleJson
) {
    /**
     * Categories for organizing skills in the UI and prompt generation.
     */
    public enum SkillCategory {
        /** Creating new model elements */
        MODEL_CREATION("Model Creation"),
        /** Modifying existing elements */
        MODEL_MODIFICATION("Model Modification"),
        /** Linking and relating elements */
        MODEL_LINKING("Model Linking"),
        /** Layer transformations and propagation */
        TRANSFORMATION("Transformation"),
        /** Batch operations on multiple elements */
        BATCH_OPERATIONS("Batch Operations"),
        /** Diagram creation and manipulation */
        DIAGRAM("Diagram"),
        /** Requirements management */
        REQUIREMENTS("Requirements"),
        /** Functional chains and scenarios */
        FUNCTIONAL_ANALYSIS("Functional Analysis");

        private final String displayName;

        SkillCategory(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Information about a skill parameter.
     */
    public record ParameterInfo(
        /** Parameter name as it appears in JSON (e.g., "layer", "name", "parent_name") */
        String name,
        /** Human-readable description */
        String description,
        /** Expected type (e.g., "string", "OA|SA|LA|PA", "boolean") */
        String type
    ) {}

    /**
     * Builder for creating SkillMetadata instances.
     */
    public static Builder builder(String displayName) {
        return new Builder(displayName);
    }

    public static class Builder {
        private final String displayName;
        private String description = "";
        private SkillCategory category = SkillCategory.MODEL_CREATION;
        private List<ParameterInfo> requiredParameters = List.of();
        private List<ParameterInfo> optionalParameters = List.of();
        private String exampleJson = "";

        private Builder(String displayName) {
            this.displayName = displayName;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder category(SkillCategory category) {
            this.category = category;
            return this;
        }

        public Builder requiredParameters(ParameterInfo... params) {
            this.requiredParameters = List.of(params);
            return this;
        }

        public Builder optionalParameters(ParameterInfo... params) {
            this.optionalParameters = List.of(params);
            return this;
        }

        public Builder example(String json) {
            this.exampleJson = json;
            return this;
        }

        public SkillMetadata build() {
            return new SkillMetadata(displayName, description, category,
                                     requiredParameters, optionalParameters, exampleJson);
        }
    }

    /**
     * Formats this metadata as a prompt snippet for the LLM.
     */
    public String formatForPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(displayName).append("\n");
        sb.append(description).append("\n");

        if (!requiredParameters.isEmpty()) {
            sb.append("Required: ");
            for (int i = 0; i < requiredParameters.size(); i++) {
                if (i > 0) sb.append(", ");
                ParameterInfo p = requiredParameters.get(i);
                sb.append("`").append(p.name()).append("` (").append(p.type()).append(")");
            }
            sb.append("\n");
        }

        if (!optionalParameters.isEmpty()) {
            sb.append("Optional: ");
            for (int i = 0; i < optionalParameters.size(); i++) {
                if (i > 0) sb.append(", ");
                ParameterInfo p = optionalParameters.get(i);
                sb.append("`").append(p.name()).append("`");
            }
            sb.append("\n");
        }

        if (!exampleJson.isBlank()) {
            sb.append("Example: `").append(exampleJson).append("`\n");
        }

        return sb.toString();
    }
}
