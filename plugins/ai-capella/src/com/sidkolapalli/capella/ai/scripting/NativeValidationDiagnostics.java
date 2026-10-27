package com.sidkolapalli.capella.ai.scripting;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.osgi.framework.Bundle;
import org.polarsys.capella.core.data.capellacore.CapellaElement;
import org.polarsys.capella.core.data.capellacore.NamedElement;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Optional, scalar metadata from the installed EMF validation contracts, without another bundle
 * dependency.
 */
public final class NativeValidationDiagnostics {
    private static final int MAX_NAME = 2400;
    private static final int MAX_ID = 4096;
    private final Contracts contracts;

    /**
     * Resolve public interface methods once, through the same bundle used for native validation.
     */
    public NativeValidationDiagnostics(Bundle validationBundle) {
        Contracts resolved = null;
        if (validationBundle != null) {
            try {
                Class<?> status =
                        validationBundle.loadClass(
                                "org.eclipse.emf.validation.model.IConstraintStatus");
                Class<?> constraint =
                        validationBundle.loadClass(
                                "org.eclipse.emf.validation.model.IModelConstraint");
                Class<?> descriptor =
                        validationBundle.loadClass(
                                "org.eclipse.emf.validation.service.IConstraintDescriptor");
                resolved =
                        new Contracts(
                                status,
                                status.getMethod("getTarget"),
                                status.getMethod("getConstraint"),
                                constraint.getMethod("getDescriptor"),
                                descriptor.getMethod("getId"),
                                descriptor.getMethod("getName"));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                // Native statuses remain useful when an optional metadata contract is unavailable.
            }
        }
        contracts = resolved;
    }

    /**
     * Preserve the status verbatim and add exact identities when optional native metadata is
     * accessible.
     */
    public Map<String, Object> message(IStatus issue) {
        Objects.requireNonNull(issue, "Native validation status required");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("severity", issue.getSeverity());
        result.put("message", issue.getMessage());
        result.put("plugin", issue.getPlugin());
        result.put("code", issue.getCode());
        if (contracts == null) {
            result.put("metadata_unavailable", true);
            return result;
        }
        if (!contracts.statusType().isInstance(issue)) return result;
        Map<String, Object> optional = new LinkedHashMap<>();
        try {
            Object value = contracts.target().invoke(issue);
            if (value instanceof EObject target && !target.eIsProxy()) {
                identity(
                        optional,
                        "target_id",
                        target instanceof CapellaElement element
                                ? element.getId()
                                : EcoreUtil.getID(target));
                optional.put("target_type", bounded(target.eClass().getName()));
                if (target instanceof NamedElement named && named.getName() != null)
                    optional.put("target_name", bounded(named.getName()));
            } else {
                // Do not resolve a proxy, traverse resources or infer an ID from its URI.
                optional.put("metadata_unavailable", true);
            }
            Object constraint = contracts.constraint().invoke(issue);
            Object descriptor =
                    constraint == null ? null : contracts.descriptor().invoke(constraint);
            if (descriptor == null) {
                optional.put("metadata_unavailable", true);
            } else {
                identity(
                        optional,
                        "constraint_id",
                        (String) contracts.descriptorId().invoke(descriptor));
                Object name = contracts.descriptorName().invoke(descriptor);
                if (name instanceof String text) optional.put("constraint_name", bounded(text));
            }
            result.putAll(optional);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            // Do not replace the diagnostic or publish raw exceptions containing resource/provider
            // details.
            result.put("metadata_unavailable", true);
        }
        return result;
    }

    private static void identity(Map<String, Object> metadata, String key, String value) {
        if (value != null && !value.isBlank() && value.length() <= MAX_ID) metadata.put(key, value);
        else metadata.put("metadata_unavailable", true);
    }

    private static String bounded(String value) {
        if (value.length() <= MAX_NAME) return value;
        int end = MAX_NAME - 1;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end) + "…";
    }

    private record Contracts(
            Class<?> statusType,
            Method target,
            Method constraint,
            Method descriptor,
            Method descriptorId,
            Method descriptorName) {}
}
