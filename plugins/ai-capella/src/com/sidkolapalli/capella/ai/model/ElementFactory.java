package com.sidkolapalli.capella.ai.model;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.sirius.business.api.session.Session;
import com.sidkolapalli.capella.ai.session.OperationInferencer;
import com.sidkolapalli.capella.ai.session.SessionBridge;
import com.sidkolapalli.capella.ai.session.SessionSafeguards;
import org.polarsys.capella.core.data.capellacore.CapellacorePackage;
import org.polarsys.capella.core.data.cs.BlockArchitecture;
import org.polarsys.capella.core.data.cs.Component;
import org.polarsys.capella.core.data.cs.ComponentPkg;
import org.polarsys.capella.core.data.cs.CsFactory;
import org.polarsys.capella.core.data.cs.CsPackage;
import org.polarsys.capella.core.data.cs.Part;
import org.polarsys.capella.core.data.ctx.SystemComponent;
import org.polarsys.capella.core.data.epbs.EPBSArchitecture;
import org.polarsys.capella.core.data.epbs.EpbsPackage;
import org.polarsys.capella.core.data.fa.AbstractFunction;
import org.polarsys.capella.core.data.fa.FaPackage;
import org.polarsys.capella.core.data.information.InformationPackage;
import org.polarsys.capella.core.data.information.MultiplicityElement;
import org.polarsys.capella.core.data.information.datavalue.DatavalueFactory;
import org.polarsys.capella.core.data.information.datavalue.LiteralNumericValue;
import org.polarsys.capella.core.model.helpers.BlockArchitectureExt;

import java.util.ArrayDeque;
import java.util.Deque;

/** Generic element creation utilities using EMF dynamic factories. */
public final class ElementFactory {
    private ElementFactory() {}

    /**
     * Create an element with the given simple EClass name under a suitable container discovered
     * from the given architecture root (e.g., LA root).
     */
    public static EObject createUnder(
            EObject architectureRoot, String simpleEClassName, String name) {
        Session session = SessionBridge.getActiveSession();
        if (session == null) return null;
        return createUnder(session, architectureRoot, simpleEClassName, name);
    }

    public static EObject createUnder(
            Session session, EObject architectureRoot, String simpleEClassName, String name) {
        if (session == null) return null;
        if (architectureRoot == null)
            throw new IllegalArgumentException("Creation parent cannot be null");
        return SessionSafeguards.runWrite(
                session,
                "Create " + simpleEClassName,
                () -> {
                    TransactionalEditingDomain domain = session.getTransactionalEditingDomain();
                    if (domain == null) return null;

                    EClass targetClass = findEClass(simpleEClassName);
                    if (targetClass == null) return null;

                    if (targetClass.isAbstract()) {
                        throw new IllegalArgumentException(
                                "Cannot create abstract type " + simpleEClassName);
                    }
                    requireLayerType(architectureRoot, targetClass);
                    EObject container = findBestContainer(architectureRoot, targetClass);
                    if (container == null) {
                        container = architectureRoot;
                    }
                    EReference containment = findContainmentFor(container, targetClass);
                    if (containment == null) return null;
                    requireComponentContainment(container, targetClass);

                    EObject obj =
                            targetClass.getEPackage().getEFactoryInstance().create(targetClass);
                    EStructuralFeature nameFeat = obj.eClass().getEStructuralFeature("name");
                    if (nameFeat != null && nameFeat.isChangeable()) {
                        obj.eSet(nameFeat, name);
                    }
                    @SuppressWarnings("unchecked")
                    java.util.List<EObject> list =
                            (java.util.List<EObject>) container.eGet(containment);
                    list.add(obj);
                    if (obj instanceof Component component) {
                        createRepresentingPart(component);
                    }
                    defaultCardinality(obj);

                    // Record operation for label inference
                    OperationInferencer.recordCreate(simpleEClassName, name);

                    ModelSummary.invalidate(session);
                    try {
                        SessionSafeguards.requestSave(session);
                    } catch (Exception e) {
                        throw new RuntimeException(
                                "Failed to save after creating " + simpleEClassName, e);
                    }
                    return obj;
                });
    }

