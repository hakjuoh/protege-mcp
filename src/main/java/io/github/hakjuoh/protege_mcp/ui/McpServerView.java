package io.github.hakjuoh.protege_mcp.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;

import org.protege.editor.owl.ui.view.AbstractOWLViewComponent;
import io.github.hakjuoh.protege_mcp.oauth.OAuthStore;
import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.McpServerRegistry;

/**
 * Status view for the MCP server in this window: shows whether it is running, the bound URL/port,
 * the bearer token, and the read-only state; offers start/stop, token regeneration, and one-click
 * copy of the connection URL and a ready-to-paste {@code claude mcp add} command.
 *
 * <p>It also lists the OAuth clients currently registered with the running server — when each was
 * registered and last seen, and how many active tokens it holds — and lets the user revoke a
 * selected client (dropping all of its tokens immediately).
 */
public class McpServerView extends AbstractOWLViewComponent {

    private static final long serialVersionUID = 1L;

    private static final String[] CLIENT_COLUMNS =
            {"Client", "Client ID", "Registered", "Last seen", "Active tokens", "Expires"};
    private static final int COL_CLIENT_ID = 1;
    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private JLabel statusLabel;
    private JTextField urlField;
    private JTextField tokenField;
    private JCheckBox showToken;
    private JLabel readOnlyLabel;
    private JButton startButton;
    private JButton stopButton;

    private JLabel clientsLabel;
    private DefaultTableModel clientsModel;
    private JTable clientsTable;
    private JButton revokeButton;
    private JLabel staticUseLabel;

    private Timer refreshTimer;

