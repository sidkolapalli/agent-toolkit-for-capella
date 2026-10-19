package org.polarsys.capella.ai.session;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.WeakHashMap;
import java.lang.ref.WeakReference;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.business.api.session.SessionManager;
import org.eclipse.sirius.business.api.session.factory.SessionFactory;
import org.eclipse.sirius.viewpoint.DRepresentation;
import org.eclipse.sirius.viewpoint.DSemanticDecorator;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/** Utility to access the current Capella Sirius Session in-process. */
public final class SessionBridge {

    private static final Logger LOG = Logger.getLogger(
        SessionBridge.class.getName()
    );
    // Accessed only on the workbench display thread. Chat focus must not undo a model selection.
    private static final Map<IWorkbenchWindow, RememberedSelection> MODEL_SELECTIONS = new WeakHashMap<>();

    private record RememberedSelection(WeakReference<Session> session, WeakReference<IEditorPart> editor,
            List<WeakReference<EObject>> elements) {}

    static record UiResolution(Session session, boolean authoritative) {}

    private SessionBridge() {}

    /**
     * Capture current UI selection and active diagram as an immutable snapshot.
     *
     * <p>Thread-safe: captures on the UI thread when the workbench is running.
     * Returns {@link SelectionContext#empty()} when UI context is unavailable.
     */
    public static SelectionContext getSelectionContext() {
        try {
            if (!PlatformUI.isWorkbenchRunning()) {
                return SelectionContext.empty();
            }
            Display display = Display.getDefault();
            if (display == null || display.isDisposed()) {
                return SelectionContext.empty();
            }

            final SelectionContext[] holder = new SelectionContext[] {
                SelectionContext.empty(),
            };
            Runnable capture = () -> {
                try {
                    IWorkbench wb = PlatformUI.getWorkbench();
                    IWorkbenchWindow win = wb.getActiveWorkbenchWindow();
                    if (win == null) {
                        holder[0] = SelectionContext.empty();
                        return;
                    }
                    IWorkbenchPage page = win.getActivePage();
                    IEditorPart editor = page != null ? page.getActiveEditor() : null;
                    UiResolution resolved = resolveUiSession(win);
                    Session session = resolved.session();
                    if (session == null && !resolved.authoritative()) {
                        session = chooseSingleOpenSession(SessionManager.INSTANCE.getSessions());
                    }
                    List<EObject> selected = extractSemanticSelection(win, editor);
                    RememberedSelection remembered = MODEL_SELECTIONS.get(win);
                    selected = chooseSemanticSelection(session, selected,
                        remembered != null ? remembered.session().get() : null,
                        remembered != null ? remembered.elements() : List.of());
                    DRepresentation activeDiagram = extractDiagramFromEditor(editor);
                    holder[0] = filterSelectionContext(session, selected, activeDiagram);
                } catch (Exception ex) {
                    LOG.log(Level.FINE, "Failed to capture selection context", ex);
                    holder[0] = SelectionContext.empty();
                }
            };

            if (Display.getCurrent() == display) {
                capture.run();
            } else {
                display.syncExec(capture);
            }
            return holder[0] != null ? holder[0] : SelectionContext.empty();
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Selection context unavailable", ex);
            return SelectionContext.empty();
        }
    }

    /** Convenience: best-effort selected semantic elements. */
    public static java.util.List<EObject> getSelectedSemanticElements() {
        return getSelectionContext().getSelectedElements();
    }

    /** Convenience: best-effort active diagram representation. */
    public static Optional<DRepresentation> getActiveDiagram() {
        return getSelectionContext().getActiveDiagram();
    }

