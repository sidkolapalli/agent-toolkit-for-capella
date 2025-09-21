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
        CacheEntry cached = CACHE.get(session);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.timestamp < CACHE_TTL_MS) {
            return cached.summary;
        }

        Object la = ArchitectureLocator.findTargetElement(session, "LA");
        Object sa = ArchitectureLocator.findTargetElement(session, "SA");
        Object pa = ArchitectureLocator.findTargetElement(session, "PA");

        int comp = countByClass(session, "LogicalComponent");
        int func = countByClass(session, "LogicalFunction");
        int cex = countByClass(session, "ComponentExchange");
        int fex = countByClass(session, "FunctionalExchange");

        Collection<DRepresentation> reps = DialectManager.INSTANCE.getAllRepresentations(session);
        int diagCount = reps != null ? reps.size() : 0;
        List<DiagramInfo> activeDiagrams = DiagramBrowser.listActive();
        String diagNames = activeDiagrams.stream().limit(5).map(d -> d.name).collect(Collectors.joining(", "));

        StringBuilder sb = new StringBuilder();
        sb.append("Model Summary\n");
        sb.append("- Layers present: ").append(fmt(la,"LA")).append(", ").append(fmt(sa,"SA")).append(", ").append(fmt(pa,"PA")).append("\n");
        sb.append("- LogicalComponents: ").append(comp).append("\n");
        sb.append("- LogicalFunctions: ").append(func).append("\n");
        sb.append("- ComponentExchanges: ").append(cex).append("\n");
        sb.append("- FunctionalExchanges: ").append(fex).append("\n");
        sb.append("- Diagrams: ").append(diagCount);
        if (!diagNames.isBlank()) sb.append(" (e.g., ").append(diagNames).append(")");

        String summary = sb.toString();
        CACHE.put(session, new CacheEntry(summary, now));
        return summary;
    }

    public static void invalidate(Session session) {
        if (session != null) {
            CACHE.remove(session);
        }
    }

    private static String fmt(Object o, String name) { return o != null ? name : "-"; }

    private static int countByClass(Session session, String simpleName) {
        var rs = session.getTransactionalEditingDomain() != null ? session.getTransactionalEditingDomain().getResourceSet() : null;
        if (rs == null) return 0;
        int c = 0;
        try {
            for (var res : rs.getResources()) {
                if (res == null) continue;
                var it = res.getAllContents();
                while (it.hasNext()) {
                    var eo = it.next();
                    if (eo.eClass().getName().equals(simpleName)) c++;
                }
            }
        } catch (Exception ignore) {}
        return c;
    }
}
