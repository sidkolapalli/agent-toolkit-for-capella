package org.polarsys.capella.ai.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.stream.Collectors;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.model.ModelAnalytics.Analysis;
import org.polarsys.capella.ai.model.ModelAnalytics.FunctionalSnapshot;
import org.polarsys.capella.ai.model.ModelAnalytics.LayerStats;
import org.polarsys.capella.ai.model.ModelAnalytics.ValidationReport;
import org.polarsys.capella.ai.session.SessionBridge;

/**
 * Provides Arcadia methodology guidance derived from live model analytics.
 */
public final class MethodologyCoach {

    public static final String COMMAND_ARCADIA_NEXT = "/arcadia-next";

    private static final long CACHE_TTL_MS = 5_000L;
    private static final Map<Session, CacheEntry> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private MethodologyCoach() {}

    public static Guidance guidance() {
        return guidance(SessionBridge.getActiveSession());
    }

    public static Guidance guidance(Session session) {
        if (session == null) {
            return Guidance.unavailable("No active Capella session.");
        }
        long now = System.currentTimeMillis();
        CacheEntry cached = CACHE.get(session);
        if (cached != null && now - cached.timestamp < CACHE_TTL_MS) {
            return cached.guidance;
        }

        Analysis analysis = ModelAnalytics.analyze(session);
        ResourceSet resourceSet = session.getTransactionalEditingDomain() != null
                ? session.getTransactionalEditingDomain().getResourceSet()
                : null;

        Guidance guidance = buildGuidance(analysis, resourceSet);
        CACHE.put(session, new CacheEntry(guidance, now));
        return guidance;
    }

    public static void invalidate(Session session) {
        if (session != null) {
            CACHE.remove(session);
        }
    }

    private static Guidance buildGuidance(Analysis analysis, ResourceSet resourceSet) {
        Map<String, LayerStats> layers = analysis.layerStats();
        LayerStats operational = layers.get("OA");
        LayerStats system = layers.get("SA");
        LayerStats logical = layers.get("LA");
        LayerStats physical = layers.get("PA");

        int missionCount = countElements(resourceSet, Set.of("Mission"));
        int capabilityCount = countElements(resourceSet, Set.of("Capability"));

        Step operationalStep = buildOperationalStep(operational, missionCount);
        Step systemStep = buildSystemStep(system, capabilityCount);
        Step logicalStep = buildLogicalStep(logical);
        Step physicalStep = buildPhysicalStep(physical);
        Step verificationStep = buildVerificationStep(analysis, layers);

        List<Step> steps = List.of(operationalStep, systemStep, logicalStep, physicalStep, verificationStep);
        List<String> alerts = new ArrayList<>();

        ValidationReport validation = analysis.validation();
        if (validation.isAvailable() && validation.errorCount() > 0) {
            alerts.add(String.format(Locale.ROOT,
                    "Validation reports %d error(s) and %d warning(s). Run Capella validation before committing changes.",
                    validation.errorCount(), validation.warningCount()));
        }

        return new Guidance(steps, alerts);
    }

    private static Step buildOperationalStep(LayerStats stats, int missions) {
        int actors = stats != null ? stats.components() : 0;
        int activities = stats != null ? stats.functions() : 0;
        double structure = stats != null ? stats.componentStructureCoverage() : 0d;
        int unlinked = stats != null ? stats.functionsWithoutAllocation() : 0;
        List<String> gaps = stats != null ? stats.topComponentGaps() : List.of();
        List<String> missing = stats != null ? stats.topUnallocatedFunctions() : List.of();

        Status status;
        String headline;
        List<String> details = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();

        if ((actors + activities + missions) == 0) {
            status = Status.NOT_STARTED;
            headline = "Capture missions, actors, and operational activities to ground the context.";
            suggestions.add(COMMAND_ARCADIA_NEXT);
            suggestions.add("/diagram-walkthrough");
            suggestions.add("/explain-selection");
        } else {
            details.add(String.format(Locale.ROOT,
                    "Missions: %d · Actors: %d · Activities: %d",
                    missions, actors, activities));
            boolean structureOk = structure >= 70 || actors <= 1;
            boolean allocationOk = unlinked == 0 || activities == 0;
            if (structureOk && allocationOk && missions > 0 && actors > 0 && activities > 0) {
                status = Status.COMPLETE;
                headline = String.format(Locale.ROOT,
                        "Operational baseline defined (structure %.0f%%, allocations complete).",
                        structure);
            } else {
                status = Status.IN_PROGRESS;
                headline = "Refine the operational breakdown and connect activities.";
                if (!structureOk && stats != null && stats.componentsWithoutChildren() > 0) {
                    details.add(String.format(Locale.ROOT,
                            "Break down %d actor/entity gap(s): %s",
                            stats.componentsWithoutChildren(), String.join(", ", limit(gaps, 4))));
                }
                if (!allocationOk && unlinked > 0) {
                    details.add(String.format(Locale.ROOT,
                            "%d activity allocation(s) missing%s",
                            unlinked,
                            missing.isEmpty() ? "" : ": " + String.join(", ", limit(missing, 4))));
                }
                suggestions.add("/diagram-walkthrough");
                suggestions.add("/explain-selection");
            }
        }

        return new Step("operational", "Operational Analysis", status, headline, details, suggestions);
    }

