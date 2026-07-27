package io.github.hakjuoh.protege_mcp.testing;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;

/**
 * The {@code java.util.prefs} store the test JVM runs on, installed through the
 * {@code java.util.prefs.PreferencesFactory} system property in the plugin's surefire configuration.
 *
 * <p>Protégé's {@code PreferencesManager} is a thin facade over {@code java.util.prefs}, and
 * {@code McpConfig.prefs()} names the very node a live Protégé reads and writes — so an unqualified
 * test run edits the user's settings, and on macOS leaves a plist behind for every preference set the
 * suite ever names. Keeping the store in memory also means a run starts from a known-empty state
 * instead of inheriting whatever the previous one persisted.
 *
 * <p>Built on {@link AbstractPreferences} on purpose: the key and value length limits the plugin's
 * stored values have to respect live there, and a hand-rolled map would quietly stop enforcing them.
 */
public final class InMemoryPreferencesFactory implements PreferencesFactory {

    private static final Node USER_ROOT = new Node(null, "");
    private static final Node SYSTEM_ROOT = new Node(null, "");

    @Override
    public Preferences userRoot() {
        return USER_ROOT;
    }

    @Override
    public Preferences systemRoot() {
        return SYSTEM_ROOT;
    }

    /** One node of the tree; {@link AbstractPreferences} holds the node lock around every call here. */
    static final class Node extends AbstractPreferences {

        private final Map<String, String> values = new TreeMap<>();
        private final Map<String, Node> children = new ConcurrentHashMap<>();

        Node(Node parent, String name) {
            super(parent, name);
            // Nothing outlives the JVM, so every node this store hands out was created by this run.
            newNode = true;
        }

        @Override
        protected void putSpi(String key, String value) {
            values.put(key, value);
        }

        @Override
        protected String getSpi(String key) {
            return values.get(key);
        }

        @Override
        protected void removeSpi(String key) {
            values.remove(key);
        }

        @Override
        protected void removeNodeSpi() {
            values.clear();
            children.clear();
            if (parent() instanceof Node removedFrom) {
                removedFrom.children.remove(name());
            }
        }

        @Override
        protected String[] keysSpi() {
            return values.keySet().toArray(new String[0]);
        }

        @Override
        protected String[] childrenNamesSpi() {
            return children.keySet().toArray(new String[0]);
        }

        @Override
        protected AbstractPreferences childSpi(String name) {
            return children.computeIfAbsent(name, child -> new Node(this, child));
        }

        @Override
        protected void syncSpi() {
        }

        @Override
        protected void flushSpi() {
        }
    }
}
