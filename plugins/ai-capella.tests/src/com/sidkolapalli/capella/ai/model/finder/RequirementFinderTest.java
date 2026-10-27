package com.sidkolapalli.capella.ai.model.finder;

import static org.junit.Assert.*;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.sirius.business.api.session.Session;
import org.junit.Assume;
import org.junit.Test;
import com.sidkolapalli.capella.ai.scripting.RequirementsAPI;

import java.lang.reflect.Proxy;

public class RequirementFinderTest {
    private EObject nativeObject(String type, String id, String name) {
        Assume.assumeTrue(new RequirementsAPI(null).available());
        EPackage model = EPackage.Registry.INSTANCE.getEPackage(RequirementsAPI.REQUIREMENTS_NS);
        EObject result = model.getEFactoryInstance().create((EClass) model.getEClassifier(type));
        result.eSet(result.eClass().getEStructuralFeature("ReqIFIdentifier"), id);
        result.eSet(result.eClass().getEStructuralFeature("ReqIFLongName"), name);
        return result;
    }

    private ResourceSetImpl resources(EObject... objects) {
        var resources = new ResourceSetImpl();
        var resource = new ResourceImpl(URI.createURI("memory:/finder.capella"));
        resources.getResources().add(resource);
        for (EObject object : objects) resource.getContents().add(object);
        return resources;
    }

    @Test
    public void findsNativeIdentifierLongNameAndShortName() {
        EObject requirement =
                nativeObject("Requirement", "FSAE-2027-V1:EV.3.3.1:p001", "Power limit");
        requirement.eSet(requirement.eClass().getEStructuralFeature("ReqIFName"), "Power");
        var resources = resources(requirement);
        assertSame(
                requirement,
                RequirementFinder.findByIdentifier(resources, "FSAE-2027-V1:EV.3.3.1:p001"));
        assertSame(requirement, RequirementFinder.findByName(resources, "Power limit"));
        assertSame(requirement, RequirementFinder.findByName(resources, "Power"));
        assertSame(
                requirement,
                RequirementFinder.findByName(resources, " FSAE-2027-V1:EV.3.3.1:p001 "));
    }

    @Test
    public void nativeFolderIsNotReturnedAsRequirement() {
        var resources =
                resources(
                        nativeObject("Folder", "folder", "Folder"),
                        nativeObject("Requirement", "rule", "Rule"));
        assertNull(RequirementFinder.findByName(resources, "Folder"));
        assertNull(RequirementFinder.findByIdentifier(resources, "folder"));
        assertEquals(1, RequirementFinder.listAll(resources).size());
    }

    @Test
    public void duplicateNativeIdentifierFailsInsteadOfChoosingArbitraryRule() {
        var resources =
                resources(
                        nativeObject("Requirement", "duplicate", "A"),
                        nativeObject("Requirement", "duplicate", "B"));
        assertThrows(
                IllegalStateException.class,
                () -> RequirementFinder.findByIdentifier(resources, "duplicate"));
    }

    @Test
    public void sessionIdentifierLookupUsesNativeRequirementsFacade() {
        EObject requirement = nativeObject("Requirement", "native-id", "Native title");
        var domain =
                TransactionalEditingDomain.Factory.INSTANCE.createEditingDomain(
                        resources(requirement));
        Session session =
                (Session)
                        Proxy.newProxyInstance(
                                Session.class.getClassLoader(),
                                new Class<?>[] {Session.class},
                                (proxy, method, args) ->
                                        method.getName().equals("getTransactionalEditingDomain")
                                                ? domain
                                                : null);
        try {
            assertSame(requirement, RequirementFinder.findByIdentifier(session, "native-id"));
            assertSame(requirement, RequirementFinder.findByName(session, "Native title"));
        } finally {
            domain.dispose();
        }
    }

    @Test
    public void legacyNameAndNullInputsRemainSupported() {
        var model = EcoreFactory.eINSTANCE.createEPackage();
        model.setNsURI("urn:legacy-requirements-finder-test");
        var type = EcoreFactory.eINSTANCE.createEClass();
        type.setName("Requirement");
        model.getEClassifiers().add(type);
        var name = EcoreFactory.eINSTANCE.createEAttribute();
        name.setName("name");
        name.setEType(EcorePackage.Literals.ESTRING);
        type.getEStructuralFeatures().add(name);
        EObject requirement = model.getEFactoryInstance().create(type);
        requirement.eSet(name, "Legacy rule");
        assertSame(
                requirement, RequirementFinder.findByName(resources(requirement), "Legacy rule"));
        assertNull(RequirementFinder.findByName((ResourceSetImpl) null, "Legacy rule"));
        assertNull(RequirementFinder.findByName(resources(requirement), "  "));
    }
}
