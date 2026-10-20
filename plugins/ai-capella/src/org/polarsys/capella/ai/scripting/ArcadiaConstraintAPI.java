package org.polarsys.capella.ai.scripting;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.util.InternalEList;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.model.ModelAnalytics;
import org.polarsys.capella.ai.model.ModelSummary;
import org.polarsys.capella.ai.model.index.ModelIndex;
import org.polarsys.capella.ai.session.SessionSafeguards;
import org.polarsys.capella.common.data.modellingcore.ModelElement;
import org.polarsys.capella.core.data.capellacore.CapellaElement;
import org.polarsys.capella.core.data.capellacore.CapellacoreFactory;
import org.polarsys.capella.core.data.capellacore.Constraint;
import org.polarsys.capella.core.data.capellamodeller.Project;
import org.polarsys.capella.core.data.information.datavalue.DatavalueFactory;
import org.polarsys.capella.core.data.information.datavalue.OpaqueExpression;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Native, scoped text Constraints. Storing a predicate does not execute or verify it. */
public final class ArcadiaConstraintAPI {
    private static final String LANGUAGE = "capella:linkedText";
    private static final int MAX_CONSTRAINTS = 4096;
    private final Session session;

    public ArcadiaConstraintAPI(Session session) {
        this.session = session;
    }

    /** Preserve exact text and subject order; return an identical existing constraint unchanged. */
    public Constraint constraint(
            CapellaElement owner,
            String name,
            String expression,
            List<? extends EObject> subjects) {
        text(name, "Constraint name", 240);
        text(expression, "Constraint expression", 12000);
        List<ModelElement> requested = subjects(subjects);
        AtomicReference<Constraint> result = new AtomicReference<>();
        SessionSafeguards.runOnUiThread(
                () -> {
                    requireSession();
                    if (SessionSafeguards.isInWriteContext()) {
                        // The managed-write guard also checks the exact captured Session/domain.
                        result.set(
                                SessionSafeguards.runWrite(
                                        session,
                                        "Create scoped Constraint",
                                        () -> create(owner, name, expression, requested)));
                        return;
                    }
                    TransactionalEditingDomain domain = session.getTransactionalEditingDomain();
                    try {
                        domain.runExclusive(
                                () -> result.set(preflight(owner, name, expression, requested)));
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new SessionSafeguards.ScriptCancelledException(
                                "Constraint lookup was interrupted");
                    }
                    if (result.get() == null) {
                        result.set(
                                SessionSafeguards.runWrite(
                                        session,
                                        "Create scoped Constraint",
                                        () -> create(owner, name, expression, requested)));
                    }
                });
        return result.get();
    }

    private Constraint create(
            CapellaElement owner, String name, String body, List<ModelElement> subjects) {
        Constraint previous = preflight(owner, name, body, subjects);
        if (previous != null) return previous;
        Constraint result = CapellacoreFactory.eINSTANCE.createConstraint();
        result.setId(UUID.randomUUID().toString());
        result.setName(name);
        OpaqueExpression expression = DatavalueFactory.eINSTANCE.createOpaqueExpression();
        expression.setId(UUID.randomUUID().toString());
        expression.getLanguages().add(LANGUAGE);
        expression.getBodies().add(body);
        result.setOwnedSpecification(expression);
        result.getConstrainedElements().addAll(subjects);
        owner.getOwnedConstraints().add(result);
        ModelSummary.invalidate(session);
        ModelIndex.invalidate(session);
        ModelAnalytics.invalidate(session);
        try {
            SessionSafeguards.requestSave(session);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not save native Constraint changes", failure);
        }
        return result;
    }

