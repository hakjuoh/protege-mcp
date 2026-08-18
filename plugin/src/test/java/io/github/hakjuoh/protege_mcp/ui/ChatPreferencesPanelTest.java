package io.github.hakjuoh.protege_mcp.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.Action;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;

import org.junit.jupiter.api.Test;
import org.protege.editor.core.prefs.Preferences;

import io.github.hakjuoh.protege_mcp.chat.ChatModelCatalog;
import io.github.hakjuoh.protege_mcp.chat.ChatModels;
import io.github.hakjuoh.protege_mcp.chat.ChatClientAdapter;
import io.github.hakjuoh.protege_mcp.chat.ChatClientModelCatalog;
import io.github.hakjuoh.protege_mcp.chat.ChatClientPreferences;
import io.github.hakjuoh.protege_mcp.chat.ChatClientProfile;
import io.github.hakjuoh.protege_mcp.chat.ChatClients;
import io.github.hakjuoh.protege_mcp.chat.ChatModelDefinition;
import io.github.hakjuoh.protege_mcp.chat.ChatProvider;
import io.github.hakjuoh.protege_mcp.chat.claude.ClaudeCliProvider;
import io.github.hakjuoh.protege_mcp.config.McpConfig;
import io.github.hakjuoh.protege_mcp.testing.TestPreferences;

/**
 * Headless coverage for the ordered model editor: the Add/Update/Delete UI semantics, the Enter key
 * that has to apply the field without stealing the dialog's OK button, and the feedback line that has
 * to say an edit is only staged until OK.
 */
class ChatPreferencesPanelTest {

    @Test
    void storedEmptyCatalogRequiresExplicitRefresh() throws Exception {
        Preferences preferences = TestPreferences.cleared();
        ChatModelCatalog.save(preferences, "dynamic", List.of());
        ChatClientProfile client = dynamicClient((home, override) -> List.of("provider/a"));
        Object editor = newEditor(client, preferences, () -> "/staged/dynamic-cli");

        invoke(editor, "component");
        assertFalse(field(editor, "autoDiscoveryPending", Boolean.class));
        assertEquals(null, field(editor, "discoveryWorker", SwingWorker.class));

        JButton refresh = field(editor, "refreshButton", JButton.class);
        onEdt(refresh::doClick);
        awaitModels(editor, 1);
        assertEquals(List.of("provider/a"), models(editor));
    }

    @Test
    void disposeRejectsLateDiscoveryResults() throws Exception {
        Preferences preferences = TestPreferences.cleared();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ChatClientProfile client = dynamicClient((home, override) -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return List.of("late/model");
        });
        Object editor = newEditor(client, preferences, () -> "/staged/dynamic-cli");

        invoke(editor, "component");
        onEdt(field(editor, "refreshButton", JButton.class)::doClick);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        Method dispose = editor.getClass().getDeclaredMethod("dispose");
        dispose.setAccessible(true);
        onEdt(() -> {
            try {
                dispose.invoke(editor);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        });
        release.countDown();
        onEdt(() -> { });
        Thread.sleep(50);
        onEdt(() -> { });

        assertTrue(models(editor).isEmpty());
    }

    @Test
    void refreshFromCliMergesModelsUsingTheStagedExecutablePath() throws Exception {
        Preferences preferences = TestPreferences.cleared();
        ChatModelCatalog.save(preferences, "dynamic", List.of("custom/manual"));
        AtomicReference<String> seenOverride = new AtomicReference<>();
        ChatClientProfile client = new ChatClientProfile("dynamic", new ChatClientAdapter() {
            @Override public String id() { return "dynamic-test"; }
            @Override public ChatProvider createProvider(ChatClientProfile profile) { return null; }
            @Override public List<String> discoverModels(Path home) { return List.of(); }
            @Override public List<String> discoverModels(Path home, String override) {
                seenOverride.set(override);
                return List.of("provider/a", "provider/b", "provider/a");
            }
        }, "Dynamic", "dynamic-cli");
        Object editor = newEditor(client, preferences, () -> "/staged/dynamic-cli");

        invoke(editor, "component");
        JButton refresh = field(editor, "refreshButton", JButton.class);
        onEdt(refresh::doClick);
        @SuppressWarnings("unchecked")
        SwingWorker<?, Void> worker = field(
                editor, "discoveryWorker", SwingWorker.class);
        worker.get(5, TimeUnit.SECONDS);
        awaitModels(editor, 3);

        assertEquals("/staged/dynamic-cli", seenOverride.get());
        assertEquals(List.of("provider/a", "provider/b", "custom/manual"), models(editor));
        saveEditor(editor, preferences);
        assertEquals(List.of("provider/a", "provider/b", "custom/manual"),
                ChatModelCatalog.load(preferences, "dynamic"));
        assertTrue(preferences.getBoolean(
                ChatClientPreferences.modelDiscoveryCompletedPrefKey("dynamic"), false));
    }

