package org.polarsys.capella.ai.scripting;

import static org.junit.Assert.*;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.sirius.business.api.session.Session;
import org.junit.Before;
import org.junit.Test;
import org.polarsys.capella.core.data.capellamodeller.CapellamodellerFactory;
import org.polarsys.capella.core.data.cs.BlockArchitecture;
import org.polarsys.capella.core.data.cs.Component;
import org.polarsys.capella.core.data.ctx.CtxFactory;
import org.polarsys.capella.core.data.la.LaFactory;
import org.polarsys.capella.core.data.oa.OaFactory;
import org.polarsys.capella.core.data.pa.PaFactory;

import java.lang.reflect.Proxy;
import java.util.List;

/** Native EClass lookup semantics; no SWT, writes, save or mocked metamodel required. */
public class CapellaAPIFindAllTypeTest {
    private CapellaAPI api;
    private final EObject[] capabilities = new EObject[4];
    private final Component[] actors = new Component[4];
    private final Component[] components = new Component[4];

    @Before
    public void setUp() {
        Resource resource = new ResourceImpl(URI.createURI("memory:/find-all-types.capella"));
        var project = CapellamodellerFactory.eINSTANCE.createProject();
        var engineering = CapellamodellerFactory.eINSTANCE.createSystemEngineering();
        project.getOwnedModelRoots().add(engineering);
        resource.getContents().add(project);
        for (int layer = 0; layer < 4; layer++) {
            engineering.getOwnedArchitectures().add(architecture(layer));
            set(capabilities[layer], "name", "Shared capability name");
            actors[layer].setName("Driver");
            actors[layer].setActor(true);
            components[layer].setName("System responsibility");
            components[layer].setActor(false);
        }
        Session session =
                (Session)
                        Proxy.newProxyInstance(
                                Session.class.getClassLoader(),
                                new Class<?>[] {Session.class},
                                (proxy, method, arguments) ->
                                        switch (method.getName()) {
                                            case "getSemanticResources" -> List.of(resource);
                                            case "hashCode" -> System.identityHashCode(proxy);
                                            case "equals" -> proxy == arguments[0];
                                            default -> null;
                                        });
        api = new CapellaAPI(session);
    }

    @Test
    public void sameNamedOperationalAndSystemCapabilitiesDoNotCollide() {
        assertEquals(List.of(capabilities[1]), api.findAll("Capability"));
        assertEquals(List.of(capabilities[0]), api.findAll("OperationalCapability"));
        assertEquals(
                List.of(capabilities[2], capabilities[3]), api.findAll("CapabilityRealization"));
    }

    @Test
    public void abstractSuperclassQueriesIncludeTheirActualNativeSubtypes() {
        assertEquals(List.of(capabilities), api.findAll("AbstractCapability"));
        List<EObject> found = api.findAll("Component");
        assertEquals(8, found.size());
        for (int layer = 0; layer < 4; layer++) {
            assertTrue(found.contains(actors[layer]));
            assertTrue(found.contains(components[layer]));
        }
    }

    @Test
    public void suffixesAndCaseVariantsCannotMatchUnrelatedNativeTypes() {
        for (String type :
                List.of("lCapability", "ity", "Actor", "capability", " Capability", "", " ")) {
            assertTrue("Unexpected suffix/case match for " + type, api.findAll(type).isEmpty());
        }
        assertTrue(api.findAll(null).isEmpty());
    }

    @Test
    public void documentedActorAliasesFilterNativeActorFlags() {
        String[] aliases = {"OperationalActor", "SystemActor", "LogicalActor", "PhysicalActor"};
        String[] nativeTypes = {
            "Entity", "SystemComponent", "LogicalComponent", "PhysicalComponent"
        };
        for (int layer = 0; layer < 4; layer++) {
            assertEquals(List.of(actors[layer]), api.findAll(aliases[layer]));
            assertEquals(
                    List.of(components[layer], actors[layer]), api.findAll(nativeTypes[layer]));
        }
    }

