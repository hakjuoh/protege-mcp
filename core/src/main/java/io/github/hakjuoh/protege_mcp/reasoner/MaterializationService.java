package io.github.hakjuoh.protege_mcp.reasoner;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.model.parameters.AxiomAnnotations;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

/** Category-atomic, preview-only inference materialization over one isolated ontology. */
public final class MaterializationService {
    public static final Duration PREVIEW_TTL = Duration.ofMinutes(30);
    public static final String PROVENANCE_PROPERTY =
            "https://w3id.org/protege-mcp/materialization-provenance";
    private static final String PROVENANCE_BASE =
            "https://w3id.org/protege-mcp/materialization/";
    private static final int MAX_CANONICAL_AXIOM_CHARACTERS = 1_048_576;
    private static final int MAX_CANONICAL_AXIOM_NODES = 16_384;
    private static final int MAX_CANONICAL_AXIOM_DEPTH = 128;

    private final Clock clock;
    private final Supplier<String> artifactIds;
    private final Function<Runnable, Thread> workerFactory;
    private final Executor cleanupExecutor;
    private final AtomicBoolean computationActive = new AtomicBoolean();

    public MaterializationService(Clock clock) {
        this(clock, () -> UUID.randomUUID().toString(),
                runnable -> new Thread(runnable,
                        "protege-mcp-materialization-preview"));
    }

    MaterializationService(Clock clock, Supplier<String> artifactIds) {
        this(clock, artifactIds, runnable -> new Thread(runnable,
                "protege-mcp-materialization-preview"));
    }

    MaterializationService(Clock clock, Supplier<String> artifactIds,
            Function<Runnable, Thread> workerFactory) {
        this(clock, artifactIds, workerFactory, cleanupExecutor());
    }

