package com.sidkolapalli.capella.ai.scripting;

import static org.junit.Assert.*;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.InternalEObject;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.polarsys.capella.core.data.ctx.CtxFactory;
import org.polarsys.capella.core.data.ctx.SystemFunction;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Uses the actual installed ConstraintStatus and Capella targets; metadata contracts are test
 * proxies.
 */
public class NativeValidationDiagnosticsTest {
    @Test
    public void sameNamedTargetsRetainDistinctNativeIdentitiesAndDescriptorIdIsNotStatusCode()
            throws Exception {
        var formatter = new NativeValidationDiagnostics(validationBundle(new AtomicInteger()));
        SystemFunction first =
                function("6b847ab9-1c99-4c35-a9cb-24f0e8ba2643", "Repeated function name");
        SystemFunction second =
                function("5296405d-f960-4490-bb80-ce0b2d53db5b", "Repeated function name");
        Map<String, Object> one =
                formatter.message(status(first, "fixture.constraint.rule", "Fixture rule", false));
        Map<String, Object> two =
                formatter.message(status(second, "fixture.constraint.rule", "Fixture rule", false));
        assertEquals(first.getId(), one.get("target_id"));
        assertEquals(second.getId(), two.get("target_id"));
        assertNotEquals(one.get("target_id"), two.get("target_id"));
        assertEquals("SystemFunction", one.get("target_type"));
        assertEquals("Repeated function name", one.get("target_name"));
        assertEquals("fixture.constraint.rule", one.get("constraint_id"));
        assertEquals("Fixture rule", one.get("constraint_name"));
        assertEquals(77, one.get("code"));
        assertEquals("fixture.validation", one.get("plugin"));
        assertEquals("Native warning text", one.get("message"));
        assertEquals(IStatus.WARNING, one.get("severity"));
        assertFalse(one.containsKey("metadata_unavailable"));
        assertTrue(one.values().stream().noneMatch(value -> value instanceof EObject));
    }

    @Test
    public void ordinaryStatusRetainsExactlyItsOriginalFields() {
        IStatus plain =
                new Status(IStatus.WARNING, "plain.plugin", 18, "Ordinary diagnostic", null);
        assertEquals(
                Map.of(
                        "severity",
                        IStatus.WARNING,
                        "message",
                        "Ordinary diagnostic",
                        "plugin",
                        "plain.plugin",
                        "code",
                        18),
                new NativeValidationDiagnostics(validationBundle(new AtomicInteger()))
                        .message(plain));
    }

    @Test
    public void throwingDescriptorOmitsOptionalMetadataWithoutReplacingStatusOrLeakingException()
            throws Exception {
        IStatus nativeStatus =
                status(
                        function("f410658b-ae30-4f4d-b201-b0b4aa534196", "Function"),
                        "fixture.rule",
                        "Fixture rule",
                        true);
        Map<String, Object> result =
                new NativeValidationDiagnostics(validationBundle(new AtomicInteger()))
                        .message(nativeStatus);
        assertEquals(IStatus.WARNING, result.get("severity"));
        assertEquals(77, result.get("code"));
        assertEquals("Native warning text", result.get("message"));
        assertEquals(true, result.get("metadata_unavailable"));
        assertEquals(5, result.size());
        assertFalse(result.toString().contains("sensitive-fixture-exception"));
    }

    @Test
    public void unavailableContractStillReturnsNativeDiagnostic() {
        Bundle unavailable =
                (Bundle)
                        Proxy.newProxyInstance(
                                Bundle.class.getClassLoader(),
                                new Class<?>[] {Bundle.class},
                                (proxy, method, arguments) -> {
                                    if (method.getName().equals("loadClass"))
                                        throw new ClassNotFoundException(
                                                "private-resource-details");
                                    return null;
                                });
        Map<String, Object> result =
                new NativeValidationDiagnostics(unavailable)
                        .message(new Status(IStatus.ERROR, "native.plugin", "Native error"));
        assertEquals(IStatus.ERROR, result.get("severity"));
        assertEquals("Native error", result.get("message"));
        assertEquals(true, result.get("metadata_unavailable"));
        assertFalse(result.toString().contains("private-resource-details"));
    }

