package org.polarsys.capella.ai.tools;

import static org.junit.Assert.*;

import org.eclipse.sirius.diagram.DSemanticDiagram;
import org.eclipse.sirius.diagram.DiagramFactory;
import org.eclipse.sirius.diagram.description.DescriptionFactory;
import org.junit.Test;
import org.polarsys.capella.core.data.fa.FaFactory;
import org.polarsys.capella.core.data.la.LaFactory;

/** Which elements go through Capella's Insert services instead of plain node creation. */
public class CapellaDiagramShowTest {
    @Test
    public void architectureDiagramsRouteExchangesAndFunctionsButNotComponents() {
        DSemanticDiagram lab = diagram("Logical Architecture Blank");
        assertTrue(CapellaDiagramShow.handles(lab, FaFactory.eINSTANCE.createComponentExchange()));
        assertTrue(CapellaDiagramShow.handles(lab, FaFactory.eINSTANCE.createFunctionalExchange()));
        assertTrue(CapellaDiagramShow.handles(lab, LaFactory.eINSTANCE.createLogicalFunction()));
        assertFalse(
                "Components keep the part-aware node path",
                CapellaDiagramShow.handles(lab, LaFactory.eINSTANCE.createLogicalComponent()));
        assertTrue(
                "Operational entity diagrams are architecture diagrams",
                CapellaDiagramShow.handles(
                        diagram("Operational Entity Blank"),
                        FaFactory.eINSTANCE.createComponentExchange()));
    }

    @Test
    public void dataFlowDiagramsRouteFunctionalExchangesAndFunctionsOnly() {
        DSemanticDiagram ldfb = diagram("Logical Data Flow Blank");
        assertTrue(
                CapellaDiagramShow.handles(ldfb, FaFactory.eINSTANCE.createFunctionalExchange()));
        assertTrue(CapellaDiagramShow.handles(ldfb, LaFactory.eINSTANCE.createLogicalFunction()));
        assertFalse(
                "Component exchanges are not drawn in data-flow diagrams",
                CapellaDiagramShow.handles(ldfb, FaFactory.eINSTANCE.createComponentExchange()));
        assertTrue(
                CapellaDiagramShow.handles(
                        diagram("Operational Activity Interaction Blank"),
                        FaFactory.eINSTANCE.createFunctionalExchange()));
    }

    @Test
    public void otherDiagramKindsAndMissingDescriptionsFallBack() {
        assertFalse(
                CapellaDiagramShow.handles(
                        diagram("Logical Functional Breakdown"),
                        FaFactory.eINSTANCE.createFunctionalExchange()));
        DSemanticDiagram bare = DiagramFactory.eINSTANCE.createDSemanticDiagram();
        assertFalse(
                CapellaDiagramShow.handles(bare, FaFactory.eINSTANCE.createComponentExchange()));
    }

    private static DSemanticDiagram diagram(String kind) {
        DSemanticDiagram diagram = DiagramFactory.eINSTANCE.createDSemanticDiagram();
        var description = DescriptionFactory.eINSTANCE.createDiagramDescription();
        description.setName(kind);
        diagram.setDescription(description);
        return diagram;
    }
}
