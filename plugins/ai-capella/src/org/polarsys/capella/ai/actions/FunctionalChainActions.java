package org.polarsys.capella.ai.actions;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.model.ArchitectureLocator;
import org.polarsys.capella.ai.model.ElementFactory;
import org.polarsys.capella.ai.model.Finders;
import org.polarsys.capella.ai.model.ModelSummary;
import org.polarsys.capella.ai.model.index.ModelIndex;
import org.polarsys.capella.ai.session.SessionSafeguards;

/** Helpers for Functional Chain authoring actions. */
public final class FunctionalChainActions {
    private static final List<String> FUNCTION_CLASSES = List.of(
            "LogicalFunction",
            "SystemFunction",
            "PhysicalFunction"
    );

    private FunctionalChainActions() {
    }

    public static boolean createChain(Session session, String layer, String name, String description) {
        if (session == null || name == null || name.isBlank()) {
            return false;
        }
        return SessionSafeguards.runWrite(session, "Create functional chain", () -> {
            EObject root = ArchitectureLocator.findTargetElement(session, resolveLayer(layer, "LA"));
            if (root == null) {
                return false;
            }
            EObject chain = ElementFactory.createUnder(session, root, "FunctionalChain", name);
            if (chain == null) {
                return false;
            }
            setIfPresent(chain, "description", description);
            invalidate(session);
            return true;
        });
    }

    public static boolean addFunction(Session session, String chainName, String functionName, String preferredClass) {
        if (session == null || chainName == null || functionName == null) {
            return false;
        }
        return SessionSafeguards.runWrite(session, "Extend functional chain", () -> {
            EObject chain = Finders.findByName(session, "FunctionalChain", chainName);
            if (chain == null) {
                return false;
            }
            EObject function = findFunction(session, functionName, preferredClass);
            if (function == null) {
                return false;
            }
            if (hasInvolvement(chain, function)) {
                return true;
            }
            boolean linked = createInvolvement(chain, function, functionName);
            if (linked) {
                invalidate(session);
            }
            return linked;
        });
    }

    public static boolean addExchange(Session session, String chainName, String exchangeName) {
        if (session == null || chainName == null || exchangeName == null) {
            return false;
        }
        return SessionSafeguards.runWrite(session, "Sequence functional exchange", () -> {
            EObject chain = Finders.findByName(session, "FunctionalChain", chainName);
            if (chain == null) {
                return false;
            }
            EObject exchange = Finders.findByName(session, "FunctionalExchange", exchangeName);
            if (exchange == null) {
                return false;
            }
            if (linkReference(chain, exchange, "exchange")) {
                invalidate(session);
                return true;
            }
            // fallback: create involvement element pointing to exchange
            if (hasInvolvement(chain, exchange)) {
                return true;
            }
            boolean linked = createInvolvement(chain, exchange, exchangeName);
            if (linked) {
                invalidate(session);
            }
            return linked;
        });
    }

    public static boolean attachScenario(Session session, String chainName, String scenarioName) {
        if (session == null || chainName == null || scenarioName == null) {
            return false;
        }
        return SessionSafeguards.runWrite(session, "Attach scenario to chain", () -> {
            EObject chain = Finders.findByName(session, "FunctionalChain", chainName);
            if (chain == null) {
                return false;
            }
            EObject scenario = findScenario(session, scenarioName);
            if (scenario == null) {
                return false;
            }
            if (linkReference(chain, scenario, "scenario", "sequence", "diagram")) {
                invalidate(session);
                return true;
            }
            return false;
        });
    }

    private static boolean createInvolvement(EObject chain, EObject target, String nameHint) {
        EClass involvementClass = ElementFactory.findEClass("FunctionalChainInvolvement");
        if (involvementClass == null) {
            return false;
        }
        EReference containment = findContainment(chain, involvementClass);
        if (containment == null) {
            return false;
        }
        EObject involvement = involvementClass.getEPackage().getEFactoryInstance().create(involvementClass);
        setIfPresent(involvement, "name", nameHint);
        assignReference(involvement, target, "involved", "function", "exchange");
        @SuppressWarnings("unchecked")
        List<EObject> list = (List<EObject>) chain.eGet(containment);
        list.add(involvement);
        return true;
    }

