package org.polarsys.capella.ai.session;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.emf.common.command.Command;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.transaction.RecordingCommand;
import org.eclipse.emf.transaction.RollbackException;
import org.eclipse.emf.transaction.Transaction;
import org.eclipse.emf.transaction.TransactionalCommandStack;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.emf.transaction.TransactionalEditingDomainEvent;
import org.eclipse.emf.transaction.TransactionalEditingDomainListenerImpl;
import org.eclipse.emf.transaction.util.TransactionUtil;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.swt.widgets.Display;
import org.polarsys.capella.ai.model.ActionResult;
import org.polarsys.capella.ai.model.ActionResult.ErrorCode;
import org.polarsys.capella.ai.model.ModelSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Centralised safeguards for interacting with the live Capella session. Ensures write operations
 * run on the UI thread in a single EMF command and allows callers to defer saves so undo/redo
 * remains usable.
 */
public final class SessionSafeguards {
    private static final Logger LOG = Logger.getLogger(SessionSafeguards.class.getName());

    private record WriteContext(Session session, TransactionalEditingDomain domain) {}

    private static final ThreadLocal<WriteContext> IN_WRITE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> COMMITTING = new ThreadLocal<>();
    private static final ThreadLocal<Integer> DEFER_SAVE_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Boolean> SAVE_DIRTY =
            ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<ExecutionControl> EXECUTION = new ThreadLocal<>();

    /** Cooperative script deadline, shared by the worker and dispatched UI writes. */
    public static final class ExecutionControl {
        private final long startedNanos = System.nanoTime();
        private final long budgetNanos;
        private final ExecutionControl parent;
        private volatile boolean cancelled;
        private volatile Throwable rollbackFailure;
        private int activeWrites;
        private volatile boolean committed;
        private volatile boolean saved;
        private volatile Object committedValue;
        private volatile SaveFailedAfterCommitException saveFailure;

        public ExecutionControl(long timeoutMs) {
            this(timeoutMs, null);
        }

        /** Nested scripts share cancellation and cannot outlive their parent deadline. */
        public ExecutionControl(long timeoutMs, ExecutionControl parent) {
            if (timeoutMs <= 0) {
                throw new IllegalArgumentException("Script timeout must be positive");
            }
            budgetNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            this.parent = parent;
        }

        public boolean isCancelled() {
            return cancelled
                    || System.nanoTime() - startedNanos >= budgetNanos
                    || (parent != null && parent.isCancelled());
        }

        public long remainingNanos() {
            long remaining = Math.max(0L, budgetNanos - (System.nanoTime() - startedNanos));
            return parent == null ? remaining : Math.min(remaining, parent.remainingNanos());
        }

        public void checkCancelled() {
            if (isCancelled()) {
                throw new ScriptCancelledException("Script execution was cancelled or timed out");
            }
            if (rollbackFailure != null) {
                throw new IllegalStateException(
                        "A nested script failed; the entire model operation must roll back: "
                                + rollbackFailure.getMessage(),
                        rollbackFailure);
            }
        }

        public synchronized void cancel() {
            cancelled = true;
            if (parent != null) parent.cancel();
        }

        /** A caught or ignored child failure must never allow its partial changes to commit. */
        public synchronized void markRollbackOnly(Throwable failure) {
            if (rollbackFailure == null) rollbackFailure = failure;
            if (parent != null) parent.markRollbackOnly(failure);
        }

        private void recordCommit(Object value) {
            committedValue = value;
            committed = true;
        }

        public boolean wasCommitted() {
            return committed;
        }

        public boolean wasSaved() {
            return saved;
        }

        public Object committedValue() {
            return committedValue;
        }

        public SaveFailedAfterCommitException saveFailure() {
            return saveFailure;
        }

        private synchronized void beginWrite() {
            checkCancelled();
            activeWrites++;
        }

        private synchronized void endWrite() {
            activeWrites--;
            notifyAll();
        }

