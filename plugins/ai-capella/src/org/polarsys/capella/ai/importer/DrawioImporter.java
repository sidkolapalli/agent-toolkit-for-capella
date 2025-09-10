package org.polarsys.capella.ai.importer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.viewpoint.DRepresentation;
import org.polarsys.capella.ai.model.ArchitectureLocator;
import org.polarsys.capella.ai.model.ElementFactory;
import org.polarsys.capella.ai.session.SessionBridge;
import org.polarsys.capella.ai.tools.DiagramCommands;

/** Imports a draw.io file into LA as LogicalComponents and reveals them on a LAB diagram. */
public final class DrawioImporter {
    private static final Logger LOG = Logger.getLogger(DrawioImporter.class.getName());

    public static class Result {
        public int nodesCreated;
        public int edgesProcessed;
        public List<String> createdUuids = new ArrayList<>();
        public String diagramName;
    }

    private DrawioImporter() {}

    public static Result importLogical(File drawioFile, boolean useLLM) throws Exception {
        Session session = SessionBridge.getActiveSession();
        if (session == null) throw new IllegalStateException("No active session");

        DrawioParser.Graph g = DrawioParser.parse(drawioFile);

        // For v1, we use heuristics to map nodes->LogicalComponent. LLM hooks can be added later.
        var la = ArchitectureLocator.findTargetElement(session, "LA");
        if (la == null) throw new IllegalStateException("LA architecture not found");

        Result r = new Result();
        java.util.Map<String, org.eclipse.emf.ecore.EObject> nodeMap = new java.util.HashMap<>();
        java.util.Map<String, org.eclipse.emf.ecore.EObject> funcMap = new java.util.HashMap<>();
        for (var n : g.nodes) {
            var created = ElementFactory.createUnder(la, "LogicalComponent", n.label.isBlank()? "Component" : n.label);
            if (created != null) {
                String uuid = tryGetUuid(created);
                if (uuid != null) r.createdUuids.add(uuid);
                r.nodesCreated++;
                nodeMap.put(n.id, created);
            }
        }

        // Edge mapping: optionally consult LLM to classify edge types and direction
        java.util.Map<String, String> llmTypes = java.util.Collections.emptyMap();
        if (useLLM) {
            try {
                llmTypes = LLMEdgeMapper.classifyEdges(g);
            } catch (Exception e) {
                LOG.warning("LLM edge mapping failed, falling back to heuristics: " + e.getMessage());
            }
        }

        int mapped = 0;
        java.util.List<org.eclipse.emf.ecore.EObject> createdFunctions = new java.util.ArrayList<>();
        java.util.List<org.eclipse.emf.ecore.EObject> createdCompExchanges = new java.util.ArrayList<>();
        java.util.List<org.eclipse.emf.ecore.EObject> createdFuncExchanges = new java.util.ArrayList<>();
        for (var e : g.edges) {
            var src = nodeMap.get(e.source);
            var tgt = nodeMap.get(e.target);
            if (src == null || tgt == null) continue;
            String etype = llmTypes.getOrDefault(e.id, heuristicType(e.label));
            if ("functional".equals(etype)) {
                // Ensure functions for endpoints
                org.eclipse.emf.ecore.EObject fsrc = funcMap.get(e.source);
                if (fsrc == null) {
                    fsrc = ElementFactory.createUnder(la, "LogicalFunction", labelOrDefault(nodeLabel(g, e.source), "Function"));
                    if (fsrc != null) { funcMap.put(e.source, fsrc); createdFunctions.add(fsrc); }
                }
                org.eclipse.emf.ecore.EObject ftgt = funcMap.get(e.target);
                if (ftgt == null) {
                    ftgt = ElementFactory.createUnder(la, "LogicalFunction", labelOrDefault(nodeLabel(g, e.target), "Function"));
                    if (ftgt != null) { funcMap.put(e.target, ftgt); createdFunctions.add(ftgt); }
                }
                // Create FunctionalExchange between functions
                var fex = ElementFactory.createUnder(la, "FunctionalExchange", e.label == null || e.label.isBlank()? "FunctionalExchange" : e.label);
                if (fex != null) {
                    setEndpoint(fex, "source", fsrc);
                    setEndpoint(fex, "target", ftgt);
                    createdFuncExchanges.add(fex);
                    mapped++;
                }
                // Optionally create/ensure a ComponentExchange between components and link
                var cex = ElementFactory.createUnder(la, "ComponentExchange", e.label == null || e.label.isBlank()? "Exchange" : e.label);
                if (cex != null) {
                    setEndpoint(cex, "source", src);
                    setEndpoint(cex, "target", tgt);
                    createdCompExchanges.add(cex);
                    // Try to allocate/link fex to cex via cross-reference
                    linkIfCompatible(fex, cex, new String[]{"allocated", "allocation", "component", "realiz"});
                    linkIfCompatible(cex, fex, new String[]{"functional", "realiz"});
                }
            } else {
                // Component-level exchange
                var exch = ElementFactory.createUnder(la, "ComponentExchange", e.label == null || e.label.isBlank()? "Exchange" : e.label);
                if (exch != null) {
                    setEndpoint(exch, "source", src);
                    setEndpoint(exch, "target", tgt);
                    createdCompExchanges.add(exch);
                    mapped++;
                }
            }
        }
        r.edgesProcessed = mapped;

        // Create & open LAB, reveal all created
        String desc = org.polarsys.capella.ai.tools.DiagramMappings.descriptionFor("LA", "LAB");
        DRepresentation rep = DiagramCommands.createAndOpenDiagram(la, desc, "[LAB] Imported from draw.io");
        if (rep != null && !r.createdUuids.isEmpty()) {
            DiagramCommands.addElementsByUuid(session, rep, r.createdUuids);
            DiagramCommands.arrange(rep);
            r.diagramName = rep.getName();
        }

        // Create a functional diagram for function-level visualization if functions were created
        if (!createdFunctions.isEmpty()) {
            String fdesc = org.polarsys.capella.ai.tools.DiagramMappings.descriptionFor("LA", "LDFB");
            DRepresentation frep = DiagramCommands.createAndOpenDiagram(la, fdesc, "[LDFB] Imported from draw.io");
            if (frep != null) {
                java.util.List<String> fuuids = new java.util.ArrayList<>();
                for (var f : createdFunctions) { String u = tryGetUuid(f); if (u != null) fuuids.add(u); }
                if (!fuuids.isEmpty()) {
                    DiagramCommands.addElementsByUuid(session, frep, fuuids);
                    DiagramCommands.arrange(frep);
                }
            }
        }

        return r;
    }

