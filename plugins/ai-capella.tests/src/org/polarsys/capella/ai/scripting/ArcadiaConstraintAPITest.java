package org.polarsys.capella.ai.scripting;

import static org.junit.Assert.*;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.sirius.business.api.session.Session;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.polarsys.capella.ai.model.ModelSummary;
import org.polarsys.capella.ai.session.SessionSafeguards;
import org.polarsys.capella.core.data.capellacore.CapellaElement;
import org.polarsys.capella.core.data.capellacore.CapellacoreFactory;
import org.polarsys.capella.core.data.capellacore.Constraint;
import org.polarsys.capella.core.data.capellamodeller.CapellamodellerFactory;
import org.polarsys.capella.core.data.capellamodeller.Project;
import org.polarsys.capella.core.data.ctx.CtxFactory;
import org.polarsys.capella.core.data.epbs.EpbsFactory;
import org.polarsys.capella.core.data.information.datavalue.DatavalueFactory;
import org.polarsys.capella.core.data.information.datavalue.OpaqueExpression;
import org.polarsys.capella.core.data.la.LaFactory;
import org.polarsys.capella.core.data.oa.OaFactory;
import org.polarsys.capella.core.data.pa.PaFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Native Capella subjects, transactions, intrinsic identities and XMI; only Session is a proxy. */
public class ArcadiaConstraintAPITest {
    private ResourceSetImpl resources;
    private Resource resource;
    private TransactionalEditingDomain domain;
    private Session session;
    private ArcadiaConstraintAPI constraints;
    private boolean open;
    private int saves;
    private byte[] saved;
    private final List<Resource> semantic = new ArrayList<>();
    private final List<CapellaElement> owners = new ArrayList<>();
    private CapellaElement authorize, command;

    @Before
    public void setUp() {
        resources = resources();
        resource = resources.createResource(URI.createURI("memory:/constraints.xmi"));
        semantic.add(resource);
        Project project = CapellamodellerFactory.eINSTANCE.createProject();
        project.setName("Constraint fixture");
        var engineering = CapellamodellerFactory.eINSTANCE.createSystemEngineering();
        project.getOwnedModelRoots().add(engineering);
        var oa = OaFactory.eINSTANCE.createOperationalAnalysis();
        engineering.getOwnedArchitectures().add(oa);
        var oaPackage = OaFactory.eINSTANCE.createOperationalActivityPkg();
        oa.setOwnedFunctionPkg(oaPackage);
        var activity = OaFactory.eINSTANCE.createOperationalActivity();
        oaPackage.getOwnedOperationalActivities().add(activity);
        owners.add(activity);
        var sa = CtxFactory.eINSTANCE.createSystemAnalysis();
        engineering.getOwnedArchitectures().add(sa);
        var saPackage = CtxFactory.eINSTANCE.createSystemFunctionPkg();
        sa.setOwnedFunctionPkg(saPackage);
        var function = CtxFactory.eINSTANCE.createSystemFunction();
        saPackage.getOwnedSystemFunctions().add(function);
        owners.add(function);
        var first = CtxFactory.eINSTANCE.createSystemFunction();
        first.setName("Authorize drive");
        var second = CtxFactory.eINSTANCE.createSystemFunction();
        second.setName("Command propulsion");
        function.getOwnedFunctions().addAll(List.of(first, second));
        authorize = first;
        command = second;
        var la = LaFactory.eINSTANCE.createLogicalArchitecture();
        engineering.getOwnedArchitectures().add(la);
        var laPackage = LaFactory.eINSTANCE.createLogicalFunctionPkg();
        la.setOwnedFunctionPkg(laPackage);
        var logical = LaFactory.eINSTANCE.createLogicalFunction();
        laPackage.getOwnedLogicalFunctions().add(logical);
        owners.add(logical);
        var pa = PaFactory.eINSTANCE.createPhysicalArchitecture();
        engineering.getOwnedArchitectures().add(pa);
        var paPackage = PaFactory.eINSTANCE.createPhysicalFunctionPkg();
        pa.setOwnedFunctionPkg(paPackage);
        var physical = PaFactory.eINSTANCE.createPhysicalFunction();
        paPackage.getOwnedPhysicalFunctions().add(physical);
        owners.add(physical);
        var epbs = EpbsFactory.eINSTANCE.createEPBSArchitecture();
        engineering.getOwnedArchitectures().add(epbs);
        epbs.setOwnedConfigurationItemPkg(EpbsFactory.eINSTANCE.createConfigurationItemPkg());
        var item = EpbsFactory.eINSTANCE.createConfigurationItem();
        epbs.getOwnedConfigurationItemPkg().getOwnedConfigurationItems().add(item);
        owners.add(item);
        identifyTree(project);
        resource.getContents().add(project);
        domain = TransactionalEditingDomain.Factory.INSTANCE.createEditingDomain(resources);
        open = true;
        session =
                (Session)
                        Proxy.newProxyInstance(
                                Session.class.getClassLoader(),
                                new Class<?>[] {Session.class},
                                (proxy, method, arguments) ->
                                        switch (method.getName()) {
                                            case "isOpen" -> open;
                                            case "getTransactionalEditingDomain" -> domain;
                                            case "getSemanticResources" -> List.copyOf(semantic);
                                            case "getSessionResource" -> resource;
                                            case "save" -> {
                                                saves++;
                                                saved = bytes(resource);
                                                yield null;
                                            }
                                            case "equals" -> proxy == arguments[0];
                                            case "hashCode" -> System.identityHashCode(proxy);
                                            default -> null;
                                        });
        constraints = new ArcadiaConstraintAPI(session);
        resource.setModified(false);
    }

