package org.polarsys.capella.ai.agent;

import static org.junit.Assert.*;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.transaction.RecordingCommand;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.sirius.diagram.DiagramFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.polarsys.capella.core.data.ctx.CtxFactory;
import org.polarsys.capella.core.data.ctx.SystemFunction;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * A full validation pass takes about 48 s on a 312,000-object model. The pass after one edit is the
 * baseline of the next, unless a semantic resource changed in between.
 */
public class ModelVerificationReuseTest {
    private Resource semantic, aird;
    private TransactionalEditingDomain domain;
    private Session session;
    private SystemFunction function;
    private final AtomicInteger passes = new AtomicInteger();
    private Function<Session, Map<String, Object>> validation;

    @Before
    public void setUp() {
        var resources = new ResourceSetImpl();
        semantic = new ResourceImpl(URI.createURI("memory:/reuse.capella"));
        aird = new ResourceImpl(URI.createURI("memory:/reuse.aird"));
        resources.getResources().addAll(List.of(semantic, aird));
        function = CtxFactory.eINSTANCE.createSystemFunction();
        function.setName("Monitor insulation");
        semantic.getContents().add(function);
        aird.getContents().add(DiagramFactory.eINSTANCE.createDSemanticDiagram());
        domain = TransactionalEditingDomain.Factory.INSTANCE.createEditingDomain(resources);
        session = session(domain);
        validation =
                ignored -> {
                    long pass = passes.incrementAndGet();
                    return Map.of(
                            "errors",
                            0L,
                            "warnings",
                            pass,
                            "issueCount",
                            pass,
                            "issues",
                            List.of(),
                            "truncated",
                            false);
                };
    }

    @After
    public void tearDown() {
        ModelVerification.forgetLastPass();
        domain.dispose();
    }

    @Test
    public void anUnchangedModelReusesTheLastPass() {
        var first = ModelVerification.reusingSnapshot(session, validation);
        var second = ModelVerification.reusingSnapshot(session, validation);
        assertEquals(1, passes.get());
        assertEquals(first, second);
    }

    @Test
    public void aCommittedSemanticChangeRunsANewPass() {
        ModelVerification.reusingSnapshot(session, validation);
        write(() -> function.setName("Monitor insulation resistance"));
        var after = ModelVerification.reusingSnapshot(session, validation);
        assertEquals(2, passes.get());
        assertEquals("The new pass is the one returned", 2, after.warnings());
        ModelVerification.reusingSnapshot(session, validation);
        assertEquals("…and it is reusable in turn", 2, passes.get());
    }

    @Test
    public void undoIsAChangeToo() {
        write(() -> function.setName("Renamed"));
        ModelVerification.reusingSnapshot(session, validation);
        domain.getCommandStack().undo();
        ModelVerification.reusingSnapshot(session, validation);
        assertEquals(2, passes.get());
    }

    @Test
    public void aDiagramOnlyChangeKeepsThePass() {
        ModelVerification.reusingSnapshot(session, validation);
        write(() -> aird.getContents().add(DiagramFactory.eINSTANCE.createDSemanticDiagram()));
        ModelVerification.reusingSnapshot(session, validation);
        assertEquals("Validation reads only semantic resources", 1, passes.get());
    }

    @Test
    public void aNewResourceInTheSetRunsANewPass() {
        ModelVerification.reusingSnapshot(session, validation);
        write(
                () ->
                        domain.getResourceSet()
                                .getResources()
                                .add(new ResourceImpl(URI.createURI("memory:/added.capella"))));
        ModelVerification.reusingSnapshot(session, validation);
        assertEquals(2, passes.get());
    }

    @Test
    public void anotherSessionOrAnUnavailablePassIsNeverReused() {
        ModelVerification.reusingSnapshot(session, validation);
        ModelVerification.reusingSnapshot(session(domain), validation);
        assertEquals(2, passes.get());

        AtomicInteger failures = new AtomicInteger();
        Function<Session, Map<String, Object>> failing =
                ignored -> {
                    failures.incrementAndGet();
                    throw new IllegalStateException("validator unavailable");
                };
        assertFalse(ModelVerification.reusingSnapshot(session, failing).available());
        assertFalse(ModelVerification.reusingSnapshot(session, failing).available());
        assertEquals(2, failures.get());
    }

    private void write(Runnable change) {
        domain.getCommandStack()
                .execute(
                        new RecordingCommand(domain) {
                            @Override
                            protected void doExecute() {
                                change.run();
                            }
                        });
    }

    private Session session(TransactionalEditingDomain editingDomain) {
        return (Session)
                Proxy.newProxyInstance(
                        Session.class.getClassLoader(),
                        new Class<?>[] {Session.class},
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "getTransactionalEditingDomain" -> editingDomain;
                                    case "getSessionResource" -> aird;
                                    case "getSemanticResources" -> List.of(semantic);
                                    case "getAllSessionResources" -> Set.of(aird);
                                    case "isOpen" -> true;
                                    case "equals" -> proxy == args[0];
                                    case "hashCode" -> System.identityHashCode(proxy);
                                    default -> null;
                                });
    }
}
