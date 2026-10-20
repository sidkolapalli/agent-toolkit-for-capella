package org.polarsys.capella.ai.codex;

import org.polarsys.capella.ai.agent.ArcadiaAgent;
import org.polarsys.capella.ai.agent.ArcadiaModelTools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pinned app-server 0.160.1 dynamic function specifications. No executable schemas. */
public final class CodexToolSchemas {
    public static final String PLAN = "capella_plan";
    public static final String PROPOSE = "capella_propose_edit";

    private CodexToolSchemas() {}

    public static List<Map<String, Object>> definitions(Set<String> allowedReads) {
        if (!ArcadiaModelTools.toolNames().containsAll(allowedReads))
            throw new IllegalArgumentException("Unknown native read tool");
        List<Map<String, Object>> tools = new ArrayList<>();
        for (String name : allowedReads.stream().sorted().toList())
            tools.add(spec(name, readDescription(name), readSchema(name)));
        tools.add(
                spec(
                        PLAN,
                        "Present/revise a bounded Arcadia task plan. Edit completion is set only by"
                            + " authoritative native execution evidence.",
                        object(
                                Map.of("message", text(2400), "steps", array(stepSchema(), 1, 12)),
                                List.of("message", "steps"))));
        tools.add(
                spec(
                        PROPOSE,
                        "Pause for review of this exact complete Groovy script; never execute it."
                            + " stepIds must name pending edit steps from capella_plan. Await and"
                            + " print the tool's eventual execution receipt. If Code Mode exec"
                            + " yields a running cell, wait on that cell until the receipt arrives;"
                            + " do not finalize while it is pending. "
                                + ArcadiaAgent.EDIT_STEP_COMPLETION
                                + "Postconditions are FLAT objects, each with its own stepId; there"
                                + " is no nested target or arguments field. native_structure"
                                + " instead contains one nested check for stored reference"
                                + " equality, exact reference order, immediate containment or a"
                                + " unique directed relation. Example: {\"message\":\"Create a"
                                + " function\",\"script\":\"api.create('LA','LogicalFunction','Probe')\",\"stepIds\":[\"create\"],\"postconditions\":[{\"stepId\":\"create\",\"kind\":\"element_exists\",\"type\":\"LogicalFunction\",\"name\":\"Probe\",\"layer\":\"LA\"}]}."
                                + " Caller returns actual execution or rejection facts; an accepted"
                                + " proposal is still unexecuted.",
                        proposalSchema()));
        return List.copyOf(tools);
    }

    private static Map<String, Object> spec(
            String name, String description, Map<String, Object> schema) {
        return Map.of(
                "type",
                "function",
                "name",
                name,
                "description",
                description,
                "inputSchema",
                schema);
    }

    private static Map<String, Object> proposalSchema() {
        Map<String, Object> schema =
                new LinkedHashMap<>(
                        object(
                                Map.of(
                                        "message",
                                        text(2400),
                                        "script",
                                        text(120000),
                                        "stepIds",
                                        array(text(64), 1, 12),
                                        "postconditions",
                                        array(postconditionSchema(), 0, 12)),
                                List.of("message", "script", "stepIds")));
        // Standard local schema references avoid repeating deeply nested native
        // selectors while keeping the bounded JSON protocol unchanged.
        schema.put(
                "$defs",
                Map.of(
                        "nativeSelector",
                        structuralSelector(true),
                        "nativeBaseSelector",
                        structuralSelector(false)));
        return Map.copyOf(schema);
    }

