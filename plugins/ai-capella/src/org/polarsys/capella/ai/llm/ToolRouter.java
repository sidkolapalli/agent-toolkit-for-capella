package org.polarsys.capella.ai.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.viewpoint.DRepresentation;
import org.polarsys.capella.ai.model.ArchitectureLocator;
import org.polarsys.capella.ai.model.ElementFactory;
import org.polarsys.capella.ai.model.Finders;
import org.polarsys.capella.ai.model.ModelOps;
import org.polarsys.capella.ai.model.ModelSummary;
import org.polarsys.capella.ai.session.SessionBridge;
import org.polarsys.capella.ai.tools.DiagramCommands;
import org.polarsys.capella.ai.tools.DiagramMappings;

/** Routes chat requests to LLM and executes tool plans when present. */
public final class ToolRouter {
    public static final class Result {
        public String reply;
        public int actionsApplied;
        public String error;
        public String planSummary;
    }

    private ToolRouter() {}

    public static Result handleChat(Session session, DRepresentation diagram, String userMessage, boolean enableActions) {
        Result result = new Result();
        try {
            if (session == null) {
                throw new IllegalStateException("No active Capella session");
            }
            String prompt = buildPrompt(session, userMessage);
            String reply = LLMService.get().send(prompt);
            result.reply = reply;

            String json = extractPlan(reply);
            if (json != null && !json.isBlank()) {
                List<Action> actions = parseActions(json);
                result.planSummary = describePlan(actions, enableActions);
                if (enableActions && !actions.isEmpty()) {
                    int applied = executePlan(session, diagram, actions);
                    result.actionsApplied = applied;
                }
            }
        } catch (PlanValidationException ex) {
            result.error = ex.getMessage();
        } catch (Exception ex) {
            result.error = ex.getMessage();
        }
        return result;
    }