    @Test
    void refreshAssociatesDiscoveredEffortsWithEachModel() throws Exception {
        Preferences preferences = TestPreferences.cleared();
        ChatClientProfile client = new ChatClientProfile("dynamic", new ChatClientAdapter() {
            @Override public String id() { return "dynamic-test"; }
            @Override public ChatProvider createProvider(ChatClientProfile profile) { return null; }
            @Override public List<String> discoverModels(Path home) { return List.of(); }
            @Override public List<ChatModelDefinition> discoverModelDefinitions(
                    Path home, String override) {
                return List.of(
                        new ChatModelDefinition("provider/a", List.of("low", "high")),
                        new ChatModelDefinition("provider/b", List.of("max")));
            }
        }, "Dynamic", "dynamic-cli");
        Object editor = newEditor(client, preferences, () -> "/staged/dynamic-cli");

        invoke(editor, "component");
        onEdt(field(editor, "refreshButton", JButton.class)::doClick);
        awaitModels(editor, 2);
        JList<?> list = field(editor, "modelList", JList.class);
        onEdt(() -> list.setSelectedIndex(0));
        assertEquals("low, high", field(editor, "effortField", JTextField.class).getText());

        saveEditor(editor, preferences);
        assertEquals(List.of(
                new ChatModelDefinition("provider/a", List.of("low", "high")),
                new ChatModelDefinition("provider/b", List.of("max"))),
                new ChatClientModelCatalog(client).loadDefinitions(preferences));
    }

    @Test
    void refreshEnrichesAnUnsavedLegacyRowAndResyncsItsSelectedEditor() throws Exception {
        Preferences preferences = TestPreferences.cleared();
        ChatModelCatalog.save(preferences, "opencode", List.of("provider/a"));
        Object editor = newEditor("opencode", preferences);
        invoke(editor, "component");
        JList<?> list = field(editor, "modelList", JList.class);
        onEdt(() -> list.setSelectedIndex(0));
        assertEquals("", field(editor, "effortField", JTextField.class).getText());

        mergeModels(editor, List.of(
                new ChatModelDefinition("provider/a", List.of("high", "max"))));

        assertEquals("high, max", field(editor, "effortField", JTextField.class).getText());
    }

    @Test
    void refreshNeverReplacesASavedPerModelEffortList() throws Exception {
        Preferences preferences = TestPreferences.cleared();
        Object seedEditor = newEditor("opencode", preferences);
        ChatClientModelCatalog catalog = field(
                seedEditor, "clientCatalog", ChatClientModelCatalog.class);
        catalog.saveDefinitions(preferences, List.of(
                new ChatModelDefinition("provider/a", List.of("low"))));
        Object editor = newEditor("opencode", preferences);
        invoke(editor, "component");
        JList<?> list = field(editor, "modelList", JList.class);
        onEdt(() -> list.setSelectedIndex(0));

        assertEquals(0, mergeModels(editor, List.of(
                new ChatModelDefinition("provider/a", List.of("high", "max")))));
        assertEquals("low", field(editor, "effortField", JTextField.class).getText());
    }

    @Test
    void refreshPrependsNewlyDiscoveredModelsPreservingExistingOrder() throws Exception {
        Preferences preferences = TestPreferences.cleared();
        ChatModelCatalog.save(preferences, "antigravity", List.of("model-c", "model-b", "model-d"));
        Object editor = newEditor("antigravity", preferences);
        invoke(editor, "component");

        assertEquals(List.of("model-c", "model-b", "model-d"), models(editor));

        int changed = mergeModels(editor, List.of(
                new ChatModelDefinition("model-a", List.of("high")),
                new ChatModelDefinition("model-b", List.of()),
                new ChatModelDefinition("model-c", List.of()),
                new ChatModelDefinition("model-d", List.of())));

        assertEquals(1, changed);
        assertEquals(List.of("model-a", "model-c", "model-b", "model-d"), models(editor));
    }

