package org.polarsys.capella.ai.model;

import java.util.Collection;
import java.util.stream.Collectors;

import org.eclipse.sirius.business.api.dialect.DialectManager;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.viewpoint.DRepresentation;
import org.polarsys.capella.ai.browse.DiagramBrowser;
import org.polarsys.capella.ai.session.SessionBridge;

public final class ModelSummary {
    private ModelSummary() {}

    public static String summarize() {
        Session s = SessionBridge.getActiveSession();
        if (s == null) return "No active model session.";
        Object la = ArchitectureLocator.findTargetElement(s, "LA");
        Object sa = ArchitectureLocator.findTargetElement(s, "SA");
        Object pa = ArchitectureLocator.findTargetElement(s, "PA");

        int comp = countByClass(s, "LogicalComponent");
        int func = countByClass(s, "LogicalFunction");
        int cex = countByClass(s, "ComponentExchange");
        int fex = countByClass(s, "FunctionalExchange");

        Collection<DRepresentation> reps = DialectManager.INSTANCE.getAllRepresentations(s);
        int diagCount = reps != null ? reps.size() : 0;
        String diagNames = DiagramBrowser.listActive().stream().limit(5).map(d -> d.name).collect(Collectors.joining(", "));

        StringBuilder sb = new StringBuilder();
        sb.append("Model Summary\n");
        sb.append("- Layers present: ").append(fmt(la,"LA")).append(", ").append(fmt(sa,"SA")).append(", ").append(fmt(pa,"PA")).append("\n");
        sb.append("- LogicalComponents: ").append(comp).append("\n");
        sb.append("- LogicalFunctions: ").append(func).append("\n");
        sb.append("- ComponentExchanges: ").append(cex).append("\n");
        sb.append("- FunctionalExchanges: ").append(fex).append("\n");
        sb.append("- Diagrams: ").append(diagCount);
        if (!diagNames.isBlank()) sb.append(" (e.g., ").append(diagNames).append(")");
        return sb.toString();
    }

    private static String fmt(Object o, String name) { return o != null ? name : "-"; }

    private static int countByClass(Session s, String simpleName) {
        var rs = s.getTransactionalEditingDomain() != null ? s.getTransactionalEditingDomain().getResourceSet() : null;
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

