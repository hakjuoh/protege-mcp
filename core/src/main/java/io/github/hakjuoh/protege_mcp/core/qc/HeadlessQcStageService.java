package io.github.hakjuoh.protege_mcp.core.qc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.profiles.OWLProfileReport;
import org.semanticweb.owlapi.profiles.OWLProfileViolation;
import org.semanticweb.owlapi.profiles.Profiles;
import org.semanticweb.owlapi.profiles.violations.UndeclaredEntityViolation;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.reasoner.SimpleConfiguration;

import io.github.hakjuoh.protege_mcp.contracts.RdfDatasetFingerprint;
import io.github.hakjuoh.protege_mcp.contracts.RdfDatasetFingerprints;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ReasonerNames;

/** Protégé-free execution of QC stages that depend only on OWLAPI state and an injected reasoner. */
public final class HeadlessQcStageService {

    private HeadlessQcStageService() {
    }

    /**
     * Classification result and optional query snapshot. The snapshot is null when none was supplied;
     * callers must inspect {@link QuerySnapshot#inferredAvailable()} before executing inferred queries.
     */
    public record ReasoningOutcome(QcStageExecution execution, QuerySnapshot querySnapshot) {
        public ReasoningOutcome {
            if (execution == null) {
                throw new IllegalArgumentException("execution must not be null");
            }
        }
    }

    /** Compute the policy-pinned root RDF dataset identity. */
    public static QcStageExecution interoperability(OWLOntology root, ProjectPolicy policy) {
        if (root == null || policy == null || !policy.valid()) {
            return QcStageExecution.skipped("interoperability",
                    "interoperability requires a valid project policy and ontology snapshot");
        }
        Map<String, Object> interoperability = object(policy.effective().get("interoperability"));
        Map<String, Object> metadata = object(interoperability.get("metadata"));
        Map<String, Object> canonicalization = object(interoperability.get("canonicalization"));
        String algorithm = string(canonicalization.get("algorithm"));
        String hash = string(canonicalization.get("hash"));
        String scope = string(canonicalization.get("scope"));
        if (!RdfDatasetFingerprints.CANONICALIZATION_ALGORITHM.equals(algorithm)
                || !RdfDatasetFingerprints.HASH_ALGORITHM.equals(hash)
                || !RdfDatasetFingerprints.SCOPE.equals(scope)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("canonicalization_algorithm", algorithm);
            details.put("hash_algorithm", hash);
            details.put("scope", scope);
            return QcStageExecution.error("interoperability",
                    "unsupported RDF dataset identity contract", details);
        }
        try {
            long timeout = number(canonicalization.get("timeout_ms"),
                    RdfDatasetFingerprints.DEFAULT_TIMEOUT_MS);
            RdfDatasetFingerprint fingerprint = RdfDatasetFingerprints.compute(root, null, timeout);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("profile", interoperability.get("profile"));
            details.put("additional_profiles", interoperability.get("additional_profiles"));
            details.put("ro_crate_format", metadata.get("format"));
            details.put("manifest_path", path(policy, "interoperability_manifest"));
            details.put("root_artifact", interoperability.get("root_artifact"));
            details.put("canonicalization_algorithm", fingerprint.canonicalizationAlgorithm());
            details.put("hash_algorithm", fingerprint.hashAlgorithm());
            details.put("scope", fingerprint.scope());
            details.put("rdf_dataset_fingerprint", fingerprint.rdfDatasetFingerprint());
            details.put("canonical_nquads_bytes", fingerprint.canonicalNQuadsBytes());
            return new QcStageExecution("interoperability", QcStageVerdict.PASS, null, details);
        } catch (RuntimeException error) {
            return QcStageExecution.error("interoperability",
                    "RDF dataset canonicalization failed: " + message(error),
                    Map.of("error", message(error)));
        }
    }

