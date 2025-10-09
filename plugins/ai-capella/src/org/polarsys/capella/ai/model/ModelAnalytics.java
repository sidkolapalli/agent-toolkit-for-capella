package org.polarsys.capella.ai.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.eclipse.emf.common.util.Diagnostic;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.util.Diagnostician;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.sirius.business.api.session.Session;

/**
 * Aggregates higher-level analytics for the active Capella session.
 */
public final class ModelAnalytics {
    private static final long CACHE_TTL_MS = 5_000L;
    private static final Map<Session, CacheEntry> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private enum LayerId { OA, SA, LA, PA }

    private static final Map<LayerId, LayerConfig> LAYER_CONFIGS = Map.of(
        LayerId.OA, new LayerConfig(
            "Operational",
            Set.of("OperationalEntity", "OperationalActor"),
            Set.of("OperationalActivity"),
            Set.of("OperationalActivityFlow", "OperationalExchange"),
            Set.of("Requirement", "OperationalCapability")),
        LayerId.SA, new LayerConfig(
            "System",
            Set.of("SystemComponent"),
            Set.of("SystemFunction"),
            Set.of("Exchange", "FunctionalExchange", "ComponentExchange"),
            Set.of("SystemRequirement", "Requirement")),
        LayerId.LA, new LayerConfig(
            "Logical",
            Set.of("LogicalComponent"),
            Set.of("LogicalFunction"),
            Set.of("ComponentExchange", "FunctionalExchange"),
            Set.of("Requirement", "LogicalRequirement")),
        LayerId.PA, new LayerConfig(
            "Physical",
            Set.of("PhysicalComponent"),
            Set.of("PhysicalFunction"),
            Set.of("ComponentExchange", "PhysicalLink"),
            Set.of("Requirement", "PhysicalRequirement"))
    );

    private ModelAnalytics() {}

    public static Analysis analyze(Session session) {
        if (session == null) {
            return Analysis.empty();
        }
        long now = System.currentTimeMillis();
        CacheEntry cached = CACHE.get(session);
        if (cached != null && now - cached.timestamp < CACHE_TTL_MS) {
            return cached.analysis;
        }
        ResourceSet resourceSet = Optional.ofNullable(session.getTransactionalEditingDomain())
                .map(ed -> ed.getResourceSet()).orElse(null);
        Analysis analysis = computeAnalysis(session, resourceSet);
        CACHE.put(session, new CacheEntry(analysis, now));
        return analysis;
    }

    private static Analysis computeAnalysis(Session session, ResourceSet resourceSet) {
        Map<LayerId, LayerStats> layerStats = new EnumMap<>(LayerId.class);
        for (LayerId layerId : LayerId.values()) {
            EObject root = resourceSet != null
                    ? ArchitectureLocator.findTargetElement(resourceSet, layerId.name())
                    : ArchitectureLocator.findTargetElement(session, layerId.name());
            if (root != null) {
                layerStats.put(layerId, analyseLayer(root, LAYER_CONFIGS.get(layerId)));
            }
        }
        ValidationReport validation = ValidationReport.fromSession(resourceSet);
        FunctionalSnapshot snapshot = FunctionalSnapshot.fromResourceSet(resourceSet);
        DependencyGraph graph = DependencyGraph.fromResourceSet(resourceSet);
        return new Analysis(layerStats, validation, snapshot, graph);
    }

    private static LayerStats analyseLayer(EObject root, LayerConfig config) {
        LayerStats stats = new LayerStats(config.label);
        List<String> gapComponents = new ArrayList<>();
        List<String> unallocatedFunctions = new ArrayList<>();

        visit(root, eo -> {
            String className = eo.eClass().getName();

            if (config.componentClasses.contains(className)) {
                stats.components++;
                if (!hasChildComponent(eo)) {
                    stats.componentsWithoutChildren++;
                    gapComponents.add(nameOf(eo));
                }
            }

            if (config.functionClasses.contains(className)) {
                stats.functions++;
                if (!hasAllocationToComponent(eo)) {
                    stats.functionsWithoutAllocation++;
                    unallocatedFunctions.add(nameOf(eo));
                }
            }

            if (config.exchangeClasses.stream().anyMatch(className::equals)) {
                stats.exchanges++;
            }

            if (config.requirementClasses.stream().anyMatch(className::equals)) {
                stats.requirements++;
            }
        });

        stats.topComponentGaps = gapComponents.stream().filter(n -> !n.isBlank()).limit(5).collect(Collectors.toList());
        stats.topUnallocatedFunctions = unallocatedFunctions.stream().filter(n -> !n.isBlank()).limit(5).collect(Collectors.toList());
        return stats;
    }

