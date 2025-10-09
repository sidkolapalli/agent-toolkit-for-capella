package org.polarsys.capella.ai.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.sirius.business.api.session.Session;

/** Lightweight model change-set derivation (uuid/name/class) for review/telemetry. */
public final class ModelChangeSet {
    private ModelChangeSet() {}

    public static Snapshot snapshot(Session session) {
        if (session == null || session.getTransactionalEditingDomain() == null) return new Snapshot(Map.of());
        return snapshot(session.getTransactionalEditingDomain().getResourceSet());
    }

    public static Snapshot snapshot(ResourceSet rs) {
        Map<String, Item> items = new HashMap<>();
        try {
            for (Resource res : rs.getResources()) {
                if (res == null) continue;
                var it = res.getAllContents();
                while (it.hasNext()) {
                    EObject eo = it.next();
                    String uuid = idOf(eo);
                    if (uuid == null || uuid.isBlank()) continue;
                    String cls = eo.eClass().getName();
                    String name = nameOf(eo);
                    items.put(uuid, new Item(uuid, cls, name));
                }
            }
        } catch (Exception ignore) {}
        return new Snapshot(items);
    }

    public static ChangeSet diff(Snapshot before, Snapshot after) {
        Map<String, Item> a = before.items;
        Map<String, Item> b = after.items;
        List<Item> created = new ArrayList<>();
        List<Item> deleted = new ArrayList<>();
        List<Rename> renamed = new ArrayList<>();
        for (Map.Entry<String, Item> e : b.entrySet()) {
            if (!a.containsKey(e.getKey())) {
                created.add(e.getValue());
            }
        }
        for (Map.Entry<String, Item> e : a.entrySet()) {
            if (!b.containsKey(e.getKey())) {
                deleted.add(e.getValue());
            } else {
                Item ai = e.getValue();
                Item bi = b.get(e.getKey());
                if (!safe(ai.name).equals(safe(bi.name))) {
                    renamed.add(new Rename(e.getKey(), safe(ai.name), safe(bi.name), safe(bi.cls)));
                }
            }
        }
        return new ChangeSet(created, deleted, renamed);
    }

    public static final class Snapshot {
        final Map<String, Item> items;
        Snapshot(Map<String, Item> items) { this.items = Map.copyOf(items); }
    }

    public static final class Item {
        public final String uuid; public final String cls; public final String name;
        Item(String uuid, String cls, String name) { this.uuid = uuid; this.cls = cls; this.name = name; }
    }

    public static final class Rename {
        public final String uuid; public final String from; public final String to; public final String cls;
        Rename(String uuid, String from, String to, String cls) { this.uuid = uuid; this.from = from; this.to = to; this.cls = cls; }
    }

    public static final class ChangeSet {
        public final List<Item> created; public final List<Item> deleted; public final List<Rename> renamed;
        ChangeSet(List<Item> created, List<Item> deleted, List<Rename> renamed) {
            this.created = List.copyOf(created);
            this.deleted = List.copyOf(deleted);
            this.renamed = List.copyOf(renamed);
        }
        public String summary() {
            return String.format("created=%d deleted=%d renamed=%d", created.size(), deleted.size(), renamed.size());
        }
    }

    private static String idOf(EObject eo) {
        try {
            var f = eo.eClass().getEStructuralFeature("uuid");
            if (f != null) { Object v = eo.eGet(f); if (v != null) return String.valueOf(v); }
            var uri = org.eclipse.emf.ecore.util.EcoreUtil.getURI(eo);
            if (uri != null && uri.fragment() != null) return uri.fragment();
        } catch (Exception ignore) {}
        return null;
    }

    private static String nameOf(EObject eo) {
        try {
            var f = eo.eClass().getEStructuralFeature("name");
            if (f != null) { Object v = eo.eGet(f); if (v != null) return String.valueOf(v); }
        } catch (Exception ignore) {}
        return "";
    }

    private static String safe(String s) { return s == null ? "" : s; }
}

