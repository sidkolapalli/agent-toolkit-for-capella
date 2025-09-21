package org.polarsys.capella.ai.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.viewpoint.DRepresentation;
import org.polarsys.capella.ai.model.ArchitectureLocator;
import org.polarsys.capella.ai.model.ElementFactory;
import org.polarsys.capella.ai.model.Finders;
import org.polarsys.capella.ai.model.ModelOps;
import org.polarsys.capella.ai.actions.FunctionalChainActions;
import org.polarsys.capella.ai.actions.OperationalActions;
import org.polarsys.capella.ai.actions.RequirementActions;
import org.polarsys.capella.ai.actions.SystemActions;
import org.polarsys.capella.ai.actions.TransformationActions;
import org.polarsys.capella.ai.model.ModelSummary;
import org.polarsys.capella.ai.tools.DiagramCommands;
import org.polarsys.capella.ai.tools.DiagramMappings;
import org.polarsys.capella.ai.session.SessionSafeguards;

/**
 * Parses TOOL_PLAN responses and applies requested actions against the active Capella session.
 */
public final class ToolRouter {

    private ToolRouter() {}

    public static final class Result {
        public String reply;
        public ToolPlan plan;
        public String error;
    }

    public static final class ToolPlan {
        private final List<Action> actions;

        ToolPlan(List<Action> actions) {
            this.actions = Collections.unmodifiableList(actions);
        }

        public boolean isEmpty() {
            return actions.isEmpty();
        }

        public int size() {
            return actions.size();
        }

        public List<String> describe() {
            List<String> lines = new ArrayList<>(actions.size());
            for (Action action : actions) {
                lines.add(action.describe());
            }
            return lines;
        }

        public List<PlanItem> items() {
            if (actions.isEmpty()) {
                return List.of();
            }
            List<PlanItem> items = new ArrayList<>(actions.size());
            for (int i = 0; i < actions.size(); i++) {
                items.add(new PlanItem(i, actions.get(i)));
            }
            return Collections.unmodifiableList(items);
        }

        public ToolPlan filterByIndex(List<Integer> indices) {
            if (indices == null || indices.isEmpty()) {
                return new ToolPlan(List.of());
            }
            List<Action> subset = new ArrayList<>(indices.size());
            for (Integer idx : indices) {
                if (idx == null) {
                    continue;
                }
                int value = idx.intValue();
                if (value < 0 || value >= actions.size()) {
                    continue;
                }
                subset.add(actions.get(value));
            }
            if (subset.size() == actions.size()) {
                return this;
            }
            return new ToolPlan(subset);
        }

        List<Action> actions() {
            return actions;
        }
    }

    public static final class PlanItem {
        private final int index;
        private final Action action;

        PlanItem(int index, Action action) {
            this.index = index;
            this.action = action;
        }

        public int getIndex() {
            return index;
        }

        public String getTypeId() {
            return action.type.name();
        }

        public String getTypeLabel() {
            return action.typeLabel();
        }

        public String getLayer() {
            return action.layer;
        }

        public String getName() {
            return action.name;
        }

        public String getSourceName() {
            return action.sourceName;
        }

        public String getTargetName() {
            return action.targetName;
        }

        public String getElementClass() {
            return action.elementClass;
        }

        public String getExistingName() {
            return action.existingName;
        }

        public String getNewName() {
            return action.newName;
        }

        public String getDiagramType() {
            return action.diagramType;
        }

        public String getDescription() {
            return action.describe();
        }

        public String getParentName() {
            return action.parentName;
        }

        public String getCapabilityName() {
            return action.capabilityName;
        }

        public String getLogicalName() {
            return action.logicalName;
        }

        public String getPhysicalName() {
            return action.physicalName;
        }

        public String getTargetLayer() {
            return action.targetLayer;
        }

        public boolean includeChildren() {
            return action.includeChildren;
        }

        public boolean updateAttributes() {
            return action.updateAttributes;
        }

        public String getChainName() {
            return action.chainName;
        }

        public String getFunctionName() {
            return action.functionName;
        }

        public String getFunctionClass() {
            return action.functionClass;
        }

        public String getExchangeName() {
            return action.exchangeName;
        }

        public String getScenarioName() {
            return action.scenarioName;
        }

        public String getContainerClass() {
            return action.containerClass;
        }

        public String getContainerName() {
            return action.containerName;
        }

        public String getRequirementType() {
            return action.requirementType;
        }

        public String getRequirementTargetClass() {
            return action.requirementTargetClass;
        }

        public String getRequirementStatus() {
            return action.requirementStatus;
        }

        public String getRequirementNotes() {
            return action.requirementNotes;
        }

        public boolean requiresConfirmation() {
            return action.type == ActionType.SYNC_PHYSICAL_FROM_LOGICAL;
        }

