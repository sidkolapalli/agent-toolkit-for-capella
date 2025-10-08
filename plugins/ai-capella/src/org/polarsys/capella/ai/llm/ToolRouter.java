package org.polarsys.capella.ai.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.viewpoint.DRepresentation;
import org.polarsys.capella.ai.model.ArchitectureLocator;
import org.polarsys.capella.ai.model.ElementFactory;
import org.polarsys.capella.ai.model.Finders;
import org.polarsys.capella.ai.model.ModelOps;
import org.polarsys.capella.ai.actions.FunctionalChainActions;
import org.polarsys.capella.ai.actions.MassEditActions;
import org.polarsys.capella.ai.actions.MassEditActions.MappingItem;
import org.polarsys.capella.ai.actions.MassEditActions.RenameItem;
import org.polarsys.capella.ai.actions.OperationalActions;
import org.polarsys.capella.ai.actions.RequirementActions;
import org.polarsys.capella.ai.actions.SystemActions;
import org.polarsys.capella.ai.actions.TransformationActions;
import org.polarsys.capella.ai.model.ModelSummary;
import org.polarsys.capella.ai.tools.DiagramCommands;
import org.polarsys.capella.ai.tools.DiagramMappings;
import org.polarsys.capella.ai.session.SessionSafeguards;
import org.polarsys.capella.ai.telemetry.Telemetry;

/**
 * Parses TOOL_PLAN responses and applies requested actions against the active Capella session.
 */
public final class ToolRouter {

    private ToolRouter() {}

    public static final class Result {
        public String reply;
        public ToolPlan plan;
        public String error;
        public List<String> validationIssues = List.of();
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

        public List<RenameItem> getRenameItems() {
            return action.renameItems;
        }

        public List<String> getItemNames() {
            return action.itemNames;
        }

        public List<String> getSecondaryNames() {
            return action.secondaryNames;
        }