    private static java.util.List<EObject> extractSemanticSelection(
        IWorkbenchWindow win,
        IEditorPart editor
    ) {
        LinkedHashSet<EObject> out = new LinkedHashSet<>();

        // 1) Selection service (Project Explorer, etc.)
        try {
            ISelection sel =
                win != null ? win.getSelectionService().getSelection() : null;
            out.addAll(semanticElements(sel));
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Failed to read workbench selection", ex);
        }
        // An explicit semantic selection supersedes stale selection in a background diagram editor.
        if (!out.isEmpty()) return List.copyOf(out);

        // 2) Diagram editor selection (if active editor is a Sirius diagram editor)
        try {
            if (editor != null) {
                Class<?> edClass = Class.forName(
                    "org.eclipse.sirius.diagram.ui.tools.api.editor.DDiagramEditor"
                );
                if (edClass.isInstance(editor)) {
                    var m = edClass.getMethod("getDiagramGraphicalViewer");
                    Object viewerObj = m.invoke(editor);
                    if (viewerObj instanceof org.eclipse.gef.EditPartViewer) {
                        ISelection sel = ((org.eclipse.gef.EditPartViewer) viewerObj).getSelection();
                        if (sel instanceof IStructuredSelection) {
                            for (Object o : ((IStructuredSelection) sel).toArray()) {
                                EObject eo = extractSemanticFromDiagramElement(o);
                                if (eo != null) out.add(eo);
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Failed to read diagram editor selection", ex);
        }

        return java.util.List.copyOf(out);
    }

    private static List<EObject> semanticElements(ISelection selection) {
        LinkedHashSet<EObject> elements = new LinkedHashSet<>();
        if (selection instanceof IStructuredSelection structured) {
            for (Object value : structured.toArray()) {
                EObject semantic = toSemanticEObject(value);
                if (semantic != null) elements.add(semantic);
            }
        }
        return List.copyOf(elements);
    }

    private static EObject toSemanticEObject(Object o) {
        if (o == null) return null;
        if (o instanceof DSemanticDecorator) {
            try {
                return ((DSemanticDecorator) o).getTarget();
            } catch (Exception ex) {
                LOG.log(Level.FINE, "Failed to resolve DSemanticDecorator target", ex);
                return null;
            }
        }
        if (o instanceof EObject) return (EObject) o;
        if (o instanceof IAdaptable) {
            try {
                Object adapted =
                    ((IAdaptable) o).getAdapter(DSemanticDecorator.class);
                if (adapted instanceof DSemanticDecorator) {
                    return ((DSemanticDecorator) adapted).getTarget();
                }
            } catch (Exception ex) {
                LOG.log(
                    Level.FINE,
                    "Failed to adapt selection to DSemanticDecorator",
                    ex
                );
            }
            try {
                Object adapted = ((IAdaptable) o).getAdapter(EObject.class);
                if (adapted instanceof EObject) return toSemanticEObject(adapted);
            } catch (Exception ex) {
                LOG.log(Level.FINE, "Failed to adapt selection to EObject", ex);
            }
        }
        return extractSemanticFromDiagramElement(o);
    }

    private static EObject extractSemanticFromDiagramElement(Object o) {
        if (o == null) return null;
        try {
            // Most Sirius diagram selections are IGraphicalEditPart
            if (o instanceof org.eclipse.gef.EditPart) {
                try {
                    java.lang.reflect.Method m = o
                        .getClass()
                        .getMethod("resolveSemanticElement");
                    Object eo = m.invoke(o);
                    if (eo instanceof EObject) return toSemanticEObject(eo);
                } catch (Exception ex) {
                    LOG.log(
                        Level.FINE,
                        "Failed to resolve semantic element from diagram selection",
                        ex
                    );
                }
            }
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Failed to inspect diagram selection element", ex);
        }
        return null;
    }

    private static DRepresentation extractDiagramFromEditor(IEditorPart editor) {
        if (editor == null) {
            return null;
        }
        // Prefer the standard DialectEditor interface when available.
        try {
            Class<?> dialectEditor = Class.forName(
                "org.eclipse.sirius.ui.business.api.dialect.DialectEditor"
            );
            if (dialectEditor.isInstance(editor)) {
                java.lang.reflect.Method m =
                    dialectEditor.getMethod("getRepresentation");
                Object rep = m.invoke(editor);
                if (rep instanceof DRepresentation) {
                    return (DRepresentation) rep;
                }
            }
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Failed to extract active diagram from editor", ex);
        }

        // Fallback: editor adaptation (best effort)
        try {
            if (editor instanceof IAdaptable) {
                Object rep = ((IAdaptable) editor).getAdapter(DRepresentation.class);
                if (rep instanceof DRepresentation) {
                    return (DRepresentation) rep;
                }
            }
        } catch (Exception ex) {
            LOG.log(
                Level.FINE,
                "Failed to adapt active editor to DRepresentation",
                ex
            );
        }

        return null;
    }

    /** Get the active Sirius Session from the current editor or selection. */
    public static Session getActiveSession() {
        // Read editor/selection state on its owning SWT thread.
        try {
            if (PlatformUI.isWorkbenchRunning()) {
                IWorkbench workbench = PlatformUI.getWorkbench();
                Display display = workbench.getDisplay();
                if (display != null && !display.isDisposed()) {
                    UiResolution[] detected = { new UiResolution(null, false) };
                    Runnable detect = () -> detected[0] = resolveUiSession(workbench.getActiveWorkbenchWindow());
                    if (Display.getCurrent() == display) detect.run();
                    else display.syncExec(detect);
                    if (detected[0].session() != null || detected[0].authoritative()) return detected[0].session();
                }
            }
        } catch (Exception ex) {
            LOG.log(
                Level.FINE,
                "UI-based session detection failed",
                ex
            );
        }

        // Preserve single-session headless use, but never choose between models arbitrarily.
        try {
            return chooseSingleOpenSession(SessionManager.INSTANCE.getSessions());
        } catch (Exception ex) {
            LOG.log(
                Level.WARNING,
                "Failed to query SessionManager for open sessions",
                ex
            );
        }

        return null;
    }

    private static Session findUiSession(IWorkbenchWindow window) {
        return resolveUiSession(window).session();
    }

    private static UiResolution resolveUiSession(IWorkbenchWindow window) {
        if (window == null) return new UiResolution(null, false);
        IWorkbenchPage page = window.getActivePage();
        IEditorPart editor = page != null ? page.getActiveEditor() : null;
        boolean editorActive = editor != null && page.getActivePart() == editor;
        ISelection selection = window.getSelectionService().getSelection();
        UiResolution selected = resolveSelectionSession(selection, SessionBridge::sessionForSelection);
        Session editorSession = editorActive || !selected.authoritative() ? sessionForEditor(editor) : null;
        boolean modelEditorActive = editorActive && editorSession != null;
        RememberedSelection previous = MODEL_SELECTIONS.get(window);
        Session remembered = previous != null ? previous.session().get() : null;
        boolean sameEditor = previous != null && previous.editor().get() == editor;
        UiResolution result = chooseUiSession(selected, editorSession, modelEditorActive, remembered, sameEditor);
        if (modelEditorActive) {
            MODEL_SELECTIONS.remove(window);
        } else if (selected.authoritative()) {
            if (selected.session() != null) {
                List<WeakReference<EObject>> elements = semanticElements(selection).stream()
                    .filter(value -> belongsToSession(selected.session(), value))
                    .map(value -> new WeakReference<>(value)).toList();
                MODEL_SELECTIONS.put(window, new RememberedSelection(new WeakReference<>(selected.session()),
                    new WeakReference<>(editor), elements));
            } else {
                MODEL_SELECTIONS.remove(window);
            }
        } else if (!sameEditor || remembered == null || !remembered.isOpen()) {
            MODEL_SELECTIONS.remove(window);
        }
        return result;
    }

    static UiResolution chooseUiSession(UiResolution selection, Session editor, boolean editorActive,
            Session remembered, boolean sameEditor) {
        if (editorActive && editor != null && editor.isOpen()) return new UiResolution(editor, true);
        if (selection.authoritative()) return selection;
        // Deliberately activating even the same old editor clears the earlier Project Explorer override.
        if (!editorActive && sameEditor && remembered != null && remembered.isOpen()) {
            return new UiResolution(remembered, true);
        }
        return new UiResolution(editor != null && editor.isOpen() ? editor : null, false);
    }

    static UiResolution resolveSelectionSession(ISelection selection, Function<Object, Session> resolver) {
        if (!(selection instanceof IStructuredSelection structured)) return new UiResolution(null, false);
        Session chosen = null;
        boolean modelSelection = false;
        boolean unresolved = false;
        for (Object value : structured.toArray()) {
            Session candidate = resolver.apply(value);
            if (candidate != null) {
                modelSelection = true;
                if (chosen != null && chosen != candidate) return new UiResolution(null, true);
                chosen = candidate;
            } else if (isModelSelection(value)) {
                modelSelection = true;
                unresolved = true;
            }
        }
        return new UiResolution(!unresolved && chosen != null && chosen.isOpen() ? chosen : null, modelSelection);
    }

    private static boolean isModelSelection(Object value) {
        EObject semantic = toSemanticEObject(value);
        if (semantic != null && semantic.eResource() != null && semantic.eResource().getURI() != null) {
            String extension = semantic.eResource().getURI().fileExtension();
            if ("capella".equals(extension) || "melodymodeller".equals(extension) || "aird".equals(extension)) return true;
        }
        IFile file = selectedFile(value);
        return file != null && ("capella".equals(file.getFileExtension()) || "melodymodeller".equals(file.getFileExtension()) ||
            "aird".equals(file.getFileExtension()));
    }

    private static Session sessionForSelection(Object value) {
        Session session = sessionForElement(toSemanticEObject(value));
        if (session != null) return session;
        IFile file = selectedFile(value);
        return file != null ? sessionForFile(file) : null;
    }

    private static IFile selectedFile(Object value) {
        if (value instanceof IFile file) return file;
        if (value instanceof IAdaptable adaptable) return adaptable.getAdapter(IFile.class);
        return null;
    }

    private static Session sessionForEditor(IEditorPart editor) {
        if (editor != null) {
            // Sirius diagram inputs are not necessarily IFileEditorInput.
            DRepresentation representation = extractDiagramFromEditor(editor);
            Session session = sessionForElement(representation);
            if (session != null) return session;
            session = sessionForElement(editor.getAdapter(EObject.class));
            if (session != null) return session;
            IEditorInput input = editor.getEditorInput();
            if (input instanceof IFileEditorInput fileInput) {
                session = sessionForFile(fileInput.getFile());
                if (session != null) return session;
            }
        }
        return null;
    }

    static SelectionContext filterSelectionContext(Session session, List<EObject> selected, DRepresentation diagram) {
        if (session == null || !session.isOpen()) return SelectionContext.empty();
        List<EObject> owned = selected.stream().filter(value -> belongsToSession(session, value)).toList();
        if (diagram != null) {
            Resource resource = diagram.eResource();
            boolean ownedDiagram = resource != null && (resource == session.getSessionResource() ||
                session.getAllSessionResources().contains(resource));
            if (!ownedDiagram || (diagram instanceof DSemanticDecorator decorator && !belongsToSession(session, decorator.getTarget()))) {
                diagram = null;
            }
        }
        return SelectionContext.of(owned, diagram);
    }

    static List<EObject> chooseSemanticSelection(Session session, List<EObject> current,
            Session rememberedSession, List<WeakReference<EObject>> remembered) {
        if (session == null || !session.isOpen()) return List.of();
        List<EObject> owned = current.stream().filter(value -> belongsToSession(session, value)).toList();
        if (!owned.isEmpty()) return owned;
        if (rememberedSession != session) return List.of();
        // A chat control has no semantic selection. Reuse only live elements still owned by this model.
        return remembered.stream().map(WeakReference::get)
            .filter(value -> belongsToSession(session, value)).distinct().toList();
    }

    private static boolean belongsToSession(Session session, EObject value) {
        return value != null && !value.eIsProxy() && value.eResource() != null &&
            session.getSemanticResources().contains(value.eResource());
    }

    private static Session sessionForElement(EObject element) {
        if (element == null) return null;
        EObject semantic = toSemanticEObject(element);
        Session session = SessionManager.INSTANCE.getSession(semantic != null ? semantic : element);
        return session != null && session.isOpen() ? session : null;
    }

    private static Session sessionForFile(IFile file) {
        URI uri = URI.createPlatformResourceURI(file.getFullPath().toString(), true);
        Session session = SessionManager.INSTANCE.getExistingSession(uri);
        if (session != null && session.isOpen()) return session;
        // A selected .capella file is a semantic resource, rather than a session URI.
        Session match = null;
        for (Session candidate : SessionManager.INSTANCE.getSessions()) {
            if (candidate != null && candidate.isOpen()) {
                for (org.eclipse.emf.ecore.resource.Resource resource : candidate.getSemanticResources()) {
                    if (uri.equals(resource.getURI())) {
                        if (match != null && match != candidate) return null;
                        match = candidate;
                        break;
                    }
                }
            }
        }
        return match;
    }

    private static Session chooseSingleOpenSession(Collection<Session> sessions) {
        if (sessions == null) return null;
        Session chosen = null;
        for (Session session : sessions) {
            if (session == null || !session.isOpen()) continue;
            if (chosen != null && chosen != session) return null;
            chosen = session;
        }
        return chosen;
    }

    /** Open a Capella session for the given .aird URI (headless-friendly). */
    public static Session openSession(URI airdUri) {
        if (airdUri == null) return null;
        try {
            Session s = SessionManager.INSTANCE.getSession(
                airdUri,
                new NullProgressMonitor()
            );
            if (s == null) {
                s = SessionFactory.INSTANCE.createSession(
                    airdUri,
                    new NullProgressMonitor()
                );
            }
            if (s != null && !s.isOpen()) {
                s.open(new NullProgressMonitor());
            }
            return s;
        } catch (Exception ex) {
            LOG.log(
                Level.WARNING,
                "Failed to open Capella session: " + airdUri,
                ex
            );
            return null;
        }
    }

    /** Close a Capella session if open. */
    public static void closeSession(Session session) {
        if (session == null) return;
        try {
            if (session.isOpen()) {
                session.close(new NullProgressMonitor());
            }
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Failed to close Capella session", ex);
        }
    }

    /** List all open sessions. */
    public static java.util.List<Session> listOpenSessions() {
        java.util.List<Session> out = new java.util.ArrayList<>();
        try {
            for (Session s : SessionManager.INSTANCE.getSessions()) {
                if (s != null && s.isOpen()) out.add(s);
            }
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Error listing sessions", ex);
        }
        return out;
    }
}
