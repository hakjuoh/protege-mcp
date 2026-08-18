package io.github.hakjuoh.protege_mcp.ui;

import io.github.hakjuoh.protege_mcp.chat.ChatClientModelCatalog;
import io.github.hakjuoh.protege_mcp.chat.ChatClientPreferences;
import io.github.hakjuoh.protege_mcp.chat.ChatClientProfile;
import io.github.hakjuoh.protege_mcp.chat.ChatModelCatalog;

import org.protege.editor.core.prefs.Preferences;

import java.awt.BorderLayout;
import java.util.List;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** One self-contained Preferences tab for a stable client profile. */
final class ChatClientEditor {

    private final ChatClientProfile client;
    private final JTextField displayName;
    private final JTextField executablePath;
    private final JLabel detectionStatus = new JLabel();
    private final ClientInstallationGuidePanel installationGuide;
    private final ChatModelEditor models;

    ChatClientEditor(ChatClientProfile client, Preferences preferences) {
        this.client = client;
        displayName = new JTextField(ChatClientPreferences.displayName(preferences, client), 30);
        executablePath =
                new JTextField(
                        preferences.getString(
                                ChatClientPreferences.executablePathPrefKey(client.id()), ""),
                        30);
        installationGuide =
                client.adapter()
                        .installationGuide()
                        .map(ClientInstallationGuidePanel::new)
                        .orElse(null);
        models =
                new ChatModelEditor(
                        new ChatClientModelCatalog(client),
                        "Models",
                        preferences,
                        executablePath::getText);
    }

    JPanel component() {
        ResponsivePreferencesLayoutPanel panel = new ResponsivePreferencesLayoutPanel();
        panel.addGroup("Client");
        panel.addGroupComponent(PreferencesRows.labelled("Name:", displayName));
        panel.addHelpText(
                "This is the label shown in the Assistant. Changing it does not change the client "
                        + "type or its saved conversation and model settings.");
        panel.addGroupComponent(
                PreferencesRows.labelled(
                        client.executable() + " path (optional):", executablePath));
        panel.addGroupComponent(detectionStatus);
        panel.addHelpText(
                "Leave the path blank to auto-detect on PATH and common install directories. Set"
                    + " the executable or its directory when a GUI-launched Protégé cannot find"
                    + " it.");

        if (installationGuide != null) {
            panel.addGroup("Install or update");
            panel.addGroupComponent(installationGuide);
            panel.addHelpText(
                    client.adapter().installationGuide().orElseThrow().firstRunInstruction()
                            + " If Protégé still reports not found, restart it or set the"
                            + " executable path above.");
        }

        panel.addGroup("Available models");
        panel.addHelpText(
                "This catalog belongs only to this client. Use Add or Enter to add a model, select"
                    + " a row to update its id and reasoning efforts or reorder it, and X to delete"
                    + " it. Refresh adds client-provided models and fills effort lists that have"
                    + " not been saved here; it does not replace your saved effort lists. A list"
                    + " holds up to "
                        + ChatModelCatalog.maxModels()
                        + " ids. An empty list delegates model selection to "
                        + "the client. Changes are stored only when you click OK.");
        // The catalog is the only vertically elastic part of a client tab. Giving its row the
        // spare height keeps the form anchored at the top and leaves several model rows visible.
        panel.addExpandingGroupComponent(models.component());

        executablePath.getDocument().addDocumentListener(documentListener(this::refreshDetection));
        refreshDetection();
        JPanel root = new JPanel(new BorderLayout());
        root.add(panel, BorderLayout.CENTER);
        return root;
    }

    void followDisplayName(Runnable listener) {
        displayName.getDocument().addDocumentListener(documentListener(listener));
    }

    String displayName() {
        return ChatClientPreferences.normalizeDisplayName(
                displayName.getText(), client.defaultDisplayName());
    }

    private void refreshDetection() {
        detectionStatus.setText(
                ChatPreferencesSupport.detect(client.executable(), executablePath.getText()));
    }

    void save(Preferences preferences) {
        ChatClientPreferences.saveDisplayName(preferences, client, displayName.getText());
        preferences.putString(
                ChatClientPreferences.executablePathPrefKey(client.id()),
                executablePath.getText().trim());
        List<String> catalog = models.save(preferences);
        if (models.isDirty()) {
            ChatPreferencesSupport.clearMissingModelSelection(preferences, client.id(), catalog);
        }
    }

    void dispose() {
        models.dispose();
    }

    private static DocumentListener documentListener(Runnable listener) {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                listener.run();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                listener.run();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                listener.run();
            }
        };
    }
}
