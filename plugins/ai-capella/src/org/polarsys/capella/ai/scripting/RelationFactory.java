package org.polarsys.capella.ai.scripting;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.model.ArchitectureLocator;
import org.polarsys.capella.ai.model.ElementFactory;
import org.polarsys.capella.ai.model.FindResult;
import org.polarsys.capella.ai.session.SessionSafeguards;

/**
 * Factory for creating relationships between Capella model elements.
 * Supports dynamic linking based on element types and relation names.
 *
 * <p>Relation types supported:
 * <ul>
 *   <li>{@code FunctionalExchange} - Exchange between functions</li>
 *   <li>{@code ComponentExchange} - Exchange between components</li>
 *   <li>{@code allocation} - Function-to-component allocation</li>
 *   <li>{@code involvement} - Activity involvement in capability</li>
 *   <li>{@code realization} - Traceability between layers</li>
 *   <li>{@code requirement} - Requirement linkage</li>
 * </ul>
 */
public final class RelationFactory {

    private RelationFactory() {}

    /**
     * Mapping of relation type names to their creation strategies.
     */
    private static final Map<String, RelationStrategy> STRATEGIES =
        Map.ofEntries(
            Map.entry(
                "functionalexchange",
                RelationFactory::createFunctionalExchange
            ),
            Map.entry(
                "componentexchange",
                RelationFactory::createComponentExchange
            ),
            Map.entry("allocation", RelationFactory::createAllocation),
            Map.entry("involvement", RelationFactory::createInvolvement),
            Map.entry("realization", RelationFactory::createRealization),
            Map.entry("trace", RelationFactory::createRealization),
            Map.entry(
                "functionrealization",
                RelationFactory::createFunctionRealization
            ),
            Map.entry(
                "componentrealization",
                RelationFactory::createComponentRealization
            ),
            Map.entry("requirement", RelationFactory::createRequirementLink),
            Map.entry("reference", RelationFactory::createGenericReference)
        );

    /**
     * Create a relationship between source and target elements.
     *
     * @param session the Capella session
     * @param source the source element
     * @param target the target element
     * @param relationType the type of relationship to create
     * @return the created relationship element, or null if creation failed
     */
    public static EObject link(
        Session session,
        EObject source,
        EObject target,
        String relationType
    ) {
        if (session == null || source == null || target == null) {
            return null;
        }
        if (relationType == null || relationType.isBlank()) {
            relationType = "reference";
        }

        String normalizedType = relationType
            .toLowerCase(Locale.ROOT)
            .replace("_", "")
            .replace("-", "");
        RelationStrategy strategy = STRATEGIES.get(normalizedType);

        if (strategy != null) {
            return strategy.create(session, source, target, relationType);
        }

        // Fallback: try to find matching reference by name
        return createGenericReference(session, source, target, relationType);
    }

    /**
     * Create a FunctionalExchange between two functions.
     */
    private static EObject createFunctionalExchange(
        Session session,
        EObject source,
        EObject target,
        String name
    ) {
        return SessionSafeguards.runWrite(
            session,
            "Create FunctionalExchange",
            () -> {
                // Find container for the exchange (typically the function pkg or parent)
                EObject container = findExchangeContainer(
                    session,
                    source,
                    "FunctionalExchange"
                );
                if (container == null) {
                    container = source.eContainer();
                }

                EObject exchange = ElementFactory.createUnder(
                    session,
                    container,
                    "FunctionalExchange",
                    name != null && !name.equalsIgnoreCase("functionalexchange")
                        ? name
                        : "Exchange"
                );

                if (exchange != null) {
                    assignEndpoint(exchange, "source", source);
                    assignEndpoint(exchange, "target", target);
                }
                return exchange;
            }
        );
    }

    /**
     * Create a ComponentExchange between two components.
     */
    private static EObject createComponentExchange(
        Session session,
        EObject source,
        EObject target,
        String name
    ) {
        return SessionSafeguards.runWrite(
            session,
            "Create ComponentExchange",
            () -> {
                EObject container = findExchangeContainer(
                    session,
                    source,
                    "ComponentExchange"
                );
                if (container == null) {
                    container = source.eContainer();
                }

                EObject exchange = ElementFactory.createUnder(
                    session,
                    container,
                    "ComponentExchange",
                    name != null && !name.equalsIgnoreCase("componentexchange")
                        ? name
                        : "Exchange"
                );

                if (exchange != null) {
                    assignEndpoint(exchange, "source", source);
                    assignEndpoint(exchange, "target", target);
                }
                return exchange;
            }
        );
    }

    /**
     * Create an allocation relationship (e.g., function to component).
     */
    private static EObject createAllocation(
        Session session,
        EObject source,
        EObject target,
        String name
    ) {
        return SessionSafeguards.runWrite(session, "Create allocation", () -> {
            boolean linked = addReference(
                source,
                target,
                "allocated",
                "allocation",
                "function"
            );
            if (!linked) {
                // Try reverse direction
                linked = addReference(
                    target,
                    source,
                    "allocating",
                    "allocation",
                    "component"
                );
            }
            return linked ? source : null;
        });
    }

