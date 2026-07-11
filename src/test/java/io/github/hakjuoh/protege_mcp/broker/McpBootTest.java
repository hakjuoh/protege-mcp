package io.github.hakjuoh.protege_mcp.broker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.protege.editor.core.prefs.Preferences;

import io.github.hakjuoh.protege_mcp.config.McpConfig;
import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.OntologyAccess;

/**
 * Headless tests for the user-stop latch in {@link McpBoot#ensureStarted}. The latch check comes
 * before the broker/standalone start ladder, so a latched controller can be exercised without a
 * broker, a bindable port, or a live Protégé — the call must refuse up front. The non-latched happy
 * paths (broker attach, standalone start, ephemeral fallback) boot real servers and are covered by
 * the broker/server integration tests instead.
 */
class McpBootTest {

    private Preferences prefs;
    private String savedToken;

    @BeforeEach
    void snapshotPrefs() {
        // The controller's constructor runs McpConfig.load(), which mints and persists a bearer
        // token when none is stored — snapshot it so the test leaves the store as it found it.
        prefs = McpConfig.prefs();
        savedToken = prefs.getString(McpConfig.KEY_TOKEN, "");
    }

    @AfterEach
    void restorePrefs() {
        prefs.putString(McpConfig.KEY_TOKEN, savedToken);
    }

    private McpServerController newController() {
        // Never started in this test, so a null-backed OntologyAccess is safe (same seam as
        // McpServerControllerTest).
        return new McpServerController(new OntologyAccess(null));
    }

    @Test
    void ensureStartedRefusesAUserStoppedServer() {
        McpServerController c = newController();
        c.setUserStopped(true);

        assertThrows(IllegalStateException.class, () -> McpBoot.ensureStarted(c),
                "the chat's lazy start must not override the user's explicit Stop");
        assertFalse(c.isRunning(), "the refused controller must be left stopped");
        assertTrue(c.isUserStopped(), "the latch must survive the refusal");
    }

    @Test
    void ensureStartedRefusalNamesTheWayBack() {
        McpServerController c = newController();
        c.setUserStopped(true);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> McpBoot.ensureStarted(c));
        // The message surfaces verbatim in the chat transcript ("Could not start <provider>: …"),
        // so it must say both what happened (Stop) and how to undo it (Start).
        assertTrue(thrown.getMessage().contains("Stop"),
                "message must name the user's Stop as the cause: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("Start"),
                "message must point at the Start button as the way back: " + thrown.getMessage());
    }

    @Test
    void ensureStartedRefusalIsRepeatable() {
        // Every chat turn re-runs ensureStarted; the refusal must be stable, not one-shot.
        McpServerController c = newController();
        c.setUserStopped(true);

        assertThrows(IllegalStateException.class, () -> McpBoot.ensureStarted(c));
        assertThrows(IllegalStateException.class, () -> McpBoot.ensureStarted(c),
                "a second turn against a still-latched server must be refused the same way");
        assertFalse(c.isRunning());
    }

    @Test
    void ensureStartedReturnsSilentlyWhenRunningEvenIfLatched() throws Exception {
        // Running-but-latched is a real transient shape: the view's Stop latches BEFORE it detaches
        // and stops (so a racing chat turn cannot lazily restart the server), which means a chat
        // turn arriving in that window must take the isRunning() short-circuit — the server is
        // still up and can serve the turn — not the latch refusal. Pin the precedence by flipping
        // the controller's private volatile running field (the suite's reflective-state idiom;
        // actually booting a server here would defeat the headless design of this file).
        McpServerController c = newController();
        c.setUserStopped(true);
        setRunning(c, true);
        try {
            assertDoesNotThrow(() -> McpBoot.ensureStarted(c),
                    "isRunning() must short-circuit before the latch check");
            assertTrue(c.isUserStopped(), "the silent return must not consume the latch");
        } finally {
            setRunning(c, false); // never leave a fake-running controller behind
        }
    }

    private static void setRunning(McpServerController c, boolean value) throws Exception {
        Field f = McpServerController.class.getDeclaredField("running");
        f.setAccessible(true);
        f.setBoolean(c, value);
    }
}
