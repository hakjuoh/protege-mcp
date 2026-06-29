package io.github.hakjuoh.protege_mcp.ui;

import java.awt.BorderLayout;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

import org.protege.editor.core.prefs.Preferences;
import org.protege.editor.core.ui.preferences.PreferencesLayoutPanel;
import org.protege.editor.core.ui.preferences.PreferencesPanel;
import io.github.hakjuoh.protege_mcp.chat.CliSupport;
import io.github.hakjuoh.protege_mcp.chat.claude.ClaudeCliProvider;
import io.github.hakjuoh.protege_mcp.chat.codex.CodexCliProvider;
import io.github.hakjuoh.protege_mcp.config.McpConfig;

/**
 * Preferences for the in-Protégé chat (Architecture Approach B): optional CLI path overrides (a
 * Finder/Dock-launched Protégé often has a minimal {@code PATH}, so {@code claude}/{@code codex} may
 * not auto-resolve) and a reset for the one-time egress consent. There is deliberately no API-key
 * field — each CLI uses the user's existing login.
 */
public class ChatPreferencesPanel extends PreferencesPanel {

    private static final long serialVersionUID = 1L;

    private JTextField claudePath;
    private JTextField codexPath;
    private JLabel claudeStatus;
    private JLabel codexStatus;

    @Override
    public void initialise() throws Exception {
        setLayout(new BorderLayout());
        Preferences p = McpConfig.prefs();

        PreferencesLayoutPanel panel = new PreferencesLayoutPanel();

        panel.addGroup("Coding-agent CLIs");
        panel.addHelpText("The chat drives a locally-installed coding-agent CLI, which connects back to "
                + "Protégé's MCP server to edit the live ontology. Install and log in to Claude Code "
                + "(claude) and/or Codex (codex). No API key is stored here — each CLI uses your own login.");

        claudePath = new JTextField(p.getString(McpConfig.KEY_CHAT_CLAUDE_PATH, ""), 30);
        panel.addLabelledGroupComponent("claude path (optional):", claudePath);
        claudeStatus = new JLabel();
        panel.addGroupComponent(claudeStatus);

        codexPath = new JTextField(p.getString(McpConfig.KEY_CHAT_CODEX_PATH, ""), 30);
        panel.addLabelledGroupComponent("codex path (optional):", codexPath);
        codexStatus = new JLabel();
        panel.addGroupComponent(codexStatus);

        panel.addHelpText("Leave blank to auto-detect on PATH and common install dirs. Set the full path "
                + "to the executable if Protégé (launched from Finder/Dock) cannot find it.");

        panel.addGroup("Privacy");
        panel.addHelpText("The chat sends your prompts and the ontology content the assistant reads to "
                + "your model provider via the CLI. Edits obey the MCP server's read-only / confirm-write "
                + "settings (Preferences ▸ MCP).");
        JButton resetConsent = new JButton("Reset egress consent prompt");
        resetConsent.addActionListener(e -> {
            McpConfig.prefs().putBoolean(McpConfig.KEY_CHAT_CONSENTED, false);
            resetConsent.setText("Egress consent will be asked again");
            resetConsent.setEnabled(false);
        });
        JPanel resetRow = new JPanel(new BorderLayout());
        resetRow.add(resetConsent, BorderLayout.WEST);
        panel.addGroupComponent(resetRow);

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
    }

    @Override
    public void dispose() throws Exception {
        // nothing to release
    }
}
