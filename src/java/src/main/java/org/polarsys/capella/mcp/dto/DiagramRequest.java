package org.polarsys.capella.mcp.dto;

import lombok.Data;
import java.util.List;

/**
 * Request DTO for creating Capella diagrams.
 */
@Data
public class DiagramRequest {
    private String name;           // Optional diagram name
    private String type;           // Required: Diagram type (e.g., LAB, PAB, OAB, SAB)
    private String layer;          // Required: ARCADIA layer (OA, SA, LA, PA)
    private String modelPath;      // Path to .aird model file
    private String targetElement;  // UUID of target element for context diagrams
    private List<String> initialElements; // UUIDs of elements to include initially
    private String description;    // Optional description
}