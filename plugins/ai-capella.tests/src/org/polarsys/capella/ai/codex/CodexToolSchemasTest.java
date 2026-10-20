package org.polarsys.capella.ai.codex;

import static org.junit.Assert.*;

import org.junit.Test;
import org.polarsys.capella.ai.agent.AgentJson;
import org.polarsys.capella.ai.agent.ArcadiaModelTools;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class CodexToolSchemasTest {
    @Test
    public void registersExactlyWhitelistedReadsPlusPlanAndReviewedProposal() {
        List<Map<String, Object>> definitions =
                CodexToolSchemas.definitions(ArcadiaModelTools.toolNames());
        assertEquals(10, definitions.size());
        Set<String> names =
                definitions.stream()
                        .map(item -> (String) item.get("name"))
                        .collect(Collectors.toSet());
        assertTrue(names.containsAll(ArcadiaModelTools.toolNames()));
        assertTrue(names.contains(CodexToolSchemas.PLAN));
        assertTrue(names.contains(CodexToolSchemas.PROPOSE));
        for (Map<String, Object> definition : definitions) {
            assertEquals(Set.of("type", "name", "description", "inputSchema"), definition.keySet());
            assertEquals("function", definition.get("type"));
            assertEquals(Boolean.FALSE, schema(definition).get("additionalProperties"));
        }
        assertFalse(names.contains("shell"));
        assertFalse(names.contains("execute"));
        assertNotNull(AgentJson.parse(AgentJson.write(definitions), 32_000));
        assertThrows(
                IllegalArgumentException.class,
                () -> CodexToolSchemas.definitions(Set.of("shell")));
    }

    @Test
    public void capturedReadSelectorsAndDocPathsAreBounded() {
        Map<String, Object> search = schema(tool("model_search")),
                properties = object(search.get("properties"));
        assertEquals(160, object(properties.get("query")).get("maxLength"));
        assertEquals(50, object(properties.get("limit")).get("maximum"));
        assertEquals(80, object(properties.get("cursor")).get("maxLength"));
        assertEquals(
                List.of("OA", "SA", "LA", "PA", "EPBS"),
                object(properties.get("layer")).get("enum"));
        assertEquals(2, ((List<?>) search.get("anyOf")).size());
        Map<String, Object> docs = object(schema(tool("capella_docs")).get("properties"));
        assertEquals(
                ArcadiaModelTools.documentationPaths(),
                Set.copyOf((List<?>) object(docs.get("path")).get("enum")));
        assertEquals(List.of("path"), schema(tool("capella_docs")).get("required"));
        assertEquals(List.of("id"), schema(tool("model_inspect")).get("required"));
    }

    @Test
    public void proposalRequiresExactSourceAndPendingStepScopeWithBoundedTypedPostconditions() {
        Map<String, Object> proposal = schema(tool(CodexToolSchemas.PROPOSE));
        Map<String, Object> properties = object(proposal.get("properties"));
        assertEquals(List.of("message", "script", "stepIds"), proposal.get("required"));
        assertEquals(120_000, object(properties.get("script")).get("maxLength"));
        assertEquals(12, object(properties.get("stepIds")).get("maxItems"));
        Map<String, Object> conditions = object(properties.get("postconditions")),
                condition = object(conditions.get("items"));
        assertEquals(12, conditions.get("maxItems"));
        assertEquals(Boolean.FALSE, condition.get("additionalProperties"));
        assertEquals(2, ((List<?>) condition.get("oneOf")).size());
        assertEquals(
                List.of("attribute", "expected"), object(condition.get("then")).get("required"));
        assertEquals(
                List.of("null", "boolean", "number", "string"),
                object(object(condition.get("properties")).get("expected")).get("type"));
        assertFalse(object(condition.get("properties")).containsKey("predicate"));
    }

    @Test
    public void schemasDoNotAddToolsOutsideTheTrustedReadSubset() {
        List<Map<String, Object>> definitions =
                CodexToolSchemas.definitions(Set.of("model_inspect"));
        assertEquals(
                Set.of("model_inspect", CodexToolSchemas.PLAN, CodexToolSchemas.PROPOSE),
                definitions.stream()
                        .map(item -> (String) item.get("name"))
                        .collect(Collectors.toSet()));
        assertEquals(2, CodexToolSchemas.definitions(Set.of()).size());
        assertThrows(UnsupportedOperationException.class, () -> definitions.add(Map.of()));
    }

    @Test
    public void descriptionsExplainActualFailedCallCorrectionsAndValidationSelectorsAreExclusive() {
        assertTrue(
                String.valueOf(tool("model_search").get("description"))
                        .contains("NEVER a name argument"));
        assertTrue(
                String.valueOf(tool("model_search").get("description"))
                        .contains("query:'' is invalid"));
        assertTrue(String.valueOf(tool("model_inspect").get("description")).contains("1–30"));
        assertTrue(String.valueOf(tool("model_inspect").get("description")).contains("Omit limit"));
        assertTrue(
                String.valueOf(tool(CodexToolSchemas.PROPOSE).get("description"))
                        .contains("FLAT objects"));
        assertTrue(
                String.valueOf(tool(CodexToolSchemas.PROPOSE).get("description"))
                        .contains("\"stepId\":\"create\""));
        List<?> forbiddenCombinations =
                (List<?>) object(schema(tool("model_validate")).get("not")).get("anyOf");
        assertEquals(List.of("id", "ids"), object(forbiddenCombinations.get(0)).get("required"));
        assertEquals(List.of("id", "layer"), object(forbiddenCombinations.get(1)).get("required"));
        assertEquals(List.of("ids", "layer"), object(forbiddenCombinations.get(2)).get("required"));
    }

    private static Map<String, Object> tool(String name) {
        return CodexToolSchemas.definitions(ArcadiaModelTools.toolNames()).stream()
                .filter(item -> name.equals(item.get("name")))
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, Object> schema(Map<String, Object> tool) {
        return object(tool.get("inputSchema"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }
}
