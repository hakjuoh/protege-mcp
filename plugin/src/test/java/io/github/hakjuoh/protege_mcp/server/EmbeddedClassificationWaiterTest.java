package io.github.hakjuoh.protege_mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.OWLEditorKit;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.event.EventType;
import org.protege.editor.owl.model.event.OWLModelManagerChangeEvent;
import org.protege.editor.owl.model.event.OWLModelManagerListener;
import org.protege.editor.owl.model.inference.OWLReasonerManager;
import org.protege.editor.owl.model.inference.ReasonerStatus;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.reasoner.Node;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

/**
 * Method-level tests for {@link EmbeddedClassificationWaiter#runAndWait(OntologyAccess, long)}.
 *
 * <p>The waiter is a pure-logic wrapper around {@link OntologyAccess#compute(java.util.function.Function)}:
 * it registers an {@link OWLModelManagerListener}, starts classification via the
 * {@link OWLReasonerManager}, blocks on a latch that the {@code ONTOLOGY_CLASSIFIED} event counts
 * down, then re-reads status and builds a result map. Nothing here needs the live Protégé runtime
 * <em>except</em> the {@link OWLEditorKit} that {@code OntologyAccess} dereferences — its constructor
 * builds a workspace and registers OSGi services, so it is created WITHOUT running any constructor via
 * {@code sun.misc.Unsafe.allocateInstance} and its private {@code modelManager} field is wired to a
 * hand-rolled {@link Proxy} test double. The double implements only the manager/reasoner methods the
 * waiter actually calls and is fully configurable (start success/failure, whether classification
 * signals completion, reasoner status, name, unsatisfiable count, and reasoner-throws behaviour).
 *
 * <p>Determinism: a "completing" classifier fires {@code ONTOLOGY_CLASSIFIED} synchronously from
 * inside {@code classifyAsynchronously} (the listener is already registered at that point), so the
 * latch is already counted down before {@code await} runs — no wall-clock dependence. A
 * "non-completing" classifier fires nothing and is exercised with a short bounded wait so the timeout
 * branch is deterministic and fast.
 */
class EmbeddedClassificationWaiterTest {

    // ------------------------------------------------------------------ fakes / plumbing

    /** Records listener + reasoner lifecycle and drives configurable classification behaviour. */
    private static final class FakeState {
        boolean startSucceeds = true;
        boolean signalCompletion = true;
        ReasonerStatus status = ReasonerStatus.INITIALIZED;
        String reasonerName = "FaCT++";
        int unsatisfiableCount = 0;
        boolean reasonerThrows = false;

        final List<OWLModelManagerListener> listeners = new ArrayList<>();
        int computeCalls = 0;
        int classifyCalls = 0;
        Set<?> lastPrecompute;

        void fireClassified(OWLModelManager mgr) {
            OWLModelManagerChangeEvent event =
                    new OWLModelManagerChangeEvent(mgr, EventType.ONTOLOGY_CLASSIFIED);
            for (OWLModelManagerListener l : new ArrayList<>(listeners)) {
                l.handleChange(event);
            }
        }
    }

