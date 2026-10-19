package org.polarsys.capella.ai.session;

import static org.junit.Assert.*;
import java.util.List;
import java.util.UUID;
import org.junit.Test;
import org.polarsys.capella.ai.privacy.Tokenizer;
import org.polarsys.capella.core.data.la.LaFactory;

public class SelectionSummaryPrivacyTest {
    @Test
    public void contextDoesNotLeakUuidPrefixBeforeTokenization() {
        var component = LaFactory.eINSTANCE.createLogicalComponent();
        component.setName("Secret Project Name");
        component.setId("123e4567-e89b-12d3-a456-426614174000");
        Tokenizer tokenizer = Tokenizer.forSession("selection-test-" + UUID.randomUUID());
        String summary = SelectionSummary.summarize(SelectionContext.of(List.of(component), null), tokenizer);
        assertFalse(summary.contains("Secret Project Name"));
        assertFalse(summary.contains("123e4567"));
        assertTrue(summary.contains("UUID_"));
        assertTrue(tokenizer.detokenizeText(summary).contains(component.getId()));
    }
}
