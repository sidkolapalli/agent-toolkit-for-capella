package org.polarsys.capella.ai.agent;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.eclipse.emf.transaction.RecordingCommand;
import org.eclipse.emf.transaction.Transaction;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.swt.widgets.Display;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.polarsys.capella.ai.agent.ArcadiaAgent.Postcondition;
import org.polarsys.capella.ai.agent.ModelPostconditions.Status;
import org.polarsys.capella.ai.codex.CodexArcadiaAgent;
import org.polarsys.capella.ai.codex.CodexToolSchemas;
import org.polarsys.capella.ai.codex.CodexTransport;
import org.polarsys.capella.ai.llm.LLMService.Message;
import org.polarsys.capella.core.data.capellacore.CapellaElement;
import org.polarsys.capella.core.data.capellacore.NamedElement;
import org.polarsys.capella.core.data.capellamodeller.CapellamodellerFactory;
import org.polarsys.capella.core.data.ctx.CtxFactory;
import org.polarsys.capella.core.data.ctx.SystemFunction;
import org.polarsys.capella.core.data.fa.FaFactory;
import org.polarsys.capella.core.data.fa.FunctionRealization;
import org.polarsys.capella.core.data.interaction.InteractionFactory;
import org.polarsys.capella.core.data.interaction.Scenario;
import org.polarsys.capella.core.data.la.LaFactory;
import org.polarsys.capella.core.data.la.LogicalFunction;

/** Native references plus real Direct/Codex protocol parsers. No provider, process or model edits are invoked by an agent. */
public class NativeStructuralPostconditionIntegrationTest {
    private Resource resource;
    private TransactionalEditingDomain domain;
    private Session session;
    private LogicalFunction logical;
    private SystemFunction system;
    private FunctionRealization realization;
    private Scenario scenario;
    private boolean open;
    private final AtomicInteger saves = new AtomicInteger();

    @Before public void model() {
        Display.getDefault();
        var resources = new ResourceSetImpl();
        resources.getResourceFactoryRegistry().getExtensionToFactoryMap().put("xmi", new XMIResourceFactoryImpl());
        resource = resources.createResource(URI.createURI("memory:/structural-postconditions.xmi"));
        var project = identified(CapellamodellerFactory.eINSTANCE.createProject(), "project");
        var engineering = identified(CapellamodellerFactory.eINSTANCE.createSystemEngineering(), "engineering");
        project.getOwnedModelRoots().add(engineering); resource.getContents().add(project);
        var sa = identified(CtxFactory.eINSTANCE.createSystemAnalysis(), "sa");
        var systemFunctions = identified(CtxFactory.eINSTANCE.createSystemFunctionPkg(), "sa-functions"); sa.setOwnedFunctionPkg(systemFunctions);
        var root = named(CtxFactory.eINSTANCE.createSystemFunction(), "system-root", "Root system function");
        systemFunctions.getOwnedSystemFunctions().add(root);
        system = named(CtxFactory.eINSTANCE.createSystemFunction(), "system", "Authorize demand"); root.getOwnedFunctions().add(system);
        var la = identified(LaFactory.eINSTANCE.createLogicalArchitecture(), "la");
        var logicalFunctions = identified(LaFactory.eINSTANCE.createLogicalFunctionPkg(), "la-functions"); la.setOwnedFunctionPkg(logicalFunctions);
        var logicalRoot = named(LaFactory.eINSTANCE.createLogicalFunction(), "logical-root", "Root logical function");
        logicalFunctions.getOwnedLogicalFunctions().add(logicalRoot);
        logical = named(LaFactory.eINSTANCE.createLogicalFunction(), "logical", "Authorize demand"); logicalRoot.getOwnedFunctions().add(logical);
        realization = identified(FaFactory.eINSTANCE.createFunctionRealization(), "realization");
        realization.setSourceElement(logical); realization.setTargetElement(system); logical.getOwnedFunctionRealizations().add(realization);
        scenario = named(InteractionFactory.eINSTANCE.createScenario(), "scenario", "Nominal");
        var capability = identified(CtxFactory.eINSTANCE.createCapability(), "capability");
        var capabilities = identified(CtxFactory.eINSTANCE.createCapabilityPkg(), "capabilities"); sa.setOwnedAbstractCapabilityPkg(capabilities);
        capabilities.getOwnedCapabilities().add(capability); capability.getOwnedScenarios().add(scenario);
        scenario.getOwnedInstanceRoles().addAll(List.of(
            named(InteractionFactory.eINSTANCE.createInstanceRole(), "role-one", "Vehicle"),
            named(InteractionFactory.eINSTANCE.createInstanceRole(), "role-two", "Controller")));
        engineering.getOwnedArchitectures().addAll(List.of(sa, la));
        domain = TransactionalEditingDomain.Factory.INSTANCE.createEditingDomain(resources); open = true;
        session = session(domain, List.of(resource), null);
    }

