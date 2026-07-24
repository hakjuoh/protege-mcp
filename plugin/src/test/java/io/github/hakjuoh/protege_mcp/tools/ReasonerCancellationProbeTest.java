package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.BufferingMode;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerConfiguration;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.reasoner.SimpleConfiguration;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;

class ReasonerCancellationProbeTest {

    @Test
    void reviewedStructuralProfileProvesAndCachesBoundedCancellation() {
        IsolatedReasonerSpec spec = IsolatedReasonerSpec.capture(info(
                "org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory",
                "Structural Reasoner", new StructuralReasonerFactory()));
        ReasonerCancellationProbe probe = new ReasonerCancellationProbe();

        assertTrue(probe.proven(spec));
        assertTrue(probe.proven(spec));
    }

    @Test
    void unknownProfileIsRejectedWithoutConstructingAReasoner() {
        BlockingFactory factory = new BlockingFactory();
        IsolatedReasonerSpec spec = IsolatedReasonerSpec.capture(
                info("test.blocking.reasoner", "Blocking", factory));

        assertFalse(new ReasonerCancellationProbe().proven(spec));
        assertTrue(factory.creations.get() == 0);
    }

    @Test
    void blockingInterruptAndDisposeCannotHoldTheProbePastItsDeadline()
            throws Exception {
        BlockingFactory factory = new BlockingFactory();
        IsolatedReasonerSpec spec = IsolatedReasonerSpec.capture(
                info("test.blocking.reasoner", "Blocking", factory));
        long started = System.nanoTime();
        try {
            assertFalse(ReasonerCancellationProbe.probe(
                    spec, Duration.ofMillis(75)));
            long elapsed = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - started);
            assertTrue(elapsed < 1_000,
                    "the bounded probe took " + elapsed + " ms");
        } finally {
            factory.release.set(true);
        }
    }

    @Test
    void constructionFailureNeverCountsAsCancellationEvidence() {
        OWLReasonerFactory failing = factory(() -> {
            throw new IllegalStateException("construction failed");
        });
        IsolatedReasonerSpec spec = IsolatedReasonerSpec.capture(
                info("test.throwing.reasoner", "Throwing", failing));

        assertFalse(ReasonerCancellationProbe.probe(
                spec, Duration.ofMillis(100)));
    }

    @Test
    void reasonerConstructedAfterTimeoutIsStillInterruptedAndDisposed()
            throws Exception {
        AtomicBoolean releaseConstruction = new AtomicBoolean();
        CountDownLatch constructionReturned = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch disposed = new CountDownLatch(1);
        OWLReasoner late = reasoner((method, args) -> {
            switch (method) {
                case "interrupt" -> interrupted.countDown();
                case "dispose" -> disposed.countDown();
                default -> { }
            }
            return "isConsistent".equals(method);
        });
        OWLReasonerFactory factory = factory(() -> {
            while (!releaseConstruction.get()) Thread.yield();
            constructionReturned.countDown();
            return late;
        });
        IsolatedReasonerSpec spec = IsolatedReasonerSpec.capture(
                info("test.late.reasoner", "Late", factory));

        assertFalse(ReasonerCancellationProbe.probe(
                spec, Duration.ofMillis(50)));
        releaseConstruction.set(true);

        assertTrue(constructionReturned.await(1, TimeUnit.SECONDS));
        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        assertTrue(disposed.await(5, TimeUnit.SECONDS));
    }

    @Test
    void cancellationControllerSignalsTheExactRegisteredReasoner() {
        AtomicInteger interrupts = new AtomicInteger();
        AtomicInteger disposals = new AtomicInteger();
        OWLReasoner reasoner = reasoner((method, args) -> {
            if ("interrupt".equals(method)) interrupts.incrementAndGet();
            if ("dispose".equals(method)) disposals.incrementAndGet();
            return "isConsistent".equals(method);
        });
        ReasonerCancellationController cancellation =
                new ReasonerCancellationController();
        assertTrue(cancellation.register(reasoner));

        cancellation.requestCancellation();

        assertTrue(cancellation.awaitStopped(Duration.ofSeconds(1)));
        assertTrue(cancellation.stopCompleted());
        assertTrue(interrupts.get() == 1);
        assertTrue(disposals.get() == 1);
    }

    @Test
    void invalidProbeArgumentsFailClosed() {
        assertFalse(ReasonerCancellationProbe.probe(null, Duration.ofMillis(1)));
        assertFalse(ReasonerCancellationProbe.probe(
                IsolatedReasonerSpec.capture(info(
                        "test.reasoner", "Test", new StructuralReasonerFactory())),
                Duration.ZERO));
    }

    private static ProtegeOWLReasonerInfo info(
            String id, String name, OWLReasonerFactory factory) {
        return (ProtegeOWLReasonerInfo) Proxy.newProxyInstance(
                ReasonerCancellationProbeTest.class.getClassLoader(),
                new Class<?>[] {ProtegeOWLReasonerInfo.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getReasonerId" -> id;
                    case "getReasonerName" -> name;
                    case "getReasonerFactory" -> factory;
                    case "getRecommendedBuffering" -> BufferingMode.BUFFERING;
                    case "getConfiguration" -> new SimpleConfiguration();
                    case "toString" -> name;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static OWLReasonerFactory factory(
            java.util.function.Supplier<OWLReasoner> supplier) {
        return new OWLReasonerFactory() {
            @Override
            public String getReasonerName() {
                return "Test";
            }

            @Override
            public OWLReasoner createReasoner(OWLOntology ontology) {
                return supplier.get();
            }

            @Override
            public OWLReasoner createNonBufferingReasoner(OWLOntology ontology) {
                return supplier.get();
            }

            @Override
            public OWLReasoner createReasoner(
                    OWLOntology ontology, OWLReasonerConfiguration configuration) {
                return supplier.get();
            }

            @Override
            public OWLReasoner createNonBufferingReasoner(
                    OWLOntology ontology, OWLReasonerConfiguration configuration) {
                return supplier.get();
            }
        };
    }

    private static OWLReasoner reasoner(ReasonerCall call) {
        return (OWLReasoner) Proxy.newProxyInstance(
                ReasonerCancellationProbeTest.class.getClassLoader(),
                new Class<?>[] {OWLReasoner.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getBufferingMode" -> BufferingMode.BUFFERING;
                    case "isConsistent", "interrupt", "dispose" ->
                            call.invoke(method.getName(), args);
                    case "toString" -> "TestReasoner";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @FunctionalInterface
    private interface ReasonerCall {
        Object invoke(String method, Object[] args);
    }

    private static final class BlockingFactory implements OWLReasonerFactory {
        private final AtomicInteger creations = new AtomicInteger();
        private final AtomicBoolean release = new AtomicBoolean();

        @Override
        public String getReasonerName() {
            return "Blocking";
        }

        @Override
        public OWLReasoner createReasoner(OWLOntology ontology) {
            return create();
        }

        @Override
        public OWLReasoner createNonBufferingReasoner(OWLOntology ontology) {
            return create();
        }

        @Override
        public OWLReasoner createReasoner(
                OWLOntology ontology, OWLReasonerConfiguration configuration) {
            return create();
        }

        @Override
        public OWLReasoner createNonBufferingReasoner(
                OWLOntology ontology, OWLReasonerConfiguration configuration) {
            return create();
        }

        private OWLReasoner create() {
            creations.incrementAndGet();
            return (OWLReasoner) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {OWLReasoner.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "isConsistent", "interrupt", "dispose" -> {
                            while (!release.get()) {
                                Thread.onSpinWait();
                            }
                            yield "isConsistent".equals(method.getName());
                        }
                        case "toString" -> "BlockingReasoner";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
