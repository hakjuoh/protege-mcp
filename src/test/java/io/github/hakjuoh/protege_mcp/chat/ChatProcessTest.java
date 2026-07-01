package io.github.hakjuoh.protege_mcp.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Method-level tests for {@link ChatProcess}. Uses a hand-rolled {@link FakeProcess} subclass of
 * {@link Process} to observe the call sequence and timing driven by {@link ChatProcess#cancel()} and
 * {@link ChatProcess#isAlive()}. No Swing/AWT/Protégé collaborators are touched.
 */
class ChatProcessTest {

    /**
     * A controllable {@link Process} test double. Records which lifecycle methods were called (and how
     * many times), lets a test dictate {@code isAlive()} and how {@code waitFor(long, TimeUnit)}
     * responds (exit-in-time vs. timeout vs. interrupt), and exposes the stdin stream so a test can
     * assert it was closed exactly once.
     */
    private static class FakeProcess extends Process {

        final AtomicBoolean alive = new AtomicBoolean(true);
        final AtomicInteger destroyCount = new AtomicInteger();
        final AtomicInteger destroyForciblyCount = new AtomicInteger();
        final AtomicInteger waitForTimedCount = new AtomicInteger();
        final AtomicLong lastWaitForTimeout = new AtomicLong(-1);
        final AtomicReference<TimeUnit> lastWaitForUnit = new AtomicReference<>();

        /** What {@code waitFor(long, TimeUnit)} returns; true = exited within the window. */
        volatile boolean waitForResult = true;
        /** If true, {@code waitFor(long, TimeUnit)} throws {@link InterruptedException}. */
        volatile boolean waitForThrowsInterrupted = false;
        /** Optional latch counted down when {@code waitFor(long, TimeUnit)} is invoked. */
        volatile CountDownLatch waitForEntered;
        /** Optional latch counted down when {@code destroyForcibly()} is invoked. */
        volatile CountDownLatch destroyForciblyLatch;

        final CountingOutputStream stdin = new CountingOutputStream();

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            waitForTimedCount.incrementAndGet();
            lastWaitForTimeout.set(timeout);
            lastWaitForUnit.set(unit);
            CountDownLatch entered = waitForEntered;
            if (entered != null) {
                entered.countDown();
            }
            if (waitForThrowsInterrupted) {
                throw new InterruptedException("test-induced");
            }
            return waitForResult;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public void destroy() {
            destroyCount.incrementAndGet();
            alive.set(false);
        }

        @Override
        public Process destroyForcibly() {
            destroyForciblyCount.incrementAndGet();
            alive.set(false);
            CountDownLatch l = destroyForciblyLatch;
            if (l != null) {
                l.countDown();
            }
            return this;
        }
    }

    /** An {@link OutputStream} that counts {@link #close()} calls; optionally throws on close. */
    private static final class CountingOutputStream extends OutputStream {
        final AtomicInteger closeCount = new AtomicInteger();
        volatile boolean throwOnClose = false;

        @Override
        public void write(int b) {
            // discard
        }

        @Override
        public void close() throws IOException {
            closeCount.incrementAndGet();
            if (throwOnClose) {
                throw new IOException("test-induced close failure");
            }
        }
    }

    // ---- constructor ----

    @Test
    void constructorWithNonNullArgsProducesUsableInstance() {
        FakeProcess p = new FakeProcess();
        Thread worker = new Thread(() -> {}, "worker");
        ChatProcess cp = new ChatProcess(p, worker);
        assertNotNull(cp, "constructed instance should not be null");
        assertTrue(cp.isAlive(), "isAlive should reflect the live fake process");
    }

    @Test
    void constructorAcceptsNullWorkerBecauseWorkerIsUnusedByPublicApi() {
        FakeProcess p = new FakeProcess();
        // worker is stored but never dereferenced by isAlive/cancel, so null is tolerated.
        ChatProcess cp = new ChatProcess(p, null);
        assertTrue(cp.isAlive(), "isAlive delegates only to the process, not the worker");
    }

    @Test
    void constructorWithNullProcessDefersFailureUntilProcessIsUsed() {
        // The constructor itself does not dereference process, so it does not throw.
        ChatProcess cp = new ChatProcess(null, new Thread(() -> {}));
        assertThrows(NullPointerException.class, cp::isAlive,
                "isAlive should NPE once it dereferences the null process");
    }

    // ---- isAlive() ----

    @Test
    void isAliveReturnsTrueWhenProcessAlive() {
        FakeProcess p = new FakeProcess();
        p.alive.set(true);
        ChatProcess cp = new ChatProcess(p, null);
        assertTrue(cp.isAlive(), "should report alive when process.isAlive() is true");
    }

    @Test
    void isAliveReturnsFalseWhenProcessDead() {
        FakeProcess p = new FakeProcess();
        p.alive.set(false);
        ChatProcess cp = new ChatProcess(p, null);
        assertFalse(cp.isAlive(), "should report not alive when process.isAlive() is false");
    }

    @Test
    void isAliveIsConsistentAcrossRepeatedCalls() {
        FakeProcess p = new FakeProcess();
        p.alive.set(true);
        ChatProcess cp = new ChatProcess(p, null);
        assertTrue(cp.isAlive(), "first call");
        assertTrue(cp.isAlive(), "second call should be consistent");
        p.alive.set(false);
        assertFalse(cp.isAlive(), "should track the underlying process state change");
    }

    // ---- cancel(): happy path ----

    @Test
    @Timeout(5)
    void cancelClosesStdinExactlyOnceOnFirstCall() {
        FakeProcess p = new FakeProcess();
        ChatProcess cp = new ChatProcess(p, null);
        cp.cancel();
        assertEquals(1, p.stdin.closeCount.get(), "cancel should close stdin exactly once");
    }

    @Test
    @Timeout(5)
    void cancelInvokesDestroyOnFirstCall() {
        FakeProcess p = new FakeProcess();
        ChatProcess cp = new ChatProcess(p, null);
        cp.cancel();
        assertEquals(1, p.destroyCount.get(), "cancel should call process.destroy() once");
    }

    @Test
    @Timeout(5)
    void cancelReturnsImmediatelyWhileKillerRunsAsync() throws InterruptedException {
        FakeProcess p = new FakeProcess();
        // Block the killer thread inside waitFor so cancel() must return before it completes.
        CountDownLatch inWaitFor = new CountDownLatch(1);
        p.waitForEntered = inWaitFor;
        p.waitForResult = true;
        ChatProcess cp = new ChatProcess(p, null);

        long start = System.nanoTime();
        cp.cancel();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        // The caller thread should not block on the 1500ms waitFor window.
        assertTrue(elapsedMs < 1000, "cancel() should return immediately, took ms=" + elapsedMs);
        assertTrue(inWaitFor.await(3, TimeUnit.SECONDS),
                "killer thread should still enter waitFor asynchronously");
    }

    @Test
    @Timeout(5)
    void cancelKillerPassesFifteenHundredMillisTimeoutToWaitFor() throws InterruptedException {
        FakeProcess p = new FakeProcess();
        CountDownLatch inWaitFor = new CountDownLatch(1);
        p.waitForEntered = inWaitFor;
        p.waitForResult = true;
        ChatProcess cp = new ChatProcess(p, null);
        cp.cancel();
        assertTrue(inWaitFor.await(3, TimeUnit.SECONDS), "killer should call waitFor");
        assertEquals(1500L, p.lastWaitForTimeout.get(), "killer should wait 1500 units");
        assertEquals(TimeUnit.MILLISECONDS, p.lastWaitForUnit.get(), "unit should be MILLISECONDS");
    }

    @Test
    @Timeout(5)
    void cancelDoesNotForceKillWhenProcessExitsWithinWindow() throws InterruptedException {
        FakeProcess p = new FakeProcess();
        CountDownLatch inWaitFor = new CountDownLatch(1);
        p.waitForEntered = inWaitFor;
        p.waitForResult = true; // exited within window
        ChatProcess cp = new ChatProcess(p, null);
        cp.cancel();
        assertTrue(inWaitFor.await(3, TimeUnit.SECONDS), "killer should run waitFor");
        // Give the killer thread a brief window to (not) escalate.
        Thread.sleep(100);
        assertEquals(0, p.destroyForciblyCount.get(),
                "destroyForcibly must NOT be called when the process exits in time");
    }

    @Test
    @Timeout(5)
    void cancelForceKillsWhenProcessDoesNotExitWithinWindow() throws InterruptedException {
        FakeProcess p = new FakeProcess();
        CountDownLatch forced = new CountDownLatch(1);
        p.destroyForciblyLatch = forced;
        p.waitForResult = false; // did NOT exit within window
        ChatProcess cp = new ChatProcess(p, null);
        cp.cancel();
        assertTrue(forced.await(3, TimeUnit.SECONDS),
                "destroyForcibly should be called when waitFor reports non-exit");
        assertEquals(1, p.destroyForciblyCount.get(), "exactly one force-kill");
    }

    @Test
    @Timeout(5)
    void cancelForceKillsWhenKillerThreadIsInterrupted() throws InterruptedException {
        FakeProcess p = new FakeProcess();
        CountDownLatch forced = new CountDownLatch(1);
        p.destroyForciblyLatch = forced;
        p.waitForThrowsInterrupted = true; // waitFor throws InterruptedException
        ChatProcess cp = new ChatProcess(p, null);
        cp.cancel();
        assertTrue(forced.await(3, TimeUnit.SECONDS),
                "InterruptedException path should still force-kill");
        assertEquals(1, p.destroyForciblyCount.get(), "exactly one force-kill on interrupt");
    }

    @Test
    @Timeout(5)
    void cancelSwallowsIOExceptionFromStdinClose() {
        FakeProcess p = new FakeProcess();
        p.stdin.throwOnClose = true; // simulate stream already closed
        ChatProcess cp = new ChatProcess(p, null);
        // Must not propagate the IOException.
        cp.cancel();
        assertEquals(1, p.stdin.closeCount.get(), "close was still attempted once");
        assertEquals(1, p.destroyCount.get(),
                "destroy should still run after a swallowed close failure");
    }

    // ---- cancel(): idempotence ----

    @Test
    @Timeout(5)
    void cancelSecondCallIsIdempotentAndDoesNotCloseStdinAgain() {
        FakeProcess p = new FakeProcess();
        ChatProcess cp = new ChatProcess(p, null);
        cp.cancel();
        int closesAfterFirst = p.stdin.closeCount.get();
        int destroysAfterFirst = p.destroyCount.get();
        cp.cancel();
        assertEquals(closesAfterFirst, p.stdin.closeCount.get(),
                "second cancel must not close stdin again");
        assertEquals(destroysAfterFirst, p.destroyCount.get(),
                "second cancel must not call destroy again");
    }

    @Test
    @Timeout(5)
    void cancelSecondCallDoesNotSpawnAnotherKillerWaitFor() throws InterruptedException {
        FakeProcess p = new FakeProcess();
        p.waitForResult = true;
        CountDownLatch inWaitFor = new CountDownLatch(1);
        p.waitForEntered = inWaitFor;
        ChatProcess cp = new ChatProcess(p, null);
        cp.cancel();
        assertTrue(inWaitFor.await(3, TimeUnit.SECONDS), "first killer runs");
        Thread.sleep(100);
        int waitForCallsAfterFirst = p.waitForTimedCount.get();
        cp.cancel();
        Thread.sleep(150);
        assertEquals(waitForCallsAfterFirst, p.waitForTimedCount.get(),
                "second cancel must not spawn a second killer that calls waitFor");
    }

    @Test
    @Timeout(5)
    void cancelFirstCallSpawnsExactlyOneKillerThread() throws InterruptedException {
        FakeProcess p = new FakeProcess();
        p.waitForResult = true;
        CountDownLatch inWaitFor = new CountDownLatch(1);
        p.waitForEntered = inWaitFor;
        ChatProcess cp = new ChatProcess(p, null);
        cp.cancel();
        assertTrue(inWaitFor.await(3, TimeUnit.SECONDS), "killer should have entered waitFor");
        // Let the killer finish; then confirm only one waitFor invocation happened.
        Thread.sleep(100);
        assertEquals(1, p.waitForTimedCount.get(),
                "exactly one killer thread ran waitFor once");
    }

    @Test
    @Timeout(5)
    void cancelKillerThreadIsNamedAndDaemon() throws InterruptedException {
        // Capture the killer thread via a waitFor invocation that records Thread.currentThread().
        FakeProcess p = new FakeProcess();
        AtomicReference<Thread> killerRef = new AtomicReference<>();
        CountDownLatch captured = new CountDownLatch(1);
        FakeProcess capturing = new FakeProcess() {
            @Override
            public boolean waitFor(long timeout, TimeUnit unit) {
                killerRef.set(Thread.currentThread());
                captured.countDown();
                return true;
            }
        };
        ChatProcess cp = new ChatProcess(capturing, null);
        cp.cancel();
        assertTrue(captured.await(3, TimeUnit.SECONDS), "killer thread should invoke waitFor");
        Thread killer = killerRef.get();
        assertNotNull(killer, "killer thread reference should be captured");
        assertTrue(killer.isDaemon(), "killer thread must be a daemon");
    }
}
