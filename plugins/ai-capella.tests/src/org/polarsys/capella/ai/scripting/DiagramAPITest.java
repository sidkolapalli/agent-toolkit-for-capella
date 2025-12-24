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

    @Test
    public void testDistributeValidShortcuts() {
        DiagramAPI api = new DiagramAPI(null);
        // Should NOT throw exception for valid args, but will return false due to null session
        boolean result = api.distribute(null, Collections.emptyList(), "h");
        assertFalse(result);
    }
}
