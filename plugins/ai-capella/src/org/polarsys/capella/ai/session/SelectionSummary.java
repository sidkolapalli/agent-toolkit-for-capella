package org.polarsys.capella.ai.session;

import java.util.List;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.polarsys.capella.ai.privacy.Tokenizer;

/** Formats {@link SelectionContext} for prompt injection. */
public final class SelectionSummary {

    private static final int MAX_ELEMENTS = 5;
    private static final int MAX_ID_CHARS = 18;

    private SelectionSummary() {}

    public static String summarize(
        SelectionContext context,
        Tokenizer tokenizer
    ) {
        if (context == null || (!context.hasSelection() && !context.hasDiagram())) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Current Context\n\n");

        if (context.hasDiagram()) {
            String name = safe(context.getActiveDiagramName());
            String uid = safe(context.getActiveDiagramUid());
            String type = safe(context.getActiveDiagramType());
            String diagLine = "Active Diagram: \"" +
                (name.isBlank() ? "Unnamed" : name) +
                "\"" +
                (!uid.isBlank() ? " (uid: " + truncateId(uid) + ")" : "") +
                (!type.isBlank() ? " [" + type + "]" : "");
            sb.append(diagLine).append("\n\n");
        }

        List<EObject> selected = context.getSelectedElements();
        if (selected != null && !selected.isEmpty()) {
            sb.append("Selected Elements (").append(selected.size()).append("):\n");
            int count = 0;
            for (EObject eo : selected) {
                if (eo == null) continue;
                if (count >= MAX_ELEMENTS) break;
                sb.append("- ").append(describe(eo)).append("\n");
                count++;
            }
            int remaining = selected.size() - count;
            if (remaining > 0) {
                sb.append("- +").append(remaining).append(" more\n");
            }
            sb.append("\n");
            sb.append("Use `selection` (List<EObject>) in scripts to reference these elements deterministically.\n");
        }

        String out = sb.toString();
        return tokenizer != null ? tokenizer.tokenizeText(out) : out;
    }

    private static String describe(EObject eo) {
        String type = eo.eClass() != null ? eo.eClass().getName() : "EObject";
        String name = extractName(eo);
        String id = extractStableId(eo);
        String label = type;
        if (name != null && !name.isBlank()) {
            label += " \"" + name + "\"";
        }
        if (id != null && !id.isBlank()) {
            label += " (id: " + truncateId(id) + ")";
        }
        return label;
    }

    private static String extractName(EObject eo) {
        try {
            EStructuralFeature f = eo.eClass().getEStructuralFeature("name");
            if (f != null) {
                Object v = eo.eGet(f);
                return v != null ? String.valueOf(v) : null;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String extractStableId(EObject eo) {
        if (eo == null || eo.eClass() == null) {
            return null;
        }
        try {
            EStructuralFeature idFeat = eo.eClass().getEStructuralFeature("id");
            if (idFeat != null) {
                Object v = eo.eGet(idFeat);
                if (v != null) return String.valueOf(v);
            }
        } catch (Exception ignored) {}
        try {
            EStructuralFeature uuidFeat = eo.eClass().getEStructuralFeature("uuid");
            if (uuidFeat != null) {
                Object v = eo.eGet(uuidFeat);
                if (v != null) return String.valueOf(v);
            }
        } catch (Exception ignored) {}
        try {
            var uri = org.eclipse.emf.ecore.util.EcoreUtil.getURI(eo);
            if (uri != null && uri.fragment() != null) {
                return uri.fragment();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String truncateId(String id) {
        if (id == null) return "";
        String s = id.trim();
        if (s.length() <= MAX_ID_CHARS) return s;
        return s.substring(0, MAX_ID_CHARS - 3) + "...";
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}

