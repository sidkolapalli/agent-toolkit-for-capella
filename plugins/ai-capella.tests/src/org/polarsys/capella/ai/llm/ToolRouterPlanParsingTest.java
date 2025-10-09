package org.polarsys.capella.ai.llm;

import static org.junit.Assert.*;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.Test;

public class ToolRouterPlanParsingTest {

    @SuppressWarnings("unchecked")
    private static List<Object> invokeParseActions(String json) throws Exception {
        Class<?> tr = ToolRouter.class;
        Method m = tr.getDeclaredMethod("parseActions", String.class);
        m.setAccessible(true);
        Object list = m.invoke(null, json);
        return (List<Object>) list;
    }

    private static String readField(Object obj, String field) throws Exception {
        var f = obj.getClass().getDeclaredField(field);
        f.setAccessible(true);
        Object v = f.get(obj);
        return v == null ? null : String.valueOf(v);
    }

    @Test
    public void parsesSingleKeyActionObjectsAndCanonicalizesLayer() throws Exception {
        String plan = "{\n" +
                "  \"actions\": [\n" +
                "    {\n" +
                "      \"create_operational_activity\": {\n" +
                "        \"layer\": \"operational\",\n" +
                "        \"name\": \"Browse Entertainment Catalog\",\n" +
                "        \"parent_name\": \"Passenger\"\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}";
        List<Object> actions = invokeParseActions(plan);
        assertEquals(1, actions.size());
        Object action = actions.get(0);
        // Assert type was inferred correctly
        String typeName = readField(action, "type");
        assertNotNull(typeName);
        assertTrue(typeName.contains("CREATE_OPERATIONAL_ACTIVITY"));
        // Assert layer canonicalization OA
        assertEquals("OA", readField(action, "layer"));
        assertEquals("Browse Entertainment Catalog", readField(action, "name"));
        assertEquals("Passenger", readField(action, "parentName"));
    }

    @Test
    public void acceptsExplicitTypeForm() throws Exception {
        String plan = "{\n" +
                "  \"actions\": [\n" +
                "    {\n" +
                "      \"type\": \"create_operational_activity\",\n" +
                "      \"layer\": \"system\",\n" +
                "      \"name\": \"Start Playback\",\n" +
                "      \"parent_name\": \"Passenger\"\n" +
                "    }\n" +
                "  ]\n" +
                "}";
        List<Object> actions = invokeParseActions(plan);
        assertEquals(1, actions.size());
        Object action = actions.get(0);
        String typeName = readField(action, "type");
        assertNotNull(typeName);
        assertTrue(typeName.contains("CREATE_OPERATIONAL_ACTIVITY"));
        // 'system' canonicalized to SA (but operational activity defaults OA; here we test canonicalizer alone)
        assertEquals("SA", readField(action, "layer"));
    }

    @Test
    public void parsesSetViewBoundsSingleKey() throws Exception {
        String plan = "{\n" +
                "  \"actions\": [\n" +
                "    {\n" +
                "      \"set_view_bounds\": {\n" +
                "        \"element\": \"Passenger\",\n" +
                "        \"x\": 100, \"y\": 200, \"width\": 300, \"height\": 150,\n" +
                "        \"diagram\": \"MyLab\"\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}";
        List<Object> actions = invokeParseActions(plan);
        assertEquals(1, actions.size());
        Object action = actions.get(0);
        String typeName = readField(action, "type");
        assertTrue(typeName.contains("SET_VIEW_BOUNDS"));
        assertEquals("Passenger", readField(action, "existingName"));
        assertEquals("MyLab", readField(action, "name"));
        assertEquals("100", readField(action, "x"));
        assertEquals("200", readField(action, "y"));
        assertEquals("300", readField(action, "width"));
        assertEquals("150", readField(action, "height"));
    }

    @Test
    public void parsesRemoveViewElementSingleKey() throws Exception {
        String plan = "{\n" +
                "  \"actions\": [\n" +
                "    {\n" +
                "      \"remove_view_element\": {\n" +
                "        \"element\": \"Passenger\",\n" +
                "        \"diagram\": \"MyLab\"\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}";
        List<Object> actions = invokeParseActions(plan);
        assertEquals(1, actions.size());
        Object action = actions.get(0);
        String typeName = readField(action, "type");
        assertTrue(typeName.contains("REMOVE_VIEW_ELEMENT"));
        assertEquals("Passenger", readField(action, "existingName"));
        assertEquals("MyLab", readField(action, "name"));
    }

