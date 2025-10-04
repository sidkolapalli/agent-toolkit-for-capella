package org.polarsys.capella.ai.session;

import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.function.Supplier;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.transaction.RecordingCommand;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.swt.widgets.Display;
import org.polarsys.capella.ai.Activator;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.sirius.business.api.session.Session;

/**
 * Centralised safeguards for interacting with the live Capella session.
 * Ensures write operations run on the UI thread in a single EMF command and
 * allows callers to defer saves so undo/redo remains usable.
 */
public final class SessionSafeguards {
    private static final Logger LOG = Logger.getLogger(SessionSafeguards.class.getName());

    private static final ThreadLocal<Boolean> IN_WRITE = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<Integer> DEFER_SAVE_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Boolean> SAVE_DIRTY = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private SessionSafeguards() {}

    /** Returns true when already executing inside a managed write transaction. */
    public static boolean isInWriteContext() {
        return Boolean.TRUE.equals(IN_WRITE.get());
    }

    /** Execute the supplier within a single RecordingCommand on the UI thread. */
    public static <T> T runWrite(Session session, String label, Supplier<T> supplier) {
        if (session == null) {
            return supplier.get();
        }
        TransactionalEditingDomain domain = session.getTransactionalEditingDomain();
        if (domain == null) {
            return supplier.get();
        }
        if (isInWriteContext()) {
            return supplier.get();
        }
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        Runnable execute = () -> {
            try {
                domain.getCommandStack().execute(new RecordingCommand(domain, label) {
                    @Override
                    protected void doExecute() {
                        IN_WRITE.set(Boolean.TRUE);
                        try {
                            result.set(supplier.get());
                        } catch (RuntimeException ex) {
                            failure.set(ex);
                            throw ex;
                        } finally {
                            IN_WRITE.set(Boolean.FALSE);
                        }
                    }
                });
            } catch (RuntimeException ex) {
                failure.set(ex);
                throw ex;
            }
        };
        runOnUiThread(execute);
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    /** Execute the supplier while deferring session saves until completion. */
    public static <T> T deferSaves(Session session, Supplier<T> supplier) {
        DEFER_SAVE_DEPTH.set(DEFER_SAVE_DEPTH.get() + 1);
        try {
            return supplier.get();
        } finally {
            int depth = DEFER_SAVE_DEPTH.get() - 1;
            DEFER_SAVE_DEPTH.set(depth);
            if (depth == 0 && Boolean.TRUE.equals(SAVE_DIRTY.get())) {
                SAVE_DIRTY.set(Boolean.FALSE);
                saveNow(session);
            }
        }
    }

    /** Request a save; if currently deferring, mark the session dirty instead. */
    public static void requestSave(Session session) {
        if (session == null) {
            return;
        }
        if (DEFER_SAVE_DEPTH.get() > 0) {
            SAVE_DIRTY.set(Boolean.TRUE);
            return;
        }
        saveNow(session);
    }

    private static void saveNow(Session session) {
        try {
            session.save(new NullProgressMonitor());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Session save failed", e);
            if (Activator.getDefault() != null) {
                Activator.getDefault().getLog().log(new Status(IStatus.WARNING, Activator.PLUGIN_ID, e.getMessage(), e));
            }
        }
    }

    /** Ensure the runnable executes on the SWT UI thread. */
    public static void runOnUiThread(Runnable runnable) {
        if (runnable == null) {
            return;
        }
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            runnable.run();
            return;
        }
        if (Display.getCurrent() == display) {
            runnable.run();
        } else {
            display.syncExec(runnable);
        }
    }

    /** Clear ThreadLocal state to avoid leaks when the plug-in stops. */
    public static void cleanup() {
        IN_WRITE.remove();
        DEFER_SAVE_DEPTH.remove();
        SAVE_DIRTY.remove();
    }
}
