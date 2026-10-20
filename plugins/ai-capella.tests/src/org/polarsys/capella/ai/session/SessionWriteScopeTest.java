package org.polarsys.capella.ai.session;

import static org.junit.Assert.*;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceImpl;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.swt.widgets.Display;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.polarsys.capella.ai.model.ActionResult;
import org.polarsys.capella.core.data.capellamodeller.CapellamodellerFactory;
import org.polarsys.capella.core.data.capellamodeller.Project;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Native transactions must never reuse a write context from a different session/domain. */
public class SessionWriteScopeTest {
    private Fixture first;
    private Fixture second;

    @Before
    public void setUp() {
        assertSame(Display.getDefault(), Display.getCurrent());
        first = new Fixture("first");
        second = new Fixture("second");
    }

    @After
    public void tearDown() {
        first.domain.dispose();
        second.domain.dispose();
        SessionSafeguards.cleanup();
    }

    @Test
    public void differentSessionWriteRejectsBeforeItsCallbackAndRollsBackTheOuterWrite()
            throws Exception {
        byte[] a = bytes(first.resource), b = bytes(second.resource);
        AtomicBoolean invoked = new AtomicBoolean();
        assertThrows(
                IllegalStateException.class,
                () ->
                        SessionSafeguards.runWrite(
                                first.session,
                                "Outer",
                                () -> {
                                    first.project.setName("Partial");
                                    requestSave(first.session);
                                    return SessionSafeguards.runWrite(
                                            second.session,
                                            "Foreign",
                                            () -> {
                                                invoked.set(true);
                                                second.project.setName("Foreign mutation");
                                                return null;
                                            });
                                }));
        assertFalse(invoked.get());
        assertUnchanged(a, b);
    }

    @Test
    public void distinctSessionObjectsCannotShareTheContextEvenWhenTheirDomainIsTheSame()
            throws Exception {
        byte[] a = bytes(first.resource), b = bytes(second.resource);
        Session alias = first.newSession();
        AtomicBoolean invoked = new AtomicBoolean();
        assertThrows(
                IllegalStateException.class,
                () ->
                        SessionSafeguards.runWrite(
                                first.session,
                                "Outer",
                                () -> {
                                    first.project.setName("Partial");
                                    return SessionSafeguards.runWrite(
                                            alias,
                                            "Other session",
                                            () -> {
                                                invoked.set(true);
                                                return null;
                                            });
                                }));
        assertFalse(invoked.get());
        assertUnchanged(a, b);
    }

    @Test
    public void replacingTheCapturedSessionsDomainRejectsBeforeTheCallback() throws Exception {
        byte[] a = bytes(first.resource), b = bytes(second.resource);
        AtomicBoolean invoked = new AtomicBoolean();
        assertThrows(
                IllegalStateException.class,
                () ->
                        SessionSafeguards.runWrite(
                                first.session,
                                "Outer",
                                () -> {
                                    first.project.setName("Partial");
                                    first.currentDomain.set(second.domain);
                                    try {
                                        return SessionSafeguards.runWrite(
                                                first.session,
                                                "Changed domain",
                                                () -> {
                                                    invoked.set(true);
                                                    return null;
                                                });
                                    } finally {
                                        first.currentDomain.set(first.domain);
                                    }
                                }));
        assertFalse(invoked.get());
        assertUnchanged(a, b);
    }

    @Test
    public void nullSessionCannotEscapeAnExistingWriteScope() throws Exception {
        byte[] a = bytes(first.resource), b = bytes(second.resource);
        AtomicBoolean invoked = new AtomicBoolean();
        assertThrows(
                IllegalStateException.class,
                () ->
                        SessionSafeguards.runWrite(
                                first.session,
                                "Outer",
                                () -> {
                                    first.project.setName("Partial");
                                    return SessionSafeguards.runWrite(
                                            null,
                                            "Unscoped",
                                            () -> {
                                                invoked.set(true);
                                                return null;
                                            });
                                }));
        assertFalse(invoked.get());
        assertUnchanged(a, b);
    }

