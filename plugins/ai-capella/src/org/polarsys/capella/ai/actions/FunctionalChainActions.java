package org.polarsys.capella.ai.actions;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.model.ActionResult;
import org.polarsys.capella.ai.model.ActionResult.ErrorCode;
import org.polarsys.capella.ai.model.ArchitectureLocator;
import org.polarsys.capella.ai.model.ElementFactory;
import org.polarsys.capella.ai.model.FindResult;
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

    public static ActionResult createChain(Session session, String layer, String name, String description) {
        if (session == null) {
            return ActionResult.fail(ErrorCode.SESSION_ERROR, "No active Capella session");
        }
        if (name == null || name.isBlank()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Functional chain name is required");
        }
        return SessionSafeguards.runWriteWithResult(session, "Create functional chain", () -> {
            FindResult layerResult = ArchitectureLocator.findLayerWithDiagnostic(session, resolveLayer(layer, "LA"));
            if (layerResult.failed()) {
                return layerResult.toActionResult();
            }
            EObject chain = ElementFactory.createUnder(session, layerResult.element(), "FunctionalChain", name);
            if (chain == null) {
                return ActionResult.fail(ErrorCode.CONSTRAINT_VIOLATION,
                    "Failed to create FunctionalChain '" + name + "'",
                    "Check EMF constraints and ensure the layer can hold FunctionalChain elements");
            }
            setIfPresent(chain, "description", description);
            invalidate(session);
            return ActionResult.ok("Created FunctionalChain '" + name + "'");
        });
    }

    public static ActionResult addFunction(Session session, String chainName, String functionName, String preferredClass) {
        if (session == null) {
            return ActionResult.fail(ErrorCode.SESSION_ERROR, "No active Capella session");
        }
        if (chainName == null || chainName.isBlank()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Chain name is required");
        }
        if (functionName == null || functionName.isBlank()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Function name is required");
        }
        return SessionSafeguards.runWriteWithResult(session, "Extend functional chain", () -> {
            // Find chain with diagnostics
            FindResult chainResult = Finders.findByNameWithDiagnostic(session, "FunctionalChain", chainName);
            if (chainResult.failed()) {
                return chainResult.toActionResult();
            }
            EObject chain = chainResult.element();

            // Find function with diagnostics
            FunctionLookupResult functionResult = findFunctionWithDiagnostic(session, functionName, preferredClass);
            if (!functionResult.success) {
                return functionResult.error;
            }
            EObject function = functionResult.function;

            if (hasInvolvement(chain, function)) {
                return ActionResult.ok("Function '" + functionName + "' already involved in chain '" + chainName + "'");
            }
            boolean linked = createInvolvement(chain, function, functionName);
            if (!linked) {
                return ActionResult.fail(ErrorCode.CONSTRAINT_VIOLATION,
                    "Failed to add function '" + functionName + "' to chain '" + chainName + "'",
                    "Unable to create FunctionalChainInvolvement element");
            }
            invalidate(session);
            return ActionResult.ok("Added function '" + functionName + "' to chain '" + chainName + "'");
        });
    }

    public static ActionResult addExchange(Session session, String chainName, String exchangeName) {
        if (session == null) {
            return ActionResult.fail(ErrorCode.SESSION_ERROR, "No active Capella session");
        }
        if (chainName == null || chainName.isBlank()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Chain name is required");
        }
        if (exchangeName == null || exchangeName.isBlank()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Exchange name is required");
        }
        return SessionSafeguards.runWriteWithResult(session, "Sequence functional exchange", () -> {
            // Find chain with diagnostics
            FindResult chainResult = Finders.findByNameWithDiagnostic(session, "FunctionalChain", chainName);
            if (chainResult.failed()) {
                return chainResult.toActionResult();
            }
            EObject chain = chainResult.element();

            // Find exchange with diagnostics
            FindResult exchangeResult = Finders.findByNameWithDiagnostic(session, "FunctionalExchange", exchangeName);
            if (exchangeResult.failed()) {
                return ActionResult.failWithAlternatives(ErrorCode.REFERENCE_NOT_FOUND,
                    "FunctionalExchange '" + exchangeName + "' not found",
                    exchangeResult.hasAlternatives() ? "Did you mean: " + String.join(", ", exchangeResult.alternatives()) : null,
                    exchangeResult.alternatives());
            }
            EObject exchange = exchangeResult.element();

            if (linkReference(chain, exchange, "exchange")) {
                invalidate(session);
                return ActionResult.ok("Added exchange '" + exchangeName + "' to chain '" + chainName + "'");
            }
            // fallback: create involvement element pointing to exchange
            if (hasInvolvement(chain, exchange)) {
                return ActionResult.ok("Exchange '" + exchangeName + "' already involved in chain '" + chainName + "'");
            }
            boolean linked = createInvolvement(chain, exchange, exchangeName);
            if (!linked) {
                return ActionResult.fail(ErrorCode.CONSTRAINT_VIOLATION,
                    "Failed to add exchange '" + exchangeName + "' to chain '" + chainName + "'",
                    "Unable to create involvement or link reference");
            }
            invalidate(session);
            return ActionResult.ok("Added exchange '" + exchangeName + "' to chain '" + chainName + "'");
        });
    }

    public static ActionResult attachScenario(Session session, String chainName, String scenarioName) {
        if (session == null) {
            return ActionResult.fail(ErrorCode.SESSION_ERROR, "No active Capella session");
        }
        if (chainName == null || chainName.isBlank()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Chain name is required");
        }
        if (scenarioName == null || scenarioName.isBlank()) {
            return ActionResult.fail(ErrorCode.INVALID_INPUT, "Scenario name is required");
        }
        return SessionSafeguards.runWriteWithResult(session, "Attach scenario to chain", () -> {
            // Find chain with diagnostics
            FindResult chainResult = Finders.findByNameWithDiagnostic(session, "FunctionalChain", chainName);
            if (chainResult.failed()) {
                return chainResult.toActionResult();
            }
            EObject chain = chainResult.element();

            // Find scenario with diagnostics
            ScenarioLookupResult scenarioResult = findScenarioWithDiagnostic(session, scenarioName);
            if (!scenarioResult.success) {
                return scenarioResult.error;
            }
            EObject scenario = scenarioResult.scenario;

            if (linkReference(chain, scenario, "scenario", "sequence", "diagram")) {
                invalidate(session);
                return ActionResult.ok("Attached scenario '" + scenarioName + "' to chain '" + chainName + "'");
            }
            return ActionResult.fail(ErrorCode.CONSTRAINT_VIOLATION,
                "Failed to attach scenario '" + scenarioName + "' to chain '" + chainName + "'",
                "No suitable reference found for scenario attachment");
        });
    }

    // ========== Legacy boolean methods for backward compatibility ==========

    /** @deprecated Use {@link #createChain(Session, String, String, String)} instead */
    @Deprecated
    public static boolean createChainLegacy(Session session, String layer, String name, String description) {
        return createChain(session, layer, name, description).success();
    }

    /** @deprecated Use {@link #addFunction(Session, String, String, String)} instead */
    @Deprecated
    public static boolean addFunctionLegacy(Session session, String chainName, String functionName, String preferredClass) {
        return addFunction(session, chainName, functionName, preferredClass).success();
    }

    /** @deprecated Use {@link #addExchange(Session, String, String)} instead */
    @Deprecated
    public static boolean addExchangeLegacy(Session session, String chainName, String exchangeName) {
        return addExchange(session, chainName, exchangeName).success();
    }

    /** @deprecated Use {@link #attachScenario(Session, String, String)} instead */
    @Deprecated
    public static boolean attachScenarioLegacy(Session session, String chainName, String scenarioName) {
        return attachScenario(session, chainName, scenarioName).success();
    }

    // ========== Helper Classes ==========

    /** Result of function lookup with diagnostic info. */
    private static class FunctionLookupResult {
        final boolean success;
        final EObject function;
        final ActionResult error;

        private FunctionLookupResult(EObject function) {
            this.success = true;
            this.function = function;
            this.error = null;
        }

        private FunctionLookupResult(ActionResult error) {
            this.success = false;
            this.function = null;
            this.error = error;
        }

        static FunctionLookupResult ok(EObject function) {
            return new FunctionLookupResult(function);
        }

        static FunctionLookupResult fail(ActionResult error) {
            return new FunctionLookupResult(error);
        }
    }

    /** Result of scenario lookup with diagnostic info. */
    private static class ScenarioLookupResult {
        final boolean success;
        final EObject scenario;
        final ActionResult error;

        private ScenarioLookupResult(EObject scenario) {
            this.success = true;
            this.scenario = scenario;
            this.error = null;
        }

        private ScenarioLookupResult(ActionResult error) {
            this.success = false;
            this.scenario = null;
            this.error = error;
        }

        static ScenarioLookupResult ok(EObject scenario) {
            return new ScenarioLookupResult(scenario);
        }

        static ScenarioLookupResult fail(ActionResult error) {
            return new ScenarioLookupResult(error);
        }
    }

    // ========== Helper Methods ==========

    private static FunctionLookupResult findFunctionWithDiagnostic(Session session, String name, String preferredClass) {
        // Try preferred class first
        if (preferredClass != null && !preferredClass.isBlank()) {
            FindResult result = Finders.findByNameWithDiagnostic(session, preferredClass, name);
            if (result.success()) {
                return FunctionLookupResult.ok(result.element());
            }
        }
        // Try all function classes
        List<String> allAlternatives = new java.util.ArrayList<>();
        for (String cls : FUNCTION_CLASSES) {
            FindResult result = Finders.findByNameWithDiagnostic(session, cls, name);
            if (result.success()) {
                return FunctionLookupResult.ok(result.element());
            }
            if (result.hasAlternatives()) {
                allAlternatives.addAll(result.alternatives());
            }
        }
        // Not found - return error with alternatives
        String suggestion = allAlternatives.isEmpty() ? null : "Did you mean: " + String.join(", ", allAlternatives);
        return FunctionLookupResult.fail(ActionResult.fail(ErrorCode.ELEMENT_NOT_FOUND,
            "Function '" + name + "' not found in any layer (tried: " + String.join(", ", FUNCTION_CLASSES) + ")",
            suggestion));
    }

    private static ScenarioLookupResult findScenarioWithDiagnostic(Session session, String name) {
        List<String> scenarioClasses = List.of(
                "Scenario",
                "FunctionalScenario",
                "OperationalScenario",
                "SystemScenario"
        );
        List<String> allAlternatives = new java.util.ArrayList<>();
        for (String cls : scenarioClasses) {
            FindResult result = Finders.findByNameWithDiagnostic(session, cls, name);
            if (result.success()) {
                return ScenarioLookupResult.ok(result.element());
            }
            if (result.hasAlternatives()) {
                allAlternatives.addAll(result.alternatives());
            }
        }
        String suggestion = allAlternatives.isEmpty() ? null : "Did you mean: " + String.join(", ", allAlternatives);
        return ScenarioLookupResult.fail(ActionResult.fail(ErrorCode.ELEMENT_NOT_FOUND,
            "Scenario '" + name + "' not found (tried: " + String.join(", ", scenarioClasses) + ")",
            suggestion));
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
