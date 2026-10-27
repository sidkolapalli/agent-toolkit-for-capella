package com.sidkolapalli.capella.ai.agent;

import org.eclipse.emf.common.util.Enumerator;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.ECrossReferenceAdapter;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.emf.transaction.TransactionalEditingDomainEvent;
import org.eclipse.emf.transaction.TransactionalEditingDomainListenerImpl;
import org.eclipse.emf.transaction.util.TransactionUtil;
import org.eclipse.sirius.business.api.session.Session;
import org.eclipse.swt.widgets.Display;
import com.sidkolapalli.capella.ai.model.ArchitectureLocator;
import com.sidkolapalli.capella.ai.scripting.ApiCatalog;
import com.sidkolapalli.capella.ai.scripting.CapellaAPI;
import com.sidkolapalli.capella.ai.session.SessionSafeguards;
import org.polarsys.capella.core.data.capellamodeller.Project;
import org.polarsys.capella.core.data.cs.BlockArchitecture;
import org.polarsys.capella.core.data.fa.AbstractFunction;
import org.polarsys.capella.core.data.fa.ComponentFunctionalAllocation;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Trusted, read-only model tools. The caller supplies data, never code, paths to workspace files,
 * model objects, AQL, or reflective method names. Every model read uses the fixed session's editing
 * domain on SWT in a read transaction.
 */
public final class ArcadiaModelTools {
    public static final int MAX_OUTPUT_CHARS = 32000;
    private static final int SCAN_OBJECTS = 2500;
    private static final long SCAN_NANOS = 75_000_000L;
    private static final int SEARCH_OBJECTS = 250000;
    private static final long SEARCH_NANOS = 2_000_000_000L;
    private static final int SEARCH_OUTPUT_CHARS = 24000;
    private static final long CURSOR_LIFETIME_NANOS = 300_000_000_000L;
    private static final List<String> LAYERS = List.of("OA", "SA", "LA", "PA", "EPBS");
    private static final Set<String> TOOLS =
            Set.of(
                    "model_summary",
                    "model_search",
                    "model_inspect",
                    "model_relationships",
                    "model_validate",
                    "native_validation",
                    "capella_docs",
                    "capella_capabilities");
    private static final Set<String> DOCS =
            Set.of(
                    "SKILL.md",
                    "api/SKILL.md",
                    "api/create.md",
                    "api/find.md",
                    "api/modify.md",
                    "api/link.md",
                    "api/describe.md",
                    "api/validate.md",
                    "api/arcadia.md",
                    "api/diagram.md",
                    "api/diagram-layout.md",
                    "api/style.md",
                    "api/ops.md",
                    "metamodel/SKILL.md",
                    "metamodel/oa/SKILL.md",
                    "metamodel/sa/SKILL.md",
                    "metamodel/la/SKILL.md",
                    "metamodel/pa/SKILL.md",
                    "metamodel/epbs/SKILL.md",
                    "skills/requirements/SKILL.md",
                    "skills/state-machine/SKILL.md",
                    "skills/interface/SKILL.md",
                    "skills/diagram/SKILL.md",
                    "skills/arcadia-workflow/SKILL.md");

    private final Session session;
    private final IntFunction<Map<String, Object>> nativeValidator;
    private final LongSupplier validationClock;
    private final int searchObjects;
    private final long searchNanos;
    private final LongSupplier searchClock;
    private final Runnable afterSearchSlice;
    // Accessed only inside the native read context. Cursors retain at most six
    // lazy traversals, expire, and are invalidated after a command/Undo/Redo.
    private final Map<String, Scan> cursors = new LinkedHashMap<>();
    private final Map<String, ValidationSnapshot> validationCursors = new LinkedHashMap<>();

    public ArcadiaModelTools(Session session) {
        this(session, maximum -> new CapellaAPI(session).validateNative(maximum), System::nanoTime);
    }

    ArcadiaModelTools(
            Session session,
            IntFunction<Map<String, Object>> nativeValidator,
            LongSupplier validationClock) {
        this(
                session,
                nativeValidator,
                validationClock,
                SEARCH_OBJECTS,
                SEARCH_NANOS,
                System::nanoTime,
                () -> {});
    }

    /**
     * Smaller limits and a clock/slice observer make bounded native scheduling deterministic in
     * tests.
     */
    ArcadiaModelTools(
            Session session,
            IntFunction<Map<String, Object>> nativeValidator,
            LongSupplier validationClock,
            int searchObjects,
            long searchNanos,
            LongSupplier searchClock,
            Runnable afterSearchSlice) {
        if (searchObjects < 1
                || searchObjects > SEARCH_OBJECTS
                || searchNanos < 1
                || searchNanos > SEARCH_NANOS) {
            throw new IllegalArgumentException(
                    "Search limits must stay within the native logical-call bounds");
        }
        this.session = session;
        this.nativeValidator = java.util.Objects.requireNonNull(nativeValidator);
        this.validationClock = java.util.Objects.requireNonNull(validationClock);
        this.searchObjects = searchObjects;
        this.searchNanos = searchNanos;
        this.searchClock = java.util.Objects.requireNonNull(searchClock);
        this.afterSearchSlice = java.util.Objects.requireNonNull(afterSearchSlice);
    }

