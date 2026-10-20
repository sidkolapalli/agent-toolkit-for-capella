package org.polarsys.capella.ai.agent;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.InternalEList;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.session.SessionSafeguards;
import org.polarsys.capella.core.data.cs.BlockArchitecture;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bounded, data-only observations of stored native structure. No supplied expressions or resolving
 * reads.
 */
public final class NativeStructuralAssertions {
    public static final int MAX_CHECKS = 12;
    public static final int MAX_ORDERED_TARGETS = 32;
    // The time budget is the real guard; the object cap only bounds memory (raised after a
    // 312,000-object stress test).
    public static final int MAX_SCAN_OBJECTS = 2_000_000;
    private static final long MAX_SCAN_NANOS = 10_000_000_000L;
    private static final int MAX_INPUT_BYTES = 65_536;
    private static final int MAX_OUTPUT_BYTES = 131_072;
    private static final Set<String> LAYERS = Set.of("OA", "SA", "LA", "PA", "EPBS");
    private static final Set<String> KINDS =
            Set.of(
                    "reference_equals",
                    "reference_sequence_equals",
                    "contained_by",
                    "relation_exists",
                    "diagram_shows");

    private NativeStructuralAssertions() {}

    public enum Status {
        PASSED,
        FAILED,
        UNAVAILABLE
    }

    /**
     * Scalar/list/map evidence only; no EObject, adapter, resource or mutable collection escapes.
     */
    public record Result(String kind, Status status, Object expected, Object actual, String note) {
        public Result {
            expected = detached(expected);
            actual = detached(actual);
            note = clip(note, 400);
        }