    private static void visit(EObject root, java.util.function.Consumer<EObject> consumer) {
        var iterator = EcoreUtil.getAllProperContents(root, true);
        while (iterator.hasNext()) {
            consumer.accept((EObject) iterator.next());
        }
    }

    private static boolean hasChildComponent(EObject component) {
        for (EStructuralFeature feature : component.eClass().getEAllStructuralFeatures()) {
            if (feature instanceof EReference) {
                EReference ref = (EReference) feature;
                if (ref.isContainment()) {
                    String name = ref.getName().toLowerCase(Locale.ROOT);
                    if (name.contains("component")) {
                        Object value = component.eGet(ref);
                        if (value instanceof Collection<?> collection) {
                            if (!collection.isEmpty()) {
                                return true;
                            }
                        } else if (value != null) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean hasAllocationToComponent(EObject function) {
        for (EStructuralFeature feature : function.eClass().getEAllStructuralFeatures()) {
            if (feature instanceof EReference ref && !ref.isContainment()) {
                String name = ref.getName().toLowerCase(Locale.ROOT);
                if (name.contains("component") || name.contains("allocation")) {
                    Object value = function.eGet(ref);
                    if (value instanceof Collection<?> collection) {
                        if (!collection.isEmpty()) {
                            return true;
                        }
                    } else if (value != null) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String nameOf(EObject eo) {
        if (eo == null) {
            return "";
        }
        EStructuralFeature feature = eo.eClass().getEStructuralFeature("name");
        if (feature != null) {
            Object value = eo.eGet(feature);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        feature = eo.eClass().getEStructuralFeature("id");
        if (feature != null) {
            Object value = eo.eGet(feature);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return eo.eClass().getName();
    }

    private static final class LayerConfig {
        final String label;
        final Set<String> componentClasses;
        final Set<String> functionClasses;
        final Set<String> exchangeClasses;
        final Set<String> requirementClasses;

        LayerConfig(String label, Set<String> componentClasses, Set<String> functionClasses,
                    Set<String> exchangeClasses, Set<String> requirementClasses) {
            this.label = label;
            this.componentClasses = componentClasses;
            this.functionClasses = functionClasses;
            this.exchangeClasses = exchangeClasses;
            this.requirementClasses = requirementClasses;
        }
    }

    private static final class CacheEntry {
        final Analysis analysis;
        final long timestamp;

        CacheEntry(Analysis analysis, long timestamp) {
            this.analysis = analysis;
            this.timestamp = timestamp;
        }
    }

    // ----------------------------------------------------------------------------------------------

    public static final class Analysis {
        private final Map<LayerId, LayerStats> layers;
        private final ValidationReport validation;
        private final FunctionalSnapshot snapshot;
        private final DependencyGraph dependencyGraph;

        Analysis(Map<LayerId, LayerStats> layers, ValidationReport validation,
                 FunctionalSnapshot snapshot, DependencyGraph dependencyGraph) {
            this.layers = layers;
            this.validation = validation;
            this.snapshot = snapshot;
            this.dependencyGraph = dependencyGraph;
        }

        static Analysis empty() {
            return new Analysis(Map.of(), ValidationReport.empty(), FunctionalSnapshot.empty(), DependencyGraph.empty());
        }

        public Map<String, LayerStats> layerStats() {
            Map<String, LayerStats> readable = new LinkedHashMap<>();
            for (Map.Entry<LayerId, LayerStats> entry : layers.entrySet()) {
                readable.put(entry.getKey().name(), entry.getValue());
            }
            return readable;
        }

        public ValidationReport validation() { return validation; }
        public FunctionalSnapshot snapshot() { return snapshot; }
        public DependencyGraph dependencyGraph() { return dependencyGraph; }
    }

    public static final class LayerStats {
        private final String label;
        int components;
        int functions;
        int exchanges;
        int requirements;
        int componentsWithoutChildren;
        int functionsWithoutAllocation;
        List<String> topComponentGaps = List.of();
        List<String> topUnallocatedFunctions = List.of();

        LayerStats(String label) {
            this.label = label;
        }

        public String label() { return label; }
        public int components() { return components; }
        public int functions() { return functions; }
        public int exchanges() { return exchanges; }
        public int requirements() { return requirements; }
        public int componentsWithoutChildren() { return componentsWithoutChildren; }
        public int functionsWithoutAllocation() { return functionsWithoutAllocation; }
        public List<String> topComponentGaps() { return topComponentGaps; }
        public List<String> topUnallocatedFunctions() { return topUnallocatedFunctions; }

        public double componentStructureCoverage() {
            return components == 0 ? 100d : ((components - componentsWithoutChildren) * 100d) / components;
        }

        public double functionAllocationCoverage() {
            return functions == 0 ? 100d : ((functions - functionsWithoutAllocation) * 100d) / functions;
        }
    }

    public static final class ValidationReport {
        private final boolean available;
        private final int errors;
        private final int warnings;
        private final List<String> messages;

        ValidationReport(boolean available, int errors, int warnings, List<String> messages) {
            this.available = available;
            this.errors = errors;
            this.warnings = warnings;
            this.messages = messages;
        }

        static ValidationReport empty() {
            return new ValidationReport(false, 0, 0, List.of("Validation unavailable."));
        }

        public boolean isAvailable() { return available; }
        public int errorCount() { return errors; }
        public int warningCount() { return warnings; }
        public List<String> messages() { return messages; }

        static ValidationReport fromSession(ResourceSet resourceSet) {
            if (resourceSet == null) {
                return empty();
            }
            // Quick, bounded EMF validation to surface issues without heavy plugin lookups.
            int errors = 0;
            int warnings = 0;
            List<String> messages = new ArrayList<>();
            int checked = 0;
            final int MAX_CHECK = 250; // cap the number of EObjects to validate
            try {
                for (Resource res : resourceSet.getResources()) {
                    if (res == null) continue;
                    for (EObject root : res.getContents()) {
                        if (root == null) continue;
                        // Validate root
                        Diagnostic d = Diagnostician.INSTANCE.validate(root);
                        if (d != null && d.getSeverity() != Diagnostic.OK) {
                            if (d.getSeverity() == Diagnostic.ERROR) errors++;
                            else if (d.getSeverity() == Diagnostic.WARNING) warnings++;
                            messages.add(formatDiagnostic(d));
                        }
                        checked++;
                        if (checked >= MAX_CHECK) break;
                        // Validate a limited number of children
                        var it = root.eAllContents();
                        while (it.hasNext() && checked < MAX_CHECK) {
                            EObject eo = (EObject) it.next();
                            Diagnostic dd = Diagnostician.INSTANCE.validate(eo);
                            if (dd != null && dd.getSeverity() != Diagnostic.OK) {
                                if (dd.getSeverity() == Diagnostic.ERROR) errors++;
                                else if (dd.getSeverity() == Diagnostic.WARNING) warnings++;
                                messages.add(formatDiagnostic(dd));
                            }
                            checked++;
                        }
                        if (checked >= MAX_CHECK) break;
                    }
                    if (checked >= MAX_CHECK) break;
                }
            } catch (Exception ex) {
                messages.add("Validation encountered an error: " + ex.getMessage());
            }
            if (messages.isEmpty()) {
                messages.add("Validation: no issues found in quick scan.");
            }
            return new ValidationReport(true, errors, warnings, messages);
        }

        private static String formatDiagnostic(Diagnostic diagnostic) {
            String source = diagnostic.getSource() != null ? diagnostic.getSource() : "Unknown";
            return source + ": " + diagnostic.getMessage();
        }
    }

    public static final class FunctionalSnapshot {
        private final int chainCount;
        private final int scenarioCount;
        private final List<String> representativeChains;
        private final List<String> representativeScenarios;

        FunctionalSnapshot(int chainCount, int scenarioCount, List<String> representativeChains, List<String> representativeScenarios) {
            this.chainCount = chainCount;
            this.scenarioCount = scenarioCount;
            this.representativeChains = representativeChains;
            this.representativeScenarios = representativeScenarios;
        }

        static FunctionalSnapshot empty() {
            return new FunctionalSnapshot(0, 0, List.of(), List.of());
        }

        public int chainCount() { return chainCount; }
        public int scenarioCount() { return scenarioCount; }
        public List<String> representativeChains() { return representativeChains; }
        public List<String> representativeScenarios() { return representativeScenarios; }

        static FunctionalSnapshot fromResourceSet(ResourceSet resourceSet) {
            if (resourceSet == null) {
                return empty();
            }
            List<String> chains = new ArrayList<>();
            List<String> scenarios = new ArrayList<>();
            int chainCount = 0;
            int scenarioCount = 0;
            for (Resource res : resourceSet.getResources()) {
                if (res == null) continue;
                for (EObject root : res.getContents()) {
                    ModelAnalytics.visit(root, eo -> {
                        String cls = eo.eClass().getName();
                        if (cls.contains("FunctionalChain")) {
                            chains.add(nameOf(eo));
                        }
                        if (cls.contains("Scenario") || cls.contains("StateMachine")) {
                            scenarios.add(nameOf(eo));
                        }
                    });
                }
            }
            chainCount = chains.size();
            scenarioCount = scenarios.size();
            List<String> topChains = chains.stream().filter(n -> !n.isBlank()).limit(5).collect(Collectors.toList());
            List<String> topScenarios = scenarios.stream().filter(n -> !n.isBlank()).limit(5).collect(Collectors.toList());
            return new FunctionalSnapshot(chainCount, scenarioCount, topChains, topScenarios);
        }
    }

    public static final class DependencyGraph {
        private final Map<String, List<String>> componentToFunctions;
        private final Map<String, List<String>> functionToComponents;
        private final Map<String, List<String>> componentToRequirements;

        private DependencyGraph(Map<String, List<String>> componentToFunctions,
                                 Map<String, List<String>> functionToComponents,
                                 Map<String, List<String>> componentToRequirements) {
            this.componentToFunctions = componentToFunctions;
            this.functionToComponents = functionToComponents;
            this.componentToRequirements = componentToRequirements;
        }

        static DependencyGraph empty() {
            return new DependencyGraph(Map.of(), Map.of(), Map.of());
        }

        public Map<String, List<String>> componentToFunctions() { return componentToFunctions; }
        public Map<String, List<String>> functionToComponents() { return functionToComponents; }
        public Map<String, List<String>> componentToRequirements() { return componentToRequirements; }

        static DependencyGraph fromResourceSet(ResourceSet resourceSet) {
            if (resourceSet == null) {
                return empty();
            }
            Map<String, List<String>> componentToFunctions = new LinkedHashMap<>();
            Map<String, List<String>> functionToComponents = new LinkedHashMap<>();
            Map<String, List<String>> componentToRequirements = new LinkedHashMap<>();

            for (Resource res : resourceSet.getResources()) {
                if (res == null) continue;
                for (EObject root : res.getContents()) {
                    ModelAnalytics.visit(root, eo -> {
                        String cls = eo.eClass().getName();
                        String name = nameOf(eo);
                        if (cls.endsWith("Component")) {
                            List<String> functions = collectReferences(eo, target -> target.contains("Function"));
                            if (!functions.isEmpty()) {
                                componentToFunctions.computeIfAbsent(name, k -> new ArrayList<>()).addAll(functions);
                                functions.forEach(fn -> functionToComponents.computeIfAbsent(fn, k -> new ArrayList<>()).add(name));
                            }
                            List<String> requirements = collectReferences(eo, target -> target.contains("Requirement"));
                            if (!requirements.isEmpty()) {
                                componentToRequirements.computeIfAbsent(name, k -> new ArrayList<>()).addAll(requirements);
                            }
                        }
                    });
                }
            }

            componentToFunctions.replaceAll((k, v) -> dedupe(v));
            functionToComponents.replaceAll((k, v) -> dedupe(v));
            componentToRequirements.replaceAll((k, v) -> dedupe(v));
            return new DependencyGraph(componentToFunctions, functionToComponents, componentToRequirements);
        }

        private static List<String> collectReferences(EObject source, Predicate<String> classPredicate) {
            List<String> results = new ArrayList<>();
            for (EStructuralFeature feature : source.eClass().getEAllStructuralFeatures()) {
                if (feature instanceof EReference ref && !ref.isContainment()) {
                    Object value = source.eGet(ref);
                    if (value instanceof Collection<?> collection) {
                        for (Object item : collection) {
                            if (item instanceof EObject target) {
                                String targetClass = target.eClass().getName();
                                if (classPredicate.test(targetClass)) {
                                    results.add(nameOf(target));
                                }
                            }
                        }
                    } else if (value instanceof EObject target) {
                        String targetClass = target.eClass().getName();
                        if (classPredicate.test(targetClass)) {
                            results.add(nameOf(target));
                        }
                    }
                }
            }
            return results;
        }

        private static List<String> dedupe(List<String> list) {
            return list.stream().distinct().collect(Collectors.toList());
        }
    }
}
