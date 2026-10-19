package org.polarsys.capella.ai.session;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.diagram.DNode;
import org.eclipse.sirius.diagram.DiagramFactory;
import org.eclipse.sirius.viewpoint.DSemanticDecorator;
import org.eclipse.sirius.viewpoint.DRepresentation;
import org.junit.Test;

public class SessionBridgeSelectionTest {
    @Test
    public void diagramDecoratorSelectionResolvesItsSemanticTarget() {
        EObject target = EcoreFactory.eINSTANCE.createEClass();
        DNode node = DiagramFactory.eINSTANCE.createDNode();
        node.setTarget(target);

        assertSame(target, semantic(node));
    }

    @Test
    public void adaptedEObjectDecoratorAlsoResolvesItsSemanticTarget() {
        EObject target = EcoreFactory.eINSTANCE.createEClass();
        DNode node = DiagramFactory.eINSTANCE.createDNode();
        node.setTarget(target);
        IAdaptable selection = new IAdaptable() {
            @Override
            public <T> T getAdapter(Class<T> type) {
                return type == EObject.class ? type.cast(node) : null;
            }
        };

        assertSame(target, semantic(selection));
    }

    @Test
    public void decoratorAdaptationTakesPriorityOverGenericEObjectAdaptation() {
        EObject target = EcoreFactory.eINSTANCE.createEClass();
        EObject unrelatedView = EcoreFactory.eINSTANCE.createEClass();
        DNode node = DiagramFactory.eINSTANCE.createDNode();
        node.setTarget(target);
        IAdaptable selection = new IAdaptable() {
            @Override
            public <T> T getAdapter(Class<T> type) {
                if (type == DSemanticDecorator.class) return type.cast(node);
                return type == EObject.class ? type.cast(unrelatedView) : null;
            }
        };

        assertSame(target, semantic(selection));
    }

    @Test
    public void decoratorWithoutTargetDoesNotReturnItsViewObject() {
        assertNull(semantic(DiagramFactory.eINSTANCE.createDNode()));
    }

    @Test
    public void directSemanticSelectionRemainsUnchanged() {
        EObject target = EcoreFactory.eINSTANCE.createEClass();
        assertSame(target, semantic(target));
        assertNull(semantic(null));
    }

    @Test
    public void aSingleOpenSessionRemainsAvailableToHeadlessCallers() {
        Session open = session(true);
        Session closed = session(false);
        assertSame(open, choose(Arrays.asList(null, closed, open)));
        assertNull(choose(List.of(closed)));
        assertNull(choose(List.of()));
        assertNull(choose(null));
    }

    @Test
    public void unresolvedContextNeverChoosesBetweenOpenModels() {
        Session first = session(true);
        Session second = session(true);
        assertNull(choose(List.of(first, second)));
        assertNull(choose(List.of(second, first)));
    }

    @Test
    public void repeatedReferenceToTheSameSessionIsUnambiguous() {
        Session open = session(true);
        assertSame(open, choose(List.of(open, open)));
    }

    @Test
    public void explicitModelSelectionOverridesAStaleBackgroundEditor() {
        Session selected = session(true);
        Session staleEditor = session(true);
        Object selection = resolveSelection(new StructuredSelection("model"), value -> selected);
        assertSame(selected, chooseUiSession(selection, staleEditor, false, null, false));
    }

    @Test
    public void typingInChatKeepsTheExplicitProjectExplorerModel() {
        Session selected = session(true);
        Session staleEditor = session(true);
        Object noModelSelection = resolveSelection(new StructuredSelection("chat control"), value -> null);
        assertSame(selected, chooseUiSession(noModelSelection, staleEditor, false, selected, true));
    }

    @Test
    public void deliberatelyActivatingTheSameOldEditorClearsTheRememberedModel() {
        Session selected = session(true);
        Session oldEditor = session(true);
        // Editor activation wins even if the selection service momentarily retains the old tree selection.
        Object staleTreeSelection = resolution(selected, true);
        assertSame(oldEditor, chooseUiSession(staleTreeSelection, oldEditor, true, selected, true));
    }

    @Test
    public void aDifferentEditorAndAClosedRememberedModelCannotKeepAnOldOverride() {
        Session selected = session(true);
        Session editor = session(true);
        Object empty = resolution(null, false);
        assertSame(editor, chooseUiSession(empty, editor, false, selected, false));
        assertSame(editor, chooseUiSession(empty, editor, false, session(false), true));
    }

