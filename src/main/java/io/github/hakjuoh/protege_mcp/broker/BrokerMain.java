package io.github.hakjuoh.protege_mcp.broker;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import io.github.hakjuoh.protege_mcp.server.EmbeddedHttpServer;

/**
 * Entry point of the standalone shared-broker process. Spawned on demand by the first Protégé
 * instance that finds no live broker ({@code java -cp <plugin jar> ... BrokerMain --port N}); exits
 * on its own once no instance references it (see {@link BrokerServer}'s maintenance loop).
 *
 * <p>Runs from the plugin bundle jar outside OSGi/Protégé, so everything reachable from here must
 * stay free of Protégé imports. Its own lifecycle lines go to stdout, which the spawner redirects to
 * {@code ~/.protege-mcp/broker.log}.
 *
 * <p>Singleton discipline: the configured port is bound <em>strictly</em>. If the bind conflicts,
 * another broker probably won the race — we probe for it and defer (exit 0). Only when the port is
 * held by something that is <em>not</em> a live broker (a foreign app) do we fall back to an
 * ephemeral port; discovery through {@code broker.json} still works, and an orphaned ephemeral
 * broker that never receives a registration exits after the boot grace period.
 */
public final class BrokerMain {

    private BrokerMain() {
    }

    public static void main(String[] args) throws Exception {
        BrokerHome home = BrokerHome.defaultHome();
        int port = 8123;
        String version = "dev";
        long staleMs = BrokerServer.HEARTBEAT_STALE_MS;
        long lingerMs = BrokerServer.IDLE_LINGER_MS;
        long bootGraceMs = BrokerServer.BOOT_GRACE_MS;
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--home" -> home = new BrokerHome(Path.of(args[i + 1]));
                case "--port" -> port = Integer.parseInt(args[i + 1]);
                case "--version" -> version = args[i + 1];
                // Lifecycle overrides for tests (shorten the reference-count exit without minutes).
                case "--stale-ms" -> staleMs = Long.parseLong(args[i + 1]);
                case "--linger-ms" -> lingerMs = Long.parseLong(args[i + 1]);
                case "--boot-grace-ms" -> bootGraceMs = Long.parseLong(args[i + 1]);
                default -> {
                    System.err.println("protege-mcp-broker: unknown argument " + args[i]);
                    System.exit(2);
                }
            }
        }

        home.ensureDir();
        String dirSecret = home.ensureDirSecret();

        if (findLiveBroker(home, dirSecret).isPresent()) {
            System.out.println("protege-mcp-broker: another broker is already running — exiting");
            return;
        }

        CountDownLatch exit = new CountDownLatch(1);
        BrokerServer server = new BrokerServer(home, dirSecret, version, exit::countDown,
                staleMs, lingerMs, bootGraceMs);

        int bound;
        try {
            bound = server.start(port);
        } catch (Exception e) {
            if (!EmbeddedHttpServer.isBindConflict(e)) {
                throw e;
            }
            // Lost the port. If a sibling broker took it (spawn race), defer to it; a foreign app
            // holding the port means we serve from an ephemeral port instead (discovery is by file).
            for (int attempt = 0; attempt < 10; attempt++) {
                if (findLiveBroker(home, dirSecret).isPresent()) {
                    System.out.println("protege-mcp-broker: lost the bind race to a sibling — exiting");
                    return;
                }
                Thread.sleep(300);
            }
            System.out.println("protege-mcp-broker: configured port " + port
                    + " is held by a foreign process — using an ephemeral port");
            bound = server.start(0);
        }

        System.out.println("protege-mcp-broker: v" + version + " listening on http://127.0.0.1:" + bound
                + " (pid " + ProcessHandle.current().pid() + ")");

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "protege-mcp-broker-cleanup"));
        exit.await();
        server.stop();
        System.out.println("protege-mcp-broker: stopped");
        // The JDK HttpClient inside the proxy may keep non-daemon worker threads alive briefly;
        // this is a single-purpose daemon process, so end it deterministically.
        System.exit(0);
    }

    /** True when {@code broker.json} names a process that answers the authenticated info probe. */
    static Optional<BrokerState> findLiveBroker(BrokerHome home, String dirSecret) {
        Optional<BrokerState> state = home.readState();
        if (state.isEmpty()) {
            return Optional.empty();
        }
        return BrokerClient.probe(state.get(), dirSecret) ? state : Optional.empty();
    }
}