        /** Do not report cancellation while a dispatched write can still mutate the model. */
        public void awaitWritesFinished() {
            boolean interrupted = false;
            synchronized (this) {
                while (activeWrites != 0) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Unchecked so cancellation escapes a RecordingCommand and triggers rollback. */
    public static final class ScriptCancelledException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ScriptCancelledException(String message) {
            // Groovy's injected exception message references synthetic AST source positions.
            super("Script execution was cancelled or timed out");
        }
    }

    /** The EMF command committed, but its resource save failed; never reapply the script. */
    public static final class SaveFailedAfterCommitException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public SaveFailedAfterCommitException(Throwable cause) {
            super(
                    "Model changes were applied, but saving failed. Save the model again; do not"
                        + " rerun the script.",
                    cause);
        }
    }

    /** Used by injected Groovy checks, including closures dispatched to SWT. */
    public static boolean isScriptCancellationRequested() {
        ExecutionControl control = EXECUTION.get();
        return control != null && control.isCancelled();
    }

    public static <T> T withExecutionControl(ExecutionControl control, Supplier<T> supplier) {
        ExecutionControl previous = EXECUTION.get();
        if (control == null) {
            EXECUTION.remove();
        } else {
            EXECUTION.set(control);
        }
        try {
            if (control != null) {
                control.checkCancelled();
            }
            return supplier.get();
        } finally {
            if (previous == null) {
                EXECUTION.remove();
            } else {
                EXECUTION.set(previous);
            }
        }
    }

    private static void checkScriptCancellation() {
        ExecutionControl control = EXECUTION.get();
        if (control != null) {
            control.checkCancelled();
        }
    }

    private SessionSafeguards() {}

    /** Returns true when already executing inside a managed write transaction. */
    public static boolean isInWriteContext() {
        return IN_WRITE.get() != null;
    }

    /**
     * True inside a managed write or while its transaction commits on this thread. Pre-commit
     * triggers (Sirius diagram refresh) run after the supplier returns but belong to the same
     * transaction and Undo, so they are part of the reviewed change.
     */
    public static boolean isInManagedTransaction() {
        return IN_WRITE.get() != null || COMMITTING.get() != null;
    }

    /** A managed command may only reuse its exact captured session and native domain. */
    private static boolean requireMatchingWriteContext(
            Session session, TransactionalEditingDomain domain) {
        WriteContext context = IN_WRITE.get();
        if (context == null) return false;
        if (session != context.session()
                || domain != context.domain()
                || context.session().getTransactionalEditingDomain() != context.domain()) {
            throw new WriteScopeMismatchException();
        }
        return true;
    }

    private static final class WriteScopeMismatchException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        WriteScopeMismatchException() {
            super(
                    "Nested model writes must use the captured session and transactional editing"
                        + " domain");
        }
    }

    private static void restoreWriteContext(WriteContext previous) {
        if (previous == null) IN_WRITE.remove();
        else IN_WRITE.set(previous);
    }

