package com.sidkolapalli.capella.ai.session;

import static org.junit.Assert.*;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.emf.common.command.Command;
import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.common.notify.impl.AdapterImpl;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.eclipse.emf.transaction.ResourceSetChangeEvent;
import org.eclipse.emf.transaction.ResourceSetListenerImpl;
import org.eclipse.emf.transaction.RollbackException;
import org.eclipse.emf.transaction.Transaction;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.emf.transaction.util.TransactionUtil;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.diagram.DDiagram;
import org.eclipse.sirius.diagram.DNode;
import org.eclipse.sirius.diagram.DiagramFactory;
import org.eclipse.swt.widgets.Display;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import com.sidkolapalli.capella.ai.scripting.ExecutionResult;
import com.sidkolapalli.capella.ai.scripting.GroovyExecutor;
import org.polarsys.capella.core.data.capellamodeller.CapellamodellerFactory;
import org.polarsys.capella.core.data.capellamodeller.Project;
import org.polarsys.capella.core.data.ctx.CtxFactory;
import org.polarsys.capella.core.data.ctx.SystemComponent;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Real native EMF commands: rollback flags must describe the surviving semantic/view state. */
public class SessionSafeguardsRollbackTest {
    private Resource semantic;
    private Resource views;
    private Project project;
    private SystemComponent system;
    private DDiagram diagram;
    private DNode node;
    private TransactionalEditingDomain domain;
    private Session session;
    private final AtomicInteger saves = new AtomicInteger();
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final AtomicBoolean saveFails = new AtomicBoolean();

    @Before
    public void setUp() throws Exception {
        assertSame(Display.getDefault(), Display.getCurrent());
        var set = new ResourceSetImpl();
        set.getResourceFactoryRegistry()
                .getExtensionToFactoryMap()
                .put("xmi", new XMIResourceFactoryImpl());
        semantic = set.createResource(URI.createURI("memory:/rollback-semantic.xmi"));
        views = set.createResource(URI.createURI("memory:/rollback-views.xmi"));
        project = CapellamodellerFactory.eINSTANCE.createProject();
        project.setId(UUID.randomUUID().toString());
        project.setName("Original project");
        var engineering = CapellamodellerFactory.eINSTANCE.createSystemEngineering();
        engineering.setId(UUID.randomUUID().toString());
        project.getOwnedModelRoots().add(engineering);
        var layer = CtxFactory.eINSTANCE.createSystemAnalysis();
        layer.setId(UUID.randomUUID().toString());
        engineering.getOwnedArchitectures().add(layer);
        var pkg = CtxFactory.eINSTANCE.createSystemComponentPkg();
        pkg.setId(UUID.randomUUID().toString());
        layer.setOwnedSystemComponentPkg(pkg);
        system = CtxFactory.eINSTANCE.createSystemComponent();
        system.setId(UUID.randomUUID().toString());
        system.setName("Original system");
        pkg.getOwnedSystemComponents().add(system);
        semantic.getContents().add(project);
        diagram = DiagramFactory.eINSTANCE.createDSemanticDiagram();
        node = DiagramFactory.eINSTANCE.createDNode();
        node.setName("Original node");
        node.setTarget(system);
        diagram.getOwnedDiagramElements().add(node);
        views.getContents().add(diagram);
        semantic.setTrackingModification(true);
        views.setTrackingModification(true);
        domain = TransactionalEditingDomain.Factory.INSTANCE.createEditingDomain(set);
        session =
                (Session)
                        Proxy.newProxyInstance(
                                Session.class.getClassLoader(),
                                new Class<?>[] {Session.class},
                                (proxy, method, args) ->
                                        switch (method.getName()) {
                                            case "getTransactionalEditingDomain" -> domain;
                                            case "getSemanticResources" -> List.of(semantic);
                                            case "getSessionResource" -> views;
                                            case "isOpen" -> open.get();
                                            case "save" -> {
                                                saves.incrementAndGet();
                                                if (saveFails.get())
                                                    throw new IllegalStateException(
                                                            "Synthetic disk failure");
                                                semantic.setModified(false);
                                                views.setModified(false);
                                                yield null;
                                            }
                                            case "hashCode" -> System.identityHashCode(proxy);
                                            case "equals" -> proxy == args[0];
                                            case "toString" -> "Native rollback flag fixture";
                                            default -> null;
                                        });
        semantic.setModified(false);
        views.setModified(false);
    }

