package com.sidkolapalli.capella.ai.scripting;

import static org.junit.Assert.*;

import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.polarsys.capella.core.data.cs.BlockArchitecture;
import org.polarsys.capella.core.data.ctx.CtxFactory;
import org.polarsys.capella.core.data.fa.AbstractFunction;
import org.polarsys.capella.core.data.la.LaFactory;
import org.polarsys.capella.core.data.oa.OaFactory;
import org.polarsys.capella.core.data.pa.PaFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.lang.reflect.Method;

import javax.xml.parsers.DocumentBuilderFactory;

/** Checks target selection against Capella's deployed descriptors and real model types. */
public class DiagramTargetIntegrationTest {
    private final DiagramAPI api = new DiagramAPI(null);

    @Test
    public void operationalAbbreviationsMatchNativeDescriptors() throws Exception {
        assertEquals("Operational Entity Blank", description("OAB"));
        assertEquals("Operational Activity Interaction Blank", description("OAIB"));
        nativeDescription("oa", description("OAB"));
        nativeDescription("oa", description("OAIB"));
    }

    @Test
    public void dataFlowArchitectureAndPackageTargetsResolveToRootFunctions() throws Exception {
        for (int layer = 1; layer <= 3; layer++) {
            Fixture fixture = fixture(layer);
            String name = description(dataFlowType(layer));
            EObject fromArchitecture = resolve(name, fixture.architecture);
            EObject fromPackage = resolve(name, fixture.functionPackage);
            assertSame(fixture.rootFunction, fromArchitecture);
            assertSame(fixture.rootFunction, fromPackage);
            assertNativeApplicable(
                    nativeDescription(descriptorFile(layer), name), fromArchitecture);
        }
    }

    @Test
    public void dataFlowPreservesExplicitNestedFunctionsAndCapabilities() throws Exception {
        for (int layer = 1; layer <= 3; layer++) {
            Fixture fixture = fixture(layer);
            AbstractFunction child = function(layer);
            fixture.rootFunction.getOwnedFunctions().add(child);
            String name = description(dataFlowType(layer));
            Element descriptor = nativeDescription(descriptorFile(layer), name);
            assertSame(child, resolve(name, child));
            assertSame(fixture.capability, resolve(name, fixture.capability));
            assertNativeApplicable(descriptor, child);
            assertNativeApplicable(descriptor, fixture.capability);
            if (layer >= 2) {
                assertTrue(
                        descriptor
                                .getAttribute("preconditionExpression")
                                .contains(
                                        "oclIsKindOf("
                                                + (layer == 2
                                                        ? "la::LogicalArchitecture"
                                                        : "pa::PhysicalArchitecture")
                                                + ")"));
            }
        }
    }

    @Test
    public void operationalRootTargetsResolveToNativeEntityAndActivityTargets() throws Exception {
        Fixture fixture = fixture(0);
        String entityName = description("OAB");
        String activityName = description("OAIB");
        EObject entityTarget = resolve(entityName, fixture.architecture);
        assertSame(fixture.entityPackage, entityTarget);
        assertSame(fixture.rootFunction, resolve(activityName, fixture.architecture));
        assertSame(fixture.rootFunction, resolve(activityName, fixture.functionPackage));
        assertSame(fixture.capability, resolve(activityName, fixture.capability));
        AbstractFunction child = function(0);
        fixture.rootFunction.getOwnedFunctions().add(child);
        assertSame(child, resolve(activityName, child));
        assertNativeApplicable(nativeDescription("oa", entityName), entityTarget);
        assertNativeApplicable(nativeDescription("oa", activityName), fixture.rootFunction);
        assertNativeApplicable(nativeDescription("oa", activityName), fixture.capability);
    }

    @Test
    public void wrongLayerAndMissingFunctionRootsCannotBecomeDataFlowTargets() throws Exception {
        Fixture logical = fixture(2);
        assertNull(resolve(description("SDFB"), logical.rootFunction));
        assertNull(resolve(description("PDFB"), logical.capability));
        assertNull(resolve(description("SDFB"), CtxFactory.eINSTANCE.createSystemAnalysis()));
    }

    private String description(String abbreviation) throws Exception {
        Method method =
                DiagramAPI.class.getDeclaredMethod("resolveDiagramDescription", String.class);
        method.setAccessible(true);
        return (String) method.invoke(api, abbreviation);
    }

    private EObject resolve(String description, EObject target) throws Exception {
        Method method =
                DiagramAPI.class.getDeclaredMethod(
                        "resolveTargetForDescription", String.class, EObject.class);
        method.setAccessible(true);
        return (EObject) method.invoke(api, description, target);
    }

