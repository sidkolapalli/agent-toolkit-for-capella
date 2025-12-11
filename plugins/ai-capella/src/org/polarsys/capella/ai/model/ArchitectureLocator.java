package org.polarsys.capella.ai.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.model.ActionResult.ErrorCode;
import org.polarsys.capella.core.data.capellamodeller.Project;
import org.polarsys.capella.core.data.la.LogicalArchitecture;
import org.polarsys.capella.core.data.oa.OperationalAnalysis;
import org.polarsys.capella.core.data.pa.PhysicalArchitecture;

/** Find ARCADIA architecture roots in the current model. */
public final class ArchitectureLocator {

    private ArchitectureLocator() {}

    /** All valid layer identifiers */
    private static final List<String> VALID_LAYERS = List.of(
        "OA",
        "SA",
        "LA",
        "PA"
    );

    public static EObject findTargetElement(Session session, String layer) {
        try {
            for (Resource res : session.getSemanticResources()) {
                if (res.getContents().isEmpty()) continue;
                EObject root = res.getContents().get(0);
                EObject found = findInProject(root, layer);
                if (found != null) {
                    return found;
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    public static EObject findTargetElement(
        ResourceSet resourceSet,
        String layer
    ) {
        if (resourceSet == null) {
            return null;
        }
        try {
            for (Resource res : resourceSet.getResources()) {
                if (res == null || res.getContents().isEmpty()) {
                    continue;
                }
                EObject root = res.getContents().get(0);
                EObject found = findInProject(root, layer);
                if (found != null) {
                    return found;
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    private static EObject findInProject(EObject root, String layer) {
        if (!(root instanceof Project)) {
            return null;
        }
        Project project = (Project) root;

        // First, find SystemEngineering - architecture layers are nested under it
        EObject systemEngineering = null;
        for (EObject child : project.eContents()) {
            if (child.eClass().getName().equals("SystemEngineering")) {
                systemEngineering = child;
                break;
            }
        }

        // If no SystemEngineering found, fall back to direct children (legacy models)
        Iterable<EObject> searchIn = systemEngineering != null
            ? systemEngineering.eContents()
            : project.eContents();

        for (EObject child : searchIn) {
            if (
                "OA".equalsIgnoreCase(layer) &&
                child instanceof OperationalAnalysis
            ) return child;
            if (
                "LA".equalsIgnoreCase(layer) &&
                child instanceof LogicalArchitecture
            ) return child;
            if (
                "PA".equalsIgnoreCase(layer) &&
                child instanceof PhysicalArchitecture
            ) return child;
            if (
                "SA".equalsIgnoreCase(layer) &&
                child.eClass().getName().equals("SystemAnalysis")
            ) return child;
        }
        return null;
    }

    // ========== Diagnostic Methods ==========

    /**
     * Find architecture layer with full diagnostic information.
     * Returns FindResult with available layers when target layer is not found.
     *
     * @param session the Capella session
     * @param layer the layer identifier (OA, SA, LA, PA, or variants like "operational", "logical")
     * @return FindResult with layer element, error code, and available layers
     */
    public static FindResult findLayerWithDiagnostic(
        Session session,
        String layer
    ) {
        // Input validation
        if (session == null) {
            return FindResult.error(
                ErrorCode.SESSION_ERROR,
                "No active Capella session"
            );
        }
        if (layer == null || layer.isBlank()) {
            return FindResult.error(
                ErrorCode.INVALID_INPUT,
                "Layer identifier is required"
            );
        }

        // Canonicalize layer name
        String canonical = canonicalizeLayer(layer);
        if (canonical == null) {
            return FindResult.error(
                ErrorCode.INVALID_INPUT,
                "Invalid layer identifier: '" +
                    layer +
                    "'. Valid layers: " +
                    String.join(", ", VALID_LAYERS)
            );
        }

        // Find available layers
        List<String> availableLayers = new ArrayList<>();
        try {
            for (Resource res : session.getSemanticResources()) {
                if (res == null || res.getContents().isEmpty()) {
                    continue;
                }
                EObject root = res.getContents().get(0);
                if (root instanceof Project project) {
                    // Find SystemEngineering first - layers are nested under it
                    EObject systemEngineering = null;
                    for (EObject child : project.eContents()) {
                        if (
                            child.eClass().getName().equals("SystemEngineering")
                        ) {
                            systemEngineering = child;
                            break;
                        }
                    }

                    // Search in SystemEngineering if found, otherwise in project
                    Iterable<EObject> searchIn = systemEngineering != null
                        ? systemEngineering.eContents()
                        : project.eContents();

                    for (EObject child : searchIn) {
                        if (
                            child instanceof OperationalAnalysis &&
                            !availableLayers.contains("OA")
                        ) {
                            availableLayers.add("OA");
                        } else if (
                            child instanceof LogicalArchitecture &&
                            !availableLayers.contains("LA")
                        ) {
                            availableLayers.add("LA");
                        } else if (
                            child instanceof PhysicalArchitecture &&
                            !availableLayers.contains("PA")
                        ) {
                            availableLayers.add("PA");
                        } else if (
                            child.eClass().getName().equals("SystemAnalysis") &&
                            !availableLayers.contains("SA")
                        ) {
                            availableLayers.add("SA");
                        }
                    }
                }
            }
        } catch (Exception ex) {
            return FindResult.error(
                ErrorCode.SESSION_ERROR,
                "Error searching model: " + ex.getMessage()
            );
        }

        // Find the requested layer
        EObject layerElement = findTargetElement(session, canonical);
        if (layerElement == null) {
            if (availableLayers.isEmpty()) {
                return FindResult.notFound(
                    "Layer",
                    canonical,
                    List.of(),
                    "No architecture layers found in the model. Model may not be properly initialized."
                );
            }
            return FindResult.notFound(
                "Layer",
                canonical,
                availableLayers,
                "Layer '" +
                    canonical +
                    "' not found. Available layers: " +
                    String.join(", ", availableLayers)
            );
        }

        return FindResult.success(layerElement);
    }

    /**
     * Canonicalize layer identifier to standard form (OA, SA, LA, PA).
     *
     * @param layer the layer identifier (e.g., "operational", "system", "logical", "physical", or abbreviations)
     * @return canonical form or null if invalid
     */
    public static String canonicalizeLayer(String layer) {
        if (layer == null) {
            return null;
        }
        String lower = layer.toLowerCase(Locale.ROOT).trim();
        return switch (lower) {
            case "oa", "operational", "operational analysis" -> "OA";
            case "sa", "system", "system analysis" -> "SA";
            case "la", "logical", "logical architecture" -> "LA";
            case "pa", "physical", "physical architecture" -> "PA";
            default -> {
                // Check if it's already a valid canonical form
                String upper = layer.toUpperCase(Locale.ROOT).trim();
                yield VALID_LAYERS.contains(upper) ? upper : null;
            }
        };
    }

    /**
     * Get list of valid layer identifiers.
     */
    public static List<String> getValidLayers() {
        return VALID_LAYERS;
    }
}