    private static Step buildSystemStep(LayerStats stats, int capabilities) {
        int components = stats != null ? stats.components() : 0;
        int functions = stats != null ? stats.functions() : 0;
        double structure = stats != null ? stats.componentStructureCoverage() : 0d;
        double allocation = stats != null ? stats.functionAllocationCoverage() : 0d;
        List<String> gaps = stats != null ? stats.topComponentGaps() : List.of();
        List<String> missing = stats != null ? stats.topUnallocatedFunctions() : List.of();

        Status status;
        String headline;
        List<String> details = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();

        if ((components + functions + capabilities) == 0) {
            status = Status.NOT_STARTED;
            headline = "Translate needs into system capabilities and functional architecture.";
            suggestions.add(COMMAND_ARCADIA_NEXT);
            suggestions.add("/scaffold-component");
        } else {
            details.add(String.format(Locale.ROOT,
                    "Capabilities: %d · System components: %d (%.0f%% structured) · Functions allocated: %.0f%%",
                    capabilities, components, structure, allocation));
            boolean capabilityOk = capabilities > 0;
            boolean structureOk = structure >= 65 || components <= 1;
            boolean allocationOk = allocation >= 60 || functions == 0;
            if (capabilityOk && structureOk && allocationOk && components > 0 && functions > 0) {
                status = Status.COMPLETE;
                headline = "System need clarified and capabilities allocated.";
            } else {
                status = Status.IN_PROGRESS;
                headline = "Strengthen capabilities and wire functions to components.";
                if (!structureOk && stats != null && stats.componentsWithoutChildren() > 0) {
                    details.add(String.format(Locale.ROOT,
                            "Decompose %d component(s): %s",
                            stats.componentsWithoutChildren(), String.join(", ", limit(gaps, 4))));
                }
                if (!allocationOk && stats != null && stats.functionsWithoutAllocation() > 0) {
                    details.add(String.format(Locale.ROOT,
                            "%d function allocation(s) missing%s",
                            stats.functionsWithoutAllocation(),
                            missing.isEmpty() ? "" : ": " + String.join(", ", limit(missing, 4))));
                }
                if (!capabilityOk) {
                    details.add("No capabilities defined yet.");
                }
                suggestions.add("/scaffold-component");
                suggestions.add("/extend-chain");
                suggestions.add("/cover-requirements");
            }
        }

        return new Step("system", "System Need & Capabilities", status, headline, details, suggestions);
    }