    private static String tryGetUuid(Object eo) {
        try {
            if (eo instanceof org.eclipse.emf.ecore.EObject) {
                org.eclipse.emf.ecore.EObject o = (org.eclipse.emf.ecore.EObject) eo;
                var f = o.eClass().getEStructuralFeature("uuid");
                if (f != null) {
                    Object v = o.eGet(f);
                    return v != null ? String.valueOf(v) : null;
                }
            }
        } catch (Exception ignore) {}
        return null;
    }

    private static String heuristicType(String label) {
        if (label == null) return "component";
        String s = label.toLowerCase();
        if (s.contains("data") || s.contains("signal") || s.contains("message") || s.contains("flow") || s.contains("fx") || s.contains("func"))
            return "functional";
        return "component";
    }

    private static void setEndpoint(org.eclipse.emf.ecore.EObject exchange, String role, org.eclipse.emf.ecore.EObject end) {
        try {
            for (org.eclipse.emf.ecore.EStructuralFeature f : exchange.eClass().getEAllStructuralFeatures()) {
                if (f instanceof org.eclipse.emf.ecore.EReference && !((org.eclipse.emf.ecore.EReference) f).isContainment()) {
                    String name = f.getName().toLowerCase();
                    if ((role.equals("source") && (name.contains("src") || name.contains("source"))) ||
                        (role.equals("target") && (name.contains("tgt") || name.contains("target")))) {
                        // Type check: allow if eType is supertype of end.eClass()
                        org.eclipse.emf.ecore.EClassifier et = f.getEType();
                        if (et instanceof org.eclipse.emf.ecore.EClass) {
                            org.eclipse.emf.ecore.EClass ect = (org.eclipse.emf.ecore.EClass) et;
                            if (ect.isSuperTypeOf(end.eClass()) || ect == end.eClass()) {
                                exchange.eSet(f, end);
                                return;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignore) {}
    }

    private static void linkIfCompatible(org.eclipse.emf.ecore.EObject a, org.eclipse.emf.ecore.EObject b, String[] nameHints) {
        if (a == null || b == null) return;
        try {
            for (org.eclipse.emf.ecore.EStructuralFeature f : a.eClass().getEAllStructuralFeatures()) {
                if (f instanceof org.eclipse.emf.ecore.EReference && !((org.eclipse.emf.ecore.EReference) f).isContainment()) {
                    String lname = f.getName().toLowerCase();
                    boolean hintMatch = false;
                    for (String h : nameHints) { if (lname.contains(h)) { hintMatch = true; break; } }
                    if (!hintMatch) continue;
                    org.eclipse.emf.ecore.EClassifier et = f.getEType();
                    if (et instanceof org.eclipse.emf.ecore.EClass) {
                        org.eclipse.emf.ecore.EClass ect = (org.eclipse.emf.ecore.EClass) et;
                        if (ect.isSuperTypeOf(b.eClass()) || ect == b.eClass()) {
                            if (f.isMany()) {
                                @SuppressWarnings("unchecked")
                                java.util.List<org.eclipse.emf.ecore.EObject> lst = (java.util.List<org.eclipse.emf.ecore.EObject>) a.eGet(f);
                                if (!lst.contains(b)) lst.add(b);
                            } else {
                                a.eSet(f, b);
                            }
                            return;
                        }
                    }
                }
            }
        } catch (Exception ignore) {}
    }

    private static String nodeLabel(DrawioParser.Graph g, String id) {
        for (var n : g.nodes) if (n.id.equals(id)) return n.label;
        return "";
    }

    private static String labelOrDefault(String s, String def) { return (s == null || s.isBlank()) ? def : s; }
}
