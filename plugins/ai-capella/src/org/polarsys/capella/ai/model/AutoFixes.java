package org.polarsys.capella.ai.model;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.session.SessionSafeguards;

/**
 * Minimal, safe auto-fixers to clean up common issues without heavy validation packs.
 * All operations are advisory and capped to small batches.
 */
public final class AutoFixes {

    private AutoFixes() {}

    public static final class FixResult {

        public final int orphanComponentExchangesRemoved;
        public final int orphanFunctionalExchangesRemoved;

        FixResult(int cex, int fex) {
            this.orphanComponentExchangesRemoved = cex;
            this.orphanFunctionalExchangesRemoved = fex;
        }
    }

    /** Apply a small set of safe cleanups; returns counts. */
    public static FixResult apply(Session session) {
        if (
            session == null || session.getTransactionalEditingDomain() == null
        ) {
            return new FixResult(0, 0);
        }
        ResourceSet rs = session
            .getTransactionalEditingDomain()
            .getResourceSet();
        final List<EObject> orphanComponentExchanges = new ArrayList<>();
        final List<EObject> orphanFunctionalExchanges = new ArrayList<>();
        final int cap =
            org.polarsys.capella.ai.preferences.AdvancedPreferences.getMaxAutoFixRemovals();

        try {
            for (Resource res : rs.getResources()) {
                if (res == null) continue;
                for (EObject root : res.getContents()) {
                    ModelAnalytics.visit(root, eo -> {
                        if (
                            orphanComponentExchanges.size() >= cap &&
                            orphanFunctionalExchanges.size() >= cap
                        ) return;
                        String cls = eo.eClass().getName();
                        if (cls.contains("ComponentExchange")) {
                            if (
                                missingEndpoint(eo, "source") ||
                                missingEndpoint(eo, "target")
                            ) {
                                orphanComponentExchanges.add(eo);
                            }
                        } else if (cls.contains("FunctionalExchange")) {
                            if (
                                missingEndpoint(eo, "source") ||
                                missingEndpoint(eo, "target")
                            ) {
                                orphanFunctionalExchanges.add(eo);
                            }
                        }
                    });
                }
            }
        } catch (Exception ex) {
            System.err.println(
                "[AutoFixes] Error scanning for orphan exchanges: " +
                    ex.getMessage()
            );
        }

        // Apply removals in a single write transaction
        SessionSafeguards.runWrite(session, "Auto-fix orphan exchanges", () -> {
            for (EObject eo : orphanComponentExchanges) {
                delete(session, eo);
            }
            for (EObject eo : orphanFunctionalExchanges) {
                delete(session, eo);
            }
            return true;
        });
        return new FixResult(
            orphanComponentExchanges.size(),
            orphanFunctionalExchanges.size()
        );
    }

    private static boolean missingEndpoint(EObject exchange, String role) {
        // Heuristic: find a non-containment reference whose name hints at source/target
        for (EStructuralFeature f : exchange
            .eClass()
            .getEAllStructuralFeatures()) {
            if (
                f instanceof org.eclipse.emf.ecore.EReference ref &&
                !ref.isContainment()
            ) {
                String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                boolean src = role.equals("source");
                if (
                    src
                        ? (n.contains("src") || n.contains("source"))
                        : (n.contains("tgt") || n.contains("target"))
                ) {
                    Object v = exchange.eGet(f);
                    if (v == null) return true;
                    if (
                        v instanceof java.util.Collection<?> coll
                    ) return coll.isEmpty();
                    return false;
                }
            }
        }
        // If we cannot resolve endpoints by heuristic, do not delete
        return false;
    }

    private static void delete(Session session, EObject eo) {
        try {
            String fragment = String.valueOf(
                org.eclipse.emf.ecore.util.EcoreUtil.getURI(eo).fragment()
            );
            if (fragment != null && !fragment.isBlank()) {
                ModelOps.deleteByUuid(session, fragment);
            }
        } catch (Exception ex) {
            System.err.println(
                "[AutoFixes] Error deleting orphan element: " + ex.getMessage()
            );
        }
    }
}