    @Test
    public void modifyingReturnedListCannotPolluteCachedTypeResults() {
        List<EObject> found = api.findAll("Capability");
        found.clear();
        assertEquals(List.of(capabilities[1]), api.findAll("Capability"));
        assertEquals(List.of(capabilities[0]), api.findAll("OperationalCapability"));
    }

    private BlockArchitecture architecture(int layer) {
        BlockArchitecture architecture;
        EObject capabilityPackage;
        EObject componentPackage;
        String capabilityFeature;
        String componentPackageFeature;
        String componentFeature;
        switch (layer) {
            case 0:
                architecture = OaFactory.eINSTANCE.createOperationalAnalysis();
                capabilityPackage = OaFactory.eINSTANCE.createOperationalCapabilityPkg();
                capabilities[layer] = OaFactory.eINSTANCE.createOperationalCapability();
                componentPackage = OaFactory.eINSTANCE.createEntityPkg();
                components[layer] = OaFactory.eINSTANCE.createEntity();
                actors[layer] = OaFactory.eINSTANCE.createEntity();
                capabilityFeature = "ownedOperationalCapabilities";
                componentPackageFeature = "ownedEntityPkg";
                componentFeature = "ownedEntities";
                break;
            case 1:
                architecture = CtxFactory.eINSTANCE.createSystemAnalysis();
                capabilityPackage = CtxFactory.eINSTANCE.createCapabilityPkg();
                capabilities[layer] = CtxFactory.eINSTANCE.createCapability();
                componentPackage = CtxFactory.eINSTANCE.createSystemComponentPkg();
                components[layer] = CtxFactory.eINSTANCE.createSystemComponent();
                actors[layer] = CtxFactory.eINSTANCE.createSystemComponent();
                capabilityFeature = "ownedCapabilities";
                componentPackageFeature = "ownedSystemComponentPkg";
                componentFeature = "ownedSystemComponents";
                break;
            case 2:
                architecture = LaFactory.eINSTANCE.createLogicalArchitecture();
                capabilityPackage = LaFactory.eINSTANCE.createCapabilityRealizationPkg();
                capabilities[layer] = LaFactory.eINSTANCE.createCapabilityRealization();
                componentPackage = LaFactory.eINSTANCE.createLogicalComponentPkg();
                components[layer] = LaFactory.eINSTANCE.createLogicalComponent();
                actors[layer] = LaFactory.eINSTANCE.createLogicalComponent();
                capabilityFeature = "ownedCapabilityRealizations";
                componentPackageFeature = "ownedLogicalComponentPkg";
                componentFeature = "ownedLogicalComponents";
                break;
            case 3:
                architecture = PaFactory.eINSTANCE.createPhysicalArchitecture();
                capabilityPackage = LaFactory.eINSTANCE.createCapabilityRealizationPkg();
                capabilities[layer] = LaFactory.eINSTANCE.createCapabilityRealization();
                componentPackage = PaFactory.eINSTANCE.createPhysicalComponentPkg();
                components[layer] = PaFactory.eINSTANCE.createPhysicalComponent();
                actors[layer] = PaFactory.eINSTANCE.createPhysicalComponent();
                capabilityFeature = "ownedCapabilityRealizations";
                componentPackageFeature = "ownedPhysicalComponentPkg";
                componentFeature = "ownedPhysicalComponents";
                break;
            default:
                throw new AssertionError(layer);
        }
        set(architecture, "ownedAbstractCapabilityPkg", capabilityPackage);
        set(architecture, componentPackageFeature, componentPackage);
        add(capabilityPackage, capabilityFeature, capabilities[layer]);
        add(componentPackage, componentFeature, components[layer]);
        add(componentPackage, componentFeature, actors[layer]);
        return architecture;
    }

    private static void set(EObject owner, String feature, Object value) {
        owner.eSet(owner.eClass().getEStructuralFeature(feature), value);
    }

    @SuppressWarnings("unchecked")
    private static void add(EObject owner, String feature, EObject value) {
        ((List<EObject>) owner.eGet(owner.eClass().getEStructuralFeature(feature))).add(value);
    }
}
