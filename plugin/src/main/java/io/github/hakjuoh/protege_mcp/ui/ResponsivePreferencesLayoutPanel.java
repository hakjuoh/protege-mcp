package io.github.hakjuoh.protege_mcp.ui;

import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;

/**
 * Two-column preferences layout whose content column consumes the available horizontal space.
 *
 * <p>Protégé's stock {@code PreferencesLayoutPanel} deliberately lays group components out with
 * {@code fill=NONE}. That is suitable for compact checkboxes and spinners, but it compresses nested
 * editors such as client tabs, command fields, and model lists. This variant keeps the familiar
 * right-aligned group labels while making every content row responsive.
 */
final class ResponsivePreferencesLayoutPanel extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final Insets COMPONENT_INSETS = new Insets(4, 0, 4, 0);

    private int row;
    private String pendingGroup;

    ResponsivePreferencesLayoutPanel() {
        super(new GridBagLayout());
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
    }

    void addGroup(String groupLabel) {
        pendingGroup = groupLabel;
    }

    void addSeparator() {
        add(new JSeparator(), new GridBagConstraints(
                0, row++, 2, 1, 1, 0,
                GridBagConstraints.CENTER, GridBagConstraints.HORIZONTAL,
                new Insets(5, 0, 5, 0), 0, 0));
    }

    void addGroupComponent(JComponent component) {
        addGroupComponent(component, 0, GridBagConstraints.HORIZONTAL);
    }

    /** Adds the main editor row that should receive any spare vertical space. */
    void addExpandingGroupComponent(JComponent component) {
        addGroupComponent(component, 1, GridBagConstraints.BOTH);
    }

    private void addGroupComponent(JComponent component, double weightY, int fill) {
        if (pendingGroup != null) {
            JLabel label = new JLabel(pendingGroup);
            label.setFont(label.getFont().deriveFont(Font.BOLD));
            add(label, new GridBagConstraints(
                    0, row, 1, 1, 0, 0,
                    GridBagConstraints.NORTHEAST, GridBagConstraints.NONE,
                    new Insets(COMPONENT_INSETS.top, 0, COMPONENT_INSETS.bottom, 10), 0, 0));
            pendingGroup = null;
        }
        add(component, new GridBagConstraints(
                1, row, 1, 1, 1, weightY,
                GridBagConstraints.NORTHWEST, fill,
                COMPONENT_INSETS, 0, 0));
        row++;
    }

    void addHelpText(String helpText) {
        addGroupComponent(PreferencesText.helpText(helpText));
    }
}
