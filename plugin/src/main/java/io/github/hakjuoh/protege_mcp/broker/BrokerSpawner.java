package io.github.hakjuoh.protege_mcp.broker;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Launches the standalone broker process from inside Protégé. The broker's classpath is this
 * plugin's own bundle jar (compile-scope dependencies — Jetty, Jackson, jakarta.servlet — are
 * inlined into it) plus the slf4j jars resolved from the classes the <em>running</em> framework
 * loaded (slf4j is provided-scope, supplied by Protégé, so it is NOT in the bundle jar). If any
 * required jar cannot be resolved to a plain file, we don't spawn — the caller degrades to the
 * standalone (non-broker) mode instead of producing a broker that dies on NoClassDefFoundError.
 *
 * <p>The broker never runs from those original jars directly: they are first staged as
 * content-named copies under {@code ~/.protege-mcp/jars/} and the copies go on the {@code -cp}.
 * A JVM holds its classpath jars open for its whole life, and the broker outlives Protégé (idle
 * linger, boot grace) — on Windows that open handle would block deleting or replacing the plugin
 * jar in Protégé's plugins directory during an update, and on any OS an in-place rewrite of a jar
 * the broker is lazily class-loading from corrupts it mid-run. The copy's name embeds a content
 * hash, so a rebuilt jar with the same version gets a fresh copy, concurrent spawns staging the
 * same content are writing identical bytes, and a locked old copy never needs replacing. Staging
 * failures fall back to the original paths — a broker on the original jar beats no broker.
 */
public final class BrokerSpawner {

    private static final Logger log = LoggerFactory.getLogger(BrokerSpawner.class);

    private BrokerSpawner() {
    }

