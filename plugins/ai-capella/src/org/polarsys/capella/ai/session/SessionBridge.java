package org.polarsys.capella.ai.session;

import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.common.util.URI;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.business.api.session.SessionManager;
import org.eclipse.sirius.business.api.session.factory.SessionFactory;
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
        } catch (Exception ignore) {}
        return out;
    }
}
