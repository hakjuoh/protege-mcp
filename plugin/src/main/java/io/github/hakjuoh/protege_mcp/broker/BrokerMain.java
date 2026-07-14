package io.github.hakjuoh.protege_mcp.broker;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import io.github.hakjuoh.protege_mcp.server.EmbeddedHttpServer;

/**
 * Entry point of the standalone shared-broker process. Spawned on demand by the first Protégé
 * instance that finds no live broker ({@code java -cp <staged jar copies> ... BrokerMain --port N},
 * see {@link BrokerSpawner}); exits on its own once no instance references it (see
 * {@link BrokerServer}'s maintenance loop).
 *
 * <p>Runs from the plugin bundle jar outside OSGi/Protégé, so everything reachable from here must
 * stay free of Protégé imports. Its own lifecycle lines go to stdout, which the spawner redirects to
 * {@code ~/.protege-mcp/broker.log}.
 *
 * <p>Singleton discipline, in two layers. First a cross-process {@link FileLock} on
 * {@code broker.lock}, taken before binding and held until this broker stops serving (released
 * right after the server stops; the OS drops it with the process on a crash): it is what makes the
 * singleton hold even when the configured port is {@code 0} (the ephemeral-port preference) or when
 * a foreign app owns the configured port — in both of those shapes every racing sibling binds
 * successfully, so a port-based mutex alone would let two brokers serve. Second, the configured
 * port is bound <em>strictly</em>: a bind conflict means another (possibly older, pre-lock) broker
 * probably won the race — we probe for it and defer (exit 0). Only when the port is held by
 * something that is <em>not</em> a live broker (a foreign app) do we fall back to an ephemeral
 * port; discovery through {@code broker.json} still works, and an orphaned ephemeral broker that
 * never receives a registration exits after the boot grace period.
 */
public final class BrokerMain {

    private BrokerMain() {
    }

