package org.polarsys.capella.ai.model;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.stream.Collectors;

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
    private static final long CACHE_TTL_MS = 5_000L;
    private static final Map<Session, CacheEntry> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private static final class CacheEntry {
        final String summary;
        final long timestamp;

        CacheEntry(String summary, long timestamp) {
            this.summary = summary;
            this.timestamp = timestamp;
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
        long now = System.currentTimeMillis();
        CacheEntry cached = CACHE.get(session);
        if (cached != null && now - cached.timestamp < CACHE_TTL_MS) {
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
        sb.append(dependencyHighlights(analysis.dependencyGraph())).append("\n");
        sb.append("- Diagrams: ").append(diagramCount);
        if (!diagramExamples.isBlank()) {
            sb.append(" (e.g., ").append(diagramExamples).append(")");
        }

        String summary = sb.toString();
        CACHE.put(session, new CacheEntry(summary, now));
        return summary;
    }

    public static void invalidate(Session session) {
        if (session != null) {
            CACHE.remove(session);
            ModelIndex.invalidate(session);
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
}
