package io.github.hakjuoh.protege_mcp.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.Box;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JCheckBox;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.protege.editor.core.prefs.Preferences;
import org.protege.editor.core.ui.preferences.PreferencesLayoutPanel;
import org.protege.editor.core.ui.preferences.PreferencesPanel;
import io.github.hakjuoh.protege_mcp.chat.CliSupport;
import io.github.hakjuoh.protege_mcp.chat.ChatModelCatalog;
import io.github.hakjuoh.protege_mcp.chat.ChatModels;
import io.github.hakjuoh.protege_mcp.chat.claude.ClaudeCliProvider;
import io.github.hakjuoh.protege_mcp.chat.codex.CodexCliProvider;
import io.github.hakjuoh.protege_mcp.config.McpConfig;

/**
 * Preferences for the in-Protégé chat (Architecture Approach B): optional CLI path overrides (a
 * Finder/Dock-launched Protégé often has a minimal {@code PATH}, so {@code claude}/{@code codex} may
 * not auto-resolve) and a non-blocking privacy disclosure. There is deliberately no API-key field —
 * each CLI uses the user's existing login.
 */
public class ChatPreferencesPanel extends PreferencesPanel {

    private static final long serialVersionUID = 1L;

    private JTextField claudePath;
    private JTextField codexPath;
    private JLabel claudeStatus;
    private JLabel codexStatus;
    private JCheckBox allowWrites;
    private ModelEditor claudeModels;
    private ModelEditor codexModels;

    @Override
    public void initialise() throws Exception {
        setLayout(new BorderLayout());
        Preferences p = McpConfig.prefs();

        PreferencesLayoutPanel panel = new PreferencesLayoutPanel();

        panel.addGroup("Coding-agent CLIs");
        panel.addHelpText(PreferencesText.wrapped(
                "The chat drives a locally-installed coding-agent CLI, which connects back to "
                + "Protégé's MCP server to edit the live ontology. Install and log in to Claude Code "
                + "(claude) and/or Codex (codex). No API key is stored here — each CLI uses your own login."));

        claudePath = new JTextField(p.getString(McpConfig.KEY_CHAT_CLAUDE_PATH, ""), 30);
        panel.addGroupComponent(PreferencesRows.labelled("claude path (optional):", claudePath));
        claudeStatus = new JLabel();
        panel.addGroupComponent(claudeStatus);

        codexPath = new JTextField(p.getString(McpConfig.KEY_CHAT_CODEX_PATH, ""), 30);
        panel.addGroupComponent(PreferencesRows.labelled("codex path (optional):", codexPath));
        codexStatus = new JLabel();
        panel.addGroupComponent(codexStatus);

        panel.addHelpText(PreferencesText.wrapped(
                "Leave blank to auto-detect on PATH and common install dirs. Set the full path "
                + "to the executable if Protégé (launched from Finder/Dock) cannot find it."));

        panel.addGroup("Available models");
        panel.addHelpText(PreferencesText.wrapped(
                "Model ids start from the model you already had selected plus whatever the installed "
                + "CLI's local metadata names. If neither is available, only (default) is shown. "
                + "Use Add or Enter for a new id or select a row and use Update. "
                + "Delete with X independently for each CLI. Select a row to reorder it with ↑ and ↓. "
                + "A list holds up to " + ChatModelCatalog.maxModels() + " ids. An empty list means no "
                + "model argument is sent, so the CLI uses its own configured default. Edits here are "
                + "saved when you click OK and discarded if you cancel; an already-open Ontology "
                + "Assistant picks them up on OK."));
        claudeModels = new ModelEditor("claude", "Claude Code models", p);
        panel.addGroupComponent(claudeModels.component());
        codexModels = new ModelEditor("codex", "Codex models", p);
        panel.addGroupComponent(codexModels.component());

        panel.addGroup("Assistant access");
        allowWrites = new JCheckBox("Allow the Ontology Assistant to edit the ontology and project",
                p.getBoolean(McpConfig.KEY_CHAT_ALLOW_WRITES, true));
        panel.addGroupComponent(allowWrites);
        panel.addHelpText(PreferencesText.wrapped(
                "Each chat turn receives its own short-lived credential. Disabling this keeps chat "
                + "usable for ontology reads but rejects edits. When enabled, the credential is still "
                + "limited to ontology/project operations: it has no server-admin, external-file, "
                + "network, or unrestricted local-admin authority. MCP read-only and confirm-write "
                + "settings remain hard limits."));

        panel.addGroup("Privacy");
        panel.addHelpText(PreferencesText.wrapped(
                "The chat sends your prompts, any attachments or pasted content you include, and the "
                + "ontology content the assistant reads to your model provider via the CLI. Switching providers "
                + "also sends the conversation turns the newly active provider missed. Edits obey the MCP "
                + "server's read-only / confirm-write settings (Preferences ▸ MCP)."));

        add(panel, BorderLayout.NORTH);
        refreshDetection();
    }

