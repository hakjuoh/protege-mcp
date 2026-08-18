package io.github.hakjuoh.protege_mcp.ui;

import java.awt.Component;
import java.awt.Dimension;
import java.util.List;
import java.util.Objects;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JPanel;

import io.github.hakjuoh.protege_mcp.chat.ChatModelCatalog;
import io.github.hakjuoh.protege_mcp.chat.ChatModels;
import io.github.hakjuoh.protege_mcp.chat.ChatProvider;
import io.github.hakjuoh.protege_mcp.config.McpConfig;

/**
 * Owns the provider, model, and reasoning-effort pickers and their saved selections.
 *
 * <p>The control reports user-driven changes after its internal state and dependent pickers are
 * consistent. Conversation/session policy stays in {@link ChatView}; catalog and preference policy
 * stays here. All methods are EDT-only except the listener registration methods, whose catalog
 * callback is delivered on the EDT by {@link ChatModelCatalog}.
 */
final class ChatProviderControls extends JPanel {

    private static final long serialVersionUID = 1L;

    static final String MODEL_DEFAULT_LABEL = "(default)";
    static final String EFFORT_DEFAULT_LABEL = "(default)";

    interface Listener {
        void providerChanged(ChatProvider provider);

        void modelChanged(String displayName);

        void reasoningEffortChanged(String displayName);
    }

    private final Listener listener;
    private final JComboBox<ChatProvider> providerPicker;
    private final JComboBox<String> modelPicker = new JComboBox<>();
    private final JComboBox<String> effortPicker = new JComboBox<>();

    private ChatProvider provider;
    private String activeModel;
    private String activeReasoningEffort;
    private boolean suppressModelEvents;
    private boolean suppressEffortEvents;
    private Runnable catalogListener;