    private static Step buildLogicalStep(LayerStats stats) {
        int components = stats != null ? stats.components() : 0;
        int functions = stats != null ? stats.functions() : 0;
        double structure = stats != null ? stats.componentStructureCoverage() : 0d;
        double allocation = stats != null ? stats.functionAllocationCoverage() : 0d;
        List<String> gaps = stats != null ? stats.topComponentGaps() : List.of();
        List<String> missing = stats != null ? stats.topUnallocatedFunctions() : List.of();

        Status status;
        String headline;
        List<String> details = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();

        if ((components + functions) == 0) {
            status = Status.NOT_STARTED;
            headline = "Derive the logical architecture from the system need.";
            suggestions.add(COMMAND_ARCADIA_NEXT);
            suggestions.add("/scaffold-component");
        } else {
            details.add(String.format(Locale.ROOT,
                    "Logical components: %d (%.0f%% structured) · Functions allocated: %.0f%%",
                    components, structure, allocation));
            boolean structureOk = structure >= 70 || components <= 1;
            boolean allocationOk = allocation >= 70 || functions == 0;
            if (structureOk && allocationOk && components > 0 && functions > 0) {
                status = Status.COMPLETE;
                headline = "Logical architecture is in place.";
            } else {
                status = Status.IN_PROGRESS;
                headline = "Refine logical breakdown and allocate functions.";
                if (!structureOk && stats != null && stats.componentsWithoutChildren() > 0) {
                    details.add(String.format(Locale.ROOT,
                            "Break down %d logical component(s): %s",
                            stats.componentsWithoutChildren(), String.join(", ", limit(gaps, 4))));
                }
                if (!allocationOk && stats != null && stats.functionsWithoutAllocation() > 0) {
                    details.add(String.format(Locale.ROOT,
                            "%d logical function(s) need allocation%s",
                            stats.functionsWithoutAllocation(),
                            missing.isEmpty() ? "" : ": " + String.join(", ", limit(missing, 4))));
                }
                suggestions.add("/scaffold-component");
                suggestions.add("/extend-chain");
            }
        }

        return new Step("logical", "Logical Architecture", status, headline, details, suggestions);
    }

    private static Step buildPhysicalStep(LayerStats stats) {
        int components = stats != null ? stats.components() : 0;
        int functions = stats != null ? stats.functions() : 0;
        double structure = stats != null ? stats.componentStructureCoverage() : 0d;
        double allocation = stats != null ? stats.functionAllocationCoverage() : 0d;

        Status status;
        String headline;
        List<String> details = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();

        if ((components + functions) == 0) {
            status = Status.NOT_STARTED;
            headline = "Propagate the logical design into a physical solution.";
            suggestions.add(COMMAND_ARCADIA_NEXT);
            suggestions.add("/sync-physical");
        } else {
            details.add(String.format(Locale.ROOT,
                    "Physical components: %d (%.0f%% structured) · Realised functions: %.0f%%",
                    components, structure, allocation));
            boolean structureOk = structure >= 60 || components <= 1;
            boolean allocationOk = allocation >= 60 || functions == 0;
            if (structureOk && allocationOk && components > 0) {
                status = Status.COMPLETE;
                headline = "Physical architecture aligned with logical design.";
            } else {
                status = Status.IN_PROGRESS;
                headline = "Consolidate physical breakdown and synchronise traces.";
                if (!structureOk && stats != null && stats.componentsWithoutChildren() > 0) {
                    details.add(String.format(Locale.ROOT,
                            "Expand %d physical component(s) to cover allocations.",
                            stats.componentsWithoutChildren()));
                }
                if (!allocationOk && stats != null && stats.functionsWithoutAllocation() > 0) {
                    details.add(String.format(Locale.ROOT,
                            "%d physical functions need allocation to components.",
                            stats.functionsWithoutAllocation()));
                }
                suggestions.add("/sync-physical");
            }
        }

        return new Step("physical", "Physical Architecture", status, headline, details, suggestions);
    }

    private static Step buildVerificationStep(Analysis analysis, Map<String, LayerStats> layers) {
        FunctionalSnapshot snapshot = analysis.snapshot();
        ValidationReport validation = analysis.validation();
        int requirements = layers.values().stream().mapToInt(LayerStats::requirements).sum();
        int chains = snapshot.chainCount();
        int scenarios = snapshot.scenarioCount();
        int errors = validation.errorCount();
        int warnings = validation.warningCount();

        Status status;
        String headline;
        List<String> details = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();

        if (requirements == 0 && chains == 0 && scenarios == 0) {
            status = Status.NOT_STARTED;
            headline = "Capture requirements and behavioural scenarios to close the loop.";
            suggestions.add(COMMAND_ARCADIA_NEXT);
            suggestions.add("/cover-requirements");
        } else {
            details.add(String.format(Locale.ROOT,
                    "Requirements: %d · Functional chains: %d · Scenarios/state machines: %d",
                    requirements, chains, scenarios));
            if (validation.isAvailable()) {
                details.add(String.format(Locale.ROOT,
                        "Validation: %d error(s), %d warning(s)", errors, warnings));
            }
            boolean requirementsOk = requirements > 0;
            boolean behaviourOk = chains > 0 || scenarios > 0;
            boolean validationOk = !validation.isAvailable() || errors == 0;
            if (requirementsOk && behaviourOk && validationOk) {
                status = Status.COMPLETE;
                headline = "Requirements traced and behavioural coverage captured.";
            } else {
                status = Status.IN_PROGRESS;
                headline = "Link requirements, scenarios, and resolve validation findings.";
                if (!requirementsOk) {
                    details.add("Add or import requirements packages.");
                }
                if (!behaviourOk) {
                    details.add("Author functional chains or scenarios to describe behaviour.");
                }
                if (validation.isAvailable() && errors > 0) {
                    String firstIssue = validation.messages().isEmpty() ? "see validation report" : validation.messages().get(0);
                    details.add(String.format(Locale.ROOT,
                            "Resolve validation errors (first issue: %s)", firstIssue));
                }
                suggestions.add("/cover-requirements");
                suggestions.add("/diagram-walkthrough");
            }
        }

        return new Step("verification", "Verification & Behaviour", status, headline, details, suggestions);
    }

