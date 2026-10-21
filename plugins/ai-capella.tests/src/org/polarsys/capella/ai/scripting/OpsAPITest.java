package org.polarsys.capella.ai.scripting;

import static org.junit.Assert.*;

import org.junit.Test;
import org.polarsys.capella.core.data.ctx.CtxFactory;
import org.polarsys.capella.core.data.fa.FaFactory;
import org.polarsys.capella.core.data.la.LaFactory;

/** Which native TransitionCommandHelper command each transition kind runs. */
public class OpsAPITest {

    @Test
    public void layerKindsPickTheCommandFromTheSelection() {
        assertEquals(
                "getFunctionalTransitionCommand",
                OpsAPI.command("SA2LA", FaFactory.eINSTANCE.createFunctionalExchange()));
        assertEquals(
                "getCapabilityTransitionCommand",
                OpsAPI.command("OA2SA", CtxFactory.eINSTANCE.createCapability()));
        assertEquals(
                "getSystemTransitionCommand",
                OpsAPI.command("SA2LA", LaFactory.eINSTANCE.createLogicalComponent()));
    }

    @Test
    public void everyNativeTransitionHasAKind() {
        assertEquals(
                "getLC2PCTransitionCommand",
                OpsAPI.command("LA2PA", LaFactory.eINSTANCE.createLogicalComponent()));
        assertEquals("getPC2CITransitionCommand", OpsAPI.command("pa2epbs", null));
        assertEquals("getFunctionalTransitionCommand", OpsAPI.command("functional", null));
        assertEquals("getDataTransitionCommand", OpsAPI.command("data", null));
        assertEquals("getExchangeItemTransitionCommand", OpsAPI.command("exchange-item", null));
        assertEquals("getInterfaceTransitionCommand", OpsAPI.command("interface", null));
        assertEquals("getStateMachineTransitionCommand", OpsAPI.command("state-machine", null));
        assertEquals("getActorTransitionCommand", OpsAPI.command("actor", null));
        assertEquals("getCapabilityTransitionCommand", OpsAPI.command("capability", null));
        assertEquals("getPropertyValueTransitionCommand", OpsAPI.command("property-value", null));
        assertEquals("getOE2ActorTransitionCommand", OpsAPI.command("entity-to-actor", null));
        assertEquals("getOE2SystemTransitionCommand", OpsAPI.command("entity-to-system", null));
    }

    @Test
    public void unknownKindsNameTheSupportedOnes() {
        var error =
                assertThrows(IllegalArgumentException.class, () -> OpsAPI.command("LA2EPBS", null));
        assertTrue(error.getMessage(), error.getMessage().contains("LA2PA"));
        assertTrue(error.getMessage(), error.getMessage().contains("state-machine"));
    }
}
