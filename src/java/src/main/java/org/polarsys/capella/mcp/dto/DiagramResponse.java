package org.polarsys.capella.mcp.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * Response DTO for Capella diagram operations.
 */
@Data
public class DiagramResponse {
    private String diagramId;      // Unique diagram identifier
    private String name;           // Diagram name
    private String type;           // Diagram type
    private String layer;          // ARCADIA layer
    private String modelPath;      // Path to model file
    private String status;         // CREATED, UPDATED, ERROR
    private String createdAt;      // Creation timestamp
    private int elementCount;      // Number of elements in diagram
    private List<String> elements; // Element UUIDs
    private Map<String, Object> metadata; // Additional metadata
    private String error;          // Error message if any
    
    /**
     * Factory method for error responses.
     */
    public static DiagramResponse error(String message) {
        DiagramResponse response = new DiagramResponse();
        response.setStatus("ERROR");
        response.setError(message);
        return response;
    }
}