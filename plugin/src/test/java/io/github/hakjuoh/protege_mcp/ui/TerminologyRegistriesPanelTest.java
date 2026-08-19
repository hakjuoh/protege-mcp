package io.github.hakjuoh.protege_mcp.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.swing.JButton;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.external.ProviderConnectionProbe;
import io.github.hakjuoh.protege_mcp.external.ProviderFailure;
import io.github.hakjuoh.protege_mcp.external.ProviderOwnerConfig;

class TerminologyRegistriesPanelTest {

    @Test
    void loadingAndApplyingPreservesEveryCredentialBindingField() throws Exception {
        ProviderOwnerConfig.OriginBinding origin = ProviderOwnerConfig.bindOrigin(
                "ncbo", "ontoportal", URI.create("https://data.bioontology.org"));
        ProviderOwnerConfig.CredentialBinding first = credential("first", "project-a");
        ProviderOwnerConfig.CredentialBinding second = credential("second", "project-b");
        ProviderOwnerConfig initial = ProviderOwnerConfig.of(Map.of(origin.alias(), origin),
                Map.of(first.id(), first, second.id(), second));
        AtomicReference<ProviderOwnerConfig> saved = new AtomicReference<>();
        TerminologyRegistriesPanel.ConfigurationAccess access =
                new TerminologyRegistriesPanel.ConfigurationAccess() {
                    @Override public ProviderOwnerConfig load() { return initial; }
                    @Override public void save(ProviderOwnerConfig config,
                            Map<String, byte[]> pendingSecrets) {
                        assertEquals(0, pendingSecrets.size());
                        saved.set(config);
                    }
                };
        TerminologyRegistriesPanel panel = new TerminologyRegistriesPanel(access,
                (selectedOrigin, selectedCredential, secret) ->
                        new ProviderConnectionProbe.ProbeResult("https://example.org/probe", 0));

        panel.applyChanges();

        assertEquals(initial.origins(), saved.get().origins());
        assertEquals(initial.credentials(), saved.get().credentials());
        panel.disposePanel();
    }

