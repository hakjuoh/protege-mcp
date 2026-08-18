package io.github.hakjuoh.protege_mcp.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.JTextArea;

import org.junit.jupiter.api.Test;

/**
 * Headless unit tests for {@link PreferencesText}. Same-package placement reaches the
 * package-private class and its static members.
 */
class PreferencesTextTest {

    @Test
    void helpTextUsesNativeResponsiveWordWrapping() {
        JTextArea help = PreferencesText.helpText("some help");
        assertTrue(help.getLineWrap());
        assertTrue(help.getWrapStyleWord());
        assertFalse(help.isEditable());
        assertFalse(help.isFocusable());
        assertFalse(help.isOpaque());
    }

    @Test
    void helpTextPreservesPlainUnicodeAndMarkupLookingProse() {
        String prose = "Protégé windows — use <html> & Preferences ▸ MCP";
        JTextArea help = PreferencesText.helpText(prose);
        assertTrue(help.getText().equals(prose));
    }

    @Test
    void preferredHeightReflowsWhenTheAllocatedWidthChanges() {
        JTextArea help = PreferencesText.helpText(
                "A sufficiently long help paragraph should occupy more lines at a narrow width "
                + "and fewer lines when the Preferences dialog grows wider.");
        help.setSize(280, 1000);
        int narrowHeight = help.getPreferredSize().height;
        help.setSize(700, 1000);
        int wideHeight = help.getPreferredSize().height;

        assertTrue(narrowHeight > wideHeight,
                "native wrapping must recalculate preferred height from the allocated width");
    }
}
