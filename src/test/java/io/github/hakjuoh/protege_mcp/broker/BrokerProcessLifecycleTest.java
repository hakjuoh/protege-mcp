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
    void successorRetriesTheLockWhileADyingHolderFinishesExiting() throws Exception {
        // The dying-holder shape: a retiring (or idle-exiting) broker stops answering probes and
        // deletes broker.json BEFORE its process dies, so a successor's first tryLock loses to a
        // corpse and discovery never comes true. The successor must keep retrying the lock inside
        // its defer poll instead of giving up ("singleton lock is held but no broker became
        // discoverable"). The test JVM plays the dying holder: it holds the lock cross-process
        // against the child broker, then releases it mid-poll with no broker.json to discover.
        BrokerHome home = new BrokerHome(tmp.resolve("home5"));
        home.ensureDir();
        String dirSecret = home.ensureDirSecret();
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();

        try (java.nio.channels.FileChannel holder = java.nio.channels.FileChannel.open(home.lockFile(),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
            java.nio.channels.FileLock lock = holder.lock();

            Process successor = new ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"),
                    BrokerMain.class.getName(), "--home", home.dir().toString(), "--port", "0",
                    "--boot-grace-ms", "60000")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(home.logFile().toFile()))
                    .start();
            try {
                // Long enough for the successor to have lost its first tryLock and entered the
                // defer poll (25 x 200ms budget); on a machine slow enough to still be booting,
                // the first attempt simply succeeds and the assertion below still holds.
                Thread.sleep(2_500);
                lock.release();

                Optional<BrokerState> live = Optional.empty();
                for (int i = 0; i < 50 && live.isEmpty(); i++) {
                    Thread.sleep(200);
                    live = BrokerMain.findLiveBroker(home, dirSecret);
                }
                assertTrue(live.isPresent(),
                        "the successor must retry the lock and serve once the dying holder released it");
                assertTrue(live.get().pid == successor.pid(),
                        "broker.json must name the successor that took over the freed lock");
            } finally {
                successor.destroyForcibly();
                successor.waitFor(5, TimeUnit.SECONDS); // let the log-file handle go before @TempDir cleanup
            }
        }
    }

    @Test
    void successorGivesUpWhenTheLockStaysHeldAndNothingPublishes() throws Exception {
        // The safety side of the lock retry: against a holder that neither dies nor publishes
        // (genuinely wedged), the successor must still exit with the give-up line instead of ever
        // serving next to it — the retry must not have weakened the anti-split-brain guarantee.
        BrokerHome home = new BrokerHome(tmp.resolve("home7"));
        home.ensureDir();
        String dirSecret = home.ensureDirSecret();
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();

        try (java.nio.channels.FileChannel holder = java.nio.channels.FileChannel.open(home.lockFile(),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
            java.nio.channels.FileLock lock = holder.lock();

            Process successor = new ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"),
                    BrokerMain.class.getName(), "--home", home.dir().toString(), "--port", "0",
                    "--boot-grace-ms", "60000")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(home.logFile().toFile()))
                    .start();
            try {
                assertTrue(successor.waitFor(20, TimeUnit.SECONDS),
                        "a successor facing a wedged holder must give up, not hang or serve");
                assertTrue(brokerLog(home).contains("singleton lock is held but no broker became discoverable"),
                        () -> "the give-up must say why:\n" + brokerLog(home));
                assertFalse(BrokerMain.findLiveBroker(home, dirSecret).isPresent(),
                        "no broker may serve while the wedged holder keeps the lock");
            } finally {
                successor.destroyForcibly();
                successor.waitFor(5, TimeUnit.SECONDS);
            }
            lock.release();
        }
    }

    @Test
    void versionTakeoverHandsTheLockToTheReplacementBroker() throws Exception {
        // End-to-end regression for the takeover race: retire an idle broker exactly the way
        // BrokerLink.maybeRetireForUpgrade does (shutdown request, then wait only for the probe to
        // fail — NOT for the process to die), then immediately spawn the replacement. The retired
        // broker must release its lock as it stops serving, and the replacement must win the lock
        // even when its first attempt lands while the old process is still dying.
        BrokerHome home = new BrokerHome(tmp.resolve("home6"));
        home.ensureDir();
        String dirSecret = home.ensureDirSecret();
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();

        Process old = new ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"),
                BrokerMain.class.getName(), "--home", home.dir().toString(), "--port", "0",
                "--version", "0.0.1-old", "--boot-grace-ms", "60000")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(home.logFile().toFile()))
                .start();
        Process replacement = null;
        try {
            Optional<BrokerState> live = Optional.empty();
            for (int i = 0; i < 50 && live.isEmpty(); i++) {
                Thread.sleep(200);
                live = BrokerMain.findLiveBroker(home, dirSecret);
            }
            assertTrue(live.isPresent(), "the old broker must come up first");

            BrokerClient client = new BrokerClient(live.get().baseUrl(), dirSecret);
            client.requestShutdown();
            for (int i = 0; i < 20 && client.probe(); i++) {
                Thread.sleep(150);
            }
            assertFalse(client.probe(),
                    "the retiring broker must stop answering probes within the 3s drain");

            replacement = new ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"),
                    BrokerMain.class.getName(), "--home", home.dir().toString(), "--port", "0",
                    "--version", "0.0.2-new", "--boot-grace-ms", "60000")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(home.logFile().toFile()))
                    .start();

            live = Optional.empty();
            for (int i = 0; i < 50 && live.isEmpty(); i++) {
                Thread.sleep(200);
                live = BrokerMain.findLiveBroker(home, dirSecret);
            }
            assertTrue(live.isPresent(),
                    () -> "the replacement broker must take over after the retirement:\n" + brokerLog(home));
            assertTrue(live.get().pid == replacement.pid(),
                    "broker.json must name the replacement, not the retired broker");
            assertTrue("0.0.2-new".equals(live.get().version),
                    "the takeover must land on the new version, got " + live.get().version);
        } finally {
            if (replacement != null) {
                replacement.destroyForcibly();
                replacement.waitFor(5, TimeUnit.SECONDS);
            }
            old.destroyForcibly();
            old.waitFor(5, TimeUnit.SECONDS);
        }
    }

    /** The broker log for a failure message; never throws into an assertion. */
    private static String brokerLog(BrokerHome home) {
        try {
            return java.nio.file.Files.readString(home.logFile());
        } catch (java.io.IOException e) {
            return "<broker.log unreadable: " + e + ">";
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
