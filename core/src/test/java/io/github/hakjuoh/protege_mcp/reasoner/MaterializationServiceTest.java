package io.github.hakjuoh.protege_mcp.reasoner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.BufferingMode;
import org.semanticweb.owlapi.reasoner.SimpleConfiguration;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;

import io.github.hakjuoh.protege_mcp.contracts.ModelRevision;

class MaterializationServiceTest {
    private static final String HASH_A =
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B =
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String HASH_C =
            "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    private static final String OWNER =
            "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
    private static final Instant NOW = Instant.parse("2026-07-22T12:00:00Z");

    @Test
    void previewIsDeterministicPrivateAndReportsAssertedCollisions() throws Exception {
        Fixture fixture = fixture();
        MaterializationRequest request = request(List.of(
                MaterializationCategory.SUBCLASS_AXIOMS,
                MaterializationCategory.CLASS_ASSERTIONS), 100);
        MaterializationService service = new MaterializationService(
                Clock.fixed(NOW, ZoneOffset.UTC), () -> "preview-1");

        MaterializationArtifact first = service.preview(fixture.ontology,
                fixture.factory::createReasoner, fixture.capabilities, request, fixture.identity);
        Fixture repeated = fixture();
        MaterializationArtifact second = service.preview(repeated.ontology,
                repeated.factory::createReasoner, repeated.capabilities, request,
                repeated.identity);

        assertEquals(first.materializationDigest(), second.materializationDigest());
        assertEquals(first.artifactDigest(), second.artifactDigest());
        assertEquals(NOW.plus(MaterializationService.PREVIEW_TTL), first.expiresAt());
        assertFalse(first.axioms().isEmpty(), "the transitive subclass inference is retained");
        assertTrue(first.axioms().stream().allMatch(axiom -> axiom.getAnnotations().stream()
                .anyMatch(annotation -> annotation.getProperty().getIRI().toString()
                        .equals(MaterializationService.PROVENANCE_PROPERTY))));
        assertEquals(5, fixture.ontology.getAxiomCount(),
                "preview does not alter the isolated input ontology");
        assertTrue(((Number) first.report().get("asserted_collision_count")).longValue() >= 2);
        assertEquals(false, first.report().get("live_state_changed"));
    }

    @Test
    void categoryOverflowDiscardsThePreviewBeforeArtifactPublication() throws Exception {
        Fixture fixture = fixture();
        MaterializationService service = new MaterializationService(
                Clock.fixed(NOW, ZoneOffset.UTC), () -> "never-published");
        MaterializationException failure = assertThrows(MaterializationException.class,
                () -> service.preview(fixture.ontology, fixture.factory::createReasoner,
                        fixture.capabilities, request(List.of(
                                MaterializationCategory.SUBCLASS_AXIOMS), 1), fixture.identity));
        assertEquals("materialization_bound_exceeded", failure.code());
        assertEquals(true, failure.details().get("category_discarded"));
        assertEquals(5, fixture.ontology.getAxiomCount());
    }

    @Test
    void unsupportedCategoryFailsBeforeReasonerCreation() throws Exception {
        Fixture fixture = fixture();
        StructuralReasonerFactory factory = new StructuralReasonerFactory();
        ReasonerIdentity reasonerIdentity = ReasonerIdentity.capture(
                factory.getClass().getName(), factory.getReasonerName(), factory,
                new SimpleConfiguration(), BufferingMode.BUFFERING, "test");
        ReasonerCapabilityReport capabilities =
                new ReasonerCapabilityRegistry().report(reasonerIdentity);
        MaterializationInputIdentity identity = new MaterializationInputIdentity(
                fixture.identity.modelRevision(), fixture.identity.closureFingerprint(),
                fixture.identity.importLockDigest(), fixture.identity.mappingRevision(),
                fixture.identity.policyDigest(), fixture.identity.policyAssetDigest(),
                fixture.identity.policyPath(), reasonerIdentity);
        AtomicInteger creations = new AtomicInteger();
        MaterializationService service = new MaterializationService(
                Clock.fixed(NOW, ZoneOffset.UTC), () -> "never-created");
        MaterializationException failure = assertThrows(MaterializationException.class,
                () -> service.preview(fixture.ontology, ontology -> {
                    creations.incrementAndGet();
                    return factory.createReasoner(ontology);
                }, capabilities, request(List.of(
                        MaterializationCategory.OBJECT_PROPERTY_ASSERTIONS), 100),
                        identity));
        assertEquals("materialization_category_not_supported", failure.code());
        assertEquals(0, creations.get());
    }