    private void refreshDetection() {
        claudeStatus.setText(detect(ClaudeCliProvider.EXECUTABLE, claudePath.getText()));
        codexStatus.setText(detect(CodexCliProvider.EXECUTABLE, codexPath.getText()));
    }

    private static String detect(String exe, String override) {
        String resolved = CliSupport.resolveExecutable(exe, override);
        return resolved == null ? "    not found" : "    found: " + resolved;
    }

    @Override
    public void applyChanges() {
        Preferences p = McpConfig.prefs();
        p.putString(McpConfig.KEY_CHAT_CLAUDE_PATH, claudePath.getText().trim());
        p.putString(McpConfig.KEY_CHAT_CODEX_PATH, codexPath.getText().trim());
        List<String> claudeCatalog = claudeModels.save(p);
        if (claudeModels.isDirty()) {
            clearMissingModelSelection(p, "claude", claudeCatalog);
        }
        List<String> codexCatalog = codexModels.save(p);
        if (codexModels.isDirty()) {
            clearMissingModelSelection(p, "codex", codexCatalog);
        }
        p.putBoolean(McpConfig.KEY_CHAT_ALLOW_WRITES, allowWrites.isSelected());
        // Last, so an open Assistant re-reads a settled catalog: a selection this edit deleted has
        // already been cleared above, and must fall back to (default) rather than linger in the picker.
        ChatModelCatalog.fireChanged();
    }

    private static void clearMissingModelSelection(Preferences preferences, String providerId,
            List<String> catalog) {
        String selected = preferences.getString(ChatModels.modelPrefKey(providerId), "");
        if (!selected.isBlank() && !catalog.contains(selected)) {
            preferences.putString(ChatModels.modelPrefKey(providerId), "");
        }
    }

    @Override
    public void dispose() throws Exception {
        // nothing to release
    }

    /** Small reusable editor for one provider's ordered model-id list. */
    private static final class ModelEditor {

        private static final int ACTION_BUTTON_WIDTH = 24;
        private static final int ACTION_BUTTON_HEIGHT = 22;
        private static final int ACTION_GAP = 2;
        private static final int CELL_HEIGHT = 26;

        private final String providerId;
        private final String title;
        private final DefaultListModel<String> modelData = new DefaultListModel<>();
        private final JList<String> modelList = new JList<>(modelData) {
            private static final long serialVersionUID = 1L;

            @Override
            protected void processMouseEvent(MouseEvent event) {
                if (event.getID() == MouseEvent.MOUSE_PRESSED
                        && SwingUtilities.isLeftMouseButton(event)) {
                    int index = locationToIndex(event.getPoint());
                    if (index < 0 || !getCellBounds(index, index).contains(event.getPoint())) {
                        clearSelection();
                        return;
                    }
                    if (handleActionClick(event)) {
                        return;
                    }
                }
                super.processMouseEvent(event);
            }
        };
        private final JTextField modelField = new JTextField(24);
        private final JLabel feedback = new JLabel(" ");
        private JButton applyButton;
        private JScrollPane modelScroll;
        private boolean syncingField;
        private boolean dirty;

        private ModelEditor(String providerId, String title, Preferences preferences) {
            this.providerId = providerId;
            this.title = title;
            for (String model : ChatModelCatalog.load(preferences, providerId)) {
                modelData.addElement(model);
            }
            feedback.setForeground(new JLabel().getForeground());
        }