    /** A configurable OWLReasoner returning a canned unsatisfiable-classes node (or throwing). */
    private static OWLReasoner reasonerProxy(FakeState state) {
        Set<OWLClass> unsat = new LinkedHashSet<>();
        // Build placeholder OWLClass entities so getEntitiesMinusBottom().size() == unsatisfiableCount.
        for (int i = 0; i < state.unsatisfiableCount; i++) {
            unsat.add(classProxy("http://example.org/Unsat" + i));
        }
        Node<OWLClass> node = nodeProxy(unsat);
        return (OWLReasoner) Proxy.newProxyInstance(
                EmbeddedClassificationWaiterTest.class.getClassLoader(),
                new Class<?>[] { OWLReasoner.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUnsatisfiableClasses":
                            if (state.reasonerThrows) {
                                throw new IllegalStateException("reasoner cannot answer mid-state");
                            }
                            return node;
                        case "toString":
                            return "FakeReasoner";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            throw new UnsupportedOperationException(
                                    "FakeReasoner does not implement " + method.getName());
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private static Node<OWLClass> nodeProxy(Set<OWLClass> entitiesMinusBottom) {
        return (Node<OWLClass>) Proxy.newProxyInstance(
                EmbeddedClassificationWaiterTest.class.getClassLoader(),
                new Class<?>[] { Node.class },
                (proxy, method, args) -> {
                    if ("getEntitiesMinusBottom".equals(method.getName())) {
                        return entitiesMinusBottom;
                    }
                    if ("toString".equals(method.getName())) {
                        return "FakeNode";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    throw new UnsupportedOperationException(
                            "FakeNode does not implement " + method.getName());
                });
    }

    private static OWLClass classProxy(String iri) {
        return (OWLClass) Proxy.newProxyInstance(
                EmbeddedClassificationWaiterTest.class.getClassLoader(),
                new Class<?>[] { OWLClass.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "toString":
                            return iri;
                        case "hashCode":
                            return iri.hashCode();
                        case "equals":
                            return proxy == args[0];
                        default:
                            throw new UnsupportedOperationException(
                                    "FakeClass does not implement " + method.getName());
                    }
                });
    }

    private static OWLReasonerManager reasonerManagerProxy(FakeState state, OWLModelManager[] mgrHolder) {
        return (OWLReasonerManager) Proxy.newProxyInstance(
                EmbeddedClassificationWaiterTest.class.getClassLoader(),
                new Class<?>[] { OWLReasonerManager.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "classifyAsynchronously":
                            state.classifyCalls++;
                            state.lastPrecompute = (Set<?>) args[0];
                            if (state.startSucceeds && state.signalCompletion) {
                                // Listener is already registered at this point; fire synchronously so
                                // the latch is counted down deterministically before await().
                                state.fireClassified(mgrHolder[0]);
                            }
                            return state.startSucceeds;
                        case "getReasonerStatus":
                            return state.status;
                        case "getCurrentReasonerName":
                            return state.reasonerName;
                        case "toString":
                            return "FakeReasonerManager";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            throw new UnsupportedOperationException(
                                    "FakeReasonerManager does not implement " + method.getName());
                    }
                });
    }

    private static OWLModelManager managerProxy(FakeState state) {
        OWLModelManager[] holder = new OWLModelManager[1];
        OWLReasonerManager rm = reasonerManagerProxy(state, holder);
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getOWLReasonerManager":
                    return rm;
                case "getReasoner":
                    return reasonerProxy(state);
                case "addListener":
                    state.listeners.add((OWLModelManagerListener) args[0]);
                    return null;
                case "removeListener":
                    state.listeners.remove(args[0]);
                    return null;
                case "toString":
                    return "FakeModelManager";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    throw new UnsupportedOperationException(
                            "FakeModelManager does not implement " + method.getName());
            }
        };
        OWLModelManager mgr = (OWLModelManager) Proxy.newProxyInstance(
                EmbeddedClassificationWaiterTest.class.getClassLoader(),
                new Class<?>[] { OWLModelManager.class }, h);
        holder[0] = mgr;
        return mgr;
    }

    /** Allocate an instance without running its constructor (via sun.misc.Unsafe). */
    private static Object allocate(Class<?> type) throws Exception {
        Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        f.setAccessible(true);
        Object unsafe = f.get(null);
        Method allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
        return allocate.invoke(unsafe, type);
    }