    /** Execute the supplier within a single RecordingCommand on the UI thread. */
    public static <T> T runWrite(Session session, String label, Supplier<T> supplier) {
        checkScriptCancellation();
        TransactionalEditingDomain domain =
                session == null ? null : session.getTransactionalEditingDomain();
        if (requireMatchingWriteContext(session, domain)) {
            return supplier.get();
        }
        if (session == null) {
            return supplier.get();
        }
        if (domain == null) {
            throw new IllegalStateException(
                    "No transactional editing domain available for model writes");
        }
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        Runnable execute =
                () -> {
                    ExecutionControl control = EXECUTION.get();
                    boolean entered = false;
                    try {
                        // A worker can be dispatched during a reentrant SWT event in another
                        // command.
                        if (requireMatchingWriteContext(session, domain)) {
                            result.set(supplier.get());
                            checkScriptCancellation();
                            return;
                        }
                        if (session.getTransactionalEditingDomain() != domain) {
                            throw new IllegalStateException(
                                    "The captured session's transactional editing domain changed"
                                        + " before writing");
                        }
                        if (control != null) {
                            control.beginWrite();
                            entered = true;
                        }
                        // The save scope must be on the same UI thread as the transaction.
                        // It closes only after execute(command, options) has committed
                        // successfully.
                        deferSaves(
                                session,
                                () -> {
                                    RollbackDirtyState dirtyState =
                                            new RollbackDirtyState(session, domain);
                                    boolean commandCommitted = false;
                                    try {
                                        RecordingCommand command =
                                                new RecordingCommand(domain, label) {
                                                    @Override
                                                    protected void doExecute() {
                                                        WriteContext previous = IN_WRITE.get();
                                                        requireMatchingWriteContext(
                                                                session, domain);
                                                        IN_WRITE.set(
                                                                new WriteContext(session, domain));
                                                        // Set once the transaction lock is held; it
                                                        // stays set through the commit's triggers.
                                                        COMMITTING.set(Boolean.TRUE);
                                                        boolean isRoot =
                                                                OperationInferencer.begin();
                                                        try {
                                                            result.set(supplier.get());
                                                            checkScriptCancellation();

                                                            if (isRoot) {
                                                                String inferred =
                                                                        OperationInferencer
                                                                                .endAndGenerateLabel();
                                                                if (inferred != null
                                                                        && !inferred.isBlank()) {
                                                                    if ("Execute Script"
                                                                                    .equals(label)
                                                                            || "LLM batch operation"
                                                                                    .equals(label)
                                                                            || (label != null
                                                                                    && label
                                                                                            .startsWith(
                                                                                                    "Create"
                                                                                                        + " "))) {
                                                                        setLabel(inferred);
                                                                    }
                                                                }
                                                            }
                                                        } catch (RuntimeException ex) {
                                                            failure.set(ex);
                                                            if (ex
                                                                            instanceof
                                                                            ScriptCancelledException
                                                                    || isScriptCancellationRequested()) {
                                                                throw new OperationCanceledException(
                                                                        "Script execution"
                                                                            + " cancelled");
                                                            }
                                                            throw ex;
                                                        } finally {
                                                            if (isRoot) {
                                                                OperationInferencer.clear();
                                                            }
                                                            restoreWriteContext(previous);
                                                        }
                                                    }
                                                };
                                        Boolean committingBefore = COMMITTING.get();
                                        try {
                                            ((TransactionalCommandStack) domain.getCommandStack())
                                                    .execute(command, dirtyState.options());
                                        } catch (InterruptedException ex) {
                                            Thread.currentThread().interrupt();
                                            throw new ScriptCancelledException(
                                                    "Model transaction was interrupted");
                                        } catch (RollbackException ex) {
                                            if (failure.get() != null) {
                                                throw failure.get();
                                            }
                                            throw new IllegalStateException(
                                                    "Model transaction rolled back: "
                                                            + ex.getMessage(),
                                                    ex);
                                        } finally {
                                            // Restore rather than clear: this write may run nested
                                            // in another command's commit.
                                            if (committingBefore == null) COMMITTING.remove();
                                            else COMMITTING.set(committingBefore);
                                        }
                                        if (failure.get() != null) {
                                            throw failure.get();
                                        }
                                        commandCommitted = true;
                                        if (control != null) {
                                            control.recordCommit(result.get());
                                        }
                                        return null;
                                    } finally {
                                        try {
                                            if (!commandCommitted)
                                                dirtyState.restoreAfterConfirmedRollback();
                                        } finally {
                                            dirtyState.close();
                                        }
                                    }
                                });
                    } catch (RuntimeException ex) {
                        // Queries inside a failed command may have cached uncommitted
                        // data. Rollback emits no normal post-commit invalidation event.
                        if (control == null || !control.wasCommitted())
                            ModelSummary.invalidate(session);
                        failure.set(ex);
                    } finally {
                        if (entered) {
                            control.endWrite();
                        }
                    }
                };
        runOnUiThread(execute);
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    /**
     * Resource modification tracking also sees the inverse notifications emitted by rollback.
     * Restore those flags only after native EMF confirms that this exact recorded transaction
     * reverted its changes. An absent undo command or a thrown script exception is not proof.
     */
    private static final class RollbackDirtyState extends TransactionalEditingDomainListenerImpl {
        private static final String SCOPE_OPTION =
                SessionSafeguards.class.getName() + ".rollbackScope";
        private final Object scope = new Object();
        private final Session session;
        private final TransactionalEditingDomain domain;
        private final TransactionalEditingDomain.Lifecycle lifecycle;
        private final ResourceSet resourceSet;
        private final Resource sessionResource;
        private final List<ResourceState> resources = new ArrayList<>();
        private final Command undoCommand;
        private final Command redoCommand;
        private final Thread owner = Thread.currentThread();
        private Transaction transaction;
        private boolean closed;
        private boolean disposed;

        RollbackDirtyState(Session session, TransactionalEditingDomain domain) {
            this.session = session;
            this.domain = domain;
            resourceSet = domain.getResourceSet();
            sessionResource = session.getSessionResource();
            undoCommand = domain.getCommandStack().getUndoCommand();
            redoCommand = domain.getCommandStack().getRedoCommand();
            lifecycle =
                    TransactionUtil.getAdapter(domain, TransactionalEditingDomain.Lifecycle.class);
            if (lifecycle != null) {
                for (Resource resource : resourceSet.getResources())
                    resources.add(new ResourceState(resource));
                lifecycle.addTransactionalEditingDomainListener(this);
            }
        }

        Map<String, Object> options() {
            return Map.of(SCOPE_OPTION, scope);
        }

        @Override
        public void transactionStarted(TransactionalEditingDomainEvent event) {
            Transaction value = event.getTransaction();
            if (event.getSource() == domain
                    && value != null
                    && value.getParent() == null
                    && !value.isReadOnly()
                    && value.getOwner() == owner
                    && value.getOptions().get(SCOPE_OPTION) == scope) transaction = value;
        }

        @Override
        public void transactionClosed(TransactionalEditingDomainEvent event) {
            if (event.getTransaction() == transaction && transaction != null) closed = true;
        }

        @Override
        public void editingDomainDisposing(TransactionalEditingDomainEvent event) {
            disposed = true;
        }

        void restoreAfterConfirmedRollback() {
            if (lifecycle == null
                    || disposed
                    || !closed
                    || transaction == null
                    || transaction.isActive()
                    || transaction.getStatus() == null
                    || !transaction.getStatus().matches(IStatus.ERROR | IStatus.CANCEL)
                    || Boolean.TRUE.equals(transaction.getOptions().get(Transaction.OPTION_NO_UNDO))
                    || Boolean.TRUE.equals(
                            transaction.getOptions().get(Transaction.OPTION_UNPROTECTED))) return;
            // Native TransactionImpl clears the recorded changes only after change.apply()
            // succeeds.
            // A rollback that itself throws can still emit transactionClosed with an error status.
            if (transaction.getChangeDescription() == null
                    || !transaction.getChangeDescription().isEmpty()) return;
            if (Thread.currentThread() != owner
                    || !session.isOpen()
                    || session.getTransactionalEditingDomain() != domain
                    || session.getSessionResource() != sessionResource
                    || domain.getResourceSet() != resourceSet
                    || domain.getCommandStack().getUndoCommand() != undoCommand
                    || domain.getCommandStack().getRedoCommand() != redoCommand
                    || resourceSet.getResources().size() != resources.size()) return;
            for (int index = 0; index < resources.size(); index++) {
                ResourceState state = resources.get(index);
                if (resourceSet.getResources().get(index) != state.resource
                        || !state.sameIdentity(resourceSet)) return;
            }
            try {
                for (ResourceState state : resources) {
                    if (state.resource.isModified() != state.modified)
                        state.resource.setModified(state.modified);
                }
            } catch (RuntimeException failure) {
                // Keep the original script/transaction failure; normal flag notifications must
                // remain enabled.
                LOG.log(
                        Level.WARNING,
                        "Could not restore resource modification state after confirmed rollback",
                        failure);
            }
        }

        void close() {
            if (lifecycle != null && !disposed)
                lifecycle.removeTransactionalEditingDomainListener(this);
        }
    }

    private static final class ResourceState {
        private final Resource resource;
        private final URI uri;
        private final boolean loaded;
        private final boolean tracking;
        private final boolean modified;
        private final List<EObject> roots;

        ResourceState(Resource resource) {
            this.resource = resource;
            uri = resource.getURI();
            loaded = resource.isLoaded();
            tracking = resource.isTrackingModification();
            modified = resource.isModified();
            roots = new ArrayList<>(resource.getContents());
        }

        boolean sameIdentity(ResourceSet resourceSet) {
            if (resource.getResourceSet() != resourceSet
                    || !Objects.equals(resource.getURI(), uri)
                    || resource.isLoaded() != loaded
                    || resource.isTrackingModification() != tracking
                    || resource.getContents().size() != roots.size()) return false;
            for (int index = 0; index < roots.size(); index++) {
                if (resource.getContents().get(index) != roots.get(index)) return false;
            }
            return true;
        }
    }

    /** Execute the supplier while deferring session saves until completion. */
    public static <T> T deferSaves(Session session, Supplier<T> supplier) {
        requireMatchingWriteContext(
                session, session == null ? null : session.getTransactionalEditingDomain());
        if (session == null) {
            throw new IllegalArgumentException("Session cannot be null");
        }
        DEFER_SAVE_DEPTH.set(DEFER_SAVE_DEPTH.get() + 1);
        boolean previouslyDirty = SAVE_DIRTY.get();
        boolean completed = false;
        try {
            T result = supplier.get();
            completed = true;
            return result;
        } finally {
            int depth = DEFER_SAVE_DEPTH.get() - 1;
            DEFER_SAVE_DEPTH.set(depth);
            if (!completed) {
                SAVE_DIRTY.set(previouslyDirty);
            }
            if (depth == 0 && completed && Boolean.TRUE.equals(SAVE_DIRTY.get())) {
                SAVE_DIRTY.set(Boolean.FALSE);
                try {
                    saveNow(session);
                    ExecutionControl control = EXECUTION.get();
                    if (control != null) {
                        control.saved = true;
                    }
                } catch (Exception e) {
                    SaveFailedAfterCommitException failure = new SaveFailedAfterCommitException(e);
                    ExecutionControl control = EXECUTION.get();
                    if (control != null) {
                        control.saveFailure = failure;
                    }
                    throw failure;
                }
            }
        }
    }

    /** Request a save; if currently deferring, mark the session dirty instead. */
    public static void requestSave(Session session) throws Exception {
        requireMatchingWriteContext(
                session, session == null ? null : session.getTransactionalEditingDomain());
        if (session == null) {
            return;
        }
        if (DEFER_SAVE_DEPTH.get() > 0) {
            SAVE_DIRTY.set(Boolean.TRUE);
            return;
        }
        saveNow(session);
    }

    /**
     * Save the session immediately.
     *
     * @throws Exception if save fails
     */
    private static void saveNow(Session session) throws Exception {
        session.save(new NullProgressMonitor());
        LOG.info("Session saved successfully");
    }

    /** Ensure the runnable executes on the SWT UI thread. */
    public static void runOnUiThread(Runnable runnable) {
        if (runnable == null) {
            return;
        }
        ExecutionControl control = EXECUTION.get();
        AtomicReference<Throwable> dispatchFailure = new AtomicReference<>();
        Runnable contextual =
                () -> {
                    try {
                        withExecutionControl(
                                control,
                                () -> {
                                    runnable.run();
                                    return null;
                                });
                    } catch (Throwable failure) {
                        // In particular, a queued script can be cancelled before its
                        // contextual write starts. Deliver that failure to its caller,
                        // instead of letting SWT report it as an uncaught UI exception.
                        dispatchFailure.set(failure);
                    }
                };
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            contextual.run();
        } else if (Display.getCurrent() == display) {
            contextual.run();
        } else {
            display.syncExec(contextual);
        }
        Throwable failure = dispatchFailure.get();
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
        if (failure != null) throw new IllegalStateException("UI model operation failed", failure);
    }