    @After public void dispose() { if (domain != null) domain.dispose(); }

    @Test public void observedElementsCannotCompleteAWrongRealizationDirection() {
        var report = ModelPostconditions.check(session, List.of(exists("edit", "logical"), exists("edit", "system"),
            structural("edit", reference("realization", "sourceElement", id("system")))));
        assertEquals(List.of(Status.PASSED, Status.PASSED, Status.FAILED), statuses(report));
        assertFalse(report.allPassed()); assertTrue(report.completedStepIds().isEmpty());
        String summary = report.markdownSummary();
        assertTrue("A failed check says what was declared: " + summary, summary.contains("sourceElement points to element system"));
        assertTrue("and what was found: " + summary, summary.contains("Observed value: LogicalFunction Authorize demand"));
        assertTrue(report.feedback().toString().contains("sourceElement"));
    }

    @Test public void everyConditionForAStepMustPassAndOtherStepsRemainIndependent() {
        var report = ModelPostconditions.check(session, List.of(exists("relationship", "logical"),
            structural("relationship", reference("realization", "sourceElement", id("logical"))),
            structural("relationship", reference("realization", "targetElement", id("logical"))),
            exists("other", "system"), structural("other", reference("realization", "targetElement", id("system")))));
        assertEquals(Set.of("other"), report.completedStepIds()); assertFalse(report.allPassed());
        var corrected = ModelPostconditions.check(session, List.of(exists("relationship", "logical"),
            structural("relationship", reference("realization", "sourceElement", id("logical"))),
            structural("relationship", reference("realization", "targetElement", id("system")))));
        assertEquals(Set.of("relationship"), corrected.completedStepIds()); assertTrue(corrected.allPassed());
    }

    @Test public void closedAndIncompleteNativeReadsNeverCompleteMixedSteps() throws Exception {
        var conditions = List.of(exists("edit", "logical"), structural("edit", validReference()));
        open = false;
        var closed = ModelPostconditions.check(session, conditions);
        assertEquals(List.of(Status.UNAVAILABLE, Status.UNAVAILABLE), statuses(closed)); assertTrue(closed.completedStepIds().isEmpty());
        open = true;
        var bounded = ModelPostconditions.class.getDeclaredMethod("check", Session.class, List.class, int.class);
        bounded.setAccessible(true);
        var partial = (ModelPostconditions.Report) bounded.invoke(null, session, conditions, 1);
        assertEquals(Status.UNAVAILABLE, partial.results().get(1).status()); assertTrue(partial.completedStepIds().isEmpty());
        resource.getErrors().add(new Resource.Diagnostic() {
            @Override public String getMessage() { return "Unreadable model"; }
            @Override public String getLocation() { return "memory:/structural-postconditions.xmi"; }
            @Override public int getLine() { return 1; }
            @Override public int getColumn() { return 1; }
        });
        assertTrue(ModelPostconditions.check(session, conditions).completedStepIds().isEmpty());
    }

    @Test public void ambiguousStructuralSelectorsNeverPromotePassingExistenceEvidence() {
        write(() -> ((LogicalFunction) logical.eContainer()).getOwnedFunctions().add(
            named(LaFactory.eINSTANCE.createLogicalFunction(), "logical-duplicate", logical.getName())));
        var ambiguous = reference("realization", "sourceElement", Map.of("type", "LogicalFunction", "name", logical.getName(), "layer", "LA"));
        var report = ModelPostconditions.check(session, List.of(exists("edit", "logical"), structural("edit", ambiguous)));
        assertEquals(Status.PASSED, report.results().get(0).status()); assertEquals(Status.UNAVAILABLE, report.results().get(1).status());
        assertTrue(report.completedStepIds().isEmpty());
    }

