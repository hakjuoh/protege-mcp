package io.github.hakjuoh.protege_mcp.server;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import org.protege.editor.owl.OWLEditorKit;
import org.protege.editor.owl.model.OWLModelManager;

/**
 * A headless {@link OntologyAccess} whose {@code compute}/{@code run} route straight to a supplied
 * {@link OWLModelManager} (typically a {@code FakeModelManager}) on the calling thread — no live
 * Protégé, no OSGi, no Swing event-dispatch thread.
 *
 * <p>This is the reusable version of the plumbing {@code OntologyAccessSeamTest} pioneered: an
 * {@link OWLEditorKit} is created via {@code sun.misc.Unsafe.allocateInstance} (its constructor spins
 * up real Protégé collaborators), and its {@code private final modelManager} field is set — also via
 * {@code Unsafe}, so the {@code final} modifier is a non-issue — to the test double. The
 * package-private {@link OntologyAccess.EdtGateway} is stubbed to report "already on the EDT" so
 * {@code compute} runs the body inline and deterministically. Living in {@code io.github.hakjuoh.protege_mcp.server}
 * is what grants access to that package-private gateway and constructor.
 *
 * <p>Lets a tool handler that routes through {@code ctx.access().compute(...)} — e.g.
 * {@code run_competency_questions}, {@code verify_ontology} — be driven end-to-end in a unit test.
 */
public final class HeadlessAccess {

    private HeadlessAccess() {
    }

    /** An {@link OntologyAccess} whose every {@code compute(fn)} runs {@code fn} against {@code mm} inline. */
    public static OntologyAccess over(OWLModelManager mm) {
        try {
            return new OntologyAccess(kitOver(mm), 30_000L, InlineGateway.INSTANCE);
        } catch (Exception e) {
            throw new IllegalStateException("could not build a headless OntologyAccess", e);
        }
    }

    /**
     * Like {@link #over}, but every dispatch from {@code firstStalledDispatch} (1-based) onward
     * starts its body only after {@code stallMillis} on a background thread, while the access's
     * DEFAULT wait bound is {@code defaultBoundMillis} (choose it smaller than the stall). A
     * multi-hop tool driven through this access therefore succeeds only where it hands
     * {@code compute(fn, bound)} an explicit bound larger than the stall — a call site that
     * regresses to the default-bound {@code compute(fn)} expires first, its queued body is then
     * skipped by {@link OntologyAccess}'s cancellation flag, and the tool call fails.
     * {@code dispatchCounter} receives the running dispatch count so the test can pin the expected
     * hop structure.
     */
    public static OntologyAccess overStalledDispatches(OWLModelManager mm, long defaultBoundMillis,
            long stallMillis, int firstStalledDispatch, AtomicInteger dispatchCounter) {
        try {
            return new OntologyAccess(kitOver(mm), defaultBoundMillis,
                    new StalledGateway(stallMillis, firstStalledDispatch, dispatchCounter));
        } catch (Exception e) {
            throw new IllegalStateException("could not build a headless OntologyAccess", e);
        }
    }

    /**
     * Like {@link #over}, but {@code beforeDispatch} runs immediately before the body of every
     * dispatch from {@code firstHookedDispatch} (1-based) onward — the seam a test needs to mutate
     * an on-disk input BETWEEN a multi-hop tool's model hops (e.g. to pin the release gate's
     * snapshot-drift detection). {@code dispatchCounter} receives the running dispatch count so the
     * test can pin the expected hop structure.
     */
    public static OntologyAccess overHookedDispatches(OWLModelManager mm, Runnable beforeDispatch,
            int firstHookedDispatch, AtomicInteger dispatchCounter) {
        try {
            return new OntologyAccess(kitOver(mm), 30_000L,
                    new HookedGateway(beforeDispatch, firstHookedDispatch, dispatchCounter));
        } catch (Exception e) {
            throw new IllegalStateException("could not build a headless OntologyAccess", e);
        }
    }

    private static OWLEditorKit kitOver(OWLModelManager mm) throws Exception {
        OWLEditorKit kit = (OWLEditorKit) allocate(OWLEditorKit.class);
        setField(kit, OWLEditorKit.class, "modelManager", mm);
        return kit;
    }

    /** Gateway that runs early dispatches synchronously and delays later bodies past the stall. */
    private static final class StalledGateway implements OntologyAccess.EdtGateway {
        private final long stallMillis;
        private final int firstStalled;
        private final AtomicInteger dispatches;

        StalledGateway(long stallMillis, int firstStalled, AtomicInteger dispatches) {
            this.stallMillis = stallMillis;
            this.firstStalled = firstStalled;
            this.dispatches = dispatches;
        }

        @Override
        public boolean isDispatchThread() {
            return false;
        }

        @Override
        public void invokeLater(Runnable task) {
            if (dispatches.incrementAndGet() < firstStalled) {
                task.run();
                return;
            }
            Thread worker = new Thread(() -> {
                try {
                    Thread.sleep(stallMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                task.run();
            }, "headless-stalled-dispatch");
            worker.setDaemon(true);
            worker.start();
        }
    }

    /** Gateway that runs every dispatch body inline, invoking the hook from the Nth dispatch on. */
    private static final class HookedGateway implements OntologyAccess.EdtGateway {
        private final Runnable beforeDispatch;
        private final int firstHooked;
        private final AtomicInteger dispatches;

        HookedGateway(Runnable beforeDispatch, int firstHooked, AtomicInteger dispatches) {
            this.beforeDispatch = beforeDispatch;
            this.firstHooked = firstHooked;
            this.dispatches = dispatches;
        }

        @Override
        public boolean isDispatchThread() {
            return false;
        }

        @Override
        public void invokeLater(Runnable task) {
            if (dispatches.incrementAndGet() >= firstHooked) {
                beforeDispatch.run();
            }
            task.run();
        }
    }

    /** Gateway that reports "on the EDT" so {@link OntologyAccess#compute} runs the body inline. */
    private enum InlineGateway implements OntologyAccess.EdtGateway {
        INSTANCE;

        @Override
        public boolean isDispatchThread() {
            return true;
        }

        @Override
        public void invokeLater(Runnable task) {
            task.run();
        }
    }

    // ------------------------------------------------------------------ Unsafe plumbing

    private static Object unsafe() throws Exception {
        Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return f.get(null);
    }

    private static Object allocate(Class<?> type) throws Exception {
        Object unsafe = unsafe();
        Method allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
        return allocate.invoke(unsafe, type);
    }

    /** Set an (even {@code final}) instance field via {@code Unsafe.objectFieldOffset}/{@code putObject}. */
    private static void setField(Object target, Class<?> owner, String name, Object value)
            throws Exception {
        Object unsafe = unsafe();
        Field field = owner.getDeclaredField(name);
        Method offset = unsafe.getClass().getMethod("objectFieldOffset", Field.class);
        long fieldOffset = (long) offset.invoke(unsafe, field);
        Method putObject = unsafe.getClass().getMethod("putObject", Object.class, long.class, Object.class);
        putObject.invoke(unsafe, target, fieldOffset, value);
    }
}
