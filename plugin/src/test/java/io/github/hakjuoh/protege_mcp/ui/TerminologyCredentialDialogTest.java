package io.github.hakjuoh.protege_mcp.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.external.ProviderOwnerConfig.AuthScheme;

class TerminologyCredentialDialogTest {

    @Test
    void ontoPortalProfileDefaultsToTheStructuredAuthorizationScheme() {
        assertEquals(AuthScheme.ONTOPORTAL_API_KEY,
                TerminologyCredentialDialog.defaultSchemeForProfile("ontoportal"));
        assertEquals("", TerminologyCredentialDialog.defaultValueForProfile("ontoportal"));
        assertEquals(AuthScheme.BEARER,
                TerminologyCredentialDialog.defaultSchemeForProfile("ols4"));
        assertEquals("", TerminologyCredentialDialog.defaultValueForProfile("ols4"));
    }

    @Test
    void originChangesOnlyReplaceValuesThatRemainAutoFilled() {
        assertTrue(TerminologyCredentialDialog.shouldReplaceAutoFilledValue(
                "ontoportal", "ontoportal"));
        assertTrue(TerminologyCredentialDialog.shouldReplaceAutoFilledValue("", "ontoportal"));
        assertFalse(TerminologyCredentialDialog.shouldReplaceAutoFilledValue(
                "another-origin", "ontoportal"),
                "a user-selected ID equal to another origin alias is still custom");
        assertFalse(TerminologyCredentialDialog.shouldReplaceAutoFilledValue(
                "project-provider", "ontoportal"));
    }
}