        public List<MappingItem> getMappingItems() {
            return action.mappingItems;
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

    public interface StreamingConsumer {
        void onPartial(String text);
        void onComplete(Result result);
        void onError(String message);
        void onCancelled();
        boolean isCancelled();
    }

    public static final class ToolStream {
        private final LLMService.StreamHandle handle;
        private final CompletableFuture<Result> completion;

        ToolStream(LLMService.StreamHandle handle, CompletableFuture<Result> completion) {
            this.handle = handle;
            this.completion = completion;
        }

        public void cancel() {
            if (handle != null) {
                handle.cancel();
            }
        }

        public CompletableFuture<Result> completion() {
            return completion;
        }
    }

    public static Result handleChat(Session session, String userMessage) {
        return handleChat(session, userMessage, List.of());
    }

    public static Result handleChat(Session session, String userMessage, List<LLMService.Message> history) {
        String corr = UUID.randomUUID().toString();
        Telemetry.setCorrelationId(corr);
        Result result = new Result();
        try {
            if (session == null) {
                throw new IllegalStateException("No active Capella session");
            }
            List<LLMService.Message> messages = buildMessages(session, userMessage, history);
            String reply = LLMService.get().send(messages);
            result = parseReply(session, reply);
        } catch (PlanValidationException ex) {
            result.error = ex.getMessage();
        } catch (Exception ex) {
            result.error = ex.getMessage();
        } finally {
            Telemetry.clearCorrelationId();
        }
        return result;
    }

    public static ToolStream handleChatStreaming(Session session, String userMessage, List<LLMService.Message> history, StreamingConsumer consumer) {
        if (consumer == null) {
            throw new IllegalArgumentException("Streaming consumer required");
        }
        if (session == null) {
            consumer.onError("No active Capella session");
            CompletableFuture<Result> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("No active Capella session"));
            return new ToolStream(null, failed);
        }
        String corr = UUID.randomUUID().toString();
        Telemetry.setCorrelationId(corr);
        List<LLMService.Message> messages = buildMessages(session, userMessage, history);
        final StringBuilder aggregate = new StringBuilder();
        LLMService.StreamHandle handle;
        try {
            handle = LLMService.get().stream(messages, new LLMService.StreamListener() {
                @Override
                public boolean isCancelled() {
                    return consumer.isCancelled();
                }

                @Override
                public void onChunk(String text) {
                    if (text == null || text.isEmpty()) {
                        return;
                    }
                    aggregate.append(text);
                    consumer.onPartial(text);
                }
            });
        } catch (Exception ex) {
            consumer.onError(ex.getMessage());
            CompletableFuture<Result> failed = new CompletableFuture<>();
            failed.completeExceptionally(ex);
            Telemetry.clearCorrelationId();
            return new ToolStream(null, failed);
        }

        CompletableFuture<Result> completion = new CompletableFuture<>();
        handle.completion().whenComplete((reply, throwable) -> {
            if (throwable != null) {
                Throwable cause = throwable instanceof CancellationException ? throwable : throwable.getCause();
                if (cause instanceof CancellationException) {
                    consumer.onCancelled();
                } else {
                    String message = throwable.getMessage() != null ? throwable.getMessage() : throwable.toString();
                    consumer.onError(message);
                }
                completion.completeExceptionally(throwable);
                Telemetry.clearCorrelationId();
                return;
            }
            String finalReply = reply != null ? reply : aggregate.toString();
            try {
                Result parsed = parseReply(session, finalReply);
                consumer.onComplete(parsed);
                completion.complete(parsed);
            } catch (PlanValidationException ex) {
                consumer.onError(ex.getMessage());
                completion.completeExceptionally(ex);
            } finally {
                Telemetry.clearCorrelationId();
            }
        });
        return new ToolStream(handle, completion);
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
        List<Action> actions = plan.actions();
        for (int i = 0; i < actions.size(); i++) {
            Action action = actions.get(i);
            try {
                Telemetry.recordToolCall(i, action.type.name(), action.describe(), "attempt", "");
                boolean success = applyAction(session, action);
                if (success) {
                    applied++;
                    Telemetry.recordToolCall(i, action.type.name(), action.describe(), "success", "");
                } else {
                    String msg = "Failed: " + action.describe();
                    errors.add(msg);
                    Telemetry.recordToolCall(i, action.type.name(), action.describe(), "failure", msg);
                }
            } catch (Exception ex) {
                String msg = action.describe() + " → " + ex.getMessage();
                errors.add(msg);
                Telemetry.recordToolCall(i, action.type.name(), action.describe(), "error", msg);
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
            case ADD_VIEW_NODE: {
                DRepresentation rep = DiagramCommands.getLastOpened();
                if (rep == null) return false;
                EObject sem = findAnyByName(session, action.existingName);
                if (sem == null) return false;
                return DiagramCommands.createNode(session, rep, sem);
            }
            case ADD_VIEW_EDGE: {
                DRepresentation rep = DiagramCommands.getLastOpened();
                if (rep == null) return false;
                EObject src = findAnyByName(session, action.sourceName);
                EObject tgt = findAnyByName(session, action.targetName);
                if (src == null || tgt == null) return false;
                return DiagramCommands.createEdge(session, rep, src, tgt);
            }
            case SET_VIEW_BOUNDS: {
                DRepresentation rep = DiagramCommands.getLastOpened();
                if (rep == null) return false;
                EObject sem = findAnyByName(session, action.existingName);
                if (sem == null) return false;
                return DiagramCommands.setViewBounds(session, rep, sem, action.x, action.y, action.width, action.height);
            }
            case REMOVE_VIEW_ELEMENT: {
                DRepresentation rep = DiagramCommands.getLastOpened();
                if (rep == null) return false;
                EObject sem = findAnyByName(session, action.existingName);
                if (sem == null) return false;
                return DiagramCommands.removeViewElement(session, rep, sem);
            }
            case BATCH_RENAME:
                return MassEditActions.batchRename(session, action.elementClass, action.renameItems);
            case BATCH_REPARENT:
                return MassEditActions.batchReparent(session, action.elementClass, action.parentName, action.itemNames);
            case BATCH_ALLOCATE:
                return MassEditActions.batchAllocateCapability(session, action.capabilityName, action.itemNames);
            case BATCH_MAP:
                return MassEditActions.batchMap(session, action.elementClass, action.containerClass, action.mappingItems);
            case BATCH_DELETE:
                return MassEditActions.batchDelete(session, action.elementClass, action.itemNames);
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

    private static List<LLMService.Message> buildMessages(Session session, String user, List<LLMService.Message> history) {
        List<LLMService.Message> messages = new ArrayList<>();
        messages.add(LLMService.Message.system(buildSystemPrompt(session)));
        List<LLMService.Message> trimmed = trimHistory(history);
        messages.addAll(trimmed);
        messages.add(LLMService.Message.user(user));
        return messages;
    }

    private static List<LLMService.Message> trimHistory(List<LLMService.Message> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        int limit = 12;
        if (history.size() <= limit) {
            return new ArrayList<>(history);
        }
        return new ArrayList<>(history.subList(history.size() - limit, history.size()));
    }

    private static String buildSystemPrompt(Session session) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are the Capella AI copilot embedded inside the modelling tool.\n");
        sb.append("Answer clearly. When structural changes are needed emit exactly one line beginning with 'TOOL_PLAN:' followed by compact JSON.\n");
        sb.append("If no model change is required, reply naturally without a TOOL_PLAN line.\n");
        sb.append("Schema: {\"actions\":[{...}]} with snake_case field names. Supported action types include:\n");
        sb.append("- create_component {layer, name}\n");
        sb.append("- create_function {layer, name}\n");
        sb.append("- create_component_exchange {layer, name?, source_name, target_name}\n");
        sb.append("- create_functional_exchange {layer, name?, source_function, target_function}\n");
        sb.append("- create_lab {layer}\n");
        sb.append("- open_diagram {layer, type, name?}\n");
        sb.append("- rename {class, name, new_name}\n");
        sb.append("- delete {class, name}\n");
        sb.append("- create_mission {layer?, name, description?}\n");
        sb.append("- create_operational_activity {layer?, name, parent_name?}\n");
        sb.append("- create_operational_exchange {layer?, name?, source_actor, target_actor}\n");
        sb.append("- link_operational_to_capability {operational_activity, capability}\n");
        sb.append("- create_system_component {layer?, name, parent_name?}\n");
        sb.append("- create_system_function {layer?, name, parent_name?}\n");
        sb.append("- create_interface {layer?, name?, source_component, target_component}\n");
        sb.append("- allocate_capability {component, capability}\n");
        sb.append("- propagate_logical_to_physical {logical_component, target_layer?, include_children?}\n");
        sb.append("- sync_physical_from_logical {logical_component, physical_component, update_attributes?, include_children?}\n");
        sb.append("- trace_component_mapping {logical_component, physical_component}\n");
        sb.append("- create_functional_chain {layer?, name, description?}\n");
        sb.append("- extend_chain {chain, function, function_class?}\n");
        sb.append("- sequence_exchange {chain, exchange}\n");
        sb.append("- attach_scenario {chain, scenario}\n");
        sb.append("- batch_rename {class, renames:[{from,to}]}\n");
        sb.append("- batch_reparent {class, parent_name, items:[]}\n");
        sb.append("- batch_allocate {capability, components:[]}\n");
        sb.append("- batch_map {source_class, target_class, pairs:[{source,target}]}\n");
        sb.append("- batch_delete {class, items:[]}\n");
        sb.append("- create_requirement {name, container_class?, container_name?, requirement_type?, description?}\n");
        sb.append("- link_requirement {requirement, target_class, target_name}\n");
        sb.append("- record_requirement_coverage {target_class, target_name, status?, notes?}\n");
        sb.append("Cross-check references against the live model and reuse existing artefacts when possible.\n");
        sb.append("- add_view_node {diagram?, semantic, x?, y?}\n");
        sb.append("- add_view_edge {diagram?, source, target}\n");
        sb.append("Acknowledge validation warnings from dry-run simulations.\n");
        sb.append("Current model snapshot:\n");
        try {
            sb.append(ModelSummary.summarize(session));
        } catch (Exception ex) {
            sb.append("(summary unavailable)\n");
        }
        return sb.toString();
    }

    private static Result parseReply(Session session, String reply) throws PlanValidationException {
        Result result = new Result();
        result.reply = reply;
        String planJson = extractPlan(reply);
        if (planJson != null && !planJson.isBlank()) {
            List<Action> actions = parseActions(planJson);
            result.plan = actions.isEmpty() ? null : new ToolPlan(actions);
            if (result.plan != null) {
                result.validationIssues = validatePlan(session, result.plan);
            }
        }
        return result;
    }

    private static List<String> validatePlan(Session session, ToolPlan plan) {
        List<String> issues = new ArrayList<>();
        if (session == null || session.getTransactionalEditingDomain() == null) {
            issues.add("No active session for validation");
            return issues;
        }
        for (PlanItem item : plan.items()) {
            ActionType t = ActionType.from(item.getTypeId().toLowerCase(Locale.ROOT));
            switch (t) {
                case RENAME:
                case DELETE: {
                    String cls = item.getElementClass();
                    String name = item.getExistingName();
                    if (find(session, cls, name) == null) {
                        issues.add(label(item) + ": element not found: " + safe(name) + " (" + safe(cls) + ")");
                    }
                    break;
                }
                case CREATE_COMPONENT_EXCHANGE: {
                    if (find(session, "LogicalComponent", item.getSourceName()) == null) {
                        issues.add(label(item) + ": source component not found: " + safe(item.getSourceName()));
                    }
                    if (find(session, "LogicalComponent", item.getTargetName()) == null) {
                        issues.add(label(item) + ": target component not found: " + safe(item.getTargetName()));
                    }
                    break;
                }
                case CREATE_FUNCTIONAL_EXCHANGE: {
                    if (find(session, "LogicalFunction", item.getSourceName()) == null) {
                        issues.add(label(item) + ": source function not found: " + safe(item.getSourceName()));
                    }
                    if (find(session, "LogicalFunction", item.getTargetName()) == null) {
                        issues.add(label(item) + ": target function not found: " + safe(item.getTargetName()));
                    }
                    break;
                }
                case TRACE_COMPONENT_MAPPING: {
                    if (find(session, "LogicalComponent", item.getLogicalName()) == null) {
                        issues.add(label(item) + ": logical component not found: " + safe(item.getLogicalName()));
                    }
                    if (find(session, "PhysicalComponent", item.getPhysicalName()) == null) {
                        issues.add(label(item) + ": physical component not found: " + safe(item.getPhysicalName()));
                    }
                    break;
                }
                case CREATE_SYSTEM_COMPONENT:
                case CREATE_SYSTEM_FUNCTION: {
                    if (item.getParentName() != null && !item.getParentName().isBlank()) {
                        String cls = t == ActionType.CREATE_SYSTEM_COMPONENT ? "SystemComponent" : "SystemFunction";
                        if (find(session, cls, item.getParentName()) == null) {
                            issues.add(label(item) + ": parent not found: " + safe(item.getParentName()));
                        }
                    }
                    break;
                }
                case CREATE_INTERFACE: {
                    if (find(session, "SystemComponent", item.getSourceName()) == null) {
                        issues.add(label(item) + ": source component not found: " + safe(item.getSourceName()));
                    }
                    if (find(session, "SystemComponent", item.getTargetName()) == null) {
                        issues.add(label(item) + ": target component not found: " + safe(item.getTargetName()));
                    }
                    break;
                }
                case LINK_REQUIREMENT:
                case RECORD_REQUIREMENT_COVERAGE: {
                    String targetCls = item.getRequirementTargetClass();
                    String targetName = item.getTargetName();
                    if (find(session, targetCls, targetName) == null) {
                        issues.add(label(item) + ": target not found: " + safe(targetName) + " (" + safe(targetCls) + ")");
                    }
                    break;
                }
                default:
                    break;
            }
        }
        return issues;
    }

    private static EObject find(Session session, String simpleClass, String name) {
        if (simpleClass == null || name == null || name.isBlank()) return null;
        return Finders.findByName(session, simpleClass, name);
    }

    private static String label(PlanItem item) {
        return String.format("Step %d", item.getIndex() + 1);
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

    private static EObject findAnyByName(Session session, String name) {
        if (name == null || name.isBlank()) return null;
        String[] classes = new String[] {
                "OperationalActivity", "OperationalActor",
                "SystemComponent", "LogicalComponent", "PhysicalComponent",
                "SystemFunction", "LogicalFunction", "PhysicalFunction",
                "Capability", "Interface",
                "FunctionalExchange", "ComponentExchange"
        };
        for (String cls : classes) {
            EObject found = Finders.findByName(session, cls, name);
            if (found != null) return found;
        }
        return null;
    }

        private static String canonicalLayer(String v) {
            if (v == null || v.isBlank()) return v;
            String s = v.trim().toLowerCase(java.util.Locale.ROOT);
            if (s.equals("oa") || s.equals("operational") || s.equals("oal")) return "OA";
            if (s.equals("la") || s.equals("logical")) return "LA";
            if (s.equals("sa") || s.equals("system") || s.equals("systemanalysis")) return "SA";
            if (s.equals("pa") || s.equals("physical")) return "PA";
            return v; // leave unchanged if already a recognized code or custom
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
        ADD_VIEW_NODE,
        ADD_VIEW_EDGE,
        SET_VIEW_BOUNDS,
        REMOVE_VIEW_ELEMENT,
        BATCH_RENAME,
        BATCH_REPARENT,
        BATCH_ALLOCATE,
        BATCH_MAP,
        BATCH_DELETE,
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
                case "add_view_node":
                    return ADD_VIEW_NODE;
                case "add_view_edge":
                    return ADD_VIEW_EDGE;
                case "set_view_bounds":
                    return SET_VIEW_BOUNDS;
                case "remove_view_element":
                    return REMOVE_VIEW_ELEMENT;
                case "batch_rename":
                    return BATCH_RENAME;
                case "batch_reparent":
                    return BATCH_REPARENT;
                case "batch_allocate":
                    return BATCH_ALLOCATE;
                case "batch_map":
                    return BATCH_MAP;
                case "batch_delete":
                    return BATCH_DELETE;
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
        List<RenameItem> renameItems = List.of();
        List<String> itemNames = List.of();
        List<String> secondaryNames = List.of();
        List<MappingItem> mappingItems = List.of();

        Action(ActionType type, String layer, String name, String sourceName, String targetName,
               String elementClass, String existingName, String newName, String diagramType,
               String parentName, String description, String capabilityName,
               String logicalName, String physicalName, String targetLayer,
               boolean includeChildren, boolean updateAttributes,
               String chainName, String functionName, String functionClass,
               String exchangeName, String scenarioName) {
            this.type = type;
            this.layer = canonicalLayer(layer);
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
            this.targetLayer = canonicalLayer(targetLayer);
            this.includeChildren = includeChildren;
            this.updateAttributes = updateAttributes;
            this.chainName = chainName;
            this.functionName = functionName;
            this.functionClass = functionClass;
            this.exchangeName = exchangeName;
            this.scenarioName = scenarioName;
        }

        // View geometry fields
        int x; int y; int width; int height;

        static Action fromMap(int index, Map<String, Object> map) {
            // Tolerate both shapes:
            // 1) {"type":"create_operational_activity", ...}
            // 2) {"create_operational_activity": { ... }}
            String typeValue = string(map.get("type"));
            if (typeValue == null) {
                if (map.size() == 1) {
                    Map.Entry<String, Object> only = map.entrySet().iterator().next();
                    String inferred = only.getKey();
                    Object val = only.getValue();
                    if (val instanceof Map) {
                        @SuppressWarnings("unchecked") Map<String, Object> inner = (Map<String, Object>) val;
                        // Merge inner into map and set explicit type
                        map = new java.util.LinkedHashMap<>(inner);
                        map.put("type", inferred);
                        typeValue = inferred;
                    }
                }
            }
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
                case ADD_VIEW_NODE:
                    // view-only: diagram? optional; semantic required
                    return new Action(type, stringOrDefault(map.get("layer"), ""), string(map.get("diagram")), null, null,
                            null, require(map, "semantic", index), null, null, null, null, null,
                            null, null, null, false, false, null, null, null, null, null);
                case ADD_VIEW_EDGE:
                    // view-only: diagram? optional; source/target required
                    return new Action(type, stringOrDefault(map.get("layer"), ""), string(map.get("diagram")), require(map, "source", index), require(map, "target", index),
                            null, null, null, null, null, null, null,
                            null, null, null, false, false, null, null, null, null, null);
                case SET_VIEW_BOUNDS: {
                    Action a = new Action(type, stringOrDefault(map.get("layer"), ""), string(map.get("diagram")), null, null,
                            null, require(map, "element", index), null, null, null, null, null,
                            null, null, null, false, false, null, null, null, null, null);
                    a.x = parseInt(map.get("x"));
                    a.y = parseInt(map.get("y"));
                    a.width = parseInt(map.get("width"));
                    a.height = parseInt(map.get("height"));
                    return a;
                }
                case REMOVE_VIEW_ELEMENT:
                    return new Action(type, stringOrDefault(map.get("layer"), ""), string(map.get("diagram")), null, null,
                            null, require(map, "element", index), null, null, null, null, null,
                            null, null, null, false, false, null, null, null, null, null);
                case BATCH_RENAME: {
                    Action renameAction = new Action(type, stringOrDefault(map.get("layer"), "LA"), null, null, null,
                            require(map, "class", index), null, null, null, null, null, null,
                            null, null, null, false, false,
                            null, null, null, null, null);
                    renameAction.renameItems = parseRenameItems(map, index);
                    return renameAction;
                }
                case BATCH_REPARENT: {
                    Action reparentAction = new Action(type, stringOrDefault(map.get("layer"), "LA"), null, null, null,
                            require(map, "class", index), null, null, null, string(map.get("parent")), null, null,
                            null, null, null, false, false,
                            null, null, null, null, null);
                    reparentAction.itemNames = parseStringList(map.get("items"), index, "items");
                    return reparentAction;
                }
                case BATCH_ALLOCATE: {
                    Action allocateAction = new Action(type, stringOrDefault(map.get("layer"), "SA"), null, null, null,
                            null, null, null, null, null, null, require(map, "capability", index),
                            null, null, null, false, false,
                            null, null, null, null, null);
                    allocateAction.itemNames = parseStringList(map.get("components"), index, "components");
                    return allocateAction;
                }
                case BATCH_MAP: {
                    Action mapAction = new Action(type, stringOrDefault(map.get("layer"), "LA"), null, null, null,
                            require(map, "source_class", index), null, null, null, null, null, null,
                            null, null, null, false, false,
                            null, null, null, null, null);
                    mapAction.containerClass = require(map, "target_class", index);
                    mapAction.mappingItems = parseMappingItems(map.get("pairs"), index);
                    return mapAction;
                }
                case BATCH_DELETE: {
                    Action deleteAction = new Action(type, stringOrDefault(map.get("layer"), "LA"), null, null, null,
                            require(map, "class", index), null, null, null, null, null, null,
                            null, null, null, false, false,
                            null, null, null, null, null);
                    deleteAction.itemNames = parseStringList(map.get("items"), index, "items");
                    return deleteAction;
                }
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

        private static int parseInt(Object v) {
            if (v == null) return 0;
            if (v instanceof Number) return ((Number) v).intValue();
            try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return 0; }
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
                case ADD_VIEW_NODE:
                    return String.format("Add view node for '%s'%s", defaultString(existingName, "?"),
                            (name != null && !name.isBlank()) ? (" in diagram '" + name + "'") : "");
                case ADD_VIEW_EDGE:
                    return String.format("Add view edge %s → %s%s", defaultString(sourceName, "?"), defaultString(targetName, "?"),
                            (name != null && !name.isBlank()) ? (" in diagram '" + name + "'") : "");
                case SET_VIEW_BOUNDS:
                    return String.format("Set bounds for '%s' to (%d,%d %dx%d)%s",
                            defaultString(existingName, "?"), x, y, width, height,
                            (name != null && !name.isBlank()) ? (" in diagram '" + name + "'") : "");
                case REMOVE_VIEW_ELEMENT:
                    return String.format("Remove view element '%s'%s", defaultString(existingName, "?"),
                            (name != null && !name.isBlank()) ? (" from diagram '" + name + "'") : "");
                case BATCH_RENAME:
                    return String.format("Batch rename %d %s", renameItems != null ? renameItems.size() : 0,
                            defaultString(elementClass, "elements"));
                case BATCH_REPARENT:
                    return String.format("Move %d %s under %s",
                            itemNames != null ? itemNames.size() : 0,
                            defaultString(elementClass, "elements"), defaultString(parentName, "(parent)"));
                case BATCH_ALLOCATE:
                    return String.format("Allocate capability '%s' to %d component(s)", defaultString(capabilityName, "?"),
                            itemNames != null ? itemNames.size() : 0);
                case BATCH_MAP:
                    return String.format("Map %d %s → %s",
                            mappingItems != null ? mappingItems.size() : 0,
                            defaultString(elementClass, "sources"), defaultString(containerClass, "targets"));
                case BATCH_DELETE:
                    return String.format("Batch delete %d %s", itemNames != null ? itemNames.size() : 0,
                            defaultString(elementClass, "elements"));
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
                case BATCH_RENAME:
                case BATCH_REPARENT:
                case BATCH_ALLOCATE:
                case BATCH_MAP:
                case LINK_REQUIREMENT:
                case RECORD_REQUIREMENT_COVERAGE:
                    return ChangeCategory.UPDATE;
                case DELETE:
                case BATCH_DELETE:
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
                case ADD_VIEW_NODE:
                    return String.format("Reveal node '%s' in diagram", defaultString(existingName, "?"));
                case ADD_VIEW_EDGE:
                    return String.format("Reveal edge %s → %s in diagram", defaultString(sourceName, "?"), defaultString(targetName, "?"));
                case SET_VIEW_BOUNDS:
                    return String.format("Resize/move view element '%s'", defaultString(existingName, "?"));
                case REMOVE_VIEW_ELEMENT:
                    return String.format("Remove view element '%s'", defaultString(existingName, "?"));
                case BATCH_RENAME:
                    return String.format("Rename %d %s", renameItems != null ? renameItems.size() : 0,
                            defaultString(elementClass, "elements"));
                case BATCH_REPARENT:
                    return String.format("Move %d %s under %s",
                            itemNames != null ? itemNames.size() : 0,
                            defaultString(elementClass, "elements"), defaultString(parentName, "(parent)"));
                case BATCH_ALLOCATE:
                    return String.format("Allocate capability '%s' to %d component(s)", defaultString(capabilityName, "?"),
                            itemNames != null ? itemNames.size() : 0);
                case BATCH_MAP:
                    return String.format("Link %d %s → %s",
                            mappingItems != null ? mappingItems.size() : 0,
                            defaultString(elementClass, "sources"), defaultString(containerClass, "targets"));
                case BATCH_DELETE:
                    return String.format("Delete %d %s", itemNames != null ? itemNames.size() : 0,
                            defaultString(elementClass, "elements"));
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

    private static String safe(String s) { return s == null ? "" : s; }

    private static List<RenameItem> parseRenameItems(Map<String, Object> map, int index) {
        Object raw = map.get("items");
        if (!(raw instanceof List<?> list)) {
            throw new PlanValidationException("Action " + index + " missing 'items' array");
        }
        if (list.isEmpty()) {
            throw new PlanValidationException("Action " + index + " requires at least one rename item");
        }
        List<RenameItem> result = new ArrayList<>(list.size());
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> entryMap)) {
                throw new PlanValidationException("Rename item must be an object");
            }
            String from = string(entryMap.get("from"));
            String to = string(entryMap.get("to"));
            if (from == null || from.isBlank() || to == null) {
                throw new PlanValidationException("Rename items require 'from' and 'to'");
            }
            result.add(new RenameItem(from, to));
        }
        return List.copyOf(result);
    }

    private static List<String> parseStringList(Object raw, int index, String field) {
        if (!(raw instanceof List<?> list)) {
            throw new PlanValidationException("Action " + index + " missing array '" + field + "'");
        }
        if (list.isEmpty()) {
            throw new PlanValidationException("Action " + index + " requires values in '" + field + "'");
        }
        List<String> values = new ArrayList<>(list.size());
        for (Object entry : list) {
            String value = string(entry);
            if (value == null || value.isBlank()) {
                continue;
            }
            values.add(value);
        }
        if (values.isEmpty()) {
            throw new PlanValidationException("Action " + index + " contains empty entries in '" + field + "'");
        }
        return List.copyOf(values);
    }

    private static List<MappingItem> parseMappingItems(Object raw, int index) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new PlanValidationException("Action " + index + " missing mapping pairs");
        }
        List<MappingItem> pairs = new ArrayList<>(list.size());
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> mapEntry)) {
                throw new PlanValidationException("Mapping item must be an object");
            }
            String source = string(mapEntry.get("source"));
            String target = string(mapEntry.get("target"));
            if (source == null || source.isBlank() || target == null || target.isBlank()) {
                throw new PlanValidationException("Mapping items require 'source' and 'target'");
            }
            pairs.add(new MappingItem(source, target));
        }
        return List.copyOf(pairs);
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
