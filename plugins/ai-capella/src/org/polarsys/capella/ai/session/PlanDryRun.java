package org.polarsys.capella.ai.session;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.llm.ToolRouter.PlanItem;
import org.polarsys.capella.ai.llm.ToolRouter.ToolPlan;
import org.polarsys.capella.ai.model.ArchitectureLocator;
import org.polarsys.capella.ai.model.ElementFactory;
import org.polarsys.capella.ai.model.Finders;

/**
 * Executes TOOL_PLAN steps against an in-memory clone of the session to surface issues before committing.
 */
public final class PlanDryRun {
    private PlanDryRun() {}

    public static DryRunResult evaluate(Session session, ToolPlan plan) {
        if (plan == null || plan.isEmpty()) {
            return DryRunResult.unavailable("No actions to simulate.");
        }
        Optional<ScenarioWorkspace> workspaceOpt = ScenarioWorkspace.create(session);
        if (workspaceOpt.isEmpty()) {
            return DryRunResult.unavailable("Scenario workspace unavailable (no active session or cloning failed).");
        }
        ResourceSet sandbox = workspaceOpt.get().getResourceSet();
        Map<Integer, Outcome> outcomes = new LinkedHashMap<>();
        List<String> issues = new ArrayList<>();
        for (PlanItem item : plan.items()) {
            Outcome outcome = simulateAction(sandbox, item);
            outcomes.put(item.getIndex(), outcome);
            if (!outcome.isSuccess()) {
                issues.add(String.format("Step %d (%s): %s", item.getIndex() + 1, item.getTypeLabel(), outcome.getMessage()));
            }
        }
        return new DryRunResult(true, outcomes, issues);
    }

    private static Outcome simulateAction(ResourceSet sandbox, PlanItem item) {
        String typeId = item.getTypeId();
        switch (typeId) {
            case "CREATE_COMPONENT":
                return simulateCreate(sandbox, item, "LogicalComponent");
            case "CREATE_FUNCTION":
                return simulateCreate(sandbox, item, "LogicalFunction");
            case "CREATE_COMPONENT_EXCHANGE":
                return simulateExchange(sandbox, item, "ComponentExchange", item.getSourceName(), item.getTargetName());
            case "CREATE_FUNCTIONAL_EXCHANGE":
                return simulateExchange(sandbox, item, "FunctionalExchange", item.getSourceName(), item.getTargetName());
            case "CREATE_LAB":
                return Outcome.success("Would create LAB diagram in " + safeLayer(item.getLayer()));
            case "CREATE_MISSION":
                return simulateMission(sandbox, item);
            case "CREATE_OPERATIONAL_ACTIVITY":
                return simulateOperationalActivity(sandbox, item);
            case "CREATE_OPERATIONAL_EXCHANGE":
                return simulateOperationalExchange(sandbox, item);
            case "LINK_OPERATIONAL_TO_CAPABILITY":
                return simulateOperationalLink(sandbox, item);
            case "CREATE_SYSTEM_COMPONENT":
                return simulateSystemComponent(sandbox, item);
            case "CREATE_SYSTEM_FUNCTION":
                return simulateSystemFunction(sandbox, item);
            case "CREATE_INTERFACE":
                return simulateSystemInterface(sandbox, item);
            case "ALLOCATE_CAPABILITY":
                return simulateCapabilityAllocation(sandbox, item);
            case "PROPAGATE_LOGICAL_TO_PHYSICAL":
                return simulatePropagate(sandbox, item);
            case "SYNC_PHYSICAL_FROM_LOGICAL":
                return simulateSync(sandbox, item);
            case "TRACE_COMPONENT_MAPPING":
                return simulateTrace(sandbox, item);
            case "CREATE_FUNCTIONAL_CHAIN":
                return simulateCreateFunctionalChain(sandbox, item);
            case "EXTEND_CHAIN":
                return simulateExtendChain(sandbox, item);
            case "SEQUENCE_EXCHANGE":
                return simulateSequenceExchange(sandbox, item);
            case "ATTACH_SCENARIO":
                return simulateAttachScenario(sandbox, item);
            case "CREATE_REQUIREMENT":
                return simulateCreateRequirement(sandbox, item);
            case "LINK_REQUIREMENT":
                return simulateLinkRequirement(sandbox, item);
            case "RECORD_REQUIREMENT_COVERAGE":
                return simulateRequirementCoverage(sandbox, item);
            case "OPEN_DIAGRAM":
                return Outcome.success("Would open " + defaultString(item.getDiagramType(), "LAB") + " diagram");
            case "RENAME":
                return simulateRename(sandbox, item);
            case "DELETE":
                return simulateDelete(sandbox, item);
            default:
                return Outcome.failure("Unsupported action type in sandbox");
        }
    }

