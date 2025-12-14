package org.polarsys.capella.ai.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.model.ActionResult.ErrorCode;
import org.polarsys.capella.ai.session.SessionBridge;

public final class Finders {

    private Finders() {}

    /** Maximum Levenshtein distance for typo suggestions */
    private static final int MAX_EDIT_DISTANCE = 3;
    /** Maximum number of alternatives to return */
    private static final int MAX_ALTERNATIVES = 5;

    public static EObject findByName(String simpleClass, String name) {
        Session session = SessionBridge.getActiveSession();
        if (session == null) {
            return null;
        }
        return findByName(session, simpleClass, name);
    }

    public static EObject findByName(
        Session session,
        String simpleClass,
        String name
    ) {
        if (session == null || simpleClass == null || name == null) {
            return null;
        }
        try {
            java.util.Collection<Resource> semantics =
                session.getSemanticResources();
            if (semantics != null && !semantics.isEmpty()) {
                return findByNameInResources(semantics, simpleClass, name);
            }
        } catch (Exception ex) {
            // Log error but fall back to ResourceSet search
            System.err.println(
                "[Finders] Error searching semantic resources for " +
                    simpleClass +
                    " '" +
                    name +
                    "': " +
                    ex.getMessage()
            );
        }
        ResourceSet rs = session.getTransactionalEditingDomain() != null
            ? session.getTransactionalEditingDomain().getResourceSet()
            : null;
        return findByName(rs, simpleClass, name);
    }

    public static EObject findByName(
        ResourceSet rs,
        String simpleClass,
        String name
    ) {
        if (rs == null || simpleClass == null || name == null) {
            return null;
        }
        try {
            for (Resource res : rs.getResources()) {
                if (res == null) {
                    continue;
                }
                var it = res.getAllContents();
                while (it.hasNext()) {
                    EObject eo = it.next();
                    try {
                        if (!eo.eClass().getName().equals(simpleClass)) {
                            continue;
                        }
                        var feature = eo.eClass().getEStructuralFeature("name");
                        if (feature != null) {
                            Object value = eo.eGet(feature);
                            if (
                                value != null &&
                                name.equals(String.valueOf(value))
                            ) {
                                return eo;
                            }
                        }
                    } catch (Exception ex) {
                        // Log per-element errors but keep searching
                        System.err.println(
                            "[Finders] Error checking element: " +
                                ex.getMessage()
                        );
                    }
                }
            }
        } catch (Exception ex) {
            // Log outer iteration error
            System.err.println(
                "[Finders] Error iterating resources for " +
                    simpleClass +
                    " '" +
                    name +
                    "': " +
                    ex.getMessage()
            );
        }
        return null;
    }

    private static EObject findByNameInResources(
        java.util.Collection<Resource> resources,
        String simpleClass,
        String name
    ) {
        try {
            for (Resource res : resources) {
                if (res == null) {
                    continue;
                }
                var it = res.getAllContents();
                while (it.hasNext()) {
                    EObject eo = it.next();
                    try {
                        if (!eo.eClass().getName().equals(simpleClass)) {
                            continue;
                        }
                        var feature = eo.eClass().getEStructuralFeature("name");
                        if (feature != null) {
                            Object value = eo.eGet(feature);
                            if (
                                value != null &&
                                name.equals(String.valueOf(value))
                            ) {
                                return eo;
                            }
                        }
                    } catch (Exception ex) {
                        // Log per-element errors but keep searching
                        System.err.println(
                            "[Finders] Error checking element in resources: " +
                                ex.getMessage()
                        );
                    }
                }
            }
        } catch (Exception ex) {
            System.err.println(
                "[Finders] Error iterating resources: " + ex.getMessage()
            );
        }
        return null;
    }

    // ========== Diagnostic Methods ==========

    /**
     * Find element by name with full diagnostic information.
     * Returns FindResult with alternatives when element is not found.
     *
     * @param session the Capella session
     * @param simpleClass the simple class name (e.g., "LogicalComponent")
     * @param name the element name to find
     * @return FindResult with element, error code, and alternatives
     */
    public static FindResult findByNameWithDiagnostic(
        Session session,
        String simpleClass,
        String name
    ) {
        // Input validation
        if (session == null) {
            return FindResult.error(
                ErrorCode.SESSION_ERROR,
                "No active Capella session"
            );
        }
        if (simpleClass == null || simpleClass.isBlank()) {
            return FindResult.error(
                ErrorCode.INVALID_INPUT,
                "Element class is required"
            );
        }
        if (name == null || name.isBlank()) {
            return FindResult.error(
                ErrorCode.INVALID_INPUT,
                "Element name is required"
            );
        }

        List<EObject> matches = new ArrayList<>();
        List<String> allNames = new ArrayList<>();

        try {
            // Try semantic resources first
            Collection<Resource> semantics = session.getSemanticResources();
            if (semantics != null && !semantics.isEmpty()) {
                collectMatchesFromResources(
                    semantics,
                    simpleClass,
                    name,
                    matches,
                    allNames
                );
            }

            // Fall back to editing domain if no semantic resources
            if (matches.isEmpty() && allNames.isEmpty()) {
                ResourceSet rs = session.getTransactionalEditingDomain() != null
                    ? session.getTransactionalEditingDomain().getResourceSet()
                    : null;
                if (rs != null) {
                    collectMatchesFromResources(
                        rs.getResources(),
                        simpleClass,
                        name,
                        matches,
                        allNames
                    );
                }
            }
        } catch (Exception ex) {
            return FindResult.error(
                ErrorCode.SESSION_ERROR,
                "Error searching model: " + ex.getMessage()
            );
        }

        // Process results
        if (matches.isEmpty()) {
            List<String> similar = findSimilarNames(allNames, name);
            if (allNames.isEmpty()) {
                return FindResult.notFound(
                    simpleClass,
                    name,
                    List.of(),
                    "No " + simpleClass + " elements exist in the model"
                );
            } else {
                String availableHint = allNames.size() <= 5
                    ? String.join(", ", allNames)
                    : String.join(", ", allNames.subList(0, 5)) + "...";
                return FindResult.notFound(
                    simpleClass,
                    name,
                    similar,
                    "No " +
                        simpleClass +
                        " named '" +
                        name +
                        "' found. Available: " +
                        availableHint
                );
            }
        }

        if (matches.size() > 1) {
            List<String> matchNames = matches
                .stream()
                .map(Finders::getElementName)
                .toList();
            return FindResult.ambiguous(
                matches.get(0),
                matches.size(),
                matchNames
            );
        }

        return FindResult.success(matches.get(0));
    }

