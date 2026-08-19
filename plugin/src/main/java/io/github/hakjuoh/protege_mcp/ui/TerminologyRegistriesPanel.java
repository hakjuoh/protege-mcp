package io.github.hakjuoh.protege_mcp.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridLayout;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;

import io.github.hakjuoh.protege_mcp.external.ProviderConfigurationStore;
import io.github.hakjuoh.protege_mcp.external.ProviderConnectionProbe;
import io.github.hakjuoh.protege_mcp.external.ProviderFailure;
import io.github.hakjuoh.protege_mcp.external.ProviderOwnerConfig;
import io.github.hakjuoh.protege_mcp.external.ProviderOwnerConfig.CredentialBinding;
import io.github.hakjuoh.protege_mcp.external.ProviderOwnerConfig.OriginBinding;

/** Owner origin and credential editor that preserves the complete one-to-many binding model. */
final class TerminologyRegistriesPanel extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final String TEST_CONNECTION_LABEL = "Test Connection";
    static final int BUSY_LABEL_INTERVAL_MS = 500;
    static final Duration DEFAULT_PROBE_TIMEOUT = Duration.ofSeconds(10);
    private final Map<String, OriginBinding> origins = new LinkedHashMap<>();
    private final Map<String, CredentialBinding> credentials = new LinkedHashMap<>();
    private final Map<String, byte[]> pendingSecrets = new LinkedHashMap<>();
    private final OriginTableModel originModel = new OriginTableModel();
    private final CredentialTableModel credentialModel = new CredentialTableModel();
    private final JTable originTable = table(originModel);
    private final JTable credentialTable = table(credentialModel);
    private final ConfigurationAccess configuration;
    private final ProbeAccess probe;
    private final long probeTimeoutMillis;
    private final MessageAccess messages;
    private final List<JButton> actionButtons = new ArrayList<>();
    private JButton testConnectionButton;
    private int busyLabelFrame;
    private final Timer busyLabelTimer = new Timer(BUSY_LABEL_INTERVAL_MS, ignored -> {
        busyLabelFrame = nextConnectionTestFrame(busyLabelFrame);
        if (testConnectionButton != null) {
            testConnectionButton.setText(connectionTestLabel(busyLabelFrame));
        }
    });
    private final ExecutorService probeExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread worker = new Thread(task, "protege-mcp-terminology-probe");
        worker.setDaemon(true);
        return worker;
    });
    private final AtomicBoolean probeRunning = new AtomicBoolean();
    private final AtomicBoolean disposed = new AtomicBoolean();
    private Timer probeTimeoutTimer;
    private ProbeAttempt activeProbe;
    private ProbeAttempt stoppingProbe;
    private long activeProbeId;

    TerminologyRegistriesPanel() throws ProviderFailure {
        this(defaultConfiguration(), defaultProbe(), DEFAULT_PROBE_TIMEOUT,
                JOptionPane::showMessageDialog);
    }

    TerminologyRegistriesPanel(ConfigurationAccess configuration, ProbeAccess probe)
            throws ProviderFailure {
        this(configuration, probe, DEFAULT_PROBE_TIMEOUT, JOptionPane::showMessageDialog);
    }

    TerminologyRegistriesPanel(ConfigurationAccess configuration, ProbeAccess probe,
            Duration probeTimeout, MessageAccess messages) throws ProviderFailure {
        super(new GridLayout(2, 1, 0, 8));
        this.configuration = java.util.Objects.requireNonNull(configuration, "configuration");
        this.probe = java.util.Objects.requireNonNull(probe, "probe");
        if (probeTimeout == null || probeTimeout.isZero() || probeTimeout.isNegative()
                || probeTimeout.toMillis() <= 0
                || probeTimeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("probe timeout must fit a Swing timer");
        }
        this.probeTimeoutMillis = probeTimeout.toMillis();
        this.messages = java.util.Objects.requireNonNull(messages, "messages");
        ProviderOwnerConfig initial = configuration.load();
        origins.putAll(initial.origins());
        credentials.putAll(initial.credentials());
        add(originSection());
        add(credentialSection());
    }

    private JPanel originSection() {
        JPanel panel = section("Terminology registry origins", originTable);
        JPanel buttons = buttonBar();
        buttons.add(button("Add origin...", this::addOrigin));
        buttons.add(button("Edit origin...", this::editOrigin));
        buttons.add(button("Remove origin", this::removeOrigin));
        testConnectionButton = button(TEST_CONNECTION_LABEL, this::testConnection);
        testConnectionButton.setText(connectionTestLabel(3));
        Dimension stableButtonSize = testConnectionButton.getPreferredSize();
        testConnectionButton.setText(TEST_CONNECTION_LABEL);
        testConnectionButton.setPreferredSize(stableButtonSize);
        testConnectionButton.setMinimumSize(stableButtonSize);
        buttons.add(testConnectionButton);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel credentialSection() {
        JPanel panel = section("Credential bindings", credentialTable);
        JPanel buttons = buttonBar();
        buttons.add(button("Add credential...", this::addCredential));
        buttons.add(button("Edit credential...", this::editCredential));
        buttons.add(button("Remove credential", this::removeCredential));
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private void addOrigin() {
        TerminologyRegistryDialog dialog = new TerminologyRegistryDialog(owner(),
                "Add terminology origin", null);
        dialog.setVisible(true);
        OriginBinding value = dialog.result();
        if (value == null) return;
        if (origins.containsKey(value.alias())) {
            error("An origin with that alias already exists.");
            return;
        }
        origins.put(value.alias(), value);
        refresh();
    }

    private void editOrigin() {
        OriginBinding current = selectedOrigin();
        if (current == null) return;
        TerminologyRegistryDialog dialog = new TerminologyRegistryDialog(owner(),
                "Edit terminology origin", current);
        dialog.setVisible(true);
        OriginBinding updated = dialog.result();
        if (updated == null) return;
        if (!updated.alias().equals(current.alias()) && origins.containsKey(updated.alias())) {
            error("An origin with that alias already exists.");
            return;
        }
        List<CredentialBinding> rebound = credentials.values().stream()
                .filter(value -> value.originAlias().equals(current.alias()))
                .map(value -> new CredentialBinding(value.id(), value.providerId(), updated.alias(),
                        value.scheme(), value.header(), value.parameter(),
                        value.projectFingerprint()))
                .toList();
        origins.remove(current.alias());
        origins.put(updated.alias(), updated);
        rebound.forEach(value -> credentials.put(value.id(), value));
        refresh();
    }

    private void removeOrigin() {
        OriginBinding current = selectedOrigin();
        if (current == null) return;
        origins.remove(current.alias());
        List<String> removedCredentials = credentials.values().stream()
                .filter(value -> value.originAlias().equals(current.alias()))
                .map(CredentialBinding::id).toList();
        removedCredentials.forEach(this::removeCredential);
        refresh();
    }

    private void addCredential() {
        if (origins.isEmpty()) {
            error("Add an origin before adding credentials.");
            return;
        }
        TerminologyCredentialDialog dialog = new TerminologyCredentialDialog(owner(),
                "Add credential binding", List.copyOf(origins.values()), null);
        dialog.setVisible(true);
        acceptCredential(dialog, null);
    }

    private void editCredential() {
        CredentialBinding current = selectedCredential(true);
        if (current == null) return;
        TerminologyCredentialDialog dialog = new TerminologyCredentialDialog(owner(),
                "Edit credential binding", List.copyOf(origins.values()), current);
        dialog.setVisible(true);
        acceptCredential(dialog, current);
    }

    private void acceptCredential(TerminologyCredentialDialog dialog, CredentialBinding previous) {
        CredentialBinding updated = dialog.result();
        if (updated == null) return;
        char[] chars = dialog.secret();
        try {
            if ((previous == null || !updated.id().equals(previous.id()))
                    && credentials.containsKey(updated.id())) {
                throw new ProviderFailure("provider_credential_invalid",
                        "A credential with that ID already exists", false);
            }
            if (requiresSecretReentry(previous, updated) && chars.length == 0) {
                throw new ProviderFailure("provider_credential_invalid",
                        "Changing a credential ID or authentication scheme requires entering its"
                                + " secret again", false);
            }
            byte[] encoded = encodeSecret(chars);
            if (previous != null && !previous.id().equals(updated.id())) {
                removeCredential(previous.id());
            }
            credentials.put(updated.id(), updated);
            if (encoded != null) replacePendingSecret(updated.id(), encoded);
            refresh();
        } catch (ProviderFailure failure) {
            error(failure.getMessage());
        } finally {
            Arrays.fill(chars, '\0');
        }
    }

    private void removeCredential() {
        CredentialBinding current = selectedCredential(true);
        if (current != null) removeCredential(current.id());
        refresh();
    }

    private void removeCredential(String id) {
        credentials.remove(id);
        byte[] pending = pendingSecrets.remove(id);
        if (pending != null) Arrays.fill(pending, (byte) 0);
    }

    private void testConnection() {
        if (stoppingProbe != null && !stoppingProbe.isFinished()) {
            messages.show(this,
                    "The previous connection test is still stopping. Wait a moment, then try "
                            + "again.",
                    "Connection probe", JOptionPane.WARNING_MESSAGE);
            return;
        }
        stoppingProbe = null;
        if (!probeRunning.compareAndSet(false, true)) return;
        OriginBinding origin = selectedOrigin();
        if (origin == null) {
            probeRunning.set(false);
            return;
        }
        CredentialBinding credential = selectedCredential(false);
        if (credential != null && !credential.originAlias().equals(origin.alias())) credential = null;
        if (credential == null) {
            List<CredentialBinding> candidates = credentials.values().stream()
                    .filter(value -> value.originAlias().equals(origin.alias())).toList();
            if (candidates.size() == 1) credential = candidates.get(0);
            if (candidates.size() > 1) {
                Object selected = JOptionPane.showInputDialog(this, "Credential:",
                        "Choose probe credential", JOptionPane.QUESTION_MESSAGE, null,
                        candidates.stream().map(CredentialBinding::id).toArray(),
                        candidates.get(0).id());
                if (selected == null) {
                    probeRunning.set(false);
                    return;
                }
                credential = credentials.get(String.valueOf(selected));
            }
        }
        CredentialBinding selectedCredential = credential;
        byte[] pending = selectedCredential == null ? null
                : pendingSecrets.get(selectedCredential.id());
        byte[] secret = pending == null ? null : pending.clone();
        setProbeBusy(true);
        long probeId = ++activeProbeId;
        ProbeAttempt attempt = new ProbeAttempt(probeId, origin, selectedCredential, secret);
        activeProbe = attempt;
        Future<?> future = probeExecutor.submit(() -> runProbe(attempt));
        attempt.attach(future);
        probeTimeoutTimer = new Timer(Math.toIntExact(probeTimeoutMillis),
                ignored -> timeOutProbe(probeId));
        probeTimeoutTimer.setRepeats(false);
        probeTimeoutTimer.start();
    }

    private void runProbe(ProbeAttempt attempt) {
        if (!attempt.begin()) return;
        String message;
        int type;
        try {
            ProviderConnectionProbe.ProbeResult result = probe.run(attempt.origin(),
                    attempt.credential(), attempt.secret());
            message = "Connection succeeded: " + result.sourceUrl();
            type = JOptionPane.INFORMATION_MESSAGE;
        } catch (ProviderFailure failure) {
            message = "Connection failed (" + failure.code() + ").";
            type = JOptionPane.ERROR_MESSAGE;
        } catch (RuntimeException failure) {
            message = "Connection failed (provider_probe_failed).";
            type = JOptionPane.ERROR_MESSAGE;
        } finally {
            attempt.finish();
            SwingUtilities.invokeLater(() -> workerStopped(attempt));
        }
        String finalMessage = message;
        int finalType = type;
        SwingUtilities.invokeLater(() -> finishProbe(attempt.id(), finalMessage, finalType));
    }

    private void timeOutProbe(long probeId) {
        if (probeId != activeProbeId || !probeRunning.get()) return;
        ProbeAttempt attempt = activeProbe;
        if (attempt != null && attempt.id() == probeId && attempt.cancel()) {
            stoppingProbe = attempt;
        }
        finishProbe(probeId, timeoutMessage(Duration.ofMillis(probeTimeoutMillis)),
                JOptionPane.WARNING_MESSAGE);
    }

    static String timeoutMessage(Duration timeout) {
        long millis = timeout.toMillis();
        String description;
        if (millis % 1_000 == 0) {
            description = (millis / 1_000) + " seconds";
        } else {
            description = millis + " milliseconds";
        }
        return "Connection timed out after " + description
                + ". Check the origin, authentication method, credential value, and network "
                + "access, then try again.";
    }

    private void finishProbe(long probeId, String message, int type) {
        if (probeId != activeProbeId || !probeRunning.compareAndSet(true, false)) return;
        activeProbeId++;
        activeProbe = null;
        if (probeTimeoutTimer != null) {
            probeTimeoutTimer.stop();
            probeTimeoutTimer = null;
        }
        setProbeBusy(false);
        if (!disposed.get()) messages.show(this, message, "Connection probe", type);
    }

    private void workerStopped(ProbeAttempt attempt) {
        if (stoppingProbe == attempt) stoppingProbe = null;
    }

    private void setProbeBusy(boolean busy) {
        actionButtons.forEach(button -> button.setEnabled(!busy && !disposed.get()));
        if (busy) {
            busyLabelFrame = 0;
            testConnectionButton.setText(connectionTestLabel(busyLabelFrame));
            busyLabelTimer.start();
        } else {
            busyLabelTimer.stop();
            testConnectionButton.setText(TEST_CONNECTION_LABEL);
        }
    }

    static String connectionTestLabel(int frame) {
        if (frame < 0 || frame > 3) throw new IllegalArgumentException("invalid busy label frame");
        return TEST_CONNECTION_LABEL + ".".repeat(frame);
    }

    static int nextConnectionTestFrame(int frame) {
        if (frame < 0 || frame > 3) throw new IllegalArgumentException("invalid busy label frame");
        return (frame + 1) % 4;
    }

    void applyChanges() throws ProviderFailure {
        ProviderOwnerConfig updated;
        try {
            updated = ProviderOwnerConfig.of(origins, credentials);
        } catch (IllegalArgumentException invalid) {
            throw new ProviderFailure("provider_configuration_invalid",
                    "Terminology registry configuration is invalid", false);
        }
        configuration.save(updated, pendingSecrets);
        clearPendingSecrets();
    }

    void disposePanel() {
        disposed.set(true);
        if (probeTimeoutTimer != null) probeTimeoutTimer.stop();
        busyLabelTimer.stop();
        ProbeAttempt task = activeProbe;
        if (task != null) task.cancel();
        ProbeAttempt stopping = stoppingProbe;
        if (stopping != null) stopping.cancel();
        probeExecutor.shutdownNow();
        clearPendingSecrets();
    }

    ProviderOwnerConfig editedConfig() {
        return ProviderOwnerConfig.of(origins, credentials);
    }

    private OriginBinding selectedOrigin() {
        int row = originTable.getSelectedRow();
        if (row < 0 || row >= origins.size()) {
            error("Select a terminology origin first.");
            return null;
        }
        return new ArrayList<>(origins.values()).get(row);
    }

    private CredentialBinding selectedCredential(boolean reportMissing) {
        int row = credentialTable.getSelectedRow();
        if (row < 0 || row >= credentials.size()) {
            if (reportMissing) error("Select a credential binding first.");
            return null;
        }
        return new ArrayList<>(credentials.values()).get(row);
    }

    private void replacePendingSecret(String id, byte[] value) {
        byte[] previous = pendingSecrets.put(id, value);
        if (previous != null) Arrays.fill(previous, (byte) 0);
    }

    private void clearPendingSecrets() {
        pendingSecrets.values().forEach(value -> Arrays.fill(value, (byte) 0));
        pendingSecrets.clear();
    }

    private void refresh() {
        originModel.fireTableDataChanged();
        credentialModel.fireTableDataChanged();
    }

    private Frame owner() {
        return (Frame) SwingUtilities.getAncestorOfClass(Frame.class, this);
    }

    private void error(String message) {
        JOptionPane.showMessageDialog(this, message, "Terminology registries",
                JOptionPane.ERROR_MESSAGE);
    }

    private static byte[] encodeSecret(char[] chars) throws ProviderFailure {
        if (chars == null || chars.length == 0) return null;
        byte[] result = new byte[chars.length];
        for (int index = 0; index < chars.length; index++) {
            if (chars[index] < 0x20 || chars[index] > 0x7e) {
                Arrays.fill(result, (byte) 0);
                throw new ProviderFailure("provider_credential_invalid",
                        "Credential secrets must contain printable ASCII characters", false);
            }
            result[index] = (byte) chars[index];
        }
        return result;
    }

    static boolean requiresSecretReentry(CredentialBinding previous,
            CredentialBinding updated) {
        return previous != null && updated != null
                && (!previous.id().equals(updated.id())
                        || previous.scheme() != updated.scheme());
    }

    private static JTable table(AbstractTableModel model) {
        JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setPreferredScrollableViewportSize(new Dimension(580, 95));
        return table;
    }

    private static JPanel section(String title, JTable table) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        return panel;
    }

    private static JPanel buttonBar() {
        return new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
    }

    private JButton button(String label, Runnable action) {
        JButton button = new JButton(label);
        button.addActionListener(ignored -> action.run());
        actionButtons.add(button);
        return button;
    }

    private static ConfigurationAccess defaultConfiguration() throws ProviderFailure {
        ProviderConfigurationStore store = new ProviderConfigurationStore();
        return new ConfigurationAccess() {
            @Override public ProviderOwnerConfig load() throws ProviderFailure { return store.load(); }
            @Override public void save(ProviderOwnerConfig config, Map<String, byte[]> secrets)
                    throws ProviderFailure { store.save(config, secrets); }
        };
    }

    private static ProbeAccess defaultProbe() {
        ProviderConnectionProbe probe = new ProviderConnectionProbe();
        return probe::probe;
    }

    @FunctionalInterface
    interface ProbeAccess {
        ProviderConnectionProbe.ProbeResult run(OriginBinding origin, CredentialBinding credential,
                byte[] pendingSecret) throws ProviderFailure;
    }

    @FunctionalInterface
    interface MessageAccess {
        void show(Component parent, String message, String title, int type);
    }

    interface ConfigurationAccess {
        ProviderOwnerConfig load() throws ProviderFailure;
        void save(ProviderOwnerConfig config, Map<String, byte[]> pendingSecrets)
                throws ProviderFailure;
    }

    /** Owns the temporary secret across queued, running, cancelled, and completed task states. */
    static final class ProbeAttempt {
        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int CANCELLED = 2;
        private static final int FINISHED = 3;

        private final long id;
        private final OriginBinding origin;
        private final CredentialBinding credential;
        private final byte[] secret;
        private final AtomicInteger state = new AtomicInteger(QUEUED);
        private volatile Future<?> future;

        ProbeAttempt(long id, OriginBinding origin, CredentialBinding credential, byte[] secret) {
            this.id = id;
            this.origin = origin;
            this.credential = credential;
            this.secret = secret;
        }

        long id() { return id; }
        OriginBinding origin() { return origin; }
        CredentialBinding credential() { return credential; }
        byte[] secret() { return secret; }

        void attach(Future<?> submitted) {
            future = submitted;
            if (state.get() == CANCELLED) submitted.cancel(true);
        }

        boolean begin() {
            return state.compareAndSet(QUEUED, RUNNING);
        }

        void finish() {
            wipeSecret();
            state.set(FINISHED);
        }

        /** @return true only while a worker still owns the secret and must finish cleanup. */
        boolean cancel() {
            boolean cancelledBeforeStart = state.compareAndSet(QUEUED, CANCELLED);
            Future<?> submitted = future;
            if (submitted != null) submitted.cancel(true);
            if (cancelledBeforeStart) wipeSecret();
            return state.get() == RUNNING;
        }

        boolean isFinished() {
            return state.get() != QUEUED && state.get() != RUNNING;
        }

        private void wipeSecret() {
            if (secret != null) Arrays.fill(secret, (byte) 0);
        }
    }

    private final class OriginTableModel extends AbstractTableModel {
        private final String[] columns = {"Alias", "Profile", "Origin URL", "Loopback test"};
        @Override public int getRowCount() { return origins.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Object getValueAt(int row, int column) {
            OriginBinding value = new ArrayList<>(origins.values()).get(row);
            return switch (column) {
                case 0 -> value.alias();
                case 1 -> value.profile();
                case 2 -> value.origin().toASCIIString();
                case 3 -> value.testOnlyLoopback() ? "Yes" : "No";
                default -> "";
            };
        }
    }

    private final class CredentialTableModel extends AbstractTableModel {
        private final String[] columns = {"ID", "Provider ID", "Origin", "Authentication", "Project scope"};
        @Override public int getRowCount() { return credentials.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Object getValueAt(int row, int column) {
            CredentialBinding value = new ArrayList<>(credentials.values()).get(row);
            return switch (column) {
                case 0 -> value.id();
                case 1 -> value.providerId();
                case 2 -> value.originAlias();
                case 3 -> switch (value.scheme()) {
                    case BEARER -> "Bearer";
                    case API_KEY -> "API key header";
                    case ONTOPORTAL_API_KEY -> "OntoPortal API key";
                    case QUERY_API_KEY -> "Query parameter: apikey";
                };
                case 4 -> value.projectFingerprint() == null ? "All projects" : value.projectFingerprint();
                default -> "";
            };
        }
    }
}
