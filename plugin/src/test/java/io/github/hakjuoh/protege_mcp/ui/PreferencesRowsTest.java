package io.github.hakjuoh.protege_mcp.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;

import javax.swing.Box;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;

import org.junit.jupiter.api.Test;

import org.protege.editor.core.ui.preferences.PreferencesLayoutPanel;

/**
 * Headless unit tests for {@link PreferencesRows}. Swing components are lightweight, so composing
 * and laying them out needs no display; same-package placement reaches the package-private class.
 */
class PreferencesRowsTest {

    @Test
    void labelledOrdersLabelGapComponent() {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(8123, 1, 65535, 1));
        Box row = PreferencesRows.labelled("Port:", spinner);
        assertEquals(3, row.getComponentCount());
        assertEquals("Port:", ((JLabel) row.getComponent(0)).getText());
        assertEquals(spinner, row.getComponent(2));
        assertEquals(PreferencesRows.LABEL_GAP_PX,
                row.getComponent(1).getPreferredSize().width);
    }

    @Test
    void labelledPinsTextFieldMinimumToPreferred() {
        // Mirrors the guard PreferencesLayoutPanel.addGroupComponent applies to a BARE text field
        // (it cannot see one inside this row): without it GridBagLayout collapses the field to a
        // sliver whenever the dialog is narrower than the panel's preferred width.
        JTextField field = new JTextField("", 30);
        PreferencesRows.labelled("claude path (optional):", field);
        assertEquals(field.getPreferredSize().width, field.getMinimumSize().width);
        assertTrue(field.getMinimumSize().width > 100);
    }

    @Test
    void labelledLeavesNonTextFieldMinimumAlone() {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(8123, 1, 65535, 1));
        assertEquals(false, spinner.isMinimumSizeSet());
        PreferencesRows.labelled("Port:", spinner);
        assertEquals(false, spinner.isMinimumSizeSet());
    }

    /**
     * Regression test for the labelled-row placement inside a real {@code PreferencesLayoutPanel}.
     * The upstream {@code addLabelledGroupComponent} puts the label and its field into the SAME
     * grid cell (field leading, label trailing), so next to a wide sibling (a long checkbox, a
     * wrapped help text) the label floated at the far right edge of the dialog. A row composed by
     * {@link PreferencesRows#labelled} must keep the label at the leading edge of the component
     * column, left of its field.
     */
    @Test
    void labelledRowStaysAtLeadingEdgeOfComponentColumn() {
        PreferencesLayoutPanel panel = new PreferencesLayoutPanel();
        panel.addGroup("Connection");
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(8123, 1, 65535, 1));
        Box row = PreferencesRows.labelled("Port:", spinner);
        panel.addGroupComponent(row);
        JCheckBox wideSibling = new JCheckBox(
                "Share one MCP endpoint across all Protégé windows and instances (broker process)");
        panel.addGroupComponent(wideSibling);
        panel.addHelpText(PreferencesText.wrapped(
                "A help text long enough to be wrapped over several lines by the fixed-width HTML "
                + "block, which widens the shared component column just like the real MCP tab."));

        panel.setSize(900, 600);
        layoutTree(panel);

        Component label = row.getComponent(0);
        assertTrue(label.getX() < spinner.getX(),
                "label must sit left of its field within the row");
        assertEquals(0, label.getX(), "label must sit at the leading edge of the row");
        int rowAbsoluteX = absoluteX(row, panel);
        int siblingAbsoluteX = absoluteX(wideSibling, panel);
        assertEquals(siblingAbsoluteX, rowAbsoluteX,
                "labelled row must start in the same column as its group siblings");
        assertTrue(rowAbsoluteX + label.getWidth() < panel.getWidth() / 2,
                "label must not float toward the right edge of the dialog");
        assertNotEquals(0, label.getWidth(), "layout must have actually run");
    }

    private static int absoluteX(Component c, Component root) {
        int x = 0;
        for (Component cursor = c; cursor != root; cursor = cursor.getParent()) {
            x += cursor.getX();
        }
        return x;
    }

    private static void layoutTree(Component c) {
        c.doLayout();
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                layoutTree(child);
            }
        }
    }
}
