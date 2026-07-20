package io.github.hakjuoh.protege_mcp.core.qc;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.semanticweb.owlapi.formats.PrefixDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

import io.github.hakjuoh.protege_mcp.contracts.OntologyFingerprint;
import io.github.hakjuoh.protege_mcp.core.qc.CompetencyQuestionService.Question;
import io.github.hakjuoh.protege_mcp.core.qc.InvariantQcService.Invariant;
import io.github.hakjuoh.protege_mcp.core.workspace.ProjectWorkspace;
import io.github.hakjuoh.protege_mcp.core.workspace.WorkspaceSnapshot;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;

/** Complete policy-backed QC execution over one offline, lifecycle-owned workspace snapshot. */
public final class HeadlessProjectQcService {

    private static final List<String> ALL_STAGES = List.of(
            "interoperability", "reasoner", "profile", "governance", "structural",
            "invariants", "cqs", "shacl");
    private static final long DEFAULT_TIMEOUT_MS = 120_000L;

    private HeadlessProjectQcService() {
    }

    /** Aggregated gate plus the stable project-QC public envelope. */
    public record Result(ProjectQcReport report, Map<String, Object> output) {
        public Result {
            if (report == null || output == null) {
                throw new IllegalArgumentException("report and output must not be null");
            }
            output = Collections.unmodifiableMap(new LinkedHashMap<>(output));
        }
    }

    /** Capture, execute, drift-check, and close one headless project snapshot. */
    public static Result run(ProjectWorkspace workspace, OWLReasonerFactory reasonerFactory,
            int limit, LocalDate today) throws IOException {
        if (workspace == null || today == null) {
            throw new IllegalArgumentException("workspace and today must not be null");
        }
        if (limit < 0 || limit > 10_000) {
            throw new IllegalArgumentException("limit must be between 0 and 10000");
        }
        try (WorkspaceSnapshot snapshot = workspace.capture()) {
            return execute(workspace, snapshot, reasonerFactory, limit, today);
        }
    }

    /** Execute against a caller-owned open snapshot so a larger workflow can reuse the exact bytes. */
    public static Result runCaptured(ProjectWorkspace workspace, WorkspaceSnapshot snapshot,
            OWLReasonerFactory reasonerFactory, int limit, LocalDate today) {
        if (workspace == null || snapshot == null || today == null) {
            throw new IllegalArgumentException("workspace, snapshot, and today must not be null");
        }
        if (limit < 0 || limit > 10_000) {
            throw new IllegalArgumentException("limit must be between 0 and 10000");
        }
        return execute(workspace, snapshot, reasonerFactory, limit, today);
    }