    @Test
    public void nonsemanticChatSelectionWithoutAnOverridePreservesTheEditor() {
        Session editor = session(true);
        Object selection = resolveSelection(new StructuredSelection("chat"), value -> null);
        assertSame(editor, chooseUiSession(selection, editor, false, null, false));
    }

    @Test
    public void aSelectionSpanningTwoModelsDoesNotFallBackToTheEditor() {
        Session first = session(true);
        Session second = session(true);
        Object selection = resolveSelection(new StructuredSelection(new Object[] {first, second}), value -> (Session) value);
        assertTrue(authoritative(selection));
        assertNull(chooseUiSession(selection, first, false, first, true));
    }

    @Test
    public void modelFileSelectionChoosesItsSessionAndUnresolvedAdaptedFilesFailClosed() {
        Session selected = session(true);
        Session editor = session(true);
        IFile file = file("aird");
        Object selection = resolveSelection(new StructuredSelection(file), value -> selected);
        assertSame(selected, chooseUiSession(selection, editor, false, null, false));
        IAdaptable wrapper = new IAdaptable() {
            @Override
            public <T> T getAdapter(Class<T> type) {
                return type == IFile.class ? type.cast(file) : null;
            }
        };
        Object unresolved = resolveSelection(new StructuredSelection(wrapper), value -> null);
        assertTrue(authoritative(unresolved));
        assertNull(chooseUiSession(unresolved, editor, false, null, false));
    }

    @Test
    public void selectionAndDiagramFromAnotherModelAreExcludedTogether() {
        Resource semantic = new ResourceImpl(URI.createURI("memory:/fsae.capella"));
        Resource foreign = new ResourceImpl(URI.createURI("memory:/integration.capella"));
        EObject selected = EcoreFactory.eINSTANCE.createEClass();
        EObject foreignSelected = EcoreFactory.eINSTANCE.createEClass();
        semantic.getContents().add(selected);
        foreign.getContents().add(foreignSelected);
        Resource ownAird = new ResourceImpl(URI.createURI("memory:/fsae.aird"));
        Resource foreignAird = new ResourceImpl(URI.createURI("memory:/integration.aird"));
        var foreignDiagram = DiagramFactory.eINSTANCE.createDSemanticDiagram();
        foreignDiagram.setTarget(foreignSelected);
        foreignAird.getContents().add(foreignDiagram);
        Session chosen = session(true, List.of(semantic), ownAird, Set.of(ownAird));
        var context = filterSelectionContext(chosen, List.of(selected, foreignSelected), foreignDiagram);
        assertEquals(List.of(selected), context.getSelectedElements());
        assertTrue(context.getActiveDiagram().isEmpty());
    }

    @Test
    public void ownedFragmentedDiagramIsRetainedOnlyWithAnOwnedSemanticTarget() {
        Resource semantic = new ResourceImpl(URI.createURI("memory:/fsae.capella"));
        Resource foreign = new ResourceImpl(URI.createURI("memory:/integration.capella"));
        EObject target = EcoreFactory.eINSTANCE.createEClass();
        EObject foreignTarget = EcoreFactory.eINSTANCE.createEClass();
        semantic.getContents().add(target);
        foreign.getContents().add(foreignTarget);
        Resource main = new ResourceImpl(URI.createURI("memory:/fsae.aird"));
        Resource fragment = new ResourceImpl(URI.createURI("memory:/fsae-fragment.aird"));
        var diagram = DiagramFactory.eINSTANCE.createDSemanticDiagram();
        diagram.setTarget(target);
        fragment.getContents().add(diagram);
        Session chosen = session(true, List.of(semantic), main, Set.of(main, fragment));
        assertSame(diagram, filterSelectionContext(chosen, List.of(target), diagram).getActiveDiagram().orElse(null));
        diagram.setTarget(foreignTarget);
        assertTrue(filterSelectionContext(chosen, List.of(target), diagram).getActiveDiagram().isEmpty());
    }

