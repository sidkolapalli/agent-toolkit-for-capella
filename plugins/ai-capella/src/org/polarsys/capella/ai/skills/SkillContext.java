package org.polarsys.capella.ai.skills;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Execution context for a skill, containing all parameters parsed from TOOL_PLAN JSON.
 * This class provides type-safe accessors for common parameters while supporting
 * arbitrary additional parameters via a generic map.
 */
public final class SkillContext {

    private final String layer;
    private final String name;
    private final String parentName;
    private final String sourceName;
    private final String targetName;
    private final String elementClass;
    private final String existingName;
    private final String newName;
    private final String description;
    private final String capabilityName;
    private final String diagramName;
    private final String diagramType;
    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final int existingIndex;
    private final int sourceIndex;
    private final int targetIndex;
    private final List<String> items;
    private final List<MappingItem> mappingItems;
    private final Map<String, Object> extras;

    private SkillContext(Builder builder) {
        this.layer = builder.layer;
        this.name = builder.name;
        this.parentName = builder.parentName;
        this.sourceName = builder.sourceName;
        this.targetName = builder.targetName;
        this.elementClass = builder.elementClass;
        this.existingName = builder.existingName;
        this.newName = builder.newName;
        this.description = builder.description;
        this.capabilityName = builder.capabilityName;
        this.diagramName = builder.diagramName;
        this.diagramType = builder.diagramType;
        this.x = builder.x;
        this.y = builder.y;
        this.width = builder.width;
        this.height = builder.height;
        this.existingIndex = builder.existingIndex;
        this.sourceIndex = builder.sourceIndex;
        this.targetIndex = builder.targetIndex;
        this.items = builder.items != null ? List.copyOf(builder.items) : List.of();
        this.mappingItems = builder.mappingItems != null ? List.copyOf(builder.mappingItems) : List.of();
        this.extras = builder.extras != null ? Map.copyOf(builder.extras) : Map.of();
    }

    // ========== Common Parameter Accessors ==========

    public String getLayer() { return layer; }
    public String getName() { return name; }
    public String getParentName() { return parentName; }
    public String getSourceName() { return sourceName; }
    public String getTargetName() { return targetName; }
    public String getElementClass() { return elementClass; }
    public String getExistingName() { return existingName; }
    public String getNewName() { return newName; }
    public String getDescription() { return description; }
    public String getCapabilityName() { return capabilityName; }
    public String getDiagramName() { return diagramName; }
    public String getDiagramType() { return diagramType; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getExistingIndex() { return existingIndex; }
    public int getSourceIndex() { return sourceIndex; }
    public int getTargetIndex() { return targetIndex; }
    public List<String> getItems() { return items; }
    public List<MappingItem> getMappingItems() { return mappingItems; }

    // ========== Generic Extra Parameters ==========

    public Object getExtra(String key) {
        return extras.get(key);
    }

    public String getExtraString(String key) {
        Object v = extras.get(key);
        return v != null ? String.valueOf(v) : null;
    }

    public int getExtraInt(String key, int defaultValue) {
        Object v = extras.get(key);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        return defaultValue;
    }

    public boolean getExtraBool(String key, boolean defaultValue) {
        Object v = extras.get(key);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        return defaultValue;
    }

    public Map<String, Object> getAllExtras() {
        return extras;
    }

    // ========== Convenience Methods ==========

    public boolean hasName() {
        return name != null && !name.isBlank();
    }

    public boolean hasParent() {
        return parentName != null && !parentName.isBlank();
    }

    public boolean hasSource() {
        return sourceName != null && !sourceName.isBlank();
    }

    public boolean hasTarget() {
        return targetName != null && !targetName.isBlank();
    }

    public boolean hasExistingName() {
        return existingName != null && !existingName.isBlank();
    }

    public boolean hasNewName() {
        return newName != null && !newName.isBlank();
    }

    // ========== Mapping Item for batch operations ==========

    public record MappingItem(String source, String target) {}

    // ========== Builder ==========

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String layer;
        private String name;
        private String parentName;
        private String sourceName;
        private String targetName;
        private String elementClass;
        private String existingName;
        private String newName;
        private String description;
        private String capabilityName;
        private String diagramName;
        private String diagramType;
        private int x;
        private int y;
        private int width;
        private int height;
        private int existingIndex = -1;
        private int sourceIndex = -1;
        private int targetIndex = -1;
        private List<String> items;
        private List<MappingItem> mappingItems;
        private Map<String, Object> extras;

        public Builder layer(String layer) { this.layer = layer; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder parentName(String parentName) { this.parentName = parentName; return this; }
        public Builder sourceName(String sourceName) { this.sourceName = sourceName; return this; }
        public Builder targetName(String targetName) { this.targetName = targetName; return this; }
        public Builder elementClass(String elementClass) { this.elementClass = elementClass; return this; }
        public Builder existingName(String existingName) { this.existingName = existingName; return this; }
        public Builder newName(String newName) { this.newName = newName; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder capabilityName(String capabilityName) { this.capabilityName = capabilityName; return this; }
        public Builder diagramName(String diagramName) { this.diagramName = diagramName; return this; }
        public Builder diagramType(String diagramType) { this.diagramType = diagramType; return this; }
        public Builder x(int x) { this.x = x; return this; }
        public Builder y(int y) { this.y = y; return this; }
        public Builder width(int width) { this.width = width; return this; }
        public Builder height(int height) { this.height = height; return this; }
        public Builder existingIndex(int existingIndex) { this.existingIndex = existingIndex; return this; }
        public Builder sourceIndex(int sourceIndex) { this.sourceIndex = sourceIndex; return this; }
        public Builder targetIndex(int targetIndex) { this.targetIndex = targetIndex; return this; }
        public Builder items(List<String> items) { this.items = items; return this; }
        public Builder mappingItems(List<MappingItem> mappingItems) { this.mappingItems = mappingItems; return this; }

        public Builder extra(String key, Object value) {
            if (this.extras == null) {
                this.extras = new HashMap<>();
            }
            this.extras.put(key, value);
            return this;
        }

        public Builder extras(Map<String, Object> extras) {
            this.extras = extras != null ? new HashMap<>(extras) : null;
            return this;
        }

        public SkillContext build() {
            return new SkillContext(this);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("SkillContext{");
        if (layer != null) sb.append("layer=").append(layer).append(", ");
        if (name != null) sb.append("name=").append(name).append(", ");
        if (parentName != null) sb.append("parentName=").append(parentName).append(", ");
        if (existingName != null) sb.append("existingName=").append(existingName).append(", ");
        if (newName != null) sb.append("newName=").append(newName).append(", ");
        if (!extras.isEmpty()) sb.append("extras=").append(extras);
        return sb.append("}").toString();
    }
}
