package com.sidkolapalli.capella.ai.model;

import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.common.notify.impl.AdapterImpl;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.emf.ecore.xmi.XMLResource;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Keeps an EObject's intrinsic ID and its resource's serialized-reference index in sync. */
public final class PersistentIds {
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._:~-]*");

    private PersistentIds() {}

    /**
     * Call inside the existing model write transaction, instead of setting the ID feature directly.
     */
    public static void setId(EObject element, String id) {
        if (element == null) {
            throw new IllegalArgumentException(
                    "An element is required when assigning a persistent ID");
        }
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "A persistent ID must be nonblank and contain only URI-safe ID characters");
        }
        EAttribute attribute = idAttribute(element);
        rejectCollision(element, id);
        install(element, attribute);
        element.eSet(attribute, id);
        // A no-op assignment may not notify adapters; it must still repair a stale existing index.
        synchronize(element, attribute);
    }

    /**
     * Install before an ID feature mutation so command undo/redo also updates the resource index.
     */
    public static void install(EObject element) {
        if (element == null) {
            throw new IllegalArgumentException(
                    "An element is required when installing persistent ID support");
        }
        install(element, idAttribute(element));
    }

    private static EAttribute idAttribute(EObject element) {
        EAttribute attribute = element.eClass().getEIDAttribute();
        if (attribute == null
                || !attribute.isChangeable()
                || attribute.isMany()
                || attribute.getEAttributeType().getInstanceClass() != String.class) {
            throw new IllegalArgumentException(
                    "The element must have a writable intrinsic String ID attribute");
        }
        return attribute;
    }

    private static void install(EObject element, EAttribute attribute) {
        for (var adapter : element.eAdapters()) {
            if (adapter instanceof IdIndexAdapter indexAdapter
                    && indexAdapter.attribute == attribute) {
                return;
            }
        }
        element.eAdapters().add(new IdIndexAdapter(attribute));
    }

    private static void rejectCollision(EObject element, String id) {
        Resource owner = element.eResource();
        if (owner == null) {
            return; // Detached objects receive their resource index when they are attached.
        }
        if (owner.getResourceSet() == null) {
            rejectCollision(owner, element, id);
        } else {
            for (Resource resource : owner.getResourceSet().getResources()) {
                rejectCollision(resource, element, id);
            }
        }
    }

    private static void rejectCollision(Resource resource, EObject element, String id) {
        EObject other;
        try {
            other = resource.getEObject(id);
        } catch (IllegalArgumentException unsupportedFragment) {
            return;
        }
        if (other != null && other != element) {
            throw new IllegalArgumentException(
                    "Persistent ID already belongs to another element: " + id);
        }
    }

    private static void synchronize(EObject element, EAttribute attribute) {
        Resource resource = element.eResource();
        if (resource == null) {
            return;
        }
        String id = (String) element.eGet(attribute, false);
        if (resource instanceof XMLResource xml) {
            // Native Capella attachedHelper indexes the original ID. An attribute notification
            // alone
            // does not change this map, which XML serialization uses for cross references.
            xml.setID(element, id);
        } else if (resource instanceof ResourceImpl implementation) {
            Map<String, EObject> index = implementation.getIntrinsicIDToEObjectMap();
            if (index != null) {
                index.entrySet()
                        .removeIf(
                                entry ->
                                        entry.getValue() == element
                                                && !Objects.equals(entry.getKey(), id));
                if (id != null) {
                    index.put(id, element);
                }
            }
        }
    }

    private static final class IdIndexAdapter extends AdapterImpl {
        private final EAttribute attribute;

        private IdIndexAdapter(EAttribute attribute) {
            this.attribute = attribute;
        }

        @Override
        public void notifyChanged(Notification notification) {
            if (notification.getNotifier() instanceof EObject element
                    && notification.getFeature() == attribute
                    && (notification.getEventType() == Notification.SET
                            || notification.getEventType() == Notification.UNSET)) {
                synchronize(element, attribute);
            }
        }
    }
}