    @Test
    public void actionResultNestingCannotConvertAForeignScopeFailureIntoACommittedPartialEdit()
            throws Exception {
        byte[] a = bytes(first.resource), b = bytes(second.resource);
        AtomicBoolean invoked = new AtomicBoolean();
        ActionResult result =
                SessionSafeguards.runWriteWithResult(
                        first.session,
                        "Outer action",
                        () -> {
                            first.project.setName("Partial");
                            requestSave(first.session);
                            return SessionSafeguards.runWriteWithResult(
                                    first.session,
                                    "Same scope action",
                                    () ->
                                            SessionSafeguards.runWriteWithResult(
                                                    second.session,
                                                    "Foreign action",
                                                    () -> {
                                                        invoked.set(true);
                                                        second.project.setName("Foreign mutation");
                                                        return ActionResult.ok();
                                                    }));
                        });
        assertFalse(result.success());
        assertEquals(ActionResult.ErrorCode.SESSION_ERROR, result.code());
        assertFalse(invoked.get());
        assertUnchanged(a, b);
    }

    @Test
    public void nestedActionExceptionConvertsToAResultOnlyAfterNativeRollback() throws Exception {
        byte[] a = bytes(first.resource), b = bytes(second.resource);
        ActionResult result =
                SessionSafeguards.runWriteWithResult(
                        first.session,
                        "Outer action",
                        () -> {
                            first.project.setName("Partial");
                            requestSave(first.session);
                            return SessionSafeguards.runWriteWithResult(
                                    first.session,
                                    "Nested failing action",
                                    () -> {
                                        first.project.setName("More partial edits");
                                        throw new IllegalArgumentException(
                                                "Action failed after native edits");
                                    });
                        });
        assertFalse(result.success());
        assertEquals(ActionResult.ErrorCode.CONSTRAINT_VIOLATION, result.code());
        assertTrue(result.message().contains("Action failed after native edits"));
        assertUnchanged(a, b);
    }

    @Test
    public void foreignSaveIsRejectedBeforeAnySaveOrDeferredSaveFlagChange() throws Exception {
        byte[] a = bytes(first.resource), b = bytes(second.resource);
        assertThrows(
                IllegalStateException.class,
                () ->
                        SessionSafeguards.runWrite(
                                first.session,
                                "Outer",
                                () -> {
                                    first.project.setName("Partial");
                                    requestSave(second.session);
                                    return null;
                                }));
        assertUnchanged(a, b);
    }

    @Test
    public void workerDispatchedDuringAnOuterSwtCommandCannotEnterAnotherSessionsWrite()
            throws Exception {
        byte[] a = bytes(first.resource), b = bytes(second.resource);
        AtomicBoolean invoked = new AtomicBoolean();
        assertThrows(
                IllegalStateException.class,
                () ->
                        SessionSafeguards.runWrite(
                                first.session,
                                "Outer",
                                () -> {
                                    first.project.setName("Partial");
                                    var task =
                                            new FutureTask<RuntimeException>(
                                                    () -> {
                                                        try {
                                                            SessionSafeguards.runWrite(
                                                                    second.session,
                                                                    "Dispatched foreign write",
                                                                    () -> {
                                                                        invoked.set(true);
                                                                        return null;
                                                                    });
                                                            return null;
                                                        } catch (RuntimeException failure) {
                                                            return failure;
                                                        }
                                                    });
                                    Thread worker = new Thread(task, "ForeignWriteScopeTest");
                                    worker.setDaemon(true);
                                    worker.start();
                                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                                    try {
                                        while (!task.isDone() && System.nanoTime() < deadline) {
                                            if (!Display.getDefault().readAndDispatch())
                                                Thread.sleep(2);
                                        }
                                        RuntimeException failure = task.get(1, TimeUnit.SECONDS);
                                        assertNotNull(
                                                "The dispatched callback must be rejected",
                                                failure);
                                        throw failure;
                                    } catch (RuntimeException failure) {
                                        throw failure;
                                    } catch (Exception failure) {
                                        throw new IllegalStateException(failure);
                                    }
                                }));
        assertFalse(invoked.get());
        assertUnchanged(a, b);
    }

