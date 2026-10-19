package org.polarsys.capella.ai;

import java.util.LinkedHashSet;
import java.util.Set;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.viewpoint.DSemanticDecorator;
import org.polarsys.capella.ai.session.SelectionContext;

/** Small, read-only descriptions of the native selection. Called on SWT. */
final class ChatContextDetails {
    private ChatContextDetails() {}

    static String modelName(Session session) {
        if (session == null || !session.isOpen()) return "No active model";
        for (Resource resource : session.getSemanticResources()) {
            for (EObject root : resource.getContents()) {
                if ("Project".equals(root.eClass().getName())) {
                    String name = feature(root, "name");
                    if (!name.isBlank()) return name;
                }
            }
        }
        if (session.getSessionResource() != null && session.getSessionResource().getURI() != null) {
            String segment = URI.decode(session.getSessionResource().getURI().lastSegment());
            if (segment != null) return segment.replaceFirst("\\.aird$", "");
        }
        return "Active Capella model";
    }

    static String selectionSummary(SelectionContext context) {
        String selection = "Select an element or open a diagram for context";
        if (context.hasSelection()) {
            EObject first = context.getSelectedElements().get(0);
            String layer = layer(first);
            selection = (layer.isBlank() ? "" : layer + " · ") + describe(first);
            if (context.getSelectedElements().size() > 1) {
                selection += " · " + context.getSelectedElements().size() + " selected";
            }
        } else if (context.hasDiagram()) {
            String layer = context.getActiveDiagram().get() instanceof DSemanticDecorator semantic
                ? layer(semantic.getTarget()) : "";
            selection = (layer.isBlank() ? "" : layer + " · ") + "Diagram: " + context.getActiveDiagramName();
        }
        return selection;
    }

    static String inspect(Session session, SelectionContext context) {
        StringBuilder text = new StringBuilder("Model: ").append(modelName(session));
        if (session != null && session.getSessionResource() != null) {
            text.append("\nSession: ").append(session.getSessionResource().getURI());
        }
        if (context.hasDiagram()) {
            text.append("\nDiagram: ").append(context.getActiveDiagramName())
                .append("\nDiagram type: ").append(context.getActiveDiagramType());
        }
        if (!context.hasSelection()) text.append("\nNo semantic element is selected.");
        int count = 0;
        for (EObject element : context.getSelectedElements()) {
            if (++count > 50) break;
            text.append("\n\n").append(describe(element));
            String layer = layer(element);
            if (!layer.isBlank()) text.append("\nLayer: ").append(layer);
            String id = feature(element, "id");
            if (!id.isBlank()) text.append("\nID: ").append(id);
            if (element.eContainer() != null) text.append("\nContainer: ").append(describe(element.eContainer()));
        }
        if (context.getSelectedElements().size() > 50) text.append("\nShowing the first 50 selected elements.");
        text.append("\n\nThis context is read locally. It does not send a request or change the model.");
        return text.toString();
    }

    static String layer(EObject element) {
        Set<EObject> visited = new LinkedHashSet<>();
        for (EObject current = element; current != null && visited.add(current); current = current.eContainer()) {
            String layer = switch (current.eClass().getName()) {
                case "OperationalAnalysis" -> "OA";
                case "SystemAnalysis" -> "SA";
                case "LogicalArchitecture" -> "LA";
                case "PhysicalArchitecture" -> "PA";
                case "EPBSArchitecture" -> "EPBS";
                default -> "";
            };
            if (!layer.isBlank()) return layer;
        }
        return "";
    }

    private static String describe(EObject element) {
        String name = feature(element, "name");
        return element.eClass().getName() + (name.isBlank() ? "" : ": " + name);
    }

    private static String feature(EObject element, String name) {
        var feature = element.eClass().getEStructuralFeature(name);
        Object value = feature == null ? null : element.eGet(feature);
        return value == null ? "" : value.toString();
    }
}
