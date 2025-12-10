package org.polarsys.capella.ai.llm;

import static org.junit.Assert.*;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.Test;

public class ToolRouterChangeSetTest {

    private static Object parsePlan(String json) throws Exception {
        Class<?> tr = ToolRouter.class;
        try {
            Method m = tr.getDeclaredMethod("parsePlan", String.class);
            m.setAccessible(true);
            return m.invoke(null, json);
        } catch (NoSuchMethodException e) {
            // Fall back to private parseActions + wrap into ToolPlan via inner constructor
            Method parse = tr.getDeclaredMethod("parseActions", String.class);
            parse.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Object> actions = (List<Object>) parse.invoke(null, json);
            // Locate ToolPlan(List<Action>) constructor
            for (Class<?> inner : tr.getDeclaredClasses()) {
                if (inner.getSimpleName().equals("ToolPlan")) {
                    var ctor = inner.getDeclaredConstructors()[0];
                    ctor.setAccessible(true);
                    return ctor.newInstance(actions);
                }
            }
            throw new IllegalStateException("ToolPlan inner class not found");
        }
    }

    private static List<?> items(Object plan) throws Exception {
        var m = plan.getClass().getDeclaredMethod("items");
        m.setAccessible(true);
        return (List<?>) m.invoke(plan);
    }

    private static String itemField(Object item, String getter) throws Exception {
        var m = item.getClass().getDeclaredMethod(getter);
        m.setAccessible(true);
        Object v = m.invoke(item);
        return v == null ? null : String.valueOf(v);
    }

    @Test
    public void categoriesAndDiffSummariesAreDerived() throws Exception {
        String plan = "{\n" +
                "  \"actions\": [\n" +
                "    { \"create_component\": { \"layer\": \"LA\", \"name\": \"Flight Computer\" } },\n" +
                "    { \"type\": \"rename\", \"class\": \"LogicalComponent\", \"name\": \"Computer\", \"new_name\": \"FCU\" },\n" +
                "    { \"delete\": { \"class\": \"LogicalComponent\", \"name\": \"Obsolete\" } },\n" +
                "    { \"open_diagram\": { \"type\": \"LAB\", \"name\": \"Overview\" } }\n" +
                "  ]\n" +
                "}";
        Object toolPlan = parsePlan(plan);
        List<?> items = items(toolPlan);
        assertEquals(4, items.size());

        // Create → CREATE category + summary
        Object i0 = items.get(0);
        assertEquals("CREATE", itemField(i0, "getCategory"));
        assertEquals("Create Component 'Flight Computer' in LA", itemField(i0, "getDiffSummary"));

        // Rename → UPDATE category + summary
        Object i1 = items.get(1);
        assertEquals("UPDATE", itemField(i1, "getCategory"));
        assertEquals("Rename LogicalComponent 'Computer' → 'FCU'", itemField(i1, "getDiffSummary"));

        // Delete → DELETE category + summary
        Object i2 = items.get(2);
        assertEquals("DELETE", itemField(i2, "getCategory"));
        assertEquals("Delete LogicalComponent 'Obsolete'", itemField(i2, "getDiffSummary"));

        // Open → OPEN category + summary
        Object i3 = items.get(3);
        assertEquals("OPEN", itemField(i3, "getCategory"));
        assertEquals("Open LAB diagram (Overview)", itemField(i3, "getDiffSummary"));
    }

    @Test
    public void acceptsPerDiagramArrangeStrategies() throws Exception {
        String plan = "{\n" +
                "  \"actions\": [\n" +
                "    { \"arrange_selection\": { \"strategy\": \"la_flow\" } },\n" +
                "    { \"arrange_selection\": { \"strategy\": \"pa_radial\" } }\n" +
                "  ]\n" +
                "}";
        Object toolPlan = parsePlan(plan);
        List<?> items = items(toolPlan);
        assertEquals(2, items.size());
        assertEquals("Arrange selection (la_flow)", itemField(items.get(0), "getDiffSummary"));
        assertEquals("Arrange selection (pa_radial)", itemField(items.get(1), "getDiffSummary"));
    }
}

