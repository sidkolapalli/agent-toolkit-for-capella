package org.polarsys.capella.ai.model;

import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.stream.Collectors;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.sirius.business.api.dialect.DialectManager;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.viewpoint.DRepresentation;
import org.polarsys.capella.ai.browse.DiagramBrowser;
import org.polarsys.capella.ai.browse.DiagramBrowser.DiagramInfo;
import org.polarsys.capella.ai.model.ModelAnalytics.Analysis;
import org.polarsys.capella.ai.model.index.ModelIndex;

import org.polarsys.capella.ai.model.ModelAnalytics.DependencyGraph;
import org.polarsys.capella.ai.model.ModelAnalytics.FunctionalSnapshot;
import org.polarsys.capella.ai.model.ModelAnalytics.LayerStats;
import org.polarsys.capella.ai.model.ModelAnalytics.ValidationReport;
import org.polarsys.capella.ai.session.SessionBridge;

public final class ModelSummary {
    private static final Map<Session, CacheEntry> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private static final class CacheEntry {
        final String summary;
        final org.eclipse.emf.transaction.ResourceSetListener listener;

        CacheEntry(Session session, String summary) {
            this.summary = summary;
            WeakReference<Session> sessionRef = new WeakReference<>(session);
            this.listener =
                new org.eclipse.emf.transaction.ResourceSetListenerImpl() {
                    @Override
                    public void resourceSetChanged(
                        org.eclipse.emf.transaction.ResourceSetChangeEvent event
                    ) {
                        Session s = sessionRef.get();
                        if (s != null) {
                            ModelSummary.invalidateSummary(s);
                            return;
                        }
                        try {
                            if (event != null && event.getEditingDomain() != null) {
                                event
                                    .getEditingDomain()
                                    .removeResourceSetListener(this);
                            }
                        } catch (Exception ex) {
                            // best effort
                        }
                    }
                };
        }
    }

    private ModelSummary() {}

    public static String summarize() {
        return summarize(SessionBridge.getActiveSession());
    }

    public static String summarize(Session session) {
        if (session == null) {
            return "No active model session.";
        }

        CacheEntry cached;
        synchronized (ModelSummary.class) {
            cached = CACHE.get(session);
        }
        if (cached != null) {
            return cached.summary;
        }

        Analysis analysis = ModelAnalytics.analyze(session);
        Collection<DRepresentation> representations = DialectManager.INSTANCE.getAllRepresentations(session);
        int diagramCount = representations != null ? representations.size() : 0;
        List<DiagramInfo> activeDiagrams = DiagramBrowser.listActive();
        String diagramExamples = activeDiagrams.stream().limit(5).map(diagram -> diagram.name).collect(Collectors.joining(", "));

        StringBuilder sb = new StringBuilder();
        sb.append("Model Summary\n");
        sb.append(layerDashboards(analysis)).append("\n");
        sb.append(validationSection(analysis.validation())).append("\n");
        sb.append(functionalSection(analysis.snapshot())).append("\n");
        sb.append(traceabilitySection(session)).append("\n");
        sb.append(dependencyHighlights(analysis.dependencyGraph())).append("\n");
        sb.append("- Diagrams: ").append(diagramCount);
        if (!diagramExamples.isBlank()) {
            sb.append(" (e.g., ").append(diagramExamples).append(")");
        }

        String summary = sb.toString();
        synchronized (ModelSummary.class) {
            if (!CACHE.containsKey(session)) {
                CacheEntry refreshed = new CacheEntry(session, summary);
                CACHE.put(session, refreshed);
                attachListener(session, refreshed);
            }
        }
        return summary;
    }