    @Test public void unresolvedOrForeignReferencedEvidenceNeverCompletesAnEditStep() {
        var proxy = CtxFactory.eINSTANCE.createSystemFunction();
        ((InternalEObject) proxy).eSetProxyURI(URI.createURI("memory:/unknown.xmi#unresolved"));
        write(() -> realization.setTargetElement(proxy));
        var report = ModelPostconditions.check(session, List.of(exists("edit", "logical"),
            structural("edit", reference("realization", "targetElement", id("system")))));
        assertEquals(Status.UNAVAILABLE, report.results().get(1).status()); assertTrue(report.completedStepIds().isEmpty()); assertTrue(proxy.eIsProxy());
        var foreign = domain.getResourceSet().createResource(URI.createURI("memory:/foreign.xmi"));
        var foreignFunction = named(CtxFactory.eINSTANCE.createSystemFunction(), "foreign", "Foreign demand");
        write(() -> { foreign.getContents().add(foreignFunction); realization.setTargetElement(foreignFunction); });
        report = ModelPostconditions.check(session, List.of(exists("edit", "logical"),
            structural("edit", reference("realization", "targetElement", id("foreign")))));
        assertEquals(Status.UNAVAILABLE, report.results().get(1).status()); assertTrue(report.completedStepIds().isEmpty());
    }

