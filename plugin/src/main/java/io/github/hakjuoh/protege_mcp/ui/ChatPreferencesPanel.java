package io.github.hakjuoh.protege_mcp.ui;

import io.github.hakjuoh.protege_mcp.chat.ChatClientProfile;
import io.github.hakjuoh.protege_mcp.chat.ChatClients;
import io.github.hakjuoh.protege_mcp.chat.ChatModelCatalog;
import io.github.hakjuoh.protege_mcp.config.McpConfig;

import org.protege.editor.core.prefs.Preferences;
import org.protege.editor.core.ui.preferences.PreferencesPanel;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

/**
 * Preferences for the in-Protégé chat, divided into one editor per client profile plus shared
 * access and privacy settings. Predefined profiles retain stable ids and legacy preference keys
 * while their user-visible names, executable paths, and model catalogs are edited independently.
 */
public class ChatPreferencesPanel extends PreferencesPanel {

    private static final long serialVersionUID = 1L;
    // Keep the preferred size inside Protégé's 850 px default Preferences viewport after the
    // outer group-label column is added. GridBag weight/fill still lets the tabs consume every
    // additional pixel when the dialog grows.
    private static final int PREFERRED_EDITOR_WIDTH = 600;
    private static final int PREFERRED_EDITOR_HEIGHT = 520;

    private final List<ChatClientEditor> clientEditors = new ArrayList<>();
    private JTabbedPane clientTabs;
    private JCheckBox allowWrites;

    @Override
    public void initialise() throws Exception {
        setLayout(new BorderLayout());
        Preferences p = McpConfig.prefs();

        ResponsivePreferencesLayoutPanel panel = new ResponsivePreferencesLayoutPanel();
        panel.addGroup("Assistant clients");
        panel.addHelpText(
                "Each tab is an independent client profile with its own name, executable path, and"
                        + " model catalog. The predefined name can be changed without changing the"
                        + " profile's stable identity or losing its settings.");

        clientTabs = new JTabbedPane();
        clientTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        for (ChatClientProfile client : ChatClients.predefined()) {
            ChatClientEditor editor = new ChatClientEditor(client, p);
            clientEditors.add(editor);
            int tabIndex = clientTabs.getTabCount();
            clientTabs.addTab(editor.displayName(), editor.component());
            clientTabs.setToolTipTextAt(tabIndex, editor.displayName());
            editor.followDisplayName(
                    () -> {
                        String displayName = editor.displayName();
                        clientTabs.setTitleAt(tabIndex, displayName);
                        clientTabs.setToolTipTextAt(tabIndex, displayName);
                    });
        }
        clientTabs.addTab("General", generalComponent(p));
        clientTabs.setPreferredSize(
                new Dimension(
                        PREFERRED_EDITOR_WIDTH,
                        Math.max(PREFERRED_EDITOR_HEIGHT, clientTabs.getPreferredSize().height)));
        panel.addGroupComponent(clientTabs);

        add(panel, BorderLayout.NORTH);
    }

    private JPanel generalComponent(Preferences p) {
        ResponsivePreferencesLayoutPanel panel = new ResponsivePreferencesLayoutPanel();
        panel.addGroup("Assistant access");
        allowWrites =
                new JCheckBox(
                        "Allow the Ontology Assistant to edit the ontology and project",
                        p.getBoolean(McpConfig.KEY_CHAT_ALLOW_WRITES, true));
        panel.addGroupComponent(allowWrites);
        panel.addHelpText(
                "Each chat turn receives its own short-lived credential. Disabling this keeps chat"
                    + " usable for ontology reads but rejects edits. When enabled, the credential"
                    + " is still limited to ontology/project operations: it has no server-admin,"
                    + " external-file, network, or unrestricted local-admin authority. MCP"
                    + " read-only and confirm-write settings remain hard limits.");

        panel.addGroup("Privacy");
        panel.addHelpText(
                "The chat sends your prompts, any attachments or pasted content you include, and"
                    + " the ontology content the assistant reads to your model provider via the"
                    + " CLI. Switching providers also sends the conversation turns the newly active"
                    + " provider missed. Edits obey the MCP server's read-only / confirm-write"
                    + " settings (Preferences ▸ MCP).");

        JPanel root = new JPanel(new BorderLayout());
        root.add(panel, BorderLayout.NORTH);
        return root;
    }

    @Override
    public void applyChanges() {
        Preferences p = McpConfig.prefs();
        for (ChatClientEditor editor : clientEditors) {
            editor.save(p);
        }
        p.putBoolean(McpConfig.KEY_CHAT_ALLOW_WRITES, allowWrites.isSelected());
        // Last, so an open Assistant re-reads settled catalogs and repaints renamed clients. A model
        // selection this edit deleted has already been cleared and must fall back to (default).
        ChatModelCatalog.fireChanged();
    }

    @Override
    public void dispose() throws Exception {
        for (ChatClientEditor editor : clientEditors) {
            editor.dispose();
        }
    }
}