    @Test
    void unknownReasonerProfileFailsBeforeReasonerCreation() throws Exception {
        Fixture fixture = fixture();
        ReasonerIdentity unknownIdentity = ReasonerIdentity.capture(
                "unreviewed.reasoner", fixture.factory.getReasonerName(), fixture.factory,
                new SimpleConfiguration(), BufferingMode.BUFFERING, "test");
        ReasonerCapabilityReport unknownCapabilities =
                new ReasonerCapabilityRegistry().report(unknownIdentity);
        assertEquals("unknown", unknownCapabilities.profileStatus());
        MaterializationInputIdentity inputIdentity = new MaterializationInputIdentity(
                fixture.identity.modelRevision(), fixture.identity.closureFingerprint(),
                fixture.identity.importLockDigest(), fixture.identity.mappingRevision(),
                fixture.identity.policyDigest(), fixture.identity.policyAssetDigest(),
                fixture.identity.policyPath(), unknownIdentity);
        AtomicInteger creations = new AtomicInteger();
        MaterializationService service = new MaterializationService(
                Clock.fixed(NOW, ZoneOffset.UTC), () -> "never-created");

        MaterializationException failure = assertThrows(MaterializationException.class,
                () -> service.preview(fixture.ontology, ontology -> {
                    creations.incrementAndGet();
                    return fixture.factory.createReasoner(ontology);
        }, unknownCapabilities, request(List.of(
                        MaterializationCategory.SUBCLASS_AXIOMS), 100), inputIdentity));

        assertEquals("materialization_category_not_supported", failure.code());
        assertEquals(Map.of("subclass_axioms", "unknown"),
                failure.details().get("category_status"));
        assertEquals(0, creations.get());
    }

    @Test
    void semanticConsistencyMustBeExactlySupported() throws Exception {
        Fixture fixture = fixture();
        StructuralReasonerFactory factory = new StructuralReasonerFactory();
        ReasonerIdentity reasonerIdentity = ReasonerIdentity.capture(
                factory.getClass().getName(), factory.getReasonerName(), factory,
                new SimpleConfiguration(), BufferingMode.BUFFERING, "test");
        ReasonerCapabilityReport capabilities =
                new ReasonerCapabilityRegistry().report(reasonerIdentity);
        MaterializationInputIdentity identity = new MaterializationInputIdentity(
                fixture.identity.modelRevision(), fixture.identity.closureFingerprint(),
                fixture.identity.importLockDigest(), fixture.identity.mappingRevision(),
                fixture.identity.policyDigest(), fixture.identity.policyAssetDigest(),
                fixture.identity.policyPath(), reasonerIdentity);

        MaterializationException failure = assertThrows(MaterializationException.class,
                () -> new MaterializationService(Clock.fixed(NOW, ZoneOffset.UTC)).preview(
                        fixture.ontology, factory::createReasoner, capabilities,
                        request(List.of(MaterializationCategory.SUBCLASS_AXIOMS), 100),
                        identity));

        assertEquals("materialization_consistency_not_supported", failure.code());
    }

    @Test
    void categoriesMustBeNonEmpty() {
        assertThrows(IllegalArgumentException.class, () -> request(List.of(), 100));
    }

