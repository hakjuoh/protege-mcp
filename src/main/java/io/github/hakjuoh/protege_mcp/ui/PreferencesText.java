package io.github.hakjuoh.protege_mcp.ui;

/**
 * Pure text helpers for the preferences panels, split out of the Swing components so they can be
 * unit-tested headless (same pattern as {@link ServerViewText}).
 */
final class PreferencesText {

    private PreferencesText() {
    }

    /**
     * Wrap width for preferences help text, in CSS pixels. Swing's HTML renderer scales CSS px by a
     * fixed 1.3 (its 96-to-72 dpi conversion), so the label lays out at about
     * {@code 1.3 * HELP_TEXT_WIDTH_PX} display pixels — chosen to roughly match the widest checkbox
     * row in the panels, so wrapped help lines don't widen the dialog beyond the controls above them.
     */
    static final int HELP_TEXT_WIDTH_PX = 420;

    /**
     * Prepare a help string for {@code PreferencesLayoutPanel.addHelpText}, which puts the string
     * verbatim into a single-line {@link javax.swing.JLabel}: without this, a long help text never
     * wraps and stretches the whole Preferences dialog into horizontal scrolling. The fixed-width
     * HTML block makes the label soft-wrap at {@value #HELP_TEXT_WIDTH_PX} CSS px. HTML-significant
     * characters in the text are escaped, so callers can keep passing plain prose.
     */
    static String wrapped(String helpText) {
        String escaped = helpText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<html><div style='width: " + HELP_TEXT_WIDTH_PX + "px'>" + escaped + "</div></html>";
    }
}
