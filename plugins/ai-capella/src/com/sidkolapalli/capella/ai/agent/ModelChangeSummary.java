package com.sidkolapalli.capella.ai.agent;

import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EContentAdapter;
import org.eclipse.emf.ecore.util.InternalEList;
import org.eclipse.emf.ecore.xmi.XMLResource;
import org.eclipse.sirius.business.api.session.Session;
import com.sidkolapalli.capella.ai.scripting.ExecutionResult;
import com.sidkolapalli.capella.ai.session.SessionSafeguards;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Observes one reviewed execution without changing its command, cancellation or save behavior. Only
 * identities are scanned initially; feature values are retained lazily for objects actually touched
 * by EMF notifications. Reports describe net changes in this session's semantic and owned
 * representation resources, not attempted edits, validator compliance or disk persistence.
 */
public final class ModelChangeSummary {
    public static final int MAX_DISPLAY_ITEMS = 40;
    private static final int MAX_BASELINE_OBJECTS =
            2_000_000; // about 64 MB of identities at the cap
    private static final int MAX_TOUCHED_OBJECTS = 50_000;

    private ModelChangeSummary() {}

    /** Capture immediately before execution, after its review has been approved. */
    public static Recorder capture(Session session) {
        Recorder recorder = new Recorder(session);
        try {
            SessionSafeguards.runOnUiThread(recorder::attach);
        } catch (RuntimeException failure) {
            recorder.unavailable(
                    "Change capture could not start: " + Text.oneLine(failure.getMessage(), 240));
            recorder.close();
        }
        return recorder;
    }

    public record ElementChange(String id, String type, String name, List<String> features) {
        public ElementChange {
            features = features == null ? List.of() : List.copyOf(features);
        }

