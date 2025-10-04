package org.polarsys.capella.ai.session;

import org.eclipse.core.resources.IFile;
import org.eclipse.emf.common.util.URI;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.business.api.session.SessionManager;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/** Utility to access the current Capella Sirius Session in-process. */
public final class SessionBridge {
    private static final Logger LOG = Logger.getLogger(SessionBridge.class.getName());

    private SessionBridge() {}

    /** Get the active Sirius Session from the current editor or selection. */
    public static Session getActiveSession() {
        try {
            IWorkbench wb = PlatformUI.getWorkbench();
            IWorkbenchWindow win = wb.getActiveWorkbenchWindow();
            if (win == null) return null;
            IWorkbenchPage page = win.getActivePage();
            if (page != null) {
                IEditorPart editor = page.getActiveEditor();
                if (editor != null) {
                    IEditorInput input = editor.getEditorInput();
                    if (input instanceof IFileEditorInput) {
                        IFile file = ((IFileEditorInput) input).getFile();
                        URI uri = URI.createPlatformResourceURI(file.getFullPath().toString(), true);
                        Session s = SessionManager.INSTANCE.getSession(uri, null);
                        if (s != null && s.isOpen()) return s;
                    }
                }
                // Fallback: try selection
                ISelection sel = win.getSelectionService().getSelection();
                if (sel instanceof IStructuredSelection) {
                    Object first = ((IStructuredSelection) sel).getFirstElement();
                    // Try to adapt to IFile
                    if (first instanceof IFile) {
                        IFile file = (IFile) first;
                        URI uri = URI.createPlatformResourceURI(file.getFullPath().toString(), true);
                        Session s = SessionManager.INSTANCE.getSession(uri, null);
                        if (s != null && s.isOpen()) return s;
                    }
                }
            }
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Failed to resolve active Capella session", ex);
        }
        return null;
    }
}