    private static int countElements(ResourceSet resourceSet, Set<String> simpleClassNames) {
        if (resourceSet == null || simpleClassNames == null || simpleClassNames.isEmpty()) {
            return 0;
        }
        int count = 0;
        Set<String> lookup = simpleClassNames.stream()
                .filter(Objects::nonNull)
                .map(name -> name.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        try {
            for (Resource resource : resourceSet.getResources()) {
                if (resource == null) {
                    continue;
                }
                var iterator = resource.getAllContents();
                while (iterator.hasNext()) {
                    EObject eo = iterator.next();
                    try {
                        String simpleName = eo.eClass().getName();
                        if (lookup.contains(simpleName.toLowerCase(Locale.ROOT))) {
                            count++;
                        }
                    } catch (Exception ignore) {
                        // continue scanning
                    }
                }
            }
        } catch (Exception ignore) {
            // best-effort count
        }
        return count;
    }

    private static List<String> limit(List<String> values, int max) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .limit(max)
                .collect(Collectors.toList());
    }

    private static final class CacheEntry {
        final Guidance guidance;
        final long timestamp;

        CacheEntry(Guidance guidance, long timestamp) {
            this.guidance = guidance;
            this.timestamp = timestamp;
        }
    }

    public static final class Guidance {
        private final List<Step> steps;
        private final List<String> alerts;
        private final List<String> recommendedCommands;
        private final boolean available;
        private final String reason;

        Guidance(List<Step> steps, List<String> alerts) {
            this(steps, alerts, true, null);
        }

        private Guidance(List<Step> steps, List<String> alerts, boolean available, String reason) {
            this.steps = steps == null ? List.of() : List.copyOf(steps);
            this.alerts = alerts == null ? List.of() : List.copyOf(alerts);
            this.recommendedCommands = this.steps.stream()
                    .flatMap(step -> step.suggestions().stream())
                    .distinct()
                    .collect(Collectors.toList());
            this.available = available;
            this.reason = reason;
        }

        static Guidance unavailable(String reason) {
            return new Guidance(List.of(), List.of(), false, reason);
        }

        public boolean isAvailable() {
            return available;
        }

        public String reason() {
            return reason;
        }

        public List<Step> steps() {
            return steps;
        }

        public List<String> alerts() {
            return alerts;
        }

        public List<String> recommendedCommands() {
            return recommendedCommands;
        }
    }

    public static final class Step {
        private final String id;
        private final String title;
        private final Status status;
        private final String headline;
        private final List<String> details;
        private final List<String> suggestions;

        Step(String id, String title, Status status, String headline, List<String> details, List<String> suggestions) {
            this.id = id;
            this.title = title;
            this.status = status;
            this.headline = headline;
            this.details = details == null ? List.of() : List.copyOf(details);
            this.suggestions = suggestions == null ? List.of() : suggestions.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .distinct()
                    .collect(Collectors.toList());
        }

        public String id() {
            return id;
        }

        public String title() {
            return title;
        }

        public Status status() {
            return status;
        }

        public String headline() {
            return headline;
        }

        public List<String> details() {
            return details;
        }

        public List<String> suggestions() {
            return suggestions;
        }
    }

    public enum Status {
        NOT_STARTED("[ ]"),
        IN_PROGRESS("[~]"),
        COMPLETE("[✔]");

        private final String symbol;

        Status(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }
    }
}