    private static Outcome simulateCreate(ResourceSet sandbox, PlanItem item, String simpleEClass) {
        EObject layerRoot = ArchitectureLocator.findTargetElement(sandbox, safeLayer(item.getLayer()));
        if (layerRoot == null) {
            return Outcome.failure("Layer " + safeLayer(item.getLayer()) + " not found");
        }
        EClass targetClass = ElementFactory.findEClass(simpleEClass);
        if (targetClass == null) {
            return Outcome.failure("EClass " + simpleEClass + " not registered");
        }
        EObject container = findBestContainer(layerRoot, targetClass);
        if (container == null) {
            container = layerRoot;
        }
        EReference containment = findContainmentFor(container, targetClass);
        if (containment == null) {
            return Outcome.failure("No containment for " + simpleEClass);
        }
        EObject created = targetClass.getEPackage().getEFactoryInstance().create(targetClass);
        EStructuralFeature nameFeat = created.eClass().getEStructuralFeature("name");
        if (nameFeat != null && nameFeat.isChangeable()) {
            created.eSet(nameFeat, defaultString(item.getName(), simpleEClass));
        }
        @SuppressWarnings("unchecked")
        List<EObject> list = (List<EObject>) container.eGet(containment);
        list.add(created);
        return Outcome.success(String.format("Create %s '%s' in %s", simpleEClass, defaultString(item.getName(), simpleEClass), safeLayer(item.getLayer())));
    }

    private static Outcome simulateExchange(ResourceSet sandbox, PlanItem item, String exchangeClass, String sourceName, String targetName) {
        EObject layerRoot = ArchitectureLocator.findTargetElement(sandbox, safeLayer(item.getLayer()));
        if (layerRoot == null) {
            return Outcome.failure("Layer " + safeLayer(item.getLayer()) + " not found");
        }
        EObject source = Finders.findByName(sandbox, elementClassForExchange(exchangeClass, true), sourceName);
        EObject target = Finders.findByName(sandbox, elementClassForExchange(exchangeClass, false), targetName);
        if (source == null || target == null) {
            return Outcome.failure("Source or target missing for exchange");
        }
        EClass exchangeEClass = ElementFactory.findEClass(exchangeClass);
        if (exchangeEClass == null) {
            return Outcome.failure(exchangeClass + " EClass not registered");
        }
        EObject container = findBestContainer(layerRoot, exchangeEClass);
        if (container == null) {
            container = layerRoot;
        }
        EReference containment = findContainmentFor(container, exchangeEClass);
        if (containment == null) {
            return Outcome.failure("No containment for " + exchangeClass);
        }
        EObject exchange = exchangeEClass.getEPackage().getEFactoryInstance().create(exchangeEClass);
        EStructuralFeature nameFeat = exchange.eClass().getEStructuralFeature("name");
        if (nameFeat != null && nameFeat.isChangeable()) {
            exchange.eSet(nameFeat, defaultString(item.getName(), exchangeClass));
        }
        setEndpoint(exchange, "source", source);
        setEndpoint(exchange, "target", target);
        @SuppressWarnings("unchecked")
        List<EObject> list = (List<EObject>) container.eGet(containment);
        list.add(exchange);
        return Outcome.success(String.format("Create %s '%s' (%s → %s)", exchangeClass, defaultString(item.getName(), exchangeClass), defaultString(sourceName, "?"), defaultString(targetName, "?")));
    }

    private static Outcome simulateMission(ResourceSet sandbox, PlanItem item) {
        String layer = safeLayer(item.getLayer(), "OA");
        if (item.getName() == null || item.getName().isBlank()) {
            return Outcome.failure("Mission name missing");
        }
        EObject root = ArchitectureLocator.findTargetElement(sandbox, layer);
        if (root == null) {
            return Outcome.failure("Layer " + layer + " not found");
        }
        return Outcome.success(String.format("Create mission '%s' in %s", item.getName(), layer));
    }

