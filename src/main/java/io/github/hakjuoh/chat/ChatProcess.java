package io.github.hakjuoh.chat;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A handle to a running provider turn: the spawned CLI {@link Process} plus the daemon worker that
 * pumps its output. Used by the panel to {@link #cancel()} an in-flight turn (Stop button / view
 * dispose). Construction is package-private — only {@link CliSupport#spawn} makes these.
 */
public final class ChatProcess {

    private final Process process;
    private final Thread worker;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    ChatProcess(Process process, Thread worker) {
        this.process = process;
        this.worker = worker;
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /**
     * Stop the turn. Returns immediately (safe to call on the EDT): closes stdin, requests termination,
     * then escalates to a force-kill on a short-lived daemon thread if the process does not exit
     * promptly. Idempotent — repeated calls are no-ops.
     */
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        try {
            process.getOutputStream().close();
        } catch (Exception ignored) {
            // stdin may already be closed
        }
        process.destroy();
        Thread killer = new Thread(() -> {
            try {
                if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }, "protege-chat-kill");
        killer.setDaemon(true);
        killer.start();
    }
}
