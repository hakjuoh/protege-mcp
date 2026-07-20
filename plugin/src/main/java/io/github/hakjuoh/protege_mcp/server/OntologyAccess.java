package io.github.hakjuoh.protege_mcp.server;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.swing.SwingUtilities;

import org.protege.editor.owl.OWLEditorKit;
import org.protege.editor.owl.model.OWLModelManager;

/**
 * The single choke point through which <em>every</em> MCP read/write touches the Protégé model.
 *
 * <p>OWL API objects and Protégé's caches/renderers/listeners are not thread safe and assume Swing
 * EDT access, so MCP edits must be serialised with manual UI edits by running on the EDT. MCP
 * handlers run on HTTP/transport threads ({@code immediateExecution(true)}), so this class marshals
 * the work onto the EDT with a bounded wait. The {@link SwingUtilities#isEventDispatchThread()}
 * guard runs the body inline when already on the EDT, avoiding an {@code invokeAndWait} self
 * deadlock. A stuck EDT surfaces as an {@link McpAccessException} (→ MCP error) instead of hanging.
 */
public final class OntologyAccess {

    private static final int QUEUED = 0;
    private static final int RUNNING = 1;
    private static final int FINISHED = 2;
    private static final int CANCELLED = 3;

    private final OWLEditorKit editorKit;
    private final long timeoutMillis;
    private final EdtGateway edt;

    public OntologyAccess(OWLEditorKit editorKit) {
        this(editorKit, 30_000L);
    }

    public OntologyAccess(OWLEditorKit editorKit, long timeoutMillis) {
        this(editorKit, timeoutMillis, SwingEdtGateway.INSTANCE);
    }

    OntologyAccess(OWLEditorKit editorKit, long timeoutMillis, EdtGateway edt) {
        this.editorKit = editorKit;
        this.timeoutMillis = timeoutMillis;
        this.edt = edt;
    }

    public OWLEditorKit getEditorKit() {
        return editorKit;
    }

    /**
     * Run {@code fn} against the live {@link OWLModelManager} on the EDT and return its result.
     * Runtime exceptions thrown by {@code fn} propagate to the caller; an unresponsive EDT throws
     * {@link McpAccessException}.
     */
    public <T> T compute(Function<OWLModelManager, T> fn) {
        return compute(fn, timeoutMillis);
    }

    /**
     * Like {@link #compute(Function)} but with an explicit wait bound. A few tools do legitimately
     * heavy on-EDT work in one shot (e.g. merging a whole document's axioms) and need a longer bound
     * than the default so a slow-but-succeeding apply is not reported as a timeout.
     */
    public <T> T compute(Function<OWLModelManager, T> fn, long boundMillis) {
        OWLModelManager mm = editorKit.getModelManager();
        if (edt.isDispatchThread()) {
            return fn.apply(mm);
        }
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        AtomicInteger state = new AtomicInteger(QUEUED);
        CountDownLatch latch = new CountDownLatch(1);
        edt.invokeLater(() -> {
            // Claim the task atomically. If the caller already gave up (timeout/interruption below),
            // do NOT run the body — this prevents a queued task from mutating the model after the
            // client was told the call failed. A boolean check/set pair is insufficient here: timeout
            // can race between the check and fn.apply(), leaving both sides believing they won.
            if (!state.compareAndSet(QUEUED, RUNNING)) {
                latch.countDown();
                return;
            }
            try {
                result.set(fn.apply(mm));
            } catch (RuntimeException e) {
                failure.set(e);
            } catch (Throwable t) {
                failure.set(new McpAccessException(t));
            } finally {
                state.set(FINISHED);
                latch.countDown();
            }
        });
        try {
            if (!latch.await(boundMillis, TimeUnit.MILLISECONDS)) {
                boolean prevented = state.compareAndSet(QUEUED, CANCELLED);
                throw abandoned("Timed out after " + boundMillis + " ms waiting for the Protégé UI "
                        + "thread (the application may be busy).", prevented, failure.get());
            }
        } catch (InterruptedException e) {
            boolean prevented = state.compareAndSet(QUEUED, CANCELLED);
            Thread.currentThread().interrupt();
            throw abandoned("Interrupted while waiting for the Protégé UI thread.",
                    prevented, failure.get());
        }
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    private static McpAccessException abandoned(String message, boolean prevented,
            RuntimeException completedFailure) {
        if (prevented) return new McpAccessException(message);
        String warning = message + " The model-thread body had already started (or finished) and may "
                + "still complete; do not assume its effects were prevented.";
        return completedFailure == null
                ? new McpAccessException(warning)
                : new McpAccessException(warning, completedFailure);
    }

    /** Convenience for side-effecting work that has no return value. */
    public void run(Consumer<OWLModelManager> fn) {
        compute(mm -> {
            fn.accept(mm);
            return null;
        });
    }

    /**
     * Seam over the Swing event-dispatch thread so {@link #compute}'s marshalling, timeout and
     * cancellation logic can be driven deterministically in a headless test. The production instance
     * delegates straight to {@link SwingUtilities}.
     */
    interface EdtGateway {
        boolean isDispatchThread();

        void invokeLater(Runnable task);
    }

    private enum SwingEdtGateway implements EdtGateway {
        INSTANCE;

        @Override
        public boolean isDispatchThread() {
            return SwingUtilities.isEventDispatchThread();
        }

        @Override
        public void invokeLater(Runnable task) {
            SwingUtilities.invokeLater(task);
        }
    }
}