    private static boolean hasInvolvement(EObject chain, EObject target) {
        for (EObject child : chain.eContents()) {
            if (child == null) {
                continue;
            }
            if (!child.eClass().getName().contains("FunctionalChainInvolvement")) {
                continue;
            }
            if (references(child, target)) {
                return true;
            }
        }
        return false;
    }

    private static boolean references(EObject source, EObject target) {
        for (EStructuralFeature feature : source.eClass().getEAllStructuralFeatures()) {
            if (!(feature instanceof EReference ref) || ref.isContainment()) {
                continue;
            }
            Object value = source.eGet(ref);
            if (ref.isMany()) {
                if (value instanceof Collection<?>) {
                    if (((Collection<?>) value).contains(target)) {
                        return true;
                    }
                }
            } else if (value == target) {
                return true;
            }
        }
        return false;
    }

    private static boolean linkReference(EObject source, EObject target, String... keywords) {
        for (EStructuralFeature feature : source.eClass().getEAllStructuralFeatures()) {
            if (!(feature instanceof EReference ref) || ref.isContainment()) {
                continue;
            }
            if (!ref.isMany()) {
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
            if (!ref.getEReferenceType().isInstance(target) && !ref.getEReferenceType().isSuperTypeOf(target.eClass())) {
                continue;
            }
            Object current = source.eGet(ref);
            if (current instanceof Collection<?>) {
                @SuppressWarnings("unchecked")
                Collection<EObject> collection = (Collection<EObject>) current;
                if (!collection.contains(target)) {
                    collection.add(target);
                }
                return true;
            }
        }
        return false;
    }

    private static void assignReference(EObject source, EObject target, String... keywords) {
        for (EStructuralFeature feature : source.eClass().getEAllStructuralFeatures()) {
            if (!(feature instanceof EReference ref) || ref.isContainment()) {
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
            if (!ref.getEReferenceType().isInstance(target) && !ref.getEReferenceType().isSuperTypeOf(target.eClass())) {
                continue;
            }
            if (ref.isMany()) {
                Object current = source.eGet(ref);
                if (current instanceof Collection<?>) {
                    @SuppressWarnings("unchecked")
                    Collection<EObject> list = (Collection<EObject>) current;
                    if (!list.contains(target)) {
                        list.add(target);
                    }
                }
            } else {
                source.eSet(ref, target);
            }
            return;
        }
    }

    private static EReference findContainment(EObject container, EClass type) {
        for (EStructuralFeature feature : container.eClass().getEAllStructuralFeatures()) {
            if (feature instanceof EReference ref && ref.isContainment() && ref.isMany()) {
                if (ref.getEReferenceType().isSuperTypeOf(type) || ref.getEReferenceType() == type) {
                    return ref;
                }
            }
        }
        return null;
    }

    private static EObject findFunction(Session session, String name, String preferredClass) {
        if (preferredClass != null && !preferredClass.isBlank()) {
            EObject fn = Finders.findByName(session, preferredClass, name);
            if (fn != null) {
                return fn;
            }
        }
        for (String cls : FUNCTION_CLASSES) {
            EObject fn = Finders.findByName(session, cls, name);
            if (fn != null) {
                return fn;
            }
        }
        return null;
    }

    private static EObject findScenario(Session session, String name) {
        List<String> scenarioClasses = List.of(
                "Scenario",
                "FunctionalScenario",
                "OperationalScenario",
                "SystemScenario"
        );
        for (String cls : scenarioClasses) {
            EObject scenario = Finders.findByName(session, cls, name);
            if (scenario != null) {
                return scenario;
            }
        }
        return null;
    }

    private static void setIfPresent(EObject element, String featureName, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        EStructuralFeature feature = element.eClass().getEStructuralFeature(featureName);
        if (feature != null && feature.isChangeable()) {
            element.eSet(feature, value);
        }
    }

    private static String resolveLayer(String requested, String fallback) {
        if (requested == null || requested.isBlank()) {
            return fallback;
        }
        return requested;
    }

    private static void invalidate(Session session) {
        ModelSummary.invalidate(session);
        ModelIndex.invalidate(session);
        SessionSafeguards.requestSave(session);
    }
}
