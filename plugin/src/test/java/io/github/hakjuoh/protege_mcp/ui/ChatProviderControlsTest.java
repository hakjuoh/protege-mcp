package io.github.hakjuoh.protege_mcp.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import javax.swing.JComboBox;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.protege.editor.core.prefs.Preferences;

import io.github.hakjuoh.protege_mcp.chat.ChatClientPreferences;
import io.github.hakjuoh.protege_mcp.chat.ChatListener;
import io.github.hakjuoh.protege_mcp.chat.ChatModelCatalog;
import io.github.hakjuoh.protege_mcp.chat.ChatModels;
import io.github.hakjuoh.protege_mcp.chat.ChatProcess;
import io.github.hakjuoh.protege_mcp.chat.ChatProvider;
import io.github.hakjuoh.protege_mcp.chat.ChatRequest;
import io.github.hakjuoh.protege_mcp.chat.claude.ClaudeCliProvider;
import io.github.hakjuoh.protege_mcp.chat.claude.ClaudeClient;
import io.github.hakjuoh.protege_mcp.config.McpConfig;

@ExtendWith(EdtTestExtension.class)
class ChatProviderControlsTest {

    private Preferences preferences;

    @BeforeEach
    void clearPreferences() {
        preferences = McpConfig.prefs();
        preferences.clear();
    }

    @AfterEach
    void restorePreferences() {
        preferences.clear();
    }

    @Test
    void initialSelectionUsesSavedProviderWithoutPublishingAChange() {
        ChatProvider first = provider("first", "First", List.of("", "one"));
        ChatProvider second = provider("second", "Second", List.of("", "two"));
        preferences.putString(McpConfig.KEY_CHAT_PROVIDER, second.id());
        RecordingListener listener = new RecordingListener();

        ChatProviderControls controls = new ChatProviderControls(List.of(first, second), listener);

        assertSame(second, controls.selectedProvider());
        assertEquals(List.of("(default)", "two"), items(controls.modelPicker()));
        assertTrue(listener.providers.isEmpty(), "construction is not a user-driven provider change");
    }

    @Test
    void unknownSavedProviderFallsBackToFirstAndRepairsPreference() {
        ChatProvider first = provider("first", "First", List.of(""));
        preferences.putString(McpConfig.KEY_CHAT_PROVIDER, "removed-provider");

        ChatProviderControls controls =
                new ChatProviderControls(List.of(first), new RecordingListener());

        assertSame(first, controls.selectedProvider());
        assertEquals("first", preferences.getString(McpConfig.KEY_CHAT_PROVIDER, ""));
    }

    @Test
    void switchingProviderRefreshesDependentPickersBeforeNotifying() {
        ChatProvider first = provider(
                "first", "First", List.of("", "one"), List.of("", "medium"));
        ChatProvider second = provider(
                "second", "Second", List.of("", "two"), List.of("", "high"));
        RecordingListener listener = new RecordingListener();
        ChatProviderControls controls = new ChatProviderControls(List.of(first, second), listener);
        listener.controls = controls;

        controls.providerPicker().setSelectedItem(second);

        assertSame(second, controls.selectedProvider());
        assertEquals("second", preferences.getString(McpConfig.KEY_CHAT_PROVIDER, ""));
        assertEquals(List.of(second), listener.providers);
        assertEquals(List.of("(default)", "two"), listener.modelsAtProviderNotification);
        assertEquals(List.of("(default)", "high"), listener.effortsAtProviderNotification);
    }

    @Test
    void reselectingActiveModelDoesNotPublishAChange() {
        RecordingListener listener = new RecordingListener();
        ChatProviderControls controls = new ChatProviderControls(
                List.of(provider("codex", "Codex", List.of("", "gpt-5.4"))), listener);
        controls.modelPicker().setSelectedItem("gpt-5.4");
        listener.models.clear();

        controls.modelPicker().setSelectedItem("gpt-5.4");

        assertTrue(listener.models.isEmpty());
        assertEquals("gpt-5.4", controls.selectedModel());
    }

    @Test
    void programmaticRefreshIsNotPublishedAsAUserSwitch() {
        RecordingListener listener = new RecordingListener();
        ChatProviderControls controls = new ChatProviderControls(
                List.of(provider("claude", "Claude", List.of("", "sonnet"))), listener);

        controls.refreshPickers();

        assertTrue(listener.models.isEmpty());
        assertTrue(listener.efforts.isEmpty());
    }