    private static Element nativeDescription(String file, String name) throws Exception {
        String path = "description/" + file + ".odesign";
        Bundle bundle = Platform.getBundle("org.polarsys.capella.core.sirius.analysis");
        InputStream stream =
                bundle != null && bundle.getEntry(path) != null
                        ? bundle.getEntry(path).openStream()
                        : DiagramTargetIntegrationTest.class
                                .getClassLoader()
                                .getResourceAsStream(path);
        assertNotNull("Native Capella descriptor resource missing: " + path, stream);
        try (InputStream input = stream) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            NodeList descriptions =
                    factory.newDocumentBuilder()
                            .parse(input)
                            .getElementsByTagName("ownedRepresentations");
            for (int i = 0; i < descriptions.getLength(); i++) {
                Element descriptor = (Element) descriptions.item(i);
                if (name.equals(descriptor.getAttribute("name"))) return descriptor;
            }
        }
        throw new AssertionError("No native Capella descriptor named " + name);
    }

    private static void assertNativeApplicable(Element descriptor, EObject target) {
        String domain = descriptor.getAttribute("domainClass");
        assertTrue(
                "Target " + target.eClass().getName() + " does not match native domain " + domain,
                hasType(target, domain));
        String expression = descriptor.getAttribute("preconditionExpression");
        String targetType =
                "self.oclIsKindOf("
                        + target.eClass().getEPackage().getName()
                        + "::"
                        + target.eClass().getName()
                        + ")";
        assertTrue(
                "Native target precondition excludes " + targetType,
                expression.contains(targetType));
    }

    private static boolean hasType(EObject target, String name) {
        if (target.eClass().getName().equals(name)) return true;
        for (EClass type : target.eClass().getEAllSuperTypes()) {
            if (type.getName().equals(name)) return true;
        }
        return false;
    }

    private static Fixture fixture(int layer) {
        Fixture result = new Fixture();
        String functions;
        String capabilities;
        EObject capabilityPackage;
        switch (layer) {
            case 0:
                result.architecture = OaFactory.eINSTANCE.createOperationalAnalysis();
                result.functionPackage = OaFactory.eINSTANCE.createOperationalActivityPkg();
                result.entityPackage = OaFactory.eINSTANCE.createEntityPkg();
                set(result.architecture, "ownedEntityPkg", result.entityPackage);
                capabilityPackage = OaFactory.eINSTANCE.createOperationalCapabilityPkg();
                result.capability = OaFactory.eINSTANCE.createOperationalCapability();
                functions = "ownedOperationalActivities";
                capabilities = "ownedOperationalCapabilities";
                break;
            case 1:
                result.architecture = CtxFactory.eINSTANCE.createSystemAnalysis();
                result.functionPackage = CtxFactory.eINSTANCE.createSystemFunctionPkg();
                capabilityPackage = CtxFactory.eINSTANCE.createCapabilityPkg();
                result.capability = CtxFactory.eINSTANCE.createCapability();
                functions = "ownedSystemFunctions";
                capabilities = "ownedCapabilities";
                break;
            case 2:
                result.architecture = LaFactory.eINSTANCE.createLogicalArchitecture();
                result.functionPackage = LaFactory.eINSTANCE.createLogicalFunctionPkg();
                capabilityPackage = LaFactory.eINSTANCE.createCapabilityRealizationPkg();
                result.capability = LaFactory.eINSTANCE.createCapabilityRealization();
                functions = "ownedLogicalFunctions";
                capabilities = "ownedCapabilityRealizations";
                break;
            case 3:
                result.architecture = PaFactory.eINSTANCE.createPhysicalArchitecture();
                result.functionPackage = PaFactory.eINSTANCE.createPhysicalFunctionPkg();
                capabilityPackage = LaFactory.eINSTANCE.createCapabilityRealizationPkg();
                result.capability = LaFactory.eINSTANCE.createCapabilityRealization();
                functions = "ownedPhysicalFunctions";
                capabilities = "ownedCapabilityRealizations";
                break;
            default:
                throw new AssertionError(layer);
        }
        result.rootFunction = function(layer);
        set(result.architecture, "ownedFunctionPkg", result.functionPackage);
        set(result.architecture, "ownedAbstractCapabilityPkg", capabilityPackage);
        add(result.functionPackage, functions, result.rootFunction);
        add(capabilityPackage, capabilities, result.capability);
        return result;
    }

    private static AbstractFunction function(int layer) {
        return switch (layer) {
            case 0 -> OaFactory.eINSTANCE.createOperationalActivity();
            case 1 -> CtxFactory.eINSTANCE.createSystemFunction();
            case 2 -> LaFactory.eINSTANCE.createLogicalFunction();
            case 3 -> PaFactory.eINSTANCE.createPhysicalFunction();
            default -> throw new AssertionError(layer);
        };
    }

    private static String dataFlowType(int layer) {
        return switch (layer) {
            case 1 -> "SDFB";
            case 2 -> "LDFB";
            case 3 -> "PDFB";
            default -> throw new AssertionError(layer);
        };
    }

    private static String descriptorFile(int layer) {
        return switch (layer) {
            case 1 -> "context";
            case 2 -> "logical";
            case 3 -> "physical";
            default -> throw new AssertionError(layer);
        };
    }

    private static void set(EObject owner, String feature, EObject value) {
        owner.eSet(owner.eClass().getEStructuralFeature(feature), value);
    }

    @SuppressWarnings("unchecked")
    private static void add(EObject owner, String feature, EObject value) {
        ((EList<EObject>) owner.eGet(owner.eClass().getEStructuralFeature(feature))).add(value);
    }

    private static final class Fixture {
        BlockArchitecture architecture;
        EObject functionPackage;
        EObject entityPackage;
        AbstractFunction rootFunction;
        EObject capability;
    }
}
