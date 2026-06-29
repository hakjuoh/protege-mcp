package io.github.hakjuoh.ui;

import javax.swing.JCheckBox;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

import org.protege.editor.core.prefs.Preferences;
import org.protege.editor.core.ui.preferences.PreferencesLayoutPanel;
import org.protege.editor.core.ui.preferences.PreferencesPanel;
import io.github.hakjuoh.config.McpConfig;

/**
 * Preferences tab for the MCP server: listen port (or ephemeral), auto-start, read-only mode and
 * write confirmation. Values are persisted via the Protégé {@link Preferences} store; the server
 * reads a fresh snapshot when it (re)starts, while read-only / confirm toggles apply live.
 */
public class McpPreferencesPanel extends PreferencesPanel {

    private static final long serialVersionUID = 1L;

    private JSpinner portSpinner;
    private JCheckBox ephemeralCheck;
    private JCheckBox autoStartCheck;
    private JCheckBox readOnlyCheck;
    private JCheckBox confirmWritesCheck;

    @Override
    public void initialise() throws Exception {
        Preferences p = McpConfig.prefs();
        int port = p.getInt(McpConfig.KEY_PORT, McpConfig.DEFAULT_PORT);
        boolean ephemeral = port == 0;

        portSpinner = new JSpinner(new SpinnerNumberModel(ephemeral ? McpConfig.DEFAULT_PORT : port,
                1, 65535, 1));
        portSpinner.setEditor(new JSpinner.NumberEditor(portSpinner, "#"));
        portSpinner.setEnabled(!ephemeral);

        ephemeralCheck = new JCheckBox("Use an ephemeral (auto-assigned) port", ephemeral);
        ephemeralCheck.addActionListener(e -> portSpinner.setEnabled(!ephemeralCheck.isSelected()));

        autoStartCheck = new JCheckBox("Start the server automatically when a window opens",
                p.getBoolean(McpConfig.KEY_AUTOSTART, true));
        readOnlyCheck = new JCheckBox("Read-only mode (block all write tools)",
                p.getBoolean(McpConfig.KEY_READ_ONLY, false));
        confirmWritesCheck = new JCheckBox("Confirm each write with a dialog",
                p.getBoolean(McpConfig.KEY_CONFIRM_WRITES, false));

        PreferencesLayoutPanel panel = new PreferencesLayoutPanel();
        panel.addGroup("Connection");
        panel.addLabelledGroupComponent("Port:", portSpinner);
        panel.addGroupComponent(ephemeralCheck);
        panel.addHelpText("The server binds to 127.0.0.1 only. Port changes take effect on restart.");
        panel.addSeparator();
        panel.addGroup("Startup");
        panel.addGroupComponent(autoStartCheck);
        panel.addSeparator();
        panel.addGroup("Safety");
        panel.addGroupComponent(readOnlyCheck);
        panel.addGroupComponent(confirmWritesCheck);
        panel.addHelpText("Read-only and confirmation apply immediately, without a restart.");
        add(panel);
    }

    @Override
    public void applyChanges() {
        Preferences p = McpConfig.prefs();
        int port = ephemeralCheck.isSelected() ? 0 : (Integer) portSpinner.getValue();
        p.putInt(McpConfig.KEY_PORT, port);
        p.putBoolean(McpConfig.KEY_AUTOSTART, autoStartCheck.isSelected());
        p.putBoolean(McpConfig.KEY_READ_ONLY, readOnlyCheck.isSelected());
        p.putBoolean(McpConfig.KEY_CONFIRM_WRITES, confirmWritesCheck.isSelected());
    }

    @Override
    public void dispose() throws Exception {
        // nothing to release
    }
}