    public static String describeElement(EObject element) {
        if (element == null) {
            return "No element provided.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(formatElement(element));

        EObject container = element.eContainer();
        if (container != null) {
            sb.append("\nContainer: ").append(formatElement(container));
        }

        List<String> attributes = describeAttributes(element);
        if (!attributes.isEmpty()) {
            sb.append("\nAttributes:");
            attributes.forEach(attr -> sb.append("\n- ").append(attr));
        }

        List<String> references = describeReferences(element);
        if (!references.isEmpty()) {
            sb.append("\nReferences:");
            references.forEach(ref -> sb.append("\n- ").append(ref));
        }

        return sb.toString().stripTrailing();
    }

    public static void invalidate(Session session) {
        invalidateSummary(session);
        ModelIndex.invalidate(session);
    }

    private static void invalidateSummary(Session session) {
        if (session != null) {
            CacheEntry entry;
            synchronized (ModelSummary.class) {
                entry = CACHE.remove(session);
            }
            try {
                if (entry != null) {
                    var domain = session.getTransactionalEditingDomain();
                    if (domain != null && entry.listener != null) {
                        domain.removeResourceSetListener(entry.listener);
                    }
                }
            } catch (Exception ex) {
                // best effort
            }
        }
    }

    private static void attachListener(Session session, CacheEntry entry) {
        try {
            var domain = session.getTransactionalEditingDomain();
            if (domain != null && entry != null && entry.listener != null) {
                domain.addResourceSetListener(entry.listener);
            }
        } catch (Exception ex) {
            // best effort
        }
    }

    private static String layerDashboards(Analysis analysis) {
        Map<String, LayerStats> stats = analysis.layerStats();
        if (stats.isEmpty()) {
            return "- No architecture layers detected.";
        }
        StringBuilder sb = new StringBuilder("Layer Dashboards:\n");
        stats.forEach((layerId, layerStats) -> {
            sb.append(String.format("  * %s (%s) — Components: %d, Functions: %d, Exchanges: %d, Requirements: %d\n",
                    layerId, layerStats.label(), layerStats.components(), layerStats.functions(),
                    layerStats.exchanges(), layerStats.requirements()));
            sb.append(String.format("    Coverage: components %.0f%% structured, functions %.0f%% allocated\n",
                    layerStats.componentStructureCoverage(), layerStats.functionAllocationCoverage()));
            if (!layerStats.topComponentGaps().isEmpty()) {
                sb.append("    Components needing breakdown: ")
                        .append(String.join(", ", layerStats.topComponentGaps())).append("\n");
            }
            if (!layerStats.topUnallocatedFunctions().isEmpty()) {
                sb.append("    Functions lacking allocation: ")
                        .append(String.join(", ", layerStats.topUnallocatedFunctions())).append("\n");
            }
        });
        return sb.toString().stripTrailing();
    }

    private static String validationSection(ValidationReport report) {
        if (!report.isAvailable()) {
            return "Validation: unavailable";
        }
        String header = String.format("Validation: %d error(s), %d warning(s)", report.errorCount(), report.warningCount());
        String details = report.messages().stream().limit(3).collect(Collectors.joining(" | "));
        return details.isBlank() ? header : header + " — " + details;
    }

    private static String functionalSection(FunctionalSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Functional Assets: %d chain(s), %d scenario(s)",
                snapshot.chainCount(), snapshot.scenarioCount()));
        if (!snapshot.representativeChains().isEmpty()) {
            sb.append(" — Chains e.g. ").append(String.join(", ", snapshot.representativeChains()));
        }
        if (!snapshot.representativeScenarios().isEmpty()) {
            sb.append("; Scenarios e.g. ").append(String.join(", ", snapshot.representativeScenarios()));
        }
        return sb.toString();
    }

    private static String traceabilitySection(Session session) {
        try {
            var t = ModelAnalytics.computeTraceability(session);
            String header = String.format("Traceability: requirements %.0f%% linked, functions %.0f%% allocated",
                    t.requirementLinkCoverage(),
                    // reuse layer metric approximation: functionsAllocated()/functionCount()
                    (t.functionCount() == 0 ? 100d : (t.functionsAllocated() * 100d) / t.functionCount()));
            if (t.componentExchangeCount() > 0) {
                header += String.format(
                        ", component exchanges %d",
                        t.componentExchangeCount());
            }
            return header;
        } catch (Exception ex) {
            return "Traceability: unavailable";
        }
    }

    private static String dependencyHighlights(DependencyGraph graph) {
        if (graph.componentToFunctions().isEmpty() && graph.componentToRequirements().isEmpty()) {
            return "Dependencies: no cross-layer links detected.";
        }
        StringBuilder sb = new StringBuilder("Dependencies: ");
        graph.componentToFunctions().entrySet().stream().limit(3).forEach(entry ->
                sb.append(entry.getKey()).append("→functions(")
                        .append(String.join(", ", entry.getValue())).append(") "));
        graph.componentToRequirements().entrySet().stream().limit(2).forEach(entry ->
                sb.append(entry.getKey()).append("→requirements(")
                        .append(String.join(", ", entry.getValue())).append(") "));
        return sb.toString().stripTrailing();
    }

    private static final int MAX_ATTRIBUTES = 12;
    private static final int MAX_REFERENCES = 12;
    private static final int MAX_RELATIONS = 5;
    private static final int MAX_VALUE_LENGTH = 160;

    private static List<String> describeAttributes(EObject element) {
        List<String> items = element.eClass()
            .getEAllAttributes()
            .stream()
            .filter(attr -> !attr.isDerived() && !attr.isTransient())
            .map(attr -> describeAttribute(element, attr))
            .filter(value -> value != null && !value.isBlank())
            .sorted(Comparator.naturalOrder())
            .limit(MAX_ATTRIBUTES)
            .collect(Collectors.toList());
        return items;
    }

    private static String describeAttribute(EObject element, EAttribute attr) {
        Object value = element.eGet(attr);
        if (value == null) {
            return null;
        }
        String rendered = formatValue(value, MAX_RELATIONS);
        if (rendered == null || rendered.isBlank()) {
            return null;
        }
        return attr.getName() + " = " + rendered;
    }

    private static List<String> describeReferences(EObject element) {
        List<String> items = element.eClass()
            .getEAllReferences()
            .stream()
            .filter(ref -> !ref.isDerived() && !ref.isTransient())
            .map(ref -> describeReference(element, ref))
            .filter(value -> value != null && !value.isBlank())
            .sorted(Comparator.naturalOrder())
            .limit(MAX_REFERENCES)
            .collect(Collectors.toList());
        return items;
    }

    private static String describeReference(EObject element, EReference ref) {
        Object value = element.eGet(ref);
        if (value == null) {
            return null;
        }
        String label = ref.getName();
        if (ref.isContainment()) {
            label += " (contains)";
        }
        String rendered = formatReferenceValue(value);
        if (rendered == null || rendered.isBlank()) {
            return null;
        }
        return label + " -> " + rendered;
    }

    private static String formatReferenceValue(Object value) {
        if (value instanceof Collection<?> collection) {
            if (collection.isEmpty()) {
                return null;
            }
            String joined = collection
                .stream()
                .filter(EObject.class::isInstance)
                .map(eo -> formatElementShort((EObject) eo))
                .limit(MAX_RELATIONS)
                .collect(Collectors.joining(", "));
            return collection.size() +
                " [" +
                joined +
                (collection.size() > MAX_RELATIONS ? ", ..." : "") +
                "]";
        }
        if (value instanceof EObject eo) {
            return formatElementShort(eo);
        }
        return truncate(value.toString(), MAX_VALUE_LENGTH);
    }

    private static String formatValue(Object value, int maxItems) {
        if (value instanceof Collection<?> collection) {
            if (collection.isEmpty()) {
                return null;
            }
            String joined = collection
                .stream()
                .limit(maxItems)
                .map(item -> truncate(String.valueOf(item), MAX_VALUE_LENGTH))
                .collect(Collectors.joining(", "));
            return collection.size() +
                " [" +
                joined +
                (collection.size() > maxItems ? ", ..." : "") +
                "]";
        }
        return truncate(String.valueOf(value), MAX_VALUE_LENGTH);
    }

    private static String formatElement(EObject element) {
        String type = element.eClass().getName();
        String name = getFeatureAsString(element, "name");
        String id = getIdentifier(element);
        StringBuilder sb = new StringBuilder();
        sb.append(type);
        if (name != null && !name.isBlank()) {
            sb.append(" \"").append(truncate(name, 80)).append("\"");
        }
        if (id != null && !id.isBlank()) {
            sb.append(" (id=").append(truncate(id, 80)).append(")");
        }
        return sb.toString();
    }

    private static String formatElementShort(EObject element) {
        String type = element.eClass().getName();
        String name = getFeatureAsString(element, "name");
        if (name != null && !name.isBlank()) {
            return type + " \"" + truncate(name, 60) + "\"";
        }
        String id = getIdentifier(element);
        if (id != null && !id.isBlank()) {
            return type + " (" + truncate(id, 60) + ")";
        }
        return type;
    }

    private static String getFeatureAsString(EObject element, String name) {
        try {
            EStructuralFeature feature = element
                .eClass()
                .getEStructuralFeature(name);
            if (feature == null) {
                return null;
            }
            Object value = element.eGet(feature);
            return value != null ? value.toString() : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static String getIdentifier(EObject element) {
        String id = getFeatureAsString(element, "id");
        if (id != null && !id.isBlank()) {
            return id;
        }
        String uuid = getFeatureAsString(element, "uuid");
        if (uuid != null && !uuid.isBlank()) {
            return uuid;
        }
        try {
            var uri = EcoreUtil.getURI(element);
            if (uri != null && uri.fragment() != null) {
                return uri.fragment();
            }
        } catch (Exception ex) {
            return null;
        }
        return null;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, Math.max(0, max - 3)) + "...";
    }
}