        public Map<String, Object> feedback() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", id);
            value.put("type", type);
            value.put("name", name);
            value.put("features", features);
            return Collections.unmodifiableMap(value);
        }
    }

    /** Counts are -1 when capture is unavailable; they must not be presented as zero. */
    public record ChangeReport(
            boolean available,
            boolean committed,
            int createdCount,
            int editedCount,
            int deletedCount,
            List<ElementChange> created,
            List<ElementChange> edited,
            List<ElementChange> deleted,
            boolean truncated,
            String note,
            Map<String, Map<String, Map<String, Integer>>> byLayer) {
        public ChangeReport {
            created = List.copyOf(created);
            edited = List.copyOf(edited);
            deleted = List.copyOf(deleted);
            byLayer =
                    byLayer == null
                            ? Map.of()
                            : Collections.unmodifiableMap(new LinkedHashMap<>(byLayer));
        }

        public boolean hasChanges() {
            return available && committed && createdCount + editedCount + deletedCount > 0;
        }

        /** Primitives and bounded lists only, suitable for the next model turn. */
        public Map<String, Object> feedback() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("available", available);
            result.put("committed", committed);
            result.put("created_count", createdCount);
            result.put("edited_count", editedCount);
            result.put("deleted_count", deletedCount);
            result.put("details_truncated", truncated);
            result.put("created", created.stream().map(ElementChange::feedback).toList());
            result.put("edited", edited.stream().map(ElementChange::feedback).toList());
            result.put("deleted", deleted.stream().map(ElementChange::feedback).toList());
            result.put("by_layer", byLayer);
            result.put("note", note);
            return Collections.unmodifiableMap(result);
        }

        public String markdownSummary() {
            if (!available) return "Model change evidence unavailable. " + note;
            if (!committed)
                return "No committed model changes are reported for this execution. " + note;
            StringBuilder text =
                    new StringBuilder("Model changes: ")
                            .append(createdCount)
                            .append(" created, ")
                            .append(editedCount)
                            .append(" edited, ")
                            .append(deletedCount)
                            .append(" deleted.");
            if (note != null && !note.isBlank()) text.append(' ').append(note);
            // Large edits are reviewed per perspective first; the digest counts every observed
            // change.
            if (createdCount + editedCount + deletedCount > DIGEST_THRESHOLD)
                appendDigest(text, byLayer);
            appendItems(text, "Created", created);
            appendItems(text, "Edited", edited);
            appendItems(text, "Deleted", deleted);
            if (truncated)
                text.append("\n\nShowing at most ")
                        .append(MAX_DISPLAY_ITEMS)
                        .append(" elements; counts include all observed changes.");
            return text.toString();
        }
    }

    public static final class Recorder implements AutoCloseable {
        private final Session session;
        private final Set<Resource> resources = identitySet();
        private final Set<EObject> baseline = identitySet();
        private final Map<EObject, Touch> touched = new IdentityHashMap<>();
        private final Set<EObject> attachedObjects = identitySet();
        private final EContentAdapter adapter =
                new EContentAdapter() {
                    @Override
                    protected boolean resolve() {
                        return false;
                    }

                    @Override
                    protected boolean useRecursion() {
                        return false;
                    }

                    @Override
                    protected void addAdapter(org.eclipse.emf.common.notify.Notifier notifier) {
                        super.addAdapter(notifier);
                        if (notifier instanceof EObject object) attachedObjects.add(object);
                    }

                    @Override
                    public void notifyChanged(Notification notification) {
                        try {
                            if (active && available) observe(notification);
                        } catch (RuntimeException failure) {
                            unavailable(
                                    "Change evidence is incomplete: "
                                            + Text.oneLine(failure.getMessage(), 240));
                        } finally {
                            super.notifyChanged(notification);
                        }
                    }
                };
        private final Set<Resource> semanticResources =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean available = true;
        private boolean active;
        private boolean closed;
        private String note = "";
        private ChangeReport finished;

        private Recorder(Session session) {
            this.session = session;
        }

        private void attach() {
            if (session == null
                    || !session.isOpen()
                    || session.getTransactionalEditingDomain() == null) {
                unavailable("An open model and its editing domain are required.");
                return;
            }
            var domainResources = session.getTransactionalEditingDomain().getResourceSet();
            Collection<Resource> semantic = session.getSemanticResources();
            if (semantic != null) {
                resources.addAll(semantic);
                semanticResources.addAll(semantic);
            }
            Resource primary = session.getSessionResource();
            if (primary != null) resources.add(primary);
            Collection<Resource> all = session.getAllSessionResources();
            Collection<Resource> referenced = session.getReferencedSessionResources();
            if (all != null)
                for (Resource resource : all) {
                    if (referenced == null || !referenced.contains(resource))
                        resources.add(resource);
                }
            if (referenced != null) resources.removeAll(referenced);
            if (resources.isEmpty()) {
                unavailable("The session has no owned resources.");
                return;
            }
            for (Resource resource : resources) {
                if (resource.getResourceSet() != domainResources
                        || !resource.getErrors().isEmpty()) {
                    unavailable("An owned resource is outside the model domain or failed to load.");
                    return;
                }
                var iterator = resource.getAllContents();
                while (iterator.hasNext()) {
                    EObject object = iterator.next();
                    if (object.eIsProxy()) continue;
                    if (baseline.size() >= MAX_BASELINE_OBJECTS) {
                        unavailable(
                                "The model exceeds the change capture identity limit of "
                                        + MAX_BASELINE_OBJECTS
                                        + ".");
                        baseline.clear();
                        return;
                    }
                    baseline.add(object);
                }
            }
            active = true;
            for (Resource resource : resources) resource.eAdapters().add(adapter);
        }

        private void observe(Notification event) {
            int kind = event.getEventType();
            if (event.isTouch()
                    || kind == Notification.REMOVING_ADAPTER
                    || kind == Notification.RESOLVE) return;
            Object notifier = event.getNotifier(), feature = event.getFeature();
            if (notifier instanceof Resource resource) {
                if (event.getFeatureID(Resource.class) == Resource.RESOURCE__IS_LOADED
                        && Boolean.FALSE.equals(event.getNewValue())) {
                    unavailable("An observed resource was unloaded during execution.");
                    return;
                }
                if (event.getFeatureID(Resource.class) == Resource.RESOURCE__CONTENTS) {
                    if (!SessionSafeguards.isInManagedTransaction()) {
                        if (!semanticResources.contains(resource))
                            return; // see below: representation data
                        unavailable(
                                "Resource contents changed outside the reviewed script ("
                                        + resource.getURI().lastSegment()
                                        + "); attribution is unavailable.");
                        return;
                    }
                    observeContainment(event, null, null, resource);
                }
            } else if (notifier instanceof EObject object
                    && feature instanceof EStructuralFeature structural) {
                if (structural.isDerived()
                        || structural.isTransient()
                        || !structural.isChangeable()) return;
                if (!baseline.contains(object) && !resources.contains(object.eResource())) return;
                if (!SessionSafeguards.isInManagedTransaction()) {
                    // An opened diagram editor arranges new views and updates its descriptor in its
                    // own
                    // commands after the script. Representation data outside the semantic resources
                    // is
                    // presentation: it is neither script evidence nor a reason to distrust semantic
                    // evidence.
                    if (object.eResource() != null
                            && !semanticResources.contains(object.eResource())) return;
                    unavailable(
                            "The model changed outside the reviewed script while capture was active"
                                    + " ("
                                    + object.eClass().getName()
                                    + "."
                                    + structural.getName()
                                    + "); attribution is unavailable.");
                    return;
                }
                Touch touch = touch(object, null, null, null, false);
                if (touch == null) return;
                touch.features.computeIfAbsent(
                        structural, ignored -> FeatureValue.before(object, structural, event));
                if (structural instanceof EReference reference && reference.isContainment()) {
                    observeContainment(event, object, structural, object.eResource());
                }
            }
        }

        private void observeContainment(
                Notification event, EObject parent, EStructuralFeature feature, Resource resource) {
            switch (event.getEventType()) {
                case Notification.REMOVE, Notification.REMOVE_MANY, Notification.UNSET ->
                        visitValues(
                                event.getOldValue(),
                                value -> markSubtree(value, parent, feature, resource, true));
                case Notification.ADD, Notification.ADD_MANY ->
                        visitValues(
                                event.getNewValue(),
                                value -> markSubtree(value, parent, feature, resource, false));
                case Notification.SET -> {
                    visitValues(
                            event.getOldValue(),
                            value -> markSubtree(value, parent, feature, resource, true));
                    visitValues(
                            event.getNewValue(),
                            value -> markSubtree(value, parent, feature, resource, false));
                }
                default -> {}
            }
        }

        private void markSubtree(
                EObject value,
                EObject oldParent,
                EStructuralFeature feature,
                Resource resource,
                boolean removed) {
            Touch root = touch(value, oldParent, feature, resource, removed);
            if (removed && root != null) root.lastDetached = describe(value, resource, null);
            var children = value.eAllContents();
            while (children.hasNext()) {
                EObject child = children.next();
                Touch descendant =
                        touch(
                                child,
                                child.eContainer(),
                                child.eContainmentFeature(),
                                resource,
                                removed);
                if (removed && descendant != null)
                    descendant.lastDetached = describe(child, resource, null);
            }
        }

        private Touch touch(
                EObject object,
                EObject oldParent,
                EStructuralFeature feature,
                Resource oldResource,
                boolean removed) {
            Touch value = touched.get(object);
            if (value != null) return value;
            if (touched.size() >= MAX_TOUCHED_OBJECTS) {
                unavailable(
                        "The execution exceeds the change capture limit of "
                                + MAX_TOUCHED_OBJECTS
                                + " touched elements.");
                return null;
            }
            Resource resource = removed ? oldResource : object.eResource();
            value =
                    new Touch(
                            describe(object, resource, null),
                            removed ? oldParent : object.eContainer(),
                            removed ? feature : object.eContainmentFeature(),
                            resource);
            touched.put(object, value);
            return value;
        }

        /** Uses execution status, including the explicit committed-but-unsaved status. */
        public ChangeReport finish(ExecutionResult result) {
            boolean committed =
                    result != null
                            && ((result.success()
                                            && result.code() == ExecutionResult.ErrorCode.SUCCESS)
                                    || result.code()
                                            == ExecutionResult.ErrorCode.SAVE_FAILED_AFTER_COMMIT);
            return finish(
                    committed,
                    result != null
                            && result.code() == ExecutionResult.ErrorCode.SAVE_FAILED_AFTER_COMMIT);
        }

        /**
         * For callers with an authoritative transaction outcome. Prefer finish(ExecutionResult).
         */
        public ChangeReport finish(boolean committed) {
            return finish(committed, false);
        }

        private synchronized ChangeReport finish(boolean committed, boolean saveFailed) {
            if (finished != null) return finished;
            AtomicReference<ChangeReport> result = new AtomicReference<>();
            try {
                SessionSafeguards.runOnUiThread(() -> result.set(report(committed, saveFailed)));
            } catch (RuntimeException failure) {
                unavailable(
                        "Change evidence could not be finalized: "
                                + Text.oneLine(failure.getMessage(), 240));
                result.set(unknown(committed));
            } finally {
                close();
            }
            finished = result.get();
            return finished;
        }

        private ChangeReport report(boolean committed, boolean saveFailed) {
            if (!committed)
                return new ChangeReport(
                        true,
                        false,
                        0,
                        0,
                        0,
                        List.of(),
                        List.of(),
                        List.of(),
                        false,
                        "Tentative notifications were discarded.",
                        Map.of());
            if (!available || closed) return unknown(committed);
            if (session == null || !session.isOpen()) {
                unavailable("The session closed before final model changes could be read.");
                return unknown(true);
            }
            List<ElementChange> created = new ArrayList<>(),
                    edited = new ArrayList<>(),
                    deleted = new ArrayList<>();
            Map<String, Map<String, Map<String, Integer>>> byLayer =
                    new java.util.TreeMap<>(Comparator.comparingInt(LAYER_ORDER::indexOf));
            // Views, styles and layout inside a diagram are reported once, as an edit of that
            // diagram.
            Set<EObject> diagramsWithViewChanges = identitySet();
            for (var entry : touched.entrySet()) {
                EObject object = entry.getKey();
                Touch touch = entry.getValue();
                EObject owner = representationInternal(object) ? representationOf(object) : null;
                if (owner != null || representationInternal(object)) {
                    if (owner != null
                            && baseline.contains(owner)
                            && resources.contains(owner.eResource()))
                        diagramsWithViewChanges.add(owner);
                    continue;
                }
                boolean existed = baseline.contains(object),
                        remains = resources.contains(object.eResource());
                if (!existed && remains) {
                    created.add(
                            withFeatures(describe(object, object.eResource(), null), List.of()));
                    count(
                            byLayer,
                            layer(object, object.eResource()),
                            "created",
                            object.eClass().getName());
                } else if (existed && !remains) {
                    deleted.add(
                            withFeatures(
                                    touch.lastDetached == null
                                            ? touch.original
                                            : touch.lastDetached,
                                    List.of()));
                    count(
                            byLayer,
                            layer(touch.parent, touch.resource),
                            "deleted",
                            object.eClass().getName());
                } else if (existed) {
                    List<String> features = new ArrayList<>();
                    for (var change : touch.features.entrySet()) {
                        if (!change.getValue().matches(object, change.getKey()))
                            features.add(change.getKey().getName());
                    }
                    if (object.eContainer() != touch.parent
                            || object.eContainmentFeature() != touch.containment
                            || object.eResource() != touch.resource) features.add("containment");
                    if (!features.isEmpty()) {
                        edited.add(
                                withFeatures(describe(object, object.eResource(), null), features));
                        count(
                                byLayer,
                                layer(object, object.eResource()),
                                "edited",
                                object.eClass().getName());
                    }
                }
            }
            for (EObject diagram : diagramsWithViewChanges) {
                ElementChange described = describe(diagram, diagram.eResource(), null);
                int index = -1;
                for (int i = 0; i < edited.size(); i++)
                    if (described.id() != null && described.id().equals(edited.get(i).id()))
                        index = i;
                if (index >= 0) {
                    List<String> features = new ArrayList<>(edited.get(index).features());
                    features.add("views");
                    edited.set(index, withFeatures(edited.get(index), features));
                } else {
                    edited.add(withFeatures(described, List.of("views")));
                    count(
                            byLayer,
                            layer(diagram, diagram.eResource()),
                            "edited",
                            diagram.eClass().getName());
                }
            }
            Comparator<ElementChange> order =
                    Comparator.comparing(
                                    ElementChange::type, Comparator.nullsFirst(String::compareTo))
                            .thenComparing(
                                    ElementChange::name, Comparator.nullsFirst(String::compareTo))
                            .thenComparing(
                                    ElementChange::id, Comparator.nullsFirst(String::compareTo));
            created.sort(order);
            edited.sort(order);
            deleted.sort(order);
            int createdCount = created.size(),
                    editedCount = edited.size(),
                    deletedCount = deleted.size();
            // Prefer user-facing created/deleted elements, then edited containers and relations.
            int remaining = MAX_DISPLAY_ITEMS;
            created = sample(created, remaining);
            remaining -= created.size();
            deleted = sample(deleted, remaining);
            remaining -= deleted.size();
            edited = sample(edited, remaining);
            return new ChangeReport(
                    true,
                    true,
                    createdCount,
                    editedCount,
                    deletedCount,
                    created,
                    edited,
                    deleted,
                    createdCount + editedCount + deletedCount > MAX_DISPLAY_ITEMS,
                    saveFailed
                            ? "Committed in memory; saving failed. Do not repeat the script."
                            : "Includes semantic elements and diagrams; changes inside a diagram"
                                    + " are reported as that diagram. Disk save status is reported"
                                    + " separately.",
                    byLayer);
        }

        private ChangeReport unknown(boolean committed) {
            return new ChangeReport(
                    false,
                    committed,
                    -1,
                    -1,
                    -1,
                    List.of(),
                    List.of(),
                    List.of(),
                    true,
                    note.isBlank()
                            ? "The recorder was closed before an execution outcome was supplied."
                            : note,
                    Map.of());
        }

        private void unavailable(String reason) {
            available = false;
            note = reason;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            try {
                SessionSafeguards.runOnUiThread(this::detach);
            } catch (RuntimeException dispatchFailure) {
                // Workbench shutdown may dispose SWT before the worker finishes.
                // Adapter removal changes no model features and must still release references.
                try {
                    detach();
                } catch (RuntimeException cleanupFailure) {
                    unavailable(
                            "Change capture cleanup could not complete: "
                                    + Text.oneLine(cleanupFailure.getMessage(), 240));
                }
            } finally {
                closed = true;
            }
        }

        private void detach() {
            active = false;
            for (Resource resource : resources) resource.eAdapters().remove(adapter);
            // Also detach any object removed/reparented while EContentAdapter was observing it.
            for (EObject object : new ArrayList<>(attachedObjects))
                object.eAdapters().remove(adapter);
            attachedObjects.clear();
            baseline.clear();
            touched.clear();
        }
    }

    private static final class Touch {
        private final ElementChange original;
        private ElementChange lastDetached;
        private final EObject parent;
        private final EStructuralFeature containment;
        private final Resource resource;
        private final Map<EStructuralFeature, FeatureValue> features = new IdentityHashMap<>();

        private Touch(
                ElementChange original,
                EObject parent,
                EStructuralFeature containment,
                Resource resource) {
            this.original = original;
            this.parent = parent;
            this.containment = containment;
            this.resource = resource;
        }
    }

    private record FeatureValue(Object value, Boolean wasSet) {
        private static FeatureValue before(
                EObject object, EStructuralFeature feature, Notification event) {
            Object original = event.getOldValue();
            if (feature.isMany()) {
                Object current = readValue(object, feature);
                List<Object> values =
                        new ArrayList<>(
                                current instanceof Collection<?> collection
                                        ? collection
                                        : List.of());
                int position = event.getPosition();
                switch (event.getEventType()) {
                    case Notification.ADD -> remove(values, position, event.getNewValue());
                    case Notification.ADD_MANY -> {
                        Collection<?> added = (Collection<?>) event.getNewValue();
                        if (position >= 0)
                            for (int i = 0; i < added.size(); i++) values.remove(position);
                        else for (Object value : added) values.remove(value);
                    }
                    case Notification.REMOVE ->
                            values.add(
                                    position < 0 ? values.size() : position, event.getOldValue());
                    case Notification.REMOVE_MANY -> {
                        List<?> removed = new ArrayList<>((Collection<?>) event.getOldValue());
                        if (event.getNewValue() instanceof int[] positions) {
                            for (int i = 0; i < positions.length; i++)
                                values.add(positions[i], removed.get(i));
                        } else values.addAll(Math.max(0, position), removed);
                    }
                    case Notification.MOVE -> {
                        Object moved = values.remove(position);
                        values.add(((Number) event.getOldValue()).intValue(), moved);
                    }
                    case Notification.SET -> {
                        if (position >= 0) values.set(position, event.getOldValue());
                        else {
                            values.clear();
                            if (event.getOldValue() instanceof Collection<?> previous)
                                values.addAll(previous);
                        }
                    }
                    case Notification.UNSET -> {
                        values.clear();
                        if (event.getOldValue() instanceof Collection<?> previous)
                            values.addAll(previous);
                    }
                    default ->
                            throw new IllegalStateException(
                                    "Unsupported feature notification " + event.getEventType());
                }
                original = values;
            }
            return new FeatureValue(original, feature.isUnsettable() ? event.wasSet() : null);
        }

        private boolean matches(EObject object, EStructuralFeature feature) {
            Object current = readValue(object, feature);
            return java.util.Objects.equals(value, current)
                    && (wasSet == null || wasSet.booleanValue() == object.eIsSet(feature));
        }

        private static void remove(List<Object> values, int position, Object value) {
            if (position >= 0) values.remove(position);
            else values.remove(value);
        }
    }

    private static Object readValue(EObject object, EStructuralFeature feature) {
        Object value = object.eGet(feature, false);
        if (!feature.isMany()) return value;
        List<Object> result = new ArrayList<>();
        var iterator =
                value instanceof InternalEList<?> internal
                        ? internal.basicIterator()
                        : ((Collection<?>) value).iterator();
        while (iterator.hasNext()) result.add(iterator.next());
        return result;
    }

    /**
     * Sirius views, styles, GMF notation and Ecore annotation data: diagram internals, not elements
     * a reviewer names.
     */
    private static boolean representationInternal(EObject object) {
        if (object instanceof org.eclipse.sirius.viewpoint.DRepresentation
                || object instanceof org.eclipse.sirius.viewpoint.DRepresentationDescriptor)
            return false;
        String namespace = object.eClass().getEPackage().getNsURI();
        return namespace != null
                && (namespace.startsWith("http://www.eclipse.org/sirius")
                        || namespace.startsWith("http://www.eclipse.org/gmf")
                        || namespace.equals(org.eclipse.emf.ecore.EcorePackage.eNS_URI));
    }

    private static EObject representationOf(EObject object) {
        for (EObject current = object; current != null; current = current.eContainer()) {
            if (current instanceof org.eclipse.sirius.viewpoint.DRepresentation) return current;
            // GMF notation is held by an annotation entry on the representation.
            if (current instanceof org.eclipse.gmf.runtime.notation.View view
                    && view.getElement()
                            instanceof org.eclipse.sirius.viewpoint.DRepresentation representation
                    && current.eContainer() == null) return representation;
        }
        return null;
    }

    private static ElementChange describe(EObject object, Resource resource, Object previousName) {
        String id = null,
                name =
                        previousName == null
                                ? null
                                : Text.oneLine(String.valueOf(previousName), 180);
        EAttribute identifier = object.eClass().getEIDAttribute();
        if (identifier != null) {
            Object value = object.eGet(identifier, false);
            if (value != null) id = Text.oneLine(String.valueOf(value), 100);
        } else if (resource instanceof XMLResource xml) id = Text.oneLine(xml.getID(object), 100);
        if (name == null)
            for (String candidate : List.of("name", "ReqIFLongName", "ReqIFIdentifier")) {
                EStructuralFeature feature = object.eClass().getEStructuralFeature(candidate);
                if (feature instanceof EAttribute
                        && !feature.isDerived()
                        && !feature.isTransient()) {
                    Object value = object.eGet(feature, false);
                    if (value instanceof String text && !text.isBlank()) {
                        name = Text.oneLine(text, 180);
                        break;
                    }
                }
            }
        if (name == null
                && object instanceof org.eclipse.sirius.viewpoint.DRepresentation representation) {
            // Sirius keeps a diagram's name on its descriptor.
            try {
                var descriptor =
                        new org.eclipse.sirius.business.api.query.DRepresentationQuery(
                                        representation)
                                .getRepresentationDescriptor();
                if (descriptor != null && descriptor.getName() != null)
                    name = Text.oneLine(descriptor.getName(), 180);
            } catch (RuntimeException unavailable) {
                /* keep the unnamed fallback */
            }
        }
        return new ElementChange(id, Text.oneLine(object.eClass().getName(), 100), name, List.of());
    }

    private static ElementChange withFeatures(ElementChange value, List<String> features) {
        return new ElementChange(
                value.id(),
                value.type(),
                value.name(),
                features.stream()
                        .distinct()
                        .sorted()
                        .limit(10)
                        .map(feature -> Text.oneLine(feature, 80))
                        .toList());
    }

    private static <T> List<T> sample(List<T> values, int max) {
        return List.copyOf(values.subList(0, Math.min(values.size(), Math.max(0, max))));
    }

    private static void visitValues(Object value, java.util.function.Consumer<EObject> consumer) {
        if (value instanceof EObject object) consumer.accept(object);
        else if (value instanceof Collection<?> collection)
            for (Object item : collection) {
                if (item instanceof EObject object) consumer.accept(object);
            }
    }

    private static <T> Set<T> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static final int DIGEST_THRESHOLD = 10;
    private static final List<String> LAYER_ORDER =
            List.of("OA", "SA", "LA", "PA", "EPBS", "Other", "Diagrams");

    /**
     * Perspective of an element, or of its former container for a deleted one; view data counts as
     * Diagrams.
     */
    private static String layer(EObject object, Resource resource) {
        for (EObject current = object; current != null; current = current.eContainer()) {
            switch (current.eClass().getName()) {
                case "OperationalAnalysis":
                    return "OA";
                case "SystemAnalysis":
                    return "SA";
                case "LogicalArchitecture":
                    return "LA";
                case "PhysicalArchitecture":
                    return "PA";
                case "EPBSArchitecture":
                    return "EPBS";
                default:
            }
        }
        return resource != null
                        && resource.getURI() != null
                        && "aird".equals(resource.getURI().fileExtension())
                ? "Diagrams"
                : "Other";
    }

    private static void count(
            Map<String, Map<String, Map<String, Integer>>> byLayer,
            String layer,
            String change,
            String type) {
        byLayer.computeIfAbsent(layer, key -> new LinkedHashMap<>())
                .computeIfAbsent(change, key -> new java.util.TreeMap<>())
                .merge(type, 1, Integer::sum);
    }

    private static void appendDigest(
            StringBuilder text, Map<String, Map<String, Map<String, Integer>>> byLayer) {
        if (byLayer.isEmpty()) return;
        text.append("\n\nBy perspective:");
        byLayer.forEach(
                (layer, changes) -> {
                    text.append("\n- ").append(layer).append(": ");
                    List<String> parts = new ArrayList<>();
                    for (String change : List.of("created", "edited", "deleted")) {
                        Map<String, Integer> types = changes.get(change);
                        if (types == null) continue;
                        int total = types.values().stream().mapToInt(Integer::intValue).sum();
                        parts.add(
                                total
                                        + " "
                                        + change
                                        + " ("
                                        + types.entrySet().stream()
                                                .sorted(
                                                        Map.Entry
                                                                .<String, Integer>comparingByValue()
                                                                .reversed()
                                                                .thenComparing(
                                                                        Map.Entry.comparingByKey()))
                                                .limit(6)
                                                .map(
                                                        type ->
                                                                plain(type.getKey())
                                                                        + " "
                                                                        + type.getValue())
                                                .collect(java.util.stream.Collectors.joining(", "))
                                        + (types.size() > 6 ? ", ..." : "")
                                        + ")");
                    }
                    text.append(String.join("; ", parts));
                });
    }

    private static void appendItems(StringBuilder text, String label, List<ElementChange> items) {
        if (items.isEmpty()) return;
        text.append("\n\n").append(label).append(':');
        for (ElementChange item : items) {
            text.append("\n- ").append(plain(item.type())).append(" ").append(plain(item.name()));
            if (item.id() != null) text.append(" · ").append(plain(item.id()));
            if (!item.features().isEmpty())
                text.append(" (").append(String.join(", ", item.features())).append(')');
        }
    }

    private static String plain(String text) {
        return text == null
                ? "(unnamed)"
                : text.replace("\\", "\\\\")
                        .replace("*", "\\*")
                        .replace("`", "\\`")
                        .replace("[", "\\[")
                        .replace("]", "\\]");
    }
}
