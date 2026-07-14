package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerConfiguration;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.reasoner.ReasonerInterruptedException;
import org.semanticweb.owlapi.reasoner.SimpleConfiguration;

/**
 * Containment tests for {@link ReasonerTools.TrackingReasonerFactory} — the get_explanations
 * timeout cleanup. The clarkparsia black-box search creates a FRESH reasoner per satisfiability
 * probe and never checks the thread's interrupt flag, so interrupting the already-created
 * instances alone would leave an abandoned (timed-out) search grinding on new reasoners
 * indefinitely on its daemon thread. These tests pin the whole containment contract on stub
 * reasoners/factories: {@code cancel()} interrupts every tracked instance and latches the factory
 * shut (creation fails fast with {@link ReasonerInterruptedException}, which nothing in the
 * generators catches); a create that raced past the latch is interrupted, disposed, and dropped
 * rather than tracked; a probe loop shaped like the real runaway dies promptly; and none of this
 * disturbs the success path (delegate-create, track, disposeAll).
 */
class TrackingReasonerFactoryTest {

    /** A recording {@link OWLReasoner} stub: counts interrupt()/dispose(), fails loudly otherwise. */
    private static final class StubReasoner {
        final AtomicInteger interrupts = new AtomicInteger();
        final AtomicInteger disposes = new AtomicInteger();
        final OWLReasoner reasoner;