    @Test
    public void reflectedPublicContractsAreLoadedOnceAndReused() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        NativeValidationDiagnostics formatter =
                new NativeValidationDiagnostics(validationBundle(loads));
        assertEquals(3, loads.get());
        IStatus issue =
                status(
                        function("b875f506-6d93-4ce1-99f7-cdd0fa5b2532", "Target"),
                        "fixture.rule",
                        "Rule",
                        false);
        formatter.message(issue);
        formatter.message(issue);
        assertEquals(3, loads.get());
    }

    @Test
    public void nestedMultiStatusLeavesPreserveDiagnosticCountAndTargetIdentity() throws Exception {
        IStatus nativeStatus =
                status(
                        function("c35ac0ac-050a-49e9-a551-2085f03731d5", "Target"),
                        "fixture.rule",
                        "Rule",
                        false);
        IStatus plain = new Status(IStatus.ERROR, "plain.plugin", "Plain error");
        MultiStatus inner =
                new MultiStatus(
                        "group.plugin", 0, new IStatus[] {nativeStatus}, "Inner group", null);
        MultiStatus root =
                new MultiStatus(
                        "group.plugin", 0, new IStatus[] {inner, plain}, "Outer group", null);
        List<IStatus> leaves = new ArrayList<>();
        collect(root, leaves);
        NativeValidationDiagnostics formatter =
                new NativeValidationDiagnostics(validationBundle(new AtomicInteger()));
        var messages = leaves.stream().map(formatter::message).toList();
        assertEquals(2, messages.size());
        assertEquals(
                1,
                messages.stream()
                        .filter(message -> message.get("severity").equals(IStatus.ERROR))
                        .count());
        assertEquals(
                1,
                messages.stream()
                        .filter(message -> message.get("severity").equals(IStatus.WARNING))
                        .count());
        assertEquals("c35ac0ac-050a-49e9-a551-2085f03731d5", messages.get(0).get("target_id"));
        assertEquals("Plain error", messages.get(1).get("message"));
    }

    @Test
    public void proxyTargetIsNotResolvedOrReportedAsAnIdentifiedElement() throws Exception {
        SystemFunction target =
                function("b10f2e52-1d6d-4c7b-a37b-960774a021be", "Unresolved proxy");
        ((InternalEObject) target)
                .eSetProxyURI(URI.createURI("missing:/private-model.capella#unknown-id"));
        Map<String, Object> result =
                new NativeValidationDiagnostics(validationBundle(new AtomicInteger()))
                        .message(status(target, "fixture.rule", "Rule", false));
        assertTrue(target.eIsProxy());
        assertFalse(result.containsKey("target_id"));
        assertFalse(result.containsKey("target_name"));
        assertFalse(result.containsKey("target_type"));
        assertEquals("fixture.rule", result.get("constraint_id"));
        assertEquals(true, result.get("metadata_unavailable"));
        assertFalse(result.toString().contains("private-model"));
    }

    @Test
    public void longDisplayNamesAreBoundedButUuidIdentityIsExact() throws Exception {
        SystemFunction target = function("c49a6fc3-9d2a-4530-afef-c39a4fd3ca57", "x".repeat(3000));
        Map<String, Object> result =
                new NativeValidationDiagnostics(validationBundle(new AtomicInteger()))
                        .message(status(target, "fixture.rule", "r".repeat(3000), false));
        assertEquals(target.getId(), result.get("target_id"));
        assertEquals(2400, ((String) result.get("target_name")).length());
        assertEquals(2400, ((String) result.get("constraint_name")).length());
    }

    @Test
    public void oversizedIdentityIsOmittedRatherThanChangedIntoAnotherIdentity() throws Exception {
        SystemFunction target = function("a".repeat(5000), "Target");
        Map<String, Object> result =
                new NativeValidationDiagnostics(validationBundle(new AtomicInteger()))
                        .message(status(target, "fixture.rule", "Rule", false));
        assertFalse(result.containsKey("target_id"));
        assertEquals("Target", result.get("target_name"));
        assertEquals(true, result.get("metadata_unavailable"));
    }

    private static SystemFunction function(String id, String name) {
        SystemFunction function = CtxFactory.eINSTANCE.createSystemFunction();
        function.setId(id);
        function.setName(name);
        return function;
    }

    private static IStatus status(
            EObject target, String id, String name, boolean throwingDescriptor) throws Exception {
        Class<?> descriptorType =
                contract("org.eclipse.emf.validation.service.IConstraintDescriptor");
        Object descriptor =
                Proxy.newProxyInstance(
                        descriptorType.getClassLoader(),
                        new Class<?>[] {descriptorType},
                        (proxy, method, arguments) ->
                                switch (method.getName()) {
                                    case "getId" -> {
                                        if (throwingDescriptor)
                                            throw new IllegalStateException(
                                                    "sensitive-fixture-exception");
                                        yield id;
                                    }
                                    case "getName" -> name;
                                    case "getPluginId" -> "fixture.validation";
                                    case "isEnabled", "isBatch" -> true;
                                    case "isLive", "isError" -> false;
                                    case "getStatusCode" -> 77;
                                    default -> null;
                                });
        Class<?> constraintType = contract("org.eclipse.emf.validation.model.IModelConstraint");
        Object constraint =
                Proxy.newProxyInstance(
                        constraintType.getClassLoader(),
                        new Class<?>[] {constraintType},
                        (proxy, method, arguments) ->
                                method.getName().equals("getDescriptor") ? descriptor : null);
        return (IStatus)
                contract("org.eclipse.emf.validation.model.ConstraintStatus")
                        .getConstructor(
                                constraintType,
                                EObject.class,
                                int.class,
                                int.class,
                                String.class,
                                Set.class)
                        .newInstance(
                                constraint,
                                target,
                                IStatus.WARNING,
                                77,
                                "Native warning text",
                                Set.of(target));
    }

    private static Bundle validationBundle(AtomicInteger loads) {
        return (Bundle)
                Proxy.newProxyInstance(
                        Bundle.class.getClassLoader(),
                        new Class<?>[] {Bundle.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("loadClass")) {
                                loads.incrementAndGet();
                                return contract((String) arguments[0]);
                            }
                            if (method.getName().equals("toString"))
                                return "Native validation contracts fixture";
                            return null;
                        });
    }

    private static Class<?> contract(String name) throws ClassNotFoundException {
        Bundle installed = Platform.getBundle("org.polarsys.capella.core.validation");
        return installed != null ? installed.loadClass(name) : Class.forName(name);
    }

    private static void collect(IStatus status, List<IStatus> leaves) {
        if (status.isMultiStatus())
            for (IStatus child : status.getChildren()) collect(child, leaves);
        else if (!status.isOK()) leaves.add(status);
    }
}
