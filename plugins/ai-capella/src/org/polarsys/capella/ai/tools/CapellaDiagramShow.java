package org.polarsys.capella.ai.tools;

import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.diagram.AbstractDNode;
import org.eclipse.sirius.diagram.DDiagram;
import org.eclipse.sirius.diagram.DDiagramElement;
import org.polarsys.capella.ai.session.SessionSafeguards;
import org.polarsys.capella.core.data.fa.AbstractFunction;
import org.polarsys.capella.core.data.fa.ComponentExchange;
import org.polarsys.capella.core.data.fa.FunctionalExchange;
import org.polarsys.capella.core.sirius.analysis.ABServices;
import org.polarsys.capella.core.sirius.analysis.DDiagramContents;
import org.polarsys.capella.core.sirius.analysis.FaServices;

/**
 * Shows exchanges and functions the way Capella's own Insert tools do. In architecture diagrams a
 * function goes inside the component it is allocated to and a connector attaches to the right
 * ports; in data-flow diagrams an exchange is drawn between its functions, which are shown first.
 * Plain node creation cannot do either: connectors are edges, not shapes.
 */
public final class CapellaDiagramShow {
    private CapellaDiagramShow() {}

    /** Exchanges and functions in architecture and data-flow diagrams; everything else uses node creation. */
    public static boolean handles(DDiagram diagram, EObject element) {
        boolean kindHandled = element instanceof ComponentExchange || element instanceof FunctionalExchange
            || element instanceof AbstractFunction;
        return kindHandled && (architecture(diagram) || dataFlow(diagram) && !(element instanceof ComponentExchange));
    }

    /** Shows a handled element; returns whether it now has a view on the diagram. */
    public static boolean show(Session session, DDiagram diagram, EObject element) {
        SessionSafeguards.runWrite(session, "Show " + element.eClass().getName(), () -> {
            DDiagramContents contents = new DDiagramContents(diagram);
            if (architecture(diagram)) {
                ABServices services = ABServices.getService();
                if (element instanceof ComponentExchange) services.showABComponentExchange(List.of(element), contents);
                else if (element instanceof FunctionalExchange) services.showABFunctionalExchange(List.of(element), contents);
                else services.showABAbstractFunction(List.of(element), contents);
            } else if (element instanceof FunctionalExchange exchange) {
                FaServices services = FaServices.getFaServices();
                AbstractDNode source = null;
                for (EObject end : List.of(exchange.getSource(), exchange.getTarget())) {
                    AbstractFunction function = end instanceof AbstractFunction f ? f
                        : end != null && end.eContainer() instanceof AbstractFunction owner ? owner : null;
                    if (function == null) continue;
                    AbstractDNode node = services.showDFAbstractFunction(function, diagram, contents);
                    if (source == null) source = node;
                }
                if (source != null) services.showDFFunctionalExchange(source, exchange, contents, true);
            } else {
                FaServices.getFaServices().showDFAbstractFunction((AbstractFunction) element, diagram, contents);
            }
            contents.commitDeferredActions();
            return null;
        });
        for (var it = diagram.eAllContents(); it.hasNext();) {
            if (it.next() instanceof DDiagramElement view && view.getTarget() == element) return true;
        }
        return false;
    }

    private static boolean architecture(DDiagram diagram) {
        String kind = kind(diagram);
        return kind.endsWith("Architecture Blank") || "Operational Entity Blank".equals(kind);
    }

    private static boolean dataFlow(DDiagram diagram) {
        String kind = kind(diagram);
        return kind.endsWith("Data Flow Blank") || "Operational Activity Interaction Blank".equals(kind);
    }

    private static String kind(DDiagram diagram) {
        return diagram.getDescription() == null || diagram.getDescription().getName() == null ? "" : diagram.getDescription().getName();
    }
}
