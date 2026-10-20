package org.polarsys.capella.ai.scripting;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import java.util.Collections;
import org.junit.Test;

public class DiagramAPITest {

    @Test
    public void testAlignInvalidStrategyThrows() {
        DiagramAPI api = new DiagramAPI(null); // No session needed for arg check
        try {
            api.align(null, Collections.emptyList(), "diagonal");
            fail("Should throw IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // Expected
        }
    }

    @Test
    public void testDistributeInvalidStrategyThrows() {
        DiagramAPI api = new DiagramAPI(null);
        try {
            api.distribute(null, Collections.emptyList(), "diagonal");
            fail("Should throw IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // Expected
        }
    }

    /** Live FSAE run: the agent created an "Exchange Scenario" diagram, a name Capella 7 does not have. */
    @Test
    public void scenarioDiagramShortcutsUseCapella7DescriptionNames() throws Exception {
        DiagramAPI api = new DiagramAPI(null);
        var resolve = DiagramAPI.class.getDeclaredMethod("resolveDiagramDescription", String.class);
        resolve.setAccessible(true);
        org.junit.Assert.assertEquals("Component Exchanges Scenario", resolve.invoke(api, "ES"));
        org.junit.Assert.assertEquals("Component Interfaces Scenario", resolve.invoke(api, "IS"));
        org.junit.Assert.assertEquals("Functional Scenario", resolve.invoke(api, "FS"));
        org.junit.Assert.assertEquals("Earlier docs used this name", "Component Exchanges Scenario", resolve.invoke(api, "Exchange Scenario"));
        org.junit.Assert.assertEquals("Component Interfaces Scenario", resolve.invoke(api, "Interface Scenario"));
    }

    @Test
    public void testDistributeValidShortcuts() {
        DiagramAPI api = new DiagramAPI(null);
        // Should NOT throw exception for valid args, but will return false due to null session
        boolean result = api.distribute(null, Collections.emptyList(), "h");
        assertFalse(result);
    }
}