    @Test
    public void detachedAndStaleObjectsWithTheSameResourceUriAreNotAcceptedAsCurrentSelection() {
        URI uri = URI.createURI("memory:/fsae.capella");
        Resource semantic = new ResourceImpl(uri);
        Resource staleResource = new ResourceImpl(uri);
        EObject stale = EcoreFactory.eINSTANCE.createEClass();
        EObject detached = EcoreFactory.eINSTANCE.createEClass();
        staleResource.getContents().add(stale);
        Session chosen = session(true, List.of(semantic), null, Set.of());
        assertTrue(filterSelectionContext(chosen, List.of(stale, detached), null).getSelectedElements().isEmpty());
    }

    @Test
    public void chatFocusKeepsExplicitSelectionWhenTheBackgroundEditorBelongsToAnotherModel() {
        Resource semantic = new ResourceImpl(URI.createURI("memory:/fsae.capella"));
        Resource foreign = new ResourceImpl(URI.createURI("memory:/integration.capella"));
        EObject selected = EcoreFactory.eINSTANCE.createEClass();
        EObject editorSelected = EcoreFactory.eINSTANCE.createEClass();
        semantic.getContents().add(selected);
        foreign.getContents().add(editorSelected);
        Session chosen = session(true, List.of(semantic), null, Set.of());
        List<EObject> retained = chooseSemanticSelection(chosen, List.of(editorSelected), chosen,
            List.of(new WeakReference<>(selected)));
        assertEquals(List.of(selected), retained);
        assertTrue(filterSelectionContext(chosen, retained, null).hasSelection());
    }

    @Test
    public void aCurrentOwnedSelectionReplacesTheRememberedExplicitSelection() {
        Resource semantic = new ResourceImpl(URI.createURI("memory:/fsae.capella"));
        EObject previous = EcoreFactory.eINSTANCE.createEClass();
        EObject current = EcoreFactory.eINSTANCE.createEClass();
        semantic.getContents().addAll(List.of(previous, current));
        Session chosen = session(true, List.of(semantic), null, Set.of());
        assertEquals(List.of(current), chooseSemanticSelection(chosen, List.of(current), chosen,
            List.of(new WeakReference<>(previous))));
    }

    @Test
    public void rememberedSelectionCannotSurviveEditorActivationModelSwitchOrAmbiguity() {
        Resource shared = new ResourceImpl(URI.createURI("memory:/shared.capella"));
        EObject selected = EcoreFactory.eINSTANCE.createEClass();
        shared.getContents().add(selected);
        Session previous = session(true, List.of(shared), null, Set.of());
        Session editor = session(true, List.of(shared), null, Set.of());
        List<WeakReference<EObject>> remembered = List.of(new WeakReference<>(selected));
        // Even a resource shared by sessions cannot carry a remembered selection across a model switch.
        assertTrue(chooseSemanticSelection(editor, List.of(), previous, remembered).isEmpty());
        assertTrue(chooseSemanticSelection(previous, List.of(), null, List.of()).isEmpty());
        assertTrue(chooseSemanticSelection(null, List.of(), previous, remembered).isEmpty());
        assertTrue(chooseSemanticSelection(session(false, List.of(shared), null, Set.of()),
            List.of(), previous, remembered).isEmpty());
    }

    @Test
    public void rememberedElementsAreRevalidatedAfterDeletionProxyingAndWeakReferenceRelease() {
        URI uri = URI.createURI("memory:/fsae.capella");
        Resource semantic = new ResourceImpl(uri);
        Resource staleResource = new ResourceImpl(uri);
        EObject deleted = EcoreFactory.eINSTANCE.createEClass();
        EObject stale = EcoreFactory.eINSTANCE.createEClass();
        EObject proxy = EcoreFactory.eINSTANCE.createEClass();
        EObject released = EcoreFactory.eINSTANCE.createEClass();
        semantic.getContents().addAll(List.of(deleted, proxy, released));
        staleResource.getContents().add(stale);
        WeakReference<EObject> releasedReference = new WeakReference<>(released);
        List<WeakReference<EObject>> remembered = List.of(new WeakReference<>(deleted),
            new WeakReference<>(stale), new WeakReference<>(proxy), releasedReference);
        semantic.getContents().remove(deleted);
        ((InternalEObject) proxy).eSetProxyURI(URI.createURI("memory:/missing.capella#removed"));
        releasedReference.clear();
        Session chosen = session(true, List.of(semantic), null, Set.of());
        assertTrue(chooseSemanticSelection(chosen, List.of(), chosen, remembered).isEmpty());
    }