    private static Outcome simulateOperationalActivity(ResourceSet sandbox, PlanItem item) {
        String layer = safeLayer(item.getLayer(), "OA");
        if (item.getName() == null || item.getName().isBlank()) {
            return Outcome.failure("Operational activity name missing");
        }
        EObject container;
        if (item.getParentName() != null && !item.getParentName().isBlank()) {
            container = Finders.findByName(sandbox, "OperationalActivity", item.getParentName());
            if (container == null) {
                return Outcome.failure("Parent operational activity '" + item.getParentName() + "' not found");
            }
            return Outcome.success(String.format("Create operational activity '%s' under %s", item.getName(), item.getParentName()));
        }
        container = ArchitectureLocator.findTargetElement(sandbox, layer);
        if (container == null) {
            return Outcome.failure("Layer " + layer + " not found");
        }
        return Outcome.success(String.format("Create operational activity '%s' in %s", item.getName(), layer));
    }

    private static Outcome simulateOperationalExchange(ResourceSet sandbox, PlanItem item) {
        String src = defaultString(item.getSourceName(), "");
        String tgt = defaultString(item.getTargetName(), "");
        if (src.isBlank() || tgt.isBlank()) {
            return Outcome.failure("Operational exchange requires source and target actors");
        }
        EObject source = Finders.findByName(sandbox, "OperationalActor", src);
        EObject target = Finders.findByName(sandbox, "OperationalActor", tgt);
        if (source == null || target == null) {
            return Outcome.failure("Operational actors not found for exchange");
        }
        return Outcome.success(String.format("Create operational exchange '%s' between %s → %s",
                defaultString(item.getName(), "OperationalExchange"), src, tgt));
    }

    private static Outcome simulateOperationalLink(ResourceSet sandbox, PlanItem item) {
        String activity = defaultString(item.getExistingName(), "");
        String capability = defaultString(item.getCapabilityName(), "");
        if (activity.isBlank() || capability.isBlank()) {
            return Outcome.failure("Operational activity and capability required");
        }
        EObject activityObj = Finders.findByName(sandbox, "OperationalActivity", activity);
        EObject capabilityObj = Finders.findByName(sandbox, "Capability", capability);
        if (activityObj == null || capabilityObj == null) {
            return Outcome.failure("Operational activity or capability not found");
        }
        return Outcome.success(String.format("Link operational activity '%s' to capability '%s'", activity, capability));
    }

    private static Outcome simulateCreateFunctionalChain(ResourceSet sandbox, PlanItem item) {
        String name = defaultString(item.getName(), "");
        if (name.isBlank()) {
            return Outcome.failure("Functional chain name missing");
        }
        String layer = safeLayer(item.getLayer(), "LA");
        EObject root = ArchitectureLocator.findTargetElement(sandbox, layer);
        if (root == null) {
            return Outcome.failure("Layer " + layer + " not found");
        }
        return Outcome.success(String.format("Create functional chain '%s' in %s", name, layer));
    }

    private static Outcome simulateExtendChain(ResourceSet sandbox, PlanItem item) {
        String chainName = defaultString(item.getChainName(), "");
        String functionName = defaultString(item.getFunctionName(), "");
        if (chainName.isBlank() || functionName.isBlank()) {
            return Outcome.failure("Chain and function required");
        }
        EObject chain = Finders.findByName(sandbox, "FunctionalChain", chainName);
        if (chain == null) {
            return Outcome.failure("Functional chain '" + chainName + "' not found");
        }
        EObject function = findFunction(sandbox, functionName, item.getFunctionClass());
        if (function == null) {
            return Outcome.failure("Function '" + functionName + "' not found");
        }
        return Outcome.success(String.format("Extend chain '%s' with function '%s'", chainName, functionName));
    }

    private static Outcome simulateSequenceExchange(ResourceSet sandbox, PlanItem item) {
        String chainName = defaultString(item.getChainName(), "");
        String exchangeName = defaultString(item.getExchangeName(), "");
        if (chainName.isBlank() || exchangeName.isBlank()) {
            return Outcome.failure("Chain and exchange required");
        }
        EObject chain = Finders.findByName(sandbox, "FunctionalChain", chainName);
        if (chain == null) {
            return Outcome.failure("Functional chain '" + chainName + "' not found");
        }
        EObject exchange = Finders.findByName(sandbox, "FunctionalExchange", exchangeName);
        if (exchange == null) {
            return Outcome.failure("Functional exchange '" + exchangeName + "' not found");
        }
        return Outcome.success(String.format("Add exchange '%s' to chain '%s'", exchangeName, chainName));
    }