    @Test public void reviewedDeclarationDeepFreezesSelectorsContainersAndOrderedLists() {
        var first = mutable("id", "role-one"); var second = mutable("id", "role-two");
        var expected = new ArrayList<Map<String, Object>>(List.of(first, second));
        var check = mutable("kind", "reference_sequence_equals", "target", mutable("id", "scenario"),
            "feature", "ownedInstanceRoles", "expected", expected);
        var condition = structural("edit", check);
        first.put("id", "role-two"); expected.clear(); check.put("feature", "ownedMessages");
        var frozen = object(condition.arguments().get("check"));
        assertEquals("ownedInstanceRoles", frozen.get("feature")); assertEquals(id("role-one"), ((List<?>) frozen.get("expected")).get(0));
        assertThrows(UnsupportedOperationException.class, () -> frozen.put("feature", "other"));
        assertThrows(UnsupportedOperationException.class, () -> object(frozen.get("target")).put("id", "other"));
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>) frozen.get("expected")).clear());
        var container = mutable("id", "scenario");
        var selector = mutable("type", "InstanceRole", "name", "Vehicle", "container", container);
        var nested = structural("edit", Map.of("kind", "contained_by", "target", selector, "container", id("scenario")));
        container.put("id", "missing"); selector.put("name", "Controller");
        assertEquals("scenario", object(object(object(nested.arguments().get("check")).get("target")).get("container")).get("id"));
        assertTrue(ModelPostconditions.check(session, List.of(condition, nested)).allPassed());
        assertDetached(condition.arguments());
    }

    @Test public void mixedChecksShareOneSwtExclusiveBoundaryWithoutModelSaveUndoOrAdapterEffects() throws Exception {
        write(() -> logical.setSummary("Existing undoable command"));
        byte[] before = bytes(); resource.setModified(false);
        var adapters = adapters(); Object undo = domain.getCommandStack().getUndoCommand(), redo = domain.getCommandStack().getRedoCommand();
        AtomicInteger depth = new AtomicInteger(), boundaries = new AtomicInteger(); List<Transaction> roots = new ArrayList<>();
        var observedDomain = (TransactionalEditingDomain) Proxy.newProxyInstance(TransactionalEditingDomain.class.getClassLoader(),
            new Class<?>[] { TransactionalEditingDomain.class }, (proxy, method, args) -> {
                try {
                    if (!"runExclusive".equals(method.getName())) return method.invoke(domain, args);
                    if (depth.getAndIncrement() == 0) boundaries.incrementAndGet();
                    try {
                        return domain.runExclusive(() -> { assertNotNull(Display.getCurrent()); assertTrue(activeRoot().isReadOnly()); ((Runnable) args[0]).run(); });
                    } finally { depth.decrementAndGet(); }
                } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
        Session observedSession = session(observedDomain, List.of(resource), () -> {
            assertNotNull(Display.getCurrent()); assertTrue(depth.get() > 0); roots.add(activeRoot());
        });
        var conditions = List.of(exists("edit", "logical"), new Postcondition("edit", "attribute_equals",
            Map.of("id", "logical", "attribute", "name", "expected", "Authorize demand")), structural("edit", validReference()));
        AtomicReference<ModelPostconditions.Report> report = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { report.set(ModelPostconditions.check(observedSession, conditions)); } catch (Throwable problem) { failure.set(problem); }
        }, "structural-postcondition-read");
        worker.start(); long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (worker.isAlive() && System.nanoTime() < deadline) {
            if (!Display.getDefault().readAndDispatch()) Thread.sleep(1);
        }
        if (worker.isAlive()) { worker.interrupt(); fail("Native read did not finish within the bounded test interval"); }
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertTrue(report.get().markdownSummary(), report.get().allPassed()); assertEquals(1, boundaries.get());
        assertTrue("Both native read phases were observed", roots.size() >= 2);
        for (Transaction transaction : roots) assertSame(roots.get(0), transaction);
        assertFalse(resource.isModified()); assertEquals(0, saves.get());
        assertSame(undo, domain.getCommandStack().getUndoCommand()); assertSame(redo, domain.getCommandStack().getRedoCommand());
        var afterAdapters = adapters(); assertEquals(adapters.size(), afterAdapters.size());
        adapters.forEach((owner, values) -> assertEquals(values, afterAdapters.get(owner)));
        assertArrayEquals(before, bytes()); assertDetached(report.get().feedback());
    }

    @Test public void directProposalAcceptsStructuralDataButFalseRelationshipRemainsPendingAfterReceipt() {
        var provider = new ScriptedProvider(planJson(), proposalJson(reference("realization", "sourceElement", id("system"))), finalJson());
        var agent = direct(provider);
        var review = agent.run("Native model instructions", List.of(), "Realize the demand function", () -> false, null);
        assertTrue(review.hasProposal()); assertEquals("native_structure", review.postconditions().get(0).kind());
        var report = ModelPostconditions.check(session, review.postconditions()); assertTrue(report.completedStepIds().isEmpty());
        var result = agent.finish(review, outcome(report), () -> false, null);
        assertEquals("pending", result.plan().get(0).status());
        assertTrue(result.messages().stream().anyMatch(message -> message.getContent().contains("AUTHORITATIVE CAPELLA EXECUTION OUTCOME")));
        assertEquals(0, saves.get());
    }

    @Test public void directProtocolRejectsInvalidNestedDeclarationsBeforeReview() {
        for (Map<String, Object> check : invalidChecks()) {
            var provider = new ScriptedProvider(planJson(), proposalJson(check));
            var failure = assertThrows(ArcadiaAgent.AgentException.class,
                () -> direct(provider).run("Native instructions", List.of(), "Edit", () -> false, null));
            assertFalse(failure.turn().hasProposal()); assertEquals("pending", failure.turn().plan().get(0).status());
        }
        assertEquals(0, saves.get());
    }

    @Test public void directPostCommitPaginationCanReadMoreThanThreePagesWithinTheCumulativeBudget() {
        String page = AgentJson.write(mutable("kind", "tool", "toolName", "native_validation", "arguments", mutable("limit", 1, "cursor", "page")));
        var provider = new ScriptedProvider(planJson(), proposalJson(validReference()), page, page, page, page, finalJson());
        AtomicInteger reads = new AtomicInteger();
        var agent = new ArcadiaAgent(provider, (name, args) -> {
            assertEquals("native_validation", name); reads.incrementAndGet(); return "{\"ok\":true,\"result\":{\"messages\":[]}}";
        }, Set.of("native_validation"), new ArcadiaAgent.Limits(7, 4, 200_000, 32_000, 120_000, false));
        var review = agent.run("Native instructions", List.of(), "Realize demand and inspect diagnostics", () -> false, null);
        assertEquals(2, review.requests()); assertEquals(0, review.toolCalls());
        var report = ModelPostconditions.check(session, review.postconditions());
        var result = agent.finish(review, outcome(report), () -> false, null);
        assertEquals(4, reads.get()); assertEquals(4, result.toolCalls()); assertEquals(7, result.requests());
        assertFalse(result.hasProposal()); assertEquals("completed", result.plan().get(0).status()); assertEquals(0, saves.get());
    }

    @Test public void codexProposalAcceptsStructuralDataAndUsesOnlyPassingAuthoritativeStepIds() {
        var fake = new FakeTransport(); fake.add(planEvent(), proposalEvent("write", validReference()));
        try (var agent = codex(fake)) {
            var review = agent.run("Native instructions", List.of(), "Realize demand", () -> false, null);
            assertTrue(review.hasProposal()); assertFalse(fake.responses.containsKey("write"));
            var report = ModelPostconditions.check(session, review.postconditions()); assertEquals(Set.of("edit"), report.completedStepIds());
            agent.recordOutcome(review, outcome(report));
            assertEquals("completed", agent.outcomeSnapshot().plan().get(0).status());
            assertTrue(AgentJson.write(fake.responses.get("write")).contains("AUTHORITATIVE CAPELLA EXECUTION OUTCOME"));
        }
        fake = new FakeTransport(); fake.add(planEvent(), proposalEvent("write", reference("realization", "sourceElement", id("system"))));
        try (var agent = codex(fake)) {
            var review = agent.run("Native instructions", List.of(), "Realize demand", () -> false, null);
            var report = ModelPostconditions.check(session, review.postconditions()); agent.recordOutcome(review, outcome(report));
            assertEquals("pending", agent.outcomeSnapshot().plan().get(0).status());
            assertTrue(agent.outcomeSnapshot().messages().stream().anyMatch(message -> message.getContent().contains("\"completedStepIds\":[]")));
        }
        assertEquals(0, saves.get());
    }

    @Test public void codexRejectsMalformedNestedDataBeforeReviewAndKeepsTheEditPending() {
        for (var check : invalidChecks()) {
            var fake = new FakeTransport(); fake.add(planEvent(), proposalEvent("invalid", check), doneEvent());
            try (var agent = codex(fake)) {
                var result = agent.run("Native instructions", List.of(), "Edit", () -> false, null);
                assertFalse(result.hasProposal()); assertEquals("pending", result.plan().get(0).status());
                assertEquals(Boolean.FALSE, fake.responses.get("invalid").get("success"));
            }
        }
        assertEquals(0, saves.get());
    }

    @Test public void codexAdvertisedSchemaContainsEverySupportedNestedKindAndBoundedNativeSelectors() {
        var tool = CodexToolSchemas.definitions(Set.of()).stream().filter(value -> CodexToolSchemas.PROPOSE.equals(value.get("name"))).findFirst().orElseThrow();
        var schema = object(tool.get("inputSchema")); assertLocalReferences(schema, schema);
        var conditions = object(object(schema.get("properties")).get("postconditions"));
        assertEquals(12, conditions.get("maxItems"));
        var item = object(conditions.get("items")); assertEquals(Boolean.FALSE, item.get("additionalProperties"));
        var check = object(object(item.get("properties")).get("check"));
        var branches = ((List<?>) check.get("oneOf")).stream().map(NativeStructuralPostconditionIntegrationTest::object).toList();
        assertEquals(Set.of("reference_equals", "reference_sequence_equals", "contained_by", "relation_exists", "diagram_shows"),
            Set.copyOf(branches.stream().map(value -> String.valueOf(object(object(value.get("properties")).get("kind")).get("const"))).toList()));
        for (var branch : branches) assertEquals(Boolean.FALSE, branch.get("additionalProperties"));
        var sequence = branches.get(1); assertEquals(32, object(object(sequence.get("properties")).get("expected")).get("maxItems"));
        var predicates = object(object(branches.get(3).get("properties")).get("predicates"));
        assertEquals(1, predicates.get("minProperties")); assertEquals(4, predicates.get("maxProperties"));
        var selector = resolveLocal(schema, object(object(branches.get(0).get("properties")).get("target")));
        var nestedContainer = resolveLocal(schema, object(object(selector.get("properties")).get("container")));
        assertEquals(Boolean.FALSE, selector.get("additionalProperties")); assertEquals(Boolean.FALSE, nestedContainer.get("additionalProperties"));
        assertFalse(object(nestedContainer.get("properties")).containsKey("container"));
    }

    private static void assertLocalReferences(Map<String, Object> root, Object value) {
        if (value instanceof Map<?, ?> map) {
            if (map.containsKey("$ref")) assertFalse(resolveLocal(root, object(map)).isEmpty());
            map.values().forEach(child -> assertLocalReferences(root, child));
        } else if (value instanceof List<?> list) list.forEach(child -> assertLocalReferences(root, child));
    }
    private static Map<String, Object> resolveLocal(Map<String, Object> root, Map<String, Object> schema) {
        if (!schema.containsKey("$ref")) return schema;
        assertTrue(schema.get("$ref") instanceof String);
        String ref = (String) schema.get("$ref"); assertTrue("Only a defined local selector reference is allowed", ref.startsWith("#/$defs/"));
        String name = ref.substring("#/$defs/".length()); assertFalse(name.contains("/"));
        var definitions = object(root.get("$defs")); assertTrue("Every advertised reference must resolve", definitions.containsKey(name));
        return object(definitions.get(name));
    }

    private Transaction activeRoot() {
        try {
            Transaction active = (Transaction) domain.getClass().getMethod("getActiveTransaction").invoke(domain);
            assertNotNull(active); while (active.getParent() != null) active = active.getParent(); return active;
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private Session session(TransactionalEditingDomain editing, List<Resource> semantic, Runnable onResources) {
        return (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[] { Session.class }, (proxy, method, args) -> switch (method.getName()) {
            case "getTransactionalEditingDomain" -> editing; case "isOpen" -> open;
            case "getSemanticResources" -> { if (onResources != null) onResources.run(); yield semantic; }
            case "save" -> { saves.incrementAndGet(); yield null; }
            case "hashCode" -> System.identityHashCode(proxy); case "equals" -> proxy == args[0]; default -> null;
        });
    }
    private void write(Runnable action) { domain.getCommandStack().execute(new RecordingCommand(domain) { @Override protected void doExecute() { action.run(); } }); }
    private byte[] bytes() throws Exception { var output = new ByteArrayOutputStream(); resource.save(output, Map.of()); return output.toByteArray(); }
    private Map<Object, List<?>> adapters() {
        Map<Object, List<?>> result = new IdentityHashMap<>(); result.put(domain.getResourceSet(), List.copyOf(domain.getResourceSet().eAdapters()));
        result.put(resource, List.copyOf(resource.eAdapters())); var iterator = resource.getAllContents();
        while (iterator.hasNext()) { EObject value = iterator.next(); result.put(value, List.copyOf(value.eAdapters())); } return result;
    }
    private static List<Status> statuses(ModelPostconditions.Report report) { return report.results().stream().map(ModelPostconditions.Result::status).toList(); }
    private static Postcondition exists(String step, String id) { return new Postcondition(step, "element_exists", id(id)); }
    private static Postcondition structural(String step, Map<String, Object> check) { return new Postcondition(step, "native_structure", Map.of("check", check)); }
    private static Map<String, Object> id(String id) { return Map.of("id", id); }
    private static Map<String, Object> reference(String target, String feature, Object expected) { return mutable("kind", "reference_equals", "target", id(target), "feature", feature, "expected", expected); }
    private static Map<String, Object> validReference() { return reference("realization", "sourceElement", id("logical")); }
    private static List<Map<String, Object>> invalidChecks() {
        return List.of(mutable("kind", "evaluate", "expression", "anything"),
            mutable("kind", "reference_equals", "target", mutable("type", "SequenceMessage", "name", "Future message", "layer", "OA"),
                "feature", "invokedOperation", "expected", id("future-exchange")),
            mutable("kind", "relation_exists", "type", "FunctionRealization", "predicates", mutable("unknownReference", id("logical"))),
            mutable("kind", "contained_by", "target", mutable("type", "FunctionalChainInvolvementFunction", "name", "Invented label"),
                "container", id("future-chain")),
            mutable("kind", "contained_by", "target", mutable("id", "logical", "getter", "arbitrary"), "container", id("logical-root")),
            mutable("kind", "reference_sequence_equals", "target", id("scenario"), "feature", "ownedInstanceRoles", "expected", List.of(id("role-one"), id("role-one"))),
            mutable("kind", "contained_by", "target", mutable("type", "UnknownNativeClassifier", "name", "Demand"), "container", id("logical-root")));
    }
    private static ArcadiaAgent.ExecutionOutcome outcome(ModelPostconditions.Report report) {
        return new ArcadiaAgent.ExecutionOutcome(true, true, true, report.markdownSummary(), report.completedStepIds());
    }
    private static ArcadiaAgent direct(ScriptedProvider provider) { return new ArcadiaAgent(provider, (name, args) -> { throw new AssertionError("No tool was requested"); }, Set.of(), ArcadiaAgent.Limits.defaults()); }
    private static String planJson() { return AgentJson.write(mutable("kind", "plan", "steps", List.of(editStep()))); }
    private static String proposalJson(Map<String, Object> check) { return AgentJson.write(proposalArguments(check)); }
    private static String finalJson() { return AgentJson.write(mutable("kind", "final", "message", "The actual receipt was received.")); }
    private static Map<String, Object> editStep() { return mutable("id", "edit", "title", "Realize demand in the captured model", "type", "edit", "status", "pending"); }
    private static Map<String, Object> proposalArguments(Map<String, Object> check) {
        return mutable("kind", "proposal", "message", "Review the demand realization", "script", "return 'proposal only'", "stepIds", List.of("edit"),
            "postconditions", List.of(mutable("stepId", "edit", "kind", "native_structure", "check", check)));
    }
    private static CodexArcadiaAgent codex(FakeTransport fake) {
        return new CodexArcadiaAgent(fake, (name, args) -> { throw new AssertionError("No tool was requested"); }, Set.of(), thread -> {}, null, null,
            "E:/synthetic-capella", UnaryOperator.identity(), UnaryOperator.identity(), new CodexArcadiaAgent.Limits(32, 24, 3, Duration.ofSeconds(5)));
    }
    private static Map<String, Object> planEvent() { return call("plan", CodexToolSchemas.PLAN, mutable("message", "Plan", "steps", List.of(editStep()))); }
    private static Map<String, Object> proposalEvent(String id, Map<String, Object> check) {
        var args = proposalArguments(check); args.remove("kind"); return call(id, CodexToolSchemas.PROPOSE, args);
    }
    private static Map<String, Object> call(String id, String tool, Map<String, Object> args) {
        return mutable("id", id, "method", "item/tool/call", "params", mutable("threadId", "thread-1", "turnId", "turn-1", "callId", "call-" + id, "tool", tool, "arguments", args));
    }
    private static Map<String, Object> doneEvent() {
        return mutable("method", "turn/completed", "params", mutable("threadId", "thread-1", "turnId", "turn-1", "turn", mutable("id", "turn-1", "status", "completed",
            "items", List.of(mutable("type", "agentMessage", "id", "answer", "phase", "final_answer", "text", "No changes were proposed.")))));
    }
    private static Map<String, Object> mutable(Object... entries) { Map<String, Object> result = new LinkedHashMap<>(); for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]); return result; }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }
    private static <T extends CapellaElement> T identified(T element, String id) { element.setId(id); return element; }
    private static <T extends NamedElement> T named(T element, String id, String name) { identified(element, id); element.setName(name); return element; }
    private static void assertDetached(Object value) {
        assertFalse(value instanceof EObject); assertFalse(value instanceof Resource); assertFalse(value instanceof Session);
        if (value instanceof Map<?, ?> map) map.values().forEach(NativeStructuralPostconditionIntegrationTest::assertDetached);
        else if (value instanceof List<?> list) list.forEach(NativeStructuralPostconditionIntegrationTest::assertDetached);
        else assertTrue(value == null || value instanceof String || value instanceof Number || value instanceof Boolean);
    }
    private static final class ScriptedProvider implements ArcadiaAgent.Provider {
        private final Deque<String> replies;
        ScriptedProvider(String... replies) { this.replies = new ArrayDeque<>(List.of(replies)); }
        @Override public String send(List<Message> messages) { assertFalse("Unexpected provider continuation", replies.isEmpty()); return replies.removeFirst(); }
    }
    private static final class FakeTransport implements CodexTransport {
        private final Deque<Map<String, Object>> events = new ArrayDeque<>();
        private final Map<Object, Map<String, Object>> responses = new LinkedHashMap<>();
        @SafeVarargs final void add(Map<String, Object>... values) { events.addAll(List.of(values)); }
        @Override public Map<String, Object> request(String method, Map<String, Object> params, BooleanSupplier cancelled) {
            return switch (method) {
                case "initialize", "turn/interrupt" -> Map.of();
                case "account/read" -> mutable("requiresOpenaiAuth", true, "account", mutable("type", "chatgpt"));
                case "thread/start", "thread/resume" -> mutable("thread", mutable("id", "thread-1"));
                case "turn/start" -> mutable("turn", mutable("id", "turn-1"));
                default -> throw new AssertionError("Unexpected RPC " + method);
            };
        }
        @Override public void notify(String method, Map<String, Object> params) { assertEquals("initialized", method); }
        @Override public Map<String, Object> nextEvent(BooleanSupplier cancelled, Duration timeout) { return events.isEmpty() ? null : events.removeFirst(); }
        @Override public void respond(Object id, Map<String, Object> response) { assertFalse(responses.containsKey(id)); responses.put(id, response); }
        @Override public void reject(Object id, int code, String message) { fail("Unexpected rejected native RPC"); }
        @Override public void close() { }
    }
}
