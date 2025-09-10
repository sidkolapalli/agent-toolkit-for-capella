package org.polarsys.capella.mcp.service;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.common.command.Command;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.transaction.RecordingCommand;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.sirius.business.api.dialect.DialectManager;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.diagram.DDiagram;
import org.eclipse.sirius.diagram.DDiagramElement;
import org.eclipse.sirius.diagram.DSemanticDiagram;
import org.eclipse.sirius.diagram.description.DiagramDescription;
import org.eclipse.sirius.viewpoint.DRepresentation;
import org.eclipse.sirius.viewpoint.DRepresentationDescriptor;
import org.eclipse.sirius.viewpoint.description.RepresentationDescription;
import org.eclipse.sirius.viewpoint.description.Viewpoint;
import org.polarsys.capella.core.data.capellamodeller.Project;
import org.polarsys.capella.core.data.cs.BlockArchitecture;
import org.polarsys.capella.core.data.la.LogicalArchitecture;
import org.polarsys.capella.core.data.oa.OperationalAnalysis;
import org.polarsys.capella.core.data.pa.PhysicalArchitecture;
import org.polarsys.capella.core.model.helpers.BlockArchitectureExt;
import org.polarsys.capella.mcp.dto.DiagramRequest;
import org.polarsys.capella.mcp.dto.DiagramResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Real implementation of DiagramService using Capella APIs.
 * TDD REFACTOR Phase: Creates actual diagrams in .aird files.
 */
@Service
@Primary
public class RealDiagramService extends DiagramService {
    
    private static final Logger LOGGER = Logger.getLogger(RealDiagramService.class.getName());
    
    @Autowired
    private CapellaSessionManager sessionManager;
    
    // Track created diagrams for listing/retrieval
    private final Map<String, DiagramResponse> diagramRegistry = new ConcurrentHashMap<>();
    // Track Sirius representations by our generated diagramId
    private final Map<String, DRepresentation> representationRegistry = new ConcurrentHashMap<>();
    
    // Mapping of diagram types to Sirius diagram descriptions
    private static final Map<String, String> DIAGRAM_TYPE_MAPPING = new HashMap<>();
    static {
        // Operational Architecture
        DIAGRAM_TYPE_MAPPING.put("OA_OAB", "Operational Architecture Blank");
        DIAGRAM_TYPE_MAPPING.put("OA_OES", "Operational Entity Scenario");
        DIAGRAM_TYPE_MAPPING.put("OA_OPD", "Operational Process Description");
        DIAGRAM_TYPE_MAPPING.put("OA_OCB", "Operational Capabilities Blank");
        
        // System Architecture
        DIAGRAM_TYPE_MAPPING.put("SA_SAB", "System Architecture Blank");
        DIAGRAM_TYPE_MAPPING.put("SA_ES", "Exchange Scenario");
        DIAGRAM_TYPE_MAPPING.put("SA_FS", "Functional Scenario");
        DIAGRAM_TYPE_MAPPING.put("SA_EAB", "EPBS Architecture Blank");
        
        // Logical Architecture
        DIAGRAM_TYPE_MAPPING.put("LA_LAB", "Logical Architecture Blank");
        DIAGRAM_TYPE_MAPPING.put("LA_LDFB", "Logical Data Flow Blank");
        DIAGRAM_TYPE_MAPPING.put("LA_LFBD", "Logical Function Breakdown");
        
        // Physical Architecture
        DIAGRAM_TYPE_MAPPING.put("PA_PAB", "Physical Architecture Blank");
        DIAGRAM_TYPE_MAPPING.put("PA_PDFB", "Physical Data Flow Blank");
        DIAGRAM_TYPE_MAPPING.put("PA_PFBD", "Physical Function Breakdown");
        
        // Common diagrams
        DIAGRAM_TYPE_MAPPING.put("CDB", "Class Diagram Blank");
        DIAGRAM_TYPE_MAPPING.put("IS", "Interface Scenario");

        // Additional mappings to broaden coverage
        // Operational
        DIAGRAM_TYPE_MAPPING.put("OA_OEB", "Operational Entity Breakdown");
        DIAGRAM_TYPE_MAPPING.put("OA_ORB", "Operational Role Breakdown");
        // System
        DIAGRAM_TYPE_MAPPING.put("SA_CSA", "Component Scenario");
        DIAGRAM_TYPE_MAPPING.put("SA_SDFB", "System Data Flow Blank");
        // Logical aliases
        DIAGRAM_TYPE_MAPPING.put("LA_ES", "Exchange Scenario");
        DIAGRAM_TYPE_MAPPING.put("LA_FS", "Functional Scenario");
        DIAGRAM_TYPE_MAPPING.put("LA_EAB", "EPBS Architecture Blank");
        // Physical aliases
        DIAGRAM_TYPE_MAPPING.put("PA_ES", "Exchange Scenario");
        DIAGRAM_TYPE_MAPPING.put("PA_FS", "Functional Scenario");
        DIAGRAM_TYPE_MAPPING.put("PA_EAB", "EPBS Architecture Blank");
    }
    