    private static String buildPrompt(Session session, String user) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an AI agent inside Capella.\n");
        sb.append("When the user asks for changes, output a line starting with 'TOOL_PLAN:' followed by compact JSON.\n");
        sb.append("Schema: {\"actions\":[{\"type\":one of \"create_component\",\"create_function\",\"create_component_exchange\",\"create_functional_exchange\",\"create_lab\",\"rename\",\"delete\",\"open_diagram\"},...]}\n");
        sb.append("Action fields:\n");
        sb.append("- create_component: {layer, name}\n");
        sb.append("- create_function: {layer, name}\n");
        sb.append("- create_component_exchange: {source_name, target_name, layer, name}\n");
        sb.append("- create_functional_exchange: {source_function, target_function, layer, name}\n");
        sb.append("- create_lab: {layer}\n");
        sb.append("- rename: {class, name, new_name}\n");
        sb.append("- delete: {class, name}\n");
        sb.append("- open_diagram: {layer, type}\n");
        sb.append("Provide a short natural-language answer too.\n\n");
        sb.append("Model summary:\n").append(ModelSummary.summarize(session)).append("\n\n");
        sb.append("User: ").append(user);
        return sb.toString();
    }

    private static String extractPlan(String reply) {
        if (reply == null) {
            return null;
        }
        int marker = reply.indexOf("TOOL_PLAN:");
        if (marker < 0) {
            return null;
        }
        int braceStart = reply.indexOf('{', marker);
        if (braceStart < 0) {
            return null;
        }
        int depth = 0;
        int i = braceStart;
        char[] chars = reply.toCharArray();
        for (; i < chars.length; i++) {
            if (chars[i] == '{') {
                depth++;
            } else if (chars[i] == '}') {
                depth--;
                if (depth == 0) {
                    return reply.substring(braceStart, i + 1);
                }
            }
        }
        return null;
    }

    private static List<Action> parseActions(String json) {
        Object parsed = SimpleJson.parse(json);
        if (!(parsed instanceof Map)) {
            throw new PlanValidationException("TOOL_PLAN must be a JSON object");
        }
        Object rawActions = ((Map<?, ?>) parsed).get("actions");
        if (!(rawActions instanceof List)) {
            throw new PlanValidationException("TOOL_PLAN requires an 'actions' array");
        }
        List<Action> actions = new ArrayList<>();
        List<?> list = (List<?>) rawActions;
        for (int index = 0; index < list.size(); index++) {
            Object entry = list.get(index);
            if (!(entry instanceof Map)) {
                throw new PlanValidationException("Action at index " + index + " must be an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) entry;
            actions.add(Action.fromMap(index, map));
        }
        return actions;
    }

    private static int executePlan(Session session, DRepresentation diagram, List<Action> actions) {
        TransactionalEditingDomain domain = session.getTransactionalEditingDomain();
        if (domain == null) {
            return 0;
        }
        int applied = 0;
        for (Action action : actions) {
            try {
                switch (action.type) {
                    case CREATE_COMPONENT:
                        if (createComponent(session, action.layer, action.name)) applied++;
                        break;
                    case CREATE_FUNCTION:
                        if (createFunction(session, action.layer, action.name)) applied++;
                        break;
                    case CREATE_COMPONENT_EXCHANGE:
                        if (createComponentExchange(session, action.layer, action.sourceName, action.targetName, action.name)) applied++;
                        break;
                    case CREATE_FUNCTIONAL_EXCHANGE:
                        if (createFunctionalExchange(session, action.layer, action.sourceName, action.targetName, action.name)) applied++;
                        break;
                    case CREATE_LAB:
                        if (createLabDiagram(session, action.layer)) applied++;
                        break;
                    case OPEN_DIAGRAM:
                        if (openDiagram(session, action.layer, action.diagramType, action.name)) applied++;
                        break;
                    case RENAME:
                        if (rename(session, action.elementClass, action.existingName, action.newName)) applied++;
                        break;
                    case DELETE:
                        if (delete(session, action.elementClass, action.existingName)) applied++;
                        break;
                    default:
                        break;
                }
            } catch (Exception ex) {
                // continue executing the plan but surface the failure later
            }
        }
        return applied;
    }

    private static String describePlan(List<Action> actions, boolean willExecute) {
        if (actions.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(willExecute ? "Executed plan actions:" : "Plan (not executed):");
        for (Action action : actions) {
            sb.append(System.lineSeparator()).append(" • ").append(action.describe());
        }
        return sb.toString();
    }

    private static boolean createComponent(Session session, String layer, String name) {
        EObject root = ArchitectureLocator.findTargetElement(session, layer);
        if (root == null || name.isBlank()) {
            return false;
        }
        return ElementFactory.createUnder(session, root, "LogicalComponent", name) != null;
    }

    private static boolean createFunction(Session session, String layer, String name) {
        EObject root = ArchitectureLocator.findTargetElement(session, layer);
        if (root == null || name.isBlank()) {
            return false;
        }
        return ElementFactory.createUnder(session, root, "LogicalFunction", name) != null;
    }

    private static boolean createComponentExchange(Session session, String layer, String sourceName, String targetName, String name) {
        EObject root = ArchitectureLocator.findTargetElement(session, layer);
        EObject src = Finders.findByName(session, "LogicalComponent", sourceName);
        EObject tgt = Finders.findByName(session, "LogicalComponent", targetName);
        if (root == null || src == null || tgt == null) {
            return false;
        }
        EObject exchange = ElementFactory.createUnder(session, root, "ComponentExchange", name);
        if (exchange == null) {
            return false;
        }
        setEndpoint(exchange, "source", src);
        setEndpoint(exchange, "target", tgt);
        return true;
    }

    private static boolean createFunctionalExchange(Session session, String layer, String sourceName, String targetName, String name) {
        EObject root = ArchitectureLocator.findTargetElement(session, layer);
        EObject src = Finders.findByName(session, "LogicalFunction", sourceName);
        EObject tgt = Finders.findByName(session, "LogicalFunction", targetName);
        if (root == null || src == null || tgt == null) {
            return false;
        }
        EObject exchange = ElementFactory.createUnder(session, root, "FunctionalExchange", name);
        if (exchange == null) {
            return false;
        }
        setEndpoint(exchange, "source", src);
        setEndpoint(exchange, "target", tgt);
        return true;
    }

    private static boolean createLabDiagram(Session session, String layer) {
        EObject root = ArchitectureLocator.findTargetElement(session, layer);
        if (root == null) {
            return false;
        }
        String description = DiagramMappings.descriptionFor(layer != null ? layer : "LA", "LAB");
        return DiagramCommands.createAndOpenDiagram(root, description, "AI LAB Diagram") != null;
    }

    private static boolean openDiagram(Session session, String layer, String type, String suggestedName) {
        EObject root = ArchitectureLocator.findTargetElement(session, layer);
        if (root == null) {
            return false;
        }
        String desc = DiagramMappings.descriptionFor(layer != null ? layer : "LA", type != null ? type : "LAB");
        String name = suggestedName != null && !suggestedName.isBlank() ? suggestedName : ("AI " + type + " Diagram");
        return DiagramCommands.createAndOpenDiagram(root, desc, name) != null;
    }

    private static boolean rename(Session session, String elementClass, String existingName, String newName) {
        EObject target = Finders.findByName(session, elementClass, existingName);
        if (target == null) {
            return false;
        }
        return ModelOps.renameByUuid(session, uuidOf(target), newName);
    }

    private static boolean delete(Session session, String elementClass, String existingName) {
        EObject target = Finders.findByName(session, elementClass, existingName);
        if (target == null) {
            return false;
        }
        return ModelOps.deleteByUuid(session, uuidOf(target));
    }

    private static void setEndpoint(EObject exchange, String role, EObject end) {
        if (exchange == null || end == null) return;
        try {
            for (var feature : exchange.eClass().getEAllStructuralFeatures()) {
                if (feature instanceof org.eclipse.emf.ecore.EReference) {
                    org.eclipse.emf.ecore.EReference ref = (org.eclipse.emf.ecore.EReference) feature;
                    if (!ref.isContainment()) {
                        String name = ref.getName().toLowerCase(Locale.ROOT);
                        boolean matches = (role.equals("source") && (name.contains("src") || name.contains("source")))
                                || (role.equals("target") && (name.contains("tgt") || name.contains("target")));
                        if (matches) {
                            var type = ref.getEType();
                            if (type instanceof org.eclipse.emf.ecore.EClass) {
                                org.eclipse.emf.ecore.EClass eClass = (org.eclipse.emf.ecore.EClass) type;
                                if (eClass.isSuperTypeOf(end.eClass()) || eClass == end.eClass()) {
                                    exchange.eSet(ref, end);
                                    return;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignore) {
        }
    }

    private static String uuidOf(EObject eo) {
        if (eo == null) {
            return "";
        }
        var feature = eo.eClass().getEStructuralFeature("uuid");
        if (feature != null) {
            Object value = eo.eGet(feature);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return String.valueOf(eo.hashCode());
    }

    private enum ActionType {
        CREATE_COMPONENT,
        CREATE_FUNCTION,
        CREATE_COMPONENT_EXCHANGE,
        CREATE_FUNCTIONAL_EXCHANGE,
        CREATE_LAB,
        OPEN_DIAGRAM,
        RENAME,
        DELETE;

        static ActionType from(String value) {
            if (value == null) {
                throw new PlanValidationException("Action type missing");
            }
            switch (value) {
                case "create_component":
                    return CREATE_COMPONENT;
                case "create_function":
                    return CREATE_FUNCTION;
                case "create_component_exchange":
                    return CREATE_COMPONENT_EXCHANGE;
                case "create_functional_exchange":
                    return CREATE_FUNCTIONAL_EXCHANGE;
                case "create_lab":
                    return CREATE_LAB;
                case "open_diagram":
                    return OPEN_DIAGRAM;
                case "rename":
                    return RENAME;
                case "delete":
                    return DELETE;
                default:
                    throw new PlanValidationException("Unknown action type: " + value);
            }
        }
    }

    private static final class Action {
        final ActionType type;
        final String layer;
        final String name;
        final String sourceName;
        final String targetName;
        final String elementClass;
        final String existingName;
        final String newName;
        final String diagramType;

        Action(ActionType type, String layer, String name, String sourceName, String targetName,
               String elementClass, String existingName, String newName, String diagramType) {
            this.type = type;
            this.layer = layer;
            this.name = name;
            this.sourceName = sourceName;
            this.targetName = targetName;
            this.elementClass = elementClass;
            this.existingName = existingName;
            this.newName = newName;
            this.diagramType = diagramType;
        }

        static Action fromMap(int index, Map<String, Object> map) {
            String typeValue = string(map.get("type"));
            ActionType type = ActionType.from(typeValue);
            switch (type) {
                case CREATE_COMPONENT:
                    return new Action(type, stringOrDefault(map.get("layer"), "LA"), require(map, "name", index), null, null, null, null, null, null);
                case CREATE_FUNCTION:
                    return new Action(type, stringOrDefault(map.get("layer"), "LA"), require(map, "name", index), null, null, null, null, null, null);
                case CREATE_COMPONENT_EXCHANGE:
                    return new Action(type,
                            stringOrDefault(map.get("layer"), "LA"),
                            stringOrDefault(map.get("name"), "ComponentExchange"),
                            require(map, "source_name", index),
                            require(map, "target_name", index),
                            null, null, null, null);
                case CREATE_FUNCTIONAL_EXCHANGE:
                    return new Action(type,
                            stringOrDefault(map.get("layer"), "LA"),
                            stringOrDefault(map.get("name"), "FunctionalExchange"),
                            require(map, "source_function", index),
                            require(map, "target_function", index),
                            null, null, null, null);
                case CREATE_LAB:
                    return new Action(type, stringOrDefault(map.get("layer"), "LA"), null, null, null, null, null, null, null);
                case OPEN_DIAGRAM:
                    return new Action(type, stringOrDefault(map.get("layer"), "LA"), stringOrDefault(map.get("name"), ""), null, null, null, null, null, stringOrDefault(map.get("type"), "LAB"));
                case RENAME:
                    return new Action(type, null, null, null, null, require(map, "class", index), require(map, "name", index), require(map, "new_name", index), null);
                case DELETE:
                    return new Action(type, null, null, null, null, require(map, "class", index), require(map, "name", index), null, null);
                default:
                    throw new PlanValidationException("Unsupported action type");
            }
        }

        String describe() {
            switch (type) {
                case CREATE_COMPONENT:
                case CREATE_FUNCTION:
                    return String.format("%s '%s' in %s", typeLabel(), name, layer);
                case CREATE_COMPONENT_EXCHANGE:
                    return String.format("%s '%s' between %s → %s", typeLabel(), name, sourceName, targetName);
                case CREATE_FUNCTIONAL_EXCHANGE:
                    return String.format("%s '%s' between %s → %s", typeLabel(), name, sourceName, targetName);
                case CREATE_LAB:
                    return String.format("Create LAB diagram in %s", layer);
                case OPEN_DIAGRAM:
                    return String.format("Open %s diagram (%s)", diagramType, name == null || name.isBlank() ? "auto-named" : name);
                case RENAME:
                    return String.format("Rename %s '%s' → '%s'", elementClass, existingName, newName);
                case DELETE:
                    return String.format("Delete %s '%s'", elementClass, existingName);
                default:
                    return typeLabel();
            }
        }

        private String typeLabel() {
            return type.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
    }

    private static String require(Map<String, Object> map, String key, int index) {
        String value = string(map.get(key));
        if (value == null || value.isBlank()) {
            throw new PlanValidationException("Action " + index + " missing field '" + key + "'");
        }
        return value;
    }

    private static String stringOrDefault(Object value, String defaultValue) {
        String str = string(value);
        return (str == null || str.isBlank()) ? defaultValue : str;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static final class PlanValidationException extends RuntimeException {
        PlanValidationException(String message) {
            super(message);
        }
    }
}