    @Override
    protected void initialiseOWLView() throws Exception {
        setLayout(new BorderLayout(8, 8));

        JPanel form = new JPanel(new GridLayout(0, 1, 4, 4));
        form.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        statusLabel = new JLabel();
        readOnlyLabel = new JLabel();

        urlField = new JTextField();
        urlField.setEditable(false);

        tokenField = new JTextField();
        tokenField.setEditable(false);
        showToken = new JCheckBox("Show token", false);
        showToken.addActionListener(e -> refresh());

        form.add(statusLabel);
        form.add(labelled("Endpoint URL:", urlField));
        form.add(new JLabel("Auth: OAuth (a browser opens to authorize on first connect)."));
        form.add(labelled("Token (manual fallback):", tokenField));
        form.add(showToken);
        form.add(readOnlyLabel);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        startButton = new JButton("Start");
        stopButton = new JButton("Stop");
        JButton regenerate = new JButton("Regenerate token");
        JButton copyUrl = new JButton("Copy URL");
        JButton copyCmd = new JButton("Copy connect command");
        startButton.addActionListener(e -> startStop(true));
        stopButton.addActionListener(e -> startStop(false));
        regenerate.addActionListener(e -> {
            McpServerController c = controller();
            if (c != null) {
                c.regenerateToken();
                refresh();
            }
        });
        copyUrl.addActionListener(e -> copy(urlField.getText()));
        copyCmd.addActionListener(e -> copy(connectCommand()));
        buttons.add(startButton);
        buttons.add(stopButton);
        buttons.add(regenerate);
        buttons.add(copyUrl);
        buttons.add(copyCmd);

        JPanel top = new JPanel(new BorderLayout(8, 8));
        top.add(form, BorderLayout.NORTH);
        top.add(buttons, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        add(buildClientsPanel(), BorderLayout.CENTER);

        refreshTimer = new Timer(1500, e -> refresh());
        refreshTimer.start();
        refresh();
    }

    private JPanel buildClientsPanel() {
        clientsLabel = new JLabel("Connected clients:");

        clientsModel = new DefaultTableModel(CLIENT_COLUMNS, 0) {
            private static final long serialVersionUID = 1L;

            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        clientsTable = new JTable(clientsModel);
        clientsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        clientsTable.getSelectionModel().addListSelectionListener(e -> updateRevokeEnabled());

        revokeButton = new JButton("Revoke selected client");
        revokeButton.setEnabled(false);
        revokeButton.addActionListener(e -> revokeSelected());

        staticUseLabel = new JLabel();

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        bar.add(revokeButton);
        bar.add(staticUseLabel);

        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        panel.add(clientsLabel, BorderLayout.NORTH);
        panel.add(new JScrollPane(clientsTable), BorderLayout.CENTER);
        panel.add(bar, BorderLayout.SOUTH);
        return panel;
    }

    @Override
    protected void disposeOWLView() {
        if (refreshTimer != null) {
            refreshTimer.stop();
            refreshTimer = null;
        }
    }

    private McpServerController controller() {
        return McpServerRegistry.get(getOWLEditorKit());
    }

    private void refresh() {
        McpServerController c = controller();
        if (c == null) {
            statusLabel.setText("MCP server: not available in this window.");
            urlField.setText("");
            tokenField.setText("");
            readOnlyLabel.setText("");
            startButton.setEnabled(false);
            stopButton.setEnabled(false);
            clientsModel.setRowCount(0);
            clientsLabel.setText("Connected clients:");
            staticUseLabel.setText("");
            updateRevokeEnabled();
            return;
        }
        boolean running = c.isRunning();
        statusLabel.setText("MCP server: " + (running ? "RUNNING" : "stopped")
                + (c.getLastError() != null && !running ? "  (last error: " + c.getLastError() + ")" : ""));
        urlField.setText(running ? c.getEndpointUrl() : "");
        String token = c.getToken();
        tokenField.setText(showToken.isSelected() ? token : mask(token));
        readOnlyLabel.setText("Mode: " + (c.isReadOnly() ? "READ-ONLY" : "writable")
                + (c.isConfirmWrites() ? ", writes require confirmation" : ""));
        startButton.setEnabled(!running);
        stopButton.setEnabled(running);

        updateClientsTable(c);
    }

    private void updateClientsTable(McpServerController c) {
        List<OAuthStore.ClientInfo> clients = c.listClients();
        clientsLabel.setText("Connected clients (" + clients.size() + "):");

        String selectedId = selectedClientId();
        clientsModel.setRowCount(0);
        for (OAuthStore.ClientInfo ci : clients) {
            clientsModel.addRow(new Object[] {
                    ci.clientName,
                    ci.clientId,
                    fmtDateTime(ci.registeredAt),
                    ci.lastSeenAt == 0 ? "—" : fmtDateTime(ci.lastSeenAt),
                    String.valueOf(ci.activeAccessTokens),
                    ci.latestAccessExpiry == 0 ? "—" : fmtDateTime(ci.latestAccessExpiry),
            });
        }
        if (selectedId != null) {
            for (int row = 0; row < clientsModel.getRowCount(); row++) {
                if (selectedId.equals(clientsModel.getValueAt(row, COL_CLIENT_ID))) {
                    clientsTable.setRowSelectionInterval(row, row);
                    break;
                }
            }
        }

        long staticSeen = c.getStaticTokenLastSeen();
        staticUseLabel.setText("Static fallback token last used: "
                + (staticSeen == 0 ? "never" : fmtDateTime(staticSeen)));
        updateRevokeEnabled();
    }

    private void updateRevokeEnabled() {
        McpServerController c = controller();
        revokeButton.setEnabled(c != null && c.isRunning() && clientsTable.getSelectedRow() >= 0);
    }

    private void revokeSelected() {
        McpServerController c = controller();
        if (c == null) {
            return;
        }
        String clientId = selectedClientId();
        if (clientId == null) {
            return;
        }
        int row = clientsTable.getSelectedRow();
        String name = row >= 0 ? String.valueOf(clientsModel.getValueAt(row, 0)) : clientId;
        int choice = JOptionPane.showConfirmDialog(this,
                "Revoke client \"" + name + "\"?\nIts tokens stop working immediately; it must "
                        + "re-authorize through the browser to reconnect.",
                "Revoke MCP client", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice == JOptionPane.OK_OPTION) {
            c.revokeClient(clientId);
            refresh();
        }
    }

    private String selectedClientId() {
        int row = clientsTable.getSelectedRow();
        if (row < 0) {
            return null;
        }
        Object value = clientsModel.getValueAt(row, COL_CLIENT_ID);
        return value == null ? null : value.toString();
    }

    private void startStop(boolean start) {
        McpServerController c = controller();
        if (c == null) {
            return;
        }
        startButton.setEnabled(false);
        stopButton.setEnabled(false);
        Thread worker = new Thread(() -> {
            try {
                if (start) {
                    c.start();
                } else {
                    c.stop();
                }
            } catch (Exception ex) {
                // surfaced via getLastError() in refresh()
            } finally {
                SwingUtilities.invokeLater(this::refresh);
            }
        }, "protege-mcp-view-" + (start ? "start" : "stop"));
        worker.setDaemon(true);
        worker.start();
    }

    private String connectCommand() {
        McpServerController c = controller();
        if (c == null || !c.isRunning()) {
            return "";
        }
        // OAuth: no header needed — Claude opens a browser to authorize on first connect. The
        // static token below remains a manual fallback for clients without OAuth support.
        return "claude mcp add --transport http protege " + c.getEndpointUrl();
    }

    private static JPanel labelled(String label, JTextField field) {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        p.add(new JLabel(label), BorderLayout.WEST);
        p.add(field, BorderLayout.CENTER);
        return p;
    }

    private static String mask(String token) {
        if (token == null || token.length() <= 6) {
            return "••••••";
        }
        return token.substring(0, 3) + "••••••"
                + token.substring(token.length() - 3);
    }

    private static String fmtDateTime(long millis) {
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
                .toLocalDateTime().format(TS_FORMAT);
    }

    private static void copy(String text) {
        if (text != null && !text.isEmpty()) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        }
    }
}
