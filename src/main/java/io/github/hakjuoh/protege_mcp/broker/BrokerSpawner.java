package io.github.hakjuoh.protege_mcp.broker;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Launches the standalone broker process from inside Protégé. The broker's classpath is this
 * plugin's own bundle jar (compile-scope dependencies — Jetty, Jackson, jakarta.servlet — are
 * inlined into it) plus the slf4j jars resolved from the classes the <em>running</em> framework
 * loaded (slf4j is provided-scope, supplied by Protégé, so it is NOT in the bundle jar). If any
 * required jar cannot be resolved to a plain file, we don't spawn — the caller degrades to the
 * standalone (non-broker) mode instead of producing a broker that dies on NoClassDefFoundError.
 */
public final class BrokerSpawner {

    private static final Logger log = LoggerFactory.getLogger(BrokerSpawner.class);

    private BrokerSpawner() {
    }

    /** Spawn a broker for {@code home}; returns false when the environment makes that impossible. */
    public static boolean spawn(BrokerHome home, int port, String version) {
        Path pluginJar = jarOf(BrokerMain.class);
        Path slf4jJar = jarOf(Logger.class);
        if (pluginJar == null || slf4jJar == null) {
            log.warn("protege-mcp: cannot resolve the plugin/slf4j jar paths (plugin={}, slf4j={}) — "
                    + "shared broker unavailable", pluginJar, slf4jJar);
            return false;
        }
        // Deliberately NO slf4j binding on the broker's classpath: Protégé's logback would arrive
        // without its configuration and default to DEBUG-to-console, which both grows broker.log
        // without bound and can record request headers (bearer tokens, secrets). With only the API
        // present, library logging no-ops; the broker's own lifecycle lines print via stdout.
        List<Path> classpath = new ArrayList<>(List.of(pluginJar, slf4jJar));

        String javaBin = Path.of(System.getProperty("java.home"), "bin",
                File.separatorChar == '\\' ? "java.exe" : "java").toString();
        StringBuilder cp = new StringBuilder();
        for (Path p : classpath) {
            if (cp.length() > 0) {
                cp.append(File.pathSeparatorChar);
            }
            cp.append(p);
        }

        try {
            home.ensureDir();
            rotateLog(home);
            ProcessBuilder pb = new ProcessBuilder(javaBin,
                    "-Xmx128m",
                    "-cp", cp.toString(),
                    BrokerMain.class.getName(),
                    "--home", home.dir().toString(),
                    "--port", String.valueOf(port),
                    "--version", version);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(home.logFile().toFile()));
            Process process = pb.start();
            log.info("protege-mcp: spawned shared broker (pid {}, log {})", process.pid(), home.logFile());
            return true;
        } catch (IOException e) {
            log.warn("protege-mcp: failed to spawn the shared broker", e);
            return false;
        }
    }

    /** Keep broker.log bounded across spawns: roll it aside once it exceeds half a megabyte. */
    private static void rotateLog(BrokerHome home) {
        try {
            Path log = home.logFile();
            if (Files.exists(log) && Files.size(log) > 512 * 1024) {
                Files.move(log, log.resolveSibling("broker.log.1"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // best-effort housekeeping — never block the spawn on it
        }
    }

    /** The plain-file jar a class was loaded from, or null (unusual OSGi/CI loaders). */
    static Path jarOf(Class<?> type) {
        try {
            CodeSource source = type.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            URL url = source.getLocation();
            String s = url.toString();
            // Felix/Equinox may hand back decorated forms like "reference:file:/…" or "jar:file:…!/".
            if (s.startsWith("reference:")) {
                s = s.substring("reference:".length());
            }
            if (s.startsWith("jar:")) {
                s = s.substring("jar:".length());
                int bang = s.indexOf("!/");
                if (bang > 0) {
                    s = s.substring(0, bang);
                }
            }
            if (!s.startsWith("file:")) {
                return null;
            }
            Path path = Path.of(new URI(s.replace(" ", "%20")));
            return Files.exists(path) ? path : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Discover-or-spawn: returns a client for a live broker, waiting for a fresh spawn to boot. */
    public static Optional<BrokerClient> ensureBroker(BrokerHome home, String dirSecret, int port,
            String version) throws InterruptedException {
        Optional<BrokerState> live = BrokerMain.findLiveBroker(home, dirSecret);
        if (live.isPresent()) {
            return Optional.of(new BrokerClient(live.get().baseUrl(), dirSecret));
        }
        if (!spawn(home, port, version)) {
            return Optional.empty();
        }
        // The broker writes broker.json once bound; poll briefly for it to come up.
        for (int attempt = 0; attempt < 25; attempt++) {
            Thread.sleep(200);
            live = BrokerMain.findLiveBroker(home, dirSecret);
            if (live.isPresent()) {
                return Optional.of(new BrokerClient(live.get().baseUrl(), dirSecret));
            }
        }
        log.warn("protege-mcp: spawned broker did not come up within 5s (see {})", home.logFile());
        return Optional.empty();
    }
}
