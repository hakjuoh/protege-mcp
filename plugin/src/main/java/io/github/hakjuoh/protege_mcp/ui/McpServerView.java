package io.github.hakjuoh.protege_mcp.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
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
 * selected client (dropping all of its tokens immediately). Dead entries disappear on their own:
 * the store supersedes a reconnected client's old registration and sweeps abandoned or long-idle
 * ones (see {@link OAuthStore#sweepInactiveClients()}), so revoking by hand is for kicking out a
 * live client, not list hygiene.
 */
public class McpServerView extends AbstractOWLViewComponent {

    private static final long serialVersionUID = 1L;

    private static final String[] CLIENT_COLUMNS =
            {"Client", "Client ID", "Capabilities", "Registered", "Last seen", "Active tokens", "Expires"};
    private static final int COL_CLIENT_ID = 1;

    private JLabel statusLabel;
    private JLabel connectionLabel;
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
    /** EDT-confined: identifies the management authority that produced the currently shown rows. */
    private boolean clientsFromBroker;

    private Timer refreshTimer;

    @Override
    protected void initialiseOWLView() throws Exception {
        setLayout(new BorderLayout(8, 8));

        JPanel form = new JPanel(new GridLayout(0, 1, 4, 4));
        form.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        statusLabel = new JLabel();
        connectionLabel = new JLabel();
        readOnlyLabel = new JLabel();

        urlField = new JTextField();
        urlField.setEditable(false);

        tokenField = new JTextField();
        tokenField.setEditable(false);
        showToken = new JCheckBox("Show token", false);
        showToken.addActionListener(e -> refresh());

        form.add(statusLabel);
        form.add(connectionLabel);
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
                // The broker validates the static token from the last heartbeat's snapshot — push
                // one now so the regenerated token applies there immediately too.
                io.github.hakjuoh.protege_mcp.broker.BrokerLink.get().pokeHeartbeat();
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
            statusLabel.setToolTipText(null);
            connectionLabel.setText(" ");
            connectionLabel.setToolTipText(null);
            urlField.setText("");
            tokenField.setText("");
            readOnlyLabel.setText("");
            startButton.setEnabled(false);
            stopButton.setEnabled(false);
            clientsModel.setRowCount(0);
            clientsFromBroker = false;
            clientsLabel.setText("Connected clients:");
            staticUseLabel.setText("");
            updateRevokeEnabled();
            return;
        }
        boolean running = c.isRunning();
        String brokerUrl = io.github.hakjuoh.protege_mcp.broker.BrokerLink.get().brokerMcpUrl();
        boolean brokerDown = ServerViewText.brokerDown(running, c.isBrokerManaged(), brokerUrl);
        // Two lines instead of one crammed label (which truncated away the broker state): run state
        // first, connection mode second — each with a tooltip carrying its full text, so nothing is
        // lost when the view is narrow.
        String status = ServerViewText.statusLine(running, c.getLastError());
        statusLabel.setText(status);
        statusLabel.setToolTipText(status);
        String connection = ServerViewText.connectionLine(running, c.isBrokerManaged(), brokerUrl,
                io.github.hakjuoh.protege_mcp.config.McpConfig.load().isSharedBroker(),
                c.getEndpointUrl(), c.isPortFallback(), c.getConfiguredPort());
        connectionLabel.setText(connection.isEmpty() ? " " : connection);
        connectionLabel.setToolTipText(connection.isEmpty() ? null : connection);
        // Reuse the frame's brokerUrl snapshot: a second read could see the heartbeat flip the
        // broker state mid-refresh and render a URL contradicting the status line above it.
        urlField.setText(running ? clientFacingUrl(c, brokerUrl) : "");
        String token = c.getToken();
        tokenField.setText(showToken.isSelected() ? token : mask(token));
        readOnlyLabel.setText("Mode: " + (c.isReadOnly() ? "READ-ONLY" : "writable")
                + (c.isConfirmWrites() ? ", writes require confirmation" : ""));
        // While the shared broker is down, this window's server keeps serving directly, but the
        // client-facing endpoint is gone — so the actionable button is Start (relaunch the broker),
        // not Stop.
        startButton.setEnabled(!running || brokerDown);
        startButton.setToolTipText(brokerDown ? "Relaunch the shared broker" : null);
        stopButton.setEnabled(running && !brokerDown);

        if (running && c.isBrokerManaged() && !brokerDown) {
            updateBrokerClientsTable();
            staticUseLabel.setText("");
        } else {
            // Includes the broker-down shape: the view advertises this window's direct URL then,
            // so a client that connects (and OAuth-registers) during the outage lands in THIS
            // window's in-memory store — it must be visible and revocable here, not hidden behind
            // a "managed by the shared broker" claim about a dead process.
            updateClientsTable(c);
        }
    }

    private void updateClientsTable(McpServerController c) {
        clientsFromBroker = false;
        List<OAuthStore.ClientInfo> clients = c.listClients();
        clientsLabel.setText("Connected clients (" + clients.size() + "):");
        clientsLabel.setToolTipText(null);

        String selectedId = selectedClientId();
        clientsModel.setRowCount(0);
        for (OAuthStore.ClientInfo ci : clients) {
            clientsModel.addRow(new Object[] {
                    ci.clientName,
                    ci.clientId,
                    String.join(", ", ci.capabilities.stream().sorted().toList()),
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

    private void updateBrokerClientsTable() {
        clientsFromBroker = true;
        io.github.hakjuoh.protege_mcp.broker.BrokerLink link =
                io.github.hakjuoh.protege_mcp.broker.BrokerLink.get();
        List<io.github.hakjuoh.protege_mcp.broker.BrokerClient.ClientInfo> clients =
                link.brokerClients();
        int pending = link.pendingBrokerRevocations();
        clientsLabel.setText(link.isBrokerClientManagementAvailable()
                ? "Connected clients via shared broker (" + clients.size() + ")"
                        + (pending == 0 ? ":" : "; " + pending + " revocation(s) need retry:")
                : "Connected clients: shared-broker management is temporarily unavailable");
        clientsLabel.setToolTipText("OAuth client state is managed by the shared broker.");

        String selectedId = selectedClientId();
        clientsModel.setRowCount(0);
        for (io.github.hakjuoh.protege_mcp.broker.BrokerClient.ClientInfo ci : clients) {
            clientsModel.addRow(new Object[] {
                    ci.clientName,
                    ci.clientId,
                    String.join(", ", ci.capabilities.stream().sorted().toList()),
                    fmtDateTime(ci.registeredAt),
                    ci.lastSeenAt == 0 ? "—" : fmtDateTime(ci.lastSeenAt),
                    String.valueOf(ci.activeAccessTokens),
                    ci.latestAccessExpiry == 0 ? "—" : fmtDateTime(ci.latestAccessExpiry),
            });
        }
        restoreSelection(selectedId);
        updateRevokeEnabled();
    }

    private void restoreSelection(String selectedId) {
        if (selectedId == null) {
            return;
        }
        for (int row = 0; row < clientsModel.getRowCount(); row++) {
            if (selectedId.equals(clientsModel.getValueAt(row, COL_CLIENT_ID))) {
                clientsTable.setRowSelectionInterval(row, row);
                return;
            }
        }
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
            revokeButton.setEnabled(false);
            boolean brokerManaged = clientsFromBroker;
            Thread worker = new Thread(() -> {
                try {
                    boolean revoked;
                    if (brokerManaged) {
                        revoked = io.github.hakjuoh.protege_mcp.broker.BrokerLink.get()
                                .revokeBrokerClient(clientId);
                    } else {
                        revoked = c.revokeClient(clientId);
                    }
                    if (!revoked) {
                        throw new java.io.IOException("the client is no longer registered");
                    }
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                            "Could not revoke the client: " + ex.getMessage(),
                            "Revoke MCP client", JOptionPane.ERROR_MESSAGE));
                } finally {
                    SwingUtilities.invokeLater(this::refresh);
                }
            }, "protege-mcp-revoke-client");
            worker.setDaemon(true);
            worker.start();
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
                    // An explicit Start withdraws the user's Stop before anything else — even if
                    // this start attempt then fails, the auto-starts are allowed to try again.
                    c.setUserStopped(false);
                    if (c.isRunning() && c.isBrokerManaged()) {
                        // Start is only enabled while running when the broker is down: relaunch
                        // it now, bypassing the heartbeat's re-spawn throttle. (If several
                        // instances press Start at once, the first bind wins and the others'
                        // heartbeats reconnect to the winner.)
                        io.github.hakjuoh.protege_mcp.broker.BrokerLink.get().reconnectNow();
                    } else {
                        // Same broker-first policy as auto-start and the chat's lazy start.
                        io.github.hakjuoh.protege_mcp.broker.McpBoot.ensureStarted(c);
                    }
                } else {
                    // Latch BEFORE stopping so a chat turn racing this Stop cannot lazily restart
                    // the server the user is in the middle of taking down.
                    c.setUserStopped(true);
                    io.github.hakjuoh.protege_mcp.broker.BrokerLink.get().detach(c);
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
        return ServerViewText.connectCommand(clientFacingUrl(c,
                io.github.hakjuoh.protege_mcp.broker.BrokerLink.get().brokerMcpUrl()));
    }

    /** What external clients should connect to: the shared broker when attached, else this window. */
    private String clientFacingUrl(McpServerController c, String brokerUrl) {
        if (c.isBrokerManaged() && brokerUrl != null) {
            return brokerUrl;
        }
        return c.getEndpointUrl();
    }

    private static JPanel labelled(String label, JTextField field) {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        p.add(new JLabel(label), BorderLayout.WEST);
        p.add(field, BorderLayout.CENTER);
        return p;
    }

    private static String mask(String token) {
        return ServerViewText.mask(token);
    }

    private static String fmtDateTime(long millis) {
        return ServerViewText.fmtDateTime(millis);
    }

    private static void copy(String text) {
        if (text != null && !text.isEmpty()) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        }
    }
}
