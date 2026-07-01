package io.github.hakjuoh.protege_mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.OWLEditorKit;
import org.protege.editor.owl.model.OWLModelManager;

/**
 * Method-level tests for {@link OntologyAccess}'s EDT-marshalling seam.
 *
 * <p>{@link OntologyAccess#compute(Function)} / {@link OntologyAccess#compute(Function, long)} /
 * {@link OntologyAccess#run(Consumer)} route work onto the Swing event-dispatch thread through the
 * package-private {@link OntologyAccess.EdtGateway}. Because this test lives in the same package it
 * can supply hand-rolled gateway doubles and drive the fast path, async path, timeout, cancellation
 * and exception-translation logic deterministically, with no live Protégé runtime.
 *
 * <p>{@link OWLEditorKit} is a concrete class (not an interface) so it cannot be {@code Proxy}'d; a
 * bare instance is created via {@code sun.misc.Unsafe.allocateInstance} — its {@code getModelManager()}
 * then returns {@code null}, which is fine because every {@code fn} here ignores the model manager.
 */
class OntologyAccessSeamTest {

    // ------------------------------------------------------------------ plumbing

    /** Allocate an instance without running its constructor (via sun.misc.Unsafe). */
    private static Object allocate(Class<?> type) throws Exception {
        Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        f.setAccessible(true);
        Object unsafe = f.get(null);
        Method allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
        return allocate.invoke(unsafe, type);
    }

    /** A bare OWLEditorKit whose getModelManager() returns null (no wired modelManager field). */
    private static OWLEditorKit bareKit() throws Exception {
        return (OWLEditorKit) allocate(OWLEditorKit.class);
    }

    /** Gateway reporting "on the EDT" — compute runs the body inline and never enqueues. */
    private static final class InlineGateway implements OntologyAccess.EdtGateway {
        final AtomicInteger invokeLaterCalls = new AtomicInteger();

        @Override
        public boolean isDispatchThread() {
            return true;
        }

        @Override
        public void invokeLater(Runnable task) {
            invokeLaterCalls.incrementAndGet();
            task.run();
        }
    }

    /** Gateway reporting "off the EDT"; runs the task on a fresh daemon thread. */
    private static final class DaemonThreadGateway implements OntologyAccess.EdtGateway {
        @Override
        public boolean isDispatchThread() {
            return false;
        }

        @Override
        public void invokeLater(Runnable task) {
            Thread t = new Thread(task, "test-edt");
            t.setDaemon(true);
            t.start();
        }
    }

    /** Gateway reporting "off the EDT" that never runs the enqueued task (simulates a stuck EDT). */
    private static final class NeverRunGateway implements OntologyAccess.EdtGateway {
        @Override
        public boolean isDispatchThread() {
            return false;
        }

        @Override
        public void invokeLater(Runnable task) {
            // Intentionally dropped: the EDT is "busy" and never dispatches the task.
        }
    }

    /** Gateway reporting "off the EDT" that captures the task without running it. */
    private static final class CapturingGateway implements OntologyAccess.EdtGateway {
        volatile Runnable captured;

        @Override
        public boolean isDispatchThread() {
            return false;
        }

        @Override
        public void invokeLater(Runnable task) {
            captured = task;
        }
    }

    // ------------------------------------------------------------------ fast path (on EDT)

    @Test
    void fastPathRunsInlineOnCallerThreadAndReturnsValue() throws Exception {
        InlineGateway gw = new InlineGateway();
        OntologyAccess access = new OntologyAccess(bareKit(), 30_000L, gw);
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> ranOn = new AtomicReference<>();

        Function<OWLModelManager, String> fn = mm -> {
            ranOn.set(Thread.currentThread());
            return "ok";
        };
        String result = access.compute(fn);

        assertEquals("ok", result, "fast path returns fn's value");
        assertSame(caller, ranOn.get(), "fn ran inline on the caller thread");
        assertEquals(0, gw.invokeLaterCalls.get(), "invokeLater is never called on the fast path");
    }

    @Test
    void fastPathHonoursExplicitBoundWithoutEnqueuing() throws Exception {
        InlineGateway gw = new InlineGateway();
        OntologyAccess access = new OntologyAccess(bareKit(), 30_000L, gw);

        Integer result = access.compute(mm -> 42, 5L);

        assertEquals(Integer.valueOf(42), result, "explicit-bound compute still runs inline on EDT");
        assertEquals(0, gw.invokeLaterCalls.get(), "no enqueue on the fast path even with a bound");
    }

