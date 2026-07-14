package io.github.hakjuoh.protege_mcp.ui;

import java.awt.Color;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;

import org.protege.editor.core.prefs.Preferences;
import org.protege.editor.core.ui.preferences.PreferencesLayoutPanel;
import org.protege.editor.core.ui.preferences.PreferencesPanel;
import io.github.hakjuoh.protege_mcp.config.McpConfig;
import io.github.hakjuoh.protege_mcp.server.EmbeddedHttpServer;

/**
 * Preferences tab for the MCP server: listen port (or ephemeral), bind address, shared broker and
 * its idle linger, auto-start, read-only mode and write confirmation. Values are persisted via the
 * Protégé {@link Preferences} store; the server reads a fresh snapshot when it (re)starts, while
 * read-only / confirm toggles apply live and the linger reaches a running broker per heartbeat.
 */
public class McpPreferencesPanel extends PreferencesPanel {

    private static final long serialVersionUID = 1L;

    private JSpinner portSpinner;
    private JCheckBox ephemeralCheck;
    private JComboBox<String> bindCombo;
    private JLabel bindWarning;
    private JCheckBox sharedBrokerCheck;
    private JSpinner lingerSpinner;
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

        bindCombo = new JComboBox<>(new String[] {
                McpConfig.DEFAULT_BIND_ADDRESS, "::1", "0.0.0.0"});
        bindCombo.setEditable(true);
        bindCombo.setSelectedItem(McpConfig.sanitizeBindAddress(
                p.getString(McpConfig.KEY_BIND_ADDRESS, McpConfig.DEFAULT_BIND_ADDRESS)));
        bindCombo.setPrototypeDisplayValue("255.255.255.255.255");

        bindWarning = new JLabel(PreferencesText.wrapped(
                "Warning: a non-loopback bind address exposes the MCP endpoint (and the shared "
                + "broker) to the network over plain, unencrypted HTTP — the static bearer token "
                + "transits in clear text, and anyone who captures it gets full MCP access (OAuth "
                + "authorization itself stays same-machine only). Prefer keeping 127.0.0.1 and "
                + "tunnelling instead (ssh -L 8123:127.0.0.1:8123 <this-machine>); bind other "
                + "addresses only on networks you trust."));
        bindWarning.setForeground(new Color(0xB0, 0x20, 0x20));
        bindCombo.addActionListener(e -> updateBindWarning());
        ((JTextComponent) bindCombo.getEditor().getEditorComponent()).getDocument()
                .addDocumentListener(new DocumentListener() {
                    @Override
                    public void insertUpdate(DocumentEvent e) {
                        updateBindWarning();
                    }

                    @Override
                    public void removeUpdate(DocumentEvent e) {
                        updateBindWarning();
                    }

                    @Override
                    public void changedUpdate(DocumentEvent e) {
                        updateBindWarning();
                    }
                });
        updateBindWarning();

        sharedBrokerCheck = new JCheckBox("Share one MCP endpoint across all Protégé windows and "
                + "instances (broker process)", p.getBoolean(McpConfig.KEY_SHARED_BROKER, true));

        lingerSpinner = new JSpinner(new SpinnerNumberModel(
                McpConfig.clampBrokerLingerSeconds(p.getInt(McpConfig.KEY_BROKER_LINGER_SECONDS,
                        McpConfig.DEFAULT_BROKER_LINGER_SECONDS)),
                0, McpConfig.MAX_BROKER_LINGER_SECONDS, 1));
        lingerSpinner.setEditor(new JSpinner.NumberEditor(lingerSpinner, "#"));
        lingerSpinner.setEnabled(sharedBrokerCheck.isSelected());
        sharedBrokerCheck.addActionListener(e ->
                lingerSpinner.setEnabled(sharedBrokerCheck.isSelected()));

        autoStartCheck = new JCheckBox("Start the server automatically when a window opens",
                p.getBoolean(McpConfig.KEY_AUTOSTART, true));
        readOnlyCheck = new JCheckBox("Read-only mode (block all write tools)",
                p.getBoolean(McpConfig.KEY_READ_ONLY, false));
        confirmWritesCheck = new JCheckBox("Confirm each write with a dialog",
                p.getBoolean(McpConfig.KEY_CONFIRM_WRITES, false));

