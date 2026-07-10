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
}