    // ========== ActionResult-aware Methods ==========

    /** Functional interface for operations that return ActionResult. */
    @FunctionalInterface
    public interface ActionSupplier {
        ActionResult execute();
    }

    /**
     * Execute an action within a single RecordingCommand on the UI thread, returning ActionResult
     * with diagnostic information on failure.
     *
     * <p>This method wraps the action in a transaction and converts any transaction-level failures
     * to appropriate ActionResult error codes.
     *
     * @param session the Capella session
     * @param label the command label (for undo/redo stack)
     * @param action the action to execute, must return ActionResult
     * @return ActionResult from the action, or error result on transaction failure
     */
    public static ActionResult runWriteWithResult(
            Session session, String label, ActionSupplier action) {
        TransactionalEditingDomain domain =
                session == null ? null : session.getTransactionalEditingDomain();
        boolean nested = requireMatchingWriteContext(session, domain);
        if (session == null) {
            return ActionResult.fail(ErrorCode.SESSION_ERROR, "No active Capella session");
        }

        if (domain == null) {
            return ActionResult.fail(
                    ErrorCode.SESSION_ERROR, "No transactional editing domain available");
        }

        // If already in write context, execute directly
        if (nested) {
            // A thrown failure must reach the owning command before conversion to a result.
            ActionResult result = action.execute();
            return result != null
                    ? result
                    : ActionResult.fail(ErrorCode.SESSION_ERROR, "Action returned null");
        }

        AtomicReference<RuntimeException> actionFailure = new AtomicReference<>();
        try {
            // Share the managed command's session scope and confirmed rollback lifecycle.
            return runWrite(
                    session,
                    label,
                    () -> {
                        try {
                            ActionResult result = action.execute();
                            return result != null
                                    ? result
                                    : ActionResult.fail(
                                            ErrorCode.SESSION_ERROR, "Action returned null");
                        } catch (RuntimeException ex) {
                            actionFailure.set(ex);
                            throw ex;
                        }
                    });
        } catch (SaveFailedAfterCommitException ex) {
            throw ex;
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Transaction execution failed", ex);
            RuntimeException actionException = actionFailure.get();
            if (actionException == ex
                    && !(actionException instanceof WriteScopeMismatchException)
                    && !(actionException instanceof ScriptCancelledException)) {
                return ActionResult.fail(
                        ErrorCode.CONSTRAINT_VIOLATION,
                        "Action failed: " + actionException.getMessage(),
                        "Check EMF constraints and model state");
            }
            return ActionResult.fail(
                    ErrorCode.SESSION_ERROR,
                    "Transaction failed: " + ex.getMessage(),
                    "The model may be in an inconsistent state. Consider reopening the session.");
        }
    }

    /** Clear ThreadLocal state to avoid leaks when the plug-in stops. */
    public static void cleanup() {
        IN_WRITE.remove();
        COMMITTING.remove();
        DEFER_SAVE_DEPTH.remove();
        SAVE_DIRTY.remove();
        EXECUTION.remove();
    }
}