    @Test
    void releasePerformanceEnvelopePinsClosedMaterializationLimits() throws Exception {
        var envelope = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                java.nio.file.Files.readAllBytes(
                        java.nio.file.Path.of("performance", "materialization-v1.json")));
        assertEquals(1, envelope.path("schema_version").asInt());
        assertEquals(MaterializationRequest.MAX_AXIOMS,
                envelope.path("produced_axioms").asInt());
        assertEquals(500, envelope.path("maximum_live_axioms").asInt());
        assertEquals(512L * 1024 * 1024,
                envelope.path("maximum_incremental_heap_bytes").asLong());
        assertEquals(100, envelope.path("maximum_model_thread_stall_ms").asInt());
        assertEquals(5_000, envelope.path("maximum_cancellation_ms").asInt());
        assertEquals(2.0, envelope.path("maximum_regression_factor").asDouble());
        assertEquals(250.0, envelope.path("minimum_noise_floor_ms").asDouble());
    }

    @Test
    void requestParserRejectsUnknownTopLevelAndNestedKeys() {
        Map<String, Object> valid = new LinkedHashMap<>();
        valid.put("categories", List.of("subclass_axioms"));
        valid.put("destination", Map.of("kind", "new_ontology",
                "identifier", "https://example.org/materialized"));
        valid.put("provenance", Map.of("generator", "protege-mcp",
                "purpose", "strict parser test"));
        valid.put("limits", Map.of("max_axioms_per_category", 100,
                "max_axioms_total", 100, "max_bytes", 1_048_576,
                "timeout_ms", 10_000));

        Map<String, Object> topLevel = new LinkedHashMap<>(valid);
        topLevel.put("unexpected", true);
        assertThrows(IllegalArgumentException.class,
                () -> MaterializationRequests.parse(topLevel));

        Map<String, Object> nestedDestination = new LinkedHashMap<>();
        nestedDestination.put("kind", "new_ontology");
        nestedDestination.put("identifier", "https://example.org/materialized");
        nestedDestination.put("unexpected", true);
        Map<String, Object> nested = new LinkedHashMap<>(valid);
        nested.put("destination", nestedDestination);
        assertThrows(IllegalArgumentException.class,
                () -> MaterializationRequests.parse(nested));
    }

    @Test
    void categoryEnumerationFailureNamesAndDiscardsTheWholeCategory() throws Exception {
        Fixture fixture = fixture();
        MaterializationService service = new MaterializationService(
                Clock.fixed(NOW, ZoneOffset.UTC), () -> "never-published");
        OWLReasoner failing = (OWLReasoner) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {OWLReasoner.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getRootOntology" -> fixture.ontology;
                    case "isConsistent" -> true;
                    case "dispose" -> null;
                    case "getSuperClasses" -> throw new IllegalStateException(
                            "scripted enumeration failure");
                    case "toString" -> "FailingReasoner";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        MaterializationException failure = assertThrows(MaterializationException.class,
                () -> service.preview(fixture.ontology, ignored -> failing,
                        fixture.capabilities, request(List.of(
                                MaterializationCategory.SUBCLASS_AXIOMS), 100), fixture.identity));
        assertEquals("materialization_enumeration_failed", failure.code());
        assertEquals("subclass_axioms", failure.details().get("category"));
        assertEquals(true, failure.details().get("category_discarded"));
    }

    @Test
    void creationThatReturnsAfterTheDeadlinePublishesNothing() throws Exception {
        Fixture fixture = fixture();
        MaterializationRequest timed = new MaterializationRequest(
                List.of(MaterializationCategory.SUBCLASS_AXIOMS),
                new MaterializationRequest.Destination(
                        "new_ontology", "https://example.org/materialized"),
                new MaterializationRequest.Provenance("protege-mcp", "timeout test"),
                new MaterializationRequest.Limits(100, 100, 1_048_576, 1));
        MaterializationService service = new MaterializationService(
                Clock.fixed(NOW, ZoneOffset.UTC), () -> "never-published");
        MaterializationException failure = assertThrows(MaterializationException.class,
                () -> service.preview(fixture.ontology, ontology -> {
                    try {
                        Thread.sleep(10L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return fixture.factory.createReasoner(ontology);
                }, fixture.capabilities, timed, fixture.identity));
        assertEquals("materialization_timeout", failure.code());
    }

    @Test
    void cancellationBeforeTheWorkerEntersReleasesTheService() throws Exception {
        Fixture fixture = fixture();
        var firstManager = fixture.ontology.getOWLOntologyManager();
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch workerFinished = new CountDownLatch(1);
        MaterializationService service = new MaterializationService(
                Clock.fixed(NOW, ZoneOffset.UTC), () -> "delayed-worker", runnable ->
                        new Thread(() -> {
                            try {
                                releaseWorker.await();
                                runnable.run();
                            } catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                                runnable.run();
                            } finally {
                                workerFinished.countDown();
                            }
                        }, "delayed-materialization-worker"));
        MaterializationRequest timed = new MaterializationRequest(
                List.of(MaterializationCategory.SUBCLASS_AXIOMS),
                new MaterializationRequest.Destination(
                        "new_ontology", "https://example.org/materialized"),
                new MaterializationRequest.Provenance("protege-mcp", "pre-start timeout"),
                new MaterializationRequest.Limits(100, 100, 1_048_576, 10));

        MaterializationException timeout = assertThrows(MaterializationException.class,
                () -> service.preview(fixture.ontology, fixture.factory::createReasoner,
                        fixture.capabilities, timed, fixture.identity));
        assertEquals("materialization_timeout", timeout.code());
        releaseWorker.countDown();
        assertTrue(workerFinished.await(1, TimeUnit.SECONDS));
        awaitEmpty(firstManager);

        Fixture second = fixture();
        MaterializationArtifact recovered = service.preview(second.ontology,
                second.factory::createReasoner, second.capabilities,
                request(List.of(MaterializationCategory.SUBCLASS_AXIOMS), 100),
                second.identity);
        assertEquals("delayed-worker", recovered.artifactId());
    }

    @Test
    void successfulPreviewStaysBusyWhileBoundedDisposalIsBlocked() throws Exception {
        Fixture fixture = fixture();
        var firstManager = fixture.ontology.getOWLOntologyManager();
        CountDownLatch cancellationEntered = new CountDownLatch(1);
        CountDownLatch releaseCancellation = new CountDownLatch(1);
        CountDownLatch disposed = new CountDownLatch(1);
        OWLReasoner delegate = fixture.factory.createReasoner(fixture.ontology);
        OWLReasoner blockingCleanup = (OWLReasoner) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {OWLReasoner.class},
                (proxy, method, args) -> {
                    if ("interrupt".equals(method.getName())) {
                        cancellationEntered.countDown();
                        boolean released = false;
                        while (!released) {
                            try {
                                releaseCancellation.await();
                                released = true;
                            } catch (InterruptedException ignored) {
                                // Deliberately model a blocking third-party cleanup.
                            }
                        }
                        return null;
                    }
                    if ("dispose".equals(method.getName())) {
                        delegate.dispose();
                        disposed.countDown();
                        return null;
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        MaterializationService service = new MaterializationService(
                Clock.fixed(NOW, ZoneOffset.UTC), () -> "bounded-cleanup");
        long cleanupThreadsBefore = Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> "protege-mcp-materialization-cleanup".equals(
                        thread.getName())).count();

        MaterializationArtifact artifact = service.preview(fixture.ontology,
                ignored -> blockingCleanup, fixture.capabilities,
                request(List.of(MaterializationCategory.SUBCLASS_AXIOMS), 100),
                fixture.identity);
        assertEquals("bounded-cleanup", artifact.artifactId());
        assertTrue(cancellationEntered.await(1, TimeUnit.SECONDS));
        assertTrue(Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> "protege-mcp-materialization-cleanup".equals(
                        thread.getName())).count() <= cleanupThreadsBefore + 1);

        Fixture second = fixture();
        MaterializationException busy = assertThrows(MaterializationException.class,
                () -> service.preview(second.ontology, second.factory::createReasoner,
                        second.capabilities, request(List.of(
                                MaterializationCategory.SUBCLASS_AXIOMS), 100),
                        second.identity));
        assertEquals("materialization_busy", busy.code());
        releaseCancellation.countDown();
        assertTrue(disposed.await(1, TimeUnit.SECONDS));
        awaitEmpty(firstManager);
    }

    @Test
    void rejectedCleanupSubmissionPermanentlyFencesTheService() throws Exception {
        Fixture fixture = fixture();
        MaterializationService service = new MaterializationService(
                Clock.fixed(NOW, ZoneOffset.UTC), () -> "cleanup-rejected",
                runnable -> new Thread(runnable, "cleanup-rejection-worker"), command -> {
                    throw new RejectedExecutionException("injected cleanup rejection");
                });

        MaterializationArtifact artifact = service.preview(fixture.ontology,
                fixture.factory::createReasoner, fixture.capabilities,
                request(List.of(MaterializationCategory.SUBCLASS_AXIOMS), 100),
                fixture.identity);

        assertEquals("cleanup-rejected", artifact.artifactId());
        Fixture second = fixture();
        MaterializationException busy = assertThrows(MaterializationException.class,
                () -> service.preview(second.ontology, second.factory::createReasoner,
                        second.capabilities, request(List.of(
                                MaterializationCategory.SUBCLASS_AXIOMS), 100),
                        second.identity));
        assertEquals("materialization_busy", busy.code());
    }

    @Test
    void hardTimeoutFencesAReasonerCallThatIgnoresInterruption() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch cancellationEntered = new CountDownLatch(1);
        CountDownLatch releaseCancellation = new CountDownLatch(1);
        AtomicBoolean blocked = new AtomicBoolean();
        OWLReasoner delegate = new StructuralReasonerFactory()
                .createReasoner(fixture.ontology);
        OWLReasoner hanging = (OWLReasoner) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {OWLReasoner.class},
                (proxy, method, args) -> {
                    if ("getRootOntology".equals(method.getName())) return fixture.ontology;
                    if ("isConsistent".equals(method.getName())) return true;
                    if ("interrupt".equals(method.getName())) {
                        cancellationEntered.countDown();
                        boolean released = false;
                        while (!released) {
                            try {
                                releaseCancellation.await();
                                released = true;
                            } catch (InterruptedException ignored) {
                                // Deliberately model blocking third-party cancellation.
                            }
                        }
                        return null;
                    }
                    if ("dispose".equals(method.getName())) return null;
                    if ("getSuperClasses".equals(method.getName())
                            && blocked.compareAndSet(false, true)) {
                        entered.countDown();
                        boolean released = false;
                        while (!released) {
                            try {
                                release.await();
                                released = true;
                            } catch (InterruptedException ignored) {
                                // Deliberately model a non-cooperative reasoner call.
                            }
                        }
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        MaterializationRequest timed = new MaterializationRequest(
                List.of(MaterializationCategory.SUBCLASS_AXIOMS),
                new MaterializationRequest.Destination(
                        "new_ontology", "https://example.org/materialized"),
                new MaterializationRequest.Provenance("protege-mcp", "hard timeout test"),
                new MaterializationRequest.Limits(100, 100, 1_048_576, 100));
        MaterializationService service = new MaterializationService(
                Clock.fixed(NOW, ZoneOffset.UTC), () -> "never-published");
        long started = System.nanoTime();
        MaterializationException timeout = assertThrows(MaterializationException.class,
                () -> service.preview(fixture.ontology, ignored -> hanging,
                        fixture.capabilities, timed, fixture.identity));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertEquals("materialization_timeout", timeout.code());
        assertTrue(elapsedMillis < 2_000, "the synchronous caller has a hard return bound");
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertTrue(cancellationEntered.await(1, TimeUnit.SECONDS));

        Fixture second = fixture();
        MaterializationException busy = assertThrows(MaterializationException.class,
                () -> service.preview(second.ontology, second.factory::createReasoner,
                        second.capabilities, request(List.of(
                                MaterializationCategory.SUBCLASS_AXIOMS), 100),
                        second.identity));
        assertEquals("materialization_busy", busy.code());
        release.countDown();
        releaseCancellation.countDown();
    }

    @Test
    void ownerStoreChecksFingerprintAndExpiresAtThirtyMinutes() throws Exception {
        Fixture fixture = fixture();
        MutableClock clock = new MutableClock(NOW);
        MaterializationArtifact artifact = new MaterializationService(
                clock, () -> "owner-preview").preview(fixture.ontology,
                        fixture.factory::createReasoner, fixture.capabilities,
                        request(List.of(MaterializationCategory.SUBCLASS_AXIOMS), 100),
                        fixture.identity);
        MaterializationArtifactStore store = new MaterializationArtifactStore(
                clock, 2, 1_048_576);
        store.put(OWNER, artifact);
        assertEquals(artifact, store.require(
                OWNER, "owner-preview", artifact.artifactFingerprint()));
        assertEquals("materialization_artifact_mismatch", assertThrows(
                MaterializationException.class, () -> store.require(
                        OWNER, "owner-preview", HASH_A)).code());
        assertEquals("materialization_artifact_not_found", assertThrows(
                MaterializationException.class, () -> store.require(
                        HASH_C, "owner-preview", artifact.artifactFingerprint())).code());
        clock.now = NOW.plus(MaterializationService.PREVIEW_TTL);
        assertEquals("materialization_artifact_not_found", assertThrows(
                MaterializationException.class, () -> store.require(
                        OWNER, "owner-preview", artifact.artifactFingerprint())).code());
    }

    @Test
    void ownerStoreEvictsOnlyItsOldestArtifactAndRejectsOversizedPayloads()
            throws Exception {
        Fixture firstFixture = fixture();
        MaterializationArtifact first = new MaterializationService(
                Clock.fixed(NOW, ZoneOffset.UTC), () -> "first").preview(
                        firstFixture.ontology, firstFixture.factory::createReasoner,
                        firstFixture.capabilities, request(List.of(
                                MaterializationCategory.SUBCLASS_AXIOMS), 100),
                        firstFixture.identity);
        MaterializationArtifact second = copyArtifact(first, "second", HASH_B, 1);
        String otherOwner = HASH_C;
        MaterializationArtifact other = copyArtifact(first, "other", HASH_C, 1);
        MaterializationArtifact oversized = copyArtifact(first, "oversized", HASH_A, 2_048);
        MaterializationArtifactStore store = new MaterializationArtifactStore(
                Clock.fixed(NOW, ZoneOffset.UTC), 1, 1_024);

        store.put(OWNER, first);
        store.put(otherOwner, other);
        store.put(OWNER, second);

        assertEquals("materialization_artifact_not_found", assertThrows(
                MaterializationException.class, () -> store.require(
                        OWNER, first.artifactId(), first.artifactFingerprint())).code());
        assertEquals(second, store.require(OWNER, "second", HASH_B));
        assertEquals(other, store.require(otherOwner, "other", HASH_C));
        assertEquals("materialization_artifact_quota_exceeded", assertThrows(
                MaterializationException.class, () -> store.put(OWNER, oversized)).code());
    }

    private static MaterializationArtifact copyArtifact(MaterializationArtifact source,
            String artifactId, String fingerprint, long canonicalBytes) {
        return new MaterializationArtifact(artifactId, fingerprint,
                source.artifactDigest(), source.materializationDigest(), NOW,
                NOW.plus(MaterializationService.PREVIEW_TTL), source.request(),
                source.inputIdentity(), Set.of(), Map.of(), canonicalBytes);
    }

    private static void awaitEmpty(
            org.semanticweb.owlapi.model.OWLOntologyManager manager)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!manager.getOntologies().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertTrue(manager.getOntologies().isEmpty());
    }

    private static MaterializationRequest request(List<MaterializationCategory> categories,
            int categoryLimit) {
        return new MaterializationRequest(categories,
                new MaterializationRequest.Destination(
                        "new_ontology", "https://example.org/materialized"),
                new MaterializationRequest.Provenance("protege-mcp", "test preview"),
                new MaterializationRequest.Limits(categoryLimit, 100, 1_048_576, 10_000));
    }

    private static Fixture fixture() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("https://example.org/source"));
        var data = manager.getOWLDataFactory();
        var a = data.getOWLClass(IRI.create("https://example.org/A"));
        var b = data.getOWLClass(IRI.create("https://example.org/B"));
        var c = data.getOWLClass(IRI.create("https://example.org/C"));
        var individual = data.getOWLNamedIndividual(IRI.create("https://example.org/i"));
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(a));
        manager.addAxiom(ontology, data.getOWLSubClassOfAxiom(a, b));
        manager.addAxiom(ontology, data.getOWLSubClassOfAxiom(b, c));
        manager.addAxiom(ontology, data.getOWLClassAssertionAxiom(a, individual));
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(individual));

        OWLReasonerFactory factory = new org.semanticweb.HermiT.ReasonerFactory();
        ReasonerIdentity reasonerIdentity = ReasonerIdentity.capture(
                factory.getClass().getName(), factory.getReasonerName(), factory,
                new SimpleConfiguration(), BufferingMode.BUFFERING, "test");
        ReasonerCapabilityReport capabilities =
                new ReasonerCapabilityRegistry().report(reasonerIdentity);
        assertEquals("reviewed", capabilities.profileStatus());
        ModelRevision revision = new ModelRevision(
                "00000000-0000-4000-8000-000000000001", 1, HASH_A, HASH_B);
        MaterializationInputIdentity identity = new MaterializationInputIdentity(
                revision, HASH_C, null, null, HASH_A, HASH_B, null, reasonerIdentity);
        return new Fixture(ontology, factory, capabilities, identity);
    }

    private record Fixture(OWLOntology ontology, OWLReasonerFactory factory,
            ReasonerCapabilityReport capabilities, MaterializationInputIdentity identity) { }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