    @Test
    void predefinedClientsHaveIndependentTabsNamesAndModelEditors() throws Exception {
        Preferences preferences = McpConfig.prefs();
        preferences.clear();
        ChatModelCatalog.save(preferences, "claude", List.of("claude-model"));
        ChatModelCatalog.save(preferences, "codex", List.of("codex-model"));
        ChatModelCatalog.save(preferences, "antigravity", List.of("agy-model"));
        ChatModelCatalog.save(preferences, "opencode", List.of("ollama/qwen3"));
        ChatPreferencesPanel panel = new ChatPreferencesPanel();
        try {
            panel.initialise();
            JTabbedPane tabs = field(panel, "clientTabs", JTabbedPane.class);
            assertEquals(5, tabs.getTabCount());
            assertEquals(List.of("Claude Code", "Codex", "Antigravity", "OpenCode", "General"),
                    java.util.stream.IntStream.range(0, tabs.getTabCount())
                            .mapToObj(tabs::getTitleAt).toList());

            List<?> editors = field(panel, "clientEditors", List.class);
            assertEquals(4, editors.size());
            assertEquals(List.of("claude-model"),
                    models(field(editors.get(0), "models", Object.class)));
            assertEquals(List.of("codex-model"),
                    models(field(editors.get(1), "models", Object.class)));
            assertEquals(List.of("agy-model"),
                    models(field(editors.get(2), "models", Object.class)));
            assertEquals(List.of("ollama/qwen3"),
                    models(field(editors.get(3), "models", Object.class)));

            for (int index = 0; index < editors.size(); index++) {
                ClientInstallationGuidePanel guidePanel = field(editors.get(index),
                        "installationGuide", ClientInstallationGuidePanel.class);
                String expectedCommand = ChatClients.predefined().get(index).adapter()
                        .installationGuide().orElseThrow()
                        .commandFor(System.getProperty("os.name", "")).command();
                assertEquals(expectedCommand,
                        field(guidePanel, "commandField", JTextField.class).getText());
                assertTrue(buttonTexts(guidePanel).contains("Copy"));
                assertTrue(buttonTexts(guidePanel).contains("Open official guide"));
                assertTrue(labels(guidePanel).stream().anyMatch(label ->
                        label.getLabelFor() == fieldUnchecked(
                                guidePanel, "commandField", JTextField.class)));
            }

            JTextField name = field(editors.get(0), "displayName", JTextField.class);
            JTextField initialPath = field(editors.get(0), "executablePath", JTextField.class);
            assertTrue(labels(tabs.getComponentAt(0)).stream()
                    .anyMatch(label -> label.getLabelFor() == name));
            assertTrue(labels(tabs.getComponentAt(0)).stream()
                    .anyMatch(label -> label.getLabelFor() == initialPath));
            onEdt(() -> name.setText("Ontology Research"));
            assertEquals("Ontology Research", tabs.getTitleAt(0));
            assertEquals("Claude Code", new ClaudeCliProvider().displayName(),
                    "a tab edit remains staged until the Preferences dialog applies it");

            JTextField claudePath = field(editors.get(0), "executablePath", JTextField.class);
            JTextField codexPath = field(editors.get(1), "executablePath", JTextField.class);
            JTextField antigravityPath = field(editors.get(2), "executablePath", JTextField.class);
            JTextField openCodePath = field(editors.get(3), "executablePath", JTextField.class);
            JTextField openCodeName = field(editors.get(3), "displayName", JTextField.class);
            onEdt(() -> {
                claudePath.setText("/tools/claude");
                codexPath.setText("/tools/codex");
                antigravityPath.setText("/tools/agy");
                openCodePath.setText("/tools/opencode");
                openCodeName.setText("Local Models");
                fieldUnchecked(panel, "allowWrites", JCheckBox.class).setSelected(false);
            });
            Object antigravityModels = field(editors.get(2), "models", Object.class);
            JTextField antigravityModelField = field(
                    antigravityModels, "modelField", JTextField.class);
            onEdt(() -> antigravityModelField.setText("gemini-extra"));
            invoke(antigravityModels, "applyModel");

            panel.applyChanges();
            assertEquals("Ontology Research", new ClaudeCliProvider().displayName());
            assertEquals("/tools/claude", preferences.getString(
                    ChatClientPreferences.executablePathPrefKey("claude"), ""));
            assertEquals("/tools/codex", preferences.getString(
                    ChatClientPreferences.executablePathPrefKey("codex"), ""));
            assertEquals("/tools/agy", preferences.getString(
                    ChatClientPreferences.executablePathPrefKey("antigravity"), ""));
            assertEquals("/tools/opencode", preferences.getString(
                    ChatClientPreferences.executablePathPrefKey("opencode"), ""));
            assertEquals("Local Models", preferences.getString(
                    ChatClientPreferences.displayNamePrefKey("opencode"), ""));
            assertEquals(List.of("agy-model", "gemini-extra"),
                    ChatModelCatalog.load(preferences, "antigravity"));
            assertEquals(List.of("ollama/qwen3"),
                    ChatModelCatalog.load(preferences, "opencode"));
            assertFalse(preferences.getBoolean(McpConfig.KEY_CHAT_ALLOW_WRITES, true));
            assertEquals(List.of("codex-model"), ChatModelCatalog.load(preferences, "codex"),
                    "renaming one client must not touch another client's catalog");
        } finally {
            panel.dispose();
            preferences.clear();
        }
    }

    @Test
    void longEditableNameCannotHideOtherClientOrGeneralTabs() throws Exception {
        Preferences preferences = McpConfig.prefs();
        preferences.clear();
        preferences.putString(McpConfig.KEY_CHAT_CLIENT_NAME_CLAUDE,
                "Claude Code — A Very Long Research Workspace Name Used Every Day");
        ChatPreferencesPanel panel = new ChatPreferencesPanel();
        try {
            panel.initialise();
            JTabbedPane tabs = field(panel, "clientTabs", JTabbedPane.class);
            onEdt(() -> {
                tabs.setSize(560, 400);
                tabs.doLayout();
            });
            assertEquals(JTabbedPane.SCROLL_TAB_LAYOUT, tabs.getTabLayoutPolicy());
            for (int index = 0; index < tabs.getTabCount(); index++) {
                int selected = index;
                onEdt(() -> {
                    tabs.setSelectedIndex(selected);
                    tabs.doLayout();
                });
                assertEquals(index, tabs.getSelectedIndex(), "every tab remains keyboard-selectable");
                assertTrue(tabs.getUI().getTabBounds(tabs, index) != null,
                        "the selected tab must be brought into the scrollable viewport");
            }
            assertEquals(tabs.getTitleAt(0), tabs.getToolTipTextAt(0));
        } finally {
            panel.dispose();
            preferences.clear();
        }
    }

