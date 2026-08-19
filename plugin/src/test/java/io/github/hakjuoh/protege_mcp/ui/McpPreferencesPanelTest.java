package io.github.hakjuoh.protege_mcp.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.event.HierarchyEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JScrollPane;
import javax.swing.JCheckBox;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import io.github.hakjuoh.protege_mcp.config.McpConfig;

/** Responsive-layout coverage for MCP help and warning prose. */
class McpPreferencesPanelTest {

    @TempDir java.nio.file.Path temporary;
    private String originalHome;

    @BeforeEach
    void isolateOwnerProviderFiles() {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", temporary.toString());
    }

    @AfterEach
    void restoreOwnerHome() {
        if (originalHome == null) System.clearProperty("user.home");
        else System.setProperty("user.home", originalHome);
    }

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

    @Test
    void panelContainsServerAndExternalsSubTabs() throws Exception {
        McpPreferencesPanel panel = new McpPreferencesPanel();
        panel.initialise();
        javax.swing.JTabbedPane tabbedPane = null;
        for (Component c : panel.getComponents()) {
            if (c instanceof javax.swing.JTabbedPane tabs) {
                tabbedPane = tabs;
                break;
            }
        }
        org.junit.jupiter.api.Assertions.assertNotNull(tabbedPane, "McpPreferencesPanel must have a JTabbedPane");
        org.junit.jupiter.api.Assertions.assertEquals(2, tabbedPane.getTabCount());
        org.junit.jupiter.api.Assertions.assertEquals("Server", tabbedPane.getTitleAt(0));
        org.junit.jupiter.api.Assertions.assertEquals("Externals", tabbedPane.getTitleAt(1));
        assertFalse(java.nio.file.Files.exists(temporary.resolve(".protege-mcp")),
                "opening Preferences must not create an owner provider store");

        // apply changes safely executes
        panel.applyChanges();
        panel.dispose();
    }

    @Test
    void openingThePanelPositionsThePreferencesViewportAtTheTop() throws Exception {
        McpPreferencesPanel panel = new McpPreferencesPanel() {
            @Override public boolean isShowing() { return true; }
        };
        panel.initialise();
        JScrollPane viewport = new JScrollPane(panel);
        onEdt(() -> {
            viewport.setPreferredSize(new Dimension(600, 180));
            viewport.setSize(600, 180);
            layoutTree(viewport);
            viewport.getViewport().setViewPosition(new Point(0, 120));
            HierarchyEvent showing = new HierarchyEvent(panel,
                    HierarchyEvent.HIERARCHY_CHANGED, panel, viewport,
                    HierarchyEvent.SHOWING_CHANGED);
            for (java.awt.event.HierarchyListener listener : panel.getHierarchyListeners()) {
                listener.hierarchyChanged(showing);
            }
        });
        onEdt(() -> { });

        org.junit.jupiter.api.Assertions.assertEquals(0,
                viewport.getViewport().getViewPosition().y);
        panel.dispose();
    }

    @Test
    void malformedExternalStoreDoesNotHideOrDiscardServerPreferences() throws Exception {
        java.nio.file.Path providers = temporary.resolve(".protege-mcp/providers");
        java.nio.file.Files.createDirectories(providers);
        java.nio.file.Files.writeString(providers.resolve("config.json"), "{not-json");
        boolean previous = McpConfig.prefs().getBoolean(McpConfig.KEY_READ_ONLY, false);
        McpPreferencesPanel panel = new McpPreferencesPanel();
        try {
            panel.initialise();
            JCheckBox readOnly = checkBoxes(panel).stream()
                    .filter(box -> box.getText().startsWith("Read-only mode"))
                    .findFirst().orElseThrow();
            readOnly.setSelected(!previous);

            panel.applyChanges();

            assertTrue(McpConfig.prefs().getBoolean(McpConfig.KEY_READ_ONLY, previous) != previous,
                    "server settings must persist even while Externals is unavailable");
            assertTrue(textAreas(panel).stream().anyMatch(area -> area.getText()
                    .contains("Server settings remain available")));
        } finally {
            McpConfig.prefs().putBoolean(McpConfig.KEY_READ_ONLY, previous);
            panel.dispose();
        }
    }

    private static List<JTextArea> textAreas(Component root) {
        List<JTextArea> areas = new ArrayList<>();
        collectTextAreas(root, areas);
        return areas;
    }

    private static List<JCheckBox> checkBoxes(Component root) {
        List<JCheckBox> boxes = new ArrayList<>();
        collect(root, boxes);
        return boxes;
    }

    private static void collect(Component component, List<JCheckBox> boxes) {
        if (component instanceof JCheckBox box) boxes.add(box);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) collect(child, boxes);
        }
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
