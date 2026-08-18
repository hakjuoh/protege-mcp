package io.github.hakjuoh.protege_mcp.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

/** Responsive-layout coverage for MCP help and warning prose. */
class McpPreferencesPanelTest {

    @Test
    void helpTextFillsTheMcpTabAndDoesNotForceHorizontalScrolling() throws Exception {
        McpPreferencesPanel panel = new McpPreferencesPanel();
        panel.initialise();
        JScrollPane viewport = new JScrollPane(panel);
        onEdt(() -> {
            viewport.setSize(760, 600);
            layoutTree(viewport);
        });
        List<JTextArea> helpTexts = textAreas(panel);
        assertTrue(helpTexts.size() >= 4, "warning and all MCP help paragraphs use responsive text");
        JTextArea visibleHelp = helpTexts.stream().filter(Component::isVisible).findFirst().orElseThrow();
        int narrowWidth = visibleHelp.getWidth();

        onEdt(() -> {
            viewport.setSize(1040, 600);
            layoutTree(viewport);
        });

        assertTrue(visibleHelp.getWidth() >= narrowWidth + 250,
                "MCP help text must consume additional Preferences width, narrow=" + narrowWidth
                        + ", wide=" + visibleHelp.getWidth());
        assertFalse(viewport.getHorizontalScrollBar().isVisible(),
                "responsive MCP prose must not introduce horizontal scrolling");
    }

    private static List<JTextArea> textAreas(Component root) {
        List<JTextArea> areas = new ArrayList<>();
        collectTextAreas(root, areas);
        return areas;
    }

    private static void collectTextAreas(Component component, List<JTextArea> areas) {
        if (component instanceof JTextArea area) {
            areas.add(area);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectTextAreas(child, areas);
            }
        }
    }

    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layoutTree(nested);
            }
        }
    }

    private static void onEdt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }
}