    private static Result execute(ProjectWorkspace workspace, WorkspaceSnapshot snapshot,
            OWLReasonerFactory reasonerFactory, int limit, LocalDate today) {
        ProjectPolicy policy = snapshot.policy();
        if (!policy.valid()) {
            throw new IllegalArgumentException("workspace snapshot requires a valid project policy");
        }
        Configuration config = Configuration.from(policy, limit);
        OWLOntology root = snapshot.root();
        Set<OWLOntology> closure = snapshot.closure();
        List<QcStageExecution> executions = new ArrayList<>();

        List<Invariant> invariants = List.of();
        String invariantAssetError = null;
        if (config.stages.contains("invariants")) {
            try {
                invariants = ValidationAssetLoader.loadInvariants(
                        snapshot.capturedAssets().getOrDefault("invariants", List.of()));
            } catch (RuntimeException error) {
                invariantAssetError = message(error);
            }
        }

        List<Question> questions = List.of();
        String questionAssetError = null;
        if (config.stages.contains("cqs")) {
            try {
                questions = ValidationAssetLoader.loadQuestions(policy,
                        snapshot.capturedAssets(), root);
            } catch (RuntimeException error) {
                questionAssetError = message(error);
            }
        }

        boolean needInferences = invariants.stream().anyMatch(Invariant::includeInferred)
                || questions.stream().anyMatch(Question::includeInferred);
        boolean needQueries = config.stages.contains("invariants")
                || config.stages.contains("cqs") || config.stages.contains("shacl");
        QuerySnapshot queries = null;
        String querySnapshotError = null;
        if (needQueries) {
            try {
                queries = QuerySnapshot.capture(root, closure, prefixes(root, policy));
            } catch (RuntimeException error) {
                querySnapshotError = message(error);
            }
        }

        HeadlessQcStageService.ReasoningOutcome reasoning = null;
        if (config.stages.contains("reasoner") || needInferences) {
            boolean materialize = needInferences && queries != null;
            reasoning = HeadlessQcStageService.reason(root, reasonerFactory,
                    config.requiredReasoner, config.timeoutMs, limit,
                    materialize ? queries : null, materialize);
            if (materialize) queries = reasoning.querySnapshot();
        }
        QcStageExecution swrlError = null;
        if (reasoning != null && reasoning.execution().details() != null
                && Boolean.TRUE.equals(reasoning.execution().details().get("swrl_ignored"))) {
            swrlError = QcStageExecution.error("reasoner",
                    String.valueOf(reasoning.execution().details().get("warning")),
                    reasoning.execution().details());
        }

        ImportGraphAnalysisService.Report imports = ImportGraphAnalysisService.analyze(root);
        List<Map<String, Object>> projectChecks = new ArrayList<>(
                ModuleGovernanceService.moduleChecks(policy,
                        snapshot.capturedAssets().getOrDefault("modules", List.of()), limit));
        projectChecks.addAll(ModuleGovernanceService.importChecks(
                imports.cycles(), imports.conflicts(), limit));

        for (String stage : ALL_STAGES) {
            if ("reasoner".equals(stage) && swrlError != null) {
                executions.add(swrlError);
                continue;
            }
            if (!config.stages.contains(stage)) continue;
            switch (stage) {
                case "interoperability" -> executions.add(safe(stage,
                        () -> HeadlessQcStageService.interoperability(root, policy)));
                case "reasoner" -> executions.add(reasoning == null
                        ? QcStageExecution.skipped(stage, "no headless reasoner was scheduled")
                        : reasoning.execution());
                case "profile" -> executions.add(safe(stage,
                        () -> HeadlessQcStageService.profile(root, config.profile, limit)));
                case "governance" -> executions.add(safe(stage, () ->
                        GovernanceQcService.evaluate(root, closure, config.iriPattern,
                                config.requiredNamespaces, config.requiredAnnotations, true,
                                config.governanceRules, today, projectChecks, limit,
                                GovernanceQcService.Presentation.canonical()).execution()));
                case "structural" -> executions.add(safe(stage, () ->
                        StructuralQcService.evaluate(root, closure, config.disabledStructural,
                                config.structuralSeverity, true).execution()));
                case "invariants" -> executions.add(invariantExecution(queries, invariants,
                        invariantAssetError, querySnapshotError, Math.max(limit, 1000),
                        config.timeoutMs));
                case "cqs" -> executions.add(questionExecution(queries, questions,
                        questionAssetError, querySnapshotError, Math.max(limit, 1000),
                        config.timeoutMs));
                case "shacl" -> executions.add(shaclExecution(queries,
                        snapshot.capturedAssets().getOrDefault("shacl", List.of()),
                        querySnapshotError, Math.max(limit, 1000), config.timeoutMs));
                default -> throw new IllegalStateException("unsupported QC stage: " + stage);
            }
        }
        executions = executions.stream()
                .map(execution -> portableExecution(snapshot, execution)).toList();

        OntologyFingerprint fingerprint = snapshot.fingerprint();
        String selectedReasoner = reasonerName(reasonerFactory);
        List<String> snapshotStages = executions.stream()
                .filter(execution -> execution.verdict().ran()
                        && !execution.verdict().executionError())
                .map(QcStageExecution::stage).toList();
        ProjectQcRequest request = new ProjectQcRequest(true, config.policyVersion,
                policy.digest(), new ArrayList<>(config.stages), config.failOn, fingerprint,
                selectedReasoner, workspace.isCurrent(snapshot), null, "isolated",
                snapshotStages, true, snapshot.closureFingerprint());
        ProjectQcReport report = ProjectQcService.aggregate(request, executions);
        Map<String, Object> output = new LinkedHashMap<>(report.toMap());
        output.put("project_id", policy.effective().get("project_id"));
        output.put("policy_path", portablePath(policy, policy.path()));
        output.put("project_root", ".");
        output.put("resolved_assets", assetJson(policy));
        output.put("surface", "headless");
        return new Result(report, output);
    }