    private static String readDescription(String name) {
        String details =
                switch (name) {
                    case "model_summary" ->
                            "Get the captured model identity and architecture roots. Arguments: {}"
                                + " (optional read stepId).";
                    case "model_search" ->
                            "Find native elements by query substring and/or canonical metamodel"
                                + " type, optionally layer. Use query for a name, NEVER a name"
                                + " argument. Omit unused optional fields; query:'' is invalid."
                                + " Examples:"
                                + " {\"query\":\"Probe\",\"type\":\"SystemFunction\",\"layer\":\"SA\"}"
                                + " or {\"type\":\"ConfigurationItem\",\"layer\":\"EPBS\"}. limit"
                                + " is 1–50, default20. Continue a returned cursor with the SAME"
                                + " query/type/layer filters. Returned id is the native UUID, not a"
                                + " name or ReqIF identifier.";
                    case "model_inspect" ->
                            "Inspect one native element by its id returned by model_search."
                                + " Example: {\"id\":\"native-uuid\"}. limit is 1–30, default15; 80"
                                + " is invalid. Names, paths and ReqIF identifiers are not native"
                                + " IDs. Omit limit to use the default.";
                    case "model_relationships" ->
                            "Read stored outgoing and bounded incoming relationships of a native id"
                                + " returned by model_search. Example: {\"id\":\"native-uuid\"}."
                                + " limit is 1–50, default20; continue returned cursor with the"
                                + " same id.";
                    case "model_validate" ->
                            "Run bounded structural checks and Arcadia method checks"
                                + " (unallocated/unrealized leaf functions, cross-component"
                                + " functional exchanges without a component exchange). Use a layer"
                                + " for method checks. Example: {\"ids\":[\"native-uuid\"]} or"
                                + " {\"layer\":\"LA\"}. Choose id OR ids OR layer, never combine"
                                + " them; {} checks bounded whole-model scope. limit is 1–50,"
                                + " default30. This is not full native validation or compliance.";
                    case "native_validation" ->
                            "Run installed full-project native Capella constraints; complete counts"
                                + " with bounded messages. Example: {}. Optional limit is 1–50,"
                                + " default30. Continue the returned cursor for remaining messages"
                                + " from the SAME validation without rerunning it. Cursors expire"
                                + " after five minutes or a model command/Undo/Redo."
                                + " source_truncated reports findings beyond the 10000-message"
                                + " ceiling; message_truncated marks clipped text. This can be"
                                + " expensive; run once after a coherent edit or when validation is"
                                + " requested. Passing is not completeness or compliance.";
                    case "capella_docs" ->
                            "Read a shipped API/methodology guide at an exact path from"
                                + " capella_capabilities documentationPaths. Example:"
                                + " {\"path\":\"api/create.md\"}. No arbitrary workspace paths or"
                                + " user files.";
                    case "capella_capabilities" ->
                            "Discover supported APIs, limitations and exact shipped documentation"
                                + " paths. Example: {} or {\"module\":\"api\"}. Optional module is"
                                + " api, diagram, requirements, arcadia, skills or fs; omit unused"
                                + " fields.";
                    default -> throw new IllegalArgumentException("Unknown native read tool");
                };
        return details
                + " Read-only: no execution, model mutation, save or arbitrary UI control. Optional"
                + " stepId must belong to a read step.";
    }

