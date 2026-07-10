package io.github.hakjuoh.protege_mcp.ui;

import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTextField;

/**
 * Row composition for the preferences panels. {@code PreferencesLayoutPanel
 * .addLabelledGroupComponent} places the label and the component into the SAME grid cell (component
 * leading-anchored, label trailing-anchored), so in a group whose column is widened by checkboxes
 * or help text the label floats at the far right edge of the Preferences dialog, detached from its
 * field. The panels compose the row themselves with {@link #labelled} and add it via
 * {@code addGroupComponent} instead.
 */
final class PreferencesRows {

    private PreferencesRows() {
    }

    /** Gap between the label and its field, in pixels. */
    static final int LABEL_GAP_PX = 8;

    /**
     * A left-aligned {@code label field} row for {@code PreferencesLayoutPanel.addGroupComponent}.
     * A {@link JTextField} keeps its preferred width as its minimum (the same guard
     * {@code addGroupComponent} applies to a bare text field, which it cannot see inside this row),
     * so the surrounding {@code GridBagLayout} never collapses it to a sliver under width pressure.
     */
    static Box labelled(String label, JComponent component) {
        if (component instanceof JTextField) {
            component.setMinimumSize(component.getPreferredSize());
        }
        Box row = Box.createHorizontalBox();
        row.add(new JLabel(label));
        row.add(Box.createHorizontalStrut(LABEL_GAP_PX));
        row.add(component);
        return row;
    }
}
