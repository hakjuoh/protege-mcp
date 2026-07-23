package io.github.hakjuoh.protege_mcp.reasoner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.BufferingMode;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.SimpleConfiguration;
import org.semanticweb.owlapi.reasoner.impl.OWLClassNode;
import org.semanticweb.owlapi.reasoner.impl.OWLClassNodeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.contracts.ModelRevision;

/** Opt-in 0.8 materialization ceiling fixture. */
@EnabledIfSystemProperty(named = "protege.performance", matches = "true")
class MaterializationScaleTest {
    private static final String HASH =
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void fiftyThousandAxiomsStayCompleteAndInsideTheReleaseEnvelope() throws Exception {
        JsonNode budget = JSON.readTree(Files.readAllBytes(
                Path.of("performance", "materialization-v1.json")));
        assertEquals(1, budget.path("schema_version").asInt());
        int count = budget.path("produced_axioms").asInt();
        assertEquals(MaterializationRequest.MAX_AXIOMS, count);

        var manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(
                "https://example.org/materialization-scale"));
        var data = manager.getOWLDataFactory();
        OWLClass parent = data.getOWLClass(IRI.create(
                "https://example.org/materialization-scale#Parent"));
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(parent));
        for (int index = 0; index < count; index++) {
            OWLClass child = data.getOWLClass(IRI.create(
                    "https://example.org/materialization-scale#C" + index));
            manager.addAxiom(ontology, data.getOWLDeclarationAxiom(child));
        }

        var factory = new org.semanticweb.HermiT.ReasonerFactory();
        ReasonerIdentity reasonerIdentity = ReasonerIdentity.capture(
                factory.getClass().getName(), factory.getReasonerName(), factory,
                new SimpleConfiguration(), BufferingMode.BUFFERING, "performance_fixture");
        ReasonerCapabilityReport capabilities =
                new ReasonerCapabilityRegistry().report(reasonerIdentity);
        assertEquals("reviewed", capabilities.profileStatus());
        MaterializationInputIdentity identity = new MaterializationInputIdentity(
                new ModelRevision("00000000-0000-0000-0000-000000000008", 1, HASH, HASH), HASH,
                null, null, HASH, HASH, null, reasonerIdentity);
        MaterializationRequest request = new MaterializationRequest(
                List.of(MaterializationCategory.SUBCLASS_AXIOMS),
                new MaterializationRequest.Destination(
                        "new_ontology", "https://example.org/materialization-scale/output"),
                new MaterializationRequest.Provenance(
                        "protege-mcp-performance", "50k release fixture"),
                new MaterializationRequest.Limits(count, count,
                        MaterializationRequest.MAX_BYTES,
                        budget.path("maximum_elapsed_ms").asLong()));
        OWLReasoner reasoner = reasoner(ontology, parent);
        System.gc();
        MaterializationArtifact artifact;
        long elapsedMillis;
        long incrementalPeakHeap;
        long peakHeap;
        try (HeapPeakSampler heap = HeapPeakSampler.start()) {
            long started = System.nanoTime();
            artifact = new MaterializationService(
                    Clock.fixed(Instant.parse("2026-07-23T12:00:00Z"), ZoneOffset.UTC),
                    () -> "materialization-scale").preview(ontology, ignored -> reasoner,
                            capabilities, request, identity);
            elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
            peakHeap = heap.stopAndGetPeak();
            incrementalPeakHeap = peakHeap - heap.baseline();
        }
        long regressionLimit = regressionLimit(
                budget, "baseline_elapsed_ms", "maximum_elapsed_ms");
        long cancellationMillis = cancellationLatency(capabilities, reasonerIdentity,
                budget.path("maximum_cancellation_ms").asLong());
        assertEquals(count, artifact.axioms().size());
        assertEquals(count, ((Number) ((Map<?, ?>) artifact.report().get(
                "artifact")).get("axiom_count")).intValue());
        assertTrue(elapsedMillis <= regressionLimit,
                () -> "materialization elapsed " + elapsedMillis
                        + " ms; regression limit=" + regressionLimit);
        assertTrue(incrementalPeakHeap <= budget.path(
                        "maximum_incremental_heap_bytes").asLong(),
                () -> "materialization incremental peak heap "
                        + incrementalPeakHeap + " bytes");
        assertTrue(cancellationMillis <= budget.path("maximum_cancellation_ms").asLong(),
                () -> "materialization cancellation took " + cancellationMillis + " ms");
        Path result = Path.of("core", "target", "materialization-performance-results.json");
        Files.createDirectories(result.getParent());
        Files.write(result, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of(
                "schema_version", 1,
                "produced_axioms", artifact.axioms().size(),
                "elapsed_ms", elapsedMillis,
                "regression_limit_ms", regressionLimit,
                "peak_heap_bytes", peakHeap,
                "incremental_peak_heap_bytes", incrementalPeakHeap,
                "cancellation_latency_ms", cancellationMillis,
                "budget", budget)));
    }

    private static long cancellationLatency(ReasonerCapabilityReport capabilities,
            ReasonerIdentity identity, long maximumMillis) throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(
                "https://example.org/materialization-cancellation"));
        var data = manager.getOWLDataFactory();
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(data.getOWLClass(
                IRI.create("https://example.org/materialization-cancellation#C"))));
        AtomicBoolean cancelled = new AtomicBoolean();
        CountDownLatch cancellationObserved = new CountDownLatch(1);
        OWLReasoner blocking = blockingReasoner(
                ontology, cancelled, cancellationObserved);
        MaterializationRequest request = new MaterializationRequest(
                List.of(MaterializationCategory.SUBCLASS_AXIOMS),
                new MaterializationRequest.Destination("new_ontology",
                        "https://example.org/materialization-cancellation/output"),
                new MaterializationRequest.Provenance(
                        "protege-mcp-performance", "cancellation release fixture"),
                new MaterializationRequest.Limits(10, 10, 1_048_576, 25));
        MaterializationInputIdentity input = new MaterializationInputIdentity(
                new ModelRevision("00000000-0000-0000-0000-000000000009", 1,
                        HASH, HASH), HASH, null, null, HASH, HASH, null, identity);
        long started = System.nanoTime();
        MaterializationException timeout = org.junit.jupiter.api.Assertions.assertThrows(
                MaterializationException.class,
                () -> new MaterializationService(Clock.systemUTC()).preview(
                        ontology, ignored -> blocking, capabilities, request, input));
        assertEquals("materialization_timeout", timeout.code());
        assertTrue(cancellationObserved.await(maximumMillis, TimeUnit.MILLISECONDS),
                "private reasoner did not observe cancellation within the release envelope");
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static OWLReasoner blockingReasoner(OWLOntology ontology,
            AtomicBoolean cancelled, CountDownLatch cancellationObserved) {
        return (OWLReasoner) Proxy.newProxyInstance(
                MaterializationScaleTest.class.getClassLoader(),
                new Class<?>[] {OWLReasoner.class}, (proxy, method, arguments) -> switch (
                        method.getName()) {
                    case "getRootOntology" -> ontology;
                    case "isConsistent" -> true;
                    case "getSuperClasses" -> {
                        while (!cancelled.get()) LockSupport.parkNanos(1_000_000L);
                        yield new OWLClassNodeSet();
                    }
                    case "interrupt", "dispose" -> {
                        cancelled.set(true);
                        cancellationObserved.countDown();
                        yield null;
                    }
                    case "toString" -> "MaterializationCancellationReasoner";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static long regressionLimit(JsonNode budget, String baselineField,
            String absoluteField) {
        long absolute = budget.path(absoluteField).asLong();
        double baseline = budget.path(baselineField).asDouble();
        double factor = budget.path("maximum_regression_factor").asDouble();
        double noise = budget.path("minimum_noise_floor_ms").asDouble();
        return Math.min(absolute,
                (long) Math.ceil(Math.max(baseline * factor, noise)));
    }

    private static OWLReasoner reasoner(OWLOntology ontology, OWLClass parent) {
        return (OWLReasoner) Proxy.newProxyInstance(
                MaterializationScaleTest.class.getClassLoader(),
                new Class<?>[] {OWLReasoner.class}, (proxy, method, arguments) -> switch (
                        method.getName()) {
                    case "getRootOntology" -> ontology;
                    case "isConsistent" -> true;
                    case "getSuperClasses" -> parent.equals(arguments[0])
                            ? new OWLClassNodeSet()
                            : new OWLClassNodeSet(new OWLClassNode(parent));
                    case "interrupt", "dispose" -> null;
                    case "toString" -> "MaterializationScaleReasoner";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static long usedHeap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static final class HeapPeakSampler implements AutoCloseable {
        private final long baseline;
        private final AtomicLong peak;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Thread sampler;

        private HeapPeakSampler() {
            baseline = usedHeap();
            peak = new AtomicLong(baseline);
            sampler = new Thread(() -> {
                while (running.get()) {
                    peak.accumulateAndGet(usedHeap(), Math::max);
                    LockSupport.parkNanos(1_000_000L);
                }
                peak.accumulateAndGet(usedHeap(), Math::max);
            }, "protege-mcp-materialization-heap-sampler");
            sampler.setDaemon(true);
            sampler.start();
        }

        static HeapPeakSampler start() {
            return new HeapPeakSampler();
        }

        long baseline() {
            return baseline;
        }

        long stopAndGetPeak() throws InterruptedException {
            stop();
            return peak.get();
        }

        private void stop() throws InterruptedException {
            if (running.compareAndSet(true, false)) sampler.join(5_000L);
            if (sampler.isAlive()) throw new IllegalStateException("heap sampler did not stop");
        }

        @Override
        public void close() throws InterruptedException {
            stop();
        }
    }
}