    /** Find an EClass by simple name across registered EPackages. */
    public static EClass findEClass(String simpleName) {
        // The shared Modellingcore package also declares an abstract Constraint.
        // A Capella constraint must use the concrete native classifier regardless
        // of EMF registry iteration order.
        if ("Constraint".equals(simpleName)) return CapellacorePackage.Literals.CONSTRAINT;
        // Other registered packages (Sirius, Kitalpha) reuse names such as Property: prefer Capella's.
        EClass other = null;
        for (Object v : EPackage.Registry.INSTANCE.values()) {
            if (v instanceof EPackage) {
                EClass c = findEClassRec((EPackage) v, simpleName);
                if (c != null && c.getEPackage().getNsURI().contains("/capella/core/")) return c;
                if (other == null) other = c;
            }
        }
        return other;
    }

    /**
     * New class properties and exchange item elements get [1..1], as Capella's creation tools give
     * them; without a minimum cardinality each is a DWF_D_51 validation error.
     */
    public static void defaultCardinality(EObject element) {
        if (!(element instanceof MultiplicityElement multiplicity)
                || element.eClass() != InformationPackage.Literals.PROPERTY
                        && element.eClass() != InformationPackage.Literals.EXCHANGE_ITEM_ELEMENT)
            return;
        if (multiplicity.getOwnedMinCard() == null) multiplicity.setOwnedMinCard(one());
        if (multiplicity.getOwnedMaxCard() == null) multiplicity.setOwnedMaxCard(one());
    }

    private static LiteralNumericValue one() {
        LiteralNumericValue value = DatavalueFactory.eINSTANCE.createLiteralNumericValue();
        value.setId(java.util.UUID.randomUUID().toString());
        value.setValue("1");
        return value;
    }

    private static EClass findEClassRec(EPackage pkg, String simpleName) {
        for (EClassifier cls : pkg.getEClassifiers()) {
            if (cls instanceof EClass && simpleName.equals(cls.getName())) return (EClass) cls;
        }
        for (EPackage sp : pkg.getESubpackages()) {
            EClass c = findEClassRec(sp, simpleName);
            if (c != null) return c;
        }
        return null;
    }