    @Test
    void clientEditorsFillTheWindowAndGrowWithIt() throws Exception {
        Preferences preferences = McpConfig.prefs();
        preferences.clear();
        ChatPreferencesPanel panel = new ChatPreferencesPanel();
        try {
            panel.initialise();
            JTabbedPane tabs = field(panel, "clientTabs", JTabbedPane.class);
            Object clientEditor = field(panel, "clientEditors", List.class).get(0);
            ClientInstallationGuidePanel guide = field(clientEditor,
                    "installationGuide", ClientInstallationGuidePanel.class);
            JTextField command = field(guide, "commandField", JTextField.class);
            JButton officialGuide = field(guide, "openGuideButton", JButton.class);
            Object modelEditor = field(clientEditor, "models", Object.class);
            JScrollPane modelScroll = field(modelEditor, "modelScroll", JScrollPane.class);
            JTextField modelField = field(modelEditor, "modelField", JTextField.class);
            JTextField effortField = field(modelEditor, "effortField", JTextField.class);
            JTextArea helpText = textAreas(tabs.getComponentAt(0)).get(0);

            onEdt(() -> {
                panel.setSize(760, 760);
                layoutTree(panel);
            });
            int narrowTabs = tabs.getWidth();
            int narrowCommand = command.getWidth();
            int narrowModels = modelScroll.getWidth();
            int narrowHelp = helpText.getWidth();

            onEdt(() -> {
                panel.setSize(1040, 760);
                layoutTree(panel);
            });

            assertTrue(tabs.getWidth() >= narrowTabs + 250,
                    "the client tabs must consume additional Preferences width");
            assertTrue(command.getWidth() >= narrowCommand + 250,
                    "the install command must grow with its tab");
            assertTrue(modelScroll.getWidth() >= narrowModels + 250,
                    "the model list must grow with its tab");
            assertTrue(helpText.getWidth() >= narrowHelp + 250,
                    "help text must wrap against the expanded client-tab width");
            assertTrue(command.getWidth() > 500,
                    "a wide Preferences window must show the complete install command");
            assertTrue(modelScroll.getWidth() > 650,
                    "the model list must remain usable in a wide Preferences window");
            assertTrue(modelField.getWidth() > 550,
                    "the model-id field must remain usable in a wide Preferences window");
            assertTrue(effortField.getWidth() > 550,
                    "the reasoning-effort field must remain usable in a wide Preferences window");
            assertEquals(effortField.getX(), modelField.getX(),
                    "the two model-setting fields must have the same left edge");
            assertEquals(effortField.getWidth(), modelField.getWidth(),
                    "the two model-setting fields must have exactly the same width");
            assertTrue(modelScroll.getHeight() >= 3 * 42,
                    "at least three model rows must remain visible, actual=" + modelScroll.getHeight());

            Component clientTab = tabs.getComponentAt(0);
            JLabel clientHeading = labels(clientTab).stream()
                    .filter(label -> "Client".equals(label.getText()))
                    .findFirst().orElseThrow();
            Point clientStart = SwingUtilities.convertPoint(clientHeading, 0, 0, clientTab);
            assertTrue(clientStart.y <= 30,
                    "the client form must start directly below the tab strip without a large gap");
            Point commandStart = SwingUtilities.convertPoint(command, 0, 0, clientTab);
            Point guideStart = SwingUtilities.convertPoint(officialGuide, 0, 0, clientTab);
            assertEquals(commandStart.x, guideStart.x,
                    "the official-guide action must align with the command input");
        } finally {
            panel.dispose();
            preferences.clear();
        }
    }

    @Test
    void defaultPreferencesViewportDoesNotNeedAHorizontalScrollbar() throws Exception {
        Preferences preferences = McpConfig.prefs();
        preferences.clear();
        ChatPreferencesPanel panel = new ChatPreferencesPanel();
        try {
            panel.initialise();
            JScrollPane preferencesViewport = new JScrollPane(panel);
            onEdt(() -> {
                // PreferencesDialogPanel uses an 850 px default width. Its selected tab is wrapped
                // in a JScrollPane exactly like this one.
                preferencesViewport.setSize(850, 600);
                layoutTree(preferencesViewport);
            });

            assertFalse(preferencesViewport.getHorizontalScrollBar().isVisible(),
                    "the initial Preferences window must fit without horizontal scrolling");
            assertTrue(preferencesViewport.getViewport().getExtentSize().width
                            >= panel.getPreferredSize().width,
                    "the responsive panel's preferred width must fit inside the initial viewport");
        } finally {
            panel.dispose();
            preferences.clear();
        }
    }