    @After
    public void tearDown() {
        if (domain != null) domain.dispose();
        SessionSafeguards.cleanup();
    }

    @Test
    public void failedNativeCommandRestoresCleanSemanticAndDiagramFlagsAndBytes() throws Exception {
        // Preserve a genuine prior Undo command; failed writes must not add/erase that history.
        SessionSafeguards.runWrite(
                session,
                "Prior accepted change",
                () -> {
                    system.setName("Existing accepted system");
                    return null;
                });
        semantic.setModified(false);
        views.setModified(false);
        byte[] model = bytes(semantic), view = bytes(views);
        Command undo = domain.getCommandStack().getUndoCommand();
        AtomicInteger cleanNotifications = new AtomicInteger();
        semantic.eAdapters()
                .add(
                        new AdapterImpl() {
                            @Override
                            public void notifyChanged(Notification event) {
                                if (event.getNotifier() == semantic
                                        && event.getFeatureID(Resource.class)
                                                == Resource.RESOURCE__IS_MODIFIED
                                        && Boolean.FALSE.equals(event.getNewValue()))
                                    cleanNotifications.incrementAndGet();
                            }
                        });
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SessionSafeguards.runWrite(
                                session,
                                "Failed reviewed phase",
                                () -> {
                                    system.setName("Partial system");
                                    node.setName("Partial diagram");
                                    var actor = CtxFactory.eINSTANCE.createSystemComponent();
                                    actor.setActor(true);
                                    actor.setId(UUID.randomUUID().toString());
                                    ((org.polarsys.capella.core.data.ctx.SystemComponentPkg)
                                                    system.eContainer())
                                            .getOwnedSystemComponents()
                                            .add(actor);
                                    requestSave();
                                    throw new IllegalArgumentException(
                                            "Assertion failed after native edits");
                                }));
        assertFalse(semantic.isModified());
        assertFalse(views.isModified());
        assertTrue(
                "Normal flag notifications must reach editor/session listeners",
                cleanNotifications.get() > 0);
        assertArrayEquals(model, bytes(semantic));
        assertArrayEquals(view, bytes(views));
        assertSame(undo, domain.getCommandStack().getUndoCommand());
        assertFalse(domain.getCommandStack().canRedo());
        assertEquals(0, saves.get());
        assertEquals("Existing accepted system", system.getName());
        assertEquals(
                1,
                ((org.polarsys.capella.core.data.ctx.SystemComponentPkg) system.eContainer())
                        .getOwnedSystemComponents()
                        .size());
    }

    @Test
    public void rollbackPreservesResourcesThatWereAlreadyDirty() throws Exception {
        byte[] model = bytes(semantic), view = bytes(views);
        semantic.setModified(true);
        views.setModified(false);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SessionSafeguards.runWrite(
                                session,
                                "Failure on unsaved model",
                                () -> {
                                    system.setName("Partial");
                                    node.setName("Partial");
                                    throw new IllegalArgumentException("Fail");
                                }));
        assertTrue(semantic.isModified());
        assertFalse(views.isModified());
        assertArrayEquals(model, bytes(semantic));
        assertArrayEquals(view, bytes(views));
        assertEquals(0, saves.get());
        assertFalse(domain.getCommandStack().canUndo());
    }

    @Test
    public void nativePrecommitRejectionRestoresFlagsOnlyAfterFullRollback() throws Exception {
        byte[] model = bytes(semantic);
        var reject =
                new ResourceSetListenerImpl() {
                    @Override
                    public boolean isPrecommitOnly() {
                        return true;
                    }

                    @Override
                    public Command transactionAboutToCommit(ResourceSetChangeEvent event)
                            throws RollbackException {
                        throw new RollbackException(
                                new Status(
                                        IStatus.ERROR,
                                        "com.sidkolapalli.capella.ai.tests",
                                        "Synthetic validation rejection"));
                    }
                };
        domain.addResourceSetListener(reject);
        try {
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            SessionSafeguards.runWrite(
                                    session,
                                    "Rejected by native validator",
                                    () -> {
                                        system.setName("Rejected");
                                        requestSave();
                                        return null;
                                    }));
        } finally {
            domain.removeResourceSetListener(reject);
        }
        assertFalse(semantic.isModified());
        assertArrayEquals(model, bytes(semantic));
        assertEquals(0, saves.get());
        assertFalse(domain.getCommandStack().canUndo());
    }

    @Test
    public void cooperativeCancellationRestoresTheConfirmedRolledBackScope() throws Exception {
        byte[] model = bytes(semantic);
        var control = new SessionSafeguards.ExecutionControl(10_000);
        assertThrows(
                SessionSafeguards.ScriptCancelledException.class,
                () ->
                        SessionSafeguards.withExecutionControl(
                                control,
                                () ->
                                        SessionSafeguards.runWrite(
                                                session,
                                                "Cancelled reviewed phase",
                                                () -> {
                                                    system.setName("Partial");
                                                    control.cancel();
                                                    return null;
                                                })));
        assertFalse(semantic.isModified());
        assertArrayEquals(model, bytes(semantic));
        assertFalse(control.wasCommitted());
        assertEquals(0, saves.get());
        assertFalse(domain.getCommandStack().canUndo());
    }

    @Test
    public void committedChangesAndPostcommitSaveFailureStayDirtyAndUndoable() {
        saveFails.set(true);
        var control = new SessionSafeguards.ExecutionControl(10_000);
        assertThrows(
                SessionSafeguards.SaveFailedAfterCommitException.class,
                () ->
                        SessionSafeguards.withExecutionControl(
                                control,
                                () ->
                                        SessionSafeguards.runWrite(
                                                session,
                                                "Applied but not saved",
                                                () -> {
                                                    system.setName("Applied");
                                                    node.setName("Applied");
                                                    requestSave();
                                                    return null;
                                                })));
        assertTrue(semantic.isModified());
        assertTrue(views.isModified());
        assertEquals("Applied", system.getName());
        assertEquals("Applied", node.getName());
        assertTrue(control.wasCommitted());
        assertFalse(control.wasSaved());
        assertNotNull(control.saveFailure());
        assertEquals(1, saves.get());
        assertTrue(domain.getCommandStack().canUndo());
    }

    @Test
    public void unconfirmedClosedSessionDoesNotClearModifiedFlags() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SessionSafeguards.runWrite(
                                session,
                                "Session changed while failing",
                                () -> {
                                    system.setName("Partial");
                                    open.set(false);
                                    throw new IllegalArgumentException(
                                            "Fail after session changed");
                                }));
        assertEquals("Original system", system.getName());
        assertTrue(
                "A closed captured session must not have its flags silently cleared",
                semantic.isModified());
        assertEquals(0, saves.get());
        assertFalse(domain.getCommandStack().canUndo());
    }

    @Test
    public void resourceIdentityChangePreventsAnyDirtyFlagRestoration() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SessionSafeguards.runWrite(
                                session,
                                "Untracked resource lifecycle change",
                                () -> {
                                    system.setName("Partial");
                                    views.setTrackingModification(false);
                                    throw new IllegalArgumentException("Fail");
                                }));
        assertFalse(views.isTrackingModification());
        assertTrue(
                "Changed resource tracking means complete identity/state could not be confirmed",
                semantic.isModified());
        assertEquals("Original system", system.getName());
        assertEquals(0, saves.get());
    }

    @Test
    public void actualGroovyAssertionFailureRestoresNativeBytesAndDirtyFlags() throws Exception {
        byte[] model = bytes(semantic), view = bytes(views);
        Command undo = domain.getCommandStack().getUndoCommand();
        var task =
                new FutureTask<>(
                        () ->
                                GroovyExecutor.execute(
                                        session,
                                        "api.set(system, 'name', 'Partial'); node.name = 'Partial';"
                                            + " def actor ="
                                            + " CtxFactory.eINSTANCE.createSystemComponent();"
                                            + " actor.actor = true; actor.id ="
                                            + " 'rollback-native-actor';"
                                            + " system.eContainer().ownedSystemComponents.add(actor);"
                                            + " assert false : 'deliberate failed phase assertion'",
                                        30_000,
                                        Map.of("system", system, "node", node)));
        Thread caller = new Thread(task, "NativeRollback-GroovyCaller");
        caller.setDaemon(true);
        caller.start();
        Display display = Display.getDefault();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (!task.isDone() && System.nanoTime() < deadline) {
            if (!display.readAndDispatch()) Thread.sleep(2);
        }
        ExecutionResult result = task.get(1, TimeUnit.SECONDS);
        assertFalse(result.success());
        assertEquals(ExecutionResult.ErrorCode.RUNTIME_ERROR, result.code());
        assertTrue(
                result.message(), result.message().contains("deliberate failed phase assertion"));
        assertFalse(semantic.isModified());
        assertFalse(views.isModified());
        assertArrayEquals(model, bytes(semantic));
        assertArrayEquals(view, bytes(views));
        assertSame(undo, domain.getCommandStack().getUndoCommand());
        assertFalse(domain.getCommandStack().canRedo());
        assertEquals(0, saves.get());
    }

    @Test
    public void exceptionDuringNativeRollbackApplicationCannotClearTheDirtyFlag() {
        AtomicBoolean rollbackFault = new AtomicBoolean();
        var fault =
                new AdapterImpl() {
                    @Override
                    public void notifyChanged(Notification event) {
                        if ("Original system".equals(event.getNewValue())
                                && "Partial".equals(event.getOldValue())) {
                            rollbackFault.set(true);
                            throw new IllegalStateException(
                                    "Synthetic rollback application failure");
                        }
                    }
                };
        system.eAdapters().add(fault);
        try {
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            SessionSafeguards.runWrite(
                                    session,
                                    "Broken rollback",
                                    () -> {
                                        system.setName("Partial");
                                        throw new IllegalArgumentException(
                                                "Original script failure");
                                    }));
            assertTrue(
                    "The native inverse application must actually reach the fault",
                    rollbackFault.get());
            assertTrue(
                    "A closed transaction alone cannot certify a completed rollback",
                    semantic.isModified());
            assertEquals(0, saves.get());
        } finally {
            system.eAdapters().remove(fault);
        }
    }

    @Test
    public void disabledNativeUndoRecordingCannotCertifyAtomicRollback() {
        var options =
                TransactionUtil.getAdapter(domain, TransactionalEditingDomain.DefaultOptions.class);
        assertNotNull(options);
        options.setDefaultTransactionOptions(Map.of(Transaction.OPTION_NO_UNDO, Boolean.TRUE));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SessionSafeguards.runWrite(
                                session,
                                "Unrecorded failed write",
                                () -> {
                                    system.setName("Unrecorded partial change");
                                    throw new IllegalArgumentException(
                                            "Fail without undo recording");
                                }));
        assertEquals("Unrecorded partial change", system.getName());
        assertTrue(
                "An error status with no undo recording cannot justify clearing dirty state",
                semantic.isModified());
        assertEquals(0, saves.get());
    }

    private void requestSave() {
        try {
            SessionSafeguards.requestSave(session);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static byte[] bytes(Resource resource) throws Exception {
        boolean dirty = resource.isModified();
        var output = new ByteArrayOutputStream();
        try {
            resource.save(output, Map.of());
            return output.toByteArray();
        } finally {
            resource.setModified(dirty);
        }
    }
}