    // ------------------------------------------------------------------ async path (off EDT)

    @Test
    void asyncPathRunsTaskOnGatewayThreadAndReturnsValue() throws Exception {
        DaemonThreadGateway gw = new DaemonThreadGateway();
        OntologyAccess access = new OntologyAccess(bareKit(), 30_000L, gw);
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> ranOn = new AtomicReference<>();

        String result = access.compute(mm -> {
            ranOn.set(Thread.currentThread());
            return "async";
        });

        assertEquals("async", result, "async path returns fn's value after the latch counts down");
        assertFalse(caller == ranOn.get(),
                "fn ran on the gateway-supplied thread, not the caller thread");
    }

    @Test
    void asyncPathIgnoresModelManagerNull() throws Exception {
        // getModelManager() is null on a bare kit; a fn that ignores it must still work off-EDT.
        DaemonThreadGateway gw = new DaemonThreadGateway();
        OntologyAccess access = new OntologyAccess(bareKit(), 30_000L, gw);

        assertNull(access.getEditorKit().getModelManager(),
                "bare kit's model manager is null");
        AtomicReference<OWLModelManager> seen = new AtomicReference<>();
        String result = access.compute(mm -> {
            seen.set(mm);
            return "done";
        });

        assertEquals("done", result, "compute completes with a null model manager");
        assertNull(seen.get(), "bare kit yields a null model manager passed through to fn");
    }

    // ------------------------------------------------------------------ run(Consumer)

    @Test
    void runExecutesSideEffectAndReturnsVoid() throws Exception {
        InlineGateway gw = new InlineGateway();
        OntologyAccess access = new OntologyAccess(bareKit(), 30_000L, gw);
        AtomicBoolean ran = new AtomicBoolean(false);

        access.run(mm -> ran.set(true));

        assertTrue(ran.get(), "run executes the consumer's side effect");
    }

    @Test
    void runOffEdtExecutesConsumerOnGatewayThread() throws Exception {
        DaemonThreadGateway gw = new DaemonThreadGateway();
        OntologyAccess access = new OntologyAccess(bareKit(), 30_000L, gw);
        AtomicReference<Thread> ranOn = new AtomicReference<>();

        access.run(mm -> ranOn.set(Thread.currentThread()));

        Thread ran = ranOn.get();
        // Prove the consumer actually executed before comparing threads — otherwise a broken async
        // seam (consumer never runs, ranOn stays null) would still pass `currentThread() != null`.
        assertNotNull(ran, "run executed the consumer");
        assertNotSame(Thread.currentThread(), ran,
                "run marshals the consumer onto the gateway thread when off the EDT");
    }

    // ------------------------------------------------------------------ exception propagation

    @Test
    void runtimeExceptionFromFnPropagatesSameInstanceInline() throws Exception {
        InlineGateway gw = new InlineGateway();
        OntologyAccess access = new OntologyAccess(bareKit(), 30_000L, gw);
        RuntimeException boom = new IllegalStateException("kaboom");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> access.compute(mm -> {
                    throw boom;
                }));

