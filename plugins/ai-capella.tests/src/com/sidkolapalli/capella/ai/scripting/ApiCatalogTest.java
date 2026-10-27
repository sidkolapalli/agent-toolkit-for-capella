package com.sidkolapalli.capella.ai.scripting;

import static org.junit.Assert.*;

import org.junit.Test;

public class ApiCatalogTest {

    @Test
    public void arcadiaCatalogMakesNativeBuildersDiscoverableAndRejectsInventedMethods() {
        assertTrue(
                ApiCatalog.methodNames("arcadia")
                        .containsAll(
                                java.util.Set.of(
                                        "linearChain",
                                        "operationalScenario",
                                        "involveFunction",
                                        "involveComponent",
                                        "involveChain",
                                        "realizeCapability",
                                        "deploy",
                                        "physicalLink",
                                        "allocateExchange")));
        assertTrue(ApiCatalog.help("arcadia").contains("api.arcadia"));
        assertNull(ApiCatalog.validateCode("api.arcadia.deploy(behaviorPart, nodePart)"));
        assertNull(
                ApiCatalog.validateCode(
                        "api.arcadia().linearChain(root, name, functions, exchanges)"));
        assertNotNull(ApiCatalog.validateCode("api.arcadia.makeSafeVehicle()"));
        assertNotNull(ApiCatalog.validateCode("api.arcadia().fakeScenario()"));
    }

    @Test
    public void requirementCatalogIncludesNativeTraceAndImportMethods() {
        assertTrue(
                ApiCatalog.methodNames("requirements")
                        .containsAll(
                                java.util.Set.of(
                                        "createModule",
                                        "create",
                                        "importRequirements",
                                        "satisfy",
                                        "verify",
                                        "derive")));
        assertNull(ApiCatalog.validateCode("api.requirements().satisfy(component, requirement)"));
        assertNotNull(
                ApiCatalog.validateCode("api.requirements.fakeTrace(component, requirement)"));
    }

    @Test
    public void testHelpDiagramIncludesKnownMethods() {
        String json = ApiCatalog.help("diagram");
        assertNotNull(json);
        assertTrue(json.contains("\"api.diagram\""));
        assertTrue(json.contains("createSafe"));
        assertTrue(json.contains("addSafe"));
        assertTrue(json.contains("addByUuids"));
    }

    @Test
    public void testValidateCodeFlagsUnknownDiagramMethod() {
        String code = "api.diagram.addElement(diagram, comp)";
        String error = ApiCatalog.validateCode(code);
        assertNotNull(error);
        assertTrue(error.contains("Unknown methods"));
        assertTrue(error.contains("addByUuids"));
    }
}