    ChatProviderControls(List<ChatProvider> available, Listener listener) {
        this.listener = Objects.requireNonNull(listener);
        List<ChatProvider> providers = List.copyOf(available);

        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));

        providerPicker = providers.isEmpty() ? null : buildProviderPicker(providers);
        if (providerPicker != null) {
            add(providerPicker);
            add(Box.createHorizontalStrut(6));
        }
        configureModelPicker();
        add(modelPicker);
        add(Box.createHorizontalStrut(6));
        configureEffortPicker();
        add(effortPicker);
    }

    ChatProvider selectedProvider() {
        return provider;
    }

    String selectedModel() {
        Object selected = modelPicker.getSelectedItem();
        return ChatModels.normalizeModel(
                selected == null ? null : selected.toString(), MODEL_DEFAULT_LABEL);
    }

    String selectedReasoningEffort() {
        Object selected = effortPicker.getSelectedItem();
        return ChatModels.normalizeReasoningEffort(
                selected == null ? null : selected.toString(), EFFORT_DEFAULT_LABEL);
    }

    void setControlsEnabled(boolean enabled) {
        if (providerPicker != null) {
            providerPicker.setEnabled(enabled);
        }
        modelPicker.setEnabled(enabled);
        effortPicker.setEnabled(enabled);
    }

    /** Registers after the containing view is fully built, so callbacks cannot target partial UI. */
    void followCatalogEdits() {
        if (catalogListener != null) {
            return;
        }
        catalogListener = this::refreshPickers;
        ChatModelCatalog.addChangeListener(catalogListener);
    }

    /** Removes the static catalog registration that would otherwise retain the closed Swing tree. */
    void stopFollowingCatalogEdits() {
        if (catalogListener != null) {
            ChatModelCatalog.removeChangeListener(catalogListener);
            catalogListener = null;
        }
    }

    /** Rebuilds selections from the current catalog and saved preferences. */
    void refreshPickers() {
        if (providerPicker != null) {
            providerPicker.repaint();
            updateProviderTooltip();
        }
        if (provider == null) {
            return;
        }
        populateModels(provider);
        activeModel = selectedModel();
        populateReasoningEfforts(provider);
        activeReasoningEffort = selectedReasoningEffort();
    }

    JComboBox<ChatProvider> providerPicker() {
        return providerPicker;
    }

    JComboBox<String> modelPicker() {
        return modelPicker;
    }

    JComboBox<String> effortPicker() {
        return effortPicker;
    }

    private JComboBox<ChatProvider> buildProviderPicker(List<ChatProvider> providers) {
        JComboBox<ChatProvider> picker = new JComboBox<>();
        picker.setToolTipText("Provider (switching keeps this conversation and hands off new turns)");
        picker.setRenderer(new DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                if (value instanceof ChatProvider chatProvider) {
                    setText(chatProvider.displayName());
                    setToolTipText(chatProvider.displayName());
                }
                return this;
            }
        });

        String savedProvider = McpConfig.prefs().getString(McpConfig.KEY_CHAT_PROVIDER, "");
        for (ChatProvider candidate : providers) {
            picker.addItem(candidate);
            if (provider == null
                    && (candidate.id().equals(savedProvider) || savedProvider.isEmpty())) {
                provider = candidate;
            }
        }
        if (provider == null) {
            provider = providers.get(0);
            McpConfig.prefs().putString(McpConfig.KEY_CHAT_PROVIDER, provider.id());
        }
        picker.setSelectedItem(provider);
        updateProviderTooltip(picker);
        picker.addActionListener(event -> {
            Object selected = picker.getSelectedItem();
            if (selected instanceof ChatProvider next && next != provider) {
                selectProvider(next);
            }
        });
        Dimension preferred = picker.getPreferredSize();
        picker.setPreferredSize(new Dimension(Math.max(132, preferred.width), preferred.height));
        return picker;
    }

    private void configureModelPicker() {
        modelPicker.setToolTipText("Model ((default) = the CLI's own default)");
        if (provider != null) {
            populateModels(provider);
            activeModel = selectedModel();
        }
        modelPicker.addActionListener(event -> onModelChanged());
        Dimension preferred = modelPicker.getPreferredSize();
        modelPicker.setPreferredSize(new Dimension(132, preferred.height));
    }

    private void configureEffortPicker() {
        effortPicker.setToolTipText(
                "Reasoning effort ((default) = the CLI's configured default)");
        if (provider != null) {
            populateReasoningEfforts(provider);
            activeReasoningEffort = selectedReasoningEffort();
        }
        effortPicker.addActionListener(event -> onReasoningEffortChanged());
        Dimension preferred = effortPicker.getPreferredSize();
        effortPicker.setPreferredSize(new Dimension(104, preferred.height));
    }

    private void selectProvider(ChatProvider selected) {
        provider = selected;
        McpConfig.prefs().putString(McpConfig.KEY_CHAT_PROVIDER, selected.id());
        refreshPickers();
        listener.providerChanged(selected);
    }

    private void updateProviderTooltip() {
        updateProviderTooltip(providerPicker);
    }

    private void updateProviderTooltip(JComboBox<ChatProvider> picker) {
        if (picker != null && provider != null) {
            picker.setToolTipText(provider.displayName()
                    + " — switching keeps this conversation and hands off new turns");
        }
    }

    private void populateModels(ChatProvider selectedProvider) {
        boolean previousSuppression = suppressModelEvents;
        suppressModelEvents = true;
        try {
            modelPicker.removeAllItems();
            List<String> models = selectedProvider.listModels();
            for (String model : models) {
                modelPicker.addItem(displayModel(model));
            }
            String saved = McpConfig.prefs().getString(modelPrefKey(selectedProvider), "");
            boolean validSaved = !saved.isEmpty() && models.contains(saved);
            modelPicker.setSelectedItem(validSaved ? saved : MODEL_DEFAULT_LABEL);
        } finally {
            suppressModelEvents = previousSuppression;
        }
    }

    private void onModelChanged() {
        if (suppressModelEvents || provider == null) {
            return;
        }
        String selected = selectedModel();
        if (Objects.equals(activeModel, selected)) {
            return;
        }
        activeModel = selected;
        McpConfig.prefs().putString(modelPrefKey(provider), selected);
        populateReasoningEfforts(provider);
        activeReasoningEffort = selectedReasoningEffort();
        listener.modelChanged(displayModel(selected));
    }

    private void populateReasoningEfforts(ChatProvider selectedProvider) {
        boolean previousSuppression = suppressEffortEvents;
        suppressEffortEvents = true;
        try {
            effortPicker.removeAllItems();
            List<String> efforts = selectedProvider.reasoningEfforts(selectedModel());
            for (String effort : efforts) {
                effortPicker.addItem(displayEffort(effort));
            }
            String saved = McpConfig.prefs().getString(
                    ChatModels.reasoningEffortPrefKey(selectedProvider.id()), "");
            boolean validSaved = !saved.isEmpty() && efforts.contains(saved);
            effortPicker.setSelectedItem(validSaved ? saved : EFFORT_DEFAULT_LABEL);
        } finally {
            suppressEffortEvents = previousSuppression;
        }
    }

    private void onReasoningEffortChanged() {
        if (suppressEffortEvents || provider == null) {
            return;
        }
        String selected = selectedReasoningEffort();
        if (Objects.equals(activeReasoningEffort, selected)) {
            return;
        }
        activeReasoningEffort = selected;
        McpConfig.prefs().putString(
                ChatModels.reasoningEffortPrefKey(provider.id()), selected);
        listener.reasoningEffortChanged(displayEffort(selected));
    }

    static String modelPrefKey(ChatProvider provider) {
        return ChatModels.modelPrefKey(provider.id());
    }

    private static String displayModel(String model) {
        return model.isEmpty() ? MODEL_DEFAULT_LABEL : model;
    }

    private static String displayEffort(String effort) {
        return effort.isEmpty() ? EFFORT_DEFAULT_LABEL : effort;
    }
}
