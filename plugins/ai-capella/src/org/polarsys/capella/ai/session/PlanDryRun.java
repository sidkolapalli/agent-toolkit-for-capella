package org.polarsys.capella.ai.session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    private static String defaultString(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static String safeLayer(String layer) {
        return defaultString(layer, "LA");
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