        PreferencesLayoutPanel panel = new PreferencesLayoutPanel();
        panel.addGroup("Connection");
        panel.addGroupComponent(PreferencesRows.labelled("Port:", portSpinner));
        panel.addGroupComponent(ephemeralCheck);
        panel.addGroupComponent(PreferencesRows.labelled("Bind address:", bindCombo));
        panel.addGroupComponent(bindWarning);
        panel.addGroupComponent(sharedBrokerCheck);
        panel.addGroupComponent(PreferencesRows.labelled("Broker idle linger (seconds):", lingerSpinner));
        panel.addHelpText(PreferencesText.wrapped(
                "The server binds the address above — 127.0.0.1 (this machine only) by default; "
                + "IPv6-preferring clients connect fine over IPv4 loopback, so ::1 is only for a "
                + "client hard-wired to the IPv6 loopback. 0.0.0.0 serves every interface, but "
                + "handed-out URLs still say 127.0.0.1 — on another machine, replace that host "
                + "with this machine's address, and authenticate with the static token (OAuth "
                + "authorization is same-machine only). With the shared broker on, the port "
                + "belongs to a small broker process that outlives any single window and routes "
                + "MCP clients to the right window; each window's own server uses an ephemeral "
                + "loopback port behind it. Port, bind-address and broker changes apply the next "
                + "time a server (or the broker) starts — for a clean switch, close all Protégé "
                + "windows and reopen."));
        panel.addHelpText(PreferencesText.wrapped(
                "Idle linger: once the last Protégé instance disconnects (last window closed, or "
                + "the application quits), the broker keeps running this many seconds before "
                + "exiting, so a quick restart — or a second instance arriving moments later — "
                + "reuses the live broker and its port instead of paying a respawn. Unlike the "
                + "settings above, this reaches a running broker within a heartbeat (a few "
                + "seconds) while this window's server is attached to it; with the server "
                + "stopped, it applies when the broker next starts. 0 makes the broker exit "
                + "immediately when the last "
                + "instance disconnects: every quit-and-relaunch then spawns a fresh broker, MCP "
                + "clients briefly get connection errors during that gap, and a relaunch racing "
                + "the dying broker's lock handover can delay startup by a few seconds. The "
                + "default of 15 seconds bridges a normal restart."));
        panel.addSeparator();
        panel.addGroup("Startup");
        panel.addGroupComponent(autoStartCheck);
        panel.addSeparator();
        panel.addGroup("Safety");
        panel.addGroupComponent(readOnlyCheck);
        panel.addGroupComponent(confirmWritesCheck);
        panel.addHelpText(PreferencesText.wrapped(
                "Read-only and confirmation apply immediately, without a restart."));
        add(panel);
    }

    /** Show the exposure warning exactly while the (possibly still uncommitted) text is non-loopback. */
    private void updateBindWarning() {
        bindWarning.setVisible(!EmbeddedHttpServer.isLoopback(
                McpConfig.sanitizeBindAddress(editedBindAddress())));
    }

    /** The bind editor's current text — getSelectedItem() lags behind an uncommitted edit. */
    private String editedBindAddress() {
        return String.valueOf(bindCombo.getEditor().getItem());
    }

    @Override
    public void applyChanges() {
        Preferences p = McpConfig.prefs();
        int port = ephemeralCheck.isSelected() ? 0 : (Integer) portSpinner.getValue();
        p.putInt(McpConfig.KEY_PORT, port);
        p.putString(McpConfig.KEY_BIND_ADDRESS, McpConfig.sanitizeBindAddress(editedBindAddress()));
        p.putBoolean(McpConfig.KEY_SHARED_BROKER, sharedBrokerCheck.isSelected());
        p.putInt(McpConfig.KEY_BROKER_LINGER_SECONDS,
                McpConfig.clampBrokerLingerSeconds((Integer) lingerSpinner.getValue()));
        p.putBoolean(McpConfig.KEY_AUTOSTART, autoStartCheck.isSelected());
        p.putBoolean(McpConfig.KEY_READ_ONLY, readOnlyCheck.isSelected());
        p.putBoolean(McpConfig.KEY_CONFIRM_WRITES, confirmWritesCheck.isSelected());
    }

    @Override
    public void dispose() throws Exception {
        // nothing to release
    }
}
