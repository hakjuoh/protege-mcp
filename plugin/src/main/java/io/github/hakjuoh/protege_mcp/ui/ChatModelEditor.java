package io.github.hakjuoh.protege_mcp.ui;

import io.github.hakjuoh.protege_mcp.chat.ChatClientModelCatalog;
import io.github.hakjuoh.protege_mcp.chat.ChatClientPreferences;
import io.github.hakjuoh.protege_mcp.chat.ChatModelCatalog;
import io.github.hakjuoh.protege_mcp.chat.ChatModelDefinition;
import io.github.hakjuoh.protege_mcp.chat.ChatReasoningEfforts;

import org.protege.editor.core.prefs.Preferences;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.Box;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * EDT-confined editor for one client's staged model catalog.
 *
 * <p>Edits stay in this component until {@link #save(Preferences)} is called by the Preferences
 * dialog's OK/apply path. Discovery performs CLI work in a {@link SwingWorker}; its completion runs
 * on the EDT, and generation-based cancellation prevents a late result from mutating an editor that
 * has already been saved or disposed.
 */
final class ChatModelEditor {

    private static final int ACTION_BUTTON_WIDTH = 24;
    private static final int ACTION_BUTTON_HEIGHT = 22;
    private static final int ACTION_GAP = 2;
    private static final int CELL_HEIGHT = 42;

    private final ChatClientModelCatalog clientCatalog;
    private final String title;
    private final DefaultListModel<String> modelData = new DefaultListModel<>();
    private final Map<String, List<String>> effortsByModel = new LinkedHashMap<>();
    private final Set<String> modelsWithSavedEfforts = new LinkedHashSet<>();
    private final JList<String> modelList =
            new JList<>(modelData) {
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
    private final JTextField effortField = new JTextField(24);
    private final JLabel feedback = new JLabel(" ");
    private JButton applyButton;
    private JButton refreshButton;
    private JScrollPane modelScroll;
    private boolean syncingField;
    private boolean dirty;
    private boolean discoveryCompleted;
    private boolean autoDiscoveryPending;
    private boolean disposed;
    private long discoveryGeneration;
    private SwingWorker<List<ChatModelDefinition>, Void> discoveryWorker;
    private final Supplier<String> executableOverride;

    ChatModelEditor(
            ChatClientModelCatalog clientCatalog,
            String title,
            Preferences preferences,
            Supplier<String> executableOverride) {
        this.clientCatalog = clientCatalog;
        this.title = title;
        this.executableOverride = executableOverride;
        for (ChatModelDefinition definition : clientCatalog.loadDefinitions(preferences)) {
            modelData.addElement(definition.id());
            effortsByModel.put(definition.id(), definition.reasoningEfforts());
            if (clientCatalog.hasSavedReasoningEfforts(preferences, definition.id())) {
                modelsWithSavedEfforts.add(definition.id());
            }
        }
        // A stored empty catalog is intentional: it delegates selection to the CLI. Only a
        // genuinely unset catalog gets first-show discovery; Refresh remains explicit otherwise.
        boolean catalogWasNeverSaved =
                preferences.getString(clientCatalog.preferenceKey(), null) == null;
        autoDiscoveryPending =
                catalogWasNeverSaved
                        && modelData.isEmpty()
                        && !preferences.getBoolean(
                                ChatClientPreferences.modelDiscoveryCompletedPrefKey(
                                        clientCatalog.client().id()),
                                false);
        feedback.setForeground(new JLabel().getForeground());
    }

    JPanel component() {
        JPanel root = new JPanel(new BorderLayout(6, 4));
        JPanel header = new JPanel(new BorderLayout(6, 0));
        JLabel modelListLabel = new JLabel(title + ":");
        modelListLabel.setLabelFor(modelList);
        header.add(modelListLabel, BorderLayout.WEST);
        refreshButton = new JButton("Refresh models");
        refreshButton.setToolTipText(
                "Reload model ids and fill effort lists that have not "
                        + "been saved or edited in Preferences");
        refreshButton.addActionListener(event -> discoverModels());
        header.add(refreshButton, BorderLayout.EAST);
        root.add(header, BorderLayout.NORTH);

        modelList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        modelList.setFixedCellHeight(CELL_HEIGHT);
        modelList.setVisibleRowCount(Math.min(5, Math.max(3, modelData.size())));
        modelList.setCellRenderer(new ModelCellRenderer(effortsByModel));
        modelList.addListSelectionListener(
                event -> {
                    if (!event.getValueIsAdjusting()) {
                        syncFieldFromSelection();
                    }
                });
        installKeyboardActions();
        modelScroll = new JScrollPane(modelList);
        // Leave list/viewport colours to the look and feel: a hard-coded white background keeps
        // the LAF's own (light) foreground on a dark theme and makes the rows unreadable.
        modelScroll.getViewport().setBackground(modelList.getBackground());
        modelScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        modelScroll.setMinimumSize(new Dimension(0, CELL_HEIGHT * 3 + 3));
        updateModelScrollHeight();
        root.add(modelScroll, BorderLayout.CENTER);

        JPanel controls = new JPanel(new GridBagLayout());
        addFullWidthField(controls, 0, "Model ID:", modelField);
        addFullWidthField(controls, 1, "Reasoning efforts:", effortField);
        applyButton = new JButton();
        applyButton.addActionListener(e -> applyModel());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 2;
        constraints.gridy = 2;
        constraints.insets = new Insets(2, 0, 2, 0);
        constraints.anchor = GridBagConstraints.WEST;
        controls.add(applyButton, constraints);

        effortField.setToolTipText(
                "Comma-separated identifiers using ASCII letters, numbers, '.', '_' or '-'; "
                        + "blank offers only the client's default");
        effortField
                .getAccessibleContext()
                .setAccessibleDescription(
                        "Comma-separated reasoning effort identifiers for the selected model."
                            + " Allowed characters are ASCII letters, numbers, period, underscore"
                            + " and hyphen.");

        JLabel effortHelp =
                new JLabel(
                        "Comma-separated IDs (A-Z, 0-9, . _ -); blank uses the client default. ");
        effortHelp.setForeground(new JLabel().getForeground());
        constraints = new GridBagConstraints();
        constraints.gridy = 2;
        constraints.gridx = 1;
        constraints.gridwidth = 1;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(2, 0, 2, 6);
        controls.add(effortHelp, constraints);
        constraints.gridy = 3;
        constraints.gridx = 1;
        constraints.gridwidth = 2;
        constraints.insets = new Insets(2, 0, 2, 0);
        controls.add(feedback, constraints);
        root.add(controls, BorderLayout.SOUTH);
        modelField
                .getDocument()
                .addDocumentListener(
                        new DocumentListener() {
                            @Override
                            public void insertUpdate(DocumentEvent event) {
                                fieldChanged();
                            }

                            @Override
                            public void removeUpdate(DocumentEvent event) {
                                fieldChanged();
                            }

                            @Override
                            public void changedUpdate(DocumentEvent event) {
                                fieldChanged();
                            }
                        });
        effortField
                .getDocument()
                .addDocumentListener(
                        new DocumentListener() {
                            @Override
                            public void insertUpdate(DocumentEvent event) {
                                fieldChanged();
                            }

                            @Override
                            public void removeUpdate(DocumentEvent event) {
                                fieldChanged();
                            }

                            @Override
                            public void changedUpdate(DocumentEvent event) {
                                fieldChanged();
                            }
                        });
        // Enter in either field applies exactly like the Add/Update button, but only while there is
        // something to apply: Protégé stores nothing until the Preferences dialog's default OK
        // button is pressed, so an empty field has to leave Enter to that button.
        Action applyModelAction =
                new AbstractAction() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public boolean isEnabled() {
                        return !modelField.getText().isBlank();
                    }

                    @Override
                    public void actionPerformed(ActionEvent event) {
                        applyModel();
                    }
                };
        for (JTextField field : List.of(modelField, effortField)) {
            field.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "apply-model");
            field.getActionMap().put("apply-model", applyModelAction);
        }
        updateApplyButton();
        root.setPreferredSize(
                new Dimension(
                        PreferencesText.HELP_TEXT_DISPLAY_WIDTH_PX,
                        root.getPreferredSize().height));
        root.addHierarchyListener(
                event -> {
                    if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0
                            && root.isShowing()
                            && autoDiscoveryPending) {
                        autoDiscoveryPending = false;
                        discoverModels();
                    }
                });
        return root;
    }

    /** Adds identically constrained fields so their left and right edges cannot drift apart. */
    private static void addFullWidthField(
            JPanel controls, int row, String labelText, JTextField field) {
        JLabel label = new JLabel(labelText);
        label.setLabelFor(field);
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = row;
        labelConstraints.anchor = GridBagConstraints.WEST;
        labelConstraints.insets = new Insets(2, 0, 2, 6);
        controls.add(label, labelConstraints);

        GridBagConstraints fieldConstraints = new GridBagConstraints();
        fieldConstraints.gridx = 1;
        fieldConstraints.gridy = row;
        fieldConstraints.gridwidth = 2;
        fieldConstraints.weightx = 1;
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        fieldConstraints.insets = new Insets(2, 0, 2, 0);
        controls.add(field, fieldConstraints);
    }

    private void discoverModels() {
        if (disposed || discoveryWorker != null && !discoveryWorker.isDone()) {
            return;
        }
        refreshButton.setEnabled(false);
        feedback.setText("Loading models from " + clientCatalog.client().executable() + "…");
        String override = executableOverride.get();
        String userHome = System.getProperty("user.home", "");
        Path metadataHome = Path.of(userHome.isBlank() ? "." : userHome);
        long generation = ++discoveryGeneration;
        discoveryWorker =
                new SwingWorker<>() {
                    @Override
                    protected List<ChatModelDefinition> doInBackground() {
                        return clientCatalog
                                .client()
                                .adapter()
                                .discoverModelDefinitions(metadataHome, override);
                    }

                    @Override
                    protected void done() {
                        if (disposed || generation != discoveryGeneration || isCancelled()) {
                            return;
                        }
                        refreshButton.setEnabled(true);
                        try {
                            List<ChatModelDefinition> discovered = get();
                            if (discovered.isEmpty()) {
                                feedback.setText(
                                        "No models found — check client installation and"
                                                + " configuration.");
                                return;
                            }
                            int changed = mergeModels(discovered);
                            discoveryCompleted = true;
                            feedback.setText(
                                    changed == 0
                                            ? "Models are up to date."
                                            : "Updated "
                                                    + changed
                                                    + " model entries — click OK to save.");
                        } catch (Exception failure) {
                            feedback.setText(
                                    "Could not load models — run the CLI in a terminal for"
                                            + " details.");
                        }
                    }
                };
        discoveryWorker.execute();
    }

    private int mergeModels(List<ChatModelDefinition> discovered) {
        int changed = 0;
        String selectedModel = modelList.getSelectedValue();
        boolean selectedEffortsChanged = false;
        for (ChatModelDefinition definition : discovered) {
            String normalized = definition == null ? "" : definition.id().trim();
            if (!ChatModelCatalog.isAcceptableModelId(normalized)) {
                continue;
            }
            if (contains(normalized)) {
                // A Preferences edit is authoritative. Refresh enriches legacy/unconfigured
                // rows but never silently replaces a list the user has already saved.
                if (modelsWithSavedEfforts.contains(normalized)) {
                    continue;
                }
                List<String> previous = effortsByModel.getOrDefault(normalized, List.of());
                if (!previous.equals(definition.reasoningEfforts())) {
                    effortsByModel.put(normalized, definition.reasoningEfforts());
                    selectedEffortsChanged |= normalized.equals(selectedModel);
                    changed++;
                }
                continue;
            }
            if (modelData.size() >= ChatModelCatalog.maxModels()) {
                break;
            }
            modelData.addElement(normalized);
            effortsByModel.put(normalized, definition.reasoningEfforts());
            changed++;
        }
        if (changed > 0) {
            dirty = true;
            modelList.setVisibleRowCount(Math.min(5, Math.max(3, modelData.size())));
            updateModelScrollHeight();
            modelList.repaint();
        }
        if (selectedEffortsChanged) {
            syncFieldFromSelection();
        }
        return changed;
    }

    private void updateModelScrollHeight() {
        if (modelScroll == null) {
            return;
        }
        modelScroll.setPreferredSize(
                new Dimension(
                        PreferencesText.HELP_TEXT_DISPLAY_WIDTH_PX,
                        CELL_HEIGHT * modelList.getVisibleRowCount() + 3));
        modelScroll.revalidate();
    }

    private void installKeyboardActions() {
        modelList.getInputMap().put(KeyStroke.getKeyStroke("DELETE"), "delete-model");
        modelList.getInputMap().put(KeyStroke.getKeyStroke("alt UP"), "move-model-up");
        modelList.getInputMap().put(KeyStroke.getKeyStroke("alt DOWN"), "move-model-down");
        modelList.getActionMap().put("delete-model", selectedModelAction(0));
        modelList.getActionMap().put("move-model-up", selectedModelAction(-1));
        modelList.getActionMap().put("move-model-down", selectedModelAction(1));
        modelList
                .getAccessibleContext()
                .setAccessibleDescription(
                        "Ordered model ids. Delete removes a row; Alt+Up and Alt+Down reorder it.");
    }

    private Action selectedModelAction(int direction) {
        return new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent event) {
                int selected = modelList.getSelectedIndex();
                if (selected < 0) {
                    return;
                }
                if (direction == 0) {
                    deleteModel(selected);
                } else {
                    moveModel(selected, direction);
                }
            }
        };
    }

    private void applyModel() {
        String model = normalizedField();
        if (model == null) {
            return;
        }
        ChatReasoningEfforts.ParseResult effortResult =
                ChatReasoningEfforts.parseEditorText(effortField.getText());
        if (!effortResult.valid()) {
            feedback.setText(effortResult.error());
            return;
        }
        int selectedIndex = modelList.getSelectedIndex();
        if (selectedIndex < 0) {
            if (contains(model)) {
                feedback.setText("That model id is already listed.");
                return;
            }
            if (modelData.size() >= ChatModelCatalog.maxModels()) {
                feedback.setText(
                        "This list holds at most "
                                + ChatModelCatalog.maxModels()
                                + " model ids — delete one first.");
                return;
            }
            modelData.addElement(model);
            effortsByModel.put(model, effortResult.values());
            modelsWithSavedEfforts.add(model);
            dirty = true;
            setFields("", List.of());
            modelList.clearSelection();
            setStagedFeedback("Added " + model);
            return;
        }
        if (containsOtherThan(model, selectedIndex)) {
            feedback.setText("That model id is already listed.");
            return;
        }
        String previous = modelData.get(selectedIndex);
        modelData.set(selectedIndex, model);
        if (!previous.equals(model)) {
            effortsByModel.remove(previous);
            modelsWithSavedEfforts.remove(previous);
        }
        effortsByModel.put(model, effortResult.values());
        modelsWithSavedEfforts.add(model);
        dirty = true;
        modelList.repaint();
        setStagedFeedback("Updated model and reasoning efforts");
    }

    private void syncFieldFromSelection() {
        String selected = modelList.getSelectedValue();
        setFields(
                selected == null ? "" : selected,
                selected == null ? List.of() : effortsByModel.getOrDefault(selected, List.of()));
        feedback.setText(" ");
        updateApplyButton();
        modelList.repaint();
    }

    private void fieldChanged() {
        if (syncingField) {
            return;
        }
        if (modelField.getText().isBlank()) {
            SwingUtilities.invokeLater(
                    () -> {
                        if (!syncingField && modelField.getText().isBlank()) {
                            modelList.clearSelection();
                            updateApplyButton();
                        }
                    });
        }
        updateApplyButton();
    }

    private void setFields(String model, List<String> efforts) {
        syncingField = true;
        try {
            modelField.setText(model);
            effortField.setText(ChatReasoningEfforts.editorText(efforts));
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
        applyButton.setToolTipText(
                updating
                        ? "Update the selected model and its reasoning efforts (Enter)"
                        : "Add this model and its reasoning efforts (Enter)");
    }

    private void deleteModel(int index) {
        String removed = modelData.remove(index);
        effortsByModel.remove(removed);
        modelsWithSavedEfforts.remove(removed);
        dirty = true;
        modelList.clearSelection();
        setFields("", List.of());
        updateApplyButton();
        setStagedFeedback("Deleted " + removed);
    }

    private void moveModel(int index, int direction) {
        int target = index + direction;
        if (target < 0 || target >= modelData.size()) {
            feedback.setText(
                    direction < 0 ? "The model is already first." : "The model is already last.");
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
        int fromRight =
                modelList.getCellBounds(index, index).x
                        + modelList.getCellBounds(index, index).width
                        - event.getX();
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
            feedback.setText(
                    "A model id can be at most "
                            + ChatModelCatalog.maxModelIdChars()
                            + " characters.");
            return null;
        }
        if (!ChatModelCatalog.isAcceptableModelId(model)) {
            feedback.setText(
                    "Enter a non-empty model id other than (default), with no line breaks.");
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

    List<String> save(Preferences preferences) {
        // OK during a slow refresh commits exactly what is currently visible. Prevent a late
        // worker callback from mutating the closed, already-saved editor afterward.
        cancelDiscovery();
        List<String> models = models();
        if (dirty) {
            clientCatalog.saveDefinitions(preferences, definitions());
        }
        if (discoveryCompleted) {
            preferences.putBoolean(
                    ChatClientPreferences.modelDiscoveryCompletedPrefKey(
                            clientCatalog.client().id()),
                    true);
        }
        return models;
    }

    void dispose() {
        disposed = true;
        cancelDiscovery();
    }

    private void cancelDiscovery() {
        discoveryGeneration++;
        if (discoveryWorker != null) {
            discoveryWorker.cancel(true);
        }
    }

    boolean isDirty() {
        return dirty;
    }

    private List<String> models() {
        List<String> models = new ArrayList<>();
        for (int i = 0; i < modelData.size(); i++) {
            models.add(modelData.get(i));
        }
        return List.copyOf(models);
    }

    private List<ChatModelDefinition> definitions() {
        return models().stream()
                .map(
                        model ->
                                new ChatModelDefinition(
                                        model, effortsByModel.getOrDefault(model, List.of())))
                .toList();
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
    private static final class ModelCellRenderer extends JPanel
            implements ListCellRenderer<String> {

        private static final long serialVersionUID = 1L;
        private final JLabel modelLabel = new JLabel();
        private final JLabel effortLabel = new JLabel();
        private final Box actions = Box.createHorizontalBox();
        private final Map<String, List<String>> effortsByModel;

        private ModelCellRenderer(Map<String, List<String>> effortsByModel) {
            super(new BorderLayout(4, 0));
            this.effortsByModel = effortsByModel;
            setOpaque(true);
            Box labels = Box.createVerticalBox();
            labels.add(modelLabel);
            labels.add(effortLabel);
            add(labels, BorderLayout.CENTER);
            add(actions, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends String> list,
                String value,
                int index,
                boolean selected,
                boolean hasFocus) {
            modelLabel.setText(value);
            List<String> efforts = effortsByModel.getOrDefault(value, List.of());
            effortLabel.setText(
                    "Reasoning: " + (efforts.isEmpty() ? "none" : String.join(", ", efforts)));
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            modelLabel.setForeground(
                    selected ? list.getSelectionForeground() : list.getForeground());
            effortLabel.setForeground(
                    selected ? list.getSelectionForeground() : list.getForeground());
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