    private static Outcome simulateAttachScenario(ResourceSet sandbox, PlanItem item) {
        String chainName = defaultString(item.getChainName(), "");
        String scenarioName = defaultString(item.getScenarioName(), "");
        if (chainName.isBlank() || scenarioName.isBlank()) {
            return Outcome.failure("Chain and scenario required");
        }
        EObject chain = Finders.findByName(sandbox, "FunctionalChain", chainName);
        if (chain == null) {
            return Outcome.failure("Functional chain '" + chainName + "' not found");
        }
        EObject scenario = findScenario(sandbox, scenarioName);
        if (scenario == null) {
            return Outcome.failure("Scenario '" + scenarioName + "' not found");
        }
        return Outcome.success(String.format("Attach scenario '%s' to chain '%s'", scenarioName, chainName));
    }

    private static Outcome simulateCreateRequirement(ResourceSet sandbox, PlanItem item) {
        String reqName = defaultString(item.getName(), "");
        if (reqName.isBlank()) {
            return Outcome.failure("Requirement name missing");
        }
        EObject container = findRequirementContainer(sandbox, item.getContainerClass(), item.getContainerName());
        if (container == null) {
            return Outcome.failure("Requirement container not found");
        }
        String type = defaultString(item.getRequirementType(), "Requirement");
        EClass eClass = ElementFactory.findEClass(type);
        if (eClass == null) {
            return Outcome.failure("Requirement type '" + type + "' not recognised");
        }
        String label = (item.getContainerName() != null && !item.getContainerName().isBlank())
                ? item.getContainerName()
                : defaultString(item.getContainerClass(), "requirements");
        return Outcome.success(String.format("Create requirement '%s' in %s", reqName, label));
    }

    private static Outcome simulateLinkRequirement(ResourceSet sandbox, PlanItem item) {
        String reqName = defaultString(item.getName(), "");
        if (reqName.isBlank()) {
            return Outcome.failure("Requirement name missing");
        }
        EObject requirement = findRequirement(sandbox, reqName);
        if (requirement == null) {
            return Outcome.failure("Requirement '" + reqName + "' not found");
        }
        String targetClass = defaultString(item.getRequirementTargetClass(), "");
        String targetName = defaultString(item.getTargetName(), "");
        if (targetClass.isBlank() || targetName.isBlank()) {
            return Outcome.failure("Target class and name required");
        }
        EObject target = Finders.findByName(sandbox, targetClass, targetName);
        if (target == null) {
            return Outcome.failure("Target " + targetClass + " '" + targetName + "' not found");
        }
        return Outcome.success(String.format("Link requirement '%s' to %s '%s'", reqName, targetClass, targetName));
    }

    private static Outcome simulateRequirementCoverage(ResourceSet sandbox, PlanItem item) {
        String targetClass = defaultString(item.getRequirementTargetClass(), "");
        String targetName = defaultString(item.getTargetName(), "");
        if (targetClass.isBlank() || targetName.isBlank()) {
            return Outcome.failure("Target class and name required");
        }
        EObject target = Finders.findByName(sandbox, targetClass, targetName);
        if (target == null) {
            return Outcome.failure("Target " + targetClass + " '" + targetName + "' not found");
        }
        return Outcome.success(String.format("Record coverage for %s '%s' (%s)",
                targetClass, targetName, defaultString(item.getRequirementStatus(), "status")));
    }