    /** Check both the full loaded closure and the root document's owned axioms against an OWL profile. */
    public static QcStageExecution profile(OWLOntology root, String profileName, int limit) {
        if (root == null) {
            throw new IllegalArgumentException("root must not be null");
        }
        if (limit < 0 || limit > 10_000) {
            throw new IllegalArgumentException("limit must be between 0 and 10000");
        }
        final Profiles profile;
        try {
            profile = profile(profileName);
        } catch (IllegalArgumentException invalid) {
            return QcStageExecution.error("profile", invalid.getMessage(),
                    Map.of("owl_profile", String.valueOf(profileName)));
        }
        if (profile == null) {
            return QcStageExecution.skipped("profile",
                    "profile check skipped (owl_profile=" + profileName + ")");
        }
        try {
            OWLOntology flattened = flatten(root);
            OWLProfileReport closure = profile.checkOntology(flattened);
            Set<OWLAxiom> ownedAxioms = root.getAxioms();
            Set<OWLEntity> ownedHeaderSignature = new java.util.LinkedHashSet<>();
            for (OWLAnnotation annotation : root.getAnnotations()) {
                ownedHeaderSignature.addAll(annotation.getSignature());
            }
            List<OWLProfileViolation> owned = new ArrayList<>();
            int imported = 0;
            for (OWLProfileViolation violation : closure.getViolations()) {
                OWLAxiom axiom = violationAxiom(violation);
                boolean rootOwned = axiom == null
                        ? axiomlessOwned(violation, ownedHeaderSignature)
                        : ownedAxioms.contains(axiom);
                if (rootOwned) {
                    owned.add(violation);
                } else {
                    imported++;
                }
            }
            List<String> examples = owned.stream().map(Object::toString)
                    .sorted().limit(limit).toList();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("profile", normalizeProfile(profileName));
            details.put("in_profile", closure.isInProfile());
            details.put("owned_in_profile", owned.isEmpty());
            details.put("violations", owned.size());
            details.put("imported_violations", imported);
            details.put("examples", examples);
            if (owned.size() > examples.size()) {
                details.put("truncated", owned.size() - examples.size());
            }
            return new QcStageExecution("profile",
                    owned.isEmpty() ? QcStageVerdict.PASS : QcStageVerdict.FAIL,
                    null, details);
        } catch (RuntimeException error) {
            return QcStageExecution.error("profile", "OWL profile check failed: " + message(error),
                    Map.of("error", message(error)));
        }
    }

    /** Classify one snapshot with a private reasoner and dispose it on every completion path. */
    public static QcStageExecution reasoner(OWLOntology root, OWLReasonerFactory factory,
            String requiredReasoner, long timeoutMs, int limit) {
        return reason(root, factory, requiredReasoner, timeoutMs, limit, null, false).execution();
    }