    /**
     * Find element by name with diagnostic, using active session.
     */
    public static FindResult findByNameWithDiagnostic(
        String simpleClass,
        String name
    ) {
        Session session = SessionBridge.getActiveSession();
        return findByNameWithDiagnostic(session, simpleClass, name);
    }

    /**
     * Collect all matching elements and all names of the target class.
     */
    private static void collectMatchesFromResources(
        Collection<Resource> resources,
        String simpleClass,
        String targetName,
        List<EObject> matches,
        List<String> allNames
    ) {
        for (Resource res : resources) {
            if (res == null) {
                continue;
            }
            try {
                var it = res.getAllContents();
                while (it.hasNext()) {
                    EObject eo = it.next();
                    try {
                        String className = eo.eClass().getName();
                        // Match exact class name or class name ending with simpleClass
                        if (
                            !className.equals(simpleClass) &&
                            !className.endsWith(simpleClass)
                        ) {
                            continue;
                        }
                        String elementName = getElementName(eo);
                        if (elementName != null) {
                            allNames.add(elementName);
                            if (elementName.equals(targetName)) {
                                matches.add(eo);
                            }
                        }
                    } catch (Exception ignore) {
                        // Continue with next element
                    }
                }
            } catch (Exception ignore) {
                // Continue with next resource
            }
        }
    }

    /**
     * Find similar names using Levenshtein distance and substring matching.
     */
    private static List<String> findSimilarNames(
        List<String> allNames,
        String target
    ) {
        String targetLower = target.toLowerCase();
        List<String> similar = new ArrayList<>();

        for (String candidate : allNames) {
            if (similar.size() >= MAX_ALTERNATIVES) {
                break;
            }
            String candidateLower = candidate.toLowerCase();

            // Check substring containment
            if (
                candidateLower.contains(targetLower) ||
                targetLower.contains(candidateLower)
            ) {
                if (!similar.contains(candidate)) {
                    similar.add(candidate);
                }
                continue;
            }

            // Check Levenshtein distance
            if (
                levenshteinDistance(candidateLower, targetLower) <=
                MAX_EDIT_DISTANCE
            ) {
                if (!similar.contains(candidate)) {
                    similar.add(candidate);
                }
            }
        }

        return similar;
    }

    /**
     * Calculate Levenshtein edit distance between two strings.
     * Optimized with O(n) space complexity and early termination.
     *
     * @param a first string
     * @param b second string
     * @return edit distance, or MAX_EDIT_DISTANCE + 1 if distance exceeds threshold
     */
    private static int levenshteinDistance(String a, String b) {
        // Early termination: if length difference exceeds max distance, no point computing
        if (Math.abs(a.length() - b.length()) > MAX_EDIT_DISTANCE) {
            return MAX_EDIT_DISTANCE + 1;
        }

        // Ensure a is the shorter string to minimize space usage
        if (a.length() > b.length()) {
            String temp = a;
            a = b;
            b = temp;
        }

        int m = a.length();
        int n = b.length();

        // Use single row DP - O(n) space instead of O(n*m)
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];

        // Initialize first row
        for (int j = 0; j <= n; j++) {
            prev[j] = j;
        }

        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            int minInRow = curr[0];

            for (int j = 1; j <= n; j++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(
                    Math.min(prev[j] + 1, curr[j - 1] + 1),
                    prev[j - 1] + cost
                );
                minInRow = Math.min(minInRow, curr[j]);
            }

            // Early termination: if minimum value in current row exceeds threshold,
            // the final distance will also exceed it
            if (minInRow > MAX_EDIT_DISTANCE) {
                return MAX_EDIT_DISTANCE + 1;
            }

            // Swap rows
            int[] temp = prev;
            prev = curr;
            curr = temp;
        }

        return prev[n];
    }

    /**
     * Get the name of an element via reflection.
     */
    private static String getElementName(EObject eo) {
        if (eo == null) {
            return null;
        }
        try {
            var feature = eo.eClass().getEStructuralFeature("name");
            if (feature != null) {
                Object value = eo.eGet(feature);
                if (value != null) {
                    return String.valueOf(value);
                }
            }
        } catch (Exception ex) {
            // Best-effort name extraction for display purposes
            System.err.println(
                "[Finders] Error getting element name: " + ex.getMessage()
            );
        }
        return null;
    }
}
