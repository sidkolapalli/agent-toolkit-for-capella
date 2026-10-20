package org.polarsys.capella.ai.agent;

import org.eclipse.emf.common.util.Enumerator;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.xmi.XMLResource;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.session.SessionSafeguards;
import org.polarsys.capella.core.data.cs.BlockArchitecture;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Data-only assertions over the captured session. Never evaluates a supplied predicate. */
public final class ModelPostconditions {
    public static final int MAX_CONDITIONS = 12;
    // The time budget is the real guard; the object cap only bounds memory (raised after a
    // 312,000-object stress test).
    public static final int MAX_SCAN_OBJECTS = 2_000_000;
    private static final long MAX_SCAN_NANOS = 10_000_000_000L;
    private static final Set<String> LAYERS = Set.of("OA", "SA", "LA", "PA", "EPBS");
    private static final String SCOPE =
            "Independently checked declared native semantic model state only. This does not"
                + " establish goal completeness, requirements verification, safety or compliance.";

    private ModelPostconditions() {}

    public enum Status {
        PASSED,
        FAILED,
        UNAVAILABLE
    }

    /**
     * All values are detached, bounded evidence; no EObject or mutable model collection escapes.
     */
    public record Result(
            String stepId,
            String kind,
            Map<String, Object> target,
            Status status,
            Object expected,
            Object actual,
            String note) {
        public Result {
            target = Collections.unmodifiableMap(new LinkedHashMap<>(target));
            expected = boundedValue(expected);
            actual = boundedValue(actual);
            note = Text.clip(note, 400);
        }

        public Map<String, Object> feedback() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("stepId", stepId);
            value.put("kind", kind);
            value.put("target", boundedValue(target));
            value.put("status", status.name().toLowerCase(Locale.ROOT));
            value.put("expected", expected);
            value.put("actual", actual);
            value.put("note", note);
            return Collections.unmodifiableMap(value);
        }
    }

    public static final class Report {
        private final List<Result> results;
        private final String note;

        private Report(List<Result> results, String note) {
            this.results = List.copyOf(results);
            this.note = Text.clip(note, 400);
        }

        public List<Result> results() {
            return results;
        }

        /** A step is verified only when every assertion for that step passed. */
        public Set<String> completedStepIds() {
            Map<String, Boolean> groups = new LinkedHashMap<>();
            for (Result result : results)
                if (!result.stepId().isEmpty()) {
                    groups.merge(
                            result.stepId(),
                            result.status() == Status.PASSED,
                            (left, right) -> left && right);
                }
            Set<String> passed = new LinkedHashSet<>();
            groups.forEach(
                    (id, all) -> {
                        if (all) passed.add(id);
                    });
            return Collections.unmodifiableSet(passed);
        }

        public boolean allPassed() {
            return !results.isEmpty()
                    && results.stream().allMatch(result -> result.status() == Status.PASSED);
        }

        public Map<String, Object> feedback() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("allPassed", allPassed());
            value.put("completedStepIds", List.copyOf(completedStepIds()));
            value.put("conditions", results.stream().map(Result::feedback).toList());
            value.put("note", note);
            value.put("scope", SCOPE);
            return Collections.unmodifiableMap(value);
        }

        public String markdownSummary() {
            long passed =
                    results.stream().filter(result -> result.status() == Status.PASSED).count();
            StringBuilder text =
                    new StringBuilder("Declared postconditions independently checked: ")
                            .append(passed)
                            .append("/")
                            .append(results.size())
                            .append(" passed.");
            if (!note.isEmpty()) text.append(" ").append(markdown(note));
            for (Result result : results) {
                text.append("\n- ")
                        .append(
                                switch (result.status()) {
                                    case PASSED -> "Passed";
                                    case FAILED -> "Failed";
                                    case UNAVAILABLE -> "Unavailable";
                                })
                        .append(" · ");
                if ("native_structure".equals(result.kind())) {
                    // Already escaped; say what was declared and, on failure, what the model holds.
                    text.append(
                                    org.polarsys.capella.ai.presentation.NativeCheckDescription
                                            .describe(result.target().get("check")))
                            .append("; ")
                            .append(markdown(result.note()));
                    if (result.status() != Status.PASSED)
                        text.append(markdown(observed(result.actual())));
                    continue;
                }
                text.append(markdown(targetDescription(result)));
                if ("attribute_equals".equals(result.kind())) {
                    text.append(": ")
                            .append(markdown(String.valueOf(result.target().get("attribute"))))
                            .append(" equals ")
                            .append(markdown(displayValue(result.expected())))
                            .append("; actual ")
                            .append(markdown(displayValue(result.actual())));
                } else {
                    text.append("element_exists".equals(result.kind()) ? " exists" : " is absent");
                    if (result.actual() instanceof Map<?, ?>) text.append(" (1 match)");
                    else if (result.actual() instanceof Number count)
                        text.append(" (").append(count).append(" matches observed)");
                    else if (result.actual() != null)
                        text.append("; actual ").append(markdown(displayValue(result.actual())));
                }
                if (!result.note().isEmpty()) text.append(". ").append(markdown(result.note()));
            }
            return text.append("\n").append(SCOPE).toString();
        }
    }

    /**
     * Call only after observed committed changes; this does not itself prove execution or saving.
     */
    public static Report check(Session session, List<ArcadiaAgent.Postcondition> conditions) {
        return check(session, conditions, MAX_SCAN_OBJECTS);
    }

    // A smaller trusted traversal budget permits deterministic tests of incomplete evidence.
    static Report check(
            Session session, List<ArcadiaAgent.Postcondition> conditions, int scanLimit) {
        if (conditions == null
                || conditions.size() > MAX_CONDITIONS
                || conditions.stream().anyMatch(value -> value == null)) {
            throw new IllegalArgumentException(
                    "Postconditions must contain at most 12 non-null assertions");
        }
        if (scanLimit < 1 || scanLimit > MAX_SCAN_OBJECTS)
            throw new IllegalArgumentException("Invalid read traversal budget");
        List<ArcadiaAgent.Postcondition> declarations = List.copyOf(conditions);
        if (declarations.isEmpty())
            return new Report(
                    List.of(),
                    "No native postconditions were declared; edit steps remain unverified.");
        AtomicReference<Report> report = new AtomicReference<>();
        try {
            SessionSafeguards.runOnUiThread(
                    () -> {
                        if (session == null
                                || !session.isOpen()
                                || session.getTransactionalEditingDomain() == null) {
                            report.set(
                                    unavailable(
                                            declarations,
                                            "The captured Capella model is closed or has no editing"
                                                    + " domain."));
                            return;
                        }
                        TransactionalEditingDomain domain = session.getTransactionalEditingDomain();
                        try {
                            domain.runExclusive(
                                    () -> {
                                        if (!session.isOpen()
                                                || Thread.currentThread().isInterrupted()) {
                                            report.set(
                                                    unavailable(
                                                            declarations,
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
                                            report.set(
                                                    unavailable(
                                                            declarations,
                                                            "Complete semantic resources in the"
                                                                    + " captured editing domain are"
                                                                    + " required."));
                                            return;
                                        }
                                        report.set(
                                                evaluateAll(
                                                        session,
                                                        resources,
                                                        declarations,
                                                        scanLimit));
                                    });
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            report.set(
                                    unavailable(declarations, "The native read was interrupted."));
                        }
                    });
        } catch (RuntimeException failure) {
            // Do not expose an exception's arbitrary model/file/provider contents as feedback.
            report.set(
                    unavailable(
                            declarations,
                            "The native read could not complete. No postcondition was verified."));
        }
        return report.get() == null
                ? unavailable(declarations, "The native read returned no evidence.")
                : report.get();
    }

    @SuppressWarnings("unchecked")
    private static Report evaluateAll(
            Session session,
            List<Resource> resources,
            List<ArcadiaAgent.Postcondition> declarations,
            int scanLimit) {
        List<ArcadiaAgent.Postcondition> primitive =
                declarations.stream()
                        .filter(condition -> !"native_structure".equals(condition.kind()))
                        .toList();
        List<Map<String, Object>> structural =
                declarations.stream()
                        .filter(condition -> "native_structure".equals(condition.kind()))
                        .map(condition -> (Map<String, Object>) condition.arguments().get("check"))
                        .toList();
        // Both reads run on SWT within the same exclusive native read, so an edit
        // cannot slip between primitive and relationship completion evidence.
        List<Result> ordinaryResults =
                primitive.isEmpty()
                        ? List.of()
                        : evaluate(resources, primitive, scanLimit).results();
        List<NativeStructuralAssertions.Result> structuralResults =
                structural.isEmpty()
                        ? List.of()
                        : NativeStructuralAssertions.check(session, structural, scanLimit);
        List<Result> results = new ArrayList<>();
        int ordinaryIndex = 0, structuralIndex = 0;
        for (ArcadiaAgent.Postcondition declaration : declarations) {
            if (!"native_structure".equals(declaration.kind()))
                results.add(ordinaryResults.get(ordinaryIndex++));
            else {
                NativeStructuralAssertions.Result result = structuralResults.get(structuralIndex++);
                results.add(
                        new Result(
                                declaration.stepId(),
                                declaration.kind(),
                                declaration.arguments(),
                                Status.valueOf(result.status().name()),
                                result.expected(),
                                result.actual(),
                                result.note()));
            }
        }
        return new Report(results, "");
    }

    private static Report evaluate(
            List<Resource> resources, List<ArcadiaAgent.Postcondition> declarations, int limit) {
        List<Query> queries = declarations.stream().map(Query::new).toList();
        boolean needsScan = false;
        for (Query query : queries) {
            if (!query.valid) continue;
            if (query.id != null
                    && resources.stream().allMatch(resource -> resource instanceof XMLResource)) {
                for (Resource resource : resources) {
                    EObject candidate = resource.getEObject(query.id);
                    if (candidate == null) continue;
                    if (candidate.eIsProxy()
                            || candidate.eResource() != resource
                            || !query.id.equals(storedString(candidate, "id"))) {
                        query.complete = false;
                        query.note =
                                "Native ID lookup returned stale, foreign or unresolved evidence.";
                        break;
                    }
                    query.add(candidate);
                }
            } else {
                query.needsScan = true;
                needsScan = true;
            }
        }
        boolean complete = true;
        String incompleteReason = "";
        if (needsScan) {
            int scanned = 0;
            long started = System.nanoTime();
            outer:
            for (Resource resource : resources) {
                var iterator = EcoreUtil.<EObject>getAllContents(resource, false);
                while (iterator.hasNext()) {
                    if (scanned >= limit
                            || System.nanoTime() - started >= MAX_SCAN_NANOS
                            || Thread.currentThread().isInterrupted()) {
                        complete = false;
                        incompleteReason =
                                "The bounded native search did not finish; absence and uniqueness"
                                        + " are unverified.";
                        break outer;
                    }
                    EObject candidate = iterator.next();
                    scanned++;
                    if (candidate.eIsProxy() || candidate.eResource() != resource) {
                        iterator.prune();
                        complete = false;
                        incompleteReason =
                                "Unresolved or foreign semantic containment prevents a complete"
                                        + " search.";
                        continue;
                    }
                    for (Query query : queries)
                        if (query.valid && query.needsScan && query.matches(candidate))
                            query.add(candidate);
                }
            }
        }
        List<Result> results = new ArrayList<>();
        for (Query query : queries) {
            if (query.needsScan && !complete) {
                query.complete = false;
                query.note = incompleteReason;
            }
            results.add(query.result());
        }
        return new Report(
                results,
                "Evidence is scoped to the captured session; execution, saving and engineering"
                        + " intent are checked separately.");
    }

    private static final class Query {
        private final ArcadiaAgent.Postcondition declaration;
        private final Map<String, Object> target = new LinkedHashMap<>();
        private String id, type, name, layer, attribute;
        private boolean valid = true, complete = true, needsScan;
        private int matches;
        private EObject first;
        private String note = "";

        Query(ArcadiaAgent.Postcondition declaration) {
            this.declaration = declaration;
            Map<String, Object> args = declaration.arguments();
            try {
                if (!Set.of("id", "type", "name", "layer", "attribute", "expected")
                        .containsAll(args.keySet())) throw new IllegalArgumentException();
                id = text(args, "id", false);
                type = text(args, "type", false);
                name = text(args, "name", false);
                layer = text(args, "layer", false);
                attribute = text(args, "attribute", false);
                if (id != null) {
                    if (type != null
                            || name != null
                            || layer != null
                            || !id.matches("[\\p{L}\\p{N}_.:@-]+"))
                        throw new IllegalArgumentException();
                    target.put("id", id);
                } else {
                    if (type == null
                            || name == null
                            || !type.matches("[A-Za-z_][A-Za-z0-9_]{0,119}")
                            || !supportedType(type)) throw new IllegalArgumentException();
                    target.put("type", type);
                    target.put("name", name);
                }
                if (layer != null) {
                    if (!LAYERS.contains(layer)) throw new IllegalArgumentException();
                    target.put("layer", layer);
                }
                if ("attribute_equals".equals(declaration.kind())) {
                    if (attribute == null
                            || !attribute.matches("[A-Za-z_][A-Za-z0-9_]{0,119}")
                            || sensitive(attribute)
                            || !args.containsKey("expected")
                            || !primitive(args.get("expected")))
                        throw new IllegalArgumentException();
                    target.put("attribute", attribute);
                } else if (attribute != null || args.containsKey("expected"))
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) {
                valid = false;
                note =
                        "The declared native selector or stored attribute is invalid or"
                                + " unsupported.";
            }
        }

        boolean matches(EObject candidate) {
            if (id != null) return id.equals(storedString(candidate, "id"));
            return matchesType(candidate, type)
                    && name.equals(displayName(candidate))
                    && (layer == null || layer.equals(layerOf(candidate)));
        }

        void add(EObject candidate) {
            matches++;
            if (first == null) first = candidate;
        }

        Result result() {
            Object expected =
                    "attribute_equals".equals(declaration.kind())
                            ? declaration.arguments().get("expected")
                            : "element_exists".equals(declaration.kind())
                                    ? "one unique native element"
                                    : "no native element";
            if (!valid) return result(Status.UNAVAILABLE, expected, null, note);
            if (matches > 1)
                return result(
                        Status.FAILED,
                        expected,
                        "at least two matching native elements",
                        "The target is ambiguous; use its native UUID.");
            if (!complete) return result(Status.UNAVAILABLE, expected, matches, note);
            if (first != null && layer != null && !layer.equals(layerOf(first))) {
                return result(
                        Status.FAILED,
                        expected,
                        descriptor(first),
                        "The native ID exists in a different architecture layer.");
            }
            if ("element_absent".equals(declaration.kind()))
                return result(
                        matches == 0 ? Status.PASSED : Status.FAILED,
                        expected,
                        first == null ? 0 : descriptor(first),
                        matches == 0
                                ? "Complete search found no matching element in this session."
                                : "The target still exists.");
            if (first == null)
                return result(
                        Status.FAILED,
                        expected,
                        0,
                        "No matching native element exists in this session.");
            if ("element_exists".equals(declaration.kind()))
                return result(
                        Status.PASSED,
                        expected,
                        descriptor(first),
                        "Unique native target observed.");
            EStructuralFeature feature = first.eClass().getEStructuralFeature(attribute);
            if (!(feature instanceof EAttribute)
                    || feature.isDerived()
                    || feature.isTransient()
                    || feature.isVolatile()
                    || feature.isMany()) {
                return result(
                        Status.UNAVAILABLE,
                        expected,
                        null,
                        "Only a single stored EAttribute can be compared; references and derived"
                                + " attributes are unsupported.");
            }
            Object raw = first.eGet(feature, false);
            Object actual = raw instanceof Enumerator literal ? literal.getLiteral() : raw;
            if (!primitive(actual))
                return result(
                        Status.UNAVAILABLE,
                        expected,
                        null,
                        "The native attribute datatype is not a supported JSON primitive.");
            return result(
                    equal(expected, actual) ? Status.PASSED : Status.FAILED,
                    expected,
                    actual,
                    "Stored native attribute compared without resolving references.");
        }

        private Result result(Status status, Object expected, Object actual, String detail) {
            return new Result(
                    declaration.stepId(),
                    declaration.kind(),
                    target,
                    status,
                    expected,
                    actual,
                    detail);
        }
    }

    private static boolean matchesType(EObject element, String requested) {
        String canonical = canonicalType(requested);
        if (!semanticNamespace(element.eClass().getEPackage().getNsURI())) return false;
        if (!canonical.equals(element.eClass().getName())
                && element.eClass().getEAllSuperTypes().stream()
                        .noneMatch(
                                parent ->
                                        canonical.equals(parent.getName())
                                                && semanticNamespace(
                                                        parent.getEPackage().getNsURI())))
            return false;
        return !requested.endsWith("Actor") || Boolean.TRUE.equals(storedValue(element, "actor"));
    }

    private static String canonicalType(String requested) {
        return switch (requested) {
            case "OperationalActor", "OperationalEntity" -> "Entity";
            case "SystemActor" -> "SystemComponent";
            case "LogicalActor" -> "LogicalComponent";
            case "PhysicalActor" -> "PhysicalComponent";
            default -> requested;
        };
    }

    private static boolean supportedType(String requested) {
        String canonical = canonicalType(requested);
        // Read only registered native semantic classifiers. Sirius/GMF views and
        // unknown type names cannot establish absence from semantic resources.
        for (Object key : new ArrayList<>(EPackage.Registry.INSTANCE.keySet())) {
            if (!(key instanceof String namespace) || !semanticNamespace(namespace)) continue;
            EPackage model = EPackage.Registry.INSTANCE.getEPackage(namespace);
            if (model != null && model.getEClassifier(canonical) instanceof EClass) return true;
        }
        return false;
    }

    private static boolean semanticNamespace(String namespace) {
        return namespace != null
                && (namespace.startsWith("http://www.polarsys.org/capella/core/")
                        || namespace.equals("http://www.polarsys.org/kitalpha/requirements")
                        || namespace.equals("http://www.polarsys.org/capella/requirements"));
    }

    private static String layerOf(EObject element) {
        for (int depth = 0;
                element != null && depth < 128;
                depth++, element = element.eContainer()) {
            if (element instanceof BlockArchitecture)
                return switch (element.eClass().getName()) {
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

    private static Object storedValue(EObject owner, String name) {
        EStructuralFeature feature = owner.eClass().getEStructuralFeature(name);
        return feature instanceof EAttribute
                        && !feature.isDerived()
                        && !feature.isTransient()
                        && !feature.isVolatile()
                        && !feature.isMany()
                ? owner.eGet(feature, false)
                : null;
    }

    private static String storedString(EObject owner, String name) {
        Object value = storedValue(owner, name);
        return value instanceof String text ? text : "";
    }

    private static String displayName(EObject owner) {
        for (String feature : List.of("name", "ReqIFLongName", "ReqIFName", "ReqIFIdentifier")) {
            String name = storedString(owner, feature);
            if (!name.isEmpty()) return name;
        }
        return "";
    }

    private static Map<String, Object> descriptor(EObject owner) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", storedString(owner, "id"));
        value.put("type", owner.eClass().getName());
        value.put("name", displayName(owner));
        value.put("layer", layerOf(owner));
        return value;
    }

    private static String text(Map<String, Object> arguments, String key, boolean required) {
        if (!arguments.containsKey(key) && !required) return null;
        if (!(arguments.get(key) instanceof String text)
                || text.isBlank()
                || text.length() > 2400
                || text.indexOf('\0') >= 0) throw new IllegalArgumentException();
        return text;
    }

    private static boolean primitive(Object value) {
        return value == null
                || value instanceof String
                || value instanceof Boolean
                || value instanceof Number number && Double.isFinite(number.doubleValue());
    }

    private static boolean equal(Object expected, Object actual) {
        if (expected instanceof Number left && actual instanceof Number right) {
            try {
                return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString()))
                        == 0;
            } catch (NumberFormatException unsupported) {
                return false;
            }
        }
        return java.util.Objects.equals(expected, actual);
    }

    private static Report unavailable(List<ArcadiaAgent.Postcondition> conditions, String reason) {
        return new Report(
                conditions.stream()
                        .map(
                                condition ->
                                        new Result(
                                                condition.stepId(),
                                                condition.kind(),
                                                "native_structure".equals(condition.kind())
                                                        ? condition.arguments()
                                                        : new Query(condition).target,
                                                Status.UNAVAILABLE,
                                                condition.arguments().get("expected"),
                                                null,
                                                reason))
                        .toList(),
                reason);
    }

    private static boolean sensitive(String name) {
        return name.toLowerCase(Locale.ROOT)
                .matches(".*(password|secret|credential|api.?key|access.?token).*");
    }

    private static Object boundedValue(Object value) {
        if (value instanceof String text) return Text.clip(text, 320);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> bounded = new LinkedHashMap<>();
            map.forEach((key, entry) -> bounded.put((String) key, boundedValue(entry)));
            return Collections.unmodifiableMap(bounded);
        }
        return value;
    }

    /** The decisive facts of a failed structural check, bounded. */
    private static String observed(Object actual) {
        if (!(actual instanceof Map<?, ?> facts)) return "";
        StringBuilder text = new StringBuilder();
        for (String key :
                List.of("matchCount", "diagrams", "missing", "count", "value", "container")) {
            if (!facts.containsKey(key)) continue;
            Object value = facts.get(key);
            // Plain ifs: Tycho's ECJ hangs on pattern variables inside nested ternaries.
            String shown = value == null ? "none" : String.valueOf(value);
            if (value instanceof Map<?, ?> element) {
                Object name = element.get("name");
                shown =
                        name instanceof String && !((String) name).isEmpty()
                                ? element.get("type") + " " + name
                                : "element " + element.get("id");
            }
            text.append(text.length() == 0 ? " Observed " : "; ")
                    .append(key)
                    .append(": ")
                    .append(shown);
        }
        return Text.clip(text.toString(), 400);
    }

    private static String targetDescription(Result result) {
        if ("native_structure".equals(result.kind())) {
            Object check = result.target().get("check");
            return "Native structural check "
                    + (check instanceof Map<?, ?> data ? data.get("kind") : "unavailable");
        }
        Map<String, Object> target = result.target();
        if (target.containsKey("type")) {
            return target.get("type")
                    + " “"
                    + Text.clip(String.valueOf(target.get("name")), 320)
                    + "”"
                    + (target.containsKey("layer") ? " in " + target.get("layer") : "");
        }
        if (result.actual() instanceof Map<?, ?> actual
                && actual.get("type") instanceof String type) {
            return type
                    + " “"
                    + actual.get("name")
                    + "”"
                    + ("".equals(actual.get("layer")) ? "" : " in " + actual.get("layer"));
        }
        return "Native element UUID “"
                + Text.clip(String.valueOf(target.getOrDefault("id", "unavailable")), 320)
                + "”";
    }

    private static String displayValue(Object value) {
        if (value == null) return "null";
        return value instanceof String text ? "“" + text + "”" : String.valueOf(value);
    }

    private static String markdown(String value) {
        return value.replaceAll("[\\r\\n\\t\\p{Cntrl}]", " ")
                .replace("\\", "\\\\")
                .replace("`", "\\`")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
