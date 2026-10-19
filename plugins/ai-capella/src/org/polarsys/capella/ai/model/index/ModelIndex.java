package org.polarsys.capella.ai.model.index;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.session.SessionBridge;

/** Builds a lightweight search index over Capella model elements. */
public final class ModelIndex {

    private static final Map<Session, CacheEntry> CACHE = new WeakHashMap<>();
    private static final Map<Session, Future<?>> INFLIGHT = new WeakHashMap<>();
    private static final ExecutorService INDEX_EXEC =
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "capella-ai-model-index");
            t.setDaemon(true);
            return t;
        });

    private ModelIndex() {}

    private static final class CacheEntry {

        final List<IndexedElement> elements;
        final Map<String, IndexedElement> byUuid;
        final org.eclipse.emf.transaction.ResourceSetListener listener;

        CacheEntry(Session session, List<IndexedElement> elements) {
            this.elements = elements;
            this.byUuid = elements
                .stream()
                .filter(element -> element.uuid != null)
                .collect(
                    Collectors.toConcurrentMap(
                        element -> element.uuid,
                        element -> element,
                        (left, right) -> left
                    )
                );
            // Invalidate the cache on any committed model change
            this.listener =
                new org.eclipse.emf.transaction.ResourceSetListenerImpl() {
                    @Override
                    public void resourceSetChanged(
                        org.eclipse.emf.transaction.ResourceSetChangeEvent event
                    ) {
                        if (
                            org.polarsys.capella.ai.preferences.AdvancedPreferences.isAsyncIndexingEnabled()
                        ) {
                            ModelIndex.rebuildAsync(session);
                        } else {
                            ModelIndex.invalidate(session);
                        }
                    }
                };
        }
    }

    /** Describes an indexed element for display/search. */
    public static final class IndexedElement {

        public final String name;
        public final String type;
        public final String layer;
        public final String uuid;
        public final String path;
        public final String summary;

        IndexedElement(
            String name,
            String type,
            String layer,
            String uuid,
            String path,
            String summary
        ) {
            this.name = name;
            this.type = type;
            this.layer = layer;
            this.uuid = uuid;
            this.path = path;
            this.summary = summary;
        }
    }

    /** Searches the model using substring matching on name/type/path. */
    public static List<IndexedElement> search(String query, int limit) {
        return search(SessionBridge.getActiveSession(), query, limit);
    }

    public static synchronized List<IndexedElement> search(
        Session session,
        String query,
        int limit
    ) {
        if (session == null || query == null || query.isBlank()) {
            return List.of();
        }
        List<IndexedElement> index = ensureIndex(session);
        String[] tokens = query.toLowerCase(Locale.ROOT).split("\\s+");
        return index
            .stream()
            .map(element -> new ScoredElement(element, score(element, tokens)))
            .filter(scored -> scored.score > 0)
            .sorted(
                Comparator.comparingInt((ScoredElement s) ->
                    -s.score
                ).thenComparing(s -> s.element.name)
            )
            .limit(limit)
            .map(scored -> scored.element)
            .collect(Collectors.toList());
    }

    /** Clears cached index for a session. */
    public static synchronized void invalidate(Session session) {
        if (session != null) {
            CacheEntry entry = CACHE.remove(session);
            try {
                if (entry != null) {
                    var domain = session.getTransactionalEditingDomain();
                    if (domain != null && entry.listener != null) {
                        domain.removeResourceSetListener(entry.listener);
                    }
                }
            } catch (Exception ex) {
                System.err.println(
                    "[ModelIndex] Error removing listener during invalidation: " +
                        ex.getMessage()
                );
            }
        }
    }

    /** Rebuilds the index asynchronously while keeping current cache available. */
    public static synchronized void rebuildAsync(Session session) {
        if (session == null) return;
        Future<?> f = INFLIGHT.get(session);
        if (f != null && !f.isDone()) return;
        Future<?> submitted = INDEX_EXEC.submit(() -> {
            try {
                List<IndexedElement> rebuilt;
                var domain = session.getTransactionalEditingDomain();
                if (domain != null) {
                    final List<IndexedElement>[] holder = new List[] {
                        List.of(),
                    };
                    try {
                        domain.runExclusive(() -> holder[0] = rebuild(session));
                    } catch (Exception e) {
                        // Fallback without exclusive lock
                        holder[0] = rebuild(session);
                    }
                    rebuilt = holder[0];
                } else {
                    rebuilt = rebuild(session);
                }
                synchronized (ModelIndex.class) {
                    CacheEntry old = CACHE.get(session);
                    try {
                        if (old != null) {
                            var oldDomain =
                                session.getTransactionalEditingDomain();
                            if (oldDomain != null && old.listener != null) {
                                oldDomain.removeResourceSetListener(
                                    old.listener
                                );
                            }
                        }
                    } catch (Exception ex) {
                        System.err.println(
                            "[ModelIndex] Error removing old listener during rebuild: " +
                                ex.getMessage()
                        );
                    }
                    CacheEntry refreshed = new CacheEntry(session, rebuilt);
                    CACHE.put(session, refreshed);
                    attachListener(session, refreshed);
                }
            } catch (Exception ex) {
                System.err.println(
                    "[ModelIndex] Error during async rebuild: " +
                        ex.getMessage()
                );
            }
        });
        INFLIGHT.put(session, submitted);
    }

    private static CacheEntry ensureEntry(Session session) {
        CacheEntry entry = CACHE.get(session);
        if (entry != null) {
            return entry;
        }
        List<IndexedElement> rebuilt = rebuild(session);
        CacheEntry refreshed = new CacheEntry(session, rebuilt);
        CACHE.put(session, refreshed);
        attachListener(session, refreshed);
        return refreshed;
    }

    private static List<IndexedElement> ensureIndex(Session session) {
        return ensureEntry(session).elements;
    }

    private static void attachListener(Session session, CacheEntry entry) {
        try {
            var domain = session.getTransactionalEditingDomain();
            if (domain != null) {
                domain.addResourceSetListener(entry.listener);
            }
        } catch (Exception ex) {
            System.err.println(
                "[ModelIndex] Error attaching listener: " + ex.getMessage()
            );
        }
    }

    private static List<IndexedElement> rebuild(Session session) {
        if (
            session == null || session.getTransactionalEditingDomain() == null
        ) {
            return List.of();
        }
        // Limit indexing to semantic resources for performance
        Collection<Resource> resources;
        try {
            resources = session.getSemanticResources();
        } catch (Exception ex) {
            resources = null;
        }
        ResourceSet resourceSet = session
            .getTransactionalEditingDomain()
            .getResourceSet();
        if (resources == null || resources.isEmpty()) {
            resources = resourceSet.getResources();
        }
        List<IndexedElement> elements = new ArrayList<>();
        Map<EObject, String> layerCache = new ConcurrentHashMap<>();
        for (Resource resource : resources) {
            if (resource == null || resource.getContents().isEmpty()) {
                continue;
            }
            for (EObject root : resource.getContents()) {
                visit(root, eo -> {
                    IndexedElement element = indexElement(
                        eo,
                        layerCache,
                        resourceSet
                    );
                    if (element != null) {
                        elements.add(element);
                    }
                });
            }
        }
        return elements;
    }

    public static IndexedElement describe(EObject eo) {
        return describe(SessionBridge.getActiveSession(), eo);
    }

    public static synchronized IndexedElement describe(
        Session session,
        EObject eo
    ) {
        if (session == null || eo == null) {
            return null;
        }
        CacheEntry entry = ensureEntry(session);
        String uuid = readFeature(eo, "uuid");
        if (uuid == null || uuid.isBlank()) {
            uuid = EcoreUtil.getURI(eo).fragment();
        }
        if (uuid != null) {
            IndexedElement cached = entry.byUuid.get(uuid);
            if (cached != null) {
                return cached;
            }
        }
        ResourceSet resourceSet = session.getTransactionalEditingDomain() !=
            null
            ? session.getTransactionalEditingDomain().getResourceSet()
            : null;
        if (resourceSet == null) {
            return null;
        }
        Map<EObject, String> layerCache = new ConcurrentHashMap<>();
        IndexedElement generated = indexElement(eo, layerCache, resourceSet);
        if (generated != null && uuid != null) {
            entry.byUuid.put(uuid, generated);
        }
        return generated;
    }

    private static void visit(EObject root, Consumer<EObject> consumer) {
        var iterator = EcoreUtil.getAllProperContents(root, true);
        while (iterator.hasNext()) {
            consumer.accept((EObject) iterator.next());
        }
    }

    private static IndexedElement indexElement(
        EObject eo,
        Map<EObject, String> layerCache,
        ResourceSet resourceSet
    ) {
        String name = readFeature(eo, "name");
        String type = eo.eClass().getName();
        String uuid = readFeature(eo, "uuid");
        if (uuid == null || uuid.isBlank()) {
            uuid = EcoreUtil.getURI(eo).fragment();
        }
        String layer = resolveLayer(eo, layerCache, resourceSet);
        String path = buildPath(eo);
        String summary = composeSummary(name, type, layer, path);
        String label = (name == null || name.isBlank()) ? type : name;
        return new IndexedElement(label, type, layer, uuid, path, summary);
    }

    private static String readFeature(EObject eo, String featureName) {
        EStructuralFeature feature = eo
            .eClass()
            .getEStructuralFeature(featureName);
        if (feature != null) {
            Object value = eo.eGet(feature);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private static String resolveLayer(
        EObject eo,
        Map<EObject, String> cache,
        ResourceSet resourceSet
    ) {
        return cache.computeIfAbsent(eo, key -> {
            for (EObject ancestor = key; ancestor != null; ancestor = ancestor.eContainer()) {
                switch (ancestor.eClass().getName()) {
                    case "OperationalAnalysis": return "OA";
                    case "SystemAnalysis": return "SA";
                    case "LogicalArchitecture": return "LA";
                    case "PhysicalArchitecture": return "PA";
                    case "EPBSArchitecture": return "EPBS";
                    default: break;
                }
            }
            return "";
        });
    }

    private static String buildPath(EObject eo) {
        List<String> segments = new ArrayList<>();
        EObject current = eo;
        while (current != null) {
            String name = readFeature(current, "name");
            if (name == null || name.isBlank()) {
                name = current.eClass().getName();
            }
            segments.add(name);
            current = current.eContainer();
        }
        Collections.reverse(segments);
        return String.join(" / ", segments);
    }

    private static String composeSummary(
        String name,
        String type,
        String layer,
        String path
    ) {
        StringJoiner joiner = new StringJoiner(" · ");
        joiner.add(type);
        if (name != null && !name.isBlank()) {
            joiner.add(name);
        }
        if (layer != null && !layer.isBlank()) {
            joiner.add(layer);
        }
        joiner.add(path);
        return joiner.toString();
    }

    private static int score(IndexedElement element, String[] tokens) {
        int score = 0;
        String name = element.name.toLowerCase(Locale.ROOT);
        String type = element.type.toLowerCase(Locale.ROOT);
        String path = element.path.toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            if (name.contains(token)) {
                score += 5;
            }
            if (type.contains(token)) {
                score += 3;
            }
            if (path.contains(token)) {
                score += 2;
            }
        }
        return score;
    }

    private static final class ScoredElement {

        final IndexedElement element;
        final int score;

        ScoredElement(IndexedElement element, int score) {
            this.element = element;
            this.score = score;
        }
    }
}
