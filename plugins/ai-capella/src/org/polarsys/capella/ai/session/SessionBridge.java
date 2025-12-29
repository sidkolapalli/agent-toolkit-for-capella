package org.polarsys.capella.ai.session;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
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
                    List<EObject> selected = extractSemanticSelection(win, editor);
                    DRepresentation activeDiagram = extractDiagramFromEditor(editor);
                    holder[0] = SelectionContext.of(selected, activeDiagram);
                } catch (Exception ex) {
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
            if (sel instanceof IStructuredSelection) {
                for (Object o : ((IStructuredSelection) sel).toArray()) {
                    EObject eo = toSemanticEObject(o);
                    if (eo != null) out.add(eo);
                }
            }
        } catch (Exception ignored) {
            // best effort
        }

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
        } catch (Throwable ignored) {
            // best effort
        }

        return java.util.List.copyOf(out);
    }

    private static EObject toSemanticEObject(Object o) {
        if (o == null) return null;
        if (o instanceof EObject) return (EObject) o;
        if (o instanceof DSemanticDecorator) {
            try {
                return ((DSemanticDecorator) o).getTarget();
            } catch (Exception ignored) {
                return null;
            }
        }
        if (o instanceof IAdaptable) {
            try {
                Object adapted = ((IAdaptable) o).getAdapter(EObject.class);
                if (adapted instanceof EObject) return (EObject) adapted;
            } catch (Exception ignored) {}
            try {
                Object adapted =
                    ((IAdaptable) o).getAdapter(DSemanticDecorator.class);
                if (adapted instanceof DSemanticDecorator) {
                    return ((DSemanticDecorator) adapted).getTarget();
                }
            } catch (Exception ignored) {}
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
                    if (eo instanceof EObject) return (EObject) eo;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {
            // best effort
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
        } catch (Throwable ignored) {
            // best effort
        }

        // Fallback: editor adaptation (best effort)
        try {
            if (editor instanceof IAdaptable) {
                Object rep = ((IAdaptable) editor).getAdapter(DRepresentation.class);
                if (rep instanceof DRepresentation) {
                    return (DRepresentation) rep;
                }
            }
        } catch (Exception ignored) {
            // best effort
        }

        // Fallback: last diagram opened via our tooling
        try {
            if (org.polarsys.capella.ai.tools.DiagramCommands.lastOpened instanceof DRepresentation) {
                return (DRepresentation) org.polarsys.capella.ai.tools.DiagramCommands.lastOpened;
            }
        } catch (Exception ignored) {
            // best effort
        }
        return null;
    }

    /** Get the active Sirius Session from the current editor or selection. */
    public static Session getActiveSession() {
        // First try UI-based detection (works when called from UI thread)
        try {
            IWorkbench wb = PlatformUI.getWorkbench();
            IWorkbenchWindow win = wb.getActiveWorkbenchWindow();
            if (win != null) {
                IWorkbenchPage page = win.getActivePage();
                if (page != null) {
                    IEditorPart editor = page.getActiveEditor();
                    if (editor != null) {
                        IEditorInput input = editor.getEditorInput();
                        if (input instanceof IFileEditorInput) {
                            IFile file = ((IFileEditorInput) input).getFile();
                            URI uri = URI.createPlatformResourceURI(
                                file.getFullPath().toString(),
                                true
                            );
                            Session s = SessionManager.INSTANCE.getSession(
                                uri,
                                null
                            );
                            if (s != null && s.isOpen()) return s;
                        }
                    }
                    // Fallback: try selection
                    ISelection sel = win.getSelectionService().getSelection();
                    if (sel instanceof IStructuredSelection) {
                        Object first =
                            ((IStructuredSelection) sel).getFirstElement();
                        if (first instanceof IFile) {
                            IFile file = (IFile) first;
                            URI uri = URI.createPlatformResourceURI(
                                file.getFullPath().toString(),
                                true
                            );
                            Session s = SessionManager.INSTANCE.getSession(
                                uri,
                                null
                            );
                            if (s != null && s.isOpen()) return s;
                        }
                    }
                }
            }
        } catch (Exception ex) {
            LOG.log(
                Level.FINE,
                "UI-based session detection failed (expected for non-UI threads)",
                ex
            );
        }

        // Always try SessionManager fallback - works from any thread
        // If exactly one open session exists, use it
        try {
            java.util.Collection<Session> sessions =
                SessionManager.INSTANCE.getSessions();
            if (sessions != null) {
                Session chosen = null;
                int openCount = 0;
                for (Session s : sessions) {
                    if (s != null && s.isOpen()) {
                        chosen = s;
                        openCount++;
                    }
                }
                // Return the session if exactly one is open, or the first one if multiple
                if (openCount == 1) {
                    return chosen;
                } else if (openCount > 1) {
                    // Multiple sessions - return first open one but log warning
                    LOG.log(
                        Level.INFO,
                        "Multiple open sessions detected (" +
                            openCount +
                            "), using first one"
                    );
                    for (Session s : sessions) {
                        if (s != null && s.isOpen()) return s;
                    }
                }
            }
        } catch (Exception ex) {
            LOG.log(
                Level.WARNING,
                "Failed to query SessionManager for open sessions",
                ex
            );
        }

        return null;
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
            System.err.println(
                "[SessionBridge] Error listing sessions: " + ex.getMessage()
            );
        }
        return out;
    }
}