    /**
     * Create an involvement relationship (e.g., activity to capability).
     */
    private static EObject createInvolvement(
        Session session,
        EObject source,
        EObject target,
        String name
    ) {
        return SessionSafeguards.runWrite(session, "Create involvement", () -> {
            // Try to find involvement reference on either side
            boolean linked = addReference(
                target,
                source,
                "involved",
                "involvement",
                "activity"
            );
            if (!linked) {
                linked = addReference(
                    source,
                    target,
                    "capability",
                    "involvement"
                );
            }
            return linked ? source : null;
        });
    }

    /**
     * Create a realization/trace relationship between layers.
     */
    private static EObject createRealization(
        Session session,
        EObject source,
        EObject target,
        String name
    ) {
        return SessionSafeguards.runWrite(session, "Create realization", () -> {
            boolean linked = addReference(
                source,
                target,
                "realiz",
                "trace",
                "refined"
            );
            if (!linked) {
                linked = addReference(target, source, "realizing", "refining");
            }
            return linked ? source : null;
        });
    }

    /**
     * Create a FunctionRealization between functions across layers.
     * Source is the lower-level function (LA/PA), target is the higher-level function (SA/LA).
     */
    private static EObject createFunctionRealization(
        Session session,
        EObject source,
        EObject target,
        String name
    ) {
        return SessionSafeguards.runWrite(
            session,
            "Create FunctionRealization",
            () -> {
                try {
                    var faFactory =
                        org.polarsys.capella.core.data.fa.FaFactory.eINSTANCE;
                    var realization = faFactory.createFunctionRealization();
                    realization.setTargetElement(
                        (org.polarsys.capella.common.data.modellingcore.TraceableElement) target
                    );
                    realization.setSourceElement(
                        (org.polarsys.capella.common.data.modellingcore.TraceableElement) source
                    );

                    // Add to source function's ownedFunctionRealizations
                    var realizationsRef = source
                        .eClass()
                        .getEStructuralFeature("ownedFunctionRealizations");
                    if (realizationsRef != null) {
                        @SuppressWarnings("unchecked")
                        var list = (java.util.List<EObject>) source.eGet(
                            realizationsRef
                        );
                        list.add(realization);
                        return realization;
                    }
                    return null;
                } catch (Exception e) {
                    return null;
                }
            }
        );
    }

    /**
     * Create a ComponentRealization between components across layers.
     * Source is the lower-level component (LA/PA), target is the higher-level component (SA/LA).
     */
    private static EObject createComponentRealization(
        Session session,
        EObject source,
        EObject target,
        String name
    ) {
        return SessionSafeguards.runWrite(
            session,
            "Create ComponentRealization",
            () -> {
                try {
                    var csFactory =
                        org.polarsys.capella.core.data.cs.CsFactory.eINSTANCE;
                    var realization = csFactory.createComponentRealization();
                    realization.setTargetElement(
                        (org.polarsys.capella.common.data.modellingcore.TraceableElement) target
                    );
                    realization.setSourceElement(
                        (org.polarsys.capella.common.data.modellingcore.TraceableElement) source
                    );

                    // Add to source component's ownedComponentRealizations
                    var realizationsRef = source
                        .eClass()
                        .getEStructuralFeature("ownedComponentRealizations");
                    if (realizationsRef != null) {
                        @SuppressWarnings("unchecked")
                        var list = (java.util.List<EObject>) source.eGet(
                            realizationsRef
                        );
                        list.add(realization);
                        return realization;
                    }
                    return null;
                } catch (Exception e) {
                    return null;
                }
            }
        );
    }

    /**
     * Create a requirement link.
     */
    private static EObject createRequirementLink(
        Session session,
        EObject source,
        EObject target,
        String name
    ) {
        return SessionSafeguards.runWrite(
            session,
            "Create requirement link",
            () -> {
                boolean linked = addReference(
                    source,
                    target,
                    "requirement",
                    "satisfy",
                    "trace"
                );
                if (!linked) {
                    linked = addReference(target, source, "element", "satisfy");
                }
                return linked ? source : null;
            }
        );
    }

    /**
     * Create a generic reference by finding a matching feature.
     */
    private static EObject createGenericReference(
        Session session,
        EObject source,
        EObject target,
        String hintName
    ) {
        return SessionSafeguards.runWrite(session, "Create reference", () -> {
            // First try with the hint name
            if (hintName != null && !hintName.isBlank()) {
                boolean linked = addReference(
                    source,
                    target,
                    hintName.toLowerCase(Locale.ROOT)
                );
                if (linked) {
                    return source;
                }
            }

            // Try to find any compatible reference
            for (EStructuralFeature feature : source
                .eClass()
                .getEAllStructuralFeatures()) {
                if (
                    !(feature instanceof EReference ref) || ref.isContainment()
                ) {
                    continue;
                }
                if (ref.getEReferenceType().isInstance(target)) {
                    Object current = source.eGet(ref);
                    if (ref.isMany()) {
                        @SuppressWarnings("unchecked")
                        Collection<Object> list = (Collection<Object>) current;
                        if (!list.contains(target)) {
                            list.add(target);
                        }
                    } else if (current == null) {
                        source.eSet(ref, target);
                    } else {
                        continue; // Already set, try next
                    }
                    return source;
                }
            }
            return null;
        });
    }