    @After
    public void tearDown() {
        if (session != null) ModelSummary.invalidate(session);
        if (domain != null) domain.dispose();
    }

    @Test
    public void storesConcreteExpressionAndActualOrderedSubjectsWithNativeIdentities() {
        int before = count();
        Constraint result = create();
        assertSame(authorize, result.eContainer());
        assertTrue(authorize.getOwnedConstraints().contains(result));
        assertEquals("Authorization", result.getName());
        assertEquals(List.of(authorize, command), result.getConstrainedElements());
        assertTrue(result.getOwnedSpecification() instanceof OpaqueExpression);
        OpaqueExpression expression = (OpaqueExpression) result.getOwnedSpecification();
        assertSame(result, expression.eContainer());
        assertEquals("ownedSpecification", expression.eContainingFeature().getName());
        assertEquals(List.of("capella:linkedText"), expression.getLanguages());
        assertEquals(List.of(body()), expression.getBodies());
        assertSame(result, resource.getEObject(result.getId()));
        assertSame(expression, resource.getEObject(expression.getId()));
        UUID.fromString(result.getId());
        UUID.fromString(expression.getId());
        assertEquals(before + 2, count());
        assertEquals(1, saves);
    }

    @Test
    public void oneUndoRedoAndReloadPreserveExpressionSubjectsAndIds() throws Exception {
        int before = count();
        Constraint result = create();
        String id = result.getId(),
                expressionId = ((OpaqueExpression) result.getOwnedSpecification()).getId();
        var change = domain.getCommandStack().getUndoCommand();
        assertNotNull(change);
        domain.getCommandStack().undo();
        assertEquals(before, count());
        assertFalse(authorize.getOwnedConstraints().contains(result));
        assertNotSame(resource, result.eResource());
        domain.getCommandStack().redo();
        assertSame(change, domain.getCommandStack().getUndoCommand());
        assertEquals(before + 2, count());
        assertSame(result, resource.getEObject(id));
        assertSame(result.getOwnedSpecification(), resource.getEObject(expressionId));
        Resource copy = resources().createResource(resource.getURI());
        copy.load(new ByteArrayInputStream(saved), Map.of());
        assertTrue(copy.getErrors().toString(), copy.getErrors().isEmpty());
        Constraint restored = (Constraint) copy.getEObject(id);
        assertEquals(
                List.of(copy.getEObject(authorize.getId()), copy.getEObject(command.getId())),
                restored.getConstrainedElements());
        assertSame(restored, copy.getEObject(expressionId).eContainer());
        assertEquals(
                List.of(body()), ((OpaqueExpression) restored.getOwnedSpecification()).getBodies());
        assertEquals(1, saves);
    }