    /** A complete read preflight also runs inside the write command, before attaching anything. */
    private Constraint preflight(
            CapellaElement owner, String name, String body, List<ModelElement> subjects) {
        requireSession();
        requireOwned(owner);
        Project project = project(owner);
        if (project == null)
            throw invalid("Constraint owner must belong to a native semantic Project");
        for (ModelElement subject : subjects) {
            requireOwned(subject);
            if (project(subject) != project)
                throw invalid(
                        "Constraint subjects and owner must belong to the same semantic Project");
        }
        var feature = owner.eClass().getEStructuralFeature("ownedConstraints");
        if (!(feature instanceof EReference reference)
                || !reference.isContainment()
                || !reference.isMany()
                || reference.isDerived()
                || reference.isTransient()
                || reference.isVolatile()) {
            throw invalid(
                    "Constraint owner requires its native stored ownedConstraints containment");
        }
        Object value = owner.eGet(reference, false);
        if (!(value instanceof InternalEList<?> list) || list.size() > MAX_CONSTRAINTS) {
            throw invalid("Constraint lookup requires a bounded native containment list");
        }
        Constraint previous = null;
        var iterator = list.basicIterator();
        while (iterator.hasNext()) {
            checkCancellation();
            Object item = iterator.next();
            if (!(item instanceof Constraint candidate)
                    || candidate.eIsProxy()
                    || candidate.eContainer() != owner
                    || candidate.eContainingFeature() != reference)
                throw invalid("Stored Constraints require resolved exact native ownership");
            if (!name.equals(candidate.getName())) continue;
            if (previous != null)
                throw invalid("Duplicate Constraint names require explicit model repair");
            previous = candidate;
        }
        if (previous != null) {
            requireOwned(previous);
            Object specification =
                    previous.eGet(
                            previous.eClass().getEStructuralFeature("ownedSpecification"), false);
            if (!(specification instanceof OpaqueExpression expression)
                    || expression.eIsProxy()
                    || expression.eContainer() != previous
                    || !"ownedSpecification".equals(expression.eContainingFeature().getName())) {
                throw invalid(
                        "Existing Constraint requires its exact native owned OpaqueExpression");
            }
            requireOwned(expression);
            List<EObject> actualSubjects = storedSubjects(previous);
            if (!expression.getLanguages().equals(List.of(LANGUAGE))
                    || !expression.getBodies().equals(List.of(body))
                    || !actualSubjects.equals(subjects))
                throw invalid(
                        "An existing Constraint with this name has conflicting expression or"
                            + " subjects");
        }
        return previous;
    }

    private List<EObject> storedSubjects(Constraint constraint) {
        Object value =
                constraint.eGet(
                        constraint.eClass().getEStructuralFeature("constrainedElements"), false);
        if (!(value instanceof InternalEList<?> list) || list.size() < 1 || list.size() > 32) {
            throw invalid("Stored Constraint subjects must contain 1 to 32 native elements");
        }
        List<EObject> result = new ArrayList<>();
        Set<EObject> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        var iterator = list.basicIterator();
        while (iterator.hasNext()) {
            Object item = iterator.next();
            if (!(item instanceof ModelElement element) || !unique.add(element))
                throw invalid("Stored Constraint subjects must be distinct native ModelElements");
            requireOwned(element);
            result.add(element);
        }
        return result;
    }

    private static List<ModelElement> subjects(List<? extends EObject> values) {
        if (values == null || values.isEmpty() || values.size() > 32)
            throw invalid("Constraint requires 1 to 32 distinct native subjects");
        List<ModelElement> result = new ArrayList<>();
        Set<EObject> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        for (EObject value : values) {
            if (!(value instanceof ModelElement subject) || !unique.add(value))
                throw invalid("Constraint subjects must be distinct native ModelElements");
            result.add(subject);
        }
        return List.copyOf(result);
    }

    private void requireSession() {
        checkCancellation();
        if (session == null
                || !session.isOpen()
                || session.getTransactionalEditingDomain() == null
                || session.getTransactionalEditingDomain().getResourceSet() == null) {
            throw new IllegalStateException(
                    "An open captured Capella session with a transactional domain is required");
        }
    }

    private void requireOwned(ModelElement element) {
        if (element == null
                || element.eIsProxy()
                || element.eResource() == null
                || element.eResource().getResourceSet()
                        != session.getTransactionalEditingDomain().getResourceSet()
                || !session.getSemanticResources().contains(element.eResource())) {
            throw invalid(
                    "Constraint endpoints must be resolved native semantic elements in the captured"
                        + " session");
        }
        String id = element.getId();
        if (id == null || id.isBlank() || element.eResource().getEObject(id) != element) {
            throw invalid(
                    "Constraint endpoint identity must resolve uniquely in its native resource");
        }
    }

    private static Project project(EObject value) {
        for (int depth = 0; value != null && depth < 256; depth++, value = value.eContainer()) {
            if (value.eIsProxy()) throw invalid("Constraint ancestry must be resolved");
            if (value instanceof Project project) return project;
        }
        return null;
    }

    private static void text(String value, String label, int limit) {
        if (value == null
                || value.isBlank()
                || value.length() > limit
                || value.indexOf('\0') >= 0) {
            throw invalid(label + " must be nonblank bounded text without null characters");
        }
        for (int offset = 0; offset < value.length(); ) {
            int point = value.codePointAt(offset);
            if (!(point == 9
                    || point == 10
                    || point == 13
                    || point >= 0x20 && point <= 0xD7FF
                    || point >= 0xE000 && point <= 0xFFFD
                    || point >= 0x10000 && point <= 0x10FFFF)) {
                throw invalid(label + " must contain valid XML text");
            }
            offset += Character.charCount(point);
        }
    }

    private static void checkCancellation() {
        if (SessionSafeguards.isScriptCancellationRequested()) {
            throw new SessionSafeguards.ScriptCancelledException(
                    "Constraint creation was cancelled or timed out");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
