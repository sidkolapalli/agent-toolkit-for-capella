package org.polarsys.capella.ai.browse;

import org.eclipse.sirius.business.api.dialect.DialectManager;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.diagram.DDiagram;
import org.eclipse.sirius.viewpoint.DRepresentation;
import org.polarsys.capella.ai.session.SessionBridge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Helpers to list diagrams from the active session. */
public final class DiagramBrowser {
    private DiagramBrowser() {}

    public static class DiagramInfo {
        public final String uid;
        public final String name;
        public final String type;

        public DiagramInfo(String uid, String name, String type) {
            this.uid = uid;
            this.name = name;
            this.type = type;
        }

        @Override
        public String toString() {
            return String.format("%s [%s] (%s)", name, type, uid);
        }
    }

    public static List<DiagramInfo> listActive() {
        Session s = SessionBridge.getActiveSession();
        List<DiagramInfo> out = new ArrayList<>();
        if (s == null) return out;
        Collection<DRepresentation> reps = DialectManager.INSTANCE.getAllRepresentations(s);
        for (DRepresentation r : reps) {
            String type = r.getClass().getSimpleName();
            if (r instanceof DDiagram) type = ((DDiagram) r).eClass().getName();
            out.add(new DiagramInfo(r.getUid(), r.getName(), type));
        }
        return out;
    }
}