        private JPanel component() {
            JPanel root = new JPanel(new BorderLayout(6, 4));
            root.add(new JLabel(title + ":"), BorderLayout.NORTH);

            modelList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            modelList.setFixedCellHeight(CELL_HEIGHT);
            modelList.setVisibleRowCount(Math.min(5, Math.max(3, modelData.size())));
            modelList.setCellRenderer(new ModelCellRenderer());
            modelList.addListSelectionListener(event -> {
                if (!event.getValueIsAdjusting()) {
                    syncFieldFromSelection();
                }
            });
            modelScroll = new JScrollPane(modelList);
            // Leave list/viewport colours to the look and feel: a hard-coded white background keeps
            // the LAF's own (light) foreground on a dark theme and makes the rows unreadable.
            modelScroll.getViewport().setBackground(modelList.getBackground());
            modelScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            modelScroll.setPreferredSize(new Dimension(
                    PreferencesText.HELP_TEXT_DISPLAY_WIDTH_PX,
                    CELL_HEIGHT * modelList.getVisibleRowCount() + 3));
            root.add(modelScroll, BorderLayout.CENTER);

            JPanel controls = new JPanel(new BorderLayout(6, 4));
            controls.add(modelField, BorderLayout.CENTER);
            applyButton = new JButton();
            applyButton.addActionListener(e -> applyModel());
            controls.add(applyButton, BorderLayout.EAST);
            controls.add(feedback, BorderLayout.SOUTH);
            root.add(controls, BorderLayout.SOUTH);
            modelField.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent event) { fieldChanged(); }
                @Override public void removeUpdate(DocumentEvent event) { fieldChanged(); }
                @Override public void changedUpdate(DocumentEvent event) { fieldChanged(); }
            });
            // Enter applies the field exactly like the Add/Update button, but only while there is
            // something to apply: Protégé stores nothing until the Preferences dialog's default OK
            // button is pressed, so an empty field has to leave Enter to that button.
            modelField.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "apply-model");
            modelField.getActionMap().put("apply-model", new AbstractAction() {
                private static final long serialVersionUID = 1L;

                @Override
                public boolean isEnabled() {
                    return !modelField.getText().isBlank();
                }

                @Override
                public void actionPerformed(ActionEvent event) {
                    applyModel();
                }
            });
            updateApplyButton();
            root.setPreferredSize(new Dimension(
                    PreferencesText.HELP_TEXT_DISPLAY_WIDTH_PX,
                    root.getPreferredSize().height));
            return root;
        }

        private void applyModel() {
            String model = normalizedField();
            if (model == null) {
                return;
            }
            int selectedIndex = modelList.getSelectedIndex();
            if (selectedIndex < 0) {
                if (contains(model)) {
                    feedback.setText("That model id is already listed.");
                    return;
                }
                if (modelData.size() >= ChatModelCatalog.maxModels()) {
                    feedback.setText("This list holds at most " + ChatModelCatalog.maxModels()
                            + " model ids — delete one first.");
                    return;
                }
                modelData.addElement(model);
                dirty = true;
                setFieldText("");
                modelList.clearSelection();
                setStagedFeedback("Added " + model);
                return;
            }
            if (containsOtherThan(model, selectedIndex)) {
                feedback.setText("That model id is already listed.");
                return;
            }
            modelData.set(selectedIndex, model);
            dirty = true;
            modelList.repaint();
            setStagedFeedback("Updated model id");
        }

        private void syncFieldFromSelection() {
            String selected = modelList.getSelectedValue();
            setFieldText(selected == null ? "" : selected);
            feedback.setText(" ");
            updateApplyButton();
            modelList.repaint();
        }

        private void fieldChanged() {
            if (syncingField) {
                return;
            }
            if (modelField.getText().isBlank()) {
                SwingUtilities.invokeLater(() -> {
                    if (!syncingField && modelField.getText().isBlank()) {
                        modelList.clearSelection();
                        updateApplyButton();
                    }
                });
            }
            updateApplyButton();
        }

        private void setFieldText(String value) {
            syncingField = true;
            try {
                modelField.setText(value);
            } finally {
                syncingField = false;
            }
        }

        /** Reports a change the panel is holding: only OK'ing the dialog makes Protégé store it. */
        private void setStagedFeedback(String change) {
            feedback.setText(change + " — click OK to save.");
        }

        private void updateApplyButton() {
            if (applyButton == null) {
                return;
            }
            boolean updating = !modelList.isSelectionEmpty();
            applyButton.setText(updating ? "Update" : "Add");
            applyButton.setToolTipText(updating
                    ? "Update the selected model id (Enter)" : "Add this model id (Enter)");
        }

        private void deleteModel(int index) {
            String removed = modelData.remove(index);
            dirty = true;
            modelList.clearSelection();
            setFieldText("");
            updateApplyButton();
            setStagedFeedback("Deleted " + removed);
        }

        private void moveModel(int index, int direction) {
            int target = index + direction;
            if (target < 0 || target >= modelData.size()) {
                feedback.setText(direction < 0 ? "The model is already first." : "The model is already last.");
                return;
            }
            String moved = modelData.get(index);
            List<String> reordered = ChatModelCatalog.moveModel(models(), index, direction);
            replaceModels(reordered);
            modelList.setSelectedIndex(target);
            modelList.ensureIndexIsVisible(target);
            dirty = true;
            setStagedFeedback("Moved " + moved);
        }

        private boolean handleActionClick(MouseEvent event) {
            int index = modelList.locationToIndex(event.getPoint());
            if (index < 0 || !modelList.getCellBounds(index, index).contains(event.getPoint())) {
                return false;
            }
            int fromRight = modelList.getCellBounds(index, index).x
                    + modelList.getCellBounds(index, index).width - event.getX();
            if (fromRight <= ACTION_BUTTON_WIDTH) {
                deleteModel(index);
                return true;
            }
            if (index != modelList.getSelectedIndex()) {
                return false;
            }
            if (fromRight <= ACTION_BUTTON_WIDTH * 2 + ACTION_GAP) {
                moveModel(index, 1);
                return true;
            } else if (fromRight <= ACTION_BUTTON_WIDTH * 3 + ACTION_GAP * 2) {
                moveModel(index, -1);
                return true;
            }
            return false;
        }

        private String normalizedField() {
            String model = modelField.getText().trim();
            if (model.length() > ChatModelCatalog.maxModelIdChars()) {
                feedback.setText("A model id can be at most "
                        + ChatModelCatalog.maxModelIdChars() + " characters.");
                return null;
            }
            if (!ChatModelCatalog.isAcceptableModelId(model)) {
                feedback.setText("Enter a non-empty model id other than (default), with no line breaks.");
                return null;
            }
            return model;
        }


        private boolean contains(String model) {
            return containsOtherThan(model, -1);
        }

        private boolean containsOtherThan(String model, int ignoredIndex) {
            for (int i = 0; i < modelData.size(); i++) {
                if (i != ignoredIndex && model.equals(modelData.get(i))) {
                    return true;
                }
            }
            return false;
        }

        private List<String> save(Preferences preferences) {
            List<String> models = models();
            if (dirty) {
                ChatModelCatalog.save(preferences, providerId, models);
            }
            return models;
        }

        private boolean isDirty() {
            return dirty;
        }

        private List<String> models() {
            List<String> models = new ArrayList<>();
            for (int i = 0; i < modelData.size(); i++) {
                models.add(modelData.get(i));
            }
            return List.copyOf(models);
        }

        private void replaceModels(List<String> models) {
            modelData.clear();
            for (String model : models) {
                modelData.addElement(model);
            }
        }

        private static JButton compactButton(String text, boolean enabled) {
            JButton button = new JButton(text);
            button.setEnabled(enabled);
            button.setFocusable(false);
            button.setMargin(new Insets(0, 2, 0, 2));
            button.setPreferredSize(new Dimension(ACTION_BUTTON_WIDTH, ACTION_BUTTON_HEIGHT));
            button.setMinimumSize(button.getPreferredSize());
            button.setMaximumSize(button.getPreferredSize());
            return button;
        }

        /** Paints native list selection while exposing compact row-action affordances. */
        private static final class ModelCellRenderer extends JPanel implements ListCellRenderer<String> {

            private static final long serialVersionUID = 1L;
            private final JLabel label = new JLabel();
            private final Box actions = Box.createHorizontalBox();

            private ModelCellRenderer() {
                super(new BorderLayout(4, 0));
                setOpaque(true);
                add(label, BorderLayout.CENTER);
                add(actions, BorderLayout.EAST);
            }

            @Override
            public Component getListCellRendererComponent(JList<? extends String> list, String value,
                    int index, boolean selected, boolean hasFocus) {
                label.setText(value);
                setBackground(selected ? list.getSelectionBackground() : list.getBackground());
                label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
                actions.removeAll();
                if (selected) {
                    actions.add(compactButton("↑", index > 0));
                    actions.add(Box.createHorizontalStrut(ACTION_GAP));
                    actions.add(compactButton("↓", index < list.getModel().getSize() - 1));
                    actions.add(Box.createHorizontalStrut(ACTION_GAP));
                }
                actions.add(compactButton("X", true));
                return this;
            }
        }
    }
}