    @Test
    public void parsesArrangeSelection() throws Exception {
        String plan = "{\n" +
                "  \"actions\": [\n" +
                "    { \"type\": \"arrange_selection\", \"diagram\": \"MyLab\", \"strategy\": \"auto\" }\n" +
                "  ]\n" +
                "}";
        List<Object> actions = invokeParseActions(plan);
        assertEquals(1, actions.size());
        Object action = actions.get(0);
        String typeName = readField(action, "type");
        assertTrue(typeName.contains("ARRANGE_SELECTION"));
        assertEquals("MyLab", readField(action, "name"));
        assertEquals("auto", readField(action, "description")); // reuse description to hold strategy label
    }

    @Test
    public void parsesDiagramLifecycleActions() throws Exception {
        String plan = "{\n" +
                "  \"actions\": [\n" +
                "    { \"type\": \"rename_diagram\", \"diagram\": \"OldName\", \"name\": \"NewName\" },\n" +
                "    { \"delete_diagram\": { \"diagram\": \"ToDelete\" } },\n" +
                "    { \"duplicate_diagram\": { \"diagram\": \"Source\", \"name\": \"Copy\" } },\n" +
                "    { \"reveal_in_diagram\": { \"diagram\": \"MyLab\", \"element\": \"Passenger\" } }\n" +
                "  ]\n" +
                "}";
        List<Object> actions = invokeParseActions(plan);
        assertEquals(4, actions.size());
        // rename_diagram
        Object a0 = actions.get(0);
        assertTrue(readField(a0, "type").contains("RENAME_DIAGRAM"));
        assertEquals("OldName", readField(a0, "existingName"));
        assertEquals("NewName", readField(a0, "newName"));
        // delete_diagram
        Object a1 = actions.get(1);
        assertTrue(readField(a1, "type").contains("DELETE_DIAGRAM"));
        assertEquals("ToDelete", readField(a1, "existingName"));
        // duplicate_diagram
        Object a2 = actions.get(2);
        assertTrue(readField(a2, "type").contains("DUPLICATE_DIAGRAM"));
        assertEquals("Source", readField(a2, "existingName"));
        assertEquals("Copy", readField(a2, "newName"));
        // reveal_in_diagram
        Object a3 = actions.get(3);
        assertTrue(readField(a3, "type").contains("REVEAL_IN_DIAGRAM"));
        assertEquals("MyLab", readField(a3, "name"));
        assertEquals("Passenger", readField(a3, "existingName"));
    }

    @Test
    public void parsesStyleView() throws Exception {
        String plan = "{\n" +
                "  \"actions\": [\n" +
                "    { \"style_view\": { \"element\": \"Passenger\", \"foreground\": \"#112233\", \"background\": \"#445566\", \"border\": \"#AABBCC\", \"label_text\": \"VIP Passenger\", \"show_icon\": true } }\n" +
                "  ]\n" +
                "}";
        List<Object> actions = invokeParseActions(plan);
        assertEquals(1, actions.size());
        Object a0 = actions.get(0);
        assertTrue(readField(a0, "type").contains("STYLE_VIEW"));
        assertEquals("Passenger", readField(a0, "existingName"));
        assertEquals("true", readField(a0, "showIcon"));
        assertEquals("VIP Passenger", readField(a0, "labelText"));
    }

    @Test
    public void rejectsInvalidArrangeStrategy() throws Exception {
        String plan = "{\n" +
                "  \"actions\": [\n" +
                "    { \"type\": \"arrange_selection\", \"diagram\": \"MyLab\", \"strategy\": \"diagonal\" }\n" +
                "  ]\n" +
                "}";
        try {
            invokeParseActions(plan);
            fail("Expected schema validation to reject invalid strategy");
        } catch (Exception ex) {
            Throwable t = ex.getCause() != null ? ex.getCause() : ex;
            assertNotNull("expected a validation error message", t.getMessage());
        }
    }

    @Test
    public void rejectsInvalidLayerValue() throws Exception {
        String plan = "{\n" +
                "  \"actions\": [\n" +
                "    { \"type\": \"create_component\", \"layer\": \"foo\", \"name\": \"X\" }\n" +
                "  ]\n" +
                "}";
        try {
            invokeParseActions(plan);
            fail("Expected schema validation to reject invalid layer");
        } catch (Exception ex) {
            Throwable t = ex.getCause() != null ? ex.getCause() : ex;
            assertNotNull("expected a validation error message", t.getMessage());
        }
    }
}