    private static QcStageExecution invariantExecution(QuerySnapshot queries,
            List<Invariant> invariants, String assetError, String snapshotError,
            int limit, long timeoutMs) {
        if (assetError != null) return stageError("invariants", "could not load invariants", assetError);
        if (snapshotError != null) return stageError("invariants",
                "could not prepare the shared query snapshot", snapshotError);
        return safe("invariants", () -> {
            QcStageExecution execution = InvariantQcService.evaluate(
                    queries, invariants, limit, timeoutMs).execution();
            if (number(execution.details(), "errors") > 0
                    || number(execution.details(), "inference_caveats") > 0) {
                return QcStageExecution.error("invariants",
                        "one or more required invariants could not execute with complete inferred data",
                        execution.details());
            }
            return execution;
        });
    }

    private static QcStageExecution questionExecution(QuerySnapshot queries,
            List<Question> questions, String assetError, String snapshotError,
            int limit, long timeoutMs) {
        if (assetError != null) return stageError("cqs",
                "could not load competency questions", assetError);
        if (snapshotError != null) return stageError("cqs",
                "could not prepare the shared query snapshot", snapshotError);
        return safe("cqs", () -> {
            QcStageExecution execution = CompetencyQuestionService.evaluate(
                    queries, questions, limit, timeoutMs).execution();
            if (number(execution.details(), "errors") > 0
                    || number(execution.details(), "inference_caveats") > 0) {
                return QcStageExecution.error("cqs",
                        "one or more required competency questions could not execute with their "
                                + "requested data", execution.details());
            }
            return execution;
        });
    }

    private static QcStageExecution shaclExecution(QuerySnapshot queries, List<Path> shapes,
            String snapshotError, int limit, long timeoutMs) {
        if (snapshotError != null) return stageError("shacl",
                "could not prepare the shared query snapshot", snapshotError);
        return safe("shacl", () -> ShaclValidationService.evaluatePolicy(
                queries.assertedTurtle(), shapes, limit, timeoutMs).execution());
    }

    private static QcStageExecution safe(String stage,
            java.util.function.Supplier<QcStageExecution> action) {
        try {
            return action.get();
        } catch (RuntimeException error) {
            return stageError(stage, stage + " execution failed", message(error));
        }
    }

    private static QcStageExecution stageError(String stage, String summary, String error) {
        String detail = error == null || error.isBlank() ? summary : error;
        return QcStageExecution.error(stage, summary + ": " + detail,
                Map.of("error", detail));
    }