    @Test
    public void supportsAllFiveNativeOwnersAndSameProjectCrossLayerSubjects() {
        for (CapellaElement owner : owners) {
            Constraint result =
                    constraints.constraint(
                            owner,
                            "Cross-layer obligation",
                            body(),
                            List.of(authorize, owners.get(3), owners.get(4)));
            assertSame(owner, result.eContainer());
            assertEquals(
                    List.of(authorize, owners.get(3), owners.get(4)),
                    result.getConstrainedElements());
        }
        assertEquals(5, saves);
    }

    @Test
    public void exactRepeatHasNoSaveNoUndoNoResourceOrAdapterChange() throws Exception {
        Constraint result = create();
        byte[] before = bytes(resource);
        var undo = domain.getCommandStack().getUndoCommand();
        var redo = domain.getCommandStack().getRedoCommand();
        int priorSaves = saves, priorCount = count();
        boolean dirty = resource.isModified();
        List<Integer> adapters = objects().stream().map(item -> item.eAdapters().size()).toList();
        assertSame(result, create());
        assertEquals(priorCount, count());
        assertEquals(priorSaves, saves);
        assertEquals(dirty, resource.isModified());
        assertSame(undo, domain.getCommandStack().getUndoCommand());
        assertSame(redo, domain.getCommandStack().getRedoCommand());
        assertArrayEquals(before, bytes(resource));
        assertEquals(adapters, objects().stream().map(item -> item.eAdapters().size()).toList());
    }

