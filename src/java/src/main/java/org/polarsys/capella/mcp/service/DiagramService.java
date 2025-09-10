package org.polarsys.capella.mcp.service;

import org.polarsys.capella.mcp.dto.DiagramRequest;
import org.polarsys.capella.mcp.dto.DiagramResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Service for managing Capella diagrams.
 * TDD GREEN Phase: In-memory implementation that tracks diagrams realistically.
 */
@Service
public class DiagramService {
    
    // In-memory storage for diagrams
    private final Map<String, DiagramResponse> diagrams = new ConcurrentHashMap<>();
    private final AtomicInteger diagramCounter = new AtomicInteger(0);
    
    // Valid diagram types per ARCADIA layer
    private static final Map<String, Set<String>> VALID_DIAGRAM_TYPES = Map.of(
        "OA", Set.of("OAB", "OES", "OEB", "ORB", "OCB", "OPD", "IS", "CDB"),
        "SA", Set.of("SAB", "ES", "FS", "EAB", "CSA", "IS", "CDB", "SDFB"),
        "LA", Set.of("LAB", "LDFB", "CDB", "IS", "EAB", "FS", "ES", "LFBD"),
        "PA", Set.of("PAB", "PDFB", "CDB", "IS", "EAB", "FS", "ES", "PFBD")
    );
    
    /**
     * Create a new diagram with realistic tracking.
     */
    public DiagramResponse createDiagram(DiagramRequest request) {
        // Validate request
        validateDiagramRequest(request);
        
        // Generate unique diagram ID
        String diagramId = generateDiagramId(request.getLayer(), request.getType());
        
        // Create diagram response with realistic metadata
        DiagramResponse response = new DiagramResponse();
        response.setDiagramId(diagramId);
        response.setName(request.getName() != null ? request.getName() : 
            String.format("[%s] %s", request.getType(), "Generated Diagram " + diagramCounter.get()));
        response.setType(request.getType());
        response.setLayer(request.getLayer());
        response.setModelPath(request.getModelPath());
        response.setStatus("CREATED");
        response.setCreatedAt(LocalDateTime.now().toString());
        response.setElementCount(0);
        
        // Add initial elements if provided
        if (request.getInitialElements() != null && !request.getInitialElements().isEmpty()) {
            response.setElements(new ArrayList<>(request.getInitialElements()));
            response.setElementCount(request.getInitialElements().size());
        } else {
            response.setElements(new ArrayList<>());
        }
        
        // Add metadata
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("createdBy", "Capella Diagram Service");
        metadata.put("version", "1.0.0");
        metadata.put("persistenceMode", "MOCK");
        metadata.put("diagramFile", String.format("%s.aird#%s", 
            request.getModelPath() != null ? request.getModelPath() : "model", diagramId));
        response.setMetadata(metadata);
        
        // Store in memory
        diagrams.put(diagramId, response);
        
        return response;
    }
    
    /**
     * Get diagram by ID.
     */
    public DiagramResponse getDiagram(String diagramId) {
        return diagrams.get(diagramId);
    }
    
    /**
     * List diagrams with optional filtering.
     */
    public List<DiagramResponse> listDiagrams(String modelPath, String layer, String type) {
        return diagrams.values().stream()
            .filter(d -> modelPath == null || modelPath.equals(d.getModelPath()))
            .filter(d -> layer == null || layer.equals(d.getLayer()))
            .filter(d -> type == null || type.equals(d.getType()))
            .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
            .collect(Collectors.toList());
    }
    
    /**
     * Update diagram elements.
     */
    public DiagramResponse updateDiagramElements(String diagramId, Map<String, List<String>> elements) {
        DiagramResponse diagram = diagrams.get(diagramId);
        if (diagram == null) {
            return null;
        }
        
        // Handle add elements
        if (elements.containsKey("add")) {
            List<String> currentElements = diagram.getElements();
            if (currentElements == null) {
                currentElements = new ArrayList<>();
            }
            for (String element : elements.get("add")) {
                if (!currentElements.contains(element)) {
                    currentElements.add(element);
                }
            }
            diagram.setElements(currentElements);
        }
        
        // Handle remove elements
        if (elements.containsKey("remove")) {
            List<String> currentElements = diagram.getElements();
            if (currentElements != null) {
                currentElements.removeAll(elements.get("remove"));
                diagram.setElements(currentElements);
            }
        }
        
        // Update element count
        diagram.setElementCount(diagram.getElements() != null ? diagram.getElements().size() : 0);
        diagram.setStatus("UPDATED");
        diagram.getMetadata().put("lastModified", LocalDateTime.now().toString());
        
        return diagram;
    }
    
    /**
     * Delete a diagram.
     */
    public boolean deleteDiagram(String diagramId) {
        return diagrams.remove(diagramId) != null;
    }
    
    /**
     * Get total number of diagrams created (for metrics).
     */
    public int getTotalDiagramsCreated() {
        return diagramCounter.get();
    }
    
    /**
     * Validate diagram creation request.
     */
    private void validateDiagramRequest(DiagramRequest request) {
        if (request.getLayer() == null || request.getLayer().isEmpty()) {
            throw new IllegalArgumentException("Layer is required");
        }
        
        if (request.getType() == null || request.getType().isEmpty()) {
            throw new IllegalArgumentException("Diagram type is required");
        }
        
        // Validate layer
        if (!VALID_DIAGRAM_TYPES.containsKey(request.getLayer())) {
            throw new IllegalArgumentException("Invalid layer: " + request.getLayer() + 
                ". Valid layers are: OA, SA, LA, PA");
        }
        
        // Validate diagram type for layer
        Set<String> validTypes = VALID_DIAGRAM_TYPES.get(request.getLayer());
        if (!validTypes.contains(request.getType())) {
            throw new IllegalArgumentException(String.format(
                "Invalid diagram type '%s' for layer '%s'. Valid types are: %s",
                request.getType(), request.getLayer(), validTypes
            ));
        }
    }
    
    /**
     * Generate unique diagram ID with meaningful prefix.
     */
    private String generateDiagramId(String layer, String type) {
        int id = diagramCounter.incrementAndGet();
        return String.format("%s_%s_%s_%d", 
            layer.toLowerCase(), 
            type.toLowerCase(), 
            UUID.randomUUID().toString().substring(0, 8),
            id
        );
    }
}