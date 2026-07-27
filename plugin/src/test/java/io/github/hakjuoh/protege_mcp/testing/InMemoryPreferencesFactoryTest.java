package io.github.hakjuoh.protege_mcp.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.protege.editor.core.prefs.Preferences;

/**
 * Pins the redirection that keeps a test run out of the preference store a live Protégé owns. The
 * first test is the one that matters: if the surefire {@code java.util.prefs.PreferencesFactory}
 * property is ever dropped, the suite starts editing the user's settings, and that has to be a red
 * build rather than a surprise in someone's Preferences pane.
 */
class InMemoryPreferencesFactoryTest {

    @Test
    void theTestJvmNeverReachesTheRealPreferenceStore() {
        assertEquals(InMemoryPreferencesFactory.class.getName(),
                System.getProperty("java.util.prefs.PreferencesFactory"),
                "the surefire configuration must install the in-memory preferences factory");
        assertInstanceOf(InMemoryPreferencesFactory.Node.class,
                java.util.prefs.Preferences.userRoot(),
                "java.util.prefs must resolve to the in-memory store");
    }

    @Test
    void aClearedNodeKeepsNoKeysAndStaysUsable() {
        Preferences preferences = TestPreferences.cleared();
        preferences.putString("model", "kept");
        assertEquals("kept", preferences.getString("model", ""));

        Preferences reacquired = TestPreferences.cleared();
        assertEquals("", reacquired.getString("model", ""),
                "every acquisition must hand the next test an empty node");
        reacquired.putString("model", "again");
        assertEquals("again", reacquired.getString("model", ""),
                "a cleared node must stay writable");
    }

    @Test
    void storedListsAndRemovalsBehaveLikeTheProtegeBackedStore() {
        Preferences preferences = TestPreferences.cleared();
        preferences.putStringList("models", List.of("first", "second"));
        assertEquals(List.of("first", "second"), preferences.getStringList("models", List.of()));
        assertTrue(preferences.getStringList("absent", List.of()).isEmpty());

        preferences.putString("model", "gone");
        preferences.putString("model", null);
        assertEquals("", preferences.getString("model", ""),
                "a null value removes the key instead of blanking it");

        assertTrue(TestPreferences.cleared().getStringList("models", List.of()).isEmpty(),
                "clearing must take the stored lists with it");
    }

    @Test
    void theStoreKeepsTheLengthLimitsTheRealBackendEnforces() {
        Preferences preferences = TestPreferences.cleared();
        String overlong = "x".repeat(8193);
        assertThrows(IllegalArgumentException.class, () -> preferences.putString("models", overlong),
                "a value over 8 KiB must be refused exactly as the Protégé-backed store refuses it");
        preferences.putString("models", "x".repeat(8192));
        assertEquals(8192, preferences.getString("models", "").length());
    }
}
