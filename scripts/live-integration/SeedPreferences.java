import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/** Seeds an isolated Protégé user home for the live integration harness. */
public final class SeedPreferences {

    private static final String ROOT = "PROTEGE_PREFERENCES";

    private SeedPreferences() {
    }

    public static void main(String[] args) throws BackingStoreException {
        if (!"Linux".equals(System.getProperty("os.name"))) {
            throw new IllegalStateException("The live integration preference sandbox is Linux-only; "
                    + "other java.util.prefs backends may ignore the isolated user.home");
        }
        if (Runtime.version().feature() < 17) {
            throw new IllegalStateException("The live integration harness requires Java 17 or newer");
        }
        if (args.length != 1 || args[0].isBlank()) {
            throw new IllegalArgumentException("Usage: SeedPreferences.java <bearer-token>");
        }

        Preferences server = Preferences.userRoot().node(ROOT)
                .node("io.github.hakjuoh.protege_mcp").node("server");
        server.putInt("port", 0);
        server.put("bindAddress", "127.0.0.1");
        server.putBoolean("autoStart", true);
        server.putBoolean("sharedBroker", true);
        server.putInt("brokerLingerSeconds", 0);
        server.putBoolean("readOnly", false);
        server.putBoolean("confirmWrites", false);
        server.put("bearerToken", args[0]);

        Preferences updates = Preferences.userRoot().node(ROOT)
                .node("application_preferences")
                .node("org.protege.editor.core.update.PluginManager");
        updates.putBoolean("CheckForUpdates", false);
        Preferences.userRoot().flush();
    }
}
