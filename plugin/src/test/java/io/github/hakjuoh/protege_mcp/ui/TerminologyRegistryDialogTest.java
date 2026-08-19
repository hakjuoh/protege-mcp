package io.github.hakjuoh.protege_mcp.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.JComboBox;
import javax.swing.JTextField;

import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.external.ProviderOwnerConfig.OriginBinding;

class TerminologyRegistryDialogTest {

    @Test
    void oneOntoPortalProfileOffersEditableVendorEndpointPresets() {
        assertEquals(java.util.List.of("ols4", "ontoportal"),
                TerminologyRegistryDialog.supportedProfiles());
        assertEquals("https://data.bioontology.org",
                TerminologyRegistryDialog.defaultOriginForPreset("BioPortal"));
        assertEquals("https://data.agroportal.eu",
                TerminologyRegistryDialog.defaultOriginForPreset("AgroPortal"));
        assertEquals(2, TerminologyRegistryDialog.ontoPortalPresets().size());
        assertEquals("https://", TerminologyRegistryDialog.defaultCustomOrigin());
        assertEquals("BioPortal", TerminologyRegistryDialog.presetForOrigin("ontoportal",
                "https://data.bioontology.org"));
        assertEquals("AgroPortal", TerminologyRegistryDialog.presetForOrigin("ontoportal",
                "https://data.agroportal.eu"));
        assertEquals("Custom", TerminologyRegistryDialog.presetForOrigin("ontoportal",
                "https://registry.example.org/base"));
        assertEquals("Custom", TerminologyRegistryDialog.presetForOrigin("ols4",
                "https://data.bioontology.org"));

        assertTrue(TerminologyRegistryDialog.shouldReplaceAutoFilledOrigin(
                "https://data.bioontology.org", "https://data.bioontology.org"));
        assertFalse(TerminologyRegistryDialog.shouldReplaceAutoFilledOrigin(
                "https://registry.example.org", "https://data.bioontology.org"));
    }

    @Test
    void swingControlsApplyPresetsPreserveCustomOriginsAndSaveOntoPortal() {
        JComboBox<String> profile = new JComboBox<>(new String[] {"ols4", "ontoportal"});
        JComboBox<String> preset = new JComboBox<>(new String[] {
                "Custom", "BioPortal", "AgroPortal"});
        JTextField origin = new JTextField("https://");
        preset.setEnabled(false);
        new TerminologyRegistryDialog.PresetBehavior(
                profile, preset, origin, "https://").install();

        profile.setSelectedItem("ontoportal");
        assertTrue(preset.isEnabled());
        assertEquals("BioPortal", preset.getSelectedItem());
        assertEquals("https://data.bioontology.org", origin.getText());

        preset.setSelectedItem("AgroPortal");
        assertEquals("https://data.agroportal.eu", origin.getText());
        origin.setText("https://registry.example.org/onto/base");

        profile.setSelectedItem("ols4");
        assertFalse(preset.isEnabled());
        assertEquals("https://registry.example.org/onto/base", origin.getText());
        profile.setSelectedItem("ontoportal");
        assertEquals("Custom", preset.getSelectedItem());
        assertEquals("https://registry.example.org/onto/base", origin.getText());

        OriginBinding saved = TerminologyRegistryDialog.bindingFor(
                "custom", profile.getSelectedItem(), origin.getText());
        assertEquals("ontoportal", saved.profile());
        assertEquals("https://registry.example.org/onto/base",
                saved.origin().toASCIIString());
    }
}