    private static Outcome simulatePropagate(ResourceSet sandbox, PlanItem item) {
        String logicalName = defaultString(item.getLogicalName(), item.getExistingName());
        if (logicalName == null || logicalName.isBlank()) {
            return Outcome.failure("Logical component name missing");
        }
        EObject logical = Finders.findByName(sandbox, "LogicalComponent", logicalName);
        if (logical == null) {
            return Outcome.failure("Logical component '" + logicalName + "' not found");
        }
        String targetLayer = defaultString(item.getTargetLayer(), defaultString(item.getLayer(), "PA"));
        EObject physicalRoot = ArchitectureLocator.findTargetElement(sandbox, safeLayer(targetLayer));
        if (physicalRoot == null) {
            return Outcome.failure("Layer " + safeLayer(targetLayer) + " not found");
        }
        EClass physicalClass = ElementFactory.findEClass("PhysicalComponent");
        if (physicalClass == null) {
            return Outcome.failure("PhysicalComponent EClass not registered");
        }
        EObject container = findBestContainer(physicalRoot, physicalClass);
        if (container == null) {
            container = physicalRoot;
        }
        EReference containment = findContainmentFor(container, physicalClass);
        if (containment == null) {
            return Outcome.failure("No containment for PhysicalComponent in target layer");
        }
        String finalName = defaultString(readName(logical), logicalName);
        if (containsByName(container, containment, finalName)) {
            return Outcome.failure("Physical component '" + finalName + "' already exists");
        }
        EObject copy = createComponentCopy(logical, physicalClass, item.includeChildren());
        addChild(container, containment, copy);
        addTraceLink(logical, copy);
        String suffix = item.includeChildren() ? " (with hierarchy)" : "";
        return Outcome.success(String.format("Propagate logical component '%s' into %s%s", finalName, safeLayer(targetLayer), suffix));
    }

    private static Outcome simulateSync(ResourceSet sandbox, PlanItem item) {
        String logicalName = defaultString(item.getLogicalName(), "");
        if (logicalName.isBlank()) {
            return Outcome.failure("Logical component name missing");
        }
        EObject logical = Finders.findByName(sandbox, "LogicalComponent", logicalName);
        if (logical == null) {
            return Outcome.failure("Logical component '" + logicalName + "' not found");
        }
        String physicalName = defaultString(item.getPhysicalName(), "");
        if (physicalName.isBlank()) {
            return Outcome.failure("Physical component name missing");
        }
        EObject physical = Finders.findByName(sandbox, "PhysicalComponent", physicalName);
        if (physical == null) {
            return Outcome.failure("Physical component '" + physicalName + "' not found");
        }
        if (item.updateAttributes()) {
            copyScalarAttributes(logical, physical);
        }
        addTraceLink(logical, physical);
        return Outcome.success(String.format("Sync physical component '%s' from logical '%s'%s",
                physicalName,
                logicalName,
                item.updateAttributes() ? "" : " (link only)"));
    }

    private static Outcome simulateTrace(ResourceSet sandbox, PlanItem item) {
        String logicalName = defaultString(item.getLogicalName(), "");
        String physicalName = defaultString(item.getPhysicalName(), "");
        if (logicalName.isBlank() || physicalName.isBlank()) {
            return Outcome.failure("Logical or physical component missing");
        }
        EObject logical = Finders.findByName(sandbox, "LogicalComponent", logicalName);
        EObject physical = Finders.findByName(sandbox, "PhysicalComponent", physicalName);
        if (logical == null || physical == null) {
            return Outcome.failure("Components not found for trace");
        }
        addTraceLink(logical, physical);
        return Outcome.success(String.format("Trace logical '%s' to physical '%s'", logicalName, physicalName));
    }

    private static Outcome simulateRename(ResourceSet sandbox, PlanItem item) {
        String elementClass = defaultString(item.getElementClass(), "?");
        EObject target = Finders.findByName(sandbox, elementClass, item.getExistingName());
        if (target == null) {
            return Outcome.failure("Element " + elementClass + " '" + defaultString(item.getExistingName(), "?") + "' not found");
        }
        EStructuralFeature nameFeat = target.eClass().getEStructuralFeature("name");
        if (nameFeat == null || !nameFeat.isChangeable()) {
            return Outcome.failure("Element does not expose a writable name");
        }
        target.eSet(nameFeat, defaultString(item.getNewName(), item.getExistingName()));
        return Outcome.success(String.format("Rename %s '%s' → '%s'", elementClass, defaultString(item.getExistingName(), "?"), defaultString(item.getNewName(), "?")));
    }

