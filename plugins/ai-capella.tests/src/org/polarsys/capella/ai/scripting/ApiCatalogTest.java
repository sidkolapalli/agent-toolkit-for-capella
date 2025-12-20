package org.polarsys.capella.ai.scripting;

import static org.junit.Assert.*;

import org.junit.Test;

public class ApiCatalogTest {

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
