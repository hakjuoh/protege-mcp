package io.github.hakjuoh.protege_mcp.server;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

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
            OWLEditorKit kit = (OWLEditorKit) allocate(OWLEditorKit.class);
            setField(kit, OWLEditorKit.class, "modelManager", mm);
            return new OntologyAccess(kit, 30_000L, InlineGateway.INSTANCE);
        } catch (Exception e) {
            throw new IllegalStateException("could not build a headless OntologyAccess", e);
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
