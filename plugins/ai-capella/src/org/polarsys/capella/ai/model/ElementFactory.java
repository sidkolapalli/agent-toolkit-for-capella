package org.polarsys.capella.ai.model;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.logging.Logger;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.transaction.RecordingCommand;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.session.SessionBridge;

/** Generic element creation utilities using EMF dynamic factories. */
public final class ElementFactory {
    private static final Logger LOG = Logger.getLogger(ElementFactory.class.getName());
    private ElementFactory() {}

    /**
     * Create an element with the given simple EClass name under a suitable container
     * discovered from the given architecture root (e.g., LA root).
     */
    public static EObject createUnder(EObject architectureRoot, String simpleEClassName, String name) {
        Session session = SessionBridge.getActiveSession();
        if (session == null) return null;
        return createUnder(session, architectureRoot, simpleEClassName, name);
    }

    public static EObject createUnder(Session session, EObject architectureRoot, String simpleEClassName, String name) {
        if (session == null) return null;
        TransactionalEditingDomain domain = session.getTransactionalEditingDomain();
        if (domain == null) return null;

        EClass targetClass = findEClass(simpleEClassName);
        if (targetClass == null) return null;

        EFactory factory = targetClass.getEPackage().getEFactoryInstance();
        final EObject[] created = new EObject[1];

        EObject container = findBestContainer(architectureRoot, targetClass);
        if (container == null) container = architectureRoot;
        final EObject targetContainer = container;
        final EReference containment = findContainmentFor(targetContainer, targetClass);
        if (containment == null) return null;

        domain.getCommandStack().execute(new RecordingCommand(domain) {
            @Override
            protected void doExecute() {
                EObject obj = factory.create(targetClass);
                // Set name if available
                EStructuralFeature nameFeat = obj.eClass().getEStructuralFeature("name");
                if (nameFeat != null && nameFeat.isChangeable()) obj.eSet(nameFeat, name);
                @SuppressWarnings("unchecked")
                java.util.List<EObject> list = (java.util.List<EObject>) targetContainer.eGet(containment);
                list.add(obj);
                created[0] = obj;
            }
        });
        ModelSummary.invalidate(session);
        try { session.save(new NullProgressMonitor()); } catch (Exception e) { LOG.warning("Save failed: " + e.getMessage()); }
        return created[0];
    }

    /** Find an EClass by simple name across registered EPackages. */
    public static EClass findEClass(String simpleName) {
        for (Object v : EPackage.Registry.INSTANCE.values()) {
            if (v instanceof EPackage) {
                EClass c = findEClassRec((EPackage) v, simpleName);
                if (c != null) return c;
            }
        }
        return null;
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

    private static EReference findContainmentFor(EObject container, EClass target) {
        for (EStructuralFeature f : container.eClass().getEAllStructuralFeatures()) {
            if (f instanceof EReference) {
                EReference r = (EReference) f;
                if (r.isContainment() && r.isMany()) {
                    EClass et = (EClass) r.getEType();
                    if (et == target || et.isSuperTypeOf(target)) return r;
                }
            }
        }
        return null;
    }
}