    private static Map<String, Object> readSchema(String name) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("stepId", text(64));
        List<String> required = List.of();
        switch (name) {
            case "model_summary" -> {}
            case "model_search" -> {
                properties.put("query", text(160));
                properties.put("type", text(80));
                properties.put("layer", layer());
                properties.put("limit", integer(1, 50));
                properties.put("cursor", text(80));
            }
            case "model_inspect", "model_relationships" -> {
                properties.put("id", text(240));
                properties.put("limit", integer(1, "model_inspect".equals(name) ? 30 : 50));
                if ("model_relationships".equals(name)) properties.put("cursor", text(80));
                required = List.of("id");
            }
            case "model_validate" -> {
                properties.put("id", text(240));
                properties.put("ids", array(text(240), 1, 50));
                properties.put("layer", layer());
                properties.put("limit", integer(1, 50));
            }
            case "native_validation" -> {
                properties.put("limit", integer(1, 50));
                properties.put("cursor", text(80));
            }
            case "capella_docs" -> {
                properties.put(
                        "path",
                        Map.of(
                                "type",
                                "string",
                                "enum",
                                ArcadiaModelTools.documentationPaths().stream().sorted().toList()));
                required = List.of("path");
            }
            case "capella_capabilities" ->
                    properties.put(
                            "module",
                            Map.of(
                                    "type",
                                    "string",
                                    "enum",
                                    List.of(
                                            "api",
                                            "diagram",
                                            "requirements",
                                            "arcadia",
                                            "skills",
                                            "fs")));
            default -> throw new IllegalArgumentException("Unknown native read tool");
        }
        Map<String, Object> schema = new LinkedHashMap<>(object(properties, required));
        if ("model_search".equals(name))
            schema.put(
                    "anyOf",
                    List.of(
                            Map.of("required", List.of("query")),
                            Map.of("required", List.of("type"))));
        if ("model_validate".equals(name))
            schema.put(
                    "not",
                    Map.of(
                            "anyOf",
                            List.of(
                                    Map.of("required", List.of("id", "ids")),
                                    Map.of("required", List.of("id", "layer")),
                                    Map.of("required", List.of("ids", "layer")))));
        return Map.copyOf(schema);
    }

    private static Map<String, Object> stepSchema() {
        return object(
                Map.of(
                        "id",
                        Map.of("type", "string", "pattern", "^[A-Za-z0-9_-]{1,64}$"),
                        "title",
                        text(240),
                        "type",
                        Map.of("enum", List.of("read", "edit")),
                        "status",
                        Map.of("enum", List.of("pending", "in_progress", "completed"))),
                List.of("id", "title", "type", "status"));
    }

    private static Map<String, Object> postconditionSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("stepId", text(64));
        properties.put(
                "kind",
                Map.of(
                        "enum",
                        List.of(
                                "element_exists",
                                "element_absent",
                                "attribute_equals",
                                "native_structure")));
        for (String key : List.of("id", "type", "name", "attribute"))
            properties.put(key, text("type".equals(key) || "attribute".equals(key) ? 120 : 2400));
        properties.put("layer", layer());
        properties.put(
                "expected",
                Map.of("type", List.of("null", "boolean", "number", "string"), "maxLength", 2400));
        properties.put("check", structuralCheckSchema());
        Map<String, Object> schema =
                new LinkedHashMap<>(object(properties, List.of("stepId", "kind")));
        List<Map<String, Object>> selectors =
                List.of(
                        Map.of(
                                "required",
                                List.of("id"),
                                "not",
                                Map.of(
                                        "anyOf",
                                        List.of(
                                                Map.of("required", List.of("type")),
                                                Map.of("required", List.of("name")),
                                                Map.of("required", List.of("layer"))))),
                        Map.of(
                                "required",
                                List.of("type", "name"),
                                "not",
                                Map.of("required", List.of("id"))));
        schema.put(
                "oneOf",
                List.of(
                        Map.of(
                                "properties",
                                Map.of(
                                        "kind",
                                        Map.of(
                                                "enum",
                                                List.of(
                                                        "element_exists",
                                                        "element_absent",
                                                        "attribute_equals"))),
                                "oneOf",
                                selectors,
                                "not",
                                Map.of("required", List.of("check"))),
                        Map.of(
                                "properties",
                                Map.of("kind", Map.of("const", "native_structure")),
                                "required",
                                List.of("check"),
                                "not",
                                Map.of(
                                        "anyOf",
                                        List.of(
                                                        "id",
                                                        "type",
                                                        "name",
                                                        "layer",
                                                        "attribute",
                                                        "expected")
                                                .stream()
                                                .map(key -> Map.of("required", List.of(key)))
                                                .toList()))));
        schema.put("if", Map.of("properties", Map.of("kind", Map.of("const", "attribute_equals"))));
        schema.put("then", Map.of("required", List.of("attribute", "expected")));
        schema.put(
                "else",
                Map.of(
                        "not",
                        Map.of(
                                "anyOf",
                                List.of(
                                        Map.of("required", List.of("attribute")),
                                        Map.of("required", List.of("expected"))))));
        return Map.copyOf(schema);
    }

    private static Map<String, Object> structuralSelector(boolean containerAllowed) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", text(2400));
        properties.put("type", text(120));
        properties.put("name", text(2400));
        properties.put("layer", layer());
        if (containerAllowed)
            properties.put("container", Map.of("$ref", "#/$defs/nativeBaseSelector"));
        Map<String, Object> schema = new LinkedHashMap<>(object(properties, List.of()));
        schema.put(
                "oneOf",
                List.of(
                        Map.of("required", List.of("id"), "maxProperties", 1),
                        Map.of(
                                "required",
                                List.of("type", "name"),
                                "not",
                                Map.of("required", List.of("id")))));
        return Map.copyOf(schema);
    }

    private static Map<String, Object> structuralCheckSchema() {
        Map<String, Object> selector = Map.of("$ref", "#/$defs/nativeSelector");
        Map<String, Object> predicates =
                Map.of(
                        "type",
                        "object",
                        "minProperties",
                        1,
                        "maxProperties",
                        4,
                        "propertyNames",
                        Map.of("pattern", "^[A-Za-z_][A-Za-z0-9_]*$", "maxLength", 120),
                        "additionalProperties",
                        selector);
        return Map.of(
                "oneOf",
                List.of(
                        object(
                                Map.of(
                                        "kind",
                                        Map.of("const", "reference_equals"),
                                        "target",
                                        selector,
                                        "feature",
                                        text(120),
                                        "expected",
                                        Map.of("anyOf", List.of(selector, Map.of("type", "null")))),
                                List.of("kind", "target", "feature", "expected")),
                        object(
                                Map.of(
                                        "kind",
                                        Map.of("const", "reference_sequence_equals"),
                                        "target",
                                        selector,
                                        "feature",
                                        text(120),
                                        "expected",
                                        array(selector, 0, 32)),
                                List.of("kind", "target", "feature", "expected")),
                        object(
                                Map.of(
                                        "kind",
                                        Map.of("const", "contained_by"),
                                        "target",
                                        selector,
                                        "container",
                                        selector),
                                List.of("kind", "target", "container")),
                        object(
                                Map.of(
                                        "kind",
                                        Map.of("const", "relation_exists"),
                                        "type",
                                        text(120),
                                        "predicates",
                                        predicates,
                                        "owner",
                                        selector),
                                List.of("kind", "type", "predicates")),
                        object(
                                Map.of(
                                        "kind",
                                        Map.of("const", "diagram_shows"),
                                        "diagram",
                                        text(400),
                                        "elements",
                                        array(selector, 1, 32)),
                                List.of("kind", "diagram", "elements"))));
    }

    private static Map<String, Object> object(
            Map<String, Object> properties, List<String> required) {
        return Map.of(
                "type",
                "object",
                "properties",
                Map.copyOf(properties),
                "required",
                required,
                "additionalProperties",
                false);
    }

    private static Map<String, Object> text(int max) {
        return Map.of("type", "string", "minLength", 1, "maxLength", max);
    }

    private static Map<String, Object> integer(int min, int max) {
        return Map.of("type", "integer", "minimum", min, "maximum", max);
    }

    private static Map<String, Object> array(Object items, int min, int max) {
        return Map.of("type", "array", "items", items, "minItems", min, "maxItems", max);
    }

    private static Map<String, Object> layer() {
        return Map.of("type", "string", "enum", List.of("OA", "SA", "LA", "PA", "EPBS"));
    }
}
