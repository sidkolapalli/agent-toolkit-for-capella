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
}