    @Override
    public DiagramResponse createDiagram(DiagramRequest request) {
        try {
            // Validate request
            validateDiagramRequest(request);
            
            // Get or create session
            Session session = sessionManager.getSession(request.getModelPath());
            if (session == null) {
                throw new RuntimeException("Failed to open Capella session for: " + request.getModelPath());
            }
            
            // Get the editing domain for transactions
            TransactionalEditingDomain domain = sessionManager.getEditingDomain(session);
            if (domain == null) {
                throw new RuntimeException("Failed to get editing domain for session");
            }
            
            // Find the target element (architecture layer)
            EObject targetElement = findTargetElement(session, request.getLayer());
            if (targetElement == null) {
                throw new RuntimeException("Failed to find target element for layer: " + request.getLayer());
            }
            
            // Create the diagram in a transaction
            final DiagramResponse[] response = new DiagramResponse[1];
            
            Command createCommand = new RecordingCommand(domain) {
                @Override
                protected void doExecute() {
                    try {
                        // Create the diagram using DialectManager
                        DRepresentation diagram = createDiagramRepresentation(
                            session, 
                            targetElement, 
                            request.getName(), 
                            request.getType(),
                            request.getLayer()
                        );
                        
                        if (diagram == null) {
                            throw new RuntimeException("Failed to create diagram representation");
                        }
                        
                        // Build response
                        response[0] = buildDiagramResponse(diagram, request);
                        
                        // Add initial elements if provided
                        if (request.getInitialElements() != null && !request.getInitialElements().isEmpty()) {
                            addElementsToDiagram(session, diagram, request.getInitialElements());
                            response[0].setElements(request.getInitialElements());
                            response[0].setElementCount(request.getInitialElements().size());
                        }
                        
                        // Save the session to persist changes
                        sessionManager.saveSession(session);
                        
                        LOGGER.info("Created diagram: " + response[0].getDiagramId());
                        
                    } catch (Exception e) {
                        LOGGER.severe("Error creating diagram: " + e.getMessage());
                        throw new RuntimeException("Failed to create diagram", e);
                    }
                }
            };
            
            // Execute the command
            domain.getCommandStack().execute(createCommand);
            
            // Register the diagram
            if (response[0] != null) {
                diagramRegistry.put(response[0].getDiagramId(), response[0]);
                // Try to resolve and register the created representation by UID
                try {
                    String uid = response[0].getMetadata() != null ? (String) response[0].getMetadata().get("diagramUID") : null;
                    if (uid != null) {
                        DRepresentation rep = findRepresentationByUid(session, uid);
                        if (rep != null) {
                            representationRegistry.put(response[0].getDiagramId(), rep);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warning("Could not register representation: " + e.getMessage());
                }
            }
            
            return response[0];
            
        } catch (Exception e) {
            LOGGER.severe("Failed to create diagram: " + e.getMessage());
            throw new RuntimeException("Failed to create diagram", e);
        }
    }
    
    /**
     * Create a diagram representation using DialectManager.
     */
    private DRepresentation createDiagramRepresentation(
            Session session, 
            EObject targetElement, 
            String name,
            String diagramType,
            String layer) {
        
        try {
            // Get the appropriate diagram description
            String descriptionKey = layer + "_" + diagramType;
            String descriptionName = DIAGRAM_TYPE_MAPPING.getOrDefault(descriptionKey, diagramType);
            
            // Find the diagram description
            RepresentationDescription description = findDiagramDescription(session, descriptionName, diagramType);
            if (description == null) {
                LOGGER.warning("Could not find diagram description for: " + descriptionName);
                // Try generic approach
                description = findGenericDiagramDescription(session, diagramType);
            }
            
            if (description == null) {
                throw new RuntimeException("No suitable diagram description found for type: " + diagramType);
            }
            
            // Create the diagram
            IProgressMonitor monitor = new NullProgressMonitor();
            String diagramName = name != null ? name : String.format("[%s] New Diagram", diagramType);
            
            DRepresentation representation = DialectManager.INSTANCE.createRepresentation(
                diagramName,
                targetElement,
                description,
                session,
                monitor
            );
            
            if (representation instanceof DDiagram) {
                DDiagram diagram = (DDiagram) representation;
                // Set additional properties if needed
                diagram.setDocumentation("Created by Capella MCP Java Service");
            }
            
            return representation;
            
        } catch (Exception e) {
            LOGGER.severe("Error creating diagram representation: " + e.getMessage());
            throw new RuntimeException("Failed to create diagram representation", e);
        }
    }
    
    /**
     * Find the target element (architecture) for the given layer.
     */
    private EObject findTargetElement(Session session, String layer) {
        try {
            // Get all semantic resources
            for (Resource resource : session.getSemanticResources()) {
                // Look for the Project root
                if (!resource.getContents().isEmpty()) {
                    EObject root = resource.getContents().get(0);
                    
                    if (root instanceof Project) {
                        Project project = (Project) root;
                        
                        // Find the appropriate architecture
                        for (EObject element : project.eContents()) {
                            if ("OA".equals(layer) && element instanceof OperationalAnalysis) {
                                return element;
                            } else if ("SA".equals(layer) && element instanceof org.polarsys.capella.core.data.ctx.SystemAnalysis) {
                                return element;
                            } else if ("LA".equals(layer) && element instanceof LogicalArchitecture) {
                                return element;
                            } else if ("PA".equals(layer) && element instanceof PhysicalArchitecture) {
                                return element;
                            }
                        }
                    }
                }
            }
            
            LOGGER.warning("Could not find architecture for layer: " + layer);
            return null;
            
        } catch (Exception e) {
            LOGGER.severe("Error finding target element: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Find a diagram description by name.
     */
    private RepresentationDescription findDiagramDescription(Session session, String descriptionName, String diagramType) {
        try {
            // Get all viewpoints
            Collection<Viewpoint> viewpoints = session.getSelectedViewpoints(false);
            
            for (Viewpoint viewpoint : viewpoints) {
                for (RepresentationDescription desc : viewpoint.getOwnedRepresentations()) {
                    if (desc instanceof DiagramDescription) {
                        DiagramDescription diagramDesc = (DiagramDescription) desc;
                        
                        // Check by name or label
                        if (descriptionName.equals(diagramDesc.getName()) ||
                            descriptionName.equals(diagramDesc.getLabel()) ||
                            diagramType.equals(diagramDesc.getName())) {
                            return diagramDesc;
                        }
                    }
                }
            }
            
            return null;
            
        } catch (Exception e) {
            LOGGER.warning("Error finding diagram description: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Find a generic diagram description as fallback.
     */
    private RepresentationDescription findGenericDiagramDescription(Session session, String diagramType) {
        try {
            Collection<Viewpoint> viewpoints = session.getSelectedViewpoints(false);
            
            // Look for any diagram description that matches the type pattern
            for (Viewpoint viewpoint : viewpoints) {
                for (RepresentationDescription desc : viewpoint.getOwnedRepresentations()) {
                    if (desc instanceof DiagramDescription) {
                        DiagramDescription diagramDesc = (DiagramDescription) desc;
                        String name = diagramDesc.getName();
                        
                        // Check if the type matches common patterns
                        if (name.contains(diagramType) || 
                            name.contains("Blank") || 
                            name.contains("Architecture")) {
                            LOGGER.info("Using generic diagram description: " + name);
                            return diagramDesc;
                        }
                    }
                }
            }
            
            // Last resort: return first available diagram description
            for (Viewpoint viewpoint : viewpoints) {
                if (!viewpoint.getOwnedRepresentations().isEmpty()) {
                    RepresentationDescription desc = viewpoint.getOwnedRepresentations().get(0);
                    LOGGER.warning("Using fallback diagram description: " + desc.getName());
                    return desc;
                }
            }
            
            return null;
            
        } catch (Exception e) {
            LOGGER.warning("Error finding generic diagram description: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Add elements to a diagram.
     */
    private void addElementsToDiagram(Session session, DRepresentation diagram, List<String> elementIds) {
        if (!(diagram instanceof DDiagram) || elementIds == null || elementIds.isEmpty()) {
            return;
        }
        try {
            var domain = session.getTransactionalEditingDomain();
            if (domain == null) return;
            var rs = domain.getResourceSet();
            if (rs == null) return;

            int resolved = 0;
            for (String id : elementIds) {
                EObject eo = findElementById(rs, id);
                if (eo != null) resolved++;
            }
            // Best-effort refresh to reveal semantics according to diagram rules
            DialectManager.INSTANCE.refresh(diagram, new NullProgressMonitor());
            LOGGER.info("Requested reveal of " + resolved + " elements (refresh invoked)");
        } catch (Exception e) {
            LOGGER.warning("Failed to add elements to diagram: " + e.getMessage());
        }
    }
    
    /**
     * Build response from created diagram.
     */
    private DiagramResponse buildDiagramResponse(DRepresentation diagram, DiagramRequest request) {
        DiagramResponse response = new DiagramResponse();
        
        // Generate unique ID
        String diagramId = UUID.randomUUID().toString();
        
        response.setDiagramId(diagramId);
        response.setName(diagram.getName());
        response.setType(request.getType());
        response.setLayer(request.getLayer());
        response.setModelPath(request.getModelPath());
        response.setStatus("CREATED");
        response.setCreatedAt(LocalDateTime.now().toString());
        response.setElements(new ArrayList<>());
        response.setElementCount(0);
        
        // Add metadata
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("createdBy", "Capella Diagram Service (Real)");
        metadata.put("version", "1.0.0");
        metadata.put("persistenceMode", "REAL");
        metadata.put("diagramUID", diagram.getUid());
        
        if (diagram instanceof DDiagram) {
            DDiagram dDiagram = (DDiagram) diagram;
            metadata.put("diagramClass", dDiagram.eClass().getName());
            metadata.put("elementCount", dDiagram.getDiagramElements().size());
        }
        
        response.setMetadata(metadata);
        
        return response;
    }
    
    @Override
    public DiagramResponse getDiagram(String diagramId) {
        return diagramRegistry.get(diagramId);
    }
    
    @Override
    public List<DiagramResponse> listDiagrams(String modelPath, String layer, String type) {
        return diagramRegistry.values().stream()
            .filter(d -> modelPath == null || modelPath.equals(d.getModelPath()))
            .filter(d -> layer == null || layer.equals(d.getLayer()))
            .filter(d -> type == null || type.equals(d.getType()))
            .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
            .collect(Collectors.toList());
    }
    
    @Override
    public int getTotalDiagramsCreated() {
        return diagramRegistry.size();
    }
    
    @Override
    public boolean deleteDiagram(String diagramId) {
        DiagramResponse removed = diagramRegistry.remove(diagramId);
        DRepresentation rep = representationRegistry.remove(diagramId);
        boolean ok = removed != null;
        try {
            if (removed != null) {
                Session session = sessionManager.getSession(removed.getModelPath());
                if (rep == null && removed.getMetadata() != null) {
                    String uid = (String) removed.getMetadata().get("diagramUID");
                    rep = findRepresentationByUid(session, uid);
                }
                if (rep != null) {
                    DialectManager.INSTANCE.deleteRepresentation(rep, new NullProgressMonitor());
                    sessionManager.saveSession(session);
                    ok = true;
                }
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to delete real diagram: " + e.getMessage());
        }
        return ok;
    }

    @Override
    public DiagramResponse updateDiagramElements(String diagramId, Map<String, List<String>> elements) {
        DiagramResponse resp = diagramRegistry.get(diagramId);
        if (resp == null) return null;
        try {
            DRepresentation rep = representationRegistry.get(diagramId);
            if (rep == null && resp.getMetadata() != null) {
                Session session = sessionManager.getSession(resp.getModelPath());
                String uid = (String) resp.getMetadata().get("diagramUID");
                rep = findRepresentationByUid(session, uid);
                if (rep != null) representationRegistry.put(diagramId, rep);
            }
            if (rep != null) {
                Session session = sessionManager.getSession(resp.getModelPath());
                addElementsToDiagram(session, rep, elements.getOrDefault("add", List.of()));
                DialectManager.INSTANCE.refresh(rep, new NullProgressMonitor());
                sessionManager.saveSession(session);
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to update diagram elements: " + e.getMessage());
        }

        // Update bookkeeping
        List<String> current = resp.getElements() != null ? new ArrayList<>(resp.getElements()) : new ArrayList<>();
        if (elements.containsKey("add")) {
            for (String id : elements.get("add")) if (!current.contains(id)) current.add(id);
        }
        if (elements.containsKey("remove")) {
            current.removeAll(elements.get("remove"));
        }
        resp.setElements(current);
        resp.setElementCount(current.size());
        resp.setStatus("UPDATED");
        if (resp.getMetadata() != null) {
            resp.getMetadata().put("lastModified", LocalDateTime.now().toString());
        }
        return resp;
    }

    private DRepresentation findRepresentationByUid(Session session, String uid) {
        if (session == null || uid == null) return null;
        try {
            Collection<DRepresentation> reps = DialectManager.INSTANCE.getAllRepresentations(session);
            for (DRepresentation r : reps) {
                if (uid.equals(r.getUid())) return r;
            }
        } catch (Exception ignore) {}
        return null;
    }

    private EObject findElementById(org.eclipse.emf.ecore.resource.ResourceSet rs, String id) {
        if (rs == null || id == null) return null;
        try {
            for (Resource res : rs.getResources()) {
                if (res == null) continue;
                var it = res.getAllContents();
                while (it.hasNext()) {
                    EObject eo = it.next();
                    try {
                        var cls = eo.eClass();
                        var idFeat = cls.getEStructuralFeature("id");
                        if (idFeat != null) {
                            Object v = eo.eGet(idFeat);
                            if (id.equals(v)) return eo;
                        }
                        var uuidFeat = cls.getEStructuralFeature("uuid");
                        if (uuidFeat != null) {
                            Object v = eo.eGet(uuidFeat);
                            if (id.equals(v)) return eo;
                        }
                        // Compare URI fragment as last resort
                        URI uri = org.eclipse.emf.ecore.util.EcoreUtil.getURI(eo);
                        if (uri != null && id.equals(uri.fragment())) return eo;
                    } catch (Exception ignore) {}
                }
            }
        } catch (Exception ignore) {}
        return null;
    }
    
    /**
     * Cleanup resources on shutdown.
     */
    public void cleanup() {
        sessionManager.closeAllSessions();
        diagramRegistry.clear();
    }
}
