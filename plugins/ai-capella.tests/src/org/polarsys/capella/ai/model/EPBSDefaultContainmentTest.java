package org.polarsys.capella.ai.model;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.sirius.business.api.session.Session;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.polarsys.capella.ai.scripting.CapellaAPI;
import org.polarsys.capella.core.data.capellamodeller.CapellamodellerFactory;
import org.polarsys.capella.core.data.cs.CsFactory;
import org.polarsys.capella.core.data.cs.Part;
import org.polarsys.capella.core.data.epbs.ConfigurationItem;
import org.polarsys.capella.core.data.epbs.ConfigurationItemKind;
import org.polarsys.capella.core.data.epbs.EPBSArchitecture;
import org.polarsys.capella.core.data.epbs.EpbsFactory;
import org.polarsys.capella.core.model.helpers.BlockArchitectureExt;

/** Native initialized EPBS containment; only the Sirius Session is a proxy. */
public class EPBSDefaultContainmentTest {
    private Resource resource;
    private TransactionalEditingDomain domain;
    private Session session;
    private CapellaAPI api;
    private EPBSArchitecture epbs;
    private ConfigurationItem product;
    private int saves;

    @Before public void initializeProductBreakdown() {
        var resources = resourceSet();
        resource = resources.createResource(URI.createURI("memory:/epbs-default.xmi"));
        var project = CapellamodellerFactory.eINSTANCE.createProject();
        var engineering = CapellamodellerFactory.eINSTANCE.createSystemEngineering();
        project.getOwnedModelRoots().add(engineering); resource.getContents().add(project);
        epbs = EpbsFactory.eINSTANCE.createEPBSArchitecture(); epbs.setName("EPBS");
        epbs.setOwnedConfigurationItemPkg(EpbsFactory.eINSTANCE.createConfigurationItemPkg());
        engineering.getOwnedArchitectures().add(epbs);
        product = EpbsFactory.eINSTANCE.createConfigurationItem(); product.setName("Product");
        product.setKind(ConfigurationItemKind.SYSTEM_CI);
        epbs.getOwnedConfigurationItemPkg().getOwnedConfigurationItems().add(product);
        Part rootPart = CsFactory.eINSTANCE.createPart(); rootPart.setName(product.getName()); rootPart.setAbstractType(product);
        epbs.getOwnedConfigurationItemPkg().getOwnedParts().add(rootPart);
        domain = TransactionalEditingDomain.Factory.INSTANCE.createEditingDomain(resources);
        session = (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[] {Session.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "isOpen" -> true;
                case "getTransactionalEditingDomain" -> domain;
                case "getSemanticResources" -> List.of(resource);
                case "getSessionResource" -> resource;
                case "save" -> { saves++; yield null; }
                case "equals" -> proxy == arguments[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> null;
            });
        api = new CapellaAPI(session);
    }

    @After public void disposeModel() {
        if (session != null) ModelSummary.invalidate(session);
        if (domain != null) domain.dispose();
    }

    @Test public void defaultCreationExtendsTheUniqueProductAndCreatesItsPartInOneUndoableCommand() {
        ConfigurationItem child = (ConfigurationItem) api.create("EPBS", "ConfigurationItem", "Power subsystem");
        assertSame(product, child.eContainer());
        assertEquals("ownedConfigurationItems", child.eContainingFeature().getName());
        assertEquals(List.of(product), epbs.getOwnedConfigurationItemPkg().getOwnedConfigurationItems());
        assertEquals(1, BlockArchitectureExt.getRootComponents(epbs).size());
        assertEquals(1, epbs.getOwnedConfigurationItemPkg().getOwnedParts().size());
        Part childPart = partOf(child); assertNotNull(childPart); assertSame(product, childPart.eContainer());
        assertEquals(child.getName(), childPart.getName());
        domain.getCommandStack().undo();
        // EMF retains removed objects inside the undo ChangeDescription. They
        // must be absent from semantic containment, rather than have no owner.
        assertFalse(product.getOwnedConfigurationItems().contains(child));
        assertFalse(product.getOwnedFeatures().contains(childPart));
        assertNotSame(resource, child.eResource()); assertNotSame(resource, childPart.eResource());
        assertNull(partOf(child));
        assertEquals(1, BlockArchitectureExt.getRootComponents(epbs).size());
        domain.getCommandStack().redo();
        assertSame(product, child.eContainer()); assertSame(product, childPart.eContainer());
        assertSame(resource, child.eResource()); assertSame(resource, childPart.eResource());
        assertSame(child, childPart.getAbstractType());
        assertSame(childPart, partOf(child));
        assertEquals(1, BlockArchitectureExt.getRootComponents(epbs).size());
    }

    @Test public void inferredLayerCreationAlsoUsesTheExistingProductAndPersistsNativeReferences() throws Exception {
        ConfigurationItem child = (ConfigurationItem) api.create("ConfigurationItem", "Controller CI");
        String childId = child.getId(), partId = partOf(child).getId();
        var output = new ByteArrayOutputStream(); resource.save(output, Map.of());
        var reloaded = resourceSet().createResource(resource.getURI());
        reloaded.load(new ByteArrayInputStream(output.toByteArray()), Map.of());
        assertTrue(reloaded.getErrors().toString(), reloaded.getErrors().isEmpty());
        ConfigurationItem copy = (ConfigurationItem) reloaded.getEObject(childId);
        Part copyPart = (Part) reloaded.getEObject(partId);
        assertNotNull(copy); assertNotNull(copyPart); assertSame(copy.eContainer(), copyPart.eContainer());
        assertSame(copy, copyPart.getAbstractType());
        assertEquals(product.getId(), ((ConfigurationItem) copy.eContainer()).getId());
    }

    @Test public void explicitPackageAndNestedParentsKeepTheirRequestedContainment() {
        var secondRoot = (ConfigurationItem) api.create("ConfigurationItem", "Explicit package item", epbs.getOwnedConfigurationItemPkg());
        assertSame(epbs.getOwnedConfigurationItemPkg(), secondRoot.eContainer());
        var nested = (ConfigurationItem) api.create("ConfigurationItem", "Explicit nested item", product);
        assertSame(product, nested.eContainer()); assertNotNull(partOf(nested));
        assertEquals(2, BlockArchitectureExt.getRootComponents(epbs).size());
    }

    @Test public void ambiguousExistingRootsFailWithoutCreatingAnItemPartSaveOrUndoEntry() {
        api.create("ConfigurationItem", "Second product", epbs.getOwnedConfigurationItemPkg());
        int itemCount = epbs.getOwnedConfigurationItemPkg().getOwnedConfigurationItems().size();
        int partCount = epbs.getOwnedConfigurationItemPkg().getOwnedParts().size();
        int previousSaves = saves; Object undo = domain.getCommandStack().getUndoCommand();
        var error = assertThrows(IllegalArgumentException.class,
            () -> api.create("EPBS", "ConfigurationItem", "Ambiguous default"));
        assertTrue(error.getMessage().contains("multiple root configuration items"));
        assertEquals(itemCount, epbs.getOwnedConfigurationItemPkg().getOwnedConfigurationItems().size());
        assertEquals(partCount, epbs.getOwnedConfigurationItemPkg().getOwnedParts().size());
        assertEquals(previousSaves, saves); assertSame(undo, domain.getCommandStack().getUndoCommand());
        assertTrue(product.getOwnedConfigurationItems().isEmpty());
    }

    @Test public void anEmptyArchitectureCanStillCreateItsFirstProductRoot() {
        var empty = EpbsFactory.eINSTANCE.createEPBSArchitecture();
        empty.setOwnedConfigurationItemPkg(EpbsFactory.eINSTANCE.createConfigurationItemPkg());
        org.polarsys.capella.ai.session.SessionSafeguards.runWrite(session, "Replace EPBS fixture", () -> {
            var engineering = (org.polarsys.capella.core.data.capellamodeller.SystemEngineering) epbs.eContainer();
            engineering.getOwnedArchitectures().remove(epbs); engineering.getOwnedArchitectures().add(empty);
            return null;
        });
        ConfigurationItem first = (ConfigurationItem) api.create("EPBS", "ConfigurationItem", "First product");
        assertSame(empty.getOwnedConfigurationItemPkg(), first.eContainer());
        assertEquals(1, BlockArchitectureExt.getRootComponents(empty).size());
        assertTrue(empty.getOwnedConfigurationItemPkg().getOwnedParts().stream().anyMatch(part -> part.getAbstractType() == first));
    }

    private Part partOf(ConfigurationItem component) {
        return product.getOwnedFeatures().stream().filter(feature -> feature instanceof Part part && part.getAbstractType() == component)
            .map(feature -> (Part) feature).findFirst().orElse(null);
    }
    private static ResourceSetImpl resourceSet() {
        var resources = new ResourceSetImpl();
        resources.getResourceFactoryRegistry().getExtensionToFactoryMap().put("xmi", new XMIResourceFactoryImpl());
        return resources;
    }
}