    @Test
    public void exactSessionNestingKeepsOneUndoCommandAndSavesOnlyAfterItCommits() {
        SessionSafeguards.runWrite(
                first.session,
                "Outer accepted write",
                () -> {
                    first.project.setName("First accepted name");
                    assertTrue(
                            SessionSafeguards.runWriteWithResult(
                                            first.session,
                                            "Nested accepted write",
                                            () -> {
                                                assertTrue(SessionSafeguards.isInWriteContext());
                                                first.project.setName("Final accepted name");
                                                requestSave(first.session);
                                                return ActionResult.ok();
                                            })
                                    .success());
                    assertTrue(SessionSafeguards.isInWriteContext());
                    return null;
                });
        assertFalse(SessionSafeguards.isInWriteContext());
        assertEquals(1, first.saves.get());
        assertFalse(first.savedInsideWrite.get());
        assertEquals("Final accepted name", first.project.getName());
        assertTrue(first.domain.getCommandStack().canUndo());
        first.domain.getCommandStack().undo();
        assertEquals("first", first.project.getName());
        assertFalse(first.domain.getCommandStack().canUndo());
        first.domain.getCommandStack().redo();
        assertEquals("Final accepted name", first.project.getName());
        assertEquals(0, second.saves.get());
        assertFalse(second.domain.getCommandStack().canUndo());
    }

    private void assertUnchanged(byte[] a, byte[] b) throws Exception {
        assertArrayEquals(a, bytes(first.resource));
        assertArrayEquals(b, bytes(second.resource));
        assertFalse(first.resource.isModified());
        assertFalse(second.resource.isModified());
        assertFalse(first.domain.getCommandStack().canUndo());
        assertFalse(second.domain.getCommandStack().canUndo());
        assertFalse(first.domain.getCommandStack().canRedo());
        assertFalse(second.domain.getCommandStack().canRedo());
        assertEquals(0, first.saves.get());
        assertEquals(0, second.saves.get());
        assertFalse(SessionSafeguards.isInWriteContext());
    }

    private static void requestSave(Session session) {
        try {
            SessionSafeguards.requestSave(session);
        } catch (RuntimeException failure) {
            throw failure;
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

    private static final class Fixture {
        final Resource resource;
        final Project project;
        final TransactionalEditingDomain domain;
        final AtomicReference<TransactionalEditingDomain> currentDomain;
        final AtomicInteger saves = new AtomicInteger();
        final AtomicBoolean savedInsideWrite = new AtomicBoolean();
        final Session session;

        Fixture(String name) {
            var resources = new ResourceSetImpl();
            resource = new XMIResourceImpl(URI.createURI("memory:/" + name + ".capella"));
            resources.getResources().add(resource);
            project = CapellamodellerFactory.eINSTANCE.createProject();
            project.setId(name);
            project.setName(name);
            resource.getContents().add(project);
            resource.setTrackingModification(true);
            domain = TransactionalEditingDomain.Factory.INSTANCE.createEditingDomain(resources);
            currentDomain = new AtomicReference<>(domain);
            session = newSession();
            resource.setModified(false);
        }

        Session newSession() {
            return (Session)
                    Proxy.newProxyInstance(
                            Session.class.getClassLoader(),
                            new Class<?>[] {Session.class},
                            (proxy, method, args) ->
                                    switch (method.getName()) {
                                        case "getTransactionalEditingDomain" -> currentDomain.get();
                                        case "getSessionResource" -> resource;
                                        case "getSemanticResources" -> List.of(resource);
                                        case "isOpen" -> true;
                                        case "save" -> {
                                            saves.incrementAndGet();
                                            savedInsideWrite.set(
                                                    SessionSafeguards.isInWriteContext());
                                            resource.setModified(false);
                                            yield null;
                                        }
                                        case "hashCode" -> System.identityHashCode(proxy);
                                        case "equals" -> proxy == args[0];
                                        default -> null;
                                    });
        }
    }
}