    // ========== Helper Methods ==========

    /**
     * Find the appropriate container for an exchange element.
     */
    private static EObject findExchangeContainer(
        Session session,
        EObject nearElement,
        String exchangeType
    ) {
        // Navigate up to find a suitable container
        EObject current = nearElement;
        while (current != null) {
            EClass eClass = current.eClass();
            // Look for a package or architecture layer
            String className = eClass.getName();
            if (
                className.contains("Pkg") ||
                className.contains("Architecture") ||
                className.contains("Analysis")
            ) {
                // Check if it can contain exchanges
                EClass targetClass = ElementFactory.findEClass(exchangeType);
                if (targetClass != null) {
                    for (EStructuralFeature f : eClass.getEAllStructuralFeatures()) {
                        if (
                            f instanceof EReference ref &&
                            ref.isContainment() &&
                            ref.isMany()
                        ) {
                            if (
                                ref
                                    .getEReferenceType()
                                    .isSuperTypeOf(targetClass)
                            ) {
                                return current;
                            }
                        }
                    }
                }
            }
            current = current.eContainer();
        }

        // Fall back to architecture root
        String sourceClassName = nearElement.eClass().getName();
        String layer = guessLayerFromClassName(sourceClassName);
        FindResult layerResult = ArchitectureLocator.findLayerWithDiagnostic(
            session,
            layer
        );
        return layerResult.success() ? layerResult.element() : null;
    }

    /**
     * Guess the architecture layer from an element's class name.
     */
    private static String guessLayerFromClassName(String className) {
        if (className == null) return "SA";
        String lower = className.toLowerCase(Locale.ROOT);
        if (
            lower.contains("operational") || lower.startsWith("oa")
        ) return "OA";
        if (lower.contains("logical") || lower.startsWith("la")) return "LA";
        if (lower.contains("physical") || lower.startsWith("pa")) return "PA";
        return "SA"; // Default to System Analysis
    }

    /**
     * Add a reference from source to target using keyword matching.
     */
    private static boolean addReference(
        EObject source,
        EObject target,
        String... keywords
    ) {
        for (EStructuralFeature feature : source
            .eClass()
            .getEAllStructuralFeatures()) {
            if (!(feature instanceof EReference ref) || ref.isContainment()) {
                continue;
            }
            if (!ref.getEReferenceType().isInstance(target)) {
                continue;
            }

            String name = feature.getName().toLowerCase(Locale.ROOT);
            boolean matches = keywords.length == 0;
            for (String keyword : keywords) {
                if (name.contains(keyword.toLowerCase(Locale.ROOT))) {
                    matches = true;
                    break;
                }
            }
            if (!matches) {
                continue;
            }

            Object current = source.eGet(ref);
            if (ref.isMany()) {
                @SuppressWarnings("unchecked")
                Collection<Object> list = (Collection<Object>) current;
                if (!list.contains(target)) {
                    list.add(target);
                }
                return true;
            }
            source.eSet(ref, target);
            return true;
        }
        return false;
    }

    /**
     * Assign an endpoint (source/target) to an exchange element.
     */
    private static void assignEndpoint(
        EObject exchange,
        String role,
        EObject endpoint
    ) {
        if (exchange == null || endpoint == null) {
            return;
        }
        for (EStructuralFeature feature : exchange
            .eClass()
            .getEAllStructuralFeatures()) {
            if (!(feature instanceof EReference ref) || ref.isContainment()) {
                continue;
            }
            String name = feature.getName().toLowerCase(Locale.ROOT);
            boolean matches =
                (role.equals("source") && name.contains("source")) ||
                (role.equals("target") && name.contains("target"));
            if (!matches) {
                continue;
            }
            if (ref.getEReferenceType().isInstance(endpoint)) {
                if (ref.isMany()) {
                    @SuppressWarnings("unchecked")
                    Collection<Object> list = (Collection<
                        Object
                    >) exchange.eGet(ref);
                    if (!list.contains(endpoint)) {
                        list.add(endpoint);
                    }
                } else {
                    exchange.eSet(ref, endpoint);
                }
                return;
            }
        }
    }

    /**
     * Functional interface for relation creation strategies.
     */
    @FunctionalInterface
    private interface RelationStrategy {
        EObject create(
            Session session,
            EObject source,
            EObject target,
            String name
        );
    }
}
