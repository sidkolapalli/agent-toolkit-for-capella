package org.polarsys.capella.mcp.controller;

import org.polarsys.capella.mcp.dto.DiagramRequest;
import org.polarsys.capella.mcp.dto.DiagramResponse;
import org.polarsys.capella.mcp.service.DiagramService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST Controller for Capella Diagram operations.
 * TDD REFACTOR Phase: Uses real Capella API integration.
 */
@RestController
@RequestMapping("/api/v1")
public class DiagramController {
    
    private final DiagramService diagramService;
    
    @Autowired
    public DiagramController(DiagramService diagramService) {
        this.diagramService = diagramService;
    }
    
    /**
     * Create a new diagram in Capella model.
     * Supports all 32 diagram type/layer combinations.
     */
    @PostMapping("/diagrams")
    public ResponseEntity<DiagramResponse> createDiagram(@RequestBody DiagramRequest request) {
        try {
            DiagramResponse response = diagramService.createDiagram(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(
                DiagramResponse.error(e.getMessage())
            );
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                DiagramResponse.error("Failed to create diagram: " + e.getMessage())
            );
        }
    }
    
    /**
     * Get diagram by ID.
     */
    @GetMapping("/diagrams/{diagramId}")
    public ResponseEntity<DiagramResponse> getDiagram(@PathVariable String diagramId) {
        try {
            DiagramResponse response = diagramService.getDiagram(diagramId);
            if (response == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                DiagramResponse.error("Failed to get diagram: " + e.getMessage())
            );
        }
    }
    
    /**
     * List all diagrams in model.
     */
    @GetMapping("/diagrams")
    public ResponseEntity<List<DiagramResponse>> listDiagrams(
            @RequestParam(required = false) String modelPath,
            @RequestParam(required = false) String layer,
            @RequestParam(required = false) String type) {
        try {
            List<DiagramResponse> diagrams = diagramService.listDiagrams(modelPath, layer, type);
            return ResponseEntity.ok(diagrams);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }
    
    /**
     * Update diagram elements (add/remove components).
     */
    @PutMapping("/diagrams/{diagramId}/elements")
    public ResponseEntity<DiagramResponse> updateDiagramElements(
            @PathVariable String diagramId,
            @RequestBody Map<String, List<String>> elements) {
        try {
            DiagramResponse response = diagramService.updateDiagramElements(diagramId, elements);
            if (response == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                DiagramResponse.error("Failed to update diagram: " + e.getMessage())
            );
        }
    }
    
    /**
     * Delete a diagram.
     */
    @DeleteMapping("/diagrams/{diagramId}")
    public ResponseEntity<Map<String, String>> deleteDiagram(@PathVariable String diagramId) {
        try {
            boolean deleted = diagramService.deleteDiagram(diagramId);
            if (!deleted) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Diagram deleted successfully",
                "diagramId", diagramId
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                Map.of("status", "error", "message", e.getMessage())
            );
        }
    }
    
    /**
     * Health check endpoint for integration testing.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", "Capella Diagram Service",
            "phase", "TDD REFACTOR (Real)",
            "version", "1.0.0",
            "diagramsCreated", diagramService.getTotalDiagramsCreated()
        ));
    }
}