    MaterializationService(Clock clock, Supplier<String> artifactIds,
            Function<Runnable, Thread> workerFactory, Executor cleanupExecutor) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.artifactIds = java.util.Objects.requireNonNull(artifactIds, "artifactIds");
        this.workerFactory = java.util.Objects.requireNonNull(workerFactory, "workerFactory");
        this.cleanupExecutor = java.util.Objects.requireNonNull(
                cleanupExecutor, "cleanupExecutor");
    }

    private static ThreadPoolExecutor cleanupExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(0, 1, 1L, TimeUnit.SECONDS,
                new SynchronousQueue<>(), runnable -> {
                    Thread thread = new Thread(runnable,
                            "protege-mcp-materialization-cleanup");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    @FunctionalInterface
    public interface ReasonerProvider {
        OWLReasoner create(OWLOntology isolatedOntology);
    }

    public MaterializationArtifact preview(OWLOntology isolated,
            ReasonerProvider reasonerProvider, ReasonerCapabilityReport capabilities,
            MaterializationRequest request, MaterializationInputIdentity inputIdentity) {
        if (isolated == null || reasonerProvider == null || capabilities == null
                || request == null || inputIdentity == null) {
            throw new IllegalArgumentException("materialization preview arguments are required");
        }
        try {
            if (!capabilities.identity().profileKey()
                    .equals(inputIdentity.reasonerIdentity().profileKey())) {
                throw failure("materialization_reasoner_identity_mismatch",
                        "The capability report and captured reasoner identity differ.",
                        Map.of(), false);
            }
            requireSupported(request.categories(), capabilities);
        } catch (RuntimeException | LinkageError rejected) {
            cleanupOwnedOntology(isolated);
            throw rejected;
        }
        if (!computationActive.compareAndSet(false, true)) {
            cleanupOwnedOntology(isolated);
            throw failure("materialization_busy",
                    "A previous private materialization computation is still terminating.",
                    Map.of(), true);
        }
        AtomicReference<OWLReasoner> running = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean workerDone = new AtomicBoolean();
        AtomicBoolean cleanupDone = new AtomicBoolean(true);
        AtomicBoolean released = new AtomicBoolean();
        FutureTask<MaterializationArtifact> task = new FutureTask<>(() -> computePreview(
                isolated, reasonerProvider, request, inputIdentity, running, cancelled,
                workerDone, cleanupDone, released));
        Thread worker;
        try {
            worker = workerFactory.apply(() -> {
                try {
                    task.run();
                } finally {
                    workerDone.set(true);
                    releaseWhenTerminated(isolated, workerDone, cleanupDone, released);
                }
            });
        } catch (RuntimeException | LinkageError creationFailure) {
            cleanupOwnedOntology(isolated);
            computationActive.set(false);
            throw creationFailure;
        }
        if (worker == null) {
            cleanupOwnedOntology(isolated);
            computationActive.set(false);
            throw new IllegalStateException("materialization worker factory returned null");
        }
        worker.setDaemon(true);
        try {
            worker.start();
            return task.get(request.limits().timeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            cancelled.set(true);
            task.cancel(true);
            stopPrivateReasoner(running, isolated, workerDone, cleanupDone, released);
            throw failure("materialization_timeout",
                    "Materialization exceeded the explicit preview time limit.",
                    Map.of("timeout_ms", request.limits().timeoutMillis()), true);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cancelled.set(true);
            task.cancel(true);
            stopPrivateReasoner(running, isolated, workerDone, cleanupDone, released);
            throw failure("materialization_interrupted",
                    "Materialization was interrupted before a complete artifact was published.",
                    Map.of(), true);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            if (cause instanceof MaterializationException known) throw known;
            if (cause instanceof DeadlineExceeded) {
                throw failure("materialization_timeout",
                        "Materialization exceeded the explicit preview time limit.",
                        Map.of("timeout_ms", request.limits().timeoutMillis()), true);
            }
            throw failure("materialization_enumeration_failed",
                    "The selected reasoner could not completely enumerate the requested categories.",
                    Map.of("failure_type", cause.getClass().getName()), false);
        } catch (RuntimeException | LinkageError startFailure) {
            cancelled.set(true);
            task.cancel(true);
            stopPrivateReasoner(running, isolated, workerDone, cleanupDone, released);
            if (!worker.isAlive()) {
                workerDone.set(true);
                releaseWhenTerminated(isolated, workerDone, cleanupDone, released);
            }
            throw startFailure;
        }
    }

    private MaterializationArtifact computePreview(OWLOntology isolated,
            ReasonerProvider reasonerProvider, MaterializationRequest request,
            MaterializationInputIdentity inputIdentity, AtomicReference<OWLReasoner> running,
            AtomicBoolean cancelled, AtomicBoolean workerDone,
            AtomicBoolean cleanupDone, AtomicBoolean released) {
        Deadline deadline = new Deadline(request.limits().timeoutMillis());
        OWLReasoner reasoner = null;
        try {
            deadline.check();
            reasoner = reasonerProvider.create(isolated);
            if (reasoner != null) {
                cleanupDone.set(false);
                running.set(reasoner);
            }
            if (cancelled.get()) throw new DeadlineExceeded();
            if (reasoner == null || reasoner.getRootOntology() != isolated) {
                throw failure("materialization_reasoner_creation_failed",
                        "The private reasoner did not bind to the isolated ontology.",
                        Map.of(), false);
            }
            deadline.check();
            if (!reasoner.isConsistent()) {
                throw failure("materialization_inconsistent_ontology",
                        "Inference materialization requires a consistent isolated ontology.",
                        Map.of(), false);
            }
            deadline.check();
            MaterializationArtifact artifact = enumerate(
                    isolated, reasoner, request, inputIdentity, deadline);
            if (cancelled.get()) throw new DeadlineExceeded();
            return artifact;
        } finally {
            if (reasoner != null && running.compareAndSet(reasoner, null)) {
                scheduleCancellation(reasoner, isolated, workerDone, cleanupDone, released);
            }
        }
    }

    private void stopPrivateReasoner(AtomicReference<OWLReasoner> running,
            OWLOntology isolated, AtomicBoolean workerDone,
            AtomicBoolean cleanupDone, AtomicBoolean released) {
        OWLReasoner reasoner = running.getAndSet(null);
        if (reasoner == null) return;
        scheduleCancellation(reasoner, isolated, workerDone, cleanupDone, released);
    }

    private void scheduleCancellation(OWLReasoner reasoner, OWLOntology isolated,
            AtomicBoolean workerDone, AtomicBoolean cleanupDone,
            AtomicBoolean released) {
        cleanupDone.set(false);
        Runnable cleanup = () -> {
            try {
                reasoner.interrupt();
            } catch (RuntimeException | LinkageError ignored) {
                // Disposal remains the final best-effort cancellation signal.
            }
            try {
                reasoner.dispose();
            } catch (RuntimeException | LinkageError ignored) {
                // The isolated reasoner is fenced from every later request.
            } finally {
                cleanupDone.set(true);
                releaseWhenTerminated(isolated, workerDone, cleanupDone, released);
            }
        };
        try {
            cleanupExecutor.execute(cleanup);
        } catch (RejectedExecutionException | LinkageError unavailable) {
            // A reasoner that could not enter the sole cleanup worker remains permanently fenced.
            // Releasing here would permit an unbounded sequence of undisposed private reasoners.
        }
    }

    private void releaseWhenTerminated(OWLOntology isolated,
            AtomicBoolean workerDone, AtomicBoolean cleanupDone,
            AtomicBoolean released) {
        if (workerDone.get() && cleanupDone.get()
                && released.compareAndSet(false, true)) {
            cleanupOwnedOntology(isolated);
            computationActive.set(false);
        }
    }

    private static void cleanupOwnedOntology(OWLOntology ontology) {
        if (ontology == null) return;
        org.semanticweb.owlapi.model.OWLOntologyManager manager =
                ontology.getOWLOntologyManager();
        for (OWLOntology loaded : new ArrayList<>(manager.getOntologies())) {
            manager.removeOntology(loaded);
        }
    }

    private MaterializationArtifact enumerate(OWLOntology isolated, OWLReasoner reasoner,
            MaterializationRequest request, MaterializationInputIdentity input,
            Deadline deadline) {
        Map<String, MaterializationArtifact.CategoryResult> results = new LinkedHashMap<>();
        Set<OWLAxiom> artifactAxioms = new LinkedHashSet<>();
        long totalEnumerated = 0;
        long totalBytes = 0;
        List<String> materializationParts = new ArrayList<>();
        for (MaterializationCategory category : request.categories()) {
            deadline.check();
            Set<OWLAxiom> enumerated;
            try {
                enumerated = enumerateCategory(
                        isolated, reasoner, category, request.limits(), deadline);
            } catch (MaterializationException | DeadlineExceeded known) {
                throw known;
            } catch (RuntimeException | LinkageError failure) {
                throw failure("materialization_enumeration_failed",
                        "The selected reasoner could not completely enumerate a requested category.",
                        Map.of("category", category.value(), "category_discarded", true,
                                "failure_type", failure.getClass().getName()), false);
            }
            totalEnumerated += enumerated.size();
            if (totalEnumerated > request.limits().maxAxiomsTotal()) {
                throw bound(category, "max_axioms_total",
                        request.limits().maxAxiomsTotal(), totalEnumerated);
            }

            List<RenderedAxiom> rendered;
            try {
                rendered = renderAll(isolated, enumerated, deadline,
                        request.limits().maxBytes() - totalBytes);
            } catch (CanonicalByteLimitExceeded exceeded) {
                throw bound(category, "max_bytes", request.limits().maxBytes(),
                        totalBytes + exceeded.observedBytes);
            }
            long categoryBytes = rendered.stream().mapToLong(RenderedAxiom::bytes).sum();
            totalBytes += categoryBytes;
            if (totalBytes > request.limits().maxBytes()) {
                throw bound(category, "max_bytes", request.limits().maxBytes(), totalBytes);
            }
            String contentDigest = digest(rendered.stream().map(RenderedAxiom::canonical).toList());
            String provenanceDigest = digest(List.of(
                    input.modelRevision().semanticFingerprint(),
                    input.closureFingerprint(), input.reasonerIdentity().profileKey(),
                    category.value(), contentDigest));
            String provenanceIri = PROVENANCE_BASE
                    + provenanceDigest.substring("sha256:".length());
            OWLAnnotationProperty property = isolated.getOWLOntologyManager().getOWLDataFactory()
                    .getOWLAnnotationProperty(IRI.create(PROVENANCE_PROPERTY));
            OWLAnnotation provenance = isolated.getOWLOntologyManager().getOWLDataFactory()
                    .getOWLAnnotation(property, IRI.create(provenanceIri));
            long collisions = 0;
            long produced = 0;
            for (RenderedAxiom item : rendered) {
                if (isolated.containsAxiom(item.axiom(), Imports.INCLUDED,
                        AxiomAnnotations.IGNORE_AXIOM_ANNOTATIONS)) {
                    collisions++;
                } else {
                    artifactAxioms.add(item.axiom().getAnnotatedAxiom(Set.of(provenance)));
                    produced++;
                }
            }
            results.put(category.value(), new MaterializationArtifact.CategoryResult(
                    category.value(), true, enumerated.size(), produced, collisions,
                    categoryBytes, false, contentDigest, provenanceIri));
            materializationParts.add(category.value() + "\u0000" + contentDigest
                    + "\u0000" + provenanceIri);
        }
        String materializationDigest = digest(List.of(
                input.modelRevision().semanticFingerprint(), input.closureFingerprint(),
                input.reasonerIdentity().profileKey(), String.join("\n", materializationParts)));
        List<String> artifactParts = new ArrayList<>();
        artifactParts.add(materializationDigest);
        artifactParts.add(request.destination().kind());
        artifactParts.add(request.destination().identifier());
        artifactParts.add(request.provenance().generator());
        artifactParts.add(request.provenance().purpose());
        List<RenderedAxiom> renderedArtifact;
        try {
            renderedArtifact = renderAll(isolated, artifactAxioms, deadline,
                    request.limits().maxBytes());
        } catch (CanonicalByteLimitExceeded exceeded) {
            throw bound(request.categories().get(request.categories().size() - 1),
                    "max_bytes", request.limits().maxBytes(), exceeded.observedBytes);
        }
        long artifactBytes = renderedArtifact.stream().mapToLong(RenderedAxiom::bytes).sum();
        if (artifactBytes > request.limits().maxBytes()) {
            throw bound(request.categories().get(request.categories().size() - 1),
                    "max_bytes", request.limits().maxBytes(), artifactBytes);
        }
        artifactParts.addAll(renderedArtifact.stream().map(RenderedAxiom::canonical).toList());
        String artifactDigest = digest(artifactParts);
        Instant created = clock.instant();
        Instant expires = created.plus(PREVIEW_TTL);
        String artifactId = artifactIds.get();
        if (artifactId == null || !artifactId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalStateException("artifact id supplier returned an invalid identifier");
        }
        String artifactFingerprint = digest(List.of(
                artifactId, artifactDigest, created.toString(), expires.toString()));
        return new MaterializationArtifact(artifactId, artifactFingerprint, artifactDigest,
                materializationDigest, created, expires, request, input, artifactAxioms,
                results, artifactBytes);
    }

    /** Recompute the immutable payload digest immediately before a commit permit is consumed. */
    public boolean verifyArtifact(MaterializationArtifact artifact) {
        if (artifact == null) return false;
        org.semanticweb.owlapi.model.OWLOntologyManager manager =
                org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager();
        try {
            OWLOntology context = manager.createOntology();
            Deadline deadline = new Deadline(Math.min(10_000L,
                    artifact.request().limits().timeoutMillis()));
            List<String> parts = new ArrayList<>();
            parts.add(artifact.materializationDigest());
            parts.add(artifact.request().destination().kind());
            parts.add(artifact.request().destination().identifier());
            parts.add(artifact.request().provenance().generator());
            parts.add(artifact.request().provenance().purpose());
            List<RenderedAxiom> rendered = renderAll(context, artifact.axioms(), deadline,
                    artifact.request().limits().maxBytes());
            long bytes = rendered.stream().mapToLong(RenderedAxiom::bytes).sum();
            parts.addAll(rendered.stream().map(RenderedAxiom::canonical).toList());
            return bytes == artifact.canonicalBytes()
                    && digest(parts).equals(artifact.artifactDigest());
        } catch (org.semanticweb.owlapi.model.OWLOntologyCreationException
                | RuntimeException | LinkageError failure) {
            return false;
        } finally {
            for (OWLOntology ontology : new ArrayList<>(manager.getOntologies())) {
                manager.removeOntology(ontology);
            }
        }
    }

    private static Set<OWLAxiom> enumerateCategory(OWLOntology ontology, OWLReasoner reasoner,
            MaterializationCategory category, MaterializationRequest.Limits limits,
            Deadline deadline) {
        OWLDataFactory dataFactory = ontology.getOWLOntologyManager().getOWLDataFactory();
        CategoryAccumulator out = new CategoryAccumulator(category,
                limits.maxAxiomsPerCategory(), deadline);
        switch (category) {
            case SUBCLASS_AXIOMS -> {
                for (OWLClass cls : ontology.getClassesInSignature(Imports.INCLUDED)) {
                    deadline.check();
                    if (cls.isOWLThing() || cls.isOWLNothing()) continue;
                    for (OWLClass parent : reasoner.getSuperClasses(cls, false).getFlattened()) {
                        if (!parent.isOWLThing() && !parent.equals(cls)) {
                            out.add(dataFactory.getOWLSubClassOfAxiom(cls, parent));
                        }
                    }
                }
            }
            case EQUIVALENT_CLASS_AXIOMS -> {
                for (OWLClass cls : ontology.getClassesInSignature(Imports.INCLUDED)) {
                    deadline.check();
                    if (cls.isOWLThing() || cls.isOWLNothing()) continue;
                    Set<OWLClass> equivalents = new LinkedHashSet<>(
                            reasoner.getEquivalentClasses(cls).getEntities());
                    equivalents.remove(cls);
                    equivalents.removeIf(OWLClass::isOWLThing);
                    equivalents.removeIf(OWLClass::isOWLNothing);
                    for (OWLClass equivalent : equivalents) {
                        out.add(dataFactory.getOWLEquivalentClassesAxiom(cls, equivalent));
                    }
                }
            }
            case CLASS_ASSERTIONS -> {
                for (OWLNamedIndividual individual
                        : ontology.getIndividualsInSignature(Imports.INCLUDED)) {
                    deadline.check();
                    for (OWLClass type : reasoner.getTypes(individual, false).getFlattened()) {
                        if (!type.isOWLThing()) {
                            out.add(dataFactory.getOWLClassAssertionAxiom(type, individual));
                        }
                    }
                }
            }
            case PROPERTY_HIERARCHY_AXIOMS -> {
                for (OWLObjectProperty property
                        : ontology.getObjectPropertiesInSignature(Imports.INCLUDED)) {
                    deadline.check();
                    if (property.isOWLTopObjectProperty()
                            || property.isOWLBottomObjectProperty()) continue;
                    for (OWLObjectPropertyExpression parent
                            : reasoner.getSuperObjectProperties(property, false).getFlattened()) {
                        if (!parent.isOWLTopObjectProperty() && !parent.equals(property)) {
                            out.add(dataFactory.getOWLSubObjectPropertyOfAxiom(property, parent));
                        }
                    }
                }
                for (OWLDataProperty property
                        : ontology.getDataPropertiesInSignature(Imports.INCLUDED)) {
                    deadline.check();
                    if (property.isOWLTopDataProperty()
                            || property.isOWLBottomDataProperty()) continue;
                    for (OWLDataProperty parent
                            : reasoner.getSuperDataProperties(property, false).getFlattened()) {
                        if (!parent.isOWLTopDataProperty() && !parent.equals(property)) {
                            out.add(dataFactory.getOWLSubDataPropertyOfAxiom(property, parent));
                        }
                    }
                }
            }
            case OBJECT_PROPERTY_ASSERTIONS -> {
                Set<OWLObjectProperty> properties = ontology
                        .getObjectPropertiesInSignature(Imports.INCLUDED);
                for (OWLNamedIndividual subject
                        : ontology.getIndividualsInSignature(Imports.INCLUDED)) {
                    for (OWLObjectProperty property : properties) {
                        deadline.check();
                        if (property.isOWLTopObjectProperty()
                                || property.isOWLBottomObjectProperty()) continue;
                        for (OWLNamedIndividual value : reasoner
                                .getObjectPropertyValues(subject, property).getFlattened()) {
                            out.add(dataFactory.getOWLObjectPropertyAssertionAxiom(
                                    property, subject, value));
                        }
                    }
                }
            }
            case DATA_PROPERTY_ASSERTIONS -> {
                Set<OWLDataProperty> properties = ontology
                        .getDataPropertiesInSignature(Imports.INCLUDED);
                for (OWLNamedIndividual subject
                        : ontology.getIndividualsInSignature(Imports.INCLUDED)) {
                    for (OWLDataProperty property : properties) {
                        deadline.check();
                        if (property.isOWLTopDataProperty()
                                || property.isOWLBottomDataProperty()) continue;
                        for (OWLLiteral value : reasoner.getDataPropertyValues(subject, property)) {
                            out.add(dataFactory.getOWLDataPropertyAssertionAxiom(
                                    property, subject, value));
                        }
                    }
                }
            }
        }
        return out.finish();
    }

    private static List<RenderedAxiom> renderAll(OWLOntology context,
            Set<OWLAxiom> axioms, Deadline deadline, long maximumBytes) {
        List<RenderedAxiom> rendered = new ArrayList<>(axioms.size());
        long totalBytes = 0;
        for (OWLAxiom axiom : axioms) {
            deadline.check();
            String canonical = CanonicalOwlRenderer.render(context, axiom,
                    MAX_CANONICAL_AXIOM_CHARACTERS, MAX_CANONICAL_AXIOM_NODES,
                    MAX_CANONICAL_AXIOM_DEPTH, deadline::check);
            long bytes = canonical.getBytes(StandardCharsets.UTF_8).length + 1L;
            if (bytes > maximumBytes - totalBytes) {
                throw new CanonicalByteLimitExceeded(totalBytes + bytes);
            }
            rendered.add(new RenderedAxiom(axiom, canonical, bytes));
            totalBytes += bytes;
        }
        rendered.sort(Comparator.comparing(RenderedAxiom::canonical));
        return rendered;
    }

    private static void requireSupported(List<MaterializationCategory> categories,
            ReasonerCapabilityReport capabilities) {
        Map<String, String> unsupported = new LinkedHashMap<>();
        for (MaterializationCategory category : categories) {
            for (String capability : category.capabilityIds()) {
                CapabilityStatus status = capabilities.owlStatus(capability);
                if (status != CapabilityStatus.SUPPORTED) {
                    unsupported.put(category.value(), status.value());
                    break;
                }
            }
        }
        if (!unsupported.isEmpty()) {
            throw failure("materialization_category_not_supported",
                    "Every requested category requires exact supported capability evidence.",
                    Map.of("category_status", unsupported, "effects_prevented", true), false);
        }
        CapabilityStatus consistency = capabilities.owlStatus("consistency");
        if (consistency != CapabilityStatus.SUPPORTED) {
            throw failure("materialization_consistency_not_supported",
                    "Materialization requires exact supported consistency evidence.",
                    Map.of("status", consistency.value(), "effects_prevented", true), false);
        }
    }

    private static MaterializationException bound(MaterializationCategory category,
            String budget, long maximum, long observed) {
        return failure("materialization_bound_exceeded",
                "A requested category crossed an explicit whole-category bound.",
                Map.of("category", category.value(), "budget", budget,
                        "maximum", maximum, "observed", observed,
                        "category_discarded", true, "effects_prevented", true), false);
    }

    private static String digest(List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            StringBuilder out = new StringBuilder("sha256:");
            for (byte value : digest.digest()) {
                out.append(String.format("%02x", value & 0xff));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static MaterializationException failure(String code, String message,
            Map<String, Object> details, boolean retryable) {
        Map<String, Object> complete = new LinkedHashMap<>(details);
        complete.putIfAbsent("effects_prevented", true);
        return new MaterializationException(code, message, complete, retryable);
    }

    private record RenderedAxiom(OWLAxiom axiom, String canonical, long bytes) { }

    private static final class CategoryAccumulator {
        private final MaterializationCategory category;
        private final int maximum;
        private final Deadline deadline;
        private final Set<OWLAxiom> axioms = new HashSet<>();

        CategoryAccumulator(MaterializationCategory category, int maximum, Deadline deadline) {
            this.category = category;
            this.maximum = maximum;
            this.deadline = deadline;
        }

        void add(OWLAxiom axiom) {
            deadline.check();
            if (axioms.add(axiom.getAxiomWithoutAnnotations()) && axioms.size() > maximum) {
                axioms.clear();
                throw bound(category, "max_axioms_per_category", maximum,
                        (long) maximum + 1);
            }
        }

        Set<OWLAxiom> finish() {
            deadline.check();
            return Set.copyOf(axioms);
        }
    }

    private static final class Deadline {
        private final long deadlineNanos;

        Deadline(long timeoutMillis) {
            long now = System.nanoTime();
            long duration = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            deadlineNanos = now > Long.MAX_VALUE - duration ? Long.MAX_VALUE : now + duration;
        }

        void check() {
            if (System.nanoTime() - deadlineNanos >= 0) throw new DeadlineExceeded();
        }
    }

    private static final class DeadlineExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class CanonicalByteLimitExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final long observedBytes;

        CanonicalByteLimitExceeded(long observedBytes) {
            this.observedBytes = observedBytes;
        }
    }
}
