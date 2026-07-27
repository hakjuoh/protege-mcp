package io.github.hakjuoh.protege_mcp.testing;

import org.protege.editor.core.prefs.Preferences;
import org.protege.editor.core.prefs.PreferencesManager;

/**
 * The one preference node tests are allowed to write.
 *
 * <p>Protégé derives the on-disk name from the preference set <em>and</em> the group — on macOS the
 * first three node-path components become {@code protege_preferences.<set>.<group>.plist} — so a node
 * per test is a file per test whenever the in-memory factory is bypassed. Everything shares one set
 * and one group instead, and each acquisition clears it: tests run sequentially, so a cleared node is
 * as private as a per-test one.
 */
public final class TestPreferences {

    private static final String SET_ID = "protege-mcp-test";
    private static final String GROUP = "scratch";

    private TestPreferences() {
    }

    /** An empty preference node for the calling test. */
    public static Preferences cleared() {
        Preferences preferences =
                PreferencesManager.getInstance().getPreferencesForSet(SET_ID, GROUP);
        preferences.clear();
        return preferences;
    }
}
