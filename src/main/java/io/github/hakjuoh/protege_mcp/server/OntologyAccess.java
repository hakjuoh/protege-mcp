package io.github.hakjuoh.protege_mcp.server;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private final OWLEditorKit editorKit;
    private final long timeoutMillis;

    public OntologyAccess(OWLEditorKit editorKit) {
        this(editorKit, 30_000L);
    }

    public OntologyAccess(OWLEditorKit editorKit, long timeoutMillis) {
        this.editorKit = editorKit;
        this.timeoutMillis = timeoutMillis;
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
        if (SwingUtilities.isEventDispatchThread()) {
            return fn.apply(mm);
        }
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            // If the caller already gave up (timeout below), do NOT run the body — this prevents a
            // queued task from mutating the model after the client was told the call failed.
            if (cancelled.get()) {
                return;
            }
            try {
                result.set(fn.apply(mm));
            } catch (RuntimeException e) {
                failure.set(e);
            } catch (Throwable t) {
                failure.set(new McpAccessException(t));
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(boundMillis, TimeUnit.MILLISECONDS)) {
                cancelled.set(true);
                throw new McpAccessException(
                        "Timed out after " + boundMillis + " ms waiting for the Protégé UI thread "
                                + "(the application may be busy).");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpAccessException("Interrupted while waiting for the Protégé UI thread.");
        }
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    /** Convenience for side-effecting work that has no return value. */
    public void run(Consumer<OWLModelManager> fn) {
        compute(mm -> {
            fn.accept(mm);
            return null;
        });
    }
}