    private static EObject semantic(Object selection) {
        return (EObject) invoke("toSemanticEObject", Object.class, selection);
    }

    private static Session choose(Collection<Session> sessions) {
        return (Session) invoke("chooseSingleOpenSession", Collection.class, sessions);
    }

    private static Object invoke(String name, Class<?> argumentType, Object argument) {
        return invoke(name, new Class<?>[] { argumentType }, argument);
    }

    // These helpers deliberately use reflection: the tests run in a separate OSGi bundle,
    // so the same package name does not grant access to production package-private types.
    private static Object resolveSelection(ISelection selection, Function<Object, Session> resolver) {
        return invoke("resolveSelectionSession", new Class<?>[] { ISelection.class, Function.class }, selection, resolver);
    }

    private static Session chooseUiSession(Object selection, Session editor, boolean editorActive,
            Session remembered, boolean sameEditor) {
        Object result = invoke("chooseUiSession", new Class<?>[] { resolutionType(), Session.class, boolean.class,
            Session.class, boolean.class }, selection, editor, editorActive, remembered, sameEditor);
        return (Session) resolutionProperty(result, "session");
    }

    private static boolean authoritative(Object resolution) {
        return (Boolean) resolutionProperty(resolution, "authoritative");
    }

    private static Class<?> resolutionType() {
        return Arrays.stream(SessionBridge.class.getDeclaredClasses())
            .filter(type -> type.getSimpleName().equals("UiResolution")).findFirst().orElseThrow();
    }

    private static Object resolution(Session session, boolean authoritative) {
        try {
            Constructor<?> constructor = resolutionType().getDeclaredConstructor(Session.class, boolean.class);
            constructor.setAccessible(true);
            return constructor.newInstance(session, authoritative);
        } catch (InvocationTargetException ex) {
            throw new AssertionError(ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static Object resolutionProperty(Object resolution, String name) {
        try {
            Method method = resolution.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(resolution);
        } catch (InvocationTargetException ex) {
            throw new AssertionError(ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static SelectionContext filterSelectionContext(Session session, List<EObject> selected, DRepresentation diagram) {
        return (SelectionContext) invoke("filterSelectionContext",
            new Class<?>[] { Session.class, List.class, DRepresentation.class }, session, selected, diagram);
    }

    @SuppressWarnings("unchecked")
    private static List<EObject> chooseSemanticSelection(Session session, List<EObject> current,
            Session rememberedSession, List<WeakReference<EObject>> remembered) {
        return (List<EObject>) invoke("chooseSemanticSelection",
            new Class<?>[] { Session.class, List.class, Session.class, List.class },
            session, current, rememberedSession, remembered);
    }

    private static Object invoke(String name, Class<?>[] argumentTypes, Object... arguments) {
        try {
            Method method = SessionBridge.class.getDeclaredMethod(name, argumentTypes);
            method.setAccessible(true);
            return method.invoke(null, arguments);
        } catch (InvocationTargetException ex) {
            throw new AssertionError(ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static Session session(boolean open) {
        return session(open, List.of(), null, Set.of());
    }

    private static Session session(boolean open, Collection<Resource> semantic, Resource sessionResource, Set<Resource> allSessionResources) {
        return (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[] { Session.class },
            (proxy, method, arguments) -> {
                if (method.getName().equals("isOpen")) return open;
                if (method.getName().equals("getSemanticResources")) return semantic;
                if (method.getName().equals("getSessionResource")) return sessionResource;
                if (method.getName().equals("getAllSessionResources")) return allSessionResources;
                if (method.getName().equals("equals")) return proxy == arguments[0];
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("toString")) return "Session[open=" + open + "]";
                throw new UnsupportedOperationException(method.getName());
            });
    }

    private static IFile file(String extension) {
        return (IFile) Proxy.newProxyInstance(IFile.class.getClassLoader(), new Class<?>[] { IFile.class },
            (proxy, method, arguments) -> {
                if (method.getName().equals("getFileExtension")) return extension;
                if (method.getName().equals("equals")) return proxy == arguments[0];
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("toString")) return "Selected model file";
                throw new UnsupportedOperationException(method.getName());
            });
    }
}