    public static void main(String[] args) throws Exception {
        BrokerHome home = BrokerHome.defaultHome();
        int port = 8123;
        String bind = BrokerState.DEFAULT_HOST;
        String version = "dev";
        long staleMs = BrokerServer.HEARTBEAT_STALE_MS;
        long lingerMs = BrokerServer.IDLE_LINGER_MS;
        long bootGraceMs = BrokerServer.BOOT_GRACE_MS;
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--home" -> home = new BrokerHome(Path.of(args[i + 1]));
                case "--port" -> port = Integer.parseInt(args[i + 1]);
                case "--bind" -> bind = args[i + 1];
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

        FileChannel lockChannel = null;
        try {
            lockChannel = FileChannel.open(home.lockFile(),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            boolean locked = tryLockSingleton(lockChannel);
            if (!locked) {
                // A sibling holds the lock (it may still be booting): defer to it once it becomes
                // discoverable — but keep retrying the lock too. A RETIRING or idle-exiting broker
                // stops answering probes and deletes broker.json BEFORE its process dies, so the
                // lock can free up mid-poll with nothing left to discover; without the retry that
                // shape ends in a give-up and the spawning instance falls back to standalone.
                for (int attempt = 0; attempt < 25 && !locked; attempt++) {
                    Thread.sleep(200);
                    if (findLiveBroker(home, dirSecret).isPresent()) {
                        System.out.println("protege-mcp-broker: lost the singleton lock to a sibling — exiting");
                        closeQuietly(lockChannel);
                        return;
                    }
                    try {
                        locked = tryLockSingleton(lockChannel);
                    } catch (IOException retryFailed) {
                        // The lock has been OBSERVED held: an I/O hiccup on a retry must not flip
                        // us to lockless serving next to a live holder. Keep polling — the give-up
                        // below stays the safe exit.
                    }
                }
                if (!locked) {
                    // The holder stayed locked yet undiscoverable for the whole poll: genuinely
                    // wedged. Exiting is the safe side — serving anyway could produce two brokers;
                    // recovery is a fresh spawn (the window's Start button, or the next instance).
                    System.out.println("protege-mcp-broker: singleton lock is held but no broker became "
                            + "discoverable — exiting");
                    closeQuietly(lockChannel);
                    return;
                }
            }
            singletonLockChannel = lockChannel;
        } catch (IOException lockingUnavailable) {
            // e.g. an NFS home without lockd: file locking itself is broken there, so nobody can
            // hold the lock. Pre-lock plugin versions served such setups fine — degrade to the
            // strict-bind mutex instead of refusing to serve at all.
            System.out.println("protege-mcp-broker: cannot use the singleton lock at "
                    + home.lockFile() + " (" + lockingUnavailable + ") — continuing without it");
            closeQuietly(lockChannel);
        }
        // Re-probe under the lock: a broker from a plugin version without the lock file could have
        // come up between the fast-path probe above and the lock acquisition.
        if (findLiveBroker(home, dirSecret).isPresent()) {
            System.out.println("protege-mcp-broker: another broker is already running — exiting");
            return;
        }

        CountDownLatch exit = new CountDownLatch(1);
        BrokerServer server = new BrokerServer(home, dirSecret, version, exit::countDown,
                staleMs, lingerMs, bootGraceMs);

        int bound;
        try {
            bound = server.start(bind, port);
        } catch (Exception e) {
            if (!EmbeddedHttpServer.isBindConflict(e)) {
                System.out.println("protege-mcp-broker: cannot bind " + bind + ":" + port
                        + " (" + e + ") — exiting; check the MCP Bind address preference");
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
            try {
                bound = server.start(bind, 0);
            } catch (Exception unbindable) {
                // BindException covers EADDRNOTAVAIL too, and that one follows the address to
                // port 0: the address itself (stale LAN IP after a DHCP renumber, ::1 with IPv6
                // off) is unbindable. Exit plainly — a crash-looping respawn would tell nobody
                // anything, and the instances keep the view honest ("Broker is down").
                System.out.println("protege-mcp-broker: address " + bind + " cannot be bound at "
                        + "all (" + unbindable + ") — exiting; check the MCP Bind address preference");
                return;
            }
            System.out.println("protege-mcp-broker: configured port " + port
                    + " is held by a foreign process — using an ephemeral port");
        }

        System.out.println("protege-mcp-broker: v" + version + " listening on http://"
                + EmbeddedHttpServer.connectHost(bind) + ":" + bound
                + (EmbeddedHttpServer.isWildcard(bind) ? " (all interfaces)" : "")
                + " (pid " + ProcessHandle.current().pid() + ")");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            releaseSingletonLock();
        }, "protege-mcp-broker-cleanup"));
        exit.await();
        server.stop();
        // Release the lock the moment we stop serving: a successor spawned by a version takeover
        // (or racing this idle exit) retries the lock right away, while this JVM can take a while
        // longer to die — see the HttpClient note below.
        releaseSingletonLock();
        System.out.println("protege-mcp-broker: stopped");
        // The JDK HttpClient inside the proxy may keep non-daemon worker threads alive briefly;
        // this is a single-purpose daemon process, so end it deterministically.
        System.exit(0);
    }

    /**
     * Held while this broker serves; released explicitly once it stops (the OS drops it with the
     * process on a crash). Static so the channel stays strongly reachable — a GC'd channel would
     * release the lock while the broker still serves.
     */
    private static FileChannel singletonLockChannel;

    /** One {@code tryLock} attempt on the open lock channel; false when another broker holds it. */
    private static boolean tryLockSingleton(FileChannel channel) throws IOException {
        try {
            return channel.tryLock() != null;
        } catch (OverlappingFileLockException heldInThisJvm) {
            return false;
        }
    }

    /** Closing the channel releases the {@link FileLock}; safe to call more than once. */
    private static void releaseSingletonLock() {
        FileChannel channel = singletonLockChannel;
        singletonLockChannel = null;
        closeQuietly(channel);
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // best-effort cleanup of a channel we are not keeping
            }
        }
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