        assertSame(boom, thrown, "the exact RuntimeException from fn propagates on the fast path");
    }

    @Test
    void runtimeExceptionFromFnPropagatesSameInstanceAsync() throws Exception {
        DaemonThreadGateway gw = new DaemonThreadGateway();
        OntologyAccess access = new OntologyAccess(bareKit(), 30_000L, gw);
        RuntimeException boom = new IllegalArgumentException("async boom");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> access.compute(mm -> {
                    throw boom;
                }));

        assertSame(boom, thrown, "async path re-throws the exact captured RuntimeException");
    }

    @Test
    void nonRuntimeThrowableFromFnAsyncSurfacesAsMcpAccessException() throws Exception {
        DaemonThreadGateway gw = new DaemonThreadGateway();
        OntologyAccess access = new OntologyAccess(bareKit(), 30_000L, gw);
        Error err = new AssertionError("hard failure");

        McpAccessException thrown = assertThrows(McpAccessException.class,
                () -> access.compute(mm -> {
                    throw sneak(err); // throw the Error from inside the lambda
                }));

        assertSame(err, thrown.getCause(),
                "a non-RuntimeException Throwable is wrapped as McpAccessException with it as cause");
    }

    /** Rethrow a checked/Error throwable from a lambda body without declaring it. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneak(Throwable t) throws T {
        throw (T) t;
    }

    // ------------------------------------------------------------------ timeout

    @Test
    void timeoutWhenTaskNeverRunsThrowsMcpAccessExceptionWithBound() throws Exception {
        NeverRunGateway gw = new NeverRunGateway();
        OntologyAccess access = new OntologyAccess(bareKit(), 30_000L, gw);

        McpAccessException thrown = assertThrows(McpAccessException.class,
                () -> access.compute(mm -> "unreached", 25L));

        String msg = thrown.getMessage();
        assertTrue(msg.contains("Timed out"), "message reports a timeout: " + msg);
        assertTrue(msg.contains("25"), "message carries the wait bound (25 ms): " + msg);
    }

    @Test
    void defaultBoundIsUsedByComputeWhenNoBoundGiven() throws Exception {
        // A tiny default timeout + a never-running gateway must time out via the no-bound overload.
        NeverRunGateway gw = new NeverRunGateway();
        OntologyAccess access = new OntologyAccess(bareKit(), 15L, gw);

        McpAccessException thrown = assertThrows(McpAccessException.class,
                () -> access.compute(mm -> "unreached"));

        assertTrue(thrown.getMessage().contains("15"),
                "compute(fn) uses the constructor's default bound in the timeout message: "
                        + thrown.getMessage());
    }

    // ------------------------------------------------------------------ cancellation

    @Test
    void capturedRunnableAfterTimeoutIsNoOpAndDoesNotAffectFreshCall() throws Exception {
        CapturingGateway gw = new CapturingGateway();
        OntologyAccess access = new OntologyAccess(bareKit(), 30_000L, gw);
        AtomicInteger fnRuns = new AtomicInteger();

        // First call: enqueues but the gateway never runs it -> times out with the cancelled flag set.
        assertThrows(McpAccessException.class,
                () -> access.compute(mm -> {
                    fnRuns.incrementAndGet();
                    return "late";
                }, 20L));
        assertEquals(0, fnRuns.get(), "the body did not run while the caller was waiting");

        Runnable stale = gw.captured;
        assertTrue(stale != null, "the gateway captured the enqueued task");

        // Running the stale task after cancellation must be a no-op: the cancelled flag guards the body.
        stale.run();
        assertEquals(0, fnRuns.get(),
                "post-timeout the cancelled runnable must NOT execute fn (no model mutation)");

        // A fresh call is unaffected by the stale, cancelled task.
        gw.captured = null;
        AtomicInteger secondRuns = new AtomicInteger();
        assertThrows(McpAccessException.class,
                () -> access.compute(mm -> {
                    secondRuns.incrementAndGet();
                    return "again";
                }, 20L));
        Runnable freshTask = gw.captured;
        assertTrue(freshTask != null, "the fresh call enqueued its own task");
        assertFalse(freshTask == stale, "the fresh call uses a new runnable, not the stale one");
        assertEquals(0, secondRuns.get(), "the fresh call's body also did not run (it too timed out)");
    }

    // ------------------------------------------------------------------ interrupt

    @Test
    void interruptWhileWaitingSurfacesAsMcpAccessExceptionAndRestoresFlag() throws Exception {
        // A gateway that never runs the task; interrupt this waiting thread so latch.await throws
        // InterruptedException deterministically. The gateway self-interrupts on enqueue so the wait
        // is already interrupted before await() is reached.
        OntologyAccess.EdtGateway selfInterrupting = new OntologyAccess.EdtGateway() {
            @Override
            public boolean isDispatchThread() {
                return false;
            }

            @Override
            public void invokeLater(Runnable task) {
                // Interrupt the caller (which is about to call latch.await) and never run the task.
                Thread.currentThread().interrupt();
            }
        };
        OntologyAccess access = new OntologyAccess(bareKit(), 30_000L, selfInterrupting);

        McpAccessException thrown = assertThrows(McpAccessException.class,
                () -> access.compute(mm -> "unreached", 30_000L));

        assertTrue(thrown.getMessage().contains("Interrupted"),
                "an interrupted wait surfaces as an 'Interrupted' McpAccessException: "
                        + thrown.getMessage());
        assertTrue(Thread.interrupted(),
                "the interrupt flag is re-asserted before throwing (and cleared here for isolation)");
    }
}