    /**
     * Classify once and optionally materialize inferred query data before disposing the reasoner.
     * {@code timeoutMs} is one wall-clock budget for both classification and requested materialization.
     */
    public static ReasoningOutcome reason(OWLOntology root, OWLReasonerFactory factory,
            String requiredReasoner, long timeoutMs, int limit, QuerySnapshot querySnapshot,
            boolean materializeInferences) {
        if (materializeInferences && querySnapshot == null) {
            throw new IllegalArgumentException(
                    "querySnapshot is required when materializeInferences is true");
        }
        if (materializeInferences && !querySnapshot.capturedFrom(root)) {
            throw new IllegalArgumentException(
                    "querySnapshot was not captured from the current root import closure");
        }
        if (root == null || factory == null) {
            return unavailable(QcStageExecution.skipped("reasoner",
                    "no headless reasoner is available"), querySnapshot, materializeInferences,
                    "No headless reasoner is available.");
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeout_ms must be positive");
        }
        if (limit < 0 || limit > 10_000) {
            throw new IllegalArgumentException("limit must be between 0 and 10000");
        }
        String discoveredName;
        try {
            discoveredName = factory.getReasonerName();
        } catch (RuntimeException error) {
            return unavailable(QcStageExecution.error("reasoner",
                            "could not inspect headless reasoner: " + message(error),
                            Map.of("error", message(error))),
                    querySnapshot, materializeInferences,
                    "The headless reasoner configuration could not be inspected: "
                            + message(error));
        }
        final String name = displayReasonerName(factory, discoveredName);
        if (requiredReasoner != null && !requiredReasoner.isBlank()
                && !ReasonerNames.resolve(requiredReasoner, List.of(
                        new ReasonerNames.Candidate(name, factory.getClass().getName()))).unique()) {
            String reason = "policy requires reasoner '" + requiredReasoner
                    + "' but headless runtime provides '" + name + "'";
            return unavailable(QcStageExecution.error("reasoner", reason,
                            Map.of("required_reasoner", requiredReasoner,
                                    "available_reasoner", name)),
                    querySnapshot, materializeInferences, reason);
        }

        AtomicReference<OWLReasoner> running = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        FutureTask<ReasoningOutcome> task = new FutureTask<>(
                () -> classify(root, factory, name, timeoutMs, limit, running, cancelled,
                        querySnapshot, materializeInferences));
        Thread worker = new Thread(task, "protege-mcp-headless-qc-reasoner");
        worker.setDaemon(true);
        worker.start();
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            cancelled.set(true);
            task.cancel(true);
            dispose(running);
            String operation = materializeInferences
                    ? "classification and inference materialization" : "classification";
            String reason = operation + " timed out after " + timeoutMs + " ms";
            return unavailable(QcStageExecution.error("reasoner", reason,
                            Map.of("reasoner", name, "timeout_ms", timeoutMs)),
                    querySnapshot, materializeInferences, reason);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cancelled.set(true);
            task.cancel(true);
            dispose(running);
            return unavailable(QcStageExecution.error("reasoner",
                            "classification was interrupted", Map.of("reasoner", name)),
                    querySnapshot, materializeInferences, "classification was interrupted");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            String reason = "classification failed: " + message(cause);
            return unavailable(QcStageExecution.error("reasoner", reason,
                            Map.of("reasoner", name, "error", message(cause))),
                    querySnapshot, materializeInferences, reason);
        }
    }

    private static ReasoningOutcome classify(OWLOntology root, OWLReasonerFactory factory,
            String name, long timeoutMs, int limit, AtomicReference<OWLReasoner> running,
            AtomicBoolean cancelled, QuerySnapshot querySnapshot,
            boolean materializeInferences) {
        OWLReasoner reasoner = null;
        try {
            reasoner = factory.createReasoner(root, new SimpleConfiguration(timeoutMs));
            running.set(reasoner);
            if (cancelled.get()) {
                return unavailable(QcStageExecution.error("reasoner",
                                "classification was cancelled", Map.of("reasoner", name)),
                        querySnapshot, materializeInferences, "classification was cancelled");
            }
            boolean consistent = reasoner.isConsistent();
            Set<org.semanticweb.owlapi.model.OWLClass> unsatisfiable = consistent
                    ? reasoner.getUnsatisfiableClasses().getEntitiesMinusBottom() : Set.of();
            List<String> items = unsatisfiable.stream().map(value -> value.getIRI().toString())
                    .sorted().limit(limit).toList();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reasoner", name);
            details.put("reasoner_configuration", Map.of(
                    "reasoner_name", name,
                    "factory_class", factory.getClass().getName()));
            details.put("consistent", consistent);
            details.put("unsatisfiable_count", unsatisfiable.size());
            details.put("unsatisfiable_classes", Map.of(
                    "count", unsatisfiable.size(), "items", items,
                    "truncated", unsatisfiable.size() > items.size()));
            String swrlWarning = SwrlReasonerSupport.ignoredWarning(
                    root.getImportsClosure(), name, factory.getClass().getName());
            if (swrlWarning != null) {
                details.put("warning", swrlWarning);
                details.put("swrl_ignored", true);
            }
            QcStageExecution execution = new QcStageExecution("reasoner",
                    consistent && unsatisfiable.isEmpty()
                            ? QcStageVerdict.PASS : QcStageVerdict.FAIL,
                    null, details);
            QuerySnapshot completed = querySnapshot;
            if (materializeInferences) {
                if (!consistent) {
                    completed = querySnapshot.withInferenceError(
                            "The isolated ontology is inconsistent; inferred query data cannot be "
                                    + "materialized soundly.");
                } else {
                    try {
                        completed = querySnapshot.withInferences(reasoner);
                    } catch (RuntimeException materializationFailure) {
                        completed = querySnapshot.withInferenceError(
                                "Inference materialization failed after classification completed: "
                                        + message(materializationFailure));
                    }
                }
            }
            return new ReasoningOutcome(execution, completed);
        } catch (RuntimeException error) {
            String failure = "classification failed: " + message(error);
            return unavailable(QcStageExecution.error("reasoner", failure,
                            Map.of("reasoner", name, "error", message(error))),
                    querySnapshot, materializeInferences, failure);
        } finally {
            if (reasoner != null && running.compareAndSet(reasoner, null)) {
                reasoner.dispose();
            }
        }
    }

    private static ReasoningOutcome unavailable(QcStageExecution execution,
            QuerySnapshot querySnapshot, boolean materializeInferences, String reason) {
        QuerySnapshot completed = materializeInferences
                ? querySnapshot.withInferenceError(reason) : querySnapshot;
        return new ReasoningOutcome(execution, completed);
    }

    private static void dispose(AtomicReference<OWLReasoner> running) {
        OWLReasoner reasoner = running.getAndSet(null);
        if (reasoner != null) {
            try {
                reasoner.dispose();
            } catch (RuntimeException ignored) {
                // The stage result already records the primary timeout/interruption.
            }
        }
    }

    private static OWLOntology flatten(OWLOntology source) {
        org.semanticweb.owlapi.model.OWLOntologyManager manager =
                org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager();
        try {
            OWLOntology copy = manager.createOntology(source.getOntologyID());
            for (OWLOntology member : source.getImportsClosure()) {
                manager.addAxioms(copy, member.getAxioms());
                member.getAnnotations().forEach(annotation -> manager.applyChange(
                        new org.semanticweb.owlapi.model.AddOntologyAnnotation(copy, annotation)));
            }
            return copy;
        } catch (org.semanticweb.owlapi.model.OWLOntologyCreationException error) {
            throw new IllegalStateException("could not flatten the profile snapshot", error);
        }
    }

    private static OWLAxiom violationAxiom(OWLProfileViolation violation) {
        try {
            return violation.getAxiom();
        } catch (IllegalStateException absent) {
            return null;
        }
    }

    private static boolean axiomlessOwned(OWLProfileViolation violation,
            Set<OWLEntity> ownedHeaderSignature) {
        if (violation instanceof UndeclaredEntityViolation undeclared) {
            return ownedHeaderSignature.contains(undeclared.getEntity());
        }
        return true;
    }

    private static Profiles profile(String value) {
        return switch (normalizeProfile(value)) {
            case "DL" -> Profiles.OWL2_DL;
            case "EL" -> Profiles.OWL2_EL;
            case "QL" -> Profiles.OWL2_QL;
            case "RL" -> Profiles.OWL2_RL;
            case "FULL", "NONE" -> null;
            default -> throw new IllegalArgumentException("unsupported OWL profile: " + value);
        };
    }

    private static String normalizeProfile(String value) {
        if (value == null || value.isEmpty()) {
            return "DL";
        }
        return value.trim().toUpperCase(Locale.ROOT)
                .replace("OWL2_", "").replace("OWL2", "")
                .replace("OWL 2 ", "").replace("-", "").replace(" ", "");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String path(ProjectPolicy policy, String key) {
        List<java.nio.file.Path> paths = policy.assets().getOrDefault(key, List.of());
        return paths.size() == 1 ? paths.get(0).toString() : null;
    }

    private static long number(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    static String displayReasonerName(OWLReasonerFactory factory, String discoveredName) {
        String className = factory.getClass().getName();
        if (className.startsWith("org.semanticweb.HermiT.")) return "HermiT";
        return discoveredName == null || discoveredName.isBlank() ? className : discoveredName;
    }
}
