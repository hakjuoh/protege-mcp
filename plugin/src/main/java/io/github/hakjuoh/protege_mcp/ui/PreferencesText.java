package io.github.hakjuoh.protege_mcp.ui;

import javax.swing.JTextArea;

/**
 * Shared help-text presentation for Preferences panels.
 */
final class PreferencesText {

    private PreferencesText() {
    }

    /** Initial preferred width; responsive layouts may freely grow or shrink the text area. */
    static final int HELP_TEXT_DISPLAY_WIDTH_PX = 560;

    /**
     * Creates non-interactive prose that wraps against its actual component width. A Swing HTML
     * label needs a fixed CSS width to wrap, so it cannot respond when the Preferences dialog is
     * resized; a text area performs native word wrapping at every allocated width instead.
     */
    static JTextArea helpText(String text) {
        return new ResponsiveHelpText(text);
    }
}
