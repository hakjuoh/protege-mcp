package io.github.hakjuoh.protege_mcp.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Headless unit tests for {@link PreferencesText}. Same-package placement reaches the
 * package-private class and its static members.
 */
class PreferencesTextTest {

    @Test
    void wrappedProducesFixedWidthHtmlBlock() {
        assertEquals("<html><div style='width: " + PreferencesText.HELP_TEXT_WIDTH_PX
                + "px'>some help</div></html>", PreferencesText.wrapped("some help"));
    }

    @Test
    void wrappedStartsWithHtmlTagSoSwingRendersMultiLine() {
        // JLabel only soft-wraps when the text literally starts with <html>; a leading space or
        // other prefix would silently fall back to single-line rendering.
        assertTrue(PreferencesText.wrapped("x").startsWith("<html>"));
        assertTrue(PreferencesText.wrapped("").startsWith("<html>"));
    }

    @Test
    void wrappedEscapesHtmlSignificantCharacters() {
        String wrapped = PreferencesText.wrapped("a < b & b > c");
        assertTrue(wrapped.contains("a &lt; b &amp; b &gt; c"));
        assertFalse(wrapped.contains("a < b"));
    }

    @Test
    void wrappedEscapesAmpersandBeforeAngleBrackets() {
        // Ampersands must be escaped first, or "&lt;" would double-escape to "&amp;lt;".
        assertTrue(PreferencesText.wrapped("<").contains("&lt;"));
        assertFalse(PreferencesText.wrapped("<").contains("&amp;lt;"));
    }

    @Test
    void wrappedPreservesNonAsciiProseVerbatim() {
        // Help texts use em-dashes, accented product names and menu glyphs; the HTML view renders
        // literal Unicode fine, so no entity substitution should happen.
        String prose = "Protégé windows — Preferences ▸ MCP";
        assertTrue(PreferencesText.wrapped(prose).contains(prose));
    }

    @Test
    void wrappedNeutralizesInjectedMarkup() {
        // A future help text containing markup-looking prose must not alter the HTML structure.
        String wrapped = PreferencesText.wrapped("use <html> or </div> literally");
        assertFalse(wrapped.substring("<html>".length()).contains("<html>"));
        assertTrue(wrapped.contains("&lt;html&gt;"));
        assertTrue(wrapped.contains("&lt;/div&gt;"));
    }
}