    @Test
    void applyAddsWhenNoRowIsSelectedAndUpdatesWhenOneIsSelected() throws Exception {
        Preferences preferences = TestPreferences.cleared();
        ChatModelCatalog.save(preferences, "codex", List.of("first", "second"));
        Object editor = newEditor(preferences);
        invoke(editor, "component");

        JTextField field = field(editor, "modelField", JTextField.class);
        JButton apply = field(editor, "applyButton", JButton.class);
        assertEquals("Add", apply.getText());
        onEdt(() -> field.setText("third"));
        invoke(editor, "applyModel");
        assertEquals(List.of("first", "second", "third"), models(editor));
        JList<?> list = field(editor, "modelList", JList.class);
        assertEquals(-1, list.getSelectedIndex());
        assertEquals("Add", apply.getText());

        onEdt(() -> list.setSelectedIndex(1));
        assertEquals("Update", apply.getText());
        onEdt(() -> field.setText("updated"));
        invoke(editor, "applyModel");
        assertEquals(List.of("first", "updated", "third"), models(editor));

        onEdt(() -> field.setText(""));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(-1, list.getSelectedIndex());
        assertEquals("Add", apply.getText());
    }

    @Test
    void modelEffortsCanBeEditedAndSavedIndependently() throws Exception {
        Preferences preferences = TestPreferences.cleared();
        Object seedEditor = newEditor("opencode", preferences);
        ChatClientModelCatalog seedCatalog = field(
                seedEditor, "clientCatalog", ChatClientModelCatalog.class);
        seedCatalog.saveDefinitions(preferences, List.of(
                new ChatModelDefinition("provider/a", List.of("low", "high")),
                new ChatModelDefinition("provider/b", List.of("max"))));
        Object editor = newEditor("opencode", preferences);
        invoke(editor, "component");
        JList<?> list = field(editor, "modelList", JList.class);

        onEdt(() -> list.setSelectedIndex(0));
        JTextField effortField = field(editor, "effortField", JTextField.class);
        assertEquals("low, high", effortField.getText());
        onEdt(() -> effortField.setText("medium, high"));
        pressEnter(editor, "effortField");
        saveEditor(editor, preferences);

        ChatClientModelCatalog savedCatalog = field(
                editor, "clientCatalog", ChatClientModelCatalog.class);
        assertEquals(List.of(
                new ChatModelDefinition("provider/a", List.of("medium", "high")),
                new ChatModelDefinition("provider/b", List.of("max"))),
                savedCatalog.loadDefinitions(preferences));
    }

    @Test
    void invalidReasoningEffortDoesNotStageAnUpdate() throws Exception {
        Preferences preferences = TestPreferences.cleared();
        ChatModelCatalog.save(preferences, "codex", List.of("model-a"));
        Object editor = newEditor(preferences);
        invoke(editor, "component");
        JList<?> list = field(editor, "modelList", JList.class);
        onEdt(() -> list.setSelectedIndex(0));
        JTextField effortField = field(editor, "effortField", JTextField.class);
        onEdt(() -> effortField.setText("low effort"));

        invoke(editor, "applyModel");

        assertEquals("Reasoning efforts may contain only ASCII letters, numbers, '.', '_' and '-'. "
                + "Separate values with commas.", feedback(editor));
        assertFalse(field(editor, "dirty", Boolean.class));
    }