    /** Trusted argument diagnostics; native/service exception messages are never tool feedback. */
    public static final class ArgumentException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        private ArgumentException(String message) {
            super(message.length() <= 512 ? message : message.substring(0, 512));
        }
    }

    public static Set<String> toolNames() {
        return TOOLS;
    }

    public static Set<String> documentationPaths() {
        Set<String> paths = new java.util.LinkedHashSet<>(DOCS);
        paths.addAll(CapellaHelp.paths());
        return paths;
    }

    /** Returns a bounded JSON envelope. Validation findings are successful reads. */
    public String execute(String name, Map<String, Object> arguments) throws Exception {
        if (name == null || !TOOLS.contains(name)) throw new ArgumentException("Unknown read tool");
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        Map<String, Object> result =
                switch (name) {
                    case "capella_docs" -> docs(args);
                    case "capella_capabilities" -> capabilities(args);
                    case "model_summary" -> {
                        keys(args);
                        yield read(this::summary);
                    }
                    case "model_search" -> {
                        keys(args, "query", "type", "layer", "limit", "cursor");
                        String query = optionalString(args, "query", 160),
                                type = optionalString(args, "type", 80);
                        if (query == null && type == null)
                            throw new ArgumentException("Search requires query or type");
                        String layer = layer(args), cursor = optionalString(args, "cursor", 80);
                        int limit = integer(args, "limit", 20, 50);
                        yield search(query, type, layer, limit, cursor);
                    }
                    case "model_inspect" -> {
                        keys(args, "id", "limit");
                        String id = id(args, "id");
                        int limit = integer(args, "limit", 15, 30);
                        yield read(() -> inspect(resolve(id), limit));
                    }
                    case "model_relationships" -> {
                        keys(args, "id", "limit", "cursor");
                        String id = id(args, "id"), cursor = optionalString(args, "cursor", 80);
                        int limit = integer(args, "limit", 20, 50);
                        yield read(() -> relationships(resolve(id), limit, cursor));
                    }
                    case "model_validate" -> {
                        keys(args, "id", "ids", "layer", "limit");
                        List<String> ids = ids(args);
                        String layer = layer(args);
                        if (!ids.isEmpty() && layer != null)
                            throw new ArgumentException("Choose IDs or a layer, not both");
                        int limit = integer(args, "limit", 30, 50);
                        yield read(() -> validate(ids, layer, limit));
                    }
                    case "native_validation" -> {
                        keys(args, "limit", "cursor");
                        int limit = integer(args, "limit", 30, 50);
                        String cursor = optionalString(args, "cursor", 80);
                        yield read(() -> nativeValidation(limit, cursor));
                    }
                    default -> throw new IllegalArgumentException("Unknown read tool");
                };
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("ok", true);
        envelope.put("tool", name);
        envelope.put("result", result);
        String encoded = json(envelope);
        int level = 0;
        while (encoded.length() > MAX_OUTPUT_CHARS && level++ < 6) {
            envelope.put("output_truncated", true);
            envelope.put("result", compact(envelope.get("result")));
            encoded = json(envelope);
        }
        if (encoded.length() > MAX_OUTPUT_CHARS)
            throw new IllegalStateException("Read result exceeds its output limit");
        return encoded;
    }

    private <T> T read(Supplier<T> supplier) {
        AtomicReference<T> value = new AtomicReference<>();
        SessionSafeguards.runOnUiThread(
                () -> {
                    requireSession();
                    TransactionalEditingDomain domain = session.getTransactionalEditingDomain();
                    if (domain == null)
                        throw new IllegalStateException(
                                "Model reads require a transactional editing domain");
                    try {
                        domain.runExclusive(
                                () -> {
                                    requireSession();
                                    value.set(supplier.get());
                                });
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Model read was interrupted", interrupted);
                    }
                });
        return value.get();
    }

    private void requireSession() {
        if (Thread.currentThread().isInterrupted())
            throw new IllegalStateException("Model read was cancelled");
        if (session == null || !session.isOpen())
            throw new IllegalStateException("The request's Capella model is closed or unavailable");
        var domain = session.getTransactionalEditingDomain();
        if (domain != null)
            for (Resource resource : session.getSemanticResources()) {
                if (resource.getResourceSet() != domain.getResourceSet()) {
                    throw new IllegalStateException(
                            "Semantic resource belongs to another editing domain");
                }
            }
    }

    private Map<String, Object> summary() {
        List<Object> projects = new ArrayList<>(), layers = new ArrayList<>();
        for (Resource resource : resources())
            for (EObject root : resource.getContents()) {
                if (root instanceof Project) projects.add(descriptor(root));
            }
        for (String layer : LAYERS) {
            EObject architecture = ArchitectureLocator.findTargetElement(session, layer);
            if (architecture == null) continue;
            Map<String, Object> entry = descriptor(architecture);
            Map<String, Object> counts = new LinkedHashMap<>();
            for (EReference reference : architecture.eClass().getEAllReferences()) {
                if (!reference.isContainment() || reference.isDerived() || reference.isTransient())
                    continue;
                Object value = architecture.eGet(reference, false);
                counts.put(
                        reference.getName(),
                        value instanceof List<?> list ? list.size() : value == null ? 0 : 1);
            }
            entry.put("direct_containment_counts", counts);
            layers.add(entry);
        }
        return map(
                "projects",
                projects,
                "layers",
                layers,
                "semantic_resource_count",
                resources().size(),
                "scope",
                "Architecture roots and direct containment counts only; these are not whole-model"
                        + " element totals.");
    }

    /**
     * A fresh search that spans several read slices restarts when the model is written in between
     * (for example the save and refresh after a reviewed edit on a large model), so one result
     * never mixes model states. A resumed cursor cannot restart: its earlier pages described the
     * old state.
     */
    private Map<String, Object> search(
            String query, String type, String layer, int limit, String cursor) {
        for (int attempt = 1; ; attempt++) {
            try {
                return searchOnce(query, type, layer, limit, cursor);
            } catch (IllegalStateException changed) {
                if (cursor != null
                        || attempt == SEARCH_RESTARTS
                        || !String.valueOf(changed.getMessage())
                                .startsWith("Model changed during native search")) throw changed;
            }
        }
    }

    private static final int SEARCH_RESTARTS = 3;

    private Map<String, Object> searchOnce(
            String query, String type, String layer, int limit, String cursor) {
        String signature = "search:" + query + ":" + type + ":" + layer;
        long started = searchClock.getAsLong();
        Thread requester = Thread.currentThread();
        if (requester.isInterrupted())
            throw new IllegalStateException("Native search was cancelled");
        SearchPage page =
                read(
                        () ->
                                new SearchPage(
                                        scan(
                                                cursor,
                                                signature,
                                                layer != null ? layer : inferredLayer(type)),
                                        requester));
        // A native UI caller cannot yield to itself between syncExec reads.
        // Keep that caller to one bounded slice and return its normal cursor.
        boolean uiCaller = Display.getCurrent() != null;
        try {
            while (page.stoppedBy == null) {
                if (requester.isInterrupted())
                    throw new IllegalStateException("Native search was cancelled");
                read(
                        () -> {
                            page.guard.check();
                            searchSlice(page, query, type, layer, limit, started);
                            return null;
                        });
                page.slices++;
                afterSearchSlice.run();
                if (page.stoppedBy == null && uiCaller) page.stoppedBy = "ui_slice";
                if (page.stoppedBy == null && searchClock.getAsLong() - started >= searchNanos)
                    page.stoppedBy = "time_budget";
            }
            return read(
                    () -> {
                        if (requester.isInterrupted())
                            throw new IllegalStateException("Native search was cancelled");
                        page.guard.check();
                        Scan scan = page.scan;
                        boolean traversalComplete = !scan.searchHasNext();
                        boolean evidenceAvailable =
                                scan.unresolved == 0
                                        && scan.foreign == 0
                                        && scan.oversized == 0
                                        && scan.loadErrors == 0
                                        && scan.unloaded == 0;
                        return map(
                                "matches",
                                page.matches,
                                "scanned_this_page",
                                page.scanned,
                                "scanned_total",
                                scan.scanned,
                                "read_slices",
                                page.slices,
                                "stopped_by",
                                page.stoppedBy,
                                "traversal_complete",
                                traversalComplete,
                                "evidence_available",
                                evidenceAvailable,
                                "unavailable_evidence",
                                map(
                                        "unresolved_containment",
                                        scan.unresolved,
                                        "foreign_containment",
                                        scan.foreign,
                                        "load_errors",
                                        scan.loadErrors,
                                        "unloaded_resources",
                                        scan.unloaded,
                                        "oversized_identity",
                                        scan.oversized),
                                "complete",
                                traversalComplete && evidenceAvailable,
                                "cursor",
                                retain(scan),
                                "scope",
                                "Names, native IDs and ReqIF identifiers in the captured semantic"
                                    + " resources. Continue an available cursor with identical"
                                    + " filters. Traversal completion does not establish absence or"
                                    + " uniqueness when evidence_available is false; unavailable"
                                    + " content was not resolved.");
                    });
        } finally {
            // A closed/disposed model must not prevent removal of this local
            // listener. It observes transactions only and installs no adapters.
            SessionSafeguards.runOnUiThread(page.guard::close);
        }
    }

    private void searchSlice(
            SearchPage page, String query, String type, String layer, int limit, long started) {
        Scan scan = page.scan;
        long sliceStarted = searchClock.getAsLong();
        int inspected = 0;
        String queryLower = query == null ? null : query.toLowerCase(Locale.ROOT);
        while (scan.searchHasNext()) {
            if (page.requester.isInterrupted())
                throw new IllegalStateException("Native search was cancelled");
            long now = searchClock.getAsLong();
            if (page.matches.size() == limit) {
                page.stoppedBy = "match_limit";
                return;
            }
            if (page.scanned == searchObjects) {
                page.stoppedBy = "object_budget";
                return;
            }
            if (now - started >= searchNanos) {
                page.stoppedBy = "time_budget";
                return;
            }
            if (inspected == SCAN_OBJECTS || now - sliceStarted >= SCAN_NANOS) return;
            Map<String, Object> match = scan.pendingSearchMatch;
            if (match == null) {
                EObject element = scan.next();
                inspected++;
                page.scanned++;
                scan.scanned++;
                if (element.eIsProxy()) {
                    scan.unresolved++;
                    scan.pruneChildren();
                    continue;
                }
                if (!scan.resources.contains(element.eResource())) {
                    scan.foreign++;
                    scan.pruneChildren();
                    continue;
                }
                // Preserve the existing bounded proof for optional ReqIF metadata.
                if ("Requirement".equalsIgnoreCase(type)
                        && scan.requirements.canSkipChildren(element)) scan.pruneChildren();
                if (type != null && !matchesType(element, type)) continue;
                if (layer != null && !layer.equals(layerOf(element))) continue;
                if (queryLower != null && !searchText(element).contains(queryLower)) continue;
                if (nativeId(element).length() > 2400) {
                    scan.oversized++;
                    continue;
                }
                match = descriptor(element);
            }
            int encodedSize = json(match).length() + 1;
            if (encodedSize > SEARCH_OUTPUT_CHARS) {
                scan.oversized++;
                scan.pendingSearchMatch = null;
                continue;
            }
            if (!page.matches.isEmpty() && page.encodedSize + encodedSize > SEARCH_OUTPUT_CHARS) {
                // The object was already traversed: retain its exact detached
                // descriptor so an output boundary cannot silently lose it.
                scan.pendingSearchMatch = match;
                page.stoppedBy = "output_budget";
                return;
            }
            scan.pendingSearchMatch = null;
            page.matches.add(match);
            page.encodedSize += encodedSize;
        }
        page.stoppedBy = "traversal_complete";
    }

    private final class SearchPage {
        final Scan scan;
        final SearchGuard guard;
        final Thread requester;
        final List<Object> matches = new ArrayList<>();
        int scanned, slices, encodedSize;
        String stoppedBy;

        SearchPage(Scan scan, Thread requester) {
            this.scan = scan;
            this.requester = requester;
            guard = new SearchGuard(scan);
        }
    }

    /** Detect even non-Undo writes between separately scheduled native read slices. */
    private final class SearchGuard extends TransactionalEditingDomainListenerImpl {
        final Scan scan;
        final TransactionalEditingDomain.Lifecycle lifecycle;
        boolean changed, disposed;

        SearchGuard(Scan scan) {
            this.scan = scan;
            lifecycle =
                    TransactionUtil.getAdapter(
                            scan.domain, TransactionalEditingDomain.Lifecycle.class);
            if (lifecycle == null)
                throw new IllegalStateException(
                        "Native search requires editing-domain lifecycle evidence");
            lifecycle.addTransactionalEditingDomainListener(this);
        }

        @Override
        public void transactionStarted(TransactionalEditingDomainEvent event) {
            if (event.getTransaction() != null && !event.getTransaction().isReadOnly())
                changed = true;
        }

        @Override
        public void editingDomainDisposing(TransactionalEditingDomainEvent event) {
            disposed = true;
        }

        void check() {
            if (changed || disposed)
                throw new IllegalStateException(
                        "Model changed during native search; restart without its cursor");
            scan.checkVersion();
        }

        void close() {
            if (!disposed) lifecycle.removeTransactionalEditingDomainListener(this);
        }
    }

    private Map<String, Object> inspect(EObject element, int limit) {
        Map<String, Object> result = descriptor(element), attributes = new LinkedHashMap<>();
        boolean truncated = false;
        for (EAttribute attribute : element.eClass().getEAllAttributes()) {
            if (attribute.isDerived() || attribute.isTransient() || sensitive(attribute.getName()))
                continue;
            if (attributes.size() == limit) {
                truncated = true;
                break;
            }
            attributes.put(attribute.getName(), scalar(element.eGet(attribute, false), 1200));
        }
        List<Object> references = new ArrayList<>();
        int budget = 60;
        for (EReference reference : element.eClass().getEAllReferences()) {
            if (reference.isDerived() || reference.isTransient()) continue;
            Object value = element.eGet(reference, false);
            if (value == null || value instanceof List<?> list && list.isEmpty()) continue;
            if (references.size() == limit || budget <= 0) {
                truncated = true;
                break;
            }
            List<Object> targets = new ArrayList<>();
            int count = value instanceof List<?> list ? list.size() : 1;
            for (Object target : values(value)) {
                if (targets.size() == limit || budget == 0) break;
                if (target instanceof EObject object) {
                    targets.add(referenceDescriptor(object));
                    budget--;
                }
            }
            references.add(
                    map(
                            "feature",
                            reference.getName(),
                            "containment",
                            reference.isContainment(),
                            "count",
                            count,
                            "targets",
                            targets,
                            "truncated",
                            count > targets.size()));
            truncated |= count > targets.size();
        }
        result.put("attributes", attributes);
        result.put("references", references);
        EObject parent = element.eContainer();
        if (parent != null) result.put("parent", referenceDescriptor(parent));
        result.put("source_properties", sourceProperties(element, limit));
        result.put("truncated", truncated);
        result.put(
                "scope",
                "Stored native attributes and references; derived model queries and external"
                        + " resources are not resolved.");
        return result;
    }

    private Map<String, Object> relationships(EObject owner, int limit, String cursor) {
        String signature = "relationships:" + nativeId(owner);
        // Sirius normally maintains this index already. Never install an adapter
        // here: constructing one would walk the complete large model on SWT.
        ECrossReferenceAdapter inverse =
                ECrossReferenceAdapter.getCrossReferenceAdapter(
                        session.getTransactionalEditingDomain().getResourceSet());
        List<Object> incoming = new ArrayList<>();
        if (cursor == null && inverse != null) {
            int inspected = 0;
            // getInverseReferences also invokes derived opposite getters, which
            // can require adapters and unbounded native queries. Read only the
            // index's stored, non-navigable entries and scan the remainder.
            for (EStructuralFeature.Setting setting :
                    inverse.getNonNavigableInverseReferences(owner, false)) {
                if (inspected++ >= 1000 || incoming.size() == limit) break;
                EObject candidate = setting.getEObject();
                if (candidate.eIsProxy()
                        || !owned(candidate)
                        || !(setting.getEStructuralFeature() instanceof EReference reference)
                        || reference.isContainment()
                        || reference.isDerived()) continue;
                incoming.add(map("element", descriptor(candidate), "feature", reference.getName()));
            }
        }
        Scan scan = scan(cursor, signature, null);
        int scanned = 0;
        boolean incompleteReferences = false;
        long started = System.nanoTime();
        while (scan.hasNext()
                && scanned < SCAN_OBJECTS
                && incoming.size() < limit
                && System.nanoTime() - started < SCAN_NANOS) {
            EObject candidate = scan.next();
            scanned++;
            scan.scanned++;
            if (candidate.eIsProxy() || !owned(candidate)) continue;
            for (EReference reference : candidate.eClass().getEAllReferences()) {
                if (reference.isDerived() || reference.isContainment() || reference.isTransient())
                    continue;
                Object value = candidate.eGet(reference, false);
                int checked = 0;
                for (Object target : values(value)) {
                    if (checked++ == 100) {
                        incompleteReferences = true;
                        break;
                    }
                    if (target == owner) {
                        Map<String, Object> match =
                                map(
                                        "element",
                                        descriptor(candidate),
                                        "feature",
                                        reference.getName());
                        if (!incoming.contains(match)) incoming.add(match);
                        break;
                    }
                }
                if (incoming.size() == limit) {
                    incompleteReferences = true;
                    break;
                }
            }
        }
        var details = inspect(owner, Math.min(limit, 15));
        return map(
                "element",
                descriptor(owner),
                "outgoing",
                details.get("references"),
                "incoming",
                incoming,
                "scanned_total",
                scan.scanned,
                "complete",
                !scan.hasNext() && !incompleteReferences,
                "cursor",
                retain(scan),
                "truncated",
                incompleteReferences || Boolean.TRUE.equals(details.get("truncated")),
                "scope",
                (inverse == null
                                ? ""
                                : "Existing native inverse-reference index supplies stored hints;"
                                        + " no new index was built. ")
                        + "Stored outgoing references and bounded incoming references, including"
                        + " typed allocations, realizations and requirement traces. Continue with"
                        + " cursor until complete.");
    }

    private Map<String, Object> validate(List<String> ids, String layer, int limit) {
        List<EObject> roots = new ArrayList<>();
        for (String id : ids) roots.add(resolve(id));
        if (roots.isEmpty()) {
            if (layer != null) {
                EObject architecture = ArchitectureLocator.findTargetElement(session, layer);
                if (architecture == null)
                    throw new IllegalArgumentException("Architecture is unavailable: " + layer);
                roots.add(architecture);
            } else for (Resource resource : resources()) roots.addAll(resource.getContents());
        }
        List<Map<String, Object>> issues = new ArrayList<>();
        int inspected = 0;
        Methodology method = new Methodology();
        boolean truncated = false;
        long started = System.nanoTime();
        for (EObject root : roots) {
            Iterator<EObject> iterator = new RootIterator(root);
            while (iterator.hasNext()) {
                if (inspected == SCAN_OBJECTS
                        || issues.size() == limit
                        || System.nanoTime() - started >= SCAN_NANOS) {
                    truncated = true;
                    break;
                }
                EObject element = iterator.next();
                inspected++;
                if (element.eIsProxy()) {
                    issues.add(
                            issue(
                                    element,
                                    "error",
                                    "unresolved_reference",
                                    "Unresolved contained proxy"));
                    continue;
                }
                for (EReference reference : element.eClass().getEAllReferences()) {
                    if (reference.isDerived() || reference.isTransient()) continue;
                    Object value = element.eGet(reference, false);
                    int checked = 0;
                    for (Object target : values(value)) {
                        if (checked++ == 100) {
                            truncated = true;
                            break;
                        }
                        if (target instanceof EObject object
                                && (object.eIsProxy() || object.eResource() == null)) {
                            issues.add(
                                    issue(
                                            element,
                                            "error",
                                            "unresolved_reference",
                                            "Unresolved/detached target in "
                                                    + reference.getName()));
                        }
                        if (issues.size() == limit) break;
                    }
                    if (issues.size() == limit) break;
                }
                if (issues.size() < limit
                        && element instanceof ComponentFunctionalAllocation allocation
                        && allocation.getTargetElement() instanceof AbstractFunction function
                        && !function.getOwnedFunctions().isEmpty()) {
                    issues.add(
                            issue(
                                    element,
                                    "error",
                                    "decomposed_allocation_target",
                                    "Function allocation targets a decomposed function instead of a"
                                            + " leaf"));
                }
                method.observe(element, issues, limit);
            }
            if (truncated) break;
        }
        // Absence rules need every allocation in scope, so they never run on partial or ID-scoped
        // reads.
        boolean whole = ids.isEmpty() && !truncated;
        if (whole) truncated = method.finish(issues, limit);
        long errors =
                issues.stream().filter(issue -> "error".equals(issue.get("severity"))).count();
        return map(
                "mode",
                "bounded_structural",
                "inspected",
                inspected,
                "errors",
                errors,
                "warnings",
                issues.size() - errors,
                "issues",
                issues,
                "truncated",
                truncated,
                "methodology_scope",
                whole ? "complete" : "local_only",
                "scope",
                "Counts cover inspected objects only. Errors are stored-reference faults; warnings"
                    + " are Arcadia method gaps (unallocated or unrealized leaf functions,"
                    + " functional exchanges between components without a component exchange)."
                    + " methodology_scope local_only means absence rules were skipped. Not all"
                    + " native constraints or engineering compliance; use native_validation for the"
                    + " installed validator.");
    }

    /**
     * Arcadia method gaps from stored references. Leaf functions directly under a function package
     * are roots and exempt.
     */
    private static final class Methodology {
        final List<AbstractFunction> leaves = new ArrayList<>();
        final Map<AbstractFunction, EObject> allocatedTo = new java.util.IdentityHashMap<>();
        final List<org.polarsys.capella.core.data.fa.FunctionalExchange> exchanges =
                new ArrayList<>();
        final Set<EObject> carried =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        void observe(EObject element, List<Map<String, Object>> issues, int limit) {
            String layer = layerOf(element);
            if (element instanceof ComponentFunctionalAllocation allocation
                    && allocation.getTargetElement() instanceof AbstractFunction function) {
                allocatedTo.put(
                        function,
                        allocation.getSourceElement() != null
                                ? allocation.getSourceElement()
                                : allocation.eContainer());
            } else if (element
                    instanceof
                    org.polarsys.capella.core.data.fa.ComponentExchangeFunctionalExchangeAllocation
                                    allocation) {
                if (allocation.getTargetElement() != null)
                    carried.add(allocation.getTargetElement());
            } else if (element instanceof AbstractFunction function
                    && Set.of("SA", "LA", "PA").contains(layer)
                    && function.getOwnedFunctions().isEmpty()
                    && !(function.eContainer()
                            instanceof org.polarsys.capella.core.data.fa.FunctionPkg)) {
                leaves.add(function);
                if (!"SA".equals(layer)
                        && function.getOwnedFunctionRealizations().isEmpty()
                        && issues.size() < limit) {
                    issues.add(
                            issue(
                                    function,
                                    "warning",
                                    "unrealized_function",
                                    "Leaf function does not realize a function of the previous"
                                            + " perspective"));
                }
            } else if (element
                    instanceof org.polarsys.capella.core.data.fa.FunctionalExchange exchange) {
                exchanges.add(exchange);
                if ((exchange.getSource() == null || exchange.getTarget() == null)
                        && issues.size() < limit) {
                    issues.add(
                            issue(
                                    exchange,
                                    "error",
                                    "exchange_missing_end",
                                    "Functional exchange is missing its source or target"));
                }
            } else if (element
                            instanceof org.polarsys.capella.core.data.fa.ComponentExchange exchange
                    && (exchange.getSource() == null || exchange.getTarget() == null)
                    && issues.size() < limit) {
                issues.add(
                        issue(
                                exchange,
                                "error",
                                "exchange_missing_end",
                                "Component exchange is missing its source or target"));
            }
        }

        /** Returns true when the issue limit cut the findings short. */
        boolean finish(List<Map<String, Object>> issues, int limit) {
            for (AbstractFunction leaf : leaves) {
                if (allocatedTo.containsKey(leaf)) continue;
                if (issues.size() == limit) return true;
                issues.add(
                        issue(
                                leaf,
                                "warning",
                                "unallocated_leaf_function",
                                "Leaf function is not allocated to a component"));
            }
            for (var exchange : exchanges) {
                EObject from = allocatedTo.get(function(exchange.getSource())),
                        to = allocatedTo.get(function(exchange.getTarget()));
                if (from == null || to == null || from == to || carried.contains(exchange))
                    continue;
                if (issues.size() == limit) return true;
                issues.add(
                        issue(
                                exchange,
                                "warning",
                                "unallocated_functional_exchange",
                                "Functional exchange between functions on different components is"
                                        + " not allocated to a component exchange"));
            }
            return false;
        }

        private static AbstractFunction function(EObject end) {
            return end instanceof AbstractFunction function
                    ? function
                    : end != null && end.eContainer() instanceof AbstractFunction owner
                            ? owner
                            : null;
        }
    }

    private Map<String, Object> nativeValidation(int limit, String cursor) {
        long now = validationClock.getAsLong();
        validationCursors.values().removeIf(value -> now - value.touched > CURSOR_LIFETIME_NANOS);
        ValidationSnapshot snapshot =
                cursor == null
                        ? new ValidationSnapshot(nativeValidator.apply(10000), now)
                        : validationCursors.remove(cursor);
        if (snapshot == null)
            throw new ArgumentException(
                    "Validation cursor is unavailable; restart without its cursor");
        var commands = session.getTransactionalEditingDomain().getCommandStack();
        if (snapshot.undo != commands.getUndoCommand()
                || snapshot.redo != commands.getRedoCommand()
                || !snapshot.resources.equals(resources())) {
            throw new IllegalStateException(
                    "Model changed; restart native validation without its cursor");
        }
        snapshot.touched = now;
        int offset = snapshot.offset, encodedSize = 0;
        List<Object> page = new ArrayList<>();
        while (snapshot.offset < snapshot.issues.size() && page.size() < limit) {
            Map<String, Object> issue = snapshot.issues.get(snapshot.offset);
            int size = json(issue).length() + 1;
            // Decide the page boundary before advancing its cursor. Generic
            // output compaction must never discard unseen diagnostics.
            if (!page.isEmpty() && encodedSize + size > 24000) break;
            page.add(issue);
            encodedSize += size;
            snapshot.offset++;
        }
        boolean remaining = snapshot.offset < snapshot.issues.size();
        String next = null;
        if (remaining) {
            while (validationCursors.size() >= 3)
                validationCursors.remove(validationCursors.keySet().iterator().next());
            next = UUID.randomUUID().toString();
            validationCursors.put(next, snapshot);
        }
        Map<String, Object> result = new LinkedHashMap<>(snapshot.counts);
        result.putAll(
                map(
                        "issues",
                        page,
                        "validation_id",
                        snapshot.id,
                        "page_offset",
                        offset,
                        "available_issue_count",
                        snapshot.issues.size(),
                        "returned_issue_count",
                        page.size(),
                        "cursor",
                        next,
                        "complete",
                        !remaining,
                        "source_truncated",
                        snapshot.sourceTruncated,
                        "truncated",
                        remaining || snapshot.sourceTruncated,
                        "mode",
                        "native_batch",
                        "scope",
                        "One installed full-project batch validation. Counts cover all findings;"
                            + " continue cursor for all captured messages (maximum 10000). Complete"
                            + " means all captured messages were delivered; source_truncated"
                            + " reports omitted findings. Long messages are marked"
                            + " message_truncated. Passing does not establish design completeness"
                            + " or rule compliance."));
        return result;
    }

    /** Detached diagnostic data: paging never reruns validation or retains model objects. */
    private final class ValidationSnapshot {
        final String id = UUID.randomUUID().toString();
        final List<Resource> resources = ArcadiaModelTools.this.resources();
        final Object undo =
                session.getTransactionalEditingDomain().getCommandStack().getUndoCommand();
        final Object redo =
                session.getTransactionalEditingDomain().getCommandStack().getRedoCommand();
        final Map<String, Object> counts = new LinkedHashMap<>();
        final List<Map<String, Object>> issues = new ArrayList<>();
        final boolean sourceTruncated;
        long touched;
        int offset;

        ValidationSnapshot(Map<String, Object> nativeResult, long now) {
            for (String key : List.of("severity", "errors", "warnings", "issueCount"))
                counts.put(key, nativeResult.get(key));
            if (!(nativeResult.get("issues") instanceof List<?> messages)) {
                throw new IllegalStateException("Native validation returned no diagnostic list");
            }
            for (Object entry : messages.subList(0, Math.min(10000, messages.size()))) {
                if (!(entry instanceof Map<?, ?> message))
                    throw new IllegalStateException("Invalid native diagnostic");
                String text = String.valueOf(message.get("message"));
                Map<String, Object> detached =
                        map(
                                "severity",
                                message.get("severity"),
                                "code",
                                message.get("code"),
                                "plugin",
                                scalar(message.get("plugin"), 160),
                                "message",
                                Text.clip(text, 1200),
                                "message_truncated",
                                text.length() > 1200);
                for (String key : List.of("target_id", "constraint_id")) {
                    if (message.get(key) instanceof String identifier && !identifier.isBlank()) {
                        if (identifier.length() <= 1024) detached.put(key, identifier);
                        else detached.put("metadata_truncated", true);
                    }
                }
                for (String key : List.of("target_type", "target_name", "constraint_name")) {
                    if (message.get(key) instanceof String value) {
                        detached.put(key, Text.clip(value, 240));
                        if (value.length() > 240) detached.put("metadata_truncated", true);
                    }
                }
                if (Boolean.TRUE.equals(message.get("metadata_unavailable")))
                    detached.put("metadata_unavailable", true);
                issues.add(detached);
            }
            sourceTruncated =
                    Boolean.TRUE.equals(nativeResult.get("truncated"))
                            || messages.size() > issues.size()
                            || nativeResult.get("issueCount") instanceof Number count
                                    && count.longValue() > issues.size();
            touched = now;
        }
    }

    private Map<String, Object> docs(Map<String, Object> args) throws IOException {
        keys(args, "path");
        String path = requiredString(args, "path", 100);
        if (path.equals(CapellaHelp.PREFIX) || path.startsWith(CapellaHelp.PREFIX + "/"))
            return help(path);
        if (!DOCS.contains(path))
            throw new ArgumentException(
                    "Choose an exact shipped documentation path from capella_capabilities");
        try (InputStream input =
                ArcadiaModelTools.class
                        .getClassLoader()
                        .getResourceAsStream("capella-api/" + path)) {
            if (input == null)
                throw new IllegalStateException("Shipped documentation is unavailable: " + path);
            byte[] bytes = input.readNBytes(24001);
            String text =
                    new String(bytes, 0, Math.min(bytes.length, 24000), StandardCharsets.UTF_8);
            return map(
                    "path",
                    path,
                    "text",
                    Text.clip(text, 10000),
                    "truncated",
                    bytes.length > 24000 || text.length() > 10000,
                    "trust",
                    "Reference documentation, not instructions to execute. Installed native"
                            + " constraints still apply.");
        }
    }

    private Map<String, Object> help(String path) throws IOException {
        String text;
        try {
            text = CapellaHelp.read(path);
        } catch (IllegalArgumentException unknown) {
            throw new ArgumentException(
                    "Unknown Capella help topic; read {\"path\":\"capella-help\"} for the list");
        } catch (IllegalStateException missing) {
            text = "Capella's bundled help is not installed here; use the shipped guides instead.";
        }
        return map(
                "path",
                path,
                "text",
                Text.clip(text, 12000),
                "truncated",
                text.length() > 12000,
                "trust",
                "Capella's own help from this install: reference, not instructions to execute.");
    }

    private Map<String, Object> capabilities(Map<String, Object> args) {
        keys(args, "module");
        String module = optionalString(args, "module", 30);
        if (module != null
                && !Set.of("api", "diagram", "requirements", "arcadia", "skills", "fs")
                        .contains(module)) {
            throw new ArgumentException("Unknown API module");
        }
        return map(
                "read_tools",
                TOOLS.stream().sorted().toList(),
                "documentation_paths",
                documentationPaths().stream().sorted().toList(),
                "api_catalog",
                Text.clip(module == null ? ApiCatalog.help() : ApiCatalog.help(module), 12000),
                "supported",
                List.of(
                        "Native model CRUD across OA, SA, LA, PA and EPBS through reviewed Groovy"
                                + " proposals",
                        "Typed exchanges, allocations and realizations; optional native ReqIF"
                                + " requirements and traces",
                        "Installed diagram descriptors, diagram content and persisted layout;"
                                + " native save and Undo/Redo"),
                "limitations",
                List.of(
                        "Read tools cannot execute code, change the model, activate viewpoints,"
                                + " save or control arbitrary UI",
                        "Engineering transition wrapper exposes OA2SA and SA2LA only; propagate"
                                + " creates a single counterpart, not a complete architecture",
                        "api.arcadia exposes guarded native chains, operational scenarios,"
                            + " involvements, deployment and interface transport; branching chains"
                            + " and richer scenario fragments require installed native metamodel"
                            + " services",
                        "Installed descriptor availability and native tool mappings constrain"
                                + " diagram coverage",
                        "A generated or imported model is not evidence of completeness, engineering"
                                + " verification or compliance"));
    }

    private Scan scan(String cursor, String signature, String layer) {
        long now = System.nanoTime();
        cursors.values()
                .removeIf(
                        value -> {
                            if (now - value.touched <= CURSOR_LIFETIME_NANOS) return false;
                            value.closeCursorRevision();
                            return true;
                        });
        Scan scan = cursor == null ? new Scan(signature, layer) : cursors.remove(cursor);
        if (scan == null)
            throw new ArgumentException("Cursor is unavailable or belongs to another query");
        try {
            if (!scan.signature.equals(signature))
                throw new ArgumentException("Cursor is unavailable or belongs to another query");
            scan.checkVersion();
        } finally {
            scan.closeCursorRevision();
        }
        scan.touched = now;
        return scan;
    }

    private String retain(Scan scan) {
        if (!scan.searchHasNext()) return null;
        while (cursors.size() >= 6)
            cursors.remove(cursors.keySet().iterator().next()).closeCursorRevision();
        scan.cursorRevision = new CursorRevision(scan.domain);
        String token = UUID.randomUUID().toString();
        cursors.put(token, scan);
        return token;
    }

    private final class Scan implements Iterator<EObject> {
        final String signature;
        final List<Resource> resources = ArcadiaModelTools.this.resources();
        final TransactionalEditingDomain domain = session.getTransactionalEditingDomain();
        final Object resourceSet = domain.getResourceSet();
        final Object undo = domain.getCommandStack().getUndoCommand();
        final Object redo = domain.getCommandStack().getRedoCommand();
        final List<ResourceReadState> resourceStates =
                resources.stream().map(ResourceReadState::new).toList();
        final Iterator<Resource> resourceIterator;
        Iterator<EObject> objects = List.<EObject>of().iterator();
        final RequirementPruner requirements = new RequirementPruner();
        long touched = System.nanoTime();
        int scanned;
        int unresolved, foreign, oversized, loadErrors, unloaded;
        Map<String, Object> pendingSearchMatch;
        CursorRevision cursorRevision;

        Scan(String signature, String layer) {
            this.signature = signature;
            for (ResourceReadState state : resourceStates) {
                loadErrors += state.errors;
                if (!state.loaded) unloaded++;
            }
            if (layer == null) resourceIterator = resources.iterator();
            else {
                EObject architecture = ArchitectureLocator.findTargetElement(session, layer);
                if (architecture == null)
                    throw new IllegalArgumentException("Architecture is unavailable: " + layer);
                resourceIterator = List.<Resource>of().iterator();
                objects = new RootIterator(architecture);
            }
        }

        @Override
        public boolean hasNext() {
            while (!objects.hasNext() && resourceIterator.hasNext())
                objects = EcoreUtil.<EObject>getAllContents(resourceIterator.next(), false);
            return objects.hasNext();
        }

        @Override
        public EObject next() {
            if (!hasNext()) throw new java.util.NoSuchElementException();
            return objects.next();
        }

        boolean searchHasNext() {
            return pendingSearchMatch != null || hasNext();
        }

        void checkVersion() {
            var commands = domain.getCommandStack();
            if (!session.isOpen()
                    || session.getTransactionalEditingDomain() != domain
                    || domain.getResourceSet() != resourceSet
                    || undo != commands.getUndoCommand()
                    || redo != commands.getRedoCommand()
                    || cursorRevision != null && cursorRevision.invalid()
                    || !resources.equals(ArcadiaModelTools.this.resources())
                    || resourceStates.stream().anyMatch(state -> !state.same())) {
                throw new IllegalStateException(
                        "Model changed; restart the read query without its cursor");
            }
        }

        void closeCursorRevision() {
            if (cursorRevision != null) {
                cursorRevision.close();
                cursorRevision = null;
            }
        }

        void pruneChildren() {
            if (objects instanceof TreeIterator<?> tree) tree.prune();
            else if (objects instanceof RootIterator rootIterator) rootIterator.pruneChildren();
        }
    }

    /**
     * A retained cursor observes intervening non-Undo transactions too. This static listener
     * retains neither a Session nor model objects; an abandoned query can be collected, and the
     * listener removes itself within the TTL.
     */
    private static final class CursorRevision extends TransactionalEditingDomainListenerImpl {
        final WeakReference<TransactionalEditingDomain.Lifecycle> lifecycle;
        final Display display;
        final Runnable expiry;
        boolean changed, disposed, expired, closed;

        CursorRevision(TransactionalEditingDomain domain) {
            var value =
                    TransactionUtil.getAdapter(domain, TransactionalEditingDomain.Lifecycle.class);
            if (value == null)
                throw new IllegalStateException(
                        "Native cursor requires editing-domain lifecycle evidence");
            lifecycle = new WeakReference<>(value);
            display = Display.getCurrent();
            WeakReference<CursorRevision> self = new WeakReference<>(this);
            expiry =
                    () -> {
                        var current = self.get();
                        if (current != null) {
                            current.expired = true;
                            current.close();
                        }
                    };
            value.addTransactionalEditingDomainListener(this);
            if (display != null && !display.isDisposed())
                display.timerExec((int) (CURSOR_LIFETIME_NANOS / 1_000_000L), expiry);
        }

        @Override
        public void transactionStarted(TransactionalEditingDomainEvent event) {
            if (event.getTransaction() != null && !event.getTransaction().isReadOnly())
                changed = true;
        }

        @Override
        public void editingDomainDisposing(TransactionalEditingDomainEvent event) {
            disposed = true;
            close();
        }

        boolean invalid() {
            return changed || disposed || expired;
        }

        void close() {
            if (closed) return;
            closed = true;
            var value = lifecycle.get();
            if (value != null && !disposed) value.removeTransactionalEditingDomainListener(this);
            if (display != null && !display.isDisposed()) {
                if (Display.getCurrent() == display) display.timerExec(-1, expiry);
                else
                    display.asyncExec(
                            () -> {
                                if (!display.isDisposed()) display.timerExec(-1, expiry);
                            });
            }
        }
    }

    private static final class ResourceReadState {
        final Resource resource;
        final org.eclipse.emf.common.util.URI uri;
        final boolean loaded;
        final int errors;

        ResourceReadState(Resource resource) {
            this.resource = resource;
            uri = resource.getURI();
            loaded = resource.isLoaded();
            errors = resource.getErrors().size();
        }

        boolean same() {
            return java.util.Objects.equals(uri, resource.getURI())
                    && loaded == resource.isLoaded()
                    && errors == resource.getErrors().size();
        }
    }

    private static final class RootIterator implements Iterator<EObject> {
        private EObject root;
        private TreeIterator<EObject> children;
        private boolean lastWasRoot;

        RootIterator(EObject root) {
            this.root = root;
            children = EcoreUtil.getAllContents(root, false);
        }

        public boolean hasNext() {
            return root != null || children != null && children.hasNext();
        }

        public EObject next() {
            lastWasRoot = root != null;
            if (lastWasRoot) {
                EObject value = root;
                root = null;
                return value;
            }
            if (children == null) throw new java.util.NoSuchElementException();
            return children.next();
        }

        void pruneChildren() {
            if (lastWasRoot) children = null;
            else if (children != null) children.prune();
        }
    }

    /** Bounded containment proof over one requirement's metadata, never a model index. */
    private static final class RequirementPruner {
        private static final Set<String> NAMESPACES =
                Set.of(
                        "http://www.polarsys.org/kitalpha/requirements",
                        "http://www.polarsys.org/capella/requirements");
        private static final int MAX_METADATA_OBJECTS = 128;
        private final Map<EClass, List<EReference>> features = new java.util.IdentityHashMap<>();

        boolean canSkipChildren(EObject requirement) {
            EClass type = requirement.eClass();
            if (!"Requirement".equals(type.getName()) || !nativePackage(type.getEPackage()))
                return false;
            // EMDE extensions are generic: a nonempty slot must be inspected,
            // never inferred safe from its declared EObject/ModelElement type.
            java.util.Deque<EObject> pending = new java.util.ArrayDeque<>();
            pending.add(requirement);
            int inspected = 0;
            while (!pending.isEmpty()) {
                if (++inspected > MAX_METADATA_OBJECTS || Thread.currentThread().isInterrupted())
                    return false;
                EObject object = pending.removeFirst();
                if (object.eIsProxy()
                        || object.eResource() != requirement.eResource()
                        || !nativePackage(object.eClass().getEPackage())) return false;
                if (object != requirement && isRequirement(object.eClass())) return false;
                if (!features.containsKey(object.eClass())) {
                    if (features.size() >= 128) return false;
                    features.put(
                            object.eClass(), List.copyOf(object.eClass().getEAllContainments()));
                }
                for (EReference feature : features.get(object.eClass())) {
                    if (feature.isDerived() || feature.isTransient() || feature.isVolatile())
                        return false;
                    Object value = object.eGet(feature, false);
                    if (value instanceof List<?> list) {
                        if (pending.size() + list.size() + inspected > MAX_METADATA_OBJECTS)
                            return false;
                        // InternalEList iteration resolves proxy elements; use
                        // its stored basic iterator for generic extension slots.
                        var iterator =
                                list instanceof org.eclipse.emf.ecore.util.InternalEList<?> stored
                                        ? stored.basicIterator()
                                        : list.iterator();
                        while (iterator.hasNext()) {
                            if (!(iterator.next() instanceof EObject queuedChild)) return false;
                            pending.addLast(queuedChild);
                        }
                    } else if (value instanceof EObject containedChild)
                        pending.addLast(containedChild);
                    else if (value != null) return false;
                }
            }
            return true;
        }

        private static boolean isRequirement(EClass type) {
            return "Requirement".equals(type.getName())
                    || type.getEAllSuperTypes().stream()
                            .anyMatch(
                                    parent ->
                                            nativePackage(parent.getEPackage())
                                                    && "Requirement".equals(parent.getName()));
        }

        private static boolean nativePackage(EPackage model) {
            return model != null && NAMESPACES.contains(model.getNsURI());
        }
    }

    private List<Resource> resources() {
        return List.copyOf(session.getSemanticResources());
    }

    private boolean owned(EObject element) {
        return element.eResource() != null && resources().contains(element.eResource());
    }

    private EObject resolve(String id) {
        for (Resource resource : resources()) {
            EObject element = resource.getEObject(id);
            if (element != null
                    && !element.eIsProxy()
                    && owned(element)
                    && id.equals(nativeId(element))) return element;
        }
        throw new ArgumentException("Native element ID is absent from this session: " + id);
    }

    private Map<String, Object> descriptor(EObject element) {
        return map(
                "id",
                nativeId(element),
                "type",
                element.eClass().getName(),
                "name",
                Text.clip(displayName(element), 200),
                "layer",
                layerOf(element),
                "reqif_identifier",
                Text.clip(storedString(element, "ReqIFIdentifier"), 200));
    }

    private Map<String, Object> referenceDescriptor(EObject element) {
        if (element.eIsProxy()) return map("unresolved", true, "type", element.eClass().getName());
        if (!owned(element)) return map("external", true, "type", element.eClass().getName());
        return descriptor(element);
    }

    private static String nativeId(EObject element) {
        return storedString(element, "id");
    }

    private static String displayName(EObject element) {
        for (String feature : List.of("name", "ReqIFLongName", "ReqIFName", "ReqIFIdentifier")) {
            String value = storedString(element, feature);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static String searchText(EObject element) {
        return (displayName(element)
                        + " "
                        + nativeId(element)
                        + " "
                        + storedString(element, "ReqIFIdentifier")
                        + " "
                        + storedString(element, "ReqIFName"))
                .toLowerCase(Locale.ROOT);
    }

    private static boolean matchesType(EObject element, String type) {
        return type.equalsIgnoreCase(element.eClass().getName())
                || element.eClass().getEAllSuperTypes().stream()
                        .anyMatch(superType -> type.equalsIgnoreCase(superType.getName()));
    }

    private static String inferredLayer(String type) {
        if (type == null) return null;
        String canonical = type.toLowerCase(Locale.ROOT);
        if (Set.of(
                        "operationalanalysis",
                        "operationalactivity",
                        "operationalactivitypkg",
                        "operationalcapability",
                        "operationalcapabilitypkg",
                        "entity",
                        "entitypkg",
                        "communicationmean")
                .contains(canonical)) return "OA";
        if (Set.of(
                        "systemfunction",
                        "systemfunctionpkg",
                        "systemcomponent",
                        "systemcomponentpkg",
                        "systemanalysis")
                .contains(canonical)) return "SA";
        if (Set.of(
                        "logicalarchitecture",
                        "logicalfunction",
                        "logicalfunctionpkg",
                        "logicalcomponent",
                        "logicalcomponentpkg")
                .contains(canonical)) return "LA";
        if (Set.of(
                        "physicalarchitecture",
                        "physicalfunction",
                        "physicalfunctionpkg",
                        "physicalcomponent",
                        "physicalcomponentpkg",
                        "physicallink",
                        "physicallinkend",
                        "physicalport")
                .contains(canonical)) return "PA";
        if (Set.of(
                        "configurationitem",
                        "configurationitempkg",
                        "epbsarchitecture",
                        "physicalartifactrealization")
                .contains(canonical)) return "EPBS";
        return null;
    }

    private static String layerOf(EObject element) {
        for (int depth = 0;
                element != null && depth < 64;
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

    private static String storedString(EObject element, String name) {
        EStructuralFeature feature = element.eClass().getEStructuralFeature(name);
        if (!(feature instanceof EAttribute)
                || feature.isDerived()
                || feature.isTransient()
                || feature.isMany()) return "";
        Object value = element.eGet(feature, false);
        return value instanceof String text ? text : "";
    }

    private Map<String, Object> sourceProperties(EObject owner, int limit) {
        Map<String, Object> result = new LinkedHashMap<>();
        EStructuralFeature feature = owner.eClass().getEStructuralFeature("ownedAttributes");
        if (!(feature instanceof EReference) || feature.isDerived()) return result;
        Object value = owner.eGet(feature, false);
        int inspected = 0;
        for (Object entry : values(value)) {
            if (inspected++ == limit) {
                result.put("_truncated", true);
                break;
            }
            if (!(entry instanceof EObject attribute) || attribute.eIsProxy() || !owned(attribute))
                continue;
            EStructuralFeature definitionFeature =
                    attribute.eClass().getEStructuralFeature("definition");
            if (!(definitionFeature instanceof EReference) || definitionFeature.isDerived())
                continue;
            Object definitionValue = attribute.eGet(definitionFeature, false);
            if (!(definitionValue instanceof EObject definition)
                    || definition.eIsProxy()
                    || !owned(definition)) continue;
            String key = storedString(definition, "ReqIFLongName");
            EStructuralFeature attributeValue = attribute.eClass().getEStructuralFeature("value");
            if (key.isBlank()
                    || sensitive(key)
                    || !(attributeValue instanceof EAttribute)
                    || attributeValue.isDerived()) continue;
            result.put(Text.clip(key, 100), scalar(attribute.eGet(attributeValue, false), 1500));
        }
        return result;
    }

    private static Object scalar(Object value, int limit) {
        if (value == null || value instanceof Boolean) return value;
        if (value instanceof Number number)
            return Double.isFinite(number.doubleValue()) ? number : String.valueOf(number);
        if (value instanceof String text) return Text.clip(text, limit);
        if (value instanceof Enumerator literal) return literal.getLiteral();
        if (value instanceof List<?> list)
            return list.stream().limit(10).map(item -> scalar(item, limit)).toList();
        return "[native datatype]";
    }

    private static Iterable<?> values(Object value) {
        return value == null ? List.of() : value instanceof List<?> list ? list : List.of(value);
    }

    private static Map<String, Object> issue(
            EObject owner, String severity, String rule, String message) {
        return map(
                "severity",
                severity,
                "rule",
                rule,
                "id",
                nativeId(owner),
                "type",
                owner.eClass().getName(),
                "message",
                message);
    }

    private static boolean sensitive(String name) {
        return name.toLowerCase(Locale.ROOT)
                .matches(".*(password|secret|credential|api.?key|access.?token).*");
    }

    private static String layer(Map<String, Object> args) {
        String value = optionalString(args, "layer", 10);
        if (value != null && !LAYERS.contains(value.toUpperCase(Locale.ROOT)))
            throw new ArgumentException("Layer must be OA, SA, LA, PA or EPBS");
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private static String id(Map<String, Object> args, String key) {
        String value = requiredString(args, key, 240);
        if (!value.matches("[\\p{L}\\p{N}_.:@-]+"))
            throw new ArgumentException("Use a native element ID, not a URI or path");
        return value;
    }

    private static List<String> ids(Map<String, Object> args) {
        if (args.containsKey("id") && args.containsKey("ids"))
            throw new ArgumentException("Choose id or ids, not both");
        if (args.containsKey("id")) return List.of(id(args, "id"));
        if (!args.containsKey("ids")) return List.of();
        if (!(args.get("ids") instanceof List<?> list) || list.isEmpty() || list.size() > 50)
            throw new ArgumentException("ids must contain 1 to 50 native IDs");
        List<String> ids = new ArrayList<>();
        for (Object value : list) {
            if (!(value instanceof String text))
                throw new ArgumentException("Every ID must be text");
            ids.add(id(Map.of("id", text), "id"));
        }
        return ids;
    }

    private static void keys(Map<String, Object> args, String... allowed) {
        Set<String> accepted = Set.of(allowed);
        for (Object key : args.keySet()) {
            if (!(key instanceof String))
                throw new ArgumentException("Tool argument names must be text");
            if (!accepted.contains(key))
                throw new ArgumentException(
                        "Unexpected tool argument: " + Text.clip((String) key, 80));
        }
    }

    private static String optionalString(Map<String, Object> args, String key, int max) {
        return args.containsKey(key) ? requiredString(args, key, max) : null;
    }

    private static String requiredString(Map<String, Object> args, String key, int max) {
        if (!(args.get(key) instanceof String text)
                || text.isBlank()
                || text.length() > max
                || text.indexOf('\0') >= 0)
            throw new ArgumentException(
                    key
                            + " must be nonempty text of at most "
                            + max
                            + " characters; omit unused optional fields");
        return text.strip();
    }

    private static int integer(Map<String, Object> args, String key, int fallback, int max) {
        if (!args.containsKey(key)) return fallback;
        if (!(args.get(key) instanceof Number value)
                || !Double.isFinite(value.doubleValue())
                || value.doubleValue() != value.intValue()
                || value.intValue() < 1
                || value.intValue() > max)
            throw new ArgumentException(key + " must be an integer from 1 to " + max);
        return value.intValue();
    }

    private static Map<String, Object> map(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2)
            map.put((String) entries[index], entries[index + 1]);
        return map;
    }

    private static Object compact(Object value) {
        if (value instanceof String text) return Text.clip(text, Math.max(120, text.length() / 2));
        if (value instanceof List<?> list)
            return list.stream()
                    .limit(Math.max(1, list.size() / 2))
                    .map(ArcadiaModelTools::compact)
                    .toList();
        if (value instanceof Map<?, ?> values) {
            Map<String, Object> result = new LinkedHashMap<>();
            values.forEach(
                    (key, item) ->
                            result.put(
                                    (String) key,
                                    Set.of("id", "cursor", "reqif_identifier").contains(key)
                                            ? item
                                            : compact(item)));
            return result;
        }
        return value;
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Map<?, ?> values)
            return values.entrySet().stream()
                    .map(entry -> json(entry.getKey()) + ":" + json(entry.getValue()))
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        if (value instanceof List<?> values)
            return values.stream()
                    .map(ArcadiaModelTools::json)
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        if (!(value instanceof String text))
            throw new IllegalArgumentException("Tool result must contain plain data");
        StringBuilder encoded = new StringBuilder("\"");
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            switch (c) {
                case '"' -> encoded.append("\\\"");
                case '\\' -> encoded.append("\\\\");
                case '\n' -> encoded.append("\\n");
                case '\r' -> encoded.append("\\r");
                case '\t' -> encoded.append("\\t");
                default -> {
                    if (c < 32 || Character.isSurrogate(c))
                        encoded.append(String.format("\\u%04x", (int) c));
                    else encoded.append(c);
                }
            }
        }
        return encoded.append('"').toString();
    }
}