    @Test
    public void nestedCreationJoinsOneOuterCommandAndRollbackRemovesAllNewNativeObjects()
            throws Exception {
        byte[] before = bytes(resource);
        int priorCount = count();
        var undo = domain.getCommandStack().getUndoCommand();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SessionSafeguards.runWrite(
                                session,
                                "Abort two obligations",
                                () -> {
                                    create();
                                    constraints.constraint(
                                            command,
                                            "Command scope",
                                            "Command is reviewed",
                                            List.of(command));
                                    throw new IllegalArgumentException("Fixture abort");
                                }));
        assertEquals(priorCount, count());
        assertArrayEquals(before, bytes(resource));
        assertEquals(0, saves);
        assertSame(undo, domain.getCommandStack().getUndoCommand());
        SessionSafeguards.runWrite(
                session,
                "Two scoped obligations",
                () -> {
                    create();
                    constraints.constraint(
                            command, "Command scope", "Command is reviewed", List.of(command));
                    return null;
                });
        assertEquals(priorCount + 4, count());
        assertEquals(1, saves);
        domain.getCommandStack().undo();
        assertEquals(priorCount, count());
        domain.getCommandStack().redo();
        assertEquals(priorCount + 4, count());
    }

    @Test
    public void differingTextAndSubjectOrderRejectWithoutChanges() throws Exception {
        create();
        unchanged(
                () ->
                        constraints.constraint(
                                authorize,
                                "Authorization",
                                body() + " changed",
                                List.of(authorize, command)));
        unchanged(
                () ->
                        constraints.constraint(
                                authorize, "Authorization", body(), List.of(command, authorize)));
        unchanged(
                () ->
                        constraints.constraint(
                                authorize, "Authorization", body(), List.of(authorize)));
    }

    @Test
    public void emptyOrOversizedOrInvalidTextAndSubjectListsRejectBeforeMutation()
            throws Exception {
        unchanged(() -> constraints.constraint(authorize, " ", body(), List.of(authorize)));
        unchanged(
                () ->
                        constraints.constraint(
                                authorize, "x".repeat(241), body(), List.of(authorize)));
        unchanged(() -> constraints.constraint(authorize, "X", " ", List.of(authorize)));
        unchanged(
                () ->
                        constraints.constraint(
                                authorize, "X", "x".repeat(12001), List.of(authorize)));
        unchanged(() -> constraints.constraint(authorize, "X", "a\0b", List.of(authorize)));
        unchanged(() -> constraints.constraint(authorize, "X", body(), null));
        unchanged(() -> constraints.constraint(authorize, "X", body(), List.of()));
        unchanged(
                () ->
                        constraints.constraint(
                                authorize, "X", body(), List.of(authorize, authorize)));
        unchanged(
                () ->
                        constraints.constraint(
                                authorize,
                                "X",
                                body(),
                                java.util.Collections.nCopies(33, authorize)));
        List<EObject> nullSubject = new ArrayList<>();
        nullSubject.add(null);
        unchanged(() -> constraints.constraint(authorize, "X", body(), nullSubject));
        unchanged(
                () ->
                        constraints.constraint(
                                authorize,
                                "X",
                                body(),
                                List.of(
                                        org.eclipse.emf.ecore.EcoreFactory.eINSTANCE
                                                .createEClass())));
    }

    @Test
    public void detachedProxyForeignAndNonsemanticEndpointsAreRejected() throws Exception {
        var detached = CtxFactory.eINSTANCE.createSystemFunction();
        detached.setId(UUID.randomUUID().toString());
        unchanged(() -> constraints.constraint(detached, "X", body(), List.of(authorize)));
        unchanged(() -> constraints.constraint(authorize, "X", body(), List.of(detached)));
        var proxy = CtxFactory.eINSTANCE.createSystemFunction();
        ((InternalEObject) proxy).eSetProxyURI(URI.createURI("memory:/missing.xmi#x"));
        unchanged(() -> constraints.constraint(authorize, "X", body(), List.of(proxy)));
        Resource foreign = resources().createResource(URI.createURI("memory:/foreign.xmi"));
        var foreignProject = id(CapellamodellerFactory.eINSTANCE.createProject());
        foreign.getContents().add(foreignProject);
        unchanged(() -> constraints.constraint(authorize, "X", body(), List.of(foreignProject)));
        unchanged(() -> constraints.constraint(foreignProject, "X", body(), List.of(authorize)));
        Resource nonsemantic =
                resources.createResource(URI.createURI("memory:/representation.xmi"));
        SessionSafeguards.runWrite(
                session,
                "Prepare nonsemantic endpoint",
                () -> {
                    nonsemantic
                            .getContents()
                            .add(id(CapellamodellerFactory.eINSTANCE.createProject()));
                    return null;
                });
        unchanged(
                () ->
                        constraints.constraint(
                                authorize, "X", body(), List.of(nonsemantic.getContents().get(0))));
    }

    @Test
    public void aDifferentSemanticProjectInTheSameCapturedResourceSetStillRejects()
            throws Exception {
        Resource other = resources.createResource(URI.createURI("memory:/other-project.xmi"));
        Project project = id(CapellamodellerFactory.eINSTANCE.createProject());
        SessionSafeguards.runWrite(
                session,
                "Prepare other semantic project",
                () -> {
                    other.getContents().add(project);
                    return null;
                });
        semantic.add(other);
        unchanged(() -> constraints.constraint(authorize, "X", body(), List.of(project)));
        unchanged(() -> constraints.constraint(project, "X", body(), List.of(authorize)));
    }

    @Test
    public void descriptionOnlyAndAbstractOrMalformedExistingSpecificationsRejectUnchanged()
            throws Exception {
        Constraint result = id(CapellacoreFactory.eINSTANCE.createConstraint());
        result.setName("Authorization");
        result.setDescription(body());
        prepare(() -> authorize.getOwnedConstraints().add(result));
        unchanged(this::create);
        prepare(
                () -> {
                    result.setOwnedSpecification(
                            id(DatavalueFactory.eINSTANCE.createLiteralStringValue()));
                });
        unchanged(this::create);
        prepare(
                () -> {
                    OpaqueExpression expression =
                            id(DatavalueFactory.eINSTANCE.createOpaqueExpression());
                    expression.getLanguages().add("Text");
                    expression.getBodies().add(body());
                    result.setOwnedSpecification(expression);
                    result.getConstrainedElements().addAll(List.of(authorize, command));
                });
        unchanged(this::create);
    }

    @Test
    public void duplicateSameNameAndMalformedStoredSubjectsRejectWithoutRepairingThem()
            throws Exception {
        Constraint first = create();
        prepare(
                () -> {
                    var duplicate = id(CapellacoreFactory.eINSTANCE.createConstraint());
                    duplicate.setName(first.getName());
                    authorize.getOwnedConstraints().add(duplicate);
                });
        unchanged(this::create);
        prepare(() -> authorize.getOwnedConstraints().remove(1));
        var proxy = CtxFactory.eINSTANCE.createSystemFunction();
        ((InternalEObject) proxy).eSetProxyURI(URI.createURI("memory:/not-loaded.xmi#missing"));
        prepare(() -> first.getConstrainedElements().set(1, proxy));
        unchanged(this::create);
        assertTrue(proxy.eIsProxy());
    }

    @Test
    public void closedOrMissingSessionCannotCreate() throws Exception {
        open = false;
        unchanged(this::create);
        open = true;
        unchanged(
                () ->
                        new ArcadiaConstraintAPI(null)
                                .constraint(authorize, "X", body(), List.of(authorize)));
    }

    @Test
    public void exactBoundaryTextIsPreservedAndCatalogExposesTypedFacade() {
        String name = "x".repeat(240), body = "line\n" + "x".repeat(11995);
        Constraint result =
                new ArcadiaAPI(session).constraint(authorize, name, body, List.of(authorize));
        assertEquals(name, result.getName());
        assertEquals(
                List.of(body), ((OpaqueExpression) result.getOwnedSpecification()).getBodies());
        assertTrue(ApiCatalog.methodNames("arcadia").contains("constraint"));
        assertNull(
                ApiCatalog.validateCode(
                        "api.arcadia.constraint(owner, 'Scope', 'Predicate', [subject])"));
    }

    @Test
    public void invalidXmlCharactersRejectBeforeCommitAndUnicodeTextReloads() throws Exception {
        unchanged(
                () ->
                        constraints.constraint(
                                authorize, "X", "invalid\u0001text", List.of(authorize)));
        unchanged(
                () -> constraints.constraint(authorize, "X", "unpaired\uD800", List.of(authorize)));
        unchanged(
                () ->
                        constraints.constraint(
                                authorize, "invalid\uDC00", body(), List.of(authorize)));
        String text =
                "Temperature \u2264 limit; \u03a9/V; \uD83D\uDD12\n"
                    + "	Manual review remains required.";
        Constraint result =
                constraints.constraint(authorize, "Unicode predicate", text, List.of(authorize));
        Resource copy = resources().createResource(resource.getURI());
        copy.load(new ByteArrayInputStream(saved), Map.of());
        assertEquals(
                List.of(text),
                ((OpaqueExpression)
                                ((Constraint) copy.getEObject(result.getId()))
                                        .getOwnedSpecification())
                        .getBodies());
    }

    private Constraint create() {
        return constraints.constraint(
                authorize, "Authorization", body(), List.of(authorize, command));
    }

    private static String body() {
        return "Active propulsion requires valid demand AND permission; otherwise command is zero.";
    }

    private void prepare(Runnable action) {
        SessionSafeguards.runWrite(
                session,
                "Prepare constraint fixture",
                () -> {
                    action.run();
                    return null;
                });
    }

    private void unchanged(Runnable action) throws Exception {
        byte[] before = bytes(resource);
        int priorCount = count(), priorSaves = saves;
        boolean dirty = resource.isModified();
        var undo = domain.getCommandStack().getUndoCommand();
        var redo = domain.getCommandStack().getRedoCommand();
        assertThrows(RuntimeException.class, action::run);
        assertEquals(priorCount, count());
        assertEquals(priorSaves, saves);
        assertEquals(dirty, resource.isModified());
        assertSame(undo, domain.getCommandStack().getUndoCommand());
        assertSame(redo, domain.getCommandStack().getRedoCommand());
        assertArrayEquals(before, bytes(resource));
    }

    private int count() {
        return objects().size();
    }

    private List<EObject> objects() {
        List<EObject> values = new ArrayList<>();
        var iterator = resource.getAllContents();
        while (iterator.hasNext()) values.add(iterator.next());
        return values;
    }

    private static byte[] bytes(Resource resource) throws Exception {
        var output = new ByteArrayOutputStream();
        resource.save(output, Map.of());
        return output.toByteArray();
    }

    private static ResourceSetImpl resources() {
        var set = new ResourceSetImpl();
        set.getResourceFactoryRegistry()
                .getExtensionToFactoryMap()
                .put("xmi", new XMIResourceFactoryImpl());
        return set;
    }

    private static <T extends CapellaElement> T id(T element) {
        element.setId(UUID.randomUUID().toString());
        return element;
    }

    private static void identifyTree(EObject root) {
        if (root instanceof CapellaElement element) id(element);
        var iterator = root.eAllContents();
        while (iterator.hasNext())
            if (iterator.next() instanceof CapellaElement element) id(element);
    }
}
