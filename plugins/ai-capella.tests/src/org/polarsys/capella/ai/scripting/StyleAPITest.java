package org.polarsys.capella.ai.scripting;

import static org.junit.Assert.fail;

import org.junit.Test;

public class StyleAPITest {

    @Test
    public void testSetColorValidation() {
        StyleAPI api = new StyleAPI(null, null);

        try {
            api.setColor(null, null, "invalid-color-name");
            fail("Should throw IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // Expected
        }
    }
}