    @Test
    void modelSwitchRebuildsEffortsAndRejectsUnsupportedSavedValue() {
        ChatProvider provider = effortProvider();
        preferences.putString(ChatModels.reasoningEffortPrefKey(provider.id()), "high");
        RecordingListener listener = new RecordingListener();
        ChatProviderControls controls = new ChatProviderControls(List.of(provider), listener);

        controls.modelPicker().setSelectedItem("model-b");
        assertEquals(List.of("(default)", "low"), items(controls.effortPicker()));
        assertEquals("(default)", controls.effortPicker().getSelectedItem());
        assertEquals(List.of("model-b"), listener.models);

        controls.modelPicker().setSelectedItem("model-a");
        assertEquals(List.of("(default)", "high"), items(controls.effortPicker()));
        assertEquals("high", controls.effortPicker().getSelectedItem());
    }

    @Test
    void savedModelAndEffortAreRestoredAndUserChangesArePersisted() {
        ChatProvider provider = provider("codex", "Codex",
                List.of("", "model-a", "model-b"), List.of("", "high", "low"));
        preferences.putString(ChatProviderControls.modelPrefKey(provider), "model-a");
        preferences.putString(ChatModels.reasoningEffortPrefKey(provider.id()), "high");
        ChatProviderControls controls =
                new ChatProviderControls(List.of(provider), new RecordingListener());

        assertEquals("model-a", controls.selectedModel());
        assertEquals("high", controls.selectedReasoningEffort());

        controls.modelPicker().setSelectedItem("model-b");
        controls.effortPicker().setSelectedItem("low");

        assertEquals("model-b", preferences.getString(
                ChatProviderControls.modelPrefKey(provider), ""));
        assertEquals("low", preferences.getString(
                ChatModels.reasoningEffortPrefKey(provider.id()), ""));
    }

    @Test
    void modelPickerUsesSavedCatalogOrder() {
        ChatModelCatalog.save(preferences, "codex", List.of("third", "first", "second"));

        ChatProviderControls controls = new ChatProviderControls(
                List.of(catalogProvider(preferences)), new RecordingListener());

        assertEquals(List.of("(default)", "third", "first", "second"),
                items(controls.modelPicker()));
    }

    @Test
    void openControlsPickUpCatalogEdits() throws Exception {
        ChatModelCatalog.save(preferences, "codex", List.of("first"));
        ChatProviderControls controls = new ChatProviderControls(
                List.of(catalogProvider(preferences)), new RecordingListener());
        controls.followCatalogEdits();
        try {
            assertEquals(List.of("(default)", "first"), items(controls.modelPicker()));

            editCatalogAndDeliver(preferences, List.of("first", "second"));

            assertEquals(List.of("(default)", "first", "second"),
                    items(controls.modelPicker()));
        } finally {
            controls.stopFollowingCatalogEdits();
        }
    }

    @Test
    void stoppedControlsNoLongerFollowCatalogEdits() throws Exception {
        ChatModelCatalog.save(preferences, "codex", List.of("first"));
        ChatProviderControls controls = new ChatProviderControls(
                List.of(catalogProvider(preferences)), new RecordingListener());
        controls.followCatalogEdits();
        controls.stopFollowingCatalogEdits();
        controls.modelPicker().removeAllItems();

        editCatalogAndDeliver(preferences, List.of("first", "second"));

        assertEquals(List.of(), items(controls.modelPicker()));
    }

    @Test
    void repeatedFollowCallRegistersOnlyOnce() throws Exception {
        ChatModelCatalog.save(preferences, "codex", List.of("first"));
        CountingProvider provider = new CountingProvider(preferences);
        ChatProviderControls controls =
                new ChatProviderControls(List.of(provider), new RecordingListener());
        controls.followCatalogEdits();
        controls.followCatalogEdits();
        int before = provider.listCalls;
        try {
            ChatModelCatalog.fireChanged();
            assertEquals(before + 1, provider.listCalls);
        } finally {
            controls.stopFollowingCatalogEdits();
        }
    }

    @Test
    void savedRenameRefreshesTooltipInOpenControls() throws Exception {
        ChatProvider provider = new ClaudeCliProvider();
        ChatProviderControls controls =
                new ChatProviderControls(List.of(provider), new RecordingListener());
        controls.followCatalogEdits();
        try {
            String longName = "Claude Code — Research Ontology Workspace";
            ChatClientPreferences.saveDisplayName(preferences, ClaudeClient.PROFILE, longName);
            ChatModelCatalog.fireChanged();

            assertTrue(controls.providerPicker().getToolTipText().startsWith(longName));
        } finally {
            controls.stopFollowingCatalogEdits();
        }
    }

    @Test
    void noProvidersLeavesOnlyEmptyModelControls() {
        ChatProviderControls controls =
                new ChatProviderControls(List.of(), new RecordingListener());

        assertNull(controls.selectedProvider());
        assertNull(controls.providerPicker());
        assertEquals("", controls.selectedModel());
        assertEquals("", controls.selectedReasoningEffort());
    }