    private static void setField(Object target, Class<?> declaring, String name, Object value)
            throws Exception {
        Field field = declaring.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * Build a real {@link OntologyAccess} (constructor bypassed) whose editor kit returns the given
     * fake {@link OWLModelManager}. Also stamps the private timeout field so {@code compute(fn)} works.
     */
    private static OntologyAccess accessOver(OWLModelManager mgr) throws Exception {
        OWLEditorKit kit = (OWLEditorKit) allocate(OWLEditorKit.class);
        setField(kit, OWLEditorKit.class, "modelManager", mgr);
        // Construct through the package-private EDT-gateway seam (no Unsafe needed for OntologyAccess),
        // supplying a gateway that reproduces the real Swing dispatch: on this non-EDT test thread
        // compute() marshals the work onto the real event-dispatch thread exactly as in production.
        return new OntologyAccess(kit, 30_000L, new OntologyAccess.EdtGateway() {
            @Override
            public boolean isDispatchThread() {
                return javax.swing.SwingUtilities.isEventDispatchThread();
            }

            @Override
            public void invokeLater(Runnable task) {
                javax.swing.SwingUtilities.invokeLater(task);
            }
        });
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void completesWithinTimeoutReturnsStartedCompletedResult() throws Exception {
        FakeState state = new FakeState();
        state.status = ReasonerStatus.INITIALIZED;
        state.reasonerName = "HermiT";
        state.unsatisfiableCount = 0;
        OntologyAccess access = accessOver(managerProxy(state));

        Map<String, Object> result = EmbeddedClassificationWaiter.runAndWait(access, 30_000L);

        assertEquals(Boolean.TRUE, result.get("started"), "classification started");
        assertEquals(Boolean.TRUE, result.get("completed"), "event signalled completion");
        assertEquals("HermiT", result.get("reasoner"), "reasoner name propagated");
        assertEquals("INITIALIZED", result.get("status"), "status stringified");
        assertEquals(Boolean.FALSE, result.get("inconsistent"), "INITIALIZED is not inconsistent");
        assertEquals(Integer.valueOf(0), result.get("unsatisfiable_count"),
                "zero unsatisfiable classes on a satisfiable ontology");
        String msg = (String) result.get("message");
        assertTrue(msg.contains("Reasoner: HermiT"), "message names reasoner: " + msg);
        assertTrue(msg.contains("Status: INITIALIZED"), "message names status: " + msg);
        assertTrue(msg.contains("Unsatisfiable classes: 0"), "message reports count: " + msg);
    }

    @Test
    void classificationIsStartedExactlyOnce() throws Exception {
        FakeState state = new FakeState();
        OntologyAccess access = accessOver(managerProxy(state));

        EmbeddedClassificationWaiter.runAndWait(access, 30_000L);

        assertEquals(1, state.classifyCalls,
                "classifyAsynchronously is invoked exactly once, in the first compute");
    }

    @Test
    void listenerRegisteredThenRemovedOnSuccess() throws Exception {
        FakeState state = new FakeState();
        OntologyAccess access = accessOver(managerProxy(state));

        EmbeddedClassificationWaiter.runAndWait(access, 30_000L);

        assertTrue(state.listeners.isEmpty(),
                "listener must be removed in the second compute after completion");
    }

    @Test
    void classifyReceivesAllInferenceTypes() throws Exception {
        FakeState state = new FakeState();
        OntologyAccess access = accessOver(managerProxy(state));

        EmbeddedClassificationWaiter.runAndWait(access, 30_000L);

        assertEquals(
                EnumSetAll(),
                state.lastPrecompute,
                "classifyAsynchronously receives EnumSet.allOf(InferenceType.class)");
    }

    private static Set<org.semanticweb.owlapi.reasoner.InferenceType> EnumSetAll() {
        return java.util.EnumSet.allOf(org.semanticweb.owlapi.reasoner.InferenceType.class);
    }

    // ------------------------------------------------------------------ start failure

    @Test
    void cannotStartReturnsShortFailureResult() throws Exception {
        FakeState state = new FakeState();
        state.startSucceeds = false;
        OntologyAccess access = accessOver(managerProxy(state));

        Map<String, Object> result = EmbeddedClassificationWaiter.runAndWait(access, 30_000L);

        assertEquals(Boolean.FALSE, result.get("started"), "start failed");
        assertFalse(result.containsKey("completed"), "failure result omits completed");
        assertFalse(result.containsKey("status"), "failure result omits status");
        assertFalse(result.containsKey("reasoner"), "failure result omits reasoner");
        String msg = (String) result.get("message");
        assertTrue(msg.contains("Could not start classification"), "explains why: " + msg);
    }

    @Test
    void listenerRemovedWhenStartFails() throws Exception {
        FakeState state = new FakeState();
        state.startSucceeds = false;
        OntologyAccess access = accessOver(managerProxy(state));

        EmbeddedClassificationWaiter.runAndWait(access, 30_000L);

        assertTrue(state.listeners.isEmpty(),
                "listener added then removed inside the first compute when start fails");
    }

    // ------------------------------------------------------------------ timeout

    @Test
    void timeoutBeforeCompletionReportsNotCompleted() throws Exception {
        FakeState state = new FakeState();
        state.signalCompletion = false; // start succeeds but no ONTOLOGY_CLASSIFIED is ever fired
        state.status = ReasonerStatus.INITIALIZATION_IN_PROGRESS;
        state.reasonerName = "SlowReasoner";
        OntologyAccess access = accessOver(managerProxy(state));

        Map<String, Object> result = EmbeddedClassificationWaiter.runAndWait(access, 30L);

        assertEquals(Boolean.TRUE, result.get("started"), "classification did start");
        assertEquals(Boolean.FALSE, result.get("completed"), "no completion event within bound");
        assertEquals("SlowReasoner", result.get("reasoner"), "reasoner name still reported");
        String msg = (String) result.get("message");
        assertTrue(msg.contains("did not signal completion within 30 ms"),
                "message carries the timeout bound: " + msg);
    }

    @Test
    void zeroTimeoutTimesOutImmediately() throws Exception {
        FakeState state = new FakeState();
        state.signalCompletion = false;
        OntologyAccess access = accessOver(managerProxy(state));

        Map<String, Object> result = EmbeddedClassificationWaiter.runAndWait(access, 0L);

        assertEquals(Boolean.TRUE, result.get("started"), "started");
        assertEquals(Boolean.FALSE, result.get("completed"), "zero timeout means immediate timeout");
        assertTrue(((String) result.get("message")).contains("within 0 ms"),
                "zero bound reflected in message");
    }

    @Test
    void listenerRemovedEvenAfterTimeout() throws Exception {
        FakeState state = new FakeState();
        state.signalCompletion = false;
        OntologyAccess access = accessOver(managerProxy(state));

        EmbeddedClassificationWaiter.runAndWait(access, 20L);

        assertTrue(state.listeners.isEmpty(),
                "the second compute removes the listener regardless of completion");
    }

    // ------------------------------------------------------------------ status variants

    @Test
    void inconsistentStatusFlagsInconsistentAndSkipsUnsatCount() throws Exception {
        FakeState state = new FakeState();
        state.status = ReasonerStatus.INCONSISTENT;
        state.reasonerName = "HermiT";
        state.unsatisfiableCount = 7; // must NOT be consulted on the inconsistent branch
        OntologyAccess access = accessOver(managerProxy(state));

        Map<String, Object> result = EmbeddedClassificationWaiter.runAndWait(access, 30_000L);

        assertEquals(Boolean.TRUE, result.get("inconsistent"), "INCONSISTENT flagged");
        assertFalse(result.containsKey("unsatisfiable_count"),
                "unsatisfiable_count is not computed for an inconsistent ontology");
        String msg = (String) result.get("message");
        assertTrue(msg.contains("INCONSISTENT"), "message mentions inconsistency: " + msg);
    }

    @Test
    void satisfiableWithUnsatisfiableClassesReportsCount() throws Exception {
        FakeState state = new FakeState();
        state.status = ReasonerStatus.INITIALIZED; // isEnableStop() == true
        state.unsatisfiableCount = 3;
        OntologyAccess access = accessOver(managerProxy(state));

        Map<String, Object> result = EmbeddedClassificationWaiter.runAndWait(access, 30_000L);

        assertEquals(Boolean.FALSE, result.get("inconsistent"), "not inconsistent");
        assertEquals(Integer.valueOf(3), result.get("unsatisfiable_count"), "count surfaced");
        assertTrue(((String) result.get("message")).contains("Unsatisfiable classes: 3"),
                "message reports the count");
    }

    @Test
    void statusWithoutEnableStopOmitsUnsatCount() throws Exception {
        FakeState state = new FakeState();
        // REASONER_NOT_INITIALIZED has isEnableStop() == false and is not INCONSISTENT,
        // so neither the inconsistent branch nor the unsat-count branch runs.
        state.status = ReasonerStatus.REASONER_NOT_INITIALIZED;
        state.unsatisfiableCount = 5; // must not be consulted
        OntologyAccess access = accessOver(managerProxy(state));

        Map<String, Object> result = EmbeddedClassificationWaiter.runAndWait(access, 30_000L);

        assertEquals(Boolean.FALSE, result.get("inconsistent"), "not inconsistent");
        assertFalse(result.containsKey("unsatisfiable_count"),
                "no unsat count when the status cannot be stopped");
        String msg = (String) result.get("message");
        assertFalse(msg.contains("Unsatisfiable classes"),
                "message omits the unsat line: " + msg);
        assertTrue(msg.contains("Status: REASONER_NOT_INITIALIZED"), "status still reported: " + msg);
    }

    @Test
    void completedResetToNullReasonerFlagsClassificationFailed() throws Exception {
        // A classification that signalled completion but left the reasoner UNINITIALIZED means the
        // factory threw and Protégé reset to the Null reasoner (e.g. HermiT rejecting a SWRL built-in
        // atom). runAndWait must flag it so run_reasoner reports an error instead of a benign no-op.
        FakeState state = new FakeState();
        state.signalCompletion = true;
        state.status = ReasonerStatus.REASONER_NOT_INITIALIZED;
        state.reasonerName = "Protégé Null Reasoner";
        OntologyAccess access = accessOver(managerProxy(state));

        Map<String, Object> result = EmbeddedClassificationWaiter.runAndWait(access, 30_000L);

        assertEquals(Boolean.TRUE, result.get("completed"), "the run signalled completion");
        assertEquals(Boolean.TRUE, result.get("classification_failed"),
                "a completed run that reset to the uninitialized/Null reasoner is a classification failure");
        assertTrue(((String) result.get("message")).contains("Classification FAILED"),
                "the message flags the failure: " + result.get("message"));
    }

    @Test
    void outOfSyncStatusStillReportsUnsatCount() throws Exception {
        FakeState state = new FakeState();
        state.status = ReasonerStatus.OUT_OF_SYNC; // isEnableStop() == true
        state.unsatisfiableCount = 2;
        OntologyAccess access = accessOver(managerProxy(state));

        Map<String, Object> result = EmbeddedClassificationWaiter.runAndWait(access, 30_000L);

        assertEquals(Integer.valueOf(2), result.get("unsatisfiable_count"),
                "OUT_OF_SYNC enables stop, so the count is computed");
    }

    // ------------------------------------------------------------------ reasoner throwing

    @Test
    void reasonerThrowingIsSwallowedAndCountOmitted() throws Exception {
        FakeState state = new FakeState();
        state.status = ReasonerStatus.INITIALIZED; // enableStop -> unsat branch entered
        state.reasonerThrows = true;
        state.reasonerName = "HermiT";
        OntologyAccess access = accessOver(managerProxy(state));

        Map<String, Object> result = EmbeddedClassificationWaiter.runAndWait(access, 30_000L);

        assertFalse(result.containsKey("unsatisfiable_count"),
                "a RuntimeException from getUnsatisfiableClasses is caught and the count omitted");
        String msg = (String) result.get("message");
        assertFalse(msg.contains("Unsatisfiable classes"),
                "no unsat line when the reasoner refused to answer: " + msg);
        assertTrue(msg.contains("Status: INITIALIZED"), "the status line still terminates message");
        assertEquals(Boolean.TRUE, result.get("started"), "still a started result");
    }

    // ------------------------------------------------------------------ result shape / ordering

    @Test
    void successResultKeyOrderMatchesSpec() throws Exception {
        FakeState state = new FakeState();
        state.status = ReasonerStatus.INITIALIZED;
        state.unsatisfiableCount = 1;
        OntologyAccess access = accessOver(managerProxy(state));

        Map<String, Object> result = EmbeddedClassificationWaiter.runAndWait(access, 30_000L);

        assertEquals(
                Set.of("started", "completed", "classification_failed", "reasoner", "status",
                        "inconsistent", "unsatisfiable_count", "message"),
                new java.util.HashSet<>(result.keySet()),
                "success result carries exactly these keys");
    }

    @Test
    void failureResultHasOnlyStartedAndMessage() throws Exception {
        FakeState state = new FakeState();
        state.startSucceeds = false;
        OntologyAccess access = accessOver(managerProxy(state));

        Map<String, Object> result = EmbeddedClassificationWaiter.runAndWait(access, 30_000L);

        assertEquals(List.of("started", "message"), new ArrayList<>(result.keySet()),
                "failure result carries exactly started + message");
    }

    @Test
    void longTimeoutStillReturnsImmediatelyWhenEventFires() throws Exception {
        // Long.MAX_VALUE bound must not stall when completion is signalled synchronously.
        FakeState state = new FakeState();
        state.status = ReasonerStatus.INITIALIZED;
        OntologyAccess access = accessOver(managerProxy(state));

        Map<String, Object> result = EmbeddedClassificationWaiter.runAndWait(access, Long.MAX_VALUE);

        assertEquals(Boolean.TRUE, result.get("completed"),
                "event fired, so the huge bound is never actually waited out");
    }
}