    private static Outcome simulateDelete(ResourceSet sandbox, PlanItem item) {
        String elementClass = defaultString(item.getElementClass(), "?");
        EObject target = Finders.findByName(sandbox, elementClass, item.getExistingName());
        if (target == null) {
            return Outcome.failure("Element " + elementClass + " '" + defaultString(item.getExistingName(), "?") + "' not found");
        }
        EObject container = target.eContainer();
        if (container != null) {
            EStructuralFeature feat = target.eContainingFeature();
            if (feat.isMany()) {
                @SuppressWarnings("unchecked")
                List<EObject> list = (List<EObject>) container.eGet(feat);
                list.remove(target);
            } else {
                container.eSet(feat, null);
            }
        } else if (target.eResource() != null) {
            target.eResource().getContents().remove(target);
        }
        return Outcome.success(String.format("Delete %s '%s'", elementClass, defaultString(item.getExistingName(), "?")));
    }

    private static Outcome simulateSystemComponent(ResourceSet sandbox, PlanItem item) {
        String name = defaultString(item.getName(), "");
        if (name.isBlank()) {
            return Outcome.failure("System component name missing");
        }
        String layer = safeLayer(item.getLayer(), "SA");
        if (item.getParentName() != null && !item.getParentName().isBlank()) {
            EObject parent = Finders.findByName(sandbox, "SystemComponent", item.getParentName());
            if (parent == null) {
                return Outcome.failure("Parent system component '" + item.getParentName() + "' not found");
            }
            return Outcome.success(String.format("Create system component '%s' under %s", name, item.getParentName()));
        }
        EObject root = ArchitectureLocator.findTargetElement(sandbox, layer);
        if (root == null) {
            return Outcome.failure("Layer " + layer + " not found");
        }
        return Outcome.success(String.format("Create system component '%s' in %s", name, layer));
    }

    private static Outcome simulateSystemFunction(ResourceSet sandbox, PlanItem item) {
        String name = defaultString(item.getName(), "");
        if (name.isBlank()) {
            return Outcome.failure("System function name missing");
        }
        String layer = safeLayer(item.getLayer(), "SA");
        if (item.getParentName() != null && !item.getParentName().isBlank()) {
            EObject parent = Finders.findByName(sandbox, "SystemFunction", item.getParentName());
            if (parent == null) {
                return Outcome.failure("Parent system function '" + item.getParentName() + "' not found");
            }
            return Outcome.success(String.format("Create system function '%s' under %s", name, item.getParentName()));
        }
        EObject root = ArchitectureLocator.findTargetElement(sandbox, layer);
        if (root == null) {
            return Outcome.failure("Layer " + layer + " not found");
        }
        return Outcome.success(String.format("Create system function '%s' in %s", name, layer));
    }

    private static Outcome simulateSystemInterface(ResourceSet sandbox, PlanItem item) {
        String src = defaultString(item.getSourceName(), "");
        String tgt = defaultString(item.getTargetName(), "");
        if (src.isBlank() || tgt.isBlank()) {
            return Outcome.failure("System interface requires source and target components");
        }
        EObject source = Finders.findByName(sandbox, "SystemComponent", src);
        EObject target = Finders.findByName(sandbox, "SystemComponent", tgt);
        if (source == null || target == null) {
            return Outcome.failure("System components not found for interface");
        }
        String name = defaultString(item.getName(), "ComponentExchange");
        return Outcome.success(String.format("Create system interface '%s' between %s → %s", name, src, tgt));
    }

    private static Outcome simulateCapabilityAllocation(ResourceSet sandbox, PlanItem item) {
        String component = defaultString(item.getExistingName(), "");
        String capability = defaultString(item.getCapabilityName(), "");
        if (component.isBlank() || capability.isBlank()) {
            return Outcome.failure("Component and capability required for allocation");
        }
        EObject componentObj = Finders.findByName(sandbox, "SystemComponent", component);
        EObject capabilityObj = Finders.findByName(sandbox, "Capability", capability);
        if (componentObj == null || capabilityObj == null) {
            return Outcome.failure("Component or capability not found");
        }
        return Outcome.success(String.format("Allocate capability '%s' to system component '%s'", capability, component));
    }

    private static EObject findBestContainer(EObject root, EClass target) {
        List<EObject> queue = new ArrayList<>();
        queue.add(root);
        int index = 0;
        while (index < queue.size()) {
            EObject current = queue.get(index++);
            EReference ref = findContainmentFor(current, target);
            if (ref != null) {
                return current;
            }
            queue.addAll(current.eContents());
        }
        return null;
    }