    /**
     * Spawn a broker for {@code home}; returns false when the environment makes that impossible.
     *
     * @param lingerMs initial idle linger (the user's preference at spawn time); negative uses the
     *     built-in default. Register/heartbeat payloads keep it current after boot either way.
     */
    public static boolean spawn(BrokerHome home, int port, String bindAddress, String version,
            long lingerMs) {
        Path pluginJar = jarOf(BrokerMain.class);
        Path slf4jJar = jarOf(Logger.class);
        if (pluginJar == null || slf4jJar == null) {
            log.warn("protege-mcp: cannot resolve the plugin/slf4j jar paths (plugin={}, slf4j={}) — "
                    + "shared broker unavailable", pluginJar, slf4jJar);
            return false;
        }
        String javaBin = Path.of(System.getProperty("java.home"), "bin",
                File.separatorChar == '\\' ? "java.exe" : "java").toString();

        try {
            home.ensureDir();
            // Deliberately NO slf4j binding on the broker's classpath: Protégé's logback would
            // arrive without its configuration and default to DEBUG-to-console, which both grows
            // broker.log without bound and can record request headers (bearer tokens, secrets).
            // With only the API present, library logging no-ops; the broker's own lifecycle lines
            // print via stdout.
            List<Path> classpath = stageClasspath(home.jarsDir(), List.of(pluginJar, slf4jJar));
            StringBuilder cp = new StringBuilder();
            for (Path p : classpath) {
                if (cp.length() > 0) {
                    cp.append(File.pathSeparatorChar);
                }
                cp.append(p);
            }
            rotateLog(home);
            ProcessBuilder pb = new ProcessBuilder(
                    brokerCommand(javaBin, cp.toString(), home, port, bindAddress, version, lingerMs));
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

    /** The broker's full command line; split out so the argument shape is unit-testable. */
    static List<String> brokerCommand(String javaBin, String classpath, BrokerHome home, int port,
            String bindAddress, String version, long lingerMs) {
        List<String> command = new ArrayList<>(List.of(javaBin,
                "-Xmx128m",
                "-cp", classpath,
                BrokerMain.class.getName(),
                "--home", home.dir().toString(),
                "--port", String.valueOf(port),
                "--bind", bindAddress,
                "--version", version));
        if (lingerMs >= 0) {
            command.add("--linger-ms");
            command.add(String.valueOf(lingerMs));
        }
        return command;
    }

    /** Copies younger than this survive a sweep — a concurrent sibling spawn may be about to exec them. */
    private static final long SWEEP_AGE_MS = TimeUnit.MINUTES.toMillis(10);

    /** Disambiguates temp names beyond the pid, so no two stagings ever share a temp file. */
    private static final AtomicLong STAGING_COUNTER = new AtomicLong();

    /** Stage every jar as a content-named copy under {@code jarsDir}, then sweep unused old copies. */
    static List<Path> stageClasspath(Path jarsDir, List<Path> jars) {
        List<Path> staged = new ArrayList<>(jars.size());
        for (Path jar : jars) {
            staged.add(stageJar(jarsDir, jar));
        }
        sweepStaleCopies(jarsDir, staged);
        return staged;
    }

    /**
     * A copy of {@code source} at {@code jarsDir/<name>-<sha256/12>.jar}, reusing an existing copy
     * (the name pins the content). Returns {@code source} itself for non-jar classpath entries
     * (dev/test run from {@code target/classes}) and when staging fails for any reason.
     */
    static Path stageJar(Path jarsDir, Path source) {
        if (!Files.isRegularFile(source)) {
            return source;
        }
        try {
            Files.createDirectories(jarsDir);
            Path target = jarsDir.resolve(stemOf(source) + "-" + contentHash(source) + ".jar");
            if (Files.isRegularFile(target) && Files.size(target) == Files.size(source)) {
                try {
                    // Re-arm the age gate: a sibling's sweep must not reap a copy this launch execs.
                    Files.setLastModifiedTime(target, FileTime.fromMillis(System.currentTimeMillis()));
                } catch (IOException bestEffort) {
                    // an unrefreshed timestamp only narrows the sweep protection — still usable
                }
                return target;
            }
            Path tmp = jarsDir.resolve(target.getFileName() + ".tmp-" + ProcessHandle.current().pid()
                    + "-" + STAGING_COUNTER.incrementAndGet());
            try {
                Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
                publishStagedCopy(tmp, target, Files.size(source));
            } finally {
                Files.deleteIfExists(tmp);
            }
            return target;
        } catch (IOException e) {
            log.warn("protege-mcp: could not stage {} for the broker — launching from the original "
                    + "(on Windows the running broker will then hold this jar locked)", source, e);
            return source;
        }
    }

    /**
     * Move a fully-written temp copy into place. When the atomic move fails over an existing target
     * of the expected size, a sibling staged the identical content first (the name embeds the hash)
     * and theirs is accepted as-is; any other failure retries as a plain move for file systems
     * without atomic moves.
     */
    static void publishStagedCopy(Path tmp, Path target, long expectedSize) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            if (!(Files.isRegularFile(target) && Files.size(target) == expectedSize)) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Best-effort removal of copies (and orphaned temp files) no current launch uses. Age-gated so
     * a copy a concurrent sibling spawn just staged is never yanked between its stage and exec; a
     * copy still open in a running old broker survives on Windows (delete fails) and is retried on
     * a later spawn.
     */
    private static void sweepStaleCopies(Path jarsDir, List<Path> keep) {
        long now = System.currentTimeMillis();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(jarsDir)) {
            for (Path entry : entries) {
                try {
                    if (!keep.contains(entry)
                            && now - Files.getLastModifiedTime(entry).toMillis() > SWEEP_AGE_MS) {
                        Files.deleteIfExists(entry);
                    }
                } catch (IOException skipped) {
                    // locked or vanished — housekeeping never blocks a spawn
                }
            }
        } catch (IOException | DirectoryIteratorException ignored) {
            // no jars dir yet, or a read error mid-iteration — housekeeping never blocks a spawn
        }
    }

    /** File name without a {@code .jar} suffix, restricted to file-name-safe characters. */
    private static String stemOf(Path jar) {
        String name = jar.getFileName().toString();
        if (name.length() > 4 && name.regionMatches(true, name.length() - 4, ".jar", 0, 4)) {
            name = name.substring(0, name.length() - 4);
        }
        return name.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    /** First 12 hex chars of the file's SHA-256 — enough to pin a copy to its exact build. */
    private static String contentHash(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException required) {
            throw new IOException("SHA-256 unavailable", required);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, n);
            }
        }
        byte[] sum = digest.digest();
        StringBuilder hex = new StringBuilder(12);
        for (int i = 0; i < 6; i++) {
            hex.append(String.format("%02x", sum[i]));
        }
        return hex.toString();
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
            String bindAddress, String version, long lingerMs) throws InterruptedException {
        Optional<BrokerState> live = BrokerMain.findLiveBroker(home, dirSecret);
        if (live.isPresent()) {
            return Optional.of(new BrokerClient(live.get().baseUrl(), dirSecret));
        }
        if (!spawn(home, port, bindAddress, version, lingerMs)) {
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