    private static int number(Map<String, Object> details, String key) {
        if (details == null) return 0;
        Object value = details.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static Map<String, String> prefixes(OWLOntology root, ProjectPolicy policy) {
        Map<String, String> prefixes = new LinkedHashMap<>();
        OWLDocumentFormat format = root.getOWLOntologyManager().getOntologyFormat(root);
        if (format != null && format.isPrefixOWLOntologyFormat()) {
            PrefixDocumentFormat prefixed = format.asPrefixOWLOntologyFormat();
            prefixes.putAll(prefixed.getPrefixName2PrefixMap());
        }
        object(policy.effective().get("prefixes")).forEach((name, value) -> {
            if (value instanceof String namespace) {
                prefixes.put(name.endsWith(":") ? name : name + ":", namespace);
            }
        });
        prefixes.putIfAbsent("rdf:", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
        prefixes.putIfAbsent("rdfs:", "http://www.w3.org/2000/01/rdf-schema#");
        prefixes.putIfAbsent("owl:", "http://www.w3.org/2002/07/owl#");
        prefixes.putIfAbsent("xsd:", "http://www.w3.org/2001/XMLSchema#");
        return prefixes;
    }

    private static String reasonerName(OWLReasonerFactory factory) {
        if (factory == null) return null;
        try {
            String name = factory.getReasonerName();
            return HeadlessQcStageService.displayReasonerName(factory, name);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static Map<String, List<String>> assetJson(ProjectPolicy policy) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        policy.assets().forEach((key, paths) -> result.put(key,
                paths.stream().map(path -> portablePath(policy, path)).toList()));
        return result;
    }

    private static QcStageExecution portableExecution(WorkspaceSnapshot snapshot,
            QcStageExecution execution) {
        @SuppressWarnings("unchecked")
        Map<String, Object> details = execution.details() == null ? null
                : (Map<String, Object>) portableValue(snapshot, execution.details());
        String message = execution.message() == null ? null
                : portableString(snapshot, execution.message());
        return new QcStageExecution(execution.stage(), execution.verdict(), message, details);
    }

    private static Object portableValue(WorkspaceSnapshot snapshot, Object value) {
        if (value instanceof String string) return portableString(snapshot, string);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), portableValue(snapshot, item)));
            return copy;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> portableValue(snapshot, item)).toList();
        }
        return value;
    }

    private static String portableString(WorkspaceSnapshot snapshot, String value) {
        String result = value;
        for (var source : snapshot.sources()) {
            String portable = portablePath(snapshot.policy(), source.original());
            result = result.replace(source.captured().toString(), portable)
                    .replace(source.captured().toUri().toString(), portable);
        }
        Path root = snapshot.policy().projectRoot().toAbsolutePath().normalize();
        String rootPath = root.toString();
        result = result.replace(rootPath + java.io.File.separator, "")
                .replace(root.toUri().toString(), "");
        return result;
    }

    private static String portablePath(ProjectPolicy policy, Path path) {
        Path root = policy.projectRoot().toAbsolutePath().normalize();
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(root)) return absolute.getFileName().toString();
        Path relative = root.relativize(absolute);
        return relative.getNameCount() == 0 ? "." : relative.toString().replace('\\', '/');
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private record Configuration(Set<String> stages, String failOn, String profile,
            long timeoutMs, String requiredReasoner, Pattern iriPattern,
            List<String> requiredNamespaces, List<String> requiredAnnotations,
            PolicyGovernanceService.Rules governanceRules, Set<String> disabledStructural,
            Map<String, String> structuralSeverity, int policyVersion) {

        static Configuration from(ProjectPolicy policy, int limit) {
            Map<String, Object> root = policy.effective();
            Map<String, Object> validation = object(root.get("validation"));
            Set<String> stages = normalizeStages(strings(validation.get("required_stages")));
            Map<String, Object> reasoning = object(root.get("reasoning"));
            if (Boolean.TRUE.equals(reasoning.get("required"))) stages.add("reasoner");
            if (stages.isEmpty()) {
                throw new IllegalArgumentException(
                        "validation.required_stages must not be empty for project QC");
            }
            String failOn = normalizeFailOn(string(validation.get("fail_on")));
            String profile = normalizeProfile(string(reasoning.get("owl_profile")));
            long timeout = integer(reasoning.get("timeout_ms"), DEFAULT_TIMEOUT_MS);
            if (timeout <= 0) throw new IllegalArgumentException("reasoning.timeout_ms must be positive");
            Map<String, Object> iri = object(root.get("iri_policy"));
            Pattern iriPattern = compilePattern(string(iri.get("pattern")));
            Map<String, Object> structural = object(validation.get("structural"));
            return new Configuration(Collections.unmodifiableSet(stages), failOn, profile,
                    timeout, string(reasoning.get("reasoner")), iriPattern,
                    strings(iri.get("required_namespaces")), annotationRequirements(root),
                    HeadlessProjectQcService.governanceRules(root),
                    new LinkedHashSet<>(strings(structural.get("disabled"))),
                    stringMap(object(structural.get("severity_overrides"))),
                    ((Number) root.get("version")).intValue());
        }
    }

    private static Set<String> normalizeStages(List<String> requested) {
        Set<String> result = new LinkedHashSet<>();
        for (String stage : requested) {
            String normalized = stage.trim().toLowerCase(Locale.ROOT);
            if (!ALL_STAGES.contains(normalized)) {
                throw new IllegalArgumentException("unknown required stage: " + stage);
            }
            result.add(normalized);
        }
        return result;
    }

    private static String normalizeFailOn(String value) {
        if (value == null || "error".equals(value)) return "error";
        if ("warning".equals(value) || "warn".equals(value)) return "warn";
        if ("info".equals(value) || "none".equals(value)) return value;
        throw new IllegalArgumentException("unknown policy fail_on: " + value);
    }

    private static String normalizeProfile(String value) {
        if (value == null || value.isBlank()) return "DL";
        String normalized = value.trim().toUpperCase(Locale.ROOT)
                .replace("OWL2_", "").replace("OWL2", "")
                .replace("OWL 2 ", "").replace("-", "").replace(" ", "");
        return switch (normalized) {
            case "DL", "EL", "QL", "RL" -> normalized;
            case "NONE", "FULL" -> "none";
            default -> throw new IllegalArgumentException("unknown owl_profile: " + value);
        };
    }

    private static Pattern compilePattern(String value) {
        if (value == null) return null;
        try {
            return Pattern.compile(value);
        } catch (PatternSyntaxException error) {
            throw new IllegalArgumentException("iri_policy.pattern is invalid: "
                    + error.getMessage(), error);
        }
    }

    private static List<String> annotationRequirements(Map<String, Object> root) {
        Map<String, Object> annotations = object(root.get("annotations"));
        List<String> requirements = new ArrayList<>(strings(annotations.get("required")));
        Map<String, Object> labels = object(annotations.get("labels"));
        if (!strings(labels.get("required_languages")).isEmpty()
                || Boolean.TRUE.equals(labels.get("one_preferred_per_language"))) {
            requirements.addAll(strings(labels.get("properties")));
        }
        Map<String, Object> definitions = object(annotations.get("definitions"));
        if (Boolean.TRUE.equals(definitions.get("required"))
                || !strings(definitions.get("required_languages")).isEmpty()) {
            requirements.addAll(strings(definitions.get("properties")));
        }
        Map<String, Object> prefixes = object(root.get("prefixes"));
        return new LinkedHashSet<>(requirements).stream()
                .map(reference -> expandReference(reference, prefixes)).toList();
    }

    private static PolicyGovernanceService.Rules governanceRules(Map<String, Object> root) {
        Map<String, Object> prefixes = object(root.get("prefixes"));
        Map<String, Object> annotations = object(root.get("annotations"));
        Map<String, Object> labels = object(annotations.get("labels"));
        Map<String, Object> definitions = object(annotations.get("definitions"));
        Map<String, Object> lifecycle = object(root.get("lifecycle"));
        Map<String, Object> validation = object(root.get("validation"));
        String status = string(lifecycle.get("status_property"));
        List<PolicyGovernanceService.Waiver> waivers = new ArrayList<>();
        for (Map<String, Object> waiver : objects(validation.get("waivers"))) {
            String expires = string(waiver.get("expires"));
            waivers.add(new PolicyGovernanceService.Waiver(string(waiver.get("rule_id")),
                    string(waiver.get("focus_iri")), string(waiver.get("reason")),
                    string(waiver.get("owner")),
                    expires == null ? null : LocalDate.parse(expires)));
        }
        return new PolicyGovernanceService.Rules(
                iris(strings(labels.get("properties")), prefixes),
                new LinkedHashSet<>(strings(labels.get("required_languages"))),
                Boolean.TRUE.equals(labels.get("one_preferred_per_language")),
                iris(strings(definitions.get("properties")), prefixes),
                Boolean.TRUE.equals(definitions.get("required")),
                new LinkedHashSet<>(strings(definitions.get("required_languages"))),
                status == null ? null : IRI.create(expandReference(status, prefixes)),
                new LinkedHashSet<>(strings(lifecycle.get("allowed_values"))),
                new LinkedHashSet<>(strings(lifecycle.get("deprecated_values"))),
                iris(strings(lifecycle.get("replaced_by_properties")), prefixes),
                !Boolean.FALSE.equals(lifecycle.get("require_replacement")), waivers);
    }

    private static List<IRI> iris(List<String> references, Map<String, Object> prefixes) {
        return references.stream().map(reference -> IRI.create(
                expandReference(reference, prefixes))).toList();
    }

    private static String expandReference(String reference, Map<String, Object> prefixes) {
        int colon = reference.indexOf(':');
        if (colon > 0 && !Set.of("http", "https", "urn", "file")
                .contains(reference.substring(0, colon).toLowerCase(Locale.ROOT))) {
            String namespace = string(prefixes.get(reference.substring(0, colon)));
            if (namespace == null) {
                throw new IllegalArgumentException("unknown policy prefix in " + reference);
            }
            return namespace + reference.substring(colon + 1);
        }
        return reference;
    }

    private static Map<String, String> stringMap(Map<String, Object> values) {
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, String.valueOf(value)));
        return result;
    }

    private static long integer(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static String string(Object value) {
        return value instanceof String string ? string : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        return value instanceof List<?> ? (List<String>) value : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(Object value) {
        return value instanceof List<?> ? (List<Map<String, Object>>) value : List.of();
    }
}