    private static EReference findContainmentFor(EObject container, EClass target) {
        for (EStructuralFeature feature : container.eClass().getEAllStructuralFeatures()) {
            if (feature instanceof EReference) {
                EReference ref = (EReference) feature;
                if (ref.isContainment() && ref.isMany()) {
                    EClass type = (EClass) ref.getEType();
                    if (type == target || type.isSuperTypeOf(target)) {
                        return ref;
                    }
                }
            }
        }
        return null;
    }

    private static void setEndpoint(EObject exchange, String role, EObject end) {
        if (exchange == null || end == null) {
            return;
        }
        try {
            for (EStructuralFeature feature : exchange.eClass().getEAllStructuralFeatures()) {
                if (feature instanceof EReference) {
                    EReference ref = (EReference) feature;
                    if (ref.isContainment()) {
                        continue;
                    }
                    String name = ref.getName().toLowerCase();
                    boolean matches = ("source".equals(role) && (name.contains("src") || name.contains("source")))
                            || ("target".equals(role) && (name.contains("tgt") || name.contains("target")));
                    if (!matches) {
                        continue;
                    }
                    if (ref.getEReferenceType().isInstance(end)) {
                        exchange.eSet(ref, end);
                        return;
                    }
                }
            }
        } catch (Exception ignore) {
            // sandbox best-effort
        }
    }

    private static String elementClassForExchange(String exchangeClass, boolean source) {
        if ("ComponentExchange".equals(exchangeClass)) {
            return "LogicalComponent";
        }
        if ("FunctionalExchange".equals(exchangeClass)) {
            return "LogicalFunction";
        }
        return source ? "Component" : "Component";
    }

