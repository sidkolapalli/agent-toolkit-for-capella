package com.sidkolapalli.capella.ai.scripting;

import static org.junit.Assert.*;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

public class DiagramAPIPolymorphismTest {

    @Test
    public void testAlignAndDistributeAcceptListOfUnknownTypeAtCompileTime() {
        DiagramAPI api = new DiagramAPI(null);
        List<Object> elements = List.of(new Object());

        try {
            api.align(null, elements, "top");
            fail("Expected IllegalArgumentException for unsupported element type");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Unsupported element type"));
        }

        try {
            api.distribute(null, elements, "horizontal");
            fail("Expected IllegalArgumentException for unsupported element type");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Unsupported element type"));
        }
    }

    @Test
    public void testToSemanticTargetsPrefersDDiagramElementTarget() throws Exception {
        ClassLoader apiCl = DiagramAPI.class.getClassLoader();
        Class<?> eObjectType = Class.forName("org.eclipse.emf.ecore.EObject", true, apiCl);
        Class<?> ddeType = Class.forName("org.eclipse.sirius.diagram.DDiagramElement", true, apiCl);

        Object target =
                Proxy.newProxyInstance(
                        eObjectType.getClassLoader(),
                        new Class<?>[] {eObjectType},
                        (proxy, method, args) -> null);

        Object dde =
                Proxy.newProxyInstance(
                        ddeType.getClassLoader(),
                        new Class<?>[] {ddeType},
                        (proxy, method, args) -> {
                            if ("getTarget".equals(method.getName())) {
                                return target;
                            }
                            return null;
                        });

        Method m = DiagramAPI.class.getDeclaredMethod("toSemanticTargets", List.class);
        m.setAccessible(true);
        Object result = m.invoke(null, List.of(dde));
        assertTrue(result instanceof List<?>);
        List<?> semantics = (List<?>) result;

        assertEquals(1, semantics.size());
        assertSame(target, semantics.get(0));
    }
}