    @Test
    void connectionProbeIsSingleFlightAndDisposalInterruptsTheWorker() throws Exception {
        ProviderOwnerConfig.OriginBinding origin = ProviderOwnerConfig.bindOrigin(
                "ncbo", "ontoportal", URI.create("https://data.bioontology.org"));
        ProviderOwnerConfig initial = ProviderOwnerConfig.of(Map.of(origin.alias(), origin), Map.of());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger messages = new AtomicInteger();
        TerminologyRegistriesPanel panel = new TerminologyRegistriesPanel(
                new TerminologyRegistriesPanel.ConfigurationAccess() {
                    @Override public ProviderOwnerConfig load() { return initial; }
                    @Override public void save(ProviderOwnerConfig config,
                            Map<String, byte[]> secrets) { }
                }, (selectedOrigin, selectedCredential, secret) -> {
                    calls.incrementAndGet();
                    started.countDown();
                    try {
                        new CountDownLatch(1).await();
                        throw new AssertionError("probe should be interrupted by disposal");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        stopped.countDown();
                        throw new ProviderFailure("provider_probe_interrupted",
                                "Probe interrupted", true);
                    }
                }, Duration.ofSeconds(1), (parent, text, title, type) ->
                        messages.incrementAndGet());
        JTable origins = find(panel, JTable.class);
        JButton test = findButtons(panel).stream()
                .filter(button -> button.getText().equals("Test Connection"))
                .findFirst().orElseThrow();
        AtomicReference<java.util.List<Integer>> tableHeights = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            panel.setSize(1_180, 400);
            layoutTree(panel);
            tableHeights.set(tableViewportHeights(panel));
            origins.setRowSelectionInterval(0, 0);
            test.doClick();
            test.doClick();
            layoutTree(panel);
            assertTrue(findButtons(panel).stream().noneMatch(JButton::isEnabled));
            assertEquals("Test Connection", test.getText());
            assertTrue(test.getIcon() == null);
            assertEquals(tableHeights.get(), tableViewportHeights(panel),
                    "loading state must not resize either table viewport");
        });

        org.junit.jupiter.api.Assertions.assertTrue(started.await(2, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
        panel.disposePanel();
        assertTrue(stopped.await(2, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(0, messages.get(), "dispose must suppress the stale worker result");
    }

    @Test
    void connectionProbeTimesOutAndRestoresEveryActionAfterTenSecondPolicy() throws Exception {
        assertEquals(Duration.ofSeconds(10), TerminologyRegistriesPanel.DEFAULT_PROBE_TIMEOUT);
        assertTrue(TerminologyRegistriesPanel.timeoutMessage(
                TerminologyRegistriesPanel.DEFAULT_PROBE_TIMEOUT).contains("after 10 seconds"));
        ProviderOwnerConfig.OriginBinding origin = ProviderOwnerConfig.bindOrigin(
                "ncbo", "ontoportal", URI.create("https://data.bioontology.org"));
        ProviderOwnerConfig initial = ProviderOwnerConfig.of(Map.of(origin.alias(), origin), Map.of());
        CountDownLatch messageShown = new CountDownLatch(1);
        AtomicReference<String> message = new AtomicReference<>();
        AtomicInteger messageCount = new AtomicInteger();
        TerminologyRegistriesPanel panel = new TerminologyRegistriesPanel(
                new TerminologyRegistriesPanel.ConfigurationAccess() {
                    @Override public ProviderOwnerConfig load() { return initial; }
                    @Override public void save(ProviderOwnerConfig config,
                            Map<String, byte[]> secrets) { }
                }, (selectedOrigin, selectedCredential, secret) -> {
                    try {
                        new CountDownLatch(1).await();
                        throw new AssertionError("probe should be interrupted by timeout");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new ProviderFailure("provider_probe_interrupted",
                                "Probe interrupted", true);
                    }
                }, Duration.ofMillis(60), (parent, text, title, type) -> {
                    message.set(text);
                    messageCount.incrementAndGet();
                    messageShown.countDown();
                });
        JTable origins = find(panel, JTable.class);
        JButton test = findButtons(panel).stream()
                .filter(button -> button.getText().equals("Test Connection"))
                .findFirst().orElseThrow();

        SwingUtilities.invokeAndWait(() -> {
            origins.setRowSelectionInterval(0, 0);
            test.doClick();
        });

        assertTrue(messageShown.await(2, TimeUnit.SECONDS));
        assertTrue(message.get().contains("Connection timed out after"));
        assertTrue(message.get().contains("Check the origin"));
        Thread.sleep(100);
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(findButtons(panel).stream().allMatch(JButton::isEnabled));
            assertEquals("Test Connection", test.getText());
            assertTrue(test.getIcon() == null);
        });
        assertEquals(1, messageCount.get(), "the interrupted worker callback must be stale");
        panel.disposePanel();
    }

    @Test
    void successAndFailureEachRestoreActionsAndPublishExactlyOneResult() throws Exception {
        assertCompletedProbeState((origin, credential, secret) ->
                        new ProviderConnectionProbe.ProbeResult("https://example.org/probe", 0),
                "Connection succeeded");
        assertCompletedProbeState((origin, credential, secret) -> {
            throw new ProviderFailure("provider_authorization_failed", "denied", false);
        }, "provider_authorization_failed");
    }

    @Test
    void interruptIgnoringTimedOutWorkerBlocksQueueingButAllowsRetryAfterItStops()
            throws Exception {
        ProviderOwnerConfig.OriginBinding origin = ProviderOwnerConfig.bindOrigin(
                "ncbo", "ontoportal", URI.create("https://data.bioontology.org"));
        ProviderOwnerConfig initial = ProviderOwnerConfig.of(Map.of(origin.alias(), origin), Map.of());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        TerminologyRegistriesPanel panel = new TerminologyRegistriesPanel(configuration(initial),
                (selectedOrigin, selectedCredential, secret) -> {
                    calls.incrementAndGet();
                    started.countDown();
                    while (release.getCount() > 0) {
                        try {
                            release.await();
                        } catch (InterruptedException ignored) {
                            // Deliberately model a network operation that does not stop immediately.
                        }
                    }
                    return new ProviderConnectionProbe.ProbeResult(
                            "https://example.org/probe", 0);
                }, Duration.ofMillis(60), (parent, text, title, type) -> messages.add(text));
        JTable origins = find(panel, JTable.class);
        JButton test = testButton(panel);
        SwingUtilities.invokeAndWait(() -> {
            origins.setRowSelectionInterval(0, 0);
            test.doClick();
        });

        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertTrue(messages.poll(2, TimeUnit.SECONDS).contains("timed out"));
        SwingUtilities.invokeAndWait(test::doClick);
        assertTrue(messages.poll(2, TimeUnit.SECONDS).contains("still stopping"));
        assertEquals(1, calls.get(), "a second probe must not queue behind the stuck worker");

        release.countDown();
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(test::doClick);
        assertTrue(messages.poll(2, TimeUnit.SECONDS).contains("Connection succeeded"));
        assertEquals(2, calls.get());
        panel.disposePanel();
    }

    @Test
    void cancellingAQueuedAttemptWipesItsOwnedSecretWithoutRunning() {
        byte[] secret = "owner-secret".getBytes(StandardCharsets.US_ASCII);
        TerminologyRegistriesPanel.ProbeAttempt attempt =
                new TerminologyRegistriesPanel.ProbeAttempt(1, null, null, secret);

        assertFalse(attempt.cancel());

        assertTrue(attempt.isFinished());
        assertTrue(java.util.Arrays.equals(new byte[secret.length], secret));
    }

    @Test
    void runningAttemptRetainsSecretUntilItsWorkerFinishesThenWipesIt() {
        byte[] secret = "owner-secret".getBytes(StandardCharsets.US_ASCII);
        TerminologyRegistriesPanel.ProbeAttempt attempt =
                new TerminologyRegistriesPanel.ProbeAttempt(1, null, null, secret);
        assertTrue(attempt.begin());

        assertTrue(attempt.cancel());
        assertFalse(java.util.Arrays.equals(new byte[secret.length], secret));
        attempt.finish();

        assertTrue(attempt.isFinished());
        assertTrue(java.util.Arrays.equals(new byte[secret.length], secret));
    }

    @Test
    void credentialIdentityOrSchemeChangesRequireAReplacementSecret() {
        ProviderOwnerConfig.CredentialBinding original = credential("first", "project-a");
        ProviderOwnerConfig.CredentialBinding same = credential("first", "project-a");
        ProviderOwnerConfig.CredentialBinding renamed = credential("renamed", "project-a");
        ProviderOwnerConfig.CredentialBinding bearer =
                new ProviderOwnerConfig.CredentialBinding("first", "disease-provider", "ncbo",
                        ProviderOwnerConfig.AuthScheme.BEARER,
                        "Authorization", "project-a");
        ProviderOwnerConfig.CredentialBinding query =
                new ProviderOwnerConfig.CredentialBinding("first", "disease-provider", "ncbo",
                        ProviderOwnerConfig.AuthScheme.QUERY_API_KEY,
                        null, "apikey", "project-a");

        assertFalse(TerminologyRegistriesPanel.requiresSecretReentry(original, same));
        assertTrue(TerminologyRegistriesPanel.requiresSecretReentry(original, renamed));
        assertTrue(TerminologyRegistriesPanel.requiresSecretReentry(original, bearer));
        assertTrue(TerminologyRegistriesPanel.requiresSecretReentry(bearer, query));
    }

    @Test
    void connectionBusyLabelCyclesFromZeroThroughThreeDots() {
        assertEquals(500, TerminologyRegistriesPanel.BUSY_LABEL_INTERVAL_MS);
        assertEquals("Test Connection", TerminologyRegistriesPanel.connectionTestLabel(0));
        assertEquals("Test Connection.", TerminologyRegistriesPanel.connectionTestLabel(1));
        assertEquals("Test Connection..", TerminologyRegistriesPanel.connectionTestLabel(2));
        assertEquals("Test Connection...", TerminologyRegistriesPanel.connectionTestLabel(3));
        assertEquals(1, TerminologyRegistriesPanel.nextConnectionTestFrame(0));
        assertEquals(2, TerminologyRegistriesPanel.nextConnectionTestFrame(1));
        assertEquals(3, TerminologyRegistriesPanel.nextConnectionTestFrame(2));
        assertEquals(0, TerminologyRegistriesPanel.nextConnectionTestFrame(3));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> TerminologyRegistriesPanel.connectionTestLabel(4));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> TerminologyRegistriesPanel.nextConnectionTestFrame(-1));
    }

    private static void assertCompletedProbeState(TerminologyRegistriesPanel.ProbeAccess probe,
            String expectedMessage) throws Exception {
        ProviderOwnerConfig.OriginBinding origin = ProviderOwnerConfig.bindOrigin(
                "ncbo", "ontoportal", URI.create("https://data.bioontology.org"));
        ProviderOwnerConfig initial = ProviderOwnerConfig.of(Map.of(origin.alias(), origin), Map.of());
        LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        AtomicInteger messageCount = new AtomicInteger();
        TerminologyRegistriesPanel panel = new TerminologyRegistriesPanel(configuration(initial),
                probe, Duration.ofSeconds(1), (parent, text, title, type) -> {
                    messageCount.incrementAndGet();
                    messages.add(text);
                });
        JTable origins = find(panel, JTable.class);
        JButton test = testButton(panel);
        SwingUtilities.invokeAndWait(() -> {
            origins.setRowSelectionInterval(0, 0);
            test.doClick();
        });

        assertTrue(messages.poll(2, TimeUnit.SECONDS).contains(expectedMessage));
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(7, findButtons(panel).size());
            assertTrue(findButtons(panel).stream().allMatch(JButton::isEnabled));
            assertEquals("Test Connection", test.getText());
            assertTrue(test.getIcon() == null);
        });
        Thread.sleep(50);
        assertEquals(1, messageCount.get());
        panel.disposePanel();
    }

    private static TerminologyRegistriesPanel.ConfigurationAccess configuration(
            ProviderOwnerConfig initial) {
        return new TerminologyRegistriesPanel.ConfigurationAccess() {
            @Override public ProviderOwnerConfig load() { return initial; }
            @Override public void save(ProviderOwnerConfig config, Map<String, byte[]> secrets) { }
        };
    }

    private static JButton testButton(TerminologyRegistriesPanel panel) {
        return findButtons(panel).stream()
                .filter(button -> button.getText().equals("Test Connection"))
                .findFirst().orElseThrow();
    }

    private static java.util.List<JButton> findButtons(java.awt.Component root) {
        java.util.List<JButton> result = new java.util.ArrayList<>();
        if (root instanceof JButton button) result.add(button);
        if (root instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                result.addAll(findButtons(child));
            }
        }
        return result;
    }

    private static <T> T find(java.awt.Component root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                T found = find(child, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static java.util.List<Integer> tableViewportHeights(java.awt.Component root) {
        java.util.List<Integer> heights = new java.util.ArrayList<>();
        if (root instanceof JScrollPane scroll
                && scroll.getViewport().getView() instanceof JTable) {
            heights.add(scroll.getViewport().getHeight());
        }
        if (root instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                heights.addAll(tableViewportHeights(child));
            }
        }
        return heights;
    }

    private static void layoutTree(java.awt.Container container) {
        container.doLayout();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof java.awt.Container nested) layoutTree(nested);
        }
    }

    private static ProviderOwnerConfig.CredentialBinding credential(String id, String project) {
        return new ProviderOwnerConfig.CredentialBinding(id, "disease-provider", "ncbo",
                ProviderOwnerConfig.AuthScheme.ONTOPORTAL_API_KEY, "Authorization", project);
    }
}