        StubReasoner(boolean interruptThrows) {
            reasoner = (OWLReasoner) Proxy.newProxyInstance(
                    StubReasoner.class.getClassLoader(),
                    new Class<?>[] { OWLReasoner.class },
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "interrupt":
                                interrupts.incrementAndGet();
                                if (interruptThrows) {
                                    throw new UnsupportedOperationException(
                                            "this reasoner cannot be interrupted");
                                }
                                return null;
                            case "dispose":
                                disposes.incrementAndGet();
                                return null;
                            case "toString":
                                return "StubReasoner";
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == args[0];
                            default:
                                throw new UnsupportedOperationException(
                                        "StubReasoner does not implement " + method.getName());
                        }
                    });
        }
    }

    /**
     * A stub delegate factory: every create overload mints a fresh recording reasoner and logs it,
     * so tests can assert exactly which instances the delegate ever produced. {@code onCreate}
     * runs between the delegate's creation and the tracking factory's registration — the precise
     * window where a create races {@code cancel()}.
     */
    private static final class StubFactory implements OWLReasonerFactory {
        final List<StubReasoner> created = Collections.synchronizedList(new ArrayList<>());
        volatile Runnable onCreate;
        volatile boolean interruptThrows;

        @Override
        public String getReasonerName() {
            return "Stub";
        }

        @Override
        public OWLReasoner createReasoner(OWLOntology ontology) {
            return mint();
        }

        @Override
        public OWLReasoner createNonBufferingReasoner(OWLOntology ontology) {
            return mint();
        }

        @Override
        public OWLReasoner createReasoner(OWLOntology ontology,
                OWLReasonerConfiguration configuration) {
            return mint();
        }

        @Override
        public OWLReasoner createNonBufferingReasoner(OWLOntology ontology,
                OWLReasonerConfiguration configuration) {
            return mint();
        }

        private OWLReasoner mint() {
            StubReasoner stub = new StubReasoner(interruptThrows);
            created.add(stub);
            if (onCreate != null) {
                onCreate.run();
            }
            return stub.reasoner;
        }
    }

    private static OWLOntology emptyOntology() {
        try {
            return OwlManagers.create().createOntology();
        } catch (OWLOntologyCreationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------------- success path

    @Test
    void successPathDelegatesTracksAndDisposesOncePerInstance() {
        StubFactory delegate = new StubFactory();
        ReasonerTools.TrackingReasonerFactory tracking =
                new ReasonerTools.TrackingReasonerFactory(delegate);
        OWLOntology o = emptyOntology();

        OWLReasoner r1 = tracking.createReasoner(o);
        OWLReasoner r2 = tracking.createNonBufferingReasoner(o);
        OWLReasoner r3 = tracking.createReasoner(o, new SimpleConfiguration());
        OWLReasoner r4 = tracking.createNonBufferingReasoner(o, new SimpleConfiguration());

        assertEquals("Stub", tracking.getReasonerName(), "name passes straight through");
        assertEquals(4, delegate.created.size(), "each overload consulted the delegate once");
        assertSame(delegate.created.get(0).reasoner, r1, "the delegate's instance is returned as-is");
        assertSame(delegate.created.get(1).reasoner, r2);
        assertSame(delegate.created.get(2).reasoner, r3);
        assertSame(delegate.created.get(3).reasoner, r4);
        for (StubReasoner stub : delegate.created) {
            assertEquals(0, stub.interrupts.get(), "no interrupt on the success path");
            assertEquals(0, stub.disposes.get(), "no dispose before the worker's finally");
        }

        tracking.disposeAll();
        for (StubReasoner stub : delegate.created) {
            assertEquals(1, stub.disposes.get(), "disposeAll disposes each tracked instance once");
        }
    }

    // ------------------------------------------------------------------ cancel: interrupt sweep

    @Test
    void cancelInterruptsEveryTrackedReasonerButLeavesDisposalToTheWorker() {
        StubFactory delegate = new StubFactory();
        ReasonerTools.TrackingReasonerFactory tracking =
                new ReasonerTools.TrackingReasonerFactory(delegate);
        OWLOntology o = emptyOntology();
        tracking.createReasoner(o);
        tracking.createNonBufferingReasoner(o);
        tracking.createNonBufferingReasoner(o);

        tracking.cancel();

        for (StubReasoner stub : delegate.created) {
            assertEquals(1, stub.interrupts.get(), "every tracked instance is interrupted once");
            assertEquals(0, stub.disposes.get(),
                    "cancel must not dispose — disposal stays with the worker's finally");
        }
        tracking.disposeAll();
        for (StubReasoner stub : delegate.created) {
            assertEquals(1, stub.disposes.get(), "the worker's disposeAll still cleans up");
        }
    }

    // -------------------------------------------------------------------- cancel: creation latch

    @Test
    void everyCreateOverloadFailsFastAfterCancelWithoutConsultingTheDelegate() {
        StubFactory delegate = new StubFactory();
        ReasonerTools.TrackingReasonerFactory tracking =
                new ReasonerTools.TrackingReasonerFactory(delegate);
        OWLOntology o = emptyOntology();

        tracking.cancel();

        assertThrows(ReasonerInterruptedException.class, () -> tracking.createReasoner(o));
        assertThrows(ReasonerInterruptedException.class,
                () -> tracking.createNonBufferingReasoner(o));
        assertThrows(ReasonerInterruptedException.class,
                () -> tracking.createReasoner(o, new SimpleConfiguration()));
        assertThrows(ReasonerInterruptedException.class,
                () -> tracking.createNonBufferingReasoner(o, new SimpleConfiguration()));
        assertTrue(delegate.created.isEmpty(),
                "post-cancel creation must fail BEFORE the delegate mints anything — the "
                        + "abandoned search may not keep burning CPU on fresh reasoners");
    }

    @Test
    void cancelIsIdempotentAndTheLatchStaysShut() {
        StubFactory delegate = new StubFactory();
        ReasonerTools.TrackingReasonerFactory tracking =
                new ReasonerTools.TrackingReasonerFactory(delegate);
        OWLOntology o = emptyOntology();
        tracking.createReasoner(o);
        StubReasoner tracked = delegate.created.get(0);

        tracking.cancel();
        tracking.cancel();

        assertEquals(2, tracked.interrupts.get(), "each cancel sweeps (harmless re-interrupt)");
        assertThrows(ReasonerInterruptedException.class, () -> tracking.createReasoner(o),
                "the latch never reopens");
    }

    // --------------------------------------------------------------------- cancel/create race

    @Test
    void createRacingCancelIsInterruptedDisposedAndDroppedNotReturned() {
        StubFactory delegate = new StubFactory();
        ReasonerTools.TrackingReasonerFactory tracking =
                new ReasonerTools.TrackingReasonerFactory(delegate);
        OWLOntology o = emptyOntology();
        // cancel() fires INSIDE the delegate's create — after the fail-fast check passed, before
        // the tracking factory registers the instance — the exact window the interrupt sweep
        // cannot see.
        delegate.onCreate = tracking::cancel;

        assertThrows(ReasonerInterruptedException.class,
                () -> tracking.createNonBufferingReasoner(o),
                "the raced instance must not be handed to the abandoned search");

        assertEquals(1, delegate.created.size(), "exactly the one raced creation happened");
        StubReasoner straggler = delegate.created.get(0);
        assertEquals(1, straggler.interrupts.get(), "the straggler is interrupted on containment");
        assertEquals(1, straggler.disposes.get(), "the straggler is disposed on containment");

        // The straggler was DROPPED from tracking, not accumulated: the worker's disposeAll must
        // not find (and re-dispose) it.
        tracking.disposeAll();
        assertEquals(1, straggler.disposes.get(), "dropped instances are not disposed again");
    }

    // ------------------------------------------------------------- cancel: hostile reasoners

    @Test
    void nonInterruptibleReasonerDoesNotBreakTheSweepOrTheLatch() {
        StubFactory delegate = new StubFactory();
        ReasonerTools.TrackingReasonerFactory tracking =
                new ReasonerTools.TrackingReasonerFactory(delegate);
        OWLOntology o = emptyOntology();
        delegate.interruptThrows = true;
        tracking.createReasoner(o);
        delegate.interruptThrows = false;
        tracking.createReasoner(o);

        tracking.cancel();

        for (StubReasoner stub : delegate.created) {
            assertEquals(1, stub.interrupts.get(),
                    "one throwing interrupt must not skip the other instances");
        }
        assertThrows(ReasonerInterruptedException.class, () -> tracking.createReasoner(o),
                "the latch shuts even when a reasoner rejects interrupt()");
    }

    // ------------------------------------------------------------- the runaway probe loop shape

    @Test
    void abandonedProbeLoopDiesPromptlyOnceCancelled() throws Exception {
        StubFactory delegate = new StubFactory();
        ReasonerTools.TrackingReasonerFactory tracking =
                new ReasonerTools.TrackingReasonerFactory(delegate);
        OWLOntology o = emptyOntology();
        CountDownLatch probing = new CountDownLatch(1);
        AtomicReference<Throwable> death = new AtomicReference<>();
        // The runaway shape of the black-box search after a timeout: a fresh reasoner per
        // satisfiability probe, no Thread.interrupted() check anywhere — only the factory latch
        // can stop it.
        Thread worker = new Thread(() -> {
            try {
                while (true) {
                    OWLReasoner r = tracking.createNonBufferingReasoner(o);
                    probing.countDown();
                    r.dispose();
                }
            } catch (Throwable t) {
                death.set(t);
                probing.countDown();
            }
        }, "runaway-probe-loop");
        worker.setDaemon(true);
        worker.start();
        assertTrue(probing.await(5, TimeUnit.SECONDS), "the loop must be probing before cancel");

        tracking.cancel();
        worker.join(5_000);

        assertFalse(worker.isAlive(), "the abandoned loop must die promptly once cancelled");
        assertInstanceOf(ReasonerInterruptedException.class, death.get(),
                "the loop dies by the factory's fail-fast, not by luck: " + death.get());
        int mintedAtDeath = delegate.created.size();
        assertThrows(ReasonerInterruptedException.class,
                () -> tracking.createNonBufferingReasoner(o));
        assertEquals(mintedAtDeath, delegate.created.size(),
                "after the loop's death nothing can mint another reasoner");
    }
}
