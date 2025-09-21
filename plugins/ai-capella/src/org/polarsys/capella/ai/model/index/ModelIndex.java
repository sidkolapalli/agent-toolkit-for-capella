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
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.sirius.business.api.session.Session;
import org.polarsys.capella.ai.model.ArchitectureLocator;
import org.polarsys.capella.ai.session.SessionBridge;

/** Builds a lightweight search index over Capella model elements. */
public final class ModelIndex {
    private static final long CACHE_TTL_MS = 10_000L;

    private static final Map<Session, CacheEntry> CACHE = new WeakHashMap<>();

    private ModelIndex() {}

    private static final class CacheEntry {
        final long timestamp;
        final List<IndexedElement> elements;

        CacheEntry(List<IndexedElement> elements) {
            this.timestamp = System.currentTimeMillis();
            this.elements = elements;
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

        IndexedElement(String name, String type, String layer, String uuid, String path, String summary) {
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

    public static synchronized List<IndexedElement> search(Session session, String query, int limit) {
        if (session == null || query == null || query.isBlank()) {
            return List.of();
        }
        List<IndexedElement> index = ensureIndex(session);
        String[] tokens = query.toLowerCase(Locale.ROOT).split("\\s+");
        return index.stream()
                .map(element -> new ScoredElement(element, score(element, tokens)))
                .filter(scored -> scored.score > 0)
                .sorted(Comparator.comparingInt((ScoredElement s) -> -s.score)
                        .thenComparing(s -> s.element.name))
                .limit(limit)
                .map(scored -> scored.element)
                .collect(Collectors.toList());
    }

    /** Clears cached index for a session. */
    public static synchronized void invalidate(Session session) {
        if (session != null) {
            CACHE.remove(session);
        }
    }

    private static List<IndexedElement> ensureIndex(Session session) {
        CacheEntry entry = CACHE.get(session);
        long now = System.currentTimeMillis();
        if (entry != null && now - entry.timestamp < CACHE_TTL_MS) {
            return entry.elements;
        }
        List<IndexedElement> rebuilt = rebuild(session);
        CACHE.put(session, new CacheEntry(rebuilt));
        return rebuilt;
    }

    private static List<IndexedElement> rebuild(Session session) {
        ResourceSet resourceSet = session.getTransactionalEditingDomain() != null
                ? session.getTransactionalEditingDomain().getResourceSet()
                : null;
        if (resourceSet == null) {
            return List.of();
        }
        List<IndexedElement> elements = new ArrayList<>();
        Map<EObject, String> layerCache = new ConcurrentHashMap<>();
        for (Resource resource : resourceSet.getResources()) {
            if (resource == null || resource.getContents().isEmpty()) {
                continue;
            }
            for (EObject root : resource.getContents()) {
                visit(root, eo -> {
                    IndexedElement element = indexElement(eo, layerCache, resourceSet);
                    if (element != null) {
                        elements.add(element);
                    }
                });
            }
        }
        return elements;
    }

    private static void visit(EObject root, Consumer<EObject> consumer) {
        var iterator = EcoreUtil.getAllProperContents(root, true);
        while (iterator.hasNext()) {
            consumer.accept((EObject) iterator.next());
        }
    }

    private static IndexedElement indexElement(EObject eo, Map<EObject, String> layerCache, ResourceSet resourceSet) {
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
        EStructuralFeature feature = eo.eClass().getEStructuralFeature(featureName);
        if (feature != null) {
            Object value = eo.eGet(feature);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private static String resolveLayer(EObject eo, Map<EObject, String> cache, ResourceSet resourceSet) {
        EObject root = EcoreUtil.getRootContainer(eo);
        return cache.computeIfAbsent(root, key -> {
            for (String candidate : List.of("OA", "SA", "LA", "PA")) {
                EObject layerRoot = ArchitectureLocator.findTargetElement(resourceSet, candidate);
                if (layerRoot != null && EcoreUtil.isAncestor(layerRoot, eo)) {
                    return candidate;
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

    private static String composeSummary(String name, String type, String layer, String path) {
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