    private static EObject findBestContainer(EObject root, EClass target) {
        // A native initialized EPBS already has its product root. Default
        // architecture-level CI creation extends that breakdown rather than
        // creating a second product beside it. Explicit package/CI parents
        // still select their own native containment through the normal path.
        if (root instanceof EPBSArchitecture epbsArchitecture
                && EpbsPackage.Literals.CONFIGURATION_ITEM.isSuperTypeOf(target)) {
            java.util.Collection<Component> roots =
                    BlockArchitectureExt.getRootComponents(epbsArchitecture);
            if (roots != null && roots.size() > 1) {
                throw new IllegalArgumentException(
                        "EPBS architecture has multiple root configuration items. Select an"
                            + " explicit ConfigurationItem parent or repair the product breakdown"
                            + " before default creation.");
            }
            if (roots != null && roots.size() == 1) {
                Component product = roots.iterator().next();
                if (findContainmentFor(product, target) == null) {
                    throw new IllegalArgumentException(
                            "The EPBS root cannot contain " + target.getName());
                }
                return product;
            }
        }
        // The function package owns the layer's root function. New functions are
        // children of that root, so exchanges have a common function container.
        if (root instanceof BlockArchitecture architecture
                && FaPackage.Literals.ABSTRACT_FUNCTION.isSuperTypeOf(target)) {
            AbstractFunction rootFunction =
                    BlockArchitectureExt.getRootFunction(architecture, false);
            if (rootFunction != null) {
                if (!rootFunction.eClass().isSuperTypeOf(target)) {
                    throw new IllegalArgumentException(
                            "Function type "
                                    + target.getName()
                                    + " does not match architecture "
                                    + architecture.eClass().getName());
                }
                return findContainmentFor(rootFunction, target) != null ? rootFunction : null;
            }
        }
        // BFS over eAllContents to find a container that has a containment for the target type
        Deque<EObject> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            EObject cur = q.removeFirst();
            EReference ref = findContainmentFor(cur, target);
            if (ref != null) return cur;
            for (EObject child : cur.eContents()) q.addLast(child);
        }
        return null;
    }

    private static void createRepresentingPart(Component component) {
        EObject owner = component.eContainer();
        java.util.List<? extends EObject> features;
        if (owner instanceof ComponentPkg componentPackage) {
            features = componentPackage.getOwnedParts();
        } else if (owner instanceof Component parentComponent) {
            features = parentComponent.getOwnedFeatures();
        } else {
            throw new IllegalArgumentException(
                    "Cannot create a representing Part for "
                            + component.eClass().getName()
                            + " under "
                            + owner.eClass().getName());
        }
        // Read the stored feature directly; the native representingParts view
        // requires an Eclipse adapter and may be unavailable during initialization.
        for (EObject feature : features) {
            if (feature instanceof Part existing && existing.getAbstractType() == component) return;
        }
        Part part = CsFactory.eINSTANCE.createPart();
        // Capella's native component creation contributions place the instance
        // beside its type in the immediate component or component package.
        if (owner instanceof ComponentPkg owningPackage) {
            owningPackage.getOwnedParts().add(part);
        } else if (owner instanceof Component parentOwner) {
            parentOwner.getOwnedFeatures().add(part);
        }
        part.setName(component.getName());
        part.setAbstractType(component);
    }

    private static void requireComponentContainment(EObject owner, EClass target) {
        if (owner instanceof SystemComponent system
                && !system.isActor()
                && (CsPackage.Literals.COMPONENT.isSuperTypeOf(target)
                        || CsPackage.Literals.COMPONENT_PKG.isSuperTypeOf(target)
                        || CsPackage.Literals.PART.isSuperTypeOf(target))) {
            throw new IllegalArgumentException(
                    "The System Analysis system is a black box and cannot be decomposed. "
                            + "Create SystemActor elements in the SystemComponentPkg instead.");
        }
    }

    private static void requireLayerType(EObject parent, EClass target) {
        boolean function = FaPackage.Literals.ABSTRACT_FUNCTION.isSuperTypeOf(target);
        boolean configurationItem =
                EpbsPackage.Literals.CONFIGURATION_ITEM.isSuperTypeOf(target)
                        || EpbsPackage.Literals.CONFIGURATION_ITEM_PKG.isSuperTypeOf(target);
        if (!function && !configurationItem) return;
        for (EObject ancestor = parent; ancestor != null; ancestor = ancestor.eContainer()) {
            if (ancestor instanceof BlockArchitecture) {
                if (configurationItem) {
                    if (!"EPBSArchitecture".equals(ancestor.eClass().getName())) {
                        throw new IllegalArgumentException(
                                target.getName() + " belongs to the EPBS architecture");
                    }
                    return;
                }
                if ("EPBSArchitecture".equals(ancestor.eClass().getName())) {
                    throw new IllegalArgumentException(
                            "EPBS architecture does not contain functions or operational"
                                + " activities");
                }
                String expectedType =
                        switch (ancestor.eClass().getName()) {
                            case "OperationalAnalysis" -> "OperationalActivity";
                            case "SystemAnalysis" -> "SystemFunction";
                            case "LogicalArchitecture" -> "LogicalFunction";
                            case "PhysicalArchitecture" -> "PhysicalFunction";
                            default -> null;
                        };
                EClass expected = expectedType != null ? findEClass(expectedType) : null;
                if (expected != null && !expected.isSuperTypeOf(target)) {
                    throw new IllegalArgumentException(
                            "Function type "
                                    + target.getName()
                                    + " does not match architecture "
                                    + ancestor.eClass().getName());
                }
                return;
            }
        }
    }

    private static EReference findContainmentFor(EObject container, EClass target) {
        EReference best = null;
        for (EStructuralFeature f : container.eClass().getEAllStructuralFeatures()) {
            if (f instanceof EReference) {
                EReference r = (EReference) f;
                // These inherited extension/migration buckets accept nearly any
                // model element, but are not semantic modeling containers.
                if ("ownedMigratedElements".equals(r.getName())
                        || "ownedExtensions".equals(r.getName())) continue;
                if (r.isContainment() && r.isMany() && r.isChangeable() && !r.isDerived()) {
                    EClass et = (EClass) r.getEType();
                    if ((et == target || et.isSuperTypeOf(target))
                            && (best == null || best.getEReferenceType().isSuperTypeOf(et))) {
                        best = r;
                    }
                }
            }
        }
        return best;
    }
}
