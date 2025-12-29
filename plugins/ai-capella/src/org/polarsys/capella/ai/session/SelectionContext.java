package org.polarsys.capella.ai.session;

import java.util.List;
import java.util.Optional;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.sirius.viewpoint.DRepresentation;

/**
 * Immutable snapshot of the user's current UI context (selection + active diagram).
 *
 * <p>Captured on the UI thread via {@link SessionBridge#getSelectionContext()} and safe
 * to pass into background jobs and retry loops.
 */
public final class SelectionContext {

    private final List<EObject> selectedElements;
    private final DRepresentation activeDiagram;
    private final String activeDiagramName;
    private final String activeDiagramType;
    private final String activeDiagramUid;
    private final long capturedAtMs;

    private SelectionContext(
        List<EObject> selectedElements,
        DRepresentation activeDiagram,
        String activeDiagramName,
        String activeDiagramType,
        String activeDiagramUid,
        long capturedAtMs
    ) {
        this.selectedElements =
            selectedElements != null ? List.copyOf(selectedElements) : List.of();
        this.activeDiagram = activeDiagram;
        this.activeDiagramName = activeDiagramName;
        this.activeDiagramType = activeDiagramType;
        this.activeDiagramUid = activeDiagramUid;
        this.capturedAtMs = capturedAtMs;
    }

    public static SelectionContext of(
        List<EObject> selectedElements,
        DRepresentation activeDiagram
    ) {
        String name = null;
        String type = null;
        String uid = null;
        if (activeDiagram != null) {
            try {
                name = activeDiagram.getName();
            } catch (Exception ignored) {}
            try {
                type =
                    activeDiagram.eClass() != null
                        ? activeDiagram.eClass().getName()
                        : activeDiagram.getClass().getSimpleName();
            } catch (Exception ignored) {}
            try {
                uid = activeDiagram.getUid();
            } catch (Exception ignored) {}
        }
        return new SelectionContext(
            selectedElements,
            activeDiagram,
            name,
            type,
            uid,
            System.currentTimeMillis()
        );
    }

    public static SelectionContext empty() {
        return new SelectionContext(
            List.of(),
            null,
            null,
            null,
            null,
            System.currentTimeMillis()
        );
    }

    public List<EObject> getSelectedElements() {
        return selectedElements;
    }

    public Optional<DRepresentation> getActiveDiagram() {
        return Optional.ofNullable(activeDiagram);
    }

    public String getActiveDiagramName() {
        return activeDiagramName;
    }

    public String getActiveDiagramType() {
        return activeDiagramType;
    }

    public String getActiveDiagramUid() {
        return activeDiagramUid;
    }

    public long getCapturedAtMs() {
        return capturedAtMs;
    }

    public boolean hasSelection() {
        return selectedElements != null && !selectedElements.isEmpty();
    }

    public boolean hasDiagram() {
        return activeDiagram != null;
    }
}

