package com.sidkolapalli.capella.ai.scripting;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.List;

public class CapellaAPITest {

    @Test
    public void testValidateWithoutSession() {
        CapellaAPI api = new CapellaAPI(null);
        List<String> messages = api.validate();

        assertFalse(messages.isEmpty());
        assertTrue(messages.get(0).toLowerCase().contains("validation"));
    }

    @Test
    public void testValidateLayerWithoutSession() {
        CapellaAPI api = new CapellaAPI(null);
        List<String> messages = api.validate("LA");

        assertFalse(messages.isEmpty());
        assertTrue(messages.get(0).toLowerCase().contains("validation"));
    }
}