    private static boolean containsByName(EObject container, EReference containment, String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        Object current = container.eGet(containment);
        if (!(current instanceof List<?> list)) {
            return false;
        }
        for (Object obj : list) {
            if (obj instanceof EObject child) {
                if (name.equals(readName(child))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static EObject createComponentCopy(EObject logical, EClass targetClass, boolean includeChildren) {
        EObject copy = targetClass.getEPackage().getEFactoryInstance().create(targetClass);
        copyScalarAttributes(logical, copy);
        cleanIdentifiers(copy);
        if (!includeChildren) {
            return copy;
        }
        EReference nested = findContainmentFor(copy, targetClass);
        if (nested == null) {
            return copy;
        }
        for (EObject child : logical.eContents()) {
            if (!"LogicalComponent".equals(child.eClass().getName())) {
                continue;
            }
            EObject nestedCopy = createComponentCopy(child, targetClass, true);
            addChild(copy, nested, nestedCopy);
        }
        return copy;
    }

    private static void addChild(EObject container, EReference containment, EObject child) {
        Object current = container.eGet(containment);
        if (current instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<EObject> typed = (List<EObject>) list;
            typed.add(child);
        }
    }

    private static void copyScalarAttributes(EObject source, EObject target) {
        for (EStructuralFeature feature : source.eClass().getEAllStructuralFeatures()) {
            if (!(feature instanceof EAttribute attr)) {
                continue;
            }
            if (!attr.isChangeable() || attr.isDerived()) {
                continue;
            }
            String fname = attr.getName().toLowerCase();
            if (fname.contains("uuid") || fname.endsWith("id")) {
                continue;
            }
            EStructuralFeature targetFeature = target.eClass().getEStructuralFeature(attr.getName());
            if (targetFeature instanceof EAttribute targetAttr && targetAttr.isChangeable()) {
                target.eSet(targetAttr, source.eGet(attr));
            }
        }
    }

    private static void cleanIdentifiers(EObject element) {
        for (EStructuralFeature feature : element.eClass().getEAllStructuralFeatures()) {
            if (!feature.isChangeable()) {
                continue;
            }
            String fname = feature.getName().toLowerCase();
            if (fname.contains("uuid") || fname.endsWith("id")) {
                element.eSet(feature, null);
            }
        }
        for (EObject child : element.eContents()) {
            cleanIdentifiers(child);
        }
    }

    private static String readName(EObject element) {
        if (element == null) {
            return null;
        }
        EStructuralFeature feature = element.eClass().getEStructuralFeature("name");
        if (feature != null) {
            Object value = element.eGet(feature);
            if (value != null) {
                return value.toString();
            }
        }
        return null;
    }

    private static void addTraceLink(EObject logical, EObject physical) {
        addReference(logical, physical);
        addReference(physical, logical);
    }

    private static void addReference(EObject source, EObject target) {
        for (EStructuralFeature feature : source.eClass().getEAllStructuralFeatures()) {
            if (!(feature instanceof EReference ref) || ref.isContainment()) {
                continue;
            }
            String name = feature.getName().toLowerCase();
            if (!(name.contains("trace") || name.contains("alloc") || name.contains("component"))) {
                continue;
            }
            Object current = source.eGet(ref);
            if (ref.isMany()) {
                @SuppressWarnings("unchecked")
                Collection<Object> list = (Collection<Object>) current;
                if (!list.contains(target)) {
                    list.add(target);
                }
                return;
            }
            source.eSet(ref, target);
            return;
        }
    }

    private static String defaultString(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static String safeLayer(String layer) {
        return safeLayer(layer, "LA");
    }

    private static String safeLayer(String layer, String fallback) {
        return defaultString(layer, fallback);
    }

    private static EObject findFunction(ResourceSet sandbox, String name, String preferredClass) {
        if (preferredClass != null && !preferredClass.isBlank()) {
            EObject fn = Finders.findByName(sandbox, preferredClass, name);
            if (fn != null) {
                return fn;
            }
        }
        String[] classes = {"LogicalFunction", "SystemFunction", "PhysicalFunction"};
        for (String cls : classes) {
            EObject fn = Finders.findByName(sandbox, cls, name);
            if (fn != null) {
                return fn;
            }
        }
        return null;
    }

    private static EObject findScenario(ResourceSet sandbox, String name) {
        String[] classes = {"Scenario", "FunctionalScenario", "OperationalScenario", "SystemScenario"};
        for (String cls : classes) {
            EObject scenario = Finders.findByName(sandbox, cls, name);
            if (scenario != null) {
                return scenario;
            }
        }
        return null;
    }

    private static EObject findRequirement(ResourceSet sandbox, String name) {
        String[] requirementClasses = {"Requirement", "SystemRequirement", "LogicalRequirement", "PhysicalRequirement"};
        for (String cls : requirementClasses) {
            EObject requirement = Finders.findByName(sandbox, cls, name);
            if (requirement != null) {
                return requirement;
            }
        }
        return null;
    }

    private static EObject findRequirementContainer(ResourceSet sandbox, String containerClass, String containerName) {
        if (containerClass != null && !containerClass.isBlank()) {
            if (containerName != null && !containerName.isBlank()) {
                EObject container = Finders.findByName(sandbox, containerClass, containerName);
                if (container != null) {
                    return container;
                }
            }
            EObject fallback = Finders.findByName(sandbox, containerClass, "");
            if (fallback != null) {
                return fallback;
            }
        }
        if (containerName != null && !containerName.isBlank()) {
            EObject named = Finders.findByName(sandbox, "RequirementsPkg", containerName);
            if (named != null) {
                return named;
            }
        }
        return Finders.findByName(sandbox, "RequirementsPkg", "Requirements");
    }

    // --- Result types ---------------------------------------------------------------------------------------------

    public static final class DryRunResult {
        private final boolean available;
        private final Map<Integer, Outcome> outcomes;
        private final List<String> issues;

        private DryRunResult(boolean available, Map<Integer, Outcome> outcomes, List<String> issues) {
            this.available = available;
            this.outcomes = outcomes;
            this.issues = issues;
        }

        public static DryRunResult unavailable(String reason) {
            List<String> issues = new ArrayList<>();
            issues.add(reason);
            return new DryRunResult(false, Map.of(), issues);
        }

        public boolean isAvailable() {
            return available;
        }

        public boolean isSuccessful() {
            return available && outcomes.values().stream().allMatch(Outcome::isSuccess);
        }

        public Outcome outcomeFor(int index) {
            return outcomes.get(index);
        }

        public Map<Integer, Outcome> outcomes() {
            return outcomes;
        }

        public List<String> issues() {
            return issues;
        }
    }

    public static final class Outcome {
        private final boolean success;
        private final String message;

        private Outcome(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public static Outcome success(String message) {
            return new Outcome(true, message);
        }

        public static Outcome failure(String message) {
            return new Outcome(false, message);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

    }
}