        public ChangeCategory getCategory() {
            return action.category();
        }

        public String getDiffSummary() {
            return action.diffSummary();
        }

    }

    public enum ChangeCategory {
        CREATE("Creates"),
        UPDATE("Updates"),
        DELETE("Deletes"),
        OPEN("View");

        private final String label;

        ChangeCategory(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public static final class PlanExecutionResult {
        public final int applied;
        public final List<String> errors;

        PlanExecutionResult(int applied, List<String> errors) {
            this.applied = applied;
            this.errors = errors;
        }
    }

    public static Result handleChat(Session session, String userMessage) {
        Result result = new Result();
        try {
            if (session == null) {
                throw new IllegalStateException("No active Capella session");
            }
            String prompt = buildPrompt(session, userMessage);
            String reply = LLMService.get().send(prompt);
            result.reply = reply;

            String planJson = extractPlan(reply);
            if (planJson != null && !planJson.isBlank()) {
                List<Action> actions = parseActions(planJson);
                result.plan = actions.isEmpty() ? null : new ToolPlan(actions);
            }
        } catch (PlanValidationException ex) {
            result.error = ex.getMessage();
        } catch (Exception ex) {
            result.error = ex.getMessage();
        }
        return result;
    }

    public static PlanExecutionResult applyPlan(Session session, DRepresentation diagram, ToolPlan plan) {
        if (session == null || plan == null || plan.isEmpty()) {
            return new PlanExecutionResult(0, List.of());
        }
        return SessionSafeguards.deferSaves(session, () ->
            SessionSafeguards.runWrite(session, "Apply AI plan", () -> applyPlanInternal(session, diagram, plan))
        );
    }

    private static PlanExecutionResult applyPlanInternal(Session session, DRepresentation diagram, ToolPlan plan) {
        int applied = 0;
        List<String> errors = new ArrayList<>();
        for (Action action : plan.actions()) {
            try {
                boolean success = applyAction(session, action);
                if (success) {
                    applied++;
                } else {
                    errors.add("Failed: " + action.describe());
                }
            } catch (Exception ex) {
                errors.add(action.describe() + " → " + ex.getMessage());
            }
        }
        return new PlanExecutionResult(applied, errors);
    }

    private static boolean applyAction(Session session, Action action) {
        switch (action.type) {
            case CREATE_COMPONENT:
                return createComponent(session, action.layer, action.name);
            case CREATE_FUNCTION:
                return createFunction(session, action.layer, action.name);
            case CREATE_COMPONENT_EXCHANGE:
                return createComponentExchange(session, action.layer, action.sourceName, action.targetName, action.name);
            case CREATE_FUNCTIONAL_EXCHANGE:
                return createFunctionalExchange(session, action.layer, action.sourceName, action.targetName, action.name);
            case CREATE_LAB:
                return createLabDiagram(session, action.layer);
            case CREATE_MISSION:
                return OperationalActions.createMission(session, action.layer, action.name, action.description);
            case CREATE_OPERATIONAL_ACTIVITY:
                return OperationalActions.createOperationalActivity(session, action.layer, action.name, action.parentName);
            case CREATE_OPERATIONAL_EXCHANGE:
                return OperationalActions.createOperationalExchange(session, action.layer, action.name, action.sourceName, action.targetName);
            case LINK_OPERATIONAL_TO_CAPABILITY:
                return OperationalActions.linkActivityToCapability(session, action.existingName, action.capabilityName);
            case CREATE_SYSTEM_COMPONENT:
                return SystemActions.createComponent(session, action.layer, action.name, action.parentName);
            case CREATE_SYSTEM_FUNCTION:
                return SystemActions.createFunction(session, action.layer, action.name, action.parentName);
            case CREATE_INTERFACE:
                return SystemActions.createInterface(session, action.layer, action.name, action.sourceName, action.targetName);
            case ALLOCATE_CAPABILITY:
                return SystemActions.allocateCapability(session, action.existingName, action.capabilityName);
            case PROPAGATE_LOGICAL_TO_PHYSICAL:
                String targetLayer = (action.targetLayer != null && !action.targetLayer.isBlank()) ? action.targetLayer : action.layer;
                return TransformationActions.propagateLogicalComponent(session, action.logicalName, targetLayer, action.includeChildren);
            case SYNC_PHYSICAL_FROM_LOGICAL:
                return TransformationActions.syncPhysicalFromLogical(session, action.logicalName, action.physicalName, action.updateAttributes);
            case TRACE_COMPONENT_MAPPING:
                return TransformationActions.traceComponentMapping(session, action.logicalName, action.physicalName);
            case CREATE_FUNCTIONAL_CHAIN:
                return FunctionalChainActions.createChain(session, action.layer, action.name, action.description);
            case EXTEND_CHAIN:
                return FunctionalChainActions.addFunction(session, action.chainName, action.functionName, action.functionClass);
            case SEQUENCE_EXCHANGE:
                return FunctionalChainActions.addExchange(session, action.chainName, action.exchangeName);
            case ATTACH_SCENARIO:
                return FunctionalChainActions.attachScenario(session, action.chainName, action.scenarioName);
            case CREATE_REQUIREMENT:
                return RequirementActions.createRequirement(session, action.containerClass, action.containerName, action.requirementType, action.name, action.description);
            case LINK_REQUIREMENT:
                return RequirementActions.linkRequirement(session, action.name, action.requirementTargetClass, action.targetName);
            case RECORD_REQUIREMENT_COVERAGE:
                return RequirementActions.recordCoverage(session, action.requirementTargetClass, action.targetName, action.requirementStatus, action.requirementNotes);
            case OPEN_DIAGRAM:
                return openDiagram(session, action.layer, action.diagramType, action.name);
            case RENAME:
                return rename(session, action.elementClass, action.existingName, action.newName);
            case DELETE:
                return delete(session, action.elementClass, action.existingName);
            default:
                return false;
        }
    }

    private static String buildPrompt(Session session, String user) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an AI agent inside Capella.\n");
        sb.append("When the user asks for changes, output a line starting with 'TOOL_PLAN:' followed by compact JSON.\n");
        sb.append("Schema: {\"actions\":[{\"type\":one of \"create_component\",\"create_function\",\"create_component_exchange\",\"create_functional_exchange\",\"create_lab\",\"rename\",\"delete\",\"open_diagram\",\"create_mission\",\"create_operational_activity\",\"create_operational_exchange\",\"link_operational_to_capability\",\"create_system_component\",\"create_system_function\",\"create_interface\",\"allocate_capability\",\"propagate_logical_to_physical\",\"sync_physical_from_logical\",\"trace_component_mapping\",\"create_functional_chain\",\"extend_chain\",\"sequence_exchange\",\"attach_scenario\",\"create_requirement\",\"link_requirement\",\"record_requirement_coverage\"},...]}\n");
        sb.append("Action fields:\n");
        sb.append("- create_component: {layer, name}\n");
        sb.append("- create_function: {layer, name}\n");
        sb.append("- create_component_exchange: {source_name, target_name, layer, name}\n");
        sb.append("- create_functional_exchange: {source_function, target_function, layer, name}\n");
        sb.append("- create_lab: {layer}\n");
        sb.append("- create_mission: {name, layer?, description?}\n");
        sb.append("- create_operational_activity: {name, layer?, parent_name?}\n");
        sb.append("- create_operational_exchange: {source_actor, target_actor, layer?, name?}\n");
        sb.append("- link_operational_to_capability: {operational_activity, capability, layer?}\n");
        sb.append("- create_system_component: {name, layer?, parent_name?}\n");
        sb.append("- create_system_function: {name, layer?, parent_name?}\n");
        sb.append("- create_interface: {source_component, target_component, layer?, name?}\n");
        sb.append("- allocate_capability: {component, capability, layer?}\n");
        sb.append("- propagate_logical_to_physical: {logical_component, target_layer?, include_children?}\n");
        sb.append("- sync_physical_from_logical: {logical_component, physical_component, layer?, update_attributes?, include_children?}\n");
        sb.append("- trace_component_mapping: {logical_component, physical_component, layer?}\n");
        sb.append("- create_functional_chain: {name, layer?, description?}\n");
        sb.append("- extend_chain: {chain, function, layer?, function_class?}\n");
        sb.append("- sequence_exchange: {chain, exchange, layer?}\n");
        sb.append("- attach_scenario: {chain, scenario, layer?}\n");
        sb.append("- create_requirement: {name, container_class?, container_name?, requirement_type?, description?, layer?}\n");
        sb.append("- link_requirement: {requirement, target_class, target_name, layer?}\n");
        sb.append("- record_requirement_coverage: {target_class, target_name, status?, notes?, layer?}\n");
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
        List<?> list = (List<?>) rawActions;
        if (list.isEmpty()) {
            return List.of();
        }
        List<Action> actions = new ArrayList<>(list.size());
        for (int index = 0; index < list.size(); index++) {
            Object entry = list.get(index);
            if (!(entry instanceof Map)) {
                throw new PlanValidationException("Action " + index + " must be an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) entry;
            actions.add(Action.fromMap(index, map));
        }
        return actions;
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
        String name = (suggestedName != null && !suggestedName.isBlank()) ? suggestedName : ("AI " + type + " Diagram");
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
        if (exchange == null || end == null) {
            return;
        }
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
        DELETE,
        CREATE_MISSION,
        CREATE_OPERATIONAL_ACTIVITY,
        CREATE_OPERATIONAL_EXCHANGE,
        LINK_OPERATIONAL_TO_CAPABILITY,
        CREATE_SYSTEM_COMPONENT,
        CREATE_SYSTEM_FUNCTION,
        CREATE_INTERFACE,
        ALLOCATE_CAPABILITY,
        PROPAGATE_LOGICAL_TO_PHYSICAL,
        SYNC_PHYSICAL_FROM_LOGICAL,
        TRACE_COMPONENT_MAPPING,
        CREATE_FUNCTIONAL_CHAIN,
        EXTEND_CHAIN,
        SEQUENCE_EXCHANGE,
        ATTACH_SCENARIO,
        CREATE_REQUIREMENT,
        LINK_REQUIREMENT,
        RECORD_REQUIREMENT_COVERAGE;

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
                case "create_mission":
                    return CREATE_MISSION;
                case "create_operational_activity":
                    return CREATE_OPERATIONAL_ACTIVITY;
                case "create_operational_exchange":
                    return CREATE_OPERATIONAL_EXCHANGE;
                case "link_operational_to_capability":
                    return LINK_OPERATIONAL_TO_CAPABILITY;
                case "create_system_component":
                    return CREATE_SYSTEM_COMPONENT;
                case "create_system_function":
                    return CREATE_SYSTEM_FUNCTION;
                case "create_interface":
                    return CREATE_INTERFACE;
                case "allocate_capability":
                    return ALLOCATE_CAPABILITY;
                case "propagate_logical_to_physical":
                    return PROPAGATE_LOGICAL_TO_PHYSICAL;
                case "sync_physical_from_logical":
                    return SYNC_PHYSICAL_FROM_LOGICAL;
                case "trace_component_mapping":
                    return TRACE_COMPONENT_MAPPING;
                case "create_functional_chain":
                    return CREATE_FUNCTIONAL_CHAIN;
                case "extend_chain":
                    return EXTEND_CHAIN;
                case "sequence_exchange":
                    return SEQUENCE_EXCHANGE;
                case "attach_scenario":
                    return ATTACH_SCENARIO;
                case "create_requirement":
                    return CREATE_REQUIREMENT;
                case "link_requirement":
                    return LINK_REQUIREMENT;
                case "record_requirement_coverage":
                    return RECORD_REQUIREMENT_COVERAGE;
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
        final String parentName;
        final String description;
        final String capabilityName;
        final String logicalName;
        final String physicalName;
        final String targetLayer;
        final boolean includeChildren;
        final boolean updateAttributes;
        final String chainName;
        final String functionName;
        final String functionClass;
        final String exchangeName;
        final String scenarioName;
        String containerClass;
        String containerName;
        String requirementType;
        String requirementTargetClass;
        String requirementStatus;
        String requirementNotes;

        Action(ActionType type, String layer, String name, String sourceName, String targetName,
               String elementClass, String existingName, String newName, String diagramType,
               String parentName, String description, String capabilityName,
               String logicalName, String physicalName, String targetLayer,
               boolean includeChildren, boolean updateAttributes,
               String chainName, String functionName, String functionClass,
               String exchangeName, String scenarioName) {
            this.type = type;
            this.layer = layer;
            this.name = name;
            this.sourceName = sourceName;
            this.targetName = targetName;
            this.elementClass = elementClass;
            this.existingName = existingName;
            this.newName = newName;
            this.diagramType = diagramType;
            this.parentName = parentName;
            this.description = description;
            this.capabilityName = capabilityName;
            this.logicalName = logicalName;
            this.physicalName = physicalName;
            this.targetLayer = targetLayer;
            this.includeChildren = includeChildren;
            this.updateAttributes = updateAttributes;
            this.chainName = chainName;
            this.functionName = functionName;
            this.functionClass = functionClass;
            this.exchangeName = exchangeName;
            this.scenarioName = scenarioName;
        }

        static Action fromMap(int index, Map<String, Object> map) {
            String typeValue = string(map.get("type"));
            ActionType type = ActionType.from(typeValue);
            switch (type) {
                case CREATE_COMPONENT:
                    return new Action(type, stringOrDefault(map.get("layer"), "LA"), require(map, "name", index), null, null, null, null, null, null, null, null, null,
                            null, null, null, false, false, null, null, null, null, null);
                case CREATE_FUNCTION:
                    return new Action(type, stringOrDefault(map.get("layer"), "LA"), require(map, "name", index), null, null, null, null, null, null, null, null, null,
                            null, null, null, false, false, null, null, null, null, null);
                case CREATE_COMPONENT_EXCHANGE:
                    return new Action(type,
                            stringOrDefault(map.get("layer"), "LA"),
                            stringOrDefault(map.get("name"), "ComponentExchange"),
                            require(map, "source_name", index),
                            require(map, "target_name", index),
                            null, null, null, null, null, null, null,
                            null, null, null, false, false, null, null, null, null, null);
                case CREATE_FUNCTIONAL_EXCHANGE:
                    return new Action(type,
                            stringOrDefault(map.get("layer"), "LA"),
                            stringOrDefault(map.get("name"), "FunctionalExchange"),
                            require(map, "source_function", index),
                            require(map, "target_function", index),
                            null, null, null, null, null, null, null,
                            null, null, null, false, false, null, null, null, null, null);
                case CREATE_LAB:
                    return new Action(type, stringOrDefault(map.get("layer"), "LA"), null, null, null, null, null, null, null, null, null, null,
                            null, null, null, false, false, null, null, null, null, null);
                case OPEN_DIAGRAM:
                    return new Action(type, stringOrDefault(map.get("layer"), "LA"), stringOrDefault(map.get("name"), ""), null, null, null, null, null, stringOrDefault(map.get("type"), "LAB"), null, null, null,
                            null, null, null, false, false, null, null, null, null, null);
                case RENAME:
                    return new Action(type, null, null, null, null, require(map, "class", index), require(map, "name", index), require(map, "new_name", index), null, null, null, null,
                            null, null, null, false, false, null, null, null, null, null);
                case CREATE_MISSION:
                    return new Action(type, stringOrDefault(map.get("layer"), "OA"), require(map, "name", index), null, null, null, null, null, null, null, stringOrDefault(map.get("description"), ""), null,
                            null, null, null, false, false, null, null, null, null, null);
                case CREATE_OPERATIONAL_ACTIVITY:
                    return new Action(type, stringOrDefault(map.get("layer"), "OA"), require(map, "name", index), null, null, null, null, null, null, stringOrDefault(map.get("parent_name"), ""), null, null,
                            null, null, null, false, false, null, null, null, null, null);
                case CREATE_OPERATIONAL_EXCHANGE:
                    return new Action(type, stringOrDefault(map.get("layer"), "OA"), stringOrDefault(map.get("name"), "OperationalExchange"), require(map, "source_actor", index), require(map, "target_actor", index), null, null, null, null, null, null, null,
                            null, null, null, false, false, null, null, null, null, null);
                case LINK_OPERATIONAL_TO_CAPABILITY:
                    return new Action(type, stringOrDefault(map.get("layer"), "OA"), null, null, null, null, require(map, "operational_activity", index), null, null, null, null, require(map, "capability", index),
                            null, null, null, false, false, null, null, null, null, null);
                case CREATE_SYSTEM_COMPONENT:
                    return new Action(type, stringOrDefault(map.get("layer"), "SA"), require(map, "name", index), null, null, null, null, null, null, stringOrDefault(map.get("parent_name"), ""), null, null,
                            null, null, null, false, false, null, null, null, null, null);
                case CREATE_SYSTEM_FUNCTION:
                    return new Action(type, stringOrDefault(map.get("layer"), "SA"), require(map, "name", index), null, null, null, null, null, null, stringOrDefault(map.get("parent_name"), ""), null, null,
                            null, null, null, false, false, null, null, null, null, null);
                case CREATE_INTERFACE:
                    return new Action(type, stringOrDefault(map.get("layer"), "SA"), stringOrDefault(map.get("name"), "ComponentExchange"), require(map, "source_component", index), require(map, "target_component", index), null, null, null, null, null, null, null,
                            null, null, null, false, false, null, null, null, null, null);
                case ALLOCATE_CAPABILITY:
                    return new Action(type, stringOrDefault(map.get("layer"), "SA"), null, null, null, null, require(map, "component", index), null, null, null, null, require(map, "capability", index),
                            null, null, null, false, false, null, null, null, null, null);
                case DELETE:
                    return new Action(type, null, null, null, null, require(map, "class", index), require(map, "name", index), null, null, null, null, null,
                            null, null, null, false, false, null, null, null, null, null);
                case PROPAGATE_LOGICAL_TO_PHYSICAL:
                    String targetLayer = stringOrDefault(map.get("target_layer"), stringOrDefault(map.get("layer"), "PA"));
                    return new Action(type, targetLayer, null, null, null, null, null, null, null, null, null, null,
                            require(map, "logical_component", index), null, targetLayer,
                            bool(map.get("include_children"), true), false, null, null, null, null, null);
                case SYNC_PHYSICAL_FROM_LOGICAL:
                    String syncLayer = stringOrDefault(map.get("layer"), "PA");
                    return new Action(type, syncLayer, null, null, null, null, null, null, null, null, null, null,
                            require(map, "logical_component", index), require(map, "physical_component", index), syncLayer,
                            bool(map.get("include_children"), false), bool(map.get("update_attributes"), true), null, null, null, null, null);
                case TRACE_COMPONENT_MAPPING:
                    String traceLayer = stringOrDefault(map.get("layer"), "");
                    return new Action(type, traceLayer, null, null, null, null, null, null, null, null, null, null,
                            require(map, "logical_component", index), require(map, "physical_component", index), traceLayer,
                            false, false, null, null, null, null, null);
                case CREATE_FUNCTIONAL_CHAIN:
                    return new Action(type, stringOrDefault(map.get("layer"), "LA"), require(map, "name", index), null, null, null, null, null, null, null, stringOrDefault(map.get("description"), ""), null,
                            null, null, null, false, false, require(map, "name", index), null, null, null, null);
                case EXTEND_CHAIN:
                    return new Action(type, stringOrDefault(map.get("layer"), "LA"), null, null, null, null, null, null, null, null, null, null,
                            null, null, null, false, false,
                            require(map, "chain", index), require(map, "function", index), string(map.get("function_class")), null, null);
                case SEQUENCE_EXCHANGE:
                    return new Action(type, stringOrDefault(map.get("layer"), "LA"), null, null, null, null, null, null, null, null, null, null,
                            null, null, null, false, false,
                            require(map, "chain", index), null, null, require(map, "exchange", index), null);
                case ATTACH_SCENARIO:
                    return new Action(type, stringOrDefault(map.get("layer"), "LA"), null, null, null, null, null, null, null, null, null, null,
                            null, null, null, false, false,
                            require(map, "chain", index), null, null, null, require(map, "scenario", index));
                case CREATE_REQUIREMENT:
                    Action createReq = new Action(type, stringOrDefault(map.get("layer"), "LA"), require(map, "name", index), null, null, null, null, null, null, null, stringOrDefault(map.get("description"), ""), null,
                            null, null, null, false, false,
                            null, null, null, null, null);
                    createReq.containerClass = string(map.get("container_class"));
                    createReq.containerName = string(map.get("container_name"));
                    createReq.requirementType = string(map.get("requirement_type"));
                    return createReq;
                case LINK_REQUIREMENT:
                    Action linkReq = new Action(type, stringOrDefault(map.get("layer"), "LA"), require(map, "requirement", index), null, stringOrDefault(map.get("target_name"), ""), null, null, null, null, null, null, null,
                            null, null, null, false, false,
                            null, null, null, null, null);
                    linkReq.requirementTargetClass = require(map, "target_class", index);
                    return linkReq;
                case RECORD_REQUIREMENT_COVERAGE:
                    Action coverage = new Action(type, stringOrDefault(map.get("layer"), "LA"), null, null, stringOrDefault(map.get("target_name"), ""), null, null, null, null, null, null, null,
                            null, null, null, false, false,
                            null, null, null, null, null);
                    coverage.requirementTargetClass = require(map, "target_class", index);
                    coverage.requirementStatus = stringOrDefault(map.get("status"), "");
                    coverage.requirementNotes = stringOrDefault(map.get("notes"), "");
                    return coverage;
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
                case CREATE_MISSION:
                    return String.format("Create mission '%s' in %s", name, layer);
                case CREATE_OPERATIONAL_ACTIVITY:
                    return parentName != null && !parentName.isBlank() ?
                        String.format("Create operational activity '%s' under %s", name, parentName) :
                        String.format("Create operational activity '%s' in %s", name, layer);
                case CREATE_OPERATIONAL_EXCHANGE:
                    return String.format("Create operational exchange '%s' between %s → %s", name, sourceName, targetName);
                case LINK_OPERATIONAL_TO_CAPABILITY:
                    return String.format("Link operational activity '%s' to capability '%s'", existingName, capabilityName);
                case CREATE_SYSTEM_COMPONENT:
                    return parentName != null && !parentName.isBlank() ?
                        String.format("Create system component '%s' under %s", name, parentName) :
                        String.format("Create system component '%s' in %s", name, layer);
                case CREATE_SYSTEM_FUNCTION:
                    return parentName != null && !parentName.isBlank() ?
                        String.format("Create system function '%s' under %s", name, parentName) :
                        String.format("Create system function '%s' in %s", name, layer);
                case CREATE_INTERFACE:
                    return String.format("Create interface '%s' between %s → %s", name, sourceName, targetName);
                case ALLOCATE_CAPABILITY:
                    return String.format("Allocate capability '%s' to system component '%s'", capabilityName, existingName);
                case PROPAGATE_LOGICAL_TO_PHYSICAL:
                    return String.format("Propagate logical component '%s' to %s%s",
                            defaultString(logicalName, "?"),
                            safeLayer(defaultString(targetLayer, defaultString(layer, "PA"))),
                            includeChildren ? " (with hierarchy)" : "");
                case SYNC_PHYSICAL_FROM_LOGICAL:
                    return String.format("Sync physical component '%s' from logical '%s'%s",
                            defaultString(physicalName, "?"),
                            defaultString(logicalName, "?"),
                            updateAttributes ? "" : " (link only)");
                case TRACE_COMPONENT_MAPPING:
                    return String.format("Trace logical '%s' <-> physical '%s'", defaultString(logicalName, "?"), defaultString(physicalName, "?"));
                case OPEN_DIAGRAM:
                    return String.format("Open %s diagram (%s)", diagramType, name == null || name.isBlank() ? "auto-named" : name);
                case RENAME:
                    return String.format("Rename %s '%s' → '%s'", elementClass, existingName, newName);
                case DELETE:
                    return String.format("Delete %s '%s'", elementClass, existingName);
                case CREATE_FUNCTIONAL_CHAIN:
                    return String.format("Create functional chain '%s' in %s", defaultString(name, "(auto)"), safeLayer(layer));
                case EXTEND_CHAIN:
                    return String.format("Extend chain '%s' with function '%s'", defaultString(chainName, "?"), defaultString(functionName, "?"));
                case SEQUENCE_EXCHANGE:
                    return String.format("Add exchange '%s' to chain '%s'", defaultString(exchangeName, "?"), defaultString(chainName, "?"));
                case ATTACH_SCENARIO:
                    return String.format("Attach scenario '%s' to chain '%s'", defaultString(scenarioName, "?"), defaultString(chainName, "?"));
                case CREATE_REQUIREMENT:
                    String containerLabel = (containerName != null && !containerName.isBlank()) ? containerName
                            : defaultString(containerClass, "requirements container");
                    return String.format("Create requirement '%s' in %s", defaultString(name, "?"), containerLabel);
                case LINK_REQUIREMENT:
                    return String.format("Link requirement '%s' to %s '%s'", defaultString(name, "?"),
                            defaultString(requirementTargetClass, "element"), defaultString(targetName, "?"));
                case RECORD_REQUIREMENT_COVERAGE:
                    return String.format("Record coverage for %s '%s' (%s)",
                            defaultString(requirementTargetClass, "element"), defaultString(targetName, "?"),
                            defaultString(requirementStatus, "status"));
                default:
                    return typeLabel();
            }
        }

        ChangeCategory category() {
            switch (type) {
                case CREATE_COMPONENT:
                case CREATE_FUNCTION:
                case CREATE_COMPONENT_EXCHANGE:
                case CREATE_FUNCTIONAL_EXCHANGE:
                case CREATE_LAB:
                case CREATE_MISSION:
                case CREATE_OPERATIONAL_ACTIVITY:
                case CREATE_OPERATIONAL_EXCHANGE:
                case CREATE_SYSTEM_COMPONENT:
                case CREATE_SYSTEM_FUNCTION:
                case CREATE_INTERFACE:
                case PROPAGATE_LOGICAL_TO_PHYSICAL:
                case CREATE_FUNCTIONAL_CHAIN:
                case CREATE_REQUIREMENT:
                    return ChangeCategory.CREATE;
                case RENAME:
                case LINK_OPERATIONAL_TO_CAPABILITY:
                case ALLOCATE_CAPABILITY:
                case SYNC_PHYSICAL_FROM_LOGICAL:
                case TRACE_COMPONENT_MAPPING:
                case EXTEND_CHAIN:
                case SEQUENCE_EXCHANGE:
                case ATTACH_SCENARIO:
                case LINK_REQUIREMENT:
                case RECORD_REQUIREMENT_COVERAGE:
                    return ChangeCategory.UPDATE;
                case DELETE:
                    return ChangeCategory.DELETE;
                case OPEN_DIAGRAM:
                default:
                    return ChangeCategory.OPEN;
            }
        }

        String diffSummary() {
            switch (type) {
                case CREATE_COMPONENT:
                    return summarizeCreate("Component", name, layer);
                case CREATE_FUNCTION:
                    return summarizeCreate("Function", name, layer);
                case CREATE_COMPONENT_EXCHANGE:
                    return summarizeExchange("Component exchange", name, sourceName, targetName, layer);
                case CREATE_FUNCTIONAL_EXCHANGE:
                    return summarizeExchange("Functional exchange", name, sourceName, targetName, layer);
                case CREATE_LAB:
                    return String.format("Create LAB diagram in %s", safeLayer(layer));
                case CREATE_MISSION:
                    return summarizeCreate("Mission", name, layer);
                case CREATE_OPERATIONAL_ACTIVITY:
                    return parentName != null && !parentName.isBlank() ?
                        String.format("Create operational activity '%s' under %s", name, parentName) :
                        summarizeCreate("Operational activity", name, layer);
                case CREATE_OPERATIONAL_EXCHANGE:
                    return summarizeExchange("Operational exchange", name, sourceName, targetName, layer);
                case LINK_OPERATIONAL_TO_CAPABILITY:
                    return String.format("Link operational activity '%s' to capability '%s'", defaultString(existingName, "?"), defaultString(capabilityName, "?"));
                case CREATE_SYSTEM_COMPONENT:
                    return parentName != null && !parentName.isBlank() ?
                        String.format("Create system component '%s' under %s", name, parentName) :
                        summarizeCreate("System component", name, layer);
                case CREATE_SYSTEM_FUNCTION:
                    return parentName != null && !parentName.isBlank() ?
                        String.format("Create system function '%s' under %s", name, parentName) :
                        summarizeCreate("System function", name, layer);
                case CREATE_INTERFACE:
                    return summarizeExchange("System interface", name, sourceName, targetName, layer);
                case ALLOCATE_CAPABILITY:
                    return String.format("Allocate capability '%s' to system component '%s'", defaultString(capabilityName, "?"), defaultString(existingName, "?"));
                case PROPAGATE_LOGICAL_TO_PHYSICAL:
                    return String.format("Propagate logical component '%s' into %s%s",
                            defaultString(logicalName, "?"),
                            safeLayer(defaultString(targetLayer, defaultString(layer, "PA"))),
                            includeChildren ? " (with hierarchy)" : "");
                case SYNC_PHYSICAL_FROM_LOGICAL:
                    return String.format("Sync physical component '%s' from logical '%s'%s",
                            defaultString(physicalName, "?"),
                            defaultString(logicalName, "?"),
                            updateAttributes ? "" : " (link only)");
                case TRACE_COMPONENT_MAPPING:
                    return String.format("Trace logical '%s' to physical '%s'", defaultString(logicalName, "?"), defaultString(physicalName, "?"));
                case OPEN_DIAGRAM:
                    return String.format("Open %s diagram (%s)", defaultString(diagramType, "LAB"), defaultString(name, "auto"));
                case RENAME:
                    return String.format("Rename %s '%s' → '%s'", defaultString(elementClass, "element"), defaultString(existingName, "?"), defaultString(newName, "?"));
                case DELETE:
                    return String.format("Delete %s '%s'", defaultString(elementClass, "element"), defaultString(existingName, "?"));
                case CREATE_FUNCTIONAL_CHAIN:
                    return String.format("Create functional chain '%s' in %s", defaultString(name, "(auto)"), safeLayer(layer));
                case EXTEND_CHAIN:
                    return String.format("Extend chain '%s' with function '%s'", defaultString(chainName, "?"), defaultString(functionName, "?"));
                case SEQUENCE_EXCHANGE:
                    return String.format("Add exchange '%s' to chain '%s'", defaultString(exchangeName, "?"), defaultString(chainName, "?"));
                case ATTACH_SCENARIO:
                    return String.format("Attach scenario '%s' to chain '%s'", defaultString(scenarioName, "?"), defaultString(chainName, "?"));
                case CREATE_REQUIREMENT:
                    String containerLabel = (containerName != null && !containerName.isBlank()) ? containerName
                            : defaultString(containerClass, "requirements container");
                    return String.format("Create requirement '%s' in %s", defaultString(name, "(auto)"), containerLabel);
                case LINK_REQUIREMENT:
                    return String.format("Link requirement '%s' to %s '%s'", defaultString(name, "?"),
                            defaultString(requirementTargetClass, "element"), defaultString(targetName, "?"));
                case RECORD_REQUIREMENT_COVERAGE:
                    return String.format("Record coverage for %s '%s' (%s)",
                            defaultString(requirementTargetClass, "element"), defaultString(targetName, "?"),
                            defaultString(requirementStatus, "status"));
                default:
                    return describe();
            }
        }

        private String summarizeCreate(String kind, String valueName, String layerName) {
            return String.format("Create %s '%s' in %s", kind, defaultString(valueName, "(auto)"), safeLayer(layerName));
        }

        private String summarizeExchange(String kind, String valueName, String src, String tgt, String layerName) {
            return String.format("Create %s '%s' (%s → %s) in %s", kind, defaultString(valueName, kind),
                    defaultString(src, "?"), defaultString(tgt, "?"), safeLayer(layerName));
        }

        private String safeLayer(String layerName) {
            return defaultString(layerName, "LA");
        }

        private String defaultString(String value, String fallback) {
            return (value == null || value.isBlank()) ? fallback : value;
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

    private static boolean bool(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        String str = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (str.isEmpty()) {
            return defaultValue;
        }
        return "true".equals(str) || "1".equals(str) || "yes".equals(str) || "y".equals(str);
    }

    private static final class PlanValidationException extends RuntimeException {
        PlanValidationException(String message) {
            super(message);
        }
    }
}