    @Test
    void enabledStatePropagatesToEveryPicker() {
        ChatProviderControls controls = new ChatProviderControls(
                List.of(provider("codex", "Codex", List.of(""))), new RecordingListener());

        controls.setControlsEnabled(false);

        assertFalse(controls.providerPicker().isEnabled());
        assertFalse(controls.modelPicker().isEnabled());
        assertFalse(controls.effortPicker().isEnabled());
    }

    @Test
    void modelPreferenceKeysRemainProviderScoped() {
        assertEquals(McpConfig.KEY_CHAT_MODEL_CODEX,
                ChatProviderControls.modelPrefKey(provider("codex", "Codex", List.of(""))));
        assertEquals(McpConfig.KEY_CHAT_MODEL_CLAUDE,
                ChatProviderControls.modelPrefKey(provider("claude", "Claude", List.of(""))));
        assertEquals(ChatModels.modelPrefKey("something-else"),
                ChatProviderControls.modelPrefKey(
                        provider("something-else", "Other", List.of(""))));
    }

    private static ChatProvider provider(String id, String name, List<String> models) {
        return provider(id, name, models, List.of(""));
    }

    private static ChatProvider provider(String id, String name, List<String> models,
            List<String> efforts) {
        return new ChatProvider() {
            @Override public String id() { return id; }
            @Override public String displayName() { return name; }
            @Override public boolean isAvailable() { return true; }
            @Override public List<String> listModels() { return models; }
            @Override public List<String> reasoningEfforts(String model) { return efforts; }
            @Override public String defaultModel() { return ""; }
            @Override public ChatProcess startTurn(ChatRequest request, ChatListener listener) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static ChatProvider effortProvider() {
        return new ChatProvider() {
            @Override public String id() { return "effort-switch-test"; }
            @Override public String displayName() { return "Effort test"; }
            @Override public boolean isAvailable() { return true; }
            @Override public List<String> listModels() {
                return List.of("", "model-a", "model-b");
            }
            @Override public List<String> reasoningEfforts(String model) {
                return "model-a".equals(model) ? List.of("", "high") : List.of("", "low");
            }
            @Override public String defaultModel() { return ""; }
            @Override public ChatProcess startTurn(ChatRequest request, ChatListener listener) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static ChatProvider catalogProvider(Preferences preferences) {
        return new ChatProvider() {
            @Override public String id() { return "codex"; }
            @Override public String displayName() { return "Codex"; }
            @Override public boolean isAvailable() { return true; }
            @Override public List<String> listModels() {
                return ChatModelCatalog.pickerModels(preferences, id());
            }
            @Override public String defaultModel() { return ""; }
            @Override public ChatProcess startTurn(ChatRequest request, ChatListener listener) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static List<String> items(JComboBox<String> combo) {
        List<String> displayed = new ArrayList<>();
        for (int i = 0; i < combo.getItemCount(); i++) {
            displayed.add(combo.getItemAt(i));
        }
        return List.copyOf(displayed);
    }

    private static void editCatalogAndDeliver(Preferences preferences, List<String> models)
            throws Exception {
        ChatModelCatalog.save(preferences, "codex", models);
        ChatModelCatalog.fireChanged();
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeAndWait(() -> { });
        }
    }

    private static final class RecordingListener implements ChatProviderControls.Listener {
        private final List<ChatProvider> providers = new ArrayList<>();
        private final List<String> models = new ArrayList<>();
        private final List<String> efforts = new ArrayList<>();
        private List<String> modelsAtProviderNotification = List.of();
        private List<String> effortsAtProviderNotification = List.of();
        private ChatProviderControls controls;

        @Override
        public void providerChanged(ChatProvider provider) {
            providers.add(provider);
            if (controls != null) {
                modelsAtProviderNotification = items(controls.modelPicker());
                effortsAtProviderNotification = items(controls.effortPicker());
            }
        }

        @Override
        public void modelChanged(String displayName) {
            models.add(displayName);
        }

        @Override
        public void reasoningEffortChanged(String displayName) {
            efforts.add(displayName);
        }
    }

    private static final class CountingProvider implements ChatProvider {
        private final Preferences preferences;
        private int listCalls;

        private CountingProvider(Preferences preferences) {
            this.preferences = preferences;
        }

        @Override public String id() { return "codex"; }
        @Override public String displayName() { return "Codex"; }
        @Override public boolean isAvailable() { return true; }
        @Override public List<String> listModels() {
            listCalls++;
            return ChatModelCatalog.pickerModels(preferences, id());
        }
        @Override public String defaultModel() { return ""; }
        @Override public ChatProcess startTurn(ChatRequest request, ChatListener listener) {
            throw new UnsupportedOperationException();
        }
    }

}