        public Map<String, Object> feedback() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("kind", kind);
            result.put("status", status.name().toLowerCase(java.util.Locale.ROOT));
            result.put("expected", expected);
            result.put("actual", actual);
            result.put("note", note);
            return Collections.unmodifiableMap(result);
        }
    }

    public static List<Result> check(Session session, List<Map<String, Object>> checks) {
        return check(session, checks, MAX_SCAN_OBJECTS);
    }

    /** Validate and detach a declaration before review, without reading a semantic model. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> declaration(Map<String, Object> check) {
        return (Map<String, Object>) detached(new Parse().check(map(check)).data);
    }

    /** Apply a smaller bounded traversal budget; incomplete evidence remains unavailable. */
    public static List<Result> check(
            Session session, List<Map<String, Object>> checks, int scanLimit) {
        if (checks == null
                || checks.size() > MAX_CHECKS
                || checks.stream().anyMatch(value -> value == null)) {
            throw invalid("At most 12 non-null native structural checks are supported");
        }
        if (scanLimit < 1 || scanLimit > MAX_SCAN_OBJECTS)
            throw invalid("Invalid native traversal budget");
        Parse parse = new Parse();
        List<Check> requests = checks.stream().map(parse::check).toList();
        if (requests.isEmpty()) return List.of();
        AtomicReference<List<Result>> results = new AtomicReference<>();
        AtomicReference<IllegalArgumentException> invalid = new AtomicReference<>();
        try {
            SessionSafeguards.runOnUiThread(
                    () -> {
                        if (session == null
                                || !session.isOpen()
                                || session.getTransactionalEditingDomain() == null) {
                            results.set(
                                    unavailable(
                                            requests,
                                            "The captured native model is closed or has no editing"
                                                + " domain."));
                            return;
                        }
                        TransactionalEditingDomain domain = session.getTransactionalEditingDomain();
                        try {
                            domain.runExclusive(
                                    () -> {
                                        if (!session.isOpen()
                                                || Thread.currentThread().isInterrupted()) {
                                            results.set(
                                                    unavailable(
                                                            requests,
                                                            "The captured model closed or its read"
                                                                + " was cancelled."));
                                            return;
                                        }
                                        List<Resource> resources =
                                                List.copyOf(session.getSemanticResources());
                                        if (resources.isEmpty()
                                                || resources.size() > 128
                                                || resources.stream()
                                                        .anyMatch(
                                                                resource ->
                                                                        resource == null
                                                                                || resource
                                                                                                .getResourceSet()
                                                                                        != domain
                                                                                                .getResourceSet()
                                                                                || !resource.getErrors()
                                                                                        .isEmpty())) {
                                            results.set(
                                                    unavailable(
                                                            requests,
                                                            "Loaded semantic resources in the"
                                                                + " captured editing domain are"
                                                                + " required."));
                                            return;
                                        }
                                        Snapshot snapshot = Snapshot.scan(resources, scanLimit);
                                        if (!snapshot.complete) {
                                            results.set(unavailable(requests, snapshot.note));
                                            return;
                                        }
                                        List<Result> evaluated = new ArrayList<>();
                                        int outputBytes = 0;
                                        for (Check request : requests) {
                                            Result result;
                                            try {
                                                result = evaluate(session, request, snapshot);
                                            } catch (EvidenceUnavailable incomplete) {
                                                result =
                                                        new Result(
                                                                request.kind,
                                                                Status.UNAVAILABLE,
                                                                request.data,
                                                                null,
                                                                incomplete.getMessage());
                                            }
                                            outputBytes += bytes(result.feedback());
                                            if (outputBytes > MAX_OUTPUT_BYTES) {
                                                results.set(
                                                        unavailable(
                                                                requests,
                                                                "The bounded evidence output was"
                                                                    + " exceeded; no structural"
                                                                    + " check was confirmed."));
                                                return;
                                            }
                                            evaluated.add(result);
                                        }
                                        results.set(List.copyOf(evaluated));
                                    });
                        } catch (IllegalArgumentException rejected) {
                            invalid.set(rejected);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            results.set(unavailable(requests, "The native read was interrupted."));
                        }
                    });
        } catch (IllegalArgumentException rejected) {
            throw rejected;
        } catch (RuntimeException failure) {
            results.set(
                    unavailable(
                            requests,
                            "The native read could not complete; no structural check was"
                                + " confirmed."));
        }
        if (invalid.get() != null) throw invalid.get();
        return results.get() == null
                ? unavailable(requests, "The native read returned no evidence.")
                : results.get();
    }

    private static Result evaluate(Session session, Check request, Snapshot snapshot) {
        snapshot.budget();
        if ("relation_exists".equals(request.kind)) return relation(request, snapshot);
        if ("diagram_shows".equals(request.kind)) return diagramShows(session, request, snapshot);
        Node target = snapshot.one(request.target);
        if (target == null)
            return failed(request, null, "The target selector has no native match.");
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("target", target.describe());
        if ("contained_by".equals(request.kind)) {
            Node expected = snapshot.one(request.container);
            EObject parent = container(target.object);
            actual.put("container", parent == null ? null : snapshot.scoped(parent).describe());
            return verdict(
                    request,
                    expected != null && parent == expected.object,
                    actual,
                    "Immediate native containment compared.");
        }
        EReference reference =
                reference(
                        target.object.eClass(),
                        request.feature,
                        "reference_sequence_equals".equals(request.kind));
        actual.put("feature", reference.getName());
        Object stored = target.object.eGet(reference, false);
        if ("reference_equals".equals(request.kind)) {
            Node expected = request.expected == null ? null : snapshot.one(request.expected);
            Node value = stored == null ? null : snapshot.scoped(object(stored));
            actual.put("value", value == null ? null : value.describe());
            boolean equal =
                    request.expected == null
                            ? stored == null
                            : expected != null && stored == expected.object;
            return verdict(
                    request,
                    equal,
                    actual,
                    "Stored single native reference compared without proxy resolution.");
        }
        if (!(stored instanceof InternalEList<?> list))
            throw unavailable(
                    "The native reference collection cannot be read without resolving proxies.");
        if (list.size() > MAX_SCAN_OBJECTS)
            throw unavailable("The bounded native reference read was exceeded.");
        List<Node> expected = new ArrayList<>();
        Set<EObject> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean missing = false;
        for (Selector selector : request.sequence) {
            Node value = snapshot.one(selector);
            if (value == null) missing = true;
            else if (!unique.add(value.object))
                throw invalid("Ordered expected selectors must identify distinct native targets");
            expected.add(value);
        }
        boolean equal = !missing && list.size() == expected.size();
        List<Map<String, Object>> observed = new ArrayList<>();
        for (int index = 0; index < list.size(); index++) {
            snapshot.budget();
            Node value = snapshot.scoped(object(list.basicGet(index)));
            if (index < MAX_ORDERED_TARGETS) observed.add(value.describe());
            if (index >= expected.size()
                    || expected.get(index) == null
                    || expected.get(index).object != value.object) equal = false;
        }
        actual.put("count", list.size());
        actual.put("values", observed);
        actual.put("truncated", list.size() > observed.size());
        return verdict(
                request,
                equal,
                actual,
                "Exact order and cardinality of a stored ordered native reference compared.");
    }

    private static Result relation(Check request, Snapshot snapshot) {
        Node owner = request.container == null ? null : snapshot.one(request.container);
        Map<String, Node> expected = new LinkedHashMap<>();
        boolean missing = request.container != null && owner == null;
        for (var predicate : request.predicates.entrySet()) {
            Node target = snapshot.one(predicate.getValue());
            expected.put(predicate.getKey(), target);
            missing |= target == null;
        }
        int count = 0;
        List<Map<String, Object>> matches = new ArrayList<>();
        // Validate the feature contract even when the current model has no relation of that type.
        for (EClass type : request.types)
            for (String feature : expected.keySet()) reference(type, feature, false);
        if (!missing)
            for (Node candidate : snapshot.nodes) {
                snapshot.budget();
                if (!matches(candidate.object.eClass(), request.types)
                        || owner != null && container(candidate.object) != owner.object) continue;
                boolean match = true;
                for (var predicate : expected.entrySet()) {
                    EReference feature =
                            reference(candidate.object.eClass(), predicate.getKey(), false);
                    Object value = candidate.object.eGet(feature, false);
                    if (value != null) snapshot.scoped(object(value));
                    if (value != predicate.getValue().object) match = false;
                }
                if (match) {
                    count++;
                    if (matches.size() < MAX_ORDERED_TARGETS) matches.add(candidate.describe());
                }
            }
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("matchCount", count);
        actual.put("matches", matches);
        actual.put("truncated", count > matches.size());
        return verdict(
                request,
                count == 1,
                actual,
                "Exactly one native relation must match every stored-reference predicate and"
                    + " optional immediate owner.");
    }

    /**
     * Exactly one representation of that name in the session's own resources has a view of every
     * element.
     */
    private static Result diagramShows(Session session, Check request, Snapshot snapshot) {
        Set<Resource> owned = new LinkedHashSet<>();
        if (session.getSessionResource() != null) owned.add(session.getSessionResource());
        if (session.getAllSessionResources() != null)
            owned.addAll(session.getAllSessionResources());
        // Referenced libraries often hold diagrams with the same default names.
        if (session.getReferencedSessionResources() != null)
            owned.removeAll(session.getReferencedSessionResources());
        List<org.eclipse.sirius.diagram.DDiagram> diagrams = new ArrayList<>();
        int unreadable = 0;
        for (Resource resource : owned) {
            var contents = resource.getAllContents();
            while (contents.hasNext()) {
                snapshot.budget();
                EObject next = contents.next();
                // Sirius keeps the name on the descriptor; do not descend into representations.
                if (next instanceof org.eclipse.sirius.viewpoint.DRepresentation) contents.prune();
                else if (next
                                instanceof
                                org.eclipse.sirius.viewpoint.DRepresentationDescriptor descriptor
                        && request.feature.equals(descriptor.getName())) {
                    if (descriptor.getRepresentation()
                            instanceof org.eclipse.sirius.diagram.DDiagram diagram)
                        diagrams.add(diagram);
                    else unreadable++;
                }
            }
        }
        if (unreadable > 0)
            throw unavailable(
                    "A diagram with this name could not be loaded; what it shows is unverified.");
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("diagrams", diagrams.size());
        if (diagrams.size() != 1)
            return failed(
                    request,
                    actual,
                    diagrams.isEmpty()
                            ? "No diagram has this name."
                            : "Several diagrams have this name.");
        Set<EObject> shown = Collections.newSetFromMap(new IdentityHashMap<>());
        var views = diagrams.get(0).eAllContents();
        while (views.hasNext()) {
            snapshot.budget();
            EObject view = views.next();
            if (view instanceof org.eclipse.sirius.viewpoint.DSemanticDecorator decorated
                    && decorated.getTarget() != null) shown.add(decorated.getTarget());
            // A component box targets its Part and lists the component among its semantic elements.
            if (view instanceof org.eclipse.sirius.diagram.DDiagramElement element)
                shown.addAll(element.getSemanticElements());
        }
        List<Object> missing = new ArrayList<>();
        for (Selector selector : request.sequence) {
            Node element = snapshot.one(selector);
            if (element == null) missing.add(selector.data);
            else if (!shown.contains(element.object)) missing.add(element.id);
        }
        actual.put("missing", missing);
        return verdict(
                request,
                missing.isEmpty(),
                actual,
                "Semantic targets of the named diagram's views compared.");
    }

    private static EReference reference(EClass type, String feature, boolean sequence) {
        EStructuralFeature value = type.getEStructuralFeature(feature);
        if (!(value instanceof EReference reference)
                || reference.isDerived()
                || reference.isTransient()
                || reference.isVolatile()
                || reference.isMany() != sequence
                || sequence && !reference.isOrdered()) {
            throw invalid(
                    "Structural checks require a stored "
                            + (sequence ? "ordered many-valued" : "single-valued")
                            + " native EReference; "
                            + type.getName()
                            + "."
                            + feature
                            + " is unsupported");
        }
        return reference;
    }

    private static boolean matches(EClass actual, List<EClass> types) {
        return types.stream().anyMatch(type -> type.isSuperTypeOf(actual));
    }

    private static EObject object(Object value) {
        if (!(value instanceof EObject object))
            throw unavailable("A native reference returned unsupported evidence.");
        return object;
    }

    private static Result verdict(Check request, boolean passed, Object actual, String note) {
        return new Result(
                request.kind, passed ? Status.PASSED : Status.FAILED, request.data, actual, note);
    }

    private static Result failed(Check request, Object actual, String note) {
        return verdict(request, false, actual, note);
    }

    private static List<Result> unavailable(List<Check> requests, String note) {
        return requests.stream()
                .map(
                        request ->
                                new Result(
                                        request.kind, Status.UNAVAILABLE, request.data, null, note))
                .toList();
    }

    private static final class Parse {
        private final Map<String, List<EClass>> registry = nativeTypes();
        private int bytes;

        Check check(Map<String, Object> input) {
            String kind = text(input.get("kind"), 80);
            if (!KINDS.contains(kind)) throw invalid("Unknown native structural check kind");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("kind", kind);
            Check result = new Check(kind, data);
            if ("relation_exists".equals(kind)) {
                keys(input, Set.of("kind", "type", "predicates", "owner"));
                result.types = type(input.get("type"));
                data.put("type", text(input.get("type"), 120));
                Map<String, Object> predicates = map(input.get("predicates"));
                if (predicates.isEmpty() || predicates.size() > 4)
                    throw invalid("A native relation check requires 1..4 predicates");
                Map<String, Object> described = new LinkedHashMap<>();
                result.predicates = new LinkedHashMap<>();
                for (var entry : predicates.entrySet()) {
                    String feature = feature(entry.getKey());
                    Selector selector = selector(entry.getValue(), 0);
                    result.predicates.put(feature, selector);
                    described.put(feature, selector.data);
                }
                data.put("predicates", described);
                if (input.containsKey("owner")) {
                    result.container = selector(input.get("owner"), 0);
                    data.put("owner", result.container.data);
                }
            } else if ("diagram_shows".equals(kind)) {
                keys(input, Set.of("kind", "diagram", "elements"));
                result.feature = text(input.get("diagram"), 400);
                data.put("diagram", result.feature);
                if (!(input.get("elements") instanceof List<?> elements)
                        || elements.isEmpty()
                        || elements.size() > MAX_ORDERED_TARGETS) {
                    throw invalid("A diagram check names 1..32 elements");
                }
                result.sequence = elements.stream().map(value -> selector(value, 0)).toList();
                data.put(
                        "elements",
                        result.sequence.stream().map(selector -> selector.data).toList());
            } else {
                keys(
                        input,
                        "contained_by".equals(kind)
                                ? Set.of("kind", "target", "container")
                                : Set.of("kind", "target", "feature", "expected"));
                result.target = selector(input.get("target"), 0);
                data.put("target", result.target.data);
                if ("contained_by".equals(kind)) {
                    result.container = selector(input.get("container"), 0);
                    data.put("container", result.container.data);
                } else {
                    result.feature = feature(input.get("feature"));
                    data.put("feature", result.feature);
                    if (!input.containsKey("expected"))
                        throw invalid("A reference check requires an expected value");
                    if ("reference_equals".equals(kind)) {
                        result.expected =
                                input.get("expected") == null
                                        ? null
                                        : selector(input.get("expected"), 0);
                        data.put("expected", result.expected == null ? null : result.expected.data);
                    } else {
                        if (!(input.get("expected") instanceof List<?> sequence)
                                || sequence.size() > MAX_ORDERED_TARGETS)
                            throw invalid("At most 32 ordered native targets are supported");
                        result.sequence =
                                sequence.stream().map(value -> selector(value, 0)).toList();
                        Set<Map<String, Object>> unique = new LinkedHashSet<>();
                        for (Selector selector : result.sequence)
                            if (!unique.add(selector.data))
                                throw invalid("Duplicate ordered native selectors are unsupported");
                        data.put(
                                "expected",
                                result.sequence.stream().map(selector -> selector.data).toList());
                    }
                }
            }
            // Native classifier metadata is already available for named selectors and
            // relation types. Reject impossible checks before a proposal can be reviewed
            // or committed; ID-only selectors still need their actual native instance.
            if ("relation_exists".equals(kind)) {
                for (EClass nativeType : result.types) {
                    for (String nativeFeature : result.predicates.keySet())
                        reference(nativeType, nativeFeature, false);
                }
            } else if (!"contained_by".equals(kind)
                    && !"diagram_shows".equals(kind)
                    && result.target.types != null) {
                for (EClass nativeType : result.target.types) {
                    reference(nativeType, result.feature, "reference_sequence_equals".equals(kind));
                }
            }
            return result;
        }

        Selector selector(Object value, int depth) {
            Map<String, Object> input = map(value);
            keys(input, Set.of("id", "type", "name", "layer", "container"));
            Map<String, Object> data = new LinkedHashMap<>();
            Selector result = new Selector(data);
            if (input.containsKey("id")) {
                if (input.size() != 1)
                    throw invalid("A native selector uses an exact ID or type and name");
                result.id = text(input.get("id"), 2400);
                if (!result.id.matches("[\\p{L}\\p{N}_.:@-]+"))
                    throw invalid("Invalid native identity selector");
                data.put("id", result.id);
            } else {
                result.types = type(input.get("type"));
                result.name = text(input.get("name"), 2400);
                boolean hasStoredLabel = false;
                for (EClass nativeType : result.types) {
                    for (String label :
                            List.of("name", "ReqIFLongName", "ReqIFName", "ReqIFIdentifier")) {
                        EStructuralFeature labelFeature = nativeType.getEStructuralFeature(label);
                        if (labelFeature instanceof EAttribute attribute
                                && !attribute.isMany()
                                && !attribute.isDerived()
                                && !attribute.isTransient()
                                && !attribute.isVolatile()) {
                            hasStoredLabel = true;
                            break;
                        }
                    }
                }
                if (!hasStoredLabel)
                    throw invalid(
                            "Native classifier "
                                    + input.get("type")
                                    + " has no stored name/ReqIF label; use an exact ID or a"
                                    + " relation_exists check");
                data.put("type", text(input.get("type"), 120));
                data.put("name", result.name);
                if (input.containsKey("layer")) {
                    result.layer = text(input.get("layer"), 8);
                    if (!LAYERS.contains(result.layer))
                        throw invalid("Unknown native architecture layer");
                    data.put("layer", result.layer);
                }
                if (input.containsKey("container")) {
                    if (depth >= 1)
                        throw invalid(
                                "Native selector containment is limited to two selector levels");
                    result.container = selector(input.get("container"), depth + 1);
                    data.put("container", result.container.data);
                }
            }
            return result;
        }

        List<EClass> type(Object value) {
            String name = text(value, 120);
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*") || !registry.containsKey(name))
                throw invalid("Unknown registered native classifier");
            return registry.get(name);
        }

        String feature(Object value) {
            String feature = text(value, 120);
            if (!feature.matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw invalid("Invalid native feature name");
            return feature;
        }

        String text(Object value, int limit) {
            if (!(value instanceof String text)
                    || text.isBlank()
                    || text.length() > limit
                    || text.indexOf('\0') >= 0)
                throw invalid("Native assertion text must be bounded and nonblank");
            bytes += text.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_INPUT_BYTES)
                throw invalid("Native structural assertion data exceeds its byte budget");
            return text;
        }
    }

    private static final class Check {
        final String kind;
        final Map<String, Object> data;
        Selector target, expected, container;
        String feature;
        List<Selector> sequence;
        List<EClass> types;
        Map<String, Selector> predicates;

        Check(String kind, Map<String, Object> data) {
            this.kind = kind;
            this.data = data;
        }
    }

    private static final class Selector {
        final Map<String, Object> data;
        String id, name, layer;
        List<EClass> types;
        Selector container;

        Selector(Map<String, Object> data) {
            this.data = data;
        }
    }

    private static final class Node {
        final EObject object;
        final Resource resource;
        final String id, name, layer;

        Node(EObject object, Resource resource) {
            this.object = object;
            this.resource = resource;
            EAttribute identity = object.eClass().getEIDAttribute();
            id =
                    identity == null
                            ? storedString(object, "id")
                            : storedString(object, identity.getName());
            if (id.length() > 2400)
                throw unavailable(
                        "A native identity exceeds the bounded evidence format; its ID was not"
                            + " shortened.");
            String label = "";
            for (String feature :
                    List.of("name", "ReqIFLongName", "ReqIFName", "ReqIFIdentifier")) {
                label = storedString(object, feature);
                if (!label.isEmpty()) break;
            }
            name = label;
            layer = layer(object);
        }

        Map<String, Object> describe() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            result.put("type", object.eClass().getName());
            result.put("name", clip(name, 240));
            result.put("layer", layer);
            return result;
        }
    }

    private record Entry(EObject object, Resource resource) {}

    private static final class Snapshot {
        final List<Node> nodes = new ArrayList<>();
        final Map<EObject, Node> byObject = new IdentityHashMap<>();
        final Map<String, List<Node>> byId = new LinkedHashMap<>();
        final Map<String, List<Node>> byName = new LinkedHashMap<>();
        final long started = System.nanoTime();
        boolean complete = true;
        String note = "";

        static Snapshot scan(List<Resource> resources, int limit) {
            Snapshot snapshot = new Snapshot();
            ArrayDeque<Entry> pending = new ArrayDeque<>();
            try {
                for (Resource resource : resources) {
                    if (pending.size() + resource.getContents().size() > MAX_SCAN_OBJECTS)
                        throw unavailable("The bounded native root scan was exceeded.");
                    for (EObject root : resource.getContents())
                        pending.add(new Entry(root, resource));
                }
                while (!pending.isEmpty()) {
                    snapshot.budget();
                    if (snapshot.nodes.size() >= limit)
                        throw unavailable(
                                "The bounded native scan did not finish; uniqueness and absence are"
                                    + " unverified.");
                    Entry entry = pending.removeLast();
                    EObject object = entry.object();
                    if (object.eIsProxy() || object.eResource() != entry.resource())
                        throw unavailable(
                                "Unresolved or foreign native containment prevents a complete"
                                    + " scan.");
                    if (!scanNamespace(object.eClass().getEPackage().getNsURI()))
                        throw unavailable(
                                "An unsupported semantic namespace prevents complete native"
                                    + " structural evidence.");
                    if (snapshot.byObject.containsKey(object))
                        throw unavailable("Repeated native containment prevents a complete scan.");
                    Node node = new Node(object, entry.resource());
                    snapshot.nodes.add(node);
                    snapshot.byObject.put(object, node);
                    if (!node.id.isEmpty())
                        snapshot.byId
                                .computeIfAbsent(node.id, ignored -> new ArrayList<>())
                                .add(node);
                    snapshot.byName
                            .computeIfAbsent(node.name, ignored -> new ArrayList<>())
                            .add(node);
                    for (EReference feature : object.eClass().getEAllContainments()) {
                        if (feature.isDerived() || feature.isTransient() || feature.isVolatile())
                            continue;
                        Object value = object.eGet(feature, false);
                        if (feature.isMany()) {
                            if (!(value instanceof InternalEList<?> children))
                                throw unavailable(
                                        "Native containment cannot be read without resolving"
                                            + " proxies.");
                            if (pending.size() + children.size() > MAX_SCAN_OBJECTS)
                                throw unavailable(
                                        "The bounded native containment scan was exceeded.");
                            for (int index = 0; index < children.size(); index++)
                                pending.add(
                                        new Entry(
                                                object(children.basicGet(index)),
                                                entry.resource()));
                        } else if (value != null)
                            pending.add(new Entry(object(value), entry.resource()));
                    }
                }
                if (snapshot.nodes.isEmpty())
                    throw unavailable(
                            "No native semantic objects were read; structural evidence is"
                                + " unavailable.");
            } catch (EvidenceUnavailable incomplete) {
                snapshot.complete = false;
                snapshot.note = incomplete.getMessage();
            }
            return snapshot;
        }

        void budget() {
            if (Thread.currentThread().isInterrupted()
                    || System.nanoTime() - started > MAX_SCAN_NANOS)
                throw unavailable(
                        "The bounded native read did not finish; uniqueness and absence are"
                            + " unverified.");
        }

        Node one(Selector selector) {
            budget();
            Node container = selector.container == null ? null : one(selector.container);
            if (selector.container != null && container == null) return null;
            List<Node> matches =
                    selector.id != null
                            ? byId.getOrDefault(selector.id, List.of())
                            : byName.getOrDefault(selector.name, List.of()).stream()
                                    .filter(
                                            node ->
                                                    matches(node.object.eClass(), selector.types)
                                                            && (selector.layer == null
                                                                    || selector.layer.equals(
                                                                            node.layer))
                                                            && (container == null
                                                                    || container(node.object)
                                                                            == container.object))
                                    .toList();
            budget();
            if (matches.size() > 1)
                throw unavailable(
                        "The native selector is ambiguous; use an exact ID or a unique immediate"
                            + " container.");
            return matches.isEmpty() ? null : matches.get(0);
        }

        Node scoped(EObject object) {
            budget();
            Node value = byObject.get(object);
            if (object.eIsProxy() || value == null || object.eResource() != value.resource)
                throw unavailable(
                        "A reference target is unresolved or outside the captured semantic"
                            + " resources.");
            return value;
        }
    }

    private static String storedString(EObject object, String name) {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(name);
        if (!(feature instanceof EAttribute)
                || feature.isMany()
                || feature.isDerived()
                || feature.isTransient()
                || feature.isVolatile()) return "";
        Object value = object.eGet(feature, false);
        return value instanceof String text ? text : "";
    }

    private static String layer(EObject object) {
        for (EObject owner = object; owner != null; owner = container(owner))
            if (owner instanceof BlockArchitecture) {
                return switch (owner.eClass().getName()) {
                    case "OperationalAnalysis" -> "OA";
                    case "SystemAnalysis" -> "SA";
                    case "LogicalArchitecture" -> "LA";
                    case "PhysicalArchitecture" -> "PA";
                    case "EPBSArchitecture" -> "EPBS";
                    default -> "";
                };
            }
        return "";
    }

    private static EObject container(EObject object) {
        return object instanceof InternalEObject internal
                ? internal.eInternalContainer()
                : object.eContainer();
    }

    private static Map<String, List<EClass>> nativeTypes() {
        Map<String, List<EClass>> result = new LinkedHashMap<>();
        Set<EPackage> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object value : new ArrayList<>(EPackage.Registry.INSTANCE.values()))
            if (value instanceof EPackage model) types(model, result, seen);
        return result;
    }

    private static void types(
            EPackage model, Map<String, List<EClass>> result, Set<EPackage> seen) {
        if (!seen.add(model)) return;
        String namespace = model.getNsURI();
        if (nativeNamespace(namespace)) {
            for (var classifier : model.getEClassifiers())
                if (classifier instanceof EClass type)
                    result.computeIfAbsent(type.getName(), ignored -> new ArrayList<>()).add(type);
        }
        for (EPackage child : model.getESubpackages()) types(child, result, seen);
    }

    private static boolean nativeNamespace(String namespace) {
        return namespace != null
                && (namespace.startsWith("http://www.polarsys.org/capella/core/")
                        || namespace.startsWith("http://www.polarsys.org/capella/common/")
                        || namespace.equals("http://www.polarsys.org/kitalpha/requirements")
                        || namespace.equals("http://www.polarsys.org/capella/requirements"));
    }

    private static boolean scanNamespace(String namespace) {
        // Sirius includes the standard viewpoint .afm resource among a Capella
        // session's semantic resources. Inspect its stored contents too, without
        // making metadata classifiers available as native model selector types.
        return nativeNamespace(namespace)
                || "http://www.polarsys.org/kitalpha/ad/metadata/1.0.0".equals(namespace);
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> input)
                || input.size() > 8
                || input.keySet().stream().anyMatch(key -> !(key instanceof String)))
            throw invalid("Native assertion data must be a bounded object");
        Map<String, Object> result = new LinkedHashMap<>();
        input.forEach((key, entry) -> result.put((String) key, entry));
        return result;
    }

    private static void keys(Map<String, Object> input, Set<String> allowed) {
        if (!allowed.containsAll(input.keySet()))
            throw invalid("Unknown native structural assertion fields");
    }

    private static Object detached(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean) return value;
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> result = new LinkedHashMap<>();
            source.forEach((key, item) -> result.put((String) key, detached(item)));
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> source)
            return source.stream().map(NativeStructuralAssertions::detached).toList();
        throw invalid("Only detached native assertion evidence is supported");
    }

    private static int bytes(Object value) {
        if (value == null) return 4;
        if (value instanceof String text) return text.getBytes(StandardCharsets.UTF_8).length;
        if (value instanceof Map<?, ?> map)
            return map.entrySet().stream()
                    .mapToInt(entry -> bytes(entry.getKey()) + bytes(entry.getValue()))
                    .sum();
        if (value instanceof List<?> list)
            return list.stream().mapToInt(NativeStructuralAssertions::bytes).sum();
        return 24;
    }

    private static String clip(String value, int limit) {
        if (value.length() <= limit) return value;
        int end = limit - 1;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end) + "…";
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private static EvidenceUnavailable unavailable(String message) {
        return new EvidenceUnavailable(message);
    }

    private static final class EvidenceUnavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        EvidenceUnavailable(String message) {
            super(message);
        }
    }
}
