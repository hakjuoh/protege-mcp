package io.github.hakjuoh.protege_mcp.broker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The broker as a real operating-system process: spawned with {@code java -cp} exactly like the
 * plugin does (the test uses the surefire classpath in place of the bundle jar), discovered through
 * {@code broker.json}, reference-counted, and gone from the OS once the last instance unregisters.
 * This pins the user-specified lifecycle end to end: spawn → register → unregister → self-exit.
 */
class BrokerProcessLifecycleTest {

    @TempDir
    Path tmp;

    @Test
    void spawnRegisterUnregisterExitsTheProcess() throws Exception {
        BrokerHome home = new BrokerHome(tmp.resolve("home"));
        home.ensureDir();
        String dirSecret = home.ensureDirSecret();

        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process broker = new ProcessBuilder(javaBin,
                "-cp", System.getProperty("java.class.path"),
                BrokerMain.class.getName(),
                "--home", home.dir().toString(),
                "--port", "0",
                "--version", "it",
                "--linger-ms", "300")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(home.logFile().toFile()))
                .start();

        try {
            // Discovery: the broker writes broker.json once bound and answers the secret probe.
            Optional<BrokerState> live = Optional.empty();
            for (int i = 0; i < 50 && live.isEmpty(); i++) {
                Thread.sleep(200);
                live = BrokerMain.findLiveBroker(home, dirSecret);
            }
            assertTrue(live.isPresent(), "spawned broker must become discoverable via broker.json");

            BrokerClient client = new BrokerClient(live.get().baseUrl(), dirSecret);
            String processId = client.register(ProcessHandle.current().pid(), "it", "tok", List.of());
            assertTrue(broker.isAlive(), "a registered instance keeps the broker alive");

            client.unregister(processId);
            assertTrue(broker.waitFor(15, TimeUnit.SECONDS),
                    "refcount 0 must end the broker PROCESS, not just its server");
            assertFalse(BrokerMain.findLiveBroker(home, dirSecret).isPresent(),
                    "a dead broker must not remain discoverable (state cleaned or probe fails)");
        } finally {
            broker.destroyForcibly();
        }
    }

    @Test
    void secondBrokerDefersToTheLiveOne() throws Exception {
        BrokerHome home = new BrokerHome(tmp.resolve("home2"));
        home.ensureDir();
        String dirSecret = home.ensureDirSecret();
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();

        Process first = new ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"),
                BrokerMain.class.getName(), "--home", home.dir().toString(), "--port", "0",
                "--boot-grace-ms", "60000")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(home.logFile().toFile()))
                .start();
        try {
            Optional<BrokerState> live = Optional.empty();
            for (int i = 0; i < 50 && live.isEmpty(); i++) {
                Thread.sleep(200);
                live = BrokerMain.findLiveBroker(home, dirSecret);
            }
            assertTrue(live.isPresent());

            Process second = new ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"),
                    BrokerMain.class.getName(), "--home", home.dir().toString(), "--port", "0")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(home.logFile().toFile()))
                    .start();
            assertTrue(second.waitFor(10, TimeUnit.SECONDS),
                    "a sibling that finds a live broker must exit instead of double-serving");
            assertTrue(BrokerMain.findLiveBroker(home, dirSecret).isPresent(),
                    "the original broker keeps serving after the sibling defers");
        } finally {
            first.destroyForcibly();
        }
    }

    @Test
    void simultaneousEphemeralSpawnsLeaveExactlyOneBroker() throws Exception {
        // With the ephemeral-port preference (--port 0) every sibling BINDS successfully, so the
        // strict-bind mutex that dedupes fixed-port brokers never fires — the broker.lock file
        // lock is the only thing standing between a spawn race and two live brokers.
        BrokerHome home = new BrokerHome(tmp.resolve("home3"));
        home.ensureDir();
        String dirSecret = home.ensureDirSecret();
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();

        Process a = null;
        Process b = null;
        try {
            // Launch both before either can publish broker.json: a pure discovery check cannot
            // dedupe them — only the singleton lock decides.
            a = new ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"),
                    BrokerMain.class.getName(), "--home", home.dir().toString(), "--port", "0",
                    "--boot-grace-ms", "60000")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(home.logFile().toFile()))
                    .start();
            b = new ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"),
                    BrokerMain.class.getName(), "--home", home.dir().toString(), "--port", "0",
                    "--boot-grace-ms", "60000")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(home.logFile().toFile()))
                    .start();

            // One of the two must defer and exit on its own; destroyForcibly is only cleanup.
            long deadline = System.currentTimeMillis() + 20_000;
            while (a.isAlive() && b.isAlive() && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }
            assertTrue(!a.isAlive() || !b.isAlive(),
                    "one of two racing ephemeral-port brokers must defer and exit");
            Process survivor = a.isAlive() ? a : b;
            assertTrue(survivor.isAlive(), "the lock winner must keep serving");

            Optional<BrokerState> live = Optional.empty();
            for (int i = 0; i < 50 && live.isEmpty(); i++) {
                Thread.sleep(200);
                live = BrokerMain.findLiveBroker(home, dirSecret);
            }
            assertTrue(live.isPresent(), "the surviving broker must be discoverable");
            assertTrue(live.get().pid == survivor.pid(),
                    "broker.json must name the surviving process, not the deferred one");
        } finally {
            if (a != null) {
                a.destroyForcibly();
            }
            if (b != null) {
                b.destroyForcibly();
            }
        }
    }

    @Test
    void unbindableBindAddressExitsCleanlyInsteadOfCrashLooping() throws Exception {
        // 203.0.113.7 (TEST-NET-3) is never assigned to a local interface, so binding it fails
        // with EADDRNOTAVAIL — which is a BindException just like a port conflict. The broker
        // must recognise that the ADDRESS (not the port) is the problem and exit with a clear
        // message rather than dying on an uncaught exception every respawn.
        BrokerHome home = new BrokerHome(tmp.resolve("home4"));
        home.ensureDir();
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();

        Process broker = new ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"),
                BrokerMain.class.getName(), "--home", home.dir().toString(), "--port", "0",
                "--bind", "203.0.113.7")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(home.logFile().toFile()))
                .start();
        try {
            assertTrue(broker.waitFor(20, TimeUnit.SECONDS),
                    "an unbindable address must end the process, not hang it");
            String log = java.nio.file.Files.readString(home.logFile());
            assertTrue(log.contains("cannot be bound at all") || log.contains("cannot bind"),
                    "the exit must explain the bad address plainly:\n" + log);
        } finally {
            broker.destroyForcibly();
        }
    }
}
