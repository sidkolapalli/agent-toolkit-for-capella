package org.polarsys.capella.ai.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.viewpoint.DRepresentation;
import org.polarsys.capella.ai.model.ArchitectureLocator;
import org.polarsys.capella.ai.model.ElementFactory;
import org.polarsys.capella.ai.model.Finders;
import org.polarsys.capella.ai.model.ModelSummary;
import org.polarsys.capella.ai.session.SessionBridge;
import org.polarsys.capella.ai.tools.DiagramCommands;
import org.polarsys.capella.ai.tools.DiagramMappings;

/** Routes chat requests to LLM and executes tool plans when present. */
public final class ToolRouter {
    public static class Result { public String reply; public int actionsApplied; public String error; }

    private ToolRouter() {}

    public static Result handleChat(String userMessage, boolean enableActions) {
        Result r = new Result();
        try {
            String prompt = buildPrompt(userMessage);
            String reply = LLMService.get().send(prompt);
            r.reply = reply;
            String json = extractPlan(reply);
            if (json != null && enableActions) {
                int n = executePlan(json);
                r.actionsApplied = n;
            }
        } catch (Exception e) {
            r.error = e.getMessage();
        }
        return r;
    }

    private static String buildPrompt(String user) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an AI agent inside Capella.\n");
        sb.append("When the user asks for changes, output a line starting with 'TOOL_PLAN:' followed by a compact JSON with actions.\n");
        sb.append("Schema: {\"actions\":[{\"type\": one of \"create_component\",\"create_function\",\"create_component_exchange\",\"create_functional_exchange\",\"create_lab\", \"rename\", \"delete\",\"open_diagram\" , ...], ...}] }\n");
        sb.append("Action fields:\n");
        sb.append("- create_component: {layer, name}\n");
        sb.append("- create_function: {layer, name}\n");
        sb.append("- create_component_exchange: {source_name, target_name, layer, name}\n");
        sb.append("- create_functional_exchange: {source_function, target_function, layer, name}\n");
        sb.append("- create_lab: {layer}\n");
        sb.append("- rename: {class, name, new_name}\n");
        sb.append("- delete: {class, name}\n");
        sb.append("- open_diagram: {layer, type}\n");
        sb.append("Provide a short natural language answer too.\n\n");
        sb.append("Model summary:\n").append(ModelSummary.summarize()).append("\n\n");
        sb.append("User: ").append(user);
        return sb.toString();
    }

    private static String extractPlan(String reply) {
        if (reply == null) return null;
        int i = reply.indexOf("TOOL_PLAN:");
        if (i < 0) return null;
        int b = reply.indexOf('{', i);
        if (b < 0) return null;
        int depth = 0; int j=b; char[] ch = reply.toCharArray();
        for (; j<ch.length; j++) {
            if (ch[j] == '{') depth++;
            else if (ch[j] == '}') { depth--; if (depth == 0) { j++; break; } }
        }
        if (depth != 0) return null;
        return reply.substring(b, j);
    }

    @SuppressWarnings("unchecked")
    private static int executePlan(String json) {
        // Very light JSON parsing using built-in minimal approach
        // Expecting {"actions":[{...},{...}]}
        List<Map<String,Object>> actions = SimpleJson.parseActions(json);
        int applied = 0;
        for (Map<String,Object> a : actions) {
            String type = str(a.get("type"));
            try {
                if ("create_component".equals(type)) {
                    String name = str(a.get("name")); String layer = str(a.get("layer"), "LA");
                    EObject root = ArchitectureLocator.findTargetElement(SessionBridge.getActiveSession(), layer);
                    if (root != null && ElementFactory.createUnder(root, "LogicalComponent", name) != null) applied++;
                } else if ("create_function".equals(type)) {
                    String name = str(a.get("name")); String layer = str(a.get("layer"), "LA");
                    EObject root = ArchitectureLocator.findTargetElement(SessionBridge.getActiveSession(), layer);
                    if (root != null && ElementFactory.createUnder(root, "LogicalFunction", name) != null) applied++;
                } else if ("create_component_exchange".equals(type)) {
                    String sname = str(a.get("source_name")); String tname = str(a.get("target_name")); String layer = str(a.get("layer"), "LA");
                    EObject root = ArchitectureLocator.findTargetElement(SessionBridge.getActiveSession(), layer);
                    EObject src = Finders.findByName("LogicalComponent", sname);
                    EObject tgt = Finders.findByName("LogicalComponent", tname);
                    if (root != null && src != null && tgt != null) {
                        EObject exch = ElementFactory.createUnder(root, "ComponentExchange", str(a.get("name"), "Exchange"));
                        setEndpoint(exch, "source", src); setEndpoint(exch, "target", tgt); applied++;
                    }
                } else if ("create_functional_exchange".equals(type)) {
                    String sname = str(a.get("source_function")); String tname = str(a.get("target_function")); String layer = str(a.get("layer"), "LA");
                    EObject root = ArchitectureLocator.findTargetElement(SessionBridge.getActiveSession(), layer);
                    EObject src = Finders.findByName("LogicalFunction", sname);
                    EObject tgt = Finders.findByName("LogicalFunction", tname);
                    if (root != null && src != null && tgt != null) {
                        EObject fex = ElementFactory.createUnder(root, "FunctionalExchange", str(a.get("name"), "FunctionalExchange"));
                        setEndpoint(fex, "source", src); setEndpoint(fex, "target", tgt); applied++;
                    }
                } else if ("create_lab".equals(type)) {
                    String layer = str(a.get("layer"), "LA");
                    EObject root = ArchitectureLocator.findTargetElement(SessionBridge.getActiveSession(), layer);
                    if (root != null) {
                        String desc = DiagramMappings.descriptionFor(layer, "LAB");
                        DRepresentation rep = DiagramCommands.createAndOpenDiagram(root, desc, "["+layer+"] LAB");
                        if (rep != null) applied++;
                    }
                } else if ("open_diagram".equals(type)) {
                    String layer = str(a.get("layer"), "LA"); String t = str(a.get("type"), "LAB");
                    EObject root = ArchitectureLocator.findTargetElement(SessionBridge.getActiveSession(), layer);
                    if (root != null) {
                        String desc = DiagramMappings.descriptionFor(layer, t);
                        DRepresentation rep = DiagramCommands.createAndOpenDiagram(root, desc, "["+layer+"] "+t);
                        if (rep != null) applied++;
                    }
                } else if ("rename".equals(type)) {
                    String cls = str(a.get("class")); String name = str(a.get("name")); String newName = str(a.get("new_name"));
                    EObject eo = Finders.findByName(cls, name);
                    if (eo != null) {
                        var feat = eo.eClass().getEStructuralFeature("name");
                        if (feat != null && feat.isChangeable()) { eo.eSet(feat, newName); applied++; }
                    }
                } else if ("delete".equals(type)) {
                    String cls = str(a.get("class")); String name = str(a.get("name"));
                    EObject eo = Finders.findByName(cls, name);
                    if (eo != null) { // reuse delete by container logic via ElementFactory path not present; simple remove
                        var domain = SessionBridge.getActiveSession().getTransactionalEditingDomain();
                        if (domain != null) {
                            final EObject target = eo;
                            domain.getCommandStack().execute(new org.eclipse.emf.transaction.RecordingCommand(domain) {
                                protected void doExecute() {
                                    EObject c = target.eContainer();
                                    if (c != null) { var f = target.eContainingFeature(); if (f.isMany()) ((java.util.List) c.eGet(f)).remove(target); else c.eSet(f, null); }
                                    else if (target.eResource() != null) target.eResource().getContents().remove(target);
                                }
                            });
                            applied++;
                        }
                    }
                }
            } catch (Exception ignore) {}
        }
        try { SessionBridge.getActiveSession().save(new org.eclipse.core.runtime.NullProgressMonitor()); } catch (Exception ignore) {}
        return applied;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static String str(Object o, String def) { String s = str(o); return s.isBlank()? def : s; }

    private static void setEndpoint(EObject exchange, String role, EObject end) {
        if (exchange == null || end == null) return;
        try {
            for (var f : exchange.eClass().getEAllStructuralFeatures()) {
                if (f instanceof org.eclipse.emf.ecore.EReference && !((org.eclipse.emf.ecore.EReference) f).isContainment()) {
                    String name = f.getName().toLowerCase();
                    if ((role.equals("source") && (name.contains("src") || name.contains("source"))) ||
                        (role.equals("target") && (name.contains("tgt") || name.contains("target")))) {
                        var et = f.getEType();
                        if (et instanceof org.eclipse.emf.ecore.EClass) {
                            var ect = (org.eclipse.emf.ecore.EClass) et;
                            if (ect.isSuperTypeOf(end.eClass()) || ect == end.eClass()) { exchange.eSet(f, end); return; }
                        }
                    }
                }
            }
        } catch (Exception ignore) {}
    }
}

