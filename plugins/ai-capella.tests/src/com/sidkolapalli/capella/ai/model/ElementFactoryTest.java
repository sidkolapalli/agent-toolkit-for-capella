package com.sidkolapalli.capella.ai.model;

import static org.junit.Assert.*;

import org.junit.Test;
import org.polarsys.capella.core.data.cs.CsFactory;
import org.polarsys.capella.core.data.information.InformationFactory;
import org.polarsys.capella.core.data.information.InformationPackage;
import org.polarsys.capella.core.data.information.MultiplicityElement;
import org.polarsys.capella.core.data.information.datavalue.LiteralNumericValue;

/** Native classifier lookup and the defaults Capella's creation tools give new elements. */
public class ElementFactoryTest {

    /** GAPS: api.create("Property", name, aClass) was rejected. */
    @Test
    public void propertyResolvesToCapellasClassProperty() {
        assertSame(InformationPackage.Literals.PROPERTY, ElementFactory.findEClass("Property"));
        assertSame(
                InformationPackage.Literals.EXCHANGE_ITEM_ELEMENT,
                ElementFactory.findEClass("ExchangeItemElement"));
    }

    /** DWF_D_51: every created exchange item element was a validation error. */
    @Test
    public void newPropertiesAndExchangeItemElementsAreOneToOne() {
        for (MultiplicityElement element :
                new MultiplicityElement[] {
                    InformationFactory.eINSTANCE.createExchangeItemElement(),
                    InformationFactory.eINSTANCE.createProperty()
                }) {
            ElementFactory.defaultCardinality(element);
            assertEquals("1", ((LiteralNumericValue) element.getOwnedMinCard()).getValue());
            assertEquals("1", ((LiteralNumericValue) element.getOwnedMaxCard()).getValue());
            assertNotNull(element.getOwnedMinCard().getId());
        }
        var part = CsFactory.eINSTANCE.createPart();
        ElementFactory.defaultCardinality(part);
        assertNull(part.getOwnedMinCard());
    }
}