    @Test
    void listRendererShowsCompactActionsAndNativeSelection() throws Exception {
        Preferences preferences = TestPreferences.cleared();
        ChatModelCatalog.save(preferences, "claude", List.of("first", "second"));
        Object editor = newEditor("claude", preferences);
        invoke(editor, "component");
        JList<String> list = field(editor, "modelList", JList.class);
        assertInstanceOf(DefaultListModel.class, list.getModel());

        Component unselected = list.getCellRenderer().getListCellRendererComponent(
                list, "first", 0, false, false);
        assertEquals(List.of("X"), buttonTexts(unselected));
        assertEquals("Reasoning: none", labels(unselected).stream()
                .filter(label -> label.getText().startsWith("Reasoning:"))
                .findFirst().orElseThrow().getText());

        @SuppressWarnings("unchecked")
        Map<String, List<String>> effortsByModel =
                field(editor, "effortsByModel", Map.class);
        effortsByModel.put("second", List.of("low", "medium", "high", "xhigh", "max"));
        list.setSelectedIndex(1);
        Component selected = list.getCellRenderer().getListCellRendererComponent(
                list, "second", 1, true, true);
        assertEquals(List.of("↑", "↓", "X"), buttonTexts(selected));
        for (JButton button : buttons(selected)) {
            assertEquals(24, button.getPreferredSize().width);
            assertTrue(button.getMargin().left <= 2 && button.getMargin().right <= 2);
        }
        assertEquals(list.getSelectionBackground(), selected.getBackground());
        JLabel reasoning = labels(selected).stream()
                .filter(label -> label.getText().startsWith("Reasoning:"))
                .findFirst().orElseThrow();
        assertFalse(reasoning.getText().contains("(default)"));
        assertEquals("Reasoning: low, medium, high, xhigh, max", reasoning.getText());

        list.setSize(PreferencesText.HELP_TEXT_DISPLAY_WIDTH_PX, 52);
        Rectangle selectedBounds = list.getCellBounds(1, 1);
        dispatchPress(list, selectedBounds.x + selectedBounds.width - 64,
                selectedBounds.y + selectedBounds.height / 2);
        assertEquals(List.of("second", "first"), models(editor));

        JScrollPane scroll = field(editor, "modelScroll", JScrollPane.class);
        assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, scroll.getHorizontalScrollBarPolicy());
        assertEquals(PreferencesText.HELP_TEXT_DISPLAY_WIDTH_PX, scroll.getPreferredSize().width);
        assertEquals(list.getBackground(), scroll.getViewport().getBackground());
    }

    @Test
    void modelListKeepsLookAndFeelColorsOnADarkPalette() throws Exception {
        Object previousBackground = UIManager.get("List.background");
        Object previousForeground = UIManager.get("List.foreground");
        Object previousSelectionBackground = UIManager.get("List.selectionBackground");
        Object previousSelectionForeground = UIManager.get("List.selectionForeground");
        Color dark = new Color(38, 38, 38);
        Color light = new Color(225, 225, 225);
        Color selection = new Color(50, 85, 125);
        UIManager.put("List.background", dark);
        UIManager.put("List.foreground", light);
        UIManager.put("List.selectionBackground", selection);
        UIManager.put("List.selectionForeground", Color.WHITE);
        try {
            Preferences preferences = TestPreferences.cleared();
            ChatModelCatalog.save(preferences, "codex", List.of("first"));
            Object editor = newEditor(preferences);
            invoke(editor, "component");
            JList<String> list = field(editor, "modelList", JList.class);
            JScrollPane scroll = field(editor, "modelScroll", JScrollPane.class);
            assertEquals(dark, list.getBackground());
            assertEquals(dark, scroll.getViewport().getBackground());
            Component unselected = list.getCellRenderer().getListCellRendererComponent(
                    list, "first", 0, false, false);
            assertEquals(light, labels(unselected).stream()
                    .filter(label -> "first".equals(label.getText())).findFirst().orElseThrow()
                    .getForeground());
            Component selected = list.getCellRenderer().getListCellRendererComponent(
                    list, "first", 0, true, true);
            assertEquals(selection, selected.getBackground());
            assertEquals(Color.WHITE, labels(selected).stream()
                    .filter(label -> "first".equals(label.getText())).findFirst().orElseThrow()
                    .getForeground());
        } finally {
            UIManager.put("List.background", previousBackground);
            UIManager.put("List.foreground", previousForeground);
            UIManager.put("List.selectionBackground", previousSelectionBackground);
            UIManager.put("List.selectionForeground", previousSelectionForeground);
        }
    }

    @Test
    void rowDeleteRemovesOnlyTheClickedModel() throws Exception {
        Preferences preferences = TestPreferences.cleared();
        ChatModelCatalog.save(preferences, "codex", List.of("first", "second", "third"));
        Object editor = newEditor(preferences);
        invoke(editor, "component");
        JList<?> list = field(editor, "modelList", JList.class);
        list.setSize(PreferencesText.HELP_TEXT_DISPLAY_WIDTH_PX, 78);
        Rectangle second = list.getCellBounds(1, 1);
        dispatchPress(list, second.x + second.width - 12, second.y + second.height / 2);
        assertEquals(List.of("first", "third"), models(editor));
        assertEquals(-1, list.getSelectedIndex());
    }

    @Test
    void nonPrimaryClickDoesNotTriggerRowActions() throws Exception {
        Preferences preferences = TestPreferences.cleared();
        ChatModelCatalog.save(preferences, "codex", List.of("first", "second"));
        Object editor = newEditor(preferences);
        invoke(editor, "component");
        JList<?> list = field(editor, "modelList", JList.class);
        list.setSize(PreferencesText.HELP_TEXT_DISPLAY_WIDTH_PX, 52);
        Rectangle first = list.getCellBounds(0, 0);

        dispatchPress(list, first.x + first.width - 12,
                first.y + first.height / 2, MouseEvent.BUTTON3);

        assertEquals(List.of("first", "second"), models(editor));
    }

    @Test
    void selectedRowActionsAreKeyboardOperableAndListIsLabelled() throws Exception {
        Preferences preferences = TestPreferences.cleared();
        ChatModelCatalog.save(preferences, "codex", List.of("first", "second"));
        Object editor = newEditor(preferences);
        Component component = (Component) invoke(editor, "component");
        JList<?> list = field(editor, "modelList", JList.class);
        JLabel label = labels(component).stream()
                .filter(candidate -> candidate.getLabelFor() == list)
                .findFirst().orElseThrow();
        assertTrue(label.getText().contains("models"));
        JTextField modelField = field(editor, "modelField", JTextField.class);
        assertTrue(labels(component).stream().anyMatch(candidate ->
                candidate.getLabelFor() == modelField && "Model ID:".equals(candidate.getText())));
        assertTrue(list.getAccessibleContext().getAccessibleDescription().contains("Alt+Up"));

        onEdt(() -> list.setSelectedIndex(1));
        triggerListAction(list, KeyStroke.getKeyStroke("alt UP"));
        assertEquals(List.of("second", "first"), models(editor));
        triggerListAction(list, KeyStroke.getKeyStroke("DELETE"));
        assertEquals(List.of("first"), models(editor));
    }

    // ---- Enter, and the feedback that says nothing is stored yet ---------------------------------

    @Test
    void enterAddsTheTypedModelExactlyLikeTheAddButton() throws Exception {
        Preferences preferences = TestPreferences.cleared();
        ChatModelCatalog.save(preferences, "codex", List.of("first"));
        Object editor = newEditor(preferences);
        invoke(editor, "component");

        type(editor, "gpt-5-codex");
        pressEnter(editor);

        assertEquals(List.of("first", "gpt-5-codex"), models(editor));
        assertEquals("", field(editor, "modelField", JTextField.class).getText(),
                "the field clears after an add, ready for the next id");
    }

    @Test
    void enterUpdatesTheSelectedRowRatherThanAddingACopy() throws Exception {
        Preferences preferences = TestPreferences.cleared();
        ChatModelCatalog.save(preferences, "codex", List.of("first", "second"));
        Object editor = newEditor(preferences);
        invoke(editor, "component");
        JList<?> list = field(editor, "modelList", JList.class);

        onEdt(() -> list.setSelectedIndex(1));
        type(editor, "renamed");
        pressEnter(editor);

        assertEquals(List.of("first", "renamed"), models(editor));
    }

    @Test
    void enterOnAnEmptyFieldIsLeftToTheDialogsOkButton() throws Exception {
        Preferences preferences = TestPreferences.cleared();
        ChatModelCatalog.save(preferences, "codex", List.of("first"));
        Object editor = newEditor(preferences);
        invoke(editor, "component");
        JTextField field = field(editor, "modelField", JTextField.class);

        Object binding = field.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
        Action action = field.getActionMap().get(binding);
        assertFalse(action.isEnabled(),
                "a blank field must leave Enter to the Preferences dialog's default button, which is "
                        + "the only thing that actually stores an edit");

        // What Swing does with a disabled binding: nothing happens here, and the key travels on.
        pressEnter(editor);
        assertEquals(List.of("first"), models(editor));
    }

    @Test
    void everyStagedEditSaysItIsNotStoredUntilOk() throws Exception {
        Preferences preferences = TestPreferences.cleared();
        ChatModelCatalog.save(preferences, "codex", List.of("first", "second"));
        Object editor = newEditor(preferences);
        invoke(editor, "component");
        JList<?> list = field(editor, "modelList", JList.class);

        type(editor, "third");
        pressEnter(editor);
        assertEquals("Added third — click OK to save.", feedback(editor));

        onEdt(() -> list.setSelectedIndex(0));
        type(editor, "renamed");
        pressEnter(editor);
        assertEquals("Updated model and reasoning efforts — click OK to save.", feedback(editor));

        invoke(editor, "moveModel", 1, 1);
        assertEquals("Moved second — click OK to save.", feedback(editor));

        invoke(editor, "deleteModel", 0);
        assertEquals("Deleted renamed — click OK to save.", feedback(editor));
    }

    @Test
    void aRefusedEditIsNotReportedAsSomethingOkWouldSave() throws Exception {
        Preferences preferences = TestPreferences.cleared();
        ChatModelCatalog.save(preferences, "codex", List.of("first"));
        Object editor = newEditor(preferences);
        invoke(editor, "component");

        type(editor, "first");
        pressEnter(editor);

        assertEquals("That model id is already listed.", feedback(editor));
        assertEquals(List.of("first"), models(editor));
    }

    // ---- what an open Assistant is left with ------------------------------------------------------

    @Test
    void aModelSelectionSurvivesAnEditUnlessItWasDeleted() throws Exception {
        Preferences preferences = TestPreferences.cleared();
        ChatModelCatalog.save(preferences, "codex", List.of("kept", "dropped"));
        preferences.putString(ChatModels.modelPrefKey("codex"), "kept");

        ChatPreferencesSupport.clearMissingModelSelection(
                preferences, "codex", List.of("kept", "dropped"));
        assertEquals("kept", preferences.getString(ChatModels.modelPrefKey("codex"), ""),
                "an edit that leaves the selected id in place must not reset the picker");

        preferences.putString(ChatModels.modelPrefKey("codex"), "dropped");
        ChatPreferencesSupport.clearMissingModelSelection(preferences, "codex", List.of("kept"));
        assertEquals("", preferences.getString(ChatModels.modelPrefKey("codex"), ""),
                "a selection the edit deleted must fall back to the CLI default, not linger as a "
                        + "value the next turn would still run on");
    }

    private static void type(Object editor, String text) throws Exception {
        JTextField field = field(editor, "modelField", JTextField.class);
        onEdt(() -> field.setText(text));
    }

    /** Presses Enter through the field's own key bindings, exactly as a focused field would. */
    private static void pressEnter(Object editor) throws Exception {
        pressEnter(editor, "modelField");
    }

    private static void pressEnter(Object editor, String fieldName) throws Exception {
        JTextField field = field(editor, fieldName, JTextField.class);
        KeyStroke enter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0);
        onEdt(() -> {
            Object binding = field.getInputMap().get(enter);
            Action action = field.getActionMap().get(binding);
            SwingUtilities.notifyAction(action, enter, new KeyEvent(field, KeyEvent.KEY_PRESSED,
                    System.currentTimeMillis(), 0, KeyEvent.VK_ENTER, '\n'), field, 0);
        });
    }

    private static String feedback(Object editor) throws Exception {
        return field(editor, "feedback", JLabel.class).getText();
    }

    private static Object newEditor(Preferences preferences) throws Exception {
        return newEditor("codex", preferences);
    }

    private static Object newEditor(String providerId, Preferences preferences) throws Exception {
        ChatClientProfile client = new ChatClientProfile(providerId, new ChatClientAdapter() {
            @Override public String id() { return "test-adapter"; }
            @Override public ChatProvider createProvider(ChatClientProfile profile) { return null; }
            @Override public List<String> discoverModels(Path home) { return List.of(); }
        }, providerId, providerId);
        return newEditor(client, preferences, () -> "");
    }

    private static Object newEditor(ChatClientProfile client, Preferences preferences,
            Supplier<String> executableOverride) throws Exception {
        Class<?> type = ChatModelEditor.class;
        Constructor<?> constructor = type.getDeclaredConstructor(
                ChatClientModelCatalog.class, String.class, Preferences.class, Supplier.class);
        constructor.setAccessible(true);
        return constructor.newInstance(new ChatClientModelCatalog(client),
                client.id() + " models", preferences, executableOverride);
    }

    @FunctionalInterface
    private interface ModelDiscovery {
        List<String> discover(Path home, String override);
    }

    private static ChatClientProfile dynamicClient(ModelDiscovery discovery) {
        return new ChatClientProfile("dynamic", new ChatClientAdapter() {
            @Override public String id() { return "dynamic-test"; }
            @Override public ChatProvider createProvider(ChatClientProfile profile) { return null; }
            @Override public List<String> discoverModels(Path home) { return List.of(); }
            @Override public List<String> discoverModels(Path home, String override) {
                return discovery.discover(home, override);
            }
        }, "Dynamic", "dynamic-cli");
    }

    private static void awaitModels(Object editor, int expectedSize) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (models(editor).size() < expectedSize && System.nanoTime() < deadline) {
            onEdt(() -> { });
            Thread.sleep(10);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> models(Object editor) throws Exception {
        DefaultListModel<String> model = field(editor, "modelData", DefaultListModel.class);
        List<String> values = new ArrayList<>();
        for (int i = 0; i < model.size(); i++) {
            values.add(model.get(i));
        }
        return List.copyOf(values);
    }

    private static Object invoke(Object target, String name, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] instanceof Integer ? int.class : args[i].getClass();
        }
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static int mergeModels(Object editor, List<ChatModelDefinition> definitions)
            throws Exception {
        Method method = editor.getClass().getDeclaredMethod("mergeModels", List.class);
        method.setAccessible(true);
        return (Integer) method.invoke(editor, definitions);
    }

    private static void saveEditor(Object editor, Preferences preferences) throws Exception {
        Method method = editor.getClass().getDeclaredMethod("save", Preferences.class);
        method.setAccessible(true);
        method.invoke(editor, preferences);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    private static <T> T fieldUnchecked(Object target, String name, Class<T> type) {
        try {
            return field(target, name, type);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static List<String> buttonTexts(Component root) {
        return buttons(root).stream().map(JButton::getText).toList();
    }

    private static List<JButton> buttons(Component root) {
        List<JButton> buttons = new ArrayList<>();
        collectButtons(root, buttons);
        return buttons;
    }

    private static List<JLabel> labels(Component root) {
        List<JLabel> labels = new ArrayList<>();
        collectLabels(root, labels);
        return labels;
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

    private static void collectLabels(Component component, List<JLabel> labels) {
        if (component instanceof JLabel label) {
            labels.add(label);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectLabels(child, labels);
            }
        }
    }

    private static void triggerListAction(JList<?> list, KeyStroke key) throws Exception {
        onEdt(() -> {
            Object binding = list.getInputMap().get(key);
            Action action = list.getActionMap().get(binding);
            action.actionPerformed(null);
        });
    }

    private static void collectButtons(Component component, List<JButton> buttons) {
        if (component instanceof JButton button) {
            buttons.add(button);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectButtons(child, buttons);
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

    private static void dispatchPress(JList<?> list, int x, int y) throws Exception {
        dispatchPress(list, x, y, MouseEvent.BUTTON1);
    }

    private static void dispatchPress(JList<?> list, int x, int y, int button) throws Exception {
        onEdt(() -> list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), 0, x, y, 1, false, button)));
    }

}
